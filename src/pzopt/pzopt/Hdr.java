package pzopt;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeWayland;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL41;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;

/**
 * HDR output (Config.HDR). The game keeps drawing exactly as it does in SDR, gamma-encoded, into a window whose
 * default framebuffer is RGBA16F (an FP16 EGL config on Wayland), so nothing clamps at 1.0:
 *
 * 1. the stock world composite shader ("screen") gets {@code pzHdrWorld()} appended (ShaderUnit hook,
 *    {@link #patchShader}): the finished SDR world pixel is expanded to scene-referred HDR (highlights above 1.0,
 *    world paper white relative to the UI white), still gamma-encoded, so the UI then blends over it exactly as in SDR;
 * 2. at the swap ({@link #beforeSwap}) one pass decodes the whole frame (gamma 2.2, what KWin uses for SDR windows),
 *    scales the UI white to the desktop's reference white, rolls the top off into the panel's peak and writes
 *    extended-linear values; {@link HdrWayland} tagged the surface ext_linear / sRGB primaries with the output's own
 *    luminances, so 1.0 = the panel peak and the compositor passes the pixels through.
 *
 * Tuning lives in fields read every frame (Config defaults, then the dev file {@code Config.HDR_TUNE} re-read once a
 * second), so one run can sweep parameter sets; {@code hdrDump} requests write the frame (FP16, before and after the
 * encode) for tools/hdr/hdrframe.py.
 */
public final class Hdr {
   private Hdr() {
   }

   // Linux and macOS (HdrMac ran on a MacBook Pro XDR panel, 2026-09-24); Windows (HdrWin) is native interop written without
   // the hardware, hdrUntestedPlatforms=true lets a test run use it
   private static final boolean PLATFORM = Config.HDR_UNTESTED_PLATFORMS
         || System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux") || HdrMac.MAC;
   /** HDR is on because hdrAuto found an HDR screen, not because hdr=true asked for it. */
   public static final boolean AUTO = Overrides.enabled() && PLATFORM && !Config.HDR && Config.HDR_AUTO && autoDetect();
   public static final boolean REQUESTED = Overrides.enabled() && PLATFORM && (Config.HDR || AUTO);

   /**
    * hdrAuto (default on): turn HDR on when the screen is HDR. Linux: a Wayland session with an output in HDR mode
    * (HdrWayland.probeHdrOutput, before GLFW, so SDR desktops keep XWayland). macOS: decided in windowCreated (AppKit is
    * asked on the main thread once GLFW is up); asking for the alpha bits costs nothing on an SDR display.
    */
   private static boolean autoDetect() {
      if (HdrMac.MAC) {
         return true;
      }
      if (System.getenv("WAYLAND_DISPLAY") == null) {
         autoNote = "hdr: auto: off (not a Wayland session)";
         return false;
      }
      boolean hdr = HdrWayland.probeHdrOutput();
      autoNote = "hdr: auto: " + (hdr ? "HDR screen found, HDR on" : "no HDR screen, HDR off") + " (" + HdrWayland.probeResult + ")";
      return hdr;
   }

   /** The auto decision, logged from windowCreated: Hdr is initialised in Display.init, before the game log exists. */
   private static String autoNote;

   /** Output is really HDR (FP16 back buffer + an attached image description). Render thread writes it once. */
   public static volatile boolean active;
   /**
    * Gain carried in the back buffer's alpha instead of RGB above 1.0 (8-bit back buffers: the macOS EDR bridge, where
    * GLFW cannot make a float framebuffer). The composite keeps its SDR output; an alpha-only pass after it writes the
    * world gain log-encoded (alpha 1 = gain 1, so opaque UI drawn later reads as SDR); the presenter rebuilds HDR as
    * decode(rgb) x gain(alpha).
    */
   public static volatile boolean alphaGain;
   static final float GAIN_MAX = 16F;
   private static boolean floatBackBuffer;
   private static String state = "off";

   // ---- tunables (live) ----
   static final class Tune {
      float uiNits; // UI / SDR white on the panel, 0 = the desktop's reference white
      float paperPct; // world paper white, % of the UI white
      float peakNits; // 0 = the panel's peak
      float itm; // highlight expansion strength 0..1
      float thresholdDay, thresholdNight; // SDR linear brightness where expansion starts
      float curve; // expansion exponent
      float chroma; // mix luminance -> max channel for the expansion key (0 = luminance, 1 = max channel)
      float saturation; // extra chroma of the world (0 = none)
      float knee; // encode roll-off start, fraction of the peak
      float blackLift; // world black offset in nits (negative = crush), 0 = none
      float gamma; // decode exponent of the SDR signal
      float nightLo, nightHi; // average world luminance (linear) mapped to night 1 .. 0
      float bloom; // bloom strength (0 = off): the blurred energy the expansion added, added back
      float light, lightCurve, lightFlipY, debugView; // light-map gain strength / exponent, map orientation, 1-3 = debug views
      float lightMax = 4F; // light gain at full excess (x)
      float itmDay; // ITM strength in daylight (itm is the night strength)
      float flashMax = 3F; // gain of a full lightning strike (world x), 1 = the SDR flash
      float lightHot = 0.7F; // weight of the analytic (source-distance) intensity in the light map: the hot core
      float lightTint = 0.6F; // how much the light's own colour tints its gain (fire warm, lamps white)
      float lightReach = 0.6F; // levels: vertical surfaces take the light of the floor in front of them (down-screen samples)
      float glint = 1F; // speculars and sky reflections of water / puddles (HdrGlint), overall strength (0 = off)
      float sunGlint = 8F; // sun glint peak (x SDR white) on a clear day (4 peaked near the UI white, hdr28-riverday)
      float skyReflect = 0.6F; // sky reflection strength (Fresnel), x the sky colour
      float glintShine = 150F; // specular exponent of the sun glint
      float lampGlint = 1.5F; // lamps / torches / headlights glittering on water at night
      float carGlass = 3F, carPaint = 0.8F; // car speculars (the game's own five model lights): windows, paint (x SDR white)
      float vehLamp = 4F; // active headlights / taillights as emitters (x their SDR colour)
      float sunMax = 1.6F; // sunlit outdoors on a clear day (x the SDR picture): what the sun adds over the shade
      float sunLean = 0.6F; // 0 = the physical sun, 1 = the mirror of the view (glitter everywhere)

      Tune copy() {
         Tune t = new Tune();
         t.uiNits = uiNits;
         t.paperPct = paperPct;
         t.peakNits = peakNits;
         t.itm = itm;
         t.thresholdDay = thresholdDay;
         t.thresholdNight = thresholdNight;
         t.curve = curve;
         t.chroma = chroma;
         t.saturation = saturation;
         t.knee = knee;
         t.blackLift = blackLift;
         t.gamma = gamma;
         t.nightLo = nightLo;
         t.nightHi = nightHi;
         t.bloom = bloom;
         t.light = light;
         t.lightCurve = lightCurve;
         t.lightFlipY = lightFlipY;
         t.debugView = debugView;
         t.lightMax = lightMax;
         t.itmDay = itmDay;
         t.flashMax = flashMax;
         t.lightReach = lightReach;
         t.lightHot = lightHot;
         t.lightTint = lightTint;
         t.glint = glint;
         t.sunGlint = sunGlint;
         t.skyReflect = skyReflect;
         t.glintShine = glintShine;
         t.lampGlint = lampGlint;
         t.sunLean = sunLean;
         t.sunMax = sunMax;
         t.carGlass = carGlass;
         t.carPaint = carPaint;
         t.vehLamp = vehLamp;
         return t;
      }

      void set(String key, float v) {
         switch (key) {
            case "uiNits" -> uiNits = v;
            case "paperPct" -> paperPct = v;
            case "peakNits" -> peakNits = v;
            case "itm" -> itm = v;
            case "thresholdDay" -> thresholdDay = v;
            case "thresholdNight" -> thresholdNight = v;
            case "curve" -> curve = v;
            case "chroma" -> chroma = v;
            case "saturation" -> saturation = v;
            case "knee" -> knee = v;
            case "blackLift" -> blackLift = v;
            case "gamma" -> gamma = v;
            case "nightLo" -> nightLo = v;
            case "nightHi" -> nightHi = v;
            case "bloom" -> bloom = v;
            case "light" -> light = v;
            case "lightCurve" -> lightCurve = v;
            case "lightFlipY" -> lightFlipY = v;
            case "debugView" -> debugView = v;
            case "lightMax" -> lightMax = v;
            case "itmDay" -> itmDay = v;
            case "flashMax" -> flashMax = v;
            case "lightReach" -> lightReach = v;
            case "lightHot" -> lightHot = v;
            case "lightTint" -> lightTint = v;
            case "glint" -> glint = v;
            case "sunGlint" -> sunGlint = v;
            case "skyReflect" -> skyReflect = v;
            case "glintShine" -> glintShine = v;
            case "lampGlint" -> lampGlint = v;
            case "sunLean" -> sunLean = v;
            case "sunMax" -> sunMax = v;
            case "carGlass" -> carGlass = v;
            case "carPaint" -> carPaint = v;
            case "vehLamp" -> vehLamp = v;
            default -> Log.warn("hdr tune: unknown key " + key);
         }
      }

      @Override
      public String toString() {
         return String.format("uiNits=%.0f paperPct=%.0f peakNits=%.0f itm=%.2f thresholdDay=%.2f thresholdNight=%.2f curve=%.2f chroma=%.2f"
               + " saturation=%.2f knee=%.2f blackLift=%.2f gamma=%.2f nightLo=%.3f nightHi=%.3f bloom=%.2f light=%.2f lightCurve=%.2f lightFlipY=%.0f debugView=%.0f lightMax=%.2f itmDay=%.2f flashMax=%.2f lightReach=%.2f lightHot=%.2f lightTint=%.2f glint=%.2f sunGlint=%.2f skyReflect=%.2f glintShine=%.0f lampGlint=%.2f sunLean=%.2f sunMax=%.2f carGlass=%.2f carPaint=%.2f vehLamp=%.2f", uiNits, paperPct, peakNits, itm,
               thresholdDay, thresholdNight, curve, chroma, saturation, knee, blackLift, gamma, nightLo, nightHi, bloom, light, lightCurve, lightFlipY, debugView, lightMax, itmDay, flashMax, lightReach, lightHot, lightTint, glint, sunGlint, skyReflect, glintShine, lampGlint, sunLean, sunMax, carGlass, carPaint, vehLamp);
      }
   }

   static Tune tune = defaults();

   private static Tune defaults() {
      Tune t = new Tune();
      t.uiNits = Config.HDR_UI_NITS;
      t.paperPct = Config.HDR_PAPER_PCT;
      t.peakNits = Config.HDR_PEAK_NITS;
      t.itm = Config.HDR_ITM_PCT / 100F;
      t.thresholdDay = 0.55F;
      t.thresholdNight = 0.12F;
      t.curve = 2.0F;
      t.chroma = 0.6F;
      t.saturation = Config.HDR_SATURATION_PCT / 100F;
      t.knee = 0.75F;
      t.blackLift = 0F;
      t.gamma = 2.2F;
      t.nightLo = 0.01F;
      t.nightHi = 0.04F; // PZ daylight averages ~0.09 linear, night ~0.008 (hdr17-day / -night)
      t.bloom = Config.HDR_BLOOM_PCT / 100F;
      t.glint = Config.HDR_GLINT_PCT / 100F;
      t.sunMax = 1F + Config.HDR_SUN_PCT / 100F;
      t.light = Config.HDR_LIGHT_PCT / 100F;
      t.lightCurve = 1.5F;
      t.lightFlipY = 1F;
      return t;
   }

