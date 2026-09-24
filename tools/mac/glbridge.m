// Prototype of the macOS present bridge: a legacy (2.1) GL context renders like the game does, every frame is
// blitted into an IOSurface-backed rectangle texture, and a CAMetalLayer over the view presents that IOSurface with
// Metal's timed present (the only API that gets ProMotion / Adaptive-Sync timing, see mtlprobe.m). Measures the
// on-glass intervals (MTLDrawable.presentedTime, read back from retained drawables, no blocks needed) and the cost.
//
//   clang -fobjc-arc -O2 -framework Cocoa -framework Metal -framework QuartzCore -framework OpenGL -framework IOSurface \
//         -Wno-deprecated-declarations -o glbridge glbridge.m
//   ./glbridge <fps> <win|fs|bw> <gl|plain|min|at> [seconds] [jitter_ms]
//     gl: plain NSOpenGLContext flushBuffer (no bridge; on-glass unknown), plain/min/at: the bridge's Metal present
//     jitter_ms: random extra CPU "work" per frame (0..jitter) to mimic uneven game frames
#import <Cocoa/Cocoa.h>
#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import <IOSurface/IOSurface.h>
#import <OpenGL/gl.h>
#import <OpenGL/glext.h>
#import <OpenGL/CGLIOSurface.h>
#import <MetalPerformanceShaders/MetalPerformanceShaders.h>

static double fps = 90, seconds = 8, jitterMs = 0;
static int screenMode = 0, mode = 1, asViewLayer = 0, drawables = 2, jit = 0, gpuQuads = 0, lowRes = 0, mpsScale = 0;  // screen: 0 window 1 native fullscreen 2 borderless; mode: 0 gl, 1 plain, 2 min, 3 at

@interface Bridge : NSObject <NSApplicationDelegate>
@property NSWindow *window;
@property NSOpenGLContext *gl;
@property CAMetalLayer *layer;
@property id<MTLDevice> dev;
@property id<MTLCommandQueue> queue;
@end

@implementation Bridge {
  IOSurfaceRef surf;
  GLuint tex, fbo;
  id<MTLTexture> mtex;
  int w, h, dw, dh;
  MPSImageBilinearScale *scaler;
  NSMutableArray *pending;  // drawables waiting for presentedTime
  NSMutableArray<NSNumber *> *presented;
  NSMutableArray<NSNumber *> *lat;
  NSMutableArray<NSNumber *> *commitTimes;
  double blitCpuMs, presentCpuMs;
  int frames;
}

- (void)applicationDidFinishLaunching:(NSNotification *)n {
  NSScreen *s = NSScreen.mainScreen;
  NSRect r = screenMode == 2 ? s.frame : NSMakeRect(100, 100, 1200, 800);
  self.window = [[NSWindow alloc] initWithContentRect:r
                                            styleMask:screenMode == 2 ? NSWindowStyleMaskBorderless : NSWindowStyleMaskTitled | NSWindowStyleMaskResizable
                                              backing:NSBackingStoreBuffered defer:NO];
  if (screenMode == 2) self.window.level = NSMainMenuWindowLevel + 1;
  self.window.collectionBehavior = NSWindowCollectionBehaviorFullScreenPrimary;
  NSView *view = self.window.contentView;
  view.wantsBestResolutionOpenGLSurface = lowRes ? NO : YES;  // the game's GLFW window asks for no Retina framebuffer
  NSOpenGLPixelFormatAttribute attrs[] = {NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersionLegacy, NSOpenGLPFADoubleBuffer,
                                          NSOpenGLPFAColorSize, 24, NSOpenGLPFAAlphaSize, 8, NSOpenGLPFADepthSize, 24, 0};
  NSOpenGLPixelFormat *pf = [[NSOpenGLPixelFormat alloc] initWithAttributes:attrs];
  self.gl = [[NSOpenGLContext alloc] initWithFormat:pf shareContext:nil];
  [self.window makeKeyAndOrderFront:nil];
  [NSApp activateIgnoringOtherApps:YES];
  if (screenMode == 1) [self.window toggleFullScreen:nil];
  pending = [NSMutableArray array];
  presented = [NSMutableArray array];
  lat = [NSMutableArray array];
  commitTimes = [NSMutableArray array];
  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)((screenMode == 1 ? 2.5 : 0.8) * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
    [self setup];
    [NSThread detachNewThreadWithBlock:^{ [self loop]; }];
  });
}

