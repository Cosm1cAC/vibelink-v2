# AGENTS.md — 宿舍 PC 热点交接（PC 侧）

> 适用机器：PC `HAOSHAO`（用户 `22771`）。本文件优先级高于 `wproxy/README.md`
> 中历史的固定 IP 配置。动手前先读完；不确定就只读不动。
>
> **一句话策略：PC 是热点宿主，翻墙出口在笔记本。PC 的系统代理必须指向笔记本
> 当前 LAN IP 的 `7897`，由 `pc-proxy-failover.ps1` 自动发现。PC 本地 mihomo
> 只是最后兜底，不要主用，也不要为了“优化”去换它的节点。**

更新时间：2026-10-27（Asia/Shanghai）

## 0. 当前拓扑（先看这里）

```
PC HAOSHAO (22771)
  ├─ 以太网 192.168.86.197        → 校园网上行
  ├─ ICS 热点 192.168.137.1       → SSID "HAOSHAO 5189"（2.4GHz / 802.11n）
  └─ 系统代理 192.168.137.104:7897
                         │  (PC ↔ 笔记本 走热点 LAN，约 3–13ms)
笔记本 DESKTOP-9M0BU3H (admin, Tailscale 100.123.253.85)
  ├─ WLAN 192.168.137.104         → DHCP，IP 会变（曾经是 .24）
  ├─ mihomo allow-lan: true       → AUTO-DORM = Japan-MacMini-HY2
  └─ Tailscale → Mac 直连 130.62.209.98:41641 → 日本出口 130.62.209.98
```

关键事实：

| 事实 | 说明 |
| --- | --- |
| PC 出口（经笔记本） | `130.62.209.98`（日本） |
| PC 出口（直连） | `175.152.178.218`（校园网） |
| PC → Mac 的 Tailscale | **DERP(tok) 中继，264–293ms**；所以 PC 本地 HY2/SSH 天生慢 |
| 笔记本 → Mac 的 Tailscale | **direct** `130.62.209.98:41641`；所以笔记本才是正确出口 |
| PC 本地 mihomo（`127.0.0.1:7897`） | `PROXY=Japan-MacMini-SSH`，实测 **18–27KB/s**，仅最后兜底 |
| 经笔记本 LAN（`.104:7897`） | 实测 **313–368KB/s** |
| 经笔记本 Tailscale（`100.123.253.85:7897`） | 约 **170KB/s**，LAN 失败时的兜底 |

> 2026-10-27 事故：笔记本 DHCP 从 `.24` 变成 `.104`，旧脚本写死 `.24`，PC 连续
> 失败后落到本地 mihomo，10MB 下载只有 18–27KB/s。新脚本已改为自动发现。
>
> 2026-10-27 第二次事故（Google 登录点「继续」无反应）：笔记本 → Mac 的 Tailscale
> 直连丢包 26%，同时 `AUTO-DORM` 里还挂着宿舍不该用的 CF 通道（p90 8.5s、
> 3/20 失败）。HY2 一抖就 fallback 到 CF，页面直接卡死。已把 CF/CF2 从
> `AUTO-DORM`/`AUTO` 移除，只留 `HY2 → TS`。**宿舍下 CF 通道不要再加回来。**

## 1. 唯一正确的策略

1. **主路径**：PC 系统代理 → 笔记本当前 LAN IP 的 `7897`。
2. **发现顺序**：ARP 里笔记本 Wi-Fi MAC `20:C1:9B:82:8E:44` → 地址缓存 →
   显式 `192.168.137.104` / `192.168.137.24` → Tailscale `100.123.253.85`。
3. **健康检查**：204，优先国内直连端点
   `http://connect.rom.miui.com/generate_204`（HY2 满载时仍能秒回）；
   当前 LAN 目标只要 TCP 可连就保持，避免大流量时误切。
4. **兜底顺序**：LAN → Tailscale → 连续 2 次失败才回落 `127.0.0.1:7897`（本地）。
5. **永远不要写死 `192.168.137.24`**，也不要把系统代理长期设成 `127.0.0.1:7897`。
6. **笔记本侧的出口组**（`AUTO-DORM` / `AUTO`）只允许 `HY2 → Japan-MacMini(TS)`，
   不要加入 CF；探测 URL 用 `https://www.google.com/generate_204`。

## 2. PC 侧文件与职责

| 路径 | 作用 | 规则 |
| --- | --- | --- |
| `C:\Users\22771\bin\pc-proxy-failover.ps1` | 每分钟跑的自动切换脚本 | **源文件在笔记本 repo**，不要在 PC 上单独改 |
| `C:\Users\22771\bin\pc-proxy-failover.vbs` | 计划任务入口（无窗口） | 不要改 |
| `C:\Users\22771\bin\pc-proxy-failover.log` | 切换日志 | 只看，不手改 |
| `C:\Users\22771\bin\pc-proxy-failover.fails` | 连续失败计数 | 只看；正常应为 `0` |
| `C:\Users\22771\bin\pc-proxy-failover.addresses.json` | 地址缓存 | 只看 |
| `...\proxy-stack\wproxy\pc-proxy-failover.ps1` | PC repo 内的源副本 | 应与 bin 一致（可对比 SHA256） |
| `...\proxy-stack\wproxy\config.yaml` | PC 本地 mihomo（旧配置） | **仅兜底**；`PROXY=Japan-MacMini-SSH`，不要换节点/端口 |
| `...\proxy-stack\wproxy\hysteria-client.yaml` | PC 本地 HY2 客户端（7893） | 仅备用；PC→Mac 走 DERP，慢是正常的 |
| `C:\Users\22771\bin\pc-lan-gateway.ps1` | 手机用代理：热点网关 `:7890` → 笔记本 `:7897` | 计划任务 `PcLanGateway` 每分钟跟随笔记本 IP；`-Status` 查看、`-Remove` 撤销 |

计划任务：`PcProxyFailover`（登录自启 + 每分钟）→ `wscript.exe pc-proxy-failover.vbs`
→ `powershell.exe -File pc-proxy-failover.ps1`。

## 3. 接手检查（按顺序，只读）

```powershell
hostname
ipconfig | findstr /i "IPv4"

# 1) 系统代理
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' |
  Select-Object ProxyEnable,ProxyServer

# 2) 任务状态
Get-ScheduledTaskInfo -TaskName PcProxyFailover |
  Select-Object LastRunTime,LastTaskResult,NextRunTime

# 3) 切换日志与失败计数
Get-Content C:\Users\22771\bin\pc-proxy-failover.log -Tail 10
Get-Content C:\Users\22771\bin\pc-proxy-failover.fails

# 4) 笔记本当前 LAN IP（ICS DNS 名称）
Resolve-DnsName DESKTOP-9M0BU3H.mshome.net

# 5) 端口与代理连通性
Test-NetConnection 192.168.137.104 -Port 7897
curl.exe -sS -o NUL -w "code=%{http_code} ttfb=%{time_starttransfer}s`n" `
  --max-time 10 -x http://192.168.137.104:7897 https://www.gstatic.com/generate_204

# 6) 5MB 实测（正常 > 250KB/s）
curl.exe -sS -o NUL -w "code=%{http_code} speed=%{speed_download}B/s total=%{time_total}s`n" `
  --max-time 60 -x http://192.168.137.104:7897 `
  "https://speed.cloudflare.com/__down?bytes=5000000"

