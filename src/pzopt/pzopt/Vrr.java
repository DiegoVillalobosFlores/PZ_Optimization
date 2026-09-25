package pzopt;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Is the display running variable refresh right now, and what follows from it.
 *
 * Linux: the compositor (KWin, Mutter, gamescope, a bare X server) asks the kernel for variable refresh through the
 * CRTC property VRR_ENABLED; any process in the video group can read it with libdrm, no DRM master needed. A daemon
 * thread polls every active CRTC twice a second (a handful of ioctls) and the connectors' vrr_capable once. That
 * is what the game acts on: the compositor only enables VRR for a fullscreen window on a capable display with its
 * VRR setting on, and none of those is visible from inside GL. Elsewhere (Windows, macOS) the state is unknown and
 * only {@code vrr=on} turns the VRR behaviour on.
 *
 * While VRR is active (or {@code vrr=on}): present pacing {@code auto} becomes {@code gpu} (pzopt.Pacing), and the
 * frame cap stays inside the VRR range ({@code vrrCap}: refresh - refresh^2/3600, 157 fps at 165 Hz, 224 at 240;
 * above the maximum refresh the display falls back to vsync behaviour - queued frames and added latency - or
 * tears). {@code vrr=off} leaves all of it alone.
 */
public final class Vrr {
   public static final String MODE = Config.VRR.toLowerCase(Locale.ROOT);
   private static final boolean FORCED_ON = "on".equals(MODE) || "true".equals(MODE);
   private static final boolean DISABLED = "off".equals(MODE) || "false".equals(MODE);

   private static volatile boolean active;
   private static volatile boolean capable;
   private static volatile double refreshHz;
   private static volatile boolean started;
   private static volatile String source = "none";

   private Vrr() {
   }

   /** VRR behaviour on: the display runs variable refresh (Linux), or the player forced it. */
   public static boolean active() {
      if (!Overrides.enabled() || DISABLED) {
         return false;
      }
      return FORCED_ON || active;
   }

   public static boolean capable() {
      return capable;
   }

   /** Maximum refresh of the display the game is on (the active mode's rate), 0 when unknown. */
   public static double refreshHz() {
      double hz = refreshHz;
      if (hz <= 0.0) {
         try {
            int f = org.lwjglx.opengl.Display.getDesktopDisplayMode().getFrequency();
            hz = f > 0 ? f : 0.0;
         } catch (Throwable ignored) {
         }
      }
      return hz;
   }

   /** Frame cap that keeps frames inside the VRR range: refresh - refresh^2/3600 (0 = no cap known). */
   public static int cap() {
      int forced = Config.VRR_CAP_FPS;
      if (forced > 0) {
         return forced;
      }
      double hz = refreshHz();
      return hz <= 0.0 ? 0 : (int)Math.floor(hz - hz * hz / 3600.0);
   }

   public static String describe() {
      if (!Overrides.enabled() || DISABLED) {
         return "VRR off (vrr=off)";
      }
      String s = active() ? "VRR active" : capable ? "VRR capable, not active" : "VRR not detected";
      return s + " (" + source + (refreshHz() > 0.0 ? String.format(Locale.ROOT, ", %.0f Hz", refreshHz()) : "") + ")";
   }

   /** Overlay suffix for the stats line: what the display does ("" when nothing is known). */
   public static String overlayText() {
      if (MacPresent.active()) {
         return "   " + (MacPresent.variableDisplay() ? I18n.text("overlay.vrrProMotion", "ProMotion via Metal (%1 Hz max)", String.format(Locale.ROOT, "%.0f", MacPresent.maxHz()))
               : I18n.text("overlay.vrrMetal", "Metal present"));
      }
      if (active()) {
         int m = Pacing.effectiveMode();
         String paced = m == Pacing.GPU || m == Pacing.CPU || m == Pacing.GPU_FINISH ? I18n.text("overlay.vrrPaced", ", paced") : "";
         return "   " + I18n.text("overlay.vrrOn", "VRR on (%1 Hz)", String.format(Locale.ROOT, "%.0f", refreshHz())) + paced;
      }
      return capable && !DISABLED ? "   " + I18n.text("overlay.vrrOffCapable", "VRR off (display capable)") : "";
   }

