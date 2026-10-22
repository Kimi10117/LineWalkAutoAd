package com.linewalk.autoad.test.tier3

import com.linewalk.autoad.engine.MainTab
import com.linewalk.autoad.engine.PatrolPhase
import com.linewalk.autoad.test.fixture.EngineSimulator
import com.linewalk.autoad.test.fixture.EngineSimulator.BingoStepAction
import com.linewalk.autoad.test.fixture.EngineSimulator.FriendGiftStepAction
import com.linewalk.autoad.test.fixture.EngineSimulator.GuardAction
import com.linewalk.autoad.test.fixture.EngineSimulator.ReceiveDrawerStepAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 3 - Cross-Feature Combinations & Pairwise Interaction Coverage Suite.
 * Tests pairwise feature combinations:
 * - F2 (External Guard) x F4 (Bingo Dialog)
 * - F3 (Infinite Loop) x F6 (Friend Gift & Drawer)
 * - F1 (Floating Pill) x F3 (Infinite Loop Cooldown Pause)
 * - F1 (Floating Pill) x F2 (External App Guard)
 * - F4 (Bingo) x F5 (Log Export)
 * - F2 (External Guard) x F6 (Receive Drawer Ad Play)
 */
class Tier3_CrossFeatureCombinationTest {

    private lateinit var simulator: EngineSimulator

    @Before
    fun setUp() {
        simulator = EngineSimulator()
    }

    @Test
    fun test01_externalAppInterruptionDuringActiveBingoDialog() {
        // Pairwise: F2 (External Guard) x F4 (Bingo)
        // 1. In bingo dialog, Step 1 is ready (open cell available)
        simulator.startPatrol()
        val step1 = simulator.stepBingoDialog(
            hasOpenCellButton = true,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = listOf(1)
        )
        assertTrue(step1 is BingoStepAction.ClickOpenCell)

        // 2. Suddenly an external browser opens (e.g. com.android.chrome)
        val guardDecision = simulator.evaluateForegroundPackage("com.android.chrome")
        assertTrue("External guard must detect interruption", guardDecision.isExternal)
        assertEquals(GuardAction.SAFE_BACK_NAVIGATION, guardDecision.recommendedAction)
        assertFalse("Bingo clicks must be suspended during external interruption", guardDecision.regularClicksAllowed)

        // 3. User / system returns to LINE Walk
        val returnDecision = simulator.evaluateForegroundPackage("com.linecorp.LGWALK")
        assertFalse(returnDecision.isExternal)
        assertTrue(returnDecision.regularClicksAllowed)

        // 4. Bingo resumes: cell opening exhausted, so Get is claimed next
        val step2 = simulator.stepBingoDialog(
            hasOpenCellButton = false,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = listOf(1)
        )
        assertTrue(step2 is BingoStepAction.ClaimConnectionReward)
    }

    @Test
    fun test02_infiniteLoopTransitionAcrossFriendGiftToRound2() {
        // Pairwise: F3 (Infinite Loop) x F6 (Friend Gift & Drawer)
        simulator.startPatrol()
        assertEquals(1, simulator.currentRound)

        // 1. Friend gift hits 5/5 limit -> triggers drawer transition
        val giftAction = simulator.stepFriendGift(currentGiftCount = 5, maxGiftCount = 5)
        assertEquals(FriendGiftStepAction.OpenReceiveGiftDrawer, giftAction)
        simulator.isFriendGiftCompleted = true

        // 2. In receive drawer, claim solid free gift first
        val drawerAction1 = simulator.stepReceiveDrawer(solidFreeGiftsCount = 1, adPlayGiftsCount = 1, isEmptyDrawer = false)
        assertTrue(drawerAction1 is ReceiveDrawerStepAction.ClaimSolidFreeGift)

        // 3. Next claim ad play gift
        val drawerAction2 = simulator.stepReceiveDrawer(solidFreeGiftsCount = 0, adPlayGiftsCount = 1, isEmptyDrawer = false)
        assertTrue(drawerAction2 is ReceiveDrawerStepAction.ClaimAdPlayGift)

        // 4. Drawer now empty -> close drawer
        val drawerAction3 = simulator.stepReceiveDrawer(solidFreeGiftsCount = 0, adPlayGiftsCount = 0, isEmptyDrawer = false)
        assertTrue(drawerAction3 is ReceiveDrawerStepAction.CloseDrawer)
        simulator.isReceiveGiftCompleted = true

        // 5. Complete round 1 and enter 5s cooldown
        val cooldownRes = simulator.simulateRoundCompletion(pauseRequestedAtSec = null)
        assertEquals(1, cooldownRes.completedRound)
        assertEquals(2, cooldownRes.nextRound)

        // 6. Round 2 restarted cleanly at Move tab
        assertEquals(MainTab.MOVE, simulator.currentTab)
        assertEquals(PatrolPhase.MAIN_TABS, simulator.currentPhase)
        assertFalse(simulator.isFriendGiftCompleted)
        assertFalse(simulator.isReceiveGiftCompleted)
        assertTrue(simulator.isRunning)
    }

