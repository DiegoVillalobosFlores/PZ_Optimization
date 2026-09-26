package zombie.core.opengl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lwjgl.opengl.GL20;
import zombie.core.Core;
import zombie.core.IndieFileLoader;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.util.StringUtils;

public final class ShaderUnit {
   static boolean combineShaderSources;
   private final ShaderProgram parentProgram;
   private final String fileName;
   private final ShaderUnit.Type unitType;
   private int glId;
   private boolean isAttached;
   private final ArrayList<ShaderAttribLocation> attributeLocations = new ArrayList<>();
   protected String processedCode;
   private static final Pattern locationPattern = Pattern.compile(
      "^layout\\s*\\(\\s*location\\s*=\\s*([0-9]+)\\s*\\)\\s*in\\s*([A-Za-z0-9]+)\\s*([A-Za-z0-9]+)\\s*;\\s*$"
   );
   private static final Pattern inPattern = Pattern.compile("^in\\s*(\\S+)\\s*(\\S+)\\s*;\\s*$");
   private static final Pattern outPattern = Pattern.compile("^out\\s*(\\S+)\\s*(\\S+)\\s*;\\s*$");
   private static final Pattern colourPattern = Pattern.compile("\\s*colour\\s*=\\s*(.+);\\s*$");

   public ShaderUnit(ShaderProgram parent, String fileName, ShaderUnit.Type unitType) {
      this.parentProgram = parent;
      this.fileName = fileName;
      this.unitType = unitType;
      this.glId = 0;
      this.isAttached = false;
   }

   public String getFileName() {
      return this.fileName;
   }

   public ShaderUnit.Type getType() {
      return this.unitType;
   }

   public boolean isCompiled() {
      return combineShaderSources && !this.parentProgram.isFirstUnit(this) ? this.processedCode != null : this.glId != 0;
   }

   public boolean compile() {
      if (DebugLog.isEnabled(DebugType.Shader)) {
         DebugType.Shader.debugln(this.getFileName());
      }

      int glType = getGlType(this.unitType);
      ArrayList<String> additionalIncludeFiles = new ArrayList<>();
      String processedCode = this.loadShaderFile(this.fileName, additionalIncludeFiles);
      if (processedCode == null) {
         return false;
      }

      if (combineShaderSources) {
         this.processedCode = processedCode;
      }

      for (String additionalIncludeFile : additionalIncludeFiles) {
         if (this.parentProgram == null) {
            DebugType.Shader.error(this.getFileName() + "> Cannot include additional shader file. Parent program is null. " + additionalIncludeFile);
            break;
         }

         String shaderFile = additionalIncludeFile + ".glsl";
         if (DebugLog.isEnabled(DebugType.Shader)) {
            DebugType.Shader.debugln(this.getFileName() + "> Loading additional shader unit: " + shaderFile);
         }

         ShaderUnit includeUnit = this.parentProgram.addShader(shaderFile, this.unitType);
         if (!includeUnit.isCompiled() && !includeUnit.compile()) {
            DebugType.Shader.error(this.getFileName() + "> Included shader unit failed to compile: " + shaderFile);
            return false;
         }
      }

      if (combineShaderSources && !this.parentProgram.isFirstUnit(this)) {
         if (this.getType() == ShaderUnit.Type.Vert) {
            this.parentProgram.addCombinedVertexSource(this.processedCode);
         } else {
            this.parentProgram.addCombinedFragmentSource(this.processedCode);
         }

         return true;
      } else {
         if (combineShaderSources) {
            processedCode = this.processedCode;
         }

         int shaderID = GL20.glCreateShader(glType);
         if (shaderID == 0) {
            DebugType.Shader.error(this.getFileName() + "> Failed to generate shaderID. Shader code:\n" + processedCode);
            return false;
         } else {
            GL20.glShaderSource(shaderID, pzopt.SpriteFilter.patchShader(this.fileName, pzopt.Ssr.patchShader(this.fileName, pzopt.PixelLight.patchShader(this.fileName, pzopt.Hdr.patchShader(this.fileName, pzopt.Grade.patchShader(this.fileName, processedCode)))))); // pzopt: HDR output, the world composite shader gets its expansion appended (after the colour grade, pzopt.Grade); pixelLight, the chunk composite shader lights each pixel; reflections, the water shaders look up the scene; sprite filter, the chunk composite's variants per zoom
            GL20.glCompileShader(shaderID);
            ShaderProgram.printLogInfo(shaderID);
            this.glId = shaderID;
            return true;
         }
      }
   }

   public boolean isAttached() {
      return this.isAttached;
   }

