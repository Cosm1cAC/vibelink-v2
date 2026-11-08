# vibelink —— Rust 版控制面（hub / agent / GUI）

Node 版 `../mesh/` 的替代实现，协议不变（camelCase JSON + 同样的 REST 路径），
所以两边可以混跑、逐个切换。

## 构建

```powershell
cargo build --release
# 产物：
#   target/release/vibelink-hub.exe    ~690 KB
#   target/release/vibelink-agent.exe  ~300 KB
#   target/release/vibelink-gui.exe    ~10 MB
```

需要 Rust ≥ 1.85（edition 2024）。macOS 上直接 `cargo build --release` 即可，无需交叉编译。

## 运行

```powershell
# Hub（PC，常驻）
$env:VIBELINK_PORT='18995'; $env:VIBELINK_DATA='.\data'
.\target\release\vibelink-hub.exe

# Agent（每台设备）
$env:VIBELINK_HUB='http://192.168.137.1:18995'
$env:VIBELINK_TOKEN='<data/token.txt>'
$env:VIBELINK_DEVICE='laptop'
.\target\release\vibelink-agent.exe

# GUI（Windows / macOS）
.\target\release\vibelink-gui.exe
# 配置存在 %APPDATA%\vibelink-gui\config.json
```

## 内存实测（Windows 11 / Node 25 对照）

| 进程 | Node 版 | Rust 版 |
| --- | --- | --- |
| hub | 79.5 MB | **6.7 MB** |
| agent | 86.5 MB | **7.5 MB** |
| GUI | — | 129 MB（egui + OpenGL 驱动，窗口大小影响很小） |

GUI 的 129MB 主要是 GL 驱动固定开销（关掉中文字体也有 114MB），
如果不接受，后续换成 Slint 软件渲染或有原生控件的框架；hub/agent/协议不用动。

## 已实现

- 群聊持久任务：Hub 主脑路由、执行、只读审校与验收；追问续接原任务，手机退出和服务重启后恢复。现增加用户授权记录、目录互斥/独立目录并行、任务预算与归档导出，需要 Agent ≥ 0.4.0；[方案、API 与后续阶段](docs/group-work-roadmap.md)。
- Hub：设备注册 / 心跳 / 离线判定 / 事件流 / 命令队列 / 配置版本库 / `/phone/<name>?t=` 下载
- Agent：IP / 网关 / 系统代理 / 端口探测 / 出口 CONNECT 延迟 / 内存 / 运行时长
- Agent 动作（Windows）：`set-proxy` `clear-proxy` `restart-service`（白名单）`probe`
- 自愈引擎（hub）：`rules`（把某台设备的系统代理指向"当前健康出口"的 IP:port，出口离线自动回落）
  + `watchdogs`（端口不通就重启白名单服务），**默认 `dryRun: true`**，只记事件不动手
- GUI：拓扑、设备列表、详情、事件、设置（hub+token）、动作按钮、配置库（复制链接/上传）
- GUI 顶栏：hub 状态 + **Tailscale 状态 chip**（`● Tailscale 100.x` / `需登录` / `NoState`，点击拉起托盘并重连）
- GUI 托盘：点 × 隐藏到托盘（左键唤回，右键菜单 显示/退出），托盘提示显示「在线数 + 当前出口」；
  `--minimized` 启动时直接隐藏（开机自启用）

## 安装 / 自启

见 [`install/README.md`](install/README.md)：Windows 用 `install-windows.ps1`（计划任务），
macOS 用 `install-macos.sh`（LaunchAgent）。脚本幂等，带卸载。

### 一个 watchdog 管三样（当前 PC 的用法）

`C:\Users\22771\bin\vibelink-watchdog.ps1`，计划任务 `VibelinkWatchdog`（登录触发、
失败重启 3 次 × 1 分钟、隐藏窗口），每 20 秒检查：

- `vibelink-hub` 进程 + 18995 端口，挂了就 `run-hub.cmd`
- `vibelink-agent` 进程，挂了就 `run-agent.cmd`
- Tailscale：`tailscale status --json` 不是 `Running` 就拉起 `tailscale-ipn.exe`；
  托盘在但 `NoState` 时每 5 分钟 `tailscale up` 一次；`NeedsLogin` 只记日志

