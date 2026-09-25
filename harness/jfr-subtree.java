// Callee tree under one frame, from async-profiler samples of one thread (jdk.ExecutionSample = cpu, profiler.WallClockSample
// = wall), as a share of ALL that thread's samples in the window: where a phase's time goes, 2 ms samples instead of the
// overlay's 25 Hz stack log (harness/subtree.py).
//
//   java harness/jfr-subtree.java <asprof.jfr> <thread> <cpu|wall> <frame> [depth] [min-pct] [start_epoch_ns end_epoch_ns]
//
// <frame> matches the short name "Class.method" (inner classes as Outer$Inner.method); the first match from the root is
// the subtree's root. Prints the inclusive share per callee, biggest first, then the leaf frames under it (self time).
import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;

public class JfrSubtree {
   static final class Node {
      final String name;
      int count;
      final Map<String, Node> kids = new HashMap<>();
      Node(String n) { name = n; }
   }

   public static void main(String[] a) throws Exception {
      Path jfr = Path.of(a[0]);
      String thread = a[1];
      String event = a[2].equals("cpu") ? "jdk.ExecutionSample" : "profiler.WallClockSample";
      String frame = a[3];
      int depth = a.length > 4 ? Integer.parseInt(a[4]) : 6;
      double minPct = a.length > 5 ? Double.parseDouble(a[5]) : 0.2;
      long from = a.length > 7 ? Long.parseLong(a[6]) : Long.MIN_VALUE, to = a.length > 7 ? Long.parseLong(a[7]) : Long.MAX_VALUE;
      Node root = new Node(frame);
      Map<String, Integer> self = new HashMap<>();
      int total = 0;
      try (RecordingFile rf = new RecordingFile(jfr)) {
         while (rf.hasMoreEvents()) {
            RecordedEvent ev = rf.readEvent();
            if (!ev.getEventType().getName().equals(event)) continue;
            RecordedThread t = ev.hasField("sampledThread") ? ev.getThread("sampledThread") : ev.getThread();
            if (t == null) continue;
            String tn = t.getJavaName() != null ? t.getJavaName() : ("0".equals(System.getenv("JFR_JAVA_ONLY")) ? t.getOSName() : null); // native threads inherit their creator's OS name
            if (tn == null || !tn.equals(thread)) continue;
            java.time.Instant st = ev.getStartTime();
            long ns = st.getEpochSecond() * 1_000_000_000L + st.getNano();
            if (ns < from || ns >= to) continue;
            total++;
            RecordedStackTrace s = ev.getStackTrace();
            if (s == null) continue;
            List<RecordedFrame> fr = s.getFrames();
            List<String> path = new ArrayList<>();
            for (int i = fr.size() - 1; i >= 0; i--) { // root first
               RecordedMethod m = fr.get(i).getMethod();
               String n = m.getType() == null ? m.getName() : m.getType().getName() + "." + m.getName();
               path.add(shortName(n));
            }
            int at = path.indexOf(frame);
            if (at < 0) continue;
            Node n = root;
            n.count++;
            for (int i = at + 1; i < path.size(); i++) {
               String name = path.get(i);
               if (skip(name)) continue;
               n = n.kids.computeIfAbsent(name, Node::new);
               n.count++;
            }
            self.merge(n.name, 1, Integer::sum);
         }
      }
      System.out.printf("%s: %d of %d samples (%.1f %%) of %s%n", frame, root.count, total, 100.0 * root.count / Math.max(1, total), thread);
      print(root, total, 0, depth, minPct);
      System.out.println("  self time under it:");
      final int tot = total;
      self.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(30)
         .forEach(e -> System.out.printf("   %5.2f %%  %s%n", 100.0 * e.getValue() / tot, e.getKey()));
   }

   static void print(Node n, int total, int level, int depth, double minPct) {
      if (level > 0) System.out.printf("%s%5.2f %%  %s%n", "  ".repeat(level), 100.0 * n.count / total, n.name);
      if (level >= depth) return;
      n.kids.values().stream().sorted((x, y) -> y.count - x.count)
         .filter(k -> 100.0 * k.count / total >= minPct).forEach(k -> print(k, total, level + 1, depth, minPct));
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
