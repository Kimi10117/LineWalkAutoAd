package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.ui.FloatingOverlayService
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.ScreenViewportCalibrator
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
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget?
}

private fun isButtonColorfulAndActive(bitmap: Bitmap, bounds: Rect): Boolean {
    val sw = bitmap.width
    val sh = bitmap.height
    val padX = max(4, (sw * 0.014f).toInt())
    val padY = max(2, (sh * 0.0033f).toInt())
    val left = (bounds.left - padX).coerceIn(0, sw - 1)
    val top = (bounds.top - padY).coerceIn(0, sh - 1)
    val right = (bounds.right + padX).coerceIn(left + 1, sw)
    val bottom = (bounds.bottom + padY).coerceIn(top + 1, sh)

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

private val COMMON_GAME_AD_EXCLUDES = (listOf(
    "雙重", "LINE POINTS", "輕鬆", "遊玩", "AD", "adjoe", "Sponsored",
    "酷澎", "Coupang", "立即下載", "瞭解詳情", "了解詳情"
) + OcrRecognizer.AD_BAIT_PROMO_KEYWORDS).distinct()

private fun containsBaitPromoText(text: String): Boolean {
    val normalized = text.replace(" ", "")
    return OcrRecognizer.AD_BAIT_PROMO_KEYWORDS.any { kw ->
        normalized.contains(kw.replace(" ", ""), ignoreCase = true)
    }
}

private fun viewportRoiNormalized(
    vp: ScreenViewportCalibrator.ViewportMetrics,
    sw: Int,
    sh: Int,
    leftRatio: Float,
    topRatio: Float,
    rightRatio: Float,
    bottomRatio: Float
): RectF {
    val safeSw = max(1, sw)
    val safeSh = max(1, sh)
    return RectF(
        vp.mapContentX(leftRatio).toFloat() / safeSw,
        vp.mapContentY(topRatio).toFloat() / safeSh,
        vp.mapContentX(rightRatio).toFloat() / safeSw,
        vp.mapContentY(bottomRatio).toFloat() / safeSh
    )
}

/**
 * 主畫面分頁（步數 / 移動 / 時間）共用的主領獎按鈕偵測流程：
 * 嚴格鎖定在 y = 25%..36.5% 安全互動區，結合粉紅按鈕像素驗證、字長上限與廣告宣傳話術黑名單。
 */
private fun findMainStageActiveButton(
    screenBitmap: Bitmap,
    ocrItems: List<OcrItem>,
    templateMatcher: TemplateMatcher,
    ocrRecognizer: OcrRecognizer,
    displayName: String,
    pinkButtonLabel: String,
    ocrKeywords: List<String>
): TaskTriggerTarget? {
    val sw = screenBitmap.width
    val sh = screenBitmap.height
    val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)
    val yMin = vp.mapContentY(0.25f)
    val yMax = vp.mapContentY(0.365f)

    // 1. 優先透過珊瑚粉紅長條按鈕偵測器尋找主畫面 y=25%~36.5% 的領獎按鈕
    val pinkBtn = templateMatcher.findCoralPinkButton(
        screenBitmap = screenBitmap,
        yStartRatio = 0.25f,
        yEndRatio = 0.365f,
        label = pinkButtonLabel
    )
    if (pinkBtn != null && pinkBtn.bounds.centerY() in yMin..yMax) {
        return TaskTriggerTarget(
            taskName = displayName,
            targetLabel = pinkBtn.label,
            bounds = pinkBtn.bounds
        )
    }

    // 2. OCR 尋找彩色的領獎按鈕（限定主互動區 cy <= 0.365f、字長上限與按鈕色塊像素驗證）
    val stageRoi = if (vp.effectiveLeftX == 0 && vp.effectiveRightX == sw &&
        vp.effectiveTopY == 0 && vp.effectiveBottomY == sh
    ) {
        RectF(0.15f, 0.25f, 0.85f, 0.365f)
    } else {
        viewportRoiNormalized(vp, sw, sh, 0.15f, 0.25f, 0.85f, 0.365f)
    }
    val ticketBtnOcr = ocrRecognizer.findMatch(
        items = ocrItems,
        keywords = ocrKeywords,
        screenWidth = sw,
        screenHeight = sh,
        roiNormalized = stageRoi,
        maxTextLength = 10,
        excludeKeywords = COMMON_GAME_AD_EXCLUDES
    )
    if (ticketBtnOcr != null && ticketBtnOcr.bounds.centerY() in yMin..yMax &&
        !containsBaitPromoText(ticketBtnOcr.text) &&
        isButtonColorfulAndActive(screenBitmap, ticketBtnOcr.bounds)
    ) {
        return TaskTriggerTarget(
            taskName = displayName,
            targetLabel = "按鈕[${ticketBtnOcr.text}]",
            bounds = ticketBtnOcr.bounds
        )
    }
    return null
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
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        return findMainStageActiveButton(
            screenBitmap = screenBitmap,
            ocrItems = ocrItems,
            templateMatcher = templateMatcher,
            ocrRecognizer = ocrRecognizer,
            displayName = displayName,
            pinkButtonLabel = "粉紅[獲得票券]鈕",
            ocrKeywords = listOf("獲得票券", "領取票券", "獲得金幣")
        )
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
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)

        // 1 & 2. 主畫面珊瑚粉紅 / 彩色 [獲得金幣 / 獲得票券] 按鈕
        findMainStageActiveButton(
            screenBitmap = screenBitmap,
            ocrItems = ocrItems,
            templateMatcher = templateMatcher,
            ocrRecognizer = ocrRecognizer,
            displayName = displayName,
            pinkButtonLabel = "粉紅[獲得金幣]鈕",
            ocrKeywords = listOf("獲得金幣", "獲得票券")
        )?.let { return it }

        // 3. 【車廂備援】OCR 搜尋列車對話框「乘車費用 / 收取乘車 / 移動一段距離 / 費用」
        val trainBubbleOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("乘車費用", "收取乘車", "乘車", "移動一段距離", "費用", "你可以收取", "一段距離"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = viewportRoiNormalized(vp, sw, sh, 0.10f, 0.18f, 0.90f, 0.365f),
            maxTextLength = 14,
            excludeKeywords = COMMON_GAME_AD_EXCLUDES
        )
        if (trainBubbleOcr != null && !containsBaitPromoText(trainBubbleOcr.text)) {
            val trainRect = Rect(
                vp.mapContentX(0.30f),
                vp.mapContentY(0.27f),
                vp.mapContentX(0.70f),
                vp.mapContentY(0.35f)
            )
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = "列車車廂[${trainBubbleOcr.text}]",
                bounds = trainRect
            )
        }

        // 4. 若主按鈕已反灰且無列車對話框，嚴格判定本分頁已領取完畢，避免誤抓反灰按鈕內之小金幣圖示
        if (templateMatcher.isPopupButtonDisabledGray(screenBitmap, vp.mapContentY(0.305f))) {
            return null
        }

        return null
    }
}

