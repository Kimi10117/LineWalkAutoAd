package com.linewalk.autoad.test.v11586

import android.content.Context
import android.graphics.Bitmap
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.15.86 Robolectric 實機邏輯回歸與模擬驗證測試：
 * 驗證 v1.15.85 雙機實機日誌（SM-F731N Cash Giraffe + SM-S7210 AHEALTH）與截圖揭露之 4 項核心修復：
 * 1. Cash Giraffe 底部左下角圓形倒數（如 `41`、`36)` @ 8%, 96%）可正確識別，且嚴禁執行破壞性 `Reveal Swipe`。
 * 2. 左上角 `[▶| Google Play]` 優先點擊左側 `[▶|]` 圖示熱區；兩層式 Play 商店 Half-Sheet 返回時補發第 2 次 Back，
 *    並清除過期 `lastClickedAdLabel` 且返回後不觸發 `Reveal Swipe`。
 * 3. AHEALTH 右上角 `[Google Play ×]`：首次點擊標記為 `右上角[Google Play ×]關閉鈕` 走 2.0s + 2x Back 商店緩衝解鎖，
 *    冷卻後二次點擊切回 `右上角幾何X`，若仍跳轉商店則永久黑名單並跨 `onEnterAdMode()` 保留 45s。
 * 4. `isLineWalkMainScreenVisible` 拒認含全螢幕廣告特徵之畫面；`IN_GAME_SCAN` 步驟 D3C 於冷卻或商店廣告期間絕不誤點。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_86SimulationTest {

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
            coEvery { performSwipe(any(), any(), any(), any(), any()) } returns true
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

    private fun drawPixelX(bmp: Bitmap, cx: Int, cy: Int, armHalfLen: Int, halfStroke: Int, color: Int) {
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
        fillRectPixels(bmp, Rect(740, 220, 1028, 294), Color.rgb(22, 22, 22))
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
    fun test01_cashGiraffeBottomLeftCountdownDetectedAndRevealSwipeDisabled() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ocrRecognizer = OcrRecognizer()
        val handler = createHandler(ctx)
        val mockService = createMockService("com.linecorp.LGWKTW")
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(190, 235, 160))
        }

        // 1. 驗證底部左下角 (x ≈ 8.0%, y ≈ 96.2%) 純數字 `41` 與圓弧粘連 `36)` 可正確識別
        val bottomOcr41 = listOf(
            OcrItem("Drag to adjust kick power and angle.", Rect(140, 260, 940, 480)),
            OcrItem("41", Rect(66, 2515, 108, 2565))
        )
        assertEquals(41, ocrRecognizer.detectAdCountdownSeconds(bottomOcr41, sh, sw))

        val bottomOcr36Arc = listOf(
            OcrItem("Cash Giraffe", Rect(110, 980, 970, 1110)),
            OcrItem("$0", Rect(360, 1560, 720, 1780)),
            OcrItem("Try Again!", Rect(290, 1820, 790, 1970)),
            OcrItem("CLAIM!", Rect(280, 2190, 800, 2350)),
            OcrItem("36)", Rect(66, 2515, 114, 2565))
        )
        assertEquals(36, ocrRecognizer.detectAdCountdownSeconds(bottomOcr36Arc, sh, sw))

        // 中央區域非角落數字仍不得誤判為倒數秒數
        val centerNumOnly = listOf(
            OcrItem("36)", Rect(490, 2480, 560, 2540))
        )
        assertNull(ocrRecognizer.detectAdCountdownSeconds(centerNumOnly, sh, sw))

        // 2. 有底部倒數 `41` 時必須回傳 StillCountingDown 且絕不執行 SwipedToReveal
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 10_000L)

        val tick1 = handler.processAdTick(mockService, bmp, bottomOcr41)
        assertTrue("有左下角倒數時應耐心倒數而非滑動試玩: $tick1", tick1 is AdStepResult.StillCountingDown)
        assertEquals(41, (tick1 as AdStepResult.StillCountingDown).remainingSec)

        // 3. 即使倒數短暫遺失，於 "Drag to adjust..." 或 "CLAIM!" / "$0" 畫面也嚴禁觸發 SwipedToReveal
        val dragWithoutCountdown = listOf(
            OcrItem("Drag to adjust kick power and angle.", Rect(140, 260, 940, 480))
        )
        val tick2 = handler.processAdTick(mockService, bmp, dragWithoutCountdown)
        assertFalse("Drag 試玩畫面不得觸發破壞性 SwipedToReveal: $tick2", tick2 is AdStepResult.SwipedToReveal)

        val claimWithoutCountdown = listOf(
            OcrItem("Cash Giraffe", Rect(110, 980, 970, 1110)),
            OcrItem("$0", Rect(360, 1560, 720, 1780)),
            OcrItem("CLAIM!", Rect(280, 2190, 800, 2350))
        )
        val tick3 = handler.processAdTick(mockService, bmp, claimWithoutCountdown)
        assertFalse("CLAIM! End Card 不得觸發 SwipedToReveal: $tick3", tick3 is AdStepResult.SwipedToReveal)
        coVerify(exactly = 0) { mockService.performSwipe(any(), any(), any(), any(), any()) }
    }

    @Test
    fun test02_topLeftGooglePlayPillClicksLeftIconClearsLabelUsesDualBackAndNeverPostSwipes() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()

        val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
        val adCloser = adCloserField.get(engine) as AdCloserHandler
        adCloser.onEnterAdMode()
        adCloser.clearRedirectMemory()

        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val lastPillClickField = AdCloserHandler::class.java.getDeclaredField("lastGooglePlayPillClickTimeMs").apply { isAccessible = true }
        startField.setLong(adCloser, System.currentTimeMillis() - 8_000L)

        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 30, 30))
        }
        val pillOcr = listOf(
            OcrItem("Google Play", Rect(148, 220, 318, 286)),
            OcrItem("Cash Giraffe", Rect(110, 980, 970, 1110)),
            OcrItem("CLAIM!", Rect(280, 2190, 800, 2350))
        )

        val mockService = createMockService("com.linecorp.LGWKTW")
        val pillClick = adCloser.processAdTick(mockService, bmp, pillOcr)
        assertTrue("應命中左上角 [▶| Google Play] 膠囊: $pillClick", pillClick is AdStepResult.ClickedCloseOrSkip)
        val clicked = pillClick as AdStepResult.ClickedCloseOrSkip
        assertEquals("左上角[▶| Google Play]略過鈕", clicked.sourceLabel)
        // 點擊點必須落在左側圖示熱區，而非 `"Google Play"` 中間靠右區域
        assertTrue("點擊 X (${clicked.bounds.centerX()}) 應偏向左側圖示熱區 (< 180)", clicked.bounds.centerX() < 180)
        assertEquals("左上角[▶| Google Play]略過鈕", adCloser.getLastClickedAdLabel())

        // 模擬跳轉 Google Play Half-Sheet (com.android.vending)：首次 Back 後仍在商店底層，須補發第 2 次 Back
        var backCalls = 0
        val playGuardService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } answers {
                when (backCalls) {
                    0 -> "com.android.vending"
                    1 -> "com.android.vending"
                    else -> "com.linecorp.LGWKTW"
                }
            }
            every { safeNavigateBack(any(), any(), any()) } answers {
                backCalls++
                true
            }
            every { bringAppToFront(any()) } returns true
        }
        engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
        val legitStartField = AutomationEngine::class.java.getDeclaredField("legitSkipRedirectStartMs").apply { isAccessible = true }
        val legitPkgField = AutomationEngine::class.java.getDeclaredField("legitSkipRedirectPkg").apply { isAccessible = true }
        val extTicksField = AutomationEngine::class.java.getDeclaredField("externalAppTicks").apply { isAccessible = true }
        extTicksField.setInt(engine, 0)
        legitPkgField.set(engine, "com.android.vending")
        legitStartField.setLong(engine, System.currentTimeMillis() - 2_100L)

        engine.handleExternalAppGuard(playGuardService, "com.android.vending")

        assertEquals("Play Half-Sheet + Store 兩層架構應連續補發 2 次 safeNavigateBack", 2, backCalls)
        assertNull("從預期略過返回後必須清除過期 lastClickedAdLabel", adCloser.getLastClickedAdLabel())

        // 返回後即使經過數秒、膠囊暫時消失，也不得再次觸發破壞性 SwipedToReveal
        startField.setLong(adCloser, System.currentTimeMillis() - 12_000L)
        lastPillClickField.setLong(adCloser, System.currentTimeMillis() - 5_000L)
        val afterReturn = adCloser.processAdTick(mockService, bmp, listOf(OcrItem("Cash Giraffe", Rect(110, 980, 970, 1110))))
        assertFalse("點擊過 [▶| Google Play] 返回後絕不可執行 SwipedToReveal: $afterReturn", afterReturn is AdStepResult.SwipedToReveal)
        coVerify(exactly = 0) { mockService.performSwipe(any(), any(), any(), any(), any()) }
    }

    @Test
    fun test03_twoStageGooglePlayCloseUnlockAndPermanentBlacklistAcrossReentry() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()

        val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
        val adCloser = adCloserField.get(engine) as AdCloserHandler
        adCloser.onEnterAdMode()
        adCloser.clearRedirectMemory()

        val bmp = buildAhealthEndCardBitmap()
        val ocrItems = buildAhealthEndCardOcr()
        val mockService = createMockService("com.linecorp.LGWKTW")

        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val retTimeField = AdCloserHandler::class.java.getDeclaredField("lastRedirectReturnTimeMs").apply { isAccessible = true }
        val lastBackField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }

        // 1. 首次點擊右上角 [Google Play ×] 的 `×` (994, 258)：標記為 "右上角[Google Play ×]關閉鈕"
        startField.setLong(adCloser, System.currentTimeMillis() - 40_000L)
        val click1 = adCloser.processAdTick(mockService, bmp, ocrItems)
        assertTrue("第 1 次應命中 [Google Play ×] 右側 ×: $click1", click1 is AdStepResult.ClickedCloseOrSkip)
        val c1 = click1 as AdStepResult.ClickedCloseOrSkip
        assertEquals("右上角[Google Play ×]關閉鈕", c1.sourceLabel)
        assertTrue("點擊座標應為右側 × (cx > 960): ${c1.bounds}", c1.bounds.centerX() > 960)

        // 2. 模擬跳轉 Google Play 並走兩段 Back 安全返回（不寫入永久失敗重定向，僅設 3.5s 冷卻並清空 label）
        var backCalls = 0
        val playGuardService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } answers { if (backCalls < 2) "com.android.vending" else "com.linecorp.LGWKTW" }
            every { safeNavigateBack(any(), any(), any()) } answers {
                backCalls++
                true
            }
            every { bringAppToFront(any()) } returns true
        }
        engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
        val legitStartField = AutomationEngine::class.java.getDeclaredField("legitSkipRedirectStartMs").apply { isAccessible = true }
        val legitPkgField = AutomationEngine::class.java.getDeclaredField("legitSkipRedirectPkg").apply { isAccessible = true }
        val extTicksField = AutomationEngine::class.java.getDeclaredField("externalAppTicks").apply { isAccessible = true }
        extTicksField.setInt(engine, 0)
        legitPkgField.set(engine, "com.android.vending")
        legitStartField.setLong(engine, System.currentTimeMillis() - 2_100L)

        engine.handleExternalAppGuard(playGuardService, "com.android.vending")
        assertEquals(2, backCalls)
        assertNull(adCloser.getLastClickedAdLabel())

        // 3. 超過 3.5s 返回冷卻後，允許第 2 次點擊已解鎖的 (994, 258)（此次標籤為一般 "右上角幾何X"）
        retTimeField.setLong(adCloser, System.currentTimeMillis() - 4_000L)
        lastBackField.setLong(adCloser, System.currentTimeMillis())
        startField.setLong(adCloser, System.currentTimeMillis() - 46_000L)
        val click2 = adCloser.processAdTick(mockService, bmp, ocrItems)
        assertTrue("返還後冷卻期過應允許二次點擊 (994, 258) 關閉已解鎖 End Card: $click2", click2 is AdStepResult.ClickedCloseOrSkip)
        assertEquals("右上角幾何X", (click2 as AdStepResult.ClickedCloseOrSkip).sourceLabel)

        // 4. 若第 2 次點擊依然誤跳商店，則永久封鎖 (994, 258)，且 45s 內重新進入 AD_WATCHING 也保留黑名單
        adCloser.markRecentRedirect(Point(994, 258), fromExternalApp = true)
        assertTrue("第 2 次仍跳轉必須封鎖 (994, 258)", adCloser.isInvalidOrBlacklisted(994, 258))
        assertTrue("45 秒內應回傳 hasRecentRedirectWithin(45_000L) == true", adCloser.hasRecentRedirectWithin(45_000L))

        adCloser.onEnterAdMode(preserveRedirectState = adCloser.hasRecentRedirectWithin(45_000L))
        assertTrue("跨 onEnterAdMode 重進後仍須保留 (994, 258) 黑名單", adCloser.isInvalidOrBlacklisted(994, 258))
        adCloser.clearRedirectMemory()
    }

    @Test
    fun test04_mainScreenRejectsFullScreenAdsAndInGameScanStepD3CGUardBlocksLoops() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ocrRecognizer = OcrRecognizer()
        val templateMatcher = TemplateMatcher()
        val sw = 1080
        val sh = 2340
        val mainScreenGreen = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 175, 110))
        }

        // 1. 即使背景帶綠或有部分字樣，只要存在 Google Play / Not interested / CLAIM!，isLineWalkMainScreenVisible 一律為 false
        val baseGameOcr = listOf(
            OcrItem("首頁", Rect(60, 2150, 160, 2220)),
            OcrItem("好友", Rect(460, 2150, 560, 2220)),
            OcrItem("集點", Rect(860, 2150, 960, 2220))
        )
        assertFalse(
            "含 Google Play 不可視為散步趣主畫面",
            ocrRecognizer.isLineWalkMainScreenVisible(mainScreenGreen, baseGameOcr + OcrItem("Google Play", Rect(745, 224, 948, 286)), templateMatcher)
        )
        assertFalse(
            "含 Not interested 不可視為散步趣主畫面",
            ocrRecognizer.isLineWalkMainScreenVisible(mainScreenGreen, baseGameOcr + OcrItem("Not interested", Rect(632, 1935, 920, 1975)), templateMatcher)
        )
        assertFalse(
            "含 CLAIM! 不可視為散步趣主畫面",
            ocrRecognizer.isLineWalkMainScreenVisible(mainScreenGreen, baseGameOcr + OcrItem("CLAIM!", Rect(280, 2000, 800, 2150)), templateMatcher)
        )

        // 2. IN_GAME_SCAN 在 adWatchdogCooldownTicks > 0 且面對 AHEALTH 廣告時，D3C 絕不可繞過誤點 (994, 258)
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        val cooldownField = AutomationEngine::class.java.getDeclaredField("adWatchdogCooldownTicks").apply { isAccessible = true }
        val origCooldown = cooldownField.getInt(engine)
        cooldownField.setInt(engine, 4)

        try {
            val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
            val adCloser = adCloserField.get(engine) as AdCloserHandler
            adCloser.clearRedirectMemory()

            val bmp = buildAhealthEndCardBitmap(sw, sh)
            val ocrItems = buildAhealthEndCardOcr()
            val mockService = createMockService("com.linecorp.LGWKTW")

            engine.transitionTo(EngineState.IN_GAME_SCAN, "掃描中...", "[巡邏] 掃描中")
            engine.handleInGameScan(mockService, bmp, ocrItems)

            coVerify(exactly = 0) { mockService.clickRect(any(), any(), any()) }
            assertEquals("冷卻期間不應立即跳轉或執行 D3C 點擊", EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)

            // 3. 冷卻歸零時，若仍偵測到商店/廣告畫面且 45s 內曾重定向，切換回 AD_WATCHING 時須保留 (994, 258) 黑名單
            adCloser.onEnterAdMode()
            adCloser.markRecentRedirect(Point(994, 258), fromExternalApp = true)
            cooldownField.setInt(engine, 1) // handleInGameScan 開頭遞減為 0
            engine.handleInGameScan(mockService, bmp, ocrItems)

            assertEquals("冷卻結束後應切換至 AD_WATCHING 處理廣告", EngineState.AD_WATCHING, engine.uiStatus.value.state)
            assertTrue("重進 AD_WATCHING 須保留先前的 (994, 258) 黑名單", adCloser.isInvalidOrBlacklisted(994, 258))
            coVerify(exactly = 0) { mockService.clickRect(any(), any(), any()) }
            adCloser.clearRedirectMemory()
        } finally {
            cooldownField.setInt(engine, origCooldown)
        }
    }
}
