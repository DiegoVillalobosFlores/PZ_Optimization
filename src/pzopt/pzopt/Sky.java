package pzopt;

import zombie.GameTime;
import zombie.iso.weather.ClimateManager;

/**
 * The real sky over Knox County (Config {@code skyPath=astro}): where the sun and the moon stand for the game's date and
 * hour, and how much of the moon is lit.
 *
 * <ul>
 *   <li>Sun: declination and right ascension from the Astronomical Almanac's low-precision solar formulas (~0.01 deg);
 *       its hour angle from the game's own clock around the season's high noon ({@code ErosionSeason.getDayHighNoon}),
 *       at the season's latitude ({@code getLat}, 38 N by default), so the sun crosses the horizon at the game's own
 *       dawn and dusk (the stock daylight follows the same latitude formula) and stands highest when the game says noon.</li>
 *   <li>Moon: the Almanac's low-precision lunar longitude / latitude (six and four periodic terms, ~0.3 deg), its hour
 *       angle offset from the sun's by the difference of right ascensions at the same instant (UT from the game's local
 *       time, Eastern time); the lit fraction from the elongation, the brightness from the lunar phase law.</li>
 * </ul>
 * Directions are in the world frame x east, y south, z up (PZ: +x east, +y south). Game thread, once a frame; the
 * ephemeris is recomputed when the game minute changes.
 */
public final class Sky {
   private Sky() {
   }

   /** The direction to the sun / moon (x east, y south, z up), unit length. */
   static final double[] sun = {0.0, 0.0, 1.0}, moon = {0.0, 0.0, -1.0};
   static volatile double sunElevDeg = 45.0, moonElevDeg = -30.0;
   /** The moon's lit fraction (0 new, 1 full) and its brightness relative to a full moon (phase law, 0..1). */
   static volatile double moonLit, moonBrightness;
   static volatile boolean moonWaxing;
   static double latDeg = 38.0, noonHour = 12.5, hourNow = 12.0;
   private static long lastKey = Long.MIN_VALUE;
   private static long updates;

   static String stats() {
      return String.format(java.util.Locale.ROOT, "sky: lat %.1f noon %.2f hour %.2f sun el %.1f az %.1f | moon el %.1f az %.1f lit %.2f bright %.2f %s",
         latDeg, noonHour, hourNow, sunElevDeg, azimuthDeg(sun), moonElevDeg, azimuthDeg(moon), moonLit, moonBrightness, moonWaxing ? "waxing" : "waning");
   }

   /** Compass azimuth (0 north, 90 east) of a world direction. */
   static double azimuthDeg(double[] w) {
      double az = Math.toDegrees(Math.atan2(w[0], -w[1]));
      return az < 0.0 ? az + 360.0 : az;
   }

   /** Game thread: the sky of the game's date and hour (devSunHour overrides the hour). */
   static void update(float hourOverride) {
      GameTime gt = GameTime.getInstance();
      int year = 1993, month = 6, day = 9;
      float hour = 12F;
      if (gt != null) {
         year = gt.getYear();
         month = gt.getMonth();
         day = gt.getDay();
         hour = gt.getTimeOfDay();
      }
      if (hourOverride >= 0F) {
         hour = hourOverride;
      }
      if (!Config.DEV_SKY_DATE.isEmpty()) {
         try {
            java.time.LocalDate d = java.time.LocalDate.parse(Config.DEV_SKY_DATE.trim());
            year = d.getYear();
            month = d.getMonthValue() - 1;
            day = d.getDayOfMonth() - 1;
         } catch (RuntimeException e) {
            // keep the game's date
         }
      }
      double lat = 38.0, noon = 12.5;
      ClimateManager cm = ClimateManager.getInstance();
      if (cm != null && cm.getSeason() != null) {
         lat = cm.getSeason().getLat();
         float hn = cm.getSeason().getDayHighNoon();
         if (hn > 6F && hn < 18F) {
            noon = hn;
         }
      }
      if (Config.SKY_LATITUDE_DEG != 0) {
         lat = Config.SKY_LATITUDE_DEG;
      }
      long key = ((((long)year * 16L + month) * 32L + day) * 1440L + (long)Math.floor(hour * 60.0)) * 1000L + (long)(lat * 10.0) + (long)(noon * 1000.0) * 7919L;
      hourNow = hour;
      if (key == lastKey && hourOverride < 0F) {
         return;
      }
      lastKey = key;
      updates++;
      latDeg = lat;
      noonHour = noon;
      compute(year, month, day, hour, lat, noon);
   }

