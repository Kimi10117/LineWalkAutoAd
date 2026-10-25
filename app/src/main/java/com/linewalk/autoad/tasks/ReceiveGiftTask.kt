package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem

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

    fun isEnabled(config: AppConfig): Boolean = config.enableFriendGiftTask

    /**
     * 右上角「禮物盒」圖示座標 (揚聲器與齒輪設定之間，常帶有紅點通知，主頁與好友頁皆通用)
     */
    fun getHeaderGiftBoxRect(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.835f).toInt(),
            (sh * 0.045f).toInt(),
            (sw * 0.885f).toInt(),
            (sh * 0.080f).toInt()
        )
    }

    /**
     * 動態定位頂部「禮物盒」圖示（優先偵測右上角通知紅點，次選幾何標準座標）
     */
    fun findHeaderGiftBox(screenBitmap: Bitmap?, sw: Int, sh: Int): Rect {
        if (screenBitmap != null) {
            val redDotRect = findHeaderGiftBoxRedDot(screenBitmap)
            if (redDotRect != null) return redDotRect
        }
        return getHeaderGiftBoxRect(sw, sh)
    }

    private fun findHeaderGiftBoxRedDot(screenBitmap: Bitmap): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val xStart = (sw * 0.80f).toInt()
        val xEnd = (sw * 0.92f).toInt()
        val yStart = (sh * 0.04f).toInt()
        val yEnd = (sh * 0.09f).toInt()

        for (y in yStart..yEnd) {
            for (x in xStart..xEnd) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 亮紅點特徵 (r > 200, g < 70, b < 70)
                if (r > 200 && g < 70 && b < 70) {
                    val cx = (x - (sw * 0.012f).toInt()).coerceIn(0, sw - 1)
                    val cy = (y + (sh * 0.010f).toInt()).coerceIn(0, sh - 1)
                    val halfW = (sw * 0.035f).toInt()
                    val halfH = (sh * 0.020f).toInt()
                    return Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
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
            t.contains("沒有禮物")
        }
    }

    /**
     * 判斷「禮物」抽屜彈窗是否開啟中
     * 注意：因「禮物」抽屜覆蓋於下半部，背景（好友排行榜或主畫面）頂部仍可能在OCR中被偵測到，
     * 故只要確認畫面具備「禮物」抽屜的標題或清單內容，且非送禮確認彈窗，即判定為禮物抽屜開啟！
     */
    fun isReceiveGiftDialogOpen(ocrItems: List<OcrItem>, sh: Int): Boolean {
        // 嚴格排除好友送禮相關彈窗（如『要向...贈送金幣嗎？』『也送給自己』『剩餘贈送次數』『贈送完畢』）
        val hasSendGiftModal = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("也送給自己") || t.contains("剩餘贈送次數") ||
            (t.contains("要向") && t.contains("金幣")) ||
            t.contains("贈送完畢") || (t.contains("已贈送") && t.contains("金幣"))
        }
        if (hasSendGiftModal) return false

        // 檢查是否為空抽屜狀態（『尚未收到禮物。』）
        if (hasEmptyDrawer(ocrItems)) return true

        // 檢查是否具有「禮物」抽屜專屬內容特徵：
        val hasDrawerContent = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.25f..0.88f && (
                t.contains("送來的禮物") ||
                t.contains("一起送達") ||
                (t.contains("收到") && t.contains("禮物"))
            )
        }
        if (hasDrawerContent) return true

        // 檢查「禮物」抽屜標題 (y in 0.18f..0.32f) 與內部截止/領取文字
        val hasDrawerTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy in 0.18f..0.32f && (t == "禮物" || t == "禮物盒")
        }
        val hasDrawerSubText = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.28f..0.92f && (t.contains("止") || t.contains("剩下") || t == "領取")
        }

        return hasDrawerTitle && hasDrawerSubText
    }

    /**
     * 尋找禮物清單中下一個待領取的按鈕 (x ≈ 0.60f..0.95f, y ≈ 0.25f..0.52f)
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

        // 若確認為空抽屜狀態（尚未收到禮物），嚴禁尋找按鈕，避免誤點底部的推廣橫幅！
        if (hasEmptyDrawer(ocrItems)) {
            return null
        }

        // 嚴格限定在白底禮物抽屜內部 (y <= 0.52f)，絕不可搜尋至 y > 0.52f 的綠色底層廣告橫幅！
        val x1 = (sw * 0.60f).toInt().coerceIn(0, sw - 1)
        val x2 = (sw * 0.95f).toInt().coerceIn(x1 + 1, sw)
        val y1 = (sh * 0.25f).toInt().coerceIn(0, sh - 1)
        val y2 = (sh * 0.52f).toInt().coerceIn(y1 + 1, sh)

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
                x += 2
            }
            y += 2
        }

        val solidClaims = mutableListOf<Rect>()
        val adPlays = mutableListOf<Rect>()

        if (greenPoints.isNotEmpty()) {
            val sortedByY = greenPoints.sortedBy { it.y }
            var currentCluster = mutableListOf<android.graphics.Point>()
            var prevY = sortedByY.first().y

            for (pt in sortedByY) {
                if (pt.y - prevY <= 15) {
                    currentCluster.add(pt)
                } else {
                    evaluateAndAddCluster(currentCluster, sw, sh, solidClaims, adPlays)
                    currentCluster = mutableListOf(pt)
                }
                prevY = pt.y
            }
            evaluateAndAddCluster(currentCluster, sw, sh, solidClaims, adPlays)
        }

        // 2. 【OCR 輔助搜尋】：
        // 2A. 尋找「領取」OCR 按鈕 (x > 0.60f, y in 0.25f..0.52f, 嚴格排除「已領取」)
        val directClaimOcr = ocrItems.filter { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim()
            cy in 0.25f..0.52f && cx > 0.60f && (t == "領取" || (t.contains("領取") && !t.contains("已")))
        }.sortedBy { it.bounds.top }

        for (item in directClaimOcr) {
            val b = item.bounds
            val padX = (sw * 0.05f).toInt()
            val padY = (sh * 0.01f).toInt()
            val expanded = Rect(
                (b.left - padX).coerceAtLeast((sw * 0.65f).toInt()),
                b.top - padY,
                (b.right + padX).coerceAtMost((sw * 0.92f).toInt()),
                b.bottom + padY
            )
            if (solidClaims.none { it.contains(expanded.centerX(), expanded.centerY()) }) {
                solidClaims.add(expanded)
            }
        }

        // 2B. 尋找「一起送達的禮物」OCR 行 (按鈕就在右側)
        val togetherGiftOcr = ocrItems.filter { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.25f..0.52f && t.contains("一起送達")
        }.sortedBy { it.bounds.top }

        for (item in togetherGiftOcr) {
            val cy = item.bounds.centerY()
            val btnHalfW = (sw * 0.12f).toInt()
            val btnHalfH = (sh * 0.020f).toInt()
            val btnCenterX = (sw * 0.785f).toInt()
            val togetherRect = Rect(btnCenterX - btnHalfW, cy - btnHalfH, btnCenterX + btnHalfW, cy + btnHalfH)
            if (solidClaims.none { it.contains(togetherRect.centerX(), togetherRect.centerY()) }) {
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
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            cy in 0.25f..0.52f && cx > 0.60f && (item.text.contains("▶") || item.text.contains(">"))
        }.sortedBy { it.bounds.top }

        for (item in playOcr) {
            if (adPlays.none { it.contains(item.bounds.centerX(), item.bounds.centerY()) }) {
                adPlays.add(item.bounds)
            }
        }

        // 2D. 尋找「收到...送來的禮物」文字行 (按鈕就在右側)
        val friendGiftTextOcr = ocrItems.filter { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text
            cy in 0.25f..0.52f && (t.contains("送來的禮物") || (t.contains("收到") && t.contains("禮物")))
        }.sortedBy { it.bounds.top }

        for (item in friendGiftTextOcr) {
            val cy = item.bounds.centerY()
            val btnHalfW = (sw * 0.12f).toInt()
            val btnHalfH = (sh * 0.020f).toInt()
            val btnCenterX = (sw * 0.785f).toInt()
            val playRect = Rect(btnCenterX - btnHalfW, cy - btnHalfH, btnCenterX + btnHalfW, cy + btnHalfH)
            if (adPlays.none { it.contains(playRect.centerX(), playRect.centerY()) }) {
                adPlays.add(playRect)
            }
        }

        // 2E. 尋找「今天...止」或「剩下...天」截止文字，綠色播放按鈕就在其正上方約 30~50px
        val deadlineItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            cy in 0.30f..0.52f && cx > sw * 0.55f &&
            ((item.text.contains("今天") && item.text.contains("止")) ||
             item.text.contains("剩餘") || item.text.contains("剩下") || item.text.contains("天"))
        }
        if (deadlineItem != null) {
            val cx = deadlineItem.bounds.centerX()
            val cy = deadlineItem.bounds.centerY()
            val btnHalfW = (sw * 0.12f).toInt()
            val btnHalfH = (sh * 0.020f).toInt()
            val btnCenterY = cy - (sh * 0.038f).toInt()
            val deadlineBtn = Rect(cx - btnHalfW, btnCenterY - btnHalfH, cx + btnHalfW, btnCenterY + btnHalfH)
            if (adPlays.none { it.contains(deadlineBtn.centerX(), deadlineBtn.centerY()) }) {
                adPlays.add(deadlineBtn)
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
        sw: Int,
        sh: Int,
        solidClaims: MutableList<Rect>,
        adPlays: MutableList<Rect>
    ) {
        if (cluster.size < 30) return
        val minX = cluster.minOf { it.x }
        val maxX = cluster.maxOf { it.x }
        val minY = cluster.minOf { it.y }
        val maxY = cluster.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        // 驗證按鈕寬度 (約螢幕寬度的 15% ~ 38%) 與高度 (約螢幕高度的 1.8% ~ 7.0%)
        if (width in (sw * 0.15f).toInt()..(sw * 0.38f).toInt() &&
            height in (sh * 0.018f).toInt()..(sh * 0.070f).toInt()
        ) {
            val rect = Rect(minX, minY, maxX, maxY)
            val totalSamples = ((width / 2) * (height / 2)).coerceAtLeast(1)
            val density = cluster.size.toFloat() / totalSamples
            // 實心「領取」按鈕內部全填滿亮綠色 (密度高達 90% 以上或點數 > 400)
            val isSolid = density > 0.55f || cluster.size > 400
            if (isSolid) {
                solidClaims.add(rect)
            } else {
                adPlays.add(rect)
            }
        }
    }

    /**
     * 禮物彈窗右上角關閉 (X) 按鈕熱區
     */
    fun getCloseButtonBounds(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.84f).toInt(),
            (sh * 0.18f).toInt(),
            (sw * 0.98f).toInt(),
            (sh * 0.26f).toInt()
        )
    }
}
