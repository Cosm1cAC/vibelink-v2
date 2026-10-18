# metrics-hub

多设备监控采集中心：把「连着本代理栈的每台设备」的状态汇总到 wproxy 监控页（zashboard 设置页右下角面板）。

## 架构

```
监控页 (127.0.0.1:9097/ui 设置页)
   └─ GET /devices ─> Windows Hub (主 Windows, :18993, 用户态)
                        ├─ 自采集（CPU / GPU / 内存 / 硬盘 / 带宽 / 延迟）
                        ├─ 收 push：第二台 Windows（agent 模式，POST /ingest）
                        ├─ 收 push：安卓手机（Termux 脚本，POST /ingest）
                        └─ 主动 pull：Mac 指标代理（100.105.59.31:18992，见 macos-metrics 项目）
```

- 面板按设备切换（设备名 chips，带在线状态点），内存行可点击展开明细
- 延迟行（四端统一口径，2026-10-07）：`直连国内` = 223.5.5.5:443 TCP 握手；`代理链路` = 到 cp.cloudflare.com 的真实 HTTP 请求耗时（PC/手机经各自代理链路，Mac 为出口直连）。网状网（Tailscale 直连/中继）行已从卡片移除——它只是 Tailscale 互联状态，与代理性能无关，曾多次造成误解
- push 模型：第二台 Windows / 手机**不需要任何入站权限**，主动 POST 到 Hub
- Hub 监听 `0.0.0.0:18993`，防火墙仅放行 Tailscale 网段（`100.64.0.0/10`）；loopback 供监控页读取

## 组件

| 文件 | 说明 |
| --- | --- |
| `metrics-hub.ps1` | Hub / Agent 双模式（Windows PowerShell 5.1+，无需管理员） |
| `install-hub.ps1` | 主 Windows：注册登录自启任务并启动（动作指向 `hub-start.vbs`；改动作时自动 UAC 一次） |
| `hub-start.vbs` | 静默拉起 Hub（wscript 窗口样式 0，任务动作/看门狗重启都用它，不闪黑窗） |
| `hub-watchdog.ps1` + `hub-watchdog.vbs` | 自愈看门狗：探测卡死并重启 `MetricsHub` 任务（见下） |
| `install-agent.ps1` | 第二台 Windows：由 Hub 分发，下载 + 启动文件夹自启 + 立即启动 |
| `phone-metrics.sh` | 安卓 Termux 上报脚本（/proc 采集 + curl POST） |

## 部署

> ⚠️ **2026-10-10 本机 Tailscale 节点重建**（`haoshao` → `haoshao-1`），地址从 `100.74.71.80`
> 变为 **`100.98.206.126`**（MagicDNS `haoshao-1.tail2fec51.ts.net`），旧地址已不可达。仓库内脚本/文档已同步；
> 但**手机上已装好的旧脚本仍写着旧地址**，需重下 `/static/phone-metrics.sh`（或改 `HUB=`）才能继续上报。

### 主 Windows（Hub，已完成）

```powershell
# 注册登录自启任务并启动（任务动作已存在时需管理员，脚本会自动请求一次 UAC）
powershell -ExecutionPolicy Bypass -File .\install-hub.ps1

# 管理员一次（UAC）：放行 tailnet 网段访问
New-NetFirewallRule -DisplayName "wproxy-metrics-hub" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 18993 -RemoteAddress 100.64.0.0/10
```

### 第二台 Windows（Agent，普通权限，无需管理员）

```powershell
powershell -NoProfile -Command "[Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy; irm http://100.98.206.126:18993/static/install-agent.ps1 | iex"
```