/**
 * 【時間(放置)分頁任務】時間放置莓果/蘋果獎勵 (IdleRewardTask)
 * - 第一優先：若畫面上有「紅利看廣告獲得一顆莓果/蘋果」按鈕，先點紅利看廣告獲得莓果
 * - 第二優先：再點擊「獲得票券」收取成熟莓果/蘋果
 */
class IdleRewardTask : GameTask {
    override val id: String = "idle_reward"
    override val displayName: String = "時間獎勵(獲得票券)"

    /** 記錄當前停留於時間分頁時是否已嘗試點擊過右側/畫面紅利獲莓果按鈕，避免冷卻中無限重試 */
    var hasTriedBonusBerryInCurrentStay: Boolean = false

    override fun isEnabled(config: AppConfig): Boolean = config.enableIdleRewardTask

    /**
     * 尋找時間分頁上的「紅利看廣告獲得一顆莓果/蘋果」按鈕：
     * 1. 檢查時間分頁果樹/黑板旁 (y in 0.17f..0.34f, 特別是右側 x in 0.70f..0.98f 或左側 x in 0.04f..0.32f) 是否有「紅利」粉紅角標或看廣告獲莓果按鈕
     * 2. 排除已處於冷卻倒數 (回復 / 還需 / 00:00) 或黑板已滿 8 顆之狀態
     */
    fun findBonusBerryAdButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        if (hasTriedBonusBerryInCurrentStay) return null
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)

        // 若黑板累積數量已滿 8 顆，必須先收取莓果才能再拿紅利莓果
        val isFullEight = ocrItems.any { item ->
            val cy = (item.bounds.centerY() - vp.effectiveTopY).toFloat() / vp.contentHeight
            val cx = (item.bounds.centerX() - vp.effectiveLeftX).toFloat() / vp.contentWidth
            val t = item.text.replace(" ", "")
            cy in 0.17f..0.27f && cx in 0.25f..0.52f && (t == "8" || t == "x8" || t == "×8")
        }
        if (isFullEight) return null

        // 1. OCR 搜尋時間分頁舞台區 (y in 0.16f..0.34f) 的「紅利」標籤或看廣告獲莓果文字
        val bonusOcr = ocrItems.firstOrNull { item ->
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            val normX = (cx - vp.effectiveLeftX).toFloat() / vp.contentWidth
            val normY = (cy - vp.effectiveTopY).toFloat() / vp.contentHeight
            val t = item.text.replace(" ", "")
            !FloatingOverlayService.isInsideOverlay(cx, cy) &&
                !containsBaitPromoText(t) &&
                normY in 0.16f..0.34f &&
                (normX >= 0.62f || normX <= 0.36f || t.contains("紅利")) &&
                !t.contains("獲得票券") && !t.contains("獲得金幣") &&
                !t.contains("距離下次") && !t.contains("累積數量") &&
                (t.contains("紅利") || t.contains("看廣告") || t.contains("莓果") || t.contains("蘋果") || t.contains("+1") || t.contains("1顆"))
        }
        if (bonusOcr != null) {
            val marginY = (vp.contentHeight * 0.04f).toInt()
            if (!ocrRecognizer.isAreaInCooldown(ocrItems, bonusOcr.bounds.top - marginY, bonusOcr.bounds.bottom + marginY)) {
                val clickBounds = Rect(
                    bonusOcr.bounds.left.coerceAtLeast(vp.mapContentX(0.05f)),
                    bonusOcr.bounds.top.coerceAtLeast(vp.mapContentY(0.17f)),
                    bonusOcr.bounds.right.coerceAtMost(vp.mapContentX(0.96f)),
                    (bonusOcr.bounds.bottom + (vp.contentHeight * 0.025f).toInt()).coerceAtMost(vp.mapContentY(0.34f))
                )
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "時間紅利[${bonusOcr.text}]看廣告獲莓果鈕",
                    bounds = clickBounds
                )
            }
        }

        // 2. 像素級探測：時間分頁右側 (x in 0.73f..0.98f, y in 0.19f..0.30f) 的珊瑚粉紅『紅利』角標或亮橘色 [▶] 紅利獲莓果按鈕
        val rightX1 = vp.mapContentX(0.73f).coerceIn(0, sw - 1)
        val rightX2 = vp.mapContentX(0.98f).coerceIn(rightX1 + 1, sw)
        val rightY1 = vp.mapContentY(0.19f).coerceIn(0, sh - 1)
        val rightY2 = vp.mapContentY(0.30f).coerceIn(rightY1 + 1, sh)

        // 檢查右側區域是否有冷卻時間文字
        val rightInCooldown = ocrItems.any { item ->
            val icx = item.bounds.centerX()
            val icy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            icx in vp.mapContentX(0.65f)..vp.effectiveRightX && icy in rightY1..rightY2 && (
                t.contains("回復") || t.contains("還需") || Regex("""\d{1,2}:\d{2}""").containsMatchIn(t)
            )
        }
        if (!rightInCooldown) {
            val minBonusPts = max(10, (vp.contentWidth * vp.contentHeight) / 74000)
            var bonusPts = 0
            var minX = sw
            var maxX = 0
            var minY = sh
            var maxY = 0
            for (y in rightY1 until rightY2 step 2) {
                for (x in rightX1 until rightX2 step 2) {
                    if (FloatingOverlayService.isInsideOverlay(x, y)) continue
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    // 珊瑚粉紅紅利標籤或亮橘色紅利按鈕
                    val isCoralPink = r > 210 && g in 35..125 && b in 45..135 && (r - g) > 100
                    val isBrightOrange = r > 220 && g in 100..170 && b < 50 && (r - b) > 170
                    if (isCoralPink || isBrightOrange) {
                        bonusPts++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (bonusPts >= minBonusPts &&
                (maxX - minX) >= (vp.contentWidth * 0.10f).toInt() &&
                (maxY - minY) >= (vp.contentHeight * 0.025f).toInt()
            ) {
                val rect = Rect(minX, minY, maxX, maxY)
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "時間右側[紅利看廣告獲莓果]鈕",
                    bounds = rect
                )
            }
        }

        return null
    }

    override suspend fun findTargetOnScreen(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        config: AppConfig,
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)

        // 1. 【第一優先（使用者指定）】：若畫面上有「紅利看廣告獲得一顆莓果/蘋果」按鈕，先點紅利看廣告！
        val bonusBerryTarget = findBonusBerryAdButton(screenBitmap, ocrItems, ocrRecognizer)
        if (bonusBerryTarget != null) {
            hasTriedBonusBerryInCurrentStay = true
            return bonusBerryTarget
        }

        // 2. 【第二優先】：點擊中間珊瑚粉紅 / 彩色 [獲得票券] 按鈕收取成熟莓果/蘋果 (y=25%~36.5%)
        findMainStageActiveButton(
            screenBitmap = screenBitmap,
            ocrItems = ocrItems,
            templateMatcher = templateMatcher,
            ocrRecognizer = ocrRecognizer,
            displayName = displayName,
            pinkButtonLabel = "粉紅[獲得票券]鈕",
            ocrKeywords = listOf("獲得票券", "獲得金幣")
        )?.let { return it }

        // 3. 檢查是否有「蘋果成熟了！可以收成囉！」或果樹收成對話框 (y in 0.25f..0.44f)
        val harvestBubbleOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("成熟了", "可以收成", "收成囉"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = viewportRoiNormalized(vp, sw, sh, 0.20f, 0.25f, 0.80f, 0.44f),
            excludeKeywords = COMMON_GAME_AD_EXCLUDES
        )
        if (harvestBubbleOcr != null && !containsBaitPromoText(harvestBubbleOcr.text)) {
            val btnRect = Rect(
                vp.mapContentX(0.28f),
                vp.mapContentY(0.27f),
                vp.mapContentX(0.72f),
                vp.mapContentY(0.34f)
            )
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, btnRect.centerY())) {
                return TaskTriggerTarget(
                    taskName = displayName,
                    targetLabel = "收成提示[${harvestBubbleOcr.text}]",
                    bounds = btnRect
                )
            }
        }

        return null
    }
}