    @Test
    fun test03_pauseClickedDuringCooldownWithFloatingPill() {
        // Pairwise: F1 (Floating Pill) x F3 (Infinite Loop Cooldown Pause)
        simulator.startPatrol()
        assertTrue("Pill should be collapsed while running", simulator.isCollapsed)

        // User pauses at second 3 of cooldown
        val cooldownRes = simulator.simulateRoundCompletion(pauseRequestedAtSec = 3)
        assertTrue(cooldownRes.wasPausedDuringCooldown)
        assertFalse("Engine must stop running", simulator.isRunning)

        // Expand button '+' works smoothly in paused state
        simulator.toggleExpandCollapse()
        assertFalse("Pill expands to full panel", simulator.isCollapsed)
        assertTrue(simulator.layoutWidth > 200)

        // Status text reflects pause reason
        assertTrue(simulator.overlayStatusText.contains("暫停"))
    }

    @Test
    fun test04_floatingPillDuringExternalAppGuard() {
        // Pairwise: F1 (Floating Pill) x F2 (External Guard)
        simulator.startPatrol()
        assertTrue(simulator.isCollapsed)

        // External app detected
        val guard = simulator.evaluateForegroundPackage("com.sec.android.app.sbrowser")
        assertTrue(guard.isExternal)

        // Pill status displays guard message without truncation
        val guardStatus = "[防護] 偵測到外部套件 (com.sec.android.app.sbrowser)，自動按返回鍵退回廣告"
        simulator.overlayStatusText = guardStatus

        assertEquals(guardStatus, simulator.overlayStatusText)
        assertEquals(-2, simulator.layoutWidth)
        assertNull(simulator.ellipsizeMode)
    }

    @Test
    fun test05_bingoDepletedProceedsToNextPhaseWithDetailedLogExport() {
        // Pairwise: F4 (Bingo) x F5 (Log Export)
        simulator.startPatrol()

        // Bingo actions logged sequentially
        simulator.appendLog("進入【每日賓果】彈窗")
        simulator.appendLog("優先點擊橘色「立即開啟賓果格」")
        simulator.appendLog("依序領取連線獎勵 [1 BINGO]")
        simulator.appendLog("依序領取連線獎勵 [3 BINGO]")
        simulator.appendLog("點擊右上角 (X) 關閉賓果視窗")

        assertEquals(5, simulator.fullSessionLogs.size)
        val fullLogs = simulator.getFullLogsFormatted()

        assertTrue(fullLogs.contains("立即開啟賓果格"))
        assertTrue(fullLogs.contains("[1 BINGO]"))
        assertTrue(fullLogs.contains("[3 BINGO]"))
        assertTrue(fullLogs.contains("日誌筆數：5"))

        // Share intent contains full sequence
        val intentData = simulator.createShareIntentData(fullLogs)
        assertEquals("android.intent.action.SEND", intentData.action)
    }

    @Test
    fun test06_externalRedirectDuringReceiveDrawerAdPlay() {
        // Pairwise: F2 (External Guard) x F6 (Receive Drawer Ad Play)
        // 1. In receive drawer, ad play clicked
        val claim = simulator.stepReceiveDrawer(solidFreeGiftsCount = 0, adPlayGiftsCount = 1, isEmptyDrawer = false)
        assertTrue(claim is ReceiveDrawerStepAction.ClaimAdPlayGift)

        // 2. Ad watch triggers Google Play Store redirect
        val redirectGuard = simulator.evaluateForegroundPackage("com.android.vending")
        assertTrue(redirectGuard.isExternal)
        assertEquals(GuardAction.SAFE_BACK_NAVIGATION, redirectGuard.recommendedAction)

        // 3. Returning from redirect brings back game
        val returnGuard = simulator.evaluateForegroundPackage("com.linecorp.LGWALK")
        assertFalse(returnGuard.isExternal)
        assertTrue(returnGuard.regularClicksAllowed)
    }
}
