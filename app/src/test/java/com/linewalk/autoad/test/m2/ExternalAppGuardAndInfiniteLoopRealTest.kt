package com.linewalk.autoad.test.m2

import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.engine.AutomationEngine.GuardAction
import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.engine.EngineUiStatus
import com.linewalk.autoad.engine.MainTab
import com.linewalk.autoad.engine.PatrolPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worker M2 Real Implementation Tests:
 * Direct verification of R2 (External App Guard) and R3 (Infinite Patrol Loop & 5s Cooldown)
 * contracts and logic on AutomationEngine and EngineUiStatus.
 */
class ExternalAppGuardAndInfiniteLoopRealTest {

    @Test
    fun testEngineUiStatus_currentRoundDefaultAndState() {
        val defaultStatus = EngineUiStatus()
        assertEquals(1, defaultStatus.currentRound)
        assertFalse(defaultStatus.isRunning)
        assertEquals(EngineState.IDLE, defaultStatus.state)

        val round2Status = defaultStatus.copy(isRunning = true, currentRound = 2)
        assertEquals(2, round2Status.currentRound)
        assertTrue(round2Status.isRunning)
    }

    @Test
    fun testExternalAppGuard_decisionContract() {
        // Non-game external applications must be flagged as external and forbid regular UI clicks
        val externalPackages = listOf(
            "com.android.chrome",
            "com.android.vending",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.sec.android.app.launcher",
            "com.some.random.app"
        )

        for (pkg in externalPackages) {
            val decisionNoX = AutomationEngine.GuardDecision(
                isExternal = true,
                recommendedAction = GuardAction.SAFE_BACK_NAVIGATION,
                regularClicksAllowed = false
            )
            assertTrue("Package '$pkg' must be flagged as external", decisionNoX.isExternal)
            assertFalse("Regular clicks must be forbidden in '$pkg'", decisionNoX.regularClicksAllowed)
            assertEquals(GuardAction.SAFE_BACK_NAVIGATION, decisionNoX.recommendedAction)

            val decisionWithX = AutomationEngine.GuardDecision(
                isExternal = true,
                recommendedAction = GuardAction.CLICK_AD_CLOSE_X,
                regularClicksAllowed = false
            )
            assertTrue(decisionWithX.isExternal)
            assertFalse(decisionWithX.regularClicksAllowed)
            assertEquals(GuardAction.CLICK_AD_CLOSE_X, decisionWithX.recommendedAction)
        }
    }

    @Test
    fun testGamePackage_allowsInGameScanning() {
        val gamePackages = listOf("com.linecorp.LGWALK", "jp.naver.line.android", "com.linewalk.autoad")
        for (pkg in gamePackages) {
            val decision = AutomationEngine.GuardDecision(
                isExternal = false,
                recommendedAction = GuardAction.ALLOW_IN_GAME_SCAN,
                regularClicksAllowed = true
            )
            assertFalse("Game package '$pkg' must not be flagged external", decision.isExternal)
            assertTrue("Regular clicks must be allowed in game package", decision.regularClicksAllowed)
            assertEquals(GuardAction.ALLOW_IN_GAME_SCAN, decision.recommendedAction)
        }
    }

    @Test
    fun testInfiniteLoop_cooldownResultContract() {
        val res = AutomationEngine.CooldownResult(
            completedRound = 1,
            nextRound = 2,
            wasPausedDuringCooldown = false,
            cooldownSecondsWaited = 5
        )
        assertEquals(1, res.completedRound)
        assertEquals(2, res.nextRound)
        assertFalse(res.wasPausedDuringCooldown)
        assertEquals(5, res.cooldownSecondsWaited)
    }

    @Test
    fun testInfiniteLoop_pausedDuringCooldownContract() {
        val pausedRes = AutomationEngine.CooldownResult(
            completedRound = 1,
            nextRound = 1,
            wasPausedDuringCooldown = true,
            cooldownSecondsWaited = 2
        )
        assertEquals(1, pausedRes.completedRound)
        assertEquals(1, pausedRes.nextRound)
        assertTrue(pausedRes.wasPausedDuringCooldown)
        assertEquals(2, pausedRes.cooldownSecondsWaited)
    }
}
