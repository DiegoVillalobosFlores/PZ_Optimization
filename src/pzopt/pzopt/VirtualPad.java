package pzopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryUtil;
import org.lwjglx.input.Controller;
import org.lwjglx.input.Controllers;
import org.lwjglx.input.GamepadState;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;

/**
 * Harness-only virtual gamepad (2026-09-23, menu profiling on machines without uinput, e.g. the Mac): {@code --flag
 * pad=<script>} in the harness flag file turns it on. A fake {@link Controller} (no GLFW device behind it) sits in
 * {@link Controllers}' slot {@link #ID}; the render thread's input poll ({@link #poll}, called from RenderThread instead
 * of {@code GameWindow.GameInput.poll()}) then writes its buttons and hat into the polling {@code GamepadState} under
 * the controller cache's lock, so everything from {@code zombie.core.input.Input} up (JoypadManager, the Lua
 * JoypadControllerData, the menus) runs exactly as for a real pad. The GUID is the Xbox 360 one harness/pad.py uses;
 * the run puts it in options.ini ({@code --option controller=<GUID>}) so the game lists the pad.
 *
 * <p>The script is {@code Zomboid/mods/pzopt-harness/pad/<script>.txt} (shipped with the harness mod) in pad.py's
 * language: a b x y start back lb rb up down left right (a 120 ms press), {@code hold <button> <s>}, {@code sleep <s>},
 * {@code mark <name>}, {@code done}. It starts 2 s after the harness Lua wrote {@code Lua/pzopt-pad-ready.txt} (the main
 * menu accepts input); every press, mark and "done" goes to {@code Zomboid/pzopt-pad.out} with its epoch ms in pad.py's
 * format (harness/padlat.py reads either), and "done" appends {@code pad_done=1} to the flag file (the Lua quits).
 */
public final class VirtualPad {
   public static final int ID = 15;
   public static final String GUID = "030000005e0400008e02000010010000";
   private static final String[] BUTTONS = {"a", "b", "x", "y", "lb", "rb", "back", "start"}; // GLFW gamepad order
   private static final int HAT_UP = 1, HAT_RIGHT = 2, HAT_DOWN = 4, HAT_LEFT = 8;
   private static final int DPAD_UP = 11, DPAD_RIGHT = 12, DPAD_DOWN = 13, DPAD_LEFT = 14;

   /** Off until the flag file names a script; decided once the Zomboid dir is known. */
   private static volatile int state; // 0 = undecided, 1 = on, -1 = off
   private static String script;
   private static volatile int buttons; // bit i = GLFW gamepad button i
   private static volatile int hat;
   private static Controller pad;
   private static Field cacheField, lockField, statesField, pollingIndexField, gamepadStateField;

   private VirtualPad() {
   }

   /** RenderThread: the input poll, with the virtual pad's state written into the polling state under the same lock. */
   public static void poll() {
      if (state == 0) {
         decide();
      }
      if (state < 0 || !Controllers.isCreated()) {
         GameWindow.GameInput.poll();
         return;
      }
      try {
         if (pad == null) {
            install();
         }
         Object cache = cacheField.get(GameWindow.GameInput);
         synchronized (lockField.get(cache)) {
            GameWindow.GameInput.poll();
            Object[] states = (Object[])statesField.get(cache);
            Object polling = states[pollingIndexField.getInt(cache)];
            write(((GamepadState[])gamepadStateField.get(polling))[ID]);
         }
      } catch (ReflectiveOperationException | RuntimeException e) {
         Log.warn("virtual pad: off after " + e);
         state = -1;
         GameWindow.GameInput.poll();
      }
   }

   private static void decide() {
      String dir = ZomboidFileSystem.instance == null ? null : ZomboidFileSystem.instance.getCacheDir();
      if (dir == null) {
         return; // too early; the next poll asks again
      }
      String name = null;
      File flags = new File(dir, "Lua" + File.separator + "pzopt-harness.txt");
      if (flags.isFile()) {
         try (BufferedReader r = new BufferedReader(new FileReader(flags))) {
            for (String line; (line = r.readLine()) != null; ) {
               if (line.startsWith("pad=")) {
                  name = line.substring(4).trim();
               }
            }
         } catch (IOException e) {
            Log.warn("virtual pad: " + e);
         }
      }
      if (name == null || name.isEmpty() || name.equals("1")) {
         state = -1; // pad=1 is run.sh --pad (the uinput pad drives the game)
         return;
      }
      script = name;
      state = 1;
   }

