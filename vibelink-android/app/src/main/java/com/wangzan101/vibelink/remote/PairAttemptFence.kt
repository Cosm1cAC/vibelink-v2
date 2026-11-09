package com.wangzan101.vibelink.remote

/** A scan result can authorize only the same link that initiated it. */
internal class PairAttemptFence {
    data class Attempt(val revision: Long, val link: PairLink)
    private var revision = 0L
    private var current: Attempt? = null
    @Synchronized fun begin(link: PairLink): Attempt = Attempt(++revision, link).also { current = it }
    @Synchronized fun capture(link: PairLink): Attempt? = current?.takeIf { it.link == link }
    @Synchronized fun accepts(attempt: Attempt): Boolean = current == attempt
    @Synchronized fun cancel() { revision++; current = null }
}
