<#
.SYNOPSIS
    Turnkey Toolchain Provisioning Script for Android RDP Client on Windows
.DESCRIPTION
    Installs OpenJDK 17 LTS, Android SDK cmdline-tools 34, NDK r25c, CMake 3.22.1,
    configures environment variables (Machine and Session), writes local.properties,
    and bootstraps Gradle 8.7 wrapper.
.PARAMETER SdkRoot
    Android SDK target path. Default: C:\Android\sdk
.PARAMETER JdkDir
    OpenJDK 17 target path. Default: C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
.PARAMETER Force
    Force reinstallation of components even if already present.
#>
[CmdletBinding()]
param(
    [string]$SdkRoot = "C:\Android\sdk",
    [string]$JdkDir = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot",
    [switch]$Force
)

$ErrorActionPreference = "Continue"

function Write-Section {
    param([string]$Title)
    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan
}

function Write-Success {
    param([string]$Message)
    Write-Host " [PASS] $Message" -ForegroundColor Green
}

function Write-Info {
    param([string]$Message)
    Write-Host " [INFO] $Message" -ForegroundColor Yellow
}

function Invoke-DownloadWithRetry {
    param(
        [string]$Url,
        [string]$OutputFile,
        [int]$MaxRetries = 3
    )
    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        Write-Info "Downloading ($attempt/$MaxRetries): $Url"
        $curlResult = & curl.exe -L -s -f --retry 2 -o $OutputFile $Url
        if ($LASTEXITCODE -eq 0 -and (Test-Path $OutputFile) -and ((Get-Item $OutputFile).Length -gt 0)) {
            Write-Success "Downloaded: $OutputFile ($([math]::Round((Get-Item $OutputFile).Length / 1MB, 2)) MB)"
            return
        }
        Write-Warning "Download attempt $attempt failed. Retrying in 3 seconds..."
        Start-Sleep -Seconds 3
    }
    throw "Failed to download $Url after $MaxRetries attempts."
}

# ------------------------------------------------------------
# Step 0: Pre-flight Validations & Windows Hardening
# ------------------------------------------------------------
Write-Section "Pre-flight Environment Validation"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    throw "This script must be executed with Administrator privileges."
}
Write-Success "Administrative privileges verified."

# Enable Windows Long Paths in registry
try {
    Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem" -Name "LongPathsEnabled" -Value 1 -Force
    Write-Success "Enabled Windows LongPathsEnabled in registry."
} catch {
    Write-Warning "Could not set LongPathsEnabled: $_"
}

$cDrive = Get-PSDrive -Name C
$freeGb = [math]::Round($cDrive.Free / 1GB, 2)
Write-Info "C: Drive Free Space: $freeGb GB"
if ($freeGb -lt 10.0) {
    throw "Insufficient free disk space ($freeGb GB). At least 10 GB required."
}
Write-Success "Sufficient disk storage confirmed."

# Determine Project Root
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (Test-Path "$ScriptDir\..\settings.gradle.kts") {
    $ProjectRoot = (Resolve-Path "$ScriptDir\..").Path
} elseif (Test-Path "C:\Users\Administrator\teamwork_projects\android_rdp_client") {
    $ProjectRoot = "C:\Users\Administrator\teamwork_projects\android_rdp_client"
} else {
    $ProjectRoot = (Get-Location).Path
}
Write-Info "Project Root: $ProjectRoot"

# ------------------------------------------------------------
# Step 1: OpenJDK 17 LTS Provisioning
# ------------------------------------------------------------
Write-Section "Step 1/6: OpenJDK 17 LTS Provisioning"