   public boolean attach() {
      if (DebugLog.isEnabled(DebugType.Shader)) {
         DebugType.Shader.debugln(this.getFileName());
      }

      if (this.getParentShaderProgramGLID() == 0) {
         DebugType.Shader.error("Parent program does not exist.");
         return false;
      }

      if (!this.isCompiled()) {
         this.compile();
      }

      if (!this.isCompiled()) {
         return false;
      }

      for (int i = 0; i < this.attributeLocations.size(); i++) {
         ShaderAttribLocation loc = this.attributeLocations.get(i);
         GL20.glBindAttribLocation(this.getParentShaderProgramGLID(), loc.index, loc.name);
      }

      GL20.glAttachShader(this.getParentShaderProgramGLID(), this.getGLID());
      if (!PZGLUtil.checkGLError(false)) {
         this.destroy();
         return false;
      } else {
         this.isAttached = true;
         return true;
      }
   }

   public void destroy() {
      if (this.glId == 0) {
         this.isAttached = false;
      } else {
         DebugType.Shader.debugln(this.getFileName());

         try {
            if (this.isAttached && this.getParentShaderProgramGLID() != 0) {
               GL20.glDetachShader(this.getParentShaderProgramGLID(), this.glId);
               if (!PZGLUtil.checkGLError(false)) {
                  DebugType.Shader.error("ShaderUnit failed to detach: " + this.getFileName());
                  return;
               }
            }

            GL20.glDeleteShader(this.glId);
            PZGLUtil.checkGLError(false);
         } finally {
            this.glId = 0;
            this.isAttached = false;
         }
      }
   }

   public int getGLID() {
      return this.glId;
   }

   public int getParentShaderProgramGLID() {
      return this.parentProgram != null ? this.parentProgram.getShaderID() : 0;
   }

   private static int getGlType(ShaderUnit.Type unitType) {
      return unitType == ShaderUnit.Type.Vert ? 35633 : 35632;
   }

   private String loadShaderFile(String fileName, ArrayList<String> additionalIncludeFiles) {
      additionalIncludeFiles.clear();
      String processedCode = this.preProcessShaderFile(fileName, additionalIncludeFiles);
      if (processedCode == null) {
         return null;
      }

      int indexOfHash = processedCode.indexOf("#version");
      if (indexOfHash > 0) {
         processedCode = processedCode.substring(indexOfHash);
      }

      return processedCode;
   }

   private String preProcessShaderFile(String fileName, ArrayList<String> includes) {
      StringBuilder processedCode = new StringBuilder();

      try (
         InputStreamReader isr = IndieFileLoader.getStreamReader(fileName, false);
         BufferedReader reader = new BufferedReader(isr);
      ) {
         String newLine = System.getProperty("line.separator");

         for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            String processedLine = line.trim();
            if (!processedLine.startsWith("#include ") || !this.processIncludeLine(fileName, processedCode, processedLine, newLine, includes)) {
               processedLine = this.processShaderSyntax(processedLine);
               processedCode.append(processedLine).append(newLine);
            }
         }
      } catch (Exception e) {
         DebugType.Shader.printException(e, "Failed reading shader code. fileName:" + fileName, LogSeverity.Error);
         return null;
      }

