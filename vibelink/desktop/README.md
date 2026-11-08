# VibeLink 桌面融合

基于 [makecindy/cindy](https://github.com/makecindy/cindy) 的 Apache-2.0 源码，固定上游 commit `d53e60e099`（完整值见 manifest.json）。保留上游许可，使用可校验补丁和新增源文件分发；不包含 Cindy 的 Git 历史、用户数据、模型凭据或 Clash 配置。

融合版现在使用 VibeLink 统一登录：登录页提供内测码、手机扫码和显式“使用本机工作区”。不再要求 Cindy 云账号，也不伪造 Cindy membership。开发版 owner 登录后保留 Cindy 会话、项目工具与“设备与连接”；内测主体进入独立 Hub 工作区，本机模型执行被 Main 拒绝。后续任务面板单独交付。

订阅模型仍只走官方 CLI。GPT 官方 Codex app-server 使用原生 OpenAI provider；融合模式拒绝订阅翻译代理以及原语音润色中的直接 ChatGPT OAuth HTTP 调用。非融合上游行为与 API Key 供应商入口保持原有语义。没有修改 maker-core 或数据库 schema。

## 准备与启动

```powershell
.\Prepare-CindyFusion.ps1 -CindyRepository D:\AI\VibeLink\cindy
pnpm --dir "$env:LOCALAPPDATA\vibelink-mesh\desktop\source" install --frozen-lockfile
.\Start-VibeLinkDesktop.ps1
```

准备器只从已有本机仓库创建固定 commit 的新 worktree，再应用校验过的文件；目标已存在时拒绝覆盖。不会读取或复制 `.user-data`、`.relay-data`、Token、登录态或 SQLite 数据。

启动器沿用上游安全启动包装、默认使用外置命名隔离 profile `vibelink`，不带 `--no-sandbox`，不改系统代理、不停止 Hub/Agent、不自动启动旧 Relay，也不迁移原有 Cindy 账号。原 profile 数据完整保留在原位置；是否切换到已存在的 profile 由最终部署处理。

指定已准备的源码：

```powershell
.\Start-VibeLinkDesktop.ps1 -SourceRoot D:\AI\VibeLink\.vibelink-worktrees\cindy-fusion
```

安装桌面和开始菜单的统一入口（保留旧入口备份）：

```powershell
.\Install-DesktopEntry.ps1 -SourceRoot D:\AI\VibeLink\.vibelink-worktrees\cindy-fusion
```

此交付为源码启动版，需要保留已准备的源码与依赖；不是独立桌面安装包，也未发布桌面自更新通道。入口优先打开该源码已经运行的窗口，未运行时才使用安全包装编译启动；后台控制台隐藏。现有 `CindyVibelinkDesktop` 登录任务会使用同一新入口，旧 Relay 仍保持禁用。开发模式不运行上游桌面自动更新。

只有输出 `DESKTOP_DEV_VERDICT=ready` 才表示窗口、登录/本机模式和数据库均就绪。浏览器 UI 验证不等于完整桌面启动成功。

## 连接与凭据

开发版登录固定连接 `http://127.0.0.1:18995`；内测码与内测扫码固定连接 `https://hub.vibelink.cloud`。Main 的 Electron 网络层继承当前系统网络设置，不写死代理地址或修改系统代理。Renderer 不能指定凭据目的地址。

Main 保存由 Hub 验证的 client session，token 和 QR pollSecret 不进入 Renderer；只有一次性扫码 link、核对码和公开 session 信息用于界面。持久凭据通过 Electron safeStorage 加密，保存并刷盘后才确认 QR 消费；ACK 明确 denied/expired 会撤销本机能力与 owner。网络丢 ACK 保留 Main 私有证明重试。空 profile 不主动探测 Keychain；安全存储不可用不降级到明文。退出使用无秘密的持久 tombstone，系统凭据库锁定时同样阻止重启自动恢复。

数据目录以 Hub 稳定 issuer、edition、userId 的散列隔离。只有显式本机管理员交换得到的真实 owner 可以在 Main 建立到旧 `local-v1` 的受信映射，继续本机历史；其他远程或内测同名用户不会套用该映射。原 Cindy 凭据、旧 DB 和原 profile 保留，不复制到源码或发布包。

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

本轮 49 项定向 Vitest 与真实 HTTP 测试通过；登录页 12 种语言 × 深浅色 × fresh/scanned 共 48 项 UI 变体通过并检查截图。全量 TypeScript 已以 12GB 堆执行，旧 `outbound-fetch.ts` 的 Undici DispatchHandler 以及生成 net-policy IPv6 类型与当前依赖不兼容；不能声称全量 typecheck 通过。新增登录代码的类型问题已逐项修正。

打包说明：父仓 `.gitattributes` 对 `vibelink/desktop/**` 设置 `-text`，保持文件原始字节，避免跨平台 checkout 改写换行导致 SHA256 不匹配。原型中全局替换端点、伪造账号、明文保存Token、旧中继自动启动和禁用沙箱均未纳入交付。完整原生客户端已由发布流程返回 `DESKTOP_DEV_VERDICT=ready`（真实模式、region cn、隔离profile vibelink），最终运行验收由部署方记录。

默认 GPT 的隔离上下文 host 同样直接使用官方 CLI：保留模型、上下文窗口和每模型压缩预算，通过上游本机 `model_catalog_json` 生成器传递给 Codex，不把 `custom-context` 标记当作需要模型代理的依据。只有依赖共享订阅代理的受邀 host 才本机拒绝。此分支有完整默认 spawn 参数回归及原生模型 catalog 测试覆盖。

2026-11-08 最终运行验证：安全包装真实模式返回 `DESKTOP_DEV_VERDICT=ready`，原生 Electron 窗口通过官方 Codex app-server 收到 `DESKTOP_OK`；同窗“设备与连接”读取运行 Hub 的在线状态及 PC/Mac Agent。96 项订阅入口、原生 spawn、模型 catalog 和 Renderer 桥接测试通过，另有真实 HTTP 凭据隔离测试。首次真实请求出现网络重连后完成，不能据此保证后续网络延迟。手机扫码配对仍需本人在界面完成。
