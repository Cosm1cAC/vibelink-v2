package com.wangzan101.mika.net

import android.content.Context
import com.wangzan101.mika.remote.RemoteStore
import java.io.File

/**
 * 给 mihomo 用的最小配置。
 * 手机侧只做 TUN / DNS / 分流：
 *   - 100.x（tailnet）→ pchub：PC 直接转发（PC 本来就在 tailnet 里）
 *   - 其余 → pcexit：PC 再链到笔记本 mihomo，由笔记本的 HY2 出网
 * HY2 是 QUIC，手机经 TCP 网关拨不了号，所以出口链在 PC 上终结。
 */
object ClashConfig {
    private const val FALLBACK_HOST = "192.168.137.1"
    private const val FALLBACK_TOKEN = "000001a18bc5616395b46d2347c7ba7c"

    internal fun hubHost(context: Context): String {
        val hub = RemoteStore.hub(context)
        return runCatching { java.net.URI(hub).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_HOST
    }

    fun write(context: Context): File {
        val host = hubHost(context)
        val token = RemoteStore.token(context).ifBlank { FALLBACK_TOKEN }
        val file = File(context.filesDir, "clash/config.yaml")
        file.parentFile?.mkdirs()
        file.writeText(
            """
            mixed-port: 7890
            allow-lan: false
            mode: rule
            log-level: info
            ipv6: false

            dns:
              enable: true
              ipv6: false
              listen: 0.0.0.0:1053
              enhanced-mode: fake-ip
              fake-ip-range: 198.18.0.1/16
              default-nameserver:
                - 223.5.5.5
                - 1.1.1.1
              nameserver:
                - https://dns.alidns.com/dns-query
                - https://1.1.1.1/dns-query

            proxies:
              # 直达 PC 的 tailnet（100.x 地址）
              - name: pchub
                type: http
                server: 192.168.5.2
                port: 18996
                headers:
                  X-Mesh-Token: $token

              # 出口：PC 再链到笔记本 mihomo(7897) → HY2 → 外网
              - name: pcexit
                type: http
                server: 192.168.5.2
                port: 18997
                headers:
                  X-Mesh-Token: $token

            proxy-groups:
              - name: PROXY
                type: select
                proxies:
                  - pcexit
                  - DIRECT

            rules:
              - IP-CIDR,100.64.0.0/10,pchub,no-resolve
              - GEOIP,PRIVATE,DIRECT,no-resolve
              - MATCH,PROXY
            """.trimIndent(),
        )
        return file
    }
}





