package com.linewalk.autoad.test.fixture

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import com.linewalk.autoad.vision.OcrItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mock Fixtures and Generators for LineWalkAutoAd E2E & Unit Test Suites.
 * Simulates screen layouts, OCR items, Bitmaps, and Node hierarchies.
 */
object MockFixtures {

    const val SCREEN_WIDTH = 1080
    const val SCREEN_HEIGHT = 2400

    /**
     * Helper to create an OcrItem with specific bounding rect
     */
    fun createOcrItem(text: String, left: Int, top: Int, right: Int, bottom: Int, confidence: Float = 0.99f): OcrItem {
        return OcrItem(
            text = text,
            bounds = Rect(left, top, right, bottom),
            confidence = confidence
        )
    }

    /**
     * Helper to create an OcrItem with normalized ratios (0.0f..1.0f)
     */
    fun createNormalizedOcrItem(
        text: String,
        leftRatio: Float,
        topRatio: Float,
        rightRatio: Float,
        bottomRatio: Float,
        sw: Int = SCREEN_WIDTH,
        sh: Int = SCREEN_HEIGHT
    ): OcrItem {
        return createOcrItem(
            text = text,
            left = (sw * leftRatio).toInt(),
            top = (sh * topRatio).toInt(),
            right = (sw * rightRatio).toInt(),
            bottom = (sh * bottomRatio).toInt()
        )
    }

    // ==========================================
    // Screen Fixture Generators
    // ==========================================

    /**
     * Generates OCR items for the Daily Bingo Dialog (R4).
     */
    fun createBingoDialogItems(
        hasOpenCell: Boolean = true,
        inCooldown: Boolean = false,
        cooldownText: String = "02:45",
        isLimitReached: Boolean = false,
        getColumns: List<Int> = listOf(1, 3), // active columns: 1, 3, 5, 8
        sw: Int = SCREEN_WIDTH,
        sh: Int = SCREEN_HEIGHT
    ): List<OcrItem> {
        val items = mutableListOf<OcrItem>()

        // Title at top (cy < 0.16f)
        items.add(createNormalizedOcrItem("每日賓果", 0.35f, 0.08f, 0.65f, 0.14f, sw, sh))

        // Subtitle / instructions
        items.add(createNormalizedOcrItem("連線即可獲得金幣獎勵", 0.20f, 0.15f, 0.80f, 0.18f, sw, sh))

        // Orange open cell button area (y ≈ 0.58f..0.70f)
        if (hasOpenCell && !inCooldown && !isLimitReached) {
            items.add(createNormalizedOcrItem("立即開啟賓果格", 0.20f, 0.61f, 0.80f, 0.67f, sw, sh))
        } else if (inCooldown) {
            items.add(createNormalizedOcrItem(cooldownText, 0.40f, 0.61f, 0.60f, 0.65f, sw, sh))
            items.add(createNormalizedOcrItem("後可再次開啟", 0.35f, 0.65f, 0.65f, 0.68f, sw, sh))
        } else if (isLimitReached) {
            items.add(createNormalizedOcrItem("已達到今日上限", 0.25f, 0.62f, 0.75f, 0.67f, sw, sh))
        }

        // Connection rewards row (y ≈ 0.78f..0.88f)
        // 4 Columns: 1 BINGO (0.18f), 3 BINGO (0.39f), 5 BINGO (0.61f), 8 BINGO (0.82f)
        val colRatios = mapOf(1 to 0.18f, 3 to 0.39f, 5 to 0.61f, 8 to 0.82f)
        for ((bingoCount, cx) in colRatios) {
            items.add(createNormalizedOcrItem("$bingoCount BINGO", cx - 0.08f, 0.73f, cx + 0.08f, 0.77f, sw, sh))
            if (bingoCount in getColumns) {
                items.add(createNormalizedOcrItem("▶ Get", cx - 0.07f, 0.80f, cx + 0.07f, 0.86f, sw, sh))
            } else {
                items.add(createNormalizedOcrItem("已達成", cx - 0.07f, 0.80f, cx + 0.07f, 0.86f, sw, sh))
            }
        }

        return items
    }

