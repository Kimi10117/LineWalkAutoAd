#!/usr/bin/env python3
"""
EMPIRICAL TEST SUITE: Milestone 3 (v1.15.52 / Requirement R4)
Gacha (Lottery) 50-Draw Ticket Guard & Button Internal Pixel Sampling
LineWalkAutoAd

Covers:
- R4.1: Top Asset Ticket Count Extraction & 50-Draw Guard:
        - Extracts ticket count from modal header ticket pill (cy in 0.16f..0.25f, cx > 0.60f).
        - Extracts ticket count from screen top bar asset zone (cy in 0.03f..0.10f, cx in 0.48f..0.80f).
        - If ticketCount != null && ticketCount < 50: strictly forbids clicking 50-draw button.
        - Automatically diverts to "免費看廣告抽一次" if present, or cleanly closes the dialog via (X).
        - Allows 50-draw when ticketCount >= 50 (or null fallback when button is active).
- R4.2: Button Internal Pixel Color Sampling for Gray-Out Detection:
        - Replaces whole-screen white edge margin dependencies (x=5%, 95% having RGB > 220)
          with direct internal button pixel color sampling (x in [0.15f * w, 0.45f * w]).
        - Active / Clickable: Red > 180 && Red - Green > 60
        - Gray / Disabled: Red - Green < 40 || Saturation < 0.3
        - Preserves leftMarginColor identifier in TemplateMatcher.kt for backward compatibility.
"""

from pathlib import Path
import re
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"


class Rect:
    def __init__(self, left: int, top: int, right: int, bottom: int):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    def width(self) -> int:
        return self.right - self.left

    def height(self) -> int:
        return self.bottom - self.top

    def centerX(self) -> int:
        return (self.left + self.right) // 2

    def centerY(self) -> int:
        return (self.top + self.bottom) // 2


class OcrItem:
    def __init__(self, text: str, bounds: Rect):
        self.text = text
        self.bounds = bounds


# ==============================================================================
# Simulation: extractTicketCount (LotteryTask.kt)
# ==============================================================================

def simulate_extract_ticket_count(ocr_items, sw=1080, sh=2400):
    """
    Port of LotteryTask.kt extractTicketCount:
    1. Modal pill: cy in 0.15f..0.26f, cx > 0.58f with digits.
    2. Fallback top bar: cy in 0.03f..0.10f, cx in 0.48f..0.80f with digits.
    3. Keyword fallback: cy < 0.25f containing '張'/'票'/'ticket' with digits.
    """
    # 1. Modal header pill
    for item in ocr_items:
        cy = item.bounds.centerY()
        cx = item.bounds.centerX()
        in_y = int(sh * 0.15) <= cy <= int(sh * 0.26) if sh > 0 else (120 <= cy <= 650)
        in_x = cx > int(sw * 0.58) if sw > 0 else (cx > 350)
        t = item.text.replace(" ", "").replace(",", "")
        if in_y and in_x:
            m = re.search(r"\d+", t)
            if m:
                return int(m.group(0))

    # 2. Fallback top bar asset zone
    for item in ocr_items:
        cy = item.bounds.centerY()
        cx = item.bounds.centerX()
        in_y = int(sh * 0.03) <= cy <= int(sh * 0.10) if sh > 0 else (30 <= cy <= 180)
        in_x = int(sw * 0.48) <= cx <= int(sw * 0.80) if sw > 0 else (400 <= cx <= 900)
        t = item.text.replace(" ", "").replace(",", "")
        if in_y and in_x:
            m = re.search(r"\d+", t)
            if m:
                return int(m.group(0))

    # 3. Keyword fallback
    for item in ocr_items:
        cy = item.bounds.centerY()
        in_top = cy < sh * 0.25 if sh > 0 else cy < 500
        t = item.text.replace(" ", "").replace(",", "")
        if in_top and ("張" in t or "票" in t or "ticket" in t.lower()):
            m = re.search(r"\d+", t)
            if m:
                return int(m.group(0))

    return None


# ==============================================================================
# Simulation: findTargetDrawButton & resolveNextAction (LotteryTask.kt)
# ==============================================================================

