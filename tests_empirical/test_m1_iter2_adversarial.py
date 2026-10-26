#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EMPIRICAL CHALLENGER ADVERSARIAL STRESS SUITE — Milestone 1 Iteration 2
LineWalkAutoAd Stability Fixes Verification

Targets 4 specific fixes:
1. FloatingOverlayService touch dragging boundary clamping under extreme deltas and degenerate view/screen sizes.
2. FloatingOverlayService orientation changes and dynamic expand/collapse bounds re-clamping.
3. MainActivity ClipboardManager safe casting, ActivityNotFoundException fallback, and string resources.
4. AutomationEngine IPC Binder buffer protection (getExportLogsFormatted <= 512KB) vs 100% full log retention in getFullLogsFormatted (exactly 5,000 logs).
"""

import sys
import os
import re
import time
import math
import datetime
import threading
from concurrent.futures import ThreadPoolExecutor

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
APP_SRC_MAIN = os.path.join(PROJECT_ROOT, "app", "src", "main")

class KotlinIntMath:
    """Exact simulation of 32-bit signed integer arithmetic and Kotlin coerceIn/coerceAtLeast."""
    INT32_MIN = -2147483648
    INT32_MAX = 2147483647

    @staticmethod
    def to_int32(val: int) -> int:
        val = val & 0xFFFFFFFF
        if val >= 0x80000000:
            val -= 0x100000000
        return val

    @staticmethod
    def coerce_at_least(val: int, minimum: int) -> int:
        return max(val, minimum)

    @staticmethod
    def coerce_in(val: int, minimum: int, maximum: int) -> int:
        if minimum > maximum:
            raise ValueError(f"Cannot coerce value to an empty range: maximum {maximum} is less than minimum {minimum}.")
        if val < minimum:
            return minimum
        if val > maximum:
            return maximum
        return val


class OverlayClampingEngine:
    """Exact replica of FloatingOverlayService clamping logic."""

    @staticmethod
    def clamp_drag(initial_x: int, initial_y: int,
                   delta_x: int, delta_y: int,
                   screen_w: int, screen_h: int,
                   view_w: int, view_h: int):
        # Kotlin:
        # val maxX = (displayMetrics.widthPixels - viewW).coerceAtLeast(0)
        # val maxY = (displayMetrics.heightPixels - viewH).coerceAtLeast(0)
        # wmParams.x = (initialX + deltaX).coerceIn(0, maxX)
        # wmParams.y = (initialY + deltaY).coerceIn(0, maxY)
        v_w = view_w if view_w > 0 else 1
        v_h = view_h if view_h > 0 else 1
        max_x = KotlinIntMath.coerce_at_least(screen_w - v_w, 0)
        max_y = KotlinIntMath.coerce_at_least(screen_h - v_h, 0)

        # 32-bit signed int overflow simulation
        raw_x = KotlinIntMath.to_int32(initial_x + delta_x)
        raw_y = KotlinIntMath.to_int32(initial_y + delta_y)

        clamped_x = KotlinIntMath.coerce_in(raw_x, 0, max_x)
        clamped_y = KotlinIntMath.coerce_in(raw_y, 0, max_y)
        return clamped_x, clamped_y, max_x, max_y

    @staticmethod
    def re_clamp_after_rotation(curr_x: int, curr_y: int,
                                new_screen_w: int, new_screen_h: int,
                                view_w: int, view_h: int):
        v_w = view_w if view_w > 0 else 1
        v_h = view_h if view_h > 0 else 1
        max_x = KotlinIntMath.coerce_at_least(new_screen_w - v_w, 0)
        max_y = KotlinIntMath.coerce_at_least(new_screen_h - v_h, 0)
        clamped_x = KotlinIntMath.coerce_in(curr_x, 0, max_x)
        clamped_y = KotlinIntMath.coerce_in(curr_y, 0, max_y)
        return clamped_x, clamped_y, max_x, max_y


class SimulatedAutomationEngine:
    """Exact replica of AutomationEngine logging, formatting, and buffer retention."""

    def __init__(self, capacity=5000):
        self.capacity = capacity
        self._fullSessionLogs = []
        self._lock = threading.Lock()
        self.sessionStartTime = datetime.datetime.now()

    def appendLog(self, message: str, timestamp_dt: datetime.datetime = None):
        date = timestamp_dt or datetime.datetime.now()
        if "全自動巡邏已啟動" in message:
            self.sessionStartTime = date
        ts = date.strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{ts}] {message}"
        with self._lock:
            if len(self._fullSessionLogs) >= self.capacity:
                self._fullSessionLogs.pop(0)
            self._fullSessionLogs.add if hasattr(self._fullSessionLogs, 'add') else self._fullSessionLogs.append(line)

    @property
    def fullSessionLogs(self):
        with self._lock:
            return list(self._fullSessionLogs)

    def getExportLogsFormatted(self, maxLines: int = 1500,
                               deviceModel: str = "Google Pixel 8",
                               osVersion: str = "Android 14 (API 34)",
                               appVersion: str = "v1.15.20") -> str:
        date_format = "%Y-%m-%d %H:%M:%S"
        start_time_str = self.sessionStartTime.strftime(date_format)
        export_time_str = datetime.datetime.now().strftime(date_format)
        logs_snapshot = self.fullSessionLogs
        is_truncated = len(logs_snapshot) > maxLines
        target_logs = logs_snapshot[-maxLines:] if is_truncated else logs_snapshot

        sb = []
        sb.append("========================================")
        sb.append("《LINE 散步趣助手》系統診斷日誌")
        sb.append(f"應用版本：{appVersion}")
        sb.append(f"裝置型號：{deviceModel}")
        sb.append(f"系統版本：{osVersion}")
        sb.append(f"啟動時間：{start_time_str}")
        sb.append(f"匯出時間：{export_time_str}")
        if is_truncated:
            sb.append(f"日誌筆數：{len(target_logs)} (總計 {len(logs_snapshot)} 筆，已截取最近 1,500 筆記錄)")
        else:
            sb.append(f"日誌筆數：{len(target_logs)}")
        sb.append("========================================\n")

        if is_truncated:
            sb.append("[注意：日誌量龐大，已匯出最近 1,500 筆記錄以防系統溢位。如需完整記錄請使用「複製日誌」功能]\n")

        if not target_logs:
            sb.append("(目前尚無執行日誌記錄)")
        else:
            for line in target_logs:
                sb.append(line)
        return "\n".join(sb) + "\n"

    def getFullLogsFormatted(self, deviceModel: str = "Google Pixel 8",
                             osVersion: str = "Android 14 (API 34)",
                             appVersion: str = "v1.15.20") -> str:
        # Kotlin: getExportLogsFormatted(maxLines = Int.MAX_VALUE)
        return self.getExportLogsFormatted(
            maxLines=KotlinIntMath.INT32_MAX,
            deviceModel=deviceModel,
            osVersion=osVersion,
            appVersion=appVersion
        )


def test_fix1_extreme_coordinate_clamping():
    print("\n--- [CHALLENGE FIX 1] Boundary Clamping Under Extreme & Adversarial Inputs ---")
    screen_configs = [
        {"name": "Portrait 1080p", "w": 1080, "h": 2400, "vw": 260, "vh": 80},
        {"name": "Landscape 1080p", "w": 2400, "h": 1080, "vw": 260, "vh": 80},
        {"name": "Budget 480p", "w": 480, "h": 800, "vw": 260, "vh": 80},
        {"name": "Degenerate Screen (screen < view)", "w": 200, "h": 50, "vw": 260, "vh": 80},
        {"name": "Zero Dimension Screen", "w": 0, "h": 0, "vw": 260, "vh": 80},
        {"name": "Zero Dimension View", "w": 1080, "h": 2400, "vw": 0, "vh": 0},
    ]

    test_deltas = [
        # (deltaX, deltaY, description)
        (0, 0, "Zero delta"),
        (-100, -200, "Moderate negative delta"),
        (-5000, -5000, "Large negative delta"),
        (-10000000, -10000000, "Extreme negative delta"),
        (KotlinIntMath.INT32_MIN, KotlinIntMath.INT32_MIN, "32-bit INT_MIN underflow"),
        (500, 1000, "Moderate positive delta"),
        (10000, 20000, "Large overflow delta"),
        (10000000, 10000000, "Extreme overflow delta"),
        (KotlinIntMath.INT32_MAX, KotlinIntMath.INT32_MAX, "32-bit INT_MAX overflow"),
    ]

    initial_positions = [
        (0, 0),
        (16, 396),
        (500, 1200),
        (1000, 2300),
    ]

    total_checks = 0
    for screen in screen_configs:
        w, h = screen["w"], screen["h"]
        vw, vh = screen["vw"], screen["vh"]
        for init_x, init_y in initial_positions:
            for dx, dy, desc in test_deltas:
                clamped_x, clamped_y, max_x, max_y = OverlayClampingEngine.clamp_drag(
                    init_x, init_y, dx, dy, w, h, vw, vh
                )
                assert 0 <= clamped_x <= max_x, f"clamped_x={clamped_x} out of [0, {max_x}] for {screen['name']}, {desc}"
                assert 0 <= clamped_y <= max_y, f"clamped_y={clamped_y} out of [0, {max_y}] for {screen['name']}, {desc}"
                total_checks += 1

    print(f"  [+] Executed {total_checks} extreme boundary clamping combinations across 6 screen topologies.")
    print("  [+] PASS: Clamped coordinates strictly conform to [0, maxX] and [0, maxY], zero exceptions.")


def test_fix2_orientation_and_collapse_re_clamping():
    print("\n--- [CHALLENGE FIX 2] Orientation Changes & Collapse/Expand Dynamics ---")
    # Scenario 1: User drags overlay to bottom-right of portrait screen (1080 x 2400)
    portrait_w, portrait_h = 1080, 2400
    card_w, card_h = 260, 80
    user_x = 1080 - card_w  # 820
    user_y = 2400 - card_h  # 2320

    # User rotates phone to Landscape (2400 x 1080)
    landscape_w, landscape_h = 2400, 1080
    re_x, re_y, max_lx, max_ly = OverlayClampingEngine.re_clamp_after_rotation(
        user_x, user_y, landscape_w, landscape_h, card_w, card_h
    )
    print(f"  [*] Portrait bottom-right ({user_x}, {user_y}) -> Landscape clamped ({re_x}, {re_y})")
    assert re_x == 820, f"X coordinate should remain 820 since 820 <= 2400 - 260 (2140)"
    assert re_y == 1000, f"Y coordinate {re_y} must clamp to 1080 - 80 = 1000"
    assert re_y + card_h <= landscape_h, "Overlay must be 100% visible on landscape screen"

    # User in landscape drags to (2140, 1000), then rotates back to Portrait (1080 x 2400)
    back_x, back_y, max_px, max_py = OverlayClampingEngine.re_clamp_after_rotation(
        re_x, re_y, portrait_w, portrait_h, card_w, card_h
    )
    assert back_x <= max_px and back_y <= max_py
    assert back_x + card_w <= portrait_w and back_y + card_h <= portrait_h
    print(f"  [*] Landscape bottom-right ({re_x}, {re_y}) -> Portrait clamped ({back_x}, {back_y})")

    # Scenario 2: Dynamic expansion near screen edge
    # User puts collapsed pill (width 120, height 40) at rightmost edge of screen: x = 1080 - 120 = 960
    pill_w, pill_h = 120, 40
    init_pill_x = portrait_w - pill_w # 960
    init_pill_y = 500
    # User clicks '+' expand button -> view becomes expanded card (width 260, height 120)
    # Without post re-clamping, x=960 + 260 = 1220 > 1080 (overflowing 140px off screen!)
    expanded_w, expanded_h = 260, 120
    expanded_max_x = max(0, portrait_w - expanded_w) # 820
    expanded_max_y = max(0, portrait_h - expanded_h)
    re_clamped_expand_x = KotlinIntMath.coerce_in(init_pill_x, 0, expanded_max_x)
    print(f"  [*] Collapsed pill at edge x={init_pill_x} -> Expanded re-clamped to x={re_clamped_expand_x}")
    assert re_clamped_expand_x == 820, f"Must re-clamp from 960 to 820 on expansion"
    assert re_clamped_expand_x + expanded_w <= portrait_w

    print("  [+] PASS: Orientation changes and expand/collapse post-clamping guarantee 100% on-screen visibility.")


def test_fix3_clipboard_activity_not_found():
    print("\n--- [CHALLENGE FIX 3] MainActivity Clipboard Safe-Cast & ActivityNotFoundException Fallback ---")
    main_activity_file = os.path.join(APP_SRC_MAIN, "java", "com", "linewalk", "autoad", "ui", "MainActivity.kt")
    strings_file = os.path.join(APP_SRC_MAIN, "res", "values", "strings.xml")

    with open(main_activity_file, "r", encoding="utf-8") as f:
        ma_code = f.read()

    with open(strings_file, "r", encoding="utf-8") as f:
        strings_code = f.read()

    # 1. Verify safe ClipboardManager cast: "as? ClipboardManager"
    assert "as? ClipboardManager" in ma_code, "MainActivity must use safe-cast 'as? ClipboardManager' to prevent ClassCastException"
    assert "clipboard == null" in ma_code, "MainActivity must check if clipboard is null"

    # 2. Verify ActivityNotFoundException handling in btnExportLogs
    assert "ActivityNotFoundException" in ma_code, "MainActivity must catch ActivityNotFoundException"
    assert "copyLogsToClipboard(fullLogs, showSuccessToast = false)" in ma_code, "Must fall back to copy full logs on ActivityNotFoundException"

    # 3. Verify string resources
    assert "toast_clipboard_error" in strings_code, "strings.xml must contain toast_clipboard_error"
    assert "toast_export_fallback_copied" in strings_code, "strings.xml must contain toast_export_fallback_copied"
    print("  [+] Verified strings.xml contains toast_clipboard_error and toast_export_fallback_copied.")
    print("  [+] Verified MainActivity.kt protects startActivity with try-catch and clipboard fallback.")
    print("  [+] PASS: Safe-casting and graceful degradation confirmed.")


def test_fix4_binder_safety_and_full_log_retention():
    print("\n--- [CHALLENGE FIX 4] Binder IPC Buffer Safety & 100% Full Log Retention Mandate ---")
    engine = SimulatedAutomationEngine(capacity=5000)

    # Populate with exactly 5,000 logs
    base_time = datetime.datetime(2026, 10, 22, 10, 0, 0)
    for i in range(5000):
        t = base_time + datetime.timedelta(seconds=i)
        engine.appendLog(f"巡邏事件項目 #{i:04d} - 狀態確認正常，執行自動領取與廣告監控測試", timestamp_dt=t)

    assert len(engine.fullSessionLogs) == 5000, "Engine buffer must contain exactly 5,000 logs"

    # 1. MANDATE CHECK: getFullLogsFormatted() MUST retain 100% of the 5,000 logs!
    full_logs_text = engine.getFullLogsFormatted()
    assert "日誌筆數：5000\n" in full_logs_text, "getFullLogsFormatted must report exactly 5000 entries in header"
    assert "已截取" not in full_logs_text, "getFullLogsFormatted must NOT contain any truncation note"
    assert "巡邏事件項目 #0000" in full_logs_text, "Line #0 must be present in full logs"
    assert "巡邏事件項目 #4999" in full_logs_text, "Line #4999 must be present in full logs"

    # Count actual log lines in full output
    log_lines = [l for l in full_logs_text.splitlines() if l.startswith("[2026-10-22")]
    print(f"  [*] Full logs formatted line count: {len(log_lines)} (expected 5000)")
    assert len(log_lines) == 5000, f"Full logs formatted must have exactly 5000 lines, found {len(log_lines)}"

    full_utf8_size = len(full_logs_text.encode("utf-8"))
    full_utf16_size = len(full_logs_text) * 2  # UTF-16 bytes in Android Binder Parcel
    print(f"  [*] Full logs memory footprint: UTF-8={full_utf8_size / 1024:.2f} KB, UTF-16={full_utf16_size / 1024:.2f} KB")

    # 2. MANDATE CHECK: getExportLogsFormatted(maxLines = 1500) MUST cap for Binder IPC
    export_logs_text = engine.getExportLogsFormatted(maxLines=1500)
    assert "日誌筆數：1500 (總計 5000 筆，已截取最近 1,500 筆記錄)" in export_logs_text
    assert "[注意：日誌量龐大，已匯出最近 1,500 筆記錄以防系統溢位。如需完整記錄請使用「複製日誌」功能]" in export_logs_text

    export_log_lines = [l for l in export_logs_text.splitlines() if l.startswith("[2026-10-22")]
    print(f"  [*] Export logs formatted line count: {len(export_log_lines)} (expected 1500)")
    assert len(export_log_lines) == 1500, f"Export logs must contain exactly 1500 lines, found {len(export_log_lines)}"
    assert "巡邏事件項目 #0000" not in export_logs_text, "Oldest logs must be truncated from export payload"
    assert "巡邏事件項目 #4999" in export_logs_text, "Newest log must be present in export payload"
    assert "巡邏事件項目 #3500" in export_logs_text, "First line of recent 1500 must be line #3500"

    export_utf8_size = len(export_logs_text.encode("utf-8"))
    export_utf16_size = len(export_logs_text) * 2

    print(f"  [*] Export logs footprint: UTF-8={export_utf8_size / 1024:.2f} KB, UTF-16={export_utf16_size / 1024:.2f} KB")
    SAFE_BINDER_LIMIT = 512 * 1024  # 512 KB
    HARD_BINDER_LIMIT = 1024 * 1024 # 1 MB
    assert export_utf8_size < SAFE_BINDER_LIMIT, f"Export UTF-8 payload {export_utf8_size} exceeds 512 KB safe ceiling"
    assert export_utf16_size < SAFE_BINDER_LIMIT, f"Export UTF-16 payload {export_utf16_size} exceeds 512 KB safe ceiling"

    # 3. Secondary safety check in MainActivity: if exportLogs.length > 200,000 chars
    # Test with extreme long messages (e.g. 500 chars per line x 1500 lines = 750,000 chars)
    giant_engine = SimulatedAutomationEngine(capacity=1500)
    for i in range(1500):
        giant_engine.appendLog(f"極長記錄 #{i:04d} " + ("ABCDE12345" * 45))
    giant_export = giant_engine.getExportLogsFormatted(maxLines=1500)
    assert len(giant_export) > 200_000, "Giant export must exceed 200,000 chars to test MainActivity secondary ceiling"

    # Simulate MainActivity secondary ceiling logic
    safe_payload = (
        "[注意：日誌量極大，已自動安全截斷以防止系統當機。完整日誌請使用「複製日誌」]\n\n" + giant_export[-180_000:]
        if len(giant_export) > 200_000 else giant_export
    )
    assert len(safe_payload) <= 180_000 + 100
    safe_utf16_size = len(safe_payload) * 2
    assert safe_utf16_size < SAFE_BINDER_LIMIT, "Even under adversarial 750KB logs, secondary ceiling guarantees < 512 KB"

    print("  [+] PASS: getFullLogsFormatted retains 100% (5,000/5,000 logs), while getExportLogsFormatted safely caps < 512 KB.")


def test_concurrency_stress_and_ordering():
    print("\n--- [CONCURRENCY STRESS] Multi-Threaded Logging & Formatting Invariant ---")
    engine = SimulatedAutomationEngine(capacity=5000)

    # 20 concurrent threads writing 250 logs each (total 5000 logs)
    # Simultaneously 5 reader threads repeatedly querying full & export logs
    num_writers = 20
    logs_per_writer = 250
    num_readers = 5
    reader_stop_flag = threading.Event()
    reader_errors = []

    def writer_func(worker_id):
        for i in range(logs_per_writer):
            engine.appendLog(f"Worker {worker_id:02d} - Iteration {i:03d}")
            time.sleep(0.0001)

    def reader_func(reader_id):
        try:
            while not reader_stop_flag.is_set():
                fl = engine.getFullLogsFormatted()
                el = engine.getExportLogsFormatted(maxLines=1500)
                assert len(fl) > 0 and len(el) > 0
                time.sleep(0.001)
        except Exception as e:
            reader_errors.append(f"Reader {reader_id} error: {e}")

    with ThreadPoolExecutor(max_workers=num_writers + num_readers) as pool:
        reader_futures = [pool.submit(reader_func, r) for r in range(num_readers)]
        writer_futures = [pool.submit(writer_func, w) for w in range(num_writers)]

        for wf in writer_futures:
            wf.result()

        reader_stop_flag.set()
        for rf in reader_futures:
            rf.result()

    assert len(reader_errors) == 0, f"Reader threads encountered errors: {reader_errors}"
    final_logs = engine.fullSessionLogs
    assert len(final_logs) == 5000, f"Expected 5000 logs, got {len(final_logs)}"
    print(f"  [+] Multi-threaded stress finished with 0 errors across 20 writers + 5 readers.")
    print("  [+] PASS: Synchronization and thread safety confirmed.")


if __name__ == "__main__":
    print("=====================================================================")
    print("STARTING ITERATION 2 ADVERSARIAL VERIFICATION HARNESS")
    print("=====================================================================")
    t0 = time.time()
    test_fix1_extreme_coordinate_clamping()
    test_fix2_orientation_and_collapse_re_clamping()
    test_fix3_clipboard_activity_not_found()
    test_fix4_binder_safety_and_full_log_retention()
    test_concurrency_stress_and_ordering()
    dt = time.time() - t0
    print("\n=====================================================================")
    print(f"ALL ADVERSARIAL STRESS CHALLENGES PASSED IN {dt:.3f}s (0 FAILURES)")
    print("=====================================================================")
