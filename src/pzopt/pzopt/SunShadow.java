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

   private SunShadow() {
   }

   static boolean enabled() {
      return Config.SUN_SHADOWS;
   }

   static String stats() {
      return String.format("sun: hour %.2f dir %.2f,%.2f,%.2f strength %.2f penumbra %.3f changes %d", hourNow, dir[0], dir[1], dir[2], dir[3], perp[3], changes);
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
         hour = Math.min(18F, hour + Config.DEV_SUN_HOUR_SPEED * (System.nanoTime() - sweepT0) / 1e9F);
      }
      hourNow = hour;
      double stepRad = Math.toRadians(Math.max(1, Config.SUN_STEP_DEG10) / 10.0);
      double a = Math.PI * (hour - 6.0) / 12.0; // 0 at 6 h (east), pi / 2 at noon (south), pi at 18 h (west)
      long aStep = Math.round(a / stepRad);
      a = aStep * stepRad; // the quantised sun: every compute of one step sees the same one
      double sinA = Math.sin(a);
      double elev = Math.max(0.0, sinA) * Math.toRadians(Math.max(5, Math.min(89, Config.SUN_MAX_ELEVATION_DEG)));
      double az = Math.toRadians(Config.SUN_AZIMUTH_DEG);
      double hx = Math.cos(a), hy = sinA; // towards the sun, seen from above (east at 6 h, south at noon)
      double rx = hx * Math.cos(az) - hy * Math.sin(az), ry = hx * Math.sin(az) + hy * Math.cos(az);
      double h = Math.sqrt(rx * rx + ry * ry);
      rx /= h;
      ry /= h;
      double wx = rx * Math.cos(elev), wy = ry * Math.cos(elev), wz = Math.sin(elev);

      ClimateManager cm = ClimateManager.getInstance();
      float day = cm != null ? clamp01(cm.getDayLightStrength()) : 1F;
      float cloud = cm != null ? clamp01(cm.getCloudIntensity()) : 0F;
      float rain = cm != null ? clamp01(cm.getPrecipitationIntensity()) : 0F;
      float fog = cm != null ? clamp01(cm.getFogIntensity()) : 0F;
      double minElev = Math.toRadians(Math.max(1, Config.SUN_MIN_ELEVATION_DEG));
      float rise = (float)Math.max(0.0, Math.min(1.0, (elev - minElev) / Math.toRadians(8.0)));
      float clear = (1F - 0.85F * cloud) * (1F - 0.8F * rain) * (1F - 0.9F * fog);
      float s = day * clear * rise * Math.max(0, Config.SUN_SHADOW_STRENGTH_PCT) / 100F;
      long sStep = Math.round(Math.min(1F, s) * STRENGTH_STEPS);
      if (sStep != 0L && lastStrengthStep > 0L && Math.abs(sStep - lastStrengthStep) < 2L) {
         sStep = lastStrengthStep; // hysteresis: clouds drifting across a step boundary would recompute every texture again and again
      }
      lastStrengthStep = sStep;
      float penumbra = (float)Math.tan(Math.toRadians(3.0 * Math.max(1, Config.SUN_SHADOW_SOFTNESS_PCT) / 100.0));

      long next = sStep == 0L ? -1L : (aStep * 64L + sStep) * 1024L + Config.SUN_SHADOW_SOFTNESS_PCT;
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
      return 1F - s * (1F - visibleAt(chr.getX(), chr.getY(), chr.getZ()));
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

   private static float clamp01(float v) {
      return Math.max(0F, Math.min(1F, v));
   }
}
