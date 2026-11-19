package com.linewalk.autoad.test.v11595

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
import kotlin.math.abs

/**
 * v1.15.95 Robolectric 回歸與實機截圖幾何重播測試（針對 SM-S7210 韓系 MoneyWalk 廣告日誌與雙截圖）：
 * 1. `TemplateMatcher.hasCapsuleExtension` 改採「同水平掃描線文字明暗跳變」判定，徹底解決跨騎黑邊交界線 (`y = effTop = 248`)
 *    之獨立左上角 `[▶|]` 略過鈕遭黑邊／亮底跨列混合誤拒，同時保留橫向文字倒數膠囊排除與無按鈕畫面零誤報；`Step 4C` 於 4 秒起即時搶點。
 * 2. Level 3 多工卡片精準滑除 (`forceKillStuckGameTaskViaRecents`)：當 `CLEAR_TASK` 仍無法消滅頑固廣告 Activity 時，
 *    開啟多工並僅於正向識別「散步趣／LINE Walk」卡片後上滑強制結束任務再冷啟動；未確認卡片絕不盲滑，已落入 Splash 載入頁時不誤殺。
 * 3. 懸浮窗狀態文案區分「監控中」與「強退中」，避免一般廣告監控第 12 秒誤顯示「強退中」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_95SimulationTest {

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

    /** 重現 SM-S7210 實機韓系廣告截圖幾何：頂部黑邊 y < 248，下方淺灰卡片 rgb(217,217,217)，可選繪製跨騎 y=248 之左上角 [▶|] 圓鈕 */
    private fun buildLetterboxSeamMoneyWalkBitmap(withSkipPill: Boolean): Bitmap {
        val sw = 1080
        val sh = 2340
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLACK)
        fillRect(bmp, 0, 248, sw, 2169, Color.rgb(217, 217, 217))
        // 模擬左上角橘黃鞋圖示 (y=280..450) 與右上角小圖示，確認無按鈕時絕不誤報
        fillRect(bmp, 24, 280, 200, 450, Color.rgb(255, 186, 13))
        if (withSkipPill) {
            val cx = 71
            val cy = 248
            val rad = 30
            for (dy in -rad..rad) {
                for (dx in -rad..rad) {
                    if (dx * dx + dy * dy <= rad * rad) {
                        bmp.setPixel(cx + dx, cy + dy, Color.rgb(88, 88, 92))
                    }
                }
            }
            // 繪製純白實心三角形 ▶ 與右側豎槓 |
            for (dy in -10..10) {
                val maxDx = (10 - abs(dy)) * 12 / 10
                for (dx in -9..(-9 + maxDx)) {
                    bmp.setPixel(cx + dx, cy + dy, Color.WHITE)
                }
                for (dx in 6..9) {
                    bmp.setPixel(cx + dx, cy + dy, Color.WHITE)
                }
            }
        }
        return bmp
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
            every { bringAppToFront(any()) } returns true
            every { forceResetAppTaskToFront(any(), any()) } returns true
            every { lastBackRejectReason } returns ""
            coEvery { clickRect(any(), any(), any()) } returns true
            coEvery { performSwipe(any(), any(), any(), any(), any()) } returns true
        }
    }

    @Test
    fun test01_letterboxSeamFastForwardSkipPillDetectedAndCountdownCapsuleStillExcluded() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val tm = TemplateMatcher()
        val withPillBmp = buildLetterboxSeamMoneyWalkBitmap(withSkipPill = true)
        val withoutPillBmp = buildLetterboxSeamMoneyWalkBitmap(withSkipPill = false)

        // 1. 跨騎黑邊交界線 (effTop=248) 的獨立左上角 [▶|] 必須穩定命中 (71, 248)
        val match = tm.detectCornerFastForwardSkip(withPillBmp)
        assertNotNull("跨騎黑邊交界線 y=248 的左上角 [▶|] 略過鈕不可再被 hasCapsuleExtension 誤拒", match)
        assertEquals("左上角略過鈕(▶|)", match!!.label)
        assertTrue("中心 X 應接近 71: ${match.bounds.centerX()}", abs(match.bounds.centerX() - 71) <= 12)
        assertTrue("中心 Y 應接近 248: ${match.bounds.centerY()}", abs(match.bounds.centerY() - 248) <= 12)

        // 2. AdCloserHandler 於 elapsedSec = 4 即刻搶點，避免 2–3 秒快閃略過鈕消失
        val handler = AdCloserHandler(OcrRecognizer(), NodeInspector(), tm, AppConfig(ctx))
        val mockService = createMockService("com.linecorp.LGWKTW")
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 4_000L)
        val clickRes = handler.processAdTick(mockService, withPillBmp, emptyList())
        assertTrue("4 秒起出現 [▶|] 必須立即搶點: $clickRes", clickRes is AdStepResult.ClickedCloseOrSkip)
        assertEquals("左上角略過鈕(▶|)", (clickRes as AdStepResult.ClickedCloseOrSkip).sourceLabel)

        // 3. [▶|] 消失後的乾淨畫面絕不可誤報橘黃鞋圖示
        assertNull("略過鈕消失後不得誤報其他畫面元素", tm.detectCornerFastForwardSkip(withoutPillBmp))

        // 4. 真正的橫向倒數文字膠囊仍必須被同列明暗跳變偵測攔截
        val capsuleBmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(220, 220, 220))
        }
        fillRect(capsuleBmp, 42, 132, 420, 192, Color.rgb(65, 65, 65))
        for (dy in -10..10) {
            val maxDx = (10 - abs(dy)) * 12 / 10
            for (dx in -9..(-9 + maxDx)) capsuleBmp.setPixel(76 + dx, 162 + dy, Color.WHITE)
            for (dx in 6..9) capsuleBmp.setPixel(76 + dx, 162 + dy, Color.WHITE)
        }
        fillRect(capsuleBmp, 126, 148, 152, 178, Color.WHITE)
        fillRect(capsuleBmp, 162, 148, 188, 178, Color.WHITE)
        fillRect(capsuleBmp, 202, 148, 232, 178, Color.WHITE)
        assertNull("橫向文字倒數膠囊必須持續被過濾排除", tm.detectCornerFastForwardSkip(capsuleBmp))
    }

    @Test
    fun test02_level3RecentsDismissKillsStuckAdWhenClearTaskFailsAndNeverBlindSwipes() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()

        val ocrField = AutomationEngine::class.java.getDeclaredField("ocrRecognizer").apply { isAccessible = true }
        val origOcr = ocrField.get(engine)

        try {
            // 情境 A：多工畫面正向識別到「LINE 散步趣」卡片 (x=540) -> 向上滑除該卡片並冷啟動
            val recentsSvc = createMockService("com.sec.android.app.launcher")
            every { recentsSvc.openRecents() } returns true
            every { recentsSvc.findRecentsAppCardCenterX(any(), any()) } returns 540
            val killed = engine.forceKillStuckGameTaskViaRecents(recentsSvc, "com.linecorp.LGWKTW", 1080, 2340)
            assertTrue("正向鎖定散步趣卡片後應回傳 true", killed)
            verify(exactly = 1) { recentsSvc.openRecents() }
            coVerify(exactly = 1) { recentsSvc.performSwipe(540f, any(), 540f, any(), any()) }
            verify(atLeast = 1) { recentsSvc.pressHome() }
            verify(atLeast = 1) { recentsSvc.bringAppToFront("com.linecorp.LGWKTW") }

            // 情境 B：多工畫面僅有其他應用（無「散步趣／LINE Walk」）-> 嚴格禁止盲滑！
            val spiedOcr = spyk(OcrRecognizer()).apply {
                coEvery { recognize(any()) } returns listOf(OcrItem("YouTube", Rect(400, 300, 680, 360)))
            }
            ocrField.set(engine, spiedOcr)
            val otherAppRecentsSvc = createMockService("com.sec.android.app.launcher")
            every { otherAppRecentsSvc.openRecents() } returns true
            every { otherAppRecentsSvc.findRecentsAppCardCenterX(any(), any()) } returns null
            coEvery { otherAppRecentsSvc.captureScreenBitmap() } answers {
                Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            }
            val notKilled = engine.forceKillStuckGameTaskViaRecents(otherAppRecentsSvc, "com.linecorp.LGWKTW", 1080, 2340)
            assertFalse("未確認散步趣卡片時必須回傳 false 且不可盲滑", notKilled)
            coVerify(exactly = 0) { otherAppRecentsSvc.performSwipe(any(), any(), any(), any(), any()) }
        } finally {
            ocrField.set(engine, origOcr)
            engine.stop("測試清理")
        }
    }

    @Test
    fun test03_overlayPillShowsMonitoringInsteadOfForceEscapingDuringNormalAdWatch() {
        val overlaySvc = FloatingOverlayService()
        val monitoringStatus = EngineUiStatus(
            isRunning = true,
            state = EngineState.AD_WATCHING,
            statusDetail = "偵測到廣告/宣傳視窗，安全等待與關閉中 (12s)...",
            overlaySafeDetail = "[廣告] 廣告監控中 (12s)"
        )
        val monitoringText = overlaySvc.formatActiveStatusText(monitoringStatus, maxAdWaitSec = 65)
        assertTrue("一般廣告監控應顯示「監控中」而非「強退中」: $monitoringText", monitoringText.contains("監控中"))
        assertFalse("一般廣告監控不得誤顯示「強退中」: $monitoringText", monitoringText.contains("強退中"))

        val forceEscapeStatus = EngineUiStatus(
            isRunning = true,
            state = EngineState.AD_WATCHING,
            statusDetail = "[監控] 嘗試 末期安全返回鍵 關閉廣告 (50s)...",
            overlaySafeDetail = "[監控] 嘗試關閉 (50s)..."
        )
        val forceText = overlaySvc.formatActiveStatusText(forceEscapeStatus, maxAdWaitSec = 65)
        assertTrue("實際嘗試退回／強退時仍須顯示「強退中」: $forceText", forceText.contains("強退中"))
    }
}
