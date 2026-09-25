package zombie.core.skinnedmodel.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.joml.Vector3f;
import org.lwjgl.util.vector.Matrix;
import org.lwjgl.util.vector.Matrix4f;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.util.vector.Vector4f;
import zombie.GameProfiler;
import zombie.GameTime;
import zombie.GameProfiler.ProfileArea;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.math.VectorUtil;
import zombie.core.physics.RagdollController;
import zombie.core.skinnedmodel.HelperFunctions;
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.animation.debug.AnimationPlayerRecorder;
import zombie.core.skinnedmodel.animation.sharedskele.SharedSkeleAnimationRepository;
import zombie.core.skinnedmodel.animation.sharedskele.SharedSkeleAnimationTrack;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkeletonBone;
import zombie.core.skinnedmodel.model.SkinningBone;
import zombie.core.skinnedmodel.model.SkinningBoneHierarchy;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.debug.DebugOptions;
import zombie.debug.DebugType;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.util.IPooledObject;
import zombie.util.Lambda;
import zombie.util.Pool;
import zombie.util.PooledObject;
import zombie.util.StringUtils;
import zombie.util.list.PZArrayUtil;
import zombie.util.list.PZArrayUtil.Comparators;

public final class AnimationPlayer extends PooledObject {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.core.skinnedmodel.animation.AnimationPlayer");
   }

   private Model model;
   private final Matrix4f propTransforms = new Matrix4f();
   private boolean boneTransformsNeedFirstFrame = true;
   private float boneTransformsTimeDelta = -1.0F;
   public AnimatorsBoneTransform[] boneTransforms;
   private Matrix4f[] modelTransforms;
   private AnimationPlayer.SkinTransformData skinTransformData;
   private AnimationPlayer.SkinTransformData skinTransformDataPool;
   private SkinningData skinningData;
   private AnimationClip ragdollAnimationClip;
   private float ragdollAnimationWeight;
   private SharedSkeleAnimationRepository sharedSkeleAnimationRepo;
   private SharedSkeleAnimationTrack currentSharedTrack;
   private AnimationClip currentSharedTrackClip;
   private float angle;
   private float targetAngle;
   private boolean characterAllowsTwist = true;
   private float twistAngle;
   private float shoulderTwistAngle;
   private float shoulderTwistWeight = 1.0F;
   private float targetTwistAngle;
   private float maxTwistAngle = PZMath.degToRad(70.0F);
   private float excessTwist;
   private static final float angleStepBase = 0.15F;
   public float angleStepDelta = 1.0F;
   public float angleTwistDelta = 1.0F;
   public boolean doBlending = true;
   public boolean updateBones = true;
   private final Vector2 targetDir = new Vector2();
   private final ArrayList<AnimationBoneBindingPair> reparentedBoneBindings = new ArrayList<>();
   private final List<AnimationBoneBinding> twistBones = new ArrayList<>();
   private AnimationBoneBinding counterRotationBone;
   public final ArrayList<Integer> dismembered = new ArrayList<>();
   private final float minimumValidAnimWeight = 0.001F;
   private final LiveAnimationTrackEntries liveAnimationTrackEntries = new LiveAnimationTrackEntries();
   public AnimationPlayer parentPlayer;
   private final Vector2 deferredMovement = new Vector2();
   private final Object deferredMovementLock = new Object();
   private final Vector2 deferredMovementAccum = new Vector2();
   private final Object deferredMovementAccumLock = new Object();
   private final Vector3 deferredMovementFromRagdoll = new Vector3();
   private float deferredRotationWeight;
   private float deferredAngleDelta;
   private final Vector3f targetGrapplePos = new Vector3f();
   private final Vector2 targetGrappleRotation = new Vector2(1.0F, 0.0F);
   private final Vector3f grappleOffset = new Vector3f();
   private AnimationPlayerRecorder recorder;
   private static final ThreadLocal<AnimationTrack[]> tempTracks = ThreadLocal.withInitial(() -> new AnimationTrack[0]);
   private static final ThreadLocal<Vector2> tempo = ThreadLocal.withInitial(Vector2::new); // pzopt: per thread (pzopt.AnimBatch)
   private RagdollController ragdollController;
   private final org.lwjgl.util.vector.Vector3f ragdollWorldPosition = new org.lwjgl.util.vector.Vector3f();
   private final Quaternion ragdollWorldRotation = new Quaternion();
   private IsoGameCharacter character;
   private static final Pool<AnimationPlayer> s_pool = new Pool(AnimationPlayer::new);
   private final AnimationMultiTrack multiTrack = new AnimationMultiTrack();

   private AnimationPlayer() {
   }

   public static AnimationPlayer alloc(Model model) {
      AnimationPlayer animPlayer = (AnimationPlayer)s_pool.alloc();
      animPlayer.setModel(model);
      return animPlayer;
   }

   public AnimationClip getAnimationClip() {
      return this.currentSharedTrackClip;
   }

   public static float lerpBlendWeight(float from, float to, float fadeTimeTo1) {
      if (PZMath.equal(from, to, 1.0E-4F)) {
         return to;
      }

      float fadeSpeed = 1.0F / fadeTimeTo1;
      float dt = GameTime.getInstance().getTimeDelta();
      float fadeDiff = to - from;
      float fadeDir = PZMath.sign(fadeDiff);
      float newPos = from + fadeDir * fadeSpeed * dt;
      float newDiff = to - newPos;
      float newDir = PZMath.sign(newDiff);
      if (newDir != fadeDir) {
         newPos = to;
      }

      return newPos;
   }

   public void setModel(Model model) {
      Objects.requireNonNull(model);
      if (model != this.model) {
         this.model = model;
         this.initSkinningData();
      }
   }

   public Model getModel() {
      return this.model;
   }

   public int getNumBones() {
      return !this.isReady() ? 0 : this.boneTransforms.length;
   }

   public AnimatorsBoneTransform getBoneTransformAt(int i) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (i >= 0 && this.getNumBones() > i) {
         return this.boneTransforms[i];
      } else {
         throw new IndexOutOfBoundsException("Bone index " + i + " out of range. NumBones:" + this.getNumBones());
      }
   }

   public <T extends BoneTransform> T getBoneTransformAt(int i, T result) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (i >= 0 && this.getNumBones() > i) {
         result.set(this.boneTransforms[i]);
         return result;
      } else {
         throw new IndexOutOfBoundsException("Bone index " + i + " out of range. NumBones:" + this.getNumBones());
      }
   }

   private void initSkinningData() {
      if (this.model != null && this.model.isReady()) {
         SkinningData skinningData = (SkinningData)this.model.tag;
         if (skinningData != null) {
            if (this.skinningData != skinningData) {
               if (this.skinningData != null) {
                  this.skinningData = null;
                  this.multiTrack.reset();
               }

               this.skinningData = skinningData;
               Lambda.forEachFrom(PZArrayUtil::forEach, this.reparentedBoneBindings, this.skinningData, AnimationBoneBindingPair::setSkinningData);
               Lambda.forEachFrom(PZArrayUtil::forEach, this.twistBones, this.skinningData, AnimationBoneBinding::setSkinningData);
               if (this.counterRotationBone != null) {
                  this.counterRotationBone.setSkinningData(this.skinningData);
               }

               int boneCount = skinningData.numBones();
               this.modelTransforms = (Matrix4f[])PZArrayUtil.newInstance(Matrix4f.class, this.modelTransforms, boneCount, Matrix4f::new);
               this.boneTransforms = (AnimatorsBoneTransform[])PZArrayUtil.newInstance(
                  AnimatorsBoneTransform.class, this.boneTransforms, boneCount, AnimatorsBoneTransform::alloc
               );

               for (int i = 0; i < boneCount; i++) {
                  if (this.boneTransforms[i] == null) {
                     this.boneTransforms[i] = AnimatorsBoneTransform.alloc();
                  }

                  this.boneTransforms[i].setIdentity();
               }

               this.boneTransformsNeedFirstFrame = true;
            }
         }
      }
   }

   public boolean isReady() {
      this.initSkinningData();
      return this.hasSkinningData();
   }

   public boolean hasSkinningData() {
      return this.skinningData != null;
   }

   public void addBoneReparent(String boneName, String newParentBone) {
      if (!PZArrayUtil.contains(this.reparentedBoneBindings, Lambda.predicate(boneName, newParentBone, AnimationBoneBindingPair::matches))) {
         AnimationBoneBindingPair newBindingPair = new AnimationBoneBindingPair(boneName, newParentBone);
         newBindingPair.setSkinningData(this.skinningData);
         this.reparentedBoneBindings.add(newBindingPair);
      }
   }

   public void setTwistBones(String... bones) {
      List<String> boneNames = AnimationPlayer.L_setTwistBones.boneNames;
      PZArrayUtil.listConvert(this.twistBones, boneNames, bone -> bone.boneName);
      if (!PZArrayUtil.sequenceEqual(bones, boneNames, Comparators::equalsIgnoreCase)) {
         this.twistBones.clear();
         Lambda.forEachFrom(PZArrayUtil::forEach, bones, this, (boneName, lThis) -> {
            AnimationBoneBinding binding = new AnimationBoneBinding((String)boneName);
            binding.setSkinningData(lThis.skinningData);
            lThis.twistBones.add(binding);
         });
      }
   }

   public int getNumTwistBones() {
      return this.twistBones.size();
   }

   public AnimatorsBoneTransform getTwistBoneAt(int twistBoneIdx) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      AnimationBoneBinding twistBoneBinding = this.twistBones.get(twistBoneIdx);
      SkinningBone twistBone = twistBoneBinding.getBone();
      int boneIdx = twistBone.index;
      return this.boneTransforms[boneIdx];
   }

   public String getTwistBoneNameAt(int twistBoneIdx) {
      return this.twistBones.get(twistBoneIdx).boneName;
   }

   public void setCounterRotationBone(String boneName) {
      if (this.counterRotationBone != null && StringUtils.equals(this.counterRotationBone.boneName, boneName)) {
      }

      this.counterRotationBone = new AnimationBoneBinding(boneName);
      this.counterRotationBone.setSkinningData(this.skinningData);
   }

   public AnimationBoneBinding getCounterRotationBone() {
      return this.counterRotationBone;
   }

   public void reset() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.multiTrack.reset();
      this.releaseRagdollController();
   }

   public void onReleased() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.model = null;
      this.skinningData = null;
      this.propTransforms.setIdentity();
      this.boneTransformsNeedFirstFrame = true;
      this.boneTransformsTimeDelta = -1.0F;
      this.boneTransforms = (AnimatorsBoneTransform[])IPooledObject.tryReleaseAndBlank(this.boneTransforms);
      PZArrayUtil.forEach(this.modelTransforms, Matrix::setIdentity);
      this.resetSkinTransforms();
      this.setAngle(0.0F);
      this.setTargetAngle(0.0F);
      this.twistAngle = 0.0F;
      this.shoulderTwistAngle = 0.0F;
      this.targetTwistAngle = 0.0F;
      this.maxTwistAngle = PZMath.degToRad(70.0F);
      this.excessTwist = 0.0F;
      this.angleStepDelta = 1.0F;
      this.angleTwistDelta = 1.0F;
      this.doBlending = true;
      this.updateBones = true;
      this.targetDir.set(0.0F, 0.0F);
      this.reparentedBoneBindings.clear();
      this.twistBones.clear();
      this.counterRotationBone = null;
      this.dismembered.clear();
      this.liveAnimationTrackEntries.clear();
      this.parentPlayer = null;
      this.deferredMovement.set(0.0F, 0.0F);
      this.deferredMovementAccum.set(0.0F, 0.0F);
      this.deferredMovementFromRagdoll.set(0.0F, 0.0F, 0.0F);
      this.deferredRotationWeight = 0.0F;
      this.deferredAngleDelta = 0.0F;
      this.recorder = null;
      this.multiTrack.reset();
      this.releaseRagdollController();
      this.ragdollAnimationClip = null;
      this.ragdollAnimationWeight = 0.0F;
      this.character = null;
   }

   public SkinningData getSkinningData() {
      return this.skinningData;
   }

   public HashMap<String, Integer> getSkinningBoneIndices() {
      return this.skinningData != null ? this.skinningData.boneIndices : null;
   }

   // pzopt: boneIndexCache. calculateShadowParams asks for the head and both feet of every drawn character every frame,
   // two HashMap<String, Integer> probes each (1.6 % of the game thread on the Louisville horde); the answer per skinning
   // data never changes, so the last few (name, index) pairs are kept beside it and matched by string identity first
   // (the callers pass literals). A name that is not a bone is not cached and takes the stock path.
   private SkinningData pzoptBoneIndexData;
   private final String[] pzoptBoneIndexNames = new String[8];
   private final int[] pzoptBoneIndexValues = new int[8];
   private int pzoptBoneIndexCount;

   public int getSkinningBoneIndex(String boneName, int defaultVal) {
      if (pzopt.Overrides.enabled() && pzopt.Config.BONE_INDEX_CACHE && boneName != null) {
         SkinningData skinningData = this.getSkinningData();
         if (skinningData != this.pzoptBoneIndexData) {
            this.pzoptBoneIndexData = skinningData;
            this.pzoptBoneIndexCount = 0;
         }

         String[] names = this.pzoptBoneIndexNames;
         int count = this.pzoptBoneIndexCount;

         for (int k = 0; k < count; k++) {
            if (names[k] == boneName) {
               return this.pzoptBoneIndexValues[k];
            }
         }

         for (int k = 0; k < count; k++) {
            if (names[k].equals(boneName)) {
               return this.pzoptBoneIndexValues[k];
            }
         }

         HashMap<String, Integer> boneIndices = this.getSkinningBoneIndices();
         Integer found = boneIndices == null ? null : boneIndices.get(boneName);
         if (found == null) {
            return defaultVal;
         }

         if (count < names.length) {
            names[count] = boneName;
            this.pzoptBoneIndexValues[count] = found;
            this.pzoptBoneIndexCount = count + 1;
         }

         return found;
      }

      HashMap<String, Integer> boneIndices = this.getSkinningBoneIndices();
      return boneIndices != null && boneIndices.containsKey(boneName) ? boneIndices.get(boneName) : defaultVal;
   }

   private synchronized AnimationPlayer.SkinTransformData getSkinTransformData(SkinningData skinnedTo) {
      for (AnimationPlayer.SkinTransformData current = this.skinTransformData; current != null; current = current.next) {
         if (skinnedTo == current.skinnedTo) {
            return current;
         }
      }

      AnimationPlayer.SkinTransformData var3 = this.getOrCreateSkinTransformData(skinnedTo);
      var3.next = this.skinTransformData;
      this.skinTransformData = var3;
      return var3;
   }

   private synchronized AnimationPlayer.SkinTransformData getOrCreateSkinTransformData(SkinningData skinnedTo) {
      AnimationPlayer.SkinTransformData data = this.skinTransformDataPool;
      AnimationPlayer.SkinTransformData prev = null;

      while (data != null) {
         if (data.transforms != null && data.transforms.length == skinnedTo.numBones()) {
            if (prev == null) {
               this.skinTransformDataPool = data.next;
            } else {
               prev.next = data.next;
            }

            data.setSkinnedTo(skinnedTo);
            data.dirty = true;
            return data;
         }

         prev = data;
         data = data.next;
      }

      return AnimationPlayer.SkinTransformData.alloc(skinnedTo);
   }

   private synchronized void resetSkinTransforms() {
      ProfileArea var1 = GameProfiler.getInstance().profile("resetSkinTransforms");

      try {
         this.resetSkinTransformsInternal();
      } catch (Throwable var5) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void resetSkinTransformsInternal() {
      if (this.skinTransformDataPool != null) {
         AnimationPlayer.SkinTransformData last = this.skinTransformDataPool;

         while (last.next != null) {
            last = last.next;
         }

         last.next = this.skinTransformData;
      } else {
         this.skinTransformDataPool = this.skinTransformData;
      }

      this.skinTransformData = null;
   }

   public Matrix4f GetPropBoneMatrix(int bone) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.propTransforms.load(this.modelTransforms[bone]);
      return this.propTransforms;
   }

   public AnimationTrack startClip(AnimationClip clip, boolean loop, float ragdollMaxTime) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (clip == null) {
         throw new NullPointerException("Supplied clip is null.");
      }

      AnimationTrack track = AnimationTrack.alloc();
      track.startClip(clip, loop, ragdollMaxTime);
      track.setName(clip.name);
      track.isPlaying = true;
      this.multiTrack.addTrack(track);
      DebugType.AnimationDetailed.debugln("startClip: %s", new Object[]{clip.name});
      return track;
   }

   public static void releaseTracks(List<AnimationTrack> tracks) {
      AnimationTrack[] temp = tempTracks.get();
      AnimationTrack[] tracksToRelease = tracks.toArray(temp);
      PZArrayUtil.forEach(tracksToRelease, PooledObject::release);
   }

   public AnimationTrack play(String animName, boolean looped) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.play(animName, looped, false, -1.0F);
   }

   public AnimationTrack play(String animName, boolean looped, boolean isRagdoll, float ragdollMaxTime) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (!this.isReady()) {
         DebugType.Animation.warn("AnimationPlayer is not ready. Cannot play animation: %s%s", new Object[]{animName, isRagdoll ? "(Ragdoll)" : ""});
         return null;
      }

      if (this.skinningData == null) {
         DebugType.Animation.warn("Skinning Data not found. AnimName: %s%s", new Object[]{animName, isRagdoll ? "(Ragdoll)" : ""});
         return null;
      }

      AnimationClip chosenClip;
      if (isRagdoll) {
         chosenClip = this.getOrCreateRagdollAnimationClip();
      } else {
         chosenClip = (AnimationClip)this.skinningData.animationClips.get(animName);
      }

      if (chosenClip == null) {
         DebugType.Animation.warn("Anim Clip %snot found: %s", new Object[]{isRagdoll ? "(Ragdoll)" : "", animName});
         return null;
      } else {
         return this.startClip(chosenClip, looped, ragdollMaxTime);
      }
   }

   public AnimationTrack play(StartAnimTrackParameters params, AnimLayer animLayer) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      AnimationTrack track = this.play(params.animName, params.isLooped, params.isRagdoll, params.ragdollMaxTime);
      if (track == null) {
         return null;
      }

      track.isPrimary = params.isPrimary;
      SkinningData skinningData = this.getSkinningData();
      if (animLayer.isSubLayer()) {
         track.setBoneWeights(params.subLayerBoneWeights);
         track.initBoneWeights(skinningData);
      } else {
         track.setBoneWeights(null);
      }

      SkinningBone deferredBone = skinningData.getBone(params.deferredBoneName);
      if (deferredBone == null) {
         DebugType.Animation.error("Deferred bone not found: \"%s\"", new Object[]{params.deferredBoneName});
      }

      track.setSpeedDelta(params.speedScale);
      track.syncTrackingEnabled = params.syncTrackingEnabled;
      track.trackTimeToVariable = params.trackTimeToVariable;
      track.setDeferredBone(deferredBone, params.deferredBoneAxis);
      track.setUseDeferredRotation(params.useDeferredRotation);
      track.setDeferredRotationScale(params.deferredRotationScale);
      track.setBlendWeight(params.initialWeight);
      track.reverse = params.isReversed;
      track.priority = params.priority;
      track.ragdollStartTime = params.ragdollStartTime;
      track.setMatchingGrappledAnimNode(params.matchingGrappledAnimNode);
      track.setAnimLayer(animLayer);
      return track;
   }

   public AnimationClip getOrCreateRagdollAnimationClip() {
      if (!this.isReady()) {
         return null;
      }

      SkinningBoneHierarchy skeletonBoneHierarchy = this.getSkeletonBoneHierarchy();
      int numberOfBones = skeletonBoneHierarchy.numBones();
      if (this.ragdollAnimationClip == null) {
         ArrayList<Keyframe> keyframeList = new ArrayList<>();

         for (int i = 0; i < numberOfBones; i++) {
            SkinningBone bone = skeletonBoneHierarchy.getBoneAt(i);
            int boneIndex = bone.index;
            Keyframe keyframe = new Keyframe();
            keyframe.bone = boneIndex;
            keyframe.time = 0.0F;
            keyframe.position = new org.lwjgl.util.vector.Vector3f();
            keyframe.rotation = new Quaternion();
            keyframe.scale = new org.lwjgl.util.vector.Vector3f();
            keyframeList.add(keyframe);
            keyframe = new Keyframe();
            keyframe.bone = boneIndex;
            keyframe.time = 1.0F;
            keyframe.position = new org.lwjgl.util.vector.Vector3f();
            keyframe.rotation = new Quaternion();
            keyframe.scale = new org.lwjgl.util.vector.Vector3f();
            keyframeList.add(keyframe);
         }

         this.ragdollAnimationClip = new AnimationClip(1.0F, keyframeList, "RagdollAnimationClip", true, true);
      }

      return this.ragdollAnimationClip;
   }

   public SkinningBoneHierarchy getSkeletonBoneHierarchy() {
      return !this.isReady() ? null : this.getSkinningData().getSkeletonBoneHierarchy();
   }

   public void Update() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.Update(GameTime.instance.getTimeDelta());
   }

   public void Update(float deltaT) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      ProfileArea var2 = GameProfiler.getInstance().profile("AnimationPlayer.Update");

      try {
         this.updateInternal(deltaT);
      } catch (Throwable var6) {
         if (var2 != null) {
            try {
               var2.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }
         }

         throw var6;
      }

      if (var2 != null) {
         var2.close();
      }
   }

   private void updateInternal(float deltaT) {
      this.pzoptShadowValid = false; // pzopt: shadowPrep, the bones are about to move
      this.pzoptCapsulesValid = false; // pzopt: sunShadows, likewise the capsule end points
      if (this.isReady()) {
         this.updateRagdoll(deltaT);
         this.multiTrack.Update(deltaT);
         if (!this.updateBones) {
            this.updateAnimation_NonVisualOnly(deltaT);
         } else if (this.multiTrack.getTrackCount() > 0) {
            SharedSkeleAnimationTrack sharedSkeleTrack = this.determineCurrentSharedSkeleTrack();
            if (sharedSkeleTrack != null) {
               float trackTime = this.multiTrack.getTrackAt(0).getCurrentTrackTime();
               this.updateAnimation_SharedSkeleTrack(sharedSkeleTrack, deltaT, trackTime);
            } else if (!pzopt.AnimBatch.submit(this, deltaT)) { // pzopt: a zombie's bone math runs later on the pool (Config.animBonesParallel)
               this.updateAnimation_StandardAnimation(deltaT);
               this.postUpdateRagdoll(deltaT);
            }
         }
      }
   }

   /** pzopt: the part of updateInternal that pzopt.AnimBatch deferred, run on a worker thread (or on the game thread at the join). */
   public void pzoptRunDeferred(float deltaT) {
      this.updateAnimation_StandardAnimation(deltaT);
      this.postUpdateRagdoll(deltaT);
      if (pzopt.Config.SKIN_TRANSFORMS_PRECOMPUTE) {
         this.pzoptPrecomputeSkinTransforms();
      }

      if (pzopt.Config.SHADOW_PREP && this.hasSkinningData()) {
         this.pzoptPrecomputeShadow();
      }
   }

   // pzopt: shadowPrep. The shadow ellipse of a drawn character (IsoGameCharacter.calculateShadowParams: head and feet
   // projected to the ground, extents along the facing) reads only this player's model transforms and angle, which the
   // deferred update just wrote, so the worker computes it here (pzopt.ShadowPrep, stock's arithmetic with thread-local
   // scratch) and the IsoZombie override of calculateShadowParams serves the pair until the next update clears it.
   private volatile boolean pzoptShadowValid;
   private long pzoptShadowPacked;

   private void pzoptPrecomputeShadow() {
      int head = this.getSkinningBoneIndex("Bip01_Head", -1);
      int leftFoot = this.getSkinningBoneIndex("Bip01_L_Foot", -1);
      int rightFoot = this.getSkinningBoneIndex("Bip01_R_Foot", -1);
      int bones = this.modelTransforms == null ? 0 : this.modelTransforms.length;
      if (head < 0 || leftFoot < 0 || rightFoot < 0 || head >= bones || leftFoot >= bones || rightFoot >= bones) {
         return; // stock would read out of range too; leave it to the game thread's own call
      }

      this.pzoptShadowPacked = pzopt.ShadowPrep.compute(this, head, leftFoot, rightFoot);
      this.pzoptShadowValid = true;
      if (pzopt.CapsuleShadow.wanted()) { // pzopt: sunShadows, the body's capsule end points for the sun shadow pass (pzopt.CapsuleShadow)
         this.pzoptCapsulesValid = pzopt.CapsuleShadow.points(this, this.pzoptCapsules); // pzopt
      } // pzopt
   }

   // pzopt: sunShadows. The capsule end points (pzopt.CapsuleShadow.BONES, relative to the character, metric) computed by the
   // deferred update with the shadow ellipse; the game thread reads them in the render after the bone batch joined.
   private final float[] pzoptCapsules = new float[pzopt.CapsuleShadow.POINTS * 3]; // pzopt
   private volatile boolean pzoptCapsulesValid; // pzopt
   public int[] pzoptCapsuleBones; // pzopt: skinning bone indices of CapsuleShadow.BONES, for pzoptCapsuleBonesOf
   public Object pzoptCapsuleBonesOf; // pzopt: the skinning data they were looked up in

   /** pzopt: sunShadows, the capsule end points of the last deferred update, or null when the game thread must compute them. */
   public float[] pzoptCapsules() { // pzopt
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.pzoptCapsulesValid ? this.pzoptCapsules : null; // pzopt
   } // pzopt

   /** pzopt: shadowPrep, the pair computed by the last deferred update, or 0 when the game thread must compute it. */
   public long pzoptShadowParams() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.pzoptShadowValid ? this.pzoptShadowPacked : 0L;
   }

   /**
    * pzopt: skinTransformsPrecompute. The standard-animation update ends by handing this frame's skin-transform sets
    * (one per model skinned to this player: body, hair, every clothing item) back to the pool, and the render phase asks
    * for each again while it builds the draw data, which multiplies every bone by the model's bone offsets on the game
    * thread (3 % of it on the Louisville horde). The sets in the pool are exactly the models drawn last frame, so the
    * worker that just updated the bones asks for the same sets now: each comes back out of the pool computed, and the
    * render phase finds them clean. A model that is new this frame computes as before, an entry that is not drawn
    * is only a few wasted multiplies on a worker. Reads only this player's arrays and the read-only skinning data.
    */
   private void pzoptPrecomputeSkinTransforms() {
      SkinningData[] wanted = pzoptSkinnedTo.get();
      int n = 0;
      synchronized (this) {
         for (AnimationPlayer.SkinTransformData data = this.skinTransformDataPool; data != null && n < wanted.length; data = data.next) {
            if (data.skinnedTo != null) {
               wanted[n++] = data.skinnedTo;
            }
         }
      }

      for (int i = 0; i < n; i++) {
         Matrix4f[] transforms = this.getSkinTransforms(wanted[i]);
         if (pzopt.Config.SKIN_PALETTE_PRECOMPUTE) {
            this.pzoptStorePalette(wanted[i], transforms);
         }

         wanted[i] = null;
      }
   }

   // pzopt: skinPalettePrecompute. AnimatedModelInstanceRenderData.initMatrixPalette stores every skin matrix of every
   // drawn sub-model into the draw data's FloatBuffer on the game thread, sixteen puts per matrix (3 % of it on the
   // Louisville horde). The worker that computed the set stores it once into a buffer kept on the set, and the render
   // phase copies that buffer in one bulk put (AnimatedModel override, pzoptSkinPalette). Same column order (Matrix4f.store).
   private void pzoptStorePalette(SkinningData skinnedTo, Matrix4f[] transforms) {
      AnimationPlayer.SkinTransformData data = this.getSkinTransformData(skinnedTo);
      if (data.transforms != transforms) {
         return;
      }

      int floats = transforms.length * 16;
      java.nio.FloatBuffer palette = data.pzoptPalette;
      if (palette == null || palette.capacity() < floats) {
         palette = org.lwjgl.BufferUtils.createFloatBuffer(floats);
         data.pzoptPalette = palette;
      }

      palette.clear();

      for (int i = 0; i < transforms.length; i++) {
         transforms[i].store(palette);
      }

      palette.flip();
      data.pzoptPaletteValid = true;
   }

   /**
    * pzopt: skinPalettePrecompute. The palette buffer of the skin-transform set for {@code skinnedTo} when the worker
    * filled it for the current transforms (flipped: position 0, limit = bones x 16), else null (the caller stores the
    * matrices itself). Game thread; the set is the one getSkinTransforms would return.
    */
   public java.nio.FloatBuffer pzoptSkinPalette(SkinningData skinnedTo) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (skinnedTo == null) {
         return null;
      }

      AnimationPlayer.SkinTransformData data;
      synchronized (this) {
         for (data = this.skinTransformData; data != null; data = data.next) {
            if (data.skinnedTo == skinnedTo) {
               break;
            }
         }
      }

      if (data == null || data.dirty || !data.pzoptPaletteValid) {
         return null;
      }

      data.pzoptPalette.rewind();
      return data.pzoptPalette;
   }

   private static final ThreadLocal<SkinningData[]> pzoptSkinnedTo = ThreadLocal.withInitial(() -> new SkinningData[16]);

   /** pzopt: animBatchAsync, set by the game thread when this player's bone math is queued, cleared when its batch is joined. */
   public boolean pzoptInFlight;

   /** pzopt: pzopt.AnimBatch eligibility: no parent player to copy from, no ragdoll, no recorder. */
   public boolean pzoptBatchable() {
      // pzopt: nor a ragdoll track: its controller calls the Bullet library, game thread only (showcase crash, 2026-09-24)
      return this.parentPlayer == null && !this.isRagdolling() && !this.isRecording() && this.ragdollController == null && !this.multiTrack.containsAnyRagdollTracks();
   }

   private SharedSkeleAnimationTrack determineCurrentSharedSkeleTrack() {
      if (this.isRagdolling()) {
         return null;
      }

      if (this.sharedSkeleAnimationRepo == null) {
         return null;
      }

      if (this.doBlending) {
         return null;
      }

      if (!DebugOptions.instance.animation.sharedSkeles.enabled.getValue()) {
         return null;
      }

      if (this.multiTrack.getTrackCount() != 1) {
         return null;
      }

      if (!PZMath.equal(this.twistAngle, 0.0F, 114.59155F)) {
         return null;
      }

      if (this.parentPlayer != null) {
         return null;
      }

      AnimationTrack animTrack = this.multiTrack.getTrackAt(0);
      if (animTrack.isRagdoll()) {
         return null;
      }

      float trackWeight = animTrack.getBlendFieldWeight();
      if (!PZMath.equal(trackWeight, 0.0F, 0.1F)) {
         return null;
      }

      AnimationClip clip = animTrack.getClip();
      if (clip == this.currentSharedTrackClip) {
         return this.currentSharedTrack;
      }

      SharedSkeleAnimationTrack sharedTrack = this.sharedSkeleAnimationRepo.getTrack(clip);
      if (sharedTrack == null) {
         DebugType.Animation.debugln("Caching SharedSkeleAnimationTrack: %s", new Object[]{animTrack.getName()});
         sharedTrack = new SharedSkeleAnimationTrack();
         ModelTransformSampler sampler = ModelTransformSampler.alloc(this, animTrack);

         try {
            sharedTrack.set(sampler, 5.0F);
         } finally {
            sampler.release();
         }

         this.sharedSkeleAnimationRepo.setTrack(clip, sharedTrack);
      }

      this.currentSharedTrackClip = clip;
      this.currentSharedTrack = sharedTrack;
      return sharedTrack;
   }

   private void updateAnimation_NonVisualOnly(float deltaT) {
      this.updateMultiTrackBoneTransforms_DeferredMovementOnly();
      this.DoAngles(deltaT);
      this.calculateDeferredMovement();
   }

   public void setSharedAnimRepo(SharedSkeleAnimationRepository repo) {
      this.sharedSkeleAnimationRepo = repo;
   }

   private void updateAnimation_SharedSkeleTrack(SharedSkeleAnimationTrack sharedSkeleTrack, float deltaT, float trackTime) {
      this.updateMultiTrackBoneTransforms_DeferredMovementOnly();
      this.DoAngles(deltaT);
      this.calculateDeferredMovement();
      sharedSkeleTrack.moveToTime(trackTime);

      for (int boneIdx = 0; boneIdx < this.modelTransforms.length; boneIdx++) {
         sharedSkeleTrack.getBoneMatrix(boneIdx, this.modelTransforms[boneIdx]);
      }

      this.UpdateSkinTransforms();
   }

   private void updateAnimation_StandardAnimation(float deltaT) {
      if (this.parentPlayer == null) {
         this.updateMultiTrackBoneTransforms(deltaT);
      } else {
         this.copyBoneTransformsFromParentPlayer();
      }

      this.DoAngles(deltaT);
      this.calculateDeferredMovement();
      this.updateTwistBone();
      this.applyBoneReParenting();
      this.updateModelTransforms();
      this.UpdateSkinTransforms();
   }

   private void updateRagdoll(float deltaT) {
      if (!this.updateBones) {
         this.releaseRagdollController();
      } else if (!this.canRagdoll()) {
         this.releaseRagdollController();
      } else if (!this.multiTrack.containsAnyRagdollTracks()) {
         this.releaseRagdollController();
      } else {
         AnimationTrack ragdollTrack = this.multiTrack.getActiveRagdollTrack();
         if (ragdollTrack == null) {
            this.releaseRagdollController();
         } else if (this.ragdollController == null && pzopt.AnimParallel.offGameThread()) { // pzopt: a ragdoll that starts on a frame worker
            // pzopt: waits one frame for the game thread (pzoptBatchable refuses ragdoll tracks): the controller calls the Bullet
            // library, which is not thread-safe (btDiscreteDynamicsWorld::calculateSimulationIslands crash, showcase-t3 / -t4-nobones)
         } else {
            ProfileArea var3 = GameProfiler.getInstance().profile("AnimationPlayer.updateRagdoll");

            try {
               this.updateRagdollInternal(deltaT);
            } catch (Throwable var7) {
               if (var3 != null) {
                  try {
                     var3.close();
                  } catch (Throwable var6) {
                     var7.addSuppressed(var6);
                  }
               }

               throw var7;
            }

            if (var3 != null) {
               var3.close();
            }
         }
      }
   }

   private void postUpdateRagdoll(float deltaT) {
      if (!this.updateBones) {
         this.releaseRagdollController();
      } else if (this.getIsoGameCharacter() == null) {
         this.releaseRagdollController();
      } else if (!this.multiTrack.containsAnyRagdollTracks()) {
         this.releaseRagdollController();
      } else {
         AnimationTrack ragdollTrack = this.multiTrack.getActiveRagdollTrack();
         if (ragdollTrack == null) {
            this.releaseRagdollController();
         } else {
            this.postUpdateRagdollInternal(deltaT);
         }
      }
   }

   private void updateRagdollInternal(float deltaT) {
      RagdollController ragdollController = this.getOrCreateRagdollController();
      if (ragdollController != null) {
         if (!this.isBoneTransformsNeedFirstFrame()) {
            if (this.multiTrack.anyRagdollFirstFrame()) {
               DebugType.Animation.debugln("Initiating radgoll first-frames to boneTransforms...");
               this.multiTrack.initRagdollTransforms(this.boneTransforms, false);
            }

            this.updateTotalRagdollWeight();
            this.deferredMovementFromRagdoll.set(0.0F, 0.0F, 0.0F);
            if (ragdollController.isFirstFrame()) {
               DebugType.Animation.debugln("Initiating radgoll first-frames to boneTransforms...");
               this.multiTrack.initRagdollTransforms(this.boneTransforms, true);
            }

            if (this.character != null && this.isSimulationDirectionCalculated() && this.isFullyRagdolling()) {
               this.calculateDeferredMovementFromRagdolls(this.deferredMovementFromRagdoll);
               this.character.doDeferredMovementFromRagdoll(this.deferredMovementFromRagdoll);
            }

            pzopt.AnimParallel.noteRagdoll("stepped"); // pzopt: evidence rig, ragdoll physics off the game thread
            ragdollController.update(deltaT, this.ragdollWorldPosition, this.ragdollWorldRotation);
         }
      }
   }

   private void updateTotalRagdollWeight() {
      this.ragdollAnimationWeight = 0.0F;
      float remainingWeight = 1.0F;

      for (int animBlendIdx = this.liveAnimationTrackEntries.count() - 1; animBlendIdx >= 0 && remainingWeight > 0.0F; animBlendIdx--) {
         LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendIdx);
         float blendWeight = liveTrackEntry.getBlendWeight();
         if (liveTrackEntry.getTrack().isRagdoll()) {
            this.ragdollAnimationWeight = PZMath.clamp(this.ragdollAnimationWeight + blendWeight, 0.0F, 1.0F);
         }

         remainingWeight -= blendWeight;
      }
   }

   private void postUpdateRagdollInternal(float deltaT) {
      RagdollController ragdollController = this.getRagdollController();
      if (ragdollController != null) {
         ragdollController.postUpdate(deltaT);
      }
   }

   private void copyBoneTransformsFromParentPlayer() {
      this.boneTransformsNeedFirstFrame = false;

      for (int n = 0; n < this.boneTransforms.length; n++) {
         this.boneTransforms[n].set(this.parentPlayer.boneTransforms[n]);
      }
   }

   public static float calculateAnimPlayerAngle(float dirX, float dirY) {
      return Vector2.getDirection(dirX, dirY);
   }

   public void setTargetDirection(float dirX, float dirY) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (this.targetDir.x != dirX || this.targetDir.y != dirY) {
         this.setTargetAngle(calculateAnimPlayerAngle(dirX, dirY));
         this.targetTwistAngle = PZMath.getClosestAngle(this.angle, this.targetAngle);
         float targetTwistClamped = PZMath.clamp(this.targetTwistAngle, -this.maxTwistAngle, this.maxTwistAngle);
         this.excessTwist = PZMath.getClosestAngle(targetTwistClamped, this.targetTwistAngle);
         this.targetDir.set(dirX, dirY);
      }
   }

   public void setTargetAndCurrentDirection(Vector2 dir) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.setTargetAndCurrentDirection(dir.x, dir.y);
   }

   public void setTargetAndCurrentDirection(float dirX, float dirY) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.setTargetAngle(calculateAnimPlayerAngle(dirX, dirY));
      this.setAngleToTarget();
      this.targetTwistAngle = 0.0F;
      this.targetDir.set(dirX, dirY);
   }

   public void updateForwardDirection(IsoGameCharacter character) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (character != null) {
         this.setTargetDirection(character.getForwardDirectionX(), character.getForwardDirectionY());
         this.characterAllowsTwist = character.allowsTwist();
         this.shoulderTwistWeight = character.getShoulderTwistWeight();
      }
   }

   public void updateVerticalAimAngle(IsoGameCharacter character) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (character != null) {
         float prevAngle = character.getCurrentVerticalAimAngle() * (float) (Math.PI / 180.0);
         float targetAngle = character.getTargetVerticalAimAngle() * (float) (Math.PI / 180.0);
         if (!PZMath.equal(prevAngle, targetAngle, 0.01F)) {
            float deltaT = GameTime.instance.getTimeDelta();
            float angleStepBase = 0.08F;
            float angleScaledStepBase = 0.08F * GameTime.instance.getMultiplierFromTimeDelta(deltaT);
            float diff = PZMath.getClosestAngle(prevAngle, targetAngle);
            if (PZMath.equal(diff, 0.0F, 0.001F)) {
               character.setCurrentVerticalAimAngle(targetAngle * (180.0F / (float)Math.PI));
            } else {
               float diffSign = PZMath.sign(diff);
               float angleStep = angleScaledStepBase * diffSign;
               float stepSign = PZMath.sign(angleStep);
               float nextAngleUnclamped = prevAngle + angleStep;
               float newDiffUnclamped = PZMath.getClosestAngle(nextAngleUnclamped, targetAngle);
               float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
               if (newDiffUnclampedSign != diffSign && stepSign == diffSign) {
                  character.setCurrentVerticalAimAngle(targetAngle * (180.0F / (float)Math.PI));
               } else {
                  character.setCurrentVerticalAimAngle(nextAngleUnclamped * (180.0F / (float)Math.PI));
               }
            }
         }
      }
   }

   public void DoAngles(float deltaT) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (!this.isRagdolling()) {
         ProfileArea var2 = GameProfiler.getInstance().profile("AnimationPlayer.doAngles");

         try {
            this.doAnglesInternal(deltaT);
         } catch (Throwable var6) {
            if (var2 != null) {
               try {
                  var2.close();
               } catch (Throwable var5) {
                  var6.addSuppressed(var5);
               }
            }

            throw var6;
         }

         if (var2 != null) {
            var2.close();
         }
      }
   }

   private void doAnglesInternal(float deltaT) {
      float angleScaledStepBase = 0.15F * GameTime.instance.getMultiplierFromTimeDelta(deltaT);
      angleScaledStepBase = PZMath.min(angleScaledStepBase, (float) Math.PI);
      this.interpolateBodyAngle(angleScaledStepBase);
      this.interpolateBodyTwist(angleScaledStepBase);
      this.interpolateShoulderTwist(angleScaledStepBase);
   }

   private void interpolateBodyAngle(float angleScaledStepBase) {
      float targetAngle = this.targetAngle;
      float diff = PZMath.getClosestAngle(this.angle, targetAngle);
      if (PZMath.equal(diff, 0.0F, 0.001F)) {
         this.setAngleToTarget();
         this.targetTwistAngle = 0.0F;
      } else {
         float diffSign = PZMath.sign(diff);
         float angleStepUndeferred = angleScaledStepBase * diffSign * this.angleStepDelta;
         float angleStep;
         if (DebugOptions.instance.character.debug.animate.deferredRotationsOnly.getValue()) {
            angleStep = this.deferredAngleDelta;
         } else if (this.deferredRotationWeight > 0.0F) {
            angleStep = this.deferredAngleDelta * this.deferredRotationWeight + angleStepUndeferred * (1.0F - this.deferredRotationWeight);
         } else {
            angleStep = angleStepUndeferred;
         }

         float stepSign = PZMath.sign(angleStep);
         float prevAngle = this.angle;
         float nextAngleUnclamped = prevAngle + angleStep;
         float newDiffUnclamped = PZMath.getClosestAngle(nextAngleUnclamped, targetAngle);
         float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
         if (newDiffUnclampedSign != diffSign && stepSign == diffSign) {
            this.setAngleToTarget();
            this.targetTwistAngle = 0.0F;
         } else {
            this.setAngle(nextAngleUnclamped);
            this.targetTwistAngle = newDiffUnclamped;
         }
      }
   }

   private void interpolateBodyTwist(float angleScaledStepBase) {
      float targetTwistUnclamped = PZMath.wrap(this.targetTwistAngle, (float) -Math.PI, (float) Math.PI);
      float targetTwist = PZMath.clamp(targetTwistUnclamped, -this.maxTwistAngle, this.maxTwistAngle);
      this.excessTwist = PZMath.getClosestAngle(targetTwist, targetTwistUnclamped);
      float twistDiff = PZMath.getClosestAngle(this.twistAngle, targetTwist);
      if (PZMath.equal(twistDiff, 0.0F, 0.001F)) {
         this.twistAngle = targetTwist;
      } else {
         float twistDiffSign = PZMath.sign(twistDiff);
         float twistAngleStep = angleScaledStepBase * twistDiffSign * PZMath.abs(this.angleTwistDelta);
         float prevTwist = this.twistAngle;
         float nextTwistUnclamped = prevTwist + twistAngleStep;
         float newDiffUnclamped = PZMath.getClosestAngle(nextTwistUnclamped, targetTwist);
         float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
         if (newDiffUnclampedSign == twistDiffSign) {
            this.twistAngle = nextTwistUnclamped;
         } else {
            this.twistAngle = targetTwist;
         }
      }
   }

   private void interpolateShoulderTwist(float angleScaledStepBase) {
      float targetTwist = PZMath.wrap(this.twistAngle, (float) -Math.PI, (float) Math.PI);
      float twistDiff = PZMath.getClosestAngle(this.shoulderTwistAngle, targetTwist);
      if (PZMath.equal(twistDiff, 0.0F, 0.001F)) {
         this.shoulderTwistAngle = targetTwist;
      } else {
         float twistDiffSign = PZMath.sign(twistDiff);
         float twistAngleStep = angleScaledStepBase * twistDiffSign * PZMath.abs(this.angleTwistDelta) * 0.55F;
         float prevTwist = this.shoulderTwistAngle;
         float nextTwistUnclamped = prevTwist + twistAngleStep;
         float newDiffUnclamped = PZMath.getClosestAngle(nextTwistUnclamped, targetTwist);
         float newDiffUnclampedSign = PZMath.sign(newDiffUnclamped);
         if (newDiffUnclampedSign == twistDiffSign) {
            this.shoulderTwistAngle = nextTwistUnclamped;
         } else {
            this.shoulderTwistAngle = targetTwist;
         }
      }
   }

   private void updateTwistBone() {
      ProfileArea var1 = GameProfiler.getInstance().profile("updateTwistBone");

      try {
         this.updateTwistBoneInternal();
      } catch (Throwable var5) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void updateTwistBoneInternal() {
      if (!this.twistBones.isEmpty()) {
         if (!DebugOptions.instance.character.debug.animate.noBoneTwists.getValue()) {
            if (this.characterAllowsTwist) {
               int count = this.twistBones.size();
               int headBoneIdx = count - 1;
               int shoulderBoneIdx = PZArrayUtil.indexOf(this.twistBones, "Bip01_Spine1", AnimationBoneBinding::isBoneName);
               if (shoulderBoneIdx < 0) {
                  shoulderBoneIdx = headBoneIdx - 2;
               }

               if (shoulderBoneIdx < 0) {
                  shoulderBoneIdx = headBoneIdx;
               }

               float shoulderTwistAngle = this.shoulderTwistAngle;
               if (DebugOptions.instance.character.debug.animate.alwaysAimTwist.getValue()) {
                  Vector2 dir = IsoPlayer.getInstance().getAimVector(new Vector2());
                  if (dir.getLengthSquared() > 1.0E-4F) {
                     float worldAngle = calculateAnimPlayerAngle(dir.x, dir.y);
                     shoulderTwistAngle = PZMath.getClosestAngle(this.angle, worldAngle);
                     shoulderTwistAngle = PZMath.clamp(shoulderTwistAngle, -this.maxTwistAngle, this.maxTwistAngle);
                  }
               }

               float headTwistAngle = shoulderTwistAngle;
               SkinningBone headBone = this.twistBones.get(headBoneIdx).getBone();
               Quaternion twistTurnAdjustRot = this.calculateDesiredTwist(headBone, headTwistAngle, AnimationPlayer.L_applyTwistBone.TL.get().twistTurnAdjustRot); // pzopt: per-thread scratch (animBonesParallel)
               Quaternion twistTurnIdentity = AnimationPlayer.L_applyTwistBone.TL.get().twistTurnIdentity; // pzopt: per-thread scratch (animBonesParallel)
               twistTurnIdentity.setIdentity();
               float twistWeightDelta = this.shoulderTwistWeight / (count - 1);
               Quaternion twistTurnStep = AnimationPlayer.L_applyTwistBone.TL.get().twistTurnStep; // pzopt: per-thread scratch (animBonesParallel)
               PZMath.slerp(twistTurnStep, twistTurnIdentity, twistTurnAdjustRot, twistWeightDelta);

               for (int i = 0; i < headBoneIdx; i++) {
                  SkinningBone twistBone = this.twistBones.get(i).getBone();
                  this.applyTwistBone(twistBone, twistTurnStep);
               }

               if (this.isAiming()) {
                  SkinningBone shoulderBone = this.twistBones.get(shoulderBoneIdx).getBone();
                  this.applyTwistBone(shoulderBone, twistTurnStep);
               } else {
                  this.applyTwistBone(headBone, twistTurnStep);
               }
            }
         }
      }
   }

   private boolean isAiming() {
      IsoGameCharacter character = this.getIsoGameCharacter();
      return character != null ? character.isAiming() : false;
   }

   private void applyTwistBone(SkinningBone twistBone, Quaternion twistRot) {
      if (twistBone != null) {
         int boneIndex = twistBone.index;
         int parentBoneIndex = twistBone.parent.index;
         Matrix4f twistParentBoneTrans = this.getBoneModelTransform(parentBoneIndex, AnimationPlayer.L_applyTwistBone.TL.get().twistParentBoneTrans); // pzopt: per-thread scratch (animBonesParallel)
         Matrix4f twistParentBoneTransInv = Matrix4f.invert(twistParentBoneTrans, AnimationPlayer.L_applyTwistBone.TL.get().twistParentBoneTransInv); // pzopt: per-thread scratch (animBonesParallel)
         if (twistParentBoneTransInv != null) {
            Matrix4f twistBoneModelTrans = this.getBoneModelTransform(boneIndex, AnimationPlayer.L_applyTwistBone.TL.get().twistBoneTrans); // pzopt: per-thread scratch (animBonesParallel)
            org.lwjgl.util.vector.Vector3f twistBonePos = HelperFunctions.getPosition(twistBoneModelTrans, AnimationPlayer.L_applyTwistBone.TL.get().twistBonePos); // pzopt: per-thread scratch (animBonesParallel)
            Matrix4f twistBoneNewTrans = AnimationPlayer.L_applyTwistBone.TL.get().twistBoneNewTrans; // pzopt: per-thread scratch (animBonesParallel)
            twistBoneNewTrans.load(twistBoneModelTrans);
            HelperFunctions.setPosition(twistBoneNewTrans, 0.0F, 0.0F, 0.0F);
            Matrix4f twistBoneAdjustTrans = AnimationPlayer.L_applyTwistBone.TL.get().twistBoneAdjustTrans; // pzopt: per-thread scratch (animBonesParallel)
            twistBoneAdjustTrans.setIdentity();
            HelperFunctions.CreateFromQuaternion(twistRot, twistBoneAdjustTrans);
            Matrix4f.mul(twistBoneNewTrans, twistBoneAdjustTrans, twistBoneNewTrans);
            HelperFunctions.setPosition(twistBoneNewTrans, twistBonePos);
            this.boneTransforms[boneIndex].twist = PZMath.wrap(
               HelperFunctions.getRotationY(twistBoneNewTrans) - (float) Math.PI, (float) -Math.PI, (float) Math.PI
            );
            this.boneTransforms[boneIndex].mul(twistBoneNewTrans, twistParentBoneTransInv);
         }
      }
   }

   private Quaternion calculateDesiredTwist(SkinningBone twistBone, float twistAngle, Quaternion twistRot) {
      if (twistBone == null) {
         return twistRot.setIdentity();
      }

      int boneIndex = twistBone.index;
      int parentBoneIndex = twistBone.parent.index;
      Matrix4f twistParentBoneTrans = this.getBoneModelTransform(parentBoneIndex, AnimationPlayer.L_applyTwistBone.TL.get().twistParentBoneTrans); // pzopt: per-thread scratch (animBonesParallel)
      Matrix4f twistParentBoneTransInv = Matrix4f.invert(twistParentBoneTrans, AnimationPlayer.L_applyTwistBone.TL.get().twistParentBoneTransInv); // pzopt: per-thread scratch (animBonesParallel)
      if (twistParentBoneTransInv == null) {
         return twistRot.setIdentity();
      }

      Matrix4f twistBoneModelTrans = this.getBoneModelTransform(boneIndex, AnimationPlayer.L_applyTwistBone.TL.get().twistBoneTrans); // pzopt: per-thread scratch (animBonesParallel)
      Matrix4f twistBoneNewTrans = AnimationPlayer.L_applyTwistBone.TL.get().twistBoneNewTrans; // pzopt: per-thread scratch (animBonesParallel)
      twistBoneNewTrans.load(twistBoneModelTrans);
      org.lwjgl.util.vector.Vector3f desiredForward = AnimationPlayer.L_applyTwistBone.TL.get().desiredForward; // pzopt: per-thread scratch (animBonesParallel)
      desiredForward.set(0.0F, 0.0F, 1.0F);
      HelperFunctions.transform(
         HelperFunctions.setFromAxisAngle(0.0F, 1.0F, 0.0F, twistAngle, AnimationPlayer.L_applyTwistBone.TL.get().twistTurnRot), desiredForward, desiredForward // pzopt: per-thread scratch (animBonesParallel)
      );
      org.lwjgl.util.vector.Vector3f currentForward = AnimationPlayer.L_applyTwistBone.TL.get().forward; // pzopt: per-thread scratch (animBonesParallel)
      currentForward.set(0.0F, 0.0F, -1.0F);
      HelperFunctions.transformVector(twistBoneNewTrans, currentForward, currentForward);
      currentForward.y = 0.0F;
      currentForward.normalise();
      org.lwjgl.util.vector.Vector3f twistRotateAxis = AnimationPlayer.L_applyTwistBone.TL.get().twistRotateAxis; // pzopt: per-thread scratch (animBonesParallel)
      org.lwjgl.util.vector.Vector3f.cross(desiredForward, currentForward, twistRotateAxis);
      if (PZMath.equal(twistRotateAxis.lengthSquared(), 0.0F)) {
         return twistRot.setIdentity();
      }

      twistRotateAxis.normalise();
      float dotAngle = org.lwjgl.util.vector.Vector3f.dot(desiredForward, currentForward);
      float dotAngleClamped = PZMath.clamp(dotAngle, -1.0F, 1.0F);
      float twistRotateAngle = PZMath.acosf(dotAngleClamped);
      HelperFunctions.setFromAxisAngle(twistRotateAxis.x, twistRotateAxis.y, twistRotateAxis.z, -twistRotateAngle, twistRot);
      return twistRot;
   }

   public void resetBoneModelTransforms() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (this.skinningData != null && this.modelTransforms != null) {
         this.boneTransformsNeedFirstFrame = true;
         this.boneTransformsTimeDelta = -1.0F;
         int boneCount = this.boneTransforms.length;

         for (int boneIdx = 0; boneIdx < boneCount; boneIdx++) {
            this.boneTransforms[boneIdx].reset();
            this.modelTransforms[boneIdx].setIdentity();
         }
      }
   }

   public boolean isBoneTransformsNeedFirstFrame() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.boneTransformsNeedFirstFrame;
   }

   private void updateMultiTrackBoneTransforms(float timeDelta) {
      ProfileArea var2 = GameProfiler.getInstance().profile("updateMultiTrackBoneTransforms");

      try {
         this.updateMultiTrackBoneTransformsInternal(timeDelta);
      } catch (Throwable var6) {
         if (var2 != null) {
            try {
               var2.close();
            } catch (Throwable var5) {
               var6.addSuppressed(var5);
            }
         }

         throw var6;
      }

      if (var2 != null) {
         var2.close();
      }
   }

   private void updateMultiTrackBoneTransformsInternal(float timeDelta) {
      this.boneTransformsTimeDelta = timeDelta;

      for (int boneIdx = 0; boneIdx < this.boneTransforms.length; boneIdx++) {
         AnimatorsBoneTransform boneTransform = this.boneTransforms[boneIdx];
         boneTransform.nextFrame(timeDelta);
      }

      for (int boneIdx = 0; boneIdx < this.modelTransforms.length; boneIdx++) {
         this.modelTransforms[boneIdx].setIdentity();
      }

      this.updateLayerBlendWeightings();
      if (this.liveAnimationTrackEntries.count() != 0) {
         if (this.isRecording()) {
            this.recorder.logAnimWeights(this.liveAnimationTrackEntries, this.deferredMovement, this.deferredMovementFromRagdoll);
         }

         for (int boneIdx = 0; boneIdx < this.boneTransforms.length; boneIdx++) {
            if (!this.isBoneReparented(boneIdx)) {
               this.updateBoneAnimationTransform(boneIdx, null);
            }
         }

         this.boneTransformsNeedFirstFrame = false;
      }
   }

   private void updateLayerBlendWeightings() {
      List<AnimationTrack> tracks = this.multiTrack.getTracks();
      this.liveAnimationTrackEntries.setTracks(tracks, 0.001F, this.boneTransformsNeedFirstFrame);
   }

   private void calculateDeferredMovement() {
      ProfileArea var1 = GameProfiler.getInstance().profile("calculateDeferredMovement");

      try {
         this.calculateDeferredMovementInternal();
      } catch (Throwable var5) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void calculateDeferredMovementInternal() {
      synchronized (this.deferredMovementAccumLock) {
         this.calculateDeferredMovementAccumInternal(this.deferredMovementAccum);
         this.pushDeferredMovementAccumToDeferredMovement();
      }
   }

   private void pushDeferredMovementAccumToDeferredMovement() {
      synchronized (this.deferredMovementLock) {
         this.deferredMovement.set(this.deferredMovementAccum);
      }
   }

   private void calculateDeferredMovementAccumInternal(Vector2 deferredMovementAccum) {
      this.deferredAngleDelta = 0.0F;
      this.deferredRotationWeight = 0.0F;
      float remainingWeight = 1.0F;

      for (int animBlendTrackIdx = this.liveAnimationTrackEntries.count() - 1; animBlendTrackIdx >= 0 && !(remainingWeight <= 0.001F); animBlendTrackIdx--) {
         LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendTrackIdx);
         AnimationTrack track = liveTrackEntry.getTrack();
         if (!track.isFinished()) {
            float boneWeight = track.getDeferredBoneWeight();
            if (!(boneWeight <= 0.001F)) {
               float rawAnimWeight = liveTrackEntry.getBlendWeight() * boneWeight;
               if (!(rawAnimWeight <= 0.001F)) {
                  float animWeight = PZMath.clamp(rawAnimWeight, 0.0F, remainingWeight);
                  remainingWeight -= rawAnimWeight;
                  remainingWeight = org.joml.Math.max(0.0F, remainingWeight);
                  if (!track.isRagdoll()) {
                     if (track.getUseDeferredMovement()) {
                        Vector2.addScaled(deferredMovementAccum, track.getDeferredMovementDiff(tempo.get()), animWeight, deferredMovementAccum); // pzopt: per-thread scratch (animBonesParallel)
                     }

                     if (track.getUseDeferredRotation()) {
                        this.deferredAngleDelta = this.deferredAngleDelta + track.getDeferredRotationDiff() * animWeight;
                        this.deferredRotationWeight += animWeight;
                     }
                  }
               }
            }
         }
      }

      this.applyRotationToDeferredMovement(deferredMovementAccum);
      deferredMovementAccum.x = deferredMovementAccum.x * AdvancedAnimator.motionScale;
      deferredMovementAccum.y = deferredMovementAccum.y * AdvancedAnimator.motionScale;
      this.deferredAngleDelta = this.deferredAngleDelta * AdvancedAnimator.rotationScale;
      this.targetGrapplePos.x = this.targetGrapplePos.x + deferredMovementAccum.x;
      this.targetGrapplePos.y = this.targetGrapplePos.y + deferredMovementAccum.y;
   }

   private float calculateDeferredMovementFromRagdolls(Vector3 deferredMovement) {
      float ragdollWeight = 0.0F;
      float remainingWeight = 1.0F;

      for (int animBlendTrackIdx = this.liveAnimationTrackEntries.count() - 1; animBlendTrackIdx >= 0 && !(remainingWeight <= 0.001F); animBlendTrackIdx--) {
         LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendTrackIdx);
         AnimationTrack track = liveTrackEntry.getTrack();
         if (!track.isFinished()) {
            float boneWeight = track.getDeferredBoneWeight();
            if (!(boneWeight <= 0.001F)) {
               float rawAnimWeight = liveTrackEntry.getBlendWeight() * boneWeight;
               if (!(rawAnimWeight <= 0.001F)) {
                  float animWeight = PZMath.clamp(rawAnimWeight, 0.0F, remainingWeight);
                  remainingWeight -= rawAnimWeight;
                  remainingWeight = org.joml.Math.max(0.0F, remainingWeight);
                  if (track.isRagdoll()) {
                     ragdollWeight += animWeight;
                  }
               }
            }
         }
      }

      deferredMovement.set(0.0F, 0.0F, 0.0F);
      ragdollWeight = PZMath.clamp(ragdollWeight, 0.0F, 1.0F) * 0.5F;
      if (this.character != null && this.isSimulationDirectionCalculated()) {
         float deferredWeight = PZMath.clamp(ragdollWeight * GameTime.getInstance().getMultiplier(), 0.0F, 1.0F);
         RagdollController ragdollController = this.getRagdollController();
         deferredMovement.x = (ragdollController.getDesiredCharacterPositionX() - this.character.getX()) * deferredWeight;
         deferredMovement.y = (ragdollController.getDesiredCharacterPositionY() - this.character.getY()) * deferredWeight;
         deferredMovement.z = (ragdollController.getDesiredCharacterPositionZ() - this.character.getZ()) * deferredWeight;
         float sourceAngle = this.targetAngle;
         float simulationAngle = ragdollController.getCalculatedSimulationDirectionAngle();
         float angleDiff = PZMath.getClosestAngle(sourceAngle, simulationAngle);
         float lerpedAngle = PZMath.lerpAngle(sourceAngle, simulationAngle, deferredWeight);
         this.targetAngle = lerpedAngle;
         this.setAngleToTarget();
         float simulationCharacterForwardAngle = this.getRagdollController().getSimulationCharacterForwardAngle();
         if (this.isRecording()) {
            this.recorder.logVariable("anm_simulationCharacterForwardAngle", simulationCharacterForwardAngle * (180.0F / (float)Math.PI));
            this.recorder.logVariable("anm_sourceAngle", sourceAngle * (180.0F / (float)Math.PI));
            this.recorder.logVariable("anm_simulationAngle", simulationAngle * (180.0F / (float)Math.PI));
            this.recorder.logVariable("anm_angleDiff", angleDiff * (180.0F / (float)Math.PI));
            this.recorder.logVariable("anm_ragdollWeight", ragdollWeight);
            this.recorder.logVariable("anm_lerpedAngle", lerpedAngle * (180.0F / (float)Math.PI));
            this.recorder.logVariable("anm_newTargetAngle", this.targetAngle * (180.0F / (float)Math.PI));
            this.recorder.logVariable("anm_deferredMovement.x", deferredMovement.x);
            this.recorder.logVariable("anm_deferredMovement.y", deferredMovement.y);
            this.recorder.logVariable("anm_deferredMovement.z", deferredMovement.z);
         }
      }

      return ragdollWeight;
   }

   private boolean isSimulationDirectionCalculated() {
      return this.isRagdolling() && this.getRagdollController().isSimulationDirectionCalculated();
   }

   private boolean isSimulationActive() {
      return this.isRagdolling() && this.getRagdollController().isSimulationActive();
   }

   private void applyRotationToDeferredMovement(Vector2 result) {
      float angle = this.getRenderedAngle();
      applyRotationToDeferredMovement(result, angle);
   }

   private static void applyRotationToDeferredMovement(Vector2 result, float angle) {
      float len = result.normalize();
      result.rotate(angle);
      result.setLength(-len);
   }

   private void applyBoneReParenting() {
      ProfileArea var1 = GameProfiler.getInstance().profile("applyBoneReParenting");

      try {
         this.applyBoneReParentingInternal();
      } catch (Throwable var5) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void applyBoneReParentingInternal() {
      int reparentIdx = 0;

      for (int reparentCount = this.reparentedBoneBindings.size(); reparentIdx < reparentCount; reparentIdx++) {
         AnimationBoneBindingPair reparentPair = this.reparentedBoneBindings.get(reparentIdx);
         if (!reparentPair.isValid()) {
            DebugType.Animation.warn("Animation binding pair is not valid: %s", new Object[]{reparentPair});
         } else {
            this.updateBoneAnimationTransform(reparentPair.getBoneIdxA(), reparentPair);
         }
      }
   }

   private void updateBoneAnimationTransform(int boneIdx, AnimationBoneBindingPair reparentPair) {
      this.updateBoneAnimationTransform_Internal(boneIdx, reparentPair);
   }

   private void updateBoneAnimationTransform_Internal(int boneIdx, AnimationBoneBindingPair reparentPair) {
      org.lwjgl.util.vector.Vector3f pos = AnimationPlayer.L_updateBoneAnimationTransform.TL.get().pos; // pzopt: per-thread scratch (animBonesParallel)
      Quaternion rot = AnimationPlayer.L_updateBoneAnimationTransform.TL.get().rot; // pzopt: per-thread scratch (animBonesParallel)
      org.lwjgl.util.vector.Vector3f scale = AnimationPlayer.L_updateBoneAnimationTransform.TL.get().scale; // pzopt: per-thread scratch (animBonesParallel)
      Keyframe key = AnimationPlayer.L_updateBoneAnimationTransform.TL.get().key; // pzopt: per-thread scratch (animBonesParallel)
      int totalAnimBlendCount = this.liveAnimationTrackEntries.count();
      AnimationBoneBinding crBone = this.counterRotationBone;
      boolean isCounterRotationBone = crBone != null && crBone.getBone() != null && crBone.getBone().index == boneIdx;
      key.setIdentity();
      float totalWeight = 0.0F;
      boolean isFirst = true;
      float remainingWeight = 1.0F;

      for (int animBlendIdx = totalAnimBlendCount - 1; animBlendIdx >= 0 && remainingWeight > 0.0F && !(remainingWeight <= 0.001F); animBlendIdx--) {
         LiveAnimationTrackEntry liveTrackEntry = this.liveAnimationTrackEntries.get(animBlendIdx);
         AnimationTrack track = liveTrackEntry.getTrack();
         float boneWeight = track.getBoneWeight(boneIdx);
         if (!(boneWeight <= 0.001F)) {
            float rawAnimWeight = liveTrackEntry.getBlendWeight() * boneWeight;
            if (!(rawAnimWeight <= 0.001F)) {
               float animWeight = PZMath.clamp(rawAnimWeight, 0.0F, remainingWeight);
               remainingWeight -= rawAnimWeight;
               remainingWeight = org.joml.Math.max(0.0F, remainingWeight);
               this.getTrackTransform(boneIdx, track, reparentPair, pos, rot, scale);
               if (isCounterRotationBone && !track.isRagdoll() && track.getUseDeferredRotation()) {
                  if (DebugOptions.instance.character.debug.animate.zeroCounterRotationBone.getValue()) {
                     org.lwjgl.util.vector.Vector3f rotAxis = AnimationPlayer.L_updateBoneAnimationTransform.TL.get().rotAxis; // pzopt: per-thread scratch (animBonesParallel)
                     Matrix4f rotMat = AnimationPlayer.L_updateBoneAnimationTransform.TL.get().rotMat; // pzopt: per-thread scratch (animBonesParallel)
                     rotMat.setIdentity();
                     rotAxis.set(0.0F, 1.0F, 0.0F);
                     rotMat.rotate((float) (-Math.PI / 2), rotAxis);
                     rotAxis.set(1.0F, 0.0F, 0.0F);
                     rotMat.rotate((float) (-Math.PI / 2), rotAxis);
                     HelperFunctions.getRotation(rotMat, rot);
                  } else {
                     org.lwjgl.util.vector.Vector3f rotEulers = HelperFunctions.ToEulerAngles(rot, AnimationPlayer.L_updateBoneAnimationTransform.TL.get().rotEulers); // pzopt: per-thread scratch (animBonesParallel)
                     HelperFunctions.ToQuaternion(rotEulers.x, rotEulers.y, (float) (Math.PI / 2), rot);
                  }
               }

               boolean isDeferredMovementBone = !track.isRagdoll() && track.getDeferredMovementBoneIdx() == boneIdx;
               if (isDeferredMovementBone) {
                  org.lwjgl.util.vector.Vector3f deferredCounterPosition = track.getCurrentDeferredCounterPosition(
                     AnimationPlayer.L_updateBoneAnimationTransform.TL.get().deferredPos // pzopt: per-thread scratch (animBonesParallel)
                  );
                  pos.x = pos.x + deferredCounterPosition.x;
                  pos.y = pos.y + deferredCounterPosition.y;
                  pos.z = pos.z + deferredCounterPosition.z;
               }

               if (isFirst) {
                  VectorUtil.setScaled(pos, animWeight, key.position);
                  key.rotation.set(rot);
                  totalWeight = animWeight;
                  isFirst = false;
               } else {
                  float animRotationWeight = animWeight / (animWeight + totalWeight);
                  totalWeight += animWeight;
                  VectorUtil.addScaled(key.position, pos, animWeight, key.position);
                  PZMath.slerp(key.rotation, key.rotation, rot, animRotationWeight);
               }
            }
         }
      }

      if (remainingWeight > 0.0F && !this.boneTransformsNeedFirstFrame) {
         this.boneTransforms[boneIdx].getPRS(pos, rot, scale);
         VectorUtil.addScaled(key.position, pos, remainingWeight, key.position);
         PZMath.slerp(key.rotation, rot, key.rotation, totalWeight);
         PZMath.lerp(key.scale, scale, key.scale, totalWeight);
      }

      this.boneTransforms[boneIdx].set(key.position, key.rotation, key.scale);
      this.boneTransforms[boneIdx].blendWeight = totalWeight;
   }

   private void getTrackTransform(
      int boneIdx,
      AnimationTrack track,
      AnimationBoneBindingPair reparentPair,
      org.lwjgl.util.vector.Vector3f pos,
      Quaternion rot,
      org.lwjgl.util.vector.Vector3f scale
   ) {
      if (boneIdx == SkeletonBone.Bip01.index() && !track.isRagdoll()) {
         if (!track.isInitialAdjustmentCalculated) {
            track.initialAdjustment.set(0.0F, 0.0F, 0.0F);
            if (this.isRagdolling() && DebugOptions.instance.character.debug.animate.keepAtOrigin.getValue()) {
               int adjustingBone = SkeletonBone.Bip01.index();
               Matrix4f existingBone = this.getBoneModelTransform(adjustingBone, new Matrix4f());
               Matrix4f trackBone = this.getUnweightedModelTransform(track, adjustingBone, new Matrix4f());
               org.lwjgl.util.vector.Vector3f existingBonePos = HelperFunctions.getPosition(existingBone, new org.lwjgl.util.vector.Vector3f());
               org.lwjgl.util.vector.Vector3f trackBonePos = HelperFunctions.getPosition(trackBone, new org.lwjgl.util.vector.Vector3f());
               org.lwjgl.util.vector.Vector3f.sub(track.initialAdjustment, trackBonePos, track.initialAdjustment);
            }

            track.isInitialAdjustmentCalculated = true;
         }

         track.get(boneIdx, pos, rot, scale);
         pos.x = pos.x + track.initialAdjustment.x;
         pos.y = pos.y - track.initialAdjustment.z;
      } else if (reparentPair == null) {
         track.get(boneIdx, pos, rot, scale);
      } else {
         Matrix4f result = AnimationPlayer.L_getTrackTransform.TL.get().result; // pzopt: per-thread scratch (animBonesParallel)
         SkinningBone bone = reparentPair.getBoneA();
         Matrix4f pa = getUnweightedBoneTransform(track, bone.index, AnimationPlayer.L_getTrackTransform.TL.get().Pa); // pzopt: per-thread scratch (animBonesParallel)
         SkinningBone boneA = bone.parent;
         SkinningBone boneB = reparentPair.getBoneB();
         Matrix4f mA = this.getBoneModelTransform(boneA.index, AnimationPlayer.L_getTrackTransform.TL.get().mA); // pzopt: per-thread scratch (animBonesParallel)
         Matrix4f mAinv = Matrix4f.invert(mA, AnimationPlayer.L_getTrackTransform.TL.get().mAinv); // pzopt: per-thread scratch (animBonesParallel)
         Matrix4f mB = this.getBoneModelTransform(boneB.index, AnimationPlayer.L_getTrackTransform.TL.get().mB); // pzopt: per-thread scratch (animBonesParallel)
         Matrix4f umA = this.getUnweightedModelTransform(track, boneA.index, AnimationPlayer.L_getTrackTransform.TL.get().umA); // pzopt: per-thread scratch (animBonesParallel)
         Matrix4f umB = this.getUnweightedModelTransform(track, boneB.index, AnimationPlayer.L_getTrackTransform.TL.get().umB); // pzopt: per-thread scratch (animBonesParallel)
         Matrix4f umBinv = Matrix4f.invert(umB, AnimationPlayer.L_getTrackTransform.TL.get().umBinv); // pzopt: per-thread scratch (animBonesParallel)
         Matrix4f.mul(pa, umA, result);
         Matrix4f.mul(result, umBinv, result);
         Matrix4f.mul(result, mB, result);
         Matrix4f.mul(result, mAinv, result);
         HelperFunctions.getPosition(result, pos);
         HelperFunctions.getRotation(result, rot);
         scale.set(1.0F, 1.0F, 1.0F);
      }
   }

   public boolean isBoneReparented(int boneIdx) {
      // pzopt: a plain loop. Stock allocated and released a pooled predicate object per call, i.e. per bone per character
      // per frame (~30k a frame on the Louisville horde); every pool alloc / release also bumps shared atomic counters,
      // which the bone-math worker threads (pzopt.AnimBatch) all contended on. Same answer, no allocation.
      ArrayList<AnimationBoneBindingPair> bindings = this.reparentedBoneBindings;
      for (int i = 0, n = bindings.size(); i < n; i++) {
         if (bindings.get(i).getBoneIdxA() == boneIdx) {
            return true;
         }
      }
      return false;
   }

   private void initRagdollController() {
      if (this.ragdollController == null) {
         if (this.canRagdoll()) {
            pzopt.AnimParallel.noteRagdoll("controller created"); // pzopt: evidence rig, ragdoll physics off the game thread
            RagdollController ragdollController = RagdollController.alloc();
            ragdollController.setGameCharacterObject(this.getIsoGameCharacter());
            if (this.getIsoGameCharacter() != null) {
               this.getIsoGameCharacter().onRagdollSimulationStarted();
            }

            this.ragdollController = ragdollController;
         }
      }
   }

   public boolean isRagdolling() {
      RagdollController ragdollController = this.getRagdollController();
      return ragdollController == null ? false : ragdollController.isInitialized();
   }

   public RagdollController getRagdollController() {
      return this.ragdollController;
   }

   private RagdollController getOrCreateRagdollController() {
      this.initRagdollController();
      return this.getRagdollController();
   }

   public boolean canRagdoll() {
      return this.character != null && this.character.canRagdoll();
   }

   public void stopAll() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.getMultiTrack().reset();
      this.releaseRagdollController();
   }

   public void releaseRagdollController() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (this.ragdollController != null) { pzopt.AnimParallel.noteRagdoll("controller released"); } // pzopt: evidence rig, ragdoll physics off the game thread
      this.ragdollController = (RagdollController)Pool.tryRelease(this.ragdollController);
      if (this.ragdollAnimationClip != null) {
         this.ragdollAnimationClip.setRagdollSimulationActive(false);
      }

      this.ragdollAnimationWeight = 0.0F;
   }

   public AnimationClip getRagdollSimulationAnimationClip() {
      return this.ragdollAnimationClip;
   }

   public void setIsoGameCharacter(IsoGameCharacter character) {
      this.character = character;
   }

   public IsoGameCharacter getIsoGameCharacter() {
      return this.character;
   }

   public int getModelTransformsCount() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return PZArrayUtil.lengthOf(this.modelTransforms);
   }

   public Matrix4f getModelTransformAt(int idx) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.modelTransforms[idx];
   }

   public float getBoneTransformsTimeDelta() {
      return this.boneTransformsTimeDelta;
   }

   public boolean isRagdollSimulationActive() {
      return this.ragdollController != null && this.ragdollController.isSimulationActive();
   }

   public boolean isFullyRagdolling() {
      return this.isRagdolling() && this.isRagdollSimulationActive() && this.ragdollAnimationWeight > 0.9F;
   }

   public void updateMultiTrackBoneTransforms_DeferredMovementOnly() {
      this.deferredMovementFromRagdoll.set(0.0F, 0.0F, 0.0F);
      if (this.parentPlayer == null) {
         this.updateLayerBlendWeightings();
         if (this.liveAnimationTrackEntries.count() != 0) {
            int[] boneIndices = AnimationPlayer.updateMultiTrackBoneTransforms_DeferredMovementOnly.TL.get().boneIndices; // pzopt: per-thread scratch (animBonesParallel)
            int boneCount = 0;
            List<AnimationTrack> tracks = this.multiTrack.getTracks();
            int tracksCount = tracks.size();

            for (int trackIdx = 0; trackIdx < tracksCount; trackIdx++) {
               AnimationTrack track = tracks.get(trackIdx);
               int boneIdx = track.getDeferredMovementBoneIdx();
               if (boneIdx != -1 && !PZArrayUtil.contains(boneIndices, boneCount, boneIdx)) {
                  boneIndices[boneCount++] = boneIdx;
               }
            }

            for (int i = 0; i < boneCount; i++) {
               this.updateBoneAnimationTransform(boneIndices[i], null);
            }
         }
      }
   }

   public boolean isRecording() {
      return this.recorder != null && this.recorder.isRecording();
   }

   public void setRecorder(AnimationPlayerRecorder recorder) {
      this.recorder = recorder;
   }

   public AnimationPlayerRecorder getRecorder() {
      return this.recorder;
   }

   public void dismember(int bone) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.dismembered.add(bone);
   }

   private void updateModelTransforms() {
      ProfileArea var1 = GameProfiler.getInstance().profile("updateModelTransforms");

      try {
         this.updateModelTransformsInternal();
      } catch (Throwable var5) {
         if (var1 != null) {
            try {
               var1.close();
            } catch (Throwable var4) {
               var5.addSuppressed(var4);
            }
         }

         throw var5;
      }

      if (var1 != null) {
         var1.close();
      }
   }

   private void updateModelTransformsInternal() {
      this.boneTransforms[0].getMatrix(this.modelTransforms[0]);

      for (int boneIdx = 1; boneIdx < this.modelTransforms.length; boneIdx++) {
         SkinningBone bone = this.skinningData.getBoneAt(boneIdx);
         SkinningBone parentBone = bone.parent;
         BoneTransform.mul(this.boneTransforms[bone.index], this.modelTransforms[parentBone.index], this.modelTransforms[bone.index]);
      }
   }

   public void transformRootChildBones(String boneName, Quaternion rotation) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      Matrix4f rotationMatrix = HelperFunctions.CreateFromQuaternion(rotation, HelperFunctions.getMatrix());

      for (int boneIdx = 0; boneIdx < this.modelTransforms.length; boneIdx++) {
         SkinningBone bone = this.skinningData.getBoneAt(boneIdx);
         if (StringUtils.equalsIgnoreCase(bone.name, boneName)) {
            BoneTransform.mul(rotationMatrix, this.boneTransforms[bone.index], this.boneTransforms[bone.index]);
            break;
         }
      }

      this.updateModelTransformsInternal();
      HelperFunctions.returnMatrix(rotationMatrix);
   }

   public Matrix4f getBoneModelTransform(int boneIdx, Matrix4f modelTransform) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      Matrix4f boneTransform = AnimationPlayer.L_getBoneModelTransform.TL.get().boneTransform; // pzopt: per-thread scratch (animBonesParallel)
      modelTransform.setIdentity();
      SkinningBone bone = this.skinningData.getBoneAt(boneIdx);

      for (SkinningBone current = bone; current != null; current = current.parent) {
         this.getBoneTransform(current.index, boneTransform);
         Matrix4f.mul(modelTransform, boneTransform, modelTransform);
      }

      return modelTransform;
   }

   private org.lwjgl.util.vector.Vector3f getBoneModelPosition(SkeletonBone bone, org.lwjgl.util.vector.Vector3f pos) {
      return HelperFunctions.getPosition(this.getBoneModelTransform(bone.index(), new Matrix4f()), pos);
   }

   public org.lwjgl.util.vector.Vector3f getBoneWorldPosition(SkeletonBone bone, org.lwjgl.util.vector.Vector3f pos) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.getBoneModelPosition(bone, pos);
      Vector3 pos3 = new Vector3(pos.x, pos.y, pos.z);
      Model.vectorToWorldCoords(this.character, pos3);
      pos.set(pos3.x, pos3.y, pos3.z);
      return pos;
   }

   public Matrix4f getBindPoseBoneModelTransform(int boneIdx, Matrix4f modelTransform) {
      Matrix4f boneTransform = AnimationPlayer.L_getBoneModelTransform.TL.get().boneTransform; // pzopt: per-thread scratch (animBonesParallel)
      modelTransform.setIdentity();
      SkinningBone bone = this.skinningData.getBoneAt(boneIdx);

      for (SkinningBone current = bone; current != null; current = current.parent) {
         boneTransform.load((Matrix4f)this.skinningData.bindPose.get(current.index));
         Matrix4f.mul(modelTransform, boneTransform, modelTransform);
      }

      return modelTransform;
   }

   public Matrix4f getBoneTransform(int boneIdx, Matrix4f boneTransform) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.boneTransforms[boneIdx].getMatrix(boneTransform);
      return boneTransform;
   }

   public TwistableBoneTransform getBone(int boneIdx) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.boneTransforms[boneIdx];
   }

   public Matrix4f getUnweightedModelTransform(AnimationTrack track, int boneIdx, Matrix4f modelTransform) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      Matrix4f boneTransform = AnimationPlayer.L_getUnweightedModelTransform.TL.get().boneTransform; // pzopt: per-thread scratch (animBonesParallel)
      boneTransform.setIdentity();
      modelTransform.setIdentity();
      SkinningBone bone = this.skinningData.getBoneAt(boneIdx);

      for (SkinningBone current = bone; current != null; current = current.parent) {
         getUnweightedBoneTransform(track, current.index, boneTransform);
         Matrix4f.mul(modelTransform, boneTransform, modelTransform);
      }

      return modelTransform;
   }

   public static Matrix4f getUnweightedBoneTransform(AnimationTrack track, int boneIdx, Matrix4f boneTransform) {
      org.lwjgl.util.vector.Vector3f pos = AnimationPlayer.L_getUnweightedBoneTransform.TL.get().pos; // pzopt: per-thread scratch (animBonesParallel)
      Quaternion rot = AnimationPlayer.L_getUnweightedBoneTransform.TL.get().rot; // pzopt: per-thread scratch (animBonesParallel)
      org.lwjgl.util.vector.Vector3f scale = AnimationPlayer.L_getUnweightedBoneTransform.TL.get().scale; // pzopt: per-thread scratch (animBonesParallel)
      track.get(boneIdx, pos, rot, scale);
      HelperFunctions.CreateFromQuaternionPositionScale(pos, rot, scale, boneTransform);
      return boneTransform;
   }

   public void UpdateSkinTransforms() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.resetSkinTransforms();
   }

   public Matrix4f[] getSkinTransforms(SkinningData skinnedTo) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      if (skinnedTo == null) {
         return this.modelTransforms;
      }

      AnimationPlayer.SkinTransformData data = this.getSkinTransformData(skinnedTo);
      Matrix4f[] skinTransforms = data.transforms;
      if (data.dirty) {
         data.pzoptPaletteValid = false; // pzopt: skinPalettePrecompute, the palette below is for the old transforms
         data.checkBoneMap(this.getSkinningData());

         for (int bone = 0; bone < this.modelTransforms.length; bone++) {
            int boneTo = data.boneMap[bone];
            if (boneTo != -1) {
               if (skinnedTo.boneOffset != null && skinnedTo.boneOffset.get(boneTo) != null) {
                  Matrix4f.mul((Matrix4f)skinnedTo.boneOffset.get(boneTo), this.modelTransforms[bone], skinTransforms[boneTo]);
               } else {
                  skinTransforms[boneTo].setIdentity();
               }
            }
         }

         data.dirty = false;
      }

      return skinTransforms;
   }

   public Vector2 getDeferredMovement(Vector2 result, boolean reset) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      synchronized (this.deferredMovementLock) {
         result.set(this.deferredMovement);
      }

      if (reset) {
         synchronized (this.deferredMovementAccumLock) {
            this.deferredMovementAccum.set(0.0F, 0.0F);
         }
      }

      return result;
   }

   public void resetDeferredMovementAccum() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      synchronized (this.deferredMovementAccumLock) {
         this.deferredMovementAccum.set(0.0F, 0.0F);
      }
   }

   public Vector3 getDeferredMovementFromRagdoll(Vector3 result) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return result.set(this.deferredMovementFromRagdoll);
   }

   public float getDeferredAngleDelta() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.deferredAngleDelta;
   }

   public float getDeferredRotationWeight() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.deferredRotationWeight;
   }

   public Vector3f getTargetGrapplePos(Vector3f result) {
      result.set(this.targetGrapplePos);
      return result;
   }

   public Vector3 getTargetGrapplePos(Vector3 result) {
      result.set(this.targetGrapplePos.x, this.targetGrapplePos.y, this.targetGrapplePos.z);
      return result;
   }

   public void setTargetGrapplePos(float x, float y, float z) {
      this.targetGrapplePos.set(x, y, z);
   }

   public void setTargetGrappleRotation(float x, float y) {
      this.targetGrappleRotation.set(x, y);
   }

   public Vector2 getTargetGrappleRotation(Vector2 result) {
      result.set(this.targetGrappleRotation);
      return result;
   }

   public Vector3f getGrappleOffset(Vector3f result) {
      result.set(this.grappleOffset);
      return result;
   }

   public Vector3 getGrappleOffset(Vector3 result) {
      result.set(this.grappleOffset.x, this.grappleOffset.y, this.grappleOffset.z);
      return result;
   }

   public void setGrappleOffset(float x, float y, float z) {
      this.grappleOffset.set(x, y, z);
   }

   public AnimationMultiTrack getMultiTrack() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.multiTrack;
   }

   public void setRecording(boolean val) {
      this.recorder.setRecording(val);
   }

   public void discardRecording() {
      if (this.recorder != null) {
         this.recorder.discardRecording();
      }
   }

   public float getRenderedAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.angle + (float) (Math.PI / 2);
   }

   public float getAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.angle;
   }

   public void setAngle(float angle) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.angle = angle;
   }

   public void setAngleToTarget() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.setAngle(this.targetAngle);
   }

   public void setTargetToAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      float angle = this.getAngle();
      this.setTargetAngle(angle);
   }

   public float getTargetAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.targetAngle;
   }

   public void setTargetAngle(float targetAngle) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.targetAngle = targetAngle;
   }

   public float getMaxTwistAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.maxTwistAngle;
   }

   public void setMaxTwistAngle(float radians) {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      this.maxTwistAngle = radians;
   }

   public float getExcessTwistAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.excessTwist;
   }

   public float getTwistAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.twistAngle;
   }

   public float getShoulderTwistAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.shoulderTwistAngle;
   }

   public float getTargetTwistAngle() {
      if (this.pzoptInFlight) { pzopt.AnimBatch.guard(); } // pzopt: animBatchAsync, join the bone batch before a game-thread touch
      return this.targetTwistAngle;
   }

   private static class L_applyTwistBone {
      // pzopt: one scratch set per thread; the standard-animation math of a zombie runs on a worker (pzopt.AnimBatch)
      static final ThreadLocal<AnimationPlayer.L_applyTwistBone> TL = ThreadLocal.withInitial(AnimationPlayer.L_applyTwistBone::new);
      final Matrix4f twistParentBoneTrans = new Matrix4f();
      final Matrix4f twistParentBoneTransInv = new Matrix4f();
      final Matrix4f twistBoneTrans = new Matrix4f();
      final org.lwjgl.util.vector.Vector3f twistBonePos = new org.lwjgl.util.vector.Vector3f();
      final Matrix4f twistBoneNewTrans = new Matrix4f();
      final Matrix4f twistBoneAdjustTrans = new Matrix4f();
      final org.lwjgl.util.vector.Vector3f twistRotateAxis = new org.lwjgl.util.vector.Vector3f();
      final org.lwjgl.util.vector.Vector3f forward = new org.lwjgl.util.vector.Vector3f();
      final Quaternion twistTurnRot = new Quaternion();
      final Quaternion twistTurnAdjustRot = new Quaternion();
      final Quaternion twistTurnStep = new Quaternion();
      final Quaternion twistTurnIdentity = new Quaternion();
      final org.lwjgl.util.vector.Vector3f desiredForward = new org.lwjgl.util.vector.Vector3f();
   }

   private static class L_getBoneModelTransform {
      // pzopt: one scratch set per thread; the standard-animation math of a zombie runs on a worker (pzopt.AnimBatch)
      static final ThreadLocal<AnimationPlayer.L_getBoneModelTransform> TL = ThreadLocal.withInitial(AnimationPlayer.L_getBoneModelTransform::new);
      final Matrix4f boneTransform = new Matrix4f();
      final Matrix4f modelTransform = new Matrix4f();
   }

   private static final class L_getTrackTransform {
      // pzopt: one scratch set per thread; the standard-animation math of a zombie runs on a worker (pzopt.AnimBatch)
      static final ThreadLocal<AnimationPlayer.L_getTrackTransform> TL = ThreadLocal.withInitial(AnimationPlayer.L_getTrackTransform::new);
      final Matrix4f Pa = new Matrix4f();
      final Matrix4f mA = new Matrix4f();
      final Matrix4f mB = new Matrix4f();
      final Matrix4f umA = new Matrix4f();
      final Matrix4f umB = new Matrix4f();
      final Matrix4f mAinv = new Matrix4f();
      final Matrix4f umBinv = new Matrix4f();
      final Matrix4f result = new Matrix4f();
   }

   private static class L_getUnweightedBoneTransform {
      // pzopt: one scratch set per thread; the standard-animation math of a zombie runs on a worker (pzopt.AnimBatch)
      static final ThreadLocal<AnimationPlayer.L_getUnweightedBoneTransform> TL = ThreadLocal.withInitial(AnimationPlayer.L_getUnweightedBoneTransform::new);
      final org.lwjgl.util.vector.Vector3f pos = new org.lwjgl.util.vector.Vector3f();
      final Quaternion rot = new Quaternion();
      final org.lwjgl.util.vector.Vector3f scale = new org.lwjgl.util.vector.Vector3f();
   }

   private static class L_getUnweightedModelTransform {
      // pzopt: one scratch set per thread; the standard-animation math of a zombie runs on a worker (pzopt.AnimBatch)
      static final ThreadLocal<AnimationPlayer.L_getUnweightedModelTransform> TL = ThreadLocal.withInitial(AnimationPlayer.L_getUnweightedModelTransform::new);
      final Matrix4f boneTransform = new Matrix4f();
   }

   private static final class L_setTwistBones {
      static final ArrayList<String> boneNames = new ArrayList<>();
   }

   private static final class L_updateBoneAnimationTransform {
      // pzopt: one scratch set per thread; the standard-animation math of a zombie runs on a worker (pzopt.AnimBatch)
      static final ThreadLocal<AnimationPlayer.L_updateBoneAnimationTransform> TL = ThreadLocal.withInitial(AnimationPlayer.L_updateBoneAnimationTransform::new);
      final Quaternion rot = new Quaternion();
      final org.lwjgl.util.vector.Vector3f pos = new org.lwjgl.util.vector.Vector3f();
      final org.lwjgl.util.vector.Vector3f scale = new org.lwjgl.util.vector.Vector3f();
      final Keyframe key = new Keyframe(
         new org.lwjgl.util.vector.Vector3f(0.0F, 0.0F, 0.0F), new Quaternion(0.0F, 0.0F, 0.0F, 1.0F), new org.lwjgl.util.vector.Vector3f(1.0F, 1.0F, 1.0F)
      );
      final Matrix4f boneMat = new Matrix4f();
      final Matrix4f rotMat = new Matrix4f();
      final org.lwjgl.util.vector.Vector3f rotAxis = new org.lwjgl.util.vector.Vector3f(1.0F, 0.0F, 0.0F);
      final Quaternion crRot = new Quaternion();
      final Vector4f crRotAA = new Vector4f();
      final Matrix4f crMat = new Matrix4f();
      final org.lwjgl.util.vector.Vector3f rotEulers = new org.lwjgl.util.vector.Vector3f();
      final org.lwjgl.util.vector.Vector3f deferredPos = new org.lwjgl.util.vector.Vector3f();
   }

   private static class SkinTransformData extends PooledObject {
      public Matrix4f[] transforms;
      private SkinningData skinnedTo;
      public boolean dirty;
      // pzopt: skinPalettePrecompute. The transforms stored column by column as the shader palette, filled by the worker
      // right after the transforms (pzoptPrecomputeSkinTransforms), valid until the transforms are recomputed.
      java.nio.FloatBuffer pzoptPalette;
      boolean pzoptPaletteValid;
      private SkinningData animPlayerSkinningData;
      private int[] boneMap;
      private AnimationPlayer.SkinTransformData next;
      private static final Pool<AnimationPlayer.SkinTransformData> s_pool = new Pool(AnimationPlayer.SkinTransformData::new);

      public void setSkinnedTo(SkinningData skinnedTo) {
         if (this.skinnedTo != skinnedTo) {
            this.dirty = true;
            this.skinnedTo = skinnedTo;
            this.transforms = (Matrix4f[])PZArrayUtil.newInstance(Matrix4f.class, this.transforms, skinnedTo.numBones(), Matrix4f::new);
            this.animPlayerSkinningData = null;
         }
      }

      public void checkBoneMap(SkinningData animPlayerSkinningData) {
         if (this.animPlayerSkinningData != animPlayerSkinningData) {
            this.animPlayerSkinningData = animPlayerSkinningData;
            int numBones = animPlayerSkinningData.numBones();
            if (this.boneMap == null || this.boneMap.length < numBones) {
               this.boneMap = new int[numBones];
            }

            for (int i = 0; i < numBones; i++) {
               SkinningBone skinningBone = animPlayerSkinningData.getBoneAt(i);
               Integer boneIndexObj = (Integer)this.skinnedTo.boneIndices.get(skinningBone.name);
               if (boneIndexObj == null) {
                  this.boneMap[i] = -1;
               } else {
                  this.boneMap[i] = boneIndexObj;
               }
            }
         }
      }

      public static AnimationPlayer.SkinTransformData alloc(SkinningData skinnedTo) {
         AnimationPlayer.SkinTransformData newInstance = (AnimationPlayer.SkinTransformData)s_pool.alloc();
         newInstance.setSkinnedTo(skinnedTo);
         newInstance.dirty = true;
         return newInstance;
      }
   }

   private static final class updateMultiTrackBoneTransforms_DeferredMovementOnly {
      // pzopt: one scratch set per thread; the standard-animation math of a zombie runs on a worker (pzopt.AnimBatch)
      static final ThreadLocal<AnimationPlayer.updateMultiTrackBoneTransforms_DeferredMovementOnly> TL = ThreadLocal.withInitial(AnimationPlayer.updateMultiTrackBoneTransforms_DeferredMovementOnly::new);
      int[] boneIndices = new int[60];
   }
}