# 7) 路径确认（PC→Mac 走 DERP 是正常的；PC→笔记本应 direct）
tailscale ping -c 2 100.105.59.31
tailscale status | findstr /i "desktop-9m0bu3h"
```

**正常状态**：`ProxyServer=192.168.137.<当前IP>:7897`，`fails=0`，204 在 2s 内，
5MB > 250KB/s，`tailscale ping` 到 Mac 显示 `DERP(tok)`（这是 PC 本地慢的根因，
不是故障）。

## 4. 故障决策树

| 现象 | 判断 | 处理 |
| --- | --- | --- |
| `ProxyServer=192.168.137.24:7897` | 旧地址，脚本没跑或任务停了 | 先看任务状态，再手动跑一次脚本；不要手改注册表 |
| `ProxyServer=127.0.0.1:7897` | 笔记本不可达，已回落本地 | `Test-NetConnection <当前IP> -Port 7897`；跑 `-ProbeOnly` 看候选；不要手动改代理 |
| `ProxyServer=100.123.253.85:7897` | LAN 暂时失败，Tailscale 兜底 | 下一分钟应自动回 LAN；可手动跑一次脚本 |
| 代理地址正确但网页慢 | 不是 PC 脚本问题 | 到笔记本查 `AUTO-DORM`/`Japan-MacMini-HY2` 是否正常；**不要在 PC 换节点** |
| 完全断网 | 先排除上行 | PC 直连 `curl https://ipinfo.io/ip`；检查 `192.168.86.197` 以太网；再查笔记本是否在线 |
| 笔记本 IP 又变了 | 正常（DHCP） | 等下一次任务（≤1 分钟），或手动跑脚本；不要改成固定 IP |

手动跑一次（安全，会自行判断）：

```powershell
& C:\Users\22771\bin\pc-proxy-failover.ps1 -ProbeOnly   # 只看，不改
& C:\Users\22771\bin\pc-proxy-failover.ps1             # 应用
```

## 5. 修改 / 同步流程（重要）

**笔记本 repo 是唯一源文件**：

```
C:\Users\admin\Desktop\项目\proxy-stack\wproxy\pc-proxy-failover.ps1
C:\Users\admin\Desktop\项目\proxy-stack\wproxy\AGENTS.md
```

PC 可以 SSH 到笔记本（密钥已配置）：

```powershell
ssh admin@100.123.253.85 hostname    # 应返回 DESKTOP-9M0BU3H
```

PC 拉取最新脚本（在 PC 上执行）：

```powershell
scp admin@100.123.253.85:"C:/Users/admin/Desktop/项目/proxy-stack/wproxy/pc-proxy-failover.ps1" `
  C:\Users\22771\bin\pc-proxy-failover.ps1
scp admin@100.123.253.85:"C:/Users/admin/Desktop/项目/proxy-stack/wproxy/AGENTS.md" `
  C:\Users\22771\Desktop\项目\proxy-stack\wproxy\AGENTS.md
```

改完后在 PC 上验证：

```powershell
Get-FileHash C:\Users\22771\bin\pc-proxy-failover.ps1
& C:\Users\22771\bin\pc-proxy-failover.ps1 -ProbeOnly
& C:\Users\22771\bin\pc-proxy-failover.ps1
```

规则：

- 先改笔记本源文件，再同步到 PC；**不要在两台机器各维护一份不同版本**。
- PC 侧 Agent 对笔记本默认**只读**：可以 `ssh` 查看/拉取文件，但不要直接改笔记本的
  repo、`config.yaml`、`profiles.ps1`、`AUTO-DORM`；需要改策略时先在笔记本侧由
  用户/笔记本 Agent 确认，再按上面的流程同步。所有变更记入本文件 Change Log。
- 应急在 PC 上改：先备份 `.bak-YYYYMMDD`，改完立即把 diff 同步回笔记本 repo，
  并更新本文件 Change Log。
- PC repo 是一个**有大量未提交改动的旧工作副本**：**不要 `git pull`、
  `git reset --hard`、`git checkout .`**，会覆盖本地改动。
- 不要试图把整个 `wproxy/` 从笔记本同步到 PC：两边配置本来就不同
  （PC 本地端口 7893、旧代理组），只需要同步 `pc-proxy-failover.ps1` 和文档。

## 6. 不要做（Do Not）

- ❌ 不要把系统代理写死成 `192.168.137.24:7897` 或任何固定 IP。
- ❌ 不要把系统代理长期设成 `127.0.0.1:7897`（本地只有 18–27KB/s）。
- ❌ 不要禁用/删除 `PcProxyFailover` 计划任务，或改它的入口 VBS。
- ❌ 不要在 PC 上单独编辑 `bin\pc-proxy-failover.ps1`（改源 + 同步）。
- ❌ 不要改 PC 本地 `config.yaml` 的节点/端口/`allow-lan`，不要把 `PROXY`
  从 `Japan-MacMini-SSH` 换成 `Japan-MacHy2`/其他节点（实测更慢或直接失败）。
- ❌ 不要在 PC 上开 TUN、装第二个代理/Clash、改 DNS、改 hosts。
- ❌ 不要改热点 SSID/密码/频段/ICS，也不要把 PC 变成第二个网关。
- ❌ 不要改笔记本的 `allow-lan`、`profiles.ps1`、`AUTO-DORM`、`hysteria-client.yaml`；
  这些是笔记本侧 Agent 的职责。
- ❌ 不要因为“PC 本地测速慢”就判定脚本坏了：PC→Mac 的 Tailscale 是 DERP，
  本地慢是物理路径问题，不是配置能修的。
- ❌ **不要把 `Japan-MacMini-CF` / `Japan-MacMini-CF2` 加回 `AUTO-DORM` 或 `AUTO`**。
  宿舍下 CF 隧道 `cloudflared` 持续 origin EOF，p90 8.5s、3/20 失败；HY2 抖动一次
  就会 fallback 过去，表现为「页面点了没反应」。CF 只属于办公室组（`AUTO-OFFICE`）。
- ❌ 不要把健康检查 URL 改回 `www.gstatic.com`：gstatic 经 HY2 有 2/30 握手失败，
  会触发误判降级；宿舍组统一用 `https://www.google.com/generate_204`。
- ❌ 不要为了「Google 登录慢」去改 PC 系统代理、PC 本地节点或 DNS：这类抖动来自
  笔记本→Mac 的 Tailscale 直连丢包（实测 26%），改 PC 侧无效。
- ❌ **手机报「網路組態問題 / 非預期的 SSL 憑證」时，先查是不是代理叠加**：
  手机用 CMFA（Clash 类 App，开 VPN）时，**Wi-Fi 里绝对不能再设 HTTP 代理**，
  两层叠加会 DNS 解析错乱，症状就是 SSL 证书错误。
  2026-10-27 就是这么踩的坑——把 Wi-Fi 代理设回「无」，只留 CMFA。
- ❌ CMFA 配置里**不要用「Tailscale 嵌套」作为主路径**：手机上 userspace
  Tailscale + fake-ip 再套 socks5，同样会报 SSL 证书错误。宿舍/热点场景用
  `LAN-Laptop`（直连 `192.168.137.104:7897`）优先，`Japan-MacMini` 只作兜底。
