param([string]$OutputDirectory = "", [switch]$DesktopOnly)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $IsWindows) { throw 'This verifier checks Windows desktop packages.' }
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repo ('build/audit/release-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath (Join-Path $OutputDirectory 'result.json')) { throw 'Preserve the earlier result and use a new directory.' }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$started = [DateTimeOffset]::UtcNow
$result = [ordered]@{startedAt=$started.ToString('o'); verified=$false; error=$null; desktop=@(); android=$null}
$tasks = @(':composeApp:createDistributable', ':composeApp:createReleaseDistributable')
if (-not $DesktopOnly) { $tasks += ':composeApp:assembleRelease' }
$result.scope = if ($DesktopOnly) { 'windows-desktop' } else { 'windows-desktop-and-android' }

# A jpackage launcher can own a second process, and Process.MainWindowHandle
# ignores owned windows. Inspect visible AWT frames belonging only to this
# launch and its descendants instead of treating a zero convenience handle
# as an application startup failure.
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public sealed class AuditWindowInfo {
    public uint ProcessId;
    public string Title;
    public string ClassName;
    public bool Visible;
}
public static class AuditWindows {
    private delegate bool EnumProc(IntPtr hwnd, IntPtr parameter);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc callback, IntPtr parameter);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int length);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, StringBuilder text, int length);
    public static AuditWindowInfo[] Owned(uint[] processes) {
        var allowed = new HashSet<uint>(processes);
        var windows = new List<AuditWindowInfo>();
        EnumWindows((hwnd, parameter) => {
            uint pid; GetWindowThreadProcessId(hwnd, out pid);
            if (allowed.Contains(pid)) {
                var title = new StringBuilder(512);
                var cls = new StringBuilder(256);
                GetWindowText(hwnd, title, title.Capacity);
                GetClassName(hwnd, cls, cls.Capacity);
                windows.Add(new AuditWindowInfo { ProcessId=pid, Title=title.ToString(), ClassName=cls.ToString(), Visible=IsWindowVisible(hwnd) });
            }
            return true;
        }, IntPtr.Zero);
        return windows.ToArray();
    }
}
'@

function Get-LaunchedProcessIds([int]$root) {
    $queue = [Collections.Generic.Queue[int]]::new()
    $queue.Enqueue($root)
    while ($queue.Count -gt 0) {
        $current = $queue.Dequeue()
        [uint32]$current
        foreach ($child in Get-CimInstance Win32_Process -Filter "ParentProcessId=$current") {
            $queue.Enqueue([int]$child.ProcessId)
        }
    }
}

function Write-Phase([string]$name) {
    @{phase=$name; processId=$PID; at=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $OutputDirectory 'phase.json') -Encoding utf8
}

function Snapshot {
    $files = @(& git ls-files -co --exclude-standard | Sort-Object -Unique)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate source files' }
    return @(foreach ($file in $files) {
        if (Test-Path -LiteralPath $file -PathType Leaf) { "$file $((Get-FileHash -LiteralPath $file).Hash)" }
        else { "$file MISSING" }
    })
}

function Verify-Copies([string]$directory) {
    foreach ($name in @('DAView-GPL-3.0.txt','DAView-NOTICE.txt')) {
        $path = Join-Path $directory "licenses/$name"
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing package license: $path" }
        $source = Join-Path $repo "composeApp/nativeResources/common/licenses/$name"
        if ((Get-FileHash -LiteralPath $path).Hash -ne (Get-FileHash -LiteralPath $source).Hash) { throw "License copy mismatch: $path" }
    }
}

