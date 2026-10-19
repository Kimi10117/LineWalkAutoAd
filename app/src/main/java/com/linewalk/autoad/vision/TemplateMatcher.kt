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

/**
 * 第三層辨識：
 * 1. 內建《LINE 散步趣》專屬珊瑚粉紅按鈕偵測器 (findCoralPinkButton)，
 *    可在 1 毫秒內精準定位主畫面的『獲得票券』(y=25%~37%) 與彈窗中的『紅利 [▶] 15 獲得』(y=68%~83%)。
 * 2. 雙階段粗精搜 ZNCC + RGB 局部模板匹配器。
 * 3. 免樣本幾何角落 X 偵測器。
 */
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
                // LINE 散步趣粉紅按鈕色彩特徵: R > 215, G 介於 35~125, B 介於 45~135, 且 R-G > 110
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

        // 確認粉紅區塊具有足夠的按鈕高度 (至少 3 個取樣列)
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

    suspend fun detectCornerGeometricX(
        screenBitmap: Bitmap,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null
    ): TemplateMatchResult? = withContext(Dispatchers.Default) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val boxSize = (min(sw, sh) * 0.048f).toInt().coerceIn(36, 72)
        val half = boxSize / 2

        val searchZones = listOf(
            Rect((sw * 0.82f).toInt(), (sh * 0.025f).toInt(), (sw * 0.985f).toInt(), (sh * 0.14f).toInt()),
            Rect((sw * 0.015f).toInt(), (sh * 0.025f).toInt(), (sw * 0.18f).toInt(), (sh * 0.14f).toInt())
        )

        var bestCandidate: TemplateMatchResult? = null

        for ((zoneIdx, zone) in searchZones.withIndex()) {
            val zw = zone.width()
            val zh = zone.height()
            if (zw <= boxSize || zh <= boxSize) continue

            val pixels = IntArray(zw * zh)
            screenBitmap.getPixels(pixels, 0, zw, zone.left, zone.top, zw, zh)

            fun lum(x: Int, y: Int): Float {
                val c = pixels[y * zw + x]
                return 0.299f * ((c shr 16) and 0xFF) +
                    0.587f * ((c shr 8) and 0xFF) +
                    0.114f * (c and 0xFF)
            }

            var cy = half
            while (cy < zh - half) {
                var cx = half
                while (cx < zw - half) {
                    val absX = zone.left + cx
                    val absY = zone.top + cy
                    if (blacklistedCoord?.invoke(absX, absY) != true) {
                        val centerLum = lum(cx, cy)
                        val r = (half * 0.55f).toInt().coerceAtLeast(6)

                        val diag1 = (lum(cx - r, cy - r) + lum(cx + r, cy + r) +
                            lum(cx - r / 2, cy - r / 2) + lum(cx + r / 2, cy + r / 2)) / 4f
                        val diag2 = (lum(cx + r, cy - r) + lum(cx - r, cy + r) +
                            lum(cx + r / 2, cy - r / 2) + lum(cx - r / 2, cy + r / 2)) / 4f
                        val cardinal = (lum(cx, cy - r) + lum(cx, cy + r) +
                            lum(cx - r, cy) + lum(cx + r, cy)) / 4f

                        val diagSymmetry = 1f - (abs(diag1 - diag2) / 255f)
                        val centerConsistency = 1f - (abs(centerLum - (diag1 + diag2) * 0.5f) / 255f)
                        val contrast = abs((diag1 + diag2) * 0.5f - cardinal)

                        if (contrast > 48f && diagSymmetry > 0.88f && centerConsistency > 0.84f) {
                            val score = (0.4f * (contrast / 160f).coerceAtMost(1f)) +
                                0.3f * diagSymmetry + 0.3f * centerConsistency
                            if (score >= 0.78f && (bestCandidate == null || score > bestCandidate.score)) {
                                bestCandidate = TemplateMatchResult(
                                    score = score,
                                    bounds = Rect(absX - half, absY - half, absX + half, absY + half),
                                    label = if (zoneIdx == 0) "右上角幾何X" else "左上角幾何X"
                                )
                            }
                        }
                    }
                    cx += 4
                }
                cy += 4
            }
        }
        bestCandidate
    }
}
