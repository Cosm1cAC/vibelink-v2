# Antigravity 打不开 / 一直转圈 —— 根因与修复（2026-10-24）

## 现象

- Antigravity（桌面 App，Electron）窗口能起来，但界面一直加载不出来 / 卡在登录或空白；
- 自 2026-10-24 03:18 起，`language_server.exe` 全量报错，**一条都没成功过**；
- 同一时间段其他需要翻墙的软件（Telegram、GitHub、Google 搜索）是正常的 —— 说明**不是出口节点整体挂了**。

## 根因（三个叠加，其中第 3 条是主因）

### 1. language_server.exe 根本不读系统代理

日志里唯一一条代理相关记录是：

```
[RemoteControl] Resolved proxyServerURL: ""
```

此后所有请求都是 `dial tcp <公网IP>:443: connectex: A connection attempt failed...` ——
Go 程序在**建连阶段**直连，**完全没走 127.0.0.1:7897**（走了会显示 `dial tcp 127.0.0.1:7897`）。

原因：`config.yaml` 给 antigravity 用到的域名挂的是 `GEOSITE,geolocation-!cn → PROXY`，
但这只在**应用把请求交给代理**时才有意义。App 内部其实由三套不同的网络栈组成，各自策略不同：

| 组件 | 网络栈 | 是否走系统代理 |
| --- | --- | --- |
| Electron 外壳（自动更新、窗口加载） | Chromium | ✅ 走（日志里更新下载确实成功了） |
| `language_server.exe`（OAuth、模型、云代理） | Go `net/http` | ❌ 实际没走（日志已证明） |

### 2. Caddy 本地反代已经退化成死循环

`%APPDATA%\Caddy\autosave.json` 当前生效的配置是：

```json
{"match":[{"host":["daily-cloudcode-pa.googleapis.com","oauth2.googleapis.com",
                   "play.googleapis.com","www.googleapis.com","googleapis.com",
                   "antigravity-unleash.goog","lh3.googleusercontent.com"]}],
 "handle":[{"handler":"reverse_proxy","upstreams":[{"dial":"{http.request.host}:443"}]}]}
```

`upstreams.dial` 用的是 `{http.request.host}` —— 也就是 **把请求代理回它自己**（同一个域名）。
这只有在 `hosts` 里把该域名钉到某个第三方中转 IP 时才成立；而现在 `C:\Windows\System32\drivers\etc\hosts`
里**没有任何相关条目**。结果：

- 证书还在（`%APPDATA%\Caddy\certificates\local\` 下有 8 个域名的 Caddy 本地 CA 证书，签发于
  2026-06-26 03:34 ~ 11:34）；
- 但路由已经变成 **:443 → 自己:443** 的环；
- 如果系统信任了 Caddy 本地 CA（多半信任过，否则当初就不会这么配），任何走 :443 的请求都会
  自己跟自己握手，然后 TLS 失败 / 超时，**比直连还糟**；
- 顺带一提：这些证书都已过期（`notAfter = 2026-06-26 ...` 同日），而 App 现在是 2.21.1。

### 3. 版本跳变 + 自动更新反复重装（触发点）

`%APPDATA%\Antigravity\logs\main.log` 显示：

- 当初配这套 Caddy 反代时是 **2.2.1**（2026-06-26），当时的报错是 `ERR_CONNECTION_CLOSED`；
- 2026-10-09 升到 **2.21.1**，最后成功启动是 `2026-10-09 22:01`；
- 2026-10-24 03:18 起（换了校园网 → 有线）再次启动，全量失败；
- 03:18:35 发现新版本 **2.26.0**，之后**每小时**都重新下载一遍 2.26.0 并 `Update downloaded`，
  但只在退出时静默安装（`14:06:39 Executing ... --updated /S`）；
- 换句话说：**新版从没真正装上过**，用户看到的是 2.21.1，而 2.21.1 的 `language_server` 已经
  和当前后端 + 当前网络环境不兼容。

## 为什么"检查代理配置"这个方向看起来没问题、实际不成立

- 系统代理注册表指向 `127.0.0.1:7897`，节点 `Japan-MacHy2` 是活的；
- 但 `language_server.exe` 把请求发出去了**没经过 7897**，所以 wproxy 的规则（GEOSITE / 节点自动切换）
  对它**一行都不生效**；
- 日志里 `play.googleapis.com` 的 `i/o timeout` 和 `oauth2.googleapis.com` 的 `connectex ... failed`
  就是直连被墙的典型表现，与 wproxy 的节点质量**无关**。

## 修复步骤

按顺序做，前两步是必做项。

### 第 1 步：把 Antigravity 的流量真正送进 wproxy

给 Antigravity 进程注入代理环境变量（`language_server.exe` 由 App 拉起，会继承）：

```powershell
# 当前会话
$env:HTTPS_PROXY = 'http://127.0.0.1:7897'
$env:HTTP_PROXY  = 'http://127.0.0.1:7897'
$env:ALL_PROXY   = 'http://127.0.0.1:7897'   # 注意是 socks5:// 还是 http:// 视客户端；wproxy 的 7897 是 mixed，http 即可
$env:NO_PROXY    = 'localhost,127.0.0.1,100.64.0.0/10,*.ts.net'
```

长期方案选一个：

- **A（推荐）** 改快捷方式：`Antigravity.exe` 的属性 → 目标前加
  `cmd /c "set HTTPS_PROXY=http://127.0.0.1:7897 && set HTTP_PROXY=http://127.0.0.1:7897 && start "" "C:\Users\22771\AppData\Local\Programs\antigravity\Antigravity.exe"`；
  或者用 `antigravity-fix\launch-with-proxy.ps1` 启动。
