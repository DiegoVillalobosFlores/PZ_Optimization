package pzopt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.time.Instant;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjglx.input.GamepadState;
import org.lwjglx.input.Keyboard;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;

/**
 * Every input the game thread sees, one line per change (2026-09-24, key {@code inputLog}, on in every harness run:
 * its default is {@code instrument}). Called once per game frame right after {@code GameInput.updateGameThread()}
 * (the InputLag hook in the GameWindow override), so it records what the game actually swapped in, whatever sent it:
 * a real keyboard / mouse / pad, uinput (harness/pad.py, inputlag-drive.py), xdotool, or {@link VirtualPad}.
 *
 * <p>{@code Zomboid/pzopt-input.out}, tab-separated {@code <epoch us> <device> <control> <value>}:
 * {@code key <name> 1|0} (every lwjglx key code, raw state), {@code mouse left|right|middle|b<N> 1|0},
 * {@code mouse x|y <px>} (absolute, when it moved), {@code mouse wheel <delta>} (non-zero deltas),
 * {@code pad<N> <button> 1|0} (GLFW gamepad order a b x y lb rb back start guide l3 r3 up right down left),
 * {@code pad<N> lx|ly|rx|ry|lt|rt <value>} (when it moved by 0.02 or more, -1..1; triggers rest at -1) and
 * {@code pad<N> hat <bits>}. Header lines start with {@code #}. Flushed every 250 ms, so harness/grafana/ingest.py
 * --follow can stream it live. Costs one pass over ~350 booleans a frame.
 */
public final class InputRecorder {
   private static final String[] PAD_BUTTONS = {"a", "b", "x", "y", "lb", "rb", "back", "start", "guide", "l3", "r3", "up", "right", "down", "left"};
   private static final String[] PAD_AXES = {"lx", "ly", "rx", "ry", "lt", "rt"};
   private static final float AXIS_STEP = 0.02f;

   private static volatile int state; // 0 = undecided, 1 = on, -1 = off
   private static long offsetNs;
   private static final StringBuilder buf = new StringBuilder(1 << 16);
   private static Writer out;

   private static boolean[] keys = new boolean[0];
   private static String[] keyNames;
   private static boolean[] buttons = new boolean[0];
   private static int mouseX = Integer.MIN_VALUE, mouseY = Integer.MIN_VALUE;
   private static byte[][] padButtons = new byte[0][];
   private static float[][] padAxes = new float[0][];
   private static int[] padHats = new int[0];

   private static Object padCache;
   private static Field padStates, padUsingIndex, padGamepadState;

   private InputRecorder() {
   }

   /** Game thread, once per frame after the game swapped in this frame's input. */
   public static void frame() {
      int s = state;
      if (s == 0) {
         s = decide();
      }
      if (s <= 0) {
         return;
      }
      try {
         record(System.nanoTime());
      } catch (IllegalAccessException | RuntimeException e) {
         Log.warn("input log: off after " + e);
         state = -1;
      }
   }

   private static synchronized int decide() {
      if (state != 0) {
         return state;
      }
      if (!Config.INPUT_LOG) {
         state = -1;
         return -1;
      }
      String dir;
      try {
         dir = ZomboidFileSystem.instance == null ? null : ZomboidFileSystem.instance.getCacheDir();
      } catch (RuntimeException e) {
         dir = null;
      }
      if (dir == null) {
         return 0; // too early; the next frame asks again
      }
      try {
         Field f = GameWindow.GameInput.getClass().getDeclaredField("controllerStateCache");
         f.setAccessible(true);
         padCache = f.get(GameWindow.GameInput);
         Class<?> c = padCache.getClass();
         padStates = accessible(c.getDeclaredField("states"));
         padUsingIndex = accessible(c.getDeclaredField("stateIndexUsing"));
         padGamepadState = accessible(padStates.getType().getComponentType().getDeclaredField("gamepadState"));
         out = new FileWriter(new File(dir, "pzopt-input.out"));
      } catch (ReflectiveOperationException | IOException | RuntimeException e) {
         Log.warn("input log: off after " + e);
         state = -1;
         return -1;
      }
      keyNames = new String[Keyboard.KEYBOARD_SIZE];
      for (int i = 0; i < keyNames.length; i++) {
         String n = null;
         try {
            n = Keyboard.getKeyName(i);
         } catch (RuntimeException ignored) {
         }
         keyNames[i] = n == null || n.isEmpty() ? "key" + i : n;
      }
      keys = new boolean[Keyboard.KEYBOARD_SIZE];
      long best = Long.MAX_VALUE;
      for (int i = 0; i < 20; i++) {
         long a = System.nanoTime();
         Instant now = Instant.now();
         long b = System.nanoTime();
         if (b - a < best) {
            best = b - a;
            offsetNs = now.getEpochSecond() * 1_000_000_000L + now.getNano() - (a + (b - a) / 2);
         }
      }
      Thread t = new Thread(InputRecorder::flushLoop, "pzopt-input-log");
      t.setDaemon(true);
      t.start();
      append("# pzopt input log: epoch_us\tdevice\tcontrol\tvalue; one line per change the game thread sees\n");
      Log.info("input log: every key / mouse / pad change to pzopt-input.out");
      state = 1;
      return 1;
   }

