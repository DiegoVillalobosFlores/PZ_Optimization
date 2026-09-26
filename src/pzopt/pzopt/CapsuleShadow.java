package pzopt;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.LightingJNI;

/**
 * Soft shadows of characters and vehicles (Config {@code sunShadows} + {@code sunShadowCharacters} /
 * {@code sunShadowVehicles}, {@code sunShadowTorches}): every character drawn with a model this frame casts the shadow of
 * its body onto whatever the scene depth holds (ground, walls, furniture: the static world, the pass runs right after the
 * chunk composite, before any character is drawn), from the sun when it stands outdoors in daylight and from the torches
 * and headlights in reach at any hour.
 *
 * <p>The body is ten capsules between its bones (torso, head, thighs, calves, upper arms, forearms), the end points taken
 * on the bone worker right after the bones ({@code AnimationPlayer.pzoptPrecomputeShadow}, zombies) or here (the player,
 * animals); a vehicle is two body capsules side by side and the cabin. Per pixel of a caster's shadow area: the world
 * position from the scene depth (IsoDepthHelper's depth is linear in x + y + 2z, the screen gives x - y and x + y - 6z),
 * one bounding capsule first (a pixel in its full light is in full light), then Quilez's soft capsule shadow towards the
 * light (the closest approach of the ray to each segment over the distance travelled: a penumbra that widens with the
 * distance from the body, sharp where the feet touch the ground). The sun: one instanced draw, a quad per caster (the
 * screen box of its capsules and their projections on its floor along the sun); a receiver the static world already hides
 * from the sun takes no second shadow (8 depth taps towards the sun). Lights: one instanced draw of casters x lights, each
 * quad the box of the bounding capsule and its projection away from the light, the shadow's strength the light's share
 * of the pixel (PixelLight's fitted torch model x the darkness), the holder never shading their own torch. Blended as a
 * multiply onto the scene; the capsules come from a small float texture.
 */
public final class CapsuleShadow {
   /** The bones whose positions end the capsules. */
   static final String[] BONES = {"Bip01_Pelvis", "Bip01_Neck", "Bip01_Head", "Bip01_L_Thigh", "Bip01_L_Calf", "Bip01_L_Foot", "Bip01_R_Thigh",
      "Bip01_R_Calf", "Bip01_R_Foot", "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand"};
   public static final int POINTS = 15;
   /** Capsules as pairs of BONES indices; the head runs from the neck through the head bone and 60 % beyond it. */
   private static final int[] SEG_A = {0, 1, 3, 4, 6, 7, 9, 10, 12, 13};
   private static final int[] SEG_B = {1, 2, 4, 5, 7, 8, 10, 11, 13, 14};
   /** Radii in model metres (x 1.5 into squares). */
   private static final float[] RADIUS = {0.15F, 0.11F, 0.075F, 0.055F, 0.075F, 0.055F, 0.05F, 0.045F, 0.05F, 0.045F};
   static final int K = 10;
   /** Per caster: (unused), its bounding capsule (a xyz r, b xyz 0), its facts (floor z, kind, sun, alpha), then a (xyz, r) and b (xyz, 0) per capsule. */
   private static final int TEXELS = 4 + 2 * K;
   private static final int MAX = 1024;
   private static final int MAX_LIGHTS = 4;
   private static final int MAX_PAIRS = 2048;
   private static long lightPairs;
   private static final float METRIC_Z = 2.4494897F; // squares of height per level

   private static final Frame[] FRAMES = {new Frame(), new Frame(), new Frame(), new Frame()};
   private static int frameIndex;
   private static Frame current; // game thread: the frame the characters drawn now add their capsules to
   private static volatile boolean failed;
   private static volatile boolean lightsActive; // last frame had a torch or headlight in view (the workers compute end points)
   private static long drawn;
   private static long characters;
   private static long computedHere;
   private static long frames;
   private static long lightFrames;
   private static final float[] SCRATCH = new float[POINTS * 3];
   private static long alternateT0;

   private CapsuleShadow() {
   }

   /** Do the bone workers compute the capsule end points (the sun up, or a torch in view last frame)? */
   public static boolean wanted() {
      return Config.SUN_SHADOWS && Config.SUN_SHADOW_CHARACTERS && (SunShadow.dir[3] > 0F || lightsActive) && !failed;
   }

   static String stats() {
      return "capsule shadows: frames=" + frames + " (with lights " + lightFrames + ", caster x light quads " + lightPairs + ") characters=" + characters + " vehicles=" + vehicles + " atlas=" + atlas + " (end points on the game thread "
         + computedHere + ") draws=" + drawn + (failed ? " FAILED" : "");
   }

   /** The end points of BONES (relative to the character, metric) into out; false when the skeleton lacks one. Any thread. */
   public static boolean points(AnimationPlayer player, float[] out) {
      Object sd = player.getSkinningData();
      if (sd == null) {
         return false;
      }
      int[] idx = player.pzoptCapsuleBones;
      if (idx == null || player.pzoptCapsuleBonesOf != sd) {
         idx = new int[POINTS];
         for (int i = 0; i < POINTS; i++) {
            idx[i] = player.getSkinningBoneIndex(BONES[i], -1);
         }
         player.pzoptCapsuleBones = idx;
         player.pzoptCapsuleBonesOf = sd;
      }
      return ShadowPrep.capsulePoints(player, idx, out);
   }

