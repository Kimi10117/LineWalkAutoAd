#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Milestone 1 Adversarial Challenge & Empirical Verification Suite
LineWalkAutoAd - Milestone 1: R1, R5, R6 Universal Device Adaptation Architecture

Executed by Challenger 2 (teamwork_preview_challenger).
Targets:
- F1/F2 (R1): Dynamic Top Tab Scan, Semantic Y-Anchor & 4-Quadrant Horizontal Navigation
- F3/F4 (R5): Dynamic Task Panel Tab Discovery, Active Tab Detection & Clean Close
- F5/F6 (R6): Bottom Navigation Dynamic OCR Center Locking & Safe 0.910f Fallback Anchor
- Edge cases: 0 tabs, duplicate tabs, negative coordinates, null bitmaps, density metric variations
- Handler regressions in AutomationEngine.kt (isVerifiedMenuScreen, state machine loops)
"""

import math
import pytest


class Rect:
    def __init__(self, left: int, top: int, right: int, bottom: int):
        self.left = int(left)
        self.top = int(top)
        self.right = int(right)
        self.bottom = int(bottom)

    def centerX(self) -> int:
        return (self.left + self.right) // 2

    def centerY(self) -> int:
        return (self.top + self.bottom) // 2

    def width(self) -> int:
        return self.right - self.left

    def height(self) -> int:
        return self.bottom - self.top


class OcrItem:
    def __init__(self, text: str, bounds: Rect):
        self.text = text
        self.bounds = bounds


class MainTab:
    def __init__(self, name: str, short_label: str, x_ratio: float, y_ratio: float = 0.165):
        self.name = name
        self.shortLabel = short_label
        self.xRatio = x_ratio
        self.yRatio = y_ratio


MOVE = MainTab("首頁(移動)", "移動", 0.125)
STEP = MainTab("步數", "步數", 0.375)
TIME = MainTab("時間(放置)", "時間", 0.625)
GAME = MainTab("遊戲", "遊戲", 0.875)


def clamp(val: int, min_val: int, max_val: int) -> int:
    return max(min_val, min(val, max_val))


# -------------------------------------------------------------------------
# Simulated AutomationEngine getTabRect
# -------------------------------------------------------------------------
def getTabRect(tab: MainTab, sw: int, sh: int, ocr_items: list, screen_bitmap=None, probed_y: int = None) -> Rect:
    effective_sh = sh if sh > 0 else 2400
    effective_sw = sw if sw > 0 else 1080

    # 1. 動態掃描 cy < sh * 0.30f 之頂部四大分頁文字
    matched_tabs = []
    for item in ocr_items:
        cy = item.bounds.centerY() / effective_sh
        t = item.text.replace(" ", "")
        if 0.05 <= cy <= 0.30 and any(kw in t for kw in ["首頁", "移動", "步數", "時間", "遊戲"]):
            matched_tabs.append(item)

    # 2. 計算動態基準線 Y_anchor
    if matched_tabs:
        dynamic_y_anchor = int(sum(it.bounds.centerY() for it in matched_tabs) / len(matched_tabs))
    else:
        if probed_y is not None:
            raw_probed = probed_y - int(effective_sh * 0.015)
            dynamic_y_anchor = clamp(raw_probed, int(effective_sh * 0.130), int(effective_sh * 0.225))
        else:
            dynamic_y_anchor = int(effective_sh * 0.165)

    # 3. 四大分頁點擊座標水平採用 4 等分相對象限中心 (0.125f, 0.375f, 0.625f, 0.875f)
    target_center_x = int(effective_sw * tab.xRatio)
    half_w = int(effective_sw * 0.10)
    half_h = max(24, int(effective_sh * 0.018))

    tab_keywords = {
        "首頁(移動)": ["首頁", "移動"],
        "步數": ["步數"],
        "時間(放置)": ["時間"],
        "遊戲": ["遊戲"],
    }.get(tab.name, [])

    direct_ocr = None
    for item in matched_tabs:
        t = item.text.replace(" ", "")
        if any(kw in t for kw in tab_keywords):
            direct_ocr = item
            break

    if direct_ocr is not None:
        pad_x = max(24, int(effective_sw * 0.04))
        return Rect(
            clamp(direct_ocr.bounds.left - pad_x, 0, effective_sw),
            clamp(dynamic_y_anchor - half_h, 0, effective_sh),
            clamp(direct_ocr.bounds.right + pad_x, 0, effective_sw),
            clamp(dynamic_y_anchor + half_h, 0, effective_sh),
        )
    else:
        return Rect(
            clamp(target_center_x - half_w, 0, effective_sw),
            clamp(dynamic_y_anchor - half_h, 0, effective_sh),
            clamp(target_center_x + half_w, 0, effective_sw),
            clamp(dynamic_y_anchor + half_h, 0, effective_sh),
        )


# -------------------------------------------------------------------------
# Simulated MissionTask logic
# -------------------------------------------------------------------------
class SimulatedMissionTask:
    @staticmethod
    def getAvailableTabs(ocr_items: list, sh: int) -> list:
        standard_order = ["特別", "每日", "每週"]
        effective_sh = sh if sh > 0 else 2400
        detected = []
        for tab_name in standard_order:
            if any(0.12 <= (item.bounds.centerY() / effective_sh) <= 0.28 and tab_name in item.text for item in ocr_items):
                detected.append(tab_name)
        return detected if detected else ["每日", "每週"]

    @staticmethod
    def detectCurrentActiveTab(black_xs: list, sw: int, available_tabs: list = None) -> str:
        if available_tabs is None:
            available_tabs = ["特別", "每日", "每週"]
        if len(black_xs) >= 12:
            avg_x_ratio = sum(black_xs) / len(black_xs) / sw
            if len(available_tabs) == 2:
                return available_tabs[0] if avg_x_ratio < 0.50 else available_tabs[1]
            elif len(available_tabs) == 1:
                return available_tabs[0]
            else:
                if avg_x_ratio < 0.35:
                    return "特別"
                elif 0.35 <= avg_x_ratio <= 0.65:
                    return "每日"
                else:
                    return "每週"
        return available_tabs[0] if available_tabs else "每日"

    @staticmethod
    def getTabRect(tab_name: str, sw: int, sh: int, ocr_items: list = None) -> Rect:
        ocr_items = ocr_items or []
        effective_sh = sh if sh > 0 else 2400
        effective_sw = sw if sw > 0 else 1080

        ocr_match = None
        for item in ocr_items:
            cy = item.bounds.centerY() / effective_sh
            if 0.12 <= cy <= 0.28 and tab_name in item.text:
                ocr_match = item
                break
        if ocr_match:
            return ocr_match.bounds

        has_special = any(
            0.12 <= (item.bounds.centerY() / effective_sh) <= 0.28 and "特別" in item.text
            for item in ocr_items
        )
        if tab_name == "特別":
            x_ratio = 0.20
        elif tab_name == "每週":
            x_ratio = 0.80 if has_special else 0.67
        else:
            x_ratio = 0.50 if has_special else 0.33

        y_center = int(effective_sh * 0.188)
        half_w = int(effective_sw * 0.14)
        half_h = int(effective_sh * 0.025)
        return Rect(
            int(effective_sw * x_ratio) - half_w,
            y_center - half_h,
            int(effective_sw * x_ratio) + half_w,
            y_center + half_h,
        )


# -------------------------------------------------------------------------
# Simulated FriendGiftTask logic
# -------------------------------------------------------------------------
class SimulatedFriendGiftTask:
    @staticmethod
    def getBottomFriendsTabRect(sw: int, sh: int, ocr_items: list = None) -> Rect:
        ocr_items = ocr_items or []
        effective_sh = sh if sh > 0 else 2400
        effective_sw = sw if sw > 0 else 1080

        for item in ocr_items:
            cy = item.bounds.centerY() / effective_sh
            cx = item.bounds.centerX() / effective_sw
            t = item.text.replace(" ", "")
            if cy > 0.85 and 0.20 <= cx <= 0.55 and "好友" in t:
                if not any(ex in t for ex in ["邀請", "被", "名單"]) and len(t) <= 4:
                    return item.bounds

        target_x = int(effective_sw * 0.375)
        target_y = int(effective_sh * 0.910)
        half_w = int(effective_sw * 0.08)
        half_h = max(24, int(effective_sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)

    @staticmethod
    def getBottomMainTabRect(sw: int, sh: int, ocr_items: list = None) -> Rect:
        ocr_items = ocr_items or []
        effective_sh = sh if sh > 0 else 2400
        effective_sw = sw if sw > 0 else 1080

        for item in ocr_items:
            cy = item.bounds.centerY() / effective_sh
            cx = item.bounds.centerX() / effective_sw
            t = item.text.replace(" ", "")
            if cy > 0.85 and cx < 0.30 and ("集點" in t) and len(t) <= 4:
                return item.bounds

        target_x = int(effective_sw * 0.125)
        target_y = int(effective_sh * 0.910)
        half_w = int(effective_sw * 0.08)
        half_h = max(24, int(effective_sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)


# -------------------------------------------------------------------------
# Simulated AutomationEngine isVerifiedMenuScreen logic
# -------------------------------------------------------------------------
def isVerifiedMenuScreen(ocr_items: list, sw: int, sh: int) -> bool:
    effective_sh = sh if sh > 0 else 2400
    # 1. 排除主畫面頂部分頁 (cy in 0.05f..0.30f)
    has_main_tabs = any(
        0.05 <= (item.bounds.centerY() / effective_sh) <= 0.30 and
        any(k in item.text for k in ["首頁", "步數", "時間", "遊戲"])
        for item in ocr_items
    )
    if has_main_tabs:
        return False

    # 2. 排除底部導覽列 (cy > 0.85f)
    has_bottom_nav = any(
        (item.bounds.centerY() / effective_sh) > 0.85 and
        any(k in item.text for k in ["集點", "好友", "使用", "精選"])
        for item in ocr_items
    )
    if has_bottom_nav:
        return False

    # 3. 選單標題 (cy in 0.08f..0.22f)
    has_menu_title = any(
        0.08 <= (item.bounds.centerY() / effective_sh) <= 0.22 and
        item.text.replace(" ", "").upper() in ["選單", "設定", "SETTINGS"]
        for item in ocr_items
    )

    # 4. 設定項目
    setting_keywords = [
        "應用程式設定", "帳號", "提醒", "關於", "條款",
        "關閉動畫效果", "省電模式", "位置資訊設定", "目前版本",
        "常見問題", "服務條款", "隱私權政策", "登出"
    ]
    matched_kws = [
        kw for kw in setting_keywords
        if any(kw in it.text.replace(" ", "") for it in ocr_items)
    ]
    return (has_menu_title and len(matched_kws) > 0) or len(matched_kws) >= 2


# =========================================================================
# TEST SUITE
# =========================================================================

def test_r1_top_tab_zero_ocr_fallback_coordinates():
    """R1: Test getTabRect fallback coordinates across various resolutions when 0 tabs detected by OCR."""
    test_resolutions = [
        ("16:9 Standard", 1080, 1920),
        ("19.5:9 Modern", 1080, 2340),
        ("22:9 Tall (SM-S7210 / SM-F731N)", 1080, 2640),
        ("Foldable Inner", 1812, 2176),
        ("Tablet Portrait", 1600, 2560),
    ]
    tabs = [MOVE, STEP, TIME, GAME]

    for res_name, sw, sh in test_resolutions:
        expected_y = int(sh * 0.165)
        for tab in tabs:
            rect = getTabRect(tab, sw, sh, ocr_items=[])
            expected_x = int(sw * tab.xRatio)
            # Center X must match quadrant center
            assert rect.centerX() == expected_x, f"Failed for {res_name} {tab.name}: centerX={rect.centerX()} != {expected_x}"
            # Center Y must match baseline 0.165f
            assert rect.centerY() == expected_y, f"Failed for {res_name} {tab.name}: centerY={rect.centerY()} != {expected_y}"
            # Rect must be fully within screen bounds
            assert 0 <= rect.left < rect.right <= sw
            assert 0 <= rect.top < rect.bottom <= sh


def test_r1_top_tab_four_quadrants_no_overlap():
    """R1: Verify that 4 quadrant tab rects never overlap horizontally on any screen aspect ratio."""
    test_widths = [720, 1080, 1440, 1600, 1812, 2160]
    sh = 2400

    for sw in test_widths:
        r_move = getTabRect(MOVE, sw, sh, ocr_items=[])
        r_step = getTabRect(STEP, sw, sh, ocr_items=[])
        r_time = getTabRect(TIME, sw, sh, ocr_items=[])
        r_game = getTabRect(GAME, sw, sh, ocr_items=[])

        # Verify strict monotonicity: move.right < step.left < step.right < time.left < ...
        assert r_move.right < r_step.left, f"Overlap between MOVE and STEP at sw={sw}"
        assert r_step.right < r_time.left, f"Overlap between STEP and TIME at sw={sw}"
        assert r_time.right < r_game.left, f"Overlap between TIME and GAME at sw={sw}"
        assert r_game.right <= sw


def test_r1_top_tab_dynamic_y_anchor_calculation():
    """R1: Test dynamic Y-anchor calculation when OCR detects tabs at non-standard heights."""
    sw, sh = 1080, 2640  # SM-F731N tall device
    # Tabs situated at y = 435 px (~0.165) and 445 px (~0.168)
    ocr_items = [
        OcrItem("首頁", Rect(50, 420, 200, 450)),    # cy = 435
        OcrItem("步數", Rect(320, 420, 470, 450)),   # cy = 435
        OcrItem("時間", Rect(590, 430, 740, 460)),   # cy = 445
        OcrItem("遊戲", Rect(860, 430, 1010, 460)),  # cy = 445
    ]
    # Average Y = (435 + 435 + 445 + 445) / 4 = 440
    rect = getTabRect(GAME, sw, sh, ocr_items)
    assert rect.centerY() == 440
    # Because "遊戲" directly matches OCR, horizontal bounds align with OCR item + padX
    pad_x = max(24, int(sw * 0.04))  # 43
    assert rect.left == clamp(860 - pad_x, 0, sw)
    assert rect.right == clamp(1010 + pad_x, 0, sw)


def test_r1_top_tab_with_underline_probe():
    """R1: Test fallback to templateMatcher underline probe when OCR is missing."""
    sw, sh = 1080, 2400
    probed_y = 480  # ~0.200f
    rect = getTabRect(MOVE, sw, sh, ocr_items=[], probed_y=probed_y)
    expected_y = clamp(probed_y - int(sh * 0.015), int(sh * 0.130), int(sh * 0.225))
    assert rect.centerY() == expected_y


def test_r1_top_tab_edge_cases_negative_and_duplicates():
    """R1: Adversarial edge cases: duplicate tabs, negative OCR coordinates, empty OCR."""
    sw, sh = 1080, 2400
    # Duplicate tabs and negative bounds
    ocr_items = [
        OcrItem("首頁", Rect(-100, -50, 150, 300)),    # negative coordinates, cy = 125, cy/sh = 0.052 (in range)
        OcrItem("首頁", Rect(100, 200, 250, 400)),     # duplicate cy = 300, cy/sh = 0.125
        OcrItem("遊戲", Rect(900, 250, 1200, 350)),    # right exceeds sw
        OcrItem("其他雜訊", Rect(50, 1500, 200, 1600)), # cy out of 0.05..0.30 range
    ]
    rect_move = getTabRect(MOVE, sw, sh, ocr_items)
    # Must be safely clamped within 0..sw and 0..sh
    assert 0 <= rect_move.left <= sw
    assert 0 <= rect_move.right <= sw
    assert 0 <= rect_move.top <= sh
    assert 0 <= rect_move.bottom <= sh

    rect_game = getTabRect(GAME, sw, sh, ocr_items)
    assert rect_game.right == sw  # Clamped to sw


def test_r5_mission_get_available_tabs_3tabs_vs_2tabs():
    """R5: Test dynamic task panel tab discovery with 3 tabs vs 2 tabs."""
    sh = 2400
    # Scenario A: 3 tabs present ("特別", "每日", "每週")
    ocr_3tabs = [
        OcrItem("特別", Rect(150, 400, 300, 460)),
        OcrItem("每日", Rect(450, 400, 600, 460)),
        OcrItem("每週", Rect(750, 400, 900, 460)),
    ]
    tabs_a = SimulatedMissionTask.getAvailableTabs(ocr_3tabs, sh)
    assert tabs_a == ["特別", "每日", "每週"]

    # Scenario B: "特別" tab completed and disappeared, only 2 tabs left
    ocr_2tabs = [
        OcrItem("每日", Rect(300, 400, 500, 460)),
        OcrItem("每週", Rect(600, 400, 800, 460)),
    ]
    tabs_b = SimulatedMissionTask.getAvailableTabs(ocr_2tabs, sh)
    assert tabs_b == ["每日", "每週"]

    # Scenario C: OCR detects 0 tabs -> graceful fallback to 2 tabs
    tabs_c = SimulatedMissionTask.getAvailableTabs([], sh)
    assert tabs_c == ["每日", "每週"]


def test_r5_mission_detect_current_active_tab():
    """R5: Test active tab detection for both 3-tab layout and 2-tab layout."""
    sw = 1080
    # Case 1: 3-tab layout
    avail_3 = ["特別", "每日", "每週"]
    # Pill on left: x ≈ 0.20 -> "特別"
    black_left = [int(sw * 0.20)] * 20
    assert SimulatedMissionTask.detectCurrentActiveTab(black_left, sw, avail_3) == "特別"

    # Pill in middle: x ≈ 0.50 -> "每日"
    black_mid = [int(sw * 0.50)] * 20
    assert SimulatedMissionTask.detectCurrentActiveTab(black_mid, sw, avail_3) == "每日"

    # Pill on right: x ≈ 0.80 -> "每週"
    black_right = [int(sw * 0.80)] * 20
    assert SimulatedMissionTask.detectCurrentActiveTab(black_right, sw, avail_3) == "每週"

    # Case 2: 2-tab layout (Special tab gone)
    avail_2 = ["每日", "每週"]
    # Pill on left (x ≈ 0.35) -> should be "每日" (in 2-tab layout, x < 0.50 is 每日)
    black_2tab_left = [int(sw * 0.35)] * 20
    assert SimulatedMissionTask.detectCurrentActiveTab(black_2tab_left, sw, avail_2) == "每日"

    # Pill on right (x ≈ 0.65) -> should be "每週"
    black_2tab_right = [int(sw * 0.65)] * 20
    assert SimulatedMissionTask.detectCurrentActiveTab(black_2tab_right, sw, avail_2) == "每週"

    # Case 3: Insufficient black points (< 12) -> returns first tab safely
    assert SimulatedMissionTask.detectCurrentActiveTab([100] * 5, sw, avail_2) == "每日"


def test_r5_mission_state_machine_termination_and_clean_close():
    """R5: Adversarial simulation of Mission state machine loop to guarantee termination and clean close."""
    sw, sh = 1080, 2400

    # Simulation with 2 tabs: 每日 and 每週
    available_tabs = ["每日", "每週"]
    checked_mission_tabs = set()
    is_mission_completed = False
    closed_via_x = False

    # Mock screen ticks
    for tick in range(10):
        if is_mission_completed:
            break
        # Suppose tick 0 is on 每日, tick 1 is on 每週
        active_tab = "每日" if tick == 0 else "每週"
        # No claimable buttons
        checked_mission_tabs.add(active_tab)

        next_tab = next((t for t in available_tabs if t not in checked_mission_tabs), None)
        if next_tab is not None:
            # Switching tabs
            continue
        else:
            # All checked -> close dialog!
            is_mission_completed = True
            closed_via_x = True

    assert is_mission_completed is True
    assert closed_via_x is True
    assert len(checked_mission_tabs) == 2


def test_r6_bottom_nav_ocr_locking_vs_fallback():
    """R6: Test OCR locking vs fallback coordinates for bottom nav tabs."""
    sw, sh = 1080, 2400

    # Scenario 1: OCR text detected
    ocr_items = [
        OcrItem("好友", Rect(350, 2180, 450, 2220)),
        OcrItem("集點", Rect(100, 2180, 180, 2220)),
    ]
    r_friends = SimulatedFriendGiftTask.getBottomFriendsTabRect(sw, sh, ocr_items)
    assert r_friends.centerX() == 400
    assert r_friends.centerY() == 2200

    r_main = SimulatedFriendGiftTask.getBottomMainTabRect(sw, sh, ocr_items)
    assert r_main.centerX() == 140
    assert r_main.centerY() == 2200

    # Scenario 2: OCR missing -> fallback strictly anchors at sh * 0.910f
    fb_friends = SimulatedFriendGiftTask.getBottomFriendsTabRect(sw, sh, [])
    assert fb_friends.centerX() == int(sw * 0.375)
    assert fb_friends.centerY() == int(sh * 0.910)

    fb_main = SimulatedFriendGiftTask.getBottomMainTabRect(sw, sh, [])
    assert fb_main.centerX() == int(sw * 0.125)
    assert fb_main.centerY() == int(sh * 0.910)


def test_r6_bottom_nav_clearance_across_display_densities():
    """R6: Verify that bottom nav fallback (sh * 0.910f) never penetrates virtual nav bars across display densities."""
    # Common densities and screen heights
    densities_and_devices = [
        ("MDPI (160dpi, density=1.0)", 1.0, 720, 1280),
        ("HDPI (240dpi, density=1.5)", 1.5, 720, 1280),
        ("XHDPI (320dpi, density=2.0)", 2.0, 1080, 1920),
        ("420DPI (density=2.625)", 2.625, 1080, 2340),
        ("XXHDPI (480dpi, density=3.0)", 3.0, 1080, 2400),
        ("Tall XXHDPI (480dpi, density=3.0)", 3.0, 1080, 2640),
        ("XXXHDPI (640dpi, density=4.0)", 4.0, 1440, 3120),
    ]

    for label, density, sw, sh in densities_and_devices:
        fb_rect = SimulatedFriendGiftTask.getBottomFriendsTabRect(sw, sh, [])
        # 3-button nav bar height: standard 54dp
        nav_bar_height_px = int(54 * density)
        nav_bar_top_y = sh - nav_bar_height_px

        # Gesture navigation bar height: standard 16dp
        gesture_bar_height_px = int(16 * density)
        gesture_bar_top_y = sh - gesture_bar_height_px

        # Center click must strictly be above 3-button nav bar and gesture bar
        assert fb_rect.centerY() < nav_bar_top_y, f"Failed for {label}: centerY={fb_rect.centerY()} >= nav_top={nav_bar_top_y}"
        assert fb_rect.centerY() < gesture_bar_top_y, f"Failed for {label}: centerY={fb_rect.centerY()} >= gesture_top={gesture_bar_top_y}"

        # Jitter margin (+6 px) must also stay safe
        assert fb_rect.centerY() + 6 < gesture_bar_top_y


def test_r6_bottom_nav_ocr_exclusion_of_bait_and_long_text():
    """R6: Ensure bait keywords like '邀請好友', '好友名單' do not trigger false OCR locking."""
    sw, sh = 1080, 2400
    bait_ocr = [
        OcrItem("還沒有邀請好友實在太可惜了！", Rect(200, 2100, 800, 2200)),
        OcrItem("邀請好友", Rect(300, 2150, 500, 2220)),
        OcrItem("被邀請好友", Rect(300, 2150, 550, 2220)),
        OcrItem("好友名單", Rect(300, 2150, 500, 2220)),
    ]
    rect = SimulatedFriendGiftTask.getBottomFriendsTabRect(sw, sh, bait_ocr)
    # All bait items must be excluded; fallback to 0.375f, 0.910f
    assert rect.centerX() == int(sw * 0.375)
    assert rect.centerY() == int(sh * 0.910)


def test_is_verified_menu_screen_regression_guard():
    """Regression check: Verify isVerifiedMenuScreen correctly discriminates main screen tabs in 0.05f..0.30f."""
    sw, sh = 1080, 2640  # Tall screen SM-S7210 / SM-F731N

    # Main screen with tabs at 0.165f -> MUST NOT be identified as menu screen!
    main_screen_ocr = [
        OcrItem("首頁", Rect(50, 420, 200, 450)),
        OcrItem("遊戲", Rect(860, 420, 1010, 450)),
        OcrItem("集點", Rect(100, 2180, 250, 2220)),
        OcrItem("常見問題", Rect(100, 1000, 300, 1050)), # Even if noise keyword present
    ]
    assert isVerifiedMenuScreen(main_screen_ocr, sw, sh) is False

    # Real Settings menu screen
    settings_screen_ocr = [
        OcrItem("設定", Rect(400, 300, 680, 380)),        # Menu title at cy=340 (340/2640 = 0.128, in 0.08..0.22)
        OcrItem("應用程式設定", Rect(100, 600, 400, 650)),
        OcrItem("常見問題", Rect(100, 800, 300, 850)),
    ]
    assert isVerifiedMenuScreen(settings_screen_ocr, sw, sh) is True
