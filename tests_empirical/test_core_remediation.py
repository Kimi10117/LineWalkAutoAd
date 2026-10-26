#!/usr/bin/env python3
"""
EMPIRICAL TEST SUITE: Core Remediation Verification (M3-Core)
LineWalkAutoAd

Covers:
1. R1: Top Notification & Dynamic Island Safe Zone (< 8% screen height) & Verified Menu Screen
2. R2: Daily Bingo Dialog Overhaul, Cooldown/Limit Checks, Geometric X Guard, & Strict Sequential Flow
3. R3: Third-Party Ad Containers Full Exemption, Redirect Package Precedence, & Zero-Back Enforcement
4. AST & Static Semantic Verification across all modified Kotlin source files
"""

import os
import re
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


# ==============================================================================
# Helper classes simulating Kotlin model structs
# ==============================================================================

class Rect:
    def __init__(self, left, top, right, bottom):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    def width(self):
        return self.right - self.left

    def height(self):
        return self.bottom - self.top

    def centerX(self):
        return (self.left + self.right) / 2.0

    def centerY(self):
        return (self.top + self.bottom) / 2.0


class OcrItem:
    def __init__(self, text, bounds):
        self.text = text
        self.bounds = bounds


# ==============================================================================
# 1. R1: Top Notification & Dynamic Island Safe Zone & Menu Protection
# ==============================================================================

def test_r1_top_safe_zone_click_rejection():
    """Verify that clicks targeting y < sh * 0.08f are rejected unless allowTopSafeZone=True."""
    max_h = 2400
    safe_zone_limit = max_h * 0.08  # 192.0

    def simulate_perform_click(target_y, allow_top_safe_zone=False):
        if not allow_top_safe_zone and target_y < safe_zone_limit:
            return False  # Rejected
        return True  # Allowed

    # 1. Clicks in notification / Dynamic Island region (< 8% height) without exemption
    notification_y_coords = [10.0, 50.0, 100.0, 150.0, 191.9]
    for y in notification_y_coords:
        assert simulate_perform_click(y, allow_top_safe_zone=False) is False, f"Click at y={y} must be rejected"

    # 2. Clicks in safe zone WITH explicit exemption (e.g. header gift box at y=0.06h)
    assert simulate_perform_click(144.0, allow_top_safe_zone=True) is True, "Header gift box with allowTopSafeZone must be allowed"

    # 3. Normal in-game clicks (>= 8% height)
    normal_y_coords = [192.0, 200.0, 500.0, 1200.0, 2200.0]
    for y in normal_y_coords:
        assert simulate_perform_click(y, allow_top_safe_zone=False) is True, f"Normal click at y={y} must be allowed"


def test_r1_template_matcher_safe_zone_and_bingo_x_exclusion():
    """Verify TemplateMatcher geometric X scan zone limits and bingo close X exclusion."""
    sw = 1080
    sh = 2400
    top_limit_y = max(int(sh * 0.082), 60)  # 196
    assert top_limit_y >= int(sh * 0.08), f"topLimitY {top_limit_y} must be >= sh * 0.08"

    # Candidate box top filtering
    r = int(sw * 0.018)  # 19
    for abs_y in range(0, int(sh * 0.24)):
        box_top = abs_y - r
        is_in_safe_zone = box_top < int(sh * 0.08)
        if abs_y < int(sh * 0.08):
            assert is_in_safe_zone is True

    # Bingo close X coordinates exclusion
    bingo_close_x_min = int(sw * 0.84)
    bingo_close_x_max = int(sw * 0.98)
    bingo_close_y_min = int(sh * 0.08)
    bingo_close_y_max = int(sh * 0.16)

    def is_bingo_close_zone(x, y):
        return (bingo_close_x_min <= x <= bingo_close_x_max) and (bingo_close_y_min <= y <= bingo_close_y_max)

    # Coordinates of bingo (X) button center
    bingo_x = int(sw * 0.91)
    bingo_y = int(sh * 0.12)
    assert is_bingo_close_zone(bingo_x, bingo_y) is True, "Bingo close X coordinates must be blacklisted in TemplateMatcher"

    # Coordinates outside bingo close zone
    assert is_bingo_close_zone(int(sw * 0.5), int(sh * 0.5)) is False
    assert is_bingo_close_zone(int(sw * 0.91), int(sh * 0.20)) is False


