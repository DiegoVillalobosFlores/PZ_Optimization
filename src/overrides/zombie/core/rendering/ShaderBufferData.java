package zombie.core.rendering;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.lwjgl.opengl.GL; // pzopt: capability guard for uniform-block reflection
import org.lwjgl.opengl.GL31; // pzopt: distinguish block members from ordinary uniforms
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vector.Matrix3f;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector3f;
import org.lwjgl.util.vector.Vector4f;
import zombie.core.skinnedmodel.shader.Shader;

public class ShaderBufferData {
  protected List<ShaderParameter> parameterList = new ArrayList<ShaderParameter>();
  protected List<ShaderParameter> uniformParameterList = new ArrayList<ShaderParameter>();
  protected List<ShaderParameter> instancedParameterList = new ArrayList<ShaderParameter>();
  public HashMap<String, ShaderParameter> parameters = new HashMap<>();
  private int size;
  private int currentInstance;

  public ShaderBufferData(Shader shader) {
    Uniform[] uniforms;
    int program = shader.getShaderProgram().getShaderID();
    int location = shader.instancedDataAttrib;
    if (location >= 0) {
      int[] variables = BufferUtility.GetBlockMembers(program, location);
      for (int i = 0; i < variables.length; ++i) {
        String name = BufferUtility.GetMemberName(program, variables[i]);
        int type = BufferUtility.GetMemberProperty(program, variables[i], 37626);
        int size = BufferUtility.GetMemberProperty(program, variables[i], 37627);
        int offset = BufferUtility.GetMemberProperty(program, variables[i], 37628);
        int length = BufferUtility.GetMemberProperty(program, variables[i], 37627);
        ShaderParameter param = ShaderBufferData.CreateShaderParameter(size, type, name);
        assert (param != null);
        param.offset = offset;
        param.length = length;
        this.AddBufferMember(param);
      }
      this.size = BufferUtility.GetBlockProperty(program, location, 37635);
      this.size = (int) Math.ceil((double) this.size / 128.0);
    }
    for (Uniform uniform : uniforms = BufferUtility.GetUniforms(program)) {
      ShaderParameter param =
          ShaderBufferData.CreateShaderParameter(uniform.size, uniform.type, uniform.name);
      assert (param != null);
      param.offset = uniform.location;
      param.PullUniform(program);
      param.UpdateDefault();
      this.AddUniform(param);
    }
    this.instancedParameterList.sort(Comparator.comparingInt(a -> a.offset));
  }

  private static ShaderParameter CreateShaderParameter(int size, int type, String name) {
    if (size == 1) {
      switch (type) {
        case 35670:
          {
            return new ShaderParameter(name, false);
          }
        case 5124:
          {
            return new ShaderParameter(name, 0, false);
          }
        case 5126:
          {
            return new ShaderParameter(name, 0.0f);
          }
        case 35664:
          {
            return new ShaderParameter(name, new Vector2f());
          }
        case 35665:
          {
            return new ShaderParameter(name, new Vector3f());
          }
        case 35666:
          {
            return new ShaderParameter(name, new Vector4f());
          }
        case 35675:
          {
            return new ShaderParameter(name, new Matrix3f());
          }
        case 35676:
          {
            return new ShaderParameter(name, new Matrix4f());
          }
        case 35677:
        case 35678:
        case 35679:
        case 35680:
        case 36288:
        case 36289:
        case 36297:
        case 36298:
        case 36299:
        case 36302:
        case 36303:
        case 36305:
        case 36306:
        case 36307:
        case 36308:
        case 36310:
        case 36311:
        case 36878:
        case 36879:
          {
            return new ShaderParameter(name, 0, true);
          }
      }
    } else {
      switch (type) {
        case 5124:
          {
            return new ShaderParameter(name, new int[size], false);
          }
        case 5126:
          {
            return new ShaderParameter(name, new float[size]);
          }
        case 35664:
          {
            return new ShaderParameter(name, new Vector2f[size]);
          }
        case 35665:
          {
            return new ShaderParameter(name, new Vector3f[size]);
          }
        case 35666:
          {
            return new ShaderParameter(name, new Vector4f[size]);
          }
        case 35675:
          {
            return new ShaderParameter(name, new Matrix3f[size]);
          }
        case 35676:
          {
            return new ShaderParameter(name, new Matrix4f[size]);
          }
        case 35677:
        case 35678:
        case 35679:
        case 35680:
        case 36305:
        case 36306:
        case 36307:
        case 36308:
          {
            return new ShaderParameter(name, 0, true);
          }
      }
    }
    return null;
  }

  private void Reset(List<ShaderParameter> list) {
    for (ShaderParameter parameter : this.parameterList) {
      parameter.ResetValue();
    }
  }

  public void ResetParameters() {
    this.Reset(this.parameterList);
  }

  public void ResetUniforms() {
    this.Reset(this.uniformParameterList);
  }

  public void ResetInstanced() {
    this.Reset(this.instancedParameterList);
  }

  protected void AddBufferMember(ShaderParameter parameter) {
    this.instancedParameterList.add(parameter);
    this.parameterList.add(parameter);
    this.parameters.put(parameter.name, parameter);
  }

