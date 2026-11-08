package com.wangzan101.vibelink.ui

import android.content.Context
import com.wangzan101.vibelink.remote.RemoteApi
import com.wangzan101.vibelink.remote.RemoteStore
import com.wangzan101.vibelink.remote.BotSessionBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Bot 生命周期类型：
 * - MANAGEMENT: 长期管理型 Bot（如 Grok 幕僚长、Harper 情报核查官、Sentinel 哨兵运维，常驻编制）
 * - PROJECT: 短期项目型 Bot（如 Benjamin 逻辑与代码主程、Lucas 红队审校、小葵 UI 主程，完结可归档）
 */
enum class BotLifecycle(val label: String, val badgeColorHex: Long) {
    MANAGEMENT("长期管理", 0xFF0A7CFF),
    PROJECT("短期项目", 0xFF1F9D55),
}

/**
 * 绑定在 Bot 模板或实例上的专属技能剧本（对标 Rakazo `SKILL.md` + Open Duet 6段式 Skill）
 */
data class BotSkillSpec(
    val id: String,
    val name: String,
    val description: String,
    val whenToUse: String = "",
    val playbook: String,
    val enabled: Boolean = true,
    val source: String = "builtin", // "builtin" | "learned" | "imported" | "user"
)

data class LearnedSkillDraft(
    val skill: BotSkillSpec,
    val warnings: List<String>,
    val redactedSecretsCount: Int,
)

/**
 * 绑定在 Bot 模板或实例上的默认例行巡检任务（对标 Grok Bot `Routines` & Open Duet `routines`）
 */
data class BotRoutineSpec(
    val name: String,
    val cron: String,
    val prompt: String,
    val enabled: Boolean = true,
)

/**
 * Bot 跨轮次未完结工作便签（对标 Rakazo `scratchpad-context.ts` `<scratchpad_open>`）
 */