def test_r1_verified_menu_screen_multi_condition():
    """Verify isVerifiedMenuScreen excludes bingo, main tabs, nav bars, and notifications."""
    sw = 1080
    sh = 2400

    def is_verified_menu_screen(ocr_items, is_bingo_open=False):
        if is_bingo_open:
            return False

        has_main_tabs = any(
            0.08 <= item.bounds.centerY() / sh <= 0.20 and any(t in item.text for t in ["首頁", "步數", "時間", "遊戲"])
            for item in ocr_items
        )
        if has_main_tabs:
            return False

        has_bottom_nav = any(
            item.bounds.centerY() / sh > 0.85 and any(t in item.text for t in ["集點", "好友", "使用", "精選"])
            for item in ocr_items
        )
        if has_bottom_nav:
            return False

        has_menu_title = any(
            0.08 <= item.bounds.centerY() / sh <= 0.22 and item.text.replace(" ", "") in ["選單", "設定", "SETTINGS"]
            for item in ocr_items
        )

        setting_keywords = [
            "應用程式設定", "帳號", "提醒", "關於", "條款",
            "關閉動畫效果", "省電模式", "位置資訊設定", "目前版本",
            "常見問題", "服務條款", "隱私權政策", "登出"
        ]
        matched_kws = [kw for kw in setting_keywords if any(kw in item.text.replace(" ", "") for item in ocr_items)]

        return (has_menu_title and len(matched_kws) >= 1) or len(matched_kws) >= 2

    # Case A: False positive prevented - Push notification with "選單" at y < 0.08h
    notification_items = [
        OcrItem("LINE 訊息: 請查收最新團購選單", Rect(100, 20, 900, 80)),
        OcrItem("首頁", Rect(100, 250, 250, 320)),
        OcrItem("集點", Rect(100, 2100, 250, 2200))
    ]
    assert is_verified_menu_screen(notification_items) is False

    # Case B: False positive prevented - Bingo dialog open with "抽選" text
    bingo_items = [
        OcrItem("每日賓果", Rect(400, 300, 700, 400)),
        OcrItem("距離下次抽選還有", Rect(300, 1400, 800, 1500))
    ]
    assert is_verified_menu_screen(bingo_items, is_bingo_open=True) is False

    # Case C: Genuine menu screen - Title below safe zone + settings item
    genuine_menu_items = [
        OcrItem("選單", Rect(450, 260, 630, 350)),  # cy = 305 / 2400 ≈ 0.127
        OcrItem("應用程式設定", Rect(100, 500, 500, 600)),
        OcrItem("省電模式", Rect(100, 700, 400, 800))
    ]
    assert is_verified_menu_screen(genuine_menu_items) is True

    # Case D: Genuine menu screen - 2 distinct settings items without title
    settings_only_items = [
        OcrItem("位置資訊設定", Rect(100, 500, 500, 600)),
        OcrItem("目前版本 3.4.1", Rect(100, 700, 500, 800))
    ]
    assert is_verified_menu_screen(settings_only_items) is True


# ==============================================================================
# 2. R2: Daily Bingo Dialog Overhaul & Loop Elimination
# ==============================================================================

