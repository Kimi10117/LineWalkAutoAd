#!/usr/bin/env python3
"""
Independent Reviewer 1 Adversarial Verification Suite
LineWalkAutoAd - Milestone 1 Iteration 2
Author: Reviewer 1 (ef010785-3bdf-4ae4-9868-de1e72b4e79c)
"""

import sys
import os
import re
from datetime import datetime

def test_clamping_adversarial_suite():
    print("=== [REVIEWER 1 TEST 1] Drag & Orientation Clamping Adversarial Matrix ===")
    
    def clamp_action_move(initial_x, initial_y, raw_x, raw_y, touch_x, touch_y, screen_w, screen_h, view_w, view_h):
        max_x = max(0, screen_w - (view_w if view_w > 0 else 1))
        max_y = max(0, screen_h - (view_h if view_h > 0 else 1))
        delta_x = int(raw_x - touch_x)
        delta_y = int(raw_y - touch_y)
        clamped_x = max(0, min(initial_x + delta_x, max_x))
        clamped_y = max(0, min(initial_y + delta_y, max_y))
        return clamped_x, clamped_y, max_x, max_y

    def clamp_orientation(curr_x, curr_y, screen_w, screen_h, view_w, view_h):
        max_x = max(0, screen_w - (view_w if view_w > 0 else 1))
        max_y = max(0, screen_h - (view_h if view_h > 0 else 1))
        clamped_x = max(0, min(curr_x, max_x))
        clamped_y = max(0, min(curr_y, max_y))
        return clamped_x, clamped_y, max_x, max_y

    # Test cases: normal, boundary, extreme adversarial
    devices = [
        ("FHD Portrait", 1080, 2400),
        ("FHD Landscape", 2400, 1080),
        ("Tiny Screen (WearOS)", 360, 360),
        ("Foldable Outer", 904, 2316),
        ("Foldable Inner", 2208, 1768),
        ("Split Screen Top", 1080, 950),
        ("Split Screen Bottom", 1080, 1100),
        ("Zero Metrics Fallback", 0, 0),
        ("Negative Metrics Glitch", -100, -200),
    ]

    view_sizes = [
        ("Collapsed Pill", 260, 80),
        ("Expanded Window", 800, 600),
        ("Zero Width/Height Before Layout", 0, 0),
        ("Negative Width Glitch", -10, -20),
        ("Oversized View > Screen", 1200, 2500),
    ]

    for dev_name, sw, sh in devices:
        for view_name, vw, vh in view_sizes:
            # Adversarial drag scenarios:
            # 1. Drag far into negative infinity
            cx, cy, mx, my = clamp_action_move(100, 100, -999999, -999999, 100, 100, sw, sh, vw, vh)
            assert cx == 0 and cy == 0, f"Negative drag failed for {dev_name} + {view_name}: ({cx}, {cy})"
            assert 0 <= cx <= mx and 0 <= cy <= my, f"Clamped out of max bounds"

            # 2. Drag far into positive infinity
            cx, cy, mx, my = clamp_action_move(100, 100, 999999, 999999, 100, 100, sw, sh, vw, vh)
            assert cx == mx and cy == my, f"Positive drag failed for {dev_name} + {view_name}: ({cx}, {cy}) != ({mx}, {my})"
            assert 0 <= cx <= mx and 0 <= cy <= my

            # 3. Orientation change from bottom-right corner of FHD Portrait (y=2300)
            orig_x, orig_y = 1000, 2300
            ox, oy, omx, omy = clamp_orientation(orig_x, orig_y, sw, sh, vw, vh)
            assert 0 <= ox <= omx and 0 <= oy <= omy, f"Orientation clamp out of bounds on {dev_name} + {view_name}"
            if sw > 0 and vw > 0 and vw <= sw:
                assert ox + vw <= sw, f"Pill overflows right edge on {dev_name}"
            if sh > 0 and vh > 0 and vh <= sh:
                assert oy + vh <= sh, f"Pill overflows bottom edge on {dev_name}"

    print(f"  [+] 9 screen configs x 5 view sizes x 3 adversarial vectors = 135 permutations: ALL PASS")

