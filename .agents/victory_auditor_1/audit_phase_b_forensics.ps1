Write-Host "=== Phase B: Forensic Cheating & Anti-Circumvention Analysis ==="

# 1. Search for @Ignore / @Disabled
Write-Host "`n[Check 1] Searching for @Ignore or @Disabled annotations in tests..."
$ignoreMatches = Get-ChildItem -Path @("app/src", "core-rdp/src", "feature-mouse/src", "feature-session/src", "feature-telemetry/src") -Recurse -Filter "*.kt" |
    Select-String -Pattern "@Ignore|@Disabled"
if ($ignoreMatches) {
    Write-Host "WARNING: Found @Ignore / @Disabled matches:"
    $ignoreMatches | ForEach-Object { Write-Host "$($_.Filename):$($_.LineNumber): $($_.Line.Trim())" }
} else {
    Write-Host "CLEAN: Zero @Ignore or @Disabled annotations found."
}

# 2. Search for empty test functions: fun test...() {} or similar
Write-Host "`n[Check 2] Searching for empty @Test functions..."
$allTestFiles = Get-ChildItem -Path @("app/src/test", "core-rdp/src/test", "feature-mouse/src/test", "feature-session/src/test", "feature-telemetry/src/test") -Recurse -Filter "*.kt"
$emptyTests = @()
foreach ($file in $allTestFiles) {
    $content = Get-Content $file.FullName -Raw
    # Match @Test followed optionally by other annotations, then fun test...() { }
    $matches = [regex]::Matches($content, "(?ms)@Test\s+(?:@[^\n]+\s+)*fun\s+(\w+)\s*\([^)]*\)\s*\{\s*\}")
    foreach ($m in $matches) {
        $emptyTests += "$($file.Name): $($m.Groups[1].Value)"
    }
}
if ($emptyTests.Count -gt 0) {
    Write-Host "WARNING: Found empty @Test functions:"
    $emptyTests | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "CLEAN: Zero empty @Test functions found."
}

# 3. Search for tautological assertions (e.g. assertTrue(true), assertEquals(x, x), etc.)
Write-Host "`n[Check 3] Searching for tautological assertions..."
$tautologyPatterns = @(
    "assertTrue\s*\(\s*true\s*\)",
    "assertFalse\s*\(\s*false\s*\)",
    "assertEquals\s*\(\s*true\s*,\s*true\s*\)",
    "assertEquals\s*\(\s*1\s*,\s*1\s*\)",
    "assertEquals\s*\(\s*0\s*,\s*0\s*\)",
    "assertThat\s*\(\s*true\s*\)\.isTrue\(\)"
)
$tautologyFound = @()
foreach ($pat in $tautologyPatterns) {
    $matches = $allTestFiles | Select-String -Pattern $pat
    if ($matches) {
        foreach ($m in $matches) {
            $tautologyFound += "$($m.Filename):$($m.LineNumber): $($m.Line.Trim())"
        }
    }
}
if ($tautologyFound.Count -gt 0) {
    Write-Host "WARNING: Found tautological assertions:"
    $tautologyFound | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "CLEAN: Zero tautological assertions found."
}

# 4. Search for build lint suppression or ignoreFailures
Write-Host "`n[Check 4] Checking build.gradle.kts files for abortOnError / ignoreFailures..."
$gradleFiles = Get-ChildItem -Path . -Recurse -Filter "*.gradle.kts" -Exclude ".gradle"
$suppressMatches = $gradleFiles | Select-String -Pattern "ignoreFailures\s*=\s*true|abortOnError\s*=\s*false"
if ($suppressMatches) {
    Write-Host "NOTE: Found build suppression flags:"
    $suppressMatches | ForEach-Object { Write-Host "$($_.Filename):$($_.LineNumber): $($_.Line.Trim())" }
} else {
    Write-Host "CLEAN: No ignoreFailures=true or abortOnError=false found in build.gradle.kts files."
}

# 5. Search for TODO/FIXME in production code (src/main)
Write-Host "`n[Check 5] Checking for TODO / FIXME / STUB in src/main..."
$mainFiles = Get-ChildItem -Path @("app/src/main", "core-rdp/src/main", "feature-mouse/src/main", "feature-session/src/main", "feature-telemetry/src/main") -Recurse -Filter "*.kt"
$todoMatches = $mainFiles | Select-String -Pattern "\b(TODO|FIXME|STUB|XXX)\b"
if ($todoMatches) {
    Write-Host "NOTE: Found TODO/FIXME items in src/main:"
    $todoMatches | ForEach-Object { Write-Host "$($_.Filename):$($_.LineNumber): $($_.Line.Trim())" }
} else {
    Write-Host "CLEAN: Zero TODO/FIXME/STUB tokens in src/main."
}

# 6. Check for Log.* or println in src/main
Write-Host "`n[Check 6] Checking for Log.* or println in src/main..."
$logMatches = $mainFiles | Select-String -Pattern "\b(Log\.[vdiew]|System\.out\.print|println)\b"
if ($logMatches) {
    Write-Host "NOTE: Found logging/prints in src/main:"
    $logMatches | ForEach-Object { Write-Host "$($_.Filename):$($_.LineNumber): $($_.Line.Trim())" }
} else {
    Write-Host "CLEAN: Zero Log.* or println calls in src/main."
}
