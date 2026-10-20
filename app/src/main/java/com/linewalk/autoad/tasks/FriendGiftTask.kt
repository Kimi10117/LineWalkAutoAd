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
        // 嚴格排除主畫面四大分頁特徵
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh > 0) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else false
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return false

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

        // 1. OCR 辨識「禮物」文字 (x > 0.75f, y in 0.38f..0.85f)
        val giftOcrItems = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.38f).toInt()..(sh * 0.85f).toInt() &&
            cx > (sw * 0.75f).toInt() &&
            item.text.contains("禮物")
        }.sortedBy { it.bounds.top }

        for (item in giftOcrItems) {
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            if (!isAlreadyGifted(cx, cy)) {
                return Rect(
                    (sw * 0.78f).toInt(),
                    cy - 45,
                    (sw * 0.94f).toInt(),
                    cy + 15
                )
            }
        }

        // 2. 2D 綠色方塊區塊偵測 (LINE 亮綠 #06C755: g > 140 && g > r + 30 && g > b + 30)
        // 搜尋右側按鈕區域 x in 0.78f..0.94f, y in 0.38f..0.85f
        val greenPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.38f).toInt()
        val yEnd = (sh * 0.85f).toInt()
        val xStart = (sw * 0.78f).toInt()
        val xEnd = (sw * 0.94f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 140 && g > r + 30 && g > b + 30) {
                    greenPts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (greenPts.isNotEmpty()) {
            val sortedByY = greenPts.sortedBy { it.y }
            var currentCluster = mutableListOf<android.graphics.Point>()
            var clusterStartY = sortedByY.first().y

            for (pt in sortedByY) {
                if (pt.y - clusterStartY <= (sh * 0.08f).toInt()) {
                    currentCluster.add(pt)
                } else {
                    if (currentCluster.size >= 15) {
                        val minX = currentCluster.minOf { it.x }
                        val maxX = currentCluster.maxOf { it.x }
                        val minY = currentCluster.minOf { it.y }
                        val maxY = currentCluster.maxOf { it.y }
                        val cx = (minX + maxX) / 2
                        val cy = (minY + maxY) / 2
                        if (!isAlreadyGifted(cx, cy)) {
                            return Rect(minX, minY, maxX, maxY)
                        }
                    }
                    currentCluster = mutableListOf(pt)
                    clusterStartY = pt.y
                }
            }
            if (currentCluster.size >= 15) {
                val minX = currentCluster.minOf { it.x }
                val maxX = currentCluster.maxOf { it.x }
                val minY = currentCluster.minOf { it.y }
                val maxY = currentCluster.maxOf { it.y }
                val cx = (minX + maxX) / 2
                val cy = (minY + maxY) / 2
                if (!isAlreadyGifted(cx, cy)) {
                    return Rect(minX, minY, maxX, maxY)
                }
            }
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

        // 1. 【第一優先】OCR 辨識「也送給自己」/「送給自己」/「送禮物也送自己」/「看廣告」/「▶」
        val selfGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.35f).toInt()..(sh * 0.75f).toInt() && (
                item.text.contains("也送給自己") ||
                item.text.contains("送給自己") ||
                item.text.contains("送禮物也送自己") ||
                item.text.contains("送自己") ||
                item.text.contains("看廣告") ||
                item.text.contains("廣告") ||
                item.text.contains("▶") ||
                (item.text.contains("送") && item.text.contains("自己"))
            )
        }
        if (selfGiftOcr != null) {
            return Pair("也送給自己(看廣告)", selfGiftOcr.bounds)
        }

        // 2. 綠色按鈕色彩聚類 (LINE 亮綠 #06C755: g > 140 && g > r + 30 && g > b + 30, y in 0.40..0.65, x in 0.15..0.85)
        // 彈窗中央的「也送給自己」為亮綠色長條按鈕！
        val greenPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.40f).toInt()
        val yEnd = (sh * 0.65f).toInt()
        val xStart = (sw * 0.15f).toInt()
        val xEnd = (sw * 0.85f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 140 && g > r + 30 && g > b + 30) {
                    greenPts.add(android.graphics.Point(x, y))
                }
            }
        }
        if (greenPts.size >= 35) {
            val minX = greenPts.minOf { it.x }
            val maxX = greenPts.maxOf { it.x }
            val minY = greenPts.minOf { it.y }
            val maxY = greenPts.maxOf { it.y }
            val btnW = maxX - minX
            if (btnW > sw * 0.30f) {
                return Pair("也送給自己(綠色按鈕)", Rect(minX, minY, maxX, maxY))
            }
        }

        // 3. 備援：一般「贈送 / 送禮物 / 送出」按鈕
        val normalGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.45f).toInt()..(sh * 0.75f).toInt() && (
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
     * 底部導覽列「好友」按鈕 (第 2 顆，優先比對 OCR「好友」，備援相對幾何座標)
     */
    fun getBottomFriendsTabRect(sw: Int, sh: Int, ocrItems: List<OcrItem> = emptyList()): Rect {
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            cy > 0.85f && cx in 0.20f..0.55f && item.text.contains("好友")
        }
        if (ocrMatch != null) {
            return ocrMatch.bounds
        }
        return Rect(
            (sw * 0.25f).toInt(),
            (sh * 0.91f).toInt(),
            (sw * 0.50f).toInt(),
            (sh * 0.985f).toInt()
        )
    }

    /**
     * 底部導覽列「集點」(首頁) 按鈕 (第 1 顆，優先比對 OCR「集點」，備援相對幾何座標)
     */
    fun getBottomMainTabRect(sw: Int, sh: Int, ocrItems: List<OcrItem> = emptyList()): Rect {
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            cy > 0.85f && cx < 0.30f && (item.text.contains("集點") || item.text.contains("首頁"))
        }
        if (ocrMatch != null) {
            return ocrMatch.bounds
        }
        return Rect(
            (sw * 0.02f).toInt(),
            (sh * 0.91f).toInt(),
            (sw * 0.26f).toInt(),
            (sh * 0.985f).toInt()
        )
    }
}
