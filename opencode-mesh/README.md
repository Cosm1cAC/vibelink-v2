# opencode-mesh

多机 opencode 互访：每台机器常驻一个 headless `opencode serve`（自带 Web UI），经 Tailscale 互联；
任意设备的浏览器打开任一台的界面，用内置的 **Servers（多服务器）** 功能把其它机器加进来，
即可在一个界面里查看/操作所有机器上的 opencode 会话。

## 原理

- 每台机器：`opencode serve --hostname 127.0.0.1 --port 4097`（内部），前面加一层极简反向代理
  `mesh-proxy.py`（对外 `0.0.0.0:4096`）：HTML 注入角标脚本，其余流量（SSE/上传/下载/预检）原样透传
- 页面左下角**角标**：显示**真实链路**，不是按 RTT 猜。代理在 `/__mesh/path` 里按请求源地址
  分类（本机 / 局域网 / Tailscale / 公网），再查 `tailscale status --json` 的 `CurAddr` 与
  `Relay` 判断打洞直连还是 DERP 中继，5 秒缓存；RTT 只作为附加信息显示
  （Tailscale 同局域网自动直连、异地自动 DERP 中继）
- basic auth 保护，LaunchAgent / 启动文件夹保活
- 跨机访问必需 `--cors <对方 UI origin>`（实测：默认不带 `Access-Control-Allow-Origin`，浏览器会拦截）
- Web UI 原生支持多服务器：`Servers → Add server`（地址 + 用户名 + 密码），之后统一列出各机器的项目/会话
- 终端互操：`opencode attach http://<对方 Tailscale IP>:4096`

## 架构

```
PC  haoshao        100.98.206.126:4096  (mesh-proxy) ──> 127.0.0.1:4097 (opencode serve)
Mac liangmac-mini  100.105.59.31:4096 (mesh-proxy) ──> 127.0.0.1:4097 (opencode serve)
        ↑                      ↑
        └── 任一设备浏览器打开任一台 UI → Add server 聚合 ──┘
            （手机经 CMFA tsnode 入 tailnet，无需 Tailscale App；左下角角标显示直连/中继）
```

| 目录 | 部署位置 | 说明 |
| --- | --- | --- |
| `windows/` | Windows | 看门狗 + 安装/状态/卸载脚本（计划任务或启动文件夹自启） |
| `macos/` | Mac | 官方安装脚本 + LaunchAgent（while 守护循环）+ 状态/卸载脚本 |

## 部署

### Windows

```powershell
cd C:\Users\22771\Desktop\项目\proxy-stack\opencode-mesh\windows
pwsh -ExecutionPolicy Bypass -File .\install.ps1            # 默认：计划任务失败自动回退启动文件夹（无需管理员）
pwsh -ExecutionPolicy Bypass -File .\install.ps1 -Elevate   # 想注册计划任务（会弹一次 UAC）
pwsh -ExecutionPolicy Bypass -File .\status.ps1             # 查看状态
```

安装内容：生成服务密码（`~/.config/opencode-server/password`）、注册登录自启、停止旧 4096 监听、启动看门狗并等待健康。

### macOS

```powershell
# 在 Windows 上传脚本（Tailscale 可达时）
scp -i "$env:USERPROFILE\.ssh\id_ed25519" -r .\opencode-mesh\macos kobayashi@100.105.59.31:~/opencode-mesh/
ssh -i "$env:USERPROFILE\.ssh\id_ed25519" kobayashi@100.105.59.31 "sh ~/opencode-mesh/macos/install.sh"
```

安装内容：官方脚本安装 opencode 到 `~/.opencode/bin`、生成密码、写入并加载
`~/Library/LaunchAgents/com.opencode-server.plist`（`while true` 守护循环）、健康检查。

### 凭据同步（Mac 端要能跑模型，必须）

Mac 端需要与 Windows 相同的 provider 凭据（不入库，仅经 Tailscale 传输）：

```powershell
ssh kobayashi@100.105.59.31 "mkdir -p ~/.local/share/opencode ~/.config/opencode"
scp -i "$env:USERPROFILE\.ssh\id_ed25519" "$env:USERPROFILE\.local\share\opencode\auth.json" kobayashi@100.105.59.31:~/.local/share/opencode/auth.json
scp -i "$env:USERPROFILE\.ssh\id_ed25519" "$env:USERPROFILE\.config\opencode\opencode.json" kobayashi@100.105.59.31:~/.config/opencode/opencode.json
```

