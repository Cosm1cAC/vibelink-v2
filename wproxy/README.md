# wproxy

自建代理控制台：**开源 mihomo 内核 + 自研控制脚本 + 开源 WebUI（zashboard）**。
2026-10-06 已完成对 CuteCloud 的替代：系统代理接管至 127.0.0.1:7897，原 CuteCloud 已卸载（服务与残留全部清除）。

## 目录结构

| 文件 | 说明 |
| --- | --- |
| `mihomo.exe` | 开源内核（MetaCubeX/mihomo v1.19.32 官方 release） |
| `wintun.dll` | TUN 模式驱动（wintun 官方 0.14.1）；**TUN 当前不可用，见「模式切换」段** |
| `hysteria.exe` | hysteria2 客户端（apernet/hysteria v2.13.0 官方 release；**抗丢包通道**，被看门狗监管，见「Hysteria2 抗丢包通道」段） |
| `hysteria-client.yaml` | hysteria2 客户端配置：socks5 `127.0.0.1:7893` + Brutal 带宽参数 |
| `config.yaml` | 全部配置：节点 / 规则 / DNS / TUN（明文，可审计、可被 agent 直接修改） |
| `geosite.dat` / `geoip.metadb` | 分流规则数据库（MetaCubeX meta-rules-dat） |
| `ui/` | zashboard 开源面板（Zephyruso/zashboard） |
| `ui-patch/` | zashboard 补丁：Mac 监控面板（`apply-ui-patch.ps1` 幂等注入，`download-assets.ps1` 自动应用） |
| `config.ps1` | 端口与 API 密钥 |
| `start.ps1` | 一键启动（内核 + 系统代理 + 校验），`一键启动.cmd` 为其包装 |
| `mihomo-run.ps1` | 看门狗（保活 + 停止请求处理 + 自动选路 + 全挂降级 DIRECT），由计划任务以最高权限运行 |
| `wproxy.ps1` | 控制台 CLI（安装/启停/状态/热重载/模式切换/选节点/测延迟） |
| `logs/` | 看门狗与内核日志 |

## 快速开始

```powershell
cd C:\Users\22771\Desktop\项目\proxy-stack\wproxy

# 【最常用】双击 一键启动.cmd，或执行：
.\start.ps1
#   = 启动内核 + 接管系统代理 + 校验（代理没真接管就红字报错，不会假报成功）

# 查看状态（任务/进程/端口/系统代理/TUN/出口IP/当前节点）
.\wproxy.ps1 -Action status
```

首次使用需先注册自启计划任务（弹一次 UAC，仅此一次）：

```powershell
.\wproxy.ps1 -Action install
```

一键启动会做四步并逐步校验：① 注册/检查计划任务 → ② 启动内核并等待 `127.0.0.1:7897` LISTEN
→ ③ 切换到 sysproxy 模式 → ④ 校验系统代理已接管、`wproxy` 虚拟网卡不存在、出口 IP 可达。
任何一步失败都会红字退出并给出原因。

## 模式切换（系统代理 / TUN）

```powershell
.\wproxy.ps1 -Action mode -Value sysproxy   # 浏览器等走系统代理；指向 127.0.0.1:7897
.\wproxy.ps1 -Action mode -Value tun        # TUN 全局 —— 当前不可用，见下方警告
.\wproxy.ps1 -Action mode -Value off        # 全部关闭
```

> **[2026-10-11] TUN 模式当前不可用，请只用 `sysproxy`。**
> 本机上 mihomo 的 TUN 与 Tailscale 无法共存：TUN 一开，mihomo 出站到 `100.105.59.31:7891`
> 直接报 `WSAENETUNREACH`（系统里找不到到该地址的路由），连带 `dns-hijack: any:53` 下的
> 全部 DNS 解析失败，整机表现为断网。Tailscale 在 Windows 上只为每个 peer 装 `/32` 路由
> （不装 `100.64.0.0/10`），`route-exclude-address` 排除后反而让 Windows 无路可走。
> 本机已因此断网三次，`tun.enable` 固定保持 `false`（与 git 首次提交一致）。
>
> 另外 Windows 系统代理（WinINET）本身就不承载 UDP，所以 `sysproxy` 模式下 UDP 一律不走代理。

## 常用命令

```powershell
.\wproxy.ps1 -Action select -Value "Japan-MacMini"   # 切换节点
.\wproxy.ps1 -Action delay                           # 测节点延迟
.\wproxy.ps1 -Action reload                          # 改 config.yaml 后热重载（秒生效）
.\wproxy.ps1 -Action ui                              # 打开 WebUI
.\wproxy.ps1 -Action stop / start / restart          # 停止 / 启动 / 重启（无需管理员）
.\wproxy.ps1 -Action uninstall                       # 卸载
```

## 出口节点与局域网共享（2026-10-07 更新）

PROXY 组可选节点：