- ❌ 不要把 `pc-lan-gateway.ps1` 的 `ListenPort` 改成 7897：PC 本地 mihomo 已占用
  `127.0.0.1:7897`，改成 7897 会冲突；也不要删 `wproxy-lan-7890` 防火墙规则。
- ❌ **PC 侧环境变量必须指向笔记本，不要写成 `127.0.0.1:7897`**：
  首选 **`http://DESKTOP-9M0BU3H.mshome.net:7897`**（ICS 主机名，DHCP 换 IP 也能跟上），
  没有主机名解析时才退回当前的 `192.168.137.<IP>:7897`。
  PC 本地 mihomo 只有 18–27KB/s，且 PC 桌面端的 Node WebSocket 只读环境变量、
  不读系统代理。改完必须彻底退出 ChatGPT/codex 再启动才生效。
- ❌ 升级 Store 包不要用 `Add-AppxPackage -Register` 以 SYSTEM 身份执行（报
  `0x80073CF9`）；用当前用户 + 最高权限的计划任务，且升级前必须杀干净进程
  （否则 `0x80073D02`）。
- ❌ 不要在 PC repo 执行 `git pull` / `git reset --hard` / `git checkout .`。

## 7. Change Log

- **2026-11-03（02:02，magpie 全红/magpie 网关全 502 —— 真因是笔记本 config.yaml 被写坏）**：
  用户报「magpie 网关全挂了，即使还有额度」。**跟额度无关，是 magpie 的所有出站请求
  在代理握手阶段就死了。**
  - **证据链**（`~\.config\magpie\logs\app.log`）：
    `proxyconnect tcp: dial tcp 192.168.5.5:7897: connectex: No connection could be
    made because the target machine actively refused it`，每 30s 一轮，打满所有
    provider：Antigravity 全部报「signed out」（其实是 token 刷新被拦在同一处）、
    Codex usage 读不到、WorkBuddy/OpenCode 网关 `502 after 15s`。
  - **为什么是 192.168.5.5**：magpie 是 Go 程序，只读 `HTTP_PROXY/HTTPS_PROXY`
    环境变量（`~\.config\magpie\settings.json` 里**没有任何代理设置项**），
    而用户级 env 三个变量都指向笔记本。
  - **笔记本为什么拒连**：笔记本在线（Tailscale direct `192.168.5.5:41641`、22 端口通），
    但 **mihomo 根本没进程**。看门狗日志 `mihomo failed to start, exit code 1`，
    `mihomo.out.log` 给的是 **`Parse config error: yaml: line 14: did not find
    expected key`** —— `config.yaml`（**mtime 2026-11-03 01:32:57**，13.7KB，
    原 11.7KB）被整份写坏了：中文注释变成 `锛?` 这种乱码，**换行被吃进多字节字符**，
    `sniffer:` 被并到上一行注释里，于是下面 `  enable: true` 失去父键 → YAML 崩。
    典型「UTF-8 被当 GBK 读出来再存成 UTF-8」的二次编码事故。
    对照 `config.yaml.bak-pin`（10-30 00:07，11.7KB）同一个位置是正常中文。
  - **处置（只动 PC 侧）**：用户级 `HTTP_PROXY/HTTPS_PROXY/ALL_PROXY` 由
    `http://192.168.5.5:7897` 改为 `http://127.0.0.1:7897`，原值备份在
    `C:\Users\22771\proxy-env-backup-20261103.txt`；杀 magpie + magpie-bun 后
    用新 env 重拉。**重启后日志 0 条 proxyconnect、0 条 failed**，
    Antigravity 三个账号正常拿到 tier 和模型列表，`credits-daily.json` 立即刷新。
  - **本地兜底这次是好的**：`PROXY = Japan-MacHy2`（走 Mac `100.105.59.31:8443`，
    不依赖笔记本），实测 `google.com/generate_204` **204 / 0.52s**、
    5MB **476KB/s**、出口 **130.62.209.98**（日本）。
    所以「本地只有 18–27KB/s」那条结论只在 `Japan-MacMini-SSH` 时期成立，现在不成立。
  - **没做的事（属于笔记本侧职责）**：没有改笔记本 `config.yaml`。修法二选一：
    ① `Copy-Item config.yaml.bak-pin config.yaml`（最省事，10-30 的版本，已验证能起）；
    ② 逆向还原编码（UTF-8 读 → GBK 编码回字节 → 再按 UTF-8 读），但换行已被吃掉，
    会丢内容，不推荐。修好后记得把 PC 三个 env 改回 `192.168.5.5:7897`。
  - **顺带发现拓扑已经变了，文档 §0 需要改**：现在**没有 ICS 热点**
    （无 `192.168.137.1`），PC `192.168.5.2` 与笔记本 `192.168.5.5`
    挂同一个路由器；`PcProxyFailover` 任务**最后一次运行是 2026-10-30 15:51**
    （它找的还是 `192.168.137.x`），系统代理停在 `127.0.0.1:7897` 没再被它管过。
- **2026-10-30（10:05，笔记本换 IP 到 .156 + 热点侧无 IPv4 DNS →「已连接但无网络」）**：
  用户报笔记本连上热点但显示无网络。定位到**两个独立问题**，都已修。
  - **① IP 变了**：笔记本回来后被 ICS DHCP 分到 **`192.168.137.156`**（原 `.104`），
    旧地址在 ARP 里是 `Incomplete`。`pc-proxy-failover` 靠 MAC 自动发现，
    `10:02:10` 已自动把系统代理切到 `.156:7897`，`fails=0`；
    `PcLanGateway` 也在 `10:02:12` 跟随（手机网关 `192.168.137.1:7890` → `.156`）。
  - **② 真正的「无网络」是 DNS**：笔记本 WLAN 通过 ICS DHCP 只拿到一个
    **IPv6 链路本地 DNS（`fe80::...`）**，没有 IPv4 DNS，于是
    `curl` 报 `Could not resolve host`、NCSI 探测失败 → Windows 显示「无网络」。
    **IP/网关本身是好的**（ping `223.5.5.5` 8ms、0 丢包）。
    修法：`Set-DnsClientServerAddress -InterfaceAlias WLAN -ServerAddresses
    192.168.137.1,223.5.5.5`。修后 `www.baidu.com` 解析正常、
    `connecttest.txt` 返回 `Microsoft Connect Test`、直连 baidu 200/0.56s。
  - **③ 环境变量改成主机名**：`HTTP(S)_PROXY`/`ALL_PROXY` 由 `.104` 直接换成
    **`http://DESKTOP-9M0BU3H.mshome.net:7897`**——ICS 会把这个名字解析到笔记本
    当前 IP（实测解析到 `.156`，且经它访问 `oauth2.googleapis.com` 404/1.2s、
    `google` 204/0.73s），**以后 DHCP 再换 IP 也不用改环境变量**。
    `192.168.137.104` 这个写死值从文档里撤掉，规则改成「优先主机名」。
  - **④ jio 在笔记本侧不用加规则**：笔记本的 `config.yaml` 规则尾部是
    **`MATCH,PROXY`**（PC 本地是 `MATCH,DIRECT`），所以 jio 天然走代理；
    实测经 `.156:7897` → `301`。**PC 本地那份仍需 `DOMAIN-SUFFIX,jio.com,PROXY`**
    （09:05 已加）。
  - **顺带确认**：`WProxyHysteria` 任务在笔记本重启后正常拉起（mihomo 09:56:53、
    hysteria 09:58:48），`WProxy` 也是 Running；`VIDEOIDLE/STANDBYIDLE` 仍是 0、
    屏保仍是关——电源那套扛过了重启。
