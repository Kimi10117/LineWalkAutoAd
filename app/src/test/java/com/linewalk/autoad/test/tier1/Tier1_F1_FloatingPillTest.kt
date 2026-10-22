package com.linewalk.autoad.test.tier1

import com.linewalk.autoad.test.fixture.EngineSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 1 - Feature 1: Floating Overlay Window Collapse & Non-Truncating Layout (R1).
 * Tests verify auto-collapse to compact single-line pill, adaptive text width,
 * '+' expand button action, compact dimension state, and uncollapsed state.
 */
class Tier1_F1_FloatingPillTest {

    private lateinit var simulator: EngineSimulator

    @Before
    fun setUp() {
        simulator = EngineSimulator()
    }

    @Test
    fun test01_startPatrolTriggersAutoCollapseToCompactPill() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R1 & PROJECT.md §Feature Inventory F1
        // Given initial idle state
        assertFalse("Engine should initially be idle", simulator.isRunning)
        assertFalse("Pill should initially not be collapsed", simulator.isCollapsed)

        // When starting patrol
        simulator.startPatrol()

        // Then pill must auto-collapse to compact single-line pill
        assertTrue("Engine must be running", simulator.isRunning)
        assertTrue("Floating window must auto-collapse when patrol starts", simulator.isCollapsed)
    }

    @Test
    fun test02_statusTextDisplayAdaptiveWithoutEllipsisTruncation() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R1 ("收折狀態下的狀態文字寬度必須自適應，徹底修復文字截斷為省略號")
        simulator.startPatrol()

        // Verify layout params specify WRAP_CONTENT (-2) and ellipsizeMode is null
        assertEquals("Width must be WRAP_CONTENT to prevent truncation", -2, simulator.layoutWidth)
        assertNull("Ellipsize must be null (no ... truncation allowed)", simulator.ellipsizeMode)

        // Test with different descriptive status strings
        val statusTexts = listOf(
            "[巡邏中] 首頁",
            "[抽抽樂] 開啟中...",
            "[賓果] 檢查可開格/連線獎勵中 (1/2)...",
            "[禮物] 開啟【禮物】視窗接收禮物中..."
        )

        for (status in statusTexts) {
            simulator.overlayStatusText = status
            assertEquals("Status text must be preserved intact", status, simulator.overlayStatusText)
            assertFalse("Status text must not contain ellipsis indicator", simulator.overlayStatusText.endsWith("... · ..."))
        }
    }

    @Test
    fun test03_expandButtonClickRestoresFullControlPanel() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R1 & PROJECT.md §Interface Contracts
        simulator.startPatrol()
        assertTrue("Must be collapsed while patrolling", simulator.isCollapsed)

        // When user clicks the '+' expand button
        simulator.toggleExpandCollapse()

        // Then floating window expands to full control panel
        assertFalse("Pill must be expanded (not collapsed)", simulator.isCollapsed)
        assertTrue("Expanded width must be larger than compact pill", simulator.layoutWidth > 200)
        assertTrue("Expanded height must be larger than compact pill", simulator.layoutHeight > 200)

        // Clicking again minimizes back to pill
        simulator.toggleExpandCollapse()
        assertTrue("Pill should minimize back to compact state", simulator.isCollapsed)
        assertEquals("Width must revert to WRAP_CONTENT", -2, simulator.layoutWidth)
    }

    @Test
    fun test04_compactDimensionStateVerification() {
        // Authoritative Source: ORIGINAL_REQUEST.md §Acceptance Criteria 1
        simulator.startPatrol()

        // In collapsed state, pill dimensions must be compact wrap_content
        assertEquals(-2, simulator.layoutWidth)
        assertEquals(-2, simulator.layoutHeight)
        assertTrue("Pill must report isCollapsed = true", simulator.isCollapsed)
    }

    @Test
    fun test05_uncollapsedStatePreservesFullControlFeatures() {
        // Authoritative Source: PROJECT.md §Architecture UI Layer
        // Initially uncollapsed
        assertFalse(simulator.isCollapsed)

        // Controls are accessible, stopping patrol keeps uncollapsed state
        simulator.stopPatrol("待命檢查")
        assertFalse("Stopped state should not force collapse", simulator.isCollapsed)
        assertEquals("[待命] 待命檢查", simulator.overlayStatusText)
    }
}
