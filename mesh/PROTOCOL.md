# mesh 协议 v2

本文是 hub、agent、手机端之间的线上契约。改动前先读这一页；实现以
`shared/protocol.ts`、`hub/server.ts`、`agent/control.ts` 为准。

协议版本：**2**。hub 仍接受 v1 agent（无 `x-mesh-protocol` 头），但会把它标记为
`protocolVersion: 1`，并且不提供手机接管能力（v1 agent 没有控制面）。

## 1. 版本协商

| 方向 | 头部 | 说明 |
| --- | --- | --- |
| agent → hub | `x-mesh-protocol: 2` | 每个请求都带；`/api/ingest` 同时写进 body |
| agent → hub | `x-mesh-instance: <uuid>` | 本进程实例 id；每个请求都带（可选，老 agent 没有） |
| hub → agent | `x-mesh-protocol: 2` | 控制面请求，agent 校验后回同版本头 |
| 手机 → hub | 无 | 浏览器只走 `/api/*`，由 hub 代理到 agent |

- agent 协议 `< 1` 或 `> 2`：hub 返回 **426** `VERSION_MISMATCH`，body 带
  `hubProtocol` / `peerProtocol`。
- agent 收到 426 是**永久错误**：记录日志后 `exit 2`，不做指数退避重试。
- 新增字段一律可选，旧端忽略；破坏兼容才 +1。

## 2. 帧上限与背压

HTTP 没有帧，这里的「帧」指单次请求体。

| 路由 | 上限 |
| --- | --- |
| `POST /api/ingest` | 64 KiB |
| `POST /api/commands` | 16 KiB |
| `POST /api/commands/result` | 64 KiB |
| `POST /api/config/<name>` | 1 MiB |
| agent 控制面请求体 | 256 KiB |
| 单个 SSE 事件 | 1 MiB（超出替换为 `{truncated:true}`） |
| 每设备 pending 命令 | 32 条 |
| 设备总数 | 64 |
| 每 agent 并发 SSE | 8 条 |
| 事件环形缓冲 | 500 条 / 5 分钟 |

- 超限返回 **413** `PAYLOAD_TOO_LARGE`，不再直接 `req.destroy()`（旧实现会让客户端
  只看到连接被重置）。
- pending 命令超限返回 **429** `TOO_MANY_COMMANDS`。
- agent 控制面并发超过 8 返回 **503** `CONGESTED`。
- SSE 客户端写缓冲超过 1 MiB：服务端直接断开，客户端带 `since` 重连并从环形缓冲补齐。

## 3. 错误码

所有错误响应统一为 `{ "error": "...", "code": "..." }`。

| code | HTTP | 含义 |
| --- | --- | --- |
| `BAD_REQUEST` | 400 | 参数缺失或形状不对 |
| `UNAUTHORIZED` | 401 | token 错误 |
| `NOT_FOUND` | 404 | 设备 / 会话 / 命令不存在 |
| `VERSION_MISMATCH` | 426 | 协议版本不兼容 |
| `DEVICE_REMOVED` | 403 | 设备已被移除，停止重试 |
| `AGENT_REPLACED` | 409 | 另一个 agent 实例拥有该设备；带 `activeInstanceId` / `activeStartedAt` |
| `DEVICE_OFFLINE` | 502 | 设备心跳过期 |
| `TOO_MANY_CONNECTIONS` | 429 | SSE 或连接数超限 |
| `TOO_MANY_COMMANDS` | 429 | 设备 pending 命令队列满 |
| `PAYLOAD_TOO_LARGE` | 413 | 请求体超限 |
| `CONGESTED` | 503 | hub / agent 过载 |
| `UPSTREAM_UNAVAILABLE` | 502 | agent 或 opencode 不可达 |
| `TIMEOUT` | 504 | 上游超时 |
| `CHANNEL_NOT_ALLOWED` | 403 | 不在控制面白名单 |
| `INTERNAL` | 500 | 未分类错误 |

