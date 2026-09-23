$files = @(
    "baseline_build.log",
    "baseline_exit.txt",
    "final_gate.log",
    "final_gate_rerun.log",
    "app\build\outputs\apk\debug\app-debug.apk"
)

foreach ($f in $files) {
    if (Test-Path $f) {
        $item = Get-Item $f
        [PSCustomObject]@{
            File = $f
            LastWrite = $item.LastWriteTime.ToString("yyyy-MM-dd HH:mm:ss")
            Length = $item.Length
        }
    } else {
        [PSCustomObject]@{
            File = $f
            LastWrite = "NOT FOUND"
            Length = 0
        }
    }
}
