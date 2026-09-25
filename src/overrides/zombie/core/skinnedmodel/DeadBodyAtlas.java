package zombie.core.skinnedmodel;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.AttachedItems.AttachedModelName;
import zombie.characters.AttachedItems.AttachedModelNames;
import zombie.core.Core;
import zombie.core.ImmutableColor;
import zombie.core.PerformanceSettings;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.logger.ExceptionLogger;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.RenderThread;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel;
import zombie.core.skinnedmodel.advancedanimation.IAnimatable;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.animation.BoneTransform;
import zombie.core.skinnedmodel.animation.TwistableBoneTransform;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkinningBone;
import zombie.core.skinnedmodel.population.ClothingItem;
import zombie.core.skinnedmodel.visual.AnimalVisual;
import zombie.core.skinnedmodel.visual.BaseVisual;
import zombie.core.skinnedmodel.visual.HumanVisual;
import zombie.core.skinnedmodel.visual.IHumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.debug.DebugOptions;
import zombie.interfaces.ITexture;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoObjectPicker;
import zombie.iso.PlayerCamera;
import zombie.iso.Vector2;
import zombie.iso.IsoGridSquare.ILighting;
import zombie.iso.IsoGridSquare.ResultLight;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoMannequin;
import zombie.iso.objects.ShadowParams;
import zombie.popman.ObjectPool;
import zombie.util.Pool;
import zombie.util.StringUtils;
import zombie.util.Type;
import zombie.util.list.PZArrayUtil;
import zombie.vehicles.UI3DScene;
import zombie.vehicles.UI3DScene.Plane;
import zombie.vehicles.UI3DScene.Ray;

public final class DeadBodyAtlas {
   public static final int ATLAS_SIZE = 1024;
   private TextureFBO fbo;
   public static final DeadBodyAtlas instance = new DeadBodyAtlas();
   private static final Vector2 tempVector2 = new Vector2();
   private final HashMap<String, DeadBodyAtlas.BodyTexture> entryMap = new HashMap<>();
   private final ArrayList<DeadBodyAtlas.Atlas> atlasList = new ArrayList<>();
   private final ArrayList<DeadBodyAtlas.Atlas> atlasListMain = new ArrayList<>();
   private final ConcurrentLinkedQueue<DeadBodyAtlas.Atlas> newAtlasQ = new ConcurrentLinkedQueue<>();
   private final DeadBodyAtlas.BodyParams bodyParams = new DeadBodyAtlas.BodyParams();
   private int updateCounter = -1;
   private final DeadBodyAtlas.Checksummer checksummer = new DeadBodyAtlas.Checksummer();
   private static final Stack<DeadBodyAtlas.RenderJob> JobPool = new Stack<>();
   private final DeadBodyAtlas.DebugDrawInWorld[] debugDrawInWorld = new DeadBodyAtlas.DebugDrawInWorld[3];
   private long debugDrawTime;
   private final ArrayList<DeadBodyAtlas.RenderJob> renderJobs = new ArrayList<>();
   private final DeadBodyAtlas.CharacterTextureVisual characterTextureVisualFemale = new DeadBodyAtlas.CharacterTextureVisual(true);
   private final DeadBodyAtlas.CharacterTextureVisual characterTextureVisualMale = new DeadBodyAtlas.CharacterTextureVisual(false);
   private final CharacterTextures characterTexturesFemale = new CharacterTextures();
   private final CharacterTextures characterTexturesMale = new CharacterTextures();
   private final ObjectPool<DeadBodyAtlas.BodyTextureDrawer> bodyTextureDrawerPool = new ObjectPool(
      DeadBodyAtlas.BodyTextureDrawer::new, "DeadBodyAtlas.bodyTextureDrawerPool"
   );
   private static final ObjectPool<DeadBodyAtlas.BodyTextureDepthDrawer> s_BodyTextureDepthDrawerPool = new ObjectPool(
      DeadBodyAtlas.BodyTextureDepthDrawer::new, "DeadBodyAtlas.s_BodyTextureDepthDrawerPool"
   );
   public static Shader deadBodyAtlasShader;

   public void lightingUpdate(int updateCounter, boolean lightsChanged) {
      if (updateCounter != this.updateCounter && lightsChanged) {
         this.updateCounter = updateCounter;
      }
   }

   public DeadBodyAtlas.BodyTexture getBodyTexture(IsoDeadBody body) {
      this.bodyParams.init(body);
      return this.getBodyTexture(this.bodyParams);
   }

   public DeadBodyAtlas.BodyTexture getBodyTexture(IsoZombie body) {
      this.bodyParams.init(body);
      return this.getBodyTexture(this.bodyParams);
   }

   public DeadBodyAtlas.BodyTexture getBodyTexture(IsoMannequin body) {
      this.bodyParams.init(body);
      return this.getBodyTexture(this.bodyParams);
   }

   public DeadBodyAtlas.BodyTexture getBodyTexture(boolean bFemale, String animSet, String stateName, IsoDirections dir, int frame, float trackTime) {
      CharacterTextures characterTextures = bFemale ? this.characterTexturesFemale : this.characterTexturesMale;
      DeadBodyAtlas.BodyTexture texture = characterTextures.getTexture(animSet, stateName, dir, frame);
      if (texture != null) {
         return texture;
      }

      this.bodyParams.init(bFemale ? this.characterTextureVisualFemale : this.characterTextureVisualMale, dir, animSet, stateName, trackTime);
      this.bodyParams.variables.put("zombieWalkType", "1");
      DeadBodyAtlas.BodyTexture tex = this.getBodyTexture(this.bodyParams);
      characterTextures.addTexture(animSet, stateName, dir, frame, tex);
      return tex;
   }

   public DeadBodyAtlas.BodyTexture getBodyTexture(DeadBodyAtlas.BodyParams body) {
      String key = this.getBodyKey(body);
      DeadBodyAtlas.BodyTexture bodyTexture = this.entryMap.get(key);
      if (bodyTexture != null) {
         return bodyTexture;
      }

      DeadBodyAtlas.AtlasEntry entry = new DeadBodyAtlas.AtlasEntry();
      entry.key = key;
      entry.lightKey = this.getLightKey(body);
      entry.updateCounter = this.updateCounter;
      bodyTexture = new DeadBodyAtlas.BodyTexture();
      bodyTexture.entry = entry;
      this.entryMap.put(key, bodyTexture);
      this.renderJobs.add(DeadBodyAtlas.RenderJob.getNew().init(body, entry));
      return bodyTexture;
   }

   public void invalidateBodyTexture(DeadBodyAtlas.BodyTexture bodyTexture, IsoDeadBody body) {
      if (bodyTexture != null && bodyTexture.entry != null && bodyTexture.entry.ready && bodyTexture.entry.atlas.tex != null) {
         this.bodyParams.init(body);
         bodyTexture.entry.ready = false;
         DeadBodyAtlas.RenderJob job = DeadBodyAtlas.RenderJob.getNew().init(this.bodyParams, bodyTexture.entry);
         job.clearThisSlotOnly = true;
         this.renderJobs.add(job);
      }
   }

   public void checkLights(Texture entryTex, IsoDeadBody body) {
      if (entryTex != null) {
         DeadBodyAtlas.BodyTexture bodyTexture = this.entryMap.get(entryTex.getName());
         if (bodyTexture != null) {
            DeadBodyAtlas.AtlasEntry entry = bodyTexture.entry;
            if (entry != null && entry.tex == entryTex) {
               if (entry.updateCounter != this.updateCounter) {
                  entry.updateCounter = this.updateCounter;
                  this.bodyParams.init(body);
                  String lightKey = this.getLightKey(this.bodyParams);
                  if (!entry.lightKey.equals(lightKey)) {
                     this.entryMap.remove(entry.key);
                     entry.key = this.getBodyKey(this.bodyParams);
                     entry.lightKey = lightKey;
                     entryTex.setNameOnly(entry.key);
                     this.entryMap.put(entry.key, bodyTexture);
                     DeadBodyAtlas.RenderJob job = DeadBodyAtlas.RenderJob.getNew().init(this.bodyParams, entry);
                     job.clearThisSlotOnly = true;
                     this.renderJobs.add(job);
                     this.render();
                  }
               }
            }
         }
      }
   }

