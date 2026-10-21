package com.linewalk.autoad.engine

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.config.TemplateStorage
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.ui.FloatingOverlayService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import kotlin.math.hypot

sealed class AdStepResult {
    data class RedirectRecovered(val externalPkg: String) : AdStepResult()
    data object AdChoicesDismissed : AdStepResult()
    data class AdLoading(val elapsedSec: Int) : AdStepResult()
    data class StillCountingDown(val remainingSec: Int, val elapsedSec: Int) : AdStepResult()
    data class WaitingGracePeriod(val elapsedSec: Int) : AdStepResult()
    data class ClickedCloseOrSkip(val sourceLabel: String, val bounds: Rect) : AdStepResult()
    data class ReturnedToGame(val confirmBounds: Rect?, val label: String) : AdStepResult()
    data class WatchdogBackTriggered(val elapsedSec: Int, val actionLabel: String = "返回鍵") : AdStepResult()
    data class ScanningAd(val elapsedSec: Int) : AdStepResult()
}

/**
 * 通用多階段廣告關閉處理器 (AdCloserHandler)：
 * - 只有當真正偵測到《LINE 散步趣》主畫面 (isLineWalkMainScreenVisible) 時才判定廣告結束，
 *   不會因為廣告畫面內出現「邀請好友」「雙方都能獲得幣」而提早跳出廣告監控！
 * - 內建升級版幾何角落 X 偵測器，支援深色圓底白 X 與細線 X，並排除 AdChoices『ⓘ ▷』圖示。
 */
