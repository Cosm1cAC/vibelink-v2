package com.wangzan101.vibelink.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun parsesDesktopLink() {
        val link = PairLink.parse(
            "vibelink://pair?v=1&s=$secret&n=HAOSHAO&u=http://192.168.137.1:18995&u=http://100.98.206.126:18995/&u=https://hub.example.com",
        )!!
        assertEquals(secret, link.secret)
        assertEquals("HAOSHAO", link.host)
        assertEquals(
            listOf("http://192.168.137.1:18995", "http://100.98.206.126:18995", "https://hub.example.com"),
            link.urls,
        )
    }

    @Test
    fun rejectsOtherCodes() {
        assertNull(PairLink.parse(null))
        assertNull(PairLink.parse("https://example.com/?s=$secret&u=http://a"))
        assertNull(PairLink.parse("vibelink://pair?v=1&s=123456&u=http://a:1"))
        assertNull(PairLink.parse("vibelink://pair?v=1&s=$secret"))
        // 非 http(s) 地址丢掉；一个都不剩就整体无效
        assertNull(PairLink.parse("vibelink://pair?v=1&s=$secret&u=file:///etc/passwd&u=javascript:alert(1)"))
    }

    @Test
    fun sanitizesHostAndDedupes() {
        val link = PairLink.parse("vibelink://pair?s=$secret&n=a%20b%3Cc%3E&u=http://x:1&u=http://x:1/")!!
        assertEquals("abc", link.host)
        assertEquals(listOf("http://x:1"), link.urls)
    }

    @Test
    fun inboxIsSingleUse() {
        assert(PairInbox.offer("vibelink://pair?s=$secret&u=http://x:1"))
        assertEquals(secret, PairInbox.take()?.secret)
        assertNull(PairInbox.take())
        assert(!PairInbox.offer("hello"))
    }
}
