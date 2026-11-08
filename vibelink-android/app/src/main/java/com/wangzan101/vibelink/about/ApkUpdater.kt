package com.wangzan101.vibelink.about

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.wangzan101.vibelink.BuildConfig
import com.wangzan101.vibelink.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Downloads and validates this edition's APK, then hands it to the foreground install flow. */
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
                val prefs = app.getSharedPreferences("pending_app_update", Context.MODE_PRIVATE)
                val cached = File(app.cacheDir, "updates/vibelink-update.apk")
                if (prefs.getString("url", null) == url && prefs.getString("expected", null) == expected && cached.isFile &&
                    runCatching { validate(app, cached, expected) }.isSuccess) {
                    withContext(Dispatchers.Main) { openInstaller(app, cached); onReady() }
                    return@launch
                }
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { runCatching { it.delete() } }
                val out = File(dir, "vibelink-update.apk")
                val fresh = url + if (url.contains('?')) "&ts=${System.currentTimeMillis()}" else "?ts=${System.currentTimeMillis()}"
                val req = Request.Builder()
                    .url(fresh)
                    .header("User-Agent", "VibeLink/${BuildConfig.VERSION_NAME}")
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
                validate(app, out, expected)
                prefs.edit().putString("url", url).putString("expected", expected).apply()
                withContext(Dispatchers.Main) {
                    onProgress(100)
                    openInstaller(app, out)
                    onReady()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onError(e.message ?: e.toString()) }
            }
        }
    }

    private fun validate(context: Context, file: File, expected: String?) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("下载的文件不是有效 APK，请重试")
        check(info.packageName == context.packageName) { "下载包与当前开发版/内测版不匹配" }
        if (expected != null && !info.versionName.equals(expected.removePrefix("v"), ignoreCase = true)) {
            error("下载到的是 ${info.versionName}，不是 $expected（清单/缓存不一致，请重试）")
        }
    }

    private fun openInstaller(context: Context, file: File) {
        context.startActivity(Intent(context, UpdateInstallActivity::class.java)
            .putExtra(UpdateInstallActivity.EXTRA_APK, file.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 没有进度条的入口（欢迎页的更新条）用这个。 */
    fun startWithToasts(context: Context, url: String) {
        Toast.makeText(context, "开始下载更新…", Toast.LENGTH_SHORT).show()
        start(
            context, url,
            onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
            onReady = { Toast.makeText(context, "下载完成，正在打开安装页面", Toast.LENGTH_SHORT).show() },
        )
    }
}