    /**
     * Generates OCR items for the Friend Gift Screen (R6).
     */
    fun createFriendGiftItems(
        currentGiftCount: Int = 3,
        maxGiftCount: Int = 5,
        isRankingsVisible: Boolean = true,
        hasGiftableFriends: Boolean = true,
        isConfirmModalVisible: Boolean = false,
        isSentCompletedModalVisible: Boolean = false,
        sw: Int = SCREEN_WIDTH,
        sh: Int = SCREEN_HEIGHT
    ): List<OcrItem> {
        val items = mutableListOf<OcrItem>()

        if (isSentCompletedModalVisible) {
            items.add(createNormalizedOcrItem("贈送完畢", 0.30f, 0.30f, 0.70f, 0.38f, sw, sh))
            items.add(createNormalizedOcrItem("已贈送金幣給你自己和好友", 0.20f, 0.40f, 0.80f, 0.46f, sw, sh))
            items.add(createNormalizedOcrItem("關閉", 0.35f, 0.71f, 0.65f, 0.75f, sw, sh))
            return items
        }

        if (isConfirmModalVisible) {
            items.add(createNormalizedOcrItem("要向好友贈送金幣嗎？", 0.20f, 0.35f, 0.80f, 0.42f, sw, sh))
            items.add(createNormalizedOcrItem("也送給自己", 0.25f, 0.50f, 0.75f, 0.56f, sw, sh))
            items.add(createNormalizedOcrItem("一般贈送", 0.35f, 0.62f, 0.65f, 0.68f, sw, sh))
            return items
        }

        if (isRankingsVisible) {
            items.add(createNormalizedOcrItem("好友排行榜", 0.30f, 0.05f, 0.70f, 0.10f, sw, sh))
            items.add(createNormalizedOcrItem("今日剩餘贈送次數 $currentGiftCount/$maxGiftCount", 0.20f, 0.12f, 0.80f, 0.16f, sw, sh))
        }

        if (hasGiftableFriends && currentGiftCount < maxGiftCount) {
            // Friend rows with gift buttons on the right
            for (i in 0..2) {
                val y = 0.40f + i * 0.14f
                items.add(createNormalizedOcrItem("好友 ${i + 1}", 0.15f, y, 0.45f, y + 0.05f, sw, sh))
                items.add(createNormalizedOcrItem("禮物", 0.80f, y, 0.92f, y + 0.05f, sw, sh))
            }
        }

        // Bottom nav tabs
        items.add(createNormalizedOcrItem("集點", 0.05f, 0.93f, 0.20f, 0.97f, sw, sh))
        items.add(createNormalizedOcrItem("好友", 0.30f, 0.93f, 0.45f, 0.97f, sw, sh))

        return items
    }

    /**
     * Generates OCR items for the Receive Gift Drawer (R6).
     */
    fun createReceiveGiftItems(
        solidClaimsCount: Int = 1,
        adPlaysCount: Int = 1,
        isEmpty: Boolean = false,
        sw: Int = SCREEN_WIDTH,
        sh: Int = SCREEN_HEIGHT
    ): List<OcrItem> {
        val items = mutableListOf<OcrItem>()

        // Drawer header
        items.add(createNormalizedOcrItem("禮物", 0.45f, 0.20f, 0.55f, 0.26f, sw, sh))

        if (isEmpty) {
            items.add(createNormalizedOcrItem("目前沒有禮物", 0.30f, 0.45f, 0.70f, 0.52f, sw, sh))
            items.add(createNormalizedOcrItem("尚無禮物可領取", 0.30f, 0.53f, 0.70f, 0.58f, sw, sh))
            return items
        }

        var currentY = 0.32f

        // Solid claims (free - together gifts)
        repeat(solidClaimsCount) { index ->
            items.add(createNormalizedOcrItem("一起送達的禮物 #$index", 0.15f, currentY, 0.60f, currentY + 0.04f, sw, sh))
            items.add(createNormalizedOcrItem("領取", 0.72f, currentY, 0.88f, currentY + 0.04f, sw, sh))
            currentY += 0.12f
        }

        // Ad plays (friend gifts requiring ad)
        repeat(adPlaysCount) { index ->
            items.add(createNormalizedOcrItem("收到好友送來的禮物 #$index", 0.15f, currentY, 0.60f, currentY + 0.04f, sw, sh))
            items.add(createNormalizedOcrItem("▶", 0.75f, currentY, 0.85f, currentY + 0.04f, sw, sh))
            items.add(createNormalizedOcrItem("今天 23:59 止", 0.60f, currentY + 0.04f, 0.90f, currentY + 0.07f, sw, sh))
            currentY += 0.12f
        }

        return items
    }

