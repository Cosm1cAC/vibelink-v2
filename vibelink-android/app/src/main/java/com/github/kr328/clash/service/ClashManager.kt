package com.github.kr328.clash.service

import android.content.Context
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
        @Synchronized fun ensureCapture(context: Context) {
            if (captureJob?.isActive == true) return
            val application = context.applicationContext
            val store = ServiceStore(application)
            captureJob = captureScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val receiver = Clash.subscribeLogcat()
                try {
                    for (item in receiver) {
                        logRelay.accept(
                            item.copy(message = com.wangzan101.vibelink.clash.TailscaleAuthCache.redact(item.message)),
                            item.message, store.activeProfile?.toString(), StatusProvider.serviceRunning
                        )
                    }
                } finally { receiver.cancel() }
            }
            if (receiverRegistered) return
            receiverRegistered = true
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    if (intent?.action == com.github.kr328.clash.common.constants.Intents.ACTION_CLASH_STOPPED) tailscaleAuth.clear()
                    else tailscaleAuth.profileLoaded(store.activeProfile?.toString())
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(com.github.kr328.clash.common.constants.Intents.ACTION_CLASH_STOPPED)
                addAction(com.github.kr328.clash.common.constants.Intents.ACTION_PROFILE_LOADED)
            }
            androidx.core.content.ContextCompat.registerReceiver(application, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
    init { ensureCapture(context) }

    override fun queryTailscaleAuthUrl(): String? = tailscaleAuth.current(store.activeProfile?.toString(), StatusProvider.serviceRunning)

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