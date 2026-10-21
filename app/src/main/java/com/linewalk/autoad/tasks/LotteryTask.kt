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
        // 嚴格排除主畫面四大分頁特徵
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh > 0) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else false
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return false

        val maxTopY = if (sh > 0) sh * 0.16f else 350f
        return ocrItems.any { item ->
            (item.bounds.centerY() < maxTopY && item.text.contains("抽抽樂")) ||
            item.text.contains("BIG") ||
            (item.text.contains("50張") && item.text.contains("抽")) ||
            item.text.contains("中獎囉") ||
            item.text.contains("中獎") ||
            item.text.contains("Thank you for watching")
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

        // 0. 檢查 50 抽 (左側區域) 是否處於冷卻倒數中（包含『回復還需』、『還需』、『回復』、時間格式『\d{1,2}:\d{2}』或『剩0次』）
        val isFiftyInCooldown = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cx < (sw * 0.55f).toInt() && cy in (sh * 0.38f).toInt()..(sh * 0.58f).toInt() && (
                item.text.contains("回復還需") ||
                item.text.contains("還需") ||
                item.text.contains("回復") ||
                item.text.contains("剩0次") ||
                item.text.replace(" ", "") == "0次" ||
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(item.text)
            )
        }

        // 1. 【第一優先】：50 抽 (僅在未冷卻且票券/次數充足時搜尋「50張 抽1次」紅色按鈕)
        if (!isFiftyInCooldown) {
            val fiftyDrawOcr = ocrItems.firstOrNull { item ->
                val cy = item.bounds.centerY()
                val cx = item.bounds.centerX()
                val t = item.text.replace(" ", "")
                cy in (sh * 0.43f).toInt()..(sh * 0.56f).toInt() &&
                cx < (sw * 0.55f).toInt() &&
                (t.contains("50張") || (t.contains("50") && t.contains("抽"))) &&
                !t.contains("5張") && !t.contains("回復") && !t.contains("還需")
            }
            if (fiftyDrawOcr != null) {
                val cy = fiftyDrawOcr.bounds.centerY()
                if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                    return Pair("50張抽一次", fiftyDrawOcr.bounds)
                }
            }

            // 備援：2D 紅色按鈕聚類偵測 (限定按鈕實際高度 y in 0.44f..0.54f，絕對排除 y < 0.44f 的 BIG 紅色抽獎箱！)
            val leftRedBtn = findFiftyDrawByColor(screenBitmap)
            if (leftRedBtn != null && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, leftRedBtn.centerY())) {
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

        // 備援：2D 黃色按鈕聚類偵測 (x: 0.52f..0.98f, y: 0.49f..0.58f，黃底白字『▶ 免費抽1次』)
        val freeYellowBtn = findFreeDrawByColor(screenBitmap)
        if (freeYellowBtn != null && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, freeYellowBtn.centerY())) {
            return Pair("免費看廣告抽一次(黃色按鈕)", freeYellowBtn)
        }

        // 3. 【嚴格防呆】：若任何候選含有 "5張"，絕對回傳 null！
        return null
    }

    private fun findFiftyDrawByColor(screenBitmap: Bitmap): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val redPts = mutableListOf<android.graphics.Point>()
        // 嚴格限定在 50 抽按鈕本體高度 (y in 0.44f..0.54f)，避開 y < 0.44f 的 BIG 抽抽樂紅色抽獎本體箱！
        val yStart = (sh * 0.44f).toInt()
        val yEnd = (sh * 0.54f).toInt()
        val xStart = (sw * 0.05f).toInt()
        val xEnd = (sw * 0.50f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 50 抽按鈕為紅色膠囊按鈕
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
            if (maxX - minX >= (sw * 0.30f).toInt() && maxY - minY in 12..(sh * 0.08f).toInt()) {
                return Rect(minX, minY, maxX, maxY)
            }
        }
        return null
    }

    private fun findFreeDrawByColor(screenBitmap: Bitmap): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val yellowPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.49f).toInt()
        val yEnd = (sh * 0.58f).toInt()
        val xStart = (sw * 0.52f).toInt()
        val xEnd = (sw * 0.98f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 黃色膠囊按鈕: r > 190 && g in 110..195 && b < 50
                if (r > 190 && g in 110..195 && b < 50) {
                    yellowPts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (yellowPts.size >= 60) {
            val minX = yellowPts.minOf { it.x }
            val maxX = yellowPts.maxOf { it.x }
            val minY = yellowPts.minOf { it.y }
            val maxY = yellowPts.maxOf { it.y }
            if (maxX - minX >= (sw * 0.28f).toInt() && maxY - minY in 12..(sh * 0.08f).toInt()) {
                return Rect(minX, minY, maxX, maxY)
            }
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
     * 偵測抽抽樂抽獎後的結果彈窗綠色看廣告獲票按鈕：[▶ [抽獎] x1 獲得]
     * - 背景為 LINE 鮮綠色 (#06C856 / g > 130 && g > r + 30 && g > b + 30)
     * - 位於畫面中下部 (y ≈ 0.60f ~ 0.74f)
     * - 嚴格安全機制：必須在具有抽抽樂/中獎上下文（中獎囉、中獎、枚金幣、分享給好友、抽抽樂等）時才生效！
     * - 絕對排除日常領獎標題（獲得票券！、獲得金幣！等）！
     */
    fun findLotteryRewardAdButton(screenBitmap: Bitmap, ocrItems: List<OcrItem>): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 0. 嚴格安全前置檢查：必須處於抽抽樂中獎結果畫面！
        val hasLotteryResultContext = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("中獎囉") || t.contains("中獎") || t.contains("Thankyouforwatching") ||
            (t.contains("枚金幣") && t.contains("中獎")) ||
            (t.contains("金幣") && t.contains("中獎")) ||
            (item.bounds.centerY() < sh * 0.22f && t.contains("抽抽樂")) ||
            (item.bounds.centerY() > sh * 0.65f && (t.contains("分享給好友") || t.contains("分享"))) ||
            (item.bounds.centerY() in (sh * 0.60f).toInt()..(sh * 0.74f).toInt() &&
                (t.contains("x1", ignoreCase = true) || t.contains("1張")) &&
                !t.contains("金幣") && !t.contains("票券"))
        }
        if (!hasLotteryResultContext) {
            return null
        }

        // 1. 優先策略 A：連續實心綠色行分析 (Contiguous Solid Green Rows)
        // 實心膠囊按鈕在 y: 0.60f..0.74f, x: 0.10f..0.90f
        val yStart = (sh * 0.60f).toInt()
        val yEnd = (sh * 0.74f).toInt()
        val xStart = (sw * 0.10f).toInt()
        val xEnd = (sw * 0.90f).toInt()
        val stepY = 2
        val stepX = 2
        val roiSampledWidth = (xEnd - xStart) / stepX
        val minSolidRowGreen = (roiSampledWidth * 0.35f).toInt()

        val solidRows = mutableListOf<Int>()
        for (y in yStart..yEnd step stepY) {
            var greenCount = 0
            for (x in xStart..xEnd step stepX) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 130 && (g - r > 30) && (g - b > 30)) {
                    greenCount++
                }
            }
            if (greenCount >= minSolidRowGreen) {
                solidRows.add(y)
            }
        }

        // 尋找連續綠色行段落（完全過濾掉分享按鈕 1-2 像素的邊框綠線）
        val runs = mutableListOf<MutableList<Int>>()
        var curRun = mutableListOf<Int>()
        for (y in solidRows) {
            if (curRun.isEmpty() || y - curRun.last() <= stepY * 2) {
                curRun.add(y)
            } else {
                runs.add(curRun)
                curRun = mutableListOf(y)
            }
        }
        if (curRun.isNotEmpty()) {
            runs.add(curRun)
        }

        for (run in runs) {
            val yMin = run.first()
            val yMax = run.last()
            val runHeight = yMax - yMin
            // 實心膠囊按鈕高度應佔螢幕 2.5% ~ 12% 且至少連續取樣 4 行
            if (run.size >= 4 && runHeight in (sh * 0.025f).toInt()..(sh * 0.12f).toInt()) {
                var xMin = Int.MAX_VALUE
                var xMax = Int.MIN_VALUE
                for (y in run) {
                    for (x in xStart..xEnd step stepX) {
                        val c = screenBitmap.getPixel(x, y)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        if (g > 130 && (g - r > 30) && (g - b > 30)) {
                            if (x < xMin) xMin = x
                            if (x > xMax) xMax = x
                        }
                    }
                }
                if (xMax > xMin && (xMax - xMin) >= (sw * 0.35f).toInt()) {
                    return Rect(xMin, yMin, xMax, yMax)
                }
            }
        }

        // 2. 備援策略 B：OCR 比對 (y: 0.60f..0.74f，文字包含 "x1"、"1張" 或 "獲得")
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in yStart..yEnd &&
            !t.endsWith("！") && !t.endsWith("!") &&
            !t.contains("票券") && !t.contains("金幣") &&
            !t.contains("分享") && !t.contains("關閉") &&
            (t.contains("x1", ignoreCase = true) || t.contains("1張") || t.contains("獲得"))
        }
        if (ocrMatch != null) {
            val left = (sw * 0.15f).toInt()
            val right = (sw * 0.85f).toInt()
            val top = ocrMatch.bounds.top.coerceAtLeast(yStart)
            val bottom = ocrMatch.bounds.bottom.coerceAtMost(yEnd)
            return Rect(left, top, right, bottom)
        }

        // 3. 備援策略 C：幾何中心綠色像素探針 (Center Green Probe)
        val cx = sw / 2
        for (y in (sh * 0.64f).toInt()..(sh * 0.70f).toInt() step 4) {
            val c = screenBitmap.getPixel(cx, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (g > 130 && (g - r > 30) && (g - b > 30)) {
                var lx = cx
                while (lx > (sw * 0.08f).toInt()) {
                    val p = screenBitmap.getPixel(lx, y)
                    val pr = (p shr 16) and 0xFF
                    val pg = (p shr 8) and 0xFF
                    val pb = p and 0xFF
                    if (!(pg > 130 && (pg - pr > 30) && (pg - pb > 30))) break
                    lx -= 3
                }
                var rx = cx
                while (rx < (sw * 0.92f).toInt()) {
                    val p = screenBitmap.getPixel(rx, y)
                    val pr = (p shr 16) and 0xFF
                    val pg = (p shr 8) and 0xFF
                    val pb = p and 0xFF
                    if (!(pg > 130 && (pg - pr > 30) && (pg - pb > 30))) break
                    rx += 3
                }
                if (rx - lx >= (sw * 0.35f).toInt()) {
                    val topY = (y - sh * 0.025f).toInt().coerceAtLeast(yStart)
                    val bottomY = (y + sh * 0.025f).toInt().coerceAtMost(yEnd)
                    return Rect(lx, topY, rx, bottomY)
                }
            }
        }

        return null
    }

    /**
     * 偵測抽抽樂抽獎結果彈窗底部的「關閉」按鈕 (y ≈ 0.75f ~ 0.88f)
     */
    fun findLotteryResultCloseButton(screenBitmap: Bitmap, ocrItems: List<OcrItem>): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            cy in (sh * 0.75f).toInt()..(sh * 0.88f).toInt() && (t == "關閉" || t.contains("關閉"))
        }
        if (ocrMatch != null) {
            return ocrMatch.bounds
        }
        // 若畫面確定有抽獎結果標題 (中獎囉！)，提供幾何預設關閉按鈕
        val hasResultTitle = ocrItems.any { it.text.contains("中獎") || it.text.contains("中獎囉") }
        if (hasResultTitle) {
            return Rect(
                (sw * 0.15f).toInt(),
                (sh * 0.78f).toInt(),
                (sw * 0.85f).toInt(),
                (sh * 0.85f).toInt()
            )
        }
        return null
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
