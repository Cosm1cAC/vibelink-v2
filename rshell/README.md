# rshell — 常驻反向运维通道

主 PC（haoshao）与受管 Windows 机器（如笔记本 DESKTOP-9M0BU3H）之间的**带外恢复通道**。
当对端网络不通 / opencode 等工具异常、SSH/WinRM 都不可用时，用它执行远程诊断和修复。

## 架构

```
受管机（管理员 PowerShell，计划任务开机自启，断线每 10s 重连）
    └── 主动连出 192.168.86.20:4444
主 PC（python 服务，循环接受连接 + 空闲保活）
    └── 命令写入 %TEMP%\rshell\run.txt → 发送到对端执行 → 结果写入 result-<id>.txt
```

- 每条命令在**独立子进程（Start-Job）**中执行，超时 300s 自动中止：
  对端脚本自身的变量不会被命令污染，命令卡死也不会拖垮通道。
- 空闲 45s 服务端发空命令保活（防热点 NAT 超时断链）。
- 对端断线/重启后自动重连；两端均注册计划任务，开机自启。

## 使用（主 PC）

```powershell
pwsh -File rshell\rc.ps1 'Get-Process opencode | Select-Object Id,StartTime | Out-String'
```

- 命令必须是**单行**；输出以 Base64 回传（支持中文）。
- 结果 120s 内未返回则打印 `[no result - timeout]`。

## 安装

主 PC：

```powershell
pwsh -ExecutionPolicy Bypass -File .\rshell\install-server.ps1
```

受管机（管理员 PowerShell）：

```powershell
pwsh -ExecutionPolicy Bypass -File .\rshell\install-client.ps1
```

## 注意事项

- 通道**无认证**，仅限主 PC 的热点局域网（192.168.86.20:4444），不要暴露到公网。
- 受管机客户端以管理员权限常驻；`%ProgramData%\rshell-client\client.ps1` 为其脚本。
- 客户端会依次尝试 `192.168.137.1:4444`（热点网关）和 `192.168.86.20:4444`（WLAN），断线每 10s 重连。
- **远程重启客户端**（更新脚本后）必须用分离进程，不能在客户端自己的命令里直接
  `schtasks /End`（会把自己杀掉导致失联）：

  ```powershell
  Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-Command','Start-Sleep 2; schtasks /End /TN RShellClient; Start-Sleep 2; schtasks /Run /TN RShellClient'
  ```

- 卸载：主 PC 删除启动文件夹里的 `RShellServer.vbs`（或 `Unregister-ScheduledTask RShellServer`）；
  受管机 `schtasks /Delete /TN RShellClient /F`。
