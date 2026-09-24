package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Properties;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameWindow;
import zombie.Lua.LuaManager;
import zombie.SystemDisabler;
import zombie.ZomboidFileSystem;
import zombie.core.PerformanceSettings;
import zombie.gameStates.GameLoadingState;
import zombie.ui.UIElement;

/**
 * Frame limiter: makes the game's own "Uncapped" option usable and adds a separate cap for
 * the menus.
 *
 * Stock has an "Uncapped" entry but it is dead: nothing sets the SystemDisabler flag that puts
 * it into the options-screen combo, and Core.loadOptions resets a saved uncappedFPS=true back
 * to a 60 fps lock. GameWindow.InitDisplay calls {@link #afterLoadOptions()} right after
 * loadOptions; it enables the combo entry and re-applies the saved frameRate / uncappedFPS
 * pair snapshotted by {@link #beforeLoadOptions()} (Core re-saves the file before we run). The limiter itself is the stock accumulator in
 * GameWindow.mainThreadStep, which now asks {@link #uncappedNow()} / {@link #lockNow()}: the
 * in-game values while a world is up or loading, the menu values otherwise and while the pause menu is up.
 *
 * The menu cap is one combo index ("Menu framerate" in Display options, added by
 * media/lua/client/pzopt/pzopt_framecap_options.lua): 1 = same as in-game, 2 = uncapped,
 * 3.. = the stock fps table. Persisted in Zomboid/pzopt/framecap.ini because Core.saveOptions
 * only writes the keys it knows.
 *
 * Config key {@code uncappedFps}: {@code auto} (default) honours options.ini; {@code true} /
 * {@code false} force the in-game cap off / on for a run without touching the player's settings.
 */
public final class FrameCap {
   /**
    * The combo's fps entries, highest first. 500..300 are pzopt additions (the stock combo stops at
    * 244); the rest is Core.setFramerate indices 2..14 in the same order. Also the table the Lua
    * combos show, so the two must agree.
    */
   static final int[] FPS_TABLE = {500, 430, 400, 330, 300, 244, 240, 165, 144, 120, 95, 90, 75, 60, 55, 45, 30, 24};
   /** Core's IntegerConfigOption for frameRate= rejects anything above this. */
   static final int STOCK_MAX_FPS = 244;

   /** Length of the last game-thread frame step (GameWindow.frameStep) in ns, without the limiter's wait between steps. */
   public static volatile long lastStepNs;

   public static void stepDone(long startNs) {
      lastStepNs = System.nanoTime() - startNs;
   }
   public static final int MIN_FPS = 24;
   public static final int MAX_FPS = 500;
   public static final int MENU_SAME = 1;
   public static final int MENU_UNCAPPED = 2;
   public static final int MENU_CHOICES = 2 + FPS_TABLE.length;

   private static volatile int menuIndex = MENU_SAME;

   private FrameCap() {
   }

   // --- main-loop queries -------------------------------------------------------------

   /** Main thread: loading or playing a save (the loading screen counts), as opposed to the menus. */
   static boolean inGame() {
      return GameWindow.isIngameState() || GameWindow.states.current instanceof GameLoadingState;
   }

   /**
    * Main thread: the pause menu (Esc in a game: the in-game MainScreen shown over the world, options screens
    * included) is up. It is a menu like the main menu, so it runs at the menu cap; stock only knows the in-game one
    * and an uncapped game drew its pause menu at several hundred fps.
    */
   static boolean pauseMenuUp() {
      if (!GameWindow.isIngameState()) {
         return false;
      }
      try {
         KahluaTable env = LuaManager.env;
         Object screen = env == null ? null : env.rawget("MainScreen");
         Object inst = screen instanceof KahluaTable t ? t.rawget("instance") : null;
         if (!(inst instanceof KahluaTable ms) || ms.rawget("inGame") != Boolean.TRUE) {
            return false;
         }
         return ms.rawget("javaObject") instanceof UIElement el && el.isVisible();
      } catch (Throwable t) {
         return false;
      }
   }

   /** Main thread: the menu cap applies now (not in a world or its loading screen, or the pause menu is up). */
   static boolean menuNow() {
      return !inGame() || pauseMenuUp();
   }

   /** Main thread only: is the current state's cap "uncapped"? */
   public static boolean uncappedNow() {
      if (Config.FRAME_CAP_FPS > 0 && Overrides.enabled()) {
         return false;
      }
      if (vrrCapNow() > 0 || MacPresent.active()) {
         return false;
      }
      return playerUncappedNow();
   }

   /**
    * While variable refresh is active (pzopt.Vrr), the cap that keeps frames inside its range, when the player's cap is
    * above it or uncapped; 0 otherwise. A forced uncappedFps=true run is left uncapped.
    */
   static int vrrCapNow() {
      if (!Config.VRR_CAP || !Vrr.active() || "true".equalsIgnoreCase(Config.UNCAPPED_FPS)) {
         return 0;
      }
      int cap = Vrr.cap();
      if (cap <= 0) {
         return 0;
      }
      return playerUncappedNow() || playerLockNow() > cap ? cap : 0;
   }