   public void checkLights(Texture entryTex, IsoZombie body) {
      if (entryTex != null) {
         DeadBodyAtlas.BodyTexture bodyTexture = this.entryMap.get(entryTex.getName());
         if (bodyTexture != null) {
            DeadBodyAtlas.AtlasEntry entry = bodyTexture.entry;
            if (entry != null && entry.tex == entryTex) {
               if (entry.updateCounter != this.updateCounter) {
                  entry.updateCounter = this.updateCounter;
                  this.bodyParams.init(body);
                  String lightKey = this.getLightKey(this.bodyParams);
                  if (!entry.lightKey.equals(lightKey)) {
                     this.entryMap.remove(entry.key);
                     entry.key = this.getBodyKey(this.bodyParams);
                     entry.lightKey = lightKey;
                     entryTex.setNameOnly(entry.key);
                     this.entryMap.put(entry.key, bodyTexture);
                     DeadBodyAtlas.RenderJob job = DeadBodyAtlas.RenderJob.getNew().init(this.bodyParams, entry);
                     job.clearThisSlotOnly = true;
                     this.renderJobs.add(job);
                     this.render();
                  }
               }
            }
         }
      }
   }

   private void assignEntryToAtlas(DeadBodyAtlas.AtlasEntry entry, int entryW, int entryH) {
      if (entry.atlas == null) {
         for (int i = 0; i < this.atlasList.size(); i++) {
            DeadBodyAtlas.Atlas atlas = this.atlasList.get(i);
            if (!atlas.isFull() && atlas.entryWidth == entryW && atlas.entryHeight == entryH) {
               atlas.addEntry(entry);
               return;
            }
         }

         DeadBodyAtlas.Atlas atlas = new DeadBodyAtlas.Atlas(1024, 1024, entryW, entryH);
         atlas.addEntry(entry);
         this.atlasList.add(atlas);
         this.newAtlasQ.add(atlas);
      }
   }

   private String getBodyKey(DeadBodyAtlas.BodyParams body) {
      if (body.baseVisual == this.characterTextureVisualFemale.humanVisual) {
         return "SZF_" + body.animSetName + "_" + body.stateName + "_" + body.dir + "_" + body.trackTime;
      }

      if (body.baseVisual == this.characterTextureVisualMale.humanVisual) {
         return "SZM_" + body.animSetName + "_" + body.stateName + "_" + body.dir + "_" + body.trackTime;
      }

      try {
         this.checksummer.reset();
         if (body.baseVisual instanceof AnimalVisual animalVisual) {
            this.checksummer.update((byte)body.dir.ordinal());
            this.checksummer.update((int)(PZMath.wrap(body.angle, 0.0F, (float) (Math.PI * 2)) * (180.0F / (float)Math.PI)));
            Model model = body.baseVisual.getModel();
            if (model != null) {
               this.checksummer.update(model.name);
            }

            this.checksummer.update(body.female);
            this.checksummer.update(body.skeleton);
            this.checksummer.update(body.animSetName);
            this.checksummer.update(body.stateName);
            this.checksummer.update((int)(animalVisual.getAnimalSize() * 100.0F));
            this.checksummer.update(animalVisual.getSkinTexture());

            for (int i = 0; i < body.attachedModelNames.size(); i++) {
               AttachedModelName amn = body.attachedModelNames.get(i);
               this.checksummer.update(amn.attachmentNameSelf);
               this.checksummer.update(amn.attachmentNameParent);
               this.checksummer.update(amn.modelName);
               this.checksummer.update((int)(amn.bloodLevel * 100.0F));
            }

            ItemVisuals itemVisuals = body.itemVisuals;

            for (int i = 0; i < itemVisuals.size(); i++) {
               ItemVisual var23 = (ItemVisual)itemVisuals.get(i);
            }

            this.checksummer.update(body.fallOnFront);
            this.checksummer.update(body.standing);
            this.checksummer.update(body.outside);
            this.checksummer.update(body.room);
            float ambientR = (int)(body.ambient.r * 10.0F) / 10.0F;
            this.checksummer.update((byte)(ambientR * 255.0F));
            float ambientG = (int)(body.ambient.g * 10.0F) / 10.0F;
            this.checksummer.update((byte)(ambientG * 255.0F));
            float ambientB = (int)(body.ambient.b * 10.0F) / 10.0F;
            this.checksummer.update((byte)(ambientB * 255.0F));
            this.checksummer.update((int)body.trackTime);

            for (int i = 0; i < body.lights.length; i++) {
               this.checksummer.update(body.lights[i], body.x, body.y, body.z);
            }

            return this.checksummer.checksumToString();
         } else {
            HumanVisual humanVisual = (HumanVisual)Type.tryCastTo(body.baseVisual, HumanVisual.class);
            this.checksummer.update((byte)body.dir.ordinal());
            this.checksummer.update((int)(PZMath.wrap(body.angle, 0.0F, (float) (Math.PI * 2)) * (180.0F / (float)Math.PI)));
            this.checksummer.update(humanVisual.getHairModel());
            this.checksummer.update(humanVisual.getBeardModel());
            this.checksummer.update(humanVisual.getSkinColor());
            this.checksummer.update(humanVisual.getSkinTexture());
            this.checksummer.update((int)(humanVisual.getTotalBlood() * 100.0F));
            this.checksummer.update(body.primaryHandItem);
            this.checksummer.update(body.secondaryHandItem);

            for (int i = 0; i < body.attachedModelNames.size(); i++) {
               AttachedModelName amn = body.attachedModelNames.get(i);
               this.checksummer.update(amn.attachmentNameSelf);
               this.checksummer.update(amn.attachmentNameParent);
               this.checksummer.update(amn.modelName);
               this.checksummer.update((int)(amn.bloodLevel * 100.0F));
            }

            this.checksummer.update(body.female);
            this.checksummer.update(body.zombie);
            this.checksummer.update(body.skeleton);
            this.checksummer.update(body.animSetName);
            this.checksummer.update(body.stateName);
            ItemVisuals itemVisuals = body.itemVisuals;

            for (int i = 0; i < itemVisuals.size(); i++) {
               ItemVisual item = (ItemVisual)itemVisuals.get(i);
               ClothingItem clothingItem = item.getClothingItem();
               if (clothingItem != null) {
                  this.checksummer.update(item.getBaseTexture(clothingItem));
                  this.checksummer.update(item.getTextureChoice(clothingItem));
                  this.checksummer.update(item.getTint(clothingItem));
                  this.checksummer.update(clothingItem.getModel(humanVisual.isFemale()));
                  this.checksummer.update((int)(item.getTotalBlood() * 100.0F));
               }
            }

            this.checksummer.update(body.fallOnFront);
            this.checksummer.update(body.standing);
            this.checksummer.update(body.outside);
            this.checksummer.update(body.room);
            float ambientR = (int)(body.ambient.r * 10.0F) / 10.0F;
            this.checksummer.update((byte)(ambientR * 255.0F));
            float ambientG = (int)(body.ambient.g * 10.0F) / 10.0F;
            this.checksummer.update((byte)(ambientG * 255.0F));
            float ambientB = (int)(body.ambient.b * 10.0F) / 10.0F;
            this.checksummer.update((byte)(ambientB * 255.0F));
            this.checksummer.update((int)body.trackTime);

            for (int i = 0; i < body.lights.length; i++) {
               this.checksummer.update(body.lights[i], body.x, body.y, body.z);
            }

            return this.checksummer.checksumToString();
         }
      } catch (Throwable t) {
         ExceptionLogger.logException(t);
         return "bogus";
      }
   }

   private String getLightKey(DeadBodyAtlas.BodyParams body) {
      try {
         this.checksummer.reset();
         this.checksummer.update(body.outside);
         this.checksummer.update(body.room);
         float ambientR = (int)(body.ambient.r * 10.0F) / 10.0F;
         this.checksummer.update((byte)(ambientR * 255.0F));
         float ambientG = (int)(body.ambient.g * 10.0F) / 10.0F;
         this.checksummer.update((byte)(ambientG * 255.0F));
         float ambientB = (int)(body.ambient.b * 10.0F) / 10.0F;
         this.checksummer.update((byte)(ambientB * 255.0F));

         for (int i = 0; i < body.lights.length; i++) {
            this.checksummer.update(body.lights[i], body.x, body.y, body.z);
         }

         return this.checksummer.checksumToString();
      } catch (Throwable t) {
         ExceptionLogger.logException(t);
         return "bogus";
      }
   }

   private void updateAtlasList() {
      for (DeadBodyAtlas.Atlas atlas = this.newAtlasQ.poll(); atlas != null; atlas = this.newAtlasQ.poll()) {
         this.atlasListMain.add(atlas);
      }
   }