   private static Field accessible(Field f) {
      f.setAccessible(true);
      return f;
   }

   private static void record(long nowNs) throws IllegalAccessException {
      long us = (nowNs + offsetNs) / 1000L;
      StringBuilder sb = null;
      for (int i = 0; i < keys.length; i++) {
         boolean d;
         try {
            d = GameKeyboard.isKeyDownRaw(i);
         } catch (ArrayIndexOutOfBoundsException e) { // the game's key array is shorter than lwjglx's key space
            keys = java.util.Arrays.copyOf(keys, i);
            break;
         }
         if (d != keys[i]) {
            keys[i] = d;
            sb = line(sb, us, "key", keyNames[i], d ? "1" : "0");
         }
      }
      boolean[] mb = Mouse.buttonDownStates;
      if (mb != null) {
         if (buttons.length != mb.length) {
            buttons = new boolean[mb.length];
         }
         for (int i = 0; i < mb.length; i++) {
            if (mb[i] != buttons[i]) {
               buttons[i] = mb[i];
               sb = line(sb, us, "mouse", i == 0 ? "left" : i == 1 ? "right" : i == 2 ? "middle" : "b" + i, mb[i] ? "1" : "0");
            }
         }
      }
      int x = Mouse.getXA(), y = Mouse.getYA();
      if (x != mouseX) {
         mouseX = x;
         sb = line(sb, us, "mouse", "x", Integer.toString(x));
      }
      if (y != mouseY) {
         mouseY = y;
         sb = line(sb, us, "mouse", "y", Integer.toString(y));
      }
      int wheel = Mouse.getWheelState();
      if (wheel != 0) {
         sb = line(sb, us, "mouse", "wheel", Integer.toString(wheel));
      }
      Object[] states = (Object[])padStates.get(padCache);
      Object using = states[padUsingIndex.getInt(padCache)];
      GamepadState[] pads = using == null ? null : (GamepadState[])padGamepadState.get(using);
      if (pads != null) {
         if (padButtons.length != pads.length) {
            padButtons = new byte[pads.length][PAD_BUTTONS.length];
            padAxes = new float[pads.length][PAD_AXES.length];
            padHats = new int[pads.length];
            for (float[] a : padAxes) {
               java.util.Arrays.fill(a, Float.NaN);
            }
         }
         for (int p = 0; p < pads.length; p++) {
            GamepadState g = pads[p];
            if (g == null) {
               continue;
            }
            GLFWGamepadState gs = g.axesButtons;
            String dev = "pad" + p;
            for (int b = 0; b < PAD_BUTTONS.length; b++) {
               byte v = gs.buttons(b);
               if (v != padButtons[p][b]) {
                  padButtons[p][b] = v;
                  sb = line(sb, us, dev, PAD_BUTTONS[b], v != 0 ? "1" : "0");
               }
            }
            for (int a = 0; a < PAD_AXES.length; a++) {
               float v = gs.axes(a);
               float last = padAxes[p][a];
               if (last != last ? Math.abs(v) >= AXIS_STEP && !(a >= 4 && v <= -1f + AXIS_STEP) : Math.abs(v - last) >= AXIS_STEP) {
                  padAxes[p][a] = v;
                  sb = line(sb, us, dev, PAD_AXES[a], String.format(java.util.Locale.ROOT, "%.3f", v));
               }
            }
            if (g.hatState != padHats[p]) {
               padHats[p] = g.hatState;
               sb = line(sb, us, dev, "hat", Integer.toString(g.hatState));
            }
         }
      }
      if (sb != null) {
         append(sb.toString());
      }
   }

   private static StringBuilder line(StringBuilder sb, long us, String device, String control, String value) {
      if (sb == null) {
         sb = new StringBuilder(64);
      }
      return sb.append(us).append('\t').append(device).append('\t').append(control).append('\t').append(value).append('\n');
   }

   private static void append(String s) {
      synchronized (buf) {
         buf.append(s);
      }
   }

   private static void flushLoop() {
      while (true) {
         try {
            Thread.sleep(250L);
            String s;
            synchronized (buf) {
               if (buf.isEmpty()) {
                  continue;
               }
               s = buf.toString();
               buf.setLength(0);
            }
            out.write(s);
            out.flush();
         } catch (InterruptedException | IOException e) {
            return;
         }
      }
   }
}