function Smoke-Desktop([string]$variant) {
    $directory = Join-Path $repo "composeApp/build/compose/binaries/$variant/app/DAView"
    $exe = Join-Path $directory 'DAView.exe'
    if (-not (Test-Path -LiteralPath $exe)) { throw "Missing desktop launcher: $exe" }
    Verify-Copies (Join-Path $directory 'app/resources')
    $mpv = Join-Path $directory 'app/resources/libmpv-2.dll'
    if (-not (Test-Path -LiteralPath $mpv)) { throw "Missing packaged libmpv: $variant" }
    $pin = Get-Content -LiteralPath (Join-Path $repo 'scripts/libmpv-windows.json') -Raw | ConvertFrom-Json
    if ((Get-Item -LiteralPath $mpv).Length -ne $pin.dllSize -or (Get-FileHash -LiteralPath $mpv).Hash -ne $pin.dllSha256) {
        throw "Packaged libmpv differs from the tested pin: $variant"
    }
    $provenance = Join-Path $directory 'app/resources/libmpv-build.json'
    if ((Get-FileHash -LiteralPath $provenance).Hash -ne (Get-FileHash -LiteralPath (Join-Path $repo 'scripts/libmpv-windows.json')).Hash) {
        throw "Packaged libmpv provenance differs from the pin: $variant"
    }
    foreach ($name in @('Copyright','LICENSE.GPL','SOURCES.txt')) {
        $packaged = Join-Path $directory "app/resources/licenses/mpv/$name"
        $source = Join-Path $repo "composeApp/nativeResources/windows/licenses/mpv/$name"
        if ((Get-FileHash -LiteralPath $packaged).Hash -ne (Get-FileHash -LiteralPath $source).Hash) {
            throw "Packaged mpv notice differs: $variant/$name"
        }
    }
    $data = Join-Path $OutputDirectory "data-$variant"
    $info = [Diagnostics.ProcessStartInfo]::new($exe)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.WorkingDirectory = $directory
    $info.Environment['DAVIEW_DATA'] = $data
    # Do not let a developer's environment connect the fresh smoke-test data
    # directory to their real storage or scraper accounts.
    foreach ($key in @('DAVIEW_WEBDAV_URL','DAVIEW_WEBDAV_USER','DAVIEW_WEBDAV_PASS','DAVIEW_TMDB_KEY','DAVIEW_TVDB_KEY','DAVIEW_BANGUMI_TOKEN')) {
        [void]$info.Environment.Remove($key)
    }
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    try {
        if (-not $process.WaitForInputIdle(30000)) { throw "$variant launcher did not become ready" }
        # A short settling interval inside the worker, without model polling.
        if ($process.WaitForExit(8000)) { throw "$variant exited during startup ($($process.ExitCode))" }
        $process.Refresh()
        $processIds = @(Get-LaunchedProcessIds $process.Id)
        $windows = @([AuditWindows]::Owned([uint32[]]$processIds))
        $windows | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $OutputDirectory "$variant.windows.json") -Encoding utf8
        if (@($windows | Where-Object { $_.ClassName -eq 'SunAwtFrame' -and $_.Visible }).Count -eq 0) { throw "$variant has no visible AWT main window" }
        $database = Join-Path $data 'daview.db'
        if (-not (Test-Path -LiteralPath $database)) { throw "$variant did not open its isolated database" }
        $log = Join-Path $data 'logs/daview.log'
        if (-not (Test-Path -LiteralPath $log)) { throw "$variant did not initialize file logging" }
        # Logback keeps a writer open. A read handle must allow that existing
        # writer (and rolling rename) rather than request FileShare.Read only.
        $logStream = [IO.File]::Open($log, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
        $reader = [IO.StreamReader]::new($logStream, [Text.Encoding]::UTF8)
        try { $logged = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($logged -match '(?m)\bERROR\b|uncaught exception|NoClassDefFoundError|NoSuchMethodError') { throw "$variant startup log contains errors" }
        return @{variant=$variant; launcher=$exe; startupPassed=$true; licensesVerified=$true; mpvSha256=$pin.dllSha256; databaseBytes=(Get-Item $database).Length; log=$log}
    } finally {
        # Closing the test process directly avoids writing window preferences
        # into the desktop user's real UI settings node on the normal quit path.
        if (-not $process.HasExited) { $process.Kill($true); [void]$process.WaitForExit(15000) }
        [IO.File]::WriteAllText((Join-Path $OutputDirectory "$variant.stdout.log"), $stdout.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $OutputDirectory "$variant.stderr.log"), $stderr.GetAwaiter().GetResult(), [Text.UTF8Encoding]::new($false))
        $process.Dispose()
    }
}

try {
    $before = Snapshot
    $before | Set-Content -LiteralPath (Join-Path $OutputDirectory 'inputs.sha256') -Encoding utf8
    $result.gitCommit = (& git rev-parse HEAD).Trim()
    $result.gitStatus = @(& git status --short)
    Write-Phase 'build'
    & (Join-Path $repo 'gradlew.bat') @tasks --continue --console=plain -q *> (Join-Path $OutputDirectory 'gradle.log')
    $result.gradleExitCode = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw 'Artifact build failed; see gradle.log.' }
    foreach ($variant in @('main','main-release')) {
        Write-Phase "startup-$variant"
        $result.desktop += Smoke-Desktop $variant
    }

    if (-not $DesktopOnly) {
        Write-Phase 'android-apk'
        $apks = @(Get-ChildItem 'composeApp/build/outputs/apk/release' -Filter '*.apk')
        if ($apks.Count -ne 1) { throw 'Expected exactly one Android release APK' }
        $zip = [IO.Compression.ZipFile]::OpenRead($apks[0].FullName)
        try {
            foreach ($name in @('DAView-GPL-3.0.txt','DAView-NOTICE.txt','sqlite/Apache-2.0.txt')) {
                $entry = $zip.GetEntry("assets/licenses/$name")
                if ($null -eq $entry) { throw "Release APK is missing $name" }
                $stream = $entry.Open()
                try { $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
                finally { $stream.Dispose() }
                if ($actual -ne (Get-FileHash -LiteralPath "composeApp/src/androidMain/assets/licenses/$name").Hash) { throw "Release APK license differs: $name" }
            }
            $abis = @('arm64-v8a','armeabi-v7a','x86','x86_64')
            $missingNative = @(foreach ($abi in $abis) {
                foreach ($library in @('libffmpegJNI.so','libavcodec.so','libavutil.so','libswresample.so','libsqliteJni.so')) {
                    if ($null -eq $zip.GetEntry("lib/$abi/$library")) { "lib/$abi/$library" }
                }
            })
            $result.android = @{apk=$apks[0].FullName; bytes=$apks[0].Length; sha256=(Get-FileHash $apks[0].FullName).Hash; licensesVerified=$true; missingNativeLibraries=$missingNative}
            if ($missingNative.Count -gt 0) { throw "Release APK is missing native libraries: $($missingNative -join ', ')" }
        } finally { $zip.Dispose() }
    }
    $result.inputFilesUnchanged = ($null -eq (Compare-Object $before (Snapshot)))
    if (-not $result.inputFilesUnchanged) { throw 'Source files changed during verification' }
    $result.verified = $true
} catch { $result.error = $_.Exception.Message }
finally {
    $result.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $temporary = Join-Path $OutputDirectory 'result.json.tmp'
    [IO.File]::WriteAllText($temporary, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination (Join-Path $OutputDirectory 'result.json') -Force
}
if ($result.verified) { exit 0 } else { exit 1 }