   public void render() {
      this.updateAtlasList();

      for (int i = 0; i < this.atlasListMain.size(); i++) {
         DeadBodyAtlas.Atlas atlas = this.atlasListMain.get(i);
         if (atlas.clear) {
            SpriteRenderer.instance.drawGeneric(new DeadBodyAtlas.ClearAtlasTexture(atlas));
         }
      }

      if (!this.renderJobs.isEmpty()) {
         for (int i = 0; i < this.renderJobs.size(); i++) {
            DeadBodyAtlas.RenderJob job = this.renderJobs.get(i);
            if (job.done != 1 || job.renderRefCount <= 0) {
               if (job.done == 1 && job.renderRefCount == 0) {
                  this.renderJobs.remove(i--);
                  assert !JobPool.contains(job);
                  JobPool.push(job);
               } else if (job.renderMain()) {
                  job.renderRefCount++;
                  SpriteRenderer.instance.drawGeneric(job);
               }
            }
         }
      }
   }

   public void renderDebug() {
   }

   public void renderUI() {
      if (Core.debug && DebugOptions.instance.deadBodyAtlas.render.getValue()) {
         boolean bDepth = false;
         int d = 512 / Core.tileScale;
         int x = 0;
         int y = 0;
         this.updateAtlasList();

         for (int i = 0; i < this.atlasListMain.size(); i++) {
            DeadBodyAtlas.Atlas atlas = this.atlasListMain.get(i);
            Texture tex = atlas.tex;
            SpriteRenderer.instance.renderi(null, x, y, d, d, 1.0F, 1.0F, 1.0F, 0.75F, null);
            SpriteRenderer.instance.renderi(tex, x, y, d, d, 1.0F, 1.0F, 1.0F, 1.0F, null);
            float scale = (float)d / tex.getWidth();

            for (int xx = 0; xx <= tex.getWidth() / atlas.entryWidth; xx++) {
               SpriteRenderer.instance
                  .renderline(null, (int)(x + xx * atlas.entryWidth * scale), y, (int)(x + xx * atlas.entryWidth * scale), y + d, 0.5F, 0.5F, 0.5F, 1.0F);
            }

            for (int yy = 0; yy <= tex.getHeight() / atlas.entryHeight; yy++) {
               SpriteRenderer.instance
                  .renderline(
                     null, x, (int)(y + d - yy * atlas.entryHeight * scale), x + d, (int)(y + d - yy * atlas.entryHeight * scale), 0.5F, 0.5F, 0.5F, 1.0F
                  );
            }

            y += d + 4;
            if (y + d > Core.getInstance().getScreenHeight()) {
               y = 0;
               x += d + 4;
            }
         }

         TextureFBO fbo1 = ModelManager.instance.bitmap;
         ITexture tex = fbo1.getTexture();
         SpriteRenderer.instance.renderi(null, x, y, d, d, 1.0F, 1.0F, 1.0F, 1.0F, null);
         SpriteRenderer.instance.renderi((Texture)tex, x, y, d, d, 1.0F, 1.0F, 1.0F, 1.0F, null);
      }
   }

   public void Reset() {
      if (this.fbo != null) {
         this.fbo.destroyLeaveTexture();
         this.fbo = null;
      }

      this.newAtlasQ.clear();
      this.atlasListMain.clear();
      this.atlasList.forEach(DeadBodyAtlas.Atlas::Reset);
      this.atlasList.clear();
      this.entryMap.clear();
      this.characterTexturesFemale.clear();
      this.characterTexturesMale.clear();
      JobPool.forEach(DeadBodyAtlas.RenderJob::Reset);
      JobPool.clear();
      this.renderJobs.clear();
   }

   private void toBodyAtlas(DeadBodyAtlas.RenderJob job) {
      GL11.glPushAttrib(2048);
      if (this.fbo.getTexture() != job.entry.atlas.tex) {
         this.fbo.setTextureAndDepth(job.entry.atlas.tex, job.entry.atlas.depth);
      }

      this.fbo.startDrawing();
      GL11.glViewport(0, 0, this.fbo.getWidth(), this.fbo.getHeight());
      Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
      int texWid = job.entry.atlas.tex.getWidth();
      int texHgt = job.entry.atlas.tex.getHeight();
      projection.setOrtho2D(0.0F, texWid, texHgt, 0.0F);
      Core.getInstance().projectionMatrixStack.push(projection);
      Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
      modelView.identity();
      Core.getInstance().modelViewMatrixStack.push(modelView);
      GL11.glEnable(2929);
      GL11.glDepthFunc(519);
      GL11.glDepthMask(true);
      GL11.glEnable(3553);
      GL11.glDisable(3089);
      int program = pzopt.GlState.currentProgramNoSync(); // pzopt: glNoSync, ShaderHelper's record instead of a driver round trip
      GL20.glUseProgram(0);
      if (job.entry.atlas.clear) {
         GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         GL11.glClear(16640);
         GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
         job.entry.atlas.clear = false;
      }

      GL11.glEnable(3089);
      GL11.glScissor(job.entry.x, 1024 - job.entry.y - job.entry.h, job.entry.w, job.entry.h);
      if (job.clearThisSlotOnly) {
         GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         GL11.glClear(16640);
         GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
      }

      TextureFBO mmBitmap = ModelManager.instance.bitmap;
      int w = mmBitmap.getTexture().getWidth() / 8 * Core.tileScale;
      int h = mmBitmap.getTexture().getHeight() / 8 * Core.tileScale;
      int x = job.entry.x - (w - job.entry.atlas.entryWidth) / 2;
      int y = job.entry.y - (h - job.entry.atlas.entryHeight) / 2;
      if (deadBodyAtlasShader == null) {
         deadBodyAtlasShader = new DeadBodyAtlas.DeadBodyAtlasShader("DeadBodyAtlas");
      }

      if (deadBodyAtlasShader.getShaderProgram().isCompiled()) {
         GL13.glActiveTexture(33985);
         GL11.glEnable(3553);
         GL11.glBindTexture(3553, mmBitmap.getDepthTexture().getID());
         GL13.glActiveTexture(33984);
         GL11.glDepthMask(true);
         GL11.glDepthFunc(519);
         VBORenderer vbor = VBORenderer.getInstance();
         vbor.startRun(vbor.formatPositionColorUv);
         vbor.setMode(7);
         vbor.setDepthTest(true);
         vbor.setShaderProgram(deadBodyAtlasShader.getShaderProgram());
         vbor.setTextureID(((Texture)mmBitmap.getTexture()).getTextureId());
         vbor.cmdUseProgram(deadBodyAtlasShader.getShaderProgram());
         vbor.cmdShader1f("zDepthBlendZ", 0.0F);
         vbor.cmdShader1f("zDepthBlendToZ", 1.0F);
         vbor.addQuad(x, y, 0.0F, 0.0F, x + w, y + h, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F);
         vbor.endRun();
         vbor.flush();
         GL13.glActiveTexture(33985);
         GL11.glBindTexture(3553, 0);
         GL11.glDisable(3553);
         GL13.glActiveTexture(33984);
         GL11.glBindTexture(3553, Texture.lastTextureID = 0); // pzopt: decompiler fix, as the jar (dup of the assigned value)
      } else {
         mmBitmap.getTexture().bind();
         GL11.glBegin(7);
         GL11.glColor3f(1.0F, 1.0F, 1.0F);
         GL11.glTexCoord2f(0.0F, 0.0F);
         GL11.glVertex2i(x, y);
         GL11.glTexCoord2f(1.0F, 0.0F);
         GL11.glVertex2i(x + w, y);
         GL11.glTexCoord2f(1.0F, 1.0F);
         GL11.glVertex2i(x + w, y + h);
         GL11.glTexCoord2f(0.0F, 1.0F);
         GL11.glVertex2i(x, y + h);
         GL11.glEnd();
         GL11.glBindTexture(3553, Texture.lastTextureID = 0); // pzopt: decompiler fix, as the jar (dup of the assigned value)
      }

      int playerIndex = SpriteRenderer.instance.getRenderingPlayerIndex();
      int x1 = playerIndex != 0 && playerIndex != 2 ? Core.getInstance().getOffscreenTrueWidth() / 2 : 0;
      int y1 = playerIndex != 0 && playerIndex != 1 ? Core.getInstance().getOffscreenTrueHeight() / 2 : 0;
      int w1 = Core.getInstance().getOffscreenTrueWidth();
      int h1 = Core.getInstance().getOffscreenTrueHeight();
      if (IsoPlayer.numPlayers > 1) {
         w1 /= 2;
      }

      if (IsoPlayer.numPlayers > 2) {
         h1 /= 2;
      }

      GL11.glScissor(x1, y1, w1, h1);
      this.fbo.endDrawing();
      ShaderHelper.glUseProgramObjectARB(program);
      GL11.glEnable(3089);
      Core.getInstance().projectionMatrixStack.pop();
      Core.getInstance().modelViewMatrixStack.pop();
      GL11.glPopAttrib();
      job.entry.ready = true;
      job.done = 1;
   }

   private final class Atlas {
      public final int entryWidth;
      public final int entryHeight;
      public Texture tex;
      public Texture depth;
      public final ArrayList<DeadBodyAtlas.AtlasEntry> entryList = new ArrayList<>();
      public boolean clear = true;

