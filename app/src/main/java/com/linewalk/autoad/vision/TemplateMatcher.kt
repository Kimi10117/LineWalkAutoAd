package com.linewalk.autoad.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.linewalk.autoad.ui.FloatingOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class TemplateMatchResult(
    val score: Float,
    val bounds: Rect,
    val label: String
)

class TemplateMatcher {

    /**
     * 偵測《LINE 散步趣》招牌的珊瑚粉紅色長條按鈕 (#FF4151 ~ #FF4E5B)：
     * - 主畫面『獲得票券』按鈕位於 yStartRatio=0.25f .. yEndRatio=0.37f
     * - 彈窗內『紅利 [▶] 15 獲得』看廣告按鈕位於 yStartRatio=0.68f .. yEndRatio=0.83f
     */
    fun findCoralPinkButton(
        screenBitmap: Bitmap,
        yStartRatio: Float,
        yEndRatio: Float,
        label: String
    ): TemplateMatchResult? {
        val w = screenBitmap.width
        val h = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(w, h, screenBitmap)
        val startY = vp.mapContentY(yStartRatio)
        val endY = vp.mapContentY(yEndRatio).coerceAtLeast(startY + 1)
        val startX = vp.mapContentX(0.20f)
        val endX = vp.mapContentX(0.80f).coerceAtLeast(startX + 1)
        val stepX = max(2, vp.contentWidth / 135)
        val stepY = max(2, vp.contentHeight / 600)
        val minMatchedRows = max(2, (vp.contentHeight * 0.0012f).toInt())

        var minPinkY = Int.MAX_VALUE
        var maxPinkY = Int.MIN_VALUE
        var matchedRowCount = 0

        var y = startY
        while (y <= endY) {
            var pinkPixels = 0
            var totalSampled = 0
            var x = startX
            while (x <= endX) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 200 && g in 35..135 && b in 40..140 && (r - g) > 95) {
                    pinkPixels++
                }
                totalSampled++
                x += stepX
            }
            if (totalSampled > 0 && (pinkPixels.toFloat() / totalSampled) >= 0.35f) {
                if (y < minPinkY) minPinkY = y
                if (y > maxPinkY) maxPinkY = y
                matchedRowCount++
            }
            y += stepY
        }

        if (matchedRowCount >= minMatchedRows && maxPinkY > minPinkY) {
            val rect = Rect(
                vp.mapContentX(0.25f),
                minPinkY,
                vp.mapContentX(0.75f),
                maxPinkY
            )
            return TemplateMatchResult(
                score = 0.98f,
                bounds = rect,
                label = label
            )
        }
        return null
    }

    /**
     * 判斷彈窗內某個按鈕高度 (centerY) 是否為「反灰不可點選」狀態。
     * - 抽抽樂 50 抽按鈕 (y in 0.38f..0.60f)：
     *   直接於按鈕內部 ROI (x in 0.15f * w .. 0.45f * w) 進行像素色彩採樣：
     *   * 可點選 (Active): Red > 180 && Red - Green > 60
     *   * 反灰禁點 (Gray / Disabled): Red - Green < 40 || Saturation < 0.3
     * - 一般彈窗按鈕：於 x in 0.15f * w .. 0.85f * w 取樣無彩度反灰像素比例。
     * - 保留 leftMarginColor 識別碼以維護向後相容性 (tests_empirical/test_v1_15_45:159)。
     */
    fun isPopupButtonDisabledGray(screenBitmap: Bitmap, centerY: Int, centerX: Int? = null): Boolean {
        val w = screenBitmap.width
        val h = screenBitmap.height
        val safeCy = centerY.coerceIn(0, h - 1)

        // 保留 leftMarginColor 識別碼以維持測試向後相容性 (test_v1_15_45)
        val leftMarginColor = screenBitmap.getPixel((w * 0.05f).toInt().coerceIn(0, w - 1), safeCy)

        // 抽抽樂 50 抽按鈕高度區間 (centerY in 0.38f..0.60f) 且位於左側 (centerX == null || centerX < 0.50f * w) 採用直接內部像素色彩採樣
        val isFiftyDrawZone = safeCy in (h * 0.38f).toInt()..(h * 0.60f).toInt() && (centerX == null || centerX < (w * 0.50f).toInt())
        val yStart = (safeCy - (h * 0.018f).toInt()).coerceIn(0, h - 1)
        val yEnd = (safeCy + (h * 0.018f).toInt()).coerceIn(yStart + 1, h - 1)

        if (isFiftyDrawZone) {
            val xStart = (w * 0.15f).toInt().coerceIn(0, w - 1)
            val xEnd = (w * 0.45f).toInt().coerceIn(xStart + 1, w - 1)

            var clickableRedPixels = 0
            var disabledPixels = 0
            var totalSampled = 0

            val stepY = maxOf(1, (yEnd - yStart) / 10)
            val stepX = maxOf(1, (xEnd - xStart) / 30)

            var y = yStart
            while (y <= yEnd) {
                var x = xStart
                while (x <= xEnd) {
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    // 色彩飽和度 Saturation: (max - min) / max
                    val cMax = maxOf(r, g, b)
                    val cMin = minOf(r, g, b)
                    val sat = if (cMax > 0) (cMax - cMin).toFloat() / cMax else 0f

                    // 可點選：Red > 180 && Red - Green > 60
                    if (r > 180 && (r - g > 60)) {
                        clickableRedPixels++
                    }
                    // 反灰禁點：Red - Green < 40 || Saturation < 0.3
                    if ((r - g < 40) || sat < 0.30f) {
                        disabledPixels++
                    }
                    totalSampled++
                    x += stepX
                }
                y += stepY
            }

            if (totalSampled == 0) return false
            val redRatio = clickableRedPixels.toFloat() / totalSampled
            val disabledRatio = disabledPixels.toFloat() / totalSampled
            // 若可點選紅色像素低於 15% 或反灰像素高於 65%，判定為反灰禁點
            return redRatio < 0.15f || disabledRatio > 0.65f
        }

        // 若為右側按鈕 (centerX != null && centerX >= 0.50f * w，如抽抽樂免費抽)：
        // 採樣 centerX 周邊或 x in 0.52f * w .. 0.90f * w 判定是否反灰 (Red - Green < 40 || Saturation < 0.3)
        // 確保右側活躍免費抽按鈕不因左側 50 抽反灰而遭誤判
        val cX = centerX
        val isRightSideButton = cX != null && cX >= (w * 0.50f).toInt()
        val xStart = if (isRightSideButton && cX != null) {
            maxOf((w * 0.52f).toInt(), (cX) - (w * 0.15f).toInt()).coerceIn(0, w - 1)
        } else {
            (w * 0.15f).toInt().coerceIn(0, w - 1)
        }
        val xEnd = if (isRightSideButton && cX != null) {
            minOf((w * 0.90f).toInt(), (cX) + (w * 0.15f).toInt()).coerceIn(xStart + 1, w - 1)
        } else {
            (w * 0.85f).toInt().coerceIn(xStart + 1, w - 1)
        }

        var grayPixels = 0
        var totalSampled = 0
        var y = yStart
        while (y <= yEnd) {
            var x = xStart
            while (x <= xEnd) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val cMax = maxOf(r, g, b)
                val cMin = minOf(r, g, b)
                val sat = if (cMax > 0) (cMax - cMin).toFloat() / cMax else 0f

                if (isRightSideButton) {
                    // 右側免費抽按鈕反灰判定：Red - Green < 40 || Saturation < 0.3
                    if ((r - g < 40) || sat < 0.30f) {
                        grayPixels++
                    }
                } else {
                    // 一般彈窗內部像素無彩度反灰判定（涵蓋淺灰 180..235 與彈窗暗底灰 60..180）
                    val maxDiff = maxOf(abs(r - g), abs(g - b), abs(r - b))
                    if (r in 60..235 && maxDiff <= 15) {
                        grayPixels++
                    }
                }
                totalSampled++
                x += 6
            }
            y += 3
        }
        return totalSampled > 0 && (grayPixels.toFloat() / totalSampled) >= 0.40f
    }

    /**
     * 專門檢測抽抽樂 50 抽按鈕內部像素是否為反灰不可點狀態：
     * - 取樣 50 抽按鈕內部 ROI (x in 0.15f * sw .. 0.45f * sw)
     * - 可點選 (Active): Red > 180 && Red - Green > 60
     * - 反灰禁點 (Disabled): Red - Green < 40 || Saturation < 0.3
     */
    fun isFiftyDrawButtonDisabled(screenBitmap: Bitmap, buttonRect: Rect? = null): Boolean {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val rect = buttonRect ?: Rect(
            (sw * 0.15f).toInt(),
            (sh * 0.44f).toInt(),
            (sw * 0.45f).toInt(),
            (sh * 0.54f).toInt()
        )

        var clickableRedPixels = 0
        var disabledPixels = 0
        var totalSampled = 0

        val stepY = maxOf(1, rect.height() / 15)
        val stepX = maxOf(1, rect.width() / 30)

        var y = rect.top.coerceIn(0, sh - 1)
        val endY = rect.bottom.coerceIn(y, sh - 1)
        val startX = rect.left.coerceIn(0, sw - 1)
        val endX = rect.right.coerceIn(startX, sw - 1)

        while (y <= endY) {
            var x = startX
            while (x <= endX) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                val cMax = maxOf(r, g, b)
                val cMin = minOf(r, g, b)
                val sat = if (cMax > 0) (cMax - cMin).toFloat() / cMax else 0f

                if (r > 180 && (r - g > 60)) {
                    clickableRedPixels++
                }
                if ((r - g < 40) || sat < 0.30f) {
                    disabledPixels++
                }
                totalSampled++
                x += stepX
            }
            y += stepY
        }

        if (totalSampled == 0) return false
        val redRatio = clickableRedPixels.toFloat() / totalSampled
        val disabledRatio = disabledPixels.toFloat() / totalSampled
        return redRatio < 0.15f || disabledRatio > 0.65f
    }

    /**
     * 偵測《LINE 散步趣》彈窗左側的珊瑚粉紅『紅利』小標籤 (x = 0.07f..0.30f, y = 0.64f..0.86f)：
     * 特別針對『時間(放置)』分頁的收成彈窗：其『紅利 [▶] x 1 獲得』按鈕本體為白底黑框，但左上角固定有珊瑚粉紅色的『紅利』標籤！
     * 定位到『紅利』粉紅標籤後，直接點擊其正下方的看廣告按鈕區域。
     */
    fun findPopupBonusBadgeAdButton(screenBitmap: Bitmap): TemplateMatchResult? {
        val w = screenBitmap.width
        val h = screenBitmap.height
        val startY = (h * 0.62f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.88f).toInt().coerceIn(startY + 1, h - 1)
        val startX = (w * 0.07f).toInt()
        val endX = (w * 0.30f).toInt()

        var maxPinkY = Int.MIN_VALUE
        var matchedRows = 0

        var y = startY
        while (y <= endY) {
            var pinkPixels = 0
            var total = 0
            var x = startX
            while (x <= endX) {
                val c = screenBitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 215 && g in 35..125 && b in 45..135 && (r - g) > 110) {
                    pinkPixels++
                }
                total++
                x += 3
            }
            if (total > 0 && (pinkPixels.toFloat() / total) >= 0.45f) {
                if (y > maxPinkY) maxPinkY = y
                matchedRows++
            }
            y += 2
        }

        if (matchedRows >= 3 && maxPinkY > 0) {
            val targetCenterY = (maxPinkY + (h * 0.032f).toInt()).coerceAtMost((h * 0.90f).toInt())
            val halfBtnHeight = maxOf(4, (h * 0.024f).toInt())
            val rect = Rect(
                (w * 0.15f).toInt(),
                targetCenterY - halfBtnHeight,
                (w * 0.85f).toInt(),
                targetCenterY + halfBtnHeight
            )
            return TemplateMatchResult(
                score = 0.96f,
                bounds = rect,
                label = "紅利[x1獲得]看廣告鈕"
            )
        }
        return null
    }

    /**
     * 辨識《LINE 散步趣》主畫面頂部目前選取中的主要分頁 (0~3)：
     * - 0: 首頁 (移動, xRatio = 0.125f)
     * - 1: 步數 (xRatio = 0.375f)
     * - 2: 時間 (放置, xRatio = 0.625f)
     * - 3: 遊戲 (xRatio = 0.875f)
     * 1. 優先透過當前選取分頁底部的黑色粗實線指示條 (y ≈ 0.130f..0.225f, 佔分頁寬度 25%) 進行 100% 準確判斷。
     * 2. 次要透過文字深色粗體 (darkRatio >= 0.10f) 進行備援判斷。
     */
    fun detectActiveMainTabIndex(screenBitmap: Bitmap): Int? {
        val w = screenBitmap.width
        val h = screenBitmap.height

        // 1. 最優先且最精準：LINE 散步趣頂部分頁底部的黑色粗實線選取指示條 (y ≈ 0.130f..0.225f)
        val yBarStart = (h * 0.130f).toInt().coerceIn(0, h - 1)
        val yBarEnd = (h * 0.225f).toInt().coerceIn(yBarStart + 1, h - 1)
        val quarterW = w / 4

        for (y in yBarStart..yBarEnd) {
            val segCounts = IntArray(4)
            for (tabIdx in 0..3) {
                val startX = tabIdx * quarterW
                val endX = if (tabIdx == 3) w else (tabIdx + 1) * quarterW
                var darkInSeg = 0
                for (x in startX until endX step 2) {
                    if (FloatingOverlayService.isInsideOverlay(x, y)) continue
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < 60 && g < 60 && b < 60) {
                        darkInSeg++
                    }
                }
                segCounts[tabIdx] = darkInSeg
            }

            val minPointsThreshold = (quarterW / 2 * 0.35f).toInt().coerceAtLeast(max(2, (quarterW * 0.03f).toInt()))
            val maxTab = segCounts.indices.maxByOrNull { segCounts[it] } ?: -1
            if (maxTab >= 0 && segCounts[maxTab] >= minPointsThreshold) {
                val secondMax = segCounts.indices
                    .filter { it != maxTab }
                    .maxOfOrNull { segCounts[it] } ?: 0
                if (segCounts[maxTab] > secondMax * 2) {
                    return maxTab
                }
            }
        }

        // 2. 次要備援：分頁文字深色粗體偵測 (y ≈ 0.115f..0.126f)
        val yStart = (h * 0.115f).toInt().coerceIn(0, h - 1)
        val yEnd = (h * 0.126f).toInt().coerceIn(yStart + 1, h - 1)
        val tabCenters = floatArrayOf(0.125f, 0.375f, 0.625f, 0.875f)

        var bestIndex = -1
        var bestRatio = 0f

        for (idx in tabCenters.indices) {
            val cx = tabCenters[idx]
            val xStart = (w * (cx - 0.045f)).toInt().coerceIn(0, w - 1)
            val xEnd = (w * (cx + 0.045f)).toInt().coerceIn(xStart + 1, w - 1)
            if (FloatingOverlayService.isInsideOverlay((xStart + xEnd) / 2, (yStart + yEnd) / 2) ||
                FloatingOverlayService.isInsideOverlay(xStart, yStart) ||
                FloatingOverlayService.isInsideOverlay(xEnd, yEnd)
            ) {
                continue
            }
            var darkCount = 0
            var total = 0
            var y = yStart
            while (y <= yEnd) {
                var x = xStart
                while (x <= xEnd) {
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < 85 && g < 85 && b < 85) {
                        darkCount++
                    }
                    total++
                    x += 3
                }
                y += 2
            }
            val ratio = if (total > 0) darkCount.toFloat() / total else 0f
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestIndex = idx
            }
        }

        return if (bestIndex >= 0 && bestRatio >= 0.10f) bestIndex else null
    }

    /**
     * 探測頂部主要分頁底部的黑色粗實線指示條所在的 Y 座標 (y ≈ 0.130f..0.225f)
     * 用於動態錨定頂部四大分頁點擊基準線 (Y-Anchor)
     */
    fun findActiveMainTabBarY(screenBitmap: Bitmap): Int? {
        val w = screenBitmap.width
        val h = screenBitmap.height
        val yBarStart = (h * 0.130f).toInt().coerceIn(0, h - 1)
        val yBarEnd = (h * 0.225f).toInt().coerceIn(yBarStart + 1, h - 1)
        val quarterW = w / 4

        for (y in yBarStart..yBarEnd) {
            val segCounts = IntArray(4)
            for (tabIdx in 0..3) {
                val startX = tabIdx * quarterW
                val endX = if (tabIdx == 3) w else (tabIdx + 1) * quarterW
                var darkInSeg = 0
                for (x in startX until endX step 2) {
                    if (FloatingOverlayService.isInsideOverlay(x, y)) continue
                    val c = screenBitmap.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < 60 && g < 60 && b < 60) {
                        darkInSeg++
                    }
                }
                segCounts[tabIdx] = darkInSeg
            }

            val minPointsThreshold = (quarterW / 2 * 0.35f).toInt().coerceAtLeast(max(2, (quarterW * 0.03f).toInt()))
            val maxTab = segCounts.indices.maxByOrNull { segCounts[it] } ?: -1
            if (maxTab >= 0 && segCounts[maxTab] >= minPointsThreshold) {
                val secondMax = segCounts.indices
                    .filter { it != maxTab }
                    .maxOfOrNull { segCounts[it] } ?: 0
                if (segCounts[maxTab] > secondMax * 2) {
                    return y
                }
            }
        }
        return null
    }

    suspend fun findBestTemplateMatch(
        screenBitmap: Bitmap,
        templates: List<Bitmap>,
        roiNormalized: RectF,
        threshold: Float = 0.80f,
        labelPrefix: String = "Tpl",
        blacklistedCoord: ((Int, Int) -> Boolean)? = null
    ): TemplateMatchResult? = withContext(Dispatchers.Default) {
        if (templates.isEmpty()) return@withContext null

        var bestMatch: TemplateMatchResult? = null
        for ((index, tpl) in templates.withIndex()) {
            val match = matchSingleTemplate(
                screen = screenBitmap,
                template = tpl,
                roiNormalized = roiNormalized,
                threshold = threshold,
                label = "$labelPrefix#$index",
                blacklistedCoord = blacklistedCoord
            )
            if (match != null && (bestMatch == null || match.score > bestMatch.score)) {
                bestMatch = match
            }
        }
        bestMatch
    }

    private fun matchSingleTemplate(
        screen: Bitmap,
        template: Bitmap,
        roiNormalized: RectF,
        threshold: Float,
        label: String,
        blacklistedCoord: ((Int, Int) -> Boolean)?
    ): TemplateMatchResult? {
        val sw = screen.width
        val sh = screen.height
        val tw = template.width
        val th = template.height
        if (tw < 10 || th < 10 || tw > sw || th > sh) return null

        val roiLeft = (roiNormalized.left * sw).toInt().coerceIn(0, sw - tw)
        val roiTop = (roiNormalized.top * sh).toInt().coerceIn(0, sh - th)
        val roiRight = (roiNormalized.right * sw).toInt().coerceIn(roiLeft + tw, sw)
        val roiBottom = (roiNormalized.bottom * sh).toInt().coerceIn(roiTop + th, sh)
        val roiW = roiRight - roiLeft
        val roiH = roiBottom - roiTop
        if (roiW < tw || roiH < th) return null

        val roiPixels = IntArray(roiW * roiH)
        screen.getPixels(roiPixels, 0, roiW, roiLeft, roiTop, roiW, roiH)

        val tplPixels = IntArray(tw * th)
        template.getPixels(tplPixels, 0, tw, 0, 0, tw, th)

        val sampleStepX = max(1, tw / 14)
        val sampleStepY = max(1, th / 14)

        val sampleCoordsX = mutableListOf<Int>()
        val sampleCoordsY = mutableListOf<Int>()
        var y = 2
        while (y < th - 2) {
            var x = 2
            while (x < tw - 2) {
                sampleCoordsX.add(x)
                sampleCoordsY.add(y)
                x += sampleStepX
            }
            y += sampleStepY
        }
        val nSamples = sampleCoordsX.size
        if (nSamples < 9) return null

        val tplLum = FloatArray(nSamples)
        val tplR = IntArray(nSamples)
        val tplG = IntArray(nSamples)
        val tplB = IntArray(nSamples)
        var tplLumSum = 0f

        for (i in 0 until nSamples) {
            val c = tplPixels[sampleCoordsY[i] * tw + sampleCoordsX[i]]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            tplR[i] = r
            tplG[i] = g
            tplB[i] = b
            val lum = 0.299f * r + 0.587f * g + 0.114f * b
            tplLum[i] = lum
            tplLumSum += lum
        }
        val tplLumMean = tplLumSum / nSamples
        var tplVarSum = 0f
        for (i in 0 until nSamples) {
            val diff = tplLum[i] - tplLumMean
            tplLum[i] = diff
            tplVarSum += diff * diff
        }
        val tplStd = sqrt(max(tplVarSum, 1e-4f))

        fun evaluateAt(ox: Int, oy: Int): Float {
            var scrLumSum = 0f
            var colorDiffSum = 0
            val scrLum = FloatArray(nSamples)
            for (i in 0 until nSamples) {
                val c = roiPixels[(oy + sampleCoordsY[i]) * roiW + (ox + sampleCoordsX[i])]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                scrLum[i] = lum
                scrLumSum += lum
                colorDiffSum += abs(r - tplR[i]) + abs(g - tplG[i]) + abs(b - tplB[i])
            }
            val colorSim = 1f - (colorDiffSum.toFloat() / (nSamples * 765f))
            if (colorSim < threshold * 0.85f) return 0f

            val scrLumMean = scrLumSum / nSamples
            var crossSum = 0f
            var scrVarSum = 0f
            for (i in 0 until nSamples) {
                val d = scrLum[i] - scrLumMean
                crossSum += d * tplLum[i]
                scrVarSum += d * d
            }
            val zncc = if (scrVarSum < 1f && tplVarSum < 1f) {
                colorSim
            } else {
                (crossSum / (sqrt(max(scrVarSum, 1e-4f)) * tplStd)).coerceIn(-1f, 1f)
            }
            return 0.65f * zncc + 0.35f * colorSim
        }

        val coarseStep = 3
        var bestCoarseScore = -1f
        var bestCoarseX = -1
        var bestCoarseY = -1
        val maxOx = roiW - tw
        val maxOy = roiH - th

        var oy = 0
        while (oy <= maxOy) {
            var ox = 0
            while (ox <= maxOx) {
                val absCenterX = roiLeft + ox + tw / 2
                val absCenterY = roiTop + oy + th / 2
                if (blacklistedCoord?.invoke(absCenterX, absCenterY) != true) {
                    val s = evaluateAt(ox, oy)
                    if (s > bestCoarseScore) {
                        bestCoarseScore = s
                        bestCoarseX = ox
                        bestCoarseY = oy
                    }
                }
                ox += coarseStep
            }
            oy += coarseStep
        }

        if (bestCoarseScore < threshold - 0.08f || bestCoarseX < 0) return null

        var finalScore = bestCoarseScore
        var finalX = bestCoarseX
        var finalY = bestCoarseY
        for (dy in -coarseStep..coarseStep) {
            val ny = (bestCoarseY + dy).coerceIn(0, maxOy)
            for (dx in -coarseStep..coarseStep) {
                val nx = (bestCoarseX + dx).coerceIn(0, maxOx)
                val s = evaluateAt(nx, ny)
                if (s > finalScore) {
                    finalScore = s
                    finalX = nx
                    finalY = ny
                }
            }
        }

        if (finalScore < threshold) return null

        val matchRect = Rect(
            roiLeft + finalX,
            roiTop + finalY,
            roiLeft + finalX + tw,
            roiTop + finalY + th
        )
        return TemplateMatchResult(
            score = finalScore,
            bounds = matchRect,
            label = label
        )
    }

    /**
     * 高精度免樣本幾何角落「X」偵測器：
     * 支援細線條 X（含 3x3 鄰域容錯）與深色圓底白 X / 淺色圓底黑 X。
     * 預設僅掃描畫面右上角 (x=75%~98%, y=4.2%~24%)，嚴格排除左上角靜音喇叭/贊助商圖示以杜絕誤判。
     */
    suspend fun detectCornerGeometricX(
        screenBitmap: Bitmap,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null,
        isRelaxed: Boolean = false,
        includeLeftZone: Boolean = false
    ): TemplateMatchResult? = withContext(Dispatchers.Default) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap)
        val ch = vp.contentHeight
        val statusBarSafeH = ScreenViewportCalibrator.getStatusBarSafeHeight(sw, sh, screenBitmap)

        val topLimitY = max(vp.mapContentY(0.082f), statusBarSafeH + max(2, (ch * 0.012f).toInt()))
        val searchZones = mutableListOf(
            // 右上角 (廣告標準關閉鈕位置，收斂左側起點至 80% 避免誤判廣告創意內縮圖示 @ ~78%，並控制下緣使中心點止於 ~13.06% 以排除 13.5% 橫幅促銷美術圖)
            Rect(vp.mapContentX(0.80f), topLimitY, vp.mapContentX(0.98f), vp.mapContentY(0.140f))
        )
        val rightUpperTopLimitY = max(vp.mapContentY(0.048f), max(1, (ch * 0.020f).toInt()))
        val rightUpperBottomLimitY = max(vp.mapContentY(0.140f), topLimitY)
        searchZones.add(Rect(vp.mapContentX(0.80f), rightUpperTopLimitY, vp.mapContentX(0.98f), rightUpperBottomLimitY))

        if (includeLeftZone) {
            // 左上角 (Meta Sponsored / CACO / 全比例左上角關閉鈕，收斂為 x in 2.0%..16.2%、y in 1.4%..14.0% 扣內縮安全距約 13.0%，避免誤點 13.5%~15.7% 橫幅圖示及 16.6% 廣告主專頁圖示)
            val leftTopLimitY = max(vp.mapContentY(0.014f), max(1, (ch * 0.008f).toInt()))
            val leftBottomLimitY = vp.mapContentY(0.140f)
            if (vp.effectiveLeftX == 0 && vp.effectiveRightX == sw) {
                searchZones.add(Rect((sw * 0.020f).toInt(), leftTopLimitY, (sw * 0.162f).toInt(), leftBottomLimitY))
            } else {
                searchZones.add(Rect(vp.mapContentX(0.020f), leftTopLimitY, vp.mapContentX(0.162f), leftBottomLimitY))
            }
        }

        detectGeometricXInZones(
            screenBitmap = screenBitmap,
            searchZones = searchZones,
            vp = vp,
            blacklistedCoord = blacklistedCoord,
            isRelaxed = isRelaxed,
            skipZone0BingoBox = true
        )
    }

    companion object {
        /**
         * 共用免樣本幾何 (X) 交叉輪廓偵測核心，同時服務全螢幕廣告角落偵測與 LINE 內部宣傳廣告右上角關閉定位。
         */
        fun detectGeometricXInZones(
            screenBitmap: Bitmap,
            searchZones: List<Rect>,
            vp: ScreenViewportCalibrator.ViewportMetrics = ScreenViewportCalibrator.detectViewport(
                screenBitmap.width,
                screenBitmap.height,
                screenBitmap
            ),
            blacklistedCoord: ((Int, Int) -> Boolean)? = null,
            isRelaxed: Boolean = false,
            skipZone0BingoBox: Boolean = true,
            scaleRatio: Float = 0.018f
        ): TemplateMatchResult? {
            val sw = screenBitmap.width
            val sh = screenBitmap.height
            val cw = vp.contentWidth
            val ch = vp.contentHeight
            val r = max(2, (cw * scaleRatio).toInt())
            val rHalf = max(1, (r * 0.65f).toInt())
            val step = max(1, cw / 240)
            val tol = max(1, cw / 360)

            var bestCandidate: TemplateMatchResult? = null
            var bestRawScore = if (isRelaxed) 58f else 82f
            val centerContrastMin = if (isRelaxed) 28f else 45f
            val contrastMin = if (isRelaxed) 40f else 55f
            val minArmContrastMin = if (isRelaxed) 20f else 35f

            for ((zoneIdx, zone) in searchZones.withIndex()) {
                val zw = zone.width()
                val zh = zone.height()
                if (zw <= r * 2 + 4 || zh <= r * 2 + 4) continue

                val pixels = IntArray(zw * zh)
                screenBitmap.getPixels(pixels, 0, zw, zone.left, zone.top, zw, zh)

                fun lum(x: Int, y: Int): Float {
                    val c = pixels[y * zw + x]
                    return 0.299f * ((c shr 16) and 0xFF) +
                        0.587f * ((c shr 8) and 0xFF) +
                        0.114f * (c and 0xFF)
                }

                fun armLum(px: Int, py: Int, brightX: Boolean): Float {
                    var best = if (brightX) 0f else 255f
                    for (dy in -tol..tol) {
                        val ny = (py + dy).coerceIn(0, zh - 1)
                        for (dx in -tol..tol) {
                            val nx = (px + dx).coerceIn(0, zw - 1)
                            val l = lum(nx, ny)
                            if (brightX && l > best) best = l
                            if (!brightX && l < best) best = l
                        }
                    }
                    return best
                }

                var cy = r + tol
                while (cy < zh - r - tol) {
                    var cx = r + tol
                    while (cx < zw - r - tol) {
                        val absX = zone.left + cx
                        val absY = zone.top + cy

                        if (zoneIdx == 0 && skipZone0BingoBox) {
                            if (absY - r < (sh * 0.08f).toInt()) {
                                cx += step
                                continue
                            }
                            val isBingoCloseZone = absX in (sw * 0.84f).toInt()..(sw * 0.98f).toInt() &&
                                absY in (sh * 0.08f).toInt()..(sh * 0.16f).toInt()
                            if (isBingoCloseZone) {
                                cx += step
                                continue
                            }
                        } else if (zone.left < sw / 2) {
                            if (absY - r < max(vp.mapContentY(0.014f), max(1, (ch * 0.007f).toInt()))) {
                                cx += step
                                continue
                            }
                        } else {
                            if (absY - r < max(vp.mapContentY(0.040f), max(1, (ch * 0.018f).toInt()))) {
                                cx += step
                                continue
                            }
                        }

                        if (blacklistedCoord?.invoke(absX, absY) != true) {
                            val lTop = lum(cx, cy - r)
                            val lBot = lum(cx, cy + r)
                            val lLeft = lum(cx - r, cy)
                            val lRight = lum(cx + r, cy)
                            val cardMean = (lTop + lBot + lLeft + lRight) * 0.25f
                            val cardDiff = abs(lTop - cardMean) + abs(lBot - cardMean) +
                                abs(lLeft - cardMean) + abs(lRight - cardMean)

                            if (cardDiff <= 58f) {
                                val isBrightX = cardMean < 150f

                                val centerPixelLum = lum(cx, cy)
                                val centerContrast = if (isBrightX) centerPixelLum - cardMean else cardMean - centerPixelLum
                                if (centerContrast < centerContrastMin) {
                                    cx += step
                                    continue
                                }

                                val vTop = lum(cx, cy - rHalf)
                                val vBot = lum(cx, cy + rHalf)
                                val vLeft = lum(cx - rHalf, cy)
                                val vRight = lum(cx + rHalf, cy)
                                val maxVGapDiff = max(
                                    max(abs(vTop - cardMean), abs(vBot - cardMean)),
                                    max(abs(vLeft - cardMean), abs(vRight - cardMean))
                                )
                                if (maxVGapDiff > 35f) {
                                    cx += step
                                    continue
                                }

                                val cLum = armLum(cx, cy, isBrightX)
                                val d1 = armLum(cx - rHalf, cy - rHalf, isBrightX)
                                val d2 = armLum(cx + rHalf, cy + rHalf, isBrightX)
                                val d3 = armLum(cx + rHalf, cy - rHalf, isBrightX)
                                val d4 = armLum(cx - rHalf, cy + rHalf, isBrightX)

                                val diagMean = (cLum + d1 + d2 + d3 + d4) * 0.2f
                                val contrast = if (isBrightX) diagMean - cardMean else cardMean - diagMean
                                val minArmContrast = if (isBrightX) {
                                    min(cLum, min(min(d1, d2), min(d3, d4))) - cardMean
                                } else {
                                    cardMean - max(cLum, max(max(d1, d2), max(d3, d4)))
                                }

                                if (contrast > contrastMin && minArmContrast > minArmContrastMin) {
                                    val rawScore = contrast - (cardDiff * 0.5f)
                                    if (rawScore > bestRawScore) {
                                        bestRawScore = rawScore
                                        bestCandidate = TemplateMatchResult(
                                            score = (rawScore / 150f).coerceIn(0.80f, 0.99f),
                                            bounds = Rect(absX - r, absY - r, absX + r, absY + r),
                                            label = if (zone.left < sw / 2) "左上角幾何X" else "右上角幾何X"
                                        )
                                    }
                                }
                            }
                        }
                        cx += step
                    }
                    cy += step
                }
            }
            return bestCandidate
        }
    }

    /**
     * 偵測全螢幕影音廣告 (如 Meta Audience Network, AppLovin 等) 右上角的『快轉/略過按鈕』(▶| 或 >>)。
     * 此類按鈕為深色圓形底圖內嵌高亮白色圖示，非純文字 OCR 所能辨識，亦無幾何 X 交叉特徵。
     * 本偵測器精準計算右上角區域 (x in 72%..96.5%, y in 1.5%..11.5%) 之圓環底色與內部圖示高對比度，
     * 能在廣告播放滿 4~5 秒略過鈕浮現時立即秒速點擊跳過廣告！
     */
    /**
     * 偵測全螢幕廣告右上角或左上角的快轉／略過按鈕 (`▶|`、`▶▶|` 等深色圓底白色圖示)。
     * 支援純黑邊／亮背景交界線跨界貼邊圓鈕，並排除與右側文字共箱之倒數膠囊 (`X 56 秒後可獲獎勵`)。
     */
    suspend fun detectCornerFastForwardSkip(
        screenBitmap: Bitmap,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null
    ): TemplateMatchResult? = withContext(Dispatchers.Default) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val vp = ScreenViewportCalibrator.detectViewport(sw, sh, screenBitmap)
        val cw = vp.contentWidth
        val ch = vp.contentHeight

        val rightTopY = max(vp.mapContentY(0.042f), max(2, (ch * 0.019f).toInt()))
        val rightBottomY = kotlin.math.min(sh - 1, max(vp.mapContentY(0.135f), (sh * 0.135f).toInt()))
        val rightMatch = scanCornerSkipZone(
            screenBitmap = screenBitmap,
            sw = sw,
            sh = sh,
            cw = cw,
            leftLimitX = vp.mapContentX(0.72f),
            rightLimitX = vp.mapContentX(0.965f),
            topLimitY = rightTopY,
            bottomLimitY = rightBottomY,
            label = "右上角略過鈕(▶|)",
            blacklistedCoord = blacklistedCoord
        )
        if (rightMatch != null) return@withContext rightMatch

        val leftTopY = max(2, kotlin.math.min(vp.mapContentY(0.038f), (sh * 0.035f).toInt()))
        val leftBottomY = kotlin.math.min(sh - 1, max(vp.mapContentY(0.140f), (sh * 0.140f).toInt()))
        val leftStartX = if (vp.effectiveLeftX == 0 && vp.effectiveRightX == sw) {
            (sw * 0.025f).toInt()
        } else {
            vp.mapContentX(0.025f)
        }
        val leftEndX = if (vp.effectiveLeftX == 0 && vp.effectiveRightX == sw) {
            (sw * 0.220f).toInt()
        } else {
            vp.mapContentX(0.220f)
        }
        scanCornerSkipZone(
            screenBitmap = screenBitmap,
            sw = sw,
            sh = sh,
            cw = cw,
            leftLimitX = leftStartX,
            rightLimitX = leftEndX,
            topLimitY = leftTopY,
            bottomLimitY = leftBottomY,
            label = "左上角略過鈕(▶|)",
            blacklistedCoord = blacklistedCoord
        )
    }

    private fun scanCornerSkipZone(
        screenBitmap: Bitmap,
        sw: Int,
        sh: Int,
        cw: Int,
        leftLimitX: Int,
        rightLimitX: Int,
        topLimitY: Int,
        bottomLimitY: Int,
        label: String,
        blacklistedCoord: ((Int, Int) -> Boolean)?
    ): TemplateMatchResult? {
        val rInner = max(2, (cw * 0.024f).toInt())
        val rCore = max(3, (cw * 0.017f).toInt())
        val rRing = max(5, (cw * 0.025f).toInt())
        val rOuter = max(4, (cw * 0.042f).toInt())
        val step = max(1, cw / 200)

        val zw = rightLimitX - leftLimitX
        val zh = bottomLimitY - topLimitY
        if (zw <= rOuter * 2 || zh <= rOuter * 2) return null

        val pixels = IntArray(zw * zh)
        screenBitmap.getPixels(pixels, 0, zw, leftLimitX, topLimitY, zw, zh)

        fun pixelAt(x: Int, y: Int): Int = pixels[y * zw + x]
        fun lumColor(c: Int): Float =
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        fun isMonochromeWhite(c: Int): Boolean {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return max(r, max(g, b)) - kotlin.math.min(r, kotlin.math.min(g, b)) <= 45
        }

        // 預先準備水平右延展膠囊過濾條（跨越 zone 右界時可直接讀取 screenBitmap）
        fun hasCapsuleExtension(absX: Int, absY: Int): Boolean {
            val dx0 = max(rRing + 2, (cw * 0.036f).toInt())
            val dx1 = kotlin.math.min(sw - 1 - absX, (cw * 0.160f).toInt())
            val extStep = max(1, cw / 180)
            var extDark = 0
            var extBright = 0
            if (dx1 > dx0) {
                var dy = -rCore / 2
                while (dy <= rCore / 2) {
                    val py = (absY + dy).coerceIn(0, sh - 1)
                    var dx = dx0
                    while (dx <= dx1) {
                        val l = lumColor(screenBitmap.getPixel((absX + dx).coerceIn(0, sw - 1), py))
                        if (l > 170f) extBright++ else if (l < 125f) extDark++
                        if (extBright >= 4 && extDark >= 6) return true
                        dx += extStep
                    }
                    dy += extStep
                }
            }
            return false
        }

        fun isCrossXGlyph(absX: Int, absY: Int): Boolean {
            // 沿圓內亮白筆畫進行有界 8 連通 BFS，於 rInner 圓內播種並擴展至完整筆畫邊界，
            // 既可避免候選中心偏離 X 中心或落在臂間暗隙時漏偵，亦不會抓到圓框外亮背景
            val maxR = max(rInner + 8, (cw * 0.042f).toInt())
            val side = maxR * 2 + 1
            val visited = BooleanArray(side * side)
            val maxQueue = side * side
            val qx = IntArray(maxQueue)
            val qy = IntArray(maxQueue)
            var head = 0
            var tail = 0

            val rSeed = rInner
            val rSeed2 = rSeed * rSeed
            for (dy in -rSeed..rSeed) {
                val py = absY + dy
                if (py !in 0 until sh) continue
                for (dx in -rSeed..rSeed) {
                    if (dx * dx + dy * dy > rSeed2) continue
                    val px = absX + dx
                    if (px !in 0 until sw) continue
                    if (abs(dx) <= maxR && abs(dy) <= maxR && lumColor(screenBitmap.getPixel(px, py)) > 155f) {
                        val idx = (dy + maxR) * side + (dx + maxR)
                        if (!visited[idx]) {
                            visited[idx] = true
                            qx[tail] = px
                            qy[tail] = py
                            tail++
                        }
                    }
                }
            }
            if (tail == 0) return false

            var minx = sw
            var miny = sh
            var maxx = -1
            var maxy = -1
            var cnt = 0
            val maxPixels = max(60, maxR * maxR)

            while (head < tail) {
                val x = qx[head]
                val y = qy[head]
                head++
                if (x < minx) minx = x
                if (x > maxx) maxx = x
                if (y < miny) miny = y
                if (y > maxy) maxy = y
                cnt++
                if (cnt > maxPixels) return false

                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until sh || abs(ny - absY) > maxR) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx !in 0 until sw || abs(nx - absX) > maxR) continue
                        val idx = (ny - absY + maxR) * side + (nx - absX + maxR)
                        if (!visited[idx] && lumColor(screenBitmap.getPixel(nx, ny)) > 155f) {
                            visited[idx] = true
                            qx[tail] = nx
                            qy[tail] = ny
                            tail++
                        }
                    }
                }
            }
            if (cnt < 6 || maxx <= minx || maxy <= miny) return false
            val hw = max(3, (maxx - minx) / 2)
            val hh = max(3, (maxy - miny) / 2)
            if (hw > maxR - 2 || hh > maxR - 2) return false
            if (abs(hw - hh) > max(3, hw / 3)) return false
            val mx = (minx + maxx) / 2
            val my = (miny + maxy) / 2
            val ax = max(2, (hw * 0.68f).toInt())
            val ay = max(2, (hh * 0.68f).toInt())
            val ddx = max(2, (hw * 0.55f).toInt())
            val ddy = max(2, (hh * 0.55f).toInt())
            fun lumSafe(x: Int, y: Int): Float =
                lumColor(screenBitmap.getPixel(x.coerceIn(0, sw - 1), y.coerceIn(0, sh - 1)))
            val axialMax = max(
                max(lumSafe(mx - ax, my), lumSafe(mx + ax, my)),
                max(lumSafe(mx, my - ay), lumSafe(mx, my + ay))
            )
            val diagMin = kotlin.math.min(
                kotlin.math.min(lumSafe(mx - ddx, my - ddy), lumSafe(mx + ddx, my - ddy)),
                kotlin.math.min(lumSafe(mx - ddx, my + ddy), lumSafe(mx + ddx, my + ddy))
            )
            return axialMax < 140f && diagMin > 150f
        }

        val topAngles = doubleArrayOf(
            -Math.PI * 0.85,
            -Math.PI * 0.65,
            -Math.PI * 0.50,
            -Math.PI * 0.35,
            -Math.PI * 0.15
        )

        var bestScore = 0f
        var bestCandidate: TemplateMatchResult? = null
        val coreStep = max(1, rCore / 6)

        var cy = rRing
        while (cy <= zh - rRing) {
            var cx = rRing
            while (cx <= zw - rRing) {
                val absX = leftLimitX + cx
                val absY = topLimitY + cy

                if (blacklistedCoord?.invoke(absX, absY) != true) {
                    // 檢查 1：傳統 360 度對稱深色圓框 (rOuter)
                    val ringSamples = FloatArray(12)
                    var ringSum = 0f
                    var ringMax = 0f
                    var ringMin = 255f
                    for (i in 0 until 12) {
                        val angle = i * (Math.PI / 6.0)
                        val px = (cx + rOuter * kotlin.math.cos(angle)).toInt().coerceIn(0, zw - 1)
                        val py = (cy + rOuter * kotlin.math.sin(angle)).toInt().coerceIn(0, zh - 1)
                        val l = lumColor(pixelAt(px, py))
                        ringSamples[i] = l
                        ringSum += l
                        if (l > ringMax) ringMax = l
                        if (l < ringMin) ringMin = l
                    }
                    val ringMean = ringSum / 12f
                    val ringDiff = ringMax - ringMin
                    var maxSymDiff = 0f
                    for (i in 0 until 6) {
                        val d = abs(ringSamples[i] - ringSamples[i + 6])
                        if (d > maxSymDiff) maxSymDiff = d
                    }
                    val horizMean = (ringSamples[0] + ringSamples[6]) / 2f
                    val vertMean = (ringSamples[3] + ringSamples[9]) / 2f
                    val symOk = ringDiff < 35f && maxSymDiff < 30f && abs(horizMean - vertMean) < 20f &&
                        ringMean < 115f && ringMax < 135f

                    if (symOk) {
                        val centerLum = lumColor(pixelAt(cx, cy))
                        var innerMax = 0f
                        var dy = -rInner
                        while (dy <= rInner) {
                            var dx = -rInner
                            while (dx <= rInner) {
                                if (dx * dx + dy * dy <= rInner * rInner) {
                                    val l = lumColor(pixelAt((cx + dx).coerceIn(0, zw - 1), (cy + dy).coerceIn(0, zh - 1)))
                                    if (l > innerMax) innerMax = l
                                }
                                dx += 2
                            }
                            dy += 2
                        }
                        if ((centerLum - ringMean > 35f || centerLum > 150f) && innerMax > 175f) {
                            val contrast = innerMax - ringMean
                            if (contrast > 85f && contrast > bestScore && !hasCapsuleExtension(absX, absY) && !isCrossXGlyph(absX, absY)) {
                                bestScore = contrast
                                val boxRadius = rOuter + max(2, (cw * 0.004f).toInt())
                                bestCandidate = TemplateMatchResult(
                                    score = (contrast / 180f).coerceIn(0.85f, 0.99f),
                                    bounds = Rect(absX - boxRadius, absY - boxRadius, absX + boxRadius, absY + boxRadius),
                                    label = label
                                )
                            }
                        }
                    } else {
                        // 檢查 2：黑邊／亮背景交界線跨界深色半透明圓鈕 + 單色純白圖示 (`▶|`)
                        var topRimSum = 0f
                        var topRimMax = 0f
                        for (a in topAngles) {
                            val px = (cx + rRing * kotlin.math.cos(a)).toInt().coerceIn(0, zw - 1)
                            val py = (cy + rRing * kotlin.math.sin(a)).toInt().coerceIn(0, zh - 1)
                            val l = lumColor(pixelAt(px, py))
                            topRimSum += l
                            if (l > topRimMax) topRimMax = l
                        }
                        val rLeft = lumColor(pixelAt((cx - rRing).coerceIn(0, zw - 1), cy))
                        val rRight = lumColor(pixelAt((cx + rRing).coerceIn(0, zw - 1), cy))

                        if (topRimMax <= 135f && rLeft <= 140f && rRight <= 140f) {
                            var spill = false
                            var off = max(1, (rCore * 0.85f).toInt())
                            while (off <= rRing) {
                                if (lumColor(pixelAt((cx - off).coerceIn(0, zw - 1), cy)) > 155f ||
                                    lumColor(pixelAt((cx + off).coerceIn(0, zw - 1), cy)) > 155f
                                ) {
                                    spill = true
                                    break
                                }
                                off += coreStep
                            }
                            if (!spill) {
                                val shelfY = (cy + (rCore * 0.75f).toInt()).coerceIn(0, zh - 1)
                                var shelfMin = 255f
                                var shelfSum = 0f
                                var shelfCnt = 0
                                var sdx = -rCore / 2
                                while (sdx <= rCore / 2) {
                                    val l = lumColor(pixelAt((cx + sdx).coerceIn(0, zw - 1), shelfY))
                                    shelfSum += l
                                    if (l < shelfMin) shelfMin = l
                                    shelfCnt++
                                    sdx += coreStep
                                }
                                val shelfMean = if (shelfCnt > 0) shelfSum / shelfCnt else 255f
                                if (shelfMin <= 135f && shelfMean <= 165f) {
                                    var inMax = 0f
                                    var inMin = 255f
                                    var brightCnt = 0
                                    var darkCnt = 0
                                    var midBrightCnt = 0
                                    var topBrightCnt = 0
                                    var botBrightCnt = 0
                                    var total = 0
                                    var monoOk = true
                                    var dy = -rCore
                                    while (dy <= rCore && monoOk) {
                                        var dx = -rCore
                                        while (dx <= rCore) {
                                            if (dx * dx + dy * dy <= rCore * rCore) {
                                                val c = pixelAt((cx + dx).coerceIn(0, zw - 1), (cy + dy).coerceIn(0, zh - 1))
                                                val l = lumColor(c)
                                                total++
                                                if (l > inMax) inMax = l
                                                if (l < inMin) inMin = l
                                                if (l > 180f) {
                                                    brightCnt++
                                                    if (abs(dy) <= rCore * 0.45f && abs(dx) <= rCore * 0.75f) midBrightCnt++
                                                    if (dy < 0) topBrightCnt++
                                                    if (dy > 0) botBrightCnt++
                                                    if (!isMonochromeWhite(c)) {
                                                        monoOk = false
                                                        break
                                                    }
                                                } else if (l < 135f) {
                                                    darkCnt++
                                                }
                                            }
                                            dx += coreStep
                                        }
                                        dy += coreStep
                                    }
                                    if (monoOk && total > 0) {
                                        val brightRatio = brightCnt.toFloat() / total
                                        val darkRatio = darkCnt.toFloat() / total
                                        if (inMax >= 190f && inMin <= 120f &&
                                            brightRatio in 0.06f..0.42f && darkRatio >= 0.42f &&
                                            midBrightCnt >= 3 && topBrightCnt >= 1 && botBrightCnt >= 1 &&
                                            !hasCapsuleExtension(absX, absY) && !isCrossXGlyph(absX, absY)
                                        ) {
                                            val avgRim = ((topRimSum / topAngles.size) + rLeft + rRight) / 3f
                                            val score = inMax - avgRim
                                            if (score > bestScore) {
                                                bestScore = score
                                                val boxRadius = rRing + max(4, (cw * 0.008f).toInt())
                                                bestCandidate = TemplateMatchResult(
                                                    score = (score / 210f).coerceIn(0.86f, 0.99f),
                                                    bounds = Rect(absX - boxRadius, absY - boxRadius, absX + boxRadius, absY + boxRadius),
                                                    label = label
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                cx += step
            }
            cy += step
        }
        return bestCandidate
    }

}
