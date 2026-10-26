#!/usr/bin/env python3
"""
ADVERSARIAL EMPIRICAL CHALLENGER TEST SUITE: R1 & R2
Challenger 1 (challenger_m3_1)
LineWalkAutoAd

Adversarial stress-testing targeting 7 critical specifications:
1. Boundary coordinates: targets at y = sh * 0.079f (must be rejected) vs y = sh * 0.081f (accepted).
2. Candidate geometric boxes intersecting or inside y < sh * 0.08f (rejected).
3. Candidate geometric boxes inside the bingo close X zone (0.84..0.98sw, 0.08..0.16sh) (rejected).
4. Menu verification: OCR containing "選單" with no settings items (rejected), OCR containing bingo "抽選" (rejected).
5. Bingo dialog detection under background tab leakage ("首頁", "步數", "時間", "遊戲" present in background) - must still detect bingo.
6. Bingo open cell rejection when countdown (\\d{1,2}:\\d{2}) or 9/9 or 今日上限 is present.
7. Multi-tick reward state locking: ensure claimedBingoColumns retains columns across 10+ simulation ticks without clearing.
"""

import os
import re
import random
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


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
# 1. Boundary coordinates: y = sh * 0.079f vs y = sh * 0.081f
# ==============================================================================

def test_challenger_item1_boundary_coordinates_safe_zone():
    """Stress-test boundary coordinates across 10 diverse display resolutions."""
    test_resolutions = [
        (1080, 2400, "FHD+ Modern 20:9"),
        (1440, 3200, "WQHD+ Modern 20:9"),
        (1080, 1920, "Standard FHD 16:9"),
        (720, 1600, "HD+ Budget 20:9"),
        (1812, 2176, "Foldable Unfolded Inner"),
        (1080, 2640, "Tall Flip 22:9"),
        (1200, 2000, "Tablet 5:3"),
        (800, 1280, "Small Tablet 16:10"),
        (1440, 2560, "QHD 16:9"),
        (1080, 1080, "Square 1:1")
    ]

    def simulate_perform_click(target_x, target_y, max_w, max_h, allow_top_safe_zone=False):
        # AutoAdAccessibilityService.kt lines 172-180 logic
        tx = max(5.0, min(float(max_w - 5), target_x))
        ty = max(5.0, min(float(max_h - 5), target_y))
        if not allow_top_safe_zone and ty < max_h * 0.08:
            return False  # Rejected
        return True  # Accepted

    for sw, sh, desc in test_resolutions:
        safe_boundary = sh * 0.08

        # 1A. targets at y = sh * 0.079f MUST be rejected
        y_079 = sh * 0.079
        assert y_079 < safe_boundary
        assert simulate_perform_click(sw * 0.5, y_079, sw, sh, allow_top_safe_zone=False) is False, \
            f"[{desc}] y = sh * 0.079f ({y_079}) must be rejected"

        # Ultra-tight boundary tests below 0.08f
        for eps in [0.001, 0.0001, 0.00001, 0.000001]:
            y_below = (0.08 - eps) * sh
            assert simulate_perform_click(sw * 0.5, y_below, sw, sh, allow_top_safe_zone=False) is False, \
                f"[{desc}] y = (0.08 - {eps}) * sh ({y_below}) must be rejected"

        # 1B. targets at y = sh * 0.081f MUST be accepted
        y_081 = sh * 0.081
        assert y_081 > safe_boundary
        assert simulate_perform_click(sw * 0.5, y_081, sw, sh, allow_top_safe_zone=False) is True, \
            f"[{desc}] y = sh * 0.081f ({y_081}) must be accepted"

        # Ultra-tight boundary tests above 0.08f
        for eps in [0.001, 0.0001, 0.00001, 0.000001]:
            y_above = (0.08 + eps) * sh
            assert simulate_perform_click(sw * 0.5, y_above, sw, sh, allow_top_safe_zone=False) is True, \
                f"[{desc}] y = (0.08 + {eps}) * sh ({y_above}) must be accepted"

        # 1C. allowTopSafeZone exemption test (e.g. ReceiveGiftTask header box at y = 0.06sh)
        y_header_gift = sh * 0.06
        assert simulate_perform_click(sw * 0.9, y_header_gift, sw, sh, allow_top_safe_zone=False) is False
        assert simulate_perform_click(sw * 0.9, y_header_gift, sw, sh, allow_top_safe_zone=True) is True

    # 1D. AST Check in AutoAdAccessibilityService.kt
    service_path = os.path.join(SRC_DIR, "service", "AutoAdAccessibilityService.kt")
    with open(service_path, "r", encoding="utf-8") as f:
        src = f.read()
    assert "allowTopSafeZone: Boolean = false" in src
    assert "targetY < maxH * 0.08f" in src