| 节点 | 地址 | 说明 |
| --- | --- | --- |
| `Japan-PhoneRelay` | socks5 `100.105.59.31:7891`（经 `PhoneEntry`） | **当前最快（实测 138–150ms）**：手机 CMFA 中继（PC → 手机混合口 → CMFA tsnode 直连 Mac） |
| `Japan-PhoneRelay2` | socks5 `100.105.59.31:7891`（经 `PhoneSocks`） | 备用中继：手机 Termux userspace tailscale + `tailscale serve` 暴露 SOCKS（需 Termux 常驻 + `--socks5-server`） |
| `PhoneEntry` | socks5 `192.168.137.104:7890` | 手机 CMFA 混合端口（热点 DHCP IP，可能变化）；仅作 dialer-proxy，不直接选 |
| `PhoneSocks` | socks5 `100.109.145.14:18080` | 手机 Termux `tailscale serve --tcp=18080`；仅作 dialer-proxy |
| `Japan-MacHy2` | socks5 `127.0.0.1:7893` | **抗丢包通道（2026-10-18）**：hysteria2/Brutal（独立客户端 → tailnet → Mac 侧服务端），出口劣化时最快（实测 820KB/s）；自动切换兜底 #1 |
| `Japan-MacMini` | socks5 `100.105.59.31:7891` | Mac socks-relay（经 Tailscale，直连/中继自动）；出口正常时可用、劣化夜单流塌到几十 KB/s；自动切换兜底 #2 |
| `Japan-Ngrok` | socks5 `127.0.0.1:1081` | 应急快路：Mac ngrok SSH 隧道 + `ssh -D`（独立于 Tailscale；免费版有流量限额，2026-10 额度已耗尽 `ERR_NGROK_725`） |
| `Japan-MacUser` | socks5 `100.86.50.115:7891` | Mac 上 kobayashi 账号的 userspace Tailscale 节点（`tailscale serve --tcp` 转发 7891，可随时重启） |
| `Japan-CF` | socks5 `127.0.0.1:7892` | Cloudflare Tunnel `socks.vibelink.cloud`（无限量；PC 侧 `cloudflared access tcp` 接入）；自动切换兜底 #3 |
| `Japan-MacMini-SSH` | socks5 `127.0.0.1:1080` | SSH 隧道备用（仅 TCP） |

## 分流规则（默认直连，需要外网的才代理）

`config.yaml` 的 `rules` 自上而下匹配，命中即止：

```yaml
rules:
  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve   # Tailscale 网段
  - GEOIP,private,DIRECT                       # 局域网
  - PROCESS-NAME,tailscaled.exe,DIRECT         # Tailscale 自身进程不走隧道
  - PROCESS-NAME,tailscale.exe,DIRECT
  - PROCESS-NAME,Telegram.exe,PROXY            # Telegram MTProto 直连 DC IP，域名规则匹配不到
  - IP-CIDR,91.108.4.0/22,PROXY,no-resolve     # Telegram 官方 DC 网段
  - IP-CIDR,91.108.8.0/22,PROXY,no-resolve
  - IP-CIDR,91.108.12.0/22,PROXY,no-resolve
  - IP-CIDR,91.108.16.0/22,PROXY,no-resolve
  - IP-CIDR,91.108.20.0/22,PROXY,no-resolve
  - IP-CIDR,91.108.56.0/22,PROXY,no-resolve
  - IP-CIDR,149.154.160.0/20,PROXY,no-resolve
  - IP-CIDR,185.76.151.0/24,PROXY,no-resolve
  - DOMAIN-SUFFIX,tailscale.com,PROXY         # 控制台域名被 DNS 污染成 CN IP，必须走代理
  - DOMAIN-SUFFIX,ev.phncdn.com,PORN-VIDEO     # Pornhub 视频/动图走 CF 隧道
  - DOMAIN-SUFFIX,el.phncdn.com,PORN-VIDEO
  - DOMAIN-SUFFIX,el2.phncdn.com,PORN-VIDEO
  - DOMAIN,static.loverslab.com,DIRECT         # LoversLab 静态资源直连
  - DOMAIN,www.loverslab.com,PROXY             # 主站/下载走代理（直连大文件会塌到 KB/s）
  - DOMAIN-SUFFIX,loverslab.com,DIRECT         # 其余子域直连
  - DOMAIN-SUFFIX,challenges.cloudflare.com,DIRECT  # CF 人机验证脚本，直连避免卡验证
  - GEOSITE,geolocation-!cn,PROXY              # 非中国大陆域名（27217 条）
  - MATCH,DIRECT                               # 默认直连
```

**2026-10-11 改策略**：原来是 `GEOSITE,cn,DIRECT` + `GEOIP,CN,DIRECT` + `MATCH,PROXY`（国内直连、其余全走代理），
现在**兜底反过来是 `MATCH,DIRECT`**，只有 `geolocation-!cn` 收录的域名才走代理 —— 未收录的冷门站默认直连，
不再白耗跨境带宽（实测经 Mac 的隧道单流仅约 100KB/s）。手机端 `android-phone/cmfa-config.yaml` 规则同步。

配套的 DNS 改动：

- `dns.fallback-filter` 去掉 `geoip: true` / `geoip-code: CN`，只留 `geosite: [gfw]` + `ipcidr: 240.0.0.0/4`。
  以前任何解析到非 CN IP 的域名都会被丢给钉在 `#PROXY` 上的境外解析器，代理一挂 DNS 就整体卡死；
  现在只有真正被墙的域名才去碰它们，Steam / OneDrive / Microsoft 直接由 doh.pub、alidns 解析。
- `dns.nameserver` 现在是 `https://1.12.12.12/dns-query`、`https://doh.pub/dns-query`（腾讯 DoH）。
  原来排第一的 AliDNS 对 LoversLab 返回不可达 IP（202.160.x / Dropbox / Facebook 等污染地址），
  换腾讯 DoH 后返回真实 Cloudflare IP；仍用 IP 字面量，避免 `dns-hijack: any:53` 下域名形式 DoH 的自举死锁。
- ~~新增顶层 `interface-name: "WLAN"`~~（**2026-10-24 已移除**）：本机已改用有线（`以太网 192.168.86.197`），
  WLAN 断开后该绑定会把 mihomo 的 DIRECT/DNS 出站发到死网卡（169.254.x），表现为「开代理就国内站全挂」。
  现在不绑定网卡、由系统路由表选出口；`tun.auto-detect-interface` 仍保持 `false`（TUN 关闭，此项只影响 TUN）。
  代价：若将来换成「多网卡同时在线且默认路由不明确」的环境，需要重新评估是否钉回具体网卡名。

