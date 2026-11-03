package com.wangzan101.mika.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log

/**
 * 网络一变化就立刻尝试恢复代理，不用等定时重试（最长 5 分钟）。
 *
 * 只在一件事上生效：代理因为「网关失联」被自动关掉、正在等重试的时候。
 * 注册点在 [VpnRetryReceiver.schedule]，销号点在 [VpnRetryReceiver.reset]，
 * 所以正常运行时不会有任何监听开销。
 *
 * 进程被杀时回调随之失效，[VpnRetryReceiver] 的定时重试仍然是兜底。
 */
object VpnNetWatcher {
    private const val TAG = "vibelink-vpn"
    private const val DEBOUNCE_MS = 3_000L

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var lastTry = 0L

    @Synchronized
    fun start(context: Context) {
        if (callback != null) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = retryNow(app)

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryNow(app)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess {
                callback = cb
                Log.i(TAG, "net watcher on")
            }
            .onFailure { Log.w(TAG, "net watcher register failed", it) }
    }

    @Synchronized
    fun stop(context: Context) {
        val cb = callback ?: return
        callback = null
        runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(cb)
        }
        Log.i(TAG, "net watcher off")
    }

    private fun retryNow(app: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTry < DEBOUNCE_MS) return
        lastTry = now
        Log.i(TAG, "net event, probing gateway")
        // 探测是阻塞 socket，不能放在回调线程（主线程）上
        Thread {
            if (!ClashVpnService.wasEnabled(app) || ClashVpnService.running) return@Thread
            if (!ClashVpnService.gatewayReachable(app)) return@Thread
            Log.i(TAG, "network back, gateway ok -> restart vpn")
            VpnRetryReceiver.reset(app)
            ClashVpnService.start(app)
        }.also { it.isDaemon = true }.start()
    }
}

