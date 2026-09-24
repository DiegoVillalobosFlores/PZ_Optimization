<#
.SYNOPSIS
Install, remove or inspect the PZ_Optimization class overrides on Windows from a release zip.

.DESCRIPTION
Standalone: needs Windows PowerShell 5.1 or newer. Nothing is compiled. The zip is fetched
from the GitHub releases ($env:GITHUB_TOKEN is used if set; the gh CLI if logged in);
-Zip skips the download. -From installs the same tree from an unpacked folder (no network);
a pzopt-classes folder next to this script (the Steam Workshop item layout) is used
automatically.

  .\install.ps1                                  # find the game, download the zip for its revision, install
  .\install.ps1 -Zip "$env:USERPROFILE\Downloads\pzopt-b0bbce05d5-classes.zip"
  .\install.ps1 -From "D:\SteamLibrary\steamapps\workshop\content\108600\<id>\mods\PZ_Optimization\42\pzopt-classes"
  .\install.ps1 -Dir "D:\SteamLibrary\steamapps\common\ProjectZomboid"
  .\install.ps1 -Status
  .\install.ps1 -Uninstall

Files written are recorded in <game dir>\pzopt-installed.txt (same format as the Linux
tools). projectzomboid.jar is never modified; the runtime guard turns the classes off, with
one console.txt line, if the game revision differs.

If scripts are blocked: powershell -ExecutionPolicy Bypass -File .\install.ps1
#>
[CmdletBinding()]
param(
  [string]$Dir,
  [string]$Zip,
  [string]$From,
  [string]$Tag,
  [switch]$Uninstall,
  [switch]$Status
)
$ErrorActionPreference = 'Stop'
$RepoSlug = 'xD3I/PZ_Optimization'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Fail($msg) { Write-Host "error: $msg" -ForegroundColor Red; exit 1 }

# --- locate the game -------------------------------------------------------------------

