package com.linewalk.autoad.test.tier1

import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.linewalk.autoad.tasks.FriendGiftTask
import com.linewalk.autoad.tasks.LotteryTask
import com.linewalk.autoad.test.fixture.MockFixtures
import com.linewalk.autoad.vision.OcrItem
import com.linewalk.autoad.vision.OcrRecognizer
import com.linewalk.autoad.vision.TemplateMatcher
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5-Core Remediation Unit Tests:
 * - R1: Lottery draw priority order (50-draw 1st, free ad draw 2nd) and Grok Bot banner false-positive rejection
 * - R2: Meta left-top geometric X and fallback hot zone contracts
 * - R3: Friend gift loop elimination, 2-click attempt limit, skip list, and 0/5 full quota instant finish
 */
class Tier1_F7_LotteryDrawPriorityAndGrokGuardTest {

    private val lotteryTask = LotteryTask()
    private val ocrRecognizer = OcrRecognizer()
    private val friendGiftTask = FriendGiftTask()
    private val templateMatcher = mockk<TemplateMatcher>(relaxed = true)

    private val sw = MockFixtures.SCREEN_WIDTH
    private val sh = MockFixtures.SCREEN_HEIGHT

    @Test
    fun test01_lotteryPriority50DrawFirst() {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns sw
        every { mockBitmap.height } returns sh
        every { templateMatcher.isPopupButtonDisabledGray(any(), any()) } returns false

        // Both 50-draw and free-draw are present
        val items = listOf(
            MockFixtures.createNormalizedOcrItem("抽抽樂", 0.40f, 0.12f, 0.60f, 0.16f, sw, sh),
            MockFixtures.createNormalizedOcrItem("BIG", 0.15f, 0.35f, 0.35f, 0.40f, sw, sh),
            MockFixtures.createNormalizedOcrItem("50張 抽1次", 0.15f, 0.46f, 0.45f, 0.52f, sw, sh),
            MockFixtures.createNormalizedOcrItem("免費抽1次", 0.65f, 0.53f, 0.95f, 0.58f, sw, sh)
        )

        val target = lotteryTask.findTargetDrawButton(mockBitmap, items, templateMatcher)
        assertNotNull("Target draw button must not be null", target)
        assertEquals("50-draw must be prioritized 1st when tickets >= 50", "50張抽一次", target!!.first)
    }

    @Test
    fun test02_lotteryPriorityFreeDrawSecondWhen50InCooldown() {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns sw
        every { mockBitmap.height } returns sh
        every { templateMatcher.isPopupButtonDisabledGray(any(), any()) } returns false

        // 50-draw in cooldown / 0 times, free-draw available
        val items = listOf(
            MockFixtures.createNormalizedOcrItem("抽抽樂", 0.40f, 0.12f, 0.60f, 0.16f, sw, sh),
            MockFixtures.createNormalizedOcrItem("BIG", 0.15f, 0.35f, 0.35f, 0.40f, sw, sh),
            MockFixtures.createNormalizedOcrItem("剩0次", 0.15f, 0.46f, 0.45f, 0.52f, sw, sh),
            MockFixtures.createNormalizedOcrItem("免費抽1次", 0.65f, 0.53f, 0.95f, 0.58f, sw, sh)
        )

        val target = lotteryTask.findTargetDrawButton(mockBitmap, items, templateMatcher)
        assertNotNull("Free draw must be chosen when 50-draw is cooling", target)
        assertEquals("免費看廣告抽一次", target!!.first)
    }

    @Test
    fun test03_lotteryAllInCooldownReturnsNull() {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns sw
        every { mockBitmap.height } returns sh

        val items = listOf(
            MockFixtures.createNormalizedOcrItem("抽抽樂", 0.40f, 0.12f, 0.60f, 0.16f, sw, sh),
            MockFixtures.createNormalizedOcrItem("BIG", 0.15f, 0.35f, 0.35f, 0.40f, sw, sh),
            MockFixtures.createNormalizedOcrItem("回復還需 02:45", 0.15f, 0.46f, 0.45f, 0.52f, sw, sh),
            MockFixtures.createNormalizedOcrItem("回復還需 04:30", 0.65f, 0.53f, 0.95f, 0.58f, sw, sh),
            MockFixtures.createNormalizedOcrItem("5張 抽1次", 0.65f, 0.46f, 0.95f, 0.52f, sw, sh)
        )

        val target = lotteryTask.findTargetDrawButton(mockBitmap, items, templateMatcher)
        assertNull("When both are cooling, must return null (and never click 5-draw)", target)
    }

