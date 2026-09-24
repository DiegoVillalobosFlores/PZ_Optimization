// Real presentation times of the game window, from outside the game (X11 / XWayland).
//
// The GL driver presents every swap through the X Present extension (DRI3/Present on Mesa and on NVIDIA
// since the explicit-sync drivers). Any X client may select PresentCompleteNotify for any window, and the
// event carries the time the frame really reached the screen (ust, CLOCK_MONOTONIC microseconds; under
// XWayland it comes from the compositor's wp_presentation feedback, i.e. the page flip) plus how it got
// there (flip / copy / skip). With variable refresh on, flip-to-flip intervals follow the game; on a
// fixed refresh they are multiples of the refresh period. The game's own swap-return times show neither.
//
//   cc -O2 -o harness/presentprobe harness/presentprobe.c -lxcb -lxcb-present
//   harness/presentprobe [--name Zomboid] [--duration S] [--out FILE]
//
// Output: "# mono_to_epoch_us <offset>" header, then one row per completed present:
//   ust_us msc serial mode kind       mode: 0 copy, 1 flip, 2 skip, 3 suboptimal copy; kind 0 pixmap
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <xcb/xcb.h>
#include <xcb/present.h>

static xcb_connection_t *c;
static xcb_atom_t atom(const char *name) {
  xcb_intern_atom_reply_t *r = xcb_intern_atom_reply(c, xcb_intern_atom(c, 0, strlen(name), name), NULL);
  xcb_atom_t a = r ? r->atom : XCB_NONE;
  free(r);
  return a;
}

static int title_matches(xcb_window_t w, xcb_atom_t prop, xcb_atom_t type, const char *needle) {
  xcb_get_property_reply_t *r = xcb_get_property_reply(c, xcb_get_property(c, 0, w, prop, type, 0, 256), NULL);
  int ok = 0;
  if (r) {
    int len = xcb_get_property_value_length(r);
    if (len > 0) {
      char buf[1100];
      if (len > 1024) len = 1024;
      memcpy(buf, xcb_get_property_value(r), len);
      buf[len] = 0;
      for (int i = 0; i < len; i++) if (!buf[i]) buf[i] = ' ';  // WM_CLASS is two strings
      ok = strstr(buf, needle) != NULL;
    }
    free(r);
  }
  return ok;
}

static xcb_window_t find(xcb_window_t w, const char *needle, xcb_atom_t net_name, xcb_atom_t utf8, int depth) {
  if (title_matches(w, net_name, utf8, needle) || title_matches(w, XCB_ATOM_WM_NAME, XCB_ATOM_STRING, needle) ||
      title_matches(w, XCB_ATOM_WM_CLASS, XCB_ATOM_STRING, needle)) {
    xcb_get_window_attributes_reply_t *a = xcb_get_window_attributes_reply(c, xcb_get_window_attributes(c, w), NULL);
    int viewable = a && a->map_state == XCB_MAP_STATE_VIEWABLE;
    free(a);
    if (viewable) return w;
  }
  if (depth > 3) return 0;
  xcb_query_tree_reply_t *t = xcb_query_tree_reply(c, xcb_query_tree(c, w), NULL);
  xcb_window_t found = 0;
  if (t) {
    xcb_window_t *ch = xcb_query_tree_children(t);
    for (int i = 0; i < xcb_query_tree_children_length(t) && !found; i++) found = find(ch[i], needle, net_name, utf8, depth + 1);
    free(t);
  }
  return found;
}

static long long now_us(clockid_t id) {
  struct timespec ts;
  clock_gettime(id, &ts);
  return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

int main(int argc, char **argv) {
  const char *needle = "Zomboid", *outp = NULL;
  double duration = 0;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--name") && i + 1 < argc) needle = argv[++i];
    else if (!strcmp(argv[i], "--duration") && i + 1 < argc) duration = atof(argv[++i]);
    else if (!strcmp(argv[i], "--out") && i + 1 < argc) outp = argv[++i];
  }
  FILE *out = outp ? fopen(outp, "w") : stdout;
  if (!out) { perror(outp); return 1; }
  setvbuf(out, NULL, _IOLBF, 0);
  c = xcb_connect(NULL, NULL);
  if (xcb_connection_has_error(c)) { fprintf(stderr, "presentprobe: no X display\n"); return 1; }
  const xcb_query_extension_reply_t *ext = xcb_get_extension_data(c, &xcb_present_id);
  if (!ext || !ext->present) { fprintf(stderr, "presentprobe: no Present extension\n"); return 1; }
  free(xcb_present_query_version_reply(c, xcb_present_query_version(c, 1, 2), NULL));
  xcb_window_t root = xcb_setup_roots_iterator(xcb_get_setup(c)).data->root;
  xcb_atom_t net_name = atom("_NET_WM_NAME"), utf8 = atom("UTF8_STRING");
  long long start = now_us(CLOCK_MONOTONIC), end = duration > 0 ? start + (long long)(duration * 1e6) : 0;
  fprintf(out, "# mono_to_epoch_us %lld\n", now_us(CLOCK_REALTIME) - now_us(CLOCK_MONOTONIC));
  fprintf(out, "# ust_us msc serial mode kind\n");
  while (1) {  // the game may restart its window (display mode change): re-find it each time it goes away
    xcb_window_t w = 0;
    while (!(w = find(root, needle, net_name, utf8, 0))) {
      if (end && now_us(CLOCK_MONOTONIC) > end) return 0;
      usleep(200000);
    }
    fprintf(out, "# window 0x%x\n", w);
    uint32_t eid = xcb_generate_id(c);
    uint32_t structure = XCB_EVENT_MASK_STRUCTURE_NOTIFY;
    xcb_change_window_attributes(c, w, XCB_CW_EVENT_MASK, &structure);
    xcb_void_cookie_t ck = xcb_present_select_input_checked(c, eid, w, XCB_PRESENT_EVENT_MASK_COMPLETE_NOTIFY);
    xcb_generic_error_t *err = xcb_request_check(c, ck);
    if (err) { fprintf(stderr, "presentprobe: select input failed (%d)\n", err->error_code); free(err); return 1; }
    int gone = 0;
    while (!gone) {
      if (end && now_us(CLOCK_MONOTONIC) > end) return 0;
      xcb_generic_event_t *ev = xcb_poll_for_event(c);
      if (!ev) {
        if (xcb_connection_has_error(c)) return 1;
        usleep(500);
        continue;
      }
      uint8_t type = ev->response_type & 0x7f;
      if (type == XCB_GE_GENERIC) {
        xcb_ge_generic_event_t *ge = (xcb_ge_generic_event_t *)ev;
        if (ge->extension == ext->major_opcode && ge->event_type == XCB_PRESENT_EVENT_COMPLETE_NOTIFY) {
          xcb_present_complete_notify_event_t *e = (xcb_present_complete_notify_event_t *)ev;
          fprintf(out, "%llu %llu %u %u %u\n", (unsigned long long)e->ust, (unsigned long long)e->msc, e->serial, e->mode, e->kind);
        }
      } else if (type == XCB_DESTROY_NOTIFY || type == XCB_UNMAP_NOTIFY) {
        gone = 1;
      }
      free(ev);
    }
    fprintf(out, "# window gone\n");
  }
}
