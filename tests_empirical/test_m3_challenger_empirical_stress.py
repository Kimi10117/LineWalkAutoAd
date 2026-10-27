#!/usr/bin/env python3
"""
EMPIRICAL CHALLENGER STRESS SUITE: Milestone 3 (Requirement R4)
Gacha 50-Draw Ticket Guard & Button Internal Pixel Gray Sampling
LineWalkAutoAd Universal Device Adaptation Architecture

Author: Challenger 1 (teamwork_preview_challenger)
Working Directory: .agents/teamwork/challenger_m3_1

Empirically stress-tests:
1. Synthetic and real bitmaps testing active red button vs grayed-out button under varied
   brightness levels and dimmed overlay margins (confirming complete independence from
   the buggy screen margin checks).
2. Screen aspect ratios (16:9, 19.5:9, 20:9, 22:9, tablet 4:3) and button ROI boundaries.
3. OCR text configurations with ticket counts from 0 to 100 (parametrized 101 cases),
   checking strict priority fallthrough (50-draw -> free-draw -> close).
4. Boundary condition at tickets=49 vs tickets=50.
5. Strict exclusion of "5張 抽1次" buttons under all conditions.
6. AST and source invariants of Kotlin implementation files.
"""

from pathlib import Path
import re
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"


# ==============================================================================
# Geometric Primitives & Mock Bitmap
# ==============================================================================

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

    def __repr__(self):
        return f"Rect({self.left}, {self.top}, {self.right}, {self.bottom})"

    def __eq__(self, other):
        if not isinstance(other, Rect):
            return False
        return (self.left == other.left and self.top == other.top and
                self.right == other.right and self.bottom == other.bottom)


class OcrItem:
    def __init__(self, text: str, bounds: Rect):
        self.text = text
        self.bounds = bounds

    def __repr__(self):
        return f"OcrItem('{self.text}', {self.bounds})"


class MockBitmap:
    """
    2D In-Memory Bitmap simulator mimicking android.graphics.Bitmap.
    Stores 32-bit ARGB integers (0xAARRGGBB).
    """
    def __init__(self, width: int, height: int, default_argb: int = 0xFFFFFFFF):
        self.width = width
        self.height = height
        self._pixels = [default_argb] * (width * height)

    def setPixel(self, x: int, y: int, argb: int):
        if 0 <= x < self.width and 0 <= y < self.height:
            self._pixels[y * self.width + x] = argb

    def getPixel(self, x: int, y: int) -> int:
        safe_x = max(0, min(x, self.width - 1))
        safe_y = max(0, min(y, self.height - 1))
        return self._pixels[safe_y * self.width + safe_x]

    def fillRect(self, rect: Rect, argb: int):
        for y in range(max(0, rect.top), min(self.height, rect.bottom)):
            row_start = y * self.width
            for x in range(max(0, rect.left), min(self.width, rect.right)):
                self._pixels[row_start + x] = argb

    @staticmethod
    def to_argb(r: int, g: int, b: int, a: int = 255) -> int:
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)


# ==============================================================================
# Faithful Python Ports of Kotlin Logic in TemplateMatcher & LotteryTask
# ==============================================================================