      public Atlas(final int w, final int h, final int entryW, final int entryH) {
         this.entryWidth = entryW;
         this.entryHeight = entryH;
         this.tex = new Texture(w, h, 16);
         this.depth = new Texture(w, h, 512);
         if (DeadBodyAtlas.this.fbo == null) {
            DeadBodyAtlas.this.fbo = new TextureFBO(this.tex, this.depth, false);
         }
      }

      public boolean isFull() {
         int columns = this.tex.getWidth() / this.entryWidth;
         int rows = this.tex.getHeight() / this.entryHeight;
         return this.entryList.size() >= columns * rows;
      }

      public DeadBodyAtlas.AtlasEntry addBody(String key) {
         int columns = this.tex.getWidth() / this.entryWidth;
         int index = this.entryList.size();
         int col = index % columns;
         int row = index / columns;
         DeadBodyAtlas.AtlasEntry entry = new DeadBodyAtlas.AtlasEntry();
         entry.atlas = this;
         entry.key = key;
         entry.x = col * this.entryWidth;
         entry.y = row * this.entryHeight;
         entry.w = this.entryWidth;
         entry.h = this.entryHeight;
         entry.tex = this.tex.split(key, entry.x, this.tex.getHeight() - (entry.y + this.entryHeight), entry.w, entry.h);
         entry.tex.setName(key);
         this.entryList.add(entry);
         return entry;
      }

      public void addEntry(DeadBodyAtlas.AtlasEntry entry) {
         int columns = this.tex.getWidth() / this.entryWidth;
         int index = this.entryList.size();
         int col = index % columns;
         int row = index / columns;
         entry.atlas = this;
         entry.x = col * this.entryWidth;
         entry.y = row * this.entryHeight;
         entry.w = this.entryWidth;
         entry.h = this.entryHeight;
         entry.tex = this.tex.split(entry.key, entry.x, this.tex.getHeight() - (entry.y + this.entryHeight), entry.w, entry.h);
         entry.tex.setName(entry.key);
         this.entryList.add(entry);
      }

      public void Reset() {
         this.entryList.forEach(DeadBodyAtlas.AtlasEntry::Reset);
         this.entryList.clear();
         if (!this.tex.isDestroyed()) {
            RenderThread.invokeOnRenderContext(() -> GL11.glDeleteTextures(this.tex.getID()));
         }

         this.tex = null;
         if (!this.depth.isDestroyed()) {
            RenderThread.invokeOnRenderContext(() -> GL11.glDeleteTextures(this.depth.getID()));
         }

         this.depth = null;
      }
   }

   private static final class AtlasEntry {
      public DeadBodyAtlas.Atlas atlas;
      public String key;
      public String lightKey;
      public int updateCounter;
      public int x;
      public int y;
      public int w;
      public int h;
      public float offsetX;
      public float offsetY;
      public Texture tex;
      public final ShadowParams shadowParams = new ShadowParams(1.0F, 1.0F, 1.0F);
      public boolean ready;

      public void Reset() {
         this.atlas = null;
         this.tex.destroy();
         this.tex = null;
         this.ready = false;
      }
   }

   public static final class BodyParams {
      public BaseVisual baseVisual;
      public final ItemVisuals itemVisuals = new ItemVisuals();
      public IsoDirections dir;
      public float angle;
      public boolean female;
      public boolean zombie;
      public boolean skeleton;
      public String animSetName;
      public String stateName;
      public final HashMap<String, String> variables = new HashMap<>();
      public boolean standing;
      public String primaryHandItem;
      public String secondaryHandItem;
      public final AttachedModelNames attachedModelNames = new AttachedModelNames();
      public float x;
      public float y;
      public float z;
      public float trackTime;
      public boolean outside;
      public boolean room;
      public final ColorInfo ambient = new ColorInfo();
      public boolean fallOnFront;
      public boolean killedByFall;
      public final ResultLight[] lights = new ResultLight[5];
      public IGrappleable grappleable;
      public TwistableBoneTransform[] diedBoneTransforms;
      boolean ragdollFall;

      public BodyParams() {
         for (int i = 0; i < this.lights.length; i++) {
            this.lights[i] = new ResultLight();
         }
      }

      public void init(DeadBodyAtlas.BodyParams body) {
         this.baseVisual = body.baseVisual;
         this.itemVisuals.clear();
         PZArrayUtil.addAll(this.itemVisuals, body.itemVisuals);
         this.dir = body.dir;
         this.angle = body.angle;
         this.female = body.female;
         this.zombie = body.zombie;
         this.skeleton = body.skeleton;
         this.animSetName = body.animSetName;
         this.stateName = body.stateName;
         this.variables.clear();
         this.variables.putAll(body.variables);
         this.standing = body.standing;
         this.primaryHandItem = body.primaryHandItem;
         this.secondaryHandItem = body.secondaryHandItem;
         this.attachedModelNames.copyFrom(body.attachedModelNames);
         this.x = body.x;
         this.y = body.y;
         this.z = body.z;
         this.trackTime = body.trackTime;
         this.fallOnFront = body.fallOnFront;
         this.ragdollFall = body.ragdollFall;
         this.outside = body.outside;
         this.room = body.room;
         this.ambient.set(body.ambient.r, body.ambient.g, body.ambient.b, 1.0F);

         for (int i = 0; i < this.lights.length; i++) {
            this.lights[i].copyFrom(body.lights[i]);
         }

         this.grappleable = body.grappleable;
         this.diedBoneTransforms = (TwistableBoneTransform[])Pool.tryRelease(this.diedBoneTransforms);
         this.diedBoneTransforms = (TwistableBoneTransform[])PZArrayUtil.clone(
            body.diedBoneTransforms, TwistableBoneTransform::alloc, TwistableBoneTransform::set
         );
      }

      public void init(IsoDeadBody body) {
         this.baseVisual = body.getVisual();
         body.getItemVisuals(this.itemVisuals);
         this.dir = body.getForwardIsoDirection();
         this.angle = body.getAngle();
         this.female = body.isFemale();
         this.zombie = body.isZombie();
         this.skeleton = body.isSkeleton();
         this.primaryHandItem = null;
         this.secondaryHandItem = null;
         this.attachedModelNames.initFrom(body.getAttachedItems());
         this.animSetName = "zombie";
         this.stateName = body.isBeingGrappled() ? "grappled" : "onground";
         this.variables.clear();
         this.standing = false;
         this.trackTime = 0.0F;
         if (body.getPrimaryHandItem() == null && body.getSecondaryHandItem() == null) {
            if (!body.isAnimal() && !body.isZombie() && body.isKilledByFall()) {
               this.animSetName = "player";
               this.stateName = "deadbody";
               this.trackTime = 1.0F;
            }
         } else {
            if (body.getPrimaryHandItem() != null && !StringUtils.isNullOrEmpty(body.getPrimaryHandItem().getStaticModel())) {
               this.primaryHandItem = body.getPrimaryHandItem().getStaticModel();
            }

            if (body.getSecondaryHandItem() != null && !StringUtils.isNullOrEmpty(body.getSecondaryHandItem().getStaticModel())) {
               this.secondaryHandItem = body.getSecondaryHandItem().getStaticModel();
            }

            this.animSetName = "player";
            this.stateName = "deadbody";
            if (body.isKilledByFall()) {
               this.trackTime = 1.0F;
            }
         }

         if (body.isAnimal()) {
            this.animSetName = body.animalAnimSet;
            this.stateName = "deadbody";
         }

         this.x = body.getX();
         this.y = body.getY();
         this.z = body.getZ();
         this.fallOnFront = body.isFallOnFront();
         this.killedByFall = body.isKilledByFall();
         this.ragdollFall = body.ragdollFall;
         this.outside = body.square != null && body.square.isOutside();
         this.room = body.square != null && body.square.getRoom() != null;
         this.initAmbient(body.square);
         this.initLights(body.square);
         this.grappleable = body;
         this.initDeathPose(body);
      }

      public void init(IsoZombie body) {
         this.baseVisual = body.getHumanVisual();
         body.getItemVisuals(this.itemVisuals);
         this.dir = body.getForwardIsoDirection();
         this.angle = body.getAnimAngleRadians();
         this.female = body.isFemale();
         this.zombie = true;
         this.skeleton = body.isSkeleton();
         this.primaryHandItem = null;
         this.secondaryHandItem = null;
         this.attachedModelNames.initFrom(body.getAttachedItems());
         this.animSetName = "zombie";
         this.stateName = "onground";
         this.variables.clear();
         this.standing = false;
         this.x = body.getX();
         this.y = body.getY();
         this.z = body.getZ();
         this.trackTime = 0.0F;
         this.fallOnFront = body.isFallOnFront();
         this.killedByFall = body.isKilledByFall();
         this.ragdollFall = body.isRagdollFall();
         this.outside = body.getCurrentSquare() != null && body.getCurrentSquare().isOutside();
         this.room = body.getCurrentSquare() != null && body.getCurrentSquare().getRoom() != null;
         this.initAmbient(body.getCurrentSquare());
         this.initLights(body.getCurrentSquare());
         this.grappleable = body;
         this.initDeathPose(body);
      }

