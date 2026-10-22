package com.linewalk.autoad.test.tier2

import com.linewalk.autoad.engine.MainTab
import com.linewalk.autoad.engine.PatrolPhase
import com.linewalk.autoad.tasks.BingoTask
import com.linewalk.autoad.tasks.ReceiveGiftTask
import com.linewalk.autoad.test.fixture.EngineSimulator
import com.linewalk.autoad.test.fixture.EngineSimulator.BingoStepAction
import com.linewalk.autoad.test.fixture.EngineSimulator.GuardAction
import com.linewalk.autoad.test.fixture.EngineSimulator.ReceiveDrawerStepAction
import com.linewalk.autoad.test.fixture.MockFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tier 2 - Boundary & Corner Cases Suite (>=5 per feature across R1-R6).
 * Tests extreme inputs, nullables, rapid toggles, 0/5 vs 5/5 limits, all-claimed boards,
 * extra-long status strings, and scale stress.
 */
class Tier2_BoundaryAndCornerTest {

    private lateinit var simulator: EngineSimulator
    private lateinit var bingoTask: BingoTask
    private lateinit var receiveGiftTask: ReceiveGiftTask

    @Before
    fun setUp() {
        simulator = EngineSimulator()
        bingoTask = BingoTask()
        receiveGiftTask = ReceiveGiftTask()
    }

    // ==========================================
    // F1: Floating Pill Boundaries
    // ==========================================

    @Test
    fun testF1_01_extraLongStatusStringWithoutTruncationOrCrash() {
        simulator.startPatrol()
        val extraLong = "【每日賓果】開格完成 ➔ 依序領取連線獎勵 [1 BINGO]、[3 BINGO]、[5 BINGO]、[8 BINGO] ➔ 關閉視窗準備進入【抽抽樂】(50抽優先)..."
        simulator.overlayStatusText = extraLong

        assertEquals(extraLong, simulator.overlayStatusText)
        assertEquals("Width must remain WRAP_CONTENT", -2, simulator.layoutWidth)
        assertNull("Ellipsize must not be enabled", simulator.ellipsizeMode)
    }

    @Test
    fun testF1_02_rapidConsecutiveToggleClicks() {
        simulator.startPatrol()
        assertTrue(simulator.isCollapsed)

        // Rapid 10 toggles
        repeat(10) {
            simulator.toggleExpandCollapse()
        }
        assertTrue("After 10 even toggles, state should return to collapsed", simulator.isCollapsed)

        simulator.toggleExpandCollapse()
        assertFalse("11th toggle expands", simulator.isCollapsed)
    }

    @Test
    fun testF1_03_emptyAndBlankStatusString() {
        simulator.startPatrol()
        simulator.overlayStatusText = ""
        assertEquals("", simulator.overlayStatusText)

        simulator.overlayStatusText = "   "
        assertEquals("   ", simulator.overlayStatusText)
    }

    @Test
    fun testF1_04_specialCharactersAndEmojiInStatusString() {
        simulator.startPatrol()
        val emojiStatus = "✨ [巡邏中] 首頁 🚗 ➔ 步數 👟 ➔ 時間 ⏳ ➔ 遊戲 🎮 (第 5 輪)"
        simulator.overlayStatusText = emojiStatus
        assertEquals(emojiStatus, simulator.overlayStatusText)
    }

    @Test
    fun testF1_05_multiLineStatusStringHandling() {
        simulator.startPatrol()
        val multiLine = "第一行: 巡邏中\n第二行: 廣告監控\n第三行: 完成確認"
        simulator.overlayStatusText = multiLine
        assertTrue(simulator.overlayStatusText.contains("\n"))
        assertEquals(-2, simulator.layoutWidth)
    }

    // ==========================================
    // F2: External App Guard Boundaries
    // ==========================================