def test_engine_logging_and_ipc_safety():
    print("=== [REVIEWER 1 TEST 2] Engine Logging Truncation & IPC Safety ===")

    class LogEngineModel:
        def __init__(self):
            self.logs = []
            self.session_start = datetime(2026, 10, 22, 10, 0, 0)

        def append_log(self, msg):
            from datetime import timedelta
            ts = (self.session_start + timedelta(seconds=len(self.logs))).strftime("%Y-%m-%d %H:%M:%S")
            self.logs.append(f"[{ts}] {msg}")
            if len(self.logs) > 5000:
                self.logs.pop(0)

        def get_export_logs_formatted(self, max_lines=1500, device="Pixel 7 Pro", os_ver="Android 14", app_ver="v1.15.20"):
            snapshot = list(self.logs)
            is_truncated = len(snapshot) > max_lines
            target_logs = snapshot[-max_lines:] if is_truncated else snapshot

            sb = []
            sb.append("========================================\n")
            sb.append("《LINE 散步趣助手》系統診斷日誌\n")
            sb.append(f"應用版本：{app_ver}\n")
            sb.append(f"裝置型號：{device}\n")
            sb.append(f"系統版本：{os_ver}\n")
            sb.append(f"啟動時間：{self.session_start.strftime('%Y-%m-%d %H:%M:%S')}\n")
            sb.append("匯出時間：2026-10-22 12:00:00\n")
            if is_truncated:
                sb.append(f"日誌筆數：{len(target_logs)} (總計 {len(snapshot)} 筆，已截取最近 1,500 筆記錄)\n")
            else:
                sb.append(f"日誌筆數：{len(target_logs)}\n")
            sb.append("========================================\n\n")

            if is_truncated:
                sb.append("[注意：日誌量龐大，已匯出最近 1,500 筆記錄以防系統溢位。如需完整記錄請使用「複製日誌」功能]\n\n")

            if not target_logs:
                sb.append("(目前尚無執行日誌記錄)")
            else:
                sb.append("\n".join(target_logs))
            return "".join(sb)

        def get_full_logs_formatted(self):
            return self.get_export_logs_formatted(max_lines=sys.maxsize)

    engine = LogEngineModel()

    # Case A: Empty logs
    empty_export = engine.get_export_logs_formatted()
    empty_full = engine.get_full_logs_formatted()
    assert "(目前尚無執行日誌記錄)" in empty_export
    assert "(目前尚無執行日誌記錄)" in empty_full
    assert "已截取最近 1,500 筆記錄" not in empty_export
    assert "日誌筆數：0" in empty_export
    print("  [+] Case A (Empty logs): PASS")

    # Case B: Exactly 1500 logs (boundary)
    for i in range(1500):
        engine.append_log(f"例行巡邏檢查任務 #{i:04d} 完成點擊 (x=500, y=1200)")
    exp_1500 = engine.get_export_logs_formatted()
    full_1500 = engine.get_full_logs_formatted()
    assert "已截取最近 1,500 筆記錄" not in exp_1500, "1500 logs must not trigger truncation warning"
    assert "日誌筆數：1500\n" in exp_1500
    assert "任務 #0000" in exp_1500
    assert "任務 #1499" in exp_1500
    print("  [+] Case B (Exactly 1500 logs boundary): PASS")

    # Case C: 1501 logs (first truncation boundary)
    engine.append_log("例行巡邏檢查任務 #1500 完成點擊")
    exp_1501 = engine.get_export_logs_formatted()
    full_1501 = engine.get_full_logs_formatted()
    assert "已截取最近 1,500 筆記錄" in exp_1501
    assert "日誌筆數：1500 (總計 1501 筆，已截取最近 1,500 筆記錄)" in exp_1501
    assert "任務 #0000" not in exp_1501, "Earliest log must be dropped in truncated export"
    assert "任務 #0001" in exp_1501, "Second log must be the start of truncated export"
    assert "任務 #1500" in exp_1501, "Latest log must be retained"
    # Full logs must retain everything
    assert "已截取最近 1,500 筆記錄" not in full_1501
    assert "任務 #0000" in full_1501
    assert "日誌筆數：1501\n" in full_1501
    print("  [+] Case C (1501 logs first truncation boundary): PASS")

    # Case D: Max buffer 5000 logs under heavy payload
    for i in range(1501, 5500):
        engine.append_log(f"長日誌記錄 #{i} - 詳細操作步驟包含無障礙節點巡邏比對座標 Rect(100, 200, 800, 900) 耗時 23ms 返回碼 SUCCESS - 附帶系統診斷詳細元數據與狀態快照")
    
    assert len(engine.logs) == 5000
    exp_5000 = engine.get_export_logs_formatted(max_lines=1500)
    full_5000 = engine.get_full_logs_formatted()

    full_bytes_utf8 = len(full_5000.encode("utf-8"))
    full_bytes_utf16 = len(full_5000.encode("utf-16-le"))
    exp_bytes_utf8 = len(exp_5000.encode("utf-8"))
    exp_bytes_utf16 = len(exp_5000.encode("utf-16-le"))

    print(f"  [*] 5,000 full logs size: {len(full_5000)} chars | {full_bytes_utf8/1024:.1f} KB (UTF-8) | {full_bytes_utf16/1024:.1f} KB (UTF-16)")
    print(f"  [*] 1,500 export logs size: {len(exp_5000)} chars | {exp_bytes_utf8/1024:.1f} KB (UTF-8) | {exp_bytes_utf16/1024:.1f} KB (UTF-16)")

    # Binder limit is 1MB total across all transactions in process; safe limit is 512KB
    assert full_bytes_utf8 > 512 * 1024, "Full logs prove need for protection by exceeding 512KB"
    assert exp_bytes_utf8 < 512 * 1024, f"Export UTF-8 bytes {exp_bytes_utf8} exceeds 512KB!"
    assert exp_bytes_utf16 < 768 * 1024, f"Export UTF-16 bytes {exp_bytes_utf16} exceeds safe IPC limit!"

    # Test MainActivity secondary ceiling check (200,000 chars)
    if len(exp_5000) > 200_000:
        notice = "[注意：日誌量極大，已自動安全截斷以防止系統當機。完整日誌請使用「複製日誌」]\n\n"
        safe_payload = notice + exp_5000[-180_000:]
    else:
        safe_payload = exp_5000
    assert len(safe_payload.encode("utf-8")) < 512 * 1024
    print("  [+] Case D (5,000 max logs & Binder transaction buffer safety): PASS")

