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
- push 模型：第二台 Windows / 手机**不需要任何入站权限**，主动 POST 到 Hub
- Hub 监听 `0.0.0.0:18993`，防火墙仅放行 Tailscale 网段（`100.64.0.0/10`）；loopback 供监控页读取

## 组件

| 文件 | 说明 |
| --- | --- |
| `metrics-hub.ps1` | Hub / Agent 双模式（Windows PowerShell 5.1+，无需管理员） |
| `install-hub.ps1` | 主 Windows：注册登录自启任务并启动 |
| `install-agent.ps1` | 第二台 Windows：由 Hub 分发，下载 + 启动文件夹自启 + 立即启动 |
| `phone-metrics.sh` | 安卓 Termux 上报脚本（/proc 采集 + curl POST） |

## 部署

### 主 Windows（Hub，已完成）

```powershell
# 普通权限：注册登录自启任务并启动
powershell -ExecutionPolicy Bypass -File .\install-hub.ps1

# 管理员一次（UAC）：放行 tailnet 网段访问
New-NetFirewallRule -DisplayName "wproxy-metrics-hub" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 18993 -RemoteAddress 100.64.0.0/10
```

### 第二台 Windows（Agent，普通权限，无需管理员）

```powershell
powershell -NoProfile -Command "[Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy; irm http://100.74.71.80:18993/static/install-agent.ps1 | iex"
```

前置：该机 Tailscale 已登录在线。安装后：下载脚本到 `~\.metrics-agent\`、启动文件夹写入 `metrics-agent.cmd`（登录自启）、立即启动上报。

### 安卓手机（Termux 上报，无需 root）

1. 安装 Termux（GitHub releases 或 F-Droid；手机开着 CMFA 代理即可直接下载）
2. Termux 里执行（一次性）：

```bash
pkg update -y && pkg install -y curl
curl -sL http://100.74.71.80:18993/static/phone-metrics.sh -o ~/phone-metrics.sh
chmod +x ~/phone-metrics.sh
termux-wake-lock
nohup ~/phone-metrics.sh >/dev/null 2>&1 &
```

3. （可选）开机自启：安装 Termux:Boot 后

```bash
mkdir -p ~/.termux/boot
printf '#!/data/data/com.termux/files/usr/bin/sh\ntermux-wake-lock\nnohup ~/phone-metrics.sh >/dev/null 2>&1 &\n' > ~/.termux/boot/metrics.sh
chmod +x ~/.termux/boot/metrics.sh
```

4. 系统设置 → 应用 → Termux → 电池 → **无限制**（否则后台会被杀）

数据路径：Termux → CMFA TUN → 规则 `100.64.0.0/10 → Tailscale(tsnode)` → Hub。
采集：CPU（/proc/stat 差分）、内存（/proc/meminfo）、磁盘（df /data）、带宽（/proc/net/dev 差分）、延迟（/dev/tcp 计时）、电量（/sys/class/power_supply，可读时）、开机时长。

## 端点

| 路径 | 说明 |
| --- | --- |
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
- ✅ Mac → Hub 可达（Windows netstat 显示 `100.105.59.31 → 100.74.71.80:18993 ESTABLISHED`）
- ✅ 监控页补丁下发（多设备 chips + Hub 数据源）

## 卸载

- 第二台 Windows：删启动文件夹 `metrics-agent.cmd` 与 `~\.metrics-agent\`，并结束 `-Mode agent` 进程
- 主 Windows：`schtasks /Delete /TN MetricsHub /F`（管理员）+ 删除防火墙规则 `wproxy-metrics-hub`
- 手机：结束 Termux 进程 / 删除 `~/phone-metrics.sh` 与 `~/.termux/boot/metrics.sh`
