package pzopt;

/**
 * The player's chunk grid width (Config.CHUNK_GRID_WIDTH, "Render distance" in the Optimizations tab; 2026-09-22).
 *
 * Stock IsoChunkMap.CalcChunkWidth sizes the grid from the screen but only up to a 1080p one: 13 * 1.5 * min(1, screen /
 * 1080p), made odd, at most 19 chunks (152 tiles). The grid is a diamond on screen; it covers a W x H screen only while
 * its width in pixels reaches W + 2H (the diamond is twice as wide as tall), and one tile is 128 / zoom pixels across
 * (Core.getMaxZoom() already folds in the tile scale). At 5120x2160 and the widest zoom (2.5) that is 184 tiles, 23
 * chunks: the stock 19 leaves dark screen corners from zoom 2.25 outward.
 *
 * {@link #width}: 0 = the stock width; "auto" ({@code auto == true}) = the smallest odd width that covers the screen at
 * the widest zoom plus one chunk (the player may sit anywhere in the centre chunk, half a chunk off-centre each way),
 * never below the stock width; N = N made odd. The result is clamped to [{@link #MIN}, {@link #MAX}].
 * {@code tests/pzopt/ChunkGridTest}.
 */
public final class ChunkGrid {
   public static final int MIN = 5;
   public static final int MAX = 41; // an 8K screen at zoom 2.5 needs 41 in auto
   /** Screen pixels across one tile's diamond at zoom 1 with the 2x tiles; getMaxZoom() scales for 1x tiles. */
   static final float TILE_PX = 128.0F;

   private ChunkGrid() {
   }

   /** The smallest odd width in chunks whose grid diamond covers a screenW x screenH screen at maxZoom, plus one chunk. */
   public static int coveringWidth(int screenW, int screenH, float maxZoom) {
      float tiles = (screenW + 2.0F * screenH) * Math.max(maxZoom, 0.1F) / TILE_PX;
      int chunks = (int)Math.ceil(tiles / 8.0F) + 1;
      return chunks | 1;
   }

   /** The stock width CalcChunkWidth computed before the setting replaced it (0 = not computed yet). */
   public static int stock;

   /**
    * How many tiles north and west of the player the grid centre moves at height z (2026-09-24). The camera centres on
    * the player's screen position, height included: a level is 96 px (1x tiles), a tile step 16 px, so on level z the
    * ground under the screen centre is 3z tiles north and 3z tiles west of the player, and a player-centred grid leaves
    * the top screen corners dark from the second floor up on a grid sized for level 0. Following that ground point keeps
    * level 0 covered as on the ground floor. Capped so the player stays at least as far from every grid edge as in a
    * stock-width grid (the world is never simulated nearer than vanilla): 0 for grids no wider than stock, 3 chunks for
    * 25 over a stock 19 (level 8). Levels below 0 draw no surface above them, so no shift.
    */
   public static int heightShiftTiles(float z, int gridWidth, int stockWidth) {
      if (!(z > 0.0F)) {
         return 0;
      }
      int cap = Math.max(0, gridWidth / 2 - stockWidth / 2) * 8;
      return Math.min(Math.round(3.0F * z), cap);
   }

   public static int width(int stock, boolean auto, int fixed, int screenW, int screenH, float maxZoom) {
      int w;
      if (auto) {
         w = Math.max(stock, coveringWidth(screenW, screenH, maxZoom));
      } else if (fixed > 0) {
         w = fixed | 1;
      } else {
         return stock;
      }
      return Math.min(Math.max(w, MIN), MAX);
   }
}
