# 2026-10-24 21:20-22:30 笔记本 magpie 波动复查

## 结论

波动**不是 magpie 或 Mac 出口的问题**，是两个叠加原因：

1. **Mac 上有重复进程抢端口**（我 19 点手工 `nohup` 的残留 + launchd 各一份）——
   `socks-relay` 和 `mac-metrics` 都刷 `OSError: [Errno 48] Address already in use`，
   launchd 的 `KeepAlive` 反复重启，端口在"有/无"之间闪断。
   清理掉手动的两份后，笔记本 HY2 节点延迟从 `366/429/3113/无响应/3451ms` 收敛到稳定的 `185~200ms`。

2. **54~58 条 `mtalk.google.com` 长轮询连接把 DERP 中继占满了**——
   笔记本上 `mtalk.google.com` 命中 `MATCH → PROXY`（规则里没有 google 直连项），
   于是 50+ 条 Chrome 推送长连接全挤到 PC↔Mac 的那条 **DERP 中继（nue）** 上。
   `socks-relay` 是「一个连接两个线程」的模型，实测 **391 线程 / 360 FD**，
   而 launchd 给它的 `jetsam thread limit = 32` —— 远超限额，导致连接排队、偶发 `keepalive` 超时。

## 实测数据

清理重复进程前：

```
laptop HY2 delay: 366 / 429 / 3113 / (空) / 3451 ms
laptop magpie 流式: 4/5
```

清理后：

```
laptop HY2 delay: (空) / Timeout / 485 / 201 / 194 / 188 / 185 / 188 ms
laptop magpie 流式: 8/8
laptop magpie 持续 20 次: 17 OK / 3 FAIL（failures: keepalive / 空响应）
```

连接分布（笔记本，71 条）：

```
DIRECT                              14
Japan-MacMini>AUTO>PROXY            57     ← 其中 40+ 条是 mtalk.google.com
```

链路对比（关键）：

```
PC   -> laptop : direct 192.168.86.197  (3ms)      ← 局域网直连，很好
PC   -> Mac    : relay "nue"  (DERP)
laptop -> Mac  : relay "nue"  (DERP)               ← 笔记本和 PC 挤同一条中继
Mac 自身出口   : 10/10 成功，35ms                   ← Mac 没问题
```

## 没解决的部分

- `mtalk.google.com` 走 DERP 是**规则问题**：笔记本 `config.yaml` 里没有 google 相关直连规则，
  只有 `GeoSite cn → DIRECT` + `MATCH → PROXY`，所以 google 全家都走代理。
  但**直连 google 在这条校园网下是不通的**（实测 DIRECT 探活 `mtalk.google.com` 超时），
  所以不能简单加 `DOMAIN-SUFFIX,google.com,DIRECT`——加了 Chrome 推送会彻底断。
- 真正的解法是给 `socks-relay` 提高线程限额，或改成 `asyncio` 单线程模型。
  现在 391 线程 vs `jetsam thread limit 32`，只是靠 macOS 宽容才没被 kill。

## 建议动作（按优先级）

1. **别再手工 `nohup` 拉服务**。`launchctl bootstrap` 之后如果没起来，看日志，
   不要用 `nohup` 兜底——会和 launchd 抢端口，反而制造波动。
2. 给 `com.socks-relay.plist` 加 `ThreadLimit`（或直接 `SoftResourceLimits` 里加 `NumberOfProcesses`），
   让它有足够线程额度。当前 `jetsam thread limit = 32` 对 50+ 并发连接明显不够。
3. 考虑把 `socks-relay.py` 的「每连接两线程」改成 `select`/`asyncio`，从根上降低线程数。
4. 如果 Chrome 推送不是必需，在笔记本上关掉 Chrome 的后台运行，能立刻减少 50 条连接。
5. `mtalk.google.com` 这类长轮询**不适合**走跨境代理——考虑在规则里给它一个低优先级/超时更短的策略，
   或者干脆让它失败（Chrome 会降级到普通轮询）。

## 顺带发现

- PC 的 `wproxy` watchdog 在 21:08 又因为「两个节点都 check failed」fail-safe 到了 `DIRECT`，
  21:14/21:21 报 `Another watchdog instance is running. Exiting.`（说明有重复 watchdog 实例）。
  21:19 复查时 PC `PROXY` 仍是 `DIRECT`，已重新切回 `Japan-MacMini`。
- PC 与笔记本之间的 tailnet 是 **direct**（局域网直连），所以「PC 连笔记本查 magpie」这条路本身很稳。
