# VibeLink 桌面融合

基于 [makecindy/cindy](https://github.com/makecindy/cindy) 的 Apache-2.0 源码，固定上游 commit `d53e60e099`（完整值见 manifest.json）。保留上游许可，使用可校验补丁和新增源文件分发；不包含 Cindy 的 Git 历史、用户数据、模型凭据或 Clash 配置。

融合版使用 VibeLink 统一登录：登录页提供内测码、手机扫码和显式“使用本机工作区”。不再要求 Cindy 云账号，也不伪造 Cindy membership。开发版 owner 登录后保留 Cindy 会话、项目工具与“设备与连接”，侧栏可进入 Hub 工作区；内测主体直接进入 Hub 工作区，本机模型执行被 Main 拒绝。

订阅模型仍只走官方 CLI。GPT 官方 Codex app-server 使用原生 OpenAI provider；融合模式拒绝订阅翻译代理以及原语音润色中的直接 ChatGPT OAuth HTTP 调用。非融合上游行为与 API Key 供应商入口保持原有语义。没有修改 maker-core 或数据库 schema。

桌面图标直接取手机实际 adaptive launcher 的 `ic_vibelink_fore.xml` 和 `vibelink_icon_bg`，同一深色底、蓝色 V 与端点。资源包含 PNG512 和 16–256 像素九层 ICO；`Build-DesktopIcon.mjs` 可从 Android 资源及已准备 Cindy 的锁定 sharp 依赖重建，来源哈希见 `resources/vibelink-icon-source.json`。融合主窗、Windows 任务栏身份图标和托盘使用这些资源；Mac 源码模式 Dock 使用同一 PNG。普通 Cindy 图标不变。

安装器将 ICO/PNG 发布到稳定的 `%LOCALAPPDATA%\vibelink-mesh\desktop\assets`，显式设置桌面、开始菜单及本项目隐藏兼容入口图标，并仅刷新 AUMID 为 `cloud.vibelink.desktop` 的已有任务栏固定入口。快捷方式不再依赖旧 `bin\vibelink-gui.exe`；原启动目标、参数、通知身份和 userData 保留。已运行窗口的图标在下一次安全启动生效，更新图标资源及快捷方式不会重启应用。

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

开发版登录页只有一个“扫码登录”入口，点击前明确告知“打开本机工作区，并显示手机登录二维码”。显式验证本机 owner 并提交到原 `local-v1` 后，直接显示“手机连接码 · 登录这台手机”；手机扫描此码并确认一次即可保存独立登录凭据。电脑已通过本机工作区验证登录，不等待手机批准。开发版不再显示或生成旧 `desktop_login` 二维码，也没有重复的“首次连接手机”入口；“仅打开电脑工作区”是独立的非扫码操作。

内测连接只显示一张 `desktop_login` 码，不进入开发版手机配对流程。内部导航提示只驻留内存、精确绑定当次 issuer/session、60 秒过期，失败、取消或切换账号不会自动开码；Main 既有管理权限检查不变。

2026-11-09 单码登录修复的 17 项定向测试、16 项中英文深浅色 UI 变体通过，包括旧开发版码隐藏、内测单码和取消交互。运行窗口只读确认唯一“扫码登录”、旧重复入口已移除、开发版二维码图片为 0，仍在登录页；未替用户登录、生成真实手机连接码或重启现有进程。77 文件包另从固定上游全新重放。

## Hub 任务工作区

桌面读取同一 Hub 的群、任务账本、步骤和群聊消息，展示结果、授权范围、预算、实测用量和底层会话编号。可显式创建群并选现有成员、提交任务、追问、取消、重试、归档和导出。Main 通过当前登录的 client session 调用固定 Hub 接口，没有管理员回退，不运行第二套模型或任务调度器。管理按钮须由服务端 `canManage=true` 开放；缺字段时只读。

发送前，Main 生成请求编号并将原始输入用 OS safeStorage 加密刷盘。网络丢包、429、5xx或重启后沿用原编号；401/403保留未确认记录，重新登录同一主体后恢复，不假定原任务尚未执行。待发送记录不能删除；Hub 明确拒绝的记录须先核对原任务，再显式移除。切换账号、owner边界或登录到期会隔离队列并中止旧网络请求，晚响应不能改写新账号视图。

取消仅采用 Hub 回报；出现 `cancelled_unknown` 时显示“已停止后续派发，原执行待确认”。底层会话编号只读，没有绕过任务授权的直接 prompt 入口。结果未读与任务完成、系统提醒投递分别记录，不外发 IM 或邮件。

## 可选系统提醒与回执

每个 Hub 账号的系统提醒和任务预览均默认关闭。只有显式开启后 Main 才串行读取 Hub 投影；首次开启和重新开启只建立当前状态基线，不补弹历史。仅服务端 `canManage=true` 的完成、需补充和失败事件形成提醒；普通进度及其他账号任务不提醒。应用在前台时自动提醒只保留本机未读；用户明确点击“重发提醒”时才绕过前台抑制。

通知发送账本按 issuer、edition、userId 稳定隔离，用 OS safeStorage 加密并在调用显示前刷盘。每条系统 Tag 含账号与任务版本的 SHA256，长度固定 64；系统分组使用账号散列。退出、到期、切换账号或关闭提醒立即中断扫描并关闭在途对象。关闭有无秘密 tombstone，即使加密存储暂时不可用，重启也保持关闭。已进入显示调用但缺少确认的记录变为“结果不确定”，重启不自动重发；失败和不确定记录均只能显式重发提醒，不能代替任务重试。

系统 show、click、close、failed 各自保存回执。“已提交系统提醒”表示收到系统 show 回执，不代表用户已看到，也不修改 Work 完成或已读。点击仅在当前同一账号及有效 owner 下进入对应 Hub 群和任务；预览默认仅通用正文，须另行选择才能包含标题或结果。IM、邮件仅保留未来通道类型声明，没有目标凭据或外发实现。

Windows 源码启动版固定 AUMID `cloud.vibelink.desktop` 与 Toast Activator CLSID。桌面、登录任务仍使用原安全包装；开始菜单使用受控 Electron 启动桩。Electron 42 按 exe 产品元数据寻找 `Electron.lnk`，因此安装器另建本应用专属的隐藏兼容入口，同样指向安全启动桩；第三方 Electron 入口冲突时保留原文件。Main 在固定原 userData 后设置 VibeLink 名称，并核对主入口与兼容入口的 target/cwd/args 和两个身份字段；缺失或冲突时跳过原生初始化，绝不代为覆盖入口。注册 warmup 仅构造并关闭未显示对象，从不调用 show。能力登记与实际提醒显示独立，系统免打扰或通知权限仍可能影响显示；源码进程完全退出后的行动中心重新激活不在本次承诺范围。平台前置条件见 [Electron 通知说明](https://www.electronjs.org/docs/latest/tutorial/notifications)。

本批原生验收确认 65 文件包可重放、默认两个开关关闭、0 提醒记录、原 `local-v1` 及绝对 userData 不变。Windows 的开始菜单身份、AUMID/Activator 两属性和 Electron 创建的 CLSID 注册已核实；外部 WinRT `Get_Setting` 仍返回 `0x80070490`，因此系统通知权限状态未确认。Main 的 `supported/registered` 仅表示 API 支持与身份匹配，不代表系统已接受或显示提醒。本批未实际调用 show、开启预览、运行模型或发送外部消息；投递回执由 mock 验证，实际弹窗投递尚未验收。

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

2026-11-09 本轮 72 项登录、任务工作区及系统提醒定向 Vitest、1 项真实 HTTP 凭据隔离测试通过。登录页此前验证 48 项 UI 变体，任务面板和系统提醒面板分别验证 12 种语言 × 深浅色 × 4 种状态，共各 96 项变体并检查截图，无 warnings。全新 Prepare 目录独立安装锁定依赖后，全量 TypeScript 以 12GB 堆执行通过；该结果取代早期原型环境中的 Undici/net-policy 类型限制。

打包说明：父仓 `.gitattributes` 对 `vibelink/desktop/**` 设置 `-text`，保持文件原始字节，避免跨平台 checkout 改写换行导致 SHA256 不匹配。原型中全局替换端点、伪造账号、明文保存Token、旧中继自动启动和禁用沙箱均未纳入交付。完整原生客户端已由发布流程返回 `DESKTOP_DEV_VERDICT=ready`（真实模式、region cn、隔离profile vibelink），最终运行验收由部署方记录。

默认 GPT 的隔离上下文 host 同样直接使用官方 CLI：保留模型、上下文窗口和每模型压缩预算，通过上游本机 `model_catalog_json` 生成器传递给 Codex，不把 `custom-context` 标记当作需要模型代理的依据。只有依赖共享订阅代理的受邀 host 才本机拒绝。此分支有完整默认 spawn 参数回归及原生模型 catalog 测试覆盖。

2026-11-08 最终运行验证：安全包装真实模式返回 `DESKTOP_DEV_VERDICT=ready`，原生 Electron 窗口通过官方 Codex app-server 收到 `DESKTOP_OK`；同窗“设备与连接”读取运行 Hub 的在线状态及 PC/Mac Agent。96 项订阅入口、原生 spawn、模型 catalog 和 Renderer 桥接测试通过，另有真实 HTTP 凭据隔离测试。首次真实请求出现网络重连后完成，不能据此保证后续网络延迟。手机扫码配对仍需本人在界面完成。

## 内测管理

本机开发者登录后，在「Hub 工作区」或「设备与连接」打开「内测管理」，可显示/复制现有邀请码、查看按用户统计的每日 token 和最近调用、修改五项额度。普通内测账号无管理权限。保存冲突或回执不明时须先刷新核对，不会自动重试。管理员凭据仅在 Main 使用，详见 [操作与计量说明](../docs/beta-admin.md)。

「Hub 工作区」的「主动运行」折叠面板管理当前账号的 observe/off、规则版本、撤销和最近判断；只记录建议，不自动执行。详见 [观察首批说明](../docs/automation-observe.md)。
