# proxy-stack

成都（Windows）→ 大阪（Mac）代理栈：三个子项目收纳于同一目录，分别部署在 Windows / Mac 两端，做的是同一件事——把网络出口放到日本。全部开源、可审计、agent 可全自动管理。

## 结构

| 目录 | 部署位置 | 说明 |
| --- | --- | --- |
| `wproxy/` | Windows | 主客户端：开源 mihomo 内核 + 自研控制台（一键启停 / 系统代理·TUN 切换 / 热重载 / 开源 WebUI） |
| `macos-udp-proxy/` | Mac | 主节点：TCP+UDP SOCKS5 中继（python3 实现，开机自启 + 三层保活） |
| `macos-socks-proxy/` | Windows | 备用线路：SSH SOCKS5 隧道（仅 TCP）+ 保活看门狗 |
| `android-phone/` | 安卓手机 | CMFA（mihomo 内核内置 Tailscale 出站）单 App 接入，规则同 wproxy |
| `macos-ssh-fallback/` | Mac + Windows | 应急 SSH 通道：用户态纯密钥 SSH + ngrok 出站隧道，**不依赖 Tailscale** |

## 链路

```
Windows 应用
  └─> wproxy (mihomo, 127.0.0.1:7897 / TUN)
        ├─ 国内域名/IP ────────────────> 直连
        ├─ 主线路 ──Tailscale──> Mac socks-relay (100.105.59.31:7891, TCP+UDP) ──> 日本大阪出口
        └─ 备线路 ──SSH 隧道───> Mac sshd (127.0.0.1:1080, 仅 TCP)

应急通道（不依赖 Tailscale）:
Windows/手机 ──ssh──> ngrok TCP ──> Mac 用户态 SSH (127.0.0.1:12222, 仅密钥)
```

## 快速开始

Windows：

1. 进 `wproxy/`，首次运行 `download-assets.ps1` 拉取内核资源（含 SHA256 校验）
2. 双击 `一键启动.cmd`（首次会弹一次 UAC）

Mac（新机器）：按 `macos-udp-proxy/README.md` 部署。

详见各子目录 README。

## 安全说明

- 全开源组件（mihomo / zashboard / wintun）+ 自研脚本；二进制来自官方源并有哈希校验
- 私有仓库，含内网 Tailscale IP 与本地 API 密钥（仅监听 127.0.0.1）
- 敏感凭据不入库：Tailscale auth key、ngrok authtoken 仅存在于设备本地配置
- 应急通道 `macos-ssh-fallback/`：用户态 SSH 仅密钥认证（无密码通道），ngrok 地址每次重启变化，入口不固定