- (void)setup {
  NSView *view = self.window.contentView;
  [self.gl setView:view];
  GLint one = mode == 0 ? 1 : 0;
  [self.gl setValues:&one forParameter:NSOpenGLContextParameterSwapInterval];
  NSRect px = lowRes ? view.bounds : [view convertRectToBacking:view.bounds];
  w = (int)px.size.width;
  h = (int)px.size.height;
  if (mode == 0) return;
  self.dev = MTLCreateSystemDefaultDevice();
  self.queue = [self.dev newCommandQueue];
  surf = IOSurfaceCreate((__bridge CFDictionaryRef) @{
    (id)kIOSurfaceWidth : @(w), (id)kIOSurfaceHeight : @(h), (id)kIOSurfaceBytesPerElement : @4,
    (id)kIOSurfacePixelFormat : @((unsigned)'BGRA')
  });
  MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:w height:h mipmapped:NO];
  d.usage = MTLTextureUsageShaderRead;
  d.storageMode = MTLStorageModeShared;
  mtex = [self.dev newTextureWithDescriptor:d iosurface:surf plane:0];
  self.layer = [CAMetalLayer layer];
  self.layer.device = self.dev;
  self.layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
  self.layer.framebufferOnly = NO;
  CGFloat bs = self.window.backingScaleFactor;
  dw = mpsScale ? (int)(view.bounds.size.width * bs) : w;
  dh = mpsScale ? (int)(view.bounds.size.height * bs) : h;
  self.layer.drawableSize = CGSizeMake(dw, dh);
  self.layer.contentsScale = lowRes && !mpsScale ? 1.0 : self.window.backingScaleFactor;
  if (mpsScale) scaler = [[MPSImageBilinearScale alloc] initWithDevice:self.dev];
  self.layer.frame = view.bounds;
  self.layer.opaque = YES;
  self.layer.maximumDrawableCount = drawables;
  if (asViewLayer) {  // the Metal layer IS the content view's layer (direct-to-display candidate)
    view.layer = self.layer;
    view.wantsLayer = YES;
  } else {
    view.wantsLayer = YES;
    [view.layer addSublayer:self.layer];
  }
}

- (void)glSetup {
  [self.gl makeCurrentContext];
  if (mode == 0) return;
  CGLContextObj cgl = self.gl.CGLContextObj;
  glGenTextures(1, &tex);
  glBindTexture(GL_TEXTURE_RECTANGLE_ARB, tex);
  CGLError e = CGLTexImageIOSurface2D(cgl, GL_TEXTURE_RECTANGLE_ARB, GL_RGBA, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, surf, 0);
  if (e) printf("CGLTexImageIOSurface2D error %d\n", e);
  glGenFramebuffersEXT(1, &fbo);
  glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);
  glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_RECTANGLE_ARB, tex, 0);
  GLenum st = glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
  if (st != GL_FRAMEBUFFER_COMPLETE_EXT) printf("fbo incomplete 0x%x\n", st);
  glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
  printf("GL %s, %dx%d -> drawable %dx%d%s, bridge ready\n", glGetString(GL_VERSION), w, h, dw, dh, scaler ? " (MPS bilinear)" : "");
}

static double now_s(void) { return CACurrentMediaTime(); }

- (void)drawScene:(int)i {
  glViewport(0, 0, w, h);
  double g = (i % 120) / 120.0;
  glClearColor(g, 0.3, 1.0 - g, 1);
  glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
  glMatrixMode(GL_PROJECTION);
  glLoadIdentity();
  glOrtho(0, 1, 0, 1, -1, 1);
  if (gpuQuads) {  // GPU load: blended full-screen quads (the game's frames take ms of GPU time)
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glBegin(GL_QUADS);
    for (int q = 0; q < gpuQuads; q++) {
      glColor4f(q % 2, 0.5, 0.2, 0.02);
      glVertex2f(0, 0); glVertex2f(1, 0); glVertex2f(1, 1); glVertex2f(0, 1);
    }
    glEnd();
    glDisable(GL_BLEND);
  }
  glBegin(GL_QUADS);  // a bar sweeping across: judder shows as uneven steps
  double x = fmod(i / 120.0, 1.0);
  glColor3f(1, 1, 1);
  glVertex2f(x, 0); glVertex2f(x + 0.02, 0); glVertex2f(x + 0.02, 1); glVertex2f(x, 1);
  glEnd();
}

