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
    val left = (bounds.left - 15).coerceIn(0, sw - 1)
    val top = (bounds.top - 8).coerceIn(0, sh - 1)
    val right = (bounds.right + 15).coerceIn(left + 1, sw)
    val bottom = (bounds.bottom + 8).coerceIn(top + 1, sh)

    var coralPinkPixels = 0
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
            if (r > 205 && g in 30..135 && b in 40..145 && (r - g) > 100) {
                coralPinkPixels++
            }
            totalSampled++
            x += stepX
        }
        y += stepY
    }
    return totalSampled > 0 && (coralPinkPixels.toFloat() / totalSampled) >= 0.25f
}

/**
 * 【步數分頁任務】步數獎勵任務 (StepBottleTask)
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
        // 1. 優先透過珊瑚粉紅長條按鈕偵測器尋找主畫面 y=25%~38% 的『獲得票券 / 獲得金幣』按鈕
        val pinkBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.25f,
            yEndRatio = 0.38f,
            label = "粉紅[獲得票券]鈕"
        )
        if (pinkBtn != null) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = pinkBtn.label,
                bounds = pinkBtn.bounds
            )
        }

        // 2. OCR 尋找彩色的『獲得票券 / 領取票券 / 獲得金幣』
        val ticketBtnOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("獲得票券", "領取票券", "獲得金幣"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = RectF(0.15f, 0.25f, 0.85f, 0.38f),
            excludeKeywords = listOf("雙重", "LINE POINTS", "輕鬆賺", "輕鬆", "開始遊玩", "遊玩", "AD", "adjoe", "Sponsored")
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

/**
 * 【首頁(移動)分頁任務】移動距離獎勵 (MoveGaugeTask)
 */
class MoveGaugeTask : GameTask {
    override val id: String = "move_gauge"
    override val displayName: String = "移動獎勵(獲得金幣)"

    override fun isEnabled(config: AppConfig): Boolean = config.enableMoveGaugeTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        val pinkBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.25f,
            yEndRatio = 0.38f,
            label = "粉紅[獲得金幣]鈕"
        )
        if (pinkBtn != null) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = pinkBtn.label,
                bounds = pinkBtn.bounds
            )
        }

        val coinBtnOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("獲得金幣", "獲得票券"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = RectF(0.15f, 0.25f, 0.85f, 0.38f),
            excludeKeywords = listOf("雙重", "LINE POINTS", "輕鬆賺", "輕鬆", "開始遊玩", "遊玩", "AD", "adjoe", "Sponsored")
        )
        if (coinBtnOcr != null && isButtonColorfulAndActive(screenBitmap, coinBtnOcr.bounds)) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = "按鈕[${coinBtnOcr.text}]",
                bounds = coinBtnOcr.bounds
            )
        }

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
        return null
    }
}

/**
 * 【時間(放置)分頁任務】時間放置蘋果獎勵 (IdleRewardTask)
 */
class IdleRewardTask : GameTask {
    override val id: String = "idle_reward"
    override val displayName: String = "時間獎勵(獲得票券)"

    override fun isEnabled(config: AppConfig): Boolean = config.enableIdleRewardTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        val pinkBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.25f,
            yEndRatio = 0.38f,
            label = "粉紅[獲得票券]鈕"
        )
        if (pinkBtn != null) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = pinkBtn.label,
                bounds = pinkBtn.bounds
            )
        }

        val ticketBtnOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("獲得票券", "獲得金幣"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = RectF(0.15f, 0.25f, 0.85f, 0.38f),
            excludeKeywords = listOf("雙重", "LINE POINTS", "輕鬆賺", "輕鬆", "開始遊玩", "遊玩", "AD", "adjoe", "Sponsored")
        )
        if (ticketBtnOcr != null && isButtonColorfulAndActive(screenBitmap, ticketBtnOcr.bounds)) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = "按鈕[${ticketBtnOcr.text}]",
                bounds = ticketBtnOcr.bounds
            )
        }

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
 * 【遊戲分頁任務】LINE POINTS GAME 獲得金幣與抽獎 (GachaTask)
 * - 嚴格鎖定遊戲機台中央 [🪙 獲得金幣] (y = 0.46f..0.58f, x = 0.20f..0.80f)
 * - 嚴格排除上方「輕鬆賺」/「開始遊玩」等外部廣告推廣橫幅！
 * - 若機台按鈕為灰色代表不可點選，回傳 null 交由引擎繼續檢查下一項。
 */
class GachaTask : GameTask {
    override val id: String = "gacha_task"
    override val displayName: String = "遊戲獎勵(獲得金幣)"

    override fun isEnabled(config: AppConfig): Boolean = config.enableGachaTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        // 1. 遊戲機台中央『獲得金幣』按鈕位於 y = 0.46f..0.58f, x = 0.20f..0.80f
        // 達成可領時為珊瑚粉紅，未達成/已領取為灰色
        val pinkGameBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.46f,
            yEndRatio = 0.58f,
            label = "機台[獲得金幣]粉紅鈕"
        )
        if (pinkGameBtn != null) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = pinkGameBtn.label,
                bounds = pinkGameBtn.bounds
            )
        }

        // 2. OCR 嚴格鎖定機台區域 y = 0.46f..0.58f, x = 0.20f..0.80f，嚴格排除上方「輕鬆賺」AD廣告！
        val gameCoinOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("獲得金幣"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = RectF(0.20f, 0.46f, 0.80f, 0.58f),
            excludeKeywords = listOf("輕鬆賺", "輕鬆", "開始遊玩", "遊玩", "AD", "Sponsored", "adjoe", "雙重")
        )
        if (gameCoinOcr != null) {
            val isGray = templateMatcher.isPopupButtonDisabledGray(screenBitmap, gameCoinOcr.bounds.centerY())
            val isActive = isButtonColorfulAndActive(screenBitmap, gameCoinOcr.bounds)
            if (!isGray && isActive) {
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "按鈕[${gameCoinOcr.text}]",
                    bounds = gameCoinOcr.bounds
                )
            }
        }

        // 3. 自訂範本 (若使用者有框選機台金幣按鈕)
        val templates = templateStorage.getTemplates(TemplateCategory.GACHA_BTN).map { it.bitmap }
        if (templates.isNotEmpty()) {
            val match = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = templates,
                roiNormalized = RectF(0.20f, 0.46f, 0.80f, 0.58f),
                threshold = config.templateMatchThreshold,
                labelPrefix = "機台金幣按鈕",
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
