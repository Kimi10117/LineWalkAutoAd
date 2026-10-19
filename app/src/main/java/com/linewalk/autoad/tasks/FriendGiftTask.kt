package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem
import kotlin.math.hypot

/**
 * 好友送禮任務 (FriendGiftTask)：
 * - 切換至底部「好友」分頁
 * - 在好友排行榜中尋找綠色「禮物」按鈕
 * - 點擊禮物後，【最優先選擇】「送禮物也送自己」(看廣告獲取雙方獎勵)
 * - 逐一贈送畫面上的好友，送完後向下滾動清單直到全部好友送完
 * - 完成後自動切回底部「集點」主畫面
 */
class FriendGiftTask {

    private val giftedPoints = mutableListOf<android.graphics.Point>()

    fun isEnabled(config: AppConfig): Boolean = config.enableFriendGiftTask

    fun resetSession() {
        giftedPoints.clear()
    }

    fun isFriendsPage(ocrItems: List<OcrItem>, sh: Int = 0): Boolean {
        val maxTopY = if (sh > 0) sh * 0.25f else 500f
        return ocrItems.any { item ->
            item.bounds.centerY() < maxTopY && (
                item.text.contains("好友排行榜") ||
                item.text.contains("排行榜") ||
                item.text.contains("好友清單")
            )
        }
    }

    /**
     * 尋找畫面中尚未贈送的綠色「禮物」按鈕 (x in 0.75f..0.95f, y in 0.34f..0.88f)
     */
    fun findNextGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. OCR 辨識「禮物」文字 (x > 0.75f, y in 0.34f..0.88f)
        val giftOcrItems = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.34f).toInt()..(sh * 0.88f).toInt() &&
            cx > (sw * 0.75f).toInt() &&
            item.text.contains("禮物")
        }.sortedBy { it.bounds.top }

        for (item in giftOcrItems) {
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            if (!isAlreadyGifted(cx, cy)) {
                // 擴大點擊範圍以涵蓋上方綠色圖示
                return Rect(
                    (sw * 0.78f).toInt(),
                    cy - 45,
                    (sw * 0.94f).toInt(),
                    cy + 15
                )
            }
        }

        // 2. 顏色辨識：搜尋綠色圓角方塊按鈕 (LINE 綠色: R < 60, G > 175, B < 110)
        val xTarget = (sw * 0.86f).toInt()
        var y = (sh * 0.35f).toInt()
        val yEnd = (sh * 0.88f).toInt()
        while (y <= yEnd) {
            val c = screenBitmap.getPixel(xTarget, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (g > 175 && (g - r > 90) && (g - b > 70)) {
                if (!isAlreadyGifted(xTarget, y)) {
                    return Rect(
                        xTarget - 30,
                        y - 25,
                        xTarget + 30,
                        y + 25
                    )
                }
            }
            y += 20
        }

        return null
    }

    /**
     * 檢查送禮彈窗：
     * - 使用者指定：【都以送禮物也送自己為主】！
     * - 優先搜尋包含「送禮物也送自己」、「送自己」、「看廣告」或帶有播放圖示的按鈕
     * - 次選回傳「送禮物 / 贈送」一般按鈕
     */
    fun findSelfGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Pair<String, Rect>? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 【第一優先】OCR 辨識「也送給自己」/「送給自己」/「送禮物也送自己」/「送自己」
        val selfGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.40f).toInt()..(sh * 0.65f).toInt() && (
                item.text.contains("也送給自己") ||
                item.text.contains("送給自己") ||
                item.text.contains("送禮物也送自己") ||
                item.text.contains("送自己") ||
                (item.text.contains("送") && item.text.contains("自己"))
            )
        }
        if (selfGiftOcr != null) {
            return Pair("也送給自己(看廣告)", selfGiftOcr.bounds)
        }

        // 2. 綠色按鈕色彩辨識 (LINE 綠色 R < 40, G > 175, B < 110, y in 0.48..0.56, x in 0.20..0.80)
        // 畫面上方的「也送給自己」為亮綠色長條按鈕！
        val yTarget = (sh * 0.52f).toInt()
        val c = screenBitmap.getPixel((sw * 0.50f).toInt(), yTarget)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        if (g > 160 && (g - r > 70) && (g - b > 50)) {
            val greenBtn = Rect(
                (sw * 0.18f).toInt(),
                (sh * 0.495f).toInt(),
                (sw * 0.82f).toInt(),
                (sh * 0.545f).toInt()
            )
            return Pair("也送給自己(綠色按鈕)", greenBtn)
        }

        // 3. 備援：一般「贈送 / 送禮物 / 送出」按鈕
        val normalGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.55f).toInt()..(sh * 0.70f).toInt() && (
                item.text == "贈送" ||
                item.text == "送出" ||
                item.text == "送禮物" ||
                item.text == "確認"
            )
        }
        if (normalGiftOcr != null) {
            return Pair("一般贈送", normalGiftOcr.bounds)
        }

        return null
    }

    fun markGifted(point: android.graphics.Point) {
        giftedPoints.add(point)
    }

    private fun isAlreadyGifted(x: Int, y: Int): Boolean {
        return giftedPoints.any { pt ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < 45.0
        }
    }

    /**
     * 底部導覽列「好友」按鈕 (第 2 顆)
     */
    fun getBottomFriendsTabRect(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.28f).toInt(),
            (sh * 0.93f).toInt(),
            (sw * 0.48f).toInt(),
            (sh * 0.985f).toInt()
        )
    }

    /**
     * 底部導覽列「集點」(首頁) 按鈕 (第 1 顆)
     */
    fun getBottomMainTabRect(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.03f).toInt(),
            (sh * 0.93f).toInt(),
            (sw * 0.23f).toInt(),
            (sh * 0.985f).toInt()
        )
    }
}
