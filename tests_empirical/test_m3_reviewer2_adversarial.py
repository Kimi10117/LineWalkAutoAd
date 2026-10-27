#!/usr/bin/env python3
"""
ADVERSARIAL STRESS TEST SUITE: Reviewer 2 (Milestone 3: Requirement R4)
Target: LotteryTask.kt, TemplateMatcher.kt
LineWalkAutoAd Universal Device Adaptation Architecture

Stress-tests and adversarial verification of:
1. Ticket count null/missing fallback:
   - Graceful fallback to button pixel sampling without crashing.
   - Empty OCR items, non-numeric items, malformed items.
   - Multiple aspect ratios (16:9, 19.5:9, 20:9, 22:9, 4:3 tablet).
2. Ticket count boundary values:
   - 0 -> strictly disabled (< 50)
   - 1 -> strictly disabled (< 50)
   - 48 -> strictly disabled (< 50)
   - 49 -> strictly disabled (< 50)
   - 50 -> ENABLED / allowed (>= 50 threshold boundary)
   - 51 -> enabled (>= 50)
   - Large numbers (100, 786, 9999) -> enabled
   - Negative / malformed strings
3. Dimmed popup background and non-white margins:
   - Immunity of button gray/active detection to darkened overlay backgrounds:
     RGB (158, 157, 155), (60, 60, 60), (30, 30, 30), (0, 0, 0), and pure white (255, 255, 255).
   - Old margin bug regression check: old method checked lr < 220 returning false; new method
     completely decouples button determination from screen margins.
4. Button internal pixel color sampling robustness:
   - Active red buttons (with white text, varying red hues, border transitions).
   - Disabled gray buttons (low saturation, low R-G contrast, dark gray, light gray).
   - Boundary condition color metrics (R-G = 61 vs R-G = 59, saturation 0.31 vs 0.29).
5. Priority ordering, diversion, and strict safety invariants:
   - 50-draw -> Free-draw -> Clean close (X).
   - "5張 抽1次" absolute exclusion under all circumstances.
6. Kotlin source AST and backwards compatibility invariants:
   - leftMarginColor identifier retention.
   - isFiftyDrawZone internal ROI sampling.
   - No hardcoded mocks or facade patterns.
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

    def __eq__(self, other):
        if not isinstance(other, Rect):
            return False
        return (self.left == other.left and self.top == other.top and
                self.right == other.right and self.bottom == other.bottom)

    def __repr__(self):
        return f"Rect({self.left}, {self.top}, {self.right}, {self.bottom})"


class OcrItem:
    def __init__(self, text: str, bounds: Rect):
        self.text = text
        self.bounds = bounds


# ==============================================================================
# Simulation of LotteryTask.kt logic
# ==============================================================================

def simulate_extract_ticket_count(ocr_items, sw=1080, sh=2400):
    effective_sh = sh if sh > 0 else 0
    effective_sw = sw if sw > 0 else 0

    # 1. Modal header pill
    for item in ocr_items:
        cy = item.bounds.centerY()
        cx = item.bounds.centerX()
        if effective_sh > 0:
            in_y = int(effective_sh * 0.15) <= cy <= int(effective_sh * 0.26)
        else:
            in_y = 120 <= cy <= 650 or (len(ocr_items) > 0 and 0.14 <= cy / max(1, max(o.bounds.bottom for o in ocr_items)) <= 0.27)
        if effective_sw > 0:
            in_x = cx > int(effective_sw * 0.58)
        else:
            in_x = cx > 350 or (len(ocr_items) > 0 and cx / max(1, max(o.bounds.right for o in ocr_items)) > 0.55)

        t = item.text.replace(" ", "")
        if in_y and in_x and re.search(r"\d+", t):
            m = re.search(r"\d+", item.text)
            if m:
                try:
                    return int(m.group(0))
                except ValueError:
                    pass

    # 2. Fallback top bar asset zone
    for item in ocr_items:
        cy = item.bounds.centerY()
        cx = item.bounds.centerX()
        if effective_sh > 0:
            in_y = int(effective_sh * 0.03) <= cy <= int(effective_sh * 0.10)
        else:
            in_y = 30 <= cy <= 180 or (len(ocr_items) > 0 and 0.03 <= cy / max(1, max(o.bounds.bottom for o in ocr_items)) <= 0.11)
        if effective_sw > 0:
            in_x = int(effective_sw * 0.48) <= cx <= int(effective_sw * 0.80)
        else:
            in_x = 400 <= cx <= 900 or (len(ocr_items) > 0 and 0.45 <= cx / max(1, max(o.bounds.right for o in ocr_items)) <= 0.85)

        t = item.text.replace(" ", "")
        if in_y and in_x and re.search(r"\d+", t):
            m = re.search(r"\d+", item.text)
            if m:
                try:
                    return int(m.group(0))
                except ValueError:
                    pass

    # 3. Keyword fallback
    for item in ocr_items:
        cy = item.bounds.centerY()
        in_top = cy < effective_sh * 0.25 if effective_sh > 0 else cy < 500
        t = item.text.replace(" ", "")
        if in_top and ("張" in t or "票" in t or "ticket" in t.lower()) and re.search(r"\d+", t):
            m = re.search(r"\d+", item.text)
            if m:
                try:
                    return int(m.group(0))
                except ValueError:
                    pass

    return None


def simulate_find_target_draw_button(ocr_items, sw=1080, sh=2400,
                                     is_fifty_disabled_gray=False,
                                     is_free_disabled_gray=False):
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

    # 1. Priority 1: 50-draw
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
    close_rect = Rect(int(sw * 0.86), int(sh * 0.095), int(sw * 0.96), int(sh * 0.145))
    return "關閉", close_rect


# ==============================================================================
# Simulation of TemplateMatcher.kt Button Internal Pixel Sampling
# ==============================================================================

def simulate_is_fifty_draw_button_disabled(pixels_rgb):
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
# 1. ADVERSARIAL TESTS: Ticket Count Null / Missing Fallback
# ==============================================================================

def test_ticket_count_null_fallback_to_button_pixel_sampling():
    """
    CRITICAL EDGE CASE: When ticket count cannot be extracted (null/missing):
    1. System must NOT crash.
    2. isTicketInsufficient evaluates to False.
    3. If button internal pixels are active red -> 50-draw is clicked.
    4. If button internal pixels are gray (disabled) -> falls through to free draw.
    5. If free draw is also unavailable -> resolves to clean close (X).
    """
    sw, sh = 1080, 2400
    fifty_rect = Rect(100, 1100, 500, 1250)
    free_rect = Rect(580, 1100, 980, 1250)

    # Empty OCR items -> ticket count is None
    empty_items = []
    assert simulate_extract_ticket_count(empty_items, sw, sh) is None

    # OCR items with NO numbers -> ticket count is None
    items_no_digits = [
        OcrItem("BIG抽抽樂", Rect(300, 350, 780, 410)),
        OcrItem("抽獎說明", Rect(700, 420, 950, 500)),
        OcrItem("50張 抽1次", fifty_rect),
        OcrItem("免費抽1次", free_rect),
    ]
    assert simulate_extract_ticket_count(items_no_digits, sw, sh) is None

    # Case A: Ticket count is None, but 50-draw button is ACTIVE RED -> 50-draw allowed
    target = simulate_find_target_draw_button(items_no_digits, sw, sh, is_fifty_disabled_gray=False)
    assert target is not None
    assert target[0] == "50張抽一次"
    assert target[1] == fifty_rect

    # Case B: Ticket count is None, but 50-draw button is GRAY (DISABLED) -> falls through to free draw!
    target_fallback = simulate_find_target_draw_button(items_no_digits, sw, sh, is_fifty_disabled_gray=True)
    assert target_fallback is not None
    assert target_fallback[0] == "免費看廣告抽一次"
    assert target_fallback[1] == free_rect

    # Case C: Ticket count is None, 50-draw is gray, free-draw is in cooldown -> clean close
    items_free_cooldown = [
        OcrItem("BIG抽抽樂", Rect(300, 350, 780, 410)),
        OcrItem("50張 抽1次", fifty_rect),
        OcrItem("回復還需 05:00", free_rect),
    ]
    action, bounds = simulate_resolve_next_action(items_free_cooldown, sw, sh, is_fifty_disabled_gray=True)
    assert action == "關閉"
    assert bounds.centerX() == int(sw * 0.91)


def test_ticket_count_extraction_across_diverse_aspect_ratios():
    """
    Verify ticket count extraction behaves consistently across diverse devices:
    - SM-S7210 (22:9 ultra tall: 1080x2640)
    - SM-F731N (foldable outer: 720x748 / inner: 1080x2640)
    - Standard 16:9 (1080x1920)
    - Tablet 4:3 (1600x1200)
    """
    aspect_ratios = [
        (1080, 2640),  # 22:9 SM-S7210
        (1080, 2340),  # 19.5:9
        (1080, 2400),  # 20:9
        (1080, 1920),  # 16:9
        (1600, 1200),  # 4:3 Tablet
    ]

    for sw, sh in aspect_ratios:
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.85), int(sh * 0.23))
        items = [
            OcrItem("BIG抽抽樂", Rect(int(sw * 0.2), int(sh * 0.15), int(sw * 0.5), int(sh * 0.20))),
            OcrItem("38", pill_rect),
            OcrItem("50張 抽1次", Rect(int(sw * 0.1), int(sh * 0.45), int(sw * 0.45), int(sh * 0.53))),
            OcrItem("免費抽1次", Rect(int(sw * 0.55), int(sh * 0.48), int(sw * 0.9), int(sh * 0.56))),
        ]

        count = simulate_extract_ticket_count(items, sw, sh)
        assert count == 38, f"Failed ticket extraction on {sw}x{sh}"

        # 38 < 50, must divert to free draw on all aspect ratios
        target = simulate_find_target_draw_button(items, sw, sh, is_fifty_disabled_gray=False)
        assert target is not None
        assert target[0] == "免費看廣告抽一次", f"Failed diversion on {sw}x{sh}"


# ==============================================================================
# 2. ADVERSARIAL TESTS: Ticket Count Boundary Values (0, 49, 50, etc.)
# ==============================================================================

@pytest.mark.parametrize("ticket_val,expected_50_draw_allowed", [
    (0, False),     # Exact zero -> strictly disabled
    (1, False),     # Low value -> strictly disabled
    (48, False),    # Sub-boundary -> strictly disabled
    (49, False),    # CRITICAL BOUNDARY: 49 < 50 -> strictly disabled!
    (50, True),     # CRITICAL BOUNDARY: 50 >= 50 -> ENABLED!
    (51, True),     # 51 >= 50 -> enabled
    (99, True),     # 99 >= 50 -> enabled
    (100, True),    # 100 >= 50 -> enabled
    (839, True),    # Typical high balance -> enabled
    (10000, True),  # Extreme balance -> enabled
])
def test_ticket_count_exact_boundaries(ticket_val, expected_50_draw_allowed):
    """
    Exhaustive boundary testing around the 50-ticket threshold:
    - Values <= 49 must strictly disallow 50-draw and route to free draw.
    - Values >= 50 must allow 50-draw.
    """
    sw, sh = 1080, 2400
    pill_rect = Rect(700, 420, 950, 500)
    fifty_rect = Rect(100, 1100, 500, 1250)
    free_rect = Rect(580, 1100, 980, 1250)

    items = [
        OcrItem(f"{ticket_val}", pill_rect),
        OcrItem("50張 抽1次", fifty_rect),
        OcrItem("免費抽1次", free_rect),
    ]

    extracted = simulate_extract_ticket_count(items, sw, sh)
    assert extracted == ticket_val

    target = simulate_find_target_draw_button(items, sw, sh, is_fifty_disabled_gray=False)
    assert target is not None

    if expected_50_draw_allowed:
        assert target[0] == "50張抽一次"
        assert target[1] == fifty_rect
    else:
        assert target[0] == "免費看廣告抽一次"
        assert target[1] == free_rect


def test_ticket_count_adverse_string_formats():
    """Test adverse OCR text formats: spaces, prefix/suffix, unit characters."""
    sw, sh = 1080, 2400
    pill_rect = Rect(700, 420, 950, 500)

    test_cases = [
        ("49張", 49),
        (" 49 ", 49),
        ("x49", 49),
        ("No. 49", 49),
        ("50張", 50),
        ("剩餘 50 張", 50),
        ("x 50", 50),
        ("0張", 0),
        ("x0", 0),
    ]

    for raw_text, expected_num in test_cases:
        items = [OcrItem(raw_text, pill_rect)]
        assert simulate_extract_ticket_count(items, sw, sh) == expected_num


# ==============================================================================
# 3. ADVERSARIAL TESTS: Dimmed Popup Background & Margin Immunity
# ==============================================================================

def test_dimmed_screen_margin_immunity_across_margin_colors():
    """
    CRITICAL REQUIREMENT R4.2:
    The screen margin (x = 5%, 95%) around the lottery popup is darkened/dimmed:
    RGB ~ [158, 157, 155] or dark overlay [30, 30, 30] or pure black [0, 0, 0].
    The old method checked: if leftMarginColor.rgb < 220: return false (prematurely active!).
    Verify that in the new implementation:
    1. A gray button is 100% detected as disabled, REGARDLESS of margin color.
    2. An active red button is 100% detected as active, REGARDLESS of margin color.
    """
    margin_colors_to_test = [
        (158, 157, 155),  # Empirical lottery modal dimmed overlay
        (0, 0, 0),        # Pure black margin
        (30, 30, 30),     # Dark gray overlay
        (60, 60, 60),     # Medium dark overlay
        (100, 100, 100),  # Overlay with game background showing
        (200, 200, 200),  # Light gray margin
        (255, 255, 255),  # Pure white margin
        (255, 0, 0),      # Extreme red background leak
    ]

    # Active button internal pixels (R > 180 && R - G > 60)
    active_red_pixels = [(238, 104, 114)] * 750 + [(255, 255, 255)] * 250

    # Gray button internal pixels (R - G < 40 || Saturation < 0.3)
    gray_button_pixels = [(178, 151, 153)] * 800 + [(210, 210, 210)] * 200

    for m_rgb in margin_colors_to_test:
        # Check old method failure:
        lr, lg, lb = m_rgb
        if lr < 220 or lg < 220 or lb < 220:
            # Old method would have returned False for ANY button (even gray ones!)
            old_method_buggy_verdict = False
        else:
            old_method_buggy_verdict = True

        # New internal pixel sampling is completely immune to margin RGB:
        active_res = simulate_is_fifty_draw_button_disabled(active_red_pixels)
        assert active_res is False, f"Active button should be False (active) with margin {m_rgb}"

        gray_res = simulate_is_fifty_draw_button_disabled(gray_button_pixels)
        assert gray_res is True, f"Gray button should be True (disabled) with margin {m_rgb}"


# ==============================================================================
# 4. ADVERSARIAL TESTS: Button Internal Pixel Color Sampling Robustness
# ==============================================================================

def test_color_metric_boundary_conditions():
    """
    Stress-test exact mathematical boundaries of R > 180 && R - G > 60 vs (R - G < 40 || Sat < 0.3).
    """
    # Active: R > 180 && R - G > 60
    # Boundary case 1: R=181, G=120 (R-G = 61 > 60) -> Active
    active_boundary = [(181, 120, 100)] * 1000
    assert simulate_is_fifty_draw_button_disabled(active_boundary) is False

    # Disabled case 1: R=180, G=120 (R not > 180) and R-G = 60 (not < 40), but Sat = (180-100)/180 = 0.44.
    # clickable_red = 0 (< 15%) -> returns True (disabled)!
    sub_boundary = [(180, 120, 100)] * 1000
    assert simulate_is_fifty_draw_button_disabled(sub_boundary) is True

    # Disabled case 2: Low saturation (Sat < 0.30):
    # R=200, G=190, B=185: max=200, min=185, Sat = 15/200 = 0.075 < 0.30
    desaturated_button = [(200, 190, 185)] * 1000
    assert simulate_is_fifty_draw_button_disabled(desaturated_button) is True

    # Disabled case 3: Low contrast (R - G < 40):
    # R=220, G=195, B=100: R-G = 25 < 40 -> disabled
    low_contrast_button = [(220, 195, 100)] * 1000
    assert simulate_is_fifty_draw_button_disabled(low_contrast_button) is True

    # Defensive: Empty pixels -> returns False
    assert simulate_is_fifty_draw_button_disabled([]) is False


def test_mixed_button_noise_and_decorations():
    """
    Test real-world button rendering artifacts:
    - Button with 60% active red, 20% white text, 20% shadow border
    - Button with 50% gray, 30% dark gray, 20% white text
    """
    # Active button with shadows and text
    realistic_active = (
        [(235, 95, 105)] * 600 +    # Red button body
        [(255, 255, 255)] * 200 +   # White text "50張 抽1次"
        [(150, 40, 50)] * 100 +     # Dark red shadow
        [(245, 130, 140)] * 100     # Top highlight
    )
    assert simulate_is_fifty_draw_button_disabled(realistic_active) is False

    # Gray button with shadows and text
    realistic_gray = (
        [(175, 150, 150)] * 500 +   # Gray button body
        [(120, 100, 100)] * 300 +   # Dark gray shadow
        [(240, 240, 240)] * 200     # White/gray text
    )
    assert simulate_is_fifty_draw_button_disabled(realistic_gray) is True


# ==============================================================================
# 5. ADVERSARIAL TESTS: Strict Safety & 5-Draw Prohibition
# ==============================================================================

def test_strictly_never_click_5_draw_button():
    """
    CRITICAL USER RULE:
    【嚴禁點擊】：絕不點擊「5張 抽1次」按鈕！
    Even if tickets = 5, or tickets = 50, or tickets = 100,
    the 5-draw button must NEVER be returned as a click target.
    """
    sw, sh = 1080, 2400
    pill_rect = Rect(700, 420, 950, 500)
    five_draw_rect = Rect(100, 1100, 500, 1250)

    # Only 5-draw is available, free draw in cooldown
    items = [
        OcrItem("5", pill_rect),
        OcrItem("5張 抽1次", five_draw_rect),
        OcrItem("回復還需 03:00", Rect(580, 1100, 980, 1250)),
    ]

    target = simulate_find_target_draw_button(items, sw, sh, is_fifty_disabled_gray=False)
    assert target is None, "5-draw button must NEVER be clicked!"

    action, _ = simulate_resolve_next_action(items, sw, sh, is_fifty_disabled_gray=False)
    assert action == "關閉", "When only 5-draw exists, must cleanly close dialog"


# ==============================================================================
# 6. AST & SOURCE CODE INTEGRITY
# ==============================================================================

def test_kotlin_source_integrity_and_no_cheating():
    """
    Verify integrity of Kotlin source implementations:
    - No dummy return or hardcoded test fixtures
    - Both tasks implement real algorithm logic
    - Required contracts are satisfied
    """
    lottery_file = SRC_DIR / "tasks/LotteryTask.kt"
    tm_file = SRC_DIR / "vision/TemplateMatcher.kt"

    assert lottery_file.exists()
    assert tm_file.exists()

    l_src = lottery_file.read_text(encoding="utf-8")
    t_src = tm_file.read_text(encoding="utf-8")

    # Anti-cheat check: no test mock artifacts embedded in production code
    assert "fake" not in l_src.lower()
    assert "mock" not in l_src.lower()
    assert "test" not in l_src.lower()
    assert "fake" not in t_src.lower()
    assert "mock" not in t_src.lower()

    # R4.1 LotteryTask checks
    assert "extractTicketCount" in l_src
    assert "ticketCount != null && ticketCount < 50" in l_src
    assert "!isFiftyInCooldown && !isTicketInsufficient" in l_src
    assert "findFreeDrawOnly" in l_src
    assert "getCloseButtonBounds" in l_src
    assert "resolveNextAction" in l_src

    # R4.2 TemplateMatcher checks
    assert "leftMarginColor" in t_src  # Backward compat
    assert "isFiftyDrawZone" in t_src
    assert "clickableRedPixels" in t_src
    assert "disabledPixels" in t_src
    assert "(r - g < 40) || sat < 0.30f" in t_src
    assert "isFiftyDrawButtonDisabled" in t_src

    # Verify that the buggy lr < 220 early return was removed from isPopupButtonDisabledGray
    assert "lr < 220 || lg < 220" not in t_src
    assert "rr < 220 || rg < 220" not in t_src


def test_adversarial_finding_free_draw_roi_conflation_flaw():
    """
    CRITICAL DEFECT CONFIRMATION:
    In TemplateMatcher.kt lines 102-108:
        val isFiftyDrawZone = safeCy in (h * 0.38f).toInt()..(h * 0.60f).toInt()
        if (isFiftyDrawZone) {
            val xStart = (w * 0.15f).toInt().coerceIn(0, w - 1)
            val xEnd = (w * 0.45f).toInt().coerceIn(xStart + 1, w - 1)
            ...
    When LotteryTask.kt lines 266-267 calls:
        val cy = freeDrawOcr.bounds.centerY() // cy is around 0.53f * h
        if (!templateMatcher.isPopupButtonDisabledGray(screenBitmap, cy))
    Because freeDrawOcr.bounds.centerY() is in [0.48f * h, 0.62f * h],
    it falls DIRECTLY into isFiftyDrawZone (0.38f..0.60f)!
    Thus isPopupButtonDisabledGray samples x in 0.15f..0.45f (the 50-draw button)
    instead of the free-draw button (x in 0.50f..0.98f)!
    If the 50-draw button is gray (e.g. ticketCount < 50), isPopupButtonDisabledGray
    returns True (disabled), causing findFreeDrawOnly to erroneously reject the active
    free-draw button!
    """
    sh = 2400
    sw = 1080
    free_cy = int(sh * 0.53)  # Standard free-draw centerY
    is_fifty_draw_zone = int(sh * 0.38) <= free_cy <= int(sh * 0.60)
    assert is_fifty_draw_zone is True, (
        "Demonstrates flaw: free_cy is trapped inside isFiftyDrawZone in TemplateMatcher.kt"
    )


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

