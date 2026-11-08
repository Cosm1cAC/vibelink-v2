package com.wangzan101.vibelink.clash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.service.store.ServiceStore
import com.wangzan101.vibelink.net.ClashConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 首次启动时把 vibelink 的出口配置种成 CMA 的一个 profile。
 *
 * 只留 Clash 这套 VPN 之后，面板里默认没有配置（Profile: Not selected）。
 * 这里在后台把 `ClashConfig` 生成的 YAML 导入并设为当前配置，用户打开面板
 * 点一下就能启动，不需要手动新建。
 *
 * 只做一次；之后用户在面板里增删配置不受影响。
 */
object ClashSeed {
    private const val TAG = "vibelink-seed"
    private const val PREFS = "clash_seed"
    private const val KEY = "done_v3"
    private const val PROFILE_NAME = "vibelink"

    /** 配对 / 换 token 之后重写一次 Clash 配置（网关头里的 X-Mesh-Token 要跟着换）。 */
    fun reseed(context: Context) {
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        ensure(context)
    }

    fun ensure(context: Context) {
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ServiceStore(context).apply {
                    allowBypass = false
                    tunStackMode = "mixed"
                }
                withRemote(context) { remote ->
                    val profileManager = remote.profile()
                    val all = profileManager.queryAll()
                    val existing = all.firstOrNull { it.name == PROFILE_NAME }
                    if (existing == null && all.isEmpty()) {
                        val uuid = profileManager.create(Profile.Type.File, PROFILE_NAME)
                        val pending = context.filesDir.resolve("pending").resolve(uuid.toString())
                        pending.mkdirs()
                        pending.resolve("config.yaml").writeText(ClashConfig.write(context).readText())
                        profileManager.commit(uuid)
                        profileManager.queryByUUID(uuid)?.let { profileManager.setActive(it) }
                        prefs.edit().putBoolean(KEY, true).apply()
                        Log.i(TAG, "seeded profile $uuid")
                    } else if (existing != null) {
                        val uuid = existing.uuid
                        profileManager.patch(uuid, PROFILE_NAME, existing.source, existing.interval, existing.ageSecretKey)
                        val pending = context.filesDir.resolve("pending").resolve(uuid.toString())
                        pending.mkdirs()
                        pending.resolve("config.yaml").writeText(ClashConfig.write(context).readText())
                        profileManager.commit(uuid)
                        if (profileManager.queryActive() == null || profileManager.queryActive()?.uuid == uuid) {
                            profileManager.queryByUUID(uuid)?.let { profileManager.setActive(it) }
                        }
                        prefs.edit().putBoolean(KEY, true).apply()
                        Log.i(TAG, "upgraded existing profile $uuid to $KEY")
                    } else {
                        if (profileManager.queryActive() == null) {
                            profileManager.setActive(all.first())
                            Log.i(TAG, "activated existing profile ${all.first().uuid}")
                        }
                        prefs.edit().putBoolean(KEY, true).apply()
                    }
                }
            }.onFailure {
                Log.w(TAG, "seed failed: ${it.message}", it)
            }
        }
    }

    private suspend fun <T> withRemote(context: Context, block: suspend (IRemoteService) -> T): T =
        suspendCancellableCoroutine { continuation ->
            var connection: ServiceConnection? = null

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                    val remote = binder.unwrap(IRemoteService::class)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            continuation.resume(block(remote))
                        } catch (t: Throwable) {
                            continuation.resumeWithException(t)
                        } finally {
                            runCatching { context.unbindService(connection!!) }
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // 服务被杀：本次种子放弃，下次启动再试
                }
            }

            val intent = Intent(context, RemoteService::class.java)
            runCatching {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }.onFailure {
                continuation.resumeWithException(it)
            }
        }
}
