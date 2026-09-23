# Building the DLSS shim on Windows (`pzopt_ngx64.dll`)

Written 2026-09-24 for the maintainer's Windows boot (the desktop: RTX 4090). Goal: build the Windows DLSS shim with
MSVC, check that DLSS starts in the game, publish it as a `dlss-windows-<commit>` release so the Optimizations tab's
**Install DLSS files** button works on Windows, and record what happened. Claude Code on Windows can follow this file
top to bottom; every step says what success looks like.

Background (docs/plan-upscalers.md): DLSS runs through `src/native/pzopt_ngx.cpp`, a small C++ shim that creates a
Vulkan device on the GPU of the game's OpenGL context, runs NVIDIA NGX there and shares the images with OpenGL
(`GL_EXT_memory_object_win32` / `GL_EXT_semaphore_win32` on Windows). The Windows paths of the shim (`#ifdef _WIN32`)
and of `pzopt.Dlss` (Win32 handles, `natives/pzopt_ngx64.dll`) were written blind; **they were first compiled and run
on 2026-09-24 and needed no change** (MSVC 14.44, DLSS SDK v310.9.1, `dlss: ready` in game). Steps 1-4 below are
therefore a re-run, not an experiment; docs/plan-upscalers.md carries the numbers.
NVIDIA's static library `nvsdk_ngx_s.lib` only links with MSVC, which is why this has to happen on Windows.

## 1. Tools (once)

| What | How | Check |
|---|---|---|
| Visual Studio 2022 Build Tools, workload **Desktop development with C++** (MSVC v143 + a Windows 10/11 SDK) | `winget install Microsoft.VisualStudio.2022.BuildTools` then add the workload in the installer, or the full Community edition | Start menu: **x64 Native Tools Command Prompt for VS 2022**, `cl` prints the compiler version |
| Git for Windows (brings Git Bash) | `winget install Git.Git` | `git --version` |
| Python 3 (for the packaging script) | `winget install Python.Python.3.12` | `python --version` in Git Bash (`python3` may need `alias python3=python`) |
| GitHub CLI, logged in as the repo owner | `winget install GitHub.cli`, `gh auth login` | `gh auth status` |
| NVIDIA driver with an RTX card | already there | `C:\Windows\System32\nvapi64.dll` and `vulkan-1.dll` exist |

No Vulkan SDK is needed: the shim loads `vulkan-1.dll` at run time, so the Vulkan **headers** are enough.

## 2. Sources

In Git Bash:

```bash
cd /c && mkdir -p pz && cd pz
git clone https://github.com/xD3I/PZ_Optimization.git            # master has everything (2026-09-24)
git clone --branch v310.9.1 --depth 1 https://github.com/NVIDIA/DLSS.git dlss-sdk
git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git vulkan-headers
ls dlss-sdk/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib dlss-sdk/lib/Windows_x86_64/rel/nvngx_dlss.dll   # both must exist
```

The SDK must stay on tag `v310.9.1`: the packaging step pins `nvngx_dlss.dll` of that tag by its sha256
(`3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983`, 58,956,912 bytes). If you use a newer SDK tag,
the Linux shim and `libnvidia-ngx-dlss.so` should move to the same tag (re-run `scripts/dlss-natives.sh --publish` on
Linux); nothing else changes.

## 3. Build

Open **x64 Native Tools Command Prompt for VS 2022** (plain cmd with the MSVC environment), then:

```bat
cd C:\pz\PZ_Optimization
mkdir build
cl /nologo /O2 /EHsc /std:c++17 /MT /LD /DNDEBUG /DWIN32_LEAN_AND_MEAN /DNOMINMAX ^
   /I C:\pz\dlss-sdk\include /I C:\pz\vulkan-headers\include ^
   src\native\pzopt_ngx.cpp C:\pz\dlss-sdk\lib\Windows_x86_64\x64\nvsdk_ngx_s.lib ^
   /Fe:build\pzopt_ngx64.dll /Fo:build\ ^
   /link advapi32.lib user32.lib shell32.lib ole32.lib version.lib
```

- `/MT` + `nvsdk_ngx_s.lib` = the static C runtime on both sides, so players need no Visual C++ redistributable.
  (The `_d` libraries are for `/MD`; do not mix them.)
- Success: `build\pzopt_ngx64.dll` exists and exports the entry points the Java side looks up:
  `dumpbin /exports build\pzopt_ngx64.dll` must list `pzngx_init`, `pzngx_error`, `pzngx_optimal`, `pzngx_create`,
  `pzngx_image_fd`, `pzngx_image_bytes`, `pzngx_semaphore_fd`, `pzngx_evaluate`, `pzngx_evaluate_set`,
  `pzngx_times`, `pzngx_gpu_us`, `pzngx_destroy`, `pzngx_shutdown`.
