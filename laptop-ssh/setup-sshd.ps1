# setup-sshd.ps1 - install OpenSSH Server on the laptop and trust the PC's key
# Run as Administrator on DESKTOP-9M0BU3H.
$ErrorActionPreference = 'Continue'
$pub = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOQONsKcDdFUtJG/73EcKcNmaMCuCQtC51p/rsEks+ZC 22771@windows'

function Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }

Step '1/6 checking OpenSSH Server capability'
$cap = Get-WindowsCapability -Online -Name 'OpenSSH.Server*' -ErrorAction SilentlyContinue
if ($cap -and $cap.State -eq 'Installed') {
    Write-Host '    already installed'
} else {
    try {
        Write-Host '    installing from Windows Update ...'
        Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' -ErrorAction Stop | Out-Null
        Write-Host '    installed'
    } catch {
        Write-Host "    capability install failed: $($_.Exception.Message)"
        Step '    falling back to Win32-OpenSSH release'
        $zip = "$env:TEMP\OpenSSH-Win64.zip"
        $dir = 'C:\Program Files\OpenSSH'
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri 'https://github.com/PowerShell/Win32-OpenSSH/releases/latest/download/OpenSSH-Win64.zip' -OutFile $zip -UseBasicParsing
            if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
            Expand-Archive -Path $zip -DestinationPath 'C:\Program Files' -Force
            if (-not (Test-Path "$dir\sshd.exe")) { Move-Item 'C:\Program Files\OpenSSH-Win64' $dir -Force }
            & powershell.exe -ExecutionPolicy Bypass -File "$dir\install-sshd.ps1"
            Write-Host '    installed from GitHub release'
        } catch {
            Write-Host "    FATAL: could not install OpenSSH Server: $($_.Exception.Message)" -ForegroundColor Red
            Write-Host '    need internet on this laptop, or install "OpenSSH Server" from Settings > Optional Features'
            return
        }
    }
}

Step '2/6 enabling and starting sshd'
Set-Service -Name sshd -StartupType Automatic -ErrorAction SilentlyContinue
Start-Service sshd -ErrorAction SilentlyContinue
$svc = Get-Service sshd -ErrorAction SilentlyContinue
Write-Host "    sshd: $($svc.Status) / $($svc.StartType)"

Step '3/6 firewall rule for tcp/22'
$r = Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
if (-not $r) {
    New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (sshd)' `
        -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 -Profile Any -ErrorAction SilentlyContinue | Out-Null
}
Write-Host '    rule present'

Step '4/6 trusting the PC key'
$adm = "$env:ProgramData\ssh\administrators_authorized_keys"
if (-not (Test-Path $adm)) { New-Item -Path $adm -ItemType File -Force | Out-Null }
$existing = Get-Content $adm -ErrorAction SilentlyContinue
if ($existing -notcontains $pub) { Add-Content -Path $adm -Value $pub -Encoding ascii }
& icacls.exe $adm /inheritance:r /grant 'Administrators:F' /grant 'SYSTEM:F' | Out-Null
Write-Host "    $adm ok"

$userKeys = "$env:USERPROFILE\.ssh\authorized_keys"
if (-not (Test-Path "$env:USERPROFILE\.ssh")) { New-Item -Path "$env:USERPROFILE\.ssh" -ItemType Directory -Force | Out-Null }
$uk = Get-Content $userKeys -ErrorAction SilentlyContinue
if ($uk -notcontains $pub) { Add-Content -Path $userKeys -Value $pub -Encoding ascii }
Write-Host "    $userKeys ok"

Step '5/6 default shell = powershell'
New-Item -Path 'HKLM:\SOFTWARE\OpenSSH' -Force -ErrorAction SilentlyContinue | Out-Null
New-ItemProperty -Path 'HKLM:\SOFTWARE\OpenSSH' -Name DefaultShell `
    -Value 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -PropertyType String -Force | Out-Null
Restart-Service sshd -ErrorAction SilentlyContinue
Write-Host '    done'

Step '6/6 listening check'
Start-Sleep -Seconds 2
$listen = Get-NetTCPConnection -State Listen -LocalPort 22 -ErrorAction SilentlyContinue
if ($listen) {
    Write-Host '    port 22 LISTENING:'
    $listen | Select-Object LocalAddress, LocalPort | Format-Table -AutoSize | Out-String | Write-Host
} else {
    Write-Host '    WARNING: nothing listening on 22' -ForegroundColor Yellow
}
Write-Host ''
Write-Host 'DONE. Now run this on the PC:  ssh ADMIN@100.123.253.85 hostname' -ForegroundColor Green
