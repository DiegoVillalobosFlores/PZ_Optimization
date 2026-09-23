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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The main menu's "Update PZ Optimization" item (media/lua/client/pzopt/pzopt_mainscreen_update.lua).
 *
 * Once per boot, when the main menu asks ({@link #check()}), a daemon thread lists the GitHub releases of
 * {@link #REPO_SLUG} and picks the newest one that carries {@code pzopt-<revision>-classes.zip} for the
 * running game revision (the assets are per revision: the runtime guard would disable any other build).
 * That release is an update when its tag's commit ({@code win-<revision>-<commit>}) is not the commit
 * this build was made from and it was published after this build ({@code commit=} and {@code built=} in
 * build-info.properties; a from-source build newer than the last release stays quiet).
 *
 * {@link #install()} downloads the zip next to the game folder, checks that it is a release for this
 * revision, unpacks it into a temporary folder on the same file system, moves every file over the installed
 * one, deletes what the previous install listed and the new zip no longer has, and rewrites
 * {@code pzopt-installed.txt} in the installers' format (install.sh / install.ps1 / scripts/pzopt.sh read it back
 * for --status and --uninstall). Classes the JVM already loaded stay as they are, so the game has to be
 * restarted afterwards; the Lua says so and offers to quit. Without a manifest (a hand-unpacked zip) the
 * item only shows the release page.
 *
 * Every state is a string the Lua polls once a frame through the PerformanceSettings forwards.
 */
public final class Updater {
   public static final String REPO_SLUG = "xD3I/PZ_Optimization";
   static final String API = "https://api.github.com/repos/" + REPO_SLUG + "/releases?per_page=30";
   static final String RELEASES_PAGE = "https://github.com/" + REPO_SLUG + "/releases";
   static final String MANIFEST = "pzopt-installed.txt";
   static final String FILE_LIST = "pzopt-files.txt";

   /** idle → checking → (up-to-date | available | error); available → downloading → installing → (installed | error). */
   public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, INSTALLED, ERROR }

   private static volatile State state = State.IDLE;
   private static volatile Release release;
   private static volatile String message = "";
   private static volatile int progress;   // 0..100 of the download
   private static volatile boolean started;
   private static volatile boolean installing;

   /** One release with the zip for this revision. */
   static final class Release {
      final String tag;
      final String commit;
      final String published;
      final String notes;
      final String pageUrl;
      final String zipUrl;
      final long zipSize;

      Release(String tag, String commit, String published, String notes, String pageUrl, String zipUrl, long zipSize) {
         this.tag = tag;
         this.commit = commit;
         this.published = published;
         this.notes = notes;
         this.pageUrl = pageUrl;
         this.zipUrl = zipUrl;
         this.zipSize = zipSize;
      }
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

   /** True when the running copy was installed by an installer (there is a manifest to replace). */
   public static boolean canInstall() {
      Path dir = gameDir();
      return Files.isRegularFile(dir.resolve(MANIFEST)) || Files.isRegularFile(dir.resolve(FILE_LIST));
   }

   // --- check ----------------------------------------------------------------------------------------

   /** Starts the release check once per boot; later calls are no-ops. */
   public static void check() {
      if (started) {
         return;
      }
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
      if (Harness.REQUESTED || Harness.active()) { // REQUESTED: at the main menu the harness is still idle (it starts with the world)
         Log.info("update check skipped in a harness run");
         return;
      }
      state = State.CHECKING;
      Thread t = new Thread(Updater::runCheck, "pzopt-update-check");
      t.setDaemon(true);
      t.start();
   }

   private static void runCheck() {
      try {
         String rev = Overrides.jarRevision();
         String body = get(API, "application/vnd.github+json");
         Release r = pickRelease(new JSONArray(body), rev);
         if (r == null) {
            state = State.UP_TO_DATE;
            Log.info("update check: no release carries pzopt-" + rev + "-classes.zip");
            return;
         }
         if (Config.DEV_UPDATE_OFFER || isNewer(r, installedCommit(), builtEpoch())) {
            release = r;
            state = State.AVAILABLE;
            Log.info("update available: " + r.tag + " (published " + r.published + "), this build is " + installedCommit()
                  + (canInstall() ? "" : "; no " + MANIFEST + ", the menu item only opens the release page"));
         } else {
            state = State.UP_TO_DATE;
            Log.info("update check: newest release for " + rev + " is " + r.tag + ", this build is " + installedCommit() + ", up to date");
         }
      } catch (Exception e) {
         state = State.ERROR;
         message = "update check failed: " + e;
         Log.warn(message);
      }
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

   // --- install --------------------------------------------------------------------------------------

   /** Downloads and installs the offered release on a daemon thread; false if there is nothing to install. */
   public static boolean install() {
      Release r = release;
      if (r == null || state != State.AVAILABLE || installing || !canInstall()) {
         return false;
      }
      installing = true;
      progress = 0;
      state = State.DOWNLOADING;
      Thread t = new Thread(() -> runInstall(r), "pzopt-update-install");
      t.setDaemon(true);
      t.start();
      return true;
   }

   private static void runInstall(Release r) {
      Path dir = gameDir();
      Path zip = dir.resolve("pzopt-update.tmp.zip");
      Path stage = dir.resolve("pzopt-update.tmp");
      try {
         download(r, zip);
         state = State.INSTALLING;
         AotCache.onInstallChanging(dir); // the loose files change: launcher back to them, jar and cache dropped
         String rev = Overrides.jarRevision();
         Map<String, String> installed = swap(zip, stage, dir, rev);
         message = "installed " + r.tag + " (" + installed.size() + " files)";
         state = State.INSTALLED;
         Log.info(message + "; restart the game to load it");
      } catch (Exception e) {
         state = State.ERROR;
         message = "update failed: " + e.getMessage();
         Log.warn("update failed: " + e);
      } finally {
         deleteTree(stage);
         try {
            Files.deleteIfExists(zip);
         } catch (IOException ignored) {
         }
         installing = false;
      }
   }

   private static void download(Release r, Path zip) throws Exception {
      if (r.zipUrl.isEmpty()) {
         throw new IOException("the release has no download url");
      }
      fetch(r.zipUrl, r.zipSize, zip, p -> progress = p);
   }

   /** Downloads {@code url} to {@code out}, reporting 0..100 (99 until the last byte is in); checks the length. */
   static void fetch(String url, long expectedSize, Path out, java.util.function.IntConsumer progressOut) throws Exception {
      HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
            .header("User-Agent", "PZ_Optimization-updater").header("Accept", "application/octet-stream").GET().build();
      HttpResponse<InputStream> resp = client().send(req, HttpResponse.BodyHandlers.ofInputStream());
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
            if (total > 0) {
               progressOut.accept((int) Math.min(99, done * 100 / total));
            }
         }
      }
      if (total > 0 && done != total) {
         throw new IOException("download truncated at " + done + " of " + total + " bytes");
      }
      progressOut.accept(100);
   }

   /**
    * Unpacks {@code zip} into {@code stage}, moves every file into {@code dir}, deletes the previous install's
    * files the zip no longer has, writes the manifest. Returns rel path → sha256 of what is installed now.
    */
   static Map<String, String> swap(Path zip, Path stage, Path dir, String rev) throws Exception {
      List<String> files = new ArrayList<>();
      try (ZipFile z = new ZipFile(zip.toFile())) {
         ZipEntry info = z.getEntry("pzopt/build-info.properties");
         if (info == null) {
            throw new IOException("not a PZ_Optimization release zip (no pzopt/build-info.properties)");
         }
         Properties p = new Properties();
         try (InputStream in = z.getInputStream(info)) {
            p.load(in);
         }
         String zipRev = p.getProperty("revision", "");
         if (!zipRev.equals(rev)) {
            throw new IOException("the zip was built for game revision " + zipRev + " but this game is " + rev);
         }
         deleteTree(stage);
         Files.createDirectories(stage);
         Enumeration<? extends ZipEntry> en = z.entries();
         while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) {
               continue;
            }
            String rel = e.getName();
            Path target = stage.resolve(rel).normalize();
            if (rel.startsWith("/") || rel.contains("\\") || !target.startsWith(stage)) {
               throw new IOException("refusing zip entry " + rel);
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = z.getInputStream(e)) {
               Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            files.add(rel);
         }
      }
      if (files.isEmpty()) {
         throw new IOException("the zip is empty");
      }
      files.sort(null);

      List<String> previous = previousFiles(dir);
      Map<String, String> installed = new LinkedHashMap<>();
      for (String rel : files) {
         Path src = stage.resolve(rel);
         Path dst = dir.resolve(rel);
         installed.put(rel, sha256(src));
         Files.createDirectories(dst.getParent());
         Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
      }
      for (String rel : previous) {
         if (!installed.containsKey(rel)) {
            Files.deleteIfExists(dir.resolve(rel));
            removeEmptyParents(dir, dir.resolve(rel).getParent());
         }
      }
      StringBuilder sb = new StringBuilder();
      sb.append("# files written by the in-game updater (pzopt.Updater) - do not edit\n");
      sb.append("# revision=").append(rev).append(" installed=")
            .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))).append('\n');
      for (Map.Entry<String, String> e : installed.entrySet()) {
         sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
      }
      Files.writeString(dir.resolve(MANIFEST), sb.toString(), StandardCharsets.UTF_8);
      deleteTree(stage);
      return installed;
   }

   /** What the previous install wrote: the manifest's paths, else the zip's own file list. */
   static List<String> previousFiles(Path dir) throws IOException {
      List<String> out = new ArrayList<>();
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
         out.add(rel);
      }
      return out;
   }

   private static void removeEmptyParents(Path root, Path d) {
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

   private static void deleteTree(Path p) {
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
      StringBuilder sb = new StringBuilder();
      for (byte b : md.digest()) {
         sb.append(String.format("%02x", b));
      }
      return sb.toString();
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

   private static HttpClient client() {
      return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
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