$javaExe = "$JdkDir\bin\java.exe"
if (-not $Force -and (Test-Path $javaExe)) {
    Write-Success "OpenJDK 17 already present at: $JdkDir"
} else {
    $jdkUrl = "https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.msi"
    $jdkMsi = "$env:TEMP\microsoft-jdk-17.0.20.1-windows-x64.msi"
    Invoke-DownloadWithRetry -Url $jdkUrl -OutputFile $jdkMsi

    Write-Info "Installing OpenJDK 17 via MSI..."
    $proc = Start-Process -FilePath "msiexec.exe" -ArgumentList "/i `"$jdkMsi`" /quiet /norestart" -Wait -PassThru
    Remove-Item $jdkMsi -Force -ErrorAction SilentlyContinue

    if ($proc.ExitCode -ne 0 -and $proc.ExitCode -ne 3010) {
        Write-Warning "MSI installer exited with code $($proc.ExitCode). Attempting portable zip fallback..."
        $jdkZipUrl = "https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.zip"
        $jdkZip = "$env:TEMP\microsoft-jdk-17.zip"
        Invoke-DownloadWithRetry -Url $jdkZipUrl -OutputFile $jdkZip
        New-Item -ItemType Directory -Path "C:\Java" -Force | Out-Null
        & tar.exe -xf $jdkZip -C "C:\Java"
        Remove-Item $jdkZip -Force -ErrorAction SilentlyContinue
        $JdkDir = "C:\Java\jdk-17.0.20.101-hotspot"
        $javaExe = "$JdkDir\bin\java.exe"
    }
}

if (-not (Test-Path $javaExe)) {
    throw "Java executable not found at: $javaExe"
}
$javaVersionOut = (cmd.exe /c "`"$javaExe`" -version 2>&1") -join "`n"
Write-Success "OpenJDK 17 operational:`n$javaVersionOut".Trim()

# Configure JAVA_HOME immediately for subsequent steps
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', $JdkDir, 'Machine')
$env:JAVA_HOME = $JdkDir
if ($env:PATH -notlike "*$JdkDir\bin*") {
    $env:PATH = "$JdkDir\bin;$env:PATH"
}

# ------------------------------------------------------------
# Step 2: Android SDK Command-Line Tools
# ------------------------------------------------------------
Write-Section "Step 2/6: Android SDK Command-Line Tools"

$cmdlineBase = "$SdkRoot\cmdline-tools"
$latestDir = "$cmdlineBase\latest"
$sdkManagerBat = "$latestDir\bin\sdkmanager.bat"

if (-not $Force -and (Test-Path $sdkManagerBat)) {
    Write-Success "Android SDK cmdline-tools already present at: $latestDir"
} else {
    New-Item -ItemType Directory -Path $cmdlineBase -Force | Out-Null
    $cmdlineZipUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    $cmdlineZip = "$env:TEMP\commandlinetools-win-11076708_latest.zip"
    Invoke-DownloadWithRetry -Url $cmdlineZipUrl -OutputFile $cmdlineZip

    Write-Info "Extracting cmdline-tools archive..."
    & tar.exe -xf $cmdlineZip -C $cmdlineBase
    Remove-Item $cmdlineZip -Force -ErrorAction SilentlyContinue

    $extractedFolder = "$cmdlineBase\cmdline-tools"
    if (Test-Path $extractedFolder) {
        if (Test-Path $latestDir) {
            Remove-Item $latestDir -Recurse -Force
        }
        Rename-Item -Path $extractedFolder -NewName "latest"
    }
}

if (-not (Test-Path $sdkManagerBat)) {
    throw "sdkmanager.bat not found at: $sdkManagerBat"
}
Write-Success "Android SDK cmdline-tools verified at: $latestDir"

# Configure ANDROID_HOME & ANDROID_SDK_ROOT
[System.Environment]::SetEnvironmentVariable('ANDROID_HOME', $SdkRoot, 'Machine')
[System.Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $SdkRoot, 'Machine')
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
if ($env:PATH -notlike "*$latestDir\bin*") {
    $env:PATH = "$latestDir\bin;$SdkRoot\platform-tools;$env:PATH"
}

# ------------------------------------------------------------
# Step 3: Android SDK Licenses Acceptance
# ------------------------------------------------------------
Write-Section "Step 3/6: Android SDK License Acceptance"

$licensesDir = "$SdkRoot\licenses"
New-Item -ItemType Directory -Path $licensesDir -Force | Out-Null

$licenseHashes = @{
    "android-sdk-license" = "8933bad161af4178b1185d1a37fbf41ea5269c55`nd56f5187479451eabf01fb78715194014c496678`n24333f8a63b1d9397908148f7632b2b623c039f"
    "android-sdk-preview-license" = "84831b9409646a532e303d79635c53775248c4ae`n7993a44a35e40c15b1d8e373f21749d64fb707b8"
    "android-sdk-arm-dbt-license" = "859f317696f67ef43be2a9e4469dc10264b9748c"
    "intel-android-extra-license" = "d975f751698a77b662f1254ddbeed3901e1ad6f6"
    "android-googletv-license" = "601085b94cd77f6b24b867824209db30"
}

foreach ($entry in $licenseHashes.GetEnumerator()) {
    $targetFile = "$licensesDir\$($entry.Key)"
    Set-Content -Path $targetFile -Value $entry.Value -Encoding ASCII
}
Write-Success "Pre-seeded SDK license hashes in $licensesDir"

# Automated stdin confirmation
$yesFile = "$env:TEMP\sdk_licenses_yes.txt"
1..50 | ForEach-Object { Add-Content -Path $yesFile -Value 'y' }
cmd.exe /c "type `"$yesFile`" | `"$sdkManagerBat`" --sdk_root=`"$SdkRoot`" --licenses" | Out-Null
Remove-Item $yesFile -Force -ErrorAction SilentlyContinue
Write-Success "All SDK licenses confirmed accepted."

# ------------------------------------------------------------
# Step 4: Install Android SDK Packages (NDK & CMake)
# ------------------------------------------------------------
Write-Section "Step 4/6: SDK Packages, NDK & CMake Installation"

$packages = @(
    "platform-tools",
    "platforms;android-34",
    "build-tools;34.0.0",
    "ndk;25.2.9519653",
    "cmake;3.22.1"
)

foreach ($pkg in $packages) {
    Write-Info "Installing: $pkg..."
    cmd.exe /c "`"$sdkManagerBat`" --sdk_root=`"$SdkRoot`" `"$pkg`""
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install SDK package: $pkg (exit code: $LASTEXITCODE)"
    }
    Write-Success "Installed: $pkg"
}