- **B** 如果确认 Go 侧只认 `HTTP_PROXY`，再补 `ALL_PROXY`；
- **C** 验证：启动后看 `%APPDATA%\Antigravity\logs\language_server.log` 新行，出现
  `dial tcp 127.0.0.1:7897` 或不再出现 `dial tcp <公网IP>:443 ... failed`，即说明进了代理。

### 第 2 步：停掉退化的 Caddy 本地反代

```powershell
# 先看是谁在跑
Get-NetTCPConnection -LocalPort 443 -State Listen | Select-Object LocalAddress,OwningProcess
Get-Process caddy -ErrorAction SilentlyContinue

# 如果是 caddy：先备份配置再停
Copy-Item "$env:APPDATA\Caddy\autosave.json" "$env:APPDATA\Caddy\autosave.json.bak-20261024"
Stop-Process -Name caddy -Force      # 自启项也一并关掉，否则重启又回来
Get-ScheduledTask | Where-Object { $_.TaskName -match 'caddy' } | Select-Object TaskName, State
Get-CimInstance Win32_Service | Where-Object { $_.Name -match 'caddy' -or $_.DisplayName -match 'caddy' } |
    Select-Object Name, State, StartMode
```

如果它确实在监听 443，**必须先停掉再重启 Antigravity**，否则所有 HTTPS 都会撞上它。

> 提示：`antigravity-fix\diagnose.ps1` 会自动完成以上检查并打印结论，不需要你逐条判断。

### 第 3 步：让新版真正装上

`%LOCALAPPDATA%\antigravity-updater\pending\Antigravity-x64.exe` 已经存在（2.26.0）。
关掉 App，等它自动 `--updated /S` 安装完，或手动跑：

```powershell
Start-Process "$env:LOCALAPPDATA\antigravity-updater\pending\Antigravity-x64.exe" -ArgumentList '/S','--updated' -Wait
```

装完确认 `%APPDATA%\Antigravity\logs\main.log` 里的版本号变成 2.26.0，且不再每小时重复下载。

### 第 4 步（可选）：如果确实想用 Caddy 反代

那必须同时满足：

1. `hosts` 里把那 8 个域名钉到真正能连的落地点（现在的配置里没有任何条目，等于在代理自己）；
2. 重新签发证书（现有证书已于 2026-06-26 过期）；
3. 确认 Caddy 本地 CA 仍在系统「受信任的根证书颁发机构」里。

**不建议**再走这条路：本机已经有 wproxy（mihomo）能做同等事情，多一层本地 TLS 中间人只会
增加 2.21.1 → 2.26.0 这类版本升级后的全新失效点。**直接停掉 Caddy + 走 7897** 是最省事的。

## 验收

```powershell
# 1. 443 不再被本机进程占用
Get-NetTCPConnection -LocalPort 443 -State Listen -ErrorAction SilentlyContinue

# 2. 系统代理 + 出口 IP 正常
.\wproxy\wproxy.ps1 -Action status

# 3. Antigravity 能整页加载，日志里不再有连续 failed
Get-Content "$env:APPDATA\Antigravity\logs\language_server.log" -Tail 30
```

## 关联观察（不属于本次故障，但值得知道）

`wproxy\logs\watchdog-20261024.log` 显示 10-24 全天节点在
`throughput 0~58KB/s` 之间反复横跳、每 1~2 分钟切一次节点（`[auto]` 行 400+ 条）。
这是校园网国际出口劣化，**不影响 Antigravity 的判定**（因为它压根没走代理），
但会让你觉得"代理开着还是慢"。要根治得看 hysteria2 通道的 `bandwidth` 设置或换出口。
