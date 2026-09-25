package zombie.core;

import zombie.UsedFromLua;
import zombie.core.math.PZMath;
import zombie.core.textures.Texture; // pzopt: preview clip frames for the Optimizations tab
import zombie.iso.IsoPuddles;
import zombie.iso.IsoWater;
import zombie.ui.UIManager;

@UsedFromLua
public final class PerformanceSettings {
   public static int manualFrameSkips;
   private static int lockFps = 60;
   private static boolean uncappedFps;
   public static int waterQuality;
   public static int puddlesQuality;
   public static boolean newRoofHiding = true;
   public static boolean lightingThread = true;
   public static int lightingFps = 15;
   public static boolean auto3DZombies;
   public static final PerformanceSettings instance = new PerformanceSettings();
   public static boolean interpolateAnims = true;
   public static int animationSkip = 1;
   public static boolean modelLighting = true;
   public static int zombieAnimationSpeedFalloffCount = 6;
   public static int zombieBonusFullspeedFalloff = 3;
   public static int baseStaticAnimFramerate = 60;
   public static boolean useFbos;
   public static int numberZombiesBlended = 20;
   public static boolean fboRenderChunk = true;
   public static int fogQuality;
   public static int viewConeOpacity = 3;

   public static int getLockFPS() {
      return lockFps;
   }

   public static void setLockFPS(int lockFPS) {
      lockFps = lockFPS;
   }

   public boolean isFramerateUncapped() {
      return uncappedFps;
   }

   public void setFramerateUncapped(boolean uncappedFPS) {
      uncappedFps = uncappedFPS;
   }

   public int getFramerate() {
      return getLockFPS();
   }

   public void setFramerate(int framerate) {
      setLockFPS(framerate);
   }

   // pzopt: separate frame limiter for the menus (main menu, options); index convention of the
   // Display-options combo, see pzopt.FrameCap. Exposed to Lua because this class is.
   public int getMenuFramerateIndex() {
      return pzopt.FrameCap.menuIndex();
   }

   public void setMenuFramerateIndex(int index) {
      pzopt.FrameCap.setMenuIndex(index);
   }

   public int getMenuFramerateChoices() {
      return pzopt.FrameCap.MENU_CHOICES;
   }

   // pzopt: in-game cap from the extended "Framerate" combo; 0 = uncapped. Core.setFramerate only
   // knows the stock indices and options.ini cannot hold a value above 244.
   public void setGameFramerate(int fps) {
      pzopt.FrameCap.setGameFramerate(fps);
   }

   // pzopt: the "Optimizations" options tab (media/lua/client/pzopt/pzopt_optimizations_options.lua)
   // reads and writes the pzopt.Config keys through these; values are strings as in pzopt.properties.
   // Choices go to Zomboid/pzopt/options.ini (pzopt.UserOptions) and apply on the next launch; the
   // Profiler and Enhancements tabs' keys apply at once (pzopt.Config.reloadLive, pzopt.Enhancements).
   // The tab is offered whenever the build matches, also when the player switched every optimization
   // off (Config.enabled=false), so it can switch them back on.
   public boolean hasPzoptOptions() {
      return pzopt.Overrides.buildMatches();
   }

   // pzopt: the master switch as it is since boot: false = every override on its stock path.
   public boolean isPzoptEnabled() {
      return pzopt.Overrides.enabled();
   }

   public boolean isPzoptOptionKnown(String key) {
      return pzopt.Config.knows(key);
   }

   /** The value in force since boot ("" for an unknown key). */
   public String getPzoptOption(String key) {
      String v = pzopt.Config.value(key);
      return v == null ? "" : v;
   }

   public String getPzoptOptionDefault(String key) {
      String v = pzopt.Config.defaultValue(key);
      return v == null ? "" : v;
   }

   /** The value saved from the tab ("" when the default is in force). */
   public String getPzoptOptionSaved(String key) {
      String v = pzopt.UserOptions.get(key);
      return v == null ? "" : v;
   }

   /** "" or what overrides the tab for this key: "pzopt.properties" or "-Dpzopt.<key>". */
   public String getPzoptOptionPinnedBy(String key) {
      String v = pzopt.Config.pinnedBy(key);
      return v == null ? "" : v;
   }

   /** "" removes the key (default on the next launch). */
   public void setPzoptOption(String key, String value) {
      if (pzopt.Config.knows(key)) {
         pzopt.UserOptions.set(key, value);
      }
   }

   // pzopt: the tab's stock-vs-optimized preview clips (animated GIFs under media/ui/pzopt/compare/), decoded and
   // held as textures by pzopt.GifTextures. The Lua draws the frame returned for its clock and frees the clips
   // when the options screen closes.
   /** The frame of the GIF at {@code path} (media-relative) for {@code nowMs}, or null while it loads / if missing. */
   public Texture getPzoptGifFrame(String path, double nowMs) {
      return pzopt.GifTextures.frame(path, (long) nowMs);
   }

   /** "loading", "ready", "missing" or "error". */
   public String getPzoptGifState(String path) {
      return pzopt.GifTextures.state(path);
   }

   public int getPzoptGifWidth(String path) {
      return pzopt.GifTextures.width(path);
   }

