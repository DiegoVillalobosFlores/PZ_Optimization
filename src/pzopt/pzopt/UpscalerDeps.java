package pzopt;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Enhancements tab's "Install DLSS files" button (docs/plan-upscalers.md): the native files DLSS needs that a
 * release does not carry (the maintainer's decision of 2026-09-22: 58 MB of NVIDIA library, a Windows-useless .so,
 * and the Workshop refuses .so files), fetched on request into the game's {@code natives/} folder.
 *
 * <p>{@link #check()} (once, on a daemon thread) decides what this machine needs. Only Linux x86-64 has a shim build;
 * the NVIDIA driver's NGX runtime ({@code libnvidia-ngx.so.1}) and the Vulkan loader ({@code libvulkan.so.1}) are
 * system parts the game cannot install, so their absence is reported, not fixed. When those are there and the two
 * files are not, {@link #install()} downloads {@link #ASSET} from the newest GitHub release that carries it: our shim
 * and {@link #FILE_LIST} (name, sha256, and for NVIDIA's DLSS library the URL of that file in NVIDIA's own DLSS
 * repository at the pinned SDK version). NVIDIA's library is fetched from NVIDIA, never re-hosted by this project (the
 * DLSS SDK license allows distributing it only as part of an application, not stand-alone). Every file is checked
 * against its sha256 before it is moved into {@code natives/}; then {@link #MARKER} is written. The upscaler reads its
 * settings at launch, so the game has to be restarted afterwards.
 *
 * <p>The files are not added to {@code pzopt-installed.txt}: the in-game updater deletes what a new release zip no
 * longer lists, which would remove them on every update. Every state is a string the Lua polls through the
 * PerformanceSettings forwards.
 */
public final class UpscalerDeps {
   private UpscalerDeps() {
   }

   /** What one platform downloads: the release asset, the shim's file name, the prefix of NVIDIA's DLSS library. */
   record Platform(String asset, String shim, String dlssPrefix) {
   }

   static final Platform LINUX = new Platform("pzopt-dlss-linux-x64.zip", "libpzopt_ngx64.so", "libnvidia-ngx-dlss.so.");
   /** Windows: the shim DLL is built with MSVC (docs/dlss-windows-build.md), NVIDIA's DLL is nvngx_dlss.dll. */
   static final Platform WINDOWS = new Platform("pzopt-dlss-windows-x64.zip", "pzopt_ngx64.dll", "nvngx_dlss");
   static final Platform PLATFORM = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? WINDOWS : LINUX;
   public static final String ASSET = LINUX.asset();
   static final String FILE_LIST = "pzopt-dlss-files.txt";
   static final String MARKER = "pzopt-dlss-installed.txt";
   static final String SHIM = LINUX.shim();
   static final String DLSS_PREFIX = LINUX.dlssPrefix();
   /** The only place a listed URL may point to: NVIDIA's DLSS SDK repository (its raw files redirect to raw.githubusercontent.com). */
   static final String NVIDIA_SOURCE = "https://github.com/NVIDIA/DLSS/";

   /** idle → checking → (unsupported | missing | installed | error); missing → downloading → installing → (done | error). */
   public enum State { IDLE, CHECKING, UNSUPPORTED, MISSING, INSTALLED, DOWNLOADING, INSTALLING, DONE, ERROR }

   private static volatile State state = State.IDLE;
   private static volatile String message = "";
   private static volatile int progress;
   private static volatile boolean started;
   private static volatile boolean installing;

   public static String state() {
      return state.name().toLowerCase(Locale.ROOT);
   }

   public static String message() {
      return message;
   }

   public static int progress() {
      return progress;
   }

   /** Starts the one check of this session (idempotent; the Lua calls it when the tab is built). */
   public static void check() {
      if (started) {
         return;
      }
      started = true;
      state = State.CHECKING;
      Thread t = new Thread(UpscalerDeps::runCheck, "pzopt-upscaler-deps");
      t.setDaemon(true);
      t.start();
   }

   private static void runCheck() {
      try {
         String why = unsupportedReason(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
         if (why != null) {
            message = why;
            state = State.UNSUPPORTED;
            return;
         }
         List<String> system = new ArrayList<>();
         if (PLATFORM == WINDOWS) {
            if (!loadable("nvapi64")) {
               system.add("the NVIDIA driver (nvapi64.dll: an RTX card on NVIDIA's own driver)");
            }
            if (!loadable("vulkan-1")) {
               system.add("the Vulkan runtime (vulkan-1.dll: comes with the NVIDIA driver)");
            }
         } else {
            if (!loadable("libnvidia-ngx.so.1")) {
               system.add("the NVIDIA driver's NGX runtime (libnvidia-ngx.so.1: the proprietary NVIDIA driver on an RTX card)");
            }
            if (!loadable("libvulkan.so.1")) {
               system.add("the Vulkan loader (libvulkan.so.1: package vulkan-icd-loader / libvulkan1)");
            }
         }
         if (!system.isEmpty()) {
            message = "DLSS needs " + String.join(" and ", system) + ", which the game cannot install";
            state = State.UNSUPPORTED;
            return;
         }
         Path natives = Updater.gameDir().resolve("natives");
         if (present(natives, PLATFORM)) {
            message = "the DLSS files are in " + natives;
            state = State.INSTALLED;
         } else {
            message = "the DLSS shim (from this project's releases) and NVIDIA's DLSS library (about 60 MB, from NVIDIA's DLSS repository) can be downloaded";
            state = State.MISSING;
         }
         Log.info("upscaler deps: " + state() + ": " + message);
      } catch (Throwable t) {
         message = "check failed: " + t;
         state = State.ERROR;
         Log.warn("upscaler deps: " + message);
      }
   }

   /** Why this platform has no DLSS build, or null (Linux or Windows on x86-64). */
   static String unsupportedReason(String os, String arch) {
      String o = os.toLowerCase(Locale.ROOT);
      if (o.contains("win")) {
         return arch.equals("amd64") || arch.equals("x86_64") ? null : "no DLSS build for " + arch + " (x86-64 only)";
      }
      if (o.contains("mac") || o.contains("darwin")) {
         return "there is no DLSS on macOS (FSR 1.0 needs no files)";
      }
      if (!o.contains("linux")) {
         return "no DLSS build for " + os;
      }
      if (!arch.equals("amd64") && !arch.equals("x86_64")) {
         return "no DLSS build for " + arch + " (x86-64 only)";
      }
      return null;
   }

   private static boolean loadable(String library) {
      try (Arena arena = Arena.ofConfined()) {
         SymbolLookup.libraryLookup(library, arena);
         return true;
      } catch (Throwable t) {
         return false;
      }
   }

   /** The shim and a DLSS library are in {@code natives} (put there by this button, a checkout build or by hand). */
   static boolean present(Path natives, Platform platform) {
      if (!Files.isRegularFile(natives.resolve(platform.shim()))) {
         return false;
      }
      try (var files = Files.list(natives)) {
         return files.anyMatch(p -> p.getFileName().toString().startsWith(platform.dlssPrefix()));
      } catch (IOException e) {
         return false;
      }
   }

   /** Starts the download and install when the check found the files missing; false when there is nothing to do. */
   public static boolean install() {
      if (state != State.MISSING && state != State.ERROR || installing) {
         return false;
      }
      installing = true;
      progress = 0;
      state = State.DOWNLOADING;
      Thread t = new Thread(UpscalerDeps::runInstall, "pzopt-upscaler-deps-install");
      t.setDaemon(true);
      t.start();
      return true;
   }

   private static void runInstall() {
      Path natives = Updater.gameDir().resolve("natives");
      Path zip = natives.resolve("pzopt-dlss.tmp.zip");
      try {
         Files.createDirectories(natives);
         JSONObject asset = findAsset(PLATFORM, new JSONArray(Updater.get("https://api.github.com/repos/" + Updater.REPO_SLUG + "/releases?per_page=50",
               "application/vnd.github+json")));
         if (asset == null) {
            throw new IOException("no release carries " + PLATFORM.asset());
         }
         Updater.fetch(asset.optString("browser_download_url"), asset.optLong("size", -1), zip, p -> progress = p / 20);
         List<String> names = unpack(PLATFORM, zip, natives, p -> progress = 5 + p * 95 / 100); // NVIDIA's library is the bulk
         state = State.INSTALLING;
         Files.writeString(natives.resolve(MARKER), "# written by the Enhancements tab's DLSS button (pzopt.UpscalerDeps)\n"
               + "release=" + asset.optString("pzoptTag") + "\n" + String.join("\n", names) + "\n", StandardCharsets.UTF_8);
         message = "installed " + String.join(", ", names) + " from " + asset.optString("pzoptTag") + "; restart the game to use DLSS";
         state = State.DONE;
         Log.info("upscaler deps: " + message);
      } catch (Exception e) {
         message = "install failed: " + e.getMessage();
         state = State.ERROR;
         Log.warn("upscaler deps: " + message);
      } finally {
         try {
            Files.deleteIfExists(zip);
            try (var files = Files.list(natives)) { // a download cut short leaves its .tmp
               for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".tmp")
                     && (p.getFileName().toString().startsWith(PLATFORM.shim()) || p.getFileName().toString().startsWith(PLATFORM.dlssPrefix()))).toList()) {
                  Files.deleteIfExists(p);
               }
            }
         } catch (IOException ignored) {
         }
         installing = false;
      }
   }

   /** The platform's asset of the newest (by publish date) non-draft release that has it, with the tag added as pzoptTag. */
   static JSONObject findAsset(Platform platform, JSONArray releases) {
      JSONObject best = null;
      String bestDate = "";
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
            String published = rel.optString("published_at", "");
            if (platform.asset().equals(a.optString("name")) && (best == null || published.compareTo(bestDate) > 0)) {
               best = new JSONObject(a.toString()).put("pzoptTag", rel.optString("tag_name", ""));
               bestDate = published;
            }
         }
      }
      return best;
   }

   /**
    * Checks every file the zip's list names against its sha256 and moves it into {@code dir}; returns the names. A
    * listed file with a URL is downloaded from there (NVIDIA's repository only), the others come out of the zip. Only
    * plain file names of the two expected kinds are accepted.
    */
   static List<String> unpack(Platform platform, Path zip, Path dir, java.util.function.IntConsumer downloadProgress) throws Exception {
      Map<String, String> expected = new LinkedHashMap<>();
      Map<String, String> urls = new LinkedHashMap<>();
      List<String> names = new ArrayList<>();
      try (ZipFile z = new ZipFile(zip.toFile())) {
         ZipEntry list = z.getEntry(FILE_LIST);
         if (list == null) {
            throw new IOException("not a DLSS files zip (no " + FILE_LIST + ")");
         }
         try (InputStream in = z.getInputStream(list)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
               String[] f = line.trim().split("\\s+");
               if (f.length < 2 || f[0].startsWith("#")) {
                  continue;
               }
               if (!safeName(platform, f[0])) {
                  throw new IOException("unexpected file in the list: " + f[0]);
               }
               expected.put(f[0], f[1].toLowerCase(Locale.ROOT));
               if (f.length >= 3) {
                  if (!f[2].startsWith(NVIDIA_SOURCE) || f[2].contains("..")) {
                     throw new IOException("unexpected source for " + f[0] + ": " + f[2]);
                  }
                  urls.put(f[0], f[2]);
               }
            }
         }
         if (!expected.containsKey(platform.shim()) || expected.keySet().stream().noneMatch(n -> n.startsWith(platform.dlssPrefix()))) {
            throw new IOException("the list lacks the shim or the DLSS library");
         }
         for (Map.Entry<String, String> e : expected.entrySet()) {
            Path tmp = dir.resolve(e.getKey() + ".tmp");
            String url = urls.get(e.getKey());
            if (url != null) {
               Updater.fetch(url, -1, tmp, downloadProgress);
            } else {
               ZipEntry entry = z.getEntry(e.getKey());
               if (entry == null) {
                  throw new IOException(e.getKey() + " is listed but not in the zip");
               }
               try (InputStream in = z.getInputStream(entry)) {
                  Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
               }
            }
            String sum = Updater.sha256(tmp);
            if (!sum.equals(e.getValue())) {
               Files.deleteIfExists(tmp);
               throw new IOException(e.getKey() + ": sha256 " + sum + " is not the listed " + e.getValue());
            }
            Files.move(tmp, dir.resolve(e.getKey()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            names.add(e.getKey());
         }
      }
      return names;
   }

   static boolean safeName(Platform platform, String name) {
      return name.matches("[A-Za-z0-9._+-]+") && !name.contains("..") && (name.equals(platform.shim()) || name.startsWith(platform.dlssPrefix()));
   }
}
