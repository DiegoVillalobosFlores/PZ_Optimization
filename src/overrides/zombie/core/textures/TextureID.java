package zombie.core.textures;

import java.io.BufferedInputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import org.lwjgl.opengl.GL11;
import org.lwjglx.BufferUtils;
import org.lwjglx.opengl.Display;
import zombie.IndieGL;
import zombie.asset.Asset;
import zombie.asset.AssetManager;
import zombie.asset.AssetPath;
import zombie.asset.AssetType;
import zombie.asset.Asset.State;
import zombie.asset.AssetManager.AssetParams;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.PZGLUtil;
import zombie.core.opengl.RenderThread;
import zombie.core.utils.BooleanGrid;
import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.WrappedBuffer;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.fileSystem.FileSystem.SubTexture;
import zombie.interfaces.IDestroyable;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;

public final class TextureID extends Asset implements IDestroyable, Serializable {
   private static final long serialVersionUID = 4409253583065563738L;
   public static long totalGraphicMemory;
   public static boolean useFiltering;
   public static boolean useCompression = true;
   public static boolean useCompressionOption = true;
   public static float totalMemUsed;
   private static final boolean FREE_MEMORY = true;
   private static final HashMap<Integer, String> TextureIDMap = new HashMap<>();
   protected String pathFileName;
   protected boolean solid;
   protected int width;
   protected int widthHw;
   protected int height;
   protected int heightHw;
   protected transient ImageData data;
   protected transient int id = -1;
   private int glMagFilter = -1;
   private int glMinFilter = -1;
   ArrayList<AlphaColorIndex> alphaList;
   int referenceCount;
   BooleanGrid mask;
   protected int flags = 0;
   public TextureID.TextureIDAssetParams assetParams;
   private int format = 6408;
   private int internalFormat = 6408;
   public static final IntBuffer deleteTextureIDS = BufferUtils.createIntBuffer(20);
   public static final AssetType ASSET_TYPE = new AssetType("TextureID");

   public TextureID(AssetPath path, AssetManager manager, TextureID.TextureIDAssetParams params) {
      super(path, manager);
      this.assetParams = params;
      this.flags = params == null ? 0 : this.assetParams.flags;
   }

   protected TextureID() {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = null;
      this.onCreated(State.READY);
   }

   public TextureID(int width, int height, int flags) {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = new TextureID.TextureIDAssetParams();
      this.assetParams.flags = flags;
      if ((flags & 16) == 0 && (flags & 512) == 0) {
         this.data = new ImageData(width, height);
      } else {
         if ((flags & 4) != 0) {
            DebugType.General.warn("FBO incompatible with COMPRESS");
            this.assetParams.flags &= -5;
         }

         this.data = new ImageData(width, height, null);
      }

      this.width = this.data.getWidth();
      this.height = this.data.getHeight();
      this.widthHw = this.data.getWidthHW();
      this.heightHw = this.data.getHeightHW();
      this.solid = this.data.isSolid();
      RenderThread.queueInvokeOnRenderContext(() -> this.createTexture(false));
      this.onCreated(State.READY);
   }

   public TextureID(int width, int height, int flags, int format, int internalFormat) {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = new TextureID.TextureIDAssetParams();
      this.assetParams.flags = flags;
      if ((flags & 16) == 0 && (flags & 512) == 0) {
         this.data = new ImageData(width, height);
      } else {
         if ((flags & 4) != 0) {
            DebugType.General.warn("FBO incompatible with COMPRESS");
            this.assetParams.flags &= -5;
         }

         this.data = new ImageData(width, height, null);
      }

      this.width = this.data.getWidth();
      this.height = this.data.getHeight();
      this.widthHw = this.data.getWidthHW();
      this.heightHw = this.data.getHeightHW();
      this.solid = this.data.isSolid();
      this.format = format;
      this.internalFormat = internalFormat;
      RenderThread.queueInvokeOnRenderContext(() -> this.createTexture(false));
      this.onCreated(State.READY);
   }