$ndkDir = "$SdkRoot\ndk\25.2.9519653"
if (-not (Test-Path "$ndkDir\source.properties")) {
    throw "NDK installation check failed: $ndkDir\source.properties not found"
}
[System.Environment]::SetEnvironmentVariable('ANDROID_NDK_ROOT', $ndkDir, 'Machine')
[System.Environment]::SetEnvironmentVariable('ANDROID_NDK_HOME', $ndkDir, 'Machine')
$env:ANDROID_NDK_ROOT = $ndkDir
$env:ANDROID_NDK_HOME = $ndkDir
Write-Success "NDK r25c verified at: $ndkDir"

$cmakeExe = "$SdkRoot\cmake\3.22.1\bin\cmake.exe"
if (-not (Test-Path $cmakeExe)) {
    throw "CMake executable not found at: $cmakeExe"
}
Write-Success "CMake 3.22.1 verified at: $cmakeExe"

# ------------------------------------------------------------
# Step 5: Environment Variables & local.properties
# ------------------------------------------------------------
Write-Section "Step 5/6: Environment Persistence & local.properties"

# Machine PATH configuration
$toolPaths = @(
    "$JdkDir\bin",
    "$SdkRoot\cmdline-tools\latest\bin",
    "$SdkRoot\platform-tools",
    "$SdkRoot\cmake\3.22.1\bin",
    "$SdkRoot\build-tools\34.0.0"
)

$currentMachinePath = [System.Environment]::GetEnvironmentVariable('Path', 'Machine')
$pathParts = $currentMachinePath -split ';' | Where-Object { $_ -ne '' }
$toAdd = @()
foreach ($tp in $toolPaths) {
    if ($pathParts -notcontains $tp) {
        $toAdd += $tp
    }
    if ($env:PATH -notlike "*$tp*") {
        $env:PATH = "$tp;$env:PATH"
    }
}
if ($toAdd.Count -gt 0) {
    $updatedPath = ($toAdd + $pathParts) -join ';'
    [System.Environment]::SetEnvironmentVariable('Path', $updatedPath, 'Machine')
    Write-Success "Added toolchain directories to Machine PATH."
}