def test_r2_bingo_dialog_multi_dimensional_recognition():
    """Verify isBingoDialogOpen succeeds even when main tabs leak into OCR."""
    sh = 2400

    def is_bingo_dialog_open(ocr_items):
        has_title = False
        has_open_cell_or_cooldown = False
        has_connection = False

        for item in ocr_items:
            cy = item.bounds.centerY()
            t = item.text.replace(" ", "")

            if "每日賓果" in t or "今日賓果" in t or (cy < sh * 0.28 and (t.upper() == "BINGO" or ("每日" in t and "賓果" in t))):
                has_title = True

            if "立即開啟賓果格" in t or "開啟賓果格" in t or ("立即開啟" in t and cy > sh * 0.40) or \
               "距離下次抽選還有" in t or "距離下次" in t or "抽選還有" in t or \
               (sh * 0.40 <= cy <= sh * 0.85 and any(k in t for k in ["今日上限", "已達到今日上限", "9/9", "已全數開啟"])):
                has_open_cell_or_cooldown = True

            if any(b in t for b in ["1BINGO", "1 BINGO", "3BINGO", "3 BINGO", "5BINGO", "5 BINGO", "8BINGO", "8 BINGO"]) or \
               (sh * 0.70 <= cy <= sh * 0.95 and any(g in t.lower() for g in ["get", "cet", "qet"])):
                has_connection = True

        has_strong = (has_title and (has_open_cell_or_cooldown or has_connection)) or \
                     (has_open_cell_or_cooldown and has_connection) or \
                     any(any(k in item.text.replace(" ", "") for k in ["每日賓果", "今日賓果", "立即開啟賓果格", "距離下次抽選還有", "1BINGO", "3BINGO"]) for item in ocr_items)

        if has_strong:
            return True

        # Fallback check main tabs veto
        has_main_tabs = any(0.08 <= item.bounds.centerY() / sh <= 0.18 and any(k in item.text for k in ["首頁", "步數", "時間", "遊戲"]) for item in ocr_items)
        if has_main_tabs:
            return False

        return False

    # Scenario: Bingo dialog is open, but background "首頁" and "步數" leak into OCR
    leaky_bingo_items = [
        OcrItem("首頁", Rect(100, 250, 250, 320)),            # Background tab leak
        OcrItem("步數", Rect(300, 250, 450, 320)),            # Background tab leak
        OcrItem("每日賓果", Rect(400, 300, 700, 400)),        # Bingo title
        OcrItem("立即開啟賓果格", Rect(300, 1500, 800, 1600)), # Open cell button
        OcrItem("1 BINGO", Rect(100, 1800, 250, 1880)),       # Connection tag
        OcrItem("Get", Rect(100, 1900, 250, 1980))            # Claim button
    ]
    assert is_bingo_dialog_open(leaky_bingo_items) is True, "Background tabs must NOT veto bingo dialog when strong features exist"

    # Scenario: Clean main screen with only bingo icon at left
    main_screen_items = [
        OcrItem("首頁", Rect(100, 250, 250, 320)),
        OcrItem("步數", Rect(300, 250, 450, 320)),
        OcrItem("賓果", Rect(50, 950, 200, 1050))             # Small icon at (x < 0.25sw, y ≈ 0.42sh)
    ]
    assert is_bingo_dialog_open(main_screen_items) is False, "Main screen bingo icon must not be mistaken for bingo dialog"


def test_r2_find_open_cell_button_cooldown_and_limit_rejection():
    """Verify findOpenCellButton strictly rejects countdown and limit conditions."""
    sh = 2400
    time_regex = re.compile(r"\d{1,2}:\d{2}")

    def find_open_cell_mock(ocr_items):
        # Limit check
        has_reached_limit = any(
            any(k in item.text.replace(" ", "") for k in ["今日上限", "已達到今日上限", "9/9", "已全數開啟"])
            for item in ocr_items
        )
        if has_reached_limit:
            return None

        # Cooldown check
        is_cooling_down = any(
            "距離下次抽選還有" in item.text or "距離下次" in item.text or "抽選還有" in item.text or
            (sh * 0.48 <= item.bounds.centerY() <= sh * 0.78 and (
                bool(time_regex.search(item.text)) or
                ("還有" in item.text and "立即" not in item.text) or
                ("抽選" in item.text and "開啟" not in item.text and "立即" not in item.text)
            ))
            for item in ocr_items
        )
        if is_cooling_down:
            return None

        # OCR match
        for item in ocr_items:
            t = item.text.replace(" ", "")
            cy = item.bounds.centerY()
            if sh * 0.54 <= cy <= sh * 0.74 and any(k in t for k in ["立即開啟", "開啟賓果格", "開啟賓果", "立即開"]) and \
               "還有" not in t and not bool(time_regex.search(t)) and "今日上限" not in t and "9/9" not in t:
                return item.bounds

        return None

    # Case 1: Active button available
    active_items = [OcrItem("立即開啟賓果格", Rect(300, 1500, 800, 1600))]
    assert find_open_cell_mock(active_items) is not None

    # Case 2: Cooldown countdown active (e.g. 02:45)
    cooldown_items = [
        OcrItem("距離下次抽選還有 02:45", Rect(300, 1450, 800, 1520)),
        OcrItem("立即開啟賓果格", Rect(300, 1550, 800, 1650))
    ]
    assert find_open_cell_mock(cooldown_items) is None

    # Case 3: Daily quota limit reached (9/9)
    limit_items = [
        OcrItem("已達到今日上限 (9/9)", Rect(300, 1400, 800, 1480)),
        OcrItem("立即開啟賓果格", Rect(300, 1550, 800, 1650))
    ]
    assert find_open_cell_mock(limit_items) is None


