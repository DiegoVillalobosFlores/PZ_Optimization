package pzopt;

import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;
import zombie.core.Core;
import zombie.core.textures.TextureDraw;

/**
 * Late-latched software cursor ({@code cursorLatch}, 2026-09-24). With "Lock cursor to window" the game hides the OS
 * cursor and draws its own (zombie.input.Mouse.renderCursorTexture) at the mouse position the game thread read at the
 * start of its frame, so the cursor trails the hand by the whole pipeline (7 ms at a 240 cap, ~26 ms with vsync and an
 * uncapped game). The cursor sprite is captured when it is recorded; just before the render thread replays that frame
 * it pumps the OS events and shifts the sprite to the newest pointer position (what VR runtimes and games do with
 * cursors and head poses: late latching). Only the drawn cursor moves; the game's own reads of the mouse are untouched.
 */
public final class CursorLatch implements Consumer<TextureDraw> {
   public static final boolean ON = Config.CURSOR_LATCH && Overrides.enabled();
   private static final CursorLatch INSTANCE = new CursorLatch();
   private static final int SLOTS = 4;
   private static final Object[] slotState = new Object[SLOTS];
   private static final TextureDraw[] slotDraw = new TextureDraw[SLOTS];
   private static final int[] slotX = new int[SLOTS], slotY = new int[SLOTS];
   private static int next, pendingX, pendingY;
   private static long latched, lastLogNs;
   private static double shiftSum;

   private CursorLatch() {
   }

   /** Game thread: the modifier for the cursor sprite drawn at (usedX, usedY) (the Mouse.getXA / getYA it used). */
   public static Consumer<TextureDraw> capture(int usedX, int usedY) {
      if (!ON) {
         return null;
      }
      pendingX = usedX;
      pendingY = usedY;
      return INSTANCE;
   }

   @Override
   public void accept(TextureDraw texd) {
      synchronized (slotState) {
         int i = next++ % SLOTS;
         slotState[i] = zombie.core.SpriteRenderer.instance.states.getPopulating();
         slotDraw[i] = texd;
         slotX[i] = pendingX;
         slotY[i] = pendingY;
      }
   }

   /** Render thread, lockStepRenderStep before the sprite replay of renderState. */
   public static void beforeReplay(Object renderState) {
      if (!ON) {
         return;
      }
      TextureDraw texd = null;
      int usedX = 0, usedY = 0;
      synchronized (slotState) {
         for (int i = 0; i < SLOTS; i++) {
            if (slotState[i] == renderState && slotDraw[i] != null) {
               texd = slotDraw[i];
               usedX = slotX[i];
               usedY = slotY[i];
               slotState[i] = null;
               slotDraw[i] = null;
            }
         }
      }
      if (texd == null) {
         return;
      }
      GLFW.glfwPollEvents(); // the newest pointer position into lwjglx
      int x = org.lwjglx.input.Mouse.pzoptLatestX();
      int y = Core.getInstance().getScreenHeight() - org.lwjglx.input.Mouse.pzoptLatestY() - 1;
      float dx = x - usedX, dy = y - usedY;
      texd.x0 += dx;
      texd.x1 += dx;
      texd.x2 += dx;
      texd.x3 += dx;
      texd.y0 += dy;
      texd.y1 += dy;
      texd.y2 += dy;
      texd.y3 += dy;
      latched++;
      shiftSum += Math.hypot(dx, dy);
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         if (lastLogNs != 0L) {
            Log.info(String.format("cursor latch: %d frames, mean shift %.1f px", latched, shiftSum / Math.max(1, latched)));
         }
         lastLogNs = now;
         latched = 0;
         shiftSum = 0.0;
      }
   }
}
