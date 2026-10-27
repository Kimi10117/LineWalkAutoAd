from pathlib import Path
from PIL import Image
import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"
USER_COUPANG_IMG = Path(r"C:\Users\kimic\.gemini\antigravity\brain\e232dbfa-92dc-4e8d-aba9-96ef68275342\.user_uploaded\media_1793034387843_62f4066f.jpg")


def test_coupang_screenshot_geometric_x_detected():
    """Verify that detectCornerGeometricX detects the close X on media_1793034387843_62f4066f.jpg."""
    assert USER_COUPANG_IMG.exists(), f"Screenshot not found at {USER_COUPANG_IMG}"

    img = Image.open(USER_COUPANG_IMG)
    sw, sh = img.size
    assert sw == 418 and sh == 1024

    gray = np.array(img.convert('L'))

    r = max(5, int(sw * 0.018))
    r_half = max(3, int(r * 0.65))
    step = 2
    tol = 1

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
                    if bright_x and l > best:
                        best = l
                    if not bright_x and l < best:
                        best = l
            return best

        cy = r + tol
        while cy < zh - r - tol:
            cx = r + tol
            while cx < zw - r - tol:
                abs_x = zx1 + cx
                abs_y = zy1 + cy

                l_top = lum(cx, cy - r)
                l_bot = lum(cx, cy + r)
                l_left = lum(cx - r, cy)
                l_right = lum(cx + r, cy)
                card_mean = (l_top + l_bot + l_left + l_right) * 0.25
                card_diff = abs(l_top - card_mean) + abs(l_bot - card_mean) + abs(l_left - card_mean) + abs(l_right - card_mean)

                if card_diff <= 58.0:
                    is_bright_x = card_mean < 150.0
                    center_pixel_lum = lum(cx, cy)
                    center_contrast = center_pixel_lum - card_mean if is_bright_x else card_mean - center_pixel_lum
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
                            contrast = diag_mean - card_mean if is_bright_x else card_mean - diag_mean
                            min_arm_contrast = (min(c_lum, min(min(d1, d2), min(d3, d4))) - card_mean if is_bright_x
                                                else card_mean - max(c_lum, max(max(d1, d2), max(d3, d4))))

                            if contrast > 45.0 and min_arm_contrast > 22.0:
                                raw_score = contrast - (card_diff * 0.5)
                                if raw_score > best_raw_score:
                                    best_raw_score = raw_score
                                    best_candidate = (abs_x, abs_y, raw_score)
                cx += step
            cy += step

    assert best_candidate is not None, "Geometric X was not detected on Coupang user screenshot"
    detected_x, detected_y, score = best_candidate
    assert abs(detected_x - 391) <= 3, f"Expected X near 391, got {detected_x}"
    assert abs(detected_y - 65) <= 3, f"Expected Y near 65, got {detected_y}"
    assert score > 150.0, f"Expected score > 150, got {score}"


def test_in_game_scan_ad_precedence_over_geox():
    """Verify that in AutomationEngine.handleInGameScan, ad detection precedes geometric X check."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    idx_ad_check = ae_code.find("val hasPlayableOrStoreAdOcr = !hasMainScreenMarkers && (")
    idx_geox_check = ae_code.find("val geoX = templateMatcher.detectCornerGeometricX(")

    assert idx_ad_check != -1, "hasPlayableOrStoreAdOcr must be present"
    assert idx_geox_check != -1, "detectCornerGeometricX must be present"
    assert idx_ad_check < idx_geox_check, "Playable/store ad detection MUST precede geometric X check in handleInGameScan"


def test_geox_retry_limit_protection():
    """Verify that geometric X clicks in handleInGameScan are capped by geoXConsecutiveClicks and fallback to safe back."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    assert "internal var geoXConsecutiveClicks = 0" in ae_code
    assert "geoXConsecutiveClicks++" in ae_code
    assert "geoXConsecutiveClicks >= 3" in ae_code
    assert "service.safeNavigateBack(allowInGame = true)" in ae_code
    assert "service.clickRect(geoX.bounds, allowTopSafeZone = true)" in ae_code


def test_ad_closer_reward_granted_in_game_safe_back():
    """Verify that AdCloserHandler dispatches safeNavigateBack(allowInGame = true) when reward is granted and in-game."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "rewardGrantedOcr != null && !isGameVisible" in closer_code
    assert 'AdStepResult.WatchdogBackTriggered(elapsedSec, "已獲獎勵內部返回鍵")' in closer_code
    assert "(!isGame || !isGameVisible) && now - lastBackActionTimeMs >= 3000L" in closer_code


def test_version_bump_v1_15_51():
    """Verify version 1.15.51/1.15.52 and versionCode 84/85."""
    gradle_code = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    workflow_code = (REPO_ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

    assert ('versionCode = 84' in gradle_code or 'versionCode = 85' in gradle_code)
    assert ('versionName = "1.15.51"' in gradle_code or 'versionName = "1.15.52"' in gradle_code)
    assert ('v1.15.51' in workflow_code or 'v1.15.52' in workflow_code)