      public void init(IsoMannequin body) {
         this.baseVisual = body.getHumanVisual();
         body.getItemVisuals(this.itemVisuals);
         this.dir = body.getForwardIsoDirection();
         this.angle = this.dir.ToVector().getDirection();
         this.female = body.isFemale();
         this.zombie = body.isZombie();
         this.skeleton = body.isSkeleton();
         this.primaryHandItem = null;
         this.secondaryHandItem = null;
         this.attachedModelNames.clear();
         this.animSetName = body.getAnimSetName();
         this.stateName = body.getAnimStateName();
         this.variables.clear();
         body.getVariables(this.variables);
         this.standing = true;
         this.x = body.getX();
         this.y = body.getY();
         this.z = body.getZ();
         this.trackTime = 0.0F;
         this.fallOnFront = false;
         this.outside = body.square != null && body.square.isOutside();
         this.room = body.square != null && body.square.getRoom() != null;
         this.initAmbient(body.square);
         this.initLights(null);
         this.grappleable = null;
         this.initDeathPose((IAnimatable)Type.tryCastTo(body, IAnimatable.class));
      }

      public void init(IHumanVisual iHumanVisual, IsoDirections dir, String animSet, String stateName, float trackTime) {
         this.baseVisual = iHumanVisual.getHumanVisual();
         iHumanVisual.getItemVisuals(this.itemVisuals);
         this.dir = dir;
         this.angle = dir.ToVector().getDirection();
         this.female = iHumanVisual.isFemale();
         this.zombie = iHumanVisual.isZombie();
         this.skeleton = iHumanVisual.isSkeleton();
         this.primaryHandItem = null;
         this.secondaryHandItem = null;
         this.attachedModelNames.clear();
         this.animSetName = animSet;
         this.stateName = stateName;
         this.variables.clear();
         this.standing = true;
         this.x = 0.0F;
         this.y = 0.0F;
         this.z = 0.0F;
         this.trackTime = trackTime;
         this.fallOnFront = false;
         this.outside = true;
         this.room = false;
         this.ambient.set(1.0F, 1.0F, 1.0F, 1.0F);
         this.initLights(null);
         this.grappleable = null;
         this.initDeathPose((IAnimatable)Type.tryCastTo(iHumanVisual, IAnimatable.class));
      }

      private void initDeathPose(IsoDeadBody body) {
         this.diedBoneTransforms = (TwistableBoneTransform[])Pool.tryRelease(this.diedBoneTransforms);
         if (body != null && !PZArrayUtil.isNullOrEmpty(body.getDiedBoneTransforms())) {
            this.diedBoneTransforms = (TwistableBoneTransform[])PZArrayUtil.clone(
               body.getDiedBoneTransforms(), TwistableBoneTransform::alloc, TwistableBoneTransform::set
            );
         }
      }

      private void initDeathPose(IAnimatable body) {
         this.diedBoneTransforms = (TwistableBoneTransform[])Pool.tryRelease(this.diedBoneTransforms);
         if (body != null && body.hasAnimationPlayer()) {
            AnimationPlayer animationPlayer = body.getAnimationPlayer();
            if (!animationPlayer.isBoneTransformsNeedFirstFrame()) {
               int numberOfBones = animationPlayer.getNumBones();
               this.diedBoneTransforms = TwistableBoneTransform.allocArray(numberOfBones);

               for (int boneIndex = 0; boneIndex < numberOfBones; boneIndex++) {
                  animationPlayer.getBoneTransformAt(boneIndex, this.diedBoneTransforms[boneIndex]);
               }
            }
         }
      }

      void initAmbient(IsoGridSquare square) {
         this.ambient.set(1.0F, 1.0F, 1.0F, 1.0F);
      }

      void initLights(IsoGridSquare square) {
         for (int i = 0; i < this.lights.length; i++) {
            this.lights[i].radius = 0;
         }

         if (square != null) {
            ILighting lighting = square.lighting[0];
            int lightCount = lighting.resultLightCount();

            for (int i = 0; i < lightCount; i++) {
               this.lights[i].copyFrom(lighting.getResultLight(i));
            }
         }
      }

      public void Reset() {
         this.baseVisual = null;
         this.itemVisuals.clear();
         Arrays.fill(this.lights, null);
         this.grappleable = null;
         this.diedBoneTransforms = (TwistableBoneTransform[])Pool.tryRelease(this.diedBoneTransforms);
      }
   }

   public static final class BodyTexture {
      DeadBodyAtlas.AtlasEntry entry;

      public ShadowParams getShadowParams() {
         return this.entry.shadowParams;
      }

      public Texture getTexture() {
         return this.entry == null ? null : this.entry.tex;
      }

      public float getRenderX(float sx) {
         return sx - this.entry.w / 2.0F - this.entry.offsetX;
      }

      public float getRenderY(float sy) {
         return sy - this.entry.h / 2.0F - this.entry.offsetY;
      }

      public float getRenderWidth() {
         return this.entry.w;
      }

      public float getRenderHeight() {
         return this.entry.h;
      }

      public void render(float originX, float originY, float originZ, float x, float y, float r, float g, float b, float a) {
         if (PerformanceSettings.fboRenderChunk) {
            DeadBodyAtlas.BodyTextureDepthDrawer rbt = (DeadBodyAtlas.BodyTextureDepthDrawer)DeadBodyAtlas.s_BodyTextureDepthDrawerPool.alloc();
            rbt.init(this, originX, originY, originZ, x, y, r, g, b, a);
            SpriteRenderer.instance.drawGeneric(rbt);
         } else if (this.entry.ready && this.entry.tex.isReady()) {
            this.entry
               .tex
               .render(x - this.entry.w / 2.0F - this.entry.offsetX, y - this.entry.h / 2.0F - this.entry.offsetY, this.entry.w, this.entry.h, r, g, b, a, null);
         } else {
            DeadBodyAtlas.BodyTextureDrawer btd = ((DeadBodyAtlas.BodyTextureDrawer)DeadBodyAtlas.instance.bodyTextureDrawerPool.alloc())
               .init(this, x, y, r, g, b, a);
            SpriteRenderer.instance.drawGeneric(btd);
         }
      }

      public void renderObjectPicker(float sx, float sy, ColorInfo lightInfo, IsoGridSquare square, IsoObject object) {
         if (this.entry.ready) {
            IsoObjectPicker.Instance
               .Add((int)(sx - this.entry.w / 2), (int)(sy - this.entry.h / 2), this.entry.w, this.entry.h, square, object, false, 1.0F, 1.0F);
         }
      }
   }

   private static final class BodyTextureDepthDrawer extends GenericDrawer {
      DeadBodyAtlas.BodyTexture bodyTexture;
      float ox;
      float oy;
      float oz;
      float x;
      float y;
      float r;
      float g;
      float b;
      float a;

      DeadBodyAtlas.BodyTextureDepthDrawer init(
         DeadBodyAtlas.BodyTexture bodyTexture, float originX, float originY, float originZ, float x, float y, float r, float g, float b, float a
      ) {
         this.bodyTexture = bodyTexture;
         this.ox = originX;
         this.oy = originY;
         this.oz = originZ;
         this.x = x;
         this.y = y;
         float f = 1.25F;
         this.r = PZMath.clamp(r * 1.25F, 0.0F, 1.0F);
         this.g = PZMath.clamp(g * 1.25F, 0.0F, 1.0F);
         this.b = PZMath.clamp(b * 1.25F, 0.0F, 1.0F);
         this.a = a;
         return this;
      }

