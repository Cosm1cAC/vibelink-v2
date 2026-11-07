package com.wangzan101.vibelink.about

import org.junit.Assert.*
import org.junit.Test

class ReleaseEditionTest {
    private val release = """{"tag_name":"v1.5.2","assets":[
        {"name":"vibelink-1.5.2-dev.apk","browser_download_url":"https://example.test/dev.apk"},
        {"name":"vibelink-1.5.2-beta.apk","browser_download_url":"https://example.test/beta.apk"}
    ]}"""

    @Test fun eachEditionSelectsItsOwnPackage() {
        assertEquals("https://example.test/dev.apk", Releases.fromGitHub(release, betaEdition = false).apkUrl)
        assertEquals("https://example.test/beta.apk", Releases.fromGitHub(release, betaEdition = true).apkUrl)
    }

    @Test fun incompatibleManifestCannotOfferAnUpdate() {
        val devOnly = """{"tag_name":"v1.5.2","edition":"dev","assets":[{"name":"vibelink-dev.apk","browser_download_url":"https://example.test/dev.apk"}]}"""
        assertThrows(IllegalArgumentException::class.java) { Releases.fromGitHub(devOnly, betaEdition = true) }
    }
}
