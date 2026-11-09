package com.github.kr328.clash.common.log

object Log {
    private fun redact(message: String) = com.wangzan101.vibelink.clash.TailscaleAuthCache.redact(message)
    private fun safe(error: Throwable?): Throwable? = error?.let {
        RuntimeException(redact(it.toString())).apply { stackTrace = it.stackTrace }
    }
    private const val TAG = "ClashMetaForAndroid"

    fun i(message: String, throwable: Throwable? = null) =
        android.util.Log.i(TAG, redact(message), safe(throwable))

    fun w(message: String, throwable: Throwable? = null) =
        android.util.Log.w(TAG, redact(message), safe(throwable))

    fun e(message: String, throwable: Throwable? = null) =
        android.util.Log.e(TAG, redact(message), safe(throwable))

    fun d(message: String, throwable: Throwable? = null) =
        android.util.Log.d(TAG, redact(message), safe(throwable))

    fun v(message: String, throwable: Throwable? = null) =
        android.util.Log.v(TAG, redact(message), safe(throwable))

    fun f(message: String, throwable: Throwable) =
        android.util.Log.wtf(TAG, redact(message), safe(throwable))
}
