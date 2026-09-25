package pzopt;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
 */
public final class BakeMips {
   public static final boolean ON = Config.BAKE_MIP_LEVELS > 0 && Overrides.enabled();
   private static final Set<Object> capped = Collections.newSetFromMap(new IdentityHashMap<>()); // game thread
   private static Boolean dsa; // render thread

   private BakeMips() {
   }

   /** Game thread, right after a bake's beginRenderChunkLevel: queue the cap for this render chunk's texture once. */
   public static void onBake(Object renderChunk, Texture tex) {
      if (tex == null || !capped.add(renderChunk)) {
         return;
      }
      Cap c = new Cap(); // once per render chunk (a few hundred a session)
      c.tex = tex;
      SpriteRenderer.instance.drawGeneric(c);
   }

   private static final class Cap extends TextureDraw.GenericDrawer {
      Texture tex;

      @Override
      public void render() {
         if (dsa == null) {
            dsa = GL.getCapabilities().OpenGL45 || GL.getCapabilities().GL_ARB_direct_state_access;
            Log.info("bake mipmaps: " + (dsa ? "chain capped at level " + Config.BAKE_MIP_LEVELS : "no direct state access, stock chain"));
         }
         Texture t = this.tex;
         this.tex = null;
         if (!dsa || t == null) {
            return;
         }
         int id = t.getID();
         if (id > 0) {
            GL45.glTextureParameteri(id, GL12.GL_TEXTURE_MAX_LEVEL, Config.BAKE_MIP_LEVELS);
         }
      }
   }
}
