# Builds an APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#   .\build.ps1                                  -> CarHome.apk (the launcher)
#   .\build.ps1 -Dir testplayer -Out TestPlayer.apk
param([string]$Dir = '.', [string]$Out = 'CarHome.apk')
$ErrorActionPreference = 'Stop'
$sdk = 'E:\Android\Sdk'
$bt  = "$sdk\build-tools\36.0.0"  # 34.0.0's d8 crashes on BaseAdapter subclasses
$jar = "$sdk\platforms\android-29\android.jar"
$ks  = "$PSScriptRoot\debug.keystore"
Set-Location (Resolve-Path (Join-Path $PSScriptRoot $Dir))
Remove-Item out -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory out\classes | Out-Null

& "$bt\aapt2.exe" link -o out\base.apk -I $jar --manifest AndroidManifest.xml
if ($LASTEXITCODE) { throw 'aapt2 failed' }

javac -nowarn --release 8 -classpath $jar -d out\classes (Get-ChildItem src -Recurse -Filter *.java).FullName
if ($LASTEXITCODE) { throw 'javac failed' }

jar cf out\classes.jar -C out\classes .
& "$bt\d8.bat" --release --min-api 24 --lib $jar --output out out\classes.jar
if ($LASTEXITCODE) { throw 'd8 failed' }

Push-Location out
& "$bt\aapt.exe" add base.apk classes.dex | Out-Null
Pop-Location

if (-not (Test-Path $ks)) {
    keytool -genkeypair -keystore $ks -storepass android -keypass android -alias key `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=CarHome' | Out-Null
}
& "$bt\zipalign.exe" -f 4 out\base.apk out\aligned.apk
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --out $Out out\aligned.apk
if ($LASTEXITCODE) { throw 'apksigner failed' }
Write-Host "Built $(Resolve-Path $Out) ($([int]((Get-Item $Out).Length/1KB)) KB)"
