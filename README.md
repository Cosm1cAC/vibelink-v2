# proxy-stack

成都（Windows）→ 大阪（Mac）完整代理栈：全部开源、可审计、agent 可全自动管理。

## 组成

| 目录 | 说明 |
| --- | --- |
| `wproxy/` | **Windows 端**：开源 mihomo 内核 + 自研控制台（系统代理 / TUN 可切换、配置热重载、开源 WebUI）。日常用：双击 `一键启动.cmd`；首次需先运行 `download-assets.ps1` 拉取内核资源 |
| `macos-udp-proxy/` | **Mac 端**：支持 UDP 的 SOCKS5 中继（python3 + 标准库，利用苹果签名解释器绕开 macOS 应用防火墙），LaunchAgent 自启 + 守护循环 + Windows 侧看门狗三层保活 |
| `macos-socks-proxy/` | **Mac 端**：SSH SOCKS5 隧道 + Windows 看门狗（备用节点，仅 TCP） |

## 链路

```
Windows 应用 ──> wproxy(mihomo, 127.0.0.1:7897 / TUN)
    ├─ 国内域名/IP ──────────────> 直连
    └─ 其余 ──Tailscale──> Mac socks-relay(100.105.59.31:7891, TCP+UDP) ──> 日本大阪出口
```

## 新机器部署顺序

1. 两台机器装好 Tailscale 并互通；Windows 配置到 Mac 的 SSH 免密（密钥 `~/.ssh/id_ed25519`）
2. Mac 端部署 `macos-udp-proxy`（详见其中 README）
3. Windows 端：`cd wproxy` → `.\download-assets.ps1` → 双击 `一键启动.cmd`

## 常用操作（wproxy）

```powershell
.\wproxy.ps1 -Action status                       # 状态
.\wproxy.ps1 -Action mode -Value sysproxy|tun|off # 模式切换
.\wproxy.ps1 -Action reload                       # 改 config.yaml 后热重载
.\wproxy.ps1 -Action select -Value Japan-MacMini  # 切节点
```

## 安全说明

- 私有仓库，包含内网 Tailscale IP 与本地 API 密钥（仅监听 127.0.0.1）
- 所有二进制来自官方源（mihomo / zashboard / wintun），`download-assets.ps1` 内置 SHA256 校验
- 无任何闭源组件；配置全部明文可审计
