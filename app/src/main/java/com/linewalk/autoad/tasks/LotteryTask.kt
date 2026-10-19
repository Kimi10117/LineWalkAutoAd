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

    fun isLotteryDialogOpen(ocrItems: List<OcrItem>): Boolean {
        return ocrItems.any { item ->
            item.bounds.top < 300 && (
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

        // 1. 【第一優先】：搜尋「50張 抽1次」紅色按鈕 (x in 0.05f..0.52f, y in 0.40f..0.55f)
        val fiftyDrawOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.40f).toInt()..(sh * 0.56f).toInt() &&
            cx < (sw * 0.55f).toInt() &&
            item.text.contains("50張") &&
            !item.text.contains("5張") // 雙重防呆
        }
        if (fiftyDrawOcr != null) {
            val cy = fiftyDrawOcr.bounds.centerY()
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                return Pair("50張抽一次", fiftyDrawOcr.bounds)
            }
        }

        // 備援：若 OCR 沒抓到「50張」三字，但左側 (x: 0.10f..0.45f, y: 0.43f..0.53f) 有紅色按鈕且未反灰
        val leftRedBtn = findFiftyDrawByColor(screenBitmap)
        if (leftRedBtn != null && !templateMatcher.isPopupButtonDisabledGray(screenBitmap, leftRedBtn.centerY())) {
            // 檢查票券餘額是否足夠 (若畫面頂部 OCR 有票券且數字 >= 50)
            val hasEnoughTickets = checkHasEnoughTickets(ocrItems)
            if (hasEnoughTickets != false) {
                return Pair("50張抽一次(幾何顏色)", leftRedBtn)
            }
        }

        // 2. 【第二順位】：搜尋「免費抽1次」黃色按鈕 (x in 0.52f..0.98f, y in 0.48f..0.60f)
        val freeDrawOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.48f).toInt()..(sh * 0.60f).toInt() &&
            cx > (sw * 0.50f).toInt() &&
            (item.text.contains("免費抽") || item.text.contains("免費")) &&
            !item.text.contains("5張") // 絕不點 5 張！
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

    private fun checkHasEnoughTickets(ocrItems: List<OcrItem>): Boolean? {
        // 頂部票券餘額通常位於 y < 0.15f
        for (item in ocrItems) {
            if (item.bounds.top < 150) {
                val num = item.text.filter { it.isDigit() }.toIntOrNull()
                if (num != null && num in 1..99999) {
                    return num >= 50
                }
            }
        }
        return null // 無法判斷則交由按鈕顏色與狀態決定
    }

    private fun findFiftyDrawByColor(screenBitmap: Bitmap): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val cx = (sw * 0.28f).toInt()
        val cy = (sh * 0.48f).toInt()
        val c = screenBitmap.getPixel(cx, cy)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        // 50 抽按鈕為紅色或深珊瑚粉紅
        if (r > 200 && (r - g > 60) && (r - b > 60)) {
            return Rect(
                (sw * 0.12f).toInt(),
                (sh * 0.43f).toInt(),
                (sw * 0.46f).toInt(),
                (sh * 0.53f).toInt()
            )
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
