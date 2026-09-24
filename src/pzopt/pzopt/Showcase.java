package pzopt;

import java.util.ArrayList;
import zombie.characters.CharacterStat;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.vehicles.BaseVehicle;

/**
 * Harness-only cinematic scene (2026-09-24, the HDR horde video): {@code showcase=horde} in the flag file. The player
 * starts at {@code start=} (the harness teleports there at world-ready; invisible, god mode, the area's own zombies kept
 * away from then on) and, at the route start, becomes visible with an M16 ({@code gun}), {@code mags} spare magazines and
 * a lit angle-head flashlight on an ALICE webbing (attached lights emit like held ones). A burning horde of {@code horde}
 * fast shamblers spawns {@code horde_gap} tiles behind them (the {@code horde_dir} side of the pier) and chases; the player
 * runs (the game's own pathfinding, forced run) to the pier spot {@code pier=x,y}, where an ambulance waits behind with its
 * headlights and lightbar on, turns to face the horde while a line of fires lights up across its way, and fires every
 * {@code fire_ms} through the game's own input (right mouse held to aim, left pulled, the aim point on the nearest zombie:
 * zombie.input.Mouse override), reloading from the spare magazines with the game's automatic reload when a magazine is
 * empty. {@code power=off} keeps the grid power (street lamps) off, {@code darkness=pitch} forces the sandbox's pitch-black
 * night. A state line every 2 s ({@code harness: showcase}).
 *
 * <p>With {@code director=jev} the character's actions are chosen outside the game: every 0.3 s the scene's facts go to
 * {@code Zomboid/pzopt-showcase-state.json}; harness/showcase-director.py asks TypeSafe's Jev which action fits, and writes
 * {@code <seq> <action>} to {@code Zomboid/pzopt-showcase-cmd.txt} (run_to_pier, face_horde, light_fire_line, shoot,
 * reload, hold), which the rig carries out until the next one. Movement is input like the aim: the Forward / Backward /
 * Left / Right keys toward the pier plus Run, reported held by the zombie.input.GameKeyboard override.
 */
public final class Showcase {
   private Showcase() {
   }

   /** the game's mouse position while the showcase aims (zombie.input.Mouse override reads it) */
   public static volatile boolean aimOverride;
   public static volatile int aimXA, aimYA;
   /** zombie.input.Mouse.update holds the right button (aim) and sets the left one (fire) from these */
   public static volatile boolean holdButtons, fireDown;

   private enum Phase { WAIT, RUN, TURN, FIGHT }

   /** key codes the zombie.input.GameKeyboard override reports as held (the director's movement) */
   private static final boolean[] heldKeys = new boolean[512];
   private static boolean director, pitchBlack;
   private static String command = "hold";
   private static int commandSeq = -1, commands;
   private static long lastStateNs, lastCmdCheckNs;
   private static java.io.File stateFile, cmdFile;

   public static boolean keyHeld(int key) {
      return key >= 0 && key < heldKeys.length && heldKeys[key];
   }

   private static void releaseKeys() {
      java.util.Arrays.fill(heldKeys, false);
   }

   private static void holdKey(String binding) {
      int k = zombie.core.Core.getInstance().getKey(binding);
      if (k > 0 && k < heldKeys.length) heldKeys[k] = true;
   }

   private static boolean on, powerOff, burn = true, fireLine = true, noAttack;
   private static int horde, hordeGap, hordeFires, lightbar, fireMs, mags;
   private static float pierX, pierY;
   private static String gunType, ambulanceType, hordeDir;
   private static HandWeapon gun;
   private static InventoryItem angleLight;
   private static BaseVehicle ambulance;
   private static final ArrayList<IsoZombie> zombies = new ArrayList<>();
   private static final java.util.Set<IsoZombie> hordeSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
   private static final ArrayList<IsoGridSquare> fireSquares = new ArrayList<>();
   private static float dirX, dirY;
   private static Phase phase = Phase.WAIT;
   private static long startNs, phaseNs, lastLogNs, lastClearNs, lastIgniteNs;
   private static float turnFrom, turnTo;
   private static int shots, reloads, strangersRemoved, reattached, lastAmmo = -1;
   private static boolean started, firesLit;
   private static String lastPath = "-";
   private static long lastTickNs;

