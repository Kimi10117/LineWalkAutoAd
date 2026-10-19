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

private fun isButtonColorfulAndActive(bitmap: Bitmap, bounds: Rect): Boolean {
    val sw = bitmap.width
    val sh = bitmap.height
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
 * 1. 直接使用珊瑚粉紅按鈕偵測器 (findCoralPinkButton y=0.25~0.37) + OCR 辨識『獲得票券』（即使熊大對話框擋住部分文字也能毫秒級定位！）。
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
        // 1. 優先透過珊瑚粉紅長條按鈕偵測器尋找主畫面 y=25%~37% 的『獲得票券』按鈕
        val pinkBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.25f,
            yEndRatio = 0.37f,
            label = "粉紅[獲得票券]鈕"
        )
        if (pinkBtn != null) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = pinkBtn.label,
                bounds = pinkBtn.bounds
            )
        }

        // 2. OCR 尋找『獲得票券 / 領取票券 / 獲得金幣』
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

        // 3. 使用者自訂框選的水瓶或按鈕範本
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

        val pinkBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.25f,
            yEndRatio = 0.38f,
            label = "粉紅獎勵鈕"
        )
        if (pinkBtn != null) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = pinkBtn.label,
                bounds = pinkBtn.bounds
            )
        }
        return null
    }
}

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
