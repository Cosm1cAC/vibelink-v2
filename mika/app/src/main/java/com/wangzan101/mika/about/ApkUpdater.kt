package com.wangzan101.mika.about

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 应用内更新：下载 APK 到 cache，再拉起系统安装器。
 *
 * 以前点「立即更新」是打开浏览器（`openInBrowser(downloadUrl)`），手机上体验是
 * 跳出去、下载、再回来装——看起来就像"没有应用内更新"。这里改成真的下完就装。
 */
object ApkUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(
        context: Context,
        url: String,
        expected: String? = null,
        onProgress: (Int) -> Unit = {},
        onError: (String) -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        val app = context.applicationContext
        scope.launch {
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                val out = File(dir, "mika-update.apk")
                val fresh = url + if (url.contains('?')) "&ts=${System.currentTimeMillis()}" else "?ts=${System.currentTimeMillis()}"
                val req = Request.Builder()
                    .url(fresh)
                    .header("User-Agent", "Mika/${BuildConfig.VERSION_NAME}")
                    .build()
                Net.client().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            var last = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                got += n
                                if (total > 0) {
                                    val pct = (got * 100 / total).toInt()
                                    if (pct != last) {
                                        last = pct
                                        withContext(Dispatchers.Main) { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (out.length() < 1024 * 1024) error("下载不完整（${out.length()} 字节）")
                // 校验下载到的确实是这个版本：之前出现过弹 0.5.3 却装成 0.5.2
                val info = app.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
                val got = info?.versionName
                if (expected != null && got != null && !got.equals(expected.removePrefix("v"), ignoreCase = true)) {
                    error("下载到的是 $got，不是 $expected（清单/缓存不一致，重试一次）")
                }
                withContext(Dispatchers.Main) {
                    onProgress(100)
                    install(app, out, onError)
                    onReady()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: e.toString()) }
            }
        }
    }

    /**
     * 用 PackageInstaller 会话安装：先把 APK 写进会话，再 commit。
     * 这条路径不依赖 intent 解析，不会被 Termux / 网盘之类的应用抢走；
     * 系统需要用户确认时会回 STATUS_PENDING_USER_ACTION，由 [UpdateInstallReceiver] 拉起确认页。
     */
    private suspend fun install(context: Context, file: File, onError: (String) -> Unit) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            onError("请先允许「安装未知应用」，允许后再点一次更新")
            return
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val installer = context.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
                ).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    context.contentResolver.openInputStream(Uri.fromFile(file))?.use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { out ->
                            input.copyTo(out, 64 * 1024)
                            session.fsync(out)
                        }
                    } ?: error("打开 APK 失败")
                    val result = Intent(context, UpdateInstallReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(
                        context, id, result,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pi.intentSender)
                    android.util.Log.i("vibelink-update", "session committed id=$id size=${file.length()}")
                }
                true
            }.getOrElse {
                android.util.Log.e("vibelink-update", "session install failed", it)
                false
            }
        }
        if (!ok) {
            // 会话失败再退回 intent 方式，尽量不要让用户卡在「点了没反应」
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                val base = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    clipData = android.content.ClipData.newRawUri("apk", uri)
                }
                val installers = context.packageManager.queryIntentActivities(base, 0)
                val pick = installers.firstOrNull {
                    it.activityInfo.packageName.contains("packageinstaller", ignoreCase = true)
                }
                context.startActivity(if (pick != null) Intent(base).setPackage(pick.activityInfo.packageName) else base)
            }.onFailure { onError(it.message ?: "无法拉起安装器") }
        }
    }
    /** 没有进度条的入口（欢迎页的更新条）用这个。 */
    fun startWithToasts(context: Context, url: String) {
        Toast.makeText(context, "开始下载更新…", Toast.LENGTH_SHORT).show()
        start(
            context, url,
            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
            onReady = { Toast.makeText(context, "下载完成，请在系统弹窗里确认安装", Toast.LENGTH_SHORT).show() },
        )
    }
}





