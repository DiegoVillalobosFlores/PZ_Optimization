package pzopt;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.lwjgl.system.MemoryUtil;
import zombie.core.textures.ImageData;
import zombie.core.utils.BooleanGrid;
import zombie.core.utils.DirectBufferAllocator;

/**
 * texCompressCache: the BC3 levels of each compressed texture-pack page kept on disk (~/Zomboid/pzopt/texcache,
 * deflated: 36-40 % of the blocks, ~330 MB for everything a boot loads), so a later boot skips the page's PNG decode
 * (13 ns a pixel on the desktop), its mips and its encode: the worker inflates the blocks (0.85 ns a pixel) and the
 * render thread uploads them. Keyed by the resolved pack file (path, size, mtime: a mod's pack of the same name is a
 * different key), the page, the upload's mip / premultiply state, the mask flag and the encoder version.
 *
 * A hit returns an ImageData with the texture's sizes, the mask and the blocks, and a 4-byte placeholder pixel buffer
 * (so stock's "has pixels" checks hold); hits are used only once the render thread has confirmed S3TC, so the stock
 * uncompressed path never reads the placeholder. Pages that TextureID.limitMaxSize would downscale are not cached.
 */
public final class TexCache {
   static final int MAGIC = 0x50425A33, VERSION = 2; // "3ZBP"
   static final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), stores = new AtomicLong(), hitNs = new AtomicLong(), bytesOnDisk = new AtomicLong();
   private static File dir;
   private static java.lang.reflect.Field gridValue;

   private TexCache() {
   }

   static File dir() {
      if (dir == null) {
         File d = new File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt" + File.separator + "texcache");
         d.mkdirs();
         dir = d;
      }
      return dir;
   }

   /** Cache file of a pack page as uploaded with this mip / premultiply state; null when the pack cannot be resolved. */
   static File file(String pack, String page, boolean mips, boolean premul, boolean mask) {
      try {
         String path = zombie.ZomboidFileSystem.instance.getString("media/texturepacks/" + pack + ".pack");
         File f = new File(path);
         if (!f.isFile()) {
            return null;
         }
         String key = pack + '\u0000' + page + '\u0000' + f.getAbsolutePath() + '\u0000' + f.length() + '\u0000' + f.lastModified() + '\u0000'
               + mips + premul + mask + '\u0000' + VERSION + '\u0000' + Config.TEX_COMPRESS_HQ + '\u0000' + Config.TEX_COMPRESS_HQ_THRESHOLD + '\u0000' + TexBc.decoderId();
         byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
         StringBuilder sb = new StringBuilder(44);
         for (byte b : h) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
         }
         return new File(dir(), sb.append(".bc3z").toString());
      } catch (Exception e) {
         return null;
      }
   }

   /** A cached page as an ImageData carrying its BC3 levels (pzoptBc3), or null on a miss / a bad file. */
   static ImageData load(File f, boolean mips, int flags) {
      if (f == null || !f.isFile()) {
         misses.incrementAndGet();
         return null;
      }
      long t0 = System.nanoTime();
      try {
         // read, not mapped: a mapped file cannot be replaced on Windows while the mapping lives
         byte[] all = Files.readAllBytes(f.toPath());
         ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
         if (b.getInt() != MAGIC || b.getInt() != VERSION) {
            misses.incrementAndGet();
            return null;
         }
         int width = b.getInt(), height = b.getInt(), widthHw = b.getInt(), heightHw = b.getInt();
         if (wouldLimit(flags, width, height)) {
            misses.incrementAndGet();
            return null;
         }
         boolean cachedMips = b.get() != 0;
         int maskInts = b.getInt();
         int[] mask = null;
         if (maskInts >= 0) {
            mask = new int[maskInts];
            b.asIntBuffer().get(mask);
            b.position(b.position() + maskInts * 4);
         }
         int rawLen = b.getInt(), packedLen = b.getInt();
         if (cachedMips != mips || rawLen <= 0) {
            misses.incrementAndGet();
            return null;
         }
         ByteBuffer blocks = MemoryUtil.memAlloc(rawLen);
         Inflater inf = new Inflater();
         try {
            inf.setInput(all, b.position(), packedLen);
            int n = inf.inflate(blocks);
            if (n != rawLen || !inf.finished()) {
               MemoryUtil.memFree(blocks);
               misses.incrementAndGet();
               return null;
            }
         } finally {
            inf.end();
         }
         blocks.flip();
         ImageData d = new ImageData(width, height, DirectBufferAllocator.allocate(4));
         if (d.getWidthHW() != widthHw || d.getHeightHW() != heightHw) {
            MemoryUtil.memFree(blocks);
            d.dispose();
            misses.incrementAndGet();
            return null;
         }
         if (mask != null) {
            BooleanGrid g = new BooleanGrid(width, height);
            int[] v = gridValue(g);
            if (v.length != mask.length) {
               MemoryUtil.memFree(blocks);
               d.dispose();
               misses.incrementAndGet();
               return null;
            }
            System.arraycopy(mask, 0, v, 0, v.length);
            d.mask = g;
         }
         d.pzoptBc3 = blocks;
         d.pzoptBc3Mips = mips;
         hits.incrementAndGet();
         hitNs.addAndGet(System.nanoTime() - t0);
         return d;
      } catch (Exception e) {
         misses.incrementAndGet();
         return null;
      }
   }

   /** Writes a page's BC3 levels (the worker's own blocks) with its sizes and mask; temp file + rename. */
   static void store(File f, ImageData d, boolean mips) {
      ByteBuffer blocks = d.pzoptBc3;
      if (f == null || blocks == null) {
         return;
      }
      try {
         int rawLen = blocks.capacity();
         byte[] raw = new byte[rawLen];
         blocks.get(0, raw);
         Deflater def = new Deflater(1);
         def.setInput(raw);
         def.finish();
         byte[] packed = new byte[rawLen + 1024];
         int packedLen = 0;
         while (!def.finished()) {
            packedLen += def.deflate(packed, packedLen, packed.length - packedLen);
            if (packedLen == packed.length) {
               packed = java.util.Arrays.copyOf(packed, packed.length * 2);
            }
         }
         def.end();
         int[] mask = d.mask != null ? gridValue(d.mask) : null;
         ByteBuffer head = ByteBuffer.allocate(64 + (mask != null ? mask.length * 4 : 0)).order(ByteOrder.LITTLE_ENDIAN);
         head.putInt(MAGIC).putInt(VERSION).putInt(d.getWidth()).putInt(d.getHeight()).putInt(d.getWidthHW()).putInt(d.getHeightHW());
         head.put((byte) (mips ? 1 : 0));
         head.putInt(mask != null ? mask.length : -1);
         if (mask != null) {
            for (int v : mask) {
               head.putInt(v);
            }
         }
         head.putInt(rawLen).putInt(packedLen);
         head.flip();
         File tmp = new File(f.getPath() + "." + Thread.currentThread().getId() + ".tmp");
         try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw"); FileChannel ch = raf.getChannel()) {
            ch.write(head);
            ch.write(ByteBuffer.wrap(packed, 0, packedLen));
         }
         Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         stores.incrementAndGet();
         bytesOnDisk.addAndGet(head.limit() + packedLen);
      } catch (IOException | RuntimeException e) {
         Log.warn("texCompressCache: store failed (" + e + ")");
      }
   }

   /** TextureID.limitMaxSize would downscale this texture (a smaller mip replaces it): not cached. */
   static boolean wouldLimit(int flags, int width, int height) {
      if ((flags & 0x180) == 0) {
         return false;
      }
      int max = zombie.core.Core.getInstance().getMaxTextureSizeFromFlags(flags);
      return width > max || height > max;
   }

   static int[] gridValue(BooleanGrid g) {
      try {
         if (gridValue == null) {
            java.lang.reflect.Field f = BooleanGrid.class.getDeclaredField("value");
            f.setAccessible(true);
            gridValue = f;
         }
         return (int[]) gridValue.get(g);
      } catch (ReflectiveOperationException e) {
         throw new IllegalStateException(e);
      }
   }

   static String summary() {
      return String.format(java.util.Locale.ROOT, "cache %d hits (%.0f ms), %d misses, %d stored (%.0f MB)", hits.get(), hitNs.get() / 1e6, misses.get(),
            stores.get(), bytesOnDisk.get() / 1e6);
   }
}
