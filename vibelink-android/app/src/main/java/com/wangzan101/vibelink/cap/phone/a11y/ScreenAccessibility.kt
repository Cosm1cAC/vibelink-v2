package com.wangzan101.vibelink.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.app.Notification
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.vibelink.core.uistate.ScreenEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** VibeLink 的无障碍服务:按屏读取窗口、在指定屏上做手势、按屏分发事件。 */
open class ScreenAccessibility : AccessibilityService() {

    private class DisplayEvent(val displayId: Int, val event: AccessibilityEvent)

    // 事件只作观察用,订阅方处理不过来时丢最旧的,不阻塞系统回调
    private val events = MutableSharedFlow<DisplayEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** 窗口变了(加上、拿掉、挪动,包括输入法弹出收起):只是个信号,订阅方自己去读窗口。 */
    private val windowChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val notifications = MutableSharedFlow<ScreenEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 不少 App 把显示结果的文字控件标成「对无障碍不重要」,不读它们就看不到结果;配置文件里已声明,
        // 这里再按运行时补一次,防止系统沿用旧的服务配置
        serviceInfo?.let { info ->
            if (info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS == 0) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                serviceInfo = info
            }
        }
        _instance.value = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        _instance.compareAndSet(this, null)
        overlayHost?.closeAll()
        overlayHost = null
    }

    private var overlayHost: OverlayHost? = null

    /** 在用户屏幕上画浮层用的宿主(只在主线程用);服务断开时它加的窗口一起拆掉。 */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            autoDismissOemAccessibilityDialog(event)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) windowChanges.tryEmit(Unit)
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && notifications.subscriptionCount.value > 0) {
            val notification = event.parcelableData as? Notification
            val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val body = notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val text = listOfNotNull(title, body).joinToString(" ").ifBlank { event.text.joinToString(" ") }
            notifications.tryEmit(ScreenEvent("notification", event.packageName?.toString(), text.take(2_000)))
        }
        // 没人订阅就不复制、不查屏号,事件很密
        if (events.subscriptionCount.value == 0) return
        val displayId = displayOf(event)
        if (displayId == Display.INVALID_DISPLAY) return
        // API 33 以下系统会在回调返回后回收事件对象,订阅方异步读取前先复制一份
        events.tryEmit(DisplayEvent(displayId, AccessibilityEvent(event)))
    }

    override fun onInterrupt() = Unit

    /** 指定屏上的窗口;该屏没有窗口(或不存在)时为空列表。 */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> =
        windowsOnAllDisplays.get(displayId).orEmpty()

    /** 在指定屏上沿 [path] 做一次单指手势;完成返回 true,被取消或无法下发返回 false。 */
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        return gesture(displayId, listOf(path), durationMs)
    }

    suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean =
        dispatch(displayId, paths.map { GestureDescription.StrokeDescription(it, 0, durationMs.coerceAtLeast(1)) })

    /** 在同一点上连点 [count] 下(一次手势里先后几笔):每下按住 [holdMs],两下之间隔 [gapMs]。 */
    suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean {
        val at = Path().apply { moveTo(x, y) }
        return dispatch(displayId, (0 until count).map { i -> GestureDescription.StrokeDescription(at, i * (holdMs + gapMs), holdMs.coerceAtLeast(1)) })
    }

    private suspend fun dispatch(displayId: Int, strokes: List<GestureDescription.StrokeDescription>): Boolean {
        val gesture = GestureDescription.Builder()
            .setDisplayId(displayId)
            .apply { strokes.forEach(::addStroke) }
            .build()
        return suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (!dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
    }

    /** 截一张用户屏幕(主屏)的结果:画面,或屏上有防截屏窗口,或截不到(错误码)。 */
    sealed interface MainShot {
        class Ok(val bitmap: Bitmap) : MainShot
        data object Secure : MainShot
        data class Failed(val code: Int) : MainShot
    }

    /**
     * 截一张主屏(用户看着的那块屏,包括 VibeLink 自己的浮层:要不要藏起来由调用方定)。系统限制两次截图至少隔一会儿,
     * 太快时等一秒再截一次。画面是软件位图(可以读像素、编码)。
     */
    suspend fun takeMainScreenshot(): MainShot {
        val first = screenshotOnce()
        if (first !is MainShot.Failed || first.code != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return first
        kotlinx.coroutines.delay(SCREENSHOT_INTERVAL_MS)
        return screenshotOnce()
    }

    private suspend fun screenshotOnce(): MainShot = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching null
                        hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() }
                    }
                }.getOrNull()
                if (cont.isActive) cont.resume(shot?.let(MainShot::Ok) ?: MainShot.Failed(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR))
            }

            override fun onFailure(errorCode: Int) {
                // 屏上有防截屏窗口:安卓 12 起有专门的错误码;更早的系统截出来是黑的,由调用方看画面判断
                val secure = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                if (cont.isActive) cont.resume(if (secure) MainShot.Secure else MainShot.Failed(errorCode))
            }
        })
    }

    /**
     * 发生在指定屏上的无障碍事件。这条流属于当前这个服务实例:服务断开重连后它不再有事件,
     * 订阅方要从 [instance] 拿到新实例后重新订阅。
     */
    fun eventsFor(displayId: Int): Flow<AccessibilityEvent> =
        events.filter { it.displayId == displayId }.map { it.event }

    fun notificationEvents(): Flow<ScreenEvent> = notifications

    /** 窗口变了的信号(见 [windowChanges]);属于当前这个服务实例。 */
    fun windowChanges(): Flow<Unit> = windowChanges

    /**
     * 事件所在的屏。API 33 起事件自带屏号;更早的系统按事件的窗口号去各屏窗口里找,
     * 找不到(如没有窗口号的通知类事件)就丢弃。
     */
    private fun displayOf(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return event.displayId
        val windowId = event.windowId
        if (windowId == -1) return Display.INVALID_DISPLAY
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) {
            if (all.valueAt(i).any { it.id == windowId }) return all.keyAt(i)
        }
        return Display.INVALID_DISPLAY
    }

    /**
     * ColorOS / OPlus / 国产 ROM 会定期弹出「偵測到『vibelink』取得無障礙權限 / 检测到获取无障碍权限」安全警告框,
     * 若未及时点击「保持開啟 / 保持开启」,系统可能超时关闭无障碍服务。这里识别该系统弹窗并自动点击保持开启。
     */
    private fun autoDismissOemAccessibilityDialog(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val isSystemDialogPkg = pkg.contains("permission", ignoreCase = true) ||
            pkg.contains("security", ignoreCase = true) ||
            pkg.contains("coloros", ignoreCase = true) ||
            pkg.contains("oplus", ignoreCase = true) ||
            pkg == "android" || pkg == "com.android.settings" || pkg == "com.android.systemui"
        if (!isSystemDialogPkg) return

        runCatching {
            val root = rootInActiveWindow ?: event.source ?: return
            if (!containsA11yWarningText(root)) return
            val keepOpenNode = findKeepOpenButton(root) ?: return
            var target: AccessibilityNodeInfo? = keepOpenNode
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            val clicked = (target ?: keepOpenNode).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                Log.i("VibeLinkA11y", "Auto-clicked keep-open on OEM accessibility security dialog ($pkg)")
            }
        }
    }

    private fun containsA11yWarningText(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString().orEmpty()
        if (text.contains("無障礙權限") || text.contains("无障碍权限") ||
            text.contains("關閉無障礙功能") || text.contains("关闭无障碍功能")
        ) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsA11yWarningText(child)) return true
        }
        return false
    }

    private fun findKeepOpenButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim().orEmpty()
        if (text in KEEP_OPEN_BUTTON_TEXTS) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findKeepOpenButton(child)?.let { return it }
        }
        return null
    }

    companion object {
        private const val EVENT_BUFFER = 64
        /** 两次截图之间系统要求的间隔(安卓原版是一秒)。 */
        private const val SCREENSHOT_INTERVAL_MS = 1_000L
        private val KEEP_OPEN_BUTTON_TEXTS = setOf("保持開啟", "保持开启", "繼續使用", "继续使用")

        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** 已连接的服务实例;服务未开启或已断开时为 null。 */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}