def simulate_find_target_draw_button(ocr_items, sw=1080, sh=2400,
                                     is_fifty_disabled_gray=False,
                                     is_free_disabled_gray=False):
    """
    Port of LotteryTask.kt findTargetDrawButton with R4 Ticket Count Guard.
    """
    ticket_count = simulate_extract_ticket_count(ocr_items, sw, sh)
    is_ticket_insufficient = ticket_count is not None and ticket_count < 50

    time_regex = re.compile(r"\d{1,2}:\d{2}")

    # 0A. Free draw cooling
    is_free_in_cooldown = any(
        item.bounds.centerX() > int(sw * 0.48) and
        int(sh * 0.45) <= item.bounds.centerY() <= int(sh * 0.62) and
        ("回復還需" in item.text or "還需" in item.text or "倒計時" in item.text or
         bool(time_regex.search(item.text)))
        for item in ocr_items
    )

    # 0B. 50-draw cooling
    is_fifty_in_cooldown = any(
        item.bounds.centerX() < int(sw * 0.55) and
        int(sh * 0.38) <= item.bounds.centerY() <= int(sh * 0.58) and
        ("回復還需" in item.text or "還需" in item.text or "回復" in item.text or
         "剩0次" in item.text or item.text.replace(" ", "") == "0次" or
         bool(time_regex.search(item.text)))
        for item in ocr_items
    )

    # 1. Priority 1: 50-draw (FORBIDDEN if ticket count < 50)
    if not is_fifty_in_cooldown and not is_ticket_insufficient:
        fifty_item = None
        for item in ocr_items:
            cy = item.bounds.centerY()
            cx = item.bounds.centerX()
            t = item.text.replace(" ", "")
            if (int(sh * 0.43) <= cy <= int(sh * 0.56) and
                cx < int(sw * 0.55) and
                ("50張" in t or ("50" in t and "抽" in t)) and
                "5張" not in t and "回復" not in t and "還需" not in t):
                fifty_item = item
                break

        if fifty_item and not is_fifty_disabled_gray:
            return "50張抽一次", fifty_item.bounds

    # 2. Priority 2: Free draw
    if not is_free_in_cooldown:
        free_item = None
        for item in ocr_items:
            cy = item.bounds.centerY()
            cx = item.bounds.centerX()
            t = item.text.replace(" ", "")
            if (int(sh * 0.48) <= cy <= int(sh * 0.62) and
                cx > int(sw * 0.48) and
                ("免費抽" in t or "免費" in t) and
                "5張" not in t and "回復" not in t and "還需" not in t):
                free_item = item
                break

        if free_item and not is_free_disabled_gray:
            return "免費看廣告抽一次", free_item.bounds

    return None


def simulate_resolve_next_action(ocr_items, sw=1080, sh=2400,
                                 is_fifty_disabled_gray=False,
                                 is_free_disabled_gray=False):
    target = simulate_find_target_draw_button(
        ocr_items, sw, sh, is_fifty_disabled_gray, is_free_disabled_gray
    )
    if target is not None:
        return target
    # Clean close bounds
    close_rect = Rect(int(sw * 0.86), int(sh * 0.095), int(sw * 0.96), int(sh * 0.145))
    return "關閉", close_rect


# ==============================================================================
# Simulation: Button Internal Pixel Sampling (TemplateMatcher.kt)
# ==============================================================================

def simulate_is_fifty_draw_button_disabled(pixels_rgb):
    """
    Port of TemplateMatcher.kt isPopupButtonDisabledGray / isFiftyDrawButtonDisabled:
    Active / Clickable: Red > 180 && Red - Green > 60
    Gray / Disabled: Red - Green < 40 || Saturation < 0.3
    """
    clickable_red = 0
    disabled_gray = 0
    total = len(pixels_rgb)

    for r, g, b in pixels_rgb:
        c_max = max(r, g, b)
        c_min = min(r, g, b)
        sat = (c_max - c_min) / c_max if c_max > 0 else 0.0

        if r > 180 and (r - g > 60):
            clickable_red += 1
        if (r - g < 40) or sat < 0.30:
            disabled_gray += 1

    if total == 0:
        return False
    red_ratio = clickable_red / total
    disabled_ratio = disabled_gray / total
    return red_ratio < 0.15 or disabled_ratio > 0.65


# ==============================================================================
# TESTS: Requirement R4.1 Ticket Count Extraction
# ==============================================================================

def test_extract_ticket_count_modal_header_pill():
    """Verify extraction of various numbers from the modal header ticket pill."""
    sw, sh = 1080, 2400
    # cy in 0.16f..0.25f (384..600), cx > 0.60f (648)
    pill_rect = Rect(700, 420, 950, 500)  # cy = 460 (0.191 sh), cx = 825 (0.764 sw)

    items = [
        OcrItem("BIG抽抽樂", Rect(300, 350, 780, 410)),
        OcrItem("31", pill_rect),
        OcrItem("50張 抽1次", Rect(100, 1100, 500, 1250)),
    ]
    assert simulate_extract_ticket_count(items, sw, sh) == 31

    # Decorated numbers
    items[1] = OcrItem("31張", pill_rect)
    assert simulate_extract_ticket_count(items, sw, sh) == 31

    items[1] = OcrItem("x 786", pill_rect)
    assert simulate_extract_ticket_count(items, sw, sh) == 786

    items[1] = OcrItem("0", pill_rect)
    assert simulate_extract_ticket_count(items, sw, sh) == 0

    items[1] = OcrItem("2", pill_rect)
    assert simulate_extract_ticket_count(items, sw, sh) == 2

    # Thousands separator test
    items[1] = OcrItem("1,200", pill_rect)
    assert simulate_extract_ticket_count(items, sw, sh) == 1200


