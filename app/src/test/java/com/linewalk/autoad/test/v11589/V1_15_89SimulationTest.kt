package com.linewalk.autoad.test.v11589

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import com.linewalk.autoad.R
import com.linewalk.autoad.config.AppConfig
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.engine.PatrolPhase
import com.linewalk.autoad.service.AutoAdAccessibilityService
import com.linewalk.autoad.ui.MainActivity
import com.linewalk.autoad.vision.OcrItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.15.89 Robolectric 回歸與實機邏輯測試：
 * 1. AutoAdAccessibilityService 精準區分「本助手懸浮窗 (FloatingOverlayService)」與
 *    「本助手設定主畫面 (MainActivity)」，確保 MainActivity 位於前景時不再回傳背景遊戲套件，
 *    且 performClick / performSwipe 直接拒絕對設定主畫面派發手勢點擊。
 * 2. AutomationEngine.executeSingleTick() 偵測前景為 com.linewalk.autoad 時立刻暫停截圖與點擊並記錄 [防護] 日誌。
 * 3. D3A 嚴格排除每日賓果等子視窗，避免子視窗內建橫幅文字觸發廣告假跳轉，並驗證座標與 [設定] 日誌格式。
 * 4. MainActivity 生命週期與 CheckBox 變更確實記錄 [設定] 歷程且於 pause 後歸零前景旗標。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V1_15_89SimulationTest {

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        MainActivity.isForegroundVisible = false
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
            every { lastBackRejectReason } returns ""
            coEvery { clickRect(any(), any(), any()) } returns true
        }
    }

    @Test
    fun test01_accessibilityServiceDistinguishesMainActivityFromFloatingOverlay() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val selfPkg = ctx.packageName

        try {
            // 場景 A：僅懸浮窗覆蓋於遊戲上，MainActivity 不在前景 -> 仍穿透回傳底層遊戲套件
            MainActivity.isForegroundVisible = false
            val overlayRoot = mockk<AccessibilityNodeInfo>(relaxed = true) {
                every { packageName } returns selfPkg
                every { window } returns mockk<AccessibilityWindowInfo>(relaxed = true) {
                    every { type } returns AccessibilityWindowInfo.TYPE_SYSTEM
                }
            }
            val gameRoot = mockk<AccessibilityNodeInfo>(relaxed = true) {
                every { packageName } returns "com.linecorp.LGWKTW"
            }
            val gameWin = mockk<AccessibilityWindowInfo>(relaxed = true) {
                every { type } returns AccessibilityWindowInfo.TYPE_APPLICATION
                every { isActive } returns true
                every { root } returns gameRoot
            }
            val realSvcOverlay = Robolectric.buildService(AutoAdAccessibilityService::class.java).create().get()
            val svcOverGame = spyk(realSvcOverlay) {
                every { rootInActiveWindow } returns overlayRoot
                every { windows } returns listOf(gameWin)
            }
            assertEquals("com.linecorp.LGWKTW", svcOverGame.getForegroundPackageName())
            assertFalse(svcOverGame.isOwnMainActivityForeground())

            // 場景 B：MainActivity 為作用中 TYPE_APPLICATION 視窗（即使遊戲視窗在背景）-> 必須回傳自身套件並阻擋點擊
            val selfAppRoot = mockk<AccessibilityNodeInfo>(relaxed = true) {
                every { packageName } returns selfPkg
                every { window } returns mockk<AccessibilityWindowInfo>(relaxed = true) {
                    every { type } returns AccessibilityWindowInfo.TYPE_APPLICATION
                }
            }
            val selfAppWin = mockk<AccessibilityWindowInfo>(relaxed = true) {
                every { type } returns AccessibilityWindowInfo.TYPE_APPLICATION
                every { isActive } returns true
                every { root } returns selfAppRoot
            }
            val bgGameWin = mockk<AccessibilityWindowInfo>(relaxed = true) {
                every { type } returns AccessibilityWindowInfo.TYPE_APPLICATION
                every { isActive } returns false
                every { root } returns gameRoot
            }
            val svcSelfActive = spyk(realSvcOverlay) {
                every { rootInActiveWindow } returns overlayRoot
                every { windows } returns listOf(bgGameWin, selfAppWin)
            }
            assertEquals(selfPkg, svcSelfActive.getForegroundPackageName())
            assertTrue(svcSelfActive.isOwnMainActivityForeground())

            // 場景 C：MainActivity.isForegroundVisible = true 時，即使 windows 為空且先前事件為遊戲，仍回傳自身並拒絕點擊／滑動手勢
            MainActivity.isForegroundVisible = true
            val svcEventOnly = spyk(Robolectric.buildService(AutoAdAccessibilityService::class.java).create().get()) {
                every { rootInActiveWindow } returns null
                every { windows } returns emptyList()
            }
            val gameEvt = mockk<AccessibilityEvent>(relaxed = true) {
                every { eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                every { packageName } returns "com.linecorp.LGWKTW"
            }
            svcEventOnly.onAccessibilityEvent(gameEvt)
            assertEquals(selfPkg, svcEventOnly.getForegroundPackageName())
            assertFalse("MainActivity 位於前景時 performClick 必須直接拒絕派發", svcEventOnly.performClick(540f, 1516f))
            assertFalse("MainActivity 位於前景時 performSwipe 必須直接拒絕派發", svcEventOnly.performSwipe(540f, 1500f, 540f, 1000f))
        } finally {
            MainActivity.isForegroundVisible = false
        }
    }

    @Test
    fun test02_enginePausesWithoutClickingWhenMainActivityIsInForeground() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        val instanceField = AutoAdAccessibilityService::class.java.getDeclaredField("_instance").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val serviceFlow = instanceField.get(null) as MutableStateFlow<AutoAdAccessibilityService?>
        val uiStatusField = AutomationEngine::class.java.getDeclaredField("_uiStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statusFlow = uiStatusField.get(engine) as MutableStateFlow<com.linewalk.autoad.engine.EngineUiStatus>
        val origSvc = serviceFlow.value

        try {
            engine.clearFullLogs()
            MainActivity.isForegroundVisible = true
            val mockService = createMockService(ctx.packageName)
            serviceFlow.value = mockService
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中...")
            statusFlow.value = statusFlow.value.copy(isRunning = true, state = EngineState.IN_GAME_SCAN)

            engine.executeSingleTick()
            engine.onMainActivityResumedWhileRunning()

            coVerify(exactly = 0) { mockService.captureScreenBitmap() }
            coVerify(exactly = 0) { mockService.clickRect(any(), any(), any()) }
            val fullLogs = engine.getFullLogsFormatted()
            assertTrue("前景為設定主畫面時應寫入 [防護] 暫停日誌", fullLogs.contains("自動暫停掃描與點擊以防誤觸設定"))
        } finally {
            MainActivity.isForegroundVisible = false
            serviceFlow.value = origSvc
            engine.stop("測試清理")
        }
    }

    @Test
    fun test03_subDialogBlocksD3AAndLogsSettingsAndCoordinatesProperly() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        val bmp = Bitmap.createBitmap(1080, 2640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val phaseField = AutomationEngine::class.java.getDeclaredField("currentPhase").apply { isAccessible = true }
        val cooldownField = AutomationEngine::class.java.getDeclaredField("adWatchdogCooldownTicks").apply { isAccessible = true }
        val origPhase = phaseField.get(engine)

        try {
            engine.clearFullLogs()
            engine.resetClickCircuitBreaker()
            phaseField.set(engine, PatrolPhase.BINGO)
            cooldownField.setInt(engine, 0)
            engine.transitionTo(EngineState.IN_GAME_SCAN, "巡邏中...", "[巡邏] 掃描中...")

            engine.recordSettingChange("3.【遊戲】", "關閉")
            val formattedLogs = engine.getFullLogsFormatted()
            assertTrue(formattedLogs.contains("[設定] 「3.【遊戲】」已設為：關閉"))
            assertTrue(MainActivity.filterImportantLogs(listOf("[2026-11-17 00:00:00] [設定] test")).isNotEmpty())

            assertEquals("@ (890, 1520 | 82.4%, 57.6%)", engine.formatPointCoord(Rect(870, 1500, 910, 1540), 1080, 2640))

            val snap = engine.buildPhoneStateSnapshot("TEST_V11589")
            assertTrue("狀態Snap 應包含 selfFg 欄位", snap.contains("selfFg=false"))
            assertTrue("狀態Snap 應包含目前啟用 tabs", snap.contains("tabs="))

            // 當「每日賓果」冷卻彈窗開啟且畫面內含橫幅廣告關鍵字「Google Play / 下載 / 安裝」時，不得誤切 AD_WATCHING
            val mockService = createMockService("com.linecorp.LGWKTW")
            val bingoWithBannerOcr = listOf(
                OcrItem("每日賓果", Rect(380, 240, 700, 320)),
                OcrItem("距離下次抽選還有 10 分鐘", Rect(240, 420, 840, 480)),
                OcrItem("1 BINGO", Rect(160, 1880, 320, 1940)),
                OcrItem("Google Play 下載 安裝", Rect(300, 2240, 780, 2320))
            )
            engine.handleInGameScan(mockService, bmp, bingoWithBannerOcr)
            assertEquals("子視窗內含橫幅文字不得誤切 AD_WATCHING", EngineState.IN_GAME_SCAN, engine.uiStatus.value.state)
        } finally {
            phaseField.set(engine, origPhase)
            cooldownField.setInt(engine, 0)
            engine.stop("測試清理")
        }
    }

    @Test
    fun test04_mainActivityLifecycleAndCheckboxToggleRecordSettingLogs() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val engine = AutomationEngine.getInstance(ctx)
        val origGacha = engine.config.enableGachaTask
        engine.config.enableGachaTask = true
        engine.clearFullLogs()

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue("MainActivity resume 後 isForegroundVisible 應為 true", MainActivity.isForegroundVisible)
            val activity = controller.get()
            val cbGacha = activity.findViewById<CheckBox>(R.id.cbGachaTask)
            assertTrue(cbGacha.isChecked)

            cbGacha.performClick()
            assertFalse(cbGacha.isChecked)
            assertFalse(engine.config.enableGachaTask)
            assertTrue(engine.getFullLogsFormatted().contains("[設定] 「3.【遊戲】」已設為：關閉"))

            controller.pause()
            assertFalse("MainActivity pause 後 isForegroundVisible 應為 false", MainActivity.isForegroundVisible)
        } finally {
            engine.config.enableGachaTask = origGacha
            MainActivity.isForegroundVisible = false
            controller.destroy()
            engine.clearFullLogs()
        }
    }
}
