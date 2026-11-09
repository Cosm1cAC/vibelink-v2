package com.wangzan101.vibelink.clash

import org.junit.Assert.*
import org.junit.Test

class ClashSeedPolicyTest {
    @Test fun unchangedImportDoesNotRewriteManualEdits() {
        assertFalse(ClashSeedPolicy.needsImport(false, true, false))
        assertTrue(ClashSeedPolicy.needsImport(true, true, false))
        assertTrue(ClashSeedPolicy.needsImport(false, false, false))
    }

    @Test fun upgradesPreserveSelectedCustomAndEditedProfiles() {
        assertFalse(ClashSeedPolicy.activate(false, "custom", "builtin", true, false))
        assertFalse(ClashSeedPolicy.activate(false, "builtin", "builtin", false, false))
        assertTrue(ClashSeedPolicy.activate(false, "builtin", "builtin", true, false))
        assertTrue(ClashSeedPolicy.activate(false, null, "builtin", false, false))
        assertTrue(ClashSeedPolicy.activate(true, "custom", "builtin", false, false))
    }

    @Test fun legacySeedCanMigrateWithoutDiscardingIt() {
        assertTrue(ClashSeedPolicy.activate(false, "legacy", null, false, true))
        assertFalse(ClashSeedPolicy.activate(false, "same-name-custom", null, false, false))
    }
}