日志：`%LOCALAPPDATA%\vibelink-mesh\watchdog.log`。

两个 Windows 上的坑：

1. **tailscaled 的后端跟着 IPN 客户端走**。托盘 `tailscale-ipn.exe` 一退出，
   `BackendState` 立刻变 `NoState`，`tailscale up` 也会「成功但没状态」。
   watchdog 必须先保证托盘进程在，不能只 `up`。
2. **PowerShell 5.1 不认 UTF-8 无 BOM**。启动文件夹的 VBS 用 `powershell.exe` 拉起脚本，
   中文注释会被按 ANSI 解析，直接 `UnexpectedToken` 退出。脚本必须存成 UTF-8 with BOM；
   单实例保护用命名互斥体，不要扫命令行（父 shell 的命令里也会带脚本名）。

## 待办

- macOS 的 `set-proxy` / `clear-proxy`（`networksetup -setwebproxy`）
- 自愈引擎的实战切换（需先停掉 `PcProxyFailover`，否则两边都写系统代理）
- opencode 会话互连
- ~~持久化安装~~（P5 已提供脚本，待实际部署）




## 会话面（手机接管 PC 上的 opencode 会话）

### 接口（opencode v2，已实测）

```
GET  /api/session                会话列表
GET  /api/session/:id            会话详情
GET  /api/session/:id/message    消息（reasoning / 工具调用 / 文本）
GET  /api/event                  SSE 实时流
鉴权：HTTP Basic，username=opencode，password 每次启动随机生成并打印到 stdout
```

### 已知取舍：agent 自己拉一个 opencode serve

- 用户 TUI 启动的 `:4097` 实例，密码是启动时随机生成、只打印一次的，**拿不到**；
- 所以 agent 自己启动并管理 `:4098`（密码落到 `data/opencode.json` 复用），
  它和 TUI 共用同一个 `opencode.db`，**看到的是同一批会话**；
- **代价**：手机端发 prompt 后，TUI 侧不一定实时刷新（两个 server 实例各有各的 SSE），
  通常切换一下会话/刷新即可看到。要做到真正实时，得让 TUI 连 agent 启动的那个 server，
  需要改 TUI 启动方式，风险大，暂不做。
- 另一个风险：两个实例同时写同一个 SQLite（WAL 模式），并发写有极小概率冲突；
  真出问题再改成「agent 独占 + TUI 通过 `OPENCODE_SERVER_URL` 连过来」。

### 手机端（mika 0.7.0：agent 优先）

抽屉 →「远程会话」：**agent → 会话列表 → 消息**（含 reasoning，灰字）/ 输入框发送 /「停止」。

第一层是 agent 入口，第二层才是该 agent 的会话，按设备分组（`pc 50 · laptop 50`）：

```
opencode      57       pc 50 · laptop 50 · mac 12
Codex         2        pc 2
Claude Code   0        暂无会话
Antigravity   0        Antigravity 已安装，但还没有产生会话记录
```

数据来源（`crates/agent/src/agents.rs`）：

| agent | 来源 | 说明 |
|---|---|---|
| opencode | 本地 `opencode serve` HTTP API | 已有，`/api/session` |
| Codex | `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl` | 首行 `session_meta` 取 id/cwd，`response_item` 取消息 |
| Claude Code | `~/.claude/projects/<slug>/<uuid>.jsonl` | 有文件才出现 |
| Antigravity | `%APPDATA%\Antigravity\User\globalStorage\state.vscdb` | 还没解析 SQLite，先只报「有没有数据」 |

opencode 的每条会话额外带两个字段（老 agent 没有，手机端默认 `running=false`、`model` 为空）：

| 字段 | 来源 | 用途 |
|---|---|---|
| `running` | `/api/session/active` 的 key | 手机消息列表的绿点 |
| `model` | `/api/session` 的 `model.providerID/model.id` | agent 资料页显示当前模型 |

两个细节：

- Codex 的 rollout 会把 AGENTS.md、系统提示、`<app-context>` 当成 user 消息写进去。
  过滤规则是「只丢纯注入」（`<` 开头、`You are Codex`、`# AGENTS.md`），
  但 `# Files mentioned by the user:` 只是标题，正文要保留；显示前用 `strip_headers` 去掉标题行。
  标题取首条真实用户消息里**最长的一行**（注入的元信息通常很短）