class AdCloserHandler(
    private val config: AppConfig,
    private val templateStorage: TemplateStorage,
    private val nodeInspector: NodeInspector,
    private val ocrRecognizer: OcrRecognizer,
    private val templateMatcher: TemplateMatcher
) {

    private var adStartTimeMs: Long = 0L
    private var closeClickCount: Int = 0
    private var adChoicesDismissCount: Int = 0
    private var redirectCount: Int = 0
    private var hasRecentRedirect: Boolean = false
    private var wasAdLoading: Boolean = false
    private var lastClickedPoint: Point? = null
    private var lastBackActionTimeMs: Long = 0L
    private var lastFallbackCornerTimeMs: Long = 0L
    private var fallbackCornerCycle: Int = 0
    private val clickedHistory = mutableListOf<Point>()
    private val blacklistedPoints = mutableListOf<Point>()

    fun onEnterAdMode(preserveRedirectState: Boolean = false) {
        if (!preserveRedirectState) {
            adStartTimeMs = System.currentTimeMillis()
            closeClickCount = 0
            adChoicesDismissCount = 0
            redirectCount = 0
            hasRecentRedirect = false
            wasAdLoading = false
            lastClickedPoint = null
            lastBackActionTimeMs = 0L
            lastFallbackCornerTimeMs = 0L
            fallbackCornerCycle = 0
            clickedHistory.clear()
            blacklistedPoints.clear()
        }
    }

    fun markRecentRedirect(clickedPoint: Point? = null) {
        hasRecentRedirect = true
        redirectCount++
        (clickedPoint ?: lastClickedPoint)?.let { badPt ->
            blacklistedPoints.add(badPt)
        }
        lastClickedPoint = null
    }

    fun getElapsedSeconds(): Int {
        if (adStartTimeMs == 0L) return 0
        return ((System.currentTimeMillis() - adStartTimeMs) / 1000L).toInt()
    }

    private var currentScreenWidth: Int = 0
    private var currentScreenHeight: Int = 0

    private fun isInvalidOrBlacklisted(x: Int, y: Int): Boolean {
        if (FloatingOverlayService.isInsideOverlay(x, y)) return true
        // 僅排除最頂部系統狀態列 (時間、電量，y < 3.5%)，放行廣告右上角/左上角關閉鈕
        if (currentScreenHeight > 0) {
            if (y < currentScreenHeight * 0.035f) return true
        }
        return blacklistedPoints.any { pt ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < BLACKLIST_RADIUS_PX
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
        val elapsedSec = getElapsedSeconds()

        // 1A. 防迷路檢查：若誤觸跳轉到 Google Play 商店或外部瀏覽器/Chrome Custom Tabs，立即按 BACK 並標記跳轉恢復
        val fgPkg = service.getForegroundPackageName()
        val isExternal = fgPkg != null && AppConfig.isRedirectPackage(fgPkg)
        val hasBrowserTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            cy < sh * 0.16f && (
                item.text.contains("正在載入") ||
                item.text.contains("klook") ||
                item.text.contains(".com") ||
                item.text.contains("http") ||
                item.text.contains("www.")
            )
        }

        if (isExternal || hasBrowserTitle) {
            markRecentRedirect()
            service.navigateBack()
            return AdStepResult.RedirectRecovered(fgPkg ?: "InAppBrowser")
        }

        // 1A-2. 檢查是否誤入「選單」(設定) 畫面，自動按返回鍵退回散步趣遊戲介面
        val isMenuScreen = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh
            (cy < 0.12f && (t == "選單" || t.contains("選單"))) ||
            t.contains("應用程式設定") ||
            t.contains("關閉動畫效果") ||
            t.contains("省電模式") ||
            t.contains("位置資訊設定") ||
            t.contains("目前版本")
        }
        if (isMenuScreen) {
            service.navigateBack()
            return AdStepResult.ReturnedToGame(null, "已關閉選單回到散步趣介面")
        }

        // 1A-3. Meta 輪播廣告 / 外部跳轉安全防死循環返回機制：
        // 若剛剛剛從外部瀏覽器/Custom Tabs 透過返回鍵回到廣告畫面：
        // 由於點擊該廣告角落或畫面會再度觸發跳轉外部網頁，因此切勿再點擊畫面！
        // 直接對廣告 Activity 發送系統返回鍵 (navigateBack)，安全關閉廣告退回遊戲！
        if (hasRecentRedirect) {
            hasRecentRedirect = false
            service.navigateBack()
            return AdStepResult.WatchdogBackTriggered(elapsedSec, "防重定向返回鍵")
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
                service.navigateBack()
            }
            return AdStepResult.AdChoicesDismissed
        } else {
            adChoicesDismissCount = 0
        }

        // 1C. 檢查是否處於向伺服器緩衝/載入廣告之等待畫面 (畫面顯示『載入中...』與白色 [取消] 鈕)
        val isAdLoading = ocrRecognizer.isAdLoadingScreen(ocrItems)
        if (isAdLoading) {
            wasAdLoading = true
            // 在容許載入時間 (18 秒) 內，靜候廣告緩衝，嚴禁誤按 [取消] 或誤判回到遊戲！
            if (elapsedSec < 18) {
                return AdStepResult.AdLoading(elapsedSec)
            } else {
                // 超過 18 秒伺服器無回應/無廣告庫存，點擊畫面上的 [取消] 鈕優雅返回遊戲
                wasAdLoading = false
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
                recordAndClick(service, cancelBtn)
                return AdStepResult.ReturnedToGame(null, "廣告載入逾時已取消")
            }
        } else if (wasAdLoading) {
            // 廣告剛從『載入中...』緩衝完成切換至實際廣告畫面！
            // 重置計時起點，確保實際廣告享有完整的播放保護期 (Grace Period) 與倒數計時！
            wasAdLoading = false
            adStartTimeMs = System.currentTimeMillis()
        }

        // 2A. 若已關閉全螢幕廣告並跳出『獲得15張票券！ / 獲得金幣！』中央領獎彈窗，直接回傳並點擊 [ 關閉 ]！
        if (elapsedSec >= 2) {
            val modalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
            if (modalClose != null) {
                return AdStepResult.ReturnedToGame(modalClose.second, "已關閉領獎彈窗 (${modalClose.first})")
            }
        }

        // 2B. 若廣告結束後畫面停留在『獲得票券！/ 獲得金幣！』下半部彈窗且按鈕皆在冷卻中 (下方有 [取消] 按鈕)，直接點擊 [取消] 關閉彈窗回到遊戲！
        if (elapsedSec >= 2) {
            val hasActiveClaim = templateMatcher.findCoralPinkButton(screenBitmap, 0.66f, 0.88f, "彈窗看廣告鈕") != null
            if (!hasActiveClaim) {
                val cancelBtn = ocrRecognizer.findPopupCancelButton(screenBitmap, ocrItems, templateMatcher)
                if (cancelBtn != null) {
                    return AdStepResult.ReturnedToGame(cancelBtn, "已點擊彈窗 [取消]")
                }
            }
        }

        // 2C. 若已回到《LINE 散步趣》介面（且至少過了 1 秒避免同一影格殘留），判定已完成這支廣告！
        if (elapsedSec >= 1 && isGameScreenVisible(service, screenBitmap, ocrItems)) {
            return AdStepResult.ReturnedToGame(null, "已回到散步趣介面")
        }

        // 3. 廣告前保護期 (2.5 秒)：靜候播放
        if (elapsedSec < 3) {
            return AdStepResult.WaitingGracePeriod(elapsedSec)
        }

        // 4. 檢查畫面頂部是否有明確的倒數計時（如「還有 15 秒」）
        val remainingSec = ocrRecognizer.detectAdCountdownSeconds(ocrItems, sh)
        val rewardGrantedOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_REWARD_GRANTED_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0f, 0f, 1f, 0.25f)
        )
        if (remainingSec != null && remainingSec > 1 && rewardGrantedOcr == null) {
            return AdStepResult.StillCountingDown(remainingSec, elapsedSec)
        }

        // 5. 第一層辨識：檢查無障礙節點樹是否有「跳過 / 略過 / 關閉 / X」節點
        val rootNode = service.getSafeRootNode()
        val closeNode = nodeInspector.findAdCloseNode(rootNode, sw, sh, ::isInvalidOrBlacklisted)
        if (closeNode != null) {
            recordAndClick(service, closeNode.bounds)
            return AdStepResult.ClickedCloseOrSkip(closeNode.label, closeNode.bounds)
        }

        // 6. 第二層辨識：全畫面搜尋短字「跳過 / 略過 / 關閉 / 確認 / 確定 / Skip / Close」(字數 <= 6)
        val fullScreenSkipOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_CLOSE_OCR_KEYWORDS + listOf("確認", "確定"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.02f, 0.03f, 0.98f, 0.96f),
            excludeKeywords = OcrRecognizer.AD_CLOSE_EXCLUDE_KEYWORDS,
            maxTextLength = 6
        )
        if (fullScreenSkipOcr != null &&
            !isInvalidOrBlacklisted(fullScreenSkipOcr.bounds.centerX(), fullScreenSkipOcr.bounds.centerY())
        ) {
            recordAndClick(service, fullScreenSkipOcr.bounds)
            return AdStepResult.ClickedCloseOrSkip("OCR[${fullScreenSkipOcr.text}]", fullScreenSkipOcr.bounds)
        }

        // 7. 第三層辨識 A：比對使用者在懸浮窗框選儲存的「廣告 X / >> 範本 (AD_CLOSE_ICON)」
        val userCloseTemplates = templateStorage.getTemplates(TemplateCategory.AD_CLOSE_ICON).map { it.bitmap }
        if (userCloseTemplates.isNotEmpty()) {
            val tplMatch = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = userCloseTemplates,
                roiNormalized = RectF(0.0f, 0.02f, 1.0f, 0.95f),
                threshold = config.templateMatchThreshold,
                labelPrefix = "自訂X範本",
                blacklistedCoord = ::isInvalidOrBlacklisted
            )
            if (tplMatch != null) {
                recordAndClick(service, tplMatch.bounds)
                return AdStepResult.ClickedCloseOrSkip(tplMatch.label, tplMatch.bounds)
            }
        }

        // 8. 第三層辨識 B：啟動「高精度免樣本幾何角落 X 偵測器」（支援右上角/左上角圓底白 X 與細線 X）
        // 大幅加速：3 秒後即開始尋找角落 X；若播放已超過 8 秒或已獲得獎勵，啟動放寬模式 (isRelaxed) 偵測半透明 X！
        if (elapsedSec >= 3 || rewardGrantedOcr != null) {
            val isRelaxed = elapsedSec >= 8 || rewardGrantedOcr != null
            val geoMatch = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isInvalidOrBlacklisted,
                isRelaxed = isRelaxed
            )
            if (geoMatch != null) {
                recordAndClick(service, geoMatch.bounds)
                return AdStepResult.ClickedCloseOrSkip(geoMatch.label, geoMatch.bounds)
            }
        }

        // 8B. 主動安全返回策略與右上角/內容角/Google Play 徽章 X 熱區點擊（大幅加速廣告關閉速度，告別 60 秒乾等）：
        // - 若該廣告曾觸發跳轉外部網頁 (redirectCount > 0)，每次 tick 優先發送雙重返回鍵關閉廣告
        // - 若廣告無倒數計時（例如可玩式互動廣告、應用商店推廣頁、Meta 輪播、靜態廣告或互動式 end-card），且已播放超過 5 秒：
        //   使用時間戳記間隔控制：
        //   - 每間隔 3.0 秒依序輪替點擊各關閉熱區（包含頂部 Google Play 徽章、螢幕右上角、16:9內容右上角、左上角），且熱區不加入黑名單！
        //   - 每間隔 3.0 秒發送系統雙重返回鍵 (service.doubleNavigateBack())！
        val now = System.currentTimeMillis()
        if (remainingSec == null && elapsedSec >= 5) {
            if (redirectCount > 0) {
                service.doubleNavigateBack()
                lastBackActionTimeMs = now
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "重定向雙重返回鍵")
            }

            if (now - lastFallbackCornerTimeMs >= 3000L) {
                val (label, targetRect) = getNextFallbackTarget(sw, sh, ocrItems)
                lastFallbackCornerTimeMs = now
                recordAndClick(service, targetRect, allowBlacklist = false)
                return AdStepResult.ClickedCloseOrSkip(label, targetRect)
            }

            if (now - lastBackActionTimeMs >= 3000L) {
                service.doubleNavigateBack()
                lastBackActionTimeMs = now
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "雙重返回鍵")
            }
        }

        // 9. 防卡死 Watchdog：若單支廣告超過最大等待秒數 (預設 32 秒)，持續循環點擊各關閉熱區與發送返回鍵
        if (elapsedSec >= config.maxAdWaitSeconds) {
            val (label, targetRect) = getNextFallbackTarget(sw, sh, ocrItems)
            lastFallbackCornerTimeMs = now
            recordAndClick(service, targetRect, allowBlacklist = false)
            service.doubleNavigateBack()
            lastBackActionTimeMs = now
            return AdStepResult.WatchdogBackTriggered(elapsedSec, "強效關閉[$label+返回]")
        }

        return AdStepResult.ScanningAd(elapsedSec)
    }

    private fun getNextFallbackTarget(
        sw: Int,
        sh: Int,
        ocrItems: List<OcrItem>
    ): Pair<String, Rect> {
        val targets = mutableListOf<Pair<String, Rect>>()

        // 1. 若頂部出現 Google Play 徽章 (▶| Google Play)，點擊該徽章觸發跳過或喚出關閉機制
        val gpBadge = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            cy < 0.18f && cx < 0.40f && (
                item.text.contains("Google Play", ignoreCase = true) ||
                (item.text.contains("Google", ignoreCase = true) && item.text.contains("Play", ignoreCase = true))
            )
        }
        if (gpBadge != null) {
            targets.add("頂部GooglePlay按鈕" to gpBadge.bounds)
        }

        // 2. 螢幕右上角關閉熱區 (標準全螢幕廣告)
        targets.add("螢幕右上角熱區" to Rect(
            (sw * 0.88f).toInt(),
            (sh * 0.045f).toInt(),
            (sw * 0.97f).toInt(),
            (sh * 0.110f).toInt()
        ))

        // 3. 內容右上角關閉熱區 (專門應對 16:9 / 4:3 劇院黑邊廣告，點擊黑邊下方的內容角落)
        targets.add("內容右上角熱區" to Rect(
            (sw * 0.88f).toInt(),
            (sh * 0.165f).toInt(),
            (sw * 0.97f).toInt(),
            (sh * 0.230f).toInt()
        ))

        // 4. 螢幕左上角關閉熱區 (部分全螢幕廣告 X 在左上)
        targets.add("螢幕左上角熱區" to Rect(
            (sw * 0.03f).toInt(),
            (sh * 0.045f).toInt(),
            (sw * 0.15f).toInt(),
            (sh * 0.110f).toInt()
        ))

        // 5. 內容左上角關閉熱區
        targets.add("內容左上角熱區" to Rect(
            (sw * 0.03f).toInt(),
            (sh * 0.165f).toInt(),
            (sw * 0.15f).toInt(),
            (sh * 0.230f).toInt()
        ))

        val target = targets[fallbackCornerCycle % targets.size]
        fallbackCornerCycle++
        return target
    }

    private suspend fun recordAndClick(
        service: AutoAdAccessibilityService,
        rect: Rect,
        allowBlacklist: Boolean = true
    ) {
        val pt = Point(rect.centerX(), rect.centerY())
        lastClickedPoint = pt
        clickedHistory.add(pt)
        if (allowBlacklist) {
            // 若同一區域 (半徑 65px 內) 在同支廣告中已被點擊滿 2 次卻仍未關閉廣告，自動加入黑名單避免重複誤觸靜音鍵或假 X
            val nearbyClicks = clickedHistory.count { p ->
                hypot((p.x - pt.x).toDouble(), (p.y - pt.y).toDouble()) < BLACKLIST_RADIUS_PX
            }
            if (nearbyClicks >= 2) {
                blacklistedPoints.add(pt)
            }
        }
        closeClickCount++
        service.clickRect(rect, maxJitterPx = 2)
    }

    fun isGameScreenVisible(service: AutoAdAccessibilityService, screenBitmap: Bitmap, ocrItems: List<OcrItem>): Boolean {
        // 0. 若畫面正在向廣告伺服器載入廣告（如『載入中...』），代表正在等候廣告，絕非遊戲主畫面！
        if (ocrRecognizer.isAdLoadingScreen(ocrItems)) {
            return false
        }

        val fgPkg = service.getForegroundPackageName()
        if (fgPkg != null && AppConfig.isRedirectPackage(fgPkg)) {
            return false
        }
        if (ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)) {
            return true
        }

        val sh = screenBitmap.height

        // 只要看見主畫面頂部四大分頁或底部導覽列，代表已完全回到主畫面！
        val hasMainScreenMarkers = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.08f..0.18f && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))) ||
            (cy > 0.85f && (
                t == "集點" ||
                (t.contains("好友") && !t.contains("邀請") && !t.contains("被") && !t.contains("名單") && t.length <= 4) ||
                (t.contains("使用") && !t.contains("條款") && t.length <= 4) ||
                (t.contains("精選") && t.length <= 4)
            ))
        }
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
            )) || (cy in (sh * 0.40f).toInt()..(sh * 0.88f).toInt() && (
                t.contains("BINGO") || t.contains("今日上限") || t.contains("COMPLETE")
            )) || (cy > sh * 0.88f && (
                t == "集點" || t == "好友" || t == "使用" || t == "精選"
            ))
        }
        if (!matchesSubDialog) return false

        return true
    }

    companion object {
        private const val BLACKLIST_RADIUS_PX = 65.0
    }
}
