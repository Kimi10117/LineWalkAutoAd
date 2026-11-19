package com.linewalk.autoad.test.v11594

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AdCloserHandler
import com.linewalk.autoad.engine.AdStepResult
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.tasks.LotteryTask
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
 * v1.15.94 Robolectric 回歸與實機日誌重播測試（針對 SM-F731N v1.15.93 `004322` 與 SM-S7210 v1.15.92 `004516` 雙日誌）：
 * 1. 黑名單緊縮防護與歸因窗：5.5s 點擊歸因時間窗防止 11–12s 後的 SDK 自動跳轉誤拉黑；`isInvalidOrBlacklistedForExplicitOcrClose`
 *    與緊縮 `rowHalfBandY` 防止 `(979,326)` 封死 55px 外的 End Card `"X"@91%,10%` `(987,271)` 或 `(115,114)` 封死 MITSUBISHI `"(X)"@(86,132)`；
 *    `hasRecentRedirectWithin` 不再因 `adStartTimeMs` 自我續命。
 * 2. 抽抽樂強標題與底部廣告橫幅防護：含內嵌橫幅的抽抽樂畫面於 `isGameScreenVisible` 必回傳 true；
 *    `LotteryTask.findLotteryResultCloseButton` 在底部有 Google Play 橫幅時回傳 null 改走右上角 (X)，無橫幅時對齊實機 `78.25%` 高度。
 * 3. `Step 8B Reveal Swipe` 避開 `"JOIN LIVE"@49%,70%` 中央 CTA；Store-Header End Card 首返回遭吞噬後試探 1 次乾淨右上角熱區，並避開 `"普遍級"@92%,7%`。
 * 4. `handleInGameScan` 偵測 `com.sec.android.app.launcher` 立即喚醒絕不誤轉 `AD_WATCHING`；`右上角略過鈕(▶|)` 不視為預期略過跳轉；
 *    主畫面頂部分頁辨識 `"首真"`／`"步敷"` OCR 近似字；高可信 `Node[` 關閉鈕推遲返回鍵 >= 4.5s。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_94SimulationTest {

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
            every { pressHome() } returns true
            every { bringAppToFront(any()) } returns true
            every { forceResetAppTaskToFront(any(), any()) } returns true
            every { lastBackRejectReason } returns ""
            coEvery { clickRect(any(), any(), any()) } returns true
            coEvery { performSwipe(any(), any(), any(), any(), any()) } returns true
        }
    }

    @Test
    fun test01_blacklistRadiusAndWindowNeverBlockRealCornerOcrXOrRefreshStaleBlacklist() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 20, 20)) }
        val mockService = createMockService("com.linecorp.LGWKTW")
        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val lastPtField = AdCloserHandler::class.java.getDeclaredField("lastClickedPoint").apply { isAccessible = true }
        val lastClickMsField = AdCloserHandler::class.java.getDeclaredField("lastAdClickTimeMs").apply { isAccessible = true }
        val retTimeField = AdCloserHandler::class.java.getDeclaredField("lastRedirectReturnTimeMs").apply { isAccessible = true }
        val blkTimeField = AdCloserHandler::class.java.getDeclaredField("lastBlacklistTimeMs").apply { isAccessible = true }
        val swField = AdCloserHandler::class.java.getDeclaredField("currentScreenWidth").apply { isAccessible = true }
        val shField = AdCloserHandler::class.java.getDeclaredField("currentScreenHeight").apply { isAccessible = true }
        swField.setInt(handler, sw)
        shField.setInt(handler, sh)

        // 1. 11 秒後的 SDK 自動跳轉（超過 5.5 秒歸因窗）絕不可將先前點擊點 (979, 326) 拉黑
        lastPtField.set(handler, Point(979, 326))
        lastClickMsField.setLong(handler, System.currentTimeMillis() - 11_000L)
        handler.markRecentRedirect(fromExternalApp = true)
        assertFalse("11 秒後 SDK 自動跳轉不可誤封 (979, 326)", handler.isInvalidOrBlacklisted(979, 326))

        // 2. 即使 (979, 326) 因真實重定向進黑名單，重入後 Step 7A2 仍必須立刻命中 55px 上方之 End Card "X"@91%,10% (987, 271)
        handler.clearRedirectMemory()
        handler.onEnterAdMode()
        handler.markRecentRedirect(Point(979, 326), fromExternalApp = true)
        handler.onEnterAdMode(preserveRedirectState = true)
        retTimeField.setLong(handler, System.currentTimeMillis() - 10_000L)
        assertTrue("原誤觸點 (979, 326) 本身必須被封鎖", handler.isInvalidOrBlacklistedForExplicitOcrClose(979, 326))
        assertFalse("55px 上方 End Card X (987, 271) 不可受牽連", handler.isInvalidOrBlacklistedForExplicitOcrClose(987, 271))

        startField.setLong(handler, System.currentTimeMillis() - 15_000L)
        val endCardWithX = listOf(
            OcrItem("X", Rect(967, 251, 1007, 291)),
            OcrItem("Bigo Live - Live", Rect(320, 1340, 760, 1400)),
            OcrItem("安装", Rect(460, 1680, 620, 1740))
        )
        val xClick = handler.processAdTick(mockService, bmp, endCardWithX)
        assertTrue("必須立即點擊 End Card 右上角 OCR-角落[X]: $xClick", xClick is AdStepResult.ClickedCloseOrSkip)
        assertEquals("OCR-角落[X]", (xClick as AdStepResult.ClickedCloseOrSkip).sourceLabel)

        // 3. MITSUBISHI：左上角幾何X (115, 114) 被拉黑後（消耗首次防重定向返回後），34px 旁的真實 "(X)" @ (86, 132) 仍必須可被 Step 7A2 點擊
        handler.clearRedirectMemory()
        handler.onEnterAdMode()
        handler.markRecentRedirect(Point(115, 114), fromExternalApp = true)
        val hasRecentRedirField = AdCloserHandler::class.java.getDeclaredField("hasRecentRedirect").apply { isAccessible = true }
        hasRecentRedirField.setBoolean(handler, false)
        retTimeField.setLong(handler, System.currentTimeMillis() - 10_000L)
        startField.setLong(handler, System.currentTimeMillis() - 20_000L)
        val mitsubishiOcr = listOf(
            OcrItem("(X)", Rect(66, 112, 106, 152)),
            OcrItem("MITSUBISHI", Rect(300, 270, 560, 310)),
            OcrItem("三菱電機", Rect(480, 310, 700, 350))
        )
        val mitClick = handler.processAdTick(mockService, bmp, mitsubishiOcr)
        assertTrue("MITSUBISHI 左上角 (X) 必須被點擊而不受 34px 外幾何 X 黑名單封鎖: $mitClick", mitClick is AdStepResult.ClickedCloseOrSkip)

        // 4. hasRecentRedirectWithin 不得被重設後的 adStartTimeMs 永久續命
        retTimeField.setLong(handler, System.currentTimeMillis() - 200_000L)
        blkTimeField.setLong(handler, System.currentTimeMillis() - 200_000L)
        startField.setLong(handler, System.currentTimeMillis() - 5_000L)
        assertFalse("超過 180 秒的舊黑名單不可因 adStartTimeMs 剛刷新而被誤判為近期重定向", handler.hasRecentRedirectWithin(180_000L))
    }

    @Test
    fun test02_lotterySubDialogWithEmbeddedAdBannerIsRecognizedAsGameAndNeverClicksBottomAdBanner() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val mockService = createMockService("com.linecorp.LGWKTW")
        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))

        // 1. 抽抽樂主畫面含內嵌原生廣告橫幅（Google Play / 立即下載）-> isGameScreenVisible 必須為 true
        val lotteryPanelWithBanner = listOf(
            OcrItem("抽抽樂", Rect(440, 340, 640, 400)),
            OcrItem("查看獎項一覽)", Rect(60, 480, 280, 520)),
            OcrItem("Google Play", Rect(300, 2100, 560, 2160)),
            OcrItem("立即下載", Rect(620, 2100, 840, 2160))
        )
        assertTrue(
            "抽抽樂主畫面即使底部含內嵌廣告橫幅也必須識別為遊戲畫面",
            handler.isGameScreenVisible(mockService, bmp, lotteryPanelWithBanner)
        )

        // 2. 抽抽樂中獎畫面（SM-S7210 L0475 實錄）
        val lotteryWinWithBanner = listOf(
            OcrItem("今抽抽樂", Rect(400, 340, 620, 400)),
            OcrItem("10枚金幣中奬囉!", Rect(340, 740, 760, 800)),
            OcrItem("Google Play", Rect(300, 2100, 560, 2160))
        )
        assertTrue(
            "抽抽樂中獎結果畫面含廣告標記時仍應識別為遊戲畫面",
            handler.isGameScreenVisible(mockService, bmp, lotteryWinWithBanner)
        )

        // 3. LotteryTask.findLotteryResultCloseButton：底部有 Google Play 橫幅且無 OCR 關閉 -> 回傳 null 避免點擊 y=81.5% 商店橫幅
        val lotteryTask = LotteryTask()
        assertNull(
            "無 OCR 關閉且底部為 Google Play 橫幅時應回傳 null 改由右上角 (X) 關閉",
            lotteryTask.findLotteryResultCloseButton(bmp, lotteryWinWithBanner)
        )

        // 4. 無底部廣告橫幅時之幾何備援高度須對齊實機 [關閉] (~78.25%，不再落於 81.5% 橫幅帶)
        val cleanWinResult = listOf(
            OcrItem("抽抽樂", Rect(440, 340, 640, 400)),
            OcrItem("10枚金幣中獎囉!", Rect(340, 740, 760, 800))
        )
        val fallbackRect = lotteryTask.findLotteryResultCloseButton(bmp, cleanWinResult)
        assertNotNull(fallbackRect)
        val normY = fallbackRect!!.centerY().toFloat() / sh
        assertTrue("幾何備援 [關閉] 高度應位於 0.77..0.795，實際=$normY", normY in 0.77f..0.795f)
    }

    @Test
    fun test03_revealSwipeAvoidsJoinLiveCtaAndEndCardTriesSafeTopRightFallbackAfterFirstSwallowedBack() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(24, 24, 24)) }

        // A. JOIN LIVE@49%,70% 喚醒滑動必須避開中央 CTA 區域
        run {
            val mockService = createMockService("com.linecorp.LGWKTW")
            val sxSlot = slot<Float>()
            val sySlot = slot<Float>()
            val exSlot = slot<Float>()
            val eySlot = slot<Float>()
            coEvery {
                mockService.performSwipe(capture(sxSlot), capture(sySlot), capture(exSlot), capture(eySlot), any())
            } returns true

            val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))
            handler.onEnterAdMode()
            val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
            startField.setLong(handler, System.currentTimeMillis() - 10_000L)

            val joinLiveRect = Rect(340, 1780, 720, 1920)
            val res = handler.processAdTick(mockService, bmp, listOf(OcrItem("JOIN LIVE", joinLiveRect)))
            assertTrue("JOIN LIVE 應觸發 SwipedToReveal: $res", res is AdStepResult.SwipedToReveal)
            coVerify(exactly = 1) { mockService.performSwipe(any(), any(), any(), any(), any()) }
            assertFalse(
                "喚醒滑動不可落在 JOIN LIVE 中央按鈕上: (${sxSlot.captured},${sySlot.captured})->(${exSlot.captured},${eySlot.captured})",
                sxSlot.captured in joinLiveRect.left.toFloat()..joinLiveRect.right.toFloat() &&
                    sySlot.captured in joinLiveRect.top.toFloat()..joinLiveRect.bottom.toFloat()
            )
        }

        // B. Blade War / Origami Store-Header End Card (y=11%..21%，頂部右上角乾淨)：Back #1 遭吞 -> 1 次安全右上角熱區 -> Back #2 -> 升級強退
        run {
            val mockService = createMockService("com.linecorp.LGWKTW")
            val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))
            handler.onEnterAdMode()
            val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
            val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
            val fallbackTimeField = AdCloserHandler::class.java.getDeclaredField("lastFallbackCornerTimeMs").apply { isAccessible = true }
            val storeHeaderEndCard = listOf(
                OcrItem("Blade War:Three", Rect(460, 260, 840, 320)),
                OcrItem("4.5★ Google Play", Rect(360, 420, 720, 480)),
                OcrItem("安装", Rect(440, 520, 620, 580))
            )

            startField.setLong(handler, System.currentTimeMillis() - 44_000L)
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, 0L)
            val step1 = handler.processAdTick(mockService, bmp, storeHeaderEndCard)
            assertEquals("廣告結束卡片安全返回鍵", (step1 as AdStepResult.WatchdogBackTriggered).actionLabel)

            // 首次返回被吞後，嘗試 1 次乾淨右上角安全關閉熱區 (y ≈ 6.6% 高於 11%..21% 商店標頭)
            startField.setLong(handler, System.currentTimeMillis() - 47_000L)
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, 0L)
            val step2 = handler.processAdTick(mockService, bmp, storeHeaderEndCard)
            assertEquals("備援盲點[結束卡右上角熱區]", (step2 as AdStepResult.WatchdogBackTriggered).actionLabel)

            // 若仍未關閉，Back #2 後即升級 ForceBringGameToFront
            startField.setLong(handler, System.currentTimeMillis() - 50_000L)
            backTimeField.setLong(handler, 0L)
            val step3 = handler.processAdTick(mockService, bmp, storeHeaderEndCard)
            assertEquals("廣告結束卡片安全返回鍵", (step3 as AdStepResult.WatchdogBackTriggered).actionLabel)

            startField.setLong(handler, System.currentTimeMillis() - 53_000L)
            backTimeField.setLong(handler, 0L)
            val step4 = handler.processAdTick(mockService, bmp, storeHeaderEndCard)
            assertTrue("二次吞鍵後應升級 ForceBringGameToFront: $step4", step4 is AdStepResult.ForceBringGameToFront)
        }

        // C. Petit Planet 右上角有「普遍級」@92%,7% 時，必須略過右上角備援熱區不可誤點分級標章
        run {
            val mockService = createMockService("com.linecorp.LGWKTW")
            val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))
            handler.onEnterAdMode()
            val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
            val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
            val fallbackTimeField = AdCloserHandler::class.java.getDeclaredField("lastFallbackCornerTimeMs").apply { isAccessible = true }
            val petitPlanetWithRating = listOf(
                OcrItem("普遍級", Rect(940, 150, 1045, 215)),
                OcrItem("Petit Planet", Rect(380, 1600, 700, 1680)),
                OcrItem("Google Play", Rect(420, 2040, 720, 2100)),
                OcrItem("Pre-register", Rect(400, 2240, 680, 2310)),
                OcrItem("安裝", Rect(460, 2320, 620, 2380))
            )

            startField.setLong(handler, System.currentTimeMillis() - 57_000L)
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, 0L)
            val back1 = handler.processAdTick(mockService, bmp, petitPlanetWithRating)
            assertEquals("廣告結束卡片安全返回鍵", (back1 as AdStepResult.WatchdogBackTriggered).actionLabel)

            startField.setLong(handler, System.currentTimeMillis() - 60_000L)
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, 0L)
            val back2 = handler.processAdTick(mockService, bmp, petitPlanetWithRating)
            assertEquals("右上角被「普遍級」佔據時不得觸發備援盲點而應直接走返回鍵", "廣告結束卡片安全返回鍵", (back2 as AdStepResult.WatchdogBackTriggered).actionLabel)
        }
    }

    @Test
    fun test04_launcherGuardInGameScanTopRightSkipNotLegitAndOcrTabLookalikes() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

        // 1. 主畫面頂部分頁 OCR 近似字（「首真」「步敷」）必須被辨識為散步趣主畫面
        val ocr = OcrRecognizer()
        val lookalikeTabsOcr = listOf(
            OcrItem("93,705", Rect(160, 160, 330, 210)),
            OcrItem("首真", Rect(80, 290, 180, 340)),
            OcrItem("步敷", Rect(340, 290, 440, 340))
        )
        assertTrue("頂部「首真」「步敷」應識別為 LINE 散步趣主畫面", ocr.isLineWalkMainScreenVisible(bmp, lookalikeTabsOcr, TemplateMatcher()))
        val gameService = createMockService("com.linecorp.LGWKTW")
        val adCloserDirect = AdCloserHandler(ocr, NodeInspector(), TemplateMatcher(), AppConfig(ctx))
        assertTrue("AdCloserHandler 亦須辨識「首真」「步敷」為遊戲畫面", adCloserDirect.isGameScreenVisible(gameService, bmp, lookalikeTabsOcr))

        // 2. handleInGameScan 讀取到前景已切換至系統桌面 com.sec.android.app.launcher 時，絕不可切入 AD_WATCHING
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        val launcherService = createMockService("com.sec.android.app.launcher")
        val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
        val engineAdCloser = adCloserField.get(engine) as AdCloserHandler
        try {
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中")
            engine.handleInGameScan(
                launcherService,
                bmp,
                listOf(OcrItem("Google Play", Rect(100, 600, 380, 660)))
            )
            assertEquals("系統桌面前景必須保持 IN_GAME_SCAN 喚醒散步趣，不可誤轉 AD_WATCHING", EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
            verify(atLeast = 1) { launcherService.bringAppToFront(any()) }

            // 3. 右上角略過鈕(▶|) 跳出至三星瀏覽器 (Winler est) 不可視為合法預期略過緩衝，必須立即記為重定向並安全返回
            engineAdCloser.clearRedirectMemory()
            engineAdCloser.onEnterAdMode()
            val lastLabelField = AdCloserHandler::class.java.getDeclaredField("lastClickedAdLabel").apply { isAccessible = true }
            val redirField = AdCloserHandler::class.java.getDeclaredField("redirectCount").apply { isAccessible = true }
            lastLabelField.set(engineAdCloser, "右上角略過鈕(▶|)")
            engine.transitionTo(EngineState.AD_WATCHING, "監控廣告...", "[廣告] 監控中")
            val browserService = createMockService("com.sec.android.app.sbrowser")
            engine.handleExternalAppGuard(browserService, "com.sec.android.app.sbrowser")
            assertEquals("右上角略過鈕(▶|) 跳轉三星瀏覽器應立即標記為廣告重定向", 1, redirField.getInt(engineAdCloser))
            verify(atLeast = 1) { browserService.safeNavigateBack(any(), any(), any()) }
        } finally {
            engineAdCloser.clearRedirectMemory()
            engine.stop("測試清理")
        }

        // 4. 點擊高可信 Node[id=close-or-skip-button] 後須推遲返回鍵至少 4.0s 以上，防止 5 秒內動畫未完被返回鍵推出桌面
        val closeNode = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isVisibleToUser } returns true
            every { packageName } returns "com.linecorp.LGWKTW"
            every { viewIdResourceName } returns "com.applovin.sdk:id/close-or-skip-button"
            every { text } returns ""
            every { contentDescription } returns "Close button"
            every { childCount } returns 0
            every { getBoundsInScreen(any()) } answers {
                firstArg<Rect>().set(960, 150, 1026, 212)
            }
        }
        val nodeService = createMockService("com.linecorp.LGWKTW")
        every { nodeService.getSafeRootNode() } returns closeNode
        adCloserDirect.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val lastBackField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
        startField.setLong(adCloserDirect, System.currentTimeMillis() - 48_000L)
        lastBackField.setLong(adCloserDirect, 0L)

        val beforeClick = System.currentTimeMillis()
        val nodeClick = adCloserDirect.processAdTick(nodeService, bmp, emptyList())
        assertTrue("應點擊 close-or-skip-button: $nodeClick", nodeClick is AdStepResult.ClickedCloseOrSkip)
        assertTrue(
            "點擊高可信關閉節點後 lastBackActionTimeMs 應推遲至少 4 秒防止誤退桌面",
            lastBackField.getLong(adCloserDirect) >= beforeClick + 4_000L
        )
    }
}