def kotlin_is_popup_button_disabled_gray(screen_bitmap: MockBitmap, center_y: int, center_x: int = None) -> bool:
    """
    Direct faithful port of TemplateMatcher.kt isPopupButtonDisabledGray:
    - Retains leftMarginColor read at (w * 0.05f, safeCy)
    - 50-draw zone: safeCy in 0.38f * h .. 0.60f * h and (center_x is None or center_x < 0.50f * w)
    - Samples ROI: x in 0.15f * w .. 0.45f * w, y in safeCy - 0.018f * h .. safeCy + 0.018f * h
    - Active: Red > 180 && Red - Green > 60
    - Gray: Red - Green < 40 || Saturation < 0.3
    - Disabled if redRatio < 0.15 or disabledRatio > 0.65
    - If center_x >= 0.50f * w (Free-draw right button): samples right button ROI for gray pixels
    """
    w = screen_bitmap.width
    h = screen_bitmap.height
    safe_cy = max(0, min(center_y, h - 1))

    # Backward compatibility read
    left_margin_color = screen_bitmap.getPixel(int(w * 0.05), safe_cy)

    is_fifty_draw_zone = (int(h * 0.38) <= safe_cy <= int(h * 0.60)) and (center_x is None or center_x < int(w * 0.50))
    y_start = max(0, safe_cy - int(h * 0.018))
    y_end = min(h - 1, safe_cy + int(h * 0.018))

    if is_fifty_draw_zone:
        x_start = max(0, int(w * 0.15))
        x_end = min(w - 1, int(w * 0.45))

        clickable_red_pixels = 0
        disabled_pixels = 0
        total_sampled = 0

        step_y = max(1, (y_end - y_start) // 10)
        step_x = max(1, (x_end - x_start) // 30)

        y = y_start
        while y <= y_end:
            x = x_start
            while x <= x_end:
                c = screen_bitmap.getPixel(x, y)
                r = (c >> 16) & 0xFF
                g = (c >> 8) & 0xFF
                b = c & 0xFF

                c_max = max(r, g, b)
                c_min = min(r, g, b)
                sat = (c_max - c_min) / c_max if c_max > 0 else 0.0

                if r > 180 and (r - g > 60):
                    clickable_red_pixels += 1
                if (r - g < 40) or sat < 0.30:
                    disabled_pixels += 1

                total_sampled += 1
                x += step_x
            y += step_y

        if total_sampled == 0:
            return False
        red_ratio = clickable_red_pixels / total_sampled
        disabled_ratio = disabled_pixels / total_sampled
        return red_ratio < 0.15 or disabled_ratio > 0.65

    # If right-side button (Free-draw right button), sample around center_x or 0.52w..0.90w
    is_right_side_button = center_x is not None and center_x >= int(w * 0.50)
    if is_right_side_button:
        x_start = max(0, max(int(w * 0.52), center_x - int(w * 0.15)))
        x_end = min(w - 1, min(int(w * 0.90), center_x + int(w * 0.15)))
    else:
        # General popup button gray detection (outside 50-draw zone)
        x_start = max(0, int(w * 0.15))
        x_end = min(w - 1, int(w * 0.85))

    gray_pixels = 0
    total_sampled = 0
    y = y_start
    while y <= y_end:
        x = x_start
        while x <= x_end:
            c = screen_bitmap.getPixel(x, y)
            r = (c >> 16) & 0xFF
            g = (c >> 8) & 0xFF
            b = c & 0xFF
            c_max = max(r, g, b)
            c_min = min(r, g, b)
            sat = (c_max - c_min) / c_max if c_max > 0 else 0.0

            if is_right_side_button:
                if (r - g < 40) or sat < 0.30:
                    gray_pixels += 1
            else:
                max_diff = max(abs(r - g), abs(g - b), abs(r - b))
                if 60 <= r <= 235 and max_diff <= 15:
                    gray_pixels += 1
            total_sampled += 1
            x += 6
        y += 3
    return total_sampled > 0 and (gray_pixels / total_sampled) >= 0.40


def kotlin_is_fifty_draw_button_disabled(screen_bitmap: MockBitmap, button_rect: Rect = None) -> bool:
    """
    Direct faithful port of TemplateMatcher.kt isFiftyDrawButtonDisabled.
    """
    sw = screen_bitmap.width
    sh = screen_bitmap.height
    rect = button_rect if button_rect is not None else Rect(
        int(sw * 0.15), int(sh * 0.44), int(sw * 0.45), int(sh * 0.54)
    )

    clickable_red_pixels = 0
    disabled_pixels = 0
    total_sampled = 0

    step_y = max(1, rect.height() // 15)
    step_x = max(1, rect.width() // 30)

    y = max(0, rect.top)
    end_y = min(sh - 1, rect.bottom)
    start_x = max(0, rect.left)
    end_x = min(sw - 1, rect.right)

    while y <= end_y:
        x = start_x
        while x <= end_x:
            c = screen_bitmap.getPixel(x, y)
            r = (c >> 16) & 0xFF
            g = (c >> 8) & 0xFF
            b = c & 0xFF

            c_max = max(r, g, b)
            c_min = min(r, g, b)
            sat = (c_max - c_min) / c_max if c_max > 0 else 0.0

            if r > 180 and (r - g > 60):
                clickable_red_pixels += 1
            if (r - g < 40) or sat < 0.30:
                disabled_pixels += 1

            total_sampled += 1
            x += step_x
        y += step_y

    if total_sampled == 0:
        return False
    red_ratio = clickable_red_pixels / total_sampled
    disabled_ratio = disabled_pixels / total_sampled
    return red_ratio < 0.15 or disabled_ratio > 0.65


def kotlin_extract_ticket_count(ocr_items, sw=1080, sh=2400) -> int | None:
    """
    Direct faithful port of LotteryTask.kt extractTicketCount.
    """
    # 1. Modal header ticket pill (cy in 0.15f..0.26f, cx > 0.58f)
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

    # 2. Top bar asset fallback (cy in 0.03f..0.10f, cx in 0.48f..0.80f)
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

    # 3. General keyword fallback (cy < 0.25f with '張'/'票'/'ticket')
    for item in ocr_items:
        cy = item.bounds.centerY()
        in_top = cy < sh * 0.25 if sh > 0 else cy < 500
        t = item.text.replace(" ", "").replace(",", "")
        if in_top and ("張" in t or "票" in t or "ticket" in t.lower()):
            m = re.search(r"\d+", t)
            if m:
                return int(m.group(0))

    return None


def kotlin_find_target_draw_button(screen_bitmap: MockBitmap, ocr_items,
                                   sw=1080, sh=2400) -> tuple[str, Rect] | None:
    """
    Direct faithful port of LotteryTask.kt findTargetDrawButton.
    """
    ticket_count = kotlin_extract_ticket_count(ocr_items, sw, sh)
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

    # 1. 【第一順位】：50 抽 (BIG 50張 抽1次)
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

        if fifty_item:
            cy = fifty_item.bounds.centerY()
            cx = fifty_item.bounds.centerX()
            if not kotlin_is_popup_button_disabled_gray(screen_bitmap, cy, cx):
                return ("50張抽一次", fifty_item.bounds)

    # 2. 【第二順位】：免費看廣告抽一次
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

        if free_item:
            cy = free_item.bounds.centerY()
            cx = free_item.bounds.centerX()
            if not kotlin_is_popup_button_disabled_gray(screen_bitmap, cy, cx):
                return ("免費看廣告抽一次", free_item.bounds)

    # 3. 防呆
    return None


def kotlin_resolve_next_action(screen_bitmap: MockBitmap, ocr_items,
                               sw=1080, sh=2400) -> tuple[str, Rect]:
    """
    Direct faithful port of LotteryTask.kt resolveNextAction.
    """
    target = kotlin_find_target_draw_button(screen_bitmap, ocr_items, sw, sh)
    if target is not None:
        return target
    close_bounds = Rect(int(sw * 0.86), int(sh * 0.095), int(sw * 0.96), int(sh * 0.145))
    return ("關閉", close_bounds)


# ==============================================================================
# TEST SUITE 1: Synthetic Bitmaps, Varied Margins, Brightness Scaling
# ==============================================================================

class TestSyntheticAndRealBitmapsGraySampling:

    @pytest.mark.parametrize("margin_rgb, margin_desc", [
        ((0, 0, 0), "Pitch Black Margin"),
        ((40, 40, 40), "Dark Dimmed Overlay Margin"),
        ((158, 157, 155), "LineWalk Standard Dimmed Overlay Margin (Broke Old Code)"),
        ((180, 180, 180), "Medium Gray Margin"),
        ((220, 220, 220), "Threshold Light Gray Margin"),
        ((255, 255, 255), "Pure White Margin"),
        ((255, 0, 0), "Reddish Tinted Margin"),
        ((0, 120, 255), "Blue Tinted Margin"),
    ])
    def test_active_red_button_is_detected_active_under_all_margin_colors(self, margin_rgb, margin_desc):
        """
        Verify that an active red 50-draw button is classified as ACTIVE (disabled=False)
        regardless of screen margin colors (0..5% and 95..100% width).
        This specifically proves the removal of the old buggy dependency on (lr < 220 || rr < 220).
        """
        sw, sh = 1080, 2400
        # Create bitmap with specified margin color
        margin_argb = MockBitmap.to_argb(*margin_rgb)
        bmp = MockBitmap(sw, sh, default_argb=margin_argb)

        # Draw 50-draw button in its standard zone: x in 0.15*sw..0.45*sw, y in 0.44*sh..0.54*sh
        btn_rect = Rect(int(sw * 0.15), int(sh * 0.44), int(sw * 0.45), int(sh * 0.54))
        # Active red color: R=238, G=68, B=75 (R > 180, R-G = 170 > 60, Sat=0.71 > 0.3)
        red_argb = MockBitmap.to_argb(238, 68, 75)
        bmp.fillRect(btn_rect, red_argb)

        # Add 20% white text inside the button ("50張 抽1次")
        text_rect = Rect(int(sw * 0.20), int(sh * 0.47), int(sw * 0.40), int(sh * 0.51))
        white_argb = MockBitmap.to_argb(255, 255, 255)
        bmp.fillRect(text_rect, white_argb)

        # Evaluate button center Y
        cy = btn_rect.centerY()
        is_disabled = kotlin_is_popup_button_disabled_gray(bmp, cy)
        assert is_disabled is False, f"Active red button must be detected as active under {margin_desc}"

        # Dedicated function must also agree
        is_fifty_disabled = kotlin_is_fifty_draw_button_disabled(bmp, btn_rect)
        assert is_fifty_disabled is False, f"isFiftyDrawButtonDisabled must also report active under {margin_desc}"

    @pytest.mark.parametrize("margin_rgb, margin_desc", [
        ((0, 0, 0), "Pitch Black Margin"),
        ((40, 40, 40), "Dark Dimmed Overlay Margin"),
        ((158, 157, 155), "LineWalk Standard Dimmed Overlay Margin"),
        ((255, 255, 255), "Pure White Margin"),
    ])
    @pytest.mark.parametrize("gray_rgb, gray_desc", [
        ((178, 151, 153), "Empirical Real LineWalk Disabled Button (R=178, G=151, B=153)"),
        ((160, 160, 160), "Neutral Desaturated Gray (R=160, G=160, B=160)"),
        ((120, 120, 120), "Dark Gray Disabled Button (R=120, G=120, B=120)"),
        ((200, 200, 200), "Light Gray Disabled Button (R=200, G=200, B=200)"),
        ((185, 170, 165), "Slightly Warm Gray (R-G=15 < 40, Sat=0.10 < 0.3)"),
    ])
    def test_grayed_out_button_is_detected_disabled_under_all_margins_and_grays(
        self, margin_rgb, margin_desc, gray_rgb, gray_desc
    ):
        """
        Verify that disabled/grayed-out buttons are classified as DISABLED (disabled=True)
        under all margin conditions and various shades of gray.
        """
        sw, sh = 1080, 2400
        margin_argb = MockBitmap.to_argb(*margin_rgb)
        bmp = MockBitmap(sw, sh, default_argb=margin_argb)

        btn_rect = Rect(int(sw * 0.15), int(sh * 0.44), int(sw * 0.45), int(sh * 0.54))
        gray_argb = MockBitmap.to_argb(*gray_rgb)
        bmp.fillRect(btn_rect, gray_argb)

        # Even with some darker gray text
        text_rect = Rect(int(sw * 0.20), int(sh * 0.47), int(sw * 0.40), int(sh * 0.51))
        dark_text_argb = MockBitmap.to_argb(90, 90, 90)
        bmp.fillRect(text_rect, dark_text_argb)

        cy = btn_rect.centerY()
        is_disabled = kotlin_is_popup_button_disabled_gray(bmp, cy)
        assert is_disabled is True, f"Gray button ({gray_desc}) must be detected as disabled under {margin_desc}"

        is_fifty_disabled = kotlin_is_fifty_draw_button_disabled(bmp, btn_rect)
        assert is_fifty_disabled is True, f"isFiftyDrawButtonDisabled must also report disabled for {gray_desc}"

    @pytest.mark.parametrize("brightness_factor", [0.85, 0.90, 0.95, 1.0, 1.05, 1.10])
    def test_active_red_button_brightness_variation(self, brightness_factor):
        """
        Stress-test active red button under realistic brightness fluctuations.
        Base red: (238, 68, 75). Scaled by factor.
        """
        sw, sh = 1080, 2400
        bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        btn_rect = Rect(int(sw * 0.15), int(sh * 0.44), int(sw * 0.45), int(sh * 0.54))

        base_r, base_g, base_b = 238, 68, 75
        r = min(255, int(base_r * brightness_factor))
        g = min(255, int(base_g * brightness_factor))
        b = min(255, int(base_b * brightness_factor))

        bmp.fillRect(btn_rect, MockBitmap.to_argb(r, g, b))
        # 15% white text
        text_rect = Rect(int(sw * 0.20), int(sh * 0.47), int(sw * 0.38), int(sh * 0.50))
        bmp.fillRect(text_rect, MockBitmap.to_argb(255, 255, 255))

        cy = btn_rect.centerY()
        is_disabled = kotlin_is_popup_button_disabled_gray(bmp, cy)
        assert is_disabled is False, f"Scaled active red ({r},{g},{b}) must remain active"

    @pytest.mark.parametrize("sw, sh, aspect_label", [
        (1080, 1920, "16:9 Standard"),
        (1080, 2340, "19.5:9 Common"),
        (1080, 2400, "20:9 Galaxy S20/S21"),
        (1080, 2376, "19.8:9 Galaxy S24 FE SM-S7210"),
        (1080, 2640, "22:9 Galaxy Z Flip 5 SM-F731N"),
        (1440, 3120, "19.5:9 Galaxy S24 Ultra"),
        (1536, 2048, "4:3 Tablet"),
    ])
    def test_aspect_ratios_boundary_and_roi_coverage(self, sw, sh, aspect_label):
        """
        Verify that on various screen resolutions and aspect ratios,
        the 50-draw button sampling safely executes within bounds without exceptions,
        and accurately classifies both active and disabled buttons.
        """
        # 1. Test Active Red
        bmp_active = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        btn_rect = Rect(int(sw * 0.15), int(sh * 0.44), int(sw * 0.45), int(sh * 0.54))
        bmp_active.fillRect(btn_rect, MockBitmap.to_argb(238, 68, 75))

        cy = btn_rect.centerY()
        assert kotlin_is_popup_button_disabled_gray(bmp_active, cy) is False
        assert kotlin_is_fifty_draw_button_disabled(bmp_active, btn_rect) is False

        # 2. Test Disabled Gray
        bmp_gray = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        bmp_gray.fillRect(btn_rect, MockBitmap.to_argb(178, 151, 153))

        assert kotlin_is_popup_button_disabled_gray(bmp_gray, cy) is True
        assert kotlin_is_fifty_draw_button_disabled(bmp_gray, btn_rect) is True


# ==============================================================================
# TEST SUITE 2: OCR Ticket Counts 0 to 100 & Strict Priority Fallthrough
# ==============================================================================

class TestTicketCountsAndPriorityFallthrough:

    @pytest.mark.parametrize("tickets", range(0, 101))
    def test_strict_ticket_guard_for_all_counts_0_to_100(self, tickets):
        """
        PARAMETRIC STRESS TEST: Test all ticket counts from 0 to 100.
        Rule R4.1:
        - When tickets < 50 (0..49): 50-draw is STRICTLY FORBIDDEN even if 50-draw button is active red!
          Must divert to free draw if available, or close dialog if free draw in cooldown.
        - When tickets >= 50 (50..100): 50-draw is permitted if active red.
        """
        sw, sh = 1080, 2400

        # Create bitmap with active red 50-draw button and active yellow free-draw button
        bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        fifty_rect = Rect(int(sw * 0.10), int(sh * 0.44), int(sw * 0.48), int(sh * 0.54))
        free_rect = Rect(int(sw * 0.52), int(sh * 0.48), int(sw * 0.90), int(sh * 0.58))
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.90), int(sh * 0.23))

        # 50-draw button is active red
        bmp.fillRect(fifty_rect, MockBitmap.to_argb(238, 68, 75))
        # Free-draw button is active
        bmp.fillRect(free_rect, MockBitmap.to_argb(250, 180, 30))

        ocr_items = [
            OcrItem("BIG 抽抽樂", Rect(int(sw * 0.25), int(sh * 0.12), int(sw * 0.75), int(sh * 0.17))),
            OcrItem(f"{tickets}", pill_rect),
            OcrItem("50張 抽1次", fifty_rect),
            OcrItem("免費抽1次", free_rect),
        ]

        extracted = kotlin_extract_ticket_count(ocr_items, sw, sh)
        assert extracted == tickets, f"Expected extracted count {tickets}, got {extracted}"

        target = kotlin_find_target_draw_button(bmp, ocr_items, sw, sh)
        action, action_bounds = kotlin_resolve_next_action(bmp, ocr_items, sw, sh)

        if tickets < 50:
            # Under 50 tickets: MUST NOT CLICK 50-DRAW!
            assert target is not None
            label, bounds = target
            assert label == "免費看廣告抽一次", (
                f"Tickets={tickets} (<50): 50-draw must be strictly skipped and divert to free-draw!"
            )
            assert bounds == free_rect
            assert action == "免費看廣告抽一次"
            assert action_bounds == free_rect
        else:
            # 50 or more tickets: MUST CLICK 50-DRAW!
            assert target is not None
            label, bounds = target
            assert label == "50張抽一次", (
                f"Tickets={tickets} (>=50): 50-draw must be selected when button is active!"
            )
            assert bounds == fifty_rect
            assert action == "50張抽一次"
            assert action_bounds == fifty_rect

    @pytest.mark.parametrize("tickets", [0, 1, 10, 31, 48, 49])
    def test_insufficient_tickets_with_free_draw_in_cooldown_closes_dialog(self, tickets):
        """
        When tickets < 50 AND free draw is in cooldown (or unavailable),
        findTargetDrawButton MUST return None, and resolveNextAction MUST return '關閉'.
        """
        sw, sh = 1080, 2400
        bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        fifty_rect = Rect(int(sw * 0.10), int(sh * 0.44), int(sw * 0.48), int(sh * 0.54))
        free_cooling_rect = Rect(int(sw * 0.52), int(sh * 0.48), int(sw * 0.90), int(sh * 0.58))
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.90), int(sh * 0.23))

        bmp.fillRect(fifty_rect, MockBitmap.to_argb(238, 68, 75))

        ocr_items = [
            OcrItem(f"{tickets}張", pill_rect),
            OcrItem("50張 抽1次", fifty_rect),
            OcrItem("回復還需 04:32", free_cooling_rect),
        ]

        target = kotlin_find_target_draw_button(bmp, ocr_items, sw, sh)
        assert target is None, f"Tickets={tickets}: Should not find any target button when free is cooling"

        action, bounds = kotlin_resolve_next_action(bmp, ocr_items, sw, sh)
        assert action == "關閉", f"Tickets={tickets}: resolveNextAction must cleanly close dialog"
        # Close button is at top right
        assert bounds.centerX() > int(sw * 0.85)
        assert bounds.centerY() < int(sh * 0.15)

    def test_sharp_boundary_at_49_vs_50(self):
        """
        Sharp boundary testing:
        - 49: insufficient -> diverts to free draw
        - 50: sufficient -> chooses 50 draw
        """
        sw, sh = 1080, 2400
        bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        fifty_rect = Rect(int(sw * 0.10), int(sh * 0.44), int(sw * 0.48), int(sh * 0.54))
        free_rect = Rect(int(sw * 0.52), int(sh * 0.48), int(sw * 0.90), int(sh * 0.58))
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.90), int(sh * 0.23))

        bmp.fillRect(fifty_rect, MockBitmap.to_argb(238, 68, 75))
        bmp.fillRect(free_rect, MockBitmap.to_argb(250, 180, 30))

        # Test 49
        items_49 = [
            OcrItem("49", pill_rect),
            OcrItem("50張 抽1次", fifty_rect),
            OcrItem("免費抽1次", free_rect),
        ]
        target_49 = kotlin_find_target_draw_button(bmp, items_49, sw, sh)
        assert target_49[0] == "免費看廣告抽一次"

        # Test 50
        items_50 = [
            OcrItem("50", pill_rect),
            OcrItem("50張 抽1次", fifty_rect),
            OcrItem("免費抽1次", free_rect),
        ]
        target_50 = kotlin_find_target_draw_button(bmp, items_50, sw, sh)
        assert target_50[0] == "50張抽一次"

    @pytest.mark.parametrize("pill_text, expected_number", [
        ("31", 31),
        ("31張", 31),
        (" 31 ", 31),
        ("x31", 31),
        ("x 31", 31),
        ("持有 31 張", 31),
        ("49張", 49),
        ("50", 50),
        ("100", 100),
        ("999+", 999),
        ("0", 0),
        ("0張", 0),
    ])
    def test_ocr_pill_text_variations_parsing(self, pill_text, expected_number):
        """
        Verify ticket count regex parser handles various noise, spaces, suffixes, prefixes.
        """
        sw, sh = 1080, 2400
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.90), int(sh * 0.23))
        items = [OcrItem(pill_text, pill_rect)]
        count = kotlin_extract_ticket_count(items, sw, sh)
        assert count == expected_number, f"Failed parsing '{pill_text}' -> expected {expected_number}, got {count}"

    def test_five_draw_button_is_strictly_forbidden_under_all_conditions(self):
        """
        Requirement invariant:
        3. 【嚴禁點擊】：絕不點擊「5張 抽1次」按鈕！
        Even if tickets = 50 or tickets = 5, or button is active red,
        '5張 抽1次' MUST NEVER be clicked!
        """
        sw, sh = 1080, 2400
        bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        five_draw_rect = Rect(int(sw * 0.10), int(sh * 0.44), int(sw * 0.48), int(sh * 0.54))
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.90), int(sh * 0.23))

        bmp.fillRect(five_draw_rect, MockBitmap.to_argb(238, 68, 75))

        for ticket_val in [0, 5, 31, 50, 100]:
            items = [
                OcrItem(str(ticket_val), pill_rect),
                OcrItem("5張 抽1次", five_draw_rect),
            ]
            target = kotlin_find_target_draw_button(bmp, items, sw, sh)
            assert target is None, f"Tickets={ticket_val}: '5張 抽1次' must NEVER be returned!"
            action, _ = kotlin_resolve_next_action(bmp, items, sw, sh)
            assert action == "關閉", f"Tickets={ticket_val}: Must close instead of clicking 5-draw"

    def test_fifty_draw_grayed_out_falls_through_to_free_draw_even_with_many_tickets(self):
        """
        If tickets >= 50 (e.g. 800 tickets) but 50-draw button is GRAYED OUT (disabled),
        it MUST fall through to Free-draw (or Close).
        """
        sw, sh = 1080, 2400
        bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        fifty_rect = Rect(int(sw * 0.10), int(sh * 0.44), int(sw * 0.48), int(sh * 0.54))
        free_rect = Rect(int(sw * 0.52), int(sh * 0.48), int(sw * 0.90), int(sh * 0.58))
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.90), int(sh * 0.23))

        # 50-draw button is GRAY
        bmp.fillRect(fifty_rect, MockBitmap.to_argb(178, 151, 153))
        # Free-draw button is active
        bmp.fillRect(free_rect, MockBitmap.to_argb(250, 180, 30))

        items = [
            OcrItem("800", pill_rect),
            OcrItem("50張 抽1次", fifty_rect),
            OcrItem("免費抽1次", free_rect),
        ]

        target = kotlin_find_target_draw_button(bmp, items, sw, sh)
        assert target is not None, (
            "CRITICAL BUG: When 50-draw button is grayed out, free-draw button must be selected! "
            "Currently, isPopupButtonDisabledGray samples the left button (0.15..0.45) for red pixels "
            "when safeCy in 0.38..0.60, causing active right-side free-draw button to be falsely flagged as gray!"
        )
        label, bounds = target
        assert label == "免費看廣告抽一次", "Grayed 50-draw must fall through to free-draw even with 800 tickets"
        assert bounds == free_rect

    def test_tickets_under_50_with_gray_fifty_button_must_click_free_draw(self):
        """
        When tickets < 50 (e.g. 31 tickets) AND 50-draw button is rendered gray on screen,
        findTargetDrawButton MUST select Free-draw!
        Currently fails because isPopupButtonDisabledGray checks left ROI [0.15..0.45]
        even when checking the right-side free-draw button!
        """
        sw, sh = 1080, 2400
        bmp = MockBitmap(sw, sh, default_argb=MockBitmap.to_argb(158, 157, 155))
        fifty_rect = Rect(int(sw * 0.10), int(sh * 0.44), int(sw * 0.48), int(sh * 0.54))
        free_rect = Rect(int(sw * 0.52), int(sh * 0.48), int(sw * 0.90), int(sh * 0.58))
        pill_rect = Rect(int(sw * 0.65), int(sh * 0.18), int(sw * 0.90), int(sh * 0.23))

        # 50-draw button is GRAY
        bmp.fillRect(fifty_rect, MockBitmap.to_argb(178, 151, 153))
        # Free-draw button is active
        bmp.fillRect(free_rect, MockBitmap.to_argb(250, 180, 30))

        items = [
            OcrItem("31", pill_rect),
            OcrItem("50張 抽1次", fifty_rect),
            OcrItem("免費抽1次", free_rect),
        ]

        target = kotlin_find_target_draw_button(bmp, items, sw, sh)
        assert target is not None, (
            "CRITICAL BUG: When tickets < 50 and 50-draw is gray, free-draw must be selected!"
        )
        label, bounds = target
        assert label == "免費看廣告抽一次"
        assert bounds == free_rect


