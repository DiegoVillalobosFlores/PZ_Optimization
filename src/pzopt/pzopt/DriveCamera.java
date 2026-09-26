package pzopt;

/**
 * The driving camera's look-ahead (`driveLookSmooth`, 2026-09-26, docs/findings-car-jitter-2026-09-26.md). Stock
 * {@code PlayerCamera.update} ("pan camera while driving") truncates the look-ahead target and the look-ahead itself to
 * whole pixels and paces its approach with {@code System.currentTimeMillis()} deltas (4 or 5 ms at 240 fps, 8 or 9 at
 * 120). With the key on the look-ahead stays fractional and the pace comes from {@code System.nanoTime()}; the camera
 * offset the world is drawn at is still truncated once, in {@code getOffX/Y}.
 */
public final class DriveCamera {
   private DriveCamera() {
   }

   static final boolean LOOK_SMOOTH = Config.DRIVE_LOOK_SMOOTH;
   private static final long[] lastNs = new long[4];

   private static boolean on() {
      return LOOK_SMOOTH && Overrides.enabled();
   }

   /** Stock's value, or the same ratio from the frame time in nanoseconds. */
   public static float panMult(int playerIndex, float stockMult, float panSpeed) {
      if (!on() || playerIndex < 0 || playerIndex >= lastNs.length) {
         return stockMult;
      }
      long now = System.nanoTime();
      long last = lastNs[playerIndex];
      lastNs[playerIndex] = now;
      if (last == 0L) {
         return stockMult;
      }
      return Math.max(1e-4f, (float)((now - last) / 1e9) * panSpeed);
   }

   static final boolean SCREEN_PIXELS = Config.CAMERA_SCREEN_PIXELS;

   /**
    * The camera offset the world is drawn at (PlayerCamera.getOffX/Y and friends): stock truncates it to whole offscreen
    * pixels, which zoomed in (zoom &lt; 1: one offscreen pixel is 1 / zoom screen pixels) moves the world in steps of 2 screen
    * pixels at zoom 0.5. With cameraScreenPixels it is truncated to whole screen pixels instead (multiples of the zoom in
    * offscreen pixels): the world buffer is screen-sized and every pass divides the offset by the zoom, so sprites and chunk
    * textures still land on whole pixels. Zoom 1 and out: stock.
    */
   public static float snap(float v, int playerIndex) {
      if (!SCREEN_PIXELS || !Overrides.enabled()) {
         return (float)(int)v;
      }
      float z = zombie.core.Core.getInstance().getZoom(playerIndex);
      if (!(z < 0.999f) || z <= 0.01f) {
         return (float)(int)v;
      }
      return z * (float)(int)(v / z);
   }

   /** PlayerCamera.XToIso / YToIso: stock truncates the screen point; a fractional camera needs it whole. */
   public static float isoScreen(float v) {
      return (LOOK_SMOOTH || SCREEN_PIXELS) && Overrides.enabled() ? v : (float)(int)v;
   }

   /** Whole pixels (stock) or the value as is. */
   public static float px(float v) {
      return on() ? v : (float)(int)v;
   }
}
