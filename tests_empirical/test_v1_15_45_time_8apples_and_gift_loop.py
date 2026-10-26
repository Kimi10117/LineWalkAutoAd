import os
from pathlib import Path
from PIL import Image
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
UPLOAD_DIR = Path(r"C:\Users\kimic\.gemini\antigravity\brain\e232dbfa-92dc-4e8d-aba9-96ef68275342\.user_uploaded")


def is_popup_button_disabled_gray(img: Image.Image, center_y_ratio: float) -> bool:
    w, h = img.size
    center_y = int(h * center_y_ratio)
    safe_cy = max(0, min(h - 1, center_y))

    lr, lg, lb = img.getpixel((int(w * 0.05), safe_cy))[:3]
    rr, rg, rb = img.getpixel((int(w * 0.95), safe_cy))[:3]
    if lr < 220 or lg < 220 or lb < 220 or rr < 220 or rg < 220 or rb < 220:
        return False

    y_start = max(0, min(h - 1, center_y - int(h * 0.018)))
    y_end = max(y_start + 1, min(h - 1, center_y + int(h * 0.018)))
    x_start = int(w * 0.15)
    x_end = int(w * 0.85)

    gray_pixels = 0
    total_sampled = 0
    for y in range(y_start, y_end + 1, 3):
        for x in range(x_start, x_end + 1, 6):
            r, g, b = img.getpixel((x, y))[:3]
            max_diff = max(abs(r - g), abs(g - b), abs(r - b))
            if 60 <= r <= 235 and max_diff <= 15:
                gray_pixels += 1
            total_sampled += 1
    return total_sampled > 0 and (gray_pixels / total_sampled) >= 0.40


def is_ad_loading_screen_visual(img: Image.Image, has_bottom_cancel_ocr: bool) -> bool:
    if not has_bottom_cancel_ocr:
        return False
    w, h = img.size
    cy = int(h * 0.905)
    r_l, g_l, b_l = img.getpixel((int(w * 0.06), cy))[:3]
    r_r, g_r, b_r = img.getpixel((int(w * 0.94), cy))[:3]
    r_m, g_m, b_m = img.getpixel((int(w * 0.08), int(h * 0.68)))[:3]
    r_p1, g_p1, b_p1 = img.getpixel((int(w * 0.35), cy))[:3]
    r_p2, g_p2, b_p2 = img.getpixel((int(w * 0.65), cy))[:3]

    margins_dimmed = (
        r_l < 190 and g_l < 190 and b_l < 190
        and r_r < 190 and g_r < 190 and b_r < 190
        and r_m < 190 and g_m < 190 and b_m < 190
    )
    pill_bright_white = (
        (r_p1 > 225 and g_p1 > 225 and b_p1 > 225)
        or (r_p2 > 225 and g_p2 > 225 and b_p2 > 225)
    )
    return margins_dimmed and pill_bright_white


def find_header_gift_box_center(img: Image.Image):
    w, h = img.size
    x_start = int(w * 0.868)
    x_end = int(w * 0.902)
    y_start = int(h * 0.048)
    y_end = int(h * 0.062)

    sum_x = 0
    sum_y = 0
    red_count = 0
    for y in range(y_start, y_end + 1, 2):
        for x in range(x_start, x_end + 1, 2):
            r, g, b = img.getpixel((x, y))[:3]
            if r > 210 and g < 100 and b < 100 and (r - g) > 120:
                sum_x += x
                sum_y += y
                red_count += 1
    if red_count >= 5:
        dot_x = sum_x // red_count
        dot_y = sum_y // red_count
        box_cx = dot_x - int(w * 0.022)
        box_cy = dot_y + int(h * 0.014)
        return (box_cx / w, box_cy / h)
    return (0.855, 0.0675)


