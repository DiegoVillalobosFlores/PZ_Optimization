package pzopt;

/** The chunk grid width: stock pass-through, fixed widths made odd and clamped, and the auto width per screen and zoom. */
public class ChunkGridTest {
   public static void main(String[] args) {
      Check.check(ChunkGrid.width(19, false, 0, 5120, 2160, 2.5F) == 19, "0 = stock");
      Check.check(ChunkGrid.width(13, false, 0, 1280, 720, 2.5F) == 13, "0 = stock at 720p");
      Check.check(ChunkGrid.width(19, false, 14, 5120, 2160, 2.5F) == 15, "even widths are made odd");
      Check.check(ChunkGrid.width(19, false, 1, 5120, 2160, 2.5F) == ChunkGrid.MIN, "clamped to MIN");
      Check.check(ChunkGrid.width(19, false, 99, 5120, 2160, 2.5F) == ChunkGrid.MAX, "clamped to MAX");
      // 5120x2160 at zoom 2.5: (5120 + 4320) * 2.5 / 128 = 184.4 tiles = 23.05 chunks -> 24 + 1 margin -> 25
      Check.check(ChunkGrid.coveringWidth(5120, 2160, 2.5F) == 25, "5120x2160 zoom 2.5: " + ChunkGrid.coveringWidth(5120, 2160, 2.5F));
      Check.check(ChunkGrid.width(19, true, 0, 5120, 2160, 2.5F) == 25, "auto at 5120x2160");
      // 1080p at zoom 2.5 needs 11: auto never goes below the stock 19
      Check.check(ChunkGrid.coveringWidth(1920, 1080, 2.5F) == 11, "1080p zoom 2.5: " + ChunkGrid.coveringWidth(1920, 1080, 2.5F));
      Check.check(ChunkGrid.width(19, true, 0, 1920, 1080, 2.5F) == 19, "auto keeps the stock width at 1080p");
      // 4K UHD at zoom 2.5: (3840 + 4320) * 2.5 / 128 = 159.4 tiles = 19.9 chunks -> 20 + 1 -> 21
      Check.check(ChunkGrid.width(19, true, 0, 3840, 2160, 2.5F) == 21, "auto at 3840x2160: " + ChunkGrid.width(19, true, 0, 3840, 2160, 2.5F));
      // zoom disabled (max zoom 1.0) at 5120x2160: 73.75 tiles -> 10 + 1 -> 11 < 19
      Check.check(ChunkGrid.width(19, true, 0, 5120, 2160, 1.0F) == 19, "auto with zoom off keeps stock");
      Check.check(ChunkGrid.width(19, true, 0, 7680, 4320, 2.5F) == ChunkGrid.MAX, "8K at zoom 2.5 reaches MAX");
      for (int w : new int[] {1280, 1920, 2560, 3440, 3840, 5120, 7680}) {
         for (int h : new int[] {720, 1080, 1440, 2160, 4320}) {
            for (float z : new float[] {1.0F, 1.5F, 2.0F, 2.5F}) {
               int n = ChunkGrid.coveringWidth(w, h, z);
               float px = n * 8 * ChunkGrid.TILE_PX / z; // grid diamond width in screen pixels
               Check.check(n % 2 == 1, "odd");
               Check.check(px - 8 * ChunkGrid.TILE_PX / z >= w + 2.0F * h, "covers " + w + "x" + h + " at " + z + " with a chunk to spare");
            }
         }
      }
      // height shift: 3 tiles per level, none at or below level 0, capped at the extra half-width over stock
      Check.check(ChunkGrid.heightShiftTiles(0.0F, 25, 19) == 0, "level 0: no shift");
      Check.check(ChunkGrid.heightShiftTiles(-2.0F, 25, 19) == 0, "basement: no shift");
      Check.check(ChunkGrid.heightShiftTiles(2.0F, 25, 19) == 6, "level 2: 6 tiles");
      Check.check(ChunkGrid.heightShiftTiles(1.5F, 25, 19) == 5, "on the stairs: rounded (4.5 -> 5)");
      Check.check(ChunkGrid.heightShiftTiles(8.0F, 25, 19) == 24, "level 8 on 25 over 19: the cap, 3 chunks");
      Check.check(ChunkGrid.heightShiftTiles(20.0F, 25, 19) == 24, "level 20 on 25 over 19: capped");
      Check.check(ChunkGrid.heightShiftTiles(5.0F, 19, 19) == 0, "stock width: no shift");
      Check.check(ChunkGrid.heightShiftTiles(5.0F, 15, 19) == 0, "narrower than stock: no shift");
      Check.check(ChunkGrid.heightShiftTiles(Float.NaN, 25, 19) == 0, "NaN height: no shift");
      System.out.println("ChunkGridTest ok");
   }
}
