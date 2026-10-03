param([Parameter(Mandatory)][string]$ArchivePath)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$caseRoot=Join-Path $repo ('build/audit/fetch-mpv-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($caseRoot) | Out-Null
$script=Join-Path $PSScriptRoot 'fetch-libmpv.ps1'
$pinFile=Join-Path $PSScriptRoot 'libmpv-windows.json'
$pin=Get-Content -LiteralPath $pinFile -Raw | ConvertFrom-Json
$ArchivePath=[IO.Path]::GetFullPath($ArchivePath)
$target=Join-Path $caseRoot 'output'
[IO.Directory]::CreateDirectory($target) | Out-Null
$dll=Join-Path $target 'libmpv-2.dll'
[IO.File]::WriteAllText($dll,'existing DLL sentinel')
$sentinel=(Get-FileHash -LiteralPath $dll).Hash
$passed=@()
function Must-Reject([scriptblock]$Action) {
    $rejected=$false
    try { & $Action } catch { $rejected=$true }
    if (-not $rejected) { throw 'Expected a rejected input' }
    if ((Get-FileHash -LiteralPath $dll).Hash -ne $sentinel) { throw 'Invalid input replaced the existing DLL' }
}
try {
    $badArchive=Join-Path $caseRoot 'broken.7z'
    [IO.File]::WriteAllText($badArchive,'corrupt')
    Must-Reject { & $script -ArchivePath $badArchive -OutputDirectory $target }
    $passed+='corrupt archive preserves existing DLL'
    $wrongPin=Join-Path $caseRoot 'wrong-pin.json'
    $modified=Get-Content -LiteralPath $pinFile -Raw | ConvertFrom-Json
    $modified.dllSha256='0'*64
    $modified | ConvertTo-Json | Set-Content -LiteralPath $wrongPin -Encoding utf8
    Must-Reject { & $script -PinFile $wrongPin -ArchivePath $ArchivePath -OutputDirectory $target }
    $passed+='extracted DLL mismatch preserves existing DLL'
    & $script -ArchivePath $ArchivePath -OutputDirectory $target
    if ((Get-FileHash -LiteralPath $dll).Hash -ne $pin.dllSha256) { throw 'Valid extraction produced the wrong DLL' }
    if ((Get-FileHash -LiteralPath (Join-Path $target 'libmpv-build.json')).Hash -ne (Get-FileHash -LiteralPath $pinFile).Hash) { throw 'Provenance was not preserved' }
    $passed+='valid archive replaces DLL and preserves pin provenance'
    & $script -ArchivePath $ArchivePath -OutputDirectory $target
    if ((Get-FileHash -LiteralPath $dll).Hash -ne $pin.dllSha256) { throw 'Repeated installation changed the DLL' }
    $passed+='repeat installation is idempotent'
    if (@(Get-ChildItem -LiteralPath $target -Filter '*.tmp').Count -ne 0) { throw 'Temporary destination files were left behind' }
    @{passed=$true; checks=$passed; output=$target} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $caseRoot 'result.json') -Encoding utf8
    Write-Output "Verified $($passed.Count) fetch checks; result at $caseRoot/result.json"
} catch {
    @{passed=$false; checks=$passed; error=$_.Exception.Message} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $caseRoot 'result.json') -Encoding utf8
    throw
}