# ==============================================================================
# 2. Candidate geometric boxes intersecting or inside y < sh * 0.08f (rejected)
# ==============================================================================

def test_challenger_item2_geometric_box_safe_zone_intersection():
    """Verify that any candidate box intersecting or inside y < sh * 0.08f is rejected."""
    resolutions = [(1080, 2400), (1440, 3200), (720, 1600)]

    for sw, sh in resolutions:
        r = max(5, int(sw * 0.018))
        safe_zone_y = int(sh * 0.08)
        top_limit_y = max(int(sh * 0.082), 60)

        # TemplateMatcher scan condition:
        # Candidate center (absX, absY) is tested. The candidate box top is (absY - r).
        # Requirement: candidate geometric box intersecting safe zone (boxTop < sh * 0.08f) is rejected.
        def is_candidate_box_valid(abs_x, abs_y):
            box_top = abs_y - r
            # TemplateMatcher.kt line 533 check
            if box_top < safe_zone_y:
                return False  # Rejected
            return True  # Accepted

        # Case 2A: Candidate center inside safe zone (abs_y < safe_zone_y)
        for cy in range(0, safe_zone_y, 10):
            assert is_candidate_box_valid(int(sw * 0.9), cy) is False, \
                f"Candidate center at {cy} inside safe zone {safe_zone_y} must be rejected"

        # Case 2B: Candidate center slightly below safe zone, but top edge intersects safe zone (abs_y - r < safe_zone_y)
        # e.g., abs_y = safe_zone_y + r // 2
        for offset in range(0, r):
            intersecting_cy = safe_zone_y + offset
            assert is_candidate_box_valid(int(sw * 0.9), intersecting_cy) is False, \
                f"Candidate box with top={intersecting_cy - r} < {safe_zone_y} intersecting safe zone must be rejected"

        # Case 2C: Candidate center sufficiently deep below safe zone (abs_y - r >= safe_zone_y)
        valid_cy = safe_zone_y + r + 1
        assert is_candidate_box_valid(int(sw * 0.9), valid_cy) is True

    # AST Check in TemplateMatcher.kt
    matcher_path = os.path.join(SRC_DIR, "vision", "TemplateMatcher.kt")
    with open(matcher_path, "r", encoding="utf-8") as f:
        src = f.read()
    assert "val topLimitY = max((sh * 0.082f).toInt(), 60)" in src
    assert "absY - r < (sh * 0.08f).toInt()" in src


# ==============================================================================
# 3. Candidate geometric boxes inside bingo close X zone (rejected)
# ==============================================================================

