package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.ScreenViewportCalibrator
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

        // 嚴格排除好友頁面與送禮相關彈窗（「贈送完畢」、「已贈送」、「排行榜」、「好友清單」、「也送給自己」、「送自己」等絕不可誤判為抽抽樂！）
        // 注意：絕對不可單憑「好友」關鍵字判斷，因為螢幕底部導覽列 (y > 0.88f) 永遠包含「好友」分頁標籤！
        val hasFriendsFeature = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val notInBottomNav = if (sh > 0) cy < sh * 0.88f else true
            val t = item.text.replace(" ", "")
            notInBottomNav && (
                t.contains("贈送完畢") || t.contains("已贈送") || t.contains("排行榜") ||
                t.contains("好友清單") || t.contains("也送給自己") || t.contains("送自己") ||
                t.contains("贈送金幣")
            )
        }
        if (hasFriendsFeature) return false

        val maxTopY = if (sh > 0) sh * 0.22f else 450f
        return ocrItems.any { item ->
            (item.bounds.centerY() < maxTopY && item.text.contains("抽抽樂")) ||
            item.text.contains("BIG") ||
            (item.text.contains("50張") && item.text.contains("抽")) ||
            item.text.contains("中獎囉") ||
            (item.text.contains("中獎") && (item.text.contains("金幣") || item.text.contains("枚") || item.text.contains("囉") || item.text.contains("獲得"))) ||
            (item.text.contains("Thank you for watching") && ocrItems.any { o ->
                val ot = o.text.replace(" ", "")
                ot.contains("中獎") || ot.contains("抽獎") || ot.contains("x1獲得") || ot.contains("1張") || ot.contains("枚金幣")
            })
        }
    }

    /**
     * 提取抽抽樂彈窗頂部或畫面頂部資產列的票券數量：
     * 1. 彈窗頂部票券膠囊：cy in 0.16f..0.25f, cx > 0.60f，包含數字 (\d+)
     * 2. 備援畫面頂部資產列：cy in 0.03f..0.10f, cx in 0.48f..0.80f
     */
    fun extractTicketCount(
        ocrItems: List<OcrItem>,
        screenBitmap: Bitmap? = null,
        sh: Int = screenBitmap?.height ?: 0,
        sw: Int = screenBitmap?.width ?: 0
    ): Int? {
        val effectiveSh = if (sh > 0) sh else (screenBitmap?.height ?: 0)
        val effectiveSw = if (sw > 0) sw else (screenBitmap?.width ?: 0)

        // 1. 檢查抽抽樂彈窗頂部票券膠囊 (cy in 0.16f..0.25f, cx > 0.60f)
        val modalPillItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val inY = if (effectiveSh > 0) {
                cy in (effectiveSh * 0.15f).toInt()..(effectiveSh * 0.26f).toInt()
            } else {
                cy in 120..650 || (ocrItems.isNotEmpty() && cy.toFloat() / maxOf(1, ocrItems.maxOf { it.bounds.bottom }) in 0.14f..0.27f)
            }
            val inX = if (effectiveSw > 0) {
                cx > (effectiveSw * 0.58f).toInt()
            } else {
                cx > 350 || (ocrItems.isNotEmpty() && cx.toFloat() / maxOf(1, ocrItems.maxOf { it.bounds.right }) > 0.55f)
            }
            val t = item.text.replace(" ", "").replace(",", "")
            inY && inX && Regex("""\d+""").containsMatchIn(t)
        }
        if (modalPillItem != null) {
            val cleanText = modalPillItem.text.replace(",", "")
            val num = Regex("""\d+""").find(cleanText)?.value?.toIntOrNull()
            if (num != null) return num
        }

        // 2. 備援：檢查畫面頂部資產列票券區域 (cy in 0.03f..0.10f, cx in 0.48f..0.80f)
        val topBarItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val inY = if (effectiveSh > 0) {
                cy in (effectiveSh * 0.03f).toInt()..(effectiveSh * 0.10f).toInt()
            } else {
                cy in 30..180 || (ocrItems.isNotEmpty() && cy.toFloat() / maxOf(1, ocrItems.maxOf { it.bounds.bottom }) in 0.03f..0.11f)
            }
            val inX = if (effectiveSw > 0) {
                cx in (effectiveSw * 0.48f).toInt()..(effectiveSw * 0.80f).toInt()
            } else {
                cx in 400..900 || (ocrItems.isNotEmpty() && cx.toFloat() / maxOf(1, ocrItems.maxOf { it.bounds.right }) in 0.45f..0.85f)
            }
            val t = item.text.replace(" ", "").replace(",", "")
            inY && inX && Regex("""\d+""").containsMatchIn(t)
        }
        if (topBarItem != null) {
            val cleanText = topBarItem.text.replace(",", "")
            val num = Regex("""\d+""").find(cleanText)?.value?.toIntOrNull()
            if (num != null) return num
        }

        // 3. 通用備援：若任何項目位於頂部 (cy < 0.25f 或 cy < 500) 且包含票券特徵與數字
        val ticketKeywordItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val inTop = if (effectiveSh > 0) cy < effectiveSh * 0.25f else cy < 500
            val t = item.text.replace(" ", "").replace(",", "")
            inTop && (t.contains("張") || t.contains("票") || t.contains("ticket", ignoreCase = true)) && Regex("""\d+""").containsMatchIn(t)
        }
        if (ticketKeywordItem != null) {
            val cleanText = ticketKeywordItem.text.replace(",", "")
            val num = Regex("""\d+""").find(cleanText)?.value?.toIntOrNull()
            if (num != null) return num
        }

        return null
    }

    fun extractTicketCount(ocrItems: List<OcrItem>, sh: Int, sw: Int = 0): Int? {
        return extractTicketCount(ocrItems, null, sh, sw)
    }

    /**
     * 尋找符合規則的抽抽樂按鈕：
     * - 【第一優先】：50 抽按鈕 (含 "50張")，若票券不足 (< 50) 或冷卻中則跳過
     * - 【第二順位】：免費抽按鈕 (含 "免費抽" 或 "免費")，抽完即進入倒計時冷卻
     * - 【第三順位】：5 抽按鈕 (含 "5張 抽1次"，免看廣告直接消耗 5 張彩券開獎)，若票券不足 (< 5) 或反灰則跳過
     */
    fun findTargetDrawButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Pair<String, Rect>? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 檢查票券數量：若票券數明確 < 50，嚴禁點擊 50 抽！
        val ticketCount = extractTicketCount(ocrItems, screenBitmap, sh, sw)
        val isTicketInsufficient = ticketCount != null && ticketCount < 50

        // 0A. 檢查「免費抽」(右側偏下區域 x > 0.48f, y in 0.495f..0.62f) 是否處於倒計時冷卻中
        val isFreeInCooldown = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cx > (sw * 0.48f).toInt() && cy in (sh * 0.495f).toInt()..(sh * 0.62f).toInt() && (
                item.text.contains("回復還需") ||
                item.text.contains("還需") ||
                item.text.contains("倒計時") ||
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(item.text)
            )
        }

        // 0B. 檢查 50 抽 (左側區域) 是否處於冷卻倒數中（包含『回復還需』、『還需』、『回復』、時間格式『\d{1,2}:\d{2}』或『剩0次』）
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

        // 1. 【第一順位】：50 抽 (BIG 50張 抽1次，僅在未冷卻且票券充足時搜尋「50張 抽1次」紅色按鈕)
        if (!isFiftyInCooldown && !isTicketInsufficient) {
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
                val cx = fiftyDrawOcr.bounds.centerX()
                if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy, cx)) {
                    return Pair("50張抽一次", fiftyDrawOcr.bounds)
                }
            }

            // 備援：2D 紅色按鈕聚類偵測 (限定按鈕實際高度 y in 0.44f..0.54f，絕對排除 y < 0.44f 的 BIG 紅色抽獎箱！)
            val leftRedBtn = findFiftyDrawByColor(screenBitmap)
            if (leftRedBtn != null && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, leftRedBtn.centerY(), leftRedBtn.centerX())) {
                return Pair("50張抽一次(紅色按鈕)", leftRedBtn)
            }
        }

        // 2. 【第二順位】：免費看廣告抽一次 (免費抽1次，x in 0.50f..0.98f, y in 0.495f..0.60f)
        val freeTarget = findFreeDrawOnly(screenBitmap, ocrItems, templateMatcher)
        if (freeTarget != null) {
            return freeTarget
        }

        // 3. 【第三順位】：5 張抽 1 次 (x in 0.50f..0.96f, y in 0.41f..0.525f，橘色膠囊按鈕)
        val fiveTarget = findFiveDrawOnly(screenBitmap, ocrItems, templateMatcher)
        if (fiveTarget != null) {
            return fiveTarget
        }

        return null
    }

    /**
     * 自動轉向決策：
     * - 若票券數充足 (>= 50 或未識別) 且 50 抽按鈕可點 -> 回傳 50 抽按鈕
     * - 若票券不足 (< 50) 或 50 抽反灰/冷卻 -> 自動轉向「免費抽1次」
     * - 若免費抽亦冷卻 -> 自動轉向「5張 抽1次」
     * - 若無可抽項目 (三者皆不可點) -> 回傳關閉視窗 (X) 按鈕熱區，乾淨關閉彈窗
     */
    fun resolveNextAction(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Pair<String, Rect> {
        val target = findTargetDrawButton(screenBitmap, ocrItems, templateMatcher)
        if (target != null) {
            return target
        }
        return Pair("關閉", getCloseButtonBounds(screenBitmap.width, screenBitmap.height))
    }

    /**
     * 尋找第二順位「免費看廣告抽一次」按鈕 (x in 0.50f..0.98f, y in 0.495f..0.60f)
     * 當 50 抽已達每輪上限或票券不足時，可直接調用此方法切換至免費抽，杜絕死循環！
     */
    fun findFreeDrawOnly(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Pair<String, Rect>? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        val isFreeInCooldown = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cx > (sw * 0.48f).toInt() && cy in (sh * 0.495f).toInt()..(sh * 0.62f).toInt() && (
                item.text.contains("回復還需") ||
                item.text.contains("還需") ||
                item.text.contains("倒計時") ||
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(item.text)
            )
        }
        if (isFreeInCooldown) return null

        val freeDrawOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.49f).toInt()..(sh * 0.62f).toInt() &&
            cx > (sw * 0.48f).toInt() &&
            (t.contains("免費抽") || t.contains("免費")) &&
            !t.contains("5張") && !t.contains("回復") && !t.contains("還需")
        }
        if (freeDrawOcr != null) {
            val cy = freeDrawOcr.bounds.centerY()
            val cx = freeDrawOcr.bounds.centerX()
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy, cx)) {
                return Pair("免費看廣告抽一次", freeDrawOcr.bounds)
            }
        }

        // 備援：2D 黃色按鈕聚類偵測 (x: 0.52f..0.98f, y: 0.50f..0.58f，黃底白字『▶ 免費抽1次』)
        val freeYellowBtn = findFreeDrawByColor(screenBitmap)
        if (freeYellowBtn != null && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, freeYellowBtn.centerY(), freeYellowBtn.centerX())) {
            return Pair("免費看廣告抽一次(黃色按鈕)", freeYellowBtn)
        }
        return null
    }

    /**
     * 尋找第三順位「5張 抽1次」按鈕 (x in 0.50f..0.96f, y in 0.40f..0.525f)
     * 當 50 抽與免費抽皆冷卻或不可點，且彩券數量 >= 5 時，自動點擊「5張 抽1次」！
     */
    fun findFiveDrawOnly(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Pair<String, Rect>? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        val ticketCount = extractTicketCount(ocrItems, screenBitmap, sh, sw)
        if (ticketCount != null && ticketCount < 5) {
            return null
        }

        val isFiveInCooldown = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cx > (sw * 0.48f).toInt() && cy in (sh * 0.40f).toInt()..(sh * 0.495f).toInt() && (
                item.text.contains("回復還需") ||
                item.text.contains("還需") ||
                item.text.contains("剩0次") ||
                item.text.replace(" ", "") == "0次" ||
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(item.text)
            )
        }
        if (isFiveInCooldown) return null

        val fiveDrawOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.40f).toInt()..(sh * 0.525f).toInt() &&
            cx > (sw * 0.48f).toInt() &&
            t.contains("5張") && !t.contains("50張") && !t.contains("15張") && !t.contains("25張") &&
            !t.contains("回復") && !t.contains("還需")
        }
        if (fiveDrawOcr != null) {
            val cy = fiveDrawOcr.bounds.centerY()
            val cx = fiveDrawOcr.bounds.centerX()
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy, cx)) {
                return Pair("5張抽一次", fiveDrawOcr.bounds)
            }
        }

        // 備援：2D 橘色按鈕聚類偵測 (x: 0.52f..0.96f, y: 0.42f..0.495f，橘底白字『5張 抽1次』)
        val fiveOrangeBtn = findFiveDrawByColor(screenBitmap)
        if (fiveOrangeBtn != null && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, fiveOrangeBtn.centerY(), fiveOrangeBtn.centerX())) {
            return Pair("5張抽一次(橘色按鈕)", fiveOrangeBtn)
        }
        return null
    }

    private fun findFiveDrawByColor(screenBitmap: Bitmap): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val orangePts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.42f).toInt()
        val yEnd = (sh * 0.495f).toInt()
        val xStart = (sw * 0.52f).toInt()
        val xEnd = (sw * 0.96f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 橘色膠囊按鈕: r > 210 && g in 65..145 && b < 55
                if (r > 210 && g in 65..145 && b < 55 && (r - g) > 75) {
                    orangePts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (orangePts.size >= 60) {
            val minX = orangePts.minOf { it.x }
            val maxX = orangePts.maxOf { it.x }
            val minY = orangePts.minOf { it.y }
            val maxY = orangePts.maxOf { it.y }
            if (maxX - minX >= (sw * 0.28f).toInt() && maxY - minY in 12..(sh * 0.10f).toInt()) {
                return Rect(minX, minY, maxX, maxY)
            }
        }
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
            if (maxX - minX >= (sw * 0.30f).toInt() && maxY - minY in 12..(sh * 0.12f).toInt()) {
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
            if (maxX - minX >= (sw * 0.28f).toInt() && maxY - minY in 12..(sh * 0.12f).toInt()) {
                return Rect(minX, minY, maxX, maxY)
            }
        }
        return null
    }

    /**
     * 抽抽樂彈窗右上角關閉 (X) 按鈕熱區（支援像素級白底面板頂部邊界與 (X) 筆畫中心尋跡，排除 BIG 抽抽樂內文標題干擾）
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
            exactHeaderTitles = listOf("抽抽樂"),
            defaultYRatio22to9 = 0.121f,
            attempt = attempt
        )
    }

    /**
     * 偵測抽抽樂抽獎後的結果彈窗綠色看廣告獲票按鈕：[▶ [抽獎] x1 獲得]
     * - 背景為 LINE 鮮綠色 (#06C856 / g > 130 && g > r + 30 && g > b + 30)
     * - 位於畫面中下部 (y ≈ 0.60f ~ 0.74f)
     * - 嚴格安全機制：必須在具有抽抽樂/中獎上下文（中獎囉、中獎、枚金幣、分享給好友、抽抽樂等）時才生效！
     * - 絕對排除日常領獎標題（獲得票券！、獲得金幣！等）！
     */
    /**
     * 判定抽抽樂中獎結果畫面背後是否疊加了全螢幕廣告（散步趣已知重疊 Bug）：
     * - 當中獎彈窗背後露出廣告特徵（例如上方透出「還沒有邀請好友 實在太可惜了！」或頂部存在廣告關閉 X 等）
     * - 遇到此 Bug 時，只能點擊中獎彈窗底部的 [關閉] 按鈕，關閉中獎彈窗後露出廣告本體，再由廣告關閉流程點擊廣告右上角 (X) 退出！
     */
    fun hasBackgroundAdBehindLotteryResult(ocrItems: List<OcrItem>, sh: Int = 0): Boolean {
        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY()
            val inTopOrBottomAdArea = if (sh > 0) {
                cy < (sh * 0.22f).toInt() || cy > (sh * 0.88f).toInt()
            } else {
                cy < 500 || cy > 2100
            }
            inTopOrBottomAdArea && (
                t.contains("還沒有邀請好友") ||
                t.contains("實在太可惜了") ||
                (t.contains("邀請好友") && !t.contains("分享")) ||
                t.contains("最高可獲") ||
                t.contains("Sponsored", ignoreCase = true) ||
                t.contains("開啟商店") ||
                t.contains("立即遊玩") ||
                t.contains("安裝")
            )
        }
    }

    /**
     * 嚴格判定目前是否處於「抽抽樂中獎結果彈窗」（BIG 8獎 / 100 枚金幣中獎囉！ / [▶ x1 獲得] / 分享給好友 / 關閉）：
     * - 支援懸浮膠囊遮擋標題左半部或底層露出「BIG 獎項一覽」時的複合特徵辨識（頂部 1~10獎/特獎 + 中下部 [x1 獲得] / [分享給好友] / [關閉] 組合）
     * - 即使中獎彈窗中間配圖帶有「完成任務 即可獲得 大量金幣!!」宣傳字樣，只要具備中獎特徵與底部按鈕，仍能正確辨識！
     */
    fun isLotteryWinResultScreen(
        ocrItems: List<OcrItem>,
        sh: Int,
        screenBitmap: Bitmap? = null
    ): Boolean {
        // 0. 嚴格否定排除：若目前處於日常任務、每日賓果或好友列表子視窗，絕對不是抽抽樂中獎結果！
        val isMissionDialog = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inTopHeader = if (sh > 0) cy < sh * 0.25f else cy < 600
            val t = item.text.replace(" ", "")
            inTopHeader && t.contains("任務")
        } || ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inTabs = if (sh > 0) cy in (sh * 0.10f).toInt()..(sh * 0.32f).toInt() else cy in 200..800
            val t = item.text.replace(" ", "")
            inTabs && (t.contains("特別") || t.contains("每日") || t.contains("每週"))
        }
        if (isMissionDialog) {
            return false
        }

        val isBingoOrFriendDialog = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("每日賓果") || t.contains("連線達成") ||
            t.contains("好友清單") || t.contains("也送給自己") || t.contains("贈送金幣") ||
            (t.contains("排行榜") && !t.contains("BIG"))
        }
        if (isBingoOrFriendDialog) {
            return false
        }

        val hasRankHeader = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val inTopModalArea = if (sh > 0) {
                item.bounds.centerY() in (sh * 0.10f).toInt()..(sh * 0.40f).toInt()
            } else {
                item.bounds.centerY() in 150..1000
            }
            inTopModalArea && (
                Regex("""([1-9]|10|特|頭)獎""").containsMatchIn(t) ||
                t.contains("BIG") ||
                t.contains("枚金幣中獎") ||
                t.contains("中獎囉") ||
                t.contains("恭喜中獎")
            )
        }

        val hasRewardX1ButtonOcr = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val inMidLower = if (sh > 0) {
                item.bounds.centerY() in (sh * 0.54f).toInt()..(sh * 0.78f).toInt()
            } else {
                item.bounds.centerY() in 1100..1900
            }
            inMidLower &&
                (t.contains("x1", ignoreCase = true) || t.contains("×1") || t.contains("1張")) &&
                !t.contains("5張") && !t.contains("50張") && !t.contains("抽1次") &&
                !t.contains("挑戰") && !t.contains("已領取")
        }

        val hasModalCloseButtonOcr = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val inBottomModal = if (sh > 0) {
                item.bounds.centerY() in (sh * 0.70f).toInt()..(sh * 0.88f).toInt()
            } else {
                item.bounds.centerY() in 1500..2200
            }
            inBottomModal && (t == "關閉" || t.contains("關閉")) &&
                !t.contains("挑戰") && !t.contains("已領取")
        }

        val hasExplicitWinTitle = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("中獎囉") ||
            t.contains("恭喜中獎") ||
            t.contains("恭喜獲得") ||
            (t.contains("分享給好友") && !t.contains("發送")) ||
            (t.contains("中獎") && (t.contains("金幣") || t.contains("枚") || t.contains("囉") || t.contains("！") || t.contains("!")))
        } || (hasRankHeader && (hasRewardX1ButtonOcr || hasModalCloseButtonOcr)) ||
            (hasRewardX1ButtonOcr && hasModalCloseButtonOcr)

        if (hasExplicitWinTitle) {
            return true
        }

        // 若有傳入 screenBitmap，檢查是否具備『頂部獎項/底部關閉 + 中下部實心鮮綠膠囊按鈕』像素特徵
        if (screenBitmap != null && (hasRankHeader || hasModalCloseButtonOcr)) {
            if (hasSolidGreenCapsuleInResultZone(screenBitmap)) {
                return true
            }
        }

        // 檢查抽抽樂主面板中央區域特徵 (y < 0.85f，忽略 y > 0.86f 在結果彈窗下方邊緣露出的「BIG 獎項一覽」)
        // 注意：中獎彈窗中間配圖亦可能印有「完成任務 即可獲得 大量金幣!!」，不可單憑此字樣否決！
        val hasMainLotteryPanelFeatures = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val inMainPanelBody = if (sh > 0) item.bounds.centerY() < (sh * 0.85f).toInt() else true
            inMainPanelBody && (
                t.contains("查看獎項") ||
                t.contains("獎項一覽") ||
                t.contains("回復還需") ||
                (t.contains("5張") && !t.contains("15張") && !t.contains("25張")) ||
                t.contains("50張") ||
                t.contains("免費抽")
            )
        }
        if (hasMainLotteryPanelFeatures) {
            return false
        }

        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            if (t.contains("最多") || t.contains("查看獎項") || t.contains("獎項一覽") ||
                t.contains("回復") || t.contains("還需") || (t.contains("5張") && !t.contains("15張") && !t.contains("25張")) ||
                t.contains("50張") || t.contains("抽1次")
            ) {
                return@any false
            }
            t.contains("中獎囉") || (t.contains("中獎") && !t.contains("好友") && !t.contains("發送") && !t.contains("邀請")) ||
            t.contains("恭喜獲得") || t.contains("恭喜中獎") ||
            (item.bounds.centerY() > sh * 0.65f && (t.contains("分享給好友") || (t.contains("分享") && !t.contains("發送") && !t.contains("邀請")))) ||
            (hasRankHeader && item.bounds.centerY() in (sh * 0.50f).toInt()..(sh * 0.78f).toInt() &&
                (t.contains("x1", ignoreCase = true) || t.contains("×1") || t.contains("1張")) &&
                !t.contains("金幣") && !t.contains("票券"))
        }
    }

    private fun hasSolidGreenCapsuleInResultZone(screenBitmap: Bitmap): Boolean {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        if (sw <= 0 || sh <= 0) return false
        val yStart = (sh * 0.58f).toInt()
        val yEnd = (sh * 0.74f).toInt()
        val xStart = (sw * 0.18f).toInt()
        val xEnd = (sw * 0.82f).toInt()
        val stepX = 4
        val stepY = 3
        val minGreenPerRow = ((xEnd - xStart) / stepX * 0.35f).toInt()
        var solidRowCount = 0
        for (y in yStart..yEnd step stepY) {
            var greenCount = 0
            for (x in xStart..xEnd step stepX) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 135 && (g - r > 30) && (g - b > 30)) {
                    greenCount++
                }
            }
            if (greenCount >= minGreenPerRow) {
                solidRowCount++
                if (solidRowCount >= 3) return true
            }
        }
        return false
    }

    /**
     * 檢查「50抽」、「免費抽」與「5抽」是否皆處於反灰、冷卻或票券不足不可點狀態：
     * 當三者皆不可點時，可直接關閉抽抽樂視窗繼續檢查下一項，絕不點擊下方任何廣告區域。
     */
    fun areBothDrawsUnavailable(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Boolean {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        // 嚴格防護 1：若前景正開啟「中獎囉！ / BIG 8獎」結果彈窗，絕對不可誤判為抽獎按鈕反灰完成！
        if (isLotteryWinResultScreen(ocrItems, sh, screenBitmap)) {
            return false
        }
        // 嚴格防護 2：若畫面中下部存在「關閉」或「分享給好友」等前景模態彈窗按鈕，代表主面板被遮擋，不可判定為反灰
        val hasForegroundModalButtons = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.62f).toInt()..(sh * 0.88f).toInt() &&
                (t == "關閉" || t.contains("關閉") || t.contains("分享給好友"))
        }
        if (hasForegroundModalButtons) {
            return false
        }
        if (findTargetDrawButton(screenBitmap, ocrItems, templateMatcher) != null) {
            return false
        }
        // 嚴格防護 3：確認畫面確實看得到抽抽樂主面板特徵（避免開獎動畫過渡幀遮擋按鈕時被誤判為反灰）
        val hasVisibleMainPanelFeature = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("50張") || t.contains("5張") || t.contains("免費") ||
            t.contains("回復") || t.contains("還需") || t.contains("查看獎項") ||
            t.contains("完成任務")
        }
        if (!hasVisibleMainPanelFeature) {
            return false
        }
        val ticketCount = extractTicketCount(ocrItems, screenBitmap, sh, sw)
        val isFiveUnavailable = (ticketCount != null && ticketCount < 5) ||
            templateMatcher.isPopupButtonDisabledGray(screenBitmap, (sh * 0.455f).toInt(), (sw * 0.75f).toInt())
        val leftGray = templateMatcher.isPopupButtonDisabledGray(screenBitmap, (sh * 0.48f).toInt(), (sw * 0.26f).toInt())
        val rightFreeGray = templateMatcher.isPopupButtonDisabledGray(screenBitmap, (sh * 0.53f).toInt(), (sw * 0.75f).toInt())
        val hasLeftCooldown = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cx < (sw * 0.55f).toInt() && cy in (sh * 0.40f).toInt()..(sh * 0.56f).toInt() &&
            (item.text.contains("回復還需") || item.text.contains("還需") || Regex("""\d{1,2}:\d{2}""").containsMatchIn(item.text))
        }
        val hasRightCooldown = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cx > (sw * 0.48f).toInt() && cy in (sh * 0.495f).toInt()..(sh * 0.62f).toInt() &&
            (item.text.contains("回復還需") || item.text.contains("還需") || Regex("""\d{1,2}:\d{2}""").containsMatchIn(item.text))
        }
        return (leftGray || hasLeftCooldown) && (rightFreeGray || hasRightCooldown) && isFiveUnavailable
    }

    fun findLotteryRewardAdButton(screenBitmap: Bitmap, ocrItems: List<OcrItem>): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 0. 嚴格安全前置檢查：必須處於抽抽樂中獎結果畫面！
        val hasFriendsFeature = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val notInBottomNav = if (sh > 0) cy < sh * 0.88f else true
            val t = item.text.replace(" ", "")
            notInBottomNav && (
                t.contains("贈送完畢") || t.contains("已贈送") || t.contains("排行榜") ||
                t.contains("好友清單") || t.contains("也送給自己") || t.contains("送自己") ||
                t.contains("贈送金幣")
            )
        }
        if (hasFriendsFeature) {
            return null
        }

        val hasLotteryResultContext = isLotteryWinResultScreen(ocrItems, sh, screenBitmap)
        if (!hasLotteryResultContext) {
            return null
        }

        // 1. 優先策略 A：連續實心綠色行分析 (Contiguous Solid Green Rows)
        // 實心膠囊按鈕在 y: 0.52f..0.78f, x: 0.10f..0.90f (全機型自適應長螢幕與普通螢幕)
        val yStart = (sh * 0.52f).toInt()
        val yEnd = (sh * 0.78f).toInt()
        val xStart = (sw * 0.10f).toInt()
        val xEnd = (sw * 0.90f).toInt()
        val stepY = 2
        val stepX = 2
        val roiSampledWidth = (xEnd - xStart) / stepX
        val minSolidRowGreen = (roiSampledWidth * 0.25f).toInt()

        val solidRows = mutableListOf<Int>()
        for (y in yStart..yEnd step stepY) {
            var greenCount = 0
            for (x in xStart..xEnd step stepX) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 130 && (g - r > 25) && (g - b > 25)) {
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
            // 實心膠囊按鈕高度應佔螢幕 2.0% ~ 12% 且至少連續取樣 3 行
            if (run.size >= 3 && runHeight in (sh * 0.020f).toInt()..(sh * 0.12f).toInt()) {
                var xMin = Int.MAX_VALUE
                var xMax = Int.MIN_VALUE
                for (y in run) {
                    for (x in xStart..xEnd step stepX) {
                        val c = screenBitmap.getPixel(x, y)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        if (g > 130 && (g - r > 25) && (g - b > 25)) {
                            if (x < xMin) xMin = x
                            if (x > xMax) xMax = x
                        }
                    }
                }
                if (xMax > xMin && (xMax - xMin) >= (sw * 0.30f).toInt()) {
                    return Rect(xMin, yMin, xMax, yMax)
                }
            }
        }

        // 2. 備援策略 B：OCR 比對 (y: 0.52f..0.78f，文字必須明確包含 "x1"、"×1" 或 "1張"，嚴禁單憑「獲得」誤點「完成任務 即可獲得」)
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in yStart..yEnd &&
            !t.endsWith("！") && !t.endsWith("!") &&
            !t.contains("票券") && !t.contains("金幣") &&
            !t.contains("完成任務") && !t.contains("即可獲得") && !t.contains("大量") &&
            !t.contains("5張") && !t.contains("50張") && !t.contains("抽1次") &&
            !t.contains("分享") && !t.contains("關閉") &&
            !t.contains("任務") && !t.contains("挑戰") && !t.contains("領取") &&
            (t.contains("x1", ignoreCase = true) || t.contains("×1") || t.contains("1張"))
        }
        if (ocrMatch != null) {
            val left = (sw * 0.15f).toInt()
            val right = (sw * 0.85f).toInt()
            val top = ocrMatch.bounds.top.coerceAtLeast(yStart)
            val bottom = ocrMatch.bounds.bottom.coerceAtMost(yEnd)
            return Rect(left, top, right, bottom)
        }

        // 3. 備援策略 C：幾何中心綠色像素探針 (Center Green Probe, y in 0.56f..0.74f)
        val cx = sw / 2
        for (y in (sh * 0.56f).toInt()..(sh * 0.74f).toInt() step 3) {
            val c = screenBitmap.getPixel(cx, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (g > 130 && (g - r > 25) && (g - b > 25)) {
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
        // 若畫面確定有抽獎結果標題 (中獎囉！) 或複合結果彈窗特徵 (BIG 8獎 / [x1 獲得])，提供幾何預設關閉按鈕
        val hasResultTitle = ocrItems.any { it.text.contains("中獎") || it.text.contains("中獎囉") } ||
            isLotteryWinResultScreen(ocrItems, sh, screenBitmap)
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