   static void apply() {
      on = "horde".equalsIgnoreCase(HarnessFlags.get("showcase", "").trim());
      if (!on) {
         return;
      }
      horde = Integer.parseInt(HarnessFlags.get("horde", "80").trim());
      hordeGap = Integer.parseInt(HarnessFlags.get("horde_gap", "12").trim());
      hordeFires = Integer.parseInt(HarnessFlags.get("horde_fires", "9").trim());
      lightbar = Integer.parseInt(HarnessFlags.get("lightbar", "2").trim());
      fireMs = Integer.parseInt(HarnessFlags.get("fire_ms", "110").trim());
      mags = Integer.parseInt(HarnessFlags.get("mags", "6").trim());
      gunType = HarnessFlags.get("gun", "Base.AssaultRifle").trim();
      ambulanceType = HarnessFlags.get("ambulance", "Base.VanAmbulance").trim();
      hordeDir = HarnessFlags.get("horde_dir", "SE").trim().toUpperCase(java.util.Locale.ROOT);
      powerOff = "off".equalsIgnoreCase(HarnessFlags.get("power", "").trim());
      pitchBlack = "pitch".equalsIgnoreCase(HarnessFlags.get("darkness", "").trim());
      director = "jev".equalsIgnoreCase(HarnessFlags.get("director", "").trim());
      burn = !"false".equalsIgnoreCase(HarnessFlags.get("burn", "true").trim()); // false: a plain horde (the ragdoll bench, horde-shoot)
      fireLine = !"false".equalsIgnoreCase(HarnessFlags.get("fire_line", "true").trim()); // false: light_fire_line only marks the line lit
      // true: zombies never attack (stock's SystemDisabler switch; GOD_MODE is a cheat and needs Core.debug, so without it the
      // horde bit the player to death at the pier in hs-stock-1): the horde crowds the player, who keeps shooting
      noAttack = "true".equalsIgnoreCase(HarnessFlags.get("no_attack", "false").trim());
      java.io.File z = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(z, "pzopt-showcase-state.json");
      cmdFile = new java.io.File(z, "pzopt-showcase-cmd.txt");
      cmdFile.delete();
      String[] pier = HarnessFlags.get("pier", "6436,5186").split(",");
      pierX = Float.parseFloat(pier[0].trim()) + 0.5F;
      pierY = Float.parseFloat(pier[1].trim()) + 0.5F;
      String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"}; // k * 45 deg from +x (east), +y is south
      for (int k = 0; k < 8; k++) {
         if (names[k].equals(hordeDir)) {
            dirX = (float)Math.cos(Math.PI / 4 * k);
            dirY = (float)Math.sin(Math.PI / 4 * k);
         }
      }
      Log.info("harness: showcase=horde: " + horde + " burning zombies from the " + hordeDir + ", pier " + pierX + "," + pierY + ", " + gunType + " + " + mags
            + " magazines, " + ambulanceType + " lightbar " + lightbar + (powerOff ? ", power off" : ""));
   }

   /** The route must not teleport the player: the scene runs them to the pier. */
   public static boolean active() {
      return on;
   }

