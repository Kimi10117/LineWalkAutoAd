package com.linewalk.autoad.test.v11580

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AdCloserHandler
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.engine.EngineUiStatus
import com.linewalk.autoad.tasks.BingoTask
import com.linewalk.autoad.tasks.ReceiveGiftTask
import com.linewalk.autoad.ui.FloatingOverlayService
import com.linewalk.autoad.ui.MainActivity
import com.linewalk.autoad.vision.NodeInspector
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.ScreenViewportCalibrator
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.15.80 Robolectric 模擬實機驗證測試：
 * 針對實作類別執行真實 Bitmap / Rect / SharedPreferences / Configuration 驗證。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_80SimulationTest {

    @Test
    fun test01_appConfigMaxWaitClampAndPackageHelpers() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfg = AppConfig(ctx)
        assertEquals(65, cfg.maxAdWaitSeconds)

        cfg.maxAdWaitSeconds = 10
        assertEquals(20, cfg.maxAdWaitSeconds)

        cfg.maxAdWaitSeconds = 250
        assertEquals(180, cfg.maxAdWaitSeconds)

        cfg.maxAdWaitSeconds = 75
        assertEquals(75, cfg.maxAdWaitSeconds)

        assertTrue(AppConfig.isBrowserPackage("com.sec.android.app.sbrowser"))
        assertTrue(AppConfig.isBrowserPackage("com.android.chrome"))
        assertFalse(AppConfig.isBrowserPackage("com.android.vending"))
        assertTrue(AppConfig.isPlayStoreOrBrowserPackage("com.android.vending"))
        assertTrue(AppConfig.isPlayStoreOrBrowserPackage("com.android.chrome"))
        assertFalse(AppConfig.isPlayStoreOrBrowserPackage("com.linecorp.LGWKTW"))
    }

    @Test
    fun test02_viewportCalibratorRealBitmapLetterboxAndThreeButtonNav() {
        // 1. 摺疊機 22:9 與 16:9 視口測試（真實 Rect 運算）
        val closeFold = ScreenViewportCalibrator.findSubDialogCloseButtonBounds(
            sw = 1080,
            sh = 2640,
            ocrItems = emptyList(),
            screenBitmap = null,
            exactHeaderTitles = listOf("每日賓果"),
            attempt = 1
        )
        assertTrue("摺疊機關閉按鈕 X 應落於右上角區域", closeFold.centerX() in 940..1045)
        assertTrue("摺疊機關閉按鈕 Y 應貼近視窗標題列", closeFold.centerY() in 280..380)

        // 2. 展開摺疊機含左右黑邊之真實 Bitmap 掃描測試（寬 400 x 高 400，左右各 100px 純黑邊）
        val letterboxBmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        letterboxBmp.eraseColor(Color.BLACK)
        for (y in 20 until 380) {
            for (x in 100 until 300) {
                letterboxBmp.setPixel(x, y, Color.WHITE)
            }
        }
        val vp = ScreenViewportCalibrator.detectViewport(400, 400, letterboxBmp, emptyList())
        assertTrue("左側黑邊應被扣除: effectiveLeftX=${vp.effectiveLeftX}", vp.effectiveLeftX in 80..110)
        assertTrue("右側黑邊應被扣除: effectiveRightX=${vp.effectiveRightX}", vp.effectiveRightX in 290..320)
        val mappedMidX = vp.mapContentX(0.5f)
        assertTrue("內容中線映射應落於有效畫布內: $mappedMidX", mappedMidX in 185..215)

        // 3. 三大金剛鍵安全禁區偵測
        val navOcr = listOf(OcrItem("集點", Rect(80, 1660, 200, 1730)))
        assertTrue(ScreenViewportCalibrator.isInsideThreeButtonNavBarZone(1850, 1920, ocrItems = navOcr))
        assertFalse(ScreenViewportCalibrator.isInsideThreeButtonNavBarZone(1680, 1920, ocrItems = navOcr))

        // 4. 連續分段線性內插單調性檢驗
        val y16 = ScreenViewportCalibrator.interpolateSubDialogCloseYRatio(1.778f)
        val y20 = ScreenViewportCalibrator.interpolateSubDialogCloseYRatio(2.15f)
        val y22 = ScreenViewportCalibrator.interpolateSubDialogCloseYRatio(2.444f)
        assertTrue(y16 < y20 && y20 < y22)
    }

    @Test
    fun test03_receiveGiftGreenCapsulePixelGateWithRealBitmap() {
        val task = ReceiveGiftTask()
        val w = 1080
        val h = 1920
        val targetRect = Rect(760, 800, 960, 880)

        // 灰色已領取按鈕應被阻擋
        val grayBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        grayBmp.eraseColor(Color.rgb(215, 215, 215))
        assertFalse(task.hasActiveGreenClaimCapsule(grayBmp, targetRect))

        // LINE 亮綠膠囊按鈕應放行
        val greenBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        greenBmp.eraseColor(Color.WHITE)
        for (y in 810..870) {
            for (x in 770..940) {
                greenBmp.setPixel(x, y, Color.rgb(6, 199, 85))
            }
        }
        assertTrue(task.hasActiveGreenClaimCapsule(greenBmp, targetRect))
    }

    @Test
    fun test04_bingoBaitPromoKeywordsFiltered() {
        val bingo = BingoTask()
        assertTrue(OcrRecognizer.AD_BAIT_PROMO_KEYWORDS.contains("開始遊戲"))
        assertTrue(OcrRecognizer.AD_BAIT_PROMO_KEYWORDS.contains("輕鬆賺"))

        // 右上角若出現含誘餌話術之關閉字樣，必須排除
        val baitOnly = listOf(
            OcrItem("開始遊戲關閉", Rect(900, 220, 1040, 290))
        )
        assertNull(bingo.findCloseButton(baitOnly, 1080, 2400))

        // 合法右上角 X 應正常辨識
        val validClose = listOf(
            OcrItem("開始遊戲關閉", Rect(900, 220, 1040, 290)),
            OcrItem("X", Rect(950, 260, 1020, 320))
        )
        val matched = bingo.findCloseButton(validClose, 1080, 2400)
        assertNotNull(matched)
        assertEquals(Rect(950, 260, 1020, 320), matched)
    }

    @Test
    fun test05_adCloserWatchdogAndRadiusAndOverlayUiHelpers() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cfg = AppConfig(ctx)
        val handler = AdCloserHandler(
            ocrRecognizer = mockk<OcrRecognizer>(relaxed = true),
            nodeInspector = mockk<NodeInspector>(relaxed = true),
            templateMatcher = mockk<TemplateMatcher>(relaxed = true),
            config = cfg
        )

        assertEquals(65, handler.getEffectiveWatchdogLimitSec(65))
        assertEquals(20, handler.getEffectiveWatchdogLimitSec(5))
        assertEquals(64.8, handler.getBlacklistRadiusPx(1080), 0.1)
        assertEquals(28.0, handler.getBlacklistRadiusPx(400), 0.1)
        assertEquals(86.4, handler.getBlacklistRadiusPx(1440), 0.1)

        // UI 日誌過濾器與懸浮膠囊計時格式化
        val filtered = MainActivity.filterImportantLogs(
            listOf(
                "[巡邏] 掃描主畫面按鈕",
                "[計時強退] 廣告停留達 65s 強制退回",
                "[狀態Snap] cause=TEST | fg=com.google.android.gms",
                "[錯誤] 連線逾時"
            )
        )
        assertEquals(3, filtered.size)

        val overlaySvc = FloatingOverlayService()
        val watchingStatus = EngineUiStatus(
            isRunning = true,
            state = EngineState.AD_WATCHING,
            statusDetail = "[廣告監控] 播放中 (32s)"
        )
        val pillText = overlaySvc.formatActiveStatusText(watchingStatus, maxAdWaitSec = 65)
        assertEquals("[廣告監控] 播放中 32s / 65s", pillText)

        // 清空日誌與 OCR 摘要截斷驗證
        try {
            com.google.mlkit.common.sdkinternal.MlKitContext.initializeIfNeeded(ctx)
        } catch (_: Throwable) {
        }
        val engine = AutomationEngine.getInstance(ctx)
        val digest = engine.formatStagnationOcrDigest(
            listOf(
                OcrItem("立即購買超值優惠方案限時搶購不要錯過", Rect(100, 200, 900, 300)),
                OcrItem("領取", Rect(400, 1000, 600, 1100))
            ),
            sw = 1080,
            sh = 2400
        )
        assertTrue(digest.contains("…"))
        assertTrue(digest.contains("領取"))

        engine.clearFullLogs()
        val fullLogs = engine.getFullLogsFormatted()
        assertTrue(fullLogs.contains("螢幕規格："))
        assertTrue(fullLogs.contains("核心設定："))
    }
}
