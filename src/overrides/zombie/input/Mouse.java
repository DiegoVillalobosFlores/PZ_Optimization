package zombie.input;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.IntBuffer;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjglx.LWJGLException;
import org.lwjglx.input.Cursor;
import zombie.GameTime;
import zombie.UsedFromLua;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

@UsedFromLua
public final class Mouse {
   protected static int x;
   protected static int y;
   private static float timeRightPressed;
   private static final float TIME_RIGHT_PRESSED_SECONDS = 0.15F;
   public static final int BTN_OFFSET = 10000;
   public static final int BTN_0 = 10000;
   public static final int BTN_1 = 10001;
   public static final int BTN_2 = 10002;
   public static final int BTN_3 = 10003;
   public static final int BTN_4 = 10004;
   public static final int BTN_5 = 10005;
   public static final int BTN_6 = 10006;
   public static final int BTN_7 = 10007;
   public static final int LMB = 10000;
   public static final int RMB = 10001;
   public static final int MMB = 10002;
   public static boolean[] buttonDownStates;
   public static boolean[] buttonPrevStates;
   public static long lastActivity;
   public static int wheelDelta;
   private static final MouseStateCache s_mouseStateCache = new MouseStateCache();
   public static boolean[] uiCaptured = new boolean[10];
   static Cursor blankCursor;
   static Cursor defaultCursor;
   private static boolean isCursorVisible = true;
   private static Texture mouseCursorTexture;

   public static int getWheelState() {
      return wheelDelta;
   }

   public static int getButtonCount() {
      return s_mouseStateCache.getState().getButtonCount();
   }

   public static synchronized int getXA() {
      return pzopt.Showcase.aimOverride ? pzopt.Showcase.aimXA : x; // pzopt: harness showcase=horde aims the game's mouse at a zombie
   }

   public static synchronized int getYA() {
      return pzopt.Showcase.aimOverride ? pzopt.Showcase.aimYA : y; // pzopt: harness showcase=horde aims the game's mouse at a zombie
   }

   public static synchronized int getX() {
      return (int)((pzopt.Showcase.aimOverride ? pzopt.Showcase.aimXA : x) * Core.getInstance().getZoom(0)); // pzopt: harness showcase aim
   }

   public static synchronized int getY() {
      return (int)((pzopt.Showcase.aimOverride ? pzopt.Showcase.aimYA : y) * Core.getInstance().getZoom(0)); // pzopt: harness showcase aim
   }

   public static boolean isButtonKey(int key) {
      return key >= 10000;
   }

   public static boolean isButtonDown(int number) {
      return buttonDownStates != null ? buttonDownStates[number] : false;
   }

   public static boolean wasButtonDown(int number) {
      return buttonPrevStates != null ? buttonPrevStates[number] : false;
   }

   public static boolean isButtonPressed(int number) {
      return buttonDownStates != null && buttonPrevStates != null ? !buttonPrevStates[number] && buttonDownStates[number] : false;
   }

   public static boolean isButtonReleased(int number) {
      return buttonDownStates != null && buttonPrevStates != null ? buttonPrevStates[number] && !buttonDownStates[number] : false;
   }

   public static void UIBlockButtonDown(int number) {
      uiCaptured[number] = true;
   }

   public static boolean isButtonDownUICheck(int number) {
      if (buttonDownStates == null) {
         return false;
      }

      boolean b = buttonDownStates[number];
      if (!b) {
         uiCaptured[number] = false;
      } else if (uiCaptured[number]) {
         return false;
      }

      return number == 1 ? isRightDelay() : b;
   }

   public static boolean isRightDelay() {
      return !uiCaptured[1] && buttonDownStates != null && buttonDownStates[1] ? timeRightPressed >= pzopt.InputLatch.AIM_HOLD_S : false; // pzopt: aimHoldMs (stock 0.15 s)
   }

   public static boolean isLeftDown() {
      return isButtonDown(0);
   }

   public static boolean isLeftPressed() {
      return isButtonPressed(0);
   }

   public static boolean isLeftReleased() {
      return isButtonReleased(0);
   }

   public static boolean isLeftUp() {
      return !isButtonDown(0);
   }

   public static boolean isMiddleDown() {
      return isButtonDown(2);
   }

   public static boolean isMiddlePressed() {
      return isButtonPressed(2);
   }

   public static boolean isMiddleReleased() {
      return isButtonReleased(2);
   }

   public static boolean isMiddleUp() {
      return !isButtonDown(2);
   }

   public static boolean isRightDown() {
      return isButtonDown(1);
   }

   public static boolean isRightPressed() {
      return isButtonPressed(1);
   }

   public static boolean isRightReleased() {
      return isButtonReleased(1);
   }

