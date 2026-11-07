# Bot 职位与模型策略

职位模板只保存职责、名称、权限、技能、例行任务和种子记忆，不包含引擎或模型字段。选择模板不会改变当前模型；切换模型不会改名或更换头像。更换引擎时解除旧会话绑定，避免复用其他模型的上下文。

| 资源 | 开发版 | 公测版 |
| --- | --- | --- |
| 幕僚长 | GPT，通过官方 Codex CLI | DeepSeek API |
| 其余默认职位 | DeepSeek API | DeepSeek API |
| Android applicationId | `com.wangzan101.vibelink` | `com.wangzan101.vibelink.beta` |
| Hub 数据 | `<VIBELINK_DATA>/dev` | `<VIBELINK_DATA>/beta` |
| DeepSeek Key | `VIBELINK_DEV_DEEPSEEK_API_KEY` | `VIBELINK_BETA_DEEPSEEK_API_KEY` |
| 可选原生模型 ID | `VIBELINK_DEV_DEEPSEEK_MODEL` | `VIBELINK_BETA_DEEPSEEK_MODEL` |

默认模型为 `deepseek-flash`，请求固定发送至 `https://api.deepseek.com/chat/completions`，禁用 HTTP 客户端代理与重定向。两个版本不共享 Key 的回退配置。生产部署应使用不同 DeepSeek 账号/项目和不同进程账号；程序不会从旧 Magpie 配置提取凭据。

`VIBELINK_DATA` 是数据父目录。首次升级时，只将旧版根目录下的开发者配对凭据、Bot、群聊及发布包复制至 `dev`；旧数据不会复制至 `beta`。每个公测租户进一步使用 `tenants/<tenantId>` 保存自己的 Bot、群聊、会话和审批记录。公测租户不能访问开发版 CLI、终端、文件或设备中继。会话请求和普通对话请求均计入租户额度。

## 官方 CLI 边界

GPT 仅使用官方 `@openai/codex` 安装的 CLI，并通过 ChatGPT 登录。CLI 的独立目录为 `~/.vibelink-cli/dev/codex`：首次只导入官方 CLI 的 ChatGPT 登录信息，不导入用户 provider、profile 或网关配置。启动时强制 OpenAI provider / ChatGPT 登录，清除 API Base URL、API Key、代理等环境覆盖；拒绝工作区内覆盖订阅路由的 Codex 配置，不向未经检查的交互 CLI 注入指令。

Claude 和 Gemini 当前不可选，旧 Claude/AGY 执行入口已关闭。后续恢复时必须分别实现并验证官方 Claude Code / Gemini CLI，不能重新启用 AGY 包装器或订阅转 API 的中间层。手机端旧的 ChatGPT 订阅直连 Responses 模型入口也已关闭，不再提供该登录入口；已有凭据仍可由原有退出登录流程清除。

Hub 的旧任意模型 HTTP 入口已替换为 DeepSeek 专用入口，不会转发 GPT、Claude、Gemini 请求。

开发版 Codex 可通过仓库 `tools/dispatch-bot.ps1 -BotId <目录中的ID> -PromptFile <UTF-8任务文件>` 调度 DeepSeek 专家。脚本使用本机开发 Hub 的凭据，不把密钥放进模型提示词。自定义数据目录通过 `-DataRoot` 传入。

DeepSeek 专家的开发工作区工具包括列目录、读文件和执行命令；工作树职位会先建立独立 worktree，命令经过现有审批门禁，只读/consult 模式禁止命令执行。公测 Bot 不持有开发工作区工具。中断后停止后续模型与工具步骤；已提交给操作系统的命令遵循终端本身的生命周期。

## 构建与验证

```powershell
cargo test -p vibelink-hub -p vibelink-agent -p vibelink-protocol --manifest-path vibelink/Cargo.toml
cd vibelink-android
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
.\gradlew.bat -PbetaEdition=true :app:testDebugUnitTest :app:assembleRelease
```

公测 APK 与开发 APK 使用不同包名，可并排安装，数据、缓存、配对 Token、模型配置均由 Android 沙箱隔离。更新清单与 APK 下载支持 `edition=dev|beta`；客户端只选择本版本对应的 APK。安装包命名须分别包含 `-dev` 与 `-beta`。

配置 Key 并完成官方 CLI 登录后，需要分别验证一次真实 DeepSeek 对话及 Codex → DeepSeek 派单。本机未提供 API Key 时，接口返回明确的配置缺失错误，不回退到共享凭据或其他模型。

依据：[OpenAI CLI 文档](https://learn.chatgpt.com/docs/cli/reference)、[ChatGPT 登录](https://learn.chatgpt.com/docs/auth)、[DeepSeek 原生 API 与模型名](https://api-docs.deepseek.com/)。

本次本机验证：Rust Hub/Agent 共 29 项测试通过，两个版本的相关 Android 模型策略/订阅入口/更新测试各 24 项通过。全量 Android 测试未通过：core 共 2245 项、55 项失败，app 共 921 项、73 项失败（后者包含随后已修复的 5 项更新源测试）。失败包含 Windows 缺少 POSIX `/bin/sh`、Unix 文件属性和软链接语义、缺失 rootfs 资源及现有功能断言。未修改这些无关实现，也未宣称全量测试通过。真实模型联调需要另外配置两个版本各自的 DeepSeek Key。
