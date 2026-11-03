# proxy-stack

成都（Windows）→ 大阪（Mac）代理栈：多个子项目收纳于同一目录，围绕「成都 Windows ⇄ 大阪 Mac」这套基础设施：网络出口、应急通道、监控、多机 opencode 互访。全部开源、可审计、agent 可全自动管理。

## 结构

| 目录 | 部署位置 | 说明 |
| --- | --- | --- |
| `wproxy/` | Windows | 主客户端：开源 mihomo 内核 + 自研控制台（一键启停 / 系统代理·TUN 切换 / 热重载 / 开源 WebUI） |
| `macos-udp-proxy/` | Mac | 主节点：SOCKS5 中继（python3 实现，开机自启 + 三层保活）。**TCP 可用，UDP 实测不通（2026-10-11）** |
| `macos-socks-proxy/` | Windows | 备用线路：SSH SOCKS5 隧道（仅 TCP）+ 保活看门狗 |
| `android-phone/` | 安卓手机 | CMFA（mihomo 内核内置 Tailscale 出站）单 App 接入，规则同 wproxy |
| `macos-ssh-fallback/` | Mac + Windows | 应急 SSH 通道：用户态纯密钥 SSH + ngrok 出站隧道，**不依赖 Tailscale** |
| `macos-metrics/` | Mac | 监控数据源：用户态指标代理（CPU/GPU/内存/硬盘/带宽/延迟） |
| `metrics-hub/` | Windows 主机 + 第二台 Windows + 安卓 | 多设备监控 Hub：聚合本机/Windows/手机/Mac，供 WebUI 面板 |
| `opencode-mesh/` | Windows + Mac | 多机 opencode 互访：每机常驻 headless `serve` + 徽标代理（页面左下角显示直连/中继），Web UI 的 Servers 功能聚合各机会话（Tailscale + basic auth + CORS） |
| `yto-portal-auth/` | Windows | yto2022 门户自动重认证守护：断线检测 + 自动登录（Panabit/LFradius），保 PC 在线 |

## 链路

```
Windows 应用
  └─> wproxy (mihomo, 127.0.0.1:7897 系统代理 / TUN 不可用)
        ├─ 默认 ────────────────────> 直连（MATCH → DIRECT）
        ├─ 非中国大陆域名 ──────────> 主线路 ──Tailscale──> Mac socks-relay (100.105.59.31:7891, TCP) ──> 日本大阪出口
        │                              （GEOSITE geolocation-!cn，27217 条）
        └─ 全部节点不可达时 ────────> 自动降级直连（fail-safe）

应急通道（不依赖 Tailscale）:
Windows/手机 ──ssh──> ngrok TCP ──> Mac 用户态 SSH (127.0.0.1:12222, 仅密钥)

监控:
wproxy WebUI「监控」tab ──> Windows metrics-hub (127.0.0.1:18993) ──┬─ 自采集（本机 Windows）
                                                                   ├─ push：第二台 Windows / 安卓 Termux
                                                                   └─ pull：Mac macos-metrics (100.105.59.31:18992)

opencode 多机互访:
任一设备浏览器 ──Tailscale──> 各机 opencode serve (4096, basic auth)，UI 内 Add server 聚合
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
- 敏感凭据不入库：Tailscale auth key、ngrok authtoken、opencode 服务密码与 provider 凭据仅存在于设备本地配置
- 应急通道 `macos-ssh-fallback/`：用户态 SSH 仅密钥认证（无密码通道），ngrok 地址每次重启变化，入口不固定
