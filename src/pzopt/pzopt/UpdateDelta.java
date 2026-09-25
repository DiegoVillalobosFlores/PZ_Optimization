package pzopt;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The fast path of pzopt.Updater: install a release by moving only the bytes that changed.
 *
 * Consecutive releases differ in a handful of files (ab4b22b vs b70f234: 3 of 613 entries, 57 KB of 59 MB; a day of
 * releases, f837467 vs ab4b22b: 150 entries, 0.97 MB), and a zip says what every entry is before any of it is read:
 * the central directory at its end carries each entry's CRC-32, sizes and offset. So the updater reads the directory
 * (one range request of the zip's last 64 KB), compares each entry's CRC-32 and size with the installed file
 * ({@link #plan}, all cores), fetches only the changed entries' byte spans ({@link #fetch}: neighbouring spans
 * merged, parallel single-range requests over one HTTP/2 connection; GitHub's asset store answers single ranges,
 * refuses multi-range and suffix ranges), inflates and CRC-checks them on the same workers, and {@link #apply}
 * writes each one next to its target and renames it over the old file. Unchanged files are never rewritten; the
 * manifest keeps their hashes from the previous one. A full zip on disk goes through the same code with a
 * {@link FileSource}, the Workshop copy through {@link #folderChanges}.
 */
final class UpdateDelta {
   static final int LOCAL_HEADER = 0x04034b50;
   static final int CENTRAL_HEADER = 0x02014b50;
   static final int END_OF_CENTRAL = 0x06054b50;
   // Profiled 2026-09-26 (UpdaterBench "delta tuning"): on a gigabit line every setting is within noise (256-330 ms
   // cold for the 150-entry case), so the bytes decide for slow lines: a 16 KB gap and 1 MB cap fetch 1.18 MB in 24
   // multiplexed requests where 64 KB / 4 MB fetched 1.84 MB in 8; the directory of 613 entries fits the 64 KB tail.
   static int tail = 64 * 1024;
   static long mergeGap = 16 * 1024;            // two spans closer than this are one request
   static long maxSpan = 1L << 20;              // but no request longer than this (parallelism)

   private UpdateDelta() {
   }

   /** One file of the zip. {@code end} is where its local record ends (the next record or the directory). */
   record Entry(String name, long crc, long compSize, long size, int method, long offset, long end) {
   }

   record Directory(List<Entry> entries, long directoryOffset) {
   }

   /** What changed: every entry of the release, and those whose installed file differs. */
   record Plan(Directory dir, List<Entry> changed, long changedBytes, long changedCompressed) {
   }

   /** Where a zip's bytes come from. */
   interface Source {
      long size();

      byte[] read(long from, int len) throws Exception;

      default String describe() {
         return getClass().getSimpleName();
      }
   }

   /** A zip on disk. */
   static final class FileSource implements Source, AutoCloseable {
      private final FileChannel ch;
      private final long size;

      FileSource(Path zip) throws IOException {
         ch = FileChannel.open(zip, StandardOpenOption.READ);
         size = ch.size();
      }

      @Override
      public long size() {
         return size;
      }

      @Override
      public byte[] read(long from, int len) throws IOException {
         ByteBuffer b = ByteBuffer.allocate(len);
         while (b.hasRemaining()) {
            if (ch.read(b, from + b.position()) < 0) {
               throw new IOException("zip ends at " + (from + b.position()));
            }
         }
         return b.array();
      }

      @Override
      public void close() throws IOException {
         ch.close();
      }
   }

   /**
    * A release asset over HTTP range requests. The first request follows GitHub's redirect to the signed asset URL;
    * later ones go to that URL directly (one round trip less each), and back through the redirect once if it expired.
    */
   static final class HttpSource implements Source {
      private final HttpClient client;
      private final String url;
      private final long size;
      private volatile URI direct;
      final AtomicLong requests = new AtomicLong();
      final AtomicLong bytes = new AtomicLong();

      HttpSource(HttpClient client, String url, long size) {
         this.client = client;
         this.url = url;
         this.size = size;
      }

      @Override
      public long size() {
         return size;
      }

      @Override
      public byte[] read(long from, int len) throws Exception {
         URI u = direct;
         try {
            return get(u != null ? u : URI.create(url), from, len);
         } catch (IOException e) {
            if (u == null) {
               throw e;
            }
            direct = null; // the signed URL may have expired: once more through the redirect
            return get(URI.create(url), from, len);
         }
      }

      private byte[] get(URI u, long from, int len) throws Exception {
         HttpRequest req = HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(60))
               .header("User-Agent", "PZ_Optimization-updater").header("Range", "bytes=" + from + "-" + (from + len - 1)).GET().build();
         HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
         requests.incrementAndGet();
         if (resp.statusCode() != 206) {
            throw new RangeUnsupported("range request answered HTTP " + resp.statusCode());
         }
         String cr = resp.headers().firstValue("Content-Range").orElse("");
         if (!cr.startsWith("bytes " + from + "-" + (from + len - 1) + "/")) {
            throw new RangeUnsupported("range request answered " + cr);
         }
         byte[] b = resp.body();
         if (b.length != len) {
            throw new IOException("range " + from + "+" + len + " returned " + b.length + " bytes");
         }
         bytes.addAndGet(len);
         if (!resp.uri().equals(URI.create(url))) {
            direct = resp.uri();
         }
         return b;
      }

      @Override
      public String describe() {
         return "http " + requests.get() + " requests, " + bytes.get() + " bytes";
      }
   }

   /** The server does not do range requests (a proxy, a mirror): the caller downloads the whole zip instead. */
   static final class RangeUnsupported extends IOException {
      RangeUnsupported(String m) {
         super(m);
      }
   }

   // --- the directory ---------------------------------------------------------------------------------

   static Directory directory(Source s) throws Exception {
      long size = s.size();
      int tl = (int) Math.min(size, UpdateDelta.tail);
      long tailStart = size - tl;
      byte[] b = s.read(tailStart, tl);
      int eocd = -1;
      for (int i = tl - 22; i >= Math.max(0, tl - 22 - 65535); i--) {
         if (le32(b, i) == END_OF_CENTRAL) {
            eocd = i;
            break;
         }
      }
      if (eocd < 0) {
         throw new IOException("not a zip (no end of central directory)");
      }
      int count = le16(b, eocd + 10);
      long cdSize = le32u(b, eocd + 12);
      long cdOff = le32u(b, eocd + 16);
      if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
         throw new IOException("zip64 is not supported");
      }
      byte[] cd;
      int base;
      if (cdOff >= tailStart) {
         cd = b;
         base = (int) (cdOff - tailStart);
      } else {
         cd = s.read(cdOff, (int) cdSize);
         base = 0;
      }
      List<Entry> raw = new ArrayList<>(count);
      int p = base;
      for (int i = 0; i < count; i++) {
         if (le32(cd, p) != CENTRAL_HEADER) {
            throw new IOException("bad central directory record " + i);
         }
         int method = le16(cd, p + 10);
         long crc = le32u(cd, p + 16);
         long comp = le32u(cd, p + 20);
         long usize = le32u(cd, p + 24);
         int n = le16(cd, p + 28);
         int x = le16(cd, p + 30);
         int c = le16(cd, p + 32);
         long off = le32u(cd, p + 42);
         String name = new String(cd, p + 46, n, StandardCharsets.UTF_8);
         p += 46 + n + x + c;
         if (!name.endsWith("/")) {
            if (method != 0 && method != 8) {
               throw new IOException("entry " + name + " uses compression method " + method);
            }
            checkName(name);
            raw.add(new Entry(name, crc, comp, usize, method, off, -1));
         }
      }
      // a record ends where the next one starts (the local extra field is not in the directory)
      List<Entry> byOffset = new ArrayList<>(raw);
      byOffset.sort(Comparator.comparingLong(Entry::offset));
      List<Entry> out = new ArrayList<>(raw.size());
      for (int i = 0; i < byOffset.size(); i++) {
         Entry e = byOffset.get(i);
         long end = i + 1 < byOffset.size() ? byOffset.get(i + 1).offset : cdOff;
         out.add(new Entry(e.name, e.crc, e.compSize, e.size, e.method, e.offset, end));
      }
      return new Directory(out, cdOff);
   }

   static void checkName(String rel) throws IOException {
      if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains(":") || ("/" + rel + "/").contains("/../")) {
         throw new IOException("refusing zip entry " + rel);
      }
   }

   // --- what changed ----------------------------------------------------------------------------------

   /** Compares every entry with the file under {@code dir}: same size and CRC-32 = unchanged. */
   static Plan plan(Directory d, Path dir, ExecutorService pool) throws Exception {
      List<Future<Boolean>> same = new ArrayList<>(d.entries.size());
      for (Entry e : d.entries) {
         same.add(pool.submit(() -> unchanged(dir.resolve(e.name), e)));
      }
      List<Entry> changed = new ArrayList<>();
      long bytes = 0, comp = 0;
      for (int i = 0; i < same.size(); i++) {
         if (!same.get(i).get()) {
            Entry e = d.entries.get(i);
            changed.add(e);
            bytes += e.size;
            comp += e.compSize;
         }
      }
      return new Plan(d, changed, bytes, comp);
   }

   static boolean unchanged(Path f, Entry e) {
      try {
         if (!Files.isRegularFile(f) || Files.size(f) != e.size) {
            return false;
         }
         CRC32 crc = new CRC32();
         crc.update(Files.readAllBytes(f));
         return crc.getValue() == e.crc;
      } catch (IOException x) {
         return false;
      }
   }

   // --- fetching the changed entries ------------------------------------------------------------------

   record Span(long from, long to, List<Entry> entries) {
   }

   static List<Span> spans(List<Entry> changed) {
      List<Entry> sorted = new ArrayList<>(changed);
      sorted.sort(Comparator.comparingLong(Entry::offset));
      List<Span> out = new ArrayList<>();
      long from = -1, to = -1;
      List<Entry> cur = null;
      for (Entry e : sorted) {
         if (cur != null && e.offset - to <= mergeGap && e.end - from <= maxSpan) {
            cur.add(e);
            to = e.end;
         } else {
            if (cur != null) {
               out.add(new Span(from, to, cur));
            }
            cur = new ArrayList<>();
            cur.add(e);
            from = e.offset;
            to = e.end;
         }
      }
      if (cur != null) {
         out.add(new Span(from, to, cur));
      }
      return out;
   }

   /** Reads, inflates and CRC-checks the changed entries: name → content. */
   static Map<String, byte[]> fetch(Source s, List<Entry> changed, ExecutorService pool, IntConsumer progress) throws Exception {
      List<Span> spans = spans(changed);
      long total = 0;
      for (Span sp : spans) {
         total += sp.to - sp.from;
      }
      long all = Math.max(1, total);
      AtomicLong done = new AtomicLong();
      List<Future<Map<String, byte[]>>> parts = new ArrayList<>(spans.size());
      for (Span sp : spans) {
         parts.add(pool.submit((Callable<Map<String, byte[]>>) () -> {
            byte[] b = s.read(sp.from, (int) (sp.to - sp.from));
            Map<String, byte[]> m = new HashMap<>();
            for (Entry e : sp.entries) {
               m.put(e.name, extract(b, (int) (e.offset - sp.from), e));
            }
            if (progress != null) {
               progress.accept((int) Math.min(99, done.addAndGet(sp.to - sp.from) * 100 / all));
            }
            return m;
         }));
      }
      Map<String, byte[]> out = new HashMap<>();
      for (Future<Map<String, byte[]>> f : parts) {
         out.putAll(f.get());
      }
      return out;
   }

   static byte[] extract(byte[] b, int at, Entry e) throws IOException {
      if (le32(b, at) != LOCAL_HEADER) {
         throw new IOException("no local header for " + e.name);
      }
      int n = le16(b, at + 26);
      int x = le16(b, at + 28);
      int data = at + 30 + n + x;
      if ((long) data + e.compSize > b.length) {
         throw new IOException("entry " + e.name + " runs past its span");
      }
      byte[] out;
      if (e.method == 0) {
         out = new byte[(int) e.size];
         System.arraycopy(b, data, out, 0, out.length);
      } else {
         out = new byte[(int) e.size];
         Inflater inf = new Inflater(true);
         try {
            inf.setInput(b, data, (int) e.compSize);
            int pos = 0;
            while (pos < out.length && !inf.finished()) {
               int k = inf.inflate(out, pos, out.length - pos);
               if (k == 0 && (inf.needsInput() || inf.needsDictionary())) {
                  break;
               }
               pos += k;
            }
            if (pos != out.length) {
               throw new IOException("entry " + e.name + " inflated to " + pos + " of " + out.length + " bytes");
            }
         } catch (DataFormatException x2) {
            throw new IOException("entry " + e.name + ": " + x2.getMessage());
         } finally {
            inf.end();
         }
      }
      CRC32 crc = new CRC32();
      crc.update(out);
      if (crc.getValue() != e.crc) {
         throw new IOException("entry " + e.name + " fails its CRC-32");
      }
      return out;
   }

   // --- the Workshop copy -----------------------------------------------------------------------------

   /** Every file of the unpacked copy (relative, '/'), and the content of those that differ from {@code dir}. */
   record FolderChanges(List<String> names, Map<String, byte[]> changed) {
   }

   static FolderChanges folderChanges(Path src, Path dir, ExecutorService pool) throws Exception {
      List<Path> files;
      try (var s = Files.walk(src)) {
         files = s.filter(Files::isRegularFile).toList();
      }
      List<String> names = new ArrayList<>(files.size());
      List<Future<byte[]>> diff = new ArrayList<>(files.size());
      for (Path f : files) {
         String rel = src.relativize(f).toString().replace(java.io.File.separatorChar, '/');
         checkName(rel);
         names.add(rel);
         Path dst = dir.resolve(rel);
         diff.add(pool.submit(() -> {
            if (Files.isRegularFile(dst) && Files.size(dst) == Files.size(f) && Files.mismatch(dst, f) == -1L) {
               return null;
            }
            return Files.readAllBytes(f);
         }));
      }
      Map<String, byte[]> changed = new HashMap<>();
      for (int i = 0; i < names.size(); i++) {
         byte[] b = diff.get(i).get();
         if (b != null) {
            changed.put(names.get(i), b);
         }
      }
      return new FolderChanges(names, changed);
   }

   // --- applying --------------------------------------------------------------------------------------

   /**
    * Writes the changed files (each to a temporary name beside its target, then renamed over it), deletes what the
    * previous install listed and {@code names} no longer has, and returns rel path → sha256 of the whole install for
    * the manifest: new content hashed from memory, unchanged files' hashes taken from {@code previousHashes} when it
    * has them (else read back).
    */
   static Map<String, String> apply(List<String> names, Map<String, byte[]> changed, Path dir, List<String> previous,
         Map<String, String> previousHashes, ExecutorService pool) throws Exception {
      List<String> sorted = new ArrayList<>(names);
      sorted.sort(null);
      List<Future<String>> hashes = new ArrayList<>(sorted.size());
      for (String rel : sorted) {
         byte[] b = changed.get(rel);
         String old = previousHashes.get(rel);
         hashes.add(pool.submit(() -> {
            if (b != null) {
               write(dir, rel, b);
               return sha256(b);
            }
            if (old != null && old.length() == 64) {
               return old;
            }
            return Updater.sha256(dir.resolve(rel));
         }));
      }
      Map<String, String> installed = new java.util.LinkedHashMap<>();
      for (int i = 0; i < sorted.size(); i++) {
         installed.put(sorted.get(i), hashes.get(i).get());
      }
      for (String rel : previous) {
         if (!installed.containsKey(rel)) {
            Files.deleteIfExists(dir.resolve(rel));
            Updater.removeEmptyParents(dir, dir.resolve(rel).getParent());
         }
      }
      return installed;
   }

   static void write(Path dir, String rel, byte[] b) throws IOException {
      Path dst = dir.resolve(rel).normalize();
      if (!dst.startsWith(dir)) {
         throw new IOException("refusing zip entry " + rel);
      }
      Files.createDirectories(dst.getParent());
      Path tmp = dst.resolveSibling(dst.getFileName() + ".pzopt-new");
      Files.write(tmp, b);
      try {
         Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
         Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   static String sha256(byte[] b) throws Exception {
      return hex(MessageDigest.getInstance("SHA-256").digest(b));
   }

   static String hex(byte[] d) {
      StringBuilder sb = new StringBuilder(d.length * 2);
      for (byte x : d) {
         sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
      }
      return sb.toString();
   }

   // --- whole-zip download (no range support) ---------------------------------------------------------

   /**
    * Downloads {@code url} into {@code out} as {@code parts} parallel ranges (one stream when the server has no
    * ranges or the size is unknown). Returns the number of parallel parts used.
    */
   static int downloadSegmented(HttpClient client, String url, long size, Path out, int parts, ExecutorService pool, IntConsumer progress)
         throws Exception {
      if (size <= 0 || parts <= 1) {
         Updater.fetchWith(client, url, size, out, progress);
         return 1;
      }
      HttpSource src = new HttpSource(client, url, size);
      long chunk = (size + parts - 1) / parts;
      AtomicLong done = new AtomicLong();
      try (FileChannel ch = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
         // the first part resolves the redirect; the others then go to the asset URL directly
         byte[] first = src.read(0, (int) Math.min(chunk, size));
         ch.write(ByteBuffer.wrap(first), 0);
         done.addAndGet(first.length);
         List<Future<?>> fs = new ArrayList<>();
         for (long from = chunk; from < size; from += chunk) {
            long f0 = from;
            int len = (int) Math.min(chunk, size - from);
            fs.add(pool.submit((Callable<Void>) () -> {
               byte[] b = src.read(f0, len);
               synchronized (ch) {
                  ch.write(ByteBuffer.wrap(b), f0);
               }
               if (progress != null) {
                  progress.accept((int) Math.min(99, done.addAndGet(len) * 100 / size));
               }
               return null;
            }));
         }
         for (Future<?> f : fs) {
            f.get();
         }
      } catch (RangeUnsupported e) {
         Updater.fetchWith(client, url, size, out, progress);
         return 1;
      }
      return parts;
   }

   static ExecutorService pool(String name, int threads) {
      AtomicLong n = new AtomicLong();
      return Executors.newFixedThreadPool(threads, r -> {
         Thread t = new Thread(r, name + "-" + n.incrementAndGet());
         t.setDaemon(true);
         return t;
      });
   }

   static int le16(byte[] b, int i) {
      return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8;
   }

   static int le32(byte[] b, int i) {
      return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24;
   }

   static long le32u(byte[] b, int i) {
      return le32(b, i) & 0xFFFFFFFFL;
   }
}
