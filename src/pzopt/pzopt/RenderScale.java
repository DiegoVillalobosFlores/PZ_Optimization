package pzopt;

import java.util.Locale;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL41;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;

/**
 * The world pass at a fraction of the screen size (docs/plan-upscalers.md). The game renders the world through
 * an orthographic projection in world pixels into a viewport the size of the player's screen rectangle; shrinking
 * that viewport renders the same content into fewer pixels of the same offscreen texture, and {@link Upscaler}
 * resolves the small image back to the screen rectangle before the stock screen shader runs.
 *
 * <p>Everything here concerns the render thread only. The game thread keeps its full-size notion of the screen
 * (culling, chunk work, UI, mouse); the render thread sees the scaled rectangle wherever the world pass restores
 * a viewport (TextureDraw {@code glDoStartFrame} / {@code glDoStartFrameNoZoom} / {@code glViewport}) or asks
 * {@link IsoCamera} for the screen rectangle while the world framebuffer is bound.
 *
 * <p>A sub-pixel jitter (temporal upscalers) is applied as a fractional viewport offset
 * ({@code glViewportIndexedf}), which moves every draw of the frame whatever projection it uses.
 */
public final class RenderScale {
   private RenderScale() {
   }

   /** The upscaler chosen for this session, lower case; "off" when none. */
   public static final String MODE = Overrides.enabled() ? Config.UPSCALER : "off";
   /** Render scale per axis, 1.0 when off. */
   public static final float SCALE = computeScale();
   private static final boolean ACTIVE = !"off".equals(MODE) && SCALE < 1.0F || "dlss".equals(MODE) || "xess".equals(MODE);

   private static volatile boolean disabled; // a failure at run time (missing extension, shim, shader) switches the pass off for the session
   private static volatile String fallbackMode; // a temporal upscaler that cannot run (no RTX, no shim, no Vulkan) continues as fsr1 at the same scale

   static {
      if (ACTIVE) {
         Log.info("upscaler: " + MODE + " at " + Math.round(SCALE * 100.0F) + " % (" + Config.UPSCALER_QUALITY + (Config.UPSCALER_SCALE_PCT > 0 ? ", upscalerScalePct=" + Config.UPSCALER_SCALE_PCT : "") + ")");
      } else if (!"off".equals(MODE)) {
         Log.info("upscaler: " + MODE + " requested but the render scale is 100 %: off");
      }
   }

   // render-thread state
   private static boolean worldPass; // between a scaled glDoStartFrame with a player index and the next end-of-frame
   private static int worldPassPlayer = -1;
   private static int worldFboId; // the world framebuffer of that pass: the scaled view only applies while it is bound
   private static boolean suspendedQueued; // a queued suspend marker (thumbnail frames render unscaled)
   private static float jitterX; // pixels, the sub-pixel offset the next world pass starts with (temporal upscalers)
   private static float jitterY;
   private static float frameJitterX; // the offset the world pass being drawn was started with
   private static float frameJitterY;
   private static long frames;

   private static float computeScale() {
      if (!Overrides.enabled() || "off".equals(Config.UPSCALER)) {
         return 1.0F;
      }
      int pct = Config.UPSCALER_SCALE_PCT;
      if (pct >= 10 && pct <= 100) {
         return pct / 100.0F;
      }
      switch (Config.UPSCALER_QUALITY) {
         case "native": case "dlaa": case "100": return 1.0F;
         case "balanced": return 0.58F; // DLSS's balanced size (2970x1253 at 5120x2160)
         case "performance": return 0.5F;
         case "ultra": case "ultra-performance": case "ultraperformance": return 0.33333334F;
         case "quality": default: return 0.6666667F;
      }
   }

   /** The scaled world pass is on for this session (an upscaler is selected and nothing failed). */
   public static boolean active() {
      return ACTIVE && !disabled;
   }

   public static float scale() {
      return active() ? SCALE : 1.0F;
   }

   public static String mode() {
      if (!active()) {
         return "off";
      }
      String f = fallbackMode;
      return f != null ? f : MODE;
   }

   /** The selected mode cannot run here: continue with another one at the same scale (logged once). */
   public static void fallback(String mode, String why) {
      if (fallbackMode == null && !mode.equals(MODE)) {
         fallbackMode = mode;
         Log.warn("upscaler: " + MODE + " unavailable (" + why + "); using " + mode + " at " + Math.round(scale() * 100.0F) + " %");
      }
   }