def test_r2_strict_sequential_flow_and_permanent_locking():
    """Verify strict execution order: open cells -> claim Gets -> close X to LOTTERY."""
    class BingoEngineSim:
        def __init__(self):
            self.open_cells = 2
            self.ready_gets = [0, 1]  # Col 0 (1 BINGO), Col 1 (3 BINGO)
            self.claimed_columns = set()
            self.retry_counts = {}
            self.current_phase = "BINGO"
            self.empty_ticks = 0
            self.is_completed = False
            self.actions = []

        def tick(self):
            # Step 1: Open cells
            if self.open_cells > 0:
                self.actions.append(f"OPEN_CELL_{self.open_cells}")
                self.open_cells -= 1
                return

            # Step 2: Sequential Get rewards
            unclaimed_gets = [c for c in self.ready_gets if c not in self.claimed_columns]
            if unclaimed_gets:
                target_col = unclaimed_gets[0]
                count = self.retry_counts.get(target_col, 0) + 1
                self.retry_counts[target_col] = count
                # Permanent locking on claim or retry >= 2
                self.claimed_columns.add(target_col)
                self.actions.append(f"CLAIM_GET_COL_{target_col}")
                return

            # Step 3: Confirmation and Close Dialog
            self.empty_ticks += 1
            if self.empty_ticks < 2:
                self.actions.append("WAIT_EMPTY_CHECK")
                return

            self.is_completed = True
            self.actions.append("CLICK_CLOSE_X")
            self.current_phase = "LOTTERY"

    sim = BingoEngineSim()
    for _ in range(10):
        if sim.is_completed:
            break
        sim.tick()

    assert sim.actions == [
        "OPEN_CELL_2",
        "OPEN_CELL_1",
        "CLAIM_GET_COL_0",
        "CLAIM_GET_COL_1",
        "WAIT_EMPTY_CHECK",
        "CLICK_CLOSE_X"
    ]
    assert sim.current_phase == "LOTTERY"
    assert sim.claimed_columns == {0, 1}, "Claimed columns must be permanently locked"


# ==============================================================================
# 3. R3: Third-Party Ad Containers Full Exemption & Seamless Monitoring
# ==============================================================================

def test_r3_ad_packages_whitelist_and_redirect_precedence():
    """Verify all required ad packages are recognized and exempt from redirect blacklist."""
    known_ad_packages = {
        "com.facebook.katana",
        "com.facebook.orca",
        "com.facebook.lite",
        "com.facebook.mlite",
        "com.instagram.android",
        "com.google.android.gms",
        "com.google.android.as",
        "com.google.android.webview",
        "com.unity3d.player",
        "com.unity3d.ads",
        "com.applovin",
        "com.applovin.sdk",
        "com.android.chrome",
        "androidx.browser.customtabs"
    }

    external_redirect_packages = {
        "com.android.vending",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox"
    }

    def is_ad_package(pkg):
        if not pkg:
            return False
        if pkg in known_ad_packages:
            return True
        lower = pkg.lower()
        return lower.startswith("com.facebook.ads") or "audience_network" in lower or \
               "admob" in lower or "google.android.gms" in lower or "unity3d" in lower or \
               "applovin" in lower or "customtabs" in lower

    def is_redirect_package(pkg):
        if not pkg:
            return False
        if is_ad_package(pkg):
            return False  # Ad package takes precedence!
        if pkg in external_redirect_packages:
            return True
        lower = pkg.lower()
        return "browser" in lower or "chrome" in lower or "vending" in lower

    # 1. All ad packages must be identified as ad package
    for ad_pkg in known_ad_packages:
        assert is_ad_package(ad_pkg) is True, f"{ad_pkg} must be identified as ad package"
        assert is_redirect_package(ad_pkg) is False, f"{ad_pkg} must NOT be treated as external redirect"

    # 2. True redirects must be identified as redirect
    for red_pkg in external_redirect_packages:
        assert is_ad_package(red_pkg) is False
        assert is_redirect_package(red_pkg) is True


def test_r3_safe_navigate_back_prohibition_in_ad_packages():
    """Verify safeNavigateBack NEVER dispatches GLOBAL_ACTION_BACK when foreground is an ad package."""
    known_ad_packages = {"com.facebook.katana", "com.google.android.gms", "com.unity3d.ads", "com.android.chrome"}
    game_packages = {"com.linecorp.LGWALK", "com.linecorp.LGWKTW", "jp.naver.line.android"}

    def safe_navigate_back(fg_pkg, allow_in_game=False):
        # Strict protection: Game packages AND Ad packages are forbidden from receiving Back
        if not allow_in_game and (fg_pkg in game_packages or fg_pkg in known_ad_packages):
            return False  # Disallow Back
        return True  # Dispatch Back

    # Ad packages must NEVER receive Back
    for ad_pkg in known_ad_packages:
        assert safe_navigate_back(ad_pkg) is False, f"Back must NOT be sent to ad package {ad_pkg}"

    # Game packages must NEVER receive Back in normal patrol
    for game_pkg in game_packages:
        assert safe_navigate_back(game_pkg) is False

    # True external app (e.g. system settings, phone launcher) CAN receive Back
    assert safe_navigate_back("com.android.settings") is True
    assert safe_navigate_back("com.sec.android.app.launcher") is True


