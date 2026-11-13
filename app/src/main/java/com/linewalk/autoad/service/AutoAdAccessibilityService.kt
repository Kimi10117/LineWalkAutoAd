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
import android.view.accessibility.AccessibilityWindowInfo
import com.linewalk.autoad.config.AppConfig
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

    @Volatile
    var lastBackRejectReason: String = ""
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        Log.i(TAG, "AutoAdAccessibilityService 已連線啟動")
    }

    private fun isIgnoredOverlayPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val selfPkg = try {
            packageName
        } catch (_: Exception) {
            null
        }
        return (selfPkg != null && pkg == selfPkg) || AppConfig.isPassiveOverlayUtilityPackage(pkg)
    }

    fun clearBackRejectReason() {
        lastBackRejectReason = ""
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank() && !isIgnoredOverlayPackage(pkg)) {
            // 忽略我們自己的懸浮窗套件、SystemUI 與常駐動態島/輸入法覆蓋 App，紀錄底層真實前景 App 包名
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
     * 取得目前前景視窗的 Package Name（優先採用實際應用程式視窗，排除動態島等常駐覆蓋套件）。
     */
    fun getForegroundPackageName(): String? {
        val rootPkg = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
        if (!rootPkg.isNullOrBlank() && !isIgnoredOverlayPackage(rootPkg)) {
            return rootPkg
        }

        // 若活動視窗焦點暫被 DynamicSpot / 懸浮補助軟體佔據，改掃描 TYPE_APPLICATION 視窗尋找底層真實 App
        val appWindowPkg = try {
            val appWindows = windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .orEmpty()
            val prioritized = appWindows.sortedByDescending { if (it.isActive) 1 else 0 }
            prioritized.firstNotNullOfOrNull { win ->
                val pkg = win.root?.packageName?.toString()
                pkg?.takeIf { !isIgnoredOverlayPackage(it) }
            }
        } catch (_: Exception) {
            null
        }
        if (!appWindowPkg.isNullOrBlank()) {
            return appWindowPkg
        }

        return lastEventPackageName?.takeIf { !isIgnoredOverlayPackage(it) }
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
    suspend fun clickRect(rect: Rect, maxJitterPx: Int = 6, allowTopSafeZone: Boolean = false): Boolean {
        val safeJitterX = minOf(maxJitterPx, (rect.width() / 4).coerceAtLeast(1))
        val safeJitterY = minOf(maxJitterPx, (rect.height() / 4).coerceAtLeast(1))
        val dx = Random.nextInt(-safeJitterX, safeJitterX + 1)
        val dy = Random.nextInt(-safeJitterY, safeJitterY + 1)
        return performClick(
            x = (rect.centerX() + dx).toFloat(),
            y = (rect.centerY() + dy).toFloat(),
            jitterPx = 0,
            allowTopSafeZone = allowTopSafeZone
        )
    }

    /**
     * 在螢幕指定座標派發擬真點擊手勢。
     */
    suspend fun performClick(
        x: Float,
        y: Float,
        jitterPx: Int = 5,
        allowTopSafeZone: Boolean = false
    ): Boolean =
        suspendCancellableCoroutine { cont ->
            val maxW = if (lastCapturedWidth > 0) lastCapturedWidth else resources.displayMetrics.widthPixels
            val maxH = if (lastCapturedHeight > 0) lastCapturedHeight else resources.displayMetrics.heightPixels
            val offsetX = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            val offsetY = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            val targetX = (x + offsetX).coerceIn(5f, (maxW - 5).toFloat())
            val targetY = (y + offsetY).coerceIn(5f, (maxH - 5).toFloat())

            // R1: 頂部通知與即時動態安全禁區 (嚴格保護螢幕中央 x in 18%..82% 與 y < 8% 的通知/動態島區域，放行兩側角落廣告關閉/略過鈕)
            val isCenterNotificationIslandZone = targetX in (maxW * 0.18f)..(maxW * 0.82f) && targetY < maxH * 0.08f
            val statusBarSafeHeight = com.linewalk.autoad.vision.ScreenViewportCalibrator.getStatusBarSafeHeight(maxW, maxH)
            val isTopStatusBarEdge = targetY < statusBarSafeHeight.toFloat()
            if (!allowTopSafeZone && (isCenterNotificationIslandZone || isTopStatusBarEdge)) {
                Log.w(TAG, "[防護禁區] 攔截危險頂部中央通知/即時動態點擊 (targetX=$targetX, targetY=$targetY, safeH=$statusBarSafeHeight)，已拒絕派發手勢！")
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }

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
     * 1. 合法第三方廣告容器常態防護：預設 forceEscapeAdPackage = false 時嚴禁發送系統返回鍵以免中斷廣告或遺失獎勵；
     *    僅當 Watchdog 計時逾時且明確傳入 forceEscapeAdPackage = true 時，記錄 [計時強退] 警告並突破容器防護派發返回鍵。
     * 2. 散步趣遊戲本體防護：非 allowInGame 時嚴禁發送返回鍵以免退出散步趣回到手機桌面；僅在內部選單放行。
     * 3. 強制至少 minIntervalMs (預設 2000ms) 冷卻間隔，防止連續快速發送返回鍵擊穿介面。
     * 4. 單次發送 (SINGLE BACK KEY ONLY)，並於遭拒或系統派發失敗時將原因寫入 lastBackRejectReason。
     */
    fun safeNavigateBack(
        minIntervalMs: Long = 2000L,
        allowInGame: Boolean = false,
        forceEscapeAdPackage: Boolean = false
    ): Boolean {
        val fg = getForegroundPackageName()
        val fgLabel = fg ?: "unknown"
        var bypassingAdContainer = false

        // 1. 合法第三方廣告容器防護：預設禁止發送返回鍵以免中斷廣告；僅計時強退時放行
        if (com.linewalk.autoad.config.AppConfig.isAdPackage(fg)) {
            if (!forceEscapeAdPackage) {
                lastBackRejectReason = "AD_CONTAINER_PROTECT($fgLabel)"
                Log.w(TAG, "目前前景為合法第三方廣告容器 ($fgLabel)，絕對禁止發送系統返回鍵以免中斷廣告！")
                return false
            }
            bypassingAdContainer = true
            Log.w(TAG, "[計時強退] 廣告等待逾時突破容器防護，準備對第三方廣告容器 ($fgLabel) 強制派發系統返回鍵")
        }

        // 2. 遊戲本體選單防護：非 allowInGame 時嚴禁發送返回鍵
        if (!allowInGame && (
            com.linewalk.autoad.config.AppConfig.isGamePackage(fg) ||
            fg == "jp.naver.line.android" ||
            fg == "com.linecorp.LGWALK" ||
            fg == "com.linecorp.LGWKTW"
        )) {
            lastBackRejectReason = "GAME_PROTECT($fgLabel)"
            Log.w(TAG, "目前前景為散步趣 ($fgLabel)，禁止發送系統返回鍵以免退出散步趣！")
            return false
        }

        val now = System.currentTimeMillis()
        val elapsedMs = now - lastBackActionTimeMs
        if (elapsedMs < minIntervalMs) {
            lastBackRejectReason = "COOLDOWN(${elapsedMs}ms<${minIntervalMs}ms)"
            Log.d(TAG, "返回鍵冷卻中 (${elapsedMs}ms < ${minIntervalMs}ms)，跳過派發")
            return false
        }
        lastBackActionTimeMs = now
        if (bypassingAdContainer) {
            Log.w(TAG, "[計時強退] 突破容器防護派發單次系統返回鍵 (GLOBAL_ACTION_BACK), 前景: $fgLabel")
        } else {
            Log.i(TAG, "派發單次安全系統返回鍵 (GLOBAL_ACTION_BACK), 前景: $fgLabel")
        }
        val dispatched = performGlobalAction(GLOBAL_ACTION_BACK)
        if (!dispatched) {
            lastBackRejectReason = "SYSTEM_GLOBAL_ACTION_FAILED($fgLabel)"
            Log.w(TAG, "系統 performGlobalAction(GLOBAL_ACTION_BACK) 派發失敗, 前景: $fgLabel")
        } else {
            lastBackRejectReason = ""
        }
        return dispatched
    }

    /**
     * 當廣告或點擊誤跳轉至外部社群應用程式（如 Facebook 粉絲專頁 com.facebook.katana、Instagram 等）時，
     * 立即派發系統返回鍵退出外部社群 App 回到散步趣。
     */
    fun navigateBackFromExternalSocialApp(minIntervalMs: Long = 600L): Boolean {
        val now = System.currentTimeMillis()
        val elapsedMs = now - lastBackActionTimeMs
        if (elapsedMs < minIntervalMs) {
            lastBackRejectReason = "COOLDOWN(${elapsedMs}ms<${minIntervalMs}ms)"
            return false
        }
        lastBackActionTimeMs = now
        val fg = getForegroundPackageName()
        val fgLabel = fg ?: "unknown"
        Log.i(TAG, "派發外部社群應用返回鍵 (GLOBAL_ACTION_BACK), 前景: $fgLabel")
        val dispatched = performGlobalAction(GLOBAL_ACTION_BACK)
        if (!dispatched) {
            lastBackRejectReason = "SYSTEM_GLOBAL_ACTION_FAILED($fgLabel)"
        } else {
            lastBackRejectReason = ""
        }
        return dispatched
    }

    fun navigateBack(allowInGame: Boolean = false): Boolean {
        return safeNavigateBack(allowInGame = allowInGame)
    }

    suspend fun doubleNavigateBack(allowInGame: Boolean = false): Boolean {
        return safeNavigateBack(allowInGame = allowInGame)
    }

    /**
     * 強制將指定的應用程式（如《LINE 散步趣》com.linecorp.LGWKTW / com.linecorp.LGWALK）喚醒拉回前景。
     * 用於廣告播放逾時（如超過 40 秒卡死在無關閉鈕的惡意廣告頁面）時強制恢復主畫面。
     */
    fun bringAppToFront(targetPackage: String = "com.linecorp.LGWKTW"): Boolean {
        val candidates = listOf(targetPackage, "com.linecorp.LGWKTW", "com.linecorp.LGWALK")
        for (pkg in candidates) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (intent != null) {
                    startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * 執行系統 Home 鍵返回桌面（用於脫離吞噬系統返回鍵的遊戲內嵌廣告 Activity）。
     */
    fun pressHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 強制清棧拉回指定遊戲套件至前景，突破卡死在遊戲 Task 頂端的內嵌廣告 Activity：
     * - `clearTask = false`：使用 `FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_RESET_TASK_IF_NEEDED` 嘗試清除上層廣告頁。
     * - `clearTask = true`：升級使用 `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` 徹底重建遊戲主頁面 Task。
     */
    fun forceResetAppTaskToFront(
        targetPackage: String = "com.linecorp.LGWKTW",
        clearTask: Boolean = false
    ): Boolean {
        val candidates = listOf(targetPackage, "com.linecorp.LGWKTW", "com.linecorp.LGWALK")
        for (pkg in candidates) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    flags = if (clearTask) {
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    } else {
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    }
                }
                if (intent != null) {
                    startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    companion object {
        private const val TAG = "AutoAdAccService"

        private val _instance = MutableStateFlow<AutoAdAccessibilityService?>(null)
        val instance: StateFlow<AutoAdAccessibilityService?> = _instance.asStateFlow()
    }
}
