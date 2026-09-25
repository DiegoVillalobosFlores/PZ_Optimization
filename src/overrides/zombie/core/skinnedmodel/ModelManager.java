package zombie.core.skinnedmodel;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.Map.Entry;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjglx.opengl.Display;
import zombie.DebugFileWatcher;
import zombie.GameWindow;
import zombie.PredicatedFileWatcher;
import zombie.ZomboidFileSystem;
import zombie.PredicatedFileWatcher.IPredicatedFileWatcherCallback;
import zombie.asset.AssetPath;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.SurvivorFactory;
import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.AttachedItems.AttachedModels;
import zombie.characters.CharacterTimedActions.BaseAction;
import zombie.characters.WornItems.BodyLocations;
import zombie.characters.animals.IsoAnimal;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.logger.ExceptionLogger;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.PZGLUtil;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderPrograms;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.AnimationAsset;
import zombie.core.skinnedmodel.model.AnimationAssetManager;
import zombie.core.skinnedmodel.model.MeshAssetManager;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelAssetManager;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceTextureInitializer;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.VehicleModelInstance;
import zombie.core.skinnedmodel.model.VehicleSubModelInstance;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.model.AnimationAsset.AnimationAssetParams;
import zombie.core.skinnedmodel.model.Model.ModelAssetParams;
import zombie.core.skinnedmodel.model.ModelInstance.EffectLight;
import zombie.core.skinnedmodel.model.ModelMesh.MeshAssetParams;
import zombie.core.skinnedmodel.population.PopTemplateManager;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.core.textures.TextureID;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.entity.ComponentType;
import zombie.gameStates.ChooseGameInfo;
import zombie.gameStates.ChooseGameInfo.Mod;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponPart;
import zombie.iso.FireShader;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPuddles;
import zombie.iso.IsoWater;
import zombie.iso.IsoWorld;
import zombie.iso.ParticlesFire;
import zombie.iso.PuddlesShader;
import zombie.iso.SmokeShader;
import zombie.iso.WaterShader;
import zombie.iso.IsoGridSquare.ILighting;
import zombie.iso.IsoGridSquare.ResultLight;
import zombie.iso.sprite.SkyBox;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerGUI;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.AnimationsMesh;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ItemReplacement;
import zombie.scripting.objects.ModelScript;
import zombie.scripting.objects.ModelWeaponPart;
import zombie.scripting.objects.ResourceLocation;
import zombie.scripting.objects.VehicleScript;
import zombie.scripting.objects.VehicleScript.Skin;
import zombie.util.ReferencedObjectPool;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.BaseVehicle.ModelInfo;

public final class ModelManager {
   public static boolean noOpenGL;
   public static final ModelManager instance = new ModelManager();

   static {
      pzopt.Overrides.onClassLoadedQuiet("zombie.core.skinnedmodel.ModelManager"); // pzopt: marker so the game log shows the loose class was loaded
   }
   private final HashMap<String, Model> modelMap = new HashMap<>();
   public Model maleModel;
   public Model femaleModel;
   public Model skeletonMaleModel;
   public Model skeletonFemaleModel;
   public TextureFBO bitmap;
   private boolean created;
   public boolean debugEnableModels = true;
   private final ArrayList<ModelManager.ModelSlot> modelSlots = new ArrayList<>();
   private final ReferencedObjectPool<ModelInstance> modelInstancePool = new ReferencedObjectPool(ModelInstance::new, ModelInstance.class.getSimpleName());
   private ModelMesh animModel;
   private final HashMap<String, AnimationAsset> animationAssets = new HashMap<>();
   private final ModelManager.ModAnimations gameAnimations = new ModelManager.ModAnimations("game");
   private final HashMap<String, ModelManager.ModAnimations> modAnimations = new HashMap<>();
   private final HashSet<IsoGameCharacter> contains = new HashSet<>();
   private final ArrayList<IsoGameCharacter> toRemove = new ArrayList<>();
   private final ArrayList<IsoGameCharacter> toResetNextFrame = new ArrayList<>();
   private final ArrayList<IsoGameCharacter> toResetEquippedNextFrame = new ArrayList<>();
   private final ArrayList<ModelManager.ModelSlot> resetAfterRender = new ArrayList<>();
   private static final TreeMap<String, ModelManager.ModelMetaData> modelMetaData = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
   private static final String basicEffect = "basicEffect";
   private static final String isStaticTrue = ";isStatic=true";
   private static final String shaderEquals = "shader=";
   private static final String texA = ";tex=";
   private static final String amp = "&";
   private static final HashMap<String, String> toLower = new HashMap<>();
   private static final HashMap<String, String> toLowerTex = new HashMap<>();
   private static final HashMap<String, String> toLowerKeyRoot = new HashMap<>();
   private static final StringBuilder builder = new StringBuilder();

   public boolean isCreated() {
      return this.created;
   }

   public void create() {
      if (!this.created) {
         if (!GameServer.server || ServerGUI.isCreated()) {
            Texture tex = new Texture(1024, 1024, 16);
            Texture depth = new Texture(1024, 1024, 512);
            PerformanceSettings.useFbos = false;

            try {
               this.bitmap = new TextureFBO(tex, depth, false);
            } catch (Exception e) {
               DebugType.General.printException(e, LogSeverity.Error, "FBO not compatible with gfx card at this time.", new Object[0]);
               PerformanceSettings.useFbos = false;
               DebugType.Animation.error("FBO not compatible with gfx card at this time.");
               return;
            }
         }

         DebugType.Animation.println("Loading 3D models");
         this.initAnimationMeshes(false);
         this.modAnimations.put(this.gameAnimations.modId, this.gameAnimations);
         AnimationsMesh animationsMesh = ScriptManager.instance.getAnimationsMesh("Human");
         ModelMesh animationsModel = animationsMesh.modelMesh;
         Model maleModel = this.loadModel("skinned/malebody", null, animationsModel);
         Model femaleModel = this.loadModel("skinned/femalebody", null, animationsModel);
         Model skeletonMaleModel = this.loadModel("skinned/Male_Skeleton", null, animationsModel);
         Model skeletonFemaleModel = this.loadModel("skinned/Female_Skeleton", null, animationsModel);
         this.animModel = animationsModel;
         this.loadModAnimations();
         maleModel.addDependency(this.getAnimationAssetRequired("bob/bob_idle"));
         maleModel.addDependency(this.getAnimationAssetRequired("bob/bob_walk"));
         maleModel.addDependency(this.getAnimationAssetRequired("bob/bob_run"));
         femaleModel.addDependency(this.getAnimationAssetRequired("bob/bob_idle"));
         femaleModel.addDependency(this.getAnimationAssetRequired("bob/bob_walk"));
         femaleModel.addDependency(this.getAnimationAssetRequired("bob/bob_run"));
         this.maleModel = maleModel;
         this.femaleModel = femaleModel;
         this.skeletonMaleModel = skeletonMaleModel;
         this.skeletonFemaleModel = skeletonFemaleModel;
         this.created = true;
         AdvancedAnimator.systemInit();
         PopTemplateManager.instance.init();
      }
   }

   public void loadAdditionalModel(String meshName, String tex, boolean bStatic, String shaderName) {
      this.loadModelInternal(meshName, tex, shaderName, this.animModel, bStatic, null);
   }

   public ModelInstance newAdditionalModelInstance(String meshName, String tex, IsoGameCharacter chr, AnimationPlayer animPlayer, String shaderName) {
      Model model = this.tryGetLoadedModel(meshName, tex, false, shaderName, false);
      if (model == null) {
         boolean bStatic = false;
         instance.loadAdditionalModel(meshName, tex, false, shaderName);
      }

      model = this.getLoadedModel(meshName, tex, false, shaderName);
      return this.newInstance(model, chr, animPlayer);
   }

   private void loadAnimsFromDir(String dir, ModelMesh animationsModel, ArrayList<String> prefixes) {
      File file = new File(ZomboidFileSystem.instance.base.canonicalFile, dir);
      this.loadAnimsFromDir(
         ZomboidFileSystem.instance.base.lowercaseUri, ZomboidFileSystem.instance.getMediaLowercaseURI(), file, animationsModel, this.gameAnimations, prefixes
      );
   }

   private void loadAnimsFromDir(
      URI baseURI, URI mediaURI, File dir, ModelMesh animationsModel, ModelManager.ModAnimations modAnimations, ArrayList<String> prefixes
   ) {
      if (!dir.exists()) {
         DebugType.General.error("ERROR: %s", new Object[]{dir.getPath()});

         for (File parentDir = dir.getParentFile(); parentDir != null; parentDir = parentDir.getParentFile()) {
            DebugType.General.error(" - Parent exists: %B, %s", new Object[]{parentDir.exists(), parentDir.getPath()});
         }
      }

      if (dir.isDirectory()) {
         File[] children = dir.listFiles();
         if (children != null) {
            boolean bHasAnims = false;

            for (File child : children) {
               if (child.isDirectory()) {
                  this.loadAnimsFromDir(baseURI, mediaURI, child, animationsModel, modAnimations, prefixes);
               } else if (this.checkAnimationMeshPrefixes(child.getName(), prefixes)) {
                  String animName = ZomboidFileSystem.instance.getAnimName(mediaURI, child);
                  this.loadAnim(animName, animationsModel, modAnimations);
                  bHasAnims = true;
                  if (!noOpenGL && RenderThread.renderThread == null) {
                     Display.processMessages();
                  }
               }
            }

            if (bHasAnims) {
               DebugFileWatcher.instance
                  .add(new ModelManager.AnimDirReloader(baseURI, mediaURI, dir.getPath(), animationsModel, modAnimations).GetFileWatcher());
            }
         }
      }
   }