    /**
     * Generates OCR items for External Applications (R2).
     */
    fun createExternalAppItems(
        appName: String = "Google Chrome",
        url: String = "https://example.com/ad-landing",
        hasAdX: Boolean = true,
        sw: Int = SCREEN_WIDTH,
        sh: Int = SCREEN_HEIGHT
    ): List<OcrItem> {
        val items = mutableListOf<OcrItem>()
        items.add(createNormalizedOcrItem(appName, 0.10f, 0.04f, 0.50f, 0.08f, sw, sh))
        items.add(createNormalizedOcrItem(url, 0.15f, 0.09f, 0.85f, 0.13f, sw, sh))
        items.add(createNormalizedOcrItem("立即下載 享受優惠", 0.20f, 0.45f, 0.80f, 0.55f, sw, sh))
        items.add(createNormalizedOcrItem("安裝應用程式", 0.25f, 0.75f, 0.75f, 0.82f, sw, sh))

        if (hasAdX) {
            items.add(createNormalizedOcrItem("X", 0.88f, 0.05f, 0.96f, 0.10f, sw, sh))
        }
        return items
    }

    /**
     * Generates OCR items for Main Tabs (R3).
     */
    fun createMainTabsItems(
        activeTab: String = "首頁",
        sw: Int = SCREEN_WIDTH,
        sh: Int = SCREEN_HEIGHT
    ): List<OcrItem> {
        return listOf(
            createNormalizedOcrItem("首頁", 0.05f, 0.10f, 0.22f, 0.14f, sw, sh),
            createNormalizedOcrItem("步數", 0.28f, 0.10f, 0.48f, 0.14f, sw, sh),
            createNormalizedOcrItem("時間", 0.52f, 0.10f, 0.72f, 0.14f, sw, sh),
            createNormalizedOcrItem("遊戲", 0.76f, 0.10f, 0.96f, 0.10f, sw, sh),
            createNormalizedOcrItem("距離下次集滿量表", 0.20f, 0.35f, 0.80f, 0.42f, sw, sh),
            createNormalizedOcrItem("每日賓果", 0.05f, 0.40f, 0.20f, 0.45f, sw, sh),
            createNormalizedOcrItem("抽抽樂", 0.25f, 0.40f, 0.40f, 0.45f, sw, sh),
            createNormalizedOcrItem("任務", 0.45f, 0.40f, 0.60f, 0.45f, sw, sh)
        )
    }

    // ==========================================
    // Bitmap Pixel Generators
    // ==========================================

    /**
     * Color definitions for LineWalkAutoAd UI elements
     */
    object Colors {
        // Line Walk Orange Button (#FF8C00 ~ #FFA500)
        val BINGO_ORANGE = Color.rgb(255, 140, 0)
        // Line Walk Green Button (#06C755)
        val LINE_GREEN = Color.rgb(6, 199, 85)
        // Red Connection Get Button (#FF2020)
        val BINGO_GET_RED = Color.rgb(220, 30, 30)
        // Disabled Gray Button (#A0A0A0)
        val DISABLED_GRAY = Color.rgb(160, 160, 160)
        // White Background
        val WHITE = Color.WHITE
        // Black Text
        val BLACK = Color.BLACK
    }
}