- **2026-10-30（09:20，agy 授权报 proxyconnect 到 .104 超时 —— 临时改 env 指向本地）**：
  用户跑 `antigravity`（agy）OAuth，粘验证码后报
  `token exchange failed: Post "https://oauth2.googleapis.com/token": proxyconnect tcp:
  dial tcp 192.168.137.104:7897 ... timed out`。
  - **原因**：agy 是 Go 程序，只认 `HTTP_PROXY`/`HTTPS_PROXY` 环境变量；而这两个变量
    指向笔记本 `.104`，笔记本从 08:20 起就离线（Tailscale last seen 1h），
    所以卡在 `proxyconnect`，跟 token 本身无关。
  - **处置**：用户级 `HTTP_PROXY`/`HTTPS_PROXY`/`ALL_PROXY` 临时改为
    `http://127.0.0.1:7897`（本地 mihomo，此刻系统代理也是它）。
    实测 `POST https://oauth2.googleapis.com/token` 1.4s 返回
    `401 invalid_client`（应用层正常应答），链路通。
  - **注意**：这是**临时状态**，违反「env 必须指笔记本」那条规则。笔记本回来后要改回
    `http://192.168.137.104:7897`，否则一直吃本地兜底的慢速。`WinHTTP` 仍指向
    `.104`（Windows Update/Store 会失败），等笔记本回来一并处理。
  - **09:30 决定（用户确认）**：笔记本一直没回来（`.104` ARP 变 Incomplete、
    Tailscale last seen 1h），**先不回滚**，等 `.104` 恢复后再把三个变量改回
    `.104:7897`。回滚时一并做：① 改 env；② 笔记本 `config.yaml` 补
    `DOMAIN-SUFFIX,jio.com,PROXY`（09:05 那条的待办）。
- **2026-10-30（09:05，jio.com 打不开：规则漏网落到 MATCH,DIRECT —— PC 侧已修）**：
  用户报 `https://www.jio.com/dl/my_sub` 打不开。
  - **不是代理挂了**：同一时刻 `google` 204 / 1.4s、`cloudflare`/`github` 都正常。
  - **证据**（PC 本地 mihomo 日志）：
    `dial DIRECT (match Match/) ... www.jio.com:443 error: dial tcp 49.40.8.180:443: i/o timeout`
    —— 域名没命中 `GEOSITE,geolocation-!cn`（Jio 不在这个分类里），掉到最后的
    `MATCH,DIRECT`，于是从校园网直连印度 Jio 的 IP，必然超时。
  - **排除「Jio 封海外 IP」**：用 HY2 的 socks5（`--socks5-hostname`，让出口侧解析）
    访问 `www.jio.com/dl/my_sub` → **301**，根域 **200**，flipkart 也 200。
    说明隧道出口能到 Jio，问题只在分流规则。
  - **修法**：在 `GEOSITE,geolocation-!cn` 之前加
    `- DOMAIN-SUFFIX,jio.com,PROXY`（PC 本地 `config.yaml`），
    `mihomo -t` 校验通过后热加载；备份 `config.yaml.bak-20261030-jio`。
  - **结果**：经 `127.0.0.1:7897` → `301` → `https://www.jio.com/dl/my_sub/` **200**；
    根域 200 / 3.9s。
  - **待办**：笔记本的 `config.yaml` 是另一份，规则尾部同样是
    `GEOSITE,geolocation-!cn,PROXY` + `MATCH,DIRECT`，**同样要加这一行**，
    否则笔记本回来后（系统代理切回 `.104`）jio 又会走直连。09:05 时笔记本仍离线
    （Tailscale last seen 44 分钟前），等它回来补。
  - 备注：如果以后还要访问其他印度站（IRCTC 等），可以加 `GEOSITE,in,PROXY`
    或 `GEOIP,IN,PROXY` 一次覆盖，不必逐个域名加。
- **2026-10-30（00:10，宿舍组改回「HY2 优先」并确认自愈 —— 已修）**：
  用户选了「AUTO-DORM 改 url-test」的方案，实际排查后发现**不能改成 url-test**，
  真正的病根也不是 fallback 的粘性。
  - **真凶**：`config.yaml` 在 **10-29 08:43** 被人改过——`AUTO-DORM` 和 `AUTO`
    的成员顺序被换成 `Japan-MacMini`（TS）在前，注释写着「HY2 当前不可用」。
    所以 HY2 修好之后，组里**第一个成员仍然是 TS**，自然一直走慢节点。
  - **为什么不用 url-test**：mihomo 的 `url-test` 按**探测延迟**选最快。
    实测同一时刻 TS 的 RTT 经常比 HY2 低（170ms vs 243ms），但吞吐只有
    HY2 的 1/10（47KB/s vs 500KB/s）。改成 url-test 会**稳定选中慢节点**，
    正好和需求相反。配置文件里 10-27 的注释也是这么写的
    （「按延迟选路会误选慢节点，所以用 fallback」）。
  - **实际改法（同样满足「HY2 恢复后自动回来」）**：
    `AUTO-DORM` / `AUTO` 成员顺序恢复为 **HY2 → TS**，类型保持 `fallback`。
    fallback 每次健康检查（`interval: 60`）都会选「第一个存活节点」，
    HY2 一恢复，下一个周期就自动切回，不需要人工干预。
  - **流程**：`scp` 到 PC 改 → `mihomo.exe -t -d <wproxy> -f config.yaml.new`
    校验通过 → 备份 `config.yaml.bak-20261030` → 替换 → `PUT /configs?force=true`
    热加载（PS 5.1 的 `Invoke-RestMethod` 必须显式用 UTF-8 字节体，否则中文路径
    会被写成 `??` 而报 `path is not subpath of home directory`）。
  - **结果**：`AUTO-DORM members: Japan-MacMini-HY2 -> Japan-MacMini`，
    PC 经 `.104:7897`：`204` **0.84–1.23s**、2MB **371KB/s**、
    `google.com` **1.5s**。
  - **补充（23:50 那次断流的真实原因）**：不是睡眠（`Kernel-Power` 无 506/507，
    显示器超时已是 0），是 `hysteria` 隧道在 `23:50:19` 被对端重置、
    `23:52:16` 报 `timeout: no recent network activity`，`23:55:02` 才重连。
    这 5 分钟里 `AUTO-DORM` 按设计回落 TS，隧道恢复后又因为成员顺序问题
    没切回来——现在顺序修好了，这类情况会在 ≤60s 内自愈。
