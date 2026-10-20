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
     * 偵測抽抽樂抽獎後的結果彈窗綠色看廣告獲票按鈕：[▶ 🎫 x1 獲得]
     * - 背景為 LINE 鮮綠色 (#06C856 / g > 140 && g > r + 35 && g > b + 35)
     * - 位於畫面中下部 (y ≈ 0.60f ~ 0.76f)
     * - 優先像素聚類，備援 OCR 搜尋 (含 "獲得" 或 "x1")
     */
    fun findLotteryRewardAdButton(screenBitmap: Bitmap, ocrItems: List<OcrItem>): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. 優先：綠色膠囊按鈕像素聚類偵測 (y: 0.60f..0.76f, x: 0.12f..0.88f)
        val greenPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.60f).toInt()
        val yEnd = (sh * 0.76f).toInt()
        val xStart = (sw * 0.12f).toInt()
        val xEnd = (sw * 0.88f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 140 && g > r + 35 && g > b + 35) {
                    greenPts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (greenPts.size >= 50) {
            val minX = greenPts.minOf { it.x }
            val maxX = greenPts.maxOf { it.x }
            val minY = greenPts.minOf { it.y }
            val maxY = greenPts.maxOf { it.y }
            if (maxX - minX >= (sw * 0.35f).toInt() && maxY - minY in 15..(sh * 0.12f).toInt()) {
                return Rect(minX, minY, maxX, maxY)
            }
        }

        // 2. 備援：OCR 比對 (y: 0.60f..0.76f，文字含 "獲得" 或 "x1"，排除 "分享" 或 "關閉")
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in yStart..yEnd &&
            (t.contains("獲得") || t.contains("x1") || t.contains("1張") || t.contains("再獲得")) &&
            !t.contains("分享") && !t.contains("關閉")
        }
        if (ocrMatch != null) {
            return ocrMatch.bounds
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
