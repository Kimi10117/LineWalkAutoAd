package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.config.TemplateStorage
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher

data class TaskTriggerTarget(
    val taskName: String,
    val targetLabel: String,
    val bounds: Rect
)

/**
 * 《LINE 散步趣》模組化任務介面：
 * 每個任務負責在遊戲畫面中尋找可觸發獎勵的目標（如滿水瓶、移動計量條、放置金幣、扭蛋按鈕）。
 * 點擊目標後的「看廣告彈窗點擊 → 廣告倒數關閉 → 領取確認」統一由 AutomationEngine 與 AdCloserHandler 處理。
 */
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
 * 【第一優先任務】步數獎勵任務 (StepBottleTask)：
 * 偵測畫面上「集滿發亮的水瓶」圖示並點擊，觸發看廣告領金幣彈窗。
 */
class StepBottleTask : GameTask {
    override val id: String = "step_bottle"
    override val displayName: String = "步數水瓶獎勵"

    override fun isEnabled(config: AppConfig): Boolean = config.enableStepBottleTask

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateStorage: TemplateStorage,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        // 1. 優先比對使用者透過懸浮窗框選的「滿水瓶」圖示範本 (STEP_BOTTLE)
        val bottleTemplates = templateStorage.getTemplates(TemplateCategory.STEP_BOTTLE).map { it.bitmap }
        if (bottleTemplates.isNotEmpty()) {
            val match = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = bottleTemplates,
                roiNormalized = TemplateCategory.STEP_BOTTLE.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "滿水瓶"
            )
            if (match != null) {
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "${match.label} (${(match.score * 100).toInt()}%)",
                    bounds = match.bounds
                )
            }
        }

        // 2. OCR 備援：若水瓶下方或旁邊顯示「完成 / MAX / 可領取」等字樣
        val ocrMatch = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("水瓶", "步數獎勵", "集滿", "MAX"),
            screenWidth = screenBitmap.width,
            screenHeight = screenBitmap.height,
            roiNormalized = TemplateCategory.STEP_BOTTLE.defaultSearchRoi
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

/**
 * 【任務二】移動距離獎勵任務 (MoveGaugeTask)：
 * 偵測移動頁面的交通工具/火車車票集滿領獎圖示。
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
        val templates = templateStorage.getTemplates(TemplateCategory.MOVE_GAUGE).map { it.bitmap }
        if (templates.isNotEmpty()) {
            val match = templateMatcher.findBestTemplateMatch(
                screenBitmap = screenBitmap,
                templates = templates,
                roiNormalized = TemplateCategory.MOVE_GAUGE.defaultSearchRoi,
                threshold = config.templateMatchThreshold,
                labelPrefix = "移動獎勵"
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
 * 【任務三】放置/時間獎勵任務 (IdleRewardTask)：
 * 偵測角色定時產出的金幣或寶箱圖示。
 */
class IdleRewardTask : GameTask {
    override val id: String = "idle_reward"
    override val displayName: String = "放置時間獎勵"

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
                labelPrefix = "放置獎勵"
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
 * 【任務四】幸運扭蛋 / 刮刮樂任務 (GachaTask)：
 * 偵測扭蛋或刮刮樂中的免費看廣告抽獎按鈕。
 */
class GachaTask : GameTask {
    override val id: String = "gacha_task"
    override val displayName: String = "扭蛋與刮刮樂"

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
                labelPrefix = "扭蛋按鈕"
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
            roiNormalized = RectF(0.10f, 0.30f, 0.90f, 0.90f)
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
