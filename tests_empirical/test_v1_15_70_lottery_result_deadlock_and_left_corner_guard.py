"""
v1.15.70 回歸測試：
1. 掛機卡在抽抽樂「BIG 8獎 100 枚金幣中獎囉！」結果彈窗的無限循環修復
2. 左上角幾何 X 連續誤點跳轉瀏覽器封鎖
3. LINE 宣傳廣告關閉 (X) 連點上限
4. 廣告倒數純數字角落限制
5. 版本號、0 Emoji 與繁體中文審計
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/linewalk/autoad"
SCREENSHOT = Path(
    r"C:/Users/kimic/.gemini/antigravity/brain/e232dbfa-92dc-4e8d-aba9-96ef68275342/.user_uploaded/media_1794071130304_47fcddf8.jpg"
)


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def lottery_src() -> str:
    return (SRC / "tasks/LotteryTask.kt").read_text(encoding="utf-8")


def engine_src() -> str:
    return (SRC / "engine/AutomationEngine.kt").read_text(encoding="utf-8")


def closer_src() -> str:
    return (SRC / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")


def ocr_src() -> str:
    return (SRC / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# 1. 抽抽樂結果彈窗死鎖
# ---------------------------------------------------------------------------

def test_win_result_screen_accepts_bitmap_and_composite_features():
    src = lottery_src()
    assert "fun isLotteryWinResultScreen(\n        ocrItems: List<OcrItem>,\n        sh: Int,\n        screenBitmap: Bitmap? = null\n    ): Boolean" in src
    assert 'Regex("""([1-9]|10|特|頭)獎""")' in src
    assert "hasRankHeader && (hasRewardX1ButtonOcr || hasModalCloseButtonOcr)" in src
    assert "private fun hasSolidGreenCapsuleInResultZone(screenBitmap: Bitmap): Boolean" in src
    # 忽略結果彈窗下方露出之「BIG 獎項一覽」
    assert "item.bounds.centerY() < (sh * 0.85f).toInt()" in src


def test_both_draws_unavailable_guards_against_foreground_modal():
    src = lottery_src()
    body = src[src.index("fun areBothDrawsUnavailable("):src.index("fun findLotteryRewardAdButton(")]
    i_result = body.index("isLotteryWinResultScreen(ocrItems, sh, screenBitmap)")
    i_modal = body.index("hasForegroundModalButtons")
    i_panel = body.index("hasVisibleMainPanelFeature")
    i_gray = body.index("val leftGray")
    assert i_result < i_gray and i_modal < i_gray and i_panel < i_gray


def test_engine_revokes_premature_completion_before_background_close():
    src = engine_src()
    b2 = src[src.index("// B2. 檢查是否正在「抽抽樂」彈窗"):src.index("// B3. 檢查是否正在「任務」彈窗")]
    i_fg = b2.index("findCenterRewardModalCloseButton(screenBitmap, ocrItems)")
    i_revoke = b2.index("isLotteryCompleted && lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)")
    i_completed = b2.index("if (isLotteryCompleted) {")
    assert i_fg < i_revoke < i_completed
    assert "isLotteryCompleted = false" in b2[i_revoke:i_completed]
    # 第 6 次點【集點】後不再歸零，第 7、8 次改用安全返回鍵脫困
    six = b2[b2.index("if (lotteryDialogCloseAttempts == 6) {"):]
    six = six[:six.index("return")]
    assert "lotteryDialogCloseAttempts = 0" not in six
    assert "if (lotteryDialogCloseAttempts == 7)" in b2
    assert "if (lotteryDialogCloseAttempts >= 8)" in b2
    assert b2.count("service.safeNavigateBack(allowInGame = true)") >= 2


def test_handle_lottery_dialog_requires_three_empty_ticks():
    src = engine_src()
    assert "val hasLotteryResultContext = lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)" in src
    assert "if (currentPhaseEmptyTicks < 3)" in src
    assert "if (!bothGray && currentPhaseEmptyTicks < 2)" not in src


def _simulate_completion(ticks):
    """以 Python 模擬 handleLotteryDialog 結束判定：回傳在第幾個 Tick 標記完成 (None 代表未完成)。"""
    empty = 0
    for idx, has_result in enumerate(ticks, start=1):
        if has_result:
            empty = 0
            continue
        empty += 1
        if empty < 3:
            continue
        return idx
    return None


def test_simulated_animation_frames_do_not_complete_early():
    # 實機時序：00:57:47 動畫中 (無結果) -> 00:57:49 動畫中 -> 00:57:50 結果彈窗浮現
    assert _simulate_completion([False, False, True, True]) is None
    # 真正無可抽項目：連續 3 個空 Tick 後才完成
    assert _simulate_completion([False, False, False]) == 3


def test_trigger_popup_ad_ocr_respects_sub_dialog():
    src = engine_src()
    assert "((hasAdOcrDirect && !isStillInSubDialog) || (!isStillInSubDialog && (directGeoX != null" in src


@pytest.mark.skipif(not SCREENSHOT.exists(), reason="實機截圖不存在")
def test_real_screenshot_green_capsule_in_result_zone():
    from PIL import Image

    img = Image.open(SCREENSHOT).convert("RGB")
    sw, sh = img.size
    px = img.load()
    y_start, y_end = int(sh * 0.58), int(sh * 0.74)
    x_start, x_end = int(sw * 0.18), int(sw * 0.82)
    step_x, step_y = 4, 3
    min_green = int((x_end - x_start) / step_x * 0.35)
    solid = 0
    for y in range(y_start, y_end + 1, step_y):
        cnt = 0
        for x in range(x_start, x_end + 1, step_x):
            r, g, b = px[x, y]
            if g > 135 and g - r > 30 and g - b > 30:
                cnt += 1
        if cnt >= min_green:
            solid += 1
    assert solid >= 3, f"實機截圖 [x1 獲得] 綠色膠囊連續行數不足: {solid}"


# ---------------------------------------------------------------------------
# 2. 左上角幾何 X 防跳轉
# ---------------------------------------------------------------------------

def test_left_corner_disabled_after_redirect():
    src = closer_src()
    assert "private var disableLeftCornerForCurrentAd: Boolean = false" in src
    assert "private var leftCornerGeoXClicks: Int = 0" in src
    reset = src[src.index("fun onEnterAdMode("):src.index("fun resetClickBlacklist()")]
    assert "disableLeftCornerForCurrentAd = false" in reset
    assert "leftCornerGeoXClicks = 0" in reset
    assert "linePromoCloseClicks = 0" in reset
    redirect = src[src.index("fun markRecentRedirect("):src.index("fun isLeftCornerDisabledForCurrentAd()")]
    assert "disableLeftCornerForCurrentAd = true" in redirect
    assert "val leftCornerAllowedForAd = !disableLeftCornerForCurrentAd && (isMetaAd || leftCornerGeoXClicks < 2)" in src
    assert "(!leftCornerAllowedForAd && isInLeftCornerZone(x, y))" in src
    assert "!disableLeftCornerForCurrentAd && !isInvalidOrBlacklisted(sponsoredLeftTopX.centerX()" in src


def test_external_guard_marks_redirect_from_external_app():
    assert "adCloserHandler.markRecentRedirect(fromExternalApp = true)" in engine_src()


def test_line_promo_close_click_limit():
    src = closer_src()
    assert "linePromoCloseClicks < 2 && ocrRecognizer.isLineInHousePromoAd(ocrItems)" in src
    assert "if (!isInvalidOrBlacklisted(promoCloseRect.centerX(), promoCloseRect.centerY()))" in src


# ---------------------------------------------------------------------------
# 3. 倒數秒數角落限制
# ---------------------------------------------------------------------------

def test_countdown_pure_digits_must_be_in_side_corner():
    src = ocr_src()
    assert "fun detectAdCountdownSeconds(items: List<OcrItem>, screenHeight: Int, screenWidth: Int = 0): Int?" in src
    assert "cx < (effectiveSw * 0.25f).toInt() || cx > (effectiveSw * 0.75f).toInt()" in src


def _py_countdown_digit(cx, sw):
    return cx < sw * 0.25 or cx > sw * 0.75


def test_big8_center_digit_rejected():
    sw = 1080
    assert not _py_countdown_digit(int(sw * 0.50), sw)  # BIG 8獎 中央 8
    assert _py_countdown_digit(int(sw * 0.90), sw)  # 右上角倒數圈
    assert _py_countdown_digit(int(sw * 0.08), sw)  # 左上角倒數圈


# ---------------------------------------------------------------------------
# 4. 版本與審計
# ---------------------------------------------------------------------------

def test_version_bump_v1_15_70():
    gradle = read("app/build.gradle.kts")
    assert re.search(r"versionCode\s*=\s*(?:103|104)", gradle)
    assert re.search(r'versionName\s*=\s*"(?:1.15.70|1.15.71)"', gradle)
    wf = read(".github/workflows/build-apk.yml")
    assert "v1.15.70" in wf or "v1.15.71" in wf
    notes = read("RELEASE_NOTES.md")
    assert 'versionCode 103, versionName "1.15.70"' in notes


# 注意：U+2715 / U+2716 (✕ ✖) 為 OCR 比對廣告關閉 X 字形之功能性符號，非 Emoji，故不列入範圍
EMOJI_RE = re.compile(
    "[\U0001F300-\U0001FAFF\U0001F000-\U0001F02F\U0001F1E6-\U0001F1FF\U0000FE0F]"
)
SIMPLIFIED_CHARS = "与这们为来时说过还进么头发关门问现点开无"


@pytest.mark.parametrize(
    "rel",
    [
        "app/src/main/java/com/linewalk/autoad/tasks/LotteryTask.kt",
        "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt",
        "app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt",
        "app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt",
        "RELEASE_NOTES.md",
    ],
)
def test_no_emoji_in_changed_files(rel):
    text = read(rel)
    found = EMOJI_RE.findall(text)
    assert not found, f"{rel} 含 Emoji: {found[:5]}"


def test_new_v1_15_70_text_is_traditional_chinese():
    notes = read("RELEASE_NOTES.md")
    section = notes[notes.index("## 具體更新與修復內容 (v1.15.70)"):notes.index("## 歷史紀錄 (v1.15.69)")]
    bad = [c for c in SIMPLIFIED_CHARS if c in section]
    assert not bad, f"v1.15.70 發布說明含簡體字: {bad}"