   public TextureID(int width, int height, int flags, boolean defered) {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = new TextureID.TextureIDAssetParams();
      this.assetParams.flags = flags;
      if ((flags & 16) == 0 && (flags & 512) == 0) {
         this.data = new ImageData(width, height);
      } else {
         if ((flags & 4) != 0) {
            DebugType.General.warn("FBO incompatible with COMPRESS");
            this.assetParams.flags &= -5;
         }

         this.data = new ImageData(width, height, null);
      }

      this.width = this.data.getWidth();
      this.height = this.data.getHeight();
      this.widthHw = this.data.getWidthHW();
      this.heightHw = this.data.getHeightHW();
      this.solid = this.data.isSolid();
      this.createTexture(false);
      this.onCreated(State.READY);
   }

   public TextureID(ImageData image) {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = null;
      this.data = image;
      RenderThread.invokeOnRenderContext(this::createTexture);
      this.onCreated(State.READY);
   }

   public TextureID(String path, int red, int green, int blue) throws Exception {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = null;
      if (path.startsWith("/")) {
         path = path.substring(1);
      }

      int index;
      while ((index = path.indexOf("\\")) != -1) {
         path = path.substring(0, index) + "/" + path.substring(index + 1);
      }

      (this.data = new ImageData(path)).makeTransp((byte)red, (byte)green, (byte)blue);
      if (this.alphaList == null) {
         this.alphaList = new ArrayList<>();
      }

      this.alphaList.add(new AlphaColorIndex(red, green, blue, 0));
      this.pathFileName = path;
      RenderThread.invokeOnRenderContext(this::createTexture);
      this.onCreated(State.READY);
   }

   public TextureID(String path) throws Exception {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = null;
      this.data = new ImageData(path);
      if (this.data.getHeight() != -1) {
         this.pathFileName = path;
         RenderThread.invokeOnRenderContext(this::createTexture);
         this.onCreated(State.READY);
      }
   }

   public TextureID(BufferedInputStream b, String path, boolean bDoMask) throws Exception {
      super(null, TextureIDAssetManager.instance);
      this.assetParams = null;
      this.data = new ImageData(b, bDoMask);
      if (bDoMask) {
         this.mask = this.data.mask;
         this.data.mask = null;
      }

      this.pathFileName = path;
      RenderThread.invokeOnRenderContext(this::createTexture);
      this.onCreated(State.READY);
   }

   public static TextureID createSteamAvatar(long steamID) {
      ImageData imageData = ImageData.createSteamAvatar(steamID);
      return imageData == null ? null : new TextureID(imageData);
   }

   public int getID() {
      return this.id;
   }

   public boolean bind() {
      if (this.id == -1 && this.data == null) {
         Texture.getErrorTexture().bind();
         return true;
      } else {
         this.debugBoundTexture();
         return this.id != -1 && this.id == Texture.lastTextureID ? false : this.bindalways();
      }
   }

   public boolean bindalways() {
      this.bindInternal();
      return true;
   }

   private void bindInternal() {
      if (this.id == -1) {
         this.generateHwId(this.data != null && this.data.data != null);
      }

      this.assignFilteringFlags();
      Texture.lastlastTextureID = Texture.lastTextureID;
      Texture.lastTextureID = this.id;
      Texture.bindCount++;
   }

   private void debugBoundTexture() {
      if (DebugOptions.instance.checks.boundTextures.getValue() && Texture.lastTextureID != -1) {
         int unit = GL11.glGetInteger(34016);
         if (unit == 33984) {
            int current = GL11.glGetInteger(32873);
            if (current != Texture.lastTextureID) {
               String currentName = null;
               String lastName = null;

               for (Asset asset : TextureIDAssetManager.instance.getAssetTable().values()) {
                  TextureID textureID = (TextureID)asset;
                  if (textureID.id == Texture.lastTextureID) {
                     lastName = textureID.getPath().getPath();
                  }

                  if (textureID.id == current) {
                     currentName = textureID.getPath().getPath();
                  }
               }

               DebugType.General
                  .error(
                     "Texture.lastTextureID %d name=%s != GL_TEXTURE_BINDING_2D %d name=%s",
                     new Object[]{Texture.lastTextureID, lastName, current, currentName}
                  );
            }
         }
      }
   }

