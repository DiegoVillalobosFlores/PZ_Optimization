package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import zombie.ZomboidFileSystem;

/**
 * Power draw for the in-game profiler (2026-09-24): the overlay's power line and, while the frame log is on,
 * {@code Zomboid/pzopt-power.out}. Sampled by the overlay's utilization thread ({@link #sample}, every 0.5 s),
 * never on the game thread. Sources, each read only where the user may read it:
 * <ul>
 * <li>CPU: the RAPL package counters ({@code /sys/class/powercap/intel-rapl:N/energy_uj}, Linux, Intel and AMD Zen),
 *     root-only on a stock kernel (the PLATYPUS side channel); {@code scripts/power-access.sh} makes them readable.</li>
 * <li>GPU: NVIDIA board power through the driver's NVML (energy counter where the GPU has one, else the power
 *     reading; Linux and Windows), or an AMD discrete card's hwmon power (Linux).</li>
 * <li>SoC: an AMD APU's socket power (hwmon PPT: CPU + iGPU), when there is no discrete GPU.</li>
 * <li>Battery: the discharge rate while on battery (Linux), which is the whole machine, the display included.</li>
 * </ul>
 * The total is the battery on battery, else the CPU package (or the APU socket) plus the discrete GPU; unknown when
 * the CPU side is. The same rules as {@code harness/sysmon.sh}'s cpu_w / soc_w / total_w columns, so a run's
 * sysmon.csv and its pzopt-power.out agree. macOS: harness/macpower.py covers runs; the game reads nothing there.
 */
public final class Power {
   /** Watts over the last interval; NaN = no reading. */
   static volatile float cpuW = Float.NaN, gpuW = Float.NaN, socW = Float.NaN, batW = Float.NaN, totalW = Float.NaN;
   /** Why the CPU figure is missing, for the overlay line ("" when there is one). */
   private static String cpuMissing = "";
   private static boolean discovered;

   // RAPL package domains
   private static final List<Path> rapl = new ArrayList<>();
   private static long[] raplPrev, raplMax;
   // AMD: discrete card's / APU's hwmon power file (microwatts)
   private static Path amdGpuPower, amdSocPower;
   // battery
   private static Path battery;
   // NVML
   private static MethodHandle nvmlEnergy, nvmlPower;
   private static MemorySegment[] nvmlDevices = new MemorySegment[0];
   private static long[] nvmlPrevMj;
   private static MemorySegment nvmlOut;
   private static final Arena arena = Arena.ofAuto();
   private static long prevNs;

   // pzopt-power.out
   private static BufferedWriter log;
   private static boolean logFailed;

   private Power() {
   }

   /** The overlay's sampler thread, every 0.5 s while sampling. {@code fps} = frames presented in the last second. */
   static void sample(float fps, boolean logRows) {
      if (!discovered) {
         discovered = true;
         try {
            discover();
         } catch (Throwable t) {
            Log.warn("power: sensor discovery failed: " + t);
         }
      }
      long now = System.nanoTime();
      double dt = prevNs == 0L ? 0.0 : (now - prevNs) / 1e9;
      prevNs = now;
      float cpu = Float.NaN, gpu = Float.NaN, soc = Float.NaN, bat = Float.NaN;
      try {
         cpu = readRapl(dt);
         gpu = readNvml(dt);
         if (amdGpuPower != null) {
            gpu = nanSum(gpu, microwatts(amdGpuPower));
         }
         if (amdSocPower != null) {
            soc = microwatts(amdSocPower);
         }
         bat = readBattery();
      } catch (Throwable t) {
         Log.warn("power: sampling stopped: " + t);
         rapl.clear();
         nvmlDevices = new MemorySegment[0];
         amdGpuPower = amdSocPower = battery = null;
      }
      float total = bat;
      if (Float.isNaN(total)) {
         float base = Float.isNaN(cpu) ? soc : cpu;
         total = Float.isNaN(base) ? Float.NaN : Float.isNaN(gpu) ? base : base + gpu;
      }
      cpuW = cpu;
      gpuW = gpu;
      socW = soc;
      batW = bat;
      totalW = total;
      if (logRows && dt > 0.0) {
         logRow(fps);
      }
   }

