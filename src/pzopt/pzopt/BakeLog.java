package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import zombie.ZomboidFileSystem;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Per-frame chunk-bake census for instrumented runs (Zomboid/pzopt-bakes.out, harness/frame-causes.py): every chunk-level
 * bake the game thread records is classed by the strongest reason among its dirty flags, and one row per frame gives the
 * counts, so a frame that bakes 40 levels says why. Rows: "ns create object cutaway trees redraw lighting other", ns =
 * System.nanoTime() when the next frame's cell render starts (inside the game step that recorded the bakes); after it one
 * "b ns wx wy level flags playerX playerY" row per bake of that frame (flags = the level's dirty bits before the bake).
 */
public final class BakeLog {
   public static final boolean ON = Config.INSTRUMENT && Overrides.enabled();
   static final int CREATE = 0, OBJECT = 1, CUTAWAY = 2, TREES = 3, REDRAW = 4, LIGHTING = 5, OTHER = 6;
   private static final int[] counts = new int[7];
   private static BufferedWriter log;
   private static boolean failed;
   private static long lastFlushNs;

   private BakeLog() {
   }

   private static final StringBuilder rows = new StringBuilder(1 << 12);

   /** Game thread, before the level's bake clears its dirty flags. */
   public static void bake(zombie.iso.IsoChunk c, FBORenderLevels levels, int level, float zoom) {
      long flags = 0L;
      for (int bit = 0; bit < 16; bit++) {
         if (levels.isDirty(level, 1L << bit, zoom)) {
            flags |= 1L << bit;
         }
      }
      zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
      rows.append("b ").append(System.nanoTime()).append(' ').append(c.wx).append(' ').append(c.wy).append(' ').append(level).append(' ').append(flags)
         .append(' ').append(p == null ? 0 : (int)p.getX()).append(' ').append(p == null ? 0 : (int)p.getY()).append('\n');
      int k;
      if (levels.isDirty(level, 512L, zoom)) {
         k = CREATE;
      } else if (levels.isDirty(level, 1L | 2L | 4L | 8L | 16L | 64L | 128L | 256L, zoom)) {
         k = OBJECT;
      } else if (levels.isDirty(level, 2048L | 8192L | 16384L, zoom)) {
         k = CUTAWAY;
      } else if (levels.isDirty(level, 4096L, zoom)) {
         k = TREES;
      } else if (levels.isDirty(level, 1024L, zoom)) {
         k = REDRAW;
      } else if (levels.isDirty(level, 32L, zoom)) {
         k = LIGHTING;
      } else {
         k = OTHER;
      }
      counts[k]++;
   }

   /** Game thread: a level whose texture was made by beginRenderChunkLevel without being dirty first (counted as other, flag bit 20). */
   public static void hidden(zombie.iso.IsoChunk c, int level) {
      zombie.characters.IsoPlayer p = zombie.characters.IsoPlayer.getInstance();
      rows.append("b ").append(System.nanoTime()).append(' ').append(c.wx).append(' ').append(c.wy).append(' ').append(level).append(' ').append(1L << 20)
         .append(' ').append(p == null ? 0 : (int)p.getX()).append(' ').append(p == null ? 0 : (int)p.getY()).append('\n');
      counts[OTHER]++;
   }

   /** Game thread, once per frame at the start of the cell render: writes the previous frame's row. */
   public static void frame() {
      long now = System.nanoTime();
      try {
         if (log == null) {
            if (failed) {
               return;
            }
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            log = new BufferedWriter(new FileWriter(new File(dir, "pzopt-bakes.out"), false), 1 << 16);
            log.write("# ns create object cutaway trees redraw lighting other (bakes recorded by the frame that ended at ns)\n");
         }
         StringBuilder b = new StringBuilder(48).append(now);
         for (int i = 0; i < counts.length; i++) {
            b.append(' ').append(counts[i]);
            counts[i] = 0;
         }
         log.write(b.append('\n').toString());
         if (rows.length() > 0) {
            log.write(rows.toString());
            rows.setLength(0);
         }
         if (now - lastFlushNs > 1_000_000_000L) {
            log.flush();
            lastFlushNs = now;
         }
      } catch (IOException | RuntimeException e) {
         failed = true;
         log = null;
      }
   }
}
