# Sourced by verify-android-runtime.ps1. All input is sent only to its fresh AVD.
$script:uiSequence = 0

function Read-AuditUi {
    $script:uiSequence++
    $name = "release-ui-$script:uiSequence"
    Invoke-Tool $adb @('-s', $serial, 'shell', 'uiautomator', 'dump', '/sdcard/daview-audit-ui.xml') "$name-dump" | Out-Null
    $path = Join-Path $OutputDirectory "$name.xml"
    Invoke-Tool $adb @('-s', $serial, 'pull', '/sdcard/daview-audit-ui.xml', $path) "$name-pull" | Out-Null
    [xml]$document = [IO.File]::ReadAllText($path)
    return ,$document
}

function Audit-Bounds($node) {
    if ($node.GetAttribute('bounds') -notmatch '^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$') { return $null }
    return @{left=[int]$Matches[1]; top=[int]$Matches[2]; right=[int]$Matches[3]; bottom=[int]$Matches[4]}
}

function Find-AuditText($document, [string]$text, [bool]$prefix = $false) {
    foreach ($node in $document.SelectNodes('//node')) {
        $value = $node.GetAttribute('text')
        if (($prefix -and $value.StartsWith($text)) -or (-not $prefix -and $value -eq $text)) {
            $bounds = Audit-Bounds $node
            if ($bounds -and $bounds.right -gt $bounds.left -and $bounds.bottom -gt $bounds.top -and $bounds.top -ge 0 -and $bounds.bottom -le 2400) { return ,$node }
        }
    }
    return $null
}

function Tap-AuditNode($node, [string]$name) {
    while ($node -is [System.Xml.XmlElement] -and $node.GetAttribute('clickable') -ne 'true') { $node = $node.ParentNode }
    if ($node -isnot [System.Xml.XmlElement]) { throw "No clickable parent for $name" }
    if ($node.GetAttribute('enabled') -ne 'true') { throw "Disabled control: $name" }
    $b = Audit-Bounds $node
    if (-not $b -or $b.right -le $b.left -or $b.bottom -le $b.top) { throw "No visible bounds for $name" }
    $x = [int](($b.left + $b.right) / 2)
    $y = [int](($b.top + $b.bottom) / 2)
    Invoke-Tool $adb @('-s', $serial, 'shell', 'input', 'tap', "$x", "$y") "tap-$name" | Out-Null
}

function Scroll-AuditUi($document, [bool]$horizontal) {
    foreach ($node in $document.SelectNodes('//node[@scrollable="true"]')) {
        $b = Audit-Bounds $node
        if (-not $b) { continue }
        $width = $b.right - $b.left
        $height = $b.bottom - $b.top
        if ($width -le 0 -or $height -le 0 -or (($width -gt 2 * $height) -ne $horizontal)) { continue }
        if ($horizontal) {
            $y = [int](($b.top + $b.bottom) / 2)
            $coords = @([int]($b.right - $width * .15), $y, [int]($b.left + $width * .15), $y)
        } else {
            $x = [int](($b.left + $b.right) / 2)
            $coords = @($x, [int]($b.bottom - $height * .2), $x, [int]($b.top + $height * .2))
        }
        Invoke-Tool $adb (@('-s', $serial, 'shell', 'input', 'swipe') + @($coords | ForEach-Object { "$_" }) + @('300')) "scroll-$script:uiSequence" | Out-Null
        return
    }
    throw 'The expected scroll container is not visible.'
}

function Require-AuditText([string]$text, [bool]$scroll = $false, [bool]$horizontal = $false, [bool]$prefix = $false) {
    # Bounded UI-test synchronization inside the worker, not a model wait loop.
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $document = Read-AuditUi
        $node = Find-AuditText $document $text $prefix
        if ($node) { return ,$node }
        if ($scroll) { Scroll-AuditUi $document $horizontal }
    }
    throw "Expected UI text not found: $text"
}

function Invoke-ReleaseUiChecks {
    Require-AuditText '还没有媒体库' | Out-Null
    Tap-AuditNode (Require-AuditText '前往设置') 'settings'
    Require-AuditText 'WebDAV 存储' | Out-Null
    $document = Read-AuditUi
    $fields = @($document.SelectNodes('//node[@class="android.widget.EditText"]'))
    if ($fields.Count -lt 3) { throw 'Storage form fields are not exposed.' }
    Tap-AuditNode $fields[2] 'password'
    Invoke-Tool $adb @('-s', $serial, 'shell', 'input', 'text', 'audit-draft') 'type-password' | Out-Null
    $document = Read-AuditUi
    $fields = @($document.SelectNodes('//node[@class="android.widget.EditText"]'))
    Tap-AuditNode $fields[0] 'url'
    Invoke-Tool $adb @('-s', $serial, 'shell', 'input', 'text', 'not-a-url') 'type-url' | Out-Null
    Tap-AuditNode (Require-AuditText '保存' $true) 'save-invalid'
    Require-AuditText 'WebDAV 地址必须是包含主机名的 http:// 或 https:// 地址' | Out-Null
    $configText = Invoke-Tool $adb @('-s', $serial, 'shell', 'cat', '/data/user/0/com.daview.app/files/daview/config.json') 'release-config'
    $config = $configText | ConvertFrom-Json
    if ($config.storage.url -ne '' -or $config.storage.password -ne '') { throw 'Rejected storage settings were persisted.' }
    $document = Read-AuditUi
    $password = @($document.SelectNodes('//node[@class="android.widget.EditText" and @password="true"]')) | Select-Object -First 1
    if (-not $password -or $password.GetAttribute('text').Length -eq 0) { throw 'The password draft disappeared after the rejected save.' }
    $result.releaseInvalidSettingsRejected = $true
    $result.releasePasswordDraftRetained = $true

    Tap-AuditNode (Require-AuditText '通用' $true $true) 'general'
    Tap-AuditNode (Require-AuditText '放弃修改') 'discard-test-drafts'
    Require-AuditText 'DTS、TrueHD 等音轨由 FFmpeg' $true $false $true | Out-Null
    $result.releaseFfmpegAvailable = $true
}
