package com.linewalk.autoad.test.v11585

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * v1.15.85 Robolectric 實機邏輯回歸與模擬驗證測試：
 * 驗證 v1.15.84 實機日誌與 AHEALTH 結束卡片 / Google Play 重定向循環截圖揭露之 4 項修復：
 * 1. 右上角 `[Google Play ×]` 膠囊內 `'y'`/`'a'` 筆畫不受幾何 X 誤點；`×` 觸發首次重定向後整條左側膠囊區封鎖，杜絕 `Play=2` 二次跳轉。
 * 2. 底部問卷按鈕列 `✓ Interested` / `✕ Not interested` 中的 `✕` 不得被全螢幕關閉 OCR 或角落 ASCII `X` 誤點。
 * 3. `isEndCard` 系統返回鍵連續被內嵌廣告攔截 2 次後，第 3 次升級為 `ForceBringGameToFront("END_CARD_BACK_SWALLOWED")`。
 * 4. `AutomationEngine.handleAdWatching` 收到 `ForceBringGameToFront`（前景仍為 `com.linecorp.LGWKTW` 內嵌廣告）時，
 *    統一走 `pressHome -> CLEAR_TOP -> CLEAR_TASK` 清棧恢復主畫面，而非僅呼叫無效的 `bringAppToFront`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_85SimulationTest {

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

    private fun createHandler(ctx: Context): AdCloserHandler {
        val nodeInspector = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
            every { findNodeByKeywords(any(), any(), any(), any(), any()) } returns null
        }
        return AdCloserHandler(
            ocrRecognizer = spyk(OcrRecognizer()),
            nodeInspector = nodeInspector,
            templateMatcher = TemplateMatcher(),
            config = AppConfig(ctx)
        )
    }

    private fun fillRectPixels(bmp: Bitmap, rect: Rect, color: Int) {
        val left = rect.left.coerceIn(0, bmp.width - 1)
        val top = rect.top.coerceIn(0, bmp.height - 1)
        val right = rect.right.coerceIn(left, bmp.width - 1)
        val bottom = rect.bottom.coerceIn(top, bmp.height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                bmp.setPixel(x, y, color)
            }
        }
    }

    private fun drawPixelX(
        bmp: Bitmap,
        cx: Int,
        cy: Int,
        armHalfLen: Int,
        halfStroke: Int,
        color: Int
    ) {
        for (t in -armHalfLen..armHalfLen) {
            for (dy in -halfStroke..halfStroke) {
                for (dx in -halfStroke..halfStroke) {
                    val x1 = (cx + t + dx).coerceIn(0, bmp.width - 1)
                    val y1 = (cy + t + dy).coerceIn(0, bmp.height - 1)
                    bmp.setPixel(x1, y1, color)

                    val x2 = (cx + t + dx).coerceIn(0, bmp.width - 1)
                    val y2 = (cy - t + dy).coerceIn(0, bmp.height - 1)
                    bmp.setPixel(x2, y2, color)
                }
            }
        }
    }

    private fun buildAhealthEndCardBitmap(sw: Int = 1080, sh: Int = 2340): Bitmap {
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(34, 34, 34))
        }
        // 右上角 [Google Play ×] 深色膠囊 (69%..94%, 9.4%..12.6%)
        fillRectPixels(bmp, Rect(740, 220, 1028, 294), Color.rgb(22, 22, 22))
        // 在 "Google Play" 字尾 'y' 位置 (922, 258) 與右側 '×' 位置 (994, 258) 皆繪製高對比斜線交叉筆畫
        drawPixelX(bmp, cx = 922, cy = 258, armHalfLen = 14, halfStroke = 2, color = Color.WHITE)
        drawPixelX(bmp, cx = 994, cy = 258, armHalfLen = 15, halfStroke = 2, color = Color.WHITE)
        return bmp
    }

    private fun buildAhealthEndCardOcr(): List<OcrItem> {
        return listOf(
            OcrItem("Google Play", Rect(745, 224, 948, 286)),
            OcrItem("AHEALTH: Get Healthy Get Paid", Rect(96, 1200, 984, 1340)),
            OcrItem("安裝", Rect(415, 1450, 665, 1590)),
            OcrItem("✓", Rect(185, 1935, 220, 1975)),
            OcrItem("Interested", Rect(245, 1935, 460, 1975)),
            OcrItem("✕", Rect(585, 1935, 620, 1975)),
            OcrItem("Not interested", Rect(632, 1935, 920, 1975))
        )
    }

    @Test
    fun test01_googlePlayCapsulePillBlocksTextStrokesAndLeftwardRedirectStrip() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handler = createHandler(ctx)
        val mockService = createMockService("com.linecorp.LGWKTW")
        val bmp = buildAhealthEndCardBitmap()
        val ocrItems = buildAhealthEndCardOcr()

        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val lastBackField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
        val lastRedirectReturnField = AdCloserHandler::class.java.getDeclaredField("lastRedirectReturnTimeMs").apply { isAccessible = true }

        // 1. 首次在 End Card 上執行幾何 X 掃描 (elapsed=40s, 重定向數=0)：不得誤點 "Google Play" 文字內的 'y' (922, 258)
        handler.onEnterAdMode()
        startField.setLong(handler, System.currentTimeMillis() - 40_000L)
        val firstTick = handler.processAdTick(mockService, bmp, ocrItems)
        if (firstTick is AdStepResult.ClickedCloseOrSkip) {
            assertTrue(
                "不得點擊 'Google Play' 文字框內的 'y' (922, 258)，實際=${firstTick.bounds}",
                firstTick.bounds.centerX() > 955 || abs(firstTick.bounds.centerY() - 258) > 40
            )
        }

        // 2. 模擬 (994, 258) 確實點擊並觸發首次跳轉商店，隨後退回廣告頁 (redirectCount=1)
        handler.markRecentRedirect(android.graphics.Point(994, 258), fromExternalApp = true)
        handler.onEnterAdMode(preserveRedirectState = true)
        startField.setLong(handler, System.currentTimeMillis() - 50_000L)
        lastRedirectReturnField.setLong(handler, 0L) // 已過 3500ms 節流
        lastBackField.setLong(handler, System.currentTimeMillis()) // 模擬當下僅剩 GeoX 掃描路徑

        val secondTick = handler.processAdTick(mockService, bmp, ocrItems)
        if (secondTick is AdStepResult.ClickedCloseOrSkip) {
            val hitsTopRightPill = secondTick.bounds.centerX() in 734..1060 && secondTick.bounds.centerY() in 200..315
            assertFalse(
                "右上角 [Google Play ×] 曾跳轉後，整條左側膠囊文字區 ('y' 在 922,258) 必須封鎖，不得二次跳轉: ${secondTick.sourceLabel} ${secondTick.bounds}",
                hitsTopRightPill
            )
        }
    }

    @Test
    fun test02_surveyNotInterestedRowCloseSymbolNeverClickedByStep7OrGeoX() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handler = createHandler(ctx)
        val mockService = createMockService("com.linecorp.LGWKTW")
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(34, 34, 34))
        }
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }

        // A. 僅有底部問卷列 `✕` + `Not interested` 時，Step 7 / 7A2 絕不可把 `✕` 當作關閉鈕
        handler.onEnterAdMode()
        startField.setLong(handler, System.currentTimeMillis() - 15_000L)
        val surveyOnlyOcr = listOf(
            OcrItem("✓", Rect(185, 1935, 220, 1975)),
            OcrItem("Interested", Rect(245, 1935, 460, 1975)),
            OcrItem("✕", Rect(585, 1935, 620, 1975)),
            OcrItem("Not interested", Rect(632, 1935, 920, 1975)),
            OcrItem("✕ Not interested", Rect(580, 1935, 920, 1975))
        )
        val surveyResult = handler.processAdTick(mockService, bmp, surveyOnlyOcr)
        if (surveyResult is AdStepResult.ClickedCloseOrSkip) {
            assertFalse(
                "不得誤點問卷列 `✕ Not interested` (y≈1955): ${surveyResult.sourceLabel} ${surveyResult.bounds}",
                surveyResult.bounds.centerY() > 1800
            )
        }

        // B. 當右上角確實有真實「關閉」按鈕時，應略過問卷 `✕` 並精準點擊右上角「關閉」
        val withRealCloseOcr = listOf(
            OcrItem("✕", Rect(585, 1935, 620, 1975)),
            OcrItem("Not interested", Rect(632, 1935, 920, 1975)),
            OcrItem("關閉", Rect(930, 115, 1020, 170))
        )
        val realCloseResult = handler.processAdTick(mockService, bmp, withRealCloseOcr)
        assertTrue("應略過問卷 ✕ 並命中右上角真實關閉鈕: $realCloseResult", realCloseResult is AdStepResult.ClickedCloseOrSkip)
        val clickedBounds = (realCloseResult as AdStepResult.ClickedCloseOrSkip).bounds
        assertTrue("應點擊右上角關閉 (y < 300), 實際=$clickedBounds", clickedBounds.centerY() < 300)
    }

    @Test
    fun test03_stuckEndCardSwallowedBackEscalatesToForceBringGameToFront() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handler = createHandler(ctx)
        val mockService = createMockService("com.linecorp.LGWKTW")
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(34, 34, 34))
        }
        val endCardOcr = listOf(
            OcrItem("Google Play", Rect(745, 224, 948, 286)),
            OcrItem("AHEALTH: Get Healthy Get Paid", Rect(96, 1200, 984, 1340)),
            OcrItem("安裝", Rect(415, 1450, 665, 1590))
        )

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
        val fallbackTimeField = AdCloserHandler::class.java.getDeclaredField("lastFallbackCornerTimeMs").apply { isAccessible = true }

        startField.setLong(handler, System.currentTimeMillis() - 48_000L)

        // 第 1 次與第 2 次嘗試「廣告結束卡片安全返回鍵」
        for (attempt in 1..2) {
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
            val res = handler.processAdTick(mockService, bmp, endCardOcr)
            assertTrue("第 $attempt 次應先嘗試結束卡片安全返回鍵: $res", res is AdStepResult.WatchdogBackTriggered)
            assertEquals("廣告結束卡片安全返回鍵", (res as AdStepResult.WatchdogBackTriggered).actionLabel)
        }

        // 第 3 次 Back 仍卡在同一張 End Card 上 -> 不再空送第 3 次 Back，直接升級 ForceBringGameToFront
        backTimeField.setLong(handler, 0L)
        fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
        val escalateRes = handler.processAdTick(mockService, bmp, endCardOcr)
        assertTrue(
            "連續 2 次 End Card Back 遭吞噬後第 3 次必須升級 ForceBringGameToFront: $escalateRes",
            escalateRes is AdStepResult.ForceBringGameToFront
        )
        assertTrue(
            "升級原因應標記 END_CARD_BACK_SWALLOWED: $escalateRes",
            (escalateRes as AdStepResult.ForceBringGameToFront).reason.contains("END_CARD_BACK_SWALLOWED")
        )
        verify(exactly = 2) { mockService.safeNavigateBack(any(), any(), any()) }
    }

    @Test
    fun test04_engineHandlesForceBringGameToFrontViaPressHomeClearTopAndClearTask() = runTest {
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
            val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
            val swallowedField = AdCloserHandler::class.java.getDeclaredField("swallowedEndCardBackAttempts").apply { isAccessible = true }

            adCloser.onEnterAdMode()
            // elapsed = 50s (< 65s 全域超時，但 >= 42s End Card 升級門檻)
            startField.setLong(adCloser, System.currentTimeMillis() - 50_000L)
            backTimeField.setLong(adCloser, 0L)
            swallowedField.setInt(adCloser, 2)
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")

            val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(34, 34, 34))
            }
            val endCardOcr = listOf(
                OcrItem("Google Play", Rect(745, 224, 948, 286)),
                OcrItem("AHEALTH: Get Healthy Get Paid", Rect(96, 1200, 984, 1340)),
                OcrItem("安裝", Rect(415, 1450, 665, 1590))
            )

            val mockService = createMockService("com.linecorp.LGWKTW")
            coEvery { mockService.captureScreenBitmap() } answers {
                Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(34, 34, 34)) }
            }

            engine.handleAdWatching(mockService, bmp, endCardOcr)

            // 即使前景套件仍是 com.linecorp.LGWKTW，ForceBringGameToFront 不得只呼叫無效 bringAppToFront，必須清棧逃脫！
            verify(atLeast = 1) { mockService.pressHome() }
            verify(atLeast = 1) { mockService.forceResetAppTaskToFront("com.linecorp.LGWKTW", false) }
            verify(atLeast = 1) { mockService.forceResetAppTaskToFront("com.linecorp.LGWKTW", true) }
            assertEquals("ForceBringGameToFront 清棧後計時器應歸零", 0, adCloser.getElapsedSeconds())
            assertEquals(EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
        } finally {
            ocrField.set(engine, origOcr)
        }
    }
}
