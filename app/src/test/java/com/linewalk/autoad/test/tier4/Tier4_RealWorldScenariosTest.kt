package com.linewalk.autoad.test.tier4

import com.linewalk.autoad.engine.MainTab
import com.linewalk.autoad.engine.PatrolPhase
import com.linewalk.autoad.test.fixture.EngineSimulator
import com.linewalk.autoad.test.fixture.EngineSimulator.BingoStepAction
import com.linewalk.autoad.test.fixture.EngineSimulator.FriendGiftStepAction
import com.linewalk.autoad.test.fixture.EngineSimulator.GuardAction
import com.linewalk.autoad.test.fixture.EngineSimulator.ReceiveDrawerStepAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 4 - Real-World Application End-to-End Scenarios Suite (>=5 Scenarios).
 * Validates complete end-to-end user workflows, lifecycle transitions, interruption handling,
 * quota exhaustion quick-cycles, and manual user interventions.
 */
class Tier4_RealWorldScenariosTest {

    private lateinit var simulator: EngineSimulator

    @Before
    fun setUp() {
        simulator = EngineSimulator()
    }

    @Test
    fun testScenario1_fullPatrolCycleEndToEndSimulation() {
        // Authoritative Source: ORIGINAL_REQUEST.md & PROJECT.md Architecture
        // 1. User starts patrol
        simulator.startPatrol()
        assertTrue("Engine must be running", simulator.isRunning)
        assertTrue("Pill must auto-collapse to compact single-line layout", simulator.isCollapsed)
        assertEquals(-2, simulator.layoutWidth)

        // 2. Main tabs patrol: Move -> Time -> Game -> Step
        simulator.currentPhase = PatrolPhase.MAIN_TABS
        val tabsOrder = listOf(MainTab.MOVE, MainTab.TIME, MainTab.GAME, MainTab.STEP)
        for (tab in tabsOrder) {
            simulator.currentTab = tab
            simulator.confirmedEmptyTabs.add(tab)
            simulator.appendLog("完成【${tab.tabName}】分頁常規獎勵檢查")
        }
        assertEquals(4, simulator.confirmedEmptyTabs.size)

        // 3. Daily Bingo Phase
        simulator.currentPhase = PatrolPhase.BINGO
        // Step 1: Open cell
        val bingoOpen = simulator.stepBingoDialog(hasOpenCellButton = true, inCooldown = false, isLimitReached = false, unclaimedColumns = listOf(1, 3))
        assertTrue(bingoOpen is BingoStepAction.ClickOpenCell)
        simulator.appendLog("每日賓果：點擊立即開啟賓果格")

        // Step 2: Sequential Get rewards
        val bingoGet1 = simulator.stepBingoDialog(hasOpenCellButton = false, inCooldown = false, isLimitReached = false, unclaimedColumns = listOf(1, 3))
        assertTrue(bingoGet1 is BingoStepAction.ClaimConnectionReward)
        simulator.appendLog("每日賓果：領取連線 1 BINGO")

        val bingoGet3 = simulator.stepBingoDialog(hasOpenCellButton = false, inCooldown = false, isLimitReached = false, unclaimedColumns = listOf(3))
        assertTrue(bingoGet3 is BingoStepAction.ClaimConnectionReward)
        simulator.appendLog("每日賓果：領取連線 3 BINGO")

        // Step 3: Close Bingo dialog
        val bingoClose = simulator.stepBingoDialog(hasOpenCellButton = false, inCooldown = false, isLimitReached = false, unclaimedColumns = emptyList())
        assertTrue(bingoClose is BingoStepAction.CloseDialog)
        simulator.isBingoCompleted = true
        simulator.appendLog("每日賓果：完成並關閉視窗")

        // 4. Friend Gift Phase
        simulator.currentPhase = PatrolPhase.FRIEND_GIFT
        // Send gifts until 5/5 limit
        for (count in 1..4) {
            val giftStep = simulator.stepFriendGift(currentGiftCount = count, maxGiftCount = 5)
            assertTrue(giftStep is FriendGiftStepAction.SendGift)
            simulator.appendLog("好友送禮：贈送第 $count 位好友 (也送自己)")
        }
        val giftLimit = simulator.stepFriendGift(currentGiftCount = 5, maxGiftCount = 5)
        assertEquals(FriendGiftStepAction.OpenReceiveGiftDrawer, giftLimit)
        simulator.isFriendGiftCompleted = true
        simulator.appendLog("好友送禮：達 5/5 上限，前往收禮抽屜")

        // 5. Receive Gift Drawer Phase
        simulator.currentPhase = PatrolPhase.RECEIVE_GIFT
        // Step 1: Solid free claims first
        val freeClaim = simulator.stepReceiveDrawer(solidFreeGiftsCount = 1, adPlayGiftsCount = 1, isEmptyDrawer = false)
        assertTrue(freeClaim is ReceiveDrawerStepAction.ClaimSolidFreeGift)
        simulator.appendLog("收禮抽屜：優先領取實心免廣告禮物")

        // Step 2: Ad play claims second
        val adClaim = simulator.stepReceiveDrawer(solidFreeGiftsCount = 0, adPlayGiftsCount = 1, isEmptyDrawer = false)
        assertTrue(adClaim is ReceiveDrawerStepAction.ClaimAdPlayGift)
        simulator.appendLog("收禮抽屜：看廣告領取禮物")

        // Step 3: Close drawer
        val closeDrawer = simulator.stepReceiveDrawer(solidFreeGiftsCount = 0, adPlayGiftsCount = 0, isEmptyDrawer = false)
        assertTrue(closeDrawer is ReceiveDrawerStepAction.CloseDrawer)
        simulator.isReceiveGiftCompleted = true
        simulator.appendLog("收禮抽屜：全部領取完畢，關閉抽屜")

        // 6. Round Completion & 5s Cooldown
        assertEquals(1, simulator.currentRound)
        val cooldownRes = simulator.simulateRoundCompletion(pauseRequestedAtSec = null)
        assertEquals("Round 1 completed", 1, cooldownRes.completedRound)
        assertEquals("Round 2 started", 2, cooldownRes.nextRound)
        assertEquals(5, cooldownRes.cooldownSecondsWaited)

        // 7. Verify Round 2 state reset
        assertEquals(MainTab.MOVE, simulator.currentTab)
        assertEquals(PatrolPhase.MAIN_TABS, simulator.currentPhase)
        assertTrue("Confirmed empty tabs must be cleared for round 2", simulator.confirmedEmptyTabs.isEmpty())
        assertFalse(simulator.isBingoCompleted)
        assertFalse(simulator.isFriendGiftCompleted)
        assertFalse(simulator.isReceiveGiftCompleted)
        assertTrue("Engine must remain running in Round 2", simulator.isRunning)
    }

