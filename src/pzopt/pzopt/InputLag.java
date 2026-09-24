package pzopt;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.time.Instant;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjglx.input.GamepadState;
import org.lwjglx.input.Keyboard;
import zombie.GameWindow;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.input.GameKeyboard;
import zombie.input.KeyboardStateCache;
import zombie.input.MouseStateCache;

/**
 * Harness-only input-lag probe (2026-09-24, {@code --flag inputlag=1}, run.sh {@code --inputlag <script>}): stamps
 * every stage an input passes on its way to the screen, so harness/inputlag.py can line each injected press
 * (harness/inputlag-drive.py, uinput keyboard + mouse + Xbox 360 pad, epoch µs) up with:
 * <ol>
 * <li>{@code os}: the render thread's {@code glfwPollEvents} (Display.processMessages) delivered it to lwjglx
 * (keyboard, mouse; a pad has no event stage, GLFW reads it at the poll);</li>
 * <li>{@code smp}: the render thread's input poll copied it into the polling state (gated: once per game-thread
 * swap, so it can sit in lwjglx until the next effective poll);</li>
 * <li>{@code game}: the game thread's {@code logic()} swapped it in (GameKeyboard / Mouse / GameInput update);</li>
 * <li>{@code R}: player 0's position, facing, rendered (anim) facing or aim state changed, sampled after the frame's
 * update, before its render;</li>
 * <li>{@code P}: that frame was handed to the render thread ({@code RenderThread.Ready}, with the ready-slot wait);</li>
 * <li>{@code A}: the render thread took it; {@code S}: its swap returned; {@code G}: the GPU finished it (a
 * GL_TIMESTAMP query after the sprite replay, mapped to the CPU clock).</li>
 * </ol>
 * The log is {@code Zomboid/pzopt-inputlag.out}, one line per event, times in epoch µs (CLOCK_REALTIME, the clock the
 * injector stamps with). Input lines ({@code os|smp|game <bits hex> <mouse x> t=<us>}) only when a watched input changes:
 * bits 0-3 keys W S A D, 4-5 mouse left / right, 6-9 left stick up down left right, 10-13 right stick, 14 pad A,
 * 15 left trigger (aim) (analog inputs past 0.5). Frame lines: {@code L <us>} (the game frame's input swap), {@code P <n>
 * <us> <wait us>}, {@code A <n> <us>}, {@code S <n> <us>}, {@code G <n> <us>}, {@code R <us> x y dir anim aim} when the
 * player changed. Costs a few µs a frame; off (one volatile read per hook) without the flag.
 */
public final class InputLag {
   private static final int[] KEYS = {Keyboard.KEY_W, Keyboard.KEY_S, Keyboard.KEY_A, Keyboard.KEY_D};
   private static final int MOUSE_STEP = 64; // mouse x changes smaller than this are not logged
   private static final int RING = 16;

   private static volatile int state; // 0 = undecided, 1 = on, -1 = off
   private static long offsetNs; // epoch ns = nanoTime + offsetNs
   private static final StringBuilder buf = new StringBuilder(1 << 16);
   private static Writer out;

   private static int osBits = -1, osMouse = Integer.MIN_VALUE;
   private static int smpBits = -1, smpMouse = Integer.MIN_VALUE;
   private static int gameBits = -1, gameMouse = Integer.MIN_VALUE;
   private static long pushes, acquires;
   private static float px = Float.NaN, py, pdir, panim;
   private static boolean paim;

   private static KeyboardStateCache kbCache;
   private static MouseStateCache mouseCache;
   private static java.lang.reflect.Method mousePolling; // MouseStateCache.getStatePolling is private
   private static Object padCache;
   private static Field padStates, padPollingIndex, padUsingIndex, padGamepadState;

   private static int[] queries;
   private static long[] queryFrame;
   private static int gpuState; // 0 = not set up, 1 = on, -1 = no timer queries
   private static long gpuOffsetNs; // cpu nanoTime = gpu ns + gpuOffsetNs
   private static long gpuCalibratedNs;

