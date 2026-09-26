package pzopt;

import zombie.GameTime;
import zombie.iso.weather.ClimateManager;

/**
 * The sun of the soft sun shadows (Config {@code sunShadows}): its direction from the time of day and how much of the
 * outdoor light it carries from the daylight and the weather. The shadows themselves are computed by the chunk AO kernel
 * ({@link ChunkAo}) into the term it keeps per chunk texture, so a frame that bakes nothing pays nothing; when the sun
 * moves by {@code sunStepDeg10} tenths of a degree or its strength by a step, the loaded textures compute again (those
 * on screen first, a few a frame, each applied onto its colour as new / old).
 *
 * <p>The sun's path is HdrGlint's: east at 6 h, south at noon ({@code sunMaxElevationDeg} high), west at 18 h (PZ: +x
 * east, +y south), turned by {@code sunAzimuthDeg}. The kernel works in the chunk textures' orthographic view space
 * (x screen right, y screen up, z away from the camera, one unit = one square; a level is 2.449 squares tall), where the
 * world axes are the three plane normals the AO kernel snaps to.
 */
public final class SunShadow {
   /** World +x, +y and +z (one square of height) in the kernel's view space. */
   private static final double[] WX = {0.7071068, -0.3535534, -0.6123724};
   private static final double[] WY = {-0.7071068, -0.3535534, -0.6123724};
   private static final double[] WZ = {0.0, 0.8660254, -0.5};
   private static final int STRENGTH_STEPS = 32;

   /** Game thread (read when a compute is queued): the sun direction in view space (xyz) and the shadow strength (w, 0 = none). */
   static final float[] dir = new float[4];
   /** Across the sun direction and the view direction (xyz); w = tan of the sun's angular radius (the penumbra). */
   static final float[] perp = new float[4];
   /** The direction to the sun in world space (x east, y south, z up, one unit of height = one square), w = tan of the penumbra angle; strength in dir[3]. */
   static final float[] world = new float[4];
   private static long state = Long.MIN_VALUE;
   private static float hourNow = 12F;
   private static long changes;
   private static long lastStrengthStep;
   private static long sweepT0;
   private static long toggleT0;
   /** The unquantised strength of this frame's key light (cloud shadows follow it every frame). */
   static volatile float liveStrength;
   /** 0 = the sun is the key light, 1 = the moon. */
   static volatile int lightBody;
   private static final double[] stepDir = new double[3];
   private static int stepBody = -1;
   private static long stepSerial;

   private SunShadow() {
   }

   static boolean enabled() {
      return Config.SUN_SHADOWS;
   }

   static String stats() {
      return String.format(java.util.Locale.ROOT, "%s: hour %.2f dir %.2f,%.2f,%.2f strength %.2f (live %.3f) penumbra %.3f changes %d | %s", lightBody == 0 ? "sun" : "moon", hourNow, dir[0], dir[1], dir[2], dir[3], liveStrength, perp[3], changes, Sky.stats());
   }

