package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem

/**
 * 任務領取任務 (MissionTask)：
 * - 依序檢查「每日」、「每週」、「特別」分頁
 * - 尋找並點擊所有可領取的綠色按鈕「領取」
 * - 嚴格排除尚未完成的「挑戰」按鈕
 * - 全部領取完畢後點右上角 (X) 關閉
 */
class MissionTask {

    fun isEnabled(config: AppConfig): Boolean = config.enableDailyMissionTask

    fun isMissionDialogOpen(ocrItems: List<OcrItem>): Boolean {
        return ocrItems.any { item ->
            item.bounds.top < 280 && item.text.contains("任務")
        } && ocrItems.any { item ->
            item.bounds.top < 380 && (item.text.contains("每日") || item.text.contains("每週") || item.text.contains("特別"))
        }
    }

    /**
     * 尋找清單中可點擊的綠色「領取」按鈕 (排除「挑戰」)
     */
    fun findClaimableMissionButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. OCR 精準辨識「領取」按鈕 (x in 0.65f..0.96f, y in 0.22f..0.85f)
        val claimOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.22f).toInt()..(sh * 0.85f).toInt() &&
            cx > (sw * 0.65f).toInt() &&
            item.text == "領取" &&
            !item.text.contains("挑戰")
        }
        if (claimOcr != null) {
            return claimOcr.bounds
        }

        // 2. 顏色辨識：LINE 綠色按鈕 (#06C755: R < 50, G > 175, B < 110)
        // 任務清單右側按鈕區域 x in 0.70f..0.92f, y in 0.25f..0.80f
        val xTarget = (sw * 0.80f).toInt()
        var y = (sh * 0.25f).toInt()
        val yEnd = (sh * 0.80f).toInt()
        while (y <= yEnd) {
            val c = screenBitmap.getPixel(xTarget, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // 綠色領取按鈕 (非白色挑戰按鈕)
            if (g > 175 && (g - r > 90) && (g - b > 70)) {
                return Rect(
                    (sw * 0.70f).toInt(),
                    y - 20,
                    (sw * 0.92f).toInt(),
                    y + 20
                )
            }
            y += 18
        }

        return null
    }

    /**
     * 取得任務彈窗頂部分頁標籤點擊區域：
     * - "每日": x ≈ 0.50f, y ≈ 0.185f
     * - "每週": x ≈ 0.80f, y ≈ 0.185f
     * - "特別": x ≈ 0.20f, y ≈ 0.185f
     */
    fun getTabRect(tabName: String, sw: Int, sh: Int): Rect {
        val xRatio = when (tabName) {
            "特別" -> 0.20f
            "每週" -> 0.80f
            else -> 0.50f // "每日"
        }
        val yCenter = (sh * 0.185f).toInt()
        return Rect(
            (sw * (xRatio - 0.12f)).toInt(),
            yCenter - 22,
            (sw * (xRatio + 0.12f)).toInt(),
            yCenter + 22
        )
    }

    /**
     * 任務彈窗右上角關閉 (X) 按鈕熱區
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
     * 主畫面上的「任務」圖示按鈕熱區 (第 4 顆，y ≈ 0.42f)
     */
    fun getMainScreenMissionIconRect(sw: Int, sh: Int, ocrItems: List<OcrItem>): Rect {
        val match = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.38f).toInt()..(sh * 0.46f).toInt() &&
            item.bounds.centerX() > (sw * 0.75f).toInt() &&
            item.text.contains("任務")
        }
        return match?.bounds ?: Rect(
            (sw * 0.78f).toInt(),
            (sh * 0.38f).toInt(),
            (sw * 0.96f).toInt(),
            (sh * 0.46f).toInt()
        )
    }
}