   private InputLag() {
   }

   private static boolean on() {
      int s = state;
      if (s == 0) {
         s = decide();
      }
      return s > 0;
   }

   private static synchronized int decide() {
      if (state != 0) {
         return state;
      }
      String dir;
      try {
         dir = ZomboidFileSystem.instance == null ? null : ZomboidFileSystem.instance.getCacheDir();
      } catch (RuntimeException e) {
         dir = null;
      }
      if (dir == null) {
         return 0; // too early; the next hook asks again
      }
      File flags = new File(dir, "Lua" + File.separator + "pzopt-harness.txt");
      boolean wanted = false;
      try {
         wanted = flags.isFile() && java.nio.file.Files.readAllLines(flags.toPath()).stream().anyMatch(l -> l.trim().equals("inputlag=1"));
      } catch (IOException e) {
         Log.warn("input lag: " + e);
      }
      if (!wanted) {
         state = -1;
         return -1;
      }
      try {
         Field f = GameKeyboard.class.getDeclaredField("s_keyboardStateCache");
         f.setAccessible(true);
         kbCache = (KeyboardStateCache)f.get(null);
         f = zombie.input.Mouse.class.getDeclaredField("s_mouseStateCache");
         f.setAccessible(true);
         mouseCache = (MouseStateCache)f.get(null);
         mousePolling = MouseStateCache.class.getDeclaredMethod("getStatePolling");
         mousePolling.setAccessible(true);
         f = GameWindow.GameInput.getClass().getDeclaredField("controllerStateCache");
         f.setAccessible(true);
         padCache = f.get(GameWindow.GameInput);
         Class<?> c = padCache.getClass();
         padStates = accessible(c.getDeclaredField("states"));
         padPollingIndex = accessible(c.getDeclaredField("stateIndexPolling"));
         padUsingIndex = accessible(c.getDeclaredField("stateIndexUsing"));
         padGamepadState = accessible(padStates.getType().getComponentType().getDeclaredField("gamepadState"));
         out = new FileWriter(new File(dir, "pzopt-inputlag.out"));
      } catch (ReflectiveOperationException | IOException | RuntimeException e) {
         Log.warn("input lag: off after " + e);
         state = -1;
         return -1;
      }
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
      Thread t = new Thread(InputLag::flushLoop, "pzopt-inputlag");
      t.setDaemon(true);
      t.start();
      append("# pzopt input lag log; times epoch us; clock pair error " + best + " ns\n");
      Log.info("input lag: probe on, log pzopt-inputlag.out");
      state = 1;
      return 1;
   }

   private static Field accessible(Field f) {
      f.setAccessible(true);
      return f;
   }

