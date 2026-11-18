<#
.SYNOPSIS
    Start the DSH web profile so a phone on the same network can connect.

.DESCRIPTION
    DSH refuses to bind a wildcard address, so this script picks one concrete
    IPv4 address of this machine, adds it as a trusted authority for the /api
    browser-trust fence, and starts the web profile without opening a desktop
    browser. The tokenized URL that `dsh web` prints is what you paste into the
    DSH Mobile app.

.PARAMETER HostAddress
    IPv4 literal of the interface to bind. Defaults to the first usable
    non-loopback IPv4 address.

.PARAMETER Port
    Listen port. Defaults to 3080.

.EXAMPLE
    .\start-dsh-for-phone.ps1

.EXAMPLE
    .\start-dsh-for-phone.ps1 -HostAddress 192.168.1.23 -Port 8080
#>
[CmdletBinding()]
param(
    [string]$HostAddress,
    [int]$Port = 3080
)

$ErrorActionPreference = 'Stop'

if (-not $HostAddress) {
    $candidate = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -ne '127.0.0.1' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixOrigin -ne 'WellKnown'
        } |
        Sort-Object -Property @{ Expression = { if ($_.InterfaceAlias -match 'Wi-?Fi|WLAN') { 0 } else { 1 } } }, InterfaceIndex |
        Select-Object -First 1
    if (-not $candidate) {
        throw '找不到可用的局域网 IPv4 地址，请用 -HostAddress 指定。'
    }
    $HostAddress = $candidate.IPAddress
    Write-Host "使用网卡 $($candidate.InterfaceAlias)：$HostAddress" -ForegroundColor Cyan
}

$authority = "${HostAddress}:${Port}"
Write-Host ''
Write-Host '手机端要粘贴的地址形如：' -ForegroundColor DarkGray
Write-Host "  http://${authority}/?token=<终端打印的一次性 token>" -ForegroundColor DarkGray
Write-Host ''

$arguments = @(
    '--profile', 'web',
    '--host', $HostAddress,
    '--port', "$Port",
    '--trusted-host', $authority,
    '--no-open'
)

Write-Host "dsh $($arguments -join ' ')" -ForegroundColor Yellow
& dsh @arguments
