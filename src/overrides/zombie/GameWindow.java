package zombie;

import fmod.fmod.FMODManager;
import fmod.fmod.FMODSoundBank;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjglx.LWJGLException;
import org.lwjglx.input.Controller;
import org.lwjglx.opengl.Display;
import org.lwjglx.opengl.DisplayMode;
import org.lwjglx.opengl.OpenGLException;
import zombie.GameProfiler.ProfileArea;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.asset.AssetManagers;
import zombie.audio.BaseSoundBank;
import zombie.audio.DummySoundBank;
import zombie.characters.IsoPlayer;
import zombie.characters.animals.AnimalPopulationManager;
import zombie.characters.skills.CustomPerks;
import zombie.characters.skills.PerkFactory;
import zombie.core.BoxedStaticValues;
import zombie.core.Core;
import zombie.core.Languages;
import zombie.core.PZForkJoinPool;
import zombie.core.PerformanceSettings;
import zombie.core.SceneShaderStore;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.Translator;
import zombie.core.fonts.AngelCodeFont;
import zombie.core.input.Input;
import zombie.core.logger.ExceptionLogger;
import zombie.core.logger.ZipLogs;
import zombie.core.math.PZMath;
import zombie.core.opengl.RenderThread;
import zombie.core.profiling.AbstractPerformanceProfileProbe;
import zombie.core.profiling.PerformanceProfileFrameProbe;
import zombie.core.profiling.PerformanceProfileProbe;
import zombie.core.raknet.RakNetPeerInterface;
import zombie.core.raknet.VoiceManager;
import zombie.core.random.RandLua;
import zombie.core.random.RandStandard;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.IsoObjectAnimations;
import zombie.core.skinnedmodel.population.BeardStyles;
import zombie.core.skinnedmodel.population.ClothingDecals;
import zombie.core.skinnedmodel.population.HairStyles;
import zombie.core.skinnedmodel.population.OutfitManager;
import zombie.core.skinnedmodel.population.VoiceStyles;
import zombie.core.textures.NinePatchTexture;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.core.textures.TexturePackPage;
import zombie.core.znet.ServerBrowser;
import zombie.core.znet.SteamFriends;
import zombie.core.znet.SteamUtils;
import zombie.core.znet.SteamWorkshop;
import zombie.debug.DebugContext;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LineDrawer;
import zombie.debug.LogSeverity;
import zombie.entity.GameEntityManager;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.FileSystemImpl;
import zombie.fileSystem.FileSystem.TexturePackTextures;
import zombie.gameStates.GameLoadingState;
import zombie.gameStates.GameStateMachine;
import zombie.gameStates.IngameState;
import zombie.gameStates.MainScreenState;
import zombie.gameStates.TISLogoState;
import zombie.gameStates.TermsOfServiceState;
import zombie.globalObjects.SGlobalObjects;
import zombie.input.GameKeyboard;
import zombie.input.JoypadManager;
import zombie.input.Mouse;
import zombie.input.JoypadManager.Joypad;
import zombie.inventory.types.MapItem;
import zombie.iso.FishSchoolManager;
import zombie.iso.InstanceTracker;
import zombie.iso.IsoCamera;
import zombie.iso.IsoObjectPicker;
import zombie.iso.IsoWorld;
import zombie.iso.LightingJNI;
import zombie.iso.LightingThread;
import zombie.iso.MetaTracker;
import zombie.iso.SliceY;
import zombie.iso.WorldStreamer;
import zombie.iso.sprite.IsoCursor;
import zombie.iso.sprite.IsoReticle;
import zombie.iso.worldgen.WorldGenParams;
import zombie.network.CoopMaster;
import zombie.network.CustomizationManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.pathfind.PolygonalMap2;
import zombie.pathfind.nativeCode.PathfindNative;
import zombie.popman.ZombiePopulationManager;
import zombie.radio.ZomboidRadio;
import zombie.sandbox.CustomSandboxOptions;
import zombie.savefile.ClientPlayerDB;
import zombie.savefile.PlayerDB;
import zombie.savefile.SavefileThumbnail;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModRegistries;
import zombie.seams.SeamManager;
import zombie.seating.SeatingManager;
import zombie.spnetwork.SinglePlayerClient;
import zombie.spnetwork.SinglePlayerServer;
import zombie.spriteModel.SpriteModelManager;
import zombie.statistics.StatisticsManager;
import zombie.tileDepth.TileDepthMapManager;
import zombie.tileDepth.TileDepthTextureAssignmentManager;
import zombie.tileDepth.TileDepthTextureManager;
import zombie.tileDepth.TileGeometryManager;
import zombie.tileDepth.TileSeamManager;
import zombie.ui.TextManager;
import zombie.ui.UIElement;
import zombie.ui.UIFont;
import zombie.ui.UIManager;
import zombie.util.PZSQLUtils;
import zombie.vehicles.Clipper;
import zombie.vehicles.VirtualVehicleManager;
import zombie.world.moddata.GlobalModData;
import zombie.worldMap.WorldMapImages;
import zombie.worldMap.WorldMapJNI;
import zombie.worldMap.WorldMapVisited;

@UsedFromLua
public final class GameWindow {
   private static final String GAME_TITLE = "Project Zomboid";
   private static final FPSTracking s_fpsTracking = new FPSTracking();
   private static final ThreadLocal<GameWindow.StringUTF> stringUTF = ThreadLocal.withInitial(GameWindow.StringUTF::new);
   public static final Input GameInput = new Input();
   public static final boolean DEBUG_SAVE = false;
   public static boolean okToSaveOnExit;
   public static String lastP;
   public static GameStateMachine states = new GameStateMachine();
   public static boolean serverDisconnected;
   public static boolean loadedAsClient;
   public static String kickReason;
   public static boolean drawReloadingLua;
   public static Joypad activatedJoyPad;
   public static String version = "RC3";
   public static volatile boolean closeRequested;
   public static float averageFPS = PerformanceSettings.getLockFPS();
   private static boolean doRenderEvent;
   public static boolean luaDebuggerKeyDown;
   public static FileSystem fileSystem = new FileSystemImpl();
   public static AssetManagers assetManagers = new AssetManagers(fileSystem);
   private static long currentTime;
   private static long accumulator;
   public static boolean gameThreadExited;
   public static Thread gameThread;
   private static long updateTime;
   public static final ArrayList<GameWindow.TexturePack> texturePacks = new ArrayList<>();
   public static final TexturePackTextures texturePackTextures = new TexturePackTextures();