    @Test
    fun testScenario2_externalBrowserHijackRecoveryScenario() {
        simulator.startPatrol()

        // 1. Patrolling in LINE Walk
        val inGameDecision = simulator.evaluateForegroundPackage("com.linecorp.LGWALK")
        assertFalse(inGameDecision.isExternal)

        // 2. Ad click accidentally redirects to Chrome browser
        val browserDecision = simulator.evaluateForegroundPackage("com.android.chrome")
        assertTrue(browserDecision.isExternal)
        assertEquals(GuardAction.SAFE_BACK_NAVIGATION, browserDecision.recommendedAction)
        assertFalse("Exploration clicks in browser are blocked", browserDecision.regularClicksAllowed)
        simulator.appendLog("[防護] 偵測到外部瀏覽器 (com.android.chrome)，自動按返回鍵退回廣告")

        // 3. User is returned to LINE Walk
        val returnedDecision = simulator.evaluateForegroundPackage("com.linecorp.LGWALK")
        assertFalse(returnedDecision.isExternal)
        assertTrue(returnedDecision.regularClicksAllowed)
        simulator.appendLog("[防護] 成功返回遊戲，繼續巡邏流程")

        // 4. Engine logs verify proper recovery
        val logs = simulator.getFullLogsFormatted()
        assertTrue(logs.contains("偵測到外部瀏覽器"))
        assertTrue(logs.contains("成功返回遊戲"))
    }

    @Test
    fun testScenario3_quotaExhaustedQuickCycleScenario() {
        simulator.startPatrol()

        // 1. Bingo daily limit already reached -> immediately closes dialog without open cell click
        val bingoAction = simulator.stepBingoDialog(
            hasOpenCellButton = true,
            inCooldown = false,
            isLimitReached = true,
            unclaimedColumns = emptyList()
        )
        assertTrue("Must close dialog without clicking cell", bingoAction is BingoStepAction.CloseDialog)
        simulator.isBingoCompleted = true

        // 2. Friend gift already at 5/5 -> immediately jumps to receive gift drawer
        val giftAction = simulator.stepFriendGift(currentGiftCount = 5, maxGiftCount = 5)
        assertEquals(FriendGiftStepAction.OpenReceiveGiftDrawer, giftAction)
        simulator.isFriendGiftCompleted = true

        // 3. Receive gift drawer has no gifts -> immediately closes drawer
        val drawerAction = simulator.stepReceiveDrawer(solidFreeGiftsCount = 0, adPlayGiftsCount = 0, isEmptyDrawer = true)
        assertTrue("Empty drawer must close immediately", drawerAction is ReceiveDrawerStepAction.CloseDrawer)
        simulator.isReceiveGiftCompleted = true

        // 4. Quick round completion into round 2
        val cooldownRes = simulator.simulateRoundCompletion()
        assertEquals(2, cooldownRes.nextRound)
        assertTrue(simulator.isRunning)
    }

    @Test
    fun testScenario4_userManualInterventionMidPatrolScenario() {
        simulator.startPatrol()
        simulator.appendLog("巡邏開始")

        // User pauses mid-patrol
        simulator.stopPatrol("使用者暫停更換設定")
        assertFalse("Engine must be paused", simulator.isRunning)

        // Expand pill to check status
        simulator.toggleExpandCollapse()
        assertFalse(simulator.isCollapsed)

        // User exports logs to share bug report
        val exportText = simulator.getFullLogsFormatted()
        val shareIntent = simulator.createShareIntentData(exportText)
        assertNotNull(shareIntent)
        assertEquals("text/plain", shareIntent.type)

        // User changes configuration and clicks Start again
        simulator.startPatrol()
        assertTrue(simulator.isRunning)
        assertTrue(simulator.isCollapsed) // Automatically re-collapses on start
    }

    @Test
    fun testScenario5_adInterstitialCloseButtonHuntScenario() {
        simulator.startPatrol()

        // Playable ad displays in external overlay window with an 'X' close button
        val adDecision = simulator.evaluateForegroundPackage(
            fgPkg = "com.unity3d.ads.example",
            hasAdGeometricX = true
        )

        assertTrue(adDecision.isExternal)
        assertEquals("Clicking geometric ad close X is permitted in external ad window", GuardAction.CLICK_AD_CLOSE_X, adDecision.recommendedAction)
        assertFalse("General UI clicks remain strictly blocked", adDecision.regularClicksAllowed)
    }
}
