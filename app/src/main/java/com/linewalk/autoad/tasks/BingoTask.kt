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
        // 0. 嚴格一票否決：排除好友分頁、送禮彈窗、抽抽樂與任務視窗！
        val hasExclusionFeature = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val notInBottomNav = if (sh < 10000) cy < sh * 0.88f else true
            val t = item.text.replace(" ", "")
            notInBottomNav && (
                t.contains("排行榜") || t.contains("好友清單") || t.contains("散步趣好友") ||
                t.contains("編輯好友") || t.contains("也送給自己") || t.contains("送給自己") ||
                t.contains("剩餘贈送次數") || t.contains("贈送金幣") || t.contains("贈送完畢") ||
                (t.contains("已贈送") && t.contains("金幣")) ||
                (t.contains("要向") && t.contains("金幣")) ||
                t.contains("50張") || t.contains("BIG抽抽樂") || t.contains("免費抽") ||
                t.contains("中獎囉")
            )
        }
        if (hasExclusionFeature) return false

        // 多維特徵比對：
        // 1. 標題特徵 (「每日賓果」、「今日賓果」、「BINGO」)
        // 2. 開格按鈕特徵 (「立即開啟賓果格」、「開啟賓果格」、「立即開啟」)
        // 3. 冷卻倒數特徵 (「距離下次抽選還有」、「距離下次」、「抽選還有」)
        // 4. 今日上限特徵 (「今日上限」、「已達到今日上限」、「9/9」、「已全數開啟」)
        // 5. 連線達成標籤特徵 (「1BINGO」、「3BINGO」、「5BINGO」、「8BINGO」等)
        var hasTitleFeature = false
        var hasOpenCellOrCooldownFeature = false
        var hasConnectionFeature = false

        for (item in ocrItems) {
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")

            // 標題特徵 (頂部 y < 0.28f)
            if (t.contains("每日賓果") || t.contains("今日賓果") ||
                (cy < (if (sh < 10000) sh * 0.28f else 600f) && (
                    t.equals("BINGO", ignoreCase = true) ||
                    (t.contains("每日") && t.contains("賓果")) ||
                    (cy > (if (sh < 10000) sh * 0.08f else 100f) && t == "賓果")
                ))
            ) {
                hasTitleFeature = true
            }

            // 開格 / 冷卻 / 上限特徵
            if (t.contains("立即開啟賓果格") || t.contains("開啟賓果格") ||
                (t.contains("開啟") && t.contains("賓果")) ||
                (t.contains("立即開啟") && cy > (if (sh < 10000) sh * 0.40f else 600f)) ||
                t.contains("距離下次抽選還有") || t.contains("距離下次") || t.contains("抽選還有") ||
                t.contains("下次抽選") ||
                (cy in (if (sh < 10000) (sh * 0.40f).toInt()..(sh * 0.85f).toInt() else 600..1800) && (
                    t.contains("今日上限") || t.contains("已達到今日上限") || t.contains("9/9") || t.contains("已全數開啟")
                ))
            ) {
                hasOpenCellOrCooldownFeature = true
            }

            // 連線門檻 / 獎勵標籤特徵
            if (t.contains("1BINGO") || t.contains("1 BINGO") ||
                t.contains("3BINGO") || t.contains("3 BINGO") ||
                t.contains("5BINGO") || t.contains("5 BINGO") ||
                t.contains("8BINGO") || t.contains("8 BINGO") ||
                (cy in (if (sh < 10000) (sh * 0.70f).toInt()..(sh * 0.95f).toInt() else 1200..2000) && hasTitleFeature && (
                    t.contains("Get", ignoreCase = true) || t.contains("Cet", ignoreCase = true) || t.contains("Qet", ignoreCase = true)
                ))
            ) {
                hasConnectionFeature = true
            }
        }

        // 當具備強賓果特徵（標題、開格/冷卻/上限、連線標籤任二，或強確定性標題/開格按鈕）時判定為開啟
        val hasStrongBingoSignature = (hasTitleFeature && (hasOpenCellOrCooldownFeature || hasConnectionFeature)) ||
            (hasOpenCellOrCooldownFeature && hasConnectionFeature) ||
            ocrItems.any { item ->
                val t = item.text.replace(" ", "")
                t.contains("每日賓果") || t.contains("今日賓果") ||
                t.contains("立即開啟賓果格") || t.contains("開啟賓果格") ||
                t.contains("距離下次抽選還有") ||
                t.contains("1BINGO") || t.contains("3BINGO") || t.contains("5BINGO") || t.contains("8BINGO")
            }

        if (hasStrongBingoSignature) {
            return true
        }

        // 若無強特徵，檢查主畫面分頁（若處於主畫面則一票否決）
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh < 10000) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else false
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return false

        // 弱特徵兜底比對
        return ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            (cy < (if (sh < 10000) sh * 0.28f else 450f) && (
                t.contains("每日賓果") || t.contains("今日賓果") || t == "賓果"
            )) || (cy in (if (sh < 10000) (sh * 0.35f).toInt()..(sh * 0.92f).toInt() else 400..1200) && (
                t.contains("BINGO") || t.contains("OPEN") || t.contains("今日上限") ||
                t.contains("已達到今日上限") || t.contains("COMPLETE")
            ))
        }
    }

    data class BingoRewardTarget(
        val columnIndex: Int,
        val label: String,
        val bounds: Rect
    )

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

        // 0A. 若已出現「今日上限」、「已達到今日上限」、「9/9」，代表今日無法再開啟賓果格
        val hasReachedLimit = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("今日上限") || t.contains("已達到今日上限") || t.contains("9/9") || t.contains("已全數開啟")
        }
        if (hasReachedLimit) return null

        val standardTimeRegex = Regex("""\d{1,2}:\d{2}""")
        val chineseTimeRegex = Regex("""\d+\s*小時|\d+\s*分鐘|\d+\s*分|\d+\s*秒""")

        // 1. OCR 搜尋「立即開啟」/「開啟賓果格」/「開啟賓果」
        // 注意：頂部綠色橫幅 (y < 0.35f) 的「距離下次抽選還有 XX 分鐘 XX 秒」為 30 分鐘新票券定時器，
        // 絕不可作為按鈕冷卻的一票否決條件！只要畫面上存在有效橘色按鈕且非禁用灰色，優先回傳開格！
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.54f).toInt()..(sh * 0.74f).toInt() &&
            (t.contains("立即開啟") || t.contains("開啟賓果格") || t.contains("開啟賓果") || t.contains("立即開")) &&
            !t.contains("還有") && !standardTimeRegex.containsMatchIn(t) && !chineseTimeRegex.containsMatchIn(t) &&
            !t.contains("今日上限") && !t.contains("9/9")
        }
        if (match != null) {
            val cy = match.bounds.centerY()
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                return match.bounds
            }
        }

        // 2. 2D 亮橘色長條按鈕區塊聚類偵測 (#FF8C00 ~ #FFA500: r > 190 && g in 100..195 && b < 70)
        // 搜尋 y in 0.56f..0.72f, x in 0.16f..0.84f
        val orangePts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.56f).toInt()
        val yEnd = (sh * 0.72f).toInt()
        val xStart = (sw * 0.16f).toInt()
        val xEnd = (sw * 0.84f).toInt()

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
            if (btnW > sw * 0.35f && btnH in 15..(sh * 0.12f).toInt()) {
                val cy = (minY + maxY) / 2
                // 檢查按鈕本體區域內是否有冷卻倒數文字覆蓋
                val hasCooldownOnButton = ocrItems.any { item ->
                    val icy = item.bounds.centerY()
                    val t = item.text.replace(" ", "")
                    icy in (minY - 10)..(maxY + 10) && (
                        chineseTimeRegex.containsMatchIn(t) ||
                        standardTimeRegex.containsMatchIn(t) ||
                        t.contains("還有") || t.contains("回復") || t.contains("還需")
                    )
                }
                if (!hasCooldownOnButton && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                    return Rect(minX, minY, maxX, maxY)
                }
            }
        }

        return null
    }

    /**
     * 尋找可領取的賓果連線獎勵按鈕 (1 BINGO: 18%, 3 BINGO: 39%, 5 BINGO: 61%, 8 BINGO: 82%)：
     * 排除已在當前輪次領取過的直欄 (claimedColumns)。
     * 偵測特徵：
     * 1. 該欄下半部 (y in 0.74f..0.90f) 出現紅色 [▶ Get] 膠囊按鈕
     * 2. 該欄下半部 OCR 出現「Get」/「GET」/「Cet」/「領取」/「▶」
     */
    fun findNextClaimableReward(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        claimedColumns: Set<Int> = emptySet()
    ): BingoRewardTarget? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width
        val colCenters = listOf(0.18f, 0.39f, 0.61f, 0.82f)

        for ((colIndex, cx) in colCenters.withIndex()) {
            if (colIndex in claimedColumns) continue

            val x1 = (sw * (cx - 0.10f)).toInt().coerceIn(0, sw - 1)
            val x2 = (sw * (cx + 0.10f)).toInt().coerceIn(x1 + 1, sw)
            val y1 = (sh * 0.74f).toInt().coerceIn(0, sh - 1)
            val y2 = (sh * 0.90f).toInt().coerceIn(y1 + 1, sh)

            // 1. 檢查該欄是否有紅色膠囊按鈕
            val redPoints = mutableListOf<android.graphics.Point>()
            var y = y1
            while (y < y2) {
                var x = x1
                while (x < x2) {
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r > 140 && r > g + 30 && r > b + 30) {
                        redPoints.add(android.graphics.Point(x, y))
                    }
                    x += 2
                }
                y += 2
            }

            if (redPoints.size >= 20) {
                val minX = redPoints.minOf { it.x }
                val maxX = redPoints.maxOf { it.x }
                val minY = redPoints.minOf { it.y }
                val maxY = redPoints.maxOf { it.y }
                if (maxX - minX >= (sw * 0.07f).toInt() && maxY - minY in 10..(sh * 0.08f).toInt()) {
                    return BingoRewardTarget(colIndex, "紅色獎勵鈕(欄${colIndex + 1})", Rect(minX, minY, maxX, maxY))
                }
            }

            // 2. 檢查該欄 OCR 是否包含 Get / 領取 / ▶
            val colOcr = ocrItems.firstOrNull { item ->
                val icx = item.bounds.centerX()
                val icy = item.bounds.centerY()
                icx in x1..x2 && icy in y1..y2 && (
                    item.text.contains("Get", ignoreCase = true) ||
                    item.text.contains("Cet", ignoreCase = true) ||
                    item.text.contains("Qet", ignoreCase = true) ||
                    item.text.contains("領取") ||
                    item.text.contains("▶")
                ) && !item.text.contains("今日上限") && !item.text.contains("開啟")
            }
            if (colOcr != null) {
                return BingoRewardTarget(colIndex, "OCR[${colOcr.text}](欄${colIndex + 1})", colOcr.bounds)
            }
        }

        return null
    }

    /**
     * 向下相容舊版介面
     */
    fun findConnectionRewardButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        return findNextClaimableReward(screenBitmap, ocrItems, emptySet())?.bounds
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
