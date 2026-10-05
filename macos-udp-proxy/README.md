# macos-udp-proxy

Mac 端 **支持 UDP** 的 SOCKS5 代理方案（TCP + UDP 全支持）。
Windows 上的 CuteCloud/Clash 经 Tailscale 直连 Mac 的代理端口，实现完整的网络出口代理。

> 背景：原 SSH 隧道方案（macos-socks-proxy 项目）受限于 OpenSSH，只能代理 TCP。
> 本方案替代它承载日常代理流量；SSH 隧道保留为备用节点（仅 TCP）。

## 组成

| 位置 | 文件 | 说明 |
| --- | --- | --- |
| Mac | `~/bin/socks-relay.py` | SOCKS5 服务（TCP CONNECT + UDP ASSOCIATE），Python 3.9 标准库实现 |
| Mac | `~/Library/LaunchAgents/com.socks-relay.plist` | 开机自启 + while 守护循环（python 崩溃 2 秒内自愈） |
| Mac | `~/.config/socks-relay/*.log` | 运行日志 |
| Windows | Clash 节点 `Japan-MacMini` | `socks5 100.105.59.31:7891, udp: true` |

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

## 管理命令（ssh 到 Mac 后执行）

```bash
netstat -an | grep 7891                                  # 查看监听（应有 tcp4 LISTEN）
launchctl kickstart -k gui/$(id -u)/com.socks-relay      # 重启
launchctl bootout gui/$(id -u)/com.socks-relay           # 停止
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.socks-relay.plist   # 启动
tail -f ~/.config/socks-relay/stdout.log                 # 日志
```

## Clash 分流规则（国内直连）

规则顺序（自上而下匹配，命中即止）：

```yaml
rules:
  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve   # Tailscale 网段
  - GEOIP,private,DIRECT                       # 局域网
  - DOMAIN-SUFFIX,bilibili.com,DIRECT          # B 站系
  - DOMAIN-SUFFIX,bilivideo.com,DIRECT
  - DOMAIN-SUFFIX,hdslb.com,DIRECT
  - DOMAIN-SUFFIX,qq.com,DIRECT                # QQ / 腾讯系
  - DOMAIN-SUFFIX,tencent.com,DIRECT
  - DOMAIN-SUFFIX,wechat.com,DIRECT
  - GEOSITE,cn,DIRECT                          # 全部中国站点（约 11 万条域名记录）
  - GEOIP,CN,DIRECT                            # 解析到中国 IP 的
  - MATCH,PROXY                                # 其余全部走日本
```

- 想强制某站走代理：在 GEOSITE 之前加 `- DOMAIN-SUFFIX,example.com,PROXY`
- 想强制某站直连：加 `- DOMAIN-SUFFIX,example.com,DIRECT`（放最前面）
- 改完在 CuteCloud「配置」页点一下配置卡片重新应用
- 验证：CuteCloud「连接」页可查看每条连接命中 DIRECT 还是 Japan-MacMini

## 验证（Windows）

```powershell
# TCP：应返回日本出口 IP 130.62.209.98
curl.exe --socks5-hostname 100.105.59.31:7891 https://api.ipify.org
# UDP：在 CuteCloud 里选中 Japan-MacMini 后跑需要 UDP 的应用（游戏/QUIC 视频等）验证
```

