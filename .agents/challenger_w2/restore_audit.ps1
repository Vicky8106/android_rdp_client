$ErrorActionPreference = 'Stop'
$d = 'C:\Users\Administrator\teamwork_projects\android_rdp_client'
$backup = Join-Path $d '.agents\challenger_w2\backup'
$pairs = @(
  @('feature-telemetry\src\main\java\com\freerdp\feature\telemetry\pacer\FramePacer.kt', 'FramePacer.kt.orig'),
  @('feature-session\src\main\java\com\freerdp\feature\session\modifier\ModifierStateMachine.kt', 'ModifierStateMachine.kt.orig'),
  @('feature-session\src\main\java\com\freerdp\feature\session\data\AtomicFileProfileRepository.kt', 'AtomicFileProfileRepository.kt.orig'),
  @('feature-session\src\main\java\com\freerdp\feature\session\security\KeystoreCredentialStore.kt', 'KeystoreCredentialStore.kt.orig'),
  @('feature-session\src\main\java\com\freerdp\feature\session\toolbar\QuickActionToolbarFSM.kt', 'QuickActionToolbarFSM.kt.orig'),
  @('feature-session\src\main\java\com\freerdp\feature\session\keyboard\ScancodeTranslator.kt', 'ScancodeTranslator.kt.orig'),
  @('feature-telemetry\src\main\java\com\freerdp\feature\telemetry\reconnect\AutoReconnectManagerImpl.kt', 'AutoReconnectManagerImpl.kt.orig'),
  @('app\src\main\java\com\freerdp\client\session\SessionPhase.kt', 'SessionPhase.kt.orig'),
  @('feature-mouse\src\main\java\com\freerdp\feature\mouse\GestureDisambiguationEngine.kt', 'GestureDisambiguationEngine.kt.orig'),
  @('feature-mouse\src\main\java\com\freerdp\feature\mouse\CoordinateTransformer.kt', 'CoordinateTransformer.kt.orig'),
  @('core-rdp\src\main\java\com\freerdp\core\engine\NativeFreeRdpEngine.kt', 'NativeFreeRdpEngine.kt.orig')
)
$bad = 0
foreach ($p in $pairs) {
  $f = Join-Path $d $p[0]
  $b = Join-Path $backup $p[1]
  $mutant = Select-String -Path $f -Pattern 'MUTANT' -Quiet
  $h1 = (Get-FileHash $b -Algorithm SHA256).Hash
  $h2 = (Get-FileHash $f -Algorithm SHA256).Hash
  $status = 'OK'
  if ($mutant) { $status = 'MUTANT-STILL-PRESENT'; $bad++ }
  if ($h1 -ne $h2) { if ($mutant) { $status = 'MUTANT-PRESENT+HASH-DIFF' } else { $status = 'HASH-MISMATCH'; $bad++ } }
  '{0}  {1}  backup={2}' -f $status, $p[1], $h1
}
'bad={0}' -f $bad
