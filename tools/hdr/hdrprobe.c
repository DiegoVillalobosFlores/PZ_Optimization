// HDR output probe for KWin / wp_color_manager_v1 with an NVIDIA FP16 EGL window surface.
//
//   cc -O2 -o /tmp/hdrprobe tools/hdr/hdrprobe.c tools/hdr/gen/*.c -Itools/hdr/gen \
//      $(pkg-config --cflags --libs wayland-client wayland-egl egl glesv2) -lm
//   /tmp/hdrprobe <mode> [seconds]
//
// Modes: info (print the compositor's features and the surface's preferred image description, draw
// nothing HDR), scrgb (create_windows_scrgb, values = nits / 80), pq203 (PQ / BT.2020, luminances
// left at the PQ defaults: reference white 203), pqabs (PQ / BT.2020 with the preferred description's
// luminances, so the compositor has nothing to map), linear (ext_linear / sRGB with the preferred
// luminances, values = nits / reference). The picture: 10 patches at fixed nits on top, a log ramp
// 0.01 .. 2000 nits below, a sRGB white 1.0 strip at the bottom (what an SDR UI would write).
#define _GNU_SOURCE
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wayland-client.h>
#include <wayland-egl.h>
#include "color-management-v1-client-protocol.h"
#include "xdg-shell-client-protocol.h"

static struct wl_compositor *compositor;
static struct xdg_wm_base *wm;
static struct wp_color_manager_v1 *cm;
static int cm_version;
static int w = 1600, h = 900, configured, closed;
static unsigned features;
static int desc_ready, desc_failed;
static double pref_min = 0.2, pref_max = 80, pref_ref = 80, pref_tmax = 0, pref_tmin = 0;
static int pref_tf = -1, pref_prim = -1, info_done;

static void reg_global(void *d, struct wl_registry *r, uint32_t name, const char *iface, uint32_t ver) {
   if (!strcmp(iface, wl_compositor_interface.name)) compositor = wl_registry_bind(r, name, &wl_compositor_interface, 4);
   else if (!strcmp(iface, xdg_wm_base_interface.name)) wm = wl_registry_bind(r, name, &xdg_wm_base_interface, 1);
   else if (!strcmp(iface, wp_color_manager_v1_interface.name)) {
      cm_version = ver < 2 ? ver : 2;
      cm = wl_registry_bind(r, name, &wp_color_manager_v1_interface, cm_version);
   }
}
static void reg_remove(void *d, struct wl_registry *r, uint32_t n) {}
static const struct wl_registry_listener reg_l = {reg_global, reg_remove};

static void cm_intent(void *d, struct wp_color_manager_v1 *m, uint32_t v) { printf("  intent %u\n", v); }
static void cm_feature(void *d, struct wp_color_manager_v1 *m, uint32_t v) { features |= 1u << v; printf("  feature %u\n", v); }
static void cm_tf(void *d, struct wp_color_manager_v1 *m, uint32_t v) { printf("  tf %u\n", v); }
static void cm_prim(void *d, struct wp_color_manager_v1 *m, uint32_t v) { printf("  primaries %u\n", v); }
static void cm_done(void *d, struct wp_color_manager_v1 *m) { printf("  done\n"); }
static const struct wp_color_manager_v1_listener cm_l = {cm_intent, cm_feature, cm_tf, cm_prim, cm_done};

static void wm_ping(void *d, struct xdg_wm_base *b, uint32_t s) { xdg_wm_base_pong(b, s); }
static const struct xdg_wm_base_listener wm_l = {wm_ping};
static void xs_conf(void *d, struct xdg_surface *s, uint32_t serial) { xdg_surface_ack_configure(s, serial); configured = 1; }
static const struct xdg_surface_listener xs_l = {xs_conf};
static void tl_conf(void *d, struct xdg_toplevel *t, int32_t cw, int32_t ch, struct wl_array *st) {
   if (cw > 0 && ch > 0) { w = cw; h = ch; }
}
static void tl_close(void *d, struct xdg_toplevel *t) { closed = 1; }
static void tl_bounds(void *d, struct xdg_toplevel *t, int32_t a, int32_t b) {}
static void tl_caps(void *d, struct xdg_toplevel *t, struct wl_array *a) {}
static const struct xdg_toplevel_listener tl_l = {tl_conf, tl_close, tl_bounds, tl_caps};