   // ---- window (Display override) ----

   /** Display.init(): HDR needs the Wayland platform (color management); true = select it. */
   public static boolean wantsWayland() {
      return REQUESTED && System.getenv("WAYLAND_DISPLAY") != null;
   }

   private static volatile boolean glfwTerminated;
   private static boolean exitHookAdded;

   /**
    * Render thread, the end of RenderThread.renderLoop (after Display.destroy, before System.exit), or the exit hook below:
    * on the Wayland platform terminate GLFW (eglTerminate + wl_display_disconnect) on the thread that initialised it.
    * Without it the NVIDIA EGL driver's exit-time teardown ran on the JVM's VM thread against the live display and
    * segfaulted (libnvidia-eglcore+0xa54691, every native-Wayland run, HDR on or off: hdr27-waylandOff).
    */
   public static void beforeExit() {
      if (glfwTerminated) {
         return;
      }
      glfwTerminated = true;
      try {
         if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
            GLFW.glfwTerminate();
            Log.info("hdr: Wayland: GLFW terminated before exit");
         }
      } catch (Throwable t) {
         Log.warn("hdr: GLFW terminate before exit failed: " + t);
      }
   }

   /**
    * Display.create(), Wayland platform: most exits are not the render loop's own System.exit but Core.quit() from the
    * main menu (the player's "Quit to desktop", the harness) calling System.exit on the game thread with the window
    * still up (hdr30: the render-loop hook never ran). A shutdown hook hands the window's teardown and beforeExit() to
    * the render thread and waits for it at most 2 s (a stuck render thread must not hang the quit).
    */
   private static void addWaylandExitHook() {
      if (exitHookAdded || GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
         return;
      }
      exitHookAdded = true;
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         if (glfwTerminated || zombie.core.opengl.RenderThread.renderThread == null) {
            return;
         }
         java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
         zombie.core.opengl.RenderThread.queueInvokeOnRenderContext(() -> {
            try {
               if (!glfwTerminated) {
                  org.lwjglx.opengl.Display.destroy();
                  beforeExit();
               }
            } finally {
               done.countDown();
            }
            // no parking the render thread here until the halt: another shutdown hook waits on the render context and the
            // exit hung (hdr31); GL calls without a context are no-ops (glvnd), GLFW calls after terminate only report an error
         });
         try {
            for (int i = 0; i < 40 && done.getCount() > 0; i++) {
               if (SpriteRenderer.instance != null) {
                  SpriteRenderer.instance.notifyRenderStateQueue();
               }
               done.await(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
         } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
         }
      }, "pzopt-wayland-exit"));
   }

   /** Display.create(), after glfwDefaultWindowHints: ask for an FP16 default framebuffer. */
   public static void windowHints() {
      if (REQUESTED && GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
         GLFW.glfwWindowHint(GLFW.GLFW_RED_BITS, 16);
         GLFW.glfwWindowHint(GLFW.GLFW_GREEN_BITS, 16);
         GLFW.glfwWindowHint(GLFW.GLFW_BLUE_BITS, 16);
         GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 16);
      } else if (REQUESTED && (HdrMac.MAC || HdrWin.WINDOWS)) {
         // the alpha-gain platforms carry the world gain in the back buffer's alpha; Core asks for PixelFormat(32, 0, ...),
         // i.e. no alpha, and an alpha-less back buffer reads 1.0 (gain 1: SDR in an HDR swap chain)
         GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 8);
      }
   }

   /** Display.create(), context current: check the back buffer really is float and tag the surface. */
   public static void windowCreated(long window) {
      addWaylandExitHook();
      if (autoNote != null) {
         Log.info(autoNote);
      }
      if (!REQUESTED) {
         return;
      }
      try {
         if (HdrWin.WINDOWS) {
            alphaGain = true;
            active = true; // HdrWin sets it back to false if the scRGB swap chain cannot be made
            state = "Windows scRGB (DXGI flip-model swap chain, WGL_NV_DX_interop2)";
            Log.info("hdr: Windows: scRGB output through a DXGI swap chain; world gain carried in the back buffer's alpha");
            return;
         }
         if (HdrMac.MAC) {
            if (AUTO) {
               double potential = HdrMac.screenPotentialHeadroom();
               if (potential < 4.0) { // XDR panels: 16; ordinary panels only have a little backlight headroom
                  state = String.format("auto: the display has no HDR headroom (EDR %.2f), HDR off", potential);
                  Log.info("hdr: " + state);
                  return;
               }
               Log.info(String.format("hdr: auto: EDR display (potential headroom %.1f), HDR on", potential));
            }
            int alphaBits = GL11.glGetInteger(GL11.GL_ALPHA_BITS);
            if (alphaBits < 8) {
               state = "no alpha channel in the back buffer (" + alphaBits + " bits): the world gain has nowhere to go";
               Log.warn("hdr: " + state);
               return;
            }
            alphaGain = true;
            active = true; // HdrMac sets it back to false if the EDR layer cannot be made
            state = "macOS EDR (Metal layer, extended linear sRGB)";
            Log.info("hdr: macOS: EDR output through a Metal layer; world gain carried in the back buffer's alpha (" + alphaBits + " bits)");
            return;
         }
         if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_WAYLAND) {
            state = "HDR needs the Wayland platform (X11 / XWayland windows are SDR)";
            Log.warn("hdr: " + state);
            return;
         }
         int prev = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
         int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL11.GL_BACK_LEFT, GL30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE);
         int red = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, GL11.GL_BACK_LEFT, GL30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
         floatBackBuffer = type == GL11.GL_FLOAT && red >= 16;
         if (!floatBackBuffer) {
            state = "no FP16 back buffer (component type 0x" + Integer.toHexString(type) + ", red " + red + " bits)";
            Log.warn("hdr: " + state);
            return;
         }
         long dpy = GLFWNativeWayland.glfwGetWaylandDisplay();
         long surface = GLFWNativeWayland.glfwGetWaylandWindow(window);
         // hdrEncode auto: KWin shows an undescribed FP16 surface extended (gamma 2.2, 1.0 = reference white, above 1.0
         // up to the headroom: probe + hdrcmp-noenc), so the encode pass is skipped there (255 -> 285 fps uncapped at
         // 4K); elsewhere the ext_linear description, which the protocol defines
         String desktop = String.valueOf(System.getenv("XDG_CURRENT_DESKTOP")).toUpperCase(java.util.Locale.ROOT);
         boolean describe = "on".equals(Config.HDR_ENCODE) || !"off".equals(Config.HDR_ENCODE) && !desktop.contains("KDE");
         if (!HdrWayland.attach(dpy, surface, describe)) {
            state = HdrWayland.status;
            Log.warn("hdr: SDR output: " + state);
            return;
         }
         active = true;
         state = HdrWayland.status;
         Log.info("hdr: " + tune);
      } catch (Throwable t) {
         state = "failed: " + t;
         Log.warn("hdr: " + state);
      }
   }

   public static String state() {
      return state;
   }

   // ---- world composite shader (ShaderUnit hook) ----

   /** Game shaders pass through here before compilation; the world composite gets the HDR expansion appended. */
   public static String patchShader(String fileName, String code) {
      if (!REQUESTED || code == null || fileName == null || HdrMac.MAC) {
         return code; // macOS: alpha-gain path (GLSL 1.20 context), the composite stays stock
      }
      String f = fileName.replace('\\', '/');
      if ((f.endsWith("/water.frag") || f.endsWith("/water_hq.frag")) && Config.HDR_GLINT_PCT > 0) {
         return patchChecked(fileName, code, patchWater(code));
      }
      if ((f.endsWith("/vehicle.frag") || f.endsWith("/vehicle_multiuv.frag") || f.endsWith("/vehicle_norandom_multiuv.frag")) && Config.HDR_GLINT_PCT > 0) {
         return patchChecked(fileName, code, patchVehicle(code));
      }
      if (f.endsWith("puddles_common.frag.glsl") && Config.HDR_GLINT_PCT > 0) {
         return patchChecked(fileName, code, patchPuddles(code));
      }
      if (!f.endsWith("/screen.frag") || !code.contains("void main()")) {
         return code;
      }
      String patched = code.replace("void main()", "void pzStockMain()") + WORLD_GLSL;
      // ShaderUnit.compile runs with the context current: test-compile, never hand the game a unit that fails
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, patched);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("hdr: world expansion does not compile, " + fileName + " stays stock: " + log);
         return code;
      }
      Log.info("hdr: world expansion appended to " + fileName);
      return patched;
   }

   /**
    * The expansion itself, shared by the world composite (inside the game's screen.frag: its util/math.h declares
    * float max(float, float) and float / vec2 / vec3 clamp, which hide the builtin overloads, so only those forms and
    * min / pow are used here) and the bloom bright pass.
    */
   static final String GAIN_GLSL = String.join("\n",
         "uniform vec4 pzHdrA; // x on, y world paper white / UI white (linear), z headroom = peak / world paper, w strength",
         "uniform vec4 pzHdrB; // x threshold day, y threshold night, z curve exponent, w chroma key mix",
         "uniform vec4 pzHdrC; // x saturation, y black offset (fraction of paper), z night lo, w night hi",
         "uniform vec4 pzHdrD; // x gamma, y stats valid, z bloom strength, w bloom valid",
         "uniform sampler2D pzHdrStats;",
         "uniform vec3 pzHdrL0; // window px (gl_FragCoord) -> light map UV (row u)",
         "uniform vec3 pzHdrL1; // (row v)",
         "uniform vec4 pzHdrF; // x light strength, y light curve, z light map valid, w debug view",
         "uniform vec4 pzHdrG; // x light gain at full excess, y ITM strength in daylight, z lightning gain this frame, w reach (window px)",
         "uniform sampler2D pzHdrLight;",
         "vec4 pzHdrLightAt(vec2 windowPx) {",
         "  vec3 p = vec3(windowPx, 1.0);",
         "  return texture2D(pzHdrLight, vec2(dot(pzHdrL0, p), dot(pzHdrL1, p)));",
         "}",
         "// the map is the floor's light; a wall face, a character or a crate above a floor pixel takes the light of the",
         "// floor in front of it, which is below it on screen: max over samples down to the reach, with a falloff",
         "uniform vec4 pzHdrH; // x light tint",
         "vec4 pzHdrPick(vec4 best, vec4 s, float w) {",
         "  return s.a * w > best.a ? vec4(s.rgb, s.a * w) : best;",
         "}",
         "vec4 pzHdrLightSample(vec2 windowPx) {",
         "  vec4 m = pzHdrLightAt(windowPx);",
         "  float h = pzHdrG.w;",
         "  if (h > 0.5) {",
         "    m = pzHdrPick(m, pzHdrLightAt(windowPx - vec2(0.0, h * 0.33)), 0.9);",
         "    m = pzHdrPick(m, pzHdrLightAt(windowPx - vec2(0.0, h * 0.67)), 0.8);",
         "    m = pzHdrPick(m, pzHdrLightAt(windowPx - vec2(0.0, h)), 0.7);",
         "  }",
         "  return m;",
         "}",
         "float pzHdrLightExcess(vec2 windowPx) {",
         "  return pzHdrLightSample(windowPx).a;",
         "}",
         "// the light-map gain plus lightning: a strike adds light from outside, so it adds to the gain (instead of",
         "// multiplying a lamp-lit room into white, hdrcmp-storm) and mostly where no lamp already lights the surface",
         "vec3 pzHdrLightGain(vec2 windowPx) {",
         "  float flash = pzHdrG.z - 1.0;",
         "  if (pzHdrF.z < 0.5) return vec3(1.0 + flash);",
         "  vec4 m = pzHdrLightSample(windowPx);",
         "  float g = (pzHdrG.x - 1.0) * pzHdrF.x * pow(m.a, pzHdrF.y);",
         "  return vec3(1.0) + g * mix(vec3(1.0), m.rgb, pzHdrH.x) + vec3(flash * (1.0 - m.a));",
         "}",
         "float pzHdrNight() {",
         "  float avg = pzHdrD.y > 0.5 ? texture2D(pzHdrStats, vec2(0.5), 16.0).a : 0.2; // bias to the 1x1 mip (GLSL 1.20 has no textureLod in fragments)",
         "  // a dark frame by day (the unseen area, an interior) is not night: the climate's daylight caps the key (y), else",
         "  // the night ITM lifted pale albedo in daylight (hdr23-riverday: 1.45x SDR with the sun off)",
         "  return min(1.0 - smoothstep(pzHdrC.z, pzHdrC.w, avg), pzHdrH.y);",
         "}",
         "float pzHdrGain(vec3 lin, float night) {",
         "  float t = mix(pzHdrB.x, pzHdrB.y, night);",
         "  float y = dot(lin, vec3(0.2126, 0.7152, 0.0722));",
         "  float m = max(max(lin.r, lin.g), lin.b);",
         "  float k = clamp((mix(y, m, pzHdrB.w) - t) / max(1.0 - t, 1e-3), 0.0, 1.0);",
         "  return 1.0 + (pzHdrA.z - 1.0) * mix(pzHdrG.y, pzHdrA.w, night) * pow(k, pzHdrB.z);",
         "}",
         "");

   /** Water: the HDR glint (sun, sky, lamps) as a second output next to the stock colour. */
   static String patchWater(String code) {
      String anchor = "fragColor.a = min(levelf, 1.0);";
      if (!code.contains(anchor) || !code.contains("void mainImage(") || !code.contains("void main()")) {
         return null;
      }
      String c = code.replace("gl_FragColor", "gl_FragData[0]");
      c = c.replace("void mainImage(", SURFACE_GLSL + "\nvoid mainImage(");
      c = c.replace(anchor, anchor + "\n    pzGlint = pzHdrWaterGlint(gm, pzWindowPx()) * fragColor.a;");
      c = c.replace("void main()", "void pzWaterMain()");
      // the square's light (vertColour) scales the glint as it scales the water: unseen water (the visibility darkening) has none
      return c + "\nvoid main() {\n  pzWaterMain();\n  pzGlint *= vertColour.rgb;\n  gl_FragData[1] = vec4(pzGlint / (1.0 + pzGlint), pzSurfNow());\n}\n";
   }

   /**
    * Puddles (the HQ path has a ripple normal): the glint, weighted by the reflective part of the puddle, as a second
    * output, written only in the glint-only pass after the moving objects (HdrGlint.queueGlintOnly) together with the
    * finished pixel's luminance for the composite's colour match.
    */
   static String patchPuddles(String code) {
      String hq = "void mainImage(";
      String anchor = "fragColor.a = mix(fragColor.a, 0.5+muddyPuddles*0.3, alphaPuddlesReflection);";
      int at = code.indexOf(hq);
      int an = at < 0 ? -1 : code.indexOf(anchor, at);
      if (at < 0 || an < 0 || !code.contains("gl_FragColor = fragCol;")) {
         return null;
      }
      String c = code.substring(0, an + anchor.length())
            + "\n    pzGlint = pzHdrWaterGlint(normalize(gm), pzWindowPx()) * alphaPuddlesReflection;"
            + code.substring(an + anchor.length());
      c = c.replace("gl_FragColor = fragCol;", "gl_FragData[0] = fragCol;\n    pzGlint *= vertColour.rgb;\n    gl_FragData[1] = vec4(pzGlint / (1.0 + pzGlint), pzSurfNow());");
      int first = c.indexOf("vec2 SphereMap(");
      if (first < 0) {
         return null;
      }
      return c.substring(0, first) + SURFACE_GLSL + "\n" + c.substring(first);
   }

   /**
    * Car bodies: speculars of the game's own five model lights (sun / moon and the lamps around, eye space, as the
    * shader already lights with them) - strong on the windows, a clear-coat sheen on the paint - and the active
    * headlights / taillights as emitters, written as the second output with the car's luminance for the composite's
    * colour match (a zombie in front of the car, drawn later).
    */
   static String patchVehicle(String code) {
      java.util.regex.Matcher am = java.util.regex.Pattern.compile("gl_FragColor\\s*=\\s*vec4\\(\\s*col\\s*,\\s*TexturePainColor\\.a\\s*\\);").matcher(code);
      if (!am.find() || !code.contains("ref_en") || !code.contains("t1en") || !code.contains("positionEye")) {
         return null;
      }
      String anchor = am.group();
      String glsl = String.join("\n",
            "    vec3 pzV = normalize(-positionEye.xyz);",
            "    vec3 pzS = vec3(0.0);",
            "    pzS += Light0Colour * pow(max(dot(normal, normalize(normalize(Light0Direction) + pzV)), 0.0), pzHdrCar.w);",
            "    pzS += Light1Colour * pow(max(dot(normal, normalize(normalize(Light1Direction) + pzV)), 0.0), pzHdrCar.w);",
            "    pzS += Light2Colour * pow(max(dot(normal, normalize(normalize(Light2Direction) + pzV)), 0.0), pzHdrCar.w);",
            "    pzS += Light3Colour * pow(max(dot(normal, normalize(normalize(Light3Direction) + pzV)), 0.0), pzHdrCar.w);",
            "    pzS += Light4Colour * pow(max(dot(normal, normalize(normalize(Light4Direction) + pzV)), 0.0), pzHdrCar.w);",
            "    float pzGlass = clamp(ref_en, 0.0, 1.0);",
            "    vec3 pzG = pzS * (pzHdrCar.x * pzGlass + pzHdrCar.y * (1.0 - pzGlass) * (1.0 - texColorRust.a * TextureRustA));",
            "    pzG += texColorLights.rgb * (texColorLights.a * t1en * pzHdrCar.z);",
            "    gl_FragData[1] = vec4(pzG / (1.0 + pzG), dot(col, vec3(0.2126, 0.7152, 0.0722)));",
            "    ");
      String c = code.replace(anchor, glsl + "gl_FragData[0] = vec4(col, TexturePainColor.a);").replace("gl_FragColor", "gl_FragData[0]");
      int main = c.indexOf("void main()");
      return c.substring(0, main) + "uniform vec4 pzHdrCar; // pzopt HDR: x glass, y paint, z lamp emitters, w specular exponent\n" + c.substring(main);
   }

   /** Test-compiles a patched unit; the stock source when it does not compile (or the anchors were missing). */
   static String patchChecked(String fileName, String code, String patched) {
      if (patched == null) {
         Log.warn("hdr: " + fileName + " has changed, no HDR patch applied");
         return code;
      }
      int test = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
      GL20.glShaderSource(test, patched);
      GL20.glCompileShader(test);
      boolean ok = GL20.glGetShaderi(test, GL20.GL_COMPILE_STATUS) != 0;
      String log = ok ? "" : GL20.glGetShaderInfoLog(test, 4096);
      GL20.glDeleteShader(test);
      if (!ok) {
         Log.warn("hdr: " + fileName + " HDR patch does not compile, stays stock: " + log);
         return code;
      }
      Log.info("hdr: HDR glint output added to " + fileName);
      return patched;
   }

   /** Shared by the reflective surfaces (water, puddles): sun glint, sky Fresnel, lamp glints; GLSL 1.20-safe. */
   static final String SURFACE_GLSL = String.join("\n",
         "",
         "// pzopt HDR: speculars and sky reflection, written as a second output (HdrGlint)",
         GAIN_GLSL,
         "uniform vec4 pzHdrSun; // sun direction (y up, x screen right, z screen down), strength",
         "uniform vec4 pzHdrSky; // sky colour, reflection strength",
         "uniform vec4 pzHdrGlintP; // x shininess, y on, z lamp glint strength",
         "uniform vec4 pzHdrE; // screen rect in window px",
         "uniform vec4 pzHdrW; // world rect in world-framebuffer px",
         "uniform sampler2D pzHdrNow; // glint-only pass: the world colour as it is now (texture barrier, colour writes off)",
         "uniform vec4 pzHdrNowP; // x 1 in the glint-only pass, zw 1 / world texture size",
         "vec3 pzGlint = vec3(0.0);",
         "// the luminance of the finished pixel under this surface fragment, for the composite's colour match (0.003 floor: 0 means no match)",
         "float pzSurfNow() {",
         "  if (pzHdrNowP.x < 0.5) return 0.0;",
         "  return 0.003 + 0.997 * dot(texture2D(pzHdrNow, gl_FragCoord.xy * pzHdrNowP.zw).rgb, vec3(0.2126, 0.7152, 0.0722));",
         "}",
         "vec2 pzWindowPx() {",
         "  return pzHdrE.xy + (gl_FragCoord.xy - pzHdrW.xy) * pzHdrE.zw / pzHdrW.zw;",
         "}",
         "vec3 pzHdrWaterGlint(vec3 n, vec2 windowPx) {",
         "  if (pzHdrGlintP.y < 0.5) return vec3(0.0);",
         "  vec3 V = normalize(vec3(0.0, 0.62, 0.78)); // toward the iso camera",
         "  vec3 L = normalize(pzHdrSun.xyz);",
         "  vec3 H = normalize(L + V);",
         "  float spec = pow(max(dot(n, H), 0.0), pzHdrGlintP.x) * pzHdrSun.w;",
         "  float nv = max(dot(n, V), 0.0);",
         "  float F = 0.02 + 0.98 * pow(1.0 - nv, 5.0);",
         "  vec3 g = vec3(1.0, 0.96, 0.88) * spec + pzHdrSky.rgb * (F * pzHdrSky.a);",
         "  if (pzHdrF.z > 0.5 && pzHdrGlintP.z > 0.0) {",
         "    vec4 m = pzHdrLightAt(windowPx);",
         "    vec3 Hl = normalize(normalize(vec3(0.0, 1.0, -0.35)) + V);",
         "    float sl = pow(max(dot(n, Hl), 0.0), pzHdrGlintP.x * 0.4);",
         "    g += m.rgb * (m.a * sl * pzHdrGlintP.z);",
         "  }",
         "  return g;",
         "}",
         "");

   static final String WORLD_GLSL = String.join("\n",
         "",
         "// pzopt HDR: expand the finished SDR world pixel to HDR, gamma-encoded relative to the UI white",
         GAIN_GLSL,
         "uniform sampler2D pzHdrBloom;",
         "uniform vec4 pzHdrE; // the player's screen rect in window px (xy bottom-left origin, zw size): bloom level 0 covers it",
         "uniform sampler2D pzHdrAux; // HdrLight aux map: r = sun exposure (outdoors x light)",
         "uniform vec4 pzHdrS; // x sun gain above 1 (sunMax - 1) x sun strength, y aux valid",
         "uniform sampler2D pzHdrGlintTex; // HdrGlint: water / puddle speculars (rgb g / (1 + g), a the surface luminance)",
         "uniform sampler2D pzHdrWorldTex; // the world texture (render size): what the surface became after later draws",
         "uniform vec4 pzHdrWR; // the world rect in the world texture's UV",
         "uniform vec4 pzHdrGl; // x glint valid, y glint strength",
         "vec3 pzHdrGlintAt(vec2 windowPx) {",
         "  if (pzHdrGl.x < 0.5) return vec3(0.0);",
         "  vec2 wuv = pzHdrWR.xy + (windowPx - pzHdrE.xy) / pzHdrE.zw * pzHdrWR.zw;",
         "  vec4 e = texture2D(pzHdrGlintTex, wuv);",
         "  if (e.r + e.g + e.b < 0.004) return vec3(0.0);",
         "  float now = dot(texture2D(pzHdrWorldTex, wuv).rgb, vec3(0.2126, 0.7152, 0.0722));",
         "  // a = the pixel's luminance when the glint-only pass drew (after the water / puddles blended in); anything drawn over it",
         "  // since (the visibility shade, trees, rain, a zombie) changed the colour: no glint there",
         "  float vis = e.a < 0.002 ? 1.0 : 1.0 - smoothstep(0.03, 0.10, abs(now - e.a));",
         "  vec3 g = clamp(e.rgb, 0.0, 0.996);",
         "  return g / (1.0 - g) * vis * pzHdrGl.y;",
         "}",
         "vec3 pzHdrWorld(vec3 c) {",
         "  if (pzHdrA.x < 0.5) return c;",
         "  float g = pzHdrD.x;",
         "  vec3 lin = pow(clamp(c, 0.0, 1.0), vec3(g));",
         "  if (pzHdrF.w > 4.5) {",
         "    vec2 wuv = pzHdrWR.xy + (gl_FragCoord.xy - pzHdrE.xy) / pzHdrE.zw * pzHdrWR.zw;",
         "    vec4 e = texture2D(pzHdrGlintTex, wuv);",
         "    float now = dot(texture2D(pzHdrWorldTex, wuv).rgb, vec3(0.2126, 0.7152, 0.0722));",
         "    float vis = e.a < 0.002 ? 1.0 : 1.0 - smoothstep(0.03, 0.10, abs(now - e.a));",
         "    // debug 5: glint target, yellow = the glint shown, red = glint dropped by the colour match (blue = target not valid)",
         "    return pzHdrGl.x > 0.5 ? mix(c * 0.3, mix(vec3(1.0, 0.0, 0.0), vec3(1.0, 1.0, 0.0), vis), clamp(e.r * 4.0, 0.0, 1.0)) : vec3(0.0, 0.0, 1.0);",
         "  }",
         "  if (pzHdrF.w > 3.5) {",
         "    vec3 b = texture2D(pzHdrBloom, (gl_FragCoord.xy - pzHdrE.xy) / pzHdrE.zw).rgb;",
         "    return pzHdrD.w > 0.5 ? pow(clamp(b * 4.0, 0.0, 1.0), vec3(1.0 / 2.2)) : vec3(1.0, 0.0, 1.0); // debug 4: the bloom (magenta = not ready)",
         "  }",
         "  if (pzHdrF.w > 0.5 && pzHdrF.z > 0.5) {",
         "    vec3 p = vec3(gl_FragCoord.xy, 1.0);",
         "    vec2 luv = vec2(dot(pzHdrL0, p), dot(pzHdrL1, p));",
         "    vec4 lm = texture(pzHdrLight, luv);",
         "    if (pzHdrF.w > 2.5) return lm.rgb;",
         "    if (pzHdrF.w > 1.5) { vec2 sq = floor(luv * 256.0); float k = mod(sq.x + sq.y, 2.0); return mix(c, vec3(k, 1.0 - k, 0.0), 0.35); }",
         "    return mix(c, vec3(1.0, 0.0, 0.0), pzHdrLightExcess(gl_FragCoord.xy) * 0.8);",
         "  }",
         "  lin *= pzHdrGain(lin, pzHdrNight()) * pzHdrLightGain(gl_FragCoord.xy);",
         "  if (pzHdrS.y > 0.5 && pzHdrS.x > 0.0) {",
         "    vec3 sp = vec3(gl_FragCoord.xy, 1.0);",
         "    lin *= 1.0 + pzHdrS.x * texture2D(pzHdrAux, vec2(dot(pzHdrL0, sp), dot(pzHdrL1, sp))).r;",
         "  }",
         "  lin += pzHdrGlintAt(gl_FragCoord.xy);",
         "  if (pzHdrD.w > 0.5) {",
         "    lin += pzHdrD.z * texture(pzHdrBloom, (gl_FragCoord.xy - pzHdrE.xy) / pzHdrE.zw).rgb;",
         "  }",
         "  if (pzHdrC.x != 0.0) {",
         "    float yy = dot(lin, vec3(0.2126, 0.7152, 0.0722));",
         "    lin = clamp(mix(vec3(yy), lin, 1.0 + pzHdrC.x), 0.0, 65504.0);",
         "  }",
         "  lin = clamp(lin * pzHdrA.y + pzHdrC.y * pzHdrA.y, 0.0, 65504.0);",
         "  // soft roll-off into the headroom (peak / UI white) here, in the world pass: without the encode pass (hdrEncode off /",
         "  // auto on KDE) nothing else would, and the compositor clips (hdr20: 2828 nits asked of a 1307-nit panel)",
         "  float hr = pzHdrA.z * pzHdrA.y;",
         "  float mc = max(max(lin.r, lin.g), lin.b);",
         "  float kn = 0.75 * hr;",
         "  if (mc > kn && hr > kn) {",
         "    float x = (mc - kn) / (hr - kn);",
         "    lin *= (kn + (hr - kn) * (x / (1.0 + x))) / mc;",
         "  }",
         "  return pow(lin, vec3(1.0 / g));",
         "}",
         "void main() {",
         "  pzStockMain();",
         "  gl_FragColor = vec4(pzHdrWorld(gl_FragColor.rgb), gl_FragColor.a);",
         "}",
         "");

   private static int worldProgram;
   private static int uA, uB, uC, uD, uE, uStats, uBloom, uL0, uL1, uF, uLight;
   static final int STATS_UNIT = 7, BLOOM_UNIT = 6, LIGHT_UNIT = 5;
   /** The world rect (UV of the world texture) the stats / bloom passes read this frame; the composite maps vUV through it. */
   private static final float[] worldRectUv = {0F, 0F, 1F, 1F};

   /** Render thread, WeatherShader.startRenderThread() (the composite program is bound): this frame's expansion uniforms. */
   public static void worldUniforms() {
      if (!REQUESTED) {
         return;
      }
      int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      if (prog == 0) {
         return;
      }
      if (prog != worldProgram) {
         worldProgram = prog;
         uA = GL20.glGetUniformLocation(prog, "pzHdrA");
         uB = GL20.glGetUniformLocation(prog, "pzHdrB");
         uC = GL20.glGetUniformLocation(prog, "pzHdrC");
         uD = GL20.glGetUniformLocation(prog, "pzHdrD");
         uE = GL20.glGetUniformLocation(prog, "pzHdrE");
         uStats = GL20.glGetUniformLocation(prog, "pzHdrStats");
         uBloom = GL20.glGetUniformLocation(prog, "pzHdrBloom");
         uL0 = GL20.glGetUniformLocation(prog, "pzHdrL0");
         uL1 = GL20.glGetUniformLocation(prog, "pzHdrL1");
         uF = GL20.glGetUniformLocation(prog, "pzHdrF");
         uLight = GL20.glGetUniformLocation(prog, "pzHdrLight");
         Log.info("hdr: composite program " + prog + " pzHdrA at " + uA);
      }
      if (uA < 0) {
         return;
      }
      setGainUniforms(uA, uB, uC, uD);
      setLightUniforms(uL0, uL1, uF, uLight);
      setWindowRect(uE);
      boolean glint = HdrGlint.bindForComposite();
      boolean aux = HdrLight.bindAux();
      GL20.glUniform1i(GL20.glGetUniformLocation(prog, "pzHdrAux"), HdrLight.AUX_UNIT);
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrS"), (tune.sunMax - 1F) * HdrGlint.sunStrength, aux ? 1F : 0F, 0F, 0F);
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrGl"), glint ? 1F : 0F, 1F, 0F, 0F);
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrWR"), worldRectUv[0], worldRectUv[1], worldRectUv[2], worldRectUv[3]);
      GL20.glUniform1i(GL20.glGetUniformLocation(prog, "pzHdrGlintTex"), HdrGlint.GLINT_UNIT);
      GL20.glUniform1i(GL20.glGetUniformLocation(prog, "pzHdrWorldTex"), HdrGlint.WORLD_UNIT);
      if (uStats >= 0) {
         GL20.glUniform1i(uStats, STATS_UNIT);
      }
      if (uBloom >= 0) {
         GL20.glUniform1i(uBloom, BLOOM_UNIT);
      }
   }

   /**
    * Render thread, a patched surface shader (water, puddles) is bound: the light map (lamp glints at night), the screen
    * rect and the world rect in world-framebuffer px (pzHdrW) so its gl_FragCoord maps to window px.
    */
   static void surfaceLightUniforms(int prog) {
      int[] l = new int[5];
      String[] n = {"pzHdrL0", "pzHdrL1", "pzHdrF", "pzHdrLight", "pzHdrE"};
      for (int i = 0; i < n.length; i++) {
         l[i] = GL20.glGetUniformLocation(prog, n[i]);
      }
      setLightUniforms(l[0], l[1], l[2], l[3]);
      setWindowRect(l[4]);
      int[] rect = RenderScale.active() ? RenderScale.scaledRect(0) : new int[] {0, 0, Core.width, Core.height};
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrW"), rect[0], rect[1], rect[2], rect[3]);
   }

   /** The player's screen rect in window px, origin bottom-left (gl_FragCoord space of the composite). */
   private static void setWindowRect(int loc) {
      int sw = zombie.iso.IsoCamera.getScreenWidth(0), sh = zombie.iso.IsoCamera.getScreenHeight(0);
      int left = zombie.iso.IsoCamera.getScreenLeft(0), top = zombie.iso.IsoCamera.getScreenTop(0);
      GL20.glUniform4f(loc, left, Core.height - top - sh, sw, sh);
   }

   /** 1 at night, 0 in daylight: the climate's daylight (0.15 .. 0.5) as the cap of the night keys. */
   static float nightCap() {
      return 1F - Math.max(0F, Math.min(1F, (HdrGlint.daylight - 0.15F) / 0.35F));
   }

   private static void setLightUniforms(int l0, int l1, int f, int sampler) {
      int prog = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      float nightCap = nightCap();
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrH"), tune.lightTint, nightCap, 0F, 0F);
      float levelPx = 96F * Core.tileScale / Math.max(0.1F, Core.getInstance().getZoom(0)); // one floor level on screen
      GL20.glUniform4f(GL20.glGetUniformLocation(prog, "pzHdrG"), tune.lightMax, tune.itmDay, 1F + (tune.flashMax - 1F) * flash, levelPx * tune.lightReach);
      float[] m = HdrLight.mapping;
      GL20.glUniform3f(l0, m[0], m[1], m[2]);
      GL20.glUniform3f(l1, m[3], m[4], m[5]);
      GL20.glUniform4f(f, tune.light, tune.lightCurve, HdrLight.ready && tune.light > 0F ? 1F : 0F, tune.debugView);
      GL20.glUniform1i(sampler, LIGHT_UNIT);
   }

   private static void setGainUniforms(int a, int b, int c, int d) {
      Tune t = tune;
      float ui = uiNits(t);
      float paper = ui * t.paperPct / 100F;
      float peak = peakNits(t);
      GL20.glUniform4f(a, active && !alphaGain ? 1F : 0F, paper / ui, Math.max(1F, peak / paper), t.itm);
      GL20.glUniform4f(b, t.thresholdDay, t.thresholdNight, t.curve, t.chroma);
      GL20.glUniform4f(c, t.saturation, t.blackLift / Math.max(paper, 1F), t.nightLo, t.nightHi);
      GL20.glUniform4f(d, t.gamma, statsTex != 0 ? 1F : 0F, t.bloom, bloomReady && t.bloom > 0F ? 1F : 0F);
   }

   // macOS EDR has no nits: 1.0 is the screen's SDR white at its current brightness, so hdrUiNits / hdrPeakNits do not apply
   static float uiNits(Tune t) {
      return t.uiNits > 0 && !HdrMac.MAC ? t.uiNits : (float)HdrWayland.encRef;
   }

   static float peakNits(Tune t) {
      return t.peakNits > 0 && !HdrMac.MAC ? Math.min(t.peakNits, (float)HdrWayland.encMax) : (float)HdrWayland.encMax;
   }

   // ---- world passes before the composite: average luminance (GPU mip chain, no read-back) and bloom ----

   private static int statsTex, statsFbo, statsProgram, statsVao;
   private static final int STATS_W = 256, STATS_H = 128;
   private static final int BLOOM_LEVELS = 6; // 1/2 .. 1/64 of the world rect
   private static final int[] bloomTex = new int[BLOOM_LEVELS], bloomFbo = new int[BLOOM_LEVELS], bloomW = new int[BLOOM_LEVELS], bloomH = new int[BLOOM_LEVELS];
   private static int brightProgram, downProgram, upProgram;
   private static int[] brightU; // src, rect, stats, A, B, C, D
   private static boolean bloomReady;

   /** Game thread, MultiTextureFBO2.render() before the composite quads: queue the world passes. */
   /** This frame's lightning (0..1), sampled on the game thread with the frame. */
   private static volatile float flash;

   public static void queueWorldStats() {
      flash = active ? HdrFlash.now() : 0F;
      if (active) {
         HdrGlint.updateDaylight();
      }
      if (active && !alphaGain) {
         HdrGlint.update();
      }
      if (active && tune.light > 0F) {
         HdrLight.queue(tune.lightFlipY);
      }
      if (Config.DEV_HDR_TRACE_MS > 0 && zombie.characters.IsoPlayer.players[0] != null) {
         zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.players[0];
         traceDir = String.valueOf(p.getDir());
         traceAngle = (float)Math.toDegrees(Math.atan2(p.getForwardDirection().y, p.getForwardDirection().x));
      }
      if (active) {
         SpriteRenderer.instance.drawGeneric(STATS);
      }
   }

   private static final TextureDraw.GenericDrawer STATS = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         try {
            worldPasses();
         } catch (Throwable t) {
            Log.warn("hdr: world passes failed: " + t);
            statsTex = 0;
            bloomReady = false;
         }
      }
   };

   private static final int[] SAVED_VIEWPORT = new int[4];

   private static void worldPasses() {
      if (HdrMac.MAC) {
         return; // the stats / bloom passes need GL 3+ (texture storage, VAOs, GLSL 330); the macOS context is 2.1
      }
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || world.getTexture() == null) {
         return;
      }
      if (statsProgram == 0) {
         statsProgram = Shaders.program("hdr stats", FULLSCREEN_VERT, STATS_FRAG);
         if (statsProgram == 0) {
            return;
         }
         statsTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, statsTex);
         int levels = 1 + (int)Math.floor(Math.log(Math.max(STATS_W, STATS_H)) / Math.log(2));
         org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, levels, GL30.GL_RGBA16F, STATS_W, STATS_H);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
         statsFbo = fboFor(statsTex);
         statsVao = GL30.glGenVertexArrays();
      }
      Texture tex = (Texture)world.getTexture();
      int[] rect = RenderScale.active() ? RenderScale.scaledRect(0) : new int[] {0, 0, Core.width, Core.height};
      float tw = tex.getWidthHW(), th = tex.getHeightHW();
      worldRectUv[0] = rect[0] / tw;
      worldRectUv[1] = rect[1] / th;
      worldRectUv[2] = rect[2] / tw;
      worldRectUv[3] = rect[3] / th;
      GpuSections.markNow("hdr.stats", false);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, SAVED_VIEWPORT);
      int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
      int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL30.glBindVertexArray(statsVao);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);

      // 1. average luminance
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, statsFbo);
      GL11.glViewport(0, 0, STATS_W, STATS_H);
      GL20.glUseProgram(statsProgram);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.getID());
      GL20.glUniform1i(GL20.glGetUniformLocation(statsProgram, "src"), 0);
      GL20.glUniform4f(GL20.glGetUniformLocation(statsProgram, "rect"), worldRectUv[0], worldRectUv[1], worldRectUv[2], worldRectUv[3]);
      GL20.glUniform1f(GL20.glGetUniformLocation(statsProgram, "gamma"), tune.gamma);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, statsTex);
      GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + STATS_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, statsTex); // the composite and the bright pass sample it on unit 7
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GpuSections.markNow("hdr.stats", true);
      if (Config.DEV_HDR_TRACE_MS > 0) {
         trace();
      }

      // 2. bloom from what the expansion adds above SDR
      if (tune.bloom > 0F) {
         GpuSections.markNow("hdr.bloom", false);
         bloomReady = bloom(tex.getID(), rect[2], rect[3]);
         GpuSections.markNow("hdr.bloom", true);
      } else {
         bloomReady = false;
      }

      // restore
      GL13.glActiveTexture(prevActive);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      GL30.glBindVertexArray(prevVao);
      GL20.glUseProgram(prevProgram);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
      if (blend) {
         GL11.glEnable(GL11.GL_BLEND);
      } else {
         GL11.glDisable(GL11.GL_BLEND);
      }
      if (scissor) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
      if (depth) {
         GL11.glEnable(GL11.GL_DEPTH_TEST);
      }
      if (stencil) {
         GL11.glEnable(GL11.GL_STENCIL_TEST);
      }
   }

   private static volatile String traceDir = "?";
   private static volatile float traceAngle;
   private static long traceNextMs;
   private static final java.nio.FloatBuffer TRACE_BUF = BufferUtils.createFloatBuffer(4);

   /** devHdrTraceMs: the stats pass's 1x1 mip read back (a sync read, dev only) next to the keys it drives. */
   private static void trace() {
      long now = System.currentTimeMillis();
      if (now < traceNextMs) {
         return;
      }
      traceNextMs = now + Config.DEV_HDR_TRACE_MS;
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, statsTex);
      int top = (int)Math.floor(Math.log(Math.max(STATS_W, STATS_H)) / Math.log(2));
      TRACE_BUF.clear();
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, top, GL11.GL_RGBA, GL11.GL_FLOAT, TRACE_BUF);
      float avg = TRACE_BUF.get(3);
      float dl = HdrGlint.daylight, nightCap = nightCap();
      float s = Math.max(0F, Math.min(1F, (avg - tune.nightLo) / Math.max(1e-6F, tune.nightHi - tune.nightLo)));
      float night = Math.min(1F - s * s * (3F - 2F * s), nightCap);
      Log.info(String.format("hdr trace: dir=%s angle=%.0f avg=%.4f night=%.2f nightCap=%.2f daylight=%.2f lightAmbient=%.3f seen=%d maxExcess=%d could=%d counted=%d medAll=%d medSeen=%d medCould=%d",
            traceDir, traceAngle, avg, night, nightCap, dl, HdrLight.lastAmbient, HdrLight.lastSeen, HdrLight.lastMaxExcess, HdrLight.lastCould, HdrLight.lastCounted,
            HdrLight.lastMedAll, HdrLight.lastMedSeen, HdrLight.lastMedCould));
   }

   private static int fboFor(int texture) {
      int fbo = GL30.glGenFramebuffers();
      int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
      return fbo;
   }

   /** Bright pass into level 0 (half the world rect), 13-tap downsample to 1/64, tent upsample added back up. */
   private static boolean bloom(int worldTex, int rw, int rh) {
      if (brightProgram == 0) {
         brightProgram = Shaders.program("hdr bloom bright", FULLSCREEN_VERT, BRIGHT_FRAG);
         downProgram = Shaders.program("hdr bloom down", FULLSCREEN_VERT, DOWN_FRAG);
         upProgram = Shaders.program("hdr bloom up", FULLSCREEN_VERT, UP_FRAG);
         if (brightProgram == 0 || downProgram == 0 || upProgram == 0) {
            tune.bloom = 0F;
            return false;
         }
         String[] names = {"src", "rect", "pzHdrStats", "pzHdrA", "pzHdrB", "pzHdrC", "pzHdrD", "pzHdrL0", "pzHdrL1", "pzHdrF", "pzHdrLight", "pzHdrE"};
         brightU = new int[names.length];
         for (int i = 0; i < names.length; i++) {
            brightU[i] = GL20.glGetUniformLocation(brightProgram, names[i]);
         }
      }
      int w0 = Math.max(1, rw / 2), h0 = Math.max(1, rh / 2);
      if (bloomTex[0] == 0 || bloomW[0] != w0 || bloomH[0] != h0) {
         for (int i = 0; i < BLOOM_LEVELS; i++) {
            if (bloomTex[i] != 0) {
               GL11.glDeleteTextures(bloomTex[i]);
               GL30.glDeleteFramebuffers(bloomFbo[i]);
            }
            bloomW[i] = Math.max(1, w0 >> i);
            bloomH[i] = Math.max(1, h0 >> i);
            bloomTex[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTex[i]);
            org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R11F_G11F_B10F, bloomW[i], bloomH[i]);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP_TO_EDGE);
            bloomFbo[i] = fboFor(bloomTex[i]);
         }
      }
      // bright pass
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo[0]);
      GL11.glViewport(0, 0, bloomW[0], bloomH[0]);
      GL20.glUseProgram(brightProgram);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldTex);
      GL20.glUniform1i(brightU[0], 0);
      GL20.glUniform4f(brightU[1], worldRectUv[0], worldRectUv[1], worldRectUv[2], worldRectUv[3]);
      GL20.glUniform1i(brightU[2], STATS_UNIT);
      setGainUniforms(brightU[3], brightU[4], brightU[5], brightU[6]);
      setLightUniforms(brightU[7], brightU[8], brightU[9], brightU[10]);
      setWindowRect(brightU[11]);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      // down
      GL20.glUseProgram(downProgram);
      int dSrc = GL20.glGetUniformLocation(downProgram, "src"), dTexel = GL20.glGetUniformLocation(downProgram, "texel");
      GL20.glUniform1i(dSrc, 0);
      for (int i = 1; i < BLOOM_LEVELS; i++) {
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo[i]);
         GL11.glViewport(0, 0, bloomW[i], bloomH[i]);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTex[i - 1]);
         GL20.glUniform2f(dTexel, 1F / bloomW[i - 1], 1F / bloomH[i - 1]);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      }
      // up, additive
      GL20.glUseProgram(upProgram);
      int uSrc = GL20.glGetUniformLocation(upProgram, "src"), uTexel = GL20.glGetUniformLocation(upProgram, "texel");
      GL20.glUniform1i(uSrc, 0);
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
      for (int i = BLOOM_LEVELS - 1; i > 0; i--) {
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFbo[i - 1]);
         GL11.glViewport(0, 0, bloomW[i - 1], bloomH[i - 1]);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTex[i]);
         GL20.glUniform2f(uTexel, 1F / bloomW[i], 1F / bloomH[i]);
         GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      }
      GL11.glDisable(GL11.GL_BLEND);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + BLOOM_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTex[0]); // the composite samples it on unit 6
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      return true;
   }

   private static final int GL12_CLAMP_TO_EDGE = 0x812F;

   static final String FULLSCREEN_VERT = String.join("\n",
         "#version 330",
         "out vec2 uv;",
         "void main() {",
         "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));",
         "  uv = p;",
         "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);",
         "}");

   static final String STATS_FRAG = String.join("\n",
         "#version 330",
         "uniform sampler2D src;",
         "uniform vec4 rect;",
         "uniform float gamma;",
         "in vec2 uv;",
         "out vec4 o;",
         "void main() {",
         "  vec3 c = texture(src, rect.xy + uv * rect.zw).rgb;",
         "  vec3 lin = pow(clamp(c, 0.0, 1.0), vec3(gamma));",
         "  o = vec4(lin, dot(lin, vec3(0.2126, 0.7152, 0.0722)));",
         "}");

   /** The stock grading of screen.frag (desaturate 0.1, contrast 1.2 around 0.4) so the bloom sees what the composite expands. */
   static final String BRIGHT_FRAG = String.join("\n",
         "#version 330",
         "uniform sampler2D src;",
         "uniform vec4 rect;",
         "uniform vec4 pzHdrE; // the screen rect in window px: the bright pass's uv -> gl_FragCoord of the composite",
         GAIN_GLSL,
         "in vec2 uv;",
         "out vec4 o;",
         "void main() {",
         "  vec3 c = clamp(texture(src, rect.xy + uv * rect.zw).rgb, 0.0, 1.0);",
         "  float i = dot(c, vec3(0.3, 0.59, 0.11));",
         "  c = (mix(c, vec3(i), 0.1) - 0.4) * 1.2 + 0.4;",
         "  vec3 lin = pow(clamp(c, 0.0, 1.0), vec3(pzHdrD.x));",
         "  vec3 gain = pzHdrGain(lin, pzHdrNight()) * pzHdrLightGain(pzHdrE.xy + uv * pzHdrE.zw);",
         "  o = vec4(lin * (gain - 1.0), 1.0);",
         "}");

   static final String DOWN_FRAG = String.join("\n",
         "#version 330",
         "uniform sampler2D src;",
         "uniform vec2 texel;",
         "in vec2 uv;",
         "out vec4 o;",
         "vec3 s(vec2 d) { return texture(src, uv + d * texel).rgb; }",
         "void main() {",
         "  vec3 c = s(vec2(0.0)) * 0.125;",
         "  c += (s(vec2(-1.0, -1.0)) + s(vec2(1.0, -1.0)) + s(vec2(-1.0, 1.0)) + s(vec2(1.0, 1.0))) * 0.125;",
         "  c += (s(vec2(-2.0, -2.0)) + s(vec2(2.0, -2.0)) + s(vec2(-2.0, 2.0)) + s(vec2(2.0, 2.0))) * 0.03125;",
         "  c += (s(vec2(-2.0, 0.0)) + s(vec2(2.0, 0.0)) + s(vec2(0.0, -2.0)) + s(vec2(0.0, 2.0))) * 0.0625;",
         "  o = vec4(c, 1.0);",
         "}");

   static final String UP_FRAG = String.join("\n",
         "#version 330",
         "uniform sampler2D src;",
         "uniform vec2 texel;",
         "in vec2 uv;",
         "out vec4 o;",
         "vec3 s(vec2 d) { return texture(src, uv + d * texel).rgb; }",
         "void main() {",
         "  vec3 c = s(vec2(0.0)) * 4.0;",
         "  c += (s(vec2(-1.0, 0.0)) + s(vec2(1.0, 0.0)) + s(vec2(0.0, -1.0)) + s(vec2(0.0, 1.0))) * 2.0;",
         "  c += s(vec2(-1.0, -1.0)) + s(vec2(1.0, -1.0)) + s(vec2(-1.0, 1.0)) + s(vec2(1.0, 1.0));",
         "  o = vec4(c / 16.0, 1.0);",
         "}");

   // ---- alpha-gain mode: the world gain into the back buffer's alpha, after the composite ----

   private static int gainProgram;
   private static int[] gainU; // src, rect, pzHdrStats, A, B, C, D, L0, L1, F, Light, log2Max

   /** Game thread, MultiTextureFBO2.render() after the composite quads (before the UI). */
   public static void queueGainAlpha() {
      if (active && alphaGain) {
         SpriteRenderer.instance.drawGeneric(GAIN_ALPHA);
      }
   }

   private static final TextureDraw.GenericDrawer GAIN_ALPHA = new TextureDraw.GenericDrawer() {
      @Override
      public void render() {
         try {
            gainAlphaPass();
         } catch (Throwable t) {
            Log.warn("hdr: gain pass failed, HDR off: " + t);
            active = false;
         }
      }
   };

   private static void gainAlphaPass() {
      TextureFBO world = Core.getInstance().getOffscreenBuffer();
      if (world == null || world.getTexture() == null) {
         return;
      }
      if (gainProgram == 0) {
         gainProgram = Shaders.program("hdr gain alpha", QUAD120_VERT, GAIN_ALPHA_FRAG);
         if (gainProgram == 0) {
            throw new IllegalStateException("gain shader failed");
         }
         String[] names = {"src", "rect", "pzHdrStats", "pzHdrA", "pzHdrB", "pzHdrC", "pzHdrD", "pzHdrL0", "pzHdrL1", "pzHdrF", "pzHdrLight", "log2Max"};
         gainU = new int[names.length];
         for (int i = 0; i < names.length; i++) {
            gainU[i] = GL20.glGetUniformLocation(gainProgram, names[i]);
         }
      }
      Texture tex = (Texture)world.getTexture();
      int sw = zombie.iso.IsoCamera.getScreenWidth(0), sh = zombie.iso.IsoCamera.getScreenHeight(0);
      int left = zombie.iso.IsoCamera.getScreenLeft(0), top = zombie.iso.IsoCamera.getScreenTop(0);
      GpuSections.markNow("hdr.gain", false);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, SAVED_VIEWPORT);
      int prevDraw = GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_BINDING_EXT); // EXT: the macOS context is legacy 2.1
      int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, 0);
      GL11.glViewport(left, Core.height - top - sh, sw, sh);
      GL11.glColorMask(false, false, false, true);
      GL20.glUseProgram(gainProgram);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.getID());
      GL20.glUniform1i(gainU[0], 0);
      GL20.glUniform4f(gainU[1], worldRectUv[0], worldRectUv[1], worldRectUv[2], worldRectUv[3]);
      GL20.glUniform1i(gainU[2], STATS_UNIT);
      setGainUniforms(gainU[3], gainU[4], gainU[5], gainU[6]);
      setLightUniforms(gainU[7], gainU[8], gainU[9], gainU[10]);
      GL20.glUniform1f(gainU[11], (float)(Math.log(GAIN_MAX) / Math.log(2)));
      GL11.glBegin(GL11.GL_QUADS); // a legacy quad: the macOS context is OpenGL 2.1 (GLSL 1.20, no gl_VertexID)
      GL11.glTexCoord2f(0F, 0F);
      GL11.glVertex2f(-1F, -1F);
      GL11.glTexCoord2f(1F, 0F);
      GL11.glVertex2f(1F, -1F);
      GL11.glTexCoord2f(1F, 1F);
      GL11.glVertex2f(1F, 1F);
      GL11.glTexCoord2f(0F, 1F);
      GL11.glVertex2f(-1F, 1F);
      GL11.glEnd();
      GL11.glColorMask(true, true, true, true);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      GL13.glActiveTexture(prevActive);
      GL20.glUseProgram(prevProgram);
      org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferBlit.GL_DRAW_FRAMEBUFFER_EXT, prevDraw);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      if (blend) {
         GL11.glEnable(GL11.GL_BLEND);
      }
      if (scissor) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
      if (depth) {
         GL11.glEnable(GL11.GL_DEPTH_TEST);
      }
      if (stencil) {
         GL11.glEnable(GL11.GL_STENCIL_TEST);
      }
      GpuSections.markNow("hdr.gain", true);
   }

   /** GLSL 1.20 (the macOS legacy context; also accepted by compatibility contexts elsewhere). */
   static final String QUAD120_VERT = String.join("\n",
         "#version 120",
         "varying vec2 uv;",
         "void main() {",
         "  uv = gl_MultiTexCoord0.xy;",
         "  gl_Position = gl_Vertex;",
         "}");

   /** The world gain of this pixel (the composite's stock grading, the ITM, the light map, lightning), log-encoded into alpha. */
   static final String GAIN_ALPHA_FRAG = String.join("\n",
         "#version 120",
         "uniform sampler2D src;",
         "uniform vec4 rect;",
         "uniform float log2Max;",
         GAIN_GLSL,
         "varying vec2 uv;",
         "void main() {",
         "  vec3 c = clamp(texture2D(src, rect.xy + uv * rect.zw).rgb, 0.0, 1.0);",
         "  float i = dot(c, vec3(0.3, 0.59, 0.11));",
         "  c = (mix(c, vec3(i), 0.1) - 0.4) * 1.2 + 0.4;",
         "  vec3 lin = pow(clamp(c, 0.0, 1.0), vec3(pzHdrD.x));",
         "  vec3 gv = pzHdrGain(lin, pzHdrNight()) * pzHdrLightGain(gl_FragCoord.xy);",
         "  float g = max(max(gv.r, gv.g), gv.b);",
         "  gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0 - clamp(log2(max(g, 1.0)) / log2Max, 0.0, 1.0));",
         "}");

   // ---- the encode pass at the swap ----

   private static int copyTex, copyFbo, copyW, copyH, encodeProgram, encodeVao;
   private static int eSrc, eP0, eP1;
   private static long frames;

   /** Display.swapBuffers(), render thread, right before the swap: SDR-encoded extended frame -> ext_linear. */
   public static void beforeSwap() {
      if (!active || alphaGain) {
         return; // alpha-gain mode: HdrMac.present() does the encode
      }
      frames++;
      try {
         if ((frames & 63) == 0) {
            HdrWayland.pump();
            if (HdrWayland.preferredGeneration != seenGeneration) {
               seenGeneration = HdrWayland.preferredGeneration;
               HdrWayland.refresh();
            }
         }
         reloadTune();
         if ((frames % 2400) == 0 && HdrLight.builds > 0) {
            Log.info(String.format("hdr: light map %.3f ms/frame over %d builds", HdrLight.buildNs / 1e6 / HdrLight.builds, HdrLight.builds));
            HdrLight.buildNs = 0;
            HdrLight.builds = 0;
         }
         boolean diag = frames == 120 || frames == 600 || frames == 3000;
         if (diag) {
            diagnose("before");
         }
         if (HdrWayland.described) {
            encode();
         } else if (copyW != org.lwjglx.opengl.Display.getWidth() || copyH != org.lwjglx.opengl.Display.getHeight()) {
            copyW = org.lwjglx.opengl.Display.getWidth(); // hdrEncode=false: nothing to run, the size is for the dumps
            copyH = org.lwjglx.opengl.Display.getHeight();
         }
         if (diag) {
            diagnose("after");
         }
         dumpIfRequested();
      } catch (Throwable t) {
         Log.warn("hdr: encode failed, HDR off: " + t);
         active = false;
      }
   }

   private static int seenGeneration;
   private static final java.nio.FloatBuffer DIAG = BufferUtils.createFloatBuffer(4);

   /** Dev: logs sizes, GL errors and the centre pixel of the back buffer (and of the copy) around the encode. */
   private static void diagnose(String when) {
      int w = org.lwjglx.opengl.Display.getWidth(), h = org.lwjglx.opengl.Display.getHeight();
      int[] fw = new int[1], fh = new int[1];
      GLFW.glfwGetFramebufferSize(org.lwjglx.opengl.Display.getWindow(), fw, fh);
      int err0 = GL11.glGetError();
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
      GL11.glReadBuffer(GL11.GL_BACK);
      DIAG.clear();
      GL11.glReadPixels(w / 2, h / 2, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, DIAG);
      String back = String.format("%.3f,%.3f,%.3f,%.3f", DIAG.get(0), DIAG.get(1), DIAG.get(2), DIAG.get(3));
      int err1 = GL11.glGetError();
      String copy = "-";
      if (copyFbo != 0) {
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyFbo);
         DIAG.clear();
         GL11.glReadPixels(w / 2, h / 2, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, DIAG);
         copy = String.format("%.3f,%.3f,%.3f,%.3f", DIAG.get(0), DIAG.get(1), DIAG.get(2), DIAG.get(3));
      }
      int err2 = GL11.glGetError();
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      Log.info(String.format("hdr diag %s frame %d: display %dx%d glfw fb %dx%d drawFbo %d err0 0x%x back(center)=%s err1 0x%x copy(center)=%s err2 0x%x stats=%d prog=%d uA=%d",
            when, frames, w, h, fw[0], fh[0], draw, err0, back, err1, copy, err2, statsTex, worldProgram, uA));
   }

   private static void encode() {
      int w = org.lwjglx.opengl.Display.getWidth();
      int h = org.lwjglx.opengl.Display.getHeight();
      if (w <= 0 || h <= 0) {
         return;
      }
      if (encodeProgram == 0) {
         encodeProgram = Shaders.program("hdr encode", FULLSCREEN_VERT, ENCODE_FRAG);
         if (encodeProgram == 0) {
            throw new IllegalStateException("encode shader failed");
         }
         eSrc = GL20.glGetUniformLocation(encodeProgram, "src");
         eP0 = GL20.glGetUniformLocation(encodeProgram, "p0");
         eP1 = GL20.glGetUniformLocation(encodeProgram, "p1");
         encodeVao = GL30.glGenVertexArrays();
      }
      if (copyTex == 0 || copyW != w || copyH != h) {
         if (copyTex != 0) {
            GL11.glDeleteTextures(copyTex);
            GL30.glDeleteFramebuffers(copyFbo);
         }
         copyTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
         org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_RGBA16F, w, h);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         copyFbo = GL30.glGenFramebuffers();
         int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, copyFbo);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, copyTex, 0);
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
         copyW = w;
         copyH = h;
      }
      GpuSections.markNow("hdr.encode", false);
      GL11.glGetIntegerv(GL11.GL_VIEWPORT, SAVED_VIEWPORT);
      int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
      int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
      int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
      boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
            stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      // 1. back buffer -> copy
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
      GL11.glReadBuffer(GL11.GL_BACK);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
      GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
      // 2. copy -> back buffer, encoded
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
      GL11.glDrawBuffer(GL11.GL_BACK);
      GL11.glDisable(GL11.GL_BLEND);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_STENCIL_TEST);
      GL11.glColorMask(true, true, true, true);
      GL11.glViewport(0, 0, w, h);
      GL20.glUseProgram(encodeProgram);
      GL30.glBindVertexArray(encodeVao);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
      GL20.glUniform1i(eSrc, 0);
      Tune t = tune;
      float ui = uiNits(t);
      float peak = peakNits(t);
      float max = (float)HdrWayland.encMax;
      GL20.glUniform4f(eP0, ui / max, peak / max, t.knee, t.gamma);
      GL20.glUniform4f(eP1, 0F, 0F, 0F, 0F);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      // restore
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      Texture.lastTextureID = -1;
      GL13.glActiveTexture(prevActive);
      GL30.glBindVertexArray(prevVao);
      GL20.glUseProgram(prevProgram);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
      GL11.glViewport(SAVED_VIEWPORT[0], SAVED_VIEWPORT[1], SAVED_VIEWPORT[2], SAVED_VIEWPORT[3]);
      if (blend) {
         GL11.glEnable(GL11.GL_BLEND);
      }
      if (scissor) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
      if (depth) {
         GL11.glEnable(GL11.GL_DEPTH_TEST);
      }
      if (stencil) {
         GL11.glEnable(GL11.GL_STENCIL_TEST);
      }
      GpuSections.markNow("hdr.encode", true);
   }

   static final String ENCODE_FRAG = String.join("\n",
         "#version 330",
         "uniform sampler2D src;",
         "uniform vec4 p0; // x UI white / encMax, y peak / encMax, z knee (fraction of peak), w gamma",
         "uniform vec4 p1;",
         "out vec4 o;",
         "void main() {",
         "  vec3 v = max(texelFetch(src, ivec2(gl_FragCoord.xy), 0).rgb, 0.0);",
         "  vec3 n = pow(v, vec3(p0.w)) * p0.x;",
         "  float peak = p0.y;",
         "  float knee = p0.z * peak;",
         "  float m = max(max(n.r, n.g), n.b);",
         "  if (m > knee) {",
         "    float x = (m - knee) / (peak - knee);",
         "    float mc = knee + (peak - knee) * (x / (1.0 + x));",
         "    n *= mc / m;",
         "  }",
         "  o = vec4(n, 1.0);",
         "}");

   // ---- live tuning (dev) ----

   private static long tuneCheckedMs, tuneStamp;
   /** Sweep sets from the tune file: "name key=value ..." per line after a "[sweep]" line. */
   static final Map<String, Tune> SWEEP = new LinkedHashMap<>();

   private static void reloadTune() {
      if (Config.HDR_TUNE.isEmpty()) {
         return;
      }
      long now = System.currentTimeMillis();
      if (now - tuneCheckedMs < 1000) {
         return;
      }
      tuneCheckedMs = now;
      File f = new File(Config.HDR_TUNE);
      if (!f.isFile() || f.lastModified() == tuneStamp) {
         return;
      }
      tuneStamp = f.lastModified();
      try {
         Tune t = defaults();
         SWEEP.clear();
         boolean sweep = false;
         for (String line : Files.readAllLines(f.toPath())) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
               continue;
            }
            if (line.equals("[sweep]")) {
               sweep = true;
               continue;
            }
            if (sweep) {
               String[] parts = line.split("\\s+");
               Tune s = t.copy();
               for (int i = 1; i < parts.length; i++) {
                  String[] kv = parts[i].split("=", 2);
                  s.set(kv[0], Float.parseFloat(kv[1]));
               }
               SWEEP.put(parts[0], s);
            } else {
               for (String part : line.split("\\s+")) {
                  String[] kv = part.split("=", 2);
                  if (kv.length == 2) {
                     t.set(kv[0], Float.parseFloat(kv[1]));
                  }
               }
            }
         }
         tune = t;
         Log.info("hdr: tune reloaded: " + t + (SWEEP.isEmpty() ? "" : ", sweep " + SWEEP.keySet()));
      } catch (Exception e) {
         Log.warn("hdr: bad tune file " + f + ": " + e);
      }
   }

   // ---- frame dumps (dev): ~/Zomboid/pzopt-hdr.req holds a tag; the next frames are written under ~/Zomboid/pzopt-hdr/ ----

   private static final List<String> dumpQueue = new ArrayList<>();
   private static volatile String requested;

   /** Any thread: dump the next frames (every [sweep] set of the tune file, else the current tuning) under {@code tag}. */
   public static void requestDump(String tag) {
      if (active) {
         requested = tag;
      }
   }
   private static int dumpWait;
   private static String dumpTag;
   private static Tune dumpRestore;
   private static long dumpCheckFrame;

   private static long worldUpNs;
   private static int dumpAtNext;
   private static float[] dumpAt;

   /** hdrDumpAt=s1,s2,...: dumps at those seconds after the world is up (any mode; the harness's shot_at only covers bench). */
   private static void scheduledDumps() {
      if (dumpAt == null) {
         String[] parts = Config.HDR_DUMP_AT.isEmpty() ? new String[0] : Config.HDR_DUMP_AT.split(",");
         dumpAt = new float[parts.length];
         for (int i = 0; i < parts.length; i++) {
            dumpAt[i] = Float.parseFloat(parts[i].trim());
         }
      }
      if (dumpAtNext >= dumpAt.length) {
         return;
      }
      if (zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) {
         worldUpNs = 0L;
         return;
      }
      long now = System.nanoTime();
      if (worldUpNs == 0L) {
         worldUpNs = now;
      }
      if ((now - worldUpNs) / 1e9 >= dumpAt[dumpAtNext]) {
         requestDump("t" + (int)dumpAt[dumpAtNext]);
         dumpAtNext++;
      }
   }

   private static void dumpIfRequested() throws IOException {
      scheduledDumps();
      if (dumpTag == null && dumpQueue.isEmpty()) {
         String tag = requested;
         requested = null;
         if (tag == null) {
            if (frames - dumpCheckFrame < 30) {
               return;
            }
            dumpCheckFrame = frames;
            File req = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-hdr.req");
            if (!req.isFile()) {
               return;
            }
            tag = Files.readString(req.toPath()).trim();
            Files.deleteIfExists(req.toPath());
         }
         if (tag.isEmpty()) {
            tag = "f" + frames;
         }
         if (SWEEP.isEmpty()) {
            dumpQueue.add(tag);
         } else {
            dumpRestore = tune;
            for (String name : SWEEP.keySet()) {
               dumpQueue.add(tag + "-" + name);
            }
         }
         Log.info("hdr: dump requested: " + dumpQueue);
      }
      if (dumpTag == null) {
         dumpTag = dumpQueue.remove(0);
         String sweepName = dumpTag.contains("-") ? dumpTag.substring(dumpTag.lastIndexOf('-') + 1) : null;
         if (sweepName != null && SWEEP.containsKey(sweepName)) {
            tune = SWEEP.get(sweepName);
         }
         dumpWait = 3; // let the world composite of the new parameters reach the screen
         return;
      }
      if (--dumpWait > 0) {
         return;
      }
      writeDump(dumpTag);
      dumpTag = null;
      if (dumpQueue.isEmpty() && dumpRestore != null) {
         tune = dumpRestore;
         dumpRestore = null;
      }
   }

   private static int dumpPreFbo, dumpPostFbo;
   private static double dumpHeadroom = 1.0;

   /** HdrMac.present(), render thread, after the EDR encode: the tune file and the frame dumps of the alpha-gain path. */
   static void alphaGainFrame(int preFbo, int postFbo, int w, int h, double headroom) {
      frames++;
      try {
         reloadTune();
         copyW = w;
         copyH = h;
         dumpPreFbo = preFbo;
         dumpPostFbo = postFbo;
         dumpHeadroom = Math.max(1.0, headroom);
         dumpIfRequested();
      } catch (Throwable t) {
         Log.warn("hdr: dump failed: " + t);
      }
   }

   /**
    * Alpha-gain dump (macOS EDR): pre = the 8-bit back buffer, post = the EDR surface Metal presents (1.0 = SDR white),
    * its rows turned bottom-up like GL's so hdrframe.py shows the picture as it reaches the screen. The json's units are
    * % of the SDR white (uiNits 100), there is no absolute nits scale on macOS. Logs which way up the surface is.
    */
   private static void writeAlphaGainDump(String tag) {
      int w = copyW, h = copyH;
      File dir = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-hdr");
      dir.mkdirs();
      java.nio.FloatBuffer pre = BufferUtils.createFloatBuffer(w * h * 3), post = BufferUtils.createFloatBuffer(w * h * 3);
      int prevRead = GL11.glGetInteger(org.lwjgl.opengl.EXTFramebufferBlit.GL_READ_FRAMEBUFFER_BINDING_EXT); // EXT: the macOS context is legacy 2.1
      org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, dumpPreFbo);
      GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_FLOAT, pre);
      org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, dumpPostFbo);
      GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_FLOAT, post);
      org.lwjgl.opengl.EXTFramebufferObject.glBindFramebufferEXT(org.lwjgl.opengl.EXTFramebufferBlit.GL_READ_FRAMEBUFFER_EXT, prevRead);
      double hr = dumpHeadroom;
      String meta = String.format(java.util.Locale.ROOT, "{\"tag\":\"%s\",\"w\":%d,\"h\":%d,\"units\":\"%% of SDR white\",\"encMax\":%.1f,\"encRef\":100.0,\"headroom\":%.3f,\"potentialHeadroom\":%.3f,\"uiNits\":100.0,\"peakNits\":%.1f,\"zoom\":%.4f,\"flash\":%.3f,\"daylight\":%.3f,\"lightReady\":%b,\"tune\":\"%s\"}",
            tag, w, h, hr * 100.0, hr, HdrMac.potentialHeadroom, hr * 100.0, Core.getInstance().getZoom(0), flash, HdrGlint.daylight, HdrLight.ready, tune);
      Thread writer = new Thread(() -> {
         try {
            ByteBuffer preOut = BufferUtils.createByteBuffer(w * h * 3 * 2), postOut = BufferUtils.createByteBuffer(w * h * 3 * 2);
            double[] preRows = new double[h], postRows = new double[h];
            long above = 0;
            float max = 0F;
            for (int y = 0; y < h; y++) {
               int src = (h - 1 - y) * w * 3; // the surface's row 0 is Metal's top row
               double pr = 0, qr = 0;
               for (int i = 0; i < w * 3; i++) {
                  float p = pre.get(y * w * 3 + i), q = post.get(src + i);
                  preOut.putShort((y * w * 3 + i) * 2, Float.floatToFloat16(p));
                  postOut.putShort((y * w * 3 + i) * 2, Float.floatToFloat16((float)(q / hr)));
                  pr += p;
                  qr += q;
                  max = Math.max(max, q);
                  if (q > 1.0F) {
                     above++;
                  }
               }
               preRows[y] = pr;
               postRows[y] = qr;
            }
            double upright = correlation(preRows, postRows, false), flipped = correlation(preRows, postRows, true);
            write(new File(dir, tag + ".pre.f16"), preOut);
            write(new File(dir, tag + ".post.f16"), postOut);
            Files.writeString(new File(dir, tag + ".json").toPath(), meta);
            Log.info(String.format(java.util.Locale.ROOT, "hdr mac: dumped %s %dx%d: headroom %.2f (potential %.2f), max %.2fx SDR white, %.2f %% of channels above SDR white;"
                  + " orientation %s (row profile r = %.3f upright, %.3f flipped)", tag, w, h, hr, HdrMac.potentialHeadroom, max, above * 100.0 / (w * h * 3.0),
                  upright >= flipped ? "upright" : "UPSIDE DOWN", upright, flipped));
         } catch (IOException e) {
            Log.warn("hdr: dump failed: " + e);
         }
      }, "pzopt-hdr-dump");
      writer.setDaemon(true);
      writer.start();
   }

   /** Pearson r of two row profiles (b read bottom-up when flipped). */
   private static double correlation(double[] a, double[] b, boolean flipped) {
      int n = a.length;
      double ma = 0, mb = 0;
      for (int i = 0; i < n; i++) {
         ma += a[i];
         mb += b[flipped ? n - 1 - i : i];
      }
      ma /= n;
      mb /= n;
      double sab = 0, saa = 0, sbb = 0;
      for (int i = 0; i < n; i++) {
         double x = a[i] - ma, y = b[flipped ? n - 1 - i : i] - mb;
         sab += x * y;
         saa += x * x;
         sbb += y * y;
      }
      return saa > 0 && sbb > 0 ? sab / Math.sqrt(saa * sbb) : 0;
   }

   private static void writeDump(String tag) throws IOException {
      if (alphaGain) {
         writeAlphaGainDump(tag);
         return;
      }
      int w = copyW, h = copyH;
      File dir = new File(ZomboidFileSystem.instance.getCacheDir(), "pzopt-hdr");
      dir.mkdirs();
      // pre-encode (the SDR-encoded extended frame, what the game + world expansion drew) and post-encode (ext_linear, 1.0 = encMax)
      ByteBuffer pre = BufferUtils.createByteBuffer(w * h * 3 * 2);
      ByteBuffer post = BufferUtils.createByteBuffer(w * h * 3 * 2);
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      boolean encoded = HdrWayland.described;
      if (encoded) {
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyFbo);
         GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL30.GL_HALF_FLOAT, pre);
      }
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
      GL11.glReadBuffer(GL11.GL_BACK);
      GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL30.GL_HALF_FLOAT, encoded ? post : pre);
      // the world pass's depth over the world rect (render resolution): offline, to fit the floor depth vs screen position
      ByteBuffer depthBuf = null;
      int dw = 0, dh = 0;
      TextureFBO worldFbo = Core.getInstance().getOffscreenBuffer();
      int fboId = worldFbo != null ? FogPass.fboId(worldFbo) : 0;
      if (fboId > 0) {
         int[] r = RenderScale.active() ? RenderScale.scaledRect(0) : new int[] {0, 0, Core.width, Core.height};
         dw = r[2];
         dh = r[3];
         depthBuf = BufferUtils.createByteBuffer(dw * dh * 4);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
         GL11.glReadPixels(r[0], r[1], dw, dh, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depthBuf);
      }
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      final ByteBuffer depthOut = depthBuf;
      String meta = String.format("{\"tag\":\"%s\",\"w\":%d,\"h\":%d,\"depthW\":%d,\"depthH\":%d,\"zoom\":%.4f,\"encMax\":%.1f,\"encRef\":%.1f,\"panelPeak\":%.1f,\"uiNits\":%.1f,\"peakNits\":%.1f,\"flash\":%.3f,\"daylight\":%.3f,\"sunStrength\":%.3f,\"lightReady\":%b,\"lightAmbient\":%.3f,\"lightCounted\":%d,\"lightSeen\":%d,\"lightMaxExcess\":%d,\"bloomReady\":%b,\"tune\":\"%s\"}",
            tag, w, h, dw, dh, Core.getInstance().getZoom(0), HdrWayland.encMax, HdrWayland.encRef, HdrWayland.prefTargetMax, uiNits(tune), peakNits(tune),
            flash, HdrGlint.daylight, HdrGlint.sunStrength, HdrLight.ready, HdrLight.lastAmbient, HdrLight.lastCounted, HdrLight.lastSeen, HdrLight.lastMaxExcess, bloomReady, tune);
      final float decodeScale = (float)(HdrWayland.encRef / HdrWayland.encMax), gamma = tune.gamma;
      Thread writer = new Thread(() -> {
         try {
            if (!encoded) {
               // the compositor's decode of the undescribed surface: gamma 2.2, 1.0 = reference white (post is in encMax units)
               for (int i = 0, n = w * h * 3; i < n; i++) {
                  float v = Math.max(0F, Float.float16ToFloat(pre.getShort(i * 2)));
                  post.putShort(i * 2, Float.floatToFloat16((float)Math.pow(v, gamma) * decodeScale));
               }
            }
            write(new File(dir, tag + ".pre.f16"), pre);
            write(new File(dir, tag + ".post.f16"), post);
            if (depthOut != null && !tag.contains("-") || depthOut != null && tag.endsWith("-sdr")) {
               write(new File(dir, tag + ".depth.f32"), depthOut); // once per request (the sweep's sets share the frame)
            }
            Files.writeString(new File(dir, tag + ".json").toPath(), meta);
            Log.info("hdr: dumped " + tag + " " + w + "x" + h);
         } catch (IOException e) {
            Log.warn("hdr: dump failed: " + e);
         }
      }, "pzopt-hdr-dump");
      writer.setDaemon(true);
      writer.start();
   }

   private static void write(File f, ByteBuffer b) throws IOException {
      try (FileChannel ch = new FileOutputStream(f).getChannel()) {
         b.rewind();
         while (b.hasRemaining()) {
            ch.write(b);
         }
      }
   }
}