- If it does not compile or link, fix `src/native/pzopt_ngx.cpp` inside its `#ifdef _WIN32` branches (or add the
  missing Windows library to `/link`), keep the Linux code unchanged, and note every fix in
  `src/native/README.md`. Likely spots, since this code never met a Windows compiler: the `HANDLE` casts of the
  exported handles (`pzngx_image_fd`, `pzngx_semaphore_fd` return them as `long long`), `CreateThread` in
  `BigStackWorker`, and unresolved symbols from `nvsdk_ngx_s.lib` (each names the Windows `.lib` to add).
  The Linux build must still work afterwards: back on Linux, `scripts/build.sh` rebuilds the `.so` from the same file.

## 4. Test it in the game

1. Install PZ_Optimization on Windows the usual way (the newest `win-*` release: `install.ps1`, or the Workshop item
   plus its installer) and start the game once so the install is complete.
2. Next to `ProjectZomboid64.exe` (the game folder, e.g. `C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid`)
   create a folder `natives` if there is none, and copy into it:
   `C:\pz\PZ_Optimization\build\pzopt_ngx64.dll` and `C:\pz\dlss-sdk\lib\Windows_x86_64\rel\nvngx_dlss.dll`.
   (`pzopt.Dlss` opens `natives\pzopt_ngx64.dll` relative to the game's working folder, which is the game folder,
   and NGX finds `nvngx_dlss.dll` in the same folder.)
3. Game > Options > Optimizations: **Upscaler** = `dlss`, **Upscaler quality** = `quality`; leave the DLSS entries at
   their defaults (preset E, output 67 %, RCAS finish). Accept, quit, start the game again, load a save.
4. `%UserProfile%\Zomboid\console.txt` must contain `[pzopt] dlss: ready, 3413x1440 -> 3413x1440 (quality, preset e, ...)`
   (the sizes follow your screen). Anything else is the reason DLSS did not start, e.g.
   `upscaler: dlss unavailable (dlss: the GL driver has no GL_EXT_memory_object / GL_EXT_semaphore win32 ...)` or an
   NGX / Vulkan error from the shim; the game then runs FSR 1.0, so nothing breaks.
   For more detail add `-Dpzopt.devUpscalerLog=true` to the launch options (or `devUpscalerLog=true` in the game
   folder's `pzopt.properties`).
5. Look at the picture: walk and drive a little. No black screen, no doubled or trailing edges, the view cone in place.
   F9 shows the overlay (fps, GPU busy). A quick A/B: the same spot with Upscaler `off` vs `dlss`.
6. Crash or hang at start: delete the two files from `natives`, and the game is back to FSR 1.0. The shim runs all NGX
   calls on its own 64 MB-stack thread (NGX overflowed the render thread's stack on Linux); a crash inside
   `nvngx_dlss.dll` / `_nvngx.dll` points there first.

## 5. Publish the Windows DLSS files (after step 4 printed `dlss: ready`)

If you changed `src/native/pzopt_ngx.cpp`, commit and push it first (end the commit message with the repository's
usual `Co-Authored-By` line); the release is tagged on the pushed commit. Then in Git Bash:

```bash
cd /c/pz/PZ_Optimization
git pull
PZOPT_DLSS_SDK=/c/pz/dlss-sdk scripts/dlss-natives.sh --windows build/pzopt_ngx64.dll --publish
```

It writes `build/pzopt-dlss-windows-x64.zip` (our DLL + `pzopt-dlss-files.txt` + `NOTICE.txt`; NVIDIA's DLL is not in
it), checks that NVIDIA's URL still serves exactly the pinned `nvngx_dlss.dll`, and creates the GitHub release
`dlss-windows-<commit>` (**not** marked latest: the installers and the updater look for the classes zip by name).
NVIDIA's DLL is never re-hosted here: the DLSS SDK license allows distributing it only as part of an application;
the button downloads it from `https://github.com/NVIDIA/DLSS/raw/v310.9.1/lib/Windows_x86_64/rel/nvngx_dlss.dll`.

## 6. Test the button on Windows

1. Delete `pzopt_ngx64.dll` and `nvngx_dlss.dll` from the game's `natives` folder.
2. Options > Optimizations: the button reads **Install DLSS files**; its tooltip says what it will download.
   (`DLSS files: not available here` = the NVIDIA driver or `vulkan-1.dll` was not found: the tooltip says which.)
3. Click it: **Downloading the DLSS files N %** (about 60 MB, mostly NVIDIA's DLL), then
   **DLSS files installed: restart the game**. `console.txt`: `[pzopt] upscaler deps: installed pzopt_ngx64.dll,
   nvngx_dlss.dll from dlss-windows-<commit>`. `natives\pzopt-dlss-installed.txt` lists them.
4. Restart: `dlss: ready` as in step 4.

## 7. Record the result

Append what happened to `docs/plan-upscalers.md` (a dated line: builds or not, the fixes, `dlss: ready` or the
failure line, fps off vs dlss at one spot with the overlay), and to `src/native/README.md` the build line that worked.
Commit and push. Back on Linux the session reads these to continue.