   /**
    * Game thread, right after the chunk composite: a frame of capsule shadows is queued there (drawn before the characters
    * that follow); the characters drawn this frame add themselves to it.
    */
   public static void queue(int playerIndex) {
      current = null;
      if (!Overrides.enabled() || !Config.SUN_SHADOWS || !Config.SUN_SHADOW_CHARACTERS && !Config.SUN_SHADOW_VEHICLES || failed) {
         lightsActive = false;
         return;
      }
      if (Config.DEV_SUN_ALTERNATE > 0) {
         // dev: the pass (and the game thread's collection) on and off every period, from the first frame's wall clock;
         // harness/contact/alt.py splits pzopt-overlay.out's gpu_ms by the same clock
         long now = System.currentTimeMillis();
         if (alternateT0 == 0L) {
            alternateT0 = now;
            Log.info("capsule shadows: alternating every " + Config.DEV_SUN_ALTERNATE + " ms from epoch_ms " + now + " (on first)");
         }
         if (((now - alternateT0) / Config.DEV_SUN_ALTERNATE & 1L) == 1L) {
            return;
         }
      }
      Frame f = FRAMES[frameIndex & 3];
      int ox = (int)Math.floor(IsoCamera.frameState.camCharacterX), oy = (int)Math.floor(IsoCamera.frameState.camCharacterY);
      f.ox = ox;
      f.oy = oy;
      f.sunOn = SunShadow.dir[3] > 0F;
      Core core = Core.getInstance();
      f.zoom = core.getZoom(playerIndex);
      f.ts = Core.tileScale;
      f.screenW = IsoCamera.getScreenWidth(playerIndex);
      f.screenH = IsoCamera.getScreenHeight(playerIndex);
      zombie.iso.weather.ClimateManager cm = zombie.iso.weather.ClimateManager.getInstance();
      float day = cm == null ? 0F : Math.max(0F, Math.min(1F, cm.getDayLightStrength()));
      // a torch's shadow matters in the dark only: none above 90 % daylight (the bench pistol's weapon light is always on)
      f.lightStrength = Math.max(0F, Math.min(1F, (0.9F - day) / 0.6F)) * Math.max(0, Config.SUN_SHADOW_TORCH_PCT) / 100F;
      f.nl = Config.SUN_SHADOW_TORCHES && f.lightStrength > 0.02F ? gatherLights(f) : 0;
      lightsActive = f.nl > 0;
      if (!f.sunOn && f.nl == 0) {
         return;
      }
      frameIndex++;
      f.n = 0;
      f.offX = IsoCamera.getOffX();
      f.offY = IsoCamera.getOffY();
      f.d0 = IsoDepthHelper.getSquareDepthData(ox, oy, ox, oy, 0.0F).depthStart;
      System.arraycopy(SunShadow.world, 0, f.sun, 0, 4);
      f.strength = SunShadow.dir[3] * Math.max(0, Config.SUN_SHADOW_CHARACTER_PCT) / 100F;
      f.playerIndex = playerIndex;
      current = f;
      frames++;
      if (f.nl > 0) {
         lightFrames++;
      }
      SpriteRenderer.instance.drawGeneric(f);
   }

   /** The torches and headlights near the camera (LightingJNI's list for the frame), strongest reach first, MAX_LIGHTS at most. */
   private static int gatherLights(Frame f) {
      ArrayList<IsoGameCharacter.TorchInfo> torches = LightingJNI.pzoptTorches();
      if (torches == null || torches.isEmpty()) {
         return 0;
      }
      float cx = IsoCamera.frameState.camCharacterX, cy = IsoCamera.frameState.camCharacterY;
      float view = (f.screenW + 2.0F * f.screenH) * f.zoom / (64.0F * f.ts) + 4.0F;
      int n = 0;
      for (int i = 0; i < torches.size() && n < MAX_LIGHTS; i++) {
         IsoGameCharacter.TorchInfo t = torches.get(i);
         if (t.id == 0 || t.strength <= 0.05F || Math.abs(t.x - cx) > view + t.dist || Math.abs(t.y - cy) > view + t.dist) {
            continue;
         }
         float len = (float)Math.sqrt(t.angleX * t.angleX + t.angleY * t.angleY);
         if (len < 1e-4F) {
            continue;
         }
         boolean car = t.id >= 4096 || t.focusing > 0;
         boolean dup = false;
         for (int j = 0; j < n; j++) { // the game sends every lit item of a player at the same spot: one shadow
            if (Math.abs(f.lA[j * 4] - (t.x - f.ox)) < 0.05F && Math.abs(f.lA[j * 4 + 1] - (t.y - f.oy)) < 0.05F) {
               dup = true;
               break;
            }
         }
         if (dup) {
            continue;
         }
         int k = n * 4;
         f.lA[k] = t.x - f.ox;
         f.lA[k + 1] = t.y - f.oy;
         f.lA[k + 2] = t.z * METRIC_Z + (car ? 0.75F : 1.35F); // the lamp's height: a headlight, a torch in the hand
         f.lA[k + 3] = Math.max(1.0F, t.dist);
         f.lB[k] = t.angleX / len;
         f.lB[k + 1] = t.angleY / len;
         f.lB[k + 2] = t.cone ? t.dot : -2.0F;
         f.lB[k + 3] = t.strength;
         f.lK[n] = car ? 2F : 1F;
         n++;
      }
      return n;
   }

   /** Game thread, where a character's stock shadow is drawn: its capsules join this frame's pass. */
   public static void add(IsoGameCharacter chr) {
      Frame f = current;
      if (f == null || f.n >= MAX || chr == null || !Config.SUN_SHADOW_CHARACTERS) {
         return;
      }
      if (chr.isSeatedInVehicle()) {
         return; // a driver or passenger: the vehicle's capsules are the shadow there, like stock's renderShadow skipping them
      }
      IsoGridSquare sq = chr.getCurrentSquare();
      if (sq == null || !chr.hasAnimationPlayer()) {
         return;
      }
      // a character standing in the static world's shade casts no sun shadow of its own (the shade is already there): its
      // sun share through the grid (SunShadow's cached march, the same that darkens its model) scales the sun shadow
      float sunVis = f.sunOn && sq.isOutside() ? SunShadow.visibleAt(chr.getX(), chr.getY(), chr.getZ()) * CloudShadow.transmittanceAt(chr.getX(), chr.getY(), chr.getZ()) : 0F;
      boolean sun = sunVis > 0.05F;
      if (!sun && f.nl == 0) {
         return; // indoors or in the shade in daylight without a torch around: nothing to cast
      }
      float alpha = chr.getAlpha(f.playerIndex);
      if (alpha < 0.02F) {
         return; // out of sight: its shadow would give it away
      }
      AnimationPlayer ap = chr.getAnimationPlayer();
      if (ap == null || !ap.isReady()) {
         return;
      }
      float[] pts = ap.pzoptCapsules();
      if (pts == null) {
         if (!points(ap, SCRATCH)) {
            return;
         }
         pts = SCRATCH;
         computedHere++;
      }
      float cx = chr.getX() - f.ox, cy = chr.getY() - f.oy, cz = chr.getZ() * METRIC_Z;
      float[] caps = CAPS;
      for (int s = 0; s < K; s++) {
         int ia = SEG_A[s] * 3, ib = SEG_B[s] * 3;
         float ax = pts[ia], ay = pts[ia + 1], az = pts[ia + 2];
         float bx = pts[ib], by = pts[ib + 1], bz = pts[ib + 2];
         if (s == 1) { // the head: from the neck through the head bone and on
            bx += (bx - ax) * 0.6F;
            by += (by - ay) * 0.6F;
            bz += (bz - az) * 0.6F;
         }
         int o = s * 7;
         caps[o] = cx + ax;
         caps[o + 1] = cy + ay;
         caps[o + 2] = cz + az;
         caps[o + 3] = cx + bx;
         caps[o + 4] = cy + by;
         caps[o + 5] = cz + bz;
         caps[o + 6] = RADIUS[s] * 1.5F;
      }
      append(f, caps, K, cz, alpha, 0F, sun ? sunVis : 0F);
      characters++;
   }

