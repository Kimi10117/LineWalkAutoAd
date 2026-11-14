package com.linewalk.autoad.test.v11587

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AdCloserHandler
import com.linewalk.autoad.engine.AdStepResult
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.PatrolPhase
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.coEvery
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
 * v1.15.87 Robolectric 實機日誌回歸與模擬驗證測試 (依據 v1.15.86 實機日誌 20261114_123152)：
 * 1. `OcrRecognizer.findMatch` 針對 "OK" 採全字詞邊界比對並排除 `klook` / `Klook`，不再因 "klook" 含 "ok" 而誤點原生橫幅開啟瀏覽器。
 * 2. `isLineWalkMainScreenVisible` 允許主畫面內嵌原生橫幅含 `Sponsored` 字樣，且具備完整頂部分頁 + 底部導覽列（無領獎/關閉彈窗文字）時不受純像素 `isDimmedCenterModalOpen` 假陽性否決。
 * 3. `AdCloserHandler` 識別簡體 `安装` 與 `Playio` / `馬上體驗` / `换好禮` 結束卡片，且 45 秒後 `超時安全返回鍵` 遭連續吞噬 2 次時於第 3 次（~51 秒）主動升級 `ForceBringGameToFront`。
 * 4. `AutomationEngine.handleInGameScan` Tier 4 逾時自癒機制當畫面具備主畫面特徵 (`hasMainScreenMarkers`) 時禁止派發 `safeNavigateBack`，杜絕誤退至 Android 桌面 (`LAUNCHER_RECOVERY`)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_87SimulationTest {

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

    @Test
    fun test01_findMatchDoesNotMatchKlookAsOk() {
        val ocr = OcrRecognizer()
        val sw = 1080
        val sh = 2640
        val roi = RectF(0.05f, 0.04f, 0.95f, 0.94f)

        val bannerItems = listOf(
            OcrItem("klook", Rect(160, 1600, 310, 1660)),
            OcrItem("Klook", Rect(340, 1600, 500, 1660)),
            OcrItem("book", Rect(520, 1600, 660, 1660)),
            OcrItem("tiktok", Rect(700, 1600, 860, 1660))
        )

        val falseHit = ocr.findMatch(
            items = bannerItems,
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = roi,
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS,
            maxTextLength = 6
        )
        assertNull("klook/Klook/book/tiktok 絕不可誤配 OK 而被當成彈窗按鈕點擊: $falseHit", falseHit)

        val exactOk = ocr.findMatch(
            items = bannerItems + OcrItem("OK", Rect(450, 1300, 630, 1380)),
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = roi,
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS,
            maxTextLength = 6
        )
        assertNotNull("獨立字詞 OK 仍應正常匹配", exactOk)
        assertEquals("OK", exactOk!!.text)

        val chineseConfirm = ocr.findMatch(
            items = bannerItems + OcrItem("關閉", Rect(450, 1900, 630, 1980)),
            keywords = OcrRecognizer.GAME_CLAIM_CONFIRM_KEYWORDS,
            screenWidth = sw,
            screenHeight = sh,
            roiNormalized = roi,
            excludeKeywords = OcrRecognizer.GAME_CLAIM_EXCLUDE_KEYWORDS,
            maxTextLength = 6
        )
        assertNotNull("中文彈窗按鈕 關閉 應正常匹配", chineseConfirm)
        assertEquals("關閉", chineseConfirm!!.text)
    }

    @Test
    fun test02_isLineWalkMainScreenVisibleIgnoresInlineSponsoredAndPixelFalsePositive() {
        val ocr = OcrRecognizer()
        val templateMatcher = TemplateMatcher()
        val sw = 1080
        val sh = 2640

        // 構造會誤觸 isDimmedCenterModalOpen() 的色塊分布（暗緣 + 上下白底面板）
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 90, 85))
        }
        fillRectPixels(bmp, 40, (sh * 0.25f).toInt(), 1040, (sh * 0.32f).toInt(), Color.WHITE)
        fillRectPixels(bmp, 40, (sh * 0.65f).toInt(), 1040, (sh * 0.74f).toInt(), Color.WHITE)

        val mainScreenWithInlineSponsored = listOf(
            OcrItem("首頁", Rect(60, 290, 180, 350)),
            OcrItem("步數", Rect(320, 290, 440, 350)),
            OcrItem("時間", Rect(580, 290, 700, 350)),
            OcrItem("遊戲", Rect(840, 290, 960, 350)),
            OcrItem("距離下次", Rect(180, 640, 520, 700)),
            OcrItem("Sponsored", Rect(120, 1650, 340, 1700)),
            OcrItem("集點", Rect(80, 2400, 200, 2470)),
            OcrItem("好友", Rect(340, 2400, 460, 2470)),
            OcrItem("使用", Rect(600, 2400, 720, 2470)),
            OcrItem("精選", Rect(860, 2400, 980, 2470))
        )

        assertTrue(
            "具備完整四大分頁、底部導覽列且無領獎彈窗文字的主畫面，不可因內嵌 Sponsored 橫幅或純像素假陽性被否決",
            ocr.isLineWalkMainScreenVisible(bmp, mainScreenWithInlineSponsored, templateMatcher)
        )

        // 若有明確的領獎彈窗文字（獲得15張票券！ + 關閉），仍必須判定不在主畫面
        val withModalPopup = mainScreenWithInlineSponsored + listOf(
            OcrItem("獲得15張票券！", Rect(240, 1180, 840, 1260)),
            OcrItem("關閉", Rect(430, 1520, 650, 1600))
        )
        assertFalse(
            "實際開啟領獎彈窗時應判定為非主畫面",
            ocr.isLineWalkMainScreenVisible(bmp, withModalPopup, templateMatcher)
        )

        // 全螢幕廣告（右上角 Google Play + Not interested + CLAIM!）仍必須判定不在主畫面
        val fullScreenAd = listOf(
            OcrItem("Google Play", Rect(745, 224, 948, 286)),
            OcrItem("Not interested", Rect(560, 1980, 880, 2040)),
            OcrItem("CLAIM!", Rect(340, 1750, 740, 1890))
        )
        assertFalse(
            "全螢幕廣告應明確回傳 false",
            ocr.isLineWalkMainScreenVisible(bmp, fullScreenAd, templateMatcher)
        )
    }

    @Test
    fun test03_adCloserHandlerEscalatesPlayioAndSimplifiedInstallEndCards() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(30, 30, 30))
        }
        val startField = AdCloserHandler::class.java.getDeclaredField("adStartTimeMs").apply { isAccessible = true }
        val backTimeField = AdCloserHandler::class.java.getDeclaredField("lastBackActionTimeMs").apply { isAccessible = true }
        val fallbackTimeField = AdCloserHandler::class.java.getDeclaredField("lastFallbackCornerTimeMs").apply { isAccessible = true }

        // 場景 A1：知識王LIVE + 簡體「安装」 End Card (44s < 65s) -> 第 3 次應升級 ForceBringGameToFront
        run {
            val handler = createHandler(ctx)
            val mockService = createMockService("com.linecorp.LGWKTW")
            val zhishikingOcr = listOf(
                OcrItem("知識王LIVE", Rect(360, 320, 720, 410)),
                OcrItem("安装", Rect(560, 560, 780, 640)),
                OcrItem("等你來挑戰", Rect(360, 1680, 720, 1760))
            )
            handler.onEnterAdMode()
            startField.setLong(handler, System.currentTimeMillis() - 44_000L)

            for (attempt in 1..2) {
                backTimeField.setLong(handler, 0L)
                fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
                val res = handler.processAdTick(mockService, bmp, zhishikingOcr)
                assertTrue("知識王LIVE 簡體安装第 $attempt 次應觸發結束卡片返回: $res", res is AdStepResult.WatchdogBackTriggered)
                assertEquals("廣告結束卡片安全返回鍵", (res as AdStepResult.WatchdogBackTriggered).actionLabel)
            }
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
            val esc = handler.processAdTick(mockService, bmp, zhishikingOcr)
            assertTrue("知識王LIVE 簡體安装連續 2 次 Back 遭吞噬應升級 ForceBringGameToFront: $esc", esc is AdStepResult.ForceBringGameToFront)
        }

        // 場景 A2：Playio「换好禮 / 馬上體驗」 End Card (44s < 65s)
        run {
            val handler = createHandler(ctx)
            val mockService = createMockService("com.linecorp.LGWKTW")
            val playioOcr = listOf(
                OcrItem("玩遊戲", Rect(400, 1300, 680, 1380)),
                OcrItem("换好禮", Rect(400, 1400, 680, 1480)),
                OcrItem("在Playio 就這麼簡單", Rect(280, 1630, 800, 1700)),
                OcrItem("馬上體驗", Rect(360, 1840, 720, 1930))
            )
            handler.onEnterAdMode()
            startField.setLong(handler, System.currentTimeMillis() - 44_000L)

            for (attempt in 1..2) {
                backTimeField.setLong(handler, 0L)
                fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
                val res = handler.processAdTick(mockService, bmp, playioOcr)
                assertTrue("Playio 第 $attempt 次應觸發結束卡片返回: $res", res is AdStepResult.WatchdogBackTriggered)
                assertEquals("廣告結束卡片安全返回鍵", (res as AdStepResult.WatchdogBackTriggered).actionLabel)
            }
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
            val esc = handler.processAdTick(mockService, bmp, playioOcr)
            assertTrue("Playio 連續 2 次 Back 遭吞噬應升級 ForceBringGameToFront: $esc", esc is AdStepResult.ForceBringGameToFront)
        }

        // 場景 B：無特定關鍵字之純圖像卡死 End Card (48s >= 45s，尚未達 65s) -> 超時安全返回鍵連續 2 次後第 3 次同樣升級 ForceBringGameToFront
        run {
            val handler = createHandler(ctx)
            val mockService = createMockService("com.linecorp.LGWKTW")
            val opaqueAdOcr = listOf(
                OcrItem("Run. Burn. Earn.", Rect(180, 500, 800, 900))
            )
            handler.onEnterAdMode()
            startField.setLong(handler, System.currentTimeMillis() - 48_000L)

            for (attempt in 1..2) {
                backTimeField.setLong(handler, 0L)
                fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
                val res = handler.processAdTick(mockService, bmp, opaqueAdOcr)
                assertTrue("超時安全返回鍵第 $attempt 次應先發送返回鍵: $res", res is AdStepResult.WatchdogBackTriggered)
                assertEquals("超時安全返回鍵", (res as AdStepResult.WatchdogBackTriggered).actionLabel)
            }
            backTimeField.setLong(handler, 0L)
            fallbackTimeField.setLong(handler, System.currentTimeMillis() + 60_000L)
            val esc = handler.processAdTick(mockService, bmp, opaqueAdOcr)
            assertTrue("超時安全返回鍵遭連續吞噬 2 次後應提前升級 ForceBringGameToFront: $esc", esc is AdStepResult.ForceBringGameToFront)
            assertTrue((esc as AdStepResult.ForceBringGameToFront).reason.contains("END_CARD_BACK_SWALLOWED"))
        }
    }

    @Test
    fun test04_tier4IdleSafetyBackNeverExitsMainScreenToLauncher() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        engine.resetClickCircuitBreaker()

        val ocrField = AutomationEngine::class.java.getDeclaredField("ocrRecognizer").apply { isAccessible = true }
        val origOcr = ocrField.get(engine)
        val phaseField = AutomationEngine::class.java.getDeclaredField("currentPhase").apply { isAccessible = true }
        val origPhase = phaseField.get(engine)
        val idleField = AutomationEngine::class.java.getDeclaredField("nonMainScreenIdleTicks").apply { isAccessible = true }
        val origIdle = idleField.getInt(engine)

        val spiedOcr = spyk(OcrRecognizer()).apply {
            every { isLineWalkMainScreenVisible(any(), any(), any()) } returns false
        }
        ocrField.set(engine, spiedOcr)

        try {
            // 使用非 MAIN_TABS 階段避免 Tier 3 (15 ticks) 先攔截，讓輪詢計數直接進入 Tier 4 (>= 20 ticks)
            phaseField.set(engine, PatrolPhase.BINGO)
            idleField.setInt(engine, AutomationEngine.NON_MAIN_IDLE_SAFETY_BACK_TICKS)

            val mockService = createMockService("com.linecorp.LGWKTW")
            val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            val mainScreenWithKlookOcr = listOf(
                OcrItem("首頁", Rect(60, 290, 180, 350)),
                OcrItem("步數", Rect(320, 290, 440, 350)),
                OcrItem("時間", Rect(580, 290, 700, 350)),
                OcrItem("遊戲", Rect(840, 290, 960, 350)),
                OcrItem("klook", Rect(160, 1620, 310, 1680)),
                OcrItem("集點", Rect(80, 2400, 200, 2470)),
                OcrItem("好友", Rect(340, 2400, 460, 2470))
            )

            engine.handleInGameScan(mockService, bmp, mainScreenWithKlookOcr)

            verify(exactly = 0) { mockService.safeNavigateBack(any(), any(), any()) }
            assertEquals("進入主畫面保護轉向後 nonMainScreenIdleTicks 應歸零", 0, idleField.getInt(engine))
        } finally {
            ocrField.set(engine, origOcr)
            phaseField.set(engine, origPhase)
            idleField.setInt(engine, origIdle)
        }
    }
}