   private boolean checkAnimationMeshPrefixes(String fileName, ArrayList<String> prefixes) {
      if (prefixes == null) {
         return true;
      }

      if (prefixes.isEmpty()) {
         return true;
      }

      boolean prefixOK = false;

      for (String prefix : prefixes) {
         if (prefix.startsWith("!") && StringUtils.startsWithIgnoreCase(fileName, prefix.substring(1))) {
            return false;
         }

         if (StringUtils.startsWithIgnoreCase(fileName, prefix)) {
            prefixOK = true;
         }
      }

      return prefixOK;
   }

   public void RenderSkyBox(TextureDraw texd, int shaderID, int userId, int apiId, int bufferId) {
      int lastID = TextureFBO.getCurrentID();
      switch (apiId) {
         case 1:
            GL30.glBindFramebuffer(36160, bufferId);
            break;
         case 2:
            ARBFramebufferObject.glBindFramebuffer(36160, bufferId);
            break;
         case 3:
            EXTFramebufferObject.glBindFramebufferEXT(36160, bufferId);
      }

      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
      projection.setOrtho(0.0F, 1.0F, 1.0F, 0.0F, -1.0F, 1.0F);
      Core.getInstance().projectionMatrixStack.push(projection);
      GL11.glViewport(0, 0, 512, 512);
      GL11.glClear(16384);
      Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
      modelView.identity();
      Core.getInstance().modelViewMatrixStack.push(modelView);
      ShaderHelper.glUseProgramObjectARB(shaderID);
      if (Shader.ShaderMap.containsKey(shaderID)) {
         ((Shader)Shader.ShaderMap.get(shaderID)).startRenderThread(texd);
      }

      VBORenderer vbor = VBORenderer.getInstance();
      vbor.startRun(vbor.formatPositionColorUv);
      vbor.setMode(7);
      vbor.setShaderProgram(ShaderPrograms.getInstance().getProgramByID(shaderID));
      vbor.addQuad(0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.13F, 0.96F, 0.13F, 1.0F);
      vbor.endRun();
      vbor.flush();
      ShaderHelper.forgetCurrentlyBound();
      ShaderHelper.glUseProgramObjectARB(0);
      Core.getInstance().projectionMatrixStack.pop();
      Core.getInstance().modelViewMatrixStack.pop();
      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      switch (apiId) {
         case 1:
            GL30.glBindFramebuffer(36160, lastID);
            break;
         case 2:
            ARBFramebufferObject.glBindFramebuffer(36160, lastID);
            break;
         case 3:
            EXTFramebufferObject.glBindFramebufferEXT(36160, lastID);
      }

      SkyBox.getInstance().swapTextureFBO();
   }

   public void RenderWater(TextureDraw texd, int shaderID, int playerIndex, int firstSquare, int numSquares, boolean bShore) {
      try {
         PZGLUtil.checkGLError(false);
      } catch (Throwable var11) {
      }

      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
      IsoWater.getInstance().waterProjection(projection);
      Core.getInstance().projectionMatrixStack.push(projection);
      Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
      modelView.identity();
      Core.getInstance().modelViewMatrixStack.push(modelView);
      ShaderHelper.glUseProgramObjectARB(shaderID);
      Shader shader = (Shader)Shader.ShaderMap.get(shaderID);
      if (shader instanceof WaterShader waterShader) {
         waterShader.updateWaterParams(texd, playerIndex);
      }

      VertexBufferObject.setModelViewProjection(shader.getProgram());
      pzopt.ObjectMotion.beginWaterStencil(); // pzopt: dlssWaterCurrent, the water's pixels tagged in the stencil (glPopAttrib below restores it)
      IsoWater.getInstance().waterGeometry(firstSquare, numSquares, bShore);
      ShaderHelper.glUseProgramObjectARB(0);
      Core.getInstance().projectionMatrixStack.pop();
      Core.getInstance().modelViewMatrixStack.pop();
      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      if (!PZGLUtil.checkGLError(true)) {
         DebugType.General.println("DEBUG: EXCEPTION RenderWater");
         PZGLUtil.printGLState(DebugType.General);
      }

      GLStateRenderThread.restore();
   }

   public void RenderPuddles(int playerIndex, int z, int firstSquare, int numSquares) {
      PZGLUtil.checkGLError(true);
      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
      IsoPuddles.getInstance().puddlesProjection(projection);
      Core.getInstance().projectionMatrixStack.push(projection);
      Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
      modelView.identity();
      Core.getInstance().modelViewMatrixStack.push(modelView);
      int shaderID = IsoPuddles.getInstance().effect.getID();
      ShaderHelper.glUseProgramObjectARB(shaderID);
      Shader shader = IsoPuddles.getInstance().effect;
      if (shader instanceof PuddlesShader puddlesShader) {
         puddlesShader.updatePuddlesParams(playerIndex, z);
      }

      VertexBufferObject.setModelViewProjection(shader.getProgram());
      IsoPuddles.getInstance().puddlesGeometry(firstSquare, numSquares);
      ShaderHelper.glUseProgramObjectARB(0);
      Core.getInstance().projectionMatrixStack.pop();
      Core.getInstance().modelViewMatrixStack.pop();
      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      GLStateRenderThread.restore();
      if (!PZGLUtil.checkGLError(true)) {
         DebugType.General.println("DEBUG: EXCEPTION RenderPuddles");
         PZGLUtil.printGLState(DebugType.General);
      }
   }

   public void RenderParticles(TextureDraw texd, int userId, int va11) {
      int fireShaderID = ParticlesFire.getInstance().getFireShaderID();
      int smokeShaderID = ParticlesFire.getInstance().getSmokeShaderID();

      try {
         PZGLUtil.checkGLError(false);
      } catch (Throwable var9) {
      }

      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      GL11.glMatrixMode(5889);
      GL11.glPushMatrix();
      GL11.glLoadIdentity();
      GL11.glViewport( // pzopt: upscaler, the stock offscreen-size viewport scaled like the rest of the world pass
         0,
         0,
         (int)pzopt.RenderScale.viewPx(SpriteRenderer.instance.getRenderingPlayerCamera(userId).offscreenWidth),
         (int)pzopt.RenderScale.viewPx(SpriteRenderer.instance.getRenderingPlayerCamera(userId).offscreenHeight)
      );
      GL11.glMatrixMode(5888);
      GL11.glPushMatrix();
      GL11.glLoadIdentity();
      float firetime = ParticlesFire.getInstance().getShaderTime();
      GL11.glBlendFunc(770, 1);
      ShaderHelper.glUseProgramObjectARB(fireShaderID);
      Shader shader = (Shader)Shader.ShaderMap.get(fireShaderID);
      if (shader instanceof FireShader fireShader) {
         fireShader.updateFireParams(texd, userId, firetime);
      }

      ParticlesFire.getInstance().getGeometryFire(va11);
      GL11.glBlendFunc(770, 771);
      ShaderHelper.glUseProgramObjectARB(smokeShaderID);
      shader = (Shader)Shader.ShaderMap.get(smokeShaderID);
      if (shader instanceof SmokeShader smokeShader) {
         smokeShader.updateSmokeParams(texd, userId, firetime);
      }

      ParticlesFire.getInstance().getGeometry(va11);
      GL20.glUseProgram(0);
      GL11.glMatrixMode(5888);
      GL11.glPopMatrix();
      GL11.glMatrixMode(5889);
      GL11.glPopMatrix();
      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      Texture.lastTextureID = -1;
      GL11.glViewport( // pzopt: upscaler, the stock offscreen-size viewport scaled like the rest of the world pass
         0,
         0,
         (int)pzopt.RenderScale.viewPx(SpriteRenderer.instance.getRenderingPlayerCamera(userId).offscreenWidth),
         (int)pzopt.RenderScale.viewPx(SpriteRenderer.instance.getRenderingPlayerCamera(userId).offscreenHeight)
      );
      if (!PZGLUtil.checkGLError(true)) {
         DebugType.General.println("DEBUG: EXCEPTION RenderParticles");
         PZGLUtil.printGLState(DebugType.General);
      }
   }

   public void Reset(IsoGameCharacter chr) {
      if (chr.legsSprite != null && chr.legsSprite.modelSlot != null) {
         ModelManager.ModelSlot modelSlot = chr.legsSprite.modelSlot;
         this.resetModelInstance(modelSlot.model, modelSlot);

         for (int i = 0; i < modelSlot.sub.size(); i++) {
            ModelInstance inst = modelSlot.sub.get(i);
            if (inst != chr.primaryHandModel && inst != chr.secondaryHandModel && !modelSlot.attachedModels.contains(inst)) {
               this.resetModelInstanceRecurse(inst, modelSlot);
            }
         }

         this.derefModelInstances(chr.getReadyModelData());
         chr.getReadyModelData().clear();
         this.dressInRandomOutfit(chr);
         Model model = this.getBodyModel(chr);
         modelSlot.model = this.newInstance(model, chr, chr.getAnimationPlayer());
         modelSlot.model.setOwner(modelSlot);
         modelSlot.model.modelScript = chr.getVisual().getModelScript();
         this.DoCharacterModelParts(chr, modelSlot);
      }
   }

   public void reloadAllOutfits() {
      for (IsoGameCharacter chr : this.contains) {
         chr.reloadOutfit();
      }
   }

