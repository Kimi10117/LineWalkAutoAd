package com.linewalk.autoad.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Android 11 (API 30+) 無障礙核心服務：
 * 1. 使用 takeScreenshot() 免每次詢問螢幕錄製權限即可擷取畫面。
 * 2. 使用 dispatchGesture() 派發帶有隨機座標微調 (Jitter) 與隨機按壓時間的擬真點擊。
 * 3. 提供 UI 節點樹查詢與系統返回鍵 (GLOBAL_ACTION_BACK) 防迷路控制。
 */
class AutoAdAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastEventPackageName: String? = null

    @Volatile
    var lastCapturedWidth: Int = 0

    @Volatile
    var lastCapturedHeight: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        Log.i(TAG, "AutoAdAccessibilityService 已連線啟動")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) {
            // 忽略我們自己的懸浮窗套件名稱，紀錄底層真實前景 App 包名
            lastEventPackageName = pkg
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoAdAccessibilityService 被系統中斷")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        _instance.value = null
        Log.i(TAG, "AutoAdAccessibilityService 已解除綁定")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        _instance.value = null
        super.onDestroy()
    }

    /**
     * 取得目前前景視窗的 Package Name。
     */
    fun getForegroundPackageName(): String? {
        val rootPkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        if (!rootPkg.isNullOrBlank() && rootPkg != packageName) {
            return rootPkg
        }
        return lastEventPackageName
    }

    /**
     * 取得目前活動視窗的無障礙根節點 (供第一層節點樹檢查使用)。
     */
    fun getSafeRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 使用 Android 11+ (API 30) 內建無障礙截圖 API 擷取全螢幕軟體 Bitmap (ARGB_8888)。
     * 注意：系統限制呼叫頻率約每秒 1~2 次，狀態機預設 2.2 秒呼叫一次完全符合規範。
     */
    suspend fun captureScreenBitmap(): Bitmap? = withTimeoutOrNull(2500L) {
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap?.recycle()
                                softBitmap?.let {
                                    lastCapturedWidth = it.width
                                    lastCapturedHeight = it.height
                                }
                                if (cont.isActive) {
                                    cont.resume(softBitmap)
                                } else {
                                    softBitmap?.recycle()
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "轉換 Screenshot HardwareBuffer 失敗", t)
                                if (cont.isActive) cont.resume(null)
                            } finally {
                                hardwareBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "takeScreenshot 失敗，errorCode=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "呼叫 takeScreenshot 發生例外", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /**
     * 點擊目標矩形區域中心，並自動加入隨機偏移與隨機按壓時長 (65ms ~ 135ms)。
     */
    suspend fun clickRect(rect: Rect, maxJitterPx: Int = 6): Boolean {
        val safeJitterX = minOf(maxJitterPx, (rect.width() / 4).coerceAtLeast(1))
        val safeJitterY = minOf(maxJitterPx, (rect.height() / 4).coerceAtLeast(1))
        val dx = Random.nextInt(-safeJitterX, safeJitterX + 1)
        val dy = Random.nextInt(-safeJitterY, safeJitterY + 1)
        return performClick(
            x = (rect.centerX() + dx).toFloat(),
            y = (rect.centerY() + dy).toFloat(),
            jitterPx = 0
        )
    }

    /**
     * 在螢幕指定座標派發擬真點擊手勢。
     */
    suspend fun performClick(x: Float, y: Float, jitterPx: Int = 5): Boolean =
        suspendCancellableCoroutine { cont ->
            val maxW = if (lastCapturedWidth > 0) lastCapturedWidth else resources.displayMetrics.widthPixels
            val maxH = if (lastCapturedHeight > 0) lastCapturedHeight else resources.displayMetrics.heightPixels
            val offsetX = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            val offsetY = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            val targetX = (x + offsetX).coerceIn(5f, (maxW - 5).toFloat())
            val targetY = (y + offsetY).coerceIn(5f, (maxH - 5).toFloat())
            val durationMs = Random.nextLong(65L, 138L)

            val path = Path().apply {
                moveTo(targetX, targetY)
                lineTo(targetX, targetY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "手勢點擊被取消: ($targetX, $targetY)")
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                Log.w(TAG, "dispatchGesture 回傳 false: ($targetX, $targetY)")
                cont.resume(false)
            }
        }

    /**
     * 在螢幕上派發平滑滑動手勢 (例如在好友排行榜向下滾動清單)。
     */
    suspend fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L
    ): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null
        )
        if (!dispatched && cont.isActive) {
            cont.resume(false)
        }
    }

    @Volatile
    private var lastBackActionTimeMs: Long = 0L

    /**
     * 安全發送單次系統返回鍵：
     * 1. 強制至少 2000ms 冷卻間隔，防止連續快速發送返回鍵擊穿廣告層。
     * 2. 單次發送 (SINGLE BACK KEY ONLY)，絕不連擊雙重返回鍵，杜絕退回手機桌面。
     */
    fun safeNavigateBack(minIntervalMs: Long = 2000L): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastBackActionTimeMs < minIntervalMs) {
            Log.d(TAG, "返回鍵冷卻中 (${now - lastBackActionTimeMs}ms < ${minIntervalMs}ms)，跳過派發")
            return false
        }
        lastBackActionTimeMs = now
        Log.i(TAG, "派發單次安全系統返回鍵 (GLOBAL_ACTION_BACK)")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun navigateBack(): Boolean {
        return safeNavigateBack()
    }

    suspend fun doubleNavigateBack(): Boolean {
        return safeNavigateBack()
    }

    /**
     * 強制將指定的應用程式（如《LINE 散步趣》com.linecorp.LGWALK）喚醒拉回前景。
     * 用於廣告播放逾時（如超過 40 秒卡死在無關閉鈕的惡意廣告頁面）時強制恢復主畫面。
     */
    fun bringAppToFront(targetPackage: String = "com.linecorp.LGWALK"): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (intent != null) {
                startActivity(intent)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "AutoAdAccService"

        private val _instance = MutableStateFlow<AutoAdAccessibilityService?>(null)
        val instance: StateFlow<AutoAdAccessibilityService?> = _instance.asStateFlow()
    }
}