这套码是 cindy WebSocket 关闭码的 HTTP 等价物：

| cindy | mesh |
| --- | --- |
| 4400 version mismatch | 426 `VERSION_MISMATCH` |
| 4410 device removed | 403 `DEVICE_REMOVED` |
| 4429 too many connections | 429 `TOO_MANY_CONNECTIONS` |
| 1013 congested | 503 `CONGESTED` |
| 4000 agent replaced | 409 `AGENT_REPLACED` |

## 3.1 Agent 实例与接管

每个 agent 进程启动时生成 `instanceId`（UUID）和 `instanceStartedAt`（毫秒）。
`/api/ingest` body 带这两个字段；所有 agent 请求带 `x-mesh-instance`。

hub 的单写者规则：

- 无记录 / 当前实例离线 → 接受。
- 同一 `instanceId` → 接受（心跳）。
- 不同 `instanceId` 且 `instanceStartedAt` 更大 → 接受，旧实例被接管。
- 否则 → 409 `AGENT_REPLACED`，body 带 `activeInstanceId` / `activeStartedAt`。

`instanceStartedAt` 让「旧僵尸进程重新连回来」不能反向顶掉新进程；只有真正更新的进程才能接管。老 agent 没有 `instanceId` 时按 legacy 处理：没有活跃 managed 实例时可以写，有则 409。

命令队列也绑定到活跃实例：`GET /api/commands`、`/claim`、`/result` 都校验 `x-mesh-instance`。被顶掉的 agent 即使还在跑，也不能再领取/回写命令。接管时正在 `running` 的命令标记为 `failed`（旧实例的结果会被 409 拒绝，不能让它永远挂着）；`pending` 命令留给新实例继续领取。

接管时 hub 向该设备现有的手机 SSE 流发 `event: mesh.agent-replaced`，然后关闭流；手机会重连并从 `since` 续传。agent 收到 409 后：

- 默认停车（`replaced`），不自动重连；
- `MESH_RECLAIM_MS=30000` 时进入 standby，按 ×2 退避重试；如果活动实例的 `instanceStartedAt` 比本进程新，重试没有意义，直接停车。

## 4. 控制面白名单

手机只能调用下表。表外的路径在 hub 和 agent 各拒一次（默认拒绝）。

| 方法 | 路径 | 对应 opencode |
| --- | --- | --- |
| `GET` | `/health` | `GET /api/info` |
| `GET` | `/status` | agent 实例 / 事件流状态，不透传 opencode |
| `GET` | `/sessions` | `GET /api/session` |
| `GET` | `/session/:id` | `GET /api/session/:id` |
| `GET` | `/session/:id/messages` | `GET /api/session/:id/message` |
| `GET` | `/session/:id/inbox` | `GET /api/session/:id/inbox` |
| `GET` | `/session/:id/permissions` | `GET /api/session/:id/permission` |
| `POST` | `/session/:id/prompt` | `POST /api/session/:id/prompt` |
| `POST` | `/session/:id/interrupt` | `POST /api/session/:id/interrupt` |
| `POST` | `/session/:id/permissions/:rid/reply` | `POST .../permission/:rid/reply` |
| `GET` | `/models` | `GET /api/model` |
| `GET` | `/events` | `GET /api/event` |

永不开放：shell / pty、文件写入、凭证、配置写、插件、worktree、updater。
和 cindy 的 `REMOTE_INVOKE_ALLOWLIST` 同一条原则：控制端只能做会话生命周期内的事。

## 5. 事件流与断线续传

```
GET /api/oc/<device>/events?since=<seq>
```

- 每个事件：`id: <seq>` + `event: <type>` + `data: <json>`。
- `seq` 是 agent 进程内单调递增整数，不是 opencode 的事件 id（后者放在
  `x-opencode-event` 头里，仅供排查）。