   public void Add(IsoGameCharacter chr) {
      if (this.created) {
         if (!chr.isAddedToModelManager()) {
            if (this.toRemove.contains(chr)) {
               this.toRemove.remove(chr);
               chr.legsSprite.modelSlot.remove = false;
            } else {
               ModelManager.ModelSlot slot = this.getSlot(chr);
               slot.framesSinceStart = 0;
               if (slot.model != null) {
                  RenderThread.invokeOnRenderContext(slot.model::destroySmartTextures);
               }

               this.dressInRandomOutfit(chr);
               Model model = this.getBodyModel(chr);
               slot.model = this.newInstance(model, chr, chr.getAnimationPlayer());
               slot.model.setOwner(slot);
               slot.model.modelScript = chr.getVisual().getModelScript();
               this.DoCharacterModelParts(chr, slot);
               slot.active = true;
               slot.character = chr;
               slot.model.character = chr;
               slot.model.object = chr;
               slot.model.SetForceDir(slot.model.character.getForwardDirection());

               for (int i = 0; i < slot.sub.size(); i++) {
                  ModelInstance modelInstance = slot.sub.get(i);
                  modelInstance.character = chr;
                  modelInstance.object = chr;
               }

               chr.legsSprite.modelSlot = slot;
               this.contains.add(chr);
               chr.setAddedToModelManager(this, true);
               if (slot.model.animPlayer != null && slot.model.animPlayer.isBoneTransformsNeedFirstFrame()) {
                  try {
                     slot.Update(chr.getAnimationTimeDelta());
                  } catch (Throwable t) {
                     ExceptionLogger.logException(t);
                  }
               }
            }
         }
      }
   }

   public void dressInRandomOutfit(IsoGameCharacter chr) {
      IsoZombie zombieChr = (IsoZombie)Type.tryCastTo(chr, IsoZombie.class);
      if (zombieChr != null && !zombieChr.isReanimatedPlayer() && !zombieChr.wasFakeDead()) {
         if (DebugOptions.instance.zombieOutfitRandom.getValue() && !chr.isPersistentOutfitInit()) {
            zombieChr.dressInRandomOutfit = true;
         }

         if (zombieChr.dressInRandomOutfit) {
            zombieChr.getDescriptor().setForename(SurvivorFactory.getRandomForename(zombieChr.isFemale()));
            zombieChr.dressInRandomOutfit = false;
            zombieChr.dressInRandomOutfit();
         }

         if (!chr.isPersistentOutfitInit()) {
            zombieChr.dressInPersistentOutfitID(chr.getPersistentOutfitID());
         }
      } else {
         if (GameClient.client && zombieChr != null && !chr.isPersistentOutfitInit() && chr.getPersistentOutfitID() != 0) {
            zombieChr.dressInPersistentOutfitID(chr.getPersistentOutfitID());
         }
      }
   }

   public Model getBodyModel(IsoGameCharacter chr) {
      if (chr.isAnimal()) {
         return chr.getVisual().getModel();
      } else if (chr.isZombie() && ((IsoZombie)chr).isSkeleton()) {
         return chr.isFemale() ? this.skeletonFemaleModel : this.skeletonMaleModel;
      } else {
         return chr.isFemale() ? this.femaleModel : this.maleModel;
      }
   }

   public boolean ContainsChar(IsoGameCharacter chr) {
      return this.contains.contains(chr) && !this.toRemove.contains(chr);
   }

   public void ResetCharacterEquippedHands(IsoGameCharacter chr) {
      if (chr != null && chr.legsSprite != null && chr.legsSprite.modelSlot != null) {
         this.DoCharacterModelEquipped(chr, chr.legsSprite.modelSlot);
      }
   }

   public boolean shouldHideModel(ItemVisuals itemVisuals, ItemBodyLocation itemBodyLocation) {
      return BodyLocations.getGroup("Human").getLocation(itemBodyLocation) != null
         && PopTemplateManager.instance.isItemModelHidden(itemVisuals, itemBodyLocation);
   }

   private void DoCharacterModelEquipped(IsoGameCharacter chr, ModelManager.ModelSlot slot) {
      if (chr.primaryHandModel != null) {
         chr.clearVariable("RightHandMask");
         chr.primaryHandModel.maskVariableValue = null;
         this.resetModelInstanceRecurse(chr.primaryHandModel, slot);
         slot.sub.remove(chr.primaryHandModel);
         slot.model.sub.remove(chr.primaryHandModel);
         chr.primaryHandModel = null;
      }

      if (chr.secondaryHandModel != null) {
         chr.clearVariable("LeftHandMask");
         chr.secondaryHandModel.maskVariableValue = null;
         this.resetModelInstanceRecurse(chr.secondaryHandModel, slot);
         slot.sub.remove(chr.secondaryHandModel);
         slot.model.sub.remove(chr.secondaryHandModel);
         chr.secondaryHandModel = null;
      }

      for (int i = 0; i < slot.attachedModels.size(); i++) {
         ModelInstance modelInstance = (ModelInstance)slot.attachedModels.get(i);
         this.resetModelInstanceRecurse(modelInstance, slot);
         slot.sub.remove(modelInstance);
         slot.model.sub.remove(modelInstance);
      }

      slot.attachedModels.clear();
      ItemVisuals itemVisuals = new ItemVisuals();
      chr.getItemVisuals(itemVisuals);

      for (int i = 0; i < chr.getAttachedItems().size(); i++) {
         AttachedItem attachedItem = chr.getAttachedItems().get(i);
         String modelName = attachedItem.getItem().getStaticModelException();
         if (!StringUtils.isNullOrWhitespace(modelName)) {
            String attachmentName = chr.getAttachedItems().getGroup().getLocation(attachedItem.getLocation()).getAttachmentName();
            if (!this.shouldHideModel(itemVisuals, ItemBodyLocation.get(ResourceLocation.of(attachmentName)))) {
               ModelInstance modelInstance = this.addStatic(slot.model, modelName, attachmentName, attachmentName);
               if (modelInstance != null) {
                  modelInstance.setOwner(slot);
                  slot.sub.add(modelInstance);
                  if (attachedItem.getItem() instanceof HandWeapon weapon) {
                     this.addWeaponPartModels(slot, weapon, modelInstance);
                     if (!Core.getInstance().getOptionSimpleWeaponTextures()) {
                        ModelInstanceTextureInitializer textureInitializer = ModelInstanceTextureInitializer.alloc();
                        textureInitializer.init(modelInstance, weapon);
                        modelInstance.setTextureInitializer(textureInitializer);
                     }
                  } else if (this.requiresSmartTexture(attachedItem.getItem())) {
                     ModelInstanceTextureInitializer textureInitializer = ModelInstanceTextureInitializer.alloc();
                     textureInitializer.init(modelInstance, attachedItem.getItem());
                     modelInstance.setTextureInitializer(textureInitializer);
                  }

                  slot.attachedModels.add(modelInstance);
               }
            }
         }
      }

      InventoryItem primaryItem = chr.getPrimaryHandItem();
      InventoryItem secondaryItem = chr.getSecondaryHandItem();
      if (chr.isHideWeaponModel()) {
         primaryItem = null;
         secondaryItem = null;
      }

      if (chr.isHideEquippedHandL()) {
         secondaryItem = null;
      }

      if (chr.isHideEquippedHandR()) {
         primaryItem = null;
      }

      if (chr instanceof IsoPlayer && chr.forceNullOverride) {
         primaryItem = null;
         secondaryItem = null;
      }

      boolean overrideHandModels = false;
      BaseAction action = chr.getCharacterActions().isEmpty() ? null : (BaseAction)chr.getCharacterActions().get(0);
      if (action != null && action.overrideHandModels) {
         overrideHandModels = true;
         primaryItem = null;
         if (action.getPrimaryHandItem() != null) {
            primaryItem = action.getPrimaryHandItem();
         } else if (action.getPrimaryHandMdl() != null) {
            chr.primaryHandModel = this.addStatic(slot, action.getPrimaryHandMdl(), "Bip01_Prop1");
         }

         secondaryItem = null;
         if (action.getSecondaryHandItem() != null) {
            secondaryItem = action.getSecondaryHandItem();
         } else if (action.getSecondaryHandMdl() != null) {
            chr.secondaryHandModel = this.addStatic(slot, action.getSecondaryHandMdl(), "Bip01_Prop2");
         }
      }

      if (!StringUtils.isNullOrEmpty(chr.overridePrimaryHandModel)) {
         overrideHandModels = true;
         chr.primaryHandModel = this.addStatic(slot, chr.overridePrimaryHandModel, "Bip01_Prop1");
      }

      if (!StringUtils.isNullOrEmpty(chr.overrideSecondaryHandModel)) {
         overrideHandModels = true;
         chr.secondaryHandModel = this.addStatic(slot, chr.overrideSecondaryHandModel, "Bip01_Prop2");
      }

      if (primaryItem != null) {
         ItemReplacement replacement = primaryItem.getItemReplacementPrimaryHand();
         chr.primaryHandModel = this.addEquippedModelInstance(chr, slot, primaryItem, "Bip01_Prop1", replacement, overrideHandModels);
      }

      if (secondaryItem != null && primaryItem != secondaryItem) {
         ItemReplacement replacement = secondaryItem.getItemReplacementSecondHand();
         chr.secondaryHandModel = this.addEquippedModelInstance(chr, slot, secondaryItem, "Bip01_Prop2", replacement, overrideHandModels);
      }
   }