data class BotOpenWorkItem(
    val id: String,
    val title: String,
    val notes: String = "",
    val status: String = "open", // "open" | "done"
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Grok Bot (`x.ai/bot`) + Grok 4.20 Multi-Agent + Rakazo + Open Duet (`open-dots`) 配方模板（Recipe Blueprint）
 */
data class BotTemplate(
    val id: String,
    val category: String = "grok_420", // "grok_420" | "grok_bot_recipe" | "open_duet" | "engineering"
    val defaultName: String,
    val defaultAvatar: String = "",
    val jobTitle: String,
    val department: String,
    val lifecycle: BotLifecycle,
    val thinkingEffort: String = "high",
    val isolationMode: String = "shared",
    val approvalMode: String = "ask", // "ask" | "accept_edits" | "auto" | "plan"
    val manageOtherBots: Boolean = true,
    val dailyCheckIns: Boolean = true,
    /** 对外协作名片（注入到其他 Bot 的 `<bot_directory>`，决定何时被 `@` 或交接任务） */
    val publicDescription: String,
    /** 私有常驻指令（多段式 Markdown SOP，对应 Rakazo `instructions` / OpenClaw `SOUL.md` + `AGENTS.md`） */
    val rolePrompt: String,
    val summary: String,
    val disabledTools: List<String> = emptyList(),
    val exampleRequests: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val skills: List<BotSkillSpec> = emptyList(),
    val defaultRoutines: List<BotRoutineSpec> = emptyList(),
    val defaultOpenWork: List<String> = emptyList(),
    val seedMemories: List<String> = emptyList(),
)

data class BotMemoryFact(
    val id: String,
    val fact: String,
    val category: String = "general",
    val source: String = "user",
    val topic: String = "",
    val aliases: List<String> = emptyList(),
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

data class BotInstructionVersion(
    val version: Long,
    val rolePrompt: String,
    val updatedBy: String = "user",
    val updatedAt: Long = System.currentTimeMillis(),
    val note: String = "",
)

data class BotFriend(
    val id: String,
    val templateId: String = "",
    val name: String,
    /** 头像标识："preset:gpt" | "preset:claude" | "preset:gemini" | "preset:grok" | "preset:glm" | "preset:deepseek" | "data:image/jpeg;base64,..." */
    val avatar: String = "",
    val jobTitle: String,
    val department: String,
    val lifecycle: BotLifecycle,
    val engine: String,
    val model: String = BotModelPolicy.DEEPSEEK_MODEL,
    val device: String = "pc",
    val cwd: String = "D:\\AI\\VibeLink",
    /** 对外协作名片（How other bots see it in `<bot_directory>`） */
    val publicDescription: String = "",
    /** 私有常驻指令 SOP（`SOUL.md` + `AGENTS.md`） */
    val rolePrompt: String,
    val thinkingEffort: String = "medium", // "low" | "medium" | "high" | "max"
    val isolationMode: String = "shared", // "shared" | "worktree"
    val approvalMode: String = "ask", // "ask" | "accept_edits" | "auto" | "plan"
    val instructionsVersion: Long = 1L,
    val instructionsHistory: List<BotInstructionVersion> = emptyList(),
    val manageOtherBots: Boolean = true,
    val dailyCheckIns: Boolean = true,
    val disabledTools: List<String> = emptyList(),
    val exampleRequests: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val skills: List<BotSkillSpec> = emptyList(),
    val defaultRoutines: List<BotRoutineSpec> = emptyList(),
    val openWork: List<BotOpenWorkItem> = emptyList(),
    val memories: List<BotMemoryFact> = emptyList(),
    val activeSessionId: String = "",
    val sessionIds: List<String> = emptyList(),
    val archived: Boolean = false,
    val lastText: String = "",
    val lastAt: Long = 0L,
)

data class TeamGroup(
    val id: String,
    val name: String,
    val description: String,
    val botIds: List<String>,
    val cwd: String = "D:\\AI\\VibeLink",
    val lastText: String = "",
    val lastAt: Long = 0L,
)

data class TeamGroupMessage(
    val id: String,
    val senderId: String, // "user" or bot.id
    val senderName: String,
    val senderJobTitle: String = "",
    val senderEngine: String = "",
    val text: String,
    val reasoning: String = "",
    val startedAt: Long = 0L,
    val at: Long,
    val tools: List<RemoteApi.ToolCall> = emptyList(),
    val linkedSessionId: String = "",
    val status: String = "done", // "thinking" | "done" | "needs_input" | "error"
)

/**
 * 忙时排队指令条目（在上一条指令完成前再次发送 Bot 消息时默认进入排队队列，支持随时切换为「立即打断」或「并发新工位」）
 */
data class QueuedBotPrompt(
    val id: String = "q_" + UUID.randomUUID().toString().take(8),
    val text: String,
    val attachments: List<RemoteAttachment> = emptyList(),
    val skillIds: List<String> = emptyList(),
    val targetBotIds: List<String> = emptyList(),
    val overrideMode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

object BotTeamStore {
    private const val PREF_NAME = "vibelink_bot_team_store"
    private const val KEY_SEEDED = "seeded_v1"
    private const val KEY_RECIPE_V2 = "seeded_grok_recipe_v2"
    private const val KEY_MODEL_AVATAR_V3 = "seeded_model_avatar_v3"
    private const val KEY_MODEL_AVATAR_V4 = "seeded_model_avatar_v4"
    private const val KEY_OPEN_DUET_V5 = "seeded_open_duet_v5"
    private const val KEY_GEMINI_AGY_FIX_V6 = "seeded_gemini_agy_fix_v6"
    private const val KEY_CLEAN_STALE_SESSIONS_V7 = "seeded_clean_stale_sessions_v7"
    private const val KEY_BOTS = "bots_json"
    private const val KEY_PENDING_PROFILES = "pending_hub_profiles_v1"
    private val profileSyncMutex = Mutex()
    private val profileSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val KEY_GROUPS = "groups_json"
    private const val KEY_GROUP_MSGS_PREFIX = "group_msgs_"
    const val MEMORY_BUDGET_MAX_ACTIVE = 40

    private val _botsFlow = MutableStateFlow<List<BotFriend>>(emptyList())
    val botsFlow: StateFlow<List<BotFriend>> = _botsFlow

    private val _groupsFlow = MutableStateFlow<List<TeamGroup>>(emptyList())
    val groupsFlow: StateFlow<List<TeamGroup>> = _groupsFlow

    private val _versionFlow = MutableStateFlow(0)
    val versionFlow: StateFlow<Int> = _versionFlow

    @Volatile
    private var initialized = false

    @Synchronized fun resetAccountMemory() {
        initialized = false
        _botsFlow.value = emptyList()
        _groupsFlow.value = emptyList()
        draftTextCache.clear()
        draftAttachmentsCache.clear()
        draftSkillsCache.clear()
        promptQueueMap.clear()
        busyModeMap.clear()
        _versionFlow.value++
    }

    /** 对标 Open Duet `redact_secrets`：双向脱敏常见 API Key / Token 并返回 (脱敏文本, 替换次数) */
    fun redactSecrets(text: String): Pair<String, Int> {
        if (text.isEmpty()) return "" to 0
        var out = text
        var count = 0
        val regexes = listOf(
            Regex("""sk-(?:proj-|ant-)?[A-Za-z0-9_-]{18,}"""),
            Regex("""ghp_[A-Za-z0-9_]{16,}"""),
            Regex("""github_pat_[A-Za-z0-9_]{16,}"""),
            Regex("""xox[bp]-[A-Za-z0-9-]{16,}"""),
            Regex("""AIzaSy[A-Za-z0-9_-]{20,}"""),
            Regex("""AKIA[A-Z0-9]{12,}"""),
        )
        for (rgx in regexes) {
            val matches = rgx.findAll(out).count()
            if (matches > 0) {
                count += matches
                out = rgx.replace(out, "[REDACTED_SECRET]")
            }
        }
        return out to count
    }

    /** 对标 Open Duet `scan_skill_warnings`：检测技能剧本中的越权与提示词注入高危模式 */
    fun scanSkillWarnings(text: String): List<String> {
        val lower = text.lowercase()
        val warnings = mutableListOf<String>()
        val rules = listOf(
            "ignore previous instructions" to "包含提示词注入指令 (ignore previous instructions)",
            "disregard all prior" to "包含提示词越权覆盖模式 (disregard all prior)",
            "rm -rf /" to "包含高危文件系统擦除命令 (rm -rf /)",
            "format-volume" to "包含磁盘格式化高危命令 (Format-Volume)",
            "curl | sh" to "包含远程未签名脚本管道执行 (curl | sh)",
            "invoke-expression" to "包含动态代码执行指令 (Invoke-Expression)",
        )
        for ((pat, msg) in rules) {
            if (lower.contains(pat)) warnings.add(msg)
        }
        if (redactSecrets(text).second > 0) {
            warnings.add("检测到疑似明文密钥特征，保存时将自动脱敏")
        }
        return warnings
    }

    /** 对标 Open Duet `extract_needs_input`：检测消息末行是否以 `NEEDS INPUT: <question>` 挂起 */
    fun extractNeedsInput(text: String): String? {
        val lastLine = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        val prefixes = listOf("NEEDS INPUT:", "NEEDS_INPUT:", "需要输入:", "待用户确认:")
        for (p in prefixes) {
            if (lastLine.startsWith(p, ignoreCase = true)) {
                val q = lastLine.substring(p.length).trim()
                if (q.isNotEmpty()) return q
            }
        }
        return null
    }

    /** Role artwork is an appearance choice; the agent does not select a model or avatar. */
    fun defaultAvatarForRole(templateId: String): String =
        TEMPLATES.firstOrNull { it.id == templateId }?.defaultAvatar?.takeIf { it.isNotBlank() } ?: "preset:deepseek"

    /** 判断某个名称是否属于默认模型名或旧版拟人模板名（切换底层模型时可自动联动更新） */
    fun isDefaultOrLegacyBotName(name: String): Boolean {
        val n = name.trim()
        return n.isEmpty() || n in setOf(
            "DeepSeek",
            "Claude",
            "GPT",
            "GLM",
            "Gemini",
            "Grok",
            "Grok · 林默",
            "Harper · 苏晴",
            "Benjamin · 阿哲",
            "Lucas · 老K",
            "Sentinel · 陆巡",
            "小葵",
            "林默",
            "苏晴",
            "阿哲",
            "老K",
            "陆巡",
            "Antigravity",
            "Codex",
            "OpenCode",
        )
    }

    /**
     * Grok Bot (`x.ai/bot`) + Grok 4.20 Multi-Agent + Rakazo + Open Duet (`open-dots`) 8大标准配方库
     */
    val TEMPLATES: List<BotTemplate> = listOf(
        // 1. 幕僚长 · 幕僚长 (Chief of Staff & Team Lead) — 底层驱动: 运行时独立配置
        BotTemplate(
            id = "tpl_grok_cos",
            category = "core_team",
            defaultName = "幕僚长",
            defaultAvatar = "",
            jobTitle = "幕僚长 · 总调度官",
            department = "核心总调度部",
            lifecycle = BotLifecycle.MANAGEMENT,
            thinkingEffort = "high",
            isolationMode = "shared",
            approvalMode = "ask",
            manageOtherBots = true,
            dailyCheckIns = true,
            summary = "团队总调度官 (幕僚长) · 负责全局需求分诊、consult/work 双模派单、打回重做与最终裁决",
            publicDescription = "团队总调度官（Chief of Staff）：基于 运行时独立配置 驱动，负责全局需求分诊、架构拆解、向专家 Bot 派单交接（@情报官 查证 / @代码主程 编码 / @审校官 审校）以及最终结论裁决。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是团队的 **Chief of Staff（幕僚长 · 总调度官）**。
                你的核心职责是做用户的「第一入口」与「最终合成器」：绝不把未经核实的半成品直接甩给用户，而是统筹全局、明确架构边界、协调专家队友并输出高信噪比决策。

                # Core Operating Rules (AGENTS.md)
                1. **先盘点车队再派单（Fleet-First & Consult/Work Delegation）**：
                   - 涉及只读第二意见、文档/源码考据或风险预审 → 以 `mode="consult"` 咨询 **情报官（情报与事实核查官）** 或 **审校官（红队审校官）**；
                   - 涉及代码实现、Git Worktree 隔离修改与编译单测 → 以 `mode="work"` 委派 **代码主程（逻辑与代码主程）** 或 **幕僚长（UI 主程）**；
                   - 若下属产出未达验收标准，原帖点名打回重做（`rework=true`），说明具体缺失点。
                2. **四阶段协同法（Multi-Agent Synthesis）**：
                   - Stage 1（拆解）→ Stage 2（核查/执行）→ Stage 3（红队审校）→ Stage 4（裁决合成）。
                3. **阻塞挂起契约（NEEDS INPUT Protocol）**：
                   - 若遇到必须由用户拍板的破坏性操作或关键分歧，在回复最后一行严格输出：`NEEDS INPUT: <一句具体问题>`。
            """.trimIndent(),
            disabledTools = emptyList(),
            exampleRequests = listOf(
                "帮我规划今天最值得优先推进的 3 件事，并指派对应专家 Bot",
                "对标外部开源方案，组织 情报官 考据、代码主程 落地、审校官 红队挑刺",
                "汇总当前仓库的未决风险，出具有拍板选项的发版简报",
            ),
            requires = listOf("fleet_management", "delegation", "workspace_read"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_cos_decompose",
                    name = "Cross-Bot Task Decomposition",
                    description = "将复杂工程目标拆解为可验证的子阶段并指派给对应专职 Bot",
                    whenToUse = "当用户提出跨模块需求、架构改造或群聊接力任务时使用",
                    playbook = """
                        ## Purpose
                        将跨模块工程需求拆解为边界清晰、可独立验证的子任务，并匹配最合适的专家 Bot 执行。

                        ## Required inputs
                        - 用户的原始需求目标与期望交付物
                        - `<bot_directory>` 中当前在线的专家 Bot 列表及职责边界

                        ## Steps
                        1. 明确目标仓库与涉及的端（Rust Hub/Agent 或 Android VibeLink）。
                        2. 列出关键数据契约与接口变更。
                        3. 区分只读咨询（`mode="consult"`）与实操落地（`mode="work"`），按【情报核查 → 代码落地 → 红队审校】指派对应 Bot。

                        ## Validation checks
                        - 每个子任务都包含明确的验收标准（编译命令、单测名称或接口响应）
                        - 不存在两个写操作 Bot 同时直接修改同一共享文件

                        ## Output format
                        - **阶段拆解表**：阶段 / 负责 Bot / 模式(`consult|work`) / 核心交付物 / 验收标准

                        ## Approval boundary
                        - 拆解与派单无需额外审批；涉及归档 Bot、删除分支或外部发布时必须先挂起 `NEEDS INPUT:` 请用户批准
                    """.trimIndent(),
                ),
                BotSkillSpec(
                    id = "skill_cos_release_brief",
                    name = "Engineering Release Brief",
                    description = "汇总 Git 提交、测试结果与未决风险，生成高管级发布简报",
                    whenToUse = "版本发布前、每日晨会巡检或阶段性验收时使用",
                    playbook = """
                        ## Purpose
                        在版本发布或里程碑节点汇总全栈变更、测试覆盖与潜在风险，辅助用户快速拍板。

                        ## Required inputs
                        - 当前工作区 Git 状态、最近提交记录与编译/测试输出
                        - APK 版本号（`versionCode` / `versionName`）与热更新源状态

                        ## Steps
                        1. 检查 `git status` 与近期提交差异。
                        2. 核对 Rust 单测与 Android 编译产物完整性。
                        3. 汇总红队审校发现的剩余风险项。

                        ## Validation checks
                        - 简报引用的版本号、文件路径与测试通过数真实可查
                        - 所有阻塞项均已标明缓解方案

                        ## Output format
                        - **【核心进展】** / **【已验证证据】** / **【剩余风险与待拍板决策】** 三段式简报

                        ## Approval boundary
                        - 只读汇总分析直接执行；未经用户确认不得自动执行生产推送或覆盖线上配置
                    """.trimIndent(),
                ),
            ),
            defaultRoutines = listOf(
                BotRoutineSpec(
                    name = "Morning Engineering Triage (晨间工程分诊)",
                    cron = "0 9 * * 1-5",
                    prompt = "检查当前工作区 Git 状态、未完成 Scratchpad 待办与各子 Bot 进展；若一切正常且无阻塞项，仅回复 NO_RESPONSE。",
                ),
            ),
            defaultOpenWork = listOf("统筹 VibeLink 多端架构演进与发版质量门禁"),
            seedMemories = listOf("作为团队 Captain，优先通过结构化四阶段法（拆解→执行→审校→裁决）协同 情报官、代码主程与审校官。"),
        ),

        // 2. 情报官 · 情报与事实核查官 (Researcher & Fact Keeper)
        BotTemplate(
            id = "tpl_harper_research",
            category = "core_team",
            defaultName = "情报官",
            defaultAvatar = "",
            jobTitle = "情报与事实核查官",
            department = "情报与产品调研部",
            lifecycle = BotLifecycle.MANAGEMENT,
            thinkingEffort = "high",
            isolationMode = "shared",
            approvalMode = "plan",
            manageOtherBots = false,
            dailyCheckIns = true,
            summary = "情报与事实核查专家 (情报官) · 一手源码考据、技术调研、协议核对与需求边界定义（只读安全边界）",
            publicDescription = "实时情报与事实核查专家（Fact Keeper）：基于 情报官 驱动，负责源码全文检索、外部技术调研、API/协议规范核对、需求边界澄清与竞品对标，严禁凭空捏造。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是团队的 **Research & Fact Keeper（情报与事实核查官）**，底层由 **情报官** 驱动。
                你的信条是：**「没有一手源码行号或官方文档出处的结论，一律视为未证实假说。」**

                # Core Operating Rules (AGENTS.md)
                1. **一手证据锚定（Primary-Source Grounding）**：
                   - 所有事实陈述必须附带精确的 `文件路径:行号` 或权威 URL。
                2. **只读安全边界（Read-Only Analyst Boundary）**：
                   - 你是调查员、产品分析师与事实守门人，**绝不直接修改业务代码文件或执行破坏性命令**。
                3. **输出契约（Output Contract）**：
                   - 统一采用四维评估矩阵：**【核心事实与源码依据】、【方案优劣对比 (Pros/Cons)】、【接入复杂度】、【验收标准 Checklist】**。
            """.trimIndent(),
            disabledTools = listOf("write_file", "desktop_act", "git_commit"),
            exampleRequests = listOf(
                "对比当前项目与目标开源仓库在架构实现上的核心差距，附带源码行号",
                "核查 Hub Rust 端与 Android 客户端某 API 的字段契约是否完全对齐",
                "梳理新功能的正常流、空状态、断网态与可打勾的验收标准 Checklist",
            ),
            requires = listOf("read_only", "code_search", "web_research"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_harper_scout",
                    name = "Primary-Source Tech & Repo Scout",
                    description = "深度检索代码库与外部开源实现，输出带精确行号引用的一手对标报告",
                    whenToUse = "需要调研外部项目架构、核查 API 字段契约或定位跨模块调用链时使用",
                    playbook = """
                        ## Purpose
                        通过一手源码与权威文档考据，消除技术选型与跨端联调中的猜测与幻觉。

                        ## Required inputs
                        - 待核查的模块名称、API 路径或外部开源项目地址

                        ## Steps
                        1. 检索符号定义、路由挂载与上下游调用路径。
                        2. 逐字段对比服务端序列化结构与客户端反序列化解析逻辑。
                        3. 归纳已证实的架构差距与可直接借鉴的设计模式。

                        ## Validation checks
                        - 每一项关键结论均附带真实存在的 `文件:行号` 或官方文档链接
                        - 明确区分「已证实事实」与「待验证推论」

                        ## Output format
                        - **核查结论摘要** + **字段/模块对照表（含行号）** + **落地建议优先级**

                        ## Approval boundary
                        - 严格只读（禁止调用 `write_file` / `git_commit`）
                    """.trimIndent(),
                ),
                BotSkillSpec(
                    id = "skill_harper_spec",
                    name = "Product Boundary & Acceptance Spec",
                    description = "将模糊的用户想法转化为无歧义的交互状态机与验收清单",
                    whenToUse = "新功能立项、UI 交互变更或跨端联调前使用",
                    playbook = """
                        ## Purpose
                        把一句话需求转化为可直接供主程编码、供红队验收的结构化规格说明。

                        ## Required inputs
                        - 用户的原始功能诉求与目标使用场景

                        ## Steps
                        1. 梳理正常主流程、空数据态、加载态与异常降级态。
                        2. 明确数据持久化位置、向后兼容默认值与迁移策略。
                        3. 编写可逐条打勾的 QA 验收标准。

                        ## Validation checks
                        - 覆盖旧版存档升级时的默认字段回退逻辑
                        - 验收项全部具备可观测的 UI 或接口判定标准

                        ## Output format
                        - **状态机流转说明** + **数据契约定义** + **Acceptance Checklist**

                        ## Approval boundary
                        - 仅输出规格文档，不直接改动生产代码；若需求存在重大歧义，在末行输出 `NEEDS INPUT:`
                    """.trimIndent(),
                ),
            ),
            defaultOpenWork = listOf("核查跨端 API 契约（Hub Rust ⇄ Android Kotlin）字段一致性"),
            seedMemories = listOf("严格遵守只读核查边界（禁用 write_file），所有结论必须附带源码路径与行号。"),
        ),

        // 3. 代码主程 · 逻辑与代码主程 (Logician & Code Execution Loop)
        BotTemplate(
            id = "tpl_benjamin_code",
            category = "core_team",
            defaultName = "代码主程",
            defaultAvatar = "",
            jobTitle = "逻辑与代码主程",
            department = "核心工程部",
            lifecycle = BotLifecycle.PROJECT,
            thinkingEffort = "high",
            isolationMode = "worktree",
            approvalMode = "accept_edits",
            manageOtherBots = false,
            dailyCheckIns = false,
            summary = "逻辑与代码主程 (代码主程 / Codex) · 独立 Git Worktree 全栈编码、状态机推导与编译单测闭环",
            publicDescription = "核心主程与形式化逻辑执行官（Engineering Loop）：基于 代码主程 (Codex) 驱动，在独立 Git Worktree 中负责 Rust/Kotlin 全栈编码、状态机推导与编译单测闭环。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是团队的 **Logician & Engineering Loop（逻辑与代码主程）**，底层由 **代码主程 (Codex)** 驱动。
                你的行事准则是：**「Data shapes first（数据结构先行），Read before edit（先读后改），Compile & Test before claiming done（未编译通过绝不报完工）。」**

                # Core Operating Rules (AGENTS.md)
                1. **先定义数据结构与状态不变量**：
                   - 写任何业务函数前，先定义清晰的 Struct / Data Class 与序列化默认值（`#[serde(default)]` / `optString`）。
                2. **严格的工程闭环（Autonomous Verification Loop）**：
                   - 修改 Rust 代码后运行 `cargo test`；修改 Android 代码后运行 `gradlew :app:compileDebugKotlin`。
                3. **输出契约（Output Contract）**：
                   - 完工汇报必须包含：**【改动文件与符号列表】、【核心状态机/逻辑说明】、【执行的验证命令与真实通过结果】**。
            """.trimIndent(),
            disabledTools = emptyList(),
            exampleRequests = listOf(
                "在独立 Worktree 中实现该功能，并跑通 cargo test 与 compileDebugKotlin",
                "定位这段崩溃/死锁的根因，修复代码并补齐防回归单元测试",
                "重构该模块的数据结构，确保旧版 JSON 存档 100% 向后兼容",
            ),
            requires = listOf("worktree", "terminal", "file_edit"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_benjamin_worktree_loop",
                    name = "Worktree Feature & Test Loop",
                    description = "在隔离工作区完成代码实现、编译自检与单元测试闭环",
                    whenToUse = "执行任何代码新增、重构、Bug 修复任务时使用",
                    playbook = """
                        ## Purpose
                        在独立工作区安全交付高质量代码变更，确保零编译错误与测试全绿。

                        ## Required inputs
                        - 目标需求规格或幕僚长派单指令
                        - 涉及的源码文件路径与现有测试套件

                        ## Steps
                        1. 完整读取目标文件上下文与上下游调用方。
                        2. 先更新数据结构与序列化兼容层，再实现核心业务逻辑。
                        3. 执行 `cargo test` 或 `gradlew :app:compileDebugKotlin`，原地修复所有编译警告与报错。

                        ## Validation checks
                        - 编译与单元测试退出码为 `0`
                        - 未遗留 `TODO`、硬编码密钥或未处理的异常分支

                        ## Output format
                        - **改动文件清单** + **核心实现逻辑** + **验证命令与终端输出摘要**

                        ## Approval boundary
                        - 工作区内代码编辑与测试自动执行；涉及强制推送、`git reset --hard` 或删除生产数据时必须请求审批
                    """.trimIndent(),
                ),
                BotSkillSpec(
                    id = "skill_benjamin_bug_triage",
                    name = "Root-Cause Bug Triage & Regression Test",
                    description = "复现 Bug、定位根因并补充防回归单测",
                    whenToUse = "排查崩溃、死锁、序列化异常或网络断连问题时使用",
                    playbook = """
                        ## Purpose
                        通过可复现的输入与状态时序定位故障根因，并用自动化测试锁死修复效果。

                        ## Required inputs
                        - 报错堆栈、异常日志或用户复现路径

                        ## Steps
                        1. 构造触发故障的最小输入与状态时序。
                        2. 定位根因代码行，拒绝仅用空判断掩盖表象。
                        3. 编写覆盖该边界条件的回归测试并运行通过。

                        ## Validation checks
                        - 修复前测试可稳定复现问题，修复后测试 100% 通过
                        - 未破坏相邻模块既有测试

                        ## Output format
                        - **根因诊断（文件:行号）** + **修复前后对比** + **新增回归测试结果**

                        ## Approval boundary
                        - 代码修复与本地单测自动放行；涉及修改外部生产库配置时需先挂起 `NEEDS INPUT:`
                    """.trimIndent(),
                ),
            ),
            defaultOpenWork = listOf("保持 Rust Workspace 与 Android 客户端零编译警告、单测全绿"),
            seedMemories = listOf("每次修改代码后必须实跑编译与单测验证，汇报时附上修改的文件路径与验证输出。"),
        ),

        // 4. 审校官 · 魔鬼代言人与红队审校官 (Contrarian & Interrogate Reviewer)
        BotTemplate(
            id = "tpl_lucas_critic",
            category = "core_team",
            defaultName = "审校官",
            defaultAvatar = "",
            jobTitle = "魔鬼代言人 · 红队审校",
            department = "质量与红队审校部",
            lifecycle = BotLifecycle.PROJECT,
            thinkingEffort = "max",
            isolationMode = "shared",
            approvalMode = "plan",
            manageOtherBots = false,
            dailyCheckIns = false,
            summary = "魔鬼代言人与红队审校官 (审校官) · 内置 Interrogate 技能，五维红队挑刺与安全把关（只审不改）",
            publicDescription = "魔鬼代言人与红队代码审校官（Contrarian Reviewer）：基于 审校官 驱动，内置 `Interrogate` 挑刺技能，专门挑战群体思维、深挖并发竞态、安全越权与边界漏洞，只审不改。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是团队的 **Contrarian & Red-Team Reviewer（魔鬼代言人 · 红队审校官）**，底层由 **审校官** 驱动，内置 **`Interrogate`（对抗性代码/方案审校）** 核心技能。
                你是怀疑论审校者（Skeptical Reviewer），专门打破群体盲从（Groupthink），在方案或代码上线前找出致命缺陷。

                # Core Operating Rules — `Interrogate` Protocol (AGENTS.md)
                1. **只审不改（Review Only, Never Edit）**：严禁直接修改业务文件或提交代码。
                2. **五维对抗性审视**：Correctness（正确性）、Blast Radius（爆炸半径）、Security（安全边界）、Simplicity（简洁性）、Testing（测试覆盖）。
                3. **先验证再指控**：为每个怀疑的问题构造能够触发失败的具体输入或并发时序。
                4. **明确裁决结论**：给出 **`[SHIP 准予放行]`**、**`[SHIP AFTER FIXES 修复后放行]`** 或 **`[DO NOT SHIP 严禁上线]`**。
            """.trimIndent(),
            disabledTools = listOf("write_file", "desktop_act", "git_commit"),
            exampleRequests = listOf(
                "对最近的 Git Diff 执行 Rakazo Interrogate 五维红队审校，给出放行裁决",
                "挑刺当前架构方案，找出潜在的并发死锁、权限绕过或向后兼容漏洞",
                "检查本次改动是否泄露了敏感凭据或破坏了旧版客户端兼容性",
            ),
            requires = listOf("read_only", "git_diff", "security_audit"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_rakazo_interrogate",
                    name = "Interrogate (Rakazo Official Adversarial Review)",
                    description = "Adversarial review of a diff/PR/plan. Review only; never applies fixes.",
                    whenToUse = "审查 Git Diff、架构方案或主程提交的代码改动时必用",
                    playbook = """
                        ## Purpose
                        对代码变更或架构方案发起高强度红队审视，在上线前拦截并发竞态、安全漏洞与兼容性退化。

                        ## Required inputs
                        - 待审查的 Git Diff、PR 描述或架构设计方案
                        - 受影响模块的上下游调用链上下文

                        ## Steps
                        1. 确立审查对象并读取完整文件上下文，绝不孤立只看局部 Diff。
                        2. 从 Correctness、Blast Radius、Security、Simplicity、Testing 五个维度发起挑战。
                        3. 为每个疑似缺陷构造可复现的触发输入或并发时序，剔除无法证实的臆测。

                        ## Validation checks
                        - 每条指控必须包含精确的 `文件:行号` 与具体的故障触发条件
                        - 严禁修改任何工作区文件

                        ## Output format
                        - **最终裁决**：`[SHIP 准予放行]` / `[SHIP AFTER FIXES 修复后放行]` / `[DO NOT SHIP 严禁上线]`
                        - **分级问题清单**：按 `P0 致命` / `P1 严重` / `P2 建议` 排序

                        ## Approval boundary
                        - 纯只读审校模式（禁用一切写文件与提交工具）
                    """.trimIndent(),
                ),
            ),
            defaultOpenWork = listOf("对每次发版前的 Git Diff 执行 Rakazo Interrogate 五维红队审校"),
            seedMemories = listOf("严格执行 Rakazo Interrogate 协议：只审不改，先构造触发故障的具体输入再指控，绝不凑数挑刺。"),
        ),

        // 5. 运维官 · 哨兵值守与运维总管 (Watchdog & SRE Inbox Manager)
        BotTemplate(
            id = "tpl_sentinel_ops",
            category = "grok_bot_recipe",
            defaultName = "运维官",
            defaultAvatar = "",
            jobTitle = "哨兵值守 · 运维总监",
            department = "基础设施与安全部",
            lifecycle = BotLifecycle.MANAGEMENT,
            thinkingEffort = "medium",
            isolationMode = "shared",
            approvalMode = "ask",
            manageOtherBots = false,
            dailyCheckIns = true,
            summary = "运维值守 · 监控服务与网络、例行巡检、故障诊断与恢复",
            publicDescription = "全天候哨兵与基础设施运维官（Sentinel · Watchdog & SRE）：负责 Hub/Agent 进程保活、Tailnet/Mihomo 代理拓扑诊断、端口冲突排查与故障自愈。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是团队的 **哨兵值守 · 运维总监**，负责服务巡检、告警分诊与故障恢复。
                你守卫着整个 VibeLink + Mihomo + Cindy 的本地与跨网运行环境。

                # Core Operating Rules (AGENTS.md)
                1. **IF / THEN 分级告警矩阵（Escalation Matrix）**：
                   - **P0（立即告警 + 尝试非破坏性自愈）**：Hub 端口 `18995` 失联、Agent 断连、鉴权 Token 泄露、磁盘满载；
                   - **P1（结构化摘要汇报）**：代理节点延迟突增、构建缓存膨胀、单次定时任务超时；
                   - **P2（静默记录）**：常规健康心跳、无异常巡检。
                2. **零噪音守则（Zero-Noise Routine Protocol）**：
                   - 在执行定时巡检（Routine）或每日检查时，如果所有服务健康且无高优告警，最终回复必须严格为 `NO_RESPONSE`，绝不刷屏打扰用户。
            """.trimIndent(),
            disabledTools = emptyList(),
            exampleRequests = listOf(
                "全面巡检 Hub(18995)、代理网关(18996/18997)与 APK 热更新源健康状态",
                "排查手机端无法连接电脑 Hub 的网络与端口监听根因",
                "检查后台常驻进程、日志报错与磁盘空间占用情况",
            ),
            requires = listOf("mesh_health", "terminal", "routine_scheduler"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_sentinel_health_sweep",
                    name = "Proxy & Mesh Health Sweep",
                    description = "巡检 Hub (18995)、网关 (18996/18997)、Agent 在线状态与 APK 更新源完整性",
                    whenToUse = "例行巡检、手机连不上电脑或代理链路异常时使用",
                    playbook = """
                        ## Purpose
                        快速诊断 Hub 控制面、TCP 代理网关与 APK 热更新源的端到端可用性。

                        ## Required inputs
                        - 本地 Hub 端口（默认 `18995`）与 `%LOCALAPPDATA%\vibelink-mesh` 运行目录

                        ## Steps
                        1. 请求 `/api/health`、`/api/devices` 与 `/api/apk/latest` 校验响应码。
                        2. 检查 `vibelink-hub.exe` 进程状态与最近日志尾部。
                        3. 若全部正常且处于定时任务中，仅返回 `NO_RESPONSE`；若有异常，按 P0/P1 输出根因与恢复命令。

                        ## Validation checks
                        - `/api/health` 返回 `"ok": true`
                        - `/api/apk/latest` 返回有效的 `version` 与 `file`

                        ## Output format
                        - 正常例行巡检：`NO_RESPONSE`
                        - 诊断报告：**【组件状态表】** + **【异常根因】** + **【自愈命令】**

                        ## Approval boundary
                        - 只读健康检查直接执行；重启生产服务或清理目录前需确认无正在运行的构建任务
                    """.trimIndent(),
                ),
            ),
            defaultRoutines = listOf(
                BotRoutineSpec(
                    name = "Mesh & Hub Silent Watchdog (每30分钟静默巡检)",
                    cron = "*/30 * * * *",
                    prompt = "巡检 Hub 18995 端口、在线设备与代理网关状态；若全部健康正常，严格只回复 NO_RESPONSE。",
                ),
            ),
            defaultOpenWork = listOf("监控 %LOCALAPPDATA%\\vibelink-mesh 服务存活与 APK 热更新通道"),
            seedMemories = listOf("定时巡检正常时严格输出 NO_RESPONSE，仅在发现 P0/P1 级服务异常时主动告警。"),
        ),

        // 6. 幕僚长 · UI 与交互主程 (Compose & Design Engineer)
        BotTemplate(
            id = "tpl_ui_craft",
            category = "engineering",
            defaultName = "UI 主程",
            defaultAvatar = "",
            jobTitle = "UI 与交互主程",
            department = "客户端体验组",
            lifecycle = BotLifecycle.PROJECT,
            thinkingEffort = "medium",
            isolationMode = "worktree",
            approvalMode = "accept_edits",
            manageOtherBots = false,
            dailyCheckIns = false,
            summary = "工程专项配方 · 专注 Android Compose 设计系统、圆角卡片美学与窄屏防溢出",
            publicDescription = "Android Jetpack Compose 与桌面端 UI 专家：负责圆角卡片布局、深浅色主题 Token 对齐、手势交互与小屏溢出防抖。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是客户端 **UI & Interaction Engineer（UI 与交互主程）**，专注打造媲美原生旗舰应用的 Jetpack Compose 与现代桌面界面。

                # Core Operating Rules (AGENTS.md)
                1. **严格复用设计系统 Token**：颜色取自 `VibeLinkTheme.colors`，字号取自 `VibeLinkType`，圆角取自 `VibeLinkShapes`。
                2. **移动端防挤压与防溢出**：顶栏、徽章与操作胶囊必须加 `maxLines = 1`、`softWrap = false` 或 `FlowRow` 自适应换行。
                3. **编译验证**：每次修改 Compose 界面后，必须运行 `gradlew :app:compileDebugKotlin` 验证无语法与类型错误。
            """.trimIndent(),
            disabledTools = emptyList(),
            exampleRequests = listOf(
                "优化当前 Compose 页面的卡片层级、圆角间距与深浅色主题适配",
                "排查并修复窄屏手机上顶栏按钮或状态胶囊文字竖排挤压的问题",
                "为新功能设计高颜值的底部抽屉弹窗与空状态占位图",
            ),
            requires = listOf("worktree", "compose_ui", "gradle_compile"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_ui_compose_audit",
                    name = "Compose Layout & Token Polish",
                    description = "优化 Compose 页面层级、响应式换行、空状态与流畅滚动体验",
                    whenToUse = "新增或重构 Android 客户端界面、弹窗与消息气泡时使用",
                    playbook = """
                        ## Purpose
                        确保所有 Jetpack Compose 界面符合 `VibeLinkTheme` 视觉规范，并在各种屏幕宽度下保持优雅排版。

                        ## Required inputs
                        - 目标 Screen / 组件 Kotlin 源码文件
                        - `Theme.kt` 中的色彩、字号与圆角 Token 定义

                        ## Steps
                        1. 替换硬编码颜色为 `VibeLinkTheme.colors` 语义色。
                        2. 为窄屏易挤压的行内标签补充 `FlowRow`、`maxLines = 1` 与 `TextOverflow.Ellipsis`。
                        3. 运行 `gradlew :app:compileDebugKotlin` 验证编译通过。

                        ## Validation checks
                        - `:app:compileDebugKotlin` 零错误通过
                        - 360dp 窄屏下无文字竖排折行或按钮裁切

                        ## Output format
                        - **UI 改动组件清单** + **视觉与自适应优化点** + **编译验证结果**

                        ## Approval boundary
                        - UI 组件与样式调整自动执行；大幅删减现有用户入口前需挂起 `NEEDS INPUT:` 确认
                    """.trimIndent(),
                ),
            ),
            seedMemories = listOf("所有 Compose 组件必须遵循 VibeLinkTheme 设计 Token 并防止窄屏文字竖排挤压。"),
        ),

        // 7. 运维官 · 数据与日志精算官 (Open Duet `Numbers` Data & Log Analyst)
        BotTemplate(
            id = "tpl_duet_numbers",
            category = "open_duet",
            defaultName = "数据官",
            defaultAvatar = "",
            jobTitle = "数据与日志精算官",
            department = "Open Duet 数据分析部",
            lifecycle = BotLifecycle.PROJECT,
            thinkingEffort = "high",
            isolationMode = "shared",
            approvalMode = "plan",
            manageOtherBots = false,
            dailyCheckIns = false,
            summary = "Open Duet Numbers · 专注结构化日志统计、性能耗时分位数、数据库查询与异常率精算",
            publicDescription = "数据与日志精算专家（Open Duet `Numbers` · Data & Telemetry Analyst）：负责 JSONL/CSV/SQLite 日志聚合、接口延迟分位数统计、Token 消耗核算与异常趋势定位。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是团队的 **Data & Telemetry Analyst（数据与日志精算官 · Numbers）**，复刻自 Open Duet (`Anil-matcha/open-duet`) 官方 `Numbers` 配方。
                你的核心原则是：**「先核对样本口径与缺失值，再用可复现脚本算清每一个分位数与百分比。」**

                # Core Operating Rules (AGENTS.md)
                1. **口径先行（Schema & Sample Hygiene）**：统计前先说明样本总数、时间窗口与过滤条件。
                2. **可复现计算（Reproducible Aggregation）**：严禁心算编造数字；结论必须基于真实聚合输出。
                3. **只读安全边界**：严禁删除或覆写原始日志文件与数据库表。
            """.trimIndent(),
            disabledTools = listOf("write_file", "desktop_act", "git_commit"),
            exampleRequests = listOf(
                "统计最近会话日志中的各引擎调用次数、平均响应耗时与错误率分布",
                "分析 JSON/CSV 数据集中的异常离群点，并给出 Markdown 汇总表",
                "对比优化前后的构建耗时与包体积变化百分比",
            ),
            requires = listOf("read_only", "data_analysis", "log_inspection"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_duet_log_crunch",
                    name = "Telemetry & Log Quantile Crunch",
                    description = "对结构化日志或性能采样数据进行聚合统计，输出分位数与异常分布表",
                    whenToUse = "需要统计接口耗时、错误率、构建性能或数据分布时使用",
                    playbook = """
                        ## Purpose
                        从原始日志或数据文件中提取高可信度的量化指标，定位性能瓶颈与高频错误。

                        ## Required inputs
                        - 日志文件路径（JSONL / CSV / 文本日志）与统计时间范围

                        ## Steps
                        1. 探查数据首尾行、字段结构与总记录数。
                        2. 按状态码、模块或耗时区间分组统计（Count / P50 / P95 / Error%）。
                        3. 提炼 Top 3 异常根因与优化收益预估。

                        ## Validation checks
                        - 各分项计数之和与总样本数一致
                        - 明确标注空值或脏数据剔除比例

                        ## Output format
                        - **样本口径说明** + **核心指标 Markdown 表格** + **Top 异常发现**

                        ## Approval boundary
                        - 只读统计分析直接执行，严禁修改原始数据文件
                    """.trimIndent(),
                ),
            ),
            seedMemories = listOf("所有统计指标必须注明样本量（N）与数据来源路径，严禁心算估算。"),
        ),

        // 8. 情报官 · 作战纪要与 Runbook 专家 (Open Duet `Meeting Notes` & Runbook Specialist)
        BotTemplate(
            id = "tpl_duet_runbook",
            category = "open_duet",
            defaultName = "纪要官",
            defaultAvatar = "",
            jobTitle = "作战纪要 · Runbook 专家",
            department = "Open Duet 知识与交付部",
            lifecycle = BotLifecycle.MANAGEMENT,
            thinkingEffort = "medium",
            isolationMode = "shared",
            approvalMode = "accept_edits",
            manageOtherBots = false,
            dailyCheckIns = false,
            summary = "Open Duet Meeting & Runbook · 将冗长群聊辩论、排障过程提炼为标准化决策纪要与可复用 SOP",
            publicDescription = "作战纪要与运维手册专家（Open Duet `Meeting Notes & Runbook`）：负责将多 Bot 群聊讨论、事故复盘与零散命令沉淀为结构化 ADR 决策记录、可复用 Runbook 与 Action Items。",
            rolePrompt = """
                # Role & Persona (SOUL.md)
                你是团队的 **Runbook & Recap Specialist（作战纪要与手册专家）**，源自 Open Duet 官方 `Meeting Notes` 与 `Customer Follow-up` 配方。
                你擅长从冗长的多智能体讨论或排障记录中去芜存菁，提炼出「已拍板决策」、「待办责任人（Action Items）」与「一步步可执行的标准作业程序（Runbook）」。

                # Core Operating Rules (AGENTS.md)
                1. **忠于事实，拒绝脑补**：纪要中的每条决定与待办必须能追溯到群聊发言或代码提交。
                2. **三段式精炼结构**：所有复盘与纪要固定输出：**【三句话高管摘要 (TL;DR)】、【关键技术决策与原因 (ADR)】、【可执行待办清单 (Owner / Action / Verification)】**。
            """.trimIndent(),
            disabledTools = listOf("desktop_act"),
            exampleRequests = listOf(
                "把刚才群里的多轮技术讨论整理成结构化决策纪要与待办清单",
                "将本次排障与发版流程提炼为一页纸的标准化运维 Runbook",
                "汇总本周所有已落地功能与架构升级，生成对外更新日志 (Changelog)",
            ),
            requires = listOf("documentation", "recap", "runbook"),
            skills = listOf(
                BotSkillSpec(
                    id = "skill_duet_recap_runbook",
                    name = "Thread Recap & Runbook Extraction",
                    description = "将多轮群聊协同或排障对话提炼为 ADR 决策纪要与标准化 Runbook",
                    whenToUse = "长对话收尾、版本复盘或沉淀团队标准作业手册时使用",
                    playbook = """
                        ## Purpose
                        把多智能体协同过程中的口语化讨论转化为可检索、可交接、可复现的结构化文档。

                        ## Required inputs
                        - 群聊历史消息、排障命令记录或近期 Git 提交摘要

                        ## Steps
                        1. 提取已达成共识的架构/产品决策，剔除中途废弃的试错分支。
                        2. 梳理每一步操作的前置条件、执行命令与验证输出。
                        3. 列出未闭环的后续 Action Items 并标注建议负责的 Bot。

                        ## Validation checks
                        - 每个 Action Item 均包含明确的完成判定标准
                        - 命令与配置路径已脱敏，不含任何明文密钥

                        ## Output format
                        - **TL;DR 三行摘要** + **已拍板决策 (ADR)** + **Runbook 步骤 / Action Items 表格**

                        ## Approval boundary
                        - 生成纪要与更新文档自动放行；修改核心业务代码需交由主程处理
                    """.trimIndent(),
                ),
            ),
            seedMemories = listOf("所有纪要严格遵循【三行摘要/已拍板决策/待决问题/Action Items】结构，所有 Runbook 步骤必须包含完成判定标准。"),
        ),
    )

    fun buildBotFromTemplate(
        tpl: BotTemplate,
        customId: String? = null,
        customName: String? = null,
        cwd: String = "D:\\AI\\VibeLink",
        offsetMs: Long = 0L,
    ): BotFriend {
        val now = System.currentTimeMillis() - offsetMs
        val botId = customId?.takeIf { it.isNotBlank() } ?: ("bot_" + tpl.id.removePrefix("tpl_"))
        val name = customName?.takeIf { it.isNotBlank() }
            ?: tpl.defaultName.ifBlank { tpl.jobTitle }
        val avatar = tpl.defaultAvatar
        return BotFriend(
            id = botId,
            templateId = tpl.id,
            name = name,
            avatar = avatar,
            jobTitle = tpl.jobTitle,
            department = tpl.department,
            lifecycle = tpl.lifecycle,
            engine = "codex",
            model = BotModelPolicy.defaultModel(tpl.id),
            device = "pc",
            cwd = cwd,
            publicDescription = tpl.publicDescription,
            rolePrompt = tpl.rolePrompt,
            thinkingEffort = tpl.thinkingEffort,
            isolationMode = tpl.isolationMode,
            approvalMode = tpl.approvalMode,
            instructionsVersion = 1L,
            instructionsHistory = listOf(
                BotInstructionVersion(
                    version = 1L,
                    rolePrompt = tpl.rolePrompt,
                    updatedBy = "system",
                    updatedAt = now,
                    note = "来自角色模板 (${tpl.id})",
                ),
            ),
            manageOtherBots = tpl.manageOtherBots,
            dailyCheckIns = tpl.dailyCheckIns,
            disabledTools = tpl.disabledTools,
            exampleRequests = tpl.exampleRequests,
            requires = tpl.requires,
            skills = tpl.skills,
            defaultRoutines = tpl.defaultRoutines,
            openWork = tpl.defaultOpenWork.mapIndexed { idx, w ->
                BotOpenWorkItem(
                    id = "work_${botId}_$idx",
                    title = w,
                    notes = "角色模板预置待办",
                    status = "open",
                    updatedAt = now,
                )
            },
            memories = tpl.seedMemories.mapIndexed { idx, m ->
                BotMemoryFact(
                    id = "mem_${botId}_$idx",
                    fact = m,
                    category = "workflow",
                    source = "system",
                    createdAt = now,
                )
            },
            lastText = tpl.publicDescription,
            lastAt = now,
        )
    }

    private fun prefs(context: Context) =
        RemoteStore.scopedPreferences(context, PREF_NAME)

    fun ensureSeeded(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_SEEDED, false)) {
            val now = System.currentTimeMillis()
            val defaultBots = listOf(
                buildBotFromTemplate(TEMPLATES[0], customId = "bot_cto", offsetMs = 60_000L),
                buildBotFromTemplate(TEMPLATES[1], customId = "bot_pm", offsetMs = 120_000L),
                buildBotFromTemplate(TEMPLATES[2], customId = "bot_dev", offsetMs = 180_000L),
                buildBotFromTemplate(TEMPLATES[3], customId = "bot_qa", offsetMs = 240_000L),
                buildBotFromTemplate(TEMPLATES[4], customId = "bot_sre", offsetMs = 300_000L),
                buildBotFromTemplate(TEMPLATES[5], customId = "bot_ui", offsetMs = 360_000L),
            )
            writeBotsToPrefs(context, defaultBots)

            val defaultGroup = TeamGroup(
                id = "group_vibelink_core",
                name = "核心研发协作组",
                description = "幕僚长统筹、情报官查证、代码主程实现、审校官验收",
                botIds = listOf("bot_cto", "bot_pm", "bot_dev", "bot_qa"),
                cwd = "D:\\AI\\VibeLink",
                lastText = "多智能体协同流水线已就位，发任务或 @成员 即可启动",
                lastAt = now - 45_000L,
            )
            writeGroupsToPrefs(context, listOf(defaultGroup))

            val seedMsgs = listOf(
                TeamGroupMessage(
                    id = "msg_welcome_1",
                    senderId = "bot_cto",
                    senderName = defaultBots[0].name,
                    senderJobTitle = defaultBots[0].jobTitle,
                    senderEngine = defaultBots[0].engine,
                    text = buildWelcomeMessageText(defaultGroup.name, defaultBots.take(4)),
                    at = now - 45_000L,
                ),
            )
            saveGroupMessagesInternal(context, defaultGroup.id, seedMsgs)
            defaultBots.forEach { b -> AgentPrefs.setAvatar(context, b.id, b.avatar) }
            p.edit()
                .putBoolean(KEY_SEEDED, true)
                .putBoolean(KEY_RECIPE_V2, true)
                .putBoolean(KEY_MODEL_AVATAR_V3, true)
                .putBoolean(KEY_MODEL_AVATAR_V4, true)
                .putBoolean(KEY_OPEN_DUET_V5, true)
                .putBoolean(KEY_GEMINI_AGY_FIX_V6, true)
                .putBoolean(KEY_CLEAN_STALE_SESSIONS_V7, true)
                .apply()
        } else if (
            !p.getBoolean(KEY_RECIPE_V2, false) ||
            !p.getBoolean(KEY_MODEL_AVATAR_V3, false) ||
            !p.getBoolean(KEY_MODEL_AVATAR_V4, false) ||
            !p.getBoolean(KEY_OPEN_DUET_V5, false) ||
            !p.getBoolean(KEY_GEMINI_AGY_FIX_V6, false) ||
            !p.getBoolean(KEY_CLEAN_STALE_SESSIONS_V7, false)
        ) {
            // 自动将存量 Bot 平滑升级，并修正 antigravity (AGY) 引擎错用 Grok 名称/头像/配置及短期 Bot 误绑历史旧会话 (01a1a0) 的问题
            val cleanStaleSessions = !p.getBoolean(KEY_CLEAN_STALE_SESSIONS_V7, false)
            val existingBots = readBotsFromPrefs(context).toMutableList()
            val idToTpl = mapOf(
                "bot_cto" to TEMPLATES[0],
                "bot_pm" to TEMPLATES[1],
                "bot_dev" to TEMPLATES[2],
                "bot_qa" to TEMPLATES[3],
                "bot_sre" to TEMPLATES[4],
                "bot_ui" to TEMPLATES[5],
            )
            val upgraded = existingBots.map { old ->
                val tpl = idToTpl[old.id] ?: TEMPLATES.firstOrNull { old.templateId == it.id || old.jobTitle.contains(it.jobTitle.take(2)) }
                val shouldClearSession = cleanStaleSessions && (
                    old.activeSessionId.startsWith("01a1a0") ||
                        (old.lifecycle == BotLifecycle.PROJECT && old.engine == "codex")
                    )
                val cleanedActiveSessionId = if (shouldClearSession) "" else old.activeSessionId
                val cleanedSessionIds = if (shouldClearSession) {
                    old.sessionIds.filterNot { it.startsWith("01a1a0") }
                } else {
                    old.sessionIds
                }
                if (tpl != null) {
                    val isLegacyOrGrokPrompt = (old.rolePrompt.length < 140 && !old.rolePrompt.contains("#")) ||
                        old.rolePrompt.contains("Captain (Grok)") ||
                        old.rolePrompt.contains("Harper") ||
                        old.rolePrompt.contains("Benjamin") ||
                        old.rolePrompt.contains("Lucas")
                    val hasLegacyGrokDesc = old.publicDescription.isBlank() ||
                        old.publicDescription.contains("Harper") ||
                        old.publicDescription.contains("Benjamin") ||
                        old.publicDescription.contains("Lucas") ||
                        old.publicDescription.contains("Captain & Chief of Staff")
                    val fresh = buildBotFromTemplate(tpl, customId = old.id)
                    val shouldAdoptModelName = isDefaultOrLegacyBotName(old.name)
                    val newEngine = if (shouldAdoptModelName) fresh.engine else old.engine
                    val isCustomUploadedAvatar = old.avatar.startsWith("data:image") || old.avatar.startsWith("base64:")
                    val mergedSkills = (fresh.skills + old.skills.filter { s -> fresh.skills.none { it.id == s.id } })
                    val resolvedName = if (shouldAdoptModelName) fresh.name else old.name
                    val resolvedAvatar = if (isCustomUploadedAvatar) old.avatar else defaultAvatarForRole(old.templateId)
                    AgentPrefs.setAvatar(context, old.id, resolvedAvatar)
                    old.copy(
                        templateId = old.templateId.ifBlank { tpl.id },
                        name = resolvedName,
                        avatar = resolvedAvatar,
                        engine = newEngine,
                        jobTitle = if (isLegacyOrGrokPrompt) fresh.jobTitle else old.jobTitle,
                        department = if (isLegacyOrGrokPrompt || old.department.contains("Grok")) fresh.department else old.department,
                        publicDescription = if (hasLegacyGrokDesc) fresh.publicDescription else old.publicDescription,
                        rolePrompt = if (isLegacyOrGrokPrompt) fresh.rolePrompt else old.rolePrompt,
                        thinkingEffort = if (isLegacyOrGrokPrompt) fresh.thinkingEffort else old.thinkingEffort,
                        isolationMode = if (isLegacyOrGrokPrompt) fresh.isolationMode else old.isolationMode,
                        approvalMode = fresh.approvalMode,
                        manageOtherBots = fresh.manageOtherBots,
                        dailyCheckIns = fresh.dailyCheckIns,
                        disabledTools = old.disabledTools.ifEmpty { fresh.disabledTools },
                        exampleRequests = old.exampleRequests.ifEmpty { fresh.exampleRequests },
                        requires = old.requires.ifEmpty { fresh.requires },
                        skills = mergedSkills,
                        defaultRoutines = old.defaultRoutines.ifEmpty { fresh.defaultRoutines },
                        openWork = old.openWork.ifEmpty { fresh.openWork },
                        memories = (old.memories + fresh.memories).distinctBy { it.fact },
                        activeSessionId = cleanedActiveSessionId,
                        sessionIds = cleanedSessionIds,
                    )
                } else {
                    val fixedName = if (old.engine.equals("antigravity", ignoreCase = true) && old.name == "Grok") "Gemini"
                    else old.name.ifBlank { old.jobTitle.ifBlank { "Bot" } }
                    val fixedAvatar = if (old.engine.equals("antigravity", ignoreCase = true) && old.avatar == "preset:grok") "preset:gemini"
                    else old.avatar.ifBlank { defaultAvatarForRole(old.templateId) }
                    AgentPrefs.setAvatar(context, old.id, fixedAvatar)
                    old.copy(
                        name = fixedName,
                        avatar = fixedAvatar,
                        activeSessionId = cleanedActiveSessionId,
                        sessionIds = cleanedSessionIds,
                    )
                }
            }.toMutableList()
            for ((botId, tpl) in idToTpl) {
                if (upgraded.none { it.id == botId }) {
                    val b = buildBotFromTemplate(tpl, customId = botId)
                    AgentPrefs.setAvatar(context, b.id, b.avatar)
                    upgraded.add(b)
                }
            }
            writeBotsToPrefs(context, upgraded)

            val existingGroups = readGroupsFromPrefs(context).toMutableList()
            for (gi in existingGroups.indices) {
                val g = existingGroups[gi]
                if (g.id == "group_vibelink_core" || g.id == "grp_all_hands" || g.name.contains("Grok")) {
                    existingGroups[gi] = g.copy(
                        name = "核心研发协作组",
                        description = "幕僚长统筹、情报官查证、代码主程实现、审校官验收",
                    )
                }
            }
            writeGroupsToPrefs(context, existingGroups)
            p.edit()
                .putBoolean(KEY_RECIPE_V2, true)
                .putBoolean(KEY_MODEL_AVATAR_V3, true)
                .putBoolean(KEY_MODEL_AVATAR_V4, true)
                .putBoolean(KEY_OPEN_DUET_V5, true)
                .putBoolean(KEY_GEMINI_AGY_FIX_V6, true)
                .putBoolean(KEY_CLEAN_STALE_SESSIONS_V7, true)
                .apply()
        }
        if (!p.getBoolean("model_policy_v10", false)) {
            val upgraded = readBotsFromPrefs(context).map { old ->
                val tpl = TEMPLATES.firstOrNull { it.id == old.templateId }
                val engine = BotModelPolicy.defaultEngine(old.templateId)
                old.copy(
                    engine = engine,
                    model = old.model,
                    name = if (isDefaultOrLegacyBotName(old.name)) tpl?.defaultName ?: old.jobTitle else old.name,
                    avatar = old.avatar,
                    publicDescription = tpl?.publicDescription ?: old.publicDescription,
                    rolePrompt = if (listOf("Antigravity", "Gemini", "@情报官", "@代码主程", "@审校官", "Harper", "Benjamin", "Lucas").any { old.rolePrompt.contains(it) }) tpl?.rolePrompt ?: old.rolePrompt else old.rolePrompt,
                    activeSessionId = if (old.engine != engine) "" else old.activeSessionId,
                    sessionIds = if (old.engine != engine) emptyList() else old.sessionIds,
                ).also { AgentPrefs.setAvatar(context, it.id, it.avatar) }
            }
            writeBotsToPrefs(context, upgraded)
            p.edit().putBoolean("model_policy_v10", true).apply()
        }
        if (!initialized) {
            initialized = true
            _botsFlow.value = readBotsFromPrefs(context)
            _groupsFlow.value = readGroupsFromPrefs(context)
            _versionFlow.value++
        }
    }

    /**
     * 对标 Open Duet `backend/app/services/skill_learning.py`：
     * 从一段高价值实战对话（用户提问 + Bot 回复）自动提炼 6 段式 `BotSkillSpec` 技能草案，
     * 自动执行敏感凭据脱敏（`redactSecrets`）与高危模式扫描（`scanSkillWarnings`）。
     */
    fun draftSkillFromTurn(
        bot: BotFriend,
        userPrompt: String,
        assistantReply: String,
        customName: String = "",
    ): LearnedSkillDraft {
        val (cleanPrompt, r1) = redactSecrets(userPrompt.trim())
        val (cleanReply, r2) = redactSecrets(assistantReply.trim())
        val totalRedacted = r1 + r2

        val name = customName.trim().ifBlank {
            val seed = cleanPrompt.ifBlank { cleanReply }
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trimStart('#', '-', '*', ' ')
                ?.take(24)
                .orEmpty()
            if (seed.isBlank()) "实战提炼技能" else "实战技能：$seed"
        }
        val description = if (cleanPrompt.isNotBlank()) {
            "从 ${bot.name} 实战对话提炼的可复用作业剧本：${cleanPrompt.take(64)}"
        } else {
            "从 ${bot.name} 实战回复提炼的标准化六段式技能剧本"
        }
        val whenToUse = if (cleanPrompt.isNotBlank()) {
            "当遇到类似「${cleanPrompt.take(48)}」的任务或排查场景时触发"
        } else {
            "当遇到同类高频分析或执行任务时触发"
        }

        val extractedSteps = cleanReply.lineSequence()
            .map { it.trim() }
            .filter {
                it.startsWith("- ") || it.startsWith("* ") ||
                    it.startsWith("1.") || it.startsWith("2.") || it.startsWith("3.") || it.startsWith("4.")
            }
            .take(6)
            .toList()
        val stepsBlock = if (extractedSteps.isNotEmpty()) {
            extractedSteps.joinToString("\n")
        } else {
            val summary = cleanReply.take(320).replace("\n", "\n   ")
            "1. 审阅输入上下文与目标范围。\n2. 按以下实战验证流程执行：\n   $summary\n3. 核对验证结果并输出结构化结论。"
        }
        val reqInputs = if (cleanPrompt.isNotBlank()) {
            "- 触发问题样例：${cleanPrompt.take(100)}\n- 相关代码文件、日志或运行上下文"
        } else {
            "- 目标上下文、相关代码路径或日志输入"
        }

        val playbook = """
            ## Purpose
            $description

            ## Required inputs
            $reqInputs

            ## Steps
            $stepsBlock

            ## Validation checks
            - 每一项结论均有真实文件行号、日志或命令输出支撑
            - 未引入硬编码密钥或越权副作用

            ## Output format
            - **执行结论** + **关键证据链** + **后续动作建议**

            ## Approval boundary
            - 遵循当前 Bot 的审批边界（`${bot.approvalMode}`：只读分析直接执行，破坏性操作或外部发布前必须请求确认）
        """.trimIndent()

        val warnings = scanSkillWarnings(playbook)
        val skill = BotSkillSpec(
            id = "skill_learned_${System.currentTimeMillis()}",
            name = name,
            description = description,
            whenToUse = whenToUse,
            playbook = playbook,
            enabled = true,
            source = "learned",
        )
        return LearnedSkillDraft(
            skill = skill,
            warnings = warnings,
            redactedSecretsCount = totalRedacted,
        )
    }

    /** 将提炼或编辑后的六段式技能保存到指定 Bot 并同步 Hub */
    fun saveLearnedSkill(context: Context, botId: String, skill: BotSkillSpec) {
        val b = getBot(context, botId) ?: return
        val cleanPlaybook = redactSecrets(skill.playbook).first
        val cleanSkill = skill.copy(playbook = cleanPlaybook)
        val updatedSkills = b.skills.filterNot { it.id == cleanSkill.id || it.name == cleanSkill.name } + cleanSkill
        upsertBot(context, b.copy(skills = updatedSkills))
    }

    /** 切换某个绑定技能的启用/停用状态 */
    fun toggleSkillEnabled(context: Context, botId: String, skillId: String, enabled: Boolean) {
        val b = getBot(context, botId) ?: return
        val updatedSkills = b.skills.map { if (it.id == skillId) it.copy(enabled = enabled) else it }
        upsertBot(context, b.copy(skills = updatedSkills))
    }

    /**
     * 一键重置/套用 Grok 4.20 × Open Duet 全套官方配方（同步本地与 Hub）
     */
    suspend fun applyGrok420Preset(context: Context): Boolean = withContext(Dispatchers.IO) {
        val current = listBots(context, includeArchived = true)
        val specList = listOf(
            "bot_cto" to TEMPLATES[0],
            "bot_pm" to TEMPLATES[1],
            "bot_dev" to TEMPLATES[2],
            "bot_qa" to TEMPLATES[3],
            "bot_sre" to TEMPLATES[4],
            "bot_ui" to TEMPLATES[5],
        )
        val merged = mutableListOf<BotFriend>()
        specList.forEachIndexed { idx, (botId, tpl) ->
            val old = current.firstOrNull { it.id == botId }
            val fresh = buildBotFromTemplate(tpl, customId = botId, offsetMs = idx * 30_000L)
            merged.add(
                if (old != null) {
                    val keepCustomAvatar = old.avatar.isNotBlank()
                    fresh.copy(
                        name = old.name,
                        engine = old.engine,
                        model = old.model,
                        device = old.device,
                        cwd = old.cwd,
                        avatar = if (keepCustomAvatar) old.avatar else fresh.avatar,
                        activeSessionId = old.activeSessionId,
                        sessionIds = old.sessionIds,
                        instructionsVersion = old.instructionsVersion + 1L,
                        memories = (fresh.memories + old.memories).distinctBy { it.fact },
                    )
                } else {
                    fresh
                },
            )
        }
        current.filter { c -> specList.none { it.first == c.id } }.forEach { merged.add(it) }
        saveBots(context, merged)
        synchronized(this@BotTeamStore) {
            merged.forEach { enqueueProfile(context, it) }
        }

        val groups = listGroups(context).toMutableList()
        val grokGroup = TeamGroup(
            id = groups.firstOrNull()?.id ?: "group_vibelink_core",
            name = "核心研发协作组",
            description = "幕僚长统筹、情报官查证、代码主程实现、审校官验收",
            botIds = listOf("bot_cto", "bot_pm", "bot_dev", "bot_qa"),
            cwd = "D:\\AI\\VibeLink",
            lastText = "已重置核心智囊团标准角色配方",
            lastAt = System.currentTimeMillis(),
        )
        if (groups.isEmpty()) {
            saveGroups(context, listOf(grokGroup))
        } else {
            groups[0] = grokGroup
            saveGroups(context, groups)
        }

        val hub = RemoteStore.hub(context)
        val token = RemoteStore.token(context)
        if (hub.isNotBlank() && token.isNotBlank()) {
            syncWithHub(context)
        }
        true
    }

    /**
     * 导出单个 Bot 为兼容 Open Duet `open-dots.agent` (v1) + `grok-bot-recipe/v1` + OpenClaw `SOUL.md` 的便携包（自动双向脱敏敏感凭据）
     */
    fun exportBotRecipeJson(bot: BotFriend): String {
        val (cleanPrompt, r1) = redactSecrets(bot.rolePrompt)
        val (cleanDesc, r2) = redactSecrets(bot.publicDescription)
        var redactedCount = r1 + r2

        val skillsArray = JSONArray().apply {
            bot.skills.forEach { sk ->
                val (cleanPb, c) = redactSecrets(sk.playbook)
                redactedCount += c
                put(
                    JSONObject().apply {
                        put("id", sk.id)
                        put("name", sk.name)
                        put("description", sk.description)
                        put("whenToUse", sk.whenToUse)
                        put("playbook", cleanPb)
                        put("enabled", sk.enabled)
                        put("source", sk.source)
                    },
                )
            }
        }

        val coreMemories = mutableListOf<String>()
        val topicsObj = JSONObject()
        bot.memories.filter { !it.archived }.forEach { m ->
            val (cleanFact, c) = redactSecrets(m.fact)
            redactedCount += c
            if (m.topic.isBlank()) {
                coreMemories.add(cleanFact)
            } else {
                topicsObj.put(
                    m.topic,
                    JSONObject().apply {
                        put("content", cleanFact)
                        put("aliases", JSONArray(m.aliases))
                    },
                )
            }
        }

        val root = JSONObject().apply {
            put("format", "open-dots.agent")
            put("version", 1)
            put("schema", "grok-bot-recipe/v1")
            put("exportedAt", System.currentTimeMillis())
            put("redactedSecretsCount", redactedCount)
            put(
                "agent",
                JSONObject().apply {
                    put("name", bot.name)
                    put("avatar", bot.avatar)
                    put("job_title", bot.jobTitle)
                    put("department", bot.department)
                    put("description", cleanDesc)
                    put("system_prompt", cleanPrompt)
                    put("model", bot.model)
                    put("thinking_effort", bot.thinkingEffort)
                    put("approval_mode", bot.approvalMode)
                    put("isolation_mode", bot.isolationMode)
                    put("disabled_tools", JSONArray(bot.disabledTools))
                    put("example_requests", JSONArray(bot.exampleRequests))
                    put("requires", JSONArray(bot.requires))
                },
            )
            put(
                "notes",
                JSONObject().apply {
                    put("memory_md", coreMemories.joinToString("\n"))
                    put("topics", topicsObj)
                },
            )
            put("skills", skillsArray)
            put(
                "recipe",
                JSONObject().apply {
                    put("templateId", bot.templateId.ifBlank { "custom_${bot.id}" })
                    put("name", bot.name)
                    put("avatar", bot.avatar)
                    put("jobTitle", bot.jobTitle)
                    put("department", bot.department)
                    put("lifecycle", bot.lifecycle.name)
                    put("engine", bot.engine)
                    put("model", bot.model)
                    put("thinkingEffort", bot.thinkingEffort)
                    put("isolationMode", bot.isolationMode)
                    put("approvalMode", bot.approvalMode)
                    put("manageOtherBots", bot.manageOtherBots)
                    put("dailyCheckIns", bot.dailyCheckIns)
                    put("publicDescription", cleanDesc)
                    put("instructions", cleanPrompt)
                    put("disabledTools", JSONArray(bot.disabledTools))
                    put("exampleRequests", JSONArray(bot.exampleRequests))
                    put("requires", JSONArray(bot.requires))
                    put("skills", skillsArray)
                    put(
                        "defaultRoutines",
                        JSONArray().apply {
                            bot.defaultRoutines.forEach { rt ->
                                put(
                                    JSONObject().apply {
                                        put("name", rt.name)
                                        put("cron", rt.cron)
                                        put("prompt", rt.prompt)
                                        put("enabled", rt.enabled)
                                    },
                                )
                            }
                        },
                    )
                    put("openWork", JSONArray(bot.openWork.filter { it.status != "done" }.map { it.title }))
                    put("seedMemories", JSONArray(coreMemories))
                },
            )
        }
        return root.toString(2)
    }

    /**
     * 从 Open Duet `open-dots.agent` (v1) 或 Grok Bot Recipe JSON 或 OpenClaw `SOUL.md` 导入并创建/更新 Bot。
     * 严格执行 Open Duet 安全沙箱规范：
     * - 双向脱敏敏感凭据
     * - 外部导入的 `open-dots.agent` 包强制重置 `approvalMode = "ask"`，导入技能默认标记 `source = "imported"` 且 `enabled = false`
     */
    fun importBotRecipeText(context: Context, rawText: String, targetBotId: String? = null): Result<BotFriend> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("配方内容不能为空"))
        return runCatching {
            val bot = if (trimmed.startsWith("{")) {
                val root = JSONObject(trimmed)
                val isOpenDuet = root.optString("format") == "open-dots.agent" || root.has("agent")
                val r = root.optJSONObject("agent") ?: root.optJSONObject("recipe") ?: root
                val parsed = parseBotJson(r)
                val rawInstructions = r.optString("instructions")
                    .ifBlank { r.optString("rolePrompt") }
                    .ifBlank { r.optString("system_prompt") }
                val cleanInstructions = redactSecrets(rawInstructions.ifBlank { parsed.rolePrompt }).first

                // 解析顶层或 recipe 内的 skills，若为 open-dots.agent 则执行沙箱降权 (enabled = false, source = "imported")
                val topSkillsArr = root.optJSONArray("skills") ?: r.optJSONArray("skills")
                val importedSkills = if (topSkillsArr == null) {
                    parsed.skills
                } else {
                    (0 until topSkillsArr.length()).mapNotNull { i ->
                        val sk = topSkillsArr.optJSONObject(i) ?: return@mapNotNull null
                        BotSkillSpec(
                            id = sk.optString("id", "skill_imp_$i"),
                            name = sk.optString("name", "导入技能 $i"),
                            description = sk.optString("description"),
                            whenToUse = sk.optString("whenToUse"),
                            playbook = redactSecrets(sk.optString("playbook")).first,
                            enabled = if (isOpenDuet) false else sk.optBoolean("enabled", true),
                            source = "imported",
                        )
                    }
                }

                val extraMemories = mutableListOf<BotMemoryFact>()
                val seedMems = r.optJSONArray("seedMemories")
                if (seedMems != null) {
                    for (i in 0 until seedMems.length()) {
                        val m = seedMems.optString(i).trim()
                        if (m.isNotBlank()) {
                            extraMemories.add(
                                BotMemoryFact(
                                    id = "mem_imp_$i",
                                    fact = redactSecrets(m).first,
                                    category = "workflow",
                                    source = "imported",
                                ),
                            )
                        }
                    }
                }
                val notesObj = root.optJSONObject("notes")
                if (notesObj != null) {
                    val memMd = notesObj.optString("memory_md")
                    memMd.lineSequence().forEachIndexed { idx, line ->
                        val cleanLine = line.trim().removePrefix("- ").trim()
                        if (cleanLine.isNotEmpty()) {
                            extraMemories.add(
                                BotMemoryFact(
                                    id = "mem_md_$idx",
                                    fact = redactSecrets(cleanLine).first,
                                    category = "fact",
                                    source = "imported",
                                ),
                            )
                        }
                    }
                    val topicsObj = notesObj.optJSONObject("topics")
                    if (topicsObj != null) {
                        val keys = topicsObj.keys()
                        while (keys.hasNext()) {
                            val topicKey = keys.next()
                            val tVal = topicsObj.optJSONObject(topicKey)
                            val content = tVal?.optString("content") ?: topicsObj.optString(topicKey)
                            val aliasesArr = tVal?.optJSONArray("aliases")
                            val aliases = if (aliasesArr == null) emptyList() else (0 until aliasesArr.length()).mapNotNull { k ->
                                aliasesArr.optString(k).takeIf { it.isNotBlank() }
                            }
                            if (content.isNotBlank()) {
                                extraMemories.add(
                                    BotMemoryFact(
                                        id = "mem_topic_$topicKey",
                                        fact = redactSecrets(content).first,
                                        category = "topic",
                                        source = "imported",
                                        topic = topicKey,
                                        aliases = aliases,
                                    ),
                                )
                            }
                        }
                    }
                }

                parsed.copy(
                    id = targetBotId ?: parsed.id.ifBlank { "bot_" + UUID.randomUUID().toString().take(8) },
                    name = redactSecrets(parsed.name.ifBlank { parsed.jobTitle.ifBlank { "Bot" } }).first,
                    avatar = parsed.avatar.ifBlank { defaultAvatarForRole(parsed.templateId) },
                    jobTitle = r.optString("job_title").ifBlank { parsed.jobTitle },
                    publicDescription = redactSecrets(r.optString("description").ifBlank { parsed.publicDescription }).first,
                    rolePrompt = cleanInstructions,
                    approvalMode = if (isOpenDuet) "ask" else parsed.approvalMode,
                    skills = importedSkills,
                    memories = (parsed.memories + extraMemories).distinctBy { "${it.topic}:${it.fact}" },
                    lastText = parsed.publicDescription.ifBlank { "已导入角色配置" },
                    lastAt = System.currentTimeMillis(),
                )
            } else {
                // 支持直接粘贴 OpenClaw SOUL.md / AGENTS.md 纯 Markdown
                val firstHeading = trimmed.lineSequence()
                    .firstOrNull { it.trim().startsWith("#") }
                    ?.replace("#", "")
                    ?.trim()
                    ?.take(24)
                    .orEmpty()
                BotFriend(
                    id = targetBotId ?: ("bot_" + UUID.randomUUID().toString().take(8)),
                    templateId = "custom_markdown",
                    name = firstHeading.ifBlank { "自定义 Bot" },
                    avatar = defaultAvatarForRole(""),
                    jobTitle = "自定义角色",
                    department = "自定义智囊团",
                    lifecycle = BotLifecycle.PROJECT,
                    engine = BotModelPolicy.defaultEngine(""),
                    publicDescription = trimmed.lineSequence().firstOrNull { !it.startsWith("#") && it.isNotBlank() }?.take(80)
                        ?: "从 SOUL.md / AGENTS.md 剧本导入的专职 Bot",
                    rolePrompt = redactSecrets(trimmed).first,
                    thinkingEffort = "high",
                    isolationMode = "worktree",
                    approvalMode = "ask",
                    lastText = "已从 Markdown 剧本导入",
                    lastAt = System.currentTimeMillis(),
                )
            }
            upsertBot(context, bot)
            bot
        }
    }

    /**
     * 根据当前群成员实时生成集合欢迎文案（展示每个 Bot 的公开分工名片、绑定技能数与工具边界）
     */
    fun buildWelcomeMessageText(groupName: String, members: List<BotFriend>): String {
        val roster = members.joinToString("\n") { b ->
            val icon = if (b.lifecycle == BotLifecycle.MANAGEMENT) "🧭" else "⚙️"
            val boundary = if (b.disabledTools.isNotEmpty() || b.approvalMode == "plan") "🔒只读审校边界" else "🌿${b.isolationMode}"
            val activeSkills = b.skills.filter { it.enabled }
            val skillBadge = if (activeSkills.isNotEmpty()) " · ${activeSkills.size}项6段式Skill(${activeSkills.first().name.take(16)})" else ""
            val desc = b.publicDescription.ifBlank { b.rolePrompt.lineSequence().firstOrNull()?.take(42) ?: "随时待命" }
            "- $icon **${b.name} [${b.jobTitle}]** (`${b.engine}` · 思考:${b.thinkingEffort} · $boundary$skillBadge)：$desc"
        }
        return "**$groupName** 的团队成员已就绪：\n$roster\n\n发送任务或 @成员 即可开始协作。可选择只读咨询或执行任务，添加技能，并对结果追问或要求返工。"
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { k ->
            arr.optString(k).trim().takeIf { it.isNotEmpty() }
        }
    }

    internal fun parseBotJson(o: JSONObject): BotFriend {
        val sIds = parseStringList(o.optJSONArray("sessionIds"))
        val mArr = o.optJSONArray("memories")
        val memories = if (mArr == null) emptyList() else (0 until mArr.length()).mapNotNull { k ->
            val m = mArr.optJSONObject(k) ?: return@mapNotNull null
            val fact = m.optString("fact").ifBlank { m.optString("content") }.trim()
            if (fact.isEmpty()) return@mapNotNull null
            BotMemoryFact(
                id = m.optString("id").ifBlank { "mem-$k" },
                fact = fact,
                category = m.optString("category", "general"),
                source = m.optString("source", "user"),
                topic = m.optString("topic", ""),
                aliases = parseStringList(m.optJSONArray("aliases")),
                archived = m.optBoolean("archived", false),
                createdAt = m.optLong("createdAt", 0L),
            )
        }
        val hArr = o.optJSONArray("instructionsHistory")
        val history = if (hArr == null) emptyList() else (0 until hArr.length()).mapNotNull { k ->
            val h = hArr.optJSONObject(k) ?: return@mapNotNull null
            BotInstructionVersion(
                version = h.optLong("version", 1L),
                rolePrompt = h.optString("rolePrompt"),
                updatedBy = h.optString("updatedBy", "user"),
                updatedAt = h.optLong("updatedAt", 0L),
                note = h.optString("note").ifBlank { h.optString("reason") },
            )
        }
        val disabledTools = parseStringList(o.optJSONArray("disabledTools") ?: o.optJSONArray("disabled_tools"))
        val exampleRequests = parseStringList(o.optJSONArray("exampleRequests") ?: o.optJSONArray("example_requests"))
        val requires = parseStringList(o.optJSONArray("requires"))
        val skArr = o.optJSONArray("skills")
        val skills = if (skArr == null) emptyList() else (0 until skArr.length()).mapNotNull { k ->
            val sk = skArr.optJSONObject(k) ?: return@mapNotNull null
            BotSkillSpec(
                id = sk.optString("id", "skill_$k"),
                name = sk.optString("name"),
                description = sk.optString("description"),
                whenToUse = sk.optString("whenToUse"),
                playbook = sk.optString("playbook"),
                enabled = sk.optBoolean("enabled", true),
                source = sk.optString("source", "builtin"),
            )
        }
        val rtArr = o.optJSONArray("defaultRoutines")
        val routines = if (rtArr == null) emptyList() else (0 until rtArr.length()).mapNotNull { k ->
            val rt = rtArr.optJSONObject(k) ?: return@mapNotNull null
            BotRoutineSpec(
                name = rt.optString("name"),
                cron = rt.optString("cron"),
                prompt = rt.optString("prompt"),
                enabled = rt.optBoolean("enabled", true),
            )
        }
        val owArr = o.optJSONArray("openWork")
        val openWork = if (owArr == null) emptyList() else (0 until owArr.length()).mapNotNull { k ->
            val itemObj = owArr.optJSONObject(k)
            if (itemObj != null) {
                BotOpenWorkItem(
                    id = itemObj.optString("id", "work_$k"),
                    title = itemObj.optString("title"),
                    notes = itemObj.optString("notes"),
                    status = itemObj.optString("status", "open"),
                    updatedAt = itemObj.optLong("updatedAt", 0L),
                )
            } else {
                val str = owArr.optString(k).trim()
                if (str.isEmpty()) null else BotOpenWorkItem(id = "work_$k", title = str)
            }
        }
        val lcStr = o.optString("lifecycle", "PROJECT")
        val lifecycle = if (lcStr == "MANAGEMENT") BotLifecycle.MANAGEMENT else BotLifecycle.PROJECT
        val parsedEngine = BotModelPolicy.normalize(o.optString("engine"), o.optString("templateId"))
        val parsedModel = BotModelPolicy.normalizeModel(o.optString("model"), o.optString("templateId"), o.has("model"))
        val rawName = o.optString("name").trim()
        val parsedName = if (parsedEngine.equals("antigravity", ignoreCase = true) && rawName == "Grok") {
            "Gemini"
        } else {
            rawName.ifBlank { TEMPLATES.firstOrNull { it.id == o.optString("templateId") }?.defaultName ?: o.optString("jobTitle").ifBlank { "Bot" } }
        }
        val rawAvatar = o.optString("avatar").trim()
        val parsedAvatar = if (parsedEngine.equals("antigravity", ignoreCase = true) && rawAvatar == "preset:grok") {
            "preset:gemini"
        } else {
            rawAvatar
        }
        val rawDept = o.optString("department", "研发部")
        val parsedDept = if (rawDept.contains("Grok")) "核心总调度部" else rawDept
        val rawPublicDesc = o.optString("publicDescription").ifBlank { o.optString("description") }
        val parsedPublicDesc = if (rawPublicDesc.contains("Harper") || rawPublicDesc.contains("Benjamin") || rawPublicDesc.contains("Captain & Chief of Staff")) {
            TEMPLATES.firstOrNull { it.id == o.optString("templateId") }?.publicDescription ?: rawPublicDesc
        } else {
            rawPublicDesc
        }
        val rawRolePrompt = o.optString("rolePrompt").ifBlank { o.optString("instructions") }.ifBlank { o.optString("system_prompt") }
        val parsedRolePrompt = if (rawRolePrompt.contains("Captain (Grok)") || rawRolePrompt.contains("Harper") || rawRolePrompt.contains("Benjamin")) {
            TEMPLATES.firstOrNull { it.id == o.optString("templateId") }?.rolePrompt ?: rawRolePrompt
        } else {
            rawRolePrompt
        }
        return BotFriend(
            id = o.optString("id"),
            templateId = o.optString("templateId"),
            name = parsedName,
            avatar = parsedAvatar,
            jobTitle = o.optString("jobTitle").ifBlank { o.optString("job_title", "工程师") },
            department = parsedDept,
            lifecycle = lifecycle,
            engine = parsedEngine,
            model = parsedModel,
            device = o.optString("device", "pc"),
            cwd = o.optString("cwd", "D:\\AI\\VibeLink"),
            publicDescription = parsedPublicDesc,
            rolePrompt = parsedRolePrompt,
            thinkingEffort = o.optString("thinkingEffort").ifBlank { o.optString("thinking_effort", "medium") },
            isolationMode = o.optString("isolationMode").ifBlank { o.optString("isolation_mode", if (lifecycle == BotLifecycle.PROJECT) "worktree" else "shared") },
            approvalMode = o.optString("approvalMode").ifBlank { o.optString("approval_mode", "ask") },
            instructionsVersion = o.optLong("instructionsVersion", 1L).coerceAtLeast(1L),
            instructionsHistory = history,
            manageOtherBots = o.optBoolean("manageOtherBots", true),
            dailyCheckIns = o.optBoolean("dailyCheckIns", true),
            disabledTools = disabledTools,
            exampleRequests = exampleRequests,
            requires = requires,
            skills = skills,
            defaultRoutines = routines,
            openWork = openWork,
            memories = memories,
            activeSessionId = if (parsedEngine == o.optString("engine") && parsedModel == o.optString("model")) o.optString("activeSessionId") else "",
            sessionIds = if (parsedEngine == o.optString("engine") && parsedModel == o.optString("model")) sIds else emptyList(),
            archived = o.optBoolean("archived", false),
            lastText = o.optString("lastText"),
            lastAt = o.optLong("lastAt"),
        )
    }

    internal fun botToJson(b: BotFriend): JSONObject =
        JSONObject().apply {
            put("id", b.id)
            put("templateId", b.templateId)
            put("name", b.name.ifBlank { b.jobTitle.ifBlank { "Bot" } })
            put("avatar", b.avatar)
            put("jobTitle", b.jobTitle)
            put("department", b.department)
            put("lifecycle", b.lifecycle.name)
            put("engine", b.engine)
            put("model", b.model)
            put("device", b.device)
            put("cwd", b.cwd)
            put("publicDescription", b.publicDescription)
            put("rolePrompt", b.rolePrompt)
            put("thinkingEffort", b.thinkingEffort)
            put("isolationMode", b.isolationMode)
            put("approvalMode", b.approvalMode)
            put("instructionsVersion", b.instructionsVersion)
            put(
                "instructionsHistory",
                JSONArray().apply {
                    b.instructionsHistory.forEach { h ->
                        put(
                            JSONObject().apply {
                                put("version", h.version)
                                put("rolePrompt", h.rolePrompt)
                                put("updatedBy", h.updatedBy)
                                put("updatedAt", h.updatedAt)
                                put("note", h.note)
                            },
                        )
                    }
                },
            )
            put("manageOtherBots", b.manageOtherBots)
            put("dailyCheckIns", b.dailyCheckIns)
            put("disabledTools", JSONArray(b.disabledTools))
            put("exampleRequests", JSONArray(b.exampleRequests))
            put("requires", JSONArray(b.requires))
            put(
                "skills",
                JSONArray().apply {
                    b.skills.forEach { sk ->
                        put(
                            JSONObject().apply {
                                put("id", sk.id)
                                put("name", sk.name)
                                put("description", sk.description)
                                put("whenToUse", sk.whenToUse)
                                put("playbook", sk.playbook)
                                put("enabled", sk.enabled)
                                put("source", sk.source)
                            },
                        )
                    }
                },
            )
            put(
                "defaultRoutines",
                JSONArray().apply {
                    b.defaultRoutines.forEach { rt ->
                        put(
                            JSONObject().apply {
                                put("name", rt.name)
                                put("cron", rt.cron)
                                put("prompt", rt.prompt)
                                put("enabled", rt.enabled)
                            },
                        )
                    }
                },
            )
            put(
                "openWork",
                JSONArray().apply {
                    b.openWork.forEach { ow ->
                        put(
                            JSONObject().apply {
                                put("id", ow.id)
                                put("title", ow.title)
                                put("notes", ow.notes)
                                put("status", ow.status)
                                put("updatedAt", ow.updatedAt)
                            },
                        )
                    }
                },
            )
            put(
                "memories",
                JSONArray().apply {
                    b.memories.forEach { m ->
                        put(
                            JSONObject().apply {
                                put("id", m.id)
                                put("fact", m.fact)
                                put("category", m.category)
                                put("source", m.source)
                                put("topic", m.topic)
                                put("aliases", JSONArray(m.aliases))
                                put("archived", m.archived)
                                put("createdAt", m.createdAt)
                            },
                        )
                    }
                },
            )
            put("activeSessionId", b.activeSessionId)
            put("sessionIds", JSONArray(b.sessionIds))
            put("archived", b.archived)
            put("lastText", b.lastText)
            put("lastAt", b.lastAt)
        }

    private fun readBotsFromPrefs(context: Context): List<BotFriend> {
        val raw = prefs(context).getString(KEY_BOTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { parseBotJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeBotsToPrefs(context: Context, bots: List<BotFriend>) {
        val arr = JSONArray()
        bots.forEach { b -> arr.put(botToJson(b)) }
        prefs(context).edit().putString(KEY_BOTS, arr.toString()).apply()
    }

    fun listBots(context: Context, includeArchived: Boolean = false): List<BotFriend> {
        ensureSeeded(context)
        val all = _botsFlow.value.ifEmpty { readBotsFromPrefs(context).also { _botsFlow.value = it } }
        return if (includeArchived) all else all.filter { !it.archived }
    }

    fun getBot(context: Context, botId: String): BotFriend? =
        listBots(context, includeArchived = true).firstOrNull { it.id == botId }

    fun saveBots(context: Context, bots: List<BotFriend>) {
        writeBotsToPrefs(context, bots)
        _botsFlow.value = bots
        _versionFlow.value++
    }

    /**
     * 新增或修改 Bot 好友，并将新昵称、新职位、新引擎、新头像实时同步到所有群聊摘要与历史消息中。
     */
    @Synchronized
    fun upsertBot(context: Context, bot: BotFriend) {
        val previous = getBot(context, bot.id)
        val normalized = bot.copy(
            name = bot.name.trim().ifBlank { bot.jobTitle },
            avatar = bot.avatar.trim(),
            engine = BotModelPolicy.requireEngine(bot.engine),
            activeSessionId = if (previous != null && (previous.engine != bot.engine || previous.model != bot.model)) "" else bot.activeSessionId,
            sessionIds = if (previous != null && (previous.engine != bot.engine || previous.model != bot.model)) emptyList() else bot.sessionIds,
        )
        AgentPrefs.setAvatar(context, normalized.id, normalized.avatar)
        val all = listBots(context, includeArchived = true).toMutableList()
        val idx = all.indexOfFirst { it.id == normalized.id }
        val oldBot = if (idx >= 0) all[idx] else null
        if (idx >= 0) all[idx] = normalized else all.add(0, normalized)
        saveBots(context, all)
        if (previous == null || hubProfile(previous).toString() != hubProfile(normalized).toString()) {
            enqueueProfile(context, normalized)
            val appContext = context.applicationContext
            profileSyncScope.launch { syncWithHub(appContext) }
        }

        // 全局同步所有群聊中的历史发言人名字、职位及群摘要
        val groups = listGroups(context).toMutableList()
        var groupsChanged = false
        for (gi in groups.indices) {
            val g = groups[gi]
            val msgs = listGroupMessages(context, g.id).toMutableList()
            var msgsChanged = false
            val memberBots = g.botIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
            for (mi in msgs.indices) {
                val m = msgs[mi]
                if (m.id == "msg_welcome_1") {
                    val senderBot = all.firstOrNull { it.id == m.senderId } ?: bot
                    val updatedWelcome = m.copy(
                        senderName = senderBot.name,
                        senderJobTitle = senderBot.jobTitle,
                        senderEngine = senderBot.engine,
                        text = buildWelcomeMessageText(g.name, memberBots),
                    )
                    if (updatedWelcome != m) {
                        msgs[mi] = updatedWelcome
                        msgsChanged = true
                    }
                } else if (m.senderId == bot.id) {
                    val updatedMsg = m.copy(
                        senderName = bot.name,
                        senderJobTitle = bot.jobTitle,
                        senderEngine = bot.engine,
                    )
                    if (updatedMsg != m) {
                        msgs[mi] = updatedMsg
                        msgsChanged = true
                    }
                } else if (oldBot != null && oldBot.name != bot.name && m.text.contains(oldBot.name)) {
                    msgs[mi] = m.copy(text = m.text.replace(oldBot.name, bot.name))
                    msgsChanged = true
                }
            }
            if (msgsChanged) {
                saveGroupMessagesInternal(context, g.id, msgs)
            }
            if (oldBot != null && (g.lastText.contains(oldBot.name) || g.lastText.contains(oldBot.jobTitle))) {
                val newLastText = g.lastText
                    .replace("[${oldBot.jobTitle} ${oldBot.name}]", "[${bot.jobTitle} ${bot.name}]")
                    .replace(oldBot.name, bot.name)
                if (newLastText != g.lastText) {
                    groups[gi] = g.copy(lastText = newLastText)
                    groupsChanged = true
                }
            }
        }
        if (groupsChanged) {
            saveGroups(context, groups)
        } else {
            _versionFlow.value++
        }
    }

    fun deleteOrArchiveBot(context: Context, botId: String, archiveOnly: Boolean = true) {
        val all = listBots(context, includeArchived = true).toMutableList()
        val idx = all.indexOfFirst { it.id == botId }
        if (idx < 0) return
        if (archiveOnly) {
            all[idx] = all[idx].copy(archived = true)
        } else {
            all.removeAt(idx)
        }
        saveBots(context, all)
    }

    fun bindSessionToBot(context: Context, botId: String, sessionId: String, summaryText: String? = null) {
        val b = getBot(context, botId) ?: return
        val updatedIds = if (sessionId.isNotBlank()) (listOf(sessionId) + b.sessionIds).distinct() else b.sessionIds
        upsertBot(
            context,
            b.copy(
                activeSessionId = sessionId.ifBlank { b.activeSessionId },
                sessionIds = updatedIds,
                lastText = summaryText?.takeIf { it.isNotBlank() } ?: b.lastText,
                lastAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun readGroupsFromPrefs(context: Context): List<TeamGroup> {
        val raw = prefs(context).getString(KEY_GROUPS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val bArr = o.optJSONArray("botIds")
                val bIds = if (bArr == null) emptyList() else (0 until bArr.length()).mapNotNull { k ->
                    bArr.optString(k).takeIf { it.isNotBlank() }
                }
                TeamGroup(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    description = o.optString("description"),
                    botIds = bIds,
                    cwd = o.optString("cwd", "D:\\AI\\VibeLink"),
                    lastText = o.optString("lastText"),
                    lastAt = o.optLong("lastAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeGroupsToPrefs(context: Context, groups: List<TeamGroup>) {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(
                JSONObject().apply {
                    put("id", g.id)
                    put("name", g.name)
                    put("description", g.description)
                    put("botIds", JSONArray(g.botIds))
                    put("cwd", g.cwd)
                    put("lastText", g.lastText)
                    put("lastAt", g.lastAt)
                },
            )
        }
        prefs(context).edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    fun listGroups(context: Context): List<TeamGroup> {
        ensureSeeded(context)
        return _groupsFlow.value.ifEmpty { readGroupsFromPrefs(context).also { _groupsFlow.value = it } }
    }

    fun getGroup(context: Context, groupId: String): TeamGroup? =
        listGroups(context).firstOrNull { it.id == groupId }

    fun saveGroups(context: Context, groups: List<TeamGroup>) {
        writeGroupsToPrefs(context, groups)
        _groupsFlow.value = groups
        _versionFlow.value++
    }

    fun upsertGroup(context: Context, group: TeamGroup) {
        val all = listGroups(context).toMutableList()
        val idx = all.indexOfFirst { it.id == group.id }
        if (idx >= 0) all[idx] = group else all.add(0, group)
        saveGroups(context, all)
    }

    fun deleteGroup(context: Context, groupId: String) {
        val all = listGroups(context).filterNot { it.id == groupId }
        saveGroups(context, all)
        prefs(context).edit().remove(KEY_GROUP_MSGS_PREFIX + groupId).apply()
    }

    /**
     * 动态格式化群聊在首页列表中的最后一条消息摘要
     */
    fun formatGroupPreview(context: Context, group: TeamGroup, bots: List<BotFriend>): String {
        val lastMsg = listGroupMessages(context, group.id).lastOrNull()
        if (lastMsg != null) {
            if (lastMsg.senderId == "user") {
                return "我: ${lastMsg.text.replace("\n", " ").take(45)}"
            }
            val liveBot = bots.firstOrNull { it.id == lastMsg.senderId }
            val senderTitle = liveBot?.jobTitle ?: lastMsg.senderJobTitle
            val senderName = liveBot?.name ?: lastMsg.senderName
            val summary = if (lastMsg.id == "msg_welcome_1") {
                "团队已就绪，发任务或 @成员 即可开始协作"
            } else {
                lastMsg.text.replace("\n", " ").take(45)
            }
            return "[${senderTitle} ${senderName}]: $summary"
        }
        return group.lastText
    }

    fun listGroupMessages(context: Context, groupId: String): List<TeamGroupMessage> {
        ensureSeeded(context)
        val raw = prefs(context).getString(KEY_GROUP_MSGS_PREFIX + groupId, null) ?: return emptyList()
        val bots = _botsFlow.value.ifEmpty { readBotsFromPrefs(context) }
        val botsMap = bots.associateBy { it.id }
        val group = _groupsFlow.value.firstOrNull { it.id == groupId }
        val memberBots = group?.botIds?.mapNotNull { botsMap[it] } ?: bots
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val senderId = o.optString("senderId")
                val liveBot = botsMap[senderId]
                val rawText = o.optString("text")
                val rawStatus = o.optString("status", "done")
                val effectiveStatus = if (rawStatus == "done" && extractNeedsInput(rawText) != null) "needs_input" else rawStatus
                val toolsArr = o.optJSONArray("tools")
                val parsedTools = if (toolsArr == null) {
                    emptyList()
                } else {
                    (0 until toolsArr.length()).mapNotNull { ti ->
                        val to = toolsArr.optJSONObject(ti) ?: return@mapNotNull null
                        RemoteApi.ToolCall(
                            name = to.optString("name"),
                            title = to.optString("title"),
                            file = to.optString("file"),
                            command = to.optString("command"),
                            diff = to.optString("diff"),
                        )
                    }
                }
                val parsedAt = o.optLong("at")
                TeamGroupMessage(
                    id = id,
                    senderId = senderId,
                    senderName = liveBot?.name ?: o.optString("senderName"),
                    senderJobTitle = liveBot?.jobTitle ?: o.optString("senderJobTitle"),
                    senderEngine = liveBot?.engine ?: o.optString("senderEngine"),
                    text = if (id == "msg_welcome_1" && memberBots.isNotEmpty()) {
                        buildWelcomeMessageText(group?.name ?: "核心研发协作组", memberBots)
                    } else {
                        rawText
                    },
                    reasoning = o.optString("reasoning", ""),
                    startedAt = o.optLong("startedAt", parsedAt),
                    at = parsedAt,
                    tools = parsedTools,
                    linkedSessionId = o.optString("linkedSessionId"),
                    status = effectiveStatus,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveGroupMessagesInternal(context: Context, groupId: String, msgs: List<TeamGroupMessage>) {
        val arr = JSONArray()
        msgs.takeLast(200).forEach { m ->
            val effectiveStatus = if (m.status == "done" && extractNeedsInput(m.text) != null) "needs_input" else m.status
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("senderId", m.senderId)
                    put("senderName", m.senderName)
                    put("senderJobTitle", m.senderJobTitle)
                    put("senderEngine", m.senderEngine)
                    put("text", m.text)
                    put("reasoning", m.reasoning)
                    put("startedAt", m.startedAt)
                    put("at", m.at)
                    if (m.tools.isNotEmpty()) {
                        put(
                            "tools",
                            JSONArray().apply {
                                m.tools.take(40).forEach { tc ->
                                    put(
                                        JSONObject().apply {
                                            put("name", tc.name)
                                            put("title", tc.title)
                                            put("file", tc.file)
                                            put("command", tc.command)
                                            put("diff", tc.diff)
                                        },
                                    )
                                }
                            },
                        )
                    }
                    put("linkedSessionId", m.linkedSessionId)
                    put("status", effectiveStatus)
                },
            )
        }
        prefs(context).edit().putString(KEY_GROUP_MSGS_PREFIX + groupId, arr.toString()).apply()
    }

    @Synchronized
    fun saveGroupMessages(context: Context, groupId: String, msgs: List<TeamGroupMessage>) {
        saveGroupMessagesInternal(context, groupId, msgs)
        _versionFlow.value++
    }

    /**
     * 线程安全地插入或更新单条群聊消息（保证群聊多指令、多 Bot 并发执行时不会互相覆盖消息列表）
     */
    @Synchronized
    fun upsertGroupMessage(context: Context, groupId: String, msg: TeamGroupMessage): List<TeamGroupMessage> {
        val current = listGroupMessages(context, groupId).toMutableList()
        val idx = current.indexOfFirst { it.id == msg.id }
        if (idx >= 0) current[idx] = msg else current.add(msg)
        saveGroupMessagesInternal(context, groupId, current)
        _versionFlow.value++
        return current
    }

    /**
     * 为指定 Bot 新增一条长期记忆或专题记忆条目（对标 Open Duet `MEMORY.md` 预算自动归档 + `topic`/`aliases` 专题笔记）
     */
    fun addMemoryFact(
        context: Context,
        botId: String,
        fact: String,
        category: String = "general",
        topic: String = "",
        aliases: List<String> = emptyList(),
    ) {
        val clean = redactSecrets(fact.trim()).first
        if (clean.isEmpty()) return
        val cleanTopic = topic.trim()
        val b = getBot(context, botId) ?: return
        val newMem = BotMemoryFact(
            id = "mem-${System.currentTimeMillis()}",
            fact = clean,
            category = if (cleanTopic.isNotEmpty()) "topic" else category,
            source = "user",
            topic = cleanTopic,
            aliases = aliases.map { it.trim() }.filter { it.isNotEmpty() },
            archived = false,
            createdAt = System.currentTimeMillis(),
        )
        val combined = listOf(newMem) + b.memories.filterNot { it.fact == clean && it.topic == cleanTopic }
        // 若核心记忆（非 topic、未归档）超过 MEMORY_BUDGET_MAX_ACTIVE，自动归档最早的条目
        var activeCoreSeen = 0
        val budgeted = combined.map { m ->
            if (!m.archived && m.topic.isBlank()) {
                activeCoreSeen++
                if (activeCoreSeen > MEMORY_BUDGET_MAX_ACTIVE) m.copy(archived = true) else m
            } else {
                m
            }
        }
        upsertBot(context, b.copy(memories = budgeted))
    }

    /**
     * 删除指定 Bot 的一条长期记忆
     */
    fun removeMemoryFact(context: Context, botId: String, memId: String) {
        val b = getBot(context, botId) ?: return
        val updated = b.copy(memories = b.memories.filterNot { it.id == memId })
        upsertBot(context, updated)
    }

    /**
     * 与 Hub 控制面 (`/api/bots`) 双向同步 Bot 舰队、Grok Bot × Open Duet 配方、技能剧本与长期记忆
     */
    // Conversation IDs, memories and server history are not profile edits.
    private fun hubProfile(bot: BotFriend): JSONObject = botToJson(bot).apply {
        listOf("activeSessionId", "sessionIds", "lastText", "lastAt", "memories", "openWork", "instructionsHistory", "instructionsVersion")
            .forEach { remove(it) }
    }

    private fun enqueueProfile(context: Context, bot: BotFriend) {
        val pending = JSONObject(prefs(context).getString(KEY_PENDING_PROFILES, "{}") ?: "{}")
        pending.put(bot.id, hubProfile(bot))
        prefs(context).edit().putString(KEY_PENDING_PROFILES, pending.toString()).apply()
    }

    private fun flushProfiles(context: Context, hub: String, token: String, guard: () -> Unit = {}) {
        val capturedPrefs = prefs(context)
        val snapshot = synchronized(this) {
            JSONObject(capturedPrefs.getString(KEY_PENDING_PROFILES, "{}") ?: "{}")
        }
        for (id in snapshot.keys()) {
            guard()
            val profile = snapshot.getJSONObject(id)
            RemoteApi.botUpsert(hub, token, profile)
            guard()
            synchronized(this) {
                guard()
                val pending = JSONObject(capturedPrefs.getString(KEY_PENDING_PROFILES, "{}") ?: "{}")
                // A newer edit made while this request was in flight must remain pending.
                if (pending.optJSONObject(id)?.toString() == profile.toString()) {
                    pending.remove(id)
                    capturedPrefs.edit().putString(KEY_PENDING_PROFILES, pending.toString()).apply()
                }
            }
        }
    }

    /** Flush local model bindings before the Hub snapshots participants for durable work. */
    suspend fun prepareGroupWork(context: Context, groupId: String, hub: String, token: String, guard: () -> Unit) = withContext(Dispatchers.IO) {
        profileSyncMutex.withLock {
            guard()
            check(hub.isNotBlank() && token.isNotBlank()) { "请先连接 Hub 并完成配对" }
            val group = getGroup(context, groupId) ?: error("群聊已不存在")
            synchronized(this@BotTeamStore) {
                group.botIds.mapNotNull { getBot(context, it) }.forEach { enqueueProfile(context, it) }
            }
            flushProfiles(context, hub, token, guard)
            guard()
            RemoteApi.botGroupUpsert(hub, token, JSONObject().put("id", group.id).put("name", group.name)
                .put("description", group.description).put("botIds", JSONArray(group.botIds)).put("cwd", group.cwd))
            guard()
        }
    }

    /** Merge server-owned messages by ID without posting them back to the Hub. */
    @Synchronized
    fun mergeHubGroupMessages(context: Context, groupId: String, root: JSONObject, guard: () -> Unit = {}) {
        guard()
        val arr = root.optJSONArray("messages") ?: error("Hub group messages response is incompatible")
        val current = listGroupMessages(context, groupId)
        val merged = current.associateBy { it.id }.toMutableMap()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val toolJson = o.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until toolJson.length()).mapNotNull { index -> toolJson.optJSONObject(index)?.let { t ->
                RemoteApi.ToolCall(name = t.optString("name"), title = t.optString("title"), file = t.optString("file"),
                    command = t.optString("command"), diff = t.optString("diff"))
            } }
            merged[id] = TeamGroupMessage(id = id, senderId = o.optString("senderId"),
                senderName = o.optString("senderName"), senderJobTitle = o.optString("senderJobTitle"),
                senderEngine = o.optString("senderEngine", "codex"), text = o.optString("text"),
                reasoning = o.optString("reasoning"), startedAt = o.optLong("startedAt"),
                at = o.optLong("at"), tools = tools, linkedSessionId = o.optString("linkedSessionId"), status = o.optString("status", "done"))
        }
        val updated = merged.values.sortedBy { it.at }.takeLast(200)
        if (updated != current) {
            guard()
            saveGroupMessages(context, groupId, updated)
            val last = updated.lastOrNull()
            val group = getGroup(context, groupId)
            if (last != null && group != null) upsertGroup(context, group.copy(lastText = last.text.take(80), lastAt = last.at))
        }
    }

    suspend fun syncWithHub(context: Context): Boolean = withContext(Dispatchers.IO) { profileSyncMutex.withLock {
        ensureSeeded(context)
        val connection = RemoteStore.connection(context)
        val hub = connection.hub
        val token = connection.token
        fun guard() { check(RemoteStore.matches(context, connection)) { "登录身份已变化" } }
        if (hub.isBlank() || token.isBlank()) return@withLock false
        runCatching {
            flushProfiles(context, hub, token, ::guard)
            val root = RemoteApi.botsList(hub, token)
            guard()
            val bArr = root.optJSONArray("bots")
            synchronized(this@BotTeamStore) {
            guard()
            if (bArr != null && bArr.length() > 0) {
                val remoteBots = (0 until bArr.length()).mapNotNull { i ->
                    bArr.optJSONObject(i)?.let { parseBotJson(it) }
                }
                val localBots = listBots(context, includeArchived = true)
                val pending = JSONObject(prefs(context).getString(KEY_PENDING_PROFILES, "{}") ?: "{}")
                val mergedMap = LinkedHashMap<String, BotFriend>()
                for (rb in remoteBots) {
                    val lb = localBots.firstOrNull { it.id == rb.id }
                    val merged = if (lb != null && pending.has(lb.id)) {
                        lb
                    } else if (lb != null) {
                        val sameBinding = lb.engine == rb.engine && lb.model == rb.model && lb.device == rb.device && lb.cwd == rb.cwd
                        val preferLocalName = isDefaultOrLegacyBotName(rb.name) && rb.name != lb.name && lb.name in setOf("DeepSeek", "Claude", "GPT", "GLM", "Gemini", "Grok")
                        val preferLocalAvatar = lb.avatar.startsWith("data:image") || (rb.avatar.isBlank() && lb.avatar.isNotBlank())
                        rb.copy(
                            name = if (preferLocalName) lb.name else rb.name,
                            avatar = if (preferLocalAvatar) lb.avatar else rb.avatar.ifBlank { defaultAvatarForRole(rb.templateId) },
                            activeSessionId = rb.activeSessionId.ifBlank { if (sameBinding) lb.activeSessionId else "" },
                            sessionIds = (rb.sessionIds + if (sameBinding) lb.sessionIds else emptyList()).distinct(),
                            publicDescription = rb.publicDescription.ifBlank { lb.publicDescription },
                            exampleRequests = rb.exampleRequests.ifEmpty { lb.exampleRequests },
                            requires = rb.requires.ifEmpty { lb.requires },
                            skills = rb.skills.ifEmpty { lb.skills },
                            defaultRoutines = rb.defaultRoutines.ifEmpty { lb.defaultRoutines },
                            disabledTools = rb.disabledTools.ifEmpty { lb.disabledTools },
                            openWork = rb.openWork.ifEmpty { lb.openWork },
                            memories = if (rb.memories.isNotEmpty()) rb.memories else lb.memories,
                            lastText = rb.lastText.ifBlank { lb.lastText },
                            lastAt = maxOf(rb.lastAt, lb.lastAt),
                        )
                    } else {
                        rb
                    }
                    mergedMap[merged.id] = merged
                }
                for (lb in localBots) {
                    if (!mergedMap.containsKey(lb.id)) {
                        mergedMap[lb.id] = lb
                    }
                }
                guard()
                saveBots(context, mergedMap.values.toList())
            }
            }
            true
        }.getOrDefault(false)
    } }

    /**
     * 确保某个 Bot 拥有一个可用的底层远程会话 ID。
     * - 长期管理型 Bot (`BotLifecycle.MANAGEMENT`) 复用其绑定的专属常驻会话（若工作目录不匹配则新建）；
     * - 短期项目型 Bot (`BotLifecycle.PROJECT`) 在群聊派发新任务 (`forceNew = true`) 或尚无当前项目会话时自动新建独立工位会话；
     * - 绝不劫持其他历史项目（如 `tbs-workbench`）的无关旧会话。
     */
    suspend fun ensureBotRemoteSession(
        context: Context,
        bot: BotFriend,
        taskTitle: String,
        forceNew: Boolean = false,
        initialText: String = "",
    ): String = withContext(Dispatchers.IO) {
        profileSyncMutex.withLock {
            val latestBot = getBot(context, bot.id) ?: bot
            val hub = RemoteStore.hub(context)
            val token = RemoteStore.token(context)
            val engine = BotModelPolicy.requireEngine(latestBot.engine)
            check(hub.isNotBlank() && token.isNotBlank()) { "请先在远程连接中配置 Hub 并完成配对" }
            BotSessionBootstrap.create(
                synchronize = {
                    // Repairs model choices saved by older versions without an outbox too.
                    synchronized(this@BotTeamStore) { enqueueProfile(context, latestBot) }
                    flushProfiles(context, hub, token)
                    check(getBot(context, bot.id)?.let { it.engine == latestBot.engine && it.model == latestBot.model } != false) {
                        "模型配置已变化，请重新发送"
                    }
                },
                start = {
                    if (!forceNew && latestBot.activeSessionId.isNotBlank() && !latestBot.activeSessionId.startsWith("01a1a0")) {
                        val sessions = runCatching { loadAgentSessions(context, engine) }.getOrDefault(emptyList())
                        val matched = sessions.firstOrNull { it.id == latestBot.activeSessionId }
                        val sameBinding = matched != null && matched.model == latestBot.model &&
                            matched.device == latestBot.device.ifBlank { "pc" } &&
                            matched.cwd.replace('\\', '/').trimEnd('/').equals(
                                latestBot.cwd.replace('\\', '/').trimEnd('/'), ignoreCase = true,
                            )
                        if (sameBinding) return@create latestBot.activeSessionId
                    }
                    val created = RemoteApi.createSession(
                        hub = hub, token = token, device = latestBot.device.ifBlank { "pc" },
                        agent = engine, model = latestBot.model, cwd = latestBot.cwd, text = initialText,
                        botId = latestBot.id, thinkingEffort = latestBot.thinkingEffort,
                        isolationMode = latestBot.isolationMode,
                    )
                    check(created.id.isNotBlank()) { "Codex 未返回可用会话 ID" }
                    bindSessionToBot(context, latestBot.id, created.id, taskTitle.take(40))
                    val cachedList = RemoteSessionCache.getCachedSessions(context, engine)
                    RemoteSessionCache.putCachedSessions(context, engine, listOf(created) + cachedList.filterNot { it.id == created.id })
                    created.id
                },
            )
        }
    }

    /**
     * 当某个 Bot 的底层会话产生最新完整回复时，自动同步回所有群聊中该 Bot 挂起 (`thinking` 或 `仍在后台持续执行中`) 的消息气泡。
     */
    @Synchronized
    fun syncCompletedSessionToGroups(
        context: Context,
        botId: String,
        sessionId: String,
        latestAssistantText: String,
        reasoning: String = "",
        startedAt: Long = 0L,
        completedAt: Long = 0L,
        tools: List<RemoteApi.ToolCall> = emptyList(),
    ) {
        val cleanReply = latestAssistantText.trim()
        if (cleanReply.isEmpty() || sessionId.isBlank() || cleanReply.startsWith("🔧 正在调用工具") || cleanReply.startsWith("🔧 已调用")) return
        val groups = listGroups(context).toMutableList()
        var anyGroupUpdated = false
        for (gi in groups.indices) {
            val g = groups[gi]
            val msgs = listGroupMessages(context, g.id).toMutableList()
            var msgChanged = false
            // 优先精确匹配绑定到同一 sessionId 的气泡（避免群聊多任务并发时覆盖同 Bot 的其他并发任务气泡）
            val exactIdx = msgs.indexOfLast { m ->
                !m.id.startsWith("gw_") && !m.id.startsWith("work_") && m.senderId == botId && m.linkedSessionId == sessionId
            }
            val targetIdx = if (exactIdx >= 0) {
                exactIdx
            } else {
                msgs.indexOfLast { m ->
                    !m.id.startsWith("gw_") && !m.id.startsWith("work_") && m.senderId == botId && m.linkedSessionId.isBlank() && (
                        m.status == "thinking" ||
                            m.text.contains("仍在后台持续执行中") ||
                            m.text.contains("正在自己的工位会话")
                        )
                }
            }
            if (targetIdx >= 0) {
                val target = msgs[targetIdx]
                val isIncomplete = target.status == "thinking" ||
                    target.text.contains("仍在后台持续执行中") ||
                    target.text.contains("正在自己的工位会话") ||
                    (target.linkedSessionId == sessionId && (target.text != cleanReply || (target.reasoning.isBlank() && reasoning.isNotBlank())))
                if (isIncomplete) {
                    val needsInputQ = extractNeedsInput(cleanReply)
                    msgs[targetIdx] = target.copy(
                        text = cleanReply,
                        reasoning = reasoning.ifBlank { target.reasoning },
                        startedAt = if (startedAt > 0L) startedAt else target.startedAt,
                        tools = tools.ifEmpty { target.tools },
                        linkedSessionId = sessionId,
                        status = if (needsInputQ != null) "needs_input" else "done",
                        at = if (completedAt > 0L) completedAt else maxOf(target.at, System.currentTimeMillis()),
                    )
                    msgChanged = true
                }
            }
            if (msgChanged) {
                saveGroupMessagesInternal(context, g.id, msgs)
                val last = msgs.lastOrNull()
                if (last != null && last.senderId != "user") {
                    val preview = "[${last.senderJobTitle} ${last.senderName}]: ${last.text.replace("\n", " ").take(60)}"
                    groups[gi] = g.copy(lastText = preview, lastAt = last.at)
                }
                anyGroupUpdated = true
            }
        }
        if (anyGroupUpdated) {
            saveGroups(context, groups)
        }
    }

    /**
     * 对标 Rakazo `run-instructions.ts` + Open Duet `backend/app/team/prompt.py`：
     * 组装九层结构化 Bot 上下文提示词：
     * 1. `<bot_identity>` (名字、职位、思考档位、工作区模式、审批边界、对外名片)
     * 2. `<standing_instructions>` (`SOUL.md` + `AGENTS.md` 多段式常驻 SOP)
     * 3. `<tool_boundary>` (每个 Bot 独立的工具权限边界 + `consult`/`plan` 咨询只读围栏)
     * 4. `<team_protocol>` (Open Duet Lead vs Member 协作规范、`rework=true` 返工、末行 `NEEDS INPUT: <question>` 挂起规范)
     * 5. `<bound_skills>` (启用中的六段式 `SKILL.md` 作战剧本，支持单轮 `/skill` 优先挂载)
     * 6. `<scratchpad_open>` (未完结工作便签)
     * 7. `<bot_memories>` (未归档的核心 `MEMORY.md` 事实)
     * 8. `<memory_topics_index>` & `<recalled_topic_memories>` (专题记忆索引与基于 `aliases` 的自动按需召回)
     * 9. `<bot_directory>` (车队中其他队友的公开名片 `publicDescription`)
     */
    fun buildBotRolePrompt(
        bot: BotFriend,
        userMessage: String,
        groupContextSummary: String = "",
        peerBots: List<BotFriend> = emptyList(),
        mode: String = "work", // "work" | "consult"
        attachedSkillIds: List<String> = emptyList(),
    ): String =
        buildString {
            val isConsult = mode.equals("consult", ignoreCase = true) || bot.approvalMode == "plan"
            val effectiveDisabled = if (isConsult) {
                (bot.disabledTools + listOf("write_file", "edit_file", "git_commit", "desktop_act")).distinct()
            } else {
                bot.disabledTools
            }

            append("<bot_identity>\n")
            append("Name: ").append(bot.name).append("\n")
            append("Job Title: ").append(bot.jobTitle).append(" (").append(bot.department).append(")\n")
            append("Lifecycle: ").append(bot.lifecycle.label)
                .append(" · Thinking Effort: ").append(bot.thinkingEffort)
                .append(" · Workspace Isolation: ").append(bot.isolationMode)
                .append(" · Approval Boundary: ").append(bot.approvalMode)
                .append(" · Delegation Mode: ").append(if (isConsult) "consult (read-only second opinion)" else "work (execution)").append("\n")
            if (bot.publicDescription.isNotBlank()) {
                append("Public Teammate Description (How other bots see you): ").append(bot.publicDescription).append("\n")
            }
            append("</bot_identity>\n\n")

            if (bot.rolePrompt.isNotBlank()) {
                append("<standing_instructions>\n")
                append(bot.rolePrompt.trim()).append("\n")
                append("</standing_instructions>\n\n")
            }

            append("<tool_boundary>\n")
            if (isConsult) {
                append("CONSULT / PLAN-ONLY MODE ACTIVE: You are being consulted for a read-only second opinion, root-cause diagnosis, or design critique. Do NOT modify files, run mutating commands, or commit changes. Inspect files/logs and return concrete findings with file:line citations.\n")
            } else if (effectiveDisabled.isEmpty()) {
                append("All standard engineering tools (read/write file, shell, git worktree) are enabled for your role.\n")
            } else {
                append("STRICT ROLE BOUNDARY: Tools disabled for your role: [")
                    .append(effectiveDisabled.joinToString(", "))
                    .append("]. Operate strictly within your read-only analytical/review boundary; do NOT directly modify repository files.\n")
            }
            append("</tool_boundary>\n\n")

            append("<team_protocol>\n")
            if (bot.lifecycle == BotLifecycle.MANAGEMENT || bot.manageOtherBots) {
                append("You can act as a Team Lead:\n")
                append("- Use mode=\"consult\" when asking a specialist for a quick read-only second opinion (@情报官, @审校官).\n")
                append("- Use mode=\"work\" when delegating implementation or verification ownership (@代码主程, @UI主程, @运维官).\n")
                append("- If a specialist's output misses acceptance criteria, send them an in-thread rework instruction (rework=true) stating the exact gap.\n")
                append("- If blocked by an unapproved destructive action or critical ambiguity, end your response with `NEEDS INPUT: <one concrete question>` on the very last line.\n")
            } else {
                append("You are a Specialist Team Member:\n")
                append("- Stay within your assigned scope and approval boundary.\n")
                append("- If blocked by missing requirements or an unapproved destructive action, end your response with `NEEDS INPUT: <one concrete question>` on the very last line.\n")
            }
            append("</team_protocol>\n\n")

            val activeSkills = bot.skills.filter { sk ->
                sk.enabled || attachedSkillIds.any { it == sk.id || it == sk.name }
            }
            if (activeSkills.isNotEmpty()) {
                append("<bound_skills>\n")
                activeSkills.forEach { sk ->
                    val pinnedTag = if (attachedSkillIds.any { it == sk.id || it == sk.name }) " [USER-ATTACHED FOR THIS TURN — PRIORITIZE]" else ""
                    append("### Skill: ").append(sk.name).append(" (`").append(sk.id).append("`)").append(pinnedTag).append("\n")
                    append("- Description: ").append(sk.description).append("\n")
                    if (sk.whenToUse.isNotBlank()) {
                        append("- When to use: ").append(sk.whenToUse).append("\n")
                    }
                    append("- Playbook:\n").append(sk.playbook).append("\n")
                }
                append("</bound_skills>\n\n")
            }

            val openItems = bot.openWork.filter { it.status != "done" }
            if (openItems.isNotEmpty()) {
                append("<scratchpad_open>\n")
                openItems.take(15).forEach { w ->
                    append("- [").append(w.status).append("] ").append(w.title).append("\n")
                }
                append("</scratchpad_open>\n\n")
            }

            val coreMems = bot.memories.filter { !it.archived && it.topic.isBlank() }
            if (coreMems.isNotEmpty()) {
                append("<bot_memories>\n")
                coreMems.take(MEMORY_BUDGET_MAX_ACTIVE).forEach { m ->
                    append("- [").append(m.category).append("] ").append(m.fact).append("\n")
                }
                append("</bot_memories>\n\n")
            }

            val topicMems = bot.memories.filter { !it.archived && it.topic.isNotBlank() }
            if (topicMems.isNotEmpty()) {
                append("<memory_topics_index>\n")
                topicMems.forEach { tm ->
                    val aliasInfo = if (tm.aliases.isEmpty()) "" else " (aliases: ${tm.aliases.joinToString(", ")})"
                    append("- topic `").append(tm.topic).append("`").append(aliasInfo).append("\n")
                }
                append("</memory_topics_index>\n\n")

                val qLower = userMessage.lowercase()
                val recalled = topicMems.filter { tm ->
                    qLower.contains(tm.topic.lowercase()) || tm.aliases.any { a -> a.isNotBlank() && qLower.contains(a.lowercase()) }
                }
                if (recalled.isNotEmpty()) {
                    append("<recalled_topic_memories>\n")
                    recalled.forEach { tm ->
                        append("### Topic: ").append(tm.topic).append("\n").append(tm.fact).append("\n")
                    }
                    append("</recalled_topic_memories>\n\n")
                }
            }

            val peers = peerBots.filter { it.id != bot.id && !it.archived }
            if (peers.isNotEmpty()) {
                append("<bot_directory>\n")
                peers.forEach { p ->
                    val desc = p.publicDescription.ifBlank { p.rolePrompt.lineSequence().firstOrNull()?.take(60).orEmpty() }
                    append("- **").append(p.name).append("** (`").append(p.jobTitle).append("`, engine: `").append(p.engine).append("`, approval: `").append(p.approvalMode).append("`): ").append(desc).append("\n")
                }
                append("</bot_directory>\n\n")
            }

            if (groupContextSummary.isNotBlank()) {
                append("【群聊协同上下文】\n").append(groupContextSummary).append("\n\n")
            }
            append("[User Request]\n").append(userMessage)
        }

    /**
     * 让单个 Bot 在其绑定的底层 Agent 会话中执行任务，并返回 (sessionId, replyText)。
     */
    suspend fun executeBotTurn(
        context: Context,
        bot: BotFriend,
        userMessage: String,
        groupContextSummary: String = "",
        mode: String = "work",
        attachedSkillIds: List<String> = emptyList(),
        files: List<JSONObject> = emptyList(),
        forceNewSession: Boolean = (bot.lifecycle == BotLifecycle.PROJECT && !userMessage.startsWith("[Rework")),
        onSessionLinked: ((String) -> Unit)? = null,
        onTurnUpdate: ((RemoteApi.Message) -> Unit)? = null,
        onProgress: ((String) -> Unit)? = null,
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        val liveBot = getBot(context, bot.id) ?: bot
        val allPeers = listBots(context, includeArchived = false)
        val hub = RemoteStore.hub(context)
        val token = RemoteStore.token(context)
        val engine = BotModelPolicy.requireEngine(liveBot.engine)
        var sessionId = try {
            ensureBotRemoteSession(context, liveBot, userMessage, forceNew = forceNewSession)
        } catch (e: Exception) {
            return@withContext "" to "（建立 ${agentDisplayName(engine)} 会话失败：${redactSecrets(e.message.orEmpty()).first.take(600)}）"
        }
        onSessionLinked?.invoke(sessionId)

        val preMsgs = runCatching {
            RemoteApi.messages(hub, token, liveBot.device.ifBlank { "pc" }, engine, sessionId)
        }.getOrDefault(emptyList())
        val preLastMsgId = preMsgs.lastOrNull()?.id.orEmpty()
        val preLastAssistant = preMsgs.lastOrNull { it.role == "assistant" }
        val preLastAssistantId = preLastAssistant?.id.orEmpty()
        val preLastAssistantAt = preLastAssistant?.at ?: 0L
        val preLastAssistantLen = preLastAssistant?.text?.length ?: 0
        val turnStartMs = System.currentTimeMillis()

        val fullPrompt = buildBotRolePrompt(
            bot = liveBot,
            userMessage = userMessage,
            groupContextSummary = groupContextSummary,
            peerBots = allPeers,
            mode = mode,
            attachedSkillIds = attachedSkillIds,
        )

        val promptResp = runCatching {
            RemoteApi.prompt(
                hub = hub,
                token = token,
                device = liveBot.device.ifBlank { "pc" },
                session = sessionId,
                text = fullPrompt,
                agent = engine,
                model = liveBot.model,
                cwd = liveBot.cwd,
                files = files,
                botId = liveBot.id,
                thinkingEffort = liveBot.thinkingEffort,
                isolationMode = liveBot.isolationMode,
            )
        }.getOrElse { e ->
            return@withContext sessionId to "（底层会话发送失败：${e.message}，可点击下方按钮进入底层会话排查）"
        }

        val resolvedSid = promptResp.optString("id").trim()
        if (resolvedSid.isNotBlank() && resolvedSid != sessionId) {
            sessionId = resolvedSid
            bindSessionToBot(context, liveBot.id, sessionId, userMessage.take(40))
            onSessionLinked?.invoke(sessionId)
        }

        var latestAssistantText = ""
        var lastReportedProgress = ""
        val deadline = System.currentTimeMillis() + 180_000L
        while (System.currentTimeMillis() < deadline) {
            delay(1500L)
            val msgs = runCatching {
                RemoteApi.messages(hub, token, liveBot.device.ifBlank { "pc" }, engine, sessionId)
            }.getOrDefault(emptyList())
            if (msgs.isNotEmpty()) {
                RemoteSessionCache.putCachedMessages(context, sessionId, msgs)
            }
            val lastAssistant = msgs.lastOrNull { it.role == "assistant" }
            val isNewTurnAssistant = lastAssistant != null && (
                preMsgs.isEmpty() ||
                    lastAssistant.id != preLastAssistantId ||
                    lastAssistant.at == 0L ||
                    lastAssistant.started >= turnStartMs - 5000L ||
                    (preLastAssistantAt > 0L && lastAssistant.at > preLastAssistantAt) ||
                    lastAssistant.text.length != preLastAssistantLen ||
                    (msgs.lastOrNull()?.id != preLastMsgId && msgs.lastOrNull()?.role == "assistant")
                )
            if (isNewTurnAssistant) {
                onTurnUpdate?.invoke(lastAssistant)
                if (lastAssistant.at > 0L) {
                    val cleanT = lastAssistant.text.trim()
                    val isLeakedReasoning = cleanT.startsWith("🔧 正在调用工具") ||
                        cleanT.startsWith("🔧 已调用") ||
                        (lastAssistant.reasoning.isNotBlank() && lastAssistant.reasoning.contains(cleanT))
                    if (cleanT.isNotBlank() && !isLeakedReasoning) {
                        latestAssistantText = cleanT
                        break
                    } else if (lastAssistant.reasoning.isNotBlank() || lastAssistant.tools.isNotEmpty()) {
                        // 这一轮仅有思考或工具调用，尚未产出答复正文；绝不能拿 reasoning 冒充回答正文
                        break
                    }
                } else {
                    val toolLine = lastAssistant.tools.lastOrNull()?.let { t ->
                        "🔧 已调用 ${lastAssistant.tools.size} 步工具 · ${t.title.ifBlank { t.name }}"
                    }.orEmpty()
                    val reasoningLine = lastAssistant.reasoning
                        .lineSequence()
                        .map { it.trim() }
                        .lastOrNull { it.isNotBlank() && !it.startsWith("🔧") }
                        ?.take(120)
                        .orEmpty()
                    val textPreview = lastAssistant.text.trim().let { full ->
                        if (full.length <= 360) full else full.take(360) + "…"
                    }
                    val progressSnippet = buildString {
                        if (toolLine.isNotBlank()) appendLine(toolLine)
                        if (reasoningLine.isNotBlank()) appendLine("💭 $reasoningLine")
                        if (textPreview.isNotBlank()) append(textPreview)
                    }.trim()
                    if (progressSnippet.isNotBlank() && progressSnippet != lastReportedProgress) {
                        lastReportedProgress = progressSnippet
                        onProgress?.invoke(progressSnippet)
                    }
                }
            }
        }

        val finalReply = if (latestAssistantText.isNotBlank()) {
            latestAssistantText
        } else {
            // 仅有后台思维链/工具操作，无答复正文；保持为空让 UI 仅展示 ThinkingBlock 折叠卡片，绝不把工具调用日志冒充正文
            ""
        }
        bindSessionToBot(context, liveBot.id, sessionId, finalReply.lineSequence().firstOrNull()?.take(40))
        sessionId to finalReply
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 聊天输入框草稿箱（支持私聊 Bot / 远程会话 / 群聊切走后再回来甚至重启 App 自动恢复未发送文本、待发附件与挂载技能）
    // ─────────────────────────────────────────────────────────────────────────
    private val draftTextCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val draftAttachmentsCache = java.util.concurrent.ConcurrentHashMap<String, List<RemoteAttachment>>()
    private val draftSkillsCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    fun getDraftText(context: Context, key: String): String {
        draftTextCache[key]?.let { return it }
        val saved = prefs(context).getString("draft_text_$key", "").orEmpty()
        draftTextCache[key] = saved
        return saved
    }

    fun setDraftText(context: Context, key: String, text: String) {
        val prev = draftTextCache[key].orEmpty()
        if (prev == text) return
        draftTextCache[key] = text
        val editor = prefs(context).edit()
        if (text.isBlank()) {
            editor.remove("draft_text_$key")
        } else {
            editor.putString("draft_text_$key", text)
        }
        editor.apply()
        if (prev.isBlank() != text.isBlank()) {
            _versionFlow.value++
        }
    }

    fun getDraftAttachments(key: String): List<RemoteAttachment> =
        draftAttachmentsCache[key].orEmpty()

    fun setDraftAttachments(key: String, attachments: List<RemoteAttachment>) {
        val prevEmpty = draftAttachmentsCache[key].isNullOrEmpty()
        if (attachments.isEmpty()) {
            draftAttachmentsCache.remove(key)
        } else {
            draftAttachmentsCache[key] = attachments
        }
        if (prevEmpty != attachments.isEmpty()) {
            _versionFlow.value++
        }
    }

    fun getDraftSkills(key: String): Set<String> =
        draftSkillsCache[key].orEmpty()

    fun setDraftSkills(key: String, skills: Set<String>) {
        if (skills.isEmpty()) {
            draftSkillsCache.remove(key)
        } else {
            draftSkillsCache[key] = skills
        }
    }

    fun clearDraft(context: Context, key: String) {
        val hadDraft = !draftTextCache[key].isNullOrBlank() || !draftAttachmentsCache[key].isNullOrEmpty()
        draftTextCache.remove(key)
        draftAttachmentsCache.remove(key)
        draftSkillsCache.remove(key)
        prefs(context).edit().remove("draft_text_$key").apply()
        if (hadDraft) {
            _versionFlow.value++
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 忙时指令排队与打断/并发模式管理（支持上一条指令完成前默认排队顺延，并可随时切换为打断或并发）
    // ─────────────────────────────────────────────────────────────────────────
    private val promptQueueMap = java.util.concurrent.ConcurrentHashMap<String, List<QueuedBotPrompt>>()
    private val busyModeMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Synchronized
    fun getQueuedPrompts(key: String): List<QueuedBotPrompt> =
        promptQueueMap[key].orEmpty()

    @Synchronized
    fun enqueuePrompt(key: String, item: QueuedBotPrompt): List<QueuedBotPrompt> {
        val updated = promptQueueMap[key].orEmpty() + item
        promptQueueMap[key] = updated
        _versionFlow.value++
        return updated
    }

    @Synchronized
    fun removeQueuedPrompt(key: String, itemId: String): QueuedBotPrompt? {
        val cur = promptQueueMap[key].orEmpty()
        val target = cur.firstOrNull { it.id == itemId } ?: return null
        val updated = cur.filterNot { it.id == itemId }
        if (updated.isEmpty()) promptQueueMap.remove(key) else promptQueueMap[key] = updated
        _versionFlow.value++
        return target
    }

    @Synchronized
    fun popNextQueuedPrompt(key: String): QueuedBotPrompt? {
        val cur = promptQueueMap[key].orEmpty()
        val first = cur.firstOrNull() ?: return null
        val rest = cur.drop(1)
        if (rest.isEmpty()) promptQueueMap.remove(key) else promptQueueMap[key] = rest
        _versionFlow.value++
        return first
    }

    /**
     * 获取当前会话/群聊在忙时发送消息的策略：
     * - 私聊默认 `"queue"`（排队顺延），可切为 `"interrupt"`（立即打断）
     * - 群聊默认 `"parallel"`（多任务并发执行），可切为 `"queue"`（排队顺延）或 `"interrupt"`（立即打断）
     */
    fun getBusySendMode(key: String, defaultMode: String = "queue"): String =
        busyModeMap[key] ?: defaultMode

    fun setBusySendMode(key: String, mode: String) {
        busyModeMap[key] = mode
        _versionFlow.value++
    }
}