- **2026-10-29（22:40，熄屏 = 现代待机；HY2 客户端已死 15 小时 —— 已修）**：
  用户反馈「笔记本又自己熄屏了，服务有没有被打断」。查实**屏幕一关，整机就进
  Modern Standby（S0ix），服务真的停了 14 分钟**，但根因不是「睡眠超时」。
  - **证据**：`Kernel-Power` 506/507：`22:14:36 entering Modern Standby
    (Reason: Idle Timeout)` → `22:29:17 exiting (Reason: Input Keyboard)`；
    同时 `mihomo.out.log` 的 TCP 记录从 `22:14:56` 直接跳到 `22:29:00`，
    中间**一条连接都没有**。mihomo PID 40980 全程没变（S0ix 不杀进程），
    所以「进程在」不等于「服务在」。
  - **根因**：这台是 S0 现代待机机型，**关屏即进 Modern Standby**，与
    `STANDBYIDLE=0` 无关。之前只改了睡眠超时，没动显示器/屏保：
    `VIDEOIDLE=0x258`（10 分钟关屏）+ 屏保 `ScreenSaveTimeOut=600`
    + `ScreenSaverIsSecure=1`。所以 10 分钟一到 → 锁屏关屏 → 直接进 S0ix。
  - **修法**：`powercfg /change monitor-timeout-ac 0`（插电永不关屏）、
    `SUB_VIDEO\8ec4b3a5-...`（控制台锁定后关屏超时）= 0、
    `ScreenSaveActive=0`、`ScreenSaveTimeOut=0`。
    **代价：插电时屏幕常亮**，这是 S0ix 机型上唯一可靠的办法；
    别再只设 `standby-timeout-ac=0` 就以为万事大吉。
  - **顺带挖出真凶（性能）**：笔记本的 `hysteria` 客户端**从 07:43 就死了**
    （`hysteria-client.err.log` 末尾全是 `timeout: no recent network activity`），
    `AUTO-DORM` 于是回落到 `Japan-MacMini`（Tailscale，47KB/s）。
    为什么没被拉起来：
    - `hysteria-client.ps1` 这个看门狗**没有任何计划任务**（只有 `WProxy`
      管 mihomo），10-28 18:10 之后就没人跑了；
    - 而且这个脚本是 **UTF-8 无 BOM**，用 `powershell.exe`（5.1）跑会报
      `意外的标记 }`（第 51 行），必须用 `pwsh` 7。
  - **处置**：注册计划任务 **`WProxyHysteria`**（登录触发 / `admin` /
    Highest / 失败重启 3 次×1 分钟），Execute 用
    `C:\Users\admin\AppData\Local\Microsoft\WindowsApps\pwsh.exe`，
    与 `WProxy` 一致；启动后 `127.0.0.1:1082` 起来，日志
    `connected to server {"addr":"100.105.59.31:8443","tx":1250000}`。
    再把 `AUTO-DORM` 手动切回 `Japan-MacMini-HY2`。
  - **效果（PC 经 `192.168.137.104:7897`）**：`generate_204` **0.65–1.4s**
    （原 2.5–5s），2MB **502KB/s**（原 10–15KB/s），
    `https://www.google.com/` **200 / 1.9s**（原 6.8s）。
  - **教训**：① S0ix 机型「睡眠超时」和「关屏」是两回事，别只改前者；
    ② 判断慢之前先看 `AUTO-DORM` 的 `now` 是什么，HY2 掉了会静默回落 TS；
    ③ 长驻脚本必须挂计划任务，否则重启/注销后就没人拉起。
- **2026-10-29（晚 21:00 左右，笔记本掉线 → 浏览器「Google 意外终止了连接」）**：
  用户报 `www.google.com 意外终止了连接`。定位到**主出口（笔记本）整体掉线**
  + **PC 本地兜底本身是残的**，两个问题叠加。
  - **现象**：系统代理 `127.0.0.1:7897`（21:08 脚本回落），但经它访问
    `https://www.google.com/generate_204` 报 `schannel: failed to receive handshake`；
    `fails=25`；笔记本 `192.168.137.104` 的 7897 **TCP 有时能连、HTTP 完全无响应**，
    `tailscale ping 100.123.253.85` 全超时，`tailscale status` 里
    `DESKTOP-9M0BU3H` 显示 `Online=False`（LastSeen 13:20）。已排除校园网上行
    （直连 `ipinfo.io` 200、`223.5.5.5` 4ms、清华镜像 2MB/s）。
  - **根因 A**：笔记本休眠/关机，热点 LAN 与 Tailscale 两条路同时消失。
  - **根因 B**：本地 mihomo 的 `PROXY` 选的是 `Japan-MacMini-SSH`（`127.0.0.1:1080`），
    而该 SSH 隧道的 **jump host 就是笔记本**（`macos-socks-proxy/config.ps1`
    的 `$JumpHost = ADMIN@100.123.253.85`），笔记本一掉 1080 直接没进程 →
    本地兜底 100% 不通。
  - **根因 C（最隐蔽）**：`pc-proxy-failover.ps1` 的健康检查 URL 是
    `connect.rom.miui.com` / `www.gstatic.com` / `cp.cloudflare.com`，
    这三个在 PC 本地 mihomo 里**全走 DIRECT**，所以上游全死时
    仍报 `204 health verified`，把死代理当健康代理保留。
  - **处置**：`hysteria-client.yaml` 的 `bandwidth` 由 `2/8 mbps` 改为
    `10/30 mbps`（对齐 2026-10-27 服务端 Brutal 的改动；此前 7893 报
    `Recv failure: Connection was reset` 就是这个不一致），重启 hysteria 客户端，
    日志 `connected to server ... tx: 1250000`；mihomo `PROXY` 切到
    `Japan-MacHy2`（延迟探测 2062ms，`Japan-CF`/`Japan-MacMini` 均超时）。
    改后系统代理路径 `generate_204` 4–7s 通过、`https://www.google.com/` 200。
  - **残留**：`PC → Mac` 当前是 Tailscale DERP(tok) + 对称 NAT，实测只有
    3–16KB/s，本地兜底只能算「能打开文字页」，看不了视频。**真正恢复要叫醒笔记本**。
    WoL 魔数包（`20:C1:9B:82:8E:44`，端口 7/9）无效：AX201 不在
    `powercfg /devicequery wake_programmable` 里，`/deviceenablewake` 报
    「没有启用或禁用设备唤醒的权限」——**这台机器 Wi-Fi WoL 根本不可用**，
    以后别指望隔空叫醒。
  - **22:00 笔记本唤醒后的收尾**：
    - 脚本 `22:01:55` 已自动切回 `192.168.137.104:7897 (204 health verified)`，
      `fails` 归零，无需手动干预。
    - **电源（防再次睡死）**：笔记本是 S0 现代待机机型（无 S3），原「插电 10 分钟
      无操作就睡」（`STANDBYIDLE=0x258`）。已改为
      `standby-timeout-ac=0`（插电永不睡眠）、`hibernate-timeout-ac=0`、
      `RTCWAKE` 插电启用、**「系统无人参与睡眠超时」= 0**
      （`7bc4a2f9-d8fc-4469-b07b-33eb785aaca0`，这一项不清零会出现「唤醒 2 分钟
      又自己睡回去」）、`LIDACTION` 合盖 = 不采取任何操作（原先该项被隐藏，
      先用 `powercfg -attributes SUB_BUTTONS 5ca83367-... -ATTRIB_HIDE` 解除隐藏）。
      电池（DC）仍保留 4 分钟睡眠，无线节能模式本来就是最高性能。
    - **性能现状（已修正，见 22:40 条目）**：当时 PC 经笔记本只有 10–20KB/s，
      我一度判断成「校园网国际出口拥塞」，**这个判断是错的**。真实原因是
      笔记本的 Hysteria2 客户端从 07:43 起就没进程了，`AUTO-DORM` 回落到了
      `Japan-MacMini`（Tailscale 节点，47KB/s）。HY2 修好后同一路径实测
      **502KB/s**。
    - 本地 mihomo 的 `PROXY` **故意保留 `Japan-MacHy2`**：它走
      `100.105.59.31:8443`，不依赖笔记本；而 `Japan-MacMini-SSH` 的 jump host
      就是笔记本，笔记本一掉必然失效，不适合当兜底。
  - **待办**：① 健康检查加一个**真正走代理**的 URL（如 `https://www.google.com/generate_204`），
    否则「上游死、端口活」还会再次被误判；② `MacSocksProxy` 任务自 10-27 起
    未成功运行（`LastTaskResult=0xFFFFFFFF`），需要重新拉起；③ PC 侧
    `HTTP_PROXY`/`HTTPS_PROXY` 仍指向 `192.168.137.104:7897`，笔记本掉线期间
    ChatGPT/codex 等只读环境变量的程序必然连不上。
