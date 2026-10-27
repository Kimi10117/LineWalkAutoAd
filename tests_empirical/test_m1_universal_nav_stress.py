#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EMPIRICAL CHALLENGER ADVERSARIAL STRESS TEST SUITE — Milestone 1 (R1, R5, R6)
LineWalkAutoAd Universal Device Adaptation Architecture Verification

Tested components:
1. Top Tab Dynamic Y-Anchor & 4-Quadrant Relative Coordinate Engine across screen resolutions
   (1080x1920, 1080x2340, 1080x2640 SM-S7210, 1812x2176 foldable, tablet, extreme aspect ratios).
2. Dynamic Mission Task Tab Discovery ("特別" absent vs present, 2-tab vs 3-tab layout, deadlock elimination).
3. Bottom Navigation Dynamic OCR Locking & Safe Fallback Anchor (centerY=0.910f, bottom<=0.930f navigation bar clearance).
"""

import os
import re
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_SRC_MAIN = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


class Rect:
    def __init__(self, left: int, top: int, right: int, bottom: int):
        self.left = int(left)
        self.top = int(top)
        self.right = int(right)
        self.bottom = int(bottom)

    def width(self) -> int:
        return self.right - self.left

    def height(self) -> int:
        return self.bottom - self.top

    def centerX(self) -> float:
        return (self.left + self.right) / 2.0

    def centerY(self) -> float:
        return (self.top + self.bottom) / 2.0

    def __repr__(self):
        return f"Rect({self.left}, {self.top}, {self.right}, {self.bottom})"


class OcrItem:
    def __init__(self, text: str, bounds: Rect):
        self.text = text
        self.bounds = bounds


# ==============================================================================
# Python Replica of AutomationEngine.kt Top Tab Coordinate Calculations
# ==============================================================================

class MainTab:
    MOVE = (0, "首頁(移動)", "移動", 0.125)
    STEP = (1, "步數", "步數", 0.375)
    TIME = (2, "時間(放置)", "時間", 0.625)
    GAME = (3, "遊戲", "遊戲", 0.875)

    @classmethod
    def all_tabs(cls):
        return [cls.MOVE, cls.STEP, cls.TIME, cls.GAME]


class TopTabEngine:
    @staticmethod
    def get_tab_rect(tab, sw: int, sh: int, ocr_items=None, probed_underline_y=None) -> Rect:
        if ocr_items is None:
            ocr_items = []

        # 1. 動態掃描 cy < sh * 0.30f 之頂部四大分頁文字
        matched_tabs = []
        for item in ocr_items:
            cy = item.bounds.centerY() / float(sh)
            t = item.text.replace(" ", "")
            if 0.05 <= cy <= 0.30 and any(kw in t for kw in ["首頁", "移動", "步數", "時間", "遊戲"]):
                matched_tabs.append(item)

        # 2. 計算動態基準線 Y_anchor
        if len(matched_tabs) > 0:
            dynamic_y_anchor = int(sum(item.bounds.centerY() for item in matched_tabs) / len(matched_tabs))
        else:
            if probed_underline_y is not None:
                offset_y = int(probed_underline_y - int(sh * 0.015))
                min_y = int(sh * 0.130)
                max_y = int(sh * 0.225)
                dynamic_y_anchor = max(min_y, min(max_y, offset_y))
            else:
                dynamic_y_anchor = int(sh * 0.165)

        # 3. 四大分頁點擊座標水平採用 4 等分相對象限中心 (0.125f, 0.375f, 0.625f, 0.875f)
        target_center_x = int(sw * tab[3])
        half_w = int(sw * 0.10)
        half_h = max(24, int(sh * 0.018))

        tab_keywords = {
            MainTab.MOVE: ["首頁", "移動"],
            MainTab.STEP: ["步數"],
            MainTab.TIME: ["時間"],
            MainTab.GAME: ["遊戲"]
        }[tab]

        direct_ocr = None
        for item in matched_tabs:
            t = item.text.replace(" ", "")
            if any(kw in t for kw in tab_keywords):
                direct_ocr = item
                break

        if direct_ocr is not None:
            pad_x = max(24, int(sw * 0.04))
            return Rect(
                max(0, min(sw, direct_ocr.bounds.left - pad_x)),
                max(0, min(sh, dynamic_y_anchor - half_h)),
                max(0, min(sw, direct_ocr.bounds.right + pad_x)),
                max(0, min(sh, dynamic_y_anchor + half_h))
            )
        else:
            return Rect(
                max(0, min(sw, target_center_x - half_w)),
                max(0, min(sh, dynamic_y_anchor - half_h)),
                max(0, min(sw, target_center_x + half_w)),
                max(0, min(sh, dynamic_y_anchor + half_h))
            )


# ==============================================================================
# Python Replica of MissionTask.kt Dynamic Tab Discovery
# ==============================================================================

class MissionTaskEngine:
    @staticmethod
    def get_available_tabs(ocr_items, sh: int = 2400):
        standard_order = ["特別", "每日", "每週"]
        effective_sh = sh if sh > 0 else 2400
        detected = []
        for tab_name in standard_order:
            for item in ocr_items:
                cy = item.bounds.centerY() / float(effective_sh)
                if 0.12 <= cy <= 0.28 and tab_name in item.text:
                    detected.append(tab_name)
                    break
        return detected if len(detected) > 0 else ["每日", "每週"]

    @staticmethod
    def detect_current_active_tab(black_xs, sw: int, available_tabs=None):
        if available_tabs is None:
            available_tabs = ["特別", "每日", "每週"]

        if len(black_xs) >= 12:
            avg_x_ratio = (sum(black_xs) / len(black_xs)) / float(sw)
            if len(available_tabs) == 2:
                # 2-tab 佈局: 左側 (每日) vs 右側 (每週)
                return available_tabs[0] if avg_x_ratio < 0.50 else available_tabs[1]
            elif len(available_tabs) == 1:
                return available_tabs[0]
            else:
                # 3-tab 佈局: 特別 (<0.35) vs 每日 (0.35..0.65) vs 每週 (>0.65)
                if avg_x_ratio < 0.35:
                    return "特別"
                elif 0.35 <= avg_x_ratio <= 0.65:
                    return "每日"
                else:
                    return "每週"
        return available_tabs[0] if available_tabs else "每日"

    @staticmethod
    def get_tab_rect(tab_name: str, sw: int, sh: int, ocr_items=None):
        if ocr_items is None:
            ocr_items = []
        effective_sh = sh if sh > 0 else 2400

        for item in ocr_items:
            cy = item.bounds.centerY() / float(effective_sh)
            if 0.12 <= cy <= 0.28 and tab_name in item.text:
                return item.bounds

        has_special = any(0.12 <= (item.bounds.centerY() / float(effective_sh)) <= 0.28 and "特別" in item.text for item in ocr_items)

        if tab_name == "特別":
            x_ratio = 0.20
        elif tab_name == "每週":
            x_ratio = 0.80 if has_special else 0.67
        else:
            x_ratio = 0.50 if has_special else 0.33

        y_center = int(sh * 0.188)
        half_w = int(sw * 0.14)
        half_h = max(24, int(sh * 0.022))
        return Rect(
            max(0, min(sw, int(sw * x_ratio - half_w))),
            max(0, min(sh, y_center - half_h)),
            max(0, min(sw, int(sw * x_ratio + half_w))),
            max(0, min(sh, y_center + half_h))
        )


# ==============================================================================
# Python Replica of FriendGiftTask.kt Bottom Navigation Calculations
# ==============================================================================

class FriendGiftTaskEngine:
    @staticmethod
    def get_bottom_friends_tab_rect(sw: int, sh: int, ocr_items=None) -> Rect:
        if ocr_items is None:
            ocr_items = []

        for item in ocr_items:
            cy = item.bounds.centerY() / float(sh)
            cx = item.bounds.centerX() / float(sw)
            t = item.text.replace(" ", "")
            if (cy > 0.85 and 0.20 <= cx <= 0.55 and
                    "好友" in t and "邀請" not in t and "被" not in t and "名單" not in t and len(t) <= 4):
                return item.bounds

        target_x = int(sw * 0.375)
        target_y = int(sh * 0.910)
        half_w = int(sw * 0.08)
        half_h = max(24, int(sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)

    @staticmethod
    def get_bottom_main_tab_rect(sw: int, sh: int, ocr_items=None) -> Rect:
        if ocr_items is None:
            ocr_items = []

        for item in ocr_items:
            cy = item.bounds.centerY() / float(sh)
            cx = item.bounds.centerX() / float(sw)
            t = item.text.replace(" ", "")
            if cy > 0.85 and cx < 0.30 and (t == "集點" or ("集點" in t and len(t) <= 4)):
                return item.bounds

        target_x = int(sw * 0.125)
        target_y = int(sh * 0.910)
        half_w = int(sw * 0.08)
        half_h = max(24, int(sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)


# ==============================================================================
# TEST SUITE 1: Top Tab Universal Adaptation & Multi-Resolution Quadrants
# ==============================================================================

TEST_RESOLUTIONS = [
    (1080, 1920, "16:9 Standard Phone (Pixel 2/3)"),
    (1080, 2340, "19.5:9 Modern FHD+ (Galaxy S20-S24)"),
    (1080, 2640, "22:9 Long Screen (Galaxy Z Flip / SM-S7210)"),
    (1812, 2176, "Foldable Inner Display (Galaxy Z Fold 6)"),
    (1440, 3120, "19.5:9 Modern QHD+ (Pixel 8 Pro)"),
    (720, 1600, "20:9 Entry-Level Phone"),
    (1600, 2560, "16:10 Android Tablet")
]

class TestTopTabUniversalAdaptation:
    """Stress-test Top Tab Universal Adaptation across all screen form factors."""

    @pytest.mark.parametrize("sw,sh,desc", TEST_RESOLUTIONS)
    def test_top_tab_fallback_quadrants_across_resolutions(self, sw, sh, desc):
        """Confirm Y click coordinate is within [0.130f..0.225f] and X falls in respective quadrants."""
        expected_quadrants = [
            (MainTab.MOVE, 0.0, 0.25, 0.125),
            (MainTab.STEP, 0.25, 0.50, 0.375),
            (MainTab.TIME, 0.50, 0.75, 0.625),
            (MainTab.GAME, 0.75, 1.0, 0.875),
        ]

        for tab, q_min, q_max, exp_ratio in expected_quadrants:
            rect = TopTabEngine.get_tab_rect(tab, sw, sh)
            center_x = rect.centerX()
            center_y = rect.centerY()

            y_ratio = center_y / float(sh)
            x_ratio = center_x / float(sw)

            # Confirm Y coordinate is in valid bounds [0.130f..0.225f] (universal fallback 0.165f)
            assert 0.130 <= y_ratio <= 0.225, f"[{desc}] Tab {tab[1]} center Y ratio {y_ratio:.4f} outside [0.130, 0.225]"
            assert abs(y_ratio - 0.165) < 0.005, f"[{desc}] Tab {tab[1]} fallback Y ratio {y_ratio:.4f} != 0.165f"

            # Confirm X coordinate falls squarely in correct quadrant
            assert q_min <= x_ratio <= q_max, f"[{desc}] Tab {tab[1]} center X ratio {x_ratio:.4f} outside quadrant [{q_min}, {q_max}]"
            assert abs(x_ratio - exp_ratio) < 0.005, f"[{desc}] Tab {tab[1]} center X ratio {x_ratio:.4f} != {exp_ratio}"

            # Rect bounds safety
            assert 0 <= rect.left < rect.right <= sw
            assert 0 <= rect.top < rect.bottom <= sh

    @pytest.mark.parametrize("sw,sh,desc", TEST_RESOLUTIONS)
    def test_top_tab_dynamic_y_anchor_with_detected_ocr(self, sw, sh, desc):
        """Confirm dynamic Y-anchor averages detected tab titles in cy < sh * 0.30f."""
        # Simulate tabs found at non-standard vertical offset (e.g., SM-S7210 tall screen where cy ≈ 0.182f)
        simulated_cy = int(sh * 0.182)
        ocr_items = [
            OcrItem("首頁", Rect(int(sw * 0.05), simulated_cy - 20, int(sw * 0.20), simulated_cy + 20)),
            OcrItem("步數", Rect(int(sw * 0.30), simulated_cy - 18, int(sw * 0.45), simulated_cy + 18)),
            OcrItem("時間", Rect(int(sw * 0.55), simulated_cy - 22, int(sw * 0.70), simulated_cy + 22)),
            OcrItem("遊戲", Rect(int(sw * 0.80), simulated_cy - 20, int(sw * 0.95), simulated_cy + 20)),
        ]

        for tab in MainTab.all_tabs():
            rect = TopTabEngine.get_tab_rect(tab, sw, sh, ocr_items)
            center_y = rect.centerY()
            y_ratio = center_y / float(sh)
            # Dynamic anchor should match the detected average cy (0.182f) rather than hardcoded 0.165f
            assert abs(y_ratio - 0.182) < 0.005, f"[{desc}] Tab {tab[1]} dynamic anchor {y_ratio:.4f} did not track detected OCR 0.182f"

    def test_top_tab_probed_underline_offset_and_coercion(self):
        """Confirm probed underline is offset by -0.015sh and clamped within [0.130f..0.225f]."""
        sw, sh = 1080, 2640  # SM-S7210
        # Case A: Underline at 0.180f (middle of valid range)
        probed_y = int(sh * 0.180)
        rect_a = TopTabEngine.get_tab_rect(MainTab.GAME, sw, sh, probed_underline_y=probed_y)
        expected_y = probed_y - int(sh * 0.015)
        assert rect_a.centerY() == expected_y

        # Case B: Underline too high (0.120f) -> clamped to 0.130f
        probed_y_high = int(sh * 0.120)
        rect_b = TopTabEngine.get_tab_rect(MainTab.GAME, sw, sh, probed_underline_y=probed_y_high)
        assert rect_b.centerY() == int(sh * 0.130)

        # Case C: Underline too low (0.260f) -> clamped to 0.225f
        probed_y_low = int(sh * 0.260)
        rect_c = TopTabEngine.get_tab_rect(MainTab.GAME, sw, sh, probed_underline_y=probed_y_low)
        assert rect_c.centerY() == int(sh * 0.225)


# ==============================================================================
# TEST SUITE 2: Dynamic Mission Task Tab Discovery & Clean Close
# ==============================================================================

class TestMissionTaskDynamicDiscovery:
    """Stress-test Dynamic Mission Task Tab Discovery under present vs vanished '特別' tab."""

    def test_discovery_with_3_tabs_present(self):
        """When 特別 is active on promo day, all 3 tabs must be returned and mapped."""
        sh = 2400
        sw = 1080
        cy = int(sh * 0.18)
        ocr_items = [
            OcrItem("特別任務", Rect(int(sw * 0.08), cy - 25, int(sw * 0.32), cy + 25)),
            OcrItem("每日任務", Rect(int(sw * 0.38), cy - 25, int(sw * 0.62), cy + 25)),
            OcrItem("每週任務", Rect(int(sw * 0.68), cy - 25, int(sw * 0.92), cy + 25)),
        ]

        available = MissionTaskEngine.get_available_tabs(ocr_items, sh)
        assert available == ["特別", "每日", "每週"]

        # 3-tab active tab detection via indicator pill X coordinates
        assert MissionTaskEngine.detect_current_active_tab([int(sw * 0.20)] * 15, sw, available) == "特別"
        assert MissionTaskEngine.detect_current_active_tab([int(sw * 0.50)] * 15, sw, available) == "每日"
        assert MissionTaskEngine.detect_current_active_tab([int(sw * 0.80)] * 15, sw, available) == "每週"

    def test_discovery_with_special_tab_vanished_2_tabs(self):
        """When 特別 is completed/expired and removed by LINE Walk, dynamically converge to 2 tabs."""
        sh = 2400
        sw = 1080
        cy = int(sh * 0.18)
        # 特別 is GONE; only 每日 and 每週 remain
        ocr_items = [
            OcrItem("每日任務", Rect(int(sw * 0.15), cy - 25, int(sw * 0.45), cy + 25)),
            OcrItem("每週任務", Rect(int(sw * 0.55), cy - 25, int(sw * 0.85), cy + 25)),
        ]

        available = MissionTaskEngine.get_available_tabs(ocr_items, sh)
        assert available == ["每日", "每週"], f"Expected ['每日', '每週'], got {available}"

        # In 2-tab layout, '每日' is on the left half (x < 0.50), '每週' is on the right half (x >= 0.50)
        # Prior buggy code mapped x < 0.35 to '特別', causing '每日' at x=0.30 to be misidentified as '特別'!
        daily_pill_xs = [int(sw * 0.30)] * 15
        weekly_pill_xs = [int(sw * 0.70)] * 15

        active_daily = MissionTaskEngine.detect_current_active_tab(daily_pill_xs, sw, available)
        assert active_daily == "每日", f"Failed 2-tab detection: expected '每日', got '{active_daily}'"

        active_weekly = MissionTaskEngine.detect_current_active_tab(weekly_pill_xs, sw, available)
        assert active_weekly == "每週", f"Failed 2-tab detection: expected '每週', got '{active_weekly}'"

    def test_mission_dialog_infinite_loop_prevention(self):
        """Simulate the AutomationEngine mission loop when '特別' tab is absent."""
        available_tabs = ["每日", "每週"]
        checked_tabs = set()
        iterations = 0
        dialog_closed = False
        is_mission_completed = False

        # Start on 每日
        current_active = "每日"

        while iterations < 10:
            iterations += 1
            # Current tab checked and done
            checked_tabs.add(current_active)

            # Look for next unchecked tab
            next_tab = next((t for t in available_tabs if t not in checked_tabs), None)
            if next_tab is not None:
                current_active = next_tab
                continue

            # All tabs checked -> close dialog via (X)
            is_mission_completed = True
            checked_tabs.clear()
            dialog_closed = True
            break

        assert dialog_closed is True, "Mission dialog did not close!"
        assert is_mission_completed is True
        assert iterations == 2, f"Expected exactly 2 iterations to check 2 tabs, took {iterations}"


# ==============================================================================
# TEST SUITE 3: Bottom Navigation Safe Fallback Coordinates (<= 0.930f)
# ==============================================================================

class TestBottomNavSafety:
    """Stress-test Bottom Navigation Fallback Bounds never exceed 0.930f across all resolutions."""

    @pytest.mark.parametrize("sw,sh,desc", TEST_RESOLUTIONS)
    def test_bottom_friends_tab_fallback_coordinates(self, sw, sh, desc):
        """Confirm getBottomFriendsTabRect centerY is 0.910f and bottom does not exceed 0.930f."""
        rect = FriendGiftTaskEngine.get_bottom_friends_tab_rect(sw, sh)
        center_y_ratio = rect.centerY() / float(sh)
        bottom_ratio = rect.bottom / float(sh)
        center_x_ratio = rect.centerX() / float(sw)

        # Center Y must anchor at 0.910f
        assert abs(center_y_ratio - 0.910) < 0.005, f"[{desc}] Friends centerY ratio {center_y_ratio:.4f} != 0.910f"

        # Bottom must strictly never exceed 0.930f (clearing the 54dp virtual nav bar at y >= 0.935f)
        assert bottom_ratio <= 0.9305, f"[{desc}] Friends rect bottom ratio {bottom_ratio:.4f} exceeds 0.930f!"

        # Center X must anchor at 0.375f (2nd quadrant of 4 bottom items)
        assert abs(center_x_ratio - 0.375) < 0.005, f"[{desc}] Friends centerX ratio {center_x_ratio:.4f} != 0.375f"

    @pytest.mark.parametrize("sw,sh,desc", TEST_RESOLUTIONS)
    def test_bottom_main_tab_fallback_coordinates(self, sw, sh, desc):
        """Confirm getBottomMainTabRect centerY is 0.910f and bottom does not exceed 0.930f."""
        rect = FriendGiftTaskEngine.get_bottom_main_tab_rect(sw, sh)
        center_y_ratio = rect.centerY() / float(sh)
        bottom_ratio = rect.bottom / float(sh)
        center_x_ratio = rect.centerX() / float(sw)

        # Center Y must anchor at 0.910f
        assert abs(center_y_ratio - 0.910) < 0.005, f"[{desc}] Main/Points centerY ratio {center_y_ratio:.4f} != 0.910f"

        # Bottom must strictly never exceed 0.930f
        assert bottom_ratio <= 0.9305, f"[{desc}] Main/Points rect bottom ratio {bottom_ratio:.4f} exceeds 0.930f!"

        # Center X must anchor at 0.125f (1st quadrant of 4 bottom items)
        assert abs(center_x_ratio - 0.125) < 0.005, f"[{desc}] Main/Points centerX ratio {center_x_ratio:.4f} != 0.125f"

    def test_bottom_nav_dynamic_ocr_locking_and_negative_filters(self):
        """Confirm OCR locking prioritizes exact bounds and rejects bait keywords."""
        sw, sh = 1080, 2400
        # Bait OCR items (should be rejected)
        bait_items = [
            OcrItem("邀請好友領取獎勵", Rect(100, int(sh * 0.88), 500, int(sh * 0.92))),
            OcrItem("好友名單", Rect(100, int(sh * 0.88), 400, int(sh * 0.92))),
            OcrItem("已被好友領取", Rect(100, int(sh * 0.88), 450, int(sh * 0.92))),
        ]
        fallback_friends = FriendGiftTaskEngine.get_bottom_friends_tab_rect(sw, sh, bait_items)
        assert fallback_friends.centerX() == int(sw * 0.375), "Bait item was wrongly accepted as Friends tab!"

        # Legitimate OCR item
        legit_item = OcrItem("好友", Rect(int(sw * 0.32), int(sh * 0.89), int(sw * 0.44), int(sh * 0.92)))
        locked_friends = FriendGiftTaskEngine.get_bottom_friends_tab_rect(sw, sh, [legit_item] + bait_items)
        assert locked_friends.left == legit_item.bounds.left
        assert locked_friends.top == legit_item.bounds.top


# ==============================================================================
# TEST SUITE 4: Source Code Invariants Audit
# ==============================================================================

class TestSourceCodeInvariants:
    """Verify source files in app/src/main adhere to architectural requirements."""

    def test_automation_engine_top_tabs_invariants(self):
        path = os.path.join(APP_SRC_MAIN, "engine", "AutomationEngine.kt")
        with open(path, "r", encoding="utf-8") as f:
            code = f.read()

        # R1: Must scan 0.05f..0.30f for top tabs
        assert "0.05f..0.30f" in code, "AutomationEngine.kt missing '0.05f..0.30f' top tab scan range!"
        # R1: Must use 0.165f universal fallback
        assert "0.165f" in code, "AutomationEngine.kt missing '0.165f' universal baseline!"
        # R1: Old hardcoded 0.08f..0.16f must be gone
        assert "0.08f).toInt()..(sh * 0.16f)" not in code, "AutomationEngine.kt still has hardcoded 0.08f..0.16f!"

    def test_template_matcher_underline_probe_invariants(self):
        path = os.path.join(APP_SRC_MAIN, "vision", "TemplateMatcher.kt")
        with open(path, "r", encoding="utf-8") as f:
            code = f.read()

        # R1: Must probe 0.130f..0.225f
        assert "0.130f" in code and "0.225f" in code, "TemplateMatcher.kt missing 0.130f..0.225f indicator range!"
        assert "fun findActiveMainTabBarY" in code, "TemplateMatcher.kt missing findActiveMainTabBarY!"

    def test_mission_task_dynamic_discovery_invariants(self):
        path = os.path.join(APP_SRC_MAIN, "tasks", "MissionTask.kt")
        with open(path, "r", encoding="utf-8") as f:
            code = f.read()

        assert "fun getAvailableTabs" in code, "MissionTask.kt missing getAvailableTabs method!"
        assert "0.12f..0.28f" in code, "MissionTask.kt missing 0.12f..0.28f tab scan range!"

    def test_friend_gift_task_safe_fallback_invariants(self):
        path = os.path.join(APP_SRC_MAIN, "tasks", "FriendGiftTask.kt")
        with open(path, "r", encoding="utf-8") as f:
            code = f.read()

        # R6: Must anchor at 0.910f
        assert "0.910f" in code, "FriendGiftTask.kt missing 0.910f safe anchor!"
        # R6: Old dangerous 0.975f must be eliminated
        assert "0.975f" not in code, "FriendGiftTask.kt still contains dangerous 0.975f extending into nav bar!"
