package pzopt;

import java.util.IdentityHashMap;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.skinnedmodel.ModelManager;
import zombie.iso.IsoCamera;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;

/**
 * Per-object motion vectors for the temporal upscalers (`upscalerObjectMv`, docs/plan-upscalers.md): characters
 * and vehicles are 3D models drawn through {@code TextureDraw.drawModel}; the camera's motion alone would make
 * them ghost, so each model drawn in a frame gets a stencil id (written into the world framebuffer's stencil
 * where its pixels land, low 7 bits, the tree bit untouched) and an entry with its screen rectangle and its
 * screen motion since the previous frame. {@link Dlss} draws the entry's motion into the motion-vector image
 * where the stencil matches the id, on top of the camera motion.
 *
 * <p>The game thread records entries while it queues the frame's model draws; the resolve of that frame (one
 * frame later on the render thread) reads them through the frame slot handed to its resolver.
 */
public final class ObjectMotion {
   private ObjectMotion() {
   }

   /** Stencil ids 1..126 for objects (bit 7 is the game's tree mask; 127 is the water, {@link #WATER_ID}). */
   static final int MAX_IDS = 126;
   /** The stencil id of the water surface (dlssWaterCurrent): ModelManager.RenderWater writes it, {@link Dlss} masks it. */
   static final int WATER_ID = 127;
   private static final int SLOTS = 4;

   /** One frame's entries: rectangles in screen pixels (x, y, w, h; y down, relative to the player rect) and motion (dx, dy) in screen pixels from the current to the previous position. */
   static final class Frame {
      int count;
      final float[] rect = new float[MAX_IDS * 4];
      final float[] motion = new float[MAX_IDS * 2];
      long frameNo;

      void clear() {
         this.count = 0;
      }
   }

   private static final Frame[] FRAMES = new Frame[SLOTS];
   private static int slot;
   private static long frameNo;
   private static final IdentityHashMap<Object, float[]> LAST = new IdentityHashMap<>(); // object -> (screenX, screenY, frameNo)
   private static long lastPruned;

   static {
      for (int i = 0; i < SLOTS; i++) {
         FRAMES[i] = new Frame();
      }
   }

   /** Object motion is recorded when a temporal upscaler is active and the key is on. */
   static boolean enabled() {
      return Config.UPSCALER_OBJECT_MV && RenderScale.active() && Upscaler.drawsOutput() && !"fsr1".equals(RenderScale.mode());
   }

   /**
    * Game thread, from TextureDraw.drawModel: records the model's object and returns its stencil id for the
    * frame (0 = no entry: not enabled, no object, or the frame's 126 ids are used up).
    */
   public static int record(ModelManager.ModelSlot modelSlot) {
      if (!enabled() || modelSlot == null) {
         return 0;
      }
      IsoMovingObject object = modelSlot.character;
      if (object == null && modelSlot.model != null) {
         object = modelSlot.model.object;
      }
      if (object == null) {
         return 0;
      }
      Frame f = FRAMES[slot];
      if (f.count >= MAX_IDS) {
         return 0;
      }
      int playerIndex = IsoCamera.frameState.playerIndex;
      PlayerCamera camera = IsoCamera.cameras[playerIndex];
      float zoom = camera.zoom <= 0.0F ? 1.0F : camera.zoom;
      float x = object.getX(), y = object.getY(), z = object.getZ();
      float sx = (IsoUtils.XToScreen(x, y, z, 0) - camera.getOffX()) / zoom;
      float sy = (IsoUtils.YToScreen(x, y, z, 0) - camera.getOffY()) / zoom;
      float[] last = LAST.get(object);
      float dx = 0.0F, dy = 0.0F;
      boolean known = last != null && last[2] == frameNo - 1;
      if (known) {
         dx = last[0] - sx;
         dy = last[1] - sy;
      }
      if (last == null) {
         last = new float[3];
         LAST.put(object, last);
      }
      last[0] = sx;
      last[1] = sy;
      last[2] = frameNo;
      if (!known) {
         return 0; // first sight: no motion yet, the camera motion applies
      }
      // the footprint in world pixels around the feet: a tile is 64 * tileScale wide, a level 96 * tileScale tall
      float tile = 64.0F * Core.tileScale;
      float halfW, up, down;
      if (object instanceof IsoGameCharacter) {
         halfW = tile * 0.6F;
         up = tile * 1.7F;
         down = tile * 0.3F;
      } else {
         halfW = tile * 2.6F;
         up = tile * 1.8F;
         down = tile * 1.6F;
      }
      int i = f.count++;
      f.rect[i * 4] = sx - halfW / zoom;
      f.rect[i * 4 + 1] = sy - up / zoom;
      f.rect[i * 4 + 2] = 2.0F * halfW / zoom;
      f.rect[i * 4 + 3] = (up + down) / zoom;
      f.motion[i * 2] = dx;
      f.motion[i * 2 + 1] = dy;
      return i + 1;
   }

   /**
    * Game thread, when the frame's resolve is queued: the frame's entries are handed over and a fresh slot starts
    * for the next frame's draws. Returns the finished frame (read by the render thread only).
    */
   static Frame endFrame() {
      Frame done = FRAMES[slot];
      done.frameNo = frameNo;
      frameNo++;
      slot = (slot + 1) % SLOTS;
      FRAMES[slot].clear();
      if (frameNo - lastPruned > 600) {
         lastPruned = frameNo;
         LAST.values().removeIf(v -> v[2] < frameNo - 120);
      }
      return done;
   }

   // --- render thread: the stencil id written while the model draws (TextureDraw case DrawModel) ---------------

   /** Before a model draw with an id: the model's covered pixels get the id in the stencil's low 7 bits (depth pass only). */
   public static void beginStencil(int id) {
      org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
      org.lwjgl.opengl.GL11.glStencilMask(0x7F);
      org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_ALWAYS, id, 0x7F);
      org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_REPLACE);
   }

   /** After it: the game's cached stencil state is put back. */
   public static void endStencil() {
      zombie.core.opengl.GLStateRenderThread.StencilFunc.restore();
      zombie.core.opengl.GLStateRenderThread.StencilOp.restore();
      zombie.core.opengl.GLStateRenderThread.StencilMask.restore();
      zombie.core.opengl.GLStateRenderThread.StencilTest.restore();
   }

   /**
    * Render thread, ModelManager.RenderWater inside its glPushAttrib / glPopAttrib (which puts the stencil state back):
    * the water's pixels get {@link #WATER_ID} while the world pass of player 0 draws under DLSS. Objects drawn over the
    * water later write their own id; a stand-in on a chunk-texture bake or another player's pass is left alone.
    */
   public static void beginWaterStencil() {
      if (!Dlss.waterMasked()) {
         waterOff++;
         return;
      }
      if (!RenderScale.inWorldPass() || RenderScale.worldPassPlayer() != 0) {
         waterElsewhere++;
         return;
      }
      waterTagged++;
      waterTaggedThisFrame++;
      beginStencil(WATER_ID);
   }

   /** Render thread, the dlss stats line: water draws tagged / skipped because the feature was off / drawn outside player 0's world pass. */
   static long waterTagged, waterOff, waterElsewhere;
   static int waterTaggedThisFrame; // reset by the resolve

   /** Clears everything (a resolution change, a mode switch). */
   static void reset() {
      for (Frame f : FRAMES) {
         f.clear();
      }
      LAST.clear();
   }
}
