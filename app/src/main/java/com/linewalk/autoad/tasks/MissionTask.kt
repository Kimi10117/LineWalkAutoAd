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
     * 1. 嚴格排除主畫面頂部四大分頁（首頁/步數/時間/遊戲）
     * 2. 螢幕上方出現「特別」、「每日」或「每週」任務專屬分頁文字
     * 3. 頂部標題出現「任務」且具備選中膠囊或任務項目
     */
    fun isMissionDialogOpen(
        ocrItems: List<OcrItem>,
        sh: Int = 0,
        screenBitmap: Bitmap? = null
    ): Boolean {
        // 嚴格排除主畫面四大分頁特徵
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh > 0) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else cy in 150..450
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return false

        val maxTopY = if (sh > 0) sh * 0.35f else 750f
        val hasMissionTabs = ocrItems.any { item ->
            item.bounds.centerY() < maxTopY && (
                item.text.contains("每日") || item.text.contains("特別") || item.text.contains("每週")
            )
        }
        val hasMissionTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inTopHeader = if (sh > 0) cy < sh * 0.16f else cy < 350
            inTopHeader && item.text.contains("任務")
        }

        return (hasMissionTitle && !hasMainTabs) || (hasMissionTabs && !hasMainTabs)
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
     * LINE 散步趣任務彈窗標準為三繁體分頁「特別」(左側 x ≈ 0.20)、「每日」(中央 x ≈ 0.50) 與「每週」(右側 x ≈ 0.80)
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
                avgXRatio in 0.35f..0.65f -> "每日"
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

        // 若在主畫面，絕不尋找任務領取按鈕
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh > 0) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else cy in 150..450
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return null

        // 建立排除 Y 軸清單 (凡是 OCR 出現「挑戰」或「已領取」的區域，嚴格排除點擊)
        val excludedYRanges = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.22f).toInt()..(sh * 0.90f).toInt() &&
            cx > (sw * 0.45f).toInt() &&
            (item.text.contains("挑戰") || item.text.contains("已領取") || item.text.contains("已領") ||
             item.text.contains("挑") || item.text.contains("戰"))
        }.map { (it.bounds.top - 25)..(it.bounds.bottom + 25) }

        // 1. OCR 優先辨識「領取」或「Get」(排除已領取、排除挑戰)
        val claimOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.22f).toInt()..(sh * 0.90f).toInt() &&
            cx > (sw * 0.55f).toInt() &&
            (t.contains("領取") || t.contains("領獎") || t.equals("Get", ignoreCase = true)) &&
            !t.contains("已領取") &&
            !t.contains("已領") &&
            !t.contains("挑戰") &&
            !t.contains("挑") && !t.contains("戰") &&
            excludedYRanges.none { range -> cy in range }
        }
        if (claimOcr != null) {
            return claimOcr.bounds
        }

        // 2. 2D 綠色連通像素區塊偵測 (LINE 亮綠 #06C755: g > 130 && g > r + 30 && g > b + 30)
        // 僅在確認具備任務分頁 (特別/每日/每週) 時才啟用綠色色塊備援
        val hasMissionTabs = ocrItems.any { item ->
            item.bounds.centerY() < sh * 0.35f && (
                item.text.contains("每日") || item.text.contains("每週") || item.text.contains("特別")
            )
        }
        if (!hasMissionTabs) return null

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
                if (g > 130 && (g - r > 30) && (g - b > 30)) {
                    greenPts.add(Point(x, y))
                }
            }
        }

        if (greenPts.size < 25) return null

        // 聚合成候選按鈕群組
        val sortedByY = greenPts.sortedBy { it.y }.toMutableList()
        while (sortedByY.isNotEmpty()) {
            val firstY = sortedByY.first().y
            val cluster = sortedByY.filter { it.y - firstY <= (sh * 0.08f).toInt() }
            sortedByY.removeAll(cluster)

            if (cluster.size >= 25) {
                val minX = cluster.minOf { it.x }
                val maxX = cluster.maxOf { it.x }
                val minY = cluster.minOf { it.y }
                val maxY = cluster.maxOf { it.y }
                val btnW = maxX - minX
                val btnH = maxY - minY

                if (btnW in (sw * 0.12f).toInt()..(sw * 0.35f).toInt() && btnH >= 15) {
                    // 嚴格驗證實心綠色按鈕：
                    // 1. 綠色採樣填滿率 >= 45% (實心按鈕通常 60%~80%，空心「挑戰」僅 ~20%)
                    val totalSampledInBox = ((btnW / 3) + 1) * ((btnH / 3) + 1)
                    val inBoxCount = cluster.count { it.x in minX..maxX && it.y in minY..maxY }
                    val fillRatio = inBoxCount.toFloat() / totalSampledInBox.coerceAtLeast(1)

                    // 2. 檢查左右兩端背景色 (實心按鈕兩端皆為綠色，空心「挑戰」兩端為純白背景)
                    val midY = (minY + maxY) / 2
                    val leftPixel = screenBitmap.getPixel((minX + btnW * 0.20f).toInt(), midY)
                    val rightPixel = screenBitmap.getPixel((maxX - btnW * 0.20f).toInt(), midY)
                    val isLeftGreen = isGreenPixel(leftPixel)
                    val isRightGreen = isGreenPixel(rightPixel)

                    if (fillRatio >= 0.45f && isLeftGreen && isRightGreen) {
                        return Rect(minX, minY, maxX, maxY)
                    }
                }
            }
        }

        return null
    }

    private fun isGreenPixel(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return g > 130 && (g - r > 30) && (g - b > 30)
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
            else -> 0.50f // "每日"：三繁體分頁佈局下中心點約 0.50f
        }
        val yCenter = (sh * 0.188f).toInt()
        val halfW = (sw * 0.14f).toInt()
        val halfH = (sh * 0.025f).toInt()
        return Rect(
            (sw * xRatio).toInt() - halfW,
            yCenter - halfH,
            (sw * xRatio).toInt() + halfW,
            yCenter + halfH
        )
    }

    /**
     * 任務彈窗右上角關閉 (X) 按鈕熱區 (x: 0.86~0.96, y: 0.095~0.145)
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