   private ModelInstance addEquippedModelInstance(
      IsoGameCharacter chr, ModelManager.ModelSlot slot, InventoryItem item, String boneName, ItemReplacement replacement, boolean overrideHandModels
   ) {
      HandWeapon weapon = (HandWeapon)Type.tryCastTo(item, HandWeapon.class);
      if (item.getClothingItem() == null && weapon != null && item.getClothingItem() == null) {
         String weaponSprite = weapon.getStaticModel();
         ModelInstance modelInstance = this.addStatic(slot, weaponSprite, boneName);
         this.addWeaponPartModels(slot, weapon, modelInstance);
         if (Core.getInstance().getOptionSimpleWeaponTextures()) {
            return modelInstance;
         }

         ModelInstanceTextureInitializer textureInitializer = ModelInstanceTextureInitializer.alloc();
         textureInitializer.init(modelInstance, weapon);
         modelInstance.setTextureInitializer(textureInitializer);
         return modelInstance;
      } else {
         if (item != null) {
            if (replacement != null
               && !StringUtils.isNullOrEmpty(replacement.maskVariableValue)
               && (replacement.clothingItem != null || !StringUtils.isNullOrWhitespace(item.getStaticModel()))) {
               ModelInstance modelInstance = this.addMaskingModel(slot, chr, item, replacement, replacement.maskVariableValue, replacement.attachment, boneName);
               if (this.requiresSmartTexture(item)) {
                  ModelInstanceTextureInitializer textureInitializer = ModelInstanceTextureInitializer.alloc();
                  textureInitializer.init(modelInstance, item);
                  modelInstance.setTextureInitializer(textureInitializer);
               }

               return modelInstance;
            }

            if (overrideHandModels && !StringUtils.isNullOrWhitespace(item.getStaticModel())) {
               String staticModel = item.getStaticModel();
               if (item.hasComponent(ComponentType.FluidContainer) && item.getFluidContainer().getFilledRatio() > 0.5F) {
                  ModelScript modelScriptFluid = ScriptManager.instance.getModelScript(item.getWorldStaticItem() + "_Fluid");
                  if (modelScriptFluid != null) {
                     staticModel = item.getWorldStaticItem() + "_Fluid";
                  }
               }

               ModelInstance modelInstance = this.addStatic(slot, staticModel, boneName);
               if (this.requiresSmartTexture(item)) {
                  ModelInstanceTextureInitializer textureInitializer = ModelInstanceTextureInitializer.alloc();
                  textureInitializer.init(modelInstance, item);
                  modelInstance.setTextureInitializer(textureInitializer);
               }

               return modelInstance;
            }
         }

         return null;
      }
   }

   private ModelInstance addMaskingModel(
      ModelManager.ModelSlot slot,
      IsoGameCharacter chr,
      InventoryItem item,
      ItemReplacement replacement,
      String maskVariableValue,
      String animMaskAttachment,
      String bone
   ) {
      ItemVisual itemVisual = item.getVisual();
      ModelInstance result;
      if (replacement.clothingItem != null && itemVisual != null) {
         result = PopTemplateManager.instance.addClothingItem(chr, slot, itemVisual, replacement.clothingItem);
      } else {
         if (StringUtils.isNullOrWhitespace(item.getStaticModel())) {
            return null;
         }

         String forcedTex = null;
         if (itemVisual != null && item.getClothingItem() != null) {
            forcedTex = (String)item.getClothingItem().getTextureChoices().get(itemVisual.getTextureChoice());
         }

         if (!StringUtils.isNullOrEmpty(animMaskAttachment)) {
            result = this.addStaticForcedTex(slot.model, item.getStaticModel(), animMaskAttachment, animMaskAttachment, forcedTex);
         } else {
            result = this.addStaticForcedTex(slot, item.getStaticModel(), bone, forcedTex);
         }

         result.maskVariableValue = maskVariableValue;
         if (itemVisual != null) {
            result.tintR = itemVisual.tint.r;
            result.tintG = itemVisual.tint.g;
            result.tintB = itemVisual.tint.b;
         }
      }

      if (!StringUtils.isNullOrEmpty(maskVariableValue)) {
         chr.setVariable(replacement.maskVariableName, maskVariableValue);
         chr.updateEquippedTextures = true;
      }

      return result;
   }

   private void addWeaponPartModels(ModelManager.ModelSlot slot, HandWeapon weapon, ModelInstance parent) {
      ArrayList<ModelWeaponPart> modelWeaponParts = weapon.getModelWeaponPart();
      if (modelWeaponParts != null) {
         List<WeaponPart> parts = weapon.getAllWeaponParts();

         for (int i = 0; i < parts.size(); i++) {
            WeaponPart part = parts.get(i);

            for (int j = 0; j < modelWeaponParts.size(); j++) {
               ModelWeaponPart mwp = modelWeaponParts.get(j);
               if (part.getFullType().equals(mwp.partType)) {
                  ModelInstance inst = this.addStatic(parent, mwp.modelName, mwp.attachmentNameSelf, mwp.attachmentParent);
                  inst.setOwner(slot);
               }
            }
         }
      }
   }

   public void resetModelInstance(ModelInstance modelInstance, Object expectedOwner) {
      if (modelInstance != null) {
         modelInstance.clearOwner(expectedOwner);
         if (modelInstance.isRendering()) {
            modelInstance.resetAfterRender = true;
         } else {
            if (modelInstance instanceof VehicleModelInstance) {
               return;
            }

            if (modelInstance instanceof VehicleSubModelInstance) {
               return;
            }

            modelInstance.reset();
            this.modelInstancePool.release(modelInstance);
         }
      }
   }

   public void resetModelInstanceRecurse(ModelInstance modelInstance, Object expectedOwner) {
      if (modelInstance != null) {
         this.resetModelInstancesRecurse(modelInstance.sub, expectedOwner);
         this.resetModelInstance(modelInstance, expectedOwner);
      }
   }

   public void resetModelInstancesRecurse(ArrayList<ModelInstance> modelInstances, Object expectedOwner) {
      for (int i = 0; i < modelInstances.size(); i++) {
         ModelInstance modelInstance = modelInstances.get(i);
         this.resetModelInstance(modelInstance, expectedOwner);
      }
   }

   public boolean derefModelInstance(ModelInstance modelInstance) {
      if (modelInstance == null) {
         return false;
      }

      assert modelInstance.renderRefCount > 0;
      modelInstance.renderRefCount--;
      if (!modelInstance.resetAfterRender || modelInstance.isRendering()) {
         return false;
      }

      assert modelInstance.getOwner() == null;
      if (modelInstance instanceof VehicleModelInstance) {
         return false;
      }

      if (modelInstance instanceof VehicleSubModelInstance) {
         return false;
      }

      modelInstance.reset();
      this.modelInstancePool.release(modelInstance);
      return true;
   }

   public void derefModelInstances(ArrayList<ModelInstance> modelInstances) {
      for (int i = 0; i < modelInstances.size(); i++) {
         ModelInstance modelInstance = modelInstances.get(i);
         this.derefModelInstance(modelInstance);
      }
   }

   private void DoCharacterModelParts(IsoGameCharacter chr, ModelManager.ModelSlot slot) {
      if (slot.isRendering()) {
         boolean var3 = false;
      }

      if (chr instanceof IsoAnimal) {
         chr.postUpdateModelTextures();
      } else {
         DebugType.Clothing.debugln("Char: %s Slot: %s", new Object[]{chr, slot});
         slot.sub.clear();
         PopTemplateManager.instance.populateCharacterModelSlot(chr, slot);
         this.DoCharacterModelEquipped(chr, slot);
         chr.OnClothingUpdated();
         chr.OnEquipmentUpdated();
      }
   }

   public void update() {
      for (int i = 0; i < this.toResetNextFrame.size(); i++) {
         IsoGameCharacter toReset = this.toResetNextFrame.get(i);
         this.Reset(toReset);
      }

      this.toResetNextFrame.clear();

      for (int i = 0; i < this.toResetEquippedNextFrame.size(); i++) {
         IsoGameCharacter toReset = this.toResetEquippedNextFrame.get(i);
         this.ResetCharacterEquippedHands(toReset);
      }

      this.toResetEquippedNextFrame.clear();

      for (int i = 0; i < this.toRemove.size(); i++) {
         IsoGameCharacter toRemove = this.toRemove.get(i);
         this.DoRemove(toRemove);
      }

      this.toRemove.clear();

      for (int i = 0; i < this.resetAfterRender.size(); i++) {
         ModelManager.ModelSlot slot = this.resetAfterRender.get(i);
         if (!slot.isRendering()) {
            slot.reset();
            this.resetAfterRender.remove(i--);
         }
      }

      if (IsoWorld.instance != null && IsoWorld.instance.currentCell != null) {
         for (BaseVehicle vehicle : IsoWorld.instance.currentCell.getVehicles()) {
            IsoGridSquare square = vehicle.getCurrentSquare();
            if (square != null && vehicle.sprite != null && vehicle.sprite.hasActiveModel()) {
               VehicleModelInstance model = (VehicleModelInstance)vehicle.sprite.modelSlot.model;
               int playerIndex = -1;

               for (int j = 0; j < IsoPlayer.numPlayers; j++) {
                  IsoPlayer player = IsoPlayer.players[j];
                  if (player != null) {
                     boolean bForceSeen = player.getVehicle() == vehicle;
                     if ((bForceSeen || square.lighting[j].bSeen()) && (bForceSeen || square.lighting[j].bCouldSee())) {
                        playerIndex = j;
                        break;
                     }
                  }
               }

               if (playerIndex == -1) {
                  EffectLight[] lights = model.getLights();

                  for (int j = 0; j < lights.length; j++) {
                     lights[j].clear();
                  }
               } else {
                  model.UpdateLights(playerIndex);
               }
            }
         }
      }
   }

   private ModelManager.ModelSlot addNewSlot(IsoGameCharacter character) {
      ModelManager.ModelSlot newSlot = new ModelManager.ModelSlot(this.modelSlots.size(), null, character);
      this.modelSlots.add(newSlot);
      return newSlot;
   }

