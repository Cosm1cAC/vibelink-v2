package com.proxystack.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 远程命令代理：通过局域网（PC 热点）暴露一个极简 HTTP 服务，
 * 授权后用 /system/bin/sh 执行命令并回传 stdout/stderr/退出码。
 *
 * 端点：
 *   GET  /health   -> {"ok":true,...}          （无需授权）
 *   GET  /stat     -> 状态 JSON                  （需授权）
 *   POST /exec     -> body=命令文本              （需授权）
 *
 * 授权：请求头  Authorization: Bearer <token>
 */
class AgentService : Service() {

    companion object {
        const val PORT = 8765
        const val TOKEN = "[REMOVED_CREDENTIAL]"
        const val CHANNEL_ID = "proxystack_agent"
        const val NOTIF_ID = 1001
        const val EXTRA_CMD = "cmd"
        const val EXTRA_TIMEOUT = "timeout"
        const val DEFAULT_TIMEOUT_S = 120L
        const val MAX_OUTPUT = 512 * 1024

        @Volatile
        var running: Boolean = false
    }

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val pool = Executors.newFixedThreadPool(6)
    private val startedAt = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        // 支持"带命令启动"：可直接触发一次执行（日志兜底用途）
        intent?.getStringExtra(EXTRA_CMD)?.let { cmd ->
            val t = intent.getLongExtra(EXTRA_TIMEOUT, DEFAULT_TIMEOUT_S)
            pool.submit { runShell(cmd, t); stopSelf(startId) }
        }
        if (!running) startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startServer() {
        running = true
        acceptThread = Thread {
            try {
                val ss = ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0"))
                server = ss
                while (running && !ss.isClosed) {
                    val client = try { ss.accept() } catch (e: Exception) { break }
                    pool.submit { handle(client) }
                }
            } catch (e: Exception) {
                running = false
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---------------------------------------------------------------- HTTP

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 20_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            val first = readLine(input) ?: return respond(out, 400, "bad request\n")
            val parts = first.split(' ')
            val method = parts.getOrNull(0) ?: return respond(out, 400, "bad request\n")
            val path = (parts.getOrNull(1) ?: "").substringBefore('?')

            var contentLength = 0
            var authed = false
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i <= 0) continue
                val key = line.substring(0, i).trim().lowercase()
                val v = line.substring(i + 1).trim()
                when (key) {
                    "content-length" -> contentLength = v.toIntOrNull() ?: 0
                    "authorization" -> authed = (v == "Bearer $TOKEN")
                }
            }

            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            val payload = String(body, 0, read)

            when {
                path == "/health" && method == "GET" ->
                    respond(out, 200, "{\"ok\":true,\"port\":$PORT,\"up\":${(System.currentTimeMillis() - startedAt) / 1000}}\n")

                !authed -> respond(out, 401, "unauthorized\n")

                path == "/stat" && method == "GET" ->
                    respond(out, 200, statJson())

                path == "/exec" && method == "POST" -> {
                    val cmd = payload.trim()
                    if (cmd.isEmpty()) {
                        respond(out, 400, "exit=2\n--- stderr ---\nempty command\n")
                    } else {
                        respondRaw(out, 200, runShell(cmd, DEFAULT_TIMEOUT_S))
                    }
                }

                else -> respond(out, 404, "not found\n")
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun statJson(): String {
        val ips = ArrayList<String>()
        runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .forEach { ni ->
                    ni.inetAddresses.toList().forEach { a ->
                        if (a is java.net.Inet4Address && !a.isLoopbackAddress) ips.add(a.hostAddress ?: "")
                    }
                }
        }
        val sb = StringBuilder()
        sb.append("{\"ok\":true,\"running\":").append(running)
        sb.append(",\"port\":").append(PORT)
        sb.append(",\"uptime_s\":").append((System.currentTimeMillis() - startedAt) / 1000)
        sb.append(",\"ips\":[").append(ips.joinToString(",") { "\"$it\"" }).append("]")
        sb.append(",\"android\":\"").append(android.os.Build.VERSION.RELEASE).append("\"")
        sb.append(",\"model\":\"").append(android.os.Build.MODEL).append("\"}")
        return sb.toString() + "\n"
    }

    // ---------------------------------------------------------------- exec

    private fun runShell(cmd: String, timeoutS: Long): String {
        val out = StringBuilder()
        try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
            pb.redirectErrorStream(false)
            val p = pb.start()
            val so = StringBuilder()
            val se = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader().forEachLine { if (so.length < MAX_OUTPUT) so.append(it).append('\n') } }
            val t2 = Thread { p.errorStream.bufferedReader().forEachLine { if (se.length < MAX_OUTPUT) se.append(it).append('\n') } }
            t1.isDaemon = true; t2.isDaemon = true
            t1.start(); t2.start()
            val done = p.waitFor(timeoutS, TimeUnit.SECONDS)
            if (!done) {
                p.destroy()
                se.append("[timeout after ${timeoutS}s]\n")
            }
            t1.join(1500); t2.join(1500)
            val code = if (done) p.exitValue() else 124
            out.append("exit=").append(code).append('\n')
            out.append("--- stdout ---\n").append(so)
            out.append("--- stderr ---\n").append(se)
        } catch (e: Exception) {
            out.append("exit=127\n--- stderr ---\n").append(e.toString()).append('\n')
        }
        return out.toString()
    }

    private fun respond(out: OutputStream, code: Int, text: String) {
        respondRaw(out, code, text)
    }

    private fun respondRaw(out: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ')
            append(if (code == 200) "OK" else "Error").append("\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    // ------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Agent", NotificationManager.IMPORTANCE_LOW)
            ch.description = "远程命令代理常驻通知"
            nm.createNotificationChannel(ch)
        }
        val b = android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxy Agent 运行中")
            .setContentText("监听 0.0.0.0:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
        return b.build()
    }
}