  protected void AddUniform(ShaderParameter parameter) {
    this.uniformParameterList.add(parameter);
    this.parameterList.add(parameter);
    this.parameters.put(parameter.name, parameter);
  }

  private void Copy(ShaderPropertyBlock props, List<ShaderParameter> list) {
    for (ShaderParameter param : list) {
      ShaderParameter fromParam = props.GetParameter(param.name);
      if (fromParam == null) continue;
      param.Copy(fromParam, false, true);
    }
  }

  public void CopyParameters(ShaderPropertyBlock props) {
    this.Copy(props, this.parameterList);
  }

  public void CopyUniforms(ShaderPropertyBlock props) {
    this.Copy(props, this.uniformParameterList);
  }

  public void CopyInstanced(ShaderPropertyBlock props) {
    this.Copy(props, this.instancedParameterList);
  }

  public int GetSize() {
    return this.size;
  }

  public int GetCurrentInstance() {
    return this.currentInstance;
  }

  public void PushParameters(InstancedBuffer buffer) {
    this.PushUniforms();
    this.PushInstanced(buffer);
  }

  public void PushUniforms() {
    for (ShaderParameter parameter : this.uniformParameterList) {
      parameter.PushUniform();
    }
  }

  public void PushInstanced(InstancedBuffer buffer) {
    if (buffer.GetBufferID() >= 0) {
      int baseOffset = this.currentInstance * this.size;
      for (ShaderParameter parameter : this.instancedParameterList) {
        parameter.PushInstanced(buffer, baseOffset);
      }
      ++this.currentInstance;
    }
  }

  public void PushInstanced(InstancedBuffer buffer, ShaderPropertyBlock block) {
    if (buffer.GetBufferID() >= 0) {
      int baseOffset = this.currentInstance * this.size;
      buffer.data.position(baseOffset);
      block.CopyToInstanced(buffer);
      ++this.currentInstance;
    }
  }

  public void Reset() {
    this.currentInstance = 0;
  }

  private static class BufferUtility {
    private static final int[] PropertyID = new int[] {1};
    private static final int[] Count = new int[] {1};
    private static final int[] Results = new int[1];

    private BufferUtility() {}

    public static Uniform[] GetUniforms(int shader) {
      int uniformCount = GL43.glGetProgrami(shader, 35718);
      IntBuffer size = MemoryUtil.memAllocInt(1);
      IntBuffer type = MemoryUtil.memAllocInt(1);
      Uniform[] uniforms = new Uniform[uniformCount];
      int maxNameLen = GL43.glGetProgrami(shader, 35719);
      int ordinaryCount = 0; // pzopt: compact out uniform-block members
      boolean uniformBlocks = pzopt.Overrides.enabled() && GL.getCapabilities().OpenGL31; // pzopt
      for (int i = 0; i < uniformCount; ++i) {
        // pzopt: block members are updated through their buffer, never glGetUniform/glUniform.
        // pzopt: PzoptBakeLevel's uint is valid there but unsupported by ShaderParameter.
        if (uniformBlocks
            && GL31.glGetActiveUniformsi(shader, i, GL31.GL_UNIFORM_BLOCK_INDEX) >= 0) { // pzopt
          continue; // pzopt
        } // pzopt
        Uniform uniform = new Uniform();
        uniform.name = GL43.glGetActiveUniform(shader, i, maxNameLen, size, type);
        uniform.size = size.get(0);
        uniform.type = type.get(0);
        uniform.location = GL43.glGetUniformLocation(shader, uniform.name);
        uniforms[ordinaryCount++] =
            uniform; // pzopt: retain the original order of ordinary uniforms
      }
      MemoryUtil.memFree(size);
      MemoryUtil.memFree(type);
      return ordinaryCount == uniforms.length
          ? uniforms
          : java.util.Arrays.copyOf(uniforms, ordinaryCount); // pzopt
    }

    public static int GetBlockProperty(int shader, int bufferIndex, int property) {
      BufferUtility.PropertyID[0] = property;
      BufferUtility.Count[0] = 1;
      GL43.glGetProgramResourceiv(shader, 37606, bufferIndex, PropertyID, Count, Results);
      return Results[0];
    }

    public static int[] GetBlockMembers(int shader, int bufferIndex) {
      int variableCount = BufferUtility.GetBlockProperty(shader, bufferIndex, 37636);
      int[] variables = new int[variableCount];
      BufferUtility.PropertyID[0] = 37637;
      BufferUtility.Count[0] = variableCount;
      GL43.glGetProgramResourceiv(shader, 37606, bufferIndex, PropertyID, Count, variables);
      return variables;
    }

    public static String GetMemberName(int shader, int varIndex) {
      int nameLength = BufferUtility.GetMemberProperty(shader, varIndex, 37625);
      return GL43.glGetProgramResourceName(shader, 37605, varIndex, nameLength);
    }

    public static int GetMemberProperty(int shader, int varIndex, int property) {
      BufferUtility.PropertyID[0] = property;
      BufferUtility.Count[0] = 1;
      GL43.glGetProgramResourceiv(shader, 37605, varIndex, PropertyID, Count, Results);
      return Results[0];
    }
  }

  private static class Uniform {
    public String name;
    public int size;
    public int type;
    public int location;

    private Uniform() {}
  }
}
