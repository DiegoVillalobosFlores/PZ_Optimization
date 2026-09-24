package zombie.core.skinnedmodel.model;

import gnu.trove.map.hash.TObjectIntHashMap;
import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjglx.BufferUtils;
import zombie.GameProfiler;
import zombie.GameProfiler.ProfileArea;
import zombie.asset.Asset;
import zombie.asset.AssetManager;
import zombie.asset.AssetPath;
import zombie.asset.AssetType;
import zombie.asset.AssetManager.AssetParams;
import zombie.characters.IsoGameCharacter;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.PZGLUtil;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.VBORenderer;
import zombie.core.rendering.RenderList;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.ModelInstance.EffectLight;
import zombie.core.skinnedmodel.model.ModelMesh.MeshAssetParams;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.Texture;
import zombie.debug.DebugOptions;
import zombie.iso.IsoLightSource;
import zombie.iso.Vector3;
import zombie.iso.sprite.SkyBox;
import zombie.scripting.objects.ModelScript;
import zombie.util.Lambda;
import zombie.util.StringUtils;
import zombie.util.Type;

public final class Model extends Asset {
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.skinnedmodel.model.Model"); // pzopt
   }

   public static final AssetType ASSET_TYPE = new AssetType("Model");
   public String name;
   public final ModelMesh mesh;
   public Shader effect;
   public Object tag;
   public boolean isStatic;
   public Texture tex;
   public SoftwareModelMesh softwareMesh;
   public static final FloatBuffer m_staticReusableFloatBuffer = BufferUtils.createFloatBuffer(128);
   private static final Matrix4f IDENTITY = new Matrix4f();
   public static TObjectIntHashMap<Model> modelDrawCounts = new TObjectIntHashMap();
   public static int instancingThreshold = 10;
   public static final Color[] debugDrawColours = new Color[]{
      new Color(230, 25, 75),
      new Color(60, 180, 75),
      new Color(255, 225, 25),
      new Color(0, 130, 200),
      new Color(245, 130, 48),
      new Color(145, 30, 180),
      new Color(70, 240, 240),
      new Color(240, 50, 230),
      new Color(210, 245, 60),
      new Color(250, 190, 190),
      new Color(0, 128, 128),
      new Color(230, 190, 255),
      new Color(170, 110, 40),
      new Color(255, 250, 200),
      new Color(128, 0, 0),
      new Color(170, 255, 195),
      new Color(128, 128, 0),
      new Color(255, 215, 180),
      new Color(0, 0, 128),
      new Color(128, 128, 128),
      new Color(255, 255, 255),
      new Color(0, 0, 0)
   };
   public Model.ModelAssetParams assetParams;

   public Model(AssetPath path, AssetManager manager, Model.ModelAssetParams params) {
      super(path, manager);
      this.assetParams = params;
      this.isStatic = this.assetParams != null && this.assetParams.isStatic;
      MeshAssetParams meshParams = new MeshAssetParams();
      meshParams.isStatic = this.isStatic;
      meshParams.animationsMesh = this.assetParams == null ? null : this.assetParams.animationsModel;
      meshParams.postProcess = this.assetParams == null ? null : this.assetParams.postProcess;
      this.mesh = (ModelMesh)MeshAssetManager.instance.load(new AssetPath(params.meshName), meshParams);
      if (!StringUtils.isNullOrWhitespace(params.textureName)) {
         if (params.textureName.contains("media/")) {
            this.tex = Texture.getSharedTexture(params.textureName, params.textureFlags);
         } else {
            this.tex = Texture.getSharedTexture("media/textures/" + params.textureName + ".png", params.textureFlags);
         }
      }

      if (!StringUtils.isNullOrWhitespace(params.shaderName)) {
         this.CreateShader(params.shaderName);
      }

      this.onCreated(this.mesh.getState());
      this.addDependency(this.mesh);
      if (this.isReady()) {
         this.tag = this.mesh.skinningData;
         this.softwareMesh = this.mesh.softwareMesh;
         this.assetParams = null;
      }
   }

   public static void vectorToWorldCoords(float x, float y, float z, float angle, Vector3 vec) {
      vec.x = -vec.x;
      vec.rotatey(angle);
      float y1 = vec.y;
      vec.y = vec.z;
      vec.z = y1;
      vec.x *= 1.5F;
      vec.y *= 1.5F;
      vec.z *= 0.61237234F;
      vec.x += x;
      vec.y += y;
      vec.z += z;
   }

   public static void boneToWorldCoords(AnimationPlayer animPlayer, float x, float y, float z, float animalSize, int boneIndex, Vector3 vec) {
      vec.x = animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m23;
      vec.x *= animalSize;
      vec.y *= animalSize;
      vec.z *= animalSize;
      vectorToWorldCoords(x, y, z, animPlayer.getRenderedAngle(), vec);
   }

   public static void vectorToWorldCoords(IsoGameCharacter character, Vector3 vec) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      float angle = animPlayer.getRenderedAngle();
      vectorToWorldCoords(character.getX(), character.getY(), character.getZ(), angle, vec);
   }

   public static void boneToWorldCoords(IsoGameCharacter character, int boneIndex, Vector3 vec) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      vec.x = animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m23;
      if (character instanceof IsoAnimal isoAnimal) {
         vec.x = vec.x * isoAnimal.getAnimalSize();
         vec.y = vec.y * isoAnimal.getAnimalSize();
         vec.z = vec.z * isoAnimal.getAnimalSize();
      }

      vectorToWorldCoords(character, vec);
   }

   public static void boneToWorldCoords(IsoGameCharacter character, SkinningBone bone, Vector3 vec) {
      boneToWorldCoords(character, bone.index, vec);
   }

   public static void boneZDirectionToWorldCoords(IsoGameCharacter character, int boneIndex, Vector3 vec, float length) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      vec.x = animPlayer.getModelTransformAt(boneIndex).m02 * length;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m12 * length;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m22 * length;
      vec.x = vec.x + animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = vec.y + animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = vec.z + animPlayer.getModelTransformAt(boneIndex).m23;
      vectorToWorldCoords(character, vec);
   }

   public static void boneZDirectionToWorldCoords(IsoGameCharacter character, SkinningBone bone, Vector3 vec, float length) {
      boneZDirectionToWorldCoords(character, bone.index, vec, length);
   }

   public static void boneYDirectionToWorldCoords(IsoGameCharacter character, int boneIndex, Vector3 vec, float length) {
      AnimationPlayer animPlayer = character.getAnimationPlayer();
      vec.x = animPlayer.getModelTransformAt(boneIndex).m01 * length;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m11 * length;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m21 * length;
      vec.x = vec.x + animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = vec.y + animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = vec.z + animPlayer.getModelTransformAt(boneIndex).m23;
      vectorToWorldCoords(character, vec);
   }

   public static void vectorToWorldCoords(ModelSlotRenderData slotData, Vector3 vec) {
      float angle = slotData.animPlayerAngle;
      vectorToWorldCoords(slotData.x, slotData.y, slotData.z, angle, vec);
   }

   public static void boneToWorldCoords(ModelSlotRenderData slotData, int boneIndex, Vector3 vec) {
      AnimationPlayer animPlayer = slotData.animPlayer;
      vec.x = animPlayer.getModelTransformAt(boneIndex).m03;
      vec.y = animPlayer.getModelTransformAt(boneIndex).m13;
      vec.z = animPlayer.getModelTransformAt(boneIndex).m23;
      vectorToWorldCoords(slotData, vec);
   }

   public static void boneToWorldCoords(ModelSlotRenderData slotData, SkinningBone bone, Vector3 vec) {
      boneToWorldCoords(slotData, bone.index, vec);
   }

   public static void worldToModel(IsoGameCharacter isoGameCharacter, Vector3 vector3) {
      AnimationPlayer animPlayer = isoGameCharacter.getAnimationPlayer();
      float angle = animPlayer.getRenderedAngle();
      vector3.x = vector3.x - isoGameCharacter.getX();
      vector3.y = vector3.y - isoGameCharacter.getY();
      vector3.z = vector3.z - isoGameCharacter.getZ();
      vector3.x /= 1.5F;
      vector3.y /= 1.5F;
      float t = vector3.z;
      vector3.z = vector3.y;
      vector3.y = t / 0.6F;
      vector3.rotatey(-angle);
      vector3.x = -vector3.x;
   }

   public static void CharacterModelCameraBegin(ModelSlotRenderData slotData) {
      ModelCamera.instance.Begin();
      if (slotData.inVehicle) {
         Matrix4f mv = Core.getInstance().modelViewMatrixStack.peek();
         mv.translate(0.0F, slotData.centerOfMassY, 0.0F);
         mv.rotate(slotData.vehicleAngleZ * (float) (Math.PI / 180.0), 0.0F, 0.0F, 1.0F);
         mv.rotate(slotData.vehicleAngleY * (float) (Math.PI / 180.0), 0.0F, 1.0F, 0.0F);
         mv.rotate(slotData.vehicleAngleX * (float) (Math.PI / 180.0), 1.0F, 0.0F, 0.0F);
         mv.rotate((float) Math.PI, 0.0F, 1.0F, 0.0F);
         int rightToLeftHand = -1;
         mv.translate(slotData.inVehicleX, slotData.inVehicleY, slotData.inVehicleZ * -1.0F);
         mv.scale(1.5F, 1.5F, 1.5F);
      }
   }

   public static void CharacterModelCameraEnd() {
      ModelCamera.instance.End();
   }

   private void DrawWireframe(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      GL11.glPolygonMode(1032, 6913);
      GL11.glEnable(2848);
      GL11.glLineWidth(0.75F);
      Shader effect2 = ShaderManager.instance.getOrCreateShader("vehicle_wireframe", this.isStatic, false);
      if (effect2 != null) {
         effect2.Start();
         if (this.isStatic) {
            effect2.setTransformMatrix(instData.xfrm, true);
         } else {
            effect2.setMatrixPalette(instData.matrixPalette, true);
         }

         float targetDepth = PerformanceSettings.fboRenderChunk
            ? (instData.modelInstance.parent == null ? instData.modelInstance.targetDepth : instData.modelInstance.parent.targetDepth)
            : 0.5F;
         effect2.setTargetDepth(targetDepth);
         this.mesh.Draw(effect2);
         effect2.End();
      }

      GL11.glPolygonMode(1032, 6914);
      GL11.glDisable(2848);
   }

   public static void SwapInstancedBasic() {
      modelDrawCounts.forEachEntry(Model::SwapInstancedBasic);
   }

   private static boolean SwapInstancedBasic(Model key, int value) {
      if (Core.debug && DebugOptions.instance.zombieRenderInstanced.getValue()) {
         if ((key.tex == null || !key.tex.getUseAlphaChannel()) && key.effect.getName().equals("basicEffect")) {
            if (value < instancingThreshold && key.effect.isInstanced()) {
               key.effect = ShaderManager.instance.getOrCreateShader(key.effect.getName(), key.effect.isStatic(), false);
            } else if (value >= instancingThreshold && !key.effect.isInstanced()) {
               key.effect = ShaderManager.instance.getOrCreateShader(key.effect.getName(), key.effect.isStatic(), true);
            }
         }
      } else if (key.effect.isInstanced()) {
         key.effect = ShaderManager.instance.getOrCreateShader(key.effect.getName(), key.effect.isStatic(), false);
      }

      return true;
   }

   public void EnsureEffect() {
      if (this.effect == null) {
         this.CreateShader("basicEffect");
      }
   }

   private void DrawSolid(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      if (this.effect == null) {
         this.CreateShader("basicEffect");
      }

      Shader effect = this.effect;
      int drawCount = modelDrawCounts.get(this);
      modelDrawCounts.put(this, drawCount + 1);
      if (this.effect.isInstanced()) {
         Matrix4f mvp = Core.getInstance().modelViewMatrixStack.alloc();
         Matrix4f mv = Core.getInstance().modelViewMatrixStack.peek();
         Matrix4f p = Core.getInstance().projectionMatrixStack.peek();
         p.mul(mv, mvp);
         instData.properties.SetMatrix4("mvp", mvp).transpose();
         if (slotData.IsRenderingToCard()) {
            RenderList.DrawImmediate(slotData, instData);
         } else {
            RenderList.DrawQueued(slotData, instData);
         }

         Core.getInstance().modelViewMatrixStack.release(mvp);
      } else {
         if (effect != null) {
            effect.Start();
            effect.startCharacter(slotData, instData);
            effect.setScale(slotData.finalScale);
         }

         if (!DebugOptions.instance.debugDrawSkipDrawNonSkinnedModel.getValue()) {
            ProfileArea mvp = GameProfiler.getInstance().profile("Mesh.Draw.Call");

            try {
               this.mesh.Draw(effect);
            } catch (Throwable var9) {
               if (mvp != null) {
                  try {
                     mvp.close();
                  } catch (Throwable var8) {
                     var9.addSuppressed(var8);
                  }
               }

               throw var9;
            }

            if (mvp != null) {
               mvp.close();
            }
         }

         if (effect != null) {
            effect.setScale(1.0F);
            effect.End();
         }
      }
   }

   public void DrawChar(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      if (!DebugOptions.instance.character.debug.render.skipCharacters.getValue()) {
         if (!(slotData.alpha < 0.01F)) {
            if (slotData.animPlayer != null) {
               if (this.effect == null) {
                  this.CreateShader("basicEffect");
               }

               ModelScript modelScript = instData.modelInstance.modelScript;
               if (modelScript == null || modelScript.cullFace == -1) {
                  GL11.glEnable(2884);
                  GL11.glCullFace(1028);
               } else if (modelScript.cullFace == 0) {
                  GL11.glDisable(2884);
               } else {
                  GL11.glEnable(2884);
                  GL11.glCullFace(modelScript.cullFace);
               }

               GL11.glEnable(2929);
               GL11.glEnable(3008);
               GL11.glDepthFunc(513);
               GL11.glAlphaFunc(516, 0.01F);
               GL11.glBlendFunc(770, 771);
               if (Core.debug && DebugOptions.instance.model.render.wireframe.getValue()) {
                  this.DrawWireframe(slotData, instData);
               } else {
                  this.DrawSolid(slotData, instData);
               }

               GLStateRenderThread.restore();
            }
         }
      }
   }

   private void drawVehicleLights(ModelSlotRenderData slotData) {
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.startRun(vbor.formatPositionColor);
      vbor.setMode(1);
      vbor.setLineWidth(1.0F);
      vbor.setDepthTest(false);

      for (int i = 0; i < slotData.effectLights.length; i++) {
         EffectLight el = slotData.effectLights[i];
         if (!(el.radius <= 0.0F)) {
            float x = el.x;
            float y = el.y;
            float z = el.z;
            float swap = z;
            z = y;
            y = swap;
            x *= -54.0F;
            y *= 54.0F;
            z *= 54.0F;
            vbor.addLine(0.0F, 0.0F, 0.0F, x, y, z, 1.0F, 1.0F, 0.0F, 1.0F);
         }
      }

      vbor.endRun();
      vbor.flush();
      GL11.glEnable(2929);
   }

   public static void drawBoneMtx(org.lwjgl.util.vector.Matrix4f boneMtx) {
      drawBoneMtxInternal(boneMtx);
      GL11.glColor3f(1.0F, 1.0F, 1.0F);
      GL11.glEnable(2929);
   }

   private static void drawBoneMtxInternal(org.lwjgl.util.vector.Matrix4f boneMtx) {
      float scale = 0.5F;
      float arrowHeadFraction = 0.15F;
      float arrowHeadRadiusFraction = 0.1F;
      float posx = boneMtx.m03;
      float posy = boneMtx.m13;
      float posz = boneMtx.m23;
      float xAxisx = boneMtx.m00;
      float xAxisy = boneMtx.m10;
      float xAxisz = boneMtx.m20;
      float yAxisx = boneMtx.m01;
      float yAxisy = boneMtx.m11;
      float yAxisz = boneMtx.m21;
      float zAxisx = boneMtx.m02;
      float zAxisy = boneMtx.m12;
      float zAxisz = boneMtx.m22;
      drawArrowInternal(posx, posy, posz, xAxisx, xAxisy, xAxisz, zAxisx, zAxisy, zAxisz, 0.5F, 0.15F, 0.1F, 1.0F, 0.0F, 0.0F);
      drawArrowInternal(posx, posy, posz, yAxisx, yAxisy, yAxisz, zAxisx, zAxisy, zAxisz, 0.5F, 0.15F, 0.1F, 0.0F, 1.0F, 0.0F);
      drawArrowInternal(posx, posy, posz, zAxisx, zAxisy, zAxisz, xAxisx, xAxisy, xAxisz, 0.5F, 0.15F, 0.1F, 0.0F, 0.0F, 1.0F);
   }

   private static void drawArrowInternal(
      float posx,
      float posy,
      float posz,
      float fwdAxisx,
      float fwdAxisy,
      float fwdAxisz,
      float leftAxisx,
      float leftAxisy,
      float leftAxisz,
      float scale,
      float arrowHeadLength,
      float arrowHeadRadius,
      float red,
      float green,
      float blue
   ) {
      float arrowHeadInvLength = 1.0F - arrowHeadLength;
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.addLine(posx, posy, posz, posx + fwdAxisx * scale, posy + fwdAxisy * scale, posz + fwdAxisz * scale, red, green, blue, 1.0F);
      vbor.addLine(
         posx + fwdAxisx * scale,
         posy + fwdAxisy * scale,
         posz + fwdAxisz * scale,
         posx + (fwdAxisx * arrowHeadInvLength + leftAxisx * arrowHeadRadius) * scale,
         posy + (fwdAxisy * arrowHeadInvLength + leftAxisy * arrowHeadRadius) * scale,
         posz + (fwdAxisz * arrowHeadInvLength + leftAxisz * arrowHeadRadius) * scale,
         red,
         green,
         blue,
         1.0F
      );
      vbor.addLine(
         posx + fwdAxisx * scale,
         posy + fwdAxisy * scale,
         posz + fwdAxisz * scale,
         posx + (fwdAxisx * arrowHeadInvLength - leftAxisx * arrowHeadRadius) * scale,
         posy + (fwdAxisy * arrowHeadInvLength - leftAxisy * arrowHeadRadius) * scale,
         posz + (fwdAxisz * arrowHeadInvLength - leftAxisz * arrowHeadRadius) * scale,
         red,
         green,
         blue,
         1.0F
      );
   }

   public void debugDrawLightSource(IsoLightSource ls, float cx, float cy, float cz, float radians) {
      debugDrawLightSource(ls.x, ls.y, ls.z, cx, cy, cz, radians);
   }

   public static void debugDrawLightSource(float lx, float ly, float lz, float cx, float cy, float cz, float radians) {
      float x = lx - cx + 0.5F;
      float y = ly - cy + 0.5F;
      float z = lz - cz + 0.0F;
      x *= 0.67F;
      y *= 0.67F;
      float vx = x;
      float vy = y;
      x = (float)(vx * Math.cos(radians) - vy * Math.sin(radians));
      y = (float)(vx * Math.sin(radians) + vy * Math.cos(radians));
      x *= -1.0F;
      float swap = z;
      z = y;
      y = swap;
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.startRun(vbor.formatPositionColor);
      vbor.setMode(1);
      vbor.setDepthTest(false);
      vbor.setLineWidth(1.0F);
      vbor.addLine(x, y, z, x + 0.1F, y, z, 1.0F, 1.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y + 0.1F, z, 1.0F, 1.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y, z + 0.1F, 1.0F, 1.0F, 0.0F, 1.0F);
      vbor.endRun();
      vbor.flush();
      GL11.glColor3f(1.0F, 1.0F, 1.0F);
      GL11.glEnable(2929);
   }

   public void DrawVehicle(ModelSlotRenderData slotData, ModelInstanceRenderData instData) {
      if (!DebugOptions.instance.model.render.skipVehicles.getValue()) {
         ModelInstance inst = instData.modelInstance;
         float ambientR = slotData.ambientR;
         float ambientG = slotData.ambientG;
         float ambientB = slotData.ambientB;
         Texture instTex = instData.tex;
         float tintR = instData.tintR;
         float tintG = instData.tintG;
         float tintB = instData.tintB;
         PZGLUtil.checkGLErrorThrow("Model.drawVehicle Enter inst: %s, instTex: %s, slotData: %s", new Object[]{inst, instTex, slotData});
         GL11.glEnable(2884);
         GL11.glCullFace(inst.modelScript != null && inst.modelScript.invertX ? 1029 : 1028);
         GL11.glEnable(2929);
         GL11.glDepthFunc(513);
         ModelCamera.instance.Begin();
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.peek();
         modelView.translate(0.0F, slotData.centerOfMassY, 0.0F);
         float depthBufferValue = VertexBufferObject.getDepthValueAt(0.0F, 0.0F, 0.0F);
         instData.modelInstance.targetDepth = slotData.squareDepth - (depthBufferValue + 1.0F) / 2.0F + 0.5F;
         Shader effect = this.effect;
         PZGLUtil.pushAndMultMatrix(5888, instData.xfrm);
         if (Core.debug && DebugOptions.instance.model.render.wireframe.getValue()) {
            GL11.glPolygonMode(1032, 6913);
            GL11.glEnable(2848);
            GL11.glLineWidth(0.75F);
            effect = ShaderManager.instance.getOrCreateShader("vehicle_wireframe", this.isStatic, false);
            if (effect != null) {
               effect.Start();
               if (this.isStatic) {
                  effect.setTransformMatrix(IDENTITY, false);
               } else {
                  effect.setMatrixPalette(instData.matrixPalette, true);
               }

               float targetDepth = PerformanceSettings.fboRenderChunk ? instData.modelInstance.targetDepth : 0.5F;
               effect.setTargetDepth(targetDepth);
               this.mesh.Draw(effect);
               effect.End();
            }

            GL11.glDisable(2848);
            PZGLUtil.popMatrix(5888);
            ModelCamera.instance.End();
         } else {
            if (effect != null) {
               effect.Start();
               this.setLights(slotData, slotData.effectLights.length);
               if (effect.isVehicleShader()) {
                  VehicleModelInstance vmi = (VehicleModelInstance)Type.tryCastTo(inst, VehicleModelInstance.class);
                  if (inst instanceof VehicleSubModelInstance) {
                     vmi = (VehicleModelInstance)Type.tryCastTo(inst.parent, VehicleModelInstance.class);
                  }

                  effect.setTexture(vmi.tex, "Texture0", 0);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureRust, "TextureRust", 1);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureMask, "TextureMask", 2);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureLights, "TextureLights", 3);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage1Overlay, "TextureDamage1Overlay", 4);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage1Shell, "TextureDamage1Shell", 5);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage2Overlay, "TextureDamage2Overlay", 6);
                  GL11.glTexEnvi(8960, 8704, 7681);
                  effect.setTexture(vmi.textureDamage2Shell, "TextureDamage2Shell", 7);
                  GL11.glTexEnvi(8960, 8704, 7681);

                  try {
                     if (Core.getInstance().getPerfReflectionsOnLoad() && !Core.getInstance().getUseOpenGL21()) {
                        effect.setTexture((Texture)SkyBox.getInstance().getTextureCurrent(), "TextureReflectionA", 8);
                        PZGLUtil.checkGLError(false);
                     }
                  } catch (Throwable var17) {
                  }

                  try {
                     if (Core.getInstance().getPerfReflectionsOnLoad() && !Core.getInstance().getUseOpenGL21()) {
                        effect.setTexture((Texture)SkyBox.getInstance().getTexturePrev(), "TextureReflectionB", 9);
                        PZGLUtil.checkGLError(false);
                     }
                  } catch (Throwable var16) {
                  }

                  effect.setReflectionParam(SkyBox.getInstance().getTextureShift(), vmi.refWindows, vmi.refBody);
                  effect.setTextureUninstall1(vmi.textureUninstall1);
                  effect.setTextureUninstall2(vmi.textureUninstall2);
                  effect.setTextureLightsEnables1(vmi.textureLightsEnables1);
                  effect.setTextureLightsEnables2(vmi.textureLightsEnables2);
                  effect.setTextureDamage1Enables1(vmi.textureDamage1Enables1);
                  effect.setTextureDamage1Enables2(vmi.textureDamage1Enables2);
                  effect.setTextureDamage2Enables1(vmi.textureDamage2Enables1);
                  effect.setTextureDamage2Enables2(vmi.textureDamage2Enables2);
                  effect.setMatrixBlood1(vmi.matrixBlood1Enables1, vmi.matrixBlood1Enables2);
                  effect.setMatrixBlood2(vmi.matrixBlood2Enables1, vmi.matrixBlood2Enables2);
                  effect.setTextureRustA(vmi.textureRustA);
                  effect.setTexturePainColor(vmi.painColor, slotData.alpha);
                  if (this.isStatic) {
                     effect.setTransformMatrix(IDENTITY, false);
                  } else {
                     effect.setMatrixPalette(instData.matrixPalette, true);
                  }
               } else if (inst instanceof VehicleSubModelInstance) {
                  GL13.glActiveTexture(33984);
                  effect.setTexture(instTex, "Texture", 0);
                  effect.setShaderAlpha(slotData.alpha);
                  if (this.isStatic) {
                     effect.setTransformMatrix(IDENTITY, false);
                  }
               } else {
                  GL13.glActiveTexture(33984);
                  effect.setTexture(instTex, "Texture", 0);
               }

               effect.setAmbient(ambientR, ambientG, ambientB);
               effect.setTint(tintR, tintG, tintB);
               float targetDepth = PerformanceSettings.fboRenderChunk ? instData.modelInstance.targetDepth : 0.5F;
               effect.setTargetDepth(targetDepth);
               boolean pzoptGlint = pzopt.HdrGlint.vehicleOn(effect.isVehicleShader()); // pzopt: HDR output, the car's speculars / lamps into the glint target
               this.mesh.Draw(effect);
               if (pzoptGlint) {
                  pzopt.HdrGlint.vehicleOff(); // pzopt: HDR output
               }
               effect.End();
            }

            if (Core.debug && DebugOptions.instance.model.render.lights.getValue() && instData == slotData.modelData.get(0)) {
               this.drawVehicleLights(slotData);
            }

            PZGLUtil.popMatrix(5888);
            ModelCamera.instance.End();
            PZGLUtil.checkGLErrorThrow("Model.drawVehicle Exit inst: %s, instTex: %s, slotData: %s", new Object[]{inst, instTex, slotData});
         }
      }
   }

   public static void debugDrawAxis(float x, float y, float z, boolean flipX, boolean flipY, boolean flipZ, float length, float thickness) {
      VBORenderer vbor = VBORenderer.getInstance();
      vbor.startRun(vbor.formatPositionColor);
      vbor.setMode(1);
      vbor.setDepthTest(false);
      vbor.setLineWidth(Math.min(thickness, 1.0F));
      vbor.addLine(x, y, z, x + length * (flipX ? -1 : 1), y, z, 1.0F, 0.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y + length * (flipY ? -1 : 1), z, 0.0F, 1.0F, 0.0F, 1.0F);
      vbor.addLine(x, y, z, x, y, z + length * (flipZ ? -1 : 1), 0.0F, 0.0F, 1.0F, 1.0F);
      vbor.endRun();
      vbor.flush();
      GL11.glColor3f(1.0F, 1.0F, 1.0F);
      GL11.glEnable(2929);
   }

   public static void debugDrawAxis(float x, float y, float z, float length, float thickness) {
      debugDrawAxis(x, y, z, false, false, false, length, thickness);
   }

   public void setLights(ModelSlotRenderData slotData, int lightCount) {
      for (int i = 0; i < lightCount; i++) {
         EffectLight el = slotData.effectLights[i];
         this.effect.setLight(i, el.x, el.y, el.z, el.r, el.g, el.b, el.radius, slotData.animPlayerAngle, slotData.x, slotData.y, slotData.z, slotData.object);
      }

      for (int i = lightCount; i < 5; i++) {
         this.effect.setLight(i, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, null);
      }
   }

   public void setLightsInst(ModelSlotRenderData slotData, int lightCount) {
      for (int i = 0; i < lightCount; i++) {
         EffectLight el = slotData.effectLights[i];
         this.effect
            .setLightInst(i, el.x, el.y, el.z, el.r, el.g, el.b, el.radius, slotData.animPlayerAngle, slotData.x, slotData.y, slotData.z, slotData.properties);
      }

      for (int i = lightCount; i < 5; i++) {
         this.effect.setLightInst(i, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, null);
      }
   }

   public void CreateShader(String name) {
      if (!ModelManager.noOpenGL) {
         // pzopt: a shader an earlier model already created is taken from pzopt.ModelShaders; the blocking round trip to
         // pzopt: the render thread (one render step per model, 16.5 s for the 73 animal models on a laptop) is only paid
         // pzopt: by the first model per shader name (Config shaderCache)
         if (pzopt.ModelShaders.enabled()) {
            Shader cached = pzopt.ModelShaders.get(name, this.isStatic); // pzopt
            if (cached != null) { // pzopt
               this.effect = cached; // pzopt
               return; // pzopt
            }
         }

         long t0 = System.nanoTime(); // pzopt
         Lambda.invoke(
            RenderThread::invokeOnRenderContext,
            this,
            name,
            (lThis, lName) -> lThis.effect = ShaderManager.instance.getOrCreateShader(lName, lThis.isStatic, false)
         );
         pzopt.ModelShaders.created(name, this.isStatic, this.effect, System.nanoTime() - t0); // pzopt
      }
   }

   public AssetType getType() {
      return ASSET_TYPE;
   }

   protected void onBeforeReady() {
      super.onBeforeReady();
      this.tag = this.mesh.skinningData;
      this.softwareMesh = this.mesh.softwareMesh;
      this.assetParams = null;
   }

   public static final class ModelAssetParams extends AssetParams {
      public String meshName;
      public String textureName;
      public int textureFlags;
      public String shaderName;
      public boolean isStatic;
      public ModelMesh animationsModel;
      public String postProcess;
   }
}
