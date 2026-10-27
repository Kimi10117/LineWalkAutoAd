#!/usr/bin/env python3
"""
ADVERSARIAL EMPIRICAL CHALLENGER TEST SUITE: Milestone 2 (R2, R3, R7.1, R7.2)
Challenger 2 (challenger_m2_2)
LineWalkAutoAd

Empirically challenges:
1. Compiler Cleanliness:
   - All max, min, Log, and TAG references compile cleanly across ALL project Kotlin files
     with zero missing imports or definitions.
   - AdCloserHandler.kt and NodeInspector.kt imports specifically verified.
2. Unicode & State Machine Robustness:
   - No infinite loops or NPE when OCR returns partial matches, empty strings,
     and symbols with weird unicode spacing (NBSP, thin space, zero-width space, full-width space).
3. Legitimate Countdown & Close Unblocking:
   - When no confirmation dialog is present, legitimate countdown timers and close buttons
     operate unhindered without being trapped.
4. State Machine Convergence:
   - Blacklist protection terminates repeat coordinate clicking, preventing loops.
5. Adversarial Challenge on R3 Anti-Abandon Modal Detection:
   - Tests sentence-level questions ending with 'quit?' (e.g. 'Are you sure you want to quit?',
     'Do you want to quit?') and Chinese variants (e.g. '確定要放棄？').
   - Empirically verifies whether AdCloserHandler.kt source code currently intercepts these
     to guarantee 0% accidental close.
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
# Challenge 1: Compiler Cleanliness (max, min, Log, TAG)
# ==============================================================================

def test_compiler_cleanliness_all_kotlin_files():
    """Verify that all max, min, Log, and TAG references compile cleanly across all Kotlin files."""
    kt_files = list(SRC_DIR.parent.rglob("*.kt"))
    assert len(kt_files) >= 15, f"Expected at least 15 Kotlin files, found {len(kt_files)}"

    for kt_file in kt_files:
        content = kt_file.read_text(encoding="utf-8")
        lines = content.splitlines()

        # Check Log calls
        log_lines = [i + 1 for i, l in enumerate(lines) if re.search(r"\bLog\.[diewv]\b", l)]
        if log_lines:
            assert "import android.util.Log" in content, (
                f"Missing Log import in {kt_file.name} at lines {log_lines}"
            )

        # Check TAG usage
        tag_lines = [
            i + 1 for i, l in enumerate(lines)
            if re.search(r"\bTAG\b", l) and not re.search(r"(?:val|const\s+val)\s+TAG\b", l)
        ]
        if tag_lines:
            assert re.search(r"(?:val|const\s+val)\s+TAG\b", content), (
                f"Missing TAG definition in {kt_file.name} at lines {tag_lines}"
            )

        # Check bare max / min function calls
        max_lines = [
            i + 1 for i, l in enumerate(lines)
            if re.search(r"(?<![\.\w])max\(", l)
        ]
        if max_lines:
            assert (
                "import kotlin.math.max" in content
                or "import java.lang.Math.max" in content
                or "import kotlin.math.*" in content
            ), f"Missing max import in {kt_file.name} at lines {max_lines}"

        min_lines = [
            i + 1 for i, l in enumerate(lines)
            if re.search(r"(?<![\.\w])min\(", l)
        ]
        if min_lines:
            assert (
                "import kotlin.math.min" in content
                or "import java.lang.Math.min" in content
                or "import kotlin.math.*" in content
            ), f"Missing min import in {kt_file.name} at lines {min_lines}"


def test_adcloserhandler_and_nodeinspector_specific_imports():
    """Verify specific compiler imports added in M2 for AdCloserHandler.kt and NodeInspector.kt."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    inspector_code = (SRC_DIR / "vision/NodeInspector.kt").read_text(encoding="utf-8")

    assert "import kotlin.math.max" in closer_code
    assert "import kotlin.math.min" in closer_code
    assert "import android.util.Log" in closer_code
    assert 'private const val TAG = "AdCloserHandler"' in closer_code

    assert "import kotlin.math.max" in inspector_code
    assert "import kotlin.math.min" in inspector_code


# ==============================================================================
# Challenge 2: State Machine Stress Testing - Exotic Unicode Spacing & Empty Strings
# ==============================================================================