   public void destroy() {
      assert Thread.currentThread() == RenderThread.renderThread;
      if (this.id != -1) {
         if (deleteTextureIDS.position() == deleteTextureIDS.capacity()) {
            deleteTextureIDS.flip();
            GL11.glDeleteTextures(deleteTextureIDS);
            deleteTextureIDS.clear();
         }

         deleteTextureIDS.put(this.id);
         this.id = -1;
      }
   }

   public void freeMemory() {
      this.data = null;
   }

   public WrappedBuffer getData() {
      this.bind();
      WrappedBuffer bb = DirectBufferAllocator.allocate(this.heightHw * this.widthHw * 4);
      GL11.glGetTexImage(3553, 0, 6408, 5121, bb.getBuffer());
      GL11.glBindTexture(3553, Texture.lastTextureID = 0); // pzopt: decompiler fix, the jar binds the assigned value (dup), not a second constant
      return bb;
   }

   public void setData(ByteBuffer bdata) {
      if (bdata == null) {
         this.freeMemory();
      } else {
         this.bind();
         GL11.glTexSubImage2D(3553, 0, 0, 0, this.widthHw, this.heightHw, 6408, 5121, bdata);
         if (this.data != null) {
            MipMapLevel wb = this.data.getData();
            ByteBuffer buf = wb.getBuffer();
            bdata.flip();
            buf.clear();
            buf.put(bdata);
            buf.flip();
         }
      }
   }

   public ImageData getImageData() {
      return this.data;
   }

   public void setImageData(ImageData data) {
      data = this.limitMaxSize(data);
      this.data = data;
      this.width = data.getWidth();
      this.height = data.getHeight();
      this.widthHw = data.getWidthHW();
      this.heightHw = data.getHeightHW();
      if (data.mask != null) {
         this.mask = data.mask;
         data.mask = null;
      }

      RenderThread.queueInvokeOnRenderContext(this::createTexture);
   }

   private ImageData limitMaxSize(ImageData data) {
      if (this.assetParams == null) {
         return data;
      }

      int flags = this.assetParams.flags;
      int allFlags = 384;
      if ((flags & 384) == 0) {
         return data;
      }

      int maxSize = Core.getInstance().getMaxTextureSizeFromFlags(flags);
      if (data.getWidth() <= maxSize && data.getHeight() <= maxSize) {
         return data;
      }

      data.preserveTransparentColor = true;
      MipMapLevel mml = data.getData();
      int level = 0;

      while (mml.width > maxSize || mml.height > maxSize) {
         mml = data.getMipMapData(++level);
      }

      WrappedBuffer wrappedBuffer = DirectBufferAllocator.allocate(mml.getBuffer().capacity());
      mml.getBuffer().rewind();
      wrappedBuffer.getBuffer().put(mml.getBuffer());
      wrappedBuffer.getBuffer().rewind();
      ImageData newData = new ImageData(data.getWidth() >> level, data.getHeight() >> level, wrappedBuffer);
      newData.alphaPaddingDone = true;
      data.dispose();
      return newData;
   }

   public String getPathFileName() {
      return this.pathFileName;
   }

   public boolean isDestroyed() {
      return this.id == -1;
   }

   public boolean isSolid() {
      return this.solid;
   }

   private void createTexture() {
      if (this.data != null) {
         this.createTexture(true);
      }
   }

   private void createTexture(boolean setPixels) {
      if (this.id == -1) {
         this.width = this.data.getWidth();
         this.height = this.data.getHeight();
         this.widthHw = this.data.getWidthHW();
         this.heightHw = this.data.getHeightHW();
         this.solid = this.data.isSolid();
         this.generateHwId(setPixels);
      }
   }

