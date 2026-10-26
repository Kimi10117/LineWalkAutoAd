import os
from pathlib import Path
from PIL import Image
import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"
USER_UPLOADED_IMG = Path(r"C:\Users\kimic\.gemini\antigravity\brain\e232dbfa-92dc-4e8d-aba9-96ef68275342\.user_uploaded\media_1793011483432_7dd2e6a5.jpg")


def test_inmobi_upper_right_geometric_x_detected_on_user_screenshot():
    """Verify that detectCornerGeometricX detects the InMobi 'X' in media_1793011483432_7dd2e6a5.jpg."""
    assert USER_UPLOADED_IMG.exists(), f"Screenshot not found at {USER_UPLOADED_IMG}"

    img = Image.open(USER_UPLOADED_IMG)
    sw, sh = img.size
    gray = np.array(img.convert('L'))

    r = max(5, int(sw * 0.018))
    r_half = max(3, int(r * 0.65))
    step = max(2, sw // 240)
    tol = max(1, sw // 360)

    top_limit_y = max(int(sh * 0.082), 60)
    right_upper_top_limit_y = max(int(sh * 0.048), 48)

    search_zones = [
        (int(sw * 0.75), top_limit_y, int(sw * 0.98), int(sh * 0.16)),
        (int(sw * 0.75), right_upper_top_limit_y, int(sw * 0.98), top_limit_y),
    ]

    best_candidate = None
    best_raw_score = 65.0

    for zone_idx, (zx1, zy1, zx2, zy2) in enumerate(search_zones):
        zw = zx2 - zx1
        zh = zy2 - zy1
        if zw <= r * 2 + 4 or zh <= r * 2 + 4:
            continue
        pixels = gray[zy1:zy2, zx1:zx2]

        def lum(x, y):
            return float(pixels[y, x])

        def arm_lum(px, py, bright_x):
            best = 0.0 if bright_x else 255.0
            for dy in range(-tol, tol + 1):
                ny = max(0, min(zh - 1, py + dy))
                for dx in range(-tol, tol + 1):
                    nx = max(0, min(zw - 1, px + dx))
                    l = lum(nx, ny)
                    if bright_x and l > best: best = l
                    if not bright_x and l < best: best = l
            return best

        cy = r + tol
        while cy < zh - r - tol:
            cx = r + tol
            while cx < zw - r - tol:
                abs_x = zx1 + cx
                abs_y = zy1 + cy

                if zone_idx == 0:
                    if abs_y - r < int(sh * 0.08):
                        cx += step
                        continue
                    if int(sw * 0.84) <= abs_x <= int(sw * 0.98) and int(sh * 0.08) <= abs_y <= int(sh * 0.16):
                        cx += step
                        continue
                else:
                    if abs_y - r < max(int(sh * 0.040), 40):
                        cx += step
                        continue

                l_top = lum(cx, cy - r)
                l_bot = lum(cx, cy + r)
                l_left = lum(cx - r, cy)
                l_right = lum(cx + r, cy)
                card_mean = (l_top + l_bot + l_left + l_right) * 0.25
                card_diff = abs(l_top - card_mean) + abs(l_bot - card_mean) + abs(l_left - card_mean) + abs(l_right - card_mean)

                if card_diff <= 58.0:
                    is_bright_x = card_mean < 150.0
                    center_lum = lum(cx, cy)
                    center_contrast = (center_lum - card_mean) if is_bright_x else (card_mean - center_lum)
                    if center_contrast >= 35.0:
                        v_top = lum(cx, cy - r_half)
                        v_bot = lum(cx, cy + r_half)
                        v_left = lum(cx - r_half, cy)
                        v_right = lum(cx + r_half, cy)
                        max_v_gap_diff = max(max(abs(v_top - card_mean), abs(v_bot - card_mean)),
                                             max(abs(v_left - card_mean), abs(v_right - card_mean)))
                        if max_v_gap_diff <= 35.0:
                            c_lum = arm_lum(cx, cy, is_bright_x)
                            d1 = arm_lum(cx - r_half, cy - r_half, is_bright_x)
                            d2 = arm_lum(cx + r_half, cy + r_half, is_bright_x)
                            d3 = arm_lum(cx + r_half, cy - r_half, is_bright_x)
                            d4 = arm_lum(cx - r_half, cy + r_half, is_bright_x)
                            diag_mean = (c_lum + d1 + d2 + d3 + d4) * 0.2
                            contrast = (diag_mean - card_mean) if is_bright_x else (card_mean - diag_mean)
                            if is_bright_x:
                                min_arm = min(c_lum, min(min(d1, d2), min(d3, d4))) - card_mean
                            else:
                                min_arm = card_mean - max(c_lum, max(max(d1, d2), max(d3, d4)))
                            if contrast > 45.0 and min_arm > 22.0:
                                raw_score = contrast - (card_diff * 0.5)
                                if raw_score > best_raw_score:
                                    best_raw_score = raw_score
                                    label = '左上角幾何X' if zx1 < sw / 2 else '右上角幾何X'
                                    best_candidate = (label, abs_x, abs_y, raw_score)
                cx += step
            cy += step

    assert best_candidate is not None, "InMobi close 'X' must be detected"
    label, match_x, match_y, score = best_candidate
    assert label == "右上角幾何X"
    # Center of 'X' is at (383, 63)
    assert abs(match_x - 383) <= 4, f"X coord {match_x} must match actual center ~383"
    assert abs(match_y - 63) <= 4, f"Y coord {match_y} must match actual center ~63"
    assert score > 200.0, f"Score {score} must be high confidence"


def test_source_code_invariants_v1_15_47():
    """Verify source code contracts in TemplateMatcher.kt and AdCloserHandler.kt."""
    matcher_code = (SRC_DIR / "vision/TemplateMatcher.kt").read_text(encoding="utf-8")
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "rightUpperTopLimitY = max((sh * 0.048f).toInt(), 48)" in matcher_code
    assert "val topLimitY = max((sh * 0.082f).toInt(), 60)" in matcher_code
    assert "absY - r < (sh * 0.08f).toInt()" in matcher_code
    assert "val isBingoCloseZone = absX in (sw * 0.84f).toInt()..(sw * 0.98f).toInt() &&" in matcher_code

    assert "if (redirectCount > 0 && !isGame)" in closer_code
    assert "getNextFallbackTarget(sw, sh, ocrItems)" in closer_code
