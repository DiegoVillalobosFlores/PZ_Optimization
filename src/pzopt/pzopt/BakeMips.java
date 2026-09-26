package pzopt;

import java.util.IdentityHashMap;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL45;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * bakeMipLevels (2026-09-24, the Rosewood drive): a chunk-level texture (1024 x 1024 on a 4K+ screen) gets
 * glGenerateMipmap after every bake (FBORenderChunk.endRenderThread), the whole 11-level chain; that pass was a quarter
 * of a bake's GPU time (0.062 of 0.20 ms, run td-gpusec2), mostly the fixed cost of each small level. The composite
 * samples it at most 2.5x minified (the widest zoom), mip LOD <= 1.33, so levels past {@code bakeMipLevels} are never
 * read. The first bake of each render chunk sets GL_TEXTURE_MAX_LEVEL on its colour texture (direct state access, no
 * binding touched); glGenerateMipmap then builds only the base+1..max levels and the sampler clamps there. Needs GL 4.5
 * or ARB_direct_state_access (not macOS's 4.1: left stock there).
 *
 * <p>Sprite filter (2026-09-26): with {@code spriteFilter=sharp} the composite samples one mip level sharper than the
 * footprint (rgss4: four taps at bias -1), so the widest zoom reads LOD 0.32 and one level is enough
 * ({@link SpriteFilter#mipLevelsNeeded}); a render chunk whose cap differs from the wanted count is re-capped at its next
 * bake, before that bake's glGenerateMipmap (a texture not re-baked keeps its levels and the sampler clamps to them).
 */
public final class BakeMips {
   public static final boolean ON = Config.BAKE_MIP_LEVELS > 0 && Overrides.enabled();
   private static final IdentityHashMap<Object, Integer> capped = new IdentityHashMap<>(); // game thread: render chunk -> levels
   private static Boolean dsa; // render thread

   private BakeMips() {
   }

   /** Game thread, right after a bake's beginRenderChunkLevel: queue the cap for this render chunk's texture once. */
   public static void onBake(Object renderChunk, Texture tex) {
      if (tex == null) {
         return;
      }
      int levels = Math.min(Config.BAKE_MIP_LEVELS, SpriteFilter.mipLevelsNeeded(Config.BAKE_MIP_LEVELS));
      Integer had = capped.put(renderChunk, levels);
      if (had != null && had == levels) {
         return;
      }
      Cap c = new Cap(); // once per render chunk (a few hundred a session) and per change of the wanted count
      c.tex = tex;
      c.levels = levels;
      SpriteRenderer.instance.drawGeneric(c);
   }

   private static final class Cap extends TextureDraw.GenericDrawer {
      Texture tex;
      int levels;

      @Override
      public void render() {
         if (dsa == null) {
            dsa = GL.getCapabilities().OpenGL45 || GL.getCapabilities().GL_ARB_direct_state_access;
            Log.info("bake mipmaps: " + (dsa ? "chain capped at level " + this.levels : "no direct state access, stock chain"));
         }
         Texture t = this.tex;
         this.tex = null;
         if (!dsa || t == null) {
            return;
         }
         int id = t.getID();
         if (id > 0) {
            GL45.glTextureParameteri(id, GL12.GL_TEXTURE_MAX_LEVEL, this.levels);
         }
      }
   }
}