function Find-GameDir {
  $libs = @()
  $steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
  if ($steam) { $libs += $steam }
  $libs += "${env:ProgramFiles(x86)}\Steam", "$env:ProgramFiles\Steam"
  # [IO.Path]::Combine, not Join-Path: libraryfolders.vdf keeps libraries on unplugged drives
  # and Join-Path throws DriveNotFoundException for those instead of returning a path
  foreach ($lib in $libs) {
    $vdf = [IO.Path]::Combine($lib, 'steamapps\libraryfolders.vdf')
    if (Test-Path -LiteralPath $vdf) {
      foreach ($m in [regex]::Matches((Get-Content -LiteralPath $vdf -Raw), '"path"\s+"([^"]+)"')) {
        $libs += $m.Groups[1].Value.Replace('\\', '\')
      }
    }
  }
  foreach ($lib in $libs | Select-Object -Unique) {
    if (-not (Test-Path -LiteralPath $lib)) { continue }
    foreach ($c in @([IO.Path]::Combine($lib, 'steamapps\common\ProjectZomboid'), [IO.Path]::Combine($lib, 'steamapps\common\ProjectZomboid\projectzomboid'))) {
      if ((Test-Path -LiteralPath ([IO.Path]::Combine($c, 'projectzomboid.jar'))) -and (Test-Path -LiteralPath ([IO.Path]::Combine($c, 'ProjectZomboid64.json')))) { return $c }
    }
  }
  return $null
}

if (-not $Dir) { $Dir = Find-GameDir }
if (-not $Dir) { Fail 'game folder not found; pass -Dir <folder containing projectzomboid.jar>' }
$Jar = Join-Path $Dir 'projectzomboid.jar'
if (-not (Test-Path $Jar)) { Fail "no projectzomboid.jar in $Dir" }
$Json = Join-Path $Dir 'ProjectZomboid64.json'
$Manifest = Join-Path $Dir 'pzopt-installed.txt'

function Read-ZipEntry($zipPath, $entryName) {
  $z = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
  try {
    $e = $z.GetEntry($entryName)
    if (-not $e) { return $null }
    $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
    return $ms.ToArray()
  } finally { $z.Dispose() }
}
function Get-JarRevision {
  # zombie.GitVersion holds REVISION as a constant-pool string; no JDK needed
  $bytes = Read-ZipEntry $Jar 'zombie/GitVersion.class'
  if (-not $bytes) { return $null }
  $m = [regex]::Match([System.Text.Encoding]::ASCII.GetString($bytes), '\b[0-9a-f]{10}\b')
  if ($m.Success) { $m.Value } else { $null }
}
function Get-Sha256($path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower() }

# A launcher JSON that pzopt's AOT-cache mode (pzopt.AotCache) switched to its jar form goes back to the loose
# classes ("." first, no AOT options), and the jar and cache go: the loose files are about to change.
function Reset-Aot {
  if (Test-Path -LiteralPath $Json) {
    $j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
    $jar = 'pzopt/aot/pzopt.jar'
    $aot = @($j.vmArgs | Where-Object { $_ -like '-XX:AOTCache*' -or $_ -like '-Xlog:aot=info:file=pzopt/aot/*' })
    if ((@($j.classpath) -contains $jar) -or $aot.Count -gt 0) {
      $j.classpath = @('.') + @($j.classpath | Where-Object { $_ -ne '.' -and $_ -ne $jar })
      $j.vmArgs = @($j.vmArgs | Where-Object { $aot -notcontains $_ })
      [IO.File]::WriteAllText($Json, ($j | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
      Write-Host 'launcher: AOT-cache form put back to the loose classes'
    }
  }
  $aotDir = Join-Path $Dir 'pzopt\aot'
  if (Test-Path -LiteralPath $aotDir) { Remove-Item -LiteralPath $aotDir -Recurse -Force }
}
# Undo pzopt.GcChoice's launcher switch (marker -Dpzopt.gc=g1[,pause]): G1 back to ZGC, the pause target it added removed.
function Reset-Gc {
  if (-not (Test-Path -LiteralPath $Json)) { return }
  $j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
  $m = '-Dpzopt.gc=g1'; $mp = '-Dpzopt.gc=g1,pause'; $changed = $false
  $fix = {
    param($a)
    $a = @($a)
    if (-not (($a -contains $m) -or ($a -contains $mp))) { return ,$a }
    if ($a -contains $mp) { $a = @($a | Where-Object { $_ -notlike '-XX:MaxGCPauseMillis=*' }) }
    $a = @($a | Where-Object { $_ -ne $m -and $_ -ne $mp } | ForEach-Object { if ($_ -eq '-XX:+UseG1GC') { '-XX:+UseZGC' } else { $_ } })
    $script:gcChanged = $true
    return ,$a
  }
  # jitSteady (marker -Dpzopt.jit=steady): the JIT trap-limit flags it added
  $jm = '-Dpzopt.jit=steady'
  $fixJit = {
    param($a)
    $a = @($a)
    if (-not ($a -contains $jm)) { return ,$a }
    $a = @($a | Where-Object { $_ -ne $jm -and $_ -notlike '-XX:PerMethodTrapLimit=*' -and $_ -notlike '-XX:PerBytecodeTrapLimit=*' })
    $script:gcChanged = $true
    return ,$a
  }
  $script:gcChanged = $false
  if ($j.vmArgs) { $j.vmArgs = & $fixJit (& $fix $j.vmArgs) }
  foreach ($p in $j.PSObject.Properties) {
    if ($p.Value -is [psobject] -and $p.Value.PSObject.Properties['vmArgs']) { $p.Value.vmArgs = & $fixJit (& $fix $p.Value.vmArgs) }
  }
  if ($script:gcChanged) {
    [IO.File]::WriteAllText($Json, ($j | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
    Write-Host "launcher: pzopt's G1 switch / JIT flags undone (back to the launcher's own)"
  }
}
$Rev = Get-JarRevision

# --- status / uninstall ----------------------------------------------------------------

if ($Status) {
  Write-Host "game dir:      $Dir"
  Write-Host "game revision: $(if ($Rev) { $Rev } else { 'unknown' })"
  if (Test-Path $Manifest) {
    $lines = Get-Content $Manifest | Where-Object { $_ -and -not $_.StartsWith('#') }
    $for = (Get-Content $Manifest | Select-String '^# revision=(\S+)').Matches[0].Groups[1].Value
    Write-Host "installed:     yes, for $for ($($lines.Count) files)"
    $bad = $false
    foreach ($l in $lines) {
      $rel, $sha = $l -split ' ', 2
      $p = Join-Path $Dir $rel
      if (-not (Test-Path -LiteralPath $p)) { Write-Host "  MISSING  $rel"; $bad = $true }
      elseif ((Get-Sha256 $p) -ne $sha) { Write-Host "  MODIFIED $rel"; $bad = $true }
    }
    if (-not $bad) { Write-Host '  all files present and unchanged' }
  } else { Write-Host 'installed:     no' }
  $props = Join-Path $Dir 'pzopt.properties'
  if (Test-Path $props) { Write-Host 'pzopt.properties:'; Get-Content $props | ForEach-Object { "  $_" } }
  exit 0
}

if ($Uninstall) {
  Reset-Aot
  Reset-Gc
  $filesTxt = Join-Path $Dir 'pzopt-files.txt'
  if (Test-Path $Manifest) { $list = Get-Content $Manifest | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { ($_ -split ' ')[0] } }
  elseif (Test-Path $filesTxt) { $list = Get-Content $filesTxt | Where-Object { $_ } }
  else { Write-Host "not installed (no pzopt-installed.txt or pzopt-files.txt in $Dir)"; exit 0 }
  $n = 0
  foreach ($rel in $list) {
    $p = Join-Path $Dir $rel
    if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Force; $n++ }
    $d = Split-Path $p -Parent
    while ($d -and ($d.TrimEnd('\','/') -ne $Dir.TrimEnd('\','/')) -and (Test-Path -LiteralPath $d) -and -not (Get-ChildItem -LiteralPath $d -Force)) {
      Remove-Item -LiteralPath $d; $d = Split-Path $d -Parent
    }
  }
  Remove-Item -LiteralPath $Manifest, $filesTxt -Force -ErrorAction SilentlyContinue
  Write-Host "removed $n files; projectzomboid.jar was never modified"
  Write-Host "caches under $env:USERPROFILE\Zomboid\pzopt\ (anims, packs, framecap.ini, options.ini) can be deleted by hand"
  exit 0
}

# --- install ---------------------------------------------------------------------------

$running = Get-Process ProjectZomboid64 -ErrorAction SilentlyContinue | Where-Object { $_.Path -and (Split-Path $_.Path -Parent).TrimEnd('\','/') -eq $Dir.TrimEnd('\','/') }
if ($running) { Fail "the game is running from $Dir; close it first" }
if (Test-Path $Manifest) { Fail 'already installed (see -Status); run -Uninstall first' }
if (-not $Rev) { Fail "could not read the game revision from $Jar" }

Reset-Aot
# the launcher must search "." before the jar or loose classes never load
$cp = @((Get-Content $Json -Raw | ConvertFrom-Json).classpath)
if (($cp.IndexOf('.') -lt 0) -or ($cp.IndexOf('projectzomboid.jar') -lt 0) -or ($cp.IndexOf('.') -gt $cp.IndexOf('projectzomboid.jar'))) {
  Fail "$Json does not list `".`" before projectzomboid.jar on the classpath; loose classes would never load (found: $($cp -join ', '))"
}

$tmp = $null
if (-not $Zip -and -not $From) {
  $sibling = Join-Path $PSScriptRoot 'pzopt-classes'
  if (Test-Path (Join-Path $sibling 'pzopt\build-info.properties')) { $From = $sibling }
}
if ($From) {
  if (-not (Test-Path (Join-Path $From 'pzopt\build-info.properties'))) { Fail "$From is not an unpacked PZ_Optimization release (no pzopt\build-info.properties)" }
  Write-Host "installing from folder $From"
} elseif (-not $Zip) {
  $pattern = "pzopt-$Rev-classes.zip"
  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("pzopt-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
  New-Item -ItemType Directory -Path $tmp | Out-Null
  $gh = Get-Command gh -ErrorAction SilentlyContinue
  $ghOk = $false
  if ($gh) { & gh auth status 2>$null | Out-Null; $ghOk = ($LASTEXITCODE -eq 0) }
  if ($ghOk) {
    if (-not $Tag) {
      $Tag = (& gh release list -R $RepoSlug --json tagName -q '.[].tagName') | Where-Object { $_ -match "-$Rev(-|$)" } | Select-Object -First 1
      if (-not $Tag) { Fail "no release for game revision $Rev (your game is a build these classes were not built for)" }
    }
    Write-Host "downloading $pattern from release $Tag"
    & gh release download $Tag -R $RepoSlug -p $pattern -D $tmp
    if ($LASTEXITCODE -ne 0) { Fail 'gh release download failed' }
  } else {
    $h = @{ Accept = 'application/vnd.github+json' }
    if ($env:GITHUB_TOKEN) { $h.Authorization = "Bearer $env:GITHUB_TOKEN" }
    $rels = Invoke-RestMethod -Headers $h "https://api.github.com/repos/$RepoSlug/releases?per_page=50"
    $asset = $null
    # the list's order is by the tagged commit's date, not by publish date
    foreach ($r in ($rels | Sort-Object -Property published_at -Descending)) {
      if ($Tag -and $r.tag_name -ne $Tag) { continue }
      $a = $r.assets | Where-Object { $_.name -eq $pattern } | Select-Object -First 1
      if ($a) { $asset = $a; $Tag = $r.tag_name; break }
    }
    if (-not $asset) { Fail "no release has $pattern (your game revision $Rev is a build these classes were not built for)" }
    Write-Host "downloading $pattern from release $Tag"
    $h.Accept = 'application/octet-stream'
    Invoke-WebRequest -Headers $h -Uri $asset.url -OutFile (Join-Path $tmp $pattern)
  }
  $Zip = Join-Path $tmp $pattern
}
if ($From) {
  $Source = $From
  $fromRoot = (Resolve-Path -LiteralPath $From).Path.TrimEnd('\','/')
  # relative paths with '/' separators, the manifest format the zip path produces
  $files = @(Get-ChildItem -LiteralPath $fromRoot -Recurse -File | ForEach-Object { $_.FullName.Substring($fromRoot.Length + 1).Replace('\', '/') } | Sort-Object)
  $bi = Get-Content -Raw (Join-Path $fromRoot 'pzopt\build-info.properties')
} else {
  if (-not (Test-Path -LiteralPath $Zip)) { Fail "zip not found: $Zip" }
  $Source = $Zip
  $z = [System.IO.Compression.ZipFile]::OpenRead($Zip)
  try { $files = @($z.Entries | Where-Object { -not $_.FullName.EndsWith('/') } | ForEach-Object { $_.FullName } | Sort-Object) } finally { $z.Dispose() }
  if ($files -notcontains 'pzopt/build-info.properties') { Fail "$Zip is not a PZ_Optimization release zip" }
  $bi = [System.Text.Encoding]::UTF8.GetString((Read-ZipEntry $Zip 'pzopt/build-info.properties'))
}
$zipRev = ([regex]::Match($bi, '(?m)^revision=(\S+)')).Groups[1].Value
if ($zipRev -ne $Rev) { Fail "$Source was built for game revision $zipRev but this game is $Rev; the classes would disable themselves. Get the build for $Rev" }
foreach ($rel in $files) {
  $p = Join-Path $Dir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
  if (Test-Path -LiteralPath $p) { Fail "refusing to overwrite existing file: $p (a previous install? run -Uninstall)" }
}

$jarBefore = Get-Sha256 $Jar
if ($From) {
  foreach ($rel in $files) {
    $dst = Join-Path $Dir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
    New-Item -ItemType Directory -Force -Path (Split-Path $dst -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $fromRoot ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)) -Destination $dst
  }
} else {
  [System.IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dir)
}
$out = @('# files written by install.ps1 - do not edit', "# revision=$zipRev installed=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))")
foreach ($rel in $files) { $out += "$rel $(Get-Sha256 (Join-Path $Dir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)))" }
[System.IO.File]::WriteAllLines($Manifest, $out)
if ((Get-Sha256 $Jar) -ne $jarBefore) { Fail 'projectzomboid.jar changed during install (this should be impossible)' }
if ($tmp) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }

Write-Host "installed $($files.Count) files into $Dir for game revision $zipRev; projectzomboid.jar untouched"
Write-Host "launch from Steam; $env:USERPROFILE\Zomboid\console.txt shows one '[pzopt] loaded override ... active' line per class"
Write-Host "settings: Options > Optimizations in the game, or $Dir\pzopt.properties"
