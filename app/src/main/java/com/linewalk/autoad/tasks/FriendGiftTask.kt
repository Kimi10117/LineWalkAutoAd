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
        // 0. 若畫面上出現送禮確認彈窗（如『要向...贈送金幣嗎？』『也送給自己』『剩餘贈送次數』），必定屬於好友頁面！
        val hasSendGiftModal = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("也送給自己") || t.contains("贈送金幣") || t.contains("剩餘贈送次數") ||
            (t.contains("要向") && t.contains("金幣"))
        }
        if (hasSendGiftModal) return true

        // 嚴格排除主畫面四大分頁特徵
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh > 0) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else false
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return false

        // 1. 好友頁面專屬特徵（排行榜/邀請/好友清單/階層）
        val maxTopY = if (sh > 0) sh * 0.32f else 600f
        return ocrItems.any { item ->
            item.bounds.centerY() < maxTopY && (
                item.text.contains("好友排行榜") ||
                item.text.contains("排行榜") ||
                item.text.contains("邀請好友") ||
                item.text.contains("好友清單") ||
                item.text.contains("查看階層") ||
                item.text.contains("階層") ||
                item.text.contains("青銅") ||
                item.text.contains("白銀") ||
                item.text.contains("黃金")
            )
        }
    }

    /**
     * 尋找畫面中尚未贈送的綠色「禮物」按鈕 (x in 0.76f..0.95f, y in 0.38f..0.85f)
     * 採用精準的相鄰點間距聚類 (Gap Clustering)，完美區分每個好友列的獨立按鈕，並精確點擊在按鈕正中心！
     */
    fun findNextGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 優先：2D 綠色方塊區塊偵測 (LINE 亮綠 #06C755: g > 130 && g > r + 25 && g > b + 25)
        // 搜尋右側按鈕區域 x in 0.76f..0.95f, y in 0.38f..0.85f
        val greenPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.38f).toInt()
        val yEnd = (sh * 0.85f).toInt()
        val xStart = (sw * 0.76f).toInt()
        val xEnd = (sw * 0.95f).toInt()

        for (y in yStart..yEnd step 2) {
            for (x in xStart..xEnd step 2) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 130 && (g - r) > 25 && (g - b) > 25) {
                    greenPts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (greenPts.isNotEmpty()) {
            val sortedByY = greenPts.sortedBy { it.y }
            val clusters = mutableListOf<Rect>()
            var curCluster = mutableListOf<android.graphics.Point>()
            val maxGap = (sh * 0.015f).toInt().coerceAtLeast(8)

            for (pt in sortedByY) {
                if (curCluster.isEmpty() || (pt.y - curCluster.last().y) <= maxGap) {
                    curCluster.add(pt)
                } else {
                    if (curCluster.size >= 15) {
                        val minX = curCluster.minOf { it.x }
                        val maxX = curCluster.maxOf { it.x }
                        val minY = curCluster.minOf { it.y }
                        val maxY = curCluster.maxOf { it.y }
                        val w = maxX - minX
                        val h = maxY - minY
                        if (w in (sw * 0.06f).toInt()..(sw * 0.20f).toInt() && h in 15..(sh * 0.12f).toInt()) {
                            clusters.add(Rect(minX, minY, maxX, maxY))
                        }
                    }
                    curCluster = mutableListOf(pt)
                }
            }
            if (curCluster.size >= 15) {
                val minX = curCluster.minOf { it.x }
                val maxX = curCluster.maxOf { it.x }
                val minY = curCluster.minOf { it.y }
                val maxY = curCluster.maxOf { it.y }
                val w = maxX - minX
                val h = maxY - minY
                if (w in (sw * 0.06f).toInt()..(sw * 0.20f).toInt() && h in 15..(sh * 0.12f).toInt()) {
                    clusters.add(Rect(minX, minY, maxX, maxY))
                }
            }

            for (rect in clusters) {
                if (!isAlreadyGifted(rect.centerX(), rect.centerY(), sh)) {
                    return rect
                }
            }
        }

        // 2. OCR 備援：辨識「禮物」文字 (x > 0.75f, y in 0.38f..0.85f)
        val giftOcrItems = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.38f).toInt()..(sh * 0.85f).toInt() &&
            cx > (sw * 0.75f).toInt() &&
            item.text.contains("禮物") &&
            !item.text.contains("次數")
        }.sortedBy { it.bounds.top }

        for (item in giftOcrItems) {
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            val btnCenterY = cy - (sh * 0.035f).toInt()
            if (!isAlreadyGifted(cx, btnCenterY, sh)) {
                return Rect(
                    (sw * 0.78f).toInt(),
                    btnCenterY - (sh * 0.030f).toInt(),
                    (sw * 0.94f).toInt(),
                    btnCenterY + (sh * 0.030f).toInt()
                )
            }
        }

        return null
    }

    /**
     * 檢查送禮確認彈窗：
     * - 使用者指定：【都以送禮物也送自己為主】！
     * - 優先搜尋包含「也送給自己」、「送給自己」、「也送自己」之綠色膠囊按鈕 (帶有廣告播放鍵)
     * - 次選回傳「贈送 / 送禮物」一般按鈕
     */
    fun findSelfGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Pair<String, Rect>? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 【第一優先】OCR 辨識「也送給自己」/「送給自己」/「也送自己」
        val selfGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.35f).toInt()..(sh * 0.75f).toInt() && (
                t.contains("也送給自己") ||
                t.contains("送給自己") ||
                t.contains("也送自己") ||
                t.contains("送禮物也送自己") ||
                (t.contains("送") && t.contains("自己"))
            )
        }
        if (selfGiftOcr != null) {
            val cy = selfGiftOcr.bounds.centerY()
            val halfH = (sh * 0.035f).toInt()
            val btnRect = Rect(
                (sw * 0.15f).toInt(),
                cy - halfH,
                (sw * 0.85f).toInt(),
                cy + halfH
            )
            return Pair("也送給自己(OCR)", btnRect)
        }

        // 2. 綠色按鈕色彩聚類 (LINE 亮綠 #06C755: g > 130 && g > r + 25 && g > b + 25, y in 0.45..0.60, x in 0.15..0.85)
        // 彈窗中央的「也送給自己」為亮綠色膠囊按鈕 (寬度約 66% 螢幕寬度)
        val greenPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.45f).toInt()
        val yEnd = (sh * 0.60f).toInt()
        val xStart = (sw * 0.15f).toInt()
        val xEnd = (sw * 0.85f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 130 && (g - r) > 25 && (g - b) > 25) {
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
            val btnH = maxY - minY
            if (btnW > sw * 0.40f && btnH in 15..(sh * 0.08f).toInt()) {
                return Pair("也送給自己(綠色按鈕)", Rect(minX, minY, maxX, maxY))
            }
        }

        // 3. 備援：一般「贈送 / 送出 / 送禮物」按鈕
        val normalGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            cy in (sh * 0.55f).toInt()..(sh * 0.75f).toInt() && (
                t == "贈送" || t == "送出" || t == "送禮物" || t == "確認"
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

    private fun isAlreadyGifted(x: Int, y: Int, sh: Int): Boolean {
        val radius = (sh * 0.045f).toDouble()
        return giftedPoints.any { pt ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < radius
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
