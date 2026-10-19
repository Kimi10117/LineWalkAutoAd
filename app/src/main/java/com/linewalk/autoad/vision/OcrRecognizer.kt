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
     * 偵測看完廣告後的中央領獎完成彈窗（如『獲得15張票券！』『獲得60枚金幣！』下方附帶白色 [ 關閉 ] 按鈕）。
     * 當此彈窗開啟時，背景的頂部與底部導覽列會被半透明黑幕壓暗 (nav_mean < 160)，且中央上下皆有純白底框。
     */
    fun isDimmedCenterModalOpen(screenBitmap: Bitmap): Boolean {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        if (sw < 100 || sh < 100) return false

        // 1. 檢查底部導覽列 (y = 0.91..0.95, x = 0.15..0.85) 是否被半透明黑幕壓暗
        var navLumSum = 0f
        var navSamples = 0
        val navYStart = (sh * 0.91f).toInt()
        val navYEnd = (sh * 0.95f).toInt()
        val navXStart = (sw * 0.15f).toInt()
        val navXEnd = (sw * 0.85f).toInt()
        var y = navYStart
        while (y <= navYEnd) {
            var x = navXStart
            while (x <= navXEnd) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                navLumSum += (0.299f * r + 0.587f * g + 0.114f * b)
                navSamples++
                x += 12
            }
            y += 6
        }
        val navMean = if (navSamples > 0) navLumSum / navSamples else 255f
        if (navMean >= 165f) return false

        // 2. 檢查彈窗下半部『[ 關閉 ]』按鈕所在的純白底框 (y = 0.66..0.73, x = 0.25..0.75)
        var footerWhiteCount = 0
        var footerSamples = 0
        val fYStart = (sh * 0.66f).toInt()
        val fYEnd = (sh * 0.73f).toInt()
        val fXStart = (sw * 0.25f).toInt()
        val fXEnd = (sw * 0.75f).toInt()
        y = fYStart
        while (y <= fYEnd) {
            var x = fXStart
            while (x <= fXEnd) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 225 && g > 225 && b > 225) footerWhiteCount++
                footerSamples++
                x += 10
            }
            y += 5
        }
        val footerWhiteRatio = if (footerSamples > 0) footerWhiteCount.toFloat() / footerSamples else 0f

        // 3. 檢查彈窗上半部『獲得15張票券！』所在的純白底框 (y = 0.26..0.31, x = 0.25..0.75)
        var headerWhiteCount = 0
        var headerSamples = 0
        val hYStart = (sh * 0.26f).toInt()
        val hYEnd = (sh * 0.31f).toInt()
        y = hYStart
        while (y <= hYEnd) {
            var x = fXStart
            while (x <= fXEnd) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 225 && g > 225 && b > 225) headerWhiteCount++
                headerSamples++
                x += 10
            }
            y += 5
        }
        val headerWhiteRatio = if (headerSamples > 0) headerWhiteCount.toFloat() / headerSamples else 0f

        return footerWhiteRatio > 0.65f && headerWhiteRatio > 0.60f
    }

    /**
     * 尋找看完廣告後的『獲得15張票券！ / 獲得金幣！』中央彈窗的 [ 關閉 ] 按鈕：
     * - 優先使用 OCR 定位彈窗下半部 (y = 0.52..0.82) 的『關閉 / 確認 / 確定 / 收下 / OK』按鈕。
     * - 若 OCR 因字體較細未抓到『關閉』，但看到標題『張票券 / 枚金幣 / 獲得...！』或像素級偵測到中央白底彈窗開啟，
     *   直接回傳標準 [ 關閉 ] 按鈕座標 (x = 35%~65%, y = 66.8%~70.6%)！
     */
    fun findCenterRewardModalCloseButton(
        screenBitmap: Bitmap,
        items: List<OcrItem>
    ): Pair<String, Rect>? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. OCR 搜尋彈窗下半部 (y = 0.52..0.82) 的『關閉 / 確認 / 確定 / 收下 / OK』
        val closeOcr = findMatch(
            items = items,
            keywords = listOf("關閉", "確認", "確定", "收下", "OK"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.18f, 0.52f, 0.82f, 0.82f),
            excludeKeywords = AD_CLOSE_EXCLUDE_KEYWORDS + listOf("關閉廣告"),
            maxTextLength = 6
        )
        if (closeOcr != null) {
            return "OCR[${closeOcr.text}]" to closeOcr.bounds
        }

        // 2. 檢查是否有『獲得15張票券！』『獲得60枚金幣！』標題文字，或像素級中央彈窗特徵
        val hasRewardTitleOcr = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text
            cy in 0.20f..0.42f && (
                t.contains("張票券") || t.contains("枚金幣") ||
                    (t.contains("獲得") && (t.contains("！") || t.contains("!")))
                )
        }
        if (hasRewardTitleOcr || isDimmedCenterModalOpen(screenBitmap)) {
            val fallbackCloseRect = Rect(
                (sw * 0.35f).toInt(),
                (sh * 0.668f).toInt(),
                (sw * 0.65f).toInt(),
                (sh * 0.706f).toInt()
            )
            return "領獎彈窗[關閉]鈕" to fallbackCloseRect
        }

        return null
    }

    /**
     * 尋找下半部彈窗（如『時間』分頁點擊獲得票券後，按鈕皆在冷卻中）底部的 [ 取消 ] 按鈕：
     * - OCR 在螢幕下半部 (y = 0.82..0.97, x = 0.30..0.70) 尋找「取消」
     * - 或當下半部彈窗出現「回復還需 / 還需 / 00:」且按鈕不可用時，回傳底部標準取消區域 (x: 38%..62%, y: 90%..95%)！
     */
    fun findPopupCancelButton(
        screenBitmap: Bitmap,
        items: List<OcrItem>
    ): Rect? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 1. OCR 搜尋下半部 (y = 0.82..0.97, x = 0.30..0.70) 的「取消」文字
        val cancelOcr = findMatch(
            items = items,
            keywords = listOf("取消"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.30f, 0.82f, 0.70f, 0.97f),
            excludeKeywords = listOf("預訂", "訂購", "禮物卡", "購買"),
            maxTextLength = 4
        )
        if (cancelOcr != null) {
            return cancelOcr.bounds
        }

        // 2. 備援：若畫面上方為半透明遮罩，下半部出現「回復還需 / 還需 / 00:」倒數中，且有彈窗標題
        val hasCooldownText = items.any { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.60f).toInt()..(sh * 0.90f).toInt() && (
                item.text.contains("回復還需") ||
                item.text.contains("還需") ||
                item.text.contains("回復")
            )
        }
        val hasModalTitle = items.any { item ->
            val cy = item.bounds.centerY()
            cy in (sh * 0.55f).toInt()..(sh * 0.72f).toInt() && (
                item.text.contains("獲得票券") || item.text.contains("獲得金幣")
            )
        }

        if (hasCooldownText && hasModalTitle) {
            return Rect(
                (sw * 0.38f).toInt(),
                (sh * 0.90f).toInt(),
                (sw * 0.62f).toInt(),
                (sh * 0.95f).toInt()
            )
        }

        return null
    }

    /**
     * 檢查指定垂直區域內是否含有冷卻倒數文字（如『回復還需』、『還需』、『回復』或『\d\d:\d\d』時間格式）
     */
    fun isAreaInCooldown(
        items: List<OcrItem>,
        topY: Int,
        bottomY: Int
    ): Boolean {
        return items.any { item ->
            val cy = item.bounds.centerY()
            if (cy in topY..bottomY) {
                val t = item.text
                t.contains("回復還需") ||
                t.contains("還需") ||
                t.contains("回復") ||
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(t)
            } else {
                false
            }
        }
    }

    /**
     * 判斷目前畫面是否為《LINE 散步趣》的主畫面（涵蓋『首頁』『步數』『時間』『遊戲』四大分頁，且未開啟彈窗）。
     */
    fun isLineWalkMainScreenVisible(
        screenBitmap: Bitmap,
        items: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Boolean {
        val sh = screenBitmap.height

        // 1. 若下半部出現了『紅利 [▶] 15/60 獲得』粉紅按鈕或『紅利 [▶] x 1 獲得』白底粉紅標籤按鈕，代表正開著領獎彈窗！
        if (templateMatcher.findCoralPinkButton(screenBitmap, 0.66f, 0.88f, "彈窗粉紅鈕") != null ||
            templateMatcher.findPopupBonusBadgeAdButton(screenBitmap) != null
        ) {
            return false
        }

        // 2. 若看完廣告後的中央領獎彈窗 (『獲得15張票券！ [關閉]』) 正開啟中，代表不在主畫面！
        if (findCenterRewardModalCloseButton(screenBitmap, items) != null) {
            return false
        }

        // 3. 若畫面出現了彈窗或內部宣傳頁專屬字眼，代表不在乾淨主畫面！
        val hasPopupOrPromoText = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text
            t.contains("邀請好友") || t.contains("10000") ||
                t.contains("張票券") || t.contains("枚金幣") ||
                (cy > 0.50f && (t.contains("跳過廣告") || t.contains("紅利") ||
                    t.contains("15 獲得") || t.contains("15獲得") ||
                    t.contains("60 獲得") || t.contains("60獲得") ||
                    t.contains("x 1 獲得") || t.contains("x1 獲得") || t.contains("x1獲得") ||
                    t.contains("1 獲得") || t.contains("1獲得") ||
                    t.contains("5 獲得") || t.contains("5獲得") ||
                    t == "關閉" || t == "確認" || t == "確定" ||
                    t == "取消" || t.contains("同意並繼續")))
        }
        if (hasPopupOrPromoText) return false

        // 4. 檢查是否有主畫面的底部導覽列 (集點/好友/使用/精選) 與四大分頁 (首頁/步數/時間/遊戲) 元素
        var hasBottomNav = false
        var hasUpperStage = false
        var hasTopCoins = false

        for (item in items) {
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh

            // 底部導覽列：集點 / 好友 / 使用 / 精選
            if (cy > 0.85f && (t.contains("集點") || t.contains("好友") || t.contains("使用") || t.contains("精選"))) {
                hasBottomNav = true
            }

            // 頂部四大分頁與各分頁主要特徵字眼
            if (cy < 0.58f && (
                    t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲") ||
                    t.contains("距離下次") || t.contains("今天的步數") ||
                    t.contains("獲得票券") || t.contains("獲得金幣") ||
                    t.contains("達成條件") || t.contains("簡單賺金幣") ||
                    t.contains("賓果") || t.contains("抽抽樂") || t.contains("糖果")
                )
            ) {
                hasUpperStage = true
            }

            // 頂部狀態列資產數值 (金幣/票券數字)
            if (cy < 0.10f && t.any { it.isDigit() }) {
                hasTopCoins = true
            }
        }
        return hasBottomNav && (hasUpperStage || hasTopCoins)
    }

    /**
     * 偵測是否誤觸了廣告右上角的『ⓘ / ▷ (AdChoices)』而跳出底部檢舉選單：
     * 包含英文『Hide Ad / Report Ad / Why am I seeing this ad?』或中文『隱藏廣告 / 檢舉廣告 / 為什麼我會看到這則廣告』。
     */
    fun isAdChoicesMenuOpen(items: List<OcrItem>): Boolean {
        return items.any { item ->
            val t = item.text
            AD_CHOICES_MENU_KEYWORDS.any { kw -> t.contains(kw, ignoreCase = true) }
        }
    }

    fun detectAdCountdownSeconds(items: List<OcrItem>, screenHeight: Int): Int? {
        val topBarThreshold = (screenHeight * 0.22f).toInt()
        for (item in items) {
            if (item.bounds.centerY() > topBarThreshold) continue
            val t = item.text
            // 嚴格排除包含「分」、「分鐘」、「距離」、「下次」、「重置」、時間冒號「:」等長週期倒數計時（如賓果冷卻）！
            if (t.contains("分") || t.contains("距離") || t.contains("下次") ||
                t.contains("重置") || t.contains("：") || t.contains(":")
            ) {
                continue
            }
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

        val AD_CHOICES_MENU_KEYWORDS = listOf(
            "Hide Ad",
            "Report Ad",
            "Why am I seeing",
            "Mark ad as",
            "隱藏廣告",
            "檢舉廣告",
            "為什麼我會看到"
        )

        val GAME_AD_TRIGGER_KEYWORDS = listOf(
            "100 獲得",
            "100獲得",
            "60 獲得",
            "60獲得",
            "50 獲得",
            "50獲得",
            "30 獲得",
            "30獲得",
            "15 獲得",
            "15獲得",
            "10 獲得",
            "10獲得",
            "5 獲得",
            "5獲得",
            "x 1 獲得",
            "x1 獲得",
            "x1獲得",
            "× 1 獲得",
            "×1 獲得",
            "1 獲得",
            "1獲得",
            "看廣告獲得",
            "看廣告",
            "觀看廣告",
            "觀看影片",
            "看影片",
            "廣告領取",
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
            "雙方",
            "Hide",
            "Report"
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
            "好友",
            "Hide",
            "Report",
            "irrelevant",
            "offensive"
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
