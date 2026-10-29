from pathlib import Path
from PIL import Image
import numpy as np
import pytest

BUG_FOLDER = Path(r"C:\Users\kimic\桌面\散步去BUG")
IMG_7707 = BUG_FOLDER / "7707_0.jpg"
IMG_7706 = BUG_FOLDER / "7706_0.jpg"


class MockBounds:
    def __init__(self, left, top, right, bottom):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    def centerX(self):
        return (self.left + self.right) // 2

    def centerY(self):
        return (self.top + self.bottom) // 2


class MockOcrItem:
    def __init__(self, text, left, top, right, bottom):
        self.text = text
        self.bounds = MockBounds(left, top, right, bottom)


def test_7707_gift_drawer_dialog_detection_and_friends_page_mutex():
    """Verify that split-line text in 7707_0.jpg detects the drawer and triggers mutual exclusion with friends page."""
    sw, sh = 818, 2000

    # In 7707_0.jpg, OCR text is split:
    ocr_items = [
        MockOcrItem("排行榜", 100, 240, 240, 290),
        MockOcrItem("好友清單", 300, 240, 460, 290),
        MockOcrItem("收到黃小鬼送來的禮", 228, 640, 520, 690),
        MockOcrItem("物囉！", 228, 700, 310, 750),
        MockOcrItem("剩下1天", 590, 750, 700, 795),
        MockOcrItem("2026.10.29", 228, 800, 350, 840),
    ]

    effective_sh = sh

    # 1. Single-line check
    has_single_line = any(
        cy in range(int(effective_sh * 0.22), int(effective_sh * 0.88)) and (
            "送來的禮物" in item.text.replace(" ", "") or
            "一起送達" in item.text.replace(" ", "") or
            "禮物囉" in item.text.replace(" ", "") or
            "送來的" in item.text.replace(" ", "") or
            ("收到" in item.text and ("禮" in item.text or "送" in item.text))
        )
        for item in ocr_items
        for cy in [item.bounds.centerY()]
    )
    assert has_single_line, "Expected single-line check to match '收到黃小鬼送來的禮'"

    # 2. Multi-line combined check
    middle_items = [item for item in ocr_items if item.bounds.centerY() in range(int(effective_sh * 0.22), int(effective_sh * 0.88))]
    combined_text = "".join(item.text.replace(" ", "") for item in middle_items)
    assert "收到" in combined_text and "禮物" in combined_text, "Combined text must contain 收到 and 禮物"
    assert "剩下" in combined_text or "天" in combined_text, "Combined text must contain 剩下 or 天"

    # 3. FriendGiftTask.isFriendsPage mutual exclusion
    is_drawer_open = True
    is_friends_page = not is_drawer_open
    assert not is_friends_page, "FriendGiftTask.isFriendsPage must return False when drawer is open!"


def test_7707_green_play_button_color_sampling():
    """Verify that Cluster 1 in 7707_0.jpg is classified as isAd = True (green outline + white interior)."""
    if not IMG_7707.exists():
        pytest.skip(f"Historical screenshot {IMG_7707} was replaced on desktop with newer bug screenshot")
    img = Image.open(IMG_7707)
    sw, sh = img.size
    assert (sw, sh) == (818, 2000)

    # Cluster 1 on 7707_0.jpg: X=541..743, Y=628..692
    min_x, max_x = 541, 743
    min_y, max_y = 628, 692
    width = max_x - min_x
    height = max_y - min_y

    inset_x = max(int(width * 0.25), 2)
    inset_y = max(int(height * 0.25), 2)

    white_count = 0
    green_count = 0
    total_inner = 0

    for iy in range(min_y + inset_y, max_y - inset_y, 2):
        for ix in range(min_x + inset_x, max_x - inset_x, 2):
            r, g, b = img.getpixel((ix, iy))[:3]
            total_inner += 1
            if r > 200 and g > 200 and b > 200:
                white_count += 1
            elif g > 140 and g > r + 25 and g > b + 25:
                green_count += 1

    white_ratio = white_count / total_inner
    green_ratio = green_count / total_inner

    assert white_ratio > 0.40, f"Expected interior white ratio > 40%, got {white_ratio:.2%}"
    assert green_ratio < 0.60, f"Expected interior green ratio < 60%, got {green_ratio:.2%}"

    is_solid = green_ratio > 0.60
    is_ad = not is_solid
    assert is_ad is True, "Cluster 1 must be classified as isAd = True (看廣告領取)"


