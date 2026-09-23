# Scope: Milestone 1 — Build Environment & Core RDP Engine

## Architecture & Scope
Establish the production Android Gradle multi-project infrastructure and implement the `:core-rdp` module encapsulating FreeRDP native bindings behind `IRdpEngine`.

Key Components:
1. **Build Environment & Gradle Provisioning**:
   - Provision lightweight Android SDK (~270 MB command-line tools: platform-tools, platforms;android-35, build-tools;35.0.0) at `C:\Android\Sdk` if not present.
   - Configure `local.properties` (`sdk.dir=C:\\Android\\Sdk`) and `gradle.properties` (`org.gradle.java.home=C:\\Program Files\\Microsoft\\jdk-21.0.12.101-hotspot`, JVM memory args).
   - Setup Gradle wrapper (Gradle 9.5.0, AGP 9.2.1, Kotlin 2.2.20).
   - Multi-module `settings.gradle.kts` declaring `:app`, `:core-rdp`, `:feature-mouse`, `:feature-session`, `:feature-telemetry`.
2. **`:core-rdp` Module Implementation**:
   - Upstream FreeRDP JNI bridge abstraction: `com.freerdp.freerdpcore.services.LibFreeRDP`.
   - `IRdpEngine` interface and `RdpEventListener` contracts per `PROJECT.md`.
   - `NativeFreeRdpEngine`: JNI caller wrapping native pointer in `AtomicLong`, thread-safe state machine, certificate verification callback handling.
   - `MockRdpEngine`: Deterministic in-memory test double supporting full connection simulation, graphics updates, and event logging for 100% headless testing.
   - Dynamic resolution protocol (`MS-RDPEDISP` debounced monitor layout updates).
   - Clipboard synchronization (`MS-RDPECLIP` UTF-16LE text sync with echo loop suppression).
   - RDP pointer flags (`MS-RDPBCGR` bitmasks in `RdpPointerFlags`).
3. **Verification**:
   - `./gradlew assembleDebug` compiles successfully.
   - `./gradlew :core-rdp:testDebugUnitTest` passes 100%.

## Interface Contracts
Must conform strictly to `PROJECT.md § Interface Contracts`:
- `IRdpEngine`
- `RdpEventListener`
- `RdpConnectionConfig`
- `RdpPointerFlags`

## Rules
- Iterate using standard loop: Explorer -> Worker -> Reviewer -> Challenger -> Auditor -> Gate.
- Worker owns exclusively `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties`, `core-rdp/`.
- MANDATORY INTEGRITY WARNING must be given to Worker.
- Auditor must verify zero mock shortcuts in production paths, zero hardcoded bypasses.
