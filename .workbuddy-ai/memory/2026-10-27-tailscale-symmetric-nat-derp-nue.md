# 2026-10-27 代理全断（claude.com ERR_TIMED_OUT）：本机对称 NAT 导致绕道德国 DERP

## 一句话

不是 Mac 挂了、不是配置错了 —— 是**本机（haoshao-1）的 NAT 变成对称型且路由器 UPnP 关闭**，
Tailscale 无法与 Mac 建立直连，退化成经**德国纽伦堡(nue)** 的 DERP 中继；
该路径下 Tailscale 数据面对 **TCP 完全不通**（连 Mac 的 22 端口 30s 都建不起来），
所有节点（MacHy2/MacMini/CF/Ngrok）因此全灭。已用 laptop 当跳板恢复。

## 关键证据

| 观测点 | 结果 |
| --- | --- |
| `mihomo` / `hysteria` / `cloudflared` 进程 | 全部在跑，7897/7893/7892 均在监听 |
| 直连 claude.com | 200，432KB / 3.9s（**Chengdu 出口正常**） |
| 直连 claude.ai/login | 302 → `app-unavailable-in-region`（地区封锁，直连不可用） |
| 走 7897 | claude/github/openai 全部 `context deadline exceeded` |
| `tailscale ping 100.105.59.31` | 10/10 pong，但 **393ms~1705ms 剧烈抖动**，`direct connection not established` |
| TCP 到 Mac :22 | **30s 仍 FAIL**（disco ping 能过、TCP 完全不通） |
| 本机 `netcheck` | `MappingVariesByDestIP: true`（对称 NAT）、`PortMapping: 空`（UPnP 失败） |
| **Mac 侧 `tailscale status`** | 对 `haoshao-1` = **`relay "nue"`（德国纽伦堡）**；对 `desktop-9m0bu3h` = **`direct 175.152.178.218:44165`** |
| Mac `netcheck` | `MappingVariesByDestIP: false`、`PortMapping: UPnP`（Mac 侧是好穿透的锥型 NAT） |
| Mac 本机测速 | 下载 1.24MB/s、上传 3.6MB/s（**Mac 网络完全正常**） |
| Mac 服务 | `socks-relay` 7891 监听、仅 2 线程（10-25 的泄漏修复仍有效）、hysteria2/udp-relay 均在 |

## 根因链

1. 本机 NAT = 对称型 + 路由器 UPnP 关闭 → 外部无法预测映射端口
2. Mac 虽有 UPnP/锥型 NAT，但移动网络不允许入站（130.62.209.98 的 22/7891/443/8443 全 closed）
3. 双方都拿不到可直连端点 → Tailscale 退到 DERP，且选中 `nue`（本机到 hkg 只要 180ms，Mac 最近是 tok）
4. 该 DERP 路径下 disco ping 能过、TCP 建连全超时 → 所有代理节点失效
5. 浏览器侧表现即 `ERR_TIMED_OUT`

## 恢复方案（已上线）

借 **laptop（`ADMIN@100.123.253.85` / DESKTOP-9M0BU3H）** 当跳板：
本机 ↔ laptop 是**局域网直连**（CurAddr 192.168.86.197），laptop ↔ Mac 是 **Tailscale 直连**（0.54s/204）。

**最终形态（持久化，已接到原有看门狗）**：

- `macos-socks-proxy/config.ps1` 新增两个变量：
  - `$JumpHost = 'ADMIN@100.123.253.85'` —— Mac 侧自检命令走 `-J` 用
  - `$TunnelTarget = 'ADMIN@100.123.253.85'` —— **承载 -L 转发的 SSH 终点**
- `ssh-proxy.ps1` 改为 `-L 1080:100.105.59.31:7891 <$TunnelTarget>`（单跳 SSH）
- `Start-ScheduledTask MacSocksProxy` 已拉起，日志：
  `jump=ADMIN@100.123.253.85 tunnelTarget=ADMIN@100.123.253.85` → `Tunnel is up (ssh pid 38860)`
- mihomo 节点 `Japan-MacMini-SSH`（127.0.0.1:1080）已 `PUT /proxies/PROXY` 切过去
- 实测：204 探测 **8/8**，claude.com 200（12~23s），小请求 0.6s

**关键坑（两次踩）**：SSH 终点一定要放在 **laptop**，不能放在 Mac。
- 终点=Mac + `-J laptop` → 双跳 SSH，且 `-L` 目标 `100.105.59.31:7891` 变成 **Mac 回连自己（发夹）**，
  实测只有 3/5~1/3 成功。
- 终点=laptop → 本机 --SSH--> laptop --TCP--> Mac:7891，8/8 稳定。

> 备用手法：`C:\Users\22771\AppData\Local\ProxyStack\` 下有 `tunnel-via-laptop.cmd` +
> `start-tunnel.vbs`（看门狗挂了可手动双击）。**不要**再往启动文件夹放，会和计划任务抢 1080。

## 遗留问题

1. **吞吐仍只有 25~31KB/s**。已单独验证 `laptop→Mac` 就是 31KB/s，Mac 自身 1.2MB/s 正常
   → 瓶颈是**成都↔大阪国际链路对 UDP 的限速**，与 Tailscale/DERP 无关。大页面仍会明显慢。
2. 真正修好要靠 **给本机做 UDP 41641 端口转发 / 开路由器 UPnP**（路由器 `192.168.80.1`，
   UPnP 探测无响应），让直连恢复；或引入有公网 IP 的 VPS 中转。
3. `Japan-MacMini`（直连 Mac 7891）与 `Japan-MacHy2`（hysteria）在本机 NAT 修好前都不要用。

## 复用手法（值得记住）

**经 cloudflared 隧道 SSH 进 Mac**（Tailscale 全断时唯一的救命通道）：

```bash
# 1) 7892 是 SOCKS5（出口=Mac），先验证能摸到 Mac 的 sshd
curl -s --socks5-hostname 127.0.0.1:7892 telnet://127.0.0.1:22   # 期望 SSH-2.0-OpenSSH_10.3
# 2) 用 SOCKS5 桥当 ProxyCommand（脚本 C:\Users\22771\AppData\Local\Temp\socks5pipe.py + .bat）
ssh -o ServerAliveInterval=0 -o TCPKeepAlive=no \
    -o ProxyCommand="C:/Users/22771/AppData/Local/Temp/socks5pipe.bat %h %p" \
    -i ~/.ssh/id_ed25519 kobayashi@127.0.0.1
```

坑：`ServerAliveInterval` 必须设 0，否则慢链路会被 keepalive 判死；
代理脚本必须用 `os.read(0, N)`，用 `sys.stdin.read(N)` 会阻塞等满缓冲区，握手直接卡住。

## 顺带发现

- 本机到 `DESKTOP-9M0BU3H` 的 `CurAddr` 是 `192.168.86.197`，两者在同一局域网
- 本机默认网关 `192.168.80.1` 在 `/21`（255.255.248.0）内，路由本身正常，不是故障点
- `schtasks.exe`、`reg.exe` 在本机安全策略黑名单里，别指望用它注册计划任务
