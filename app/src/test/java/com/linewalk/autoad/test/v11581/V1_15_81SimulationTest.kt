package com.linewalk.autoad.test.v11581

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AdCloserHandler
import com.linewalk.autoad.engine.AdStepResult
import com.linewalk.autoad.engine.AutomationEngine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * v1.15.81 Robolectric 實機邏輯回歸與模擬驗證測試：
 * 針對 v1.15.80 日誌揭露之五大問題逐項驗證修復成果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_81SimulationTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx)
        } catch (_: Throwable) {
        }
    }

    /**
     * 直接將圓形底圖與雙對角線 (X) 筆畫寫入 Bitmap 像素陣列，確保不受 Robolectric ShadowCanvas no-op 影響。
     */
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
        val rSq = radius * radius
        for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(h - 1)) {
            for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(w - 1)) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= rSq) {
                    val onDiag1 = abs(dx - dy) <= halfStroke
                    val onDiag2 = abs(dx + dy) <= halfStroke
                    val inArmSpan = abs(dx) <= armHalfLen && abs(dy) <= armHalfLen
                    val pixel = if (inArmSpan && (onDiag1 || onDiag2)) xStrokeColor else circleColor
                    bmp.setPixel(x, y, pixel)
                }
            }
        }
    }

    @Test
    fun test01_dynamicSpotAndOverlayUtilityPackageFiltering() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. AppConfig 應將 DynamicSpot / SystemUI / 輸入法識別為常駐覆蓋元件
        assertTrue(AppConfig.isPassiveOverlayUtilityPackage("com.jamworks.dynamicspot"))
        assertTrue(AppConfig.isPassiveOverlayUtilityPackage("com.android.systemui"))
        assertTrue(AppConfig.isPassiveOverlayUtilityPackage("com.samsung.android.honeyboard"))
        assertFalse(AppConfig.isPassiveOverlayUtilityPackage("com.android.vending"))
        assertFalse(AppConfig.isPassiveOverlayUtilityPackage("com.android.chrome"))
        assertFalse(AppConfig.isPassiveOverlayUtilityPackage("com.linecorp.LGWKTW"))
        assertFalse(AppConfig.isPassiveOverlayUtilityPackage("com.android.settings"))

        // 2. evaluateForegroundPackage 遇見 com.jamworks.dynamicspot 絕不可回傳 isExternal = true
        val engine = AutomationEngine.getInstance(ctx)
        val guardDecision = engine.evaluateForegroundPackage("com.jamworks.dynamicspot")
        assertFalse("DynamicSpot 絕不可誤判為外部應用跳轉", guardDecision.isExternal)
        assertFalse(guardDecision.regularClicksAllowed)

        // 3. AutoAdAccessibilityService 遇見上層覆蓋焦點時，應穿透查找底層 TYPE_APPLICATION 視窗套件
        val fakeRootDynamicSpot = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { packageName } returns "com.jamworks.dynamicspot"
        }
        val fakeGameRoot = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { packageName } returns "com.linecorp.LGWKTW"
        }
        val fakeGameWindow = mockk<AccessibilityWindowInfo>(relaxed = true) {
            every { type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { isActive } returns true
            every { root } returns fakeGameRoot
        }
        val realService = Robolectric.buildService(AutoAdAccessibilityService::class.java).create().get()
        val svc = spyk(realService) {
            every { rootInActiveWindow } returns fakeRootDynamicSpot
            every { windows } returns listOf(fakeGameWindow)
        }
        assertEquals("com.linecorp.LGWKTW", svc.getForegroundPackageName())

        // onAccessibilityEvent 若收到 dynamicspot 通知事件，應略過並保留底層 App 包名
        val gameEvent = mockk<AccessibilityEvent>(relaxed = true) {
            every { packageName } returns "com.linecorp.LGWKTW"
        }
        val dynamicSpotEvent = mockk<AccessibilityEvent>(relaxed = true) {
            every { packageName } returns "com.jamworks.dynamicspot"
        }
        val realService2 = Robolectric.buildService(AutoAdAccessibilityService::class.java).create().get()
        val svcWithoutWindows = spyk(realService2) {
            every { rootInActiveWindow } returns fakeRootDynamicSpot
            every { windows } returns emptyList()
        }
        svcWithoutWindows.onAccessibilityEvent(gameEvent)
        svcWithoutWindows.onAccessibilityEvent(dynamicSpotEvent)
        assertEquals("com.linecorp.LGWKTW", svcWithoutWindows.getForegroundPackageName())
    }

    @Test
    fun test02_stagnationOcrDigestFiltersStatusBarTelemetryAndDuplicates() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)

        // 重現 v1.15.80 日誌 13:29:41 遭狀態列與指標位置佔滿之極端情況
        assertTrue(OcrRecognizer.isSystemTelemetryOcr("P:0/1 dX:0.0 dY:0.0"))
        assertTrue(OcrRecognizer.isSystemTelemetryOcr("dX:0.0"))
        assertFalse(OcrRecognizer.isSystemTelemetryOcr("立即領取獎勵"))

        val noisyOcr = listOf(
            // 狀態列 2% Y
            OcrItem("1:29 拿回k", Rect(100, 35, 320, 70)),
            OcrItem("1:29", Rect(100, 35, 190, 70)),
            OcrItem("拿回k", Rect(200, 35, 320, 70)),
            // 開發人員選項「指標位置」 5% Y
            OcrItem("P:0/1 dX:0.0 dY:0.0 Xv:0.0", Rect(30, 115, 500, 145)),
            OcrItem("P:0/1", Rect(30, 115, 140, 145)),
            OcrItem("dX:0.0", Rect(150, 115, 250, 145)),
            // 實際廣告畫面文字 + 其子元素拆字重複
            OcrItem("立即體驗超熱門遊戲", Rect(180, 900, 880, 980)),
            OcrItem("立即體驗", Rect(180, 900, 420, 980)),
            OcrItem("領取獎勵", Rect(400, 1800, 680, 1900))
        )

        val digest = engine.formatStagnationOcrDigest(noisyOcr, sw = 1080, sh = 2640, maxItems = 6)
        assertFalse("應排除頂部時間狀態列", digest.contains("1:29"))
        assertFalse("應排除指標位置 P:0/1", digest.contains("P:0/1"))
        assertFalse("應排除指標位置 dX:0.0", digest.contains("dX:0.0"))
        assertTrue("應保留下方真實畫面文案", digest.contains("立即體驗超熱門遊戲"))
        assertTrue("應保留下方真實按鈕文案", digest.contains("領取獎勵"))

        // 確認 detectAdCountdownSeconds 也排除系統指標位置與狀態列最頂端 (<2.8%)
        val ocr = OcrRecognizer()
        val fakeTopBarNumbers = listOf(
            OcrItem("15", Rect(960, 20, 1020, 55)), // y=1.4% 狀態列數字
            OcrItem("P:0/1 dX:12s", Rect(20, 120, 300, 155))
        )
        assertNull(ocr.detectAdCountdownSeconds(fakeTopBarNumbers, screenHeight = 2640, screenWidth = 1080))
    }

    @Test
    fun test03_linePromoCloseDetectorHitsRealWhiteAndDarkCornerXAndFastBackFallback() = runTest {
        val ocr = OcrRecognizer()

        // 場景 A：明亮白底上方標頭 (22:9 摺疊機 1080x2640)，深色圓底白 X 位於 (970, 300)
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(248, 248, 248))
        drawPixelCircleX(
            bmp = bmp,
            cx = 970,
            cy = 300,
            radius = 30,
            armHalfLen = 17,
            halfStroke = 3,
            circleColor = Color.rgb(55, 55, 55),
            xStrokeColor = Color.WHITE
        )

        val matched = ocr.findLineInHousePromoCloseButton(bmp, 1080, 2640)
        assertTrue(
            "應定位至真實幾何 X (970,300) 附近而非固定 (993,243)，實際=(${matched.centerX()},${matched.centerY()})",
            abs(matched.centerX() - 970) <= 12 && abs(matched.centerY() - 300) <= 12
        )

        // 場景 B：傳統 16:9 螢幕 (1080x1920)，淺灰圓底深色 X 位於 (980, 195)
        val bmp16 = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        bmp16.eraseColor(Color.rgb(40, 40, 40))
        drawPixelCircleX(
            bmp = bmp16,
            cx = 980,
            cy = 195,
            radius = 40,
            armHalfLen = 24,
            halfStroke = 3,
            circleColor = Color.rgb(240, 240, 240),
            xStrokeColor = Color.rgb(30, 30, 30)
        )
        val matched16 = ocr.findLineInHousePromoCloseButton(bmp16, 1080, 1920)
        assertTrue(
            "16:9 螢幕亦應精準追蹤大型深色 X (980,195)，實際=(${matched16.centerX()},${matched16.centerY()})",
            abs(matched16.centerX() - 980) <= 12 && abs(matched16.centerY() - 195) <= 12
        )

        // 場景 C：AdCloserHandler 遇見 LINE 宣傳廣告，第 1 次點 X，第 2 次應立即改發安全返回鍵且不附帶座標
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfg = AppConfig(ctx)
        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } returns "com.linecorp.LGWKTW"
            every { safeNavigateBack(any(), any(), any()) } returns true
            every { getSafeRootNode() } returns null
        }
        val mockNodeInspector = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
        }
        val handler = AdCloserHandler(ocr, mockNodeInspector, TemplateMatcher(), cfg)
        handler.onEnterAdMode()

        val promoOcr = listOf(OcrItem("還沒有邀請好友 實在太可惜了！", Rect(100, 800, 900, 900)))
        val res1 = handler.processAdTick(mockService, bmp, promoOcr)
        assertTrue(res1 is AdStepResult.ClickedCloseOrSkip)
        assertTrue((res1 as AdStepResult.ClickedCloseOrSkip).sourceLabel.contains("關閉(X)[第1次]"))
        assertFalse(res1.bounds.isEmpty)

        val res2 = handler.processAdTick(mockService, bmp, promoOcr)
        assertTrue(res2 is AdStepResult.ClickedCloseOrSkip)
        assertTrue("第 2 次起即應改發安全返回鍵縮短耗時", (res2 as AdStepResult.ClickedCloseOrSkip).sourceLabel.contains("安全返回鍵[第2次]"))
        assertTrue("系統返回鍵結果應傳空矩形避免輸出假座標", res2.bounds.isEmpty)
        verify(exactly = 1) { mockService.safeNavigateBack(minIntervalMs = 600L, allowInGame = true, forceEscapeAdPackage = false) }
    }

    @Test
    fun test04_staleCountdownUnlocksAfterFreezeAndCutoff() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfg = AppConfig(ctx).apply { maxAdWaitSeconds = 65 }
        val ocr = OcrRecognizer()
        val mockNodeInspector = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
        }
        val mockTemplate = mockk<TemplateMatcher>(relaxed = true) {
            coEvery { detectCornerGeometricX(any(), any(), any(), any()) } returns null
            coEvery { detectCornerFastForwardSkip(any(), any()) } returns null
        }
        val handler = AdCloserHandler(ocr, mockNodeInspector, mockTemplate, cfg)
        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } returns "com.google.android.gms"
            every { safeNavigateBack(any(), any(), any()) } returns true
            every { getSafeRootNode() } returns null
        }
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        val stuckCountdownOcr = listOf(OcrItem("18秒後可獲獎勵", Rect(80, 180, 420, 240)))

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }

        // 1. 播放至第 10 秒：正常倒數中必須回傳 StillCountingDown
        startField.setLong(handler, System.currentTimeMillis() - 10_000L)
        val early = handler.processAdTick(mockService, bmp, stuckCountdownOcr)
        assertTrue("正常倒數第 10 秒應保持等待: $early", early is AdStepResult.StillCountingDown)

        // 2. 倒數畫面完全靜止卡在「18秒」連續 10 次以上且已播 36 秒 -> 必須解除封鎖不再回傳 StillCountingDown
        startField.setLong(handler, System.currentTimeMillis() - 36_000L)
        repeat(9) {
            handler.processAdTick(mockService, bmp, stuckCountdownOcr)
        }
        val unlocked = handler.processAdTick(mockService, bmp, stuckCountdownOcr)
        assertFalse(
            "倒數卡死連續 10 Tick 後必須解除封鎖放行脫困，實際=$unlocked",
            unlocked is AdStepResult.StillCountingDown
        )

        // 3. 診斷快照欄位應包含 load= 與 cd= 資訊
        val diag = handler.getDiagnosticsSnapshotFields()
        assertTrue(diag.contains("load="))
        assertTrue(diag.contains("cd=18"))
    }

    @Test
    fun test05_clickCircuitBreakerResetsAcrossTimeAndAdTransitions() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { navigateBack(any()) } returns true
        }
        val rect = Rect(300, 1400, 780, 1520)

        engine.resetClickCircuitBreaker()

        // 模擬相隔 25 秒、跨不同彈窗點擊同名「OCR[關閉]」不可跨場景累算到第 3 次
        assertFalse(engine.checkClickCircuitBreaker(mockService, "OCR[關閉]", rect))
        assertFalse(engine.checkClickCircuitBreaker(mockService, "OCR[關閉]", rect))
        assertEquals(2, engine.sameTargetClickCount)

        // 超過 20 秒間隔再點同名按鈕應重新從 1 起算，不誤觸斷路器
        engine.lastTargetClickTimeMs = System.currentTimeMillis() - 25_000L
        assertFalse(engine.checkClickCircuitBreaker(mockService, "OCR[關閉]", rect))
        assertEquals(1, engine.sameTargetClickCount)

        // 同一場景短時間連點 3 次無響應，應正常觸發斷路器並發送 BACK
        assertFalse(engine.checkClickCircuitBreaker(mockService, "OCR[關閉]", rect))
        assertTrue(engine.checkClickCircuitBreaker(mockService, "OCR[關閉]", rect))
        verify(exactly = 1) { mockService.navigateBack(false) }
    }
}
