package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem

/**
 * 接收好友禮物任務 (ReceiveGiftTask)：
 * - 點擊主畫面右上角禮物盒圖示 (x ≈ 0.86, y ≈ 0.065) 開啟「禮物」視窗
 * - 檢查禮物清單內是否有待接收禮物（右側帶有綠色播放鍵 [▶] 的按鈕）
 * - 逐一觀看廣告領取好友贈送的禮物
 * - 全部領取完畢後，點擊右上角 (X) 關閉視窗 (x ≈ 0.90, y ≈ 0.23)
 */
class ReceiveGiftTask {

    fun isEnabled(config: AppConfig): Boolean = config.enableFriendGiftTask

    /**
     * 主畫面右上角「禮物盒」圖示座標 (揚聲器與齒輪設定之間，常帶有紅點通知)
     */
    fun getHeaderGiftBoxRect(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.835f).toInt(),
            (sh * 0.045f).toInt(),
            (sw * 0.885f).toInt(),
            (sh * 0.080f).toInt()
        )
    }

    /**
     * 判斷「禮物」彈窗是否開啟中（嚴格排除超商「禮物卡/禮物券」等廣告卡片！）
     * 注意：因「禮物」彈窗為下半部抽屜式視窗，頂部的四大分頁特徵仍可能隱約可見，故不可因頂部分頁存在而判定為非禮物彈窗！
     */
    fun isReceiveGiftDialogOpen(ocrItems: List<OcrItem>, sh: Int): Boolean {
        // 嚴格排除好友排行榜、排行榜、好友清單等好友頁面特徵
        val isFriendsScreen = ocrItems.any { item ->
            val t = item.text
            t.contains("好友排行榜") || t.contains("排行榜") || t.contains("好友清單") || t.contains("青銅級") || t.contains("查看階層")
        }
        if (isFriendsScreen) return false

        return ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            t.contains("送來的禮物") ||
            (t.contains("收到") && t.contains("禮物")) ||
            (t.contains("今天") && t.contains("止") && cy < sh * 0.88f) ||
            (t.contains("剩餘") && t.contains("天") && cy < sh * 0.88f) ||
            ((t == "禮物" || t == "禮物盒") &&
             cy in (sh * 0.18f).toInt()..(sh * 0.32f).toInt() && !t.contains("次數") && !t.contains("卡") && !t.contains("券") && !t.contains("超商"))
        }
    }

    /**
     * 尋找禮物清單中下一個待領取的綠色播放按鈕 [▶] (x ≈ 0.60f..0.95f, y ≈ 0.25f..0.88f)
     * 採用 2D 獨立按鈕垂直分組聚類 (支援清單中有多個待收禮物，優先點選最上方一筆)，並提供多重 OCR 備援！
     */
    fun findNextClaimGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 【第一優先】：2D 顏色區域辨識搜尋右側綠色播放按鈕 (x ≈ 0.60f..0.95f, y ≈ 0.25f..0.88f)
        val x1 = (sw * 0.60f).toInt().coerceIn(0, sw - 1)
        val x2 = (sw * 0.95f).toInt().coerceIn(x1 + 1, sw)
        val y1 = (sh * 0.25f).toInt().coerceIn(0, sh - 1)
        val y2 = (sh * 0.88f).toInt().coerceIn(y1 + 1, sh)

        val greenPoints = mutableListOf<android.graphics.Point>()
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // LINE 亮綠色：G 顯著高於 R 與 B (膠囊外框與內部綠色播放三角形)
                if (g > 140 && g > r + 30 && g > b + 30) {
                    greenPoints.add(android.graphics.Point(x, y))
                }
                x += 2
            }
            y += 2
        }

        if (greenPoints.isNotEmpty()) {
            val sortedByY = greenPoints.sortedBy { it.y }
            var currentCluster = mutableListOf<android.graphics.Point>()
            var prevY = sortedByY.first().y

            for (pt in sortedByY) {
                // 相鄰綠色點若垂直間隔 <= 15px，視為同一個按鈕
                if (pt.y - prevY <= 15) {
                    currentCluster.add(pt)
                } else {
                    val btn = evaluateGiftButtonCluster(currentCluster, sw, sh)
                    if (btn != null) {
                        return btn
                    }
                    currentCluster = mutableListOf(pt)
                }
                prevY = pt.y
            }
            val btn = evaluateGiftButtonCluster(currentCluster, sw, sh)
            if (btn != null) {
                return btn
            }
        }

        // 2. 【OCR 備援 1】：尋找「收到...送來的禮物」文字行，按鈕就在同高度右側 (x ≈ 0.65f..0.92f)
        val giftTextItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text
            cy in (sh * 0.28f).toInt()..(sh * 0.85f).toInt() &&
            (t.contains("送來的禮物") || (t.contains("收到") && t.contains("禮物")))
        }
        if (giftTextItem != null) {
            val cy = giftTextItem.bounds.centerY()
            val btnHalfW = (sw * 0.13f).toInt()
            val btnHalfH = (sh * 0.022f).toInt()
            val btnCenterX = (sw * 0.785f).toInt()
            return Rect(btnCenterX - btnHalfW, cy - btnHalfH, btnCenterX + btnHalfW, cy + btnHalfH)
        }

        // 3. 【OCR 備援 2】：尋找「今天...止」或「剩餘...天」截止文字，綠色播放按鈕就在其正上方約 30~50px
        val deadlineItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.30f).toInt()..(sh * 0.88f).toInt() &&
            cx > sw * 0.55f &&
            ((item.text.contains("今天") && item.text.contains("止")) ||
             item.text.contains("剩餘") || item.text.contains("天"))
        }
        if (deadlineItem != null) {
            val cx = deadlineItem.bounds.centerX()
            val cy = deadlineItem.bounds.centerY()
            val btnHalfW = (sw * 0.13f).toInt()
            val btnHalfH = (sh * 0.022f).toInt()
            val btnCenterY = cy - (sh * 0.038f).toInt()
            return Rect(cx - btnHalfW, btnCenterY - btnHalfH, cx + btnHalfW, btnCenterY + btnHalfH)
        }

        // 4. 【OCR 備援 3】：直接搜尋帶有「▶」播放符號的項目
        val playItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.28f).toInt()..(sh * 0.85f).toInt() &&
            cx > sw * 0.60f &&
            (item.text.contains("▶") || item.text.contains(">"))
        }
        if (playItem != null) {
            return playItem.bounds
        }

        return null
    }

    private fun evaluateGiftButtonCluster(
        cluster: List<android.graphics.Point>,
        sw: Int,
        sh: Int
    ): Rect? {
        if (cluster.size < 30) return null
        val minX = cluster.minOf { it.x }
        val maxX = cluster.maxOf { it.x }
        val minY = cluster.minOf { it.y }
        val maxY = cluster.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        // 驗證按鈕寬度 (約螢幕寬度的 16% ~ 36%) 與高度 (約螢幕高度的 1.8% ~ 7.0%)
        if (width in (sw * 0.16f).toInt()..(sw * 0.36f).toInt() &&
            height in (sh * 0.018f).toInt()..(sh * 0.070f).toInt()
        ) {
            return Rect(minX, minY, maxX, maxY)
        }
        return null
    }

    /**
     * 禮物彈窗右上角關閉 (X) 按鈕熱區
     */
    fun getCloseButtonBounds(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.84f).toInt(),
            (sh * 0.18f).toInt(),
            (sw * 0.98f).toInt(),
            (sh * 0.26f).toInt()
        )
    }
}
