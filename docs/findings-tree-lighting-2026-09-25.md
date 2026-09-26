# Tree lighting with every lighting feature on (2026-09-25/26)

Report (maintainer): with all the new lighting settings on together (pixelLight, ambientOcclusion, sunShadows, HDR) the
picture is unstable and trees are not lit correctly: their lower part gets no shadow. Worktree `../PZ_Optimization-tree`
`light`, branch `tree-light`, on master 8304fb3 (with the grid-line session's pplLv clamp, b70f234). Desktop runs through
the queue (labels `tl-*`, `tw-*`), shot spot `start=8147,11507` (Rosewood church / parking lot), `--prop hdr=true
pixelLight=true ambientOcclusion=true sunShadows=true`, the desktop tab file's AO strengths (vegetation 50, walls 50).

## What was wrong

1. **Black and stale tree crowns (PixelLight lattice).** The lattice holds a chunk's levels up to its top + 1. A tree
   crown's pixels reconstruct up to five levels above its foot. Crowns read (a) level 1 of a chunk with a two-storey
   house, where the outdoor squares are null (packed as light 0: the orange tree's black crown top at the cemetery),
   (b) levels never packed for that chunk, i.e. whatever chunk used the slot before (the fully black birch; different
   light as the camera moves: the instability), (c) chunks that are not on screen themselves but whose squares an
   on-screen texture shows. The "above" level was also never refreshed when the top level's light changed.
2. **Trees took no sun shadow and cast none.** The sun term's march is sized for the texture's two levels (4.9 squares);
   crowns stand up to 12 squares, out of reach at any sun height. A tree is baked as a flat camera-facing card
   (TreeBake): when the sun shone along it (16 h, the card runs NE-SW) its shadow collapsed to a line. The card's own
   pixels never shade themselves (the kernel skips the surface's own plane) and its DEPTH16 depth steps once per texel
   row, so its reconstructed normals are noise: the sun term had horizontal stripes across every crown.
3. **Tall crowns' depth is clamped.** A chunk texture's depth is relative to its chunk; TreeBake writes a card nearer
   by one level per level of height, so a tall tree near its chunk's near edge needs depths below the texture's range
   and GL clamps them. Anything that reads a position back from that depth slides along the view ray (PixelLight's
   light lookup included; reported to the wall-flicker session, not fixed there).
4. **A first sampled tree model seamed.** Canopy shade from foliage samples along the sun march (tried first) depends
   on which neighbour depths a texture's compute sees; a tree drawn into two overlapping textures shaded differently in
   each: dark bands where one copy takes over from the other (the birch, `tl-c-12h`: rows at 33/255 against 100).

## What changed

- `pplAirFill` (PixelLight.pack): a null square takes its column's highest square below (its top corners, like the
  existing "above" layer); levels are packed up to the tallest neighbour's top + 1 (extra levels are byte copies of the
  "above" block); a repacked level marks the levels above it that borrow its corners dirty.
- `pplPackRing`: the loaded chunks around the on-screen ones are packed too.
- The grid-line session's `pplLv` clamp (master b70f234): the light level stays within the texture's levels.
- `sunShadowTrees` (ChunkAo kernel): every tree in reach (5 x 5 chunks, the nearest 16) is a crown proxy, an ellipsoid
  on its card sized from its sprite (JUMBOXXL / XL / JUMBO / plain). Any receiver's sun visibility is multiplied by
  exp(-sigma x the length of its sun ray inside the crowns); a tree's own texels take the path out of their own crown
  (the lower crown, the trunk and the far side in shade, the sunward top lit). Tree texels are found by screen position
  on each tree's card (the depth only has to match the card or be clamped), use the card's analytic normal, and the
  cards themselves no longer cast in the march. `sunShadowCanopyPct` (35) = sigma x 100.
- `aoTreeCanopyPct` (25): the sky the crowns hide, straight up through every crown, at most half the sky, times the
  surface's AO strength (the ground under a tree, a tree's lower crown).
- Identical per world position for every texture that holds a tree's copy: no seams from the tree terms.

## Rigs

- `pzopt.TreeWalk` (`explore=trees`, `director=jev`) + `harness/trees/tree-director.py`: Jev walks the character tree to
  tree (reach, one lap of 8 waypoints on the grid path, watch); `pzopt-trees.out` logs the trees' screen boxes per frame.
  First version stalled against the Rosewood church wall (a tree 10 tiles away counted as reached; the lap pushed into
  the wall until the route cap): now reached = within 2.5 tiles, no path = given up, 6 s without moving = given up, 3 s
  per waypoint, `trees_max_seconds`.
