package pzopt;

import java.util.ArrayList;
import java.util.Locale;
import zombie.characters.IsoPlayer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.objects.IsoLightSwitch;

/**
 * Harness scene {@code room_light=auto} (2026-09-26, PR #26: at night, switching off the player's room light made the
 * other rooms' lamps over-expose, because the HDR light-map reference and the night key were shared by the whole view).
 * At the route start: the nearest ground-floor building with the most rooms with a light switch (2+, capped at 4, then nearest), grid power
 * on and every switch within 50 tiles on (Scene.lightsOnAround), the player teleported to a free square of its biggest
 * switched room and held there (no route teleports). Then, seconds after the route start: HDR dump {@code on} at
 * {@code room_light_dump} (6), the room's switches off 1 s later, dump {@code off} 3 s after that, the switches on again,
 * dump {@code on2} 3 s after that, done 1 s later. {@code room_light_face} (degrees, default 45 = SE) sets the facing.
 */
public final class RoomLightRig {
   private RoomLightRig() {
   }

   private static boolean on, started, finished;
   private static RoomDef room;
   private static final ArrayList<IsoLightSwitch> switches = new ArrayList<>();
   private static long startNs;
   private static int step;
   private static float dumpAt = 6F, face = 45F;
   private static int holdX, holdY;

   static void init() {
      on = "auto".equals(HarnessFlags.get("room_light", "").trim());
      dumpAt = Float.parseFloat(HarnessFlags.get("room_light_dump", "6").trim());
      face = Float.parseFloat(HarnessFlags.get("room_light_face", "45").trim());
   }

   public static boolean active() {
      return on;
   }

   public static boolean done() {
      return finished;
   }

   /** Route start (game thread): pick the room, light the house, put the player in the room. */
   static void routeStart(IsoPlayer p) {
      if (!on || started) return;
      started = true;
      startNs = System.nanoTime();
      IsoCell cell = IsoWorld.instance.currentCell;
      // every light switch on the loaded ground floor within room_light_radius, grouped by building
      int radius = Integer.parseInt(HarnessFlags.get("room_light_radius", "120").trim());
      java.util.HashMap<BuildingDef, java.util.HashSet<RoomDef>> byBuilding = new java.util.HashMap<>();
      int px = p.getXi(), py = p.getYi();
      for (int y = py - radius; y <= py + radius; y++) {
         for (int x = px - radius; x <= px + radius; x++) {
            IsoGridSquare s = cell.getGridSquare(x, y, 0);
            if (s == null || s.getRoom() == null) continue;
            for (int i = 0; i < s.getObjects().size(); i++) {
               if (s.getObjects().get(i) instanceof IsoLightSwitch) {
                  RoomDef r = s.getRoom().getRoomDef();
                  byBuilding.computeIfAbsent(r.getBuilding(), k -> new java.util.HashSet<>()).add(r);
               }
            }
         }
      }
      RoomDef best = null;
      int bestRooms = 0;
      float bestD = Float.MAX_VALUE;
      for (java.util.Map.Entry<BuildingDef, java.util.HashSet<RoomDef>> e : byBuilding.entrySet()) {
         BuildingDef b = e.getKey();
         int n = e.getValue().size();
         float bd = (float)Math.hypot((b.getX() + b.getX2()) / 2F - px, (b.getY() + b.getY2()) / 2F - py);
         RoomDef biggest = null;
         for (RoomDef r : e.getValue()) if (biggest == null || r.getArea() > biggest.getArea()) biggest = r;
         if (n >= 2 && biggest.getArea() >= 9 && (Math.min(n, 4) > Math.min(bestRooms, 4) || Math.min(n, 4) == Math.min(bestRooms, 4) && bd < bestD)) {
            best = biggest;
            bestRooms = n;
            bestD = bd;
         }
      }
      Log.info("harness: room_light: " + byBuilding.size() + " buildings with switched ground-floor rooms within " + radius + " tiles");
      if (best == null) {
         Log.warn("harness: room_light: no ground-floor building with 2 switched rooms within " + radius + " tiles; nothing to do");
         finished = true;
         return;
      }
      room = best;
      switches.addAll(switchesIn(cell, room));
      Scene.lightsOnAround((room.getX() + room.getX2()) / 2, (room.getY() + room.getY2()) / 2);
      IsoGridSquare sq = null;
      float cx = (room.getX() + room.getX2()) / 2F, cy = (room.getY() + room.getY2()) / 2F, sd = Float.MAX_VALUE;
      for (int y = room.getY(); y < room.getY2(); y++) {
         for (int x = room.getX(); x < room.getX2(); x++) {
            IsoGridSquare s = cell.getGridSquare(x, y, 0);
            if (s == null || !room.contains(x, y) || !s.isFree(false)) continue;
            float d = (float)Math.hypot(x + 0.5F - cx, y + 0.5F - cy);
            if (d < sd) {
               sd = d;
               sq = s;
            }
         }
      }
      if (sq == null) {
         Log.warn("harness: room_light: no free square in " + room.name);
         finished = true;
         return;
      }
      holdX = sq.getX();
      holdY = sq.getY();
      p.teleportTo(holdX, holdY, 0);
      p.setDirectionAngle(face);
      int rooms = 0;
      for (RoomDef r : room.getBuilding().getRooms()) if (r.level == 0 && !switchesIn(cell, r).isEmpty()) rooms++;
      Log.info(String.format(Locale.ROOT, "harness: room_light: %s at %d,%d-%d,%d (%d switches, %d switched ground-floor rooms in the building), player at %d,%d facing %.0f",
            room.name, room.getX(), room.getY(), room.getX2(), room.getY2(), switches.size(), rooms, holdX, holdY, face));
   }

