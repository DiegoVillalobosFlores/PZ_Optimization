package pzopt;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import zombie.GameTime;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.weather.ClimateManager;

/**
 * Speculars and sky for the HDR output (Hdr, tune {@code glint}): what daylight lacked. PZ draws water and puddles with
 * their own shaders, which know the surface normal (the water's animated waves, the puddles' rain ripples) but cap
 * everything at the 8-bit world buffer. Those shaders get a second output here (ShaderUnit hook, Hdr.patchShader): the
 * HDR part the surface would reflect - a sun glint (Blinn-Phong on the wave normal, the sun from the time of day),
 * the sky (Fresnel, bright at grazing angles, tinted by the hour and dimmed by clouds and rain), and at night the lamps,
 * torches and headlights over the water (the light map at the pixel) - into an RGBA8 target attached to the world
 * framebuffer as colour attachment 1: rgb = g / (1 + g) (the glint, reversible), a = the finished pixel's luminance.
 *
 * The attachment is only a draw buffer during a glint-only pass that draws the water and the puddles once more after the
 * moving objects (colour writes off; unpatched shaders would broadcast gl_FragColor into it) and for each car body draw.
 * The depth test keeps what stands in front of the water. Anything drawn over it later (the visibility shade, a tree,
 * rain) cannot clear it, so the composite adds the glint only where the final world colour still matches the luminance
 * stored with it.
 */
public final class HdrGlint {
   private HdrGlint() {
   }

   static final int GLINT_UNIT = 4, WORLD_UNIT = 3;
   private static int tex, texW, texH, attachedFbo;
   // render thread only: the first water / puddle draw after a composite clears the target; the composite trusts it only
   // if something drew into it since (the game thread runs a frame ahead, so no frame counters across threads)
   private static boolean needClear = true, drawnSinceComposite;
   /** sun direction in the water shaders' frame (y up, x screen right, z screen down) + strength; sky colour + strength */
   static final float[] sun = new float[4], sky = new float[4];
   /** how strong the sun is now: daylight x clear sky x above the horizon (0 at night, in rain, under cloud) */
   static volatile float sunStrength;
   /** the climate's daylight strength (0 night .. 1 noon): caps the frame-average night key (Hdr.pzHdrNight) */
   static volatile float daylight = 1F;

   /** Game thread, once a frame (Hdr.queueWorldStats, every output path): the climate's daylight. */
   static void updateDaylight() {
      ClimateManager cm = ClimateManager.getInstance();
      daylight = cm != null ? Math.max(0F, Math.min(1F, cm.getDayLightStrength())) : 1F;
   }

   /** Game thread, once a frame (Hdr.queueWorldStats): the sun and the sky of this hour and weather. */
   static void update() {
      Hdr.Tune t = Hdr.tune;
      if (t.glint <= 0F && t.sunMax <= 1F) {
         sun[3] = 0F;
         sky[3] = 0F;
         sunStrength = 0F;
         return;
      }
      float hour = GameTime.getInstance() != null ? GameTime.getInstance().getTimeOfDay() : 12F;
      ClimateManager cm = ClimateManager.getInstance();
      float day = cm != null ? Math.max(0F, Math.min(1F, cm.getDayLightStrength())) : 1F;
      float cloud = cm != null ? Math.max(0F, Math.min(1F, cm.getCloudIntensity())) : 0F;
      float rain = cm != null ? Math.max(0F, Math.min(1F, cm.getPrecipitationIntensity())) : 0F;
      // the sun's path: east at 6 h, south at noon, west at 18 h; in the water frame east is screen right-down,
      // south screen left-down (PZ: +x east, +y south)
      double a = Math.PI * (hour - 6.0) / 12.0;
      double elev = Math.max(0.0, Math.sin(a)) * Math.toRadians(55.0);
      double ex = 0.894, ez = 0.447, sx = -0.894, sz = 0.447;
      double dx = ex * Math.cos(a) + sx * Math.sin(a), dz = ez * Math.cos(a) + sz * Math.sin(a);
      double lx = dx * Math.cos(elev), ly = Math.sin(elev), lz = dz * Math.cos(elev);
      float moonGlint = 0F;
      if (!"arc".equals(Config.SKY_PATH)) {
         // the real sky (pzopt.Sky): the sun where it stands; under the horizon, the moon's glitter on the water at night
         Sky.update(-1F);
         double[] w = Sky.sun;
         elev = Math.toRadians(Sky.sunElevDeg);
         if (Sky.sunElevDeg <= 0.0 && Sky.moonElevDeg > 0.0) {
            w = Sky.moon;
            elev = Math.toRadians(Sky.moonElevDeg);
            float dark = (float)Math.max(0.0, Math.min(1.0, (-Sky.sunElevDeg - 4.0) / 8.0));
            moonGlint = dark * (float)Sky.moonBrightness * 0.35F; // a full moon's glint ~a third of the sun's (the HDR exposure is set for the night)
         }
         lx = w[0] * ex + w[1] * sx;
         ly = w[2];
         lz = w[0] * ez + w[1] * sz;
         elev = Math.max(0.0, elev);
      }
      // seen from above, a physical sun rarely mirrors into the camera; lean it toward the mirror direction of the view
      // (0, 0.62, -0.78) so the waves glitter, and let the hour move the glints
      double mx = 0.0, my = 0.62, mz = -0.78, k = t.sunLean;
      double gx = mx * k + lx * (1 - k), gy = my * k + ly * (1 - k), gz = mz * k + lz * (1 - k);
      double gl = Math.sqrt(gx * gx + gy * gy + gz * gz);
      float clear = (1F - 0.85F * cloud) * (1F - 0.7F * rain);
      sunStrength = day * clear * (float)Math.min(1.0, elev / Math.toRadians(15.0));
      sun[0] = (float)(gx / gl);
      sun[1] = (float)(gy / gl);
      sun[2] = (float)(gz / gl);
      sun[3] = t.glint * t.sunGlint * (moonGlint > 0F ? moonGlint : day) * clear * (float)Math.min(1.0, elev / Math.toRadians(8.0));
      // sky: blue-white by day, warmer near the horizon hours, a deep blue at night; overcast greys it
      float low = (float)Math.max(0.0, 1.0 - elev / Math.toRadians(20.0)) * day;
      float r = 0.78F + 0.30F * low, g = 0.88F - 0.10F * low, b = 1.0F - 0.35F * low;
      float grey = cloud * 0.8F;
      sky[0] = (r * (1 - grey) + 0.85F * grey) * day + 0.02F * (1 - day);
      sky[1] = (g * (1 - grey) + 0.85F * grey) * day + 0.03F * (1 - day);
      sky[2] = (b * (1 - grey) + 0.85F * grey) * day + 0.06F * (1 - day);
      sky[3] = t.glint * t.skyReflect * (0.35F + 0.65F * day) * (1F - 0.4F * rain);
   }

