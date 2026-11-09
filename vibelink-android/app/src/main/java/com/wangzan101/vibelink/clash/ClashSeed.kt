package com.wangzan101.vibelink.clash

import android.content.Context
import android.util.Log
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.withProfile
import com.wangzan101.vibelink.BuildConfig
import com.wangzan101.vibelink.net.ClashConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/** Managed built-in profile; imports finish before the UI can start VPN. */
object ClashSeed {
    private const val PREFS = "clash_seed"
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun ensure(context: Context) {
        if (BuildConfig.IS_BETA_EDITION) return
        scope.launch {
            try { prepare(context.applicationContext) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Log.w("vibelink-seed", "内置配置未就绪，可在网络设置重试") }
        }
    }

    /** Gateway fallback content changes with login; private seed stays unchanged. */
    fun reseed(context: Context) = ensure(context)

    suspend fun prepare(context: Context, restore: Boolean = false): String = withContext(Dispatchers.IO) {
        check(!BuildConfig.IS_BETA_EDITION) { "内测版不提供私人内置配置" }
        lock.withLock {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val content = ClashConfig.write(context).readBytes()
            val digest = hash(content)
            withTimeout(30_000) {
                withProfile {
                    val all = queryAll()
                    val ownedId = prefs.getString("managed_uuid", null)
                    val owned = all.firstOrNull { it.uuid.toString() == ownedId }
                    val active = queryActive()
                    if (!ClashSeedPolicy.needsImport(prefs.getString("content_hash", "") != digest, owned?.imported == true, restore)) {
                        if (active == null && owned != null) setActive(owned)
                        return@withProfile "开发版内置配置已就绪"
                    }
                    val oldFile = ownedId?.let { context.filesDir.resolve("imported/$it/config.yaml") }
                    val oldUnmodified = oldFile?.isFile == true &&
                        prefs.getString("installed_hash", null) == hash(oldFile.readBytes())
                    val legacyManaged = prefs.getBoolean("done_v3", false) && active?.name == "vibelink" && ownedId == null
                    val activate = ClashSeedPolicy.activate(restore, active?.uuid?.toString(), ownedId, oldUnmodified, legacyManaged)
                    // A failed import retains its pending UUID, so retry does not
                    // create duplicate profiles. Existing profiles are never overwritten.
                    val pendingId = prefs.getString("pending_uuid", null)
                    val pending = all.firstOrNull { it.uuid.toString() == pendingId && prefs.getString("pending_hash", "") == digest }
                    val uuid = pending?.uuid ?: create(Profile.Type.File, "VibeLink 开发内置 · ${BuildConfig.VERSION_NAME}")
                    check(prefs.edit().putString("pending_uuid", uuid.toString()).putString("pending_hash", digest).commit())
                    if (pending != null) patch(uuid, pending.name, pending.source, pending.interval, pending.ageSecretKey)
                    val directory = context.filesDir.resolve("pending/$uuid")
                    check(directory.mkdirs() || directory.isDirectory)
                    directory.resolve("config.yaml").writeBytes(content)
                    // CMA validates the configuration with the actual bundled core.
                    commit(uuid)
                    val imported = queryByUUID(uuid)?.takeIf { it.imported } ?: error("内核未确认配置导入")
                    val installed = context.filesDir.resolve("imported/$uuid/config.yaml")
                    if (activate) {
                        ServiceStore(context).apply { allowBypass = false; tunStackMode = "mixed" }
                        setActive(imported)
                    }
                    check(prefs.edit().putString("managed_uuid", uuid.toString()).putString("content_hash", digest)
                        .putString("installed_hash", hash(installed.readBytes()))
                        .remove("pending_uuid").remove("pending_hash").commit())
                    if (activate) "开发版内置配置已导入并选中" else "内置配置已更新，保留你当前选择的配置"
                }
            }
        }
    }
}
