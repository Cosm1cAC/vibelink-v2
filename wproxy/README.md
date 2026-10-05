# wproxy

自建代理控制台：**开源 mihomo 内核 + 自研控制脚本 + 开源 WebUI（zashboard）**。
2026-10-06 已完成对 CuteCloud 的替代：系统代理接管至 127.0.0.1:7897，原 CuteCloud 已卸载（服务与残留全部清除）。

## 目录结构

| 文件 | 说明 |
| --- | --- |
| `mihomo.exe` | 开源内核（MetaCubeX/mihomo v1.19.32 官方 release） |
| `wintun.dll` | TUN 模式驱动（wintun 官方 0.14.1） |
| `config.yaml` | 全部配置：节点 / 规则 / DNS / TUN（明文，可审计、可被 agent 直接修改） |
| `geosite.dat` / `geoip.metadb` | 分流规则数据库（MetaCubeX meta-rules-dat） |
| `ui/` | zashboard 开源面板（Zephyruso/zashboard） |
| `ui-patch/` | zashboard 补丁：Mac 监控面板（`apply-ui-patch.ps1` 幂等注入，`download-assets.ps1` 自动应用） |
| `config.ps1` | 端口与 API 密钥 |
| `mihomo-run.ps1` | 看门狗（保活+停止请求处理），由计划任务以最高权限运行 |
| `wproxy.ps1` | 控制台 CLI（安装/启停/状态/热重载/模式切换/选节点/测延迟） |
| `logs/` | 看门狗与内核日志 |

## 快速开始

```powershell
cd C:\Users\22771\Desktop\项目\proxy-stack\wproxy

# 安装（注册自启计划任务，会弹一次 UAC，仅此一次）
pwsh -ExecutionPolicy Bypass -File .\wproxy.ps1 -Action install

# 查看状态（任务/进程/端口/系统代理/TUN/出口IP/当前节点）
pwsh -ExecutionPolicy Bypass -File .\wproxy.ps1 -Action status
```

## 模式切换（系统代理 / TUN 全局，随时切换）

```powershell
.\wproxy.ps1 -Action mode -Value sysproxy   # 浏览器等走系统代理；指向 127.0.0.1:7897
.\wproxy.ps1 -Action mode -Value tun        # TUN 全局（含游戏 UDP）；会自动关系统代理
.\wproxy.ps1 -Action mode -Value off        # 全部关闭
```

## 常用命令

```powershell
.\wproxy.ps1 -Action select -Value "Japan-MacMini"   # 切换节点
.\wproxy.ps1 -Action delay                           # 测节点延迟
.\wproxy.ps1 -Action reload                          # 改 config.yaml 后热重载（秒生效）
.\wproxy.ps1 -Action ui                              # 打开 WebUI
.\wproxy.ps1 -Action stop / start / restart          # 停止 / 启动 / 重启（无需管理员）
.\wproxy.ps1 -Action uninstall                       # 卸载
```

## 从 CuteCloud 迁移（已完成，2026-10-06）

- 系统代理已切换至本方案（`mode -Value sysproxy` → 127.0.0.1:7897），验证出口 = 日本 Mac 后卸载了 CuteCloud
- 卸载内容：3 个官方卸载器静默执行、`CuteCloudHelperService` 服务删除、`C:\Program Files\CuteCloud` 与用户数据残留（`%APPDATA%\cutecloud`、`%LOCALAPPDATA%\CuteCloud`、`cutecloud-updater`）全部清除
- 如需回退：重新安装 CuteCloud 并开启其系统代理（不推荐；TUN/虚拟网卡勿与本方案同时开）

## Mac 监控面板（2026-10-06）

WebUI（`http://127.0.0.1:9097/ui/#/settings`）右下角集成 Mac 后台监控面板：

- 指标：CPU / GPU / 内存 / 硬盘 / 实时带宽（物理网卡汇总，↓↑ 分开）/ 链路 RTT / Mac 出口延迟（1.1.1.1、8.8.8.8、223.5.5.5）/ 负载 / 开机时长；带迷你曲线
- 数据源：`macos-metrics` 项目的用户态指标代理（`http://100.105.59.31:18992/metrics`，仅 Tailscale 网段，CORS 已开）
- 实现：`ui-patch/mac-monitor.js`（Shadow DOM 隔离样式，fetch 轮询 2s）+ `ui-patch/apply-ui-patch.ps1`（注入 index.html、替换 sw.js 为自毁版以清掉 zashboard PWA 缓存）
- 显示范围：默认仅 `#/settings` 页显示；改 `ui-patch/mac-monitor.js` 顶部 `CFG.showWhen = 'always'` 可全局显示

## 界面语言（简体中文）

`ui-patch/lang-zh-cn.js` 在应用初始化前把 zashboard 语言一次性设为简体中文（写入 `localStorage['config/language'] = 'zh-CN'`，带标记位，仅首次强制；之后可在 设置 → 通用 → 语言 自由更改，不会被再次覆盖）。由 `apply-ui-patch.ps1` 与监控面板一并注入。

## 验证记录（2026-10-06 实测）

- ✅ 出口：TCP/UDP 均日本 `130.62.209.98`；UDP ASSOCIATE 测试通过
- ✅ 规则：`bilibili.com / qq.com / tencent.com` → DIRECT（0.2s）；`google.com / youtube.com` → PROXY[Japan-MacMini]
- ✅ TUN：全局模式谷歌直连 200（与 Tailscale 共存、SSH 不受影响、分流正确）
- ✅ WebUI：http://127.0.0.1:9097/ui/ 正常
- ✅ 保活：看门狗互斥运行；stop 通过 `stop.request` 机制由提权看门狗执行（普通权限可停）
- ✅ 2026-10-06：系统代理接管验证（ProxyEnable=1 → 127.0.0.1:7897，系统级出口 `130.62.209.98`）；CuteCloud 卸载后出口不变
- ✅ 2026-10-06：Mac 监控面板——agent JSON 从 Windows 实测可取（cpu/gpu/mem/disk/net/latency 全字段）；mihomo `/ui/` 已含补丁，`/ui/mac-monitor.js` 200

## 已知注意点

- TUN 模式下节点已内置 `interface-name: "Tailscale"`（否则 mihomo 出站会被绑到物理网卡、连不上 Tailscale 网段节点 → 死循环超时）
- DNS fallback 已配置为 `#PROXY`（经代理查询），避免 8.8.8.8 直连被墙超时
- `status` 停止/运行显示基于 API + 进程名（提权进程命令行普通权限读不到，属正常）
- uninstall 删除计划任务可能需要管理员权限（普通权限会提示）

## 来源与校验（SHA256）

```
mihomo v1.19.32 (windows-amd64)  1AC84E795B5B915446E20139677FB6CC013A0778E876E8D4C96F994750B4C4A3
zashboard v3.29.1 (dist-no-fonts) 21371CD111B6B3D87774F3EA3DDEACDCB0F6AFF8690A16D652DAA3EA6E3B0145
wintun 0.14.1                     07C256185D6EE3652E09FA55C0B673E2624B565E02C4B9091C79CA7D2F24EF51
```

## 依赖

- 节点侧：Mac 上的 socks-relay（100.105.59.31:7891，TCP+UDP，见 macos-udp-proxy 项目）
- 备用节点：SSH 隧道（127.0.0.1:1080，仅 TCP，见 macos-socks-proxy 项目）
- 监控面板：Mac 上的 macos-metrics 指标代理（100.105.59.31:18992，见 macos-metrics 项目）
