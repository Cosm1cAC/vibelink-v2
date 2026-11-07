package com.wangzan101.vibelink.about

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * PackageInstaller 会话的结果回调。
 *
 * 走会话安装时，系统不一定会直接弹 UI：如果它需要用户确认，会回一个
 * `STATUS_PENDING_USER_ACTION` + 一个 Intent，我们必须把它拉起来，否则安装器看起来“没反应”。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "vibelink-update"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                Log.i(TAG, "install pending user action, launching=$confirm")
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.e(TAG, "launch confirm failed", it) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "install success")
            else -> Log.e(TAG, "install failed status=$status $message")
        }
    }
}
