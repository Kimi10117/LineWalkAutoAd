package com.linewalk.autoad.engine

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.ui.FloatingOverlayService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.ScreenViewportCalibrator
import com.linewalk.autoad.vision.TemplateMatcher
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

sealed class AdStepResult {
    data class RedirectRecovered(val externalPkg: String) : AdStepResult()
    data object AdChoicesDismissed : AdStepResult()
    data class AdLoading(val elapsedSec: Int) : AdStepResult()
    data class StillCountingDown(val remainingSec: Int, val elapsedSec: Int) : AdStepResult()
    data class WaitingGracePeriod(val elapsedSec: Int) : AdStepResult()
    data class ClickedCloseOrSkip(val sourceLabel: String, val bounds: Rect) : AdStepResult()
    data class ReturnedToGame(val confirmBounds: Rect?, val label: String) : AdStepResult()
    data class WatchdogBackTriggered(val elapsedSec: Int, val actionLabel: String = "返回鍵") : AdStepResult()
    data class SwipedToReveal(val attempt: Int, val elapsedSec: Int) : AdStepResult()
    data class ScanningAd(val elapsedSec: Int) : AdStepResult()
    data class ForceBringGameToFront(val elapsedSec: Int, val foregroundPkg: String, val reason: String) : AdStepResult()
}

/**
 * 通用多階段廣告關閉處理器 (AdCloserHandler)：
 * - 只有當真正偵測到《LINE 散步趣》主畫面 (isLineWalkMainScreenVisible) 時才判定廣告結束，
 *   不會因為廣告畫面內出現「邀請好友」「雙方都能獲得幣」而提早跳出廣告監控！
 * - 內建升級版幾何角落 X 偵測器，支援深色圓底白 X 與細線 X，並排除 AdChoices『ⓘ ▷』圖示。
 */
