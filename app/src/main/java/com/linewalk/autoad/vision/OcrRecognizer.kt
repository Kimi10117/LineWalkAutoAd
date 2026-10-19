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

class OcrRecognizer {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

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

    fun findMatch(
        items: List<OcrItem>,
        keywords: List<String>,
        screenWidth: Int,
        screenHeight: Int,
        roiNormalized: RectF? = null,
        excludeKeywords: List<String> = emptyList(),
        maxTextLength: Int = 20
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
                if (item.text.length > maxTextLength) return@firstOrNull false

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
     * 判斷目前畫面是否為《LINE 散步趣》的主畫面（且下半部沒有被彈窗或全螢幕廣告蓋住）。
     */
    fun isLineWalkMainScreenVisible(
        screenBitmap: Bitmap,
        items: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Boolean {
        val sh = screenBitmap.height

        // 1. 若下半部 (y = 0.68 ~ 0.83) 出現了『紅利 [▶] 15 獲得』的珊瑚粉紅按鈕，代表目前正開著領獎彈窗！
        if (templateMatcher.findCoralPinkButton(screenBitmap, 0.68f, 0.83f, "彈窗粉紅鈕") != null) {
            return false
        }

        // 2. 若畫面出現了彈窗或內部宣傳頁專屬字眼（如『跳過廣告』『紅利』『15 獲得』『邀請好友』『10000』），代表不在主畫面！
        val hasPopupOrPromoText = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text
            t.contains("邀請好友") || t.contains("10000") ||
                (cy > 0.50f && (t.contains("跳過廣告") || t.contains("紅利") || t.contains("15 獲得") ||
                    t.contains("15獲得") || t.contains("5 獲得") || t.contains("5獲得") ||
                    t == "取消" || t.contains("同意並繼續")))
        }
        if (hasPopupOrPromoText) return false

        // 3. 檢查是否有主畫面的底部導覽列 (集點/好友/使用/精選) 與上半部舞台元素
        var hasBottomNav = false
        var hasUpperStage = false
        for (item in items) {
            val t = item.text
            val cy = item.bounds.centerY().toFloat() / sh
            if (cy > 0.88f && (t == "集點" || t == "好友" || t == "使用" || t == "精選")) {
                hasBottomNav = true
            }
            if (cy < 0.48f && (t.contains("距離下次累積") || t.contains("獲得票券") || t == "賓果" || t == "抽抽樂" || t == "糖果")) {
                hasUpperStage = true
            }
        }
        return hasBottomNav && hasUpperStage
    }

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

        val GAME_AD_TRIGGER_KEYWORDS = listOf(
            "15 獲得",
            "15獲得",
            "看廣告",
            "觀看廣告",
            "觀看影片",
            "看影片",
            "廣告領取",
            "看廣告獲得",
            "2倍",
            "加倍",
            "再抽一次",
            "看廣告抽"
        )

        val GAME_AD_EXCLUDE_KEYWORDS = listOf(
            "跳過廣告",
            "不看廣告",
            "不用看",
            "略過廣告",
            "關閉廣告",
            "賺點數",
            "免費參加",
            "雙重獎勵",
            "安裝",
            "Amazon",
            "邀請",
            "好友"
        )

        /** 遊戲內彈窗關閉、活動推銷略過與領獎確認文字（移除單字『好』以避免誤配『邀請好友』！） */
        val GAME_CLAIM_CONFIRM_KEYWORDS = listOf(
            "跳過",
            "略過",
            "確認",
            "確定",
            "收下",
            "關閉",
            "OK",
            "5 獲得",
            "5獲得",
            "暫不",
            "不用了"
        )

        val GAME_CLAIM_EXCLUDE_KEYWORDS = listOf(
            "跳過廣告",
            "15 獲得",
            "15獲得",
            "同意並繼續",
            "不看廣告",
            "看廣告",
            "觀看",
            "步數",
            "移動",
            "好友",
            "邀請",
            "設定",
            "商店",
            "隱私權",
            "條款",
            "玩遊戲",
            "完成任務",
            "雙重獎勵",
            "LINE POINTS",
            "安裝",
            "下載",
            "雙方"
        )

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
            "條款",
            "邀請",
            "好友"
        )

        val AD_REWARD_GRANTED_KEYWORDS = listOf(
            "已獲得獎勵",
            "獎勵已發放",
            "獲得獎勵",
            "Reward granted",
            "Reward earned"
        )
    }
}