- 消息页不能放在 `PageScaffold` 里：它的内容区自带 `verticalScroll`，`weight(1f)` 拿不到高度，
  列表会被压成 0 高（表现为「没有消息」）。消息页是独立全屏布局：TopBar + LazyColumn(weight) + 输入框
- 三台设备**并行**查询、谁先返回谁先上屏；串行时会被最慢的一台拖到十几秒
- 老版本 agent（0.2.2）没有 `sessions.list`，手机端会回退到旧的 `/api/sessions`，
  只列 opencode——设备不会凭空消失（`mac` 目前就是这种情况，需要在那台机器上重编 agent）

接口：

```
GET /api/sessions/all?device=pc                  → { agents: [{id,name,note,sessions:[...]}] }
GET /api/sessions/{dev}/{agent}/{sid}/messages   → opencode / codex / claude
GET /api/sessions/{dev}/opencode/{sid}/messages?cursor=… → 更早一页（服务端分页）
```

分页由 opencode 的 cursor 提供：第一页 `?limit=120&order=desc`，返回原样的
`{data, cursor:{next}}`；带 `cursor` 时不能再带 `order`（opencode 会拒绝）。
手机端「载入更早」把 `cursor.next` 原样传回来，hub 只透传，agent 负责 URL 编码。

### 调研：多端会话接管都在怎么做

动手前看了几个开源实现，结论是「按 agent 分会话」是共同做法，差别在传输和进程模型：

- **VibeTunnel**（amantus-ai，4.7k）：浏览器接管终端会话，会话是唯一实体，agent 只是标签；
  重点是「attaching 到还在跑的进程」，而不是历史列表
- **AgentAPI**（coder，1.5k）：一个 agent 进程一个 HTTP API（Claude/Goose/Aider/Gemini/Amp/Codex），
  消息、状态都从进程拿——我们的 opencode 通道就是这一类
- **Happy Coder**（slopus，551）：CLI 直连手机，一条 CLI 会话一条加密通道，不做历史聚合
- **Omnara**：托管式，会话挂在 project 下面

对我们的取舍：历史会话是文件（Codex/Claude）或本地服务（opencode），没有「正在运行的进程」
可接管，所以走「agent 分组 + 会话列表 + 消息」这条，而不是 VibeTunnel 的 attach 模型。
用户要求的入口顺序（先 agent 再会话）也正好和 AgentAPI 的「一个 agent 一个来源」对齐。

- 只有跑着装新版 agent 的机器才能接管：`pc` 已是 0.2.3；
  `laptop` 会自动更新到 0.2.3；`mac` 还是 0.2.2，只能列 opencode（回退路径）
- 手机上的 token 一旦在设置里保存过就优先用保存值，不会再回落到内置值

## agent 分发与自更新

hub 现在就是 agent 的分发点，三台机器都能自己升级，不用再 SSH 或手动拷文件。

### 发布一个新版本

```
1. 改 crates/agent/src/main.rs 里的 const AGENT_VERSION = "x.y.z";
2. Windows: cargo build --release -p vibelink-agent
   macOS:   在 mac 上 cargo build --release -p vibelink-agent（不能交叉编译）
3. 把二进制放进 hub 的数据目录：
   data/agent/vibelink-agent-windows-x86_64.exe
   data/agent/vibelink-agent-macos-aarch64
   并把同名的 <target>.json 里的 version 改成 "x.y.z"
   （target = {os}-{arch}，如 windows-x86_64 / macos-aarch64）
4. 等最多 30 分钟，或者随便重启一台 agent。
```

接口：

```
GET /api/agent/latest?target=windows-x86_64   → {version, file, path, size}
GET /agent/<file>                             → 二进制（token 鉴权）
```

### agent 怎么做

- 启动 20 秒后查一次，之后每 30 分钟查一次；
- 版本不同就下载到同目录 `<exe>.new`，校验大小 + 可执行文件魔数（MZ / ELF / Mach-O），
  不对就放弃，宁可不开级也不把机器搞砖；
