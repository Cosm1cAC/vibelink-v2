package com.wangzan101.vibelink.remote

/** A Bot must publish its selected model before the Hub binds a new Agent session. */
object BotSessionBootstrap {
    inline fun <T> create(synchronize: () -> Unit, start: () -> T): T {
        synchronize()
        return start()
    }
}
