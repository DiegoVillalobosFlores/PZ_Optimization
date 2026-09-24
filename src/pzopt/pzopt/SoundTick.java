package pzopt;

/**
 * The rate of the game's per-frame sound upkeep ({@code soundTickHz}, default 60; 0 = every frame like stock).
 * FMOD Studio runs asynchronously: what the game sets (event parameters, 3D positions, starts) is queued and applied by
 * Studio's own update, every 20 ms by default, so at 110-240 fps the ambient-object slots, the wall emitters, the
 * listener's ambience parameters and the busy zombies' parameters were recomputed 2-5 times for every update FMOD
 * uses. One decision per frame (IsoCamera's frame counter): a frame is due once 7/8 of the period has passed, so a
 * 60 fps game ticks every frame and nothing changes under the tick rate. Users: the ObjectAmbientEmitters and
 * FMODAmbientWalls overrides (update), AmbientStreamManager (the listener parameter block), IsoGameCharacter
 * (updateEmitter's parameter refresh; a frame that starts a sound always refreshes).
 */
public final class SoundTick {
   private static int frame = Integer.MIN_VALUE;
   private static boolean due = true;
   private static long lastNs;

   private SoundTick() {
   }

   public static boolean due() {
      int hz = Config.SOUND_TICK_HZ;
      if (hz <= 0 || !Overrides.enabled()) {
         return true;
      }
      int f = zombie.iso.IsoCamera.frameState.frameCount;
      if (f != frame) {
         frame = f;
         long now = System.nanoTime();
         long step = 1_000_000_000L / hz;
         if (now - lastNs >= step - step / 8) {
            due = true;
            lastNs = now - lastNs > 2 * step ? now : lastNs + step; // keep the cadence, restart it after a stall
         } else {
            due = false;
         }
      }
      return due;
   }
}
