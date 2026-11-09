# 开发版内置 Clash

开发版通过本机构建参数提供私人配置，不把节点、授权密钥或实际配置提交到 Git。内测构建忽略该参数，不注册私人 assets 任务；运行时也拒绝生成或导入开发版配置。

## 本机构建

私人 YAML 与所需的 `Country.mmdb`、`geosite.dat` 放在仓库外或 Git 元数据中的私有目录。使用 GEOSITE/GEOIP 的配置必须随包提供相应数据库，首次导入不依赖 GitHub 下载。配置副本只写入被忽略的 build 目录。

```powershell
.\tools\clash\Build-DeveloperApk.ps1 -PrivateConfiguration <本机私人配置路径> -BuildCommit <已验证提交>
```

等价 Gradle 参数为 `-PdevClashConfig=<文件>`，也可使用进程环境变量 `VIBELINK_DEV_CLASH_CONFIG`。内测构建即使继承该环境变量，也不读取和打包私人文件。源码构建没有提供私人文件时保留 Hub 网关兼容方案，需要先登录开发版 Hub。

## 配置与授权

当前私人开发配置恢复既有日本 HY2 出口：手机内核的 Hysteria2 出站连接 Mac 的既有认证服务，Tailscale 仅作为到私有服务地址的 UDP 传输层，使用同一个内核，不需要另开一个手机 VPN。代理组仅提供 Japan-MacMini-HY2，国内直连规则保留；不再把 SOCKS5 节点作为日本主出口，也不再保留旧 SOCKS5 不支持 UDP 而添加的 UDP/443 拒绝规则。未验证的旧一次性 auth-key 不随新安装复用。

首次开启 VPN 由 Android 请求系统授权；首次进入 Tailnet 通过 Tailscale 官方网页授权，不要求手工填写 key。Tailscale 状态放在应用私有 core home 的稳定目录，配置 UUID 或应用版本变化不会更换该目录。授权链接只保存在服务内存中，界面显式点击才打开官方 HTTPS 地址，不携带 Hub 凭据。

开发版登录页提供“打开 Clash / 连接开发网络”，可以在尚未登录 Hub 时先建立 VPN；避免必须先登录 Hub 才能打开连接 Hub 所需的网络。面板依次启动 VPN、检测日本出口、点击出现的 Tailscale 授权入口，完成后返回登录。内测登录页不显示此私人网络入口。

若手机尚不能打开授权页面，可显式复制一次性授权链接，在已有网络的电脑完成授权，再返回手机检测。只有用户点击时才写入标记为敏感内容的剪贴板，不自动复制、发送或登录。

参考：[mihomo Tailscale 配置](https://wiki.metacubex.one/en/config/proxies/tailscale/)、[Tailscale auth-key 生命周期](https://tailscale.com/docs/features/access-control/auth-keys)。

## 导入与更新

- 打开面板及启动 VPN 时等待内核校验和导入完成，避免抢先显示空配置。
- 用配置内容摘要判断升级；服务连接和导入有超时，失败保留 pending UUID，以原导入恢复。
- 内置配置按独立 UUID 管理，不按可编辑名称覆盖配置。新版本保留旧配置；用户选择的其他配置及修改过的内置配置不被自动替换。
- 可在网络代理设置中显式恢复并选用内置配置。已有手动更新的 Geo 数据保留。

## 发布隔离

开发 APK 只发布到内网开发 Hub 的开发目录，不复制到公网 Hub 的镜像目录或 GitHub Release。内测 APK、更新清单继续使用公网通道。打包后用 `tools/clash/verify-apk-isolation.py` 对照私人源文件校验开发资产，并确认内测 APK 不含任何 `assets/private-clash/`。

## 1.6.3（94）发布验证

- 开发版 release 构建完成，签名与 1.6.2 一致，27 个原生库保留；包含私人配置和两份离线 Geo 数据。SHA256：`d7673b6b4bcd095e6488c2187fdeadf1fbaa95393b3cc00693382e62fd9ac98d`。
- 20 项配置策略、授权链接、更新通道及版别聚焦测试通过。内测构建在传入不存在的私人配置路径时仍成功，实际 APK 的私人资产数量为 0。
- 开发版已只发布到内网开发目录；公网两种 edition 查询此开发 APK 均为 404。公网内测通道保持 1.6.2-beta，本次内测 APK 仅用于隔离验证。
- 本机 mihomo 配置校验通过，现有日本 SOCKS5 出口 HTTPS 返回 200。没有消费旧 auth-key 或注册测试节点。手机未连接 ADB，真实手机的系统 VPN 授权与首次 Tailscale 登录尚需用户完成，不把服务端连通性测试等同于手机验收。

## 1.6.6 链路修复验证

- 两个 ABI 的现有内核均包含 Tailscale、tsnet 与 Hysteria2。服务启动前建立进程级日志捕获；同一 profile 的加载完成不再清除只打印一次的授权 URL，浏览器往返不会因 UI 解绑而停收授权信息。授权过期或曾经丢失时，可显式重新连接同一配置，保留认证状态目录并重新获取链接。
- 托管配置启用后自动做有间隔的 HTTPS 检测，只有本应用拥有的 VPN 真正启用才检测和重连；显示的是 YouTube HTTPS 可访问结果，不把 VPN 开关或响应成功当作地理位置证明。
- 相同 HY2 认证与节点设置已通过临时隔离测试：Mac Mihomo 到既有 HY2 再到 YouTube 返回 200；Windows Mihomo 通过仅本次测试使用的 localhost UDP 转发及现有 Tailnet，YouTube 连续两次 200，Google 204。临时进程已退出，没有修改 PC 系统代理、原服务或监听范围。
- Windows Mihomo 直接通过宿主 OS 的 Tailnet UDP 接口出现超时；这与手机使用的 tsnet 用户态 PacketConn 路径不同。手机真实授权及 VPN 流量尚需实机验证，不能把宿主测试等同于手机验收。
