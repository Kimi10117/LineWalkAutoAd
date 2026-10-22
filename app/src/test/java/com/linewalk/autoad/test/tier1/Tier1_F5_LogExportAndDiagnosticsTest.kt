package com.linewalk.autoad.test.tier1

import com.linewalk.autoad.test.fixture.EngineSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tier 1 - Feature 5: Traditional Chinese System Log Export & Sharing (R5).
 * Tests verify full session log formatting with date/time timestamps, diagnostics header,
 * clipboard copy text generation, and Intent.ACTION_SEND sharing data structure.
 */
class Tier1_F5_LogExportAndDiagnosticsTest {

    private lateinit var simulator: EngineSimulator

    @Before
    fun setUp() {
        simulator = EngineSimulator()
    }

    @Test
    fun test01_fullSessionLogFormattingWithDateTimeTimestamps() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R5 & PROJECT.md §Interface Contracts ("包含 yyyy-MM-dd HH:mm:ss 時間戳記")
        val fixedTime = Date(1776848400000L) // Fixed timestamp for deterministic testing
        val expectedTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(fixedTime)

        simulator.appendLog("全自動巡邏已啟動", fixedTime)
        simulator.appendLog("切換至【首頁(移動)】分頁", fixedTime)

        assertEquals(2, simulator.fullSessionLogs.size)
        assertTrue("Log line 1 must contain formatted timestamp", simulator.fullSessionLogs[0].startsWith("[$expectedTimeStr]"))
        assertTrue("Log line 1 message intact", simulator.fullSessionLogs[0].contains("全自動巡邏已啟動"))
        assertTrue("Log line 2 must contain formatted timestamp", simulator.fullSessionLogs[1].startsWith("[$expectedTimeStr]"))
        assertTrue("Log line 2 message intact", simulator.fullSessionLogs[1].contains("切換至【首頁(移動)】分頁"))
    }

    @Test
    fun test02_diagnosticsHeaderGeneration() {
        // Authoritative Source: PROJECT.md §Interface Contracts ("回傳診斷標頭（裝置資訊、版本號、啟動時間）與完整日誌字串")
        simulator.appendLog("測試事件 1")
        simulator.appendLog("測試事件 2")

        val formattedLogs = simulator.getFullLogsFormatted(
            deviceModel = "Pixel 8 Pro",
            osVersion = "Android 14 (API 34)",
            appVersion = "v1.15.20"
        )

        assertTrue("Header must contain title", formattedLogs.contains("《LINE 散步趣助手》系統診斷日誌"))
        assertTrue("Header must contain app version", formattedLogs.contains("應用版本：v1.15.20"))
        assertTrue("Header must contain device model", formattedLogs.contains("裝置型號：Pixel 8 Pro"))
        assertTrue("Header must contain OS version", formattedLogs.contains("系統版本：Android 14 (API 34)"))
        assertTrue("Header must contain log count", formattedLogs.contains("日誌筆數：2"))
        assertTrue("Body must contain log 1", formattedLogs.contains("測試事件 1"))
        assertTrue("Body must contain log 2", formattedLogs.contains("測試事件 2"))
    }

    @Test
    fun test03_clipboardCopyActionTransfersFormattedLogContent() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R5 ("點擊後支援將完整執行日誌複製至剪貼簿")
        simulator.appendLog("點擊領取連線獎勵 [Get]")
        val formattedLogs = simulator.getFullLogsFormatted()

        assertFalse("Clipboard text must not be empty", formattedLogs.isBlank())
        assertTrue("Clipboard text must contain formatted log entry", formattedLogs.contains("點擊領取連線獎勵 [Get]"))
    }

    @Test
    fun test04_intentActionSendSharingIntentConfiguration() {
        // Authoritative Source: ORIGINAL_REQUEST.md §R5 ("並透過 Android 系統分享選單（Intent.ACTION_SEND）將日誌匯出")
        simulator.appendLog("好友送禮完成，進入收禮抽屜")
        val fullLogs = simulator.getFullLogsFormatted()

        val shareIntent = simulator.createShareIntentData(fullLogs)

        assertEquals("Intent action must be ACTION_SEND", "android.intent.action.SEND", shareIntent.action)
        assertEquals("Intent MIME type must be text/plain", "text/plain", shareIntent.type)
        assertEquals("Intent extra text must match formatted logs", fullLogs, shareIntent.textExtra)
    }

    @Test
    fun test05_emptyLogsExportFallback() {
        // Edge case: Exporting logs when no session logs exist yet
        val formattedLogs = simulator.getFullLogsFormatted()

        assertTrue(formattedLogs.contains("日誌筆數：0"))
        assertTrue(formattedLogs.contains("(目前尚無執行日誌記錄)"))
    }
}
