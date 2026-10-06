param(
    [switch]$Once,
    [int]$IntervalSeconds = 15
)

$ErrorActionPreference = 'Continue'
$Root = $PSScriptRoot
$ConfigDir = Join-Path $env:USERPROFILE '.config\yto-portal-auth'
$ConfigFile = Join-Path $ConfigDir 'config.psd1'
$LogDir = Join-Path $Root 'logs'
$CookieFile = Join-Path $env:TEMP 'yto-portal-cookies.txt'
$ProbeUrl = 'http://connect.rom.miui.com/generate_204'
$PortalHost = 'https://wifi.zlhsc.cn'
$MutexName = 'YtoPortalAuthWatchdog'

New-Item -ItemType Directory -Force -Path $LogDir, $ConfigDir | Out-Null
$log = Join-Path $LogDir ('auth-{0:yyyyMMdd}.log' -f (Get-Date))
function Write-Log([string]$Message) {
    ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message) | Add-Content -LiteralPath $log -Encoding utf8
}

if (-not (Test-Path $ConfigFile)) {
    Write-Output ('[!] config missing: {0}' -f $ConfigFile)
    exit 1
}
$cfg = Import-PowerShellDataFile -LiteralPath $ConfigFile
if (-not $cfg.Username -or -not $cfg.Password -or $cfg.Username -like 'REPLACE*') {
    Write-Output '[!] config not filled (Username/Password)'
    exit 1
}

function Invoke-Probe {
    $raw = & curl.exe -s -D - -o NUL --max-time 6 --noproxy '*' $ProbeUrl 2>$null
    $code = 0
    $location = $null
    if ($raw) {
        $first = $raw | Select-Object -First 1
        if ($first -match 'HTTP/\S+\s+(\d+)') { $code = [int]$Matches[1] }
        $locLine = $raw | Where-Object { $_ -match '^Location:\s*(.+)$' } | Select-Object -First 1
        if ($locLine -match '^Location:\s*(.+)$') { $location = $Matches[1].Trim() }
    }
    return [pscustomobject]@{ Code = $code; Location = $location }
}

function Invoke-Login([string]$PortalUrl) {
    Write-Log ('captive detected, portal: {0}' -f $PortalUrl)
    $page = & curl.exe -s --max-time 12 --noproxy '*' -c $CookieFile -L $PortalUrl 2>$null
    if (-not $page) { Write-Log 'failed to fetch portal page'; return $false }
    $html = $page -join "`n"
    if ($html -notmatch '<form[^>]*action="([^"]+)"') { Write-Log 'login form not found'; return $false }
    $action = $Matches[1]
    $fields = @{}
    foreach ($m in [regex]::Matches($html, '<input[^>]*>')) {
        $tag = $m.Value
        if ($tag -notmatch 'name="([^"]+)"') { continue }
        $name = $Matches[1]
        $value = ''
        if ($tag -match 'value="([^"]*)"') { $value = $Matches[1] }
        $fields[$name] = $value
    }
    $fields['usrname'] = $cfg.Username
    $fields['passwd'] = $cfg.Password
    $fields['treaty'] = 'on'
    $pairs = foreach ($k in $fields.Keys) {
        '{0}={1}' -f [uri]::EscapeDataString($k), [uri]::EscapeDataString($fields[$k])
    }
    $body = $pairs -join '&'
    $postUrl = if ($action -match '^https?://') { $action } else { $PortalHost + $action }
    $resp = & curl.exe -s -D - --max-time 15 --noproxy '*' -b $CookieFile -X POST -H 'Content-Type: application/x-www-form-urlencoded' --data $body $postUrl 2>$null
    $flat = ($resp -join ' ')
    $status = $resp | Select-Object -First 1
    $okMarker = $flat -match 'panabit_value|webauth'
    $errMarker = $flat -match '登录失败|密码错误|警告'
    Write-Log ('login POST -> {0} (success_marker={1} error_marker={2})' -f $status, $okMarker, $errMarker)
    for ($i = 0; $i -lt 8; $i++) {
        Start-Sleep -Seconds 2
        $check = Invoke-Probe
        if ($check.Code -eq 204) {
            Write-Log 'login SUCCESS (internet restored)'
            return $true
        }
    }
    Write-Log 'login attempt did not restore internet'
    return $false
}

if ($Once) {
    $r = Invoke-Probe
    Write-Output ('probe: {0}' -f $r.Code)
    if ($r.Code -eq 204) { Write-Output 'online'; exit 0 }
    if ($r.Location) {
        if (Invoke-Login $r.Location) { exit 0 } else { exit 1 }
    }
    exit 1
}

$mutex = New-Object System.Threading.Mutex($false, $MutexName)
if (-not $mutex.WaitOne(0)) { exit 0 }

Write-Log 'watchdog started'
$online = $null
while ($true) {
    $r = Invoke-Probe
    if ($r.Code -eq 204) {
        if ($online -ne $true) { Write-Log 'online'; $online = $true }
    } else {
        if ($online -ne $false) { Write-Log ('offline (probe={0})' -f $r.Code); $online = $false }
        if ($r.Location) {
            if (Invoke-Login $r.Location) { $online = $true }
        }
    }
    Start-Sleep -Seconds $IntervalSeconds
}