   /**
    * Game thread, once a frame: the sun of this hour and weather. Returns true when it moved or changed strength by a
    * step since the last call that returned true (the kept shadows are stale).
    */
   static boolean update() {
      if (Config.DEV_SUN_TOGGLE_PERIOD > 0) {
         // dev (the Workshop card's clip): sun shadows on and off every period from the first frame, every loaded chunk
         // picture baking again at each switch; the log line's epoch lets a card label the recording's frames
         long now = System.currentTimeMillis();
         if (toggleT0 == 0L) {
            toggleT0 = now;
         }
         boolean on = (now - toggleT0) / Config.DEV_SUN_TOGGLE_PERIOD % 2L == 0L;
         if (on != Config.SUN_SHADOWS) {
            Config.SUN_SHADOWS = on;
            ChunkAo.reconfigure();
            Log.info("sun shadows: dev toggle " + (on ? "on" : "off") + " at epoch_ms " + now);
         }
      }
      if (!enabled()) {
         if (state != Long.MIN_VALUE) {
            state = Long.MIN_VALUE;
            dir[3] = 0F;
         }
         return false;
      }
      float hour = Config.DEV_SUN_HOUR >= 0F ? Config.DEV_SUN_HOUR : GameTime.getInstance() != null ? GameTime.getInstance().getTimeOfDay() : 12F;
      if (Config.DEV_SUN_HOUR >= 0F && Config.DEV_SUN_HOUR_SPEED > 0F) { // dev: the sun sweeps (worst case of the recompute waves)
         if (sweepT0 == 0L) {
            sweepT0 = System.nanoTime();
         }
         hour = Config.DEV_SUN_HOUR + Config.DEV_SUN_HOUR_SPEED * (System.nanoTime() - sweepT0) / 1e9F;
         hour = hour % 24F;
      }
      hourNow = hour;
      ClimateManager cm = ClimateManager.getInstance();
      float day = cm != null ? clamp01(cm.getDayLightStrength()) : 1F;
      float cloud = cm != null ? clamp01(cm.getCloudIntensity()) : 0F;
      float rain = cm != null ? clamp01(cm.getPrecipitationIntensity()) : 0F;
      float fog = cm != null ? clamp01(cm.getFogIntensity()) : 0F;
      boolean clouds = CloudShadow.enabled();
      // with cloud shadows the clouds shade where they are (the gaps keep the full sun); overcast leaves no gap
      float clear = clouds ? (1F - smooth(0.8F, 1.0F, cloud)) * (1F - 0.8F * rain) * (1F - 0.9F * fog)
         : (1F - 0.85F * cloud) * (1F - 0.8F * rain) * (1F - 0.9F * fog);
      double minElev = Math.toRadians(Math.max(1, Config.SUN_MIN_ELEVATION_DEG));
      double wx, wy, wz, elev;
      float s;
      int body; // 0 sun, 1 moon
      if ("arc".equals(Config.SKY_PATH)) {
         double a = Math.PI * (hour - 6.0) / 12.0; // 0 at 6 h (east), pi / 2 at noon (south), pi at 18 h (west)
         if (Config.SUN_STEP_MODE_ANGLE == 0) {
            double stepRad = Math.toRadians(Math.max(1, Config.SUN_STEP_DEG10) / 10.0);
            a = Math.round(a / stepRad) * stepRad; // the quantised sun: every compute of one step sees the same one
         }
         double sinA = Math.sin(a);
         elev = Math.max(0.0, sinA) * Math.toRadians(Math.max(5, Math.min(89, Config.SUN_MAX_ELEVATION_DEG)));
         double az = Math.toRadians(Config.SUN_AZIMUTH_DEG);
         double hx = Math.cos(a), hy = sinA; // towards the sun, seen from above (east at 6 h, south at noon)
         double rx = hx * Math.cos(az) - hy * Math.sin(az), ry = hx * Math.sin(az) + hy * Math.cos(az);
         double h = Math.sqrt(rx * rx + ry * ry);
         wx = rx / h * Math.cos(elev);
         wy = ry / h * Math.cos(elev);
         wz = Math.sin(elev);
         float rise = (float)Math.max(0.0, Math.min(1.0, (elev - minElev) / Math.toRadians(8.0)));
         s = day * clear * rise * Math.max(0, Config.SUN_SHADOW_STRENGTH_PCT) / 100F;
         body = 0;
      } else {
         Sky.update(Config.DEV_SUN_HOUR >= 0F ? hour : -1F);
         double sunEl = Math.toRadians(Sky.sunElevDeg);
         float sunRise = (float)Math.max(0.0, Math.min(1.0, (sunEl - minElev) / Math.toRadians(8.0)));
         float sunS = sunRise * clear * day * Math.max(0, Config.SUN_SHADOW_STRENGTH_PCT) / 100F;
         double moonEl = Math.toRadians(Sky.moonElevDeg);
         // the moon's shadows show once the sky is dark (the sun 4 deg under the horizon: none; 12 deg: all), as strong as
         // its brightness allows (the phase law: a quarter moon is ~a tenth of a full one; the shadows' contrast by its
         // square root), faded in over its first degrees above minElev like the sun's
         float dark = (float)Math.max(0.0, Math.min(1.0, (-Sky.sunElevDeg - 4.0) / 8.0));
         float moonRise = (float)Math.max(0.0, Math.min(1.0, (moonEl - minElev) / Math.toRadians(8.0)));
         float moonS = Config.MOON_SHADOWS ? dark * moonRise * clear * (float)Math.sqrt(Sky.moonBrightness)
            * Math.max(0, Config.SUN_SHADOW_STRENGTH_PCT) / 100F * Math.max(0, Config.MOON_SHADOW_PCT) / 100F : 0F;
         double[] w = sunS > 0F || moonS <= 0F ? Sky.sun : Sky.moon;
         body = w == Sky.sun ? 0 : 1;
         s = body == 0 ? sunS : moonS;
         wx = w[0];
         wy = w[1];
         wz = w[2];
         elev = body == 0 ? sunEl : moonEl;
      }
      if (Config.SUN_STEP_MODE_ANGLE != 0 || !"arc".equals(Config.SKY_PATH)) {
         // a new step once the light has turned sunStepDeg10 from the last step's direction (or changed body): the steps
         // come at the same angular spacing whatever the path (azimuth steps near a high noon sun would come far faster)
         double cosStep = Math.cos(Math.toRadians(Math.max(1, Config.SUN_STEP_DEG10) / 10.0));
         double dot = wx * stepDir[0] + wy * stepDir[1] + wz * stepDir[2];
         if (body != stepBody || dot < cosStep || stepSerial == 0L) {
            stepDir[0] = wx;
            stepDir[1] = wy;
            stepDir[2] = wz;
            stepBody = body;
            stepSerial++;
         }
         wx = stepDir[0];
         wy = stepDir[1];
         wz = stepDir[2];
      }
      liveStrength = s;
      long sStep = Math.round(Math.min(1F, s) * STRENGTH_STEPS);
      if (sStep != 0L && lastStrengthStep > 0L && Math.abs(sStep - lastStrengthStep) < 2L) {
         sStep = lastStrengthStep; // hysteresis: clouds drifting across a step boundary would recompute every texture again and again
      }
      lastStrengthStep = sStep;
      float penumbra = (float)Math.tan(Math.toRadians(3.0 * Math.max(1, Config.SUN_SHADOW_SOFTNESS_PCT) / 100.0));
      double dh = Math.sqrt(wx * wx + wy * wy + wz * wz);
      wx /= dh;
      wy /= dh;
      wz /= dh;
      long aStep = stepSerial * 2L + stepBody;
      if ("arc".equals(Config.SKY_PATH) && Config.SUN_STEP_MODE_ANGLE == 0) {
         aStep = Math.round(Math.atan2(wz, Math.hypot(wx, wy)) * 1000.0) * 7919L + Math.round(Math.atan2(wy, wx) * 1000.0);
      }

      long next = sStep == 0L ? -1L : (aStep * 64L + sStep) * 1024L + Config.SUN_SHADOW_SOFTNESS_PCT;
      lightBody = body;
      if (next == state) {
         return false;
      }
      boolean was = state != Long.MIN_VALUE;
      state = next;
      double vx = wx * WX[0] + wy * WY[0] + wz * WZ[0];
      double vy = wx * WX[1] + wy * WY[1] + wz * WZ[1];
      double vz = wx * WX[2] + wy * WY[2] + wz * WZ[2];
      dir[0] = (float)vx;
      dir[1] = (float)vy;
      dir[2] = (float)vz;
      dir[3] = sStep / (float)STRENGTH_STEPS;
      world[0] = (float)wx;
      world[1] = (float)wy;
      world[2] = (float)wz;
      world[3] = penumbra;
      double pl = Math.sqrt(vx * vx + vy * vy); // cross(L, V) with V = (0, 0, -1): (-Ly, Lx, 0)
      perp[0] = pl < 1e-4 ? 1F : (float)(-vy / pl);
      perp[1] = pl < 1e-4 ? 0F : (float)(vx / pl);
      perp[2] = 0F;
      perp[3] = penumbra;
      changes++;
      if (!was || changes <= 3 || changes % 20 == 0) {
         Log.info("sun shadows: " + stats());
      }
      return was;
   }