   public ModelManager.ModelSlot getSlot(IsoGameCharacter chr) {
      for (int i = 0; i < this.modelSlots.size(); i++) {
         ModelManager.ModelSlot slot = this.modelSlots.get(i);
         if (!slot.remove && !slot.isRendering() && !slot.active) {
            return slot;
         }
      }

      return this.addNewSlot(chr);
   }

   private boolean DoRemove(IsoGameCharacter chr) {
      if (!this.contains.contains(chr)) {
         return false;
      }

      boolean removed = false;

      for (int i = 0; i < this.modelSlots.size(); i++) {
         ModelManager.ModelSlot slot = this.modelSlots.get(i);
         if (slot.character == chr) {
            chr.legsSprite.modelSlot = null;
            this.contains.remove(chr);
            chr.setAddedToModelManager(this, false);
            if (!this.resetAfterRender.contains(slot)) {
               this.resetAfterRender.add(slot);
            }

            removed = true;
         }
      }

      return removed;
   }

   public void Remove(IsoGameCharacter chr) {
      if (chr.isAddedToModelManager()) {
         if (!this.toRemove.contains(chr)) {
            chr.legsSprite.modelSlot.remove = true;
            this.toRemove.add(chr);
            chr.setAddedToModelManager(this, false);
         } else if (this.ContainsChar(chr)) {
            throw new IllegalStateException("IsoGameCharacter.isAddedToModelManager() = false inconsistent with ModelManager.ContainsChar() = true");
         }
      }
   }

   public void Remove(BaseVehicle vehicle) {
      if (vehicle.sprite != null && vehicle.sprite.modelSlot != null) {
         ModelManager.ModelSlot slot = vehicle.sprite.modelSlot;
         if (!this.resetAfterRender.contains(slot)) {
            this.resetAfterRender.add(slot);
         }

         vehicle.sprite.modelSlot = null;
      }
   }

   private static boolean isUpdateDisabled() {
      return GameServer.server && !ServerGUI.isCreated();
   }

   public void ResetNextFrame(IsoGameCharacter isoGameCharacter) {
      if (!isUpdateDisabled()) {
         if (!this.toResetNextFrame.contains(isoGameCharacter)) {
            this.toResetNextFrame.add(isoGameCharacter);
         }
      }
   }

   public void ResetEquippedNextFrame(IsoGameCharacter isoGameCharacter) {
      if (!isUpdateDisabled()) {
         if (!this.toResetEquippedNextFrame.contains(isoGameCharacter)) {
            this.toResetEquippedNextFrame.add(isoGameCharacter);
         }
      }
   }

   public void Reset() {
      RenderThread.invokeOnRenderContext(() -> {
         for (IsoGameCharacter aToRemove : this.toRemove) {
            this.DoRemove(aToRemove);
         }

         this.toRemove.clear();

         try {
            if (!this.contains.isEmpty()) {
               IsoGameCharacter[] remove = this.contains.toArray(new IsoGameCharacter[0]);

               for (IsoGameCharacter chr : remove) {
                  this.DoRemove(chr);
               }
            }

            this.modelSlots.clear();
         } catch (Exception ex) {
            DebugType.ModelManager.error("Exception thrown removing Models.");
            ExceptionLogger.logException(ex);
         }
      });
   }

   public void getSquareLighting(int playerIndex, IsoMovingObject chr, EffectLight[] ret) {
      for (int i = 0; i < ret.length; i++) {
         ret[i].clear();
      }

      IsoGridSquare square = chr.getCurrentSquare();
      if (square != null) {
         ILighting lighting = square.lighting[playerIndex];
         int lightCount = lighting.resultLightCount();

         for (int i = 0; i < lightCount; i++) {
            ResultLight resultLight = lighting.getResultLight(i);
            ret[i].x = resultLight.x;
            ret[i].y = resultLight.y;
            ret[i].z = resultLight.z;
            ret[i].r = resultLight.r;
            ret[i].g = resultLight.g;
            ret[i].b = resultLight.b;
            ret[i].radius = resultLight.radius;
            if (i == ret.length - 1) {
               break;
            }
         }
      }
   }

   public void addVehicle(BaseVehicle vehicle) {
      if (this.created) {
         if (!GameServer.server || ServerGUI.isCreated()) {
            if (vehicle != null && vehicle.getScript() != null) {
               VehicleScript script = vehicle.getScript();
               String modelName = vehicle.getScript().getModel().file;
               Model model = this.getLoadedModel(modelName);
               if (model == null) {
                  DebugType.ModelManager.error("Failed to find vehicle model: %s", new Object[]{modelName});
               } else {
                  DebugType.ModelManager.debugln("%s", new Object[]{modelName});
                  VehicleModelInstance inst = new VehicleModelInstance();
                  inst.init(model, null, vehicle.getAnimationPlayer());
                  inst.applyModelScriptScale(modelName);
                  vehicle.getSkin();
                  Skin skin = script.getTextures();
                  if (vehicle.getSkinIndex() >= 0 && vehicle.getSkinIndex() < script.getSkinCount()) {
                     skin = script.getSkin(vehicle.getSkinIndex());
                  }

                  inst.LoadTexture(skin.texture);
                  inst.tex = skin.textureData;
                  inst.textureMask = skin.textureDataMask;
                  inst.textureDamage1Overlay = skin.textureDataDamage1Overlay;
                  inst.textureDamage1Shell = skin.textureDataDamage1Shell;
                  inst.textureDamage2Overlay = skin.textureDataDamage2Overlay;
                  inst.textureDamage2Shell = skin.textureDataDamage2Shell;
                  inst.textureLights = skin.textureDataLights;
                  inst.textureRust = skin.textureDataRust;
                  if (inst.tex != null) {
                     inst.tex.bindAlways = true;
                  } else {
                     DebugType.ModelManager.error("texture not found:", new Object[]{vehicle.getSkin()});
                  }

                  ModelManager.ModelSlot slot = this.getSlot(null);
                  slot.model = inst;
                  inst.setOwner(slot);
                  inst.object = vehicle;
                  slot.sub.clear();

                  for (int i = 0; i < vehicle.models.size(); i++) {
                     ModelInfo modelInfo = (ModelInfo)vehicle.models.get(i);
                     Model submodel = this.getLoadedModel(modelInfo.scriptModel.file == null ? modelInfo.modelScript.name : modelInfo.scriptModel.file);
                     if (submodel == null) {
                        DebugType.ModelManager.error("vehicle.models[%d] not found: %s", new Object[]{i, modelInfo.scriptModel.file});
                     } else {
                        VehicleSubModelInstance subInst = new VehicleSubModelInstance();
                        subInst.init(submodel, null, modelInfo.getAnimationPlayer());
                        subInst.setOwner(slot);
                        subInst.applyModelScriptScale(modelInfo.scriptModel.file == null ? modelInfo.modelScript.name : modelInfo.scriptModel.file);
                        subInst.object = vehicle;
                        subInst.parent = inst;
                        inst.sub.add(subInst);
                        subInst.modelInfo = modelInfo;
                        if (subInst.tex == null) {
                           subInst.tex = inst.tex;
                        }

                        slot.sub.add(subInst);
                        modelInfo.modelInstance = subInst;
                     }
                  }

                  slot.active = true;
                  vehicle.sprite.modelSlot = slot;
               }
            }
         }
      }
   }

   public ModelInstance addStatic(ModelManager.ModelSlot slot, String meshName, String texName, String boneName, String shaderName) {
      ModelInstance inst = this.newStaticInstance(slot, meshName, texName, boneName, shaderName);
      if (inst == null) {
         return null;
      }

      slot.sub.add(inst);
      inst.setOwner(slot);
      slot.model.sub.add(inst);
      return inst;
   }

   public ModelInstance newStaticInstance(ModelManager.ModelSlot slot, String meshName, String texName, String boneName, String shaderName) {
      DebugType.ModelManager.debugln("Adding Static Model: %s", new Object[]{meshName});
      Model model = this.tryGetLoadedModel(meshName, texName, true, shaderName, false);
      if (model == null && meshName != null) {
         this.loadStaticModel(meshName, texName, shaderName);
         model = this.getLoadedModel(meshName, texName, true, shaderName);
         if (model == null) {
            DebugType.ModelManager.error("Model not found. model:%s tex:%s", new Object[]{meshName, texName});
            return null;
         }
      }

      if (meshName == null) {
         model = this.tryGetLoadedModel("vehicles_wheel02", "vehicles/vehicle_wheel02", true, "vehiclewheel", false);
      }

      ModelInstance inst = this.newInstance(model, slot.character, slot.model.animPlayer);
      inst.parent = slot.model;
      if (slot.model.animPlayer != null) {
         inst.parentBone = slot.model.animPlayer.getSkinningBoneIndex(boneName, inst.parentBone);
         inst.parentBoneName = boneName;
      }

      inst.animPlayer = slot.model.animPlayer;
      return inst;
   }

   private ModelInstance addStatic(ModelManager.ModelSlot slot, String staticModel, String boneName) {
      return this.addStaticForcedTex(slot, staticModel, boneName, null);
   }

