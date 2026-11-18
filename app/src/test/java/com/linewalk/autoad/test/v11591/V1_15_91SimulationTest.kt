package com.linewalk.autoad.test.v11591

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AdCloserHandler
import com.linewalk.autoad.engine.AdStepResult
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.engine.EngineUiStatus
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.15.91 Robolectric 回歸與實機日誌重播測試（針對 SM-S7210 `20261118_051138` 6,985 行實機日誌五大根因）：
 * 1. 三星 AOD (`com.samsung.android.app.aodservice`)、生物辨識鎖定 (`biometrics.app.setting`) 與充電待機畫面 OCR 自動進入 [待機] 暫停，零 BACK / 零 CLEAR_TASK。
 * 2. 外部 Facebook (`com.facebook.katana`) 限時動態／粉絲專頁落地頁封鎖誤點「關閉限時動態匣」、封鎖跨套件誤判廣告載入失敗彈窗，並持續安全拉回遊戲。
 * 3. `Pre-register` Top 橫幅保護與 `detectCornerFastForwardSkip` 下緣收斂，延長 60 秒預先註冊展示廣告等待至 56 秒且禁止 `備援盲點` 自封右上角。
 * 4. 【首頁(移動)】向下捲動誤入「輕鬆賺 / 秘寶挖挖哇」版位時先切換＋下滑回頂部；每日賓果關閉廣告後不誤判「廣告未成功啟動」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_91SimulationTest {

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
            every { isDeviceLockedOrNonInteractive() } returns false
            every { getSafeRootNode() } returns null
            every { safeNavigateBack(any(), any(), any()) } returns true
            every { navigateBack() } returns true
            every { navigateBackFromExternalSocialApp(any()) } returns true
            every { pressHome() } returns true
            every { bringAppToFront(any()) } returns true
            every { forceResetAppTaskToFront(any(), any()) } returns true
            every { lastBackRejectReason } returns ""
            coEvery { clickRect(any(), any(), any()) } returns true
            coEvery { performSwipe(any(), any(), any(), any(), any()) } returns true
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
        if (rw <= 0 || y1 <= y0) return
        val row = IntArray(rw) { color }
        for (y in y0 until y1) {
            bmp.setPixels(row, 0, rw, x0, y, rw, 1)
        }
    }

    private fun drawDarkCircleSkipButton(bmp: Bitmap, cx: Int, cy: Int, outerR: Int = 26) {
        val w = bmp.width
        val h = bmp.height
        val r2 = outerR * outerR
        for (dy in -outerR..outerR) {
            for (dx in -outerR..outerR) {
                if (dx * dx + dy * dy <= r2) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px in 0 until w && py in 0 until h) {
                        bmp.setPixel(px, py, Color.rgb(32, 32, 32))
                    }
                }
            }
        }
        // 繪製深色圓內純白右指符號 / 竪線 (▶|)
        fillRectFast(bmp, cx - 10, cy - 9, cx + 2, cy + 9, Color.WHITE)
        fillRectFast(bmp, cx + 6, cy - 9, cx + 10, cy + 9, Color.WHITE)
    }

    @Test
    fun test01_lockscreenAndSamsungAodStandbyPausePatrolWithoutBackOrClearTask() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ocr = OcrRecognizer()

        assertTrue(AppConfig.isLockscreenOrAodPackage("com.samsung.android.app.aodservice"))
        assertTrue(AppConfig.isLockscreenOrAodPackage("com.samsung.android.biometrics.app.setting"))
        assertTrue(AppConfig.isLockscreenOrAodPackage("com.android.keyguard"))
        assertFalse(AppConfig.isLockscreenOrAodPackage("com.linecorp.LGWKTW"))

        val lockOcr = listOf(
            OcrItem("03:13", Rect(120, 680, 260, 740)),
            OcrItem("11月18日週三", Rect(180, 780, 420, 840)),
            OcrItem("深夜回顧", Rect(420, 1680, 620, 1740)),
            OcrItem("還有18分鐘即充飽", Rect(420, 1880, 780, 1940))
        )
        assertTrue("鎖屏充電 OCR 應識別為待機畫面", ocr.isLockscreenOrAodOcr(lockOcr))

        val gameOcr = listOf(
            OcrItem("首頁", Rect(60, 290, 180, 350)),
            OcrItem("步數", Rect(320, 290, 440, 350)),
            OcrItem("時間", Rect(580, 290, 700, 350)),
            OcrItem("遊戲", Rect(840, 290, 960, 350))
        )
        assertFalse("遊戲主畫面絕不可誤判為鎖屏待機", ocr.isLockscreenOrAodOcr(gameOcr))

        val engine = AutomationEngine.getInstance(ctx)
        val instanceField = AutoAdAccessibilityService::class.java.getDeclaredField("_instance").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val serviceFlow = instanceField.get(null) as MutableStateFlow<AutoAdAccessibilityService?>
        val uiStatusField = AutomationEngine::class.java.getDeclaredField("_uiStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statusFlow = uiStatusField.get(engine) as MutableStateFlow<EngineUiStatus>
        val ocrField = AutomationEngine::class.java.getDeclaredField("ocrRecognizer").apply { isAccessible = true }
        val origOcr = ocrField.get(engine)
        val origSvc = serviceFlow.value

        try {
            engine.clearFullLogs()
            // 1. 前景為 Samsung AOD：不得發送任何 BACK / bringAppToFront / click
            val aodService = createMockService("com.samsung.android.app.aodservice")
            serviceFlow.value = aodService
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.AD_WATCHING)

            engine.executeSingleTick()
            verify(exactly = 0) { aodService.safeNavigateBack(any(), any(), any()) }
            verify(exactly = 0) { aodService.navigateBack(any()) }
            verify(exactly = 0) { aodService.bringAppToFront(any()) }
            verify(exactly = 0) { aodService.forceResetAppTaskToFront(any(), any()) }
            coVerify(exactly = 0) { aodService.captureScreenBitmap() }
            coVerify(exactly = 0) { aodService.clickRect(any(), any(), any()) }

            // 2. Keyguard 鎖定（即使前景仍回報 LGWKTW）：同樣直接進入待機暫停
            val keyguardService = createMockService("com.linecorp.LGWKTW")
            every { keyguardService.isDeviceLockedOrNonInteractive() } returns true
            serviceFlow.value = keyguardService
            engine.executeSingleTick()
            coVerify(exactly = 0) { keyguardService.captureScreenBitmap() }
            verify(exactly = 0) { keyguardService.safeNavigateBack(any(), any(), any()) }

            // 3. OCR 層偵測到鎖屏充電畫面：攔截且不呼叫 clickRect / forceResetAppTaskToFront
            val spiedOcr = spyk(OcrRecognizer()).apply {
                coEvery { recognize(any()) } returns lockOcr
            }
            ocrField.set(engine, spiedOcr)
            val lockBmpService = createMockService("com.linecorp.LGWKTW")
            every { lockBmpService.isDeviceLockedOrNonInteractive() } returns false
            coEvery { lockBmpService.captureScreenBitmap() } answers {
                Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            }
            serviceFlow.value = lockBmpService
            engine.executeSingleTick()
            verify(exactly = 0) { lockBmpService.safeNavigateBack(any(), any(), any()) }
            verify(exactly = 0) { lockBmpService.forceResetAppTaskToFront(any(), any()) }
            coVerify(exactly = 0) { lockBmpService.clickRect(any(), any(), any()) }
            assertTrue("日誌應記錄 [待機] 暫停訊息", engine.getFullLogsFormatted().contains("[待機]"))
        } finally {
            ocrField.set(engine, origOcr)
            serviceFlow.value = origSvc
            engine.stop("測試清理")
        }
    }

    @Test
    fun test02_facebookStoryLandingPageIgnoresCloseStoryNodeAndEscapesWithoutErrorLoop() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2340
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

        // 1. NodeInspector 不可誤把「關閉限時動態匣」當成廣告關閉鈕
        val storyNode = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isVisibleToUser } returns true
            every { packageName } returns "com.facebook.katana"
            every { contentDescription } returns "關閉限時動態匣"
            every { text } returns null
            every { viewIdResourceName } returns null
            every { childCount } returns 0
            every { getBoundsInScreen(any()) } answers {
                firstArg<Rect>().set(860, 440, 945, 535)
            }
        }
        val inspector = NodeInspector()
        assertNull(
            "Facebook 限時動態匣節點絕不可被誤認為廣告關閉按鈕",
            inspector.findAdCloseNode(storyNode, sw, sh, isFullScreenAd = true)
        )

        // 2. AdCloserHandler 在 Facebook 限時動態頁面（即使含錯誤字眼）不得誤觸發「廣告載入失敗彈窗」，且持續透過外部社群返回 / 拉回遊戲
        val handler = AdCloserHandler(OcrRecognizer(), inspector, TemplateMatcher(), AppConfig(ctx))
        val mockService = createMockService("com.facebook.katana")
        val storyOcr = listOf(
            OcrItem("麗莎的省錢之道", Rect(160, 160, 420, 215)),
            OcrItem("有興趣", Rect(150, 720, 300, 780)),
            OcrItem("沒興趣", Rect(150, 910, 300, 970)),
            OcrItem("分享這則限時動態", Rect(180, 1110, 460, 1170)),
            OcrItem("無法載入", Rect(200, 1450, 420, 1510)),
            OcrItem("關閉", Rect(460, 1620, 620, 1680))
        )

        handler.onEnterAdMode()
        // 模擬已發生過多次社群回復，驗證不再因 < 2 次限制而卡死
        val recField = AdCloserHandler::class.java.getDeclaredField("socialLandingPageTotalRecoveries").apply { isAccessible = true }
        recField.setInt(handler, 5)

        val tick1 = handler.processAdTick(mockService, bmp, storyOcr)
        assertTrue("第 1 Tick 應立刻以 RedirectRecovered 逃離限時動態: $tick1", tick1 is AdStepResult.RedirectRecovered)
        verify(exactly = 1) { mockService.navigateBackFromExternalSocialApp(any()) }

        val tick2 = handler.processAdTick(mockService, bmp, storyOcr)
        assertTrue("第 2 Tick 仍停留在限時動態應強制 bringAppToFront 拉回散步趣: $tick2", tick2 is AdStepResult.RedirectRecovered)
        verify(exactly = 1) { mockService.bringAppToFront("com.linecorp.LGWKTW") }
    }

    @Test
    fun test03_preRegisterShowcaseAdBlocksBannerMisclicksWaits56sAndPreservesFallbackCorner() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2340
        val tm = TemplateMatcher()
        val ocr = OcrRecognizer()

        // 1. 建構頂部含 63px 狀態列黑帶之畫面，於 y=14.5% (340px) 模擬 [Pre-register] 横幅內的圓形按鍵
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        fillRectFast(bmp, 0, 63, sw, sh, Color.WHITE)
        drawDarkCircleSkipButton(bmp, cx = 884, cy = 340, outerR = 26)

        val bannerSkip = tm.detectCornerFastForwardSkip(bmp)
        assertNull(
            "detectCornerFastForwardSkip 不得因 effectiveTopY=63 下推而誤中 14.5% (y=340) Pre-register 橫幅按鈕: $bannerSkip",
            bannerSkip
        )

        // 2. AdCloserHandler Pre-register 橫幅整列封鎖 + 56 秒安全等待 + 備援盲點不自封右上角
        val handler = AdCloserHandler(ocr, NodeInspector(), tm, AppConfig(ctx))
        val preRegOcr = listOf(
            OcrItem("Silver Palace", Rect(360, 260, 620, 310)),
            OcrItem("Pre-register", Rect(790, 305, 960, 365)),
            OcrItem("Google Play", Rect(380, 385, 580, 425)),
            OcrItem("任務", Rect(100, 620, 340, 665))
        )
        assertTrue(
            "Pre-register 橫幅右按鈕應遭 OCR 列防誤點護欄封鎖",
            handler.isBlockedByCornerPillOrSurveyOcr(884, 340, preRegOcr, sw, sh)
        )
        assertTrue(
            "Pre-register 橫幅左側圖示應遭 OCR 列防誤點護欄封鎖",
            handler.isBlockedByCornerPillOrSurveyOcr(107, 356, preRegOcr, sw, sh)
        )

        val mockService = createMockService("com.linecorp.LGWKTW")
        assertFalse(
            "Pre-register + Google Play 廣告內文含『任務』絕不可誤判為回到遊戲主畫面/子視窗",
            handler.isGameScreenVisible(mockService, bmp, preRegOcr)
        )

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val redirField = AdCloserHandler::class.java.getDeclaredField("redirectCount").apply { isAccessible = true }
        val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
        val fallbackTimeField = AdCloserHandler::class.java.getDeclaredField("lastFallbackCornerTimeMs").apply { isAccessible = true }

        // 模擬 48 秒時已有 1 次 Pre-register 自動開商店：應續待至 56 秒而非於 48s 提前強退
        startField.setLong(handler, System.currentTimeMillis() - 48_000L)
        redirField.setInt(handler, 1)
        backTimeField.setLong(handler, 0L)
        val resAt48 = handler.processAdTick(mockService, bmp, preRegOcr)
        assertTrue("Pre-register 廣告於 48s 應繼續等待至 56s，不提早按返回強退: $resAt48", resAt48 is AdStepResult.ScanningAd)

        // 滿 57 秒才允許觸發末期安全返回鍵
        startField.setLong(handler, System.currentTimeMillis() - 57_000L)
        backTimeField.setLong(handler, 0L)
        val resAt57 = handler.processAdTick(mockService, bmp, preRegOcr)
        assertTrue("Pre-register 廣告滿 57s 應觸發末期安全返回鍵: $resAt57", resAt57 is AdStepResult.WatchdogBackTriggered)

        // 備援盲點連續試探 5 次皆不得將右上角座標納入 blacklistedPoints，確保 60s 出現真 "X"@94%,6% 仍可秒點
        handler.onEnterAdMode()
        val pureVideoOcr = listOf(OcrItem("Run. Burn. Earn.", Rect(180, 500, 800, 900)))
        repeat(5) {
            startField.setLong(handler, System.currentTimeMillis() - 48_000L)
            fallbackTimeField.setLong(handler, 0L)
            backTimeField.setLong(handler, System.currentTimeMillis())
            handler.processAdTick(mockService, bmp, pureVideoOcr)
        }
        assertFalse(
            "備援盲點不得將右上角 (1015, 140) 自我加入黑名單",
            handler.isInvalidOrBlacklisted(1015, 140)
        )
        startField.setLong(handler, System.currentTimeMillis() - 60_000L)
        val x60Tick = handler.processAdTick(
            mockService,
            bmp,
            pureVideoOcr + OcrItem("X", Rect(995, 120, 1035, 160))
        )
        assertTrue("60s 浮現真實 X@94%,6% 時必須成功點擊: $x60Tick", x60Tick is AdStepResult.ClickedCloseOrSkip)
    }

    @Test
    fun test04_scrolledDownHomeTabSwipesBackToTopAndBingoCloseDoesNotFalseRestart() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2340

        // 1. 【首頁(移動)】因向下捲動導致「秘寶挖挖哇 / 簡單賺金幣 / 輕鬆賺」佔據畫面中央，ensureOnTabWithDailyIcons 必須先下滑回頂部而非直接誤點輕鬆賺
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        val phaseField = AutomationEngine::class.java.getDeclaredField("currentPhase").apply { isAccessible = true }
        val origPhase = phaseField.get(engine)
        val tabRetryField = AutomationEngine::class.java.getDeclaredField("tabSwitchRetries").apply { isAccessible = true }

        val scrolledBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        // 在左側第 0 分頁畫出黑色選取指示條 (detectActiveMainTabIndex -> MainTab.MOVE)
        fillRectFast(scrolledBmp, 40, (sh * 0.145f).toInt(), (sw / 4) - 40, (sh * 0.155f).toInt(), Color.BLACK)

        val scrolledOcr = listOf(
            OcrItem("首頁", Rect(60, 280, 180, 340)),
            OcrItem("時間", Rect(330, 280, 450, 340)),
            OcrItem("遊戲", Rect(600, 280, 720, 340)),
            OcrItem("步數", Rect(860, 280, 980, 340)),
            OcrItem("秘寶挖挖哇", Rect(300, 900, 620, 960)),
            OcrItem("輕鬆賺", Rect(320, 1020, 520, 1080)),
            OcrItem("簡單賺金幣", Rect(300, 1180, 620, 1240)),
            OcrItem("集點", Rect(90, 2180, 180, 2240))
        )

        val mockService = createMockService("com.linecorp.LGWKTW")
        try {
            phaseField.set(engine, PatrolPhase.LOTTERY)
            tabRetryField.setInt(engine, 0)
            engine.currentTab = com.linewalk.autoad.engine.MainTab.MOVE
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中...")

            engine.handleInGameScan(mockService, scrolledBmp, scrolledOcr)

            // 必須觸發向下滑動回頂部（startY < endY）且絕對不可誤點 (38%..46%) 輕鬆賺圖示熱區
            coVerify(atLeast = 1) {
                mockService.performSwipe(any(), match { it < sh * 0.4f }, any(), match { it > sh * 0.6f }, any())
            }
            coVerify(exactly = 0) {
                mockService.clickRect(match { r -> r.centerY() in (sh * 0.36f).toInt()..(sh * 0.48f).toInt() }, any(), any())
            }
        } finally {
            phaseField.set(engine, origPhase)
            tabRetryField.setInt(engine, 0)
            engine.stop("測試清理")
        }

        // 2. 每日賓果觀看廣告並已點擊 Node[desc=Close] (closeClickCount > 0) 回到賓果頁時，必須回報正常完成而非「廣告未成功啟動」
        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val closeCntField = AdCloserHandler::class.java.getDeclaredField("closeClickCount").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 6_000L)
        closeCntField.setInt(handler, 1)

        val bingoReturnOcr = listOf(
            OcrItem("每日賓果", Rect(420, 240, 660, 300)),
            OcrItem("立即開啟賓果格", Rect(240, 1500, 840, 1620))
        )
        // 模擬畫面帶有珊瑚粉紅格鈕像素
        fillRectFast(scrolledBmp, 240, 1650, 840, 1800, Color.rgb(255, 72, 85))
        val res = handler.processAdTick(mockService, scrolledBmp, bingoReturnOcr)
        assertTrue("點擊過關閉鈕後回到賓果頁應為 ReturnedToGame: $res", res is AdStepResult.ReturnedToGame)
        assertEquals("已回到散步趣介面", (res as AdStepResult.ReturnedToGame).label)
    }
}