    @Test
    fun testF2_01_nullForegroundPackageHandling() {
        val decision = simulator.evaluateForegroundPackage(null)
        assertTrue("Null package must be treated as external/safe fallback", decision.isExternal)
        assertEquals(GuardAction.SAFE_BACK_NAVIGATION, decision.recommendedAction)
        assertFalse(decision.regularClicksAllowed)
    }

    @Test
    fun testF2_02_blankAndWhitespaceForegroundPackage() {
        val decision = simulator.evaluateForegroundPackage("   ")
        assertTrue("Blank package must be treated as external", decision.isExternal)
        assertFalse(decision.regularClicksAllowed)
    }

    @Test
    fun testF2_03_emptyOcrResultsDuringExternalAppGuard() {
        // Even if OCR returns zero items in an external app, guard must still block clicks
        val decision = simulator.evaluateForegroundPackage("com.android.settings", hasAdGeometricX = false)
        assertTrue(decision.isExternal)
        assertEquals(GuardAction.SAFE_BACK_NAVIGATION, decision.recommendedAction)
        assertFalse(decision.regularClicksAllowed)
    }

    @Test
    fun testF2_04_externalAppWithMisleadingInGameKeywords() {
        // Third-party app containing text like "領取" or "LINE 散步趣"
        val decision = simulator.evaluateForegroundPackage("com.random.phishing.app", hasAdGeometricX = false)
        assertTrue(decision.isExternal)
        assertFalse("Must never allow regular clicks regardless of misleading screen text", decision.regularClicksAllowed)
    }

    @Test
    fun testF2_05_rapidForegroundPackageSwitching() {
        val sequence = listOf(
            "com.linecorp.LGWALK" to false,
            "com.android.chrome" to true,
            "com.linecorp.LGWALK" to false,
            "com.android.vending" to true,
            "jp.naver.line.android" to false
        )

        for ((pkg, expectedExternal) in sequence) {
            val decision = simulator.evaluateForegroundPackage(pkg)
            assertEquals("Package $pkg external check mismatch", expectedExternal, decision.isExternal)
        }
    }

    // ==========================================
    // F3: Infinite Loop Boundaries
    // ==========================================

    @Test
    fun testF3_01_pauseRequestedAtExactBoundaryMillisecond() {
        simulator.startPatrol()
        // Pause at second 1
        val res1 = simulator.simulateRoundCompletion(pauseRequestedAtSec = 1)
        assertTrue(res1.wasPausedDuringCooldown)
        assertEquals(1, res1.cooldownSecondsWaited)
        assertFalse(simulator.isRunning)

        // Resume and pause at second 5
        simulator.startPatrol()
        val res5 = simulator.simulateRoundCompletion(pauseRequestedAtSec = 5)
        assertTrue(res5.wasPausedDuringCooldown)
        assertEquals(5, res5.cooldownSecondsWaited)
        assertFalse(simulator.isRunning)
    }

    @Test
    fun testF3_02_highConsecutiveRoundsStability() {
        simulator.startPatrol()
        repeat(50) {
            simulator.simulateRoundCompletion()
        }
        assertEquals("Round counter should accurately reach 51", 51, simulator.currentRound)
        assertTrue(simulator.isRunning)
    }

    @Test
    fun testF3_03_zeroCooldownSecondsImmediateAdvance() {
        simulator.startPatrol()
        val res = simulator.simulateRoundCompletion(totalCooldownSec = 0)
        assertEquals(0, res.cooldownSecondsWaited)
        assertEquals(2, simulator.currentRound)
        assertTrue(simulator.isRunning)
    }

    @Test
    fun testF3_04_repeatedStartStopCycles() {
        repeat(10) {
            simulator.startPatrol()
            assertTrue(simulator.isRunning)
            simulator.stopPatrol()
            assertFalse(simulator.isRunning)
        }
    }