   private static long us(long nanoTime) {
      return (nanoTime + offsetNs) / 1000L;
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

   private static int padBits(Object controllerState) throws IllegalAccessException {
      int bits = 0;
      for (GamepadState g : (GamepadState[])padGamepadState.get(controllerState)) {
         if (g == null) {
            continue;
         }
         GLFWGamepadState s = g.axesButtons;
         float lx = s.axes(0), ly = s.axes(1), rx = s.axes(2), ry = s.axes(3), lt = s.axes(4);
         bits |= (ly < -0.5f ? 1 << 6 : 0) | (ly > 0.5f ? 1 << 7 : 0) | (lx < -0.5f ? 1 << 8 : 0) | (lx > 0.5f ? 1 << 9 : 0)
               | (ry < -0.5f ? 1 << 10 : 0) | (ry > 0.5f ? 1 << 11 : 0) | (rx < -0.5f ? 1 << 12 : 0) | (rx > 0.5f ? 1 << 13 : 0)
               | (s.buttons(0) != 0 ? 1 << 14 : 0) | (lt > 0.0f ? 1 << 15 : 0); // triggers rest at -1 in GLFW
      }
      return bits;
   }

   private static Object padState(Field index) throws IllegalAccessException {
      return ((Object[])padStates.get(padCache))[index.getInt(padCache)];
   }

   private static int bucket(int x) {
      return Math.floorDiv(x, MOUSE_STEP);
   }

   /** Render thread, Display.processMessages right after glfwPollEvents: what lwjglx holds now. */
   public static void afterEvents() {
      if (!on()) {
         return;
      }
      long now = System.nanoTime();
      int bits = 0;
      for (int i = 0; i < KEYS.length; i++) {
         if (Keyboard.isKeyDown(KEYS[i])) {
            bits |= 1 << i;
         }
      }
      bits |= (org.lwjglx.input.Mouse.isButtonDown(0) ? 1 << 4 : 0) | (org.lwjglx.input.Mouse.isButtonDown(1) ? 1 << 5 : 0);
      int mx = org.lwjglx.input.Mouse.getX();
      if (bits != osBits || bucket(mx) != bucket(osMouse)) {
         osBits = bits;
         osMouse = mx;
         append("os " + Integer.toHexString(bits) + " " + mx + " t=" + us(now) + "\n");
      }
   }

   /** Render thread, after its GameInput / Mouse / GameKeyboard polls: the polling states the game will swap in. */
   public static void afterPoll() {
      if (!on()) {
         return;
      }
      long now = System.nanoTime();
      try {
         int bits = 0;
         var kb = kbCache.getStatePolling();
         if (kb.isCreated()) {
            for (int i = 0; i < KEYS.length; i++) {
               if (kb.isKeyDown(KEYS[i])) {
                  bits |= 1 << i;
               }
            }
         }
         var m = (zombie.input.MouseState)mousePolling.invoke(mouseCache);
         int mx = 0;
         if (m.isCreated()) {
            bits |= (m.isButtonDown(0) ? 1 << 4 : 0) | (m.isButtonDown(1) ? 1 << 5 : 0);
            mx = m.getX();
         }
         bits |= padBits(padState(padPollingIndex));
         if (bits != smpBits || bucket(mx) != bucket(smpMouse)) {
            smpBits = bits;
            smpMouse = mx;
            append("smp " + Integer.toHexString(bits) + " " + mx + " t=" + us(now) + "\n");
         }
      } catch (ReflectiveOperationException | RuntimeException e) {
         Log.warn("input lag: poll stage off after " + e);
         state = -1;
      }
   }

   /** Game thread, GameWindow.logic right after GameInput.updateGameThread: the input this game frame sees. */
   public static void afterGameInput() {
      InputRecorder.frame(); // every input change of this frame (inputLog), independent of the input-lag probe
      if (!on()) {
         return;
      }
      long now = System.nanoTime();
      try {
         int bits = 0;
         for (int i = 0; i < KEYS.length; i++) {
            if (GameKeyboard.isKeyDown(KEYS[i])) {
               bits |= 1 << i;
            }
         }
         var m = mouseCache.getState();
         int mx = 0;
         if (m.isCreated()) {
            bits |= (m.isButtonDown(0) ? 1 << 4 : 0) | (m.isButtonDown(1) ? 1 << 5 : 0);
            mx = m.getX();
         }
         bits |= padBits(padState(padUsingIndex));
         IsoPlayer p0 = IsoPlayer.players[0];
         if (zombie.GameWindow.activatedJoyPad == null && p0 != null && p0.getJoypadBind() != -1) {
            // the harness Lua bound the driver's pad to player 1 (inputlag_pad=1); the player only reads a pad once one
            // has been activated, which a real A press does (GameWindow.logic) -- this stands in for that press
            zombie.GameWindow.activatedJoyPad = zombie.input.JoypadManager.instance.getFromControllerID(p0.getJoypadBind());
            Log.info("input lag: joypad " + p0.getJoypadBind() + " activated for player 1");
         }
         StringBuilder sb = new StringBuilder(48);
         sb.append("L ").append(us(now)).append('\n');
         if (bits != gameBits || bucket(mx) != bucket(gameMouse)) {
            gameBits = bits;
            gameMouse = mx;
            sb.append("game ").append(Integer.toHexString(bits)).append(' ').append(mx).append(" t=").append(us(now)).append('\n');
         }
         append(sb.toString());
      } catch (IllegalAccessException | RuntimeException e) {
         Log.warn("input lag: game stage off after " + e);
         state = -1;
      }
   }

   /** Game thread, GameWindow.frameStep before renderInternal: player 0 after this frame's update. */
   public static void beforeRender() {
      if (!on()) {
         return;
      }
      IsoPlayer p = IsoPlayer.players[0];
      if (p == null) {
         return;
      }
      float x = p.getX(), y = p.getY(), dir = p.getDirectionAngle(), anim = p.getAnimAngle();
      boolean aim = p.isAiming();
      if (x != px || y != py || Math.abs(dir - pdir) > 0.5f || Math.abs(anim - panim) > 0.5f || aim != paim) {
         px = x;
         py = y;
         pdir = dir;
         panim = anim;
         paim = aim;
         append("R " + us(System.nanoTime()) + " " + x + " " + y + " " + Math.round(dir) + " " + Math.round(anim) + " " + (aim ? 1 : 0) + "\n");
      }
   }

   /** Game thread, RenderThread.Ready after pushFrameDown: the frame is queued for the render thread. */
   public static void pushed(long startNs) {
      if (!on()) {
         return;
      }
      long now = System.nanoTime();
      append("P " + ++pushes + " " + us(now) + " " + (now - startNs) / 1000L + "\n");
   }

   /** Render thread, lockStepRenderStep after a state was acquired. */
   public static void acquired() {
      if (!on()) {
         return;
      }
      append("A " + ++acquires + " " + us(System.nanoTime()) + "\n");
   }

   /** Render thread, after the sprite replay (all of the frame's GL commands are queued): its GPU-completion query. */
   public static void frameQueued() {
      if (!on() || gpuState < 0) {
         return;
      }
      try {
         if (gpuState == 0) {
            if (!GL.getCapabilities().OpenGL33 && !GL.getCapabilities().GL_ARB_timer_query) {
               gpuState = -1;
               return;
            }
            queries = new int[RING];
            queryFrame = new long[RING];
            GL15.glGenQueries(queries);
            gpuState = 1;
         }
         long now = System.nanoTime();
         if (now - gpuCalibratedNs > 1_000_000_000L) {
            long a = System.nanoTime();
            long g = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
            long b = System.nanoTime();
            gpuOffsetNs = a + (b - a) / 2 - g;
            gpuCalibratedNs = now;
         }
         int slot = (int)(acquires % RING);
         if (queryFrame[slot] != 0L) {
            collect(slot, true); // the ring came round: this slot's frame is 16 frames old, its result is in
         }
         GL33.glQueryCounter(queries[slot], GL33.GL_TIMESTAMP);
         queryFrame[slot] = acquires;
         for (int i = 0; i < RING; i++) {
            if (queryFrame[i] != 0L && i != slot) {
               collect(i, false);
            }
         }
      } catch (RuntimeException e) {
         Log.warn("input lag: GPU stage off after " + e);
         gpuState = -1;
      }
   }

   private static void collect(int i, boolean wait) {
      if (!wait && GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
         return;
      }
      long g = GL33.glGetQueryObjecti64(queries[i], GL15.GL_QUERY_RESULT);
      append("G " + queryFrame[i] + " " + us(g + gpuOffsetNs) + "\n");
      queryFrame[i] = 0L;
   }

   /** Render thread, Display.update right after the buffer swap returned. */
   public static void swapped() {
      if (!on()) {
         return;
      }
      append("S " + acquires + " " + us(System.nanoTime()) + "\n"); // Display.update runs only in lockStepRenderStep, after the acquire
   }
}
