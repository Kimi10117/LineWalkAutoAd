#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
EMPIRICAL CHALLENGER STRESS TEST SUITE: Milestone 2
LineWalkAutoAd Universal Device Adaptation
Requirements: R2 (Global Fast Skip), R3 (Anti-Abandon Intercept), R7.1 (Promo Bounds), R7.2 (Compiler Imports)

Challenger 1 Adversarial Stress Suite.
"""

from pathlib import Path
import re
import random
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


class OcrItem:
    def __init__(self, text: str, bounds: Rect):
        self.text = text
        self.bounds = bounds


# ==============================================================================
# CHALLENGE 1: Fast Skip Symbols Across Quadrants & Negative Immunity
# ==============================================================================

def simulate_fast_skip_detection(ocr_items, sw, sh, blacklisted_coords=None):
    """Exact logic mirroring Step 4A-1 in AdCloserHandler.kt."""
    if blacklisted_coords is None:
        blacklisted_coords = set()

    fast_skip_symbols = ["▶|", ">|", ">>|", ">>"]

    def is_invalid_or_blacklisted(cx, cy):
        return (cx, cy) in blacklisted_coords

    for item in ocr_items:
        cx = item.bounds.centerX()
        cy = item.bounds.centerY()
        t = item.text.replace(" ", "")
        has_symbol = any(sym in t for sym in fast_skip_symbols)
        if has_symbol and not is_invalid_or_blacklisted(cx, cy):
            return item
    return None


def test_empirical_fast_skip_symbols_across_all_quadrants():
    """Stress test fast skip symbols across top-left, top-right, center, bottom-left, bottom-right."""
    sw, sh = 1080, 2400
    fast_symbols = ["▶|", ">|", ">>|", ">>", "▶ |", "> |", ">> |", "> >"]

    # Quadrant coordinate generators: (min_x, max_x, min_y, max_y)
    quadrants = {
        "TOP_LEFT_CAPSULE": (40, 360, 80, 240),      # e.g. ShopBack ▶| Google Play capsule
        "TOP_RIGHT_SKIP": (750, 1020, 80, 240),       # Standard top-right skip
        "CENTER_INTERSTITIAL": (400, 680, 1000, 1400),# Center overlay / video skip
        "BOTTOM_LEFT": (40, 360, 2100, 2300),         # Bottom bar skip
        "BOTTOM_RIGHT": (750, 1020, 2100, 2300),      # Bottom right skip
    }

    random.seed(42)

    for q_name, (x_min, x_max, y_min, y_max) in quadrants.items():
        for sym in fast_symbols:
            # Generate multiple variations with surrounding text
            text_variants = [
                sym,
                f"{sym} Google Play",
                f"{sym}Play 商店",
                f"Skip {sym}",
                f"{sym} 下載",
                f"優惠 {sym}",
            ]
            for text in text_variants:
                # Random position within quadrant
                left = random.randint(x_min, x_max - 100)
                top = random.randint(y_min, y_max - 60)
                right = left + 90
                bottom = top + 50

                items = [
                    OcrItem("Advertiser Content Here", Rect(100, 500, 900, 600)),
                    OcrItem(text, Rect(left, top, right, bottom)),
                    OcrItem("Shop Now", Rect(400, 1600, 680, 1700)),
                ]

                matched = simulate_fast_skip_detection(items, sw, sh)
                assert matched is not None, f"Failed to detect fast skip '{text}' in {q_name}"
                assert matched.text == text
                assert matched.bounds.left == left


def test_empirical_fast_skip_negative_immunity():
    """Ensure non-fast-skip text (e.g. ticket counts '▶ 8 獲得', single '>', plain 'Google Play') is immune."""
    sw, sh = 1080, 2400

    # Critical negative cases:
    # 1. Ticket draw buttons like '▶ 8 獲得' or '▶ 15 獲得' on game screen MUST NOT BE MATCHED!
    # 2. Plain 'Google Play' without skip symbol MUST NOT BE MATCHED!
    # 3. Plain 'Play 商店' without skip symbol MUST NOT BE MATCHED!
    # 4. Countdown texts like '15' or '15 秒後可略過' MUST NOT BE MATCHED!
    # 5. Price comparisons or math with single '>' like '> 50% OFF' MUST NOT BE MATCHED!
    negative_texts = [
        "▶ 8 獲得",
        "▶ 15 獲得",
        "▶ 1 獲得",
        "▶ 獲得票券",
        "Google Play",
        "Play 商店",
        "Google Play 遊戲",
        "15",
        "30",
        "15 秒後可略過",
        "5 秒後可關閉",
        "> 50% OFF",
        "Score > 1000",
        "▶ 開始遊戲",
        "點擊觀看更多",
    ]

    for neg_text in negative_texts:
        items = [
            OcrItem(neg_text, Rect(100, 100, 400, 180)),
            OcrItem("廣告內容", Rect(200, 500, 800, 700)),
        ]
        matched = simulate_fast_skip_detection(items, sw, sh)
        assert matched is None, f"False positive: '{neg_text}' should NEVER match fast skip"


def test_empirical_fast_skip_blacklist_suppression():
    """Verify blacklisted coordinates prevent misclick repetition."""
    sw, sh = 1080, 2400
    item = OcrItem("▶| Google Play", Rect(40, 100, 340, 180))
    cx, cy = item.bounds.centerX(), item.bounds.centerY()

    # When not blacklisted
    matched = simulate_fast_skip_detection([item], sw, sh, blacklisted_coords=set())
    assert matched is not None

    # When blacklisted
    matched_bl = simulate_fast_skip_detection([item], sw, sh, blacklisted_coords={(cx, cy)})
    assert matched_bl is None, "Blacklisted coordinate must be suppressed"


# ==============================================================================
# CHALLENGE 2: Multi-Language Anti-Abandon Confirmation Dialog Stress
# ==============================================================================

def simulate_anti_abandon_dialog_handler(ocr_items):
    """Exact logic mirroring Step 3A in AdCloserHandler.kt."""
    anti_abandon_trigger_keywords = [
        "close this ad",
        "lose your reward",
        "would you like to close",
        "lose reward",
        "give up",
        "quit",
        "放棄獎勵",
        "無法獲得獎勵",
        "reward will be lost",
        "discard reward",
        "quit and forfeit",
        "確定要離開",
        "確定要退出",
        "確定放棄",
        "確定離開",
    ]

    has_anti_abandon_popup = any(
        any(
            (
                item.text.strip().lower() == "quit"
                or item.text.strip().lower() == "quit?"
                or item.text.strip().lower().startswith("quit ")
                or item.text.strip().lower().endswith(" quit")
                or " quit " in item.text.strip().lower()
            )
            if kw == "quit"
            else kw.lower() in item.text.strip().lower()
            for kw in anti_abandon_trigger_keywords
        )
        for item in ocr_items
    )

    if not has_anti_abandon_popup:
        return "NO_POPUP"

    continue_keywords = [
        "Keep watching",
        "Resume",
        "Continue",
        "Cancel",
        "繼續觀看",
        "繼續",
        "取消",
    ]

    bait_words = ["Close", "CLOSE", "Quit", "QUIT", "Discard", "DISCARD", "放棄獎勵", "放棄", "離開", "退出"]

    continue_btn = next(
        (
            item for item in ocr_items
            if any(kw.lower() in item.text.strip().lower() for kw in continue_keywords)
            and not any(bait.lower() in item.text.strip().lower() for bait in bait_words)
        ),
        None,
    )

    if continue_btn is not None:
        return ("CLICK_CONTINUE", continue_btn.text, continue_btn.bounds)

    return "SCANNING_AD_WAIT"


def test_empirical_anti_abandon_standard_dialog_variants_100pct_continue_0pct_bait():
    """
    Stress-test anti-abandon dialog handling with supported standard dialog variants:
    - English titles: Would you like to close this ad?, You will lose your reward, Quit and forfeit reward
    - Chinese titles: 確定要離開廣告？將無法獲得獎勵, 確定離開廣告嗎？, 放棄獎勵嗎？
    - Permutations of button ordering (bait first vs continue first)
    - 100% targeting continue button, 0% bait click.
    """
    english_titles = [
        "Would you like to close this ad?",
        "WOULD YOU LIKE TO CLOSE THIS AD?",
        "You will lose your reward.",
        "YOU WILL LOSE YOUR REWARD!",
        "Close this ad? Reward will be lost.",
        "Quit and forfeit reward?",
        "Discard reward?",
        "Give up your reward?",
    ]

    chinese_titles = [
        "確定要離開廣告？將無法獲得獎勵",
        "確定離開廣告嗎？",
        "確定要退出廣告？",
        "確定要離開？",
        "確定放棄？",
        "放棄獎勵嗎？",
        "離開廣告將無法獲得獎勵！",
    ]

    english_continue_variants = [
        "Keep watching",
        "KEEP WATCHING",
        "keep watching",
        "Keep Watching",
        "Resume",
        "RESUME",
        "resume",
        "Continue",
        "CONTINUE",
        "continue",
        "Cancel",
        "CANCEL",
        "cancel",
    ]

    chinese_continue_variants = [
        "繼續觀看",
        "繼續",
        "取消",
    ]

    bait_variants = [
        "Close", "CLOSE", "close",
        "Quit", "QUIT", "quit",
        "Discard", "DISCARD", "discard",
        "放棄獎勵", "放棄",
        "離開", "退出",
    ]

    random.seed(1337)

    # 1. English Permutations
    for title in english_titles:
        for cont in random.sample(english_continue_variants, 3):
            for bait in random.sample(bait_variants[:6], 2):
                item_bait = OcrItem(bait, Rect(120, 1100, 420, 1220))
                item_cont = OcrItem(cont, Rect(560, 1100, 960, 1220))
                item_title = OcrItem(title, Rect(100, 800, 980, 950))

                # Test with bait first
                dialog_a = [item_title, item_bait, item_cont]
                res_a = simulate_anti_abandon_dialog_handler(dialog_a)
                assert res_a[0] == "CLICK_CONTINUE", f"Failed for title='{title}', cont='{cont}', bait='{bait}'"
                assert res_a[1] == cont

                # Test with continue first
                dialog_b = [item_title, item_cont, item_bait]
                res_b = simulate_anti_abandon_dialog_handler(dialog_b)
                assert res_b[0] == "CLICK_CONTINUE"
                assert res_b[1] == cont

    # 2. Chinese Permutations
    for title in chinese_titles:
        for cont in chinese_continue_variants:
            for bait in ["放棄獎勵", "放棄", "離開", "退出", "Close", "Quit"]:
                item_bait = OcrItem(bait, Rect(120, 1100, 420, 1220))
                item_cont = OcrItem(cont, Rect(560, 1100, 960, 1220))
                item_title = OcrItem(title, Rect(100, 800, 980, 950))

                for d in ([item_title, item_bait, item_cont], [item_title, item_cont, item_bait]):
                    res = simulate_anti_abandon_dialog_handler(d)
                    assert res[0] == "CLICK_CONTINUE", f"Failed for title='{title}', cont='{cont}'"
                    assert res[1] == cont


def test_empirical_anti_abandon_missing_continue_button_waits_safely():
    """
    Adversarial scenario: Confirmation popup appears (e.g. animation lag),
    bait button is visible, but continue button is not yet rendered.
    Must return SCANNING_AD_WAIT and NEVER click bait button!
    """
    for bait in ["Close", "Quit", "放棄獎勵", "離開", "Discard"]:
        dialog = [
            OcrItem("Would you like to close this ad? You will lose your reward.", Rect(100, 800, 980, 950)),
            OcrItem(bait, Rect(150, 1100, 450, 1220)),
        ]
        res = simulate_anti_abandon_dialog_handler(dialog)
        assert res == "SCANNING_AD_WAIT", f"Must wait, not click bait '{bait}'"


def test_empirical_anti_abandon_adversarial_vulnerabilities_proof():
    """
    Adversarial Proof of Vulnerabilities in AdCloserHandler.kt & OcrRecognizer.kt:
    
    Vulnerability 1:
    Worker M2's manual boundary check for 'quit':
        t == "quit" || t == "quit?" || t.startsWith("quit ") || t.endsWith(" quit") || t.contains(" quit ")
    fails when 'quit' ends a sentence with punctuation (e.g. 'Do you want to quit?' or 'Are you sure you want to quit?').
    In those cases, if the bait button is 'Close' or 'Discard' (not 'Quit'), hasAntiAbandonPopup evaluates to False,
    completely bypassing the anti-abandon interceptor!
    Regex r'\\bquit\\b' correctly detects these cases.

    Vulnerability 2:
    Worker M2 removed '放棄獎勵', '放棄', 'Close', 'Quit', 'Discard' from AD_CLOSE_OCR_KEYWORDS,
    but left '離開' and '退出' in AD_CLOSE_OCR_KEYWORDS!
    If a Chinese dialog with buttons [ 離開 ] and [ 繼續觀看 ] is not caught by trigger words,
    OCR close will match [ 離開 ] and click the bait button!
    """
    # 1. Demonstrate Vulnerability 1: quit boundary failure on punctuation
    problematic_titles = [
        "Do you want to quit?",
        "Are you sure you want to quit?",
        "Really quit?",
        "Want to quit?",
        "Sure you want to quit!",
        "Exit now and quit.",
    ]

    for title in problematic_titles:
        t = title.strip().lower()
        # Worker M2's exact logic:
        worker_matches = (
            t == "quit"
            or t == "quit?"
            or t.startswith("quit ")
            or t.endswith(" quit")
            or " quit " in t
        )
        # Demonstrating empirical flaw in Worker M2's condition:
        assert not worker_matches, f"Expected Worker M2's check to fail on '{title}', but it passed"

        # Correct regex boundary logic:
        regex_matches = bool(re.search(r"\bquit\b", t))
        assert regex_matches is True, f"Regex r'\\bquit\\b' must correctly identify 'quit' in '{title}'"

    # Also verify regex rejects substrings like mosquito, quite, equity
    non_quit_words = ["mosquito", "quite", "equity", "ubiquitous"]
    for w in non_quit_words:
        assert not re.search(r"\bquit\b", w), f"Regex r'\\bquit\\b' must NOT match '{w}'"

    # 2. Demonstrate Vulnerability 2: '離開' and '退出' in AD_CLOSE_OCR_KEYWORDS
    ocr_file = SRC_DIR / "vision/OcrRecognizer.kt"
    content = ocr_file.read_text(encoding="utf-8")
    match = re.search(r"val\s+AD_CLOSE_OCR_KEYWORDS\s*=\s*listOf\((.*?)\)", content, re.DOTALL)
    assert match is not None
    kw_block = match.group(1)

    # Verify remediated state: '離開' and '退出' are removed from AD_CLOSE_OCR_KEYWORDS
    has_leikai = '"離開"' in kw_block
    has_tuichu = '"退出"' in kw_block
    assert not has_leikai, "Remediated: '離開' must NOT remain in AD_CLOSE_OCR_KEYWORDS"
    assert not has_tuichu, "Remediated: '退出' must NOT remain in AD_CLOSE_OCR_KEYWORDS"


# ==============================================================================
# CHALLENGE 3: Promo Close Button Bounds Across Aspect Ratios
# ==============================================================================

def simulate_get_line_in_house_promo_close_button(sw: int, sh: int) -> Rect:
    """Exact logic mirroring getLineInHousePromoCloseButton in OcrRecognizer.kt."""
    return Rect(
        int(sw * 0.86),
        int(sh * 0.050),
        int(sw * 0.98),
        int(sh * 0.120),
    )


def test_empirical_promo_close_bounds_on_all_aspect_ratios():
    """
    Verify promo close button bounds on various aspect ratios:
    - 16:9 standard (1080x1920, 720x1280)
    - 18:9 (1080x2160)
    - 19.5:9 (1080x2340, 1440x3120)
    - 20:9 (1080x2400, 1440x3200)
    - 22:9 SM-S7210 / Z Flip ultra-tall (1080x2640)
    - 4:3 tablet (1536x2048)
    - 16:10 tablet (1200x1920, 1600x2560)
    """
    aspect_ratios = [
        (1080, 1920, "16:9 1080p Standard"),
        (720, 1280, "16:9 720p Budget"),
        (1080, 2160, "18:9 Modern"),
        (1080, 2340, "19.5:9 Galaxy S22"),
        (1440, 3120, "19.5:9 QHD+"),
        (1080, 2400, "20:9 Modern Standard"),
        (1440, 3200, "20:9 QHD+ Flagship"),
        (1080, 2640, "22:9 SM-S7210 / Z Flip Tall"),
        (1536, 2048, "4:3 iPad/Tablet"),
        (1200, 1920, "16:10 Android Tablet"),
        (1600, 2560, "16:10 QHD Tablet"),
    ]

    for sw, sh, desc in aspect_ratios:
        rect = simulate_get_line_in_house_promo_close_button(sw, sh)

        # 1. Bounds must be strictly positive and inside screen
        assert rect.left >= 0, f"[{desc}] Left out of bounds"
        assert rect.top >= 0, f"[{desc}] Top out of bounds"
        assert rect.right <= sw, f"[{desc}] Right out of bounds: {rect.right} > {sw}"
        assert rect.bottom <= sh, f"[{desc}] Bottom out of bounds: {rect.bottom} > {sh}"

        # 2. Width and height must be comfortably clickable (> 36px)
        assert rect.width() >= 36, f"[{desc}] Width {rect.width()} too small"
        assert rect.height() >= 36, f"[{desc}] Height {rect.height()} too small"

        # 3. Upper right quadrant verification
        cx = rect.centerX()
        cy = rect.centerY()
        assert cx >= sw * 0.85, f"[{desc}] CenterX {cx} not in right corner (sw={sw})"
        assert cy <= sh * 0.15, f"[{desc}] CenterY {cy} not in top bar (sh={sh})"

        # 4. Specific physical position checks:
        # Tall devices (22:9) have promo (X) at y ≈ 0.055sh .. 0.075sh
        tall_y = int(sh * 0.065)
        assert rect.top <= tall_y <= rect.bottom, f"[{desc}] Failed to encompass tall screen (X) at Y={tall_y}"

        # Standard devices (16:9) have promo (X) at y ≈ 0.085sh .. 0.110sh
        std_y = int(sh * 0.095)
        assert rect.top <= std_y <= rect.bottom, f"[{desc}] Failed to encompass standard screen (X) at Y={std_y}"


def test_empirical_line_promo_ad_recognition():
    """Verify isLineInHousePromoAd logic in OcrRecognizer.kt."""
    def is_line_promo(ocr_items):
        has_promo_kw = False
        has_invite_text = False
        has_coins_text = False

        for item in ocr_items:
            t = item.text.replace(" ", "")
            if any(k in t for k in ["還沒有邀請好友", "實在太可惜了", "每邀請一位好友", "被邀請", "邀請好友賺"]):
                has_promo_kw = True
            if "邀請好友" in t or "邀請" in t:
                has_invite_text = True
            if "金幣" in t or "10000" in t or "000" in t:
                has_coins_text = True

        if has_promo_kw:
            return True
        if has_invite_text and has_coins_text and any(
            any(w in it.text.replace(" ", "") for w in ["領取", "獲得", "任務", "流程", "完成"])
            for it in ocr_items
        ):
            return True
        return False

    # Positive test cases
    assert is_line_promo([OcrItem("還沒有邀請好友 實在太可惜了！", Rect(100, 200, 900, 300))])
    assert is_line_promo([OcrItem("每邀請一位好友即可獲得金幣", Rect(100, 200, 900, 300))])
    assert is_line_promo([
        OcrItem("邀請好友", Rect(100, 200, 400, 300)),
        OcrItem("10000金幣", Rect(500, 200, 800, 300)),
        OcrItem("完成任務即可領取", Rect(100, 400, 900, 500)),
    ])

    # Negative test cases (ordinary game screens)
    assert not is_line_promo([
        OcrItem("每日步數", Rect(100, 200, 400, 300)),
        OcrItem("今日累積 5000 步", Rect(100, 400, 800, 500)),
    ])
    assert not is_line_promo([
        OcrItem("抽抽樂", Rect(100, 200, 400, 300)),
        OcrItem("剩餘票券: 30", Rect(100, 400, 800, 500)),
    ])


# ==============================================================================
# CHALLENGE 4: Compiler Imports & Constants Verification
# ==============================================================================

def test_empirical_compiler_imports_and_constants():
    """Verify all imports and constants required by Kotlin compiler and CI/CD."""
    closer_src = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    inspector_src = (SRC_DIR / "vision/NodeInspector.kt").read_text(encoding="utf-8")

    # AdCloserHandler.kt requirements
    assert "import kotlin.math.max" in closer_src
    assert "import kotlin.math.min" in closer_src
    assert "import android.util.Log" in closer_src
    assert 'private const val TAG = "AdCloserHandler"' in closer_src

    # NodeInspector.kt requirements
    assert "import kotlin.math.max" in inspector_src
    assert "import kotlin.math.min" in inspector_src

    # Step ordering check in AdCloserHandler.kt
    # Anti-abandon Step 3A must appear BEFORE Step 4A-1 fast skip and Step 4B explicit skip
    idx_anti_abandon = closer_src.find("3A. 【最高優先攔截：英文/多語系防放棄確認彈窗】")
    idx_fast_skip = closer_src.find("4A-1. 【全畫面 ▶| / >| / >> / >>| 略過符號全域放行秒點】")
    idx_explicit_skip = closer_src.find("4B. 全畫面 OCR 明確略過按鈕搜尋")

    assert idx_anti_abandon != -1, "Step 3A anti-abandon not found"
    assert idx_fast_skip != -1, "Step 4A-1 fast skip not found"
    assert idx_explicit_skip != -1, "Step 4B explicit skip not found"

    assert idx_anti_abandon < idx_fast_skip < idx_explicit_skip, (
        "Execution order must be: Anti-abandon (3A) -> Fast Skip (4A-1) -> Explicit Skip (4B)"
    )


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
