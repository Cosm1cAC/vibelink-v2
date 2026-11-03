# 接手说明（2026-10-07）：给安卓手机装 CMFA 代理

> 给另一台笔记本上的 opencode 会话用。先 `git pull` 本仓库，重点看 `android-phone/README.md` 与 `android-phone/cmfa-config.yaml`。

## 任务

通过 USB（adb）给手机（OnePlus Ace 3 Pro）安装 **CMFA**（ClashMetaForAndroid + mihomo 内置 Tailscale 出站）：
手机全流量经 Tailscale 从日本 Mac 出口（国内直连），同时手机可访问 tailnet（PC/Mac 的 opencode）。

## 资源现状

- **APK**：CMFA v2.11.35 arm64（46.5MB），SHA256 `af7f05d8801798a8e75de682e5f67070138becb8623b7a341c3e5cd042a3753d`（已校验）
  - **仓库内副本（推荐直接用）**：`android-phone/dist/cmfa-2.11.35-meta-arm64-v8a-release.apk`
  - 官方下载：`https://github.com/MetaCubeX/ClashMetaForAndroid/releases/download/v2.11.35/cmfa-2.11.35-meta-arm64-v8a-release.apk`
  - 主 PC（haoshao）本地也有一份：`%TEMP%\cmfa-dist\cmfa-2.11.35-arm64.apk`
- **Tailscale auth key**：必须是**新生成的**（旧的一次性 key 已被消费）
  - 用户在 `https://login.tailscale.com/admin/settings/keys` 生成（Reusable 关、Ephemeral 关、Tags 空），复制 `tskey-auth-...`
- **配置**：仓库 `android-phone/cmfa-config.yaml` 模板，把 `REPLACE_WITH_AUTH_KEY` 换成新 key
- 手机上的官方 Tailscale App 要断开（安卓单 VPN 槽，两者不能共存）

## 步骤

1. 手机开 USB 调试（设置 → 关于手机 → 版本号 ×7 → 开发者选项 → USB 调试），数据线接笔记本，`adb devices` 确认已授权（弹窗点"允许"）
2. `adb install -r cmfa-2.11.35-arm64.apk`
3. `adb push <填好key的配置>.yaml /sdcard/Download/`
4. 手机上：CMFA → 配置 → + → 从文件导入 → 选中 → 打开 VPN 开关（首次授权）
5. 验证：
   - 主 PC 上 `tailscale status` 出现 `phone-mihomo` 在线（tsnode 注册成功）
   - 手机浏览器开 `https://api.ipify.org` 返回 `130.62.209.98`（日本出口）
   - 手机浏览器开 `http://100.98.206.126:4096` 能进 opencode（basic auth：用户名 `opencode`，密码见主 PC 的 `%USERPROFILE%\.config\opencode-server\password`，当前为 `[REMOVED_CREDENTIAL]`）

## 长期配置更新（可选，先不急）

Mac 上有订阅服务 `http://100.105.59.31:18991/phone-config.yaml`（LaunchAgent `com-phone-config-http`，仅 tailnet 可达）。
换 key 时需要更新 Mac 上 `~/phone-setup/phone-config.yaml`（SSH `kobayashi@100.105.59.31`，需要主 PC 的 `~/.ssh/id_ed25519` 私钥）。

## 主 PC 侧已完成的网络改造（背景，避免踩坑）

- wproxy（mihomo）：新增出口节点 `Japan-MacUser`（Mac userspace Tailscale 节点 100.86.50.115）、`Japan-CF`（Cloudflare Tunnel `socks.vibelink.cloud`，PC 侧 `127.0.0.1:7892`）、`Japan-Ngrok`（`127.0.0.1:1081`）；规则新增 `DOMAIN-SUFFIX,tailscale.com,PROXY`；本机口 7897 免认证，局域网共享口 **7898**（带认证）
- 手机如果在家同 WiFi，也可临时用主 PC 的共享代理 `http://wproxy:[REMOVED_CREDENTIAL]@192.168.86.20:7898`（opencode 不读该环境变量，仅浏览器/curl 等可用）
- 注意：仓库里 `captive-portal/` 与 `yto-portal-auth/` 是重复的 yto2022 重登看门狗；`yto-portal-auth` 是实测可用版本（密码已更正），建议停掉另一个的启动项，避免双重登录

## 2026-10-07 晚间更新：CMFA 变成主 PC 的中继（已上线）

- 发现：手机 CMFA 的 tsnode（`phone-mihomo-1`）到 Mac 是**直连**（~130ms），比 PC→Mac 的 DERP 中继（220–450ms）快，且 CMFA 作为 VPN 常驻、比 Termux 稳定
- 方案：主 PC wproxy 新增节点：
  - `PhoneEntry`：socks5 手机热点 IP `192.168.137.104:7890`（CMFA 混合端口；需 CMFA → **覆寫（Override）** 页开「允許來自區域網路的連線」，v2.11.35 不在「網路」页）
  - `Japan-PhoneRelay`：socks5 Mac `100.105.59.31:7891` + `dialer-proxy: PhoneEntry`
- 实测 `Japan-PhoneRelay` 138–150ms（全节点最快），已设为自动切换主节点（策略内嵌 `wproxy/mihomo-run.ps1`，兜底 MacMini → CF，30s 探测、2 次失败切换）
- 注意：
  - 手机 WiFi 休眠会令中继抖动（Mac 侧曾见 `phone-mihomo-1 offline 5m`）→ 建议 CMFA 关闭电池优化 + 系统 WLAN「休眠时保持连接」
  - 手机热点 IP 为 DHCP，可能变化；变化后更新 `wproxy/config.yaml` 的 `PhoneEntry.server` 并 `.\wproxy.ps1 -Action reload`
  - 经 Mac 的隧道单流吞吐 ~100KB/s（跨境段丢包，非手机问题）；延迟才是中继的收益点
- Termux 备用链路（`Japan-PhoneRelay2`）当前不可用：手动启动的 tailscaled 缺 `--socks5-server=localhost:1055`。如需启用：
  `pkill tailscaled; nohup tailscaled --tun=userspace-networking --socks5-server=localhost:1055 --socket=$HOME/.tailscale/tailscaled.sock --statedir=$HOME/.tailscale > ~/tailscaled.log 2>&1 &`
  然后重跑 `tailscale serve --bg --tcp=18080 tcp://127.0.0.1:1055`