      public void render() {
         if (this.bodyTexture.entry.ready && this.bodyTexture.entry.tex.isReady()) {
            if (DeadBodyAtlas.deadBodyAtlasShader == null) {
               DeadBodyAtlas.deadBodyAtlasShader = new DeadBodyAtlas.DeadBodyAtlasShader("DeadBodyAtlas");
            }

            if (DeadBodyAtlas.deadBodyAtlasShader.getShaderProgram().isCompiled()) {
               DeadBodyAtlas.AtlasEntry entry = this.bodyTexture.entry;
               float x1 = this.x - entry.w / 2.0F - entry.offsetX;
               float y1 = this.y - entry.h / 2.0F - entry.offsetY;
               int w = entry.w;
               int h = entry.h;
               GL13.glActiveTexture(33985);
               GL11.glEnable(3553);
               GL11.glBindTexture(3553, entry.atlas.depth.getID());
               GL13.glActiveTexture(33984);
               GL11.glDepthMask(true);
               GL11.glDepthFunc(515);
               float playerX = (Float)Core.getInstance().floatParamMap.get(0);
               float playerY = (Float)Core.getInstance().floatParamMap.get(1);
               int playerIndex = SpriteRenderer.instance.getRenderingPlayerIndex();
               PlayerCamera camera = SpriteRenderer.instance.getRenderingPlayerCamera(playerIndex);
               float jx = camera.fixJigglyModelsSquareX;
               float jy = camera.fixJigglyModelsSquareY;
               float offsetX = entry.offsetX;
               float offsetY = entry.offsetY;
               float wx = -(offsetX + 2.0F * offsetY) / (64.0F * Core.tileScale);
               float wy = -(offsetX - 2.0F * offsetY) / (-64.0F * Core.tileScale);
               float f = 4.4F;
               float depthNear = IsoDepthHelper.getSquareDepthData(
                     PZMath.fastfloor(playerX), PZMath.fastfloor(playerY), this.ox + jx + wx + 2.2F, this.oy + jy + wy + 2.2F, this.oz + 1.0F
                  )
                  .depthStart;
               float depthFar = IsoDepthHelper.getSquareDepthData(
                     PZMath.fastfloor(playerX), PZMath.fastfloor(playerY), this.ox + jx + wx - 2.2F, this.oy + jy + wy - 2.2F, this.oz
                  )
                  .depthStart;
               depthNear += 0.0014F;
               depthFar += 0.0014F;
               VBORenderer vbor = VBORenderer.getInstance();
               vbor.startRun(vbor.formatPositionColorUv);
               vbor.setMode(7);
               vbor.setDepthTest(true);
               vbor.setShaderProgram(DeadBodyAtlas.deadBodyAtlasShader.getShaderProgram());
               Texture tex = entry.tex;
               vbor.setTextureID(tex.getTextureId());
               vbor.cmdUseProgram(DeadBodyAtlas.deadBodyAtlasShader.getShaderProgram());
               vbor.cmdShader1f("zDepthBlendZ", depthNear);
               vbor.cmdShader1f("zDepthBlendToZ", depthFar);
               vbor.addQuad(x1, y1, tex.getXStart(), tex.getYStart(), x1 + w, y1 + h, tex.getXEnd(), tex.getYEnd(), 0.0F, this.r, this.g, this.b, this.a);
               vbor.endRun();
               vbor.flush();
               GL13.glActiveTexture(33985);
               GL11.glBindTexture(3553, 0);
               GL11.glDisable(3553);
               GL13.glActiveTexture(33984);
               ShaderHelper.glUseProgramObjectARB(0);
               Texture.lastTextureID = -1;
               SpriteRenderer.ringBuffer.restoreBoundTextures = true;
               GLStateRenderThread.restore();
            }
         }
      }

      public void postRender() {
         DeadBodyAtlas.s_BodyTextureDepthDrawerPool.release(this);
      }
   }

   private static final class BodyTextureDrawer extends GenericDrawer {
      DeadBodyAtlas.BodyTexture bodyTexture;
      float x;
      float y;
      float r;
      float g;
      float b;
      float a;

      DeadBodyAtlas.BodyTextureDrawer init(DeadBodyAtlas.BodyTexture bodyTexture, float x, float y, float r, float g, float b, float a) {
         this.bodyTexture = bodyTexture;
         this.x = x;
         this.y = y;
         this.r = r;
         this.g = g;
         this.b = b;
         this.a = a;
         return this;
      }

      public void render() {
         DeadBodyAtlas.AtlasEntry entry = this.bodyTexture.entry;
         if (entry.ready && entry.tex.isReady()) {
            int x = (int)(this.x - entry.w / 2.0F - entry.offsetX);
            int y = (int)(this.y - entry.h / 2.0F - entry.offsetY);
            int w = entry.w;
            int h = entry.h;
            VBORenderer vbor = VBORenderer.getInstance();
            vbor.startRun(vbor.formatPositionColorUv);
            vbor.setTextureID(entry.tex.getTextureId());
            vbor.addQuad(x, y, entry.tex.xStart, entry.tex.yStart, x + w, y + h, entry.tex.xEnd, entry.tex.yEnd, 0.0F, this.r, this.g, this.b, this.a);
            vbor.endRun();
            vbor.flush();
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
      }

      public void postRender() {
         this.bodyTexture = null;
         DeadBodyAtlas.instance.bodyTextureDrawerPool.release(this);
      }
   }

   private static final class CharacterTextureVisual implements IHumanVisual {
      final HumanVisual humanVisual = new HumanVisual(this);
      boolean female;

      CharacterTextureVisual(boolean female) {
         this.female = female;
         this.humanVisual.setHairModel("");
         this.humanVisual.setBeardModel("");
      }

      public HumanVisual getHumanVisual() {
         return this.humanVisual;
      }

      public void getItemVisuals(ItemVisuals itemVisuals) {
         itemVisuals.clear();
      }

      public boolean isFemale() {
         return this.female;
      }

      public boolean isZombie() {
         return true;
      }

      public boolean isSkeleton() {
         return false;
      }
   }

   private static final class Checksummer {
      private MessageDigest md;
      private final StringBuilder sb = new StringBuilder();

      public void reset() throws NoSuchAlgorithmException {
         if (this.md == null) {
            this.md = MessageDigest.getInstance("MD5");
         }

         this.md.reset();
      }

      public void update(byte b) {
         this.md.update(b);
      }

      public void update(boolean b) {
         this.md.update((byte)(b ? 1 : 0));
      }

      public void update(int i) {
         this.md.update((byte)(i & 0xFF));
         this.md.update((byte)(i >> 8 & 0xFF));
         this.md.update((byte)(i >> 16 & 0xFF));
         this.md.update((byte)(i >> 24 & 0xFF));
      }

      public void update(String add) {
         if (add != null && !add.isEmpty()) {
            this.md.update(add.getBytes());
         }
      }

      public void update(ImmutableColor color) {
         this.update((byte)(color.r * 255.0F));
         this.update((byte)(color.g * 255.0F));
         this.update((byte)(color.b * 255.0F));
      }

      public void update(ResultLight ls, float x, float y, float z) {
         if (ls != null && ls.radius > 0) {
            this.update((int)(ls.x - x));
            this.update((int)(ls.y - y));
            this.update((int)(ls.z - z));
            this.update((byte)(ls.r * 255.0F));
            this.update((byte)(ls.g * 255.0F));
            this.update((byte)(ls.b * 255.0F));
            this.update((byte)ls.radius);
         }
      }

      public String checksumToString() {
         byte[] mdbytes = this.md.digest();
         this.sb.setLength(0);

         for (int i = 0; i < mdbytes.length; i++) {
            this.sb.append(mdbytes[i] & 255);
         }

         return this.sb.toString();
      }
   }

   private static final class ClearAtlasTexture extends GenericDrawer {
      DeadBodyAtlas.Atlas atlas;

      ClearAtlasTexture(DeadBodyAtlas.Atlas atlas) {
         this.atlas = atlas;
      }

      public void render() {
         TextureFBO fbo = DeadBodyAtlas.instance.fbo;
         if (fbo != null && this.atlas.tex != null) {
            if (this.atlas.clear) {
               if (fbo.getTexture() != this.atlas.tex) {
                  fbo.setTextureAndDepth(this.atlas.tex, this.atlas.depth);
               }

               fbo.startDrawing(false, false);
               GL11.glPushAttrib(2048);
               GL11.glViewport(0, 0, fbo.getWidth(), fbo.getHeight());
               Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
               int texWid = this.atlas.tex.getWidth();
               int texHgt = this.atlas.tex.getHeight();
               projection.setOrtho2D(0.0F, texWid, texHgt, 0.0F);
               Core.getInstance().projectionMatrixStack.push(projection);
               Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
               modelView.identity();
               Core.getInstance().modelViewMatrixStack.push(modelView);
               GL11.glDisable(3089);
               GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
               GL11.glClear(16640);
               GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
               fbo.endDrawing();
               GL11.glEnable(3089);
               Core.getInstance().projectionMatrixStack.pop();
               Core.getInstance().modelViewMatrixStack.pop();
               GL11.glPopAttrib();
               this.atlas.clear = false;
            }
         }
      }
   }

   public static final class DeadBodyAtlasShader extends Shader {
      public DeadBodyAtlasShader(String name) {
         super(name);
      }

