package com.linewalk.autoad.vision

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 全機型螢幕比例與導覽列自適應校準器 (ScreenViewportCalibrator)：
 * - 支援 22:9 / 21:9 (Flip/Fold 摺疊機)、20:9 / 19.5:9 (主流全螢幕)、16:9 (傳統比例) 與展開大螢幕
 * - 自動偵測底部是否啟用「傳統三大金剛按鍵 (返回/首頁/多工)」系統導覽列，並提供安全禁區防護與有效高度換算
 * - 提供像素級「子視窗白底圓角面板頂部邊界 (sheetTopY) 與右上角 (X) 筆畫中心」精準定位
 */
object ScreenViewportCalibrator {

    data class ViewportMetrics(
        val screenWidth: Int,
        val screenHeight: Int,
        val effectiveTopY: Int,
        val effectiveBottomY: Int,
        val hasThreeButtonNavBar: Boolean,
        val effectiveLeftX: Int = 0,
        val effectiveRightX: Int = screenWidth
    ) {
        val contentWidth: Int
            get() = max(1, effectiveRightX - effectiveLeftX)

        val contentHeight: Int
            get() = max(1, effectiveBottomY - effectiveTopY)

        /** 將基準寬度比例 (0f..1f) 映射至扣除左右黑邊後的實際遊戲畫布 X 座標 */
        fun mapContentX(ratio: Float): Int {
            if (screenWidth <= 0) return 0
            return (effectiveLeftX + contentWidth * ratio).toInt().coerceIn(0, screenWidth - 1)
        }

        /** 將基準高度比例 (0f..1f) 映射至扣除頂部安全區與底部導覽列後的實際遊戲畫布 Y 座標 */
        fun mapContentY(ratio: Float): Int {
            if (screenHeight <= 0) return 0
            return (effectiveTopY + contentHeight * ratio).toInt().coerceIn(0, screenHeight - 1)
        }
    }