- Windows：写一个 `%TEMP%\vibelink-self-update.cmd`，等本进程退出 → 备份 `.bak` →
  `move` 覆盖 → `schtasks /run /tn VibelinkAgent`（task 还在 Running 就重试，最多 20 次），
  然后自己 `exit`；
- macOS：`sh -c` 等进程退出 → `cp` 备份 → `mv` 覆盖 → `chmod +x` → 跑
  `~/bin/vibelink-agent-start.sh`；
- 失败时保留旧二进制，只是不升级。

### 踩过的坑：日志别用 cmd 重定向

第一版 `run-agent.cmd` 是 `"...\vibelink-agent.exe" >> agent.log 2>&1`。
agent 会拉起 `opencode serve`，**子进程继承了那个重定向句柄**，agent 被杀之后
opencode 还活着、日志文件一直被占用 → 下次启动 cmd 打不开日志 → agent 根本起不来
（task 显示 result 0、进程是空的）。自更新正好会走“退出再启动”这条路，所以必须避开。

现在：`run-agent.cmd` 只设环境变量然后直接跑 exe，**agent 自己写 `<mesh 根>/agent.log`**
（Rust 打开文件带共享位，`opencode` 不会再锁住它），超过 4MB 自动轮转成 `agent.log.1`。

## 手机用 PC 上的模型（magpie 代理）

magpie 只监听回环（`127.0.0.1:3425`），手机连不上，所以 hub 转了一层：

```
手机  →  http://192.168.137.1:18995/api/llm/v1/...   (Bearer = hub token)
hub   →  http://127.0.0.1:3425/v1/...                (Bearer magpie)
```

- 支持流式（SSE 原样透传，边生成边显示）；
- base 写 `/api/llm` 或 `/api/llm/v1` 都行，缺 `v1/` 会自动补；
- 鉴权接受 `x-mesh-token`、`?t=`、`Authorization: Bearer <hub token>`；
- 手机端不需要知道 magpie 的 key。

mika 的「模型」里选 OpenAI 兼容 / 自定义，填：

```
Base URL:  http://192.168.137.1:18995/api/llm/v1
API Key:   <hub token，见 data/token.txt>
Model:     auto/deepseek-v4.1-flash
```

## 手机代理 & 公网入口（不用再装 CMFA/Tailscale）

### App 内代理（mika 0.4.2）

设置 → 网络代理，三档：

- **关闭**：全直连
- **自动**：向 hub 要 `/api/devices`，找一台 `proxy.enabled` 的机器，用它的**局域网 IP + proxy.server 端口**
  （笔记本上报的是 `127.0.0.1:7897`，自动换成 `192.168.137.156:7897`），不写死 IP
- **手动**：自己填

作用范围：Mika 所有 OkHttp 客户端（模型、模型目录、下载、更新、ChatGPT 登录）和内置浏览器（WebView
ProxyController）。局域网、`*.vibelink.cloud`、hub、Tailscale 段一律直连，所以代理挂了也不影响看会话。

**注意**：这只覆盖 Mika 自己。整机全局（其他 App 也走代理）见下面的「整机代理」。

### 整机代理（mika 0.6.0，mihomo + VpnService）

手机上跑 mihomo 内核（`libclash.so`，CMA 的 `cfa/native`），由 `ClashVpnService` 建 TUN
接管整机流量。手机侧**不需要 Tailscale**：hub 在 PC 上多开了两个 TCP 网关，手机连过来就行。

```
手机 App/其他 App
   │  (TUN, 172.19.0.1/30, DNS 劫持 172.19.0.2:53)
   ▼
mihomo（fake-ip + 规则）
   ├─ 100.64.0.0/10  →  pchub   → PC:18996  → PC 直接连 tailnet（PC 本来就在里面）
   └─ 其余            →  pcexit  → PC:18997  → 笔记本 mihomo:7897 → HY2 → 外网
```

为什么出口要绕到 PC：HY2 是 **QUIC/UDP**，手机侧的 mihomo 没法经 TCP 网关拨号
（`dialer-proxy` 会报 `no support`），所以出口链在 PC 上终结，手机只做 TUN/分流。

hub 侧实现（`crates/hub/src/main.rs`）：