    @Test
    fun test04_grokAndAdBannerWordsNeverMatchCloseButton() {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns sw
        every { mockBitmap.height } returns sh

        val fakeAdKeywords = listOf("Grok", "Grok Bot", "TikTok", "Book", "Pocket", "Facebook", "Google Play", "安裝", "下載")

        for (kw in fakeAdKeywords) {
            val items = listOf(
                MockFixtures.createNormalizedOcrItem("獲得15張票券！", 0.25f, 0.28f, 0.75f, 0.35f, sw, sh),
                MockFixtures.createNormalizedOcrItem(kw, 0.30f, 0.66f, 0.70f, 0.72f, sw, sh)
            )
            val match = ocrRecognizer.findCenterRewardModalCloseButton(mockBitmap, items)
            // Even if fallback button triggers, it must NEVER return OCR[Grok] or match on the ad banner!
            if (match != null) {
                assertFalse("Must never match '$kw' as OCR close button", match.first.contains(kw))
            }
        }
    }

    @Test
    fun test05_exactOkWordMatchesSuccessfully() {
        val mockBitmap = mockk<Bitmap>(relaxed = true)
        every { mockBitmap.width } returns sw
        every { mockBitmap.height } returns sh

        val okKeywords = listOf("OK", "ok", " OK ", "[ OK ]", "關閉", "確認")

        for (kw in okKeywords) {
            val items = listOf(
                MockFixtures.createNormalizedOcrItem("獲得15張票券！", 0.25f, 0.28f, 0.75f, 0.35f, sw, sh),
                MockFixtures.createNormalizedOcrItem(kw, 0.35f, 0.66f, 0.65f, 0.72f, sw, sh)
            )
            val match = ocrRecognizer.findCenterRewardModalCloseButton(mockBitmap, items)
            assertNotNull("Must recognize exact keyword '$kw'", match)
            assertTrue("Matched label must indicate OCR", match!!.first.startsWith("OCR[") || match.first.contains("關閉"))
        }
    }

    @Test
    fun test06_friendGiftLoopEliminationAndNoMaliciousClear() {
        friendGiftTask.resetSession()

        // Verify markGifted and isAlreadyGiftedOrSkipped
        val pt1 = Point(928, 1200)
        val pt2 = Point(928, 1400)

        assertFalse(friendGiftTask.isAlreadyGiftedOrSkipped(pt1.x, pt1.y, sh))
        assertFalse(friendGiftTask.isAlreadyGiftedOrSkipped(pt2.x, pt2.y, sh))

        friendGiftTask.markGifted(pt1)
        assertTrue(friendGiftTask.isAlreadyGiftedOrSkipped(pt1.x, pt1.y, sh))
        assertFalse(friendGiftTask.isAlreadyGiftedOrSkipped(pt2.x, pt2.y, sh))

        friendGiftTask.markGifted(pt2)
        assertTrue(friendGiftTask.isAlreadyGiftedOrSkipped(pt2.x, pt2.y, sh))
    }

    @Test
    fun test07_friendGiftClickCountAndSkipLimit() {
        friendGiftTask.resetSession()
        val pt = Point(928, 1200)

        assertEquals(0, friendGiftTask.getButtonClickCount(pt, sh))
        val count1 = friendGiftTask.recordButtonClick(pt, sh)
        assertEquals(1, count1)
        assertFalse("1 click should not trigger skip", friendGiftTask.isAlreadyGiftedOrSkipped(pt.x, pt.y, sh))

        val count2 = friendGiftTask.recordButtonClick(pt, sh)
        assertEquals(2, count2)
        assertTrue(">= 2 clicks must be considered skipped", friendGiftTask.isAlreadyGiftedOrSkipped(pt.x, pt.y, sh))

        // Also test markSkipped
        val ptSkip = Point(928, 1500)
        friendGiftTask.markSkipped(ptSkip)
        assertTrue(friendGiftTask.isAlreadyGiftedOrSkipped(ptSkip.x, ptSkip.y, sh))
    }

    @Test
    fun test08_friendGiftQuotaFullDetection() {
        // 0/5 is full
        val itemsFull = listOf(
            MockFixtures.createNormalizedOcrItem("禮物次數 0/5", 0.10f, 0.20f, 0.40f, 0.25f, sw, sh)
        )
        assertTrue(friendGiftTask.isGiftQuotaFull(itemsFull))
        assertFalse(friendGiftTask.hasRemainingGiftQuota(itemsFull))

        // 3/5 has remaining quota
        val itemsRemaining = listOf(
            MockFixtures.createNormalizedOcrItem("禮物次數 3/5", 0.10f, 0.20f, 0.40f, 0.25f, sw, sh)
        )
        assertFalse(friendGiftTask.isGiftQuotaFull(itemsRemaining))
        assertTrue(friendGiftTask.hasRemainingGiftQuota(itemsRemaining))
    }
}
