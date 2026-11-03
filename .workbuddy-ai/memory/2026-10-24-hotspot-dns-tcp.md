# 2026-10-24 23:00 「连本机热点 → 网页 TCP 很差 / bing 不通，但 QQ 的 UDP 正常」根因

用户截图：笔记本 WLAN 连着 **HAOSHAO 5189**（= PC 开的移动热点 / ICS），Windows 显示
**「无 Internet，安全」**。同一时刻 QQ 等 UDP 应用在线，但浏览器打不开 `bing.com`。

## 结论：不是同一个问题，是三层叠加

| 层 | 现象 | 根因 | 影响面 |
| --- | --- | --- | --- |
| ① 热点 DNS | 连热点后域名解析时好时坏 | ICS 的 DNS 中继（`192.168.137.1:53`）**不回应客户端查询** | 浏览器全废、QQ 靠 IP/长连接能活 |
| ② 热点 NAT 出口 | 网页 TCP 质量极差 | 出口是 PC 的**校园网**，对境外 TCP 有严重 QoS/丢包 | 所有 HTTPS 长连接 |
| ③ 笔记本代理链路 | magpie 波动 | 笔记本→Mac 走 DERP 中继，且 54+ 条 mtalk 长连接占满 | magpie 偶发 502 |

## ① 热点 DNS 中继是坏的（这条最直接）

```
UDP  0.0.0.0:53   -> pid 4528 = SharedAccess (ICS 自己)
```

ICS 绑住了 `0.0.0.0:53`，但**从热点网卡发起的查询得不到应答**：

```
从 192.168.137.1 发起查询 -> 无应答（远程主机强迫关闭了一个现有的连接）
对照 223.5.5.5          -> 应答 bytes=93
```

所以笔记本连热点时，只要它把 `192.168.137.1` 当 DNS（ICS 默认下发的就是这个），
**域名解析就会大面积失败** → 浏览器「打不开网页」，而 QQ 这类已经持有 IP / 走长连接的能活。

这也解释了「无 Internet，安全」：Windows 的 NCSI 探测（`www.msftconnecttest.com`）
同样过不了 DNS/HTTP，于是判定无 Internet。

## ② bing 特别不通，但不是 DNS 的问题

从笔记本侧分别测：

```
笔记本 DIRECT -> www.bing.com     : 无响应
笔记本 DIRECT -> www.baidu.com    : 47ms   ✅
笔记本 DIRECT -> www.qq.com       : 46ms   ✅
笔记本 DIRECT -> www.workbuddy.ai : 595ms  ✅
笔记本 DIRECT -> cp.cloudflare.com: 327ms  ✅
笔记本 PROXY  -> www.bing.com     : 779ms  ✅
```

再用 IP 绕开 DNS：

```
笔记本 DIRECT -> 202.89.233.101 (bing IP) : 无响应
PC    DIRECT -> 202.89.233.101            : 400 (连接成功，只是 Host 头不对)
PC    DIRECT -> www.bing.com              : 302 / 37ms  ✅
```

**同一个 IP，PC 能连、笔记本连不上** → 不是 DNS，是**笔记本的 TCP 路径到那个网段不通**。
bing 在国内是 `202.89.233.x`（微软 Azure 中国），校园网/热点对这段的放行策略和 PC 直连不同。
走代理就正常，说明「绕开这条路径」是有效解法。

## ③ magpie 波动（已修一部分）

见 `2026-10-24-laptop-magpie-flap.md`。要点：
- Mac 上有重复进程抢端口 → 已清理，HY2 延迟从 366~3451ms 收敛到 185~200ms；
- 笔记本 54+ 条 `mtalk.google.com` 长连接挤在同一条 DERP 中继上；
- `socks-relay` 391 线程 vs `jetsam thread limit 32`。

## 为什么「UDP 通、TCP 差」完全成立

- UDP（QQ 心跳、语音）：小包、无握手、丢了重发即可，对丢包和 DNS 都不敏感，**只要有 IP 就能跑**；
- TCP/HTTPS：要 DNS → 要三次握手 → 要 TLS → 要稳定带宽，任何一环（DNS 中继、校园网 QoS、MTU）出问题就整页打不开。

所以「QQ 在线 + 网页打不开」不是矛盾，是**这两类流量对基础设施的要求完全不同**。

## 建议（按性价比）

1. **别用 PC 的 ICS 热点当笔记本出口**。要么让笔记本直连校园网/手机热点，
   要么把热点换成能正常下发公共 DNS 的方案。
2. 若必须用 ICS：在笔记本上**手动把 DNS 改成 `223.5.5.5` / `119.29.29.29`**，
   绕开坏掉的 ICS DNS 中继。这一步能立刻救回「网页打不开」。
3. bing 这类走不通的站点，交给代理（已验证 PROXY 779ms 可用）。
4. 长期：PC 的 ICS 与 dockerd 都在抢 `:53`（`dockerd` 绑在 docker 网桥上，暂未冲突但很脆弱），
   建议把热点方案换成专门的路由/软路由，别依赖 Windows ICS。
