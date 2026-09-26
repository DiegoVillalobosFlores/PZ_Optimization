package pzopt;

import java.util.ArrayList;
import org.lwjgl.opengl.GL11;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Trees baked into chunk-level textures (Config key {@code treeBakePass}, on top of {@code treesInChunkTexture}).
 *
 * <p>A chunk-level texture covers its chunk's footprint plus a fixed margin (two levels and the JUMBO_L
 * allowance above it), while a tree sprite is up to seven tiles wide and sixteen tile heights tall and
 * is anchored on one square. Drawn through the plain sprite path into the texture of its own chunk, a
 * tree is clipped at the texture border (issue #5: crowns cut by straight edges, black chunk-sized
 * rectangles where a dark tree is clipped), and it writes one flat depth for the whole sprite while the
 * walls it overlaps write a per-pixel depth that gets nearer with height, so an upper-storey wall behind
 * the tree cuts a vertical strip out of the crown.
 *
 * <p>This pass draws every baked tree into every chunk-level texture its sprite overlaps (its own and the
 * neighbours', in the neighbour's texture space) as a quad whose depth is the tree square's depth at its
 * base and gets nearer by one level's depth per level of height, like the stock per-frame tree billboard
 * and the wall depth textures. The quads go through {@code VBORenderer}'s position/colour/uv/depth format,
 * whose fragment shader writes the interpolated depth, last in the texture (after all its levels) under
 * GL_LEQUAL: content in front of the tree already in the depth buffer occludes it, content behind is
 * painted over. The game thread computes placement, colour and depth ({@link FBORenderCell} feeds this
 * drawer); the render thread only packs and draws.
 *
 * <p>Texture space: {@code goX} (half the texture width, FBORenderChunkManager.getXOffset) and
 * {@code yoff} (FBORenderChunkManager.getYOffset) are the sprite offsets the chunk bake adds to every
 * sprite; a high-res texture (camera zoom below 0.75) is drawn at twice the scale, so its logical size is
 * half the texture size around {@code goX}. All rectangles here are in that logical space.
 */
public final class TreeBake {
   private TreeBake() {
   }

   /** Depth written by the stock depth model per level of height (IsoDepthHelper.calculateDepth's zOffset step). */
   public static final float DEPTH_PER_LEVEL = 0.0028867084F;

   private static final int STRIDE = 12;
   private static final ArrayList<Drawer> pool = new ArrayList<>();

   public static long treesDrawn, copiesDrawn, quadsDrawn, passes, neighboursInvalidated, appendsQueued, appendsDrawn, appendsFellBack, appendsRefused;

   /** Screen rectangle of a tree sprite in the logical space of a chunk texture. */
   public static final class Rect {
      public float x0, y0, x1, y1;
      /** Row of the square's south corner: the depth base; rows above it get nearer. */
      public float ground;
   }

   /** Half the sprite width for the tree sprite named {@code spriteName} (IsoTree.renderInner's rule). */
   public static float offsetX(String spriteName, int tileScale) {
      int floorWidth = 64 * tileScale;
      if (spriteName != null) {
         if (spriteName.contains("JUMBOXXL")) {
            return floorWidth * 7 / 2.0F;
         }
         if (spriteName.contains("JUMBOXL")) {
            return floorWidth * 5 / 2.0F;
         }
         if (spriteName.contains("JUMBO")) {
            return floorWidth * 3 / 2.0F;
         }
      }
      return floorWidth / 2.0F;
   }

   /** Height of the sprite above the square's screen origin (IsoTree.renderInner's rule). */
   public static float offsetY(String spriteName, int tileScale) {
      int floorHeight = 32 * tileScale;
      if (spriteName != null) {
         if (spriteName.contains("JUMBOXXL")) {
            return floorHeight * 16 - floorHeight;
         }
         if (spriteName.contains("JUMBOXL")) {
            return floorHeight * 12 - floorHeight;
         }
         if (spriteName.contains("JUMBO")) {
            return floorHeight * 8 - floorHeight;
         }
      }
      return 96 * tileScale;
   }

   /** The sprite scale IsoSprite.performRenderFrame applies: 1x-only 64x128 tree textures draw doubled on 2x tiles. */
   public static float spriteScale(int tileScale, int widthOrig, int heightOrig) {
      return tileScale == 2 && widthOrig == 64 && heightOrig == 128 ? 2.0F : 1.0F;
   }

