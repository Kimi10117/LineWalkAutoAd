package com.linewalk.autoad.test.v11596

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
import com.linewalk.autoad.engine.EngineUiStatus
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.tasks.MissionTask
import com.linewalk.autoad.tasks.ReceiveGiftTask
import com.linewalk.autoad.ui.FloatingOverlayService
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
import org.junit.Assert.assertNotEquals
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
 * v1.15.96 Robolectric 模擬驗證測試（針對 SM-F731N 與 SM-S7210 實機 v1.15.95 日誌與截圖/錄影根因修正）：
 * 1. 全黑廣告載入過渡期僅含頂部狀態列時鐘/電量時，不再誤判為鎖屏 AOD；`adStartTimeMs == 0L` 自癒避免卡死 0s。
 * 2. Meta `Trip.com` Pod (`Ad 1 of 2`) 不再誤判 `"1"` 為倒數、延遲早期左側掃描，並允許相位升級解除誤觸黑名單點擊真實 `Skip` / `X`；
 *    同時防護 Shopee 中段 `>>一起` 誤觸，以及 S24FE 無座標記錄時的 `[Google Play ×]` 右端解鎖。
 * 3. `ReceiveGift` 空抽屜關閉嘗試升級與上限保護（終結 925 次無限迴圈）、`MissionTask` 同點重複領取保護、連續重複日誌 `[重複抑制]` 收斂。
 * 4. Level 3 多工強退嚴格排除「散步趣助手」自身視窗、優先選擇最右方《LINE 散步趣》卡片並記錄診斷指標。
 * 5. 主畫面 `貼圖任務` 與 `首夏` 誤識修正、懸浮窗 `[設定保護]` / `[待機]` 與 `[防護]` 保護期標籤修正、誤退回助手頁自動拉回，以及 `smartcapture` 被動工具略過。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_96SimulationTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx)
        } catch (_: Throwable) {
        }
    }

    private fun fillRect(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        val l = left.coerceIn(0, bmp.width - 1)
        val r = right.coerceIn(0, bmp.width)
        val t = top.coerceIn(0, bmp.height - 1)
        val b = bottom.coerceIn(0, bmp.height)
        for (y in t until b) {
            for (x in l until r) {
                bmp.setPixel(x, y, color)
            }
        }
    }

    private fun drawCornerSkipPill(bmp: Bitmap, cx: Int, cy: Int) {
        val rad = 30
        for (dy in -rad..rad) {
            for (dx in -rad..rad) {
                if (dx * dx + dy * dy <= rad * rad) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px in 0 until bmp.width && py in 0 until bmp.height) {
                        bmp.setPixel(px, py, Color.rgb(88, 88, 92))
                    }
                }
            }
        }
        for (dy in -10..10) {
            val maxDx = (10 - abs(dy)) * 12 / 10
            for (dx in -9..(-9 + maxDx)) {
                val px = cx + dx
                val py = cy + dy
                if (px in 0 until bmp.width && py in 0 until bmp.height) {
                    bmp.setPixel(px, py, Color.WHITE)
                }
            }
            for (dx in 6..9) {
                val px = cx + dx
                val py = cy + dy
                if (px in 0 until bmp.width && py in 0 until bmp.height) {
                    bmp.setPixel(px, py, Color.WHITE)
                }
            }
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
            every { openRecents() } returns false
            every { findRecentsAppCardCenterX(any(), any()) } returns null
            every { describeRecentsCardsForLog(any(), any()) } returns "mock-cards"
            every { getLastBackActionTimeMs() } returns 0L
            every { bringAppToFront(any()) } returns true
            every { forceResetAppTaskToFront(any(), any()) } returns true
            every { lastBackRejectReason } returns ""
            coEvery { clickRect(any(), any(), any()) } returns true
            coEvery { performSwipe(any(), any(), any(), any(), any()) } returns true
        }
    }

    @Test
    fun test01_blackAdLoadingStatusBarClockNotLockscreenAndAdStartTimerSelfHeals() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ocr = OcrRecognizer()
        val sh = 2640
        val sw = 1080

        // 1. 全黑廣告過渡幀僅有頂部狀態列時鐘與電量數字 (cy < 5% sh = 132) -> 絕不可誤判為鎖屏 AOD
        val statusBarClockOnly = listOf(
            OcrItem("3:05", Rect(60, 20, 180, 75)),
            OcrItem("92", Rect(960, 22, 1020, 75))
        )
        assertFalse(
            "全黑廣告載入過渡僅含頂部狀態列時鐘不得誤判鎖屏",
            ocr.isLockscreenOrAodOcr(statusBarClockOnly, sh)
        )

        // 真實鎖屏 AOD 中央大時鐘 + 充電百分比 (y = 890..2010) 仍必須正確識別為鎖屏
        val realAod = listOf(
            OcrItem("03:05", Rect(360, 820, 720, 960)),
            OcrItem("完全充電 92%", Rect(380, 1950, 700, 2010))
        )
        assertTrue("真實中央 AOD 時鐘應識別為鎖屏", ocr.isLockscreenOrAodOcr(realAod, sh))

        // 2. AdCloserHandler 當 adStartTimeMs == 0L（例如先前 timer 被清除）進入 processAdTick 時必須自癒重設
        val adCloser = AdCloserHandler(ocr, NodeInspector(), TemplateMatcher(), AppConfig(ctx))
        val adBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val adSvc = createMockService("com.linecorp.LGWKTW")
        adCloser.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(adCloser, 0L)
        assertEquals(0, adCloser.getElapsedSeconds())

        val adOcr = listOf(
            OcrItem("Trip.com", Rect(200, 1400, 520, 1470)),
            OcrItem("65", Rect(60, 180, 130, 235))
        )
        val tickRes = adCloser.processAdTick(adSvc, adBmp, adOcr)
        assertTrue("自癒後不應無限卡死，且 adStartTimeMs 必須恢復 > 0", startField.getLong(adCloser) > 0L)
        assertTrue(
            "恢復計時應進入監控/保護期結果而非錯誤重置: $tickRes",
            tickRes is AdStepResult.WaitingGracePeriod || tickRes is AdStepResult.StillCountingDown || tickRes is AdStepResult.ScanningAd
        )

        // 3. AutomationEngine.executeSingleTick 遇到狀態列時鐘黑畫面不可觸發 pauseForLockscreenStandby
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
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
            val spiedOcr = spyk(OcrRecognizer()).apply {
                coEvery { recognize(any()) } returns statusBarClockOnly
            }
            ocrField.set(engine, spiedOcr)
            val tickSvc = createMockService("com.linecorp.LGWKTW")
            coEvery { tickSvc.captureScreenBitmap() } answers {
                Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            }
            serviceFlow.value = tickSvc
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.AD_WATCHING)

            engine.executeSingleTick()
            assertEquals("僅狀態列時鐘黑幀不應離線待機或停機", EngineState.AD_WATCHING, engine.uiStatus.value.state)
            assertFalse(
                "不應出現鎖屏暫停日誌",
                engine.fullSessionLogs.any { it.contains("螢幕鎖定") || it.contains("AOD 待機") }
            )
        } finally {
            ocrField.set(engine, origOcr)
            serviceFlow.value = origSvc
            engine.stop("測試清理")
        }
    }

    @Test
    fun test02_metaTripAdPodCountdownExcludedAndPhaseUpgradeClicksSkipAndX() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ocr = OcrRecognizer()
        val sw = 1080
        val sh = 2640

        // 1. Meta Trip.com Pod 即使位於頂角或角落 "Ad 1 of 2" 內的獨立 "1" 字元絕不可誤判為 1 秒倒數
        val topCornerPodOcr = listOf(
            OcrItem("Trip.com", Rect(220, 1500, 560, 1570)),
            OcrItem("Sponsored", Rect(220, 1580, 420, 1630)),
            OcrItem("Ad 1 of 2", Rect(42, 175, 220, 230)),
            OcrItem("1", Rect(95, 180, 125, 225))
        )
        assertNull("Ad 1 of 2 內的 '1' 必須排除，不可當成剩餘 1 秒", ocr.detectAdCountdownSeconds(topCornerPodOcr, sh, sw))

        val podOcr = listOf(
            OcrItem("Trip.com", Rect(220, 1500, 560, 1570)),
            OcrItem("Sponsored", Rect(220, 1580, 420, 1630)),
            OcrItem("Ad 1 of 2", Rect(42, 1750, 220, 1805)),
            OcrItem("1", Rect(95, 1755, 125, 1800))
        )

        // 真正倒數數字 (1..60s 範圍內) 仍須正常識別
        assertEquals(45, ocr.detectAdCountdownSeconds(listOf(OcrItem("45", Rect(60, 180, 130, 235))), sh, sw))

        // 2. Meta/Facebook 影片廣告在 elapsed = 5s 時不得提前誤觸左側或 Step 4C 視覺略過
        val adCloser = AdCloserHandler(ocr, NodeInspector(), TemplateMatcher(), AppConfig(ctx))
        val metaSvc = createMockService("com.facebook.katana")
        val metaBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(245, 245, 245))
            // 左上角放置靜音類似圓鈕、右上角放置略過類似圓鈕
            drawCornerSkipPill(this, 94, 194)
        }
        adCloser.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val redirField = AdCloserHandler::class.java.getDeclaredField("redirectCount").apply { isAccessible = true }
        val redirPointsField = AdCloserHandler::class.java.getDeclaredField("redirectPointCounts").apply { isAccessible = true }
        val lastPtField = AdCloserHandler::class.java.getDeclaredField("lastClickedPoint").apply { isAccessible = true }
        val lastLabelField = AdCloserHandler::class.java.getDeclaredField("lastClickedAdLabel").apply { isAccessible = true }
        val lastClickMsField = AdCloserHandler::class.java.getDeclaredField("lastAdClickTimeMs").apply { isAccessible = true }
        val earlyBlkAtField = AdCloserHandler::class.java.getDeclaredField("earlyVisualSkipBlacklistedAtMs").apply { isAccessible = true }
        val retTimeField = AdCloserHandler::class.java.getDeclaredField("lastRedirectReturnTimeMs").apply { isAccessible = true }
        val hasRecentRedirField = AdCloserHandler::class.java.getDeclaredField("hasRecentRedirect").apply { isAccessible = true }

        startField.setLong(adCloser, System.currentTimeMillis() - 5_000L)
        val earlyRes = adCloser.processAdTick(metaSvc, metaBmp, podOcr)
        assertFalse("Meta 5 秒內不得誤觸左上方按鈕: $earlyRes", earlyRes is AdStepResult.ClickedCloseOrSkip)

        // 3. 模擬早期右上誤觸「右上角略過鈕(▶|)」(960, 192) 被加入黑名單後（維持舊合約 isInvalidOrBlacklistedForExplicitOcrClose == true），
        //    於後期 62s 出現明確 OCR "Skip" 與 End Card "X" 必須透過相位升級解鎖並點擊！
        lastLabelField.set(adCloser, "右上角略過鈕(▶|)")
        lastClickMsField.setLong(adCloser, System.currentTimeMillis() - 1_000L)
        adCloser.markRecentRedirect(android.graphics.Point(960, 192), fromExternalApp = true)
        adCloser.onEnterAdMode(preserveRedirectState = true)
        assertNotNull("觸發黑名單後應可擷取診斷摘要", adCloser.consumeLastBlacklistEventDiag())
        hasRecentRedirField.setBoolean(adCloser, false)
        retTimeField.setLong(adCloser, System.currentTimeMillis() - 15_000L)
        earlyBlkAtField.setLong(adCloser, System.currentTimeMillis() - 15_000L)

        assertTrue(
            "原始 explicit OCR 黑名單判定應仍為 true（相容既有規格）",
            adCloser.isInvalidOrBlacklistedForExplicitOcrClose(960, 192)
        )
        assertTrue(
            "後期 62s 應允許 canBypassEarlyVisualSkipBlacklist 解鎖誤封的右上角真 Skip/X",
            adCloser.canBypassEarlyVisualSkipBlacklist(926, 193, 62)
        )

        startField.setLong(adCloser, System.currentTimeMillis() - 62_000L)
        val metaSkipOcr = podOcr + listOf(OcrItem("Skip", Rect(868, 174, 985, 212)))
        val skipRes = adCloser.processAdTick(metaSvc, metaBmp, metaSkipOcr)
        assertTrue("後期出現真實 Skip 時應透過相位升級直接點擊: $skipRes", skipRes is AdStepResult.ClickedCloseOrSkip)

        // End Card 出現 "X" 時同樣解鎖並點擊
        adCloser.clearRedirectMemory()
        adCloser.onEnterAdMode()
        lastLabelField.set(adCloser, "右上角略過鈕(▶|)")
        lastClickMsField.setLong(adCloser, System.currentTimeMillis() - 1_000L)
        adCloser.markRecentRedirect(android.graphics.Point(960, 192), fromExternalApp = true)
        adCloser.onEnterAdMode(preserveRedirectState = true)
        hasRecentRedirField.setBoolean(adCloser, false)
        retTimeField.setLong(adCloser, System.currentTimeMillis() - 15_000L)
        earlyBlkAtField.setLong(adCloser, System.currentTimeMillis() - 15_000L)
        startField.setLong(adCloser, System.currentTimeMillis() - 62_000L)
        val endCardOcr = listOf(
            OcrItem("Trip.com", Rect(220, 1500, 560, 1570)),
            OcrItem("X", Rect(950, 165, 1010, 220))
        )
        val endXRes = adCloser.processAdTick(metaSvc, metaBmp, endCardOcr)
        assertTrue("End Card 出現 X 時應透過相位升級點擊關閉: $endXRes", endXRes is AdStepResult.ClickedCloseOrSkip)

        // 4. Shopee 中段 ">>一起" (277, 902) 絕不得被 Step 4A-1 或 Step 4B 當成略過符號誤點
        val shopeeHandler = AdCloserHandler(ocr, NodeInspector(), TemplateMatcher(), AppConfig(ctx))
        val shopeeSvc = createMockService("com.linecorp.LGWKTW")
        val shopeeBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        shopeeHandler.onEnterAdMode()
        AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
            .setLong(shopeeHandler, System.currentTimeMillis() - 12_000L)
        val shopeeMidBait = listOf(OcrItem(">>一起", Rect(230, 880, 325, 924)))
        val shopeeRes = shopeeHandler.processAdTick(shopeeSvc, shopeeBmp, shopeeMidBait)
        assertFalse("螢幕中段商品文案 '>>一起' 絕不可被 Step 4A-1 或 Step 4B 點擊: $shopeeRes", shopeeRes is AdStepResult.ClickedCloseOrSkip)

        // 5. S24FE 休眠喚醒滑動跳轉僅 redirectCount=1、redirectPointCounts 為空時（無右上誤觸點），不可鎖死 [Google Play ×] 右端 ×
        val s24Handler = AdCloserHandler(ocr, NodeInspector(), TemplateMatcher(), AppConfig(ctx))
        s24Handler.onEnterAdMode()
        AdCloserHandler::class.java.getDeclaredField("currentScreenWidth").apply { isAccessible = true }.setInt(s24Handler, sw)
        AdCloserHandler::class.java.getDeclaredField("currentScreenHeight").apply { isAccessible = true }.setInt(s24Handler, sh)
        redirField.setInt(s24Handler, 1)
        @Suppress("UNCHECKED_CAST")
        (redirPointsField.get(s24Handler) as MutableList<*>).clear()
        lastPtField.set(s24Handler, null)
        val pillBounds = Rect(700, 150, 1020, 220)
        assertFalse(
            "無右上角重定向座標紀錄時，[Google Play ×] 最右端 × (995, 185) 必須保持可點擊",
            s24Handler.isBlockedByCornerPillOrSurveyOcr(
                995,
                185,
                listOf(OcrItem("Google Play ×", pillBounds)),
                sw,
                sh
            )
        )
    }

    @Test
    fun test03_receiveGiftDialogCloseEscalatesAndNeverLoopsInfinitely() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sh = 2640
        val sw = 1080
        val receiveGiftTask = ReceiveGiftTask()

        // 1. 寬鬆 "禮物" 相關字樣（如「贈送禮物次數」或長字串「超值禮物兌換活動」，非禮物盒抽屜語境）必須被嚴格排除，避免誤進無限關窗迴圈
        assertFalse(
            "贈送禮物相關字樣不可誤判為收禮抽屜開啟",
            receiveGiftTask.isReceiveGiftDialogOpen(listOf(OcrItem("贈送禮物次數", Rect(200, 500, 600, 560))), sh)
        )
        assertFalse(
            "長促銷文案含 '禮物' 不可誤判為收禮抽屜開啟",
            receiveGiftTask.isReceiveGiftDialogOpen(listOf(OcrItem("超值禮物兌換活動", Rect(200, 500, 600, 560))), sh)
        )

        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        val missionTaskField = AutomationEngine::class.java.getDeclaredField("missionTask").apply { isAccessible = true }
        val origMissionTask = missionTaskField.get(engine)
        val uiStatusField = AutomationEngine::class.java.getDeclaredField("_uiStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statusFlow = uiStatusField.get(engine) as MutableStateFlow<EngineUiStatus>

        try {
            // 2. 空禮物盒抽屜持續顯示時，B4 必須升級關閉並在 >= 7 次後強制逃脫不再 925 次無限迴圈
            val mockSvc = createMockService("com.linecorp.LGWKTW")
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val emptyDrawerOcr = listOf(
                OcrItem("禮物盒", Rect(380, 420, 700, 480)),
                OcrItem("沒有可領取的禮物", Rect(240, 900, 840, 960))
            )
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.IN_GAME_SCAN)
            engine.isReceiveGiftCompleted = true
            engine.receiveGiftDialogCloseAttempts = 0

            for (tick in 1..7) {
                engine.handleInGameScan(mockSvc, bmp, emptyDrawerOcr)
            }

            verify(atLeast = 1) { mockSvc.safeNavigateBack(any(), any(), any()) }
            assertEquals("第 7 次關閉無效後應強制點擊底部集點並重設避免無限迴圈", 0, engine.receiveGiftDialogCloseAttempts)
            assertTrue("應記錄第 7 次強制重置日誌", engine.fullSessionLogs.any { it.contains("禮物視窗連續關閉達 7 次") })

            // 3. MissionTask 同一綠色「領取」座標 (810, 1765) 連續點擊 5 輪無變化時，最多僅點 3 次即止損切換
            val claimRect = Rect(730, 1720, 890, 1810)
            val stubMission = spyk(MissionTask()).apply {
                every { isMissionDialogOpen(any(), any(), any()) } returns true
                every { getAvailableTabs(any(), any(), any()) } returns listOf("特別", "每日")
                every { detectCurrentActiveTab(any(), any()) } returns "特別"
                every { findClaimableMissionButton(any(), any()) } returns claimRect
            }
            missionTaskField.set(engine, stubMission)
            val missionSvc = createMockService("com.linecorp.LGWKTW")
            engine.isMissionCompleted = false
            val missionOcr = listOf(
                OcrItem("任務", Rect(460, 320, 620, 380)),
                OcrItem("特別", Rect(240, 460, 420, 520)),
                OcrItem("每日", Rect(640, 460, 820, 520))
            )

            for (tick in 1..5) {
                engine.handleInGameScan(missionSvc, bmp, missionOcr)
            }
            coVerify(atMost = 3) { missionSvc.clickRect(claimRect, any(), any()) }

            // 4. 連續重複日誌收斂驗證：前 3 條正常、第 4+ 條觸發 [重複抑制] 摘要
            engine.clearFullLogs()
            repeat(5) { engine.appendLog("相同測試日誌 A") }
            engine.appendLog("不同測試日誌 B")
            val logs = engine.fullSessionLogs
            assertEquals("5 條相同 + 1 條不同應收斂為 3 條 A + 1 條抑制摘要 + 1 條 B", 5, logs.size)
            assertTrue("應產生 [重複抑制] 摘要", logs.any { it.contains("[重複抑制]") && it.contains("略過 2 次") })

            engine.clearFullLogs()
            repeat(15) { engine.appendLog("長重複日誌 C") }
            val exported = engine.getExportLogsFormatted()
            assertTrue("長重複未結尾日誌匯出時亦須包含 [重複抑制]", exported.contains("[重複抑制]"))
            assertTrue("15 次重複應大幅縮減日誌行數 (${engine.fullSessionLogs.size})", engine.fullSessionLogs.size <= 6)
        } finally {
            missionTaskField.set(engine, origMissionTask)
            engine.stop("測試清理")
        }
    }

    @Test
    fun test04_level3RecentsExcludesSelfHelperAppAndPicksRightmostLineWalkCard() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()

        // 1. 卡片標籤識別嚴格排除「散步趣助手」，僅接受遊戲本身名稱
        assertFalse(AutoAdAccessibilityService.isLineWalkRecentsLabel("散步趣助手"))
        assertFalse(AutoAdAccessibilityService.isLineWalkRecentsLabel("LINE 散步趣助手"))
        assertFalse(AutoAdAccessibilityService.isLineWalkRecentsLabel("散步趣助手 v1.15.96"))
        assertFalse(AutoAdAccessibilityService.isLineWalkRecentsLabel("LineWalkAutoAd"))
        assertTrue(AutoAdAccessibilityService.isLineWalkRecentsLabel("散步趣"))
        assertTrue(AutoAdAccessibilityService.isLineWalkRecentsLabel("LINE 散步趣"))
        assertTrue(AutoAdAccessibilityService.isLineWalkRecentsLabel("LINE Walk"))

        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        val ocrField = AutomationEngine::class.java.getDeclaredField("ocrRecognizer").apply { isAccessible = true }
        val origOcr = ocrField.get(engine)

        try {
            // 2. OCR fallback 同時看到左側「散步趣助手」(x=260) 與右側「LINE 散步趣」(x=860) -> 必滑 860f，嚴禁滑 260f
            val mixedOcr = spyk(OcrRecognizer()).apply {
                coEvery { recognize(any()) } returns listOf(
                    OcrItem("散步趣助手", Rect(160, 320, 360, 380)),
                    OcrItem("LINE 散步趣", Rect(740, 320, 980, 380))
                )
            }
            ocrField.set(engine, mixedOcr)
            engine.level3RecentsKillCount = 0

            val recentsSvc = createMockService("com.sec.android.app.launcher")
            every { recentsSvc.openRecents() } returns true
            every { recentsSvc.findRecentsAppCardCenterX(any(), any()) } returns null
            coEvery { recentsSvc.captureScreenBitmap() } answers {
                Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
            }

            val killed = engine.forceKillStuckGameTaskViaRecents(recentsSvc, "com.linecorp.LGWKTW", 1080, 2640)
            assertTrue(killed)
            coVerify(exactly = 1) { recentsSvc.performSwipe(860f, any(), 860f, any(), any()) }
            coVerify(exactly = 0) { recentsSvc.performSwipe(260f, any(), 260f, any(), any()) }
            assertEquals(1, engine.level3RecentsKillCount)
            assertTrue("匯出報告應包含 Level3多工滑除=1", engine.getExportLogsFormatted().contains("Level3多工滑除=1"))

            // 3. 畫面存在兩張《LINE 散步趣》相關標籤 (x=300 與 x=820) 時，優先滑除最右側前景卡片 (x=820)
            val dualOcr = spyk(OcrRecognizer()).apply {
                coEvery { recognize(any()) } returns listOf(
                    OcrItem("散步趣", Rect(220, 320, 380, 380)),
                    OcrItem("LINE Walk", Rect(720, 320, 920, 380))
                )
            }
            ocrField.set(engine, dualOcr)
            val recentsSvc2 = createMockService("com.sec.android.app.launcher")
            every { recentsSvc2.openRecents() } returns true
            every { recentsSvc2.findRecentsAppCardCenterX(any(), any()) } returns null
            coEvery { recentsSvc2.captureScreenBitmap() } answers {
                Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
            }
            assertTrue(engine.forceKillStuckGameTaskViaRecents(recentsSvc2, "com.linecorp.LGWKTW", 1080, 2640))
            coVerify(exactly = 1) { recentsSvc2.performSwipe(820f, any(), 820f, any(), any()) }
            coVerify(exactly = 0) { recentsSvc2.performSwipe(300f, any(), 300f, any(), any()) }
        } finally {
            ocrField.set(engine, origOcr)
            engine.stop("測試清理")
        }
    }

    @Test
    fun test05_homeTabStickerMissionBadgeAndSelfFgOverlayAndBackRecoveryAndSmartCapture() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val ocr = OcrRecognizer()
        val sw = 1080
        val sh = 2640

        // 1. 首頁 "首頁" 誤識為 "首夏" 且出現 "貼圖任務" 徽章 (cy=32.5% sh) -> 仍須辨識為主畫面而非子彈窗
        val homeBmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val homeOcrWithStickerBadge = listOf(
            OcrItem("首夏", Rect(60, 250, 180, 315)),
            OcrItem("步敷", Rect(320, 250, 440, 315)),
            OcrItem("時間", Rect(580, 250, 700, 315)),
            OcrItem("遊戲", Rect(840, 250, 960, 315)),
            OcrItem("貼圖任務", Rect(720, 825, 920, 891)),
            OcrItem("移動", Rect(80, 1850, 220, 1920)),
            OcrItem("步數", Rect(460, 2440, 620, 2510)),
            OcrItem("好友", Rect(820, 2440, 960, 2510))
        )
        assertTrue(
            "主畫面頂部 '首夏' 與懸浮徽章 '貼圖任務' 必須穩定識別為主畫面",
            ocr.isLineWalkMainScreenVisible(homeBmp, homeOcrWithStickerBadge, TemplateMatcher())
        )

        // 2. FloatingOverlayService 狀態膠囊顯示正確區分 設定保護 / 待機 / 廣告保護期 / 外部保護
        val overlaySvc = FloatingOverlayService()
        val settingsStatus = EngineUiStatus(
            isRunning = true,
            state = EngineState.AD_WATCHING,
            statusDetail = "[等待] 位於助手主畫面，已暫停點擊...",
            overlaySafeDetail = "[等待] 保護設定暫停中"
        )
        assertEquals("[設定保護] 暫停點擊中", overlaySvc.formatActiveStatusText(settingsStatus, 65))

        val lockStatus = EngineUiStatus(
            isRunning = true,
            state = EngineState.IDLE,
            statusDetail = "[待機] 螢幕鎖定或AOD休眠中",
            overlaySafeDetail = "[待機] 螢幕鎖定暫停"
        )
        assertEquals("[待機] 鎖屏暫停中", overlaySvc.formatActiveStatusText(lockStatus, 65))

        val graceStatus = EngineUiStatus(
            isRunning = true,
            state = EngineState.AD_WATCHING,
            statusDetail = "[防護] 廣告播放保護期 (0s)",
            overlaySafeDetail = "[防護] 廣告播放保護期 (0s)"
        )
        val graceLabel = overlaySvc.formatActiveStatusText(graceStatus, 65)
        assertNotEquals("正常廣告保護期絕不可被 '防護' 關鍵字誤顯為 [外部保護] 安全退出中", "[外部保護] 安全退出中", graceLabel)

        // 3. 三星截圖工具 smartcapture 必須判定為被動覆蓋工具，不可觸發外部重定向護欄
        assertTrue(AppConfig.isPassiveOverlayUtilityPackage("com.samsung.android.app.smartcapture"))

        // 4. 廣告中自動返回意外退回助手頁 (com.linewalk.autoad) 自動拉回遊戲；使用者手動點開設定則保持暫停
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        val instanceField = AutoAdAccessibilityService::class.java.getDeclaredField("_instance").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val serviceFlow = instanceField.get(null) as MutableStateFlow<AutoAdAccessibilityService?>
        val uiStatusField = AutomationEngine::class.java.getDeclaredField("_uiStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statusFlow = uiStatusField.get(engine) as MutableStateFlow<EngineUiStatus>
        val origSvc = serviceFlow.value

        try {
            // Case A: 剛執行完自動返回 (500ms 前) 意外落入助手主畫面 -> 自動拉回遊戲並累計指標
            engine.clearFullLogs()
            engine.selfFgAutoRecoverTotalCount = 0
            val accidentalSelfSvc = createMockService(ctx.packageName)
            every { accidentalSelfSvc.getLastBackActionTimeMs() } returns (System.currentTimeMillis() - 500L)
            serviceFlow.value = accidentalSelfSvc
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.AD_WATCHING)

            engine.executeSingleTick()
            verify(atLeast = 1) { accidentalSelfSvc.bringAppToFront(any()) }
            assertEquals(1, engine.selfFgAutoRecoverTotalCount)
            assertTrue("匯出報告應包含助手頁自動拉回=1", engine.getExportLogsFormatted().contains("助手頁自動拉回=1"))

            // Case B: 使用者手動開啟設定 (markUserOpenedSettings) -> 尊重使用者意圖，絕不拉回遊戲
            engine.markUserOpenedSettings()
            val userSelfSvc = createMockService(ctx.packageName)
            every { userSelfSvc.getLastBackActionTimeMs() } returns (System.currentTimeMillis() - 500L)
            serviceFlow.value = userSelfSvc
            engine.transitionTo(EngineState.AD_WATCHING, "廣告監控中...", "[廣告] 監控中...")
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.AD_WATCHING)

            engine.executeSingleTick()
            verify(exactly = 0) { userSelfSvc.bringAppToFront(any()) }
        } finally {
            serviceFlow.value = origSvc
            engine.stop("測試清理")
        }
    }
}
