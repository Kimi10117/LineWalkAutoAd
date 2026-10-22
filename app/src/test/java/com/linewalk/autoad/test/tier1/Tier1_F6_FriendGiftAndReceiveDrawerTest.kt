package com.linewalk.autoad.test.tier1

import com.linewalk.autoad.tasks.ReceiveGiftTask
import com.linewalk.autoad.test.fixture.EngineSimulator
import com.linewalk.autoad.test.fixture.EngineSimulator.FriendGiftStepAction
import com.linewalk.autoad.test.fixture.EngineSimulator.ReceiveDrawerStepAction
import com.linewalk.autoad.test.fixture.MockFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 1 - Feature 6: Friend Gift 5/5 Limit & Receive Gift Drawer Claim Order (R6).
 * Tests verify 5/5 daily limit detection, transition to gift drawer via header gift box,
 * empty drawer detection preventing infinite loop, solid green claim priority,
 * and outline play button secondary claim.
 */
class Tier1_F6_FriendGiftAndReceiveDrawerTest {

    private lateinit var simulator: EngineSimulator
    private lateinit var receiveGiftTask: ReceiveGiftTask

    @Before
    fun setUp() {
        simulator = EngineSimulator()
        receiveGiftTask = ReceiveGiftTask()
    }

    @Test
    fun test01_friendGiftLimit5of5TriggersCompletionAndDrawerTransition() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R6 ("偵測送禮次數上限（如 5/5）或清單滾動無可送好友時，判定好友送禮結束")
        // Given friend gift count is 5/5
        val action = simulator.stepFriendGift(
            currentGiftCount = 5,
            maxGiftCount = 5,
            hasFriendsInList = true
        )

        // Then it must immediately transition to opening the receive gift drawer
        assertEquals(
            "Reaching 5/5 gifts must trigger transition to receive gift drawer",
            FriendGiftStepAction.OpenReceiveGiftDrawer,
            action
        )
    }

    @Test
    fun test02_transitionToGiftDrawerClicksHeaderGiftBoxIcon() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R6 ("直接在當前頁面點擊右上角【禮物盒】圖示按鈕開啟「禮物」收禮抽屜")
        val sw = MockFixtures.SCREEN_WIDTH
        val sh = MockFixtures.SCREEN_HEIGHT
        val giftBoxRect = receiveGiftTask.getHeaderGiftBoxRect(sw, sh)

        // Header gift box is located at top-right (x ≈ 83.5%..88.5%, y ≈ 4.5%..8.0%)
        assertTrue("Gift box left must be in upper right", giftBoxRect.left > sw * 0.80f)
        assertTrue("Gift box top must be near top header", giftBoxRect.top < sh * 0.10f)
        assertTrue("Gift box bounds must have positive area", giftBoxRect.width() > 0 && giftBoxRect.height() > 0)
    }

    @Test
    fun test03_emptyDrawerDetectionClosesDrawerWithoutInfiniteLoop() {
        // Authoritative Source: PROJECT.md §Interface Contracts ("ReceiveGiftTask.isReceiveGiftDialogOpen(): 支援包含「尚無禮物」或「目前沒有禮物」之空抽屜狀態，防止死循環")
        // Given an empty drawer screen
        val emptyItems = MockFixtures.createReceiveGiftItems(isEmpty = true)
        val isOpen = receiveGiftTask.isReceiveGiftDialogOpen(emptyItems, MockFixtures.SCREEN_HEIGHT)

        // Empty drawer is recognized as drawer open
        assertTrue("Empty drawer should be recognized as open drawer", isOpen)

        // When stepped with empty drawer, simulator immediately issues CloseDrawer
        val action = simulator.stepReceiveDrawer(
            solidFreeGiftsCount = 0,
            adPlayGiftsCount = 0,
            isEmptyDrawer = true
        )

        assertTrue("Empty drawer must immediately close to prevent infinite loop", action is ReceiveDrawerStepAction.CloseDrawer)
    }

    @Test
    fun test04_solidGreenClaimButtonHasHighestPriority() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R6 ("依照「最高優先：實心綠色按鈕 [ 領取 ]（免看廣告）」、「次要優先：綠框播放按鈕 [ ▶ ]（需看廣告）」之順序領取")
        // Given drawer contains BOTH 2 solid free gifts AND 1 ad play gift
        val action = simulator.stepReceiveDrawer(
            solidFreeGiftsCount = 2,
            adPlayGiftsCount = 1,
            isEmptyDrawer = false
        )

        // Then Highest Priority must trigger: ClaimSolidFreeGift
        assertTrue("Highest priority must be solid free claim", action is ReceiveDrawerStepAction.ClaimSolidFreeGift)
        val solidAction = action as ReceiveDrawerStepAction.ClaimSolidFreeGift
        assertEquals("實心領取(免廣告)", solidAction.label)
    }

    @Test
    fun test05_outlinePlayClaimButtonHasSecondaryPriority() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R6 ("次要優先：綠框播放按鈕 [ ▶ ]（需看廣告）")
        // Given solid free gifts have all been claimed (solidCount = 0), but 1 ad play gift remains
        val action = simulator.stepReceiveDrawer(
            solidFreeGiftsCount = 0,
            adPlayGiftsCount = 1,
            isEmptyDrawer = false
        )

        // Then Secondary Priority must trigger: ClaimAdPlayGift
        assertTrue("When free gifts are depleted, ad play gift must be claimed", action is ReceiveDrawerStepAction.ClaimAdPlayGift)
        val adAction = action as ReceiveDrawerStepAction.ClaimAdPlayGift
        assertEquals("看廣告領取(▶)", adAction.label)
    }
}
