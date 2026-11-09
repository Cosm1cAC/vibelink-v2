package com.wangzan101.vibelink.clash

import java.io.File

/** App-private identity is independent of APK version and imported profile UUID.
 * Never clear this home when installing/restoring a managed configuration.
 * The existing tsnet identity, rather than the enrollment key, authenticates restarts.
 */
internal object ClashIdentityStorage {
    const val STATE_DIRECTORY = "tailscale-vibelink-dev"
    fun prepareHome(filesDir: File): File {
        val home = File(filesDir, "clash")
        check(home.mkdirs() || home.isDirectory) { "Clash private storage unavailable" }
        val state = File(home, STATE_DIRECTORY)
        check(state.mkdirs() || state.isDirectory) { "Tailscale private storage unavailable" }
        return home
    }
}