- `harness/trees/tree-metrics.py`, `tree-judge.py` (Jev), `walk-ab.sh`.
- `devAoDumpTree=N` + `harness/trees/tree_rig.py`: the kernel's inputs of the first N computes with trees (source
  depths, uniforms, chunk) and an offline replay of the current GLSL (matches the game to 0.00-0.02 mean).
- `devPplView=13`: the reconstructed height.

## Measurements

**Kernel cost** (tree_rig.py replaying in-game noon dumps, queued `cmd` jobs with nothing else on the GPU, RTX 4090, per
compute = kernel + blur of one 1024 x 1024 chunk texture at the 50 % AO scale):

| Texture | Tree terms off | First crown-proxy version | Optimised |
|---|---|---|---|
| 1020,1440 (a big birch crown fills it) | 46-48 us | 66-72 us | 48 us |
| 1019,1439 (trees in reach, few of their texels) | 42-46 | 48-50 | 45-47 |
| 1020,1439 | 39-42 | 46-47 | 41-43 |

What the first version paid for: the AO horizon of a tree's own texels (a clamp-aware 9-source fetch and a crown test on
16 taps: ~17 us on the birch texture) and the march skip with the same fetch (~10 us). Now a tree texel skips the horizon
(its sky term is the proxy's; the horizon over a flat card only ever saw its own leaves) and the march uses the plain
fetch (a clamped crown sample reads behind its card by up to 6 squares and is still recognised). Rig pitfall: the first
timings put ~30 us of Python uniform uploads inside the GPU timer query (moderngl idles the GPU between them); the rig now
sets the uniforms once outside the timed loop.

The sampled canopy (the first idea, per-sample foliage along the sun march) cost about the same as the proxies but seamed
between the texture copies of a tree (above); the longer march for trees (reach 14 squares, 32 steps) cost +25 us per
compute and 24 steps over the same reach looked the same (0.85 / 255 mean) at the old cost; both were replaced by the
proxies, which need neither.

**Frame cost** (drive-120-south, clear 16 h, 240 cap, no upscaler, all lighting keys on, the new keys on vs off, ABBA):
first builds cost the game thread ~+2 % (the lattice packed 2.4x the blocks: every ground light change repacked the
borrowed levels; the ring looked chunks up ~5,000 times a frame). With the borrowed levels refreshed at most every 120
frames, the ring on flat slot arrays and ring chunks packed lite (no connectivity lookups, a full pack once on screen):
on 224.4 / 223.4 fps, p99 11.1 / 11.1 ms, game thread 57 / 58 % of a core; off 218.5 / 217.0 fps, p99 11.9 / 12.5 ms,
58 / 60 %: parity (runs `tw-drv8-*`).

**Jev-directed tree walks** (`walk-ab.sh`, start 8166,11521, 16 h, no upscaler, the same three reachable trees in the
same order in every run, 100 s capture at 15 fps; runs `tw3-*`):

| | fix | keys off | stock lighting |
|---|---|---|---|
| still-camera jumps in the tree boxes, mean / p99 / max % | 0.19 / 7.8 / 12.2 | 0.23 / 9.2 / 9.8 | 0.24 / 13.7 / 17.7 |
| still-camera jumps over the view, mean / p99 % | 0.13 / 4.6 | 0.14 / 5.2 | 0.14 / 7.5 |
| burst frames | 4 | 5 | 3 |

As steady as the stock lighting with a still camera. The walk's box metrics cannot tell a black crown from the new
shadows under a tree (Jev read both lit runs as "black trees" against stock), so crowns and shading are judged on
pixel-aligned shots instead.

**Pixel-aligned shots at the repro spot** (`tree-shot-judge.py`, noon, same frame against stock lighting, runs `tf-*`,
`tf2-12h`): black pixels in the tree boxes before -> fix: river birch 70 -> 19, red maple 919 -> 297 (what is left is
dark bush and fence inside the box under the new shadows, not the crown), birch near the player 1,334 -> 152;
lower third / upper third relative to stock: 1.011 -> 0.874, 0.951 -> 0.826, 0.981 -> 0.661 (the per-frame birch with
`TreeShade`). Jev: before had the problem 0.93, lower part shaded 0.95, verdict partly_fixed 0.90 (it counts the
remaining 0.2-0.4 % dark box pixels as black crowns). The original report's state (session start, `tl-base` vs
`tl-ctl`): the whole birch black, the maple's crown top black.

## Open

- Tall crowns near a chunk's near edge keep a clamped depth: PixelLight lights those crown pixels from squares ~3 units
  of x + y off (invisible by day, wrong under a torch). A depth bias per chunk texture or a different card depth model in
  TreeBake would fix it (reported to the wall-flicker session).
- Trees drawn per frame shade symmetrically (the per-frame card does not know its sunward side).
- Faint seam lines can remain where a tree's copies meet across chunk textures (from before; much weaker now).