   private void generateHwId(boolean setPixels) {
      this.id = pzopt.GlNames.texture(); // pzopt: glNoSync, a pooled name instead of a driver round trip
      Texture.totalTextureID++;
      GL11.glBindTexture(3553, Texture.lastTextureID = this.id);
      SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      int flags;
      if (this.assetParams == null) {
         flags = useCompressionOption ? 4 : 0;
      } else {
         flags = this.assetParams.flags;
      }

      boolean bFilterMinNearest = (flags & 1) != 0;
      boolean bFilterMagNearest = (flags & 2) != 0;
      boolean filterFbo = (flags & 16) != 0;
      boolean bFilterMipMaps = (flags & 64) != 0 && !filterFbo && setPixels;
      boolean bUseCompression = (flags & 4) != 0;
      int type = 5121;
      if (bUseCompression && this.internalFormat == 6408 && Display.capabilities.GL_ARB_texture_compression) {
         this.internalFormat = 34030;
      }

      if ((flags & 512) != 0) {
         this.internalFormat = 33189;
         this.glMagFilter = 9728;
         this.glMinFilter = 9728;
         this.format = 6402;
         type = 5123;
      } else {
         this.glMagFilter = bFilterMagNearest ? 9728 : 9729;
         this.glMinFilter = bFilterMipMaps ? 9987 : (bFilterMinNearest ? 9728 : 9729);
      }

      GL11.glTexParameteri(3553, 10241, this.glMinFilter);
      GL11.glTexParameteri(3553, 10240, this.glMagFilter);
      if ((flags & 32) != 0) {
         GL11.glTexParameteri(3553, 10242, 33071);
         GL11.glTexParameteri(3553, 10243, 33071);
      } else {
         GL11.glTexParameteri(3553, 10242, 10497);
         GL11.glTexParameteri(3553, 10243, 10497);
      }

      if (setPixels) {
         if (bFilterMipMaps) {
            PZGLUtil.checkGLErrorThrowTexture("TextureID.mipMaps.start", new Object[0]);
            int mipMapCount = this.data.getMipMapCount();
            int mipLevelStart = PZMath.min(0, mipMapCount - 1);
            int mipLevelEnd = mipMapCount;

            for (int mipLevel = mipLevelStart; mipLevel < mipLevelEnd; mipLevel++) {
               MipMapLevel mipMap = this.data.getMipMapData(mipLevel);
               int mipWidth = mipMap.width;
               int mipHeight = mipMap.height;
               totalMemUsed = totalMemUsed + mipMap.getDataSize();
               GL11.glTexImage2D(3553, mipLevel - mipLevelStart, this.internalFormat, mipWidth, mipHeight, 0, 6408, 5121, mipMap.getBuffer());
               PZGLUtil.checkGLErrorThrowTexture("TextureID.mipMaps[%d].end", new Object[]{mipLevel});
            }

            PZGLUtil.checkGLErrorThrowTexture("TextureID.mipMaps.end", new Object[0]);
         } else {
            PZGLUtil.checkGLErrorThrowTexture("TextureID.noMips.start", new Object[0]);
            totalMemUsed = totalMemUsed + this.widthHw * this.heightHw * 4;
            GL11.glTexImage2D(3553, 0, this.internalFormat, this.widthHw, this.heightHw, 0, 6408, 5121, this.data.getData().getBuffer());
            PZGLUtil.checkGLErrorThrowTexture("TextureID.noMips.end", new Object[0]);
         }
      } else {
         GL11.glTexImage2D(3553, 0, this.internalFormat, this.widthHw, this.heightHw, 0, this.format, type, (ByteBuffer)null);
         totalMemUsed = totalMemUsed + this.widthHw * this.heightHw * 4;
      }

      if (this.data != null) {
         this.data.dispose();
      }

      this.data = null;
      if (this.assetParams != null) {
         this.assetParams.subTexture = null;
         this.assetParams = null;
      }

      TextureIDMap.put(this.id, this.pathFileName);
      PZGLUtil.checkGLErrorThrowTexture("generateHwId id:%d pathFileName:%s", new Object[]{this.id, this.pathFileName});
   }