- **2026-10-27（深夜 6，Brutal 带宽虚高把 PC 拖垮 —— 已修）**：
  用户反馈「PC 网络依然十分不稳定」。定位到**是我上一轮把 Brutal 带宽设太高造成的**。
  - **症状**：PC 经笔记本 60 次 → `55/60，p50 0.73s，p90 5.54s`，
    超 2s 有 10 次；而**同一时刻笔记本自身 60 次是 `60/60，p50 0.43s`**。
    PC→笔记本 TCP 建连 p50 仅 **8ms**、丢包 0%，说明 PC 侧网络没问题。
  - **根因**：Brutal 的 `bandwidth` 设成 `up 100 / down 200 mbps`，
    但实测链路只有约 **190 KB/s（1.5 mbps）**。Brutal 按设定值猛发、
    不因丢包降速 → 远超链路容量 → 大量丢包重传 → 笔记本、PC、手机
    三方共用时互相拖垮。
    旁证：笔记本本地有个 `chatgpt.com` 连接持续 399 KB/s、累计 39.5 MB
    （16 个 ChatGPT 进程从 15:56 起跑了 7 小时），把隧道占满。
  - **修法**：两端 `bandwidth` 一起收紧到 **`up 10 / down 30 mbps`**
    （贴近实测值、留少量并发余量）。服务端用 `restart-hysteria2.sh` 重启，
    客户端用 `hysteria-restart.request` 触发，确认日志 `tx: 1250000`（=10mbps）。
  - **效果**：
    - PC 40 次：`40/40，p50 0.37s，p90 0.63s，max 0.92s，超 2s = 0`
      （修复前 p90 5.54s、10 次超 2s）
    - PC 并发 6×6：**36/36 全通**
    - 笔记本自身 40 次：`40/40，p50 0.33s`
    - 吞吐 200–650 KB/s
  - **教训：Brutal 的 bandwidth 必须贴近真实可用带宽，虚高反而更差。**
    以后调这个值，先测实际吞吐再设，且要留多客户端并发的余量。
- **2026-10-27（深夜 5，Mac 端服务端 Brutal 真正生效 + 脚本化重启）**：
  上一轮的 Brutal 只作用于客户端；服务端进程一直是老 PID，新配置从未加载。
  这次把重启过程脚本化并踩清所有坑，**服务端 Brutal 已确认生效**。
  - **最终效果**：HY2 吞吐 **677 KB/s – 1.55 MB/s**（原 21–34 KB/s，约 30–45 倍）；
    系统代理综合 p50 **1.42s**，20/20 全通。
  - **踩到的 5 个坑（已全部写进 `mac-restart-hysteria2.sh` 注释）**：
    1. **macOS 没有 `setsid`**（Linux 命令）。用 `setsid nohup ... &` 会静默失败：
       SSH 立刻返回但脚本根本没跑，`restart.log` 为空、PID 不变。
       必须用 `nohup /bin/sh script >/tmp/rh.out 2>&1 & disown`。
    2. **plist 里 `KeepAlive=true`**：`pkill` 后 launchd 会立刻拉起新进程占住 8444。
       必须先 `launchctl unload` 解除 KeepAlive，再 kill。
    3. **顺序必须是「停服务 → 确认端口释放 → dry-run → load」**。
       反过来先 dry-run 必然 `bind: address already in use`，
       会被误判成「配置有问题」而回滚（v2 就栽在这里，把 bandwidth 配置还原掉了）。
    4. **服务端与客户端 `bandwidth` 必须一致**，否则握手超时，
       客户端报 `failed to initialize client: timeout: no recent network activity`。
    5. **重启 hysteria 后必须同时重启 Python `udp-relay`**，
       否则它会缓存指向旧服务端的失效映射，客户端依然连不上。
  - 脚本位置：`~/bin/restart-hysteria2.sh`（Mac），
    本地源文件 `wproxy/mac-restart-hysteria2.sh`。
    调用：`nohup /bin/sh ~/bin/restart-hysteria2.sh >/tmp/rh.out 2>&1 & disown`；
    结果看 `~/.config/hysteria2/restart.log`。
    脚本自带：配置 dry-run 校验、失败自动回滚、端口释放等待、双服务重启确认。
  - **回答「重启后 SSH 能自动接回吗」：能。** SSH 走 `100.105.59.31:22`
    （Tailscale 隧道，mihomo 有 `IP-CIDR,100.64.0.0/10,DIRECT` 直连规则），
    与 hysteria（UDP 8443）是**完全独立的两条通道**；
    `com.hysteria2` / `com.udp-relay` 都是独立 LaunchAgent（`RunAtLoad=true`），
    崩溃或重启都会自动拉起，不会连带 SSH。
- **2026-10-27（深夜 4，Mac 端 SSH 调优：Brutal 让吞吐提升 45 倍）**：
  用户授权 SSH 到 Mac（凭据在 `Desktop/标注/试标/keys/Mac连接.txt`，
  `kobayashi@100.105.59.31`，密码认证，paramiko 可用）。
  - **关键测量**：Mac 本机直连 Cloudflare **6.6 MB/s（约 53 Mbps）**，
    而经 Hysteria 隧道到 Windows 只有 **21–34 KB/s** ——
    瓶颈是 Windows↔Mac 的跨境链路，不是 Mac 出口带宽。
  - **Hysteria 服务端配置原先极简**（只有 listen/tls/auth，无 bandwidth、无 quic 调优），
    跑的是默认 BBR。已在 `~/.config/hysteria2/config.yaml`（已备份 `.bak-20261027`）
    加入 `bandwidth: up 100 mbps / down 200 mbps`（Brutal）与 quic 窗口调优。
  - **Windows 客户端 `hysteria-client.yaml` 同步开启 `bandwidth: 100/200 mbps`**，
    用 `hysteria-restart.request` 触发看门狗重启（PID 39616，20:29:08）。
  - **效果（客户端 Brutal 生效即已大幅改善）**：
    吞吐从 21–34 KB/s → **685 KB/s–1.22 MB/s，p50 991 KB/s（约 45 倍）**；
    延迟从 1.0–1.5s → 0.62–0.85s。
  - 注意：**服务端进程仍是老 PID（14455，周六启动），未重启**，即服务端 Brutal 未生效；
    当前提升来自客户端侧。要完全生效需重启服务端
    （`launchctl unload/load ~/Library/LaunchAgents/com.hysteria2.plist`），
    但本次会话中该操作会让 SSH 命令通道挂死，需谨慎或在 Mac 本地执行。
  - 架构备忘：`Windows --UDP--> 100.105.59.31:8443 (Python udp-relay.py)
    --> 127.0.0.1:8444 (hysteria server)`。Python UDP 中继本身也是潜在瓶颈。