   /** Starts the Linux poller once (from Display creation); no-op elsewhere. */
   public static void start() {
      if (started || !Overrides.enabled() || DISABLED) {
         return;
      }
      started = true;
      if (FORCED_ON) {
         source = "vrr=on";
      }
      if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
         return;
      }
      Thread t = new Thread(Vrr::pollLoop, "pzopt-vrr");
      t.setDaemon(true);
      t.setPriority(Thread.MIN_PRIORITY);
      t.start();
   }

   // --- Linux: libdrm through FFM --------------------------------------------------------------

   private static final long OBJ_CRTC = 0xCCCCCCCCL;
   private static final long OBJ_CONNECTOR = 0xC0C0C0C0L;
   private static MethodHandle open;
   private static MethodHandle getResources;
   private static MethodHandle freeResources;
   private static MethodHandle getCrtc;
   private static MethodHandle freeCrtc;
   private static MethodHandle getProps;
   private static MethodHandle freeProps;
   private static MethodHandle getProp;
   private static MethodHandle freeProp;

   private record Card(int fd, int[] crtcs, int[] connectors) {
   }

   private static final Map<Long, String> propNames = new HashMap<>();

   private static void pollLoop() {
      try {
         Linker linker = Linker.nativeLinker();
         SymbolLookup drm = SymbolLookup.libraryLookup("libdrm.so.2", Arena.global());
         SymbolLookup libc = linker.defaultLookup();
         ValueLayout.OfInt I = ValueLayout.JAVA_INT;
         ValueLayout P = ValueLayout.ADDRESS;
         open = linker.downcallHandle(libc.find("open").orElseThrow(), FunctionDescriptor.of(I, P, I));
         getResources = linker.downcallHandle(drm.find("drmModeGetResources").orElseThrow(), FunctionDescriptor.of(P, I));
         freeResources = linker.downcallHandle(drm.find("drmModeFreeResources").orElseThrow(), FunctionDescriptor.ofVoid(P));
         getCrtc = linker.downcallHandle(drm.find("drmModeGetCrtc").orElseThrow(), FunctionDescriptor.of(P, I, I));
         freeCrtc = linker.downcallHandle(drm.find("drmModeFreeCrtc").orElseThrow(), FunctionDescriptor.ofVoid(P));
         getProps = linker.downcallHandle(drm.find("drmModeObjectGetProperties").orElseThrow(), FunctionDescriptor.of(P, I, I, I));
         freeProps = linker.downcallHandle(drm.find("drmModeFreeObjectProperties").orElseThrow(), FunctionDescriptor.ofVoid(P));
         getProp = linker.downcallHandle(drm.find("drmModeGetProperty").orElseThrow(), FunctionDescriptor.of(P, I, I));
         freeProp = linker.downcallHandle(drm.find("drmModeFreeProperty").orElseThrow(), FunctionDescriptor.ofVoid(P));
         List<Card> cards = openCards();
         if (cards.isEmpty()) {
            Log.info("vrr: no readable DRM card, state unknown");
            return;
         }
         boolean cap = false;
         for (Card c : cards) {
            for (int con : c.connectors) {
               Long v = props(c.fd, con, OBJ_CONNECTOR).get("vrr_capable");
               cap |= v != null && v == 1L;
            }
         }
         capable = cap;
         source = "drm";
         boolean last = false;
         boolean first = true;
         while (true) {
            boolean on = false;
            double hz = 0.0;
            for (Card c : cards) {
               for (int crtc : c.crtcs) {
                  double modeHz = crtcHz(c.fd, crtc);
                  if (modeHz <= 0.0) {
                     continue;
                  }
                  Long v = props(c.fd, crtc, OBJ_CRTC).get("VRR_ENABLED");
                  if (v != null && v == 1L) {
                     on = true;
                     hz = Math.max(hz, modeHz);
                  } else if (!on) {
                     hz = Math.max(hz, modeHz);
                  }
               }
            }
            refreshHz = hz;
            active = on;
            if (first || on != last) {
               Log.info("vrr: " + describe() + (on ? ", cap " + cap() + " fps, present pacing " + Pacing.describeMode() : ""));
               first = false;
               last = on;
            }
            Thread.sleep(500L);
         }
      } catch (InterruptedException ignored) {
      } catch (Throwable t) {
         Log.warn("vrr: DRM poll stopped: " + t);
      }
   }

   private static List<Card> openCards() throws Throwable {
      List<Card> out = new ArrayList<>();
      File[] devs = new File("/dev/dri").listFiles((d, n) -> n.matches("card\\d+"));
      if (devs == null) {
         return out;
      }
      try (Arena a = Arena.ofConfined()) {
         for (File dev : devs) {
            int fd = (int)open.invokeExact(a.allocateFrom(dev.getPath()), 02000002); // O_RDWR | O_CLOEXEC
            if (fd < 0) {
               continue;
            }
            MemorySegment res = ((MemorySegment)getResources.invokeExact(fd)).reinterpret(64);
            if (res.address() == 0L) {
               continue;
            }
            int[] crtcs = ints(res.get(ValueLayout.ADDRESS, 24), res.get(ValueLayout.JAVA_INT, 16));
            int[] conns = ints(res.get(ValueLayout.ADDRESS, 40), res.get(ValueLayout.JAVA_INT, 32));
            freeResources.invokeExact(res);
            out.add(new Card(fd, crtcs, conns));
         }
      }
      return out;
   }

   private static int[] ints(MemorySegment p, int n) {
      MemorySegment s = p.reinterpret(4L * n);
      int[] r = new int[n];
      for (int i = 0; i < n; i++) {
         r[i] = s.getAtIndex(ValueLayout.JAVA_INT, i);
      }
      return r;
   }

   /** Refresh of the CRTC's mode, 0 when it drives none. drmModeCrtc: mode_valid @24, drmModeModeInfo @28 (clock, htotal @+10, vtotal @+20). */
   private static double crtcHz(int fd, int crtc) throws Throwable {
      MemorySegment c = ((MemorySegment)getCrtc.invokeExact(fd, crtc)).reinterpret(96);
      if (c.address() == 0L) {
         return 0.0;
      }
      try {
         if (c.get(ValueLayout.JAVA_INT, 24) == 0) {
            return 0.0;
         }
         long clock = c.get(ValueLayout.JAVA_INT, 28) & 0xFFFFFFFFL;
         int htotal = c.get(ValueLayout.JAVA_SHORT, 38) & 0xFFFF;
         int vtotal = c.get(ValueLayout.JAVA_SHORT, 48) & 0xFFFF;
         return htotal == 0 || vtotal == 0 ? 0.0 : clock * 1000.0 / ((double)htotal * vtotal);
      } finally {
         freeCrtc.invokeExact(c);
      }
   }

   /** {name: value} of a DRM object's properties; drmModeObjectProperties: count @0, props* @8, prop_values* @16. */
   private static Map<String, Long> props(int fd, int obj, long type) throws Throwable {
      Map<String, Long> out = new HashMap<>();
      MemorySegment p = ((MemorySegment)getProps.invokeExact(fd, obj, (int)type)).reinterpret(24);
      if (p.address() == 0L) {
         return out;
      }
      try {
         int n = p.get(ValueLayout.JAVA_INT, 0);
         MemorySegment ids = p.get(ValueLayout.ADDRESS, 8).reinterpret(4L * n);
         MemorySegment vals = p.get(ValueLayout.ADDRESS, 16).reinterpret(8L * n);
         for (int i = 0; i < n; i++) {
            int id = ids.getAtIndex(ValueLayout.JAVA_INT, i);
            long key = ((long)fd << 32) | (id & 0xFFFFFFFFL);
            String name = propNames.get(key);
            if (name == null) {
               MemorySegment pr = ((MemorySegment)getProp.invokeExact(fd, id)).reinterpret(40);
               name = pr.address() == 0L ? "" : pr.asSlice(8, 32).getString(0);
               if (pr.address() != 0L) {
                  freeProp.invokeExact(pr);
               }
               propNames.put(key, name);
            }
            out.put(name, vals.getAtIndex(ValueLayout.JAVA_LONG, i));
         }
      } finally {
         freeProps.invokeExact(p);
      }
      return out;
   }
}
