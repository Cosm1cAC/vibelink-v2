param(
    [switch]$Elevate,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$scriptPath = Join-Path $Root 'portal-auth.ps1'
$pwshExe = Join-Path $PSHOME 'pwsh.exe'
$ConfigDir = Join-Path $env:USERPROFILE '.config\yto-portal-auth'
$ConfigFile = Join-Path $ConfigDir 'config.psd1'
$TaskName = 'YtoPortalAuth'

function Register-AuthTask {
    $action = New-ScheduledTaskAction -Execute $pwshExe -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $scriptPath)
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Description 'yto2022 portal auto re-auth watchdog' -Force | Out-Null
}

function Install-StartupEntry {
    $startup = [Environment]::GetFolderPath('Startup')
    $vbs = Join-Path $startup 'YtoPortalAuth.vbs'
    $cmdline = '"{0}" -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{1}"' -f $pwshExe, $scriptPath
    $content = 'CreateObject("WScript.Shell").Run "' + $cmdline.Replace('"', '""') + '", 0, False'
    Set-Content -LiteralPath $vbs -Value $content -Encoding Unicode
    return $vbs
}

New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
if (-not (Test-Path $ConfigFile)) {
    $template = "@{`r`n    Username = 'REPLACE_WITH_USERNAME'`r`n    Password = 'REPLACE_WITH_PASSWORD'`r`n}`r`n"
    Set-Content -LiteralPath $ConfigFile -Value $template -Encoding utf8
    Write-Output ('[!] 请先填写账号密码后重新运行: {0}' -f $ConfigFile)
    exit 1
}
$cfg = Import-PowerShellDataFile -LiteralPath $ConfigFile
if ($cfg.Username -like 'REPLACE*' -or $cfg.Password -like 'REPLACE*') {
    Write-Output ('[!] 配置还是模板值，请填写: {0}' -f $ConfigFile)
    exit 1
}
Write-Output ('[OK] credentials: {0}' -f $ConfigFile)

$taskReady = $false
$vbs = $null
try {
    Register-AuthTask
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

if (-not $NoStart) {
    if ($taskReady) {
        Start-ScheduledTask -TaskName $TaskName
    } else {
        Start-Process -FilePath 'wscript.exe' -ArgumentList @('"' + $vbs + '"')
    }
    Start-Sleep -Seconds 3
    pwsh -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Once
}
