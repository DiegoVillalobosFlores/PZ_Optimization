package pzopt;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** The DLSS files button's pure parts: platform rules, file names, release pick, the checked unpack. */
public class UpscalerDepsTest {
   public static void main(String[] args) throws Exception {
      Check.check(UpscalerDeps.unsupportedReason("Linux", "amd64") == null, "linux x86-64 supported");
      Check.check(UpscalerDeps.unsupportedReason("Windows 11", "amd64") != null, "windows not (no shim build)");
      Check.check(UpscalerDeps.unsupportedReason("Mac OS X", "aarch64") != null, "macos not");
      Check.check(UpscalerDeps.unsupportedReason("Linux", "aarch64") != null, "arm64 linux not");

      Check.check(UpscalerDeps.safeName("libpzopt_ngx64.so") && UpscalerDeps.safeName("libnvidia-ngx-dlss.so.310.9.1"), "the two names");
      Check.check(!UpscalerDeps.safeName("../libpzopt_ngx64.so") && !UpscalerDeps.safeName("libLighting64.so")
            && !UpscalerDeps.safeName("libnvidia-ngx-dlss.so.1/../x"), "anything else refused");

      // the newest release carrying the asset wins; drafts and other assets are ignored
      JSONArray rels = new JSONArray()
            .put(release("win-x-aaa", "2026-09-24T00:00:00Z", false, "pzopt-b0bbce05d5-classes.zip", "u0"))
            .put(release("dlss-linux-old", "2026-09-20T00:00:00Z", false, UpscalerDeps.ASSET, "u1"))
            .put(release("dlss-linux-new", "2026-09-23T00:00:00Z", false, UpscalerDeps.ASSET, "u2"))
            .put(release("dlss-linux-draft", "2026-09-25T00:00:00Z", true, UpscalerDeps.ASSET, "u3"));
      JSONObject a = UpscalerDeps.findAsset(rels);
      Check.check(a != null && "u2".equals(a.getString("browser_download_url")) && "dlss-linux-new".equals(a.getString("pzoptTag")), "picked " + a);
      Check.check(UpscalerDeps.findAsset(new JSONArray().put(release("win-x", "2026", false, "other.zip", "u"))) == null, "none carries it");

      // unpack: files out of the zip checked against the list's sha256; a bad sum, a non-NVIDIA url, a missing file refuse
      Path dir = Files.createTempDirectory("pzopt-dlss-test");
      byte[] shim = "shim bytes".getBytes(StandardCharsets.UTF_8), dlss = "dlss bytes".getBytes(StandardCharsets.UTF_8);
      Path good = zip(dir, "good.zip", "libpzopt_ngx64.so " + sha(shim) + "\nlibnvidia-ngx-dlss.so.310.9.1 " + sha(dlss) + "\n", shim, dlss);
      Path out = Files.createDirectories(dir.resolve("natives"));
      List<String> names = UpscalerDeps.unpack(good, out, p -> { });
      Check.check(names.size() == 2 && UpscalerDeps.present(out) && Files.readAllBytes(out.resolve("libpzopt_ngx64.so")).length == shim.length,
            "installed " + names);
      Check.check(fails(zip(dir, "badsum.zip", "libpzopt_ngx64.so " + sha(dlss) + "\nlibnvidia-ngx-dlss.so.310.9.1 " + sha(dlss) + "\n", shim, dlss), dir),
            "a wrong sha256 is refused");
      Check.check(fails(zip(dir, "badurl.zip", "libpzopt_ngx64.so " + sha(shim) + "\nlibnvidia-ngx-dlss.so.310.9.1 " + sha(dlss)
            + " https://example.com/libnvidia-ngx-dlss.so.310.9.1\n", shim, null), dir), "a url outside NVIDIA's repository is refused");
      Check.check(fails(zip(dir, "noshim.zip", "libnvidia-ngx-dlss.so.310.9.1 " + sha(dlss) + "\n", null, dlss), dir), "a list without the shim is refused");
      System.out.println("UpscalerDepsTest ok");
   }

   private static JSONObject release(String tag, String published, boolean draft, String asset, String url) {
      return new JSONObject().put("tag_name", tag).put("published_at", published).put("draft", draft)
            .put("assets", new JSONArray().put(new JSONObject().put("name", asset).put("browser_download_url", url).put("size", 1)));
   }

   private static boolean fails(Path zip, Path dir) {
      try {
         UpscalerDeps.unpack(zip, Files.createTempDirectory(dir, "n"), p -> { });
         return false;
      } catch (Exception e) {
         return true;
      }
   }

   private static Path zip(Path dir, String name, String list, byte[] shim, byte[] dlss) throws Exception {
      Path p = dir.resolve(name);
      try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
         put(z, UpscalerDeps.FILE_LIST, list.getBytes(StandardCharsets.UTF_8));
         if (shim != null) put(z, "libpzopt_ngx64.so", shim);
         if (dlss != null) put(z, "libnvidia-ngx-dlss.so.310.9.1", dlss);
      }
      return p;
   }

   private static void put(ZipOutputStream z, String name, byte[] data) throws Exception {
      z.putNextEntry(new ZipEntry(name));
      z.write(data);
      z.closeEntry();
   }

   private static String sha(byte[] data) throws Exception {
      StringBuilder sb = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) {
         sb.append(String.format("%02x", b));
      }
      return sb.toString();
   }
}