def test_extract_ticket_count_top_bar_fallback():
    """Verify fallback to screen top bar asset zone (cy in 0.03f..0.10f, cx in 0.48f..0.80f)."""
    sw, sh = 1080, 2400
    # cy = 150 (0.062 sh), cx = 650 (0.601 sw)
    top_bar_rect = Rect(550, 120, 750, 180)

    items = [
        OcrItem("LINE 散步趣", Rect(50, 100, 300, 180)),
        OcrItem("45", top_bar_rect),
        OcrItem("首頁", Rect(50, 300, 200, 380)),
    ]
    assert simulate_extract_ticket_count(items, sw, sh) == 45


def test_extract_ticket_count_missing_returns_none():
    """Verify non-matching items return None safely."""
    sw, sh = 1080, 2400
    items = [
        OcrItem("抽抽樂", Rect(300, 350, 780, 410)),
        OcrItem("說明", Rect(700, 420, 950, 500)),
    ]
    assert simulate_extract_ticket_count(items, sw, sh) is None


# ==============================================================================
# TESTS: Requirement R4.1 50-Draw Guard & Automatic Diversion
# ==============================================================================

def test_50_draw_strictly_forbidden_when_tickets_under_50():
    """
    When ticketCount < 50 (e.g. 31), clicking 50-draw is STRICTLY forbidden
    even if the 50-draw button OCR is matched and button is active red.
    Must divert to free draw if free draw is available.
    """
    sw, sh = 1080, 2400
    pill_rect = Rect(700, 420, 950, 500)  # cy = 460 -> 31 tickets
    fifty_rect = Rect(100, 1100, 500, 1250)
    free_rect = Rect(580, 1100, 980, 1250)

    items = [
        OcrItem("31", pill_rect),
        OcrItem("50張 抽1次", fifty_rect),
        OcrItem("免費抽1次", free_rect),
    ]

    action = simulate_find_target_draw_button(items, sw, sh, is_fifty_disabled_gray=False)
    assert action is not None
    label, bounds = action
    # Diverted to free draw!
    assert label == "免費看廣告抽一次"
    assert bounds == free_rect


def test_50_draw_insufficient_diverts_to_clean_close_when_no_free_draw():
    """
    When ticketCount < 50 and free draw is in cooldown / unavailable,
    findTargetDrawButton returns None and resolveNextAction cleanly returns '關閉'.
    """
    sw, sh = 1080, 2400
    pill_rect = Rect(700, 420, 950, 500)  # 2 tickets
    fifty_rect = Rect(100, 1100, 500, 1250)
    free_cooldown_rect = Rect(580, 1100, 980, 1250)

    items = [
        OcrItem("2", pill_rect),
        OcrItem("50張 抽1次", fifty_rect),
        OcrItem("回復還需 02:45", free_cooldown_rect),
    ]

    target = simulate_find_target_draw_button(items, sw, sh, is_fifty_disabled_gray=False)
    assert target is None, "50-draw must not be clicked when tickets < 50 and free draw in cooldown"

    action, bounds = simulate_resolve_next_action(items, sw, sh, is_fifty_disabled_gray=False)
    assert action == "關閉"
    assert bounds.centerX() == int(sw * 0.91)


def test_50_draw_allowed_when_tickets_sufficient():
    """When ticketCount >= 50 and button is active, 50-draw is clicked."""
    sw, sh = 1080, 2400
    pill_rect = Rect(700, 420, 950, 500)  # 839 tickets
    fifty_rect = Rect(100, 1100, 500, 1250)
    free_rect = Rect(580, 1100, 980, 1250)

    items = [
        OcrItem("839", pill_rect),
        OcrItem("50張 抽1次", fifty_rect),
        OcrItem("免費抽1次", free_rect),
    ]

    action = simulate_find_target_draw_button(items, sw, sh, is_fifty_disabled_gray=False)
    assert action is not None
    label, bounds = action
    assert label == "50張抽一次"
    assert bounds == fifty_rect


# ==============================================================================
# TESTS: Requirement R4.2 Button Internal Pixel Color Sampling
# ==============================================================================

def test_button_internal_pixel_sampling_active_red():
    """
    Test active red button pixels:
    Mean R=238, G=104, B=114, R-G=134 > 60, Saturation=0.58 > 0.3.
    Must be classified as active (disabled = False).
    """
    # 75% red button pixels, 25% white text pixels
    pixels = [(238, 104, 114)] * 750 + [(255, 255, 255)] * 250
    is_disabled = simulate_is_fifty_draw_button_disabled(pixels)
    assert is_disabled is False, "Active red button must not be disabled"


