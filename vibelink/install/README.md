# 安装 / 自启

两个脚本，都是**幂等**的：重复执行只会覆盖配置，不会重复注册。

## Windows

需要在仓库根目录先构建好二进制：

```powershell
cd vibelink
cargo build --release
```

然后**以管理员身份**运行（脚本会自己弹 UAC）：

```powershell
# 主机（PC）：同时装 hub + agent
cd vibelink\install
.\install-windows.ps1 -Device pc

# 客户端（笔记本）：不装 hub，指向 PC
.\install-windows.ps1 -NoHub `
  -HubUrl http://192.168.137.1:18995 `
  -Token <hub 的 data/token.txt> `
  -Device laptop

# 卸载（保留数据）
.\install-windows.ps1 -Uninstall
# 连数据一起删
.\install-windows.ps1 -Uninstall -Purge
```

它会做什么：

| 动作 | 位置 |
| --- | --- |
| 复制二进制 | `%LOCALAPPDATA%\vibelink\bin\` |
| 生成启动脚本 | `run-hub.cmd` / `run-agent.cmd`（环境变量写死在里面） |
| 注册计划任务 | `VibelinkHub` / `VibelinkAgent`（登录触发、失败重启 3 次×1 分钟、最高权限） |
| 日志 | `%LOCALAPPDATA%\vibelink\data\hub.log`、`agent.log` |
| 停用旧的 Node 自启 | 把启动文件夹里的 `vibelink-hub.lnk` / `vibelink-agent.lnk` / `phone-config-http.lnk` 改名成 `.disabled` |

## macOS

```bash
cd vibelink/install
chmod +x install-macos.sh

# 只装 agent（Hub 在 PC 上）
./install-macos.sh --hub http://192.168.137.1:18995 --token <token> --device mac

# 顺便让 Mac 也当 Hub
./install-macos.sh --hub-local --device mac

# 加 GUI 自启（--minimized，开机直接进托盘）
./install-macos.sh --hub ... --token ... --device mac --gui

# 卸载
./install-macos.sh --uninstall
```

- 二进制：`~/Library/Application Support/vibelink/bin/`
- 数据：`~/Library/Application Support/vibelink/data/`
- LaunchAgent：`~/Library/LaunchAgents/ai.vibelink.{hub,agent,gui}.plist`（`RunAtLoad` + `KeepAlive`）
- 日志：`~/Library/Logs/mesh-*.log`

首次运行可能是未签名二进制，Gatekeeper 会拦；在 Finder 里右键→打开，或：

```bash
xattr -d com.apple.quarantine ~/Library/Application\ Support/mesh/bin/vibelink-agent
```

## 注意

1. **device id 必须和 hub 里策略的 `rules[].device` / `egress` 对上**，否则自愈引擎找不到设备（默认策略写的是 `pc-rs` / `laptop-rs`，装完记得在 GUI 的「自愈」里改）。
2. 装完先确认两个进程在跑：

   ```powershell
   Get-Process vibelink-hub, vibelink-agent | Select-Object ProcessName, Id
   Get-ScheduledTask -TaskName VibelinkHub, VibelinkAgent | Select-Object TaskName, State
   ```

3. **启用自愈（`dryRun: false`）前**，先停掉旧的 `PcProxyFailover`，两者都会写系统代理：

   ```powershell
   Disable-ScheduledTask -TaskName PcProxyFailover
   ```

## Windows：看门狗（hub / agent / Tailscale）

[`vibelink-watchdog.ps1`](vibelink-watchdog.ps1) 是当前 PC 在用的常驻看门狗，
每 20 秒检查 hub、agent 和 Tailscale。部署方式：

```powershell
# 1) 脚本放到 ~\bin（UTF-8 with BOM，别用无 BOM，PowerShell 5.1 会解析失败）
Copy-Item .\vibelink-watchdog.ps1 "$env:USERPROFILE\bin\" -Force

# 2) 注册登录任务（失败重启 3 次）
$script  = "$env:USERPROFILE\bin\vibelink-watchdog.ps1"
$pwsh    = "$env:LOCALAPPDATA\Programs\PowerShell\7\pwsh.exe"
$action  = New-ScheduledTaskAction -Execute $pwsh `
  -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
$trigger = New-ScheduledTaskTrigger -AtLogOn
$set     = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
  -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew -Hidden
Register-ScheduledTask -TaskName VibelinkWatchdog -Action $action -Trigger $trigger -Settings $set -Force
```

不要同时保留启动文件夹里的 `vibelink-watchdog.vbs`：两个入口会互相抢互斥体，
谁先起来是随机的（虽然逻辑上无害，但排查时容易看错）。


## macOS 免管理员方案（当前 Mac 用这个）

Mac 上的 `kobayashi` 不是管理员、也没有图形会话，所以 LaunchAgent/LaunchDaemon 都装不了。
改用**用户级 cron**，不需要任何提权：

```bash
# 1) 启动脚本
mkdir -p ~/bin
cat > ~/bin/vibelink-agent-start.sh <<'EOF'
#!/bin/sh
export VIBELINK_HUB=http://127.0.0.1:18995
export VIBELINK_TOKEN=<hub token>
export VIBELINK_DEVICE=mac
exec "$HOME/src/vibelink/target/release/vibelink-agent" >> "$HOME/Library/Logs/vibelink-agent.out.log" 2>&1
EOF
chmod +x ~/bin/vibelink-agent-start.sh

# 2) cron：开机自启 + 每 2 分钟保活
crontab -l > /tmp/ct 2>/dev/null || true
printf '%s\n' '@reboot /Users/kobayashi/bin/vibelink-agent-start.sh' \
  '*/2 * * * * pgrep -f vibelink-agent >/dev/null || /Users/kobayashi/bin/vibelink-agent-start.sh' >> /tmp/ct
crontab /tmp/ct
```

**前提：Mac 能连到 hub。** 这台 Mac → PC 的 Tailscale 是单向不通的，所以在 PC 上跑反向隧道：

```
ssh -N -R 18995:127.0.0.1:18995 kobayashi@100.105.59.31
```

PC 侧已封装成 `C:\Users\22771\bin\vibelink-tunnel.ps1`（看门狗 + 退避重连），
启动文件夹里有 `vibelink-tunnel.lnk`。

限制：cron 方案只能**监控**，改系统代理仍需管理员（Mac 在境外，用不到）。
