# macos-udp-proxy

Mac 端 SOCKS5 代理方案。**TCP 正常；UDP 的 Mac 侧问题已修，但回程仍不通，暂不可用**
（2026-10-11 实测，见文末「UDP 状态」）。
Windows 上的 mihomo（wproxy 项目）经 Tailscale 直连 Mac 的代理端口，实现完整的网络出口代理。

> 背景：原 SSH 隧道方案（macos-socks-proxy 项目）受限于 OpenSSH，只能代理 TCP。
> 本方案替代它承载日常代理流量；SSH 隧道保留为备用节点（仅 TCP）。

## 组成

| 位置 | 文件 | 说明 |
| --- | --- | --- |
| Mac | `~/bin/socks-relay.py` | SOCKS5 服务：TCP CONNECT 可用；UDP ASSOCIATE 代码已修（2026-10-11），但回程不通 |
| Mac | `~/Library/LaunchAgents/com.socks-relay.plist` | 开机自启 + while 守护循环（python 崩溃 2 秒内自愈），已加 `NumberOfFiles=10240` FD 上限 |
| Mac | `~/.config/socks-relay/*.log` | 运行日志 |
| Windows | mihomo 节点 `Japan-MacMini` | `socks5 100.105.59.31:7891, udp: true` |

监听地址：`100.105.59.31:7891`（仅 Tailscale 地址，不全网暴露）。

## 为什么不是 mihomo / sing-box？

macOS 应用防火墙（ALF）会**自动拦截未签名第三方程序的入站连接**（实测 mihomo 被列入 Block，
且当前账户无管理员权限无法放行）。而系统自带的 python3 是苹果签名，ALF 自动放行——
于是用 python3 承载代理服务即可绕过，零安装、零权限。
（实验残留：`~/bin/mihomo` 和 `com.mihomo.socks.plist.disabled`，可留可删。）

## 保活机制（双层）

1. **Mac 本地**：plist 用 `while true` 循环包住 python，进程崩溃 2 秒内自动重启
2. **Windows 侧监控**：macos-socks-proxy 项目的看门狗每 5 分钟 ssh 检查 Mac 的 7891 监听，
   发现掉了就自动 `kickstart`（覆盖 Mac 重启后 launchd 未拉起等极端情况）

## ⚠️ 重启后服务不自动拉起？用 `user` domain，不是 `gui` domain

**2026-10-24 踩过的坑**：Mac 重启后 7891 / 18992 / 8443 全部没监听，`launchctl list` 里一个自建服务都没有。
原因是这台 Mac 上有两个账户：

| 账户 | uid | 角色 |
| --- | --- | --- |
| `liang` | 501 | **console 登录用户**（`stat -f%Su /dev/console`） |
| `kobayashi` | 503 | SSH 用户，`~/bin/*` 与 `~/Library/LaunchAgents/*.plist` 都在这里 |

launchd 的 **gui domain 只存在于 console 登录的那个用户**。plist 装在 `kobayashi` 名下，
SSH 进去 bootstrap 时 `gui/503` 根本不存在：

```
launchctl bootstrap gui/$(id -u) ...plist   # Bootstrap failed: 125: Domain does not support specified action
launchctl bootstrap user/503 ...plist       # Bootstrap failed: 5: Input/output error（没加 LimitLoadToSessionType 时）
```

**修法**：给 plist 加 `LimitLoadToSessionType=Background`，服务就不再依赖 GUI 会话，可在 user domain 加载：

```bash
/usr/libexec/PlistBuddy -c "Add :LimitLoadToSessionType string Background" \
  ~/Library/LaunchAgents/com.socks-relay.plist
launchctl bootstrap user/$(id -u) ~/Library/LaunchAgents/com.socks-relay.plist
```

四个服务（`com.socks-relay` / `com.mac-metrics` / `com.hysteria2` / `com.udp-relay`）都要加，加完重启才真正自启。

## 管理命令（ssh 到 Mac 后执行）

```bash
netstat -an | grep 7891                                  # 查看监听（应有 tcp4 LISTEN）
launchctl list | grep -E 'socks-relay|mac-metrics|hysteria|udp-relay'   # 是否被 launchd 托管
launchctl kickstart -k user/$(id -u)/com.socks-relay     # 重启（改完代码/配置后用这个）
launchctl bootout user/$(id -u)/com.socks-relay          # 停止
launchctl bootstrap user/$(id -u) ~/Library/LaunchAgents/com.socks-relay.plist   # 注册
#   ⚠️ bootstrap 只注册不启动，bootstrap 之后必须再 kickstart -k 一次
tail -f ~/.config/socks-relay/stdout.log                 # 日志
lsof -p $(pgrep -f socks-relay.py | tail -1) | wc -l      # FD 用量，稳定在 ~70-80

# 健康检查：存量指标（比 delay 探活更能发现泄漏）
netstat -an -p tcp | grep 7891 | awk '{print $6}' | sort | uniq -c   # CLOSE_WAIT 应接近 0
ps -M $(lsof -nP -iTCP:7891 -sTCP:LISTEN | tail -1 | awk '{print $2}') | wc -l   # 线程应 < 150
```

