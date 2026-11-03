package com.wangzan101.mika.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.github.kr328.clash.core.bridge.Bridge
import com.github.kr328.clash.core.bridge.TunInterface
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用 mihomo 内核接管全机流量：establish() 拿 TUN fd，交给 libclash 的 tun 栈。
 * 现在只做「连上 / 断开」，分流规则在 [ClashConfig] 里。
 */
class ClashVpnService : VpnService() {
    companion object {
        private const val TAG = "vibelink-vpn"
        const val ACTION_START = "com.wangzan101.mika.START_VPN"
        const val ACTION_STOP = "com.wangzan101.mika.STOP_VPN"
        private const val CHANNEL = "vibelink_vpn"
        private const val NOTIF_ID = 4401

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, ClashVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ClashVpnService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }

        /** 上次是不是开着：开机后据此自动恢复（VPN 授权是持久的，不需要再弹框）。 */
        fun wasEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)

        private fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", on).apply()
        }

        private const val PREFS = "vibelink_vpn"

        /** 最近一次失败原因：代理页直接显示，省得用户不知道「为什么打不开」。 */
        @Volatile
        var lastError: String? = null
            private set

        /** 网关探测。我们的包被排除在 VPN 之外，所以这是真实网络探测，不会绕回 tun。 */
        private fun reachable(host: String, port: Int, timeoutMs: Int = 2500): Boolean = runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.onFailure {
            Log.w(TAG, "connect $host:$port failed: ${it.javaClass.simpleName} ${it.message}")
        }.getOrDefault(false)

        /** 给重试 receiver 用：两个网关端口都能连上才算通。 */
        // 出口在笔记本（7897），tailnet 网关在 PC（18996）
        const val EXIT_HOST = "192.168.5.2"
        const val EXIT_PORT = 18997
        const val HUB_HOST = "192.168.5.2"
        const val HUB_PORT = 18996

        fun gatewayReachable(context: Context): Boolean {
            val exit = reachable(EXIT_HOST, EXIT_PORT, 2000)
            val hub = reachable(HUB_HOST, HUB_PORT, 2000)
            Log.i(TAG, "probe exit=$exit hub=$hub")
            return exit && hub
        }

        private fun reachableWithRetry(host: String, port: Int): Boolean {
            repeat(2) { if (reachable(host, port)) return true; Thread.sleep(400) }
            return false
        }
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            VpnRetryReceiver.reset(this)
            stopTunnel()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            startForeground(NOTIF_ID, buildNotification())
            // nativeLoad 会阻塞最多 20s，establish 也可能慢：不能卡主线程
            Thread {
                try {
                    startTunnel()
                } catch (e: Throwable) {
                    Log.e(TAG, "startTunnel crashed", e)
                    lastError = "启动失败：${e.message ?: e}"
                    stopTunnel()
                }
            }.start()
        }
        return START_STICKY
    }

    @Volatile
    private var watchdog: Thread? = null

    /** 网关失联时自动关掉 VPN，否则整机流量没有出口 = 手机断网。 */
    private fun startWatchdog(host: String) {
        watchdog?.interrupt()
        watchdog = Thread {
            var miss = 0
            while (running) {
                try {
                    Thread.sleep(20_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!running) return@Thread
                if (reachable(host, 18997, 2000)) {
                    miss = 0
                    continue
                }
                miss++
                Log.w(TAG, "gateway $host:18997 unreachable ($miss)")
                if (miss >= 2) {
                    lastError = "PC 网关失联，已自动关闭代理（否则整机会没网）。恢复后会自动重连。"
                    Log.e(TAG, lastError!!)
                    VpnRetryReceiver.schedule(this@ClashVpnService, 1)
                    stopTunnel()
                    return@Thread
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun startTunnel() {
        if (running) return
        lastError = null
        // 先确认 PC 网关在线：网关不通时建 tun 等于把手机网络掐了
        val host = ClashConfig.hubHost(this)
        if (!reachableWithRetry(EXIT_HOST, EXIT_PORT) || !reachableWithRetry(HUB_HOST, HUB_PORT)) {
            lastError = "连不上出口（$EXIT_HOST:$EXIT_PORT）或 PC 网关（$HUB_HOST:$HUB_PORT）。恢复后会自动重连。"
            Log.e(TAG, lastError!!)
            VpnRetryReceiver.schedule(this, 1)
            stopTunnel()
            return
        }
        Log.i(TAG, "gateway ok: $host")
        // 配置里的 100.x 走 PC 的 TCP 网关（hub:18996），手机端不再跑 tailscale
        val config = ClashConfig.write(this)
        // Go 侧 complete() 传回来的对象类型不保证是 String，用 Any? 接再判断
        val done = CompletableDeferred<Any?>()
        // nativeLoad 要的是「目录」，内核自己拼 config.yaml（传文件路径会变成 xxx/config.yaml/config.yaml）
        runCatching { Bridge.nativeLoad(done, config.parentFile!!.absolutePath) }
            .onFailure { Log.e(TAG, "nativeLoad threw", it); stopTunnel(); return }
        val result = runBlocking { withTimeoutOrNull(20_000) { done.await() } }
        if (!done.isCompleted) {
            Log.e(TAG, "config load timeout")
            stopTunnel()
            return
        }
        val error = result as? String
        if (error != null) {
            Log.e(TAG, "config load failed: $error")
            stopTunnel()
            return
        }
        Log.i(TAG, "config loaded: ${config.absolutePath}")

        val builder = Builder()
            .setSession("vibelink")
            .setMtu(9000)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("172.19.0.2")
            .setBlocking(false)
        runCatching { builder.addDisallowedApplication(packageName) }

        val fd = runCatching { builder.establish() }.getOrNull()
        if (fd == null) {
            lastError = "系统没有授 VPN 权限：到「网络代理」页重新打开一次开关"
            Log.e(TAG, "establish() returned null (VPN consent missing?)")
            stopTunnel()
            return
        }
        tun = fd

        val rc = runCatching {
            Bridge.nativeStartTun(
                fd.fd, "gvisor", "172.19.0.1/30", "172.19.0.2", "172.19.0.2",
                object : TunInterface {
                    override fun markSocket(fd: Int) {
                        runCatching { protect(fd) }
                    }

                    override fun querySocketUid(protocol: Int, source: String, target: String): Int = -1
                },
            )
        }.onFailure {
            Log.e(TAG, "nativeStartTun threw", it)
            stopTunnel()
            return
        }.getOrDefault(-1)
        if (rc != 0) {
            lastError = "内核建 tun 失败（rc=$rc），参数或配置有问题"
            Log.e(TAG, "tun.Start returned $rc (gateway/dns 参数不对？)")
            stopTunnel()
            return
        }

        // fd 已经交给内核（sing_tun 会在 stopTun 时关闭它）。Java 侧必须 detach，
        // 否则 native 关一次、ParcelFileDescriptor 再关一次 → fdsan 直接 abort（已复现）
        runCatching { fd.detachFd() }
        tun = null
        running = true
        setEnabled(this, true)
        VpnRetryReceiver.reset(this)
        Log.i(TAG, "tunnel up, state=${runCatching { Bridge.nativeQueryTunnelState() }.getOrNull()}")
        startWatchdog(host)
    }

    @Synchronized
    private fun stopTunnel() {
        watchdog?.interrupt()
        watchdog = null
        if (running) runCatching { Bridge.nativeStopTun() }
        running = false
        runCatching { tun?.close() }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "tunnel down")
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "vibelink 代理", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("vibelink 代理运行中")
            .setContentText("全部流量经 HY2 出口")
            .setSmallIcon(R.drawable.ic_vibelink_fore)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}















