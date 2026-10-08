<#
.SYNOPSIS
    Milestone 1 Deliverable Verification Script for Android RDP Client
.DESCRIPTION
    Validates Java 17, Android SDK 34, NDK r25c, builds the project via gradlew.bat,
    and inspects the compiled APK artifact for manifest, dex, and native .so libraries.
.PARAMETER ApkPath
    Relative or absolute path to the debug APK deliverable.
.PARAMETER SdkRoot
    Path to the Android SDK installation.
.PARAMETER SkipBuild
    If specified, skips running 'gradlew assembleDebug' and verifies existing artifact.
.PARAMETER RequireNativeLibs
    If specified (default $true), requires lib/arm64-v8a/*.so and lib/x86_64/*.so in APK.
#>

[CmdletBinding()]
param(
    [string]$ApkPath = "app/build/outputs/apk/debug/app-debug.apk",
    [string]$SdkRoot = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "C:\Android\sdk" }),
    [switch]$SkipBuild,
    [bool]$RequireNativeLibs = $true
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$message) {
    Write-Host "`n[STEP] $message" -ForegroundColor Cyan
}

function Write-Pass([string]$message) {
    Write-Host "  [PASS] $message" -ForegroundColor Green
}

function Write-Fail([string]$message) {
    Write-Host "  [FAIL] $message" -ForegroundColor Red
}

Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "   ANDROID RDP CLIENT - DELIVERABLE VERIFICATION SUITE" -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

# Auto-detect or refresh environment from Machine registry if not in current process
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = [System.Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
}
if (-not $env:JAVA_HOME -and (Test-Path "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot")) {
    $env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
}
if ($env:JAVA_HOME -and ($env:PATH -notlike "*$env:JAVA_HOME\bin*")) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
if (-not $env:ANDROID_HOME) {
    $env:ANDROID_HOME = [System.Environment]::GetEnvironmentVariable('ANDROID_HOME', 'Machine')
}
if (-not $env:ANDROID_HOME -and (Test-Path "C:\Android\sdk")) {
    $env:ANDROID_HOME = "C:\Android\sdk"
}
if ($env:ANDROID_HOME) {
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    $toolDirs = @(
        "$env:ANDROID_HOME\cmdline-tools\latest\bin",
        "$env:ANDROID_HOME\platform-tools",
        "$env:ANDROID_HOME\build-tools\34.0.0",
        "$env:ANDROID_HOME\cmake\3.22.1\bin"
    )
    foreach ($td in $toolDirs) {
        if ((Test-Path $td) -and ($env:PATH -notlike "*$td*")) {
            $env:PATH = "$td;$env:PATH"
        }
    }
}

# -----------------------------------------------------------------------------
# STAGE 1: Verify Java 17 Toolchain
# -----------------------------------------------------------------------------
Write-Step "1/6: Verifying Java 17 Development Kit..."

$javaCmd = Get-Command "java.exe" -ErrorAction SilentlyContinue
if (-not $javaCmd) {
    Write-Fail "java.exe not found in PATH."
    exit 1
}

$javaVerText = (cmd.exe /c "java -version 2>&1") -join "`n"
if ($javaVerText -match 'version "(17\.[0-9]+(\.[0-9]+)?)') {
    Write-Pass "Java 17 detected: $($Matches[1]) (Binary: $($javaCmd.Source))"
} else {
    Write-Fail "Java 17 required, but found:`n$javaVerText"
    exit 1
}

# -----------------------------------------------------------------------------
# STAGE 2: Verify Android SDK 34 and NDK r25c
# -----------------------------------------------------------------------------
Write-Step "2/6: Verifying Android SDK and NDK Provisioning..."

if (-not (Test-Path $SdkRoot)) {
    Write-Fail "Android SDK root not found at '$SdkRoot'."
    exit 1
}
Write-Pass "Android SDK Root: $SdkRoot"

$androidJar = Join-Path $SdkRoot "platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) {
    Write-Fail "Android Platform 34 missing: '$androidJar'."
    exit 1
}
Write-Pass "Platform android-34 verified."

$aapt2Path = Join-Path $SdkRoot "build-tools\34.0.0\aapt2.exe"
if (-not (Test-Path $aapt2Path)) {
    Write-Fail "AAPT2 build tool missing: '$aapt2Path'."
    exit 1
}
Write-Pass "Build-tools 34.0.0 (aapt2.exe) verified."

$ndkPath = Join-Path $SdkRoot "ndk\25.2.9519653"
if (-not (Test-Path $ndkPath)) {
    Write-Fail "NDK 25.2.9519653 missing: '$ndkPath'."
    exit 1
}
Write-Pass "Android NDK r25c verified: $ndkPath"

# -----------------------------------------------------------------------------
# STAGE 3: Execute Gradle Build (gradlew.bat assembleDebug)
# -----------------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Step "3/6: Executing Gradle Automated Build (assembleDebug)..."

    $gradlew = ".\gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        Write-Fail "gradlew.bat not found in working directory ($(Get-Location))."
        exit 1
    }

    $startTime = Get-Date
    Write-Host "  Invoking: $gradlew assembleDebug --stacktrace --no-daemon" -ForegroundColor Gray
    & $gradlew assembleDebug --stacktrace --no-daemon
    $buildExitCode = $LASTEXITCODE
    $duration = (Get-Date) - $startTime

    if ($buildExitCode -ne 0) {
        Write-Fail "Build failed with exit code $buildExitCode! (Duration: $($duration.TotalSeconds)s)"
        exit $buildExitCode
    }
    Write-Pass "Build executed successfully with exit code 0! (Duration: $([math]::Round($duration.TotalSeconds, 2))s)"
} else {
    Write-Step "3/6: Skipping Gradle build (-SkipBuild specified)..."
}

# -----------------------------------------------------------------------------
# STAGE 4: Deliverable APK Existence & Sanity Check
# -----------------------------------------------------------------------------
Write-Step "4/6: Verifying Generated APK Artifact..."

if (-not (Test-Path $ApkPath)) {
    Write-Fail "APK artifact not found at expected path: '$ApkPath'!"
    exit 1
}

$apkItem = Get-Item $ApkPath
$apkSizeMB = [math]::Round($apkItem.Length / 1MB, 2)
if ($apkItem.Length -lt 1000000) {
    Write-Fail "APK size is abnormally small ($($apkItem.Length) bytes). Potential corrupted package."
    exit 1
}
Write-Pass "APK artifact found: $($apkItem.FullName)"
Write-Pass "APK file size: $apkSizeMB MB ($($apkItem.Length) bytes)"

# -----------------------------------------------------------------------------
# STAGE 5: Inspect Internal APK Zip Structure
# -----------------------------------------------------------------------------
Write-Step "5/6: Inspecting APK Internal Structure..."

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkItem.FullName)

try {
    # 5.1 Bytecode Check
    $dexEntries = $zip.Entries | Where-Object { $_.FullName -like "classes*.dex" }
    if ($dexEntries.Count -eq 0) {
        Write-Fail "No compiled Dalvik bytecode (classes*.dex) found in APK!"
        exit 1
    }
    Write-Pass "DEX bytecode present ($($dexEntries.Count) DEX file(s))."

    # 5.2 Manifest Check
    $manifestEntry = $zip.Entries | Where-Object { $_.FullName -eq "AndroidManifest.xml" }
    if (-not $manifestEntry) {
        Write-Fail "AndroidManifest.xml missing from APK archive!"
        exit 1
    }
    Write-Pass "Compiled AndroidManifest.xml verified."

    # 5.3 Resources Table
    $arscEntry = $zip.Entries | Where-Object { $_.FullName -eq "resources.arsc" }
    if (-not $arscEntry) {
        Write-Fail "resources.arsc missing from APK archive!"
        exit 1
    }
    Write-Pass "resources.arsc resource table verified."

    # 5.4 Native Shared Libraries Check
    if ($RequireNativeLibs) {
        $arm64Libs = $zip.Entries | Where-Object { $_.FullName -like "lib/arm64-v8a/*.so" }
        $x8664Libs = $zip.Entries | Where-Object { $_.FullName -like "lib/x86_64/*.so" }

        if ($arm64Libs.Count -eq 0) {
            Write-Fail "Missing required arm64-v8a native libraries (lib/arm64-v8a/*.so)!"
            exit 1
        }
        Write-Pass "arm64-v8a native libraries verified ($($arm64Libs.Count) .so files):"
        $arm64Libs | ForEach-Object { Write-Host "       -> $($_.FullName) ($([math]::Round($_.Length / 1KB, 1)) KB)" -ForegroundColor DarkGray }

        if ($x8664Libs.Count -eq 0) {
            Write-Fail "Missing required x86_64 native libraries (lib/x86_64/*.so)!"
            exit 1
        }
        Write-Pass "x86_64 native libraries verified ($($x8664Libs.Count) .so files):"
        $x8664Libs | ForEach-Object { Write-Host "       -> $($_.FullName) ($([math]::Round($_.Length / 1KB, 1)) KB)" -ForegroundColor DarkGray }
    } else {
        Write-Host "  [INFO] Native library audit skipped (-RequireNativeLibs:`$false)." -ForegroundColor Yellow
    }
} finally {
    $zip.Dispose()
}

# -----------------------------------------------------------------------------
# STAGE 6: AAPT2 Package Badging & Permissions Audit
# -----------------------------------------------------------------------------
Write-Step "6/6: Verifying Package Metadata & Permissions via AAPT2..."

$badgingLines = cmd.exe /c "`"$aapt2Path`" dump badging `"$($apkItem.FullName)`" 2>&1"
$badgingText = $badgingLines -join "`n"

# Verify package name
if ($badgingText -match "package: name='([^']+)'") {
    $pkgName = $Matches[1]
    if ($pkgName -eq "com.rdp.client") {
        Write-Pass "Package name matches contract: '$pkgName'"
    } else {
        Write-Fail "Unexpected package name: '$pkgName' (Expected: 'com.rdp.client')"
        exit 1
    }
} else {
    Write-Fail "Unable to parse package name from AAPT2 dump badging."
    exit 1
}

# Verify launcher activity
if ($badgingText -match "launchable-activity: name='([^']+)'") {
    $launchActivity = $Matches[1]
    if ($launchActivity -eq "com.rdp.client.MainActivity" -or $launchActivity -eq "com.rdp.client.ui.home.HomeActivity") {
        Write-Pass "Launchable activity matches contract: '$launchActivity'"
    } else {
        Write-Fail "Unexpected launchable activity: '$launchActivity' (Expected: 'com.rdp.client.ui.home.HomeActivity' or 'com.rdp.client.MainActivity')"
        exit 1
    }
} else {
    Write-Fail "No launchable activity found in APK manifest badging!"
    exit 1
}

# Verify permissions
$requiredPermissions = @(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.WAKE_LOCK"
)

foreach ($perm in $requiredPermissions) {
    if ($badgingText -match [regex]::Escape("uses-permission: name='$perm'")) {
        Write-Pass "Permission verified: $perm"
    } else {
        Write-Fail "Required permission missing from manifest: $perm"
        exit 1
    }
}

# -----------------------------------------------------------------------------
# CONCLUSION: All Checks Succeeded
# -----------------------------------------------------------------------------
Write-Host "`n================================================================" -ForegroundColor Green
Write-Host "   VERIFICATION SUCCESS: ALL ACCEPTANCE CRITERIA SATISFIED!    " -ForegroundColor Green
Write-Host "   APK Deliverable: $($apkItem.FullName)                       " -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
exit 0