   /** World-ready (game thread): out of harm's way until the scene starts. */
   static void worldReady(IsoPlayer p) {
      if (!on) return;
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true); // setGodMod(true, true) left the player mortal (cine-2)
      if (noAttack) zombie.SystemDisabler.zombiesDontAttack = true;
      p.setInvisible(true, true);
      if (powerOff) IsoWorld.instance.setHydroPowerOn(false);
      if (pitchBlack) {
         zombie.SandboxOptions.instance.nightDarkness.setValue(1); // Pitch Black
         Log.info("harness: showcase: night darkness forced to pitch black");
      }
      clearStrangers(p, 30F);
   }

   /** Route start (game thread): kit, ambulance, horde; then the run. */
   static void routeStart(IsoPlayer p) {
      if (!on || started) {
         return;
      }
      started = true;
      startNs = System.nanoTime();
      try {
         p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
         p.setInvisible(false, true); // the horde sees and chases the player
         p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Aiming, 10);
         p.setPerkLevelDebug(zombie.characters.skills.PerkFactory.Perks.Reloading, 10);
         clearStrangers(p, 30F);
         equip(p);
         parkAmbulance();
         spawnHorde(p);
         RagdollWatch.start(); // pzopt-ragdoll.out: every ragdoll episode of the scene
         if (director) {
            enter(Phase.WAIT); // the director (Jev) decides from the first state on
         } else {
            command = "run_to_pier";
            enter(Phase.RUN);
         }
      } catch (Throwable t) {
         Log.warn("harness: showcase setup failed: " + t);
      }
   }

   private static void enter(Phase ph) {
      phase = ph;
      phaseNs = System.nanoTime();
      Log.info("harness: showcase phase " + ph + " at +" + String.format(java.util.Locale.ROOT, "%.1f", (phaseNs - startNs) / 1e9) + " s");
   }

   private static boolean dry(IsoGridSquare sq) {
      return sq != null && !sq.isWaterSquare() && (sq.isSolidFloor() || sq.TreatAsSolidFloor());
   }

   private static void equip(IsoPlayer p) {
      for (InventoryItem lit : p.getActiveLightItems(new ArrayList<>())) { // no light but the angle-head one
         lit.setActivated(false);
         if (lit.isEmittingLight()) {
            p.removeAttachedItem(lit);
         }
      }
      p.setPrimaryHandItem(null);
      p.setSecondaryHandItem(null);
      InventoryItem item = p.getInventory().AddItem(gunType);
      if (!(item instanceof HandWeapon w)) {
         Log.warn("harness: showcase: " + gunType + " is not a weapon: " + item);
         return;
      }
      gun = w;
      gun.setContainsClip(true);
      gun.setCurrentAmmoCount(gun.getMaxAmmo());
      gun.setRoundChambered(true);
      gun.setCondition(gun.getConditionMax());
      p.setPrimaryHandItem(gun);
      if (gun.isTwoHandWeapon() || gun.isRequiresEquippedBothHands()) {
         p.setSecondaryHandItem(gun);
      }
      String magType = gun.getMagazineType();
      int filled = 0;
      for (int i = 0; magType != null && i < mags; i++) {
         InventoryItem mag = p.getInventory().AddItem(magType);
         if (mag != null) {
            mag.setCurrentAmmoCount(mag.getMaxAmmo());
            filled++;
         }
      }
      InventoryItem belt = p.getInventory().AddItem("Base.Bag_ALICE_BeltSus_Green"); // provides the webbing slots
      if (belt != null && belt.canBeEquipped() != null) {
         p.setWornItem(belt.canBeEquipped(), belt);
      }
      angleLight = p.getInventory().AddItem("Base.FlashLight_AngleHead");
      if (angleLight != null) {
         angleLight.setActivated(true);
         p.setAttachedItem("Webbing Left Walkie", angleLight);
      }
      p.resetEquippedHandsModels();
      Log.info("harness: showcase: " + gunType + " loaded (" + gun.getCurrentAmmoCount() + "), " + filled + " spare " + magType + ", angle light "
            + (angleLight == null ? "missing" : "emitting " + angleLight.isEmittingLight()));
   }

   /** Behind the pier spot (away from the horde), facing the horde, headlights and lightbar on. */
   private static void parkAmbulance() {
      IsoCell cell = IsoWorld.instance.currentCell;
      IsoGridSquare sq = cell.getGridSquare(Math.round(pierX - dirX * 4), Math.round(pierY - dirY * 4), 0);
      if (sq == null) {
         Log.warn("harness: showcase: no square behind the pier spot for the ambulance");
         return;
      }
      float a = (float)Math.atan2(-dirX, -dirY); // the vehicle frame of Harness.spawnAndEnter
      if (a < 0F) a += (float)(Math.PI * 2);
      zombie.iso.IsoDirections dir = zombie.iso.IsoDirections.values()[Math.round(a / (float)(Math.PI / 4)) % 8];
      BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(ambulanceType, dir, 0, sq);
      if (v == null) {
         Log.warn("harness: showcase: could not place " + ambulanceType);
         return;
      }
      float angle = (float)(a + Math.PI);
      while (angle > Math.PI * 2) angle -= (float)(Math.PI * 2);
      v.savedRot.setAngleAxis(angle, 0f, 1f, 0f);
      v.jniTransform.setRotation(v.savedRot);
      v.repair();
      ambulance = v;
      lights();
      Log.info("harness: showcase: " + ambulanceType + " at " + sq.x + "," + sq.y + " facing " + dir + ", headlights " + v.getHeadlightsOn() + ", lightbar " + v.hasLightbar());
   }

   private static void lights() {
      if (ambulance == null) return;
      if (!ambulance.getHeadlightsOn()) ambulance.setHeadlightsOn(true);
      if (lightbar > 0 && ambulance.hasLightbar() && ambulance.getLightbarLightsMode() != lightbar) ambulance.setLightbarLightsMode(lightbar);
   }

   /** A burning horde of fast shamblers hordeGap tiles behind the player (the horde side), chasing. */
   private static void spawnHorde(IsoPlayer p) {
      IsoCell cell = IsoWorld.instance.currentCell;
      int cx = Math.round(p.getX() + dirX * hordeGap), cy = Math.round(p.getY() + dirY * hordeGap);
      ArrayList<IsoGridSquare> ground = new ArrayList<>();
      for (int y = cy - 3; y <= cy + 3; y++) {
         for (int x = cx - 3; x <= cx + 3; x++) {
            IsoGridSquare sq = cell.getGridSquare(x, y, 0);
            float ddx = x + 0.5F - p.getX(), ddy = y + 0.5F - p.getY();
            if (dry(sq) && ddx * ddx + ddy * ddy > 8F * 8F) ground.add(sq); // never on top of the player (cine-1)
         }
      }
      if (ground.isEmpty()) {
         Log.warn("harness: showcase: no dry ground for the horde at " + cx + "," + cy);
         return;
      }
      int per = Math.max(1, (horde + ground.size() - 1) / ground.size());
      for (int i = 0; i < ground.size() && zombies.size() < horde; i++) {
         IsoGridSquare sq = ground.get(i);
         ArrayList<IsoZombie> list = zombie.Lua.LuaManager.GlobalObject.addZombiesInOutfit(sq.x, sq.y, 0, Math.min(per, horde - zombies.size()), null, 50);
         if (list != null) zombies.addAll(list);
      }
      hordeSet.addAll(zombies);
      int burning = 0;
      for (IsoZombie z : zombies) {
         try {
            z.doZombieSpeed(2); // fast shamblers: a chase the player wins
            if (burn) z.SetOnFire();
            burning += z.isOnFire() ? 1 : 0;
            z.pathToCharacter(p);
         } catch (Throwable t) {
            // not fully in the world yet: the re-ignition in tick catches it
         }
      }
      Log.info("harness: showcase: " + zombies.size() + " zombies at " + cx + "," + cy + ", " + burning + " burning");
   }

   /** A line of fires across the horde's way, 7 tiles out from the pier spot. */
   private static void lightFireLine() {
      IsoCell cell = IsoWorld.instance.currentCell;
      float lx = -dirY, ly = dirX;
      fireSquares.clear();
      if (!fireLine) {
         firesLit = true;
         Log.info("harness: showcase: fire line off (fire_line=false), the fight starts");
         return;
      }
      for (int i = 0; i < hordeFires; i++) {
         float off = (i - (hordeFires - 1) / 2F) * 1.5F;
         IsoGridSquare sq = cell.getGridSquare(Math.round(pierX + dirX * 7 + lx * off), Math.round(pierY + dirY * 7 + ly * off), 0);
         if (dry(sq)) {
            zombie.iso.objects.IsoFireManager.StartFire(cell, sq, true, 100);
            fireSquares.add(sq);
         }
      }
      firesLit = true;
      Log.info("harness: showcase: fire line of " + fireSquares.size() + " across the horde's way");
   }

   /** The area's own zombies near the player (they bit the player from behind in showcase-diag): removed. */
   private static void clearStrangers(IsoPlayer p, float radius) {
      try {
         for (IsoZombie z : new ArrayList<>(IsoWorld.instance.getCell().getZombieList())) {
            if (hordeSet.contains(z)) continue;
            float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
            if (dx * dx + dy * dy < radius * radius) {
               z.removeFromWorld();
               z.removeFromSquare();
               strangersRemoved++;
            }
         }
      } catch (Exception e) {
         Log.warn("harness: showcase: clearing the area's zombies failed: " + e);
      }
   }

   /** Every frame while the run is live (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (!on) {
         return;
      }
      if (nowNs - lastClearNs >= 1_000_000_000L) {
         lastClearNs = nowNs;
         clearStrangers(p, started ? 24F : 30F);
         if (powerOff && IsoWorld.instance.isHydroPowerOn()) IsoWorld.instance.setHydroPowerOn(false);
      }
      if (!started) {
         return;
      }
      RagdollWatch.tick(nowNs);
      lights();
      if (zombie.input.Mouse.isCursorVisible()) zombie.input.Mouse.setCursorVisible(false); // no pointer in the shot
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.getStats().set(CharacterStat.ENDURANCE, 1F);
      p.getStats().set(CharacterStat.FATIGUE, 0F);
      if (angleLight != null) {
         if (!angleLight.isActivated()) angleLight.setActivated(true);
         if (p.getAttachedItem("Webbing Left Walkie") != angleLight) {
            p.setAttachedItem("Webbing Left Walkie", angleLight);
            reattached++;
         }
      }
      if (gun != null && gun.isJammed()) gun.setJammed(false);
      if (burn && nowNs - lastIgniteNs >= 2_000_000_000L) {
         lastIgniteNs = nowNs;
         for (IsoZombie z : zombies) {
            if (!z.isDead() && !z.isOnFire()) {
               try {
                  z.SetOnFire();
               } catch (Throwable ignored) {
               }
            }
         }
         if (firesLit) { // the storm's rain does not put fires out, but a fire burns down: keep the line lit
            for (IsoGridSquare sq : fireSquares) {
               if (sq.getFire() == null) zombie.iso.objects.IsoFireManager.StartFire(IsoWorld.instance.currentCell, sq, true, 100);
            }
         }
      }
      IsoZombie target = null;
      float bestD = Float.MAX_VALUE;
      for (int i = zombies.size() - 1; i >= 0; i--) {
         IsoZombie z = zombies.get(i);
         if (z == null || z.isDead() || z.getCurrentSquare() == null) {
            zombies.remove(i);
            continue;
         }
         float dx = z.getX() - p.getX(), dy = z.getY() - p.getY(), d = dx * dx + dy * dy;
         if (d < bestD) {
            bestD = d;
            target = z;
         }
      }
      float dt = lastTickNs == 0L ? 0F : Math.min(0.1F, (nowNs - lastTickNs) / 1e9F);
      lastTickNs = nowNs;
      float px = p.getX(), py = p.getY();
      float toPier = (float)Math.hypot(pierX - px, pierY - py);
      boolean reloading = !p.getCharacterActions().isEmpty();
      float tx = target != null ? target.getX() : px + dirX * 10, ty = target != null ? target.getY() : py + dirY * 10;
      float bearing = (float)Math.toDegrees(Math.atan2(ty - py, tx - px));
      float offFacing = Math.abs(((bearing - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F);
      if (director) {
         if (nowNs - lastCmdCheckNs >= 100_000_000L) {
            lastCmdCheckNs = nowNs;
            readCommand();
         }
      } else {
         autopilot(toPier, offFacing, reloading);
      }
      releaseKeys();
      p.setForceRun(false);
      switch (command) {
         case "run_to_pier" -> {
            aimOff();
            if (toPier > 0.8F) {
               moveKeys(pierX - px, pierY - py);
               holdKey("Run");
            }
            lastPath = "keys to pier " + String.format(java.util.Locale.ROOT, "%.1f", toPier) + (p.isGodMod() ? " god" : " mortal");
         }
         case "face_horde" -> {
            aimOff();
            float d = ((bearing - p.getDirectionAngle()) % 360F + 540F) % 360F - 180F; // the short way round
            float step = 300F * dt;
            p.setDirectionAngle(p.getDirectionAngle() + Math.max(-step, Math.min(step, d)));
         }
         case "light_fire_line" -> {
            aimOff();
            if (!firesLit) lightFireLine();
         }
         case "shoot" -> fight(p, nowNs, tx, ty, target != null && bestD < 30F * 30F, reloading);
         case "reload" -> {
            aimOff();
            if (!reloading) reload(p);
         }
         default -> aimOff(); // hold
      }
      if (director && nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, bestD, toPier, offFacing, reloading);
      }
      if (gun != null) {
         int ammo = gun.getCurrentAmmoCount();
         if (lastAmmo >= 0 && ammo < lastAmmo) shots += lastAmmo - ammo;
         lastAmmo = ammo;
      }
      if (nowNs - lastLogNs >= 2_000_000_000L) {
         lastLogNs = nowNs;
         Log.info(String.format(java.util.Locale.ROOT, "harness: showcase: %s (%d director commands), %d zombies left, nearest %.1f tiles, to pier %.1f, aiming %b, rounds %d, ammo %d, reloads %d, spare mags %d, actions %d, strangers removed %d, light reattached %d, run %s | state %s action %s",
               command, commands, zombies.size(), Math.sqrt(bestD), Math.hypot(pierX - p.getX(), pierY - p.getY()), p.isAiming(), shots, gun == null ? -1 : gun.getCurrentAmmoCount(), reloads,
               spareMags(p), p.getCharacterActions().size(), strangersRemoved, reattached, lastPath,
               p.getCurrentState() == null ? "-" : p.getCurrentState().getClass().getSimpleName(), p.getActionStateName()));
      }
   }

   private static int spareMags(IsoPlayer p) {
      if (gun == null || gun.getMagazineType() == null) return 0;
      int n = 0;
      for (InventoryItem it : p.getInventory().getItemsFromFullType(gun.getMagazineType())) {
         if (it.getCurrentAmmoCount() > 0) n++;
      }
      return n;
   }

   private static void aimOff() {
      aimOverride = false;
      holdButtons = false;
      fireDown = false;
   }

   /** The movement keys toward (dx, dy) in world tiles: the keys move screen-relative (up = north-west). */
   private static void moveKeys(float dx, float dy) {
      float sx = dx - dy, sy = dx + dy, len = (float)Math.hypot(sx, sy);
      if (len < 1e-3F) return;
      sx /= len;
      sy /= len;
      if (sy < -0.38F) holdKey("Forward");
      if (sy > 0.38F) holdKey("Backward");
      if (sx < -0.38F) holdKey("Left");
      if (sx > 0.38F) holdKey("Right");
   }

   /** Without a director: run to the pier, face the horde, light the fire line, shoot, reload when empty. */
   private static void autopilot(float toPier, float offFacing, boolean reloading) {
      String next = command;
      if (!firesLit && toPier > 0.8F && !"face_horde".equals(command)) next = "run_to_pier";
      else if (!firesLit && offFacing > 8F) next = "face_horde";
      else if (!firesLit) next = "light_fire_line";
      else if (gun != null && gun.getCurrentAmmoCount() == 0 && !gun.isRoundChambered() && !reloading) next = "reload";
      else if (!reloading) next = "shoot";
      if (!next.equals(command)) {
         command = next;
         Log.info("harness: showcase autopilot: " + command);
      }
   }

   private static void readCommand() {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Set.of("run_to_pier", "face_horde", "light_fire_line", "shoot", "reload", "hold").contains(c)) return;
         if (!c.equals(command)) {
            commands++;
            Log.info("harness: showcase director: " + command + " -> " + c + " (#" + seq + ")");
         }
         command = c;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   /** The scene's facts for the director (Jev), plain numbers and flags, written atomically. */
   private static void writeState(IsoPlayer p, float bestD, float toPier, float offFacing, boolean reloading) {
      int near6 = 0, burning = 0;
      for (IsoZombie z : zombies) {
         float dx = z.getX() - p.getX(), dy = z.getY() - p.getY();
         if (dx * dx + dy * dy < 36F) near6++;
         if (z.isOnFire()) burning++;
      }
      String json = String.format(java.util.Locale.ROOT,
            "{\"t\":%d,\"seconds_since_start\":%.1f,\"current_action\":\"%s\",\"player\":{\"distance_to_pier_tiles\":%.1f,\"at_pier\":%b,\"moving\":%b,"
                  + "\"degrees_between_facing_and_nearest_zombie\":%.0f,\"aiming\":%b},\"horde\":{\"zombies_alive\":%d,\"nearest_zombie_tiles\":%.1f,"
                  + "\"zombies_within_6_tiles\":%d,\"zombies_burning\":%d},\"weapon\":{\"rounds_in_magazine\":%d,\"round_chambered\":%b,\"spare_full_magazines\":%d,"
                  + "\"reloading\":%b,\"rounds_fired\":%d},\"fire_line_lit\":%b}",
            System.currentTimeMillis(), (System.nanoTime() - startNs) / 1e9, command, toPier, toPier <= 0.8F, p.isPlayerMoving(), offFacing, p.isAiming(),
            zombies.size(), bestD == Float.MAX_VALUE ? -1F : Math.sqrt(bestD), near6, burning, gun == null ? 0 : gun.getCurrentAmmoCount(),
            gun != null && gun.isRoundChambered(), spareMags(p), reloading, shots, firesLit);
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: showcase: state write failed: " + e);
      }
   }

   /** The game's own automatic reload (the reload key's action): eject, insert a full magazine, rack. */
   private static void reload(IsoPlayer p) {
      if (gun == null || spareMags(p) == 0 || gun.getCurrentAmmoCount() >= gun.getMaxAmmo()) return;
      try {
         Object cls = zombie.Lua.LuaManager.env.rawget("ISReloadWeaponAction");
         Object fn = cls instanceof se.krka.kahlua.vm.KahluaTable t ? t.rawget("BeginAutomaticReload") : null;
         if (fn != null) {
            zombie.Lua.LuaManager.caller.pcall(zombie.Lua.LuaManager.thread, fn, p, gun);
            reloads++;
         }
      } catch (Throwable t) {
         Log.warn("harness: showcase: reload failed: " + t);
      }
   }

   private static void fight(IsoPlayer p, long nowNs, float tx, float ty, boolean inRange, boolean reloading) {
      if (gun == null || reloading) {
         aimOff();
         return;
      }
      float zoom = zombie.core.Core.getInstance().getZoom(0);
      // the aim follows the game's mouse: on the target's feet (the game projects the mouse onto the floor)
      float sx = zombie.iso.IsoUtils.XToScreen(tx, ty, p.getZ(), 0) - zombie.iso.IsoCamera.getOffX(0);
      float sy = zombie.iso.IsoUtils.YToScreen(tx, ty, p.getZ(), 0) - zombie.iso.IsoCamera.getOffY(0);
      aimXA = Math.round(sx / zoom);
      aimYA = Math.round(sy / zoom);
      aimOverride = true;
      holdButtons = true;
      fireDown = inRange && (nowNs - startNs) / 1_000_000L % fireMs < fireMs / 2;
   }
}