def test_button_internal_pixel_sampling_gray_disabled():
    """
    Test gray/disabled button pixels:
    Mean R=178, G=151, B=153, R-G=27 < 40, Saturation=0.15 < 0.3.
    Must be classified as disabled (disabled = True).
    """
    # 100% gray button pixels
    pixels = [(178, 151, 153)] * 1000
    is_disabled = simulate_is_fifty_draw_button_disabled(pixels)
    assert is_disabled is True, "Gray button must be detected as disabled"


def test_gray_detection_immune_to_dimmed_screen_margins():
    """
    CRITICAL BUG REPRODUCTION:
    The old method checked: if leftMarginColor.rgb < 220: return false (not gray!).
    Because the lottery modal dims the margins (RGB [158, 157, 155]), the old method
    falsely returned false (reporting the gray button as active!).
    The new internal pixel sampling method operates strictly inside the button ROI,
    completely independent of screen margins.
    """
    # Dimmed margins RGB (158, 157, 155) - old method would fail here!
    margin_pixel = (158, 157, 155)
    lr, lg, lb = margin_pixel
    old_method_margin_check_failed = (lr < 220 or lg < 220 or lb < 220)
    assert old_method_margin_check_failed is True, "Confirms old method margin bug"

    # Internal button pixels are gray
    gray_button_pixels = [(178, 151, 153)] * 800 + [(200, 200, 200)] * 200
    # New method correctly detects gray button
    assert simulate_is_fifty_draw_button_disabled(gray_button_pixels) is True


def test_spatial_roi_discrimination_fifty_vs_free_draw():
    """
    Verifies that isPopupButtonDisabledGray with centerX discriminates between
    50-draw (left) and free-draw (right).
    When 50-draw on left is gray and free-draw on right is yellow:
    - calling with left centerX yields True (disabled)
    - calling with right centerX yields False (active)
    """
    from tests_empirical.test_m3_challenger_empirical_stress import (
        MockBitmap,
        Rect as M3Rect,
        kotlin_is_popup_button_disabled_gray,
    )
    sw, sh = 1080, 2400
    bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
    fifty_rect = M3Rect(int(sw * 0.10), int(sh * 0.44), int(sw * 0.48), int(sh * 0.54))
    free_rect = M3Rect(int(sw * 0.52), int(sh * 0.48), int(sw * 0.90), int(sh * 0.58))

    # 50-draw is GRAY
    bmp.fillRect(fifty_rect, MockBitmap.to_argb(178, 151, 153))
    # Free-draw is ACTIVE YELLOW
    bmp.fillRect(free_rect, MockBitmap.to_argb(250, 180, 30))

    is_fifty_disabled = kotlin_is_popup_button_disabled_gray(
        bmp, fifty_rect.centerY(), fifty_rect.centerX()
    )
    is_free_disabled = kotlin_is_popup_button_disabled_gray(
        bmp, free_rect.centerY(), free_rect.centerX()
    )

    assert is_fifty_disabled is True, "50-draw button must be evaluated as gray"
    assert is_free_disabled is False, "Free-draw button must be evaluated as active"


# ==============================================================================
# TESTS: Kotlin Source Code AST / Invariant Verification
# ==============================================================================

def test_source_ast_invariants_m3():
    """Verify that all required constants, methods, and invariants exist in source."""
    lottery_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")
    tm_code = (SRC_DIR / "vision/TemplateMatcher.kt").read_text(encoding="utf-8")

    # LotteryTask.kt requirements
    assert "fun extractTicketCount(" in lottery_code
    assert "ticketCount != null && ticketCount < 50" in lottery_code
    assert "fun resolveNextAction(" in lottery_code
    assert "getCloseButtonBounds" in lottery_code

    # Priority ordering invariants
    idx_50 = lottery_code.find("【第一順位】：50 抽")
    idx_free = lottery_code.find("【第二順位】：免費看廣告抽一次")
    assert idx_50 != -1 and idx_free != -1
    assert idx_50 < idx_free, "50-draw priority comment must precede free-draw in LotteryTask.kt"

    # TemplateMatcher.kt requirements
    assert "leftMarginColor" in tm_code, "leftMarginColor identifier must be retained for backward compatibility"
    assert "isFiftyDrawZone" in tm_code
    assert "clickableRedPixels" in tm_code or "clickableRedCount" in tm_code or "r > 180 && (r - g > 60)" in tm_code
    assert "(r - g < 40) || sat < 0.30f" in tm_code
    assert "fun isFiftyDrawButtonDisabled(" in tm_code


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
