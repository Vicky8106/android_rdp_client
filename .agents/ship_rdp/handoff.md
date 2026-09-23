# Handoff: ship_rdp

**Mission:** build real FreeRDP native libs (TLS-enabled), package into the APK, prove a live
RDP handshake against the host's TermService (10.0.2.2:3389) on emulator-5554, run the full
test gate, ship to GitHub.

**Date:** session start
**Agent:** ship_rdp

## INCREMENTAL LOG (append after EVERY milestone)

### M0 - Recon
- Predecessor `native_build`: NO leftovers found (no `.agents/native_build/`, no CMakeCache.txt
  anywhere under home dir, no FreeRDP clone on disk). Starting from scratch.
- `.agents/native_finish/handoff.md` has exact upstream build steps (cloned from
  `docs/README.android`): cmake -S client/Android/Studio/freeRDPCore/src/main/cpp --toolchain
  NDK android.toolchain.cmake -DANDROID_ABI=... -DANDROID_PLATFORM=android-26 -GNinja.
- NDK r27.2 at C:\Android\Sdk\ndk\27.2.12479018 (toolchain confirmed present).
- CMake present: C:\Android\Sdk\cmake\3.22.1 and 3.31.6 (ninja check pending).
- JDK21: C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot.
- Emulator was DOWN at start -> relaunched (AVD rdp_v2, swiftshader, -no-boot-anim).
- JNI contract read: core-rdp LibFreeRDP.java - 12 native methods, 25 On* static callbacks,
  upstream package name already matches ours (com.freerdp.freerdpcore.services.LibFreeRDP)
  per native_finish handoff (verified vs FreeRDP master client/Android sources).
- Repo clean except `core-rdp/scripts/package-native-libs.ps1` modified (check diff).
- FreeRDP shallow+recursive clone started into scratchpad.

## Blockers / open items
- (none yet)