   /** The sky for a date (month 0-11, day 0-based as GameTime has them) and local hour, into the static fields. */
   static void compute(int year, int month, int day, double hour, double latDeg, double noonHour) {
      double utHour = hour + (month >= 3 && month <= 9 ? 4.0 : 5.0); // Eastern time (daylight saving April to October)
      double n = daysSinceJ2000(year, month + 1, day + 1, utHour);
      double[] s = new double[4];
      double[] m = new double[4];
      sunEquatorial(n, s);
      moonEquatorial(n, m);
      double lat = Math.toRadians(latDeg);
      double hSun = Math.toRadians(15.0 * (hour - noonHour));
      horizontal(lat, s[1], hSun, sun);
      double hMoon = hSun - wrapPi(m[0] - s[0]); // the moon culminates when its right ascension does
      horizontal(lat, m[1], hMoon, moon);
      sunElevDeg = Math.toDegrees(Math.asin(clamp(sun[2], -1.0, 1.0)));
      moonElevDeg = Math.toDegrees(Math.asin(clamp(moon[2], -1.0, 1.0)));
      double elong = Math.acos(clamp(Math.cos(m[3]) * Math.cos(m[2] - s[2]), -1.0, 1.0)); // sun-moon angle
      moonLit = (1.0 - Math.cos(elong)) * 0.5;
      moonWaxing = wrapPi(m[2] - s[2]) > 0.0;
      moonBrightness = phaseBrightness(180.0 - Math.toDegrees(elong));
   }

   /** Days from J2000.0 (2000-01-01 12:00 UT) to the date (month 1-12, day 1-31) at utHour. */
   static double daysSinceJ2000(int year, int month1, int day1, double utHour) {
      long ed = java.time.LocalDate.of(year, Math.max(1, Math.min(12, month1)), 1).toEpochDay() + (day1 - 1);
      long j2000 = java.time.LocalDate.of(2000, 1, 1).toEpochDay();
      return (ed - j2000) - 0.5 + utHour / 24.0;
   }

   /** out = {right ascension, declination, ecliptic longitude, 0} of the sun (radians), n days from J2000. */
   static void sunEquatorial(double n, double[] out) {
      double L = Math.toRadians(280.460 + 0.9856474 * n);
      double g = Math.toRadians(357.528 + 0.9856003 * n);
      double lambda = L + Math.toRadians(1.915 * Math.sin(g) + 0.020 * Math.sin(2.0 * g));
      double eps = Math.toRadians(23.439 - 0.0000004 * n);
      out[0] = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
      out[1] = Math.asin(Math.sin(eps) * Math.sin(lambda));
      out[2] = lambda;
      out[3] = 0.0;
   }

   /** out = {right ascension, declination, ecliptic longitude, ecliptic latitude} of the moon (radians), n days from J2000. */
   static void moonEquatorial(double n, double[] out) {
      double T = n / 36525.0;
      double lon = 218.32 + 481267.881 * T
         + 6.29 * sind(135.0 + 477198.87 * T) - 1.27 * sind(259.3 - 413335.36 * T)
         + 0.66 * sind(235.7 + 890534.22 * T) + 0.21 * sind(269.9 + 954397.74 * T)
         - 0.19 * sind(357.5 + 35999.05 * T) - 0.11 * sind(186.5 + 966404.03 * T);
      double lat = 5.13 * sind(93.3 + 483202.02 * T) + 0.28 * sind(228.2 + 960400.89 * T)
         - 0.28 * sind(318.3 + 6003.15 * T) - 0.17 * sind(217.6 - 407332.21 * T);
      double l = Math.toRadians(lon), b = Math.toRadians(lat);
      double eps = Math.toRadians(23.439 - 0.0000004 * n);
      double x = Math.cos(b) * Math.cos(l);
      double y = Math.cos(eps) * Math.cos(b) * Math.sin(l) - Math.sin(eps) * Math.sin(b);
      double z = Math.sin(eps) * Math.cos(b) * Math.sin(l) + Math.cos(eps) * Math.sin(b);
      out[0] = Math.atan2(y, x);
      out[1] = Math.asin(clamp(z, -1.0, 1.0));
      out[2] = l;
      out[3] = b;
   }

   /** The direction (x east, y south, z up) of a body at declination dec and hour angle h (west positive) at latitude lat. */
   static void horizontal(double lat, double dec, double h, double[] out) {
      double up = Math.sin(lat) * Math.sin(dec) + Math.cos(lat) * Math.cos(dec) * Math.cos(h);
      double east = -Math.cos(dec) * Math.sin(h);
      double north = Math.cos(lat) * Math.sin(dec) - Math.sin(lat) * Math.cos(dec) * Math.cos(h);
      double len = Math.sqrt(up * up + east * east + north * north);
      out[0] = east / len;
      out[1] = -north / len;
      out[2] = up / len;
   }

   /** The moon's brightness relative to full at phase angle i (degrees): the lunar phase law's magnitude curve. */
   static double phaseBrightness(double iDeg) {
      double i = Math.abs(iDeg);
      double dm = 0.026 * i + 4.0e-9 * i * i * i * i;
      return Math.pow(10.0, -0.4 * dm);
   }

   private static double sind(double deg) {
      return Math.sin(Math.toRadians(deg));
   }

   static double wrapPi(double a) {
      a = a % (2.0 * Math.PI);
      if (a > Math.PI) {
         a -= 2.0 * Math.PI;
      } else if (a < -Math.PI) {
         a += 2.0 * Math.PI;
      }
      return a;
   }

   private static double clamp(double v, double lo, double hi) {
      return v < lo ? lo : v > hi ? hi : v;
   }
}
