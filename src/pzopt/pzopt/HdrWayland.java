package pzopt;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wayland color management (staging protocol wp_color_manager_v1) for the game's own wl_surface, spoken through
 * libwayland-client with the JDK foreign-function API: no native library ships with pzopt (release policy), so the
 * protocol's interface tables (what wayland-scanner would generate) are built here in native memory, requests go
 * through wl_proxy_marshal_flags and events come back through upcall stubs.
 *
 * Everything runs on a private event queue (wl_display_create_queue + a display wrapper) so GLFW's own dispatch of
 * the default queue never sees our objects; events of ours are delivered only inside {@link #pump()} and the
 * round trips below, on the calling thread (the render thread, the one that owns the GL context and the swap).
 *
 * Flow: bind the manager, learn its features / transfer functions, ask the surface feedback for the preferred image
 * description of the surface (the output's luminances: reference white = the desktop's SDR brightness, target max =
 * the panel's peak), then attach an extended-linear sRGB-primaries image description with exactly those luminances,
 * so a pixel value v means v * maxLum cd/m² on the panel and the compositor has nothing to tone-map.
 */
public final class HdrWayland {
   private HdrWayland() {
   }

   // ---- results, read by pzopt.Hdr ----
   /** True once an image description is attached to the surface. */
   public static volatile boolean active;
   public static volatile String status = "not started";
   /** The output's preferred luminances (cd/m²): min, max, reference white; target max (the panel's peak) or 0. */
   public static volatile double prefMin = 0.2, prefMax = 80, prefRef = 80, prefTargetMax, prefTargetMin;
   /** Luminances of the attached description: a pixel value 1.0 is {@code encMax} cd/m². */
   public static volatile double encMax = 80, encRef = 80;
   /** Bumped whenever the compositor says the preferred description changed (output moved, HDR toggled). */
   public static volatile int preferredGeneration;

   private static final Linker LINKER = Linker.nativeLinker();
   private static final Arena ARENA = Arena.global();
   private static SymbolLookup lib;
   private static MethodHandle createQueue, createWrapper, setQueue, addListener, roundtripQueue, dispatchQueuePending,
         proxyGetVersion, proxyDestroy, displayFlush, wrapperDestroy;
   private static final Map<String, MethodHandle> MARSHAL = new HashMap<>();

   private static MemorySegment display, queue, displayWrapper, registry;
   private static MemorySegment manager, feedback, cmSurface, attachedDesc;
   private static int managerVersion;
   private static int features, tfMask, primariesMask;
   private static long surfaceAddr;

   // interface tables (native wl_interface structs)
   private static final Map<String, MemorySegment> IFACE = new HashMap<>();
   private static final Map<String, String[][]> EVENTS = new HashMap<>();

   // event scratch
   private static int descState; // 0 pending, 1 ready, -1 failed
   private static String descFailure;
   private static boolean infoDone;
   private static double infoMin, infoMax, infoRef, infoTMin, infoTMax;
   private static int infoTf, infoPrimaries;
   private static int cmGlobalName, cmGlobalVersion;

   private static final int TF_EXT_LINEAR = 5, TF_ST2084_PQ = 11, PRIMARIES_SRGB = 1, PRIMARIES_BT2020 = 6;
   private static final int FEATURE_PARAMETRIC = 1, FEATURE_SET_LUMINANCES = 4, FEATURE_MASTERING = 5, FEATURE_SCRGB = 7;

   /**
    * Attaches an HDR image description to the GLFW window's wl_surface. Call on the thread that swaps, after the
    * window exists. Returns false (and leaves the surface untouched) when the compositor has no color manager or no
    * HDR output; {@link #status} says why.
    */
   public static boolean attach(long wlDisplay, long wlSurface) {
      return attach(wlDisplay, wlSurface, true);
   }

   /** True when an image description is attached (else the surface is the compositor's plain SDR decode, extended). */
   public static volatile boolean described;

   /**
    * {@code describe} false: only read the output's luminances and attach nothing. KWin then treats the FP16 surface as
    * SDR: gamma 2.2, 1.0 = the reference white, values above 1.0 shown up to the headroom (probe, 2026-09-24), which is
    * what Hdr's encode pass computes itself; the pass is skipped (hdrEncode=false), its peak roll-off with it.
    */
   public static boolean attach(long wlDisplay, long wlSurface, boolean describe) {
      try {
         if (wlDisplay == 0L || wlSurface == 0L) {
            status = "no Wayland display/surface (not the Wayland platform)";
            return false;
         }
         if (!init(wlDisplay)) {
            return false;
         }
         surfaceAddr = wlSurface;
         MemorySegment surface = MemorySegment.ofAddress(wlSurface);
         queryPreferred(surface);
         if (prefTargetMax <= prefRef * 1.05 && prefMax <= prefRef * 1.05) {
            status = String.format("output is SDR (reference %.0f, max %.0f nits)", prefRef, Math.max(prefMax, prefTargetMax));
            return false;
         }
         if (!describe) {
            encRef = prefRef;
            encMax = prefTargetMax > 0 ? prefTargetMax : prefMax;
            described = false;
            active = true;
            status = String.format("HDR on without a description: the compositor's SDR decode, 1.0 = reference white %.0f, panel peak %.0f", encRef, encMax);
            Log.info("hdr: " + status);
            return true;
         }
         if ((features & 1 << FEATURE_PARAMETRIC) == 0 || (tfMask & 1 << TF_EXT_LINEAR) == 0 || (features & 1 << FEATURE_SET_LUMINANCES) == 0) {
            status = "compositor lacks parametric ext_linear descriptions";
            return false;
         }
         MemorySegment desc = createLinearDescription();
         if (desc == null) {
            return false;
         }
         cmSurface = marshal(manager, 2, IFACE.get("wp_color_management_surface_v1"), "no", MemorySegment.NULL, surface);
         marshal(cmSurface, 1, null, "ou", desc, 0); // set_image_description(desc, perceptual); applies on the next commit (the swap)
         attachedDesc = desc;
         flush();
         described = true;
         active = true;
         status = String.format("HDR on: ext_linear sRGB, 1.0 = %.0f nits, reference white %.0f, panel peak %.0f", encMax, encRef, prefTargetMax);
         Log.info("hdr: " + status);
         return true;
      } catch (Throwable t) {
         status = "failed: " + t;
         Log.warn("hdr: Wayland color management failed: " + t);
         return false;
      }
   }

   /** Delivers pending events of our queue (preferred-description changes). Cheap; call once a frame. */
   public static void pump() {
      if (queue == null) {
         return;
      }
      try {
         int n = (int)dispatchQueuePending.invokeExact(display, queue);
      } catch (Throwable t) {
         Log.warn("hdr: pump failed: " + t);
         queue = null;
      }
   }

   /** Re-reads the preferred description and re-attaches ours with its luminances (after preferred_changed). */
   public static void refresh() {
      if (!active || surfaceAddr == 0L) {
         return;
      }
      try {
         MemorySegment surface = MemorySegment.ofAddress(surfaceAddr);
         queryPreferredDescription();
         if (!described) {
            encRef = prefRef;
            encMax = prefTargetMax > 0 ? prefTargetMax : prefMax;
            Log.info(String.format("hdr: preferred description changed: reference %.0f, peak %.0f nits (no description attached)", encRef, encMax));
            return;
         }
         MemorySegment desc = createLinearDescription();
         if (desc != null) {
            marshal(cmSurface, 1, null, "ou", desc, 0);
            if (attachedDesc != null) {
               marshal(attachedDesc, 0, null, "", new Object[0]); // wp_image_description_v1.destroy
               destroyProxy(attachedDesc);
            }
            attachedDesc = desc;
            flush();
            Log.info(String.format("hdr: preferred description changed: reference %.0f, peak %.0f nits", encRef, prefTargetMax));
         }
      } catch (Throwable t) {
         Log.warn("hdr: refresh failed: " + t);
      }
   }

   // ------------------------------------------------------------------------------------------------------------

   private static boolean init(long wlDisplay) throws Throwable {
      if (manager != null) {
         return true;
      }
      lib = SymbolLookup.libraryLookup("libwayland-client.so.0", ARENA);
      createQueue = down("wl_display_create_queue", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      createWrapper = down("wl_proxy_create_wrapper", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      wrapperDestroy = down("wl_proxy_wrapper_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
      setQueue = down("wl_proxy_set_queue", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      addListener = down("wl_proxy_add_listener", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      roundtripQueue = down("wl_display_roundtrip_queue", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      dispatchQueuePending = down("wl_display_dispatch_queue_pending", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      proxyGetVersion = down("wl_proxy_get_version", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      proxyDestroy = down("wl_proxy_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
      displayFlush = down("wl_display_flush", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      buildInterfaces();

      display = MemorySegment.ofAddress(wlDisplay);
      queue = (MemorySegment)createQueue.invokeExact(display);
      displayWrapper = (MemorySegment)createWrapper.invokeExact(display);
      setQueue.invokeExact(displayWrapper, queue);
      MemorySegment registryIface = lib.find("wl_registry_interface").orElseThrow();
      registry = marshal(displayWrapper, 1, registryIface, "n", MemorySegment.NULL); // wl_display.get_registry
      wrapperDestroy.invokeExact(displayWrapper);
      listen(registry, "wl_registry");
      roundtrip();
      if (cmGlobalName == 0) {
         status = "compositor has no wp_color_manager_v1";
         return false;
      }
      managerVersion = Math.min(cmGlobalVersion, 2);
      MemorySegment cmIface = IFACE.get("wp_color_manager_v1");
      MemorySegment name = ARENA.allocateFrom("wp_color_manager_v1");
      manager = marshalBind(registry, cmGlobalName, name, managerVersion, cmIface);
      listen(manager, "wp_color_manager_v1");
      roundtrip();
      Log.info(String.format("hdr: wp_color_manager_v1 v%d features=0x%x tf=0x%x primaries=0x%x", managerVersion, features, tfMask, primariesMask));
      return true;
   }

   private static void queryPreferred(MemorySegment surface) throws Throwable {
      feedback = marshal(manager, 3, IFACE.get("wp_color_management_surface_feedback_v1"), "no", MemorySegment.NULL, surface);
      listen(feedback, "wp_color_management_surface_feedback_v1");
      queryPreferredDescription();
   }

   private static void queryPreferredDescription() throws Throwable {
      MemorySegment pref = marshal(feedback, 1, IFACE.get("wp_image_description_v1"), "n", MemorySegment.NULL); // get_preferred
      descState = 0;
      listen(pref, "wp_image_description_v1");
      for (int i = 0; i < 20 && descState == 0; i++) {
         roundtrip();
      }
      if (descState != 1) {
         destroyProxy(pref);
         throw new IllegalStateException("preferred description not ready: " + descFailure);
      }
      infoDone = false;
      infoMin = 0.2;
      infoMax = 80;
      infoRef = 80;
      infoTMin = 0;
      infoTMax = 0;
      MemorySegment info = marshal(pref, 1, IFACE.get("wp_image_description_info_v1"), "n", MemorySegment.NULL); // get_information
      listen(info, "wp_image_description_info_v1");
      for (int i = 0; i < 20 && !infoDone; i++) {
         roundtrip();
      }
      destroyProxy(info); // "done" is a destructor event: the server side is gone, free ours
      marshal(pref, 0, null, "", new Object[0]); // wp_image_description_v1.destroy
      destroyProxy(pref);
      prefMin = infoMin;
      prefMax = infoMax;
      prefRef = infoRef;
      prefTargetMin = infoTMin;
      prefTargetMax = infoTMax > 0 ? infoTMax : infoMax;
      Log.info(String.format("hdr: preferred description: tf=%d primaries=%d luminances min %.4f max %.0f ref %.0f, target %.4f..%.0f",
            infoTf, infoPrimaries, prefMin, prefMax, prefRef, prefTargetMin, prefTargetMax));
   }

   private static MemorySegment createLinearDescription() throws Throwable {
      double peak = prefTargetMax > 0 ? prefTargetMax : prefMax;
      double ref = prefRef;
      double min = Math.max(prefTargetMin, 0.0);
      MemorySegment creator = marshal(manager, 5, IFACE.get("wp_image_description_creator_params_v1"), "n", MemorySegment.NULL);
      marshal(creator, 1, null, "u", TF_EXT_LINEAR);
      marshal(creator, 3, null, "u", PRIMARIES_SRGB);
      marshal(creator, 5, null, "uuu", (int)Math.round(min * 10000), (int)Math.round(peak), (int)Math.round(ref));
      if ((features & 1 << FEATURE_MASTERING) != 0) {
         marshal(creator, 7, null, "uu", (int)Math.round(min * 10000), (int)Math.round(peak));
      }
      MemorySegment desc = marshal(creator, 0, IFACE.get("wp_image_description_v1"), "n", MemorySegment.NULL); // create (destroys the creator)
      destroyProxy(creator);
      descState = 0;
      listen(desc, "wp_image_description_v1");
      for (int i = 0; i < 20 && descState == 0; i++) {
         roundtrip();
      }
      if (descState != 1) {
         status = "image description failed: " + descFailure;
         Log.warn("hdr: " + status);
         destroyProxy(desc);
         return null;
      }
      encMax = peak;
      encRef = ref;
      return desc;
   }

   // ---- event handling ----

   private static void onEvent(String iface, int opcode, Object[] args) {
      try {
         switch (iface) {
            case "wl_registry":
               if (opcode == 0) {
                  String name = ((MemorySegment)args[1]).reinterpret(256).getString(0);
                  if ("wp_color_manager_v1".equals(name)) {
                     cmGlobalName = (Integer)args[0];
                     cmGlobalVersion = (Integer)args[2];
                  }
               }
               break;
            case "wp_color_manager_v1":
               int v = opcode < 4 ? (Integer)args[0] : 0;
               if (opcode == 1 && v < 32) {
                  features |= 1 << v;
               } else if (opcode == 2 && v < 32) {
                  tfMask |= 1 << v;
               } else if (opcode == 3 && v < 32) {
                  primariesMask |= 1 << v;
               }
               break;
            case "wp_image_description_v1":
               if (opcode == 0) {
                  descState = -1;
                  descFailure = "cause " + args[0] + ": " + ((MemorySegment)args[1]).reinterpret(1024).getString(0);
               } else {
                  descState = 1;
               }
               break;
            case "wp_image_description_info_v1":
               switch (opcode) {
                  case 0 -> infoDone = true;
                  case 3 -> infoPrimaries = (Integer)args[0];
                  case 5 -> infoTf = (Integer)args[0];
                  case 6 -> {
                     infoMin = Integer.toUnsignedLong((Integer)args[0]) / 10000.0;
                     infoMax = Integer.toUnsignedLong((Integer)args[1]);
                     infoRef = Integer.toUnsignedLong((Integer)args[2]);
                  }
                  case 8 -> {
                     infoTMin = Integer.toUnsignedLong((Integer)args[0]) / 10000.0;
                     infoTMax = Integer.toUnsignedLong((Integer)args[1]);
                  }
                  default -> {
                  }
               }
               break;
            case "wp_color_management_surface_feedback_v1":
               preferredGeneration++;
               break;
            default:
               break;
         }
      } catch (Throwable t) {
         Log.warn("hdr: event handler " + iface + "#" + opcode + ": " + t);
      }
   }

   // ---- libwayland plumbing ----

   private static MethodHandle down(String name, FunctionDescriptor fd) {
      return LINKER.downcallHandle(lib.find(name).orElseThrow(() -> new IllegalStateException("libwayland-client lacks " + name)), fd);
   }

   private static void roundtrip() throws Throwable {
      int r = (int)roundtripQueue.invokeExact(display, queue);
      if (r < 0) {
         throw new IllegalStateException("wl_display_roundtrip_queue failed (protocol error?)");
      }
   }

   private static void flush() throws Throwable {
      int r = (int)displayFlush.invokeExact(display);
   }

   private static void destroyProxy(MemorySegment proxy) throws Throwable {
      proxyDestroy.invokeExact(proxy);
   }

   /**
    * wl_proxy_marshal_flags(proxy, opcode, interface, version, flags, args...). {@code sig} is the request signature
    * (u/i = int, o/n/s = pointer); a new_id argument is passed as NULL and the new proxy is returned.
    */
   private static MemorySegment marshal(MemorySegment proxy, int opcode, MemorySegment iface, String sig, Object... args) throws Throwable {
      MethodHandle mh = MARSHAL.computeIfAbsent(sig, HdrWayland::marshalHandle);
      int version = (int)proxyGetVersion.invokeExact(proxy);
      Object[] all = new Object[5 + args.length];
      all[0] = proxy;
      all[1] = opcode;
      all[2] = iface == null ? MemorySegment.NULL : iface;
      all[3] = version;
      all[4] = 0;
      System.arraycopy(args, 0, all, 5, args.length);
      return (MemorySegment)mh.invokeWithArguments(all);
   }

   /** wl_registry.bind(name, interface name, version, new_id): the new proxy gets the requested version. */
   private static MemorySegment marshalBind(MemorySegment reg, int name, MemorySegment ifaceName, int version, MemorySegment iface) throws Throwable {
      MethodHandle mh = MARSHAL.computeIfAbsent("usun", HdrWayland::marshalHandle);
      return (MemorySegment)mh.invokeWithArguments(reg, 0, iface, version, 0, name, ifaceName, version, MemorySegment.NULL);
   }

   private static MethodHandle marshalHandle(String sig) {
      List<MemoryLayout> layouts = new ArrayList<>(List.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
      for (char c : sig.toCharArray()) {
         layouts.add(c == 'u' || c == 'i' || c == 'h' || c == 'f' ? ValueLayout.JAVA_INT : ValueLayout.ADDRESS);
      }
      FunctionDescriptor fd = FunctionDescriptor.of(ValueLayout.ADDRESS, layouts.toArray(new MemoryLayout[0]));
      return LINKER.downcallHandle(lib.find("wl_proxy_marshal_flags").orElseThrow(), fd, Linker.Option.firstVariadicArg(5));
   }

   /** Installs a listener whose every event calls {@link #onEvent} with the boxed arguments. */
   private static void listen(MemorySegment proxy, String iface) throws Throwable {
      String[][] events = iface.equals("wl_registry") ? new String[][] {{"global", "usu"}, {"global_remove", "u"}} : EVENTS.get(iface);
      MemorySegment table = ARENA.allocate(ValueLayout.ADDRESS, Math.max(1, events.length));
      for (int i = 0; i < events.length; i++) {
         table.setAtIndex(ValueLayout.ADDRESS, i, upcall(iface, i, events[i][1]));
      }
      int r = (int)addListener.invokeExact(proxy, table, MemorySegment.NULL);
   }

   private static final Map<String, MemorySegment> UPCALLS = new HashMap<>();

   private static MemorySegment upcall(String iface, int opcode, String sig) {
      return UPCALLS.computeIfAbsent(iface + "#" + opcode, k -> {
         try {
            String s = sig.replaceAll("[0-9?]", "");
            List<MemoryLayout> layouts = new ArrayList<>(List.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            List<Class<?>> carriers = new ArrayList<>(List.of(MemorySegment.class, MemorySegment.class));
            for (char c : s.toCharArray()) {
               boolean isInt = c == 'u' || c == 'i' || c == 'h' || c == 'f';
               layouts.add(isInt ? ValueLayout.JAVA_INT : ValueLayout.ADDRESS);
               carriers.add(isInt ? int.class : MemorySegment.class);
            }
            MethodHandle target = MethodHandles.lookup().findStatic(HdrWayland.class, "onRaw",
                  MethodType.methodType(void.class, String.class, int.class, Object[].class));
            target = MethodHandles.insertArguments(target, 0, iface, opcode);
            target = target.asCollector(Object[].class, carriers.size());
            target = target.asType(MethodType.methodType(void.class, carriers));
            return LINKER.upcallStub(target, FunctionDescriptor.ofVoid(layouts.toArray(new MemoryLayout[0])), ARENA);
         } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
         }
      });
   }

   private static void onRaw(String iface, int opcode, Object[] raw) {
      Object[] args = new Object[raw.length - 2];
      System.arraycopy(raw, 2, args, 0, args.length);
      onEvent(iface, opcode, args);
   }

   // ---- interface tables (from color-management-v1.xml, as wayland-scanner emits them) ----

   private static void buildInterfaces() {
      String[] names = {"wp_color_manager_v1", "wp_color_management_output_v1", "wp_color_management_surface_v1",
            "wp_color_management_surface_feedback_v1", "wp_image_description_creator_icc_v1", "wp_image_description_creator_params_v1",
            "wp_image_description_v1", "wp_image_description_info_v1", "wp_image_description_reference_v1"};
      // wl_interface: name, version, method_count, methods, event_count, events (40 bytes on LP64)
      for (String n : names) {
         IFACE.put(n, ARENA.allocate(40, 8));
      }
      MemorySegment wlOutput = lib.find("wl_output_interface").orElseThrow();
      MemorySegment wlSurface = lib.find("wl_surface_interface").orElseThrow();
      Map<String, MemorySegment> any = new HashMap<>(IFACE);
      any.put("wl_output", wlOutput);
      any.put("wl_surface", wlSurface);
      // request / event: {name, signature, argument interfaces (for o / n, in order)}
      define(any, "wp_color_manager_v1", 2, new String[][] {
            {"destroy", ""}, {"get_output", "no", "wp_color_management_output_v1", "wl_output"},
            {"get_surface", "no", "wp_color_management_surface_v1", "wl_surface"},
            {"get_surface_feedback", "no", "wp_color_management_surface_feedback_v1", "wl_surface"},
            {"create_icc_creator", "n", "wp_image_description_creator_icc_v1"},
            {"create_parametric_creator", "n", "wp_image_description_creator_params_v1"},
            {"create_windows_scrgb", "n", "wp_image_description_v1"},
            {"get_image_description", "2no", "wp_image_description_v1", "wp_image_description_reference_v1"}},
            new String[][] {{"supported_intent", "u"}, {"supported_feature", "u"}, {"supported_tf_named", "u"},
                  {"supported_primaries_named", "u"}, {"done", ""}});
      define(any, "wp_color_management_output_v1", 2, new String[][] {{"destroy", ""}, {"get_image_description", "n", "wp_image_description_v1"}},
            new String[][] {{"image_description_changed", ""}});
      define(any, "wp_color_management_surface_v1", 2, new String[][] {{"destroy", ""},
            {"set_image_description", "ou", "wp_image_description_v1"}, {"unset_image_description", ""}}, new String[0][]);
      define(any, "wp_color_management_surface_feedback_v1", 2, new String[][] {{"destroy", ""},
            {"get_preferred", "n", "wp_image_description_v1"}, {"get_preferred_parametric", "n", "wp_image_description_v1"}},
            new String[][] {{"preferred_changed", "u"}, {"preferred_changed2", "2uu"}});
      define(any, "wp_image_description_creator_icc_v1", 2, new String[][] {{"create", "n", "wp_image_description_v1"}, {"set_icc_file", "huu"}},
            new String[0][]);
      define(any, "wp_image_description_creator_params_v1", 2, new String[][] {{"create", "n", "wp_image_description_v1"},
            {"set_tf_named", "u"}, {"set_tf_power", "u"}, {"set_primaries_named", "u"}, {"set_primaries", "iiiiiiii"},
            {"set_luminances", "uuu"}, {"set_mastering_display_primaries", "iiiiiiii"}, {"set_mastering_luminance", "uu"},
            {"set_max_cll", "u"}, {"set_max_fall", "u"}}, new String[0][]);
      define(any, "wp_image_description_v1", 2, new String[][] {{"destroy", ""}, {"get_information", "n", "wp_image_description_info_v1"}},
            new String[][] {{"failed", "us"}, {"ready", "u"}, {"ready2", "2uu"}});
      define(any, "wp_image_description_info_v1", 2, new String[0][],
            new String[][] {{"done", ""}, {"icc_file", "hu"}, {"primaries", "iiiiiiii"}, {"primaries_named", "u"}, {"tf_power", "u"},
                  {"tf_named", "u"}, {"luminances", "uuu"}, {"target_primaries", "iiiiiiii"}, {"target_luminance", "uu"},
                  {"target_max_cll", "u"}, {"target_max_fall", "u"}});
      define(any, "wp_image_description_reference_v1", 1, new String[][] {{"destroy", ""}}, new String[0][]);
   }

   private static void define(Map<String, MemorySegment> any, String name, int version, String[][] requests, String[][] events) {
      MemorySegment iface = IFACE.get(name);
      iface.set(ValueLayout.ADDRESS, 0, ARENA.allocateFrom(name));
      iface.set(ValueLayout.JAVA_INT, 8, version);
      iface.set(ValueLayout.JAVA_INT, 12, requests.length);
      iface.set(ValueLayout.ADDRESS, 16, messages(any, requests));
      iface.set(ValueLayout.JAVA_INT, 24, events.length);
      iface.set(ValueLayout.ADDRESS, 32, messages(any, events));
      EVENTS.put(name, events);
   }

   private static MemorySegment messages(Map<String, MemorySegment> any, String[][] msgs) {
      if (msgs.length == 0) {
         return MemorySegment.NULL;
      }
      MemorySegment arr = ARENA.allocate(24L * msgs.length, 8);
      for (int i = 0; i < msgs.length; i++) {
         String sig = msgs[i][1];
         String args = sig.replaceAll("[0-9?]", "");
         MemorySegment types = ARENA.allocate(ValueLayout.ADDRESS, Math.max(1, args.length()));
         int objIdx = 2;
         for (int a = 0; a < args.length(); a++) {
            char c = args.charAt(a);
            MemorySegment t = MemorySegment.NULL;
            if ((c == 'o' || c == 'n') && objIdx < msgs[i].length) {
               t = any.get(msgs[i][objIdx++]);
            }
            types.setAtIndex(ValueLayout.ADDRESS, a, t);
         }
         arr.set(ValueLayout.ADDRESS, 24L * i, ARENA.allocateFrom(msgs[i][0]));
         arr.set(ValueLayout.ADDRESS, 24L * i + 8, ARENA.allocateFrom(sig));
         arr.set(ValueLayout.ADDRESS, 24L * i + 16, types);
      }
      return arr;
   }
}