前置：该机 Tailscale 已登录在线。安装后：下载脚本到 `~\.metrics-agent\`、启动文件夹写入 `metrics-agent.cmd`（登录自启）、立即启动上报。

### 安卓手机（Termux 上报，无需 root）

1. 安装 Termux（GitHub releases 或 F-Droid；手机开着 CMFA 代理即可直接下载）
2. Termux 里执行（一次性）：

```bash
pkg update -y && pkg install -y curl
curl -sL http://100.98.206.126:18993/static/phone-metrics.sh -o ~/phone-metrics.sh
chmod +x ~/phone-metrics.sh
termux-wake-lock
nohup ~/phone-metrics.sh >> ~/phone-metrics.log 2>&1 &
```

3. （可选）开机自启：安装 Termux:Boot 后

```bash
mkdir -p ~/.termux/boot
printf '#!/data/data/com.termux/files/usr/bin/sh\ntermux-wake-lock\nnohup ~/phone-metrics.sh >> ~/phone-metrics.log 2>&1 &\n' > ~/.termux/boot/metrics.sh
chmod +x ~/.termux/boot/metrics.sh
```

4. 系统设置 → 应用 → Termux → 电池 → **无限制**（否则后台会被杀）

数据路径：Termux → CMFA TUN → 规则 `100.64.0.0/10 → Tailscale(tsnode)` → Hub。

Android 10+ 对应用隐藏 `/proc/stat`、`/proc/net/dev`、`/proc/uptime`，脚本已改用：
内存（/proc/meminfo）、磁盘（df）、带宽（`/sys/class/net/*/statistics`）、延迟（/dev/tcp 计时）、
负载（/proc/loadavg，可读时）、电量（/sys/class/power_supply；装 Termux:API 后自动改用 termux-battery-status）。
CPU 若被系统禁止则显示 `--`。运行日志：`~/phone-metrics.log`（仅出错时写诊断）。

## 手机查看监控

浏览器直接打开 **`http://100.98.206.126:18993/`**（Hub 内置手机版监控页，深色自适应，2s 刷新；仅 tailnet 可达，无需任何额外配置）。建议「添加到主屏幕」当 App 用。

> 完整的 zashboard（127.0.0.1:9097）只监听 Windows 本机且需要后端密钥，手机如需使用需额外开放监听+防火墙+在手机上配置密钥，一般不必。

## 端点

| 路径 | 说明 |
| --- | --- |
| `GET /` 或 `/m` | 手机版监控页（mobile.html） |
| `GET /devices` | 聚合全部设备（监控页用） |
| `GET /metrics` | Hub 本机指标（调试） |
| `POST /ingest` | Agent 上报（JSON） |
| `GET /static/...` | 分发安装脚本 |

## 安全

- 仅 tailnet 可达（防火墙限定来源网段），无公网暴露；无鉴权，依赖 tailnet 边界
- Agent/手机均为出站上报，不引入额外入站面

## 验证记录（2026-10-06）

- ✅ Hub 本机全字段实测：cpu=3.1%、gpu、mem=14.3G/16.9G、disk=91.8%、net、延迟（直连223.5.5.5=23.4ms、代理出口=34ms）
- ✅ agent 模式回环测试：`test-agent` 上报后出现在 `/devices`
- ✅ Mac → Hub 可达（Windows netstat 显示 `100.105.59.31 → 100.98.206.126:18993 ESTABLISHED`）
- ✅ 监控页补丁下发（多设备 chips + Hub 数据源）

## Hub 自愈看门狗（hub-watchdog）

每 2 分钟跑一次，探测 `http://127.0.0.1:18993/devices`：连试 3 次（间隔 10s）都拿不到 200
就判定 hub 卡死，杀掉 `metrics-hub.ps1` 进程并重启 `MetricsHub` 计划任务。

**2026-10-11 改为隐藏启动**：原先任务直接执行 `powershell.exe -WindowStyle Hidden -File hub-watchdog.ps1`，
但每次运行仍会新建一个 `conhost.exe`，导致**每两分钟闪一次命令行窗口**。

现在任务动作改成 `wscript.exe hub-watchdog.vbs`，由 VBS 以窗口样式 0（隐藏）拉起 powershell：

```vbs
CreateObject("WScript.Shell").Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""<路径>\hub-watchdog.ps1""", 0, False
```

`wscript.exe` 是 GUI 程序，任务调度器不会为它分配控制台，所以彻底不弹窗。
（同样的写法见 `rshell/install-server.ps1:23-27`、`macos-socks-proxy/install.ps1:67`。）

验证：自动触发后 `LastTaskResult=0`、`LastRunTime` 正常推进、新增 `conhost.exe` 数量为 0。

```powershell
# 手动触发一次验证
Start-ScheduledTask -TaskName MetricsHubWatchdog
# 完全停掉（会失去自愈能力）
Disable-ScheduledTask -TaskName MetricsHubWatchdog
```

**2026-10-18 Hub 自身的启动也改成静默（`hub-start.vbs`）**：`MetricsHub` 任务动作原先直接执行
`powershell.exe -WindowStyle Hidden -File metrics-hub.ps1 -Mode hub`，与上面 10-11 修掉的问题同源——
**每次被看门狗重启（`Stop-ScheduledTask`/`Start-ScheduledTask`）或登录自启时都会闪一下黑窗**。
现在任务动作指向同目录的 `hub-start.vbs`，由 wscript 以窗口样式 0 拉起，彻底无窗口：

```vbs
Dim sh, ps, script
Set sh = CreateObject("WScript.Shell")
ps = sh.ExpandEnvironmentStrings("%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe")
script = Replace(WScript.ScriptFullName, "hub-start.vbs", "metrics-hub.ps1")
sh.Run """" & ps & """ -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & script & """ -Mode hub", 0, False
```

- **宿主必须写绝对路径**：本机 `HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\powershell.exe`
  被指到了 `...\Programs\PowerShell\7\pwsh.exe`，裸名 `powershell.exe` 会被 App Paths 劫持成 pwsh 宿主
  （`hub-watchdog.vbs` 里就是裸名，一直跑在 pwsh 7 下；功能正常，只是宿主与文档不一致）
- VBS 用 `WScript.ScriptFullName` 推导同目录的 `metrics-hub.ps1`，仓库搬家无需改文件
- 更新任务动作需要管理员：跑一次 `.\install-hub.ps1 -RegisterTaskOnly`（或直接跑 `.\install-hub.ps1`，会自动 UAC，且检测到 18993 已在监听就不重复启动）
- 语义变化：wscript 拉起后立即退出，任务本身显示 `Ready`（旧动作下会一直显示 `Running`）；
  `Stop-ScheduledTask` 不再能杀掉 Hub 进程，真正杀进程的是看门狗里的 `Stop-Process`（先杀后重启的顺序没变）
- 手动起一次：`wscript.exe hub-start.vbs`（若 Hub 已在运行，新实例抢不到端口会进 5s 重试循环，记得结束它）

验证（2026-10-18 20:23，端到端而非手动启动）：强杀 Hub + `Stop-ScheduledTask` → 等计划看门狗自然触发（约 2 分钟）
→ `LastRunTime` 推进到新实例创建时刻、`LastTaskResult=0`、`GET /devices -> 200` 恢复（离线约 1.5 分钟）、
**conhost 数量不增加**（与 10-11 同一判据）、全程只有 1 个 Hub 实例、宿主仍是 Windows PowerShell 5.1。

### 已知问题：看门狗偶尔误判重启

`Update-Latency` / `Get-LinkMbps`（`Get-NetAdapter`、`Get-NetRoute`）这些同步 WMI 调用偶尔卡顿，
Hub 主循环是单线程的，卡住时 `/devices` 探测会超时（20s×3 次），看门狗即强杀重启——
现象是请求日志 `requests.log` 里出现 3～6 分钟的空档（2026-10-17 17:18 / 17:54 / 18:34 各有一次）。
链路速率查询因此加了缓存（`$script:LinkCache`，默认 300s），减少高频 WMI 调用。

## 卸载

- 第二台 Windows：删启动文件夹 `metrics-agent.cmd` 与 `~\.metrics-agent\`，并结束 `-Mode agent` 进程
- 主 Windows：`schtasks /Delete /TN MetricsHub /F` + `schtasks /Delete /TN MetricsHubWatchdog /F`（管理员）+ 删除防火墙规则 `wproxy-metrics-hub`
- 手机：结束 Termux 进程 / 删除 `~/phone-metrics.sh` 与 `~/.termux/boot/metrics.sh`