### LoversLab 分流（2026-10-22，当晚二次调整）

`www.loverslab.com` 实际在 Cloudflare（真实 A 记录 `104.26.0.96` / `104.26.1.96` / `172.67.71.146`），
但成都侧 DNS 被污染成 Dropbox / Facebook / 主机商 IP，直连全部超时；改走日本隧道又要经过
Tailscale DERP(tok)，单流仅 ~37KB/s，TTFB 19s。

第一轮修复（直连）：

- `DOMAIN-SUFFIX,loverslab.com,DIRECT` + `DOMAIN-SUFFIX,challenges.cloudflare.com,DIRECT`（放在 GEOSITE 之前）
- `dns.nameserver` 改用腾讯 DoH，拿到真实 Cloudflare IP；`tcp-concurrent` 会在三个 IP 中选最快
- 实测：`curl` 经 mihomo 到分类页 TTFB **0.41s**（修复前 19s）；Chrome 首屏 `static.loverslab.com` 450KB 直接完成
- 注意：`brunhild.challenges.cloudflare.com` 只有 AAAA（IPv6），本机 `ipv6: false` 时解析不到；
  它只是 Turnstile 的辅助子域，不影响主验证（主脚本 `challenges.cloudflare.com` 已直连）

第二轮调整（大文件下载）：

- 现象：页面能开，但 36.4MB 的 mod 下载在 DIRECT 连接上稳定在 **1–2KB/s**（Chrome 预估 6 小时）；
  同一条链路用 `curl` 测 `speed.cloudflare.com` 仍有 18–26KB/s，说明是长连接在丢包下塌掉，而非站点限速
- 改法：`DOMAIN,www.loverslab.com,PROXY`（含下载），`DOMAIN,static.loverslab.com,DIRECT`（静态资源），
  其余 `loverslab.com` 子域仍直连；`challenges.cloudflare.com` 保持直连
- 实测：`speed.cloudflare.com` 经 `Japan-MacHy2` 30 秒持续 **480KB/s**（同期直连 18–26KB/s）；
  页面 TTFB 约 1.2–2.8s，但整页 1MB+ 的传输反而比直连快
- 注意：切换出口 IP 后旧的 `cf_clearance` 可能失效，浏览器或需重新过一次 Cloudflare 验证；
  旧的下载连接不会自动改道，**暂停/继续仍复用旧连接**，须取消后重新点下载

### Pornhub 视频分流（2026-10-23）

`cn.pornhub.com` 命中 `GEOSITE,geolocation-!cn → PROXY`（默认 `Japan-MacHy2`）。页面 HTML 尚可
（TTFB 2.2–2.9s），但视频媒体 `ev.phncdn.com` 在 MacHy2 上实测只有 **4.7KB/s**（20s 仅 94KB），
完全无法播放；同一签名 URL 经 `Japan-CF` 为 **437–525KB/s**（15s 7.3MB），相差约 100 倍。

- 新增 `PORN-VIDEO` 回落组：`Japan-CF` 优先、`Japan-MacHy2` 兜底，每 30s 健康检查
- 规则只把 `ev/el/el2.phncdn.com`（视频/动图）切到 `PORN-VIDEO`；`cn.pornhub.com` 与
  `ei/ew/pix-*.phncdn.com`（页面与缩略图）仍走 `PROXY` —— 小图经 MacHy2 TTFB 1.7–2.4s，
  经 CF 反而要 3.1–8.3s
- `Japan-CF` 走 `cloudflared access tcp --hostname socks.vibelink.cloud --url 127.0.0.1:7892`，
  出口同为 Mac `130.62.209.98`，因此 Pornhub 按 IP 签名的媒体 URL 在两条链路上都有效
- 看门狗新增 cloudflared 监管（7892 未监听时自动拉起，`stop.request` 时一并停止）
- 实测 `speed.cloudflare.com`：CF 隧道 **1.9MB/s**，MacHy2 **240KB/s**；页面 HTML 的 TTFB
  两者接近（CF 约 2.3–4s，MacHy2 1.2–2.9s），故只把大流量切 CF
- 注意：已打开的 Chrome 连接不会自动改道，刷新页面/重新打开播放器才会走新规则

### Telegram 加载（2026-10-23）

Telegram Desktop 虽然走系统代理连 `127.0.0.1:7897`，但 MTProto 的目标是**裸 DC IP**
（`149.154.x.x` / `91.108.x.x`），`GEOSITE` 只匹配域名，规则落到 `MATCH,DIRECT` 后全部
`i/o timeout`，表现为一直“连接中/加载不出”。

- 新增 `PROCESS-NAME,Telegram.exe,PROXY`（最稳，覆盖所有 DC IP）
- 同时补 Telegram 官方 CIDR 段 → `PROXY`，供浏览器版/其他客户端按 IP 连接时使用
- 实测规则生效后 7 条连接全部 `Japan-CF>PROXY`，单连接已下载 0.6–0.9MB，客户端恢复同步
- 注意：语音/视频通话依赖 UDP；当前通道以 TCP 为主，通话可能仍不可用

### Cloudflare 优选 IP（2026-10-23）

排查 commandcode.ai 打不开时发现：本机到**部分 CF 任播段**会绕去 LAX，换成另一段落在 HKG 的
CF IP 后差别是数量级：

| 目标 IP | 落地 | RTT | ICMP 丢包 | TLS 握手 |
|---|---|---|---|---|
| `104.21.49.202`（DNS 默认返回） | LAX | 325ms | 66% | 6–12s，约 1/3 直接失败 |
| `162.159.140.245` | HKG | 137ms | 16% | 0.3s |

- `hosts` 增加优选：`commandcode.ai` / `api.commandcode.ai` / `socks.vibelink.cloud` → `162.159.140.245`
  （`dns.use-hosts` 已开；`commandcode.ai` 本就落 `MATCH,DIRECT`，优选后 TTFB 从 7–12s 降到 0.7s）