> ⚠️ **`delay` 探活正常 ≠ relay 健康**。mihomo 的探活每次新建连接，测不到泄漏的旧连接。
> 2026-10-25 就是「探活 153ms 正常、真实请求全超时」，根因是 `pipe()` 无超时导致的
> 线程 + `CLOSE_WAIT` 双泄漏（520 线程 / 58 CLOSE_WAIT），详见
> `.workbuddy-ai/memory/2026-10-25-socks-relay-thread-leak.md`。
> 修复后 CLOSE_WAIT 归零、线程降到 64~111。

> 别用 `nohup ... &` 手动拉服务：launchd 的 `KeepAlive` 会同时拉起一份，两份抢同一个端口，
> 日志里刷 `v4 listen failed on 0.0.0.0:7891 [Errno 48] Address already in use`。
> macOS 也**没有 `setsid`**（util-linux 才有），想脱离 SSH 会话只能用 `nohup ... & disown`，正解仍是交给 launchd。

## Clash 分流规则（默认直连，需要外网的才代理）

规则顺序（自上而下匹配，命中即止），与 `wproxy/config.yaml` 完全一致：

```yaml
rules:
  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve   # Tailscale 网段
  - GEOIP,private,DIRECT                       # 局域网
  - PROCESS-NAME,tailscaled.exe,DIRECT         # Windows 端：Tailscale 自身进程直连
  - PROCESS-NAME,tailscale.exe,DIRECT
  - DOMAIN-SUFFIX,tailscale.com,PROXY         # 控制台域名会被 DNS 污染成 CN IP，必须走代理
  - GEOSITE,geolocation-!cn,PROXY              # 明确标注为非中国大陆的域名（27217 条）
  - MATCH,DIRECT                               # 默认直连
```

**2026-10-11 改策略**：原来是 `GEOSITE,cn,DIRECT` + `GEOIP,CN,DIRECT` + `MATCH,PROXY`，即"国内直连、其余全走代理"。
现在反过来 —— **兜底是 `MATCH,DIRECT`，只有 `geolocation-!cn` 里的域名才走代理**。好处是未被 geosite 收录的冷门站点
默认直连，不会白白消耗跨境带宽（实测本机经 Mac 的隧道单流只有约 100KB/s）。

配套改动（两端一致）：

- `dns.fallback-filter` 去掉了 `geoip: true` / `geoip-code: CN`，只保留 `geosite: [gfw]` + `ipcidr: 240.0.0.0/4`。
  以前所有解析到非 CN IP 的域名都会被丢给钉在 `#PROXY` 上的境外解析器，代理一挂 DNS 就整体卡死；
  现在只有真正被墙的域名才会去碰它们，Steam / OneDrive / Microsoft 这类直接由 doh.pub、alidns 解析。
- `nameserver` 从 `https://doh.pub/dns-query` 改成 IP 字面量 `https://223.5.5.5/dns-query`，
  避免 DNS 自举死锁（域名形式的 DoH 在 `dns-hijack: any:53` 下会自己把自己卡住）。

调整规则的方法：

- 想让某个站走代理：在 `MATCH` 之前加 `- DOMAIN-SUFFIX,example.com,PROXY`
- 想让某个站强制直连：加 `- DOMAIN-SUFFIX,example.com,DIRECT`（放最前面）
- 改完用 `wproxy.ps1 -Action reload` 热重载（秒生效）
- 验证：wproxy WebUI（`wproxy.ps1 -Action ui`）可查看每条连接命中 DIRECT 还是 Japan-MacMini

## 验证（Windows）

```powershell
# TCP：应返回日本出口 IP 130.62.209.98
curl.exe --socks5-hostname 100.105.59.31:7891 https://api.ipify.org
```

**UDP：实测不通（2026-10-11）** —— 见下方「UDP 已知问题」。

## UDP 状态：Mac 侧已修好，但回程仍不通（2026-10-11）

排查出 3 个独立问题，前两个在 Mac 侧已修，第三个还没定位。

