import pytest
from pathlib import Path
from PIL import Image

SRC_DIR = Path(__file__).resolve().parent.parent / "app/src/main/java/com/linewalk/autoad"
IMG_7706 = Path(r"C:\Users\kimic\桌面\散步去BUG\7706_0.jpg")

def test_v1_15_55_gacha_task_detects_real_device_button():
    """Verify that GachaTask finds the '獲得金幣' button on SM-S7210 screenshot (y ≈ 0.401)."""
    if not IMG_7706.exists():
        pytest.skip(f"Image {IMG_7706} not found")
    
    img = Image.open(IMG_7706)
    sw, sh = img.size
    # 7706_0.jpg is the SM-S7210 screenshot
    if (sw, sh) != (869, 1883):
        pytest.skip("Image is not SM-S7210 screenshot")

    # Probe for red/pink button within y in 0.32f..0.58f
    yStart = int(sh * 0.32)
    yEnd = int(sh * 0.58)
    xStart = int(sw * 0.20)
    xEnd = int(sw * 0.80)

    pixels = img.load()
    red_pts = []
    for y in range(yStart, yEnd, 3):
        for x in range(xStart, xEnd, 3):
            r, g, b = pixels[x, y][:3]
            if r > 195 and (r - g > 65) and (r - b > 65):
                red_pts.append((x, y))

    assert len(red_pts) >= 40, f"Expected red button pixels >= 40, got {len(red_pts)}"
    minX = min(p[0] for p in red_pts)
    maxX = max(p[0] for p in red_pts)
    minY = min(p[1] for p in red_pts)
    maxY = max(p[1] for p in red_pts)
    centerY = (minY + maxY) / 2
    centerYRatio = centerY / sh

    # Button center on SM-S7210 is approx 0.401
    assert 0.35 <= centerYRatio <= 0.45, f"Center Y ratio {centerYRatio} outside expected range"
    assert maxX - minX >= int(sw * 0.30), "Button width too small"


def test_v1_15_55_gacha_task_code_contract():
    """Verify GachaTask.kt search range expanded to 0.32f..0.58f and centerY >= 0.45f removed."""
    code = (SRC_DIR / "tasks/GameTask.kt").read_text(encoding="utf-8")
    assert "yStartRatio = 0.32f" in code
    assert "yEndRatio = 0.58f" in code
    assert "centerY() in (screenBitmap.height * 0.32f).toInt()..(screenBitmap.height * 0.58f).toInt()" in code
    assert "pinkGameBtn.bounds.centerY() >= screenBitmap.height * 0.45f" not in code
    assert "機台[獲得金幣]紅色按鈕" in code


def test_v1_15_55_tab_switch_y_anchor():
    """Verify getTabRect default Y anchor lowered to 0.125f to avoid clicking promo banner below."""
    code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "(sh * 0.125f).toInt()" in code
    assert "(probedY - (sh * 0.020f).toInt()).coerceIn((sh * 0.100f).toInt(), (sh * 0.155f).toInt())" in code


def test_v1_15_55_bingo_anti_early_exit():
    """Verify Bingo dialog will not close when rewards remain on screen, and empty ticks threshold is 4."""
    code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "val anyRemainingRewardOnScreen = bingoTask.findNextClaimableReward(screenBitmap, ocrItems, emptySet())" in code
    assert "if (anyRemainingRewardOnScreen != null)" in code
    assert "currentPhaseEmptyTicks < 4" in code


def test_v1_15_55_friend_gift_zero_fling_swipe():
    """Verify Friend Gift swipe uses 15% sh span (0.65f -> 0.50f) and 650ms to eliminate fling."""
    code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "startY = sh * 0.65f" in code
    assert "endY = sh * 0.50f" in code
    assert "durationMs = 650L" in code
    assert "friendGiftEmptyTicks <= 4" in code


def test_v1_15_55_lottery_reward_ad_precedence():
    """Verify Lottery dialog checks for [x1 獲得] before modal close button."""
    code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    reward_idx = code.find("findLotteryRewardAdButton")
    close_idx = code.find("findCenterRewardModalCloseButton")
    assert reward_idx != -1 and close_idx != -1
    # reward_idx in handleLotteryDialog must appear before modal close check
    handle_lottery_code = code[code.find("private suspend fun handleLotteryDialog") : code.find("private suspend fun handleMissionDialog")]
    assert handle_lottery_code.find("findLotteryRewardAdButton") < handle_lottery_code.find("findCenterRewardModalCloseButton")


def test_v1_15_55_friend_self_gift_precedence():
    """Verify findSelfGiftButton returns default geometric hotspot instead of falling back to normal gift."""
    code = (SRC_DIR / "tasks/FriendGiftTask.kt").read_text(encoding="utf-8")
    assert 'return Pair("也送給自己(預設熱區)", defaultSelfGiftRect)' in code
    assert 'return Pair("一般贈送", normalGiftOcr.bounds)' not in code
    assert "yStart = (sh * 0.40f).toInt()" in code
    assert "yEnd = (sh * 0.66f).toInt()" in code


def test_v1_15_55_ad_closer_anti_early_cut_and_skip_pill():
    """Verify isEndCard requires 32s, redirect back requires 38s, and skip pill is never nullified."""
    code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    assert "elapsedSec >= 32" in code
    assert "redirectCount > 0 && !isGame && !isMetaWebPreview && elapsedSec >= 38" in code
    assert "isExplicitSkipGooglePlayPill = false" in code
    assert "val hasMainScreenMarkers = topTabCount >= 2 || (hasBottomNav && topTabCount >= 1)" in code


def test_v1_15_55_version_bump():
    """Verify version bumped to v1.15.55 (versionCode 88)."""
    gradle_code = (Path(__file__).resolve().parent.parent / "app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'versionCode = 88' in gradle_code
    assert 'versionName = "1.15.55"' in gradle_code