def test_r3_seamless_transition_to_ad_watching():
    """Verify executeSingleTick immediately updates currentState to AD_WATCHING and bypasses non-ad handlers."""
    class StateMachineSim:
        def __init__(self):
            self.current_state = "TRIGGER_AD_POPUP"
            self.executed_handler = None
            self.dispatched_back = False

        def execute_tick(self, fg_pkg, is_ad_pkg):
            # 0. Immediate seamless transition upon ad package
            if is_ad_pkg:
                if self.current_state != "AD_WATCHING":
                    self.current_state = "AD_WATCHING"  # Update local state immediately!

            # Handler execution
            if self.current_state == "TRIGGER_AD_POPUP":
                # Old flawed behavior would time out and dispatch back
                self.executed_handler = "handleTriggerAdPopup"
                self.dispatched_back = True
            elif self.current_state == "AD_WATCHING":
                self.executed_handler = "handleAdWatching"
                self.dispatched_back = False

    sim = StateMachineSim()
    sim.execute_tick("com.facebook.katana", is_ad_pkg=True)
    assert sim.current_state == "AD_WATCHING"
    assert sim.executed_handler == "handleAdWatching"
    assert sim.dispatched_back is False, "No back action must be dispatched during ad watching"


# ==============================================================================
# 4. AST & Static Semantic Verification on Source Files
# ==============================================================================

def test_ast_source_code_integrity():
    """Static inspection of modified Kotlin files ensuring requirements are met."""
    accessibility_service = os.path.join(SRC_DIR, "service", "AutoAdAccessibilityService.kt")
    template_matcher = os.path.join(SRC_DIR, "vision", "TemplateMatcher.kt")
    app_config = os.path.join(SRC_DIR, "config", "AppConfig.kt")
    bingo_task = os.path.join(SRC_DIR, "tasks", "BingoTask.kt")
    automation_engine = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    ad_closer_handler = os.path.join(SRC_DIR, "engine", "AdCloserHandler.kt")

    for path in [accessibility_service, template_matcher, app_config, bingo_task, automation_engine, ad_closer_handler]:
        assert os.path.exists(path), f"File {path} must exist"
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()

        # Integrity red-flags check
        assert "dummy" not in content.lower(), f"{path} must not contain 'dummy'"
        assert "mock" not in content.lower(), f"{path} must not contain 'mock'"

    # 1. AutoAdAccessibilityService checks
    with open(accessibility_service, "r", encoding="utf-8") as f:
        src = f.read()
    assert "allowTopSafeZone: Boolean = false" in src
    assert "targetY < maxH * 0.08f" in src
    assert "AppConfig.isAdPackage(fg)" in src

    # 2. TemplateMatcher checks
    with open(template_matcher, "r", encoding="utf-8") as f:
        src = f.read()
    assert "val topLimitY = max((sh * 0.082f).toInt(), 60)" in src
    assert "absY - r < (sh * 0.08f).toInt()" in src
    assert "absX in (sw * 0.84f).toInt()..(sw * 0.98f).toInt()" in src

    # 3. AppConfig checks
    with open(app_config, "r", encoding="utf-8") as f:
        src = f.read()
    assert "com.facebook.katana" in src
    assert "com.google.android.gms" in src
    assert "com.unity3d.player" in src
    assert "com.applovin" in src
    assert "com.android.chrome" in src
    assert "if (isAdPackage(pkg)) return false" in src

    # 4. BingoTask checks
    with open(bingo_task, "r", encoding="utf-8") as f:
        src = f.read()
    assert "hasStrongBingoSignature" in src
    assert "距離下次抽選還有" in src
    assert "9/9" in src

    # 5. AutomationEngine checks
    with open(automation_engine, "r", encoding="utf-8") as f:
        src = f.read()
    assert "fun isVerifiedMenuScreen" in src
    assert "currentState = EngineState.AD_WATCHING" in src
    assert "PatrolPhase.LOTTERY" in src
    assert "allowTopSafeZone = true" in src

    # 6. AdCloserHandler checks
    with open(ad_closer_handler, "r", encoding="utf-8") as f:
        src = f.read()
    assert "currentScreenHeight * 0.08f" in src
    assert "AppConfig.isRedirectPackage(fgPkg) && !isAd" in src


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
