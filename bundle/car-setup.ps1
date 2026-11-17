# One-shot setup of the head unit over ADB.
#   Laptop on the car's hotspot, network ADB on (Developer options), then:
#     .\car-setup.ps1 -Unit 192.168.43.1        (IP of the head unit)
#   Or with only one device attached (USB / emulator):  .\car-setup.ps1
#
# Installs: Car Home (..\CarHome.apk) + every *.apk in this folder (DiPlay, plus any you add,
# e.g. Headunit Reloaded for Android Auto), then grants their permissions and sets Car Home as home.
param([string]$Unit)
$ErrorActionPreference = 'Continue' # some grants are expected to fail (not every app asks for every permission)
$adb = 'E:\Android\Sdk\platform-tools\adb.exe'
$a = @()
if ($Unit) {
    $target = if ($Unit -match ':') { $Unit } else { "$Unit`:5555" }
    & $adb connect $target | Write-Host
    $a = @('-s', $target)
}
function adbx { & $adb @a @args 2>$null }
function step($m) { Write-Host "`n== $m" -ForegroundColor Cyan }

step 'Device'
$abi = (adbx shell getprop ro.product.cpu.abi).Trim()
Write-Host "Android $((adbx shell getprop ro.build.version.release).Trim()), CPU $abi"

step 'Installing apps'
$apks = @((Resolve-Path "$PSScriptRoot\..\CarHome.apk").Path) +
        @(Get-ChildItem $PSScriptRoot -Filter *.apk | ForEach-Object FullName)
foreach ($apk in $apks) {
    $r = (adbx install -r $apk 2>&1 | Out-String).Trim()
    Write-Host ("{0,-28} {1}" -f (Split-Path $apk -Leaf), ($r -split "`n")[-1])
}

step 'Car Home permissions'
$me = 'com.prath.carlauncher'
foreach ($p in 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'ACCESS_BACKGROUND_LOCATION') {
    adbx shell pm grant $me "android.permission.$p" 2>$null # BACKGROUND only exists on Android 10+
}
adbx shell appops set $me WRITE_SETTINGS allow                       # brightness slider
adbx shell cmd notification allow_listener "$me/$me.Listener"        # now-playing card
adbx shell cmd package set-home-activity "$me/.Home"                 # default launcher
Write-Host 'location, brightness, music card, home screen: done'

step 'Permissions for the phone-link apps'
# Grants every runtime permission each app asks for (Bluetooth, location, mic for Siri/Assistant…)
# plus "Modify system settings" (DiPlay needs it to switch the hotspot on by itself).
foreach ($pkg in 'com.shihab.diplay', 'com.andrerinas.headunitrevived', 'com.asjohn720.androidauto',
                 'gb.xxy.hr', 'com.zjinnova.zlink') {
    if (-not (adbx shell pm path $pkg 2>$null)) { continue }
    $dump = adbx shell dumpsys package $pkg
    $perms = $dump | Select-String '^\s+(android\.permission\.[A-Z_]+)' |
             ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique
    $ok = 0
    foreach ($p in $perms) { adbx shell pm grant $pkg $p 2>$null; if ($LASTEXITCODE -eq 0) { $ok++ } }
    adbx shell appops set $pkg WRITE_SETTINGS allow
    adbx shell appops set $pkg SYSTEM_ALERT_WINDOW allow   # Open Headunit draws over other apps
    Write-Host "$pkg : granted $ok runtime permissions + modify system settings + overlay"
}

step 'Start'
adbx shell input keyevent KEYCODE_HOME
Write-Host 'Car Home is up. DiPlay (CarPlay) is tile 1, Android Auto is tile 2.'