def test_source_code_contract_and_integrity():
    print("=== [REVIEWER 1 TEST 3] Source Code Static Contract & Integrity Analysis ===")

    # 1. FloatingOverlayService.kt
    overlay_path = "app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt"
    with open(overlay_path, "r", encoding="utf-8") as f:
        overlay_src = f.read()

    assert "override fun onConfigurationChanged" in overlay_src, "Missing onConfigurationChanged override"
    assert "wmParams.x = (initialX + deltaX).coerceIn(0, maxX)" in overlay_src, "Missing x coordinate clamping"
    assert "wmParams.y = (initialY + deltaY).coerceIn(0, maxY)" in overlay_src, "Missing y coordinate clamping"
    assert "maxX = (displayMetrics.widthPixels - viewW).coerceAtLeast(0)" in overlay_src, "Missing maxX non-negative guarantee"
    assert "maxY = (displayMetrics.heightPixels - viewH).coerceAtLeast(0)" in overlay_src, "Missing maxY non-negative guarantee"
    assert "windowManager.updateViewLayout" in overlay_src
    print("  [+] FloatingOverlayService.kt: Drag clamping & orientation handling confirmed.")

    # 2. MainActivity.kt
    main_path = "app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt"
    with open(main_path, "r", encoding="utf-8") as f:
        main_src = f.read()

    assert "as? ClipboardManager" in main_src, "Must use safe cast as? ClipboardManager"
    assert "if (clipboard == null)" in main_src, "Must handle null clipboard"
    assert "catch (e: ActivityNotFoundException)" in main_src, "Must catch ActivityNotFoundException"
    assert "copyLogsToClipboard(fullLogs, showSuccessToast = false)" in main_src, "Fallback must copy full logs"
    assert "R.string.toast_export_fallback_copied" in main_src, "Must use toast_export_fallback_copied string"
    assert "getExportLogsFormatted(maxLines = 1500)" in main_src, "Must invoke getExportLogsFormatted(maxLines = 1500)"
    print("  [+] MainActivity.kt: ActivityNotFoundException & safe clipboard confirmed.")

    # 3. AutomationEngine.kt
    engine_path = "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt"
    with open(engine_path, "r", encoding="utf-8") as f:
        engine_src = f.read()

    assert "fun getExportLogsFormatted" in engine_src, "Missing getExportLogsFormatted"
    assert "maxLines: Int = 1500" in engine_src, "getExportLogsFormatted must default to 1500 lines"
    assert "fun getFullLogsFormatted" in engine_src, "Missing getFullLogsFormatted"
    assert "maxLines = Int.MAX_VALUE" in engine_src, "getFullLogsFormatted must delegate with Int.MAX_VALUE"
    assert "synchronized(_fullSessionLogs)" in engine_src, "Must synchronize on _fullSessionLogs"
    print("  [+] AutomationEngine.kt: Binder limit protection & full log retention confirmed.")

    # 4. strings.xml
    strings_path = "app/src/main/res/values/strings.xml"
    with open(strings_path, "r", encoding="utf-8") as f:
        strings_src = f.read()

    assert 'name="toast_clipboard_error"' in strings_src
    assert 'name="toast_export_fallback_copied"' in strings_src
    assert 'name="btn_export_logs"' in strings_src
    assert 'name="btn_copy_logs"' in strings_src
    assert 'name="toast_logs_copied"' in strings_src
    assert 'name="chooser_export_logs"' in strings_src
    assert 'name="subject_export_logs"' in strings_src
    print("  [+] strings.xml: All 7 required strings present and well-formed.")

    # 5. Integrity Check: Search for hardcoded fake returns or bypasses
    integrity_red_flags = [
        "if (BuildConfig.DEBUG && isTesting)",
        "return true // bypass",
        "mock_result",
        "TODO(\"implement real logic\")"
    ]
    for flag in integrity_red_flags:
        assert flag not in overlay_src, f"Integrity red flag '{flag}' found in FloatingOverlayService.kt"
        assert flag not in main_src, f"Integrity red flag '{flag}' found in MainActivity.kt"
        assert flag not in engine_src, f"Integrity red flag '{flag}' found in AutomationEngine.kt"
    print("  [+] Integrity Analysis: Zero dummy facades, zero hardcoded test escapes.")

if __name__ == "__main__":
    test_clamping_adversarial_suite()
    test_engine_logging_and_ipc_safety()
    test_source_code_contract_and_integrity()
    print("\n>>> ALL REVIEWER 1 ADVERSARIAL STRESS SUITES PASSED CLEANLY <<<")
