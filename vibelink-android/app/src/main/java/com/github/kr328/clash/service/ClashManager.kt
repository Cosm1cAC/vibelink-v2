package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    companion object {
        // tsnet logs each authorization URL only once. Capturing must outlive
        // the bound UI service, including the browser authorization round trip.
        private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val tailscaleAuth = com.wangzan101.vibelink.clash.TailscaleAuthCache(android.os.SystemClock::elapsedRealtime)
        private val logRelay = com.wangzan101.vibelink.clash.TailscaleLogRelay<LogMessage>(tailscaleAuth)
        private var captureJob: Job? = null
        private var receiverRegistered = false
        private val restartPending = java.util.concurrent.atomic.AtomicBoolean(false)
        @Synchronized fun ensureCapture(context: Context) {
            if (captureJob?.isActive == true) return
            val application = context.applicationContext
            captureJob = captureScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val receiver = Clash.subscribeLogcat()
                try {
                    for (item in receiver) {
                        logRelay.acceptCaptured(
                            item.copy(message = com.wangzan101.vibelink.clash.TailscaleAuthCache.redact(item.message)),
                            item.message, StatusProvider.serviceRunning, item.time.time
                        )
                    }
                } finally { receiver.cancel() }
            }
            if (receiverRegistered) return
            receiverRegistered = true
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    if (intent?.action == com.github.kr328.clash.common.constants.Intents.ACTION_CLASH_STOPPED) tailscaleAuth.stopped()
                    else if (intent?.action == com.github.kr328.clash.common.constants.Intents.ACTION_PROFILE_LOADED) {
                        tailscaleAuth.profileLoaded(intent.getStringExtra(com.github.kr328.clash.common.constants.Intents.EXTRA_UUID))
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(com.github.kr328.clash.common.constants.Intents.ACTION_CLASH_STOPPED)
                addAction(com.github.kr328.clash.common.constants.Intents.ACTION_PROFILE_LOADED)
            }
            androidx.core.content.ContextCompat.registerReceiver(application, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        fun beginProfileLoad(profile: java.util.UUID) = tailscaleAuth.beginLoad(profile.toString())
        fun commitProfileLoad(load: com.wangzan101.vibelink.clash.TailscaleAuthCache.Load) = tailscaleAuth.commitLoad(load)
        fun failProfileLoad(load: com.wangzan101.vibelink.clash.TailscaleAuthCache.Load) = tailscaleAuth.failLoad(load)
    }
    init { ensureCapture(context) }

    override fun queryTailscaleAuthUrl(): String? = tailscaleAuth.current(store.activeProfile?.toString(), StatusProvider.serviceRunning)

    override fun queryRuntimeProcessId(): Int = android.os.Process.myPid()

    /** UI and login live in another process; only the stopped managed core may self-exit. */
    override fun restartManagedAuthorization(expectedProfile: String): Boolean {
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid() || !mayRestartManagedAuthorization(expectedProfile)) return false
        if (!restartPending.compareAndSet(false, true)) return false
        captureScope.launch {
            try {
                delay(200)
                // Profile selection or a fresh VPN start can supersede the request.
                // Service.onCreate runs on this thread too, so a new VPN cannot
                // interleave between the stopped check and the self-exit.
                withContext(Dispatchers.Main.immediate) {
                    if (mayRestartManagedAuthorization(expectedProfile)) android.os.Process.killProcess(android.os.Process.myPid())
                }
            } finally { restartPending.set(false) }
        }
        return true
    }

    private fun mayRestartManagedAuthorization(expectedProfile: String): Boolean = runCatching {
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION || !com.wangzan101.vibelink.BuildConfig.HAS_DEV_CLASH_SEED) return@runCatching false
        val processName = (context.applicationContext as? android.app.Application)?.currentProcessName
        com.wangzan101.vibelink.clash.ManagedAuthorizationRestartPolicy.allows(
            com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION,
            com.wangzan101.vibelink.BuildConfig.HAS_DEV_CLASH_SEED,
            expectedProfile, store.activeProfile?.toString(), freshManagedProfile(),
            StatusProvider.serviceRunning, processName, context.packageName)
    }.getOrDefault(false)

    /** The seed is written by the UI process. Never trust a cached SharedPreferences view. */
    private fun freshManagedProfile(): String? = runCatching {
        val directory = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        val file = java.io.File(directory, "clash_seed.xml")
        // A backup indicates an in-progress or interrupted atomic preferences write.
        if (java.io.File(directory, "clash_seed.xml.bak").exists() || file.length() !in 1L..1_048_576L) return@runCatching null
        file.inputStream().use { input ->
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setInput(input, "UTF-8")
            var value: String? = null
            var event = parser.eventType
            while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (event == org.xmlpull.v1.XmlPullParser.DOCDECL) return@runCatching null
                if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if (parser.depth == 1 && parser.name != "map") return@runCatching null
                    if (parser.depth == 2 && parser.getAttributeValue(null, "name") == "managed_uuid") {
                        if (parser.name != "string" || value != null) return@runCatching null
                        value = parser.nextText()
                    }
                }
                event = parser.nextToken()
            }
            value?.takeIf { java.util.UUID.fromString(it).toString() == it }
        }
    }.getOrNull()

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        return Clash.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        logRelay.setObserver(observer?.let { value -> { item: LogMessage -> value.newItem(item) } })
    }
}
