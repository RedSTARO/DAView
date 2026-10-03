param(
    [string]$OutputDirectory = "",
    [int]$Port = 5580,
    [switch]$ReleaseUiSmoke
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo ('build/audit/android-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory for each isolated emulator run.' }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$emulator = Join-Path $sdk 'emulator/emulator.exe'
$serial = "emulator-$Port"
$avdName = 'DAViewAudit'
$emulatorProcess = $null
$started = [DateTimeOffset]::UtcNow
$result = [ordered]@{ startedAt = $started.ToString('o'); passed = $false; error = $null; device = $serial }
$result.uiVariant = if ($ReleaseUiSmoke) { 'release' } else { 'debug' }

function Invoke-Tool([string]$Executable, [string[]]$Arguments, [string]$Name, [int]$Timeout = 120000) {
    $info = [Diagnostics.ProcessStartInfo]::new($Executable)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        if (-not $process.WaitForExit($Timeout)) {
            $process.Kill($true)
            throw "$Name timed out"
        }
        $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        [IO.File]::WriteAllText((Join-Path $OutputDirectory "$Name.log"), $text, [Text.UTF8Encoding]::new($false))
        if ($process.ExitCode -ne 0) { throw "$Name failed with exit code $($process.ExitCode)" }
        return $text
    } finally { $process.Dispose() }
}

