# Finds how the head unit exposes "headlights on" by diffing its state with lights off vs on.
#   .\lights-probe.ps1 -Step off    (headlights OFF)  -> snapshot + clears the log
#   .\lights-probe.ps1 -Step on     (headlights ON)   -> snapshot + grabs the log since 'off'
#   .\lights-probe.ps1 -Step diff                     -> prints what changed
# Optional: -Serial 192.168.43.1:5555 when more than one device is connected.
param([Parameter(Mandatory)][ValidateSet('off', 'on', 'diff')][string]$Step, [string]$Serial)
$ErrorActionPreference = 'Stop'
$adb = 'E:\Android\Sdk\platform-tools\adb.exe'
$dir = Join-Path $PSScriptRoot 'probe'
New-Item -ItemType Directory -Force $dir | Out-Null
$a = @(); if ($Serial) { $a = @('-s', $Serial) }
function sh([string]$cmd) { & $adb @a shell $cmd 2>$null }

function Snapshot([string]$name) {
    $lines = @()
    foreach ($ns in 'system', 'global', 'secure') { $lines += sh "settings list $ns" | ForEach-Object { "settings.$ns`: $_" } }
    $lines += sh 'getprop' | ForEach-Object { "prop: $_" }
    # backlight level straight from the kernel, if readable without root
    $lines += sh 'for f in /sys/class/backlight/*/brightness /sys/class/backlight/*/actual_brightness /sys/class/leds/lcd-backlight/brightness; do [ -r $f ] && echo "$f=$(cat $f)"; done' | ForEach-Object { "sys: $_" }
    $lines += sh 'dumpsys display' | Select-String -Pattern 'brightness' -SimpleMatch | ForEach-Object { "display: $($_.Line.Trim())" }
    $lines += sh 'dumpsys power' | Select-String -Pattern 'brightness|night|dim' | ForEach-Object { "power: $($_.Line.Trim())" }
    $lines | Where-Object { $_ -notmatch 'time|uptime|boot_count|_ms\b|\.ts\]|heartbeat' } |
        Set-Content -Encoding utf8 (Join-Path $dir "$name.txt")
    Write-Host "Saved $name snapshot ($($lines.Count) lines)"
}

switch ($Step) {
    'off' { Snapshot 'off'; & $adb @a logcat -c; Write-Host 'Now turn the headlights ON, then run -Step on' }
    'on' {
        Snapshot 'on'
        & $adb @a logcat -d -v brief | Set-Content -Encoding utf8 (Join-Path $dir 'log.txt')
        Write-Host 'Now run -Step diff'
    }
    'diff' {
        $off = Get-Content (Join-Path $dir 'off.txt'); $on = Get-Content (Join-Path $dir 'on.txt')
        Write-Host "`n=== Changed state (<= lights off, => lights on) ==="
        Compare-Object $off $on | Sort-Object { $_.InputObject } | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }
        Write-Host "`n=== Log lines that look light-related ==="
        Get-Content (Join-Path $dir 'log.txt') |
            Select-String -Pattern 'illum|headl|lamp|light|night|backlight|brightness|dimm|ACC|small_light|carlight' |
            Select-Object -Last 40 | ForEach-Object { $_.Line }
    }
}