   private static void initShared() throws Exception {
      String path = ZomboidFileSystem.instance.getCacheDir() + File.separator;
      File file = new File(path);
      if (!file.exists()) {
         file.mkdirs();
      }

      TexturePackPage.ignoreWorldItemTextures = true;
      int flags = 2;
      LoadTexturePack("UI", 2);
      LoadTexturePack("UI2", 2);
      LoadTexturePack("IconsMoveables", 2);
      LoadTexturePack("RadioIcons", 2);
      LoadTexturePack("ApComUI", 2);
      LoadTexturePack("Mechanics", 2);
      LoadTexturePack("WeatherFx", 2);
      setTexturePackLookup();
      IsoCursor.getInstance();
      IsoReticle.initInstances();
      MainScreenState.preloadBackgroundTextures();
      PerkFactory.init();
      CustomPerks.instance.init();
      CustomPerks.instance.initLua();
      CustomSandboxOptions.instance.init();
      CustomSandboxOptions.instance.initInstance(SandboxOptions.instance);
      ModRegistries.init();
      DoLoadingText(Translator.getText("UI_Loading_Scripts", new Object[0]));
      pzopt.BootAsync.joinFmod(); // pzopt: GameSounds.ScriptsLoaded (end of Load) is the first FMOD user
      ScriptManager.instance.Load();
      if (pzopt.Config.DUMP_ITEMS) {
         pzopt.ScriptDump.dumpItems(); // pzopt: equivalence check for itemParamSwitch
      }

      CustomizationManager.getInstance().load();
      SpriteModelManager.getInstance().init();
      if (pzopt.Config.EARLY_MODELS && pzopt.Overrides.enabled()) {
         // pzopt: register the models and the 3,990 animation files now (enter() finds it created and skips), so
         // the boot pump's file pool imports them during the Lua load instead of after the main menu
         ModelManager.instance.create();
         pzopt.BootAsync.startAnimSets();
      }
      DoLoadingText(Translator.getText("UI_Loading_Clothing", new Object[0]));
      ClothingDecals.init();
      BeardStyles.init();
      HairStyles.init();
      OutfitManager.init();
      VoiceStyles.init();
      DoLoadingText("");
      RandStandard.INSTANCE.init();
      RandLua.INSTANCE.init();
      TexturePackPage.ignoreWorldItemTextures = false;
      TextureID.useCompression = TextureID.useCompressionOption;
      Mouse.initCustomCursor();
      TileGeometryManager.getInstance().init();
      TileDepthTextureAssignmentManager.getInstance().init();
      if (pzopt.Config.EARLY_TILE_PACKS && pzopt.Overrides.enabled()) {
         // pzopt: earlyTilePacks, the tile packs register here (same flags, same order after the UI packs) and the 218
         // depth-map loads are queued here, instead of in enter() after the Lua load: their ~12 + 4 thread-s of decode
         // run during the Lua load and not into Continue
         loadTilePacks();
         pzoptTilePacksLoaded = true;
         TileDepthTextureManager.getInstance().init();
         pzopt.TileDefPreload.start(); // needs the mods and the translations only; starts as early as it can
      }
      SeamManager.getInstance().init();
      SeatingManager.getInstance().init();
      if (!Core.debug || !DebugOptions.instance.uiDisableLogoState.getValue()) {
         states.states.add(new TISLogoState());
      }

      states.states.add(new TermsOfServiceState());
      states.states.add(new MainScreenState());
      if (!Core.debug) {
         states.loopToState = 1;
      }

      GameInput.initControllers();
      if (Core.getInstance().isDefaultOptions() && SteamUtils.isSteamModeEnabled() && SteamUtils.isRunningOnSteamDeck()) {
         Core.getInstance().setOptionActiveController(0, true);
      }

      int counta = GameInput.getControllerCount();
      DebugType.Input.println("----------------------------------------------");
      DebugType.Input.println("--    Information about controllers     ");
      DebugType.Input.println("----------------------------------------------");

      for (int m = 0; m < counta; m++) {
         Controller controller = GameInput.getController(m);
         if (controller != null) {
            DebugType.Input.println("----------------------------------------------");
            DebugType.Input.println("--  Joypad: " + controller.getGamepadName());
            DebugType.Input.println("----------------------------------------------");
            int count = controller.getAxisCount();
            if (count > 1) {
               DebugType.Input.println("----------------------------------------------");
               DebugType.Input.println("--    Axis definitions for controller " + m);
               DebugType.Input.println("----------------------------------------------");

               for (int n = 0; n < count; n++) {
                  String name = controller.getAxisName(n);
                  DebugType.Input.println("Axis: " + name);
               }
            }

            count = controller.getButtonCount();
            if (count > 1) {
               DebugType.Input.println("----------------------------------------------");
               DebugType.Input.println("--    Button definitions for controller " + m);
               DebugType.Input.println("----------------------------------------------");

               for (int n = 0; n < count; n++) {
                  String name = controller.getButtonName(n);
                  DebugType.Input.println("Button: " + name);
               }
            }
         }
      }
   }

   private static void logic() {
      Display.imGuiNewFrame();
      if (Core.debug) {
         try {
            DebugContext.instance.tickFrameStart();
         } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
         }
      }

      if (GameClient.client) {
         try {
            GameClient.instance.update();
         } catch (Exception ex) {
            ExceptionLogger.logException(ex);
         }
      }

      try {
         SinglePlayerServer.update();
         SinglePlayerClient.update();
      } catch (Throwable t) {
         ExceptionLogger.logException(t);
      }

      GameProfiler profiler = GameProfiler.getInstance();
      ProfileArea uiVoice = profiler.profile("Steam Loop");

      try {
         SteamUtils.runLoop();
      } catch (Throwable var25) {
         if (uiVoice != null) {
            try {
               uiVoice.close();
            } catch (Throwable var13) {
               var25.addSuppressed(var13);
            }
         }

         throw var25;
      }

      if (uiVoice != null) {
         uiVoice.close();
      }

      pzopt.InputLatch.beforeInputSwap(); // pzopt: frameStartGate / inputLatch, a fresh input poll right before the swaps
      uiVoice = profiler.profile("Mouse");

      try {
         Mouse.update();
      } catch (Throwable var24) {
         if (uiVoice != null) {
            try {
               uiVoice.close();
            } catch (Throwable var12) {
               var24.addSuppressed(var12);
            }
         }

         throw var24;
      }

      if (uiVoice != null) {
         uiVoice.close();
      }

      uiVoice = profiler.profile("Keyboard");

      try {
         GameKeyboard.update();
      } catch (Throwable var23) {
         if (uiVoice != null) {
            try {
               uiVoice.close();
            } catch (Throwable var11) {
               var23.addSuppressed(var11);
            }
         }

         throw var23;
      }

      if (uiVoice != null) {
         uiVoice.close();
      }

      GameInput.updateGameThread();
      pzopt.InputLag.afterGameInput(); // pzopt: harness input-lag probe, the input this game frame sees
      if (CoopMaster.instance != null) {
         CoopMaster.instance.update();
      }

      if (IsoPlayer.players[0] != null) {
         IsoPlayer.setInstance(IsoPlayer.players[0]);
         IsoCamera.setCameraCharacter(IsoPlayer.players[0]);
      }

      uiVoice = profiler.profile("UI");

      try {
         UIManager.update();
      } catch (Throwable var22) {
         if (uiVoice != null) {
            try {
               uiVoice.close();
            } catch (Throwable var10) {
               var22.addSuppressed(var10);
            }
         }

         throw var22;
      }

      if (uiVoice != null) {
         uiVoice.close();
      }

      CompletableFuture<Void> uiVoicex = null;
      if (DebugOptions.instance.threadSound.getValue()) {
         uiVoicex = CompletableFuture.runAsync(() -> {
            VoiceManager.instance.update();
            SoundManager.instance.Update();
         }, PZForkJoinPool.commonPool());
      }