      protected void onCompileSuccess(ShaderProgram sender) {
         sender.Start();
         sender.setSamplerUnit("DIFFUSE", 0);
         sender.setSamplerUnit("DEPTH", 1);
         sender.End();
      }
   }

   private static final class DebugDrawInWorld extends GenericDrawer {
      DeadBodyAtlas.RenderJob job;
      boolean rendered;

      public void init(DeadBodyAtlas.RenderJob job) {
         this.job = job;
         this.rendered = false;
      }

      public void render() {
         this.job.animatedModel.DoRenderToWorld(this.job.body.x, this.job.body.y, this.job.body.z, this.job.animPlayerAngle);
         this.rendered = true;
      }

      public void postRender() {
         if (this.job.animatedModel != null) {
            if (this.rendered) {
               assert !DeadBodyAtlas.JobPool.contains(this.job);
               DeadBodyAtlas.JobPool.push(this.job);
            } else {
               assert !DeadBodyAtlas.JobPool.contains(this.job);
               DeadBodyAtlas.JobPool.push(this.job);
            }

            this.job.animatedModel.postRender(this.rendered);
         }
      }
   }

   private static final class RenderJob extends GenericDrawer {
      private static final float SIZE_V = 42.75F;
      public final DeadBodyAtlas.BodyParams body = new DeadBodyAtlas.BodyParams();
      public DeadBodyAtlas.AtlasEntry entry;
      public AnimatedModel animatedModel;
      public float animPlayerAngle;
      public int done;
      public int renderRefCount;
      public boolean clearThisSlotOnly;
      int entryW;
      int entryH;
      final int[] viewport = new int[4];
      final Matrix4f matri4f = new Matrix4f();
      final Matrix4f projection = new Matrix4f();
      final Matrix4f modelView = new Matrix4f();
      final Vector3f scenePos = new Vector3f();
      final float[] bounds = new float[4];
      final float[] offset = new float[3];

      public static DeadBodyAtlas.RenderJob getNew() {
         return DeadBodyAtlas.JobPool.isEmpty() ? new DeadBodyAtlas.RenderJob() : DeadBodyAtlas.JobPool.pop();
      }

      public DeadBodyAtlas.RenderJob init(DeadBodyAtlas.BodyParams body, DeadBodyAtlas.AtlasEntry entry) {
         this.body.init(body);
         this.entry = entry;
         if (this.animatedModel == null) {
            this.animatedModel = new AnimatedModel();
            this.animatedModel.setAnimate(false);
         }

         this.animatedModel.setAnimSetName(body.animSetName);
         this.animatedModel.setState(body.stateName);
         this.animatedModel.setPrimaryHandModelName(body.primaryHandItem);
         this.animatedModel.setSecondaryHandModelName(body.secondaryHandItem);
         this.animatedModel.setAttachedModelNames(body.attachedModelNames);
         this.animatedModel.setAmbient(body.ambient, body.outside, body.room);
         this.animatedModel.setLights(body.lights, body.x, body.y, body.z);
         this.animatedModel.setVariable("FallOnFront", body.fallOnFront);
         this.animatedModel.setVariable("KilledByFall", body.killedByFall);
         body.variables.forEach((k, v) -> this.animatedModel.setVariable(k, v));
         this.animatedModel.setModelData(body.baseVisual, body.itemVisuals);
         this.animatedModel.setAngle(DeadBodyAtlas.tempVector2.setLengthAndDirection(this.body.angle, 1.0F));
         this.setDeathPose();
         this.animatedModel.setTrackTime(body.trackTime);
         this.animatedModel.setGrappleable(body.grappleable);
         this.animatedModel.update();
         this.clearThisSlotOnly = false;
         this.done = 0;
         this.renderRefCount = 0;
         return this;
      }

      private void setDeathPose() {
         if (!PZArrayUtil.isNullOrEmpty(this.body.diedBoneTransforms)) {
            AnimationPlayer animationPlayer = this.animatedModel.getAnimationPlayer();
            animationPlayer.stopAll();
            AnimationTrack animationTrack = animationPlayer.play("DeathPose", true, true, -1.0F);
            if (animationTrack != null) {
               animationTrack.setBlendWeight(1.0F);
               animationTrack.initRagdollTransforms(this.body.diedBoneTransforms);
            }
         }
      }

      public boolean renderMain() {
         if (!this.animatedModel.isReadyToRender()) {
            return false;
         }

         this.animatedModel.renderMain();
         this.animPlayerAngle = this.animatedModel.getAnimationPlayer().getRenderedAngle();
         boolean bRagdoll = this.body.ragdollFall && !PZArrayUtil.isNullOrEmpty(this.body.diedBoneTransforms);
         this.animatedModel.calculateShadowParams(this.entry.shadowParams, bRagdoll);
         return true;
      }

      public void render() {
         if (this.done != 1) {
            GL11.glDepthMask(true);
            GL11.glColorMask(true, true, true, true);
            GL11.glDisable(3089);
            GL11.glPushAttrib(2048);
            ModelManager.instance.bitmap.startDrawing(true, true);
            GL11.glViewport(0, 0, ModelManager.instance.bitmap.getWidth(), ModelManager.instance.bitmap.getHeight());
            this.calcModelOffset(this.offset);
            this.animatedModel.setOffsetWhileRendering(this.offset[0], this.offset[1], this.offset[2]);
            this.animatedModel
               .DoRender(
                  0,
                  0,
                  ModelManager.instance.bitmap.getTexture().getWidth(),
                  ModelManager.instance.bitmap.getTexture().getHeight(),
                  42.75F,
                  this.animPlayerAngle
               );
            if (this.animatedModel.isRendered()) {
               this.calcEntrySize();
            }

            ModelManager.instance.bitmap.endDrawing();
            GL11.glPopAttrib();
            if (this.animatedModel.isRendered()) {
               DeadBodyAtlas.instance.assignEntryToAtlas(this.entry, this.entryW, this.entryH);
               DeadBodyAtlas.instance.toBodyAtlas(this);
            }
         }
      }

      public void postRender() {
         if (this.animatedModel != null) {
            this.animatedModel.postRender(this.done == 1);
            assert this.renderRefCount > 0;
            this.renderRefCount--;
         }
      }

      void calcMatrices(Matrix4f projection, Matrix4f modelView, float offsetX, float offsetY, float offsetZ) {
         int w = ModelManager.instance.bitmap.getWidth();
         int h = ModelManager.instance.bitmap.getHeight();
         float sizeV = 42.75F;
         float xScale = (float)w / h;
         boolean flipY = true;
         projection.identity();
         projection.ortho(-42.75F * xScale, 42.75F * xScale, 42.75F, -42.75F, -100.0F, 100.0F);
         float f = org.joml.Math.sqrt(2048.0F);
         projection.scale(-f, f, f);
         modelView.identity();
         boolean bIsometric = true;
         modelView.rotate((float) (Math.PI / 6), 1.0F, 0.0F, 0.0F);
         modelView.rotate(this.animPlayerAngle + (float) (Math.PI / 4), 0.0F, 1.0F, 0.0F);
         float animalSize = this.animatedModel.getScale();
         modelView.scale(animalSize);
         modelView.translate(offsetX, offsetY, offsetZ);
      }

