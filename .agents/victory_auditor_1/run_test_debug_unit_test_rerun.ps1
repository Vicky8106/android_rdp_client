$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

Write-Host "Starting independent execution of: gradlew testDebugUnitTest --rerun-tasks"
Write-Host "Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
& .\gradlew.bat testDebugUnitTest --rerun-tasks --console=plain
$exitCode = $LASTEXITCODE
$stopwatch.Stop()

Write-Host "Finished testDebugUnitTest --rerun-tasks in $($stopwatch.Elapsed.TotalSeconds) seconds."
Write-Host "Exit Code: $exitCode"

Write-Host "`n=== Parsing Newly Generated TEST-*.xml Artifacts ==="
$modules = @("app", "core-rdp", "feature-mouse", "feature-session", "feature-telemetry")
$totalSuites = 0
$totalTests = 0
$totalFailures = 0
$totalErrors = 0
$totalSkipped = 0

foreach ($m in $modules) {
    $xmlPath = "$m/build/test-results/testDebugUnitTest"
    $xmls = Get-ChildItem -Path $xmlPath -Filter "TEST-*.xml"
    $mSuites = $xmls.Count
    $mTests = 0
    $mFailures = 0
    $mErrors = 0
    $mSkipped = 0

    foreach ($xml in $xmls) {
        [xml]$doc = Get-Content $xml.FullName
        $tests = [int]$doc.testsuite.tests
        $failures = [int]$doc.testsuite.failures
        $errors = [int]$doc.testsuite.errors
        $skipped = [int]$doc.testsuite.skipped

        $mTests += $tests
        $mFailures += $failures
        $mErrors += $errors
        $mSkipped += $skipped
    }

    Write-Host ("Module {0,-18} : {1,2} suites | {2,3} tests | {3} failures | {4} errors | {5} skipped" -f $m, $mSuites, $mTests, $mFailures, $mErrors, $mSkipped)
    $totalSuites += $mSuites
    $totalTests += $mTests
    $totalFailures += $mFailures
    $totalErrors += $mErrors
    $totalSkipped += $mSkipped
}

Write-Host "-----------------------------------------------------------------------------------"
Write-Host ("PROJECT TOTAL       : {0,2} suites | {1,3} tests | {2} failures | {3} errors | {4} skipped" -f $totalSuites, $totalTests, $totalFailures, $totalErrors, $totalSkipped)

exit $exitCode
