package com.wangzan101.vibelink.about

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps installation confirmation in a visible Activity, rather than a background receiver. */
class UpdateInstallActivity : ComponentActivity() {
    private var plan = UpdateInstallPlan()
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private lateinit var apk: File
    private var sessionId = -1
    private var started = false
    private var awaitingConfirmation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(cacheDir, "updates").canonicalFile
        apk = File(intent.getStringExtra(EXTRA_APK).orEmpty()).canonicalFile
        if (apk.parentFile != dir || !apk.isFile) { finish(); return }
        title = "安装 VibeLink 更新"
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        message = TextView(this).apply { textSize = 17f; gravity = Gravity.CENTER }
        progress = ProgressBar(this)
        retry = Button(this).apply { text = "安装更新"; setOnClickListener { proceed() } }
        content.addView(message)
        content.addView(progress)
        content.addView(retry)
        content.addView(Button(this).apply { text = "稍后"; setOnClickListener { finish() } })
        setContentView(content)
        render("正在准备系统安装确认…", true)
        val savedSession = savedInstanceState?.getInt("sessionId", -1) ?: -1
        if (savedSession >= 0 && packageManager.packageInstaller.getSessionInfo(savedSession) != null) {
            sessionId = savedSession
            started = true
            awaitingConfirmation = savedInstanceState?.getBoolean("awaitingConfirmation") == true
            plan.submitted()
            render("请在系统页面确认安装；尚未确认时可返回此页继续。", true)
        } else if (savedInstanceState?.getBoolean("requestingPermission") == true) {
            plan = UpdateInstallPlan(UpdateInstallPlan.Phase.Permission)
            started = true
            render("请允许安装后返回，或点“安装更新”重新打开设置。", false)
        }
        lifecycleScope.launch {
            // Only a resumed Activity may launch the system confirmation. A receiver
            // cannot reliably open it on recent Android/OEM background launch rules.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                UpdateInstallEvents.result.collect { result ->
                    if (result == null || result.sessionId != sessionId) return@collect
                    UpdateInstallEvents.result.value = null
                    when (result.status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirm = result.confirm
                            if (confirm == null) fail("系统未返回安装确认页面，请重试")
                            else runCatching {
                                awaitingConfirmation = true
                                startActivityForResult(Intent(confirm).apply { removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }, REQUEST_CONFIRM)
                                render("请在系统页面确认安装", true)
                            }.onFailure { fail("无法打开安装确认：${it.message}") }
                        }
                        PackageInstaller.STATUS_SUCCESS -> {
                            render("更新安装完成", false)
                            finish()
                        }
                        else -> fail(if (result.status == PackageInstaller.STATUS_FAILURE_ABORTED) "安装已取消，可直接重试，无需重新下载" else "安装失败：${result.message.ifBlank { "状态 ${result.status}" }}")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::apk.isInitialized && ::message.isInitialized && !started) {
            started = true
            proceed()
        } else if (::message.isInitialized && plan.phase == UpdateInstallPlan.Phase.Permission && packageManager.canRequestPackageInstalls()) {
            act(plan.returnedFromSettings(true))
        }
    }

    @Suppress("DEPRECATION")
    private fun act(action: UpdateInstallPlan.Action) {
        when (action) {
            UpdateInstallPlan.Action.PermissionSettings -> {
                render("请允许 VibeLink 安装应用。返回后会继续安装已下载的更新。", false)
                runCatching {
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), REQUEST_PERMISSION)
                }.onFailure { fail("无法打开安装权限设置：${it.message}") }
            }
            UpdateInstallPlan.Action.Submit -> submit()
            UpdateInstallPlan.Action.None -> Unit
        }
    }

    private fun proceed() = act(plan.start(packageManager.canRequestPackageInstalls()))

    private fun submit() {
        render("正在准备系统安装确认…", true)
        lifecycleScope.launch {
            val installer = packageManager.packageInstaller
            try {
                sessionId = withContext(Dispatchers.IO) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(packageName)
                        setSize(apk.length())
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                        }
                    }
                    val id = installer.createSession(params)
                    sessionId = id
                    try {
                        installer.openSession(id).use { session ->
                            apk.inputStream().use { input ->
                                session.openWrite("base.apk", 0, apk.length()).use { output ->
                                    input.copyTo(output, 64 * 1024)
                                    session.fsync(output)
                                }
                            }
                        }
                    } catch (e: Exception) { installer.abandonSession(id); throw e }
                    id
                }
                val result = Intent(this@UpdateInstallActivity, UpdateInstallReceiver::class.java)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                val pending = PendingIntent.getBroadcast(this@UpdateInstallActivity, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                plan.submitted()
                withContext(Dispatchers.IO) { installer.openSession(sessionId).use { it.commit(pending.intentSender) } }
                android.util.Log.i("vibelink-update", "session committed id=$sessionId")
            } catch (e: Exception) {
                if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
                fail("准备安装失败：${e.message}")
            }
        }
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PERMISSION) {
            val allowed = packageManager.canRequestPackageInstalls()
            if (!allowed) render("尚未允许安装。点“安装更新”可再次打开授权，无需重新下载。", false)
            act(plan.returnedFromSettings(allowed))
        } else if (requestCode == REQUEST_CONFIRM && awaitingConfirmation) {
            awaitingConfirmation = false
            fail("已返回应用。如尚未完成安装，可直接重试，无需重新下载。")
        }
    }

    private fun fail(text: String) { plan.failed(); render(text, false) }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("sessionId", sessionId)
        outState.putBoolean("awaitingConfirmation", awaitingConfirmation)
        outState.putBoolean("requestingPermission", plan.phase == UpdateInstallPlan.Phase.Permission)
        super.onSaveInstanceState(outState)
    }
    private fun render(text: String, busy: Boolean) {
        message.text = text
        progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        retry.visibility = if (busy) android.view.View.GONE else android.view.View.VISIBLE
    }

    companion object {
        const val EXTRA_APK = "updateApk"
        private const val REQUEST_PERMISSION = 701
        private const val REQUEST_CONFIRM = 702
    }
}