   /** The overlay's power line, e.g. "power 245 W: CPU 60 W + GPU 185 W   1.020 J/frame". */
   static String overlayLine(float fps) {
      if (!discovered) {
         return I18n.text("overlay.powerReading", "power: reading sensors");
      }
      float total = totalW;
      List<String> parts = new ArrayList<>();
      if (!Float.isNaN(cpuW)) {
         parts.add(String.format(Locale.ROOT, "CPU %.0f W", cpuW));
      } else if (!Float.isNaN(socW)) {
         parts.add(String.format(Locale.ROOT, "SoC %.1f W", socW));
      }
      if (!Float.isNaN(gpuW)) {
         parts.add(String.format(Locale.ROOT, "GPU %.0f W", gpuW));
      }
      String body;
      if (!Float.isNaN(batW)) {
         body = onBattery(String.format(Locale.ROOT, "%.1f", batW)) + (parts.isEmpty() ? "" : ": " + String.join(", ", parts));
      } else if (!Float.isNaN(total)) {
         body = String.format(Locale.ROOT, "%.0f W: ", total) + String.join(" + ", parts);
      } else if (!parts.isEmpty()) {
         body = String.join(", ", parts);
      } else if (!rapl.isEmpty() || nvmlDevices.length > 0 || amdGpuPower != null || amdSocPower != null) {
         return I18n.text("overlay.powerReading", "power: reading sensors"); // the energy counters need two samples
      } else {
         return battery != null ? I18n.text("overlay.powerMainsOnly", "power: n/a on mains (only the battery reports power here)")
               : I18n.text("overlay.powerNoSensor", "power: no readable sensor on this machine");
      }
      StringBuilder b = new StringBuilder(I18n.text("overlay.power", "power %1", body));
      if (!Float.isNaN(total) && fps > 0f) {
         b.append(String.format(Locale.ROOT, "   %.3f J/frame", total / fps));
      }
      if (Float.isNaN(cpuW) && Float.isNaN(socW) && !cpuMissing.isEmpty() && Float.isNaN(batW)) {
         b.append("   ").append(I18n.text("overlay.powerCpuMissing", "CPU n/a (%1)", cpuMissingText()));
      }
      return b.toString();
   }

   private static String onBattery(String watts) {
      return I18n.text("overlay.powerOnBattery", "%1 W on battery (whole machine)", watts);
   }

   /** {@link #cpuMissing} (English, it is logged too) in the game's language. */
   private static String cpuMissingText() {
      switch (cpuMissing) {
         case "no CPU power counter on Windows": return I18n.text("overlay.powerNoCpuCounterWindows", "no CPU power counter on Windows");
         case "RAPL counters are root-only": return I18n.text("overlay.powerRaplRootOnly", "RAPL counters are root-only");
         case "no RAPL counters": return I18n.text("overlay.powerNoRapl", "no RAPL counters");
         default: return cpuMissing;
      }
   }

   /** The widest line {@link #overlayLine} makes, for the overlay's steady panel width. */
   static String template() {
      return I18n.text("overlay.power", "power %1", onBattery("88.8") + ": SoC 88.8 W, GPU 888 W") + "   8.888 J/frame";
   }

   private static void discover() {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      boolean linux = os.contains("linux");
      if (linux) {
         discoverRapl();
         discoverBattery();
      } else {
         cpuMissing = os.contains("win") ? "no CPU power counter on Windows" : "";
      }
      if (os.contains("win") || linux) {
         discoverNvml(os.contains("win") ? "nvml.dll" : "libnvidia-ml.so.1");
      }
      if (linux) {
         discoverAmd();
      }
      Log.info("power: CPU " + (rapl.isEmpty() ? "n/a" + (cpuMissing.isEmpty() ? "" : " (" + cpuMissing + ")") : "RAPL x" + rapl.size())
            + ", GPU " + (nvmlDevices.length > 0 ? "NVML x" + nvmlDevices.length : amdGpuPower != null ? amdGpuPower : "n/a")
            + (amdSocPower != null ? ", APU socket " + amdSocPower : "") + (battery != null ? ", battery " + battery : ""));
   }