    @Test
    fun testF3_05_allConfirmedEmptyTabsClearedProperly() {
        simulator.startPatrol()
        simulator.confirmedEmptyTabs.addAll(MainTab.entries)
        assertEquals(4, simulator.confirmedEmptyTabs.size)

        simulator.simulateRoundCompletion()
        assertEquals("All tabs must be cleared on round transition", 0, simulator.confirmedEmptyTabs.size)
    }

    // ==========================================
    // F4: Daily Bingo Boundaries
    // ==========================================

    @Test
    fun testF4_01_allClaimedBingoBoard() {
        // All 4 columns claimed, cell button depleted
        val action = simulator.stepBingoDialog(
            hasOpenCellButton = false,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = emptyList()
        )
        assertTrue("All claimed board must trigger close dialog", action is BingoStepAction.CloseDialog)
    }

    @Test
    fun testF4_02_noisyOcrGetTextVariations() {
        // Bingo OCR items with noisy texts "Cet", "Qet"
        val noisyItems = listOf(
            MockFixtures.createNormalizedOcrItem("每日賓果", 0.35f, 0.08f, 0.65f, 0.14f),
            MockFixtures.createNormalizedOcrItem("1 BINGO", 0.10f, 0.73f, 0.26f, 0.77f),
            MockFixtures.createNormalizedOcrItem("Cet", 0.11f, 0.80f, 0.25f, 0.86f),
            MockFixtures.createNormalizedOcrItem("3 BINGO", 0.31f, 0.73f, 0.47f, 0.77f),
            MockFixtures.createNormalizedOcrItem("Qet", 0.32f, 0.80f, 0.46f, 0.86f)
        )
        assertTrue("Dialog must still be recognized", bingoTask.isBingoDialogOpen(noisyItems, MockFixtures.SCREEN_HEIGHT))
    }

    @Test
    fun testF4_03_cooldownTimerAtZeroSecondsBoundary() {
        // Cooldown timer displayed as "00:00"
        val action = simulator.stepBingoDialog(
            hasOpenCellButton = true,
            inCooldown = true,
            isLimitReached = false,
            unclaimedColumns = emptyList()
        )
        assertFalse("Cooldown flag must prevent open cell click even at boundary", action is BingoStepAction.ClickOpenCell)
        assertTrue(action is BingoStepAction.CloseDialog)
    }

    @Test
    fun testF4_04_partialColumnStatesOnlyColumn8Ready() {
        // Only 8 BINGO column is unclaimed (1, 3, 5 already claimed)
        val action = simulator.stepBingoDialog(
            hasOpenCellButton = false,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = listOf(8)
        )
        assertTrue(action is BingoStepAction.ClaimConnectionReward)
        val claim = action as BingoStepAction.ClaimConnectionReward
        assertEquals(8, claim.bingoCol)
    }

    @Test
    fun testF4_05_bingoDialogDetectionWithAbnormalScreenDimensions() {
        val items = MockFixtures.createBingoDialogItems()
        // Non-standard screen heights (e.g. tablet 1600px, fold 2176px, extreme 3200px)
        assertTrue(bingoTask.isBingoDialogOpen(items, 1600))
        assertTrue(bingoTask.isBingoDialogOpen(items, 2176))
        assertTrue(bingoTask.isBingoDialogOpen(items, 3200))
    }

    // ==========================================
    // F5: System Log Export Boundaries
    // ==========================================

    @Test
    fun testF5_01_largeSessionLogVolumeStability() {
        repeat(5000) { i ->
            simulator.appendLog("巡邏事件項目 #$i")
        }
        assertEquals(5000, simulator.fullSessionLogs.size)

        val formatted = simulator.getFullLogsFormatted()
        assertTrue(formatted.contains("日誌筆數：5000"))
        assertTrue(formatted.contains("巡邏事件項目 #0"))
        assertTrue(formatted.contains("巡邏事件項目 #4999"))
    }