      void calcModelBounds(float[] bounds) {
         float minX = Float.MAX_VALUE;
         float minY = Float.MAX_VALUE;
         float maxX = -Float.MAX_VALUE;
         float maxY = -Float.MAX_VALUE;
         int dummy01 = this.animatedModel.getAnimationPlayer().getSkinningBoneIndex("Dummy01", -1);
         int bip01 = this.animatedModel.getAnimationPlayer().getSkinningBoneIndex("Bip01", -1);
         int bip01Pelvis = this.animatedModel.getAnimationPlayer().getSkinningBoneIndex("Bip01_Pelvis", -1);
         int translationData = this.animatedModel.getAnimationPlayer().getSkinningBoneIndex("Translation_Data", -1);
         org.lwjgl.util.vector.Matrix4f[] worldSpace = null;
         boolean[] bIgnoreBone = null;
         boolean bRagdoll = this.body.ragdollFall && !PZArrayUtil.isNullOrEmpty(this.body.diedBoneTransforms) && bip01Pelvis != -1;
         float relativeToX = 0.0F;
         float relativeToY = 0.0F;
         if (bRagdoll) {
            worldSpace = (org.lwjgl.util.vector.Matrix4f[])PZArrayUtil.newInstance(
               org.lwjgl.util.vector.Matrix4f.class, this.body.diedBoneTransforms.length, org.lwjgl.util.vector.Matrix4f::new
            );
            bIgnoreBone = new boolean[worldSpace.length];
            this.body.diedBoneTransforms[bip01].getMatrix(worldSpace[bip01]);
            this.body.diedBoneTransforms[bip01Pelvis].getMatrix(worldSpace[bip01Pelvis]);
            BoneTransform.mul(this.body.diedBoneTransforms[bip01Pelvis], worldSpace[bip01], worldSpace[bip01Pelvis]);
            org.lwjgl.util.vector.Matrix4f mtx = worldSpace[bip01Pelvis];
            this.sceneToUI(mtx.m03, mtx.m13, mtx.m23, this.projection, this.modelView, this.scenePos);
            relativeToX = this.scenePos.x;
            relativeToY = this.scenePos.y;

            for (int i = 0; i < this.body.diedBoneTransforms.length; i++) {
               bIgnoreBone[i] = true;
               if (i != dummy01 && i != bip01 && i != bip01Pelvis && i != translationData) {
                  SkinningBone bone = this.animatedModel.getAnimationPlayer().getSkinningData().getBoneAt(i);
                  SkinningBone parentBone = bone.parent;
                  if (parentBone.index != dummy01 && parentBone.index != bip01) {
                     bIgnoreBone[i] = false;
                     BoneTransform.mul(this.body.diedBoneTransforms[bone.index], worldSpace[parentBone.index], worldSpace[bone.index]);
                  }
               }
            }

            org.lwjgl.util.vector.Vector3f xAxis = new org.lwjgl.util.vector.Vector3f(1.0F, 0.0F, 0.0F);
            org.lwjgl.util.vector.Vector3f yAxis = new org.lwjgl.util.vector.Vector3f(0.0F, 1.0F, 0.0F);

            for (int i = 0; i < this.body.diedBoneTransforms.length; i++) {
               if (!bIgnoreBone[i]) {
                  worldSpace[i].rotate((float) (Math.PI / 2), xAxis);
                  worldSpace[i].rotate((float) Math.PI, yAxis);
               }
            }
         }

         for (int i = 0; i < this.animatedModel.getAnimationPlayer().getModelTransformsCount(); i++) {
            if ((!bRagdoll || !bIgnoreBone[i] && i != dummy01 && i != bip01 && i != bip01Pelvis) && i != translationData) {
               org.lwjgl.util.vector.Matrix4f mtx = this.animatedModel.getAnimationPlayer().getModelTransformAt(i);
               if (bRagdoll) {
                  mtx = worldSpace[i];
               }

               this.sceneToUI(mtx.m03, mtx.m13, mtx.m23, this.projection, this.modelView, this.scenePos);
               if (bRagdoll) {
                  this.scenePos.x -= relativeToX;
                  this.scenePos.y -= relativeToY;
               }

               minX = PZMath.min(minX, this.scenePos.x);
               maxX = PZMath.max(maxX, this.scenePos.x);
               minY = PZMath.min(minY, this.scenePos.y);
               maxY = PZMath.max(maxY, this.scenePos.y);
            }
         }

         if (bRagdoll) {
            minX += 512.0F;
            maxX += 512.0F;
            minY += 512.0F;
            maxY += 512.0F;
         }

         if (maxX - minX > 1024.0F) {
            float excess = maxX - minX - 1024.0F;
            minX += excess / 2.0F;
            maxX -= excess / 2.0F;
         }

         if (maxY - minY > 1024.0F) {
            float excess = maxY - minY - 1024.0F;
            minY += excess / 2.0F;
            maxY -= excess / 2.0F;
         }

         bounds[0] = minX;
         bounds[1] = minY;
         bounds[2] = maxX;
         bounds[3] = maxY;
      }

      void calcModelOffset(float[] offset) {
         int w = ModelManager.instance.bitmap.getWidth();
         int h = ModelManager.instance.bitmap.getHeight();
         float offsetX = 0.0F;
         float offsetY = this.body.standing ? -0.0F : 0.0F;
         this.calcMatrices(this.projection, this.modelView, offsetX, 0.0F, offsetY);
         this.calcModelBounds(this.bounds);
         float minX = this.bounds[0];
         float minY = this.bounds[1];
         float maxX = this.bounds[2];
         float maxY = this.bounds[3];
         this.uiToScene(this.projection, this.modelView, minX, minY, this.scenePos);
         float sceneMinX = this.scenePos.x;
         float sceneMinY = this.scenePos.z;
         float uiX = (w - (maxX - minX)) / 2.0F;
         float uiY = (h - (maxY - minY)) / 2.0F;
         this.uiToScene(this.projection, this.modelView, uiX, uiY, this.scenePos);
         offsetX += (this.scenePos.x - sceneMinX) * this.animatedModel.getScale();
         offsetY += (this.scenePos.z - sceneMinY) * this.animatedModel.getScale();
         offset[0] = offsetX;
         offset[1] = 0.0F;
         offset[2] = offsetY;
         this.entry.offsetX = (uiX - minX) / (8.0F / Core.tileScale);
         this.entry.offsetY = (uiY - minY) / (8.0F / Core.tileScale);
      }

      void calcEntrySize() {
         this.calcMatrices(this.projection, this.modelView, this.offset[0], this.offset[1], this.offset[2]);
         this.calcModelBounds(this.bounds);
         float minX = this.bounds[0];
         float minY = this.bounds[1];
         float maxX = this.bounds[2];
         float maxY = this.bounds[3];
         float pad = 128.0F;
         minX -= 128.0F;
         minY -= 128.0F;
         maxX += 128.0F;
         maxY += 128.0F;
         minX = org.joml.Math.floor(minX / 128.0F) * 128.0F;
         maxX = org.joml.Math.ceil(maxX / 128.0F) * 128.0F;
         minY = org.joml.Math.floor(minY / 128.0F) * 128.0F;
         maxY = org.joml.Math.ceil(maxY / 128.0F) * 128.0F;
         this.entryW = PZMath.min((int)(maxX - minX) / (8 / Core.tileScale), 1024);
         this.entryH = PZMath.min((int)(maxY - minY) / (8 / Core.tileScale), 1024);
      }

      Ray getCameraRay(float uiX, float uiY, Matrix4f projection, Matrix4f modelView, Ray cameraRay) {
         Matrix4f matrix4f = DeadBodyAtlas.RenderJob.L_getCameraRay.matrix4f;
         matrix4f.set(projection);
         matrix4f.mul(modelView);
         matrix4f.invert();
         this.viewport[0] = 0;
         this.viewport[1] = 0;
         this.viewport[2] = ModelManager.instance.bitmap.getWidth();
         this.viewport[3] = ModelManager.instance.bitmap.getHeight();
         Vector3f rayStart = matrix4f.unprojectInv(uiX, uiY, 0.0F, this.viewport, DeadBodyAtlas.RenderJob.L_getCameraRay.ray_start);
         Vector3f rayEnd = matrix4f.unprojectInv(uiX, uiY, 1.0F, this.viewport, DeadBodyAtlas.RenderJob.L_getCameraRay.ray_end);
         cameraRay.origin.set(rayStart);
         cameraRay.direction.set(rayEnd.sub(rayStart).normalize());
         return cameraRay;
      }

      Vector3f sceneToUI(float sceneX, float sceneY, float sceneZ, Matrix4f projection, Matrix4f modelView, Vector3f out) {
         Matrix4f matrix4f = this.matri4f;
         matrix4f.set(projection);
         matrix4f.mul(modelView);
         this.viewport[0] = 0;
         this.viewport[1] = 0;
         this.viewport[2] = ModelManager.instance.bitmap.getWidth();
         this.viewport[3] = ModelManager.instance.bitmap.getHeight();
         matrix4f.project(sceneX, sceneY, sceneZ, this.viewport, out);
         return out;
      }

      Vector3f uiToScene(Matrix4f projection, Matrix4f modelView, float uiX, float uiY, Vector3f out) {
         Plane plane = DeadBodyAtlas.RenderJob.L_uiToScene.plane;
         plane.point.set(0.0F);
         plane.normal.set(0.0F, 1.0F, 0.0F);
         Ray cameraRay = this.getCameraRay(uiX, uiY, projection, modelView, DeadBodyAtlas.RenderJob.L_uiToScene.ray);
         if (UI3DScene.intersect_ray_plane(plane, cameraRay, out) != 1) {
            out.set(0.0F);
         }

         return out;
      }

      public void Reset() {
         this.body.Reset();
         this.entry = null;
         if (this.animatedModel != null) {
            this.animatedModel.releaseAnimationPlayer();
            this.animatedModel = null;
         }
      }

      static final class L_getCameraRay {
         static final Matrix4f matrix4f = new Matrix4f();
         static final Vector3f ray_start = new Vector3f();
         static final Vector3f ray_end = new Vector3f();
      }

      static final class L_uiToScene {
         static final Plane plane = new Plane();
         static final Ray ray = new Ray();
      }
   }
}
