package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.i18n.AppLanguage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.wangzan101.vibelink.ui.components.ButtonKind
import com.wangzan101.vibelink.ui.components.KeyboardDismissArea
import com.wangzan101.vibelink.ui.components.VibeLinkButton
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import com.wangzan101.vibelink.confirm.ConfirmationNotifier
import com.wangzan101.vibelink.memory.MemoryRuntime
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.vibelink.cap.web.WebHandover
import com.wangzan101.vibelink.cap.web.WebSites
import com.wangzan101.vibelink.browser.UserBrowser
import com.wangzan101.vibelink.BuildConfig
import com.wangzan101.vibelink.remote.RemoteScreen
import com.wangzan101.vibelink.VibeLinkApp
import com.wangzan101.vibelink.about.Diagnostics
import com.wangzan101.vibelink.about.DiagnosticsCollector
import com.wangzan101.vibelink.about.VibeLinkLinks
import com.wangzan101.vibelink.about.QrImage
import com.wangzan101.vibelink.chat.ConversationStore
import com.wangzan101.vibelink.record.RunStore
import com.wangzan101.vibelink.task.TaskService
import com.wangzan101.vibelink.core.knowledge.GuideCatalog
import com.wangzan101.vibelink.settings.Prerequisites
import com.wangzan101.vibelink.settings.ProviderStore
import com.wangzan101.vibelink.settings.SystemStatusMonitor
import com.wangzan101.vibelink.cap.phone.PhoneServices
import com.wangzan101.vibelink.cap.phone.setup.DeveloperSettings
import com.wangzan101.vibelink.cap.phone.setup.PairingNotifier
import com.wangzan101.vibelink.core.shell.BuiltinShellStatus
import com.wangzan101.vibelink.ui.theme.VibeLinkTheme
import com.wangzan101.vibelink.ui.theme.animationsOff

class MainActivity : ComponentActivity() {
    private lateinit var monitor: SystemStatusMonitor
    private var requestedConversation by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // 用户选过「不再询问」时系统不再弹窗,只能去设置里打开
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
        monitor.refresh()
    }

    // 安卓 12 及以前套上 App 里选的语言;13 起系统自己套
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        // 系统相机扫到 vibelink://pair 拉起来的：交给远程会话页弹确认（不在这里做任何网络请求）
        if (savedInstanceState == null) com.wangzan101.vibelink.remote.PairInbox.offer(intent?.dataString)
        monitor = SystemStatusMonitor(this, PhoneServices.of(this).shellControl)
        val store = ProviderStore.get(this)
        setContent {
            VibeLinkTheme(reduceMotion = animationsOff(this)) {
                // 在输入框外面点一下或拖动就收起键盘,全 App 统一
                KeyboardDismissArea(Modifier.fillMaxSize()) {
                    VibeLinkRoot(store, monitor, ::perform, requestedConversation) { requestedConversation = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        com.wangzan101.vibelink.remote.PairInbox.offer(intent.dataString)
        requestVpnConsentIfAsked(intent)
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
        // 每次打开都悄悄查一次新版本(两分钟内连着打开的只查一次),失败不吭声
        (application as VibeLinkApp).updates.autoCheckInBackground()
        requestVpnConsentIfAsked(intent)
    }

    /** 调试：adb shell am start -n com.wangzan101.vibelink/.ui.MainActivity --ez vpn_prep true */
    private fun requestVpnConsentIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra("vpn_prep", false) != true) return
        val prep = android.net.VpnService.prepare(this)
        if (prep == null) Log.i("vibelink-vpn", "already authorized")
        else runCatching { startActivity(prep) }
            .onFailure { Log.e("vibelink-vpn", "consent failed", it) }
    }

    override fun onResume() {
        super.onResume()
        monitor.refresh()
    }

    override fun onStop() {
        monitor.stop()
        super.onStop()
    }

    private val phone get() = PhoneServices.of(this)

    private fun perform(fix: Fix) {
        when (fix) {
            Fix.OpenAccessibility -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Fix.OpenAppInfo -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            Fix.AllowNotifications -> {
                val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsGrant) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
            }
            Fix.OpenAttentionChannel -> openChannelSettings(TaskService.CHANNEL_ATTENTION)
            Fix.OpenResultChannel -> openChannelSettings(TaskService.CHANNEL_RESULT)
            Fix.OpenProgressChannel -> openChannelSettings(TaskService.CHANNEL_PROGRESS)
            Fix.OpenPairingChannel -> openChannelSettings(PairingNotifier.ensureChannel(this).id)
            Fix.AddModel -> Unit // 设置页自己处理
            // 换页由 VibeLinkRoot 处理
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> Unit
            Fix.RetryPhoneHelper -> phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellControl.retry()
            }
            Fix.UnpairPhoneHelper -> phone.pairing.scope.launch { phone.recovery.unpair() }
            Fix.OpenAboutPhone -> openFirst(DeveloperSettings.aboutPhone())
            Fix.OpenDeveloperOptions -> openFirst(DeveloperSettings.developerOptionsPage())
            Fix.OpenUsbDebugging -> openFirst(DeveloperSettings.usbDebuggingPage())
            Fix.OpenWirelessDebugging -> openFirst(DeveloperSettings.wirelessDebuggingPage())
            Fix.OpenWifi -> openFirst(DeveloperSettings.wifiPage())
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openNotificationSettings() = open(appNotificationSettings())

    /** 直接打开某个通知频道的设置页;系统没有这个页面时退回应用通知总页。 */
    private fun openChannelSettings(channelId: String) {
        val channelPage = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        firstThatStarts(listOf(channelPage, appNotificationSettings())) { intent ->
            if (intent.resolveActivity(packageManager) == null) false
            else try { startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
        }
    }

    /** 依次试,第一个能打开的就用:各家设置页不一定都认同一个入口。 */
    private fun openFirst(intents: List<Intent>) {
        val hit = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        open(hit)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
        }
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        private const val TAG = "VibeLinkMain"
    }
}

