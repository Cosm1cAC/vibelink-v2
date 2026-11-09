package com.wangzan101.vibelink.clash

/** Decisions use ownership and content, never a user-editable profile name. */
internal object ClashSeedPolicy {
    fun needsImport(contentChanged: Boolean, imported: Boolean, restore: Boolean) =
        contentChanged || !imported || restore

    fun activate(explicit: Boolean, active: String?, owned: String?, ownedUnmodified: Boolean, legacyManaged: Boolean): Boolean =
        explicit || active == null || (active == owned && ownedUnmodified) || legacyManaged
}
