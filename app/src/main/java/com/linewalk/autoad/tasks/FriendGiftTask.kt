package com.linewalk.autoad.tasks

import android.graphics.Bitmap
import android.graphics.Rect
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.vision.OcrItem
import kotlin.math.hypot

/**
 * 好友送禮任務 (FriendGiftTask)：
 * - 切換至底部「好友」分頁
 * - 在好友排行榜中尋找綠色「禮物」按鈕
 * - 點擊禮物後，【最優先選擇】「送禮物也送自己」(看廣告獲取雙方獎勵)
 * - 逐一贈送畫面上的好友，送完後向下滾動清單直到全部好友送完
 * - 完成後自動切回底部「集點」主畫面
 */
class FriendGiftTask {

    private val giftedPoints = mutableListOf<android.graphics.Point>()
    private val skippedPoints = mutableListOf<android.graphics.Point>()
    private val buttonClickCounts = mutableMapOf<android.graphics.Point, Int>()

    fun isEnabled(config: AppConfig): Boolean = config.enableFriendGiftTask

    fun resetSession() {
        giftedPoints.clear()
        skippedPoints.clear()
        buttonClickCounts.clear()
    }

    fun clearGiftedPoints() {
        // 保留相容性，但僅在顯式調用時清空已贈送清單，不破壞略過名單
        giftedPoints.clear()
    }

    fun recordButtonClick(point: android.graphics.Point, sh: Int): Int {
        val radius = (sh * 0.020f).toDouble()
        val existingEntry = buttonClickCounts.keys.firstOrNull { pt ->
            hypot((pt.x - point.x).toDouble(), (pt.y - point.y).toDouble()) < radius
        }
        val targetPoint = existingEntry ?: point
        val newCount = (buttonClickCounts[targetPoint] ?: 0) + 1
        buttonClickCounts[targetPoint] = newCount
        return newCount
    }

    fun getButtonClickCount(point: android.graphics.Point, sh: Int): Int {
        val radius = (sh * 0.020f).toDouble()
        val existingEntry = buttonClickCounts.keys.firstOrNull { pt ->
            hypot((pt.x - point.x).toDouble(), (pt.y - point.y).toDouble()) < radius
        } ?: return 0
        return buttonClickCounts[existingEntry] ?: 0
    }