   /**
    * Full (untrimmed) rectangle of a tree sprite anchored on square ({@code sx}, {@code sy}, {@code z}) of the
    * world, in the logical space of the chunk texture of chunk ({@code wx}, {@code wy}).
    */
   public static void spriteRect(int sx, int sy, int z, int wx, int wy, float offsetX, float offsetY, float width, float height,
                                 float goX, float yoff, int tileScale, Rect out) {
      float xRel = sx - wx * 8;
      float yRel = sy - wy * 8;
      float screenX = (xRel - yRel) * (32 * tileScale);
      float screenY = (xRel + yRel) * (16 * tileScale) - z * (96 * tileScale);
      out.x0 = screenX - offsetX + goX;
      out.y0 = screenY - offsetY + yoff;
      out.x1 = out.x0 + width;
      out.y1 = out.y0 + height;
      out.ground = screenY + yoff + 32 * tileScale;
   }

   /** The logical area {@code [goX - w/2, goX + w/2] x [0, h]} of the texture whose space we are in. */
   public static void ownTextureRect(float goX, float logicalWidth, float logicalHeight, Rect out) {
      out.x0 = goX - logicalWidth / 2.0F;
      out.x1 = goX + logicalWidth / 2.0F;
      out.y0 = 0.0F;
      out.y1 = logicalHeight;
   }

   /**
    * The logical area of the texture of the chunk {@code dwx, dwy} chunks away, expressed in this texture's space:
    * chunk corners are {@code 8 * 32 * tileScale} px apart per chunk in x and {@code 8 * 16 * tileScale} in y, and
    * each texture's row 0 sits {@code yoff} above its chunk's corner.
    */
   public static void neighbourTextureRect(int dwx, int dwy, float goX, float yoff, float otherYoff, float otherLogicalWidth,
                                           float otherLogicalHeight, int tileScale, Rect out) {
      float dx = (dwx - dwy) * (8 * 32 * tileScale);
      float dy = (dwx + dwy) * (8 * 16 * tileScale);
      out.x0 = goX + dx - otherLogicalWidth / 2.0F;
      out.x1 = goX + dx + otherLogicalWidth / 2.0F;
      out.y0 = dy + yoff - otherYoff;
      out.y1 = out.y0 + otherLogicalHeight;
   }

   /** Does the sprite rectangle touch the texture area? */
   public static boolean overlaps(Rect sprite, Rect texture) {
      return sprite.x1 > texture.x0 && sprite.x0 < texture.x1 && sprite.y1 > texture.y0 && sprite.y0 < texture.y1;
   }

   /**
    * Does {@code target} need its own copy of the sprite: the part of the sprite inside {@code target} is not
    * entirely inside {@code own} (the texture of the tree's own chunk), so without the copy those pixels would be
    * clipped. Adjacent chunk textures overlap by half, so most trees fit their own texture and need no copy.
    */
   public static boolean needsCopy(Rect sprite, Rect target, Rect own) {
      float ix0 = Math.max(sprite.x0, target.x0);
      float ix1 = Math.min(sprite.x1, target.x1);
      float iy0 = Math.max(sprite.y0, target.y0);
      float iy1 = Math.min(sprite.y1, target.y1);
      if (ix1 <= ix0 || iy1 <= iy0) {
         return false;
      }
      return ix0 < own.x0 || ix1 > own.x1 || iy0 < own.y0 || iy1 > own.y1;
   }

   /** Depth of a sprite row: the base depth at the ground row, one level's depth nearer per level of height. */
   public static float depthAtRow(float base, float ground, float row, int tileScale) {
      return base + (row - ground) * (DEPTH_PER_LEVEL / (96 * tileScale));
   }

   public static Drawer alloc() {
      synchronized (pool) {
         if (!pool.isEmpty()) {
            return pool.remove(pool.size() - 1);
         }
      }
      return new Drawer();
   }

   /** One chunk texture's tree quads: filled on the game thread, drawn once on the render thread, then pooled. */
   public static final class Drawer extends TextureDraw.GenericDrawer {
      private Texture[] textures = new Texture[32];
      private float[] v = new float[32 * STRIDE];
      private int count;
      private Texture expect; // treeAppend: draw only into the framebuffer whose colour attachment is this texture

      /** treeAppend: the quads go into an existing texture; the render thread refuses to draw anywhere else. */
      public void expectTexture(Texture texture) {
         this.expect = texture;
      }

