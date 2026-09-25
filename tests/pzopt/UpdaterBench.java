package pzopt;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;

/**
 * Profiles every phase of the in-game updater against the real GitHub releases (scripts/updater-bench.sh; not a unit
 * test, it downloads). The baseline is the updater as it was before 2026-09-26, carried here verbatim: a new
 * HttpClient per request, the 210 KB release list, one stream of the 59 MB zip, every entry unpacked to a stage,
 * hashed and moved. Each technique is timed on its own and in the shipped combination.
 *
 * Args: <folder with the release zips named <tag>.zip> [runs]
 */
public class UpdaterBench {
   static final String REPO = "https://github.com/xD3I/PZ_Optimization/releases/download/";
   static final String REV = "b0bbce05d5";
   static final String NEW = "win-b0bbce05d5-ab4b22b";
   static final String[] OLD = {"win-b0bbce05d5-b70f234", "win-b0bbce05d5-f837467"};
   static Path zips;
   static int runs;

   public static void main(String[] args) throws Exception {
      zips = Path.of(args[0]);
      runs = args.length > 1 ? Integer.parseInt(args[1]) : 3;
      String only = args.length > 2 ? args[2] : "all";
      System.out.println("# updater bench, " + runs + " runs each, median (min..max) ms; " + Runtime.getRuntime().availableProcessors() + " cores");
      if (only.equals("all") || only.equals("check")) {
         check();
      }
      if (only.equals("all") || only.equals("local")) {
         local();
      }
      if (only.equals("all") || only.equals("install")) {
         for (String old : OLD) {
            install(old);
         }
      }
      if (only.equals("all") || only.equals("sweep")) {
         sweep(OLD[1]);
      }
      if (only.equals("all") || only.equals("spans")) {
         spansSweep(OLD[1]);
         spansSweep(OLD[0]);
      }
      if (only.equals("all") || only.equals("http3")) {
         http3();
      }
      if (only.equals("all") || only.equals("workshop")) {
         workshop(OLD[0]);
         workshop(OLD[1]);
      }
      System.exit(0);
   }

   // --- timing ---------------------------------------------------------------------------------------

   static String stats(List<Double> ms) {
      List<Double> s = new ArrayList<>(ms);
      s.sort(null);
      return String.format("%8.1f (%.1f..%.1f)", s.get(s.size() / 2), s.get(0), s.get(s.size() - 1));
   }

   static double ms(long t0) {
      return (System.nanoTime() - t0) / 1e6;
   }

   interface Step {
      void run() throws Exception;
   }

   static void bench(String name, Step setup, Step body) throws Exception {
      List<Double> t = new ArrayList<>();
      for (int i = 0; i < runs; i++) {
         if (setup != null) {
            setup.run();
         }
         long t0 = System.nanoTime();
         body.run();
         t.add(ms(t0));
      }
      System.out.println(String.format("%-64s %s", name, stats(t)));
   }

   static HttpClient cold() {
      return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
   }

   static HttpClient cold(HttpClient.Version v) {
      return HttpClient.newBuilder().version(v).followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
   }

   static void resetSharedClients() throws Exception {
      for (String f : new String[]{"client", "noRedirectClient"}) {
         var field = Updater.class.getDeclaredField(f);
         field.setAccessible(true);
         field.set(null, null);
      }
   }

   static String url(String tag) {
      return REPO + tag + "/pzopt-" + REV + "-classes.zip";
   }

   static Path zip(String tag) {
      return zips.resolve(tag + ".zip");
   }

   // --- the check ------------------------------------------------------------------------------------