    fun markSkipped(point: android.graphics.Point) {
        if (skippedPoints.none { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) < 15.0 }) {
            skippedPoints.add(point)
        }
    }

    fun markGifted(point: android.graphics.Point) {
        if (giftedPoints.none { hypot((it.x - point.x).toDouble(), (it.y - point.y).toDouble()) < 15.0 }) {
            giftedPoints.add(point)
        }
    }

    fun isAlreadyGiftedOrSkipped(x: Int, y: Int, sh: Int): Boolean {
        val radius = (sh * 0.020f).toDouble()
        val isGifted = giftedPoints.any { pt ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < radius
        }
        if (isGifted) return true

        val isSkipped = skippedPoints.any { pt ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < radius
        }
        if (isSkipped) return true

        val attempts = buttonClickCounts.entries.firstOrNull { (pt, _) ->
            hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < radius
        }?.value ?: 0
        return attempts >= 2
    }

    fun isAlreadyGifted(x: Int, y: Int, sh: Int): Boolean = isAlreadyGiftedOrSkipped(x, y, sh)

    /**
     * 檢查畫面上是否仍顯示剩餘贈送額度 (例如 5/5、4/5、3/5、2/5、1/5、剩餘 3 次 等)
     */
    fun hasRemainingGiftQuota(ocrItems: List<OcrItem>): Boolean {
        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            val match = Regex("([1-5])/5").find(t)
            match != null ||
            (t.contains("次數") && (t.contains("1/5") || t.contains("2/5") || t.contains("3/5") || t.contains("4/5") || t.contains("5/5"))) ||
            (t.contains("剩餘") && Regex("[1-5]").find(t) != null && !t.contains("0"))
        }
    }

    /**
     * 檢查好友送禮次數是否已達上限 (例如 0/5、今日上限、已達上限、贈送完畢)
     * 注意：LINE 散步趣的「禮物次數 5/5」代表今日剩餘 5 次完整額度，0/5 才代表額度用罄！
     */
    fun isGiftQuotaFull(ocrItems: List<OcrItem>): Boolean {
        if (hasRemainingGiftQuota(ocrItems)) return false
        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("0/5") ||
            (t.contains("次數") && (t.contains("0/5") || t.contains("上限") || t.contains("0次"))) ||
            t.contains("今日上限") ||
            t.contains("已達今日上限") ||
            t.contains("已達上限") ||
            t.contains("今日已無贈送次數") ||
            (t.contains("贈送次數") && t.contains("0"))
        }
    }

    fun isFriendsPage(
        ocrItems: List<OcrItem>,
        sh: Int = 0,
        receiveGiftTask: ReceiveGiftTask = ReceiveGiftTask()
    ): Boolean {
        // -1. 嚴格互斥：若當前處於「禮物」抽屜視窗中，絕非好友常規頁面！
        if (receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh)) return false

        // 0. 若畫面上出現送禮確認彈窗（如『要向...贈送金幣嗎？』『也送給自己』『剩餘贈送次數』『贈送完畢』），必定屬於好友頁面！
        val hasSendGiftModal = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("也送給自己") || t.contains("贈送金幣") || t.contains("剩餘贈送次數") ||
            (t.contains("要向") && t.contains("金幣")) ||
            t.contains("贈送完畢") || (t.contains("已贈送") && t.contains("金幣"))
        }
        if (hasSendGiftModal) return true

        // 嚴格排除主畫面四大分頁特徵
        val hasMainTabs = ocrItems.any { item ->
            val cy = item.bounds.centerY()
            val inRange = if (sh > 0) cy in (sh * 0.08f).toInt()..(sh * 0.18f).toInt() else false
            val t = item.text
            inRange && (t.contains("首頁") || t.contains("步數") || t.contains("時間") || t.contains("遊戲"))
        }
        if (hasMainTabs) return false

        // 嚴格排除「邀請好友」推銷宣傳彈窗（如『還沒有邀請好友 實在太可惜了！』『獲得金幣的流程』）
        if (isInvitePromoSheet(ocrItems)) return false

        // 1. 好友頁面專屬特徵（排行榜/好友清單/階層）
        val maxTopY = if (sh > 0) sh * 0.32f else 600f
        return ocrItems.any { item ->
            item.bounds.centerY() < maxTopY && (
                item.text.contains("好友排行榜") ||
                item.text.contains("排行榜") ||
                item.text.contains("好友清單") ||
                item.text.contains("查看階層") ||
                item.text.contains("階層") ||
                item.text.contains("青銅") ||
                item.text.contains("白銀") ||
                item.text.contains("黃金")
            )
        }
    }

    /**
     * 判定是否跳出 LINE 內部宣傳推銷全螢幕/半螢幕廣告 (如『還沒有邀請好友 實在太可惜了！』『獲得金幣的流程』)
     */
    fun isInvitePromoSheet(ocrItems: List<OcrItem>): Boolean {
        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("還沒有邀請") || t.contains("太可惜了") || t.contains("獲得金幣的流程") ||
            t.contains("邀請好友吧") || t.contains("邀請任務觸發") || t.contains("都能獲得幣") ||
            t.contains("完成邀請任務") ||
            (t.contains("邀請好友") && ocrItems.any {
                val o = it.text.replace(" ", "")
                o.contains("流程") || o.contains("都能獲得") || o.contains("太可惜") || o.contains("10000")
            })
        }
    }

    /**
     * LINE 內部宣傳推銷廣告右上角的圓形關閉按鈕 (X) 座標 (x in 86%..98%, y in 6.5%..13%)
     */
    fun getInvitePromoCloseButton(sw: Int, sh: Int): Rect {
        return Rect(
            (sw * 0.86f).toInt(),
            (sh * 0.065f).toInt(),
            (sw * 0.98f).toInt(),
            (sh * 0.13f).toInt()
        )
    }

    /**
     * 尋找畫面中尚未贈送的綠色「禮物」按鈕 (x in 0.76f..0.95f, y in 0.38f..0.85f)
     * 採用精準的相鄰點間距聚類 (Gap Clustering)，完美區分每個好友列的獨立按鈕，並精確點擊在按鈕正中心！
     */
    fun findNextGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 優先：2D 綠色方塊區塊偵測 (LINE 亮綠 #06C755: g > 130 && g > r + 25 && g > b + 25)
        // 搜尋右側按鈕區域 x in 0.76f..0.95f, y in 0.38f..0.85f
        val greenPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.38f).toInt()
        val yEnd = (sh * 0.85f).toInt()
        val xStart = (sw * 0.76f).toInt()
        val xEnd = (sw * 0.95f).toInt()

        for (y in yStart..yEnd step 2) {
            for (x in xStart..xEnd step 2) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // 嚴格區分「未贈送之亮綠按鈕 (#06C755, G≈199)」與「已贈送之深綠金幣印章按鈕 (RGB≈10,97,46)」
                if (g > 150 && (g - r) > 40 && (g - b) > 35) {
                    greenPts.add(android.graphics.Point(x, y))
                }
            }
        }

        if (greenPts.isNotEmpty()) {
            val sortedByY = greenPts.sortedBy { it.y }
            val clusters = mutableListOf<Rect>()
            var curCluster = mutableListOf<android.graphics.Point>()
            val maxGap = (sh * 0.015f).toInt().coerceAtLeast(8)

            for (pt in sortedByY) {
                if (curCluster.isEmpty() || (pt.y - curCluster.last().y) <= maxGap) {
                    curCluster.add(pt)
                } else {
                    if (curCluster.size >= 15) {
                        val minX = curCluster.minOf { it.x }
                        val maxX = curCluster.maxOf { it.x }
                        val minY = curCluster.minOf { it.y }
                        val maxY = curCluster.maxOf { it.y }
                        val w = maxX - minX
                        val h = maxY - minY
                        if (w in (sw * 0.06f).toInt()..(sw * 0.20f).toInt() && h in 15..(sh * 0.12f).toInt()) {
                            clusters.add(Rect(minX, minY, maxX, maxY))
                        }
                    }
                    curCluster = mutableListOf(pt)
                }
            }
            if (curCluster.size >= 15) {
                val minX = curCluster.minOf { it.x }
                val maxX = curCluster.maxOf { it.x }
                val minY = curCluster.minOf { it.y }
                val maxY = curCluster.maxOf { it.y }
                val w = maxX - minX
                val h = maxY - minY
                if (w in (sw * 0.06f).toInt()..(sw * 0.20f).toInt() && h in 15..(sh * 0.12f).toInt()) {
                    clusters.add(Rect(minX, minY, maxX, maxY))
                }
            }

            for (rect in clusters) {
                if (!isAlreadyGiftedOrSkipped(rect.centerX(), rect.centerY(), sh)) {
                    return rect
                }
            }
            // 當前畫面上的綠色按鈕均已送禮完畢或已達略過門檻，回傳 null 以驅動向下滾動！
        }

        // 2. OCR 備援：辨識「禮物」文字 (x > 0.75f, y in 0.38f..0.85f)
        // 重要防呆：已贈送過的好友按鈕下方依然印有灰色「禮物」二字，但上方方塊已變為深綠金幣印章 (G < 140)！
        // 故必須驗證按鈕區域內確實含有亮綠色像素 (g > 150)，絕不可對已贈送之深綠印章按鈕重複空點！
        val giftOcrItems = ocrItems.filter { item ->
            val cy = item.bounds.centerY()
            val cx = item.bounds.centerX()
            cy in (sh * 0.38f).toInt()..(sh * 0.85f).toInt() &&
            cx > (sw * 0.75f).toInt() &&
            item.text.contains("禮物") &&
            !item.text.contains("次數")
        }.sortedBy { it.bounds.top }

        for (item in giftOcrItems) {
            val cx = item.bounds.centerX()
            val cy = item.bounds.centerY()
            val btnCenterY = cy - (sh * 0.035f).toInt()
            val candidateRect = Rect(
                (sw * 0.78f).toInt(),
                (btnCenterY - (sh * 0.030f).toInt()).coerceIn(0, sh - 1),
                (sw * 0.94f).toInt(),
                (btnCenterY + (sh * 0.030f).toInt()).coerceIn(0, sh - 1)
            )
            var brightGreenCount = 0
            for (by in candidateRect.top..candidateRect.bottom step 3) {
                for (bx in candidateRect.left..candidateRect.right step 3) {
                    val c = screenBitmap.getPixel(bx, by)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (g > 150 && (g - r) > 40 && (g - b) > 35) {
                        brightGreenCount++
                    }
                }
            }
            if (brightGreenCount >= 12 && !isAlreadyGiftedOrSkipped(cx, btnCenterY, sh)) {
                return candidateRect
            }
        }

        return null
    }

    /**
     * 檢查送禮確認彈窗：
     * - 使用者指定：【都以送禮物也送自己為主】！
     * - 優先搜尋包含「也送給自己」、「送給自己」、「也送自己」之綠色膠囊按鈕 (帶有廣告播放鍵)
     * - 次選回傳「贈送 / 送禮物」一般按鈕
     */
    fun findSelfGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Pair<String, Rect>? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 【第一優先】OCR 辨識「也送給自己」/「送給自己」/「也送自己」
        val selfGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.replace(" ", "")
            cy in (sh * 0.35f).toInt()..(sh * 0.75f).toInt() && (
                t.contains("也送給自己") ||
                t.contains("送給自己") ||
                t.contains("也送自己") ||
                t.contains("送禮物也送自己") ||
                (t.contains("送") && t.contains("自己"))
            )
        }
        if (selfGiftOcr != null) {
            val cy = selfGiftOcr.bounds.centerY()
            val halfH = (sh * 0.035f).toInt()
            val btnRect = Rect(
                (sw * 0.15f).toInt(),
                cy - halfH,
                (sw * 0.85f).toInt(),
                cy + halfH
            )
            return Pair("也送給自己(OCR)", btnRect)
        }

        // 2. 綠色按鈕色彩聚類 (LINE 亮綠 #06C755: g > 130 && g > r + 25 && g > b + 25, y in 0.45..0.60, x in 0.15..0.85)
        // 彈窗中央的「也送給自己」為亮綠色膠囊按鈕 (寬度約 66% 螢幕寬度)
        val greenPts = mutableListOf<android.graphics.Point>()
        val yStart = (sh * 0.45f).toInt()
        val yEnd = (sh * 0.60f).toInt()
        val xStart = (sw * 0.15f).toInt()
        val xEnd = (sw * 0.85f).toInt()

        for (y in yStart..yEnd step 3) {
            for (x in xStart..xEnd step 3) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (g > 130 && (g - r) > 25 && (g - b) > 25) {
                    greenPts.add(android.graphics.Point(x, y))
                }
            }
        }
        if (greenPts.size >= 35) {
            val minX = greenPts.minOf { it.x }
            val maxX = greenPts.maxOf { it.x }
            val minY = greenPts.minOf { it.y }
            val maxY = greenPts.maxOf { it.y }
            val btnW = maxX - minX
            val btnH = maxY - minY
            if (btnW > sw * 0.40f && btnH in 15..(sh * 0.08f).toInt()) {
                return Pair("也送給自己(綠色按鈕)", Rect(minX, minY, maxX, maxY))
            }
        }

        // 3. 備援：一般「贈送 / 送出 / 送禮物」按鈕
        val normalGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim()
            cy in (sh * 0.55f).toInt()..(sh * 0.75f).toInt() && (
                t == "贈送" || t == "送出" || t == "送禮物" || t == "確認"
            )
        }
        if (normalGiftOcr != null) {
            return Pair("一般贈送", normalGiftOcr.bounds)
        }

        return null
    }

    /**
     * 判定是否處於送禮確認彈窗中（例如『要向...贈送金幣嗎？』、『也送給自己』、『剩餘贈送次數』）
     */
    fun isSendGiftModalOpen(ocrItems: List<OcrItem>): Boolean {
        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("也送給自己") ||
            t.contains("送給自己") ||
            (t.contains("要向") && (t.contains("贈送") || t.contains("金幣"))) ||
            (t.contains("贈送金幣") && !t.contains("贈送完畢") && !t.contains("已贈送")) ||
            t.contains("剩餘贈送次數") ||
            (t.contains("或者") && t.contains("贈送"))
        }
    }

    /**
     * 尋找送禮確認彈窗中的一般白色 [ 贈送 ] 按鈕 (不看廣告，直接贈送好友金幣)
     */
    fun findNormalGiftButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Pair<String, Rect> {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        val normalGiftOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim().replace(" ", "")
            cy in (sh * 0.55f).toInt()..(sh * 0.65f).toInt() && (
                t == "贈送" || t == "送出" || t == "確認"
            )
        }
        if (normalGiftOcr != null) {
            return Pair("直接贈送(OCR)", normalGiftOcr.bounds)
        }

        // 幾何/座標備援：彈窗中央偏下白色圓角框 [ 贈送 ] 按鈕 (x in 16%..84%, y in 58%..63%)
        val defaultRect = Rect(
            (sw * 0.17f).toInt(),
            (sh * 0.582f).toInt(),
            (sw * 0.83f).toInt(),
            (sh * 0.628f).toInt()
        )
        return Pair("直接贈送(標準按鈕)", defaultRect)
    }

    /**
     * 尋找送禮確認彈窗底部的 [ 取消 ] 按鈕 (x in 38%..62%, y in 65%..69%)
     */
    fun findSendGiftCancelButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        val cancelOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY()
            val t = item.text.trim().replace(" ", "")
            cy in (sh * 0.63f).toInt()..(sh * 0.72f).toInt() && t == "取消"
        }
        if (cancelOcr != null) return cancelOcr.bounds

        return Rect(
            (sw * 0.38f).toInt(),
            (sh * 0.650f).toInt(),
            (sw * 0.62f).toInt(),
            (sh * 0.680f).toInt()
        )
    }


    /**
     * 檢查送禮完成確認彈窗（如『贈送完畢』『已贈送金幣給你自己和...』，下方有 [關閉] 按鈕）
     */
    fun findSentCompletedCloseButton(
        screenBitmap: Bitmap,
        ocrItems: List<OcrItem>
    ): Rect? {
        val sh = screenBitmap.height
        val sw = screenBitmap.width

        // 1. 檢查是否存在「贈送完畢」或「已贈送金幣」特徵
        val hasSentCompletedText = ocrItems.any { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cy in 0.20f..0.50f && (
                t.contains("贈送完畢") ||
                t.contains("已贈送") ||
                (t.contains("贈送") && t.contains("自己"))
            )
        }
        if (!hasSentCompletedText) return null

        // 2. OCR 搜尋彈窗下半部 [關閉] 按鈕 (y in 0.65f..0.82f, x in 0.15f..0.85f)
        val closeOcr = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim()
            cy in 0.65f..0.82f && cx in 0.20f..0.80f && (
                t == "關閉" || t.contains("關閉") || t == "確認" || t == "確定" || t == "OK"
            )
        }
        if (closeOcr != null) {
            val cy = closeOcr.bounds.centerY()
            val halfH = (sh * 0.025f).toInt()
            return Rect(
                (sw * 0.15f).toInt(),
                cy - halfH,
                (sw * 0.85f).toInt(),
                cy + halfH
            )
        }

        // 3. 備援幾何座標：中央白底黑邊 [關閉] 膠囊按鈕 (y ≈ 0.707f..0.755f, x ≈ 0.15f..0.85f)
        return Rect(
            (sw * 0.15f).toInt(),
            (sh * 0.705f).toInt(),
            (sw * 0.85f).toInt(),
            (sh * 0.755f).toInt()
        )
    }

    /**
     * 檢查目前是否位於【好友清單】子分頁（而非【排行榜】子分頁）：
     * 好友頁面共有【排行榜】、【邀請】、【好友清單】三大子分頁。
     * 送禮功能僅存在於【排行榜】中，【好友清單】僅能查看/編輯代碼，無任何禮物按鈕！
     */
    fun isOnFriendListSubTab(ocrItems: List<OcrItem>, sh: Int = 0): Boolean {
        // 0. 若畫面中存在任何【排行榜】專屬內容特徵，代表當前必定在【排行榜】子分頁，絕不可誤判為好友清單！
        val hasLeaderboardContent = ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("好友排行榜") ||
            t.contains("參加排行榜") ||
            t.contains("查看階層") ||
            t.contains("禮物次數") ||
            t.contains("未參加") ||
            t.contains("青銅") ||
            t.contains("白銀") ||
            t.contains("黃金")
        }
        if (hasLeaderboardContent) return false

        // 1. 僅當明確出現【好友清單】頁面內部專屬內容特徵時，才判定為位於【好友清單】子分頁！
        // （絕對不可單憑 y in 0.08..0.16 的頂部未選取子分頁標籤「好友清單」文字即誤判，因為頂部三標籤在任何子分頁與禮物抽屜皆常駐顯示！）
        return ocrItems.any { item ->
            val t = item.text.replace(" ", "")
            t.contains("散步趣好友") ||
            t.contains("編輯好友") ||
            t.contains("LINE散步趣代碼") ||
            t.contains("散步趣代碼") ||
            t.contains("您的LINE散步趣") ||
            (t.contains("搜尋") && t.contains("申請")) ||
            (t.contains("申請") && t.contains("接受"))
        }
    }

    /**
     * 取得頂部【排行榜】子分頁標籤座標 (x ≈ 0.08f..0.32f, y ≈ 0.10f..0.145f)：
     * 當誤入【好友清單】時，點擊此處立即切換回【排行榜】進行送禮！
     */
    fun getLeaderboardTabRect(sw: Int, sh: Int, ocrItems: List<OcrItem> = emptyList()): Rect {
        val ocrTab = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim()
            cy in 0.08f..0.16f && cx in 0.05f..0.40f && t.contains("排行榜")
        }
        if (ocrTab != null) {
            return ocrTab.bounds
        }
        return Rect(
            (sw * 0.08f).toInt(),
            (sh * 0.10f).toInt(),
            (sw * 0.32f).toInt(),
            (sh * 0.145f).toInt()
        )
    }

    /**
     * 底部導覽列「好友」按鈕 (第 2 顆，位於底部 x ≈ 0.375f, y ≈ 0.910f)
     * 優先透過 OCR 鎖定文字本體，備援座標嚴格錨定於 sh * 0.910f，精準避開底部 3 鍵導覽列 (54dp, y >= 0.93f) 與手勢條 (16dp)
     */
    fun getBottomFriendsTabRect(sw: Int, sh: Int, ocrItems: List<OcrItem> = emptyList()): Rect {
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.replace(" ", "")
            cy > 0.85f && cx in 0.20f..0.55f &&
            t.contains("好友") && !t.contains("邀請") && !t.contains("被") && !t.contains("名單") && t.length <= 4
        }
        if (ocrMatch != null) {
            return ocrMatch.bounds
        }
        val targetX = (sw * 0.375f).toInt()
        val targetY = (sh * 0.910f).toInt()
        val halfW = (sw * 0.08f).toInt()
        val halfH = (sh * 0.020f).toInt().coerceAtLeast(24)
        return Rect(targetX - halfW, targetY - halfH, targetX + halfW, targetY + halfH)
    }

    /**
     * 底部導覽列「集點」(首頁) 按鈕 (第 1 顆，位於底部 x ≈ 0.125f, y ≈ 0.910f)
     * 優先透過 OCR 鎖定文字本體，備援座標嚴格錨定於 sh * 0.910f，精準避開底部 3 鍵導覽列 (54dp, y >= 0.93f) 與手勢條 (16dp)
     */
    fun getBottomMainTabRect(sw: Int, sh: Int, ocrItems: List<OcrItem> = emptyList()): Rect {
        val ocrMatch = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.replace(" ", "")
            cy > 0.85f && cx < 0.30f && (t == "集點" || (t.contains("集點") && t.length <= 4))
        }
        if (ocrMatch != null) {
            return ocrMatch.bounds
        }
        val targetX = (sw * 0.125f).toInt()
        val targetY = (sh * 0.910f).toInt()
        val halfW = (sw * 0.08f).toInt()
        val halfH = (sh * 0.020f).toInt().coerceAtLeast(24)
        return Rect(targetX - halfW, targetY - halfH, targetX + halfW, targetY + halfH)
    }
}
