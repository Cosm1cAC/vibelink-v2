param(
    [switch]$Elevate,   # 计划任务注册失败时请求 UAC（默认直接用启动文件夹回退，无需管理员）
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'config.ps1')
$serverScript = Join-Path $PSScriptRoot 'server.ps1'
$pwshExe = Join-Path $PSHOME 'pwsh.exe'

function New-Password {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
    -join (1..24 | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

function Register-ServerTask {
    $action = New-ScheduledTaskAction -Execute $pwshExe -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $serverScript)
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Description 'opencode-mesh: headless opencode server watchdog' -Force | Out-Null
}

function Install-StartupEntry {
    $startup = [Environment]::GetFolderPath('Startup')
    $vbs = Join-Path $startup 'OpenCodeServer.vbs'
    $cmdline = '"{0}" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{1}"' -f $pwshExe, $serverScript
    $content = 'CreateObject("WScript.Shell").Run "' + $cmdline.Replace('"', '""') + '", 0, False'
    Set-Content -LiteralPath $vbs -Value $content -Encoding Unicode
    return $vbs
}

New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
if (-not (Test-Path $PasswordFile)) {
    Set-Content -LiteralPath $PasswordFile -Value (New-Password) -Encoding ASCII
    Write-Output ('[OK] password generated: {0}' -f $PasswordFile)
} else {
    Write-Output ('[OK] password exists: {0}' -f $PasswordFile)
}

$taskReady = $false
$vbs = $null
try {
    Register-ServerTask
    $taskReady = $true
    Write-Output '[OK] scheduled task registered.'
} catch {
    Write-Output ('[!] direct task registration failed: {0}' -f $_.Exception.Message)
    if ($Elevate) {
        Write-Output '[..] requesting elevation (UAC) ...'
        try {
            $elev = Start-Process -FilePath $pwshExe -Verb RunAs -Wait -PassThru -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-NoStart')
            if ($elev.ExitCode -eq 0 -and (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
                $taskReady = $true
                Write-Output '[OK] scheduled task registered with admin rights.'
            }
        } catch {
            Write-Output ('[!] elevation failed: {0}' -f $_.Exception.Message)
        }
    }
}
if (-not $taskReady) {
    $vbs = Install-StartupEntry
    Write-Output ('[OK] startup entry installed: {0}' -f $vbs)
}

foreach ($port in @($ServerPort, $UpstreamPort)) {
    $old = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($old) {
        foreach ($conn in $old) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
        Write-Output ('[OK] stopped previous listener on port {0}.' -f $port)
    }
}

if (-not $NoStart) {
    if ($taskReady) {
        Start-ScheduledTask -TaskName $TaskName
    } else {
        Start-Process -FilePath 'wscript.exe' -ArgumentList @('"' + $vbs + '"')
    }
    Write-Output '[..] waiting for server health ...'
    $password = (Get-Content -LiteralPath $PasswordFile -Raw).Trim()
    $auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('opencode:' + $password))
    $healthy = $false
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 1
        try {
            $r = Invoke-WebRequest -Uri "http://127.0.0.1:$ServerPort/global/health" -Headers @{ Authorization = $auth } -NoProxy -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { $healthy = $true; break }
        } catch { }
    }
    if ($healthy) {
        Write-Output ('[OK] server healthy: http://100.98.206.126:{0}/  (user: opencode, password: {1})' -f $ServerPort, $PasswordFile)
    } else {
        Write-Output ('[!] server not healthy yet; see logs in {0}' -f $LogDir)
    }
}
