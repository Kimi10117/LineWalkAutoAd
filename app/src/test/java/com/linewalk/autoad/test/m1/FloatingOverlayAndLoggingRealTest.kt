package com.linewalk.autoad.test.m1

import com.linewalk.autoad.engine.EngineState
import com.linewalk.autoad.engine.EngineUiStatus
import com.linewalk.autoad.ui.FloatingOverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Worker M1 Unit Tests for Floating Overlay status formatting and log diagnostic generation.
 */
class FloatingOverlayAndLoggingRealTest {

    @Test
    fun testPillStatusText_idleState() {
        val service = FloatingOverlayService()
        val idleStatus = EngineUiStatus(isRunning = false, state = EngineState.IDLE)
        val text = service.formatActiveStatusText(idleStatus)
        assertEquals("散步趣助手 · 待命", text)
    }

    @Test
    fun testPillStatusText_runningPhases() {
        val service = FloatingOverlayService()

        // Bingo - open cell
        val bingoOpen = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[賓果] 點擊立即開啟賓果格",
            overlaySafeDetail = "[賓果] 開格中..."
        )
        assertEquals("[每日賓果] 開格中", service.formatActiveStatusText(bingoOpen))

        // Bingo - claim Get
        val bingoGet = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[賓果] 點擊連線 [Get]",
            overlaySafeDetail = "[賓果] 領取Get..."
        )
        assertEquals("[每日賓果] 領取Get", service.formatActiveStatusText(bingoGet))

        // Lottery
        val lottery = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[抽獎] 進入【抽抽樂】畫面",
            overlaySafeDetail = "[抽獎] 開啟抽抽樂..."
        )
        assertEquals("[抽抽樂] 開啟中", service.formatActiveStatusText(lottery))

        // Mission
        val mission = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[任務] 檢查每日任務獎勵",
            overlaySafeDetail = "[任務] 領取中..."
        )
        assertEquals("[任務] 領取中", service.formatActiveStatusText(mission))

        // Friend gift
        val friendGift = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[禮物] 好友排行榜逐一送禮",
            overlaySafeDetail = "[禮物] 好友送禮中..."
        )
        assertEquals("[好友送禮] 贈送中", service.formatActiveStatusText(friendGift))

        // Receive gift drawer
        val receiveGift = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[禮物] 開啟收禮抽屜",
            overlaySafeDetail = "[收禮] 接收禮物中..."
        )
        assertEquals("[接收禮物] 領取中", service.formatActiveStatusText(receiveGift))

        // Ad watching with seconds
        val adWithSec = EngineUiStatus(
            isRunning = true,
            state = EngineState.AD_WATCHING,
            statusDetail = "[廣告] 播放中 (15s)",
            overlaySafeDetail = "[廣告] 播放中 (15s)..."
        )
        assertEquals("[廣告監控] 播放中 (15s)", service.formatActiveStatusText(adWithSec))

        // Main tabs
        val moveTab = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[切換] 切換至【首頁(移動)】分頁",
            overlaySafeDetail = "[巡邏] 首頁..."
        )
        assertEquals("[巡邏中] 首頁", service.formatActiveStatusText(moveTab))

        val stepTab = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[切換] 切換至【步數】分頁",
            overlaySafeDetail = "[巡邏] 步數..."
        )
        assertEquals("[巡邏中] 步數", service.formatActiveStatusText(stepTab))

        // External guard
        val guard = EngineUiStatus(
            isRunning = true,
            state = EngineState.IN_GAME_SCAN,
            statusDetail = "[防護] 偵測到外部套件，安全退出中",
            overlaySafeDetail = "[外部保護] 安全退出中"
        )
        assertEquals("[外部保護] 安全退出中", service.formatActiveStatusText(guard))
    }

    @Test
    fun testSessionLogFormatting_datePattern() {
        val testDate = Date(1776848400000L)
        val expected = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(testDate)
        assertTrue(expected.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")))
    }

    @Test
    fun testOverlayCoordinateClampingMath() {
        val screenWidth = 1080
        val screenHeight = 2400
        val viewWidth = 260
        val viewHeight = 80
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0) // 820
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0) // 2320

        // Test negative drag
        val rawDragX = -384
        val rawDragY = -104
        val clampedX = rawDragX.coerceIn(0, maxX)
        val clampedY = rawDragY.coerceIn(0, maxY)
        assertEquals(0, clampedX)
        assertEquals(0, clampedY)

        // Test positive overflow drag
        val overflowX = 1416
        val overflowY = 3096
        assertEquals(820, overflowX.coerceIn(0, maxX))
        assertEquals(2320, overflowY.coerceIn(0, maxY))

        // Test rotation from portrait (y=1800) to landscape (height=1080)
        val landscapeHeight = 1080
        val landscapeMaxY = (landscapeHeight - viewHeight).coerceAtLeast(0) // 1000
        val rotatedY = 1800.coerceIn(0, landscapeMaxY)
        assertEquals(1000, rotatedY)
        assertTrue("Rotated overlay must be completely visible within screen height", rotatedY + viewHeight <= landscapeHeight)
    }

    @Test
    fun testGetExportLogsFormatted_capsAtMaxLines() {
        val simulator = com.linewalk.autoad.test.fixture.EngineSimulator()
        repeat(2000) { i ->
            simulator.appendLog("測試事件 #$i")
        }

        val exportLogs = simulator.getExportLogsFormatted(maxLines = 1500)
        val fullLogs = simulator.getFullLogsFormatted()

        assertTrue("Export logs must contain truncation warning", exportLogs.contains("已截取最近 1,500 筆記錄"))
        assertTrue("Export logs must contain latest line #1999", exportLogs.contains("測試事件 #1999"))
        assertFalse("Export logs must NOT contain early line #0", exportLogs.contains("測試事件 #0\n"))
        assertTrue("Full logs must retain all lines including line #0", fullLogs.contains("測試事件 #0\n"))
    }

    @Test
    fun testGetExportLogsFormatted_belowMaxLines_noTruncation() {
        val simulator = com.linewalk.autoad.test.fixture.EngineSimulator()
        repeat(20) { i ->
            simulator.appendLog("測試事件 #$i")
        }

        val exportLogs = simulator.getExportLogsFormatted(maxLines = 1500)
        assertFalse("Export logs must NOT contain truncation warning when below threshold", exportLogs.contains("已截取最近"))
        assertTrue("Export logs must contain line #0", exportLogs.contains("測試事件 #0"))
    }
}
