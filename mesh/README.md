# mesh —— 多设备 / 多 agent 控制面（v0.3）

把 `wproxy`、`metrics-hub`、`opencode-mesh`、`rshell` 这几块拼成一个统一的东西：
**一个常驻 Hub + 每台设备一个 Agent + 一套 Web GUI**。

```
                         ┌──────────── Hub (PC:18994) ────────────┐
                         │ 设备注册 / 心跳 / 链路探测 / 事件        │
                         │ 配置版本库 (mihomo / CMFA / env)        │
                         │ 命令队列 (切换出口 / 重启服务 / 探测)    │
                         └───┬──────────────┬──────────────┬─────┘
                     Agent(PC)        Agent(笔记本)     Agent(Mac/手机)
                     采集+执行          采集+执行          采集+执行
```

## 快速开始

```powershell
# 1) 启动 Hub（首次会生成 data/token.txt）
node mesh\hub\server.ts

# 2) 在另一台机器上启动 Agent
$env:MESH_HUB='http://192.168.137.1:18994'
$env:MESH_TOKEN='<token>'
$env:MESH_DEVICE='laptop'
node mesh\agent\agent.ts

# 3) 浏览器打开
http://192.168.137.1:18994/
```

## 手机接管 agent 会话

手机打开 `http://<hub>:18994/m.html`，填一次 token，然后：设备 → 会话 → 对话。
可以发消息（排队 / 插话）、停止当前回合、处理权限审批，断线自动重连续传。

链路：`手机 → hub /api/oc/<device>/… → 该设备 agent 控制面 → 本地 opencode`。
手机不直连 opencode，agent 只放行会话生命周期相关的方法 + `/status`（默认拒绝）。
完整契约见 [`PROTOCOL.md`](PROTOCOL.md)。

Agent 控制面默认监听 `0.0.0.0:18998`（18995–18997 被 `vibelink-hub` 占用）：

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `MESH_CONTROL_PORT` | `18998` | 控制面端口；被占用时自动向后找 9 个端口 |
| `MESH_CONTROL_BIND` | `0.0.0.0` | 监听地址；只经 Tailscale 时可绑 `100.x.x.x` |
| `MESH_RECLAIM_MS` | `0` | 被新实例顶掉后是否 standby 抢回；`0` = 停车，`30000` = 退避重试 |
| `OPENCODE_PORT` | `4097` | 本机 opencode 上游端口 |
| `OPENCODE_SERVER_PASSWORD` | 读 `~/.config/opencode-server/password` | opencode basic auth |

每个 agent 进程启动时生成 `instanceId` + `instanceStartedAt`。hub 只允许一个
活跃实例写同一台设备：更新的进程可以接管旧进程；旧僵尸进程反向顶不掉新进程。
被顶掉的 agent 默认停车，不会再抢命令队列；需要无人值守备用时设
`MESH_RECLAIM_MS`。手机 SSE 会收到 `mesh.agent-replaced`，自动重连到新实例。
详见 [`PROTOCOL.md`](PROTOCOL.md) §3.1。

当前适配 opencode **v2**（`/api/*`）。检测到 v1 会在 `/health` 明确报
`api: "v1"`，不会静默解析错数据结构。`/status` 和 `/health` 都会返回本机实例
id、hub 连接状态、当前 SSE 客户端数和在途请求数，排障不用只看日志。

首次使用要在每台被控机器上放行控制面端口（Windows 会弹防火墙确认；也可以把
`MESH_CONTROL_BIND` 绑到 Tailscale 地址 `100.x.x.x`，只让 tailnet 访问）。

## 现在能做什么（v0.3）

- 设备在线状态、IP、系统代理、代理端口、出口延迟、opencode 实例
- 一键：给某台设备设置/清除系统代理、重启白名单服务
- 配置文件版本库：上传 → 自动带时间戳 → `/phone/<name>` 直接给手机下载
- 事件流：上线/离线/命令执行结果
- **协议版本协商 + 稳定错误码 + 请求体上限 + 队列背压**
- **单写者 agent 实例**：`instanceId` + `instanceStartedAt`，旧僵尸进程顶不掉新进程；
  命令队列也绑定活跃实例，被顶掉的 agent 不能再领命令
- **手机接管 opencode 会话**（列表 / 对话 / 发送 / 插话 / 停止 / 审批 / 断线续传）
- **事件流诊断**：`server.connected` / `mesh.upstream-up|down` / `mesh.agent-replaced`；
  客户端有连接/首事件/心跳三阶段超时和 45s watchdog，`window.__meshDiag` 可直接看
- **链路判定**：`/api/path` 报告 direct / relay(DERP) / lan，而不是按 RTT 猜

## 验证

```powershell
$t = (Get-Content mesh\data\token.txt -Raw).Trim()

# 协议版本 / 设备数
curl.exe -H "x-mesh-token: $t" http://127.0.0.1:18994/api/health
# 链路判定（从哪台机器发起就报哪条路径）
curl.exe -H "x-mesh-token: $t" http://127.0.0.1:18994/api/path
# 被控端控制面
curl.exe -H "x-mesh-token: $t" http://127.0.0.1:18994/api/oc/<device>/health
```

预期：`protocol` 为 `2`；`/api/oc/.../health` 里 `opencode.api` 为 `v2`。
协议不匹配回 426 `VERSION_MISMATCH`，超大请求回 413，命令队列满回 429。

接管/命令绑定/SSE 通知的端到端测试（自带临时 hub + 假 agent，不碰正在跑的
18994）：

```powershell
node mesh/test/takeover.mjs
node mesh/test/client.mjs
```

## 目录

| 路径 | 作用 |
| --- | --- |
| `hub/server.ts` | Hub，单文件、零依赖（Node ≥ 22.6 直接跑 .ts） |
| `agent/agent.ts` | Agent 主循环：采集 + 执行白名单动作 |
| `agent/opencode.ts` | opencode v2 适配层（唯一知道 opencode API 的地方） |
| `agent/control.ts` | 手机控制面：白名单、幂等、SSE 续传 |
| `shared/protocol.ts` | 版本、上限、错误码（hub 与 agent 共用） |
| `web/` | 桌面看板 + `m.html` 手机端（原生 JS，无构建） |
| `test/` | 端到端接管测试（临时 hub + 假 agent，不碰运行中的数据） |
| `data/` | 运行时状态（token、devices.json、configs/），不入库 |

## 为什么不用框架

这一层的复杂度在“跨设备状态一致性”，不在 Web 框架。零依赖的代价是少 300MB node_modules，
好处是笔记本/Mac/Termux 上 `node agent.ts` 就能跑，升级只需 scp 一个文件。
等接口稳定了再考虑上 React/Vite，接口不变。

## 路线图

- **v0.1**：状态总览 + 拓扑 + 手动动作 + 配置版本库 + 手机配置下载
- **v0.2**：协议 v2（版本协商 / 错误码 / 上限 / 背压）+ 手机接管 opencode 会话 + 真实链路判定
- **v0.3（当前）**：单写者 agent 实例（`instanceId` / 抢占 / 命令绑定）+ 事件流诊断与 watchdog
- **v0.4**：自动自愈（HY2 优先、IP 跟随、服务掉线重启）+ 事件告警
- **v0.5**：手机端 Agent（Termux 脚本）+ 扫码导入 CMFA
