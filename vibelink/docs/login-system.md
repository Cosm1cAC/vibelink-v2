# VibeLink 统一登录

Hub 是账号与设备会话的签发方。桌面登录不依赖 Cindy 云账号；手机和桌面登录到同一 Hub 身份后，任务、用量与 Agent 会话仍归原用户。开发版连接内网，内测版连接 `https://hub.vibelink.cloud`，两版不共用身份或资源。

## 登录入口

- **内测码**：在登录页输入私人邀请码，签发本设备独立的 client 会话。同一内测账号的多台设备共享 tenant/userId 和额度，但拥有不同 sessionId。
- **手机确认桌面登录**：电脑生成五分钟二维码，手机扫描后展示目标、版别及双方确认码。只有已登录手机明确同意才批准；扫描本身不登录。二维码不能兑换凭据，电脑还需要不放在二维码中的轮询证明。
- **开发版本机工作区**：桌面明确选择后，Main 用本机管理凭据换取普通 client 会话；管理员凭据不进入 Renderer。
- **手机扫码登录**：保留开发版电脑授权手机的原配对码流程，并纳入登录页。新设备以本机工作区同一主体登录。内测使用内测账号流程，不通过开发版配对获得 owner 权限。

本轮没有新增邮箱密码或短信平台，也不伪造 Cindy 用户。原 Cindy 数据/凭据与设备本机助手数据不删除；桌面按已验证 Hub 身份选择本机数据空间。

## API 合同（Hub 0.4.0 起）

所有时间为 Unix 毫秒。登录请求最大 8 KB，响应为 `Cache-Control: no-store`。需要身份的操作只接受 Hub 验证的 client/admin，Agent 不能签发登录会话。

| 接口 | 请求 | 结果 |
| --- | --- | --- |
| `GET /api/auth/me` | 当前 Bearer | `{ok, session}` |
| `POST /api/auth/session` | 当前 Bearer，`{deviceName}` | `{ok, token, session, hubUrl}`，同主体独立会话 |
| `POST /api/auth/beta` | `{code, deviceName}`，仅内测服务 | 同上，不返回管理员或 Agent Token |
| `POST /api/auth/logout` | 当前设备 Bearer | 吊销本设备会话，不退出其他已完成登录的设备 |
| `POST /api/auth/qr/start` | `{deviceName}` | `id, pollSecret, link, verificationCode, edition, expiresAt, intervalMs` |
| `POST /api/auth/qr/scan` | `{id, scanSecret}` | 目标/确认码/到期时间、issuer/hubId 及状态，不返回凭据 |
| `POST /api/auth/qr/confirm` | 已登录手机 Bearer，`{id, scanSecret, decision: approve\|deny}` | 确认结果，不返回电脑凭据 |
| `POST /api/auth/qr/poll` | `{id, pollSecret}` | `pending/scanned/denied/expired/approved/consumed`；approved 含本设备 `token, session` |
| `POST /api/auth/qr/ack` | `{id, pollSecret}` | 电脑保存凭据后确认接收，随后不再返回该凭据 |

`session`：`userId, label, displayName, tenantId, edition, sessionId, expiresAt, permissions, issuer, hubId`。issuer/hubId 是同一公开持久标识，Hub 重启或内网 IP 变化不改变。开发和内测各自的数据目录生成各自 issuer。设备凭据有效期 30 天，服务端强制到期。Hub 只保存凭据哈希；Android 用 Keystore AES-GCM、桌面 Main 用 Electron safeStorage 加密保存本设备凭据。桌面 Renderer 不接触 Token 或轮询证明。

扫码链接形如 `vibelink://login?v=1&purpose=desktop_login&id=…&s=…&u=…`。`s` 仅供手机查看/确认，不等于 pollSecret；链接不包含长期登录 Token。手机确认请求只发向当前账号已保存、且 issuer/版本匹配的 Hub 地址（含已知别名），不能按任意二维码地址转发已有 Bearer。电脑在 Main 保存凭据后 ACK；丢回包以原轮询证明取回同一会话，不能另签发。确认后尚未完成交付的授权在下一次来源校验时因手机凭据撤销而拒绝；已 ACK 的设备会话相互独立。

## 旧版本迁移与隔离

- 旧凭据须先 `/me` 验证；`expiresAt=0` 表示旧格式，再换取一次设备会话。旧手机 `client_<tokenId>` 主体保留，已有 Work、用量、CLI scope 和会话不会因登录升级改归 owner。
- 新二维码登录继承手机已验证的主体，桌面可对同一 Work 追问/取消。调用方传入的 `userId/meterIdentity` 不参与身份选择。
- 租户路由只能由可信原账号派生，设备显示名不能用 `tenant:…` 冒充其他账号。新设备会话最多 64 个活跃实例，退出/过期可释放名额；新制失效凭据在后续签发时清理，旧兼容凭据记录保留。
- 远程缓存与 outbox 以 `issuer + edition + userId` 隔离；凭据只发给实际配置的 Hub 地址。凭据更换仍需 session/generation 防护，不能把旧请求结果写入新账号。
- 首次安装、失效会话和退出后回登录页；网络暂不可达不能伪装成凭据验证成功，也不删除离线数据。旧版本的静态内测接口保留用于兼容，新登录界面不再保存邀请码或接收共享 Agent 凭据。

