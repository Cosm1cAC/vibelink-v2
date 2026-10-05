# macos-ssh-fallback

独立于 Tailscale 的「应急 SSH 通道」：**用户态纯密钥 SSH 服务 + ngrok 出站隧道**。
当 Tailscale（内网穿透）整条挂掉时，仍能从任意网络 SSH 进 Mac 抢修。

> 背景调研（2026-10-06 实测）：本线路（transix DS-Lite，公网 IP `130.62.209.98` 的 NAPT 在运营商 AFTR 侧）
> 无法做任何本地 IPv4 端口映射（路由器 UPnP `AddPortMapping` 返回 501，NAT-PMP/PCP 无响应）；
> IPv6 无 NAT 但被路由器/上游防火墙拦截（多为 TP-Link Archer BE450 + 光猫）。
> 因此"Mac 侧自建公网入站"不可行，需要一条**出站型**中继——ngrok 免费版即为此兜底。

## 架构

```
Windows/手机 --ssh--> 0.tcp.jp.ngrok.io:PORT --(ngrok 云)--> Mac ngrok agent(用户态)
                                                                  └─> 127.0.0.1:12222 asyncssh（仅密钥，无密码通道）
```

- 数据（Mac 侧）：
  | 文件 | 说明 |
  | --- | --- |
  | `~/bin/ssh-fallback-server.py` | asyncssh 用户态 SSH 服务（仅监听 127.0.0.1:12222） |
  | `~/.config/ssh-fallback/host_key` | 服务主机密钥（已生成） |
  | `~/.config/ssh-fallback/authorized_keys` | 允许的客户端公钥（默认复制自 `~/.ssh/authorized_keys`） |
  | `~/.config/ssh-fallback/server.log` | 认证/会话日志 |
  | `~/.config/ngrok/ngrok.yml` | ngrok agent 配置（含 authtoken，勿入仓） |
  | `~/Library/LaunchAgents/com.ssh-fallback.plist` | while 守护循环（LaunchAgent，无 sudo） |
  | `~/Library/LaunchAgents/com.ngrok-ssh.plist` | ngrok agent 守护循环 |
- 启动方式（本机 launchd 不执行 RunAtLoad/KeepAlive 的怪癖 → while 循环兜底，同 socks-relay 模式）：
  ```bash
  launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.ssh-fallback.plist
  launchctl kickstart -k gui/$(id -u)/com.ssh-fallback
  ```
- **保活闭环**：Windows 看门狗（`macos-socks-proxy/ssh-proxy.ps1`，计划任务 `MacSocksProxy`）约每分钟经 SSH 检查一次：
  `7891` 掉线 → kickstart `com.socks-relay`；`com.ssh-fallback` / `com.ngrok-ssh` 未加载（如 Mac 重启后）→ bootstrap+kickstart；
  ngrok 隧道异常 → 重启 agent。Mac 本机 launchd 的怪癖由此兜底。

## 为什么不用系统 sshd（端口映射 22）

1. ngrok 转发后 sshd 看到的来源全是 `127.0.0.1` → OpenSSH 10.x 的按来源惩罚会把**所有人**（含你自己）当成一个攻击 IP：攻击者失败几次就会掐断你的连接；
2. 系统 sshd 默认允许密码认证（改配置需要 sudo）；
3. asyncssh 服务可做到：**只有公钥、没有密码通道、独立日志、用户态可重启**。

## 使用（Windows）

```powershell
# 自动发现地址（经 Tailscale 读 Mac 本地 ngrok API），主机指纹已钉住
.\macos-ssh-fallback\connect.ps1          # 交互式 shell
.\macos-ssh-fallback\connect.ps1 -Command 'hostname; uptime'   # 执行命令

# Tailscale 也挂了：从手机/任意浏览器打开 https://dashboard.ngrok.com/endpoints
# 查看当前 TCP 地址，然后：
.\macos-ssh-fallback\connect.ps1 -Endpoint 0.tcp.jp.ngrok.io:20506
```

主机公钥固定在 `host_key.pub`（SHA256 指纹：`ssh-ed25519`，见文件），连接时自动写入临时 known_hosts 强校验。

## ngrok 免费版注意事项

- TCP 地址**每次 agent 重启会变化**（随机端口），当前地址可从两端查看：
  - 有 Tailscale：`connect.ps1` 自动读取，或 `ssh mac 'curl -s http://127.0.0.1:4040/api/tunnels'`
  - 无 Tailscale：`https://dashboard.ngrok.com/endpoints`
- authtoken 只存在于 Mac 的 `~/.config/ngrok/ngrok.yml`（不进仓库）；如泄露可在后台 Rotate
- ngrok agent 为出站连接（不受 ALF 入站限制）；Mac 重启后由计划任务/看门狗 kickstart（launchd 怪癖）

## 验证记录（2026-10-06）

- ✅ 服务功能：密钥认证、exec（stdout/退出码 7 正确）、交互式 PTY shell（TERM/TTY 正常）、SFTP/SCP 全部通过
- ✅ 端到端（Windows → ngrok → Mac）：banner `SSH-2.0-AsyncSSH_2.21.1`，`NGROK_OK`，主机指纹钉住校验通过
- ✅ 守卫：`launchctl list` 可见 `com.ssh-fallback`、`com.ngrok-ssh` 均为运行态；socks-relay 未受影响

## 可选升级（若要摆脱 ngrok 第三方依赖）

- **B. Cloudflare Tunnel**（域名 + cloudflared，出站型，SSH 可走 `cloudflared access` 或浏览器终端）
- **C. VPS + autossh 反向隧道**（完全自主，$2-4/月，顺便能做 wproxy 备用出口）
