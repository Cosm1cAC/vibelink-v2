# 2026-10-27 B站直播卡顿：Akamai CDN 域名被 geolocation-!cn 误判走代理

## 一句话

B站直播的 Akamai 节点 `upos-hz-mirrorakam.akamaized.net` 是境外域名，
被 `GEOSITE,geolocation-!cn,PROXY` 命中，整条视频流被塞进 **25KB/s** 的代理隧道 → 必然卡。
已加 B站全链路 DIRECT 规则修掉。

## 证据链

```
[TCP] --> upos-hz-mirrorakam.akamaized.net:443
      match GeoSite(geolocation-!cn) using PROXY[Japan-MacMini-SSH]   ← 误判
[TCP] --> upos-sz-mirrorali.bilivideo.com:443
      match Match using DIRECT                                        ← 正常
[TCP] --> api.live.bilibili.com:443
      match Match using DIRECT                                        ← 正常
```

其余排除项（都正常）：

| 检查 | 结果 |
| --- | --- |
| 裸连 vs 经 mihomo DIRECT 中继 | 都是 ~2.1MB/s，内核不拖慢 |
| 网卡 | 100Mbps 全双工，错误/丢弃 **0** |
| B站直播 CDN 延迟 | 30ms，丢包 0% |
| 空闲带宽占用 | ~3Mbps（WorkBuddy 轮询 + 弹幕长连接） |
| Chrome 经 mihomo 连接数 | 2 条，不是连接池打满 |
| DNS | 腾讯 DoH，首包 361~487ms，偏慢但不是主因 |

## 修复（wproxy/config.yaml，规则须排在 GEOSITE 之前）

```yaml
  - DOMAIN-SUFFIX,bilibili.com,DIRECT
  - DOMAIN-SUFFIX,hdslb.com,DIRECT
  - DOMAIN-SUFFIX,bilivideo.com,DIRECT
  - DOMAIN-SUFFIX,bilivideo.cn,DIRECT
  - DOMAIN-SUFFIX,szbdyd.com,DIRECT
  - DOMAIN-KEYWORD,mirrorakam,DIRECT
  - DOMAIN-KEYWORD,bilivideo,DIRECT
```

热重载：`PUT /configs?force=true` body `{"path":"<abs config.yaml>"}` → 204，选择状态保留。

验证：`DomainKeyword(mirrorakam) using DIRECT`；
`i0.hdslb.com` 200/0.12s、`upos-sz-mirrorali.bilivideo.com` 959/0.15s、akamai 403/0.38s（403 是无路径的正常响应）。

## 重要坑：DOMAIN-SUFFIX 是标签边界匹配

`DOMAIN-SUFFIX,mirrorakam.akamaized.net` **不会**命中 `upos-hz-mirrorakam.akamaized.net`
（因为 `upos-hz-mirrorakam` 不是独立标签）。必须用 `DOMAIN-KEYWORD,mirrorakam`
或 `DOMAIN-SUFFIX,akamaized.net`。

## 顺带测到的硬件事实

- 以太网口：Realtek Gaming **2.5GbE** 控制器，实际只协商到 **100Mbps**（2.5G 网卡跑到 100M
  通常是网线只有 2 对通、或路由器口是百兆）
- 8 路并发实测总下行只有 **18.9Mbps**（多个不同源交叉验证 17~19Mbps）
- 若用户办的是 100M 及以上宽带，这条线路明显没跑满，需要查网线 / 路由器 WAN 口 / 套餐

## 结论

B站卡 = 路由规则问题（已修），与带宽无关；但 19Mbps 这个带宽本身也偏低，值得单独查。
