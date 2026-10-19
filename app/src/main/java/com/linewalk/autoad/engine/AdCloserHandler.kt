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
 * - 只有當真正偵測到《LINE 散步趣》主畫面 (isLineWalkMainScreenVisible) 時才判定廣告結束，
 *   不會因為廣告畫面內出現「邀請好友」「雙方都能獲得幣」而提早跳出廣告監控！
 * - 內建升級版幾何角落 X 偵測器，支援深色圓底白 X 與細線 X。
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

        // 2. 只有當真正回到《LINE 散步趣》主畫面時，才判定已完成這支廣告！
        if (elapsedSec >= 5 && ocrRecognizer.isLineWalkMainScreenVisible(screenBitmap, ocrItems, templateMatcher)) {
            return AdStepResult.ReturnedToGame(null, "已回到散步趣主畫面")
        }

        // 3. 廣告前 4 秒保護期：靜候播放
        if (elapsedSec < 4) {
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
        if (elapsedSec >= 5 || rewardGrantedOcr != null) {
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
                    (sh * 0.08f).toInt(),
                    (sw * 0.94f).toInt(),
                    (sh * 0.11f).toInt()
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