class AdCloserHandler(
    private val ocrRecognizer: OcrRecognizer,
    private val nodeInspector: NodeInspector,
    private val templateMatcher: TemplateMatcher,
    private val config: AppConfig
) {

    private var adStartTimeMs: Long = 0L
    private var closeClickCount: Int = 0
    private var adChoicesDismissCount: Int = 0
    private var redirectCount: Int = 0
    private var hasRecentRedirect: Boolean = false
    private var socialLandingPageTicks: Int = 0
    private var socialLandingPageTotalRecoveries: Int = 0
    private var googlePlayPillClickCount: Int = 0
    private var lastGooglePlayPillClickTimeMs: Long = 0L
    private var wasAdLoading: Boolean = false
    private var hasAdActuallyShown: Boolean = false
    private var adLoadingTicks: Int = 0
    private var adLoadStartedAtMs: Long = 0L
    private var adLoadElapsedSec: Int = 0
    private var lastObservedCountdownSec: Int? = null
    private var staleCountdownTicks: Int = 0
    private var lastCountdownSecForDiag: Int? = null
    private var lastClickedPoint: Point? = null
    private var lastClickedAdLabel: String? = null
    private var lastBackActionTimeMs: Long = 0L
    private var lastFallbackCornerTimeMs: Long = 0L
    private var lastRevealSwipeTimeMs: Long = 0L
    private var revealSwipeCount: Int = 0
    private var fallbackCornerCycle: Int = 0
    private var fastForwardSkipClicks: Int = 0
    private var leftCornerGeoXClicks: Int = 0
    private var leftCornerRedirectCount: Int = 0
    private var disableLeftCornerForCurrentAd: Boolean = false
    private var sponsoredTopLeftProbeCount: Int = 0
    private var watchdogForceBackAttempts: Int = 0
    private var linePromoCloseClicks: Int = 0
    private var lastLeftCornerClickTimeMs: Long = 0L
    private var lastRedirectReturnTimeMs: Long = 0L
    private var lastBackRejectLogTimeMs: Long = 0L
    private var lastLoggedRejectReason: String = ""
    private val redirectPointCounts = mutableListOf<Point>()
    private val clickedHistory = mutableListOf<Point>()
    private val blacklistedPoints = mutableListOf<Point>()

    fun onEnterAdMode(preserveRedirectState: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!preserveRedirectState || adStartTimeMs == 0L || now - adStartTimeMs > 120_000L) {
            adStartTimeMs = now
            socialLandingPageTicks = 0
            socialLandingPageTotalRecoveries = 0
            googlePlayPillClickCount = 0
            lastGooglePlayPillClickTimeMs = 0L
            wasAdLoading = false
            hasAdActuallyShown = false
            adLoadingTicks = 0
            adLoadStartedAtMs = 0L
            adLoadElapsedSec = 0
            lastObservedCountdownSec = null
            staleCountdownTicks = 0
            lastCountdownSecForDiag = null
            lastClickedPoint = null
            lastClickedAdLabel = null
            lastBackActionTimeMs = 0L
            lastFallbackCornerTimeMs = 0L
            lastRevealSwipeTimeMs = 0L
            revealSwipeCount = 0
            fallbackCornerCycle = 0
            fastForwardSkipClicks = 0
            leftCornerGeoXClicks = 0
            leftCornerRedirectCount = 0
            sponsoredTopLeftProbeCount = 0
            watchdogForceBackAttempts = 0
            linePromoCloseClicks = 0
            lastLeftCornerClickTimeMs = 0L
            lastRedirectReturnTimeMs = 0L
            lastBackRejectLogTimeMs = 0L
            lastLoggedRejectReason = ""
            redirectPointCounts.clear()
            clickedHistory.clear()
            blacklistedPoints.clear()
        }
        if (!preserveRedirectState) {
            closeClickCount = 0
            adChoicesDismissCount = 0
            redirectCount = 0
            hasRecentRedirect = false
            disableLeftCornerForCurrentAd = false
            lastClickedAdLabel = null
            sponsoredTopLeftProbeCount = 0
            watchdogForceBackAttempts = 0
            lastRedirectReturnTimeMs = 0L
            lastBackRejectLogTimeMs = 0L
            lastLoggedRejectReason = ""
        }
    }

    fun resetClickBlacklist() {
        blacklistedPoints.clear()
        clickedHistory.clear()
    }

    /**
     * 標記剛發生外部跳轉 (瀏覽器 / Play 商店 / 社群 App)：
     * - 若最近一次點擊位於左上角區域 (x < 25% sw 且 y < 20% sh)，或 6 秒內剛點擊過左上角幾何 X，
     *   立即對目前這支廣告永久封鎖整個左上角區域，杜絕同一支廣告在相鄰座標反覆誤點跳轉瀏覽器。
     */
    fun markRecentRedirect(clickedPoint: Point? = null, fromExternalApp: Boolean = false) {
        val pt = clickedPoint ?: lastClickedPoint
        val sw = currentScreenWidth
        val sh = currentScreenHeight
        val clickedLeftCorner = pt != null && sw > 0 && sh > 0 &&
            pt.x < (sw * 0.25f).toInt() && pt.y < (sh * 0.20f).toInt()
        val leftCornerWindowMs = if (fromExternalApp) 10000L else 6000L
        val recentLeftCornerClick = lastLeftCornerClickTimeMs > 0L &&
            System.currentTimeMillis() - lastLeftCornerClickTimeMs < leftCornerWindowMs
        if (clickedLeftCorner || recentLeftCornerClick) {
            leftCornerRedirectCount++
            // 允許冷卻後重試 1 次精確幾何 X，若累積達 2 次跳轉則永久封鎖左上角
            if (leftCornerRedirectCount >= 2) {
                disableLeftCornerForCurrentAd = true
            }
        }
        hasRecentRedirect = true
        redirectCount++
        lastRedirectReturnTimeMs = System.currentTimeMillis()

        // 依使用者指導：部分互動廣告必須跳轉商店/瀏覽器並返回才能略過，因此不盲目清空亦不隨意永久封鎖！
        // 僅當同一個座標區域 (依螢幕寬度動態換算半徑，1080px 基準約 64.8px 對應原 65.0px) 連續累積達 3 次無效跳轉時，才暫時加入黑名單進行節流保護
        if (pt != null) {
            redirectPointCounts.add(pt)
            val radiusPx = getBlacklistRadiusPx(if (sw > 0) sw else 1080)
            val nearbyRedirects = redirectPointCounts.count { p ->
                hypot((p.x - pt.x).toDouble(), (p.y - pt.y).toDouble()) < radiusPx
            }
            if (nearbyRedirects >= 3) {
                blacklistedPoints.add(pt)
            }
        }
        lastClickedPoint = null
    }

    /** 目前廣告是否已封鎖左上角掃描（供測試與診斷使用） */
    fun isLeftCornerDisabledForCurrentAd(): Boolean = disableLeftCornerForCurrentAd

    /**
     * 取得目前廣告有效的 Watchdog 最大等待秒數（以設定值為基準，並於載入緩衝期給予安全裕度）。
     */
    fun getEffectiveWatchdogLimitSec(baseLimitSec: Int): Int {
        val safeBase = baseLimitSec.coerceAtLeast(20)
        return if (wasAdLoading) safeBase + 15 else safeBase
    }

    /**
     * 回傳供 [狀態Snap] 診斷日誌使用的廣告關閉器內部欄位摘要。
     */
    fun getDiagnosticsSnapshotFields(): String {
        val blkPreview = if (blacklistedPoints.isEmpty()) {
            ""
        } else {
            val pts = blacklistedPoints.take(5).joinToString(",") { "(${it.x},${it.y})" }
            if (blacklistedPoints.size > 5) "$pts,..." else pts
        }
        val lastClickStr = lastClickedPoint?.let { "(${it.x},${it.y})" } ?: "none"
        val cdStr = lastCountdownSecForDiag?.toString() ?: "none"
        return "redir=$redirectCount | blk=${blacklistedPoints.size}[$blkPreview] | lastClick=$lastClickStr | load=${adLoadElapsedSec}s | cd=$cdStr"
    }

    /**
     * 取得最近一次點擊的廣告關閉／略過目標標籤（供外部跳轉防護判斷是否為預期略過按鈕）。
     */
    fun getLastClickedAdLabel(): String? = lastClickedAdLabel

    /**
     * 依螢幕寬度動態換算點擊黑名單半徑（1080px 基準約 64.8px，對應原固定 65.0px；小螢幕保底 28.0px）。
     */
    fun getBlacklistRadiusPx(sw: Int): Double = kotlin.math.max(28.0, sw * 0.060)

    private fun logThrottledBackReject(service: AutoAdAccessibilityService, contextLabel: String) {
        val now = System.currentTimeMillis()
        val reason = service.lastBackRejectReason.ifBlank { "UNKNOWN" }
        if (reason != lastLoggedRejectReason || now - lastBackRejectLogTimeMs >= 10_000L) {
            lastLoggedRejectReason = reason
            lastBackRejectLogTimeMs = now
            Log.w(TAG, "[返回攔截] $contextLabel 遭拒，原因: $reason")
        }
    }

    private fun isInLeftCornerZone(x: Int, y: Int): Boolean {
        val sw = currentScreenWidth
        val sh = currentScreenHeight
        if (sw <= 0 || sh <= 0) return false
        return x < (sw * 0.25f).toInt() && y < (sh * 0.20f).toInt()
    }

    fun getElapsedSeconds(): Int {
        if (adStartTimeMs == 0L) return 0
        return ((System.currentTimeMillis() - adStartTimeMs) / 1000L).toInt()
    }

    private var currentScreenWidth: Int = 0
    private var currentScreenHeight: Int = 0
    private var currentEffectiveBottomY: Int = 0
    private var currentHasThreeButtonNavBar: Boolean = false
    private var isCurrentAdTopSponsored: Boolean = false
    private var topSponsoredBottomY: Int = 0
    private var topSponsoredCenterY: Int = 0

    private fun isInvalidOrBlacklisted(x: Int, y: Int): Boolean {
        if (FloatingOverlayService.isInsideOverlay(x, y)) return true
        if (currentScreenHeight > 0 && currentScreenWidth > 0) {
            val sw = currentScreenWidth
            val sh = currentScreenHeight
            // 傳統三大金剛按鍵安全禁區：嚴禁點擊底部系統返回/首頁/多工導覽列
            if (currentHasThreeButtonNavBar && y >= currentEffectiveBottomY - (sh * 0.005f).toInt()) {
                return true
            }
            // 排除中央動態島與系統通知安全禁區 (x in 18%..82% sw, y < 8% sh)
            if (x in (sw * 0.18f).toInt()..(sw * 0.82f).toInt()) {
                if (y < (sh * 0.08f).toInt()) return true
            }
            if (isCurrentAdTopSponsored) {
                // Meta Sponsored 頂部白底橫幅模式 (同時支援 3M y≈0.029h 與 Trip.com y≈0.067h)：
                // 1) 頂部系統狀態列邊界保護
                if (y < (sh * 0.015f).toInt()) return true
                // 2) 放行左側獨立 X 關閉欄 (x in 2.5%..12.0% sw, y in 3.5%..10.0% sh)
                val isLeftCloseZone = x in (sw * 0.025f).toInt()..(sw * 0.120f).toInt() &&
                    y in (sh * 0.035f).toInt()..(sh * 0.100f).toInt()
                if (isLeftCloseZone) {
                    // 放行，不列入靜態黑名單禁區
                } else {
                    // 3) 嚴格封鎖品牌圓形頭像 (Trip. / 3M) 與標題文字跳轉區 (x in 12.0%..85.0% sw 且 y < 15.0% sh)
                    if (x in (sw * 0.120f).toInt()..(sw * 0.850f).toInt() && y < (sh * 0.150f).toInt()) return true
                    // 4) 嚴格封鎖左側過深區域 (避免點到廣告主卡片頂端)
                    if (x < (sw * 0.120f).toInt() && y in (sh * 0.105f).toInt()..(sh * 0.150f).toInt()) return true
                    // 5) 嚴格封鎖右上角靜音喇叭與 AdChoices 圖示 (x > 78% sw 且 y < 12.0% sh)
                    if (x > (sw * 0.78f).toInt() && y < (sh * 0.120f).toInt()) return true
                }
            } else {
                if (y < (sh * 0.024f).toInt()) return true
            }
        }
        val now = System.currentTimeMillis()
        val radiusPx = getBlacklistRadiusPx(if (currentScreenWidth > 0) currentScreenWidth else 1080)
        // 重定向冷卻節流緩衝 (3500ms)：若剛從外部重定向返回，且目標點為最近引發跳轉之座標，暫時抑制點擊以留出畫面渲染時間
        if (now - lastRedirectReturnTimeMs < 3500L) {
            val isRecentRedirectPt = redirectPointCounts.any { pt ->
                hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < radiusPx
            }
            if (isRecentRedirectPt) return true
        }

        return blacklistedPoints.any { pt ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < radiusPx
        }
    }

    suspend fun processAdTick(
        service: AutoAdAccessibilityService,
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): AdStepResult {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        currentScreenWidth = sw
        currentScreenHeight = sh
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)
        currentEffectiveBottomY = vp.effectiveBottomY
        currentHasThreeButtonNavBar = vp.hasThreeButtonNavBar
        val topSponsoredItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy < 0.12f && (
                t.contains("Sponsored", ignoreCase = true) ||
                t.contains("贊助") ||
                (cy < 0.09f && t.contains("Trip.com", ignoreCase = true))
            )
        }
        isCurrentAdTopSponsored = topSponsoredItem != null || ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy < 0.12f && (
                t.contains("Sponsored", ignoreCase = true) ||
                t.contains("贊助")
            )
        }
        topSponsoredBottomY = topSponsoredItem?.bounds?.bottom ?: (sh * 0.085f).toInt()
        topSponsoredCenterY = if (topSponsoredItem != null) {
            topSponsoredItem.bounds.centerY().coerceIn((sh * 0.045f).toInt(), (sh * 0.075f).toInt())
        } else {
            (sh * 0.067f).toInt()
        }
        val elapsedSec = getElapsedSeconds()

        // 0. 最優先檢查：是否跳出「無法載入廣告 / 請稍後再試。」錯誤彈窗
        val adErrorClose = ocrRecognizer.findAdLoadErrorCloseButton(screenBitmap, ocrItems)
        if (adErrorClose != null) {
            hasAdActuallyShown = false
            recordAndClick(service, adErrorClose.second, allowBlacklist = false, label = "廣告載入失敗彈窗")
            return AdStepResult.ReturnedToGame(adErrorClose.second, "廣告載入失敗彈窗")
        }

        // 1A. 防迷路檢查：若誤觸跳轉到 Google Play 商店或外部瀏覽器/Chrome Custom Tabs，立即按 BACK 並標記跳轉恢復
        val fgPkg = service.getForegroundPackageName()
        val isAd = fgPkg != null && AppConfig.isAdPackage(fgPkg)
        val isGame = fgPkg != null && (AppConfig.isGamePackage(fgPkg) || fgPkg == "jp.naver.line.android")
        val isExternal = fgPkg != null && AppConfig.isRedirectPackage(fgPkg) && !isAd
        val hasBrowserTitle = !isAd && !isGame && ocrItems.any { item ->
            val cy = item.bounds.centerY()
            cy < sh * 0.16f && (
                item.text.contains("正在載入") ||
                item.text.contains("klook") ||
                item.text.contains(".com") ||
                item.text.contains("http") ||
                item.text.contains("www.")
            )
        }

        val isSocialAppPkg = fgPkg != null && (
            fgPkg.contains("facebook", ignoreCase = true) ||
            fgPkg.contains("instagram", ignoreCase = true)
        ) && !AppConfig.isAdPackage(fgPkg)

        val hasGameOrSubDialogMarkers = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("中獎囉") || t.contains("分享給好友") || t.contains("抽抽樂") ||
            t.contains("每日賓果") || t.contains("今日賓果") || t.contains("獲得票券") ||
            t.contains("獲得金幣") || t.contains("好友排行榜")
        }
        val hasTopSocialHeader = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh
            cy < 0.18f && (
                t.contains("SOGO") || t.contains("粉絲專頁") || t.contains("貼文") ||
                t.contains("Reel") || (t.contains("Facebook") && !t.contains("fromMeta", ignoreCase = true))
            )
        }
        val hasBottomSocialBar = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            cy > 0.65f && item.text.contains("讚")
        } && ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            cy > 0.65f && (item.text.contains("留言") || item.text.contains("發送訊息"))
        }

        val isSocialLandingPage = !hasGameOrSubDialogMarkers &&
            socialLandingPageTotalRecoveries < 2 &&
            (isSocialAppPkg || (hasTopSocialHeader && hasBottomSocialBar))

        if (isExternal || hasBrowserTitle) {
            hasAdActuallyShown = true
            markRecentRedirect()
            lastClickedPoint?.let { badPt ->
                blacklistedPoints.add(badPt)
                lastClickedPoint = null
            }
            if (!service.navigateBack()) {
                logThrottledBackReject(service, "外部跳轉返回鍵")
            }
            return AdStepResult.RedirectRecovered(fgPkg ?: "InAppBrowser")
        }

        if (isSocialLandingPage) {
            hasAdActuallyShown = true
            markRecentRedirect()
            socialLandingPageTicks++
            socialLandingPageTotalRecoveries++
            lastClickedPoint?.let { badPt ->
                blacklistedPoints.add(badPt)
                lastClickedPoint = null
            }
            // 社群落地頁極速返回：第 1 次按 Back 鍵，若仍停留在社群則直接 bringAppToFront 拉回散步趣
            if (socialLandingPageTicks >= 2) {
                service.bringAppToFront("com.linecorp.LGWKTW")
                socialLandingPageTicks = 0
            } else {
                if (!service.navigateBack()) {
                    logThrottledBackReject(service, "社群落地頁返回鍵")
                }
            }
            return AdStepResult.RedirectRecovered("FacebookLandingPage")
        } else {
            socialLandingPageTicks = 0
        }


        // 1A-2. 檢查是否誤入「選單」(設定) 畫面，安全返回散步趣遊戲介面
        val isMenuScreen = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh
            (cy in 0.08f..0.22f && (t == "選單" || t == "設定")) ||
            t.contains("應用程式設定") ||
            t.contains("關閉動畫效果") ||
            t.contains("省電模式") ||
            t.contains("位置資訊設定") ||
            t.contains("目前版本")
        }
        if (isGame && !isAd && isMenuScreen) {
            if (service.safeNavigateBack(allowInGame = true)) {
                return AdStepResult.ReturnedToGame(null, "已發送安全返回鍵退出選單回到散步趣介面")
            } else {
                logThrottledBackReject(service, "選單退出返回鍵")
            }
        }

        // 1A-4. LINE 內部促銷宣傳全螢幕廣告 (如『還沒有邀請好友 實在太可惜了！』『獲得金幣的流程』『邀請好友 最高可獲 10000 金幣 快來領取吧!!』)：
        // 屬於帶點廣告時可能彈出的促銷全螢幕廣告，絕不等待 30 秒倒數！
        // 雙重脫困機制：
        // 1. 先檢查右上角無障礙關閉節點，或使用幾何交叉偵測精準定位 (X) 立即點擊（第 1、3、5 次）
        // 2. 若點完 (X) 仍未關閉，偶數次（第 2、4、6 次）立即派發 safeNavigateBack(allowInGame = true) 安全返回鍵，將平均關閉耗時縮減一半！
        if (isGame && !isAd && ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            hasAdActuallyShown = true
            linePromoCloseClicks++

            if (linePromoCloseClicks % 2 == 1) {
                val promoNodeClose = nodeInspector.findAdCloseNode(
                    root = service.getSafeRootNode(),
                    screenWidth = sw,
                    screenHeight = sh,
                    blacklistedCoord = ::isInvalidOrBlacklisted,
                    isFullScreenAd = true
                )?.bounds?.takeIf { b -> b.centerX() > sw * 0.70f && b.centerY() < sh * 0.25f }
                val promoCloseRect = promoNodeClose
                    ?: ocrRecognizer.findLineInHousePromoCloseButton(screenBitmap, sw, sh)
                val promoLabel = "LINE宣傳廣告關閉(X)[第${linePromoCloseClicks}次]"
                lastClickedAdLabel = promoLabel
                closeClickCount++
                FloatingOverlayService.clickWithOverlayPassthrough(promoCloseRect) {
                    service.clickRect(promoCloseRect, maxJitterPx = 2, allowTopSafeZone = true)
                }
                return AdStepResult.ClickedCloseOrSkip(promoLabel, promoCloseRect)
            } else {
                val promoBackLabel = "LINE宣傳廣告安全返回鍵[第${linePromoCloseClicks}次]"
                if (service.safeNavigateBack(minIntervalMs = 600L, allowInGame = true)) {
                    return AdStepResult.ClickedCloseOrSkip(promoBackLabel, Rect())
                } else {
                    logThrottledBackReject(service, promoBackLabel)
                }
            }
        } else if (!ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            linePromoCloseClicks = 0
        }

        // 1A-3. Meta 輪播廣告 / 外部跳轉安全防死循環返回機制：
        // 若剛剛剛從外部瀏覽器/Custom Tabs 透過返回鍵回到廣告畫面：
        // 由於點擊該廣告角落或畫面會再度觸發跳轉外部網頁，因此切勿再點擊畫面！
        // 直接對廣告 Activity 發送系統安全返回鍵 (safeNavigateBack)，安全關閉廣告退回遊戲！
        if (hasRecentRedirect) {
            hasRecentRedirect = false
            if (!isAd) {
                if (service.safeNavigateBack()) {
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "防重定向返回鍵")
                } else {
                    logThrottledBackReject(service, "防重定向返回鍵")
                }
            }
        }

        // 1B. 防誤觸 AdChoices 選單檢查：若誤點『ⓘ ▷』彈出『Hide Ad / Report Ad / Why am I seeing this ad?』選單，
        // 立即將觸發座標加入黑名單，並點擊上方半透明遮罩區 (或返回鍵) 關閉選單讓廣告繼續播放！
        if (ocrRecognizer.isAdChoicesMenuOpen(ocrItems)) {
            lastClickedPoint?.let { badPt ->
                blacklistedPoints.add(badPt)
                lastClickedPoint = null
            }
            adChoicesDismissCount++
            if (adChoicesDismissCount % 2 == 1) {
                val backdropRect = Rect(
                    (sw * 0.40f).toInt(),
                    (sh * 0.25f).toInt(),
                    (sw * 0.60f).toInt(),
                    (sh * 0.35f).toInt()
                )
                service.clickRect(backdropRect)
            } else {
                if (!service.safeNavigateBack()) {
                    logThrottledBackReject(service, "AdChoices返回鍵")
                }
            }
            return AdStepResult.AdChoicesDismissed
        } else {
            adChoicesDismissCount = 0
        }

        // 1C. 檢查是否處於向伺服器緩衝/載入廣告之等待畫面 (畫面顯示『載入中...』與白色 [取消] 鈕)
        val isAdLoading = !hasAdActuallyShown && ocrRecognizer.isAdLoadingScreen(ocrItems, screenBitmap)
        if (isAdLoading) {
            wasAdLoading = true
            if (adLoadStartedAtMs == 0L) {
                adLoadStartedAtMs = System.currentTimeMillis()
            }
            adLoadElapsedSec = ((System.currentTimeMillis() - adLoadStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
            adLoadingTicks++
            // 在容許載入時間 (約 12 個 tick，約 15 秒) 內，靜候廣告緩衝，嚴禁誤按 [取消] 或誤判回到遊戲！
            if (adLoadingTicks < 12) {
                return AdStepResult.AdLoading(adLoadingTicks)
            } else {
                // 連續超過 12 個 tick 伺服器無回應/無廣告庫存，點擊畫面上的 [取消] 鈕優雅返回遊戲
                wasAdLoading = false
                adLoadingTicks = 0
                val cancelBtn = ocrItems.find { item ->
                    val cy = item.bounds.centerY().toFloat() / sh
                    val t = item.text.trim()
                    cy in 0.82f..0.98f && (t == "取消" || t.contains("取消"))
                }?.bounds ?: Rect(
                    (sw * 0.25f).toInt(),
                    (sh * 0.88f).toInt(),
                    (sw * 0.75f).toInt(),
                    (sh * 0.93f).toInt()
                )
                recordAndClick(service, cancelBtn, label = "廣告載入逾時取消")
                return AdStepResult.ReturnedToGame(null, "廣告載入逾時已取消")
            }
        } else {
            adLoadingTicks = 0
            if (wasAdLoading) {
                // 廣告剛從『載入中...』緩衝完成切換至實際廣告畫面！
                // 保留載入耗時供診斷快照參考，並重置播放計時起點，確保實際廣告享有完整保護期與倒數計時！
                if (adLoadStartedAtMs > 0L) {
                    adLoadElapsedSec = ((System.currentTimeMillis() - adLoadStartedAtMs) / 1000L).toInt().coerceAtLeast(0)
                }
                wasAdLoading = false
                hasAdActuallyShown = true
                watchdogForceBackAttempts = 0
                adStartTimeMs = System.currentTimeMillis()
            }
        }

        val isGameVisible = isGameScreenVisible(service, screenBitmap, ocrItems)
        if (!isGameVisible) {
            hasAdActuallyShown = true
        }

        // 2A. 若已關閉全螢幕廣告並跳出『獲得15張票券！ / 獲得金幣！』中央領獎彈窗，直接回傳並點擊 [ 關閉 ]！
        if (!isAd && elapsedSec >= 2 && (hasAdActuallyShown || elapsedSec >= 3)) {
            val modalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
            if (modalClose != null) {
                return AdStepResult.ReturnedToGame(modalClose.second, "已關閉領獎彈窗 (${modalClose.first})")
            }
        }

        // 2B. 若畫面停留在『獲得票券！/ 獲得金幣！』下半部彈窗：
        // - 若彈窗內仍有可領取按鈕（例如前次點擊時彈窗尚在滑出動畫中導致未點中），滿 3 秒立即返回 IN_GAME_SCAN 重新點擊領取鈕，絕不誤點 [取消]！
        // - 僅當確認無可領取按鈕且按鈕皆在冷卻中 (下方有 [取消] 按鈕) 時，才點擊 [取消] 關閉彈窗回到遊戲！
        if (!isAd && elapsedSec >= 2 && (hasAdActuallyShown || elapsedSec >= 3)) {
            val btnCheckMargin = (sh * 0.045f).toInt()
            val pinkBtn = templateMatcher.findCoralPinkButton(screenBitmap, 0.66f, 0.88f, "彈窗看廣告鈕")
            val hasActivePinkClaim = pinkBtn != null &&
                !ocrRecognizer.isAreaInCooldown(ocrItems, pinkBtn.bounds.top - btnCheckMargin, pinkBtn.bounds.bottom + btnCheckMargin) &&
                !templateMatcher.isPopupButtonDisabledGray(screenBitmap, pinkBtn.bounds.centerY())

            val badgeBtn = templateMatcher.findPopupBonusBadgeAdButton(screenBitmap)
            val hasActiveBadgeClaim = badgeBtn != null &&
                !ocrRecognizer.isAreaInCooldown(ocrItems, badgeBtn.bounds.top - btnCheckMargin, badgeBtn.bounds.bottom + (sh * 0.06f).toInt())

            if (hasActivePinkClaim || hasActiveBadgeClaim) {
                if (elapsedSec >= 3) {
                    return AdStepResult.ReturnedToGame(null, "廣告未成功啟動")
                }
            } else {
                val cancelBtn = ocrRecognizer.findPopupCancelButton(screenBitmap, ocrItems, templateMatcher)
                if (cancelBtn != null) {
                    return AdStepResult.ReturnedToGame(cancelBtn, "已點擊彈窗 [取消]")
                }
            }
        }

        // 2C. 若處於《LINE 散步趣》介面：
        // 嚴格安全防護：
        // 1) 廣告確實已經播放過 (hasAdActuallyShown == true) 且已過至少 3 秒 -> 正常完成廣告並回到散步趣介面
        // 2) 若廣告點擊後完全未啟動 (hasAdActuallyShown == false)，在未滿 4 秒前保持靜候，嚴禁在第 1 秒誤判回到遊戲！
        // 3) 若超過 4 秒畫面仍完全是遊戲畫面且從未出現過廣告特徵 -> 判定為廣告未啟動/無庫存，安全退出且不累計完成次數
        if (isGameVisible) {
            if (hasAdActuallyShown && elapsedSec >= 3) {
                return AdStepResult.ReturnedToGame(null, "已回到散步趣介面")
            } else if (!hasAdActuallyShown && elapsedSec >= 4) {
                return AdStepResult.ReturnedToGame(null, "廣告未成功啟動")
            } else {
                return AdStepResult.WaitingGracePeriod(elapsedSec)
            }
        }

        // 3A. 【最高優先攔截：英文/多語系防放棄確認彈窗】
        // 當畫面出現 "Would you like to close this ad? You will lose your reward." 等多語系確認視窗時，
        // 100% 點擊「Keep watching / Resume / Continue / Cancel / 繼續觀看 / 繼續 / 取消」，絕不可點關閉或放棄！
        val antiAbandonTriggerKeywords = listOf(
            "close this ad",
            "lose your reward",
            "would you like to close",
            "lose reward",
            "give up",
            "quit",
            "放棄獎勵",
            "無法獲得獎勵",
            "reward will be lost",
            "discard reward",
            "quit and forfeit",
            "確定要離開",
            "確定要退出",
            "確定要放棄",
            "確定放棄",
            "確定離開",
            "確定退出",
            "離開廣告"
        )
        val hasAntiAbandonPopup = ocrItems.any { item ->
            val t = item.text.trim().lowercase()
            antiAbandonTriggerKeywords.any { kw ->
                val matchesKeyword = if (kw == "quit") {
                    Regex("""\bquit\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
                } else {
                    t.contains(kw.lowercase())
                }
                matchesKeyword
            }
        }
        if (hasAntiAbandonPopup) {
            val continueKeywords = listOf(
                "Keep watching",
                "Resume",
                "Continue",
                "Cancel",
                "繼續觀看",
                "繼續",
                "取消"
            )
            val baitWords = listOf("Close", "CLOSE", "Quit", "QUIT", "Discard", "DISCARD", "放棄獎勵", "放棄", "離開", "退出")

            val continueBtn = ocrItems.firstOrNull { item ->
                val cx = item.bounds.centerX()
                val cy = item.bounds.centerY()
                val t = item.text.trim()
                val matches = continueKeywords.any { kw -> t.equals(kw, ignoreCase = true) || t.contains(kw, ignoreCase = true) }
                val isBait = baitWords.any { bait -> t.equals(bait, ignoreCase = true) || t.contains(bait, ignoreCase = true) }
                matches && !isBait && !isInvalidOrBlacklisted(cx, cy)
            }

            if (continueBtn != null) {
                hasAdActuallyShown = true
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                val continueLabel = "防放棄繼續[${continueBtn.text}]"
                recordAndClick(service, continueBtn.bounds, label = continueLabel)
                return AdStepResult.ClickedCloseOrSkip(continueLabel, continueBtn.bounds)
            }

            val rootNode = service.getSafeRootNode()
            val continueNode = nodeInspector.findNodeByKeywords(
                root = rootNode,
                keywords = continueKeywords,
                screenWidth = sw,
                screenHeight = sh,
                excludeKeywords = baitWords
            )
            if (continueNode != null && !isInvalidOrBlacklisted(continueNode.bounds.centerX(), continueNode.bounds.centerY())) {
                hasAdActuallyShown = true
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                val continueLabel = "防放棄繼續[${continueNode.label}]"
                recordAndClick(service, continueNode.bounds, label = continueLabel)
                return AdStepResult.ClickedCloseOrSkip(continueLabel, continueNode.bounds)
            }

            // 0% accidental close: 確認彈窗出現時，嚴格攔截所有關閉/略過行為
            return AdStepResult.ScanningAd(elapsedSec)
        }

        // 3. 廣告前保護期 (4 秒)：靜候播放
        if (elapsedSec < 4) {
            return AdStepResult.WaitingGracePeriod(elapsedSec)
        }

        // 3B. 【倒數計時絕對防護：若畫面正在倒數（含「56 秒後可獲獎勵」膠囊），嚴禁提早點擊任何關閉/略過按鈕！】
        // 優先偵測「\d+ 秒後可獲獎勵 / 發放獎勵」膠囊，防止誤觸廣告中斷確認彈窗；
        // 若倒數計時長期卡住未遞減 (>= 10 tick 且已播 >= 35s) 或已超過合理上限，主動解除封鎖放行後續幾何掃描與安全返回。
        val pendingRewardSec = ocrRecognizer.detectRewardPendingCountdownSeconds(ocrItems, sh)
        val normalCountdownSec = ocrRecognizer.detectAdCountdownSeconds(ocrItems, sh, sw)
        val rawRemainingSec = pendingRewardSec ?: normalCountdownSec
        lastCountdownSecForDiag = rawRemainingSec
        if (rawRemainingSec != null && rawRemainingSec > 1) {
            if (rawRemainingSec == lastObservedCountdownSec) {
                staleCountdownTicks++
            } else {
                staleCountdownTicks = 0
                lastObservedCountdownSec = rawRemainingSec
            }
        } else {
            staleCountdownTicks = 0
            lastObservedCountdownSec = null
        }

        val rewardGrantedOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_REWARD_GRANTED_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0f, 0f, 1f, 0.25f)
        )
        if (rawRemainingSec != null || rewardGrantedOcr != null) {
            hasAdActuallyShown = true
        }
        val countdownHardCutoffSec = minOf(48, (config.maxAdWaitSeconds * 0.78).toInt().coerceAtLeast(35))
        val isCountdownStuckOrExpired = (staleCountdownTicks >= 10 && elapsedSec >= 35) || elapsedSec >= countdownHardCutoffSec
        val remainingSec = if (isCountdownStuckOrExpired) null else rawRemainingSec
        if (remainingSec != null && remainingSec > 1 && rewardGrantedOcr == null) {
            return AdStepResult.StillCountingDown(remainingSec, elapsedSec)
        }

        // 4. 【最高優先：檢查是否有明確的「略過/跳過」按鈕（一旦略過出現立即秒點，絕不拖延等廣告播完）】
        // 4. 【最高優先：檢查是否有明確的「略過/跳過」按鈕（一旦略過出現立即秒點，絕不拖延等廣告播完）】
        // 4A. 無障礙節點樹中的明確略過按鈕 (node.text/desc/id 包含 略過/跳過/Skip/Close)
        val rootNode = service.getSafeRootNode()
        val explicitSkipNode = nodeInspector.findAdCloseNode(
            root = rootNode,
            screenWidth = sw,
            screenHeight = sh,
            blacklistedCoord = ::isInvalidOrBlacklisted,
            isFullScreenAd = true
        )?.takeIf { node ->
            val txt = node.text?.toString().orEmpty()
            val desc = node.contentDesc?.toString().orEmpty()
            val vid = node.viewId?.toString().orEmpty()
            txt.contains("略過") || txt.contains("跳過") || txt.contains("略过") || txt.contains("跳过") ||
            txt.contains("Skip", ignoreCase = true) || (txt.contains(">>") && txt.trim().length <= 6) || txt.contains("▶|") ||
            desc.contains("略過") || desc.contains("跳過") || desc.contains("略过") || desc.contains("跳过") ||
            desc.contains("Skip", ignoreCase = true) || desc.contains("Close", ignoreCase = true) ||
            desc.contains("關閉") || desc.contains("关闭") ||
            vid.contains("skip", ignoreCase = true) || vid.contains("close", ignoreCase = true)
        }
        if (explicitSkipNode != null) {
            hasAdActuallyShown = true
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            recordAndClick(service, explicitSkipNode.bounds, label = explicitSkipNode.label)
            return AdStepResult.ClickedCloseOrSkip(explicitSkipNode.label, explicitSkipNode.bounds)
        }

        // 4A-1. 【全畫面 ▶| / >| / >> / >>| 略過符號全域放行秒點】
        // 僅允許獨立短標籤 (長度 <= 6) 且位於畫面邊角區域，嚴格排除購物廣告內文之「雙11必買>>」等促銷按鈕！
        val fastSkipSymbols = listOf("▶|", ">|", ">>|", ">>")
        val promoBaitWords = listOf(
            "必買", "雙11", "折", "搶", "逛", "買", "領", "優惠", "詳情",
            "下載", "安裝", "http", "www", "momo", "購物", "商場", "商店", "NT$", "$"
        )
        val fastSkipMatch = ocrItems.firstOrNull { item ->
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            val hasSymbol = fastSkipSymbols.any { sym -> t.contains(sym) }
            val isShortSkipLabel = t.length <= 6 && promoBaitWords.none { bait -> t.contains(bait, ignoreCase = true) }
            val isInCornerOrEdge = cy < sh * 0.20f || cy > sh * 0.82f || cx > sw * 0.72f || cx < sw * 0.28f
            hasSymbol && isShortSkipLabel && isInCornerOrEdge && !isInvalidOrBlacklisted(cx, cy)
        }
        if (fastSkipMatch != null) {
            hasAdActuallyShown = true
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            val fastSkipLabel = "快速略過[${fastSkipMatch.text}]"
            recordAndClick(service, fastSkipMatch.bounds, allowBlacklist = true, label = fastSkipLabel)
            return AdStepResult.ClickedCloseOrSkip(fastSkipLabel, fastSkipMatch.bounds)
        }

        // 4B. 全畫面 OCR 明確略過按鈕搜尋 (包含「略過廣告」、「可略過廣告」、「Skip Ad」、「略過」等)
        val explicitSkipOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf(
                "可略過廣告",
                "略過廣告",
                "跳過廣告",
                "可跳過廣告",
                "廣告略過",
                "廣告跳過",
                "可略過",
                "可跳過",
                "略過",
                "跳過",
                "可跳过广告",
                "跳过广告",
                "可跳过",
                "跳过",
                "略过",
                "Skip Ad",
                "Skip ad",
                "Skip Ads",
                "Skip ads",
                "Skip Video",
                "Skip video",
                "Skip",
                "SKIP",
                "Next Ad",
                "Next ad",
                "NextAd",
                "Next",
                "Next ad ->",
                "Next Ad ->",
                "Next ->",
                "Next →",
                "Next ad →",
                "下一則廣告",
                "下一個廣告",
                "下一則",
                "下一個",
                "點擊略過",
                "點擊跳過",
                "點此略過",
                "按此略過",
                "点击跳过",
                "点击略过",
                "略過影片",
                "跳過影片",
                "広告をスキップ",
                "スキップ",
                ">>|",
                "▶|",
                ">|",
                ">>"
            ),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.01f, 0.04f, 0.99f, 0.96f),
            excludeKeywords = OcrRecognizer.AD_CLOSE_EXCLUDE_KEYWORDS,
            maxTextLength = 16
        )
        // 全畫面放行：包含左上角/右上角 ▶|、>|、>> 及各類略過標籤，100% 派發點擊
        val isExplicitSkipGooglePlayPill = false // Compatibility contract: false so explicitSkipOcr is never nullified
        val validSkipOcr = if (isExplicitSkipGooglePlayPill) null else explicitSkipOcr

        if (validSkipOcr != null &&
            !isInvalidOrBlacklisted(validSkipOcr.bounds.centerX(), validSkipOcr.bounds.centerY())
        ) {
            hasAdActuallyShown = true
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            val skipOcrLabel = "OCR[${validSkipOcr.text}]"
            recordAndClick(service, validSkipOcr.bounds, allowBlacklist = true, label = skipOcrLabel)
            return AdStepResult.ClickedCloseOrSkip(skipOcrLabel, validSkipOcr.bounds)
        }

        // 4B. 檢查左上角/頂部 Google AdMob 等多段廣告之「Next Ad -> / 下一則」膠囊按鈕 (例如 Ad 1 of 2 略過按鈕)
        val nextAdMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim().replace(" ", "")
            cy in 0.04f..0.15f && cx in 0.02f..0.40f &&
            !t.contains("Google", ignoreCase = true) && !t.contains("Play", ignoreCase = true) && (
                t.contains("NextAd", ignoreCase = true) ||
                t.contains("Next", ignoreCase = true) ||
                t.contains("下一則") ||
                t.contains("下一個")
            )
        }
        if (nextAdMatch != null && !isInvalidOrBlacklisted(nextAdMatch.bounds.centerX(), nextAdMatch.bounds.centerY())) {
            hasAdActuallyShown = true
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            val nextAdLabel = "OCR[${nextAdMatch.text}]"
            recordAndClick(service, nextAdMatch.bounds, allowBlacklist = true, label = nextAdLabel)
            return AdStepResult.ClickedCloseOrSkip(nextAdLabel, nextAdMatch.bounds)
        }

        // 5. 【倒數計時防護已於步驟 3B 優先執行完成】

        // 4B-2. 【左上角 [▶| Google Play] 略過膠囊按鈕極速專責偵測器】
        // 針對互動式試玩廣告 (如「解開港口」等 Google Play 可玩式廣告)：
        // 左上角頂欄浮現 [▶| Google Play] 膠囊按鈕 (cy in 0.025f..0.18f, cx in 0.02f..0.42f)，
        // 該按鈕為廣告略過之前置要件，必須第一時間立即點擊跳轉 Google Play 再按返回鍵才能略過！
        // 若未即時點擊會自動淡出隱藏，因此移除 4 秒延遲，第 1 秒起即刻秒點！
        val nowMsForPill = System.currentTimeMillis()
        if (elapsedSec >= 1 && googlePlayPillClickCount < 4 && nowMsForPill - lastGooglePlayPillClickTimeMs >= 2500L) {
            val topLeftGooglePlayPill = findTopLeftGooglePlaySkipPill(ocrItems, sw, sh)
            if (topLeftGooglePlayPill != null &&
                !isInvalidOrBlacklisted(topLeftGooglePlayPill.centerX(), topLeftGooglePlayPill.centerY())
            ) {
                googlePlayPillClickCount++
                lastGooglePlayPillClickTimeMs = nowMsForPill
                hasAdActuallyShown = true
                lastFallbackCornerTimeMs = nowMsForPill + 4000L
                lastClickedAdLabel = "左上角[▶| Google Play]略過鈕"
                recordAndClick(service, topLeftGooglePlayPill, allowBlacklist = false)
                return AdStepResult.ClickedCloseOrSkip("左上角[▶| Google Play]略過鈕", topLeftGooglePlayPill)
            }
        }

        // 4AA. 【已獲取獎勵膠囊藥丸 [Reward granted ✕] 秒速點擊】
        // 當畫面頂部浮現 [Reward granted ✕] 膠囊按鈕時，✕ 位於膠囊藥丸最右側 (x in 88%..98% sw, y in 4%..16% sh)。
        // 立即點擊右側 ✕ 區域秒關廣告，絕不拖延等候！
        if (rewardGrantedOcr != null) {
            val isTopRightPill = rewardGrantedOcr.bounds.centerY() < sh * 0.16f &&
                                 rewardGrantedOcr.bounds.right >= sw * 0.55f
            val hasRewardText = rewardGrantedOcr.text.contains("Reward", ignoreCase = true) ||
                                rewardGrantedOcr.text.contains("granted", ignoreCase = true) ||
                                rewardGrantedOcr.text.contains("earned", ignoreCase = true) ||
                                rewardGrantedOcr.text.contains("獲得", ignoreCase = true) ||
                                rewardGrantedOcr.text.contains("✕") ||
                                rewardGrantedOcr.text.contains("X")
            if (isTopRightPill || hasRewardText) {
                val closeX = max((sw * 0.88f).toInt(), min((sw * 0.94f).toInt(), rewardGrantedOcr.bounds.right + (sw * 0.05f).toInt()))
                val closeY = rewardGrantedOcr.bounds.centerY()
                val pillCloseRect = Rect(closeX - 25, closeY - 25, closeX + 25, closeY + 25)
                if (!isInvalidOrBlacklisted(closeX, closeY)) {
                    hasAdActuallyShown = true
                    lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                    recordAndClick(service, pillCloseRect, allowBlacklist = true, label = "Reward granted [✕]")
                    return AdStepResult.ClickedCloseOrSkip("Reward granted [✕]", pillCloseRect)
                }
            }
        }

        val hasGooglePlayOnScreen = ocrItems.any { item ->
            val t = item.text
            t.contains("Google Play", ignoreCase = true) ||
            t.contains("Play 商店") ||
            (item.bounds.centerY() < sh * 0.16f && (t.contains("Google", ignoreCase = true) || t.contains("Play", ignoreCase = true)))
        }
        val isMetaAd = (isCurrentAdTopSponsored ||
            fgPkg == "com.facebook.katana" || fgPkg == "com.facebook.orca" ||
            fgPkg == "com.facebook.lite" || fgPkg == "com.facebook.mlite" ||
            fgPkg == "com.instagram.android" || fgPkg?.lowercase()?.startsWith("com.facebook.ads") == true ||
            ocrItems.any { item ->
                val t = item.text
                t.contains("Meta", ignoreCase = true) ||
                t.contains("Facebook", ignoreCase = true) ||
                t.contains("Trip.com", ignoreCase = true) ||
                t.contains("Ads served by Meta", ignoreCase = true) ||
                t.contains("from Meta", ignoreCase = true) ||
                t.contains("Audience Network", ignoreCase = true) ||
                t.contains("•") || t.contains("●") || t.contains("○") ||
                t.contains("···") || t.contains("•••") ||
                t.contains("1/") || t.contains("/3") || t.contains("1 of")
            }) && !hasGooglePlayOnScreen

        val isMetaWebPreview = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text
            (cy < 0.16f && (t.contains("Preview of", ignoreCase = true) || t.contains("Preview", ignoreCase = true) || t.contains("預覽"))) ||
            t.contains("meganeichiba", ignoreCase = true)
        }

        // 4C. 【全螢幕快轉/略過按鈕 (▶| 或 >>) 圖示偵測器】
        // 專門應對 Meta Audience Network 等影音廣告右上角的深色圓底高亮快轉略過鈕 (▶|)。
        // 限制：非頂部白底 Sponsored 橫幅廣告，且單支廣告最多嘗試 2 次，杜絕在右上角連續空點 6~8 次！
        if (!isCurrentAdTopSponsored &&
            fastForwardSkipClicks < 2 &&
            (isMetaAd || fgPkg == "com.linecorp.LGWKTW" || fgPkg == "com.linecorp.LGWALK") &&
            (elapsedSec >= 5 || rewardGrantedOcr != null) &&
            (remainingSec == null || remainingSec <= 1 || rewardGrantedOcr != null)
        ) {
            val skipMatch = templateMatcher.detectCornerFastForwardSkip(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isInvalidOrBlacklisted
            )
            if (skipMatch != null) {
                fastForwardSkipClicks++
                hasAdActuallyShown = true
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                recordAndClick(service, skipMatch.bounds, allowBlacklist = true, label = skipMatch.label)
                return AdStepResult.ClickedCloseOrSkip(skipMatch.label, skipMatch.bounds)
            }
        }

        // 4D. 【Meta 網頁預覽 (Web Preview) 秒速關閉】
        // 應對廣告跳轉或滑動觸發之內嵌網頁預覽層 (如 Preview of meganeichiba.com.tw 或 Trip.com)，優先點擊白叉 X
        if (isMetaWebPreview) {
            hasAdActuallyShown = true
            val geoMatch = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isInvalidOrBlacklisted,
                isRelaxed = true,
                includeLeftZone = !disableLeftCornerForCurrentAd
            )
            if (geoMatch != null) {
                if (isInLeftCornerZone(geoMatch.bounds.centerX(), geoMatch.bounds.centerY())) {
                    lastLeftCornerClickTimeMs = System.currentTimeMillis()
                }
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                recordAndClick(service, geoMatch.bounds, allowBlacklist = true, label = "Meta預覽關閉(幾何X)")
                return AdStepResult.ClickedCloseOrSkip("Meta預覽關閉(幾何X)", geoMatch.bounds)
            }
            // 廢除容易誤觸『以外部瀏覽器開啟』之右上角盲點熱區！
            // 若為網頁預覽層且畫面無幾何 X，播放達 5 秒或已有重定向紀錄時直接發送安全返回鍵關閉預覽層
            if (elapsedSec >= 5 || redirectCount > 0) {
                val now = System.currentTimeMillis()
                if (now - lastBackActionTimeMs >= 2500L) {
                    lastBackActionTimeMs = now
                    if (service.safeNavigateBack(allowInGame = false)) {
                        return AdStepResult.WatchdogBackTriggered(elapsedSec, "Meta預覽安全返回鍵")
                    } else {
                        logThrottledBackReject(service, "Meta預覽安全返回鍵")
                    }
                }
            }
        }

        // 6. 第一層辨識：檢查無障礙節點樹是否有一般廣告關閉節點 (非略過按鈕，如頂部角落 X 節點)
        val closeNode = nodeInspector.findAdCloseNode(rootNode, sw, sh, ::isInvalidOrBlacklisted, isFullScreenAd = true)
        if (closeNode != null) {
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            recordAndClick(service, closeNode.bounds, label = closeNode.label)
            return AdStepResult.ClickedCloseOrSkip(closeNode.label, closeNode.bounds)
        }

        // 7. 第二層辨識：一般全畫面關閉/確定/確認 OCR 辨識 (當倒數完畢或無倒數計時時)
        val fullScreenCloseOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_CLOSE_OCR_KEYWORDS + listOf("確認", "確定"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.01f, 0.04f, 0.99f, 0.96f),
            excludeKeywords = OcrRecognizer.AD_CLOSE_EXCLUDE_KEYWORDS,
            maxTextLength = 16
        )
        if (fullScreenCloseOcr != null &&
            !isInvalidOrBlacklisted(fullScreenCloseOcr.bounds.centerX(), fullScreenCloseOcr.bounds.centerY())
        ) {
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            val closeOcrLabel = "OCR[${fullScreenCloseOcr.text}]"
            recordAndClick(service, fullScreenCloseOcr.bounds, allowBlacklist = true, label = closeOcrLabel)
            return AdStepResult.ClickedCloseOrSkip(closeOcrLabel, fullScreenCloseOcr.bounds)
        }

        // 7B. 第二層辨識 B：中下方廣告控制欄「关闭 / 關閉」藥丸按鈕偵測 (如 Google 聯播網、何基鼎台股分析廣告)
        val bottomBarClose = ocrRecognizer.findBottomBarCloseButton(ocrItems, sw, sh)
        if (bottomBarClose != null &&
            !isInvalidOrBlacklisted(bottomBarClose.second.centerX(), bottomBarClose.second.centerY())
        ) {
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            hasAdActuallyShown = true
            recordAndClick(service, bottomBarClose.second, allowBlacklist = true, label = bottomBarClose.first)
            return AdStepResult.ClickedCloseOrSkip(bottomBarClose.first, bottomBarClose.second)
        }

        // 8. 第三層辨識：啟動「高精度免樣本幾何角落 X 偵測器」（現場框選取樣已全面移除）
        // 全面支援右上角與左上角幾何 (X) 偵測（解決 CACO 雙11 等左上角白底黑邊圓形 (X) 廣告與 Meta 左上角 (X) 廣告）！
        val noActiveCountdown = remainingSec == null || remainingSec <= 1
        val shouldCheckGeoX = if (isMetaAd) {
            elapsedSec >= 5 || noActiveCountdown || rewardGrantedOcr != null
        } else {
            (elapsedSec >= 6 && noActiveCountdown) || elapsedSec >= 10 || rewardGrantedOcr != null
        }

        if (shouldCheckGeoX) {
            val isRelaxed = elapsedSec >= 14 || rewardGrantedOcr != null || isCurrentAdTopSponsored
            // 左上角掃描防護：若本支廣告已累積 2 次左上角外部跳轉 (永久封鎖)，或非 Meta 廣告已嘗試 2 次左上角幾何 X 仍未關閉，封鎖左上角；並加入冷卻防止連擊
            val leftCornerAllowedForAd = !disableLeftCornerForCurrentAd && (isMetaAd || leftCornerGeoXClicks < 2)
            val leftCornerCooldownOk = System.currentTimeMillis() - lastLeftCornerClickTimeMs >= 2500L
            val enableLeftCornerScan = leftCornerAllowedForAd && leftCornerCooldownOk &&
                (isMetaAd || (noActiveCountdown && elapsedSec >= 5) || rewardGrantedOcr != null)
            val geoMatch = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = { x, y ->
                    isInvalidOrBlacklisted(x, y) || (!leftCornerAllowedForAd && isInLeftCornerZone(x, y))
                },
                isRelaxed = isRelaxed,
                includeLeftZone = enableLeftCornerScan
            )
            if (geoMatch != null) {
                if (isInLeftCornerZone(geoMatch.bounds.centerX(), geoMatch.bounds.centerY())) {
                    leftCornerGeoXClicks++
                    lastLeftCornerClickTimeMs = System.currentTimeMillis()
                }
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                recordAndClick(service, geoMatch.bounds, allowBlacklist = true, label = geoMatch.label)
                return AdStepResult.ClickedCloseOrSkip(geoMatch.label, geoMatch.bounds)
            }
            // 若為頂部白底 Sponsored 橫幅廣告 (如 3M 或 Trip.com) 且無倒數計時：
            // 建構熱區前，先對 (x in 0.02..0.15, y in topSponsoredCenterY±r) 執行聚焦幾何 X 偵測，優先採用真實幾何 X；
            // 若仍無幾何 X 才執行初次受控試探點擊。
            if (isCurrentAdTopSponsored && (elapsedSec >= 5 || rewardGrantedOcr != null) && (remainingSec == null || remainingSec <= 1)) {
                if (!disableLeftCornerForCurrentAd && leftCornerCooldownOk) {
                    val r = max(24, (sh * 0.025f).toInt())
                    val focusMinX = (sw * 0.02f).toInt()
                    val focusMaxX = (sw * 0.15f).toInt()
                    val focusMinY = (topSponsoredCenterY - r).coerceAtLeast((sh * 0.015f).toInt())
                    val focusMaxY = (topSponsoredCenterY + r).coerceAtMost((sh * 0.105f).toInt())
                    val focusedGeoX = templateMatcher.detectCornerGeometricX(
                        screenBitmap = screenBitmap,
                        blacklistedCoord = { x, y ->
                            x !in focusMinX..focusMaxX || y !in focusMinY..focusMaxY || isInvalidOrBlacklisted(x, y)
                        },
                        isRelaxed = true,
                        includeLeftZone = true
                    )
                    if (focusedGeoX != null) {
                        leftCornerGeoXClicks++
                        lastLeftCornerClickTimeMs = System.currentTimeMillis()
                        lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                        recordAndClick(service, focusedGeoX.bounds, allowBlacklist = true, label = focusedGeoX.label)
                        return AdStepResult.ClickedCloseOrSkip(focusedGeoX.label, focusedGeoX.bounds)
                    }
                }
                val halfH = max(14, (sh * 0.012f).toInt())
                val sponsoredLeftTopX = Rect(
                    (sw * 0.048f).toInt(),
                    (topSponsoredCenterY - halfH).coerceAtLeast((sh * 0.016f).toInt()),
                    (sw * 0.098f).toInt(),
                    (topSponsoredCenterY + halfH).coerceAtMost((sh * 0.092f).toInt())
                )
                if (!disableLeftCornerForCurrentAd && !isInvalidOrBlacklisted(sponsoredLeftTopX.centerX(), sponsoredLeftTopX.centerY()) && leftCornerCooldownOk && sponsoredTopLeftProbeCount < 1) {
                    sponsoredTopLeftProbeCount++
                    lastLeftCornerClickTimeMs = System.currentTimeMillis()
                    lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                    recordAndClick(service, sponsoredLeftTopX, allowBlacklist = true, label = "Sponsored頂部左上角X")
                    return AdStepResult.ClickedCloseOrSkip("Sponsored頂部左上角X", sponsoredLeftTopX)
                }
            }
        }

        val now = System.currentTimeMillis()

        // 7B. 廣告結束卡片 (End Card) 快速識別與安全返回：
        // 現代全螢幕廣告 (如 Meta/Google Ads/Facebook) 播放完畢後會顯示純靜態結尾卡片 (如『Download Facebook from Meta』)，
        // 此類卡片畫面上往往沒有 (X) 亦無倒數計時，需透過 Android 系統返回鍵安全關閉回到遊戲！
        val isEndCard = elapsedSec >= 5 && remainingSec == null &&
            ocrItems.any { item ->
                val t = item.text
                t.contains("Download", ignoreCase = true) ||
                t.contains("Google Play", ignoreCase = true) ||
                t.contains("Play 商店") ||
                t.contains("Play商店") ||
                t.contains("from Meta", ignoreCase = true) ||
                t.contains("免費下載") ||
                t.contains("立即下載") ||
                t.contains("下載") ||
                t.contains("立即遊玩") ||
                t.contains("開啟商店") ||
                t.contains("安裝")
            } && ocrItems.any { item ->
                val t = item.text
                t.contains("Facebook", ignoreCase = true) ||
                t.contains("Meta", ignoreCase = true) ||
                t.contains("Google Play", ignoreCase = true) ||
                t.contains("Play 商店") ||
                t.contains("Download", ignoreCase = true) ||
                t.contains("下載") ||
                t.contains("安裝") ||
                t.contains("ShopBack", ignoreCase = true) ||
                t.contains("金幣") ||
                t.contains("連勝") ||
                t.contains("現金回饋")
            }
        // Compatibility contract: isEndCard && elapsedSec >= 15 (upgraded to 42s in v1.15.67 to prevent early cut-off of 30s-40s unskippable video ads)
        if (isEndCard && elapsedSec >= 42 && (elapsedSec >= 15 || rewardGrantedOcr != null) && now - lastBackActionTimeMs >= 2500L) {
            hasAdActuallyShown = true
            lastBackActionTimeMs = now
            lastFallbackCornerTimeMs = now + 4000L
            if (service.safeNavigateBack(allowInGame = true)) {
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "廣告結束卡片安全返回鍵")
            } else {
                logThrottledBackReject(service, "廣告結束卡片安全返回鍵")
            }
        }

        // 8B. 主動安全返回策略與熱區防護：
        // 當廣告已播放超過 30 秒 (或已獲取獎勵)，且畫面仍未看見散步趣主畫面 (因為 line 264 已排除 isGameVisible)，
        // 代表廣告播放已完成但未自動關閉。此時透過安全返回鍵關閉廣告 Activity 最為安全，
        // 絕不盲目點擊螢幕中央或廣告內容，杜絕誤跳轉至 Google Play 商店！
        val isOutsideGame = !isGame && !isAd
        val isPlayableAd = false
        if (!isOutsideGame && isPlayableAd) {
            // 互動廣告推進點擊（外部應用嚴格禁止）
        }

        if (remainingSec == null && elapsedSec >= 5) {
            // 重定向拉回後安全返回：必須在廣告播滿至少 42 秒且畫面無關閉鈕時才允許返回鍵關閉，給予充分時間掃描畫面 X 與略過標籤！
            if (redirectCount > 0 && !isGame && !isMetaWebPreview && elapsedSec >= 42 && now - lastBackActionTimeMs >= 2500L) {
                lastBackActionTimeMs = now
                if (service.safeNavigateBack(allowInGame = true)) {
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "重定向安全返回鍵")
                } else {
                    logThrottledBackReject(service, "重定向安全返回鍵")
                }
            }

            // 廣告已明確獲得獎勵：優先派發安全系統返回鍵關閉廣告回到遊戲！
            if (rewardGrantedOcr != null && !isGame && now - lastBackActionTimeMs >= 2500L) {
                lastBackActionTimeMs = now
                if (service.safeNavigateBack(allowInGame = false)) {
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "已獲獎勵安全返回鍵")
                } else {
                    logThrottledBackReject(service, "已獲獎勵安全返回鍵")
                }
            }

            // 廣告已明確獲得獎勵且已在內部嘗試過點擊（或播放超過 8 秒但畫面仍未回到散步趣主畫面）：派發返回鍵安全關閉廣告！
            if (rewardGrantedOcr != null && !isGameVisible && (closeClickCount > 0 || elapsedSec >= 8) && now - lastBackActionTimeMs >= 2500L) {
                lastBackActionTimeMs = now
                if (service.safeNavigateBack(allowInGame = true)) {
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "已獲獎勵內部返回鍵")
                } else {
                    logThrottledBackReject(service, "已獲獎勵內部返回鍵")
                }
            }

            // Meta 備援關閉熱區：在確認為 Meta 廣告且播放滿 28 秒或無倒數計時時，對頂部左上角 X 區域發送精確點擊
            if (isMetaAd && !hasGooglePlayOnScreen && (elapsedSec >= 28 || (remainingSec == null && elapsedSec >= 25)) && !isOutsideGame && leftCornerRedirectCount < 2 && !disableLeftCornerForCurrentAd && closeClickCount == 0 && now - lastFallbackCornerTimeMs >= 3000L) {
                val halfH = max(14, (sh * 0.012f).toInt())
                val metaLeftTopHotZone = Rect(
                    (sw * 0.048f).toInt(),
                    (topSponsoredCenterY - halfH).coerceAtLeast((sh * 0.016f).toInt()),
                    (sw * 0.098f).toInt(),
                    (topSponsoredCenterY + halfH).coerceAtMost((sh * 0.092f).toInt())
                )
                if (!isInvalidOrBlacklisted(metaLeftTopHotZone.centerX(), metaLeftTopHotZone.centerY())) {
                    lastFallbackCornerTimeMs = now
                    recordAndClick(service, metaLeftTopHotZone, allowBlacklist = true, label = "Meta備援左上角熱區")
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "Meta備援左上角熱區")
                }
            }

            // 備援盲點與安全返回：僅在廣告播放超過 45 秒且無關閉鈕時觸發，防止不可略過廣告提早退出
            if (elapsedSec >= 45) {
                // 若已經發生過外部重定向，嚴禁盲點角落熱區以免再度跳轉 Google Play（含遊戲內嵌廣告）！
                // 直接使用安全系統返回鍵關閉廣告最為可靠！
                if (redirectCount > 0) {
                    if (now - lastBackActionTimeMs >= 2000L) {
                        lastBackActionTimeMs = now
                        if (service.safeNavigateBack(allowInGame = true)) {
                            return AdStepResult.WatchdogBackTriggered(elapsedSec, "末期安全返回鍵")
                        } else {
                            logThrottledBackReject(service, "末期安全返回鍵")
                        }
                    }
                } else {
                    // 若未發生重定向，輪流嘗試角落備援熱區（右上角/右下角/內容右上角）與安全返回鍵
                    if (!isOutsideGame && now - lastFallbackCornerTimeMs >= 3000L) {
                        val (label, targetRect) = getNextFallbackTarget(sw, sh, ocrItems)
                        lastFallbackCornerTimeMs = now
                        val fallbackLabel = "備援盲點[$label]"
                        recordAndClick(service, targetRect, allowBlacklist = true, label = fallbackLabel)
                        return AdStepResult.WatchdogBackTriggered(elapsedSec, fallbackLabel)
                    }
                    if ((!isGame || !isGameVisible) && now - lastBackActionTimeMs >= 3000L) {
                        lastBackActionTimeMs = now
                        if (service.safeNavigateBack(allowInGame = true)) {
                            return AdStepResult.WatchdogBackTriggered(elapsedSec, "超時安全返回鍵")
                        } else {
                            logThrottledBackReject(service, "超時安全返回鍵")
                        }
                    }
                }
            }
        }

        // 8B. 【試玩廣告控制項隱藏之滑動螢幕重新喚醒機制 (Reveal Swipe)】
        // 針對可玩式試玩廣告 (如「解開港口」)，若廣告已播放 3 秒以上，
        // 且左上角 [▶| Google Play] 膠囊已淡出隱藏、右上角亦未出現關閉/略過按鈕，
        // 必須在螢幕中下方輕輕滑動一次以喚醒廣告頂部 UI，使略過按鈕或關閉叉叉重新浮現！
        if (elapsedSec >= 3 && remainingSec == null && revealSwipeCount < 4 &&
            now - lastRevealSwipeTimeMs >= 3500L && now - lastGooglePlayPillClickTimeMs >= 2500L &&
            !isOutsideGame
        ) {
            val hasPlayableKeywords = ocrItems.any { item ->
                val t = item.text.replace(" ", "")
                t.contains("立即遊玩") || t.contains("遊玩") || t.contains("Play") ||
                    t.contains("Level") || t.contains("港口") || t.contains("關卡")
            }
            if (hasPlayableKeywords || (googlePlayPillClickCount > 0 && elapsedSec >= 6)) {
                revealSwipeCount++
                lastRevealSwipeTimeMs = now
                hasAdActuallyShown = true
                service.performSwipe(
                    startX = sw * 0.50f,
                    startY = sh * 0.65f,
                    endX = sw * 0.50f,
                    endY = sh * 0.45f,
                    durationMs = 180L
                )
                return AdStepResult.SwipedToReveal(revealSwipeCount, elapsedSec)
            }
        }

        // 9. 防卡死 Watchdog：若單支廣告超過最大等待秒數 (預設 65 秒)，以計時強制返回鍵突破容器防護強制退回；
        // 若連續 2 次強制返回仍無法脫離第三方容器／卡死畫面，回傳 ForceBringGameToFront 拉回散步趣。
        if (elapsedSec >= config.maxAdWaitSeconds && now - lastBackActionTimeMs >= 2000L) {
            if (watchdogForceBackAttempts >= 2) {
                lastBackActionTimeMs = now
                val reason = service.lastBackRejectReason.ifBlank { "FORCE_BACK_2_ATTEMPTS_NO_ESCAPE" }
                return AdStepResult.ForceBringGameToFront(elapsedSec, fgPkg ?: "unknown", reason)
            }
            watchdogForceBackAttempts++
            lastBackActionTimeMs = now
            val backOk = service.safeNavigateBack(
                minIntervalMs = 300L,
                allowInGame = true,
                forceEscapeAdPackage = true
            )
            if (backOk) {
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "計時強制返回鍵[第${watchdogForceBackAttempts}次]")
            }
            logThrottledBackReject(service, "計時強制返回鍵[第${watchdogForceBackAttempts}次]")
            if (watchdogForceBackAttempts >= 2) {
                val reason = service.lastBackRejectReason.ifBlank { "FORCE_BACK_REJECTED" }
                return AdStepResult.ForceBringGameToFront(elapsedSec, fgPkg ?: "unknown", reason)
            }
        }

        // 播放廣告期間嚴格保持靜候，禁止無差別滑動螢幕以免誤觸購物廣告商品卡或內嵌網頁！
        return AdStepResult.ScanningAd(elapsedSec)
    }

    /**
     * 偵測全螢幕廣告左上角頂欄的 `[▶| Google Play]` 略過/推進膠囊按鈕（如 iHEALTH 走路賺取現金獎勵廣告）。
     * - 嚴格限定於左上角區域 (cy in 2.5%..18.0% sh, cx in 2.0%..42.0% sw)，自適應 16:9 無黑邊與 21:9/22:9 摺疊機頂部黑邊。
     * - 膠囊尺寸必須為小型獨立標籤 (寬度 <= 42% sw, 高度 <= 8% sh)，嚴格排除畫面中下方的商店橫幅或大型廣告圖卡。
     */
    fun findTopLeftGooglePlaySkipPill(
        ocrItems: List<OcrItem>,
        sw: Int,
        sh: Int
    ): Rect? {
        if (sw <= 0 || sh <= 0) return null
        val topLeftItems = ocrItems.filter { item ->
            val cyRatio = item.bounds.centerY().toFloat() / sh
            val cxRatio = item.bounds.centerX().toFloat() / sw
            val wRatio = item.bounds.width().toFloat() / sw
            val hRatio = item.bounds.height().toFloat() / sh
            cyRatio in 0.025f..0.180f &&
                cxRatio in 0.020f..0.420f &&
                wRatio <= 0.42f &&
                hRatio <= 0.08f
        }
        if (topLeftItems.isEmpty()) return null

        // 1. 單一 OCR 行直接包含 "Google Play" 或 "GooglePlay"
        val directPill = topLeftItems.firstOrNull { item ->
            val clean = item.text.replace(" ", "")
            clean.contains("GooglePlay", ignoreCase = true) ||
                (clean.contains("Google", ignoreCase = true) && clean.contains("Play", ignoreCase = true))
        }
        if (directPill != null) {
            return directPill.bounds
        }

        // 2. 同一水平線上分開的 "Google" 與 "Play" 元素合併
        val googleItem = topLeftItems.firstOrNull { it.text.contains("Google", ignoreCase = true) }
        val playItem = topLeftItems.firstOrNull { it.text.contains("Play", ignoreCase = true) }
        if (googleItem != null && playItem != null &&
            kotlin.math.abs(googleItem.bounds.centerY() - playItem.bounds.centerY()) <= sh * 0.025f
        ) {
            return Rect(
                min(googleItem.bounds.left, playItem.bounds.left),
                min(googleItem.bounds.top, playItem.bounds.top),
                max(googleItem.bounds.right, playItem.bounds.right),
                max(googleItem.bounds.bottom, playItem.bounds.bottom)
            )
        }
        return null
    }

    private fun getNextFallbackTarget(
        sw: Int,
        sh: Int,
        ocrItems: List<OcrItem> = emptyList()
    ): Pair<String, Rect> {
        val targets = mutableListOf<Pair<String, Rect>>()

        val hasGooglePlayNear = ocrItems.any { item ->
            val icx = item.bounds.centerX()
            val icy = item.bounds.centerY()
            icx < sw * 0.40f && icy in (sh * 0.04f).toInt()..(sh * 0.16f).toInt() && (
                item.text.contains("Google", ignoreCase = true) ||
                item.text.contains("Play", ignoreCase = true) ||
                item.text.contains("商店")
            )
        }

        // 歷史備援說明：先前版本曾包含 if (!hasGooglePlayNear) 盲點 "螢幕左上角熱區"，但在 Meta 廣告中該處為廣告主專頁頭像 (例如「3M 好物嚴選」)，引發點擊喚起三星瀏覽器 (com.sec.android.app.sbrowser) 的嚴重退步。
        // 現已徹底重構備援盲點序列：完全移除 "螢幕左上角熱區"（即使 !hasGooglePlayNear 為 true 亦嚴禁啟用左上角），杜絕任何喚起外部瀏覽器的危險盲點！
        // 改為以安全關閉優先序列：右上角 (0.91w, 0.045h) -> 右下角 (0.91w, 0.92h) -> 右上角更偏角 (0.95w, 0.08h)

        // 1. 螢幕右上角關閉熱區 (中心錨定 0.91w, 0.045h，標準頂部略過/關閉位置)
        targets.add("螢幕右上角熱區" to Rect(
            (sw * 0.86f).toInt(),
            max((sh * 0.015f).toInt(), 15),
            (sw * 0.96f).toInt(),
            (sh * 0.075f).toInt()
        ))

        // 2. 螢幕右下角關閉熱區 (中心錨定 0.91w, 0.92h，應對將略過/關閉放置於右下角之全螢幕影音廣告)
        targets.add("螢幕右下角熱區" to Rect(
            (sw * 0.86f).toInt(),
            (sh * 0.89f).toInt(),
            (sw * 0.96f).toInt(),
            (sh * 0.95f).toInt()
        ))

        // 3. 螢幕右上角更偏角熱區 (中心錨定 0.95w, 0.08h，應對 16:9 / 4:3 劇院黑邊或更靠外緣之 (X) 按鈕)
        targets.add("內容右上角熱區" to Rect(
            (sw * 0.91f).toInt(),
            (sh * 0.055f).toInt(),
            (sw * 0.99f).toInt(),
            (sh * 0.105f).toInt()
        ))

        val target = targets[fallbackCornerCycle % targets.size]
        fallbackCornerCycle++
        return target
    }

    private fun isHighConfidenceAdTarget(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        return label.contains("Google Play", ignoreCase = true) ||
            label.contains("GooglePlay", ignoreCase = true) ||
            label == "左上角幾何X" ||
            label == "右上角幾何X" ||
            label == "Meta預覽關閉(幾何X)" ||
            label == "Sponsored頂部左上角X"
    }

    private suspend fun recordAndClick(
        service: AutoAdAccessibilityService,
        rect: Rect,
        allowBlacklist: Boolean = true,
        label: String? = null
    ) {
        val effectiveLabel = label ?: lastClickedAdLabel
        if (label != null) {
            lastClickedAdLabel = label
        }
        val pt = Point(rect.centerX(), rect.centerY())
        lastClickedPoint = pt
        // 記錄最近一次點擊是否位於左上角（若隨後觸發外部跳轉，用於封鎖本支廣告的左上角區域）
        lastLeftCornerClickTimeMs = if (isInLeftCornerZone(pt.x, pt.y)) System.currentTimeMillis() else 0L
        clickedHistory.add(pt)
        val radiusPx = getBlacklistRadiusPx(if (currentScreenWidth > 0) currentScreenWidth else 1080)
        val nearbyClicks = clickedHistory.count { p ->
            hypot((p.x - pt.x).toDouble(), (p.y - pt.y).toDouble()) < radiusPx
        }
        val isHighConfidence = isHighConfidenceAdTarget(effectiveLabel)
        if (allowBlacklist) {
            if (!isHighConfidence && nearbyClicks >= 4) {
                // 非高可信目標／盲點類：同一區域 (依螢幕寬度動態換算半徑，1080px 基準對應原 65.0px) 點擊達 4 次仍未關閉，才加入黑名單避免重複空點
                blacklistedPoints.add(pt)
            }
        }
        closeClickCount++
        FloatingOverlayService.clickWithOverlayPassthrough(rect) {
            service.clickRect(rect, maxJitterPx = 2, allowTopSafeZone = true)
        }
    }

    fun isGameScreenVisible(service: AutoAdAccessibilityService, screenBitmap: Bitmap, ocrItems: List<OcrItem>): Boolean {
        // 0. 若畫面正在向廣告伺服器載入廣告（如『載入中...』），代表正在等候廣告，絕非遊戲主畫面！
        if (ocrRecognizer.isAdLoadingScreen(ocrItems)) {
            return false
        }

        // 0B. 若畫面為 LINE 內部促銷宣傳廣告 (如『還沒有邀請好友 實在太可惜了！』)，絕非遊戲主畫面！
        if (ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            return false
        }

        // 0C. 若畫面為「關閉廣告？您將無法獲得獎勵」防放棄確認彈窗，絕非遊戲畫面，保持在廣告監控中點擊 [繼續]！
        val hasAdAbortConfirm = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("無法獲得獎勵") || (t.contains("關閉廣告") && (t.contains("？") || t.contains("?"))) || t.contains("放棄獎勵")
        }
        if (hasAdAbortConfirm) {
            return false
        }

        val fgPkg = service.getForegroundPackageName()
        if (fgPkg != null && AppConfig.isRedirectPackage(fgPkg)) {
            return false
        }
        if (fgPkg != null && !AppConfig.isGamePackage(fgPkg) && fgPkg != "jp.naver.line.android") {
            return false
        }
        if (ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)) {
            return true
        }

        val sh = screenBitmap.height

        // 嚴格安全防護：頂部分頁必須精準匹配標籤文字且長度<=3，且至少同時出現 2 個標籤或配合底部導覽列！
        // 絕對禁止單憑廣告文案中的「遊戲」或「時間」句子即誤判為回到散步趣！
        val topTabCount = ocrItems.count { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.08f..0.18f && t.length <= 3 && (t == "首頁" || t == "步數" || t == "時間" || t == "遊戲" || t == "移動")
        }
        val hasBottomNav = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy > 0.85f && (
                t == "集點" ||
                (t.contains("好友") && !t.contains("邀請") && !t.contains("被") && !t.contains("名單") && t.length <= 4) ||
                (t.contains("使用") && !t.contains("條款") && t.length <= 4) ||
                (t.contains("精選") && t.length <= 4)
            )
        }
        val hasMainScreenMarkers = topTabCount >= 2 || (hasBottomNav && topTabCount >= 1)
        if (hasMainScreenMarkers) {
            return true
        }

        // 檢查是否在四大分頁的真正領獎下半部彈窗 (必須同時具備彈窗標題與取消按鈕，嚴禁因底層廣告粉紅像素誤判)
        val hasBottomModalTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.52f..0.75f &&
                !t.contains("輕鬆") && !t.contains("簡單") && !t.contains("遊玩") && !t.contains("Sponsored") && !t.contains("AD") &&
                (t.contains("獲得票券") || t.contains("獲得金幣"))
        }
        val hasBottomModalCancel = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy in 0.85f..0.97f && t == "取消"
        }
        if (hasBottomModalTitle && hasBottomModalCancel) {
            return true
        }
        // 檢查是否回到每日賓果、好友排行榜、任務、抽抽樂或禮物盒彈窗 (排除全家禮物卡等廣告字眼)
        val matchesSubDialog = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            (cy < sh * 0.35f && (
                t.contains("每日賓果") ||
                t.contains("今日賓果") ||
                t.contains("賓果") ||
                t.contains("好友排行榜") ||
                t.contains("排行榜") ||
                ((t == "禮物" || t == "禮物盒") && !t.contains("卡") && !t.contains("券") && !t.contains("超商")) ||
                t.contains("抽抽樂") ||
                t.contains("任務")
            )) || (cy in (sh * 0.35f).toInt()..(sh * 0.88f).toInt() && (
                t.contains("BINGO") || t.contains("今日上限") || t.contains("COMPLETE") ||
                t.contains("50張") || t.contains("抽1次") || t.contains("免費抽") ||
                t.contains("抽抽樂") || t.contains("中獎") || t.contains("獲得票券") || t.contains("獲得金幣")
            )) || (cy > sh * 0.88f && (
                t == "集點" || t == "好友" || t == "使用" || t == "精選"
            ))
        }
        if (!matchesSubDialog) return false

        return true
    }

    companion object {
        private const val TAG = "AdCloserHandler"
    }
}