   public static boolean isRightUp() {
      return !isButtonDown(1);
   }

   public static synchronized void update() {
      MouseState state = s_mouseStateCache.getState();
      if (!state.isCreated()) {
         s_mouseStateCache.swap();

         try {
            org.lwjglx.input.Mouse.create();
         } catch (LWJGLException e) {
            DebugType.General.printException(e, LogSeverity.Error);
         }
      } else {
         int lastX = x;
         int lastY = y;
         x = state.getX();
         y = Core.getInstance().getScreenHeight() - state.getY() - 1;
         wheelDelta = state.getDWheel();
         state.resetDWheel();
         boolean bActivity = lastX != x || lastY != y || wheelDelta != 0;
         if (buttonDownStates == null) {
            buttonDownStates = new boolean[state.getButtonCount()];
         }

         if (buttonPrevStates == null) {
            buttonPrevStates = new boolean[state.getButtonCount()];
         }

         for (int i = 0; i < buttonDownStates.length; i++) {
            buttonPrevStates[i] = buttonDownStates[i];
         }

         for (int i = 0; i < buttonDownStates.length; i++) {
            if (buttonDownStates[i] != state.isButtonDown(i)) {
               bActivity = true;
            }

            buttonDownStates[i] = state.isButtonDown(i);
         }

         if (pzopt.Showcase.holdButtons && buttonDownStates.length > 1) { // pzopt: harness showcase=horde, a player holding aim and pulling the trigger
            buttonDownStates[0] = pzopt.Showcase.fireDown; // pzopt: harness showcase fire (left)
            buttonDownStates[1] = true; // pzopt: harness showcase aim (right)
         }

         if (buttonDownStates[1]) {
            timeRightPressed = timeRightPressed + GameTime.getInstance().getRealworldSecondsSinceLastUpdate();
         } else {
            timeRightPressed = 0.0F;
         }

         if (bActivity) {
            lastActivity = System.currentTimeMillis();
         }

         s_mouseStateCache.swap();
         AimingReticle.update();
      }
   }

   public static void poll() {
      s_mouseStateCache.poll();
   }

   public static synchronized void setXY(int x, int y) {
      s_mouseStateCache.getState().setCursorPosition(x, Core.getInstance().getOffscreenHeight(0) - 1 - y);
   }

   public static Cursor loadCursor(String filename) throws LWJGLException {
      File file = ZomboidFileSystem.instance.getMediaFile("ui/" + filename);

      try {
         BufferedImage img = ImageIO.read(file);
         int w = img.getWidth();
         int h = img.getHeight();
         int[] rgbData = new int[w * h];

         for (int i = 0; i < rgbData.length; i++) {
            int x = i % w;
            int y = h - 1 - i / w;
            rgbData[i] = img.getRGB(x, y);
         }

         IntBuffer buffer = BufferUtils.createIntBuffer(w * h);
         buffer.put(rgbData);
         buffer.rewind();
         int xHotspot = 1;
         int yHotspot = 1;
         return new Cursor(w, h, 1, 1, 1, buffer, null);
      } catch (Exception ex) {
         return null;
      }
   }

   public static void initCustomCursor() {
      if (blankCursor == null) {
         try {
            blankCursor = loadCursor("cursor_blank.png");
            defaultCursor = loadCursor("cursor_white.png");
         } catch (LWJGLException e) {
            DebugType.General.printException(e, LogSeverity.Error);
         }
      }

      if (defaultCursor != null) {
         try {
            org.lwjglx.input.Mouse.setNativeCursor(defaultCursor);
         } catch (LWJGLException e) {
            DebugType.General.printException(e, LogSeverity.Error);
         }
      }
   }

   public static void setCursorVisible(boolean bVisible) {
      isCursorVisible = bVisible;
   }

   public static boolean isCursorVisible() {
      return isCursorVisible;
   }

   public static void renderCursorTexture() {
      if (isCursorVisible()) {
         if (mouseCursorTexture == null) {
            mouseCursorTexture = Texture.getSharedTexture("media/ui/cursor_white.png");
         }

         if (mouseCursorTexture != null && mouseCursorTexture.isReady()) {
            int mouseX = getXA();
            int mouseY = getYA();
            int hotSpotX = 1;
            int hotSpotY = 1;
            SpriteRenderer.instance
               .render(mouseCursorTexture, mouseX - 1, mouseY - 1, mouseCursorTexture.getWidth(), mouseCursorTexture.getHeight(), 1.0F, 1.0F, 1.0F, 1.0F, pzopt.CursorLatch.capture(mouseX, mouseY)); // pzopt: cursorLatch, the render thread moves the sprite to the newest pointer position
         }
      }
   }
}