- 显式规则 `DOMAIN,socks.vibelink.cloud,DIRECT`，保证 CF 隧道入口命中优选 IP，而不是被
  `GEOSITE,geolocation-!cn` 送进 `PROXY`（那会绕回隧道本身）
- `cloudflared access` 不读 mihomo 的 DNS：在 `mihomo-run.ps1` 里给它注入
  `HTTPS_PROXY=http://127.0.0.1:7897`，让它经 mihomo 出站，才能吃到 `hosts` 优选
- 实测 `Japan-CF` 从「8s 延迟测试超时、基本不可用」恢复为 **TLS 2–3s、持续 260KB/s**（5MB / 19s），
  已把 `PROXY` 默认节点切到 `Japan-CF`（同期 hy2 只有 4–16KB/s）
- ⚠️ 优选 IP 是临时手段：CF 任播调度会变。若某天 `162.159.140.245` 变慢或握手失败，
  换同段其它 IP（如 `162.159.141.1`、`188.114.96.1`）复测后再改 `hosts`
- ⚠️ `mihomo-run.ps1` 的改动要等 WProxy 计划任务重启（管理员）后才对看门狗生效；
  当前手工拉起的 cloudflared 已带 `HTTPS_PROXY`

## Hysteria2 抗丢包通道（Japan-MacHy2，2026-10-18）

校园网国际出口劣化时（丢包 ~10%、延迟抖动到秒级），普通 TCP 经 Mac 单流会塌到 25–40KB/s（10% 丢包下 CUBIC 吞吐就这个量级）。为此新增 hysteria2 通道（QUIC + **Brutal 拥塞控制，不随丢包退缩**）：

```
PC mihomo ── socks5 127.0.0.1:7893 ── hysteria 客户端（wproxy/hysteria.exe，看门狗监管）
    └─ tailnet UDP 100.105.59.31:8443 ── Mac 侧 python 中转（com.udp-relay，绕 ALF）
        └─ 127.0.0.1:8444 hysteria 服务端（com.hysteria2）── Mac 出口 130.62.209.98
```

- **为什么加中转**：macOS 应用防火墙（ALF）开启时会**静默拦截无签名二进制**（hysteria）的入站 UDP；python3 已被放行，故由 `udp-relay.py` 在 tailnet 侧收包、按来源做 NAT 映射转发到本地 8444。日后若在 Mac 执行 `sudo socketfilterfw --add ~/bin/hysteria && sudo socketfilterfw --unblockapp ~/bin/hysteria`，可去掉中转（hysteria 监听改回 `100.105.59.31:8443` 并卸载 `com.udp-relay`）
- **为什么不用 mihomo 内置 hysteria2 出站**：其 UDP 套接字受顶层 `interface-name: "WLAN"` 影响，包出不了 Tailscale 网卡（节点级覆盖无效），故采用独立客户端 + socks5 节点方案；`config.yaml` 里 `Japan-MacHy2` 保持 `type: socks5`
- **实测（2026-10-18 当晚出口劣化时）**：820KB/s（≈6.6Mbps，改造前 ~25KB/s）；节点延迟 383ms；出口 `130.62.209.98`
- **带宽参数**：`wproxy/hysteria-client.yaml` 的 `bandwidth.up/down`（当前 2/8Mbps，实测 down=8 得 6.6Mbps）。Brutal 不按丢包退让，调高需留意校园网出口承载
- **Mac 侧常驻**：`com.hysteria2`（服务端）+ `com.udp-relay`（中转）均为 LaunchAgent（KeepAlive）；PC 侧由看门狗一并监管

## 代理容错（mihomo-run.ps1 自动选路）

主节点 `Japan-PhoneRelay`，兜底依次 `Japan-MacHy2` → `Japan-PhoneRelay2` → `Japan-MacMini` → `Japan-CF`，
每 30s 探测一次，连续 2 次失败才切换，主节点连续 2 次恢复自动切回。

**2026-10-24 新增「活着但慢」判定（重要）**：原逻辑只在节点**探测失败**时切换，节点「延迟能过但吞吐塌陷」
时会一直留在坏节点上 —— 10-23 夜 `Japan-MacMini` 就是这种状态（探测 159–2000ms 抖动、实测吞吐 8–25KB/s），
系统代理开着时表现和断网一样。现在每次检查额外做降级判定：

- **延迟**：≥ `HardSlowDelayMs`（1200ms）直接记为降级；
- **吞吐**：每 2 次检查（约 60s）经本机 `7897` 下载一个 256KB 样本（`speed.cloudflare.com`，约 7MB/h 后台流量），
  实测 < `MinThroughputKBps`（60KB/s）记为降级；延迟 ≥ 800ms 时每次检查都测；
- **切换条件**：连续 2 次降级，且候选兜底节点延迟 ≤ 当前的一半（吞吐降级时放宽为 ≤ 800ms），避免在几个慢节点之间来回跳；
- **启动即判**：看门狗启动时把慢计数预置为 1，第一次探测到降级就切，不再等 60s。

日志示例：

```
[auto] current Japan-MacMini degraded: throughput 17KB/s (2/2)
[auto] Japan-MacMini -> Japan-MacHy2 (152ms; was throughput 17KB/s)
```

局限：只测到 `speed.cloudflare.com` 的单流表现，且 `Select-AliveFallback` 里失败节点是串行探测的，
一次切换最坏多花 ~15s（PhoneRelay / PhoneRelay2 / CF 都不可达时）。

**2026-10-18 新增**：看门狗同时监管 hysteria 客户端（`hysteria.exe` 未运行或 `7893` 未监听时自动拉起，`stop.request` 时一并停止），MacHy2 具备自愈能力。

