# macos-metrics

Mac 后台监控数据源：**无 sudo、stdlib-only 的 Python 指标代理**，为 wproxy WebUI（zashboard）提供 CPU / GPU / 内存 / 硬盘 / 带宽 / 延迟 等实时指标。

## 原理与实现细节（为什么这么做）

| 指标 | 实现 | 说明 |
| --- | --- | --- |
| CPU | `ps -Ao time=` 累计 CPU 时间差分 / (dt × 核数) | macOS 无 `kern.cp_time`，此路线不需要 root |
| GPU | `ioreg -r -d 1 -c IOAccelerator -l` 解析 `Device Utilization %` | Apple Silicon（AGXAccelerator）可用；同时给出 Tiler/Renderer/显存占用 |
| 内存 | `vm_stat` 页统计 + `sysctl hw.memsize` + `vm.swapusage` | used ≈ active + wired + compressor |
| 硬盘 | `df -k /System/Volumes/Data` | Data 卷才是真实占用（系统卷是只读封存卷） |
| 带宽 | `netstat -ib` 计数器每秒差分 | 汇总只算物理网卡（en*），避免 Tailscale 虚拟网卡重复计数 |
| 延迟 | TCP connect 计时（1.1.1.1 / 8.8.8.8 / 223.5.5.5 :443） | ICMP ping 需要 root，TCP connect 等价可用 |
| 负载/开机时长 | `vm.loadavg` / `kern.boottime` | |

- 纯 stdlib（`http.server`），运行在系统签名的 python3 上（ALF 放行入站）；**仅监听 Tailscale IP** `100.105.59.31:18992`，不暴露公网。
- JSON 端点：`GET http://100.105.59.31:18992/metrics`（含 `Access-Control-Allow-Origin: *`，供 127.0.0.1:9097 的页面跨域读取）；`/healthz` 返回 ok。
- 采样线程：CPU/带宽 1s，GPU/内存 2s，延迟/负载 5s，磁盘 30s。

## 部署（Mac，无需 sudo）

```bash
# 1. 脚本与 LaunchAgent
scp mac-metrics-agent.py mac:~/.bin/  # 实际路径 ~/bin/mac-metrics-agent.py
scp com.mac-metrics.plist mac:~/Library/LaunchAgents/
ssh mac 'mkdir -p ~/.config/mac-metrics'

# 2. 注册（本机 launchd 不执行 RunAtLoad/KeepAlive 的怪癖 → while 守护循环）
ssh mac 'launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.mac-metrics.plist'
ssh mac 'launchctl kickstart -k gui/$(id -u)/com.mac-metrics'
```

## 保活

Windows 看门狗（`macos-socks-proxy/ssh-proxy.ps1`，计划任务 `MacSocksProxy`）每约 1 分钟检查：
`com.mac-metrics` 未加载（含 Mac 重启后）→ 自动 bootstrap + kickstart。

## UI 集成

指标面板由 `wproxy/ui-patch/` 注入 zashboard（补丁幂等，`download-assets.ps1` 会自动应用）：
`http://127.0.0.1:9097/ui/#/settings` 右下角显示面板。

## 验证记录（2026-10-06）

- ✅ `curl http://100.105.59.31:18992/metrics` 返回完整 JSON：cpu=3.5%、gpu=3%、mem=10.8G/16G、disk=28.2%、net rx/tx、latency 1.1.1.1=14.8ms / 8.8.8.8=16.1ms / 223.5.5.5=23.6ms
- ✅ launchd 守护运行（`com.mac-metrics`），Windows 看门狗已纳入守护列表
- ✅ mihomo 已验证可向浏览器提供补丁后的 `/ui/`（含 mac-monitor.js）

## 注意

- 首次打开页面若仍是旧 zashboard，请**刷新两次**：第一次让旧 Service Worker 自毁，第二次加载补丁
- 面板显示条件可在 `wproxy/ui-patch/mac-monitor.js` 顶部 `CFG.showWhen` 改为 `'always'`（默认 `'settings'` 仅设置页显示）
