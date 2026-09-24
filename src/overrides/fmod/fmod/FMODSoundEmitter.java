package fmod.fmod;

import fmod.FMOD_STUDIO_EVENT_PROPERTY;
import fmod.javafmod;
import fmod.javafmodJNI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import zombie.GameSounds;
import zombie.SoundManager;
import zombie.UsedFromLua;
import zombie.audio.BaseSoundEmitter;
import zombie.audio.FMODParameter;
import zombie.audio.GameSound;
import zombie.audio.GameSoundClip;
import zombie.audio.parameters.ParameterOcclusion;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.debug.DebugLog;
import zombie.debug.DebugOptions;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.areas.IsoRoom;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.INetworkPacket;
import zombie.popman.ObjectPool;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.CharacterTrait;
import zombie.scripting.objects.SoundTimelineScript;

@UsedFromLua
public final class FMODSoundEmitter extends BaseSoundEmitter {
   private final ArrayList<FMODSoundEmitter.Sound> toStart = new ArrayList<>();
   private final ArrayList<FMODSoundEmitter.Sound> instances = new ArrayList<>();
   private final ArrayList<FMODSoundEmitter.Sound> stopped = new ArrayList<>();
   public float x;
   public float y;
   public float z;
   public EmitterType emitterType;
   public IsoObject parent;
   private boolean playRemoteEvents;
   private final ParameterOcclusion occlusion = new ParameterOcclusion(this);
   private final ArrayList<FMODParameter> parameters = new ArrayList<>();
   public IFMODParameterUpdater parameterUpdater;
   private final ArrayList<FMODSoundEmitter.ParameterValue> parameterValues = new ArrayList<>();
   private static final ObjectPool<FMODSoundEmitter.ParameterValue> parameterValuePool = new ObjectPool(
      FMODSoundEmitter.ParameterValue::new, "FMODSoundEmitter.parameterValuePool"
   );
   private static BitSet parameterSet;
   private final ArrayDeque<FMODSoundEmitter.EventSound> eventSoundPool = new ArrayDeque<>();
   private final ArrayDeque<FMODSoundEmitter.FileSound> fileSoundPool = new ArrayDeque<>();
   private static long currentTimeMs;

   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy (quiet: FMOD starts on a boot
   // thread, possibly before the logger)
   static {
      pzopt.Overrides.onClassLoadedQuiet("fmod.fmod.FMODSoundEmitter");
   }

   public FMODSoundEmitter() {
      SoundManager.instance.registerEmitter(this);
      if (parameterSet == null) {
         parameterSet = new BitSet(FMODManager.instance.getParameterCount());
      }
   }

   public void randomStart() {
   }

   public void setPos(float x, float y, float z) {
      this.x = x;
      this.y = y;
      this.z = z;
   }

   public int stopSound(long soundRef) {
      return this.stopSound(soundRef, true);
   }

   public int stopSoundDelayRelease(long soundRef) {
      return this.stopSound(soundRef, false);
   }

   private int stopSound(long soundRef, boolean bReleaseEvent) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (s.getRef() == soundRef) {
            this.sendStopSound(s.name, false);
            s.release(s.clip.isStopImmediate());
            this.toStart.remove(i--);
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            s.stop(bReleaseEvent, s.clip.isStopImmediate());
            this.sendStopSound(s.name, false);
            if (bReleaseEvent) {
               s.release(s.clip.isStopImmediate());
            } else {
               this.stopped.add(s);
            }

            this.instances.remove(i--);
         }
      }

