param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('install', 'uninstall', 'start', 'stop', 'restart', 'status', 'reload', 'mode', 'select', 'delay', 'ui')]
    [string]$Action,
    [string]$Value
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'config.ps1')

function Test-Elevated {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-ApiHeaders { @{ Authorization = "Bearer $ApiSecret" } }

function Invoke-Api([string]$Method, [string]$Path, $Body) {
    $p = @{ Method = $Method; Uri = ($ApiBase + $Path); Headers = (Get-ApiHeaders); TimeoutSec = 15 }
    if ($null -ne $Body) {
        $p['Body'] = ($Body | ConvertTo-Json -Compress -Depth 6)
        $p['ContentType'] = 'application/json'
    }
    Invoke-RestMethod @p
}

function Set-SystemProxy([bool]$Enable) {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    if ($Enable) {
        Set-ItemProperty -Path $key -Name ProxyEnable -Value 1 -Type DWord
        Set-ItemProperty -Path $key -Name ProxyServer -Value ("127.0.0.1:{0}" -f $MixedPort) -Type String
    } else {
        Set-ItemProperty -Path $key -Name ProxyEnable -Value 0 -Type DWord
    }
    if (-not ('Wproxy.Native' -as [type])) {
        Add-Type -Namespace Wproxy -Name Native -MemberDefinition '[DllImport("wininet.dll")] public static extern bool InternetSetOption(System.IntPtr h, int o, System.IntPtr b, int l);'
    }
    [void][Wproxy.Native]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0)
    [void][Wproxy.Native]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0)
}

function Clear-OurSystemProxyIfSet {
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
    $reg = Get-ItemProperty -Path $key -ErrorAction SilentlyContinue
    if ($reg.ProxyEnable -eq 1 -and $reg.ProxyServer -eq ("127.0.0.1:{0}" -f $MixedPort)) {
        Set-SystemProxy $false
        Write-Output '[i] system proxy pointed at wproxy, disabled'
    }
}

function Get-WatchdogProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'pwsh.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains('mihomo-run.ps1') }
}

function Get-MihomoProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'mihomo.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.Contains('wproxy') }
}

function Stop-AllProcesses {
    Get-WatchdogProcesses | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-MihomoProcesses | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Register-WProxyTask {
    $action = New-ScheduledTaskAction -Execute $PwshExe -Argument ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f (Join-Path $WproxyDir 'mihomo-run.ps1'))
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden -MultipleInstances IgnoreNew
    $principal = New-ScheduledTaskPrincipal -UserId ("{0}\{1}" -f $env:USERDOMAIN, $env:USERNAME) -LogonType Interactive -RunLevel Highest
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Description 'wproxy: open-source mihomo core watchdog' -Force | Out-Null
}