   static void check() throws Exception {
      System.out.println("\n## check (every run with a cold client: in game the check runs once per boot)");
      String[] etag = {""};
      String[] body = {""};
      bench("baseline: GET releases?per_page=30 (200, 210 KB) + pick", null, () -> {
         HttpResponse<String> r = cold().send(HttpRequest.newBuilder(URI.create(Updater.API)).header("User-Agent", "pzopt-bench")
               .header("Accept", "application/vnd.github+json").GET().build(), HttpResponse.BodyHandlers.ofString());
         etag[0] = r.headers().firstValue("ETag").orElse("");
         body[0] = r.body();
         Updater.pickRelease(new JSONArray(r.body()), REV);
      });
      bench("conditional GET with the kept ETag (304) + pick from cache", null, () -> {
         HttpResponse<String> r = cold().send(HttpRequest.newBuilder(URI.create(Updater.API)).header("User-Agent", "pzopt-bench")
               .header("Accept", "application/vnd.github+json").header("If-None-Match", etag[0]).GET().build(), HttpResponse.BodyHandlers.ofString());
         if (r.statusCode() != 304) {
            throw new IllegalStateException("expected 304, got " + r.statusCode());
         }
         Updater.pickRelease(new JSONArray(body[0]), REV);
      });
      bench("HTTP/1.1 conditional GET (304)", null, () -> {
         cold(HttpClient.Version.HTTP_1_1).send(HttpRequest.newBuilder(URI.create(Updater.API)).header("User-Agent", "pzopt-bench")
               .header("If-None-Match", etag[0]).GET().build(), HttpResponse.BodyHandlers.ofString());
      });
      bench("GET releases?per_page=5 (200, 35 KB)", null, () -> {
         cold().send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/xD3I/PZ_Optimization/releases?per_page=5"))
               .header("User-Agent", "pzopt-bench").GET().build(), HttpResponse.BodyHandlers.ofString());
      });
      bench("rate-limit fallback: web /releases/latest + asset probe + build-info", UpdaterBench::resetSharedClients,
            () -> {
               Updater.Release r = Updater.webLatest(REV);
               if (r == null || r.zipSize <= 0) {
                  throw new IllegalStateException("web fallback found nothing");
               }
            });
      // the Workshop scan the check does while the request is in flight (a copy with 613 files)
      Path tmp = Files.createTempDirectory("pzopt-bench-ws");
      Path game = tmp.resolve("steamapps/common/ProjectZomboid/projectzomboid");
      Files.createDirectories(game);
      Path copy = tmp.resolve("steamapps/workshop/content/108600/" + Updater.WORKSHOP_ID + "/mods/PZ_Optimization/42/pzopt-classes");
      unzip(zip(NEW), copy);
      bench("Workshop copy scan (613 files, pzopt-files.txt complete)", null, () -> {
         if (Updater.findWorkshopCopy(game, REV) == null) {
            throw new IllegalStateException("no copy");
         }
      });
      Updater.deleteTree(tmp);
   }

   // --- local costs (what the zstd / stat-cache / hash-reuse ideas could save) ----------------------

   static void local() throws Exception {
      System.out.println("\n## local work on the 613-entry release (warm page cache)");
      Path game = installed(NEW);
      UpdateDelta.Directory d;
      try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(zip(NEW))) {
         d = UpdateDelta.directory(fs);
      }
      for (int threads : new int[]{1, 8}) {
         ExecutorService pool = UpdateDelta.pool("bench", threads);
         bench("plan: CRC-32 of the 613 installed files, " + threads + " thread(s)", null, () -> UpdateDelta.plan(d, game, pool));
         bench("inflate + CRC all 613 entries from the zip, " + threads + " thread(s)", null, () -> {
            try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(zip(NEW))) {
               UpdateDelta.fetch(fs, d.entries(), pool, null);
            }
         });
         bench("sha256 of the 613 installed files, " + threads + " thread(s)", null, () -> {
            List<Future<String>> f = new ArrayList<>();
            for (UpdateDelta.Entry e : d.entries()) {
               f.add(pool.submit(() -> Updater.sha256(game.resolve(e.name()))));
            }
            for (Future<String> x : f) {
               x.get();
            }
         });
         pool.shutdown();
      }
      bench("stat of the 613 installed files (what a size+mtime cache would read)", null, () -> {
         for (UpdateDelta.Entry e : d.entries()) {
            Files.readAttributes(game.resolve(e.name()), java.nio.file.attribute.BasicFileAttributes.class);
         }
      });
      Updater.deleteTree(game.getParent());
   }

   // --- download + install ---------------------------------------------------------------------------

   /** A fresh game folder with {@code tag} installed (manifest with real hashes), outside the timing. */
   static Path installed(String tag) throws Exception {
      Path tmp = Files.createTempDirectory("pzopt-bench-game");
      Path game = tmp.resolve("game");
      Files.createDirectories(game);
      Files.writeString(game.resolve(Updater.FILE_LIST), "");
      Updater.swap(zip(tag), game, REV);
      return game;
   }

   static void install(String old) throws Exception {
      String from = old.substring(old.lastIndexOf('-') + 1);
      System.out.println("\n## install " + NEW + " over " + from + " (click to installed files)");
      long size = Files.size(zip(NEW));
      Path[] game = {null};
      Step fresh = () -> {
         if (game[0] != null) {
            Updater.deleteTree(game[0].getParent());
         }
         game[0] = installed(old);
      };
      bench("baseline: 1 stream, cold client + unzip all to stage, hash all, move all", fresh, () -> {
         Path z = game[0].resolve("pzopt-update.tmp.zip");
         Updater.fetchWith(cold(), url(NEW), size, z, null);
         baselineSwap(z, game[0].resolve("pzopt-update.tmp"), game[0], REV);
         Files.deleteIfExists(z);
      });
      bench("whole zip, 1 stream + delta install from the file", fresh, () -> {
         Path z = game[0].resolve("pzopt-update.tmp.zip");
         Updater.fetchWith(cold(), url(NEW), size, z, null);
         Updater.swap(z, game[0], REV);
         Files.deleteIfExists(z);
      });
      bench("whole zip, 8 parallel ranges + delta install from the file", fresh, () -> {
         Path z = game[0].resolve("pzopt-update.tmp.zip");
         UpdateDelta.downloadSegmented(cold(), url(NEW), size, z, 8, Updater.pool(), null);
         Updater.swap(z, game[0], REV);
         Files.deleteIfExists(z);
      });
      double[] phase = new double[4];
      List<double[]> phases = new ArrayList<>();
      String[] how = {""};
      Updater.Prepared[] prepared = {null};
      bench("delta: directory + CRC plan + changed ranges + write (no prefetch)", fresh, () -> {
         long t0 = System.nanoTime();
         UpdateDelta.HttpSource src = new UpdateDelta.HttpSource(cold(), url(NEW), size);
         UpdateDelta.Directory d = UpdateDelta.directory(src);
         phase[0] = ms(t0);
         t0 = System.nanoTime();
         UpdateDelta.Plan plan = UpdateDelta.plan(d, game[0], Updater.pool());
         phase[1] = ms(t0);
         t0 = System.nanoTime();
         Map<String, byte[]> changed = UpdateDelta.fetch(src, plan.changed(), Updater.pool(), null);
         phase[2] = ms(t0);
         t0 = System.nanoTime();
         Updater.applyPrepared(new Updater.Prepared(null, Updater.names(d), changed, "", true), game[0], REV, "the zip");
         phase[3] = ms(t0);
         phases.add(phase.clone());
         how[0] = plan.changed().size() + " files, " + plan.changedCompressed() + " bytes, " + src.describe();
      });
      phases.sort((a, b) -> Double.compare(a[0] + a[1] + a[2] + a[3], b[0] + b[1] + b[2] + b[3]));
      double[] m = phases.get(phases.size() / 2);
      System.out.println(String.format("    phases (median run): directory %.1f, plan %.1f, fetch %.1f, write+manifest %.1f ms; %s", m[0], m[1], m[2], m[3], how[0]));
      bench("delta, prefetched: what the click waits for (write + manifest)", () -> {
         fresh.run();
         prepared[0] = Updater.prepare(new Updater.Release(NEW, "ab4b22b", "", "", "", url(NEW), size), game[0], true, Long.MAX_VALUE, null);
      }, () -> Updater.applyPrepared(prepared[0], game[0], REV, "the zip"));
      bench("  same, manifest hashes recomputed (no reuse from the old manifest)", () -> {
         fresh.run();
         prepared[0] = Updater.prepare(new Updater.Release(NEW, "ab4b22b", "", "", "", url(NEW), size), game[0], true, Long.MAX_VALUE, null);
         Files.writeString(game[0].resolve(Updater.MANIFEST), String.join("\n", Updater.previousFiles(game[0])) + "\n");
      }, () -> Updater.applyPrepared(prepared[0], game[0], REV, "the zip"));
      Updater.deleteTree(game[0].getParent());
   }

   /** Parallelism and protocol of the range requests: the day-apart release (150 changed entries). */
   static void sweep(String old) throws Exception {
      System.out.println("\n## range fetch of the changed entries over " + old.substring(old.lastIndexOf('-') + 1) + ", by workers and protocol (cold client)");
      Path game = installed(old);
      long size = Files.size(zip(NEW));
      UpdateDelta.Plan plan;
      try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(zip(NEW))) {
         plan = UpdateDelta.plan(UpdateDelta.directory(fs), game, Updater.pool());
      }
      System.out.println("    " + plan.changed().size() + " entries in " + UpdateDelta.spans(plan.changed()).size() + " merged spans, "
            + plan.changedCompressed() + " compressed bytes");
      for (HttpClient.Version v : new HttpClient.Version[]{HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_1_1}) {
         for (int threads : new int[]{1, 4, 8, 16}) {
            ExecutorService pool = UpdateDelta.pool("bench", threads);
            bench(v + ", " + threads + " worker(s)", null, () -> {
               UpdateDelta.HttpSource src = new UpdateDelta.HttpSource(cold(v), url(NEW), size);
               UpdateDelta.fetch(src, plan.changed(), pool, null);
            });
            pool.shutdown();
         }
      }
      Updater.deleteTree(game.getParent());
   }

   /** Merge gap, span cap and directory tail: bytes over the wire against requests (warm connection, 8 workers). */
   static void spansSweep(String old) throws Exception {
      System.out.println("\n## delta tuning over " + old.substring(old.lastIndexOf('-') + 1) + ": merge gap / span cap / directory tail (cold client each run)");
      Path game = installed(old);
      long size = Files.size(zip(NEW));
      long gap0 = UpdateDelta.mergeGap, span0 = UpdateDelta.maxSpan;
      int tail0 = UpdateDelta.tail;
      for (long[] v : new long[][]{{0, 4 << 20, 128 << 10}, {8 << 10, 4 << 20, 128 << 10}, {64 << 10, 4 << 20, 128 << 10}, {256 << 10, 4 << 20, 128 << 10},
            {64 << 10, 256 << 10, 128 << 10}, {64 << 10, 1 << 20, 128 << 10}, {64 << 10, 4 << 20, 64 << 10}, {16 << 10, 1 << 20, 64 << 10}}) {
         UpdateDelta.mergeGap = v[0];
         UpdateDelta.maxSpan = v[1];
         UpdateDelta.tail = (int) v[2];
         String[] info = {""};
         bench(String.format("gap %d KB, span cap %d KB, tail %d KB", v[0] >> 10, v[1] >> 10, v[2] >> 10), null, () -> {
            UpdateDelta.HttpSource src = new UpdateDelta.HttpSource(cold(HttpClient.Version.HTTP_2), url(NEW), size);
            UpdateDelta.Directory d = UpdateDelta.directory(src);
            UpdateDelta.Plan plan = UpdateDelta.plan(d, game, Updater.pool());
            UpdateDelta.fetch(src, plan.changed(), Updater.pool(), null);
            info[0] = src.describe();
         });
         System.out.println("    " + info[0]);
      }
      UpdateDelta.mergeGap = gap0;
      UpdateDelta.maxSpan = span0;
      UpdateDelta.tail = tail0;
      Updater.deleteTree(game.getParent());
   }

   /** HTTP/3 (JDK 26's client; the game's bundled JRE 25 has none): the conditional check and the day-apart delta. */
   static void http3() throws Exception {
      System.out.println("\n## HTTP/3 (runtime " + Runtime.version() + ")");
      HttpClient.Version h3;
      try {
         h3 = HttpClient.Version.valueOf("HTTP_3");
      } catch (IllegalArgumentException e) {
         System.out.println("    no HTTP_3 in this runtime");
         return;
      }
      Path game = installed(OLD[1]);
      long size = Files.size(zip(NEW));
      for (HttpClient.Version v : new HttpClient.Version[]{HttpClient.Version.HTTP_2, h3}) {
         String[] proto = {""};
         bench(v + ": delta over f837467 (directory + plan + fetch)", null, () -> {
            UpdateDelta.HttpSource src = new UpdateDelta.HttpSource(cold(v), url(NEW), size);
            UpdateDelta.Directory d = UpdateDelta.directory(src);
            UpdateDelta.fetch(src, UpdateDelta.plan(d, game, Updater.pool()).changed(), Updater.pool(), null);
         });
         bench(v + ": GET api.github.com releases?per_page=5", null, () -> {
            HttpResponse<String> r = cold(v).send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/xD3I/PZ_Optimization/releases?per_page=5"))
                  .header("User-Agent", "pzopt-bench").GET().build(), HttpResponse.BodyHandlers.ofString());
            proto[0] = String.valueOf(r.version());
         });
         System.out.println("    negotiated: " + proto[0]);
      }
      Updater.deleteTree(game.getParent());
   }

   // --- the Workshop copy ----------------------------------------------------------------------------

   static void workshop(String old) throws Exception {
      String from = old.substring(old.lastIndexOf('-') + 1);
      System.out.println("\n## Workshop copy of " + NEW + " over " + from);
      Path copy = Files.createTempDirectory("pzopt-bench-copy");
      unzip(zip(NEW), copy);
      Path[] game = {null};
      Step fresh = () -> {
         if (game[0] != null) {
            Updater.deleteTree(game[0].getParent());
         }
         game[0] = installed(old);
      };
      bench("baseline: copy all to stage, hash all, move all", fresh, () -> baselineSwapFolder(copy, game[0].resolve("pzopt-update.tmp"), game[0]));
      bench("changed files only (size + byte compare, parallel), written", fresh, () -> Updater.swapFolder(copy, game[0], REV));
      bench("changed files only, hard-linked from the copy instead of written", fresh, () -> {
         UpdateDelta.FolderChanges fc = UpdateDelta.folderChanges(copy, game[0], Updater.pool());
         for (String rel : fc.changed().keySet()) {
            Path dst = game[0].resolve(rel);
            Files.createDirectories(dst.getParent());
            Path tmp = dst.resolveSibling(dst.getFileName() + ".pzopt-new");
            Files.deleteIfExists(tmp);
            Files.createLink(tmp, copy.resolve(rel));
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         }
         Updater.writeManifest(game[0], REV, UpdateDelta.apply(fc.names(), Map.of(), game[0], Updater.previousFiles(game[0]),
               Updater.previousHashes(game[0]), Updater.pool()));
      });
      Updater.deleteTree(game[0].getParent());
      Updater.deleteTree(copy);
   }

   // --- the pre-2026-09-26 updater, verbatim ---------------------------------------------------------

   static Map<String, String> baselineSwap(Path zip, Path stage, Path dir, String rev) throws Exception {
      List<String> files = new ArrayList<>();
      try (ZipFile z = new ZipFile(zip.toFile())) {
         Updater.deleteTree(stage);
         Files.createDirectories(stage);
         Enumeration<? extends ZipEntry> en = z.entries();
         while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) {
               continue;
            }
            String rel = e.getName();
            Path target = stage.resolve(rel).normalize();
            Files.createDirectories(target.getParent());
            try (InputStream in = z.getInputStream(e)) {
               Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            files.add(rel);
         }
      }
      return baselineMove(files, stage, dir, rev);
   }

   static Map<String, String> baselineSwapFolder(Path src, Path stage, Path dir) throws Exception {
      Updater.deleteTree(stage);
      Files.createDirectories(stage);
      List<String> files = new ArrayList<>();
      try (var s = Files.walk(src)) {
         for (Path f : s.filter(Files::isRegularFile).toList()) {
            String rel = src.relativize(f).toString().replace(java.io.File.separatorChar, '/');
            Path target = stage.resolve(rel);
            Files.createDirectories(target.getParent());
            Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
            files.add(rel);
         }
      }
      return baselineMove(files, stage, dir, REV);
   }

   static Map<String, String> baselineMove(List<String> files, Path stage, Path dir, String rev) throws Exception {
      files.sort(null);
      List<String> previous = Updater.previousFiles(dir);
      Map<String, String> installed = new LinkedHashMap<>();
      for (String rel : files) {
         Path src = stage.resolve(rel);
         Path dst = dir.resolve(rel);
         installed.put(rel, Updater.sha256(src));
         Files.createDirectories(dst.getParent());
         Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
      }
      for (String rel : previous) {
         if (!installed.containsKey(rel)) {
            Files.deleteIfExists(dir.resolve(rel));
         }
      }
      Updater.writeManifest(dir, rev, installed);
      Updater.deleteTree(stage);
      return installed;
   }

   static void unzip(Path zip, Path out) throws Exception {
      try (ZipFile z = new ZipFile(zip.toFile())) {
         Enumeration<? extends ZipEntry> en = z.entries();
         while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) {
               continue;
            }
            Path t = out.resolve(e.getName());
            Files.createDirectories(t.getParent());
            try (InputStream in = z.getInputStream(e)) {
               Files.copy(in, t, StandardCopyOption.REPLACE_EXISTING);
            }
         }
      }
   }
}
