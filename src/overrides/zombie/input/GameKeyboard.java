package zombie.input;

import org.lwjglx.input.KeyEventQueue;
import zombie.GameWindow;
import zombie.UsedFromLua;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.core.Core.KeyBinding;
import zombie.core.opengl.RenderThread;
import zombie.ui.UIManager;

@UsedFromLua
public final class GameKeyboard {
   private static boolean[] down;
   private static boolean[] lastDown;
   private static boolean[] eatKey;
   public static boolean noEventsWhileLoading;
   public static boolean doLuaKeyPressed = true;
   private static final KeyboardStateCache s_keyboardStateCache = new KeyboardStateCache();

   public static void update() {
      if (!s_keyboardStateCache.getState().isCreated()) {
         s_keyboardStateCache.swap();
      } else {
         boolean pzoptFresh = pzopt.InputLatch.KEYBOARD_FRESH; // pzopt: keyboardFresh, read the poll this frame swaps in (stock read the previous one and swapped at the end)
         if (pzoptFresh) { // pzopt
            s_keyboardStateCache.swap(); // pzopt
         } // pzopt
         int c = s_keyboardStateCache.getState().getKeyCount();
         if (down == null) {
            down = new boolean[c];
            lastDown = new boolean[c];
            eatKey = new boolean[c];
         }

         boolean bDoingTextEntry = Core.currentTextEntryBox != null && Core.currentTextEntryBox.isDoingTextEntry();

         for (int n = 1; n < c; n++) {
            lastDown[n] = down[n];
            down[n] = s_keyboardStateCache.getState().isKeyDown(n);
            if (!down[n] && lastDown[n]) {
               if (eatKey[n]) {
                  eatKey[n] = false;
                  continue;
               }

               if (noEventsWhileLoading || bDoingTextEntry || LuaManager.thread == UIManager.defaultthread && UIManager.onKeyRelease(n)) {
                  continue;
               }

               if (Core.debug && !doLuaKeyPressed) {
                  System.out.println("KEY RELEASED " + n + " doLuaKeyPressed=false");
               }

               if (LuaManager.thread == UIManager.defaultthread && doLuaKeyPressed) {
                  LuaEventManager.triggerEvent("OnKeyPressed", n);
               }

               if (LuaManager.thread == UIManager.defaultthread) {
                  LuaEventManager.triggerEvent("OnCustomUIKey", n);
                  LuaEventManager.triggerEvent("OnCustomUIKeyReleased", n);
               }
            }

            if (down[n] && lastDown[n]) {
               if (noEventsWhileLoading || bDoingTextEntry || LuaManager.thread == UIManager.defaultthread && UIManager.onKeyRepeat(n)) {
                  continue;
               }

               if (LuaManager.thread == UIManager.defaultthread && doLuaKeyPressed) {
                  LuaEventManager.triggerEvent("OnKeyKeepPressed", n);
               }
            }

            if (down[n]
               && !lastDown[n]
               && !noEventsWhileLoading
               && !bDoingTextEntry
               && !eatKey[n]
               && (LuaManager.thread != UIManager.defaultthread || !UIManager.onKeyPress(n))
               && !eatKey[n]) {
               if (LuaManager.thread == UIManager.defaultthread && doLuaKeyPressed) {
                  LuaEventManager.triggerEvent("OnKeyStartPressed", n);
               }

               if (LuaManager.thread == UIManager.defaultthread) {
                  LuaEventManager.triggerEvent("OnCustomUIKeyPressed", n);
               }
            }
         }

         if (!pzoptFresh) { // pzopt: keyboardFresh swapped at the top
            s_keyboardStateCache.swap();
         } // pzopt
      }
   }

   public static void pzoptRepoll() { // pzopt: late input latch (pzopt.InputLatch), render thread
      s_keyboardStateCache.pzoptRepoll(); // pzopt
   } // pzopt

   public static void poll() {
      s_keyboardStateCache.poll();
   }