**2026-10-23 新增**：看门狗同时监管 `cloudflared access`（`7892` 未监听时自动拉起，`stop.request` 时一并停止），`Japan-CF` 从「手动备胎」变为可自愈节点；视频/大文件分流依赖该通道。

**2026-10-11 新增**：所有节点都不可达时，PROXY 组自动降级为 `DIRECT`，
日志打 `[auto] primary ... down and no fallback reachable; fail-safe -> DIRECT`。
效果是代理全挂时境外流量降级直连而不是集体报错 —— 实测该状态下 Steam、GitHub、Tailscale 控制台均正常，
只有真正被墙的 google 失败（DNS 能解析，连接被墙）。

手动选择管理列表之外的节点（如 `Japan-Ngrok`）会暂停自动切换，视为用户意图。
决策日志在 `logs/watchdog-*.log` 的 `[auto]` 行；重启看门狗即生效：`.\wproxy.ps1 -Action restart`。

### 局域网共享

- 本机 `7897` 仅监听 `127.0.0.1`（免认证，供本机应用/浏览器）；局域网设备用 `192.168.86.20:7898`（mixed，带认证，账号见 `config.yaml` 的 `listeners.users`）
- 注意：mihomo 的 `authentication` 会连本机请求一起拦（曾导致浏览器 `ERR_TUNNEL_CONNECTION_FAILED`），所以本机口与共享口分开

## 从 CuteCloud 迁移（已完成，2026-10-06）

- 系统代理已切换至本方案（`mode -Value sysproxy` → 127.0.0.1:7897），验证出口 = 日本 Mac 后卸载了 CuteCloud
- 卸载内容：3 个官方卸载器静默执行、`CuteCloudHelperService` 服务删除、`C:\Program Files\CuteCloud` 与用户数据残留（`%APPDATA%\cutecloud`、`%LOCALAPPDATA%\CuteCloud`、`cutecloud-updater`）全部清除
- 如需回退：重新安装 CuteCloud 并开启其系统代理（不推荐；TUN/虚拟网卡勿与本方案同时开）

## 监控页（侧边栏 tab，2026-10-06）

WebUI 侧边栏「设置」上方新增「**监控**」tab（移动端底部 tab 栏同步），点击进入全屏监控页：

- 设备卡片网格：每台设备一张卡（在线状态、CPU / GPU / 内存 / 硬盘 / 实时带宽（↓↑）/ 延迟 / 负载（或队列）/ 电量（安卓）/ 运行时长），CPU/GPU/内存/带宽带迷你曲线；**离线设备只置灰（红点 + 内容淡化 + “离线”），卡片不隐藏、顺序固定**
- 延迟行注意（2026-10-07，四端统一口径）：`直连国内` = 223.5.5.5:443 TCP；`代理链路` = 到目标的真实请求耗时（绿≤0.8s / 黄≤1.5s / 红>1.5s；实测 PhoneRelay ≈ 0.6s / MacMini ≈ 1.75s / Ngrok ≈ 0.4s）。网状网行已移除——Tailscale 直连/中继与代理性能无关（PC↔Mac 无法直连是校园网对称 NAT 的物理限制，代理走手机中继不受影响）
- 内存为 **Activity Monitor 口径**（Mac：App + Wired + Compressed；Windows：已用/可用/缓存/Swap），点击卡片「内存」行展开明细
- 数据源：`metrics-hub` 项目（`http://127.0.0.1:18993/devices`）——Hub 自采集 + 收第二台 Windows/安卓 push + 拉取 Mac 指标代理
- 实现：`ui-patch/mac-monitor.js`（无侵入注入导航项与全屏页，MutationObserver 自愈，不修改 zashboard 源码）+ `ui-patch/apply-ui-patch.ps1`（注入 index.html、替换 sw.js 为自毁版以清掉 zashboard PWA 缓存）
- 交互：点其它导航（或手机底部 tab）即退出监控页；刷新页面保持上次状态

## 界面语言（简体中文）

`ui-patch/lang-zh-cn.js` 在应用初始化前把 zashboard 语言一次性设为简体中文（写入 `localStorage['config/language'] = 'zh-CN'`，带标记位，仅首次强制；之后可在 设置 → 通用 → 语言 自由更改，不会被再次覆盖）。由 `apply-ui-patch.ps1` 与监控面板一并注入。

## 验证记录

**2026-10-24 凌晨（「开代理就断网」修复 + 看门狗降级切换）**

- 现象：代理启动后系统代理已接管，但境外站几乎全挂（`gstatic` 502、`speed.cloudflare.com` 18KB/s），
  用户关掉系统代理才恢复；当时 PROXY 组停留在 `Japan-MacMini`
- 根因：mihomo 从 `store-selected` 缓存恢复上次选择 = `Japan-MacMini`，而该节点实测吞吐仅 **8–25KB/s**（延迟却只有 159–2000ms），
  看门狗只在「探测失败」时切换，慢节点永远不会被换掉
- 另发现 `config.yaml` 顶层 `interface-name: "WLAN"` 与本机现状不符（已改用有线，WLAN 是 169.254 断连状态），已注释移除
- ✅ 新增吞吐降级判定（见「代理容错」），实机验证：手动切到 `Japan-MacMini` 后 30s 内探测到 `throughput 17KB/s`，再 ~17s 切回 `Japan-MacHy2`
- ✅ 节点实测：MacHy2 157–682ms / **642–913KB/s**；MacMini 184–2079ms / **4.7–23KB/s**；PhoneRelay 与 PhoneRelay2 因本机改走有线（接不到手机热点）不可达
- ✅ 稳定期 3 分钟 × 每 15s（google / github / 百度各 36 次）：**0 失败**，google 0.55–1.51s、github 0.51–1.52s、国内直连 0.11–0.22s，出口 `130.62.209.98`
- ✅ Mac 侧 `com.cloudflared-tunnel` 自 22:18 起失去 edge 连接（`lookup region1.v2.argotunnel.com: i/o timeout`），
  `launchctl kickstart -k gui/$(id -u)/com.cloudflared-tunnel` 后重新注册 4 条 kix 连接；但 `Japan-CF` 从 PC 侧仍只有 4–5.5s TTFB 且频繁超时，暂不可用
