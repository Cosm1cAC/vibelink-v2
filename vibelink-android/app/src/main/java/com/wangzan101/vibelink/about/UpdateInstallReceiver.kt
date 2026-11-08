package com.wangzan101.vibelink.about

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

data class UpdateInstallResult(val sessionId: Int, val status: Int, val message: String, val confirm: Intent?)
object UpdateInstallEvents { val result = MutableStateFlow<UpdateInstallResult?>(null) }

/**
 * PackageInstaller 会话的结果回调。
 *
 * 走会话安装时，系统不一定会直接弹 UI：如果它需要用户确认，会回一个
 * `STATUS_PENDING_USER_ACTION` + 一个 Intent，交给前台 UpdateInstallActivity 拉起。
 * 后台接收器不直接打开页面；取消与失败也交给同一界面显示。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "vibelink-update"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        UpdateInstallEvents.result.value = UpdateInstallResult(sessionId, status, message, confirm)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.i(TAG, "install pending user action id=$sessionId; foreground Activity will confirm")
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "install success")
            else -> Log.e(TAG, "install failed status=$status $message")
        }
    }
}
