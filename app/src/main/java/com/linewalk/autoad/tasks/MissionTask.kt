package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem

/**
 * 任務領取任務 (MissionTask)：
 * - 自動依序切換檢查「特別」、「每日」、「每週」三大分頁
 * - 依賴黑色膠囊背景動態辨識當前啟用中的分頁
 * - 尋找並點擊所有可領取的綠色按鈕「領取」或「Get」
 * - 嚴格排除尚未完成的「挑戰」按鈕與已領取項目
 * - 全部領取完畢後點右上角 (X) 關閉視窗
 */
class MissionTask {

    fun isEnabled(config: AppConfig): Boolean = config.enableDailyMissionTask

    /**
     * 判定是否正在「任務」彈窗中：
     * 支援多重特徵防禦（避免被狀態列、打孔相機或不同螢幕高度影響）：
     * 1. 螢幕上方 35% 範圍內出現「任務」標題文字
     * 2. 螢幕上方 35% 範圍內出現「特別」、「每日」或「每週」分頁文字
     * 3. 偵測到頂部分頁深黑選中膠囊 (y in 0.16f..0.22f)
     * 4. 畫面出現「已領取」或「挑戰」任務清單按鈕特徵
     */
    fun isMissionDialogOpen(
        ocrItems: List<OcrItem>,
        sh: Int = 0,
        screenBitmap: Bitmap? = null
    ): Boolean {
        val maxTopY = if (sh > 0) sh * 0.35f else 750f
        val hasMissionTitle = ocrItems.any { item ->
            item.bounds.centerY() < maxTopY && item.text.contains("任務")
        }
        val hasMissionTabs = ocrItems.any { item ->
            item.bounds.centerY() < maxTopY && (
                item.text.contains("每日") || item.text.contains("每週") || item.text.contains("特別")
            )
        }
        val hasMissionItems = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh > 0) cy in (sh * 0.20f).toInt()..(sh * 0.90f).toInt() else cy > 400
            inRange && (
                item.text.contains("已領取") || item.text.contains("挑戰") || item.text.contains("領取")
            )
        }
        val hasBlackPill = screenBitmap != null && hasTabPill(screenBitmap)

        return (hasMissionTitle && (hasMissionTabs || hasBlackPill || hasMissionItems)) ||
               (hasMissionTabs && (hasBlackPill || hasMissionItems)) ||
               (hasBlackPill && hasMissionItems)
    }

    /**
     * 偵測分頁列 (y in 0.16f..0.22f) 是否存在當前選中之深黑色圓角膠囊按鈕
     */
    fun hasTabPill(screenBitmap: Bitmap): Boolean {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        var blackCount = 0
        val yStart = (sh * 0.16f).toInt()
        val yEnd = (sh * 0.22f).toInt()
        for (y in yStart..yEnd step 3) {
            for (x in 0 until sw step 4) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < 35 && g < 35 && b < 35) {
                    blackCount++
                    if (blackCount >= 20) return true
                }
            }
        }
        return false
    }

    /**
     * 偵測當前畫面選中的分頁名稱：
     * 透過深黑色膠囊按鈕 (特別: x ≈ 0.20, 每日: x ≈ 0.50, 每週: x ≈ 0.80) 之平均 X 座標判定
     */
    fun detectCurrentActiveTab(screenBitmap: Bitmap): String {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val blackXs = mutableListOf<Int>()
        val yStart = (sh * 0.16f).toInt()
        val yEnd = (sh * 0.22f).toInt()
        for (y in yStart..yEnd step 3) {
            for (x in 0 until sw step 4) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < 35 && g < 35 && b < 35) {
                    blackXs.add(x)
                }
            }
        }
        if (blackXs.size >= 15) {
            val avgXRatio = blackXs.average().toFloat() / sw
            return when {
                avgXRatio < 0.35f -> "特別"
                avgXRatio < 0.65f -> "每日"
                else -> "每週"
            }
        }
        return "特別"
    }

    /**
     * 尋找清單中可點擊的綠色「領取」按鈕 (嚴格排除「挑戰」與「已領取」)
     */
    fun findClaimableMissionButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 建立排除 Y 軸清單 (凡是 OCR 出現「挑戰」或「已領取」的區域，嚴格排除點擊)
        val excludedYRanges = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.22f).toInt()..(sh * 0.90f).toInt() &&
            cx > (sw * 0.60f).toInt() &&
            (item.text.contains("挑戰") || item.text.contains("已領取") || item.text.contains("已領"))
        }.map { (it.bounds.top - 20)..(it.bounds.bottom + 20) }

        // 1. OCR 優先辨識「領取」或「Get」(排除已領取、排除挑戰)
        val claimOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.22f).toInt()..(sh * 0.90f).toInt() &&
            cx > (sw * 0.60f).toInt() &&
            (item.text.contains("領取") || item.text.contains("領獎") || item.text.equals("Get", ignoreCase = true)) &&
            !item.text.contains("已領取") &&
            !item.text.contains("已領") &&
            !item.text.contains("挑戰") &&
            excludedYRanges.none { range -> cy in range }
        }
        if (claimOcr != null) {
            return claimOcr.bounds
        }

        // 2. 2D 綠色連通像素區塊偵測 (LINE 亮綠 #06C755: g > 140 && g > r + 35 && g > b + 35)
        // 搜尋右側按鈕區域 x in 0.65f..0.96f, y in 0.24f..0.88f
        val greenPts = mutableListOf<Point>()
        val yStart = (sh * 0.24f).toInt()
        val yEnd = (sh * 0.88f).toInt()
        val xStart = (sw * 0.65f).toInt()
        val xEnd = (sw * 0.96f).toInt()

        for (y in yStart..yEnd step 3) {
            if (excludedYRanges.any { range -> y in range }) continue
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 140 && g > r + 35 && g > b + 35) {
                    greenPts.add(Point(x, y))
                }
            }
        }

        // 若找到足夠多連通綠色像素 (實心膠囊按鈕通常 > 35 個採樣點)，聚合成按鈕矩形
        if (greenPts.size >= 35) {
            val sortedByY = greenPts.sortedBy { it.y }
            val firstCluster = mutableListOf<Point>()
            val firstY = sortedByY.first().y
            for (pt in sortedByY) {
                if (pt.y - firstY <= (sh * 0.08f).toInt()) {
                    firstCluster.add(pt)
                }
            }

            if (firstCluster.size >= 25) {
                val minX = firstCluster.minOf { it.x }
                val maxX = firstCluster.maxOf { it.x }
                val minY = firstCluster.minOf { it.y }
                val maxY = firstCluster.maxOf { it.y }
                val btnW = maxX - minX
                val btnH = maxY - minY
                if (btnW in (sw * 0.12f).toInt()..(sw * 0.35f).toInt() && btnH >= 15) {
                    return Rect(minX, minY, maxX, maxY)
                }
            }
        }

        return null
    }

    /**
     * 取得任務彈窗頂部分頁標籤點擊區域：
     * - "特別": x ≈ 0.20f, y ≈ 0.188f
     * - "每日": x ≈ 0.50f, y ≈ 0.188f
     * - "每週": x ≈ 0.80f, y ≈ 0.188f
     * 優先比對 OCR 關鍵字，若 OCR 未抓取則使用精準相對座標熱區
     */
    fun getTabRect(tabName: String, sw: Int, sh: Int, ocrItems: List<OcrItem> = emptyList()): Rect {
        val ocrMatch = ocrItems.firstOrNull { item ->
            item.bounds.centerY() in (sh * 0.15f).toInt()..(sh * 0.24f).toInt() &&
            item.text.contains(tabName)
        }
        if (ocrMatch != null) {
            return ocrMatch.bounds
        }

        val xRatio = when (tabName) {
            "特別" -> 0.20f
            "每週" -> 0.80f
            else -> 0.50f // "每日"
        }
        val yCenter = (sh * 0.188f).toInt()
        val halfW = (sw * 0.12f).toInt()
        val halfH = (sh * 0.025f).toInt()
        return Rect(
            (sw * xRatio).toInt() - halfW,
            yCenter - halfH,
            (sw * xRatio).toInt() + halfW,
            yCenter + halfH
        )
    }

    /**
     * 任務彈窗右上角關閉 (X) 按鈕熱區 (x: 0.86~0.97, y: 0.09~0.15)
     */
    fun getCloseButtonBounds(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.86f).toInt(),
            (sh * 0.09f).toInt(),
            (sw * 0.97f).toInt(),
            (sh * 0.15f).toInt()
        )
    }

    /**
     * 主畫面上的「任務」圖示按鈕熱區 (第 4 顆，y ≈ 0.42f)
     */
    fun getMainScreenMissionIconRect(sw: Int, sh: Int, ocrItems: List<OcrItem>): Rect {
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.38f).toInt()..(sh * 0.46f).toInt() &&
            item.bounds.centerX() > (sw * 0.75f).toInt() &&
            item.text.contains("任務")
        }
        return match?.bounds ?: Rect(
            (sw * 0.78f).toInt(),
            (sh * 0.38f).toInt(),
            (sw * 0.96f).toInt(),
            (sh * 0.46f).toInt()
        )
    }
}