def test_issue1_ad_loading_vs_popup_gray_empirical():
    ad_loading_img = Image.open(UPLOAD_DIR / "media_1792514537196_4d894677.jpg")
    time_active_popup = Image.open(UPLOAD_DIR / "media_1792385148347_8e5f49a7.jpg")
    time_cooldown_both_gray = Image.open(UPLOAD_DIR / "media_1792398806512_2f2ce8b7.jpg")
    move_active_pink_popup = Image.open(UPLOAD_DIR / "media_1792488300229_eb19850a.jpg")

    # 1. Dimmed Ad Loading screen must NEVER be classified as disabled gray popup button
    assert not is_popup_button_disabled_gray(ad_loading_img, 0.75)
    assert not is_popup_button_disabled_gray(ad_loading_img, 0.85)
    assert is_ad_loading_screen_visual(ad_loading_img, has_bottom_cancel_ocr=True)

    # 2. Time tab active popup (media_1792385148347_8e5f49a7.jpg): top candy button is gray (0.75), bottom claim is active white (0.85)
    assert is_popup_button_disabled_gray(time_active_popup, 0.75)
    assert not is_popup_button_disabled_gray(time_active_popup, 0.85)
    assert not (is_popup_button_disabled_gray(time_active_popup, 0.75) and is_popup_button_disabled_gray(time_active_popup, 0.85))
    assert not is_ad_loading_screen_visual(time_active_popup, has_bottom_cancel_ocr=True)

    # 3. Time tab genuine cooldown popup with both buttons gray (media_1792398806512_2f2ce8b7.jpg)
    assert is_popup_button_disabled_gray(time_cooldown_both_gray, 0.75)
    assert is_popup_button_disabled_gray(time_cooldown_both_gray, 0.85)
    assert not is_ad_loading_screen_visual(time_cooldown_both_gray, has_bottom_cancel_ocr=True)

    # 4. Move tab active coral pink popup (media_1792488300229_eb19850a.jpg)
    assert not is_popup_button_disabled_gray(move_active_pink_popup, 0.75)
    assert not is_popup_button_disabled_gray(move_active_pink_popup, 0.85)
    assert not is_ad_loading_screen_visual(move_active_pink_popup, has_bottom_cancel_ocr=True)


def test_issue2_gift_box_never_clicks_megaphone_and_ignores_dark_green_stamps():
    friends_done_img1 = Image.open(UPLOAD_DIR / "media_1792989290247_273fdd8d.jpg")
    friends_done_img2 = Image.open(UPLOAD_DIR / "media_1792949377446_0c5eeb74.jpg")

    # 1. On media_1792989290247_273fdd8d.jpg, megaphone has a red dot at x=0.800, gift box has none.
    # find_header_gift_box_center must target x=0.855 (Gift Box), NEVER x < 0.82 (Megaphone).
    gx, gy = find_header_gift_box_center(friends_done_img1)
    assert 0.84 <= gx <= 0.88, f"Expected gift box x in 0.84..0.88, got {gx}"
    assert 0.055 <= gy <= 0.080

    # 2. Verify dark green "已贈送" stamps on both real screenshots are rejected by bright green filter
    for img in (friends_done_img1, friends_done_img2):
        w, h = img.size
        bright_green_count_done = 0
        for y in range(int(h * 0.35), int(h * 0.85), 2):
            for x in range(int(w * 0.76), int(w * 0.95), 2):
                r, g, b = img.getpixel((x, y))[:3]
                if g > 150 and (g - r) > 40 and (g - b) > 35:
                    bright_green_count_done += 1
        assert bright_green_count_done < 15

    # 3. Verify an active LINE bright green (#06C755) gift button is detected
    active_sim = friends_done_img1.copy()
    w2, h2 = active_sim.size
    for y in range(int(h2 * 0.43), int(h2 * 0.48)):
        for x in range(int(w2 * 0.81), int(w2 * 0.90)):
            active_sim.putpixel((x, y), (6, 199, 85))
    bright_green_count_active = 0
    for y in range(int(h2 * 0.35), int(h2 * 0.85), 2):
        for x in range(int(w2 * 0.76), int(w2 * 0.95), 2):
            r, g, b = active_sim.getpixel((x, y))[:3]
            if g > 150 and (g - r) > 40 and (g - b) > 35:
                bright_green_count_active += 1
    assert bright_green_count_active >= 100


def test_kotlin_source_invariants_v1_15_45():
    tm_code = (REPO_ROOT / "app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt").read_text(encoding="utf-8")
    ocr_code = (REPO_ROOT / "app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    ad_code = (REPO_ROOT / "app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    fg_code = (REPO_ROOT / "app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt").read_text(encoding="utf-8")
    rg_code = (REPO_ROOT / "app/src/main/java/com/linewalk/autoad/tasks/ReceiveGiftTask.kt").read_text(encoding="utf-8")
    ae_code = (REPO_ROOT / "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt").read_text(encoding="utf-8")
    gradle_code = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")

    assert "leftMarginColor" in tm_code
    assert "upperGray && lowerGray" in ocr_code
    assert "isAdLoadingScreen(ocrItems, screenBitmap)" in ad_code
    assert 'AdStepResult.ReturnedToGame(null, "廣告未成功啟動")' in ad_code
    assert "xStart = (sw * 0.868f).toInt()" in rg_code
    assert "!hasUnderlyingMiddleContent" in rg_code
    assert "hasLeaderboardContent" in fg_code
    assert "handleRoundCompletion(service)" in ae_code
    assert any(v in gradle_code for v in ['versionName = "1.15.45"', 'versionName = "1.15.46"', 'versionName = "1.15.47"', 'versionName = "1.15.48"', 'versionName = "1.15.49"'])
    assert any(c in gradle_code for c in ["versionCode = 78", "versionCode = 79", "versionCode = 80", "versionCode = 81", "versionCode = 82"])