/** 用系统浏览器打开外部链接(GitHub、X 等);没有浏览器时提示一句。 */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, AboutCopy.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}

/** 依次试 [candidates],第一个 [start] 成功的就停;都不行返回 false。系统设置页各家不一定都有,靠这个退回通用页。 */
internal fun <T> firstThatStarts(candidates: List<T>, start: (T) -> Boolean): Boolean = candidates.any(start)

/**
 * 页面切换:对话页是根,侧栏和设置等入口把页面压进 [NavStack],返回键和页面上的返回按钮都走 [NavStack.back]。
 * 各页面只通过回调进出,不知道彼此。
 */
@Composable
private fun VibeLinkRoot(store: ProviderStore, monitor: SystemStatusMonitor, activityFix: (Fix) -> Unit,
    requestedConversation: String?, onConversationOpened: () -> Unit) {
    val nav = rememberNavStack()
    // 从设置页「重新配对」进来时先回到配对一步
    var setupRepair by rememberSaveable { mutableStateOf(false) }
    // 首次上手正在走哪一步;为空时不在上手流程里。配置模型页从哪儿来就回哪儿去
    var onboarding by rememberSaveable { mutableStateOf<OnboardingStep?>(null) }
    val onFix: (Fix) -> Unit = { fix ->
        when (fix) {
            Fix.AddModel -> nav.push(Route.Models)
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> {
                setupRepair = fix == Fix.RepairPhoneHelper
                nav.push(Route.PhoneSetup)
            }
            else -> activityFix(fix)
        }
    }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAgent by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSession by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceLegacyText by rememberSaveable { mutableStateOf<String?>(null) }
    val status by monitor.status.collectAsState()
    val roles by store.roles.collectAsState()
    val hasMain = roles.main != null
    val check = Prerequisites.check(status, hasMain)
    val context = LocalContext.current
    val app = context.applicationContext as VibeLinkApp
    // 查到的新版本:侧栏、设置首页、关于页的红点都看它
    val updateState by app.updates.state.collectAsState()
    val vibelinkRunning by app.chat.running.collectAsState()
    LaunchedEffect(requestedConversation) {
        if (requestedConversation != null) {
            app.chat.openConversation(requestedConversation)
            nav.openChat()
            onConversationOpened()
        }
    }
    val release = remember { Build.VERSION.RELEASE }
    // 结果卡片上点了「看看」:打开技能分段
    val memoryRuntime = remember { MemoryRuntime.of(context) }
    val phoneSetup = remember { PhoneServices.of(context).setupPrompt }
    val openMemory by memoryRuntime.openRequest.collectAsState()
    LaunchedEffect(openMemory) { if (openMemory != null) nav.push(Route.Memory) }
    // 扫了 vibelink 配对二维码：打开远程会话页，那里弹「连接到 xx？」
    val pairLink by com.wangzan101.vibelink.remote.PairInbox.pending.collectAsState()
    LaunchedEffect(pairLink) { if (pairLink != null) nav.push(Route.Remote) }
    // 第一次打开:欢迎 → 配置模型 → 手机操作;配过模型的跳过前两步,手机操作已就绪或看过引导的跳过最后一步
    val onboardingState = remember { OnboardingState(context) }
    fun onboardingInputs() = Onboarding.Inputs(
        hasMainModel = store.roles.value.main != null,
        modelSetupDone = onboardingState.modelSetupDone(),
        phoneNeeded = status.builtinShell != BuiltinShellStatus.Running && status.sdkInt >= Prerequisites.MIN_SDK,
        phoneGuideSeen = phoneSetup.guideSeen(),
    )
    fun goOnboarding(step: OnboardingStep?) {
        onboarding = step
        when (step) {
            // 上手的每一步都直接放在对话页上面一层,做完回到对话页
            OnboardingStep.Welcome -> nav.show(Route.Welcome)
            OnboardingStep.Model -> nav.show(Route.ModelSetup)
            OnboardingStep.Phone -> { setupRepair = false; nav.show(Route.PhoneSetup) }
            null -> nav.openChat()
        }
    }
    LaunchedEffect(Unit) {
        if (onboarding == null) Onboarding.start(onboardingInputs())?.let(::goOnboarding)
    }
    val phoneSetupNeeded by phoneSetup.needed.collectAsState()
    // 页面有自己的返回处理时(设置页里的子页、侧栏)由它们先接;这里只管退回上一页
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val back: () -> Unit = { nav.back() }
    // 聊天页离开组合树时保留列表位置和输入内容，返回设置页等子页面后原样恢复。
    val screenStates = rememberSaveableStateHolder()
    val hostContext = LocalContext.current
    LaunchedEffect(nav.current) {
        if (nav.current == Route.Home) {
            app.updates.autoCheckInBackground()
        }
    }
    when (nav.current) {
        Route.Home -> HomeScreen(
            onOpenVibeLink = { nav.openChat() },
            onOpenAgent = { agent ->
                selectedAgent = agent
                pendingSession = null
                nav.push(Route.AgentChat)
            },
            onOpenAgentSession = { agent, session ->
                selectedAgent = agent
                pendingSession = session
                nav.push(Route.AgentChat)
            },
            onOpenProfile = { agent ->
                selectedAgent = agent
                nav.push(Route.AgentProfile)
            },
            onOpenGroup = { groupId ->
                selectedGroup = groupId
                nav.push(Route.TeamGroupChat)
            },
            onOpenSettings = { nav.push(Route.Settings) },
            onOpenCindy = {
                runCatching {
                    hostContext.startActivity(
                        android.content.Intent()
                            .setClassName(hostContext.packageName, "com.xd.cindycn.MainActivity")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }.onFailure {
                    Toast.makeText(hostContext, "打开 Cindy 失败: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenClash = {
                runCatching {
                    hostContext.startActivity(
                        android.content.Intent(
                            hostContext,
                            com.github.kr328.clash.MainActivity::class.java,
                        )
                    )
                }
            },
            updateAvailable = AboutCopy.drawerDot(updateState),
            vibelinkRunning = vibelinkRunning,
        )
        Route.AgentChat -> AgentChatScreen(
            agentId = selectedAgent ?: "opencode",
            onBack = back,
            onOpenProfile = { nav.push(Route.AgentProfile) },
            initialSessionId = pendingSession,
            onSwitchAgent = { agent, session ->
                selectedAgent = agent
                pendingSession = session
            },
        )
        Route.AgentProfile -> AgentProfileScreen(
            agentId = selectedAgent ?: VIBELINK_AGENT_ID,
            onBack = back,
            onOpenChat = {
                pendingSession = null
                if (selectedAgent == VIBELINK_AGENT_ID) nav.openChat() else nav.push(Route.AgentChat)
            },
            onOpenSession = { session ->
                pendingSession = session
                nav.push(Route.AgentChat)
            },
            onOpenEngineSession = { engine, session ->
                selectedAgent = engine
                pendingSession = session.takeIf { it.isNotBlank() }
                nav.push(Route.AgentChat)
            },
            onOpenSettings = { nav.push(Route.Settings) },
        )
        Route.TeamGroupChat -> TeamGroupChatScreen(
            groupId = selectedGroup ?: "group_vibelink_core",
            onBack = back,
            onOpenBotProfile = { botId ->
                selectedAgent = botId
                nav.push(Route.AgentProfile)
            },
            onInspectSession = { engine, sessionId ->
                selectedAgent = engine
                pendingSession = sessionId.takeIf { it.isNotBlank() }
                nav.push(Route.AgentChat)
            },
        )
        Route.PhoneSetup -> PhoneSetupScreen(status, setupRepair, onFix, onboarding = onboarding == OnboardingStep.Phone) {
            phoneSetup.markGuideSeen()
            if (onboarding == OnboardingStep.Phone) goOnboarding(null) else nav.back()
        }
        Route.Welcome -> WelcomeScreen(
            onStart = { goOnboarding(OnboardingStep.Model) },
            version = BuildConfig.VERSION_NAME,
            update = updateState,
            onUpdate = { updateState.update?.let { com.wangzan101.vibelink.about.ApkUpdater.startWithToasts(context, it.downloadUrl) } },
            onRemote = { nav.push(Route.Remote) },
            onBetaActivated = {
                onboardingState.markModelSetupDone()
                goOnboarding(Onboarding.next(OnboardingStep.Model, onboardingInputs()))
            },
        )
        Route.ModelSetup -> {
            val inOnboarding = onboarding == OnboardingStep.Model
            val updateNow: () -> Unit = { updateState.update?.let { com.wangzan101.vibelink.about.ApkUpdater.startWithToasts(context, it.downloadUrl) } }
            ModelSetupScreen(
                store, onboarding = inOnboarding,
                header = if (inOnboarding) {
                    {
                        Column {
                            UpdateBanner(BuildConfig.VERSION_NAME, updateState, updateNow, Modifier.padding(bottom = 8.dp))
                            VibeLinkButton(
                                "打开远程会话（PC 上的会话）",
                                { nav.push(Route.Remote) },
                                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                kind = ButtonKind.Tonal,
                            )
                        }
                    }
                } else null,
                onBack = { if (inOnboarding) goOnboarding(OnboardingStep.Welcome) else nav.back() },
                onDone = {
                    if (inOnboarding) {
                        onboardingState.markModelSetupDone()
                        goOnboarding(Onboarding.next(OnboardingStep.Model, onboardingInputs()))
                    } else nav.back()
                },
            )
        }
        Route.Remote -> RemoteScreen(onBack = back)
        Route.Proxy -> ProxyScreen(onBack = back)
        Route.Chat -> screenStates.SaveableStateProvider(Route.Chat.name) {
            // 对话页重新看得见(回到前台、从别的页面回来):补上别处存进来的轮次,接上别处开始的任务
            LifecycleStartEffect(Unit) {
                app.chat.refresh()
                onStopOrDispose {}
            }
            ChatScreen(
                vm = app.chat,
                check = check,
                frames = app.taskState.frames,
                webOpen = app.tasks.webScreenOpen,
                reduceMotion = VibeLinkTheme.reduceMotion,
                onBack = back,
                onOpenAgentProfile = {
                    selectedAgent = VIBELINK_AGENT_ID
                    nav.push(Route.AgentProfile)
                },
                conversations = { ConversationStore.shared(context).list() },
                onSwitchConversation = { app.chat.openConversation(it) },
                openSettings = { nav.push(Route.Settings) },
                openRun = { runId = it; nav.push(Route.Run) },
                phoneSetupNeeded = phoneSetupNeeded && status.builtinShell != BuiltinShellStatus.Running,
                openPhoneSetup = { onFix(Fix.OpenPhoneSetup) },
                loadSource = { id ->
                    withContext(Dispatchers.IO) { evidenceSource(RunStore(context).events(id)) { ConfirmationNotifier.appLabel(context, it) } }
                },
                openSource = { id, legacy ->
                    sourceRunId = id
                    sourceLegacyText = legacy
                    nav.push(Route.Source)
                },
            )
        }
        Route.Settings -> {
            val registry = remember { WebSites.registry(context) }
            val sites by registry.entries.collectAsState()
            val webSites = WebSitesState(
                sites = sites,
                onOpenLogin = { WebHandover.showLogin(context, it) },
                onLogout = { WebSites.login(context).logout(it) },
                onLogoutAll = { WebSites.logoutAll(context) },
            )
            val autonomous by app.approvals.autonomous.collectAsState()
            SettingsScreen(store, status, release, onFix, onBack = back, onOpenMemory = { nav.push(Route.Memory) },
                onOpenModels = { nav.push(Route.Models) }, webSites = webSites,
                autonomous = autonomous, onAutonomous = app.approvalControl::setAutonomous,
                updateVersion = updateState.update?.version, onOpenAbout = { nav.push(Route.About) },
                onOpenPlans = { nav.push(Route.Plans) },
                onOpenBrowser = { nav.push(Route.Browser) })
        }
        Route.Models -> ModelsScreen(store, onBack = back)
        Route.About -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME, build = BuildConfig.GIT_HASH, beta = BuildConfig.BETA,
            update = updateState,
            issueUrl = remember {
                VibeLinkLinks.issueUrl(
                    VibeLinkLinks.Device(
                        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                        BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH,
                    ),
                )
            },
            onCheck = app.updates::checkInBackground,
            onOpenUrl = { openInBrowser(context, it) },
            onSaveQr = { QrImage.saveToPictures(context) },
            // 上游的「让 VibeLink 替我发微信给作者入群」已移除：融合版不应替用户联系上游作者。
            onAskVibeLinkToJoin = {},
            onOpenLicense = { nav.push(Route.License) },
            onBack = back,
            diagnostics = {
                Diagnostics.render(DiagnosticsCollector.collect(context, status))
            },
        )
        Route.License -> LicensesScreen(onBack = back)
        Route.Browser -> BrowserScreen(remember { UserBrowser.of(context) }, onBack = back)
        Route.Memory -> {
            // 第一次取记忆要读盘解密,放到后台线程;页面的协程跟着这一屏走
            val vmScope = rememberCoroutineScope()
            val vm by produceState<MemoryViewModel?>(null) {
                value = withContext(Dispatchers.IO) {
                    MemoryViewModel(
                        memoryRuntime.store(), memoryRuntime.settings(), memoryRuntime.skills(),
                        vmScope, memoryRuntime.file().brokenNotice,
                        wipe = { memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.wipeAll() } },
                        onForgotten = { id -> memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.stripMemory(setOf(id)) } },
                        runnableScripts = GuideCatalog.loadBundled().runnableScripts(),
                        appDrafts = memoryRuntime.appKnowledge(),
                    )
                }
            }
            vm?.let { m ->
                // 从结果卡片的「看看」进来:直接看技能分段
                LaunchedEffect(m) { if (memoryRuntime.openRequest.value != null) m.select(Segment.Skills) }
                MemoryScreen(m) { memoryRuntime.requestOpen(null); nav.back() }
            }
        }
        Route.Run -> {
            val id = runId
            val vm = remember(id) { id?.let { RunTimelineViewModel(RunStore(context), it, resolveMemory = app.chat::usedMemoryTexts) } }
            if (vm != null) RunTimelineScreen(vm, back) else LaunchedEffect(Unit) { nav.back() }
        }
        Route.Source -> {
            val vm = remember(sourceRunId, sourceLegacyText) {
                SourceViewModel(RunStore(context), sourceRunId, sourceLegacyText) { ConfirmationNotifier.appLabel(context, it) }
            }
            SourceScreen(vm, back)
        }
        Route.Plans -> PlansScreen(context, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.openChat()
        })
    }

    // 发现新版本就弹一次：下载 + 拉起安装都在 App 内完成，不用再翻「设置 › 关于」
    updateState.update?.let { newer ->
        var dismissed by rememberSaveable(newer.version) { mutableStateOf(false) }
        if (!dismissed) {
            UpdateDialog(newer, BuildConfig.VERSION_NAME, onDismiss = { dismissed = true })
        }
    }
}







