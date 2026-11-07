package com.wangzan101.vibelink.net

import android.content.Context
import com.wangzan101.vibelink.remote.RemoteStore
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

    internal fun hubHost(context: Context): String {
        val hub = RemoteStore.hub(context)
        return runCatching { java.net.URI(hub).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_HOST
    }

    fun write(context: Context): File {
        val file = File(context.filesDir, "clash/config.yaml")
        file.parentFile?.mkdirs()
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
            // 公网内测版：绝不生成任何开发者个人网关或代理配置
            file.writeText("mixed-port: 7890\nallow-lan: false\nmode: direct\nrules:\n  - MATCH,DIRECT\n")
            return file
        }
        val host = hubHost(context)
        // 没配对就没有 token：网关会拒绝，出口不可用，配对后 [ClashSeed.reseed] 重写配置
        val token = RemoteStore.token(context)
        file.writeText(
            """
            mixed-port: 7890
            allow-lan: false
            mode: rule
            log-level: info
            ipv6: false
            unified-delay: true
            tcp-concurrent: true

            sniffer:
              enable: true
              force-dns-mapping: true
              parse-pure-ip: true
              override-destination: true
              sniff:
                HTTP:
                  ports: [80, 8080-8880]
                  override-destination: true
                TLS:
                  ports: [443, 8443]
                QUIC:
                  ports: [443, 8443]
              skip-domain:
                - "+.lan"
                - "+.local"
                - "Mijia Cloud"
              skip-dst-address:
                - 91.105.192.0/23
                - 91.108.0.0/16
                - 149.154.160.0/20
                - 185.76.151.0/24
                - 194.221.250.50/32

            dns:
              enable: true
              ipv6: false
              listen: 0.0.0.0:1053
              enhanced-mode: fake-ip
              fake-ip-range: 198.18.0.1/16
              fake-ip-filter:
                - "+.lan"
                - "+.local"
                - "+.localhost"
                - "+.ts.net"
                - "connectivitycheck.gstatic.com"
                - "captive.apple.com"
                - "+.pool.ntp.org"
                - "time.android.com"
              default-nameserver:
                - 223.5.5.5
                - 119.29.29.29
              nameserver:
                - https://dns.alidns.com/dns-query
                - https://doh.pub/dns-query

            proxies:
              # 直达 PC 的 tailnet（100.x 地址）
              - name: pchub
                type: http
                server: $host
                port: 18996
                headers:
                  X-Mesh-Token: $token

              # 出口：PC 再链到笔记本/本机 mihomo(7897) → 外网
              - name: pcexit
                type: http
                server: $host
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
              # pcexit 是 HTTP CONNECT (仅 TCP)，阻断 QUIC (UDP 443) 迫使 YouTube/Chrome 秒切 TCP 443
              - AND,((NETWORK,UDP),(DST-PORT,443)),REJECT
              # 阻断失效的国内 GGC 缓存域名，迫使 YouTube 立即回退到 googlevideo.com
              - DOMAIN-SUFFIX,xn--ngstr-lra8j.com,REJECT
              # 阻断常见内置 DoH，防止应用绕过 Fake-IP 直接拿被污染/纯 IP 连接
              - DOMAIN,dns.google,REJECT
              - DOMAIN,dns.google.com,REJECT
              - DOMAIN,mozilla.cloudflare-dns.com,REJECT
              - DOMAIN,cloudflare-dns.com,REJECT
              - IP-CIDR,100.64.0.0/10,pchub,no-resolve
              - GEOIP,PRIVATE,DIRECT,no-resolve
              - MATCH,PROXY
            """.trimIndent(),
        )
        return file
    }
}