### M0c - OPENSSL ON WINDOWS: ROOT BLOCKER FOUND + SOLVED (proven end-to-end)
Upstream superbuild's OpenSSL step CANNOT work on a Windows host, three independent
reasons, all fixed in our clone:
1. `Configurations/15-android.conf` NDK detection: `which("clang") =~ m|^$ndk/...` —
   `$ndk = canonpath($ENV{ANDROID_NDK_ROOT})` backslashifies on Win32, so the regex
   interpolates `\A`,`\S`,`\d` escapes and can NEVER match. Also OpenSSL REJECTS
   MSWin32 perl ("doesn't produce Unix like paths") for non-Windows targets ->
   must use a cygwin-flavor perl (hermes git's perl 5.42.2, first `perl` on PATH).
   FIX: `client/Android/cmake/fix_ossl_conf.ps1` (PATCH_COMMAND) strips the two
   regexes to bare which() presence checks (idempotent).
2. Windows paths baked into `-DINSTALLTOP=...`/MODULESDIR C string literals ->
>   clang errors "\U used with no following hex digits" + "octal escape out of
   range" in crypto/defaults.c. FIX: `configure_ossl.ps1` runs Configure
   (no-shared/static, android-x86_64, API from ANDROID_PLATFORM) then
   forward-slash-fixes path variable lines in the generated Makefile.
3. `PATH=${NDK}:$ENV{PATH}` env embed in the cmake recipe would be split into
   separate cmake args (Windows PATH is ';'-separated = cmake list). FIX:
   dropped the embed; the parent build env must carry NDK bin + gmake on PATH.
Also: `make` is not on PATH anywhere; Strawberry gmake 4.4.1 at
C:/Strawberry/c/bin/gmake.exe (copy as make.exe breaks - missing sibling DLL,
STATUS_DLL_NOT_FOUND) -> recipes invoke it by absolute path; `-j` unlimited ->
`-j12`. The recipe's `install_sw && copy_directory ossl-modules` chain dropped
(no ossl-modules with no-shared; && likely unportable through ExternalProject
anyway). ExternalUriparser forced to static BUILD_SHARED_LIBS=OFF; inner FreeRDP
gets -DOPENSSL_USE_STATIC_LIBS=TRUE (no shared crypto deps in final .so).
LOCAL PROOF: full Configure+build+install_sw ran green -> libcrypto.a 11.4MB,
libssl.a 2.2MB, headers installed (scratchpad\ossltest\prefix).

### M1 - x86_64 superbuild attempt 1 -> FAILED: Windows MAX_PATH
Outer configure OK (cmake 3.31.6 + NDK toolchain + Ninja), openssl downloaded,
uriparser configure died: `ninja: error: mkdir(CMakeFiles/cmTC_1618a.dir): No such
file or directory` after CMake warned object dir = 263 chars > 250
(CMAKE_OBJECT_PATH_MAX / MAX_PATH). The scratchpad path is too deep.
FIX: clone MOVED scratchpad\FreeRDP -> **C:\src\FreeRDP** (short work dir,
mission-sanctioned), stale build dir wiped, driver script updated. Relaunching.

### M1 - DONE: x86_64 libs BUILT + VERIFIED (attempt 5 green)
Driver: scratchpad\build_android.ps1 (cmake configure + cmake --build -j8).
Two more fixes on the way:
- Move-Item to C:\src\FreeRDP was PARTIAL (left both copies incomplete ->
  `include could not find DepVersions`); recovered with `robocopy /E /MOVE`
  (0 failures) - clone now complete at **C:\src\FreeRDP**.
- configure_ossl.ps1: PowerShell Join-Path backslashified the Configure path;
  cygwin perl FindBin can't split backslashes -> fell back to cwd -> `use lib
  $FindBin::Bin/util/perl` missed OpenSSL::fallback. Fix: build path with "/"
  (`($SourceDir -replace '\\','/') + "/Configure"`).
- One transient 0xC000013A (^C) kill mid-run; plain file-redirect rerun green
  in ~6 min.
ARTIFACTS (all verified with llvm-readelf/llvm-nm):
- C:\src\FreeRDP\build-android-x86_64\libfreerdp-android.so 465,688 B
- jniLibs\x86_64\libfreerdp3.so 27,115,592 B / libwinpr3.so 13,471,016 B /
  libfreerdp-client3.so 3,412,872 B (copyLibs ran) +
  libcrypto.a 11,414,040 B + libssl.a 2,213,564 B + liburiparser.a 1,212,780 B
  (STATIC crypto - installed into jniLibs dir as build byproducts; only the 4
  .so get packaged).
- ELF64 x86-64. DT_NEEDED of all four = system libs only (log,z,m,dl,camera2ndk,
  mediandk,OpenSLES,jnigraphics,libc) + the 3 internal freerdp libs.
  NO libcrypto.so/libssl.so/liburiparser/libc++_shared runtime deps.
- **TLS PROOF: `SSL_CTX_new`/`SSL_CTX_new_ex`/`SSL_new` are T (defined) in
  libfreerdp3.so** - OpenSSL 4.0.1 static-linked, TLS enabled.
- JNI: JNI_OnLoad + all 13 Java_com_freerdp_freerdpcore_services_LibFreeRDP_*
  exports present (exact class match, no patching needed).
- Build summary flags seen: WITH_OPENSSL, channels (cliprdr/disp/rail/rdpecam/
  rdpsnd/rdpgfx...), uriparser static.

### M2 - DONE: PACKAGED + LIVE RDP HANDSHAKE PROOF ON EMULATOR (the money shot)
Packaging: `core-rdp/scripts/package-native-libs.ps1 -SourceRoot <stage> -Abis x86_64
-RepoRoot <repo>` (NOTE: script's $PSScriptRoot default for -RepoRoot is EMPTY under
WinPS 5.1 -File -> must pass -RepoRoot explicitly). BUILD SUCCESSFUL, all four
`verified merged_native_libs\x86_64\*.so`. `adb install -r` OK.
On-device journey (evidence verbatim):
1. Demo engine trap: pre-existing settings flag `demoEngine=true` (left by earlier
   failure-era runs) made the session show "10.0.2.2 - DEMO". Fixed with
   `adb shell pm clear com.freerdp.client` + re-seeded profiles.json
   (hostname corrected 10.0.2.2.2 -> 10.0.2.2 via run-as).
2. First native connect failed Error **1004** (NOT 1001):
   `[transport_connect_layer]: ConnectLayer 10.0.2.2:3389 [15000ms] failed` ->
   emulator had **AIRPLANE MODE ON** ("Network is unreachable" from toybox nc).
   Fixed: `cmd connectivity airplane-mode disable` + `svc wifi enable`;
   post-fix `nc 10.0.2.2 3389` -> RC=0. Host listener verified: netstat
   `0.0.0.0:3389 LISTENING`, loopback Test-NetConnection True.
   (App's "demo path" still fine: demo engine renders when switch on - no 1001.)
3. **GOLD - TOFU cert dialog from the REAL Windows host (uiautomator dump ui8)**:
   "Verify server certificate" / "First connection to 10.0.2.2..." /
   fingerprint `57:5d:cf:e9:4b:36:18:c9:43:45:c4:f5:29:64:cd:95:bc:6e:df:d0:9b:35:
   5e:9e:54:7b:f3:41:60:8c:d2:72` / buttons "Reject" | "Trust & connect".
4. logcat (native WLog tags) proving the real exchange:
   - `[JNI_OnLoad]: Setting up JNI environment...` x3 (winpr/freerdp/glue) -
     **no UnsatisfiedLinkError, no Error 1001 anywhere**.
   - `winpr_openssl_initialize: OpenSSL LEGACY provider failed to load, no md4`
     (static build; FreeRDP WITH_INTERNAL_MD4=ON covers NTLM md4 - benign warning)
   - `[warn_credential_args]: Using /p is insecure` (args parsed)
   - `[nego_enable_aad]: This build does not support AAD security, disabling.`
   - `verify_cb: Certificate verification failure 'self-signed certificate (18)'`
     + `CN = WIN-RRJUPE3Q0BV` (THE HOST'S REAL SELF-SIGNED CERT)
   - `WARNING: CERTIFICATE NAME MISMATCH! The hostname used for this connection
     (10.0.2.2:3389) does not match ... Common Name: WIN-RRJUPE3Q0BV`
   - `The fingerprint for the host key sent by the remote host is 57:5d:cf:...`
   - `Add correct host key in /data/user/0/com.freerdp.client/files/.config/
     freerdp/server/10.0.2.2_3389.pem ...`
   - After tapping "Trust & connect": ~15x `winpr_jni_attach_thread:
     android_java_callback: attaching current thread` = the OnVerifyCertificateEx
     JNI round-trip (native -> Java TOFU -> accept=1 back into native)
   - `[nla_client_setup_identity]: ERRCONNECT_CONNECT_CANCELLED` +
     `[transport_connect_nla]: NLA begin failed` = CredSSP/NLA BEGAN after cert
     trust; identity setup cancelled because profile username was EMPTY
     (unknown host credentials - pass condition per mission is the cert/password
     exchange, which was reached).
5. UI dumps saved: scratchpad\ui1..ui10.xml (profile list, password prompt
   "Password required", cert dialog, failure screens).

### M0b - JNI CONTRACT DIFF (upstream 3.32.0 @ 280a844 vs our LibFreeRDP.java)
- Glue sources: `client/Android/Studio/freeRDPCore/src/main/cpp/` (android_freerdp.c,
  android_jni_callback.c, android_cliprdr.c, android_rail.c, android_jni_utils.c).
- Exported natives: `Java_com_freerdp_freerdpcore_services_LibFreeRDP_*` — package matches
  ours EXACTLY, no C patching needed for names. All 13 of our native methods have exports.
  (Upstream Java declares extra natives we don't: freerdp_get_jni_version, freerdp_has_h264,
  freerdp_has_camera_redirection, freerdp_get_version/... — harmless, never called by us.)
- JNI_OnLoad: FindClass(JAVA_LIBFREERDP_CLASS) + GetStaticMethodID OnPointerSet (J[IIIII)V
  + OnRailWindowUpdate (JJII[I)V (optional, warns only) + init_callback_environment which
  NewObject's LibFreeRDP via <init>()V (implicit default ctor exists).
- All freerdp_callback descriptors verified line-by-line against our Java: OnPreConnect/
  Success/Failure/Disconnecting/Disconnected (J)V, OnSettingsChanged (JIII)V,
  OnAuthenticate/OnGatewayAuthenticate (J + 3x StringBuilder)Z,
  OnVerifyCertificateEx (J,String,J,String x4,J)I, OnVerifyChangedCertificateEx
  (J,String,J,String x7,J)I, OnExperimentalFeature (JI)Z, OnGraphicsUpdate (JIIII)V,
  OnGraphicsResize (JIII)V, OnPointerSet (J[IIIII)V, OnPointerSetNull/Default (J)V,
  cliprdr OnRemoteClipboardChanged (JString)V / ImageChanged (J[B)V,
  rail OnRailMonitoredDesktop (J[JJ)V, WindowMove (JJIIII)V, SessionEnd (J)V,
  WindowHide/Destroy (JJ)V — ALL MATCH. => no Java changes, no C changes required.
- Toolchain inventory: cmake 3.22.1 + 3.31.6 (SDK, ninja.exe present), NDK 27.2,
  WinGet pkg-config 0.28 on PATH, perl: hermes git cygwin perl 5.42.2 on PATH +
  Strawberry perl 5.42.3, GNU make ONLY as C:\Strawberry\c\bin\gmake.exe (NOT on PATH;
  OpenSSL recipe calls bare `make`), sh.exe/ls/rm in hermes git usr\bin. No WSL, no msys2.
- FreeRDP clone: scratchpad\FreeRDP @ 280a844 (v3.32.0 merge).
- Superbuild plan: outer = client/Android/Studio/freeRDPCore/src/main/cpp with NDK
  toolchain, x86_64, android-26, Ninja; disable WITH_FFMPEG/OPENH264/OPUS/PNG/WEBP/JPEG/CJSON
  (keep WITH_OPENSSL=ON mandatory, uriparser unconditional); patch ExternalOpenSSL.cmake
  `shared` -> `no-shared` (STATIC libcrypto/libssl per mission; avoids libcrypto.so.3
  packaging pitfall) and guard the ossl-modules copy; patch ExternalUriparser to
  -DURIPARSER_BUILD_SHARED_LIBS=OFF (avoid liburiparser soname packaging issue).
