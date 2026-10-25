package com.linewalk.autoad.test.tier1

import com.linewalk.autoad.tasks.BingoTask
import com.linewalk.autoad.test.fixture.EngineSimulator
import com.linewalk.autoad.test.fixture.EngineSimulator.BingoStepAction
import com.linewalk.autoad.test.fixture.MockFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 1 - Feature 4: Daily Bingo Priority Logic & Sequence (R4).
 * Tests verify orange cell button priority over Get, sequential Get claiming,
 * dialog close X when depleted, cooldown timer rejection, and daily limit rejection.
 */
class Tier1_F4_DailyBingoPriorityTest {

    private lateinit var simulator: EngineSimulator
    private lateinit var bingoTask: BingoTask

    @Before
    fun setUp() {
        simulator = EngineSimulator()
        bingoTask = BingoTask()
    }

    @Test
    fun test01_openBingoCellButtonHasHighestPriorityOverGetButtons() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R4 ("必須優先點擊橘色「立即開啟賓果格」按鈕，直到目前可開啟的賓果格全部開啟完畢")
        // Given a bingo screen with BOTH open cell available AND connection Get available (e.g. 1 BINGO ready)
        val action = simulator.stepBingoDialog(
            hasOpenCellButton = true,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = listOf(1, 3)
        )

