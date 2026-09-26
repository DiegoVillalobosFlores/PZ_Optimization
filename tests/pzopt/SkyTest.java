package pzopt;

/**
 * The real sky (Sky) against known 1993 events at Knox County's latitude, and the cloud field's coverage (CloudShadow).
 */
public final class SkyTest {
  public static void main(String[] args) {
    // noon at the summer solstice, 38 N: 90 - 38 + 23.44 = 75.4 degrees, due south
    Sky.compute(1993, 5, 20, 13.0, 38.0, 13.0);
    Check.check(Math.abs(Sky.sunElevDeg - 75.4) < 0.4, "solstice noon elevation " + Sky.sunElevDeg);
    Check.check(Math.abs(Sky.azimuthDeg(Sky.sun) - 180.0) < 0.5, "noon sun due south " + Sky.azimuthDeg(Sky.sun));
    // the equinox: 52 degrees at noon, rising due east ~6 hours before noon
    Sky.compute(1993, 2, 19, 12.5, 38.0, 12.5);
    Check.check(Math.abs(Sky.sunElevDeg - 52.0) < 0.6, "equinox noon elevation " + Sky.sunElevDeg);
    Sky.compute(1993, 2, 19, 6.5, 38.0, 12.5);
    Check.check(Math.abs(Sky.sunElevDeg) < 1.0, "equinox sunrise elevation " + Sky.sunElevDeg);
    Check.check(Math.abs(Sky.azimuthDeg(Sky.sun) - 90.0) < 2.0, "equinox sunrise due east " + Sky.azimuthDeg(Sky.sun));
    // the afternoon sun is in the west: +x east, +y south, so the direction has x < 0
    Sky.compute(1993, 6, 9, 17.0, 38.0, 13.2);
    Check.check(Sky.sun[0] < -0.5 && Sky.sunElevDeg > 15.0, "afternoon sun in the west");

    // full moon 1993-07-03 (~23:45 UT): lit, opposite the sun, high around local midnight, down at noon
    Sky.compute(1993, 6, 3, 23.5, 38.0, 13.2);
    Check.check(Sky.moonLit > 0.97, "full moon lit " + Sky.moonLit);
    Check.check(Sky.moonElevDeg > 20.0, "full moon up at midnight " + Sky.moonElevDeg);
    Check.check(Sky.moonBrightness > 0.6, "full moon bright " + Sky.moonBrightness);
    Sky.compute(1993, 6, 3, 13.2, 38.0, 13.2);
    Check.check(Sky.moonElevDeg < -20.0, "full moon down at noon " + Sky.moonElevDeg);
    // new moon 1993-07-19 (~11:25 UT)
    Sky.compute(1993, 6, 18, 8.0, 38.0, 13.2);
    Check.check(Sky.moonLit < 0.03, "new moon dark " + Sky.moonLit);
    // first quarter 1993-07-26: half lit, waxing, highest around sunset
    Sky.compute(1993, 6, 25, 19.0, 38.0, 13.2);
    Check.check(Math.abs(Sky.moonLit - 0.5) < 0.12 && Sky.moonWaxing, "first quarter " + Sky.moonLit + " waxing " + Sky.moonWaxing);
    Check.check(Sky.moonBrightness > 0.05 && Sky.moonBrightness < 0.2, "quarter moon ~a tenth of full " + Sky.moonBrightness);
    Check.check(Math.abs(Sky.azimuthDeg(Sky.moon) - 180.0) < 40.0 && Sky.moonElevDeg > 15.0, "first quarter south at dusk " + Sky.azimuthDeg(Sky.moon));

    // cloud field: tiled noise, equalised so a threshold at 1 - c leaves a share c
    Check.check(Math.abs(CloudShadow.perlin(0.3F, 0.7F, 4, 5L) - CloudShadow.perlin(4.3F, 0.7F, 4, 5L)) < 1e-5, "perlin tiles");
    Check.check(Math.abs(CloudShadow.worley(0.3F, 0.7F, 4, 5L) - CloudShadow.worley(0.3F, 4.7F, 4, 5L)) < 1e-5, "worley tiles");
    long t0 = System.nanoTime();
    byte[] f = CloudShadow.generate(128, 0x5EEDC10DL);
    long ms = (System.nanoTime() - t0) / 1_000_000L;
    for (float c : new float[] {0.1F, 0.3F, 0.6F}) {
      int above = 0;
      for (int i = 0; i < 128 * 128; i++) {
        if ((f[i * 2] & 0xFF) / 255F > 1F - c) {
          above++;
        }
      }
      float share = above / (128F * 128F);
      Check.check(Math.abs(share - c) < 0.02, "cover " + c + " covers " + share);
    }
    System.out.println("SkyTest ok (field 128 in " + ms + " ms)");
  }
}
