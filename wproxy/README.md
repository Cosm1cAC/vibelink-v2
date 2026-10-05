# wproxy

自建代理控制台：**开源 mihomo 内核 + 自研控制脚本 + 开源 WebUI（zashboard）**，用于替代 CuteCloud。
与 CuteCloud 并行运行、互不冲突，验证满意后可卸载 CuteCloud。

## 目录结构

| 文件 | 说明 |
| --- | --- |
| `mihomo.exe` | 开源内核（MetaCubeX/mihomo v1.19.32 官方 release） |
| `wintun.dll` | TUN 模式驱动（wintun 官方 0.14.1） |
| `config.yaml` | 全部配置：节点 / 规则 / DNS / TUN（明文，可审计、可被 agent 直接修改） |
| `geosite.dat` / `geoip.metadb` | 分流规则数据库（MetaCubeX meta-rules-dat） |
| `ui/` | zashboard 开源面板（Zephyruso/zashboard） |
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

## 与 CuteCloud 切换

- 两套端口互不冲突（本方案 7897/9097/1055；CuteCloud 7890/9090/1053），可同时运行
- **切到本方案**：`.\wproxy.ps1 -Action mode -Value sysproxy`（或 `tun`）
- **切回 CuteCloud**：`.\wproxy.ps1 -Action mode -Value off`，然后在 CuteCloud 界面重新打开它的系统代理
- TUN 与 CuteCloud 虚拟网卡不要同时开

## 验证记录（2026-10-06 实测）

- ✅ 出口：TCP/UDP 均日本 `130.62.209.98`；UDP ASSOCIATE 测试通过
- ✅ 规则：`bilibili.com / qq.com / tencent.com` → DIRECT（0.2s）；`google.com / youtube.com` → PROXY[Japan-MacMini]
- ✅ TUN：全局模式谷歌直连 200（与 Tailscale 共存、SSH 不受影响、分流正确）
- ✅ WebUI：http://127.0.0.1:9097/ui/ 正常
- ✅ 保活：看门狗互斥运行；stop 通过 `stop.request` 机制由提权看门狗执行（普通权限可停）

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
