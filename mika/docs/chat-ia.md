# 对话式信息架构：agent 即联系人

> 状态：P1 已落地（2026-11-01）。后续按本文的分期推进。

## 目标

把 Mika 从「一个对话页 + 侧栏」改成 QQ 式聊天软件：

- 消息列表里的每一行是一个 **agent**，不是一次对话。
- 手机端 `Mika`、电脑端 `opencode` / `codex` / `Claude Code` / `Antigravity` 都是联系人。
- 点头像进 agent 资料页，点整行进聊天。
- VPN / 代理只保留「我的 → 网络与 VPN」一个入口。

## 页面结构

```
消息（根）
├─ Mika                手机端 agent
├─ opencode            电脑端 agent（多台设备合并）
├─ codex
└─ …
                                    底部：消息 | 我的
我的 = 原 SettingsScreen
├─ 模型 / Token 用量 / 手机操作 / 通知 / 语言
├─ 隐私与数据
└─ 网络与 VPN          ← ProxyScreen（原侧栏入口已删）
```

聊天页（Mika 和电脑端 agent 各一套壳，消息渲染共用）：

```
←  [头像] 名称                      [会话] [新对话]
   设备 · 当前会话标题
   ───────────────────────────────
   消息流（思考折叠 / 工具 / 图片，图片最高 260dp）
   ───────────────────────────────
   [输入框]                        [发送 / 停止]
```

## 数据模型

`ui/AgentContacts.kt`

```kotlin
enum class AgentKind { Phone, Desktop }

data class AgentContact(
    val id: String,          // "mika" | "opencode" | "codex" | ...
    val name: String,
    val kind: AgentKind,
    val online: Boolean,
    val deviceCount: Int,
    val sessionCount: Int,
    val lastText: String,    // 列表第二行
    val lastAt: Long,        // 列表右侧时间，也用于排序
)
```

数据来源：

- `loadMikaContact()`：只读 `ConversationStore`，永远先上屏，不等 hub。
- `loadRemoteContacts()`：`RemoteApi.devices` + 每台在线设备 `allAgents`，按 agent id 合并；
  老版本 agent 回退到只列 opencode。
- `loadAgentSessions(agent)`：单个 agent 的会话，进聊天页 / 资料页用。

## 已落地（P1）

| 内容 | 文件 |
| --- | --- |
| 消息列表 + 底部两栏 | `ui/HomeScreen.kt` |
| 头像（固定色 + 首字） | `ui/AgentAvatar.kt` |
| agent 聚合 / 本地优先 | `ui/AgentContacts.kt` |
| 电脑端 agent 聊天（切会话、发送、停止、4s 轮询） | `ui/AgentChatScreen.kt` |
| agent 资料页 | `ui/AgentProfileScreen.kt` |
| 路由：Home 为根，Chat 变成 push | `ui/Navigation.kt`、`ui/MainActivity.kt` |
| 对话页顶栏：返回 + 头像 + 抽屉 + 新对话 | `ui/ChatScreen.kt`、`ui/components/Controls.kt` |
| 侧栏删掉「远程会话 / 网络代理」 | `ui/ChatDrawer.kt` |
| 返回键：对话页返回消息页（侧栏开着时先关侧栏） | `ui/ChatDrawer.kt` |
| 离线缓存：hub 连不上时保留上次的 agent 列表并标「离线」 | `ui/AgentContacts.kt`、`ui/HomeScreen.kt` |
| Mika 聊天页右上角「会话」切换（不再只靠侧栏） | `ui/ChatScreen.kt` |
| agent 资料页的会话列表，点一条直接进对应会话 | `ui/AgentProfileScreen.kt`、`ui/AgentChatScreen.kt` |
| Mika 任务在跑时消息列表显示绿点 | `ui/ChatViewModel.kt`、`ui/HomeScreen.kt` |
| 未读红点 / 置顶 / 长按菜单（置顶、标已读、免打扰） | `ui/HomeScreen.kt`、`ui/AgentPrefs.kt` |
| 远程 agent 运行中绿点（agent 的 sessions 带 `running`） | `vibelink/crates/agent`、`ui/AgentContacts.kt` |
| 资料页：模型 / 权限 / 通知（免打扰） | `ui/AgentProfileScreen.kt` |
| 图片点开全屏（双指缩放、拖动、关闭、保存到相册、系统分享） | `remote/RemoteScreen.kt` |
| 深度思考块：旋转星标 + 流光标题 + 闪烁光标，思考内容流式吐出；答案开始后自动收起为「已深度思考 · Ns」 | `remote/RemoteScreen.kt`、`ui/AgentChatScreen.kt` |
| 长会话服务端 cursor 分页（首屏 120 条，顶部「载入更早」按 cursor 拉更早一页）；上翻后新消息不会把列表拽回底部 | `vibelink/crates/agent`、`vibelink/crates/hub`、`ui/AgentChatScreen.kt` |
| 侧栏彻底移除：计划 / 浏览器进「我的」，对话管理（重命名 / 删除 / 置顶）进 Mika 资料页 | `ui/ChatScreen.kt`、`ui/SettingsScreen.kt`、`ui/AgentProfileScreen.kt`、`ui/ConversationManager.kt` |

远程会话的两个新字段由 vibelink agent 提供：

```json
{ "id": "ses_…", "title": "…", "updated": 1793…, "model": "magpie/auto/…", "running": true }
```

`running` 来自 opencode 的 `/api/session/active`；`model` 来自 `/api/session` 的 `model.providerID/model.id`。
老 agent 不带这两个字段，手机端默认 `running=false`、`model` 为空。

## 后续

- **P4**：图片长按菜单（复制、转发）。
- **P4**：免打扰的会话完全不提醒（App 里还没有按 agent 的推送，等有了再加静音）。

## 验证

模拟器（`Codex_API_36`）+ `adb reverse tcp:18995 tcp:18995` 指向本机 hub：

- 消息列表出现 `opencode / Claude Code / Codex / Mika / Antigravity`，头像与时间正常。
- opencode 有会话在跑时名字旁显示绿点；该会话更新后出现红色未读数。
- 长按一行：置顶 / 标为已读 / 免打扰；置顶后行首出现图钉并排在最前。
- 点 `opencode` 进聊天，标题显示 `pc · 会话标题`，右上角可切会话。
- 点头像进资料页：会话 50、设备 1 台在线、状态在线、模型 `magpie/auto/deepseek-v4.1-flash`、通知可切免打扰。
- 资料页会话列表点「云端与Docker题目状态盘点更新」→ 聊天页标题变成该会话。
- Mika 聊天页右上角「会话」：没有本地会话时显示「还没有会话」。
- 点消息里的图片 → 全屏黑底查看，双指缩放、拖动，右上角关闭；底部「保存」写入相册（toast 已保存），「分享」拉系统分享面板。
- 深度思考：完成后显示「已深度思考 · 49s」，点「展开」看推理原文；正在跑时是「深度思考中」+ 流光标 + 流式吐字（合成态截图验证）。
- 聊天的「载入更早」按 cursor 拉服务端更早一页（实测点一次后出现 08:21 的更早消息）。
- 聊天页顶栏只有「会话 / 新对话」，没有侧栏菜单；「我的」里出现「计划」「浏览器」；Mika 资料页可打开「会话管理」（重命名 / 删除 / 置顶）。
- 会话在跑时往上翻历史：新消息不会把列表拽回底部。
- 返回键 / 左上角返回都回到消息页。
- 「我的」进设置，网络与 VPN 在设置内。
- 断网（移除 `adb reverse`）后重开：agent 不消失，标红「离线」。
