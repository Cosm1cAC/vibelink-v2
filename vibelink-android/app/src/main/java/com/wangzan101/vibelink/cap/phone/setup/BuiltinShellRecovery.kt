package com.wangzan101.vibelink.cap.phone.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.wangzan101.vibelink.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.vibelink.core.shell.A11yObservation
import com.wangzan101.vibelink.core.shell.AccessibilityList
import com.wangzan101.vibelink.core.shell.AccessibilityRepair
import com.wangzan101.vibelink.core.shell.BootRestore
import com.wangzan101.vibelink.core.shell.BuiltinShellControl
import com.wangzan101.vibelink.core.shell.BuiltinShellStatus
import com.wangzan101.vibelink.core.shell.PermissionCommands
import com.wangzan101.vibelink.core.shell.PermissionResult
import com.wangzan101.vibelink.core.shell.RecoveryCapability
import com.wangzan101.vibelink.core.shell.RecoveryInputs
import com.wangzan101.vibelink.core.shell.RecoveryPermissions
import com.wangzan101.vibelink.core.shell.RecoveryPolicy
import com.wangzan101.vibelink.core.shell.RestoreOutcome
import com.wangzan101.vibelink.core.shell.ShellCommandRunner
import com.wangzan101.vibelink.settings.RecoverySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 内置助手的自动恢复和那几项能力。跑 shell 命令用已配对的连接 [runner](没配对时不给);
 * 能力与开机结果记在 [settings];[control] 是内置助手本身。由 [com.wangzan101.vibelink.cap.phone.PhoneServices] 建一份,
 * App 启动时由 [start] 开始看着连接。
 */