   public static boolean isKeyDownRaw(int key) {
      return down == null ? false : down[key];
   }

   public static boolean wasKeyDownRaw(int key) {
      return lastDown == null ? false : lastDown[key];
   }

   public static boolean isKeyPressed(int key) {
      return isKeyDown(key) && !wasKeyDown(key);
   }

   public static boolean isKeyPressed(String keyName) {
      return isKeyDown(keyName) && !wasKeyDown(keyName);
   }

   public static int whichKeyPressed(String keyName) {
      if (!isKeyPressed(keyName)) {
         return 0;
      } else if (isKeyPressed(Core.getInstance().getKey(keyName))) {
         return Core.getInstance().getKey(keyName);
      } else {
         return isKeyPressed(Core.getInstance().getAltKey(keyName)) ? Core.getInstance().getAltKey(keyName) : 0;
      }
   }

   public static boolean isKeyDown(int key) {
      if (key >= 10000) {
         return Mouse.isButtonDownUICheck(key - 10000);
      } else if (Core.currentTextEntryBox != null && Core.currentTextEntryBox.isDoingTextEntry()) {
         return false;
      } else if (pzopt.Showcase.keyHeld(key)) { // pzopt: harness showcase=horde, the director's movement keys
         return true;
      } else {
         return down == null ? false : down[key];
      }
   }

   public static boolean isKeyDown(String keyName) {
      KeyBinding keyB = Core.getInstance().getKeyBinding(keyName);
      return Core.getInstance().invalidBindingShiftCtrl(keyB) ? false : isKeyDown(keyB.keyValue()) || isKeyDown(keyB.altKey());
   }

   public static int whichKeyDown(String keyName) {
      if (!isKeyDown(keyName)) {
         return 0;
      } else if (isKeyDown(Core.getInstance().getKey(keyName))) {
         return Core.getInstance().getKey(keyName);
      } else {
         return isKeyDown(Core.getInstance().getAltKey(keyName)) ? Core.getInstance().getAltKey(keyName) : 0;
      }
   }

   public static int whichKeyDownIgnoreMouse(String keyName) {
      int key = Core.getInstance().getKey(keyName);
      if (key < 10000 && isKeyDown(key)) {
         return key;
      }

      key = Core.getInstance().getAltKey(keyName);
      return key < 10000 && isKeyDown(key) ? key : 0;
   }

   public static boolean wasKeyDown(int key) {
      if (key >= 10000) {
         return Mouse.wasButtonDown(key - 10000);
      } else if (Core.currentTextEntryBox != null && Core.currentTextEntryBox.isDoingTextEntry()) {
         return false;
      } else {
         return lastDown == null ? false : lastDown[key];
      }
   }

   public static boolean wasKeyDown(String keyName) {
      return wasKeyDown(Core.getInstance().getKey(keyName)) || wasKeyDown(Core.getInstance().getAltKey(keyName));
   }

   public static int whichKeyWasDown(String keyName) {
      if (wasKeyDown(Core.getInstance().getKey(keyName))) {
         return Core.getInstance().getKey(keyName);
      } else {
         return wasKeyDown(Core.getInstance().getAltKey(keyName)) ? Core.getInstance().getAltKey(keyName) : 0;
      }
   }

   public static void eatKeyPress(int key) {
      if (key >= 0 && key < eatKey.length) {
         eatKey[key] = true;
      }
   }

   public static void setDoLuaKeyPressed(boolean doIt) {
      doLuaKeyPressed = doIt;
   }

   public static KeyEventQueue getEventQueue() {
      assert Thread.currentThread() == GameWindow.gameThread;
      return s_keyboardStateCache.getState().getEventQueue();
   }

   public static KeyEventQueue getEventQueuePolling() {
      assert Thread.currentThread() == RenderThread.renderThread;
      return s_keyboardStateCache.getStatePolling().getEventQueue();
   }
}