   /** A quality name for the log / overlay: "fsr1 67 %". */
   public static String describe() {
      return mode() + " " + Math.round(scale() * 100.0F) + " %";
   }

   /** Turn the pass off for the rest of the session (logged once). */
   public static void disable(String why) {
      if (!disabled) {
         disabled = true;
         worldPass = false;
         Log.warn("upscaler off: " + why);
      }
   }

   static int px(int screenPixels) {
      return Math.max(1, Math.round(screenPixels * scale()));
   }

   /**
    * A screen-pixel size or origin fed to a world-pass shader that maps gl_FragCoord through it (fog screenInfo /
    * cameraInfo on the game thread): scaled whenever the pass is active, since every world frame renders scaled.
    */
   public static float scaledPx(float screenPixels) {
      return active() ? screenPixels * scale() : screenPixels;
   }

   /** Same for a value computed on the render thread inside the world pass (water / puddle WViewport, particles). */
   public static float viewPx(float screenPixels) {
      return scaledView() ? screenPixels * scale() : screenPixels;
   }

   /** The view-cone blur's displaySize (VisibilityPolygon2): scaled like its screenSize / displayOrigin inside the scaled world pass. */
   public static float visBlurPx(float screenPixels) {
      return Config.DEV_UPSCALER_STOCK_VIS_BLUR ? screenPixels : viewPx(screenPixels);
   }

   static int pxFloor(int screenPixels) {
      return (int)(screenPixels * scale());
   }

   // --- render thread -------------------------------------------------------------------------------------------

   public static boolean onRenderThread() {
      return Thread.currentThread() == RenderThread.renderThread;
   }

   /** The render thread is inside a scaled world pass (the world framebuffer is bound and the viewport is scaled). */
   public static boolean inWorldPass() {
      return worldPass && active() && TextureFBO.lastID == worldFboId;
   }

   public static int worldPassPlayer() {
      return worldPassPlayer;
   }

   private static boolean worldFboBound(int player) {
      Core core = Core.getInstance();
      TextureFBO fbo = core.getOffscreenBuffer(player < 0 ? 0 : player);
      if (fbo == null || TextureFBO.lastID != fbo.getBufferId()) {
         return false;
      }
      worldFboId = fbo.getBufferId();
      return true;
   }

   /**
    * After the stock {@code DoStartFrameStuff} / {@code DoStartFrameNoZoom} of a world frame (render thread): when the
    * world framebuffer is bound the viewport and scissor become the scaled player rectangle. {@code player} is the
    * index the frame was started with (-1 = a full-screen frame, never scaled).
    */
   public static void afterStartFrame(int player) {
      if (player < 0 || !active()) {
         worldPass = false;
         return;
      }
      if (suspendedQueued || !worldFboBound(player)) {
         worldPass = false;
         return;
      }
      worldPass = true;
      worldPassPlayer = player;
      frameJitterX = jitterX;
      frameJitterY = jitterY;
      applyWorldViewport(player);
   }

   /**
    * After a model draw (render thread): ModelSlotRenderData.renderToImposterCard restores the viewport it read as
    * integers, which drops the fractional jitter for the rest of the frame; put the jittered viewport back.
    */
   public static void afterModelDraw() {
      if (worldPass && (frameJitterX != 0.0F || frameJitterY != 0.0F) && active() && TextureFBO.lastID == worldFboId) {
         int p = worldPassPlayer;
         float s = scale();
         GL41.glViewportIndexedf(0, (int)(fullLeft(p) * s) + frameJitterX, (int)(fullTop(p) * s) + frameJitterY, Math.max(1, Math.round(fullWidth(p) * s)), Math.max(1, Math.round(fullHeight(p) * s)));
      }
   }

   /** The stock end of a frame (render thread): the world pass is over until the next scaled start. */
   public static void afterEndFrame() {
      worldPass = false;
   }

   /** Sets the scaled viewport (with the current jitter) and scissor of a player's world rectangle. */
   public static void applyWorldViewport(int player) {
      int x = fullLeft(player), y = fullTop(player), w = fullWidth(player), h = fullHeight(player);
      float s = scale();
      int sx = (int)(x * s), sy = (int)(y * s);
      int sw = Math.max(1, Math.round(w * s)), sh = Math.max(1, Math.round(h * s));
      viewport(sx, sy, sw, sh);
      GL11.glScissor(sx, sy, sw, sh);
   }

