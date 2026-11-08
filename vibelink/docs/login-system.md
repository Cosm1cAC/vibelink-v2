# VibeLink 统一登录

Hub 是账号与设备会话的签发方。桌面登录不依赖 Cindy 云账号；手机和桌面登录到同一 Hub 身份后，任务、用量与 Agent 会话仍归原用户。开发版连接内网，内测版连接 `https://hub.vibelink.cloud`，两版不共用身份或资源。

## 登录入口

- **内测码**：在登录页输入私人邀请码，签发本设备独立的 client 会话。同一内测账号的多台设备共享 tenant/userId 和额度，但拥有不同 sessionId。
- **手机确认桌面登录**：电脑生成五分钟二维码，手机扫描后展示目标、版别及双方确认码。只有已登录手机明确同意才批准；扫描本身不登录。二维码不能兑换凭据，电脑还需要不放在二维码中的轮询证明。
- **开发版本机工作区**：桌面明确选择后，Main 用本机管理凭据换取普通 client 会话；管理员凭据不进入 Renderer。
- **手机扫码登录**：保留开发版电脑授权手机的原配对码流程，并纳入登录页。新设备以本机工作区同一主体登录。内测使用内测账号流程，不通过开发版配对获得 owner 权限。

本轮没有新增邮箱密码或短信平台，也不伪造 Cindy 用户。原 Cindy 数据/凭据与设备本机助手数据不删除；桌面按已验证 Hub 身份选择本机数据空间。

## API 合同（Hub 0.4.0）

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

`session`：`userId, label, displayName, tenantId, edition, sessionId, expiresAt, permissions, issuer, hubId`。issuer/hubId 是同一公开持久标识，Hub 重启或内网 IP 变化不改变。开发和内测各自的数据目录生成各自 issuer。设备凭据有效期 30 天，服务端强制到期，凭据只以哈希落盘。

扫码链接形如 `vibelink://login?v=1&purpose=desktop_login&id=…&s=…&u=…`。`s` 仅供手机查看/确认，不等于 pollSecret；链接不包含长期登录 Token。手机确认请求只发向当前登录 Hub，不能按任意二维码地址转发已有 Bearer。电脑在 Main 保存凭据后 ACK；丢回包以原轮询证明取回同一会话，不能另签发。确认后尚未完成交付的授权在下一次来源校验时因手机凭据撤销而拒绝；已 ACK 的设备会话相互独立。

## 旧版本迁移与隔离

- 旧凭据须先 `/me` 验证；`expiresAt=0` 表示旧格式，再换取一次设备会话。旧手机 `client_<tokenId>` 主体保留，已有 Work、用量、CLI scope 和会话不会因登录升级改归 owner。
- 新二维码登录继承手机已验证的主体，桌面可对同一 Work 追问/取消。调用方传入的 `userId/meterIdentity` 不参与身份选择。
- 租户路由只能由可信原账号派生，设备显示名不能用 `tenant:…` 冒充其他账号。新设备会话最多 64 个活跃实例，退出/过期可释放名额；新制失效凭据在后续签发时清理，旧兼容凭据记录保留。
- 远程缓存与 outbox 以 `issuer + edition + userId` 隔离；凭据只发给实际配置的 Hub 地址。凭据更换仍需 session/generation 防护，不能把旧请求结果写入新账号。
- 首次安装、失效会话和退出后回登录页；网络暂不可达不能伪装成凭据验证成功，也不删除离线数据。旧版本的静态内测接口保留用于兼容，新登录界面不再保存邀请码或接收共享 Agent 凭据。

设计参考：[RFC 8628](https://www.rfc-editor.org/rfc/rfc8628.html) 的跨设备确认与独立设备证明思路。本协议是 VibeLink 自有登录合同，不宣称实现完整 OAuth 服务。