    /**
     * 動態偵測畫面底部是否存在「傳統三大金剛按鍵」系統導覽列 (通常佔據底部 4.5% ~ 8.5% 螢幕高度)：
     * 1. 若畫面底部導覽列文字（集點 / 好友 / 使用 / 精選）的中心 Y 明顯上移至 0.82f..0.905f，代表下方存在三大金剛按鍵列。
     * 2. 若提供 screenBitmap，掃描底部 y in 0.92f..0.995f 是否為均勻純色系統導覽列（且上方存在明顯水平分界線）。
     */
    fun detectViewport(
        sw: Int,
        sh: Int,
        screenBitmap: Bitmap? = null,
        ocrItems: List<OcrItem> = emptyList()
    ): ViewportMetrics {
        if (sw <= 0 || sh <= 0) {
            return ViewportMetrics(sw, sh, 0, sh, false, 0, max(0, sw))
        }

        val (effectiveLeftX, effectiveRightX) = scanHorizontalLetterboxBounds(screenBitmap, sw, sh)
        val effectiveTopY = scanEffectiveTopY(screenBitmap, sw, sh, effectiveLeftX, effectiveRightX)
        val detectedContentWidth = max(1, effectiveRightX - effectiveLeftX)

        // 1. 優先根據底部導覽列四大標籤 (集點/好友/使用/精選) 的實際 OCR 座標推算真實遊戲底邊界
        val bottomNavItem = ocrItems.firstOrNull { item ->
            val cyRatio = item.bounds.centerY().toFloat() / sh
            val t = item.text.replace(" ", "")
            cyRatio in 0.80f..0.97f && (
                t == "集點" || t == "精選" ||
                (t == "好友" && item.bounds.width() < detectedContentWidth * 0.24f) ||
                (t == "使用" && item.bounds.width() < detectedContentWidth * 0.24f)
            )
        }
        if (bottomNavItem != null) {
            val navBottom = (bottomNavItem.bounds.bottom + sh * 0.018f).toInt().coerceIn((sh * 0.85f).toInt(), sh)
            val hasThreeBtn = bottomNavItem.bounds.centerY() < (sh * 0.908f).toInt()
            val effectiveBot = if (hasThreeBtn) min(sh, navBottom) else sh
            return ViewportMetrics(
                sw,
                sh,
                effectiveTopY,
                effectiveBot,
                hasThreeBtn,
                effectiveLeftX,
                effectiveRightX
            )
        }

        // 2. 像素級底部三大金剛導覽列探測 (針對無底部標籤的子視窗或廣告畫面)
        if (screenBitmap != null && screenBitmap.width >= 200 && screenBitmap.height >= 400) {
            val checkStartY = (sh * 0.985f).toInt().coerceIn(0, sh - 1)
            val checkEndY = (sh * 0.905f).toInt().coerceIn(0, checkStartY)
            val sampleXs = intArrayOf(
                (sw * 0.12f).toInt(),
                (sw * 0.35f).toInt(),
                (sw * 0.50f).toInt(),
                (sw * 0.65f).toInt(),
                (sw * 0.88f).toInt()
            )

            fun rowMeanAndSpread(y: Int): Pair<Float, Float> {
                var sum = 0f
                var minL = 255f
                var maxL = 0f
                for (x in sampleXs) {
                    val c = screenBitmap.getPixel(x.coerceIn(0, sw - 1), y)
                    val l = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                    sum += l
                    if (l < minL) minL = l
                    if (l > maxL) maxL = l
                }
                return Pair(sum / sampleXs.size, maxL - minL)
            }

            val (baseLum, baseSpread) = rowMeanAndSpread(checkStartY)
            // 三大金剛導覽列背景通常為純黑 (lum < 28) 或純白/淺灰 (lum > 230)，且橫向底色一致 (spread < 25)
            if ((baseLum < 28f || baseLum > 230f) && baseSpread < 25f) {
                var barTopY = checkStartY
                var y = checkStartY - 3
                while (y >= checkEndY) {
                    val (lum, spread) = rowMeanAndSpread(y)
                    if (abs(lum - baseLum) <= 18f && spread < 45f) {
                        barTopY = y
                    } else {
                        break
                    }
                    y -= 3
                }
                val barHeightRatio = (sh - barTopY).toFloat() / sh
                // 標準三大金剛導覽列高度約佔螢幕 4.2% ~ 8.8%
                if (barHeightRatio in 0.042f..0.088f) {
                    return ViewportMetrics(
                        sw,
                        sh,
                        effectiveTopY,
                        barTopY,
                        true,
                        effectiveLeftX,
                        effectiveRightX
                    )
                }
            }
        }

        return ViewportMetrics(sw, sh, effectiveTopY, sh, false, effectiveLeftX, effectiveRightX)
    }

    /**
     * 計算不同螢幕比例下的頂部系統狀態列／瀏海安全防護高度。
     * 若傳入畫面截圖，會一併納入自動掃描到的頂部暗帶高度作為下限保護。
     */
    fun getStatusBarSafeHeight(sw: Int, sh: Int, screenBitmap: Bitmap? = null): Int {
        if (sw <= 0 || sh <= 0) return 0
        val aspect = sh.toFloat() / max(1, sw)
        val safeRatio = interpolatePiecewise(
            aspect,
            listOf(
                1.33f to 0.026f,
                1.778f to 0.034f,
                2.00f to 0.038f,
                2.22f to 0.040f
            )
        )
        val baseSafe = max(1, (sh * safeRatio).toInt())
        if (screenBitmap == null) return baseSafe
        val (leftX, rightX) = scanHorizontalLetterboxBounds(screenBitmap, sw, sh)
        val scannedTop = scanEffectiveTopY(screenBitmap, sw, sh, leftX, rightX)
        return max(baseSafe, scannedTop).coerceAtMost(max(1, (sh * 0.085f).toInt()))
    }

    /**
     * 依有效畫布長寬比連續分段線性插值推算子彈窗右上角關閉按鈕 Y 比例 (覆蓋 1.33f..2.45f)。
     */
    fun interpolateSubDialogCloseYRatio(effectiveAspect: Float): Float {
        return interpolatePiecewise(
            effectiveAspect,
            listOf(
                1.33f to 0.068f,
                1.778f to 0.078f,
                1.95f to 0.100f,
                2.15f to 0.115f,
                2.444f to 0.121f,
                2.45f to 0.121f
            )
        )
    }

