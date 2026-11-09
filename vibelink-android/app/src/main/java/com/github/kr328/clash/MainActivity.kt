package com.github.kr328.clash

import com.wangzan101.vibelink.R
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import com.wangzan101.vibelink.R as DesignR

class MainActivity : BaseActivity<MainDesign>() {
    @Volatile
    private var pendingTargetState: Pair<Boolean, Long>? = null
    private val authRefreshLock = kotlinx.coroutines.sync.Mutex()
    private val probeLock = kotlinx.coroutines.sync.Mutex()
    private val reconnectLock = kotlinx.coroutines.sync.Mutex()
    private var connectionGeneration = 0L
    private var lastExitProbeAt = 0L
    private var exitReachable: Boolean? = null
    private var exitProbeProfile: String? = null
    private val exitProbeClient = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()

    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        // 服务未就绪时 withClash/withProfile 会一直等；放到后台，别挡住下面的请求循环
        launch {
            if (!com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
                runCatching { com.wangzan101.vibelink.clash.ClashSeed.prepare(this@MainActivity) }
            }
            runCatching { design.fetch() }
                .onFailure { android.util.Log.w("clash-ui", "fetch failed: " + it.message) }
        }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ProfileChanged) connectionGeneration++
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged ->
                            launch { runCatching { design.fetch() } }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    if (it == MainDesign.Request.ToggleStatus) connectionGeneration++
                    when (it) {
                        MainDesign.Request.ToggleStatus -> launch {
                            val target = !clashRunning
                            pendingTargetState = target to System.currentTimeMillis()
                            Remote.broadcasts.clashRunning = target
                            runCatching { design.setClashRunning(target) }
                            if (target) {
                                try {
                                    design.startClash()
                                } catch (t: Throwable) {
                                    pendingTargetState = null
                                    Remote.broadcasts.clashRunning = false
                                    runCatching { design.setClashRunning(false) }
                                    runCatching {
                                        design.showToast("启动失败：" + t.message, ToastDuration.Long)
                                    }
                                }
                            } else {
                                stopClashService()
                            }
                        }
                        MainDesign.Request.AuthorizeTailscale -> launch {
                            val url = runCatching { if (managedProfileSelected()) withClash { queryTailscaleAuthUrl() } else null }.getOrNull()
                                ?.let { com.wangzan101.vibelink.clash.TailscaleAuthCache.allowedUrl(it) }
                            if (url == null) {
                                design.showToast("授权链接已失效，请重新检测日本出口", ToastDuration.Long)
                            } else {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                                    .onFailure { design.showToast("无法打开授权页面", ToastDuration.Long) }
                            }
                            design.refreshTailscale()
                        }
                        MainDesign.Request.ReconnectExit -> launch {
                            if (!reconnectLock.tryLock()) return@launch
                            try {
                                val generation = ++connectionGeneration
                                val profile = withProfile { queryActive()?.uuid }
                                suspend fun stillCurrent() = uiStore.enableVpn && managedProfileSelected() &&
                                    withProfile { queryActive()?.uuid } == profile && generation == connectionGeneration
                                if (!stillCurrent() || !ownVpnActive()) {
                                    design.showToast("请先启用本应用的 VPN 模式，再重新连接出口", ToastDuration.Long)
                                    return@launch
                                }
                                try {
                                    stopClashService()
                                    kotlinx.coroutines.withTimeout(20_000) {
                                        while (Remote.broadcasts.refreshStatus()) {
                                            if (!stillCurrent()) throw kotlinx.coroutines.CancellationException("连接操作已变更")
                                            delay(200)
                                        }
                                    }
                                    if (!stillCurrent()) return@launch
                                    exitReachable = null
                                    lastExitProbeAt = 0L
                                    design.startClash()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (stillCurrent()) design.showToast("重新连接失败，请稍后重试", ToastDuration.Long)
                                }
                            } finally { reconnectLock.unlock() }
                        }
                        MainDesign.Request.ProbeTailscale -> launch { design.probeManagedExit() }
                        MainDesign.Request.CopyTailscaleAuth -> launch {
                            val url = runCatching { if (managedProfileSelected()) withClash { queryTailscaleAuthUrl() } else null }.getOrNull()
                                ?.let { com.wangzan101.vibelink.clash.TailscaleAuthCache.allowedUrl(it) }
                            if (url == null) design.showToast("授权链接已失效，请重新检测日本出口", ToastDuration.Long)
                            else {
                                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Tailscale 设备授权", url)
                                clip.description.extras = android.os.PersistableBundle().apply {
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                                clipboard.setPrimaryClip(clip)
                                design.showToast("已复制，可在已联网电脑完成授权，然后返回手机检测连接", ToastDuration.Long)
                            }
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        launch { runCatching { design.fetchTraffic(); design.refreshTailscale() } }
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        val actualRunning = Remote.broadcasts.refreshStatus()
        val pending = pendingTargetState
        val effectiveRunning = if (pending != null && System.currentTimeMillis() - pending.second < 4000L) {
            if (pending.first == actualRunning) {
                pendingTargetState = null
            }
            pending.first
        } else {
            pendingTargetState = null
            actualRunning
        }
        Remote.broadcasts.clashRunning = effectiveRunning
        setClashRunning(effectiveRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())
        refreshTailscale()

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private fun ownVpnActive(): Boolean {
        if (!uiStore.enableVpn) return false
        val manager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return runCatching { manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true && caps.ownerUid == android.os.Process.myUid()
        } }.getOrDefault(false)
    }

    private suspend fun managedProfileSelected(): Boolean {
        if (com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION || !com.wangzan101.vibelink.BuildConfig.HAS_DEV_CLASH_SEED) return false
        val managed = getSharedPreferences("clash_seed", MODE_PRIVATE).getString("managed_uuid", null) ?: return false
        return withProfile { queryActive()?.uuid?.toString() == managed }
    }

    private suspend fun MainDesign.refreshTailscale() {
        if (!authRefreshLock.tryLock()) return
        try {
            val running = Remote.broadcasts.refreshStatus()
            val url = withClash { queryTailscaleAuthUrl() }
            val managed = managedProfileSelected()
            val vpn = ownVpnActive()
            val selected = withProfile { queryActive()?.uuid?.toString() }
            if (!running || !managed || !vpn || selected != exitProbeProfile) {
                lastExitProbeAt = 0L; exitReachable = null; exitProbeProfile = selected
            }
            setTailscaleState(url != null && managed, running && managed,
                com.wangzan101.vibelink.clash.ExitProbeStatus.text(probeLock.isLocked, url != null, exitReachable, vpn))
            if (running && managed && vpn && (lastExitProbeAt == 0L || android.os.SystemClock.elapsedRealtime() - lastExitProbeAt >= 30_000)) {
                launch { runCatching { probeManagedExit() } }
            }
        } finally { authRefreshLock.unlock() }
    }

    private suspend fun MainDesign.probeManagedExit() {
        if (!probeLock.tryLock()) return
        try {
            val generation = connectionGeneration
            val profile = withProfile { queryActive()?.uuid }
            if (!managedProfileSelected() || !Remote.broadcasts.refreshStatus() || !ownVpnActive()) return
            lastExitProbeAt = android.os.SystemClock.elapsedRealtime()
            setTailscaleState(withClash { queryTailscaleAuthUrl() } != null, true, "正在检测 YouTube 连接…")
            // An explicit NO_PROXY request is captured by Android VPN, never by
            // the app's optional Hub/PC HTTP proxy. HTTPS verifies the real exit.
            val reachable = withContext(Dispatchers.IO) {
                try {
                    exitProbeClient.newCall(okhttp3.Request.Builder().url("https://www.youtube.com/").head().build()).execute().use { it.isSuccessful }
                } catch (_: java.io.IOException) { false }
            }
            if (withProfile { queryActive()?.uuid } == profile && Remote.broadcasts.refreshStatus() && ownVpnActive() && generation == connectionGeneration) exitReachable = reachable
        } finally {
            probeLock.unlock()
            runCatching { refreshTailscale() }
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        if (!com.wangzan101.vibelink.BuildConfig.IS_BETA_EDITION) {
            runCatching { com.wangzan101.vibelink.clash.ClashSeed.prepare(this@MainActivity) }
        }
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            pendingTargetState = null
            Remote.broadcasts.clashRunning = false
            setClashRunning(false)
            showToast(DesignR.string.no_profile_selected, ToastDuration.Long) {
                setAction(DesignR.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    startClashService()
                } else {
                    pendingTargetState = null
                    Remote.broadcasts.clashRunning = false
                    setClashRunning(false)
                }
            }
        } catch (e: Exception) {
            pendingTargetState = null
            Remote.broadcasts.clashRunning = false
            setClashRunning(false)
            design?.showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setupShortcuts()
    }

    private fun setupShortcuts() {
        // Skip dynamic shortcut setup when the app icon is hidden.
        if (uiStore.hideAppIcon) return

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION

        val toggle = ShortcutInfoCompat.Builder(this, "toggle_clash")
            .setShortLabel(getString(DesignR.string.shortcut_toggle_short))
            .setLongLabel(getString(DesignR.string.shortcut_toggle_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_all))
            .setIntent(
                Intent(Intents.ACTION_TOGGLE_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(0)
            .build()

        val start = ShortcutInfoCompat.Builder(this, "start_clash")
            .setShortLabel(getString(DesignR.string.shortcut_start_short))
            .setLongLabel(getString(DesignR.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_on))
            .setIntent(
                Intent(Intents.ACTION_START_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(1)
            .build()

        val stop = ShortcutInfoCompat.Builder(this, "stop_clash")
            .setShortLabel(getString(DesignR.string.shortcut_stop_short))
            .setLongLabel(getString(DesignR.string.shortcut_stop_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_toggle_off))
            .setIntent(
                Intent(Intents.ACTION_STOP_CLASH)
                    .setClassName(this, ExternalControlActivity::class.java.name)
                    .addFlags(flags)
            )
            .setRank(2)
            .build()

        ShortcutManagerCompat.setDynamicShortcuts(this, listOf(toggle, start, stop))
    }
}
