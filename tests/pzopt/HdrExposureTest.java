package pzopt;

import zombie.iso.IsoGridSquare;
import zombie.iso.areas.IsoRoom;

/**
 * Local exposure math and immutable draw-order publication; no GL context or native lighting calls.
 */
public final class HdrExposureTest {
  public static void main(String[] args) {
    int[] histogram = new int[256];
    histogram[210] = 1;
    Check.check(HdrExposure.median(histogram, 1) == 210, "one sample must not select an empty bin");
    histogram[25] = 2;
    Check.check(
        HdrExposure.median(histogram, 3) == 25, "median ignores the isolated bright sample");

    IsoGridSquare.ResultLight lamp = new IsoGridSquare.ResultLight();
    lamp.x = 10;
    lamp.y = 20;
    lamp.z = 1;
    lamp.radius = 10;
    lamp.r = 1F;
    lamp.g = 0.5F;
    lamp.b = 0.25F;
    float[] rgb = {0.2F, 0.1F, 0.05F};
    HdrExposure.addSource(rgb, 15, 20, 1, lamp);
    Check.check(rgb[0] == 0.5F && rgb[1] == 0.25F && rgb[2] == 0.125F, "linear source falloff");
    HdrExposure.addSource(rgb, 15, 20, 1, lamp);
    Check.check(rgb[0] == 0.5F, "overlapping sources take the maximum rather than double exposure");
    lamp.flags = 2;
    HdrExposure.addSource(rgb, 10, 20, 1, lamp);
    Check.check(rgb[0] == 0.5F, "turning a directional light cannot set global exposure");
    lamp.flags = 0;
    lamp.radius = 0;
    HdrExposure.addSource(rgb, 10, 20, 1, lamp);
    HdrExposure.addSource(rgb, 10, 20, 1, null);
    Check.check(rgb[0] == 0.5F, "invalid or absent sources do not affect exposure");

    HdrExposure.Sample dark = new HdrExposure.Sample(0.005F, 20, 100, 200, 1);
    HdrExposure.Sample bright = new HdrExposure.Sample(0.5F, 20, 100, 200, 1);
    Check.check(
        HdrExposure.night(dark.luminance, 0.01F, 0.04F, 0.6F) == 0.6F,
        "climate caps night enhancement");
    Check.check(
        HdrExposure.night(bright.luminance, 0.01F, 0.04F, 1F) == 0F,
        "lit surroundings disable night expansion");
    Check.check(
        HdrExposure.night(HdrExposure.Sample.UNAVAILABLE.luminance, 0.01F, 0.04F, 1F) == 0F,
        "unknown lighting is not darkness");
    Check.check(
        HdrExposure.current == HdrExposure.Sample.UNAVAILABLE,
        "sampling does not publish ahead of rendering");
    dark.render();
    Check.check(HdrExposure.current == dark, "sample is published in draw order");
    bright.render();
    Check.check(
        HdrExposure.current == bright && dark.luminance == 0.005F,
        "later samples cannot mutate earlier ones");
    Check.check(HdrExposure.matchesFloor(1), "uploaded map matches the rendered floor");
    new HdrExposure.Sample(1F, 0, 100, 200, 2).render();
    Check.check(
        !HdrExposure.matchesFloor(1) && HdrExposure.matchesFloor(2),
        "floor selection works without diagnostics and rejects stale maps");
    HdrExposure.Sample.UNAVAILABLE.render();
    Check.check(
        !HdrExposure.matchesFloor(2) && !HdrExposure.matchesFloor(Integer.MIN_VALUE),
        "missing player context cannot select a map");
    localReferences();
    System.out.println("HdrExposureTest ok");
  }

  private static void localReferences() {
    IsoRoom left = new IsoRoom(), right = new IsoRoom();
    HdrExposure.LocalAmbient near = new HdrExposure.LocalAmbient();
    near.reset(100, 200, 18, 9);
    fillRooms(near, left, right, 230);
    float rightBefore = near.at(112, 204, 0F);
    Check.check(rightBefore == 230F / 255F, "lit room reference");
    float rightLuminance = near.meanLuminance;
    Check.check(
        HdrExposure.night(rightLuminance, 0.01F, 0.04F, 1F) == 0F,
        "lit room has no night amplification");
    fillRooms(near, left, right, 10);
    Check.check(near.at(106, 204, 0F) == 10F / 255F, "own room responds to its switch");
    Check.check(
        HdrExposure.night(near.meanLuminance, 0.01F, 0.04F, 1F) == 1F,
        "blocking incoming light allows night amplification in the darkened room");
    Check.check(
        near.at(112, 204, 0F) == rightBefore,
        "turning off the neighboring room cannot increase this room's light excess");
    Check.check(
        near.meanLuminance == rightLuminance
            && HdrExposure.night(near.meanLuminance, 0.01F, 0.04F, 1F) == 0F,
        "darkening the player's room cannot enable night amplification in its lit neighbor");
    Check.check(near.at(106, 204, 0.8F) == 0.8F, "daylight still floors local references");

    HdrExposure.LocalAmbient wide = new HdrExposure.LocalAmbient();
    wide.reset(96, 196, 26, 17);
    fillRooms(wide, left, right, 10);
    Check.check(
        wide.at(106, 204, 0F) == near.at(106, 204, 0F) && wide.at(112, 204, 0F) == rightBefore,
        "changing map coverage cannot change a world square's reference");
    // Outdoors uses the same local neighborhood, not a single shared outdoor reference.
    fillRooms(wide, null, null, 10);
    float distantOutdoor = wide.at(116, 204, 0F);
    fillRooms(wide, null, null, 0);
    Check.check(
        wide.at(116, 204, 0F) == distantOutdoor,
        "a distant outdoor change does not affect the local neighborhood");
    Check.check(wide.at(100, 204, 0F) == 0F, "initialized darkness remains valid");
    wide.values[(204 - wide.y0) * wide.width + 116 - wide.x0] = -1;
    Check.check(wide.at(116, 204, 0F) == 1F, "missing center lighting disables excess");
    Check.check(
        wide.meanLuminance == 1F, "missing center lighting also disables night amplification");
    Check.check(near.at(100, 204, 0F) == 1F, "a clipped neighborhood is not a dark reference");
    wide.reset(0, 0, 9, 9);
    wide.values[4 * 9 + 4] = 230;
    wide.luminances[4 * 9 + 4] = rightLuminance;
    Check.check(wide.at(4, 4, 0F) == 230F / 255F, "missing neighbors are not counted as darkness");
    near.clear();
    for (IsoRoom room : near.rooms)
      Check.check(room == null, "build cleanup releases room references");
  }

  private static void fillRooms(
      HdrExposure.LocalAmbient field, IsoRoom left, IsoRoom right, int leftLight) {
    for (int row = 0; row < field.height; row++) {
      for (int col = 0; col < field.width; col++) {
        int i = row * field.width + col;
        boolean inLeftRoom = field.x0 + col < 109;
        field.values[i] = inLeftRoom ? leftLight : 230;
        field.luminances[i] = (float) Math.pow(field.values[i] / 255F, 2.2);
        field.rooms[i] = inLeftRoom ? left : right;
      }
    }
  }
}