class BuiltinShellRecovery(
    context: Context,
    private val runner: ShellCommandRunner,
    private val settings: RecoverySettings,
    private val control: BuiltinShellControl,
) {
    private val app = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    @Volatile private var lastDirectWifiAdbWriteMs = 0L
    @Volatile private var rapidWifiAdbReverts = 0

    val permissions: RecoveryPermissions by lazy {
        RecoveryPermissions({ runner.takeIf { control.status.value != BuiltinShellStatus.NotPaired } }, settings, app.packageName)
    }

    /**
     * 无障碍服务没在运行时自动打开它;结果([AccessibilityRepair.last])给设置页说一句。
     * App 自己有修改系统设置的授权(「重启后自动恢复」给的)就直接写,不必等助手;没有才经助手。
     */
    val accessibilityRepair = AccessibilityRepair(
        restart = { gap -> if (canWriteSecure()) restartDirectly(gap) else permissions.restartAccessibility(gap) },
        consent = { settings.consent.value },
        direct = ::canWriteSecure,
    )

    private fun canWriteSecure() =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 和 [RecoveryPermissions.restartAccessibility] 同样的步骤,只是由 App 自己写系统设置:没有就加上,有却没绑定就去掉再加回。 */
    private suspend fun restartDirectly(gapMs: Long): PermissionResult = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun read() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        fun write(list: String) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null })
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        }
        try {
            val current = read()
            if (AccessibilityList.contains(current, me)) {
                write(AccessibilityList.without(current, me))
                withContext(NonCancellable) {
                    delay(gapMs)
                    write(AccessibilityList.with(read(), me))
                }
            } else {
                write(AccessibilityList.with(current, me))
            }
            PermissionResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 助手每次变成运行中(刚配对完、掉线后重起、App 启动时本来就配过对、开机自动恢复连上),就把默认打开、
     * 用户没关掉、还没生效的几项补上。这几条路最后都落到同一个状态,所以只在这里看,不在各处分别调。
     * 同时看着无障碍服务:没在运行、又有办法写系统设置(App 自己有授权或助手在运行)时,自动打开它。
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { permissions.applyWhenRunning(control.status) }
        scope.launch { accessibilityRepair.watch(serviceObservations(), control.status) }
        startKeepAliveWatchdog()
    }

    /**
     * 全天候无线 ADB 与无障碍自愈守护:
     * 1. 监听 adb_wifi_enabled 变化:一旦被系统超时关闭(0),只要持有 WRITE_SECURE_SETTINGS 且未在设置里关闭自动无线调试,
     *    无需依赖内置配对状态即可在 ~1s 内直接写回 1,并关闭系统 adb_allowed_connection_time 超时。
     * 2. 监听亮屏/解锁广播(ACTION_SCREEN_ON / ACTION_USER_PRESENT)与 45s 周期心跳:
     *    解除熄屏冻结期间可能触发的 AccessibilityRepair 放弃状态(reset),自动重绑无障碍与无线调试。
     */
    private fun startKeepAliveWatchdog() {
        val cr = app.contentResolver
        val mainHandler = Handler(Looper.getMainLooper())

        // 1. 监听无线调试开关被系统超时置 0
        runCatching {
            cr.registerContentObserver(
                DeveloperSettings.wirelessDebuggingUri,
                false,
                object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        scope.launch { healWirelessAdbAndA11y(fromUserWake = false) }
                    }
                },
            )
        }

        // 2. 监听亮屏与解锁广播,第一时间从国产系统熄屏冻结中自愈
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    rapidWifiAdbReverts = 0
                    accessibilityRepair.reset()
                    scope.launch { healWirelessAdbAndA11y(fromUserWake = true) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }

        // 3. 周期心跳兜底(每 45 秒检查一次无线 ADB、超时配置与无障碍存活)
        scope.launch {
            while (true) {
                runCatching { healWirelessAdbAndA11y(fromUserWake = false) }
                delay(KEEP_ALIVE_HEARTBEAT_MS)
            }
        }
    }

    private suspend fun healWirelessAdbAndA11y(fromUserWake: Boolean) = withContext(Dispatchers.IO) {
        if (canWriteSecure()) {
            val cr = app.contentResolver
            // 始终将无线 ADB 超时设为 0 (禁用 Android 11+ / ColorOS 默认几小时或 7 天断连计时)
            runCatching { Settings.Global.putLong(cr, ADB_ALLOWED_CONNECTION_TIME, 0L) }

            val consent = settings.consent.value
            if (consent.wants(RecoveryCapability.AutoWirelessDebugging)) {
                if (DeveloperSettings.wirelessDebugging(cr)) {
                    if (System.currentTimeMillis() - lastDirectWifiAdbWriteMs > REVERT_WINDOW_MS) {
                        rapidWifiAdbReverts = 0
                    }
                } else {
                    val sinceLast = System.currentTimeMillis() - lastDirectWifiAdbWriteMs
                    if (!fromUserWake && sinceLast < REVERT_WINDOW_MS) {
                        // 刚写完 1 就在几秒内被系统拨回 0:说明当前 Wi-Fi 未授权或没连 Wi-Fi,避免弹窗死循环
                        rapidWifiAdbReverts += 1
                    } else if (fromUserWake || rapidWifiAdbReverts < 2 || sinceLast > REVERT_COOLDOWN_MS) {
                        delay(800L)
                        if (!DeveloperSettings.wirelessDebugging(cr) && settings.consent.value.wants(RecoveryCapability.AutoWirelessDebugging)) {
                            lastDirectWifiAdbWriteMs = System.currentTimeMillis()
                            val ok = DeveloperSettings.turnOnWirelessDebugging(cr)
                            Log.i(TAG, "Auto-healed wireless debugging (adb_wifi_enabled -> 1): $ok")
                        }
                    }
                }
            }

            if (consent.wants(RecoveryCapability.Accessibility)) {
                val me = PermissionCommands.accessibilityService(app.packageName)
                val currentA11y = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                if (!AccessibilityList.contains(currentA11y, me)) {
                    runCatching {
                        val updated = AccessibilityList.with(currentA11y, me)
                        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                        Log.i(TAG, "Auto-healed enabled_accessibility_services -> $updated")
                    }
                }
                if (ScreenAccessibility.instance.value == null) {
                    accessibilityRepair.reset()
                }
            }

            // 同步更新已生效标记:只要持有 WRITE_SECURE_SETTINGS,这三项能力即便未走内置配对也已直接生效
            var updatedConsent = settings.consent.value
            for (cap in listOf(RecoveryCapability.Accessibility, RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging)) {
                if (updatedConsent.wants(cap) && !updatedConsent.isApplied(cap)) {
                    updatedConsent = updatedConsent.withApplied(cap, true)
                }
            }
            if (updatedConsent != settings.consent.value) {
                settings.save(updatedConsent)
            }
        } else if (control.status.value == BuiltinShellStatus.Running) {
            // 助手在运行但 App 还没拿到 WRITE_SECURE_SETTINGS:经助手补齐授权与防超时设置
            runCatching {
                runner.exec(
                    "${PermissionCommands.grantWriteSecureSettings(app.packageName)}; " +
                        "${PermissionCommands.disableAdbTimeout()}; " +
                        PermissionCommands.whitelistAdd(app.packageName),
                )
            }
            if (settings.consent.value.wants(RecoveryCapability.Accessibility) && ScreenAccessibility.instance.value == null) {
                accessibilityRepair.reset()
            }
        }
    }

    /** 系统列表里有没有 VibeLink(跟着系统设置的变化)和服务实例是否连着(跟着连上、断开)。 */
    private fun serviceObservations(): StateFlow<A11yObservation> {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun enabled() = AccessibilityList.contains(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), me)
        val listChanges = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            send(Unit)
            awaitClose { cr.unregisterContentObserver(observer) }
        }
        return combine(listChanges, ScreenAccessibility.instance) { _, service -> A11yObservation(enabled(), service != null) }
            .stateIn(scope, SharingStarted.Eagerly, A11yObservation(enabled(), ScreenAccessibility.instance.value != null))
    }

    /** 解除配对:先趁连接还在收回授权,再删配对。 */
    suspend fun unpair() {
        val a11yApplied = settings.consent.value.isApplied(RecoveryCapability.Accessibility)
        val result = permissions.revokeAll()
        // 连接已经不在时,无障碍服务至少能自己关掉;电池白名单和修改系统设置的授权只能靠连接收回
        if (result != PermissionResult.Done && a11yApplied) {
            runCatching { ScreenAccessibility.instance.value?.disableSelf() }
        }
        control.unpair()
    }

    /** 开机后按已生效的「重启后自动恢复」重新打开无线调试并重起助手;不需要动手时返回 null。 */
    suspend fun restoreAfterBoot(): RestoreOutcome? {
        val holds = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        when (BootRestore.gate(Build.VERSION.SDK_INT, settings.consent.value, holds)) {
            BootRestore.Gate.Skip -> return null
            BootRestore.Gate.PermissionMissing -> return RestoreOutcome.PermissionMissing.also(settings::recordRestore)
            BootRestore.Gate.Act -> Unit
        }
        val cr = app.contentResolver
        // 能力检测:写入被拒(抛异常/返回 false)或写了读回来不是 1,都当这台手机不支持
        val written = DeveloperSettings.turnOnWirelessDebugging(cr) && DeveloperSettings.wirelessDebugging(cr)
        if (!written) return RestoreOutcome.SettingUnavailable.also(settings::recordRestore)

        val outcome = withTimeoutOrNull(BOOT_BUDGET_MS) {
            RecoveryPolicy.recover(
                inputs = {
                    RecoveryInputs(
                        paired = control.status.value != BuiltinShellStatus.NotPaired,
                        // 不在信任的 Wi-Fi 上时系统会把开关拨回去:每轮重读,拨回去就停
                        wirelessDebugging = DeveloperSettings.wirelessDebugging(cr),
                        status = control.status.value,
                    )
                },
                attempt = { control.retry() },
            )
        }
        val result = if (outcome?.recovered == true) RestoreOutcome.Restored else RestoreOutcome.NotRecovered
        return result.also(settings::recordRestore)
    }

    private companion object {
        private const val TAG = "VibeLinkRecovery"
        private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
        private const val KEEP_ALIVE_HEARTBEAT_MS = 45_000L
        private const val REVERT_WINDOW_MS = 4_500L
        private const val REVERT_COOLDOWN_MS = 60_000L
        /** 开机广播 goAsync 之后约有一分钟;留余量。 */
        const val BOOT_BUDGET_MS = 50_000L
    }
}
