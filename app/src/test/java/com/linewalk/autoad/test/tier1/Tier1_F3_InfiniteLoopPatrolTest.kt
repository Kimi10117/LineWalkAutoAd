package com.linewalk.autoad.test.tier1

import com.linewalk.autoad.engine.MainTab
import com.linewalk.autoad.engine.PatrolPhase
import com.linewalk.autoad.test.fixture.EngineSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 1 - Feature 3: Infinite Patrol Loop & 5s Cooldown Mechanism (R3).
 * Tests verify non-stopping round completion, 5s delay execution, round counter increment,
 * task state flag reset, and pause responsiveness during cooldown.
 */
class Tier1_F3_InfiniteLoopPatrolTest {

    private lateinit var simulator: EngineSimulator

    @Before
    fun setUp() {
        simulator = EngineSimulator()
        simulator.startPatrol()
    }

    @Test
    fun test01_fullRoundCompletionTransitionsToCooldownInsteadOfStop() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R3 ("全部領取完畢後，系統不自動結束退出")
        assertEquals(1, simulator.currentRound)
        assertTrue(simulator.isRunning)

        // When all tasks complete in round 1
        simulator.currentPhase = PatrolPhase.RECEIVE_GIFT
        val result = simulator.simulateRoundCompletion(pauseRequestedAtSec = null)

        // Then engine should NOT stop; it should transition into round 2
        assertTrue("Engine must continue running after completing a full round", simulator.isRunning)
        assertEquals("Completed round should be 1", 1, result.completedRound)
        assertEquals("Next round should be 2", 2, result.nextRound)
        assertFalse("Should not be paused", result.wasPausedDuringCooldown)
    }

    @Test
    fun test02_fiveSecondCooldownDelayTimingVerification() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R3 ("完整輪次結束後，等待約 5 秒防風控冷卻時間")
        val result = simulator.simulateRoundCompletion(pauseRequestedAtSec = null, totalCooldownSec = 5)

        assertEquals("Full 5 seconds cooldown must be waited", 5, result.cooldownSecondsWaited)
        assertEquals(2, simulator.currentRound)
    }

    @Test
    fun test03_stateFlagResetOnNextRoundRestart() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R3 ("自動重置各階段完成標籤並重新從頭開始下一輪巡邏檢查") & PROJECT.md §Interface Contracts
        // Simulate marked state flags during round 1
        simulator.confirmedEmptyTabs.addAll(listOf(MainTab.MOVE, MainTab.STEP, MainTab.TIME, MainTab.GAME))
        simulator.isBingoCompleted = true
        simulator.isLotteryCompleted = true
        simulator.isMissionCompleted = true
        simulator.isFriendGiftCompleted = true
        simulator.isReceiveGiftCompleted = true
        simulator.currentTab = MainTab.GAME
        simulator.currentPhase = PatrolPhase.RECEIVE_GIFT

        // Execute round completion
        simulator.simulateRoundCompletion()

        // Verify all state flags are cleanly reset for round 2
        assertTrue("Confirmed empty tabs set must be cleared", simulator.confirmedEmptyTabs.isEmpty())
        assertFalse("isBingoCompleted must be reset", simulator.isBingoCompleted)
        assertFalse("isLotteryCompleted must be reset", simulator.isLotteryCompleted)
        assertFalse("isMissionCompleted must be reset", simulator.isMissionCompleted)
        assertFalse("isFriendGiftCompleted must be reset", simulator.isFriendGiftCompleted)
        assertFalse("isReceiveGiftCompleted must be reset", simulator.isReceiveGiftCompleted)
        assertEquals("currentTab must reset to MainTab.MOVE", MainTab.MOVE, simulator.currentTab)
        assertEquals("currentPhase must reset to PatrolPhase.MAIN_TABS", PatrolPhase.MAIN_TABS, simulator.currentPhase)
    }

    @Test
    fun test04_roundCounterIncrementsConsecutively() {
        // Authoritative Source: PROJECT.md §Interface Contracts ("增加 currentRound")
        assertEquals(1, simulator.currentRound)

        simulator.simulateRoundCompletion()
        assertEquals(2, simulator.currentRound)

        simulator.simulateRoundCompletion()
        assertEquals(3, simulator.currentRound)

        simulator.simulateRoundCompletion()
        assertEquals(4, simulator.currentRound)
    }

    @Test
    fun test05_pauseResponsivenessDuringFiveSecondCooldown() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R3 ("整個循環流程持續進行，直到使用者主動點擊懸浮控制窗或 App 主介面的「暫停」為止")
        // User pauses at second 2 of the 5s cooldown
        val result = simulator.simulateRoundCompletion(pauseRequestedAtSec = 2, totalCooldownSec = 5)

        assertTrue("Should record pause during cooldown", result.wasPausedDuringCooldown)
        assertEquals("Should have waited only up to pause request second", 2, result.cooldownSecondsWaited)
        assertFalse("Engine should now be stopped", simulator.isRunning)
        assertEquals("Round counter should remain at 1 since round 2 was not started", 1, simulator.currentRound)
    }
}
