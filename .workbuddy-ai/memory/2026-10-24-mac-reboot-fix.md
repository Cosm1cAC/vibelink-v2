# Mac 重启后代理服务不自动拉起 —— 根因与修复（2026-10-24）

## 现象

- Mac mini（`100.105.59.31`，tailnet `liangmac-mini`）在 **2026-10-24 19:19** 重启；
- 重启后 `7891`（socks-relay）、`18992`（mac-metrics）、`8443/8444`（hysteria2 + udp-relay）**全部没有监听**；
- 结果：PC 的 `Japan-MacMini` / `Japan-MacHy2` 节点全红，笔记本 magpie 整条路由 502；
- 表面上像「网络坏了」，实际是**重启后用户级 launchd 服务根本没加载**。

## 根因：SSH 用户不是 console 用户 → 没有 gui domain

Mac 上两个真人账户：

| 账户 | uid | 角色 |
| --- | --- | --- |
| `liang` | 501 | **console 登录用户**（`stat -f%Su /dev/console` 返回 `liang`） |
| `kobayashi` | 503 | SSH 登录用户，`~/bin/*`、`~/Library/LaunchAgents/*.plist` 全在这个账户下 |

launchd 的 **gui domain 只存在于 console 登录的那个用户**（这里是 `gui/501`）。而所有 plist 都装在
`/Users/kobayashi/Library/LaunchAgents/`，SSH 进去 bootstrap 时：

```
$ launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.socks-relay.plist
Bootstrap failed: 125: Domain does not support specified action     # gui/503 不存在

$ launchctl bootstrap user/503 ...
Bootstrap failed: 5: Input/output error                             # user domain 也起不来
```

`launchctl list` 里全是 `com.apple.*`，一个自建服务都没有。

## 修复：给 plist 加 `LimitLoadToSessionType=Background`

加了这一项后，服务不再依赖 GUI 会话，可以在 **user domain** 里加载：

```bash
/usr/libexec/PlistBuddy -c "Add :LimitLoadToSessionType string Background" \
  ~/Library/LaunchAgents/com.socks-relay.plist
launchctl bootstrap user/$(id -u) ~/Library/LaunchAgents/com.socks-relay.plist   # rc=0
launchctl list | grep socks-relay     # 13954  0  com.socks-relay
```

四个服务全部照做，现已全部由 launchd 托管（重启后自动拉起）：

| Label | 端口 | 状态 |
| --- | --- | --- |
| `com.socks-relay` | tcp 7891 | ✅ |
| `com.mac-metrics` | tcp 18992 | ✅ |
| `com.hysteria2` | udp 8444 (localhost) | ✅ |
| `com.udp-relay` | udp 8443 (tailnet) | ✅ |

改动前的 plist 备份：`~/Library/LaunchAgents/com.socks-relay.plist.bak-20261024`（其余三个直接改，无 .bak）。

## 顺带修掉的问题

1. **重复进程**：手动 `nohup` 起的循环和 launchd 的循环同时跑，`socks-relay.py` 会报
   `v4 listen failed on 0.0.0.0:7891 [Errno 48] Address already in use`。
   清理方式：`pkill -f socks-relay.py` 后让 launchd 的 `KeepAlive` 自己拉起来，别再手动 `nohup`。
2. **Mac 上没有 `setsid`**（macOS 默认不带 util-linux）。想脱离 SSH 会话只能用 `nohup ... & disown`，
   但**正解是交给 launchd**，不要手动拉。
3. **PC 侧 `PROXY` 组**当时被临时切到了 `DIRECT`（见 `2026-10-24-laptop-magpie.md`），
   Mac 恢复后已切回 `Japan-MacMini`；笔记本的 `PROXY` 也已从 `DIRECT` 切回 `AUTO`。

## 遗留：PC ⇄ Mac 走的是 DERP 中继，吞吐只有 25KB/s

修复后链路可用但很慢：

```
PC -> Mac 100.105.59.31:7891 (2MB 下载) : 25 KB/s
PC 直连同文件                            : 836 KB/s
```

`tailscale ping` 显示：

```
pong from liangmac-mini (100.105.59.31) via DERP(tok) in 387ms
direct connection not established
```

Mac 侧 `netcheck`：公网 `130.62.209.98`，`MappingVariesByDestIP: true`（对称型 NAT），
UPnP 探测到 TP-LINK 路由器但 PMP 失败 —— 双方都拿不到可直连的映射端口，只能走 DERP。

想提速要解决 NAT 穿透，可选：
- 在 TP-LINK（`192.168.0.1`）上给 Mac 做 **UDP 41641 端口转发**（或开 DMZ）；
- 或换用已有的 hysteria2 通道（`udp 8443` → Mac `127.0.0.1:8444`），它对丢包更友好，本来就是为
  「校园网国际出口丢包 ~10%、TCP 塌到 25KB/s」这个场景做的。

## 验证

```bash
# Mac 侧
netstat -an -p tcp | grep -E '\.7891 |\.18992 ' | grep LISTEN
netstat -an -p udp | grep -E '\.8443 |\.8444 '
launchctl list | grep -E 'socks-relay|mac-metrics|hysteria|udp-relay'

# PC 侧（经 Mac 出口）
curl -s -o /dev/null -w '%{http_code}\n' --socks5-hostname 100.105.59.31:7891 http://cp.cloudflare.com/generate_204   # 204
curl -s -H "Authorization: Bearer <secret>" 'http://127.0.0.1:9097/proxies/Japan-MacHy2/delay?timeout=8000&url=http%3A%2F%2Fcp.cloudflare.com%2Fgenerate_204'
```

实测：PC→Mac SOCKS 5/5 成功；`Japan-MacHy2` 493ms、`Japan-MacMini` 630~1600ms；
笔记本 magpie `group/auto-deepseek-v4-1-flash` 5/5 成功；metrics-hub 里 Mac 恢复 online。

## 注意：HTTPS 从 PC 命令行测不出来，是 schannel 的问题

PC 上 `curl https://...` 和 `Invoke-WebRequest` 全都报：

```
schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS (0x8009030e)
```

**直连 baidu 也一样报**，所以跟 Mac / 代理无关，是这台 Windows 的 schannel 凭证存储坏了。
判断代理是否通，用 **HTTP**（`http://cp.cloudflare.com/generate_204`）或直接看 magpie 的
`/v1/magpie/trace`，不要用 `curl https://`。magpie 走 Node 的 TLS 栈，不受影响。
