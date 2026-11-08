# VibeLink 桌面融合

基于 [makecindy/cindy](https://github.com/makecindy/cindy) 的 Apache-2.0 源码，固定上游 commit `d53e60e099`（完整值见 manifest.json）。保留上游许可，使用可校验补丁和新增源文件分发；不包含 Cindy 的 Git 历史、用户数据、模型凭据或 Clash 配置。

融合版使用 VibeLink 统一登录：登录页提供内测码、手机扫码和显式“使用本机工作区”。不再要求 Cindy 云账号，也不伪造 Cindy membership。开发版 owner 登录后保留 Cindy 会话、项目工具与“设备与连接”，侧栏可进入 Hub 工作区；内测主体直接进入 Hub 工作区，本机模型执行被 Main 拒绝。

订阅模型仍只走官方 CLI。GPT 官方 Codex app-server 使用原生 OpenAI provider；融合模式拒绝订阅翻译代理以及原语音润色中的直接 ChatGPT OAuth HTTP 调用。非融合上游行为与 API Key 供应商入口保持原有语义。没有修改 maker-core 或数据库 schema。

## 准备与启动

```powershell
.\Prepare-CindyFusion.ps1 -CindyRepository D:\AI\VibeLink\cindy
pnpm --dir "$env:LOCALAPPDATA\vibelink-mesh\desktop\source" install --frozen-lockfile
.\Start-VibeLinkDesktop.ps1
```

准备器只从已有本机仓库创建固定 commit 的新 worktree，再应用校验过的文件；目标已存在时拒绝覆盖。不会读取或复制 `.user-data`、`.relay-data`、Token、登录态或 SQLite 数据。

启动器沿用上游安全启动包装、默认使用外置命名隔离 profile `vibelink`，不带 `--no-sandbox`，不改系统代理、不停止 Hub/Agent、不自动启动旧 Relay，也不迁移原有 Cindy 账号。原 profile 数据完整保留在原位置；是否切换到已存在的 profile 由最终部署处理。

指定已准备的源码（本机当前验收目录）：

```powershell
.\Start-VibeLinkDesktop.ps1 -SourceRoot D:\AI\VibeLink\.vibelink-worktrees\cindy-login-releasecheck-20261109
```

安装桌面和开始菜单的统一入口（保留旧入口备份）：

```powershell
.\Install-DesktopEntry.ps1 -SourceRoot D:\AI\VibeLink\.vibelink-worktrees\cindy-login-releasecheck-20261109
```

此交付为源码启动版，需要保留已准备的源码与依赖；不是独立桌面安装包，也未发布桌面自更新通道。入口优先打开该源码已经运行的窗口，未运行时才使用安全包装编译启动；后台控制台隐藏。现有 `CindyVibelinkDesktop` 登录任务会使用同一新入口，旧 Relay 仍保持禁用。开发模式不运行上游桌面自动更新。

只有输出 `DESKTOP_DEV_VERDICT=ready` 才表示窗口、登录/本机模式和数据库均就绪。浏览器 UI 验证不等于完整桌面启动成功。

## 连接与凭据

开发版登录固定连接 `http://127.0.0.1:18995`；内测码与内测扫码固定连接 `https://hub.vibelink.cloud`。Main 的 Electron 网络层继承当前系统网络设置，不写死代理地址或修改系统代理。Renderer 不能指定凭据目的地址。

Main 保存由 Hub 验证的 client session，token 和 QR pollSecret 不进入 Renderer；只有一次性扫码 link、核对码和公开 session 信息用于界面。持久凭据通过 Electron safeStorage 加密，保存并刷盘后才确认 QR 消费；ACK 明确 denied/expired 会撤销本机能力与 owner。网络丢 ACK 保留 Main 私有证明重试。空 profile 不主动探测 Keychain；安全存储不可用不降级到明文。退出使用无秘密的持久 tombstone，系统凭据库锁定时同样阻止重启自动恢复。

数据目录以 Hub 稳定 issuer、edition、userId 的散列隔离。只有显式本机管理员交换得到的真实 owner 可以在 Main 建立到旧 `local-v1` 的受信映射，继续本机历史；其他远程或内测同名用户不会套用该映射。原 Cindy 凭据、旧 DB 和原 profile 保留，不复制到源码或发布包。

## Hub 任务工作区

桌面读取同一 Hub 的群、任务账本、步骤和群聊消息，展示结果、授权范围、预算、实测用量和底层会话编号。可显式创建群并选现有成员、提交任务、追问、取消、重试、归档和导出。Main 通过当前登录的 client session 调用固定 Hub 接口，没有管理员回退，不运行第二套模型或任务调度器。管理按钮须由服务端 `canManage=true` 开放；缺字段时只读。

发送前，Main 生成请求编号并将原始输入用 OS safeStorage 加密刷盘。网络丢包、429、5xx或重启后沿用原编号；401/403保留未确认记录，重新登录同一主体后恢复，不假定原任务尚未执行。待发送记录不能删除；Hub 明确拒绝的记录须先核对原任务，再显式移除。切换账号、owner边界或登录到期会隔离队列并中止旧网络请求，晚响应不能改写新账号视图。

取消仅采用 Hub 回报；出现 `cancelled_unknown` 时显示“已停止后续派发，原执行待确认”。底层会话编号只读，没有绕过任务授权的直接 prompt 入口。通知首批为本机结果未读和去重，不外发 IM 或邮件。

2026-11-09 登录包重放已从固定 commit 的全新 Prepare 目录安装独立依赖并返回真实 `DESKTOP_DEV_VERDICT=ready`。Main 登录 IPC 验证 owner 映射到原 `local-v1`；绝对 userData 仍为 `C:\Users\22771\AppData\Roaming\Cindy-dev2-vibelink`，原 `LOGIN_OWNER_OK` 历史可见。桌面、开始菜单和既有 `CindyVibelinkDesktop` 登录任务已转向该可复现源码，旧入口已备份；未复制原 dirty Cindy 源码或数据。该次检查未新增模型推理。

同日任务面板的最终真实开发版验收读取 2 个群、首群 0 个任务和 1 条消息，Main `groups/list/messages` 均成功，可由侧栏进入并返回原本机历史；没有提交模型任务。Windows 实窗截图确认侧栏、入口和旧历史正常，CDP 对透明侧栏的黑底截图不代表实窗效果。最终包另从固定 commit 全新 Prepare 重放，39 个 overlay 与 47 个已修改上游文件逐字节一致；最终运行进程来自前述已独立安装依赖的重放源码。

账号切换、退出、QR 批准的持久化与 owner 交接共用一个提交队列，并检查操作代际；晚到 A 不会覆盖 B。运行中 session 到期撤销能力并沿相同边界退出，不依赖重启。

原设备管理 HTTP fallback 需要 Main 随机私有 IPC proof 与当前已验证会话，不能从网页直接绕过登录。内测只用当前公网 client，不回退读取本机管理员凭据；本机管理动作只向经过显式本机登录的 owner 开放。管理员 token 只留 Main。

## 自检

在已安装依赖的融合 worktree 内：

```powershell
node --test apps/desktop/src/main/vibelink/embeddedServer.test.cjs
pnpm --dir apps/desktop exec vitest run src/renderer/features/vibelink/desktopBridge.test.ts src/main/maker-host/__tests__/vibelinkSubscriptionPolicy.test.ts src/main/maker-host/__tests__/piNativeSubscriptionForwarding.test.ts --maxWorkers=1
pnpm ui:check --page vibelink-desktop --theme all
```

HTTP 测试用临时 Hub 验证 Main 注入的当前身份、凭据不回传，以及无 Main proof/第三方 Origin 不能到达 Hub。新增登录测试覆盖 fresh signed_out、发行与恢复、迟到 vault/owner 写入、退出和 B 登录交错、运行中到期、ACK denied、丢 ACK、加密存储和本机执行能力边界。

2026-11-09 本轮 46 项登录与任务工作区定向 Vitest、1 项真实 HTTP 凭据隔离测试通过。登录页此前验证 48 项 UI 变体，本轮任务面板验证 12 种语言 × 深浅色 × 4 种状态，共 96 项变体并检查截图，无 warnings。全新 Prepare 目录独立安装锁定依赖后，全量 TypeScript 以 12GB 堆执行通过；该结果取代早期原型环境中的 Undici/net-policy 类型限制。

打包说明：父仓 `.gitattributes` 对 `vibelink/desktop/**` 设置 `-text`，保持文件原始字节，避免跨平台 checkout 改写换行导致 SHA256 不匹配。原型中全局替换端点、伪造账号、明文保存Token、旧中继自动启动和禁用沙箱均未纳入交付。完整原生客户端已由发布流程返回 `DESKTOP_DEV_VERDICT=ready`（真实模式、region cn、隔离profile vibelink），最终运行验收由部署方记录。

默认 GPT 的隔离上下文 host 同样直接使用官方 CLI：保留模型、上下文窗口和每模型压缩预算，通过上游本机 `model_catalog_json` 生成器传递给 Codex，不把 `custom-context` 标记当作需要模型代理的依据。只有依赖共享订阅代理的受邀 host 才本机拒绝。此分支有完整默认 spawn 参数回归及原生模型 catalog 测试覆盖。

2026-11-08 最终运行验证：安全包装真实模式返回 `DESKTOP_DEV_VERDICT=ready`，原生 Electron 窗口通过官方 Codex app-server 收到 `DESKTOP_OK`；同窗“设备与连接”读取运行 Hub 的在线状态及 PC/Mac Agent。96 项订阅入口、原生 spawn、模型 catalog 和 Renderer 桥接测试通过，另有真实 HTTP 凭据隔离测试。首次真实请求出现网络重连后完成，不能据此保证后续网络延迟。手机扫码配对仍需本人在界面完成。
