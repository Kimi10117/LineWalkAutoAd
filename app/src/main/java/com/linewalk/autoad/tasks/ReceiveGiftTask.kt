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
            (sw * 0.82f).toInt(),
            (sh * 0.045f).toInt(),
            (sw * 0.90f).toInt(),
            (sh * 0.085f).toInt()
        )
    }

    /**
     * 判斷「禮物」彈窗是否開啟中（嚴格排除超商「禮物卡/禮物券」等廣告卡片！）
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
            (t.contains("剩餘") && t.contains("天") && cy < sh * 0.85f) ||
            ((t == "禮物" || t == "禮物盒") &&
             cy < sh * 0.25f && !t.contains("次數") && !t.contains("卡") && !t.contains("券") && !t.contains("超商"))
        }
    }

    /**
     * 尋找禮物清單中下一個待領取的綠色播放按鈕 [▶] (x ≈ 0.62f..0.94f, y ≈ 0.28f..0.85f)
     * 支援 2D 綠色像素連通區塊 (綠色膠囊外框+中央綠色播放三角形) 與 OCR (「剩餘天數」正上方) 雙重定位！
     */
    fun findNextClaimGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 優先：2D 顏色區域辨識搜尋右側綠色播放按鈕 (x ≈ 0.62f..0.94f, y ≈ 0.28f..0.85f)
        val x1 = (sw * 0.62f).toInt().coerceIn(0, sw - 1)
        val x2 = (sw * 0.94f).toInt().coerceIn(x1 + 1, sw)
        val y1 = (sh * 0.28f).toInt().coerceIn(0, sh - 1)
        val y2 = (sh * 0.85f).toInt().coerceIn(y1 + 1, sh)

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

        if (greenPoints.size >= 20) {
            val minX = greenPoints.minOf { it.x }
            val maxX = greenPoints.maxOf { it.x }
            val minY = greenPoints.minOf { it.y }
            val maxY = greenPoints.maxOf { it.y }
            // 按鈕高度合理範圍 (15px ~ 0.15 * sh)
            if (maxY - minY in 15..(sh * 0.15f).toInt()) {
                return Rect(minX, minY, maxX, maxY)
            }
        }

        // 2. OCR 輔助定位：尋找「剩餘 / 天 / 剩餘1天」文字，綠色播放按鈕就在其正上方約 30~50px
        val daysItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.30f).toInt()..(sh * 0.88f).toInt() &&
            cx > sw * 0.55f &&
            (item.text.contains("剩餘") || item.text.contains("天"))
        }
        if (daysItem != null) {
            val cx = daysItem.bounds.centerX()
            val cy = daysItem.bounds.centerY()
            val btnHalfW = (sw * 0.14f).toInt()
            val btnHalfH = (sh * 0.025f).toInt()
            val btnCenterY = cy - (sh * 0.038f).toInt()
            return Rect(cx - btnHalfW, btnCenterY - btnHalfH, cx + btnHalfW, btnCenterY + btnHalfH)
        }

        // 3. OCR 尋找「▶」播放按鈕
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

    /**
     * 禮物彈窗右上角關閉 (X) 按鈕熱區
     */
    fun getCloseButtonBounds(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.84f).toInt(),
            (sh * 0.20f).toInt(),
            (sw * 0.96f).toInt(),
            (sh * 0.27f).toInt()
        )
    }
}