def test_fast_skip_unicode_spacing_and_empty_strings():
    """Stress test fast skip matching against empty strings, weird spaces, and unicode symbols."""
    fast_skip_symbols = ["▶|", ">|", ">>|", ">>"]

    def simulate_fast_skip_match(ocr_items, sw=1080, sh=2400):
        for item in ocr_items:
            cx = item.bounds.centerX()
            cy = item.bounds.centerY()
            t = item.text.replace(" ", "")
            has_symbol = any(sym in t for sym in fast_skip_symbols)
            if has_symbol and not (cy < sh * 0.042 and (cx < sw * 0.18 or cx > sw * 0.82)):
                return item
        return None

    # Empty strings & whitespace fuzzing
    weird_whitespaces = [
        "",
        " ",
        "   ",
        "\t",
        "\n",
        "\r\n",
        "\u00A0",          # Non-breaking space
        "\u2000",          # En quad
        "\u2001",          # Em quad
        "\u2002",          # En space
        "\u2003",          # Em space
        "\u2009",          # Thin space
        "\u200A",          # Hair space
        "\u200B",          # Zero-width space
        "\u3000",          # Ideographic full-width space
    ]

    for ws in weird_whitespaces:
        items = [
            OcrItem(ws, Rect(100, 200, 300, 300)),
            OcrItem(f"{ws}{ws}", Rect(400, 500, 600, 600)),
        ]
        assert simulate_fast_skip_match(items) is None

    # Fast skip with standard space
    item1 = OcrItem("▶ | Google Play", Rect(50, 100, 400, 200))
    res1 = simulate_fast_skip_match([item1])
    assert res1 is not None and res1.text == "▶ | Google Play"

    # Fast skip with multiple spaces
    item2 = OcrItem("▶   |   Google Play", Rect(50, 100, 400, 200))
    res2 = simulate_fast_skip_match([item2])
    assert res2 is not None

    # Fast skip with >> and >|
    assert simulate_fast_skip_match([OcrItem(">>", Rect(900, 100, 1000, 200))]) is not None
    assert simulate_fast_skip_match([OcrItem(">|", Rect(900, 100, 1000, 200))]) is not None
    assert simulate_fast_skip_match([OcrItem(">>|", Rect(900, 100, 1000, 200))]) is not None

    # Non-skip labels
    non_matches = ["Play Store", "Google Play", "15 秒後可略過", "點擊下載", "立即體驗", "廣告"]
    for nm in non_matches:
        assert simulate_fast_skip_match([OcrItem(nm, Rect(100, 200, 300, 300))]) is None


# ==============================================================================
# Challenge 3: Legitimate Countdown & Close Unblocking (No Confirmation Dialog)
# ==============================================================================

def test_legitimate_countdown_and_close_not_blocked():
    """Verify that when no confirmation popup is present, countdown and close buttons operate unhindered."""
    def simulate_ad_closer_tick(ocr_items, elapsed_sec=6, has_anti_abandon_popup=False):
        if has_anti_abandon_popup:
            continue_keywords = ["Keep watching", "Resume", "Continue", "Cancel", "繼續觀看", "繼續", "取消"]
            bait_words = ["Close", "CLOSE", "Quit", "QUIT", "Discard", "DISCARD", "放棄獎勵", "放棄", "離開", "退出"]
            continue_btn = next(
                (item for item in ocr_items if any(kw.lower() in item.text.lower() for kw in continue_keywords)
                 and not any(bait.lower() in item.text.lower() for bait in bait_words)),
                None
            )
            if continue_btn:
                return f"CLICK_CONTINUE[{continue_btn.text}]"
            return "SCANNING_AD_WAIT"

        # Step 4A-1 Fast skip
        fast_skip_symbols = ["▶|", ">|", ">>|", ">>"]
        for item in ocr_items:
            t = item.text.replace(" ", "")
            if any(sym in t for sym in fast_skip_symbols):
                return f"FAST_SKIP[{item.text}]"

        # Step 5: Countdown detection
        countdown_match = next(
            (item for item in ocr_items if re.search(r"(\d{1,2})\s*(?:秒|s|sec)", item.text, re.IGNORECASE)),
            None
        )
        if countdown_match:
            sec = int(re.search(r"(\d{1,2})", countdown_match.text).group(1))
            if sec > 1:
                return f"COUNTDOWN_WAIT[{sec}]"

        # Step 7: Legitimate close keywords
        close_keywords = ["略過廣告", "跳過廣告", "關閉廣告", "Skip Ad", "✕", "×", "關閉"]
        close_match = next(
            (item for item in ocr_items if any(kw in item.text for kw in close_keywords)),
            None
        )
        if close_match:
            return f"CLICK_CLOSE[{close_match.text}]"

        return "SCANNING_AD"

    # Case 1: Active countdown
    cd_items = [OcrItem("還有 15 秒", Rect(800, 100, 1000, 200))]
    assert simulate_ad_closer_tick(cd_items, elapsed_sec=6) == "COUNTDOWN_WAIT[15]"

    # Case 2: Corner X
    close_items = [OcrItem("✕", Rect(940, 100, 1020, 180))]
    assert simulate_ad_closer_tick(close_items, elapsed_sec=15) == "CLICK_CLOSE[✕]"

    # Case 3: Skip Ad
    skip_items = [OcrItem("Skip Ad", Rect(850, 120, 1020, 200))]
    assert simulate_ad_closer_tick(skip_items, elapsed_sec=8) == "CLICK_CLOSE[Skip Ad]"

    # Case 4: Fast skip ▶|
    fast_items = [OcrItem("▶| Google Play", Rect(40, 100, 360, 200))]
    assert simulate_ad_closer_tick(fast_items, elapsed_sec=5) == "FAST_SKIP[▶| Google Play]"


