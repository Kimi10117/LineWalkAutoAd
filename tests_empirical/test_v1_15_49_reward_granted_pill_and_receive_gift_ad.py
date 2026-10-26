import os
from pathlib import Path
from PIL import Image
import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"
USER_UPLOADED_IMG = Path(r"C:\Users\kimic\.gemini\antigravity\brain\e232dbfa-92dc-4e8d-aba9-96ef68275342\.user_uploaded\media_1793025557322_e4d976d6.jpg")


def test_reward_granted_pill_geometric_x_detected_on_user_screenshot():
    """Verify that detectCornerGeometricX detects the top-right X in media_1793025557322_e4d976d6.jpg."""
    assert USER_UPLOADED_IMG.exists(), f"Screenshot not found at {USER_UPLOADED_IMG}"

    img = Image.open(USER_UPLOADED_IMG)
    sw, sh = img.size
    assert sw == 418 and sh == 1024

    gray = np.array(img.convert('L'))

    r = max(5, int(sw * 0.018))  # 7
    r_half = max(3, int(r * 0.65))  # 4
    step = 2
    tol = 1

    top_limit_y = max(int(sh * 0.082), 60)
    search_zones = [
        (int(sw * 0.75), top_limit_y, int(sw * 0.98), int(sh * 0.16)),
        (int(sw * 0.75), max(int(sh * 0.048), 48), int(sw * 0.98), top_limit_y),
    ]

    best_candidate = None
    best_raw_score = 65.0
    center_contrast_min = 35.0
    contrast_min = 45.0
    min_arm_contrast_min = 22.0

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

                if zone_idx == 0:
                    if abs_y - r < int(sh * 0.08):
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
                    center_pixel_lum = lum(cx, cy)
                    center_contrast = center_pixel_lum - card_mean if is_bright_x else card_mean - center_pixel_lum
                    if center_contrast < center_contrast_min:
                        cx += step
                        continue

                    v_top = lum(cx, cy - r_half)
                    v_bot = lum(cx, cy + r_half)
                    v_left = lum(cx - r_half, cy)
                    v_right = lum(cx + r_half, cy)
                    max_v_gap_diff = max(max(abs(v_top - card_mean), abs(v_bot - card_mean)),
                                         max(abs(v_left - card_mean), abs(v_right - card_mean)))
                    if max_v_gap_diff > 35.0:
                        cx += step
                        continue

                    c_lum = arm_lum(cx, cy, is_bright_x)
                    d1 = arm_lum(cx - r_half, cy - r_half, is_bright_x)
                    d2 = arm_lum(cx + r_half, cy + r_half, is_bright_x)
                    d3 = arm_lum(cx + r_half, cy - r_half, is_bright_x)
                    d4 = arm_lum(cx - r_half, cy + r_half, is_bright_x)

                    diag_mean = (c_lum + d1 + d2 + d3 + d4) * 0.2
                    contrast = diag_mean - card_mean if is_bright_x else card_mean - diag_mean
                    min_arm_contrast = (min(c_lum, min(min(d1, d2), min(d3, d4))) - card_mean if is_bright_x
                                        else card_mean - max(c_lum, max(max(d1, d2), max(d3, d4))))

                    if contrast > contrast_min and min_arm_contrast > min_arm_contrast_min:
                        raw_score = contrast - (card_diff * 0.5)
                        if raw_score > best_raw_score:
                            best_raw_score = raw_score
                            best_candidate = (abs_x, abs_y, raw_score)
                cx += step
            cy += step

    assert best_candidate is not None, "Geometric X was not detected on user screenshot"
    detected_x, detected_y, score = best_candidate
    assert abs(detected_x - 391) <= 3, f"Expected X near 391, got {detected_x}"
    assert abs(detected_y - 65) <= 3, f"Expected Y near 65, got {detected_y}"
    assert score > 150.0, f"Expected score > 150, got {score}"


def test_reward_granted_pill_closer_logic():
    """Verify AdCloserHandler has instant Reward granted pill X closer logic."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "isTopRightPill" in closer_code
    assert 'rewardGrantedOcr.text.contains("Reward", ignoreCase = true)' in closer_code
    assert "val pillCloseRect = Rect(closeX - 25, closeY - 25, closeX + 25, closeY + 25)" in closer_code
    assert 'ClickedCloseOrSkip("Reward granted [✕]", pillCloseRect)' in closer_code


def test_external_app_ticks_isolation():
    """Verify that externalAppTicks is completely isolated from nonGameScreenTicks."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    assert "private var externalAppTicks = 0" in ae_code
    assert "externalAppTicks++" in ae_code
    assert "externalAppTicks >= 3" in ae_code
    assert "externalAppTicks = 0" in ae_code
    # nonGameScreenTicks should NOT be reset to 0 in onEngineTick when regularClicksAllowed is true
    assert "} else {\n            externalAppTicks = 0\n        }" in ae_code


def test_receive_gift_direct_claim_triggers_ad_popup():
    """Verify that clicking direct gift claim transitions to TRIGGER_AD_POPUP."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    assert 'transitionTo(EngineState.TRIGGER_AD_POPUP, "已點擊【$label】，等待獎勵確認或廣告...", "[禮物] 領取禮物中...")' in ae_code


def test_in_game_scan_ad_keywords_and_relaxed_blacklist():
    """Verify that handleInGameScan detects Reward granted, youtu.be, etc., and uses relaxed corner blacklist."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    assert 't.contains("youtu.be", ignoreCase = true)' in ae_code
    assert 't.contains("youtube.com", ignoreCase = true)' in ae_code
    assert 't.contains("立即观看")' in ae_code
    assert 'OcrRecognizer.AD_REWARD_GRANTED_KEYWORDS.any' in ae_code
    assert '(x in (sw * 0.18f).toInt()..(sw * 0.82f).toInt() && y < sh * 0.08f)' in ae_code
    assert 'y < max((sh * 0.040f).toInt(), 40)' in ae_code


def test_version_bump_v1_15_49():
    """Verify version 1.15.49/1.15.50 and versionCode 82/83."""
    gradle_code = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    workflow_code = (REPO_ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

    assert any(c in gradle_code for c in ['versionCode = 82', 'versionCode = 83'])
    assert any(v in gradle_code for v in ['versionName = "1.15.49"', 'versionName = "1.15.50"'])
    assert any(w in workflow_code for w in ['v1.15.49', 'v1.15.50'])