| 端口 | 函数 | 行为 |
|---|---|---|
| 18996 | `tcp_gateway` | `CONNECT host:port` → PC 直接连（用它访问 tailnet 上的 100.x） |
| 18997 | `tcp_exit_gateway` | `CONNECT host:port` → 链到 `VIBELINK_EXIT_UPSTREAM`（默认 `100.123.253.85:7897`） |

两个网关都要求 `X-Mesh-Token: <hub token>`，否则 401；CONNECT 头之后同包里带的
首段数据会一起转发（否则客户端半关闭会丢响应）。

真机实测（PJX110 / Android 16）：

```
curl https://www.gstatic.com/generate_204          204   0.42s
curl https://www.youtube.com                       200   2.86s
curl https://api.ipify.org                         130.62.209.98
笔记本 7897 的出口 IP                              130.62.209.98   ← 一致，确实走 HY2
nc 100.98.206.126:18995/api/health                 {"devices":3,...}  ← tailnet 直达
```

App 侧要点：

- `ClashConfig.write()` 从 `RemoteStore.hub()` 取网关地址、从 `RemoteStore.token()` 取 token，
  不写死 IP
- `ClashVpnService` 会记住开关状态，开机由 `BootRestoreReceiver` 自动恢复（授权是持久的）
- `nativeStartTun` 的 `gateway` 必须带前缀（`172.19.0.1/30`），否则 Go 侧
  `netip.ParsePrefix` 失败、tun 根本建不起来；JNI 现在会返回 0/1 并打日志
- 启动前先探测 `:18997` / `:18996`，不通就不建 tun（否则等于把手机网掐了）
- 运行中每 20 秒探一次网关，连续两次不通就自动关代理并提示，手机网络立刻恢复
- 自动关掉之后会按 1/2/3/4/5… 分钟退避重试（`AlarmManager`），最多 10 次（约 40 分钟）；
  PC 一回来就自己重新接管，手动关代理会取消重试（`VpnRetryReceiver`）
- 同时注册 `ConnectivityManager.registerDefaultNetworkCallback`（`VpnNetWatcher`）：
  网络一变（Wi-Fi 回来、切流量）就立刻探测并重连，不用等下一次闹钟；
  回调在探测前必须切到后台线程（主线程会 `NetworkOnMainThreadException`）
- 重试的探测必须 `goAsync()` + 后台线程：`onReceive` 在主线程，直接 connect 会抛
  `NetworkOnMainThreadException`，导致永远判定“网关不可达”
- tun 的 fd 交给内核后必须 `detachFd()`：`sing_tun` 在 `stopTun` 里会关闭它，
  Java 再 `ParcelFileDescriptor.close()` 就是二次关闭，**fdsan 会直接 abort 进程**

真机实测的完整自愈链路：

```
17:39:57 停掉 hub
17:40:03 unreachable (1)
17:40:23 unreachable (2) → 自动关闭 + retry #1 in 60s
17:40:47 恢复 hub
17:42:08 probe exit=true hub=true → gateway back, restarting vpn
17:42:08 tunnel up
```

已知限制：

- 出口只走 TCP。QUIC/UDP 的 App 会回落到 TCP，纯 UDP 业务（部分游戏）不通
- 网关地址来自 hub（默认 `192.168.137.1`）。手机不在热点网段、PC 又没开公网入口时，
  代理会自动拒绝启动；如果是在运行中失联，40 秒内自动关闭
- 依赖笔记本在线（出口在那台机器上）；笔记本离线时所有流量都会失败，只能等它回来

### hub 的公网入口

```
https://hub.vibelink.cloud   →  cloudflared 隧道 → 127.0.0.1:18995
```

- 隧道 id `2f0629bd-…`（名称 `vibelink-hub`），配置在 `%USERPROFILE%\.cloudflared\config.hub.yml`
- 开机自启：计划任务 `VibelinkHubTunnel`（wscript 隐藏窗口 → `%LOCALAPPDATA%\vibelink-mesh\run-hub-tunnel.cmd`）
- 鉴权还是 hub token，公网进来一样要 `?t=` 或 `Authorization: Bearer`
- hub 生成下载地址时看 `x-forwarded-proto`，所以经隧道拿到的是 **https** 链接

App 侧的自动回退：