- ℹ️ 若再次出现「代理开着但所有站都很慢」，先看 `logs/watchdog-*.log` 的 `[auto]` 行，再 `wproxy.ps1 -Action select -Value Japan-MacHy2`

**2026-10-23 凌晨（Telegram 恢复加载）**

- 现象：Telegram Desktop 一直“连接中/加载不出”；日志显示 MTProto 直连 DC IP `149.154.175.x:443/80` 命中 `MATCH,DIRECT`，全部 `i/o timeout`
- 原因：Telegram 走系统代理连 7897，但 MTProto 目标是裸 IP，`GEOSITE` 域名规则匹配不到
- ✅ 新增 `PROCESS-NAME,Telegram.exe,PROXY` + Telegram 官方 CIDR（`91.108.4.0/22` … `149.154.160.0/20` …）
- ✅ 规则生效后 7 条连接全部 `Japan-CF>PROXY`，单连接下载 0.6–0.9MB，客户端恢复同步
- ℹ️ 语音/视频通话依赖 UDP，当前通道以 TCP 为主，可能仍不可用

**2026-10-23 凌晨（Pornhub 视频改走 CF 隧道）**

- 现象：`cn.pornhub.com` 页面能开（HTML TTFB 2.2–2.9s），但视频 `ev.phncdn.com` 经 `Japan-MacHy2` 20s 只收到 **94KB（4.7KB/s）**，无法播放
- 对照：同一签名 URL 经 `Japan-CF` **437–525KB/s**（15s 7.3MB）；`speed.cloudflare.com` 30s：CF **1.9MB/s**、MacHy2 **240KB/s**
- ✅ 新增 `PORN-VIDEO` 回落组（CF 优先 / MacHy2 兜底），规则 `ev|el|el2.phncdn.com → PORN-VIDEO`
- ✅ 页面与小图仍走 MacHy2：24 张 `ei.phncdn.com` 并行加载 6.88s（CF 8.96s），单图 TTFB 1.7–2.4s（CF 3.1–8.3s）
- ✅ 看门狗接管 `cloudflared access`（7892），重启后自动拉起；`PORN-VIDEO` 健康检查 30s，启动竞态后约 30s 内切回 CF
- ℹ️ 已打开的 Chrome 连接不会自动改道，需刷新页面/重开播放器

**2026-10-22 晚（LoversLab 大文件下载改走代理）**

- 现象：页面已能打开，但 36.4MB 的 `Court_of_Desires_CBO_Patch_0.9.2-beta.zip` 在 DIRECT 连接上仅 **1–2KB/s**（Chrome 预估 6 小时）；32 分钟只完成 5.3MB
- 对照：同一时段 `speed.cloudflare.com` 直连 30s 为 18–26KB/s，经 `Japan-MacHy2` 为 **480KB/s（14.4MB/30s）**，确认是长连接在丢包下塌掉
- ✅ 规则改为 `www.loverslab.com → PROXY`、`static.loverslab.com → DIRECT`，其余 `loverslab.com` 子域仍 DIRECT，`challenges.cloudflare.com` 保持 DIRECT
- ✅ 取消旧任务后重新下载：**36.39MB 用时约 60s（≈454KB/s）**，zip 70 个条目校验通过；连接链 `Japan-MacHy2>PROXY`
- ℹ️ 页面 TTFB 由直连 0.44s 变为代理 1.2–2.8s，但整页传输更快；**暂停/继续会复用旧连接**，必须取消后重新点下载才会改道

**2026-10-22（LoversLab 直连修复）**

- 背景：`www.loverslab.com` 经日本隧道 TTFB ≈ 19s，CF 质询长期转圈；排查为成都校园网 DNS 污染（AliDNS 返回不可达 IP）+ 跨境隧道单流慢
- ✅ DNS 改为纯腾讯 DoH（`1.12.12.12` / `doh.pub`）后解析出真实 Cloudflare 记录 `104.26.0.96 / 104.26.1.96 / 172.67.71.146`，无需 hosts 钉 IP
- ✅ 新增 `DOMAIN-SUFFIX,loverslab.com,DIRECT` 与 `DOMAIN-SUFFIX,challenges.cloudflare.com,DIRECT`（置于 GEOSITE 规则之前）
- ✅ `curl` 经 mihomo TTFB **0.41–0.46s**（修复前 19s）；真实 Chrome 加载 `www.loverslab.com` **1.16MB** + `static.loverslab.com` 31KB，全部走 DIRECT，Turnstile 通过
- ℹ️ 其余境外站点仍偏慢属校园网国际出口劣化（单流 ~37KB/s），与本次改动无关

**2026-10-18（校园网国际出口劣化当晚，Hysteria2 通道上线）**

- 背景：当晚直连 v4 基本不通、v6 仅 25–40KB/s；隧道内 ICMP 丢包 10%、延迟 195–1761ms；经 Mac 单流塌到 ~25KB/s（Mac 本机经自己 socks 9.8MB/s，Mac 侧健康）；ngrok 免费额度当月耗尽（`ERR_NGROK_725`）无法备用
- ✅ 新通道 `Japan-MacHy2` 经 mihomo 实测 **820KB/s（≈6.6Mbps，此前 25KB/s，×32）**，20MB 全量下载 <25s；出口 `130.62.209.98`；`youtube.com/generate_204` 204/0.97s；节点延迟 383ms
- ✅ Mac 侧 launchd 常驻（`com.hysteria2` + `com.udp-relay`，KeepAlive 托管）；PC 侧看门狗监管 hysteria（自动拉起/随停）
- ✅ 自动切换顺序更新为 `MacHy2 → PhoneRelay2 → MacMini → CF`（手动选中 MacHy2 后重启整套，节点选择正确恢复且出口校验通过）