   private ModelInstance addStaticForcedTex(ModelManager.ModelSlot slot, String staticModel, String boneName, String forcedTex) {
      String meshName = ScriptManager.getItemName(staticModel);
      String texName = ScriptManager.getItemName(staticModel);
      String shaderName = null;
      ModelManager.ModelMetaData metaData = modelMetaData.get(staticModel);
      if (metaData != null) {
         if (!StringUtils.isNullOrWhitespace(metaData.meshName)) {
            meshName = metaData.meshName;
         }

         if (!StringUtils.isNullOrWhitespace(metaData.textureName)) {
            texName = metaData.textureName;
         }

         if (!StringUtils.isNullOrWhitespace(metaData.shaderName)) {
            shaderName = metaData.shaderName;
         }
      }

      if (!StringUtils.isNullOrEmpty(forcedTex)) {
         texName = forcedTex;
      }

      ModelScript modelScript = ScriptManager.instance.getModelScript(staticModel);
      if (modelScript != null) {
         meshName = modelScript.getMeshName();
         texName = modelScript.getTextureName();
         shaderName = modelScript.getShaderName();
         ModelInstance inst = this.addStatic(slot, meshName, texName, boneName, shaderName);
         if (inst != null) {
            inst.applyModelScriptScale(staticModel);
         }

         return inst;
      } else {
         return this.addStatic(slot, meshName, texName, boneName, shaderName);
      }
   }

   public ModelInstance addStatic(ModelInstance parentInst, String modelName, String attachNameSelf, String attachNameParent) {
      return this.addStaticForcedTex(parentInst, modelName, attachNameSelf, attachNameParent, null);
   }

   public ModelInstance addStaticForcedTex(ModelInstance parentInst, String modelName, String attachNameSelf, String attachNameParent, String forcedTex) {
      String meshName = ScriptManager.getItemName(modelName);
      String texName = ScriptManager.getItemName(modelName);
      String shaderName = null;
      ModelScript modelScript = ScriptManager.instance.getModelScript(modelName);
      if (modelScript != null) {
         meshName = modelScript.getMeshName();
         texName = modelScript.getTextureName();
         shaderName = modelScript.getShaderName();
      }

      if (!StringUtils.isNullOrEmpty(forcedTex)) {
         texName = forcedTex;
      }

      Model model = this.tryGetLoadedModel(meshName, texName, true, shaderName, false);
      if (model == null && meshName != null) {
         this.loadStaticModel(meshName, texName, shaderName);
         model = this.getLoadedModel(meshName, texName, true, shaderName);
         if (model == null) {
            DebugType.ModelManager.error("Model not found. model:%s tex:%s", new Object[]{meshName, texName});
            return null;
         }
      }

      if (meshName == null) {
         model = this.tryGetLoadedModel("vehicles_wheel02", "vehicles/vehicle_wheel02", true, "vehiclewheel", false);
      }

      if (model == null) {
         return null;
      }

      ModelInstance inst = (ModelInstance)this.modelInstancePool.alloc();
      if (parentInst != null) {
         inst.init(model, parentInst.character, parentInst.animPlayer);
         inst.parent = parentInst;
         parentInst.sub.add(inst);
      } else {
         inst.init(model, null, null);
      }

      if (modelScript != null) {
         inst.applyModelScriptScale(modelName);
      }

      inst.attachmentNameSelf = attachNameSelf;
      inst.attachmentNameParent = attachNameParent;
      return inst;
   }

   private String modifyShaderName(String shaderName) {
      if ((
            StringUtils.equals(shaderName, "vehicle")
               || StringUtils.equals(shaderName, "vehicle_multiuv")
               || StringUtils.equals(shaderName, "vehicle_norandom_multiuv")
         )
         && (!Core.getInstance().getPerfReflectionsOnLoad() || Core.getInstance().getUseOpenGL21())) {
         shaderName = shaderName + "_noreflect";
      }

      return shaderName;
   }

   private Model loadModelInternal(String meshName, String texName, String shaderName, ModelMesh animationsModel, boolean isStatic, String postProcess) {
      shaderName = this.modifyShaderName(shaderName);
      ModelAssetParams params = new ModelAssetParams();
      params.animationsModel = animationsModel;
      params.isStatic = isStatic;
      params.meshName = meshName;
      params.shaderName = shaderName;
      params.textureName = texName;
      params.postProcess = postProcess;
      if (shaderName != null && StringUtils.startsWithIgnoreCase(shaderName, "vehicle")) {
         params.textureFlags = TextureID.useCompression ? 4 : 0;
         params.textureFlags |= 256;
      } else {
         params.textureFlags = this.getTextureFlags();
      }

      if (!this.shouldLimitTextureSize(texName)) {
         params.textureFlags &= -385;
      }

      String modelId = this.createModelKey(meshName, texName, isStatic, shaderName);
      Model model = (Model)ModelAssetManager.instance.load(new AssetPath(modelId), params);
      if (model != null) {
         this.putLoadedModel(meshName, texName, isStatic, shaderName, model);
      }

      return model;
   }

   public int getTextureFlags() {
      int flags = TextureID.useCompression ? 4 : 0;
      if (Core.getInstance().getOptionModelTextureMipmaps()) {
      }

      return flags | 128;
   }

   public boolean shouldLimitTextureSize(String textureName) {
      return textureName == null ? true : !textureName.equals("IsoObject/MODELS_fixtures_doors_fences_01");
   }

   public void setModelMetaData(String meshName, String texName, String shaderName, boolean bStatic) {
      this.setModelMetaData(meshName, meshName, texName, shaderName, bStatic);
   }

   public void setModelMetaData(String modelId, String meshName, String texName, String shaderName, boolean bStatic) {
      ModelManager.ModelMetaData metaData = new ModelManager.ModelMetaData();
      metaData.meshName = meshName;
      metaData.textureName = texName;
      metaData.shaderName = shaderName;
      metaData.isStatic = bStatic;
      modelMetaData.put(modelId, metaData);
   }

   public Model loadStaticModel(String meshName, String tex, String shaderName) {
      String shader = this.modifyShaderName(shaderName);
      return this.loadModelInternal(meshName, tex, shader, null, true, null);
   }

   public Model loadModel(String meshName, String tex, ModelMesh animationsModel, String shader) {
      return this.loadModelInternal(meshName, tex, shader, animationsModel, false, null);
   }

   private Model loadModel(String meshName, String tex, ModelMesh animationsModel) {
      return this.loadModelInternal(meshName, tex, "basicEffect", animationsModel, false, null);
   }

   public Model getLoadedModel(String meshName) {
      ModelScript modelScript = ScriptManager.instance.getModelScript(meshName);
      if (modelScript != null) {
         if (modelScript.loadedModel != null) {
            return modelScript.loadedModel;
         } else {
            modelScript.shaderName = this.modifyShaderName(modelScript.shaderName);
            Model model = this.tryGetLoadedModel(
               modelScript.getMeshName(), modelScript.getTextureName(), modelScript.isStatic, modelScript.getShaderName(), false
            );
            if (model != null) {
               modelScript.loadedModel = model;
               return model;
            } else {
               AnimationsMesh animationsMesh = modelScript.animationsMesh == null ? null : ScriptManager.instance.getAnimationsMesh(modelScript.animationsMesh);
               ModelMesh animsModel = animationsMesh == null ? null : animationsMesh.modelMesh;
               model = modelScript.isStatic
                  ? this.loadModelInternal(
                     modelScript.getMeshName(), modelScript.getTextureName(), modelScript.getShaderName(), null, true, modelScript.postProcess
                  )
                  : this.loadModelInternal(
                     modelScript.getMeshName(), modelScript.getTextureName(), modelScript.getShaderName(), animsModel, false, modelScript.postProcess
                  );
               modelScript.loadedModel = model;
               return model;
            }
         }
      } else {
         ModelManager.ModelMetaData metaData = modelMetaData.get(meshName);
         if (metaData != null) {
            metaData.shaderName = this.modifyShaderName(metaData.shaderName);
            Model model = this.tryGetLoadedModel(metaData.meshName, metaData.textureName, metaData.isStatic, metaData.shaderName, false);
            if (model != null) {
               return model;
            } else {
               return metaData.isStatic
                  ? this.loadStaticModel(metaData.meshName, metaData.textureName, metaData.shaderName)
                  : this.loadModel(metaData.meshName, metaData.textureName, this.animModel);
            }
         } else {
            Model model = this.tryGetLoadedModel(meshName, null, false, null, false);
            if (model != null) {
               return model;
            }

            String nameKey = meshName.toLowerCase().trim();

            for (Entry<String, Model> entry : this.modelMap.entrySet()) {
               String key = entry.getKey();
               if (key.startsWith(nameKey)) {
                  Model value = entry.getValue();
                  if (value != null && (key.length() == nameKey.length() || key.charAt(nameKey.length()) == '&')) {
                     model = value;
                     break;
                  }
               }
            }

            if (model == null) {
               DebugType.ModelManager.error("ModelManager.getLoadedModel> Model missing for key=\"%s\"", new Object[]{nameKey});
            }

            return model;
         }
      }
   }

   public Model getLoadedModel(String meshName, String tex, boolean isStatic, String shaderName) {
      return this.tryGetLoadedModel(meshName, tex, isStatic, shaderName, true);
   }

   public Model tryGetLoadedModel(ModelScript modelScript, boolean logError) {
      if (modelScript.getModelManagerKey() == null) {
         modelScript.setModelManagerKey(
            this.createModelKey(modelScript.getMeshName(), modelScript.getTextureName(), modelScript.isStatic, modelScript.getShaderName())
         );
      }

      return this.tryGetLoadedModel(modelScript.getModelManagerKey(), logError);
   }

   public Model tryGetLoadedModel(String meshName, String tex, boolean isStatic, String shaderName, boolean logError) {
      String key = this.createModelKey(meshName, tex, isStatic, shaderName);
      return this.tryGetLoadedModel(key, logError);
   }

   public Model tryGetLoadedModel(String key, boolean logError) {
      if (key == null) {
         return null;
      }

      Model model = this.modelMap.get(key);
      if (model == null && logError) {
         DebugType.ModelManager.error("ModelManager.getLoadedModel> Model missing for key=\"%s\"", new Object[]{key});
      }

      return model;
   }