      return processedCode.toString();
   }

   private boolean processIncludeLine(String fileName, StringBuilder processedCode, String processedLine, String newLine, ArrayList<String> includes) {
      String includeTextRaw = processedLine.substring("#include ".length());
      if (includeTextRaw.startsWith("\"") && includeTextRaw.endsWith("\"")) {
         String parentFolder = this.getParentFolder(fileName);
         String includeEntryRaw = includeTextRaw.substring(1, includeTextRaw.length() - 1);
         includeEntryRaw = includeEntryRaw.trim();
         includeEntryRaw = includeEntryRaw.replace('\\', '/');
         includeEntryRaw = includeEntryRaw.toLowerCase();
         if (includeEntryRaw.contains(":")) {
            DebugType.Shader.error(fileName + "> include cannot have ':' characters. " + includeTextRaw);
            return false;
         }

         if (includeEntryRaw.startsWith("/")) {
            DebugType.Shader.error(fileName + "> include cannot start with '/' or '\\' characters. " + includeTextRaw);
            return false;
         }

         String includeEntryRawFull = parentFolder + "/" + includeEntryRaw;
         ArrayList<String> pathElements = new ArrayList<>();

         for (String pathElement : includeEntryRawFull.split("/")) {
            if (!pathElement.equals(".") && !pathElement.isEmpty()) {
               if (StringUtils.isNullOrWhitespace(pathElement)) {
                  DebugType.Shader.error(fileName + "> include path cannot have whitespace-only folders. " + includeTextRaw);
                  return false;
               }

               if (pathElement.equals("..")) {
                  if (pathElements.isEmpty()) {
                     DebugType.Shader.error(fileName + "> include cannot go out of bounds with '..' parameters. " + includeTextRaw);
                     return false;
                  }

                  pathElements.remove(pathElements.size() - 1);
               } else {
                  pathElements.add(pathElement);
               }
            }
         }

         StringBuilder includeEntryBuilder = new StringBuilder(includeEntryRawFull.length());

         for (String pathElement : pathElements) {
            if (!includeEntryBuilder.isEmpty()) {
               includeEntryBuilder.append('/');
            }

            includeEntryBuilder.append(pathElement);
         }

         String includeEntry = includeEntryBuilder.toString();
         if (includes.contains(includeEntry)) {
            processedCode.append("// Duplicate Include, skipped. ").append(processedLine).append(newLine);
            return true;
         } else {
            includes.add(includeEntry);
            String headerFileName = includeEntry + ".h";
            String resolvedIncludeText = this.preProcessShaderFile(headerFileName, includes);
            processedCode.append(newLine);
            processedCode.append("// Include begin ").append(processedLine).append(newLine);
            processedCode.append(resolvedIncludeText).append(newLine);
            processedCode.append("// Include end   ").append(processedLine).append(newLine);
            processedCode.append(newLine);
            return true;
         }
      } else {
         DebugType.Shader.error(fileName + "> include needs to be in quotes: " + includeTextRaw);
         return false;
      }
   }

   private String processShaderSyntax(String processedLine) {
      if (combineShaderSources && processedLine.startsWith("#version") && !this.parentProgram.isFirstUnit(this)) {
         return "";
      }

      if (!Core.getInstance().getUseOpenGL21()) {
         return processedLine;
      }

      if (processedLine.contains("#version 330")
         || processedLine.contains("#version 110")
         || processedLine.contains("#version 130")
         || processedLine.contains("#version 140")
         || processedLine.contains("#version 430")) {
         processedLine = "#version 120";
      }

      if (processedLine.startsWith("out vec4 colour")) {
         processedLine = "";
      }

      if (processedLine.contains("texture2DLod")) {
         processedLine = processedLine.replace("texture2DLod", "texture2D");
      }

      if (processedLine.contains("texture2DLod")) {
         processedLine = processedLine.replace("texture2DLod", "texture2D");
      }

      if (this.unitType == ShaderUnit.Type.Vert && processedLine.startsWith("layout")) {
         Matcher matcher = locationPattern.matcher(processedLine);
         if (matcher.matches()) {
            String s1 = matcher.group(1);
            String s2 = matcher.group(2);
            String s3 = matcher.group(3);
            ShaderAttribLocation loc = new ShaderAttribLocation();
            loc.index = PZMath.tryParseInt(s1, 0);
            loc.name = s3;
            this.attributeLocations.add(loc);
            return String.format("attribute %s %s;", s2, s3);
         }
      }

      if (this.unitType == ShaderUnit.Type.Vert && processedLine.startsWith("out")) {
         Matcher matcher = outPattern.matcher(processedLine);
         if (matcher.matches()) {
            String s1 = matcher.group(1);
            String s2 = matcher.group(2);
            return String.format("varying %s %s;", s1, s2);
         }
      }

      if (this.unitType == ShaderUnit.Type.Frag && processedLine.startsWith("in")) {
         Matcher matcher = inPattern.matcher(processedLine);
         if (matcher.matches()) {
            String s1 = matcher.group(1);
            String s2 = matcher.group(2);
            return String.format("varying %s %s;", s1, s2);
         }
      }

      if (this.unitType == ShaderUnit.Type.Frag && processedLine.startsWith("colour")) {
         Matcher matcher = colourPattern.matcher(processedLine);
         if (matcher.matches()) {
            String s1 = matcher.group(1);
            return String.format("gl_FragColor =  %s;", s1);
         }
      }

      return processedLine;
   }

   private String getParentFolder(String fileName) {
      int lastIndexOfSeperator = fileName.lastIndexOf("/");
      if (lastIndexOfSeperator > -1) {
         return fileName.substring(0, lastIndexOfSeperator);
      }

      lastIndexOfSeperator = fileName.lastIndexOf("\\");
      return lastIndexOfSeperator > -1 ? fileName.substring(0, lastIndexOfSeperator) : "";
   }

   public enum Type {
      Vert,
      Frag;
   }
}
