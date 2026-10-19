package com.linewalk.autoad.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
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
                if (r > 215 && g in 35..125 && b in 45..135 && (r - g) > 110) {
                    pinkPixels++
                }
                totalSampled++
                x += 8
            }
            if (totalSampled > 0 && (pinkPixels.toFloat() / totalSampled) >= 0.45f) {
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
     * 偵測《LINE 散步趣》頂部分頁列 (y = 0.115f..0.123f) 目前停留在哪一個分頁：
     * - 0: 首頁 (移動, xRatio = 0.125f)
     * - 1: 步數 (xRatio = 0.375f)
     * - 2: 時間 (放置, xRatio = 0.625f)
     * - 3: 遊戲 (xRatio = 0.875f)
     * 選取中的分頁在該區段具有實心深色圖示與粗體字 (darkRatio >= 0.08f)，未選取分頁則為 0.0f。
     */
    fun detectActiveMainTabIndex(screenBitmap: Bitmap): Int? {
        val w = screenBitmap.width
        val h = screenBitmap.height
        val yStart = (h * 0.115f).toInt().coerceIn(0, h - 1)
        val yEnd = (h * 0.123f).toInt().coerceIn(yStart + 1, h - 1)
        val tabCenters = floatArrayOf(0.125f, 0.375f, 0.625f, 0.875f)

        var bestIndex = -1
        var bestRatio = 0f

        for (idx in tabCenters.indices) {
            val cx = tabCenters[idx]
            val xStart = (w * (cx - 0.045f)).toInt().coerceIn(0, w - 1)
            val xEnd = (w * (cx + 0.045f)).toInt().coerceIn(xStart + 1, w - 1)
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

        return if (bestIndex >= 0 && bestRatio >= 0.08f) bestIndex else null
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
     * 掃描畫面右上角 (x=83%~97%, y=3.5%~14.5%) 與左上角 (x=3%~17%, y=3.5%~14.5%)。
     */
    suspend fun detectCornerGeometricX(
        screenBitmap: Bitmap,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null
    ): TemplateMatchResult? = withContext(Dispatchers.Default) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val r = max(5, (sw * 0.018f).toInt())
        val rHalf = max(3, (r * 0.65f).toInt())
        val step = max(2, sw / 240)
        val tol = max(1, sw / 360)

        val searchZones = listOf(
            // 右上角 (最常見，如『邀請好友』右上角灰底圓形 X)
            Rect((sw * 0.83f).toInt(), (sh * 0.035f).toInt(), (sw * 0.97f).toInt(), (sh * 0.145f).toInt()),
            // 左上角
            Rect((sw * 0.03f).toInt(), (sh * 0.035f).toInt(), (sw * 0.17f).toInt(), (sh * 0.145f).toInt())
        )

        var bestCandidate: TemplateMatchResult? = null
        var bestRawScore = 82f

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

                            if (contrast > 55f && minArmContrast > 35f) {
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
