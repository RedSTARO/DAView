param([string]$OutputDirectory = "")

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
$tasks = @(':composeApp:createDistributable', ':composeApp:createReleaseDistributable', ':composeApp:assembleRelease')

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
    $data = Join-Path $OutputDirectory "data-$variant"
    $info = [Diagnostics.ProcessStartInfo]::new($exe)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WorkingDirectory = $directory
    $info.Environment['DAVIEW_DATA'] = $data
    # Do not let a developer's environment connect the fresh smoke-test data
    # directory to their real storage or scraper accounts.
    foreach ($key in @('DAVIEW_WEBDAV_URL','DAVIEW_WEBDAV_USER','DAVIEW_WEBDAV_PASS','DAVIEW_TMDB_KEY','DAVIEW_TVDB_KEY','DAVIEW_BANGUMI_TOKEN')) {
        [void]$info.Environment.Remove($key)
    }
    $process = [Diagnostics.Process]::Start($info)
    try {
        if (-not $process.WaitForInputIdle(30000)) { throw "$variant launcher did not become ready" }
        # A short settling interval inside the worker, without model polling.
        if ($process.WaitForExit(8000)) { throw "$variant exited during startup ($($process.ExitCode))" }
        $process.Refresh()
        if ($process.MainWindowHandle -eq [IntPtr]::Zero) { throw "$variant has no main window" }
        $database = Join-Path $data 'daview.db'
        if (-not (Test-Path -LiteralPath $database)) { throw "$variant did not open its isolated database" }
        $log = Join-Path $data 'logs/daview.log'
        if (-not (Test-Path -LiteralPath $log)) { throw "$variant did not initialize file logging" }
        $logged = [IO.File]::ReadAllText($log)
        if ($logged -match '(?m)\bERROR\b|uncaught exception|NoClassDefFoundError|NoSuchMethodError') { throw "$variant startup log contains errors" }
        return @{variant=$variant; launcher=$exe; startupPassed=$true; licensesVerified=$true; databaseBytes=(Get-Item $database).Length; log=$log}
    } finally {
        # Closing the test process directly avoids writing window preferences
        # into the desktop user's real UI settings node on the normal quit path.
        if (-not $process.HasExited) { $process.Kill($true); [void]$process.WaitForExit(15000) }
        $process.Dispose()
    }
}

try {
    $before = Snapshot
    $before | Set-Content -LiteralPath (Join-Path $OutputDirectory 'inputs.sha256') -Encoding utf8
    $result.gitCommit = (& git rev-parse HEAD).Trim()
    $result.gitStatus = @(& git status --short)
    & (Join-Path $repo 'gradlew.bat') @tasks --continue --console=plain -q *> (Join-Path $OutputDirectory 'gradle.log')
    $result.gradleExitCode = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw 'Artifact build failed; see gradle.log.' }
    foreach ($variant in @('main','main-release')) { $result.desktop += Smoke-Desktop $variant }

    $apks = @(Get-ChildItem 'composeApp/build/outputs/apk/release' -Filter '*.apk')
    if ($apks.Count -ne 1) { throw 'Expected exactly one Android release APK' }
    $zip = [IO.Compression.ZipFile]::OpenRead($apks[0].FullName)
    try {
        foreach ($name in @('DAView-GPL-3.0.txt','DAView-NOTICE.txt')) {
            $entry = $zip.GetEntry("assets/licenses/$name")
            if ($null -eq $entry) { throw "Release APK is missing $name" }
            $stream = $entry.Open()
            try { $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
            finally { $stream.Dispose() }
            if ($actual -ne (Get-FileHash -LiteralPath "composeApp/src/androidMain/assets/licenses/$name").Hash) { throw "Release APK license differs: $name" }
        }
        $abis = @('arm64-v8a','armeabi-v7a','x86','x86_64')
        $missingNative = @(foreach ($abi in $abis) {
            foreach ($library in @('libffmpegJNI.so','libavcodec.so','libavutil.so','libswresample.so')) {
                if ($null -eq $zip.GetEntry("lib/$abi/$library")) { "lib/$abi/$library" }
            }
        })
        $result.android = @{apk=$apks[0].FullName; bytes=$apks[0].Length; sha256=(Get-FileHash $apks[0].FullName).Hash; licensesVerified=$true; missingFfmpegLibraries=$missingNative}
    } finally { $zip.Dispose() }
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