   /**
    * Game thread, for a zombie drawn as an atlas sprite (no model, no bones: stock draws it no shadow): one upright capsule
    * of a standing body, so a horde at max zoom keeps its shadows where the near ones have their bone capsules.
    */
   public static void addAtlas(IsoGameCharacter chr) {
      Frame f = current;
      if (f == null || f.n >= MAX || chr == null || !Config.SUN_SHADOW_CHARACTERS || !Config.SUN_SHADOW_ATLAS) {
         return;
      }
      IsoGridSquare sq = chr.getCurrentSquare();
      if (sq == null) {
         return;
      }
      float sunVis = f.sunOn && sq.isOutside() ? SunShadow.visibleAt(chr.getX(), chr.getY(), chr.getZ()) * CloudShadow.transmittanceAt(chr.getX(), chr.getY(), chr.getZ()) : 0F;
      if (sunVis <= 0.05F && f.nl == 0) {
         return;
      }
      float alpha = chr.getAlpha(f.playerIndex);
      if (alpha < 0.02F) {
         return;
      }
      float cx = chr.getX() - f.ox, cy = chr.getY() - f.oy, cz = chr.getZ() * METRIC_Z;
      float[] caps = CAPS;
      caps[0] = cx;
      caps[1] = cy;
      caps[2] = cz + 0.3F;
      caps[3] = cx;
      caps[4] = cy;
      caps[5] = cz + 2.35F;
      caps[6] = 0.24F;
      append(f, caps, 1, cz, alpha, 0F, sunVis > 0.05F ? sunVis : 0F);
      atlas++;
   }

   private static long atlas;

   /** Game thread, where a vehicle's stock shadow is drawn: its body (two capsules side by side and the cabin) joins the pass. */
   public static void addVehicle(zombie.vehicles.BaseVehicle v) {
      Frame f = current;
      if (f == null || f.n >= MAX || v == null || !Config.SUN_SHADOW_VEHICLES || v.getScript() == null) {
         return;
      }
      IsoGridSquare sq = v.getCurrentSquare();
      if (sq == null) {
         return;
      }
      float sunVis = f.sunOn && sq.isOutside() ? SunShadow.visibleAt(v.getX(), v.getY(), v.getZ()) * CloudShadow.transmittanceAt(v.getX(), v.getY(), v.getZ()) : 0F;
      boolean sun = sunVis > 0.05F;
      if (!sun && f.nl == 0) {
         return;
      }
      float alpha = v.getAlpha(f.playerIndex);
      if (alpha < 0.02F) {
         return;
      }
      org.joml.Vector3f e = v.getScript().getExtents(); // width, height, length (squares)
      org.joml.Vector2f se = v.getScript().getShadowExtents(); // footprint width, length
      org.joml.Vector2f so = v.getScript().getShadowOffset();
      float w = se.x, len = se.y, h = e.y;
      if (w <= 0F || len <= 0F || h <= 0F) {
         return;
      }
      float cz = v.getZ() * METRIC_Z;
      float rb = Math.min(w * 0.25F, h * 0.3F); // the two body capsules
      float rc = Math.min(w * 0.4F, h * 0.28F); // the cabin
      float[] caps = CAPS;
      float[][] local = {
         {-w * 0.25F, rb + h * 0.08F, so.y - len * 0.5F + rb, -w * 0.25F, rb + h * 0.08F, so.y + len * 0.5F - rb, rb},
         {w * 0.25F, rb + h * 0.08F, so.y - len * 0.5F + rb, w * 0.25F, rb + h * 0.08F, so.y + len * 0.5F - rb, rb},
         {0F, h - rc, so.y - len * 0.22F, 0F, h - rc, so.y + len * 0.12F, rc}};
      org.joml.Vector3f p = VEC;
      for (int s = 0; s < 3; s++) {
         float[] c = local[s];
         for (int k = 0; k < 2; k++) {
            v.getWorldPos(so.x + c[k * 3], 0F, c[k * 3 + 2], p);
            caps[s * 7 + k * 3] = p.x - f.ox;
            caps[s * 7 + k * 3 + 1] = p.y - f.oy;
            caps[s * 7 + k * 3 + 2] = cz + c[k * 3 + 1];
         }
         caps[s * 7 + 6] = c[6];
      }
      append(f, caps, 3, cz, alpha, 1F, sun ? sunVis : 0F);
      vehicles++;
   }

   private static final float[] CAPS = new float[K * 7];
   private static final org.joml.Vector3f VEC = new org.joml.Vector3f();
   private static long vehicles;

