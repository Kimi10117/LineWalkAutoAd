package com.linewalk.autoad.test.v11590

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AdCloserHandler
import com.linewalk.autoad.engine.AdStepResult
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.vision.MatchedNode
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import java.io.File
import kotlin.math.abs

/**
 * v1.15.90 Robolectric 回歸與實機日誌重播測試（針對 Coupang 酷澎輪播廣告雙層 X 與重定向黑名單跨逾時遺失）：
 * 1. TemplateMatcher 在頂部含黑邊 (effectiveTopY≈154) 卡片中，不重複上推頂部與下壓底部，
 *    徹底排除 y=14.2%~14.9% 商品圖誤判 (926, 393)，並原生支援中灰單色圓底亮白 (X) (978, 259)。
 * 2. 針對 local_archive/screenshots 真實 Coupang 截圖直接執行像素檢測驗證。
 * 3. AdCloserHandler 對 Coupang 酷澎雙層關閉鈕優先擊中下方圓形灰底 (X)，且無redirect 失敗點採緊縮半徑不吞噬下方圓鈕。
 * 4. Watchdog 逾時執行 clearAdSessionTimer() 後重進 AD_WATCHING 時，180s 內與跨逾時會期仍保留已封鎖誤觸座標。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_90SimulationTest {

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
            every { lastBackRejectReason } returns ""
            coEvery { clickRect(any(), any(), any()) } returns true
        }
    }

    private fun fillRectFast(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        val w = bmp.width
        val h = bmp.height
        val x0 = left.coerceIn(0, w)
        val y0 = top.coerceIn(0, h)
        val x1 = right.coerceIn(x0, w)
        val y1 = bottom.coerceIn(y0, h)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return
        val row = IntArray(rw) { color }
        for (y in y0 until y1) {
            bmp.setPixels(row, 0, rw, x0, y, rw, 1)
        }
    }

    private fun drawPixelXOnly(bmp: Bitmap, cx: Int, cy: Int, armHalfLen: Int, halfStroke: Int, color: Int) {
        val w = bmp.width
        val h = bmp.height
        for (d in -armHalfLen..armHalfLen) {
            for (sy in -halfStroke..halfStroke) {
                for (sx in -halfStroke..halfStroke) {
                    val p1x = cx + d + sx
                    val p1y = cy + d + sy
                    val p2x = cx + d + sx
                    val p2y = cy - d + sy
                    if (p1x in 0 until w && p1y in 0 until h) bmp.setPixel(p1x, p1y, color)
                    if (p2x in 0 until w && p2y in 0 until h) bmp.setPixel(p2x, p2y, color)
                }
            }
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
        drawPixelXOnly(bmp, cx, cy, armHalfLen, halfStroke, xStrokeColor)
    }

    /**
     * 建構與 Samsung 1080x2640 Coupang 酷澎輪播卡片相同幾何特徵的模擬畫面：
     * - 頂部黑邊 0..154px (5.8%)、左右黑邊 0..58px / 1022..1080px
     * - 右上角上方小型暗灰裝飾 × @ (990, 183)
     * - 右上角下方大型中灰 (#AFAFAF) 圓形白叉 (X) @ (980, 260)
     * - 商品圖區域高對比誘餌交叉 @ (926, 393 | 14.9%) 與 (970, 375 | 14.2%)
     */
    private fun buildCoupangLetterboxedBitmap(includeProductDecoy: Boolean = true): Bitmap {
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        fillRectFast(bmp, 58, 154, 1022, 1585, Color.WHITE)

        // 左上角綠色折扣徽章 (-4%) 符合真實版面
        fillRectFast(bmp, 58, 154, 195, 224, Color.rgb(122, 196, 62))

        // 上方小型深灰 × @ (990, 183)
        drawPixelXOnly(bmp, cx = 990, cy = 183, armHalfLen = 10, halfStroke = 2, color = Color.rgb(115, 115, 115))

        // 真實大顆中灰圓底白叉 (X) @ (980, 260), gray=175
        drawPixelCircleX(
            bmp = bmp,
            cx = 980,
            cy = 260,
            radius = 36,
            armHalfLen = 17,
            halfStroke = 3,
            circleColor = Color.rgb(175, 175, 175),
            xStrokeColor = Color.WHITE
        )

        if (includeProductDecoy) {
            // 商品圖內高對比誤觸 X @ (926, 393 | 14.9%) 與 (970, 375 | 14.2%)
            drawPixelCircleX(bmp, cx = 926, cy = 393, radius = 30, armHalfLen = 17, halfStroke = 3, circleColor = Color.rgb(25, 25, 25), xStrokeColor = Color.WHITE)
            drawPixelCircleX(bmp, cx = 970, cy = 375, radius = 30, armHalfLen = 17, halfStroke = 3, circleColor = Color.rgb(25, 25, 25), xStrokeColor = Color.WHITE)
        }
        return bmp
    }

    private fun buildCoupangOcr(withUpperX: Boolean = true, withCoupangFooter: Boolean = true): MutableList<OcrItem> {
        val list = mutableListOf(
            OcrItem("-4%", Rect(80, 170, 170, 210)),
            OcrItem("NIKE 耐吉男款 Shox", Rect(240, 840, 840, 900)),
            OcrItem("$3,736", Rect(460, 1080, 620, 1130)),
            OcrItem("立即購買", Rect(365, 1195, 715, 1295))
        )
        if (withUpperX) {
            list.add(OcrItem("X", Rect(976, 172, 1006, 212)))
        }
        if (withCoupangFooter) {
            list.add(OcrItem("COupang酷澎", Rect(165, 1385, 915, 1510)))
        }
        return list
    }

    @Test
    fun test01_detectCornerGrayCircleCloseXAndExcludeProductDecoys() = runTest {
        val tm = TemplateMatcher()
        val bmp = buildCoupangLetterboxedBitmap(includeProductDecoy = true)

        val circleHit = tm.detectCornerGrayCircleCloseX(bmp)
        assertNotNull("中灰圓底白色 (X) 必須精準偵測", circleHit)
        assertTrue("應命中圓形關閉按鈕 (980, 260) 附近，實際=${circleHit!!.bounds.centerX()},${circleHit.bounds.centerY()}",
            abs(circleHit.bounds.centerX() - 980) <= 15 && abs(circleHit.bounds.centerY() - 260) <= 15)

        // 即使上方小×加入黑名單，一般 GeoX 也必須直接命中 (980, 260)，絕不掉到 y=393 (14.9%) 或 y=375 (14.2%)
        val geoAfterUpperBlocked = tm.detectCornerGeometricX(
            screenBitmap = bmp,
            blacklistedCoord = { x, y -> kotlin.math.hypot((x - 990).toDouble(), (y - 183).toDouble()) < 34.0 },
            isRelaxed = false
        )
        assertNotNull("排除上方小×後，應直接命中中灰圓底 (X)", geoAfterUpperBlocked)
        assertTrue("不可低於 13.5% (356px) 誤點商品圖 (926,393)/(970,375)，實際=${geoAfterUpperBlocked!!.bounds.centerY()}",
            geoAfterUpperBlocked.bounds.centerY() <= 356)
        assertTrue("應命中 (980, 260)，實際=(${geoAfterUpperBlocked.bounds.centerX()}, ${geoAfterUpperBlocked.bounds.centerY()})",
            abs(geoAfterUpperBlocked.bounds.centerX() - 980) <= 15 && abs(geoAfterUpperBlocked.bounds.centerY() - 260) <= 15)

        // 若連真實 (980, 260) 與 (990, 183) 都遮蔽，也絕不可回傳 y=375/393 的商品誤觸點
        val noDecoyHit = tm.detectCornerGeometricX(
            screenBitmap = bmp,
            blacklistedCoord = { _, y -> y < 300 },
            isRelaxed = true
        )
        assertNull("即使 isRelaxed=true 也絕不可命中 14.2%/14.9% 商品圖交叉特徵，實際=$noDecoyHit", noDecoyHit)
    }

    @Test
    fun test02_realArchivedCoupangScreenshotsHitTrueCircleXAndRejectProductArt() = runTest {
        val tm = TemplateMatcher()
        val candidates = listOf(
            "Screenshot_20261117_223507_Coupang.jpg",
            "Screenshot_20261108_112149_LINE .jpg"
        )
        var verifiedFiles = 0
        for (name in candidates) {
            val file = listOf(
                File("local_archive/screenshots/$name"),
                File("../local_archive/screenshots/$name")
            ).firstOrNull { it.exists() } ?: continue
            val raw = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val bmp = if (raw.width == 1080 && raw.height == 2640) raw else Bitmap.createScaledBitmap(raw, 1080, 2640, true)

            val circle = tm.detectCornerGrayCircleCloseX(bmp)
            assertNotNull("真實截圖 $name 必須命中右上角灰底圓形 (X)", circle)
            assertTrue(
                "$name 圓形 (X) 座標應位於 (960..1005, 240..280)，實際=(${circle!!.bounds.centerX()}, ${circle.bounds.centerY()})",
                circle.bounds.centerX() in 960..1005 && circle.bounds.centerY() in 240..280
            )
            verifiedFiles++
        }
        assertTrue("至少應驗證一張真實歸檔 Coupang 截圖", verifiedFiles >= 1)
    }

    @Test
    fun test03_adCloserPrefersCoupangCircleXOverInertUpperXAndCloseNode() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ocrRecognizer = OcrRecognizer()
        val tm = TemplateMatcher()
        val bmp = buildCoupangLetterboxedBitmap(includeProductDecoy = true)
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }

        // A. 即使無障礙節點樹回傳上方無效 Node(991, 192)，Coupang 模式仍直接改點下方圓形 (X) (980, 260)
        val upperNode = MatchedNode("Node[txt=X]", Rect(975, 176, 1007, 208), null, "X", null)
        val mockInspectorWithUpper = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns upperNode
        }
        val handlerA = AdCloserHandler(ocrRecognizer, mockInspectorWithUpper, tm, AppConfig(ctx))
        val service = createMockService("com.linecorp.LGWKTW")
        handlerA.onEnterAdMode()
        startField.setLong(handlerA, System.currentTimeMillis() - 6_000L)

        val resA = handlerA.processAdTick(service, bmp, buildCoupangOcr(withUpperX = true, withCoupangFooter = true))
        assertTrue("應點擊酷澎圓形關閉(X)，實際=$resA", resA is AdStepResult.ClickedCloseOrSkip)
        val clickA = resA as AdStepResult.ClickedCloseOrSkip
        assertEquals("酷澎圓形關閉(X)", clickA.sourceLabel)
        assertTrue("應命中 y≈260 圓形按鈕而非 y≈192 上方節點，實際 y=${clickA.bounds.centerY()}",
            clickA.bounds.centerY() in 242..280)

        // B. 若無 Node，僅有 OCR-角落[X]@(991,192)，同樣直接選擇下方圓形 (X)
        val emptyInspector = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
        }
        val handlerB = AdCloserHandler(ocrRecognizer, emptyInspector, tm, AppConfig(ctx))
        handlerB.onEnterAdMode()
        startField.setLong(handlerB, System.currentTimeMillis() - 6_000L)

        val resB = handlerB.processAdTick(service, bmp, buildCoupangOcr(withUpperX = true, withCoupangFooter = true))
        assertTrue(resB is AdStepResult.ClickedCloseOrSkip)
        assertEquals("酷澎圓形關閉(X)", (resB as AdStepResult.ClickedCloseOrSkip).sourceLabel)

        // C. 非 Coupang 關鍵字時，若 OCR-角落[X]@(991,192) 連點 2 次無效會自動封鎖上點，且緊縮半徑不得吞噬下方 68px 的 (980, 260)
        val handlerC = AdCloserHandler(ocrRecognizer, emptyInspector, tm, AppConfig(ctx))
        handlerC.onEnterAdMode()
        startField.setLong(handlerC, System.currentTimeMillis() - 7_000L)
        val minimalOcr = listOf(OcrItem("X", Rect(976, 172, 1006, 212)))

        val c1 = handlerC.processAdTick(service, bmp, minimalOcr)
        assertEquals("OCR-角落[X]", (c1 as AdStepResult.ClickedCloseOrSkip).sourceLabel)
        val c2 = handlerC.processAdTick(service, bmp, minimalOcr)
        assertEquals("OCR-角落[X]", (c2 as AdStepResult.ClickedCloseOrSkip).sourceLabel)
        assertTrue("連點 2 次無效的 OCR-角落[X] 必須立即加入黑名單", handlerC.isInvalidOrBlacklisted(991, 192))
        assertFalse("無跳轉失敗點的緊縮半徑不可誤封下方圓形按鈕 (980, 260)", handlerC.isInvalidOrBlacklisted(980, 260))

        val c3 = handlerC.processAdTick(service, bmp, minimalOcr)
        assertTrue("封鎖上方假 X 後第 3 次必須改點下方真實圓形 (X): $c3", c3 is AdStepResult.ClickedCloseOrSkip)
        assertTrue((c3 as AdStepResult.ClickedCloseOrSkip).bounds.centerY() in 242..280)
    }

    @Test
    fun test04_redirectBlacklistSurvivesWatchdogClearSessionAndLongCooldownLoop() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()

        val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
        val adCloser = adCloserField.get(engine) as AdCloserHandler
        adCloser.clearRedirectMemory()

        // 1. 模擬 22:06:58 誤點 (926, 393) 觸發三星瀏覽器跳轉，並將 (926, 393) 加入黑名單
        val service = createMockService("com.linecorp.LGWKTW")
        adCloser.onEnterAdMode(preserveRedirectState = false)
        val lastPtField = AdCloserHandler::class.java.getDeclaredField("lastClickedPoint").apply { isAccessible = true }
        val lastLabelField = AdCloserHandler::class.java.getDeclaredField("lastClickedAdLabel").apply { isAccessible = true }
        lastPtField.set(adCloser, Point(926, 393))
        lastLabelField.set(adCloser, "右上角幾何X")
        adCloser.markRecentRedirect(Point(926, 393), fromExternalApp = true)
        assertTrue(adCloser.isInvalidOrBlacklisted(926, 393))

        // 2. 模擬 65sWatchdog 超時 -> recoverStuckAdToMainScreen -> clearAdSessionTimer() (adStartTimeMs=0L) -> 超過舊 45s 窗口 (65s)
        val retField = AdCloserHandler::class.java.getDeclaredField("lastRedirectReturnTimeMs").apply { isAccessible = true }
        val blkTimeField = AdCloserHandler::class.java.getDeclaredField("lastBlacklistTimeMs").apply { isAccessible = true }
        retField.setLong(adCloser, System.currentTimeMillis() - 65_000L)
        blkTimeField.setLong(adCloser, System.currentTimeMillis() - 65_000L)
        adCloser.clearAdSessionTimer()

        assertTrue("180 秒內應持續偵測到近期重定向與黑名單狀態", adCloser.hasRecentRedirectWithin(180_000L))

        // 3. 模擬 handleInGameScan 冷卻結束重新切換回 AD_WATCHING
        val cooldownField = AutomationEngine::class.java.getDeclaredField("adWatchdogCooldownTicks").apply { isAccessible = true }
        val origCooldown = cooldownField.getInt(engine)
        try {
            cooldownField.setInt(engine, 1) // handleInGameScan 開頭遞減為 0
            val bmp = buildCoupangLetterboxedBitmap(includeProductDecoy = true)
            engine.transitionTo(EngineState.IN_GAME_SCAN, "掃描中...", "[巡邏] 掃描中")
            engine.handleInGameScan(service, bmp, buildCoupangOcr(withUpperX = false, withCoupangFooter = true))

            assertEquals("偵測到 Coupang OCR 特徵應立刻切換至 AD_WATCHING", EngineState.AD_WATCHING, engine.uiStatus.value.state)
            assertTrue("重進 AD_WATCHING 後 adStartTimeMs 應重新計時而非立刻逾時", adCloser.getElapsedSeconds() <= 2)
            assertTrue("跨 clearAdSessionTimer 與 65s 冷卻後仍須永久保留 (926, 393) 黑名單，杜絕 24 次循環重跳",
                adCloser.isInvalidOrBlacklisted(926, 393))
        } finally {
            cooldownField.setInt(engine, origCooldown)
            adCloser.clearRedirectMemory()
        }
    }
}
