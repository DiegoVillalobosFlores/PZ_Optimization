package zombie.core.textures;

import gnu.trove.list.array.TIntArrayList;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30C;
import zombie.core.opengl.PZGLUtil;
import zombie.core.opengl.RenderThread;
import zombie.debug.DebugType;
import zombie.interfaces.ITexture;

public final class TextureFBO {
   private static boolean checked;
   private static boolean fboSupported;
   private static IGLFramebufferObject funcs;
   public static int lastID;
   private static final TIntArrayList stack = new TIntArrayList();
   private int id;
   private ITexture texture;
   private ITexture depthTexture;
   private int depth;
   private int width;
   private int height;

   public void swapTexture(ITexture newTex) {
      assert lastID == this.id;
      if (newTex != null && newTex != this.texture) {
         if (newTex.getWidth() == this.width && newTex.getHeight() == this.height) {
            if (newTex.getID() == -1) {
               newTex.bind();
            }

            IGLFramebufferObject funcs = getFuncs();
            funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_COLOR_ATTACHMENT0(), 3553, newTex.getID(), 0);
            this.texture = newTex;
         }
      }
   }

   public void swapTextureAndDepth(ITexture newTex, ITexture newDepth) {
      assert lastID == this.id;
      IGLFramebufferObject funcs = getFuncs();
      if (newTex != null && newTex != this.texture && newTex.getWidth() == this.width && newTex.getHeight() == this.height) {
         if (newTex.getID() == -1) {
            newTex.bind();
         }

         funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_COLOR_ATTACHMENT0(), 3553, newTex.getID(), 0);
         this.texture = newTex;
      }

      if (this.depthTexture != null
         && newDepth != null
         && newDepth != this.depthTexture
         && newDepth.getWidth() == this.width
         && newDepth.getHeight() == this.height) {
         if (newDepth.getID() == -1) {
            newDepth.bind();
         }

         funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_DEPTH_ATTACHMENT(), 3553, newDepth.getID(), 0);
         this.depthTexture = newDepth;
      }
   }

   public TextureFBO(ITexture destination) {
      this(destination, true);
   }

   public TextureFBO(ITexture destination, boolean bUseStencil) {
      RenderThread.invokeOnRenderContext(destination, bUseStencil, this::init);
   }

   public TextureFBO(ITexture destination, ITexture depth, boolean bUseStencil) {
      RenderThread.invokeOnRenderContext(destination, depth, bUseStencil, this::init);
   }

   private void init(ITexture destination, boolean bStencilBuffer) {
      int curID = lastID;

      try {
         this.initInternal(destination, null, bStencilBuffer);
      } finally {
         IGLFramebufferObject funcs = getFuncs();
         int var10001 = funcs.GL_FRAMEBUFFER();
         lastID = curID;
         funcs.glBindFramebuffer(var10001, curID);
      }
   }

   private void init(ITexture destination, ITexture depthTex, boolean bStencilBuffer) {
      int curID = lastID;

      try {
         this.initInternal(destination, depthTex, bStencilBuffer);
      } finally {
         IGLFramebufferObject funcs = getFuncs();
         int var10001 = funcs.GL_FRAMEBUFFER();
         lastID = curID;
         funcs.glBindFramebuffer(var10001, curID);
      }
   }

   public void attach(ITexture tex, int attachment) {
      assert lastID == this.id;
      IGLFramebufferObject funcs = getFuncs();
      funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), attachment, 3553, tex.getID(), 0);
      if (attachment == 36064) {
         this.texture = tex;
         this.width = this.texture.getWidth();
         this.height = this.texture.getHeight();
      }

      if (attachment == 36096) {
         this.depthTexture = tex;
      }
   }

   public static IGLFramebufferObject getFuncs() {
      if (funcs == null) {
         checkFBOSupport();
      }

      return funcs;
   }

   public void blitDepth(float x, float y, float w, float h) {
      GL30C.glBindFramebuffer(36008, this.id);
      GL30C.glBindFramebuffer(36009, lastID);
      GL30C.glBlitFramebuffer(0, 0, this.width, this.height, (int)x, (int)y, (int)w, (int)h, 256, 9729);
      GL30C.glBindFramebuffer(36160, lastID);
   }

   private void initInternal(ITexture destination, ITexture depthTex, boolean bStencilBuffer) {
      IGLFramebufferObject funcs = getFuncs();

      try {
         PZGLUtil.checkGLErrorThrow("Enter.", new Object[0]);
         ITexture mainTexture = destination != null ? destination : depthTex;
         if (mainTexture == null) {
            throw new NullPointerException("Could not create FBO. Texture is null.");
         }

         this.texture = destination;
         this.depthTexture = depthTex;
         this.width = mainTexture.getWidth();
         this.height = mainTexture.getHeight();
         if (!checkFBOSupport()) {
            throw new RuntimeException("Could not create FBO. FBO's not supported.");
         }

         int pzoptPooled = pzopt.GlNames.framebuffer(funcs instanceof GLFramebufferObject30); // pzopt: glNoSync, a pooled name
         this.id = pzoptPooled > 0 ? pzoptPooled : funcs.glGenFramebuffers(); // pzopt: glNoSync
         PZGLUtil.checkGLErrorThrow("glGenFrameBuffers", new Object[0]);
         funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), this.id);
         PZGLUtil.checkGLErrorThrow("glBindFramebuffer(%d)", new Object[]{this.id});
         if (this.texture != null) {
            this.texture.bind();
            PZGLUtil.checkGLErrorThrow("Binding texture. %s", new Object[]{this.texture});
            GL11.glTexImage2D(3553, 0, 6408, this.texture.getWidthHW(), this.texture.getHeightHW(), 0, 6408, 5121, (IntBuffer)null);
            PZGLUtil.checkGLErrorThrow("glTexImage2D(width: %d, height: %d)", new Object[]{this.texture.getWidthHW(), this.texture.getHeightHW()});
            GL11.glTexParameteri(3553, 10242, 33071);
            GL11.glTexParameteri(3553, 10243, 33071);
            GL11.glTexParameteri(3553, 10240, 9729);
            GL11.glTexParameteri(3553, 10241, 9729);
            GL11.glBindTexture(3553, Texture.lastTextureID = 0); // pzopt: decompiler fix, as the jar (dup of the assigned value)
            funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_COLOR_ATTACHMENT0(), 3553, this.texture.getID(), 0);
            PZGLUtil.checkGLErrorThrow("glFramebufferTexture2D texture: %s", new Object[]{this.texture});
         }

         if (depthTex == null) {
            this.depth = funcs.glGenRenderbuffers();
            PZGLUtil.checkGLErrorThrow("glGenRenderbuffers", new Object[0]);
            funcs.glBindRenderbuffer(funcs.GL_RENDERBUFFER(), this.depth);
            PZGLUtil.checkGLErrorThrow("glBindRenderbuffer depth: %d", new Object[]{this.depth});
         }

         if (bStencilBuffer) {
            funcs.glRenderbufferStorage(funcs.GL_RENDERBUFFER(), funcs.GL_DEPTH24_STENCIL8(), this.texture.getWidthHW(), this.texture.getHeightHW());
            PZGLUtil.checkGLErrorThrow("glRenderbufferStorage(width: %d, height: %d)", new Object[]{this.texture.getWidthHW(), this.texture.getHeightHW()});
            funcs.glBindRenderbuffer(funcs.GL_RENDERBUFFER(), 0);
            funcs.glFramebufferRenderbuffer(funcs.GL_FRAMEBUFFER(), funcs.GL_DEPTH_ATTACHMENT(), funcs.GL_RENDERBUFFER(), this.depth);
            PZGLUtil.checkGLErrorThrow("glFramebufferRenderbuffer(depth: %d)", new Object[]{this.depth});
            funcs.glFramebufferRenderbuffer(funcs.GL_FRAMEBUFFER(), funcs.GL_STENCIL_ATTACHMENT(), funcs.GL_RENDERBUFFER(), this.depth);
            PZGLUtil.checkGLErrorThrow("glFramebufferRenderbuffer(stencil: %d)", new Object[]{this.depth});
         } else {
            if (depthTex == null) {
               funcs.glRenderbufferStorage(funcs.GL_RENDERBUFFER(), 33189, this.texture.getWidthHW(), this.texture.getHeightHW());
               PZGLUtil.checkGLErrorThrow("glRenderbufferStorage(width: %d, height: %d)", new Object[]{this.texture.getWidthHW(), this.texture.getHeightHW()});
               funcs.glBindRenderbuffer(funcs.GL_RENDERBUFFER(), 0);
               funcs.glFramebufferRenderbuffer(funcs.GL_FRAMEBUFFER(), funcs.GL_DEPTH_ATTACHMENT(), funcs.GL_RENDERBUFFER(), this.depth);
            } else {
               depthTex.bind();
               GL11.glBindTexture(3553, Texture.lastTextureID = 0); // pzopt: decompiler fix, as the jar (dup of the assigned value)
               funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_DEPTH_ATTACHMENT(), 3553, depthTex.getID(), 0);
            }

            PZGLUtil.checkGLErrorThrow("glFramebufferRenderbuffer(depth: %d)", new Object[]{this.depth});
         }

         int pzoptDepthW = depthTex != null ? depthTex.getWidthHW() : 0; // pzopt: glNoSync, the attachment shape
         int pzoptDepthH = depthTex != null ? depthTex.getHeightHW() : 0; // pzopt: glNoSync
         boolean pzoptKnown = pzopt.GlNames.knownComplete(mainTexture.getWidthHW(), mainTexture.getHeightHW(), pzoptDepthW, pzoptDepthH, depthTex != null, bStencilBuffer); // pzopt: glNoSync
         int status = pzoptKnown ? funcs.GL_FRAMEBUFFER_COMPLETE() : funcs.glCheckFramebufferStatus(funcs.GL_FRAMEBUFFER()); // pzopt: glNoSync, a shape that came out complete is not asked again
         if (status != funcs.GL_FRAMEBUFFER_COMPLETE()) {
            if (status == funcs.GL_FRAMEBUFFER_UNDEFINED()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_UNDEFINED");
            }

            if (status == funcs.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT");
            }

            if (status == funcs.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT");
            }

            if (status == funcs.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS");
            }

            if (status == funcs.GL_FRAMEBUFFER_INCOMPLETE_FORMATS()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_INCOMPLETE_FORMATS");
            }

            if (status == funcs.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER");
            }

            if (status == funcs.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER");
            }

            if (status == funcs.GL_FRAMEBUFFER_UNSUPPORTED()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_UNSUPPORTED");
            }

            if (status == funcs.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE()) {
               DebugType.General.error("glCheckFramebufferStatus = GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE");
            }

            throw new RuntimeException("Could not create FBO!");
         }
         pzopt.GlNames.complete(mainTexture.getWidthHW(), mainTexture.getHeightHW(), pzoptDepthW, pzoptDepthH, depthTex != null, bStencilBuffer); // pzopt: glNoSync
      } catch (Exception ex) {
         funcs.glDeleteFramebuffers(this.id);
         funcs.glDeleteRenderbuffers(this.depth);
         this.id = 0;
         this.depth = 0;
         this.texture = null;
         throw ex;
      }
   }

   public static boolean checkFBOSupport() {
      if (checked) {
         return fboSupported;
      }

      checked = true;
      if (GL.getCapabilities().OpenGL30) {
         DebugType.General.debugln("OpenGL 3.0 framebuffer objects supported");
         funcs = new GLFramebufferObject30();
         fboSupported = true;
      } else if (GL.getCapabilities().GL_ARB_framebuffer_object) {
         DebugType.General.debugln("GL_ARB_framebuffer_object supported");
         funcs = new GLFramebufferObjectARB();
         fboSupported = true;
      } else if (GL.getCapabilities().GL_EXT_framebuffer_object) {
         DebugType.General.debugln("GL_EXT_framebuffer_object supported");
         if (!GL.getCapabilities().GL_EXT_packed_depth_stencil) {
            DebugType.General.debugln("GL_EXT_packed_depth_stencil not supported");
         }

         funcs = new GLFramebufferObjectEXT();
         fboSupported = true;
      } else {
         DebugType.General.debugln("None of OpenGL 3.0, GL_ARB_framebuffer_object or GL_EXT_framebuffer_object are supported, zoom disabled");
         fboSupported = false;
      }

      return fboSupported;
   }

   public void destroy() {
      if (lastID == this.id) {
         lastID = 0;
      }

      RenderThread.invokeOnRenderContext(() -> {
         if (this.texture != null) {
            this.texture.destroy();
            this.texture = null;
         }

         if (this.depthTexture != null) {
            this.depthTexture.destroy();
            this.depthTexture = null;
         }

         IGLFramebufferObject funcs = getFuncs();
         if (this.id != 0) {
            funcs.glDeleteFramebuffers(this.id);
            this.id = 0;
         }

         if (this.depth != 0) {
            funcs.glDeleteRenderbuffers(this.depth);
            this.depth = 0;
         }
      });
   }

   public void destroyLeaveTexture() {
      RenderThread.invokeOnRenderContext(() -> {
         this.texture = null;
         this.depthTexture = null;
         IGLFramebufferObject funcs = getFuncs();
         if (this.id != 0) {
            funcs.glDeleteFramebuffers(this.id);
            this.id = 0;
         }

         if (this.depth != 0) {
            funcs.glDeleteRenderbuffers(this.depth);
            this.depth = 0;
         }
      });
   }

   public void releaseTexture() {
      IGLFramebufferObject funcs = getFuncs();
      funcs.glFramebufferTexture2D(funcs.GL_FRAMEBUFFER(), funcs.GL_COLOR_ATTACHMENT0(), 3553, 0, 0);
      this.texture = null;
   }

   public void endDrawing() {
      if (stack.isEmpty()) {
         lastID = 0;
      } else {
         lastID = stack.removeAt(stack.size() - 1);
      }

      IGLFramebufferObject funcs = getFuncs();
      funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), lastID);
   }

   public ITexture getTexture() {
      return this.texture;
   }

   public ITexture getDepthTexture() {
      return this.depthTexture;
   }

   public int getBufferId() {
      return this.id;
   }

   public boolean isDestroyed() {
      return this.texture == null || this.id == 0 || this.depth == 0;
   }

   public void startDrawing() {
      this.startDrawing(false, false);
   }

   public void startDrawing(boolean clear, boolean clearToAlphaZero) {
      stack.add(lastID);
      lastID = this.id;
      IGLFramebufferObject funcs = getFuncs();
      funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), this.id);
      ITexture mainTexture = this.texture != null ? this.texture : this.depthTexture;
      if (mainTexture != null) {
         if (clear) {
            GL11.glClearColor(0.0F, 0.0F, 0.0F, clearToAlphaZero ? 0.0F : 1.0F);
            GL11.glClear(16640);
            if (clearToAlphaZero) {
               GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            }
         }
      }
   }

   public void setTexture(Texture tex3) {
      int curID = lastID;
      IGLFramebufferObject funcs = getFuncs();
      funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), lastID = this.id);
      this.swapTexture(tex3);
      int var10001 = funcs.GL_FRAMEBUFFER();
      lastID = curID;
      funcs.glBindFramebuffer(var10001, curID);
   }

   public void setTextureAndDepth(Texture tex, Texture depth) {
      int curID = lastID;
      IGLFramebufferObject funcs = getFuncs();
      funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), lastID = this.id);
      this.swapTextureAndDepth(tex, depth);
      int var10001 = funcs.GL_FRAMEBUFFER();
      lastID = curID;
      funcs.glBindFramebuffer(var10001, curID);
   }

   public int getWidth() {
      return this.width;
   }

   public int getHeight() {
      return this.height;
   }

   public static int getCurrentID() {
      return lastID;
   }

   public static void reset() {
      stack.resetQuick();
      if (lastID != 0) {
         IGLFramebufferObject funcs = getFuncs();
         funcs.glBindFramebuffer(funcs.GL_FRAMEBUFFER(), lastID = 0); // pzopt: decompiler fix, the jar binds the assigned value (dup), not a second constant
      }
   }
}
