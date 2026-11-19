package com.linewalk.autoad.test.v11593

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
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
 * v1.15.93 Robolectric 回歸與實機日誌重播測試（針對 SM-F731N `20261119_020005` / `081305` 實機日誌殘留問題）：
 * 1. `clearRedirectMemory()`／`clearCurrentClickContext()` 與 `markRecentRedirect()` 之 12 秒點擊歸因時間窗，防止數分鐘後的系統插斷（如 `com.google.android.calendar`）追溯誤封真實 `inmobi_close_img` `(85, 157)`。
 * 2. `AutomationEngine.handleExternalAppGuard` 僅在 `AD_WATCHING` 狀態下記錄廣告外部重定向歸因，避免 `IN_GAME_SCAN` 期間切換 App 汙染黑名單。
 * 3. `recordAndClick` 於「備援盲點」點擊時不重置 `swallowedEndCardBackAttempts`，讓無文字靜音/圖片廣告在兩次吞鍵後提前於 ~53s 升級逃脫，不再與超時返回鍵互洗至 66s Watchdog。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_93SimulationTest {

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
    fun test01_clearRedirectMemoryAndStaleClickWindowNeverBlacklistInmobiClose() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(18, 18, 18)) }

        val inmobiCloseNode = mockk<AccessibilityNodeInfo>(relaxed = true) {
            every { isVisibleToUser } returns true
            every { packageName } returns "com.linecorp.LGWKTW"
            every { viewIdResourceName } returns "com.inmobi.ads:id/inmobi_close_img"
            every { text } returns ""
            every { contentDescription } returns "Close"
            every { childCount } returns 0
            every { getBoundsInScreen(any()) } answers {
                firstArg<Rect>().set(60, 132, 110, 182)
            }
        }
        val mockService = createMockService("com.linecorp.LGWKTW")
        every { mockService.getSafeRootNode() } returns inmobiCloseNode

        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val lastPtField = AdCloserHandler::class.java.getDeclaredField("lastClickedPoint").apply { isAccessible = true }
        val lastClickMsField = AdCloserHandler::class.java.getDeclaredField("lastAdClickTimeMs").apply { isAccessible = true }

        // 1. 正常點擊 inmobi_close_img @ (85, 157)
        startField.setLong(handler, System.currentTimeMillis() - 10_000L)
        val clickRes = handler.processAdTick(mockService, bmp, emptyList())
        assertTrue(clickRes is AdStepResult.ClickedCloseOrSkip)

        // 2. clearRedirectMemory 必須連同清空 lastClickedPoint 與 lastClickedAdLabel
        handler.clearRedirectMemory()
        assertNull("clearRedirectMemory 必須清空 lastClickedPoint", lastPtField.get(handler))
        assertNull("clearRedirectMemory 必須清空 lastClickedAdLabel", handler.getLastClickedAdLabel())

        // 3. 若距前次點擊已超過 12 秒（模擬數分鐘後的無關系統視窗切出），markRecentRedirect() 絕不可追溯拉黑 (85, 157)
        handler.onEnterAdMode()
        startField.setLong(handler, System.currentTimeMillis() - 10_000L)
        handler.processAdTick(mockService, bmp, emptyList())
        lastClickMsField.setLong(handler, System.currentTimeMillis() - 30_000L)

        handler.markRecentRedirect(fromExternalApp = true)
        assertFalse(
            "超過 12 秒點擊歸因時間窗之外部切出不可誤將 (85, 157) 列入黑名單",
            handler.isInvalidOrBlacklisted(85, 157)
        )
    }

    @Test
    fun test02_externalAppGuardInGameScanNeverCallsMarkRecentRedirect() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()

        val adCloserField = AutomationEngine::class.java.getDeclaredField("adCloserHandler").apply { isAccessible = true }
        val adCloser = adCloserField.get(engine) as AdCloserHandler
        adCloser.onEnterAdMode()
        adCloser.clearRedirectMemory()

        // 模擬剛剛點過真實 InMobi 關閉鈕 (85, 157)，且已回到 IN_GAME_SCAN 巡邏階段
        val lastPtField = AdCloserHandler::class.java.getDeclaredField("lastClickedPoint").apply { isAccessible = true }
        val lastLabelField = AdCloserHandler::class.java.getDeclaredField("lastClickedAdLabel").apply { isAccessible = true }
        val lastClickMsField = AdCloserHandler::class.java.getDeclaredField("lastAdClickTimeMs").apply { isAccessible = true }
        val redirField = AdCloserHandler::class.java.getDeclaredField("redirectCount").apply { isAccessible = true }

        lastPtField.set(adCloser, android.graphics.Point(85, 157))
        lastLabelField.set(adCloser, "Node[id=inmobi_close_img, txt=, desc=Close]")
        lastClickMsField.setLong(adCloser, System.currentTimeMillis())

        val calService = createMockService("com.google.android.calendar")
        try {
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中")
            engine.handleExternalAppGuard(calService, "com.google.android.calendar")

            assertEquals("IN_GAME_SCAN 下切到日曆不應累計廣告重定向次數", 0, redirField.getInt(adCloser))
            assertFalse("IN_GAME_SCAN 下切到日曆不可將 (85, 157) 列入黑名單", adCloser.isInvalidOrBlacklisted(85, 157))
        } finally {
            adCloser.clearRedirectMemory()
            engine.stop("測試清理")
        }
    }

    @Test
    fun test03_fallbackCornerBlindTapPreservesSwallowedBackCountAndEscalatesBeforeWatchdog() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(16, 16, 16)) }
        val mockService = createMockService("com.linecorp.LGWKTW")
        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), TemplateMatcher(), AppConfig(ctx))

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val fallbackTimeField = AdCloserHandler::class.java.getDeclaredField("lastFallbackCornerTimeMs").apply { isAccessible = true }
        val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
        val swallowedField = AdCloserHandler::class.java.getDeclaredField("swallowedEndCardBackAttempts").apply { isAccessible = true }

        // 1. 46s：觸發第 1 次 備援盲點[螢幕右上角熱區]
        startField.setLong(handler, System.currentTimeMillis() - 46_000L)
        fallbackTimeField.setLong(handler, 0L)
        backTimeField.setLong(handler, 0L)
        val blind1 = handler.processAdTick(mockService, bmp, emptyList())
        assertTrue(blind1 is AdStepResult.WatchdogBackTriggered)
        assertTrue((blind1 as AdStepResult.WatchdogBackTriggered).actionLabel.startsWith("備援盲點"))

        // 2. 47s（盲點冷卻內）：觸發第 1 次 超時安全返回鍵 -> swallowed = 1
        startField.setLong(handler, System.currentTimeMillis() - 47_000L)
        fallbackTimeField.setLong(handler, System.currentTimeMillis())
        val back1 = handler.processAdTick(mockService, bmp, emptyList())
        assertEquals("超時安全返回鍵", (back1 as AdStepResult.WatchdogBackTriggered).actionLabel)
        assertEquals(1, swallowedField.getInt(handler))

        // 3. 50s：觸發第 2 次 備援盲點[內容右上角熱區]，絕不可把 swallowedEndCardBackAttempts 洗回 0！
        startField.setLong(handler, System.currentTimeMillis() - 50_000L)
        fallbackTimeField.setLong(handler, 0L)
        backTimeField.setLong(handler, System.currentTimeMillis())
        val blind2 = handler.processAdTick(mockService, bmp, emptyList())
        assertTrue((blind2 as AdStepResult.WatchdogBackTriggered).actionLabel.startsWith("備援盲點"))
        assertEquals("備援盲點點擊後不可歸零 swallowedEndCardBackAttempts", 1, swallowedField.getInt(handler))

        // 4. 第 2 次 超時安全返回鍵 -> swallowed = 2 -> 下一次即刻升級 ForceBringGameToFront 逃脫，不必卡到 66s Watchdog
        startField.setLong(handler, System.currentTimeMillis() - 51_000L)
        fallbackTimeField.setLong(handler, System.currentTimeMillis())
        backTimeField.setLong(handler, 0L)
        val back2 = handler.processAdTick(mockService, bmp, emptyList())
        assertEquals("超時安全返回鍵", (back2 as AdStepResult.WatchdogBackTriggered).actionLabel)
        assertEquals(2, swallowedField.getInt(handler))

        startField.setLong(handler, System.currentTimeMillis() - 53_000L)
        fallbackTimeField.setLong(handler, System.currentTimeMillis())
        backTimeField.setLong(handler, 0L)
        val escalate = handler.processAdTick(mockService, bmp, emptyList())
        assertTrue("累積 2 次吞鍵後必須於 ~53s 立即升級 ForceBringGameToFront: $escalate", escalate is AdStepResult.ForceBringGameToFront)
        assertTrue((escalate as AdStepResult.ForceBringGameToFront).reason.contains("END_CARD_BACK_SWALLOWED"))
    }
}