**2026-10-11（分流策略改版后复测，sysproxy 模式）**

- ✅ 规则：`bilibili / taobao / jd / zhihu / qq` → `match Match → DIRECT`（0.06–0.52s）；
  `google / github / youtube / steam` → `match GeoSite(geolocation-!cn) → PROXY[Japan-MacMini]`（1.2–4.6s）；
  `tailscale.com` → `match DomainSuffix → PROXY`。全部经系统代理实测 HTTP 200
- ✅ 兜底：PROXY 组切 `DIRECT` 后 Steam 0.67s、GitHub 0.24s、Tailscale 控制台 0.51s 均正常；
  仅 google 失败，且日志显示 `dial tcp 142.251.x.x:443: i/o timeout` —— DNS 解析成功，纯被墙
- ✅ DNS 解耦生效：DIRECT 窗口内 `mihomo --> 8.8.8.8 / 1.1.1.1` 零请求
- ✅ 一键启动脚本 `start.ps1` 带校验：系统代理已接管 + 无 wproxy 虚拟网卡 + 出口 IP 校验，三项全过才报 OK
- ✅ 出口：`130.62.209.98`（日本）
- ❌ **UDP 不通**：`one.one.one.one:53` 经代理 20s 超时（对照组 `223.5.5.5:53` 直连 26ms/43 字节）。
  SOCKS5 握手阶段 UDP ASSOCIATE 被接受（返回中继端点 `127.0.0.1:7897`），但数据包无回包 → 问题在 Mac 侧 `socks-relay.py`
- ❌ **TUN 不可用**：与 Tailscale 冲突，`WSAENETUNREACH`，连带 DNS 全挂，详见「模式切换」段的警告

<details>
<summary>2026-10-06 ~ 10-07 历史记录</summary>

- ✅ 出口：TCP/UDP 均日本 `130.62.209.98`（**UDP 一项已于 2026-10-11 复测推翻**）
- ✅ 规则：`bilibili.com / qq.com / tencent.com` → DIRECT（0.2s）；`google.com / youtube.com` → PROXY[Japan-MacMini]
- ✅ TUN：全局模式谷歌直连 200（**2026-10-11 复测已不通**；当时的 tailscaled 环境与现在不同）
- ✅ WebUI：http://127.0.0.1:9097/ui/ 正常
- ✅ 保活：看门狗互斥运行；stop 通过 `stop.request` 机制由提权看门狗执行（普通权限可停）
- ✅ 2026-10-06：系统代理接管验证（ProxyEnable=1 → 127.0.0.1:7897，系统级出口 `130.62.209.98`）；CuteCloud 卸载后出口不变
- ✅ 2026-10-06：Mac 监控面板——agent JSON 从 Windows 实测可取（cpu/gpu/mem/disk/net/latency 全字段）；mihomo `/ui/` 已含补丁，`/ui/mac-monitor.js` 200
- ✅ 2026-10-07：手机 CMFA 中继上线——`Japan-PhoneRelay` 延迟 138–150ms（对比 ngrok 187ms / CF 321ms / MacMini 445ms），出口 `130.62.209.98`；自动切换实测通过

</details>

## 已知注意点

- **TUN 与 Tailscale 冲突（2026-10-11）**：详见「模式切换」段。`tun.enable` 保持 `false`；曾三次导致整机断网
- **UDP 不通（2026-10-11）**：Mac 侧 `socks-relay.py` 的 UDP ASSOCIATE 分支有问题，见 macos-udp-proxy 项目文档
- 节点已内置 `interface-name: "Tailscale"`（否则 mihomo 出站会被绑到物理网卡、连不上 Tailscale 网段节点）；顶层另有 `interface-name: "WLAN"` 作为全局出口，两者不冲突（节点级优先；**hysteria2 出站例外，见下**）
- **mihomo 内置 hysteria2 出站不出包（2026-10-18）**：顶层 `interface-name: "WLAN"` 会绑住 hysteria2 出站的 UDP 套接字（节点级 `interface-name` 覆盖无效），包到不了 Tailscale 网段且无任何报错。现方案为独立客户端（`hysteria.exe`）+ socks5 节点，`config.yaml` 里勿改回 `type: hysteria2`
- **macOS ALF 拦无签名二进制（2026-10-18）**：Mac 的应用防火墙（ALF，State=1）会静默丢弃无签名进程（hysteria）的入站 UDP（Mac 服务端零日志、客户端 5s 超时）。绕过：python3 中转 `~/bin/udp-relay.py`（`com.udp-relay`，8443→127.0.0.1:8444）；彻底解法是给 hysteria 加 ALF 白名单（需 sudo，见「Hysteria2 抗丢包通道」段）
- **`Japan-CF` 依赖 cloudflared（2026-10-23）**：需要 PC 侧 `cloudflared access tcp --hostname socks.vibelink.cloud --url 127.0.0.1:7892` 常驻（已由看门狗监管）。该通道出口同为 Mac `130.62.209.98`，实测大文件 **1.9MB/s**，但小请求 TTFB 比 MacHy2 高约 1s，故只用于视频/大文件，不替代 `PROXY` 默认节点
- **Mac 侧隧道会「进程活着但连接掉光」（2026-10-24）**：`com.cloudflared-tunnel` 的 cloudflared 进程一直在跑，但 edge 连接断掉后不会自愈，只会每 5 分钟刷
  `Failed to refresh DNS local resolver ... i/o timeout`；此时 PC 侧日志为 `failed to connect to origin error=EOF`。恢复：Mac 上
  `launchctl kickstart -k gui/$(id -u)/com.cloudflared-tunnel`，约 5s 内重新 `Registered tunnel connection`（kix03–06）
