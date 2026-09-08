<#
.SYNOPSIS
Puts libmpv where the desktop build expects it: composeApp/nativeResources/windows/libmpv-2.dll

.DESCRIPTION
The in-app player on the desktop is libmpv. The DLL is ~115 MB, so it is not in
git; this fetches it from shinchiro's Windows builds, which are the ones mpv.io
points at for Windows.

The build must be a git-master one, not a numbered release. RTX Video HDR needs
two commits that landed after 0.41.0: without them mpv sets the driver extension
but never retags the frame as HDR10 or moves the surface to a 10-bit format, and
the feature silently does nothing. shinchiro tracks master, so any recent release
here is fine.

Needs 7-Zip on PATH (GitHub's windows runners ship it).
#>
[CmdletBinding()]
param(
    # Pin a release tag (e.g. 20260903) instead of taking the latest.
    [string]$Tag = "latest",
    # x86_64 works everywhere; x86_64-v3 needs AVX2.
    [string]$Arch = "x86_64"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$target = Join-Path $repoRoot "composeApp\nativeResources\windows"
New-Item -ItemType Directory -Force -Path $target | Out-Null

$api = if ($Tag -eq "latest") {
    "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest"
} else {
    "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/tags/$Tag"
}

Write-Host "querying $api"
$headers = @{ "User-Agent" = "DAView-build" }
if ($env:GITHUB_TOKEN) { $headers["Authorization"] = "Bearer $env:GITHUB_TOKEN" }
$release = Invoke-RestMethod -Uri $api -Headers $headers

# mpv-dev-<arch>-<date>-git-<sha>.7z holds libmpv-2.dll, the import library and
# the headers. The player archive of the same date holds mpv.exe instead.
$asset = $release.assets |
    Where-Object { $_.name -like "mpv-dev-$Arch-*.7z" -and $_.name -notlike "mpv-dev-$Arch-v*" } |
    Select-Object -First 1
if (-not $asset) { throw "no mpv-dev-$Arch asset in release $($release.tag_name)" }

Write-Host "downloading $($asset.name) ($([math]::Round($asset.size/1MB,1)) MB) from $($release.tag_name)"
$archive = Join-Path ([System.IO.Path]::GetTempPath()) $asset.name
# Deliberately not $headers: GitHub redirects the asset to a signed
# githubusercontent URL that rejects a request still carrying a bearer token,
# and Windows PowerShell 5.1 does not strip it across the redirect the way
# PowerShell 7 does. The token is only useful for the API call above anyway.
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $archive -Headers @{ "User-Agent" = "DAView-build" }

if (-not (Get-Command 7z -ErrorAction SilentlyContinue)) {
    throw "7z is not on PATH; install 7-Zip or extract $archive into $target by hand"
}
& 7z e -y -o"$target" "$archive" "libmpv-2.dll" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "7z failed with $LASTEXITCODE" }

$dll = Join-Path $target "libmpv-2.dll"
if (-not (Test-Path $dll)) { throw "libmpv-2.dll was not extracted" }
Remove-Item $archive -Force
Write-Host ("{0}  {1:N1} MB" -f $dll, ((Get-Item $dll).Length / 1MB))
