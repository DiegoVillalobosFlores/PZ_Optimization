package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reflex Boost for NVIDIA GPUs ({@code reflexBoost}, 2026-09-24). At a frame cap the GPU is idle most of each frame
 * and the driver lowers its clocks, so the frame it does render takes longer: 4 ms at a 60 fps cap against 1.3 ms at
 * 240 on an RTX 4090, all of it input latency. Reflex's "On + Boost" keeps the clocks up while the game runs; there is
 * no Reflex for OpenGL, but the driver's own NVML library (libnvidia-ml on Linux, nvml.dll on Windows, shipped with
 * the driver) sets the same PowerMizer preference: "prefer maximum performance". A normal user may set it and the
 * driver scopes it to the process that set it: it drops back to the user's setting when the game exits, crashes
 * included. It is held while a world is loaded and released at the menu. Costs power while it is on.
 */
public final class GpuBoost {
   public static final boolean ON = Config.REFLEX_BOOST && Overrides.enabled();
   private static final int PREFER_MAXIMUM_PERFORMANCE = 1;

   private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "pzopt-gpu-boost");
      t.setDaemon(true);
      return t;
   });
   private static volatile boolean wanted;
   private static boolean held, broken, inited;
   private static MethodHandle setMode, getMode;
   private static MemorySegment[] devices = new MemorySegment[0];
   private static int[] previous = new int[0];
   private static final Arena arena = Arena.ofAuto();

   private GpuBoost() {
   }

   /** Game thread, every frame (cheap): boost while a world is loaded. */
   public static void frame(boolean inWorld) {
      if (!ON || broken || inWorld == wanted) {
         return;
      }
      wanted = inWorld;
      worker.execute(GpuBoost::apply);
   }

   private static void apply() {
      try {
         if (!inited) {
            inited = true;
            init();
         }
         if (devices.length == 0) {
            return;
         }
         boolean want = wanted;
         if (want == held) {
            return;
         }
         MemorySegment modes = arena.allocate(12); // nvmlDevicePowerMizerModes_v1_t: currentMode, mode, supported
         for (int i = 0; i < devices.length; i++) {
            if (want) {
               int r = (int)getMode.invokeExact(devices[i], modes);
               previous[i] = r == 0 ? modes.get(ValueLayout.JAVA_INT, 0) : 0;
               modes.set(ValueLayout.JAVA_INT, 4, PREFER_MAXIMUM_PERFORMANCE);
            } else {
               modes.set(ValueLayout.JAVA_INT, 4, previous[i]);
            }
            int r = (int)setMode.invokeExact(devices[i], modes);
            if (r != 0) {
               Log.warn("gpu boost: nvmlDeviceSetPowerMizerMode_v1 on GPU " + i + " returned " + r + "; boost off");
               broken = true;
               return;
            }
         }
         held = want;
         Log.info("gpu boost: " + (want ? "prefer maximum performance on " + devices.length + " GPU(s)" : "released (back to mode " + previous[0] + ")"));
      } catch (Throwable t) {
         broken = true;
         Log.warn("gpu boost: off after " + t);
      }
   }

   private static void init() throws Throwable {
      String os = System.getProperty("os.name", "").toLowerCase();
      String name = os.contains("win") ? "nvml.dll" : os.contains("linux") ? "libnvidia-ml.so.1" : null;
      if (name == null) {
         Log.info("gpu boost: no NVML on this platform");
         return;
      }
      SymbolLookup lib;
      try {
         lib = SymbolLookup.libraryLookup(name, Arena.global());
      } catch (IllegalArgumentException e) {
         Log.info("gpu boost: " + name + " not found (not an NVIDIA driver)");
         return;
      }
      Linker linker = Linker.nativeLinker();
      MethodHandle init = linker.downcallHandle(lib.find("nvmlInit_v2").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
      MethodHandle count = linker.downcallHandle(lib.find("nvmlDeviceGetCount_v2").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      MethodHandle byIndex = linker.downcallHandle(lib.find("nvmlDeviceGetHandleByIndex_v2").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      var set = lib.find("nvmlDeviceSetPowerMizerMode_v1");
      var get = lib.find("nvmlDeviceGetPowerMizerMode_v1");
      if (set.isEmpty() || get.isEmpty()) {
         Log.info("gpu boost: this driver's NVML has no PowerMizer mode (needs a 2025+ driver)");
         return;
      }
      FunctionDescriptor modeFn = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
      setMode = linker.downcallHandle(set.get(), modeFn);
      getMode = linker.downcallHandle(get.get(), modeFn);
      int r = (int)init.invokeExact();
      if (r != 0) {
         Log.info("gpu boost: nvmlInit returned " + r);
         return;
      }
      MemorySegment n = arena.allocate(ValueLayout.JAVA_INT);
      r = (int)count.invokeExact(n);
      int gpus = r == 0 ? n.get(ValueLayout.JAVA_INT, 0) : 0;
      devices = new MemorySegment[gpus];
      previous = new int[gpus];
      for (int i = 0; i < gpus; i++) {
         MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
         r = (int)byIndex.invokeExact(i, out);
         if (r != 0) {
            devices = new MemorySegment[0];
            Log.info("gpu boost: nvmlDeviceGetHandleByIndex(" + i + ") returned " + r);
            return;
         }
         devices[i] = out.get(ValueLayout.ADDRESS, 0);
      }
      Log.info("gpu boost: NVML ready, " + gpus + " GPU(s)");
   }
}
