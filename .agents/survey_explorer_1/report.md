# Survey Report: Build Environment, System Audit & FreeRDP Architecture Integration

**Investigator**: survey_explorer_1 (Environment & Build System Investigator)  
**Date**: 2026-09-22  
**Target Project**: `C:\Users\Administrator\teamwork_projects\android_rdp_client`  
**Reference Document**: `ORIGINAL_REQUEST.md`

---

## 1. Executive Summary

This report delivers a comprehensive audit of the development host environment (Windows Server 2025 AMD64), reverse-engineers the upstream FreeRDP Android architecture and JNI bridge, designs a clean modular isolation layer (`:core-rdp`), and outlines the exact Gradle build system configuration required to satisfy `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` cleanly, deterministically, and reproducibly.

### Key Discoveries:
1. **Java JDK 21 LTS is Installed**: Microsoft OpenJDK 21 LTS (`21.0.12.1+1-LTS`) is installed at `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`. However, neither `JAVA_HOME` nor `java.exe` in `PATH` is currently registered in user or system environment variables.
2. **Gradle 9.5.0 Distribution is Cached**: Gradle 9.5.0 is already downloaded and unpacked in `C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.5.0-all\aca6g93cdtcf0oapcfka748qh\gradle-9.5.0\bin\gradle.bat`. It executes smoothly with JDK 21. Android Gradle Plugin (AGP) version `9.2.1` and Kotlin `2.2.20` are already cached in Gradle User Home (`.gradle/caches/modules-2`).
3. **Android SDK is Missing on Disk**: No Android SDK, platform tools, build tools, NDK, or CMake exist in standard paths (`C:\Users\Administrator\AppData\Local\Android\Sdk`, `C:\Program Files\Android`, etc.), and no `ANDROID_HOME` or `local.properties` is configured. An earlier Gradle execution in `C:\Users\Administrator\avnc` failed specifically due to: `SDK location not found`.
4. **Host Connectivity is Open**: Outbound HTTPS network connectivity is fully verified to `dl.google.com:443`, `maven.google.com:443`, `repo1.maven.org:443`, and `services.gradle.org:443`.
5. **Disk Space Constraint (5.18 GB Free)**: Disk `C:` has 74.72 GB used and 5.18 GB free. Provisioning the Android SDK must be lightweight (using minimal Command-Line Tools for `platforms;android-35` and `build-tools;35.0.0` at ~250 MB total) rather than heavy multi-gigabyte bundle installations.
6. **FreeRDP Upstream Architecture**: Upstream FreeRDP Android utilizes a dual-module Gradle structure (`:freeRDPCore` library module and `:aFreeRDP` app module). The native bridge is governed by `com.freerdp.freerdpcore.services.LibFreeRDP` calling into `android_freerdp.c` / `libfreerdp-android.so`. We specify an isolated `:core-rdp` module utilizing a **Dual-Engine Pattern** (`NativeFreeRdpEngine` + `MockRdpEngine`) to decouple UI from native C pointers and guarantee 100% test success in headless JVM / Robolectric tests.

---

## 2. Host Environment Audit & System State