    private fun interpolatePiecewise(x: Float, anchors: List<Pair<Float, Float>>): Float {
        if (anchors.isEmpty()) return 0f
        if (x <= anchors.first().first) return anchors.first().second
        if (x >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (x0, y0) = anchors[i]
            val (x1, y1) = anchors[i + 1]
            if (x <= x1) {
                val span = x1 - x0
                if (span <= 0.0001f) return y0
                val t = ((x - x0) / span).coerceIn(0f, 1f)
                return y0 + (y1 - y0) * t
            }
        }
        return anchors.last().second
    }

    private fun pixelMaxChannel(px: Int): Int {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        return max(r, max(g, b))
    }

    private fun isDarkBarPixel(px: Int): Boolean = pixelMaxChannel(px) <= 24

    private fun scanEffectiveTopY(
        screenBitmap: Bitmap?,
        sw: Int,
        sh: Int,
        leftX: Int,
        rightX: Int
    ): Int {
        if (screenBitmap == null || sw < 100 || sh < 100) return 0
        return try {
            val bw = min(sw, screenBitmap.width)
            val bh = min(sh, screenBitmap.height)
            if (bw < 100 || bh < 100) return 0
            val cl = leftX.coerceIn(0, bw - 2)
            val cr = rightX.coerceIn(cl + 1, bw)
            val cw = max(1, cr - cl)
            val sampleXs = listOf(0.14f, 0.22f, 0.30f, 0.38f, 0.62f, 0.70f, 0.78f, 0.86f)
                .map { (cl + cw * it).toInt().coerceIn(0, bw - 1) }

            val probeTopY = max(1, (bh * 0.003f).toInt())
            val topDarkCount = sampleXs.count { x -> isDarkBarPixel(screenBitmap.getPixel(x, probeTopY)) }
            if (topDarkCount.toFloat() / sampleXs.size < 0.80f) return 0

            val maxScanY = min(bh - 2, (bh * 0.085f).toInt())
            val minBandH = max(4, (bh * 0.010f).toInt())
            val yStep = max(1, bh / 1200)
            var foundTop = -1
            var y = 0
            while (y <= maxScanY) {
                val darkCount = sampleXs.count { x -> isDarkBarPixel(screenBitmap.getPixel(x, y)) }
                if (darkCount.toFloat() / sampleXs.size < 0.70f) {
                    foundTop = y
                    break
                }
                y += yStep
            }
            if (foundTop < minBandH) return 0

            val checkEndY = min(bh - 1, foundTop + max(8, (bh * 0.016f).toInt()))
            var activeHits = 0
            var totalHits = 0
            var cy = foundTop + 1
            while (cy <= checkEndY) {
                for (x in sampleXs) {
                    totalHits++
                    if (pixelMaxChannel(screenBitmap.getPixel(x, cy)) >= 36) activeHits++
                }
                cy += max(1, (checkEndY - foundTop) / 3)
            }
            if (totalHits == 0 || activeHits.toFloat() / totalHits < 0.40f) return 0
            foundTop
        } catch (_: Throwable) {
            0
        }
    }

