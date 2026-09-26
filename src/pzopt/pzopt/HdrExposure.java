package pzopt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.LightingJNI;
import zombie.iso.areas.IsoRoom;

/**
 * A local lighting reference that does not depend on camera zoom, facing, or visibility shading.
 */
final class HdrExposure {
  // Fixed world-space neighborhoods for both night adaptation and local light references.
  private static final int RADIUS = 4;
  private static final VarHandle LIGHT_LEVEL;
  private static final VarHandle UPDATE_TICK;
  // Game-thread scratch; sampling never calls JNILighting.update() or writes native lighting data.
  private static final float[] RGB = new float[3];

  static {
    VarHandle light = null, tick = null;
    try {
      MethodHandles.Lookup lookup =
          MethodHandles.privateLookupIn(LightingJNI.JNILighting.class, MethodHandles.lookup());
      light = lookup.findVarHandle(LightingJNI.JNILighting.class, "lightLevel", int.class);
      tick = lookup.findVarHandle(LightingJNI.JNILighting.class, "updateTick", int.class);
    } catch (ReflectiveOperationException e) {
      Log.warn("hdr exposure: raw lighting cache unavailable; exposure enhancement disabled: " + e);
    }
    LIGHT_LEVEL = light;
    UPDATE_TICK = tick;
  }

  /** An immutable game-thread snapshot, published in draw order before the HDR passes. */
  static final class Sample extends TextureDraw.GenericDrawer {
    static final Sample UNAVAILABLE = new Sample(1F, 0, 0, 0, Integer.MIN_VALUE);
    final float luminance;
    final int squares, x, y, z;

    Sample(float luminance, int squares, int x, int y, int z) {
      this.luminance = luminance;
      this.squares = squares;
      this.x = x;
      this.y = y;
      this.z = z;
    }

    @Override
    public void render() {
      current = this;
    }
  }

  // Render thread only. The floor selects compatible maps; luminance is diagnostic, not a gain
  // input.
  static Sample current = Sample.UNAVAILABLE;

  private HdrExposure() {}

  /** Render thread: do not apply a stale map while the current floor's replacement is building. */
  static boolean matchesFloor(int mapZ) {
    return current.z != Integer.MIN_VALUE && current.z == mapZ;
  }

  /**
   * Game thread: capture the player's floor in draw order. Optionally sample player-local light for
   * diagnostics. The rendering references are built per square by LocalAmbient.
   */
  static Sample sample(IsoCell cell, IsoGridSquare center, float gamma, boolean diagnostics) {
    if (cell == null || center == null) {
      return Sample.UNAVAILABLE;
    }
    if (!diagnostics) return new Sample(1F, 0, center.x, center.y, center.z);
    int count = 0;
    double luminance = 0;
    var room = center.getRoom();
    for (int y = center.y - RADIUS; y <= center.y + RADIUS; y++) {
      for (int x = center.x - RADIUS; x <= center.x + RADIUS; x++) {
        IsoGridSquare square = cell.getGridSquare(x, y, center.z);
        if (square == null || square.getRoom() != room || !readLight(square, RGB)) {
          continue;
        }
        float r = clamp(RGB[0]), g = clamp(RGB[1]), b = clamp(RGB[2]);
        luminance +=
            0.2126 * Math.pow(r, gamma) + 0.7152 * Math.pow(g, gamma) + 0.0722 * Math.pow(b, gamma);
        count++;
      }
    }
    return new Sample(
        count > 0 ? (float) (luminance / count) : 1F, count, center.x, center.y, center.z);
  }

  /** Cached light before visibility shading. Scratch RGB belongs to the calling thread. */
  private static boolean readLight(IsoGridSquare square, float[] rgb) {
    if (square == null
        || LIGHT_LEVEL == null
        || UPDATE_TICK == null
        || !(square.lighting[0] instanceof LightingJNI.JNILighting lighting)
        || (int) UPDATE_TICK.get(lighting) < 0) return false;
    // Native lightLevel is 0x00BBGGRR, before darkMulti. The legacy GetRLightLevel()
    // accessor interprets these channels in reverse, so read the packed cache directly.
    int packed = (int) LIGHT_LEVEL.get(lighting);
    rgb[0] = (packed & 255) / 255F;
    rgb[1] = ((packed >>> 8) & 255) / 255F;
    rgb[2] = ((packed >>> 16) & 255) / 255F;
    // Native source membership supplies wall occlusion; use maximum channel contributions
    // with linear falloff, as in the native base-light calculation, rather than summing lamps.
    for (int i = 0, n = lighting.resultLightCount(); i < n; i++) {
      addSource(rgb, square.x, square.y, square.z, lighting.getResultLight(i));
    }
    return true;
  }

