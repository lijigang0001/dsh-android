<#
.SYNOPSIS
    Inspect a built DSH Mobile APK: identity, SDK levels, permissions, signature.

.DESCRIPTION
    Runs the Android build-tools checks that matter for an installable, signed
    artifact: aapt2 badging (package, version, min/target SDK, launcher icon),
    the permission list, and apksigner's certificate verification.

.PARAMETER Apk
    APK to inspect. Defaults to the release output.

.EXAMPLE
    .\verify-apk.ps1
#>
[CmdletBinding()]
param(
    [string]$Apk = 'dsh-mobile\app\build\outputs\apk\release\app-release.apk'
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Apk)) {
    throw "找不到 APK：$Apk（先跑 assembleRelease）"
}
$Apk = (Resolve-Path $Apk).Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
if (-not $buildTools) { throw "找不到 build-tools：$sdk\build-tools" }

$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
# Respect a JDK supplied by the caller (CI sets JAVA_HOME); fall back to the
# machine-local install this project was developed against.
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\Program Files\Java\jdk-19' }

$size = [math]::Round((Get-Item $Apk).Length / 1KB, 1)
Write-Host "APK      : $Apk ($size KB)" -ForegroundColor Cyan
Write-Host "SHA-256  : $((Get-FileHash $Apk -Algorithm SHA256).Hash)" -ForegroundColor DarkGray
Write-Host ''

Write-Host '== badging ==' -ForegroundColor Yellow
& $aapt2 dump badging $Apk | Select-String -Pattern '^(package|sdkVersion|targetSdkVersion|application-label|launchable-activity|uses-permission|native-code|densities)' | ForEach-Object { "  $_" }

Write-Host ''
Write-Host '== signature ==' -ForegroundColor Yellow
& $apksigner verify --verbose --print-certs $Apk | Select-Object -First 12 | ForEach-Object { "  $_" }

Write-Host ''
Write-Host '== contents ==' -ForegroundColor Yellow
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $interesting = $zip.Entries | Where-Object { $_.FullName -match '^(classes|assets/mobile-shim|res/mipmap|AndroidManifest|resources\.arsc)' }
    $interesting | Group-Object { ($_.FullName -split '/')[0] + '/' + (($_.FullName -split '/')[1]) } |
        Sort-Object Name |
        ForEach-Object {
            $total = ($_.Group | Measure-Object -Property Length -Sum).Sum
            "  {0,-34} {1,4} files  {2,8:N1} KB" -f $_.Name, $_.Count, ($total / 1KB)
        }
} finally {
    $zip.Dispose()
}