   private static void discoverRapl() {
      File[] dirs = new File("/sys/class/powercap").listFiles();
      if (dirs == null) {
         return;
      }
      boolean seen = false;
      for (File d : dirs) {
         if (!d.getName().matches("intel-rapl:\\d+")) {
            continue;
         }
         String name = text(d.toPath().resolve("name"));
         if (name == null || !name.startsWith("package")) {
            continue;
         }
         seen = true;
         Path e = d.toPath().resolve("energy_uj");
         if (Files.isReadable(e) && text(e) != null) {
            rapl.add(e);
         }
      }
      if (rapl.isEmpty()) {
         cpuMissing = seen ? "RAPL counters are root-only" : "no RAPL counters";
         return;
      }
      raplPrev = new long[rapl.size()];
      raplMax = new long[rapl.size()];
      for (int i = 0; i < rapl.size(); i++) {
         raplPrev[i] = -1L;
         String m = text(rapl.get(i).resolveSibling("max_energy_range_uj"));
         raplMax[i] = m == null ? 0L : Long.parseLong(m);
      }
   }

   private static float readRapl(double dt) {
      if (rapl.isEmpty()) {
         return Float.NaN;
      }
      long uj = 0L;
      boolean ok = dt > 0.0;
      for (int i = 0; i < rapl.size(); i++) {
         String s = text(rapl.get(i));
         if (s == null) {
            return Float.NaN;
         }
         long e = Long.parseLong(s);
         if (raplPrev[i] < 0L) {
            ok = false;
         } else {
            long d = e - raplPrev[i];
            uj += d < 0L ? d + raplMax[i] : d;
         }
         raplPrev[i] = e;
      }
      return ok ? (float)(uj / 1e6 / dt) : Float.NaN;
   }

   /** amdgpu cards: gpu_metrics format 1 = discrete (its board power), 2 / 3 = APU (the socket). */
   private static void discoverAmd() {
      File[] cards = new File("/sys/class/drm").listFiles();
      if (cards == null) {
         return;
      }
      for (File c : cards) {
         if (!c.getName().matches("card\\d+")) {
            continue;
         }
         Path dev = c.toPath().resolve("device");
         File[] hw = dev.resolve("hwmon").toFile().listFiles();
         if (hw == null || hw.length == 0 || !"amdgpu".equals(text(hw[0].toPath().resolve("name")))) {
            continue;
         }
         Path p = hw[0].toPath().resolve("power1_average");
         if (!Files.isReadable(p)) {
            p = hw[0].toPath().resolve("power1_input");
         }
         if (!Files.isReadable(p)) {
            continue;
         }
         int format = 0;
         try {
            byte[] h = Files.readAllBytes(dev.resolve("gpu_metrics"));
            format = h.length > 2 ? h[2] : 0;
         } catch (IOException | RuntimeException ignored) {
         }
         if (format == 1 && amdGpuPower == null) {
            amdGpuPower = p;
         } else if (format >= 2 && amdSocPower == null) {
            amdSocPower = p;
         }
      }
      // a desktop CPU's idle iGPU reports its own rail under the same name, not the socket: only an APU without a
      // discrete GPU is the machine's SoC
      if (amdGpuPower != null || nvmlDevices.length > 0) {
         amdSocPower = null;
      }
   }

   private static void discoverBattery() {
      File[] ps = new File("/sys/class/power_supply").listFiles();
      if (ps == null) {
         return;
      }
      for (File b : ps) {
         Path p = b.toPath();
         if ("Battery".equals(text(p.resolve("type"))) && (Files.isReadable(p.resolve("power_now")) || Files.isReadable(p.resolve("current_now")))) {
            battery = p;
            return;
         }
      }
   }

   private static float readBattery() {
      if (battery == null || !"Discharging".equals(text(battery.resolve("status")))) {
         return Float.NaN;
      }
      String pw = text(battery.resolve("power_now"));
      if (pw != null) {
         return Long.parseLong(pw) / 1e6f;
      }
      String i = text(battery.resolve("current_now")), v = text(battery.resolve("voltage_now"));
      return i == null || v == null ? Float.NaN : (float)(Long.parseLong(i) * (double)Long.parseLong(v) / 1e12);
   }

