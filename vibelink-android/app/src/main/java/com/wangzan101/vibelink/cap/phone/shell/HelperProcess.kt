package com.wangzan101.vibelink.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.wangzan101.vibelink.core.shell.AccessibilityList
import com.wangzan101.vibelink.core.shell.HelperHandoff
import com.wangzan101.vibelink.core.shell.PermissionCommands
import java.io.File
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * 内置助手进程的入口。VibeLink 经本机无线调试(adbd 的 exec: 服务)以 shell 身份启动它:
 * `CLASSPATH=<base.apk> app_process /system/bin --nice-name=<包名>:helper com.wangzan101.vibelink.shell.HelperMain <包名> <VibeLink 的 uid> <签名指纹>`,
 * 一次性口令从标准输入读一行(见 [HelperHandoff.command])。
 *
 * 特权逻辑全在 [ShellService];这里只负责把它的 Binder 交给 VibeLink,并且在 VibeLink 进程结束后不退出:
 * 撤掉 VibeLink 留下的改动,等 VibeLink 再起来时重新交接。
 */
object HelperProcess {
    private const val TAG = "VibeLinkHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 3) {
            Log.e(TAG, "usage: HelperMain <package> <uid> <cert-sha256> (nonce on stdin)")
            exitProcess(2)
        }
        val packageName = args[0]
        val appUid = args[1].toIntOrNull() ?: exitProcess(2)
        val certPin = args[2]
        // 读完就关:标准输入只用来递口令
        val launchNonce = runCatching { System.`in`.bufferedReader().readLine()?.trim() }.getOrNull()
        runCatching { System.`in`.close() }
        if (launchNonce.isNullOrEmpty()) {
            Log.e(TAG, "no nonce on stdin")
            exitProcess(2)
        }
        // ShellContext 自建 ActivityThread 时要有主线程 Looper
        Looper.prepareMainLooper()
        val service = ShellService(allowedUid = appUid)
        // 上次在静音中被杀、没来得及恢复的 App,先把声音还回去
        service.recoverMuted()
        val helper = Helper(service, packageName, appUid, certPin)
        helper.start(launchNonce)
        Looper.loop()
    }

    private class Helper(
        private val service: ShellService,
        private val packageName: String,
        private val appUid: Int,
        private val certPin: String,
    ) {
        private val context: Context = ShellContext.create()
        private val authority = HelperHandoff.authority(packageName)
        private val userId = appUid / PER_USER_RANGE
        /** 交接、VibeLink 死亡处理都排在这一条线程上,彼此不交错,也不堵主线程。 */
        private val worker = Handler(HandlerThread("helper-handoff").apply { start() }.looper)
        /** 下次交接用的口令(VibeLink 上次收下时换发的)。 */
        private var nonce: String? = null
        /** VibeLink 进程交给我们的令牌;为 null 表示 VibeLink 现在不在。 */
        private var appToken: IBinder? = null
        private var retrying = false

        fun start(launchNonce: String) = worker.post {
            when (deliver(launchNonce)) {
                Outcome.Accepted -> {
                    // VibeLink 收下了这一个:之前起的(或残留的)助手实例都该走了
                    killOtherInstances()
                    watchUid()
                    startKeepAliveWatchdog()
                }
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> exitProcess(1)
            }
        }

        /**
         * 独立运行在 uid 2000 (com.android.shell) 的双向守护循环:
         * 1. 不受国产系统(ColorOS/MIUI/OriginOS)对普通 App 墓碑冻结的影响;
         * 2. 即便 ColorOS 开启了「权限监控」拦截了 `pm grant WRITE_SECURE_SETTINGS`,
         *    uid 2000 自身天然持有 WRITE_SECURE_SETTINGS,可直接通过 ContentResolver 监听并秒级自愈
         *    `adb_wifi_enabled` (无线调试) 与 `enabled_accessibility_services` (无障碍服务)。
         */
        private var lastWifiAdbWriteMs = 0L
        private var rapidWifiAdbReverts = 0
        private var hardenedOnce = false

        private val healWifiAdbRunnable = Runnable {
            runCatching { healWifiAdbInProcess() }.onFailure { Log.w(TAG, "healWifiAdbInProcess failed", it) }
        }

        private val healA11yRunnable = Runnable {
            runCatching { healAccessibilityInProcess() }.onFailure { Log.w(TAG, "healAccessibilityInProcess failed", it) }
        }

        private fun startKeepAliveWatchdog() {
            val cr = context.contentResolver
            runCatching {
                cr.registerContentObserver(
                    Settings.Global.getUriFor(ADB_WIFI_ENABLED),
                    false,
                    object : ContentObserver(worker) {
                        override fun onChange(selfChange: Boolean) {
                            worker.removeCallbacks(healWifiAdbRunnable)
                            worker.postDelayed(healWifiAdbRunnable, 1_000L)
                        }
                    },
                )
                cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    false,
                    object : ContentObserver(worker) {
                        override fun onChange(selfChange: Boolean) {
                            // 留出 2.5s 避让 AccessibilityRepair 主动「先移除 1s 再加回」的重绑周期与 unpair 退出
                            worker.removeCallbacks(healA11yRunnable)
                            worker.postDelayed(healA11yRunnable, 2_500L)
                        }
                    },
                )
            }.onFailure { Log.w(TAG, "registerContentObserver in HelperProcess failed", it) }

            val tick = object : Runnable {
                override fun run() {
                    runCatching { keepAliveTick() }.onFailure { Log.w(TAG, "keepAliveTick failed", it) }
                    worker.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
                }
            }
            worker.post(tick)
        }

        private fun keepAliveTick() {
            if (!installed() || !signedAsPinned()) return
            if (!hardenedOnce) {
                hardenedOnce = true
                val hardenCmd = buildString {
                    append(PermissionCommands.grantWriteSecureSettings(packageName)).append("; ")
                    append(PermissionCommands.disableAdbTimeout()).append("; ")
                    append(PermissionCommands.whitelistAdd(packageName)).append("; ")
                    PermissionCommands.allowBackgroundRun(packageName).forEach { append(it).append("; ") }
                }
                service.runSystemCommand(-1, hardenCmd)
            } else {
                service.runSystemCommand(-1, PermissionCommands.disableAdbTimeout())
            }

            healWifiAdbInProcess()
            healAccessibilityInProcess()
        }

        private fun healWifiAdbInProcess() {
            if (!installed() || !signedAsPinned()) return
            val adbWifi = service.runSystemCommand(-1, "settings get global $ADB_WIFI_ENABLED")
                .getString("output")?.trim()
            val now = System.currentTimeMillis()
            if (adbWifi == "1") {
                if (now - lastWifiAdbWriteMs > 4_500L) rapidWifiAdbReverts = 0
                return
            }
            if (adbWifi != "0") return
            val sinceLast = now - lastWifiAdbWriteMs
            if (sinceLast < 4_500L) {
                rapidWifiAdbReverts += 1
                return
            }
            if (rapidWifiAdbReverts >= 2 && sinceLast < 60_000L) return
            lastWifiAdbWriteMs = now
            val res = service.runSystemCommand(
                -1,
                "settings put global $ADB_ALLOWED_CONNECTION_TIME 0; settings put global $ADB_WIFI_ENABLED 1",
            )
            Log.i(TAG, "HelperProcess auto-healed adb_wifi_enabled -> 1 (exit=${res.getInt("exitCode")})")
        }

        private fun healAccessibilityInProcess() {
            if (!installed() || !signedAsPinned()) return
            val a11yRaw = service.runSystemCommand(-1, PermissionCommands.READ_ACCESSIBILITY)
                .getString("output")?.trim()
            val me = PermissionCommands.accessibilityService(packageName)
            if (!AccessibilityList.contains(a11yRaw, me)) {
                val updated = AccessibilityList.with(a11yRaw, me)
                val cmds = PermissionCommands.writeAccessibility(updated).joinToString("; ")
                val res = service.runSystemCommand(-1, cmds)
                Log.i(TAG, "HelperProcess auto-healed enabled_accessibility_services -> $updated (exit=${res.getInt("exitCode")})")
            }
        }

        private enum class Outcome { Accepted, Rejected, Failed }

        /** 经 ActivityManager 拿 VibeLink 的 provider(同 `content call` 的做法),把 Binder 和自报的 uid/pid 交过去。 */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun deliver(n: String?): Outcome {
            if (n == null) return Outcome.Rejected
            // 包名和 uid 可能被重装的别的 App 占用:签名对不上就不交 Binder,撤掉改动退出
            if (!signedAsPinned()) {
                Log.e(TAG, "$packageName is missing or not signed with the pinned certificate; exiting")
                runCatching { service.releaseAll() }
                exitProcess(0)
            }
            val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            val token = Binder()
            val holder = try {
                am.javaClass.getMethod(
                    "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
                    IBinder::class.java, String::class.java,
                ).invoke(am, authority, userId, token, TAG)
            } catch (t: Throwable) {
                Log.w(TAG, "getContentProviderExternal failed", t)
                return Outcome.Failed
            } ?: return Outcome.Failed
            try {
                // 同名 authority 只可能属于一个包;还是核对一下,不把 Binder 交给 VibeLink 以外的 App
                val info = holder.javaClass.getField("info").get(holder) as? ProviderInfo
                if (info == null || info.packageName != packageName || info.applicationInfo?.uid != appUid) {
                    Log.e(TAG, "provider $authority does not belong to $packageName/$appUid")
                    return Outcome.Rejected
                }
                val provider = holder.javaClass.getField("provider").get(holder)!!
                val extras = Bundle().apply {
                    putString(HelperHandoff.KEY_NONCE, n)
                    putBinder(HelperHandoff.KEY_BINDER, service)
                    putInt(HelperHandoff.KEY_UID, Process.myUid())
                    putInt(HelperHandoff.KEY_PID, Process.myPid())
                    putString(HelperHandoff.KEY_APK, System.getProperty("java.class.path"))
                }
                val call = provider.javaClass.methods.first {
                    it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
                }
                val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
                val result = call.invoke(provider, caller, authority, HelperHandoff.METHOD_ATTACH, null, extras) as Bundle?
                if (result?.getBoolean(HelperHandoff.KEY_ACCEPTED) != true) {
                    Log.w(TAG, "handoff rejected: ${result?.getString(HelperHandoff.KEY_REASON)}")
                    return Outcome.Rejected
                }
                val appBinder = result.getBinder(HelperHandoff.KEY_TOKEN) ?: return Outcome.Rejected
                nonce = result.getString(HelperHandoff.KEY_NEXT)
                appToken = appBinder
                try {
                    appBinder.linkToDeath({ worker.post { onAppDied(appBinder) } }, 0)
                } catch (t: Throwable) {
                    worker.post { onAppDied(appBinder) }
                }
                Log.i(TAG, "handed off to $packageName")
                return Outcome.Accepted
            } catch (t: Throwable) {
                Log.w(TAG, "handoff failed", t)
                return Outcome.Failed
            } finally {
                runCatching {
                    am.javaClass.getMethod(
                        "removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType,
                    ).invoke(am, authority, token, userId)
                }
            }
        }

        /** VibeLink 进程没了:它建的屏、静音、剪贴板改动跟着撤掉(和服务结束时一样),助手本身留着。 */
        private fun onAppDied(token: IBinder) {
            if (appToken !== token) return
            appToken = null
            runCatching { service.releaseAll() }.onFailure { Log.w(TAG, "cleanup failed", it) }
            if (!installed()) {
                Log.i(TAG, "$packageName is gone; exiting")
                exitProcess(0)
            }
            Log.i(TAG, "$packageName died; waiting for it to come back")
        }

        @SuppressLint("DiscouragedPrivateApi")
        private fun installed(): Boolean = runCatching {
            context.packageManager.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(context.packageManager, packageName, 0, userId)
            true
        }.getOrElse { t ->
            // 包不在了才退出;反射不可用时看不清,当作还在
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            cause !is android.content.pm.PackageManager.NameNotFoundException
        }

        /**
         * 盯着 VibeLink 的 uid:进程重新起来时(从不存在变成任一状态)系统会报一次,这时把 Binder 再交过去。
         * 用的是 ActivityManager 的 uid 重要性监听(要 PACKAGE_USAGE_STATS,shell 有),回调在 binder 线程。
         * 注册不上时由 VibeLink 那边兜底:等不到交接就经 adbd 另起一个助手,新的收下后会结束这个。
         */
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun watchUid() {
            runCatching {
                // 用 shell 包名的上下文建 ActivityManager:注册时系统按调用方包名核对权限
                val manager = ActivityManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java)
                    .apply { isAccessible = true }.newInstance(context, worker)
                val listenerClass = Class.forName("android.app.ActivityManager\$OnUidImportanceListener")
                val listener = Proxy.newProxyInstance(HelperProcess::class.java.classLoader, arrayOf(listenerClass)) { proxy, method, a ->
                    when (method.name) {
                        "onUidImportance" -> { onUidImportance(a[0] as Int, a[1] as Int); null }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === a?.getOrNull(0)
                        "toString" -> "VibeLinkHelperUidListener"
                        else -> null
                    }
                }
                ActivityManager::class.java.getMethod("addOnUidImportanceListener", listenerClass, Int::class.javaPrimitiveType)
                    .invoke(manager, listener, ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
            }.onFailure { Log.w(TAG, "cannot watch $packageName; relying on the app to restart the helper", it) }
        }

        private fun onUidImportance(uid: Int, importance: Int) {
            if (uid != appUid || importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE) return
            worker.post { if (appToken == null && !retrying) redeliver(0) }
        }

        /** 重新交接;provider 暂时不可用时隔一会儿再试几次,还不行就等下一次 VibeLink 起来。 */
        private fun redeliver(attempt: Int) {
            if (appToken != null) return
            when (deliver(nonce)) {
                Outcome.Accepted -> retrying = false
                // VibeLink 不认这个助手了(重装过、解除了配对、或者已经换了新的):退出
                Outcome.Rejected -> exitProcess(0)
                Outcome.Failed -> {
                    retrying = attempt + 1 < RETRY_DELAYS_MS.size
                    if (retrying) worker.postDelayed({ redeliver(attempt + 1) }, RETRY_DELAYS_MS[attempt + 1])
                }
            }
        }

        /** 现在装着的 [packageName] 仍是这个 uid、签名证书指纹与启动时 VibeLink 给的一致。看不清一律当不一致。 */
        @SuppressLint("DiscouragedPrivateApi")
        private fun signedAsPinned(): Boolean = runCatching {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (userId == 0) pm.getPackageInfo(packageName, flags)
            else pm.javaClass.getMethod("getPackageInfoAsUser", String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(pm, packageName, flags, userId) as PackageInfo
            val signers = info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
            info.applicationInfo?.uid == appUid && HelperHandoff.certPin(signers) == certPin
        }.getOrElse { t ->
            Log.w(TAG, "cannot read signing info of $packageName", t)
            false
        }

        /** 只认进程名完全相同的同 uid 进程:别的 app_process 不能误伤。 */
        private fun killOtherInstances() {
            val me = Process.myPid()
            val name = HelperHandoff.processName(packageName)
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull() ?: return@forEach
                val argv0 = String(cmdline.takeWhile { it != 0.toByte() }.toByteArray())
                if (argv0 == name) {
                    Log.i(TAG, "stopping older helper $pid")
                    Process.killProcess(pid)
                }
            }
        }

        companion object {
            private const val PER_USER_RANGE = 100_000
            private val RETRY_DELAYS_MS = longArrayOf(0, 1_000, 3_000, 10_000)
            private const val KEEP_ALIVE_INTERVAL_MS = 15_000L
            private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
            private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"
        }
    }
}
