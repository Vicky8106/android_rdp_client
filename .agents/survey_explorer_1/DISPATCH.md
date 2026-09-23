# Survey Task Dispatch — Explorer 1: Build Environment & FreeRDP Integration

## Identity
- Archetype: teamwork_preview_explorer
- Role: Environment & Build System Investigator
- Working Directory: C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_1
- Parent Conversation ID: 279701df-502c-4614-ba7b-407470f48f9a

## Objective
Investigate the development environment on this system (Windows) for building and testing Android applications with FreeRDP integration.

## Scope Boundaries
- Do NOT write or modify application source code.
- Investigate environment: check installed Java versions, Android SDK location (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, command line tools, platform-tools, platforms, build-tools, cmake, ndk), Gradle versions.
- Investigate FreeRDP Android upstream architecture and how FreeRDP native bindings/AAR/libs can be integrated cleanly and modularly into an Android project (isolated in a dedicated module e.g. `:core-rdp` or `com.freerdp.core`).
- Determine how to structure Gradle build files (`settings.gradle.kts`, `build.gradle.kts`, gradle wrapper) to satisfy `./gradlew assembleDebug` and `./gradlew testDebugUnitTest`.
- Output report to `C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_1\report.md`.

## Mandatory Reading
- C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md

## 2026-09-22T15:10:41Z
You are survey_explorer_1 (Environment & Build System Investigator).
Your working directory is C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_1.
Your dispatch details are at C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_1\DISPATCH.md.

MANDATORY READING: Read C:\Users\Administrator\teamwork_projects\android_rdp_client\ORIGINAL_REQUEST.md completely before starting.

Task:
1. Investigate the system environment: Java JDK (location, version), Android SDK (location via ANDROID_HOME / ANDROID_SDK_ROOT / local.properties / default paths, installed platforms, build-tools, cmake, ndk), Gradle / Gradle wrapper capabilities.
2. Investigate how FreeRDP Android core components and native bindings are structured upstream (e.g. freerdp-android / libfreerdp / jni / aar) and design how the native module should be isolated in an Android Gradle module (e.g., :core-rdp).
3. Determine exact Gradle build setup needed to satisfy `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` cleanly and reproducibly.
4. Write your full analysis and findings to C:\Users\Administrator\teamwork_projects\android_rdp_client\.agents\survey_explorer_1\report.md and write a handoff.md.
5. Notify parent via send_message when complete.