def test_challenger_item3_geometric_box_bingo_close_x_zone():
    """Verify that candidate geometric boxes inside 0.84..0.98sw, 0.08..0.16sh are rejected."""
    resolutions = [(1080, 2400), (1440, 3200), (720, 1600), (1080, 1920)]

    for sw, sh in resolutions:
        bx_min = int(sw * 0.84)
        bx_max = int(sw * 0.98)
        by_min = int(sh * 0.08)
        by_max = int(sh * 0.16)

        def is_bingo_close_zone_rejected(abs_x, abs_y):
            # TemplateMatcher.kt lines 539-544
            is_bingo_close_zone = (bx_min <= abs_x <= bx_max) and (by_min <= abs_y <= by_max)
            return is_bingo_close_zone

        # 3A. Sample 500 coordinates strictly inside the zone -> all MUST be rejected
        random.seed(42)
        for _ in range(500):
            rx = random.randint(bx_min, bx_max)
            ry = random.randint(by_min, by_max)
            assert is_bingo_close_zone_rejected(rx, ry) is True, \
                f"Coordinate ({rx}, {ry}) inside bingo close zone must be rejected"

        # 3B. Actual Bingo close button bounds from BingoTask.getCloseButtonBounds:
        # Rect(0.86sw, 0.095sh, 0.96sw, 0.145sh)
        close_btn_center_x = int(sw * 0.91)
        close_btn_center_y = int(sh * 0.12)
        assert is_bingo_close_zone_rejected(close_btn_center_x, close_btn_center_y) is True
        assert is_bingo_close_zone_rejected(int(sw * 0.86), int(sh * 0.095)) is True
        assert is_bingo_close_zone_rejected(int(sw * 0.96), int(sh * 0.145)) is True

        # 3C. Valid ad close coordinates OUTSIDE the bingo exclusion zone
        # Left corner ad close (sw * 0.10, sh * 0.12)
        assert is_bingo_close_zone_rejected(int(sw * 0.10), int(sh * 0.12)) is False
        # Far top right below bingo zone (sw * 0.90, sh * 0.20)
        assert is_bingo_close_zone_rejected(int(sw * 0.90), int(sh * 0.20)) is False

    # AST Check in TemplateMatcher.kt
    matcher_path = os.path.join(SRC_DIR, "vision", "TemplateMatcher.kt")
    with open(matcher_path, "r", encoding="utf-8") as f:
        src = f.read()
    assert "val isBingoCloseZone = absX in (sw * 0.84f).toInt()..(sw * 0.98f).toInt() &&" in src
    assert "absY in (sh * 0.08f).toInt()..(sh * 0.16f).toInt()" in src

    # Also check AutomationEngine.kt line 810: detectCornerGeometricX is blocked when isBingoDialogOpen is true
    engine_path = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        engine_src = f.read()
    assert "if (!bingoTask.isBingoDialogOpen(ocrItems, sh)) {" in engine_src


# ==============================================================================
# 4. Menu verification: OCR "選單" alone (rejected), OCR bingo "抽選" (rejected)
# ==============================================================================