  /**
   * Worker-owned light cache. Each map square gets a median from its own nine-by-nine neighborhood,
   * restricted to its room. The full-resolution halo makes that reference independent of map bounds
   * and sampling step. Read each square's native cache only once per build.
   */
  static final class LocalAmbient {
    int x0, y0, width, height;
    int[] values = new int[0]; // maximum channel, 0..255; -1 means unavailable
    IsoRoom[] rooms = new IsoRoom[0]; // null is outdoors, not unavailable
    float[] luminances = new float[0]; // linear luminance before visibility shading

    /** Mean linear luminance from the last at() query; 1 for unavailable neighborhoods. */
    float meanLuminance = 1F;

    private final int[] histogram = new int[256];
    private final float[] rgb = new float[3];

    void reset(int x, int y, int w, int h) {
      x0 = x;
      y0 = y;
      width = w;
      height = h;
      int size = w * h;
      if (values.length < size) {
        int capacity = Math.max(size, values.length * 2);
        values = new int[capacity];
        rooms = new IsoRoom[capacity];
        luminances = new float[capacity];
      }
      Arrays.fill(values, 0, size, -1);
      clear();
    }

    void prepare(IsoCell cell, int x, int y, int w, int h, int step, int z, float gamma) {
      reset(
          x - RADIUS, y - RADIUS, (w - 1) * step + 1 + 2 * RADIUS, (h - 1) * step + 1 + 2 * RADIUS);
      for (int row = 0; row < height; row++) {
        for (int col = 0; col < width; col++) {
          IsoGridSquare square = cell.getGridSquare(x0 + col, y0 + row, z);
          if (!readLight(square, rgb)) continue;
          int i = row * width + col;
          float r = clamp(rgb[0]), g = clamp(rgb[1]), b = clamp(rgb[2]);
          values[i] = Math.round(Math.max(r, Math.max(g, b)) * 255F);
          luminances[i] =
              (float)
                  (0.2126 * Math.pow(r, gamma)
                      + 0.7152 * Math.pow(g, gamma)
                      + 0.0722 * Math.pow(b, gamma));
          rooms[i] = square.getRoom();
        }
      }
    }

    /**
     * Return the local median and publish its matching mean in meanLuminance. Worker thread only.
     */
    float at(int x, int y, float daylightFloor) {
      meanLuminance = 1F;
      int col = x - x0, row = y - y0;
      // Never substitute a clipped neighborhood or an unknown square with darkness.
      if (col < RADIUS || row < RADIUS || col + RADIUS >= width || row + RADIUS >= height)
        return 1F;
      int center = row * width + col;
      if (values[center] < 0) return 1F;
      IsoRoom room = rooms[center];
      Arrays.fill(histogram, 0);
      int count = 0;
      double sum = 0;
      for (int dy = -RADIUS; dy <= RADIUS; dy++) {
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
          int i = center + dy * width + dx;
          if (values[i] >= 0 && rooms[i] == room) {
            histogram[values[i]]++;
            sum += luminances[i];
            count++;
          }
        }
      }
      meanLuminance = (float) (sum / count);
      return Math.max(daylightFloor, median(histogram, count) / 255F);
    }

    /** Release room references after every build, including failed builds, so worlds can unload. */
    void clear() {
      Arrays.fill(rooms, null);
    }
  }

  /**
   * Directional lights still illuminate the scene, but do not set whole-scene exposure when
   * turning.
   */
  static void addSource(float[] rgb, int x, int y, int z, IsoGridSquare.ResultLight light) {
    if (light == null || light.radius <= 0 || (light.flags & 2) != 0) return;
    float dx = x - light.x, dy = y - light.y, dz = (z - light.z) * 3F;
    float falloff =
        Math.max(0F, 1F - (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / light.radius);
    rgb[0] = Math.max(rgb[0], light.r * falloff);
    rgb[1] = Math.max(rgb[1], light.g * falloff);
    rgb[2] = Math.max(rgb[2], light.b * falloff);
  }

  static int median(int[] histogram, int count) {
    if (count <= 0) throw new IllegalArgumentException("Expected at least one lighting sample");
    int sum = 0;
    for (int i = 0; i < histogram.length; i++) {
      sum += histogram[i];
      if (sum >= (count + 1) / 2) return i;
    }
    throw new IllegalArgumentException("Histogram contains fewer samples than count");
  }

  /** CPU equivalent of the local night-key calculation, used by diagnostics. */
  static float night(float luminance, float low, float high, float cap) {
    float s = clamp((luminance - low) / Math.max(1e-6F, high - low));
    return Math.min(1F - s * s * (3F - 2F * s), cap);
  }

  private static float clamp(float value) {
    return Math.max(0F, Math.min(1F, value));
  }
}
