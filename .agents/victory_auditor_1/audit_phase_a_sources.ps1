Write-Host "=== Source Files (newest 15) ==="
Get-ChildItem -Path @("app/src", "core-rdp/src", "feature-mouse/src", "feature-session/src", "feature-telemetry/src") -Recurse -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 15 |
    ForEach-Object {
        [PSCustomObject]@{
            File = $_.FullName.Replace("C:\Users\Administrator\teamwork_projects\android_rdp_client\", "")
            LastWrite = $_.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
            Length = $_.Length
        }
    } | Format-Table -AutoSize

Write-Host "=== Test Result XMLs (summary per module) ==="
$modules = @("app", "core-rdp", "feature-mouse", "feature-session", "feature-telemetry")
foreach ($m in $modules) {
    $xmlPath = "$m/build/test-results/testDebugUnitTest"
    if (Test-Path $xmlPath) {
        $xmls = Get-ChildItem -Path $xmlPath -Filter "TEST-*.xml"
        $count = $xmls.Count
        $newest = ($xmls | Measure-Object -Property LastWriteTime -Maximum).Maximum
        $oldest = ($xmls | Measure-Object -Property LastWriteTime -Minimum).Minimum
        Write-Host "$m : $count XML files | Oldest: $($oldest.ToString('yyyy-MM-dd HH:mm:ss')) | Newest: $($newest.ToString('yyyy-MM-dd HH:mm:ss'))"
    } else {
        Write-Host "$m : XML path not found"
    }
}
