package com.linewalk.autoad.test.v11592

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
import com.linewalk.autoad.engine.EngineUiStatus
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
 * v1.15.92 Robolectric 回歸與實機日誌重播測試（針對 SM-F731N `20261119_000214` 3,998 行實機日誌五大根因）：
 * 1. `inmobi_enhanced_skip_close_btn` 前期惰性節點 14 秒前略過且 `Node[` 關閉目標永不自封；半括號 `"(X"` 可由 `findCornerAsciiCloseButton` 精準識別；`IN_GAME_SCAN` 排除宿主 `close_button_container`。
 * 2. `onEnterAdMode(preserveRedirectState = true)` 保留黑名單與重定向列保護但重置當輪 `redirectCount = 0`，並讓 `JOIN LIVE` 觸發 `SwipedToReveal` 喚醒自動隱藏的 `[▶| Google Play]` 略過膠囊。
 * 3. 點擊真實關閉/略過按鈕後延後 `lastBackActionTimeMs`，避免 1.5–2 秒後緊接發送 `超時安全返回鍵` 誤退回系統桌面 (`LAUNCHER_RECOVERY`)。
 * 4. 鎖屏「請勿打擾 / 開啟」OCR 與緊接桌面恢復抑制、以及 `"LINE 散步趣"@50%,56%` 啟動過渡畫面不再誤判廣告或強開重啟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_92SimulationTest {

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

    @Test
    fun test01_inmobiEnhancedSkipCloseNodeDoesNotSelfBlacklistAndHalfBracketCornerXIsClicked() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(24, 24, 24)) }

        // 1. NodeInspector 於 isFullScreenAd=false 時必須排除 LINE Mini-App 宿主空 close_button_container
        val containerCloseNode = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isVisibleToUser } returns true
            every { packageName } returns "jp.naver.line.android"
            every { viewIdResourceName } returns "jp.naver.line.android:id/close_button_container"
            every { text } returns ""
            every { contentDescription } returns ""
            every { childCount } returns 0
            every { getBoundsInScreen(any()) } answers {
                firstArg<Rect>().set(960, 120, 1040, 200)
            }
        }
        val inspector = NodeInspector()
        assertNull(
            "IN_GAME_SCAN (isFullScreenAd=false) 不可誤點無字/無 desc 的 close_button_container",
            inspector.findAdCloseNode(containerCloseNode, sw, sh, isFullScreenAd = false)
        )
        assertNotNull(
            "全螢幕廣告階段 (isFullScreenAd=true) 仍保留 close_button_container 備援",
            inspector.findAdCloseNode(containerCloseNode, sw, sh, isFullScreenAd = true)
        )

        // 2. OcrRecognizer.findCornerAsciiCloseButton 必須匹配半括號圓框 "(X"@7%,11%
        val ocr = OcrRecognizer()
        val halfBracketX = OcrItem("(X", Rect(60, 282, 110, 332))
        val cornerHit = ocr.findCornerAsciiCloseButton(listOf(halfBracketX), sw, sh)
        assertNotNull("應匹配半括號 '(X' 角落關閉字元", cornerHit)
        assertEquals("(X", cornerHit!!.text)

        // 3. inmobi_enhanced_skip_close_btn 於 14 秒前略過惰性節點、14 秒後連續點擊亦不得自我加入黑名單
        val inmobiNode = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isVisibleToUser } returns true
            every { packageName } returns "com.linecorp.LGWKTW"
            every { viewIdResourceName } returns "com.inmobi.ads:id/inmobi_enhanced_skip_close_btn"
            every { text } returns ""
            every { contentDescription } returns ""
            every { childCount } returns 0
            every { getBoundsInScreen(any()) } answers {
                firstArg<Rect>().set(60, 282, 110, 332)
            }
        }
        val mockService = createMockService("com.linecorp.LGWKTW")
        every { mockService.getSafeRootNode() } returns inmobiNode

        val handler = AdCloserHandler(ocr, inspector, TemplateMatcher(), AppConfig(ctx))
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }

        // 6 秒時不得點擊惰性 inmobi_enhanced_skip_close_btn；但畫面若有半括號 "(X" 仍可由 Step 7A2 點擊
        startField.setLong(handler, System.currentTimeMillis() - 6_000L)
        val tickBefore14NoOcr = handler.processAdTick(mockService, bmp, listOf(OcrItem("Ad", Rect(30, 2500, 90, 2550))))
        assertFalse("14 秒前不可點擊惰性 inmobi_enhanced_skip_close_btn: $tickBefore14NoOcr", tickBefore14NoOcr is AdStepResult.ClickedCloseOrSkip)

        val tickWithHalfBracket = handler.processAdTick(mockService, bmp, listOf(halfBracketX))
        assertTrue(
            "應透過 OCR-角落[(X] 點擊半括號關閉字元: $tickWithHalfBracket",
            (tickWithHalfBracket as? AdStepResult.ClickedCloseOrSkip)?.sourceLabel?.contains("(X") == true
        )

        // 15 秒起即使連點 5 次 inmobi_enhanced_skip_close_btn 亦不可自我拉黑 (85, 307)
        handler.onEnterAdMode()
        repeat(5) {
            startField.setLong(handler, System.currentTimeMillis() - 16_000L)
            val res = handler.processAdTick(mockService, bmp, listOf(OcrItem("Ad", Rect(30, 2500, 90, 2550))))
            assertTrue("14 秒後應點擊 inmobi_enhanced_skip_close_btn: $res", res is AdStepResult.ClickedCloseOrSkip)
        }
        assertFalse(
            "InMobi Node 連點不可將 (85, 307) 自我加入黑名單",
            handler.isInvalidOrBlacklisted(85, 307)
        )
    }

    @Test
    fun test02_preserveRedirectStateKeepsBlacklistButResetsRedirectCountAndWakesHiddenPill() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 20, 20)) }
        val mockService = createMockService("com.linecorp.LGWKTW")
        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))

        // 1. 模擬首次觀看引發兩次外部重定向，座標被納入 blacklistedPoints 與 redirectPointCounts
        handler.onEnterAdMode(preserveRedirectState = false)
        handler.markRecentRedirect(Point(987, 175), fromExternalApp = true)
        handler.markRecentRedirect(Point(994, 258), fromExternalApp = true)
        assertTrue(handler.isInvalidOrBlacklisted(987, 175))
        assertTrue(handler.hasRecentRedirectWithin(180_000L))

        // 2. Watchdog 重入並傳入 preserveRedirectState = true
        handler.onEnterAdMode(preserveRedirectState = true)
        val redirField = AdCloserHandler::class.java.getDeclaredField("redirectCount").apply { isAccessible = true }
        assertEquals("preserveRedirectState = true 重入新週期時 redirectCount 必須歸零", 0, redirField.getInt(handler))
        assertTrue("重入後仍須保留舊黑名單座標 (987, 175)", handler.isInvalidOrBlacklisted(987, 175))
        assertTrue("hasRecentRedirectWithin 仍須藉由 redirectPointCounts/blacklistedPoints 回傳 true", handler.hasRecentRedirectWithin(180_000L))

        // 右上角曾重定向之商店膠囊同列文字區 ('y' at 922,258) 仍應受到整列封鎖
        val storePillOcr = listOf(OcrItem("Google Play", Rect(745, 232, 948, 284)))
        assertTrue(
            "保留黑名單模式下同列商店膠囊區仍必須封鎖",
            handler.isBlockedByCornerPillOrSurveyOcr(922, 258, storePillOcr, sw, sh)
        )

        // 3. 在 20 秒無倒數 JOIN LIVE 畫面下，因 redirectCount 已歸零，Step 8B 應觸發 SwipedToReveal 喚醒頂部膠囊
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 20_000L)
        val joinLiveOcr = listOf(OcrItem("JOIN LIVE", Rect(420, 1800, 660, 1880)))
        val res = handler.processAdTick(mockService, bmp, joinLiveOcr)
        assertTrue("JOIN LIVE 無按鈕停留應觸發 SwipedToReveal 喚醒隱藏略過膠囊: $res", res is AdStepResult.SwipedToReveal)
        coVerify(atLeast = 1) { mockService.performSwipe(any(), any(), any(), any(), any()) }
    }

    @Test
    fun test03_clickingCloseButtonDelaysWatchdogBackToPreventLauncherExit() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(30, 30, 30)) }
        val mockService = createMockService("com.linecorp.LGWKTW")
        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val lastBackField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }

        // 1. 於 54 秒點擊角落 OCR-角落[X]，應延後 lastBackActionTimeMs (+2000ms)
        startField.setLong(handler, System.currentTimeMillis() - 54_000L)
        lastBackField.setLong(handler, 0L)
        val cornerXOcr = listOf(OcrItem("X", Rect(967, 251, 1007, 291)))
        val clickRes = handler.processAdTick(mockService, bmp, cornerXOcr)
        assertTrue("應點擊 OCR-角落[X]: $clickRes", clickRes is AdStepResult.ClickedCloseOrSkip)
        assertTrue(
            "點擊關閉鈕後 lastBackActionTimeMs 應推遲至未來至少 1 秒以上",
            lastBackField.getLong(handler) > System.currentTimeMillis() + 1_000L
        )

        // 2. 緊接著下一個 tick (56 秒、無按鈕)，不得立即補發超時安全返回鍵把遊戲推回系統桌面
        startField.setLong(handler, System.currentTimeMillis() - 56_000L)
        val nextTick = handler.processAdTick(mockService, bmp, emptyList())
        assertTrue("剛點關閉鈕後緊接 tick 應為 ScanningAd 靜候關閉完成，而非立即按返回: $nextTick", nextTick is AdStepResult.ScanningAd)
        verify(exactly = 0) { mockService.safeNavigateBack(any(), any(), any()) }
    }

    @Test
    fun test04_dndLockscreenOcrAndLineWalkSplashScreenDoNotFalseTriggerAdOrLauncherRecovery() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val ocr = OcrRecognizer()

        // 1. 「請勿打擾 + 開啟 + 星期/時鐘」鎖屏 OCR 辨識驗證
        val dndDateOcr = listOf(
            OcrItem("11月18日週三 21°", Rect(320, 280, 760, 350)),
            OcrItem("請勿打擾", Rect(300, 2380, 520, 2440)),
            OcrItem("開啟", Rect(310, 2450, 440, 2500))
        )
        val dndTimeOcr = listOf(
            OcrItem("05:18", Rect(430, 860, 650, 960)),
            OcrItem("請勿打擾", Rect(430, 2340, 650, 2400)),
            OcrItem("開啟", Rect(430, 2400, 560, 2450))
        )
        val clockDateOnlyOcr = listOf(
            OcrItem("11月18日週三20°", Rect(320, 280, 760, 350)),
            OcrItem("05:33", Rect(430, 500, 650, 580))
        )
        val normalAdWithOpenWord = listOf(
            OcrItem("Google Play", Rect(140, 220, 340, 270)),
            OcrItem("開啟", Rect(820, 220, 960, 270))
        )
        assertTrue(ocr.isLockscreenOrAodOcr(dndDateOcr))
        assertTrue(ocr.isLockscreenOrAodOcr(dndTimeOcr))
        assertTrue(ocr.isLockscreenOrAodOcr(clockDateOnlyOcr))
        assertFalse("含 Google Play 之正常廣告不可誤判為鎖屏", ocr.isLockscreenOrAodOcr(normalAdWithOpenWord))

        // 2. 「LINE 散步趣」Splash 過渡畫面不可當成廣告，亦不可把登出畫面當成 Splash
        val splashOcr = listOf(OcrItem("LINE 散步趣", Rect(320, 1420, 760, 1520)))
        val logoutOcr = listOf(
            OcrItem("LINE 散步趣", Rect(320, 1020, 760, 1120)),
            OcrItem("登入 LINE 帳號", Rect(280, 1800, 800, 1900))
        )
        assertTrue("啟動 Splash 畫面應識別為過渡等待", ocr.isLineWalkSplashOrLoadingScreen(splashOcr, sh))
        assertFalse("登入畫面不可誤判為 Splash", ocr.isLineWalkSplashOrLoadingScreen(logoutOcr, sh))

        // 3. AutomationEngine：AOD 待機後緊接著前景切到系統桌面，應延續待機而非 LAUNCHER_RECOVERY，且 Splash 不轉入 AD_WATCHING
        val engine = AutomationEngine.getInstance(ctx)
        val instanceField = AutoAdAccessibilityService::class.java.getDeclaredField("_instance").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val serviceFlow = instanceField.get(null) as MutableStateFlow<AutoAdAccessibilityService?>
        val uiStatusField = AutomationEngine::class.java.getDeclaredField("_uiStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statusFlow = uiStatusField.get(engine) as MutableStateFlow<EngineUiStatus>
        val origSvc = serviceFlow.value

        try {
            engine.clearFullLogs()
            val lockSvc = createMockService("com.linecorp.LGWKTW").apply {
                every { isDeviceLockedOrNonInteractive() } returns true
            }
            serviceFlow.value = lockSvc
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中...")
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.IN_GAME_SCAN)
            engine.executeSingleTick()

            // 鎖屏待機後 25 秒內短暫回報系統桌面，不得觸發 LAUNCHER_RECOVERY 強制喚醒
            val launcherSvc = createMockService("com.sec.android.app.launcher")
            serviceFlow.value = launcherSvc
            engine.executeSingleTick()
            verify(exactly = 0) { launcherSvc.bringAppToFront(any()) }
            verify(exactly = 0) { launcherSvc.forceResetAppTaskToFront(any(), any()) }
            assertFalse("不應記錄 LAUNCHER_RECOVERY", engine.getFullLogsFormatted().contains("LAUNCHER_RECOVERY"))

            // 重置 lastLockscreenStandbyTimeMs 後測試 handleInGameScan Splash 畫面
            val lastStandbyTimeField = AutomationEngine::class.java.getDeclaredField("lastLockscreenStandbyTimeMs").apply { isAccessible = true }
            lastStandbyTimeField.setLong(engine, 0L)
            val splashService = createMockService("com.linecorp.LGWKTW")
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中...")
            repeat(3) {
                engine.handleInGameScan(splashService, bmp, splashOcr)
            }
            assertEquals("Splash 過渡期間必須留在 IN_GAME_SCAN 而非轉入 AD_WATCHING", EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
            coVerify(exactly = 0) { splashService.clickRect(any(), any(), any()) }
        } finally {
            serviceFlow.value = origSvc
            engine.stop("測試清理")
        }
    }
}
