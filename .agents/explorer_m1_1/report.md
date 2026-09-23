# Build Infrastructure & Android SDK Provisioning Specification

**Agent**: explorer_m1_1 (Build Infrastructure Explorer)  
**Date**: 2026-09-22  
**Target Project**: `C:\Users\Administrator\teamwork_projects\android_rdp_client`  
**Milestone**: M1 (Build & Core RDP Engine)  
**Parent Conversation**: `279701df-502c-4614-ba7b-407470f48f9a`

---

## 1. Executive Summary & Host Audit Findings

A complete investigation of the host environment (`WIN-RRJUPE3Q0BV`, Windows Server 2025 AMD64) and cached dependencies reveals that the build infrastructure can be provisioned rapidly, deterministically, and with minimal disk space usage:

| Resource | Status | Exact Path / Value | Notes |
|---|---|---|---|
| **Java JDK 21 LTS** | Installed | `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot` | `JAVA_HOME` is currently unset in system/user environment. Must be configured. |
| **Gradle Distribution** | Cached | `C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.5.0-all\aca6g93cdtcf0oapcfka748qh\gradle-9.5.0` | Gradle 9.5.0 is already unpacked on disk. |
| **Gradle Wrapper Assets** | Available | `C:\Users\Administrator\avnc\` (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) | Can be copied directly into `android_rdp_client`. |
| **AGP & Kotlin Plugins** | Cached | AGP `9.2.1` and Kotlin `2.2.20` in `C:\Users\Administrator\.gradle\caches\modules-2\files-2.1` | No external download required for AGP or Kotlin plugins. |
| **Android SDK** | Missing | Target: `C:\Android\Sdk` | Disk space on `C:` is **5.15 GB free**. Provisioning must remain strictly minimal (~280 MB). |
| **Network Access** | Verified | `dl.google.com:443` (HTTP 200 OK, Content-Length: 153,583,359) | Rapid download of Command-Line Tools verified via `curl.exe`. |

---

## 2. Android SDK Provisioning Specification

### 2.1 Component & Disk Footprint Breakdown
To satisfy `compileSdk = 35` and `build-tools;35.0.0` while protecting the 5.15 GB disk headroom:
1. **Command-Line Tools**: `commandlinetools-win-11076708_latest.zip` (~146 MB zip, ~150 MB uncompressed).
2. **Platform Tools**: `platform-tools` (~15 MB).
3. **Platform SDK**: `platforms;android-35` (~60 MB).
4. **Build Tools**: `build-tools;35.0.0` (~55 MB).
**Total SDK footprint on disk**: ~280 MB (leaves >4.8 GB free).
*Note*: Do NOT install system images (emulator images are 1.5–3 GB each). Automated testing is performed headlessly via Robolectric 4.14.1 and MockK.

### 2.2 License Agreement Automation
Android SDK licenses must be accepted non-interactively without blocking the agent runner. We specify a dual-layer strategy:
1. **Layer 1 (Pre-seeding)**: Write license signature hashes directly to `C:\Android\Sdk\licenses\android-sdk-license`, `android-sdk-preview-license`, and related files.
2. **Layer 2 (CLI confirmation)**: Pipe automated `"y"` confirmations into `sdkmanager.bat --licenses --sdk_root="C:\Android\Sdk"`.

### 2.3 Exact SDK Provisioning Script (`setup_sdk.ps1`)
The Worker should execute this PowerShell script (or run each section sequentially):

```powershell
# ==============================================================================
# setup_sdk.ps1 - Automated Android SDK Provisioning for Windows Server 2025
# ==============================================================================
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$sdkRoot = "C:\Android\Sdk"
$jdkPath = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$cmdlineZipUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$tempZip = "$env:TEMP\commandlinetools-win-11076708.zip"
$tempExtractDir = "$env:TEMP\cmdline-tools-extracted"

Write-Host "==> [1/5] Configuring Java Environment Variables..."
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkPath, 'Machine')
[System.Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkPath, 'User')
$env:JAVA_HOME = $jdkPath
$env:Path = "$jdkPath\bin;$env:Path"

Write-Host "==> [2/5] Creating SDK Directories at $sdkRoot..."
if (-not (Test-Path "$sdkRoot\cmdline-tools")) {
    New-Item -ItemType Directory -Force -Path "$sdkRoot\cmdline-tools" | Out-Null
}

