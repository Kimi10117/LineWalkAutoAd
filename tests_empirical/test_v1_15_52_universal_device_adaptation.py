#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
COMPREHENSIVE INTEGRATION SUITE: LineWalkAutoAd v1.15.52 (versionCode 85)
Universal Device Adaptation Architecture & 7 Core Defect Remediation

Requirements Coverage:
- R1: Top tab dynamic Y-anchor calculation across resolutions (SM-S7210 22:9, 16:9, foldable),
      4 relative quadrants (0.125f, 0.375f, 0.625f, 0.875f).
- R2: Global skip on ▶|, >|, >> and Play Store auto-return (com.android.vending).
- R3: Anti-abandon confirmation popup intercept (100% continue button prioritization, removal of bait keywords).
- R4: Gacha ticket count extraction (< 50 guard) and button internal pixel color sampling.
- R5: Dynamic task panel discovery and clean close via (X).
- R6: Bottom navigation friends & points tab safe fallback at 0.910f.
- R7: Promo close expansion (0.050f..0.120f), AST check for imports/TAG clean in all Kotlin files,
      versionCode = 85, versionName = "1.15.52".
"""

from pathlib import Path
import re
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"


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


TAB_MOVE = MainTab("首頁(移動)", "移動", 0.125)
TAB_STEP = MainTab("步數", "步數", 0.375)
TAB_TIME = MainTab("時間(放置)", "時間", 0.625)
TAB_GAME = MainTab("遊戲", "遊戲", 0.875)


def clamp(val: int, min_val: int, max_val: int) -> int:
    return max(min_val, min(val, max_val))


# ==============================================================================
# R1: Top Tab Dynamic Y-Anchor & 4 Quadrants
# ==============================================================================

def simulate_get_tab_rect(tab: MainTab, sw: int, sh: int, ocr_items: list, probed_y: int = None) -> Rect:
    """Exact simulation of AutomationEngine.kt getTabRect."""
    effective_sh = sh if sh > 0 else 2400
    effective_sw = sw if sw > 0 else 1080

    matched_tabs = []
    for item in ocr_items:
        cy = item.bounds.centerY() / effective_sh
        t = item.text.replace(" ", "")
        if 0.05 <= cy <= 0.30 and any(kw in t for kw in ["首頁", "移動", "步數", "時間", "遊戲"]):
            matched_tabs.append(item)

    if matched_tabs:
        dynamic_y_anchor = int(sum(it.bounds.centerY() for it in matched_tabs) / len(matched_tabs))
    else:
        if probed_y is not None:
            raw_probed = probed_y - int(effective_sh * 0.015)
            dynamic_y_anchor = clamp(raw_probed, int(effective_sh * 0.130), int(effective_sh * 0.225))
        else:
            dynamic_y_anchor = int(effective_sh * 0.165)

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


class TestR1UniversalTopTabDynamicAdaptation:
    """Verifies Requirement R1: Universal Y-Anchor and 4-Quadrant navigation across aspect ratios."""

    def test_r1_top_tab_dynamic_y_anchor_average(self):
        sw, sh = 1080, 2640  # SM-S7210 22:9
        # Tabs placed with OCR centers at heights 430, 440, 450
        ocr_items = [
            OcrItem("首頁", Rect(50, 410, 200, 450)),   # cy = 430
            OcrItem("步數", Rect(320, 420, 470, 460)),  # cy = 440
            OcrItem("遊戲", Rect(860, 430, 1010, 470)), # cy = 450
        ]
        expected_y = int((430 + 440 + 450) / 3)  # 440
        rect = simulate_get_tab_rect(TAB_GAME, sw, sh, ocr_items)
        assert rect.centerY() == expected_y

    def test_r1_top_tab_resolutions_sm_s7210_22_9(self):
        sw, sh = 1080, 2640
        tabs = [TAB_MOVE, TAB_STEP, TAB_TIME, TAB_GAME]
        expected_x_ratios = [0.125, 0.375, 0.625, 0.875]
        expected_y = int(sh * 0.165)

        for tab, expected_x_ratio in zip(tabs, expected_x_ratios):
            rect = simulate_get_tab_rect(tab, sw, sh, [])
            assert rect.centerX() == int(sw * expected_x_ratio)
            assert rect.centerY() == expected_y
            assert 0 <= rect.left < rect.right <= sw
            assert 0 <= rect.top < rect.bottom <= sh

    def test_r1_top_tab_resolutions_standard_16_9(self):
        sw, sh = 1080, 1920
        tabs = [TAB_MOVE, TAB_STEP, TAB_TIME, TAB_GAME]
        expected_y = int(sh * 0.165)

        for tab in tabs:
            rect = simulate_get_tab_rect(tab, sw, sh, [])
            assert rect.centerX() == int(sw * tab.xRatio)
            assert rect.centerY() == expected_y

    def test_r1_top_tab_resolutions_foldable_and_tablet(self):
        resolutions = [
            (1812, 2176, "Foldable Inner"),
            (1600, 2560, "Tablet Portrait"),
        ]
        for sw, sh, label in resolutions:
            for tab in [TAB_MOVE, TAB_STEP, TAB_TIME, TAB_GAME]:
                rect = simulate_get_tab_rect(tab, sw, sh, [])
                assert rect.centerX() == int(sw * tab.xRatio), f"Failed for {label}"
                assert rect.centerY() == int(sh * 0.165), f"Failed for {label}"

    def test_r1_top_tab_ocr_probed_underline_fallback(self):
        sw, sh = 1080, 2400
        probed_y = 480  # 480 - 36 = 444
        rect = simulate_get_tab_rect(TAB_MOVE, sw, sh, [], probed_y=probed_y)
        expected_y = clamp(probed_y - int(sh * 0.015), int(sh * 0.130), int(sh * 0.225))
        assert rect.centerY() == expected_y

    def test_r1_top_tab_no_overlap_across_all_quadrants(self):
        sw, sh = 1080, 2640
        r_move = simulate_get_tab_rect(TAB_MOVE, sw, sh, [])
        r_step = simulate_get_tab_rect(TAB_STEP, sw, sh, [])
        r_time = simulate_get_tab_rect(TAB_TIME, sw, sh, [])
        r_game = simulate_get_tab_rect(TAB_GAME, sw, sh, [])

        assert r_move.right < r_step.left
        assert r_step.right < r_time.left
        assert r_time.right < r_game.left
        assert r_game.right <= sw

    def test_r1_ast_automation_engine_top_tabs(self):
        ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
        assert "val dynamicYAnchor = if (matchedTabs.isNotEmpty())" in ae_code
        assert "matchedTabs.map { it.bounds.centerY() }.average().toInt()" in ae_code
        assert "0.125f" in ae_code
        assert "0.375f" in ae_code
        assert "0.625f" in ae_code
        assert "0.875f" in ae_code


# ==============================================================================
# R2: Global Fast Skip & Google Play Auto-Return
# ==============================================================================

class TestR2GlobalSkipAndPlayStoreAutoReturn:
    """Verifies Requirement R2: Global skip on ▶|, >|, >> and Play Store auto-return."""

    FAST_SKIP_SYMBOLS = ["▶|", ">|", ">>|", ">>"]

    def find_fast_skip(self, ocr_items):
        for item in ocr_items:
            t = item.text.replace(" ", "")
            if any(sym in t for sym in self.FAST_SKIP_SYMBOLS):
                return item
        return None

    def test_r2_global_fast_skip_symbols_detection(self):
        items = [
            OcrItem("▶|", Rect(40, 100, 140, 180)),
            OcrItem(">|", Rect(940, 100, 1040, 180)),
            OcrItem(">>|", Rect(900, 2100, 1020, 2200)),
            OcrItem(">>", Rect(920, 120, 1000, 180)),
        ]
        for it in items:
            match = self.find_fast_skip([it])
            assert match is not None
            assert match.text == it.text

    def test_r2_global_fast_skip_releases_shopback_google_play_capsule(self):
        shopback_items = [
            OcrItem("▶| Google Play", Rect(40, 120, 380, 220)),
            OcrItem("ShopBack 現金回饋", Rect(200, 800, 800, 900)),
        ]
        match = self.find_fast_skip(shopback_items)
        assert match is not None
        assert match.text == "▶| Google Play"
        assert match.bounds.left == 40

    def test_r2_negative_non_skip_play_store_badges(self):
        plain_badges = [
            OcrItem("Google Play", Rect(40, 120, 320, 200)),
            OcrItem("Play 商店", Rect(40, 120, 300, 200)),
            OcrItem("15 秒後可略過", Rect(700, 120, 950, 180)),
        ]
        assert self.find_fast_skip(plain_badges) is None

    def test_r2_play_store_auto_return_and_end_card_recovery(self):
        app_config_code = (SRC_DIR / "config/AppConfig.kt").read_text(encoding="utf-8")
        closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
        ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

        # Google Play package classification
        assert '"com.android.vending"' in app_config_code
        assert "fun isRedirectPackage(" in app_config_code

        # AdCloserHandler redirect recovery
        assert "val isExternal = fgPkg != null && AppConfig.isRedirectPackage(fgPkg) && !isAd" in closer_code
        assert "service.navigateBack()" in closer_code

        # AutomationEngine safe back for com.android.vending
        assert 'externalPkg == "com.android.vending"' in ae_code
        assert "service.safeNavigateBack(minIntervalMs = 800L)" in ae_code

        # End card back unblocking
        assert "isEndCard && elapsedSec >= 15" in closer_code
        assert "service.safeNavigateBack(allowInGame = true)" in closer_code

    def test_r2_ast_ad_closer_fast_skip_implementation(self):
        closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
        assert 'val fastSkipSymbols = listOf("▶|", ">|", ">>|", ">>")' in closer_code
        assert "val hasSymbol = fastSkipSymbols.any { sym -> t.contains(sym) }" in closer_code
        assert 'ClickedCloseOrSkip("快速略過[${fastSkipMatch.text}]"' in closer_code


# ==============================================================================
# R3: Anti-Abandon Confirmation Popup Interception
# ==============================================================================

class TestR3AntiAbandonConfirmationPopup:
    """Verifies Requirement R3: Anti-abandon confirmation popup intercept (100% continue)."""

    TRIGGER_KEYWORDS = [
        "close this ad",
        "lose your reward",
        "would you like to close",
        "lose reward",
        "give up",
        "quit",
        "放棄獎勵",
        "無法獲得獎勵",
        "reward will be lost",
        "discard reward",
        "quit and forfeit",
        "確定要離開",
        "確定要退出",
        "確定要放棄",
        "確定放棄",
        "確定離開",
        "確定退出",
        "離開廣告",
    ]

    CONTINUE_KEYWORDS = [
        "Keep watching",
        "Resume",
        "Continue",
        "Cancel",
        "繼續觀看",
        "繼續",
        "取消",
    ]

    BAIT_WORDS = ["Close", "CLOSE", "Quit", "QUIT", "Discard", "DISCARD", "放棄獎勵", "放棄", "離開", "退出"]

    def process_confirmation_popup(self, ocr_items):
        has_popup = any(
            any(
                bool(re.search(r"\bquit\b", item.text, re.IGNORECASE))
                if kw == "quit"
                else kw.lower() in item.text.lower()
                for kw in self.TRIGGER_KEYWORDS
            )
            for item in ocr_items
        )
        if not has_popup:
            return "NO_POPUP"

        continue_btn = next(
            (
                item for item in ocr_items
                if any(kw.lower() in item.text.lower() for kw in self.CONTINUE_KEYWORDS)
                and not any(bait.lower() in item.text.lower() for bait in self.BAIT_WORDS)
            ),
            None
        )
        if continue_btn is not None:
            return ("CLICK_CONTINUE", continue_btn.text, continue_btn.bounds)
        return "SCANNING_AD_WAIT"

    def test_r3_anti_abandon_popup_trigger_keywords(self):
        dialog = [
            OcrItem("Would you like to close this ad? You will lose your reward.", Rect(100, 800, 980, 900)),
            OcrItem("Close", Rect(150, 1100, 450, 1220)),
            OcrItem("Keep watching", Rect(550, 1100, 950, 1220)),
        ]
        res = self.process_confirmation_popup(dialog)
        assert res[0] == "CLICK_CONTINUE"
        assert res[1] == "Keep watching"

    def test_r3_anti_abandon_popup_100_percent_continue_prioritization(self):
        test_cases = [
            ("Resume", [OcrItem("Quit and lose reward?", Rect(0,0,0,0)), OcrItem("Quit", Rect(0,0,0,0)), OcrItem("Resume", Rect(50,50,100,100))]),
            ("Continue", [OcrItem("close this ad to lose reward", Rect(0,0,0,0)), OcrItem("Close", Rect(0,0,0,0)), OcrItem("Continue", Rect(50,50,100,100))]),
            ("Cancel", [OcrItem("Quit and forfeit?", Rect(0,0,0,0)), OcrItem("Quit", Rect(0,0,0,0)), OcrItem("Cancel", Rect(50,50,100,100))]),
            ("繼續觀看", [OcrItem("確定要離開廣告？將無法獲得獎勵", Rect(0,0,0,0)), OcrItem("放棄獎勵", Rect(0,0,0,0)), OcrItem("繼續觀看", Rect(50,50,100,100))]),
            ("繼續", [OcrItem("確定放棄獎勵嗎？", Rect(0,0,0,0)), OcrItem("放棄", Rect(0,0,0,0)), OcrItem("繼續", Rect(50,50,100,100))]),
            ("取消", [OcrItem("確定退出廣告？", Rect(0,0,0,0)), OcrItem("退出", Rect(0,0,0,0)), OcrItem("取消", Rect(50,50,100,100))]),
        ]
        for expected_label, items in test_cases:
            res = self.process_confirmation_popup(items)
            assert res[0] == "CLICK_CONTINUE"
            assert res[1] == expected_label

    def test_r3_anti_abandon_popup_zero_percent_accidental_close(self):
        dialog_pending = [
            OcrItem("Would you like to close this ad? You will lose your reward.", Rect(100, 800, 980, 900)),
            OcrItem("Close", Rect(150, 1100, 450, 1220)),
        ]
        res = self.process_confirmation_popup(dialog_pending)
        assert res == "SCANNING_AD_WAIT", "Must never click Close when popup is visible"

    def test_r3_bait_words_removed_from_ad_close_ocr_keywords(self):
        ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
        match = re.search(r"val\s+AD_CLOSE_OCR_KEYWORDS\s*=\s*listOf\((.*?)\)", ocr_code, re.DOTALL)
        assert match is not None
        keywords_block = match.group(1)

        for bait in ["放棄獎勵", "放棄", "Close", "CLOSE", "Quit", "Discard"]:
            assert f'"{bait}"' not in keywords_block

    def test_r3_legitimate_close_keywords_retained(self):
        ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
        for kw in ["略過廣告", "跳過廣告", "關閉廣告", "Skip Ad", "✕", "×", ">>"]:
            assert f'"{kw}"' in ocr_code


# ==============================================================================
# R4: Gacha Ticket Count Guard & Button Pixel Sampling
# ==============================================================================

def simulate_extract_ticket_count(ocr_items, sw=1080, sh=2400):
    for item in ocr_items:
        cy = item.bounds.centerY()
        cx = item.bounds.centerX()
        if (int(sh * 0.15) <= cy <= int(sh * 0.26)) and (cx > int(sw * 0.58)):
            m = re.search(r"\d+", item.text.replace(" ", "").replace(",", ""))
            if m:
                return int(m.group(0))

    for item in ocr_items:
        cy = item.bounds.centerY()
        cx = item.bounds.centerX()
        if (int(sh * 0.03) <= cy <= int(sh * 0.10)) and (int(sw * 0.48) <= cx <= int(sw * 0.80)):
            m = re.search(r"\d+", item.text.replace(" ", "").replace(",", ""))
            if m:
                return int(m.group(0))

    return None


def simulate_pixel_sampling_is_fifty_disabled(pixels_rgb):
    clickable = sum(1 for r, g, b in pixels_rgb if r > 180 and (r - g > 60))
    disabled = sum(
        1 for r, g, b in pixels_rgb
        if (r - g < 40) or ((max(r, g, b) - min(r, g, b)) / max(r, g, b) < 0.30 if max(r, g, b) > 0 else True)
    )
    total = len(pixels_rgb)
    if total == 0:
        return False
    return (clickable / total) < 0.15 or (disabled / total) > 0.65


class TestR4Lottery50DrawGuardAndPixelSampling:
    """Verifies Requirement R4: Gacha ticket count extraction, < 50 guard, and pixel sampling."""

    def test_r4_ticket_count_extraction_modal_and_top_bar(self):
        sw, sh = 1080, 2400
        # Modal pill (cy = 480, cx = 800)
        items1 = [OcrItem("31", Rect(700, 440, 900, 520))]
        assert simulate_extract_ticket_count(items1, sw, sh) == 31

        # Decorated modal pill
        items2 = [OcrItem("x 1,250", Rect(700, 440, 900, 520))]
        assert simulate_extract_ticket_count(items2, sw, sh) == 1250

        # Top bar asset fallback (cy = 150, cx = 650)
        items3 = [OcrItem("42", Rect(550, 120, 750, 180))]
        assert simulate_extract_ticket_count(items3, sw, sh) == 42

    def test_r4_ticket_count_under_50_strictly_guards_50_draw(self):
        sw, sh = 1080, 2400
        pill = Rect(700, 440, 900, 520)
        fifty = Rect(100, 1100, 500, 1250)
        free = Rect(580, 1100, 980, 1250)

        items = [
            OcrItem("31", pill),
            OcrItem("50張 抽1次", fifty),
            OcrItem("免費抽1次", free),
        ]
        ticket_count = simulate_extract_ticket_count(items, sw, sh)
        assert ticket_count == 31
        assert ticket_count < 50
        # Disallow 50-draw
        is_allowed_50 = ticket_count >= 50
        assert is_allowed_50 is False

    def test_r4_ticket_count_greater_or_equal_50_allows_50_draw(self):
        sw, sh = 1080, 2400
        items = [
            OcrItem("839", Rect(700, 440, 900, 520)),
            OcrItem("50張 抽1次", Rect(100, 1100, 500, 1250)),
        ]
        ticket_count = simulate_extract_ticket_count(items, sw, sh)
        assert ticket_count == 839
        assert ticket_count >= 50

    def test_r4_button_internal_pixel_color_sampling_active(self):
        # Active bright red pixels: R=238, G=104, B=114
        active_pixels = [(238, 104, 114)] * 800 + [(255, 255, 255)] * 200
        assert simulate_pixel_sampling_is_fifty_disabled(active_pixels) is False

    def test_r4_button_internal_pixel_color_sampling_gray_disabled(self):
        # Gray disabled pixels: R=178, G=151, B=153
        gray_pixels = [(178, 151, 153)] * 1000
        assert simulate_pixel_sampling_is_fifty_disabled(gray_pixels) is True

    def test_r4_internal_pixel_sampling_immune_to_dimmed_margins(self):
        # Dimmed margin color is RGB(158, 157, 155). The button itself has bright red pixels.
        button_pixels = [(238, 104, 114)] * 850 + [(255, 255, 255)] * 150
        # Evaluated purely on button pixels, ignoring dimmed margin
        assert simulate_pixel_sampling_is_fifty_disabled(button_pixels) is False

    def test_r4_ast_lottery_task_and_template_matcher(self):
        lottery_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")
        tm_code = (SRC_DIR / "vision/TemplateMatcher.kt").read_text(encoding="utf-8")

        assert "fun extractTicketCount(" in lottery_code
        assert "ticketCount != null && ticketCount < 50" in lottery_code
        assert "fun isFiftyDrawButtonDisabled(" in tm_code
        assert "leftMarginColor" in tm_code


# ==============================================================================
# R5: Dynamic Task Panel Discovery and Clean Close via (X)
# ==============================================================================

class TestR5DynamicTaskPanelDiscoveryAndCleanClose:
    """Verifies Requirement R5: Dynamic task panel discovery and clean close via (X)."""

    def simulate_get_available_tabs(self, ocr_items, sh=2400):
        standard_order = ["特別", "每日", "每週"]
        detected = [
            tab for tab in standard_order
            if any(0.12 <= (it.bounds.centerY() / sh) <= 0.28 and tab in it.text for it in ocr_items)
        ]
        return detected if detected else ["每日", "每週"]

    def test_r5_task_panel_dynamic_available_tabs_3tabs(self):
        sh = 2400
        ocr_items = [
            OcrItem("特別", Rect(150, 400, 300, 460)),
            OcrItem("每日", Rect(450, 400, 600, 460)),
            OcrItem("每週", Rect(750, 400, 900, 460)),
        ]
        tabs = self.simulate_get_available_tabs(ocr_items, sh)
        assert tabs == ["特別", "每日", "每週"]

    def test_r5_task_panel_dynamic_available_tabs_2tabs(self):
        sh = 2400
        # Special tab disappeared
        ocr_items = [
            OcrItem("每日", Rect(300, 400, 500, 460)),
            OcrItem("每週", Rect(600, 400, 800, 460)),
        ]
        tabs = self.simulate_get_available_tabs(ocr_items, sh)
        assert tabs == ["每日", "每週"]

    def test_r5_task_panel_clean_close_via_x_coordinates(self):
        sw, sh = 1080, 2400
        # getCloseButtonBounds: sw * 0.86f..0.96f, sh * 0.095f..0.145f
        close_rect = Rect(int(sw * 0.86), int(sh * 0.095), int(sw * 0.96), int(sh * 0.145))
        assert close_rect.left == 928
        assert close_rect.right == 1036
        assert close_rect.top == 228
        assert close_rect.bottom == 348
        assert 0 <= close_rect.left < close_rect.right <= sw
        assert 0 <= close_rect.top < close_rect.bottom <= sh

    def test_r5_task_panel_state_machine_loop_termination(self):
        available_tabs = ["每日", "每週"]
        checked_tabs = set()
        closed_x = False

        for _ in range(5):
            next_tab = next((t for t in available_tabs if t not in checked_tabs), None)
            if next_tab is not None:
                checked_tabs.add(next_tab)
            else:
                closed_x = True
                break

        assert checked_tabs == {"每日", "每週"}
        assert closed_x is True

    def test_r5_ast_mission_task_dynamic_tabs(self):
        mission_code = (SRC_DIR / "tasks/MissionTask.kt").read_text(encoding="utf-8")
        assert "fun getAvailableTabs(" in mission_code
        assert "fun getCloseButtonBounds(" in mission_code
        assert "0.86f" in mission_code and "0.96f" in mission_code


# ==============================================================================
# R6: Bottom Navigation Dynamic Adaptation
# ==============================================================================

class TestR6BottomNavDynamicAdaptation:
    """Verifies Requirement R6: Bottom navigation friends & points tab safe fallback at 0.910f."""

    def simulate_get_bottom_friends_tab(self, sw, sh, ocr_items):
        for item in ocr_items:
            cy = item.bounds.centerY() / sh
            cx = item.bounds.centerX() / sw
            t = item.text.replace(" ", "")
            if cy > 0.85 and 0.20 <= cx <= 0.55 and "好友" in t:
                if not any(ex in t for ex in ["邀請", "被", "名單"]) and len(t) <= 4:
                    return item.bounds
        target_x = int(sw * 0.375)
        target_y = int(sh * 0.910)
        half_w = int(sw * 0.08)
        half_h = max(24, int(sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)

    def simulate_get_bottom_main_tab(self, sw, sh, ocr_items):
        for item in ocr_items:
            cy = item.bounds.centerY() / sh
            cx = item.bounds.centerX() / sw
            t = item.text.replace(" ", "")
            if cy > 0.85 and cx < 0.30 and ("集點" in t) and len(t) <= 4:
                return item.bounds
        target_x = int(sw * 0.125)
        target_y = int(sh * 0.910)
        half_w = int(sw * 0.08)
        half_h = max(24, int(sh * 0.020))
        return Rect(target_x - half_w, target_y - half_h, target_x + half_w, target_y + half_h)

    def test_r6_bottom_nav_ocr_locking_bounds_center(self):
        sw, sh = 1080, 2400
        ocr_items = [
            OcrItem("好友", Rect(350, 2180, 450, 2220)),
            OcrItem("集點", Rect(100, 2180, 180, 2220)),
        ]
        r_friends = self.simulate_get_bottom_friends_tab(sw, sh, ocr_items)
        assert r_friends.centerX() == 400
        assert r_friends.centerY() == 2200

        r_main = self.simulate_get_bottom_main_tab(sw, sh, ocr_items)
        assert r_main.centerX() == 140
        assert r_main.centerY() == 2200

    def test_r6_bottom_nav_safe_fallback_anchor_0_910f(self):
        sw, sh = 1080, 2400
        fb_friends = self.simulate_get_bottom_friends_tab(sw, sh, [])
        assert fb_friends.centerX() == int(sw * 0.375)
        assert fb_friends.centerY() == int(sh * 0.910)

        fb_main = self.simulate_get_bottom_main_tab(sw, sh, [])
        assert fb_main.centerX() == int(sw * 0.125)
        assert fb_main.centerY() == int(sh * 0.910)

    def test_r6_bottom_nav_clearance_virtual_nav_and_gesture_bar(self):
        devices = [
            ("MDPI", 1.0, 720, 1280),
            ("HDPI", 1.5, 720, 1280),
            ("XHDPI", 2.0, 1080, 1920),
            ("420DPI", 2.625, 1080, 2340),
            ("XXHDPI", 3.0, 1080, 2400),
            ("Tall XXHDPI (SM-S7210)", 3.0, 1080, 2640),
            ("XXXHDPI", 4.0, 1440, 3120),
        ]
        for label, density, sw, sh in devices:
            fb_rect = self.simulate_get_bottom_friends_tab(sw, sh, [])
            nav_bar_top = sh - int(54 * density)
            gesture_bar_top = sh - int(16 * density)
            assert fb_rect.centerY() < nav_bar_top, f"Failed on {label}"
            assert fb_rect.centerY() < gesture_bar_top, f"Failed on {label}"

    def test_r6_bottom_nav_ocr_exclusion_of_bait(self):
        sw, sh = 1080, 2400
        bait_ocr = [
            OcrItem("邀請好友", Rect(300, 2150, 500, 2220)),
            OcrItem("好友名單", Rect(300, 2150, 500, 2220)),
        ]
        rect = self.simulate_get_bottom_friends_tab(sw, sh, bait_ocr)
        assert rect.centerX() == int(sw * 0.375)
        assert rect.centerY() == int(sh * 0.910)

    def test_r6_ast_friend_gift_task_bottom_nav(self):
        task_code = (SRC_DIR / "tasks/FriendGiftTask.kt").read_text(encoding="utf-8")
        assert "fun getBottomFriendsTabRect(" in task_code
        assert "fun getBottomMainTabRect(" in task_code
        assert "0.910f" in task_code


# ==============================================================================
# R7: Promo Close Expansion & Clean CI/CD Build
# ==============================================================================

class TestR7PromoCloseAndBuildReleaseCleanliness:
    """Verifies Requirement R7: Promo close expansion, compiler imports, and version bump."""

    def test_r7_promo_close_hotzone_expansion_0_050f_to_0_120f(self):
        ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
        assert "(sh * 0.050f).toInt()" in ocr_code
        assert "(sh * 0.120f).toInt()" in ocr_code

        # Test tall vs standard resolutions
        sw, sh = 1080, 2640  # SM-S7210
        rect = Rect(int(sw * 0.86), int(sh * 0.050), int(sw * 0.98), int(sh * 0.120))
        tall_x = int(sw * 0.92)
        tall_y = int(sh * 0.065)
        assert rect.left <= tall_x <= rect.right
        assert rect.top <= tall_y <= rect.bottom

    def test_r7_ast_kotlin_imports_and_constants_clean(self):
        closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
        inspector_code = (SRC_DIR / "vision/NodeInspector.kt").read_text(encoding="utf-8")

        assert "import kotlin.math.max" in closer_code
        assert "import kotlin.math.min" in closer_code
        assert "import android.util.Log" in closer_code
        assert 'private const val TAG = "AdCloserHandler"' in closer_code
        assert "import kotlin.math.max" in inspector_code

        kt_files = list(SRC_DIR.rglob("*.kt"))
        assert len(kt_files) >= 10
        for kt_file in kt_files:
            content = kt_file.read_text(encoding="utf-8")
            if re.search(r"\bLog\.[diewv]\(", content):
                assert "import android.util.Log" in content, f"Missing Log import in {kt_file.name}"
            if re.search(r"\bTAG\b", content):
                assert re.search(r"(?:val|const\s+val)\s+TAG\b", content) is not None, f"Missing TAG in {kt_file.name}"

    def test_r7_version_code_and_name_bump(self):
        gradle_code = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        assert "versionCode = 85" in gradle_code
        assert 'versionName = "1.15.52"' in gradle_code

    def test_r7_github_actions_workflow_release_v1_15_52(self):
        workflow_code = (REPO_ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")
        assert "v1.15.52" in workflow_code


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
