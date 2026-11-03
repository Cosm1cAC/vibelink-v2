package com.proxystack.agent

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var info: TextView

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Proxy Agent"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        info = TextView(this).apply { textSize = 13f }
        root.addView(info)

        root.addView(Button(this).apply {
            text = "启动代理"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, AgentService::class.java))
            }
        })
        root.addView(Button(this).apply {
            text = "停止代理"
            setOnClickListener { stopService(Intent(this@MainActivity, AgentService::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "本机自测 /health"
            setOnClickListener {
                val msg = runCatching {
                    val s = java.net.Socket("127.0.0.1", AgentService.PORT)
                    s.soTimeout = 3000
                    s.getOutputStream().apply {
                        write("GET /health HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    val text = s.getInputStream().bufferedReader().readText()
                    s.close()
                    text.replace("\r\n", " | ").take(200)
                }.getOrElse { "ERR: $it" }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        })

        setContentView(root)
        h.post(tick)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun refresh() {
        status.text = if (AgentService.running) {
            "状态: 运行中 · 监听 0.0.0.0:${AgentService.PORT}"
        } else {
            "状态: 已停止（点「启动代理」）"
        }
        info.text = buildString {
            append("令牌: ").append(AgentService.TOKEN).append('\n')
            append("本机 IP（PC 用这些连我）:\n")
            localIps().forEach { append("  ").append(it).append('\n') }
        }
    }

    private fun localIps(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}