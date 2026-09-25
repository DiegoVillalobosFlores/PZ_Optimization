// Folds async-profiler samples (profiler.WallClockSample / jdk.ExecutionSample) of one thread inside time windows.
//
//   java harness/jfr-windows.java <asprof.jfr> <windows.txt> <thread-name> [wall|cpu] [depth] [top]
//
// windows.txt: one window per line, "<tag> <start_epoch_ns> <end_epoch_ns>" (tags group windows, e.g. "slow" / "ok").
// Prints, per tag: the samples that fell in its windows, then the most frequent stacks cut to <depth> frames from the
// leaf (JDK / libc frames skipped), then the most frequent "anchor" frames (any frame of the stack, counted once per
// sample) so a phase shows even when its leaves are spread. One pass over the recording; used by harness/frame-causes.py.
import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;

public class JfrWindows {
   static final boolean JAVA_ONLY = !"0".equals(System.getenv("JFR_JAVA_ONLY"));
   public static void main(String[] a) throws Exception {
      Path jfr = Path.of(a[0]);
      String thread = a[2];
      String kind = a.length > 3 ? a[3] : "wall";
      int depth = a.length > 4 ? Integer.parseInt(a[4]) : 4;
      int top = a.length > 5 ? Integer.parseInt(a[5]) : 25;
      String event = kind.equals("cpu") ? "jdk.ExecutionSample" : "profiler.WallClockSample";
      Map<String, List<long[]>> byTag = new TreeMap<>();
      for (String l : Files.readAllLines(Path.of(a[1]))) {
         String[] p = l.trim().split("\\s+");
         if (p.length < 3) continue;
         byTag.computeIfAbsent(p[0], k -> new ArrayList<>()).add(new long[]{Long.parseLong(p[1]), Long.parseLong(p[2])});
      }
      Map<String, long[][]> sorted = new HashMap<>();
      for (var e : byTag.entrySet()) {
         long[][] w = e.getValue().toArray(new long[0][]);
         Arrays.sort(w, Comparator.comparingLong(x -> x[0]));
         sorted.put(e.getKey(), w);
      }
      Map<String, Map<String, Integer>> stacks = new HashMap<>(), anchors = new HashMap<>();
      Map<String, Integer> totals = new HashMap<>();
      try (RecordingFile rf = new RecordingFile(jfr)) {
         while (rf.hasMoreEvents()) {
            RecordedEvent ev = rf.readEvent();
            if (!ev.getEventType().getName().equals(event)) continue;
            RecordedThread t = ev.hasField("sampledThread") ? ev.getThread("sampledThread") : ev.getThread();
            if (t == null) continue;
            // native threads inherit their creator's OS name (Bink's and FMOD's workers are "MainThread" too): a Java thread
            // name is matched only against Java threads
            String tn = t.getJavaName() != null ? t.getJavaName() : (JAVA_ONLY ? null : t.getOSName());
            if (tn == null || !tn.equals(thread)) continue;
            java.time.Instant st = ev.getStartTime();
            long ns = st.getEpochSecond() * 1_000_000_000L + st.getNano();
            for (var e : sorted.entrySet()) {
               if (!inside(e.getValue(), ns)) continue;
               String tag = e.getKey();
               totals.merge(tag, 1, Integer::sum);
               RecordedStackTrace s = ev.getStackTrace();
               List<String> fr = new ArrayList<>();
               if (s != null) {
                  for (RecordedFrame f : s.getFrames()) {
                     RecordedMethod m = f.getMethod();
                     String n = m.getType() == null ? m.getName() : m.getType().getName() + "." + m.getName();
                     fr.add(n);
                  }
               }
               StringBuilder b = new StringBuilder();
               int k = 0;
               for (String n : fr) {
                  if (skip(n)) continue;
                  if (k > 0) b.append(" < ");
                  b.append(shortName(n));
                  if (++k >= depth) break;
               }
               stacks.computeIfAbsent(tag, x -> new HashMap<>()).merge(b.toString(), 1, Integer::sum);
               Set<String> seen = new HashSet<>();
               for (String n : fr) {
                  if (skip(n)) continue;
                  String sn = shortName(n);
                  if (seen.add(sn)) anchors.computeIfAbsent(tag, x -> new HashMap<>()).merge(sn, 1, Integer::sum);
               }
            }
         }
      }
      for (String tag : sorted.keySet()) {
         int tot = totals.getOrDefault(tag, 0);
         System.out.printf("== %s: %d windows, %d samples%n", tag, sorted.get(tag).length, tot);
         if (tot == 0) continue;
         System.out.println("  stacks (leaf first):");
         stacks.get(tag).entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(top)
            .forEach(e -> System.out.printf("   %5.1f%%  %s%n", 100.0 * e.getValue() / tot, e.getKey()));
         System.out.println("  frames anywhere in the stack:");
         anchors.get(tag).entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(top * 2)
            .forEach(e -> System.out.printf("   %5.1f%%  %s%n", 100.0 * e.getValue() / tot, e.getKey()));
      }
   }

   static boolean inside(long[][] w, long ns) {
      int lo = 0, hi = w.length - 1, best = -1;
      while (lo <= hi) {
         int mid = (lo + hi) >>> 1;
         if (w[mid][0] <= ns) { best = mid; lo = mid + 1; } else hi = mid - 1;
      }
      return best >= 0 && ns < w[best][1];
   }

   static boolean skip(String n) {
      return n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.") || n.equals("[unknown]");
   }

   static String shortName(String n) {
      int paren = n.indexOf('(');
      if (paren > 0) n = n.substring(0, paren);
      int dot = n.lastIndexOf('.');
      int dot2 = dot > 0 ? n.lastIndexOf('.', dot - 1) : -1;
      return dot2 >= 0 ? n.substring(dot2 + 1) : n;
   }
}
