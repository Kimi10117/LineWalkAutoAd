package com.linewalk.autoad.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.linewalk.autoad.ui.FloatingOverlayService
import kotlinx.coroutines.tasks.await

data class OcrItem(
    val text: String,
    val bounds: Rect
)

/**
 * 第二層辨識：Google ML Kit 離線中英文 OCR 辨識器 (OcrRecognizer)。
 * 自動過濾掉懸浮窗本身所在的螢幕區域 (FloatingOverlayService.isInsideOverlay)，
 * 避免辨識到懸浮窗自己的狀態文字。
 */
class OcrRecognizer {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /**
     * 對全螢幕截圖執行離線中英文 OCR，並自動剔除位於懸浮控制窗範圍內的文字。
     */
    suspend fun recognize(bitmap: Bitmap): List<OcrItem> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            val items = mutableListOf<OcrItem>()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val lineBox = line.boundingBox
                    val lineText = line.text.trim()
                    if (lineBox != null && lineText.isNotEmpty() &&
                        !FloatingOverlayService.isInsideOverlay(lineBox.centerX(), lineBox.centerY())
                    ) {
                        items.add(OcrItem(lineText, lineBox))
                    }
                    for (element in line.elements) {
                        val elBox = element.boundingBox
                        val elText = element.text.trim()
                        if (elBox != null && elText.isNotEmpty() && elText != lineText &&
                            !FloatingOverlayService.isInsideOverlay(elBox.centerX(), elBox.centerY())
                        ) {
                            items.add(OcrItem(elText, elBox))
                        }
                    }
                }
            }
            items
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR 辨識失敗", e)
            emptyList()
        }
    }

    /**
     * 在 OCR 結果中搜尋符合關鍵字清單且位於指定相對區域 (Normalized ROI) 內的文字區塊。
     */
    fun findMatch(
        items: List<OcrItem>,
        keywords: List<String>,
        screenWidth: Int,
        screenHeight: Int,
        roiNormalized: RectF? = null,
        excludeKeywords: List<String> = emptyList()
    ): OcrItem? {
        val roiRect = roiNormalized?.let {
            Rect(
                (it.left * screenWidth).toInt(),
                (it.top * screenHeight).toInt(),
                (it.right * screenWidth).toInt(),
                (it.bottom * screenHeight).toInt()
            )
        }

        for (kw in keywords) {
            val match = items.firstOrNull { item ->
                val cx = item.bounds.centerX()
                val cy = item.bounds.centerY()
                if (FloatingOverlayService.isInsideOverlay(cx, cy)) return@firstOrNull false

                // 避開頂部中間 dynamicSpot 動態島區域 (y < 4.5% 且 x 在 28%~72%)
                val inDynamicIsland = cy < screenHeight * 0.045f &&
                    cx in (screenWidth * 0.28f).toInt()..(screenWidth * 0.72f).toInt()
                if (inDynamicIsland) return@firstOrNull false

                val inRoi = roiRect == null || roiRect.contains(cx, cy)
                val reasonableSize = item.bounds.width() < screenWidth * 0.85f &&
                    item.bounds.height() < screenHeight * 0.14f
                val excluded = excludeKeywords.any { ex -> item.text.contains(ex, ignoreCase = true) }
                inRoi && reasonableSize && !excluded && item.text.contains(kw, ignoreCase = true)
            }
            if (match != null) return match
        }
        return null
    }

    /**
     * 檢查目前廣告畫面是否仍在倒數計時中（例如「還有 14 秒」「12秒後可獲得獎勵」）。
     */
    fun detectAdCountdownSeconds(items: List<OcrItem>, screenHeight: Int): Int? {
        val topBarThreshold = (screenHeight * 0.22f).toInt()
        for (item in items) {
            if (item.bounds.centerY() > topBarThreshold) continue
            val t = item.text
            val regexMatch = COUNTDOWN_REGEX.find(t)
            if (regexMatch != null) {
                val sec = regexMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                if (sec != null && sec in 1..60) {
                    return sec
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "OcrRecognizer"

        private val COUNTDOWN_REGEX = Regex(
            "(?:還有|剩餘|倒數)?\\s*(\\d{1,2})\\s*(?:秒|s|S|sec)|(?:in|remaining)\\s*(\\d{1,2})\\s*s",
            RegexOption.IGNORE_CASE
        )

        /** 遊戲內觸發看廣告的常見按鈕文字（務必搭配 GAMES_AD_EXCLUDE_KEYWORDS 排除『不看廣告』） */
        val GAME_AD_TRIGGER_KEYWORDS = listOf(
            "看廣告領取",
            "觀看廣告",
            "觀看影片",
            "看廣告",
            "看影片",
            "廣告領取",
            "2倍領取",
            "2倍",
            "加倍",
            "再抽一次",
            "看廣告抽"
        )

        val GAME_AD_EXCLUDE_KEYWORDS = listOf(
            "不看廣告",
            "不用看",
            "略過廣告",
            "跳過廣告",
            "關閉廣告",
            "賺點數",
            "免費參加"
        )

        /** 遊戲內彈窗關閉、活動推銷略過（如『玩遊戲賺點數 -> 跳過』）與領獎確認文字 */
        val GAME_CLAIM_CONFIRM_KEYWORDS = listOf(
            "跳過",
            "略過",
            "確認",
            "確定",
            "收下",
            "關閉",
            "領取",
            "好",
            "OK",
            "暫不",
            "不用了"
        )

        val GAME_CLAIM_EXCLUDE_KEYWORDS = listOf(
            "同意並繼續",
            "不看廣告",
            "看廣告",
            "觀看",
            "步數",
            "移動",
            "好友",
            "設定",
            "商店",
            "隱私權",
            "條款",
            "玩遊戲",
            "完成任務"
        )

        /** 廣告播放完畢或推銷畫面的略過/跳過/關閉文字（支援畫面頂部與底部） */
        val AD_CLOSE_OCR_KEYWORDS = listOf(
            "略過廣告",
            "跳過廣告",
            "關閉廣告",
            "跳過",
            "略過",
            "關閉",
            "Skip Ad",
            "SKIP",
            "Skip",
            "Close",
            "CLOSE",
            "✕",
            "×",
            ">>"
        )

        val AD_CLOSE_EXCLUDE_KEYWORDS = listOf(
            "秒後",
            "還有",
            "同意並繼續",
            "下載",
            "安裝",
            "隱私權",
            "條款"
        )

        /** 廣告「已獲得獎勵」提示文字 */
        val AD_REWARD_GRANTED_KEYWORDS = listOf(
            "已獲得獎勵",
            "獎勵已發放",
            "獲得獎勵",
            "Reward granted",
            "Reward earned"
        )
    }
}