static void id_failed(void *d, struct wp_image_description_v1 *i, uint32_t cause, const char *msg) { desc_failed = 1; printf("image description failed: %u %s\n", cause, msg); }
static void id_ready(void *d, struct wp_image_description_v1 *i, uint32_t id) { desc_ready = 1; }
static void id_ready2(void *d, struct wp_image_description_v1 *i, uint32_t hi, uint32_t lo) { desc_ready = 1; }
static const struct wp_image_description_v1_listener id_l = {id_failed, id_ready, id_ready2};

static void in_done(void *d, struct wp_image_description_info_v1 *i) { info_done = 1; }
static void in_icc(void *d, struct wp_image_description_info_v1 *i, int32_t fd, uint32_t sz) {}
static void in_prim(void *d, struct wp_image_description_info_v1 *i, int32_t a, int32_t b, int32_t c, int32_t e, int32_t f, int32_t g, int32_t x, int32_t y) {
   printf("  primaries r %.4f,%.4f g %.4f,%.4f b %.4f,%.4f w %.4f,%.4f\n", a / 1e6, b / 1e6, c / 1e6, e / 1e6, f / 1e6, g / 1e6, x / 1e6, y / 1e6);
}
static void in_primn(void *d, struct wp_image_description_info_v1 *i, uint32_t p) { pref_prim = p; printf("  primaries_named %u\n", p); }
static void in_tfp(void *d, struct wp_image_description_info_v1 *i, uint32_t e) { printf("  tf_power %u\n", e); }
static void in_tfn(void *d, struct wp_image_description_info_v1 *i, uint32_t t) { pref_tf = t; printf("  tf_named %u\n", t); }
static void in_lum(void *d, struct wp_image_description_info_v1 *i, uint32_t mn, uint32_t mx, uint32_t ref) {
   pref_min = mn / 1e4; pref_max = mx; pref_ref = ref;
   printf("  luminances min %.4f max %u ref %u\n", mn / 1e4, mx, ref);
}
static void in_tprim(void *d, struct wp_image_description_info_v1 *i, int32_t a, int32_t b, int32_t c, int32_t e, int32_t f, int32_t g, int32_t x, int32_t y) {
   printf("  target primaries r %.4f,%.4f g %.4f,%.4f b %.4f,%.4f\n", a / 1e6, b / 1e6, c / 1e6, e / 1e6, f / 1e6, g / 1e6);
}
static void in_tlum(void *d, struct wp_image_description_info_v1 *i, uint32_t mn, uint32_t mx) { pref_tmin = mn / 1e4; pref_tmax = mx; printf("  target luminance min %.4f max %u\n", mn / 1e4, mx); }
static void in_cll(void *d, struct wp_image_description_info_v1 *i, uint32_t v) { printf("  target max_cll %u\n", v); }
static void in_fall(void *d, struct wp_image_description_info_v1 *i, uint32_t v) { printf("  target max_fall %u\n", v); }
static const struct wp_image_description_info_v1_listener in_l = {in_done, in_icc, in_prim, in_primn, in_tfp, in_tfn, in_lum, in_tprim, in_tlum, in_cll, in_fall};

static const char *VS = "#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.0-1.0,0,1);}";
static const char *FS =
   "#version 300 es\nprecision highp float;\nuniform vec2 size;uniform int mode;uniform float ref;out vec4 o;\n"
   "vec3 pq(vec3 nits){vec3 y=clamp(nits/10000.0,0.0,1.0);vec3 p=pow(y,vec3(0.1593017578125));"
   "return pow((0.8359375+18.8515625*p)/(1.0+18.6875*p),vec3(78.84375));}\n"
   "vec3 enc(float nits){vec3 n=vec3(nits);if(mode==1)return n/80.0;if(mode==2||mode==3)return pq(n);return n/ref;}\n"
   "float lvl[10]=float[10](0.0,0.05,1.0,10.0,80.0,203.0,505.0,1000.0,1300.0,2000.0);\n"
   "void main(){vec2 uv=gl_FragCoord.xy/size;uv.y=1.0-uv.y;float nits;\n"
   " if(uv.y<0.45){nits=lvl[int(uv.x*10.0)];}\n"
   " else if(uv.y<0.85){nits=pow(10.0,mix(-2.0,log(2000.0)/log(10.0),uv.x));}\n"
   " else {o=vec4(enc(ref),1);return;}\n"
   " o=vec4(enc(nits),1);}\n";