- 重连带 `since`，agent 从环形缓冲补发 `seq > since` 的事件。
- 缓冲已被覆盖时，agent 先发 `event: resync`，客户端清空游标并全量重拉。
- agent 重启后 `seq` 从 1 重新开始；客户端带着更大的 `since` 回来时同样回
  `resync`（`reason: agent-restarted`），避免新事件一直被旧游标忽略。
- 每条新流先发 `event: server.connected`（带 `protocol` / `at`），客户端据此把
  「hub 已连上」和「agent 已开始发事件」分开。
- 上游 opencode 断开时发 `event: mesh.upstream-down`，agent 按 1s→30s 退避重连；
  恢复后发 `event: mesh.upstream-up`。
- 设备被另一个 agent 实例接管时，hub 向现有手机流发
  `event: mesh.agent-replaced`（带 `activeInstanceId` / `activeStartedAt`），
  然后关闭流；客户端重连后从 `since` 续传。
- 心跳注释 `: heartbeat` 每 15s 一次。

客户端实现见 `web/m.js` 的 `openStream()`：游标保存在内存，`resync` 触发
`loadMessages()` 全量刷新。

## 6. 幂等

`POST` 带 `x-mesh-request-id`（或 body 里的 `requestId`）：

- prompt / interrupt / permission reply 都带；手机切屏/重试不会产生第二次副作用；
- agent 缓存 10 分钟、最多 256 条结果；
- 重复请求直接回放，响应头 `x-mesh-replayed: 1`；
- 手机发送消息时用 `crypto.randomUUID()` 生成 `messageID`，同时作为
  `x-mesh-request-id` 和 opencode 的 `messageID`，重试不会产生第二条消息。

## 7. 路径判定

`GET /api/path` 和 opencode-mesh 的 `GET /__mesh/path` 返回同一套结构：

```json
{ "peer": "100.105.59.31", "ipClass": "tailscale", "path": "relay",
  "reason": "tailscale-derp", "tailscale": { "direct": false, "relay": "tok", "host": "liangmac-mini" } }
```

- `path`: `direct` | `relay` | `local` | `wan` | `unknown`
- `reason`: `loopback` / `lan` / `public-route` / `tailscale-direct` /
  `tailscale-derp` / `tailscale-status-unavailable` / `tailscale-no-endpoint`
- Tailscale 判定读 `tailscale status --json` 的 `CurAddr`（有值 = 打洞成功）和
  `Relay`（DERP 节点），缓存 5 秒；没有 Tailscale 时退化为源地址分类。

不再用 RTT 阈值猜路径：延迟高不等于走中继，局域网拥塞会误判。

## 8. 与 cindy 的对应关系

设计参考 [makecindy/cindy](https://github.com/makecindy/cindy)（Apache-2.0）。这里是重新
实现，不复制代码；对应关系用于日后回溯「为什么这么设计」。

| cindy | 这里 |
| --- | --- |
| `packages/device-link/README.md` 控制面 / 数据面分层、allowlist 默认拒绝 | 第 4 节白名单；hub 与 agent 双重校验 |
| `packages/device-link/src/directPath.ts` 的 `path` + `reason` | 第 7 节 `/api/path` |
| `packages/device-link-relay/src/hub.ts` 关闭码、连接上限、1013 拥塞 | 第 3 节错误码；第 2 节上限 |
| `packages/device-link/src/invokeScheduler.ts` 交互写预留槽位 | `control.ts` 的 `INTERACTIVE_EXTRA` |
| `packages/device-link/src/selfHostedRelay.ts` 一次性加入码 + 主人确认 | **未实现**：本项目沿用共享 token |
| `apps/mobile/src/device-link/mobileMakerTransport.ts` 手机纯控制器 | `web/m.js`：不直连 opencode |
| `packages/device-link/src/client.ts` 1s→30s 退避、心跳判僵死 | agent 上游 1s→30s；手机 1s→15s |

刻意没照搬的部分：WebSocket 长连接（HTTP + SSE 足够）、设备签名证书、跨账号共享任务、
一次性配对码。它们的复杂度只有在多用户/公网中转时才需要。
