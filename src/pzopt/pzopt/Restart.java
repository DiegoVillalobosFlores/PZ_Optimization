package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Restart game" of the update dialog (media/lua/client/pzopt/pzopt_mainscreen_update.lua): the classes an update
 * replaced load on the next start, so instead of quitting and leaving the relaunch to the player, a small helper
 * process waits for this one to end and starts the same program again with the same arguments, working directory
 * and environment (Steam's SteamAppId and overlay variables included). The Lua then quits the game the stock way
 * (options saved, FMOD and the GL context closed).
 *
 * Linux / macOS: {@code /bin/sh} polls {@code kill -0 <pid>} every 50 ms and execs the command
 * ({@link ProcessHandle.Info#command()}: /proc/self/exe or the Mach-O path, and the arguments the JVM reads from
 * /proc/self/cmdline or KERN_PROCARGS2). Windows keeps no argument list for another process that Java can read, so a
 * hidden PowerShell reads this process's command line from WMI, says so on its output (this method waits for that
 * line, so the game never quits before the helper knows what to start), waits for the process and starts it again.
 */
public final class Restart {
   private Restart() {
   }

   /** Starts the helper; true when it is waiting for this process to end. */
   public static boolean relaunch() {
      long pid = ProcessHandle.current().pid();
      Path cwd = Path.of("").toAbsolutePath();
      try {
         if (File.separatorChar == '\\') {
            return windows(pid, cwd);
         }
         ProcessHandle.Info info = ProcessHandle.current().info();
         String exe = info.command().orElse(null);
         if (exe == null) {
            Log.warn("restart: this process's executable is unknown");
            return false;
         }
         List<String> cmd = new ArrayList<>();
         cmd.add("/bin/sh");
         cmd.add("-c");
         // $0 = pid; give up waiting after 120 s (a process nobody reaps), then start anyway
         cmd.add("n=0; while kill -0 \"$0\" 2>/dev/null && [ $n -lt 2400 ]; do sleep 0.05; n=$((n+1)); done; exec \"$@\"");
         cmd.add(Long.toString(pid));
         cmd.add(exe);
         for (String a : info.arguments().orElse(new String[0])) {
            cmd.add(a);
         }
         ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
         mark(pb);
         pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
         pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
         pb.redirectError(ProcessBuilder.Redirect.DISCARD);
         pb.start();
         Log.info("restart: helper waits for pid " + pid + " to end, then starts " + exe + " (" + (cmd.size() - 5) + " arguments) in " + cwd);
         return true;
      } catch (Exception e) {
         Log.warn("restart: could not start the helper: " + e);
         return false;
      }
   }

   /** The new process learns it was restarted and when the button was pressed (the log's restart timing). */
   private static void mark(ProcessBuilder pb) {
      pb.environment().put(ENV_FROM, Long.toString(ProcessHandle.current().pid()));
      pb.environment().put(ENV_AT, Long.toString(System.currentTimeMillis()));
   }

   static final String ENV_FROM = "PZOPT_RESTARTED_FROM";
   static final String ENV_AT = "PZOPT_RESTARTED_AT";

   /** True in a process that Restart game started. */
   public static boolean restarted() {
      return System.getenv(ENV_FROM) != null;
   }

   /** Milliseconds since Restart game was pressed in the previous process, or -1. */
   public static long msSincePressed() {
      try {
         return System.currentTimeMillis() - Long.parseLong(System.getenv(ENV_AT));
      } catch (Exception e) {
         return -1;
      }
   }

   /** Logs how long the old process took to end and the new one to start (once, early in the boot). */
   public static void logRestarted() {
      if (!restarted()) {
         return;
      }
      long at;
      try {
         at = Long.parseLong(System.getenv(ENV_AT));
      } catch (Exception e) {
         return;
      }
      long started = ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(-1L);
      Log.info("restart: started by Restart game in pid " + System.getenv(ENV_FROM) + "; this process began "
            + (started > 0 ? (started - at) + " ms" : "?") + " after the press, the boot reached the updater " + (System.currentTimeMillis() - at) + " ms after it");
   }

   private static boolean windows(long pid, Path cwd) throws Exception {
      String dir = cwd.toString().replace("'", "''");
      String script = String.join("\n",
            "$p = Get-CimInstance Win32_Process -Filter \"ProcessId=" + pid + "\"",
            "if (-not $p) { Write-Output 'none'; exit 1 }",
            "$exe = $p.ExecutablePath",
            "$cl = $p.CommandLine",
            "if ($cl.StartsWith('\"')) { $rest = $cl.Substring($cl.IndexOf('\"', 1) + 1) } else { $i = $cl.IndexOf(' '); if ($i -lt 0) { $rest = '' } else { $rest = $cl.Substring($i) } }",
            "$rest = $rest.Trim()",
            "Write-Output 'ready'",
            "[Console]::Out.Flush()",
            "Wait-Process -Id " + pid + " -Timeout 120 -ErrorAction SilentlyContinue",
            "if ($rest) { Start-Process -FilePath $exe -ArgumentList $rest -WorkingDirectory '" + dir + "' } else { Start-Process -FilePath $exe -WorkingDirectory '" + dir + "' }");
      String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
      ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-WindowStyle", "Hidden", "-EncodedCommand", encoded).directory(cwd.toFile());
      mark(pb);
      pb.redirectErrorStream(true);
      Process helper = pb.start();
      helper.getOutputStream().close();
      // wait until the helper has our command line; the game must not quit before
      BufferedReader out = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
      CompletableFuture<Boolean> ready = CompletableFuture.supplyAsync(() -> {
         try {
            String line;
            while ((line = out.readLine()) != null) {
               if (line.strip().equals("ready")) {
                  return true;
               }
            }
         } catch (Exception ignored) {
         }
         return false;
      }, job -> {
         Thread t = new Thread(job, "pzopt-restart");
         t.setDaemon(true);
         t.start();
      });
      boolean ok;
      try {
         ok = ready.get(15, TimeUnit.SECONDS);
      } catch (Exception e) {
         ok = false;
      }
      if (ok) {
         Log.info("restart: helper (pid " + helper.pid() + ") has the command line, waits for pid " + pid);
      } else {
         Log.warn("restart: the helper did not read the command line");
         helper.destroy();
      }
      return ok;
   }
}
