package com.wangzan101.vibelink.clash

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ClashIdentityStorageTest {
    @Test fun repeatedStartupUpgradeAndManagedRestoreRetainPersistentIdentity() {
        val root=Files.createTempDirectory("vibelink-identity-test").toFile()
        try {
            val home=ClashIdentityStorage.prepareHome(root)
            val state=File(home, ClashIdentityStorage.STATE_DIRECTORY)
            val identity=File(state,"tailscaled.state")
            val bytes=byteArrayOf(1,4,9,16,25)
            identity.writeBytes(bytes)
            val machine=File(state,"machine-key").apply { writeText("test-persisted-identity") }
            // Managed-profile changes only replace profile/config files, never the core home.
            for (profile in listOf("old-uuid", "upgrade-uuid", "restore-uuid")) {
                File(root,"imported/$profile").mkdirs()
                val restarted=ClashIdentityStorage.prepareHome(root)
                File(restarted,"config.yaml").writeText("managed profile: $profile")
                assertEquals(home.canonicalFile,restarted.canonicalFile)
                assertArrayEquals(bytes,identity.readBytes())
                assertEquals("test-persisted-identity",machine.readText())
            }
            assertFalse(state.canonicalPath.contains("imported"))
        } finally { root.deleteRecursively() }
    }
    @Test fun invalidStorageNeverDeletesIdentityOrSilentlyStartsFresh() {
        val root=Files.createTempDirectory("vibelink-identity-test").toFile()
        try {
            val home=File(root,"clash").apply { mkdirs() }
            val invalid=File(home,ClashIdentityStorage.STATE_DIRECTORY).apply { writeText("retain me") }
            try { ClashIdentityStorage.prepareHome(root);fail("Expected storage failure") }
            catch (_:IllegalStateException) { assertEquals("retain me",invalid.readText()) }
        } finally { root.deleteRecursively() }
    }
}
