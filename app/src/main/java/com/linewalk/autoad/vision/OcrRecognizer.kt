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

        val singleCharSymbols = setOf("✕", "×", "X", "x", "✖", "╳")
        for (kw in keywords) {
            val isSingleCharKw = kw in singleCharSymbols
            val match = items.firstOrNull { item ->
                val cx = item.bounds.centerX()
                val cy = item.bounds.centerY()
                if (FloatingOverlayService.isInsideOverlay(cx, cy)) return@firstOrNull false
                val cleanText = item.text.trim().replace(" ", "")
                if (item.text.length > maxTextLength) return@firstOrNull false
                if (isSingleCharKw && cleanText.length > 2) return@firstOrNull false

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

        // 1. 檢查中央兩側遊戲畫布邊緣 (x in 0.04..0.12, y in 0.45..0.55) 是否被半透明黑幕壓暗 (平均亮度 < 180f)
        var edgeLumSum = 0f
        var edgeSamples = 0
        val eYStart = (sh * 0.45f).toInt()
        val eYEnd = (sh * 0.55f).toInt()
        val eXStart = (sw * 0.04f).toInt()
        val eXEnd = (sw * 0.12f).toInt()
        var y = eYStart
        while (y <= eYEnd) {
            var x = eXStart
            while (x <= eXEnd) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                edgeLumSum += (0.299f * r + 0.587f * g + 0.114f * b)
                edgeSamples++
                x += 8
            }
            y += 5
        }
        val edgeMean = if (edgeSamples > 0) edgeLumSum / edgeSamples else 255f
        if (edgeMean >= 180f) return false

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

        return edgeMean < 180f && footerWhiteRatio > 0.55f && headerWhiteRatio > 0.25f
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

        // 00. 嚴格一票否決：若畫面為下半部領獎彈窗（具備底部「取消」按鈕或「紅利」、「跳過廣告」），絕非中央領獎完成彈窗！
        val isBottomRewardSheet = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.85f..0.98f && (t == "取消" || t.contains("取消"))) ||
            (cy in 0.70f..0.92f && (t.contains("跳過廣告") || t.contains("紅利")))
        }
        if (isBottomRewardSheet) {
            return null
        }

        val hasExplicitTicketOrCoinModalTitle = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.20f..0.42f && (
                t.contains("張票券！") || t.contains("張票券!") ||
                t.contains("獲得15張") || t.contains("獲得10張") ||
                t.contains("枚金幣！") || t.contains("枚金幣!") ||
                t.contains("糖果到手") || t.contains("跳過廣告糖果") ||
                t.contains("到手！") || t.contains("到手!") ||
                t.contains("獲得糖果")
            )
        }

        // 00A. 嚴格一票否決：若畫面為【遊戲】分頁（街機遊戲畫面、遊戲挑戰清單）且無明確領獎標題，絕非中央領獎彈窗！
        // 街機台深色背景容易被誤判為黑幕遮罩，導致對著下方遊戲卡片連續空點死鎖
        // 注意：移除 POINTSGAME / 輕鬆賺 / 開始遊玩，因為首頁與步數分頁下方皆有該輪播橫幅！
        val isGameTabScreen = !hasExplicitTicketOrCoinModalTitle && items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("秘寶挖挖哇") ||
            t.contains("數獨達人") ||
            t.contains("接龍高手") ||
            t.contains("簡單賺金幣")
        }
        if (isGameTabScreen) {
            return null
        }

        // 00B. 嚴格一票否決：若處於抽抽樂中獎結果畫面或抽抽樂主面板（紅橘箱子、完成任務即可獲得大量金幣、查看獎項等），
        // 絕不觸發中央領獎彈窗關閉，防止誤點下方黃色「完成任務 即可獲得 大量金幣!!」廣告橫幅！
        val isLotteryPanelOrResult = !hasExplicitTicketOrCoinModalTitle && items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            t.contains("中獎囉") || t.contains("中獎") || t.contains("分享給好友") ||
            t.contains("查看獎項") || t.contains("獎項一覽") ||
            t.contains("完成任務") || t.contains("即可獲得") || t.contains("大量金幣") ||
            t.contains("5張抽") || t.contains("50張抽") || t.contains("回復還需") ||
            (cy in 0.08f..0.22f && (t == "抽抽樂" || t.contains("抽抽樂"))) ||
            (cy in 0.60f..0.76f && (t.contains("x1", ignoreCase = true) || t.contains("1張")) && !t.contains("金幣") && !t.contains("票券") && !t.contains("糖果"))
        }
        if (isLotteryPanelOrResult) {
            return null
        }

        // 1. OCR 搜尋彈窗下半部真正的白底領獎彈窗範圍 (y = 0.60..0.78, x = 0.20..0.80)
        // 嚴格排除 Google Play / AdMob 應用推薦廣告橫幅關鍵字，且「OK」必須採用全詞精確比對
        val closeOcr = items.firstOrNull { item ->
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            val normY = cy.toFloat() / sh
            val normX = cx.toFloat() / sw
            if (FloatingOverlayService.isInsideOverlay(cx, cy)) return@firstOrNull false
            if (normY !in 0.60f..0.78f || normX !in 0.20f..0.80f) return@firstOrNull false

            val t = item.text.trim()
            // 嚴格排除 Google Play 推薦廣告橫幅關鍵字
            val isAdBanner = t.contains("Google Play", ignoreCase = true) ||
                t.contains("Play Store", ignoreCase = true) ||
                t.contains("Play 商店") ||
                t.contains("Play商店") ||
                t.contains("安裝") || t.contains("下載") ||
                t.contains("Sponsored", ignoreCase = true) ||
                t.contains("AD", ignoreCase = false) ||
                t.contains("廣告") || t.contains("推薦") ||
                t.contains("Grok", ignoreCase = true) ||
                t.contains("TikTok", ignoreCase = true) ||
                t.contains("Book", ignoreCase = true)
            if (isAdBanner) return@firstOrNull false

            // OK 必須全詞精確比對，嚴禁 substring contains
            val isExactOk = Regex("""^\s*\[?\s*OK\s*\]?\s*$""", RegexOption.IGNORE_CASE).matches(t) || t.equals("OK", ignoreCase = true)
            val isChineseClose = listOf("關閉", "確認", "確定", "收下").any { t.contains(it) }

            (isExactOk || isChineseClose) && t.length <= 6
        }
        // 2. 檢查是否有『獲得15張票券！』『獲得60枚金幣！』『1 跳過廣告糖果到手！』標題文字，或像素級中央彈窗特徵
        val hasRewardTitleOcr = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.20f..0.42f &&
                !t.contains("輕鬆") &&
                !t.contains("簡單") &&
                !t.contains("玩遊戲") &&
                !t.contains("POINTS") &&
                !t.contains("Sponsored") &&
                !t.contains("AD") &&
                !t.contains("推薦") &&
                !t.contains("條款") &&
                !t.contains("最多") &&
                !t.contains("5000") &&
                !t.contains("10000") &&
                !t.contains("完成任務") &&
                !t.contains("即可獲得") &&
                !t.contains("大量") &&
                (
                    t.contains("張票券") ||
                    t.contains("枚金幣") ||
                    t.contains("糖果到手") ||
                    t.contains("跳過廣告糖果") ||
                    t.contains("到手！") ||
                    t.contains("到手!") ||
                    t.contains("獲得糖果") ||
                    ((t.contains("票券") || t.contains("金幣") || t.contains("糖果")) && (t.contains("獲得") || t.contains("到手")) && (t.contains("！") || t.contains("!")))
                )
        }
        val isDimmedModal = isDimmedCenterModalOpen(screenBitmap)

        // 像素級驗證中央領獎彈窗底部的白底黑字 [ 關閉 ] 膠囊按鈕 (y in 0.675..0.710)：
        // 當懸浮視窗遮擋上半部『獲得15張票券！ / 1 跳過廣告糖果到手！』標題且 OCR 漏抓『關閉』二字時，仍可精準識別並點擊關閉！
        val hasPixelCloseButton = if (isDimmedModal && sw >= 100 && sh >= 100) {
            var darkCenterPts = 0
            val y1 = (sh * 0.675f).toInt().coerceIn(0, sh - 1)
            val y2 = (sh * 0.710f).toInt().coerceIn(y1 + 1, sh)
            val cx1 = (sw * 0.44f).toInt().coerceIn(0, sw - 1)
            val cx2 = (sw * 0.56f).toInt().coerceIn(cx1 + 1, sw)
            for (y in y1 until y2 step 2) {
                for (x in cx1 until cx2 step 2) {
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < 85 && g < 85 && b < 85) darkCenterPts++
                }
            }
            var whiteWingPts = 0
            var wingTotal = 0
            val wxL1 = (sw * 0.25f).toInt().coerceIn(0, sw - 1)
            val wxL2 = (sw * 0.40f).toInt().coerceIn(wxL1 + 1, sw)
            val wxR1 = (sw * 0.60f).toInt().coerceIn(0, sw - 1)
            val wxR2 = (sw * 0.75f).toInt().coerceIn(wxR1 + 1, sw)
            for (y in y1 until y2 step 3) {
                for (x in wxL1 until wxL2 step 4) {
                    val c = screenBitmap.getPixel(x, y)
                    if (((c shr 16) and 0xFF) > 230 && ((c shr 8) and 0xFF) > 230 && (c and 0xFF) > 230) whiteWingPts++
                    wingTotal++
                }
                for (x in wxR1 until wxR2 step 4) {
                    val c = screenBitmap.getPixel(x, y)
                    if (((c shr 16) and 0xFF) > 230 && ((c shr 8) and 0xFF) > 230 && (c and 0xFF) > 230) whiteWingPts++
                    wingTotal++
                }
            }
            val wingWhiteRatio = if (wingTotal > 0) whiteWingPts.toFloat() / wingTotal else 0f
            darkCenterPts >= 15 && wingWhiteRatio >= 0.85f
        } else {
            false
        }

        // 若找到 OCR [關閉] 按鈕且具備領獎標題、半透明遮罩或像素級白底黑字關閉鈕，立即點擊關閉（即使底層壓暗背景仍有每日賓果/任務亦優先關閉前景彈窗）！
        if (closeOcr != null && (hasRewardTitleOcr || isDimmedModal || hasPixelCloseButton)) {
            return "OCR[${closeOcr.text}]" to closeOcr.bounds
        }

        // 00C. 嚴格一票否決：若畫面既無 OCR [關閉] 按鈕亦無像素級 [關閉] 膠囊鈕，且處於每日賓果或任務視窗，則不觸發備援盲點
        val isOtherSubDialog = !hasExplicitTicketOrCoinModalTitle && !hasPixelCloseButton && items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy in 0.08f..0.22f && (t.contains("每日賓果") || t.contains("今日賓果") || t.contains("任務"))) ||
            t.contains("距離下次抽選") || t.contains("開啟賓果格") || t.contains("特別任務")
        }
        if (isOtherSubDialog) {
            return null
        }

        // 備援：確認有領獎標題文字且具備半透明遮罩，或同時具備半透明遮罩與像素級白底黑字 [關閉] 膠囊鈕（應對懸浮窗遮住標題文字之情況）！
        if ((hasRewardTitleOcr && (isDimmedModal || hasPixelCloseButton || items.any {
                val s = it.text.replace(" ", "")
                s.contains("張票券！") || s.contains("枚金幣！") || s.contains("張票券!") || s.contains("枚金幣!") ||
                s.contains("糖果到手") || s.contains("到手！") || s.contains("到手!")
            })) || (isDimmedModal && hasPixelCloseButton)
        ) {
            val fallbackCloseRect = Rect(
                (sw * 0.20f).toInt(),
                (sh * 0.672f).toInt(),
                (sw * 0.80f).toInt(),
                (sh * 0.720f).toInt()
            )
            return "領獎彈窗[關閉]鈕" to fallbackCloseRect
        }

        return null
    }

    /**
     * 檢查是否處於向伺服器緩衝/載入廣告的等待畫面（中央顯示『載入中...』，下方顯示『若廣告載入時間過長...』與白色 [取消] 鈕）。
     * 當『載入中...』疊加在色彩複雜的蘋果樹/熊大背景上導致 ML Kit 漏抓『載入中』文字時，
     * 透過像素級特徵（底部中央獨立純白 [取消] 膠囊鈕 RGB > 230，而兩側 x=6%/94% 與彈窗上緣 x=8%,y=68% 皆被半透明黑幕壓暗 < 190）精準識別！
     */
    fun isAdLoadingScreen(items: List<OcrItem>, screenBitmap: Bitmap? = null): Boolean {
        val hasLoadingText = items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("載入中") ||
            t.contains("廣告載入") ||
            t.contains("載入時間過長") ||
            t.contains("重新啟動應用程式") ||
            t.contains("若仍無法獲得解決") ||
            t.contains("読み込み中") ||
            (t.contains("Loading", ignoreCase = true) && items.any { it.text.contains("取消") || it.text.contains("Cancel") })
        }
        if (hasLoadingText) return true

        // 絕對一票否決：若無明確載入文字，且畫面含有抽獎/領獎/中獎/日常任務等遊戲彈窗文字，絕非廣告載入畫面！
        val isGameModalOrResult = items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("中獎") ||
            t.contains("獲得票券") ||
            t.contains("獲得金幣") ||
            t.contains("每日賓果") ||
            t.contains("今日賓果") ||
            t.contains("抽抽樂") ||
            t.contains("分享給好友") ||
            t.contains("領取獎勵")
        }
        if (isGameModalOrResult) return false

        if (screenBitmap != null) {
            val w = screenBitmap.width
            val h = screenBitmap.height
            if (w >= 100 && h >= 100) {
                val cancelItem = items.firstOrNull { item ->
                    val cy = item.bounds.centerY().toFloat() / h
                    val t = item.text.trim()
                    cy in 0.85f..0.96f && t == "取消"
                }
                if (cancelItem != null) {
                    val cy = cancelItem.bounds.centerY().coerceIn(0, h - 1)
                    // 檢查『取消』膠囊按鈕內部是否為高亮純白 (x = 35% 或 65% 避開中央『取消』黑字)
                    val pillLeft = screenBitmap.getPixel((w * 0.35f).toInt().coerceIn(0, w - 1), cy)
                    val pillRight = screenBitmap.getPixel((w * 0.65f).toInt().coerceIn(0, w - 1), cy)
                    val pillBright = (((pillLeft shr 16) and 0xFF) > 225 && ((pillLeft shr 8) and 0xFF) > 225 && (pillLeft and 0xFF) > 225) ||
                        (((pillRight shr 16) and 0xFF) > 225 && ((pillRight shr 8) and 0xFF) > 225 && (pillRight and 0xFF) > 225)

                    // 檢查『取消』膠囊兩側外側背景 (x = 6%, 94%) 與下半部 68% 處是否皆被半透明黑幕壓暗 (< 190)
                    val marginL = screenBitmap.getPixel((w * 0.06f).toInt().coerceIn(0, w - 1), cy)
                    val marginR = screenBitmap.getPixel((w * 0.94f).toInt().coerceIn(0, w - 1), cy)
                    val upperSheet = screenBitmap.getPixel((w * 0.08f).toInt().coerceIn(0, w - 1), (h * 0.68f).toInt().coerceIn(0, h - 1))

                    val isMarginLDimmed = ((marginL shr 16) and 0xFF) < 190 && ((marginL shr 8) and 0xFF) < 190 && (marginL and 0xFF) < 190
                    val isMarginRDimmed = ((marginR shr 16) and 0xFF) < 190 && ((marginR shr 8) and 0xFF) < 190 && (marginR and 0xFF) < 190
                    val isUpperDimmed = ((upperSheet shr 16) and 0xFF) < 190 && ((upperSheet shr 8) and 0xFF) < 190 && (upperSheet and 0xFF) < 190

                    if (pillBright && isMarginLDimmed && isMarginRDimmed && isUpperDimmed) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * 判定是否處於 LINE 內部宣傳促銷全螢幕/半螢幕廣告 (如『還沒有邀請好友 實在太可惜了！』『邀請好友 快來領取吧！！』『獲得金幣的流程』)。
     * 此類別為遊戲內跳出之推廣廣告，在觀看廣告前後或點擊廣告時皆可能出現，應立即點擊右上角 (X) 關閉。
     */
    fun isLineInHousePromoAd(items: List<OcrItem>): Boolean {
        // 絕對一票否決好友頁面（排行榜、好友排行榜、散步趣好友、編輯好友、查看階層、金幣排名）！
        val isFriendOrLeaderboard = items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("排行榜") ||
            t.contains("好友排行榜") ||
            t.contains("散步趣好友") ||
            t.contains("編輯好友") ||
            t.contains("查看階層") ||
            t.contains("我的排名") ||
            t.contains("階層一覽")
        }
        if (isFriendOrLeaderboard) return false

        // 絕對一票否決主畫面特徵（步數專屬特徵、頂部四大分頁標籤、底部導覽列）！
        // 散步趣主畫面底部常有第三方廣告橫幅，絕不可因橫幅上的促銷文字誤判為全螢幕促銷彈窗！
        val hasMainScreenMarkers = items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("今天的步數") || t.contains("距離下次累積還有") || t.contains("步數瓶")
        } || items.count { item ->
            val t = item.text.replace(" ", "")
            (t.length <= 3 && (t == "首頁" || t == "步數" || t == "時間" || t == "遊戲" || t == "移動")) ||
            (t == "集點" || t == "精選" || t == "好友" || t == "使用")
        } >= 2
        if (hasMainScreenMarkers) return false

        var hasPromoKeyword = false
        var hasInviteText = false
        var hasCoinsOrRewardText = false

        for (item in items) {
            val t = item.text.replace(" ", "")
            // 嚴格比對專屬官方全螢幕宣傳彈窗句式，移除通用詞（如單獨的「快來領取」、「都能獲得」）
            if (t.contains("還沒有邀請") ||
                t.contains("實在太可惜了") ||
                (t.contains("太可惜了") && t.contains("邀請")) ||
                t.contains("獲得金幣的流程") ||
                t.contains("邀請好友賺") ||
                t.contains("邀請好友吧") ||
                t.contains("邀請任務觸發") ||
                t.contains("完成邀請任務") ||
                t.contains("被邀請的好友") ||
                t.contains("雙方都可獲得") ||
                t.contains("最高可獲") ||
                t.contains("快來領取吧") ||
                t.contains("邀請好友快來領取") ||
                (t.contains("邀請好友") && (t.contains("快來領取") || t.contains("領取吧") || t.contains("賺取") || t.contains("10000")))
            ) {
                hasPromoKeyword = true
            }

            if (t.contains("邀請好友") || t.contains("邀請")) {
                hasInviteText = true
            }

            if (t.contains("金幣") || t.contains("10000") || t.contains("000")) {
                hasCoinsOrRewardText = true
            }
        }

        if (hasPromoKeyword) return true
        if (hasInviteText && hasCoinsOrRewardText && items.any {
            val t = it.text.replace(" ", "")
            (t.contains("邀請") && t.contains("任務")) ||
            t.contains("獲得金幣的流程") ||
            t.contains("雙方都") ||
            t.contains("可獲得金幣") ||
            t.contains("快來領取") ||
            t.contains("10000")
        }) return true

        return false
    }

    /**
     * LINE 內部宣傳廣告右上角的圓形關閉按鈕 (X) 區域 (中心約 x=91.8%, y=9.1%)。
     */
    fun getLineInHousePromoCloseButton(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.875f).toInt(),
            (sh * 0.065f).toInt(),
            (sw * 0.960f).toInt(),
            (sh * 0.118f).toInt()
        )
    }

    /**
     * 尋找下半部彈窗（如『時間』分頁點擊獲得票券後，按鈕皆在冷卻中）底部的 [ 取消 ] 按鈕：
     * - 嚴格安全機制：若彈窗內仍有可領取的珊瑚粉紅按鈕或有效領獎文字 (如 ▶ 8 獲得)，代表尚未領取，絕對不可點擊 [取消]！
     * - 僅在確定冷卻中 (hasCooldownText 或 hasGrayDisabledButton) 或確定無任何看廣告按鈕時，才回傳取消按鈕！
     */
    fun findPopupCancelButton(
        screenBitmap: Bitmap,
        items: List<OcrItem>,
        templateMatcher: TemplateMatcher? = null
    ): Rect? {
        // 0. 若畫面正在向廣告伺服器載入廣告（中央顯示『載入中...』與白色 [取消] 鈕），代表正在緩衝等候廣告播放，絕不可點擊取消！
        if (isAdLoadingScreen(items, screenBitmap)) {
            return null
        }

        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val btnCheckMargin = (sh * 0.045f).toInt()

        // 0A. 嚴格防呆：確認下半部為真正的未壓暗純白底框彈窗（x = 6%, 94% 於 y = 90.5% 及 x = 8%, y = 68% 皆為亮白 RGB > 220）
        // 若畫面被半透明遮罩壓暗（如廣告緩衝載入中），絕不可點擊 [取消]！
        if (sw >= 100 && sh >= 100) {
            val pLeft = screenBitmap.getPixel((sw * 0.06f).toInt().coerceIn(0, sw - 1), (sh * 0.905f).toInt().coerceIn(0, sh - 1))
            val pRight = screenBitmap.getPixel((sw * 0.94f).toInt().coerceIn(0, sw - 1), (sh * 0.905f).toInt().coerceIn(0, sh - 1))
            val pSheet = screenBitmap.getPixel((sw * 0.08f).toInt().coerceIn(0, sw - 1), (sh * 0.68f).toInt().coerceIn(0, sh - 1))
            val isUndimmedWhiteSheet =
                ((pLeft shr 16) and 0xFF) > 220 && ((pLeft shr 8) and 0xFF) > 220 && (pLeft and 0xFF) > 220 &&
                ((pRight shr 16) and 0xFF) > 220 && ((pRight shr 8) and 0xFF) > 220 && (pRight and 0xFF) > 220 &&
                ((pSheet shr 16) and 0xFF) > 220 && ((pSheet shr 8) and 0xFF) > 220 && (pSheet and 0xFF) > 220
            if (!isUndimmedWhiteSheet) {
                return null
            }
        }

        // 檢查是否有明確的冷卻倒數文字（回復還需 / 還需 / 00:00）或上下按鈕皆反灰
        val hasCooldownText = items.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.55f).toInt()..(sh * 0.92f).toInt() && (
                t.contains("回復還需") ||
                t.contains("還需") ||
                t.contains("回復") ||
                t.contains("復還需") ||
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(t)
            )
        }

        val hasDisabledGrayButtons = templateMatcher?.let { tm ->
            val upperGray = tm.isPopupButtonDisabledGray(screenBitmap, (sh * 0.75f).toInt())
            val lowerGray = tm.isPopupButtonDisabledGray(screenBitmap, (sh * 0.85f).toInt())
            // 必須上下按鈕皆反灰（避免無糖果時僅上方『跳過廣告』為灰色即誤判為全冷卻）
            upperGray && lowerGray
        } ?: false

        // 0. 重要安全防護：若彈窗內仍有高亮可領取的珊瑚粉紅按鈕 (如 ▶ 8 獲得、▶ 15 獲得) 或紅利標籤按鈕，代表獎勵未進入冷卻，絕對不可點擊 [取消]！
        val hasActivePinkBtn = templateMatcher?.let { tm ->
            val pinkBtn = tm.findCoralPinkButton(screenBitmap, 0.66f, 0.88f, "彈窗看廣告鈕")
            pinkBtn != null &&
                !isAreaInCooldown(items, pinkBtn.bounds.top - btnCheckMargin, pinkBtn.bounds.bottom + btnCheckMargin) &&
                !tm.isPopupButtonDisabledGray(screenBitmap, pinkBtn.bounds.centerY())
        } ?: false

        val hasActiveBadgeBtn = templateMatcher?.let { tm ->
            val badgeBtn = tm.findPopupBonusBadgeAdButton(screenBitmap)
            badgeBtn != null &&
                !isAreaInCooldown(items, badgeBtn.bounds.top - btnCheckMargin, badgeBtn.bounds.bottom + (sh * 0.06f).toInt())
        } ?: false

        // 檢查畫面中是否有可領取文字 (如 \d+ 獲得、看廣告)
        // 注意：嚴禁單純匹配 "紅利"，因為冷卻中的按鈕上仍固定印有 "紅利" 標籤！
        // 且必須確認該處不是反灰按鈕 (!isPopupButtonDisabledGray) 且非冷卻中
        val hasActiveClaimRewardOcr = items.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            val isClaimWord = (Regex("""\d+\s*獲得""").containsMatchIn(t) || t.contains("看廣告")) &&
                !t.contains("取消") && !t.endsWith("！") && !t.endsWith("!")
            val isGray = templateMatcher?.isPopupButtonDisabledGray(screenBitmap, cy) ?: false
            val inCooldown = isAreaInCooldown(items, cy - btnCheckMargin, cy + btnCheckMargin)

            cy in (sh * 0.65f).toInt()..(sh * 0.90f).toInt() &&
                isClaimWord &&
                !isGray &&
                !inCooldown
        }

        // 只要彈窗內仍有任何看廣告領獎鈕，絕對不可誤按取消！
        if (hasActivePinkBtn || hasActiveBadgeBtn || hasActiveClaimRewardOcr) {
            return null
        }

        // 1. OCR 搜尋下半部 (y = 0.80..0.98, x = 0.20..0.80) 的「取消」文字
        val cancelOcr = findMatch(
            items = items,
            keywords = listOf("取消"),
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = RectF(0.20f, 0.80f, 0.80f, 0.98f),
            excludeKeywords = listOf("預訂", "訂購", "禮物卡", "購買"),
            maxTextLength = 4
        )
        if (cancelOcr != null) {
            // 只有在明確出現冷卻倒數文字（回復還需、還需、00:xx）或按鈕皆反灰時，才點擊 [取消]！
            // 嚴禁在未冷卻的正常彈窗上誤點取消！
            if (hasCooldownText || hasDisabledGrayButtons) {
                return cancelOcr.bounds
            }
            return null
        }

        // 2. 備援：若下半部出現「回復還需 / 還需 / 00:」倒數中或按鈕反灰，且有彈窗標題（獲得票券 / 獲得金幣）
        val hasModalTitle = items.any { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.52f).toInt()..(sh * 0.75f).toInt() && (
                t.contains("獲得票券") || t.contains("獲得金幣")
            )
        }

        if ((hasCooldownText || hasDisabledGrayButtons) && hasModalTitle) {
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
            val overlaps = item.bounds.top <= bottomY && item.bounds.bottom >= topY
            if (cy in topY..bottomY || overlaps) {
                val t = item.text.replace(" ", "")
                t.contains("回復還需") ||
                t.contains("還需") ||
                t.contains("回復") ||
                t.contains("復還需") ||
                Regex("""\d{1,2}:\d{2}""").containsMatchIn(t)
            } else {
                false
            }
        }
    }

    /**
     * 判斷目前畫面是否為《LINE 散步趣》的主畫面（涵蓋『首頁』『步數』『時間』『遊戲』四大分頁，且未開啟彈窗）。
     */
    /**
     * 檢查是否跳出「無法載入廣告 / 請稍後再試。」錯誤提示彈窗：
     * 當看廣告失敗或網路連線不穩定時，LINE 散步趣會彈出熊大持手機問號圖示與綠色 [關閉] 按鈕。
     * 若未及時點擊 [關閉]，彈窗將遮擋遊戲畫面，導致巡邏誤以為當前分頁已無目標可領而擅自跳過！
     * 回傳該彈窗 [關閉] 按鈕的 Rect。
     */
    fun findAdLoadErrorCloseButton(
        screenBitmap: Bitmap,
        items: List<OcrItem>
    ): Pair<String, Rect>? {
        val sw = screenBitmap.width
        val sh = screenBitmap.height

        // 0. 嚴格一票否決：若畫面為每日賓果、邀請好友宣傳頁、抽抽樂、任務或好友頁面，絕非「無法載入廣告」錯誤彈窗！
        val isNonErrorScreen = items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("每日賓果") || t.contains("今日賓果") || t.contains("距離下次抽選") ||
            t.contains("開啟賓果格") || t.contains("1BINGO") || t.contains("3BINGO") ||
            t.contains("邀請好友") || t.contains("10000") || t.contains("快來領取吧") ||
            t.contains("雙方都") || t.contains("被邀請的好友") ||
            t.contains("抽抽樂") || t.contains("50張") || t.contains("免費抽")
        }
        if (isNonErrorScreen) return null

        val hasErrorText = items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("無法載入廣告") ||
            t.contains("無法載入") ||
            t.contains("請稍後再試") ||
            t.contains("稍後再試") ||
            t.contains("稍後在試") ||
            t.contains("載入失敗") ||
            t.contains("廣告載入失敗") ||
            t.contains("連線逾時") ||
            t.contains("連線錯誤") ||
            t.contains("無法播放") ||
            t.contains("廣告播放失敗")
        }

        // 強制要求必須有明確的錯誤提示文字，徹底廢除無文字時僅憑綠色像素盲猜（防止將賓果綠底、邀請好友綠底或首頁下方原萃綠茶廣告誤判為錯誤彈窗）
        if (!hasErrorText) {
            return null
        }

        // 1. OCR 定位中下半部 (y in 0.58f..0.76f) 的「關閉 / 確認 / 確定」按鈕
        val ocrClose = items.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim()
            cy in 0.58f..0.76f && cx in 0.20f..0.80f && (
                t == "關閉" || t.contains("關閉") ||
                t == "確認" || t == "確定" || t.equals("OK", ignoreCase = true)
            )
        }

        // 2. 幾何像素綠色膠囊按鈕探測 (y in 0.58..0.76)
        var greenButtonPts = 0
        var greenButtonMinX = sw
        var greenButtonMaxX = 0
        var greenButtonMinY = sh
        var greenButtonMaxY = 0

        val y1 = (sh * 0.58f).toInt().coerceIn(0, sh - 1)
        val y2 = (sh * 0.76f).toInt().coerceIn(y1 + 1, sh)
        val x1 = (sw * 0.15f).toInt().coerceIn(0, sw - 1)
        val x2 = (sw * 0.85f).toInt().coerceIn(x1 + 1, sw)

        for (y in y1 until y2 step 2) {
            for (x in x1 until x2 step 2) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 140 && (g - r > 35) && (g - b > 35)) {
                    greenButtonPts++
                    if (x < greenButtonMinX) greenButtonMinX = x
                    if (x > greenButtonMaxX) greenButtonMaxX = x
                    if (y < greenButtonMinY) greenButtonMinY = y
                    if (y > greenButtonMaxY) greenButtonMaxY = y
                }
            }
        }

        val dynamicButtonRect = if (greenButtonPts >= 25 && greenButtonMaxX > greenButtonMinX && greenButtonMaxY > greenButtonMinY) {
            Rect(greenButtonMinX, greenButtonMinY, greenButtonMaxX, greenButtonMaxY)
        } else {
            Rect(
                (sw * 0.15f).toInt(),
                (sh * 0.64f).toInt(),
                (sw * 0.85f).toInt(),
                (sh * 0.70f).toInt()
            )
        }

        if (ocrClose != null) return "錯誤彈窗[關閉]OCR" to ocrClose.bounds
        return "錯誤彈窗綠色[關閉]鈕" to dynamicButtonRect
    }

    fun isLineWalkMainScreenVisible(
        screenBitmap: Bitmap,
        items: List<OcrItem>,
        templateMatcher: TemplateMatcher
    ): Boolean {
        // 00. 若彈出「無法載入廣告 / 請稍後再試。」錯誤提示彈窗，代表非主畫面！
        if (findAdLoadErrorCloseButton(screenBitmap, items) != null) {
            return false
        }

        // 0. 若畫面正在向廣告伺服器載入廣告（中央顯示『載入中...』與白色 [取消] 鈕），代表正在等候廣告播放，絕非主畫面！
        if (isAdLoadingScreen(items, screenBitmap)) {
            return false
        }

        // 0B. 若畫面為 LINE 內部宣傳促銷廣告 (如『還沒有邀請好友 實在太可惜了！』)，絕非主畫面！
        if (isLineInHousePromoAd(items)) {
            return false
        }

        val sh = screenBitmap.height

        // 1. 若看完廣告後的中央領獎彈窗 (『獲得15張票券！ [關閉]』) 正開啟中，代表不在主畫面！
        // 遊戲分頁一票否決：街機背景易誤觸 isDimmedCenterModalOpen 假陽性，當為遊戲分頁時徹底屏蔽純像素遮罩判定，唯有真正偵測到領獎彈窗關閉按鈕時方判定不在主畫面
        // 注意：移除 POINTSGAME / LINEPOINTS / 輕鬆賺 / 開始遊玩，因為首頁與步數分頁下方皆有該輪播橫幅！
        val isGameTabScreen = items.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("秘寶挖挖哇") ||
            t.contains("數獨達人") ||
            t.contains("接龍高手") ||
            t.contains("簡單賺金幣")
        }
        if ((!isGameTabScreen && (findCenterRewardModalCloseButton(screenBitmap, items) != null || isDimmedCenterModalOpen(screenBitmap))) ||
            (isGameTabScreen && findCenterRewardModalCloseButton(screenBitmap, items) != null)
        ) {
            return false
        }

        // 2. 檢查是否有真正的下半部領獎彈窗：
        // 必須同時具備明確的領獎彈窗標題（如『獲得15張票券！』『獲得60枚金幣！』）與底部『取消』按鈕，且嚴格排除底層橫幅廣告關鍵字
        val hasBottomModalTitle = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.52f..0.75f &&
                !t.contains("輕鬆") && !t.contains("簡單") && !t.contains("遊玩") && !t.contains("Sponsored") && !t.contains("AD") &&
                (t.contains("獲得票券！") || t.contains("獲得金幣！") || t.contains("獲得票券!") || t.contains("獲得金幣!") ||
                 (t.contains("獲得") && (t.contains("票券") || t.contains("金幣")) && (t.contains("！") || t.contains("!"))))
        }
        val hasBottomModalCancel = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.trim()
            cy in 0.85f..0.97f && t == "取消"
        }
        if (hasBottomModalTitle && hasBottomModalCancel) {
            return false
        }

        // 3. 若畫面出現了任何日常子彈窗（每日賓果、抽抽樂、任務、接收禮物、好友排行榜）或全螢幕推廣頁，代表絕非主畫面！
        val hasSubDialogOpen = items.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            (cy < 0.35f && (
                t.contains("每日賓果") || t.contains("今日賓果") || (t == "賓果" && cy < 0.20f) ||
                t.contains("抽抽樂") ||
                t.contains("任務") ||
                t.contains("禮物盒") || t.contains("接收禮物") ||
                (cy in 0.18f..0.32f && (t == "禮物" || t == "禮物盒")) ||
                t.contains("送來的禮物") || (t.contains("收到") && t.contains("禮物")) ||
                t.contains("好友排行榜") || t.contains("好友清單") ||
                t.contains("邀請好友") || t.contains("服務條款") || t.contains("條款與條件") ||
                t.contains("想體驗更多遊戲") || t.contains("解鎖更多遊戲")
            )) || (cy in 0.35f..0.85f && (
                t.contains("開啟賓果格") || t.contains("立即開啟") || t.contains("BINGO") || t.contains("OPEN") ||
                t.contains("再抽一次") || t.contains("送禮物也送自己")
            ))
        }
        if (hasSubDialogOpen) return false

        // 4. 主畫面特徵判定（頂部四大分頁、遊戲分頁、狀態列資產數值、底部導覽列）
        var hasTopTabs = false
        var hasGamePage = false
        var hasUpperStage = false
        var hasTopCoins = false
        var hasBottomNav = false

        var topTabCount = 0
        for (item in items) {
            val t = item.text.replace(" ", "")
            val cy = item.bounds.centerY().toFloat() / sh

            // 頂部四大分頁 (cy in 0.08f..0.18f, 長度<=3 全詞比對，避免廣告文案誤判)
            if (cy in 0.08f..0.18f && t.length <= 3 && (t == "首頁" || t == "步數" || t == "時間" || t == "遊戲" || t == "移動")) {
                topTabCount++
            }

            // 遊戲分頁專屬特徵
            if (t.contains("LINEPOINTS") || t.contains("簡單賺金幣") || t.contains("秘寶挖挖哇") ||
                t.contains("數獨達人") || t.contains("接龍高手") || t.contains("玩LINE")
            ) {
                hasGamePage = true
            }

            // 一般分頁特徵
            if (cy < 0.58f && (
                    t.contains("距離下次") || t.contains("今天的步數") ||
                    t.contains("獲得票券") || t.contains("獲得金幣") ||
                    t.contains("達成條件") || t.contains("速度")
                )
            ) {
                hasUpperStage = true
            }

            // 頂部狀態列資產數值
            if (cy < 0.10f && t.any { it.isDigit() }) {
                hasTopCoins = true
            }

            // 底部導覽列：集點 / 好友 / 使用 / 精選
            if (cy > 0.85f && (
                t == "集點" ||
                (t.contains("好友") && !t.contains("邀請") && !t.contains("被") && !t.contains("名單") && t.length <= 4) ||
                (t.contains("使用") && !t.contains("條款") && t.length <= 4) ||
                (t.contains("精選") && t.length <= 4)
            )) {
                hasBottomNav = true
            }
        }
        hasTopTabs = topTabCount >= 2

        // 必須具備至少 2 個頂部分頁、遊戲分頁專屬特徵，或導覽列+頂部分頁/分頁內容，方為乾淨主畫面！
        // （絕不可單憑看見底部導覽列即認定為主畫面，因為日常子視窗多覆蓋於導覽列上方）
        return hasTopTabs || hasGamePage || (hasBottomNav && (topTabCount >= 1 || hasUpperStage)) || (hasTopCoins && hasUpperStage)
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

    fun detectRewardPendingCountdownSeconds(items: List<OcrItem>, screenHeight: Int): Int? {
        val topThreshold = (screenHeight * 0.25f).toInt()
        val regex = Regex("""(\d{1,2})\s*秒(?:後)?(?:可獲|發放|獲得|可獲得)?(?:獎勵)?""")
        for (item in items) {
            if (item.bounds.centerY() > topThreshold) continue
            val t = item.text.replace(" ", "")
            val match = regex.find(t)
            if (match != null) {
                val sec = match.groupValues[1].toIntOrNull()
                if (sec != null && sec in 1..60) {
                    return sec
                }
            }
        }
        return null
    }

    fun detectAdCountdownSeconds(items: List<OcrItem>, screenHeight: Int, screenWidth: Int = 0): Int? {
        val topBarThreshold = (screenHeight * 0.22f).toInt()
        val hasLongPeriodTimer = items.any { item ->
            val itText = item.text.replace(" ", "")
            itText.contains("距離下次") || itText.contains("抽選還有") || itText.contains("分鐘") || itText.contains("次抽選")
        }

        for (item in items) {
            if (item.bounds.centerY() > topBarThreshold) continue
            val t = item.text
            // 嚴格排除包含「分」、「分鐘」、「距離」、「下次」、「重置」、時間冒號「:」等長週期倒數計時（如賓果冷卻）！
            if (t.contains("分") || t.contains("距離") || t.contains("下次") ||
                t.contains("重置") || t.contains("：") || t.contains(":") || t.contains("抽選")
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
            // 支援頂部兩側角落純數字圓圈倒數計時 (如 15、25 等)，若存在遊戲內長週期計時器則嚴格略過純數字
            // 嚴格限定位於左右兩側角落 (cx < 25% 或 cx > 75% 螢幕寬)，避免將抽抽樂結果彈窗正中央「BIG 8獎」的「8」誤認為廣告倒數秒數
            if (!hasLongPeriodTimer) {
                val trimmed = t.trim()
                if (trimmed.length in 1..2 && trimmed.all { it.isDigit() }) {
                    val effectiveSw = if (screenWidth > 0) {
                        screenWidth
                    } else {
                        maxOf((screenHeight * 0.45f).toInt(), items.maxOfOrNull { it.bounds.right } ?: 0)
                    }
                    val cx = item.bounds.centerX()
                    val inSideCorner = effectiveSw <= 0 ||
                        cx < (effectiveSw * 0.25f).toInt() || cx > (effectiveSw * 0.75f).toInt()
                    val sec = trimmed.toIntOrNull()
                    if (inSideCorner && sec != null && sec in 1..60) {
                        return sec
                    }
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "OcrRecognizer"

        private val COUNTDOWN_REGEX = Regex(
            "(?:還有|剩餘|倒數|あと)?\s*(\d{1,2})\s*(?:秒(?:後)?(?:可獲|發放|獲得|可獲得)?(?:獎勵)?|s|S|sec)|(?:in|remaining)\s*(\d{1,2})\s*s",
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
            "9 獲得",
            "9獲得",
            "8 獲得",
            "8獲得",
            "7 獲得",
            "7獲得",
            "6 獲得",
            "6獲得",
            "5 獲得",
            "5獲得",
            "4 獲得",
            "4獲得",
            "3 獲得",
            "3獲得",
            "2 獲得",
            "2獲得",
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
            "好友",
            "輕鬆賺",
            "輕鬆",
            "簡單賺",
            "簡單",
            "玩遊戲",
            "LINE POINTS",
            "POINTS",
            "數獨",
            "秘寶",
            "挖挖哇",
            "條款",
            "服務條款",
            "開始遊玩",
            "遊玩",
            "下載",
            "立即下載",
            "瞭解詳情",
            "了解詳情",
            "酷澎",
            "Coupang",
            "原萃",
            "CRYSTAL",
            "VALLEY",
            "報名中",
            "去逛逛",
            "momo",
            "雙11",
            "必買",
            "解鎖更多",
            "想體驗",
            "Sponsored",
            "adjoe",
            "AD",
            "無料",
            "IRIAM",
            "歓迎"
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
            "關閉廣告",
            "無法獲得",
            "放棄獎勵",
            "玩遊戲",
            "完成任務",
            "雙重獎勵",
            "LINE POINTS",
            "安裝",
            "下載",
            "立即下載",
            "瞭解詳情",
            "了解詳情",
            "酷澎",
            "Coupang",
            "原萃",
            "輕鬆賺",
            "開始遊玩",
            "報名中",
            "去逛逛",
            "雙方",
            "Hide",
            "Report",
            "動畫效果",
            "關閉動畫",
            "省電模式",
            "應用程式設定",
            "選單"
        )

        val AD_CLOSE_OCR_KEYWORDS = listOf(
            "略過廣告",
            "跳過廣告",
            "關閉廣告",
            "離開廣告",
            "退出廣告",
            "跳過",
            "略過",
            "關閉",
            "スキップ",
            "閉じる",
            "広告をスキップ",
            "広告を閉じる",
            "終了",
            "Skip Ad",
            "SKIP",
            "Skip",
            "Next Ad",
            "Next ad",
            "NextAd",
            "Next",
            "Next ->",
            "Next →",
            "下一則廣告",
            "下一個廣告",
            "下一則",
            "下一個",
            "✕",
            "×"
        )

        val AD_CLOSE_EXCLUDE_KEYWORDS = listOf(
            "秒後",
            "還有",
            "剩餘",
            "倒數",
            "s後",
            "sec後",
            "秒可略過",
            "秒可跳過",
            "後可略過",
            "後可跳過",
            "後略過",
            "後跳過",
            "Skip in",
            "Reward in",
            "同意並繼續",
            "下載",
            "立即下載",
            "安裝",
            "隱私權",
            "條款",
            "邀請",
            "好友",
            "必買",
            "雙11",
            "搶購",
            "優惠",
            "去逛逛",
            "逛逛",
            "瞭解詳情",
            "了解詳情",
            "http",
            "momo",
            "酷澎",
            "Coupang",
            "Hide",
            "Report",
            "irrelevant",
            "offensive",
            "Google Play",
            "Play Store",
            "Play 商店",
            "開啟商店",
            "前往商店",
            "立即預訂",
            "立即購買",
            "BOOK",
            "NOW",
            "SHOP",
            "BUY",
            "ORDER"
        )

        val AD_REWARD_GRANTED_KEYWORDS = listOf(
            "已獲得獎勵",
            "獎勵已發放",
            "獲得獎勵",
            "Reward granted",
            "Reward earned",
            "リワードを獲得しました",
            "報酬を獲得しました"
        )
    }
}