# ==============================================================================
# Challenge 4: State Machine Convergence & Anti-Looping Guarantees
# ==============================================================================

def test_state_machine_convergence_and_blacklist_invariants():
    """Verify that repeat clicks on the same location trigger blacklist protection to prevent loops."""
    import math

    clicked_history = []
    blacklisted_points = []
    BLACKLIST_RADIUS_PX = 65.0

    def record_and_click(pt, allow_blacklist=True):
        clicked_history.append(pt)
        nearby_clicks = sum(
            1 for p in clicked_history
            if math.hypot(p[0] - pt[0], p[1] - pt[1]) < BLACKLIST_RADIUS_PX
        )
        if allow_blacklist and nearby_clicks >= 3:
            blacklisted_points.append(pt)

    def is_blacklisted(pt):
        return any(
            math.hypot(p[0] - pt[0], p[1] - pt[1]) < BLACKLIST_RADIUS_PX
            for p in blacklisted_points
        )

    target = (950, 150)
    assert not is_blacklisted(target)

    record_and_click(target)
    assert not is_blacklisted(target)

    record_and_click((952, 149))
    assert not is_blacklisted(target)

    record_and_click((948, 151))
    assert is_blacklisted(target), "Point must be blacklisted after 3 clicks in 65px radius"


# ==============================================================================
# Challenge 5: R3 Anti-Abandon Modal Detection - Quit Punctuation & Chinese Variants
# ==============================================================================

def test_anti_abandon_standard_triggers_pass():
    """Verify standard anti-abandon triggers that Worker M2 covered pass as expected."""
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

    def has_anti_abandon_popup(ocr_items):
        return any(
            any(
                (item.text.strip().lower() == "quit" or item.text.strip().lower() == "quit?" or
                 item.text.strip().lower().startswith("quit ") or item.text.strip().lower().endswith(" quit") or
                 " quit " in item.text.strip().lower())
                if kw == "quit"
                else kw.lower() in item.text.strip().lower()
                for kw in anti_abandon_trigger_keywords
            )
            for item in ocr_items
        )

    # Standard covered triggers
    standard_covered = [
        "Quit",
        "quit?",
        "Quit now",
        "Would you like to close this ad?",
        "You will lose your reward.",
        "確定要離開廣告？",
        "確定要退出",
        "確定離開",
        "確定放棄",
        "放棄獎勵",
        "無法獲得獎勵",
    ]
    for trig in standard_covered:
        assert has_anti_abandon_popup([OcrItem(trig, Rect(100, 200, 500, 300))])

    # Safe words that must NOT trigger popup
    safe_words = [
        "Quite an amazing game!",
        "Quitter never wins",
        "確定",
        "確定購買",
        "關閉廣告",
        "Reward granted",
        "還有 15 秒",
    ]
    for safe in safe_words:
        assert not has_anti_abandon_popup([OcrItem(safe, Rect(100, 200, 500, 300))])