   /**
    * Game thread, FBORenderCell after the moving objects and the water: the water and the puddles once more, only into
    * the glint target (colour and depth writes off). They read the world colour as it is then (a texture barrier makes
    * their own blend visible) and store its luminance beside the glint, so the composite drops the glint wherever a
    * later draw (the visibility shade, a tree, a zombie) changed the pixel; the depth test hides what already stands on
    * them. Only the surface shaders draw while the target is on (unpatched shaders broadcast gl_FragColor into it).
    */
   public static boolean queueGlintOnly() {
      if (Hdr.active && !Hdr.alphaGain && Hdr.tune.glint > 0F) {
         SpriteRenderer.instance.drawGeneric(GLINT_ONLY);
         return true;
      }
      return false;
   }

   private static boolean depthWasOn;

   private static final TextureDraw.GenericDrawer GLINT_ONLY = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         try {
            on();
            if (drawnSinceComposite) {
               GL30.glColorMaski(0, false, false, false, false);
               depthWasOn = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
               GL11.glDepthMask(false);
               GL45.glTextureBarrier();
               TextureFBO world = Core.getInstance().getOffscreenBuffer();
               Texture wt = (Texture)world.getTexture();
               nowInv[0] = 1F / wt.getWidthHW();
               nowInv[1] = 1F / wt.getHeightHW();
               int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
               GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOW_UNIT);
               GL11.glBindTexture(GL11.GL_TEXTURE_2D, wt.getID());
               GL13.glActiveTexture(prevActive);
               glintOnly = true;
            }
         } catch (Throwable t) {
            Log.warn("hdr glint: glint-only pass failed, glints off: " + t);
            Hdr.tune.glint = 0F;
         }
      }
   };

   private static boolean glintOnly;

   /** Render thread: is the water / puddle draw now the glint-only pass (Ssr skips its lookups there)? */
   static boolean glintOnlyNow() {
      return glintOnly;
   }
   /** the unit the surface shaders read the world colour from in the glint-only pass (free in the water / puddle shaders) */
   static final int NOW_UNIT = 8;
   private static final float[] nowInv = new float[2];

   public static void queueOff() {
      if (Hdr.active && !Hdr.alphaGain && Hdr.tune.glint > 0F) {
         SpriteRenderer.instance.drawGeneric(OFF);
      }
   }

   private static final TextureDraw.GenericDrawer OFF = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         int fbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         if (fbo != 0 && fbo == attachedFbo) {
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
         }
         restoreBlend();
         if (glintOnly) {
            glintOnly = false;
            int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + NOW_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(prevActive);
            GL30.glColorMaski(0, true, true, true, true);
            GL11.glDepthMask(depthWasOn);
         }
      }
   };

   static long onCalls, onOtherFbo, clears, composites, compositesValid;
   private static long lastLogMs;

   private static void on() {
      onCalls++;
      long now = System.currentTimeMillis();
      if (now - lastLogMs > 10_000L) {
         lastLogMs = now;
         Log.info("hdr glint: on " + onCalls + " (other framebuffer " + onOtherFbo + "), clears " + clears + ", composites " + compositesValid + "/" + composites
               + ", sun " + String.format("%.2f,%.2f,%.2f x%.2f", sun[0], sun[1], sun[2], sun[3]) + ", sky x" + String.format("%.2f", sky[3]));
      }
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || world.getTexture() == null) {
         return;
      }
      int worldFbo = FogPass.fboId(world);
      int bound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      if (worldFbo <= 0 || bound != worldFbo) {
         onOtherFbo++;
         return; // water drawn somewhere else (a chunk texture bake): no glint there
      }
      Texture wt = (Texture)world.getTexture();
      if (tex == 0 || texW != wt.getWidthHW() || texH != wt.getHeightHW() || attachedFbo != worldFbo) {
         if (tex != 0) {
            GL11.glDeleteTextures(tex);
         }
         texW = wt.getWidthHW();
         texH = wt.getHeightHW();
         tex = GL11.glGenTextures();
         int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, texW, texH, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
         Texture.lastTextureID = -1;
         GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, tex, 0);
         attachedFbo = worldFbo;
         Log.info("hdr glint: " + texW + "x" + texH + " RGBA8 glint target on the world framebuffer " + worldFbo);
      }
      GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
      // the glint replaces: blending off for buffer 1 (the surfaces' own non-indexed glBlendFunc(SRC_ALPHA, ...) comes after
      // this and would weight the glint by the stored luminance; the game never touches the indexed enable)
      GL30.glDisablei(GL11.GL_BLEND, 1);
      if (needClear) {
         needClear = false;
         GL30.glClearBufferfv(GL11.GL_COLOR, 1, new float[] {0F, 0F, 0F, 0F});
         clears++;
      }
      drawnSinceComposite = true;
   }

   private static boolean vehicleOn;

   /**
    * Render thread, Model.drawVehicle right before a car body's draw (its program bound): the glint target as a second
    * draw buffer for this one draw, plus the uniforms of the patched vehicle shader. Characters and everything else
    * drawn in the same pass never get it.
    */
   public static boolean vehicleOn(boolean vehicleShader) {
      if (!vehicleShader || !Hdr.active || Hdr.alphaGain || Hdr.tune.glint <= 0F) {
         return false;
      }
      try {
         int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
         int u = prog != 0 ? GL20.glGetUniformLocation(prog, "pzHdrCar") : -1;
         if (u < 0) {
            return false; // not a patched vehicle shader
         }
         on();
         if (!drawnSinceComposite) {
            return false;
         }
         Hdr.Tune t = Hdr.tune;
         GL20.glUniform4f(u, t.glint * t.carGlass, t.glint * t.carPaint, t.glint * t.vehLamp, t.glintShine * 0.5F);
         vehicleOn = true;
         return true;
      } catch (Throwable th) {
         Log.warn("hdr glint: vehicle glint failed, glints off: " + th);
         Hdr.tune.glint = 0F;
         return false;
      }
   }

   public static void vehicleOff() {
      if (vehicleOn) {
         vehicleOn = false;
         GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
         restoreBlend();
      }
   }

   /** buffer 1's blend enable back in step with buffer 0's (a later non-indexed glEnable / glDisable sets both anyway) */
   private static void restoreBlend() {
      if (GL30.glIsEnabledi(GL11.GL_BLEND, 0)) {
         GL30.glEnablei(GL11.GL_BLEND, 1);
      }
   }

   /** Render thread, the composite: the glint target + the world texture on their units, valid only when cleared this frame. */
   static boolean bindForComposite() {
      boolean ok = tex != 0 && drawnSinceComposite;
      composites++;
      compositesValid += ok ? 1 : 0;
      drawnSinceComposite = false;
      needClear = true;
      if (ok) {
         TextureFBO world = Core.getInstance().getOffscreenBuffer();
         int prevActive = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
         org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + GLINT_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
         org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + WORLD_UNIT);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, world != null && world.getTexture() != null ? ((Texture)world.getTexture()).getID() : 0);
         org.lwjgl.opengl.GL13.glActiveTexture(prevActive);
         Texture.lastTextureID = -1;
      }
      return ok;
   }

   /** Render thread, WaterShader.updateWaterParams / the puddle shaders (program bound): sun, sky, light map, mapping. */
   public static void surfaceUniforms() {
      if (!Hdr.active || Hdr.alphaGain) {
         return;
      }
      int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      if (prog == 0) {
         return;
      }
      int uSun = GL20.glGetUniformLocation(prog, "pzHdrSun");
      if (uSun < 0) {
         return; // not a patched shader
      }
      GL20.glUniform4f(uSun, sun[0], sun[1], sun[2], sun[3]);
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrSky"), sky[0], sky[1], sky[2], sky[3]);
      Hdr.Tune t = Hdr.tune;
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrGlintP"), t.glintShine, t.glint > 0F ? 1F : 0F, t.glint * t.lampGlint, 0F);
      GL20.glUniform1i(GL20.glGetUniformLocation(prog, "pzHdrNow"), NOW_UNIT);
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrNowP"), glintOnly ? 1F : 0F, 0F, nowInv[0], nowInv[1]);
      Hdr.surfaceLightUniforms(prog);
   }
}