   /**
    * One caster into the frame: its capsules (a xyz, b xyz, radius; metric, relative to the origin square), its bounding
    * capsule and its facts (floor z, kind, sun, alpha). Unused capsule slots get radius 0.
    */
   private static void append(Frame f, float[] caps, int count, float cz, float alpha, float kind, float sun) {
      float[] d = f.data;
      int base = f.n * TEXELS * 4;
      d[base] = d[base + 1] = d[base + 2] = d[base + 3] = 0F; // (unused: the vertex shaders build each quad from the bounding capsule)
      // the bounding capsule: along the longest axis of the end points' box, fat enough to hold every capsule (a pixel it
      // leaves in full light is in full light of all of them: the per-pixel test runs one capsule instead of ten there)
      float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y0 = Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
      for (int s = 0; s < count; s++) {
         for (int e = 0; e < 2; e++) {
            x0 = Math.min(x0, caps[s * 7 + e * 3]);
            x1 = Math.max(x1, caps[s * 7 + e * 3]);
            y0 = Math.min(y0, caps[s * 7 + e * 3 + 1]);
            y1 = Math.max(y1, caps[s * 7 + e * 3 + 1]);
            z0 = Math.min(z0, caps[s * 7 + e * 3 + 2]);
            z1 = Math.max(z1, caps[s * 7 + e * 3 + 2]);
         }
      }
      float mx = (x0 + x1) * 0.5F, my = (y0 + y1) * 0.5F, mz = (z0 + z1) * 0.5F;
      float ex = x1 - x0, ey = y1 - y0, ez = z1 - z0;
      float ax, ay, az, bx, by, bz; // the segment along the longest extent through the middle
      if (ez >= ex && ez >= ey) {
         ax = bx = mx; ay = by = my; az = z0; bz = z1;
      } else if (ex >= ey) {
         ay = by = my; az = bz = mz; ax = x0; bx = x1;
      } else {
         ax = bx = mx; az = bz = mz; ay = y0; by = y1;
      }
      float rb = 0F;
      for (int s = 0; s < count; s++) {
         for (int e = 0; e < 2; e++) {
            float px = caps[s * 7 + e * 3], py = caps[s * 7 + e * 3 + 1], pz = caps[s * 7 + e * 3 + 2];
            float qx = Math.max(Math.min(px, Math.max(ax, bx)), Math.min(ax, bx));
            float qy = Math.max(Math.min(py, Math.max(ay, by)), Math.min(ay, by));
            float qz = Math.max(Math.min(pz, Math.max(az, bz)), Math.min(az, bz));
            float dx = px - qx, dy = py - qy, dz = pz - qz;
            rb = Math.max(rb, (float)Math.sqrt(dx * dx + dy * dy + dz * dz) + caps[s * 7 + 6]);
         }
      }
      d[base + 4] = ax;
      d[base + 5] = ay;
      d[base + 6] = az;
      d[base + 7] = rb + 0.02F;
      d[base + 8] = bx;
      d[base + 9] = by;
      d[base + 10] = bz;
      d[base + 11] = 0F;
      d[base + 12] = cz;
      d[base + 13] = kind;
      d[base + 14] = sun; // the caster's sun share (0: no sun quad)
      d[base + 15] = alpha;
      for (int s = 0; s < K; s++) {
         int o = base + (4 + 2 * s) * 4;
         boolean has = s < count;
         d[o] = has ? caps[s * 7] : 0F;
         d[o + 1] = has ? caps[s * 7 + 1] : 0F;
         d[o + 2] = has ? caps[s * 7 + 2] : 0F;
         d[o + 3] = has ? caps[s * 7 + 6] : 0F;
         d[o + 4] = has ? caps[s * 7 + 3] : 0F;
         d[o + 5] = has ? caps[s * 7 + 4] : 0F;
         d[o + 6] = has ? caps[s * 7 + 5] : 0F;
         d[o + 7] = 0F;
      }
      f.n++;
   }

   private static final class Frame extends TextureDraw.GenericDrawer {
      int n;
      final float[] data = new float[MAX * TEXELS * 4];
      final float[] sun = new float[4];
      boolean sunOn;
      float strength;
      int nl; // lights
      final float[] lA = new float[MAX_LIGHTS * 4]; // x, y (relative to the origin square), z (metric, the lamp's height), reach
      final float[] lB = new float[MAX_LIGHTS * 4]; // direction x, y, cone cosine (-2: none), strength
      final float[] lK = new float[MAX_LIGHTS]; // 1 a handheld torch, 2 a vehicle light
      float lightStrength;
      float zoom;
      float ts;
      float offX;
      float offY;
      float screenW;
      float screenH;
      int ox;
      int oy;
      float d0;
      int playerIndex;

      @Override
      public void render() {
         if (this.n == 0 || failed) {
            return;
         }
         try {
            GL.draw(this);
         } catch (Throwable t) {
            failed = true;
            Log.warn("capsule shadows: " + t + "; off for the rest of the session");
         }
      }
   }

   private static final Gl GL = new Gl();

   private static final class Gl {
      private int program;
      private int lightProgram;
      private int dataTex;
      private int vao;
      private final int[] u = new int[8];
      private final int[] ul = new int[10];
      private int pairTex;
      private final float[] pairData = new float[MAX_PAIRS * 2];
      private FloatBuffer pairUpload;

      /**
       * The caster x light pairs worth a quad: the caster within the lamp's reach (plus its size) and inside its cone
       * (with the penumbra's margin), not the torch's holder, not a vehicle under its own headlights.
       */
      private int pairs(Frame f) {
         int n = 0;
         float[] d = f.data;
         for (int c = 0; c < f.n && n < MAX_PAIRS; c++) {
            int base = c * TEXELS * 4;
            float mx = (d[base + 4] + d[base + 8]) * 0.5F, my = (d[base + 5] + d[base + 9]) * 0.5F, r = d[base + 7];
            boolean vehicle = d[base + 13] > 0.5F;
            for (int l = 0; l < f.nl && n < MAX_PAIRS; l++) {
               float lx = f.lA[l * 4], ly = f.lA[l * 4 + 1], reach = f.lA[l * 4 + 3];
               float vx = mx - lx, vy = my - ly;
               float dl = (float)Math.sqrt(vx * vx + vy * vy);
               boolean car = f.lK[l] > 1.5F;
               if (dl > reach + r || !car && dl < 0.7F || car && vehicle && dl < 3.5F) {
                  continue;
               }
               float cone = f.lB[l * 4 + 2];
               if (cone > -1.5F && dl > r + 0.5F) {
                  // the cone test on the caster's nearest edge: the angle its radius adds, and the model's own ramp start
                  float cosToCaster = (vx * f.lB[l * 4] + vy * f.lB[l * 4 + 1]) / dl;
                  float spread = (float)Math.min(1.0, (r + 0.5F) / dl);
                  float c0 = car ? cone - 0.28F : cone + 0.025F;
                  // cos(a - s) >= c0 with a the angle to the caster, s the angle its size spans
                  double a = Math.acos(Math.max(-1F, Math.min(1F, cosToCaster))), sa = Math.asin(spread);
                  if (Math.cos(Math.max(0.0, a - sa)) < c0) {
                     continue;
                  }
               }
               this.pairData[n * 2] = c;
               this.pairData[n * 2 + 1] = l;
               n++;
            }
         }
         return n;
      }
      private int lastFbo = -1;
      private int lastDepth;
      private final int[] viewport = new int[4];
      private final float[] viewportF = new float[4];
      private FloatBuffer upload;
      private final FloatBuffer lightA = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
      private final FloatBuffer lightB = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
      private final FloatBuffer lightK = BufferUtils.createFloatBuffer(MAX_LIGHTS);
      // dev (devAoTiming): GPU time of the pass
      private int[] queries;
      private int slot;
      private long ns;
      private long timed;

