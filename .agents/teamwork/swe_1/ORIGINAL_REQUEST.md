# Original User Request

## Initial Request — 2026-09-23T15:26:01Z

Finalize packaging for the Android RDP Client project (`android_rdp_client`) by synchronizing the release APK with the latest stability and frame-rendering build, cleaning up untracked workspace artifacts, verifying the full test suite, and pushing to GitHub `origin/main`.

Working directory: C:\Users\Administrator\teamwork_projects\android_rdp_client
Integrity mode: development

## Requirements

### R1. Release APK Synchronization
Synchronize `releases/app-debug.apk` with the latest working debug build from `app/build/outputs/apk/debug/app-debug.apk` containing the FreeRDP native libraries and stability fixes. Ensure the binary size remains strictly under GitHub's 100 MB upload limit.

### R2. Test Suite & Build Verification
Verify that the complete unit and Robolectric test suite (`.\gradlew.bat testDebugUnitTest`) passes with 100% success rate (505 tests, 0 failures, 0 errors). Ensure `.\gradlew.bat assembleDebug` builds without errors.

### R3. Repository Cleanup & Git Push
Clean up untracked temporary agent directories (`.agents/auditor_gen3_1/`, etc.) and scratch files so the working tree is clean. Commit all release assets and documentation updates with a clear, descriptive commit message, and push the commit to `origin/main` (`https://github.com/Vicky8106/android_rdp_client.git`).

## Verification Resources
- Test suite command: `.\gradlew.bat testDebugUnitTest`
- Build command: `.\gradlew.bat assembleDebug`
- Target remote repository: `https://github.com/Vicky8106/android_rdp_client.git` (branch `main`)
- Verification checks:
  - `git status` reports working tree clean
  - `git push origin main` succeeds
  - `git ls-remote origin main` matches local `HEAD`

## Acceptance Criteria

### Packaging & Build
- [ ] `releases/app-debug.apk` exists, matches the latest `app/build/outputs/apk/debug/app-debug.apk` (hash/size match), and is under 100 MB.
- [ ] `.\gradlew.bat assembleDebug` succeeds.

### Automated Tests
- [ ] `.\gradlew.bat testDebugUnitTest` passes with 0 failures and 0 errors across all 5 modules (505 tests).

### Repository & Push
- [ ] Working tree has no untracked scratch artifacts or lingering temporary agent directories.
- [ ] All changes are committed and pushed to `origin/main`.
- [ ] `git ls-remote origin main` confirms the remote tip equals local `HEAD`.
