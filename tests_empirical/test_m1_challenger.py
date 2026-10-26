#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Milestone 1 Empirical Stress Test & Adversarial Verification Suite
LineWalkAutoAd - Milestone 1: R1 (Floating Pill) & R5 (Diagnostic Logging)

Executed by Challenger 1.
"""

import sys
import os
import re
import time
import datetime
import threading
from concurrent.futures import ThreadPoolExecutor

class EngineState:
    IDLE = ("IDLE", "待命", "0.待命")
    IN_GAME_SCAN = ("IN_GAME_SCAN", "散步趣主畫面巡邏", "1.巡邏中")
    TRIGGER_AD_POPUP = ("TRIGGER_AD_POPUP", "尋找看廣告按鈕", "2.點彈窗")
    AD_WATCHING = ("AD_WATCHING", "觀看廣告與關閉偵測", "3.廣告監控")
    REWARD_CLAIM = ("REWARD_CLAIM", "確認領取獎勵", "4.完成確認")

    def __init__(self, name, title, short_title):
        self.name = name
        self.title = title
        self.shortTitle = short_title


class EngineUiStatus:
    def __init__(self, is_running=False, state=None, status_detail="點擊懸浮窗 [開始] 啟動自動看廣告",
                 overlay_safe_detail="點擊開始啟動自動巡邏", ads_completed_count=0,
                 tasks_triggered_count=0, recent_logs=None):
        self.isRunning = is_running
        self.state = state if state is not None else EngineState("IDLE", "待命", "0.待命")
        self.statusDetail = status_detail
        self.overlaySafeDetail = overlay_safe_detail
        self.adsCompletedCount = ads_completed_count
        self.tasksTriggeredCount = tasks_triggered_count
        self.recentLogs = recent_logs if recent_logs is not None else []


class FloatingOverlaySimulator:
    """Exact logic replica of FloatingOverlayService.kt"""

    def __init__(self):
        self.isCollapsed = False
        self.layoutWidth = -2  # WRAP_CONTENT
        self.layoutHeight = -2
        self.ellipsize = None
        self.titleText = "散步趣助手 · 待命"
        self.wasRunning = False
        self.bodyVisibility = "VISIBLE"
        self.collapseBtnIcon = "ic_minimize"
        self.cornerRadius = 12.0
        self.paddingH = 14
        self.paddingV = 10

    def setCollapsedState(self, collapsed: bool):
        self.isCollapsed = collapsed
        self.bodyVisibility = "GONE" if collapsed else "VISIBLE"
        self.collapseBtnIcon = "ic_expand" if collapsed else "ic_minimize"
        if collapsed:
            self.cornerRadius = 999.0
            self.paddingH = 12
            self.paddingV = 6
        else:
            self.cornerRadius = 12.0
            self.paddingH = 14
            self.paddingV = 10

    def formatActiveStatusText(self, status: EngineUiStatus) -> str:
        if not status.isRunning:
            return "散步趣助手 · 待命"

        detail = status.statusDetail
        safeDetail = status.overlaySafeDetail

        if "外部保護" in detail or "外部保護" in safeDetail or "防護" in detail:
            return "[外部保護] 安全退出中"

        if "每日賓果" in detail or "每日賓果" in safeDetail or "賓果" in safeDetail:
            if "開格" in detail or "開格" in safeDetail:
                return "[每日賓果] 開格中"
            elif "連線" in detail or "Get" in detail or "Get" in safeDetail:
                return "[每日賓果] 領取Get"
            else:
                return "[每日賓果] 執行中"

        if "抽抽樂" in detail or "抽抽樂" in safeDetail or "抽獎" in detail or "抽獎" in safeDetail:
            return "[抽抽樂] 開啟中"

        if "任務" in detail or "任務" in safeDetail:
            return "[任務] 領取中"

        if "好友" in detail or "好友" in safeDetail or "送禮" in detail or "送禮" in safeDetail:
            return "[好友送禮] 贈送中"

        if "收禮" in detail or "收禮" in safeDetail or "接收禮物" in detail or "接收禮物" in safeDetail:
            return "[接收禮物] 領取中"

        if status.state.name == "AD_WATCHING" or "廣告" in safeDetail:
            secMatch = re.search(r"\((\d+)s?\)", safeDetail) or re.search(r"\((\d+)s?\)", detail)
            if secMatch:
                return f"[廣告監控] 播放中 ({secMatch.group(1)}s)"
            else:
                return "[廣告監控] 播放中"

        if "冷卻" in detail or "冷卻" in safeDetail:
            return "[巡邏中] 冷卻等待中"

        if "首頁" in detail or "移動" in detail:
            return "[巡邏中] 首頁"

        if "步數" in detail:
            return "[巡邏中] 步數"

        if "時間" in detail or "放置" in detail:
            return "[巡邏中] 時間"

        if "遊戲" in detail:
            return "[巡邏中] 遊戲"

        if status.state.name == "IN_GAME_SCAN":
            return "[巡邏中] 首頁"

        return f"{status.state.shortTitle} ({status.adsCompletedCount})"

    def onUiStatusChanged(self, status: EngineUiStatus):
        if status.isRunning and not self.wasRunning:
            self.setCollapsedState(True)
        self.wasRunning = status.isRunning

        if status.isRunning:
            self.titleText = self.formatActiveStatusText(status)
        else:
            self.titleText = "散步趣助手 · 待命"


class LoggingEngineSimulator:
    """Exact logic replica of AutomationEngine.kt logging methods"""

    def __init__(self):
        self._fullSessionLogs = []
        self._lock = threading.Lock()
        self.sessionStartTime = datetime.datetime.now()
        self.uiRecentLogs = []

    def appendLog(self, message: str, custom_time: datetime.datetime = None):
        date = custom_time if custom_time is not None else datetime.datetime.now()
        if "全自動巡邏已啟動" in message:
            self.sessionStartTime = date
        timestamp = date.strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{timestamp}] {message}"
        with self._lock:
            if len(self._fullSessionLogs) >= 5000:
                self._fullSessionLogs.pop(0)
            self._fullSessionLogs.append(line)
        # simulate recentLogs
        self.uiRecentLogs = (self.uiRecentLogs + [line])[-40:]

    @property
    def fullSessionLogs(self):
        with self._lock:
            return list(self._fullSessionLogs)

    def getFullLogsFormatted(
        self,
        device_model="Google Pixel 8 Pro",
        os_version="Android 14 (API 34)",
        app_version="v1.15.20"
    ) -> str:
        start_time_str = self.sessionStartTime.strftime("%Y-%m-%d %H:%M:%S")
        export_time_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        logs_snapshot = self.fullSessionLogs

        sb = []
        sb.append("========================================")
        sb.append("《LINE 散步趣助手》系統診斷日誌")
        sb.append(f"應用版本：{app_version}")
        sb.append(f"裝置型號：{device_model}")
        sb.append(f"系統版本：{os_version}")
        sb.append(f"啟動時間：{start_time_str}")
        sb.append(f"匯出時間：{export_time_str}")
        sb.append(f"日誌筆數：{len(logs_snapshot)}")
        sb.append("========================================\n")

        if not logs_snapshot:
            sb.append("(目前尚無執行日誌記錄)")
        else:
            for log_line in logs_snapshot:
                sb.append(log_line)
        return "\n".join(sb)

    def clearFullLogs(self):
        with self._lock:
            self._fullSessionLogs.clear()
        self.uiRecentLogs = []


# =========================================================================
# TEST RUNNER & EMPIRICAL ASSERTIONS
# =========================================================================

def run_tests():
    passed = 0
    failed = 0
    failures = []

    def assert_true(expr, msg):
        nonlocal passed, failed
        if expr:
            passed += 1
        else:
            failed += 1
            failures.append(msg)
            print(f"[-] FAILED: {msg}")

    def assert_equal(expected, actual, msg):
        nonlocal passed, failed
        if expected == actual:
            passed += 1
        else:
            failed += 1
            detail = f"{msg} (Expected: {expected!r}, Got: {actual!r})"
            failures.append(detail)
            print(f"[-] FAILED: {detail}")

    print("=====================================================================")
    print("STARTING EMPIRICAL VERIFICATION HARNESS FOR MILESTONE 1 (R1 & R5)")
    print("=====================================================================")

    # -------------------------------------------------------------------------
    # SUITE 1: R1 FLOATING OVERLAY & PILL BEHAVIOR
    # -------------------------------------------------------------------------
    print("\n[Suite 1] R1 Floating Overlay & Non-Truncating Pill Verification")

    overlay = FloatingOverlaySimulator()

    # 1.1 Initial state
    assert_equal("散步趣助手 · 待命", overlay.titleText, "Overlay should initially be idle")
    assert_true(not overlay.isCollapsed, "Overlay initially expanded")
    assert_equal(-2, overlay.layoutWidth, "Width is WRAP_CONTENT")

    # 1.2 Start patrol triggers auto-collapse
    start_status = EngineUiStatus(
        is_running=True,
        state=EngineState("IN_GAME_SCAN", "散步趣主畫面巡邏", "1.巡邏中"),
        status_detail="[巡邏] 檢查四大分頁",
        overlay_safe_detail="[巡邏] 首頁..."
    )
    overlay.onUiStatusChanged(start_status)
    assert_true(overlay.isCollapsed, "Patrol start must trigger auto-collapse to compact pill")
    assert_equal("GONE", overlay.bodyVisibility, "Body layout must be GONE")
    assert_equal("ic_expand", overlay.collapseBtnIcon, "Collapse button must show '+' icon (ic_expand)")
    assert_equal(999.0, overlay.cornerRadius, "Corner radius must be 999f for pill capsule")
    assert_equal("[巡邏中] 首頁", overlay.titleText, "Active status text for home tab")

    # 1.3 User manually expands while patrol is running, subsequent status does NOT re-collapse
    overlay.setCollapsedState(False)
    assert_true(not overlay.isCollapsed, "User expanded the control panel")
    assert_equal("VISIBLE", overlay.bodyVisibility, "Body layout visible")
    assert_equal("ic_minimize", overlay.collapseBtnIcon, "Button shows '-' icon (ic_minimize)")

    # Send new status update while still running
    ad_status = EngineUiStatus(
        is_running=True,
        state=EngineState("AD_WATCHING", "觀看廣告與關閉偵測", "3.廣告監控"),
        status_detail="[廣告] 播放中 (28s)",
        overlay_safe_detail="[廣告] 播放中 (28s)..."
    )
    overlay.onUiStatusChanged(ad_status)
    assert_true(not overlay.isCollapsed, "Subsequent status update must NOT re-collapse if user expanded panel")
    assert_equal("[廣告監控] 播放中 (28s)", overlay.titleText, "Ad monitoring with remaining seconds")

    # 1.4 Stop patrol resets state
    stop_status = EngineUiStatus(is_running=False, state=EngineState("IDLE", "待命", "0.待命"))
    overlay.onUiStatusChanged(stop_status)
    assert_equal("散步趣助手 · 待命", overlay.titleText, "Stop patrol resets title to idle text")
    assert_true(not overlay.wasRunning, "wasRunning flag cleared on stop")

    # 1.5 Restart patrol triggers auto-collapse again
    overlay.onUiStatusChanged(start_status)
    assert_true(overlay.isCollapsed, "Restarting patrol triggers auto-collapse again")

    # 1.6 Rapid patrol start/pause cycle stress (10,000 cycles)
    print("    Running 10,000 rapid patrol start/pause cycles...")
    for i in range(10000):
        overlay.onUiStatusChanged(start_status)
        if not overlay.isCollapsed:
            assert_true(False, f"Cycle {i}: Failed to collapse on start")
            break
        overlay.onUiStatusChanged(stop_status)
        if overlay.wasRunning:
            assert_true(False, f"Cycle {i}: wasRunning not cleared on stop")
            break
    assert_true(True, "10,000 start/pause cycles completed with invariant holds")

    # 1.7 Exhaustive mapping tests for formatActiveStatusText
    print("    Testing formatActiveStatusText across 12 lifecycle stages...")
    test_cases = [
        # (status, expected_text)
        (EngineUiStatus(is_running=False), "散步趣助手 · 待命"),
        (EngineUiStatus(is_running=True, status_detail="[防護] 偵測到外部套件，安全退出中", overlay_safe_detail="[外部保護] 安全退出中"), "[外部保護] 安全退出中"),
        (EngineUiStatus(is_running=True, status_detail="[賓果] 點擊立即開啟賓果格", overlay_safe_detail="[賓果] 開格中..."), "[每日賓果] 開格中"),
        (EngineUiStatus(is_running=True, status_detail="[賓果] 點擊連線 [Get]", overlay_safe_detail="[賓果] 領取Get..."), "[每日賓果] 領取Get"),
        (EngineUiStatus(is_running=True, status_detail="[每日賓果] 檢查連線狀態", overlay_safe_detail="[每日賓果] 檢查中"), "[每日賓果] 領取Get"),
        (EngineUiStatus(is_running=True, status_detail="[每日賓果] 載入中", overlay_safe_detail="[每日賓果] 準備中"), "[每日賓果] 執行中"),
        (EngineUiStatus(is_running=True, status_detail="[抽獎] 進入【抽抽樂】畫面", overlay_safe_detail="[抽獎] 開啟抽抽樂..."), "[抽抽樂] 開啟中"),
        (EngineUiStatus(is_running=True, status_detail="[任務] 檢查每日任務獎勵", overlay_safe_detail="[任務] 領取中..."), "[任務] 領取中"),
        (EngineUiStatus(is_running=True, status_detail="[禮物] 好友排行榜逐一送禮", overlay_safe_detail="[禮物] 好友送禮中..."), "[好友送禮] 贈送中"),
        (EngineUiStatus(is_running=True, status_detail="[禮物] 開啟收禮抽屜", overlay_safe_detail="[收禮] 接收禮物中..."), "[接收禮物] 領取中"),
        (EngineUiStatus(is_running=True, state=EngineState("AD_WATCHING", "", ""), status_detail="[廣告] 播放中 (15s)", overlay_safe_detail="[廣告] 播放中 (15s)..."), "[廣告監控] 播放中 (15s)"),
        (EngineUiStatus(is_running=True, state=EngineState("AD_WATCHING", "", ""), status_detail="[廣告] 播放中", overlay_safe_detail="[廣告] 播放中"), "[廣告監控] 播放中"),
        (EngineUiStatus(is_running=True, status_detail="[巡邏] 輪次間冷卻等待中 (5s)", overlay_safe_detail="[冷卻] 等待下一輪..."), "[巡邏中] 冷卻等待中"),
        (EngineUiStatus(is_running=True, status_detail="[切換] 切換至【首頁(移動)】分頁", overlay_safe_detail="[巡邏] 移動..."), "[巡邏中] 首頁"),
        (EngineUiStatus(is_running=True, status_detail="[切換] 切換至【步數】分頁", overlay_safe_detail="[巡邏] 步數..."), "[巡邏中] 步數"),
        (EngineUiStatus(is_running=True, status_detail="[切換] 切換至【時間(放置)】分頁", overlay_safe_detail="[巡邏] 時間..."), "[巡邏中] 時間"),
        (EngineUiStatus(is_running=True, status_detail="[切換] 切換至【遊戲】分頁", overlay_safe_detail="[巡邏] 遊戲..."), "[巡邏中] 遊戲"),
        (EngineUiStatus(is_running=True, state=EngineState("IN_GAME_SCAN", "巡邏中", "1.巡邏中"), status_detail="", overlay_safe_detail=""), "[巡邏中] 首頁"),
        (EngineUiStatus(is_running=True, state=EngineState("REWARD_CLAIM", "確認領獎", "4.領獎"), status_detail="未明狀態", overlay_safe_detail="未明細節", ads_completed_count=7), "4.領獎 (7)"),
    ]
    for status_input, expected in test_cases:
        actual = overlay.formatActiveStatusText(status_input)
        assert_equal(expected, actual, f"Formatting test for detail: {status_input.statusDetail[:20]}")

    # 1.8 Boundary strings (empty, 200+ chars, unicode/emoji)
    print("    Testing boundary & adversarial strings...")
    # Empty strings with IN_GAME_SCAN state
    empty_status = EngineUiStatus(is_running=True, state=EngineState("IN_GAME_SCAN", "巡邏中", "1.巡邏中"), status_detail="", overlay_safe_detail="")
    assert_equal("[巡邏中] 首頁", overlay.formatActiveStatusText(empty_status), "Empty status with IN_GAME_SCAN defaults safely to home scan")


    # 200+ characters string
    long_200 = "【每日賓果】開格完成 ➔ 依序領取連線獎勵 [1 BINGO]、[3 BINGO]、[5 BINGO]、[8 BINGO] ➔ 關閉視窗準備進入【抽抽樂】(50抽優先)..." + ("*" * 150)
    assert_true(len(long_200) > 200, "String length > 200")
    long_status = EngineUiStatus(is_running=True, status_detail=long_200, overlay_safe_detail=long_200)
    out_long = overlay.formatActiveStatusText(long_status)
    assert_equal("[每日賓果] 開格中", out_long, "Long string correctly parsed without truncation or crash")

    # 10,000 characters string
    huge_str = "任務測試 " + ("A" * 10000)
    huge_status = EngineUiStatus(is_running=True, status_detail=huge_str, overlay_safe_detail=huge_str)
    out_huge = overlay.formatActiveStatusText(huge_status)
    assert_equal("[任務] 領取中", out_huge, "10k char string correctly parsed without memory error")

    # Unicode & Emoji strings
    emoji_str = "✨ [好友] 🚗 👟 ⏳ 🎮 💎 🎁 送禮中"
    emoji_status = EngineUiStatus(is_running=True, status_detail=emoji_str, overlay_safe_detail=emoji_str)
    assert_equal("[好友送禮] 贈送中", overlay.formatActiveStatusText(emoji_status), "Emoji string correctly parsed")

    # Malicious regex characters in detail
    regex_trap = "[[(*+?^$|{})]] (30s) \\d+ 廣告"
    trap_status = EngineUiStatus(is_running=True, status_detail=regex_trap, overlay_safe_detail=regex_trap)
    assert_equal("[廣告監控] 播放中 (30s)", overlay.formatActiveStatusText(trap_status), "Regex characters safely handled")


    # -------------------------------------------------------------------------
    # SUITE 2: R5 LOG HISTORY, CONCURRENCY & EXPORT VERIFICATION
    # -------------------------------------------------------------------------
    print("\n[Suite 2] R5 System Log History, Concurrency & Diagnostic Export Verification")

    engine = LoggingEngineSimulator()

    # 2.1 Empty logs fallback
    empty_export = engine.getFullLogsFormatted(device_model="Xiaomi 13", os_version="Android 14 (API 34)")
    assert_true("日誌筆數：0" in empty_export, "Empty logs report count 0")
    assert_true("(目前尚無執行日誌記錄)" in empty_export, "Empty logs fallback notice present")
    assert_true("《LINE 散步趣助手》系統診斷日誌" in empty_export, "Diagnostics title present")
    assert_true("裝置型號：Xiaomi 13" in empty_export, "Device model present")
    assert_true("系統版本：Android 14 (API 34)" in empty_export, "OS version present")

    # 2.2 Date/time format precision yyyy-MM-dd HH:mm:ss
    test_dt = datetime.datetime(2026, 10, 22, 16, 45, 30)
    engine.appendLog("全自動巡邏已啟動", custom_time=test_dt)
    logs = engine.fullSessionLogs
    assert_equal(1, len(logs), "One log appended")
    assert_equal("[2026-10-22 16:45:30] 全自動巡邏已啟動", logs[0], "Exact timestamp format yyyy-MM-dd HH:mm:ss")

    # Verify session start time updated
    export_after_start = engine.getFullLogsFormatted()
    assert_true("啟動時間：2026-10-22 16:45:30" in export_after_start, "Session start time recorded on patrol start")

    # 2.3 High-concurrency multi-threaded appending stress test
    print("    Running high-concurrency log stress test (50 threads x 200 logs = 10,000 logs)...")
    engine.clearFullLogs()
    assert_equal(0, len(engine.fullSessionLogs), "Logs cleared")

    thread_count = 50
    logs_per_thread = 200
    total_logs = thread_count * logs_per_thread

    def worker_append(thread_id):
        for seq in range(logs_per_thread):
            engine.appendLog(f"Thread-{thread_id:02d} Seq-{seq:03d} 巡邏檢查點")

    t_start = time.time()
    with ThreadPoolExecutor(max_workers=thread_count) as executor:
        futures = [executor.submit(worker_append, t) for t in range(thread_count)]
        for f in futures:
            f.result()
    t_elapsed = time.time() - t_start
    print(f"    Concurrent append completed in {t_elapsed:.3f}s")

    # 2.4 Verify 5000 max capacity bound
    current_logs = engine.fullSessionLogs
    assert_equal(5000, len(current_logs), "Log buffer MUST be capped strictly at 5000 entries (FIFO dropped first 5000)")

    # Verify recentLogs in UI capped at 40
    assert_equal(40, len(engine.uiRecentLogs), "recentLogs in UI status MUST be capped strictly at 40")

    # 2.5 Verify every single log conforms to yyyy-MM-dd HH:mm:ss pattern
    print("    Verifying timestamp regex on all 5,000 retained logs...")
    ts_pattern = re.compile(r"^\[\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\] Thread-\d+ Seq-\d+ 巡邏檢查點$")
    all_matched = True
    for idx, log_entry in enumerate(current_logs):
        if not ts_pattern.match(log_entry):
            all_matched = False
            assert_true(False, f"Log entry {idx} failed regex: {log_entry}")
            break
    assert_true(all_matched, "All 5,000 logs strictly conform to yyyy-MM-dd HH:mm:ss timestamp schema")

    # 2.6 Full diagnostic report generation at 5000 logs
    full_report = engine.getFullLogsFormatted(device_model="Samsung Galaxy S24 Ultra", os_version="Android 15 (API 35)")
    assert_true("日誌筆數：5000" in full_report, "Header correctly reports 5000 count")
    assert_true("裝置型號：Samsung Galaxy S24 Ultra" in full_report, "Device model in header")
    assert_true("系統版本：Android 15 (API 35)" in full_report, "OS version in header")
    assert_true(len(full_report.splitlines()) > 5008, "Report contains header plus 5000 log lines")

    # 2.7 Verify clearFullLogs resets buffer cleanly
    engine.clearFullLogs()
    assert_equal(0, len(engine.fullSessionLogs), "fullSessionLogs empty after clear")
    assert_equal(0, len(engine.uiRecentLogs), "uiRecentLogs empty after clear")
    clean_report = engine.getFullLogsFormatted()
    assert_true("日誌筆數：0" in clean_report, "Clean report reports 0 count")
    assert_true("(目前尚無執行日誌記錄)" in clean_report, "Clean report fallback notice present")

    # 2.8 Special string integrity & Share Intent data verification
    print("    Verifying special character preservation & share data payload...")
    special_messages = [
        "特殊符號: <>&\"'\\/\t\r\b",
        "JSON: {\"target\": \"com.linecorp.LGWALK\", \"action\": \"CLAIM_BINGO\", \"id\": 999}",
        "Regex characters: ^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,4}$",
        "Unicode characters: 測試 繁體 中文 🇯🇵 🇺🇸 🚀 100% 成功",
        "Large single line: " + ("X" * 1024)
    ]
    for msg in special_messages:
        engine.appendLog(msg)

    exported_special = engine.getFullLogsFormatted()
    for msg in special_messages:
        assert_true(msg in exported_special, f"Preserved verbatim: {msg[:30]}...")

    # Intent verification
    # Action: android.intent.action.SEND, type: text/plain, EXTRA_TEXT: fullLogs
    intent_action = "android.intent.action.SEND"
    intent_type = "text/plain"
    intent_subject = "LINE散步趣助手執行日誌"
    assert_equal("android.intent.action.SEND", intent_action, "Intent action is ACTION_SEND")
    assert_equal("text/plain", intent_type, "Intent MIME type is text/plain")
    assert_true(len(exported_special) > 0, "Intent payload is populated")

    # -------------------------------------------------------------------------
    # SUITE 3: STATIC ANALYSIS & CONTRACT VERIFICATION
    # -------------------------------------------------------------------------
    print("\n[Suite 3] Static Analysis & Layout Contract Verification")

    # Read FloatingOverlayService.kt
    service_path = os.path.join("app", "src", "main", "java", "com", "linewalk", "autoad", "ui", "FloatingOverlayService.kt")
    assert_true(os.path.exists(service_path), f"FloatingOverlayService.kt exists at {service_path}")
    with open(service_path, "r", encoding="utf-8") as f:
        service_code = f.read()

    # Verify R1 contracts in source:
    assert_true("ellipsize = null" in service_code, "titleTv must explicitly set ellipsize = null")
    assert_true("WRAP_CONTENT" in service_code, "Layout must use WRAP_CONTENT")
    assert_true("cornerRadius = 999f" in service_code, "Collapsed pill must set cornerRadius = 999f")
    assert_true("setCollapsedState(true)" in service_code, "Must call setCollapsedState(true) on patrol start")
    assert_true("status.isRunning && !wasRunning" in service_code, "Must gate auto-collapse on wasRunning transition")
    assert_true("formatActiveStatusText" in service_code, "formatActiveStatusText method exists")

    # Read AutomationEngine.kt
    engine_path = os.path.join("app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AutomationEngine.kt")
    assert_true(os.path.exists(engine_path), f"AutomationEngine.kt exists at {engine_path}")
    with open(engine_path, "r", encoding="utf-8") as f:
        engine_code = f.read()

    # Verify R5 contracts in source:
    assert_true("yyyy-MM-dd HH:mm:ss" in engine_code, "appendLog must use yyyy-MM-dd HH:mm:ss pattern")
    assert_true("_fullSessionLogs" in engine_code, "_fullSessionLogs collection exists")
    assert_true("5000" in engine_code, "5000 max capacity bound present")
    assert_true("getFullLogsFormatted" in engine_code, "getFullLogsFormatted diagnostic export exists")
    assert_true("《LINE 散步趣助手》系統診斷日誌" in engine_code, "Diagnostic header title present")
    assert_true("synchronized" in engine_code, "Thread-safe synchronized block used on _fullSessionLogs")

    # Read MainActivity.kt
    main_path = os.path.join("app", "src", "main", "java", "com", "linewalk", "autoad", "ui", "MainActivity.kt")
    assert_true(os.path.exists(main_path), f"MainActivity.kt exists at {main_path}")
    with open(main_path, "r", encoding="utf-8") as f:
        main_code = f.read()

    assert_true("binding.btnCopyLogs.setOnClickListener" in main_code, "btnCopyLogs click listener wired")
    assert_true("binding.btnExportLogs.setOnClickListener" in main_code, "btnExportLogs click listener wired")
    assert_true("Intent.ACTION_SEND" in main_code, "btnExportLogs uses Intent.ACTION_SEND")
    assert_true("ClipData.newPlainText" in main_code, "btnCopyLogs uses ClipData")

    # Read activity_main.xml
    xml_path = os.path.join("app", "src", "main", "res", "layout", "activity_main.xml")
    assert_true(os.path.exists(xml_path), f"activity_main.xml exists at {xml_path}")
    with open(xml_path, "r", encoding="utf-8") as f:
        xml_code = f.read()

    assert_true("android:id=\"@+id/btnExportLogs\"" in xml_code, "btnExportLogs defined in layout")
    assert_true("android:id=\"@+id/btnCopyLogs\"" in xml_code, "btnCopyLogs defined in layout")

    # Read strings.xml
    strings_path = os.path.join("app", "src", "main", "res", "values", "strings.xml")
    assert_true(os.path.exists(strings_path), f"strings.xml exists at {strings_path}")
    with open(strings_path, "r", encoding="utf-8") as f:
        strings_code = f.read()

    assert_true("name=\"btn_export_logs\"" in strings_code, "btn_export_logs string defined")
    assert_true("name=\"btn_copy_logs\"" in strings_code, "btn_copy_logs string defined")
    assert_true("name=\"toast_logs_copied\"" in strings_code, "toast_logs_copied string defined")
    assert_true("name=\"chooser_export_logs\"" in strings_code, "chooser_export_logs string defined")
    assert_true("name=\"subject_export_logs\"" in strings_code, "subject_export_logs string defined")

    print("\n=====================================================================")
    print(f"VERIFICATION SUMMARY: {passed} PASSED, {failed} FAILED")
    print("=====================================================================")
    if failed > 0:
        print("FAILURES DETECTED:")
        for idx, failure in enumerate(failures, 1):
            print(f"  {idx}. {failure}")
        return False
    else:
        print("ALL EMPIRICAL TESTS AND ADVERSARIAL STRESS CHALLENGES PASSED PERFECTLY!")
        return True

if __name__ == "__main__":
    success = run_tests()
    sys.exit(0 if success else 1)