   // ------------------------------------------------------------------------------------------------ characters in the shade

   private static final int CACHE = 8192;
   private static final long[] CACHE_KEY = new long[CACHE];
   private static final float[] CACHE_VIS = new float[CACHE];
   private static final float LEVEL = 2.4494897F; // squares of height per level

   /**
    * sunShadows + sunShadowCharacters: the factor on a character's ambient light (ModelInstance.updateLights): 1 in the sun,
    * indoors and at night, 1 - strength in the static world's sun shadow. Any thread (the character draw pool): read-only
    * grid walks, a direct-mapped cache per half square and sun step (a lost race only recomputes or serves a neighbour's
    * value for a frame; the ambient eases over ~10 frames anyway).
    */
   public static float characterFactor(zombie.characters.IsoGameCharacter chr) {
      float s = dir[3];
      if (s <= 0F || !Config.SUN_SHADOWS || !Config.SUN_SHADOW_CHARACTERS || !Overrides.enabled()) {
         return 1F;
      }
      zombie.iso.IsoGridSquare sq = chr.getCurrentSquare();
      if (sq == null || !sq.isOutside()) {
         return 1F;
      }
      return 1F - s * (1F - visibleAt(chr.getX(), chr.getY(), chr.getZ()) * CloudShadow.transmittanceAt(chr.getX(), chr.getY(), chr.getZ()));
   }

