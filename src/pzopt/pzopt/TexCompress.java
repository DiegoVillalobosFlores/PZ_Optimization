package pzopt;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.system.MemoryUtil;
import zombie.core.textures.ImageData;
import zombie.core.textures.MipMapLevel;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.FileTask;
import zombie.fileSystem.IFileTaskCallback;

/**
 * textureCompression without the driver's compressor (texCompress). Stock asks for GL_COMPRESSED_RGBA and the driver
 * compresses every level inside glTexImage2D on the render thread; Mesa does it on the CPU (41 ns a pixel on the
 * flip's 890M: 603 ms for 12 UI pages, the main menu at 15-30 fps for up to 30 s after boot).
 *
 * auto (default): gpu where TexBcGpu runs (GL 4.3 compute), worker elsewhere (macOS GL 4.1, old drivers).
 * worker: the file-pool task that decoded the image also builds the levels the render thread would upload
 * (mips, premultiplied alpha: ImageData.getMipMapData does both, lazily, on the render thread in stock) and encodes
 * them to BC3 (TexBc, HQ fit); the render thread creates the texture from the finished blocks with
 * glCompressedTexImage2D, a quarter of the bytes of an uncompressed upload. Textures made outside the asset pipeline
 * (direct TextureID constructors) have no worker: gpu encodes them on the GPU (TexBcGpu, GL 4.3), else the driver.
 * gpu: the worker only builds the levels (mips, premultiplied alpha), the render thread copies them into a storage
 * buffer and the GPU encodes (TexBcGpu): CPU cost a copy. driver: stock.
 */
public final class TexCompress {
   static final String MODE = Config.TEX_COMPRESS;
   /** File-pool workers take part (prepare levels, and encode them unless the GPU will). */
   static final boolean WORKER = Overrides.enabled() && ("worker".equals(MODE) || "auto".equals(MODE) || "gpu".equals(MODE));
   /** The GPU encodes where it can: gpu and auto always, worker only for textures without prepared blocks. */
   static final boolean GPU = Overrides.enabled() && !"driver".equals(MODE) && Config.TEX_COMPRESS_GPU;
   /** Set by the render thread once TexBcGpu compiled: workers then leave the encoding to it (auto, gpu). */
   static volatile boolean gpuReady;
   /** The render thread has confirmed S3TC (texCompressCache hits need it: their pixel buffer is a placeholder). */
   static volatile boolean s3tcOk;

   // counters for the console line (devTexCompTiming) and the tests
   static final AtomicLong workerTextures = new AtomicLong(), workerPixels = new AtomicLong(), workerNs = new AtomicLong();
   static final AtomicLong preparedTextures = new AtomicLong(), preparedPixels = new AtomicLong(), stagedTextures = new AtomicLong();
   /** The wrapped stock task (PNG decode; a pack page's stock initMipMaps too), for comparison with ours. */
   static final AtomicLong stockNs = new AtomicLong(), rawTextures = new AtomicLong();
   static final AtomicLong packTasks = new AtomicLong(), looseTasks = new AtomicLong(), preS3tc = new AtomicLong(), earlyFreed = new AtomicLong(), stagingFull = new AtomicLong();
   static long stagedUploads;
   static long gpuPixels;
   static long renderTextures, renderPixels, renderNs, gpuTextures, driverTextures;
   private static long lastLog;
   private static Boolean s3tc;

   private TexCompress() {
   }

   /**
    * Wraps a texture file task: after the inner task decoded the image (on its worker), the levels the texture will be
    * created with are encoded to BC3 there. flags are the TextureID's asset flags (4 = compression, 0x40 = mipmaps,
    * 0x10 = FBO filtering, 0x200 = depth).
    */
   public static FileTask wrap(FileTask inner, FileSystem fs, IFileTaskCallback cb, int flags, String pack, String page) {
      if (!WORKER || (flags & 4) == 0 || (flags & 0x200) != 0) {
         return null;
      }
      return new Task(inner, fs, cb, (flags & 0x40) != 0 && (flags & 0x10) == 0, pack, page, flags);
   }

   /** Staged-level flags (ImageData.pzoptStageFlags). */
   public static final int STAGE_GPU_MIPS = 1, STAGE_PREMUL = 2, STAGE_PRESERVE = 4;

