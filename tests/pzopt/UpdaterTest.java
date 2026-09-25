package pzopt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;

/**
 * pzopt.Updater without the network: the release pick (newest publish date with the zip for this
 * revision, drafts and other revisions skipped), the "is it newer than this build" rule, and the file
 * swap on a temporary game folder (new files over old, stale ones removed, manifest rewritten in the
 * installers' format, a zip for another revision refused before anything changes), and the Steam Workshop copy
 * (issue #16): found in the game's library, incomplete or foreign-revision copies skipped, never offered as a
 * downgrade, installed through the same swap without touching Steam's folder.
 */
public class UpdaterTest {
   static int failures;

   static void check(boolean ok, String what) {
      if (!ok) {
         failures++;
         System.err.println("FAIL: " + what);
      }
   }

   public static void main(String[] args) throws Exception {
      pick();
      newer();
      swap();
      workshop();
      delta();
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("UpdaterTest ok");
   }

   static String release(String tag, String published, boolean draft, String... assets) {
      StringBuilder a = new StringBuilder();
      for (String name : assets) {
         if (a.length() > 0) {
            a.append(',');
         }
         a.append("{\"name\":\"").append(name).append("\",\"browser_download_url\":\"https://x/").append(name).append("\",\"size\":10}");
      }
      return "{\"tag_name\":\"" + tag + "\",\"published_at\":\"" + published + "\",\"draft\":" + draft
            + ",\"html_url\":\"https://x/" + tag + "\",\"body\":\"notes " + tag + "\",\"assets\":[" + a + "]}";
   }

   static void pick() {
      // the API lists by the tagged commit's date; the newest publish date with our zip must win
      JSONArray rels = new JSONArray("[" + String.join(",",
            release("win-aaaa-1111111", "2026-09-20T10:00:00Z", false, "pzopt-aaaa-classes.zip", "install.sh"),
            release("win-aaaa-2222222", "2026-09-22T10:00:00Z", false, "pzopt-aaaa-classes.zip"),
            release("win-aaaa-3333333", "2026-09-23T10:00:00Z", true, "pzopt-aaaa-classes.zip"),
            release("win-bbbb-4444444", "2026-09-24T10:00:00Z", false, "pzopt-bbbb-classes.zip"),
            release("win-aaaa-5555555", "2026-09-21T10:00:00Z", false, "pzopt-aaaa-classes.zip")) + "]");
      Updater.Release r = Updater.pickRelease(rels, "aaaa");
      check(r != null && "win-aaaa-2222222".equals(r.tag), "newest published release for the revision: " + (r == null ? null : r.tag));
      check(r != null && "2222222".equals(r.commit), "commit from the tag");
      check(r != null && r.zipUrl.endsWith("pzopt-aaaa-classes.zip"), "zip url");
      check(Updater.pickRelease(rels, "cccc") == null, "no release for an unknown revision");
      check("2222222".equals(Updater.tagCommit("win-aaaa-2222222")), "tagCommit");
   }

   static void newer() {
      Updater.Release r = new Updater.Release("win-aaaa-2222222", "2222222", "2026-09-22T10:00:00Z", "", "", "", 0);
      long before = 1789800000L;  // 2026-09-19
      long after = 1790200000L;   // 2026-09-24
      check(Updater.isNewer(r, "1111111", before), "older build, other commit: update");
      check(!Updater.isNewer(r, "2222222", before), "same commit: no update");
      check(!Updater.isNewer(r, "2222222-dirty", before), "same commit, dirty tree: no update");
      check(!Updater.isNewer(r, "1111111", after), "build newer than the release: no update");
      check(Updater.isNewer(r, "1111111", 0), "no build stamp: the commit decides");
      check(!Updater.isNewer(r, "unknown", before), "unknown commit: never");
      check(!Updater.isNewer(r, "", before), "empty commit: never");
      check(!Updater.isNewer(r, "22222", before), "shorter form of the same commit: no update");
   }