- **2026-10-27（深夜 3，本机与手机都极卡：两个真凶）**：
  症状「能连但非常卡」。定位到**两个独立问题**，都已修：
  - **真凶 A：探测 URL 写错导致健康检查全失败。**
    同一时刻实测：`gstatic` → HY2 245ms / TS 171ms（正常）；
    `google.com` → 两条都探测超时（返回 0）。于是 `alive=False`、
    组被误判不可用并频繁切走。`AUTO-DORM` 的 `url` 必须用 **gstatic**，
    不要用 `google.com`。（此前 2026-10-27 下午的改动是错的，已回退。）
  - **真凶 B：gstatic 被 DNS 解析到海外 IP，直连规则形同虚设。**
    `gstatic.com` 在国内有 CDN（广州联通 `58.254.137.162`，直连 40/40、p50 0.42s），
    但 mihomo 的 `fallback`（1.1.1.1/8.8.8.8 经代理）把它解析成
    Google 海外 `142.251.x.x`，导致「规则写 DIRECT 却连海外 IP」→ 直连必失败。
    修法：加 `nameserver-policy: "+.gstatic.com": alidns`，并加
    `DOMAIN-SUFFIX,gstatic.com,DIRECT`。
  - **顺带优化**：`oaistatic.com`（ChatGPT 静态资源）与 `auth.openai.com`
    实测直连 4/4 通过（0.75–1.21s），而经 HY2 隧道只有 21–34 KB/s，
    已改 DIRECT 以减轻隧道压力。**`chatgpt.com` 主站直连超时，仍走代理。**
  - 效果：综合 p50 从 1.45s → **0.94s**，20/20 全通。
  - **注意：链路本身仍在恶化时段性抖动**（实测丢包 10–20%、平均延迟
    271→562ms、最大 2360ms），且 `MappingVariesByDestIP: true` 说明处于
    对称 NAT。直连 Cloudflare 可达 520–824 KB/s，而经 HY2 隧道仅 21–34 KB/s
    —— **瓶颈在 Mac 端的跨境链路，不在本机或热点**，属物理链路限制。
- **2026-10-27（深夜 2，手机连不上笔记本 LAN IP 的真因）**：换了「LAN 直连」配置后
  手机仍不稳定，CMFA 日志刷屏：
  `[TCP] dial PROXY mihomo --> 8.8.8.8:443 error: 192.168.137.104:7897 connect error:
  dial tcp 192.168.137.104:7897: connect: connection timed out`，
  并伴随 `because AUTO failed multiple times, activate health check`。
  - **根因：ICS 热点的 NAT 回环限制。** 手机 → 笔记本 LAN IP 的 SYN 会被热点 NAT 丢掉。
    在笔记本上自测可复现：
    `127.0.0.1:7897` OK、`192.168.137.104:7897`（本机自己的 IP）OK、
    **`192.168.137.1:7897`（经网关回环）20/20 全超时**；
    笔记本 ping 手机也全超时，但 ping 网关 0% 丢包、PC→笔记本 7897 正常。
    所以不是防火墙、不是 Wi-Fi 信号（RSSI -40），是热点 NAT 不转发同网段回环。
  - 处置：手机配置改走 **PC 网关** `192.168.137.1:7890`
    （`LAN-Gateway` 节点，`udp: false`），PC 上 `netsh portproxy` 转发到笔记本 7897，
    并重新启用 `PcLanGateway` 任务维持转发。
  - 验证：经网关 40/40 **100%**（p50 1.11s、p90 2.82s）；
    并发 8×12 = **95/97（98%）**；`chatgpt.com` 403（Cloudflare 正常响应）。
  - **以后手机配置必须用 `LAN-Gateway`（192.168.137.1:7890），不要用笔记本 LAN IP 直连。**
- **2026-10-27（深夜，手机代理间歇性不稳定）**：手机「有时弹 SSL 证书错误、有时正常」。
  **根因是健康检查超时太激进，把「慢但可用」误判成「挂了」并切到 DIRECT。**
  - 实测：笔记本 → Mac 的 Tailscale 链路**丢包 30%**
    （`ping 100.105.59.31` 30 包丢 9；`tailscale ping` 10 次里 4 次超时，
    其中 2 次 `no reply`）。但经 mihomo 的 HTTPS 请求 40 次仍 39 次成功
    （97.5%，p50 1.32s）——说明 mihomo 有重试兜底，是**上层判定**太严。
  - 三条通道对比（各 20 次小请求）：HY2 **20/20**、TS **20/20**、CF **6/20**。
    CF 在宿舍依旧不可用，已确认不该回到 `AUTO-DORM`。
  - 处置 1（手机 `cmfa-config.yaml`）：`AUTO` 组
    `timeout 5000 → 10000`、`interval 120 → 60`、`lazy false → true`。
    5s 判定在 40% 抖动率下必然频繁误判。
  - 处置 2（笔记本 `config.yaml`）：`AUTO-DORM` 的
    `timeout 8000 → 15000`、`interval 120 → 60`。
  - 验证：改后手机等效路径 40 次 **39/40 成功（97.5%）**；
    并发 8 路 × 10 轮 = **80/80 全通过**。
  - 注意：Wi-Fi 本身没问题（RSSI -35、到网关 0% 丢包），
    抖动来自**笔记本 → Mac 的跨境链路**，不是热点也不是手机。
- **2026-10-27（晚，Codex 手机配对彻底定位）**：手机报
  `Remote-control clients must use /client/enroll/start and /client/enroll/finish`。
  **结论：电脑端完全正常，问题在手机 App 没走新版 enroll 流程。**
  - 服务端真实 API 在 **`https://chatgpt.com/backend-api/codex/remote/control/`**，
    不是 `codex-cloud-environments.chatgpt.com`（后者只给 exec-server 用）。
  - `GET .../environments` → **2 台电脑均 `online: true`**
    （`DESKTOP-9M0BU3H`、`haoshao`，`app_server_version 0.164.0-alpha.11.1`）。
  - `GET .../clients` → **`{"items": []}` 空**，即服务端没有任何已注册的**手机**。
  - `POST .../client/enroll/start` → **200**，正常返回 `client_id` +
    `device_key_challenge`。说明服务端接口没问题，是手机端没调用它。
  - PC 上 `codex.exe exec-server --remote https://codex-cloud-environments.chatgpt.com/api
    --environment-id ccarenv_b64_...` 进程存在，且连接经
    `192.168.137.1:57990 → 192.168.137.104:7897`（已走代理），存活 2400s+。
  - 排查中手动调用 enroll/start 会创建一个 `pending_enrollment` 的
    `Unknown Computer` 客户端，已用 `DELETE .../clients/<id>` 清理（返回 204）。
  - 因此：**不要再去改 PC 的代理、版本、环境变量**——这些都验证过是好的。
    下一步只能等 OpenAI 修复手机 App，或在手机端更新到会调用 enroll 的版本。
