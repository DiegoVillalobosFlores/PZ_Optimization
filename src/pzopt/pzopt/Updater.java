package pzopt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The main menu's "Update PZ Optimization" item (media/lua/client/pzopt/pzopt_mainscreen_update.lua).
 *
 * Once per boot a daemon thread lists the GitHub releases of {@link #REPO_SLUG} and picks the newest one that carries
 * {@code pzopt-<revision>-classes.zip} for the running game revision (the assets are per revision: the runtime guard
 * would disable any other build). That release is an update when its tag's commit ({@code win-<revision>-<commit>})
 * is not the commit this build was made from and it was published after this build ({@code commit=} and
 * {@code built=} in build-info.properties; a from-source build newer than the last release stays quiet).
 *
 * The Steam Workshop copy (issue #16, {@code updateFromWorkshop}): a subscriber already has every release on disk,
 * Steam downloads the Workshop item to {@code <library>/steamapps/workshop/content/108600/<id>/mods/PZ_Optimization/
 * <version>/pzopt-classes/}, the unpacked release zip (scripts/workshop.sh stages the GitHub asset itself). While the
 * GitHub request is in flight the check reads that copy ({@link #findWorkshopCopy}: the library that holds the game,
 * a copy for this revision whose pzopt-files.txt is complete) and offers it at once when it is newer than this build,
 * by the same rule with its {@code built=} in place of a publish date, so a copy older than a GitHub install never
 * offers a downgrade. The GitHub answer replaces the offer only with a newer release.
 *
 * Near-instant (2026-09-26, docs/findings-updater-2026-09-26.md): the check starts from GameWindow.mainThreadInit,
 * seconds before the menu exists; the release list is a conditional request (its ETag kept in
 * ~/Zomboid/pzopt/update-releases.json: 304 in ~70 ms instead of 210 KB in ~430 ms) on one shared HTTP/2 client;
 * a rate-limited API falls back to the web redirect of /releases/latest. Once a release is offered its changes are
 * prepared in the background ({@code updatePrefetch}): {@link UpdateDelta} reads the zip's central directory by a
 * range request, compares CRC-32s with the installed files and fetches only the changed entries (a release apart is
 * ~3 files / 57 KB of the 59 MB zip), so Update now only writes those files and rewrites the manifest.
 * Without range support the whole zip comes in parallel segments and goes through the same comparison.
 *
 * {@link #install()} writes each changed file beside its target and renames it over it, deletes what the previous
 * install listed and the new release no longer has, and rewrites {@code pzopt-installed.txt} in the installers'
 * format (install.sh / install.ps1 / scripts/pzopt.sh read it back for --status and --uninstall). Classes the JVM
 * already loaded stay as they are, so the game has to be restarted afterwards; the dialog offers Restart game
 * ({@link Restart}). Without a manifest (a hand-unpacked zip) the item only shows the release page.
 *
 * Every state is a string the Lua polls once a frame through the PerformanceSettings forwards.
 */
public final class Updater {
   public static final String REPO_SLUG = "xD3I/PZ_Optimization";
   static final String API = "https://api.github.com/repos/" + REPO_SLUG + "/releases?per_page=30";
   static final String RELEASES_PAGE = "https://github.com/" + REPO_SLUG + "/releases";
   static final String WEB_LATEST = "https://github.com/" + REPO_SLUG + "/releases/latest";
   static final String MANIFEST = "pzopt-installed.txt";
   static final String FILE_LIST = "pzopt-files.txt";
   static final String BUILD_INFO = "pzopt/build-info.properties";
   static final String STEAM_APP_ID = "108600";
   static final String WORKSHOP_ID = "3805285544";
   static final String WORKSHOP_CHANGELOG = "https://steamcommunity.com/sharedfiles/filedetails/changelog/" + WORKSHOP_ID;
   static final int SEGMENTS = 8;

   /** idle → checking → (up-to-date | available | error); available → downloading → installing → (installed | error). */
   public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, INSTALLED, ERROR }

   private static volatile State state = State.IDLE;
   private static volatile Release release;
   private static volatile String message = "";
   private static volatile int progress;   // 0..100 of the download
   private static volatile boolean started;
   private static volatile boolean installing;
   private static volatile long checkStartNs;

   // the background preparation of the offered release (updatePrefetch)
   private static final Object PREP = new Object();
   private static Release prepFor;
   private static CompletableFuture<Prepared> prepJob;

   private static volatile HttpClient client;
   private static volatile HttpClient noRedirectClient;
   private static volatile ExecutorService pool;

   /** One release with the zip for this revision, or the Workshop copy of one ({@link #localDir} set). */
   static final class Release {
      final String tag;
      final String commit;
      final String published;   // ISO instant; the Workshop copy's build time
      final String notes;
      final String pageUrl;
      final String zipUrl;
      final long zipSize;
      final Path localDir;      // the Workshop copy's pzopt-classes folder, null for a GitHub release

      Release(String tag, String commit, String published, String notes, String pageUrl, String zipUrl, long zipSize) {
         this(tag, commit, published, notes, pageUrl, zipUrl, zipSize, null);
      }

      Release(String tag, String commit, String published, String notes, String pageUrl, String zipUrl, long zipSize, Path localDir) {
         this.tag = tag;
         this.commit = commit;
         this.published = published;
         this.notes = notes;
         this.pageUrl = pageUrl;
         this.zipUrl = zipUrl;
         this.zipSize = zipSize;
         this.localDir = localDir;
      }
   }

   /** A release ready to apply: every file of it, and the content of those that differ from the installed ones. */
   record Prepared(Release release, List<String> names, Map<String, byte[]> changed, String how, boolean complete) {
   }

   private Updater() {
   }

   // --- what the Lua reads ---------------------------------------------------------------------------

   public static String state() {
      return state.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
   }

   public static String tag() {
      Release r = release;
      return r == null ? "" : r.tag;
   }

   public static String notes() {
      Release r = release;
      return r == null ? "" : r.notes;
   }

   /** Publish date of the offered release, yyyy-mm-dd. */
   public static String published() {
      Release r = release;
      return r == null || r.published.length() < 10 ? "" : r.published.substring(0, 10);
   }

   public static String pageUrl() {
      Release r = release;
      return r == null ? RELEASES_PAGE : r.pageUrl;
   }

   /** Where the offered build comes from: "workshop" (already on disk), "github" (a download), "" when none is offered. */
   public static String source() {
      Release r = release;
      return r == null ? "" : r.localDir != null ? "workshop" : "github";
   }

   /** The commit this build was made from ("unknown" for a build without git). */
   public static String installedCommit() {
      String c = BuildInfo.get("commit");
      return c == null || c.isEmpty() ? "unknown" : c;
   }

   /** The error text for the "error" state, or the install summary for "installed". */
   public static String message() {
      return message;
   }

   public static int progress() {
      return progress;
   }

   /**
    * The dev rig devUpdateDrive: "drive" makes the menu's Lua open the dialog and press Update now, then Restart game;
    * in the process that restart started it is "restarted" (the Lua logs the time since the press and quits). "" off.
    */
   public static String drive() {
      if (!Config.DEV_UPDATE_DRIVE) {
         return "";
      }
      return Restart.restarted() ? "restarted:" + Restart.msSincePressed() : "drive";
   }

   /** True when the running copy was installed by an installer (there is a manifest to replace). */
   public static boolean canInstall() {
      Path dir = gameDir();
      return Files.isRegularFile(dir.resolve(MANIFEST)) || Files.isRegularFile(dir.resolve(FILE_LIST));
   }

   // --- check ----------------------------------------------------------------------------------------

   /** Starts the release check once per boot (from GameWindow.mainThreadInit, and the menu); later calls are no-ops. */
   public static void check() {
      if (started) {
         return;
      }
      Restart.logRestarted();
      synchronized (Updater.class) {
         if (started) {
            return;
         }
         started = true;
      }
      if (!Config.UPDATE_CHECK) {
         Log.info("update check off (updateCheck=false)");
         return;
      }
      if ((Harness.REQUESTED || Harness.active()) && !Config.DEV_UPDATE_DRIVE) { // REQUESTED: at the main menu the harness is still idle (it starts with the world)
         Log.info("update check skipped in a harness run");
         return;
      }
      state = State.CHECKING;
      checkStartNs = System.nanoTime();
      Thread t = new Thread(Updater::runCheck, "pzopt-update-check");
      t.setDaemon(true);
      t.start();
   }

   private static void runCheck() {
      try {
         runCheck(gameDir(), Overrides.jarRevision());
      } catch (Throwable t) {
         message = "update check failed: " + t;
         settle(State.ERROR);
         Log.warn(message);
      }
   }

   static void runCheck(Path game, String rev) {
      String ours = installedCommit();
      long built = builtEpoch();
      deleteTree(game.resolve("pzopt-update.tmp")); // a stage left by an older updater
      try {
         Files.deleteIfExists(game.resolve("pzopt-update.tmp.zip"));
      } catch (IOException ignored) {
      }

      // the GitHub request goes out first; the Workshop copy is read while it is in flight
      CompletableFuture<Releases> remote = CompletableFuture.supplyAsync(() -> releases(rev), Updater::onOwnThread);
      Release local = null;
      if (Config.UPDATE_FROM_WORKSHOP) {
         try {
            local = findWorkshopCopy(game, rev);
         } catch (Exception e) {
            Log.warn("update check: reading the Steam Workshop copy failed: " + e);
         }
         if (local == null) {
            Log.info("update check: no complete Steam Workshop copy for " + rev + " next to the game");
         } else if (Config.DEV_UPDATE_OFFER || isNewer(local, ours, built)) {
            offer(local);
            Log.info("update available from the Steam Workshop copy: " + local.tag + " (built " + local.published + ") in "
                  + local.localDir + ", this build is " + ours + (canInstall() ? "" : "; no " + MANIFEST + ", the menu item only opens the Workshop page")
                  + since());
         } else {
            Log.info("update check: the Steam Workshop copy is " + local.tag + ", this build is " + ours + ", not newer");
         }
      }

      Releases answer = remote.join();
      if (answer.error != null) {
         if (local != null) {
            // the Workshop copy answered: its offer stands, or this build is at least as new as it
            message = "GitHub unreachable (" + answer.error + "); checked the Steam Workshop copy " + local.tag + " instead";
            settle(State.UP_TO_DATE);
            Log.info("update check: " + message + since());
         } else {
            message = "update check failed: " + answer.error;
            settle(State.ERROR);
            Log.warn(message + since());
         }
         return;
      }
      Release r = answer.release;
      if (r == null) {
         settle(State.UP_TO_DATE);
         Log.info("update check (" + answer.how + "): no release carries pzopt-" + rev + "-classes.zip" + since());
         return;
      }
      if (!Config.DEV_UPDATE_OFFER && !isNewer(r, ours, built)) {
         settle(State.UP_TO_DATE);
         Log.info("update check (" + answer.how + "): newest release for " + rev + " is " + r.tag + ", this build is " + ours + ", up to date" + since());
         return;
      }
      if (local != null && release == local && !isNewer(r, local.commit, builtEpochOf(local))) {
         Log.info("update check (" + answer.how + "): GitHub's newest release " + r.tag + " is the Steam Workshop copy's build, installing from the copy on disk");
         return;
      }
      if (offer(r)) {
         Log.info("update available (" + answer.how + "): " + r.tag + " (published " + r.published + "), this build is " + ours
               + (canInstall() ? "" : "; no " + MANIFEST + ", the menu item only opens the release page") + since());
      } else {
         Log.info("update check: " + r.tag + " found on GitHub while the Workshop copy is being installed; left for the next launch");
      }
   }

   private static String since() {
      return String.format(" (%.0f ms after the check started)", (System.nanoTime() - checkStartNs) / 1e6);
   }

   /** The GitHub side of the check: the newest release for the revision, or the error. */
   record Releases(Release release, String how, String error) {
   }

   static Releases releases(String rev) {
      try {
         Cached c = readCache();
         HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API)).timeout(Duration.ofSeconds(30))
               .header("User-Agent", "PZ_Optimization-updater").header("Accept", "application/vnd.github+json");
         if (c != null) {
            b.header("If-None-Match", c.etag);
         }
         HttpResponse<String> resp = client().send(b.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
         int code = resp.statusCode();
         if (code == 304 && c != null) {
            return new Releases(pickRelease(new JSONArray(c.body), rev), "api 304, cached list", null);
         }
         if (code == 200) {
            String etag = resp.headers().firstValue("ETag").orElse("");
            if (!etag.isEmpty()) {
               writeCache(etag, resp.body());
            }
            return new Releases(pickRelease(new JSONArray(resp.body()), rev), "api 200", null);
         }
         if (code == 403 || code == 429) {
            // the API's 60 requests an hour per address are shared by everyone behind one NAT; the web pages are not
            Log.info("update check: the API answered HTTP " + code + " (rate limit), asking the release page instead");
            return new Releases(webLatest(rev), "web /releases/latest", null);
         }
         throw new IOException("HTTP " + code + " from " + API);
      } catch (Exception e) {
         return new Releases(null, "", e.toString());
      }
   }

   /**
    * The newest release without the API: github.com/.../releases/latest redirects to its tag (no rate limit); the
    * asset URL follows from the tag, a one-byte range request proves it exists and gives its size, and its build
    * time (the zip's build-info.properties, read through the central directory) stands in for the publish date.
    * Null when the latest release is for another game revision.
    */
   static Release webLatest(String rev) throws Exception {
      HttpRequest req = HttpRequest.newBuilder(URI.create(WEB_LATEST)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", "PZ_Optimization-updater").GET().build();
      HttpResponse<Void> resp = noRedirectClient().send(req, HttpResponse.BodyHandlers.discarding());
      String loc = resp.headers().firstValue("Location").orElse("");
      int i = loc.lastIndexOf("/tag/");
      if (resp.statusCode() / 100 != 3 || i < 0) {
         throw new IOException("the release page answered HTTP " + resp.statusCode());
      }
      String tag = loc.substring(i + 5);
      if (!tag.startsWith("win-" + rev + "-")) {
         return null;
      }
      String url = RELEASES_PAGE + "/download/" + tag + "/pzopt-" + rev + "-classes.zip";
      HttpRequest probe = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", "PZ_Optimization-updater").header("Range", "bytes=0-0").GET().build();
      HttpResponse<byte[]> pr = client().send(probe, HttpResponse.BodyHandlers.ofByteArray());
      String cr = pr.headers().firstValue("Content-Range").orElse("");
      if (pr.statusCode() != 206 || !cr.contains("/")) {
         throw new IOException("release asset probe answered HTTP " + pr.statusCode());
      }
      long size = Long.parseLong(cr.substring(cr.indexOf('/') + 1).strip());
      UpdateDelta.HttpSource src = new UpdateDelta.HttpSource(client(), url, size);
      UpdateDelta.Directory d = UpdateDelta.directory(src);
      String published = "";
      for (UpdateDelta.Entry e : d.entries()) {
         if (e.name().equals(BUILD_INFO)) {
            Properties p = new Properties();
            p.load(new java.io.ByteArrayInputStream(UpdateDelta.fetch(src, List.of(e), pool(), null).get(BUILD_INFO)));
            published = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(Long.parseLong(p.getProperty("built", "0"))));
         }
      }
      return new Release(tag, tagCommit(tag), published, "", RELEASES_PAGE + "/tag/" + tag, url, size);
   }

   record Cached(String etag, String body) {
   }

   static Path cacheFile() {
      Path base;
      try {
         base = Path.of(zombie.ZomboidFileSystem.instance.getCacheDir());
      } catch (Throwable t) {
         base = Path.of(System.getProperty("user.home"), "Zomboid");
      }
      return base.resolve("pzopt").resolve("update-releases.json");
   }

   private static Cached readCache() {
      try {
         Path f = cacheFile();
         if (!Files.isRegularFile(f)) {
            return null;
         }
         JSONObject j = new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
         String etag = j.optString("etag", "");
         String body = j.optString("body", "");
         return etag.isEmpty() || body.isEmpty() ? null : new Cached(etag, body);
      } catch (Exception e) {
         return null;
      }
   }

   private static void writeCache(String etag, String body) {
      try {
         Path f = cacheFile();
         Files.createDirectories(f.getParent());
         Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
         Files.writeString(tmp, new JSONObject().put("etag", etag).put("body", body).toString(), StandardCharsets.UTF_8);
         Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (Exception e) {
         Log.info("update check: could not keep the release list: " + e);
      }
   }

   /** Offers {@code r} unless an install already started, and starts preparing it; true when it is the offer now. */
   private static boolean offer(Release r) {
      synchronized (Updater.class) {
         if (installing) {
            return false;
         }
         release = r;
         state = State.AVAILABLE;
      }
      if (Config.UPDATE_PREFETCH && canInstall()) {
         startPrepare(r, false);
      }
      return true;
   }

   /** The check's end state, unless the Workshop copy is already offered (or installing). */
   private static synchronized void settle(State s) {
      if (state == State.CHECKING) {
         state = s;
      }
   }

   private static long builtEpochOf(Release r) {
      try {
         return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(r.published)).getEpochSecond();
      } catch (Exception e) {
         return 0;
      }
   }

   // --- the Steam Workshop copy ----------------------------------------------------------------------

   /**
    * The newest complete copy of the Workshop item for revision {@code rev} in the Steam library that holds the game
    * (Steam keeps an app's Workshop content in the library of the app itself), or null. A copy is complete when its
    * build-info.properties names {@code rev} and a commit, and every file its pzopt-files.txt lists is there (Steam
    * replaces the files of an item one by one while it updates it).
    */
   static Release findWorkshopCopy(Path gameDir, String rev) throws IOException {
      Release best = null;
      for (Path item : workshopItemDirs(gameDir)) {
         Path mod = item.resolve("mods").resolve("PZ_Optimization");
         if (!Files.isDirectory(mod)) {
            continue;
         }
         List<Path> versions;
         try (var s = Files.list(mod)) {
            versions = s.sorted().toList();
         }
         for (Path v : versions) {
            Release r = readWorkshopCopy(v.resolve("pzopt-classes"), rev);
            if (r != null && (best == null || r.published.compareTo(best.published) > 0)) {
               best = r;
            }
         }
      }
      return best;
   }

   /** {@code <steamapps>/workshop/content/108600/<item>} for every steamapps folder above the game (usually one). */
   static List<Path> workshopItemDirs(Path gameDir) {
      List<Path> out = new ArrayList<>();
      Path d;
      try {
         d = gameDir.toRealPath();
      } catch (IOException e) {
         d = gameDir.toAbsolutePath();
      }
      for (; d != null; d = d.getParent()) {
         Path name = d.getFileName();
         if (name != null && name.toString().equalsIgnoreCase("steamapps")) {
            Path item = d.resolve("workshop").resolve("content").resolve(STEAM_APP_ID).resolve(WORKSHOP_ID);
            if (Files.isDirectory(item)) {
               out.add(item);
            }
         }
      }
      return out;
   }

   /** The copy in {@code classes} as a release, or null when it is not a complete copy for {@code rev}. */
   static Release readWorkshopCopy(Path classes, String rev) throws IOException {
      Path info = classes.resolve("pzopt").resolve("build-info.properties");
      Path list = classes.resolve(FILE_LIST);
      if (!Files.isRegularFile(info) || !Files.isRegularFile(list)) {
         return null;
      }
      Properties p = new Properties();
      try (InputStream in = Files.newInputStream(info)) {
         p.load(in);
      }
      String commit = p.getProperty("commit", "");
      if (!rev.equals(p.getProperty("revision", "")) || commit.isEmpty() || "unknown".equals(commit)) {
         return null;
      }
      long built;
      try {
         built = Long.parseLong(p.getProperty("built", ""));
      } catch (NumberFormatException e) {
         return null;
      }
      for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
         String rel = line.strip();
         if (rel.isEmpty() || rel.startsWith("#")) {
            continue;
         }
         if (rel.startsWith("/") || rel.contains("..") || !Files.isRegularFile(classes.resolve(rel))) {
            Log.info("update check: the Steam Workshop copy in " + classes + " lacks " + rel + " (Steam still updating it?), skipped");
            return null;
         }
      }
      String when = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(built));
      String notes = "The files Steam downloaded with the Workshop item, already on this computer: " + classes
            + "\n\nThe change notes are on the Workshop page.";
      return new Release("win-" + rev + "-" + commit, commit, when, notes, WORKSHOP_CHANGELOG, "", -1, classes);
   }

   /**
    * The newest release (by publish date; the API's order is by the tagged commit's date) that has the zip
    * for {@code rev} among its assets, or null.
    */
   static Release pickRelease(JSONArray releases, String rev) {
      String asset = "pzopt-" + rev + "-classes.zip";
      Release best = null;
      for (int i = 0; i < releases.length(); i++) {
         JSONObject rel = releases.getJSONObject(i);
         if (rel.optBoolean("draft", false)) {
            continue;
         }
         JSONArray assets = rel.optJSONArray("assets");
         if (assets == null) {
            continue;
         }
         for (int j = 0; j < assets.length(); j++) {
            JSONObject a = assets.getJSONObject(j);
            if (!asset.equals(a.optString("name"))) {
               continue;
            }
            String tag = rel.optString("tag_name", "");
            String published = rel.optString("published_at", "");
            if (best == null || published.compareTo(best.published) > 0) {
               best = new Release(tag, tagCommit(tag), published, rel.optString("body", ""), rel.optString("html_url", RELEASES_PAGE),
                     a.optString("browser_download_url", ""), a.optLong("size", -1));
            }
         }
      }
      return best;
   }

   /** The commit part of a {@code win-<revision>-<commit>} tag. */
   static String tagCommit(String tag) {
      int i = tag.lastIndexOf('-');
      return i < 0 ? tag : tag.substring(i + 1);
   }

   /**
    * A release is an update when it is not this build's commit ("-dirty" and length differences aside) and
    * it was published after this build was made (a from-source build ahead of the last release is not
    * behind it). A build without a commit stamp never updates itself.
    */
   static boolean isNewer(Release r, String ourCommit, long builtEpoch) {
      if (ourCommit == null || ourCommit.isEmpty() || "unknown".equals(ourCommit)) {
         return false;
      }
      String ours = ourCommit.endsWith("-dirty") ? ourCommit.substring(0, ourCommit.length() - 6) : ourCommit;
      if (ours.isEmpty() || r.commit.isEmpty() || ours.startsWith(r.commit) || r.commit.startsWith(ours)) {
         return false;
      }
      if (builtEpoch > 0 && !r.published.isEmpty()) {
         try {
            long published = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(r.published)).getEpochSecond();
            if (published <= builtEpoch) {
               return false;
            }
         } catch (Exception ignored) {
            // an unparsable date: only the commit decides
         }
      }
      return true;
   }

   private static long builtEpoch() {
      try {
         return Long.parseLong(BuildInfo.get("built"));
      } catch (Exception e) {
         return 0;
      }
   }

   // --- preparing (in the background once offered, else on the click) --------------------------------

   /**
    * Starts preparing {@code r} unless that already runs. A background preparation ({@code full=false}) fetches the
    * changed entries only while they stay under {@code updatePrefetchMaxKb} and never falls back to the whole zip;
    * the click's ({@code full=true}) does whatever it takes.
    */
   private static CompletableFuture<Prepared> startPrepare(Release r, boolean full) {
      synchronized (PREP) {
         if (prepFor == r && prepJob != null) {
            if (!full) {
               return prepJob;
            }
            Prepared done = prepJob.isCompletedExceptionally() ? null : prepJob.getNow(null);
            if (!prepJob.isDone() || (done != null && done.complete)) {
               return prepJob; // running (the click waits for it) or complete
            }
         }
         Path dir = gameDir();
         long t0 = System.nanoTime();
         if (full) {
            progress = 0;
         }
         CompletableFuture<Prepared> job = CompletableFuture.supplyAsync(() -> {
            try {
               Prepared p = prepare(r, dir, full, full ? Long.MAX_VALUE : Config.UPDATE_PREFETCH_MAX_KB * 1024L, pct -> progress = pct);
               Log.info(String.format("update %s of %s: %s in %.0f ms", full ? "download" : "prefetch", r.tag, p.how, (System.nanoTime() - t0) / 1e6));
               return p;
            } catch (Exception e) {
               throw new java.util.concurrent.CompletionException(e);
            }
         }, Updater::onOwnThread);
         prepFor = r;
         prepJob = job;
         return job;
      }
   }

   /** The release's files and the content of the changed ones, by the cheapest way that works. */
   static Prepared prepare(Release r, Path dir, boolean full, long maxBytes, IntConsumer progress) throws Exception {
      ExecutorService p = pool();
      if (r.localDir != null) {
         UpdateDelta.FolderChanges fc = UpdateDelta.folderChanges(r.localDir, dir, p);
         return new Prepared(r, fc.names(), fc.changed(), "Steam Workshop copy, " + fc.changed().size() + " of " + fc.names().size() + " files differ", true);
      }
      if (r.zipUrl.isEmpty()) {
         throw new IOException("the release has no download url");
      }
      Exception rangeFailure = null;
      if (r.zipSize > 0) {
         try {
            UpdateDelta.HttpSource src = new UpdateDelta.HttpSource(client(), r.zipUrl, r.zipSize);
            UpdateDelta.Directory d = UpdateDelta.directory(src);
            UpdateDelta.Plan plan = UpdateDelta.plan(d, dir, p);
            List<String> names = names(d);
            if (plan.changedCompressed() > maxBytes) {
               return new Prepared(r, names, Map.of(), "left for the click: " + plan.changed().size() + " files, " + plan.changedCompressed() + " bytes", false);
            }
            Map<String, byte[]> changed = UpdateDelta.fetch(src, plan.changed(), p, progress);
            return new Prepared(r, names, changed, "delta, " + plan.changed().size() + " of " + names.size() + " files, "
                  + plan.changedCompressed() + " of " + r.zipSize + " bytes (" + src.describe() + ")", true);
         } catch (UpdateDelta.RangeUnsupported e) {
            rangeFailure = e;
         } catch (Exception e) {
            rangeFailure = e;
            Log.info("update: the delta failed (" + e + ")");
         }
      }
      if (!full) {
         return new Prepared(r, List.of(), Map.of(), "left for the click (" + rangeFailure + ")", false);
      }
      Path zip = dir.resolve("pzopt-update.tmp.zip");
      try {
         int parts = UpdateDelta.downloadSegmented(client(), r.zipUrl, r.zipSize, zip, SEGMENTS, p, progress);
         try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(zip)) {
            UpdateDelta.Directory d = UpdateDelta.directory(fs);
            UpdateDelta.Plan plan = UpdateDelta.plan(d, dir, p);
            Map<String, byte[]> changed = UpdateDelta.fetch(fs, plan.changed(), p, null);
            return new Prepared(r, names(d), changed, "whole zip in " + parts + " parts, " + plan.changed().size() + " files differ"
                  + (rangeFailure != null ? " (" + rangeFailure.getMessage() + ")" : ""), true);
         }
      } finally {
         Files.deleteIfExists(zip);
      }
   }

   static List<String> names(UpdateDelta.Directory d) {
      List<String> out = new ArrayList<>(d.entries().size());
      for (UpdateDelta.Entry e : d.entries()) {
         out.add(e.name());
      }
      return out;
   }

   // --- install --------------------------------------------------------------------------------------

   /** Installs the offered release on a daemon thread; false if there is nothing to install. */
   public static boolean install() {
      Release r;
      synchronized (Updater.class) { // the GitHub answer may replace a Workshop offer until the install starts
         r = release;
         if (r == null || state != State.AVAILABLE || installing || !canInstall()) {
            return false;
         }
         installing = true;
         state = r.localDir != null ? State.INSTALLING : State.DOWNLOADING;
      }
      Thread t = new Thread(() -> runInstall(r), "pzopt-update-install");
      t.setDaemon(true);
      t.start();
      return true;
   }

   private static void runInstall(Release r) {
      Path dir = gameDir();
      long t0 = System.nanoTime();
      try {
         Prepared p;
         try {
            p = startPrepare(r, true).join(); // the prefetch's result, or its end, or a download of its own
         } catch (java.util.concurrent.CompletionException e) {
            Log.info("update: the prefetch failed (" + e.getCause() + "), preparing again");
            p = startPrepare(r, true).join();
         }
         if (!p.complete) {
            p = startPrepare(r, true).join();
         }
         long t1 = System.nanoTime();
         state = State.INSTALLING;
         Map<String, String> installed = applyPrepared(p, dir, Overrides.jarRevision(), r.localDir != null ? "the Steam Workshop copy" : "the zip");
         message = "installed " + r.tag + (r.localDir != null ? " from the Steam Workshop copy" : "") + " (" + p.changed.size() + " of "
               + installed.size() + " files changed)";
         state = State.INSTALLED;
         Log.info(String.format("%s; waited %.0f ms for the files, wrote them in %.0f ms; restart the game to load it", message,
               (t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6));
      } catch (Throwable e) {
         Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
         state = State.ERROR;
         message = "update failed: " + c.getMessage();
         Log.warn("update failed: " + c);
      } finally {
         installing = false;
      }
   }

   /**
    * Checks the release is for this revision, writes its changed files, deletes the previous install's files it no
    * longer has, writes the manifest. Returns rel path → sha256 of what is installed now.
    */
   static Map<String, String> applyPrepared(Prepared p, Path dir, String rev, String what) throws Exception {
      if (p.names.isEmpty()) {
         throw new IOException(what + " is empty");
      }
      if (!p.names.contains(BUILD_INFO)) {
         throw new IOException("not a PZ_Optimization release (no " + BUILD_INFO + ")");
      }
      byte[] info = p.changed.get(BUILD_INFO);
      Properties props = new Properties();
      props.load(new java.io.ByteArrayInputStream(info != null ? info : Files.readAllBytes(dir.resolve(BUILD_INFO))));
      String built = props.getProperty("revision", "");
      if (!built.equals(rev)) {
         throw new IOException(what + " was built for game revision " + built + " but this game is " + rev);
      }
      if (!p.changed.isEmpty()) {
         AotCache.onInstallChanging(dir); // the loose files change: launcher back to them, jar and cache dropped
      }
      Map<String, String> installed = UpdateDelta.apply(p.names, p.changed, dir, previousFiles(dir), previousHashes(dir), pool());
      writeManifest(dir, rev, installed);
      return installed;
   }

   /** The whole path for a zip on disk (tests, the bench): compare, extract what changed, apply. */
   static Map<String, String> swap(Path zip, Path dir, String rev) throws Exception {
      try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(zip)) {
         UpdateDelta.Directory d = UpdateDelta.directory(fs);
         UpdateDelta.Plan plan = UpdateDelta.plan(d, dir, pool());
         Map<String, byte[]> changed = UpdateDelta.fetch(fs, plan.changed(), pool(), null);
         return applyPrepared(new Prepared(null, names(d), changed, "zip", true), dir, rev, "the zip");
      }
   }

   /** The same for an unpacked copy (the Steam Workshop item's pzopt-classes folder); Steam's folder is only read. */
   static Map<String, String> swapFolder(Path src, Path dir, String rev) throws Exception {
      if (!Files.isRegularFile(src.resolve(BUILD_INFO))) {
         throw new IOException(src + " is not an unpacked PZ_Optimization release (no " + BUILD_INFO + ")");
      }
      UpdateDelta.FolderChanges fc = UpdateDelta.folderChanges(src, dir, pool());
      return applyPrepared(new Prepared(null, fc.names(), fc.changed(), "folder", true), dir, rev, "the Steam Workshop copy");
   }

   static void writeManifest(Path dir, String rev, Map<String, String> installed) throws IOException {
      StringBuilder sb = new StringBuilder();
      sb.append("# files written by the in-game updater (pzopt.Updater) - do not edit\n");
      sb.append("# revision=").append(rev).append(" installed=")
            .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))).append('\n');
      for (Map.Entry<String, String> e : installed.entrySet()) {
         sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
      }
      Path tmp = dir.resolve(MANIFEST + ".pzopt-new");
      Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
      Files.move(tmp, dir.resolve(MANIFEST), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
   }

   /** What the previous install wrote: the manifest's paths, else the zip's own file list. */
   static List<String> previousFiles(Path dir) throws IOException {
      return new ArrayList<>(previousHashesOrNames(dir).keySet());
   }

   /** The previous manifest's rel path → sha256 (installers and this updater write real hashes). */
   static Map<String, String> previousHashes(Path dir) throws IOException {
      Map<String, String> m = previousHashesOrNames(dir);
      m.values().removeIf(v -> v.length() != 64);
      return m;
   }

   private static Map<String, String> previousHashesOrNames(Path dir) throws IOException {
      Map<String, String> out = new java.util.LinkedHashMap<>();
      Path manifest = dir.resolve(MANIFEST);
      Path list = Files.isRegularFile(manifest) ? manifest : dir.resolve(FILE_LIST);
      if (!Files.isRegularFile(list)) {
         return out;
      }
      for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
         line = line.strip();
         if (line.isEmpty() || line.startsWith("#")) {
            continue;
         }
         int sp = line.indexOf(' ');
         String rel = sp < 0 ? line : line.substring(0, sp);
         if (rel.startsWith("/") || rel.contains("..")) {
            continue;
         }
         out.put(rel, sp < 0 ? "" : line.substring(sp + 1).strip());
      }
      return out;
   }

   static void removeEmptyParents(Path root, Path d) {
      while (d != null && !d.equals(root) && d.startsWith(root)) {
         try (var s = Files.list(d)) {
            if (s.findAny().isPresent()) {
               return;
            }
         } catch (IOException e) {
            return;
         }
         try {
            Files.delete(d);
         } catch (IOException e) {
            return;
         }
         d = d.getParent();
      }
   }

   static void deleteTree(Path p) {
      if (!Files.exists(p)) {
         return;
      }
      try (var s = Files.walk(p)) {
         s.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
            try {
               Files.deleteIfExists(q);
            } catch (IOException ignored) {
            }
         });
      } catch (IOException ignored) {
      }
   }

   static String sha256(Path p) throws Exception {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(p)) {
         byte[] buf = new byte[1 << 16];
         int n;
         while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
         }
      }
      return UpdateDelta.hex(md.digest());
   }

   /**
    * The folder the loose classes live in: the one holding projectzomboid.jar on the class path (the launcher
    * JSON lists "." then the jar, macOS' JavaAppLauncher absolute paths), else the working directory.
    */
   static Path gameDir() {
      String cp = System.getProperty("java.class.path", "");
      for (String entry : cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
         if (entry.endsWith("projectzomboid.jar")) {
            Path parent = Path.of(entry).toAbsolutePath().getParent();
            if (parent != null && Files.isDirectory(parent)) {
               return parent;
            }
         }
      }
      return Path.of("").toAbsolutePath();
   }

   // --- http -----------------------------------------------------------------------------------------

   /** One HTTP/2 client for the process: one TLS handshake per host, range requests multiplexed on it. */
   static HttpClient client() {
      HttpClient c = client;
      if (c == null) {
         synchronized (Updater.class) {
            if (client == null) {
               client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(HttpClient.Redirect.NORMAL)
                     .connectTimeout(Duration.ofSeconds(15)).build();
            }
            c = client;
         }
      }
      return c;
   }

   private static HttpClient noRedirectClient() {
      HttpClient c = noRedirectClient;
      if (c == null) {
         synchronized (Updater.class) {
            if (noRedirectClient == null) {
               noRedirectClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).followRedirects(HttpClient.Redirect.NEVER)
                     .connectTimeout(Duration.ofSeconds(15)).build();
            }
            c = noRedirectClient;
         }
      }
      return c;
   }

   /** Runs an orchestration job (it waits on {@link #pool()}, so it must not take one of its threads). */
   private static void onOwnThread(Runnable job) {
      Thread t = new Thread(job, "pzopt-update-job");
      t.setDaemon(true);
      t.start();
   }

   /** Workers for the comparison, the range requests, inflating and writing (daemon threads, created once). */
   static ExecutorService pool() {
      ExecutorService p = pool;
      if (p == null) {
         synchronized (Updater.class) {
            if (pool == null) {
               pool = UpdateDelta.pool("pzopt-update", Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));
            }
            p = pool;
         }
      }
      return p;
   }

   /** Downloads {@code url} to {@code out}, reporting 0..100 (99 until the last byte is in); checks the length. */
   static void fetch(String url, long expectedSize, Path out, IntConsumer progressOut) throws Exception {
      fetchWith(client(), url, expectedSize, out, progressOut);
   }

   static void fetchWith(HttpClient c, String url, long expectedSize, Path out, IntConsumer progressOut) throws Exception {
      HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
            .header("User-Agent", "PZ_Optimization-updater").header("Accept", "application/octet-stream").GET().build();
      HttpResponse<InputStream> resp = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
      if (resp.statusCode() != 200) {
         throw new IOException("download returned HTTP " + resp.statusCode());
      }
      long total = resp.headers().firstValueAsLong("Content-Length").orElse(expectedSize);
      long done = 0;
      byte[] buf = new byte[1 << 16];
      try (InputStream in = resp.body(); var o = Files.newOutputStream(out)) {
         int n;
         while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            done += n;
            if (total > 0 && progressOut != null) {
               progressOut.accept((int) Math.min(99, done * 100 / total));
            }
         }
      }
      if (total > 0 && done != total) {
         throw new IOException("download truncated at " + done + " of " + total + " bytes");
      }
      if (progressOut != null) {
         progressOut.accept(100);
      }
   }

   static String get(String url, String accept) throws Exception {
      HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
            .header("User-Agent", "PZ_Optimization-updater").header("Accept", accept).GET().build();
      HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (resp.statusCode() != 200) {
         throw new IOException("HTTP " + resp.statusCode() + " from " + url);
      }
      return resp.body();
   }
}
