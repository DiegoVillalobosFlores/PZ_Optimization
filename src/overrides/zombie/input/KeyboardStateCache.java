package zombie.input;

public final class KeyboardStateCache {
   private final Object lock = "KeyboardStateCache Lock";
   private int stateIndexUsing;
   private int stateIndexPolling = 1;
   private final KeyboardState[] states = new KeyboardState[]{new KeyboardState(), new KeyboardState()};

   public void poll() {
      synchronized (this.lock) {
         KeyboardState statePolling = this.getStatePolling();
         if (statePolling.wasPolled()) { // pzopt: decompiler fix, the jar returns from inside the lock (an extra monitorexit)
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         statePolling.poll(); // pzopt: decompiler fix
      }
   }

   public void pzoptRepoll() { // pzopt: late input latch (pzopt.InputLatch): poll again although this state was polled
      synchronized (this.lock) { // pzopt
         KeyboardState statePolling = this.getStatePolling(); // pzopt
         if (!statePolling.wasPolled()) { // pzopt
            statePolling.poll(); // pzopt
         } else { // pzopt
            statePolling.pzoptRepoll(this.getState()); // pzopt
         } // pzopt
      } // pzopt
   } // pzopt

   public void swap() {
      synchronized (this.lock) {
         if (!this.getStatePolling().wasPolled()) { // pzopt: decompiler fix, early return inside the lock as in the jar
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         this.stateIndexUsing = this.stateIndexPolling; // pzopt: decompiler fix
         this.stateIndexPolling = this.stateIndexPolling == 1 ? 0 : 1; // pzopt: decompiler fix
         this.getStatePolling().set(this.getState()); // pzopt: decompiler fix
         this.getStatePolling().reset(); // pzopt: decompiler fix
      }
   }

   public KeyboardState getState() {
      synchronized (this.lock) {
         return this.states[this.stateIndexUsing];
      }
   }

   public KeyboardState getStatePolling() {
      synchronized (this.lock) {
         return this.states[this.stateIndexPolling];
      }
   }
}
