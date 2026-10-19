package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.TemplateMatcher

/**
 * 每日賓果任務 (BingoTask)：
 * - 判斷「每日賓果」彈窗是否開啟
 * - 檢查是否有「立即開啟賓果格」按鈕
 * - 檢查是否有連線獎勵按鈕「Get / ▶ Get / 領取」
 * - 提供右上角 (X) 關閉按鈕座標
 */
class BingoTask {

    fun isEnabled(config: AppConfig): Boolean = config.enableBingoTask

    fun isBingoDialogOpen(ocrItems: List<OcrItem>): Boolean {
        return ocrItems.any { item ->
            item.bounds.top < 400 && (
                item.text.contains("每日賓果") ||
                item.text.contains("賓果格") ||
                (item.text.contains("賓果") && item.text.contains("BINGO"))
            )
        }
    }

    /**
     * 尋找「立即開啟賓果格」橘色按鈕 (y ≈ 0.635f)
     */
    fun findOpenCellButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Rect? {
        val sh = screenBitmap.height

        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.58f).toInt()..(sh * 0.70f).toInt() &&
            (item.text.contains("立即開啟") || item.text.contains("開啟賓果格") || item.text.contains("開啟賓果"))
        }
        if (match != null) {
            val cy = match.bounds.centerY()
            // 若為反灰按鈕則代表尚在冷卻中
            if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy)) {
                return match.bounds
            }
        }
        return null
    }

    /**
     * 尋找連線獎勵按鈕 (例如 1 BINGO 下方的紅底白字 [▶ Get] 或 [領取])
     */
    fun findConnectionRewardButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.72f).toInt()..(sh * 0.90f).toInt() &&
            (item.text.contains("Get", ignoreCase = true) || item.text.contains("領取"))
        }
        if (match != null) {
            return match.bounds
        }

        // 顏色檢查：連線獎勵按鈕為紅色/珊瑚粉紅 (#FF4151) 橢圓膠囊按鈕
        val yStart = (sh * 0.78f).toInt()
        val yEnd = (sh * 0.86f).toInt()
        var y = yStart
        while (y <= yEnd) {
            var x = (sw * 0.10f).toInt()
            while (x <= (sw * 0.90f).toInt()) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 215 && g < 85 && b < 105) {
                    return Rect(x - 30, y - 20, x + 30, y + 20)
                }
                x += 16
            }
            y += 8
        }
        return null
    }

    /**
     * 賓果彈窗右上角關閉 (X) 按鈕熱區
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
     * 主畫面上的「賓果」圖示按鈕熱區 (第 1 顆，y ≈ 0.42f)
     */
    fun getMainScreenBingoIconRect(sw: Int, sh: Int, ocrItems: List<OcrItem>): Rect {
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.38f).toInt()..(sh * 0.46f).toInt() &&
            item.bounds.centerX() < (sw * 0.25f).toInt() &&
            item.text.contains("賓果")
        }
        return match?.bounds ?: Rect(
            (sw * 0.05f).toInt(),
            (sh * 0.38f).toInt(),
            (sw * 0.20f).toInt(),
            (sh * 0.46f).toInt()
        )
    }
}