static GLuint sh(GLenum t, const char *s) {
   GLuint o = glCreateShader(t); glShaderSource(o, 1, &s, NULL); glCompileShader(o);
   GLint ok; glGetShaderiv(o, GL_COMPILE_STATUS, &ok);
   if (!ok) { char b[2048]; glGetShaderInfoLog(o, sizeof b, NULL, b); fprintf(stderr, "shader: %s\n", b); exit(1); }
   return o;
}

int main(int argc, char **argv) {
   const char *mode = argc > 1 ? argv[1] : "info";
   int seconds = argc > 2 ? atoi(argv[2]) : 6;
   struct wl_display *dpy = wl_display_connect(NULL);
   if (!dpy) { fprintf(stderr, "no wayland display\n"); return 1; }
   struct wl_registry *reg = wl_display_get_registry(dpy);
   wl_registry_add_listener(reg, &reg_l, NULL);
   wl_display_roundtrip(dpy);
   if (!cm) { fprintf(stderr, "no wp_color_manager_v1\n"); return 1; }
   printf("wp_color_manager_v1 v%d\n", cm_version);
   wp_color_manager_v1_add_listener(cm, &cm_l, NULL);
   xdg_wm_base_add_listener(wm, &wm_l, NULL);
   wl_display_roundtrip(dpy);

   struct wl_surface *surf = wl_compositor_create_surface(compositor);
   struct xdg_surface *xs = xdg_wm_base_get_xdg_surface(wm, surf);
   xdg_surface_add_listener(xs, &xs_l, NULL);
   struct xdg_toplevel *tl = xdg_surface_get_toplevel(xs);
   xdg_toplevel_add_listener(tl, &tl_l, NULL);
   xdg_toplevel_set_title(tl, "pzopt hdrprobe");
   xdg_toplevel_set_fullscreen(tl, NULL);
   wl_surface_commit(surf);
   while (!configured) wl_display_dispatch(dpy);

   // Preferred image description of the surface (what the compositor would like us to target).
   struct wp_color_management_surface_feedback_v1 *fb = wp_color_manager_v1_get_surface_feedback(cm, surf);
   struct wp_image_description_v1 *pref = wp_color_management_surface_feedback_v1_get_preferred(fb);
   wp_image_description_v1_add_listener(pref, &id_l, NULL);
   wl_display_roundtrip(dpy);
   printf("preferred image description:\n");
   struct wp_image_description_info_v1 *info = wp_image_description_v1_get_information(pref);
   wp_image_description_info_v1_add_listener(info, &in_l, NULL);
   while (!info_done) wl_display_dispatch(dpy);

   EGLDisplay ed = eglGetPlatformDisplay(EGL_PLATFORM_WAYLAND_KHR, dpy, NULL);
   eglInitialize(ed, NULL, NULL);
   eglBindAPI(EGL_OPENGL_ES_API);
   EGLint ca[] = {EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                  EGL_COLOR_COMPONENT_TYPE_EXT, EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT,
                  EGL_RED_SIZE, 16, EGL_GREEN_SIZE, 16, EGL_BLUE_SIZE, 16, EGL_ALPHA_SIZE, 16, EGL_NONE};
   EGLConfig cfg; EGLint n = 0;
   if (!eglChooseConfig(ed, ca, &cfg, 1, &n) || n < 1) { fprintf(stderr, "no FP16 EGL config\n"); return 1; }
   EGLint cx[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
   EGLContext ctx = eglCreateContext(ed, cfg, EGL_NO_CONTEXT, cx);
   struct wl_egl_window *ew = wl_egl_window_create(surf, w, h);
   EGLSurface es = eglCreateWindowSurface(ed, cfg, (EGLNativeWindowType)ew, NULL);
   if (es == EGL_NO_SURFACE) { fprintf(stderr, "eglCreateWindowSurface failed 0x%x\n", eglGetError()); return 1; }
   eglMakeCurrent(ed, es, es, ctx);
   eglSwapInterval(ed, 1);
   printf("surface %dx%d GL %s\n", w, h, glGetString(GL_RENDERER));

   int m = 0; // 0 = sRGB-ish reference-relative (info / linear), 1 scrgb, 2 pq203, 3 pqabs
   double ref = pref_ref;
   struct wp_color_management_surface_v1 *cs = NULL;
   struct wp_image_description_v1 *desc = NULL;
   if (strcmp(mode, "info")) {
      cs = wp_color_manager_v1_get_surface(cm, surf);
      if (!strcmp(mode, "scrgb")) {
         m = 1;
         desc = wp_color_manager_v1_create_windows_scrgb(cm);
      } else {
         struct wp_image_description_creator_params_v1 *c = wp_color_manager_v1_create_parametric_creator(cm);
         if (!strcmp(mode, "linear")) {
            wp_image_description_creator_params_v1_set_tf_named(c, WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_EXT_LINEAR);
            wp_image_description_creator_params_v1_set_primaries_named(c, WP_COLOR_MANAGER_V1_PRIMARIES_SRGB);
            wp_image_description_creator_params_v1_set_luminances(c, (uint32_t)(pref_min * 1e4), (uint32_t)(pref_tmax > 0 ? pref_tmax : pref_max), (uint32_t)pref_ref);
            wp_image_description_creator_params_v1_set_mastering_luminance(c, (uint32_t)(pref_tmin * 1e4), (uint32_t)(pref_tmax > 0 ? pref_tmax : pref_max));
         } else {
            m = !strcmp(mode, "pqabs") ? 3 : 2;
            wp_image_description_creator_params_v1_set_tf_named(c, WP_COLOR_MANAGER_V1_TRANSFER_FUNCTION_ST2084_PQ);
            wp_image_description_creator_params_v1_set_primaries_named(c, WP_COLOR_MANAGER_V1_PRIMARIES_BT2020);
            if (m == 3) {
               wp_image_description_creator_params_v1_set_luminances(c, (uint32_t)(pref_min * 1e4), 10000, (uint32_t)pref_ref);
               wp_image_description_creator_params_v1_set_mastering_luminance(c, (uint32_t)(pref_tmin * 1e4), (uint32_t)(pref_tmax > 0 ? pref_tmax : pref_max));
            } else {
               ref = 203;
            }
         }
         desc = wp_image_description_creator_params_v1_create(c);
      }
      wp_image_description_v1_add_listener(desc, &id_l, NULL);
      desc_ready = 0;
      while (!desc_ready && !desc_failed) wl_display_dispatch(dpy);
      if (desc_ready) {
         wp_color_management_surface_v1_set_image_description(cs, desc, WP_COLOR_MANAGER_V1_RENDER_INTENT_PERCEPTUAL);
         printf("image description set (%s), reference %.0f nits\n", mode, ref);
      }
   }

   GLuint prog = glCreateProgram();
   glAttachShader(prog, sh(GL_VERTEX_SHADER, VS));
   glAttachShader(prog, sh(GL_FRAGMENT_SHADER, FS));
   glLinkProgram(prog);
   glUseProgram(prog);
   GLuint vao; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
   glUniform1i(glGetUniformLocation(prog, "mode"), m);
   glUniform1f(glGetUniformLocation(prog, "ref"), (float)ref);
   struct timespec t0, t; clock_gettime(CLOCK_MONOTONIC, &t0);
   int frames = 0;
   for (;;) {
      clock_gettime(CLOCK_MONOTONIC, &t);
      if (closed || t.tv_sec - t0.tv_sec >= seconds) break;
      wl_display_dispatch_pending(dpy);
      wl_egl_window_resize(ew, w, h, 0, 0);
      glViewport(0, 0, w, h);
      glUniform2f(glGetUniformLocation(prog, "size"), (float)w, (float)h);
      glDrawArrays(GL_TRIANGLES, 0, 3);
      eglSwapBuffers(ed, es);
      frames++;
   }
   printf("frames %d\n", frames);
   return 0;
}
