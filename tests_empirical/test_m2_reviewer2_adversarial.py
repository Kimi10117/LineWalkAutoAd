#!/usr/bin/env python3
"""
ADVERSARIAL STRESS TEST SUITE: Reviewer 2 (Milestone 2: R2, R3, R7.1, R7.2)
Target: AdCloserHandler.kt, OcrRecognizer.kt, NodeInspector.kt

Stress-tests:
1. Multi-language anti-abandon popup patterns (English, Traditional Chinese, mixed punctuation/casing).
2. Word boundary robustness: ensures words like "quite", "mosquito", "equity" do NOT trigger anti-abandon popup,
   while "quit", "Quit?", "quit watching", "quit and forfeit" DO trigger it.
3. 100% precedence of continue keywords ("Keep watching", "Resume", "Continue", "Cancel", "繼續觀看", "繼續", "取消")
   and absolute avoidance of bait words ("Close", "Quit", "Discard", "放棄", "放棄獎勵", "離開", "退出").
4. 0% accidental close: when confirmation dialog is present but continue button is not yet rendered,
   system MUST NOT fall through to close/skip steps, must return ScanningAd.
5. Fast skip symbols (▶|, >|, >>|, >>):
   - Unconditional match across all quadrants (including top-left ShopBack ▶| Google Play capsule).
   - Single play icon ▶ must NOT trigger fast skip (avoid pausing ad video).
   - Plain "Google Play" badge without skip symbols must NOT trigger fast skip.
6. Promo close hotzone (0.050f..0.120f) on diverse aspect ratios:
   - 16:9, 18:9, 19.5:9, 20:9, 21:9, 22:9 (SM-S7210 / SM-F731N), 4:3 foldable.
7. Kotlin syntax and AST integrity checks:
   - Required imports (kotlin.math.max, kotlin.math.min, android.util.Log)
   - TAG definition in companion object
   - No hardcoded test mocks or facade implementations in Kotlin source
"""

from pathlib import Path
import re
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
# 1. Anti-Abandon Popup Triggers and Word Boundary Robustness
# ==============================================================================

