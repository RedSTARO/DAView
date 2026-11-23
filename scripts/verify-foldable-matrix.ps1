#requires -Version 7.0
<#
.SYNOPSIS
Run the DAView adaptive Android instrumentation matrix on one explicitly chosen disposable device.
.EXAMPLE
./scripts/verify-foldable-matrix.ps1 -Serial emulator-5556 -ConfirmDisposableTarget
.EXAMPLE
./scripts/verify-foldable-matrix.ps1 -Serial emulator-5556 -ConfirmDisposableTarget -Fit native -Case book,detail-continuity
.NOTES
Build the APKs separately with :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest -PdaviewSideBySide=true.
Only the two validated side-by-side APKs are installed. This does not change display/IME/system settings,
start an AVD, clear data, or uninstall anything. Device admin, physical fold and OS split evidence are separate.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:\-]*$')]
    [string]$Serial,
    [switch]$ConfirmDisposableTarget,
    [string]$SdkDirectory = '',
    [string]$AppApk = '',
    [string]$TestApk = '',
    [string]$OutputDirectory = '',
    [ValidateSet('fit', 'native')]
    [string]$Fit = 'fit',
    [ValidateRange(0.8, 2.0)]
    [double]$FontScale = 1.0,
    [ValidateRange(30, 600)]
    [int]$CaseTimeoutSeconds = 150,
    [ValidateSet('small', 'large_portrait', 'large_landscape', 'book', 'tabletop', 'split',
        'browse-continuity', 'search-continuity', 'detail-continuity', 'standalone-detail', 'settings-read-only', 'seed',
        'player-continuity', 'player-pip-playing', 'player-pip-paused')]
    [string[]]$Case = @('small', 'large_portrait', 'large_landscape', 'book', 'tabletop', 'split',
        'browse-continuity', 'search-continuity', 'detail-continuity', 'standalone-detail', 'settings-read-only', 'seed',
        'player-continuity', 'player-pip-playing', 'player-pip-paused')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmDisposableTarget) {
    throw 'Pass -ConfirmDisposableTarget only for a device and com.daview.app.debug profile reserved for this test.'
}
if ($Case.Count -eq 0 -or @($Case | Select-Object -Unique).Count -ne $Case.Count) {
    throw 'Choose at least one distinct matrix case.'
}
$repo = Split-Path -Parent $PSScriptRoot
$methods = @{
    small = 'smallBrowseDetail'; large_portrait = 'largePortraitBrowseDetail'; large_landscape = 'largeLandscapeBrowseDetail'
    book = 'bookBrowseDetail'; tabletop = 'tabletopBrowseDetail'; split = 'splitBrowseDetail'
    'browse-continuity' = 'lastTitleAndBackStackSurviveWindowChanges'
    'search-continuity' = 'searchQuerySurvivesVerticalFoldAndUnfold'
    'detail-continuity' = 'selectedSeasonAndEpisodeAnchorSurviveResize'
    'standalone-detail' = 'standaloneSeriesPairsOnlyWhenUseful'
    'settings-read-only' = 'settingsRemainReachableWithoutEditing'; seed = 'seedCatalogueAndOpenApp'
    'player-continuity' = 'realAppPlaybackSurvivesWindowMatrix'
    'player-pip-playing' = 'visiblePictureInPictureKeepsProgressAndFinishingItReleasesTheSession'
    'player-pip-paused' = 'deliberatelyPausedVideoCanEnterPictureInPictureWithoutResuming'
}
$expectedFrames = @{
    small = @('SMALL'); large_portrait = @('LARGE_PORTRAIT'); large_landscape = @('LARGE_LANDSCAPE')
    book = @('BOOK'); tabletop = @('TABLETOP'); split = @('SPLIT')
    'browse-continuity' = @('LARGE_LANDSCAPE', 'SMALL', 'LARGE_PORTRAIT', 'BOOK', 'TABLETOP', 'SPLIT', 'LARGE_LANDSCAPE')
    'search-continuity' = @('LARGE_LANDSCAPE', 'BOOK', 'LARGE_LANDSCAPE')
    'detail-continuity' = @('SMALL', 'LARGE_PORTRAIT', 'LARGE_LANDSCAPE', 'BOOK', 'TABLETOP', 'SPLIT', 'SMALL')
    'standalone-detail' = @('ROOMY_DETAIL', 'SMALL', 'ROOMY_DETAIL')
    'settings-read-only' = @('SMALL', 'BOOK', 'TABLETOP'); seed = @()
    'player-continuity' = @('SMALL', 'LARGE_PORTRAIT', 'LARGE_LANDSCAPE', 'BOOK', 'TABLETOP', 'SPLIT', 'TINY_PLAYBACK_SPLIT')
    'player-pip-playing' = @(); 'player-pip-paused' = @()
}
if (-not $SdkDirectory) {
    $SdkDirectory = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_SDK_ROOT
    } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
}
if (-not $AppApk) { $AppApk = Join-Path $repo 'composeApp/build/outputs/apk/debug/composeApp-debug.apk' }
if (-not $TestApk) { $TestApk = Join-Path $repo 'composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk' }
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repo ('build/audit/foldable-matrix-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw "Use a new output directory: $OutputDirectory" }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null

function Invoke-CheckedProcess {
    param([string]$Executable, [string[]]$Argument, [string]$LogName, [int]$TimeoutSeconds = 90)
    $log = Join-Path $OutputDirectory "$LogName.log"
    $info = [Diagnostics.ProcessStartInfo]::new($Executable)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($item in $Argument) { $info.ArgumentList.Add($item) }
    $proc = [Diagnostics.Process]::Start($info)
    try {
        $stdout = $proc.StandardOutput.ReadToEndAsync()
        $stderr = $proc.StandardError.ReadToEndAsync()
        $timedOut = -not $proc.WaitForExit($TimeoutSeconds * 1000)
        if ($timedOut) { $proc.Kill() }
        $text = $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText($log, $text, [Text.UTF8Encoding]::new($false))
        if ($timedOut) { throw "$LogName timed out. See $log" }
        if ($proc.ExitCode -ne 0) { throw "$LogName exited $($proc.ExitCode). See $log" }
        return $text.Replace("`r", '')
    } finally { $proc.Dispose() }
}

function Assert-OneCase {
    param([string]$Id, [string]$Method, [string]$Output)
    $escapedId = [regex]::Escape($Id)
    $escapedMethod = [regex]::Escape($Method)
    $begin = [regex]::Matches($Output, "(?m)^INSTRUMENTATION_STATUS: daview_matrix=$($escapedId):BEGIN$")
    $pass = [regex]::Matches($Output, "(?m)^INSTRUMENTATION_STATUS: daview_matrix=$($escapedId):PASS$")
    $finish = [regex]::Matches($Output, '(?m)^INSTRUMENTATION_STATUS_CODE: 0$')
    $test = [regex]::Matches($Output, "(?m)^INSTRUMENTATION_STATUS: test=$escapedMethod$")
    $runnerCode = [regex]::Matches($Output, '(?m)^INSTRUMENTATION_CODE: -1$')
    $bad = $Output -match '(?mi)^INSTRUMENTATION_STATUS_CODE: -\d+$|^INSTRUMENTATION_(FAILED|ABORTED):|FAILURES!!!|Process crashed'
    if ($bad -or $begin.Count -ne 1 -or $pass.Count -ne 1 -or $finish.Count -ne 1 -or $test.Count -lt 1 -or
        $runnerCode.Count -ne 1 -or $Output -notmatch '(?m)^OK \(1 test\)$' -or
        $begin[0].Index -ge $pass[0].Index -or $pass[0].Index -ge $finish[0].Index) {
        throw "No unambiguous JUnit PASS for '$Id'. See $OutputDirectory/$Id.log"
    }
    $geometry = @([regex]::Matches($Output, "(?m)^DAVIEW_MATRIX $($escapedId):FRAME (.+)$") |
        ForEach-Object { $_.Groups[1].Value })
    $frames = @($geometry | ForEach-Object { ($_ -split ' ')[0] })
    if (($frames -join ',') -ne ($expectedFrames[$Id] -join ',') -or
        ($Fit -eq 'native' -and @($geometry | Where-Object { $_ -notmatch '\bfit=native\b' }).Count -gt 0)) {
        throw "Expected frame sequence or fit not confirmed in '$Id'. See $OutputDirectory/$Id.log"
    }
    return $geometry
}

$result = [ordered]@{
    serial = $Serial; fit = $Fit; fontScale = $FontScale; startedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    passed = $false; error = $null; cases = @()
}
try {
    $adb = (Get-Item -LiteralPath (Join-Path $SdkDirectory 'platform-tools/adb.exe')).FullName
    $aapt = Get-ChildItem -LiteralPath (Join-Path $SdkDirectory 'build-tools') -Directory |
        ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -Last 1
    if (-not $aapt) { throw "No aapt2.exe found in $SdkDirectory/build-tools" }
    $app = (Get-Item -LiteralPath $AppApk).FullName
    $tests = (Get-Item -LiteralPath $TestApk).FullName
    $result.appSha256 = (Get-FileHash -LiteralPath $app).Hash
    $result.testsSha256 = (Get-FileHash -LiteralPath $tests).Hash
    $badging = Invoke-CheckedProcess $aapt @('dump', 'badging', $app) 'app-badging'
    if ($badging -notmatch "(?m)^package: name='com\.daview\.app\.debug'(?: |$)" -or
        $badging -notmatch '(?m)^application-debuggable$') {
        throw 'App APK is not the debuggable com.daview.app.debug build. Nothing was installed.'
    }
    $manifest = Invoke-CheckedProcess $aapt @('dump', 'xmltree', $tests, '--file', 'AndroidManifest.xml') 'test-manifest'
    if ($manifest -notmatch '(?m)^\s+A: package="com\.daview\.app\.debug\.test"' -or
        $manifest -notmatch '(?m)^\s+A: [^\n]*:targetPackage\(0x[0-9a-f]+\)="com\.daview\.app\.debug"' -or
        $manifest -notmatch '(?m)^\s+A: [^\n]*:name\(0x[0-9a-f]+\)="androidx\.test\.runner\.AndroidJUnitRunner"') {
        throw 'Instrumentation APK has the wrong package or runner target. Nothing was installed.'
    }
    $state = Invoke-CheckedProcess $adb @('-s', $Serial, 'get-state') 'device-state'
    if ($state.Trim() -ne 'device') { throw "Target '$Serial' is not available as a single authorized Android device." }
    $result.fingerprint = (Invoke-CheckedProcess $adb @('-s', $Serial, 'shell', 'getprop', 'ro.build.fingerprint') 'device-fingerprint').Trim()
    $result.apiLevel = (Invoke-CheckedProcess $adb @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.sdk') 'device-api').Trim()
    foreach ($apk in @(@{ name = 'install-debug'; path = $app }, @{ name = 'install-tests'; path = $tests })) {
        $install = Invoke-CheckedProcess $adb @('-s', $Serial, 'install', '-r', '-t', $apk.path) $apk.name
        if ($install -notmatch '(?m)^Success$') { throw "Install did not report success. See $($apk.name).log" }
    }
    foreach ($id in $Case) {
        $method = $methods[$id]
        $testClass = if ($id.StartsWith('player-')) { 'com.daview.app.player.PlaybackLifecycleOnDeviceTest' } else {
            'com.daview.app.AdaptiveUiOnDeviceTest'
        }
        $row = [ordered]@{ id = $id; method = "$testClass#$method"; passed = $false; error = $null; frames = @() }
        $result.cases += $row
        try {
            $font = $FontScale.ToString([Globalization.CultureInfo]::InvariantCulture)
            $output = Invoke-CheckedProcess $adb @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', $row.method, '-e', 'daviewFit', $Fit,
                '-e', 'daviewFontScale', $font, 'com.daview.app.debug.test/androidx.test.runner.AndroidJUnitRunner') $id $CaseTimeoutSeconds
            $row.frames = @(Assert-OneCase $id $method $output)
            $row.passed = $true
            Write-Host "PASS $id"
        } catch {
            $row.error = $_.Exception.Message
            Write-Warning "FAIL $id`: $($row.error)"
        }
    }
    $result.passed = @($result.cases | Where-Object { -not $_.passed }).Count -eq 0
    if (-not $result.passed) { $result.error = 'One or more matrix cases did not prove a pass.' }
} catch {
    $result.error = $_.Exception.Message
} finally {
    $result.finishedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    $summary = Join-Path $OutputDirectory 'result.json'
    [IO.File]::WriteAllText($summary, ($result | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
}
if (-not $result.passed) { throw "$($result.error) See $summary" }
Write-Host "Matrix passed. Evidence: $summary"
