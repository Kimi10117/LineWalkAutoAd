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

    @Test
    fun test06_friendGiftQuotaRemaining3of5IsNotFull() {
        val task = com.linewalk.autoad.tasks.FriendGiftTask()
        val ocrItems = listOf(
            com.linewalk.autoad.vision.OcrItem("禮物次數 3/5", android.graphics.Rect(100, 100, 300, 150)),
            com.linewalk.autoad.vision.OcrItem("好友排行榜", android.graphics.Rect(100, 200, 300, 250))
        )
        assertTrue("3/5 quota must be recognized as remaining quota", task.hasRemainingGiftQuota(ocrItems))
        assertFalse("3/5 quota must NOT be recognized as quota full", task.isGiftQuotaFull(ocrItems))
    }

    @Test
    fun test07_friendGiftQuota0of5IsFull() {
        val task = com.linewalk.autoad.tasks.FriendGiftTask()
        val ocrItems = listOf(
            com.linewalk.autoad.vision.OcrItem("禮物次數 0/5", android.graphics.Rect(100, 100, 300, 150)),
            com.linewalk.autoad.vision.OcrItem("好友排行榜", android.graphics.Rect(100, 200, 300, 250))
        )
        assertFalse("0/5 quota has no remaining quota", task.hasRemainingGiftQuota(ocrItems))
        assertTrue("0/5 quota must be recognized as quota full", task.isGiftQuotaFull(ocrItems))
    }

    @Test
    fun test08_sentCompletedDialogNotMisidentifiedAsLottery() {
        val lotteryTask = com.linewalk.autoad.tasks.LotteryTask()
        val friendGiftTask = com.linewalk.autoad.tasks.FriendGiftTask()
        val sh = MockFixtures.SCREEN_HEIGHT
        val sw = MockFixtures.SCREEN_WIDTH
        val items = listOf(
            MockFixtures.createNormalizedOcrItem("排行榜", 0.15f, 0.10f, 0.35f, 0.14f, sw, sh),
            MockFixtures.createNormalizedOcrItem("贈送完畢", 0.30f, 0.26f, 0.70f, 0.32f, sw, sh),
            MockFixtures.createNormalizedOcrItem("已贈送金幣給你自己和白貓。", 0.20f, 0.34f, 0.80f, 0.40f, sw, sh),
            MockFixtures.createNormalizedOcrItem("Thank you for watching!", 0.20f, 0.46f, 0.80f, 0.52f, sw, sh),
            MockFixtures.createNormalizedOcrItem("Tap to learn more about this product", 0.15f, 0.54f, 0.85f, 0.58f, sw, sh),
            MockFixtures.createNormalizedOcrItem("關閉", 0.40f, 0.71f, 0.60f, 0.75f, sw, sh)
        )

        // Must NOT be recognized as lottery dialog
        assertFalse("Sent completed dialog with embedded ad must NOT be recognized as lottery dialog",
            lotteryTask.isLotteryDialogOpen(items, sh))

        // Must be recognized as friend page
        assertTrue("Sent completed dialog must be recognized as friend page",
            friendGiftTask.isFriendsPage(items, sh))

        // Must find [關閉] button
        val mockBitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true)
        io.mockk.every { mockBitmap.height } returns sh
        io.mockk.every { mockBitmap.width } returns sw
        val closeBtn = friendGiftTask.findSentCompletedCloseButton(mockBitmap, items)
        org.junit.Assert.assertNotNull("findSentCompletedCloseButton must find close button", closeBtn)
    }
}

