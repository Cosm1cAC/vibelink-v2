# 开发版内置 Clash

开发版通过本机构建参数提供私人配置，不把节点、授权密钥或实际配置提交到 Git。内测构建忽略该参数，不注册私人 assets 任务；运行时也拒绝生成或导入开发版配置。

## 本机构建

私人 YAML 与所需的 `Country.mmdb`、`geosite.dat` 放在仓库外或 Git 元数据中的私有目录。使用 GEOSITE/GEOIP 的配置必须随包提供相应数据库，首次导入不依赖 GitHub 下载。配置副本只写入被忽略的 build 目录。

```powershell
.\tools\clash\Build-DeveloperApk.ps1 -PrivateConfiguration <本机私人配置路径> -BuildCommit <已验证提交>
```

等价 Gradle 参数为 `-PdevClashConfig=<文件>`，也可使用进程环境变量 `VIBELINK_DEV_CLASH_CONFIG`。内测构建即使继承该环境变量，也不读取和打包私人文件。源码构建没有提供私人文件时保留 Hub 网关兼容方案，需要先登录开发版 Hub。

## 配置与授权

当前私人开发配置沿用现有日本出口：手机内核的 Tailscale 出站连接 Mac SOCKS5。默认保留国内直连；日本 SOCKS5 的 TCP 出口经过只读连接验证。未验证的旧一次性 auth-key 不随新安装复用。

首次开启 VPN 由 Android 请求系统授权；首次进入 Tailnet 通过 Tailscale 官方网页授权，不要求手工填写 key。Tailscale 状态放在应用私有 core home 的稳定目录，配置 UUID 或应用版本变化不会更换该目录。授权链接只保存在服务内存中，界面显式点击才打开官方 HTTPS 地址，不携带 Hub 凭据。

参考：[mihomo Tailscale 配置](https://wiki.metacubex.one/en/config/proxies/tailscale/)、[Tailscale auth-key 生命周期](https://tailscale.com/docs/features/access-control/auth-keys)。

## 导入与更新

- 打开面板及启动 VPN 时等待内核校验和导入完成，避免抢先显示空配置。
- 用配置内容摘要判断升级；服务连接和导入有超时，失败保留 pending UUID，以原导入恢复。
- 内置配置按独立 UUID 管理，不按可编辑名称覆盖配置。新版本保留旧配置；用户选择的其他配置及修改过的内置配置不被自动替换。
- 可在网络代理设置中显式恢复并选用内置配置。已有手动更新的 Geo 数据保留。

## 发布隔离

开发 APK 只发布到内网开发 Hub 的开发目录，不复制到公网 Hub 的镜像目录或 GitHub Release。内测 APK、更新清单继续使用公网通道。打包后用 `tools/clash/verify-apk-isolation.py` 对照私人源文件校验开发资产，并确认内测 APK 不含任何 `assets/private-clash/`。