   /** The GPU can take a raw level 0 and build + premultiply the chain itself (texCompressGpuMips). */
   static boolean gpuMipsNow() {
      return gpuReady && Config.TEX_COMPRESS_GPU_MIPS && !"worker".equals(MODE) && TexBcGpu.staging()
            && !zombie.debug.DebugOptions.instance.isoSprite.worldMipmapColors.getValue();
   }

   static final class Task extends FileTask {
      final FileTask inner;
      final boolean mips;
      final String pack, page;
      final int flags;

      Task(FileTask inner, FileSystem fs, IFileTaskCallback cb, boolean mips, String pack, String page, int flags) {
         super(fs, cb);
         this.inner = inner;
         this.mips = mips;
         this.pack = pack;
         this.page = page;
         this.flags = flags;
      }

      @Override
      public Object call() throws Exception {
         long t0 = System.nanoTime();
         Object r;
         java.io.File cacheFile = null;
         (this.pack != null ? packTasks : looseTasks).incrementAndGet();
         if (Config.TEX_COMPRESS_CACHE && this.pack != null && !s3tcOk) {
            preS3tc.incrementAndGet();
         }
         if (Config.TEX_COMPRESS_CACHE && this.pack != null && s3tcOk) {
            // the blocks as this upload wants them: premultiplied for a mipmapped upload or a pack whose flags mipmap
            boolean packMips = (this.fileSystem.getTexturePackFlags(this.pack) & 0x40) != 0;
            cacheFile = TexCache.file(this.pack, this.page, this.mips, this.mips || packMips, this.fileSystem.getTexturePackAlpha(this.pack, this.page));
            ImageData hit = TexCache.load(cacheFile, this.mips, this.flags);
            if (hit != null) {
               return hit;
            }
            r = this.inner.call();
            stockNs.addAndGet(System.nanoTime() - t0);
            if (r instanceof ImageData d) {
               encodeCpu(d, this.mips);
               if (!TexCache.wouldLimit(this.flags, d.getWidth(), d.getHeight())) {
                  TexCache.store(cacheFile, d, this.mips);
               }
            }
            return r;
         }
         boolean raw = gpuMipsNow();
         boolean packMips = false;
         if (raw && this.pack != null) {
            // FileTask_LoadPackImage.call without its initMipMaps: the GPU builds and premultiplies the chain
            zombie.core.textures.TextureIDAssetManager.instance.waitFileTask();
            packMips = (this.fileSystem.getTexturePackFlags(this.pack) & 0x40) != 0;
            boolean mask = this.fileSystem.getTexturePackAlpha(this.pack, this.page);
            try (java.io.InputStream in = this.fileSystem.openStream(this.fileSystem.getTexturePackDevice(this.pack), this.page)) {
               r = new ImageData(in, mask);
            }
         } else {
            r = this.inner.call();
            raw &= this.pack == null; // a pack page's stock task already built and premultiplied its mips
         }
         stockNs.addAndGet(System.nanoTime() - t0);
         if (r instanceof ImageData d) {
            if (!raw || !stageRaw(d, this.mips, packMips, this.flags)) {
               if (raw && packMips) {
                  d.initMipMaps(); // no staging room: the stock state after FileTask_LoadPackImage
               }
               encode(d, this.mips);
            }
         }
         return r;
      }

      @Override
      public void done() {
         this.inner.done();
      }

      @Override
      public String getErrorMessage() {
         return this.inner.getErrorMessage();
      }
   }