    private fun scanHorizontalLetterboxBounds(
        screenBitmap: Bitmap?,
        sw: Int,
        sh: Int
    ): Pair<Int, Int> {
        if (screenBitmap == null || sw < 100 || sh < 100) return 0 to sw
        return try {
            val bw = min(sw, screenBitmap.width)
            val bh = min(sh, screenBitmap.height)
            if (bw < 100 || bh < 100) return 0 to sw

            val sampleYs = listOf(0.18f, 0.30f, 0.42f, 0.52f, 0.64f, 0.74f, 0.82f)
                .map { (bh * it).toInt().coerceIn(0, bh - 1) }
            val centerXs = listOf(0.42f, 0.50f, 0.58f)
                .map { (bw * it).toInt().coerceIn(0, bw - 1) }
            var centerBright = 0
            for (y in sampleYs) {
                for (x in centerXs) {
                    if (pixelMaxChannel(screenBitmap.getPixel(x, y)) >= 36) centerBright++
                }
            }
            if (centerBright < 3) return 0 to sw

            val leftProbeX = max(2, (bw * 0.008f).toInt())
            val rightProbeX = min(bw - 3, bw - 1 - leftProbeX)
            val leftEdgeDark = sampleYs.count { y -> isDarkBarPixel(screenBitmap.getPixel(leftProbeX, y)) }
            val rightEdgeDark = sampleYs.count { y -> isDarkBarPixel(screenBitmap.getPixel(rightProbeX, y)) }
            if (leftEdgeDark < 6 || rightEdgeDark < 6) return 0 to sw

            val xStep = max(2, bw / 240)
            val midX = bw / 2
            val leftEdge = findInnerContentEdge(screenBitmap, 0, midX, xStep, sampleYs, fromLeft = true) ?: return 0 to sw
            val rightEdge = findInnerContentEdge(screenBitmap, bw - 1, midX, -xStep, sampleYs, fromLeft = false) ?: return 0 to sw

            val minBarW = max(8, (bw * 0.020f).toInt())
            val cw = rightEdge - leftEdge
            if (leftEdge < minBarW || (bw - rightEdge) < minBarW) return 0 to sw
            if (cw < (bw * 0.35f).toInt() || cw > (bw * 0.96f).toInt()) return 0 to sw
            if (kotlin.math.abs(leftEdge - (bw - rightEdge)) > max(16, (bw * 0.08f).toInt())) return 0 to sw

            val upperYs = sampleYs.take(3)
            val lowerYs = sampleYs.takeLast(3)
            val upL = findInnerContentEdge(screenBitmap, 0, midX, xStep, upperYs, fromLeft = true) ?: return 0 to sw
            val loL = findInnerContentEdge(screenBitmap, 0, midX, xStep, lowerYs, fromLeft = true) ?: return 0 to sw
            val upR = findInnerContentEdge(screenBitmap, bw - 1, midX, -xStep, upperYs, fromLeft = false) ?: return 0 to sw
            val loR = findInnerContentEdge(screenBitmap, bw - 1, midX, -xStep, lowerYs, fromLeft = false) ?: return 0 to sw
            val alignTol = max(12, (bw * 0.03f).toInt())
            if (kotlin.math.abs(upL - loL) > alignTol || kotlin.math.abs(upR - loR) > alignTol) {
                return 0 to sw
            }
            leftEdge to rightEdge
        } catch (_: Throwable) {
            0 to sw
        }
    }

    private fun findInnerContentEdge(
        bitmap: Bitmap,
        startX: Int,
        limitX: Int,
        stepX: Int,
        rows: List<Int>,
        fromLeft: Boolean
    ): Int? {
        val threshold = max(1, rows.size / 3)
        var x = startX
        while (if (fromLeft) x < limitX else x > limitX) {
            val active = rows.count { y -> pixelMaxChannel(bitmap.getPixel(x, y)) >= 32 }
            if (active >= threshold) {
                var refined = x
                val backStep = if (fromLeft) -1 else 1
                var steps = 0
                while (steps < kotlin.math.abs(stepX) && refined + backStep != startX) {
                    val nx = refined + backStep
                    val nActive = rows.count { y -> pixelMaxChannel(bitmap.getPixel(nx, y)) >= 32 }
                    if (nActive < threshold) break
                    refined = nx
                    steps++
                }
                return if (fromLeft) refined else refined + 1
            }
            x += stepX
        }
        return null
    }

    /**
     * 檢查座標是否誤入底部「傳統三大金剛按鍵」安全禁區，防止誤按系統返回/首頁/多工鍵。
     */
    fun isInsideThreeButtonNavBarZone(
        y: Int,
        sh: Int,
        screenBitmap: Bitmap? = null,
        ocrItems: List<OcrItem> = emptyList()
    ): Boolean {
        if (sh <= 0) return false
        val vp = detectViewport(
            sw = screenBitmap?.width ?: 1080,
            sh = sh,
            screenBitmap = screenBitmap,
            ocrItems = ocrItems
        )
        return vp.hasThreeButtonNavBar && y >= (vp.effectiveBottomY - (sh * 0.005f).toInt())
    }