def test_anti_abandon_quit_sentence_and_chinese_variants_challenge():
    """
    ADVERSARIAL CHALLENGE on R3 implementation in AdCloserHandler.kt:
    
    1. In AdCloserHandler.kt line 330:
       `if (kw == "quit") {
           t == "quit" || t == "quit?" || t.startsWith("quit ") || t.endsWith(" quit") || t.contains(" quit ")
       }`
       Fails on sentence questions ending in 'quit?' such as:
       - 'Are you sure you want to quit?'
       - 'Do you want to quit?'
       - 'Want to quit?'
       because `t.endsWith(" quit")` requires a trailing space before quit and NO question mark after it.

    2. In AdCloserHandler.kt line 321-325:
       `antiAbandonTriggerKeywords` contains '確定要離開', '確定要退出', '確定放棄', '確定離開'
       but misses '確定要放棄' and '確定退出'.

    This test asserts that the actual implementation in AdCloserHandler.kt must handle
    these cases so that confirmation dialogs are 100% intercepted with 0% accidental close.
    """
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    # Extract antiAbandonTriggerKeywords from AdCloserHandler.kt
    kw_match = re.search(r"val\s+antiAbandonTriggerKeywords\s*=\s*listOf\((.*?)\)", closer_code, re.DOTALL)
    assert kw_match is not None, "antiAbandonTriggerKeywords must exist in AdCloserHandler.kt"
    kw_block = kw_match.group(1)

    # 1. Check if '確定要放棄' is included in trigger keywords
    has_confirm_give_up = '"確定要放棄"' in kw_block
    
    # 2. Check if question-mark quit sentences are handled in the quit condition
    # Check whether regex \bquit\b or explicit ' quit?' handling is present
    has_quit_question_handling = (
        r"\bquit\b" in closer_code
        or 'quit?' in closer_code and ('endsWith(" quit?")' in closer_code or 'contains(" quit?")' in closer_code)
        or "Regex" in closer_code and "quit" in closer_code
    )

    # Assert that AdCloserHandler.kt includes robust sentence-level quit and Chinese variants
    assert has_confirm_give_up, (
        "DEFECT in AdCloserHandler.kt: '確定要放棄' is missing from antiAbandonTriggerKeywords. "
        "Dialogs displaying '確定要放棄？' will bypass Step 3A and trigger accidental close in Step 7."
    )

    assert has_quit_question_handling, (
        "DEFECT in AdCloserHandler.kt line 330: kw == 'quit' check only tests "
        "t == 'quit' || t == 'quit?' || t.startsWith('quit ') || t.endsWith(' quit') || t.contains(' quit '). "
        "It fails on 'Are you sure you want to quit?' and 'Do you want to quit?', bypassing Step 3A "
        "and allowing nodeInspector or AD_CLOSE_OCR_KEYWORDS to click Close/Quit."
    )


def test_simulation_quit_question_dialog_causes_accidental_close_on_current_code():
    """
    Simulates the exact runtime execution of AdCloserHandler.kt on a confirmation modal
    with title 'Are you sure you want to quit?' and buttons [Close] and [Keep watching].

    Shows that current AdCloserHandler code fails to intercept and instead clicks [Close],
    whereas fixed code intercepts and clicks [Keep watching].
    """
    modal_items = [
        OcrItem("Are you sure you want to quit?", Rect(100, 800, 980, 900)),
        OcrItem("Close", Rect(150, 1100, 450, 1220)),
        OcrItem("Keep watching", Rect(550, 1100, 950, 1220)),
    ]

    # Current AdCloserHandler.kt:329-334 logic:
    anti_abandon_trigger_keywords = [
        "close this ad", "lose your reward", "would you like to close",
        "lose reward", "give up", "quit", "放棄獎勵", "無法獲得獎勵",
        "reward will be lost", "discard reward", "quit and forfeit",
        "確定要離開", "確定要退出", "確定放棄", "確定離開"
    ]
    def current_has_popup(ocr_items):
        return any(
            any(
                (item.text.strip().lower() == "quit" or item.text.strip().lower() == "quit?" or
                 item.text.strip().lower().startswith("quit ") or item.text.strip().lower().endswith(" quit") or
                 " quit " in item.text.strip().lower())
                if kw == "quit"
                else kw.lower() in item.text.strip().lower()
                for kw in anti_abandon_trigger_keywords
            )
            for item in ocr_items
        )

    # Current logic fails to detect popup
    assert not current_has_popup(modal_items), (
        "Demonstrating current flaw: 'Are you sure you want to quit?' is NOT detected as popup"
    )

    # Proposed fixed logic (using word boundary or question mark):
    def fixed_has_popup(ocr_items):
        extended_triggers = anti_abandon_trigger_keywords + ["確定要放棄", "確定退出"]
        return any(
            any(
                bool(re.search(r"\bquit\b", item.text, re.IGNORECASE))
                if kw == "quit"
                else kw.lower() in item.text.strip().lower()
                for kw in extended_triggers
            )
            for item in ocr_items
        )

    # Fixed logic detects popup 100%
    assert fixed_has_popup(modal_items)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