   private void assignFilteringFlags() {
      GL11.glBindTexture(3553, this.id);
      if (this.width == 1 && this.height == 1) {
         GL11.glTexParameteri(3553, 10241, 9728);
         GL11.glTexParameteri(3553, 10240, 9728);
      } else {
         if (PerformanceSettings.fboRenderChunk
            && Core.getInstance().getOffscreenBuffer() != null
            && this == ((Texture)Core.getInstance().getOffscreenBuffer().getTexture()).dataid) {
            this.glMinFilter = Core.getInstance().getScreenFilter();
            this.glMagFilter = this.glMinFilter;
         }

         GL11.glTexParameteri(3553, 10241, this.glMinFilter);
         GL11.glTexParameteri(3553, 10240, this.glMagFilter);
         if ((this.flags & 64) != 0
            && DebugOptions.instance.isoSprite.nearestMagFilterAtMinZoom.getValue()
            && this.isMinZoomLevel()
            && this.glMagFilter != 9728) {
            GL11.glTexParameteri(3553, 10240, 9728);
         }

         if (PerformanceSettings.fboRenderChunk
            && (!DebugOptions.instance.fboRenderChunk.mipMaps.getValue() || FBORenderChunkManager.instance.renderThreadCurrent != null)
            && (this.flags & 64) != 0) {
            GL11.glTexParameteri(3553, 10241, 9728);
            GL11.glTexParameteri(3553, 10240, 9728);
         }

         if (DebugOptions.instance.isoSprite.forceLinearMagFilter.getValue() && this.glMagFilter != 9729) {
            GL11.glTexParameteri(3553, 10240, 9729);
         }

         if (DebugOptions.instance.isoSprite.forceNearestMagFilter.getValue() && this.glMagFilter != 9728) {
            GL11.glTexParameteri(3553, 10240, 9728);
         }

         if (DebugOptions.instance.isoSprite.forceNearestMipMapping.getValue() && this.glMinFilter == 9987) {
            GL11.glTexParameteri(3553, 10241, 9986);
         }

         if (DebugOptions.instance.isoSprite.textureWrapClampToEdge.getValue()) {
            GL11.glTexParameteri(3553, 10242, 33071);
            GL11.glTexParameteri(3553, 10243, 33071);
         }

         if (DebugOptions.instance.isoSprite.textureWrapRepeat.getValue()) {
            GL11.glTexParameteri(3553, 10242, 10497);
            GL11.glTexParameteri(3553, 10243, 10497);
         }

         PZGLUtil.checkGLErrorThrowTexture("assignFilteringFlags id:%d pathFileName:%s", new Object[]{this.id, this.pathFileName});
      }
   }

   public void setMagFilter(int filter) {
      this.glMagFilter = filter;
   }

   public void setMinFilter(int filter) {
      this.glMinFilter = filter;
   }

   int getMinFilter() {
      return this.glMinFilter;
   }

   int getMagFilter() {
      return this.glMagFilter;
   }

   public boolean hasMipMaps() {
      return this.glMinFilter == 9987;
   }

   private boolean isMaxZoomLevel() {
      return IndieGL.isMaxZoomLevel();
   }

   private boolean isMinZoomLevel() {
      return IndieGL.isMinZoomLevel();
   }

   public void setAssetParams(AssetParams params) {
      this.assetParams = (TextureID.TextureIDAssetParams)params;
      this.flags = this.assetParams == null ? 0 : this.assetParams.flags;
   }

   public AssetType getType() {
      return ASSET_TYPE;
   }

   public static final class TextureIDAssetParams extends AssetParams {
      SubTexture subTexture;
      int flags = 0;
   }
}
