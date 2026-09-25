package zombie.core.opengl;

import org.lwjgl.opengl.GL20;
import zombie.core.opengl.ShaderProgram.Uniform;
import zombie.core.textures.TextureDraw.GenericDrawer;

public final class ShaderUniformSetter extends GenericDrawer {
   ShaderUniformSetter.Type type;
   int location;
   float f1;
   float f2;
   float f3;
   float f4;
   int i1;
   int i2;
   int i3;
   int i4;
   ShaderUniformSetter next;
   static ShaderUniformSetter pool;

   private ShaderUniformSetter set(ShaderUniformSetter.Type type, int location) {
      this.type = type;
      this.location = location;
      this.next = null;
      return this;
   }

   public ShaderUniformSetter setNext(ShaderUniformSetter next) {
      this.next = next;
      return next;
   }

   public void render() {
   }

   public void postRender() {
      ShaderUniformSetter e = this;

      while (e != null) {
         ShaderUniformSetter next1 = e.next;
         release(e);
         e = next1;
      }
   }

   public void invokeAll() {
      for (ShaderUniformSetter e = this; e != null; e = e.next) {
         e.invoke();
      }
   }

   // pzopt: uniformCache (pzopt.UniformCache). The chain of a shader start, skipping every 1f / 1i uniform the program already holds.
   public void pzoptInvokeAllCached() { // pzopt
      for (ShaderUniformSetter e = this; e != null; e = e.next) {
         if (e.type == ShaderUniformSetter.Type.Uniform1f) {
            if (pzopt.UniformCache.same1f(e.location, e.f1)) {
               continue;
            }
         } else if (e.type == ShaderUniformSetter.Type.Uniform1i) {
            if (pzopt.UniformCache.same1i(e.location, e.i1)) {
               continue;
            }
         } else {
            pzopt.UniformCache.forget(e.location);
         }
         e.invoke();
      }
   }

   private void invoke() {
      switch (this.type) {
         case Uniform1f:
            GL20.glUniform1f(this.location, this.f1);
            break;
         case Uniform2f:
            GL20.glUniform2f(this.location, this.f1, this.f2);
            break;
         case Uniform3f:
            GL20.glUniform3f(this.location, this.f1, this.f2, this.f3);
            break;
         case Uniform4f:
            GL20.glUniform4f(this.location, this.f1, this.f2, this.f3, this.f4);
            break;
         case Uniform1i:
            GL20.glUniform1i(this.location, this.i1);
            break;
         case Uniform2i:
            GL20.glUniform2i(this.location, this.i1, this.i2);
            break;
         case Uniform3i:
            GL20.glUniform3i(this.location, this.i1, this.i2, this.i3);
            break;
         case Uniform4i:
            GL20.glUniform4i(this.location, this.i1, this.i2, this.i3, this.i4);
      }
   }

   public static ShaderUniformSetter uniform1f(int location, float f1) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform1f, location);
      e.f1 = f1;
      return e;
   }

   public static ShaderUniformSetter uniform2f(int location, float f1, float f2) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform2f, location);
      e.f1 = f1;
      e.f2 = f2;
      return e;
   }

   public static ShaderUniformSetter uniform3f(int location, float f1, float f2, float f3) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform3f, location);
      e.f1 = f1;
      e.f2 = f2;
      e.f3 = f3;
      return e;
   }

   public static ShaderUniformSetter uniform4f(int location, float f1, float f2, float f3, float f4) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform4f, location);
      e.f1 = f1;
      e.f2 = f2;
      e.f3 = f3;
      e.f4 = f4;
      return e;
   }

   private static Uniform getShaderUniform(Shader shader, String uniformName, int uniformType) {
      if (shader == null) {
         return null;
      }

      ShaderProgram program = shader.getProgram();
      return program == null ? null : program.getUniform(uniformName, uniformType, false);
   }

   public static ShaderUniformSetter uniform1f(Shader shader, String location, float f1) {
      Uniform u = getShaderUniform(shader, location, 5126);
      return u == null ? alloc().set(ShaderUniformSetter.Type.NIL, -1) : uniform1f(u.loc, f1);
   }

   public static ShaderUniformSetter uniform1i(int location, int i1) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform1i, location);
      e.i1 = i1;
      return e;
   }

   public static ShaderUniformSetter uniform2i(int location, int i1, int i2) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform2i, location);
      e.i1 = i1;
      e.i2 = i2;
      return e;
   }

   public static ShaderUniformSetter uniform3i(int location, int i1, int i2, int i3) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform3i, location);
      e.i1 = i1;
      e.i2 = i2;
      e.i3 = i3;
      return e;
   }

   public static ShaderUniformSetter uniform4i(int location, int i1, int i2, int i3, int i4) {
      ShaderUniformSetter e = alloc().set(ShaderUniformSetter.Type.Uniform4i, location);
      e.i1 = i1;
      e.i2 = i2;
      e.i3 = i3;
      e.i4 = i4;
      return e;
   }

   public static ShaderUniformSetter uniform1i(Shader shader, String location, int i1) {
      Uniform u = getShaderUniform(shader, location, 5124);
      return u == null ? alloc().set(ShaderUniformSetter.Type.NIL, -1) : uniform1i(u.loc, i1);
   }

   public static ShaderUniformSetter alloc() {
      if (pool == null) {
         return new ShaderUniformSetter();
      }

      ShaderUniformSetter e = pool;
      pool = e.next;
      return e;
   }

   public static void release(ShaderUniformSetter e) {
      e.next = pool;
      pool = e;
   }

   public enum Type {
      NIL,
      Uniform1f,
      Uniform2f,
      Uniform3f,
      Uniform4f,
      Uniform1i,
      Uniform2i,
      Uniform3i,
      Uniform4i;
   }
}