   private static void install() throws ReflectiveOperationException {
      Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      Object unsafe = unsafeField.get(null);
      Controller c = (Controller)unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Controller.class);
      set(c, "id", ID);
      set(c, "joystickName", "pzopt virtual pad");
      set(c, "gamepadName", "Xbox Controller");
      set(c, "isGamepad", true);
      set(c, "axisCount", 6);
      set(c, "buttonsCount", 15);
      set(c, "hatCount", 1);
      set(c, "guid", GUID);
      float[] dz = new float[6];
      Arrays.fill(dz, 0.2f);
      set(c, "deadZone", dz);
      cacheField = accessible(GameWindow.GameInput.getClass().getDeclaredField("controllerStateCache"));
      Class<?> cacheClass = cacheField.getType();
      lockField = accessible(cacheClass.getDeclaredField("lock"));
      statesField = accessible(cacheClass.getDeclaredField("states"));
      pollingIndexField = accessible(cacheClass.getDeclaredField("stateIndexPolling"));
      gamepadStateField = accessible(statesField.getType().getComponentType().getDeclaredField("gamepadState"));
      Field slots = accessible(Controllers.class.getDeclaredField("controllers"));
      ((Controller[])slots.get(null))[ID] = c; // Input sees it at its next updateGameThread (checkConnectDisconnect)
      pad = c;
      String dir = ZomboidFileSystem.instance.getCacheDir();
      new File(dir, "Lua" + File.separator + "pzopt-pad-ready.txt").delete(); // a stale one from an earlier run
      Thread t = new Thread(() -> play(dir), "pzopt-virtual-pad");
      t.setDaemon(true);
      t.start();
      Log.info("virtual pad: controller " + ID + " (" + GUID + "), script " + script);
   }

   private static void write(GamepadState s) {
      int b = buttons;
      int h = hat;
      long base = s.axesButtons.address();
      for (int i = 0; i < 15; i++) {
         MemoryUtil.memPutByte(base + GLFWGamepadState.BUTTONS + i, (byte)((b >> i) & 1));
      }
      for (int i = 0; i < 6; i++) {
         MemoryUtil.memPutFloat(base + GLFWGamepadState.AXES + 4L * i, 0f);
      }
      s.hatState = h;
      s.polled = true;
   }

   private static void play(String dir) {
      File out = new File(dir, "pzopt-pad.out");
      try (PrintWriter log = new PrintWriter(new FileWriter(out, false), true)) {
         log.println("pad ready: virtual controller " + ID + " t=" + System.currentTimeMillis());
         File file = new File(dir, "mods" + File.separator + "pzopt-harness" + File.separator + "pad" + File.separator + script + ".txt");
         List<String> lines;
         try {
            lines = Files.readAllLines(file.toPath());
         } catch (IOException e) {
            log.println("script not found: " + file);
            Log.warn("virtual pad: script not found: " + file);
            return;
         }
         File ready = new File(dir, "Lua" + File.separator + "pzopt-pad-ready.txt");
         while (!ready.isFile()) {
            Thread.sleep(100);
         }
         log.println("menu ready t=" + System.currentTimeMillis());
         Thread.sleep(2000);
         for (String raw : lines) {
            String[] p = raw.trim().split("\\s+");
            if (p[0].isEmpty() || p[0].startsWith("#")) {
               continue;
            }
            switch (p[0]) {
               case "sleep" -> Thread.sleep((long)(Double.parseDouble(p[1]) * 1000));
               case "mark" -> log.println("mark " + (p.length > 1 ? p[1] : "") + " t=" + System.currentTimeMillis());
               case "done" -> {
                  log.println("done t=" + System.currentTimeMillis());
                  try (FileWriter w = new FileWriter(new File(dir, "Lua" + File.separator + "pzopt-harness.txt"), true)) {
                     w.write("pad_done=1\n");
                  }
               }
               case "hold" -> {
                  long[] t = press(p[1], (long)(Double.parseDouble(p[2]) * 1000));
                  if (t != null) {
                     log.println("hold " + p[1] + " t=" + t[0] + " up=" + t[1]);
                  }
               }
               default -> {
                  long[] t = press(p[0], 120);
                  if (t != null) {
                     log.println("press " + p[0] + " t=" + t[0] + " up=" + t[1]);
                  } else {
                     log.println("unknown command: " + p[0]);
                  }
               }
            }
         }
      } catch (IOException | InterruptedException | RuntimeException e) {
         Log.warn("virtual pad: script stopped: " + e);
      }
   }

   /** Holds one button or D-pad direction for ms; the epoch ms of the down and the up, null for an unknown name. */
   private static long[] press(String name, long ms) throws InterruptedException {
      int bit = List.of(BUTTONS).indexOf(name);
      int h = switch (name) {
         case "up" -> HAT_UP;
         case "down" -> HAT_DOWN;
         case "left" -> HAT_LEFT;
         case "right" -> HAT_RIGHT;
         default -> 0;
      };
      int dpad = switch (name) {
         case "up" -> DPAD_UP;
         case "down" -> DPAD_DOWN;
         case "left" -> DPAD_LEFT;
         case "right" -> DPAD_RIGHT;
         default -> -1;
      };
      if (bit < 0 && h == 0) {
         return null;
      }
      int mask = bit >= 0 ? 1 << bit : 1 << dpad;
      buttons |= mask;
      hat |= h;
      long t0 = System.currentTimeMillis();
      Thread.sleep(ms);
      buttons &= ~mask;
      hat &= ~h;
      return new long[] {t0, System.currentTimeMillis()};
   }

   private static Field accessible(Field f) {
      f.setAccessible(true);
      return f;
   }

   private static void set(Object o, String name, Object value) throws ReflectiveOperationException {
      accessible(o.getClass().getDeclaredField(name)).set(o, value);
   }
}
