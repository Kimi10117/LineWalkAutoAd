package com.linewalk.autoad.test.v11582

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
import com.linewalk.autoad.vision.TemplateMatchResult
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
 * v1.15.82 Robolectric 實機邏輯回歸與模擬驗證測試：
 * 驗證 v1.15.81 日誌揭露之 5 項問題（含抽抽樂斷線退場循環、網路錯誤彈窗識別與退避、
 * 右上角幾何X內縮誤判與無黑名單連擊、左上角幾何X單次跳轉封鎖、Play商店拉回保留 AD_WATCHING）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_82SimulationTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx)
        } catch (_: Throwable) {
        }
    }

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
    fun test01_lotteryCloseAttemptsPreservedAcrossGetNextPhaseAndEscapesCleanly() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetLotterySessionState(resetCloseAttempts = true)
        engine.isLotteryCompleted = true
        engine.lotteryDialogCloseAttempts = 1

        // 1. getNextPhase(LOTTERY) 絕不可再將 lotteryDialogCloseAttempts 洗歸 0（原 v1.15.81 導致 1->6->0->1 死循環之主因）
        val nextPhase = engine.getNextPhase(PatrolPhase.LOTTERY)
        assertTrue(nextPhase != PatrolPhase.LOTTERY)
        assertEquals(
            "推進下一階段時應保留關閉重試計數器，避免永遠卡在 1..6 迴圈",
            1,
            engine.lotteryDialogCloseAttempts
        )

        // 2. 模擬已完成抽抽樂卻仍停留在抽抽樂視窗，連續進場應穩步跨過 2..6 -> 7 (safeNavigateBack) -> 8 (reset + safeNavigateBack)
        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } returns "com.linecorp.LGWKTW"
            coEvery { clickRect(any(), any(), any()) } returns true
            every { safeNavigateBack(any(), any(), any()) } returns true
            every { getSafeRootNode() } returns null
        }
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val stuckLotteryOcr = listOf(
            OcrItem("抽抽樂", Rect(460, 280, 620, 340)),
            OcrItem("關閉", Rect(440, 1780, 640, 1860))
        )

        // 從 attempts=1 執行 7 個 tick：依序到達 2, 3, 4, 5, 6 (點【集點】+getNextPhase，不歸零!), 7 (safeNavigateBack), 8 (強制重置歸零+撤出)
        repeat(6) { idx ->
            engine.handleInGameScan(mockService, bmp, stuckLotteryOcr)
            val expectedAttempt = idx + 2
            assertEquals("第 ${idx + 1} 拍關閉次數應累加至 $expectedAttempt", expectedAttempt, engine.lotteryDialogCloseAttempts)
            assertTrue("達 7 次前 isLotteryCompleted 應持續成立", engine.isLotteryCompleted)
        }
        // 第 7 拍應觸發第 8 次收尾清理：歸零 lotteryDialogCloseAttempts 並將 isLotteryCompleted 設回 false
        engine.handleInGameScan(mockService, bmp, stuckLotteryOcr)
        assertEquals(0, engine.lotteryDialogCloseAttempts)
        assertFalse(engine.isLotteryCompleted)
        verify(atLeast = 2) { mockService.safeNavigateBack(any(), allowInGame = true, any()) }
    }

    @Test
    fun test02_networkErrorModalDetectedOverLotteryAndTriggersBackoff() = runTest {
        val ocr = OcrRecognizer()
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)

        // 場景 A：背景為抽抽樂視窗，前景未出現任何錯誤文字 -> 不可誤判
        val normalLotteryOcr = listOf(
            OcrItem("抽抽樂", Rect(460, 280, 620, 340)),
            OcrItem("50張抽 1 次", Rect(200, 1400, 880, 1500)),
            OcrItem("關閉", Rect(450, 1780, 630, 1860))
        )
        assertFalse(ocr.isNetworkErrorDialog(normalLotteryOcr))
        assertNull(ocr.findAdLoadErrorCloseButton(bmp, normalLotteryOcr))

        // 場景 B：斷線時散步趣在抽抽樂視窗上方跳出網路錯誤彈窗 -> 必須不被背景「抽抽樂」字樣否決
        val lotteryWithNetErrOcr = listOf(
            OcrItem("抽抽樂", Rect(460, 280, 620, 340)),
            OcrItem("50張抽 1 次", Rect(200, 1400, 880, 1500)),
            OcrItem("網路連線異常，請檢查網路後重試", Rect(160, 1120, 920, 1240)),
            OcrItem("關閉", Rect(450, 1760, 630, 1840))
        )
        assertTrue(ocr.isNetworkErrorDialog(lotteryWithNetErrOcr))
        val errBtn = ocr.findAdLoadErrorCloseButton(bmp, lotteryWithNetErrOcr)
        assertNotNull("前景網路錯誤彈窗即使疊在抽抽樂上方也應精準識別", errBtn)
        assertEquals("錯誤彈窗[關閉]OCR", errBtn!!.first)
        assertEquals(Rect(450, 1760, 630, 1840), errBtn.second)

        // 場景 C：AutomationEngine 處理網路錯誤彈窗時，應累積 consecutiveNetworkErrorCount 並安全退避、不破壞分頁紀錄
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.consecutiveNetworkErrorCount = 0
        engine.resetClickCircuitBreaker()
        engine.confirmedEmptyTabs.clear()
        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } returns "com.linecorp.LGWKTW"
            coEvery { clickRect(any(), any(), any()) } returns true
        }
        engine.handleInGameScan(mockService, bmp, lotteryWithNetErrOcr)
        assertEquals(1, engine.consecutiveNetworkErrorCount)
        assertTrue("網路錯誤退避時不可將正常分頁標記為已領完", engine.confirmedEmptyTabs.isEmpty())
    }

    @Test
    fun test03_topRightGeoXExcludesInner78PercentAndBlacklistsAfter3Misses() = runTest {
        val templateMatcher = TemplateMatcher()

        // 1. 在 1080x2640 模擬 log 誤判座標 (840, 358 | 77.8%, 13.6%) 畫上高對比 X
        val falseInnerBmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
        falseInnerBmp.eraseColor(Color.WHITE)
        drawPixelCircleX(
            bmp = falseInnerBmp,
            cx = 840,
            cy = 358,
            radius = 30,
            armHalfLen = 17,
            halfStroke = 3,
            circleColor = Color.rgb(30, 30, 30),
            xStrokeColor = Color.WHITE
        )
        val innerMatch = templateMatcher.detectCornerGeometricX(falseInnerBmp)
        assertNull("77.8% x=840 內縮圖示應被排除在角落幾何X搜尋之外，實際=$innerMatch", innerMatch)

        // 2. 在 1080x2640 真實角落 (995, 220 | 92.1%, 8.3%) 畫上白叉圓鈕，必須穩定命中
        val realCornerBmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
        realCornerBmp.eraseColor(Color.rgb(20, 20, 20))
        drawPixelCircleX(
            bmp = realCornerBmp,
            cx = 995,
            cy = 220,
            radius = 32,
            armHalfLen = 18,
            halfStroke = 3,
            circleColor = Color.rgb(65, 65, 65),
            xStrokeColor = Color.WHITE
        )
        val realMatch = templateMatcher.detectCornerGeometricX(realCornerBmp)
        assertNotNull("92.1% 角落真實幾何 X 應被正常辨識", realMatch)
        assertTrue(abs(realMatch!!.bounds.centerX() - 995) <= 15)

        // 3. AdCloserHandler 對「右上角幾何X」同一點連續點擊 3 次未退出，必須納入黑名單停止第 4 次重複空點
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfg = AppConfig(ctx)
        val handler = AdCloserHandler(OcrRecognizer(), mockk<NodeInspector>(relaxed = true).apply {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
        }, templateMatcher, cfg)
        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } returns "com.google.android.gms"
            every { getSafeRootNode() } returns null
            coEvery { clickRect(any(), any(), any()) } returns true
        }
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 12_000L)

        repeat(3) {
            val res = handler.processAdTick(mockService, realCornerBmp, emptyList())
            assertTrue("前 3 次應嘗試點擊右上角幾何X: $res", res is AdStepResult.ClickedCloseOrSkip)
        }
        assertTrue(
            "右上角幾何X 連點 3 次未關閉後必須寫入黑名單",
            handler.isInvalidOrBlacklisted(realMatch.bounds.centerX(), realMatch.bounds.centerY())
        )
        val res4 = handler.processAdTick(mockService, realCornerBmp, emptyList())
        assertFalse(
            "加入黑名單後第 4 次不可再死點同一座標: $res4",
            res4 is AdStepResult.ClickedCloseOrSkip && abs(res4.bounds.centerX() - realMatch.bounds.centerX()) <= 20
        )
    }

    @Test
    fun test04_leftCornerGeoXBlacklistedOnFirstExternalBrowserRedirect() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfg = AppConfig(ctx)
        val ocr = OcrRecognizer()
        val templateMatcher = TemplateMatcher()
        val mockNodeInspector = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
        }
        val handler = AdCloserHandler(ocr, mockNodeInspector, templateMatcher, cfg)
        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } returns "com.linecorp.LGWKTW"
            every { getSafeRootNode() } returns null
            coEvery { clickRect(any(), any(), any()) } returns true
        }
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(25, 25, 25))
        drawPixelCircleX(
            bmp = bmp,
            cx = 139,
            cy = 298,
            radius = 30,
            armHalfLen = 17,
            halfStroke = 3,
            circleColor = Color.rgb(65, 65, 65),
            xStrokeColor = Color.WHITE
        )

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 7_000L)

        // 1. 第 1 次點擊 (139, 298) 左上角幾何X
        val click1 = handler.processAdTick(mockService, bmp, emptyList())
        assertTrue("第 1 次應辨識並點擊左上角幾何X: $click1", click1 is AdStepResult.ClickedCloseOrSkip)
        assertEquals("左上角幾何X", (click1 as AdStepResult.ClickedCloseOrSkip).sourceLabel)
        assertTrue(abs(click1.bounds.centerX() - 139) <= 15)

        // 2. 發生第 1 次三星瀏覽器跳轉返回，呼叫 markRecentRedirect
        handler.markRecentRedirect(fromExternalApp = true)

        // 3. 清除暫時冷卻後，驗證 (139, 298) 已寫入永久黑名單，而同側真實關閉鈕 (85, 190 | 距離 120.7px) 仍保持放行
        val retTimeField = AdCloserHandler::class.java.getDeclaredField("lastRedirectReturnTimeMs").apply { isAccessible = true }
        val leftTimeField = AdCloserHandler::class.java.getDeclaredField("lastLeftCornerClickTimeMs").apply { isAccessible = true }
        retTimeField.setLong(handler, 0L)
        leftTimeField.setLong(handler, 0L)

        assertTrue("誤跳瀏覽器的左上角幾何X座標 (139,298) 第 1 次就應進黑名單", handler.isInvalidOrBlacklisted(139, 298))
        assertFalse("真實 inmobi_close_img 座標 (85,190) 不可受牽連", handler.isInvalidOrBlacklisted(85, 190))

        // 冷卻時間過後，第 2 次掃描也不會再點回 (139, 298) 引發第二次跳轉瀏覽器
        val click2 = handler.processAdTick(mockService, bmp, emptyList())
        assertFalse("不可再重複點擊 (139,298): $click2", click2 is AdStepResult.ClickedCloseOrSkip)
    }

    @Test
    fun test05_externalAppGuardPreservesAdWatchingWhenPullingBackToGame() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        val mockService = spykMockServiceForExternalGuard()

        // 場景 A：正處於 AD_WATCHING 時跳轉到 Google Play 商店達 2 次 tick -> 拉回散步趣後必須保留 AD_WATCHING！
        engine.transitionTo(EngineState.AD_WATCHING, "正在監控廣告...", "[廣告] 監控中...")
        assertEquals(EngineState.AD_WATCHING, engine.uiStatus.value.state)

        engine.handleExternalAppGuard(mockService, "com.android.vending") // tick 1 -> SAFE BACK
        engine.handleExternalAppGuard(mockService, "com.android.vending") // tick 2 -> bringAppToFront

        verify(atLeast = 1) { mockService.bringAppToFront(any()) }
        assertEquals(
            "從 Play 商店強制拉回散步趣時，原本的 AD_WATCHING 狀態與計時不可被洗回 IN_GAME_SCAN",
            EngineState.AD_WATCHING,
            engine.uiStatus.value.state
        )

        // 場景 B：若原本處於 IN_GAME_SCAN 才跳往 Play 商店 -> 拉回後維持 IN_GAME_SCAN 恢復巡邏
        engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中")
        engine.handleExternalAppGuard(mockService, "com.android.vending")
        engine.handleExternalAppGuard(mockService, "com.android.vending")
        assertEquals(EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
    }

    private fun spykMockServiceForExternalGuard(): AutoAdAccessibilityService {
        return mockk(relaxed = true) {
            every { getForegroundPackageName() } returns "com.android.vending"
            every { safeNavigateBack(any(), any(), any()) } returns true
            every { bringAppToFront(any()) } returns true
        }
    }
}
