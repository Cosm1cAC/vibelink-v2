package com.wangzan101.vibelink.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserIdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun userBrowsingDefaultsToMobile() {
        assertTrue(BrowserIdentity.mobile(loginForVibeLink = false, agentMobile = false, userDesktop = false))
        // Agent 那边用哪一版不影响平常浏览
        assertTrue(BrowserIdentity.mobile(loginForVibeLink = false, agentMobile = true, userDesktop = false))
    }

    @Test
    fun perSiteOverrideSwitchesToDesktop() {
        assertFalse(BrowserIdentity.mobile(loginForVibeLink = false, agentMobile = true, userDesktop = true))
    }

    @Test
    fun loginForVibeLinkUsesTheAgentIdentity() {
        assertFalse(BrowserIdentity.mobile(loginForVibeLink = true, agentMobile = false, userDesktop = false))
        assertTrue(BrowserIdentity.mobile(loginForVibeLink = true, agentMobile = true, userDesktop = true))
    }

    @Test
    fun onlyNotLoggedInFavoritesGoThroughLogin() {
        assertTrue(BrowserIdentity.loginFlow(FavoriteSite("example.com", loggedIn = false)))
        assertFalse(BrowserIdentity.loginFlow(FavoriteSite("example.com", loggedIn = true)))
    }

    @Test
    fun userSiteModesFollowParentDomainAndPersist() {
        val m = UserSiteModes(tmp.root)
        assertFalse(m.isDesktop("example.com"))
        m.set("www.example.com", true)
        assertTrue(m.isDesktop("example.com"))
        assertTrue(m.isDesktop("m.example.com"))
        assertFalse(m.isDesktop("example.org"))
        assertTrue(UserSiteModes(tmp.root).isDesktop("shop.example.com"))
        // 在子域名上换回手机版:连上级的记录一起去掉
        m.set("m.example.com", false)
        assertFalse(m.isDesktop("example.com"))
        assertFalse(UserSiteModes(tmp.root).isDesktop("m.example.com"))
    }
}