- `RemoteApi`：默认 hub（热点内网）连不上时，自动用 `https://hub.vibelink.cloud` 重试
- `UpdateChecker`：内置更新地址失败时，用公网清单再试一次
- 所以手机上不用再开 Tailscale 也能看会话、收更新

手机连接（开发用）：`adb tcpip 5555` 之后固定用 `adb connect 192.168.137.46:5555`，
不再依赖「无线调试」的随机端口（Wi-Fi 一断系统会关掉无线调试，端口就变了；配对记录仍在）。
手机重启后需要重新执行一次 `adb tcpip 5555`。

### 模拟器测试（没有真机时）

本机已有 AVD `Codex_API_36`（x86_64 / android-36）。APK 只有 arm64 原生库，
所以模拟器上 **clash 内核加载失败**（`UnsatisfiedLinkError: libclash_bridge.so`），
VPN / 整机代理不能测；远程会话、代理设置、更新这些纯 Dart/Compose 部分可以正常测。

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\emulator\emulator.exe" -avd Codex_API_36 -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect -no-audio
& "$sdk\platform-tools\adb.exe" -s emulator-5554 install -r app-debug.apk
```

驱动方式：`mobile-next/mobile-mcp`（已写进 `~/.config/opencode/opencode.json` 的 `mcp.mobile`，
下次开会话生效）；不接 MCP 时直接 `adb` + `uiautomator dump` 也够用。

### 消息渲染

消息正文用轻量 markdown（`RemoteScreen.kt` 里的 `parseBlocks` / `inline`）：
标题（`#`/`##`/`###`）、粗体（`**x**`）、行内代码（`` `x` ``）、列表（`- `）、代码块（```）。
不引第三方库，够手机上看会话。

一个坑：hub 的 axum 默认请求体上限是 **2MB**，大会话（3.8MB JSON）回传会被拒，
表现为 `agent pc timeout`。已在 router 上加 `DefaultBodyLimit::max(64MB)`，
agent 侧也加了 `tail_messages`（超长会话只回最后 120 条）。

### 应用内更新为什么不能用 ACTION_VIEW

真机（OnePlus / ColorOS）上踩了两个坑：

1. APK 的 MIME 是 `application/vnd.android.package-archive`，隐式 `ACTION_VIEW` 会被
   **Termux 的文件接收器**（`com.termux.filepicker.TermuxFileReceiverActivity`）抢走，
   点更新完全没反应；机器上还同时有百度网盘、QQ 下载器注册了同一 MIME
2. 显式指定 `com.android.packageinstaller` 后能弹出安装器，但 ColorOS 的解析器
   会把 `44 > 43` 的升级误判成「已安装更高版本」，直接拒装

现在改成 **PackageInstaller 会话安装**（`ApkUpdater.install`）：

```
createSession(MODE_FULL_INSTALL) → openWrite 把 APK 写进会话 → session.commit(PendingIntent)
→ 系统回 STATUS_PENDING_USER_ACTION → UpdateInstallReceiver 拉起确认页
```

不依赖 intent 解析，也不会被第三方应用抢；ColorOS 的确认页会显示正确版本号。
会话失败时仍会退回「显式挑系统安装器」的 ACTION_VIEW 作为兜底。
排查日志：`adb logcat -s vibelink-update`（会打印 handlers 列表和最终选中的包名）。

会话消息的几个细节（0.7.7）：

- **思考默认折叠**：气泡里只显示「思考过程 · N 字」，点一下展开；只有思考、没有正文的气泡不显示
- **图片**：opencode 会把图片放在 `content[].uri`（`data:image/...;base64`），工具输出（比如读截图）
  里也有；解析时递归找 `content` / `state`，手机端直接 base64 解码渲染（>12MB 的跳过）
- **输入框**：复用主页的 `GlassBar`（胶囊 + 圆形发送/停止按钮），不再用 `OutlinedTextField`

键盘遮挡：应用是 edge-to-edge，`adjustResize` 在 Android 11+ 不会缩窗口。
消息页必须自己吃 `WindowInsets.ime`（`Modifier.imePadding()`），否则键盘会盖住输入框；
键盘高度变化时还要把列表滚到底（`LaunchedEffect(messages.size, imeBottom)`）。