   private static ArrayList<IsoLightSwitch> switchesIn(IsoCell cell, RoomDef r) {
      ArrayList<IsoLightSwitch> out = new ArrayList<>();
      for (RoomDef.RoomRect rr : r.getRects()) {
         for (int y = rr.y; y < rr.y + rr.h; y++) {
            for (int x = rr.x; x < rr.x + rr.w; x++) {
               IsoGridSquare s = cell.getGridSquare(x, y, r.level);
               if (s == null) continue;
               for (int i = 0; i < s.getObjects().size(); i++) {
                  if (s.getObjects().get(i) instanceof IsoLightSwitch ls) out.add(ls);
               }
            }
         }
      }
      return out;
   }

   /** Per frame (game thread): keep the player still and play the timeline. */
   static void tick(IsoPlayer p, long nowNs) {
      if (!on || !started || finished) return;
      if (p.getXi() != holdX || p.getYi() != holdY) p.teleportTo(holdX, holdY, 0);
      p.setDirectionAngle(face);
      float t = (nowNs - startNs) / 1e9F;
      float[] at = {dumpAt, dumpAt + 1F, dumpAt + 4F, dumpAt + 5F, dumpAt + 8F, dumpAt + 9F};
      if (step >= at.length || t < at[step]) return;
      switch (step) {
         case 0 -> dump("on");
         case 1 -> setRoom(false);
         case 2 -> dump("off");
         case 3 -> setRoom(true);
         case 4 -> dump("on2");
         default -> finished = true;
      }
      step++;
   }

   private static void dump(String tag) {
      Hdr.requestDump(tag);
      Stats.mark("roomlight-" + tag);
      Log.info("harness: room_light: HDR dump " + tag);
   }

   private static void setRoom(boolean lit) {
      for (IsoLightSwitch ls : switches) ls.setActive(lit, false, true);
      Stats.mark(lit ? "roomlight-on" : "roomlight-off");
      Log.info("harness: room_light: " + switches.size() + " switch(es) of " + room.name + (lit ? " on" : " off"));
   }
}