/**
 * 【遊戲分頁任務】LINE POINTS GAME 獲得金幣與抽獎 (GachaTask)
 * - 嚴格鎖定遊戲機台中央 [[金幣] 獲得金幣] (y = 0.32f..0.58f, x = 0.20f..0.80f)
 * - 嚴格排除上方「輕鬆賺」/「開始遊玩」/「開始遊戲」等外部廣告推廣橫幅！
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
        templateMatcher: TemplateMatcher,
        ocrRecognizer: OcrRecognizer
    ): TaskTriggerTarget? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)
        val yMin = vp.mapContentY(0.32f)
        val yMax = vp.mapContentY(0.58f)

        // 1. 遊戲機台中央『獲得金幣』按鈕位於 y = 0.32f..0.58f, x = 0.20f..0.80f (SM-S7210 實測 y ≈ 0.40f)
        // 達成可領時為珊瑚粉紅/亮紅，未達成/已領取為灰色
        val pinkGameBtn = templateMatcher.findCoralPinkButton(
            screenBitmap = screenBitmap,
            yStartRatio = 0.32f,
            yEndRatio = 0.58f,
            label = "機台[獲得金幣]粉紅鈕"
        )
        if (pinkGameBtn != null && pinkGameBtn.bounds.centerY() in yMin..yMax) {
            return TaskTriggerTarget(
                taskName = displayName,
                targetLabel = pinkGameBtn.label,
                bounds = pinkGameBtn.bounds
            )
        }

        // 2. OCR 嚴格鎖定機台區域 y = 0.32f..0.58f, x = 0.20f..0.80f，嚴格排除宣傳話術廣告！
        val gameCoinOcr = ocrRecognizer.findMatch(
            items = ocrItems,
            keywords = listOf("獲得金幣"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = viewportRoiNormalized(vp, sw, sh, 0.20f, 0.32f, 0.80f, 0.58f),
            maxTextLength = 10,
            excludeKeywords = COMMON_GAME_AD_EXCLUDES
        )
        if (gameCoinOcr != null && gameCoinOcr.bounds.centerY() in yMin..yMax &&
            !containsBaitPromoText(gameCoinOcr.text)
        ) {
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

        // 2B. 備援策略：機台中央紅色膠囊按鈕色彩分析 (y in 0.34f..0.50f, x in 0.20f..0.80f)
        // 特別針對 SM-S7210 等機型上，OCR 漏抓或因按鈕文字被右上角小紅點角標「3」覆蓋時精準定位！
        val redPoints = mutableListOf<android.graphics.Point>()
        val yProbeStart = vp.mapContentY(0.34f)
        val yProbeEnd = vp.mapContentY(0.50f)
        val xProbeStart = vp.mapContentX(0.20f)
        val xProbeEnd = vp.mapContentX(0.80f)
        val scanStep = max(2, vp.contentWidth / 360)
        for (y in yProbeStart..yProbeEnd step scanStep) {
            for (x in xProbeStart..xProbeEnd step scanStep) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 195 && (r - g > 65) && (r - b > 65)) {
                    redPoints.add(android.graphics.Point(x, y))
                }
            }
        }
        val minRedPts = max(12, (vp.contentWidth * vp.contentHeight) / 64800)
        if (redPoints.size >= minRedPts) {
            val minX = redPoints.minOf { it.x }
            val maxX = redPoints.maxOf { it.x }
            val minY = redPoints.minOf { it.y }
            val maxY = redPoints.maxOf { it.y }
            val btnW = maxX - minX
            val btnH = maxY - minY
            if (btnW in (vp.contentWidth * 0.30f).toInt()..(vp.contentWidth * 0.75f).toInt() &&
                btnH in (vp.contentHeight * 0.035f).toInt()..(vp.contentHeight * 0.12f).toInt()
            ) {
                val candidateRect = Rect(minX, minY, maxX, maxY)
                if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, candidateRect.centerY(), candidateRect.centerX())) {
                    return TaskTriggerTarget(
                        taskName = displayName,
                        targetLabel = "機台[獲得金幣]紅色按鈕",
                        bounds = candidateRect
                    )
                }
            }
        }

        return null
    }
}