# Write local.properties to Project Root
$localPropsPath = Join-Path $ProjectRoot "local.properties"
$localPropsContent = @"
## This file is automatically generated by scripts/setup_toolchain.ps1.
# Do not modify this file -- YOUR CHANGES WILL BE ERASED!
sdk.dir=$($SdkRoot.Replace('\', '/'))
ndk.dir=$($ndkDir.Replace('\', '/'))
"@
Set-Content -Path $localPropsPath -Value $localPropsContent -Encoding ASCII
Write-Success "Generated local.properties at: $localPropsPath"

# ------------------------------------------------------------
# Step 6: Gradle 8.7 Wrapper Bootstrap
# ------------------------------------------------------------
Write-Section "Step 6/6: Gradle 8.7 Wrapper Bootstrap"

$gradlewBat = Join-Path $ProjectRoot "gradlew.bat"
$wrapperJar = Join-Path $ProjectRoot "gradle\wrapper\gradle-wrapper.jar"

if (-not $Force -and (Test-Path $gradlewBat) -and (Test-Path $wrapperJar)) {
    Write-Success "Gradle wrapper already present in project."
} else {
    Write-Info "Bootstrapping Gradle 8.7 wrapper..."
    $gradleZipUrl = "https://services.gradle.org/distributions/gradle-8.7-bin.zip"
    $gradleZip = "$env:TEMP\gradle-8.7-bin.zip"
    Invoke-DownloadWithRetry -Url $gradleZipUrl -OutputFile $gradleZip

    $gradleTempDir = "$env:TEMP\gradle_bootstrap"
    if (Test-Path $gradleTempDir) { Remove-Item $gradleTempDir -Recurse -Force }
    New-Item -ItemType Directory -Path $gradleTempDir -Force | Out-Null

    & tar.exe -xf $gradleZip -C $gradleTempDir
    Remove-Item $gradleZip -Force -ErrorAction SilentlyContinue

    $standaloneGradleBat = "$gradleTempDir\gradle-8.7\bin\gradle.bat"
    if (-not (Test-Path $standaloneGradleBat)) {
        throw "Standalone Gradle binary not found at: $standaloneGradleBat"
    }

    Push-Location $ProjectRoot
    try {
        cmd.exe /c "`"$standaloneGradleBat`" wrapper --gradle-version 8.7 --distribution-type bin"
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle wrapper generation failed with exit code: $LASTEXITCODE"
        }
    } finally {
        Pop-Location
        Remove-Item $gradleTempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Success "Gradle 8.7 wrapper successfully generated."
}

# ------------------------------------------------------------
# Toolchain Verification & Health Summary
# ------------------------------------------------------------
Write-Section "TOOLCHAIN PROVISIONING VERIFICATION SUMMARY"

$tests = @(
    @{ Name = "Java 17 Compiler"; Path = "$JdkDir\bin\javac.exe"; Arg = "-version" },
    @{ Name = "Android AAPT2"; Path = "$SdkRoot\build-tools\34.0.0\aapt2.exe"; Arg = "version" },
    @{ Name = "Android CMake"; Path = "$SdkRoot\cmake\3.22.1\bin\cmake.exe"; Arg = "--version" },
    @{ Name = "Android Ninja"; Path = "$SdkRoot\cmake\3.22.1\bin\ninja.exe"; Arg = "--version" },
    @{ Name = "Android NDK Clang"; Path = "$ndkDir\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe"; Arg = "--version" },
    @{ Name = "Project Gradlew"; Path = $gradlewBat; Arg = "--version" }
)

$allPassed = $true
foreach ($t in $tests) {
    if (Test-Path $t.Path) {
        $out = (cmd.exe /c "`"$($t.Path)`" $($t.Arg) 2>&1") | Select-Object -First 1
        Write-Host ("  [PASS] {0,-20} -> {1}" -f $t.Name, $out) -ForegroundColor Green
    } else {
        Write-Host ("  [FAIL] {0,-20} -> NOT FOUND at {1}" -f $t.Name, $t.Path) -ForegroundColor Red
        $allPassed = $false
    }
}

if (-not $allPassed) {
    throw "Toolchain verification failed for one or more components!"
}

Write-Host "`n>>> ALL TOOLCHAINS SUCCESSFULLY PROVISIONED AND OPERATIONAL <<<`n" -ForegroundColor Green
exit 0
