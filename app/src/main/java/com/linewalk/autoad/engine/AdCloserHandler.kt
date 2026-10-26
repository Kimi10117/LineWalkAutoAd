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
    private var hasAdActuallyShown: Boolean = false
    private var adLoadingTicks: Int = 0
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
            hasAdActuallyShown = false
            adLoadingTicks = 0
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
        if (currentScreenHeight > 0) {
            val sh = currentScreenHeight
            // 排除最頂部通知安全禁區 (中央動態島 y < currentScreenHeight * 0.08f，兩側邊角 y < 4.2% sh)
            if (x in (currentScreenWidth * 0.18f).toInt()..(currentScreenWidth * 0.82f).toInt()) {
                if (y < (currentScreenHeight * 0.08f).toInt()) return true
            } else {
                if (y < (sh * 0.042f).toInt()) return true
            }
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

        if (isExternal || hasBrowserTitle) {
            hasAdActuallyShown = true
            markRecentRedirect()
            lastClickedPoint?.let { badPt ->
                blacklistedPoints.add(badPt)
                lastClickedPoint = null
            }
            service.navigateBack()
            return AdStepResult.RedirectRecovered(fgPkg ?: "InAppBrowser")
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
            service.safeNavigateBack(allowInGame = true)
            return AdStepResult.ReturnedToGame(null, "已發送安全返回鍵退出選單回到散步趣介面")
        }

        // 1A-4. LINE 內部促銷宣傳全螢幕廣告 (如『還沒有邀請好友 實在太可惜了！』『獲得金幣的流程』)：
        // 屬於帶點廣告時可能彈出的促銷全螢幕廣告，絕不等待 30 秒倒數，立即點擊右上角圓形白底 (X) 關閉退回遊戲！
        if (isGame && !isAd && ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            val promoCloseRect = ocrRecognizer.getLineInHousePromoCloseButton(sw, sh)
            hasAdActuallyShown = true
            recordAndClick(service, promoCloseRect, allowBlacklist = true)
            return AdStepResult.ClickedCloseOrSkip("LINE宣傳廣告關閉(X)", promoCloseRect)
        }

        // 1A-3. Meta 輪播廣告 / 外部跳轉安全防死循環返回機制：
        // 若剛剛剛從外部瀏覽器/Custom Tabs 透過返回鍵回到廣告畫面：
        // 由於點擊該廣告角落或畫面會再度觸發跳轉外部網頁，因此切勿再點擊畫面！
        // 直接對廣告 Activity 發送系統安全返回鍵 (safeNavigateBack)，安全關閉廣告退回遊戲！
        if (hasRecentRedirect) {
            hasRecentRedirect = false
            if (!isAd) {
                service.safeNavigateBack()
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "防重定向返回鍵")
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
                service.safeNavigateBack()
            }
            return AdStepResult.AdChoicesDismissed
        } else {
            adChoicesDismissCount = 0
        }

        // 1C. 檢查是否處於向伺服器緩衝/載入廣告之等待畫面 (畫面顯示『載入中...』與白色 [取消] 鈕)
        val isAdLoading = !hasAdActuallyShown && ocrRecognizer.isAdLoadingScreen(ocrItems, screenBitmap)
        if (isAdLoading) {
            wasAdLoading = true
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
                recordAndClick(service, cancelBtn)
                return AdStepResult.ReturnedToGame(null, "廣告載入逾時已取消")
            }
        } else {
            adLoadingTicks = 0
            if (wasAdLoading) {
                // 廣告剛從『載入中...』緩衝完成切換至實際廣告畫面！
                // 重置計時起點，確保實際廣告享有完整的播放保護期 (Grace Period) 與倒數計時！
                wasAdLoading = false
                hasAdActuallyShown = true
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
                !ocrRecognizer.isAreaInCooldown(ocrItems, badgeBtn.bounds.top - btnCheckMargin, badgeBtn.bounds.bottom + (sh * 0.06f).toInt()) &&
                !templateMatcher.isPopupButtonDisabledGray(screenBitmap, badgeBtn.bounds.centerY())

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
        // 1) 廣告確實已經播放過 (hasAdActuallyShown == true) 且已過至少 3 秒 ➔ 正常完成廣告並回到散步趣介面
        // 2) 若廣告點擊後完全未啟動 (hasAdActuallyShown == false)，在未滿 4 秒前保持靜候，嚴禁在第 1 秒誤判回到遊戲！
        // 3) 若超過 4 秒畫面仍完全是遊戲畫面且從未出現過廣告特徵 ➔ 判定為廣告未啟動/無庫存，安全退出且不累計完成次數
        if (isGameVisible) {
            if (hasAdActuallyShown && elapsedSec >= 3) {
                return AdStepResult.ReturnedToGame(null, "已回到散步趣介面")
            } else if (!hasAdActuallyShown && elapsedSec >= 4) {
                return AdStepResult.ReturnedToGame(null, "廣告未成功啟動")
            } else {
                return AdStepResult.WaitingGracePeriod(elapsedSec)
            }
        }

        // 3. 廣告前保護期 (4 秒)：靜候播放
        if (elapsedSec < 4) {
            return AdStepResult.WaitingGracePeriod(elapsedSec)
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
            txt.contains("Skip", ignoreCase = true) || txt.contains(">>") || txt.contains("▶") ||
            desc.contains("略過") || desc.contains("跳過") || desc.contains("略过") || desc.contains("跳过") ||
            desc.contains("Skip", ignoreCase = true) || desc.contains("Close", ignoreCase = true) ||
            desc.contains("關閉") || desc.contains("关闭") ||
            vid.contains("skip", ignoreCase = true) || vid.contains("close", ignoreCase = true)
        }
        if (explicitSkipNode != null) {
            hasAdActuallyShown = true
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            recordAndClick(service, explicitSkipNode.bounds)
            return AdStepResult.ClickedCloseOrSkip(explicitSkipNode.label, explicitSkipNode.bounds)
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
        if (explicitSkipOcr != null &&
            !isInvalidOrBlacklisted(explicitSkipOcr.bounds.centerX(), explicitSkipOcr.bounds.centerY())
        ) {
            hasAdActuallyShown = true
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            recordAndClick(service, explicitSkipOcr.bounds, allowBlacklist = true)
            return AdStepResult.ClickedCloseOrSkip("OCR[${explicitSkipOcr.text}]", explicitSkipOcr.bounds)
        }

        // 4B. 檢查左上角/頂部 Google AdMob 等多段廣告之「Next Ad -> / 下一則」膠囊按鈕 (例如 Ad 1 of 2 略過按鈕)
        val nextAdMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim().replace(" ", "")
            cy in 0.04f..0.15f && cx in 0.02f..0.40f && (
                t.contains("NextAd", ignoreCase = true) ||
                t.contains("Next", ignoreCase = true) ||
                t.contains("下一則") ||
                t.contains("下一個")
            )
        }
        if (nextAdMatch != null && !isInvalidOrBlacklisted(nextAdMatch.bounds.centerX(), nextAdMatch.bounds.centerY())) {
            hasAdActuallyShown = true
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            recordAndClick(service, nextAdMatch.bounds, allowBlacklist = true)
            return AdStepResult.ClickedCloseOrSkip("OCR[${nextAdMatch.text}]", nextAdMatch.bounds)
        }

        // 5. 【倒數計時防護：無明確略過按鈕且倒數中時嚴格保持靜候】
        // 若畫面上未出現明確略過按鈕，且正在倒數計時（如「還有 15 秒」），嚴格保持靜候播放，絕不碰觸螢幕！
        val remainingSec = ocrRecognizer.detectAdCountdownSeconds(ocrItems, sh)
        val rewardGrantedOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_REWARD_GRANTED_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0f, 0f, 1f, 0.25f)
        )
        if (remainingSec != null || rewardGrantedOcr != null) {
            hasAdActuallyShown = true
        }
        if (remainingSec != null && remainingSec > 1 && rewardGrantedOcr == null) {
            return AdStepResult.StillCountingDown(remainingSec, elapsedSec)
        }

        val isMetaAd = fgPkg == "com.facebook.katana" || fgPkg == "com.facebook.orca" ||
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
            }
        // 4C. 【全螢幕快轉/略過按鈕 (▶| 或 >>) 圖示偵測器】
        // 專門應對 Meta Audience Network 等影音廣告右上角的深色圓底高亮快轉略過鈕 (▶|)。
        // 只要播放滿 5 秒（影音廣告略過鈕浮現時間）或已獲取獎勵時立即秒速偵測點擊，絕不拖延等廣告播完！
        if ((isMetaAd || fgPkg == "com.linecorp.LGWKTW" || fgPkg == "com.linecorp.LGWALK") &&
            (elapsedSec >= 5 || rewardGrantedOcr != null) &&
            (remainingSec == null || remainingSec <= 1 || rewardGrantedOcr != null)
        ) {
            val skipMatch = templateMatcher.detectCornerFastForwardSkip(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isInvalidOrBlacklisted
            )
            if (skipMatch != null) {
                hasAdActuallyShown = true
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                recordAndClick(service, skipMatch.bounds, allowBlacklist = true)
                return AdStepResult.ClickedCloseOrSkip(skipMatch.label, skipMatch.bounds)
            }
        }

        // 6. 第一層辨識：檢查無障礙節點樹是否有一般廣告關閉節點 (非略過按鈕，如頂部角落 X 節點)
        val closeNode = nodeInspector.findAdCloseNode(rootNode, sw, sh, ::isInvalidOrBlacklisted, isFullScreenAd = true)
        if (closeNode != null) {
            lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
            recordAndClick(service, closeNode.bounds)
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
            recordAndClick(service, fullScreenCloseOcr.bounds, allowBlacklist = true)
            return AdStepResult.ClickedCloseOrSkip("OCR[${fullScreenCloseOcr.text}]", fullScreenCloseOcr.bounds)
        }

        // 7. 第三層辨識 A：比對使用者在懸浮窗框選儲存的「廣告 X / >> 範本 (AD_CLOSE_ICON)」
        val userCloseTemplates = templateStorage.getTemplates(TemplateCategory.AD_CLOSE_ICON).map { it.bitmap }
        if (userCloseTemplates.isNotEmpty()) {
            val tplMatch = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = userCloseTemplates,
                roiNormalized = RectF(0.0f, 0.05f, 1.0f, 0.95f),
                threshold = config.templateMatchThreshold,
                labelPrefix = "自訂X範本",
                blacklistedCoord = ::isInvalidOrBlacklisted
            )
            if (tplMatch != null) {
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                recordAndClick(service, tplMatch.bounds, allowBlacklist = true)
                return AdStepResult.ClickedCloseOrSkip(tplMatch.label, tplMatch.bounds)
            }
        }

        // 8. 第三層辨識 B：啟動「高精度免樣本幾何角落 X 偵測器」
        // 當具有 Meta 特徵時啟用 includeLeftZone = true，且於 elapsedSec >= 5 或無倒數時即可啟動！
        // 非 Meta 廣告維持在 10 秒以上（或已獲獎勵）且 includeLeftZone = false
        val shouldCheckGeoX = if (isMetaAd) {
            elapsedSec >= 5 || remainingSec == null || rewardGrantedOcr != null
        } else {
            elapsedSec >= 10 || rewardGrantedOcr != null
        }

        if (shouldCheckGeoX) {
            val isRelaxed = elapsedSec >= 18 || rewardGrantedOcr != null
            val geoMatch = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isInvalidOrBlacklisted,
                isRelaxed = isRelaxed,
                includeLeftZone = isMetaAd
            )
            if (geoMatch != null) {
                lastFallbackCornerTimeMs = System.currentTimeMillis() + 4000L
                recordAndClick(service, geoMatch.bounds, allowBlacklist = true)
                return AdStepResult.ClickedCloseOrSkip(geoMatch.label, geoMatch.bounds)
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
                t.contains("立即遊玩") ||
                t.contains("安裝")
            } && ocrItems.any { item ->
                val t = item.text
                t.contains("Facebook", ignoreCase = true) ||
                t.contains("Meta", ignoreCase = true) ||
                t.contains("Google Play", ignoreCase = true) ||
                t.contains("Play 商店") ||
                t.contains("Download", ignoreCase = true) ||
                t.contains("下載") ||
                t.contains("安裝")
            }
        if (isEndCard && !isGame && now - lastBackActionTimeMs >= 2500L) {
            hasAdActuallyShown = true
            lastBackActionTimeMs = now
            lastFallbackCornerTimeMs = now + 4000L
            service.safeNavigateBack(allowInGame = false)
            return AdStepResult.WatchdogBackTriggered(elapsedSec, "廣告結束卡片安全返回鍵")
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
            if (redirectCount > 0 && !isGame && now - lastBackActionTimeMs >= 2500L) {
                service.safeNavigateBack(allowInGame = false)
                lastBackActionTimeMs = now
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "重定向安全返回鍵")
            }

            // 廣告已明確獲得獎勵：優先派發安全系統返回鍵關閉廣告回到遊戲！
            if (rewardGrantedOcr != null && !isGame && now - lastBackActionTimeMs >= 2500L) {
                service.safeNavigateBack(allowInGame = false)
                lastBackActionTimeMs = now
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "已獲獎勵安全返回鍵")
            }

            // Meta 備援關閉熱區：在確認為 Meta 廣告且播放滿 28 秒或無倒數計時時，允許對左上角關閉按鈕區域發送精確點擊，避免廣告停滯超過 30 秒卡死
            if (isMetaAd && (elapsedSec >= 28 || (remainingSec == null && elapsedSec >= 25)) && !isOutsideGame && redirectCount == 0 && closeClickCount == 0 && now - lastFallbackCornerTimeMs >= 3000L) {
                val metaLeftTopHotZone = Rect(
                    (sw * 0.035f).toInt(),
                    (sh * 0.048f).toInt(),
                    (sw * 0.140f).toInt(),
                    (sh * 0.095f).toInt()
                )
                if (!isInvalidOrBlacklisted(metaLeftTopHotZone.centerX(), metaLeftTopHotZone.centerY())) {
                    lastFallbackCornerTimeMs = now
                    recordAndClick(service, metaLeftTopHotZone, allowBlacklist = true)
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "Meta備援左上角熱區")
                }
            }

            // 備援盲點與安全返回：僅在廣告播放超過 40 秒且無關閉鈕時觸發
            if (elapsedSec >= 40) {
                // 若已經發生過外部重定向，或者在散步趣內部，嚴禁盲點角落熱區以免再度跳轉 Google Play！
                // 直接使用安全系統返回鍵關閉廣告最為可靠！
                if (redirectCount > 0 && !isGame) {
                    if (now - lastBackActionTimeMs >= 2000L) {
                        service.safeNavigateBack(allowInGame = false)
                        lastBackActionTimeMs = now
                        return AdStepResult.WatchdogBackTriggered(elapsedSec, "末期安全返回鍵")
                    }
                } else {
                    // 若未發生重定向，輪流嘗試角落備援熱區（右上角/左上角/內容右上角）與安全返回鍵
                    if (!isOutsideGame && now - lastFallbackCornerTimeMs >= 3000L) {
                        val (label, targetRect) = getNextFallbackTarget(sw, sh, ocrItems)
                        lastFallbackCornerTimeMs = now
                        recordAndClick(service, targetRect, allowBlacklist = true)
                        return AdStepResult.WatchdogBackTriggered(elapsedSec, "備援盲點[$label]")
                    }
                    if (!isGame && now - lastBackActionTimeMs >= 3000L) {
                        service.safeNavigateBack(allowInGame = false)
                        lastBackActionTimeMs = now
                        return AdStepResult.WatchdogBackTriggered(elapsedSec, "超時安全返回鍵")
                    }
                }
            }
        }

        // 9. 防卡死 Watchdog：若單支廣告超過最大等待秒數 (預設 48 秒)，以安全返回鍵強制關閉廣告
        if (elapsedSec >= config.maxAdWaitSeconds && now - lastBackActionTimeMs >= 2000L) {
            if (!isOutsideGame) {
                service.safeNavigateBack(allowInGame = true)
            } else {
                service.safeNavigateBack(allowInGame = true)
            }
            lastBackActionTimeMs = now
            return AdStepResult.WatchdogBackTriggered(elapsedSec, "超時安全返回鍵")
        }

        return AdStepResult.ScanningAd(elapsedSec)
    }

    private fun getNextFallbackTarget(
        sw: Int,
        sh: Int,
        ocrItems: List<OcrItem> = emptyList()
    ): Pair<String, Rect> {
        val targets = mutableListOf<Pair<String, Rect>>()

        // 0. 螢幕左上角熱區 (專門應對 Meta 影音/輪播廣告，y in 4.8%..9.5%)
        targets.add("螢幕左上角熱區" to Rect(
            (sw * 0.035f).toInt(),
            (sh * 0.048f).toInt(),
            (sw * 0.140f).toInt(),
            (sh * 0.095f).toInt()
        ))

        // 1. 螢幕右上角關閉熱區 (標準全螢幕廣告，y in 5.5%..11%)
        targets.add("螢幕右上角熱區" to Rect(
            (sw * 0.85f).toInt(),
            (sh * 0.055f).toInt(),
            (sw * 0.97f).toInt(),
            (sh * 0.110f).toInt()
        ))

        // 2. 內容右上角關閉熱區 (專門應對 16:9 / 4:3 劇院黑邊廣告)
        targets.add("內容右上角熱區" to Rect(
            (sw * 0.88f).toInt(),
            (sh * 0.165f).toInt(),
            (sw * 0.97f).toInt(),
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
        val nearbyClicks = clickedHistory.count { p ->
            hypot((p.x - pt.x).toDouble(), (p.y - pt.y).toDouble()) < BLACKLIST_RADIUS_PX
        }
        if (allowBlacklist && nearbyClicks >= 3) {
            // 同一區域 (半徑 65px 內) 在同支廣告中已被點擊滿 3 次仍未關閉，加入黑名單避免重複空點
            blacklistedPoints.add(pt)
        } else if (!allowBlacklist && nearbyClicks >= 5) {
            // 即使是不輕易黑名單的目標，若點擊達 5 次仍未關閉廣告，為防止死循環亦強制加入黑名單
            blacklistedPoints.add(pt)
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
        private const val BLACKLIST_RADIUS_PX = 65.0
    }
}