   public int getPzoptGifHeight(String path) {
      return pzopt.GifTextures.height(path);
   }

   public void releasePzoptGifs() {
      pzopt.GifTextures.releaseAll();
   }

   // pzopt: the main menu's "Update PZ Optimization" item (media/lua/client/pzopt/pzopt_mainscreen_update.lua)
   // polls pzopt.Updater through these: one release check per boot, then the download + install on a
   // daemon thread; state names in Updater.State. The game restarts to load the new classes.
   public void pzoptUpdateCheck() {
      pzopt.Updater.check();
   }

   /** "idle", "checking", "up-to-date", "available", "downloading", "installing", "installed" or "error". */
   public String getPzoptUpdateState() {
      return pzopt.Updater.state();
   }

   public String getPzoptUpdateTag() {
      return pzopt.Updater.tag();
   }

   public String getPzoptUpdateNotes() {
      return pzopt.Updater.notes();
   }

   public String getPzoptUpdatePublished() {
      return pzopt.Updater.published();
   }

   public String getPzoptUpdatePageUrl() {
      return pzopt.Updater.pageUrl();
   }

   // pzopt: "workshop" (the Steam Workshop copy on disk, no download), "github" or "" (issue #16)
   public String getPzoptUpdateSource() {
      return pzopt.Updater.source();
   }

   public String getPzoptUpdateInstalledCommit() {
      return pzopt.Updater.installedCommit();
   }

   public String getPzoptUpdateMessage() {
      return pzopt.Updater.message();
   }

   public int getPzoptUpdateProgress() {
      return pzopt.Updater.progress();
   }

   public boolean canPzoptUpdateInstall() {
      return pzopt.Updater.canInstall();
   }

   public boolean pzoptUpdateInstall() {
      return pzopt.Updater.install();
   }

   // pzopt: the devUpdateDrive rig's mode for the menu Lua: "", "drive" or "restarted:<ms since the press>"
   public String getPzoptUpdateDrive() {
      return pzopt.Updater.drive();
   }

   // pzopt: "Restart game" after an update: a helper relaunches the game once this process ends (pzopt.Restart)
   public boolean pzoptRestartGame() {
      return pzopt.Restart.relaunch();
   }

   // pzopt: the Enhancements tab's "Install DLSS files" button polls pzopt.UpscalerDeps through these: one check
   // of what this machine needs, then the download into natives/ on a daemon thread; states in UpscalerDeps.State.
   public void pzoptUpscalerDepsCheck() {
      pzopt.UpscalerDeps.check();
   }

   /** "idle", "checking", "unsupported", "missing", "installed", "downloading", "installing", "done" or "error". */
   public String getPzoptUpscalerDepsState() {
      return pzopt.UpscalerDeps.state();
   }

   public String getPzoptUpscalerDepsMessage() {
      return pzopt.UpscalerDeps.message();
   }

   public int getPzoptUpscalerDepsProgress() {
      return pzopt.UpscalerDeps.progress();
   }

   public boolean pzoptUpscalerDepsInstall() {
      return pzopt.UpscalerDeps.install();
   }

   // pzopt: the "Performance overlay" item of the main and pause menus (media/lua/client/pzopt/pzopt_mainscreen_overlay.lua),
   // the same toggle as the key binding; with overlaySampling off the toggle shows the restart notice instead.
   public void togglePzoptOverlay() {
      pzopt.Overlay.toggle();
   }

   public boolean isPzoptOverlayVisible() {
      return pzopt.Overlay.isVisible();
   }

   public boolean isPzoptOverlaySampling() {
      return pzopt.Overlay.isSampling();
   }

   public void setLightingQuality(int lighting) {
   }

   public int getLightingQuality() {
      return 0;
   }

   public void setWaterQuality(int water) {
      waterQuality = water;
      IsoWater.getInstance().applyWaterQuality();
   }

   public int getWaterQuality() {
      return waterQuality;
   }

   public void setPuddlesQuality(int puddles) {
      puddlesQuality = puddles;
      if (puddles > 2 || puddles < 0) {
         puddlesQuality = 0;
      }

      IsoPuddles.getInstance().applyPuddlesQuality();
   }

   public int getPuddlesQuality() {
      return puddlesQuality;
   }

   public void setNewRoofHiding(boolean enabled) {
      newRoofHiding = enabled;
   }

   public boolean getNewRoofHiding() {
      return newRoofHiding;
   }

   public void setLightingFPS(int fps) {
      fps = Math.max(1, Math.min(120, fps));
      lightingFps = fps;
      System.out.println("LightingFPS set to " + lightingFps);
   }

   public int getLightingFPS() {
      return lightingFps;
   }

   public int getUIRenderFPS() {
      return UIManager.useUiFbo ? Core.getInstance().getOptionUIRenderFPS() : lockFps;
   }

   public int getFogQuality() {
      return fogQuality;
   }

   public void setFogQuality(int fogQuality) {
      PerformanceSettings.fogQuality = PZMath.clamp(fogQuality, 0, 2);
   }

   public int getViewConeOpacity() {
      return viewConeOpacity;
   }

   public void setViewConeOpacity(int viewConeOpacity) {
      PerformanceSettings.viewConeOpacity = PZMath.clamp(viewConeOpacity, 0, 5);
   }
}
