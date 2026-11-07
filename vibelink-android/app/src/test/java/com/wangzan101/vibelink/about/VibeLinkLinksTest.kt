package com.wangzan101.vibelink.about

import org.junit.Rule
import com.wangzan101.vibelink.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class VibeLinkLinksTest {
    @get:Rule val texts = ChineseTexts()

    private val device = VibeLinkLinks.Device("Google", "Pixel 8", "14", 34, "0.1.0", "65fcf92")

    @Test
    fun bodyHasThreePrefilledLines() {
        val lines = VibeLinkLinks.issueBody(device).lines()
        assertEquals("- 机型：Google Pixel 8", lines[0])
        assertEquals("- 安卓版本：14（SDK 34）", lines[1])
        assertEquals("- VibeLink 版本：0.1.0（构建 65fcf92）", lines[2])
        assertTrue(lines.contains("### 遇到了什么问题"))
        assertTrue(lines.contains("### 做了哪几步"))
        assertTrue(lines.contains("### 期望是什么样"))
        // 诊断信息不自动带上:只提示用户去哪里复制
        assertTrue(lines.contains("### 诊断信息"))
        assertTrue(lines.contains("（可选）在 VibeLink「关于 › 反馈问题 › 复制诊断信息」复制后粘贴在这里"))
    }

    /** 型号里已经带厂商名时不重复写。 */
    @Test
    fun modelNotDoubled() {
        val body = VibeLinkLinks.issueBody(device.copy(manufacturer = "samsung", model = "SAMSUNG SM-S9180"))
        assertTrue(body.startsWith("- 机型：SAMSUNG SM-S9180\n"))
    }

    @Test
    fun issueUrlIsEncoded() {
        val url = VibeLinkLinks.issueUrl(device)
        assertTrue(url.startsWith("https://github.com/Cosm1cAC/vibelink-v2/issues/new?title=&body="))
        val query = url.substringAfter('?')
        assertFalse("no raw spaces or newlines", query.any { it == ' ' || it == '\n' || it == '+' })
        assertFalse("no raw non-ASCII", query.any { it.code > 127 })
        // 能被当成合法 URI 解析,正文解码回原样
        URI(url)
        val body = URLDecoder.decode(query.substringAfter("body="), "UTF-8")
        assertEquals(VibeLinkLinks.issueBody(device), body)
    }

    @Test
    fun linksPointAtTheRepo() {
        assertEquals("https://github.com/Cosm1cAC/vibelink-v2#readme", VibeLinkLinks.README)
        assertEquals("https://github.com/wangzan101/Mika", VibeLinkLinks.UPSTREAM)
        assertEquals("http://10.0.0.2:18995/api/apk/latest", VibeLinkLinks.latestReleaseApi("http://10.0.0.2:18995/"))
    }

    /** 安装包清单是公开接口：任何内置地址都不能带 token（旧版把共享 token 写进了 APK）。 */
    @Test
    fun noBuiltInLinkCarriesAToken() {
        for (u in listOf(VibeLinkLinks.LATEST_RELEASE_API_PUBLIC, VibeLinkLinks.LATEST_RELEASE_API_GITHUB, VibeLinkLinks.latestReleaseApi("http://h:1"))) {
            assertFalse(u, u.contains("t="))
        }
    }
}
