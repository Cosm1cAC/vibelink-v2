package com.wangzan101.mika.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * 调试用：adb 触发 VPN 授权 / 启停。
 *
 *   adb shell am broadcast -a com.wangzan101.mika.VPN_PREP
 *   adb shell am broadcast -a com.wangzan101.mika.VPN_START
 *   adb shell am broadcast -a com.wangzan101.mika.VPN_STOP
 */
class VpnTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREP -> {
                val prep = VpnService.prepare(context)
                if (prep == null) {
                    Log.i(TAG, "already authorized")
                } else {
                    Log.i(TAG, "requesting consent")
                    runCatching { context.startActivity(prep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Log.e(TAG, "cannot show consent", it) }
                }
            }
            ACTION_START -> {
                Log.i(TAG, "start requested")
                ClashVpnService.start(context)
            }
            ACTION_STOP -> {
                Log.i(TAG, "stop requested")
                ClashVpnService.stop(context)
            }
        }
    }

    private companion object {
        const val TAG = "vibelink-vpn"
        const val ACTION_PREP = "com.wangzan101.mika.VPN_PREP"
        const val ACTION_START = "com.wangzan101.mika.VPN_START"
        const val ACTION_STOP = "com.wangzan101.mika.VPN_STOP"
    }
}
