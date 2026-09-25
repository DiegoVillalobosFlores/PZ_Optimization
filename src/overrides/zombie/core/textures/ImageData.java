package zombie.core.textures;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;
import zombie.ZomboidFileSystem;
import zombie.core.math.PZMath;
import zombie.core.textures.PNGDecoder.Format;
import zombie.core.utils.BooleanGrid;
import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.ImageUtils;
import zombie.core.utils.WrappedBuffer;
import zombie.core.znet.SteamFriends;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.util.list.PZArrayUtil;

public final class ImageData implements Serializable {
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.textures.ImageData"); // pzopt
   }

   private static final long serialVersionUID = -7893392091273534932L;
   public MipMapLevel data;
   private MipMapLevel[] mipMaps;
   private int height;
   private int heightHw;
   private boolean solid = true;
   private int width;
   private int widthHw;
   private int mipMapCount = -1;
   public boolean alphaPaddingDone;
   private boolean preMultipliedAlphaDone;
   public boolean preserveTransparentColor;
   public BooleanGrid mask;
   private static final int BufferSize = 67108864;
   public int id = -1;
   public transient java.nio.ByteBuffer pzoptBc3; // pzopt: texCompress, the BC3 levels a file-pool worker encoded (pzopt.TexCompress)
   public transient boolean pzoptBc3Mips; // pzopt: whether they are the whole mip chain or level 0 alone
   public transient int pzoptStage; // pzopt: texCompress gpu, offset + 1 of the levels in pzopt.TexBcGpu's staging buffer (0 = none)
   public transient int pzoptStageLen; // pzopt: their byte length
   public transient int pzoptStageFlags; // pzopt: pzopt.TexCompress.STAGE_* (GPU builds the mips / premultiplies / preserveTransparentColor)
   final ArrayList<ImageDataFrame> frames = new ArrayList<>();
   public static final int MIP_LEVEL_IDX_OFFSET = 0;
   private static final ThreadLocal<ImageData.L_generateMipMaps> TL_generateMipMaps = ThreadLocal.withInitial(ImageData.L_generateMipMaps::new);
   private static final ThreadLocal<ImageData.L_performAlphaPadding> TL_performAlphaPadding = ThreadLocal.withInitial(ImageData.L_performAlphaPadding::new);

   public ImageData(TextureID texture, WrappedBuffer bb) {
      this.data = new MipMapLevel(texture.widthHw, texture.heightHw, bb);
      this.width = texture.width;
      this.widthHw = texture.widthHw;
      this.height = texture.height;
      this.heightHw = texture.heightHw;
      this.solid = texture.solid;
   }

   public ImageData(String path) throws Exception {
      if (path.contains(".txt")) {
         path = path.replace(".txt", ".png");
      }

      path = Texture.processFilePath(path);
      path = ZomboidFileSystem.instance.getString(path);
      ZomboidFileSystem.instance.validatePrefix(path);
      if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
         try {
            BufferedImage bufferedImage = ImageIO.read(new File(path).getAbsoluteFile());
            this.width = bufferedImage.getWidth();
            this.height = bufferedImage.getHeight();
            this.widthHw = ImageUtils.getNextPowerOfTwoHW(this.width);
            this.heightHw = ImageUtils.getNextPowerOfTwoHW(this.height);
            this.data = new MipMapLevel(this.widthHw, this.heightHw);
            ByteBuffer buf = this.data.getBuffer();
            buf.rewind();
            int stride = this.widthHw * 4;
            if (this.width != this.widthHw) {
               for (int x = this.width * 4; x < this.widthHw * 4; x++) {
                  for (int y = 0; y < this.heightHw; y++) {
                     buf.put(x + y * stride, (byte)0);
                  }
               }
            }

            if (this.height != this.heightHw) {
               for (int y = this.height; y < this.heightHw; y++) {
                  for (int x = 0; x < this.width * 4; x++) {
                     buf.put(x + y * stride, (byte)0);
                  }
               }
            }

            for (int y = 0; y < this.height; y++) {
               buf.position(y * stride);

               for (int x = 0; x < this.width; x++) {
                  int argb = bufferedImage.getRGB(x, y);
                  buf.put((byte)(argb << 8 >> 24));
                  buf.put((byte)(argb << 16 >> 24));
                  buf.put((byte)(argb << 24 >> 24));
                  buf.put((byte)(argb >> 24));
               }
            }

            return;
         } catch (Exception e) {
            this.dispose();
            this.width = this.height = -1;
         }
      }

      try (
         InputStream is = new FileInputStream(path);
         BufferedInputStream bis = new BufferedInputStream(is);
      ) {
         PNGDecoder png = new PNGDecoder(bis, false);
         this.width = png.getWidth();
         this.height = png.getHeight();
         this.widthHw = ImageUtils.getNextPowerOfTwoHW(this.width);
         this.heightHw = ImageUtils.getNextPowerOfTwoHW(this.height);
         this.data = new MipMapLevel(this.widthHw, this.heightHw);
         ByteBuffer buf = this.data.getBuffer();
         buf.rewind();
         int stride = this.widthHw * 4;
         if (this.width != this.widthHw) {
            for (int x = this.width * 4; x < this.widthHw * 4; x++) {
               for (int y = 0; y < this.heightHw; y++) {
                  buf.put(x + y * stride, (byte)0);
               }
            }
         }

         if (this.height != this.heightHw) {
            for (int y = this.height; y < this.heightHw; y++) {
               for (int x = 0; x < this.width * 4; x++) {
                  buf.put(x + y * stride, (byte)0);
               }
            }
         }

         png.decode(this.data.getBuffer(), stride, png.getHeight(), Format.RGBA, 1229209940);
      } catch (Exception ex) {
         this.dispose();
         this.width = this.height = -1;
      }
   }

   public ImageData(int width, int height) {
      this.width = width;
      this.height = height;
      this.widthHw = ImageUtils.getNextPowerOfTwoHW(width);
      this.heightHw = ImageUtils.getNextPowerOfTwoHW(height);
      this.data = new MipMapLevel(this.widthHw, this.heightHw);
   }

   public ImageData(int width, int height, WrappedBuffer data) {
      this.width = width;
      this.height = height;
      this.widthHw = ImageUtils.getNextPowerOfTwoHW(width);
      this.heightHw = ImageUtils.getNextPowerOfTwoHW(height);
      this.data = new MipMapLevel(this.widthHw, this.heightHw, data);
   }

   public ImageData(ImageDataFrame frame) {
      boolean bFullCompositedFrame = true;
      this.width = frame.owner.width;
      this.height = frame.owner.height;
      this.widthHw = frame.widthHw;
      this.heightHw = frame.heightHw;
      this.data = frame.data;
   }

   public ImageData(InputStream b, boolean bDoMask) throws Exception {
      BufferedImage image = null;
      PNGDecoder png = new PNGDecoder(b, bDoMask);
      this.width = png.getWidth();
      this.height = png.getHeight();
      this.widthHw = ImageUtils.getNextPowerOfTwoHW(this.width);
      this.heightHw = ImageUtils.getNextPowerOfTwoHW(this.height);
      if (png.isAnimated()) {
         ImageDataFrame frame = new ImageDataFrame().set(this, png.getCurrentFrame());
         this.frames.add(frame);
         frame.data.rewind();
         png.decode(frame.data.getBuffer(), 4 * frame.widthHw, png.getHeight(), Format.RGBA, 1229209940);
         MipMapLevel compositeBuffer = new MipMapLevel(this.widthHw, this.heightHw);
         compositeBuffer.rewind();
         if (frame.apngFrame.disposeOp == 0) {
            frame.data.rewind();
            MemoryUtil.memCopy(frame.data.getBuffer(), compositeBuffer.getBuffer());
         }

         int numFrames = png.getNumFrames();

         for (int i = 1; i < numFrames; i++) {
            png.decodeStartOfNextFrame();
            frame = new ImageDataFrame().set(this, png.getCurrentFrame());
            this.frames.add(frame);
            frame.data.rewind();
            png.decodeFrame(compositeBuffer, frame, frame.data.getBuffer(), 4 * frame.widthHw, Format.RGBA);
         }

         compositeBuffer.dispose();
      } else {
         this.data = new MipMapLevel(this.widthHw, this.heightHw);
         this.data.rewind();
         png.decode(this.data.getBuffer(), 4 * this.widthHw, png.getHeight(), Format.RGBA, 1229209940);
      }

      if (bDoMask) {
         this.mask = png.mask;
      }
   }

   public static ImageData createSteamAvatar(long steamID) {
      WrappedBuffer data = DirectBufferAllocator.allocate(65536);
      int avatarWidth = SteamFriends.CreateSteamAvatar(steamID, data.getBuffer());
      if (avatarWidth <= 0) {
         return null;
      }

      int avatarHeight = data.getBuffer().position() / (avatarWidth * 4);
      data.getBuffer().flip();
      return new ImageData(avatarWidth, avatarHeight, data);
   }

   public MipMapLevel getData() {
      if (this.data == null) {
         this.data = new MipMapLevel(this.widthHw, this.heightHw, DirectBufferAllocator.allocate(67108864));
      }

      this.data.rewind();
      return this.data;
   }

   public void makeTransp(byte red, byte green, byte blue) {
      this.makeTransp(red, green, blue, (byte)0);
   }

   public void makeTransp(byte red, byte green, byte blue, byte alpha) {
      this.solid = false;
      ByteBuffer buf = this.data.getBuffer();
      buf.rewind();
      int step = this.widthHw * 4;

      for (int y = 0; y < this.heightHw; y++) {
         int position = buf.position();

         for (int x = 0; x < this.widthHw; x++) {
            int r = buf.get();
            int g = buf.get();
            int b = buf.get();
            if (r == red && g == green && b == blue) {
               buf.put(alpha);
            } else {
               buf.get();
            }

            if (x == this.width) {
               buf.position(position + step);
               break;
            }
         }

         if (y == this.height) {
            break;
         }
      }

      buf.rewind();
   }

   public void setData(BufferedImage image) {
      if (image != null) {
         this.setData(image.getData());
      }
   }

   public void setData(Raster rasterData) {
      if (rasterData == null) {
         DebugType.General.printStackTrace();
      } else {
         this.width = rasterData.getWidth();
         this.height = rasterData.getHeight();
         if (this.width <= this.widthHw && this.height <= this.heightHw) {
            int[] pixelData = rasterData.getPixels(0, 0, this.width, this.height, (int[])null);
            ByteBuffer buf = this.data.getBuffer();
            buf.rewind();
            int counter = 0;
            int position = buf.position();
            int step = this.widthHw * 4;

            for (int i = 0; i < pixelData.length; i++) {
               if (++counter > this.width) {
                  buf.position(position + step);
                  position = buf.position();
                  counter = 1;
               }

               buf.put((byte)pixelData[i]);
               buf.put((byte)pixelData[++i]);
               buf.put((byte)pixelData[++i]);
               buf.put((byte)pixelData[++i]);
            }

            buf.rewind();
            this.solid = false;
         } else {
            DebugType.General.printStackTrace();
         }
      }
   }

   private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
      this.data = new MipMapLevel(this.widthHw, this.heightHw);
      ByteBuffer buf = this.data.getBuffer();

      for (int i = 0; i < this.widthHw * this.heightHw; i++) {
         buf.put(s.readByte()).put(s.readByte()).put(s.readByte()).put(s.readByte());
      }

      buf.flip();
   }

   private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
      ByteBuffer buf = this.data.getBuffer();
      buf.rewind();

      for (int i = 0; i < this.widthHw * this.heightHw; i++) {
         s.writeByte(buf.get());
         s.writeByte(buf.get());
         s.writeByte(buf.get());
         s.writeByte(buf.get());
      }
   }

   public int getHeight() {
      return this.height;
   }

   public int getHeightHW() {
      return this.heightHw;
   }

   public boolean isSolid() {
      return this.solid;
   }

   public int getWidth() {
      return this.width;
   }

   public int getWidthHW() {
      return this.widthHw;
   }

   public int getMipMapCount() {
      if (this.data == null) {
         return 0;
      }

      if (this.mipMapCount < 0) {
         this.mipMapCount = calculateNumMips(this.widthHw, this.heightHw);
      }

      return this.mipMapCount;
   }

   public MipMapLevel getMipMapData(int idx) {
      if (this.data != null && !this.preMultipliedAlphaDone) {
         if (this.mipMaps == null) {
            this.generateMipMaps();
         }

         this.performPreMultipliedAlpha();
      }

      if (idx == 0) {
         return this.getData();
      }

      if (this.mipMaps == null) {
         this.generateMipMaps();
      }

      int subLevelIdx = idx - 1;
      MipMapLevel mipMap = this.mipMaps[subLevelIdx];
      mipMap.rewind();
      return mipMap;
   }

   public void initMipMaps() {
      int mipMapCount = this.getMipMapCount();
      int mipLevelStart = PZMath.min(0, mipMapCount - 1);
      int mipLevelEnd = mipMapCount;

      for (int mipLevel = mipLevelStart; mipLevel < mipLevelEnd; mipLevel++) {
         MipMapLevel var5 = this.getMipMapData(mipLevel);
      }
   }

   public void dispose() {
      pzopt.TexCompress.free(this); // pzopt: texCompress blocks not uploaded
      if (this.data != null) {
         this.data.dispose();
         this.data = null;
      }

      if (this.mipMaps != null) {
         for (int i = 0; i < this.mipMaps.length; i++) {
            this.mipMaps[i].dispose();
            this.mipMaps[i] = null;
         }

         this.mipMaps = null;
      }
   }

   private void generateMipMaps() {
      this.mipMapCount = calculateNumMips(this.widthHw, this.heightHw);
      int subLevels = this.mipMapCount - 1;
      this.mipMaps = new MipMapLevel[subLevels];
      MipMapLevel baseLevel = this.getData();
      int baseLevelW = this.widthHw;
      int baseLevelH = this.heightHw;
      MipMapLevel parentLevel = baseLevel;
      int subLevelW = getNextMipDimension(baseLevelW);
      int subLevelH = getNextMipDimension(baseLevelH);

      for (int i = 0; i < subLevels; i++) {
         MipMapLevel subLevel = new MipMapLevel(subLevelW, subLevelH);
         if (i < 2) {
            this.scaleMipLevelMaxAlpha(parentLevel, subLevel, i);
         } else {
            this.scaleMipLevelAverage(parentLevel, subLevel, i);
         }

         this.mipMaps[i] = subLevel;
         parentLevel = subLevel;
         subLevelW = getNextMipDimension(subLevelW);
         subLevelH = getNextMipDimension(subLevelH);
      }

      for (int i = 0; i < subLevels; i++) {
         this.performPreMultipliedAlpha(this.mipMaps[i]);
      }
   }

   private void scaleMipLevelMaxAlpha(MipMapLevel parentLevel, MipMapLevel subLevel, int levelNo) {
      // pzopt: row-based generation on byte[] (pzopt.MipMaps, Config mipmapArrays); the stock per-byte direct-buffer
      // pzopt: loop below is what C2 miscompiled in GitHub issue #2. Identical output (tests/pzopt/MipMapsTest).
      if (pzopt.MipMaps.enabled() && !DebugOptions.instance.isoSprite.worldMipmapColors.getValue()) {
         subLevel.getBuffer().rewind(); // pzopt
         pzopt.MipMaps.scaleMaxAlpha(parentLevel.getBuffer(), parentLevel.width, parentLevel.height, subLevel.getBuffer(), subLevel.width, subLevel.height, this.preserveTransparentColor); // pzopt
         return; // pzopt
      }

      ImageData.L_generateMipMaps generateMipMaps = TL_generateMipMaps.get();
      ByteBuffer subLevelBuff = subLevel.getBuffer();
      subLevelBuff.rewind();
      int parentLevelW = parentLevel.width;
      int parentLevelH = parentLevel.height;
      ByteBuffer parentLevelBuff = parentLevel.getBuffer();
      int subLevelW = subLevel.width;
      int subLevelH = subLevel.height;

      for (int y = 0; y < subLevelH; y++) {
         for (int x = 0; x < subLevelW; x++) {
            int[] pixelBytes = generateMipMaps.pixelBytes;
            int[] originalPixel = generateMipMaps.originalPixel;
            int[] resultPixelBytes = generateMipMaps.resultPixelBytes;
            getPixelClamped(parentLevelBuff, parentLevelW, parentLevelH, x * 2, y * 2, originalPixel);
            int numSamples;
            if (!this.preserveTransparentColor && originalPixel[3] <= 0) {
               PZArrayUtil.arraySet(resultPixelBytes, 0);
               numSamples = 0;
            } else {
               PZArrayUtil.arrayCopy(resultPixelBytes, originalPixel, 0, 4);
               numSamples = 1;
            }

            numSamples += this.sampleNeighborPixelDiscard(parentLevelBuff, parentLevelW, parentLevelH, x * 2 + 1, y * 2, pixelBytes, resultPixelBytes);
            numSamples += this.sampleNeighborPixelDiscard(parentLevelBuff, parentLevelW, parentLevelH, x * 2, y * 2 + 1, pixelBytes, resultPixelBytes);
            numSamples += this.sampleNeighborPixelDiscard(parentLevelBuff, parentLevelW, parentLevelH, x * 2 + 1, y * 2 + 1, pixelBytes, resultPixelBytes);
            if (numSamples > 0) {
               resultPixelBytes[0] /= numSamples;
               resultPixelBytes[1] /= numSamples;
               resultPixelBytes[2] /= numSamples;
               resultPixelBytes[3] /= numSamples;
               if (DebugOptions.instance.isoSprite.worldMipmapColors.getValue()) {
                  setMipmapDebugColors(levelNo, resultPixelBytes);
               }
            }

            setPixel(subLevelBuff, subLevelW, subLevelH, x, y, resultPixelBytes);
         }
      }
   }

   private void scaleMipLevelAverage(MipMapLevel parentLevel, MipMapLevel subLevel, int levelNo) {
      // pzopt: same row-based path as scaleMipLevelMaxAlpha (pzopt.MipMaps, Config mipmapArrays)
      if (pzopt.MipMaps.enabled() && !DebugOptions.instance.isoSprite.worldMipmapColors.getValue()) {
         subLevel.getBuffer().rewind(); // pzopt
         pzopt.MipMaps.scaleAverage(parentLevel.getBuffer(), parentLevel.width, parentLevel.height, subLevel.getBuffer(), subLevel.width, subLevel.height); // pzopt
         return; // pzopt
      }

      ImageData.L_generateMipMaps generateMipMaps = TL_generateMipMaps.get();
      ByteBuffer subLevelBuff = subLevel.getBuffer();
      subLevelBuff.rewind();
      int parentLevelW = parentLevel.width;
      int parentLevelH = parentLevel.height;
      ByteBuffer parentLevelBuff = parentLevel.getBuffer();
      int subLevelW = subLevel.width;
      int subLevelH = subLevel.height;

      for (int y = 0; y < subLevelH; y++) {
         for (int x = 0; x < subLevelW; x++) {
            int[] resultPixelBytes = generateMipMaps.resultPixelBytes;
            int numSamples = 1;
            getPixelClamped(parentLevelBuff, parentLevelW, parentLevelH, x * 2, y * 2, resultPixelBytes);
            numSamples += getPixelDiscard(parentLevelBuff, parentLevelW, parentLevelH, x * 2 + 1, y * 2, resultPixelBytes);
            numSamples += getPixelDiscard(parentLevelBuff, parentLevelW, parentLevelH, x * 2, y * 2 + 1, resultPixelBytes);
            numSamples += getPixelDiscard(parentLevelBuff, parentLevelW, parentLevelH, x * 2 + 1, y * 2 + 1, resultPixelBytes);
            resultPixelBytes[0] /= numSamples;
            resultPixelBytes[1] /= numSamples;
            resultPixelBytes[2] /= numSamples;
            resultPixelBytes[3] /= numSamples;
            if (resultPixelBytes[3] != 0 && DebugOptions.instance.isoSprite.worldMipmapColors.getValue()) {
               setMipmapDebugColors(levelNo, resultPixelBytes);
            }

            setPixel(subLevelBuff, subLevelW, subLevelH, x, y, resultPixelBytes);
         }
      }
   }

   public static int calculateNumMips(int widthHW, int heightHW) {
      int widthMips = calculateNumMips(widthHW);
      int heightMips = calculateNumMips(heightHW);
      return PZMath.max(widthMips, heightMips);
   }

   private static int calculateNumMips(int dim) {
      int numMips = 0;

      for (int current = dim; current > 0; numMips++) {
         current >>= 1;
      }

      return numMips;
   }

   private void performPreMultipliedAlpha() {
      MipMapLevel data = this.data;
      if (data != null && data.data != null) {
         this.performPreMultipliedAlpha(data);
         this.preMultipliedAlphaDone = true;
      }
   }

   private void performPreMultipliedAlpha(MipMapLevel data) {
      // pzopt: row-based premultiply (pzopt.MipMaps, Config mipmapArrays); runs once per level on the same pool threads
      if (pzopt.MipMaps.enabled()) {
         pzopt.MipMaps.premultiplyAlpha(data.getBuffer(), data.width, data.height); // pzopt
         return; // pzopt
      }

      ImageData.L_performAlphaPadding performAlphaPadding = TL_performAlphaPadding.get();
      ByteBuffer dataBuff = data.getBuffer();
      int width = data.width;
      int height = data.height;

      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            int pixelIdx = (y * width + x) * 4;
            int[] pixelRGBA = getPixelClamped(dataBuff, width, height, x, y, performAlphaPadding.pixelRgba);
            pixelRGBA[0] = (int)(pixelRGBA[0] * pixelRGBA[3] / 255.0F);
            pixelRGBA[1] = (int)(pixelRGBA[1] * pixelRGBA[3] / 255.0F);
            pixelRGBA[2] = (int)(pixelRGBA[2] * pixelRGBA[3] / 255.0F);
            setPixel(dataBuff, width, height, x, y, pixelRGBA);
         }
      }
   }

   private int sampleNeighborPixelDiscard(ByteBuffer dataBuff, int width, int height, int x, int y, int[] neighborPixel, int[] result) {
      if (x >= 0 && x < width && y >= 0 && y < height) {
         getPixelClamped(dataBuff, width, height, x, y, neighborPixel);
         if (neighborPixel[3] > 0) {
            result[0] += neighborPixel[0];
            result[1] += neighborPixel[1];
            result[2] += neighborPixel[2];
            result[3] += neighborPixel[3];
            return 1;
         } else {
            return 0;
         }
      } else {
         return 0;
      }
   }

   public static int getPixelDiscard(ByteBuffer dataBuff, int width, int height, int x, int y, int[] result) {
      if (x >= 0 && x < width && y >= 0 && y < height) {
         int pixelIdx = (x + y * width) * 4;
         result[0] += dataBuff.get(pixelIdx) & 255;
         result[1] += dataBuff.get(pixelIdx + 1) & 255;
         result[2] += dataBuff.get(pixelIdx + 2) & 255;
         result[3] += dataBuff.get(pixelIdx + 3) & 255;
         return 1;
      } else {
         return 0;
      }
   }

   public static int[] getPixelClamped(ByteBuffer dataBuff, int width, int height, int x, int y, int[] result) {
      x = PZMath.clamp(x, 0, width - 1);
      y = PZMath.clamp(y, 0, height - 1);
      int pixelIdx = (x + y * width) * 4;
      result[0] = dataBuff.get(pixelIdx) & 255;
      result[1] = dataBuff.get(pixelIdx + 1) & 255;
      result[2] = dataBuff.get(pixelIdx + 2) & 255;
      result[3] = dataBuff.get(pixelIdx + 3) & 255;
      return result;
   }

   public static void setPixel(ByteBuffer dataBuff, int width, int height, int x, int y, int[] pixelRGBA) {
      int pixelIdx = (x + y * width) * 4;
      dataBuff.put(pixelIdx, (byte)(pixelRGBA[0] & 0xFF));
      dataBuff.put(pixelIdx + 1, (byte)(pixelRGBA[1] & 0xFF));
      dataBuff.put(pixelIdx + 2, (byte)(pixelRGBA[2] & 0xFF));
      dataBuff.put(pixelIdx + 3, (byte)(pixelRGBA[3] & 0xFF));
   }

   public static int getNextMipDimension(int dim) {
      if (dim > 1) {
         dim >>= 1;
      }

      return dim;
   }

   private static void setMipmapDebugColors(int levelNo, int[] resultPixelBytes) {
      switch (levelNo) {
         case 0:
            resultPixelBytes[0] = 255;
            resultPixelBytes[1] = 0;
            resultPixelBytes[2] = 0;
            break;
         case 1:
            resultPixelBytes[0] = 0;
            resultPixelBytes[1] = 255;
            resultPixelBytes[2] = 0;
            break;
         case 2:
            resultPixelBytes[0] = 0;
            resultPixelBytes[1] = 0;
            resultPixelBytes[2] = 255;
            break;
         case 3:
            resultPixelBytes[0] = 255;
            resultPixelBytes[1] = 255;
            resultPixelBytes[2] = 0;
            break;
         case 4:
            resultPixelBytes[0] = 255;
            resultPixelBytes[1] = 0;
            resultPixelBytes[2] = 255;
            break;
         case 5:
            resultPixelBytes[0] = 0;
            resultPixelBytes[1] = 0;
            resultPixelBytes[2] = 0;
            break;
         case 6:
            resultPixelBytes[0] = 255;
            resultPixelBytes[1] = 255;
            resultPixelBytes[2] = 255;
            break;
         case 7:
            resultPixelBytes[0] = 128;
            resultPixelBytes[1] = 128;
            resultPixelBytes[2] = 128;
      }
   }

   private static final class L_generateMipMaps {
      final int[] pixelBytes = new int[4];
      final int[] originalPixel = new int[4];
      final int[] resultPixelBytes = new int[4];
   }

   static final class L_performAlphaPadding {
      final int[] pixelRgba = new int[4];
      final int[] newPixelRgba = new int[4];
      final int[] pixelRgbaNeighbor = new int[4];
   }
}