    /**
     * 子視窗（每日賓果、抽抽樂、日常任務、禮物抽屜）右上角 (X) 關閉按鈕全比例精準定位器：
     * 1. 【第一優先】直接 OCR 匹配右上角獨立的 X / × / ✕ / 關閉。
     * 2. 【第二優先】像素級掃描「上方半透明黑幕 (lum < 165)」與「下方白色圓角子視窗面板 (r,g,b > 228)」的交界線 sheetTopY，
     *    並在面板右上角區域內搜尋深灰色 (X) 筆畫像素群的幾何中心，100% 適應 22:9、21:9、16:9 與三大金剛鍵！
     * 3. 【第三優先】嚴格限定在頂部標題列高度 (cy in 0.06f..0.15f) 的精確標題文字中心 Y。
     * 4. 【第四優先】依實際有效畫布長寬比計算之基準座標，並支援多點微調輪詢 (attemptOffset)。
     */
    fun findSubDialogCloseButtonBounds(
        sw: Int,
        sh: Int,
        ocrItems: List<OcrItem> = emptyList(),
        screenBitmap: Bitmap? = null,
        exactHeaderTitles: List<String> = emptyList(),
        defaultYRatio22to9: Float = 0.121f,
        attempt: Int = 1
    ): Rect {
        // 1. 直接 OCR 匹配右上角 X
        val directOcrX = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim()
            cy in 0.055f..0.24f && cx > 0.80f && (
                t.equals("X", ignoreCase = true) ||
                t == "×" ||
                t == "✕" ||
                t == "✖" ||
                t.contains("關閉")
            )
        }
        if (directOcrX != null && attempt <= 1) {
            return directOcrX.bounds
        }

        // 2. 像素級探測白色圓角面板頂部邊界 (sheetTopY) 與右上角 (X) 筆畫像素中心
        if (screenBitmap != null && screenBitmap.width >= 200 && screenBitmap.height >= 400) {
            val detectedRect = detectWhiteSheetTopRightCloseX(screenBitmap, defaultYRatio22to9, attempt)
            if (detectedRect != null) {
                return detectedRect
            }
        }

        // 3. 嚴格限定在頂部標題列範圍 (cy in 0.06f..0.155f) 的標題對齊，排除內文如 "1 BINGO" 或 "BIG抽抽樂" 的干擾
        val maxTitleYRatio = if (defaultYRatio22to9 > 0.18f) 0.28f else 0.155f
        val minTitleYRatio = if (defaultYRatio22to9 > 0.18f) 0.15f else 0.06f
        val strictTitleItem = ocrItems.firstOrNull { item ->
            val cy = item.bounds.centerY().toFloat() / sh
            val cx = item.bounds.centerX().toFloat() / sw
            val t = item.text.trim().replace(" ", "")
            cy in minTitleYRatio..maxTitleYRatio && cx in 0.25f..0.75f &&
                exactHeaderTitles.any { title -> t == title || t.startsWith(title) }
        }

        val vp = detectViewport(sw, sh, screenBitmap, ocrItems)
        val effectiveAspect = vp.contentHeight.toFloat() / max(1, vp.contentWidth)

        val baseCenterY = when {
            strictTitleItem != null -> strictTitleItem.bounds.centerY()
            defaultYRatio22to9 > 0.18f -> vp.mapContentY(defaultYRatio22to9)
            else -> vp.mapContentY(interpolateSubDialogCloseYRatio(effectiveAspect))
        }

        val baseCenterX = vp.mapContentX(0.908f)
        val (offsetX, offsetY) = getCalibrationOffset(sw, sh, attempt)
        val finalCx = (baseCenterX + offsetX).coerceIn(vp.mapContentX(0.84f), vp.mapContentX(0.96f))
        val finalCy = (baseCenterY + offsetY).coerceIn(vp.mapContentY(0.055f), vp.mapContentY(0.30f))

