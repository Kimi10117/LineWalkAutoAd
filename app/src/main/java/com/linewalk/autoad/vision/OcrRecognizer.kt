package com.linewalk.autoad.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class OcrItem(
    val text: String,
    val bounds: Rect
)

/**
 * 第二層辨識：Google ML Kit 離線中英文 OCR 辨識器 (OcrRecognizer)。
 * 負責辨識遊戲 Canvas / WebView 內的「觀看廣告」「領取」「確認」按鈕，
 * 以及廣告畫面中的「略過」「關閉」「已獲得獎勵」「倒數秒數」。
 */
class OcrRecognizer {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    /**
     * 對全螢幕截圖執行離線中英文 OCR，回傳所有文字行與元素座標。
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
                    if (lineBox != null && lineText.isNotEmpty()) {
                        items.add(OcrItem(lineText, lineBox))
                    }
                    // 同時加入單一詞彙元素，讓短按鈕（如「略過」「關閉」「X」）定位更精確
                    for (element in line.elements) {
                        val elBox = element.boundingBox
                        val elText = element.text.trim()
                        if (elBox != null && elText.isNotEmpty() && elText != lineText) {
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
                val inRoi = roiRect == null || roiRect.contains(item.bounds.centerX(), item.bounds.centerY())
                val reasonableSize = item.bounds.width() < screenWidth * 0.85f &&
                    item.bounds.height() < screenHeight * 0.15f
                val excluded = excludeKeywords.any { ex -> item.text.contains(ex, ignoreCase = true) }
                inRoi && reasonableSize && !excluded && item.text.contains(kw, ignoreCase = true)
            }
            if (match != null) return match
        }
        return null
    }

    /**
     * 檢查目前廣告畫面是否仍在倒數計時中（例如「還有 14 秒」「12秒後可獲得獎勵」「Reward in 10s」）。
     * 若仍在倒數，回傳剩餘秒數估計值；若未偵測到倒數，回傳 null。
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

    fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "OcrRecognizer"

        private val COUNTDOWN_REGEX = Regex(
            "(?:還有|剩餘|倒數)?\\s*(\\d{1,2})\\s*(?:秒|s|S|sec)|(?:in|remaining)\\s*(\\d{1,2})\\s*s",
            RegexOption.IGNORE_CASE
        )

        /** 遊戲內觸發看廣告的常見按鈕文字 */
        val GAME_AD_TRIGGER_KEYWORDS = listOf(
            "看廣告",
            "觀看廣告",
            "觀看影片",
            "看影片",
            "免費領取",
            "看廣告領取",
            "廣告領取",
            "2倍",
            "加倍",
            "再抽一次",
            "免費抽"
        )

        /** 遊戲內無廣告時的一般領取或確認關閉文字 */
        val GAME_CLAIM_CONFIRM_KEYWORDS = listOf(
            "確認",
            "確定",
            "獲得",
            "領取",
            "收下",
            "關閉",
            "好",
            "OK"
        )

        /** 廣告播放完畢後的略過/關閉文字 */
        val AD_CLOSE_OCR_KEYWORDS = listOf(
            "略過廣告",
            "跳過廣告",
            "關閉廣告",
            "略過",
            "跳過",
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

        /** 廣告「已獲得獎勵」提示文字（看到此文字代表倒數已結束，可以開始找 X） */
        val AD_REWARD_GRANTED_KEYWORDS = listOf(
            "已獲得獎勵",
            "獎勵已發放",
            "獲得獎勵",
            "Reward granted",
            "Reward earned"
        )
    }
}
