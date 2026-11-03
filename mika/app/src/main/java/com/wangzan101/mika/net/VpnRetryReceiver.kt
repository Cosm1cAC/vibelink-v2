package com.wangzan101.mika.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 网关失联时 [ClashVpnService] 会自动关掉代理（否则整机没网）。
 * 这个 receiver 负责在后台定时重试：网关一旦恢复，就把代理自己开回来。
 *
 * 退避：60s、120s、… 最多 5 分钟一次，累计 30 次左右放弃；
 * 用户手动关掉代理（wasEnabled=false）后立即停止重试。
 */
class VpnRetryReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "vibelink-vpn"
        const val ACTION_RETRY = "com.wangzan101.mika.VPN_RETRY"
        private const val REQ = 7301
        private const val PREF = "vibelink_vpn"
        private const val MAX_ATTEMPT = 10

        private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        fun attempt(c: Context): Int = prefs(c).getInt("retry", 0)

        fun reset(c: Context) {
            prefs(c).edit().putInt("retry", 0).apply()
            cancel(c)
            VpnNetWatcher.stop(c)
        }

        /** 排下一次重试：第 n 次等 n 分钟（封顶 5 分钟）。 */
        fun schedule(c: Context, attempt: Int) {
            // 网络一变（换 Wi-Fi / 切流量回来）就立刻试，不用等闹钟
            VpnNetWatcher.start(c)
            if (attempt > MAX_ATTEMPT) {
                Log.w(TAG, "gateway retry gave up after $MAX_ATTEMPT attempts")
                return
            }
            val delay = (attempt.coerceAtLeast(1) * 60_000L).coerceAtMost(5 * 60_000L)
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            val pi = pending(c)
            runCatching { am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, pi) }
                .onFailure { Log.w(TAG, "schedule retry failed", it) }
            Log.i(TAG, "retry #$attempt in ${delay / 1000}s")
        }

        fun cancel(c: Context) {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            runCatching { am.cancel(pending(c)) }
        }

        private fun pending(c: Context): PendingIntent {
            val i = Intent(c, VpnRetryReceiver::class.java).setAction(ACTION_RETRY)
            return PendingIntent.getBroadcast(
                c, REQ, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RETRY) return
        // 探测是阻塞 socket，onReceive 在主线程，直接连会抛 NetworkOnMainThreadException
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // 用户已经手动关掉了，就别再自作主张
                if (!ClashVpnService.wasEnabled(app)) {
                    reset(app)
                    return@Thread
                }
                val next = attempt(app) + 1
                if (!ClashVpnService.gatewayReachable(app)) {
                    prefs(app).edit().putInt("retry", next).apply()
                    Log.i(TAG, "gateway still down, attempt $next")
                    schedule(app, next)
                    return@Thread
                }
                Log.i(TAG, "gateway back, restarting vpn")
                reset(app)
                ClashVpnService.start(app)
            } catch (t: Throwable) {
                Log.e(TAG, "retry failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }
}