   private static boolean playerUncappedNow() {
      if (!Overrides.enabled() || menuIndex == MENU_SAME || !menuNow()) {
         return PerformanceSettings.instance.isFramerateUncapped();
      }
      return menuIndex == MENU_UNCAPPED;
   }

   /** Main thread only: the fps lock for the current state; only meaningful when not uncapped. */
   public static int lockNow() {
      if (Config.FRAME_CAP_FPS > 0 && Overrides.enabled()) {
         return Config.FRAME_CAP_FPS;
      }
      if (MacPresent.active()) {
         // macOS bridge: a rate the panel shows exactly (uncapped = its maximum), finer steps in fullscreen / borderless
         int base = playerUncappedNow() ? (int)Math.round(MacPresent.maxHz()) : playerLockNow();
         return MacPresent.snapFps(base > 0 ? base : 120, macAdaptive());
      }
      int vrr = vrrCapNow();
      return vrr > 0 ? vrr : playerLockNow();
   }

   private static boolean macAdaptive() {
      return org.lwjglx.opengl.Display.isFullscreen() || org.lwjglx.opengl.Display.pzoptIsBorderlessFullscreen() || MacPresent.nativeFullscreenNow();
   }

   private static int playerLockNow() {
      if (!Overrides.enabled() || menuIndex == MENU_SAME || !menuNow()) {
         return Math.max(1, PerformanceSettings.getLockFPS());
      }
      return menuIndex == MENU_UNCAPPED ? Math.max(1, PerformanceSettings.getLockFPS()) : FPS_TABLE[menuIndex - 3];
   }

   // --- per-phase frame counter: one console line per menu/game transition ----------------

   private static boolean phaseInGame;
   private static long phaseStartNs;
   private static int phaseFrames;

   /** Main thread, after every frameStep: logs "menu 3.2 s, 144 frames, 45.0 fps" when the phase flips. */
   public static void onFrame(long nowNs) {
      boolean game = !menuNow();
      if (phaseStartNs == 0L) {
         phaseInGame = game;
         phaseStartNs = nowNs;
      } else if (game != phaseInGame) {
         double secs = (nowNs - phaseStartNs) / 1.0e9;
         Log.info(String.format(java.util.Locale.ROOT, "frame cap: %s phase %.1f s, %d frames, %.1f fps (cap %s)",
            phaseInGame ? "game" : "menu", secs, phaseFrames, secs > 0 ? phaseFrames / secs : 0.0,
            phaseInGame ? describe() : describeMenu()));
         phaseInGame = game;
         phaseStartNs = nowNs;
         phaseFrames = 0;
      }
      phaseFrames++;
   }

   // --- menu option --------------------------------------------------------------------

   public static int menuIndex() {
      return menuIndex;
   }

   /** From the options screen; persists immediately. */
   public static void setMenuIndex(int index) {
      int clamped = Math.max(MENU_SAME, Math.min(MENU_CHOICES, index));
      if (clamped == menuIndex) {
         return;
      }
      menuIndex = clamped;
      save();
      Log.info("frame cap: menu " + describeMenu());
   }

   private static File file() {
      return new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt" + File.separator + "framecap.ini");
   }

   // --- persistence ---------------------------------------------------------------------
   //
   // options.ini cannot hold the whole in-game choice: Core.loadOptions feeds frameRate= through an
   // IntegerConfigOption clamped to 24..244, resets a saved uncappedFPS=true to a 60 fps lock, and
   // then re-saves the file, all before we run; Core.saveOptions refuses a lock above 244 the same
   // way. So the raw frameRate= / uncappedFPS= lines are snapshotted before Core loads
   // (beforeLoadOptions) and a cap above 244 lives in framecap.ini (gameFps=).

   /** In-game cap above the stock 244 chosen in the combo, 0 when the choice is a stock value. */
   private static volatile int extraGameFps;
   /** Raw options.ini values as they were before Core.loadOptions rewrote the file. */
   private static int rawLock = -1;
   private static Boolean rawUncapped;
   /** Player's state before a forced uncappedFps=true/false run; restored on the next boot. */
   private static String restore;

   private static void load() {
      File f = file();
      if (!f.isFile()) {
         return;
      }
      Properties p = new Properties();
      try (FileReader r = new FileReader(f)) {
         p.load(r);
         int idx = Integer.parseInt(p.getProperty("menuFramerateIndex", String.valueOf(MENU_SAME)).trim());
         menuIndex = Math.max(MENU_SAME, Math.min(MENU_CHOICES, idx));
         int extra = Integer.parseInt(p.getProperty("gameFps", "0").trim());
         extraGameFps = extra > STOCK_MAX_FPS && extra <= MAX_FPS ? extra : 0;
         restore = p.getProperty("restore");
      } catch (Exception e) {
         Log.warn("could not read " + f + ": " + e);
      }
   }