- **Tailscale 直连与 IPv6（2026-10-06 排查，2026-10-11 复核）**：本机↔Mac 的直连走 **IPv6**（无 NAT，实测 124ms，endpoint `2409:251:...:41641`）；IPv4 无法直连——校园网是硬 NAT（`MappingVariesByDestIP: true`）+ Mac 侧 transix 拒绝未请求入站 UDP。若 IPv6 不可用会回落 DERP 中继（~300ms+）
- Tailscale 网卡 InterfaceMetric=5，比 WLAN 的 40 优先级更高，这是 `auto-detect-interface` 必须关掉的原因
- ⚠️ 本机 `C:\ProgramData\Tailscale\tailscaled-env.txt`（`PORT=43210`）已丢失，tailscaled 现跑默认 41641。若再次出现端点发现异常，需重新写回该文件并重启 Tailscale 服务
- DNS fallback 仍钉在 `#PROXY`（经代理查询 1.1.1.1/8.8.8.8），但只有 `geosite: gfw` 里的域名会用到它
- `status` 停止/运行显示基于 API + 进程名（提权进程命令行普通权限读不到，属正常）
- `mode` / `select` / `reload` 命令现在失败会打印 `[!]` 并附上原因（2026-10-11 修复；此前 API 调用失败会被吞掉并照样打印 `[OK]`，曾导致误判 TUN 已开启）
- 看门狗固定 `Start-Sleep 3` 后探测 7897 端口，而 mihomo 加载 geosite 约需 4 秒，启动瞬间可能误判并多重启一次（无害，但日志里会看到一次 `mixed port is not listening; restarting`）
- uninstall 删除计划任务可能需要管理员权限（普通权限会提示）
- Tailscale 后台已加 `grants`（`tailscale.com/cap/relay` → win2）作为中继兜底；监控页「到 Mac」行会标注 `直连(IPv6) / 直连 / 中继(xxx) + 毫秒`
- DNS fallback 已配置为 `#PROXY`（经代理查询），避免 8.8.8.8 直连被墙超时
- `status` 停止/运行显示基于 API + 进程名（提权进程命令行普通权限读不到，属正常）
- uninstall 删除计划任务可能需要管理员权限（普通权限会提示）
- **手机中继前提（2026-10-07）**：CMFA → 覆寫（Override）页开启「允許來自區域網路的連線」+ 混合端口 7890（注意：不在「網路」页）；手机 WiFi 休眠会令中继抖动（Mac 侧曾见 `phone-mihomo-1 offline 5m`），建议 CMFA 关闭电池优化 + WLAN 休眠保持连接；热点 IP 变化后需更新 `config.yaml` 的 `PhoneEntry.server`
- **吞吐现状（2026-10-07 实测，2026-10-18 更新）**：经 Mac 的 TCP 隧道单流 ~100KB/s（出口劣化夜掉到 25–40KB/s），为跨境段丢包所致（Mac 本机下行 6.4MB/s、上行 10MB/s；PC 直连 Cloudflare 曾达 1.85MB/s 但波动大）——**劣化夜改走 `Japan-MacHy2`（hysteria2/Brutal）单流实测 820KB/s**，见「Hysteria2 抗丢包通道」段

## 来源与校验（SHA256）

```
mihomo v1.19.32 (windows-amd64)  1AC84E795B5B915446E20139677FB6CC013A0778E876E8D4C96F994750B4C4A3
zashboard v3.29.1 (dist-no-fonts) 21371CD111B6B3D87774F3EA3DDEACDCB0F6AFF8690A16D652DAA3EA6E3B0145
wintun 0.14.1                     07C256185D6EE3652E09FA55C0B673E2624B565E02C4B9091C79CA7D2F24EF51
hysteria v2.13.0 (macOS-arm64，ad-hoc 签名后)  ad3fd215356557d4abd120509ca51486e03218bbd078b7b5656c3bc830e3e889
hysteria v2.13.0 (windows-amd64)  162EF8FE55DC7EC810DDA662908F8E65EC36BD6DA39CB87C3BD66184AB68F067
```

## 依赖

- 节点侧：Mac 上的 socks-relay（100.105.59.31:7891，**仅 TCP 可靠**，UDP 分支待修，见 macos-udp-proxy 项目）
- **抗丢包通道（2026-10-18）**：Mac 侧 `~/bin/hysteria`（服务端，`com.hysteria2`，听 `127.0.0.1:8444`）+ `~/bin/udp-relay.py`（中转，`com.udp-relay`，听 `100.105.59.31:8443`），配置在 `~/.config/hysteria2/`；PC 侧 `wproxy/hysteria.exe` + `wproxy/hysteria-client.yaml`。来源：GitHub `apernet/hysteria` releases（darwin-arm64 / windows-amd64，PC 侧经 gh-proxy.com 镜像下载）
- 备用节点：SSH 隧道（127.0.0.1:1080，仅 TCP，见 macos-socks-proxy 项目）
- 手机中继：CMFA（mihomo tsnode）开启「允許來自區域網路」+ 混合端口 7890（PC 经热点 192.168.137.0/24 接入）；备用 Termux userspace tailscale（`tailscale serve --tcp=18080`）
- 监控面板：多设备聚合由 `metrics-hub` 提供（`127.0.0.1:18993/devices`，见 metrics-hub 项目）；Mac 端数据源为 macos-metrics 指标代理（100.105.59.31:18992）
