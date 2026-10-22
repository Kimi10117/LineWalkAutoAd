package com.linewalk.autoad.test.tier1

import com.linewalk.autoad.test.fixture.EngineSimulator
import com.linewalk.autoad.test.fixture.EngineSimulator.GuardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 1 - Feature 2: External App Strict Guard & Misclick Prevention (R2).
 * Tests verify non-game app detection, system Back trigger, geometric ad close X click,
 * rejection of regular in-game UI clicks, and package transition back to game.
 */
class Tier1_F2_ExternalAppGuardTest {

    private lateinit var simulator: EngineSimulator

    @Before
    fun setUp() {
        simulator = EngineSimulator()
    }

    @Test
    fun test01_externalAppDetectionForNonGameForegroundPackages() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R2 & PROJECT.md §Feature Inventory F2
        val externalPackages = listOf(
            "com.android.chrome",
            "com.android.vending",
            "com.google.android.apps.nexuslauncher",
            "com.android.settings",
            "com.sec.android.app.sbrowser"
        )

        for (pkg in externalPackages) {
            val decision = simulator.evaluateForegroundPackage(pkg)
            assertTrue("Package '$pkg' must be identified as external", decision.isExternal)
            assertFalse("Regular UI clicks must be forbidden in external app", decision.regularClicksAllowed)
        }
    }

    @Test
    fun test02_systemBackTriggerUponExternalAppDetection() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R2 ("在外部 App 期間僅允許執行安全退出行為：發送系統返回鍵（Back）")
        val decision = simulator.evaluateForegroundPackage(
            fgPkg = "com.android.chrome",
            hasAdGeometricX = false
        )

        assertTrue(decision.isExternal)
        assertEquals(
            "When no ad close button is detected, Back navigation must be triggered",
            GuardAction.SAFE_BACK_NAVIGATION,
            decision.recommendedAction
        )
        assertFalse(decision.regularClicksAllowed)
    }

    @Test
    fun test03_adCloseXClickAllowedInExternalWindow() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R2 ("或點擊廣告右上角的圓形關閉按鈕（X）")
        val decision = simulator.evaluateForegroundPackage(
            fgPkg = "com.android.chrome",
            hasAdGeometricX = true
        )

        assertTrue(decision.isExternal)
        assertEquals(
            "When ad geometric close button is present in external window, click X is permitted",
            GuardAction.CLICK_AD_CLOSE_X,
            decision.recommendedAction
        )
        assertFalse("General UI exploration clicks must still remain forbidden", decision.regularClicksAllowed)
    }

    @Test
    fun test04_rejectionOfRegularInGameUIClicksInExternalApp() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R2 ("嚴格禁止進行任何常規介面按鈕點擊探索")
        val decision = simulator.evaluateForegroundPackage("com.some.thirdparty.app")

        assertFalse("regularClicksAllowed must strictly be false in external applications", decision.regularClicksAllowed)
        assertTrue("isExternal must be true", decision.isExternal)
    }

    @Test
    fun test05_packageTransitionBackToGameResumesPatrolScan() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R2 ("確保返回散步趣 App 後才恢復巡邏")
        val gamePackages = listOf("com.linecorp.LGWKTW", "com.linecorp.LGWALK", "jp.naver.line.android", "com.linewalk.autoad")

        for (gamePkg in gamePackages) {
            val decision = simulator.evaluateForegroundPackage(gamePkg)
            assertFalse("Game package '$gamePkg' must not be flagged as external", decision.isExternal)
            assertTrue("Regular clicks must be allowed in game package", decision.regularClicksAllowed)
            assertEquals("In-game scan must be permitted", GuardAction.ALLOW_IN_GAME_SCAN, decision.recommendedAction)
        }
    }

    @Test
    fun test06_metaAndGoogleAdPackagesAllowedDuringAdWatching() {
        val adPackages = listOf(
            "com.facebook.katana",
            "com.facebook.orca",
            "com.google.android.gms"
        )

        for (adPkg in adPackages) {
            val decision = simulator.evaluateForegroundPackage(adPkg, isWatchingAd = true)
            assertFalse("Ad package '$adPkg' must not be flagged as external during ad watching", decision.isExternal)
            assertTrue("Clicks should be allowed during ad watching in ad container", decision.regularClicksAllowed)
            assertEquals(GuardAction.ALLOW_IN_GAME_SCAN, decision.recommendedAction)
        }
    }

    @Test
    fun test07_metaAdPackagesFlaggedAsExternalWhenNotWatchingAd() {
        val decision = simulator.evaluateForegroundPackage("com.facebook.katana", isWatchingAd = false)
        assertTrue("Facebook outside ad watching must be flagged as external", decision.isExternal)
        assertFalse("Regular clicks must be blocked in external Facebook", decision.regularClicksAllowed)
        assertEquals(GuardAction.SAFE_BACK_NAVIGATION, decision.recommendedAction)
    }

    @Test
    fun test08_redirectPackagesAlwaysExternalEvenDuringAdWatching() {
        val redirectPackages = listOf("com.android.chrome", "com.android.vending")
        for (pkg in redirectPackages) {
            val decision = simulator.evaluateForegroundPackage(pkg, isWatchingAd = true)
            assertTrue("Browser/store redirect '$pkg' must remain external during ad watching", decision.isExternal)
            assertFalse(decision.regularClicksAllowed)
        }
    }
}
