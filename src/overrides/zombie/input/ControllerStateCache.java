package zombie.input;

import org.lwjglx.input.Controller;
import org.lwjglx.input.Controllers;

public class ControllerStateCache {
   private final Object lock = "ControllerStateCache Lock";
   private int stateIndexUsing;
   private int stateIndexPolling = 1;
   private final ControllerState[] states = new ControllerState[]{new ControllerState(), new ControllerState()};
   private final Controller[] controllers = new Controller[16];

   public void poll() {
      synchronized (this.lock) {
         if (!Controllers.isCreated()) { // pzopt: decompiler fix, early returns inside the lock as in the jar
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         ControllerState statePolling = this.getStatePolling(); // pzopt: decompiler fix
         if (statePolling.wasPolled()) { // pzopt: decompiler fix
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         for (int i = 0; i < 16; i++) { // pzopt: decompiler fix
            this.controllers[i] = Controllers.getController(i); // pzopt: decompiler fix
         } // pzopt: decompiler fix

         statePolling.poll(); // pzopt: decompiler fix
      }
   }

   public void pzoptRepoll() { // pzopt: late input latch (pzopt.InputLatch): read the pads again although this state was polled
      synchronized (this.lock) { // pzopt
         if (Controllers.isCreated()) { // pzopt
            for (int i = 0; i < 16; i++) { // pzopt
               this.controllers[i] = Controllers.getController(i); // pzopt
            } // pzopt
            this.getStatePolling().poll(); // pzopt
         } // pzopt
      } // pzopt
   } // pzopt

   public void swap() {
      synchronized (this.lock) {
         ControllerState prevStatePolling = this.getStatePolling();
         if (!prevStatePolling.wasPolled()) { // pzopt: decompiler fix, early return inside the lock as in the jar
            return; // pzopt: decompiler fix
         } // pzopt: decompiler fix

         this.stateIndexUsing = this.stateIndexPolling; // pzopt: decompiler fix
         this.stateIndexPolling = this.stateIndexPolling == 1 ? 0 : 1; // pzopt: decompiler fix
         ControllerState stateActive = this.getState(); // pzopt: decompiler fix
         stateActive.onStateActive(this); // pzopt: decompiler fix
         ControllerState statePolling = this.getStatePolling(); // pzopt: decompiler fix
         statePolling.onStatePolling(this); // pzopt: decompiler fix
      }
   }

   public ControllerState getState() {
      synchronized (this.lock) {
         return this.states[this.stateIndexUsing];
      }
   }

   private ControllerState getStatePolling() {
      synchronized (this.lock) {
         return this.states[this.stateIndexPolling];
      }
   }

   public void quit() {
      this.states[0].quit();
      this.states[1].quit();
   }

   public Controller getController(int index) {
      synchronized (this.lock) {
         return this.controllers[index];
      }
   }
}
