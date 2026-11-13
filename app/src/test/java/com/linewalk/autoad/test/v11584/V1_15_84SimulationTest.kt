package com.linewalk.autoad.test.v11584

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AdCloserHandler
import com.linewalk.autoad.engine.AdStepResult
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * v1.15.84 Robolectric 實機邏輯回歸與模擬驗證測試：
 * 驗證 v1.15.82 實機日誌與 iHEALTH Run. Burn. Earn. 截圖揭露之 4 項核心修復：
 * 1. 左上角黑邊／亮牆跨界深色半透明圓鈕白 `[▶|]` 略過圖示精準偵測，同時排除頂部橫向 `[X 56 秒後可獲獎勵]` 膠囊。
 * 2. GeoX 收斂邊界排除 13.5%~15.7% 橫幅促銷圖示與 16.6% 左側廣告主專頁圖示，並保留真實角落叉鈕。
 * 3. Watchdog 計時器檢查前先行確認是否已回到散步趣主畫面（含「首真/步數/時間」容錯），避免退場後誤按 Back 返回桌面。
 * 4. 內嵌廣告 Activity 吞噬系統 Back 時（前景仍為 com.linecorp.LGWKTW），透過截圖驗證並逐級升級 `pressHome` -> `CLEAR_TOP` -> `CLEAR_TASK`（包含連續無截圖重試升級）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_84SimulationTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx)
        } catch (_: Throwable) {
        }
    }

    private fun createMockService(fgPkg: String = "com.linecorp.LGWKTW"): AutoAdAccessibilityService {
        return mockk(relaxed = true) {
            every { getForegroundPackageName() } returns fgPkg
            every { getSafeRootNode() } returns null
            every { safeNavigateBack(any(), any(), any()) } returns true
            every { navigateBack() } returns true
            every { pressHome() } returns true
            every { bringAppToFront(any()) } returns true
            every { forceResetAppTaskToFront(any(), any()) } returns true
            coEvery { clickRect(any(), any(), any()) } returns true
        }
    }

    private fun drawPixelCircleX(
        bmp: Bitmap,
        cx: Int,
        cy: Int,
        radius: Int,
        armHalfLen: Int,
        halfStroke: Int,
        circleColor: Int,
        xStrokeColor: Int
    ) {
        val w = bmp.width
        val h = bmp.height
        val r2 = radius * radius
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx * dx + dy * dy <= r2) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px in 0 until w && py in 0 until h) {
                        bmp.setPixel(px, py, circleColor)
                    }
                }
            }
        }
        for (d in -armHalfLen..armHalfLen) {
            for (sy in -halfStroke..halfStroke) {
                for (sx in -halfStroke..halfStroke) {
                    val p1x = cx + d + sx
                    val p1y = cy + d + sy
                    val p2x = cx + d + sx
                    val p2y = cy - d + sy
                    if (p1x in 0 until w && p1y in 0 until h) bmp.setPixel(p1x, p1y, xStrokeColor)
                    if (p2x in 0 until w && p2y in 0 until h) bmp.setPixel(p2x, p2y, xStrokeColor)
                }
            }
        }
    }

    private fun fillRectPixels(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        val w = bmp.width
        val h = bmp.height
        val x0 = left.coerceIn(0, w)
        val x1 = right.coerceIn(0, w)
        val y0 = top.coerceIn(0, h)
        val y1 = bottom.coerceIn(0, h)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                bmp.setPixel(x, y, color)
            }
        }
    }

    private fun drawHorizonFastForwardSkipPill(
        sw: Int = 1080,
        sh: Int = 2340,
        cx: Int = 76,
        cy: Int = 246,
        horizonY: Int = 258
    ): Bitmap {
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(245, 245, 245))
        fillRectPixels(bmp, 0, 0, sw, horizonY, Color.BLACK)

        // 深色半透明圓形底鈕 (r=30px) 跨界壓在 y=258 黑白交界上（直接寫入像素以確保跨 Robolectric graphics mode 一致性）
        val r = 30
        val r2 = r * r
        val pillColor = Color.rgb(75, 75, 75)
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy <= r2) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px in 0 until sw && py in 0 until sh) {
                        bmp.setPixel(px, py, pillColor)
                    }
                }
            }
        }

        // 內部純白 [▶|] 圖示 (左三角 + 右豎線)
        for (dx in -10..3) {
            val progress = (dx + 10).toFloat() / 13f
            val halfH = ((1f - progress) * 10f).toInt()
            for (dy in -halfH..halfH) {
                bmp.setPixel(cx + dx, cy + dy, Color.WHITE)
            }
        }
        fillRectPixels(bmp, cx + 5, cy - 10, cx + 11, cy + 11, Color.WHITE)
        return bmp
    }

    @Test
    fun test01_topLeftFastForwardSkipPillOnHorizonDetectedAndCountdownCapsuleExcluded() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val templateMatcher = TemplateMatcher()
        val ocrRecognizer = OcrRecognizer()
        val nodeInspector = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
            every { findNodeByKeywords(any(), any(), any(), any(), any()) } returns null
        }
        val handler = AdCloserHandler(ocrRecognizer, nodeInspector, templateMatcher, AppConfig(ctx))
        val mockService = createMockService("com.linecorp.LGWKTW")

        // 1. iHEALTH Run. Burn. Earn. 跨界黑邊/白牆左上角 (76, 246) [▶|] 圓鈕
        val iHealthBmp = drawHorizonFastForwardSkipPill()
        val skipMatch = templateMatcher.detectCornerFastForwardSkip(iHealthBmp)
        assertNotNull("跨界黑邊/白牆之左上角 [▶|] 略過鈕必須穩定辨識", skipMatch)
        assertEquals("左上角略過鈕(▶|)", skipMatch!!.label)
        assertTrue("X 座標應接近 76: ${skipMatch.bounds.centerX()}", abs(skipMatch.bounds.centerX() - 76) <= 15)
        assertTrue("Y 座標應接近 246: ${skipMatch.bounds.centerY()}", abs(skipMatch.bounds.centerY() - 246) <= 15)

        // 2. AdCloserHandler 於 elapsedSec = 6 時應立即命中並點擊此按鈕
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 6_000L)

        val clickRes = handler.processAdTick(mockService, iHealthBmp, emptyList())
        assertTrue("應透過左上角略過鈕(▶|)點擊突破 iHEALTH 廣告: $clickRes", clickRes is AdStepResult.ClickedCloseOrSkip)
        assertEquals("左上角略過鈕(▶|)", (clickRes as AdStepResult.ClickedCloseOrSkip).sourceLabel)

        // 3. 左上角頂部橫向「X 56 秒後可獲獎勵」深色膠囊不得被誤點為 [▶|]
        val capsuleBmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
        capsuleBmp.eraseColor(Color.rgb(220, 220, 220))
        // 寬長深色膠囊 (x=42..420, y=132..192)
        fillRectPixels(capsuleBmp, 42, 132, 420, 192, Color.rgb(65, 65, 65))
        // 左側 X
        drawPixelCircleX(capsuleBmp, cx = 76, cy = 162, radius = 26, armHalfLen = 14, halfStroke = 3, circleColor = Color.rgb(65, 65, 65), xStrokeColor = Color.WHITE)
        // 右側延長文字模擬像素塊 ("56 秒後...") @ x=126..245
        fillRectPixels(capsuleBmp, 126, 148, 152, 178, Color.WHITE)
        fillRectPixels(capsuleBmp, 162, 148, 188, 178, Color.WHITE)
        fillRectPixels(capsuleBmp, 202, 148, 232, 178, Color.WHITE)

        val capsulePixelMatch = templateMatcher.detectCornerFastForwardSkip(capsuleBmp)
        assertNull("橫向延伸文字膠囊「X 56 秒後可獲獎勵」應被像素膠囊守衛排除: $capsulePixelMatch", capsulePixelMatch)

        // 即使搭配 OCR 重疊「X 56 秒後可獲獎勵」，AdCloserHandler 亦不可觸發 4C 點擊
        val handler2 = AdCloserHandler(ocrRecognizer, nodeInspector, templateMatcher, AppConfig(ctx))
        handler2.onEnterAdMode()
        startField.setLong(handler2, System.currentTimeMillis() - 10_000L)
        val capsuleOcr = listOf(OcrItem("X 56 秒後可獲獎勵", Rect(55, 140, 405, 185)))
        val capsuleRes = handler2.processAdTick(mockService, capsuleBmp, capsuleOcr)
        assertFalse("倒數期間絕不可誤點獎勵倒數膠囊: $capsuleRes", capsuleRes is AdStepResult.ClickedCloseOrSkip)
    }

    @Test
    fun test02_geoXExcludesPromoArtAt14To16PercentAndLeft17PercentWhilePreservingCorners() = runTest {
        val templateMatcher = TemplateMatcher()
        val sw = 1080
        val sh = 2340

        // 1. 排除 log 實測 y=15.2% (107, 356) 左側促銷圖示
        val falseLeftLow = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        drawPixelCircleX(falseLeftLow, cx = 107, cy = 356, radius = 30, armHalfLen = 17, halfStroke = 3, circleColor = Color.rgb(30, 30, 30), xStrokeColor = Color.WHITE)
        assertNull("y=15.2% 左側橫幅促銷圖示應排除於 GeoX 之外", templateMatcher.detectCornerGeometricX(falseLeftLow, includeLeftZone = true))

        // 2. 排除 log 實測 y=13.5% (906, 315) 右側促銷圖示
        val falseRightLow = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        drawPixelCircleX(falseRightLow, cx = 906, cy = 315, radius = 30, armHalfLen = 17, halfStroke = 3, circleColor = Color.rgb(30, 30, 30), xStrokeColor = Color.WHITE)
        assertNull("y=13.5% 右側橫幅促銷圖示應排除於 GeoX 之外", templateMatcher.detectCornerGeometricX(falseRightLow))

        // 3. 排除 log 實測 x=16.6% (179, 205) 廣告主專頁圖示
        val falseLeftWide = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        drawPixelCircleX(falseLeftWide, cx = 179, cy = 205, radius = 30, armHalfLen = 17, halfStroke = 3, circleColor = Color.rgb(30, 30, 30), xStrokeColor = Color.WHITE)
        assertNull("x=16.6% 左側廣告主專頁圖示應排除於 GeoX 之外", templateMatcher.detectCornerGeometricX(falseLeftWide, includeLeftZone = true))

        // 4. 保留真實左上角 (12.9%, 11.3% -> 139, 265) 與右上角 (92.1%, 9.0% -> 995, 210)
        val trueLeft = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(25, 25, 25)) }
        drawPixelCircleX(trueLeft, cx = 139, cy = 265, radius = 30, armHalfLen = 17, halfStroke = 3, circleColor = Color.rgb(65, 65, 65), xStrokeColor = Color.WHITE)
        val leftHit = templateMatcher.detectCornerGeometricX(trueLeft, includeLeftZone = true)
        assertNotNull("真實左上角幾何X (12.9%, 11.3%) 必須持續辨識", leftHit)
        assertTrue(abs(leftHit!!.bounds.centerX() - 139) <= 15)

        val trueRight = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 20, 20)) }
        drawPixelCircleX(trueRight, cx = 995, cy = 210, radius = 30, armHalfLen = 17, halfStroke = 3, circleColor = Color.rgb(65, 65, 65), xStrokeColor = Color.WHITE)
        val rightHit = templateMatcher.detectCornerGeometricX(trueRight)
        assertNotNull("真實右上角幾何X (92.1%, 9.0%) 必須持續辨識", rightHit)
        assertTrue(abs(rightHit!!.bounds.centerX() - 995) <= 15)
    }

    @Test
    fun test03_watchdogDoesNotFireBackWhenAlreadyReturnedToGameHomeScreen() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        engine.consecutiveWatchdogTimeouts = 0

        val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
        val adCloser = adCloserField.get(engine) as AdCloserHandler
        adCloser.onEnterAdMode()

        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val shownField = AdCloserHandler::class.java.getDeclaredField("hasAdActuallyShown").apply { isAccessible = true }
        startField.setLong(adCloser, System.currentTimeMillis() - 66_000L)
        shownField.setBoolean(adCloser, true)

        engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
        val adsBefore = engine.uiStatus.value.adsCompletedCount

        val mockService = createMockService("com.linecorp.LGWKTW")
        val sw = 1080
        val sh = 2340
        val homeBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        // 重現 log 00:06:39：Close 已退回散步趣主畫面（含 OCR「首真」、步數、時間與底欄標籤）
        val homeOcr = listOf(
            OcrItem("249,510", Rect(240, 145, 420, 185)),
            OcrItem("首真", Rect(85, 255, 175, 305)),
            OcrItem("步數", Rect(355, 255, 445, 305)),
            OcrItem("時間", Rect(625, 255, 715, 305)),
            OcrItem("集點", Rect(80, 2170, 180, 2230)),
            OcrItem("好友", Rect(350, 2170, 450, 2230)),
            OcrItem("使用", Rect(630, 2170, 730, 2230)),
            OcrItem("精選", Rect(900, 2170, 1000, 2230))
        )

        engine.handleAdWatching(mockService, homeBmp, homeOcr)

        verify(exactly = 0) { mockService.safeNavigateBack(any(), any(), any()) }
        verify(exactly = 0) { mockService.pressHome() }
        verify(exactly = 0) { mockService.forceResetAppTaskToFront(any(), any()) }
        assertTrue(
            "回到主畫面應平穩切換至 IN_GAME_SCAN 或 REWARD_CLAIM，實際=${engine.uiStatus.value.state}",
            engine.uiStatus.value.state != EngineState.AD_WATCHING
        )
        assertTrue(
            "應計入廣告完成次數而非被 Watchdog 強退洗掉",
            engine.uiStatus.value.adsCompletedCount >= adsBefore + 1
        )
    }

    @Test
    fun test04_watchdogEscalatesPressHomeClearTopAndClearTaskInsideSwallowedEmbeddedAd() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.config.maxAdWaitSeconds = 65
        engine.resetClickCircuitBreaker()
        engine.consecutiveWatchdogTimeouts = 0

        val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
        val adCloser = adCloserField.get(engine) as AdCloserHandler
        val ocrField = AutomationEngine::class.java.getDeclaredField("ocrRecognizer").apply { isAccessible = true }
        val origOcr = ocrField.get(engine)
        val spiedOcr = spyk(OcrRecognizer()).apply {
            coEvery { recognize(any()) } returns emptyList()
        }
        ocrField.set(engine, spiedOcr)
        try {
            val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }

            // 情境 A：Back x2 遭內嵌廣告 Activity 吞噬（前景仍為 LGWKTW），重抓截圖確認仍非主畫面 -> 依序執行 pressHome -> CLEAR_TOP -> CLEAR_TASK
            adCloser.onEnterAdMode()
            startField.setLong(adCloser, System.currentTimeMillis() - 70_000L)
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")

            val adScreenBmp = drawHorizonFastForwardSkipPill()
            val mockServiceA = createMockService("com.linecorp.LGWKTW")
            coEvery { mockServiceA.captureScreenBitmap() } answers {
                Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            }

            engine.handleAdWatching(mockServiceA, adScreenBmp, emptyList())

            verify(atLeast = 2) { mockServiceA.safeNavigateBack(any(), any(), any()) }
            verify(atLeast = 1) { mockServiceA.pressHome() }
            verify(atLeast = 1) { mockServiceA.forceResetAppTaskToFront("com.linecorp.LGWKTW", false) }
            verify(atLeast = 1) { mockServiceA.forceResetAppTaskToFront("com.linecorp.LGWKTW", true) }
            assertEquals("逾時逃脫後計時器必須歸零", 0, adCloser.getElapsedSeconds())
            assertEquals(EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)

            // 情境 B：即使 captureScreenBitmap() 連續回傳 null，第 1 次先試 pressHome + CLEAR_TOP，第 2 次逾時必須直上 CLEAR_TASK 斷絕無限 65 秒循環
            engine.consecutiveWatchdogTimeouts = 0
            val mockServiceB = createMockService("com.linecorp.LGWKTW")
            coEvery { mockServiceB.captureScreenBitmap() } returns null

            adCloser.onEnterAdMode()
            startField.setLong(adCloser, System.currentTimeMillis() - 70_000L)
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
            engine.handleAdWatching(mockServiceB, adScreenBmp, emptyList())

            assertEquals(1, engine.consecutiveWatchdogTimeouts)
            verify(exactly = 1) { mockServiceB.forceResetAppTaskToFront("com.linecorp.LGWKTW", false) }
            verify(exactly = 0) { mockServiceB.forceResetAppTaskToFront("com.linecorp.LGWKTW", true) }

            adCloser.onEnterAdMode()
            startField.setLong(adCloser, System.currentTimeMillis() - 70_000L)
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
            engine.handleAdWatching(mockServiceB, adScreenBmp, emptyList())

            assertEquals(2, engine.consecutiveWatchdogTimeouts)
            verify(exactly = 1) { mockServiceB.forceResetAppTaskToFront("com.linecorp.LGWKTW", true) }
        } finally {
            ocrField.set(engine, origOcr)
        }
    }
}
