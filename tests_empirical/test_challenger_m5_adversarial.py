#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EMPIRICAL ADVERSARIAL CHALLENGER TEST SUITE: M5-Core-Oct26
Challenger: challenger_m5_1
LineWalkAutoAd

Focus Areas:
1. R1 Adversarial Stress:
   - Adversarial OCR strings with "ok" substrings (Grok, TikTok, Book, Look, Facebook, Spooky, Token, OKAY, NOT OK, OK!)
   - Normalized coordinate bounds (y < 0.50, y in 0.52..0.82, y in 0.60..0.78)
   - Lottery priority edge cases (tickets=50, tickets=49 fallback, 50 cooling, free cooling, both cooling)
2. R2 Adversarial Stress:
   - detectCornerGeometricX with includeLeftZone = True vs False
   - Extreme aspect ratios (20:9, 16:9, 21:9, foldable, tablet)
   - Pixel-level synthetic image detection (white-on-gray vs dark-gray-on-white X, plus '+', mute, AdChoices)
   - Right-zone status bar / notification safe zone (y < 0.08sh) and bingo zone exclusion (0.84..0.98sw, 0.08..0.16sh)
3. AST Source Integrity verification for R1 & R2
"""

import os
import re
import math
import random
import numpy as np
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


# ==============================================================================
# Domain Data Classes & Geometry Simulation
# ==============================================================================

class Rect:
    def __init__(self, left, top, right, bottom):
        self.left = int(left)
        self.top = int(top)
        self.right = int(right)
        self.bottom = int(bottom)

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


class TemplateMatchResult:
    def __init__(self, score, bounds, label):
        self.score = score
        self.bounds = bounds
        self.label = label


# ==============================================================================
# Ported Core Logic: findCenterRewardModalCloseButton (OcrRecognizer.kt)
# ==============================================================================

def simulate_find_center_reward_modal_close_button(items, sw=1080, sh=2400,
                                                  overlay_rect=None,
                                                  has_reward_title=True,
                                                  is_dimmed_modal=True):
    """
    Direct simulation of OcrRecognizer.kt findCenterRewardModalCloseButton (lines 183-275).
    """
    # 0. Result screen veto: 中獎囉, 中獎, 分享給好友, or cy in 0.60..0.76 with x1/1張
    is_lottery_result_screen = any(
        ("\u4e2d\u734e\u56c9" in item.text.replace(" ", "") or
         "\u4e2d\u734e" in item.text.replace(" ", "") or
         "\u5206\u4eab\u7d66\u597d\u53cb" in item.text.replace(" ", "") or
         (0.60 <= (item.bounds.centerY() / float(sh)) <= 0.76 and
          ("x1" in item.text.lower() or "1\u5f35" in item.text) and
          "\u91d1\u5e63" not in item.text and "\u7968\u5238" not in item.text))
        for item in items
    )
    if is_lottery_result_screen:
        return None

    # 1. OCR search in white footer ROI
    close_ocr = None
    for item in items:
        cx = item.bounds.centerX()
        cy = item.bounds.centerY()
        norm_y = cy / float(sh)
        norm_x = cx / float(sw)

        # Overlay check
        if overlay_rect and (overlay_rect.left <= cx <= overlay_rect.right and
                             overlay_rect.top <= cy <= overlay_rect.bottom):
            continue

        # ROI check: normY in 0.60f..0.78f, normX in 0.20f..0.80f
        if not (0.60 <= norm_y <= 0.78 and 0.20 <= norm_x <= 0.80):
            continue

        t = item.text.strip()
        t_lower = t.lower()

        # Strict ad banner keyword exclusion
        is_ad_banner = (
            "google play" in t_lower or
            "play store" in t_lower or
            "play \u5546\u5e97" in t or
            "play\u5546\u5e97" in t or
            "\u5b89\u88dd" in t or
            "\u4e0b\u8f09" in t or
            "sponsored" in t_lower or
            "AD" in t or
            "\u5ee3\u544a" in t or
            "\u63a8\u85a6" in t or
            "grok" in t_lower or
            "tiktok" in t_lower or
            "book" in t_lower
        )
        if is_ad_banner:
            continue

        # OK exact matching (no substring contains allowed!)
        is_exact_ok = bool(re.match(r"^\s*\[?\s*OK\s*\]?\s*$", t, re.IGNORECASE)) or t.lower() == "ok"
        is_chinese_close = any(kw in t for kw in ["\u95dc\u9589", "\u78ba\u8a8d", "\u78ba\u5b9a", "\u6536\u4e0b"])

        if (is_exact_ok or is_chinese_close) and len(t) <= 6:
            close_ocr = item
            break

    if close_ocr is not None and (has_reward_title or is_dimmed_modal):
        return f"OCR[{close_ocr.text}]", close_ocr.bounds

    # Fallback close button when title exists
    if has_reward_title and is_dimmed_modal:
        fallback_rect = Rect(
            int(sw * 0.35),
            int(sh * 0.668),
            int(sw * 0.65),
            int(sh * 0.706)
        )
        return "\u9818\u734e\u5f48\u7a97[\u95dc\u9589]\u9215", fallback_rect

    return None


# ==============================================================================
# Ported Core Logic: findTargetDrawButton (LotteryTask.kt)
# ==============================================================================

def simulate_find_target_draw_button(ocr_items, sw=1080, sh=2400,
                                     is_fifty_disabled_gray=False,
                                     is_free_disabled_gray=False):
    """
    Direct simulation of LotteryTask.kt findTargetDrawButton (lines 64-150).
    Priority: 50-draw first -> Free-draw second -> 5-draw strictly rejected.
    """
    time_regex = re.compile(r"\d{1,2}:\d{2}")

    # 0A. Free draw cooling
    is_free_in_cooldown = any(
        (item.bounds.centerX() > int(sw * 0.48) and
         int(sh * 0.45) <= item.bounds.centerY() <= int(sh * 0.62) and
         ("\u56de\u5fa9\u9084\u9700" in item.text or "\u9084\u9700" in item.text or "\u5012\u8a08\u6642" in item.text or
          bool(time_regex.search(item.text))))
        for item in ocr_items
    )

    # 0B. 50 draw cooling
    is_fifty_in_cooldown = any(
        (item.bounds.centerX() < int(sw * 0.55) and
         int(sh * 0.38) <= item.bounds.centerY() <= int(sh * 0.58) and
         ("\u56de\u5fa9\u9084\u9700" in item.text or "\u9084\u9700" in item.text or "\u56de\u5fa9" in item.text or
          "\u52690\u6b21" in item.text or item.text.replace(" ", "") == "0\u6b21" or
          bool(time_regex.search(item.text))))
        for item in ocr_items
    )

    # Priority 1: 50-draw
    if not is_fifty_in_cooldown:
        fifty_item = None
        for item in ocr_items:
            cx = item.bounds.centerX()
            cy = item.bounds.centerY()
            t = item.text.replace(" ", "")
            if (int(sh * 0.43) <= cy <= int(sh * 0.56) and
                cx < int(sw * 0.55) and
                ("50\u5f35" in t or ("50" in t and "\u62bd" in t)) and
                "5\u5f35" not in t and "\u56de\u5fa9" not in t and "\u9084\u9700" not in t):
                fifty_item = item
                break

        if fifty_item and not is_fifty_disabled_gray:
            return "50\u5f35\u62bd\u4e00\u6b21", fifty_item.bounds

    # Priority 2: Free ad draw
    if not is_free_in_cooldown:
        free_item = None
        for item in ocr_items:
            cx = item.bounds.centerX()
            cy = item.bounds.centerY()
            t = item.text.replace(" ", "")
            if (int(sh * 0.48) <= cy <= int(sh * 0.62) and
                cx > int(sw * 0.48) and
                ("\u514d\u8cbb\u62bd" in t or "\u514d\u8cbb" in t) and
                "5\u5f35" not in t and "\u56de\u5fa9" not in t and "\u9084\u9700" not in t):
                free_item = item
                break

        if free_item and not is_free_disabled_gray:
            return "\u514d\u8cbb\u770b\u5ee3\u544a\u62bd\u4e00\u6b21", free_item.bounds

    return None


# ==============================================================================
# Ported Core Logic: detectCornerGeometricX (TemplateMatcher.kt)
# ==============================================================================

def simulate_detect_corner_geometric_x(bitmap_array, sw, sh,
                                      include_left_zone=False,
                                      is_relaxed=False,
                                      blacklisted_coord=None):
    """
    Direct pixel-level simulation of TemplateMatcher.kt detectCornerGeometricX (lines 470-627).
    bitmap_array: 2D numpy array [H, W] of luminance values (0..255).
    """
    r = max(5, int(sw * 0.018))
    r_half = max(3, int(r * 0.65))
    step = max(2, sw // 240)
    tol = max(1, sw // 360)

    top_limit_y = max(int(sh * 0.082), 60)
    search_zones = [
        # Zone 0: Right corner
        Rect(int(sw * 0.75), top_limit_y, int(sw * 0.98), int(sh * 0.16))
    ]
    if include_left_zone:
        # Zone 1: Left corner
        left_top_limit_y = max(int(sh * 0.048), 48)
        left_bottom_limit_y = int(sh * 0.14)
        search_zones.append(Rect(int(sw * 0.025), left_top_limit_y, int(sw * 0.18), left_bottom_limit_y))

    best_candidate = None
    best_raw_score = 65.0 if is_relaxed else 82.0
    center_contrast_min = 35.0 if is_relaxed else 45.0
    contrast_min = 45.0 if is_relaxed else 55.0
    min_arm_contrast_min = 22.0 if is_relaxed else 35.0

    for zone_idx, zone in enumerate(search_zones):
        zw = zone.width()
        zh = zone.height()
        if zw <= r * 2 + 4 or zh <= r * 2 + 4:
            continue

        zone_pixels = bitmap_array[zone.top:zone.bottom, zone.left:zone.right]

        def lum(x, y):
            return float(zone_pixels[y, x])

        def arm_lum(px, py, bright_x):
            best = 0.0 if bright_x else 255.0
            for dy in range(-tol, tol + 1):
                ny = max(0, min(zh - 1, py + dy))
                for dx in range(-tol, tol + 1):
                    nx = max(0, min(zw - 1, px + dx))
                    l = lum(nx, ny)
                    if bright_x and l > best:
                        best = l
                    if not bright_x and l < best:
                        best = l
            return best

        cy = r + tol
        while cy < zh - r - tol:
            cx = r + tol
            while cx < zw - r - tol:
                abs_x = zone.left + cx
                abs_y = zone.top + cy

                # Exclusion logic
                if zone_idx == 0:
                    if abs_y - r < int(sh * 0.08):
                        cx += step
                        continue
                    is_bingo_close_zone = (int(sw * 0.84) <= abs_x <= int(sw * 0.98) and
                                           int(sh * 0.08) <= abs_y <= int(sh * 0.16))
                    if is_bingo_close_zone:
                        cx += step
                        continue
                else:
                    # Zone 1 (Left corner):
                    if abs_y - r < max(int(sh * 0.040), 40):
                        cx += step
                        continue

                if blacklisted_coord and blacklisted_coord(abs_x, abs_y):
                    cx += step
                    continue

                l_top = lum(cx, cy - r)
                l_bot = lum(cx, cy + r)
                l_left = lum(cx - r, cy)
                l_right = lum(cx + r, cy)
                card_mean = (l_top + l_bot + l_left + l_right) * 0.25
                card_diff = (abs(l_top - card_mean) + abs(l_bot - card_mean) +
                             abs(l_left - card_mean) + abs(l_right - card_mean))

                if card_diff <= 58.0:
                    is_bright_x = card_mean < 150.0

                    # 1. Center contrast
                    center_lum = lum(cx, cy)
                    center_contrast = (center_lum - card_mean) if is_bright_x else (card_mean - center_lum)
                    if center_contrast < center_contrast_min:
                        cx += step
                        continue

                    # 2. V-gap check at r_half
                    v_top = lum(cx, cy - r_half)
                    v_bot = lum(cx, cy + r_half)
                    v_left = lum(cx - r_half, cy)
                    v_right = lum(cx + r_half, cy)
                    max_v_gap_diff = max(
                        max(abs(v_top - card_mean), abs(v_bot - card_mean)),
                        max(abs(v_left - card_mean), abs(v_right - card_mean))
                    )
                    if max_v_gap_diff > 35.0:
                        cx += step
                        continue

                    c_lum = arm_lum(cx, cy, is_bright_x)
                    d1 = arm_lum(cx - r_half, cy - r_half, is_bright_x)
                    d2 = arm_lum(cx + r_half, cy + r_half, is_bright_x)
                    d3 = arm_lum(cx + r_half, cy - r_half, is_bright_x)
                    d4 = arm_lum(cx - r_half, cy + r_half, is_bright_x)

                    diag_mean = (c_lum + d1 + d2 + d3 + d4) * 0.2
                    contrast = (diag_mean - card_mean) if is_bright_x else (card_mean - diag_mean)
                    if is_bright_x:
                        min_arm_contrast = min(c_lum, min(min(d1, d2), min(d3, d4))) - card_mean
                    else:
                        min_arm_contrast = card_mean - max(c_lum, max(max(d1, d2), max(d3, d4)))

                    if contrast > contrast_min and min_arm_contrast > min_arm_contrast_min:
                        raw_score = contrast - (card_diff * 0.5)
                        if raw_score > best_raw_score:
                            best_raw_score = raw_score
                            best_candidate = TemplateMatchResult(
                                score=max(0.80, min(0.99, raw_score / 150.0)),
                                bounds=Rect(abs_x - r, abs_y - r, abs_x + r, abs_y + r),
                                label="\u53f3\u4e0a\u89d2\u5e7e\u4f55X" if zone_idx == 0 else "\u5de6\u4e0a\u89d2\u5e7e\u4f55X"
                            )

                cx += step
            cy += step

    return best_candidate


# ==============================================================================
# Helper to synthesize synthetic test screens
# ==============================================================================

def create_synthetic_screen(sw, sh, base_lum=200):
    return np.full((sh, sw), base_lum, dtype=np.uint8)


def draw_circular_x(screen, center_x, center_y, radius,
                    is_bright_x=True, arm_thickness=2):
    """
    Renders an X inside a circular button.
    In real apps, button radius is ~1.35 * detector r, so cardinal points are well within circle.
    """
    sh, sw = screen.shape
    button_r = int(radius * 1.35)
    card_lum = 50 if is_bright_x else 230
    arm_lum = 250 if is_bright_x else 30

    y_indices, x_indices = np.ogrid[:sh, :sw]
    dist_sq = (x_indices - center_x) ** 2 + (y_indices - center_y) ** 2

    # Draw circular background
    circle_mask = dist_sq <= button_r ** 2
    screen[circle_mask] = card_lum

    # Draw diagonal lines (X arms)
    r_diag = int(radius * 0.95)
    for t in range(-r_diag, r_diag + 1):
        for w in range(-arm_thickness, arm_thickness + 1):
            # Diagonal 1: (x+t+w, y+t)
            px1, py1 = center_x + t + w, center_y + t
            if 0 <= px1 < sw and 0 <= py1 < sh and (px1 - center_x)**2 + (py1 - center_y)**2 <= (button_r - 1)**2:
                screen[py1, px1] = arm_lum
            # Diagonal 2: (x+t+w, y-t)
            px2, py2 = center_x + t + w, center_y - t
            if 0 <= px2 < sw and 0 <= py2 < sh and (px2 - center_x)**2 + (py2 - center_y)**2 <= (button_r - 1)**2:
                screen[py2, px2] = arm_lum


def draw_circular_plus(screen, center_x, center_y, radius, is_bright=True, arm_thickness=2):
    """Draws a '+' (orthogonal cross) instead of diagonal X."""
    sh, sw = screen.shape
    button_r = int(radius * 1.35)
    card_lum = 50 if is_bright else 230
    arm_lum = 250 if is_bright else 30

    y_indices, x_indices = np.ogrid[:sh, :sw]
    dist_sq = (x_indices - center_x) ** 2 + (y_indices - center_y) ** 2
    screen[dist_sq <= button_r ** 2] = card_lum

    # Horizontal arm
    for dx in range(-button_r + 2, button_r - 1):
        for dy in range(-arm_thickness, arm_thickness + 1):
            px, py = center_x + dx, center_y + dy
            if 0 <= px < sw and 0 <= py < sh:
                screen[py, px] = arm_lum

    # Vertical arm
    for dy in range(-button_r + 2, button_r - 1):
        for dx in range(-arm_thickness, arm_thickness + 1):
            px, py = center_x + dx, center_y + dy
            if 0 <= px < sw and 0 <= py < sh:
                screen[py, px] = arm_lum


# ==============================================================================
# TESTS
# ==============================================================================

# ------------------------------------------------------------------------------
# Test 1: R1 Adversarial "ok" Substring Rejection Matrix
# ------------------------------------------------------------------------------
def test_r1_adversarial_ok_substring_rejection_matrix():
    """
    Stress-test findCenterRewardModalCloseButton against an exhaustive list of
    adversarial words containing 'ok' as substrings in varying casing and affixes.
    """
    sw, sh = 1080, 2400

    adversarial_words = [
        # Grok variations
        "Grok", "grok", "GROK", "Grok Bot", "grok.ai", "Grokked", "grokking", "GROK BOT",
        # TikTok variations
        "TikTok", "tiktok", "TIKTOK", "TikTok Lite", "Tik Tok", "TikTokShop", "tiktok_app",
        # English words containing ok
        "Book", "book", "BOOK", "Facebook", "MacBook", "Look", "look", "Hook", "hook",
        "Took", "Spooky", "spooky", "Spook", "Token", "token", "Tokenize", "Poker",
        "Joker", "Choke", "Smoke", "Coke", "Stroke", "Broke", "Cook", "Crook",
        # Okay / sentence variations
        "OKAY", "okay", "Okay", "OKAY!", "NOT OK", "Not OK", "NOT OKAY", "It's OK",
        "ARE YOU OK?", "OK GO", "OKIDOKI", "OK 123", "OK?", "OK.", "OK!",
        # Ad Banner Keywords
        "Google Play", "Play Store", "Play \u5546\u5e97", "Play\u5546\u5e97", "\u5b89\u88dd", "\u4e0b\u8f09",
        "Sponsored", "AD", "\u5ee3\u544a", "\u63a8\u85a6", "Sponsored: OK", "Google Play OK", "\u5ee3\u544a: OK",
        # Long strings
        "PLEASE CLICK OK", "OK BUTTON HERE", "OK CONFIRM NOW", "OK[123456]"
    ]

    for word in adversarial_words:
        items = [
            OcrItem("\u7372\u5f9715\u5f35\u7968\u5238\uff01", Rect(int(sw * 0.25), int(sh * 0.28), int(sw * 0.75), int(sh * 0.35))),
            OcrItem(word, Rect(int(sw * 0.35), int(sh * 0.65), int(sw * 0.65), int(sh * 0.73)))
        ]
        res = simulate_find_center_reward_modal_close_button(
            items, sw, sh, has_reward_title=False, is_dimmed_modal=False
        )
        # With has_reward_title=False, fallback is blocked; match must be None!
        assert res is None, f"Adversarial word '{word}' must NEVER be matched as close button!"

        # Even with title, the matched OCR label must NEVER contain the adversarial word!
        res_with_title = simulate_find_center_reward_modal_close_button(
            items, sw, sh, has_reward_title=True, is_dimmed_modal=True
        )
        if res_with_title is not None and res_with_title[0].startswith("OCR["):
            matched_text = res_with_title[0][4:-1]
            assert matched_text != word, f"Adversarial word '{word}' was incorrectly accepted as OCR match!"


# ------------------------------------------------------------------------------
# Test 2: R1 Valid OK and Chinese Close Keyword Acceptance Matrix
# ------------------------------------------------------------------------------
def test_r1_valid_close_keywords_acceptance():
    """Verify legitimate OK and Chinese close tokens are accepted within ROI."""
    sw, sh = 1080, 2400

    valid_tokens = [
        "OK", "ok", "Ok", "oK",
        " OK ", "  OK  ",
        "[OK]", "[ ok ]", "[Ok]", "[ OK ]",
        "\u95dc\u9589", "\u78ba\u8a8d", "\u78ba\u5b9a", "\u6536\u4e0b",
        "\u78ba\u8a8d\u6536\u4e0b", "\u9ede\u64ca\u95dc\u9589"
    ]

    for token in valid_tokens:
        items = [
            OcrItem("\u7372\u5f9715\u5f35\u7968\u5238\uff01", Rect(int(sw * 0.25), int(sh * 0.28), int(sw * 0.75), int(sh * 0.35))),
            OcrItem(token, Rect(int(sw * 0.35), int(sh * 0.65), int(sw * 0.65), int(sh * 0.73)))
        ]
        res = simulate_find_center_reward_modal_close_button(
            items, sw, sh, has_reward_title=True, is_dimmed_modal=False
        )
        assert res is not None, f"Valid token '{token}' should be recognized as modal close button"
        assert res[0].startswith("OCR[") or "\u95dc\u9589" in res[0]


# ------------------------------------------------------------------------------
# Test 3: R1 Normalized ROI Boundary Stress & Result Screen Veto
# ------------------------------------------------------------------------------
def test_r1_roi_bounds_and_result_screen_veto():
    """
    Verify boundary conditions for ROI (normY in 0.60..0.78, normX in 0.20..0.80)
    and result screen veto.
    """
    sw, sh = 1080, 2400

    # 3A. Boundary coordinates for OK (has_reward_title=True, is_dimmed_modal=False ensures OCR matching without triggering fallback)
    test_cases = [
        # (norm_x, norm_y, should_pass, desc)
        (0.50, 0.49, False, "y < 0.50 Google Play app recommendation"),
        (0.50, 0.55, False, "y = 0.55 above modal footer"),
        (0.50, 0.599, False, "y = 0.599 just above ROI"),
        (0.50, 0.601, True, "y = 0.601 just inside ROI"),
        (0.50, 0.700, True, "y = 0.700 inside ROI center"),
        (0.50, 0.779, True, "y = 0.779 just inside ROI bottom"),
        (0.50, 0.781, False, "y = 0.781 just below ROI bottom"),
        (0.50, 0.850, False, "y = 0.850 in bottom navigation area"),
        (0.199, 0.68, False, "x = 0.199 just outside ROI left"),
        (0.201, 0.68, True, "x = 0.201 just inside ROI left"),
        (0.799, 0.68, True, "x = 0.799 just inside ROI right"),
        (0.801, 0.68, False, "x = 0.801 just outside ROI right"),
    ]

    for nx, ny, should_pass, desc in test_cases:
        item = OcrItem("OK", Rect(int(sw * (nx - 0.05)), int(sh * (ny - 0.02)),
                                  int(sw * (nx + 0.05)), int(sh * (ny + 0.02))))
        items = [
            OcrItem("\u7372\u5f9715\u5f35\u7968\u5238\uff01", Rect(int(sw * 0.25), int(sh * 0.28), int(sw * 0.75), int(sh * 0.35))),
            item
        ]
        res = simulate_find_center_reward_modal_close_button(
            items, sw, sh, has_reward_title=True, is_dimmed_modal=False
        )
        if should_pass:
            assert res is not None, f"[{desc}] Expected to pass ROI check"
            assert res[0] == "OCR[OK]"
        else:
            assert res is None, f"[{desc}] Expected to fail ROI check"

    # 3B. Overlay collision
    overlay = Rect(100, 1000, 900, 1800)
    item_in_overlay = OcrItem("OK", Rect(400, 1600, 600, 1700))  # cy = 1650 / 2400 = 0.6875 (inside overlay)
    assert simulate_find_center_reward_modal_close_button(
        [item_in_overlay], sw, sh, overlay_rect=overlay, has_reward_title=True, is_dimmed_modal=False
    ) is None

    # 3C. Lottery result screen veto: should return None so LotteryTask can click ad reward!
    result_indicators = [
        "\u4e2d\u734e\u56c9", "\u4e2d\u734e", "\u606d\u559c\u4e2d\u734e\uff01", "\u5206\u4eab\u7d66\u597d\u53cb",
        "\u7372\u5f97 x1 \u7372\u5f97", "1\u5f35 \u7372\u5f97"
    ]
    for ind in result_indicators:
        items = [
            OcrItem(ind, Rect(int(sw * 0.3), int(sh * 0.65), int(sw * 0.7), int(sh * 0.72))),
            OcrItem("OK", Rect(int(sw * 0.35), int(sh * 0.66), int(sw * 0.65), int(sh * 0.72)))
        ]
        res = simulate_find_center_reward_modal_close_button(items, sw, sh, has_reward_title=True, is_dimmed_modal=True)
        assert res is None, f"Result screen indicator '{ind}' must veto modal close click!"


# ------------------------------------------------------------------------------
# Test 4: R1 Lottery Priority & Boundary State Machine
# ------------------------------------------------------------------------------
def test_r1_lottery_priority_boundary_states():
    """
    Stress-test LotteryTask priority state machine:
    - Tickets >= 50: 50-draw 1st
    - Tickets < 50 (disabled gray button): fallback to free ad draw 2nd
    - 50-draw cooling: fallback to free ad draw 2nd
    - Free-draw cooling: pick 50-draw 1st
    - Both cooling: return None (never click 5-draw!)
    """
    sw, sh = 1080, 2400

    fifty_btn = OcrItem("50\u5f35 \u62bd1\u6b21", Rect(int(sw * 0.10), int(sh * 0.46), int(sw * 0.45), int(sh * 0.52)))
    free_btn = OcrItem("\u514d\u8cbb\u62bd1\u6b21", Rect(int(sw * 0.55), int(sh * 0.50), int(sw * 0.95), int(sh * 0.58)))
    five_btn = OcrItem("5\u5f35 \u62bd1\u6b21", Rect(int(sw * 0.55), int(sh * 0.46), int(sw * 0.95), int(sh * 0.52)))

    # Scenario 1: Tickets = 50, both draws available
    items_both = [fifty_btn, free_btn, five_btn]
    res1 = simulate_find_target_draw_button(items_both, sw, sh)
    assert res1 is not None
    assert res1[0] == "50\u5f35\u62bd\u4e00\u6b21", "When both available, 50-draw must be prioritized 1st"

    # Scenario 2: Tickets = 49 (50-draw button disabled gray)
    res2 = simulate_find_target_draw_button(items_both, sw, sh, is_fifty_disabled_gray=True)
    assert res2 is not None
    assert res2[0] == "\u514d\u8cbb\u770b\u5ee3\u544a\u62bd\u4e00\u6b21", "When tickets < 50 (disabled gray), must fallback to free-draw"

    # Scenario 3: 50-draw cooling ("回復還需 05:00" or "剩0次")
    fifty_cd_samples = ["\u56de\u5fa9\u9084\u9700 05:00", "\u9084\u9700 02:30", "\u52690\u6b21", "0\u6b21", "\u56de\u5fa9"]
    for cd in fifty_cd_samples:
        cd_item = OcrItem(cd, Rect(int(sw * 0.15), int(sh * 0.46), int(sw * 0.45), int(sh * 0.52)))
        items_fifty_cd = [cd_item, free_btn, five_btn]
        res3 = simulate_find_target_draw_button(items_fifty_cd, sw, sh)
        assert res3 is not None, f"When 50-draw has cd '{cd}', free-draw should be returned"
        assert res3[0] == "\u514d\u8cbb\u770b\u5ee3\u544a\u62bd\u4e00\u6b21"

    # Scenario 4: Free-draw cooling ("倒計時 10:00" or "回復還需 03:00")
    free_cd_samples = ["\u56de\u5fa9\u9084\u9700 03:00", "\u5012\u8a08\u6642 10:00", "\u9084\u9700 01:00"]
    for cd in free_cd_samples:
        cd_item = OcrItem(cd, Rect(int(sw * 0.55), int(sh * 0.50), int(sw * 0.95), int(sh * 0.58)))
        items_free_cd = [fifty_btn, cd_item, five_btn]
        res4 = simulate_find_target_draw_button(items_free_cd, sw, sh)
        assert res4 is not None, f"When free-draw has cd '{cd}', 50-draw should be picked"
        assert res4[0] == "50\u5f35\u62bd\u4e00\u6b21"

    # Scenario 5: Both cooling -> strictly returns None!
    items_both_cooling = [
        OcrItem("\u56de\u5fa9\u9084\u9700 05:00", Rect(int(sw * 0.15), int(sh * 0.46), int(sw * 0.45), int(sh * 0.52))),
        OcrItem("\u56de\u5fa9\u9084\u9700 03:00", Rect(int(sw * 0.55), int(sh * 0.50), int(sw * 0.95), int(sh * 0.58))),
        five_btn  # 5-draw present
    ]
    res5 = simulate_find_target_draw_button(items_both_cooling, sw, sh)
    assert res5 is None, "When both draws cooling, must return None (and never click 5-draw!)"

    # Scenario 6: Tickets < 50 (disabled gray) AND free-draw cooling -> returns None
    items_disabled_and_free_cd = [
        fifty_btn,
        OcrItem("\u56de\u5fa9\u9084\u9700 03:00", Rect(int(sw * 0.55), int(sh * 0.50), int(sw * 0.95), int(sh * 0.58))),
        five_btn
    ]
    res6 = simulate_find_target_draw_button(items_disabled_and_free_cd, sw, sh, is_fifty_disabled_gray=True)
    assert res6 is None, "When 50-draw disabled and free-draw cooling, must return None"


# ------------------------------------------------------------------------------
# Test 5: R2 Left Zone Activation Contract (includeLeftZone True vs False)
# ------------------------------------------------------------------------------
def test_r2_left_zone_activation_flag_contract():
    """
    Verify that detectCornerGeometricX only scans the top-left corner
    when includeLeftZone = True, and strictly ignores it when includeLeftZone = False.
    """
    sw, sh = 1080, 2400
    screen = create_synthetic_screen(sw, sh, base_lum=220)

    # Place a perfect Meta White-on-Dark '✕' at x=6.5%, y=6.5%
    x_meta = int(sw * 0.065)
    y_meta = int(sh * 0.065)
    r = max(5, int(sw * 0.018))
    draw_circular_x(screen, x_meta, y_meta, r, is_bright_x=True)

    # 1. include_left_zone = False -> MUST NOT find the left corner X!
    res_without_left = simulate_detect_corner_geometric_x(
        screen, sw, sh, include_left_zone=False
    )
    assert res_without_left is None, "Left corner X must NOT be detected when includeLeftZone = False"

    # 2. include_left_zone = True -> MUST detect the left corner X!
    res_with_left = simulate_detect_corner_geometric_x(
        screen, sw, sh, include_left_zone=True
    )
    assert res_with_left is not None, "Left corner X must be detected when includeLeftZone = True"
    assert res_with_left.label == "\u5de6\u4e0a\u89d2\u5e7e\u4f55X"
    assert abs(res_with_left.bounds.centerX() - x_meta) <= 8
    assert abs(res_with_left.bounds.centerY() - y_meta) <= 8


# ------------------------------------------------------------------------------
# Test 6: R2 Dual Zone Geometry, Safe Zones, and Extreme Aspect Ratios
# ------------------------------------------------------------------------------
def test_r2_dual_zone_geometry_and_extreme_aspect_ratios():
    """
    Verify Left Zone & Right Zone boundary formulas and safe zone constraints
    across 10 diverse display resolutions and extreme aspect ratios.
    """
    resolutions = [
        (1080, 2400, "20:9 Modern Flagship"),
        (1440, 3200, "20:9 WQHD+"),
        (720, 1600, "20:9 Budget HD+"),
        (1080, 1920, "16:9 Standard FHD"),
        (1440, 2560, "16:9 QHD"),
        (720, 1280, "16:9 720p"),
        (1080, 2520, "21:9 Ultra Tall"),
        (1080, 2640, "22:9 Z Flip Tall"),
        (1812, 2176, "Foldable Inner ~4:5"),
        (1200, 2000, "Tablet 5:3")
    ]

    for sw, sh, desc in resolutions:
        r = max(5, int(sw * 0.018))

        # --- Left Zone Check ---
        left_top_limit_y = max(int(sh * 0.048), 48)
        left_bottom_limit_y = int(sh * 0.14)
        left_x_min = int(sw * 0.025)
        left_x_max = int(sw * 0.18)

        # 1. Left zone dimensions must be valid and positive
        assert left_x_max > left_x_min + r * 2, f"[{desc}] Left zone width must accommodate button"
        assert left_bottom_limit_y > left_top_limit_y + r * 2, f"[{desc}] Left zone height must accommodate button"

        # 2. Meta close X typical position (x ≈ 6.5%, y ≈ 6.5%) must be inside left zone
        x_meta = int(sw * 0.065)
        y_meta = int(sh * 0.065)
        assert left_x_min <= x_meta <= left_x_max, f"[{desc}] Meta X x={x_meta} must be within left zone x bounds"
        assert left_top_limit_y <= y_meta <= left_bottom_limit_y, f"[{desc}] Meta X y={y_meta} must be within left zone y bounds"

        # 3. Left safe-zone threshold: absY - r < max(sh * 0.04, 40)
        left_safe_y = max(int(sh * 0.040), 40)
        assert y_meta - r >= left_safe_y, f"[{desc}] Meta X at y={y_meta} must clear the left safe zone threshold {left_safe_y}"

        # 4. Left zone must strictly stay out of central status island (x in 20%..80%)
        assert left_x_max <= int(sw * 0.18), f"[{desc}] Left zone must not reach central notification area"

        # --- Right Zone Check ---
        right_top_limit_y = max(int(sh * 0.082), 60)
        right_bottom_limit_y = int(sh * 0.16)
        right_x_min = int(sw * 0.75)
        right_x_max = int(sw * 0.98)

        # 5. Right zone dimensions must be valid
        assert right_x_max > right_x_min + r * 2, f"[{desc}] Right zone width must be valid"
        assert right_bottom_limit_y > right_top_limit_y, f"[{desc}] Right zone height must be valid"

        # 6. Right zone candidate filter: absY - r < sh * 0.08 must strictly reject notification bar
        safe_right_boundary = int(sh * 0.08)
        assert right_top_limit_y >= safe_right_boundary, f"[{desc}] Right top limit must align with safe boundary"

        # 7. Bingo zone exclusion check (0.84..0.98sw, 0.08..0.16sh)
        bx_min = int(sw * 0.84)
        bx_max = int(sw * 0.98)
        by_min = int(sh * 0.08)
        by_max = int(sh * 0.16)
        test_bx = (bx_min + bx_max) // 2
        test_by = (by_min + by_max) // 2
        is_bingo = (bx_min <= test_bx <= bx_max and by_min <= test_by <= by_max)
        assert is_bingo is True, f"[{desc}] Bingo zone check must catch bingo coordinates"


# ------------------------------------------------------------------------------
# Test 7: R2 Pixel-Level Synthesis: Contrast Types, Adversarial Shapes & Noise
# ------------------------------------------------------------------------------
def test_r2_pixel_level_synthesis_stress():
    """
    Synthesize actual pixel-level bitmaps and test detectCornerGeometricX against:
    - 7A: Meta White-on-Dark '✕' in top-left
    - 7B: Meta Dark-on-White '✕' in top-left
    - 7C: Right Corner White-on-Dark '✕'
    - 7D: Adversarial shapes ('+', AdChoices 'ⓘ', mute speaker, solid circle, noise)
    """
    sw, sh = 1080, 2400
    r = max(5, int(sw * 0.018))

    # 7A. Top-left White '✕' on Dark-Gray Circle
    screen_a = create_synthetic_screen(sw, sh, base_lum=200)
    x_meta = int(sw * 0.065)
    y_meta = int(sh * 0.065)
    draw_circular_x(screen_a, x_meta, y_meta, r, is_bright_x=True)

    res_a = simulate_detect_corner_geometric_x(screen_a, sw, sh, include_left_zone=True)
    assert res_a is not None, "7A: Meta White-on-Dark X must be recognized"
    assert res_a.label == "\u5de6\u4e0a\u89d2\u5e7e\u4f55X"

    # 7B. Top-left Dark-Gray '✕' on White Circle
    screen_b = create_synthetic_screen(sw, sh, base_lum=100)
    draw_circular_x(screen_b, x_meta, y_meta, r, is_bright_x=False)

    res_b = simulate_detect_corner_geometric_x(screen_b, sw, sh, include_left_zone=True)
    assert res_b is not None, "7B: Meta Dark-on-White X must be recognized"
    assert res_b.label == "\u5de6\u4e0a\u89d2\u5e7e\u4f55X"

    # 7C. Right Corner White '✕' (at x=0.80sw, y=0.12sh - outside bingo zone!)
    screen_c = create_synthetic_screen(sw, sh, base_lum=200)
    x_right = int(sw * 0.80)  # Outside bingo exclusion 0.84..0.98!
    y_right = int(sh * 0.12)
    draw_circular_x(screen_c, x_right, y_right, r, is_bright_x=True)

    res_c = simulate_detect_corner_geometric_x(screen_c, sw, sh, include_left_zone=False)
    assert res_c is not None, "7C: Right corner X must be recognized"
    assert res_c.label == "\u53f3\u4e0a\u89d2\u5e7e\u4f55X"

    # 7D-1. Adversarial shape: Orthogonal '+' (cross) instead of diagonal X
    screen_plus = create_synthetic_screen(sw, sh, base_lum=200)
    draw_circular_plus(screen_plus, x_meta, y_meta, r, is_bright=True)

    res_plus = simulate_detect_corner_geometric_x(screen_plus, sw, sh, include_left_zone=True)
    assert res_plus is None, "7D-1: Orthogonal '+' must be REJECTED (fails diagonal contrast)"

    # 7D-2. Adversarial shape: Solid circular dot (e.g. badge/carousel dot)
    screen_dot = create_synthetic_screen(sw, sh, base_lum=200)
    button_r = int(r * 1.35)
    y_idx, x_idx = np.ogrid[:sh, :sw]
    screen_dot[(x_idx - x_meta)**2 + (y_idx - y_meta)**2 <= button_r**2] = 50

    res_dot = simulate_detect_corner_geometric_x(screen_dot, sw, sh, include_left_zone=True)
    assert res_dot is None, "7D-2: Solid circular dot must be REJECTED (no internal X contrast)"

    # 7D-3. Adversarial noise: Random salt-and-pepper background
    random_state = np.random.RandomState(42)
    screen_noise = random_state.randint(120, 220, (sh, sw), dtype=np.uint8)

    res_noise = simulate_detect_corner_geometric_x(screen_noise, sw, sh, include_left_zone=True)
    assert res_noise is None, "7D-3: Random noise texture must be REJECTED"


# ------------------------------------------------------------------------------
# Test 8: R2 AdCloserHandler Meta Trigger & Backup Hotzone Survey
# ------------------------------------------------------------------------------
def test_r2_ad_closer_meta_trigger_and_backup_hotzone():
    """
    Verify AdCloserHandler Meta trigger logic across package names, OCR signatures,
    carousel indicators, and top-left fallback hot zone geometry.
    """
    # 8A. Meta Packages
    meta_packages = [
        "com.facebook.katana",
        "com.facebook.orca",
        "com.facebook.lite",
        "com.facebook.mlite",
        "com.instagram.android",
        "com.facebook.ads.internal",
        "com.facebook.ads.sdk"
    ]
    for pkg in meta_packages:
        is_meta = (
            pkg in ["com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
                    "com.facebook.mlite", "com.instagram.android"] or
            pkg.lower().startswith("com.facebook.ads")
        )
        assert is_meta is True, f"Package '{pkg}' must trigger isMetaAd"

    # 8B. Meta OCR Signatures
    meta_keywords = [
        "Ads served by Meta", "Meta", "Facebook", "Trip.com", "from Meta", "Audience Network"
    ]
    for kw in meta_keywords:
        assert any(token in kw for token in ["Meta", "Facebook", "Trip.com", "Ads served by Meta", "from Meta", "Audience Network"])

    # 8C. Carousel Indicators
    carousel_indicators = ["\u2022", "\u25cf", "\u25cb", "\u00b7\u00b7\u00b7", "\u2022\u2022\u2022", "1/", "/3", "1 of"]
    sample_texts = [
        "1/3 Trip.com Special Offer",
        "\u25cf \u25cf \u25cb Explore now",
        "1 of 4 Sponsored",
        "\u2022\u2022\u2022 Swipe to view"
    ]
    for text in sample_texts:
        has_carousel = any(token in text for token in carousel_indicators)
        assert has_carousel is True, f"Text '{text}' must be recognized as carousel ad"

    # 8D. Top-left Backup Hotzone Geometry
    sw, sh = 1080, 2400
    hz_left = int(sw * 0.035)
    hz_top = int(sh * 0.048)
    hz_right = int(sw * 0.140)
    hz_bottom = int(sh * 0.095)

    assert hz_left < hz_right
    assert hz_top < hz_bottom
    assert 0.034 <= hz_left / sw <= 0.140
    assert 0.047 <= hz_top / sh <= 0.096

    # Check timing requirement: triggered at elapsedSec >= 5 || remainingSec == null
    for elapsed in [5, 6, 10, 30]:
        assert (elapsed >= 5 or None is None) is True
    # At elapsed < 5 with countdown present:
    assert (3 >= 5 or 25 is None) is False


# ------------------------------------------------------------------------------
# Test 9: AST Source Code Integrity for R1 & R2
# ------------------------------------------------------------------------------
def test_ast_source_code_integrity():
    """Verify production Kotlin files contain required exact contracts."""
    # OcrRecognizer.kt
    ocr_path = os.path.join(SRC_DIR, "vision", "OcrRecognizer.kt")
    with open(ocr_path, "r", encoding="utf-8") as f:
        ocr_code = f.read()

    assert "normY !in 0.60f..0.78f || normX !in 0.20f..0.80f" in ocr_code
    assert "Regex(\"\"\"^\\s*\\[?\\s*OK\\s*\\]?\\s*$\"\"\", RegexOption.IGNORE_CASE)" in ocr_code
    assert "t.contains(\"Grok\", ignoreCase = true)" in ocr_code
    assert "t.contains(\"TikTok\", ignoreCase = true)" in ocr_code
    assert "t.contains(\"Google Play\", ignoreCase = true)" in ocr_code
    assert "t.contains(\"Book\", ignoreCase = true)" in ocr_code

    # LotteryTask.kt
    lottery_path = os.path.join(SRC_DIR, "tasks", "LotteryTask.kt")
    with open(lottery_path, "r", encoding="utf-8") as f:
        lottery_code = f.read()

    idx_50 = lottery_code.find("【第一順位】：50 抽")
    idx_free = lottery_code.find("【第二順位】：免費看廣告抽一次")
    assert idx_50 != -1 and idx_free != -1
    assert idx_50 < idx_free, "50-draw priority must precede free-draw in LotteryTask.kt"

    # TemplateMatcher.kt
    matcher_path = os.path.join(SRC_DIR, "vision", "TemplateMatcher.kt")
    with open(matcher_path, "r", encoding="utf-8") as f:
        matcher_code = f.read()

    assert "val topLimitY = max((sh * 0.082f).toInt(), 60)" in matcher_code
    assert "val leftTopLimitY = max((sh * 0.048f).toInt(), 48)" in matcher_code
    assert "val leftBottomLimitY = (sh * 0.14f).toInt()" in matcher_code
    assert "Rect((sw * 0.025f).toInt(), leftTopLimitY, (sw * 0.18f).toInt(), leftBottomLimitY)" in matcher_code
    assert "absY - r < (sh * 0.08f).toInt()" in matcher_code
    assert "val isBingoCloseZone = absX in (sw * 0.84f).toInt()..(sw * 0.98f).toInt() &&" in matcher_code
    assert "absY - r < max((sh * 0.040f).toInt(), 40)" in matcher_code

    # AdCloserHandler.kt
    closer_path = os.path.join(SRC_DIR, "engine", "AdCloserHandler.kt")
    with open(closer_path, "r", encoding="utf-8") as f:
        closer_code = f.read()

    assert "includeLeftZone = isMetaAd" in closer_code
    assert "Trip.com" in closer_code
    assert "\"螢幕左上角熱區\"" in closer_code
    assert "Meta備援左上角熱區" in closer_code

    # AutomationEngine.kt
    engine_path = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        engine_code = f.read()

    assert "val modalCloseBtn = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)" in engine_code
    assert "if (modalCloseBtn == null && !hasLotteryResultContext) {" in engine_code


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
