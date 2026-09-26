package pzopt;

import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureID;
import zombie.iso.fboRenderChunk.FBORenderChunk;

/**
 * Source lighting levels retained alongside a chunk's cached colour and occlusion depth.
 *
 * <p>Only mixed-level caches need storage. R8UI contains level + 33 (-32..31), with zero reserved
 * for uncovered pixels. Integer outputs are not blended or mipmapped. Attachment 2 leaves
 * attachment 1 available to HDR's surface shaders. All GL state and resource maps in this class
 * belong to the render thread; the immutable level commands cross the sprite queue.
 */
public final class ChunkFloor {
  private ChunkFloor() {}

  // Unit 8 is free during chunk composition. HdrGlint rebinds it for its later surface pass.
  private static final int TEXTURE_UNIT = 8;
  private static final int BLOCK_BINDING = 15;
  private static final int ATTACHMENT = GL30.GL_COLOR_ATTACHMENT2;
  private static final int[] DRAW_BUFFERS = {GL30.GL_COLOR_ATTACHMENT0, GL11.GL_NONE, ATTACHMENT};
  private static final int[] ZERO = {0, 0, 0, 0};
  private static final int[] levelData = new int[4];
  private static final ByteBuffer colourMask = BufferUtils.createByteBuffer(4);
  private static final IdentityHashMap<TextureID, Integer> textures = new IdentityHashMap<>();
  private static final TextureDraw.GenericDrawer[] levels = new TextureDraw.GenericDrawer[64];
  private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
  private static final Pattern VERSION = Pattern.compile("(?m)^\\s*#version\\s+(\\d+)[^\\r\\n]*");
  private static final Pattern COLOUR = Pattern.compile("(?m)^(\\s*)out\\s+vec4\\s+(\\w+)\\s*;");
  private static final Pattern EXTENSION =
      Pattern.compile("(?m)^\\s*#extension[^\\r\\n]*[\\r\\n]+");
  private static int uniformBuffer;
  private static int emptyTexture;
  private static int currentTag = -1;
  private static boolean writing;
  private static boolean logged;

  static {
    for (int i = 0; i < levels.length; i++) {
      final int level = i - 32;
      levels[i] =
          new TextureDraw.GenericDrawer() {
            @Override
            public void render() {
              setLevel(level);
            }
          };
    }
  }

  private static boolean enabled() {
    return PixelLight.ACTIVE && !"pass".equals(Config.PPL_MODE);
  }

  /** Game thread: enqueue the source level after the chunk framebuffer has been selected. */
  public static void queueLevel(int level) {
    if (enabled() && level >= -32 && level < 32) {
      SpriteRenderer.instance.drawGeneric(levels[level + 32]);
    }
  }