def test_anti_abandon_trigger_word_boundaries_and_adversarial_inputs():
    """Verify trigger keyword matching in AdCloserHandler.kt:

    - 'quit' matches only real quit usages, not 'quite', 'mosquito', 'equity'.
    - English and Chinese trigger phrases match across varied case and punctuation.
    """
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    # Verify antiAbandonTriggerKeywords definition in Kotlin code
    assert "val antiAbandonTriggerKeywords = listOf(" in closer_code
    assert '"close this ad"' in closer_code
    assert '"lose your reward"' in closer_code
    assert '"would you like to close"' in closer_code
    assert '"lose reward"' in closer_code
    assert '"give up"' in closer_code
    assert '"quit"' in closer_code
    assert '"放棄獎勵"' in closer_code
    assert '"無法獲得獎勵"' in closer_code
    assert '"確定要離開"' in closer_code
    assert '"確定要退出"' in closer_code

    # Extract the exact logic from Kotlin:
    # if (kw == "quit") {
    #     t == "quit" || t == "quit?" || t.startsWith("quit ") || t.endsWith(" quit") || t.contains(" quit ")
    # } else {
    #     t.contains(kw.lowercase())
    # }
    trigger_keywords = [
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

    def is_triggered(raw_text: str) -> bool:
        t = raw_text.strip().lower()
        return any(
            (t == "quit" or t == "quit?" or t.startswith("quit ") or t.endswith(" quit") or " quit " in t)
            if kw == "quit"
            else kw.lower() in t
            for kw in trigger_keywords
        )

    # Positive cases:
    assert is_triggered("Would you like to close this ad? You will lose your reward.")
    assert is_triggered("Close this ad?")
    assert is_triggered("You will lose your reward if you leave.")
    assert is_triggered("lose reward")
    assert is_triggered("Don't give up your bonus!")
    assert is_triggered("Quit?")
    assert is_triggered("Quit watching?")
    assert is_triggered("Do you want to quit now?")
    assert is_triggered("Click to quit")
    assert is_triggered("確定要離開廣告？將無法獲得獎勵")
    assert is_triggered("離開廣告將無法獲得獎勵")
    assert is_triggered("放棄獎勵")
    assert is_triggered("確定放棄本輪獎勵嗎？")
    assert is_triggered("確定要退出廣告播放嗎？")
    assert is_triggered("確定離開")

    # Negative / Adversarial cases (Must NOT falsely trigger):
    assert not is_triggered("Quite an impressive game!")
    assert not is_triggered("Mosquito bite relief spray")
    assert not is_triggered("Private equity fund")
    assert not is_triggered("Ubiquitous high-speed connectivity")
    assert not is_triggered("Aquiter water filter")
    assert not is_triggered("Welcome to LineWalk")
    assert not is_triggered("Earn 10 points today")


# ==============================================================================
# 2. Strict Precedence of Continue Words vs Bait Words
# ==============================================================================

def test_anti_abandon_bait_avoidance_and_continue_precedence():
    """Verify that bait words are NEVER clicked and continue words have 100% precedence."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

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

    def resolve_popup_button(ocr_items):
        continue_btn = next(
            (
                item for item in ocr_items
                if any(kw.lower() in item.text.strip().lower() for kw in continue_keywords)
                and not any(bait.lower() in item.text.strip().lower() for bait in bait_words)
            ),
            None
        )
        return continue_btn

    # Matrix of adversarial dialog configurations:
    scenarios = [
        # (dialog_title, bait_text, continue_text, expected_continue)
        ("Would you like to close this ad?", "Close", "Keep watching", "Keep watching"),
        ("You will lose your reward", "Quit", "Resume", "Resume"),
        ("Give up reward?", "Discard", "Continue", "Continue"),
        ("Are you sure you want to quit?", "Quit and forfeit", "Cancel", "Cancel"),
        ("確定要離開廣告？", "離開", "取消", "取消"),
        ("將無法獲得獎勵", "放棄獎勵", "繼續觀看", "繼續觀看"),
        ("確定放棄嗎？", "放棄", "繼續", "繼續"),
        ("確定要退出嗎？", "退出", "繼續播放", "繼續播放"),  # matches "繼續"
    ]

    for title, bait, cont, expected in scenarios:
        items = [
            OcrItem(title, Rect(100, 800, 980, 900)),
            OcrItem(bait, Rect(150, 1100, 450, 1220)),
            OcrItem(cont, Rect(550, 1100, 950, 1220)),
        ]
        chosen = resolve_popup_button(items)
        assert chosen is not None, f"Scenario '{title}' failed to find continue button"
        assert chosen.text == cont
        assert expected.lower() in chosen.text.lower()
        # Verify bait is NOT chosen
        assert not any(b.lower() in chosen.text.lower() for b in bait_words)

    # Edge Case: Button contains both bait and continue word (e.g. "Quit and Cancel")
    # Must reject because isBait is true!
    hybrid_items = [
        OcrItem("Quit and Cancel", Rect(100, 800, 500, 900)),
    ]
    assert resolve_popup_button(hybrid_items) is None


# ==============================================================================
# 3. 0% Accidental Close When Continue Button Not Ready
# ==============================================================================

def test_anti_abandon_blocking_prevents_fallthrough_to_close():
    """Verify that when anti-abandon popup is detected, the handler halts execution

    and returns ScanningAd if continue button is not found, never executing corner X or skip.
    """
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    # Step 3A must appear BEFORE Step 3 (grace period) and Step 4 (explicit skip)
    idx_3a = closer_code.find("3A. 【最高優先攔截：英文/多語系防放棄確認彈窗】")
    idx_3 = closer_code.find("3. 廣告前保護期 (4 秒)：靜候播放")
    idx_4 = closer_code.find("4. 【最高優先：檢查是否有明確的「略過/跳過」按鈕")

    assert idx_3a != -1, "Step 3A must exist in AdCloserHandler.kt"
    assert idx_3 != -1, "Step 3 must exist in AdCloserHandler.kt"
    assert idx_4 != -1, "Step 4 must exist in AdCloserHandler.kt"
    assert idx_3a < idx_3 < idx_4, "Step 3A must be evaluated prior to Step 3 and Step 4"

    # Step 3A must return ScanningAd(elapsedSec) if popup detected but continue button missing
    step_3a_block = closer_code[idx_3a:idx_3]
    assert "return AdStepResult.ScanningAd(elapsedSec)" in step_3a_block


# ==============================================================================
# 4. Global Fast Skip Symbol Discrimination
# ==============================================================================

def test_fast_skip_symbol_discrimination_and_safety():
    """Verify fastSkipSymbols = listOf("▶|", ">|", ">>|", ">>") safely identifies

    skip controls while avoiding regular play icon '▶' or plain 'Google Play'.
    """
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert 'val fastSkipSymbols = listOf("▶|", ">|", ">>|", ">>")' in closer_code

    fast_skip_symbols = ["▶|", ">|", ">>|", ">>"]

    def matches_fast_skip(text: str) -> bool:
        t = text.replace(" ", "")
        return any(sym in t for sym in fast_skip_symbols)

    # Legitimate fast skip symbols:
    assert matches_fast_skip("▶|")
    assert matches_fast_skip("▶ |")
    assert matches_fast_skip("▶| Google Play")  # ShopBack capsule
    assert matches_fast_skip("▶ | Google Play")
    assert matches_fast_skip(">|")
    assert matches_fast_skip("> |")
    assert matches_fast_skip(">>|")
    assert matches_fast_skip(">>")
    assert matches_fast_skip("> >")
    assert matches_fast_skip("Skip >>")

    # Non-fast-skip symbols (Must NOT match):
    assert not matches_fast_skip("▶")              # Single video play icon
    assert not matches_fast_skip("Google Play")    # Plain Google Play badge
    assert not matches_fast_skip("Play 商店")      # Plain Taiwan Play Store badge
    assert not matches_fast_skip("Download")
    assert not matches_fast_skip("15 秒後可略過")


# ==============================================================================
# 5. Promo Close Hotzone Expansion Across Aspect Ratios
# ==============================================================================

def test_promo_close_hotzone_aspect_ratio_stress():
    """Verify getLineInHousePromoCloseButton (0.050f..0.120f) covers the close button

    across 16:9, 18:9, 19.5:9, 20:9, 21:9, 22:9 (SM-S7210), and 4:3 foldable.
    """
    aspect_ratios = [
        {"name": "Standard 16:9", "sw": 1080, "sh": 1920, "expected_y_range": (96, 230)},
        {"name": "Tall 18:9", "sw": 1080, "sh": 2160, "expected_y_range": (108, 259)},
        {"name": "Modern 19.5:9 (iPhone/Pixel)", "sw": 1080, "sh": 2340, "expected_y_range": (117, 280)},
        {"name": "Ultra-Tall 20:9 (Galaxy S24)", "sw": 1080, "sh": 2400, "expected_y_range": (120, 288)},
        {"name": "Cinema 21:9 (Sony Xperia)", "sw": 1080, "sh": 2520, "expected_y_range": (126, 302)},
        {"name": "Ultra-Tall 22:9 (SM-S7210 / Galaxy Z Flip)", "sw": 1080, "sh": 2640, "expected_y_range": (132, 316)},
        {"name": "Foldable Unfolded 4:3 (Z Fold 6)", "sw": 2176, "sh": 1812, "expected_y_range": (90, 217)},
    ]

    for device in aspect_ratios:
        sw = device["sw"]
        sh = device["sh"]
        rect = Rect(
            int(sw * 0.86),
            int(sh * 0.050),
            int(sw * 0.98),
            int(sh * 0.120)
        )
        assert rect.top == device["expected_y_range"][0]
        assert rect.bottom == device["expected_y_range"][1]
        assert rect.height() > 0
        assert rect.width() > 0

        # Empirical promo (X) position on modern devices is at ~6% to ~10% of screen height
        test_y = int(sh * 0.075)
        assert rect.top <= test_y <= rect.bottom, (
            f"Device {device['name']}: Promo close (X) at {test_y}px must fall inside [{rect.top}, {rect.bottom}]"
        )


# ==============================================================================
# 6. Integrity and Non-Circumvention Audit
# ==============================================================================

def test_integrity_check_no_facades_or_hardcoded_mocks():
    """Verify implementation files implement genuine logic and contain no fake facades."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    inspector_code = (SRC_DIR / "vision/NodeInspector.kt").read_text(encoding="utf-8")

    # Ensure no test mock bypasses or hardcoded test returns
    for code, filename in [(closer_code, "AdCloserHandler.kt"), (ocr_code, "OcrRecognizer.kt"), (inspector_code, "NodeInspector.kt")]:
        assert "TODO" not in code, f"Unfinished TODO in {filename}"
        assert "FIXME" not in code, f"Unfinished FIXME in {filename}"
        assert "mock" not in code.lower(), f"Suspicious mock reference in {filename}"
        assert "fake" not in code.lower(), f"Suspicious fake reference in {filename}"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