### ① UDP ASSOCIATE 返回非法地址（已修）

`handle_udp` 原来把 UDP socket 绑在 `('0.0.0.0', 0)`，然后直接把 `getsockname()` 回给客户端：

```
UDP ASSOCIATE reply: 05 00 00 01 00 00 00 00 D0 21
                            └─ BND.ADDR = 0.0.0.0
```

RFC 1928 要求这里返回客户端该把 UDP 包发到哪里，回 `0.0.0.0` 等于没说，mihomo 因此拿不到可用目标。

**修法**：从已建立的 TCP 连接取 `conn.getsockname()[0]`（客户端实际连上来的那个地址，即 Tailscale IP），
把 UDP socket 绑到该地址而不是 `0.0.0.0`。修复后实测：

```
UDP ASSOCIATE reply: 05 00 00 01 64 69 3B 1F C6 9D
                            └─ BND.ADDR = 100.105.59.31  PORT = 50845
```

### ② `getaddrinfo` 返回 IPv6 地址（已修）

stderr 里暴露的另一个 bug：

```
File "/Users/kobayashi/bin/socks-relay.py", line 174, in udp_loop
    target_sock.sendto(payload, dest)
TypeError: AF_INET address must be a pair (host, port)
```

`socket.getaddrinfo(host, port, type=socket.SOCK_DGRAM)` 没限定 `family`，可能返回 IPv6 结果，
其 `sockaddr` 是 4 元组，发给 `AF_INET` socket 直接抛异常。**修法**：显式传 `socket.AF_INET`。

### ③ 文件描述符耗尽（已修，且当时正在拖累 TCP）

```
ulimit -n            → 256                      ← 上限太低
relay 日志            → connect failed: 1.1.1.1:443 [Errno 24] Too many open files
Mac netstat           → tcp4 ... 100.105.59.31.7891 100.98.206.126.52008 SYN_RCVD（堆积）
```

`handle_udp` 每次关联开 2 个 socket，只在 TCP 连接关闭时回收，mihomo 反复建 UDP 关联就把 FD 堆到 256 上限。
后果不只影响 UDP —— **连 TCP 代理都开始 `connect failed`**，backlog 里的连接卡在 `SYN_RCVD`。

**修法**（三重）：

1. `~/Library/LaunchAgents/com.socks-relay.plist` 加 `SoftResourceLimits` / `HardResourceLimits`，
   `NumberOfFiles` = 10240
2. plist 的 `ProgramArguments` 改成 `ulimit -n 10240; while true; do ...; done` 双保险
3. 代码里加 `MAX_UDP_SESSIONS = 64`（超限直接回 `0x01` 拒绝）+ `UDP_IDLE_TIMEOUT = 300`
   （300 秒无流量自动回收 socket）

修复后 FD 稳定在 74，日志不再出现 `Errno 24`。

### ④ Mac → PC 的 UDP 回程不通（**未解决**）

这是目前端到端 UDP 仍然不通的原因。裸 UDP 实测：

| 方向 | 结果 |
| --- | --- |
| PC → Mac `100.105.59.31:9998` | ✅ Mac 收到（`RECV#1 4 bytes from 100.98.206.126 data=aabbccdd`） |
| Mac → PC `100.98.206.126:9996` | ❌ Mac `sendto` 返回 8 字节成功，PC 两次监听全超时 |
| Mac → PC `dig @100.100.100.100`（Tailscale MagicDNS） | ❌ connection timed out |

TCP 双向都正常（SSH 一直好用），**只有 UDP 的 Mac→PC 方向不通**。

已排除的可能：Tailscale 网卡是 `Private` 网络类别且有 `Tailscale-In` 入站放行规则；
`Private` 防火墙配置本身是 `Enabled = False`（等于不过滤）。所以不是普通的防火墙拦截，具体原因待查。

**在 ④ 解决之前，本方案的 UDP 不可用，只有 TCP 可用。**

### 改动的备份

- `~/bin/socks-relay.py.bak-20261011`（原版 7670 字节）
- `~/Library/LaunchAgents/com.socks-relay.plist.bak-20261011`

回滚：把备份覆盖回去，然后
`launchctl kickstart -k gui/$(id -u)/com.socks-relay`

### ⚠️ launchctl 踩坑

`launchctl bootstrap` **只注册不启动**，`launchctl print` 会显示 `state = not running`。
必须再执行 `launchctl kickstart -k gui/$(id -u)/com.socks-relay` 才会真正拉起。
（排查过程中因为只 bootstrap 没 kickstart，代理曾短暂中断。）