   private static void viewport(int x, int y, int w, int h) {
      if (frameJitterX != 0.0F || frameJitterY != 0.0F) {
         GL41.glViewportIndexedf(0, x + frameJitterX, y + frameJitterY, w, h);
      } else {
         GL11.glViewport(x, y, w, h);
      }
   }

   /**
    * A viewport requested by the game thread (TextureDraw {@code glViewport}) while the render thread is in a scaled
    * world pass: a rectangle equal to a player's screen rectangle or to the whole screen is scaled like the world
    * viewport (IsoWorld's view-cone restore); anything else (the FX mask, the cone texture) passes through.
    */
   public static void requestedViewport(int x, int y, int w, int h) {
      if (inWorldPass() && isScreenRect(x, y, w, h)) {
         float s = scale();
         viewport((int)(x * s), (int)(y * s), Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
      } else {
         GL11.glViewport(x, y, w, h);
      }
   }

   private static boolean isScreenRect(int x, int y, int w, int h) {
      if (x == 0 && y == 0 && w == Core.width && h == Core.height) {
         return true;
      }
      for (int p = 0; p < IsoPlayer.numPlayers; p++) {
         if (x == fullLeft(p) && y == fullTop(p) && w == fullWidth(p) && h == fullHeight(p)) {
            return true;
         }
      }
      return false;
   }

   // The stock screen rectangle of a player (what IsoCamera returns on the game thread).
   static int fullLeft(int p) {
      return p == 1 || p == 3 ? Core.width / 2 : 0;
   }

   static int fullTop(int p) {
      return p == 2 || p == 3 ? Core.height / 2 : 0;
   }

   static int fullWidth(int p) {
      return IsoPlayer.numPlayers > 1 ? Core.width / 2 : Core.width;
   }

   static int fullHeight(int p) {
      return IsoPlayer.numPlayers > 2 ? Core.height / 2 : Core.height;
   }

   /** The scaled rectangle (x, y, w, h) of a player's world image inside the offscreen texture. */
   public static int[] scaledRect(int p) {
      float s = scale();
      return new int[]{(int)(fullLeft(p) * s), (int)(fullTop(p) * s), Math.max(1, Math.round(fullWidth(p) * s)), Math.max(1, Math.round(fullHeight(p) * s))};
   }

   // IsoCamera override: the render thread inside a scaled world pass sees the scaled rectangle.
   public static boolean scaledView() {
      return worldPass && active() && TextureFBO.lastID == worldFboId && onRenderThread();
   }

   public static int screenLeft(int p) {
      return (int)(fullLeft(p) * scale());
   }

   public static int screenTop(int p) {
      return (int)(fullTop(p) * scale());
   }

   public static int screenWidth(int p) {
      return Math.max(1, Math.round(fullWidth(p) * scale()));
   }

   public static int screenHeight(int p) {
      return Math.max(1, Math.round(fullHeight(p) * scale()));
   }

   // --- jitter (temporal upscalers) -----------------------------------------------------------------------------

   /** The jitter of the frame being drawn, in low-res pixels; (0, 0) for spatial upscalers. */
   public static void setJitter(float x, float y) {
      jitterX = x;
      jitterY = y;
   }

   /** The jitter the frame now being resolved was drawn with. */
   public static float frameJitterX() {
      return frameJitterX;
   }

   public static float frameJitterY() {
      return frameJitterY;
   }

   // --- suspend markers (queued on the game thread, honoured by the render thread in order) ---------------------

   /** Queued before a frame that must render unscaled (save thumbnails). */
   public static void suspendQueued() {
      suspendedQueued = true;
   }

   public static void resumeQueued() {
      suspendedQueued = false;
   }

   /** Drawers the game thread queues around such a frame (SavefileThumbnail.create), in order with its draws. */
   public static final zombie.core.textures.TextureDraw.GenericDrawer SUSPEND = new zombie.core.textures.TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         suspendQueued();
      }
   };
   public static final zombie.core.textures.TextureDraw.GenericDrawer RESUME = new zombie.core.textures.TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         resumeQueued();
      }
   };

   /** One line for the console: mode and scale, or off. */
   public static String settingsLine() {
      return String.format(Locale.ROOT, "upscaler=%s scale=%.3f quality=%s", mode(), scale(), Config.UPSCALER_QUALITY);
   }

   static long frames() {
      return frames;
   }

   static void countFrame() {
      frames++;
   }
}