      void draw(Frame f) {
         if (this.program == 0 && !this.init()) {
            failed = true;
            Log.warn("capsule shadows: shaders did not compile; off");
            return;
         }
         int sceneFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         if (sceneFbo != this.lastFbo) { // the attachment query is a round trip: once per scene framebuffer
            this.lastFbo = sceneFbo;
            this.lastDepth = sceneDepthTexture(sceneFbo);
         }
         int depthTex = this.lastDepth;
         GL11.glGetIntegerv(GL11.GL_VIEWPORT, this.viewport);
         GL11.glGetFloatv(GL11.GL_VIEWPORT, this.viewportF);
         if (depthTex == 0 || this.viewport[2] <= 0 || this.viewport[3] <= 0) {
            return;
         }
         if (Config.DEV_AO_TIMING) {
            if (this.queries == null) {
               this.queries = new int[16];
               GL15.glGenQueries(this.queries);
            }
            GL33.glQueryCounter(this.queries[this.slot * 2], GL33.GL_TIMESTAMP);
         }
         // window px -> (x - y, x + y - 6z) and depth -> x + y + 2z, relative to the origin square (PixelLight.mapping)
         double vx = this.viewportF[0], vy = this.viewportF[1], vw = this.viewportF[2], vh = this.viewportF[3];
         double sxPerPx = f.screenW / vw, syPerPx = f.screenH / vh;
         double a32 = 32.0 * f.ts, a16 = 16.0 * f.ts;
         float kA = (float)(sxPerPx * f.zoom / a32);
         float cA = (float)(((-vx * sxPerPx) * f.zoom + f.offX) / a32 - (f.ox - f.oy));
         float kB = (float)(-syPerPx * f.zoom / a16);
         float cB = (float)((((vy + vh) * syPerPx) * f.zoom + f.offY) / a16 - (f.ox + f.oy));
         float kC = (float)(-1.0 / PixelLight.DEPTH_PER_XY);
         float cC = (float)(f.d0 / PixelLight.DEPTH_PER_XY);

         int count = f.n * TEXELS * 4;
         this.upload.clear();
         this.upload.put(f.data, 0, count).flip();
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.dataTex);
         GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, TEXELS, f.n, GL11.GL_RGBA, GL11.GL_FLOAT, this.upload);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);

         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glDisable(GL11.GL_STENCIL_TEST);
         GL11.glDisable(GL11.GL_ALPHA_TEST);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(false);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_COLOR);
         GL11.glColorMask(true, true, true, false);
         int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
         GL30.glBindVertexArray(this.vao);
         if (f.sunOn) {
            GL20.glUseProgram(this.program);
            GL20.glUniform1i(this.u[0], 0);
            GL20.glUniform1i(this.u[1], 1);
            GL20.glUniform4f(this.u[2], kA, cA, kB, cB);
            GL20.glUniform4f(this.u[3], kC, cC, Config.DEV_SUN_VIEW, f.strength);
            float tanA = Math.max(0.005F, f.sun[3]);
            GL20.glUniform4f(this.u[4], f.sun[0], f.sun[1], f.sun[2], 1.0F / tanA); // (both stages)
            GL20.glUniform4f(this.u[5], this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]);
            GL20.glUniform1f(this.u[6], Config.SUN_SHADOW_MARCH ? 1.0F : 0.0F);
            GL20.glUniform4f(this.u[7], Math.max(1, Config.SUN_SHADOW_CHARACTER_REACH), Config.SUN_SHADOW_CHARACTER_LOD_PCT / 100.0F, 0.0F, 0.0F);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, f.n);
         }
         int pairs = f.nl > 0 ? this.pairs(f) : 0;
         if (pairs > 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.pairTex);
            this.pairUpload.clear();
            this.pairUpload.put(this.pairData, 0, pairs * 2).flip();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, pairs, 1, GL30.GL_RG, GL11.GL_FLOAT, this.pairUpload);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUseProgram(this.lightProgram);
            GL20.glUniform1i(this.ul[9], 2);
            GL20.glUniform1i(this.ul[0], 0);
            GL20.glUniform1i(this.ul[1], 1);
            GL20.glUniform4f(this.ul[2], kA, cA, kB, cB);
            GL20.glUniform4f(this.ul[3], kC, cC, Config.DEV_SUN_VIEW, f.lightStrength);
            GL20.glUniform4f(this.ul[4], this.viewportF[0], this.viewportF[1], this.viewportF[2], this.viewportF[3]);
            this.lightA.clear();
            this.lightA.put(f.lA, 0, MAX_LIGHTS * 4).flip();
            this.lightB.clear();
            this.lightB.put(f.lB, 0, MAX_LIGHTS * 4).flip();
            this.lightK.clear();
            this.lightK.put(f.lK, 0, MAX_LIGHTS).flip();
            GL20.glUniform4fv(this.ul[5], this.lightA);
            GL20.glUniform4fv(this.ul[6], this.lightB);
            GL20.glUniform1fv(this.ul[7], this.lightK);
            GL20.glUniform1i(this.ul[8], f.nl);
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, 4, pairs);
         }
         GL30.glBindVertexArray(previousVao);
         lightPairs += pairs;
         drawn++;
         if (Config.DEV_AO_TIMING) {
            GL33.glQueryCounter(this.queries[this.slot * 2 + 1], GL33.GL_TIMESTAMP);
            this.slot = (this.slot + 1) & 7;
            int b = this.slot * 2;
            if (drawn > 8 && GL15.glGetQueryObjecti(this.queries[b + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
               this.ns += GL33.glGetQueryObjecti64(this.queries[b + 1], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(this.queries[b], GL15.GL_QUERY_RESULT);
               if (++this.timed % 600 == 0) {
                  Log.info(String.format("capsule shadows gpu us/frame=%.2f (%d casters, %d lights this frame) | %s", this.ns / 1e3 / 600, f.n, f.nl, stats()));
                  this.ns = 0L;
               }
            }
         }
         // back to the sprite renderer's state
         GL20.glUseProgram(0);
         GL13.glActiveTexture(GL13.GL_TEXTURE2);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE1);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         GL11.glColorMask(true, true, true, true);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthMask(true);
         GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GLStateRenderThread.restore();
         zombie.core.ShaderHelper.forgetCurrentlyBound();
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      }

      private boolean init() {
         this.program = AmbientOcclusion.link(VERT, withCapsule(FRAG));
         this.lightProgram = AmbientOcclusion.link(LIGHT_VERT, withCapsule(LIGHT_FRAG));
         if (this.program == 0 || this.lightProgram == 0) {
            return false;
         }
         String[] names = {"SceneDepth", "Data", "mapA", "mapC", "sun", "vp", "march", "reach"};
         for (int i = 0; i < names.length; i++) {
            this.u[i] = GL20.glGetUniformLocation(this.program, names[i]);
         }
         String[] lnames = {"SceneDepth", "Data", "mapA", "mapC", "vp", "lA", "lB", "lK", "nl", "Pairs"};
         for (int i = 0; i < lnames.length; i++) {
            this.ul[i] = GL20.glGetUniformLocation(this.lightProgram, lnames[i]);
         }
         this.dataTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.dataTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, TEXELS, MAX, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         this.pairTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.pairTex);
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RG32F, MAX_PAIRS, 1, 0, GL30.GL_RG, GL11.GL_FLOAT, (ByteBuffer)null);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
         this.pairUpload = BufferUtils.createFloatBuffer(MAX_PAIRS * 2);
         this.vao = GL30.glGenVertexArrays(); // no attributes: the vertex shader builds the quad from gl_VertexID
         this.upload = BufferUtils.createFloatBuffer(MAX * TEXELS * 4);
         Log.info("capsule shadows: pass ready");
         return true;
      }

      private static int sceneDepthTexture(int fbo) {
         if (fbo == 0) {
            return 0;
         }
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
         if (type != GL11.GL_TEXTURE) {
            return 0;
         }
         return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      }
   }

   /**
    * The sun quad of a caster: its bounding capsule's end points and their projections on its floor along the sun, in
    * window px, boxed along the shadow's direction on screen (an oriented box: a long diagonal shadow in a screen-aligned
    * box would be mostly empty pixels, each paying a depth fetch and a capsule test), widened by the radius and the
    * penumbra at the far end. A caster standing indoors gets none.
    */
   private static final String VERT = String.join("\n",
      "#version 140",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;", // x - y = mapA.x * px + mapA.y, x + y - 6z = mapA.z * py + mapA.w (window px, relative to the origin square)
      "uniform vec4 vp;", // viewport x, y, w, h
      "uniform vec4 sun;", // direction to the sun (world, metric), 1 / tan of the penumbra angle
      "uniform vec4 reach;", // x the longest shadow along the ground (squares, sunShadowCharacterReach), y where the bounding capsule alone takes over
      "flat out int inst;",
      "vec2 toPx(vec3 p) {",
      "   return vec2((p.x - p.y - mapA.y) / mapA.x, (p.x + p.y - 6.0 * p.z / 2.4494897 - mapA.w) / mapA.z);",
      "}",
      "void main() {",
      "   inst = gl_InstanceID;",
      "   vec4 a = texelFetch(Data, ivec2(1, gl_InstanceID), 0);",
      "   vec4 b = texelFetch(Data, ivec2(2, gl_InstanceID), 0);",
      "   vec4 facts = texelFetch(Data, ivec2(3, gl_InstanceID), 0);", // floor z, kind, sun, alpha
      "   vec3 L = sun.xyz;",
      "   float lz = max(L.z, 0.05);",
      // a low sun: the shadow's quad ends at the reach (sun.w's companion, reach.x squares along the ground), faded in the fragment
      "   float lxy = max(length(L.xy), 1e-3);",
      "   float ta = min(max(0.0, a.z - facts.x) / lz, reach.x / lxy), tb = min(max(0.0, b.z - facts.x) / lz, reach.x / lxy);",
      "   vec3 a2 = vec3(a.xy - L.xy * ta, facts.x), b2 = vec3(b.xy - L.xy * tb, facts.x);",
      "   vec2 s0 = toPx(a.xyz), s1 = toPx(b.xyz), s2 = toPx(a2), s3 = toPx(b2);",
      "   vec2 du = toPx(-L) - toPx(vec3(0.0));", // the shadow's direction on screen
      "   vec2 u = dot(du, du) > 1e-6 ? normalize(du) : vec2(1.0, 0.0);",
      "   vec2 w = vec2(-u.y, u.x);",
      "   vec4 pu = vec4(dot(s0, u), dot(s1, u), dot(s2, u), dot(s3, u));",
      "   vec4 pw = vec4(dot(s0, w), dot(s1, w), dot(s2, w), dot(s3, w));",
      "   float pad = (a.w + 0.05 + 0.5 * max(ta, tb) / sun.w) * 1.12 / abs(mapA.x);", // squares to px: a horizontal square spans at most 1.12 / |kA| px (along x or y), a unit of height 1.0 / |kA|
      "   vec2 lo = vec2(min(min(pu.x, pu.y), min(pu.z, pu.w)), min(min(pw.x, pw.y), min(pw.z, pw.w))) - pad;",
      "   vec2 hi = vec2(max(max(pu.x, pu.y), max(pu.z, pu.w)), max(max(pw.x, pw.y), max(pw.z, pw.w))) + pad;",
      "   int v = gl_VertexID;",
      "   vec2 c = vec2((v == 1 || v == 2) ? hi.x : lo.x, (v >= 2) ? hi.y : lo.y);",
      "   vec2 px = u * c.x + w * c.y;",
      "   if (facts.z <= 0.0) px = vec2(-1e4);",
      "   gl_Position = vec4((px - vp.xy) / vp.zw * 2.0 - 1.0, 0.0, 1.0);",
      "}");

   /** Quilez, capsule soft shadow: the ray's closest approach to the segment over the distance travelled (tmax: the light's distance). */
   private static final String CAPSULE_GLSL = String.join("\n",
      "float capShadow(vec3 ro, vec3 rd, vec3 a, vec3 b, float r, float k, float tmax) {",
      "   vec3 ba = b - a;",
      "   vec3 oa = ro - a;",
      "   float oad = dot(oa, rd);",
      "   float dba = dot(rd, ba);",
      "   float baba = dot(ba, ba);",
      "   float oaba = dot(oa, ba);",
      "   vec2 th = vec2(-oad * baba + dba * oaba, oaba - oad * dba) / max(baba - dba * dba, 1e-6);",
      "   th.x = clamp(th.x, 0.0001, tmax);",
      "   th.y = clamp(th.y, 0.0, 1.0);",
      "   vec3 p = a + ba * th.y;",
      "   vec3 q = ro + rd * th.x;",
      "   float d = length(p - q) - r;",
      "   float s = clamp(k * d / th.x + 0.5, 0.0, 1.0);",
      "   return s * s * (3.0 - 2.0 * s);",
      "}",
      "vec3 worldAt(vec2 f, float d, vec4 mapA, vec4 mapC) {",
      "   float A = mapA.x * f.x + mapA.y;",
      "   float B = mapA.z * f.y + mapA.w;",
      "   float C = mapC.x * d + mapC.y;",
      "   float Z = (C - B) * 0.125;",
      "   float S = C - 2.0 * Z;",
      "   return vec3((S + A) * 0.5, (S - A) * 0.5, Z * 2.4494897);",
      "}");

   private static final String FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D SceneDepth;",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;",
      "uniform vec4 mapC;", // x + y + 2z = mapC.x * depth + mapC.y; dev view; strength
      "uniform vec4 sun;", // direction to the sun (world, metric), 1 / tan of the penumbra angle
      "uniform float march;", // sunShadowMarch: the per-pixel static-shade test
      "uniform vec4 reach;", // x the longest shadow along the ground (squares), y where the bounding capsule alone takes over (squares)
      "flat in int inst;",
      "out vec4 fragColor;",
      "#include capsule",
      "void main() {",
      "   vec2 f = gl_FragCoord.xy;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   if (d >= 0.99999) discard;",
      "   vec3 P = worldAt(f, d, mapA, mapC);",
      "   vec3 ro = P + sun.xyz * 0.03;",
      "   vec4 ba0 = texelFetch(Data, ivec2(1, inst), 0);",
      "   vec4 bb0 = texelFetch(Data, ivec2(2, inst), 0);",
      "   if (capShadow(ro, sun.xyz, ba0.xyz, bb0.xyz, ba0.w, sun.w, 1e4) > 0.999) discard;", // outside the bounding capsule's shadow: full sun
      "   vec4 facts = texelFetch(Data, ivec2(3, inst), 0);", // floor z, kind, the caster's own sun share, alpha
      // how far along the ground from the caster: past reach.x nothing (a low sun's quad was capped), and past reach.y the
      // bounding capsule alone (thinned to the body): the penumbra there is wider than a limb, ten tests bought nothing
      "   float hd = length(P.xy - 0.5 * (ba0.xy + bb0.xy));",
      "   float fade = 1.0 - smoothstep(0.7 * reach.x, reach.x, hd);",
      "   if (fade <= 0.0) discard;",
      "   float alpha = facts.w * facts.z * fade;",
      "   float lod = smoothstep(reach.y, reach.y + 1.0, hd);",
      "   float vis = 1.0;",
      "   if (lod < 1.0) {",
      "      for (int i = 0; i < 10; i++) {", // K
      "         vec4 a = texelFetch(Data, ivec2(4 + 2 * i, inst), 0);",
      "         vec4 b = texelFetch(Data, ivec2(5 + 2 * i, inst), 0);",
      "         if (a.w > 0.0) vis *= capShadow(ro, sun.xyz, a.xyz, b.xyz, a.w, sun.w, 1e4);",
      "      }",
      "   }",
      "   if (lod > 0.0) vis = mix(vis, capShadow(ro, sun.xyz, ba0.xyz, bb0.xyz, ba0.w * 0.55, sun.w, 1e4), lod);",
      // dev (sunShadowMarch): a receiver the static world already hides from the sun takes no second shadow, per pixel:
      // march the scene depth towards the sun (8 steps, up to ~6 squares). Off by default: the caster's own sun share
      // (facts.z) does it per caster, for nothing
      "   if (vis < 0.995 && march > 0.5) {",
      "      for (int j = 1; j <= 8; j++) {",
      "         float t = float(j * j) * 0.09;",
      "         vec3 Q = P + sun.xyz * t;",
      "         float zl = Q.z / 2.4494897;",
      "         vec2 fq = vec2((Q.x - Q.y - mapA.y) / mapA.x, (Q.x + Q.y - 6.0 * zl - mapA.w) / mapA.z);",
      "         ivec2 iq = ivec2(fq);",
      "         if (any(lessThan(iq, ivec2(0))) || any(greaterThanEqual(iq, textureSize(SceneDepth, 0)))) break;",
      "         float ds = texelFetch(SceneDepth, iq, 0).r;",
      "         if (ds >= 0.99999) continue;",
      "         float gap = (mapC.x * ds + mapC.y) - (Q.x + Q.y + 2.0 * zl);", // > 0: the surface there is nearer the camera than the ray point
      "         if (gap > 0.12 && gap < 2.0) { vis = 1.0; break; }",
      "      }",
      "   }",
      "   float m = 1.0 - mapC.w * alpha * (1.0 - vis);",
      "   if (mapC.z > 0.5) m = mix(0.5, 1.0, vis);", // dev: the capsule term alone over grey
      "   if (m > 0.996) discard;",
      "   fragColor = vec4(vec3(m), 1.0);",
      "}");

   /**
    * Casters x lights: instance = caster * nl + light. The quad: the screen box of the bounding capsule and its projection
    * away from the light onto the caster's floor (at most the light's reach from it); none for a caster out of the light's
    * reach, for the torch's holder, or for a vehicle and its own headlights.
    */
   private static final String LIGHT_VERT = String.join("\n",
      "#version 140",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;",
      "uniform vec4 vp;",
      "uniform vec4 lA[4];", // x, y (relative to the origin square), z (metric), reach
      "uniform vec4 lB[4];", // direction x, y, cone cosine (-2: none), strength
      "uniform float lK[4];", // 1 a handheld torch, 2 a vehicle light
      "uniform int nl;",
      "uniform sampler2D Pairs;", // instance -> (caster, light)
      "flat out int inst;",
      "flat out int light;",
      "vec2 toPx(vec3 p) {",
      "   float A = p.x - p.y, B = p.x + p.y - 6.0 * p.z / 2.4494897;",
      "   return vec2((A - mapA.y) / mapA.x, (B - mapA.w) / mapA.z);",
      "}",
      "void main() {",
      "   vec2 pair = texelFetch(Pairs, ivec2(gl_InstanceID, 0), 0).xy;",
      "   int c = int(pair.x + 0.5);",
      "   int l = int(pair.y + 0.5);",
      "   inst = c;",
      "   light = l;",
      "   vec4 a = texelFetch(Data, ivec2(1, c), 0);",
      "   vec4 b = texelFetch(Data, ivec2(2, c), 0);",
      "   vec4 facts = texelFetch(Data, ivec2(3, c), 0);", // floor z, kind, sun, alpha
      "   vec3 L = lA[l].xyz;",
      "   vec3 mid = 0.5 * (a.xyz + b.xyz);",
      "   float dl = length(mid.xy - L.xy);",
      "   bool skip = dl > lA[l].w + a.w || (lK[l] < 1.5 && dl < 0.7) || (lK[l] > 1.5 && facts.y > 0.5 && dl < 3.5);",
      "   vec2 sp[4];",
      "   float reach = lA[l].w;",
      "   for (int e = 0; e < 2; e++) {",
      "      vec3 p = e == 0 ? a.xyz : b.xyz;",
      "      vec2 dir = p.xy - L.xy;",
      "      float dh = length(dir);",
      "      dir = dh > 1e-3 ? dir / dh : vec2(1.0, 0.0);",
      "      float t = p.z < L.z - 0.05 ? (L.z - facts.x) / (L.z - p.z) : 1e3;", // the ray from the lamp through p hits the floor at L + (p - L) t
      "      float far = min(dh * t, reach) + a.w + 0.3;",
      "      sp[e * 2] = toPx(p);",
      "      sp[e * 2 + 1] = toPx(vec3(L.xy + dir * far, facts.x));",
      "   }",
      "   vec2 du = toPx(vec3(mid.xy, facts.x)) - toPx(vec3(L.xy, facts.x));", // away from the lamp, on screen
      "   vec2 u = dot(du, du) > 1e-6 ? normalize(du) : vec2(1.0, 0.0);",
      "   vec2 w = vec2(-u.y, u.x);",
      "   vec4 pu = vec4(dot(sp[0], u), dot(sp[1], u), dot(sp[2], u), dot(sp[3], u));",
      "   vec4 pw = vec4(dot(sp[0], w), dot(sp[1], w), dot(sp[2], w), dot(sp[3], w));",
      "   float pad = (a.w + 0.3) * 1.12 / abs(mapA.x);",
      "   vec2 lo = vec2(min(min(pu.x, pu.y), min(pu.z, pu.w)), min(min(pw.x, pw.y), min(pw.z, pw.w))) - pad;",
      "   vec2 hi = vec2(max(max(pu.x, pu.y), max(pu.z, pu.w)), max(max(pw.x, pw.y), max(pw.z, pw.w))) + pad;",
      "   int v = gl_VertexID;",
      "   vec2 cc = vec2((v == 1 || v == 2) ? hi.x : lo.x, (v >= 2) ? hi.y : lo.y);",
      "   vec2 px = u * cc.x + w * cc.y;",
      "   if (skip) px = vec2(-1e4);",
      "   gl_Position = vec4((px - vp.xy) / vp.zw * 2.0 - 1.0, 0.0, 1.0);",
      "}");

   private static final String LIGHT_FRAG = String.join("\n",
      "#version 140",
      "uniform sampler2D SceneDepth;",
      "uniform sampler2D Data;",
      "uniform vec4 mapA;",
      "uniform vec4 mapC;", // x + y + 2z = mapC.x * depth + mapC.y; dev view; strength (x the darkness)
      "uniform vec4 lA[4];",
      "uniform vec4 lB[4];",
      "uniform float lK[4];",
      "flat in int inst;",
      "flat in int light;",
      "out vec4 fragColor;",
      "#include capsule",
      // PixelLight's fitted torch intensity (the share of the pixel's light the lamp gives in the dark)
      "float torch(vec2 p, vec4 a, vec4 b, float kind) {",
      "   vec2 v = p - a.xy;",
      "   float d = length(v);",
      "   bool car = kind > 1.5;",
      "   float x = clamp(1.0 - d / a.w, 0.0, 1.0);",
      "   float fall = car ? x * sqrt(x) * (0.93 + 0.07 * x) : x * (0.85 + 0.15 * x);",
      "   float ang = 1.0;",
      "   if (b.z > -1.5 && d > 1e-3) {",
      "      float c0 = car ? b.z - 0.28 : b.z + 0.025, c1 = car ? 1.0 : 0.95;",
      "      ang = clamp((dot(v, b.xy) / d - c0) / max(c1 - c0, 0.05), 0.0, 1.0);",
      "   }",
      "   return min(1.0, (car ? 1.57 : 1.76) * b.w * fall * ang);",
      "}",
      "void main() {",
      "   vec2 f = gl_FragCoord.xy;",
      "   float d = texelFetch(SceneDepth, ivec2(f), 0).r;",
      "   if (d >= 0.99999) discard;",
      "   vec3 P = worldAt(f, d, mapA, mapC);",
      "   vec4 la = lA[light];",
      "   float share = torch(P.xy, la, lB[light], lK[light]);",
      "   if (share < 0.02) discard;",
      "   vec3 tl = la.xyz - P;",
      "   float tmax = length(tl);",
      "   vec3 rd = tl / tmax;",
      "   vec3 ro = P + rd * 0.03;",
      "   float k = tmax / (lK[light] > 1.5 ? 0.25 : 0.12);", // the lamp's size: penumbra grows with the caster's distance from the receiver
      "   vec4 ba0 = texelFetch(Data, ivec2(1, inst), 0);",
      "   vec4 bb0 = texelFetch(Data, ivec2(2, inst), 0);",
      "   if (capShadow(ro, rd, ba0.xyz, bb0.xyz, ba0.w, k, tmax) > 0.999) discard;",
      "   float alpha = texelFetch(Data, ivec2(3, inst), 0).w;",
      "   float vis = 1.0;",
      "   for (int i = 0; i < 10; i++) {", // K
      "      vec4 a = texelFetch(Data, ivec2(4 + 2 * i, inst), 0);",
      "      vec4 b = texelFetch(Data, ivec2(5 + 2 * i, inst), 0);",
      "      if (a.w > 0.0) vis *= capShadow(ro, rd, a.xyz, b.xyz, a.w, k, tmax);",
      "   }",
      "   float m = 1.0 - mapC.w * share * alpha * (1.0 - vis);",
      "   if (mapC.z > 0.5) m = mix(0.5, 1.0, vis);",
      "   if (m > 0.996) discard;",
      "   fragColor = vec4(vec3(m), 1.0);",
      "}");

   /** The shared GLSL goes in where a source says "#include capsule". */
   private static String withCapsule(String src) {
      return src.replace("#include capsule", CAPSULE_GLSL);
   }
}
