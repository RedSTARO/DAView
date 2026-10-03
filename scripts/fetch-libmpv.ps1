<#
.SYNOPSIS
Fetch the pinned Windows libmpv, verifying both the archive and extracted DLL.
.DESCRIPTION
The pin is scripts/libmpv-windows.json. Update it deliberately after playback
validation. A successful build must not silently substitute a new engine.
ArchivePath allows an offline, hash-checked copy; OutputDirectory is useful
for isolated validation. The default cache is build/native-cache.
#>
[CmdletBinding()]
param(
    [string]$PinFile = (Join-Path $PSScriptRoot 'libmpv-windows.json'),
    [string]$ArchivePath = '',
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$pin = Get-Content -LiteralPath $PinFile -Raw | ConvertFrom-Json
if ($pin.repository -ne 'shinchiro/mpv-winbuild-cmake' -or
    $pin.tag -notmatch '^\d{8}$' -or $pin.architecture -ne 'x86_64' -or
    $pin.archive -notmatch '^mpv-dev-x86_64-\d{8}-git-[0-9a-f]+\.7z$' -or
    $pin.archiveSha256 -notmatch '^[0-9a-f]{64}$' -or $pin.dllSha256 -notmatch '^[0-9a-f]{64}$' -or
    $pin.archiveSize -le 0 -or $pin.dllSize -le 0) {
    throw 'Invalid libmpv pin. Use an explicit release, archive name and SHA-256 digests.'
}

function Assert-PinnedFile([string]$Path, [long]$Size, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Pinned file is missing: $Path" }
    if ((Get-Item -LiteralPath $Path).Length -ne $Size -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Pinned file failed size/SHA-256 verification: $Path"
    }
}

$cache = Join-Path $repoRoot 'build/native-cache'
[IO.Directory]::CreateDirectory($cache) | Out-Null
if (-not $ArchivePath) {
    $ArchivePath = Join-Path $cache $pin.archive
    if (-not (Test-Path -LiteralPath $ArchivePath)) {
        $download = Join-Path $cache ('.download-' + [Guid]::NewGuid().ToString('N'))
        try {
            $url = "https://github.com/$($pin.repository)/releases/download/$($pin.tag)/$($pin.archive)"
            Write-Host "Downloading pinned libmpv $($pin.tag)"
            # Public release assets need no token. Do not forward credentials
            # to GitHub's redirected download host.
            Invoke-WebRequest -Uri $url -OutFile $download -TimeoutSec 300 -Headers @{ 'User-Agent'='DAView-build' }
            Assert-PinnedFile $download $pin.archiveSize $pin.archiveSha256
            Move-Item -LiteralPath $download -Destination $ArchivePath
        } finally {
            if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download -Force }
        }
    }
}
$ArchivePath = [IO.Path]::GetFullPath($ArchivePath)
# A corrupted cache fails too; it is never mistaken for a successful fetch.
Assert-PinnedFile $ArchivePath $pin.archiveSize $pin.archiveSha256

if (-not $OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'composeApp/nativeResources/windows' }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$staging = Join-Path $cache ('.extract-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($staging) | Out-Null
$stagedDll = Join-Path $staging 'libmpv-2.dll'
$candidate = $null
$provenanceCandidate = $null
try {
    $sevenZip = Get-Command 7z -ErrorAction SilentlyContinue
    $tar = Get-Command tar -ErrorAction SilentlyContinue
    if ($sevenZip) {
        & $sevenZip.Source e -y "-o$staging" $ArchivePath 'libmpv-2.dll' | Out-Null
    } elseif ($tar) {
        & $tar.Source -xf $ArchivePath -C $staging 'libmpv-2.dll'
    } else { throw 'Extraction needs 7-Zip or Windows tar on PATH.' }
    if ($LASTEXITCODE -ne 0) { throw "libmpv extraction failed with $LASTEXITCODE" }
    Assert-PinnedFile $stagedDll $pin.dllSize $pin.dllSha256

    # Do not touch an installed copy until both verifications succeed. Stage
    # replacement files on the destination volume so the DLL swap is atomic.
    [IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
    $candidate = Join-Path $OutputDirectory ('libmpv-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $provenanceCandidate = Join-Path $OutputDirectory ('libmpv-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    Copy-Item -LiteralPath $stagedDll -Destination $candidate
    Assert-PinnedFile $candidate $pin.dllSize $pin.dllSha256
    [IO.File]::WriteAllText($provenanceCandidate, [IO.File]::ReadAllText([IO.Path]::GetFullPath($PinFile)), [Text.UTF8Encoding]::new($false))
    foreach ($pair in @(@($candidate, (Join-Path $OutputDirectory 'libmpv-2.dll')), @($provenanceCandidate, (Join-Path $OutputDirectory 'libmpv-build.json')))) {
        if (Test-Path -LiteralPath $pair[1]) { [IO.File]::Replace($pair[0], $pair[1], [NullString]::Value) }
        else { [IO.File]::Move($pair[0], $pair[1]) }
    }
    Write-Host "Verified libmpv $($pin.tag), DLL SHA-256 $($pin.dllSha256)"
} finally {
    # Only exact files created by this invocation are removed; no recursive
    # cleanup of a caller-supplied directory or existing DLL is performed.
    foreach ($temporary in @($candidate, $provenanceCandidate, $stagedDll)) {
        if ($temporary -and (Test-Path -LiteralPath $temporary)) { Remove-Item -LiteralPath $temporary -Force }
    }
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging }
}
