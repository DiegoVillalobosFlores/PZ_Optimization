package pzopt;

import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;

/**
 * animatorParallel: one character's anim events of one update on a frame worker, kept in order for the game thread,
 * plus the tracks whose release waits for them (an event can name a track that its own update just removed). One per
 * character, reused every frame; only the worker updating that character writes it during the batch, only the game
 * thread reads it after the join.
 */
public final class AnimCapture {
   AnimLayer[] layers = new AnimLayer[4];
   AnimationTrack[] tracks = new AnimationTrack[4];
   AnimEvent[] events = new AnimEvent[4];
   int count;
   AnimationTrack[] releases = new AnimationTrack[4];
   int releaseCount;

   /** Armed on the game thread: this character's animator and model update run on a worker this frame. */
   public boolean armed;
   /** Set by the worker: the character must finish on the game thread (events of the animator step, an impure read, a failure). */
   public boolean serial;
   /** Set last by the worker task (animatorPipeline: the game thread finishes the character once it sees it). */
   public volatile boolean done;
   public boolean impure;
   /** Set by the worker: the animator left a ragdoll track, whose start and step must run on the game thread. */
   public boolean ragdoll;
   public Throwable failure;
   /** The animation time step of this frame, computed on the game thread. */
   public float deltaT;

   public void arm(float deltaT) {
      this.armed = true;
      this.done = false;
      this.serial = false;
      this.impure = false;
      this.ragdoll = false;
      this.failure = null;
      this.count = 0;
      this.releaseCount = 0;
      this.deltaT = deltaT;
   }

   public int count() {
      return this.count;
   }

   public AnimLayer layer(int i) {
      return this.layers[i];
   }

   public AnimationTrack track(int i) {
      return this.tracks[i];
   }

   public AnimEvent event(int i) {
      return this.events[i];
   }

   public void add(AnimLayer layer, AnimationTrack track, AnimEvent event) {
      if (this.count == this.events.length) {
         int n = this.count * 2;
         this.layers = java.util.Arrays.copyOf(this.layers, n);
         this.tracks = java.util.Arrays.copyOf(this.tracks, n);
         this.events = java.util.Arrays.copyOf(this.events, n);
      }
      this.layers[this.count] = layer;
      this.tracks[this.count] = track;
      this.events[this.count] = event;
      this.count++;
   }

   void deferRelease(AnimationTrack track) {
      if (this.releaseCount == this.releases.length) {
         this.releases = java.util.Arrays.copyOf(this.releases, this.releaseCount * 2);
      }
      this.releases[this.releaseCount++] = track;
   }

   /** Game thread, after the events were dispatched: release the held tracks and forget the frame. */
   public void finish() {
      for (int i = 0; i < this.releaseCount; i++) {
         AnimationTrack t = this.releases[i];
         this.releases[i] = null;
         t.release();
      }
      this.releaseCount = 0;
      java.util.Arrays.fill(this.layers, 0, this.count, null);
      java.util.Arrays.fill(this.tracks, 0, this.count, null);
      java.util.Arrays.fill(this.events, 0, this.count, null);
      this.count = 0;
      this.armed = false;
      this.failure = null;
   }
}
