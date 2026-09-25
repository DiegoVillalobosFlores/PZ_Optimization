package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.util.Properties;

/** options.ini round trip and the Config registry the options tab reads. */
public class UserOptionsTest {
   public static void main(String[] args) throws Exception {
      File dir = Files.createTempDirectory("pzopt-options").toFile();
      File f = new File(dir, "sub" + File.separator + UserOptions.FILE_NAME);
      Properties p = new Properties();
      p.setProperty("workers", "2");
      p.setProperty("bakeBudget", "16");
      p.setProperty("treesInChunkTexture", "false");
      UserOptions.write(f, p);
      Check.check(f.isFile(), "file written with its parent dir");
      String text = Files.readString(f.toPath());
      Check.check(text.indexOf("bakeBudget=16") < text.indexOf("treesInChunkTexture=false")
            && text.indexOf("treesInChunkTexture=false") < text.indexOf("workers=2"), "keys sorted: " + text);
      Properties back = UserOptions.read(f);
      Check.check(back.size() == 3 && "16".equals(back.getProperty("bakeBudget")), "round trip: " + back);
      Check.check(UserOptions.read(new File(dir, "missing.ini")).isEmpty(), "missing file reads empty");

      // registry: every documented key is known, with its default recorded
      Check.check(Config.knows("enabled") && "true".equals(Config.defaultValue("enabled")) && Config.ENABLED, "master switch enabled by default");
      Check.check(Config.knows("bakeBudget") && "8".equals(Config.defaultValue("bakeBudget")), "bakeBudget default 8");
      Check.check(Config.knows("treesInChunkTexture") && "true".equals(Config.defaultValue("treesInChunkTexture")), "trees default true");
      Check.check(Config.value("workers") != null && Integer.parseInt(Config.value("workers")) >= 1, "workers value present");
      Check.check(!Config.knows("noSuchKey") && Config.value("noSuchKey") == null && Config.pinnedBy("noSuchKey") == null, "unknown key");
      Check.check("-Dpzopt.dev".equals(Config.pinnedBy("dev")), "dev pinned by the -D the test runner passes: " + Config.pinnedBy("dev"));
      Check.check(Config.pinnedBy("bakeBudget") == null || "pzopt.properties".equals(Config.pinnedBy("bakeBudget")), "bakeBudget pinned only by a cwd pzopt.properties");

      // set(): "" removes, a value writes, unchanged values do not rewrite
      System.setProperty("pzopt.userOptionsFile", new File(dir, "live.ini").getPath());
      Check.check(UserOptions.file().getName().equals("live.ini"), "file override honoured");
      UserOptions.set("bakeBudget", "4");
      Check.check("4".equals(UserOptions.get("bakeBudget")) && UserOptions.read(UserOptions.file()).getProperty("bakeBudget").equals("4"), "set writes");
      UserOptions.set("bakeBudget", "");
      Check.check(UserOptions.get("bakeBudget") == null && UserOptions.read(UserOptions.file()).getProperty("bakeBudget") == null, "empty removes");

      // the Profiler tab's keys apply at once; every other key waits for the next launch
      Check.check(Config.isLive("overlayRefreshMs") && Config.isLive("overlaySampling") && Config.isLive("gameThreadProfileHz"), "profiler keys live");
      Check.check(!Config.isLive("bakeBudget") && !Config.isLive("enabled") && !Config.isLive("overlayKey"), "other keys not live");
      int bake = Config.BAKE_BUDGET;
      UserOptions.set("bakeBudget", "3");
      Check.check(Config.BAKE_BUDGET == bake && String.valueOf(bake).equals(Config.value("bakeBudget")), "bakeBudget waits for the next launch");
      UserOptions.set("bakeBudget", "");
      UserOptions.set("overlayRefreshMs", "500");
      Check.check(Config.OVERLAY_REFRESH_MS == 500 && "500".equals(Config.value("overlayRefreshMs")), "overlayRefreshMs applied now: " + Config.OVERLAY_REFRESH_MS);
      UserOptions.set("overlayRefreshMs", "");
      Check.check(Config.OVERLAY_REFRESH_MS == 250 && "250".equals(Config.value("overlayRefreshMs")), "default back now");

      // the Enhancements tab's keys apply at once too, except the two HDR output switches (they pick the window)
      for (String k : new String[] {"upscaler", "upscalerQuality", "dlssPreset", "fsrSharpnessPct", "ambientOcclusion", "aoStrengthVegetationPct", "hdrBloomPct", "hdrUiNits"}) {
         Check.check(Config.isLive(k) && Enhancements.owns(k), k + " live and routed to Enhancements");
      }
      Check.check(!Config.isLive("hdr") && !Config.isLive("hdrAuto") && !Config.isLive("aoStrengthPct"), "hdr / hdrAuto / the old aoStrengthPct wait for the next launch");
      Check.check(!Enhancements.owns("overlayRefreshMs") && !Enhancements.owns("gameThreadProfileHz"), "overlay keys stay the overlay's");
      UserOptions.set("fsrSharpnessPct", "40"); // read every frame: no GL work to trigger in a JVM-only test
      Check.check(Config.FSR_SHARPNESS_PCT == 40, "fsrSharpnessPct applied now: " + Config.FSR_SHARPNESS_PCT);
      UserOptions.set("fsrSharpnessPct", "");
      Check.check(Config.FSR_SHARPNESS_PCT == 80, "fsrSharpnessPct default back now");
      System.out.println("UserOptionsTest: ok");
   }
}