   static Path zip(Path dir, String name, String rev, Map<String, String> files) throws Exception {
      Path z = dir.resolve(name);
      try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(z))) {
         out.putNextEntry(new ZipEntry("pzopt/"));
         out.closeEntry();
         out.putNextEntry(new ZipEntry("pzopt/build-info.properties"));
         out.write(("revision=" + rev + "\ncommit=2222222\n").getBytes(StandardCharsets.UTF_8));
         out.closeEntry();
         for (Map.Entry<String, String> e : files.entrySet()) {
            out.putNextEntry(new ZipEntry(e.getKey()));
            out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
         }
      }
      return z;
   }

   static void swap() throws Exception {
      Path tmp = Files.createTempDirectory("pzopt-updater-test");
      Path game = tmp.resolve("game");
      Files.createDirectories(game.resolve("zombie/iso"));
      Files.createDirectories(game.resolve("media/lua/client/pzopt"));
      Files.createDirectories(game.resolve("media/ui/pzopt/old"));
      Files.createDirectories(game.resolve("pzopt"));
      Files.writeString(game.resolve("projectzomboid.jar"), "jar");
      Files.writeString(game.resolve("zombie/iso/IsoChunk.class"), "old chunk");
      Files.writeString(game.resolve("media/lua/client/pzopt/a.lua"), "old a");
      Files.writeString(game.resolve("media/ui/pzopt/old/gone.gif"), "old gif");
      Files.writeString(game.resolve("pzopt/build-info.properties"), "revision=aaaa\ncommit=1111111\n");
      Files.writeString(game.resolve(Updater.MANIFEST), "# files written by install.sh\n# revision=aaaa installed=x\n"
            + "media/lua/client/pzopt/a.lua 00\nmedia/ui/pzopt/old/gone.gif 00\npzopt/build-info.properties 00\nzombie/iso/IsoChunk.class 00\n");
      Files.writeString(game.resolve("keep.txt"), "not ours");

      // a zip for another revision is refused before any file moves
      Path wrong = zip(tmp, "wrong.zip", "bbbb", Map.of("zombie/iso/IsoChunk.class", "wrong"));
      boolean refused = false;
      try {
         Updater.swap(wrong, game, "aaaa");
      } catch (Exception e) {
         refused = e.getMessage().contains("built for game revision bbbb");
      }
      check(refused, "zip for another revision refused");
      check("old chunk".equals(Files.readString(game.resolve("zombie/iso/IsoChunk.class"))), "nothing changed by the refused zip");

      Path good = zip(tmp, "good.zip", "aaaa", Map.of(
            "zombie/iso/IsoChunk.class", "new chunk",
            "media/lua/client/pzopt/a.lua", "new a",
            "media/lua/client/pzopt/b.lua", "new b",
            "pzopt-files.txt", "list"));
      Map<String, String> installed = Updater.swap(good, game, "aaaa");
      check(installed.size() == 5, "five files installed: " + installed.keySet());
      check("new chunk".equals(Files.readString(game.resolve("zombie/iso/IsoChunk.class"))), "class replaced");
      check("new a".equals(Files.readString(game.resolve("media/lua/client/pzopt/a.lua"))), "lua replaced");
      check("new b".equals(Files.readString(game.resolve("media/lua/client/pzopt/b.lua"))), "new lua added");
      check(!Files.exists(game.resolve("media/ui/pzopt/old/gone.gif")), "stale file removed");
      check(!Files.exists(game.resolve("media/ui/pzopt/old")) && !Files.exists(game.resolve("media/ui")), "empty folders of stale files removed");
      check(Files.exists(game.resolve("media/lua")), "shared folders kept");
      check("not ours".equals(Files.readString(game.resolve("keep.txt"))), "foreign file untouched");
      check(noTemp(game), "no temporary files left");
      check("list".equals(Files.readString(game.resolve(Updater.FILE_LIST))), "the zip's pzopt-files.txt installed like install.sh does");
      List<String> manifest = Files.readAllLines(game.resolve(Updater.MANIFEST));
      check(manifest.get(1).startsWith("# revision=aaaa installed="), "manifest header: " + manifest.get(1));
      check(manifest.size() == 2 + 5, "manifest lines: " + manifest.size());
      String chunkLine = manifest.stream().filter(l -> l.startsWith("zombie/iso/IsoChunk.class ")).findFirst().orElse("");
      check(chunkLine.endsWith(" " + Updater.sha256(game.resolve("zombie/iso/IsoChunk.class"))), "manifest sha256 of the installed file");
      List<String> previous = Updater.previousFiles(game);
      check(previous.size() == 5 && previous.contains("media/lua/client/pzopt/b.lua"), "manifest reads back: " + previous);

      // a hostile zip entry is refused
      Path evil = zip(tmp, "evil.zip", "aaaa", Map.of("../escape.txt", "x"));
      boolean escaped = false;
      try {
         Updater.swap(evil, game, "aaaa");
      } catch (Exception e) {
         escaped = e.getMessage().contains("refusing zip entry");
      }
      check(escaped, "zip slip entry refused");
      check(!Files.exists(tmp.resolve("escape.txt")), "no file escaped the stage");
   }

   /** A Workshop copy of commit {@code commit} built at {@code built} for {@code rev} under {@code steamapps}. */
   static Path workshopCopy(Path steamapps, String version, String rev, String commit, long built, Map<String, String> files) throws Exception {
      Path classes = steamapps.resolve("workshop/content/" + Updater.STEAM_APP_ID + "/" + Updater.WORKSHOP_ID
            + "/mods/PZ_Optimization/" + version + "/pzopt-classes");
      Files.createDirectories(classes.resolve("pzopt"));
      Files.writeString(classes.resolve("pzopt/build-info.properties"), "revision=" + rev + "\ncommit=" + commit + "\nbuilt=" + built + "\n");
      StringBuilder list = new StringBuilder("pzopt/build-info.properties\n" + Updater.FILE_LIST + "\n");
      for (Map.Entry<String, String> e : files.entrySet()) {
         Path f = classes.resolve(e.getKey());
         Files.createDirectories(f.getParent());
         Files.writeString(f, e.getValue());
         list.append(e.getKey()).append('\n');
      }
      Files.writeString(classes.resolve(Updater.FILE_LIST), list.toString());
      return classes;
   }

   static void workshop() throws Exception {
      Path tmp = Files.createTempDirectory("pzopt-updater-ws");
      Path steamapps = tmp.resolve("SteamLibrary/steamapps");
      Path game = steamapps.resolve("common/ProjectZomboid/projectzomboid");
      Files.createDirectories(game.resolve("zombie/iso"));
      Files.writeString(game.resolve("projectzomboid.jar"), "jar");
      Files.writeString(game.resolve("zombie/iso/IsoChunk.class"), "old chunk");
      Files.writeString(game.resolve("gone.class"), "stale");
      Files.writeString(game.resolve(Updater.MANIFEST), "# install.sh\nzombie/iso/IsoChunk.class 00\ngone.class 00\n");

      check(Updater.findWorkshopCopy(game, "aaaa") == null, "no Workshop item: nothing");
      long t1 = 1790000000L, t2 = 1790100000L;
      Path v42 = workshopCopy(steamapps, "42", "aaaa", "3333333", t1, Map.of("zombie/iso/IsoChunk.class", "ws chunk"));
      workshopCopy(steamapps, "42.99", "bbbb", "4444444", t2, Map.of("zombie/iso/IsoChunk.class", "other revision"));
      Updater.Release r = Updater.findWorkshopCopy(game, "aaaa");
      check(r != null && r.localDir.equals(v42), "the copy for this revision: " + (r == null ? null : r.localDir));
      check(r != null && "win-aaaa-3333333".equals(r.tag) && "3333333".equals(r.commit), "tag from build-info: " + (r == null ? null : r.tag));
      check(r != null && r.pageUrl.equals(Updater.WORKSHOP_CHANGELOG), "page is the Workshop change notes");

      // the downgrade guard: its build time stands in for the publish date
      check(r != null && Updater.isNewer(r, "1111111", t1 - 3600), "copy built after this build: offered");
      check(r != null && !Updater.isNewer(r, "5555555", t1 + 3600), "this build newer than the copy (a GitHub install): not offered");
      check(r != null && !Updater.isNewer(r, "3333333", t1 - 3600), "same commit: not offered");
      // GitHub replaces the Workshop offer only with a later release
      Updater.Release same = new Updater.Release("win-aaaa-3333333", "3333333", "2026-09-16T12:00:00Z", "", "", "u", 1);
      Updater.Release later = new Updater.Release("win-aaaa-6666666", "6666666", "2026-09-30T12:00:00Z", "", "", "u", 1);
      check(!Updater.isNewer(same, r.commit, t1), "GitHub's copy of the same build keeps the Workshop offer");
      check(Updater.isNewer(later, r.commit, t1), "a later GitHub release replaces the Workshop offer");

      // Steam half-way through an update: a listed file is missing, the copy is skipped
      Files.delete(v42.resolve("zombie/iso/IsoChunk.class"));
      check(Updater.findWorkshopCopy(game, "aaaa") == null, "incomplete copy skipped");
      Files.writeString(v42.resolve("zombie/iso/IsoChunk.class"), "ws chunk");

      // a library reached through a symlink still finds the item
      Path link = tmp.resolve("link");
      Files.createSymbolicLink(link, game);
      check(Updater.findWorkshopCopy(link, "aaaa") != null, "game dir through a symlink");

      // the swap from the folder: same result as the zip path, Steam's copy left whole
      boolean refused = false;
      try {
         Updater.swapFolder(v42, game, "bbbb");
      } catch (Exception e) {
         refused = e.getMessage().contains("built for game revision aaaa");
      }
      check(refused, "Workshop copy for another revision refused");
      Map<String, String> installed = Updater.swapFolder(v42, game, "aaaa");
      check(installed.size() == 3, "three files installed: " + installed.keySet());
      check("ws chunk".equals(Files.readString(game.resolve("zombie/iso/IsoChunk.class"))), "class replaced from the copy");
      check(!Files.exists(game.resolve("gone.class")), "stale file removed");
      check(Files.exists(v42.resolve("zombie/iso/IsoChunk.class")) && Files.exists(v42.resolve(Updater.FILE_LIST)), "Steam's copy untouched");
      check(noTemp(game), "no temporary files left");
      check(Updater.previousFiles(game).contains("pzopt/build-info.properties"), "manifest lists the copy's files");
   }

   static boolean noTemp(Path dir) throws Exception {
      try (var s = Files.walk(dir)) {
         return s.noneMatch(p -> p.getFileName().toString().endsWith(".pzopt-new"));
      }
   }

   /** The delta path: directory parsing, only changed files written, unchanged ones untouched, hashes kept right. */
   static void delta() throws Exception {
      Path tmp = Files.createTempDirectory("pzopt-updater-delta");
      Path game = tmp.resolve("game");
      Files.createDirectories(game);
      Map<String, String> v1 = new java.util.TreeMap<>();
      for (int i = 0; i < 40; i++) {
         v1.put("zombie/c" + i + ".class", "class " + i + " ".repeat(i * 50));
      }
      v1.put("media/big.gif", "x".repeat(300_000));
      Path z1 = zip(tmp, "v1.zip", "aaaa", v1);
      Files.writeString(game.resolve(Updater.FILE_LIST), "x\n"); // installed by hand once: the updater may replace it
      Map<String, String> first = Updater.swap(z1, game, "aaaa");
      check(first.size() == 42, "first install: all files " + first.size());

      // the directory agrees with java.util.zip
      UpdateDelta.Directory d;
      try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(z1)) {
         d = UpdateDelta.directory(fs);
      }
      try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z1.toFile())) {
         long files = zf.stream().filter(e -> !e.isDirectory()).count();
         check(d.entries().size() == files, "directory entries " + d.entries().size() + " vs " + files);
         for (UpdateDelta.Entry e : d.entries()) {
            ZipEntry ze = zf.getEntry(e.name());
            check(ze != null && ze.getCrc() == e.crc() && ze.getSize() == e.size(), "entry matches java.util.zip: " + e.name());
         }
      }

      // v2 changes two files, adds one, drops one
      Map<String, String> v2 = new java.util.TreeMap<>(v1);
      v2.put("zombie/c3.class", "class 3 changed");
      v2.put("zombie/c30.class", "class 30 changed" + "y".repeat(5000));
      v2.put("zombie/new.class", "new");
      v2.remove("zombie/c7.class");
      Path z2 = zip(tmp, "v2.zip", "aaaa", v2);
      Path unchanged = game.resolve("media/big.gif");
      Object keyBefore = Files.readAttributes(unchanged, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
      long mtimeBefore = Files.getLastModifiedTime(unchanged).toMillis();
      Thread.sleep(20);
      UpdateDelta.Plan plan;
      try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(z2)) {
         plan = UpdateDelta.plan(UpdateDelta.directory(fs), game, Updater.pool());
      }
      // build-info is the same text in both zips here, so exactly the three content changes
      check(plan.changed().size() == 3, "plan: 3 changed entries, got " + plan.changed().stream().map(UpdateDelta.Entry::name).toList());
      Map<String, String> second = Updater.swap(z2, game, "aaaa");
      check(second.size() == 42, "second install lists 42 files: " + second.size());
      check("class 3 changed".equals(Files.readString(game.resolve("zombie/c3.class"))), "changed file written");
      check("new".equals(Files.readString(game.resolve("zombie/new.class"))), "new file written");
      check(!Files.exists(game.resolve("zombie/c7.class")), "dropped file removed");
      Object keyAfter = Files.readAttributes(unchanged, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
      check(Files.getLastModifiedTime(unchanged).toMillis() == mtimeBefore && java.util.Objects.equals(keyBefore, keyAfter),
            "unchanged file not rewritten");
      for (Map.Entry<String, String> e : second.entrySet()) {
         check(e.getValue().equals(Updater.sha256(game.resolve(e.getKey()))), "manifest hash of " + e.getKey());
      }
      check(noTemp(game), "no temporary files left after the delta");

      // a corrupted entry is caught by its CRC before anything is written
      byte[] bytes = Files.readAllBytes(z2);
      UpdateDelta.Entry target = null;
      try (UpdateDelta.FileSource fs = new UpdateDelta.FileSource(z2)) {
         for (UpdateDelta.Entry e : UpdateDelta.directory(fs).entries()) {
            if (e.name().equals("zombie/c30.class")) {
               target = e;
            }
         }
      }
      int dataAt = (int) target.offset() + 30 + UpdateDelta.le16(bytes, (int) target.offset() + 26) + UpdateDelta.le16(bytes, (int) target.offset() + 28);
      bytes[dataAt + 2] ^= 0x55;
      Path bad = tmp.resolve("bad.zip");
      Files.write(bad, bytes);
      Files.writeString(game.resolve("zombie/c30.class"), "local edit");
      boolean caught = false;
      try {
         Updater.swap(bad, game, "aaaa");
      } catch (Exception e) {
         caught = true;
      }
      check(caught, "corrupted entry refused");
      check("local edit".equals(Files.readString(game.resolve("zombie/c30.class"))), "nothing written from a corrupted zip");

      // spans: neighbours merge, far entries do not
      List<UpdateDelta.Entry> es = List.of(
            new UpdateDelta.Entry("a", 0, 10, 10, 8, 0, 100),
            new UpdateDelta.Entry("b", 0, 10, 10, 8, 100, 200),
            new UpdateDelta.Entry("c", 0, 10, 10, 8, 10_000_000, 10_000_100));
      List<UpdateDelta.Span> sp = UpdateDelta.spans(es);
      check(sp.size() == 2 && sp.get(0).entries().size() == 2 && sp.get(0).to() == 200, "span merge: " + sp);
   }
}