   /**
    * Worker side, GPU mips: only the unpremultiplied level 0 goes into the staging buffer, with room for the chain the
    * GPU builds behind it; premultiplied as it is encoded when stock would have (a mipmapped upload, or a pack page
    * whose stock task mipmaps). False when the staging buffer is full.
    */
   static boolean stageRaw(ImageData d, boolean mips, boolean packMips, int flags) {
      long t0 = System.nanoTime();
      int w0 = d.getWidthHW(), h0 = d.getHeightHW();
      int n = mips ? ImageData.calculateNumMips(w0, h0) : 1;
      long total = 0;
      for (int i = 0, w = w0, h = h0; i < n; i++) {
         total += (long) w * h * 4;
         w = ImageData.getNextMipDimension(w);
         h = ImageData.getNextMipDimension(h);
      }
      int off = total > Integer.MAX_VALUE ? -1 : reserveWaiting((int) total);
      if (off < 0) {
         return false;
      }
      MemoryUtil.memCopy(MemoryUtil.memAddress(d.getData().getBuffer(), 0), TexBcGpu.address(off), (long) w0 * h0 * 4);
      d.pzoptStage = off + 1;
      d.pzoptStageLen = (int) total;
      d.pzoptBc3Mips = mips;
      d.pzoptStageFlags = (mips ? STAGE_GPU_MIPS : 0) | (mips || packMips ? STAGE_PREMUL : 0) | (d.preserveTransparentColor ? STAGE_PRESERVE : 0);
      if (Config.TEX_COMPRESS_EARLY_FREE && !TexCache.wouldLimit(flags, d.getWidth(), d.getHeight())) {
         // the pixels live in the staging buffer now: free them at once (the decoded-bytes budget the decoders wait on
         // drops immediately) and leave a placeholder so stock's "has pixels" checks hold
         zombie.core.textures.MipMapLevel old = d.data;
         d.data = new zombie.core.textures.MipMapLevel(w0, h0, zombie.core.utils.DirectBufferAllocator.allocate(4));
         old.dispose();
         earlyFreed.incrementAndGet();
      }
      stagedTextures.incrementAndGet();
      rawTextures.incrementAndGet();
      preparedTextures.incrementAndGet();
      preparedPixels.addAndGet(total / 4);
      workerNs.addAndGet(System.nanoTime() - t0);
      return true;
   }

   /**
    * A staging range, waiting up to texCompressStagingWaitMs for the GPU to hand ranges back (released every frame):
    * a short wait here is cheaper than the CPU mips and the render-thread copy of the fallback.
    */
   static int reserveWaiting(int len) {
      int off = TexBcGpu.reserve(len);
      for (int waited = 0; off < 0 && waited < Config.TEX_COMPRESS_STAGING_WAIT_MS && TexBcGpu.staging() && len <= Config.TEX_COMPRESS_STAGING_MB << 20; waited += 2) {
         try {
            Thread.sleep(2L);
         } catch (InterruptedException e) {
            return -1;
         }
         off = TexBcGpu.reserve(len);
      }
      if (off < 0) {
         stagingFull.incrementAndGet();
      }
      return off;
   }

   /** Worker side: BC3 of every level the render thread will upload, stored on the ImageData. */
   static void encode(ImageData d, boolean mips) {
      if (gpuReady && !"worker".equals(MODE) || "gpu".equals(MODE)) {
         prepare(d, mips);
         return;
      }
      encodeCpu(d, mips);
   }

   /** Worker side, GPU encoding: the levels built (mips, premultiplied) and copied into the staging buffer if it has room. */
   static void prepare(ImageData d, boolean mips) {
      long t0 = System.nanoTime();
      {
         // the render thread will encode on the GPU: build the levels it uploads here (mips, premultiplied alpha)
         int n = mips ? d.getMipMapCount() : 1;
         long px = 0;
         MipMapLevel[] lv = new MipMapLevel[n];
         for (int i = 0; i < n; i++) {
            lv[i] = mips ? d.getMipMapData(i) : d.getData();
            px += (long) lv[i].width * lv[i].height;
         }
         // zero copy for the render thread: the levels go straight into the GPU's staging buffer from here
         int len = (int) (px * 4);
         int off = reserveWaiting(len);
         if (off >= 0) {
            long dst = TexBcGpu.address(off);
            for (int i = 0; i < n; i++) {
               int bytes = lv[i].width * lv[i].height * 4;
               MemoryUtil.memCopy(MemoryUtil.memAddress(lv[i].getBuffer(), 0), dst, bytes);
               dst += bytes;
            }
            d.pzoptStage = off + 1;
            d.pzoptStageLen = len;
            d.pzoptBc3Mips = mips;
            stagedTextures.incrementAndGet();
         }
         preparedTextures.incrementAndGet();
         preparedPixels.addAndGet(px);
         workerNs.addAndGet(System.nanoTime() - t0);
      }
   }

