package com.linewalk.autoad.test.v11583

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
import io.mockk.every
import io.mockk.mockk
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
 * v1.15.83 Robolectric 實機邏輯回歸與模擬驗證測試：
 * 驗證 v1.15.82 實機日誌揭露之 5 項問題：
 * 1. 「起漲K線!4.8 ★▶ Goo…」應用推廣卡誤觸發左上角 [▶| Google Play] 略過鈕，同時漏掉左上角獨立 "X"@6%,6%。
 * 2. 45s 備援「螢幕右下角熱區」誤點底部安裝 CTA 造成 5 次 Play 商店跳轉，且狀態列貼邊右上角熱區未避讓及未過濾黑名單。
 * 3. Watchdog 強退未歸零 adStartTimeMs，導致 6 秒後外部跳轉拉回時誤觸發「74s/65s」秒退。
 * 4. blacklistedPoints 同一座標重複寫入及「無法讀取資料」未納入錯誤彈窗關鍵字。
 * 5. 【任務-每日】綠色「領取」免廣告直領項目每次多等 4 秒並洗版「領獎確認逾時」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_83SimulationTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx)
        } catch (_: Throwable) {
        }
    }

    private fun createMockService(fgPkg: String = "com.google.android.gms"): AutoAdAccessibilityService {
        return mockk(relaxed = true) {
            every { getForegroundPackageName() } returns fgPkg
            every { getSafeRootNode() } returns null
            every { safeNavigateBack(any(), any(), any()) } returns true
            every { navigateBack() } returns true
            every { bringAppToFront(any()) } returns true
            coEvery { clickRect(any(), any(), any()) } returns true
        }
    }

    private fun createHandler(ctx: Context): AdCloserHandler {
        val nodeInspector = mockk<NodeInspector>(relaxed = true) {
            every { findAdCloseNode(any(), any(), any(), any(), any()) } returns null
            every { findNodeByKeywords(any(), any(), any(), any(), any()) } returns null
        }
        return AdCloserHandler(
            ocrRecognizer = OcrRecognizer(),
            nodeInspector = nodeInspector,
            templateMatcher = TemplateMatcher(),
            config = AppConfig(ctx)
        )
    }

    @Test
    fun test01_promoRatingCardExcludedFromGooglePlayPillAndCornerAsciiXClicked() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handler = createHandler(ctx)
        val ocrRecognizer = OcrRecognizer()
        val mockService = createMockService()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 30, 30))
        }

        // 1. 驗證真實左上角 Meta/iHEALTH [▶| Google Play] 藥丸略過鈕仍正常命中
        val realPill = OcrItem("▶| Google Play", Rect(35, 175, 355, 235))
        val detectedRealPill = handler.findTopLeftGooglePlaySkipPill(listOf(realPill), sw, sh)
        assertNotNull("真實左上角 [▶| Google Play] 藥丸略過鈕必須正常辨識", detectedRealPill)

        // 2. 重現 v1.15.82 實機日誌「起漲K線!4.8 ★▶ Google Play」應用評分推廣卡 + 左上角獨立 "X"@6%,6%
        val promoItems = listOf(
            OcrItem("X", Rect(46, 138, 86, 178)),
            OcrItem("股市起漲K線一強勢瘋狗流", Rect(430, 260, 840, 315)),
            OcrItem("起漲K線!4.8 ★▶ Google Play", Rect(224, 395, 604, 460)),
            OcrItem("起漲K線!4.8 ★▶ Goo…", Rect(224, 400, 604, 456)),
            OcrItem("安裝", Rect(480, 520, 600, 585))
        )
        assertNull(
            "帶有星等評分與 App 名稱之推廣卡絕不可誤認為 Google Play 略過藥丸",
            handler.findTopLeftGooglePlaySkipPill(promoItems, sw, sh)
        )

        // 3. 驗證畫面中央或狀態列上緣之 "X" 不可誤點，唯有角落獨立 "X"@6%,6% 會被命中
        val centerBrandX = listOf(
            OcrItem("X", Rect(500, 1280, 580, 1360)),
            OcrItem("X", Rect(500, 25, 540, 55))
        )
        assertNull("畫面正中央或狀態列 X 必須被排除", ocrRecognizer.findCornerAsciiCloseButton(centerBrandX, sw, sh))

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 6_000L)

        val tickResult = handler.processAdTick(mockService, bmp, promoItems)
        assertTrue("應直接識別並點擊角落獨立 X: $tickResult", tickResult is AdStepResult.ClickedCloseOrSkip)
        val clicked = tickResult as AdStepResult.ClickedCloseOrSkip
        assertEquals("OCR-角落[X]", clicked.sourceLabel)
        assertTrue("點擊座標應為左上角 X (66, 158): ${clicked.bounds}", kotlin.math.abs(clicked.bounds.centerX() - 66) <= 10)
    }

    @Test
    fun test02_fallbackHotzoneRemovesBottomRightCtaAndSkipsBlacklistedCorners() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handler = createHandler(ctx)
        val mockService = createMockService()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(25, 25, 25))
        }

        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val fallbackTimeField = AdCloserHandler::class.java.getDeclaredField("lastFallbackCornerTimeMs").apply { isAccessible = true }
        val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
        val blkField = AdCloserHandler::class.java.getDeclaredField("blacklistedPoints").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 48_000L)

        // 1. 輪詢多次備援盲點：不得出現任何「螢幕右下角熱區」，且右上角熱區 Y 必須避開頂部狀態列 (<4.5%)
        val seenLabels = mutableListOf<String>()
        for (i in 0 until 3) {
            fallbackTimeField.setLong(handler, 0L)
            backTimeField.setLong(handler, System.currentTimeMillis())
            val res = handler.processAdTick(mockService, bmp, emptyList())
            assertTrue("第 ${i + 1} 次備援應觸發 WatchdogBackTriggered: $res", res is AdStepResult.WatchdogBackTriggered)
            val actionLabel = (res as AdStepResult.WatchdogBackTriggered).actionLabel
            seenLabels.add(actionLabel)
            assertFalse("不得再點擊易誤觸底部安裝鈕之螢幕右下角熱區: $actionLabel", actionLabel.contains("右下角"))
        }
        assertTrue("應命中 calibrated 螢幕右上角熱區: $seenLabels", seenLabels.any { it.contains("螢幕右上角熱區") })
        assertTrue("應命中 calibrated 內容右上角熱區: $seenLabels", seenLabels.any { it.contains("內容右上角熱區") })

        // 2. 將兩個右側備援點中心都加入黑名單後，備援階段不得再盲點這兩區，必須直接改走安全返回鍵
        @Suppress("UNCHECKED_CAST")
        val blkList = blkField.get(handler) as MutableList<android.graphics.Point>
        blkList.add(android.graphics.Point((sw * 0.915f).toInt(), (sh * 0.066f).toInt()))
        blkList.add(android.graphics.Point((sw * 0.942f).toInt(), (sh * 0.090f).toInt()))

        fallbackTimeField.setLong(handler, 0L)
        backTimeField.setLong(handler, 0L)
        val afterBlacklist = handler.processAdTick(mockService, bmp, emptyList())
        assertTrue("所有備援角已黑名單後應直接改發返回鍵: $afterBlacklist", afterBlacklist is AdStepResult.WatchdogBackTriggered)
        val afterLabel = (afterBlacklist as AdStepResult.WatchdogBackTriggered).actionLabel
        assertFalse("不得再點已黑名單備援盲點: $afterLabel", afterLabel.contains("備援盲點"))
    }

    @Test
    fun test03_watchdogExitClearsAdStartTimeSoReentryDoesNotInstantTimeout() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handler = createHandler(ctx)

        // 1. 模擬上一支廣告在 70 秒時因 Watchdog 超時強制退出
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 70_000L)
        assertTrue(handler.getElapsedSeconds() >= 65)

        // 2. Engine Watchdog 退場必須呼叫 clearAdSessionTimer()
        handler.clearAdSessionTimer()
        assertEquals("clearAdSessionTimer 後 elapsedSeconds 應歸零", 0, handler.getElapsedSeconds())

        // 3. 6 秒後外部跳轉拉回並呼叫 onEnterAdMode(preserveRedirectState = true) -> 不得繼承舊計時！
        handler.onEnterAdMode(preserveRedirectState = true)
        assertTrue(
            "重進 AD_WATCHING 後應從 0 秒重新計時，而非繼承 70 秒立刻超時: ${handler.getElapsedSeconds()}",
            handler.getElapsedSeconds() <= 2
        )
    }

    @Test
    fun test04_blacklistDeduplicationAndCannotReadDataErrorDetected() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val handler = createHandler(ctx)
        val ocrRecognizer = OcrRecognizer()
        val mockService = createMockService()
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(245, 245, 245))
        }

        // 1. 對近乎重疊的「OCR[關閉]」座標 (539,2070) 與 (540,2071) 連續無效點擊 -> blk 去重為 1 筆
        handler.onEnterAdMode()
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        startField.setLong(handler, System.currentTimeMillis() - 6_000L)

        repeat(4) { idx ->
            val dx = if (idx % 2 == 0) 539 else 540
            val dy = if (idx % 2 == 0) 2070 else 2071
            val item = OcrItem("關閉", Rect(dx - 60, dy - 30, dx + 60, dy + 30))
            handler.processAdTick(mockService, bmp, listOf(item))
        }
        val diag = handler.getDiagnosticsSnapshotFields()
        assertTrue("重複座標僅能記錄 1 筆於黑名單 (blk=1): $diag", diag.contains("blk=1["))

        // 2. 驗證抽抽樂期間跳出「無法讀取資料」彈窗時能被正確識別並找到 [關閉] 按鈕
        val errItems = listOf(
            OcrItem("75,750", Rect(200, 120, 320, 175)),
            OcrItem("抽抽樂", Rect(460, 340, 620, 405)),
            OcrItem("查看獎項一覽", Rect(60, 500, 300, 560)),
            OcrItem("無法讀取資料", Rect(360, 815, 720, 880)),
            OcrItem("關閉", Rect(420, 1460, 660, 1550))
        )
        assertTrue("isNetworkErrorDialog 必須辨識「無法讀取資料」", ocrRecognizer.isNetworkErrorDialog(errItems))
        val errClose = ocrRecognizer.findAdLoadErrorCloseButton(bmp, errItems)
        assertNotNull("抽抽樂背景上的「無法讀取資料」中央錯誤彈窗必須定位到關閉按鈕", errClose)
        assertEquals(1505, errClose!!.second.centerY())
    }

    @Test
    fun test05_missionDirectClaimFastReturnsInOneTickWithoutTimeoutLog() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()
        engine.currentPhase = PatrolPhase.MISSION

        val mockService = mockk<AutoAdAccessibilityService>(relaxed = true) {
            every { getForegroundPackageName() } returns "com.linecorp.LGWKTW"
            every { getSafeRootNode() } returns null
        }
        val sw = 1080
        val sh = 2640
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val missionItems = listOf(
            OcrItem("任務", Rect(480, 210, 600, 270)),
            OcrItem("特別", Rect(100, 420, 240, 480)),
            OcrItem("每日", Rect(470, 420, 610, 480)),
            OcrItem("每週", Rect(830, 420, 970, 480)),
            OcrItem("領取", Rect(820, 950, 980, 1020))
        )

        val pendingField = AutomationEngine::class.java.getDeclaredField("pendingMissionDirectClaim").apply { isAccessible = true }
        val retryField = AutomationEngine::class.java.getDeclaredField("popupRetryTicks").apply { isAccessible = true }
        pendingField.setBoolean(engine, true)
        retryField.setInt(engine, 0)
        engine.transitionTo(EngineState.TRIGGER_AD_POPUP, "【任務-每日】已點擊領取...", "[目標] 領取獎勵中...")
        assertTrue("進入 TRIGGER_AD_POPUP 時應保留 pendingMissionDirectClaim", pendingField.getBoolean(engine))

        // 僅 1 個 tick 仍在任務視窗即迅速切回 IN_GAME_SCAN，且不觸發「領獎確認逾時」錯誤日誌
        engine.handleTriggerAdPopup(mockService, bmp, missionItems)

        assertEquals(EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
        assertFalse("離開 TRIGGER_AD_POPUP 後 pendingMissionDirectClaim 應自動重設", pendingField.getBoolean(engine))
        val logs = engine.uiStatus.value.recentLogs.joinToString("\n")
        assertTrue("應輸出免看廣告直領快速恢復日誌: $logs", logs.contains("免看廣告獎勵已直接領取"))
        assertFalse("不可出現誤導性的領獎確認逾時警告: $logs", logs.contains("領獎確認逾時"))
    }
}
