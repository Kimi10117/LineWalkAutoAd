package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.TemplateMatcher

/**
 * 每日賓果任務 (BingoTask)：
 * - 判斷「每日賓果」彈窗是否開啟
 * - 檢查是否有「立即開啟賓果格」按鈕
 * - 檢查是否有連線獎勵按鈕「Get / ▶ Get / 領取」
 * - 提供右上角 (X) 關閉按鈕座標
 */
class BingoTask {

    fun isEnabled(config: AppConfig): Boolean = config.enableBingoTask

    fun isBingoDialogOpen(ocrItems: List<OcrItem>, sh: Int = 10000): Boolean {
        // 嚴格排除主畫面四大分頁特徵
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh < 10000) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else false
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return false

        return ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            (cy < (if (sh < 10000) sh * 0.16f else 350f) && (
                t.contains("每日賓果") ||
                t.contains("今日賓果") ||
                t == "賓果"
            )) || (cy in (if (sh < 10000) (sh * 0.40f).toInt()..(sh * 0.90f).toInt() else 400..900) && (
                t.contains("BINGO") ||
                t.contains("OPEN") ||
                t.contains("今日上限") ||
                t.contains("已達到今日上限") ||
                t.contains("COMPLETE")
            ))
        }
    }

    /**
     * 尋找「立即開啟賓果格」橘色按鈕 (y ≈ 0.635f)
     */
    fun findOpenCellButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. OCR 搜尋「立即開啟」/「開啟賓果格」/「開啟賓果」
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.58f).toInt()..(sh * 0.70f).toInt() &&
            (t.contains("立即開啟") || t.contains("開啟賓果格") || t.contains("開啟賓果") || t.contains("立即開"))
        }
        if (match != null) {
            val cy = match.bounds.centerY()
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                return match.bounds
            }
        }

        // 2. 2D 亮橘色長條按鈕區塊聚類偵測 (#FF8C00 ~ #FFA500: r > 190 && g in 100..195 && b < 70)
        // 搜尋 y in 0.58f..0.70f, x in 0.18f..0.82f
        val orangePts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.58f).toInt()
        val yEnd = (sh * 0.70f).toInt()
        val xStart = (sw * 0.18f).toInt()
        val xEnd = (sw * 0.82f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 190 && g in 100..195 && b < 70) {
                    orangePts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (orangePts.size >= 80) {
            val minX = orangePts.minOf { it.x }
            val maxX = orangePts.maxOf { it.x }
            val minY = orangePts.minOf { it.y }
            val maxY = orangePts.maxOf { it.y }
            val btnW = maxX - minX
            val btnH = maxY - minY
            if (btnW > sw * 0.35f && btnH in 15..(sh * 0.10f).toInt()) {
                return Rect(minX, minY, maxX, maxY)
            }
        }

        return null
    }

    /**
     * 尋找連線獎勵按鈕 (例如 1 BINGO 下方的紅底白字 [▶ Get] 或 [領取])：
     * 支援 4 大連線直欄 (1 BINGO, 3 BINGO, 5 BINGO, 8 BINGO) 之紅色膠囊按鈕精準偵測！
     */
    fun findConnectionRewardButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 優先：依序檢查 4 大 BINGO 連線欄位 (1 BINGO: 18%, 3 BINGO: 39%, 5 BINGO: 61%, 8 BINGO: 82%) 之紅色膠囊按鈕
        val colCenters = listOf(0.18f, 0.39f, 0.61f, 0.82f)
        for (cx in colCenters) {
            val x1 = (sw * (cx - 0.09f)).toInt().coerceIn(0, sw - 1)
            val x2 = (sw * (cx + 0.09f)).toInt().coerceIn(x1 + 1, sw)
            val y1 = (sh * 0.81f).toInt().coerceIn(0, sh - 1)
            val y2 = (sh * 0.88f).toInt().coerceIn(y1 + 1, sh)

            val redPoints = mutableListOf<android.graphics.Point>()
            var y = y1
            while (y < y2) {
                var x = x1
                while (x < x2) {
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    // 紅色/珊瑚紅判定：R 顯著高於 G 與 B
                    if (r > 155 && r > g + 35 && r > b + 35) {
                        redPoints.add(android.graphics.Point(x, y))
                    }
                    x += 2
                }
                y += 2
            }

            if (redPoints.size >= 15) {
                val minX = redPoints.minOf { it.x }
                val maxX = redPoints.maxOf { it.x }
                val minY = redPoints.minOf { it.y }
                val maxY = redPoints.maxOf { it.y }
                if (maxX - minX >= (sw * 0.10f).toInt() && maxY - minY in 12..(sh * 0.08f).toInt()) {
                    return Rect(minX, minY, maxX, maxY)
                }
            }
        }

        // 2. OCR 尋找「Get / get / GET / ▶Get / 領取」
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.80f).toInt()..(sh * 0.88f).toInt() && (
                item.text.contains("Get", ignoreCase = true) ||
                item.text.contains("Cet", ignoreCase = true) ||
                item.text.contains("Qet", ignoreCase = true) ||
                item.text.contains("領取") ||
                item.text.contains("▶")
            ) && !item.text.contains("今日上限") && !item.text.contains("開啟")
        }
        if (match != null) {
            return match.bounds
        }

        return null
    }

    /**
     * 賓果彈窗右上角關閉 (X) 按鈕熱區
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
     * 主畫面上的「賓果」圖示按鈕熱區 (第 1 顆，y ≈ 0.42f)
     */
    fun getMainScreenBingoIconRect(sw: Int, sh: Int, ocrItems: List<OcrItem>): Rect {
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.38f).toInt()..(sh * 0.46f).toInt() &&
            item.bounds.centerX() < (sw * 0.25f).toInt() &&
            item.text.contains("賓果")
        }
        return match?.bounds ?: Rect(
            (sw * 0.05f).toInt(),
            (sh * 0.38f).toInt(),
            (sw * 0.20f).toInt(),
            (sh * 0.46f).toInt()
        )
    }
}
