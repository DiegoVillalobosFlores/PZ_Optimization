package pzopt;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * gcMode (2026-09-23, maintainer's decision: G1 by default): which garbage collector the next launch uses. The game's
 * launcher JSON (ProjectZomboid64.json) starts the JVM with ZGC; on the 4-core Dell G1 was +11 % fps with half the frames
 * over 100 ms (ZGC's concurrent threads compete with the game on few cores). "stock" leaves the JSON alone and undoes our
 * switch, "g1" switches (the default), "auto" switches only on machines with {@code gcG1Cores} cores or fewer.
 *
 * The edit replaces -XX:+UseZGC with -XX:+UseG1GC in the top-level and per-platform vmArgs, adds -XX:MaxGCPauseMillis
 * when {@code gcPauseMs} > 0, and the marker -Dpzopt.gc=g1 (also listing the pause flag it added, -Dpzopt.gc=g1,pause),
 * which is how this class, scripts/pzopt.sh and the installers recognise and undo it. It takes effect on the next
 * launch. Harness runs own the JSON (run.sh --gc) and are left alone. Backed up once and written atomically, as AotCache.
 * The macOS app bundle (Info.plist) is not changed.
 */
public final class GcChoice {
   static final String MARKER = "-Dpzopt.gc=g1";
   static final String JIT_MARKER = "-Dpzopt.jit=steady";
   /** jitSteady: the flags that follow JIT_MARKER; the undo removes every argument with one of these prefixes. */
   static final String[] JIT_FLAGS = {"-XX:PerMethodTrapLimit=0", "-XX:PerBytecodeTrapLimit=0"};
   static final String MARKER_PAUSE = "-Dpzopt.gc=g1,pause";
   private static final String ZGC = "-XX:+UseZGC";
   private static final String G1 = "-XX:+UseG1GC";
   private static final String PAUSE = "-XX:MaxGCPauseMillis=";

   private GcChoice() {
   }

   public static boolean wantG1() {
      String m = Config.GC_MODE;
      if (m.equals("g1")) {
         return true;
      }
      return m.equals("auto") && Runtime.getRuntime().availableProcessors() <= Config.GC_G1_CORES;
   }

   /** From boot, on AotCache's daemon thread (the other launcher-JSON writer), after its own step. */
   static void step() {
      if (HarnessFlags.get("mode") != null) {
         return; // run.sh writes the launcher JSON for every run (--gc)
      }
      try {
         Path game = Path.of(System.getProperty("user.dir"));
         Path json = game.resolve("ProjectZomboid64.json");
         if (!Files.isRegularFile(json)) {
            return;
         }
         JSONObject j = new JSONObject(Files.readString(json, StandardCharsets.UTF_8));
         String before = j.toString();
         boolean want = Overrides.enabled() && wantG1();
         toStock(j); // from a clean stock form, so a changed gcPauseMs or mode is applied exactly
         if (want) {
            toG1(j, Config.GC_PAUSE_MS);
         }
         jitToStock(j);
         if (Overrides.enabled() && Config.JIT_STEADY) {
            jitToSteady(j);
         }
         boolean changed = !j.toString().equals(before);
         Log.info("gc: running " + currentGc() + "; gcMode=" + Config.GC_MODE + " gcPauseMs=" + Config.GC_PAUSE_MS + " ("
               + Runtime.getRuntime().availableProcessors() + " cores) -> next launch " + (want ? "G1" : "the launcher's own collector")
               + (changed ? " (launcher JSON updated)" : ""));
         if (changed) {
            save(game, j);
         }
      } catch (Throwable e) {
         Log.warn("gc: " + e);
      }
   }

   private static String currentGc() {
      StringBuilder sb = new StringBuilder();
      for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
         sb.append(sb.length() > 0 ? ", " : "").append(b.getName());
      }
      return sb.toString();
   }

   /** ZGC -> G1 (+ pause target) + marker, in every vmArgs array that selects ZGC. */
   static boolean toG1(JSONObject j, int pauseMs) {
      boolean[] changed = {false};
      forEachVmArgs(j, args -> {
         int z = indexOf(args, ZGC);
         if (z < 0) {
            return;
         }
         args.put(z, G1);
         if (pauseMs > 0 && prefixIndex(args, PAUSE) < 0) {
            args.put(PAUSE + pauseMs);
            args.put(MARKER_PAUSE);
         } else {
            args.put(MARKER);
         }
         changed[0] = true;
      });
      return changed[0];
   }

   /** jitSteady: the marker and the flags, in every vmArgs array (the flags a user set himself stay: we only add ours). */
   static void jitToSteady(JSONObject j) {
      forEachVmArgs(j, args -> {
         if (indexOf(args, JIT_MARKER) >= 0) {
            return;
         }
         for (String f : JIT_FLAGS) {
            if (prefixIndex(args, f.substring(0, f.indexOf('=') + 1)) >= 0) {
               return; // the player tunes this flag himself
            }
         }
         args.put(JIT_MARKER);
         for (String f : JIT_FLAGS) {
            args.put(f);
         }
      });
   }

   /** Undo jitToSteady where our marker is. */
   static void jitToStock(JSONObject j) {
      forEachVmArgs(j, args -> {
         if (indexOf(args, JIT_MARKER) < 0) {
            return;
         }
         for (int i = args.length() - 1; i >= 0; i--) {
            String a = args.optString(i);
            boolean ours = a.equals(JIT_MARKER);
            for (String f : JIT_FLAGS) {
               ours |= a.startsWith(f.substring(0, f.indexOf('=') + 1));
            }
            if (ours) {
               args.remove(i);
            }
         }
      });
   }

   /** Undo toG1 where one of our markers is. */
   static boolean toStock(JSONObject j) {
      boolean[] changed = {false};
      forEachVmArgs(j, args -> {
         int m = indexOf(args, MARKER);
         int mp = indexOf(args, MARKER_PAUSE);
         if (m < 0 && mp < 0) {
            return;
         }
         if (mp >= 0) {
            int p = prefixIndex(args, PAUSE);
            if (p >= 0) {
               args.remove(p);
            }
         }
         for (int i = args.length() - 1; i >= 0; i--) {
            String a = args.optString(i);
            if (a.equals(MARKER) || a.equals(MARKER_PAUSE)) {
               args.remove(i);
            }
         }
         int g = indexOf(args, G1);
         if (g >= 0) {
            args.put(g, ZGC);
         }
         changed[0] = true;
      });
      return changed[0];
   }

   private static int indexOf(JSONArray a, String s) {
      for (int i = 0; i < a.length(); i++) {
         if (s.equals(a.optString(i))) {
            return i;
         }
      }
      return -1;
   }

   private static int prefixIndex(JSONArray a, String prefix) {
      for (int i = 0; i < a.length(); i++) {
         if (a.optString(i).startsWith(prefix)) {
            return i;
         }
      }
      return -1;
   }

   /** The top-level vmArgs and every per-platform section's ("windows": {"vmArgs": ...}). */
   private static void forEachVmArgs(JSONObject j, java.util.function.Consumer<JSONArray> f) {
      JSONArray top = j.optJSONArray("vmArgs");
      if (top != null) {
         f.accept(top);
      }
      for (String k : j.keySet()) {
         JSONObject sec = j.optJSONObject(k);
         JSONArray a = sec == null ? null : sec.optJSONArray("vmArgs");
         if (a != null) {
            f.accept(a);
         }
      }
   }

   private static void save(Path game, JSONObject j) throws java.io.IOException {
      Path json = game.resolve("ProjectZomboid64.json");
      Path backup = game.resolve("ProjectZomboid64.json.pzopt-backup");
      if (!Files.exists(backup)) {
         Files.copy(json, backup);
      }
      Path tmp = game.resolve("ProjectZomboid64.json.pzopt-tmp");
      Files.writeString(tmp, j.toString(1) + "\n", StandardCharsets.UTF_8);
      JSONObject check = new JSONObject(Files.readString(tmp, StandardCharsets.UTF_8));
      if (!check.has("mainClass") || check.getJSONArray("classpath").length() == 0 || check.getJSONArray("vmArgs").length() == 0) {
         Files.deleteIfExists(tmp);
         throw new java.io.IOException("launcher JSON check failed; left unchanged");
      }
      Files.move(tmp, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
   }
}