   /** Worker side, CPU encoding (TexBc). */
   static void encodeCpu(ImageData d, boolean mips) {
      long t0 = System.nanoTime();
      TexBc.HQ_THRESHOLD = Config.TEX_COMPRESS_HQ_THRESHOLD;
      int n = mips ? d.getMipMapCount() : 1;
      int[] w = new int[n], h = new int[n];
      ByteBuffer[] src = new ByteBuffer[n];
      long bytes = 0, px = 0;
      for (int i = 0; i < n; i++) {
         MipMapLevel l = mips ? d.getMipMapData(i) : d.getData();
         w[i] = l.width;
         h[i] = l.height;
         src[i] = l.getBuffer();
         bytes += TexBc.bc3Size(w[i], h[i]);
         px += (long) w[i] * h[i];
      }
      ByteBuffer dst = MemoryUtil.memAlloc((int) bytes);
      int off = 0;
      for (int i = 0; i < n; i++) {
         TexBc.encodeBc3(src[i], 0, w[i], h[i], dst, off, 0, (h[i] + 3) >> 2, Config.TEX_COMPRESS_HQ);
         off += TexBc.bc3Size(w[i], h[i]);
      }
      d.pzoptBc3 = dst;
      d.pzoptBc3Mips = mips;
      workerTextures.incrementAndGet();
      workerPixels.addAndGet(px);
      workerNs.addAndGet(System.nanoTime() - t0);
   }

   /** Frees a level set that was never uploaded (ImageData.dispose). */
   public static void free(ImageData d) {
      if (d.pzoptStage != 0) {
         TexBcGpu.release(d.pzoptStage - 1, d.pzoptStageLen); // never dispatched: nothing on the GPU reads it
         d.pzoptStage = 0;
      }
      ByteBuffer b = d.pzoptBc3;
      if (b != null) {
         d.pzoptBc3 = null;
         MemoryUtil.memFree(b);
      }
   }

   /** Render thread, every TextureID.generateHwId and every frame: releases staging ranges the GPU has finished reading. */
   public static void pollStaging() {
      if (TexBcGpu.staging()) {
         TexBcGpu.pollFences();
      }
   }

   private static boolean s3tc() {
      if (s3tc == null) {
         s3tc = org.lwjgl.opengl.GL.getCapabilities().GL_EXT_texture_compression_s3tc;
         s3tcOk = s3tc;
         if (s3tc && GPU && !"worker".equals(MODE)) {
            gpuReady = TexBcGpu.init();
            if (!gpuReady) {
               Log.info("texCompress: no GPU encoder (" + TexBcGpu.error + "), workers encode");
            } else if (Config.TEX_COMPRESS_STAGING_MB > 0) {
               boolean st = TexBcGpu.initStaging(Config.TEX_COMPRESS_STAGING_MB << 20);
               Log.info("texCompress: staging buffer " + (st ? Config.TEX_COMPRESS_STAGING_MB + " MB mapped" : "unavailable (the render thread copies)"));
            }
         }
         // after the GPU encoder is up (workers stage from here on), the decoder model for both encoders
         if (s3tc && Config.TEX_COMPRESS_MEASURE_DECODER) {
            long tm = System.nanoTime();
            boolean measured = TexBcPalette.measure();
            if (measured && gpuReady) {
               TexBcGpu.setLut();
            }
            Log.info("texCompress: " + (measured ? "fitting to this GPU's measured BC3 decode" : "BC3 decode formula (" + TexBcPalette.error + ")")
                  + String.format(java.util.Locale.ROOT, ", %.1f ms", (System.nanoTime() - tm) / 1e6));
         }
         Log.info("texCompress=" + Config.TEX_COMPRESS + " (worker " + WORKER + ", gpu " + GPU + ", hq " + Config.TEX_COMPRESS_HQ + ", s3tc " + s3tc + ")");
      }
      return s3tc;
   }

