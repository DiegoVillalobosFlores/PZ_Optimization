package pzopt;

import zombie.core.textures.TextureDraw;

/** A one-off job run on the render thread in sprite-stream order (SpriteRenderer.drawGeneric), without making the game thread wait. */
public final class GlTask extends TextureDraw.GenericDrawer {
   private Runnable job;

   public GlTask(Runnable job) {
      this.job = job;
   }

   @Override
   public void render() {
      Runnable j = this.job;
      this.job = null;
      if (j != null) {
         j.run();
      }
   }
}
