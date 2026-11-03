# 2026-10-24 笔记本 magpie 连不到供应商 —— 排查记录

## 结论（一句话）

不是 magpie 本身坏了，是**它依赖的出口全断了**：笔记本 mihomo 的 `PROXY` 组指向 `AUTO`（三个 Japan-MacMini* 节点），而 Mac 出口
（`100.105.59.31`）从 **14:11** 起就离线，节点全部 0ms/超时；同时笔记本本地隧道 `127.0.0.1:1082` 处于 `WSAEADDRINUSE`
坏状态，任何走代理的连接 5 秒内必失败。magpie 的兜底成员（opencode-zen 429、codex 需代理）也全废 → 整条路由必然 502。

## 笔记本身份与访问方式

- 机器：`DESKTOP-9M0BU3H`，tailnet `100.123.253.85`，热点地址 `192.168.137.104`
- magpie `0.1.1364`，pid 39612，网关 `127.0.0.1:3425`（`serves:"app"`）
- 远程通道：**没有 rshell 客户端、没有 WinRM、没有 SSH**；SMB(445/135/139) 拒绝访问；唯一可用的是它 mihomo 的
  `mixed-port 7897`（`allow-lan: true`、`bind-address: *`）。
  → 技巧：`curl -x http://100.123.253.85:7897 http://127.0.0.1:3425/` 可直达它的 magpie 回环口（mihomo DIRECT 出站能连本机回环）。
  → 它 `external-controller: 127.0.0.1:9097`，secret 与仓库 `wproxy/config.yaml` 相同，经同一代理可读写。

## 证据链

1. Mac 出口死：`100.105.59.31` 的 7891/18992/22 全部不可达；metrics-hub `/devices` 里 `liangdeMac-mini.local`
   `last_seen = 14:11:21`（随后一直 offline）。PC 侧 watchdog 也在 `14:13:16` 记了
   `Japan-MacHy2 dead and no fallback reachable; fail-safe -> DIRECT`。
2. 笔记本节点死：`/proxies` 里 `Japan-MacMini`、`-HY2`、`-CF`、`-SSH` 全 `alive:false`，健康检查 `65535ms`；
   `AUTO.now = Japan-MacMini-HY2`（一个死节点）。
3. 笔记本代理链路坏死：debug 日志里满屏
   `dial tcp 127.0.0.1:1082: connectex: Only one usage of each socket address (protocol/network address/port) is normally permitted`
   → 它把 PROXY 流量交给本地 `1082` 隧道（另一条 SSH -D），那个端口 socket 处于 ADDRINUSE 坏态，连接瞬间失败。
4. magpie 路由全灭（`/v1/magpie/trace`，15:15 起每条都是）：
   - `workbuddy-ai/*` → `ECONNRESET: The socket connection was closed unexpectedly`（约 5.2s）
   - `codex/gpt-6.1-sol` → `Post "https://chatgpt.com/backend-api/codex/responses": EOF`（约 5.1s）
   - `opencode-zen/*` → `429 Free usage limit reached`（免费额度用尽）
   - `claude/*` → `502 Connection dropped (ECONNRESET)`（约 235s 才失败）
   → 组里每个成员都失败，所以「彻底连不到供应商」。
5. **关键反证**：同一台笔记本对 `www.workbuddy.ai` **直连是通的**（DIRECT 探活 439–1361ms，5/5 成功，和 PC 侧 446–1276ms 一致）。
   说明供应商域名没被封，坏的是「经代理」那条路。

## 已做的临时修复（笔记本侧）

```powershell
# 经它自己的 7897 打它的 external-controller
curl -x http://100.123.253.85:7897 -H "Authorization: Bearer <repo secret>" `
  -X PUT -d '{"name":"DIRECT"}' http://127.0.0.1:9097/proxies/PROXY
```

把 `PROXY` 从 `AUTO` 切到 `DIRECT`。验证：`group/auto-deepseek-v4-1-flash` 连续 3 次 200，流式请求也正常出字。
**这是权宜之计**：直连能救 workbuddy（它本来就没被封），但 codex/chatgpt/google 仍要等真正的出口恢复。

## 用户线索的解释（为什么手机热点行、本机热点不行）

- 本机热点（ICS `192.168.137.1`）的出口 = PC 的校园网（`192.168.86.197` → `192.168.80.1`）。校园网对境外 TCP 有严重
  QoS/丢包（watchdog 全天 400+ 次节点切换、吞吐 0~58KB/s 横跳），小包 UDP（QQ 心跳）能过、HTTPS 长连接/流式基本废
  → 「UDP 的 QQ 在线，网页 TCP 质量很差」完全对得上。
- 手机热点出口是运营商蜂窝，不受校园网这套限制，所以同一份配置换个出口就活了。
- 另外从 PC 自身探测：`192.168.137.1:53` UDP 查询被 RST、TCP 53 无监听（`ipnathlp` 只在 `0.0.0.0:53` 上，且不答来自主机的查询）。
  **待确认**：若笔记本侧也解析不了，那本机热点的 DNS 中继是坏的，会直接放大「连本机热点就网络错误」。

## 后续要做的（按优先级）

1. **先把 Mac 拉起来**（`100.105.59.31`，Mac mini）。它是 PC 和笔记本共用的主出口，它挂了两边一起瘫。查休眠/断电/Tailscale 掉线。
2. Mac 恢复后，把笔记本 `PROXY` 从 `DIRECT` 改回 `AUTO`。
3. 修笔记本 `127.0.0.1:1082` 那条隧道（ADDRINUSE 坏态），并把死节点从 `AUTO` 组里清掉/换掉。
4. magpie 的 `auto-deepseek-v4-1-flash` 组补一个**真正有余量**的兜底：现在 opencode-zen 429、codex 依赖代理，
   实际只有 workbuddy-ai 单点。
5. 别把 PC 的 ICS 热点当笔记本唯一出口；要么直接用手机热点，要么先修 ICS 的 DNS 中继。

## 附：笔记本在 16:36 之后彻底失联

tailnet 与热点两条路同时不通（`100.123.253.85:7897`、`192.168.137.104:7897` 都 closed），ARP 里 `137.104` 还在但 ping 不通。
疑似关机/合盖/断热点。后续要动它的配置得先让它回来。
