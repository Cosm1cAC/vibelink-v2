# 门户自动重登看门狗（成都正龙汇 / Panabit captive portal）
#
# 用途：yto2022 的门户认证会话掉线时自动重新登录，避免断网。
# 账号密码存放在 %USERPROFILE%\.captive-portal.json（不进仓库）：
#   {"username":"你的账号","password":"你的密码"}
#
# 由启动文件夹里的 portal-autologin.vbs 隐藏启动；日志见 %USERPROFILE%\.captive-portal.log

$ErrorActionPreference = 'Continue'
$cfgPath = Join-Path $env:USERPROFILE '.captive-portal.json'
$logPath = Join-Path $env:USERPROFILE '.captive-portal.log'
$probeUrl = 'http://connect.rom.miui.com/generate_204'
$portalBase = 'https://wifi.zlhsc.cn/lfradius/libs/portal/unify/portal.php'
$portalFallbackIp = '47.109.134.147'
$basip = '192.168.80.254'
$intervalSec = 60

function Write-Log([string]$msg) {
    try {
        if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 1MB) {
            Remove-Item -LiteralPath $logPath -Force
        }
        ('{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) | Add-Content -LiteralPath $logPath -Encoding UTF8
    } catch { }
}

function Get-WlanInfo {
    $mac = (Get-NetAdapter -Name WLAN -ErrorAction SilentlyContinue).MacAddress
    $ip = (Get-NetIPAddress -InterfaceAlias WLAN -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
    return @{ mac = ($mac -replace '-', ':'); ip = $ip }
}

function Test-Online {
    $code = & curl.exe -s -o "$env:TEMP\portal-probe.txt" -w '%{http_code}' --max-time 8 $probeUrl 2>$null
    if ($code -eq '204') { return $true }
    # 二次确认（防止探测域名本身异常导致误判）
    $code2 = & curl.exe -s -o "$env:TEMP\portal-probe2.txt" -w '%{http_code}' --max-time 8 'http://www.baidu.com/' 2>$null
    if ($code2 -eq '200') {
        $body = Get-Content "$env:TEMP\portal-probe2.txt" -Raw -ErrorAction SilentlyContinue
        if ($body -notmatch 'zlhsc|正龙汇|Panabit|lfradius') { return $true }
    }
    return $false
}

function Invoke-Login([string]$user, [string]$pass) {
    $w = Get-WlanInfo
    if (-not $w.mac -or -not $w.ip) { Write-Log 'WLAN 未就绪（无 MAC/IP），跳过本次登录'; return $false }
    $jar = Join-Path $env:TEMP 'portal-cookies.txt'
    $loginPage = "$portalBase/login/main/nasid/2/?wlanuserip=$($w.ip)&clientip=$($w.ip)&wlanacname=panabit&clientmac=$($w.mac)&paip=$basip&vlan=0.0&iarmdst=192.168.80.1/"

    # 1) 先访问登录页拿 cookie（模拟浏览器流程）
    & curl.exe -s -k -m 10 -c $jar -o NUL $loginPage 2>$null | Out-Null

    # 2) 提交登录表单
    $args = @(
        '-s', '-k', '-m', '15', '-b', $jar, '-c', $jar, '-X', 'POST',
        "$portalBase/login/Panabit_login/",
        '--data-urlencode', "usrname=$user",
        '--data-urlencode', "passwd=$pass",
        '--data-urlencode', 'treaty=on',
        '--data', 'nasid=2',
        '--data', "usrmac=$($w.mac)",
        '--data', "usrip=$($w.ip)",
        '--data', "basip=$basip",
        '--data-urlencode', "success=$portalBase/login/success/nastype/Panabit/basip/$basip/usrip/$($w.ip)/",
        '--data-urlencode', "fail=$portalBase/login/fail/",
        '--data', 'offline=1',
        '-L', '-o', "$env:TEMP\portal-login.html", '-w', '%{http_code}'
    )
    $resp = & curl.exe @args 2>$null
    if ($resp -eq '000') {
        # DNS 不通时用已知 IP 直连重试
        $resp = & curl.exe @($args | ForEach-Object { $_ }) '--resolve' "wifi.zlhsc.cn:443:$portalFallbackIp" 2>$null
    }
    Start-Sleep -Seconds 2
    if (Test-Online) {
        Write-Log "重登成功（http=$resp）"
        return $true
    }
    $snippet = ''
    try {
        $snippet = (Get-Content "$env:TEMP\portal-login.html" -Raw -ErrorAction SilentlyContinue) -replace '\s+', ' '
        if ($snippet.Length -gt 200) { $snippet = $snippet.Substring(0, 200) }
    } catch { }
    Write-Log "重登失败（http=$resp）：$snippet"
    return $false
}

Write-Log 'portal-autologin started'
while ($true) {
    try {
        if (-not (Test-Online)) {
            if (-not (Test-Path -LiteralPath $cfgPath)) {
                Write-Log "检测到离线，但缺少凭据文件 $cfgPath（无法自动重登）"
            } else {
                $cfg = Get-Content -LiteralPath $cfgPath -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
                if (-not $cfg.username -or -not $cfg.password) {
                    Write-Log '凭据文件缺少 username / password'
                } else {
                    [void](Invoke-Login $cfg.username $cfg.password)
                }
            }
        }
    } catch {
        Write-Log "循环异常: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds $intervalSec
}
