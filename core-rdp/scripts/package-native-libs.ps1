<#
.SYNOPSIS
  Packages real FreeRDP native libraries into the app and verifies Gradle packaged them.

.DESCRIPTION
  LibFreeRDP.java statically loads, in order:
      winpr3, freerdp3, freerdp-client3, freerdp-android
  i.e. it needs these exact files per ABI:
      libwinpr3.so  libfreerdp3.so  libfreerdp-client3.so  libfreerdp-android.so

  As of this writing NO .so exists anywhere on this machine (no NDK, no FreeRDP
  sources, no prebuilt binaries) - see .agents/native_finish/handoff.md for the
  full verdict and the exact upstream build steps. This script therefore does NOT
  build or fabricate anything: it only copies GENUINE .so files that you already
  built (or downloaded from a trusted source) into app/src/main/jniLibs/<abi>/ and
  then verifies the APK packaging pipeline picked them up.

  ASCII-only by design: Windows PowerShell 5.1 can misread BOM-less UTF-8 and
  non-ASCII bytes (e.g. em dashes) can surface as curly quotes, which terminate
  PowerShell strings early.

.PARAMETER SourceRoot
  Root of an existing FreeRDP Android build output, laid out as
  <SourceRoot>/<abi>/*.so  (abis: arm64-v8a, armeabi-v7a, x86, x86_64),
  OR a flat directory containing *.so (applied to every ABI you pass).

.PARAMETER Abis
  ABIs to package. Defaults to arm64-v8a (Play-store minimum for this app).

.PARAMETER SkipBuild
  Skip the trailing ':app:assembleDebug' + merged_native_libs verification.

.EXAMPLE
  .\core-rdp\scripts\package-native-libs.ps1 -SourceRoot D:\freerdp-build\libs -Abis arm64-v8a

.EXAMPLE
  # Package then verify (runs Gradle):
  .\core-rdp\scripts\package-native-libs.ps1 -SourceRoot D:\freerdp-build\libs
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,

    [string[]]$Abis = @('arm64-v8a'),

    [switch]$SkipBuild,

    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

$requiredLibs = @(
    'libwinpr3.so',
    'libfreerdp3.so',
    'libfreerdp-client3.so',
    'libfreerdp-android.so'
)

# Every .so the four required libraries DT_NEEDED at runtime must ship too:
# libcrypto.so.NN / libssl.so.NN (OpenSSL TLS), libavcodec/libavutil (FFmpeg),
# libopus, libopenh264, libpng, libwebp, libjpeg, libcjson, liburiparser, etc.
# The versioned sonames (e.g. libcrypto.so.3) are matched by lib*.so* and must
# keep their exact file names - the Android linker resolves DT_NEEDED by name.
$depPattern = 'lib*.so*'

if (-not (Test-Path $SourceRoot)) {
    throw "SourceRoot not found: $SourceRoot - build FreeRDP first (see handoff for exact steps)."
}

$jniLibsRoot = Join-Path $RepoRoot 'app\src\main\jniLibs'
$copied = 0

foreach ($abi in $Abis) {
    # Accept <SourceRoot>\<abi>\*.so (CMake/prefab style) or a flat <SourceRoot>\*.so.
    $abiDir = Join-Path $SourceRoot $abi
    if (Test-Path $abiDir) {
        $srcDir = $abiDir
    } else {
        $srcDir = $SourceRoot
    }

    $found = Get-ChildItem $srcDir -Filter $depPattern -File -ErrorAction SilentlyContinue
    if (-not $found) {
        Write-Warning "No .so files under $srcDir - skipping ABI $abi."
        continue
    }

    $dst = Join-Path $jniLibsRoot $abi
    New-Item -ItemType Directory -Force -Path $dst | Out-Null

    # Copy every shared library (the four required plus their DT_NEEDED deps).
    foreach ($f in $found) {
        # Sanity: real ELF shared object, not a stub. ELF magic = 7F 45 4C 46.
        $bytes = [System.IO.File]::ReadAllBytes($f.FullName)[0..3]
        if ($bytes[0] -ne 0x7F -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4C -or $bytes[3] -ne 0x46) {
            throw "$($f.FullName) is not an ELF shared object - refusing to package (no fabricated binaries)."
        }
        Copy-Item -Force $f.FullName (Join-Path $dst $f.Name)
        $copied++
        Write-Host "packaged  $abi\$($f.Name)" -ForegroundColor Green
    }

    # The four System.loadLibrary names must all be present now.
    foreach ($lib in $requiredLibs) {
        if (-not (Test-Path (Join-Path $dst $lib))) {
            Write-Warning "MISSING required library $lib for $abi (looked in $srcDir). System.loadLibrary will fail on-device until it exists."
        }
    }
}

if ($copied -eq 0) {
    throw "Nothing was packaged: no genuine .so found. Aborting (nothing fabricated)."
}

$missingAny = $false
foreach ($abi in $Abis) {
    foreach ($lib in $requiredLibs) {
        if (-not (Test-Path (Join-Path $jniLibsRoot (Join-Path $abi $lib)))) { $missingAny = $true }
    }
}
if ($missingAny) {
    Write-Warning "jniLibs is INCOMPLETE for one or more ABIs: LibFreeRDP will report isNativeLoaded()=false and every connect() will fail with RdpState Failed(1001)."
}

if ($SkipBuild) {
    Write-Host "Copied $copied file(s). Skipped Gradle verification (-SkipBuild)."
    return
}

# --- Verify Gradle packaging pipeline -------------------------------------
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
Push-Location $RepoRoot
try {
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "app:assembleDebug failed (exit $LASTEXITCODE)." }

    $merged = Join-Path $RepoRoot 'app\build\intermediates\merged_native_libs\debug\mergeDebugNativeLibs\out\lib'
    $ok = $true
    foreach ($abi in $Abis) {
        foreach ($lib in $requiredLibs) {
            $p = Join-Path $merged (Join-Path $abi $lib)
            if (Test-Path $p) {
                Write-Host "verified   merged_native_libs\$abi\$lib" -ForegroundColor Green
            } else {
                Write-Warning "NOT PACKAGED: merged_native_libs\$abi\$lib"
                $ok = $false
            }
        }
    }
    if (-not $ok) { throw 'Packaging verification failed: some libraries did not reach mergeDebugNativeLibs.' }
    Write-Host "Packaging verified: all required .so reach merged_native_libs." -ForegroundColor Green
} finally {
    Pop-Location
}
