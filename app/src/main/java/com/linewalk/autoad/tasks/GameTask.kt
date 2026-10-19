package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.config.TemplateStorage
import com.linewalk.autoad.ui.FloatingOverlayService
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import kotlin.math.max
import kotlin.math.min

data class TaskTriggerTarget(
    val taskName: String,
    val targetLabel: String,
    val bounds: Rect
)

interface GameTask {
    val id: String
    val displayName: String
    fun isEnabled(config: AppConfig): Boolean

    suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget?
}

/**
 * 檢查按鈕區域是否為「亮色可點擊狀態」（例如粉紅色『獲得票券』、綠色或黃色按鈕），
 * 若已經領完變成灰階按鈕 (R≈G≈B 飽和度極低)，則回傳 false 避免重複點擊死按鈕！
 */
private fun isButtonColorfulAndActive(bitmap: Bitmap, bounds: Rect): Boolean {
    val sw = bitmap.width
    val sh = bitmap.height
    // 取文字框周圍外擴一點的按鈕底色區域
    val left = (bounds.left - 20).coerceIn(0, sw - 1)
    val top = (bounds.top - 10).coerceIn(0, sh - 1)
    val right = (bounds.right + 20).coerceIn(left + 1, sw)
    val bottom = (bounds.bottom + 10).coerceIn(top + 1, sh)

    var colorfulPixels = 0
    var totalSampled = 0
    val stepX = max(1, (right - left) / 10)
    val stepY = max(1, (bottom - top) / 6)

    var y = top
    while (y < bottom) {
        var x = left
        while (x < right) {
            val c = bitmap.getPixel(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            // 粉紅/紅/綠/橘黃色按鈕的 RGB 最大最小差值 (Chroma) 通常 > 45
            if (maxC - minC > 45 && maxC > 120) {
                colorfulPixels++
            }
            totalSampled++
            x += stepX
        }
        y += stepY
    }
    return totalSampled > 0 && (colorfulPixels.toFloat() / totalSampled) >= 0.20f
}

/**
 * 【第一優先任務】步數獎勵任務 (StepBottleTask)：
 * 1. 免取樣直接透過 OCR 辨識畫面中上方的粉紅色『獲得票券』按鈕（y = 0.20 ~ 0.40）！
 * 2. 亦支援比對使用者自訂框選的範本 (STEP_BOTTLE)。
 */
class StepBottleTask : GameTask {
    override val id: String = "step_bottle"
    override val displayName: String = "步數獎勵(獲得票券)"

    override fun isEnabled(config: AppConfig): Boolean = config.enableStepBottleTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        // 1. 直接透過 OCR 尋找《LINE 散步趣》步數舞台區的「獲得票券」按鈕 (完全免手動取樣！)
        val ticketBtnOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("獲得票券", "領取票券", "獲得金幣"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = RectF(0.15f, 0.20f, 0.85f, 0.40f),
            excludeKeywords = listOf("雙重", "LINE POINTS")
        )
        if (ticketBtnOcr != null && isButtonColorfulAndActive(screenBitmap, ticketBtnOcr.bounds)) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = "按鈕[${ticketBtnOcr.text}]",
                bounds = ticketBtnOcr.bounds
            )
        }

        // 2. 使用者自訂框選的水瓶或按鈕範本
        val bottleTemplates = templateStorage.getTemplates(TemplateCategory.STEP_BOTTLE).map { it.bitmap }
        if (bottleTemplates.isNotEmpty()) {
            val match = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = bottleTemplates,
                roiNormalized = TemplateCategory.STEP_BOTTLE.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "步數範本",
                blacklistedCoord = FloatingOverlayService::isInsideOverlay
            )
            if (match != null) {
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "${match.label} (${(match.score * 100).toInt()}%)",
                    bounds = match.bounds
                )
            }
        }
        return null
    }
}

/**
 * 【任務二】首頁（移動距離）獎勵任務 (MoveGaugeTask)
 */
class MoveGaugeTask : GameTask {
    override val id: String = "move_gauge"
    override val displayName: String = "移動距離獎勵"

    override fun isEnabled(config: AppConfig): Boolean = config.enableMoveGaugeTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        // 1. 使用者框選的首頁移動獎勵範本
        val templates = templateStorage.getTemplates(TemplateCategory.MOVE_GAUGE).map { it.bitmap }
        if (templates.isNotEmpty()) {
            val match = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = templates,
                roiNormalized = TemplateCategory.MOVE_GAUGE.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "移動獎勵",
                blacklistedCoord = FloatingOverlayService::isInsideOverlay
            )
            if (match != null) {
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "${match.label} (${(match.score * 100).toInt()}%)",
                    bounds = match.bounds
                )
            }
        }

        // 2. OCR 尋找舞台區的「獲得票券 / 獲得金幣 / 領取」亮色按鈕
        val ocrMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("獲得票券", "獲得金幣", "領取獎勵"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = RectF(0.15f, 0.20f, 0.85f, 0.42f),
            excludeKeywords = listOf("雙重", "LINE POINTS")
        )
        if (ocrMatch != null && isButtonColorfulAndActive(screenBitmap, ocrMatch.bounds)) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = "OCR[${ocrMatch.text}]",
                bounds = ocrMatch.bounds
            )
        }
        return null
    }
}

/**
 * 【任務三】時間（放置）獎勵任務 (IdleRewardTask)
 */
class IdleRewardTask : GameTask {
    override val id: String = "idle_reward"
    override val displayName: String = "時間放置獎勵"

    override fun isEnabled(config: AppConfig): Boolean = config.enableIdleRewardTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        val templates = templateStorage.getTemplates(TemplateCategory.IDLE_REWARD).map { it.bitmap }
        if (templates.isNotEmpty()) {
            val match = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = templates,
                roiNormalized = TemplateCategory.IDLE_REWARD.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "時間獎勵",
                blacklistedCoord = FloatingOverlayService::isInsideOverlay
            )
            if (match != null) {
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "${match.label} (${(match.score * 100).toInt()}%)",
                    bounds = match.bounds
                )
            }
        }
        return null
    }
}

/**
 * 【任務四】抽抽樂 / 糖果看廣告任務 (GachaTask)
 */
class GachaTask : GameTask {
    override val id: String = "gacha_task"
    override val displayName: String = "抽抽樂與糖果"

    override fun isEnabled(config: AppConfig): Boolean = config.enableGachaTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        val templates = templateStorage.getTemplates(TemplateCategory.GACHA_BTN).map { it.bitmap }
        if (templates.isNotEmpty()) {
            val match = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = templates,
                roiNormalized = TemplateCategory.GACHA_BTN.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "抽獎按鈕",
                blacklistedCoord = FloatingOverlayService::isInsideOverlay
            )
            if (match != null) {
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "${match.label} (${(match.score * 100).toInt()}%)",
                    bounds = match.bounds
                )
            }
        }

        val ocrMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("看廣告抽", "免費扭蛋", "再刮一次", "觀看廣告抽"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = RectF(0.10f, 0.25f, 0.90f, 0.85f)
        )
        if (ocrMatch != null) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = "OCR[${ocrMatch.text}]",
                bounds = ocrMatch.bounds
            )
        }
        return null
    }
}