   private static void discoverNvml(String name) {
      SymbolLookup lib;
      try {
         lib = SymbolLookup.libraryLookup(name, Arena.global());
      } catch (IllegalArgumentException e) {
         return; // not an NVIDIA driver
      }
      try {
         Linker linker = Linker.nativeLinker();
         MethodHandle init = linker.downcallHandle(lib.find("nvmlInit_v2").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
         MethodHandle count = linker.downcallHandle(lib.find("nvmlDeviceGetCount_v2").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
         MethodHandle byIndex = linker.downcallHandle(lib.find("nvmlDeviceGetHandleByIndex_v2").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
         FunctionDescriptor read = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
         nvmlPower = linker.downcallHandle(lib.find("nvmlDeviceGetPowerUsage").orElseThrow(), read);
         var energy = lib.find("nvmlDeviceGetTotalEnergyConsumption");
         if ((int)init.invokeExact() != 0) {
            return;
         }
         MemorySegment n = arena.allocate(ValueLayout.JAVA_INT);
         int gpus = (int)count.invokeExact(n) == 0 ? n.get(ValueLayout.JAVA_INT, 0) : 0;
         MemorySegment[] devs = new MemorySegment[gpus];
         for (int i = 0; i < gpus; i++) {
            MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
            if ((int)byIndex.invokeExact(i, out) != 0) {
               return;
            }
            devs[i] = out.get(ValueLayout.ADDRESS, 0);
         }
         nvmlOut = arena.allocate(ValueLayout.JAVA_LONG);
         nvmlPrevMj = new long[gpus];
         if (energy.isPresent()) {
            MethodHandle h = linker.downcallHandle(energy.get(), read);
            boolean all = true;
            for (int i = 0; i < gpus; i++) { // Volta and newer; older GPUs fall back to the power reading
               all &= (int)h.invokeExact(devs[i], nvmlOut) == 0;
               nvmlPrevMj[i] = nvmlOut.get(ValueLayout.JAVA_LONG, 0);
            }
            if (all) {
               nvmlEnergy = h;
            }
         }
         nvmlDevices = devs;
      } catch (Throwable t) {
         Log.info("power: NVML unusable: " + t);
         nvmlDevices = new MemorySegment[0];
      }
   }

   private static float readNvml(double dt) throws Throwable {
      if (nvmlDevices.length == 0) {
         return Float.NaN;
      }
      double w = 0.0;
      for (int i = 0; i < nvmlDevices.length; i++) {
         if (nvmlEnergy != null) {
            if ((int)nvmlEnergy.invokeExact(nvmlDevices[i], nvmlOut) != 0) {
               return Float.NaN;
            }
            long mj = nvmlOut.get(ValueLayout.JAVA_LONG, 0);
            w += dt > 0.0 ? (mj - nvmlPrevMj[i]) / 1e3 / dt : Double.NaN;
            nvmlPrevMj[i] = mj;
         } else {
            nvmlOut.set(ValueLayout.JAVA_LONG, 0, 0L);
            if ((int)nvmlPower.invokeExact(nvmlDevices[i], nvmlOut) != 0) {
               return Float.NaN;
            }
            w += nvmlOut.get(ValueLayout.JAVA_INT, 0) / 1e3;
         }
      }
      return (float)w;
   }

   private static void logRow(float fps) {
      if (logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new BufferedWriter(new FileWriter(new File(dir, "pzopt-power.out"), false), 1 << 12);
            log.write("epoch_ms,cpu_w,gpu_w,soc_w,bat_w,total_w,fps\n");
         }
         log.write(System.currentTimeMillis() + "," + f(cpuW) + "," + f(gpuW) + "," + f(socW) + "," + f(batW) + "," + f(totalW)
               + "," + String.format(Locale.ROOT, "%.0f", fps) + "\n");
         log.flush(); // two rows a second: flushing each keeps the live view current
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("power: log stopped: " + e);
      }
   }

   private static String f(float w) {
      return Float.isNaN(w) ? "" : String.format(Locale.ROOT, "%.2f", w);
   }

   private static float microwatts(Path p) {
      String s = text(p);
      return s == null ? Float.NaN : Long.parseLong(s) / 1e6f;
   }

   private static float nanSum(float a, float b) {
      return Float.isNaN(a) ? b : Float.isNaN(b) ? a : a + b;
   }

   private static String text(Path p) {
      try {
         return Files.readString(p).trim();
      } catch (IOException | RuntimeException e) {
         return null;
      }
   }
}
