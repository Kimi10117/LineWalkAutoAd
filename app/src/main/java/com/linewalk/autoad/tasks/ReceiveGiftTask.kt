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
        val hasTitle = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            cy in (sh * 0.20f).toInt()..(sh * 0.28f).toInt() &&
                (t == "禮物" || t == "禮物盒") &&
                !t.contains("卡") && !t.contains("券") && !t.contains("超商")
        }
        val hasContent = ocrItems.any { item ->
            item.text.contains("送來的禮物") ||
            (item.text.contains("收到") && item.text.contains("禮物"))
        }
        return hasTitle && hasContent
    }

    /**
     * 尋找禮物清單中下一個待領取的綠色播放按鈕 [▶] (x ≈ 0.66f..0.91f, y ≈ 0.28f..0.85f)
     */
    fun findNextClaimGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. OCR 尋找「剩下 / 天 / 收到」右側或下方的區塊
        val yStart = (sh * 0.28f).toInt()
        val yEnd = (sh * 0.85f).toInt()
        val xTarget = (sw * 0.77f).toInt()

        // 2. 顏色辨識：搜尋綠色播放按鈕 (LINE 綠色 R < 50, G > 175, B < 110)
        var y = yStart
        while (y <= yEnd) {
            val c = screenBitmap.getPixel(xTarget, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (g > 175 && (g - r > 100) && (g - b > 80)) {
                return Rect(
                    (sw * 0.66f).toInt(),
                    y - 20,
                    (sw * 0.91f).toInt(),
                    y + 20
                )
            }
            y += 8
        }

        return null
    }

    /**
     * 禮物彈窗右上角關閉 (X) 按鈕熱區
     */
    fun getCloseButtonBounds(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.86f).toInt(),
            (sh * 0.21f).toInt(),
            (sw * 0.95f).toInt(),
            (sh * 0.26f).toInt()
        )
    }
}
