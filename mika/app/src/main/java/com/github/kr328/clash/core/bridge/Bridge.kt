package com.github.kr328.clash.core.bridge

import android.content.Context
import android.os.Build
import android.util.Log
import com.wangzan101.mika.BuildConfig
import java.io.File

/**
 * mihomo 内核的 JNI 入口。
 *
 * 符号名写死为 `Java_com_github_kr328_clash_core_bridge_Bridge_*`，
 * 对应 C 侧 `core/src/main/cpp/main.c`（原样取自 ClashMetaForAndroid 的桥接层）。
 * 改包名就必须改 C 里的符号名，别动。
 */
object Bridge {
    private const val TAG = "vibelink-core"

    init {
        System.loadLibrary("clash_bridge")
    }

    @Volatile
    var version: String = ""
        private set

    /** 必须在调用其它 native 方法之前调一次。返回内核版本号。 */
    fun init(context: Context): String {
        val home = File(context.filesDir, "clash").apply { mkdirs() }
        nativeInit(home.absolutePath, BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)
        version = nativeCoreVersion()
        Log.i(TAG, "clash core ready: $version (home=${home.absolutePath}, sdk=${Build.VERSION.SDK_INT})")
        return version
    }

    external fun nativeInit(home: String, versionName: String, sdkVersion: Int)
    external fun nativeCoreVersion(): String

    external fun nativeLoad(completable: kotlinx.coroutines.CompletableDeferred<Any?>, path: String)
    /** 返回 0 表示成功，1 表示失败（Go 侧 tun.Start 的错误码）。 */
    external fun nativeStartTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        cb: TunInterface,
    ): Int
    external fun nativeStopTun()
    external fun nativeQueryTunnelState(): String
}