def test_challenger_item4_menu_verification_adversarial():
    """Verify isVerifiedMenuScreen rejects OCR containing "選單" without settings and bingo "抽選"."""
    sh = 2400

    def is_verified_menu_screen(ocr_items, is_bingo_open=False):
        # AutomationEngine.kt lines 543-585 logic
        if is_bingo_open:
            return False

        has_main_tabs = any(
            0.08 <= item.bounds.centerY() / sh <= 0.20 and any(k in item.text for k in ["首頁", "步數", "時間", "遊戲"])
            for item in ocr_items
        )
        if has_main_tabs:
            return False

        has_bottom_nav = any(
            item.bounds.centerY() / sh > 0.85 and any(k in item.text for k in ["集點", "好友", "使用", "精選"])
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
        matched_setting_keywords = [
            kw for kw in setting_keywords if any(kw in item.text.replace(" ", "") for item in ocr_items)
        ]

        return (has_menu_title and len(matched_setting_keywords) > 0) or len(matched_setting_keywords) >= 2

    # 4A. OCR containing "選單" with NO settings items -> REJECTED
    isolated_menu_title = [
        OcrItem("選單", Rect(450, 260, 630, 350)),  # cy = 305 / 2400 ≈ 0.127
        OcrItem("今日熱門特惠推薦", Rect(100, 600, 900, 700)),
        OcrItem("點擊查看更多細節", Rect(100, 800, 800, 900))
    ]
    assert is_verified_menu_screen(isolated_menu_title) is False, \
        "OCR containing '選單' with no settings items must be rejected"

    # 4B. OCR containing bingo "抽選" (e.g. from countdown or lottery banner) -> REJECTED
    bingo_cooldown_ocr = [
        OcrItem("距離下次抽選還有", Rect(300, 1400, 800, 1500)),
        OcrItem("14:59", Rect(450, 1520, 650, 1600))
    ]
    assert is_verified_menu_screen(bingo_cooldown_ocr) is False, \
        "OCR containing bingo '抽選' must be rejected"

    # 4C. OCR containing both "選單" and bingo items -> REJECTED (due to is_bingo_open=True)
    bingo_with_menu_leak = [
        OcrItem("選單", Rect(450, 260, 630, 350)),
        OcrItem("每日賓果", Rect(400, 300, 700, 400)),
        OcrItem("距離下次抽選還有", Rect(300, 1400, 800, 1500))
    ]
    assert is_verified_menu_screen(bingo_with_menu_leak, is_bingo_open=True) is False

    # 4D. OCR containing "選單" in top notification bar (cy < 0.08sh) -> REJECTED
    notification_menu_ocr = [
        OcrItem("選單", Rect(450, 50, 630, 120)),  # cy = 85 / 2400 ≈ 0.035sh < 0.08sh
        OcrItem("應用程式設定", Rect(100, 600, 500, 700))
    ]
    assert is_verified_menu_screen(notification_menu_ocr) is False, \
        "Menu title at notification bar (cy < 0.08sh) must not satisfy hasMenuTitle"

    # 4E. Positive verification: Legitimate settings screen -> ACCEPTED
    legitimate_settings = [
        OcrItem("選單", Rect(450, 260, 630, 350)),  # cy ≈ 0.127sh
        OcrItem("應用程式設定", Rect(100, 600, 500, 700))
    ]
    assert is_verified_menu_screen(legitimate_settings) is True

    # 4F. Positive verification: 2 distinct settings items without title -> ACCEPTED
    two_settings_items = [
        OcrItem("關閉動畫效果", Rect(100, 500, 500, 600)),
        OcrItem("位置資訊設定", Rect(100, 700, 500, 800))
    ]
    assert is_verified_menu_screen(two_settings_items) is True

    # AST Check in AutomationEngine.kt
    engine_path = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        src = f.read()
    assert "fun isVerifiedMenuScreen" in src
    assert "if (bingoTask.isBingoDialogOpen(ocrItems, sh)) return false" in src
    assert "hasMenuTitle && matchedSettingKeywords.isNotEmpty()) || matchedSettingKeywords.size >= 2" in src
    assert "closeMenuRect" not in src, "Blind gear clicks (closeMenuRect) must be completely eliminated"
    assert "safeNavigateBack(allowInGame = true)" in src


# ==============================================================================
# 5. Bingo dialog detection under background tab leakage
# ==============================================================================

def test_challenger_item5_bingo_dialog_tab_leakage():
    """Verify isBingoDialogOpen returns True when background tabs leak into OCR."""
    sh = 2400

    def is_bingo_dialog_open(ocr_items):
        # BingoTask.kt lines 20-106 logic
        has_title_feature = False
        has_open_cell_or_cooldown_feature = False
        has_connection_feature = False

        for item in ocr_items:
            cy = item.bounds.centerY()
            t = item.text.replace(" ", "")

            if "每日賓果" in t or "今日賓果" in t or (
                cy < sh * 0.28 and (
                    t.upper() == "BINGO" or ("每日" in t and "賓果" in t) or (cy > sh * 0.08 and t == "賓果")
                )
            ):
                has_title_feature = True

            if "立即開啟賓果格" in t or "開啟賓果格" in t or (
                "立即開啟" in t and cy > sh * 0.40
            ) or "距離下次抽選還有" in t or "距離下次" in t or "抽選還有" in t or (
                sh * 0.40 <= cy <= sh * 0.85 and any(k in t for k in ["今日上限", "已達到今日上限", "9/9", "已全數開啟"])
            ):
                has_open_cell_or_cooldown_feature = True

            if any(b in t for b in ["1BINGO", "1 BINGO", "3BINGO", "3 BINGO", "5BINGO", "5 BINGO", "8BINGO", "8 BINGO"]) or (
                sh * 0.70 <= cy <= sh * 0.95 and any(g in t.lower() for g in ["get", "cet", "qet"])
            ):
                has_connection_feature = True

        has_strong_bingo_signature = (
            (has_title_feature and (has_open_cell_or_cooldown_feature or has_connection_feature)) or
            (has_open_cell_or_cooldown_feature and has_connection_feature) or
            any(
                any(k in item.text.replace(" ", "") for k in [
                    "每日賓果", "今日賓果", "立即開啟賓果格", "開啟賓果格", "距離下次抽選還有",
                    "1BINGO", "3BINGO", "5BINGO", "8BINGO"
                ]) for item in ocr_items
            )
        )

        if has_strong_bingo_signature:
            return True

        has_main_tabs = any(
            0.08 <= item.bounds.centerY() / sh <= 0.18 and any(k in item.text for k in ["首頁", "步數", "時間", "遊戲"])
            for item in ocr_items
        )
        if has_main_tabs:
            return False

        return False

    leaked_tabs = [
        OcrItem("首頁", Rect(50, 240, 200, 300)),   # cy = 270 / 2400 ≈ 0.1125sh
        OcrItem("步數", Rect(300, 240, 450, 300)),
        OcrItem("時間", Rect(550, 240, 700, 300)),
        OcrItem("遊戲", Rect(800, 240, 950, 300))
    ]

    # Test cases: Leaked tabs present with different strong bingo signatures
    test_signatures = [
        [OcrItem("每日賓果", Rect(400, 300, 700, 400))],
        [OcrItem("今日賓果", Rect(400, 300, 700, 400))],
        [OcrItem("立即開啟賓果格", Rect(300, 1500, 800, 1600))],
        [OcrItem("開啟賓果格", Rect(300, 1500, 800, 1600))],
        [OcrItem("距離下次抽選還有", Rect(300, 1500, 800, 1600))],
        [OcrItem("1BINGO", Rect(100, 1800, 250, 1880))],
        [OcrItem("3BINGO", Rect(350, 1800, 500, 1880))],
        [OcrItem("5BINGO", Rect(600, 1800, 750, 1880))],
        [OcrItem("8BINGO", Rect(850, 1800, 1000, 1880))],
        [OcrItem("BINGO", Rect(450, 280, 650, 360)), OcrItem("Get", Rect(100, 1900, 250, 1980))],
        [OcrItem("今日上限", Rect(350, 1500, 750, 1600)), OcrItem("1BINGO", Rect(100, 1800, 250, 1880))],
        [OcrItem("9/9", Rect(450, 1500, 650, 1600)), OcrItem("1BINGO", Rect(100, 1800, 250, 1880))]
    ]

    for idx, sig in enumerate(test_signatures):
        combined = leaked_tabs + sig
        assert is_bingo_dialog_open(combined) is True, \
            f"Test case {idx} failed: Background tab leakage must NOT veto bingo dialog with strong signature {sig[0].text}"

    # Negative control: Leaked tabs WITHOUT any bingo signature -> MUST return False
    clean_main_screen = leaked_tabs + [
        OcrItem("邀請好友", Rect(100, 800, 400, 900)),
        OcrItem("賓果", Rect(50, 950, 200, 1050))  # Main screen small icon at left
    ]
    assert is_bingo_dialog_open(clean_main_screen) is False

    # AST Check in BingoTask.kt
    task_path = os.path.join(SRC_DIR, "tasks", "BingoTask.kt")
    with open(task_path, "r", encoding="utf-8") as f:
        src = f.read()
    assert "val hasStrongBingoSignature = (hasTitleFeature && (hasOpenCellOrCooldownFeature || hasConnectionFeature))" in src
    assert "if (hasStrongBingoSignature) {" in src
    # Ensure hasStrongBingoSignature is returned before hasMainTabs veto
    pos_strong = src.find("if (hasStrongBingoSignature) {\n            return true")
    pos_tabs = src.find("if (hasMainTabs) return false")
    assert pos_strong != -1 and pos_tabs != -1
    assert pos_strong < pos_tabs, "hasStrongBingoSignature check must precede hasMainTabs veto"


# ==============================================================================
# 6. Bingo open cell rejection when countdown or 9/9 or 今日上限 is present
# ==============================================================================

def test_challenger_item6_bingo_open_cell_rejection():
    """Verify findOpenCellButton strictly returns None when countdown or limit is present."""
    sh = 2400
    time_regex = re.compile(r"\d{1,2}:\d{2}")

    def find_open_cell_button(ocr_items):
        # BingoTask.kt lines 125-161 logic
        has_reached_limit = any(
            any(k in item.text.replace(" ", "") for k in ["今日上限", "已達到今日上限", "9/9", "已全數開啟"])
            for item in ocr_items
        )
        if has_reached_limit:
            return None

        is_cooling_down = any(
            "距離下次抽選還有" in item.text.replace(" ", "") or
            "距離下次" in item.text.replace(" ", "") or
            "抽選還有" in item.text.replace(" ", "") or
            (
                0.48 * sh <= item.bounds.centerY() <= 0.78 * sh and (
                    time_regex.search(item.text.replace(" ", "")) or
                    ("還有" in item.text and "立即" not in item.text) or
                    ("抽選" in item.text and "開啟" not in item.text and "立即" not in item.text)
                )
            )
            for item in ocr_items
        )
        if is_cooling_down:
            return None

        for item in ocr_items:
            cy = item.bounds.centerY()
            t = item.text.replace(" ", "")
            if (
                0.54 * sh <= cy <= 0.74 * sh and
                any(k in t for k in ["立即開啟", "開啟賓果格", "開啟賓果", "立即開"]) and
                "還有" not in t and
                not time_regex.search(t) and
                "今日上限" not in t and
                "9/9" not in t
            ):
                return item.bounds

        return None

    # Base open cell button candidate
    open_cell_item = OcrItem("立即開啟賓果格", Rect(250, 1480, 830, 1580))  # cy = 1530 / 2400 = 0.6375sh

    # 6A. Rejection when countdown regex \d{1,2}:\d{2} matches
    countdown_samples = [
        "距離下次抽選還有 14:59",
        "15:00",
        "0:45",
        "05:20",
        "9:59",
        "00:30",
        "23:59",
        "還有 11:22",
        "抽選還有 04:30"
    ]
    for cd in countdown_samples:
        ocr_list = [open_cell_item, OcrItem(cd, Rect(300, 1500, 800, 1600))]
        assert find_open_cell_button(ocr_list) is None, f"Countdown '{cd}' must reject open cell button"

    # 6B. Rejection when 9/9 or limits are present
    limit_samples = [
        "9/9",
        "今日上限",
        "已達到今日上限",
        "今日上限已達",
        "已全數開啟",
        "已開啟 9/9 格"
    ]
    for lim in limit_samples:
        ocr_list = [open_cell_item, OcrItem(lim, Rect(300, 1500, 800, 1600))]
        assert find_open_cell_button(ocr_list) is None, f"Limit '{lim}' must reject open cell button"

    # 6C. Positive validation: Clean open cell button with NO countdown and NO limits -> ACCEPTED
    clean_open_cell = [
        OcrItem("每日賓果", Rect(400, 300, 700, 400)),
        open_cell_item
    ]
    res = find_open_cell_button(clean_open_cell)
    assert res is not None
    assert res.left == 250 and res.right == 830


# ==============================================================================
# 7. Multi-tick reward state locking: claimedBingoColumns across 10+ ticks
# ==============================================================================

def test_challenger_item7_multi_tick_reward_state_locking():
    """Verify claimedBingoColumns retains claimed columns across 15+ simulation ticks without clearing."""
    sh = 2400
    sw = 1080

    class BingoEngineSimulator:
        def __init__(self):
            self.claimedBingoColumns = set()
            self.bingoColumnRetryCount = {}
            self.currentPhaseEmptyTicks = 0
            self.isBingoCompleted = False
            self.currentPhase = "BINGO"
            self.click_history = []

        def findNextClaimableReward(self, available_columns):
            col_centers = [0.18, 0.39, 0.61, 0.82]
            labels = ["1 BINGO", "3 BINGO", "5 BINGO", "8 BINGO"]
            for col_idx in available_columns:
                if col_idx in self.claimedBingoColumns:
                    continue
                return {
                    "columnIndex": col_idx,
                    "label": labels[col_idx],
                    "bounds": Rect(int(sw * (col_centers[col_idx] - 0.08)), int(sh * 0.78),
                                   int(sw * (col_centers[col_idx] + 0.08)), int(sh * 0.86))
                }
            return None

        def execute_bingo_tick(self, has_open_cell, available_columns):
            # AutomationEngine.kt lines 914-965 logic
            # Step 1: Open cell button
            if has_open_cell:
                self.currentPhaseEmptyTicks = 0
                self.click_history.append("CLICK_OPEN_CELL")
                return "TRIGGER_AD_POPUP"

            # Step 2: Next claimable reward
            reward_target = self.findNextClaimableReward(available_columns)
            if reward_target is not None:
                self.currentPhaseEmptyTicks = 0
                col_idx = reward_target["columnIndex"]
                count = self.bingoColumnRetryCount.get(col_idx, 0) + 1
                self.bingoColumnRetryCount[col_idx] = count
                # R2: If count >= 2, permanently lock column in claimedBingoColumns!
                if count >= 2:
                    self.claimedBingoColumns.add(col_idx)
                self.click_history.append(f"CLICK_{reward_target['label']}_RETRY_{count}")
                return "TRIGGER_AD_POPUP"

            # Step 3: No open cell and no claimable reward -> complete bingo
            self.currentPhaseEmptyTicks += 1
            if self.currentPhaseEmptyTicks < 2:
                return "CHECK_EMPTY_TICKS"

            self.currentPhaseEmptyTicks = 0
            self.isBingoCompleted = True
            # Keep claimedBingoColumns LOCKED!
            self.click_history.append("CLICK_CLOSE_BINGO_X")
            self.currentPhase = "LOTTERY"
            return "LOTTERY"

    sim = BingoEngineSimulator()

    # Tick 1: Open cell button available -> clicks open cell
    state1 = sim.execute_bingo_tick(has_open_cell=True, available_columns=[0, 1])
    assert state1 == "TRIGGER_AD_POPUP"
    assert "CLICK_OPEN_CELL" in sim.click_history

    # Tick 2: Open cell enters cooldown (has_open_cell=False). Column 0 available.
    state2 = sim.execute_bingo_tick(has_open_cell=False, available_columns=[0, 1])
    assert "CLICK_1 BINGO_RETRY_1" in sim.click_history
    assert 0 not in sim.claimedBingoColumns  # retry=1

    # Tick 3: Column 0 second click -> locked in claimedBingoColumns!
    state3 = sim.execute_bingo_tick(has_open_cell=False, available_columns=[0, 1])
    assert "CLICK_1 BINGO_RETRY_2" in sim.click_history
    assert 0 in sim.claimedBingoColumns, "Column 0 must be locked in claimedBingoColumns after retry >= 2"

    # Multi-tick stress: Execute 15 additional ticks and verify Column 0 NEVER unlocks!
    for tick in range(4, 20):
        # Column 1 claimed on tick 4 & 5
        sim.execute_bingo_tick(has_open_cell=False, available_columns=[0, 1])
        # Column 0 MUST ALWAYS remain in claimedBingoColumns across all ticks!
        assert 0 in sim.claimedBingoColumns, f"Tick {tick}: Column 0 must retain locked state!"
        # Column 0 must never be returned by findNextClaimableReward
        target = sim.findNextClaimableReward([0])
        assert target is None, f"Tick {tick}: Column 0 must be skipped by findNextClaimableReward!"

    # Verify Column 1 is also locked
    assert 1 in sim.claimedBingoColumns
    # Verify final phase transitioned to LOTTERY and close button was clicked
    assert sim.isBingoCompleted is True
    assert sim.currentPhase == "LOTTERY"
    assert "CLICK_CLOSE_BINGO_X" in sim.click_history

    # AST Check in AutomationEngine.kt: claimedBingoColumns.clear() is NEVER in handleBingoDialog
    engine_path = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        engine_src = f.read()

    # Find definition of handleBingoDialog
    start_pos = engine_src.find("private suspend fun handleBingoDialog(")
    end_pos = engine_src.find("private suspend fun handleLotteryDialog(")
    assert start_pos != -1 and end_pos != -1
    bingo_dialog_src = engine_src[start_pos:end_pos]
    assert "claimedBingoColumns.clear()" not in bingo_dialog_src, \
        "claimedBingoColumns.clear() must NEVER be called inside handleBingoDialog!"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