if (-not (Test-Path "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host "==> [3/5] Downloading Android Command-Line Tools (146 MB)..."
    curl.exe -L -f -o "$tempZip" "$cmdlineZipUrl"
    
    Write-Host "==> Extracting Command-Line Tools..."
    if (Test-Path "$tempExtractDir") { Remove-Item -Recurse -Force "$tempExtractDir" }
    Expand-Archive -Path "$tempZip" -DestinationPath "$tempExtractDir" -Force
    
    # Structure requirement: C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat
    if (Test-Path "$sdkRoot\cmdline-tools\latest") { Remove-Item -Recurse -Force "$sdkRoot\cmdline-tools\latest" }
    Move-Item -Path "$tempExtractDir\cmdline-tools" -Destination "$sdkRoot\cmdline-tools\latest" -Force
    
    # Clean up temp files immediately to recover disk space
    Remove-Item -Recurse -Force "$tempExtractDir"
    Remove-Item -Force "$tempZip"
    Write-Host "==> Command-Line Tools installed and archive cleaned."
} else {
    Write-Host "==> Command-Line Tools already present at $sdkRoot\cmdline-tools\latest."
}

Write-Host "==> [4/5] Pre-seeding and Accepting Android SDK Licenses..."
$licenseDir = "$sdkRoot\licenses"
New-Item -ItemType Directory -Force -Path $licenseDir | Out-Null

# Write official license hash files
Set-Content -Path "$licenseDir\android-sdk-license" -Value @(
    "8933bad161af4178b1185d1a37fbf41ea5269c55",
    "d56f5187479451eabf01fb78ba6edcb78644d379",
    "24333f8a63b68256972e51a4fb08083ac3f642c3"
) -Encoding ASCII

Set-Content -Path "$licenseDir\android-sdk-preview-license" -Value @(
    "84831b9409646a918e30573bab4c9c91346d8abd"
) -Encoding ASCII

Set-Content -Path "$licenseDir\android-googletv-license" -Value @(
    "601085b94cd77f0b54ff8640695544944156ae7d"
) -Encoding ASCII

# Confirm licenses via sdkmanager CLI
$sdkManager = "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat"
1..20 | ForEach-Object { 'y' } | & $sdkManager --licenses --sdk_root="$sdkRoot"

Write-Host "==> [5/5] Installing SDK Packages: platform-tools, platforms;android-35, build-tools;35.0.0..."
& $sdkManager --sdk_root="$sdkRoot" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# Register SDK environment variables
[System.Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdkRoot, 'Machine')
[System.Environment]::SetEnvironmentVariable('ANDROID_HOME', $sdkRoot, 'User')
[System.Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $sdkRoot, 'User')
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:Path = "$sdkRoot\platform-tools;$sdkRoot\cmdline-tools\latest\bin;$env:Path"

Write-Host "==> SDK Provisioning Complete. Validating outputs:"
Write-Host "  - android.jar: $(Test-Path "$sdkRoot\platforms\android-35\android.jar")"
Write-Host "  - aapt2.exe:   $(Test-Path "$sdkRoot\build-tools\35.0.0\aapt2.exe")"
Write-Host "  - adb.exe:     $(Test-Path "$sdkRoot\platform-tools\adb.exe")"
```

---

## 3. Gradle Wrapper Provisioning

`teamwork_projects\android_rdp_client` does not initially contain `gradlew` or the `gradle/wrapper` folder. However, `C:\Users\Administrator\avnc` has the exact Gradle 9.5.0 wrapper assets matching the pre-cached distribution in `C:\Users\Administrator\.gradle\wrapper\dists`.

Worker command to copy Gradle wrapper assets:
```powershell
$sourceDir = "C:\Users\Administrator\avnc"
$targetDir = "C:\Users\Administrator\teamwork_projects\android_rdp_client"

Copy-Item -Path "$sourceDir\gradlew" -Destination "$targetDir\gradlew" -Force
Copy-Item -Path "$sourceDir\gradlew.bat" -Destination "$targetDir\gradlew.bat" -Force
New-Item -ItemType Directory -Force -Path "$targetDir\gradle\wrapper" | Out-Null
Copy-Item -Path "$sourceDir\gradle\wrapper\gradle-wrapper.jar" -Destination "$targetDir\gradle\wrapper\gradle-wrapper.jar" -Force
Copy-Item -Path "$sourceDir\gradle\wrapper\gradle-wrapper.properties" -Destination "$targetDir\gradle\wrapper\gradle-wrapper.properties" -Force
```

---

## 4. Exact File Contents for Root Configuration Files

### 4.1 `local.properties`
- **Path**: `C:\Users\Administrator\teamwork_projects\android_rdp_client\local.properties`
- **Format Rationale**: Java Properties file format. The colon after drive letter must be escaped (`\:`), and path separators must be double-backslashes (`\\`) or forward slashes.

```properties
## This file must *NOT* be checked into Version Control Systems,
# as it contains information specific to your local configuration.
#
# Location of the SDK. This is only used by Gradle.
sdk.dir=C\:\\Android\\Sdk
```

### 4.2 `gradle.properties`
- **Path**: `C:\Users\Administrator\teamwork_projects\android_rdp_client\gradle.properties`
- **Rationale**:
  - `org.gradle.jvmargs`: Sets memory limit to 2048m (within host RAM limits) and sets UTF-8 encoding.
  - `org.gradle.java.home`: Explicitly forces the Gradle daemon to run on Microsoft OpenJDK 21 LTS (`C:\\Program Files\\Microsoft\\jdk-21.0.12.101-hotspot`).
  - `android.useAndroidX` & `android.nonTransitiveRClass`: Standard modern Android flags for build speed and namespace hygiene.
  - `android.enableR8.fullMode=false`: Prevents aggressive bytecode stripping during unit testing and Robolectric runs.

```properties
# Build Environment JVM Configuration
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.java.home=C:\\Program Files\\Microsoft\\jdk-21.0.12.101-hotspot

# Android Tooling Configuration
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official

# R8 Optimization Stability
android.enableR8.fullMode=false
```

### 4.3 Root `settings.gradle.kts`
- **Path**: `C:\Users\Administrator\teamwork_projects\android_rdp_client\settings.gradle.kts`
- **Rationale**: Declares repository resolution policies and includes all 5 architectural modules specified in `PROJECT.md`: `:app`, `:core-rdp`, `:feature-mouse`, `:feature-session`, and `:feature-telemetry`.

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidFreeRdp"

include(":core-rdp")
include(":feature-mouse")
include(":feature-session")
include(":feature-telemetry")
include(":app")
```

### 4.4 Root `build.gradle.kts`
- **Path**: `C:\Users\Administrator\teamwork_projects\android_rdp_client\build.gradle.kts`
- **Rationale**: Registers plugin versions (`apply false`) to centralize dependency management without polluting subproject build files. Uses cached AGP 9.2.1 and Kotlin 2.2.20.

```kotlin
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
```

---

## 5. Subproject Skeleton Specifications (Preventing Gradle Evaluation Errors)

When `settings.gradle.kts` includes `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`, and `:app`, each module must have a minimal `build.gradle.kts` and `src/main/AndroidManifest.xml` so that Gradle graph resolution succeeds immediately.

### 5.1 `:core-rdp`
#### `core-rdp/build.gradle.kts`
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.freerdp.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```
#### `core-rdp/src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

### 5.2 `:feature-mouse`
#### `feature-mouse/build.gradle.kts`
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freerdp.feature.mouse"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-rdp"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```
#### `feature-mouse/src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

### 5.3 `:feature-session`
#### `feature-session/build.gradle.kts`
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.freerdp.feature.session"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-rdp"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```
#### `feature-session/src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

### 5.4 `:feature-telemetry`
#### `feature-telemetry/build.gradle.kts`
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freerdp.feature.telemetry"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-rdp"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```
#### `feature-telemetry/src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

### 5.5 `:app`
#### `app/build.gradle.kts`
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.freerdp.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freerdp.client"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(project(":core-rdp"))
    implementation(project(":feature-mouse"))
    implementation(project(":feature-session"))
    implementation(project(":feature-telemetry"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Compose Material 3
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```
#### `app/src/main/AndroidManifest.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="FreeRDP Mobile"
        android:roundIcon="@android:drawable/sym_def_app_icon"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name="com.freerdp.client.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

---

## 6. Step-by-Step Implementation Sequence for Worker

1. **Step 1: Execute SDK Provisioning Script**
   Run the PowerShell script defined in Section 2.3 to install Android SDK to `C:\Android\Sdk` and accept licenses.
2. **Step 2: Copy Gradle Wrapper**
   Execute the PowerShell copy commands defined in Section 3 from `C:\Users\Administrator\avnc` into `C:\Users\Administrator\teamwork_projects\android_rdp_client`.
3. **Step 3: Write Root Config Files**
   Create `local.properties`, `gradle.properties`, `settings.gradle.kts`, and `build.gradle.kts` with the exact contents in Section 4.
4. **Step 4: Create Module Directories & Skeletons**
   Create directory trees and starter files for `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`, and `:app` as specified in Section 5.
5. **Step 5: Verify Build Infrastructure**
   Execute in project root:
   ```cmd
   set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot
   gradlew.bat projects
   gradlew.bat assembleDebug
   gradlew.bat testDebugUnitTest
   ```

---

## 7. Risks & Verification Checklist

| Risk / Failure Mode | Root Cause | Prevention / Fix in Design |
|---|---|---|
| `SDK location not found` | Missing `local.properties` or `ANDROID_HOME` | `local.properties` contains `sdk.dir=C\:\\Android\\Sdk` AND `ANDROID_HOME` is set globally. |
| `ERROR: JAVA_HOME is not set` | `gradlew.bat` checks `JAVA_HOME` before Gradle daemon starts | `setx JAVA_HOME` permanently set + shell script sets `$env:JAVA_HOME` + `gradle.properties` specifies `org.gradle.java.home`. |
| Disk space exhaustion (<5 GB free) | System images, NDK, or leftover zip archives | Minimal install (~280 MB), immediate deletion of zip and extract directories. |
| License prompt hanging headless runner | `sdkmanager` waiting for terminal input | Both pre-seeded license files in `C:\Android\Sdk\licenses` and automated `1..20 | ... | sdkmanager --licenses` pipeline. |
| Subproject evaluation failure | Included projects lacking `build.gradle.kts` | Skeleton build scripts and manifests provided for all 5 declared modules. |
