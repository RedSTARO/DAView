param(
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repo ('build/audit/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$log = Join-Path $OutputDirectory 'gradle.log'
$resultPath = Join-Path $OutputDirectory 'result.json'
$started = [DateTimeOffset]::UtcNow
$tasks = @(
    ':core:jvmTest',
    ':composeApp:desktopTest',
    ':composeApp:compileDebugKotlinAndroid',
    ':composeApp:assembleDebugAndroidTest',
    ':core:lintDebug',
    ':composeApp:lintDebug'
)

function Get-SourceSnapshot {
    $paths = @(& git ls-files -co --exclude-standard | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate verification inputs' }
    $lines = foreach ($relative in $paths) {
        $path = Join-Path $repo $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            "$relative $hash"
        } else { "$relative MISSING" }
    }
    return @($lines)
}

function Get-TestTotals([string]$relative) {
    $total = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    $suites = @()
    $directory = Join-Path $repo $relative
    if (Test-Path -LiteralPath $directory) {
        foreach ($file in Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml') {
            [xml]$xml = [IO.File]::ReadAllText($file.FullName)
            $total += [int]$xml.testsuite.tests
            $failures += [int]$xml.testsuite.failures
            $errors += [int]$xml.testsuite.errors
            $skipped += [int]$xml.testsuite.skipped
            $suites += [string]$xml.testsuite.name
        }
    }
    return @{ tests = $total; failures = $failures; errors = $errors; skipped = $skipped; suites = $suites }
}

$exitCode = -1
$failure = $null
$before = @()
$unchanged = $false
try {
    $before = Get-SourceSnapshot
    $before | Set-Content -LiteralPath (Join-Path $OutputDirectory 'inputs.sha256') -Encoding utf8
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    # One build, one exit event. The launching model need not poll this process.
    & (Join-Path $repo 'gradlew.bat') @tasks --continue --console=plain -q *> $log
    $exitCode = $LASTEXITCODE
    $after = Get-SourceSnapshot
    $unchanged = ($null -eq (Compare-Object $before $after))
} catch {
    $failure = $_.Exception.Message
    $failure | Add-Content -LiteralPath $log -Encoding utf8
} finally {
    $core = Get-TestTotals 'core/build/test-results/jvmTest'
    $desktop = Get-TestTotals 'composeApp/build/test-results/desktopTest'
    $lint = @{}
    foreach ($module in @('core', 'composeApp')) {
        $path = Join-Path $repo "$module/build/reports/lint-results-debug.xml"
        if (Test-Path -LiteralPath $path) {
            [xml]$xml = [IO.File]::ReadAllText($path)
            $issues = @($xml.issues.issue | Where-Object { $null -ne $_ })
            $lint[$module] = @{
                errors = @($issues | Where-Object severity -eq 'Error').Count
                warnings = @($issues | Where-Object severity -eq 'Warning').Count
                report = $path
            }
        }
    }
    $result = [ordered]@{
        startedAt = $started.ToString('o')
        finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
        gradleExitCode = $exitCode
        inputFilesUnchanged = $unchanged
        verified = ($exitCode -eq 0 -and $unchanged -and $core.tests -gt 0 -and $desktop.tests -gt 0)
        error = $failure
        tasks = $tasks
        core = $core
        desktop = $desktop
        lint = $lint
        log = $log
        wakeup = 'Unavailable: this Windows CLI has no queue command or accessible daemon transport for the current desktop thread.'
    }
    $json = $result | ConvertTo-Json -Depth 8
    # Publish the completion record only after validation and all log writes finish.
    $temporary = "$resultPath.tmp"
    [IO.File]::WriteAllText($temporary, $json, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $resultPath -Force
}
if ($result.verified) { exit 0 }
exit 1