   /** How much of the sun a point one square above (x, y, z) sees through the grid (walls, upper floors, solid objects, trees). */
   static float visibleAt(float x, float y, float z) {
      int hx = (int)Math.floor(x * 2F), hy = (int)Math.floor(y * 2F), iz = (int)Math.floor(z);
      long key = ((long)(hx & 0xFFFFF) << 40 | (long)(hy & 0xFFFFF) << 20 | (iz + 64) & 0xFF) * 31L + state;
      int slot = (int)((key ^ key >>> 29) & (CACHE - 1));
      if (CACHE_KEY[slot] == key) {
         return CACHE_VIS[slot];
      }
      float v = march((hx + 0.5F) * 0.5F, (hy + 0.5F) * 0.5F, iz);
      CACHE_VIS[slot] = v;
      CACHE_KEY[slot] = key;
      return v;
   }

   private static float march(float x, float y, int z) {
      zombie.iso.IsoCell cell = zombie.iso.IsoWorld.instance.currentCell;
      float lx = world[0], ly = world[1], lz = world[2];
      if (cell == null || lz <= 0.02F) {
         return 1F;
      }
      float h = Math.max(0.2F, (float)Math.sqrt(lx * lx + ly * ly));
      float step = 0.34F / h; // a third of a square across the ground per step
      float pz0 = z * LEVEL + 1.0F; // chest height
      float vis = 1F;
      int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE, lastL = Integer.MIN_VALUE;
      for (int i = 1; i <= 40; i++) {
         float t = i * step;
         float px = x + lx * t, py = y + ly * t, pz = pz0 + lz * t;
         int lvl = (int)Math.floor(pz / LEVEL);
         if (lvl > z + 4 || Math.abs(lx * t) > 9F || Math.abs(ly * t) > 9F) {
            break;
         }
         int sx = (int)Math.floor(px), sy = (int)Math.floor(py);
         if (sx == lastX && sy == lastY && lvl == lastL) {
            continue;
         }
         int prevX = lastX, prevY = lastY;
         lastX = sx;
         lastY = sy;
         lastL = lvl;
         zombie.iso.IsoGridSquare sq = cell.getGridSquare(sx, sy, lvl);
         if (sq == null) {
            continue;
         }
         float above = pz - lvl * LEVEL; // the ray's height inside this level
         if (lvl > z && sq.getFloor() != null) {
            return 0F; // an upper floor or a roof of a building between us and the sun
         }
         // a wall on the edge the ray crossed into this square (N edge: coming from y - 1; W edge: from x - 1), or leaving it
         boolean crossN = prevY != Integer.MIN_VALUE && prevY < sy, crossW = prevX != Integer.MIN_VALUE && prevX < sx;
         boolean wall = crossN && (sq.has(zombie.iso.SpriteDetails.IsoFlagType.collideN) || sq.has(zombie.iso.SpriteDetails.IsoFlagType.WallN))
            || crossW && (sq.has(zombie.iso.SpriteDetails.IsoFlagType.collideW) || sq.has(zombie.iso.SpriteDetails.IsoFlagType.WallW));
         if (!wall && (prevY > sy || prevX > sx)) { // moving north / west: the wall sits on the square we left
            zombie.iso.IsoGridSquare from = cell.getGridSquare(prevX, prevY, lvl);
            if (from != null) {
               wall = prevY > sy && (from.has(zombie.iso.SpriteDetails.IsoFlagType.collideN) || from.has(zombie.iso.SpriteDetails.IsoFlagType.WallN))
                  || prevX > sx && (from.has(zombie.iso.SpriteDetails.IsoFlagType.collideW) || from.has(zombie.iso.SpriteDetails.IsoFlagType.WallW));
            }
         }
         if (wall) {
            return 0F;
         }
         if ((sq.has(zombie.iso.SpriteDetails.IsoFlagType.solid) || sq.has(zombie.iso.SpriteDetails.IsoFlagType.solidtrans)) && above < 0.5F * LEVEL) {
            vis *= 0.25F;
         }
         if (sq.getTree() != null && above > 0.3F * LEVEL) {
            vis *= 0.45F; // a crown lets some light through
         }
         if (vis < 0.05F) {
            return 0F;
         }
      }
      return vis;
   }

   private static float smooth(float e0, float e1, float x) {
      float t = clamp01((x - e0) / (e1 - e0));
      return t * t * (3F - 2F * t);
   }

   private static float clamp01(float v) {
      return Math.max(0F, Math.min(1F, v));
   }
}
