// What the Mac's display does with frames presented at a given rate: CAMetalLayer, real on-glass times.
//
//   clang -fobjc-arc -O2 -framework Cocoa -framework Metal -framework QuartzCore -o mtlprobe mtlprobe.m
//   ./mtlprobe <fps> <win|fs|bw> <plain|min|at> [seconds]      bw: borderless screen-sized window at the menu-bar level (GLFW's fullscreen)
//     plain: presentDrawable (next vsync); min: presentDrawable:afterMinimumDuration:1/fps (the VRR API);
//     at: presentDrawable:atTime: the paced target
// Prints NSScreen's refresh range, then the presentedTime interval distribution in 120 Hz periods
// (a fixed 120 Hz grid shows only 1x / 2x / ...; true variable refresh shows fps-sized intervals).
#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#include <mach/mach_time.h>

static double fps = 90, seconds = 8;
static int fullscreen = 0, mode = 0;  // fullscreen: 0 window, 1 native fullscreen, 2 borderless screen-sized; mode: 0 plain, 1 min, 2 at
static NSMutableArray<NSNumber *> *presented;

static double now_s(void) { return CACurrentMediaTime(); }

@interface Probe : NSObject <NSApplicationDelegate>
@property NSWindow *window;
@property CAMetalLayer *layer;
@property id<MTLCommandQueue> queue;
@end

@implementation Probe
- (void)applicationDidFinishLaunching:(NSNotification *)n {
  NSScreen *s = NSScreen.mainScreen;
  if (@available(macOS 12.0, *)) {
    printf("screen: maximumFramesPerSecond %ld, refresh interval min %.4f max %.4f s, granularity %.4f\n",
           (long)s.maximumFramesPerSecond, s.minimumRefreshInterval, s.maximumRefreshInterval, s.displayUpdateGranularity);
  }
  NSRect r = fullscreen == 2 ? s.frame : NSMakeRect(100, 100, 900, 600);
  self.window = [[NSWindow alloc] initWithContentRect:r
                                            styleMask:fullscreen == 2 ? NSWindowStyleMaskBorderless : NSWindowStyleMaskTitled | NSWindowStyleMaskResizable
                                              backing:NSBackingStoreBuffered
                                                defer:NO];
  if (fullscreen == 2) self.window.level = NSMainMenuWindowLevel + 1;
  self.window.collectionBehavior = NSWindowCollectionBehaviorFullScreenPrimary;
  id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
  self.layer = [CAMetalLayer layer];
  self.layer.device = dev;
  self.layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
  self.layer.displaySyncEnabled = YES;
  self.window.contentView.wantsLayer = YES;
  self.window.contentView.layer = self.layer;
  self.queue = [dev newCommandQueue];
  [self.window makeKeyAndOrderFront:nil];
  [NSApp activateIgnoringOtherApps:YES];
  if (fullscreen == 1) [self.window toggleFullScreen:nil];
  [NSThread detachNewThreadWithBlock:^{ [self loop]; }];
}

- (void)loop {
  [NSThread sleepForTimeInterval:fullscreen == 1 ? 2.5 : 1.0];  // fullscreen animation
  CGSize px = self.window.contentView.bounds.size;
  CGFloat scale = self.window.backingScaleFactor;
  self.layer.drawableSize = CGSizeMake(px.width * scale, px.height * scale);
  double period = 1.0 / fps, next = now_s(), end = next + seconds;
  int i = 0;
  while (now_s() < end) {
    double t = now_s();
    if (t < next) [NSThread sleepForTimeInterval:next - t];
    next += period;
    @autoreleasepool {
      id<CAMetalDrawable> d = [self.layer nextDrawable];
      if (!d) continue;
      MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
      rp.colorAttachments[0].texture = d.texture;
      rp.colorAttachments[0].loadAction = MTLLoadActionClear;
      double g = (i++ % 60) / 60.0;
      rp.colorAttachments[0].clearColor = MTLClearColorMake(g, 0.2, 1.0 - g, 1);
      rp.colorAttachments[0].storeAction = MTLStoreActionStore;
      id<MTLCommandBuffer> cb = [self.queue commandBuffer];
      [[cb renderCommandEncoderWithDescriptor:rp] endEncoding];
      [d addPresentedHandler:^(id<MTLDrawable> dr) {
        if (dr.presentedTime > 0) @synchronized(presented) { [presented addObject:@(dr.presentedTime)]; }
      }];
      if (mode == 1) [cb presentDrawable:d afterMinimumDuration:period];
      else if (mode == 2) [cb presentDrawable:d atTime:next];
      else [cb presentDrawable:d];
      [cb commit];
    }
  }
  [NSThread sleepForTimeInterval:0.3];
  dispatch_async(dispatch_get_main_queue(), ^{ [self report]; [NSApp terminate:nil]; });
}

- (void)report {
  NSArray *p;
  @synchronized(presented) { p = [presented copy]; }
  int n = (int)p.count - 1;
  if (n < 5) { printf("only %d presented frames\n", n + 1); return; }
  double sum = 0, sq = 0, per = 1.0 / 120.0;
  int hist[64] = {0}, ongrid = 0;
  for (int i = 0; i < n; i++) {
    double iv = [p[i + 1] doubleValue] - [p[i] doubleValue];
    sum += iv; sq += iv * iv;
    int q = (int)lround(iv / per * 4);
    if (q >= 0 && q < 64) hist[q]++;
    if (fabs(iv / per - lround(iv / per)) * per < 0.00025) ongrid++;
  }
  double mean = sum / n, sd = sqrt(fmax(0, sq / n - mean * mean));
  printf("fps %.0f %s %s: %d presented, interval mean %.3f ms (%.1f Hz) sd %.3f ms, %d%% on the 8.333 ms grid\n",
         fps, fullscreen == 1 ? "fullscreen" : fullscreen == 2 ? "borderless" : "window", mode == 1 ? "afterMinimumDuration" : mode == 2 ? "atTime" : "plain",
         n + 1, mean * 1000, 1 / mean, sd * 1000, 100 * ongrid / n);
  printf("  intervals in 120 Hz periods:");
  for (int q = 0; q < 64; q++) if (hist[q] * 100 / n >= 2) printf(" %.2fx %d%%", q / 4.0, hist[q] * 100 / n);
  printf("\n");
}
@end

int main(int argc, const char **argv) {
  if (argc > 1) fps = atof(argv[1]);
  if (argc > 2) fullscreen = !strcmp(argv[2], "fs") ? 1 : !strcmp(argv[2], "bw") ? 2 : 0;
  if (argc > 3) mode = !strcmp(argv[3], "min") ? 1 : !strcmp(argv[3], "at") ? 2 : 0;
  if (argc > 4) seconds = atof(argv[4]);
  presented = [NSMutableArray array];
  @autoreleasepool {
    NSApplication *app = [NSApplication sharedApplication];
    app.activationPolicy = NSApplicationActivationPolicyRegular;
    Probe *pr = [Probe new];
    app.delegate = pr;
    [app run];
  }
  return 0;
}