try {
    if ($Port -lt 5554 -or $Port -gt 5682 -or $Port % 2 -ne 0) { throw 'Choose an even Android emulator port (5554..5682).' }
    foreach ($candidate in @($Port, ($Port + 1))) {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $candidate)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    $imageRelative = 'system-images/android-36/default/x86_64/'
    if (-not (Test-Path -LiteralPath (Join-Path $sdk ($imageRelative + 'system.img')))) { throw 'Android 36 default x86_64 image is not installed.' }
    $env:ANDROID_HOME = $sdk
    $tasks = @(':composeApp:assembleDebug', ':composeApp:assembleDebugAndroidTest')
    if ($ReleaseUiSmoke) { $tasks += ':composeApp:assembleRelease' }
    & (Join-Path $repo 'gradlew.bat') @tasks --console=plain -q *> (Join-Path $OutputDirectory 'gradle.log')
    $result.buildExitCode = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed; see gradle.log.' }

    $apk = (Get-Item 'composeApp/build/outputs/apk/debug/composeApp-debug.apk').FullName
    $testApks = @(Get-ChildItem 'composeApp/build/outputs/apk/androidTest/debug' -Filter '*.apk')
    if ($testApks.Count -ne 1) { throw 'Expected one instrumentation APK.' }
    $zip = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        foreach ($name in @('DAView-GPL-3.0.txt', 'DAView-NOTICE.txt')) {
            $entry = $zip.GetEntry("assets/licenses/$name")
            if ($null -eq $entry) { throw "APK is missing $name" }
            $stream = $entry.Open()
            try {
                $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))
            } finally { $stream.Dispose() }
            $expected = (Get-FileHash -LiteralPath "composeApp/src/androidMain/assets/licenses/$name").Hash
            if ($actual -ne $expected) { throw "APK license differs from source: $name" }
        }
    } finally { $zip.Dispose() }
    $result.apkLicensesVerified = $true

    if ($ReleaseUiSmoke) {
        # Sign a COPY using a throwaway QA certificate. Production signing
        # material is never read, and the unsigned release artifact is untouched.
        $env:DAVIEW_AUDIT_KEYPASS = [Guid]::NewGuid().ToString('N')
        $keyStore = Join-Path $OutputDirectory 'audit-only.p12'
        Invoke-Tool (Get-Command keytool).Source @('-genkeypair', '-keystore', $keyStore, '-storetype', 'PKCS12', '-alias', 'audit', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', 'CN=DAView Runtime Audit', '-storepass:env', 'DAVIEW_AUDIT_KEYPASS', '-keypass:env', 'DAVIEW_AUDIT_KEYPASS') 'qa-key' | Out-Null
        $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending | Select-Object -First 1
        $signer = Join-Path $buildTools.FullName 'apksigner.bat'
        $unsigned = Get-ChildItem 'composeApp/build/outputs/apk/release' -Filter '*.apk' | Select-Object -First 1
        $releaseApk = Join-Path $OutputDirectory 'DAView-release-audit-only.apk'
        & $signer sign --ks $keyStore --ks-key-alias audit --ks-pass env:DAVIEW_AUDIT_KEYPASS --key-pass env:DAVIEW_AUDIT_KEYPASS --v4-signing-enabled false --out $releaseApk $unsigned.FullName *> (Join-Path $OutputDirectory 'sign-release.log')
        if ($LASTEXITCODE -ne 0) { throw 'QA signing failed.' }
        & $signer verify $releaseApk *> (Join-Path $OutputDirectory 'verify-signature.log')
        if ($LASTEXITCODE -ne 0) { throw 'QA APK signature verification failed.' }
        $result.releaseApk = $releaseApk
        $result.releaseUnsignedSha256 = (Get-FileHash -LiteralPath $unsigned.FullName).Hash
        $result.releaseSigning = 'Temporary audit certificate only; not for distribution.'
    }

    # Build an AVD using only known hardware settings and the installed system
    # image. No existing AVD config, user image, account or snapshot is copied.
    $avdRoot = Join-Path $OutputDirectory 'avd'
    $avd = Join-Path $avdRoot "$avdName.avd"
    [IO.Directory]::CreateDirectory($avd) | Out-Null
    $env:ANDROID_AVD_HOME = $avdRoot
    $config = @"
AvdId=$avdName
avd.ini.displayname=DAView isolated runtime audit
avd.ini.encoding=UTF-8
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.keyboard=yes
hw.mainKeys=no
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.audioInput=no
hw.camera.back=none
hw.camera.front=none
hw.sdCard=no
disk.dataPartition.size=2G
image.sysdir.1=$imageRelative
tag.id=default
target=android-36
"@
    [IO.File]::WriteAllText((Join-Path $avd 'config.ini'), $config, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $avdRoot "$avdName.ini"), "avd.ini.encoding=UTF-8`npath=$avd`ntarget=android-36`n", [Text.UTF8Encoding]::new($false))
    $emulatorProcess = Start-Process -FilePath $emulator -ArgumentList @('-avd', $avdName, '-port', $Port, '-no-window', '-no-snapshot', '-no-audio', '-no-boot-anim', '-gpu', 'swiftshader_indirect') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $OutputDirectory 'emulator.stdout.log') -RedirectStandardError (Join-Path $OutputDirectory 'emulator.stderr.log') -PassThru
    $result.emulatorProcessId = $emulatorProcess.Id
    Invoke-Tool $adb @('-s', $serial, 'wait-for-device') 'device-connect' 180000 | Out-Null
    # Boot waiting is bounded inside the background worker, never model polling.
    Invoke-Tool $adb @('-s', $serial, 'shell', 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done') 'device-boot' 180000 | Out-Null
    $actualAvd = Invoke-Tool $adb @('-s', $serial, 'emu', 'avd', 'name') 'avd-identity'
    if ($actualAvd -notmatch "(?m)^$avdName\r?$") { throw 'Connected emulator is not the isolated audit AVD.' }
    Invoke-Tool $adb @('-s', $serial, 'install', '-r', $apk) 'install-app' | Out-Null
    Invoke-Tool $adb @('-s', $serial, 'install', '-r', $testApks[0].FullName) 'install-tests' | Out-Null
    $instrumentation = Invoke-Tool $adb @('-s', $serial, 'shell', 'am', 'instrument', '-w', '-r', 'com.daview.app.test/androidx.test.runner.AndroidJUnitRunner') 'instrumentation' 300000
    $result.instrumentationPassed = ($instrumentation -match 'OK \(\d+ tests?\)' -and $instrumentation -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed')
    $result.instrumentationSummary = [regex]::Match($instrumentation, 'OK \(\d+ tests?\)').Value
    # Raw output (-r) includes skipped (-3) and assumption failure (-4) status.
    # optional real episode fixture is intentionally absent from this fresh AVD.
    $result.skipped = [regex]::Matches($instrumentation, 'INSTRUMENTATION_STATUS_CODE: -(3|4)\b').Count
    $result.completedTests = [regex]::Matches($instrumentation, 'INSTRUMENTATION_STATUS_CODE: 0\b').Count
    if ($result.completedTests -eq 0) { throw 'No completed instrumentation test cases were reported.' }
    if (-not $result.instrumentationPassed) { throw 'Instrumentation tests failed; see instrumentation.log.' }
    $result.instrumentationVariant = 'debug'
    if ($ReleaseUiSmoke) {
        # The existing instrumentation suite references app classes directly;
        # use public UI for R8 release checks instead of treating debug-class
        # names as a contract of the obfuscated app.
        Invoke-Tool $adb @('-s', $serial, 'uninstall', 'com.daview.app.test') 'uninstall-debug-tests' | Out-Null
        Invoke-Tool $adb @('-s', $serial, 'uninstall', 'com.daview.app') 'uninstall-debug-app' | Out-Null
        Invoke-Tool $adb @('-s', $serial, 'install', $releaseApk) 'install-release' | Out-Null
        Invoke-Tool $adb @('-s', $serial, 'root') 'audit-adb-root' | Out-Null
        Invoke-Tool $adb @('-s', $serial, 'wait-for-device') 'root-reconnect' | Out-Null
        # This AOSP test image permits root; needed only to assert that an
        # invalid settings submission did not change the isolated config.
        $uid = Invoke-Tool $adb @('-s', $serial, 'shell', 'id', '-u') 'audit-uid'
        if ($uid.Trim() -ne '0') { throw 'The isolated AOSP test image did not allow config verification.' }
        Invoke-Tool $adb @('-s', $serial, 'shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '0') 'hardware-keyboard' | Out-Null
    }
    $launch = Invoke-Tool $adb @('-s', $serial, 'shell', 'am', 'start', '-W', '-n', 'com.daview.app/.MainActivity') 'launch-app'
    if ($launch -match 'Error:|Exception') { throw 'The app did not launch.' }
    if ($ReleaseUiSmoke) {
        . (Join-Path $PSScriptRoot 'android-release-ui.ps1')
        Invoke-ReleaseUiChecks
    }
    Invoke-Tool $adb @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/daview-audit-ui.xml') 'dump-ui' | Out-Null
    Invoke-Tool $adb @('-s', $serial, 'pull', '/sdcard/daview-audit-ui.xml', (Join-Path $OutputDirectory 'home.xml')) 'pull-ui' | Out-Null
    Invoke-Tool $adb @('-s', $serial, 'shell', 'screencap', '-p', '/sdcard/daview-audit-home.png') 'screenshot' | Out-Null
    Invoke-Tool $adb @('-s', $serial, 'pull', '/sdcard/daview-audit-home.png', (Join-Path $OutputDirectory 'home.png')) 'pull-screenshot' | Out-Null
    $appPid = Invoke-Tool $adb @('-s', $serial, 'shell', 'pidof', 'com.daview.app') 'app-pid'
    if ($appPid -notmatch '\d+') { throw 'The app exited during startup.' }
    $result.appAliveAfterLaunch = $true
    $result.passed = $true
} catch {
    $result.error = $_.Exception.Message
} finally {
    if ($null -ne $emulatorProcess) {
        try { Invoke-Tool $adb @('-s', $serial, 'logcat', '-d', '-v', 'threadtime') 'logcat' 15000 | Out-Null } catch { }
        try { Invoke-Tool $adb @('-s', $serial, 'emu', 'kill') 'shutdown' 15000 | Out-Null } catch { }
        if (-not $emulatorProcess.WaitForExit(15000)) { $emulatorProcess.Kill($true) }
        $emulatorProcess.Dispose()
    }
    $result.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $temporary = Join-Path $OutputDirectory 'result.json.tmp'
    [IO.File]::WriteAllText($temporary, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination (Join-Path $OutputDirectory 'result.json')
}
if ($result.passed) { exit 0 }
exit 1
