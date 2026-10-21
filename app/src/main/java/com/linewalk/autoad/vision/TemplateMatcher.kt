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
        val startY = (h * yStartRatio).toInt().coerceIn(0, h - 1)
        val endY = (h * yEndRatio).toInt().coerceIn(startY + 1, h - 1)
        val startX = (w * 0.20f).toInt()
        val endX = (w * 0.80f).toInt()

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
                x += 8
            }
            if (totalSampled > 0 && (pinkPixels.toFloat() / totalSampled) >= 0.35f) {
                if (y < minPinkY) minPinkY = y
                if (y > maxPinkY) maxPinkY = y
                matchedRowCount++
            }
            y += 4
        }

        if (matchedRowCount >= 3 && maxPinkY > minPinkY) {
            val rect = Rect(
                (w * 0.25f).toInt(),
                minPinkY,
                (w * 0.75f).toInt(),
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
     * 判斷彈窗內某個按鈕高度 (centerY) 是否為「反灰不可點選」狀態（如無糖果時的灰色『跳過廣告』按鈕 #CCCCCC ~ #D2D2D2）。
     * - 反灰按鈕的灰底比例 (r,g,b in 180..225 且無色彩飽和度) 高達 80% 以上。
     * - 可點選按鈕（如有糖果時的『跳過廣告』或白底黑框的『紅利 [▶] x1 獲得』或粉紅鈕）灰底比例低於 10%。
     */
    fun isPopupButtonDisabledGray(screenBitmap: Bitmap, centerY: Int): Boolean {
        val w = screenBitmap.width
        val h = screenBitmap.height
        val yStart = (centerY - (h * 0.018f).toInt()).coerceIn(0, h - 1)
        val yEnd = (centerY + (h * 0.018f).toInt()).coerceIn(yStart + 1, h - 1)
        val xStart = (w * 0.15f).toInt()
        val xEnd = (w * 0.85f).toInt()

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
                // 判斷是否為無彩度反灰像素（涵蓋淺灰 180..235 與彈窗暗底灰 60..180）
                val maxDiff = maxOf(abs(r - g), abs(g - b), abs(r - b))
                if (r in 60..235 && maxDiff <= 15) {
                    grayPixels++
                }
                totalSampled++
                x += 6
            }
            y += 3
        }
        return totalSampled > 0 && (grayPixels.toFloat() / totalSampled) >= 0.40f
    }

    /**
     * 偵測《LINE 散步趣》彈窗左側的珊瑚粉紅『紅利』小標籤 (x = 0.07f..0.30f, y = 0.64f..0.86f)：
     * 特別針對『時間(放置)』分頁的收成彈窗：其『紅利 [▶] x 1 獲得』按鈕本體為白底黑框，但左上角固定有珊瑚粉紅色的『紅利』標籤！
     * 定位到『紅利』粉紅標籤後，直接點擊其正下方的看廣告按鈕區域。
     */
    fun findPopupBonusBadgeAdButton(screenBitmap: Bitmap): TemplateMatchResult? {
        val w = screenBitmap.width
        val h = screenBitmap.height
        val startY = (h * 0.64f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.86f).toInt().coerceIn(startY + 1, h - 1)
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
            if (!isPopupButtonDisabledGray(screenBitmap, targetCenterY)) {
                val rect = Rect(
                    (w * 0.25f).toInt(),
                    targetCenterY - 22,
                    (w * 0.75f).toInt(),
                    targetCenterY + 22
                )
                return TemplateMatchResult(
                    score = 0.96f,
                    bounds = rect,
                    label = "紅利[x1獲得]看廣告鈕"
                )
            }
        }
        return null
    }

    /**
     * 辨識《LINE 散步趣》主畫面頂部目前選取中的主要分頁 (0~3)：
     * - 0: 首頁 (移動, xRatio = 0.125f)
     * - 1: 步數 (xRatio = 0.375f)
     * - 2: 時間 (放置, xRatio = 0.625f)
     * - 3: 遊戲 (xRatio = 0.875f)
     * 1. 優先透過當前選取分頁底部的黑色粗實線指示條 (y ≈ 0.138f..0.152f, 佔分頁寬度 25%) 進行 100% 準確判斷。
     * 2. 次要透過文字深色粗體 (darkRatio >= 0.10f) 進行備援判斷。
     */
    fun detectActiveMainTabIndex(screenBitmap: Bitmap): Int? {
        val w = screenBitmap.width
        val h = screenBitmap.height

        // 1. 最優先且最精準：LINE 散步趣頂部分頁底部的黑色粗實線選取指示條 (y ≈ 0.138f..0.152f)
        val yBarStart = (h * 0.138f).toInt().coerceIn(0, h - 1)
        val yBarEnd = (h * 0.152f).toInt().coerceIn(yBarStart + 1, h - 1)
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

            val minPointsThreshold = (quarterW / 2 * 0.35f).toInt().coerceAtLeast(8)
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
     * 支援細線條 X（含 3x3 鄰域容錯）與深色圓底白 X / 淺色圓底黑 X，
     * 掃描畫面右上角 (x=75%~98%, y=2%~18%) 與左上角 (x=2%~25%, y=2%~18%)。
     */
    suspend fun detectCornerGeometricX(
        screenBitmap: Bitmap,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null,
        isRelaxed: Boolean = false
    ): TemplateMatchResult? = withContext(Dispatchers.Default) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val r = max(5, (sw * 0.018f).toInt())
        val rHalf = max(3, (r * 0.65f).toInt())
        val step = max(2, sw / 240)
        val tol = max(1, sw / 360)

        val topLimitY = max((sh * 0.052f).toInt(), 48)
        val searchZones = listOf(
            // 右上角 (最常見，涵蓋螢幕頂部系統列下方與 16:9 劇院黑邊下方區域 y in 5.2%..24%)
            Rect((sw * 0.75f).toInt(), topLimitY, (sw * 0.98f).toInt(), (sh * 0.24f).toInt()),
            // 左上角 (廣告左上角關閉鈕通常緊貼左上 x < 18%, y in 5.2%..24%)
            Rect((sw * 0.02f).toInt(), topLimitY, (sw * 0.18f).toInt(), (sh * 0.24f).toInt())
        )

        var bestCandidate: TemplateMatchResult? = null
        var bestRawScore = if (isRelaxed) 65f else 82f
        val centerContrastMin = if (isRelaxed) 35f else 45f
        val contrastMin = if (isRelaxed) 45f else 55f
        val minArmContrastMin = if (isRelaxed) 22f else 35f

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
                    if (blacklistedCoord?.invoke(absX, absY) != true) {
                        // 上下左右四個非對角扇區（圓形按鈕底色）必須亮度一致
                        val lTop = lum(cx, cy - r)
                        val lBot = lum(cx, cy + r)
                        val lLeft = lum(cx - r, cy)
                        val lRight = lum(cx + r, cy)
                        val cardMean = (lTop + lBot + lLeft + lRight) * 0.25f
                        val cardDiff = abs(lTop - cardMean) + abs(lBot - cardMean) +
                            abs(lLeft - cardMean) + abs(lRight - cardMean)

                        if (cardDiff <= 58f) {
                            val isBrightX = cardMean < 150f

                            // 1. 中心點本身必須位於 X 的交叉線上，排除『ⓘ ▷』兩個圖示中間的黑縫！
                            val centerPixelLum = lum(cx, cy)
                            val centerContrast = if (isBrightX) centerPixelLum - cardMean else cardMean - centerPixelLum
                            if (centerContrast < centerContrastMin) {
                                cx += step
                                continue
                            }

                            // 2. 檢查半徑 rHalf 的上下左右四個 V 型缺口：
                            // 真正的『X』只有四條對角線，其正上、正下、正左、正右 (rHalf 處) 必須是背景底色！
                            // 這能 100% 排除帶有垂直線或水平線的 AdChoices『ⓘ / ▷』圖示與喇叭圖示！
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
                                        label = if (zoneIdx == 0) "右上角幾何X" else "左上角幾何X"
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
        bestCandidate
    }
}