        val halfW = max(4, (vp.contentWidth * 0.045f).toInt())
        val halfH = max(4, (vp.contentHeight * 0.022f).toInt())
        return Rect(
            (finalCx - halfW).coerceIn(0, sw),
            (finalCy - halfH).coerceIn(0, sh),
            (finalCx + halfW).coerceIn(0, sw),
            (finalCy + halfH).coerceIn(0, sh)
        )
    }

    private fun detectWhiteSheetTopRightCloseX(
        bitmap: Bitmap,
        defaultYRatio: Float,
        attempt: Int
    ): Rect? {
        val sw = bitmap.width
        val sh = bitmap.height
        val searchTopY = (sh * 0.045f).toInt().coerceIn(0, sh - 1)
        val searchBotY = (sh * (if (defaultYRatio > 0.18f) 0.32f else 0.22f)).toInt().coerceIn(searchTopY + 1, sh - 1)
        val probeXs = intArrayOf((sw * 0.35f).toInt(), (sw * 0.50f).toInt(), (sw * 0.65f).toInt())

        fun isWhiteRow(y: Int): Boolean {
            var whiteCols = 0
            for (px in probeXs) {
                val c = bitmap.getPixel(px.coerceIn(0, sw - 1), y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 228 && g > 228 && b > 228) whiteCols++
            }
            return whiteCols >= 2
        }

        fun isDimmedRow(y: Int): Boolean {
            var dimCols = 0
            for (px in probeXs) {
                val c = bitmap.getPixel(px.coerceIn(0, sw - 1), y)
                val lum = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                if (lum < 185f) dimCols++
            }
            return dimCols >= 2
        }

        var sheetTopY = -1
        var seenDimmedAbove = false
        var y = searchTopY
        while (y < searchBotY - 12) {
            if (isDimmedRow(y)) {
                seenDimmedAbove = true
            } else if (seenDimmedAbove && isWhiteRow(y) && isWhiteRow(y + 4) && isWhiteRow(y + 8)) {
                sheetTopY = y
                break
            }
            y += 2
        }

        if (sheetTopY < 0) return null

        // 在白底面板右上角區域 (x in 86%..95.5% sw, y in sheetTopY + 1.0%..5.6% sh) 搜尋深灰色 (X) 筆畫像素中心
        val boxLeft = (sw * 0.860f).toInt().coerceIn(0, sw - 1)
        val boxRight = (sw * 0.955f).toInt().coerceIn(boxLeft + 1, sw - 1)
        val boxTop = (sheetTopY + (sh * 0.010f).toInt()).coerceIn(0, sh - 1)
        val boxBottom = (sheetTopY + (sh * 0.056f).toInt()).coerceIn(boxTop + 1, sh - 1)

        var sumX = 0L
        var sumY = 0L
        var count = 0
        for (py in boxTop..boxBottom step 2) {
            for (px in boxLeft..boxRight step 2) {
                val c = bitmap.getPixel(px, py)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                // 白色面板上的深灰/黑色 (X) 筆畫像素 (lum in 20..195 且非高彩度紅點)
                if (lum in 20f..195f && abs(r - g) < 35 && abs(g - b) < 35) {
                    sumX += px
                    sumY += py
                    count++
                }
            }
        }

        val baseCx = if (count >= 5) (sumX / count).toInt() else (sw * 0.908f).toInt()
        val baseCy = if (count >= 5) (sumY / count).toInt() else (sheetTopY + (sh * 0.032f).toInt())

        val (offsetX, offsetY) = getCalibrationOffset(sw, sh, attempt)
        val finalCx = (baseCx + offsetX).coerceIn((sw * 0.84f).toInt(), (sw * 0.96f).toInt())
        val finalCy = (baseCy + offsetY).coerceIn((sh * 0.055f).toInt(), (sh * 0.32f).toInt())
        val halfW = max(18, (sw * 0.035f).toInt())
        val halfH = max(18, (sh * 0.018f).toInt())

        return Rect(
            (finalCx - halfW).coerceIn(0, sw),
            (finalCy - halfH).coerceIn(0, sh),
            (finalCx + halfW).coerceIn(0, sw),
            (finalCy + halfH).coerceIn(0, sh)
        )
    }

    private fun getCalibrationOffset(sw: Int, sh: Int, attempt: Int): Pair<Int, Int> {
        return when (attempt) {
            1 -> Pair(0, 0)
            2 -> Pair(0, -(sh * 0.010f).toInt())
            3 -> Pair(0, (sh * 0.010f).toInt())
            4 -> Pair(-(sw * 0.015f).toInt(), 0)
            else -> Pair((sw * 0.012f).toInt(), -(sh * 0.006f).toInt())
        }
    }
}