# ==============================================================================
# TEST SUITE 3: Kotlin Source Code AST / Semantic Invariants
# ==============================================================================

class TestKotlinSourceInvariantsMilestone3:

    def test_lottery_task_ast_invariants(self):
        """Verify structural invariants in LotteryTask.kt."""
        code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")

        # Function definitions
        assert "fun extractTicketCount(" in code
        assert "fun resolveNextAction(" in code
        assert "fun findTargetDrawButton(" in code
        assert "fun findFreeDrawOnly(" in code
        assert "fun getCloseButtonBounds(" in code

        # Guard invariant
        assert "ticketCount != null && ticketCount < 50" in code or (
            "ticketCount != null" in code and "ticketCount < 50" in code
        )
        assert "!isFiftyInCooldown && !isTicketInsufficient" in code

        # Priority ordering comments
        idx_p1 = code.find("【第一順位】：50 抽")
        idx_p2 = code.find("【第二順位】：免費看廣告抽一次")
        assert idx_p1 != -1 and idx_p2 != -1
        assert idx_p1 < idx_p2

        # Anti-trap: 5張 exclusion
        assert "!t.contains(\"5張\")" in code

    def test_template_matcher_ast_invariants(self):
        """Verify structural invariants in TemplateMatcher.kt."""
        code = (SRC_DIR / "vision/TemplateMatcher.kt").read_text(encoding="utf-8")

        # Backward compatibility identifier
        assert "leftMarginColor" in code, "leftMarginColor must be retained for existing test compatibility"

        # Sampling methods
        assert "fun isPopupButtonDisabledGray(" in code
        assert "fun isFiftyDrawButtonDisabled(" in code

        # Color thresholds
        assert "r > 180 && (r - g > 60)" in code or "Red > 180 && Red - Green > 60" in code
        assert "(r - g < 40) || sat < 0.30f" in code or "r - g < 40 || Saturation < 0.3" in code

        # Ratios
        assert "redRatio < 0.15f || disabledRatio > 0.65f" in code or (
            "redRatio < 0.15f" in code and "disabledRatio > 0.65f" in code
        )


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