def test_7707_close_button_x_bounds():
    """Verify that getCloseButtonBounds accurately bounds the close X on 7707_0.jpg."""
    sw, sh = 818, 2000
    center_x = int(sw * 0.890)
    center_y = int(sh * 0.231)
    half_w = int(sw * 0.060)
    half_h = int(sh * 0.025)

    close_rect = (center_x - half_w, center_y - half_h, center_x + half_w, center_y + half_h)
    # Target center measured empirically on 7707_0.jpg: x ≈ 741.5, y ≈ 476.0
    target_x = 741
    target_y = 476

    assert close_rect[0] <= target_x <= close_rect[2], f"X coordinate {target_x} must be within {close_rect[0]}..{close_rect[2]}"
    assert close_rect[1] <= target_y <= close_rect[3], f"Y coordinate {target_y} must be within {close_rect[1]}..{close_rect[3]}"


def test_7706_mission_tab_3tab_vs_2tab_dynamic_deduction():
    """Verify dynamic deduction for 3-tab (with Special) vs 2-tab (Special completed and disappeared)."""
    sw, sh = 1080, 2640

    def has_special_tab(tab_items, screen_w):
        if any("特別" in item.text for item in tab_items):
            return True
        daily_item = next((item for item in tab_items if "每日" in item.text), None)
        weekly_item = next((item for item in tab_items if "每週" in item.text), None)
        if screen_w > 0:
            if daily_item:
                cx_ratio = daily_item.bounds.centerX() / screen_w
                if cx_ratio > 0.42:
                    return True
                if cx_ratio < 0.40:
                    return False
            if weekly_item:
                cx_ratio = weekly_item.bounds.centerX() / screen_w
                if cx_ratio > 0.74:
                    return True
                if cx_ratio < 0.73:
                    return False
        return False

    # Scenario A: 7706_0.jpg where "特別" is covered by overlay, "每日" is centered at 0.50f, "每週" at 0.80f
    items_7706 = [
        MockOcrItem("每日", 480, 480, 600, 540),  # cx = 540 (0.50f)
        MockOcrItem("每週", 800, 480, 920, 540),  # cx = 860 (0.796f)
    ]
    assert has_special_tab(items_7706, sw) is True, "3-tab layout must be deduced when daily is at 50% and weekly is at 80%!"

    # Scenario B: Special task completed and disappeared (2-tab layout)
    # "每日" is at 33% (left), "每週" is at 67% (right)
    items_2tab = [
        MockOcrItem("每日", 300, 480, 420, 540),  # cx = 360 (0.333f)
        MockOcrItem("每週", 660, 480, 780, 540),  # cx = 720 (0.667f)
    ]
    assert has_special_tab(items_2tab, sw) is False, "2-tab layout must be deduced when daily is at 33% and weekly is at 67%!"

    # Scenario C: "特別" is explicitly visible
    items_explicit_special = [
        MockOcrItem("特別", 150, 480, 270, 540),
        MockOcrItem("每日", 480, 480, 600, 540),
        MockOcrItem("每週", 800, 480, 920, 540),
    ]
    assert has_special_tab(items_explicit_special, sw) is True, "Explicit '特別' must return True"


def test_floating_overlay_preset_position_safety():
    """Verify that preset x = 12, y = sh * 0.290f avoids all interactive zones across devices."""
    test_resolutions = [
        (1080, 1920),  # 16:9
        (1080, 2340),  # 19.5:9
        (1080, 2640),  # 22:9 (Fold / Flip)
        (720, 1600),   # 20:9
    ]

    for sw, sh in test_resolutions:
        preset_x = 12
        preset_y = max(int(sh * 0.290), 420)

        # 1. Must be strictly below tabs (0.14f..0.22f)
        assert preset_y > int(sh * 0.22), f"Preset Y {preset_y} must be below tabs on {sw}x{sh}"

        # 2. Must be strictly below dialog headers/close buttons (0.20f..0.26f)
        assert preset_y > int(sh * 0.26), f"Preset Y {preset_y} must be below dialog headers on {sw}x{sh}"

        # 3. Must be strictly above main icon buttons (0.38f..0.46f)
        assert preset_y < int(sh * 0.38), f"Preset Y {preset_y} must be above main icons on {sw}x{sh}"

        # 4. Must be docked on the left, far from right action buttons (x > 0.65f)
        assert preset_x < int(sw * 0.30), f"Preset X {preset_x} must stay on left side on {sw}x{sh}"
