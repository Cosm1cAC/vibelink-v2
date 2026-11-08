package com.wangzan101.vibelink.ui

import com.wangzan101.vibelink.remote.ClientSession
import com.wangzan101.vibelink.remote.LoginPolicy

/** Inputs of the real application gate, including current credential availability. */
internal object AuthGatePresentation {
    fun canShowProtected(ready: ClientSession?, stored: ClientSession?, credentialPresent: Boolean, now: Long): Boolean {
        val sameSession = ready != null && stored != null && ready.userId == stored.userId && ready.issuer == stored.issuer &&
            ready.edition == stored.edition && ready.sessionId == stored.sessionId
        return LoginPolicy.allowProtected(credentialPresent, sameSession, ready, now)
    }
}