   public void putLoadedModel(String name, String tex, boolean isStatic, String shaderName, Model model) {
      String key = this.createModelKey(name, tex, isStatic, shaderName);
      if (key != null) {
         Model existingModel = this.modelMap.get(key);
         if (existingModel != model) {
            if (existingModel != null) {
               DebugType.ModelManager.debugln("Override key=\"%s\" old=%s new=%s", new Object[]{key, existingModel, model});
            } else {
               DebugType.ModelManager.debugln("key=\"%s\" model=%s", new Object[]{key, model});
            }

            this.modelMap.put(key, model);
            model.name = key;
         }
      }
   }

   private String createModelKey(String name, String tex, boolean isStatic, String shaderName) {
      builder.delete(0, builder.length());
      if (name == null) {
         return null;
      }

      if (!toLowerKeyRoot.containsKey(name)) {
         toLowerKeyRoot.put(name, name.toLowerCase(Locale.ENGLISH).trim());
      }

      builder.append(toLowerKeyRoot.get(name));
      builder.append("&");
      if (StringUtils.isNullOrWhitespace(shaderName)) {
         shaderName = "basicEffect";
      }

      builder.append("shader=");
      if (!toLower.containsKey(shaderName)) {
         toLower.put(shaderName, shaderName.toLowerCase().trim());
      }

      builder.append(toLower.get(shaderName));
      if (!StringUtils.isNullOrWhitespace(tex)) {
         builder.append(";tex=");
         if (!toLowerTex.containsKey(tex)) {
            toLowerTex.put(tex, tex.toLowerCase().trim());
         }

         builder.append(toLowerTex.get(tex));
      }

      if (isStatic) {
         builder.append(";isStatic=true");
      }

      return builder.toString();
   }

   private String createModelKey2(String name, String tex, boolean isStatic, String shaderName) {
      if (name == null) {
         return null;
      }

      if (StringUtils.isNullOrWhitespace(shaderName)) {
         shaderName = "basicEffect";
      }

      String keyParams = "shader=" + shaderName.toLowerCase().trim();
      if (!StringUtils.isNullOrWhitespace(tex)) {
         keyParams = keyParams + ";tex=" + tex.toLowerCase().trim();
      }

      if (isStatic) {
         keyParams = keyParams + ";isStatic=true";
      }

      String keyRoot = name.toLowerCase(Locale.ENGLISH).trim();
      return keyRoot + "&" + keyParams;
   }

   private AnimationAsset loadAnim(String name, ModelMesh animationsModel, ModelManager.ModAnimations modAnimations) {
      DebugType.ModelManager.debugln("Adding asset to queue: %s", new Object[]{name});
      AnimationAssetParams params = new AnimationAssetParams();
      params.animationsMesh = animationsModel;
      AnimationAsset anim = (AnimationAsset)AnimationAssetManager.instance.load(new AssetPath(name), params);
      anim.skinningData = animationsModel.skinningData;
      this.putAnimationAsset(name, anim, modAnimations);
      return anim;
   }

   private void putAnimationAsset(String name, AnimationAsset anim, ModelManager.ModAnimations modAnimations) {
      String nameKey = name.toLowerCase();
      AnimationAsset existingAsset = modAnimations.animationAssetMap.getOrDefault(nameKey, null);
      if (existingAsset != null) {
         DebugType.ModelManager.debugln("Overwriting asset: %s", new Object[]{this.animAssetToString(existingAsset)});
         DebugType.ModelManager.debugln("New asset        : %s", new Object[]{this.animAssetToString(anim)});
         modAnimations.animationAssetList.remove(existingAsset);
      }

      anim.modelManagerKey = nameKey;
      anim.modAnimations = modAnimations;
      modAnimations.animationAssetMap.put(nameKey, anim);
      modAnimations.animationAssetList.add(anim);
   }

   private String animAssetToString(AnimationAsset asset) {
      if (asset == null) {
         return "null";
      }

      AssetPath path = asset.getPath();
      return path == null ? "null-path" : String.valueOf(path.getPath());
   }

   public AnimationAsset getAnimationAsset(String name) {
      String nameKey = name.toLowerCase(Locale.ENGLISH);
      return this.animationAssets.get(nameKey);
   }

   private AnimationAsset getAnimationAssetRequired(String name) {
      AnimationAsset found = this.getAnimationAsset(name);
      if (found == null) {
         throw new NullPointerException("Required Animation Asset not found: " + name);
      } else {
         return found;
      }
   }

   public void addAnimationClip(String name, AnimationClip clip) {
      this.animModel.skinningData.animationClips.put(name, clip);
   }

   public AnimationClip getAnimationClip(String name) {
      return (AnimationClip)this.animModel.skinningData.animationClips.get(name);
   }

   public Collection<AnimationClip> getAllAnimationClips() {
      return this.animModel.skinningData.animationClips.values();
   }

   public ModelInstance newInstance(Model model, IsoGameCharacter chr, AnimationPlayer player) {
      if (model == null) {
         System.err.println("ModelManager.newInstance> Model is null.");
         return null;
      } else {
         ModelInstance modelInst = (ModelInstance)this.modelInstancePool.alloc();
         modelInst.init(model, chr, player);
         return modelInst;
      }
   }

   public boolean isLoadingAnimations() {
      for (AnimationAsset anim : this.animationAssets.values()) {
         if (anim.isEmpty()) {
            return true;
         }
      }

      return false;
   }

   public void reloadModelsMatching(String meshName) {
      meshName = meshName.toLowerCase(Locale.ENGLISH);

      for (String key : this.modelMap.keySet()) {
         if (key.contains(meshName)) {
            Model model = this.modelMap.get(key);
            if (!model.isEmpty()) {
               DebugType.General.printf("reloading model %s\n", new Object[]{key});
               MeshAssetParams assetParams = new MeshAssetParams();
               assetParams.animationsMesh = model.mesh.animationsMesh;
               assetParams.postProcess = model.mesh.postProcess;
               if (model.mesh.vb == null) {
                  assetParams.isStatic = key.contains(";isStatic=true");
               } else {
                  assetParams.isStatic = model.mesh.vb.isStatic;
               }

               MeshAssetManager.instance.reload(model.mesh, assetParams);
            }
         }
      }
   }

   public void loadModAnimations() {
      for (ModelManager.ModAnimations modAnimations : this.modAnimations.values()) {
         modAnimations.setPriority(modAnimations == this.gameAnimations ? 0 : -1);
      }

      ArrayList<AnimationsMesh> animationsMeshes = ScriptManager.instance.getAllAnimationsMeshes();
      ArrayList<String> modIDs = ZomboidFileSystem.instance.getModIDs();

      for (int i = 0; i < modIDs.size(); i++) {
         String modID = modIDs.get(i);
         Mod mod = ChooseGameInfo.getAvailableModDetails(modID);
         if (mod != null && (mod.animsXFile.common.absoluteFile.isDirectory() || mod.animsXFile.version.absoluteFile.isDirectory())) {
            ModelManager.ModAnimations modAnimations = this.modAnimations.get(modID);
            if (modAnimations != null) {
               modAnimations.setPriority(i + 1);
            } else {
               modAnimations = new ModelManager.ModAnimations(modID);
               modAnimations.setPriority(i + 1);
               this.modAnimations.put(modID, modAnimations);

               for (AnimationsMesh am2 : animationsMeshes) {
                  for (String dir : am2.animationDirectories) {
                     if (am2.modelMesh.isReady()) {
                        File subDir = new File(mod.animsXFile.common.canonicalFile, dir);
                        if (subDir.exists()) {
                           this.loadAnimsFromDir(
                              mod.baseFile.common.lowercaseUri, mod.mediaFile.common.lowercaseUri, subDir, am2.modelMesh, modAnimations, am2.animationPrefixes
                           );
                        }

                        subDir = new File(mod.animsXFile.version.canonicalFile, dir);
                        if (subDir.exists()) {
                           this.loadAnimsFromDir(
                              mod.baseFile.version.lowercaseUri,
                              mod.mediaFile.version.lowercaseUri,
                              subDir,
                              am2.modelMesh,
                              modAnimations,
                              am2.animationPrefixes
                           );
                        }
                     }
                  }
               }

               this.loadHumanAnimations(mod, modAnimations);
            }
         }
      }

      this.setActiveAnimations();
   }

   void setActiveAnimations() {
      this.animationAssets.clear();

      for (AnimationsMesh am : ScriptManager.instance.getAllAnimationsMeshes()) {
         if (am.modelMesh.isReady()) {
            am.modelMesh.skinningData.animationClips.clear();
            if (am.keepMeshAnimations) {
               am.modelMesh.skinningData.animationClips.putAll(am.modelMesh.meshAnimationClips);
            }
         }
      }

      for (ModelManager.ModAnimations modAnimations : this.modAnimations.values()) {
         if (modAnimations.isActive()) {
            for (AnimationAsset animationAsset : modAnimations.animationAssetList) {
               AnimationAsset existing = this.animationAssets.get(animationAsset.modelManagerKey);
               if (existing == null || existing == animationAsset || existing.modAnimations.priority <= modAnimations.priority) {
                  this.animationAssets.put(animationAsset.modelManagerKey, animationAsset);
                  if (animationAsset.isReady()) {
                     animationAsset.skinningData.animationClips.putAll(animationAsset.animationClips);
                  }
               }
            }
         }
      }
   }