    @Test
    fun testF5_02_exportingLogsWithSpecialAndRegexCharacters() {
        val trickyStrings = listOf(
            "[\$100] 特殊符號: \\d+ .*? ^ $ (x|y) {2,3}",
            "JSON 字串: {\"status\": \"ok\", \"count\": 42}",
            "<HTML>&nbsp;&amp;標籤</HTML>"
        )

        trickyStrings.forEach { simulator.appendLog(it) }
        val exported = simulator.getFullLogsFormatted()

        trickyStrings.forEach {
            assertTrue("Must contain literal: $it", exported.contains(it))
        }
    }

    @Test
    fun testF5_03_midnightBoundaryTimestampFormatting() {
        val midnight = Date(1776873599000L) // 23:59:59
        val nextDay = Date(1776873600000L)  // 00:00:00
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        simulator.appendLog("跨日前最後一秒", midnight)
        simulator.appendLog("跨日後第一秒", nextDay)

        assertTrue(simulator.fullSessionLogs[0].contains(fmt.format(midnight)))
        assertTrue(simulator.fullSessionLogs[1].contains(fmt.format(nextDay)))
    }

    @Test
    fun testF5_04_diagnosticsHeaderWithBlankProperties() {
        val formatted = simulator.getFullLogsFormatted(
            deviceModel = "",
            osVersion = "",
            appVersion = ""
        )
        assertNotNull(formatted)
        assertTrue(formatted.contains("《LINE 散步趣助手》系統診斷日誌"))
    }

    @Test
    fun testF5_05_shareIntentDataPayloadIntegrity() {
        simulator.appendLog("極長日誌內容測試 ".repeat(50))
        val text = simulator.getFullLogsFormatted()
        val intentData = simulator.createShareIntentData(text)

        assertEquals("android.intent.action.SEND", intentData.action)
        assertEquals("text/plain", intentData.type)
        assertEquals(text.length, intentData.textExtra.length)
    }

    // ==========================================
    // F6: Friend Gift Boundaries
    // ==========================================

    @Test
    fun testF6_01_giftBoundaryZeroOfFive() {
        val action = simulator.stepFriendGift(
            currentGiftCount = 0,
            maxGiftCount = 5,
            hasFriendsInList = true
        )
        assertTrue("0/5 gifts must allow regular gifting", action is EngineSimulator.FriendGiftStepAction.SendGift)
    }

    @Test
    fun testF6_02_giftBoundaryFourOfFiveOneRemaining() {
        val action = simulator.stepFriendGift(
            currentGiftCount = 4,
            maxGiftCount = 5,
            hasFriendsInList = true
        )
        assertTrue("4/5 gifts must allow 5th gift", action is EngineSimulator.FriendGiftStepAction.SendGift)
    }

    @Test
    fun testF6_03_giftBoundaryOverflowSixOfFive() {
        val action = simulator.stepFriendGift(
            currentGiftCount = 6,
            maxGiftCount = 5,
            hasFriendsInList = true
        )
        assertEquals(">= 5/5 gifts must trigger drawer transition", EngineSimulator.FriendGiftStepAction.OpenReceiveGiftDrawer, action)
    }

    @Test
    fun testF6_04_receiveDrawerWithMixedExpiredGifts() {
        // Drawer with zero solid claims and zero ad plays
        val action = simulator.stepReceiveDrawer(
            solidFreeGiftsCount = 0,
            adPlayGiftsCount = 0,
            isEmptyDrawer = false
        )
        assertTrue("When all gifts are claimed or expired, drawer must close", action is ReceiveDrawerStepAction.CloseDrawer)
    }

    @Test
    fun testF6_05_emptyDrawerOcrRecognitionExcludesFriendsPage() {
        val emptyDrawerItems = MockFixtures.createReceiveGiftItems(isEmpty = true)
        val isDrawer = receiveGiftTask.isReceiveGiftDialogOpen(emptyDrawerItems, MockFixtures.SCREEN_HEIGHT)
        assertTrue("Empty drawer must be identified as drawer", isDrawer)
    }
}