        // Then Priority 1 must trigger: ClickOpenCell, NOT ClaimConnectionReward
        assertTrue("Priority 1 must be open cell", action is BingoStepAction.ClickOpenCell)
        val openCell = action as BingoStepAction.ClickOpenCell
        assertEquals("立即開啟賓果格", openCell.label)
    }

    @Test
    fun test02_sequentialGetClaimingAfterOpenCellIsDepleted() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R4 ("賓果格開啟完畢後，再依序掃描並點擊下方連線達成之「Get」旗標按鈕")
        // Given open cell button is depleted (or in cooldown), with 1 BINGO and 5 BINGO ready
        val action1 = simulator.stepBingoDialog(
            hasOpenCellButton = false,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = listOf(1, 5)
        )

        assertTrue("First action must claim column 1 Get", action1 is BingoStepAction.ClaimConnectionReward)
        val claim1 = action1 as BingoStepAction.ClaimConnectionReward
        assertEquals(1, claim1.bingoCol)

        // After claiming column 1, next step claims column 5
        val action2 = simulator.stepBingoDialog(
            hasOpenCellButton = false,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = listOf(5)
        )

        assertTrue("Second action must claim column 5 Get", action2 is BingoStepAction.ClaimConnectionReward)
        val claim2 = action2 as BingoStepAction.ClaimConnectionReward
        assertEquals(5, claim2.bingoCol)
    }

    @Test
    fun test03_dialogCloseXClickedWhenBothCellAndGetDepleted() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R4 ("領取完畢後，點擊右上角 (X) 關閉每日賓果視窗並推進至下一巡邏階段")
        // Given neither open cell nor Get buttons remain
        val action = simulator.stepBingoDialog(
            hasOpenCellButton = false,
            inCooldown = false,
            isLimitReached = false,
            unclaimedColumns = emptyList()
        )

        assertTrue("Must close dialog when all rewards are claimed", action is BingoStepAction.CloseDialog)
        val close = action as BingoStepAction.CloseDialog
        val expectedClose = bingoTask.getCloseButtonBounds(MockFixtures.SCREEN_WIDTH, MockFixtures.SCREEN_HEIGHT)
        assertEquals("Close button X bounds must match specification", expectedClose, close.bounds)
    }

    @Test
    fun test04_cooldownTimerOnOpenCellButtonIsRejected() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R4 ("或按鈕進入冷卻倒數") & PROJECT.md §Interface Contracts
        // Given open cell button is showing cooldown countdown (e.g. 02:45), with Get column 3 available
        val action = simulator.stepBingoDialog(
            hasOpenCellButton = true,
            inCooldown = true,
            isLimitReached = false,
            unclaimedColumns = listOf(3)
        )

        // Open cell must be skipped because of cooldown; system falls back to claiming Get
        assertFalse("Must NOT click open cell when in cooldown", action is BingoStepAction.ClickOpenCell)
        assertTrue("Must proceed to claim Get", action is BingoStepAction.ClaimConnectionReward)
        assertEquals(3, (action as BingoStepAction.ClaimConnectionReward).bingoCol)
    }

    @Test
    fun test05_dailyLimitOnOpenCellButtonIsRejected() {
        // Authoritative Source: PROJECT.md §Interface Contracts ("findOpenCellButton: 排除「今日上限」、「已達到今日上限」")
        // Given open cell button text says "已達到今日上限", with no Get available
        val action = simulator.stepBingoDialog(
            hasOpenCellButton = true,
            inCooldown = false,
            isLimitReached = true,
            unclaimedColumns = emptyList()
        )

        // Open cell must be rejected; with no Get, dialog must close
        assertFalse("Must NOT click open cell when daily limit reached", action is BingoStepAction.ClickOpenCell)
        assertTrue("Must close dialog", action is BingoStepAction.CloseDialog)
    }

    @Test
    fun test06_isBingoDialogOpenRecognizesChineseCooldownAndOccludedText() {
        val sh = MockFixtures.SCREEN_HEIGHT
        val sw = MockFixtures.SCREEN_WIDTH
        val occludedItems = listOf(
            MockFixtures.createNormalizedOcrItem("每日賓果", 0.35f, 0.08f, 0.65f, 0.14f, sw, sh),
            MockFixtures.createNormalizedOcrItem("鐘 01 秒", 0.65f, 0.16f, 0.85f, 0.20f, sw, sh),
            MockFixtures.createNormalizedOcrItem("3 / 9 OPEN", 0.30f, 0.56f, 0.50f, 0.60f, sw, sh),
            MockFixtures.createNormalizedOcrItem("立即開啟賓果格", 0.20f, 0.61f, 0.80f, 0.67f, sw, sh)
        )
        assertTrue("Daily bingo dialog must be recognized even with partially occluded cooldown text",
            bingoTask.isBingoDialogOpen(occludedItems, sh))
    }

    @Test
    fun test07_findOpenCellButtonPermitsOrangeButtonWithBannerTimerAndRejectsButtonCooldown() {
        val sh = MockFixtures.SCREEN_HEIGHT
        val sw = MockFixtures.SCREEN_WIDTH
        val mockBitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true)
        io.mockk.every { mockBitmap.height } returns sh
        io.mockk.every { mockBitmap.width } returns sw
        val templateMatcher = com.linewalk.autoad.vision.TemplateMatcher()

        // Case A: Full Chinese countdown in top banner "距離下次抽選還有 27 分鐘 01 秒", but active orange button is present
        val bannerCooldownItems = listOf(
            MockFixtures.createNormalizedOcrItem("每日賓果", 0.35f, 0.08f, 0.65f, 0.14f, sw, sh),
            MockFixtures.createNormalizedOcrItem("距離下次抽選還有 27 分鐘 01 秒", 0.20f, 0.16f, 0.80f, 0.20f, sw, sh),
            MockFixtures.createNormalizedOcrItem("立即開啟賓果格", 0.20f, 0.61f, 0.80f, 0.67f, sw, sh)
        )
        val resultA = bingoTask.findOpenCellButton(mockBitmap, bannerCooldownItems, templateMatcher)
        org.junit.Assert.assertNotNull("findOpenCellButton must return valid bounds when button is present despite top banner timer", resultA)

        // Case B: Button area itself has cooldown text ("還有 27 分鐘" at y = 0.61f..0.67f)
        val buttonCooldownItems = listOf(
            MockFixtures.createNormalizedOcrItem("每日賓果", 0.35f, 0.08f, 0.65f, 0.14f, sw, sh),
            MockFixtures.createNormalizedOcrItem("距離下次抽選還有 27 分鐘 01 秒", 0.20f, 0.16f, 0.80f, 0.20f, sw, sh),
            MockFixtures.createNormalizedOcrItem("還有 27 分鐘", 0.20f, 0.61f, 0.80f, 0.67f, sw, sh)
        )
        val resultB = bingoTask.findOpenCellButton(mockBitmap, buttonCooldownItems, templateMatcher)
        org.junit.Assert.assertNull("findOpenCellButton must return null when button region has countdown timer", resultB)
    }
}
