package pzopt;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.ThunderStorm;

/**
 * Lightning for the HDR output (Hdr, tune {@code flashMax}). A strike lights the whole view evenly, so the light map's
 * excess over the frame's median stays near zero and the flash would get no HDR at all: the one event in the game that
 * should hit the panel's peak. ThunderStorm keeps the flash per player in a private PlayerLightningInfo (state
 * ApplyLightning while it shows, strength x mod = how far the global light is pushed to white); read here on the game
 * thread with private-lookup VarHandles, no reflection per frame.
 */
final class HdrFlash {
   private HdrFlash() {
   }

   private static final VarHandle INFOS, STATE, STRENGTH, MOD;

   static {
      VarHandle infos = null, state = null, strength = null, mod = null;
      try {
         Class<?> info = Class.forName("zombie.iso.weather.ThunderStorm$PlayerLightningInfo");
         MethodHandles.Lookup storm = MethodHandles.privateLookupIn(ThunderStorm.class, MethodHandles.lookup());
         infos = storm.findVarHandle(ThunderStorm.class, "lightningInfos", info.arrayType());
         MethodHandles.Lookup l = MethodHandles.privateLookupIn(info, MethodHandles.lookup());
         state = l.findVarHandle(info, "lightningState", Class.forName("zombie.iso.weather.ThunderStorm$LightningState"));
         strength = l.findVarHandle(info, "lightningStrength", float.class);
         mod = l.findVarHandle(info, "lightningMod", float.class);
      } catch (Throwable t) {
         Log.warn("hdr flash: lightning state not reachable, no HDR lightning: " + t);
      }
      INFOS = infos;
      STATE = state;
      STRENGTH = strength;
      MOD = mod;
   }

   /** Game thread: player 0's flash now, 0 (none) .. 1 (full strike). */
   static float now() {
      if (INFOS == null) {
         return 0F;
      }
      try {
         ClimateManager cm = ClimateManager.getInstance();
         ThunderStorm ts = cm != null ? cm.getThunderStorm() : null;
         if (ts == null) {
            return 0F;
         }
         Object[] infos = (Object[])INFOS.get(ts);
         Object info = infos != null && infos.length > 0 ? infos[0] : null;
         if (info == null || !(STATE.get(info) instanceof Enum<?> e) || e.ordinal() != 1) { // ApplyLightning
            return 0F;
         }
         float f = (float)STRENGTH.get(info) * (float)MOD.get(info);
         return Math.max(0F, Math.min(1F, f));
      } catch (Throwable t) {
         return 0F;
      }
   }
}