      return 0;
   }

   public void stopSoundLocal(long soundRef) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (s.getRef() == soundRef) {
            s.release(s.clip.isStopImmediate());
            this.toStart.remove(i--);
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            s.stop(true, s.clip.isStopImmediate());
            s.release(s.clip.isStopImmediate());
            this.instances.remove(i--);
         }
      }
   }

   public void stopOrTriggerSoundLocal(long soundRef) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (s.getRef() == soundRef) {
            s.release(s.clip.isStopImmediate());
            this.toStart.remove(i--);
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            if (s.clip.hasSustainPoints(s.remote)) {
               s.triggerCue();
            } else {
               s.stop(true, s.clip.isStopImmediate());
               s.release(s.clip.isStopImmediate());
               this.instances.remove(i--);
            }
         }
      }
   }

   public int stopSoundByName(String name) {
      GameSound gameSound = GameSounds.getSound(name);
      if (gameSound == null) {
         return 0;
      }

      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (gameSound.clips.contains(s.clip)) {
            s.release(s.clip.isStopImmediate());
            this.toStart.remove(i--);
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (gameSound.clips.contains(s.clip)) {
            s.stop(true, s.clip.isStopImmediate());
            s.release(s.clip.isStopImmediate());
            this.instances.remove(i--);
         }
      }

      return 0;
   }

   public void stopOrTriggerSound(long handle) {
      int index = this.findToStart(handle);
      if (index != -1) {
         FMODSoundEmitter.Sound s = this.toStart.remove(index);
         this.sendStopSound(s.name, true);
         s.release(s.clip.isStopImmediate());
      } else {
         index = this.findInstance(handle);
         if (index != -1) {
            FMODSoundEmitter.Sound s = this.instances.get(index);
            this.sendStopSound(s.name, true);
            if (s instanceof FMODSoundEmitter.EventSound eventSound) {
               FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription = FMODManager.instance.getParameterDescription("ActionProgressPercent");
               if (eventSound.getEventDescription().hasParameter(parameterDescription)) {
                  eventSound.setParameterValue(parameterDescription, 100.0F);
                  eventSound.triggeredCue = true;
                  eventSound.checkTimeMs = currentTimeMs;
                  return;
               }
            }

            if (s.clip.hasSustainPoints(s.remote)) {
               s.triggerCue();
            } else {
               this.instances.remove(index);
               s.stop(true, s.clip.isStopImmediate());
               s.release(s.clip.isStopImmediate());
            }
         }
      }
   }

   public void stopOrTriggerSoundByName(String name) {
      GameSound gameSound = GameSounds.getSound(name);
      if (gameSound != null) {
         for (int i = 0; i < this.toStart.size(); i++) {
            FMODSoundEmitter.Sound s = this.toStart.get(i);
            if (gameSound.clips.contains(s.clip)) {
               this.toStart.remove(i--);
               s.release(s.clip.isStopImmediate());
            }
         }

         for (int i = 0; i < this.instances.size(); i++) {
            FMODSoundEmitter.Sound s = this.instances.get(i);
            if (gameSound.clips.contains(s.clip)) {
               if (s.clip.hasSustainPoints(s.remote)) {
                  s.triggerCue();
               } else {
                  s.stop(true, s.clip.isStopImmediate());
                  s.release(s.clip.isStopImmediate());
                  this.instances.remove(i--);
               }
            }
         }
      }
   }

   private void limitSound(GameSound gameSound, int maxInstances) {
      int total = this.countToStart(gameSound) + this.countInstances(gameSound);
      if (total > maxInstances) {
         for (int i = 0; i < this.toStart.size(); i++) {
            FMODSoundEmitter.Sound s = this.toStart.get(i);
            if (gameSound.clips.contains(s.clip)) {
               this.toStart.remove(i--);
               s.release(s.clip.isStopImmediate());
               if (--total <= maxInstances) {
                  return;
               }
            }
         }

         for (int i = 0; i < this.instances.size(); i++) {
            FMODSoundEmitter.Sound s = this.instances.get(i);
            if (gameSound.clips.contains(s.clip)) {
               if (s.clip.hasSustainPoints(s.remote)) {
                  if (!s.isTriggeredCue()) {
                     s.triggerCue();
                  }
               } else {
                  s.stop(true, s.clip.isStopImmediate());
                  s.release(s.clip.isStopImmediate());
                  this.instances.remove(i--);
                  if (--total <= maxInstances) {
                     return;
                  }
               }
            }
         }
      }
   }

   public void setVolume(long soundRef, float volume) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (s.getRef() == soundRef) {
            s.volume = volume;
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            s.volume = volume;
         }
      }
   }

   public void setPitch(long soundRef, float pitch) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (s.getRef() == soundRef) {
            DebugLog.log("Set pitch for ToStart");
         }

         s.pitch = pitch;
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            DebugLog.log("Set pitch for Instance");
         }

         s.pitch = pitch;
      }
   }

   public boolean hasSustainPoints(long soundRef) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (s.getRef() == soundRef) {
            if (s.getEventDescription() == null) {
               return false;
            }

            return s.getEventDescription().hasSustainPoints;
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            if (s.getEventDescription() == null) {
               return false;
            }

            return s.getEventDescription().hasSustainPoints;
         }
      }

      return false;
   }

   public void triggerCue(long soundRef) {
      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            s.triggerCue();
         }
      }
   }

   public void setVolumeAll(float volume) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         s.volume = volume;
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         s.volume = volume;
      }
   }

   public void stopAll() {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         s.release(s.clip.isStopImmediate());
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         s.stop(true, s.clip.isStopImmediate());
         s.release(s.clip.isStopImmediate());
      }

      this.toStart.clear();
      this.instances.clear();
   }

   public long playSound(String file) {
      if (GameClient.client) {
         if (this.parent instanceof IsoMovingObject movingObject) {
            if (!(this.parent instanceof IsoPlayer player && player.isInvisible())) {
               INetworkPacket.send(PacketType.PlaySound, new Object[]{file, (byte)0, movingObject});
            }
         } else {
            GameClient.instance.PlayWorldSound(file, PZMath.fastfloor(this.x), PZMath.fastfloor(this.y), (byte)PZMath.fastfloor(this.z));
         }
      }

      return GameServer.server ? 0L : this.playSoundImpl(file, (IsoObject)null);
   }

   public long playSound(String file, IsoGameCharacter character) {
      if (GameClient.client) {
         if (!character.isInvisible()) {
            INetworkPacket.send(PacketType.PlaySound, new Object[]{file, (byte)0, character});
         }

         return character.isInvisible() && !DebugOptions.instance.character.debug.playSoundWhenInvisible.getValue()
            ? 0L
            : this.playSoundImpl(file, (IsoObject)null);
      } else {
         return GameServer.server ? 0L : this.playSoundImpl(file, (IsoObject)null);
      }
   }

   public long playSound(String file, int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
      return this.playSound(file);
   }

   public long playSound(String file, IsoGridSquare square) {
      this.x = square.x + 0.5F;
      this.y = square.y + 0.5F;
      this.z = square.z;
      return this.playSound(file);
   }

   public long playSoundImpl(String file, IsoGridSquare square) {
      this.x = square.x + 0.5F;
      this.y = square.y + 0.5F;
      this.z = square.z + 0.5F;
      return this.playSoundImpl(file, (IsoObject)null);
   }

   public long playSound(String file, boolean doWorldSound) {
      return this.playSound(file);
   }

   public long playSoundImpl(String file, boolean doWorldSound, IsoObject parent) {
      return this.playSoundImpl(file, parent);
   }

   public long playSoundLooped(String file) {
      if (GameClient.client) {
         if (this.parent instanceof IsoMovingObject isoMovingObject) {
            INetworkPacket.send(PacketType.PlaySound, new Object[]{file, (byte)1, isoMovingObject});
         } else {
            GameClient.instance.PlayWorldSound(file, PZMath.fastfloor(this.x), PZMath.fastfloor(this.y), (byte)PZMath.fastfloor(this.z));
         }
      }

      return this.playSoundLoopedImpl(file);
   }

   public long playSoundLoopedImpl(String file) {
      return this.playSoundImpl(file, false, null);
   }

   public long playSound(String file, IsoObject parent) {
      if (GameClient.client) {
         if (parent instanceof IsoMovingObject isoMovingObject) {
            INetworkPacket.send(PacketType.PlaySound, new Object[]{file, (byte)0, isoMovingObject});
         } else {
            GameClient.instance.PlayWorldSound(file, PZMath.fastfloor(this.x), PZMath.fastfloor(this.y), (byte)PZMath.fastfloor(this.z));
         }
      }

      return GameServer.server ? 0L : this.playSoundImpl(file, parent);
   }

   public long playSoundImpl(String file, IsoObject parent) {
      GameSound gameSound = GameSounds.getSound(file);
      if (gameSound == null) {
         return 0L;
      }

      GameSoundClip clip = gameSound.getRandomClip();
      return this.playClip(clip, parent);
   }

   public long playClip(GameSoundClip clip, IsoObject parent) {
      FMODSoundEmitter.Sound s = this.addSound(clip, 1.0F, parent);
      return s == null ? 0L : s.getRef();
   }

   public long playAmbientSound(String name) {
      if (GameServer.server) {
         return 0L;
      }

      GameSound gameSound = GameSounds.getSound(name);
      if (gameSound == null) {
         return 0L;
      }

      GameSoundClip clip = gameSound.getRandomClip();
      FMODSoundEmitter.Sound s = this.addSound(clip, 1.0F, null);
      if (s instanceof FMODSoundEmitter.FileSound fileSound) {
         fileSound.ambient = true;
      }

      return s == null ? 0L : s.getRef();
   }

   public long playAmbientLoopedImpl(String file) {
      if (GameServer.server) {
         return 0L;
      }

      GameSound gameSound = GameSounds.getSound(file);
      if (gameSound == null) {
         return 0L;
      }

      GameSoundClip clip = gameSound.getRandomClip();
      FMODSoundEmitter.Sound s = this.addSound(clip, 1.0F, null);
      return s == null ? 0L : s.getRef();
   }

   public void set3D(long soundRef, boolean is3D) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound s = this.toStart.get(i);
         if (s.getRef() == soundRef) {
            s.set3D(is3D);
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         if (s.getRef() == soundRef) {
            s.set3D(is3D);
         }
      }
   }

   public void tick() {
      if (!this.isEmpty()) {
         this.occlusion.update();

         for (int i = 0; i < this.parameters.size(); i++) {
            FMODParameter parameter = this.parameters.get(i);
            parameter.update();
         }
      }

      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound sound = this.toStart.get(i);
         this.instances.add(sound);
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound s = this.instances.get(i);
         boolean isStarting = this.toStart.contains(s);
         if (s.tick(isStarting)) {
            this.instances.remove(i--);
            s.release(s.clip.isStopImmediate());
         }
      }

      this.toStart.clear();

      for (int i = 0; i < this.stopped.size(); i++) {
         FMODSoundEmitter.Sound s = this.stopped.get(i);
         if (s.tickWhileStopped()) {
            this.stopped.remove(i--);
            s.release(s.clip.isStopImmediate());
         }
      }
   }

   public boolean hasSoundsToStart() {
      return !this.toStart.isEmpty();
   }

   public boolean isEmpty() {
      return this.toStart.isEmpty() && this.instances.isEmpty() && this.stopped.isEmpty();
   }

   public boolean isPlaying(long soundRef) {
      for (int i = 0; i < this.toStart.size(); i++) {
         if (this.toStart.get(i).getRef() == soundRef) {
            return true;
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         if (this.instances.get(i).getRef() == soundRef) {
            return true;
         }
      }

      return false;
   }

   public boolean isPlaying(String alias) {
      for (int i = 0; i < this.toStart.size(); i++) {
         if (alias.equals(this.toStart.get(i).name)) {
            return true;
         }
      }

      for (int i = 0; i < this.instances.size(); i++) {
         if (alias.equals(this.instances.get(i).name)) {
            return true;
         }
      }

      return false;
   }

   public boolean restart(long handle) {
      int index = this.findToStart(handle);
      if (index != -1) {
         return true;
      }

      index = this.findInstance(handle);
      return index != -1 && this.instances.get(index).restart();
   }

   public void setPlayRemoteEvents(boolean remote) {
      this.playRemoteEvents = remote;
   }

   private int findInstance(long soundRef) {
      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound sound = this.instances.get(i);
         if (sound.getRef() == soundRef) {
            return i;
         }
      }

      return -1;
   }

   private int findInstance(String name) {
      GameSound gameSound = GameSounds.getSound(name);
      if (gameSound == null) {
         return -1;
      }

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound sound = this.instances.get(i);
         if (gameSound.clips.contains(sound.clip)) {
            return i;
         }
      }

      return -1;
   }

   private int findToStart(long soundRef) {
      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound sound = this.toStart.get(i);
         if (sound.getRef() == soundRef) {
            return i;
         }
      }

      return -1;
   }

   private int findToStart(String name) {
      GameSound gameSound = GameSounds.getSound(name);
      if (gameSound == null) {
         return -1;
      }

      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound sound = this.toStart.get(i);
         if (gameSound.clips.contains(sound.clip)) {
            return i;
         }
      }

      return -1;
   }

   private int findStopped(long soundRef) {
      for (int i = 0; i < this.stopped.size(); i++) {
         FMODSoundEmitter.Sound sound = this.stopped.get(i);
         if (sound instanceof FMODSoundEmitter.EventSound eventSound && eventSound.eventInstanceStopped == soundRef) {
            return i;
         }
      }

      return -1;
   }

   private int countToStart(GameSound gameSound) {
      int count = 0;

      for (int i = 0; i < this.toStart.size(); i++) {
         FMODSoundEmitter.Sound sound = this.toStart.get(i);
         if (gameSound.clips.contains(sound.clip)) {
            count++;
         }
      }

      return count;
   }

   private int countInstances(GameSound gameSound) {
      int count = 0;

      for (int i = 0; i < this.instances.size(); i++) {
         FMODSoundEmitter.Sound sound = this.instances.get(i);
         if (gameSound.clips.contains(sound.clip)) {
            count++;
         }
      }

      return count;
   }

   public void addParameter(FMODParameter parameter) {
      this.parameters.add(parameter);
   }

   public void setParameterValue(long soundRef, FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription, float value) {
      if (soundRef != 0L && parameterDescription != null) {
         int index = this.findInstance(soundRef);
         if (index != -1) {
            FMODSoundEmitter.Sound sound = this.instances.get(index);
            sound.setParameterValue(parameterDescription, value);
         } else {
            index = this.findParameterValue(soundRef, parameterDescription);
            if (index != -1) {
               this.parameterValues.get(index).value = value;
            } else {
               index = this.findStopped(soundRef);
               if (index != -1) {
                  javafmod.FMOD_Studio_EventInstance_SetParameterByID(soundRef, parameterDescription.id, value, false);
               } else {
                  index = this.findToStart(soundRef);
                  if (index != -1) {
                     FMODSoundEmitter.ParameterValue parameterValue = (FMODSoundEmitter.ParameterValue)parameterValuePool.alloc();
                     parameterValue.eventInstance = soundRef;
                     parameterValue.parameterDescription = parameterDescription;
                     parameterValue.value = value;
                     this.parameterValues.add(parameterValue);
                  }
               }
            }
         }
      }
   }

   public void setParameterValueByName(long soundRef, String parameterName, float value) {
      FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription = FMODManager.instance.getParameterDescription(parameterName);
      this.setParameterValue(soundRef, parameterDescription, value);
   }

   public boolean isUsingParameter(long handle, String parameterName) {
      FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription = FMODManager.instance.getParameterDescription(parameterName);
      if (parameterDescription == null) {
         return false;
      }

      int index = this.findToStart(handle);
      if (index != -1) {
         FMODSoundEmitter.Sound sound = this.toStart.get(index);
         return sound.getEventDescription() != null && sound.clip.hasParameter(sound.remote, parameterDescription);
      }

      index = this.findInstance(handle);
      if (index == -1) {
         return false;
      }

      FMODSoundEmitter.Sound sound = this.instances.get(index);
      return sound.getEventDescription() != null && sound.clip.hasParameter(sound.remote, parameterDescription);
   }

   public void setTimelinePosition(long soundRef, String positionName) {
      if (soundRef != 0L) {
         int index = this.findToStart(soundRef);
         if (index != -1) {
            FMODSoundEmitter.Sound sound = this.toStart.get(index);
            sound.setTimelinePosition(positionName);
         }
      }
   }

   private int findParameterValue(long soundRef, FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription) {
      for (int i = 0; i < this.parameterValues.size(); i++) {
         FMODSoundEmitter.ParameterValue parameterValue = this.parameterValues.get(i);
         if (parameterValue.eventInstance == soundRef && parameterValue.parameterDescription == parameterDescription) {
            return i;
         }
      }

      return -1;
   }

   public void clearParameters() {
      this.occlusion.resetToDefault();
      this.parameters.clear();
      parameterValuePool.releaseAll(this.parameterValues);
      this.parameterValues.clear();
   }

   private void startEvent(long eventInstance, GameSoundClip clip, boolean remote) {
      parameterSet.clear();
      ArrayList<FMODParameter> myParameters = this.parameters;
      ArrayList<FMOD_STUDIO_PARAMETER_DESCRIPTION> eventParameters = clip.getEventDescription(remote).parameters;

      for (int i = 0; i < eventParameters.size(); i++) {
         FMOD_STUDIO_PARAMETER_DESCRIPTION eventParameter = eventParameters.get(i);
         int index = this.findParameterValue(eventInstance, eventParameter);
         if (index != -1) {
            FMODSoundEmitter.ParameterValue parameterValue = this.parameterValues.get(index);
            javafmod.FMOD_Studio_EventInstance_SetParameterByID(eventInstance, eventParameter.id, parameterValue.value, false);
            parameterSet.set(eventParameter.globalIndex, true);
         } else if (eventParameter == this.occlusion.getParameterDescription()) {
            this.occlusion.startEventInstance(eventInstance);
            parameterSet.set(eventParameter.globalIndex, true);
         } else {
            for (int j = 0; j < myParameters.size(); j++) {
               FMODParameter fmodParameter = myParameters.get(j);
               if (fmodParameter.getParameterDescription() == eventParameter) {
                  fmodParameter.startEventInstance(eventInstance);
                  parameterSet.set(eventParameter.globalIndex, true);
                  break;
               }
            }
         }
      }

      if (this.parameterUpdater != null) {
         this.parameterUpdater.startEvent(eventInstance, clip, remote, parameterSet);
      }
   }

   private void updateEvent(long eventInstance, GameSoundClip clip) {
      if (this.parameterUpdater != null) {
         this.parameterUpdater.updateEvent(eventInstance, clip);
      }
   }

   private void stopEvent(long eventInstance, GameSoundClip clip, boolean remote) {
      parameterSet.clear();
      ArrayList<FMODParameter> myParameters = this.parameters;
      ArrayList<FMOD_STUDIO_PARAMETER_DESCRIPTION> eventParameters = clip.getEventDescription(remote).parameters;

      for (int i = 0; i < eventParameters.size(); i++) {
         FMOD_STUDIO_PARAMETER_DESCRIPTION eventParameter = eventParameters.get(i);
         int index = this.findParameterValue(eventInstance, eventParameter);
         if (index != -1) {
            FMODSoundEmitter.ParameterValue parameterValue = this.parameterValues.remove(index);
            parameterValuePool.release(parameterValue);
            parameterSet.set(eventParameter.globalIndex, true);
         } else if (eventParameter == this.occlusion.getParameterDescription()) {
            this.occlusion.stopEventInstance(eventInstance);
            parameterSet.set(eventParameter.globalIndex, true);
         } else {
            for (int j = 0; j < myParameters.size(); j++) {
               FMODParameter fmodParameter = myParameters.get(j);
               if (fmodParameter.getParameterDescription() == eventParameter) {
                  fmodParameter.stopEventInstance(eventInstance);
                  parameterSet.set(eventParameter.globalIndex, true);
                  break;
               }
            }
         }
      }

      if (this.parameterUpdater != null) {
         this.parameterUpdater.stopEvent(eventInstance, clip, remote, parameterSet);
      }
   }

   private FMODSoundEmitter.EventSound allocEventSound() {
      return this.eventSoundPool.isEmpty() ? new FMODSoundEmitter.EventSound(this) : this.eventSoundPool.pop();
   }

   private void releaseEventSound(FMODSoundEmitter.EventSound sound) {
      assert !this.eventSoundPool.contains(sound);
      this.eventSoundPool.push(sound);
   }

   private FMODSoundEmitter.FileSound allocFileSound() {
      return this.fileSoundPool.isEmpty() ? new FMODSoundEmitter.FileSound(this) : this.fileSoundPool.pop();
   }

   private void releaseFileSound(FMODSoundEmitter.FileSound sound) {
      assert !this.fileSoundPool.contains(sound);
      this.fileSoundPool.push(sound);
   }

   private FMODSoundEmitter.Sound addSound(GameSoundClip clip, float volume, IsoObject parent) {
      if (clip == null) {
         DebugLog.log("null sound passed to SoundEmitter.playSoundImpl");
         return null;
      }

      if (clip.gameSound.maxInstancesPerEmitter > 0) {
         this.limitSound(clip.gameSound, clip.gameSound.maxInstancesPerEmitter - 1);
      }

      if (clip.event != null && !clip.event.isEmpty()) {
         if (clip.eventDescription == null) {
            return null;
         }

         FMOD_STUDIO_EVENT_DESCRIPTION eventDescription = clip.eventDescription;
         if (clip.eventDescriptionMp != null && this.shouldPlayRemoteEvents()) {
            eventDescription = clip.eventDescriptionMp;
         }

         long eventInstance = javafmod.FMOD_Studio_System_CreateEventInstance(eventDescription.address);
         if (eventInstance < 0L) {
            return null;
         }

         if (clip.hasMinDistance()) {
            javafmodJNI.FMOD_Studio_EventInstance_SetProperty(
               eventInstance, FMOD_STUDIO_EVENT_PROPERTY.FMOD_STUDIO_EVENT_PROPERTY_MINIMUM_DISTANCE.ordinal(), clip.getMinDistance()
            );
         }

         if (clip.hasMaxDistance()) {
            javafmodJNI.FMOD_Studio_EventInstance_SetProperty(
               eventInstance, FMOD_STUDIO_EVENT_PROPERTY.FMOD_STUDIO_EVENT_PROPERTY_MAXIMUM_DISTANCE.ordinal(), clip.getMaxDistance()
            );
         }

         FMODSoundEmitter.EventSound s = this.allocEventSound();
         s.clip = clip;
         s.remote = eventDescription == clip.eventDescriptionMp;
         s.name = clip.gameSound.getName();
         s.eventInstance = eventInstance;
         s.eventInstanceStopped = 0L;
         s.volume = volume;
         s.parent = parent;
         s.setVolume = 1.0F;
         s.setX = s.setY = s.setZ = 0.0F;
         this.toStart.add(s);
         this.pzoptMarkBusy(); // pzopt: emitterIdleSkip
         return s;
      } else if (clip.file != null && !clip.file.isEmpty()) {
         long sound = FMODManager.instance.loadSound(clip.file);
         if (sound == 0L) {
            return null;
         }

         long channel = javafmod.FMOD_System_PlaySound(sound, true);
         javafmod.FMOD_Channel_SetVolume(channel, 0.0F);
         javafmod.FMOD_Channel_SetPriority(channel, 9 - clip.priority);
         javafmod.FMOD_Channel_SetChannelGroup(channel, FMODManager.instance.channelGroupInGameNonBankSounds);
         if (clip.distanceMax == 0.0F || this.x == 0.0F && this.y == 0.0F) {
            javafmod.FMOD_Channel_SetMode(channel, 8L);
         }

         FMODSoundEmitter.FileSound s = this.allocFileSound();
         s.clip = clip;
         s.remote = false;
         s.name = clip.gameSound.getName();
         s.sound = sound;
         s.pitch = clip.pitch;
         s.channel = channel;
         s.parent = parent;
         s.volume = volume;
         s.setVolume = 1.0F;
         s.setX = s.setY = s.setZ = 0.0F;
         s.is3d = -1;
         s.ambient = false;
         this.toStart.add(s);
         this.pzoptMarkBusy(); // pzopt: emitterIdleSkip
         return s;
      } else {
         return null;
      }
   }

   // pzopt: emitterIdleSkip. A character's three emitters (vocals, footsteps, extra) have the character as parent; a
   // sound entering toStart marks it busy so IsoGameCharacter.updateEmitter ticks it again (it skips idle zombies).
   private void pzoptMarkBusy() {
      if (this.parent instanceof IsoGameCharacter chr) {
         chr.pzoptSoundBusy = true;
      }
   }

   private void sendStopSound(String soundName, boolean triggerCue) {
      if (GameClient.client && this.parent instanceof IsoMovingObject isoMovingObject) {
         GameClient.instance.StopSound(isoMovingObject, soundName, triggerCue);
      }
   }

   public static void update() {
      currentTimeMs = System.currentTimeMillis();
   }

   private boolean shouldPlayRemoteEvents() {
      return this.parent instanceof IsoPlayer isoPlayer && !isoPlayer.isLocalPlayer() ? true : this.playRemoteEvents;
   }

   private static final class EventSound extends FMODSoundEmitter.Sound {
      private long eventInstance;
      private long eventInstanceStopped;
      private boolean triggeredCue;
      private long checkTimeMs;

      public EventSound(FMODSoundEmitter emitter) {
         super(emitter);
      }

      @Override
      public long getRef() {
         return this.eventInstance;
      }

      @Override
      public void stop(boolean bReleaseEvent, boolean bImmediate) {
         if (this.eventInstance != 0L) {
            this.emitter.stopEvent(this.eventInstance, this.clip, this.remote);
            javafmod.FMOD_Studio_EventInstance_Stop(this.eventInstance, bImmediate);
            if (bReleaseEvent) {
               javafmod.FMOD_Studio_ReleaseEventInstance(this.eventInstance);
            }

            this.eventInstanceStopped = this.eventInstance;
            this.eventInstance = 0L;
         }
      }

      @Override
      public void set3D(boolean is3D) {
      }

      @Override
      public void release(boolean bImmediate) {
         this.stop(true, bImmediate);
         this.checkTimeMs = 0L;
         this.triggeredCue = false;
         this.emitter.releaseEventSound(this);
      }

      @Override
      public boolean tick(boolean isStarting) {
         if (!isStarting) {
            int state = javafmod.FMOD_Studio_GetPlaybackState(this.eventInstance);
            if (state == FMOD_STUDIO_PLAYBACK_STATE.FMOD_STUDIO_PLAYBACK_STOPPING.index) {
               return false;
            }

            if (state == FMOD_STUDIO_PLAYBACK_STATE.FMOD_STUDIO_PLAYBACK_STOPPED.index) {
               javafmod.FMOD_Studio_ReleaseEventInstance(this.eventInstance);
               this.emitter.stopEvent(this.eventInstance, this.clip, this.remote);
               this.eventInstance = 0L;
               return true;
            }

            if (this.triggeredCue
               && FMODSoundEmitter.currentTimeMs - this.checkTimeMs > 250L
               && state == FMOD_STUDIO_PLAYBACK_STATE.FMOD_STUDIO_PLAYBACK_SUSTAINING.index) {
               javafmodJNI.FMOD_Studio_EventInstance_KeyOff(this.eventInstance);
            }

            if (this.triggeredCue && this.getEventDescription().length > 0L && FMODSoundEmitter.currentTimeMs - this.checkTimeMs > 1500L) {
               long position = javafmodJNI.FMOD_Studio_GetTimelinePosition(this.eventInstance);
               if (position > this.getEventDescription().length + 1000L) {
                  javafmod.FMOD_Studio_EventInstance_Stop(this.eventInstance, false);
               }

               this.checkTimeMs = FMODSoundEmitter.currentTimeMs;
            }
         }

         boolean bPositionChanged = Float.compare(this.emitter.x, this.setX) != 0
            || Float.compare(this.emitter.y, this.setY) != 0
            || Float.compare(this.emitter.z, this.setZ) != 0;
         if (bPositionChanged) {
            this.setX = this.emitter.x;
            this.setY = this.emitter.y;
            this.setZ = this.emitter.z;
            javafmod.FMOD_Studio_EventInstance3D(this.eventInstance, this.emitter.x, this.emitter.y, this.emitter.z * 3.0F);
         }

         float volume = this.getVolume();
         if (Float.compare(volume, this.setVolume) != 0) {
            this.setVolume = volume;
            javafmod.FMOD_Studio_EventInstance_SetVolume(this.eventInstance, volume);
         }

         if (isStarting) {
            this.emitter.startEvent(this.eventInstance, this.clip, this.remote);
            javafmod.FMOD_Studio_StartEvent(this.eventInstance);
         } else {
            this.emitter.updateEvent(this.eventInstance, this.clip);
         }

         return false;
      }

      @Override
      public boolean tickWhileStopped() {
         int state = javafmod.FMOD_Studio_GetPlaybackState(this.eventInstanceStopped);
         if (state == FMOD_STUDIO_PLAYBACK_STATE.FMOD_STUDIO_PLAYBACK_STOPPING.index) {
            boolean var2 = true;
         }

         if (state == FMOD_STUDIO_PLAYBACK_STATE.FMOD_STUDIO_PLAYBACK_STOPPED.index) {
            javafmod.FMOD_Studio_ReleaseEventInstance(this.eventInstanceStopped);
            this.eventInstanceStopped = 0L;
            return true;
         } else {
            return false;
         }
      }

      @Override
      public void setParameterValue(FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription, float value) {
         if (this.eventInstance != 0L) {
            javafmod.FMOD_Studio_EventInstance_SetParameterByID(this.eventInstance, parameterDescription.id, value, false);
         }
      }

      @Override
      public void setTimelinePosition(String positionName) {
         if (this.eventInstance != 0L && this.clip != null && this.clip.event != null) {
            SoundTimelineScript script = ScriptManager.instance.getSoundTimeline(this.clip.event);
            if (script != null) {
               int position = script.getPosition(positionName);
               if (position != -1) {
                  javafmodJNI.FMOD_Studio_EventInstance_SetTimelinePosition(this.eventInstance, position);
               }
            }
         }
      }

      @Override
      public void triggerCue() {
         if (this.eventInstance != 0L) {
            if (this.clip.hasSustainPoints(this.remote)) {
               javafmodJNI.FMOD_Studio_EventInstance_KeyOff(this.eventInstance);
               this.triggeredCue = true;
               this.checkTimeMs = FMODSoundEmitter.currentTimeMs;
            }
         }
      }

      @Override
      public boolean isTriggeredCue() {
         return this.triggeredCue;
      }

      @Override
      public boolean restart() {
         if (this.eventInstance == 0L) {
            return false;
         }

         javafmodJNI.FMOD_Studio_StartEvent(this.eventInstance);
         return true;
      }
   }

   private static final class FileSound extends FMODSoundEmitter.Sound {
      private long sound;
      private long channel;
      private byte is3d = -1;
      boolean ambient;
      private float lx;
      private float ly;
      private float lz;

      private FileSound(FMODSoundEmitter emitter) {
         super(emitter);
      }

      @Override
      public long getRef() {
         return this.channel;
      }

      @Override
      public void stop(boolean bReleaseEvent, boolean bImmediate) {
         if (this.channel != 0L) {
            javafmod.FMOD_Channel_Stop(this.channel);
            this.sound = 0L;
            this.channel = 0L;
         }
      }

      @Override
      public void set3D(boolean is3D) {
         if (this.is3d != (byte)(is3D ? 1 : 0)) {
            javafmod.FMOD_Channel_SetMode(this.channel, is3D ? 16L : 8L);
            if (is3D) {
               javafmod.FMOD_Channel_Set3DAttributes(this.channel, this.emitter.x, this.emitter.y, this.emitter.z * 3.0F, 0.0F, 0.0F, 0.0F);
            }

            this.is3d = (byte)(is3D ? 1 : 0);
         }
      }

      @Override
      public void release(boolean bImmediate) {
         this.stop(true, bImmediate);
         this.emitter.releaseFileSound(this);
      }

      @Override
      public boolean tick(boolean isStarting) {
         if (isStarting && this.clip.gameSound.isLooped()) {
            javafmod.FMOD_Channel_SetMode(this.channel, 2L);
         }

         float range = this.clip.distanceMin;
         if (!isStarting && !javafmod.FMOD_Channel_IsPlaying(this.channel)) {
            return true;
         }

         float x = this.emitter.x;
         float y = this.emitter.y;
         float z = this.emitter.z;
         if (!this.clip.gameSound.is3d || x == 0.0F && y == 0.0F) {
            if ((x != 0.0F || y != 0.0F) && (isStarting || x != this.lx || y != this.ly) && this.is3d == 1) {
               javafmod.FMOD_Channel_Set3DAttributes(this.channel, x, y, z * 3.0F, 0.0F, 0.0F, 0.0F);
            }

            javafmod.FMOD_Channel_SetVolume(this.channel, this.getVolume());
            javafmod.FMOD_Channel_SetPitch(this.channel, this.pitch);
            if (isStarting) {
               javafmod.FMOD_Channel_SetPaused(this.channel, false);
            }

            return false;
         } else {
            this.lx = x;
            this.ly = y;
            this.lz = z;
            javafmod.FMOD_Channel_Set3DAttributes(this.channel, x, y, z * 3.0F, x - this.lx, y - this.ly, z * 3.0F - this.lz * 3.0F);
            float minDistFromListener = Float.MAX_VALUE;

            for (int i = 0; i < IsoPlayer.numPlayers; i++) {
               IsoPlayer player = IsoPlayer.players[i];
               if (player != null && !player.hasTrait(CharacterTrait.DEAF)) {
                  float dist = IsoUtils.DistanceTo(x, y, z * 3.0F, player.getX(), player.getY(), player.getZ() * 3.0F);
                  minDistFromListener = PZMath.min(minDistFromListener, dist);
               }
            }

            float soundSize = 2.0F;
            float level = minDistFromListener >= 2.0F ? 1.0F : 1.0F - (2.0F - minDistFromListener) / 2.0F;
            javafmodJNI.FMOD_Channel_Set3DLevel(this.channel, level);
            if (IsoPlayer.numPlayers > 1) {
               if (isStarting) {
                  javafmod.FMOD_System_SetReverbDefault(0, 0);
                  javafmod.FMOD_Channel_Set3DMinMaxDistance(this.channel, this.clip.distanceMin, this.clip.distanceMax);
                  javafmod.FMOD_Channel_Set3DOcclusion(this.channel, 0.0F, 0.0F);
               }

               javafmod.FMOD_Channel_SetVolume(this.channel, this.getVolume());
               if (isStarting) {
                  javafmod.FMOD_Channel_SetPaused(this.channel, false);
               }

               javafmod.FMOD_Channel_SetReverbProperties(this.channel, 0, 0.0F);
               javafmod.FMOD_Channel_SetReverbProperties(this.channel, 1, 0.0F);
               javafmod.FMOD_System_SetReverbDefault(1, 0);
               javafmod.FMOD_Channel_Set3DOcclusion(this.channel, 0.0F, 0.0F);
               return false;
            } else {
               minDistFromListener = this.clip.reverbMaxRange;
               soundSize = IsoUtils.DistanceManhatten(x, y, IsoPlayer.getInstance().getX(), IsoPlayer.getInstance().getY(), z, IsoPlayer.getInstance().getZ())
                  / minDistFromListener;
               IsoGridSquare current = IsoPlayer.getInstance().getCurrentSquare();
               if (current == null) {
                  javafmod.FMOD_Channel_Set3DMinMaxDistance(this.channel, range, this.clip.distanceMax);
                  javafmod.FMOD_Channel_SetVolume(this.channel, this.getVolume());
                  if (isStarting) {
                     javafmod.FMOD_Channel_SetPaused(this.channel, false);
                  }

                  return false;
               } else {
                  if (current.getRoom() == null) {
                     if (!this.ambient) {
                        soundSize += IsoPlayer.getInstance().numNearbyBuildingsRooms / 32.0F;
                     }

                     if (!this.ambient) {
                        soundSize += 0.08F;
                     }
                  } else {
                     float numTiles = current.getRoom().squares.size();
                     if (!this.ambient) {
                        soundSize += numTiles / 500.0F;
                     }
                  }

                  if (soundSize > 1.0F) {
                     soundSize = 1.0F;
                  }

                  soundSize *= soundSize;
                  soundSize *= soundSize;
                  soundSize *= this.clip.reverbFactor;
                  soundSize *= 10.0F;
                  if (IsoPlayer.getInstance().getCurrentSquare().getRoom() == null && soundSize < 0.1F) {
                     soundSize = 0.1F;
                  }

                  int presetOffA;
                  int presetOffB;
                  int preset;
                  if (!this.ambient) {
                     if (current.getRoom() != null) {
                        preset = 0;
                        presetOffA = 1;
                        presetOffB = 2;
                     } else {
                        preset = 2;
                        presetOffA = 0;
                        presetOffB = 1;
                     }
                  } else {
                     preset = 2;
                     presetOffA = 0;
                     presetOffB = 1;
                  }

                  IsoGridSquare sq = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
                  if (sq != null && sq.getZone() != null && (sq.getZone().getType().equals("Forest") || sq.getZone().getType().equals("DeepForest"))) {
                     preset = 1;
                     presetOffA = 0;
                     presetOffB = 2;
                  }

                  javafmod.FMOD_Channel_SetReverbProperties(this.channel, preset, 0.0F);
                  javafmod.FMOD_Channel_SetReverbProperties(this.channel, presetOffA, 0.0F);
                  javafmod.FMOD_Channel_SetReverbProperties(this.channel, presetOffB, 0.0F);
                  javafmod.FMOD_Channel_Set3DMinMaxDistance(this.channel, range, this.clip.distanceMax);
                  IsoGridSquare sqx = IsoWorld.instance.currentCell.getGridSquare(x, y, z);
                  float directOcclude = 0.0F;
                  float reverbOcclude = 0.0F;
                  if (sqx != null) {
                     if (this.emitter.parent instanceof IsoWindow || this.emitter.parent instanceof IsoDoor) {
                        IsoRoom r = IsoPlayer.getInstance().getCurrentSquare().getRoom();
                        if (r != this.emitter.parent.square.getRoom()) {
                           if (r != null && r.getBuilding() == this.emitter.parent.square.getBuilding()) {
                              directOcclude = 0.33F;
                              reverbOcclude = 0.33F;
                           } else {
                              IsoGridSquare doorother = null;
                              if (this.emitter.parent instanceof IsoDoor door) {
                                 if (door.north) {
                                    doorother = IsoWorld.instance.currentCell.getGridSquare(door.getX(), door.getY() - 1.0F, door.getZ());
                                 } else {
                                    doorother = IsoWorld.instance.currentCell.getGridSquare(door.getX() - 1.0F, door.getY(), door.getZ());
                                 }
                              } else {
                                 IsoWindow door = (IsoWindow)this.emitter.parent;
                                 if (door.isNorth()) {
                                    doorother = IsoWorld.instance.currentCell.getGridSquare(door.getX(), door.getY() - 1.0F, door.getZ());
                                 } else {
                                    doorother = IsoWorld.instance.currentCell.getGridSquare(door.getX() - 1.0F, door.getY(), door.getZ());
                                 }
                              }

                              if (doorother != null) {
                                 IsoRoom var34 = IsoPlayer.getInstance().getCurrentSquare().getRoom();
                                 if (var34 != null || doorother.getRoom() == null) {
                                    if (var34 == null || doorother.getRoom() == null || var34.building != doorother.getBuilding()) {
                                       directOcclude = 0.33F;
                                       reverbOcclude = 0.33F;
                                    } else if (var34 != doorother.getRoom()) {
                                       if (var34.def.level == doorother.getZ()) {
                                          directOcclude = 0.33F;
                                          reverbOcclude = 0.33F;
                                       } else {
                                          directOcclude = 0.6F;
                                          reverbOcclude = 0.6F;
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     } else if (sqx.getRoom() != null) {
                        IsoRoom r = IsoPlayer.getInstance().getCurrentSquare().getRoom();
                        if (r == null) {
                           directOcclude = 0.33F;
                           reverbOcclude = 0.23F;
                        } else if (r != sqx.getRoom()) {
                           directOcclude = 0.24F;
                           reverbOcclude = 0.24F;
                        }

                        if (r != null && sqx.getRoom().getBuilding() != r.getBuilding()) {
                           directOcclude = 1.0F;
                           reverbOcclude = 0.8F;
                        }

                        if (r != null && sqx.getRoom().def.level != IsoPlayer.getInstance().getZi()) {
                           directOcclude = 0.6F;
                           reverbOcclude = 0.6F;
                        }
                     } else {
                        IsoRoom r = IsoPlayer.getInstance().getCurrentSquare().getRoom();
                        if (r != null) {
                           directOcclude = 0.79F;
                           reverbOcclude = 0.59F;
                        }
                     }

                     if (!sqx.isCouldSee(IsoPlayer.getPlayerIndex()) && sqx != IsoPlayer.getInstance().getCurrentSquare()) {
                        directOcclude += 0.4F;
                     }
                  } else {
                     if (IsoWorld.instance.metaGrid.getRoomAt((int)Math.floor(x), PZMath.fastfloor(y), (int)Math.floor(z)) != null) {
                        directOcclude = 1.0F;
                        reverbOcclude = 1.0F;
                     }

                     IsoRoom r = IsoPlayer.getInstance().getCurrentSquare().getRoom();
                     if (r != null) {
                        directOcclude += 0.94F;
                     } else {
                        directOcclude += 0.6F;
                     }
                  }

                  if (sqx != null && IsoPlayer.getInstance().getZi() != sqx.getZ()) {
                     directOcclude *= 1.3F;
                  }

                  if (directOcclude > 0.9F) {
                     directOcclude = 0.9F;
                  }

                  if (reverbOcclude > 0.9F) {
                     reverbOcclude = 0.9F;
                  }

                  if (this.emitter.emitterType == EmitterType.Footstep
                     && z > IsoPlayer.getInstance().getZ()
                     && sqx.getBuilding() == IsoPlayer.getInstance().getBuilding()) {
                     directOcclude = 0.0F;
                     reverbOcclude = 0.0F;
                  }

                  if ("HouseAlarm".equals(this.name)) {
                     directOcclude = 0.0F;
                     reverbOcclude = 0.0F;
                  }

                  javafmod.FMOD_Channel_Set3DOcclusion(this.channel, directOcclude, reverbOcclude);
                  javafmod.FMOD_Channel_SetVolume(this.channel, this.getVolume());
                  javafmod.FMOD_Channel_SetPitch(this.channel, this.pitch);
                  if (isStarting) {
                     javafmod.FMOD_Channel_SetPaused(this.channel, false);
                  }

                  this.lx = x;
                  this.ly = y;
                  this.lz = z;
                  return false;
               }
            }
         }
      }

      @Override
      public boolean tickWhileStopped() {
         return true;
      }

      @Override
      void setParameterValue(FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription, float value) {
      }

      @Override
      void setTimelinePosition(String positionName) {
      }

      @Override
      void triggerCue() {
      }

      @Override
      boolean isTriggeredCue() {
         return false;
      }

      @Override
      boolean restart() {
         return false;
      }
   }

   private static final class ParameterValue {
      private long eventInstance;
      private FMOD_STUDIO_PARAMETER_DESCRIPTION parameterDescription;
      private float value;
   }

   private abstract static class Sound {
      public final FMODSoundEmitter emitter;
      public GameSoundClip clip;
      public boolean remote;
      public String name;
      public float volume = 1.0F;
      public float pitch = 1.0F;
      public IsoObject parent;
      public float setVolume = 1.0F;
      public float setX;
      public float setY;
      public float setZ;

      public Sound(FMODSoundEmitter emitter) {
         this.emitter = emitter;
      }

      abstract long getRef();

      abstract void stop(boolean var1, boolean var2);

      abstract void set3D(boolean var1);

      abstract void release(boolean var1);

      abstract boolean tick(boolean var1);

      abstract boolean tickWhileStopped();

      public float getVolume() {
         this.clip = this.clip.checkReloaded();
         return this.volume * this.clip.getEffectiveVolume();
      }

      abstract void setParameterValue(FMOD_STUDIO_PARAMETER_DESCRIPTION var1, float var2);

      abstract void setTimelinePosition(String var1);

      abstract void triggerCue();

      abstract boolean isTriggeredCue();

      abstract boolean restart();

      public FMOD_STUDIO_EVENT_DESCRIPTION getEventDescription() {
         return this.clip.getEventDescription(this.remote);
      }
   }
}
