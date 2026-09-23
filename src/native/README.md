# src/native

| File | What |
|---|---|
| `pzopt_los.cpp` | the C++ LOS pass experiment (`playerLosNative`, `PZOPT_NATIVE=1`) |
| `pzopt_ngx.cpp` | the DLSS shim (`upscaler=dlss`, docs/plan-upscalers.md): a Vulkan device on the GL context's GPU, NGX init / optimal size / feature / evaluate, the four images and two semaphores exported for GL, every entry point on a 64 MB-stack worker thread; a ring of three command buffers so the CPU only waits for the evaluation three frames back |

## Building the shim

The player-facing steps (packages per distribution, the SDK checkout, the one g++ line, the two files under the
game's `natives/`, the console lines to expect) are in the README, "Enabling DLSS (Linux, RTX)" under
"Upscaling: FSR 1.0 and DLSS"; releases ship no natives (2026-09-22).

Linux (`scripts/build.sh` does this when `~/.local/share/nvidia-dlss-sdk` holds a checkout of
https://github.com/NVIDIA/DLSS and the Vulkan headers are installed):

    g++ -O2 -shared -fPIC -std=c++17 -fvisibility=hidden -I$SDK/include -o natives/libpzopt_ngx64.so \
        src/native/pzopt_ngx.cpp $SDK/lib/Linux_x86_64/libnvsdk_ngx.a -ldl -lpthread

and `libnvidia-ngx-dlss.so.<ver>` from `$SDK/lib/Linux_x86_64/rel/` next to it under `natives/`.

Windows (step by step, including testing and publishing: `docs/dlss-windows-build.md`, 2026-09-24; the notes below are from 2026-09-22): the source carries the Win32 handle paths
(`VK_KHR_external_memory_win32` / `GL_EXT_memory_object_win32`, `pzopt.Dlss` imports handles instead of fds
and looks for `natives/pzopt_ngx64.dll`), but the SDK's `nvsdk_ngx_s.lib` is an MSVC static library that
mingw cannot link (`__security_cookie`, `StringCch*`, MSVC-mangled internals), so the DLL needs Visual Studio:

    cl /O2 /std:c++17 /LD /I%SDK%\include /I<vulkan headers> src\native\pzopt_ngx.cpp ^
       %SDK%\lib\Windows_x86_64\x64\nvsdk_ngx_s.lib /Fe:natives\pzopt_ngx64.dll

with `nvngx_dlss.dll` from `%SDK%\lib\Windows_x86_64\rel\` next to it.

**Built and run on 2026-09-24** (Windows 11, VS 2022 Build Tools 17.14 = MSVC 14.44.35207 + Windows SDK 10.0.26100,
DLSS SDK v310.9.1). The source needed **no change**: the `#ifdef _WIN32` branches compiled and linked as written.
The exact line, from the *x64 Native Tools Command Prompt* (or after `VC\Auxiliary\Build\vcvars64.bat`):

    cl /nologo /O2 /EHsc /std:c++17 /MT /LD /DNDEBUG /DWIN32_LEAN_AND_MEAN /DNOMINMAX ^
       /I %SDK%\include /I %VULKAN_HEADERS%\include ^
       src\native\pzopt_ngx.cpp %SDK%\lib\Windows_x86_64\x64\nvsdk_ngx_s.lib ^
       /Fe:build\pzopt_ngx64.dll /Fo:build\ ^
       /link advapi32.lib user32.lib shell32.lib ole32.lib version.lib

303,104-byte PE32+ DLL exporting all 13 `pzngx_*` entry points; `dlss: ready` on the first launch with it and
NVIDIA's `nvngx_dlss.dll` in the game's `natives\`. Measurements in docs/plan-upscalers.md (2026-09-24).
Note: wrapping that `cl` line in PowerShell's `Start-Process -Wait` never returns even though `cl` exits 0 (a stray
child keeps the handle open) — redirect to a log and read the log.
Intel XeSS (Windows only, `libxess.dll`, a Vulkan API since XeSS 2) would take the same shape — its own
`pzngx_*`-style backend behind the same images and semaphores — and is not written yet.
