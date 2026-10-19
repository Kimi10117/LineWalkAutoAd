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
    data class StillCountingDown(val remainingSec: Int, val elapsedSec: Int) : AdStepResult()
    data class WaitingGracePeriod(val elapsedSec: Int) : AdStepResult()
    data class ClickedCloseOrSkip(val sourceLabel: String, val bounds: Rect) : AdStepResult()
    data class ReturnedToGame(val confirmBounds: Rect?, val label: String) : AdStepResult()
    data class WatchdogBackTriggered(val elapsedSec: Int) : AdStepResult()
    data class ScanningAd(val elapsedSec: Int) : AdStepResult()
}

/**
 * 通用多階段廣告關閉處理器 (AdCloserHandler)：
 * 同時掃描畫面頂部 (0~25%) 與底部 (70~96%) 的「跳過 / 略過 / 關閉 / Skip / X / >>」，
 * 並避開懸浮窗本身與手機頂部中央 dynamicSpot 動態島。
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
    private var lastClickedPoint: Point? = null
    private val blacklistedPoints = mutableListOf<Point>()

    fun onEnterAdMode() {
        adStartTimeMs = System.currentTimeMillis()
        closeClickCount = 0
        lastClickedPoint = null
        blacklistedPoints.clear()
    }

    fun getElapsedSeconds(): Int {
        if (adStartTimeMs == 0L) return 0
        return ((System.currentTimeMillis() - adStartTimeMs) / 1000L).toInt()
    }

    private fun isInvalidOrBlacklisted(x: Int, y: Int): Boolean {
        if (FloatingOverlayService.isInsideOverlay(x, y)) return true
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
        val elapsedSec = getElapsedSeconds()

        // 1. 防迷路檢查：若誤觸跳轉到 Google Play 商店或外部瀏覽器，封鎖假 X 座標並立即按 BACK
        val fgPkg = service.getForegroundPackageName()
        if (fgPkg != null && fgPkg in AppConfig.EXTERNAL_REDIRECT_PACKAGES) {
            lastClickedPoint?.let { badPt ->
                blacklistedPoints.add(badPt)
                lastClickedPoint = null
            }
            service.navigateBack()
            return AdStepResult.RedirectRecovered(fgPkg)
        }

        // 2. 檢查是否已回到《LINE 散步趣》領獎完成彈窗
        if (elapsedSec >= 5 && closeClickCount >= 1) {
            val confirmMatch = ocrRecognizer.findMatch(
                items = ocrItems,
                keywords = listOf("確認", "確定", "收下", "獲得", "好", "OK"),
                screenWidth = sw,
                screenHeight = sh,
                roiNormalized = RectF(0.15f, 0.35f, 0.85f, 0.90f),
                excludeKeywords = listOf("秒", "廣告", "商店", "下載", "安裝", "同意並繼續")
            )
            if (confirmMatch != null) {
                return AdStepResult.ReturnedToGame(confirmMatch.bounds, "OCR[${confirmMatch.text}]")
            }
        }

        // 3. 第一層辨識：檢查無障礙節點樹是否有明確的「跳過 / 略過 / 關閉 / X」節點
        val rootNode = service.getSafeRootNode()
        val closeNode = nodeInspector.findAdCloseNode(rootNode, sw, sh, ::isInvalidOrBlacklisted)
        if (closeNode != null && (elapsedSec >= 3 || closeNode.text == "跳過" || closeNode.text == "略過")) {
            recordAndClick(service, closeNode.bounds)
            return AdStepResult.ClickedCloseOrSkip(closeNode.label, closeNode.bounds)
        }

        // 4. 第二層辨識 A：優先檢查全畫面 (含頂部與底部 0.03~0.96) 是否有明確的「跳過 / 略過 / 關閉廣告 / Skip」文字
        // (例如「玩遊戲賺點數」底部的『跳過』按鈕，即使剛進來也能直接點掉！)
        val fullScreenSkipOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_CLOSE_OCR_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.02f, 0.03f, 0.98f, 0.96f),
            excludeKeywords = OcrRecognizer.AD_CLOSE_EXCLUDE_KEYWORDS
        )
        if (fullScreenSkipOcr != null &&
            !isInvalidOrBlacklisted(fullScreenSkipOcr.bounds.centerX(), fullScreenSkipOcr.bounds.centerY())
        ) {
            recordAndClick(service, fullScreenSkipOcr.bounds)
            return AdStepResult.ClickedCloseOrSkip("OCR[${fullScreenSkipOcr.text}]", fullScreenSkipOcr.bounds)
        }

        // 5. 廣告前 4 秒保護期：若無明確「跳過/略過」文字，前 4 秒靜候播放
        if (elapsedSec < 4) {
            return AdStepResult.WaitingGracePeriod(elapsedSec)
        }

        // 6. 檢查畫面頂部是否有倒數計時（如「還有 15 秒」）
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

        // 8. 第三層辨識 B：若廣告已播放超過 12 秒（或已顯示獲得獎勵），啟動「免樣本幾何角落 X 偵測器」
        if (elapsedSec >= 12 || rewardGrantedOcr != null) {
            val geoMatch = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isInvalidOrBlacklisted
            )
            if (geoMatch != null) {
                recordAndClick(service, geoMatch.bounds)
                return AdStepResult.ClickedCloseOrSkip(geoMatch.label, geoMatch.bounds)
            }
        }

        // 9. 防卡死 Watchdog：若單支廣告超過最大等待秒數，嘗試發送系統返回鍵或點右上角標準 X 熱區
        if (elapsedSec >= config.maxAdWaitSeconds) {
            if (elapsedSec % 8 < 4) {
                service.navigateBack()
            } else {
                val fallbackRect = Rect(
                    (sw * 0.90f).toInt(),
                    (sh * 0.05f).toInt(),
                    (sw * 0.95f).toInt(),
                    (sh * 0.085f).toInt()
                )
                recordAndClick(service, fallbackRect)
            }
            return AdStepResult.WatchdogBackTriggered(elapsedSec)
        }

        return AdStepResult.ScanningAd(elapsedSec)
    }

    private suspend fun recordAndClick(service: AutoAdAccessibilityService, rect: Rect) {
        lastClickedPoint = Point(rect.centerX(), rect.centerY())
        closeClickCount++
        service.clickRect(rect)
    }

    companion object {
        private const val BLACKLIST_RADIUS_PX = 65.0
    }
}