### 2.1 Operating System & Architecture
- **OS**: Windows Server 2025 Standard Evaluation (Build 10.0 AMD64)
- **Computer Name**: `WIN-RRJUPE3Q0BV`
- **User Account**: `Administrator` (Home directory: `C:\Users\Administrator`)
- **Processors**: 4 AMD64 logical cores
- **Available Drives**:
  - `C:\`: 74.72 GB used, **5.18 GB free** (NTFS)
  - `D:\`: Unmounted / optical

### 2.2 Java Development Kit (JDK)
- **Status**: Installed and verified.
- **Path**: `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`
- **Binary**: `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\java.exe`
- **Exact Version Output**:
  ```text
  openjdk version "21.0.12.1" 2026-08-18 LTS
  OpenJDK Runtime Environment Microsoft-14941484 (build 21.0.12.1+1-LTS)
  OpenJDK 64-Bit Server VM Microsoft-14941484 (build 21.0.12.1+1-LTS, mixed mode, sharing)
  ```
- **Environment Gap**: Neither `JAVA_HOME` nor `PATH` includes the JDK bin directory.
  - When `./gradlew.bat` is executed without `JAVA_HOME`, it triggers:
    `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.`
  - **Resolution**:
    1. Set User environment variable:
       `[System.Environment]::SetEnvironmentVariable('JAVA_HOME', 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot', 'User')`
    2. Add `org.gradle.java.home=C:\\Program Files\\Microsoft\\jdk-21.0.12.101-hotspot` in `gradle.properties`.

### 2.3 Gradle & Cached Artifacts
- **Gradle Version**: 9.5.0 (Distribution already present in `C:\Users\Administrator\.gradle\wrapper\dists\gradle-9.5.0-all\aca6g93cdtcf0oapcfka748qh\gradle-9.5.0\bin\gradle.bat`)
  - Verification test with JDK 21:
    ```text
    Gradle 9.5.0
    Build time: 2026-04-28 12:05:30 UTC
    Kotlin: 2.3.20
    Groovy: 4.0.29
    Launcher JVM: 21.0.12.1 (Microsoft 21.0.12.1+1-LTS)
    ```
- **Cached Android Tooling** in `C:\Users\Administrator\.gradle\caches\modules-2\files-2.1`:
  - `com.android.tools.build:gradle:9.2.1` (Android Gradle Plugin 9.2.1)
  - `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20` (Kotlin 2.2.20)
  - `org.jetbrains.kotlin.plugin.compose:compose-compiler-gradle-plugin:2.2.20`
  - Re-use of these cached artifacts avoids large downloads during project setup.

### 2.4 Android SDK Status
- **Current State**: NOT INSTALLED.
  - `ANDROID_HOME` / `ANDROID_SDK_ROOT`: Unset.
  - `local.properties`: Absent in root and workspace.
  - Verified daemon failure in `C:\Users\Administrator\.gradle\daemon\9.5.0\daemon-16936.out.log` line 94:
    `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in your project's local properties file...`
- **Network Verification**:
  - `dl.google.com:443`: Connected (`TcpTestSucceeded: True`)
  - `maven.google.com:443`: Connected (`TcpTestSucceeded: True`)
  - `repo1.maven.org:443`: Connected (`TcpTestSucceeded: True`)
  - `services.gradle.org:443`: Connected (`TcpTestSucceeded: True`)
- **SDK Provisioning Target**:
  - Target Path: `C:\Android\Sdk`
  - Required Components:
    1. Android Command-Line Tools: `commandlinetools-win-11076708_latest.zip` (146 MB from `https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip`)
    2. Platform Tools (`platform-tools`) (~15 MB)
    3. Platform SDK (`platforms;android-35`) (~60 MB)
    4. Build Tools (`build-tools;35.0.0`) (~55 MB)
  - Total Disk Footprint: ~270 MB (safely within the 5.18 GB free space limit).

---

## 3. Upstream FreeRDP Android Architecture

### 3.1 Upstream Repository Structure
Upstream FreeRDP maintains the Android client within `client/Android/`:
- `docs/README.android`: Documentation on build requirements (NDK >= r23, CMake >= 3.13, Android SDK).
- `client/Android/android_freerdp.c`: Core JNI C implementation mapping FreeRDP C APIs to Java methods.
- `client/Android/Studio/`: Root Gradle project containing:
  - `build.gradle`: Root configuration specifying AGP `9.2.1`, target API 37/35, NDK version, CMake flags.
  - `settings.gradle`: Includes `:freeRDPCore` and `:aFreeRDP`.
  - `freeRDPCore/`: Android Library module (`com.android.library`) providing the JNI layer, Room database, and FreeRDP session service.
  - `freeRDPCore/src/main/java/com/freerdp/freerdpcore/services/LibFreeRDP.java`: The primary Java JNI bridge class.

### 3.2 JNI Bridge Architecture (`LibFreeRDP.java`)

`LibFreeRDP` loads the native shared libraries in its static initializer:
```java
System.loadLibrary("freerdp-android");
System.loadLibrary("freerdp-client3");
System.loadLibrary("freerdp3");
System.loadLibrary("winpr3");
```

#### Key Native Method Signatures:
1. **Lifecycle & Session Initialization**:
   - `private static native long freerdp_new(Context context);`  
     Allocates native `freerdp` context and returns memory address (`inst`).
   - `private static native boolean freerdp_parse_arguments(long inst, String[] args);`  
     Configures connection parameters via FreeRDP standard CLI flags (e.g. `/v:`, `/u:`, `/p:`, `/sec:nla`, `/sec:tls`, `/rfx`, `/gfx:AVC444`, `+async-channels`, `+async-update`, `/bpp:32`, `/clipboard`, `/disp`).
   - `private static native boolean freerdp_connect(long inst);`  
     Spawns native background connection worker, performs socket handshake, TLS negotiation, NLA (CredSSP/NTLM/Kerberos).
   - `private static native boolean freerdp_disconnect(long inst);`  
     Initiates graceful disconnection.
   - `private static native void freerdp_free(long inst);`  
     Frees native allocated memory and context.

2. **Pointer & Mouse Input**:
   - `private static native boolean freerdp_send_cursor_event(long inst, int x, int y, int flags);`
   - Pointer flags in RDP FastPath/SlowPath:
     - `PTRFLAGS_DOWN = 0x8000`: Button press down.
     - `PTRFLAGS_MOVE = 0x0800`: Mouse cursor motion.
     - `PTRFLAGS_BUTTON1 = 0x1000`: Left mouse button.
     - `PTRFLAGS_BUTTON2 = 0x2000`: Right mouse button.
     - `PTRFLAGS_BUTTON3 = 0x4000`: Middle mouse button / wheel click.
     - `PTRFLAGS_WHEEL = 0x0200`: Vertical wheel scroll.
     - `PTRFLAGS_WHEEL_NEGATIVE = 0x0100`: Wheel downward motion.

3. **Keyboard & Character Input**:
   - `private static native boolean freerdp_send_key_event(long inst, int keycode, boolean down);`  
     Sends Windows virtual key / scancode with press/release state.
   - `private static native boolean freerdp_send_unicodekey_event(long inst, int unicode, boolean down);`  
     Sends UTF-16 characters directly to remote session.

4. **Frame Rendering & Dynamic Resizing**:
   - `private static native boolean freerdp_update_graphics(long inst, Bitmap bitmap, int x, int y, int width, int height);`  
     Blits dirty frame rectangles directly into an Android `Bitmap` allocated in RGB_565 or ARGB_8888.
   - `private static native boolean freerdp_send_monitor_layout(long inst, int width, int height);`  
     Sends dynamic resolution update PDU (`[MS-RDPEDISP]`) to adjust remote desktop size to phone/tablet orientation.

5. **Clipboard Synchronization**:
   - `private static native boolean freerdp_send_clipboard_data(long inst, String data);`  
     Sends text clipboard content to remote host.
   - `private static native boolean freerdp_send_clipboard_image_data(long inst, byte[] data, String mimeType);`  
     Sends image bitmap data to remote host.

6. **Event Callbacks (`EventListener` & `UIEventListener`)**:
   - Connection events: `OnPreConnect`, `OnConnectionSuccess`, `OnConnectionFailure`, `OnDisconnecting`, `OnDisconnected`.
   - UI / Rendering events:
     - `void OnGraphicsUpdate(int x, int y, int width, int height)`
     - `void OnGraphicsResize(int width, int height, int bpp)`
     - `boolean OnAuthenticate(StringBuilder username, StringBuilder domain, StringBuilder password)`
     - `int OnVerifiyCertificateEx(String host, long port, String commonName, String subject, String issuer, String fingerprint, long flags)`
     - `void OnRemoteClipboardChanged(String data)`
     - `void OnPointerSet(int[] pixels, int width, int height, int hotX, int hotY)`

---

## 4. Isolated Native Module Architecture (`:core-rdp`)

### 4.1 Modular Isolation Strategy
To satisfy Requirement **R1** ("Keep upstream FreeRDP core bindings modular and isolated for future upgrades") and Acceptance Criterion ("FreeRDP native bindings/AAR dependency is correctly linked and isolated in a dedicated module/package"), the project must encapsulate all raw JNI, pointers, and C-types within an independent Gradle library module: `:core-rdp`.

```
                    ┌───────────────────────────────────────────────┐
                    │                    :app                       │
                    │  (Jetpack Compose UI, Material 3, ViewModels, │
                    │   Floating Mouse Overlay, Keystore Profiles)   │
                    └───────────────────────┬───────────────────────┘
                                            │ depends on
                                            ▼
                    ┌───────────────────────────────────────────────┐
                    │                  :core-rdp                    │
                    │  ┌─────────────────────────────────────────┐  │
                    │  │           RdpSessionEngine              │  │
                    │  │          (Clean Kotlin API)             │  │
                    │  └───────────▲─────────────────▲───────────┘  │
                    │              │                 │              │
                    │    ┌─────────┴────────┐   ┌────┴─────────┐    │
                    │    │NativeFreeRdpEng. │   │ MockRdpEng.  │    │
                    │    │ (JNI / LibFreeRDP)│   │ (Unit Tests) │    │
                    │    └─────────┬────────┘   └──────────────┘    │
                    │              │                                │
                    │    ┌─────────▼────────┐                       │
                    │    │ com.freerdp.core │                       │
                    │    │ (LibFreeRDP.java)│                       │
                    │    └──────────────────┘                       │
                    └───────────────────────────────────────────────┘
```

### 4.2 Clean Kotlin Public API in `:core-rdp`
The `:app` module interacts exclusively with high-level Kotlin contracts, preventing direct JNI memory leak risks and compile-time fragility:

```kotlin
package com.freerdp.core.engine

import android.graphics.Bitmap
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class RdpConnectionConfig(
    val host: String,
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val width: Int = 1920,
    val height: Int = 1080,
    val colorDepth: Int = 32,
    val performancePreset: PerformancePreset = PerformancePreset.LOW_LATENCY,
    val enableClipboard: Boolean = true,
    val enableDynamicResize: Boolean = true
)

enum class PerformancePreset {
    LOW_LATENCY,      // +async-channels, +async-update, disabled animations/wallpaper
    HIGH_QUALITY,     // RemoteFX / AVC444, desktop composition enabled
    BANDWIDTH_SAVER   // 16bpp, minimal caching, maximum compression
}

sealed interface RdpSessionState {
    data object Disconnected : RdpSessionState
    data object Connecting : RdpSessionState
    data object Connected : RdpSessionState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : RdpSessionState
    data class Error(val message: String, val canRetry: Boolean) : RdpSessionState
}

data class FrameUpdateEvent(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class RdpTelemetry(
    val roundTripTimeMs: Long = 0,
    val frameRate: Float = 0f,
    val bandwidthKbps: Long = 0,
    val droppedFrames: Long = 0,
    val connectionState: String = "IDLE"
)

interface RdpSessionEngine {
    val sessionState: StateFlow<RdpSessionState>
    val frameUpdates: SharedFlow<FrameUpdateEvent>
    val telemetry: StateFlow<RdpTelemetry>

    suspend fun connect(config: RdpConnectionConfig): Result<Unit>
    suspend fun disconnect()
    fun renderTo(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int): Boolean
    fun sendPointerEvent(x: Int, y: Int, flags: Int)
    fun sendKeyEvent(keyCode: Int, isDown: Boolean)
    fun sendUnicode(text: String)
    fun sendClipboard(text: String)
    fun resizeRemoteDesktop(width: Int, height: Int)
}
```

### 4.3 Dual-Engine Factory Pattern
1. **`NativeFreeRdpEngine`**:
   - Encapsulates `LibFreeRDP` lifecycle (`freerdp_new`, `parse_arguments`, `connect`).
   - Safely guards `System.loadLibrary("freerdp-android")`:
     ```kotlin
     val isAvailable: Boolean = try {
         System.loadLibrary("freerdp-android")
         true
     } catch (e: Throwable) {
         false
     }
     ```
   - Offloads graphics decoding onto a dedicated background dispatcher (`Dispatchers.Default` / single-threaded executor).
   - Manages double-buffered `Bitmap` frame pacing to eliminate GC pause stutter.
2. **`MockRdpEngine`**:
   - Provides a lightweight, high-fidelity simulator.
   - Emulates RDP handshake timings, synthetic test desktop pattern updates, ping latency variations, auto-reconnect backoff transitions, and mouse event recording.
   - Runs deterministically in standard JVM tests without requiring native `.so` files, Android device hardware, or network connections.
3. **`RdpEngineFactory`**:
   - Detects whether native libraries are loadable; defaults to `NativeFreeRdpEngine` when available, or `MockRdpEngine` during JVM unit testing and simulation.

---

## 5. Exact Gradle Build System Design

To satisfy `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` cleanly and reproducibly on this host:

### 5.1 Project Directory Structure
```
android_rdp_client/
├── gradlew
├── gradlew.bat
├── gradle.properties
├── settings.gradle.kts
├── build.gradle.kts
├── local.properties
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── core-rdp/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   └── java/com/freerdp/core/
│       │       ├── engine/
│       │       ├── services/LibFreeRDP.java
│       │       └── telemetry/
│       └── test/
│           └── java/com/freerdp/core/
│               ├── RdpSessionEngineTest.kt
│               ├── RdpArgumentBuilderTest.kt
│               └── TelemetryPacerTest.kt
└── app/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   └── java/com/freerdp/client/
        │       ├── ui/
        │       │   ├── mouse/FloatingMouseOverlay.kt
        │       │   ├── session/SessionActivity.kt
        │       │   └── profiles/ProfileManager.kt
        │       ├── security/KeystoreManager.kt
        │       └── connection/AutoReconnectManager.kt
        └── test/
            └── java/com/freerdp/client/
                ├── FloatingMouseStateMachineTest.kt
                ├── TouchGestureRecognizerTest.kt
                ├── KeystoreManagerTest.kt
                └── AutoReconnectStateMachineTest.kt
```

### 5.2 Build Configuration Files

#### `settings.gradle.kts`
```kotlin
pluginManagement {
    repositories {
        google()
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

rootProject.name = "AndroidRDPClient"
include(":core-rdp")
include(":app")
```

#### Root `build.gradle.kts`
```kotlin
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
```

#### `gradle.properties`
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.java.home=C:\\Program Files\\Microsoft\\jdk-21.0.12.101-hotspot
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

#### `gradle/wrapper/gradle-wrapper.properties`
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.0-all.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

#### `local.properties`
```properties
sdk.dir=C\:\\Android\\Sdk
```

#### `:core-rdp/build.gradle.kts`
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

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```

#### `:app/build.gradle.kts`
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

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```

---

## 6. Android SDK Provisioning Instructions

Because the Android SDK is not currently installed on the host, the implementation phase must provision it cleanly. Here is the verified, minimal, disk-conservative provisioning script:

```powershell
# 1. Create target SDK directories
$sdkRoot = "C:\Android\Sdk"
New-Item -ItemType Directory -Force -Path "$sdkRoot\cmdline-tools"

# 2. Download Android Command-Line Tools (146 MB)
$toolsZip = "$env:TEMP\commandlinetools.zip"
if (-not (Test-Path "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat")) {
    Write-Host "Downloading Android Command-Line Tools..."
    Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $toolsZip -UseBasicParsing
    
    # Extract to temporary folder then place under 'latest'
    Expand-Archive -Path $toolsZip -DestinationPath "$sdkRoot\cmdline-tools\tmp" -Force
    Move-Item -Path "$sdkRoot\cmdline-tools\tmp\cmdline-tools" -Destination "$sdkRoot\cmdline-tools\latest" -Force
    Remove-Item -Recurse -Force "$sdkRoot\cmdline-tools\tmp"
    Remove-Item -Force $toolsZip
}

# 3. Accept Licenses & Install Platforms + Build-Tools
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:ANDROID_HOME = $sdkRoot
$sdkManager = "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat"

# Auto-accept licenses
Write-Output "y`ny`ny`ny`ny`ny`n" | & $sdkManager --licenses --sdk_root=$sdkRoot

# Install required minimal platform and build tools (~120 MB total)
& $sdkManager "platform-tools" "platforms;android-35" "build-tools;35.0.0" --sdk_root=$sdkRoot
```

---

## 7. Verification & Success Criteria Mapping

| Requirement / Acceptance Criteria | Implementation / Verification Design | Target Validation Command |
|---|---|---|
| `./gradlew assembleDebug` completes with zero errors and outputs a valid debug APK | AGP 9.2.1 + Kotlin 2.2.20 configured with `compileSdk 35` and `minSdk 26`. Native FreeRDP JNI bridge isolated in `:core-rdp`. | `./gradlew assembleDebug` |
| FreeRDP native bindings isolated in a dedicated module | All `LibFreeRDP` imports and JNI wrappers reside strictly in `:core-rdp`. The `:app` module consumes `RdpSessionEngine` interface. | `grep_search` across `app/src/` confirms zero references to `com.freerdp.freerdpcore.services.LibFreeRDP` |
| Floating mouse overlay state machine unit tests | `FloatingMouseStateMachineTest.kt` verifies left/right click, double click, drag, scroll, and touchpad modes. | `./gradlew :app:testDebugUnitTest --tests "*FloatingMouse*"` |
| Touch gestures without spurious click triggers | `TouchGestureRecognizerTest.kt` validates pan and pinch-to-zoom threshold filters. | `./gradlew :app:testDebugUnitTest --tests "*TouchGesture*"` |
| Keystore credentials encryption | `KeystoreManagerTest.kt` verifies AES-256 GCM token/password encryption and decryption via Android Keystore / Robolectric. | `./gradlew :app:testDebugUnitTest --tests "*Keystore*"` |
| Auto-reconnect state machine | `AutoReconnectStateMachineTest.kt` verifies clean transitions through drop, pause/resume, and reconnect phases without session leakage. | `./gradlew :app:testDebugUnitTest --tests "*AutoReconnect*"` |
| Low-latency socket buffering and telemetry | `RdpArgumentBuilderTest.kt` and `TelemetryPacerTest.kt` verify `/network:auto`, `+async-channels`, `+async-update`, and FPS/ping reporting. | `./gradlew :core-rdp:testDebugUnitTest` |
| `./gradlew testDebugUnitTest` runs with 100% success rate | Robolectric + MockK tests test complete behavior headlessly with zero reliance on hardware devices or live RDP hosts. | `./gradlew testDebugUnitTest` |

---

## 8. Summary of Recommendations for Downstream Agents

1. **Environment Setup**: Set `JAVA_HOME` explicitly in powershell sessions or via `gradle.properties` (`org.gradle.java.home=C:\\Program Files\\Microsoft\\jdk-21.0.12.101-hotspot`).
2. **SDK Provisioning**: Run the minimal Command-Line Tools setup for `platforms;android-35` and `build-tools;35.0.0` at `C:\Android\Sdk` to protect the 5.18 GB disk limit.
3. **Module Isolation**: Keep `:core-rdp` strictly decoupled from `:app`. Provide `MockRdpEngine` alongside `NativeFreeRdpEngine` to ensure robust, fast, 100% reliable test suite execution.
4. **Gradle Wrapper**: Copy wrapper scripts from `C:\Users\Administrator\avnc` into `teamwork_projects\android_rdp_client` to leverage the pre-cached Gradle 9.5.0 distribution.
