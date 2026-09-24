package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A limiter on FMOD's master channel group ({@code audioLimiter}, 2026-09-24). The stock mix has none: a pistol fired
 * next to the listener over two alarms, a thunderstorm and a horde went over full scale (true peak +0.7 dBTP, ~6,000
 * clipped samples in 25 s, stock and optimized alike; harness/audio-judge.py, runs {@code snd-i2-*}), and the sound card
 * clips it. FMOD's own limiter DSP ({@code FMOD_DSP_TYPE_LIMITER}) is created through the core C API (libfmod exports
 * it; the Java binding only hands out the master channel group) and placed at the head of the master channel group,
 * the last step before the output, so every Studio event and core channel goes through it: ceiling
 * {@code audioLimiterCeilingDb} (-2 dBFS: the 32 kHz mix is resampled on its way out, single samples overshot a -1 dBFS ceiling), 50 ms release, both channels linked (the
 * stereo image does not wander), no make-up gain. Under the ceiling it passes the mix untouched. Stock mixes 5.1 at 32 kHz
 * whatever the device; on a stereo device the limiter takes stereo input ({@code audioLimiterStereoFold}), so the 5.1 ->
 * stereo fold happens inside FMOD ahead of it instead of in the OS mixer after it, where the summed channels clipped.
 * The DSP is checked by the name FMOD reports ("FMOD Limiter") before it is added. Any failure (no library, another FMOD
 * version) logs one line and leaves the mix as stock. {@link #channelsPlaying()} reads FMOD's playing / real channel
 * counts for the harness census through the same system handle.
 */
public final class AudioLimiter {
   private static final int FMOD_OK = 0;
   private static final int FMOD_DSP_TYPE_LIMITER = 11; // fmod_dsp_effects.h, unchanged through FMOD 2.x; verified by name below
   private static final int FMOD_CHANNELCONTROL_DSP_HEAD = -1;
   private static final int FMOD_SPEAKERMODE_STEREO = 3;
   private static int softwareSpeakerMode;
   private static final int LIMITER_RELEASETIME = 0, LIMITER_CEILING = 1, LIMITER_MAXIMIZERGAIN = 2, LIMITER_MODE = 3;

   private static boolean tried;
   private static long systemPtr;
   private static MethodHandle getChannelsPlaying, getMetering;
   private static long limiterDsp;
   private static MemorySegment meterIn, meterOut;
   private static MemorySegment twoInts;
   private static String state = "not installed";

   private AudioLimiter() {
   }

   /** Called once on the main thread after the FMOD init (GameWindow override, afterFmod). */
   public static synchronized void install() {
      if (tried) {
         return;
      }
      tried = true;
      if (zombie.core.Core.soundDisabled) {
         state = "sound disabled";
         return;
      }
      try {
         Path lib = library();
         if (lib == null) {
            state = "no FMOD core library under natives/";
            Log.warn("audio limiter: " + state);
            return;
         }
         Linker linker = Linker.nativeLinker();
         SymbolLookup core = SymbolLookup.libraryLookup(lib, Arena.global());
         ValueLayout P = ValueLayout.JAVA_LONG; // handles come from Java as longs
         MethodHandle getSystem = linker.downcallHandle(core.find("FMOD_ChannelGroup_GetSystemObject").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS));
         MethodHandle createDsp = linker.downcallHandle(core.find("FMOD_System_CreateDSPByType").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
         MethodHandle getInfo = linker.downcallHandle(core.find("FMOD_DSP_GetInfo").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         MethodHandle setFloat = linker.downcallHandle(core.find("FMOD_DSP_SetParameterFloat").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT));
         MethodHandle setBool = linker.downcallHandle(core.find("FMOD_DSP_SetParameterBool").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
         MethodHandle addDsp = linker.downcallHandle(core.find("FMOD_ChannelGroup_AddDSP").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT, P));
         MethodHandle setActive = linker.downcallHandle(core.find("FMOD_DSP_SetActive").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT));
         MethodHandle release = linker.downcallHandle(core.find("FMOD_DSP_Release").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P));
         getChannelsPlaying = linker.downcallHandle(core.find("FMOD_System_GetChannelsPlaying").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         twoInts = Arena.global().allocate(8);

         long master = fmod.javafmod.FMOD_System_GetMasterChannelGroup();
         MemorySegment out = Arena.global().allocate(ValueLayout.ADDRESS);
         int r = (int) getSystem.invokeExact(master, out);
         if (r != FMOD_OK) {
            fail("ChannelGroup_GetSystemObject " + r);
            return;
         }
         systemPtr = out.get(ValueLayout.ADDRESS, 0).address();
         logFormat(linker, core);
         if (!Config.AUDIO_LIMITER || !Overrides.enabled()) {
            state = "off (audioLimiter=false or optimizations off)";
            Log.info("audio limiter: " + state);
            return;
         }
         r = (int) createDsp.invokeExact(systemPtr, FMOD_DSP_TYPE_LIMITER, out);
         if (r != FMOD_OK) {
            fail("System_CreateDSPByType " + r);
            return;
         }
         long dsp = out.get(ValueLayout.ADDRESS, 0).address();
         MemorySegment name = Arena.global().allocate(32);
         MemorySegment i1 = Arena.global().allocate(4), i2 = Arena.global().allocate(4), i3 = Arena.global().allocate(4), i4 = Arena.global().allocate(4);
         r = (int) getInfo.invokeExact(dsp, name, i1, i2, i3, i4);
         String dspName = r == FMOD_OK ? name.getString(0) : "?";
         if (!"FMOD Limiter".equals(dspName)) {
            int ignored = (int) release.invokeExact(dsp);
            fail("DSP type " + FMOD_DSP_TYPE_LIMITER + " is \"" + dspName + "\", not the limiter");
            return;
         }
         float ceiling = Config.AUDIO_LIMITER_CEILING_DB;
         int e1 = (int) setFloat.invokeExact(dsp, LIMITER_RELEASETIME, 50.0F);
         int e2 = (int) setFloat.invokeExact(dsp, LIMITER_CEILING, ceiling);
         int e3 = (int) setFloat.invokeExact(dsp, LIMITER_MAXIMIZERGAIN, 0.0F);
         int e4 = (int) setBool.invokeExact(dsp, LIMITER_MODE, 1);
         // Stock renders 5.1 whatever the device (libfmodintegration64's FMOD_System_Init hardcodes SetSoftwareFormat(32000,
         // 5.1)); on a stereo device the OS mixer (PipeWire channelmix, the Windows engine) folds the six channels into
         // two by summing them, and that sum clipped although every FMOD channel stayed under 0.75 (snd-i3c-fmt). On such a
         // device the limiter takes stereo input: FMOD folds 5.1 -> stereo in front of it, the limiter caps the real
         // stereo signal and only FL / FR reach the OS mixer. A real 5.1 / 7.1 device keeps the per-channel limiter.
         int deviceChannels = deviceChannels(linker, core);
         String fold = "per channel";
         if (deviceChannels > 0 && deviceChannels <= 2 && softwareSpeakerMode > FMOD_SPEAKERMODE_STEREO && Config.AUDIO_LIMITER_STEREO_FOLD) {
            MethodHandle chanFmt = linker.downcallHandle(core.find("FMOD_DSP_SetChannelFormat").orElseThrow(),
                  FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            int rf = (int) chanFmt.invokeExact(dsp, 0, 2, FMOD_SPEAKERMODE_STEREO);
            fold = rf == FMOD_OK ? "stereo fold (device " + deviceChannels + " ch, mix speaker mode " + softwareSpeakerMode + ")" : "per channel (stereo fold refused: " + rf + ")";
         } else {
            fold = "per channel (device " + deviceChannels + " ch, mix speaker mode " + softwareSpeakerMode + ")";
         }
         r = (int) addDsp.invokeExact(master, FMOD_CHANNELCONTROL_DSP_HEAD, dsp);
         if (r != FMOD_OK) {
            int ignored = (int) release.invokeExact(dsp);
            fail("ChannelGroup_AddDSP " + r);
            return;
         }
         int e5 = (int) setActive.invokeExact(dsp, 1);
         // where the limiter ended up in the master chain (index 0 = the output end) and metering on both sides of it
         MethodHandle numDsps = linker.downcallHandle(core.find("FMOD_ChannelGroup_GetNumDSPs").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS));
         MethodHandle dspIndex = linker.downcallHandle(core.find("FMOD_ChannelGroup_GetDSPIndex").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, P, ValueLayout.ADDRESS));
         MethodHandle setMetering = linker.downcallHandle(core.find("FMOD_DSP_SetMeteringEnabled").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
         getMetering = linker.downcallHandle(core.find("FMOD_DSP_GetMeteringInfo").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         int nd = (int) numDsps.invokeExact(master, i1);
         int di = (int) dspIndex.invokeExact(master, dsp, i2);
         int em = (int) setMetering.invokeExact(dsp, 1, 1);
         meterIn = Arena.global().allocate(272);
         meterOut = Arena.global().allocate(272);
         limiterDsp = dsp;
         Log.info("audio limiter: master chain has " + (nd == FMOD_OK ? i1.get(ValueLayout.JAVA_INT, 0) : -1) + " DSPs, limiter at index "
               + (di == FMOD_OK ? i2.get(ValueLayout.JAVA_INT, 0) : -1) + ", metering " + em);
         state = "on, " + fold + " (ceiling " + ceiling + " dBFS, release 50 ms, linked; parameter results " + e1 + "/" + e2 + "/" + e3 + "/" + e4 + ", active " + e5 + ")";
         Log.info("audio limiter: " + state + " at the head of the master channel group, " + lib.getFileName());
      } catch (Throwable t) {
         fail(t.toString());
      }
   }

   /** One line with FMOD's mix format: sample rate, speaker mode (3 stereo, 6 5.1, 7 7.1), raw speakers, output type, DSP buffer. */
   private static void logFormat(Linker linker, SymbolLookup core) {
      try {
         ValueLayout P = ValueLayout.JAVA_LONG;
         MethodHandle fmt = linker.downcallHandle(core.find("FMOD_System_GetSoftwareFormat").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         MethodHandle output = linker.downcallHandle(core.find("FMOD_System_GetOutput").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS));
         MethodHandle buf = linker.downcallHandle(core.find("FMOD_System_GetDSPBufferSize").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         MemorySegment a = Arena.global().allocate(16);
         int r1 = (int) fmt.invokeExact(systemPtr, a, a.asSlice(4), a.asSlice(8));
         if (r1 == FMOD_OK) softwareSpeakerMode = a.get(ValueLayout.JAVA_INT, 4);
         String f = r1 == FMOD_OK ? a.get(ValueLayout.JAVA_INT, 0) + " Hz, speaker mode " + a.get(ValueLayout.JAVA_INT, 4) + ", raw speakers " + a.get(ValueLayout.JAVA_INT, 8) : "? (" + r1 + ")";
         int r2 = (int) output.invokeExact(systemPtr, a);
         String o = r2 == FMOD_OK ? Integer.toString(a.get(ValueLayout.JAVA_INT, 0)) : "?";
         int r3 = (int) buf.invokeExact(systemPtr, a, a.asSlice(4));
         String b = r3 == FMOD_OK ? a.get(ValueLayout.JAVA_INT, 0) + " x " + a.get(ValueLayout.JAVA_INT, 4) : "?";
         format = f + ", output type " + o + ", DSP buffer " + b;
         Log.info("audio: FMOD mix format " + format);
      } catch (Throwable t) {
         Log.warn("audio: FMOD mix format unknown: " + t);
      }
   }

   /** Channels of the device FMOD plays to (FMOD_System_GetDriver + GetDriverInfo), or -1. */
   private static int deviceChannels(Linker linker, SymbolLookup core) {
      try {
         ValueLayout P = ValueLayout.JAVA_LONG;
         MethodHandle getDriver = linker.downcallHandle(core.find("FMOD_System_GetDriver").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.ADDRESS));
         MethodHandle info = linker.downcallHandle(core.find("FMOD_System_GetDriverInfo").orElseThrow(),
               FunctionDescriptor.of(ValueLayout.JAVA_INT, P, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                     ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
         MemorySegment d = Arena.global().allocate(4), name = Arena.global().allocate(256), guid = Arena.global().allocate(16),
               rate = Arena.global().allocate(4), mode = Arena.global().allocate(4), ch = Arena.global().allocate(4);
         int r = (int) getDriver.invokeExact(systemPtr, d);
         int id = r == FMOD_OK ? d.get(ValueLayout.JAVA_INT, 0) : 0;
         r = (int) info.invokeExact(systemPtr, id, name, 256, guid, rate, mode, ch);
         if (r != FMOD_OK) {
            return -1;
         }
         int n = ch.get(ValueLayout.JAVA_INT, 0);
         Log.info("audio: FMOD device \"" + name.getString(0) + "\" " + rate.get(ValueLayout.JAVA_INT, 0) + " Hz, speaker mode "
               + mode.get(ValueLayout.JAVA_INT, 0) + ", " + n + " channels");
         return n;
      } catch (Throwable t) {
         return -1;
      }
   }

   private static String format = "?";

   public static String format() {
      return format;
   }

   private static void fail(String why) {
      state = "failed: " + why;
      Log.warn("audio limiter: " + state + "; the mix stays as stock");
   }

   private static Path library() {
      Path dir = Path.of(System.getProperty("user.dir"), "natives");
      for (String n : new String[]{"libfmod.so", "fmod.dll", "fmod64.dll", "libfmod.dylib"}) {
         Path p = dir.resolve(n);
         if (Files.isRegularFile(p)) {
            return p.toAbsolutePath();
         }
      }
      return null;
   }

   public static String state() {
      return state;
   }

   /**
    * The limiter's input and output peak (linear, max over channels) of the block FMOD metered last, or null. FMOD's
    * FMOD_DSP_METERING_INFO: int numsamples, float peaklevel[32], float rmslevel[32], short numchannels.
    */
   public static float[] meter() {
      if (getMetering == null || limiterDsp == 0L) {
         return null;
      }
      try {
         int r = (int) getMetering.invokeExact(limiterDsp, meterIn, meterOut);
         if (r != FMOD_OK) {
            return null;
         }
         return new float[]{peak(meterIn), peak(meterOut)};
      } catch (Throwable t) {
         return null;
      }
   }

   private static float peak(MemorySegment info) {
      int n = Math.min(32, Math.max(0, info.get(ValueLayout.JAVA_SHORT, 260)));
      float m = 0f;
      for (int c = 0; c < n; c++) {
         m = Math.max(m, info.get(ValueLayout.JAVA_FLOAT, 4 + 4L * c));
      }
      return m;
   }

   /** {playing, real} channels of the FMOD core system (virtual = playing - real), or null before install / on failure. */
   public static int[] channelsPlaying() {
      if (getChannelsPlaying == null || systemPtr == 0L) {
         return null;
      }
      try {
         int r = (int) getChannelsPlaying.invokeExact(systemPtr, twoInts, twoInts.asSlice(4));
         return r == FMOD_OK ? new int[]{twoInts.get(ValueLayout.JAVA_INT, 0), twoInts.get(ValueLayout.JAVA_INT, 4)} : null;
      } catch (Throwable t) {
         return null;
      }
   }
}
