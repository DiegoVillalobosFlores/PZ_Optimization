package pzopt;

/**
 * The Options > Enhancements tab's keys apply while the game runs (2026-09-25): {@link UserOptions#set} saves the
 * key, Config's live reload reads it again, and this hands it to the class that owns it.
 *
 * <ul>
 *   <li>upscaler, upscalerQuality, upscalerScalePct, dlssPreset, dlssOutputPct, dlssOutputFilter, dlssSharpen:
 *       {@link RenderScale#reconfigure} (new mode and scale from the next frame; the DLSS feature is built again).
 *       fsrSharpnessPct, upscalerObjectMv, dlssWaterCurrent and dlssWaterHistoryPct are read every frame (the water
 *       resources are made on first use).</li>
 *   <li>the HDR sliders: {@link Hdr#retune} (read every frame). hdr and hdrAuto are not live: on Linux they pick the
 *       window (a native Wayland FP16 surface) the game is created with.</li>
 *   <li>ambientOcclusion, aoScalePct, aoRadiusPct, the four aoStrength*Pct: {@link ChunkAo#reconfigure} (every loaded
 *       chunk texture bakes again with the new AO, or without it); sunShadows the same, sunShadowStrengthPct and
 *       sunShadowSoftnessPct through {@link SunShadow#update} (the kept shadows compute again, no re-bake).</li>
 * </ul>
 */
final class Enhancements {
   private Enhancements() {
   }

   /** Is this key one of the Enhancements tab's (as opposed to the Profiler tab's overlay keys)? */
   static boolean owns(String key) {
      return key.startsWith("upscaler") || key.startsWith("dlss") || key.startsWith("fsr") || key.startsWith("hdr")
         || key.equals("ambientOcclusion") || key.startsWith("ao") || key.startsWith("sunShadow");
   }

   /** Game thread, after Config.reloadLive(key) returned true. */
   static void apply(String key) {
      switch (key) {
         case "fsrSharpnessPct", "upscalerObjectMv", "dlssWaterCurrent", "dlssWaterHistoryPct" -> {
            // read every frame
         }
         case "upscaler", "upscalerQuality", "upscalerScalePct", "dlssPreset", "dlssOutputPct", "dlssOutputFilter", "dlssSharpen" ->
            RenderScale.reconfigure();
         case "ambientOcclusion", "aoScalePct", "aoRadiusPct", "aoStrengthFloorPct", "aoStrengthWallPct", "aoStrengthObjectPct",
               "aoStrengthVegetationPct", "sunShadows" -> ChunkAo.reconfigure();
         case "sunShadowStrengthPct", "sunShadowSoftnessPct", "sunShadowCharacters", "sunShadowVehicles", "sunShadowTorches" -> {
            // SunShadow.update sees the new strength / penumbra next frame and recomputes the kept shadows (no re-bake);
            // the capsule pass reads its three switches every frame
         }
         default -> {
            if (key.startsWith("hdr")) {
               Hdr.retune();
            }
         }
      }
   }
}
