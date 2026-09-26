# Per-pixel lighting: light leaking through walls and across floors, 2026-09-26

Maintainer's reports (pixelLight on): (1) light from the floor below seems to leak onto the current floor, random colours
on walls; (2) in the basement bench, while the player climbs the stairs, a room at the top left behind a wall lights up.
Worktree `../PZ_Optimization-pplleak`, branch `ppl-leaks` (on master 989947e). Desktop, every run the Jev-directed
`explore=stairs` walk of the Riverside police station (`Sandbox/2026-09-25_21-32-32`: basement -> 0 -> 1 -> 0 -> basement)
with `pixelLight pplShadows ambientOcclusion sunShadows` on and `devCapture=0,90,20,25`; day (the save's torch), night with
`lights=on` + torch, night torch only. References: the same walk with `pixelLight=false` and the stock game (`enabled=false`).

## Causes and fixes (all in `pzopt.PixelLight`, keys default on)

1. **`pplTorchCanSee`** (report 2, most of report 1). `pack()` treats a listed-but-not-added handheld torch as hidden only
   when the square's light is below 90 % of the torch value. Squares behind a wall that a room light already lights (0.9)
   fail that test: the native lists the torch (0.22-0.47) but leaves their light unchanged, so the pack subtracted a torch
   that was never added and the shader drew the analytic cone through the wall, with the stepped dark bands of the
   subtraction and the shadow mask. Those squares are "seen" but not "can see" (vis 1); every square where the native
   really added the torch had vis 7 (dumps `pr29-base-dump*`). Now the torch is off every square the player cannot see.
2. **`pplOwnLevelLights`**. The composite's light loop applied every torch, lamp, fire and vehicle light to pixels up to
   one level above and below (`abs(a.z - lz) > 1.5`, reach measured in x/y only), and the per-chunk light list and
   `torchNear` did the same: a torch's glow on the ground below the player's floor, lamp colours on the floor above. A light
   now lights its own level only (`floor(z + 0.05)`: a holder on the stairs lights the stairs' level); the native's field
   still carries whatever reaches another level.
3. **`pplShadowDepthTest`** (`pplShadows` only). The composite reads the previous frame's torch shadow mask reprojected;
   in the first frames after a floor change that mask belonged to the other floor's scene, and drew grey streaks on the
   new floor's walls. The mask already stores its depth: texels more than half a level away from the pixel are ignored.

## Evidence

- Torch off: the locker-room artifact disappears (run `pr29-base-notorch`); a dev switch on the old build (1 = own level,
  2 = torch gate) attributes the wall colours at the descent to (1) and the glow below floor 1 to (2) (runs `pr29-lf*`).
- Iteration 4 (runs `leak4-*`): the locker room during the climb and the lobby wall / floor on the way down match the
  `pixelLight=false` walk; the old build shows the patches, bands, dark EXIT wall and the floor blob. Registered
  capture diffs against `pixelLight=false` (share of frame in solid patches, per walk phase), torch-only night:

  | phase | old | fixed |
  |---|---|---|
  | basement, start | 0.153 % | 0.049 % |
  | basement, looking around | 0.173 % | 0.068 % |
  | floor 0 | 0.542 % | 0.457 % (grass shadows, below) |
  | floor 1 | 0.231 % | 0.062 % |

- Frame time unchanged (night 270.9 -> 269.3 fps, p99 13.6 -> 13.3 ms; day 271.4 -> 269.2, p99 13.3 -> 13.5). Unit
  tests pass. The patch applies cleanly on top of PR #29 (source-floor tags; independent code paths).

## Not leaks (left as they are)

- The roof of the neighbouring building black while the player is on floor 1: the stock game draws it the same (fog of
  war above the player's level); `pixelLight=false` shows a stale bake there.
- `pplShadows` (off by default): the lawn beyond the lobby's glass front stays dark under the torch. The mask view
  (`devPplView=9`) shows the grass and hedges casting long shadows away from the low torch; skipping wall-plane occluders
  in the march did not change it (tried, reverted).
- Recordings of these runs are not usable: another desktop window covered the game during several of them; the numbers
  and crops come from `devCapture` (the game's framebuffer). Rigs: `/tmp/pr29/{rdiff,cappair,cutframes}.py`.
