package com.linewalk.autoad.engine

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.config.TemplateStorage
import com.linewalk.autoad.service.AutoAdAccessibilityService
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
 * - 處理 15s / 30s 倒數計時辨識
 * - 處理多階段廣告（先點 >> 進入試玩結尾畫面，再點最終 X）
 * - 處理誤觸跳轉 Google Play / 瀏覽器時的自動返回，並將假 X 座標加入單次黑名單
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

    private fun isBlacklisted(x: Int, y: Int): Boolean {
        return blacklistedPoints.any { pt ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < BLACKLIST_RADIUS_PX
        }
    }

    /**
     * 在廣告狀態中每一個 Tick 執行一次檢查與動作。
     */
    suspend fun processAdTick(
        service: AutoAdAccessibilityService,
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): AdStepResult {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val elapsedSec = getElapsedSeconds()

        // 1. 防迷路檢查：若誤觸跳轉到 Google Play 商店或外部瀏覽器，將上一個點擊座標列入黑名單並立即按 BACK
        val fgPkg = service.getForegroundPackageName()
        if (fgPkg != null && fgPkg in AppConfig.EXTERNAL_REDIRECT_PACKAGES) {
            lastClickedPoint?.let { badPt ->
                blacklistedPoints.add(badPt)
                lastClickedPoint = null
            }
            service.navigateBack()
            return AdStepResult.RedirectRecovered(fgPkg)
        }

        // 2. 檢查是否已經成功回到《LINE 散步趣》遊戲內的領獎完成彈窗
        // 條件：已進入廣告超過 6 秒（且曾點過關閉或廣告自然結束），畫面中央/下方出現「確認 / 獲得 / 領取」
        if (elapsedSec >= 6 && closeClickCount >= 1) {
            val confirmMatch = ocrRecognizer.findMatch(
                items = ocrItems,
                keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
                screenWidth = sw,
                screenHeight = sh,
                roiNormalized = RectF(0.15f, 0.35f, 0.85f, 0.90f),
                excludeKeywords = listOf("秒", "廣告", "商店", "下載", "安裝")
            )
            if (confirmMatch != null) {
                return AdStepResult.ReturnedToGame(confirmMatch.bounds, "OCR[${confirmMatch.text}]")
            }
        }

        // 3. 廣告前 5 秒保護期：靜候播放，避免誤點剛載入的廣告畫面
        if (elapsedSec < 5) {
            return AdStepResult.WaitingGracePeriod(elapsedSec)
        }

        // 4. 檢查畫面頂部是否有明確的倒數計時（如「還有 15 秒」），若有則耐心等待不亂點
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

        // 5. 第一層辨識：檢查無障礙節點樹 (NodeInspector) 是否有原生關閉/略過節點
        val rootNode = service.getSafeRootNode()
        val closeNode = nodeInspector.findAdCloseNode(rootNode, sw, sh, ::isBlacklisted)
        if (closeNode != null) {
            recordAndClick(service, closeNode.bounds)
            return AdStepResult.ClickedCloseOrSkip(closeNode.label, closeNode.bounds)
        }

        // 6. 第二層辨識：使用 ML Kit OCR 尋找頂部 24% 或底部 18% 的「略過 / 跳過 / 關閉 / Skip / X / >>」
        val topCloseOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = OcrRecognizer.AD_CLOSE_OCR_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.0f, 0.0f, 1.0f, 0.24f),
            excludeKeywords = listOf("秒後", "還有", "下載", "安裝")
        )
        if (topCloseOcr != null && !isBlacklisted(topCloseOcr.bounds.centerX(), topCloseOcr.bounds.centerY())) {
            recordAndClick(service, topCloseOcr.bounds)
            return AdStepResult.ClickedCloseOrSkip("OCR頂部[${topCloseOcr.text}]", topCloseOcr.bounds)
        }

        // 7. 第三層辨識 A：比對使用者在懸浮窗框選儲存的「廣告 X / >> 範本 (AD_CLOSE_ICON)」
        val userCloseTemplates = templateStorage.getTemplates(TemplateCategory.AD_CLOSE_ICON).map { it.bitmap }
        if (userCloseTemplates.isNotEmpty()) {
            val tplMatch = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = userCloseTemplates,
                roiNormalized = TemplateCategory.AD_CLOSE_ICON.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "自訂X範本",
                blacklistedCoord = ::isBlacklisted
            )
            if (tplMatch != null) {
                recordAndClick(service, tplMatch.bounds)
                return AdStepResult.ClickedCloseOrSkip(tplMatch.label, tplMatch.bounds)
            }
        }

        // 8. 第三層辨識 B：若廣告已播放超過 14 秒（或已顯示獲得獎勵），啟動「免樣本幾何角落 X 偵測器」
        if (elapsedSec >= 14 || rewardGrantedOcr != null) {
            val geoMatch = templateMatcher.detectCornerGeometricX(
                screenBitmap = screenBitmap,
                blacklistedCoord = ::isBlacklisted
            )
            if (geoMatch != null) {
                recordAndClick(service, geoMatch.bounds)
                return AdStepResult.ClickedCloseOrSkip(geoMatch.label, geoMatch.bounds)
            }
        }

        // 9. 防卡死 Watchdog：若單支廣告超過最大等待秒數 (預設 65 秒)，嘗試發送系統返回鍵或盲點右上角安全關閉區
        if (elapsedSec >= config.maxAdWaitSeconds) {
            if (elapsedSec % 10 < 3) {
                service.navigateBack()
            } else {
                // 嘗試點擊右上角標準 X 熱區 (x=92.5%, y=5.8%)
                val fallbackRect = Rect(
                    (sw * 0.90f).toInt(),
                    (sh * 0.04f).toInt(),
                    (sw * 0.95f).toInt(),
                    (sh * 0.075f).toInt()
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
