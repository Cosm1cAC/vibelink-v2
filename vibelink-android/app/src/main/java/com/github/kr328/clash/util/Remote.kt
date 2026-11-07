package com.github.kr328.clash.util

import android.os.DeadObjectException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * 拿服务 binder。系统把 :background 杀掉后 [Remote.service.remote] 会一直是空，
 * 原实现会无限等待（表现为点了启动没反应、也没有 VPN 授权框）。
 * 这里加超时并主动重连一次，再失败就抛出让界面显示原因。
 */
private suspend fun awaitRemote(): IRemoteService {
    withTimeoutOrNull(2_000) { Remote.service.remote.get() }?.let { return it }

    Log.w("Remote service unavailable, rebinding")
    runCatching { Remote.service.bind() }

    return withTimeoutOrNull(3_000) { Remote.service.remote.get() }
        ?: throw IllegalStateException("Clash 服务未连接（后台进程可能被系统回收）")
}

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T {
    while (true) {
        val remote = awaitRemote()
        val client = remote.clash()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic")

            Remote.service.remote.reset(remote)
        }
    }
}

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T {
    while (true) {
        val remote = awaitRemote()
        val client = remote.profile()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic")

            Remote.service.remote.reset(remote)
        }
    }
}
