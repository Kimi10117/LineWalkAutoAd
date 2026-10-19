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
 * 第三層辨識：高效能純 Kotlin 雙層金字塔局部圖像匹配器 (TemplateMatcher)
 * 結合「零均值正規化互相關 (ZNCC)」與「RGB 色彩相似度」，
 * 能夠精準區分《LINE 散步趣》中「已集滿發亮的水瓶」與「未集滿的灰暗水瓶」，
 * 同時提供「免樣本幾何角落 X 偵測器」作為廣告關閉按鈕的備援。
 */
class TemplateMatcher {

    /**
     * 在螢幕截圖的指定相對區域 (Normalized ROI) 內，從多張範本中找出最高分的匹配位置。
     */
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

    /**
     * 雙階段粗搜 + 精搜模板比對 (Coarse-to-Fine ZNCC + RGB Similarity)：
     * 1. 先以步長 step=3 進行快速網格掃描找出前幾名候選點。
     * 2. 再於候選點周圍 +-3 px 進行逐像素精確定位。
     */
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

        // 為了加速計算，在模板上均勻抽樣網格點 (最多約 15x15 = 225 個取樣點)
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
            // 綜合結構相似度 (ZNCC 65%) 與色彩相似度 (RGB 35%)
            return 0.65f * zncc + 0.35f * colorSim
        }

        // 第一階段：粗略掃描 (步長 3px)
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

        // 第二階段：局部逐像素精搜
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
     * 免樣本幾何角落「X」偵測器：
     * 當廣告的關閉按鈕是純圖形且不在無障礙節點樹、也沒有 OCR 文字時，
     * 掃描畫面右上角與左上角是否具有典型的「圓底/方底對角線 X 圖示」特徵。
     */
    suspend fun detectCornerGeometricX(
        screenBitmap: Bitmap,
        blacklistedCoord: ((Int, Int) -> Boolean)? = null
    ): TemplateMatchResult? = withContext(Dispatchers.Default) {
        val sw = screenBitmap.width
        val sh = screenBitmap.height
        val boxSize = (min(sw, sh) * 0.048f).toInt().coerceIn(36, 72)
        val half = boxSize / 2

        // 定義右上角與左上角兩個常見的廣告 X 熱區
        val searchZones = listOf(
            // 右上角 (優先)
            Rect((sw * 0.82f).toInt(), (sh * 0.025f).toInt(), (sw * 0.985f).toInt(), (sh * 0.14f).toInt()),
            // 左上角
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
                        // X 圖示特徵：中心與兩條對角線亮度相近，且與上下左右四個非對角象限有明顯對比度，且兩條對角線對稱
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
