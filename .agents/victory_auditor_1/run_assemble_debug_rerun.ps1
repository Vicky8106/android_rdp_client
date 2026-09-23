$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Write-Host "Starting independent execution of: gradlew assembleDebug --rerun-tasks"
Write-Host "Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
& .\gradlew.bat assembleDebug --rerun-tasks --console=plain
$exitCode = $LASTEXITCODE
$stopwatch.Stop()

Write-Host "Finished assembleDebug --rerun-tasks in $($stopwatch.Elapsed.TotalSeconds) seconds."
Write-Host "Exit Code: $exitCode"

if ($exitCode -eq 0) {
    Write-Host "SUCCESS: assembleDebug --rerun-tasks completed cleanly."
    $apk = Get-Item "app\build\outputs\apk\debug\app-debug.apk"
    Write-Host "APK Path: $($apk.FullName)"
    Write-Host "APK Size: $($apk.Length) bytes"
    Write-Host "APK LastWriteTime: $($apk.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
} else {
    Write-Host "FAILURE: assembleDebug --rerun-tasks exited with code $exitCode"
}
exit $exitCode
