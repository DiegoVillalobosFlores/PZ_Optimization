package zombie.input;

public final class MouseStateCache {
   private final Object lock = "MouseStateCache Lock";
   private int stateIndexUsing;
   private int stateIndexPolling = 1;
   private final MouseState[] states = new MouseState[]{new MouseState(), new MouseState()};

   public void poll() {
      synchronized (this.lock) {
         MouseState statePolling = this.getStatePolling();
         if (statePolling.wasPolled()) { // pzopt: decompiler fix, the jar returns from inside the lock (an extra monitorexit)
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         statePolling.poll(); // pzopt: decompiler fix
      }
   }

   public void pzoptRepoll() { // pzopt: late input latch (pzopt.InputLatch): poll again although this state was polled
      synchronized (this.lock) { // pzopt
         MouseState statePolling = this.getStatePolling(); // pzopt
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

   public MouseState getState() {
      synchronized (this.lock) {
         return this.states[this.stateIndexUsing];
      }
   }

   private MouseState getStatePolling() {
      synchronized (this.lock) {
         return this.states[this.stateIndexPolling];
      }
   }
}
