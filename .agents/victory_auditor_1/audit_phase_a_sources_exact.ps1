Get-ChildItem -Path @("app/src", "core-rdp/src", "feature-mouse/src", "feature-session/src", "feature-telemetry/src") -Recurse -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 15 |
    ForEach-Object {
        "{0,-80} | {1:yyyy-MM-dd HH:mm:ss} | {2,8} B" -f $_.FullName.Replace("C:\Users\Administrator\teamwork_projects\android_rdp_client\", ""), $_.LastWriteTime, $_.Length
    }
