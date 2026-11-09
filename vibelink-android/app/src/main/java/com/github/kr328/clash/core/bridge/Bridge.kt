package com.github.kr328.clash.core.bridge

import android.content.Context
import android.os.Build
import android.util.Log
import com.wangzan101.vibelink.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * mihomo 内核的 JNI 入口。
 *
 * 符号名写死为 `Java_com_github_kr328_clash_core_bridge_Bridge_*`，
 * 对应预编译的 `libclash_bridge.so`（取自 ClashMetaForAndroid 的桥接层）。
 * 改包名就必须改 C 里的符号名，别动。
 *
 * 完整 extern 列表来自 CMA core 的 Bridge（UI 依赖它们），
 * init/initLibrary 来自 vibelink 自己的封装。
 */
object Bridge {
    private const val TAG = "vibelink-core"

    init {
        System.loadLibrary("clash_bridge")
    }

    @Volatile
    var version: String = ""
        private set

    /** 必须在调用其它 native 方法之前执行一次，返回内核版本号。 */
    fun init(context: Context): String {
        val home = com.wangzan101.vibelink.clash.ClashIdentityStorage.prepareHome(context.filesDir)
        nativeInit(home.absolutePath, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)
        version = nativeCoreVersion()
        Log.i(TAG, "clash core ready: $version (home=${home.absolutePath}, sdk=${Build.VERSION.SDK_INT})")
        return version
    }

    external fun nativeInit(home: String, versionName: String, sdkVersion: Int)
    external fun nativeCoreVersion(): String

    external fun nativeReset()
    external fun nativeForceGc()
    external fun nativeSuspend(suspend: Boolean)

    external fun nativeQueryTunnelState(): String
    external fun nativeQueryTrafficNow(): Long
    external fun nativeQueryTrafficTotal(): Long

    external fun nativeNotifyDnsChanged(dnsList: String)
    external fun nativeNotifyTimeZoneChanged(name: String, offset: Int)
    external fun nativeNotifyInstalledAppChanged(uidList: String)

    /** 返回 0 表示成功、1 表示失败（Go 端 tun.Start 的返回码）。 */
    external fun nativeStartTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        cb: TunInterface,
    ): Int

    external fun nativeStopTun()

    external fun nativeStartHttp(listenAt: String): String?
    external fun nativeStopHttp()

    external fun nativeQueryGroupNames(excludeNotSelectable: Boolean): String
    external fun nativeQueryGroup(name: String, sort: String): String?
    external fun nativeHealthCheck(completable: CompletableDeferred<*>, name: String)
    external fun nativeHealthCheckAll()
    external fun nativePatchSelector(selector: String, name: String): Boolean

    external fun nativeFetchAndValid(
        completable: FetchCallback,
        path: String,
        url: String,
        force: Boolean
    )

    external fun nativeLoad(completable: CompletableDeferred<*>, path: String)

    external fun nativeQueryProviders(): String
    external fun nativeUpdateProvider(
        completable: CompletableDeferred<*>,
        type: String,
        name: String
    )

    external fun nativeReadOverride(slot: Int): String
    external fun nativeWriteOverride(slot: Int, content: String)
    external fun nativeClearOverride(slot: Int)
    external fun nativeQueryConfiguration(): String
    external fun nativeSubscribeLogcat(callback: LogcatInterface)

    external fun nativeSetAgeSecretKey(key: String?)
    external fun nativeGenX25519KeyPair(): String?
    external fun nativeGenHybridKeyPair(): String?
    external fun nativeVeritySecretKeys(secretKeys: String): Boolean
    external fun nativeToPublicKeys(secretKeys: String): String?
    external fun nativeVerityPublicKeys(publicKeys: String): Boolean
}