   private static void save() {
      File f = file();
      try {
         f.getParentFile().mkdirs();
         try (FileWriter w = new FileWriter(f)) {
            w.write("# pzopt frame limiter; indices into the Display-options combos\n");
            w.write("# menu: 1 = same as in-game, 2 = uncapped, 3.. = 500 430 400 330 300 244 240 165 144 120 95 90 75 60 55 45 30 24\n");
            w.write("menuFramerateIndex=" + menuIndex + "\n");
            w.write("# in-game cap above 244 (options.ini cannot hold it), 0 = use options.ini\n");
            w.write("gameFps=" + extraGameFps + "\n");
            if (restore != null) {
               w.write("# player's cap before a forced uncappedFps run: lock,uncapped,gameFps; re-applied on the next boot\n");
               w.write("restore=" + restore + "\n");
            }
         }
      } catch (Exception e) {
         Log.warn("could not write " + f + ": " + e);
      }
   }

   /** GameWindow.InitDisplay, before Core.loadOptions: snapshot the two lines Core is about to rewrite. */
   public static void beforeLoadOptions() {
      if (!Overrides.enabled()) {
         return;
      }
      File ini = new File(ZomboidFileSystem.instance.getCacheDir(), "options.ini");
      if (!ini.isFile()) {
         return;
      }
      try (BufferedReader r = new BufferedReader(new FileReader(ini))) {
         String line;
         while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("frameRate=")) {
               try {
                  rawLock = Integer.parseInt(line.substring("frameRate=".length()).trim());
               } catch (NumberFormatException ignored) {
               }
            } else if (line.startsWith("uncappedFPS=")) {
               rawUncapped = Boolean.parseBoolean(line.substring("uncappedFPS=".length()).trim());
            }
         }
      } catch (Exception e) {
         Log.warn("could not read " + ini + ": " + e);
      }
   }

   /** GameWindow.InitDisplay, after Core.loadOptions: make "Uncapped" selectable and apply the saved cap. */
   public static void afterLoadOptions() {
      if (!Overrides.enabled()) {
         return;
      }
      SystemDisabler.setUncappedFPS(true);
      load();
      if (restore != null) {
         // The previous run forced the cap and the game saved that forced state on quit.
         try {
            String[] parts = restore.split(",");
            rawLock = Integer.parseInt(parts[0].trim());
            rawUncapped = Boolean.parseBoolean(parts[1].trim());
            extraGameFps = Integer.parseInt(parts[2].trim());
         } catch (Exception e) {
            Log.warn("frame cap: bad restore entry '" + restore + "'");
         }
         restore = null;
         save();
      }
      applySaved();
      String mode = Config.UNCAPPED_FPS;
      if ("true".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(mode)) {
         restore = PerformanceSettings.getLockFPS() + "," + PerformanceSettings.instance.isFramerateUncapped() + "," + extraGameFps;
         save();
         PerformanceSettings.instance.setFramerateUncapped(Boolean.parseBoolean(mode));
      }
      Log.info("frame cap: game " + describe() + " (uncappedFps=" + mode + "), menu " + describeMenu());
   }

   /** Re-applies the player's choice over what Core.loadOptions left behind. */
   private static void applySaved() {
      if (extraGameFps > 0) {
         PerformanceSettings.setLockFPS(extraGameFps);
      } else if (rawLock >= MIN_FPS && rawLock <= STOCK_MAX_FPS) {
         PerformanceSettings.setLockFPS(rawLock); // Core reset it to 60 when uncappedFPS=true was saved
      }
      if (rawUncapped != null) {
         PerformanceSettings.instance.setFramerateUncapped(rawUncapped);
      }
   }

   // --- in-game option -------------------------------------------------------------------

   /** From the options screen: 0 = uncapped, else the fps lock; persists what options.ini cannot. */
   public static void setGameFramerate(int fps) {
      if (fps <= 0) {
         PerformanceSettings.instance.setFramerateUncapped(true);
      } else {
         PerformanceSettings.instance.setFramerateUncapped(false);
         PerformanceSettings.setLockFPS(Math.max(MIN_FPS, Math.min(MAX_FPS, fps)));
      }
      int extra = fps > STOCK_MAX_FPS ? Math.min(MAX_FPS, fps) : 0;
      if (extra != extraGameFps) {
         extraGameFps = extra;
         save();
      }
      Log.info("frame cap: game " + describe());
   }

   public static String describe() {
      return PerformanceSettings.instance.isFramerateUncapped() ? "uncapped" : PerformanceSettings.getLockFPS() + " fps";
   }

   public static String describeMenu() {
      if (menuIndex == MENU_SAME) {
         return "same as game";
      }
      return menuIndex == MENU_UNCAPPED ? "uncapped" : FPS_TABLE[menuIndex - 3] + " fps";
   }
}
