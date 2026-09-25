package pzopt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * pzopt.Restart on Linux / macOS: a child JVM calls {@link Restart#relaunch()} and exits; the helper must start the
 * same program again, after the child ended, with the same arguments (one with a space and a quote), working
 * directory and the restart marks in its environment.
 */
public class RestartTest {
   public static void main(String[] args) throws Exception {
      if (args.length > 0 && args[0].equals("child")) {
         child(args);
         return;
      }
      if (java.io.File.separatorChar == '\\') {
         System.out.println("RestartTest skipped on Windows (PowerShell helper)");
         return;
      }
      Path tmp = Files.createTempDirectory("pzopt-restart-test");
      Path marker = tmp.resolve("restarted.txt");
      String java = ProcessHandle.current().info().command().orElseThrow();
      ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "pzopt.RestartTest", "child",
            marker.toString(), "an arg with spaces", "it's quoted").directory(tmp.toFile()).inheritIO();
      pb.environment().remove(Restart.ENV_FROM);
      pb.environment().remove(Restart.ENV_AT);
      Process first = pb.start();
      if (first.waitFor() != 0) {
         throw new AssertionError("first process failed");
      }
      long firstEndedMs = System.currentTimeMillis();
      for (int i = 0; i < 200 && !Files.exists(marker); i++) {
         Thread.sleep(50);
      }
      if (!Files.exists(marker)) {
         throw new AssertionError("no restarted process within 10 s");
      }
      List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
      int failures = 0;
      String[] want = {"child", marker.toString(), "an arg with spaces", "it's quoted"};
      for (int i = 0; i < want.length; i++) {
         if (!want[i].equals(lines.get(i))) {
            System.err.println("FAIL: argument " + i + " is " + lines.get(i) + ", want " + want[i]);
            failures++;
         }
      }
      if (!lines.get(4).equals(tmp.toRealPath().toString()) && !lines.get(4).equals(tmp.toString())) {
         System.err.println("FAIL: working directory " + lines.get(4));
         failures++;
      }
      if (!lines.get(5).equals(Long.toString(first.pid()))) {
         System.err.println("FAIL: " + Restart.ENV_FROM + "=" + lines.get(5) + ", want " + first.pid());
         failures++;
      }
      if (lines.get(6).equals("alive")) {
         System.err.println("FAIL: the new process started while the old one was alive");
         failures++;
      }
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("RestartTest ok (the new process wrote its marker " + (Long.parseLong(lines.get(7)) - firstEndedMs)
            + " ms after the first one ended, JVM start included)");
   }

   static void child(String[] args) throws Exception {
      Path marker = Path.of(args[1]);
      if (!Restart.restarted()) {
         if (!Restart.relaunch()) {
            System.exit(3);
         }
         Thread.sleep(300); // like the game's quit: the helper must wait for this process to end
         System.exit(0);
      }
      String from = System.getenv(Restart.ENV_FROM);
      boolean oldAlive = ProcessHandle.of(Long.parseLong(from)).map(ProcessHandle::isAlive).orElse(false);
      Path tmp = marker.resolveSibling("restarted.tmp");
      Files.writeString(tmp, String.join("\n", args) + "\n" + Path.of("").toAbsolutePath() + "\n" + from + "\n"
            + (oldAlive ? "alive" : "ended") + "\n" + System.currentTimeMillis() + "\n", StandardCharsets.UTF_8);
      Files.move(tmp, marker);
   }
}