      LineDrawer.clear();
      if (JoypadManager.instance.isAPressed(-1)) {
         for (int n = 0; n < JoypadManager.instance.joypadList.size(); n++) {
            Joypad joypad = (Joypad)JoypadManager.instance.joypadList.get(n);
            if (joypad.isAPressed()) {
               if (activatedJoyPad == null) {
                  activatedJoyPad = joypad;
               }

               if (IsoPlayer.getInstance() != null) {
                  LuaEventManager.triggerEvent("OnJoypadActivate", joypad.getID());
               } else {
                  LuaEventManager.triggerEvent("OnJoypadActivateUI", joypad.getID());
               }
               break;
            }
         }
      }

      boolean doUpdate = !GameTime.isGamePaused();
      ProfileArea var31 = profiler.profile("Collision Data");

      try {
         MapCollisionData.instance.updateGameState();
      } catch (Throwable var21) {
         if (var31 != null) {
            try {
               var31.close();
            } catch (Throwable var9) {
               var21.addSuppressed(var9);
            }
         }

         throw var21;
      }

      if (var31 != null) {
         var31.close();
      }

      CombatManager.getInstance().update(doUpdate);
      Mouse.setCursorVisible(Core.getInstance().displayCursor);
      if (doUpdate) {
         states.update();
         pzopt.NoLoadingScreen.afterStateUpdate(states.current); // pzopt: noLoadingScreen, no fade from black into the world
      } else {
         IsoCamera.updateAll();
         if (isIngameState()) {
            LuaEventManager.triggerEvent("OnTickEvenPaused", BoxedStaticValues.toDouble(0.0));
         }
      }

      if (uiVoicex != null) {
         uiVoicex.join();
      } else {
         var31 = profiler.profile("Voice");

         try {
            VoiceManager.instance.update();
         } catch (Throwable var20) {
            if (var31 != null) {
               try {
                  var31.close();
               } catch (Throwable var8) {
                  var20.addSuppressed(var8);
               }
            }

            throw var20;
         }

         if (var31 != null) {
            var31.close();
         }

         var31 = profiler.profile("Sound");

         try {
            SoundManager.instance.Update();
         } catch (Throwable var19) {
            if (var31 != null) {
               try {
                  var31.close();
               } catch (Throwable var7) {
                  var19.addSuppressed(var7);
               }
            }

            throw var19;
         }

         if (var31 != null) {
            var31.close();
         }
      }

      var31 = profiler.profile("UI Resize");

      try {
         UIManager.resize();
      } catch (Throwable var18) {
         if (var31 != null) {
            try {
               var31.close();
            } catch (Throwable var6) {
               var18.addSuppressed(var6);
            }
         }

         throw var18;
      }

      if (var31 != null) {
         var31.close();
      }

      WorldMapImages.checkLoadingQueue();
      fileSystem.updateAsyncTransactions();
      if (GameKeyboard.isKeyPressed("Take screenshot")) {
         Core.getInstance().TakeFullScreenshot(null);
      }