   public void animationAssetLoaded(AnimationAsset animationAsset) {
      if (animationAsset.modAnimations.isActive()) {
         AnimationAsset existing = this.animationAssets.get(animationAsset.modelManagerKey);
         if (existing == null || existing == animationAsset || existing.modAnimations.priority <= animationAsset.modAnimations.priority) {
            this.animationAssets.put(animationAsset.modelManagerKey, animationAsset);
            animationAsset.skinningData.animationClips.putAll(animationAsset.animationClips);
         }
      }
   }

   public void initAnimationMeshes(boolean bReloading) {
      ArrayList<AnimationsMesh> animationsMeshes = ScriptManager.instance.getAllAnimationsMeshes();

      for (AnimationsMesh am : animationsMeshes) {
         MeshAssetParams assetParams = new MeshAssetParams();
         assetParams.isStatic = false;
         assetParams.animationsMesh = null;
         assetParams.postProcess = am.postProcess;
         am.modelMesh = (ModelMesh)MeshAssetManager.instance.getAssetTable().get(am.meshFile);
         if (am.modelMesh == null) {
            am.modelMesh = (ModelMesh)MeshAssetManager.instance.load(new AssetPath(am.meshFile), assetParams);
         }

         am.modelMesh.animationsMesh = am.modelMesh;
      }

      if (!bReloading) {
         while (this.isLoadingAnimationMeshes()) {
            GameWindow.fileSystem.updateAsyncTransactions();

            try {
               Thread.sleep(10L);
            } catch (InterruptedException var8) {
            }

            if (!GameServer.server) {
               Core.getInstance().StartFrame();
               Core.getInstance().EndFrame();
               Core.getInstance().StartFrameUI();
               Core.getInstance().EndFrameUI();
            }
         }

         for (AnimationsMesh am : animationsMeshes) {
            for (String dir : am.animationDirectories) {
               if (am.modelMesh.isReady()) {
                  File file = new File(ZomboidFileSystem.instance.getAnimsXFile(), dir);
                  if (file.exists()) {
                     this.loadAnimsFromDir("media/anims_X/" + dir, am.modelMesh, am.animationPrefixes);
                  }
               }
            }
         }
      }
   }

   private boolean isLoadingAnimationMeshes() {
      for (AnimationsMesh am : ScriptManager.instance.getAllAnimationsMeshes()) {
         if (!am.modelMesh.isFailure() && !am.modelMesh.isReady()) {
            return true;
         }
      }

      return false;
   }

   private void loadHumanAnimations(Mod mod, ModelManager.ModAnimations modAnimations) {
      AnimationsMesh humanAM = ScriptManager.instance.getAnimationsMesh("Human");
      if (humanAM != null && humanAM.modelMesh != null && humanAM.modelMesh.isReady()) {
         File[] files = mod.animsXFile.common.canonicalFile.listFiles();
         if (files != null) {
            URI dirURI = mod.animsXFile.common.lowercaseUri;

            for (File file : files) {
               if (file.isDirectory()) {
                  if (!this.isAnimationsMeshDirectory(file.getName())) {
                     this.loadAnimsFromDir(mod.baseFile.common.lowercaseUri, mod.mediaFile.common.lowercaseUri, file, humanAM.modelMesh, modAnimations, null);
                  }
               } else {
                  String animName = ZomboidFileSystem.instance.getAnimName(dirURI, file);
                  this.loadAnim(animName, humanAM.modelMesh, modAnimations);
               }
            }

            files = mod.animsXFile.version.canonicalFile.listFiles();
            if (files != null) {
               dirURI = mod.animsXFile.version.lowercaseUri;

               for (File file : files) {
                  if (file.isDirectory()) {
                     if (!this.isAnimationsMeshDirectory(file.getName())) {
                        this.loadAnimsFromDir(
                           mod.baseFile.version.lowercaseUri, mod.mediaFile.version.lowercaseUri, file, humanAM.modelMesh, modAnimations, null
                        );
                     }
                  } else {
                     String animName = ZomboidFileSystem.instance.getAnimName(dirURI, file);
                     this.loadAnim(animName, humanAM.modelMesh, modAnimations);
                  }
               }
            }
         }
      }
   }

   private boolean isAnimationsMeshDirectory(String dir) {
      for (AnimationsMesh am : ScriptManager.instance.getAllAnimationsMeshes()) {
         if (am.animationDirectories.contains(dir)) {
            return true;
         }
      }

      return false;
   }

   private boolean requiresSmartTexture(InventoryItem item) {
      if (item.getColorRed() != 1.0F) {
         return true;
      } else if (item.getColorGreen() != 1.0F) {
         return true;
      } else {
         return item.getColorBlue() != 1.0F ? true : item.hasComponent(ComponentType.FluidContainer) && !item.getFluidContainer().isEmpty();
      }
   }

   class AnimDirReloader implements IPredicatedFileWatcherCallback {
      URI baseUri;
      URI mediaUri;
      String dir;
      String dirSecondary;
      String dirAbsolute;
      String dirSecondaryAbsolute;
      ModelMesh animationsModel;
      ModelManager.ModAnimations modAnimations;

      public AnimDirReloader(final URI baseURI, final URI mediaURI, String dir, final ModelMesh animationsModel, final ModelManager.ModAnimations modAnimations) {
         dir = ZomboidFileSystem.instance.getRelativeFile(baseURI, dir);
         this.baseUri = baseURI;
         this.mediaUri = mediaURI;
         this.dir = ZomboidFileSystem.instance.normalizeFolderPath(dir);
         this.dirAbsolute = ZomboidFileSystem.instance.normalizeFolderPath(new File(new File(this.baseUri), this.dir).toString());
         if (this.dir.contains("/anims/")) {
            this.dirSecondary = this.dir.replace("/anims/", "/anims_X/");
            this.dirSecondaryAbsolute = ZomboidFileSystem.instance.normalizeFolderPath(new File(new File(this.baseUri), this.dirSecondary).toString());
         }

         this.animationsModel = animationsModel;
         this.modAnimations = modAnimations;
      }

      private boolean IsInDir(String filename) {
         filename = ZomboidFileSystem.instance.normalizeFolderPath(filename);

         try {
            return this.dirSecondary == null
               ? filename.startsWith(this.dirAbsolute)
               : filename.startsWith(this.dirAbsolute) || filename.startsWith(this.dirSecondaryAbsolute);
         } catch (Exception e) {
            DebugType.General.printException(e, LogSeverity.Error);
            return false;
         }
      }

      public void call(String entryKey) {
         String lower = entryKey.toLowerCase();
         if (lower.endsWith(".fbx") || lower.endsWith(".x") || lower.endsWith(".txt") || lower.endsWith(".glb")) {
            String animName = ZomboidFileSystem.instance.getAnimName(this.mediaUri, new File(entryKey));
            AnimationAsset anim = ModelManager.this.getAnimationAsset(animName);
            if (anim != null) {
               if (!anim.isEmpty()) {
                  DebugType.General.debugln("Reloading animation: %s", new Object[]{ModelManager.this.animAssetToString(anim)});
                  assert anim.getRefCount() == 1;
                  AnimationAssetParams params = new AnimationAssetParams();
                  params.animationsMesh = this.animationsModel;
                  AnimationAssetManager.instance.reload(anim, params);
               }
            } else {
               ModelManager.this.loadAnim(animName, this.animationsModel, this.modAnimations);
            }
         }
      }

      public PredicatedFileWatcher GetFileWatcher() {
         return new PredicatedFileWatcher(this.dir, this::IsInDir, this);
      }
   }

   public static final class ModAnimations {
      public final String modId;
      public final ArrayList<AnimationAsset> animationAssetList = new ArrayList<>();
      public final HashMap<String, AnimationAsset> animationAssetMap = new HashMap<>();
      public int priority;

      public ModAnimations(String modID) {
         this.modId = modID;
      }

      public void setPriority(int priority) {
         assert priority >= -1;
         this.priority = priority;
      }

      public boolean isActive() {
         return this.priority != -1;
      }
   }

   private static final class ModelMetaData {
      String meshName;
      String textureName;
      String shaderName;
      boolean isStatic;
   }

   public static class ModelSlot {
      public int id;
      public ModelInstance model;
      public IsoGameCharacter character;
      public final ArrayList<ModelInstance> sub = new ArrayList<>();
      public final ArrayList<ModelInstance> muzzleFlashModels = new ArrayList<>();
      protected final AttachedModels attachedModels = new AttachedModels();
      public boolean active;
      public boolean remove;
      public int renderRefCount;
      public int framesSinceStart;

      public ModelSlot(int id, ModelInstance model, IsoGameCharacter character) {
         this.id = id;
         this.model = model;
         this.character = character;
      }

      public void Update(float deltaT) {
         if (this.character != null && !this.remove) {
            this.framesSinceStart++;
            if (this.model.animPlayer != this.character.getAnimationPlayer()) {
               this.model.animPlayer = this.character.getAnimationPlayer();
            }

            synchronized (this.model.lock) {
               this.model.UpdateDir();
               this.model.Update(deltaT);

               for (int n = 0; n < this.sub.size(); n++) {
                  this.sub.get(n).animPlayer = this.model.animPlayer;
               }
            }
         }
      }

      public boolean isRendering() {
         return this.renderRefCount > 0;
      }

      public void reset() {
         ModelManager.instance.resetModelInstanceRecurse(this.model, this);
         if (this.character != null) {
            this.character.primaryHandModel = null;
            this.character.secondaryHandModel = null;
            ModelManager.instance.derefModelInstances(this.character.getReadyModelData());
            this.character.getReadyModelData().clear();
         }

         this.active = false;
         this.character = null;
         this.remove = false;
         this.renderRefCount = 0;
         this.model = null;
         this.sub.clear();
         this.attachedModels.clear();
      }
   }
}