   /**
    * Render thread, in TextureID.generateHwId where stock would upload GL_COMPRESSED_RGBA levels into the bound
    * texture: creates them as BC3 instead and returns true; false leaves it to the stock (driver) path.
    */
   public static boolean upload(ImageData d, boolean mips, int widthHw, int heightHw) {
      if (d == null || !Overrides.enabled() || "driver".equals(Config.TEX_COMPRESS) || !s3tc()) {
         return false;
      }
      long t0 = System.nanoTime();
      ByteBuffer blocks = d.pzoptBc3;
      int n = mips ? d.getMipMapCount() : 1;
      if (blocks != null && d.pzoptBc3Mips == mips) {
         int off = 0;
         long px = 0;
         for (int i = 0, w = widthHw, h = heightHw; i < n; i++, w = ImageData.getNextMipDimension(w), h = ImageData.getNextMipDimension(h)) {
            // sizes only: a cached page has no pixels to build mips from
            int size = TexBc.bc3Size(w, h);
            GL13C.glCompressedTexImage2D(GL11C.GL_TEXTURE_2D, i, TexBc.GL_COMPRESSED_RGBA_S3TC_DXT5_EXT, w, h, 0,
                  MemoryUtil.memSlice(blocks, off, size));
            off += size;
            px += (long) w * h;
         }
         renderTextures++;
         renderPixels += px;
         renderNs += System.nanoTime() - t0;
         log();
         return true;
      }
      if (d.pzoptStage != 0 && d.pzoptBc3Mips == mips) {
         int off = d.pzoptStage - 1;
         d.pzoptStage = 0; // released by the fence from here on
         int[] w = new int[n], h = new int[n];
         for (int i = 0; i < n; i++) {
            // sizes only: getMipMapData would build CPU mips of a raw-staged image
            w[i] = i == 0 ? widthHw : ImageData.getNextMipDimension(w[i - 1]);
            h[i] = i == 0 ? heightHw : ImageData.getNextMipDimension(h[i - 1]);
            gpuPixels += (long) w[i] * h[i];
         }
         int f = d.pzoptStageFlags;
         TexBcGpu.uploadStaged(off, d.pzoptStageLen, w, h, 0, Config.TEX_COMPRESS_HQ, (f & STAGE_GPU_MIPS) != 0, (f & STAGE_PREMUL) != 0, (f & STAGE_PRESERVE) != 0);
         gpuTextures++;
         stagedUploads++;
         renderNs += System.nanoTime() - t0;
         log();
         return true;
      }
      if (GPU && TexBcGpu.init()) {
         gpuReady = true;
         ByteBuffer[] lv = new ByteBuffer[n];
         int[] w = new int[n], h = new int[n];
         for (int i = 0; i < n; i++) {
            MipMapLevel l = mips ? d.getMipMapData(i) : d.getData();
            lv[i] = l.getBuffer();
            w[i] = mips ? l.width : widthHw;
            h[i] = mips ? l.height : heightHw;
         }
         TexBcGpu.upload(lv, w, h, 0, Config.TEX_COMPRESS_HQ);
         gpuTextures++;
         for (int i = 0; i < n; i++) {
            gpuPixels += (long) w[i] * h[i];
         }
         renderNs += System.nanoTime() - t0;
         log();
         return true;
      }
      driverTextures++;
      return false;
   }

   private static volatile long lastUpload;
   private static boolean summaryThread;

   private static void log() {
      if (!Config.DEV_TEX_COMP_TIMING) {
         return;
      }
      long now = System.nanoTime();
      lastUpload = now;
      if (!summaryThread) {
         // one more line once uploads have been quiet for 1.5 s, so the totals include the last burst
         summaryThread = true;
         Thread t = new Thread(() -> {
            long printed = 0;
            while (true) {
               try {
                  Thread.sleep(500L);
               } catch (InterruptedException e) {
                  return;
               }
               long lu = lastUpload;
               if (lu != printed && System.nanoTime() - lu > 1_500_000_000L) {
                  printed = lu;
                  Log.info(line() + " (quiet)");
               }
            }
         }, "pzopt-texcompress-log");
         t.setDaemon(true);
         t.start();
      }
      if (now - lastLog < 2_000_000_000L) {
         return;
      }
      lastLog = now;
      Log.info(line());
   }

   private static String line() {
      return String.format(java.util.Locale.ROOT,
            "texCompress: stock decode %.0f ms CPU; %d raw-staged (GPU mips); workers encoded %d textures %.1f MPixel, prepared %d (%.1f MPixel), %.0f ms CPU; render thread %d from blocks (%.1f MPixel) + %d gpu (%.1f MPixel, %d staged), %.1f ms; driver %d; %s",
            stockNs.get() / 1e6, rawTextures.get(), workerTextures.get(), workerPixels.get() / 1e6, preparedTextures.get(), preparedPixels.get() / 1e6, workerNs.get() / 1e6,
            renderTextures, renderPixels / 1e6, gpuTextures, gpuPixels / 1e6, stagedUploads, renderNs / 1e6, driverTextures, TexCache.summary())
            + "; wrapped tasks: pack " + packTasks.get() + ", loose " + looseTasks.get() + ", cache off before S3TC known " + preS3tc.get()
            + "; pixels freed after staging " + earlyFreed.get() + "; staging full " + stagingFull.get() + "; decoders waited " + FileTaskStats.summaryOf("waitFileTask(sleep)");
   }
}