      if (Core.debug) {
         try {
            DebugContext.instance.tick();
         } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
         }
      }
   }

   public static boolean isIngameState() {
      return IngameState.instance != null && (states.current == IngameState.instance || states.states.contains(IngameState.instance));
   }

   public static void render() {
      IsoCamera.frameState.frameCount++;
      IsoCamera.frameState.updateUnPausedAccumulator();
      renderInternal();
   }

   protected static void renderInternal() {
      SpriteRenderer.instance.NewFrame();
      if (!PerformanceSettings.lightingThread && LightingJNI.init && !LightingJNI.WaitingForMain()) {
         LightingJNI.DoLightingUpdateNew(System.nanoTime(), Core.dirtyGlobalLightsCount > 0);
         if (Core.dirtyGlobalLightsCount > 0) {
            Core.dirtyGlobalLightsCount--;
         }
      }

      IsoObjectPicker.Instance.StartRender();
      LightingJNI.preUpdate();
      AbstractPerformanceProfileProbe var0 = GameWindow.s_performance.statesRender.profile();

      try {
         states.render();
      } catch (Throwable var4) {
         if (var0 != null) {
            try {
               var0.close();
            } catch (Throwable var3) {
               var4.addSuppressed(var3);
            }
         }

         throw var4;
      }

      if (var0 != null) {
         var0.close();
      }
   }

   public static void InitDisplay() throws IOException, LWJGLException {
      Display.setTitle("Project Zomboid");
      pzopt.FrameCap.beforeLoadOptions(); // pzopt: snapshot frameRate / uncappedFPS before Core clamps and re-saves them
      if (!Core.getInstance().loadOptions()) {
         Core.setFullScreen(true);
         Display.setFullscreen(true);
         Display.setResizable(false);
         DisplayMode displayMode = Display.getDesktopDisplayMode();
         Core.getInstance().init(displayMode.getWidth(), displayMode.getHeight());
         if (!GL.getCapabilities().GL_ATI_meminfo && !GL.getCapabilities().GL_NVX_gpu_memory_info) {
            DebugType.General.warn("Unable to determine available GPU memory, texture compression defaults to on");
            TextureID.useCompressionOption = true;
            TextureID.useCompression = true;
         }

         DebugType.General.debugln("Init language : " + System.getProperty("user.language"));
         Core.getInstance().setOptionLanguageName(System.getProperty("user.language").toUpperCase());
         Core.getInstance().saveOptions();
      } else {
         Core.getInstance().init(Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight());
      }

      pzopt.FrameCap.afterLoadOptions(); // pzopt: enable the Uncapped combo entry and re-apply the saved frameRate / uncappedFPS
      SpriteRenderer.instance.create();
   }

   public static void InitGameThread() {
      Thread.setDefaultUncaughtExceptionHandler(GameWindow::uncaughtGlobalException);
      gameThread = MainThread.init(GameWindow::mainThreadStart, GameWindow::mainThreadStep, GameWindow::mainThreadExit, GameWindow::uncaughtExceptionMainThread);
   }

   private static void uncaughtExceptionMainThread(Thread thread, Throwable e) {
      try {
         uncaughtException(thread, e);
      } finally {
         onGameThreadExited();
      }
   }

   private static void uncaughtGlobalException(Thread thread, Throwable e) {
      uncaughtException(thread, e);
   }

   public static void uncaughtException(Thread thread, Throwable e) {
      String exceptionMessage = String.format("Unhandled %s thrown by thread %s.", e.getClass().getName(), thread.getName());
      DebugType.General.error(exceptionMessage);
      ExceptionLogger.logException(e, exceptionMessage);
   }

   private static void mainThreadStart() {
      mainThreadInit();
      enter();
      pzopt.BootPump.stop(); // pzopt: from here GameWindow.logic pumps the file system every frame
      RenderThread.invokeOnRenderContext(() -> {
         GL20.glUseProgram(0);
         ShaderHelper.forgetCurrentlyBound();
      });
      RenderThread.setWaitForRenderState(true);
      currentTime = System.nanoTime();
   }

   private static void mainThreadStep() {
      long newTime = System.nanoTime();
      if (newTime < currentTime) {
         currentTime = newTime;
      } else {
         long timeDiffNS = newTime - currentTime;
         currentTime = newTime;
         if (pzopt.FrameCap.uncappedNow()) { // stock limiter shape; pzopt.FrameCap picks the in-game or the menu cap (and makes "Uncapped" selectable and persistent)
            pzopt.Pacing.stepStart(newTime, 0L); // pzopt: the game time this frame shows (VRR pacing, pzopt-pacing.out)
            frameStep();
            pzopt.FrameCap.onFrame(newTime); // pzopt: per-phase frame counter for the console (menu vs game)
            pzopt.FrameCap.stepDone(newTime); // pzopt: the step's own length, without the limiter wait (zoom bake plan)
         } else {
            accumulator += timeDiffNS;
            long desiredDt = PZMath.secondsToNanos / pzopt.FrameCap.lockNow();
            desiredDt = pzopt.VsyncLock.desiredDt(desiredDt, pzopt.FrameCap.lockNow()); // pzopt: vsyncLock, vsync paces a cap that divides the refresh
            if (accumulator >= desiredDt) {
               long pzoptShift = pzopt.MacPresent.takeStepShiftNs(desiredDt); // pzopt: macOS present bridge phase control
               if (pzoptShift > 0L) { // pzopt
                  pzopt.Pacing.waitUntil(newTime + pzoptShift); // pzopt: start this step later, the frame was early for its panel slot
                  newTime = System.nanoTime(); // pzopt
                  currentTime = newTime; // pzopt: the wait is not frame time
               } // pzopt
               pzopt.Pacing.stepStart(newTime, desiredDt); // pzopt: the game time this frame shows (VRR pacing, pzopt-pacing.out)
               frameStep();
               pzopt.FrameCap.onFrame(newTime); // pzopt: per-phase frame counter for the console (menu vs game)
               pzopt.FrameCap.stepDone(newTime); // pzopt: the step's own length, without the limiter wait (zoom bake plan)
               accumulator %= desiredDt;
               if (pzoptShift < 0L) { // pzopt: frames ran late for their slots: bring the next step forward
                  accumulator -= pzoptShift; // pzopt
               } // pzopt
            } else if (pzopt.Config.LIMITER_SLEEP && pzopt.Overrides.enabled()) { // pzopt: sleep most of the wait instead of spinning a core
               pzopt.Pacing.limiterWait(newTime + (desiredDt - accumulator)); // pzopt: the stock loop spins the last limiterSpinUs
            }
         }

         if (Core.debug && DebugOptions.instance.threadCrashEnabled.getValue()) {
            DebugOptions.testThreadCrash(0);
            RenderThread.invokeOnRenderContext(() -> DebugOptions.testThreadCrash(1));
         }
      }
   }

   private static void mainThreadExit() {
      exit();
   }

   private static void mainThreadInit() {
      String debug = System.getProperty("debug");
      String viewports = System.getProperty("imguidebugviewports");
      String imgui = System.getProperty("imgui");
      String nosave = System.getProperty("nosave");
      if (nosave != null) {
         Core.getInstance().setNoSave(true);
      }

      if (debug != null) {
         Core.debug = true;
         if (viewports != null) {
            Core.useViewports = true;
         }

         if (imgui != null) {
            Core.imGui = true;
         }
      }

      if (!Core.soundDisabled) {
         if (pzopt.BootAsync.fmodAsync()) {
            pzopt.BootAsync.startFmod(FMODManager.instance::init); // pzopt: joined in initShared before the scripts load
         } else {
            FMODManager.instance.init();
         }
      }

      DebugOptions.instance.init();
      GameProfiler.init();
      // pzopt: the sound singletons are built after the FMOD init (at once when it was synchronous, else at the
      // join in initShared). SoundManager and AmbientStreamManager construct FMODGlobalParameters (MusicState,
      // MusicIntensity, TimeOfDay, ...) whose constructors resolve their descriptions from the banks; built while
      // the banks are still loading they resolve to null, never register, and the menu music never stops (issue #3).
      // Core.soundDisabled is read here too, so a failed init on the thread still yields the Dummy managers.
      pzopt.BootAsync.afterFmod(() -> {
         pzopt.AudioLimiter.install(); // pzopt: audioLimiter, the master limiter DSP once FMOD is up
         SoundManager.instance = (BaseSoundManager)(Core.soundDisabled ? new DummySoundManager() : new SoundManager());
         AmbientStreamManager.instance = (BaseAmbientStreamManager)(Core.soundDisabled ? new DummyAmbientStreamManager() : new AmbientStreamManager());
         BaseSoundBank.instance = (BaseSoundBank)(Core.soundDisabled ? new DummySoundBank() : new FMODSoundBank());
         VoiceManager.instance.loadConfig();
         SoundManager.instance.setSoundVolume(Core.getInstance().getOptionSoundVolume() / 10.0F); // pzopt: the VCAs live in the banks
         SoundManager.instance.setMusicVolume(Core.getInstance().getOptionMusicVolume() / 10.0F);
         SoundManager.instance.setAmbientVolume(Core.getInstance().getOptionAmbientVolume() / 10.0F);
         SoundManager.instance.setVehicleEngineVolume(Core.getInstance().getOptionVehicleEngineVolume() / 10.0F);
      });

      while (!RenderThread.isRunning()) {
         Thread.yield();
      }

      TextureID.useCompressionOption = Core.safeModeForced || Core.getInstance().getOptionTextureCompression();
      TextureID.useCompression = TextureID.useCompressionOption;

      try {
         ZomboidFileSystem.instance.init();
      } catch (Exception e) {
         throw new RuntimeException(e);
      }

      DebugFileWatcher.instance.init();
      String server = System.getProperty("server");
      String nozombies = System.getProperty("nozombies");
      if (nozombies != null) {
         IsoWorld.noZombies = true;
      }

      if (server != null && server.equals("true")) {
         GameServer.server = true;
      }

      if (!GameServer.server && pzopt.Overrides.buildMatches()) {
         pzopt.Updater.check(); // pzopt: the release check runs during the boot, its answer is ready when the menu appears
      }

      try {
         renameSaveFolders();
         init();
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   private static void renameSaveFolders() {
      String saveDirPath = ZomboidFileSystem.instance.getSaveDir();
      File saves = new File(saveDirPath);
      if (saves.exists() && saves.isDirectory()) {
         File fighter = new File(saves, "Fighter");
         File survivor = new File(saves, "Survivor");
         if (fighter.exists() && fighter.isDirectory() && survivor.exists() && survivor.isDirectory()) {
            DebugType.General.debugln("RENAMING Saves/Survivor to Saves/Apocalypse");
            DebugType.General.debugln("RENAMING Saves/Fighter to Saves/Survivor");
            survivor.renameTo(new File(saves, "Apocalypse"));
            fighter.renameTo(new File(saves, "Survivor"));
            File latestSave = new File(ZomboidFileSystem.instance.getCacheDir() + File.separator + "latestSave.ini");
            if (latestSave.exists()) {
               latestSave.delete();
            }
         }
      }
   }

   public static long readLong(DataInputStream in) throws IOException {
      int ch1 = in.read();
      int ch2 = in.read();
      int ch3 = in.read();
      int ch4 = in.read();
      int ch5 = in.read();
      int ch6 = in.read();
      int ch7 = in.read();
      int ch8 = in.read();
      if ((ch1 | ch2 | ch3 | ch4 | ch5 | ch6 | ch7 | ch8) < 0) {
         throw new EOFException();
      } else {
         return ch1 + (ch2 << 8) + (ch3 << 16) + (ch4 << 24) + (ch5 << 32) + (ch6 << 40) + (ch7 << 48) + (ch8 << 56);
      }
   }

   public static int readInt(DataInputStream in) throws IOException {
      int ch1 = in.read();
      int ch2 = in.read();
      int ch3 = in.read();
      int ch4 = in.read();
      if ((ch1 | ch2 | ch3 | ch4) < 0) {
         throw new EOFException();
      } else {
         return ch1 + (ch2 << 8) + (ch3 << 16) + (ch4 << 24);
      }
   }

   private static boolean pzoptTilePacksLoaded; // pzopt: earlyTilePacks

   /** pzopt: the tile-pack block of enter(), callable from initShared (earlyTilePacks). */
   private static void loadTilePacks() {
      Core.tileScale = Core.getInstance().getOptionTexture2x() ? 2 : 1;
      int flags = TextureID.useCompression ? 4 : 0;
      flags |= 64;
      if (Core.tileScale == 1) {
         LoadTexturePack("Tiles1x", flags);
         LoadTexturePack("Overlays1x", flags);
         LoadTexturePack("JumboTrees1x", flags);
         LoadTexturePack("Tiles1x.floor", flags & -5);
      }

      if (Core.tileScale == 2) {
         LoadTexturePack("Tiles2x", flags);
         LoadTexturePack("Overlays2x", flags);
         LoadTexturePack("JumboTrees2x", flags);
         LoadTexturePack("JumboTreesBigs2x", flags);
         LoadTexturePack("Tiles2x.floor", flags & -5);
         LoadTexturePack("B42ChunkCaching2x", flags);
         LoadTexturePack("B42ChunkCaching2x.floor", flags & -5);
         LoadTexturePack("Clock2x", flags);
      }

      setTexturePackLookup();
   }

   private static void enter() {
      Core.tileScale = Core.getInstance().getOptionTexture2x() ? 2 : 1;
      IsoCamera.init();
      if (pzoptTilePacksLoaded) { // pzopt: earlyTilePacks, registered in initShared already
         setTexturePackLookup();
      } else {
      int flags = TextureID.useCompression ? 4 : 0;
      flags |= 64;
      if (Core.tileScale == 1) {
         LoadTexturePack("Tiles1x", flags);
         LoadTexturePack("Overlays1x", flags);
         LoadTexturePack("JumboTrees1x", flags);
         LoadTexturePack("Tiles1x.floor", flags & -5);
      }

      if (Core.tileScale == 2) {
         LoadTexturePack("Tiles2x", flags);
         LoadTexturePack("Overlays2x", flags);
         LoadTexturePack("JumboTrees2x", flags);
         LoadTexturePack("JumboTreesBigs2x", flags);
         LoadTexturePack("Tiles2x.floor", flags & -5);
         LoadTexturePack("B42ChunkCaching2x", flags);
         LoadTexturePack("B42ChunkCaching2x.floor", flags & -5);
         LoadTexturePack("Clock2x", flags);
      }

      setTexturePackLookup();
      } // pzopt: end of the stock tile-pack block
      Texture.getSharedTexture("animated_clock_01_0");
      Texture.getSharedTexture("animated_clock_01_1");
      Texture.getSharedTexture("animated_clock_01_2");
      Texture.getSharedTexture("animated_clock_01_3");
      if (Texture.getSharedTexture("TileIndieStoneTentFrontLeft") == null) {
         throw new RuntimeException("Rebuild Tiles.pack with \"1 Include This in .pack\" as individual images not tilesheets");
      }

      pzopt.TileDefPreload.start(); // pzopt: tile sprites for the first world load build on a thread while the menu loads
      pzopt.AotCache.start(); // pzopt: aotCache, the next launch's launcher form (record / use), decided on a daemon thread

      DebugType.General.debugln("LOADED UP A TOTAL OF " + Texture.totalTextureID + " TEXTURES");
      s_fpsTracking.init();
      DoLoadingText(Translator.getText("UI_Loading_ModelsAnimations", new Object[0]));
      ModelManager.instance.create();
      pzopt.BootAsync.startAnimSets(); // pzopt: no-op if already started from initShared
      if (!pzoptTilePacksLoaded) { // pzopt: earlyTilePacks initialised it in initShared
         TileDepthTextureManager.getInstance().init();
      }
      TileDepthMapManager.instance.init();
      TileSeamManager.instance.init();
      VoiceManager.instance.InitVMClient();
      DoLoadingText(Translator.getText("UI_Loading_OnGameBoot", new Object[0]));
      LuaEventManager.triggerEvent("OnGameBoot");
      UIManager.setShowLuaDebuggerOnError(true);
      if (Core.debug) {
         DebugContext.instance.init();
      }
   }

   private static void frameStep() {
      long startTime = System.nanoTime();
      IsoCamera.frameState.frameCount++;
      IsoCamera.frameState.updateUnPausedAccumulator();

      try {
         AbstractPerformanceProfileProbe ex = GameWindow.s_performance.frameStep.profile();

         try {
            s_fpsTracking.frameStep();
            pzopt.FrameClock.afterFpsTracking(); // pzopt: frameClockSmooth, the frame's simulation step on the display's grid
            AbstractPerformanceProfileProbe profiler = GameWindow.s_performance.logic.profile();

            try {
               logic();
            } catch (Throwable var29) {
               if (profiler != null) {
                  try {
                     profiler.close();
                  } catch (Throwable var25) {
                     var29.addSuppressed(var25);
                  }
               }

               throw var29;
            }

            if (profiler != null) {
               profiler.close();
            }

            if (!Core.isUseGameViewport()) {
               Core.getInstance().setScreenSize(RenderThread.getDisplayWidth(), RenderThread.getDisplayHeight());
            }

            IsoWorld.instance.FinishAnimation();
            GameProfiler profilerx = GameProfiler.getInstance();
            if (!GameServer.server) {
               ProfileArea console = profilerx.profile("IsoObjectAnimations.update");

               try {
                  IsoObjectAnimations.getInstance().update();
               } catch (Throwable var28) {
                  if (console != null) {
                     try {
                        console.close();
                     } catch (Throwable var24) {
                        var28.addSuppressed(var24);
                     }
                  }

                  throw var28;
               }

               if (console != null) {
                  console.close();
               }
            }

            pzopt.InputLag.beforeRender(); // pzopt: harness input-lag probe, player 0 after this frame's update
            pzopt.VehicleSmooth.beforeRender(); // pzopt: vehicleSmooth, vehicles drawn between the physics steps
            pzopt.DriveJitter.beforeRender(startTime); // pzopt: devDriveJitter, the frame's vehicle / camera state
            renderInternal();
            pzopt.VehicleSmooth.afterRender(); // pzopt: vehicleSmooth, the simulation's values back
            if (doRenderEvent) {
               ProfileArea var35 = profilerx.profile("On Render");

               try {
                  onRender();
               } catch (Throwable var27) {
                  if (var35 != null) {
                     try {
                        var35.close();
                     } catch (Throwable var23) {
                        var27.addSuppressed(var23);
                     }
                  }

                  throw var27;
               }

               if (var35 != null) {
                  var35.close();
               }
            }

            Core.getInstance().DoFrameReady();
            ProfileArea var36 = profilerx.profile("Lighting");

            try {
               LightingThread.instance.update();
            } catch (Throwable var26) {
               if (var36 != null) {
                  try {
                     var36.close();
                  } catch (Throwable var22) {
                     var26.addSuppressed(var22);
                  }
               }

               throw var26;
            }

            if (var36 != null) {
               var36.close();
            }

            if (states.current instanceof GameLoadingState) {
               if (GameLoadingState.loader == null || !GameLoadingState.loader.isAlive()) {
                  LuaEventManager.RunQueuedEvents();
               }
            } else {
               LuaEventManager.RunQueuedEvents();
            }

            if (Core.debug) {
               if (GameKeyboard.isKeyDown("Toggle Lua Debugger")) {
                  if (!luaDebuggerKeyDown) {
                     UIManager.setShowLuaDebuggerOnError(true);
                     LuaManager.thread.step = true;
                     LuaManager.thread.stepInto = true;
                     luaDebuggerKeyDown = true;
                  }
               } else {
                  luaDebuggerKeyDown = false;
               }

               if (GameKeyboard.isKeyPressed("ToggleLuaConsole")) {
                  UIElement console = UIManager.getDebugConsole();
                  if (console != null) {
                     console.setVisible(!console.isVisible());
                  }
               }
            }
         } catch (Throwable var30) {
            if (ex != null) {
               try {
                  ex.close();
               } catch (Throwable var21) {
                  var30.addSuppressed(var21);
               }
            }

            throw var30;
         }

         if (ex != null) {
            ex.close();
         }
      } catch (OpenGLException glEx) {
         RenderThread.logGLException(glEx);
         if (Core.isImGui()) {
            Display.imguiEndFrame();
         }
      } catch (Exception ex) {
         ExceptionLogger.logException(ex);
         if (Core.isImGui()) {
            Display.imguiEndFrame();
         }
      } finally {
         updateTime = System.nanoTime() - startTime;
      }
   }

   public static long getUpdateTime() {
      return updateTime;
   }

   private static void onRender() {
      LuaEventManager.triggerEvent("OnRenderTick");
   }

   private static void exit() {
      DebugType.ExitDebug.debugln("GameWindow.exit 1");
      pzopt.ResumeShot.exitSave = true; // pzopt: resumeShot, the save below is the exit save (floor capture)
      if (GameClient.client) {
         WorldStreamer.instance.stop();
         GameClient.instance.doDisconnect("exit");
         VoiceManager.instance.DeinitVMClient();
      }

      if (okToSaveOnExit) {
         try {
            WorldStreamer.instance.quit();
         } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
         }

         if (PlayerDB.isAllow()) {
            PlayerDB.getInstance().saveLocalPlayersForce();
            PlayerDB.getInstance().canSavePlayers = false;
         }

         try {
            if (GameClient.client && GameClient.connection != null) {
               GameClient.connection.setUserName(null);
            }

            save(true);
         } catch (Throwable t) {
            DebugType.General.printException(t, LogSeverity.Error);
         }

         try {
            if (IsoWorld.instance.currentCell != null) {
               LuaEventManager.triggerEvent("OnPostSave");
            }
         } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
         }

         try {
            if (IsoWorld.instance.currentCell != null) {
               LuaEventManager.triggerEvent("OnPostSave");
            }
         } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
         }

         try {
            LightingThread.instance.stop();
            MapCollisionData.instance.stop();
            AnimalPopulationManager.getInstance().stop();
            ZombiePopulationManager.instance.stop();
            if (PathfindNative.useNativeCode) {
               PathfindNative.instance.stop();
            } else {
               PolygonalMap2.instance.stop();
            }

            ZombieSpawnRecorder.instance.quit();
         } catch (Exception ex) {
            DebugType.General.printException(ex, LogSeverity.Error);
         }
      }

      DebugType.ExitDebug.debugln("GameWindow.exit 2");
      if (GameClient.client) {
         WorldStreamer.instance.stop();
         GameClient.instance.doDisconnect("exit-saving");

         try {
            Thread.sleep(500L);
         } catch (InterruptedException e) {
            DebugType.General.printException(e, LogSeverity.Error);
         }
      }

      DebugType.ExitDebug.debugln("GameWindow.exit 3");
      if (PlayerDB.isAvailable()) {
         PlayerDB.getInstance().close();
      }

      if (ClientPlayerDB.isAvailable()) {
         ClientPlayerDB.getInstance().close();
      }

      DebugType.ExitDebug.debugln("GameWindow.exit 4");
      GameClient.instance.Shutdown();
      SteamUtils.shutdown();
      ZipLogs.addZipFile(true);
      PathfindNative.freeMemoryAtExit();
      onGameThreadExited();
      DebugType.ExitDebug.debugln("GameWindow.exit 5");
      pzopt.AotCache.onGameExit(); // pzopt: an AOT-cache training JVM must exit through System.exit to write the cache
   }

   private static void onGameThreadExited() {
      gameThreadExited = true;
      RenderThread.onGameThreadExited();
   }

   public static void setTexturePackLookup() {
      texturePackTextures.clear();

      for (int i = texturePacks.size() - 1; i >= 0; i--) {
         GameWindow.TexturePack texturePack = texturePacks.get(i);
         if (texturePack.modId == null) {
            texturePackTextures.putAll(texturePack.textures);
         }
      }

      ArrayList<String> modIDs = ZomboidFileSystem.instance.getModIDs();

      for (int i = texturePacks.size() - 1; i >= 0; i--) {
         GameWindow.TexturePack texturePack = texturePacks.get(i);
         if (texturePack.modId != null && modIDs.contains(texturePack.modId)) {
            texturePackTextures.putAll(texturePack.textures);
         }
      }

      Texture.onTexturePacksChanged();
      NinePatchTexture.onTexturePacksChanged();
   }

   public static void LoadTexturePack(String pack, int flags) {
      LoadTexturePack(pack, flags, null);
   }

   public static void LoadTexturePack(String pack, int flags, String modID) {
      DebugType.General.println("texturepack: loading " + pack);
      DoLoadingText(Translator.getText("UI_Loading_Texturepack", new Object[]{pack}));
      String fileName = ZomboidFileSystem.instance.getString("media/texturepacks/" + pack + ".pack");
      GameWindow.TexturePack texturePack = new GameWindow.TexturePack();
      texturePack.packName = pack;
      texturePack.fileName = fileName;
      texturePack.modId = modID;
      fileSystem.mountTexturePack(pack, texturePack.textures, flags);
      texturePacks.add(texturePack);
   }

   private static void installRequiredLibrary(String exe, String name) {
      if (new File(exe).exists()) {
         DebugType.General.debugln("Attempting to install " + name);
         DebugType.General.debugln("Running " + exe + ".");
         ProcessBuilder pb = new ProcessBuilder(exe, "/quiet", "/norestart");

         try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            DebugType.General.debugln("Process exited with code " + exitCode);
            return;
         } catch (IOException | InterruptedException ex2) {
            DebugType.General.printException(ex2, LogSeverity.Error);
         }
      }

      DebugType.General.debugln("Please install " + name);
   }

   private static void checkRequiredLibraries() {
      if (System.getProperty("os.name").startsWith("Win")) {
         String suffix = "";
         if ("1".equals(System.getProperty("zomboid.debuglibs.lighting"))) {
            DebugType.General.debugln("***** Loading debug version of Lighting");
            suffix = "d";
         }

         String dll = "Lighting64" + suffix;

         try {
            System.loadLibrary(dll);
         } catch (UnsatisfiedLinkError ex) {
            DebugType.General.debugln("Error loading " + dll + ".dll.  Your system may be missing a required DLL.");
            installRequiredLibrary("_CommonRedist\\vcredist\\2010\\vcredist_x64.exe", "the Microsoft Visual C++ 2010 Redistributable.");
            installRequiredLibrary("_CommonRedist\\vcredist\\2012\\vcredist_x64.exe", "the Microsoft Visual C++ 2012 Redistributable.");
            installRequiredLibrary("_CommonRedist\\vcredist\\2013\\vcredist_x64.exe", "the Microsoft Visual C++ 2013 Redistributable.");
         }
      }
   }

   private static void init() throws Exception {
      pzopt.BootPump.start(); // pzopt: the file pool works through the queued textures and animations during init
      Core.getInstance().initGlobalShader();
      RenderThread.invokeOnRenderContext(() -> {
         GL20.glUseProgram(SceneShaderStore.defaultShaderId);
         ShaderHelper.forgetCurrentlyBound();
      });
      initFonts();
      checkRequiredLibraries();
      SteamUtils.init();
      ServerBrowser.init();
      SteamFriends.init();
      SteamWorkshop.init();
      ZomboidFileSystem.instance.resetModFolders();
      RakNetPeerInterface.init();
      LightingJNI.init();
      ZombiePopulationManager.init();
      PZSQLUtils.init();
      Clipper.init();
      PathfindNative.init();
      WorldMapJNI.init();
      String path = ZomboidFileSystem.instance.getCacheDir() + File.separator;
      File file = new File(path);
      if (!file.exists()) {
         file.mkdirs();
      }

      DoLoadingText("Loading Mods");
      ZomboidFileSystem.instance.resetDefaultModsForNewRelease("42_00");
      ZomboidFileSystem.instance.loadMods("default");
      ZomboidFileSystem.instance.loadModPackFiles();
      pzopt.LuaPrecompiler.start(); // pzopt: mods are known now; every Lua file compiles on a pool while boot continues
      if (Core.getInstance().isDefaultOptions() && SteamUtils.isSteamModeEnabled() && SteamUtils.isRunningOnSteamDeck()) {
         Core.getInstance().setOptionFontSize(2);
         Core.getInstance().setOptionSingleContextMenu(0, true);
         Core.getInstance().setOptionShoulderButtonContainerSwitch(1);
         Core.getInstance().setAutoZoom(0, true);
         Core.getInstance().setOptionZoomLevels2x("75;125;150;175;200");
         Core.getInstance().setOptionPanCameraWhileAiming(true);
         Core.getInstance().setOptionPanCameraWhileDriving(true);
         Core.getInstance().setOptionTextureCompression(true);
         Core.getInstance().setOptionVoiceEnable(false, false);
      }

      DoLoadingText("Loading Translations");
      Languages.instance.init();
      Translator.language = null;
      initFonts();
      Translator.loadFiles();
      if (!pzopt.Overrides.enabled()) { // pzopt: no photosensitivity screen at boot; stock behaviour only when the build guard is off
         doEpilepsyWarningText();
      }
      LuaManager.init();
      initShared();
      DoLoadingText(Translator.getText("UI_Loading_Lua", new Object[0]));
      LuaManager.LoadDirBase();
      ZomboidGlobals.Load();
      LuaEventManager.triggerEvent("OnLoadSoundBanks");
   }

   public static void initFonts() throws FileNotFoundException {
      pzopt.BootPump.pause(); // pzopt: font completions must run on this thread (see BootPump.pause)
      TextManager.instance.Init();

      while (TextManager.instance.isAnyFontLoading()) {
         fileSystem.updateAsyncTransactions();

         try {
            Thread.sleep(10L);
         } catch (InterruptedException var1) {
         }
      }

      pzopt.BootPump.resume();
   }

   public static void save(boolean bDoChars) throws IOException {
      if (!Core.getInstance().isNoSave()) {
         if (IsoWorld.instance.currentCell != null
            && !"LastStand".equals(Core.getInstance().getGameMode())
            && !"Tutorial".equals(Core.getInstance().getGameMode())) {
            if (GameClient.clientSave) {
               GameClient.clientSave = GameClient.client;
               MapItem.SaveWorldMap();
               WorldMapVisited.SaveAll();
               LuaEventManager.triggerEvent("OnSave");
            } else {
               File outFile = ZomboidFileSystem.instance.getFileInCurrentSave("map_ver.bin");

               try (
                  FileOutputStream fos = new FileOutputStream(outFile);
                  DataOutputStream output = new DataOutputStream(fos);
               ) {
                  output.writeInt(249);
                  WriteString(output, Core.gameMap);
               }

               outFile = ZomboidFileSystem.instance.getFileInCurrentSave("map_sand.bin");

               try (
                  FileOutputStream var23 = new FileOutputStream(outFile);
                  BufferedOutputStream output = new BufferedOutputStream(var23);
               ) {
                  SliceY.SliceBuffer.clear();
                  SandboxOptions.instance.save(SliceY.SliceBuffer);
                  output.write(SliceY.SliceBuffer.array(), 0, SliceY.SliceBuffer.position());
               }

               WorldGenParams.INSTANCE.save();
               InstanceTracker.save();
               MetaTracker.save();
               StatisticsManager.getInstance().save();
               LuaEventManager.triggerEvent("OnSave");

               try {
                  try {
                     try {
                        if (Thread.currentThread() == gameThread) {
                           SavefileThumbnail.create();
                        }
                     } catch (Exception ex) {
                        ExceptionLogger.logException(ex);
                     }

                     outFile = ZomboidFileSystem.instance.getFileInCurrentSave("map.bin");

                     try (FileOutputStream outStream = new FileOutputStream(outFile)) {
                        DataOutputStream output = new DataOutputStream(outStream);
                        IsoWorld.instance.currentCell.save(output, bDoChars);
                     } catch (Exception ex) {
                        ExceptionLogger.logException(ex);
                     }

                     AnimalPopulationManager.getInstance().save();

                     try {
                        MapCollisionData.instance.save();
                        if (!loadedAsClient) {
                           SGlobalObjects.save();
                        }
                     } catch (Exception ex) {
                        ExceptionLogger.logException(ex);
                     }

                     ZomboidRadio.getInstance().Save();
                     GlobalModData.instance.save();
                     MapItem.SaveWorldMap();
                     WorldMapVisited.SaveAll();
                     FishSchoolManager.getInstance().save();
                     GameEntityManager.Save();
                     if (!GameClient.client && !GameServer.server) {
                        VirtualVehicleManager.getInstance().save();
                     }
                  } catch (IOException ex) {
                     throw new RuntimeException(ex);
                  }
               } catch (RuntimeException ex) {
                  if (ex.getCause() instanceof IOException ioException) {
                     throw ioException;
                  } else {
                     throw ex;
                  }
               }
            }
         }
      }
   }

   public static String getCoopServerHome() {
      File file = new File(ZomboidFileSystem.instance.getCacheDir());
      return file.getParent();
   }

   public static void WriteString(ByteBuffer output, String str) {
      stringUTF.get().save(output, str);
   }

   public static void WriteString(DataOutputStream output, String str) throws IOException {
      if (str == null) {
         output.writeInt(0);
      } else {
         output.writeInt(str.length());
         if (str != null && str.length() >= 0) {
            output.writeChars(str);
         }
      }
   }

   public static String ReadString(ByteBuffer input) {
      return stringUTF.get().load(input);
   }

   public static String ReadString(DataInputStream input) throws IOException {
      int len = input.readInt();
      if (len == 0) {
         return "";
      }

      if (len > 65536) {
         throw new RuntimeException("GameWindow.ReadString: string is too long, corrupted save?");
      }

      StringBuilder sb = new StringBuilder(len);

      for (int n = 0; n < len; n++) {
         sb.append(input.readChar());
      }

      return sb.toString();
   }

   public static ByteBuffer getEncodedBytesUTF(String str) {
      return stringUTF.get().getEncodedBytes(str);
   }

   public static void WriteUUID(ByteBuffer output, UUID uuid) {
      output.putLong(uuid.getMostSignificantBits());
      output.putLong(uuid.getLeastSignificantBits());
   }

   public static UUID ReadUUID(ByteBuffer input) {
      return new UUID(input.getLong(), input.getLong());
   }

   public static void doRenderEvent(boolean b) {
      doRenderEvent = b;
   }

   public static void DoLoadingText(String text) {
      DebugLog.log(text);
   }

   public static void doEpilepsyWarningText() {
      if (TextManager.instance.font != null) {
         AngelCodeFont font = TextManager.instance.getFontFromEnum(UIFont.NewLarge);
         int lineHeight = font.getLineHeight();
         int y = (int)(Core.getInstance().getScreenHeight() / 2.0F - 3.5F * lineHeight);
         Core.getInstance().StartFrame();
         Core.getInstance().EndFrame();
         Core.getInstance().StartFrameUI();
         SpriteRenderer.instance.renderi(null, 0, 0, Core.getInstance().getScreenWidth(), Core.getInstance().getScreenHeight(), 0.0F, 0.0F, 0.0F, 1.0F, null);

         for (int i = 1; i <= 8; i++) {
            TextManager.instance
               .DrawStringCentre(
                  font, Core.getInstance().getScreenWidth() / 2.0F, y, Translator.getText("UI_EpilepsyWarning_" + i, new Object[0]), 1.0, 1.0, 1.0, 1.0
               );
            y += lineHeight;
         }

         Core.getInstance().EndFrameUI();
      }
   }

   public static class OSValidator {
      private static final String OS = System.getProperty("os.name").toLowerCase();

      public static boolean isWindows() {
         return OS.indexOf("win") >= 0;
      }

      public static boolean isMac() {
         return OS.indexOf("mac") >= 0;
      }

      public static boolean isUnix() {
         return OS.indexOf("nix") >= 0 || OS.indexOf("nux") >= 0 || OS.indexOf("aix") > 0;
      }

      public static boolean isSolaris() {
         return OS.indexOf("sunos") >= 0;
      }
   }

   private static class StringUTF {
      private char[] chars;
      private ByteBuffer byteBuffer;
      private CharBuffer charBuffer;
      private CharsetEncoder ce;
      private CharsetDecoder cd;

      private int encode(String str) {
         if (this.chars == null || this.chars.length < str.length()) {
            int capacity = (str.length() + 128 - 1) / 128 * 128;
            this.chars = new char[capacity];
            this.charBuffer = CharBuffer.wrap(this.chars);
         }

         str.getChars(0, str.length(), this.chars, 0);
         this.charBuffer.limit(str.length());
         this.charBuffer.position(0);
         if (this.ce == null) {
            this.ce = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
         }

         this.ce.reset();
         int maxBytes = (int)((double)str.length() * this.ce.maxBytesPerChar());
         maxBytes = (maxBytes + 128 - 1) / 128 * 128;
         if (this.byteBuffer == null || this.byteBuffer.capacity() < maxBytes) {
            this.byteBuffer = ByteBuffer.allocate(maxBytes);
         }

         this.byteBuffer.clear();
         this.ce.encode(this.charBuffer, this.byteBuffer, true);
         return this.byteBuffer.position();
      }

      private String decode(int numBytes) {
         if (this.cd == null) {
            this.cd = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
         }

         this.cd.reset();
         int maxChars = (int)((double)numBytes * this.cd.maxCharsPerByte());
         if (this.chars == null || this.chars.length < maxChars) {
            int capacity = (maxChars + 128 - 1) / 128 * 128;
            this.chars = new char[capacity];
            this.charBuffer = CharBuffer.wrap(this.chars);
         }

         this.charBuffer.clear();
         this.cd.decode(this.byteBuffer, this.charBuffer, true);
         return new String(this.chars, 0, this.charBuffer.position());
      }

      ByteBuffer getEncodedBytes(String str) {
         this.encode(str);
         return this.byteBuffer;
      }

      void save(ByteBuffer out, String str) {
         if (str != null && !str.isEmpty()) {
            int numBytes = this.encode(str);
            out.putShort((short)numBytes);
            this.byteBuffer.flip();
            out.put(this.byteBuffer);
         } else {
            out.putShort((short)0);
         }
      }

      String load(ByteBuffer in) {
         int numBytes = in.getShort();
         if (numBytes <= 0) {
            return "";
         }

         int maxBytes = (numBytes + 128 - 1) / 128 * 128;
         if (this.byteBuffer == null || this.byteBuffer.capacity() < maxBytes) {
            this.byteBuffer = ByteBuffer.allocate(maxBytes);
         }

         this.byteBuffer.clear();
         if (in.remaining() < numBytes) {
            DebugType.General.error("GameWindow.StringUTF.load> numBytes:" + numBytes + " is higher than the remaining bytes in the buffer:" + in.remaining());
         }

         int limit = in.limit();
         in.limit(in.position() + numBytes);
         this.byteBuffer.put(in);
         in.limit(limit);
         this.byteBuffer.flip();
         return this.decode(numBytes);
      }
   }

   private static final class TexturePack {
      String packName;
      String fileName;
      String modId;
      final TexturePackTextures textures = new TexturePackTextures();
   }

   private static class s_performance {
      static final PerformanceProfileFrameProbe frameStep = new PerformanceProfileFrameProbe("GameWindow.frameStep");
      static final PerformanceProfileProbe statesRender = new PerformanceProfileProbe("GameWindow.states.render");
      static final PerformanceProfileProbe logic = new PerformanceProfileProbe("GameWindow.logic");
   }
}