设计参考：[RFC 8628](https://www.rfc-editor.org/rfc/rfc8628.html) 的跨设备确认与独立设备证明思路。本协议是 VibeLink 自有登录合同，不宣称实现完整 OAuth 服务。

## 发布与验证（1.6.2 / Android 93）

- Android 开发版、内测版签名 APK 已发布到各自应用内更新通道：开发版通过内网 Hub，内测版通过公网 Hub。两版应用 ID、登录身份、缓存和 outbox 隔离；内测版不种入 Clash 配置。
- 开发/内测 Hub 已滚动更新到 0.4.1，PC 开发/内测与 Mac Agent 维持 0.4.0 在线。登录不要求重启 Agent；0.4.1 增加任务列表的服务端所有权投影 `canManage`，操作仍由后端校验原主体。
- Hub 70 项测试通过，覆盖签发、过期、扫码批准/拒绝、丢回包、重启恢复、来源撤销及跨设备任务权限。临时 HTTP 服务也完成扫码、退出和重启身份验证。
- Android 内测版 99 项测试及开发版 34 项最终受影响测试通过，两版 release 构建和签名校验通过。未在真实手机上验收摄像头扫码，不将协议测试等同于实机扫码。
- 桌面最终 46 项定向 Vitest、1 项真实 HTTP 测试通过；48 种登录界面和 96 种任务界面变体通过。固定上游重新准备的源码及独立依赖目录中，全量 TypeScript 检查通过。真实 Electron 验证本机 owner 登录保留原数据、官方 Codex 会话返回，以及公网内测二维码登录和独立数据空间；验证用内测凭据已吊销。
- 桌面交付包从固定上游提交与校验清单重放，不包含旧 Cindy 原型工作区的整体未提交改动。`Test-DesktopPackage.ps1` 验证文件摘要；切换源码时沿用原绝对 userData，不迁移或删除原本机数据库。

## 扫码读取修复（Android 1.6.4 / 95）

- 1.6.3 的界面以待处理请求为 `LaunchedEffect` 键；消费时清空该请求，可能取消刚开始的读取。现在由稳定的收件循环消费，忽略清空产生的空值，新二维码仍取消旧读取。
- 扫描优先使用二维码内已知的 Hub 地址；网络失败或某个地址返回 HTTP 错误后可继续尝试二维码内的其他地址。扫描不携带手机登录 Token。Hub 当前本来就将局域网与 Tailnet 地址排在 WSL/Hyper-V 地址前；最后一次虚拟网卡超时不能说明它是唯一尝试的地址。
- 单地址请求上限 6 秒，整轮上限 20 秒。取消读取、替换二维码或离开页面会取消底层 HTTP Call。失败显示重试和取消操作，不再永久显示正在读取；旧批准回包不能清除新二维码卡片。
- 确认登录使用刚扫描成功且通过当前账号地址、issuer、版本校验的地址，修复主地址不可达但已知别名可达时仍向主地址提交的问题。
- 实机无 ADB 连接时，仅记录网络、协议和回归验证；不据此宣称真实手机摄像头扫码已验收。

## 首次连接手机（1.6.5 / Android 96）

电脑授权码不会签发手机身份。未登录手机扫描它时，应提供「先登录这台手机」，而不是只显示不可用的批准按钮。

开发版首次使用：电脑登录页明确点击「首次连接手机」，Main 按既有本机工作区流程验证并提交 dev owner 身份，然后打开「手机连接码」。手机从登录面板扫描此码并明确确认，取得自己的普通 client 会话；管理员凭据不进入手机或 Renderer。手机随后可返回保留的电脑授权请求确认。内测手机从同一面板输入内测码，不能通过开发配对成为 owner。

- 尚未登录、账号/Hub 不匹配、请求过期分别给出手机登录、明确切换和重新扫码动作；扫码用途不能混用。
- 接受电脑请求时同步锁定账号页，直到用户主动离开。不能依赖瞬时 Inbox 非空，否则手机登录成功后可能丢掉原确认卡片。
- 手机配对读取及确认均绑定原请求代次。连续扫码、同码重试、取消和卸载都失效旧回包，避免把新配对秘密发给旧 Hub。
- 协议回归覆盖：未登录不能批准电脑、普通 client 不能生成管理员配对码、手机一次性配对只能消费一次，独立手机凭据能继续批准原电脑挑战并完成 ACK。

## 单次扫码入口（1.6.6 / Android 97）

- 开发版电脑登录页仅提供一个「扫码登录」入口：明确打开本机工作区，按原 localhost 管理凭据流程签发电脑普通 client，再显示唯一的手机登录码。手机扫一次并确认，获得另一个独立 client 会话；两端同 owner，但不共用 Token。登录页不再展示开发版旧电脑授权码，也没有第二个首次连接入口。
- 内测版仍只有一个电脑授权码。手机未登录时在同一卡片填写内测码并明确点击「登录手机并允许这台电脑」，先登录手机再确认原挑战，不需要扫第二张码。登录回包只能批准原 id/scan proof，且须重新校验当前身份、issuer、版本、可信地址和到期时间；换码或取消后不自动批准新请求。
- 开发版旧授权码仅作兼容：已登录手机可照常批准；未登录手机提示电脑从统一入口重新生成，不再让用户先扫另一张手机码。