## 使用

1. 任意设备浏览器打开任一台（basic auth：用户名 `opencode`，密码见该机 `~/.config/opencode-server/password`）：
   - `http://100.98.206.126:4096`（PC）
   - `http://100.105.59.31:4096`（Mac）
2. `Servers → Add server`：把另一台加进来（地址、用户名 `opencode`、密码同上）
3. 之后一个界面里即可切换/聚合所有机器的项目与会话
4. 终端场景：`opencode attach http://100.105.59.31:4096`（从 PC 操作 Mac 的会话，反之亦然）

## 管理命令

| 平台 | 安装 | 状态 | 卸载 |
| --- | --- | --- | --- |
| Windows | `install.ps1`（`-Elevate` 走 UAC 计划任务） | `status.ps1` | `uninstall.ps1`（`-KeepPassword` 保留密码） |
| macOS | `install.sh` | `status.sh` | `uninstall.sh`（`--keep-password` 保留密码） |

## 已知问题与注意

- **CORS**：跨机访问必须 `--cors`（实测无 `Access-Control-Allow-Origin` 头浏览器直接拦）。新增设备时，
  把新 origin 加进**两端**白名单（Windows `windows/config.ps1`、Mac `macos/com.opencode-server.plist`）
- **角标**：反映「地址栏那台主机」的路径（在页面内切到另一台 server 时不跟着变）；
  手机首次部署后如看不到角标，强刷一次（PWA/Service Worker 缓存）。
  `path=unknown` 表示本机没有 Tailscale CLI 或该 peer 不在 `status` 里，不是故障
- **手机速度**：Tailscale 自动选路——手机与 PC 同一局域网时自动升级**直连**（实测 6~26ms，Web UI 流畅），
  异地时自动回退 DERP 中继（实测 ~500ms，首屏/长历史会话会卡）。同一个 URL（`100.98.206.126`）通吃，
  无需维护局域网地址；排查当前路径：`tailscale ping phone-mihomo`（`direct` 还是 `via DERP`）
- **fff 搜索 bug**：以 home 目录为索引根时搜索恒为空（上游 issue #41456），两端均设
  `OPENCODE_DISABLE_FFF=1` 回退 ripgrep 实现
- **Mac 防火墙**：当前实测 ALF 未拦截（PC 可直连 Mac 4096）；若将来被拦（未签名程序入站），
  可改为 opencode 绑 `127.0.0.1` + python3 端口转发对外（与 socks-relay 同思路）
- **密码**：不入库，存放于各机 `~/.config/opencode-server/password`（Windows 为 `%USERPROFILE%\.config\opencode-server\password`）
- **版本**：opencode 1.18.34（两端一致）；`opencode serve` 自带 Web UI，无需 `web` 子命令

## 验证记录（2026-10-06）

- ✅ Windows：看门狗 pid 保活、`0.0.0.0:4096` 监听、`/global/health` OK、CORS 预检返回 ACAO
- ✅ macOS：opencode 1.18.34 安装成功、LaunchAgent 加载、本机 health OK
- ✅ PC → Mac：`/global/health` 200；预检 ACAO=`http://100.98.206.126:4096`
- ✅ Mac → PC：`/global/health` 200；预检 ACAO=`http://100.105.59.31:4096`
- ✅ 凭据同步：`auth.json`（open1code/opencode/open1code2/opencode-go）+ 自定义 provider `opencode.json`
- ✅ 徽标代理：两端 HTML 注入角标成功、SSE `/event` 流式透传正常、`/session` API 正常（2026-10-06）

## 安全说明

- 访问控制：Tailscale 内网 + basic auth 双重；密码仅存设备本地，不入库
- `auth.json` / `opencode.json` 含 API 密钥，仅经 Tailscale 在自有设备间同步，不入库
- 服务监听 `0.0.0.0`（同时覆盖 Tailscale 与局域网），不需要时可用 `--hostname 100.x.x.x` 只绑 Tailscale