      /**
       * Adds one textured quad: texture rectangle {@code x0..x1, y0..y1} in texture space, the texture's atlas
       * coordinates, the depth at the top and bottom rows (interpolated per fragment, clamped by GL to the
       * depth range), vertex colour and source square's lighting level. The level survives copies
       * into a neighbour's cache; it is not the last level drawn before this tree pass.
       */
      public void add(Texture texture, float x0, float y0, float x1, float y1, float depthTop, float depthBottom,
                      float r, float g, float b, float a, int sourceLevel) {
         if (this.count == this.textures.length) {
            this.textures = java.util.Arrays.copyOf(this.textures, this.count * 2);
            this.v = java.util.Arrays.copyOf(this.v, this.count * 2 * STRIDE);
         }
         int i = this.count * STRIDE;
         this.textures[this.count] = texture;
         this.v[i] = x0;
         this.v[i + 1] = y0;
         this.v[i + 2] = x1;
         this.v[i + 3] = y1;
         this.v[i + 4] = depthTop;
         this.v[i + 5] = depthBottom;
         this.v[i + 6] = r;
         this.v[i + 7] = g;
         this.v[i + 8] = b;
         this.v[i + 9] = a;
         this.v[i + 10] = sourceLevel;
         this.count++;
      }

      public boolean isEmpty() {
         return this.count == 0;
      }

      @Override
      public void render() {
         if (this.count == 0) {
            return;
         }
         if (this.expect != null) {
            int bound;
            if (Config.GL_NO_SYNC) {
               // glNoSync: the render chunk the render thread bound (each chunk FBO keeps its one colour texture for life)
               // instead of asking the driver, which waits for NVIDIA's driver thread to drain its queue
               zombie.iso.fboRenderChunk.FBORenderChunk cur = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance.renderThreadCurrent;
               bound = cur == null || cur.tex == null || cur.tex.getTextureId() == null ? -1 : cur.tex.getTextureId().getID();
               if (Config.DEV_GL_STATE_CHECK) {
                  GlState.checks++;
                  if (bound != org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameteri(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER,
                        org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)) {
                     GlState.mismatches++;
                  }
               }
            } else {
               bound = org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameteri(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER,
                  org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            }
            if (this.expect.getTextureId() == null || bound != this.expect.getTextureId().getID()) {
               appendsRefused++;
               return;
            }
         }
         VBORenderer vbor = VBORenderer.getInstance();
         // the chunk bake's projection (Core.StartFrameFlipY: texture pixels, y down) is current; depth as the
         // baked sprites write it, alpha test so transparent texels write neither colour nor depth
         GL11.glEnable(2929);
         GL11.glDepthFunc(515);
         GL11.glDepthMask(true);
         GL11.glEnable(3008);
         GL11.glAlphaFunc(516, 0.0F);
         for (int n = 0; n < this.count; n++) {
            Texture texture = this.textures[n];
            if (texture == null || texture.getTextureId() == null) {
               continue;
            }
            int i = n * STRIDE;
            ChunkFloor.treeLevel((int)this.v[i + 10]);
            float x0 = this.v[i];
            float y0 = this.v[i + 1];
            float x1 = this.v[i + 2];
            float y1 = this.v[i + 3];
            float dTop = this.v[i + 4];
            float dBottom = this.v[i + 5];
            float u0 = texture.getXStart();
            float u1 = texture.getXEnd();
            float t0 = texture.getYStart();
            float t1 = texture.getYEnd();
            vbor.startRun(vbor.formatPositionColorUvDepth);
            vbor.setMode(7);
            vbor.setTextureID(texture.getTextureId());
            vbor.setDepthTest(true);
            vbor.addQuadDepth(
               x0, y0, 0.0F, u0, t0, dTop,
               x1, y0, 0.0F, u1, t0, dTop,
               x1, y1, 0.0F, u1, t1, dBottom,
               x0, y1, 0.0F, u0, t1, dBottom,
               this.v[i + 6], this.v[i + 7], this.v[i + 8], this.v[i + 9]);
            vbor.endRun();
            quadsDrawn++;
         }
         vbor.flush();
         GLStateRenderThread.restore();
      }

      @Override
      public void postRender() {
         java.util.Arrays.fill(this.textures, 0, this.count, null);
         this.count = 0;
         this.expect = null;
         synchronized (pool) {
            if (pool.size() < 64) {
               pool.add(this);
            }
         }
      }
   }

   public static String stats() {
      return "tree bake: passes=" + passes + " trees=" + treesDrawn + " copies=" + copiesDrawn + " quads=" + quadsDrawn + " neighbours re-baked=" + neighboursInvalidated
         + " appends=" + appendsDrawn + " (queued=" + appendsQueued + " fell back=" + appendsFellBack + " refused=" + appendsRefused + ")";
   }
}