  /** Render thread, immediately after the native chunk framebuffer begin/clear. */
  public static void begin(FBORenderChunk chunk, boolean clear) {
    writing = false;
    if (!enabled() || !GL.getCapabilities().OpenGL42 || chunk == null || chunk.depth == null) {
      return;
    }
    TextureID key = chunk.depth.getTextureId();
    if (chunk.getMinLevel() == chunk.getTopLevel()) {
      // A pooled framebuffer can change from two levels to one on its next full bake.
      if (textures.containsKey(key)) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        release(key);
      }
      return;
    }
    ensureBuffer();
    Integer texture = textures.get(key);
    if (texture == null) {
      texture = makeTexture(chunk.depth.getWidthHW(), chunk.depth.getHeightHW(), null);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, ATTACHMENT, GL11.GL_TEXTURE_2D, texture, 0);
      if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        GL11.glDeleteTextures(texture);
        throw new IllegalStateException("Incomplete chunk floor framebuffer");
      }
      textures.put(key, texture);
      if (!logged) {
        logged = true;
        Log.info("pixel light: source-floor tags active (R8UI, mixed-level caches)");
      }
      clear = true;
    }
    GL20.glDrawBuffers(DRAW_BUFFERS);
    if (clear) {
      clearTexture(texture);
    }
    writing = true;
    currentTag = -1;
    // Tree appends provide their own levels; ordinary bakes queue their level before drawing.
    uploadTag(0);
  }

  private static void clearTexture(int texture) {
    // Clear the whole storage, including padding outside the bake viewport. Zero must never
    // become an uninitialized floor ID when the composite samples an uncovered edge texel.
    if (GL.getCapabilities().OpenGL44) {
      GL44.glClearTexImage(
          texture, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
      return;
    }
    // PPL also supports GL 4.2: clear through the framebuffer without inheriting its masks.
    boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, 2, colourMask);
    try {
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL30.glColorMaski(2, true, true, true, true);
      GL30.glClearBufferuiv(GL11.GL_COLOR, 2, ZERO);
    } finally {
      GL30.glColorMaski(
          2,
          colourMask.get(0) != 0,
          colourMask.get(1) != 0,
          colourMask.get(2) != 0,
          colourMask.get(3) != 0);
      if (scissor) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
    }
  }

  /** Render thread: stop writing tags before colour-only AO processing or framebuffer end. */
  public static void end() {
    if (writing) {
      GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
      writing = false;
    }
  }

  /** Game thread: colour-only postprocessing must not replace the cached ownership. */
  public static void queueEnd() {
    if (enabled()) {
      SpriteRenderer.instance.drawGeneric(END);
    }
  }

  private static final TextureDraw.GenericDrawer END =
      new TextureDraw.GenericDrawer() {
        @Override
        public void render() {
          end();
        }
      };

  /** Render thread: tree quads can come from either level, including during an append. */
  public static void treeLevel(int level) {
    if (writing && currentTag != level + 33) {
      // VBORenderer can defer draws past endRun. Submit the old level before changing the block.
      VBORenderer.getInstance().flush();
      setLevel(level);
    }
  }

  private static void setLevel(int level) {
    if (writing) {
      if (level < -32 || level >= 32) {
        throw new IllegalArgumentException("Chunk floor outside native range: " + level);
      }
      uploadTag(level + 33);
    }
  }

  private static void uploadTag(int tag) {
    if (currentTag == tag) {
      return;
    }
    levelData[0] = tag;
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBuffer);
    // Orphan the tiny block: earlier queued draws must keep their previous level.
    GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, levelData, GL15.GL_STREAM_DRAW);
    GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    currentTag = tag;
  }

  private static void ensureBuffer() {
    if (uniformBuffer == 0) {
      uniformBuffer = GL15.glGenBuffers();
      GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBuffer);
      GL15.glBufferData(GL31.GL_UNIFORM_BUFFER, ZERO, GL15.GL_STREAM_DRAW);
      GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BLOCK_BINDING, uniformBuffer);
      GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);
    }
  }

  private static int makeTexture(int width, int height, ByteBuffer initial) {
    int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
    int texture = GL11.glGenTextures();
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    GL11.glTexImage2D(
        GL11.GL_TEXTURE_2D,
        0,
        GL30.GL_R8UI,
        width,
        height,
        0,
        GL30.GL_RED_INTEGER,
        GL11.GL_UNSIGNED_BYTE,
        initial);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    GL13.glActiveTexture(active);
    return texture;
  }

  /**
   * Render thread: called by TextureID.destroy, so pooled textures and their tags share a lifetime.
   */
  public static void release(TextureID parent) {
    Integer texture = textures.remove(parent);
    if (texture != null) {
      GL11.glDeleteTextures(texture);
    }
  }

  /**
   * Render thread: select the existing shader variant without ownership work for an untagged cache.
   */
  static boolean hasTexture(Texture depth) {
    return depth != null && textures.containsKey(depth.getTextureId());
  }

  /** Game thread: do not leave an integer image on the later HDR surface pass's sampler unit. */
  public static void afterComposite() {
    if (Config.PIXEL_LIGHT && Overrides.enabled() && !"pass".equals(Config.PPL_MODE)) {
      SpriteRenderer.instance.drawGeneric(UNBIND);
    }
  }

  private static final TextureDraw.GenericDrawer UNBIND =
      new TextureDraw.GenericDrawer() {
        @Override
        public void render() {
          if (uniformBuffer != 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
          }
        }
      };

  /** Render thread: bind the tag image for a composite draw, returning whether it has ownership. */
  static boolean bind(Texture depth) {
    Integer texture = depth == null ? null : textures.get(depth.getTextureId());
    if (texture == null && emptyTexture == 0) {
      emptyTexture = makeTexture(1, 1, BufferUtils.createByteBuffer(1));
    }
    GL13.glActiveTexture(GL13.GL_TEXTURE0 + TEXTURE_UNIT);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture == null ? emptyTexture : texture);
    // ChunkRenderShader and PixelLight's other sampler bindings return to unit zero.
    GL13.glActiveTexture(GL13.GL_TEXTURE0);
    return texture != null;
  }

  /**
   * ShaderUnit hook, after colour rewriting and sprite-filter variant capture. Preserve existing
   * colour outputs and append an integer ownership output to fragment mains. Included fragment units
   * share the colour declarations but do not need a level block. The chunk composite only consumes
   * ownership.
   */
  public static String patchShader(String fileName, String code, boolean fragment) {
    if (!fragment || !enabled() || !GL.getCapabilities().OpenGL42) {
      return code;
    }
    String name = fileName.replace('\\', '/');
    if (name.endsWith("/chunkShader.frag")
        || name.endsWith("/pzopt_chunkBase.frag")
        || name.endsWith("/pzopt_chunkStock.frag")
        || name.endsWith("/pzopt_sfChunk.frag")) {
      return code;
    }
    ensureBuffer();
    return rewriteShader(code);
  }

  /** Pure source transformation, separate from render-context allocation. */
  static String rewriteShader(String code) {
    Matcher version = VERSION.matcher(code);
    if (version.find()) {
      code =
          version.replaceFirst(
              "#version " + Math.max(420, Integer.parseInt(version.group(1))) + " compatibility");
    } else {
      code = "#version 420 compatibility\n" + code;
    }
    String declarations = "";
    if (code.contains("gl_FragColor")) {
      declarations += "layout(location=0) out vec4 pzoptBakeColour;\n";
      code = code.replace("gl_FragColor", "pzoptBakeColour");
    }
    for (int i = 0; i < 2; i++) {
      String old = "gl_FragData[" + i + "]";
      if (code.contains(old)) {
        declarations += "layout(location=" + i + ") out vec4 pzoptBakeColour" + i + ";\n";
        code = code.replace(old, "pzoptBakeColour" + i);
      }
    }
    code = COLOUR.matcher(code).replaceAll("$1layout(location=0) out vec4 $2;");
    // Declarations must follow version/extension directives, but precede functions using them.
    int insertion = code.indexOf('\n') + 1;
    Matcher extensions = EXTENSION.matcher(code);
    while (extensions.find()) {
      insertion = extensions.end();
    }
    code = code.substring(0, insertion) + declarations + code.substring(insertion);
    Matcher main = MAIN.matcher(code);
    if (main.find()) {
      code = main.replaceFirst("void pzoptBakeMain()");
      code +=
          "\nlayout(std140, binding="
              + BLOCK_BINDING
              + ") uniform PzoptBakeLevel { uint pzoptBakeTag; };\n"
              + "layout(location=2) out uint pzoptFloor;\n"
              + "void main() { pzoptBakeMain(); pzoptFloor = pzoptBakeTag; }\n";
    }
    return code;
  }
}
