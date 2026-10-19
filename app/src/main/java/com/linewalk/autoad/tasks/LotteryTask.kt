package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.TemplateMatcher

/**
 * 抽抽樂任務 (LotteryTask)：
 * - 使用者規則：
 *   1. 【第一優先】：50 抽（BIG 抽抽樂，紅底按鈕含「50張」）
 *   2. 【第二順位】：若不滿 50 抽或 50 抽已無次數，點「免費看廣告抽一次 (免費抽1次)」
 *   3. 【嚴禁點擊】：絕不點擊「5張 抽1次」按鈕！
 */
class LotteryTask {

    fun isEnabled(config: AppConfig): Boolean = config.enableLotteryTask

    fun isLotteryDialogOpen(ocrItems: List<OcrItem>, sh: Int = 0): Boolean {
        val maxTopY = if (sh > 0) sh * 0.30f else 600f
        return ocrItems.any { item ->
            item.bounds.centerY() < maxTopY && (
                item.text.contains("抽抽樂") ||
                item.text.contains("BIG") ||
                (item.text.contains("50張") && item.text.contains("抽"))
            )
        }
    }

    /**
     * 尋找符合規則的抽抽樂按鈕：
     * - 優先回傳 50 抽按鈕 (含 "50張")
     * - 次選回傳免費抽按鈕 (含 "免費抽" 或 "免費")
     * - 絕對過濾排除 "5張" 按鈕！
     */
    fun findTargetDrawButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Pair<String, Rect>? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 【第一優先】：搜尋「50張 抽1次」紅色按鈕 (x in 0.05f..0.52f, y in 0.38f..0.58f)
        val fiftyDrawOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.38f).toInt()..(sh * 0.58f).toInt() &&
            cx < (sw * 0.55f).toInt() &&
            (t.contains("50張") || (t.contains("50") && t.contains("抽"))) &&
            !t.contains("5張") // 雙重防呆
        }
        if (fiftyDrawOcr != null) {
            val cy = fiftyDrawOcr.bounds.centerY()
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                return Pair("50張抽一次", fiftyDrawOcr.bounds)
            }
        }

        // 備援：2D 紅色按鈕聚類偵測 (x: 0.05f..0.52f, y: 0.38f..0.55f)
        val leftRedBtn = findFiftyDrawByColor(screenBitmap)
        if (leftRedBtn != null && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, leftRedBtn.centerY())) {
            // 檢查是否已無次數 (若按鈕區塊內 OCR 包含 "0次" 或 "0 次")
            val isZeroTimes = ocrItems.any { item ->
                val cy = item.bounds.centerY()
                val cx = item.bounds.centerX()
                cy in (sh * 0.38f).toInt()..(sh * 0.58f).toInt() &&
                cx < (sw * 0.55f).toInt() &&
                (item.text.replace(" ", "").contains("剩0次") || item.text.replace(" ", "") == "0次")
            }
            if (!isZeroTimes) {
                return Pair("50張抽一次(紅色按鈕)", leftRedBtn)
            }
        }

        // 2. 【第二順位】：搜尋「免費抽1次」黃色按鈕 (x in 0.52f..0.98f, y in 0.48f..0.60f)
        val freeDrawOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.48f).toInt()..(sh * 0.60f).toInt() &&
            cx > (sw * 0.50f).toInt() &&
            (t.contains("免費抽") || t.contains("免費")) &&
            !t.contains("5張") // 絕不點 5 張！
        }
        if (freeDrawOcr != null) {
            val cy = freeDrawOcr.bounds.centerY()
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                return Pair("免費看廣告抽一次", freeDrawOcr.bounds)
            }
        }

        // 3. 【嚴格防呆】：若任何候選含有 "5張"，絕對回傳 null！
        return null
    }

    private fun findFiftyDrawByColor(screenBitmap: Bitmap): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val redPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.38f).toInt()
        val yEnd = (sh * 0.55f).toInt()
        val xStart = (sw * 0.05f).toInt()
        val xEnd = (sw * 0.52f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 50 抽按鈕為紅色或深珊瑚粉紅
                if (r > 180 && (r - g > 60) && (r - b > 60)) {
                    redPts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (redPts.size >= 80) {
            val minX = redPts.minOf { it.x }
            val maxX = redPts.maxOf { it.x }
            val minY = redPts.minOf { it.y }
            val maxY = redPts.maxOf { it.y }
            return Rect(minX, minY, maxX, maxY)
        }
        return null
    }

    /**
     * 抽抽樂彈窗右上角關閉 (X) 按鈕熱區
     */
    fun getCloseButtonBounds(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.86f).toInt(),
            (sh * 0.095f).toInt(),
            (sw * 0.96f).toInt(),
            (sh * 0.145f).toInt()
        )
    }

    /**
     * 主畫面上的「抽抽樂」圖示按鈕熱區 (第 2 顆，y ≈ 0.42f)
     */
    fun getMainScreenLotteryIconRect(sw: Int, sh: Int, ocrItems: List<OcrItem>): Rect {
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.38f).toInt()..(sh * 0.46f).toInt() &&
            item.bounds.centerX() in (sw * 0.25f).toInt()..(sw * 0.50f).toInt() &&
            item.text.contains("抽抽樂")
        }
        return match?.bounds ?: Rect(
            (sw * 0.28f).toInt(),
            (sh * 0.38f).toInt(),
            (sw * 0.46f).toInt(),
            (sh * 0.46f).toInt()
        )
    }
}
