package com.linewalk.autoad.test.v11588

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
import com.linewalk.autoad.engine.PatrolPhase
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
import io.mockk.coVerify
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

/**
 * v1.15.88 Robolectric 實機日誌回歸與模擬驗證測試 (依據 v1.15.87 實機日誌 20261115_153409 / 154823)：
 * 1. Meta 網頁預覽 (`Preview of ihg.com`) 嚴格鎖定頂部標題列 (<= 11.5% 螢幕高) 尋找 `[▶|]` / 幾何 X，
 *    不再因前段影片消耗 fastForwardSkipClicks=2 而失效、絕不穿透誤點網頁內假 X (`12.5%` / `19.4%`) 觸發瀏覽器，
 *    且強制發送 `forceEscapeAdPackage=true` 返回並在連續 2 次無效後立即升級 `ForceBringGameToFront`。
 * 2. `OcrRecognizer.isLoggedOutOrLoginScreen` 精準辨識《LINE 散步趣》登出／LINE 帳號登入與 OAuth 授權畫面，
 *    並嚴格排除 IHG 網頁預覽「登入 / 加入」、內嵌廣告與正常四大分頁主畫面。
 * 3. `AutomationEngine` 與 `AdCloserHandler` 偵測到《LINE 散步趣》登出畫面時立即停止巡邏 (`IDLE`) 且零點擊。
 * 4. `"廣告未成功啟動"` 返回 `IN_GAME_SCAN` 時施加 `adWatchdogCooldownTicks` 冷卻並保留空轉分頁進度，
 *    杜絕每 5~7 秒無限來回跳轉 `IN_GAME_SCAN <-> AD_WATCHING`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_88SimulationTest {

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

    private fun fillRectPixels(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        val l = left.coerceIn(0, bmp.width - 1)
        val t = top.coerceIn(0, bmp.height - 1)
        val r = right.coerceIn(l, bmp.width - 1)
        val b = bottom.coerceIn(t, bmp.height - 1)
        for (y in t..b) {
            for (x in l..r) {
                bmp.setPixel(x, y, color)
            }
        }
    }

    private fun drawCircleFastForwardSkip(bmp: Bitmap, cx: Int, cy: Int) {
        for (dy in -44..44) {
            for (dx in -44..44) {
                if (dx * dx + dy * dy <= 44 * 44) {
                    val x = (cx + dx).coerceIn(0, bmp.width - 1)
                    val y = (cy + dy).coerceIn(0, bmp.height - 1)
                    bmp.setPixel(x, y, Color.rgb(25, 25, 25))
                }
            }
        }
        // White ▶ triangle
        for (dy in -14..14) {
            val rowEnd = (-12 + (24 * (14 - kotlin.math.abs(dy))) / 14).coerceAtLeast(-12)
            for (dx in -12..rowEnd) {
                bmp.setPixel(cx + dx, cy + dy, Color.WHITE)
            }
        }
        // White | vertical bar
        fillRectPixels(bmp, cx + 10, cy - 14, cx + 14, cy + 14, Color.WHITE)
    }

    private fun drawCrossX(bmp: Bitmap, cx: Int, cy: Int, halfSize: Int, fg: Int, bg: Int) {
        fillRectPixels(bmp, cx - halfSize - 8, cy - halfSize - 8, cx + halfSize + 8, cy + halfSize + 8, bg)
        for (d in -halfSize..halfSize) {
            for (t in -2..2) {
                bmp.setPixel((cx + d).coerceIn(0, bmp.width - 1), (cy + d + t).coerceIn(0, bmp.height - 1), fg)
                bmp.setPixel((cx + d).coerceIn(0, bmp.width - 1), (cy - d + t).coerceIn(0, bmp.height - 1), fg)
            }
        }
    }

    @Test
    fun test01_metaWebPreviewRestrictsToTopHeaderAndForceEscapesWhenStuck() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640

        val metaPreviewOcr = listOf(
            OcrItem("Preview of ihg.com", Rect(374, 175, 704, 212)),
            OcrItem("透過洲際優悅會應用程式", Rect(200, 310, 520, 345)),
            OcrItem("檢視", Rect(860, 340, 960, 400)),
            OcrItem("×", Rect(940, 495, 965, 525)),
            OcrItem("立即预订", Rect(430, 2360, 650, 2410)),
            OcrItem("Ads served by Meta", Rect(140, 2540, 360, 2580))
        )

        // 場景 A：前段影片已耗盡 fastForwardSkipClicks = 2，頂部 [▶|] 位於 (984, 192 | 7.3%)，網頁內另有假 X (890, 330 | 12.5%) 與 OCR[×] (951, 511 | 19.4%)
        run {
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(42, 42, 42))
            }
            fillRectPixels(bmp, 120, 331, 960, 2260, Color.WHITE)
            drawCircleFastForwardSkip(bmp, 984, 192)
            drawCrossX(bmp, 890, 330, halfSize = 14, fg = Color.WHITE, bg = Color.rgb(30, 30, 30))

            val handler = createHandler(ctx)
            val mockService = createMockService("com.facebook.katana")
            handler.onEnterAdMode()
            val ffField = AdCloserHandler::class.java.getDeclaredField("fastForwardSkipClicks").apply { isAccessible = true }
            ffField.setInt(handler, 2)
            val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
            startField.setLong(handler, System.currentTimeMillis() - 10_000L)

            val res = handler.processAdTick(mockService, bmp, metaPreviewOcr)
            assertTrue("Meta 網頁預覽應點擊頂部標題列 [▶|]: $res", res is AdStepResult.ClickedCloseOrSkip)
            val clicked = res as AdStepResult.ClickedCloseOrSkip
            assertEquals("Meta預覽略過鈕(▶|)", clicked.sourceLabel)
            assertTrue("點擊座標必須位於頂部標題列 (cy <= 11.5%): ${clicked.bounds}", clicked.bounds.centerY() <= (sh * 0.115f).toInt())
        }

        // 場景 B：頂部標題列無有效按鈕，僅存網頁內假 X (890, 330 | 12.5%) 與假 OCR[×] (951, 511 | 19.4%) -> 嚴禁點擊假目標，直接強制 Back 2 次後立即 ForceBringGameToFront
        run {
            val bmpNoTopBtn = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(42, 42, 42))
            }
            fillRectPixels(bmpNoTopBtn, 120, 331, 960, 2260, Color.WHITE)
            drawCrossX(bmpNoTopBtn, 890, 330, halfSize = 14, fg = Color.WHITE, bg = Color.rgb(30, 30, 30))

            val handler = createHandler(ctx)
            val mockService = createMockService("com.facebook.katana")
            every { mockService.safeNavigateBack(any(), any(), eq(false)) } returns false
            every { mockService.safeNavigateBack(any(), any(), eq(true)) } returns true

            handler.onEnterAdMode()
            val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
            val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
            startField.setLong(handler, System.currentTimeMillis() - 10_000L)

            for (attempt in 1..2) {
                backTimeField.setLong(handler, 0L)
                val res = handler.processAdTick(mockService, bmpNoTopBtn, metaPreviewOcr)
                assertFalse("絕不可點擊網頁內假 X 或假 OCR[×]: $res", res is AdStepResult.ClickedCloseOrSkip)
                assertTrue("第 $attempt 次應觸發 forceEscapeAdPackage=true 返回: $res", res is AdStepResult.WatchdogBackTriggered)
                assertEquals("Meta預覽強制返回鍵", (res as AdStepResult.WatchdogBackTriggered).actionLabel)
            }
            verify(exactly = 2) { mockService.safeNavigateBack(any(), any(), eq(true)) }
            coVerify(exactly = 0) { mockService.clickRect(any(), any(), any()) }

            backTimeField.setLong(handler, 0L)
            val esc = handler.processAdTick(mockService, bmpNoTopBtn, metaPreviewOcr)
            assertTrue("Meta 預覽連續 2 次強制返回無效應立即升級 ForceBringGameToFront: $esc", esc is AdStepResult.ForceBringGameToFront)
            assertEquals("META_PREVIEW_STUCK", (esc as AdStepResult.ForceBringGameToFront).reason)
        }
    }

    @Test
    fun test02_isLoggedOutOrLoginScreenDetectsLogoutAndExcludesAdsAndMainScreen() {
        val ocr = OcrRecognizer()
        val sh = 2640

        // Positive cases
        assertTrue(
            "應偵測『使用 LINE 帳號登入』",
            ocr.isLoggedOutOrLoginScreen(
                listOf(
                    OcrItem("LINE 散步趣", Rect(320, 420, 760, 500)),
                    OcrItem("使用 LINE 帳號登入", Rect(240, 1820, 840, 1940))
                ),
                fgPkg = "com.linecorp.LGWKTW",
                screenHeight = sh
            )
        )
        assertTrue(
            "應偵測『帳號已登出 / 請重新登入』",
            ocr.isLoggedOutOrLoginScreen(
                listOf(
                    OcrItem("帳號已登出", Rect(340, 1100, 740, 1180)),
                    OcrItem("請重新登入", Rect(360, 1260, 720, 1340))
                ),
                fgPkg = "com.linecorp.LGWKTW",
                screenHeight = sh
            )
        )
        assertTrue(
            "應偵測 LINE App OAuth 授權畫面",
            ocr.isLoggedOutOrLoginScreen(
                listOf(
                    OcrItem("LINE 散步趣", Rect(300, 500, 780, 580)),
                    OcrItem("許可", Rect(380, 1900, 700, 2000))
                ),
                fgPkg = "jp.naver.line.android",
                screenHeight = sh
            )
        )

        // Negative cases (must NOT trigger)
        assertFalse(
            "Meta 網頁預覽含「登入 / 加入」絕不可誤判為散步趣登出",
            ocr.isLoggedOutOrLoginScreen(
                listOf(
                    OcrItem("Preview of ihg.com", Rect(374, 175, 704, 212)),
                    OcrItem("登入 / 加入", Rect(610, 890, 820, 960)),
                    OcrItem("Ads served by Meta", Rect(140, 2540, 360, 2580))
                ),
                fgPkg = "com.facebook.katana",
                screenHeight = sh
            )
        )
        assertFalse(
            "遊戲內嵌 Google Play 廣告就算出現『登入』也不得誤判為遊戲登出",
            ocr.isLoggedOutOrLoginScreen(
                listOf(
                    OcrItem("使用 LINE 帳號登入", Rect(240, 1200, 840, 1280)),
                    OcrItem("Google Play", Rect(320, 1800, 680, 1860)),
                    OcrItem("安裝", Rect(420, 2000, 660, 2100))
                ),
                fgPkg = "com.linecorp.LGWKTW",
                screenHeight = sh
            )
        )
        assertFalse(
            "正常散步趣四大分頁主畫面不得誤判為登出",
            ocr.isLoggedOutOrLoginScreen(
                listOf(
                    OcrItem("首頁", Rect(60, 290, 180, 350)),
                    OcrItem("步數", Rect(320, 290, 440, 350)),
                    OcrItem("時間", Rect(580, 290, 700, 350)),
                    OcrItem("遊戲", Rect(840, 290, 960, 350))
                ),
                fgPkg = "com.linecorp.LGWKTW",
                screenHeight = sh
            )
        )
    }

    @Test
    fun test03_engineAndAdCloserStopImmediatelyWhenLineWalkLoggedOut() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val logoutOcr = listOf(
            OcrItem("LINE 散步趣", Rect(320, 420, 760, 500)),
            OcrItem("使用 LINE 帳號登入", Rect(240, 1820, 840, 1940))
        )

        // 1. AdCloserHandler 應直接回傳登出停止信號，不重試「廣告未成功啟動」
        val handler = createHandler(ctx)
        val mockService = createMockService("com.linecorp.LGWKTW")
        handler.onEnterAdMode()
        val adRes = handler.processAdTick(mockService, bmp, logoutOcr)
        assertTrue("AdCloserHandler 應立即偵測登出並回傳 ReturnedToGame: $adRes", adRes is AdStepResult.ReturnedToGame)
        assertEquals("偵測到LINE散步趣已登出", (adRes as AdStepResult.ReturnedToGame).label)

        // 2. AutomationEngine.handleInGameScan 應立即停止巡邏並零點擊
        val engine = AutomationEngine.getInstance(ctx)
        val uiStatusField = AutomationEngine::class.java.getDeclaredField("_uiStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statusFlow = uiStatusField.get(engine) as kotlinx.coroutines.flow.MutableStateFlow<com.linewalk.autoad.engine.EngineUiStatus>
        try {
            engine.resetClickCircuitBreaker()
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中...")
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.IN_GAME_SCAN)

            engine.handleInGameScan(mockService, bmp, logoutOcr)

            assertFalse("偵測登出後必須停止巡邏 (isRunning=false)", engine.uiStatus.value.isRunning)
            assertEquals("偵測登出後必須切回 IDLE", EngineState.IDLE, engine.uiStatus.value.state)
            coVerify(exactly = 0) { mockService.clickRect(any(), any(), any()) }
        } finally {
            engine.stop("測試清理")
        }
    }

    @Test
    fun test04_adNotStartedCooldownBlocksImmediatePingPongIntoAdWatching() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val cooldownField = AutomationEngine::class.java.getDeclaredField("adWatchdogCooldownTicks").apply { isAccessible = true }
        val phaseField = AutomationEngine::class.java.getDeclaredField("currentPhase").apply { isAccessible = true }
        val origPhase = phaseField.get(engine)

        try {
            engine.resetClickCircuitBreaker()
            phaseField.set(engine, PatrolPhase.MAIN_TABS)
            engine.transitionTo(EngineState.IN_GAME_SCAN, "掃描中...", "[巡邏] 掃描中...")
            // 模擬剛由「廣告未成功啟動」退回並套用 5 ticks 冷卻
            cooldownField.setInt(engine, 5)

            val mockService = createMockService("com.linecorp.LGWKTW")
            val storeAdLikeOcr = listOf(
                OcrItem("Google Play", Rect(320, 1800, 680, 1860)),
                OcrItem("安裝", Rect(420, 2000, 660, 2100))
            )

            // 冷卻期間連續 2 個 Tick 都不得立刻切進 AD_WATCHING（杜絕 2 秒來回跳針）
            engine.handleInGameScan(mockService, bmp, storeAdLikeOcr)
            assertEquals("冷卻期間第 1 Tick 不得跳轉 AD_WATCHING", EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
            engine.handleInGameScan(mockService, bmp, storeAdLikeOcr)
            assertEquals("冷卻期間第 2 Tick 仍不得跳轉 AD_WATCHING", EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
            assertEquals(3, cooldownField.getInt(engine))
        } finally {
            phaseField.set(engine, origPhase)
            cooldownField.setInt(engine, 0)
            engine.stop("測試清理")
        }
    }
}
