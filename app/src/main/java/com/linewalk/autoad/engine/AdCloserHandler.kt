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
            val topLimit = kotlin.math.max(currentScreenHeight * 0.052f, 48f)
            if (y < topLimit) return true
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
            hasAdActuallyShown = true
            markRecentRedirect()
            service.navigateBack()
            return AdStepResult.RedirectRecovered(fgPkg ?: "InAppBrowser")
        }

        // 1A-2. 檢查是否誤入「選單」(設定) 畫面，自動點擊右上角關閉退回散步趣遊戲介面
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
            val closeMenuRect = Rect((sw * 0.85f).toInt(), (sh * 0.04f).toInt(), (sw * 0.98f).toInt(), (sh * 0.10f).toInt())
            service.clickRect(closeMenuRect)
            return AdStepResult.ReturnedToGame(null, "已點擊關閉選單回到散步趣介面")
        }

        // 1A-4. LINE 內部促銷宣傳全螢幕廣告 (如『還沒有邀請好友 實在太可惜了！』『獲得金幣的流程』)：
        // 屬於帶點廣告時可能彈出的促銷全螢幕廣告，絕不等待 30 秒倒數，立即點擊右上角圓形白底 (X) 關閉退回遊戲！
        if (ocrRecognizer.isLineInHousePromoAd(ocrItems)) {
            val promoCloseRect = ocrRecognizer.getLineInHousePromoCloseButton(sw, sh)
            hasAdActuallyShown = true
            recordAndClick(service, promoCloseRect, allowBlacklist = false)
            return AdStepResult.ClickedCloseOrSkip("LINE宣傳廣告關閉(X)", promoCloseRect)
        }

        // 1A-3. Meta 輪播廣告 / 外部跳轉安全防死循環返回機制：
        // 若剛剛剛從外部瀏覽器/Custom Tabs 透過返回鍵回到廣告畫面：
        // 由於點擊該廣告角落或畫面會再度觸發跳轉外部網頁，因此切勿再點擊畫面！
        // 直接對廣告 Activity 發送系統安全返回鍵 (safeNavigateBack)，安全關閉廣告退回遊戲！
        if (hasRecentRedirect) {
            hasRecentRedirect = false
            service.safeNavigateBack()
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
                service.safeNavigateBack()
            }
            return AdStepResult.AdChoicesDismissed
        } else {
            adChoicesDismissCount = 0
        }

        // 1C. 檢查是否處於向伺服器緩衝/載入廣告之等待畫面 (畫面顯示『載入中...』與白色 [取消] 鈕)
        val isAdLoading = ocrRecognizer.isAdLoadingScreen(ocrItems)
        if (isAdLoading) {
            wasAdLoading = true
            hasAdActuallyShown = true
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
            hasAdActuallyShown = true
            adStartTimeMs = System.currentTimeMillis()
        }

        val isGameVisible = isGameScreenVisible(service, screenBitmap, ocrItems)
        if (!isGameVisible) {
            hasAdActuallyShown = true
        }

        // 2A. 若已關閉全螢幕廣告並跳出『獲得15張票券！ / 獲得金幣！』中央領獎彈窗，直接回傳並點擊 [ 關閉 ]！
        if (elapsedSec >= 2 && (hasAdActuallyShown || elapsedSec >= 3)) {
            val modalClose = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)
            if (modalClose != null) {
                return AdStepResult.ReturnedToGame(modalClose.second, "已關閉領獎彈窗 (${modalClose.first})")
            }
        }

        // 2B. 若廣告結束後畫面停留在『獲得票券！/ 獲得金幣！』下半部彈窗且按鈕皆在冷卻中 (下方有 [取消] 按鈕)，直接點擊 [取消] 關閉彈窗回到遊戲！
        if (elapsedSec >= 2 && (hasAdActuallyShown || elapsedSec >= 3)) {
            val hasActiveClaim = templateMatcher.findCoralPinkButton(screenBitmap, 0.66f, 0.88f, "彈窗看廣告鈕") != null
            if (!hasActiveClaim) {
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

        // 3. 廣告前保護期 (2.5 秒)：靜候播放
        if (elapsedSec < 3) {
            return AdStepResult.WaitingGracePeriod(elapsedSec)
        }

        // 4. 【最高優先級 - 略過廣告/關閉按鈕急先鋒 (Active Skip & Close Hunter)】
        // 現代影音廣告（如 LINE チョッキンズ、Unity、Google AdMob）在播放 5 秒後即會出現「略過廣告 / Skip Ad / 広告をスキップ」按鈕，
        // 但畫面上可能仍持續顯示「還有 25 秒」等影片總長倒數！
        // 舊邏輯若先判斷 remainingSec > 1 立即返回，會導致整整 30 秒對略過按鈕視而不見！
        // 因此必須在判定倒數前，先精確偵測無障礙節點與 OCR 畫面中是否已出現可點擊的「略過/跳過」按鈕！

        // 4A. 無障礙節點樹優先檢查 (包含 16:9 劇院黑邊廣告內容頂部的略過按鈕)
        val rootNode = service.getSafeRootNode()
        val closeNode = nodeInspector.findAdCloseNode(rootNode, sw, sh, ::isInvalidOrBlacklisted, isFullScreenAd = true)
        if (closeNode != null) {
            recordAndClick(service, closeNode.bounds)
            return AdStepResult.ClickedCloseOrSkip(closeNode.label, closeNode.bounds)
        }

        // 4B. 全畫面 OCR 明確略過按鈕搜尋 (文字長度放寬至 16，避免截斷 "略過廣告 ▶|", "広告をスキップ", "Skip Ad")
        // 注意：AD_CLOSE_EXCLUDE_KEYWORDS 已排除 "秒後"、"還有"，因此「5秒後可略過廣告」絕不會被誤判，
        // 只有倒數結束變為真正可按的「略過廣告」時才會命中！
        val explicitSkipOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf(
                "略過廣告",
                "跳過廣告",
                "広告をスキップ",
                "Skip Ad",
                "略過",
                "跳過",
                "Skip",
                "SKIP",
                "スキップ"
            ),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.01f, 0.02f, 0.99f, 0.96f),
            excludeKeywords = OcrRecognizer.AD_CLOSE_EXCLUDE_KEYWORDS,
            maxTextLength = 16
        )
        if (explicitSkipOcr != null &&
            !isInvalidOrBlacklisted(explicitSkipOcr.bounds.centerX(), explicitSkipOcr.bounds.centerY())
        ) {
            recordAndClick(service, explicitSkipOcr.bounds, allowBlacklist = false)
            return AdStepResult.ClickedCloseOrSkip("OCR[${explicitSkipOcr.text}]", explicitSkipOcr.bounds)
        }

        // 5. 檢查畫面是否有明確的倒數計時（如「還有 15 秒」）或已獲得獎勵
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

        // 6. 一般全畫面關閉/確定/確認 OCR 辨識 (當倒數完畢或無倒數計時時)
        val fullScreenCloseOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_CLOSE_OCR_KEYWORDS + listOf("確認", "確定"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.01f, 0.02f, 0.99f, 0.96f),
            excludeKeywords = OcrRecognizer.AD_CLOSE_EXCLUDE_KEYWORDS,
            maxTextLength = 16
        )
        if (fullScreenCloseOcr != null &&
            !isInvalidOrBlacklisted(fullScreenCloseOcr.bounds.centerX(), fullScreenCloseOcr.bounds.centerY())
        ) {
            recordAndClick(service, fullScreenCloseOcr.bounds, allowBlacklist = false)
            return AdStepResult.ClickedCloseOrSkip("OCR[${fullScreenCloseOcr.text}]", fullScreenCloseOcr.bounds)
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
                recordAndClick(service, tplMatch.bounds, allowBlacklist = false)
                return AdStepResult.ClickedCloseOrSkip(tplMatch.label, tplMatch.bounds)
            }
        }

        // 8. 第三層辨識 B：啟動「高精度免樣本幾何角落 X 偵測器」（支援右上角/左上角圓底白 X 與細線 X）
        // 大幅加速：2 秒後即開始尋找角落 X；若播放已超過 8 秒或已獲得獎勵，啟動放寬模式 (isRelaxed) 偵測半透明 X！
        if (elapsedSec >= 2 || rewardGrantedOcr != null) {
            val isRelaxed = elapsedSec >= 8 || rewardGrantedOcr != null
            val geoMatch = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isInvalidOrBlacklisted,
                isRelaxed = isRelaxed
            )
            if (geoMatch != null) {
                recordAndClick(service, geoMatch.bounds, allowBlacklist = false)
                return AdStepResult.ClickedCloseOrSkip(geoMatch.label, geoMatch.bounds)
            }
        }

        // 8B. 主動安全返回策略與熱區點擊（全面改用 safeNavigateBack，散步趣在前景時絕不發送返回鍵以免跳出至桌面）：
        val now = System.currentTimeMillis()
        val isOutsideGame = fgPkg == null || (fgPkg !in config.targetGamePackages && fgPkg != "com.linecorp.LGWALK" && fgPkg != "jp.naver.line.android")
        val isSafeToBack = fgPkg != null && fgPkg !in config.targetGamePackages && fgPkg != "com.linecorp.LGWALK" && fgPkg != "jp.naver.line.android"
        if (remainingSec == null && elapsedSec >= 5) {
            if (redirectCount > 0 && isSafeToBack) {
                service.safeNavigateBack()
                lastBackActionTimeMs = now
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "重定向安全返回鍵")
            }

            // 針對互動式可玩小遊戲廣告 (Playable Ad，例如飛刀命中、旋轉輪盤等)：
            // 僅在遊戲內部時於第 5~8 秒且尚未點擊過時輕點畫面中央下方推進進度，外部應用嚴格禁止！
            val isPlayableAd = ocrItems.any { item ->
                val t = item.text.uppercase()
                t.contains("DOWNLOAD") || t.contains("INSTALL") || t.contains("PLAY")
            }
            if (!isOutsideGame && isPlayableAd && elapsedSec in 5..8 && closeClickCount == 0) {
                val interactiveTap = Rect(
                    (sw * 0.45f).toInt(),
                    (sh * 0.65f).toInt(),
                    (sw * 0.55f).toInt(),
                    (sh * 0.72f).toInt()
                )
                closeClickCount++
                service.clickRect(interactiveTap, maxJitterPx = 2)
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "互動可玩廣告推進點擊")
            }

            // 盲點熱區：當處於非散步趣外部應用時，嚴格停用盲點熱區，防止在外部 App 或手機桌面誤觸！
            if (!isOutsideGame && now - lastFallbackCornerTimeMs >= 2500L) {
                val (label, targetRect) = getNextFallbackTarget(sw, sh, ocrItems)
                lastFallbackCornerTimeMs = now
                recordAndClick(service, targetRect, allowBlacklist = false)
                if (isSafeToBack) {
                    service.safeNavigateBack()
                    lastBackActionTimeMs = now
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "盲點[$label]+安全返回")
                } else {
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "盲點[$label]")
                }
            }

            if (isSafeToBack && now - lastBackActionTimeMs >= 2500L) {
                service.safeNavigateBack()
                lastBackActionTimeMs = now
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "安全返回鍵")
            }
        }

        // 9. 防卡死 Watchdog：若單支廣告超過最大等待秒數 (預設 32 秒)，持續循環點擊各關閉熱區 (外部畫面僅發送安全返回鍵)
        if (elapsedSec >= config.maxAdWaitSeconds) {
            if (!isOutsideGame) {
                val (label, targetRect) = getNextFallbackTarget(sw, sh, ocrItems)
                lastFallbackCornerTimeMs = now
                recordAndClick(service, targetRect, allowBlacklist = false)
                if (isSafeToBack) {
                    service.safeNavigateBack()
                    lastBackActionTimeMs = now
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "強效關閉[$label+安全返回]")
                } else {
                    return AdStepResult.WatchdogBackTriggered(elapsedSec, "強效關閉[$label]")
                }
            } else if (isSafeToBack && now - lastBackActionTimeMs >= 2000L) {
                service.safeNavigateBack()
                lastBackActionTimeMs = now
                return AdStepResult.WatchdogBackTriggered(elapsedSec, "外部逾時安全返回鍵")
            }
        }

        return AdStepResult.ScanningAd(elapsedSec)
    }

    private fun getNextFallbackTarget(
        sw: Int,
        sh: Int,
        ocrItems: List<OcrItem> = emptyList()
    ): Pair<String, Rect> {
        val targets = mutableListOf<Pair<String, Rect>>()

        // 1. 螢幕右上角關閉熱區 (標準全螢幕廣告，y in 5.5%..12.5% 避開系統狀態列)
        targets.add("螢幕右上角熱區" to Rect(
            (sw * 0.88f).toInt(),
            (sh * 0.055f).toInt(),
            (sw * 0.97f).toInt(),
            (sh * 0.125f).toInt()
        ))

        // 2. 內容右上角關閉熱區 (專門應對 16:9 / 4:3 劇院黑邊廣告，點擊黑邊下方的內容角落)
        targets.add("內容右上角熱區" to Rect(
            (sw * 0.88f).toInt(),
            (sh * 0.165f).toInt(),
            (sw * 0.97f).toInt(),
            (sh * 0.230f).toInt()
        ))

        // 3. 螢幕左上角關閉熱區 (若左上方為 Google Play / 商店推廣徽章，絕對避開以免誤跳轉應用商店)
        val leftTopRect = Rect(
            (sw * 0.03f).toInt(),
            (sh * 0.055f).toInt(),
            (sw * 0.14f).toInt(),
            (sh * 0.125f).toInt()
        )
        val hasStoreBadgeOnLeft = ocrItems.any { item ->
            val t = item.text.lowercase()
            (t.contains("google") || t.contains("play") || t.contains("install") || t.contains("下載") || t.contains("安裝")) &&
                item.bounds.centerY() < sh * 0.18f && item.bounds.centerX() < sw * 0.45f
        }
        if (!hasStoreBadgeOnLeft) {
            targets.add("螢幕左上角熱區" to leftTopRect)
        }

        // 4. 內容左上角關閉熱區 (避開左側可能的商店徽章或懸浮窗)
        val hasStoreBadgeOnContentLeft = ocrItems.any { item ->
            val t = item.text.lowercase()
            (t.contains("google") || t.contains("play") || t.contains("install")) &&
                item.bounds.centerY() in (sh * 0.15f).toInt()..(sh * 0.28f).toInt() && item.bounds.centerX() < sw * 0.35f
        }
        if (!hasStoreBadgeOnContentLeft) {
            targets.add("內容左上角熱區" to Rect(
                (sw * 0.03f).toInt(),
                (sh * 0.165f).toInt(),
                (sw * 0.14f).toInt(),
                (sh * 0.230f).toInt()
            ))
        }

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
            // 只有當同一區域 (半徑 65px 內) 在同支廣告中已被點擊滿 5 次仍未關閉廣告，才加入黑名單避免重複誤觸假 X
            val nearbyClicks = clickedHistory.count { p ->
                hypot((p.x - pt.x).toDouble(), (p.y - pt.y).toDouble()) < BLACKLIST_RADIUS_PX
            }
            if (nearbyClicks >= 5) {
                blacklistedPoints.add(pt)
            }
        }
        closeClickCount++
        FloatingOverlayService.clickWithOverlayPassthrough(rect) {
            service.clickRect(rect, maxJitterPx = 2)
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
