package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.ScreenViewportCalibrator

/**
 * 領取禮物按鈕目標類型：
 * @property isAd true 代表綠色外框播放按鈕 [▶] (需要觀看廣告領取)；false 代表實心綠色按鈕 [ 領取 ] (免看廣告直接領取)
 * @property label 識別標籤，用於日誌記錄
 * @property bounds 按鈕點擊區域
 */
data class ClaimGiftTarget(
    val isAd: Boolean,
    val label: String,
    val bounds: Rect
)

/**
 * 接收好友禮物任務 (ReceiveGiftTask)：
 * - 點擊右上角禮物盒圖示 (x ≈ 0.86, y ≈ 0.065) 開啟「禮物」抽屜視窗 (支援在主畫面或好友頁面直接點擊開啟)
 * - 支援兩種領取按鈕：
 *   1. 【最高優先】實心綠色按鈕 [ 領取 ]（「一起送達的禮物」，免看廣告，點擊立即領取）
 *   2. 【次要優先】綠色播放按鈕 [ ▶ ]（「收到...送來的禮物」，需觀看廣告領取）
 * - 領取完當前畫面後支援向下滾動清單搜尋更多禮物
 * - 全部領取完畢後，點擊右上角 (X) 關閉視窗 (x ≈ 0.90, y ≈ 0.23)
 */
class ReceiveGiftTask {

    fun isEnabled(config: AppConfig): Boolean = config.enableReceiveGiftTask