switch ($Action) {
    'install' {
        if (Test-Elevated) {
            Register-WProxyTask
            Write-Output '[OK] scheduled task registered (highest privileges)'
            Start-ScheduledTask -TaskName $TaskName
            Write-Output '[OK] started'
        } else {
            Write-Output '[..] admin required to register task, requesting UAC ...'
            $elev = Start-Process -FilePath $PwshExe -Verb RunAs -Wait -PassThru -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-Action', 'install')
            Write-Output ('[i] elevated exit code: {0}' -f $elev.ExitCode)
            Start-Sleep -Seconds 6
            & $PSCommandPath -Action status
        }
    }
    'uninstall' {
        Set-Content -Path (Join-Path $WproxyDir 'stop.request') -Value (Get-Date -Format s) -Encoding ASCII
        $deadline = (Get-Date).AddSeconds(20)
        while ((Get-Date) -lt $deadline -and (Get-Process -Name 'mihomo' -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 1 }
        try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch {}
        try {
            Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction Stop
            Write-Output '[OK] scheduled task removed'
        } catch {
            Write-Output ('[!] remove task failed (run as admin once): {0}' -f $_.Exception.Message)
        }
        Stop-AllProcesses
        Remove-Item -LiteralPath (Join-Path $WproxyDir 'stop.request') -Force -ErrorAction SilentlyContinue
        Set-SystemProxy $false
        Write-Output '[OK] uninstall done'
    }
    'start' {
        Remove-Item -LiteralPath (Join-Path $WproxyDir 'stop.request') -Force -ErrorAction SilentlyContinue
        Start-ScheduledTask -TaskName $TaskName
        Write-Output '[OK] task started'
    }
    'stop' {
        Set-Content -Path (Join-Path $WproxyDir 'stop.request') -Value (Get-Date -Format s) -Encoding ASCII
        $deadline = (Get-Date).AddSeconds(20)
        while ((Get-Date) -lt $deadline -and (Get-Process -Name 'mihomo' -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 1 }
        try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch {}
        Clear-OurSystemProxyIfSet
        if (Get-Process -Name 'mihomo' -ErrorAction SilentlyContinue) {
            Write-Output '[!] mihomo still running (watchdog not active? try admin once)'
        } else {
            Remove-Item -LiteralPath (Join-Path $WproxyDir 'stop.request') -Force -ErrorAction SilentlyContinue
            Write-Output '[OK] stopped'
        }
    }
    'restart' {
        try { Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue } catch {}
        Stop-AllProcesses
        Start-Sleep -Seconds 2
        Start-ScheduledTask -TaskName $TaskName
        Write-Output '[OK] restarted'
    }
    'status' {
        $t = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        Write-Output ('[task]     {0}' -f $(if ($t) { $t.State } else { 'not installed' }))
        $m = Get-Process -Name 'mihomo' -ErrorAction SilentlyContinue
        $apiUp = $false
        try { $null = Invoke-Api 'GET' '/version' $null; $apiUp = $true } catch {}
        Write-Output ('[core]     {0}' -f $(if ($apiUp) { 'UP (pid ' + ($m.Id -join ',') + ')' } elseif ($m) { 'process running, API down' } else { 'stopped' }))
        $listen = Get-NetTCPConnection -LocalPort $MixedPort -State Listen -ErrorAction SilentlyContinue
        Write-Output ('[port]     127.0.0.1:{0} {1}' -f $MixedPort, $(if ($listen) { 'LISTEN' } else { 'DOWN' }))
        $reg = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction SilentlyContinue
        Write-Output ('[sysproxy] enable={0} server={1}' -f $reg.ProxyEnable, $reg.ProxyServer)
        try {
            $cfg = Invoke-Api 'GET' '/configs' $null
            Write-Output ('[tun]      {0}' -f $cfg.tun.enable)
        } catch {
            Write-Output '[tun]      api unreachable'
        }
        $eg = & "$env:SystemRoot\System32\curl.exe" -s --max-time 15 -x ("http://127.0.0.1:{0}" -f $MixedPort) https://api.ipify.org
        Write-Output ('[egress]   {0}' -f $eg)
        try {
            $proxy = Invoke-Api 'GET' '/proxies/PROXY' $null
            Write-Output ('[node]     {0}' -f $proxy.now)
        } catch {}
    }
    'reload' {
        Invoke-Api 'PUT' '/configs?force=true' @{ path = (Join-Path $WproxyDir 'config.yaml') } | Out-Null
        Write-Output '[OK] config reloaded'
    }
    'mode' {
        switch ($Value) {
            'sysproxy' {
                Set-SystemProxy $false
                Invoke-Api 'PATCH' '/configs' @{ tun = @{ enable = $false } } | Out-Null
                Set-SystemProxy $true
                Write-Output ('[OK] system proxy -> 127.0.0.1:{0}, TUN off' -f $MixedPort)
            }
            'tun' {
                Set-SystemProxy $false
                Invoke-Api 'PATCH' '/configs' @{ tun = @{ enable = $true } } | Out-Null
                Write-Output '[OK] TUN on, system proxy off'
            }
            'off' {
                Invoke-Api 'PATCH' '/configs' @{ tun = @{ enable = $false } } | Out-Null
                Set-SystemProxy $false
                Write-Output '[OK] TUN off, system proxy off'
            }
            default {
                Write-Output 'usage: wproxy.ps1 -Action mode -Value <sysproxy|tun|off>'
            }
        }
    }
    'select' {
        if (-not $Value) { Write-Output 'usage: wproxy.ps1 -Action select -Value <Japan-MacMini|Japan-MacMini-SSH|DIRECT>'; break }
        Invoke-Api 'PUT' '/proxies/PROXY' @{ name = $Value } | Out-Null
        Write-Output ('[OK] node selected: {0}' -f $Value)
    }
    'delay' {
        $name = if ($Value) { $Value } else { 'Japan-MacMini' }
        $u = [uri]::EscapeDataString('https://cp.cloudflare.com/generate_204')
        $r = Invoke-Api 'GET' ('/proxies/{0}/delay?url={1}&timeout=5000' -f $name, $u) $null
        Write-Output ('[delay] {0}: {1} ms' -f $name, $r.delay)
    }
    'ui' {
        $url = 'http://127.0.0.1:{0}/ui/#/setup?hostname=127.0.0.1&port={0}&secret={1}' -f $ApiPort, $ApiSecret
        Write-Output $url
        Start-Process $url
    }
}
