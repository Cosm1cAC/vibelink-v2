# Android 手机接入（ClashMetaForAndroid + mihomo 内置 Tailscale 出站）

手机全流量经 Tailscale 从日本 Mac 出口，规则与 Windows wproxy 一致（**默认直连，需要外网的才代理**）。
**只装一个 App**，不占用双 VPN 槽（Android 同一时刻只允许一个 VPN 服务，Tailscale App 与 Clash 类 TUN 不能共存）。

> ⚠️ 2026-10-11：Mac 侧 socks-relay 的 UDP 分支实测不通，因此手机上的 UDP 流量目前也无法经代理出口。

## 原理

CMFA（ClashMetaForAndroid，mihomo 官方安卓客户端，v2.11.35 起）内核支持 `type: tailscale` 出站
（userspace 入网，无需系统 Tailscale），配置里：

- `Tailscale` 节点：auth-key 登录你的 tailnet（单次使用，注册后 state 持久化）
- `Japan-MacMini` 节点：socks5 `100.105.59.31:7891`，`dialer-proxy: Tailscale`（经 tailnet 拨号，**TCP 可用，UDP 待修**）
- 规则：`100.64.0.0/10 → Tailscale`（手机内其他 App 也能访问 tailnet）、`tailscale.com → PROXY`、
  `GEOSITE geolocation-!cn → PROXY`（非中国大陆域名）、`MATCH → DIRECT`（默认直连）
- DNS：`fallback-filter` 只按 `geosite: gfw` + `240.0.0.0/4` 走境外解析器，
  其余域名用 doh.pub / alidns 直解析，代理挂了也不会整体 DNS 卡死

## 配置分发（长期）

**`http://100.105.59.31:18991/phone-config.yaml`** —— Mac 上的常驻 HTTP 服务（LaunchAgent `com.phone-config-http`，python3，仅 Tailscale 网段可达），CMFA 里的订阅 URL 可长期更新。

- 服务文件：`~/phone-setup/phone-config.yaml`（含 Tailscale auth-key，不入库；仓库模板见 `cmfa-config.yaml`）
- 保活：Windows 看门狗（`macos-socks-proxy/ssh-proxy.ps1`）会自动 bootstrap/kickstart 该 LaunchAgent
- 换新手机/清除数据后：auth-key 是一次性的（已消费），需在后台重新生成并替换 Mac 服务文件里的 key

## 部署步骤

1. **装 APK**：CMFA v2.11.35 arm64（48.8MB）
   - 下载：`https://github.com/MetaCubeX/ClashMetaForAndroid/releases/download/v2.11.35/cmfa-2.11.35-meta-arm64-v8a-release.apk`
   - SHA256：`af7f05d8801798a8e75de682e5f67070138becb8623b7a341c3e5cd042a3753d`（与 GitHub 官方 digest 一致）
2. **生成 Tailscale auth key**：`https://login.tailscale.com/admin/settings/keys` → Generate auth key
   （Reusable 关、Ephemeral 关、Tags 空），复制 `tskey-auth-...`
3. **填配置**：把 `cmfa-config.yaml` 中的 `REPLACE_WITH_AUTH_KEY` 换成上一步的 key
4. **导入 CMFA**：配置 → + → 从文件/剪贴板导入 → 选中该配置 → 打开 VPN 开关（首次授权）
5. 此时手机上的 Tailscale App 会断开（单 VPN 槽约束）——正常，tailnet 访问已由 CMFA 内的 tsnode 接管

## 验证记录（2026-10-06）

- ✅ 手机（`ace-3-pro`）启用 CMFA 后正常上网，国内站点直连、境外走大阪（用户确认）
- ✅ Mac socks-relay `100.105.59.31:7891` 对手机节点可达（TCP+UDP）
- ✅ tsnode 以 `phone-mihomo` 注册进 tailnet（auth key 单次使用后即失效，state 持久化）

## 注意

- APK 官方 digest 校验通过；仓库不含 auth key（模板占位）
- 若规则报 geosite 缺失：CMFA 设置 → Geo 数据 → 更新一次
- 换手机/清数据后需要重新生成 auth key 并修改配置