    /**
     * 右上角「禮物盒」圖示座標 (揚聲器與齒輪設定之間，常帶有紅點通知，主頁與好友頁皆通用)
     */
    fun getHeaderGiftBoxRect(sw: Int, sh: Int, screenBitmap: Bitmap? = null): Rect {
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap)
        return Rect(
            vp.mapContentX(0.835f),
            vp.mapContentY(0.045f),
            vp.mapContentX(0.885f),
            vp.mapContentY(0.080f)
        )
    }

    /**
     * 動態定位頂部「禮物盒」圖示（嚴格限定在禮物盒圖示正中央 x ≈ 0.835..0.885，絕不左偏誤點大聲公通知圖示！）
     */
    fun findHeaderGiftBox(screenBitmap: Bitmap?, sw: Int, sh: Int): Rect {
        if (screenBitmap != null) {
            val redDotRect = findHeaderGiftBoxRedDot(screenBitmap)
            if (redDotRect != null) return redDotRect
        }
        return getHeaderGiftBoxRect(sw, sh, screenBitmap)
    }

    /**
     * 檢查右上角「禮物盒」圖示右上緣 (x in 0.868..0.902, y in 0.048..0.062) 是否有未讀紅點通知
     * 嚴格排除左側「大聲公(公告)」圖示的紅點 (x ≈ 0.785..0.805) 以及禮物盒本身的紅緞帶 (x <= 0.865, y >= 0.062)
     */
    fun hasHeaderGiftBoxRedDot(screenBitmap: Bitmap?): Boolean {
        if (screenBitmap == null) return false
        return findHeaderGiftBoxRedDot(screenBitmap) != null
    }

    private fun findHeaderGiftBoxRedDot(screenBitmap: Bitmap): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap)
        val xStart = vp.mapContentX(0.868f)
        val xEnd = vp.mapContentX(0.902f).coerceAtLeast(xStart)
        val yStart = vp.mapContentY(0.048f)
        val yEnd = vp.mapContentY(0.062f).coerceAtLeast(yStart)
        val minRedPixels = maxOf(2, (vp.contentWidth * vp.contentHeight) / 500000)

        var redCount = 0
        for (y in yStart..yEnd) {
            for (x in xStart..xEnd) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 亮紅點特徵 (r > 210, g < 75, b < 80)
                if (r > 210 && g < 75 && b < 80) {
                    redCount++
                    if (redCount >= minRedPixels) {
                        return getHeaderGiftBoxRect(sw, sh, screenBitmap)
                    }
                }
            }
        }
        return null
    }

    /**
     * 檢查「禮物」抽屜是否為空（尚未收到禮物、尚無禮物等）
     */
    fun hasEmptyDrawer(ocrItems: List<OcrItem>): Boolean {
        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("尚未收到禮物") ||
            t.contains("尚未收到") ||
            t.contains("尚無禮物") ||
            t.contains("目前沒有禮物") ||
            t.contains("沒有禮物") ||
            t.contains("沒有收到") ||
            t.contains("無禮物") ||
            t.contains("沒有可領取")
        }
    }

    /**
     * 判斷「禮物」抽屜彈窗是否開啟中：
     * 1. 嚴格排除好友送禮相關彈窗（『要向...贈送金幣嗎？』『也送給自己』『剩餘贈送次數』『贈送完畢』）
     * 2. 若具備空抽屜文字或專屬清單內容（『送來的禮物』『一起送達』『收到...送來的禮』『禮物囉』），立即判定開啟
     * 3. 支援跨行文字合併比對（如第一行『收到黃小鬼送來的禮』與第二行『物囉！』）
     * 4. 若具備標題『禮物』(y in 0.18..0.32)，且畫面中段無好友排行榜文字，判定為開啟
     */
    fun isReceiveGiftDialogOpen(ocrItems: List<OcrItem>, sh: Int): Boolean {
        // 嚴格排除好友送禮相關彈窗（如『要向...贈送金幣嗎？』『也送給自己』『剩餘贈送次數』『贈送完畢』）
        val hasSendGiftModal = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("也送給自己") || t.contains("送給自己") || t.contains("剩餘贈送次數") ||
            (t.contains("要向") && (t.contains("金幣") || t.contains("贈送"))) ||
            t.contains("贈送金幣") || t.contains("送禮物給") || t.contains("贈送禮物") ||
            t.contains("贈送完畢") || (t.contains("已贈送") && t.contains("金幣")) ||
            (t.contains("或者") && t.contains("贈送")) || t.contains("確認贈送") ||
            t.contains("送禮物也送自己") ||
            (t.contains("次數") && (t.contains("5/5") || t.contains("4/5") || t.contains("3/5") || t.contains("2/5") || t.contains("1/5") || t.contains("0/5")))
        }
        if (hasSendGiftModal) return false

        // 檢查是否為空抽屜狀態（『尚未收到禮物。』）
        if (hasEmptyDrawer(ocrItems)) return true

        val effectiveSh = if (sh > 0) sh else 2400

        // 檢查單行專屬內容特徵
        val hasSingleLineDrawerContent = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / effectiveSh
            val t = item.text.replace(" ", "")
            cy in 0.22f..0.88f && (
                t.contains("送來的禮物") ||
                t.contains("一起送達") ||
                t.contains("禮物囉") ||
                t.contains("送來的") ||
                (t.contains("收到") && (t.contains("禮物") || t.contains("送來")))
            )
        }
        if (hasSingleLineDrawerContent) return true

        // 檢查中段文字跨行合併特徵 (解決『收到黃小鬼送來的禮』+『物囉！』被 OCR 分割為兩行)
        val middleItems = ocrItems.filter { item ->
            val cy = item.bounds.centerY().toFloat() / effectiveSh
            cy in 0.22f..0.88f
        }
        val combinedMiddleText = middleItems.joinToString("") { it.text.replace(" ", "") }
        if (combinedMiddleText.contains("收到") && combinedMiddleText.contains("禮物")) return true
        if (combinedMiddleText.contains("送來") && (combinedMiddleText.contains("禮物") || combinedMiddleText.contains("禮"))) return true
        if ((combinedMiddleText.contains("剩下") || combinedMiddleText.contains("剩餘") || combinedMiddleText.contains("截止")) &&
            combinedMiddleText.contains("收到") && (combinedMiddleText.contains("禮物") || combinedMiddleText.contains("送來"))) return true

        // 檢查中段背景是否可見好友排行榜或主畫面看板（當禮物抽屜開啟時，y = 0.20..0.95 被白底抽屜完全遮蔽）
        val hasUnderlyingMiddleContent = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / effectiveSh
            val t = item.text.replace(" ", "")
            cy in 0.16f..0.55f && (
                t.contains("好友排行榜") ||
                t.contains("參加排行榜") ||
                t.contains("查看階層") ||
                t.contains("禮物次數") ||
                t.contains("距離下次") ||
                t.contains("累積數量") ||
                t.contains("今天的步數")
            )
        }

        // 檢查「禮物」抽屜標題 (y in 0.18f..0.32f)
        val hasDrawerTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / effectiveSh
            val t = item.text.trim()
            cy in 0.18f..0.32f && (t == "禮物" || t == "禮物盒" || t.contains("禮物"))
        }
        if (hasDrawerTitle) {
            val hasDrawerSubText = middleItems.any { item ->
                val t = item.text.replace(" ", "")
                t.contains("止") || (t.contains("剩下") && t.contains("天")) || (t.contains("剩餘") && t.contains("天")) || t == "領取" || t.contains("送來") || t.contains("收到")
            }
            if (hasDrawerSubText && !hasUnderlyingMiddleContent) return true
        }

        return hasDrawerTitle && !hasUnderlyingMiddleContent
    }

    /**
     * 尋找禮物清單中下一個待領取的按鈕 (x ≈ 0.60f..0.95f, y ≈ 0.25f..0.52f)
     * 優先順序：
     * 1. 【最高優先】實心綠色按鈕 [ 領取 ] (免看廣告，直接領取)
     * 2. 【次要優先】綠框播放按鈕 [ ▶ ] (需看廣告領取)
     */
    /**
     * 檢查目標按鈕區域內是否確實存在有效的 LINE 亮綠色膠囊按鈕像素（實心「領取」或綠框「▶」），
     * 避免已領完的禮物列（按鈕消失或反灰為「已領取」）因「送來的禮物」或截止時間 OCR 被重複合成點擊。
     */
    fun hasActiveGreenClaimCapsule(screenBitmap: Bitmap, targetRect: Rect): Boolean {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        if (sw <= 0 || sh <= 0 || targetRect.width() <= 0 || targetRect.height() <= 0) {
            return false
        }
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap)
        val minRightX = vp.mapContentX(0.60f)
        val maxRightX = vp.mapContentX(0.96f)
        val padX = maxOf(2, (vp.contentWidth * 0.015f).toInt())
        val padY = maxOf(2, (targetRect.height() * 0.30f).toInt())
        val scanLeft = maxOf(targetRect.left - padX, minRightX).coerceIn(0, sw - 1)
        val scanRight = minOf(targetRect.right + padX, maxRightX).coerceIn(scanLeft + 1, sw)
        val scanTop = (targetRect.top - padY).coerceIn(0, sh - 1)
        val scanBottom = (targetRect.bottom + padY).coerceIn(scanTop + 1, sh)
        val scanW = scanRight - scanLeft
        val scanH = scanBottom - scanTop
        if (scanW <= 2 || scanH <= 2) return false

        val step = maxOf(1, minOf(scanW, scanH) / 48)
        var greenCount = 0
        var totalSampled = 0
        var minGx = Int.MAX_VALUE
        var maxGx = Int.MIN_VALUE

        var y = scanTop
        while (y < scanBottom) {
            var x = scanLeft
            while (x < scanRight) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 135 && g > r + 25 && g > b + 25) {
                    greenCount++
                    if (x < minGx) minGx = x
                    if (x > maxGx) maxGx = x
                }
                totalSampled++
                x += step
            }
            y += step
        }

        if (totalSampled == 0 || greenCount == 0) return false
        val minGreenSamples = maxOf(4, totalSampled / 50)
        val minGreenSpanW = maxOf(6, (scanW * 0.20f).toInt())
        return greenCount >= minGreenSamples && (maxGx - minGx) >= minGreenSpanW
    }

    private fun isRowAlreadyClaimedByOcr(
        rowCenterY: Int,
        ocrItems: List<OcrItem>,
        vp: ScreenViewportCalibrator.ViewportMetrics
    ): Boolean {
        val rowTolerance = maxOf(6, (vp.contentHeight * 0.025f).toInt())
        val rightThresholdX = vp.mapContentX(0.55f)
        return ocrItems.any { item ->
            kotlin.math.abs(item.bounds.centerY() - rowCenterY) <= rowTolerance &&
                item.bounds.centerX() > rightThresholdX &&
                item.text.replace(" ", "").contains("已領")
        }
    }

    /**
     * 在「接收禮物」白底抽屜內尋找下一個可領取的禮物按鈕：
     * 優先順序：
     * 1. 【最高優先】實心綠色按鈕 [ 領取 ] (免看廣告，直接領取)
     * 2. 【次要優先】綠框播放按鈕 [ ▶ ] (需看廣告領取)
     */
    fun findNextClaimGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): ClaimGiftTarget? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap, ocrItems)

        // 若確認為空抽屜狀態（尚未收到禮物），嚴禁尋找按鈕，避免誤點底部的推廣橫幅！
        if (hasEmptyDrawer(ocrItems)) {
            return null
        }

        // 嚴格限定在白底禮物抽屜內部 (y <= 0.52f)，絕不可搜尋至 y > 0.52f 的綠色底層廣告橫幅！
        val x1 = vp.mapContentX(0.60f).coerceIn(0, sw - 1)
        val x2 = vp.mapContentX(0.95f).coerceIn(x1 + 1, sw)
        val y1 = vp.mapContentY(0.25f).coerceIn(0, sh - 1)
        val y2 = vp.mapContentY(0.52f).coerceIn(y1 + 1, sh)
        val scanStep = maxOf(1, vp.contentWidth / 540)

        val greenPoints = mutableListOf<android.graphics.Point>()
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // LINE 亮綠色：G 顯著高於 R 與 B
                if (g > 140 && g > r + 25 && g > b + 25) {
                    greenPoints.add(android.graphics.Point(x, y))
                }
                x += scanStep
            }
            y += scanStep
        }

        val solidClaims = mutableListOf<Rect>()
        val adPlays = mutableListOf<Rect>()

        if (greenPoints.isNotEmpty()) {
            val sortedByY = greenPoints.sortedBy { it.y }
            var currentCluster = mutableListOf<android.graphics.Point>()
            var prevY = sortedByY.first().y
            val yGapTol = maxOf(4, (vp.contentHeight * 0.0065f).toInt())

            for (pt in sortedByY) {
                if (pt.y - prevY <= yGapTol) {
                    currentCluster.add(pt)
                } else {
                    evaluateAndAddCluster(currentCluster, screenBitmap, vp, solidClaims, adPlays)
                    currentCluster = mutableListOf(pt)
                }
                prevY = pt.y
            }
            evaluateAndAddCluster(currentCluster, screenBitmap, vp, solidClaims, adPlays)
        }

        val drawerYMin = vp.mapContentY(0.22f)
        val drawerYMax = vp.mapContentY(0.85f)
        val rightColMinX = vp.mapContentX(0.60f)
        val btnHalfW = (vp.contentWidth * 0.12f).toInt().coerceAtLeast(8)
        val btnHalfH = (vp.contentHeight * 0.020f).toInt().coerceAtLeast(4)
        val btnCenterX = vp.mapContentX(0.785f)

        // 2. 【OCR 輔助搜尋】：
        // 2A. 尋找「領取」OCR 按鈕 (x > 0.60f, y in 0.22f..0.85f, 嚴格排除「已領取」)
        val directClaimOcr = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val t = item.text.trim()
            cy in drawerYMin..drawerYMax && cx > rightColMinX && (t == "領取" || (t.contains("領取") && !t.contains("已")))
        }.sortedBy { it.bounds.top }

        for (item in directClaimOcr) {
            val b = item.bounds
            val padX = (vp.contentWidth * 0.05f).toInt()
            val padY = (vp.contentHeight * 0.01f).toInt()
            val expanded = Rect(
                (b.left - padX).coerceAtLeast(vp.mapContentX(0.65f)),
                b.top - padY,
                (b.right + padX).coerceAtMost(vp.mapContentX(0.92f)),
                b.bottom + padY
            )
            if (!isRowAlreadyClaimedByOcr(expanded.centerY(), ocrItems, vp) &&
                hasActiveGreenClaimCapsule(screenBitmap, expanded) &&
                solidClaims.none { it.contains(expanded.centerX(), expanded.centerY()) }
            ) {
                solidClaims.add(expanded)
            }
        }

        // 2B. 尋找「一起送達的禮物」OCR 行 (按鈕就在右側)
        val togetherGiftOcr = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in drawerYMin..drawerYMax && t.contains("一起送達")
        }.sortedBy { it.bounds.top }

        for (item in togetherGiftOcr) {
            val cy = item.bounds.centerY()
            val togetherRect = Rect(btnCenterX - btnHalfW, cy - btnHalfH, btnCenterX + btnHalfW, cy + btnHalfH)
            if (!isRowAlreadyClaimedByOcr(cy, ocrItems, vp) &&
                hasActiveGreenClaimCapsule(screenBitmap, togetherRect) &&
                solidClaims.none { it.contains(togetherRect.centerX(), togetherRect.centerY()) }
            ) {
                solidClaims.add(togetherRect)
            }
        }

        // 【最優先回傳】：若有實心「領取」按鈕 (免看廣告)，優先點擊！
        if (solidClaims.isNotEmpty()) {
            val firstDirect = solidClaims.minByOrNull { it.top }!!
            return ClaimGiftTarget(
                isAd = false,
                label = "直接領取(免廣告)",
                bounds = firstDirect
            )
        }

        // 2C. 尋找「▶」播放按鈕 OCR
        val playOcr = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in drawerYMin..drawerYMax && cx > rightColMinX && (item.text.contains("▶") || item.text.contains(">"))
        }.sortedBy { it.bounds.top }

        for (item in playOcr) {
            val cy = item.bounds.centerY()
            val probeRect = Rect(btnCenterX - btnHalfW, cy - btnHalfH, btnCenterX + btnHalfW, cy + btnHalfH)
            if (!isRowAlreadyClaimedByOcr(cy, ocrItems, vp) &&
                hasActiveGreenClaimCapsule(screenBitmap, probeRect) &&
                adPlays.none { it.contains(item.bounds.centerX(), cy) }
            ) {
                adPlays.add(item.bounds)
            }
        }

        // 2D. 尋找「收到...送來的禮物」文字行 (支援跨行『送來的』『禮物囉』等，按鈕就在右側)
        val friendGiftTextOcr = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val t = item.text
            cy in drawerYMin..drawerYMax && (
                t.contains("送來的禮物") ||
                t.contains("送來的") ||
                t.contains("禮物囉") ||
                (t.contains("收到") && (t.contains("禮") || t.contains("送")))
            )
        }.sortedBy { it.bounds.top }

        for (item in friendGiftTextOcr) {
            val cy = item.bounds.centerY()
            val playRect = Rect(btnCenterX - btnHalfW, cy - btnHalfH, btnCenterX + btnHalfW, cy + btnHalfH)
            if (!isRowAlreadyClaimedByOcr(cy, ocrItems, vp) &&
                hasActiveGreenClaimCapsule(screenBitmap, playRect) &&
                adPlays.none { it.contains(playRect.centerX(), playRect.centerY()) }
            ) {
                adPlays.add(playRect)
            }
        }

        // 2E. 尋找「今天...止」或「剩下...天」截止文字，綠色播放按鈕就在其正上方
        val deadlineItems = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in vp.mapContentY(0.25f)..drawerYMax && cx > vp.mapContentX(0.55f) &&
            ((item.text.contains("今天") && item.text.contains("止")) ||
             item.text.contains("剩餘") || item.text.contains("剩下") || item.text.contains("天"))
        }.sortedBy { it.bounds.top }

        for (deadlineItem in deadlineItems) {
            val cx = deadlineItem.bounds.centerX()
            val cy = deadlineItem.bounds.centerY()
            val btnCenterY = cy - (vp.contentHeight * 0.038f).toInt()
            val deadlineBtn = Rect(cx - btnHalfW, btnCenterY - btnHalfH, cx + btnHalfW, btnCenterY + btnHalfH)
            if (!isRowAlreadyClaimedByOcr(btnCenterY, ocrItems, vp) &&
                hasActiveGreenClaimCapsule(screenBitmap, deadlineBtn) &&
                adPlays.none { it.contains(deadlineBtn.centerX(), deadlineBtn.centerY()) }
            ) {
                adPlays.add(deadlineBtn)
                break
            }
        }

        // 【次選回傳】：若有綠色播放按鈕 (需看廣告)，依序點擊觀看廣告領取！
        if (adPlays.isNotEmpty()) {
            val firstAd = adPlays.minByOrNull { it.top }!!
            return ClaimGiftTarget(
                isAd = true,
                label = "看廣告領取(▶)",
                bounds = firstAd
            )
        }

        return null
    }

    private fun evaluateAndAddCluster(
        cluster: List<android.graphics.Point>,
        screenBitmap: Bitmap,
        vp: ScreenViewportCalibrator.ViewportMetrics,
        solidClaims: MutableList<Rect>,
        adPlays: MutableList<Rect>
    ) {
        val minClusterPts = maxOf(10, (vp.contentWidth * vp.contentHeight) / 86000)
        if (cluster.size < minClusterPts) return
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val minX = cluster.minOf { it.x }
        val maxX = cluster.maxOf { it.x }
        val minY = cluster.minOf { it.y }
        val maxY = cluster.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        // 驗證按鈕寬度 (約畫布寬度的 15% ~ 38%) 與高度 (約畫布高度的 1.8% ~ 7.0%)
        if (width in (vp.contentWidth * 0.15f).toInt()..(vp.contentWidth * 0.38f).toInt() &&
            height in (vp.contentHeight * 0.018f).toInt()..(vp.contentHeight * 0.070f).toInt()
        ) {
            val rect = Rect(minX, minY, maxX, maxY)
            val insetX = (width * 0.25f).toInt().coerceAtLeast(2)
            val insetY = (height * 0.25f).toInt().coerceAtLeast(2)
            var whiteCount = 0
            var greenCount = 0
            var totalInner = 0

            val startX = (minX + insetX).coerceIn(0, sw - 1)
            val endX = (maxX - insetX).coerceIn(startX, sw - 1)
            val startY = (minY + insetY).coerceIn(0, sh - 1)
            val endY = (maxY - insetY).coerceIn(startY, sh - 1)
            val innerStep = maxOf(1, vp.contentWidth / 540)

            var iy = startY
            while (iy <= endY) {
                var ix = startX
                while (ix <= endX) {
                    val c = screenBitmap.getPixel(ix, iy)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    totalInner++
                    if (r > 200 && g > 200 && b > 200) {
                        whiteCount++
                    } else if (g > 140 && g > r + 25 && g > b + 25) {
                        greenCount++
                    }
                    ix += innerStep
                }
                iy += innerStep
            }

            val whiteRatio = if (totalInner > 0) whiteCount.toFloat() / totalInner else 0f
            val greenRatio = if (totalInner > 0) greenCount.toFloat() / totalInner else 0f

            // 綠框播放按鈕 [ ▶ ]：內部為白底 (whiteRatio > 0.40f)，中間含綠色播放三角形；
            // 實心「領取」按鈕：內部大面積全填滿綠色 (greenRatio > 0.60f)
            val isSolid = greenRatio > 0.60f || (whiteRatio < 0.25f && cluster.size > (width * height * 0.55f))
            if (isSolid) {
                solidClaims.add(rect)
            } else {
                adPlays.add(rect)
            }
        }
    }

    /**
     * 禮物彈窗右上角關閉 (X) 按鈕熱區 (校準至實機中心 x ≈ 0.890f, y ≈ 0.231f，並支援白底面板像素尋跡與全比例自適應)
     */
    fun getCloseButtonBounds(
        sw: Int,
        sh: Int,
        ocrItems: List<OcrItem> = emptyList(),
        screenBitmap: Bitmap? = null,
        attempt: Int = 1
    ): Rect {
        return ScreenViewportCalibrator.findSubDialogCloseButtonBounds(
            sw = sw,
            sh = sh,
            ocrItems = ocrItems,
            screenBitmap = screenBitmap,
            exactHeaderTitles = listOf("禮物", "禮物盒"),
            defaultYRatio22to9 = 0.231f,
            attempt = attempt
        )
    }
}
