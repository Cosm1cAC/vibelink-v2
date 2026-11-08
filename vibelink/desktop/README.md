# VibeLink 桌面融合

基于 [makecindy/cindy](https://github.com/makecindy/cindy) 的 Apache-2.0 源码，固定上游 commit `d53e60e099`（完整值见 manifest.json）。保留上游许可，使用可校验补丁和新增源文件分发；不包含 Cindy 的 Git 历史、用户数据、模型凭据或 Clash 配置。

同一个桌面窗口中保留 Cindy 会话、工作区、设置与项目工具，侧栏“设备与连接”打开 VibeLink Hub/Agent 管理。“连接手机”显示由 Hub 签发的一次性二维码。界面通过受信任的 preload IPC 调用 Main，Main 请求本机服务；Hub 管理员凭据只由本机服务读取，不传给 Renderer。不创建模型反代，也不更改 maker-core、数据库 schema 或订阅登录信息。融合模式在订阅翻译入口本机拒绝 GPT/Claude/Gemini，GPT 官方 Codex app-server 使用原生 openai provider，不再接入 Cindy 的本机模型代理；API Key 供应商原有入口保留。

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

默认连接 `http://127.0.0.1:18995`，读取现有 `%LOCALAPPDATA%\vibelink-mesh\data\dev\token.txt`，兼容旧 `data\token.txt`。没有新建或明文持久化自定义 Token 的入口。Main 环境可显式指定 `VIBELINK_HUB` 与 `VIBELINK_TOKEN`；HTTPS 正常验证证书，远程 Hub 不会误用本机 Token。界面连接设置只显示地址和是否已配置，不显示或编辑凭据。

扫码流程 `/api/pair/start` → 一次性二维码 → 手机确认 → `/api/pair/status/{id}`，管理员 Token 始终不出 Main 本机服务。二维码为临时配对授权，应仅给本人手机扫描。

## 自检

在已安装依赖的融合 worktree 内：

```powershell
node --test apps/desktop/src/main/vibelink/embeddedServer.test.cjs
pnpm --dir apps/desktop exec vitest run src/renderer/features/vibelink/desktopBridge.test.ts src/main/maker-host/__tests__/vibelinkSubscriptionPolicy.test.ts src/main/maker-host/__tests__/piNativeSubscriptionForwarding.test.ts --maxWorkers=1
pnpm ui:check --page vibelink-desktop --theme all
```

首个测试通过临时 HTTP Hub 验证实际认证、dev 凭据路径、状态不泄露 Token，并检查第三方网页 Origin 被拒绝；桥接测试验证原生窗口走 IPC。浏览器场景覆盖深浅色、内嵌导航、命令失败与二维码展示。全量 TypeScript 检查未通过完成：该超大仓库本机检查在 4GB 堆下耗尽内存，12GB 重试耗时较长后中止；不可据此声称全量检查通过。

打包说明：父仓 `.gitattributes` 对 `vibelink/desktop/**` 设置 `-text`，保持文件原始字节，避免跨平台 checkout 改写换行导致 SHA256 不匹配。原型中全局替换端点、伪造账号、明文保存Token、旧中继自动启动和禁用沙箱均未纳入交付。完整原生客户端已由发布流程返回 `DESKTOP_DEV_VERDICT=ready`（真实模式、region cn、隔离profile vibelink），最终运行验收由部署方记录。

默认 GPT 的隔离上下文 host 同样直接使用官方 CLI：保留模型、上下文窗口和每模型压缩预算，通过上游本机 `model_catalog_json` 生成器传递给 Codex，不把 `custom-context` 标记当作需要模型代理的依据。只有依赖共享订阅代理的受邀 host 才本机拒绝。此分支有完整默认 spawn 参数回归及原生模型 catalog 测试覆盖。

2026-11-08 最终运行验证：安全包装真实模式返回 `DESKTOP_DEV_VERDICT=ready`，原生 Electron 窗口通过官方 Codex app-server 收到 `DESKTOP_OK`；同窗“设备与连接”读取运行 Hub 的在线状态及 PC/Mac Agent。96 项订阅入口、原生 spawn、模型 catalog 和 Renderer 桥接测试通过，另有真实 HTTP 凭据隔离测试。首次真实请求出现网络重连后完成，不能据此保证后续网络延迟。手机扫码配对仍需本人在界面完成。
