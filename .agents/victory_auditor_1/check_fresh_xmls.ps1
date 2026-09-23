Get-ChildItem -Path @("app", "core-rdp", "feature-mouse", "feature-session", "feature-telemetry") -Recurse -Filter "TEST-*.xml" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 5 |
    ForEach-Object {
        [PSCustomObject]@{
            Suite = $_.Name
            LastWrite = $_.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
        }
    }