- **2026-10-27（傍晚，手机 SSL 报错复盘）**：手机仍报「網路組態問題 / 非預期的 SSL
  憑證」。这次是**我上一步引入的问题**：手机本来用 CMFA（Clash 类 VPN），
  我又让它把 Wi-Fi 手动代理指向 `192.168.137.1:7890`，**两层代理叠加** →
  DNS 解析错乱 → SSL 证书错误。
  - 证据：PC 上 `7890` 的活跃连接里**没有手机**（`192.168.137.187` 一条都没有），
    说明 CMFA 的 VPN 已经把流量接管，Wi-Fi 代理根本没生效只是添乱。
  - 处置：`pc-lan-gateway.ps1 -Remove` 删除 7890 转发与防火墙规则，
    并 `Disable-ScheduledTask PcLanGateway`，避免它再次自动建回来。
  - `android-phone/cmfa-config.yaml` 改为 **LAN 优先 + Tailscale 兜底**：
    新增 `LAN-Laptop`（`192.168.137.104:7897` 直连），
    `AUTO` = fallback(`LAN-Laptop` → `Japan-MacMini`)，
    探测 URL 用 `https://www.google.com/generate_204`。
    不再把「Tailscale 嵌套 socks5」当主路径（同样会触发 SSL 报错）。
  - 手机侧要做的：**Wi-Fi 手动代理改回「无」**，只让 CMFA 的 VPN 工作。
- **2026-10-27（下午，PC 桌面端升级 + 环境变量）**：手机配对报
  `Remote-control clients must use /client/enroll/start and /client/enroll/finish`。
  排查发现 **PC 与笔记本版本不一致，且 PC 侧环境变量为空**：
  - PC 桌面端 `26.1016.6573.0`、CLI `0.163.0-alpha.11`；
    笔记本 `26.1021.3705.0`、CLI `0.164.0-alpha.11.1`。
  - **PC 上 `HTTP_PROXY`/`HTTPS_PROXY`/`ALL_PROXY`/`NO_PROXY` 全为空**，
    WinHTTP 也是直连——PC 桌面端的 Node 侧 WebSocket 拿不到代理，
    所以 `hostId=durable` 从未连上（日志 `next=ready` 出现 0 次）。
  - 处置：`winget install --id OpenAI.ChatGPT --force` 升到 `26.1021.3705.0`；
    写入用户级代理变量（指向 `192.168.137.104:7897`）；`netsh winhttp` 同步；
    彻底关闭并用 `explorer shell:AppsFolder\...` 重启继承新环境。
  - 结果：笔记本侧出现来自 `192.168.137.1` 的
    `codex-cloud-backend.chatgpt.com` 长连接（存活 1400s+ 且有字节流动），
    说明 PC 桌面端云端会话已建立。
  - 注意：**Store 包升级后 `Add-AppxPackage -Register` 会被 SYSTEM 拒绝
    （0x80073CF9）**，必须用当前用户 + 最高权限的计划任务执行；
    且升级前必须彻底关闭 ChatGPT/codex 进程（否则 0x80073D02）。
- **2026-10-27（下午，手机热点代理）**：手机扫码后 ChatGPT 报「網路組態問題 / 非預期的 SSL
  憑證」。排查结论：**手机完全没走代理**——笔记本只看到 `127.0.0.1`、`192.168.137.1`、
  Tailscale 三种来源，没有手机；PC 的 mihomo 只监听 `127.0.0.1:7897`，手机连不上，
  于是直连 ChatGPT 被拦截 → SSL 证书错误。
  - 处置：在 PC 上把热点网关 `192.168.137.1:7890` 转发到笔记本的 `:7897`
    （`netsh interface portproxy`），并加防火墙入站规则 `wproxy-lan-7890`。
  - 新增 `pc-lan-gateway.ps1` + 计划任务 `PcLanGateway`（登录自启 + 每分钟）：
    按 MAC 自动跟随笔记本 DHCP 变化，LAN 不可用时回落 Tailscale。
  - 手机只需在 Wi-Fi 设置里填：代理 = 手动，主机 `192.168.137.1`，端口 `7890`。
  - **不要**把 7890 当成笔记本的端口；它是 PC 网关上的转发口。
- **2026-10-27（下午）**：PC 热点下打开 Google OAuth 授权页（OpenAI 登录）点「继续」无反应。
  排查结论：**不是 PC 侧代理配置问题**，是笔记本出口链路抖动 + 组内混入 CF 慢通道：
  - PC 系统代理正确指向 `192.168.137.104:7897`，`fails=0`，任务正常；PC 经笔记本
    出口 `130.62.209.98`（日本），`accounts.google.com` 20/20 通过（p50 0.75s）。
  - 真正的抖动在**笔记本 → Mac 的 Tailscale 直连**：`ping 100.105.59.31` 26% 丢包
    （60 包丢 8），HTTP 突发下 HY2 约 3% 握手失败（`schannel: failed to receive handshake`
    / `context deadline exceeded`）。这是校园网上行/跨境路径的丢包，不是 mihomo 配错。
  - 同一时段 `AUTO-DORM` 里还挂着 `Japan-MacMini-CF/CF2`：宿舍下 CF 隧道
    p90 8.5s、3/20 失败、`cloudflared` 持续 `failed to connect to origin: EOF`。
    HY2 抖动一次就 fallback 到 CF，整页直接卡住 —— 这才是「点了没反应」的直接原因。
  - 处置：`AUTO-DORM` 与 `AUTO` 移除 `CF/CF2`，只保留 `HY2 → TS(TCP)`；
    健康检查 URL 由 `www.gstatic.com` 改为 `www.google.com`
    （gstatic 经 HY2 2/30 失败会误判降级，google.com 30/30 通过）。
    改后系统代理 25 轮 × 8 并发 = 200/200 通过。
  - **不要**因为这类问题去改 PC 的系统代理或本地节点；CF 在宿舍本来就该排除。
- **2026-10-27（凌晨）**：笔记本 DHCP `.24` → `.104`；旧脚本写死 `.24`，PC 回落到本地
  mihomo（`Japan-MacMini-SSH`），10MB 下载 18–27KB/s。新脚本改为 MAC/ARP 自动
  发现、LAN 优先、204 健康检查（国内直连端点优先）、当前 LAN 保持；实测
  313–368KB/s，50MB 满载 2 分钟不再抖动。已部署到 `C:\Users\22771\bin\`，
  并更新笔记本 repo 的 README。
- **2026-10-27（凌晨）**：PC 本地 mihomo 曾临时切到 `Japan-MacHy2`/`Japan-MacMini`
  做对比，均明显更差，已恢复 `Japan-MacMini-SSH`；本地仅兜底，不要再动。