- (void)loop {
  [self glSetup];
  double period = 1.0 / fps, next = now_s(), end = next + seconds;
  int i = 0;
  while (now_s() < end) {
    double t = now_s();
    if (t < next) [NSThread sleepForTimeInterval:next - t];
    double target = next + 0.004;  // game-like: the frame is due a bit after its step start
    next += period;
    [self drawScene:i++];
    if (jitterMs > 0) {
      double until = now_s() + (arc4random_uniform(1000) / 1000.0) * jitterMs / 1000.0;
      while (now_s() < until) {}
    }
    if (mode == 0) {
      [self.gl flushBuffer];
      continue;
    }
    double c0 = now_s();
    glBindFramebufferEXT(GL_READ_FRAMEBUFFER_EXT, 0);
    glBindFramebufferEXT(GL_DRAW_FRAMEBUFFER_EXT, fbo);
    glBlitFramebufferEXT(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
    glFinish();  // IOSurface hand-off: GL must be done before Metal reads it
    double c1 = now_s();
    @autoreleasepool {
      id<CAMetalDrawable> dr = [self.layer nextDrawable];
      if (!dr) continue;
      id<MTLCommandBuffer> cb = [self.queue commandBuffer];
      if (scaler) {
        [scaler encodeToCommandBuffer:cb sourceTexture:mtex destinationTexture:dr.texture];
      } else {
        id<MTLBlitCommandEncoder> bl = [cb blitCommandEncoder];
        [bl copyFromTexture:mtex sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
                  toTexture:dr.texture destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
        [bl endEncoding];
      }
      if (jit && pending.count > 0) {  // commit only once the previous frame is on glass: nothing queues behind it
        id<MTLDrawable> prev = pending.lastObject;
        double until = now_s() + 0.05;
        while (prev.presentedTime <= 0 && now_s() < until) usleep(200);
      }
      if (mode == 2) [cb presentDrawable:dr afterMinimumDuration:period - 0.0005];
      else if (mode == 3) [cb presentDrawable:dr atTime:target];
      else if (mode == 4) {  // atTime on the measured glass timeline: last known presentedTime + n periods, never in the past
        static double lastGlass = 0; static long lastGlassFrame = 0; static long fno = 0;
        for (id<MTLDrawable> pd in pending) { (void)pd; }
        long mine = fno++;
        id<MTLDrawable> known = nil; long kf = 0;
        for (long q = (long)pending.count - 1; q >= 0; q--) {
          id<MTLDrawable> pd = pending[q];
          if (pd.presentedTime > 0) { known = pd; kf = mine - ((long)pending.count - q); break; }
        }
        if (known) { lastGlass = known.presentedTime; lastGlassFrame = kf; }
        double t2 = lastGlass > 0 ? lastGlass + (mine - lastGlassFrame) * period : now_s() + period;
        while (t2 < now_s() + 0.004) t2 += period;
        [cb presentDrawable:dr atTime:t2 - 0.0005];
      }
      else [cb presentDrawable:dr];
      [cb commit];
      [pending addObject:dr];
      [commitTimes addObject:@(c0)];
      while (pending.count > 6) {
        id<MTLDrawable> old = pending[0];
        if (old.presentedTime > 0) {
          [presented addObject:@(old.presentedTime)];
          [lat addObject:@(old.presentedTime - commitTimes[0].doubleValue)];
        }
        [pending removeObjectAtIndex:0];
        [commitTimes removeObjectAtIndex:0];
      }
    }
    if (i == 30) {  // verify: the IOSurface holds what GL drew (clear colour of frame 29 at a pixel left of the bar)
      uint8_t px[4];
      [mtex getBytes:px bytesPerRow:w * 4 fromRegion:MTLRegionMake2D(2, h / 2, 1, 1) mipmapLevel:0];
      double g = (29 % 120) / 120.0;
      printf("pixel check: BGRA %d %d %d, expected about %d %d %d\n", px[0], px[1], px[2], (int)((1 - g) * 255), (int)(0.3 * 255), (int)(g * 255));
    }
    double c2 = now_s();
    blitCpuMs += (c1 - c0) * 1000;
    presentCpuMs += (c2 - c1) * 1000;
    frames++;
  }
  [NSThread sleepForTimeInterval:0.3];
  for (id<MTLDrawable> old in pending) if (old.presentedTime > 0) [presented addObject:@(old.presentedTime)];
  dispatch_async(dispatch_get_main_queue(), ^{ [self report]; [NSApp terminate:nil]; });
}

- (void)report {
  const char *names[] = {"gl flushBuffer", "bridge plain", "bridge afterMinimumDuration", "bridge atTime", "bridge atTime on the glass timeline"};
  const char *screens[] = {"window", "fullscreen", "borderless"};
  if (mode == 0) { printf("fps %.0f %s %s: no on-glass feedback\n", fps, screens[screenMode], names[0]); return; }
  int n = (int)presented.count - 1;
  if (n < 5) { printf("only %d presented\n", n + 1); return; }
  double sum = 0, sq = 0, per = 1.0 / 120.0;
  int hist[64] = {0};
  for (int i = 0; i < n; i++) {
    double iv = presented[i + 1].doubleValue - presented[i].doubleValue;
    sum += iv; sq += iv * iv;
    int q = (int)lround(iv / per * 4);
    if (q >= 0 && q < 64) hist[q]++;
  }
  double mean = sum / n, sd = sqrt(fmax(0, sq / n - mean * mean));
  printf("[gpu quads %d] fps %.0f %s %s jitter %.0f ms: %d presented, mean %.3f ms (%.1f Hz) sd %.3f; cpu blit+finish %.2f ms, present %.2f ms per frame\n",
         gpuQuads, fps, screens[screenMode], names[mode], jitterMs, n + 1, mean * 1000, 1 / mean, sd * 1000, blitCpuMs / frames, presentCpuMs / frames);
  NSArray *ls = [lat sortedArrayUsingSelector:@selector(compare:)];
  if (ls.count > 10) printf("  frame-ready -> glass: p10 %.1f p50 %.1f p90 %.1f ms (%s, %d drawables%s)\n", [ls[ls.count / 10] doubleValue] * 1000,
                            [ls[ls.count / 2] doubleValue] * 1000, [ls[ls.count * 9 / 10] doubleValue] * 1000, asViewLayer ? "view layer" : "sublayer", drawables, jit ? ", commit after previous on glass" : "");
  printf("  intervals in 120 Hz periods:");
  for (int q = 0; q < 64; q++) if (hist[q] * 100 / n >= 2) printf(" %.2fx %d%%", q / 4.0, hist[q] * 100 / n);
  printf("\n");
}
@end

int main(int argc, const char **argv) {
  if (argc > 1) fps = atof(argv[1]);
  if (argc > 2) screenMode = !strcmp(argv[2], "fs") ? 1 : !strcmp(argv[2], "bw") ? 2 : 0;
  if (argc > 3) mode = !strcmp(argv[3], "gl") ? 0 : !strcmp(argv[3], "min") ? 2 : !strcmp(argv[3], "at") ? 3 : !strcmp(argv[3], "at2") ? 4 : 1;
  if (argc > 4) seconds = atof(argv[4]);
  if (argc > 5) jitterMs = atof(argv[5]);
  if (argc > 6) asViewLayer = atoi(argv[6]);
  if (argc > 7) drawables = atoi(argv[7]);
  if (argc > 8) jit = atoi(argv[8]);
  if (argc > 9) gpuQuads = atoi(argv[9]);
  if (argc > 10) lowRes = atoi(argv[10]);
  if (argc > 11) mpsScale = atoi(argv[11]);
  @autoreleasepool {
    NSApplication *app = [NSApplication sharedApplication];
    app.activationPolicy = NSApplicationActivationPolicyRegular;
    Bridge *b = [Bridge new];
    app.delegate = b;
    [app run];
  }
  return 0;
}
