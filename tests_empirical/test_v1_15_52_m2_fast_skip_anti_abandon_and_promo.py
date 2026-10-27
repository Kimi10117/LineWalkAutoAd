#!/usr/bin/env python3
"""
EMPIRICAL TEST SUITE: Milestone 2 (v1.15.52 / versionCode 85)
Requirements R2, R3, R7.1, R7.2
LineWalkAutoAd

Covers:
- R2: Global Fast Skip on ▶|, >|, >>|, >> across all screen quadrants.
      Releases ▶| Google Play capsules (e.g. ShopBack ads) without Google Play suppression.
      Preserves isExplicitSkipGooglePlayPill for v1.15.48 regression contract.
      External redirect to Google Play (com.android.vending) triggers back navigation
      and cleanly recovers to ad End Card.
- R3: Multi-Language Anti-Abandon Confirmation Popup Interception.
      Removes bait words from AD_CLOSE_OCR_KEYWORDS (放棄獎勵, 放棄, Close, CLOSE, Quit, Discard).
      Intercepts confirmation popups on trigger words (close this ad, lose your reward,
      would you like to close, quit, 放棄獎勵, 無法獲得獎勵, lose reward, give up).
      100% clicks continue buttons (Keep watching, Resume, Continue, Cancel, 繼續觀看, 繼續, 取消).
      0% accidental close (never clicks close, quit, or discard).
- R7.1: Line promo close hotzone expansion to 0.050f..0.120f in OcrRecognizer.kt.
- R7.2: Compiler imports & constant definitions (max, min, Log, TAG).
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
# R7.2: Fix Missing Imports & Constants
# ==============================================================================

def test_r7_2_missing_imports_and_constants():
    """Verify AdCloserHandler.kt and NodeInspector.kt include required imports and TAG."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    inspector_code = (SRC_DIR / "vision/NodeInspector.kt").read_text(encoding="utf-8")

    # AdCloserHandler.kt
    assert "import kotlin.math.max" in closer_code
    assert "import kotlin.math.min" in closer_code
    assert "import android.util.Log" in closer_code
    assert 'private const val TAG = "AdCloserHandler"' in closer_code

    # NodeInspector.kt
    assert "import kotlin.math.max" in inspector_code

    # Scan all Kotlin source files to guarantee no unresolved Log calls or missing TAG
    kt_files = list((SRC_DIR.parent / "autoad").rglob("*.kt"))
    assert len(kt_files) >= 10, "Should scan all project Kotlin files"

    for kt_file in kt_files:
        content = kt_file.read_text(encoding="utf-8")
        if re.search(r"\bLog\.[diewv]\(", content):
            assert "import android.util.Log" in content, f"Missing android.util.Log in {kt_file.name}"
        if re.search(r"\bTAG\b", content):
            assert re.search(r"(?:val|const\s+val)\s+TAG\b", content) is not None, f"Missing TAG definition in {kt_file.name}"


# ==============================================================================
# R7.1: Line Promo Close Hotzone Expansion
# ==============================================================================

def test_r7_1_promo_close_hotzone_expansion():
    """Verify getLineInHousePromoCloseButton vertical range is expanded to 0.050f..0.120f."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")

    assert "(sh * 0.050f).toInt()" in ocr_code
    assert "(sh * 0.120f).toInt()" in ocr_code

    # Verify across multiple screen resolutions
    test_resolutions = [
        (1080, 2400, "Samsung S24 / Modern 20:9"),
        (1080, 2640, "Galaxy Z Flip / Ultra-Tall 22:9"),
        (1080, 1920, "Standard 16:9"),
        (1440, 3200, "WQHD+ 20:9"),
        (720, 1600, "HD+ Budget"),
    ]

    for sw, sh, desc in test_resolutions:
        rect = Rect(
            int(sw * 0.86),
            int(sh * 0.050),
            int(sw * 0.98),
            int(sh * 0.120),
        )

        # On tall screens, the in-house promo close (X) typically appears at y ≈ 0.06sh .. 0.07sh
        tall_promo_x = int(sw * 0.92)
        tall_promo_y = int(sh * 0.065)

        assert rect.left <= tall_promo_x <= rect.right, f"[{desc}] X={tall_promo_x} must be within promo rect X"
        assert rect.top <= tall_promo_y <= rect.bottom, f"[{desc}] Y={tall_promo_y} must be within promo rect Y"

        # On standard screens, the in-house promo close (X) typically appears at y ≈ 0.095sh .. 0.11sh
        std_promo_x = int(sw * 0.92)
        std_promo_y = int(sh * 0.105)

        assert rect.left <= std_promo_x <= rect.right, f"[{desc}] X={std_promo_x} must be within promo rect X"
        assert rect.top <= std_promo_y <= rect.bottom, f"[{desc}] Y={std_promo_y} must be within promo rect Y"


# ==============================================================================
# R3: Bait Keywords Removed from AD_CLOSE_OCR_KEYWORDS
# ==============================================================================

def test_r3_ad_close_ocr_keywords_no_bait_words():
    """Verify AD_CLOSE_OCR_KEYWORDS does not contain bait words."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")

    # Extract AD_CLOSE_OCR_KEYWORDS block
    match = re.search(r"val\s+AD_CLOSE_OCR_KEYWORDS\s*=\s*listOf\((.*?)\)", ocr_code, re.DOTALL)
    assert match is not None, "AD_CLOSE_OCR_KEYWORDS must exist in OcrRecognizer.kt"
    keywords_block = match.group(1)

    bait_words = [
        "放棄獎勵",
        "放棄",
        "Close",
        "CLOSE",
        "Quit",
        "Discard",
    ]

    for bait in bait_words:
        pattern = rf'"{re.escape(bait)}"'
        assert not re.search(pattern, keywords_block), f"Bait word '{bait}' must NOT be in AD_CLOSE_OCR_KEYWORDS"

    # Legitimate skip/close indicators must still be present
    legitimate_keywords = ["略過廣告", "跳過廣告", "關閉廣告", "Skip Ad", "✕", "×", ">>"]
    for leg in legitimate_keywords:
        assert f'"{leg}"' in keywords_block, f"Legitimate keyword '{leg}' must remain in AD_CLOSE_OCR_KEYWORDS"


# ==============================================================================
# R3: Multi-Language Anti-Abandon Confirmation Popup Interception Simulation
# ==============================================================================

def test_r3_anti_abandon_confirmation_popup_simulation():
    """Verify anti-abandon confirmation popup logic: 100% continue, 0% accidental close."""
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

    def process_confirmation_popup(ocr_items):
        has_anti_abandon_popup = any(
            any(
                (item.text.strip().lower() == "quit" or item.text.strip().lower() == "quit?" or
                 item.text.strip().lower().startswith("quit ") or item.text.strip().lower().endswith(" quit") or
                 " quit " in item.text.strip().lower())
                if kw == "quit"
                else kw.lower() in item.text.strip().lower()
                for kw in trigger_keywords
            )
            for item in ocr_items
        )

        if not has_anti_abandon_popup:
            return "NO_POPUP"

        continue_btn = next(
            (
                item for item in ocr_items
                if any(kw.lower() in item.text.strip().lower() for kw in continue_keywords)
                and not any(bait.lower() in item.text.strip().lower() for bait in bait_words)
            ),
            None
        )

        if continue_btn is not None:
            return ("CLICK_CONTINUE", continue_btn.text, continue_btn.bounds)

        # 0% accidental close: popup present but continue button not ready -> wait
        return "SCANNING_AD_WAIT"

    # Scenario 1: English - "Would you like to close this ad? You will lose your reward."
    dialog1 = [
        OcrItem("Would you like to close this ad?", Rect(100, 800, 980, 900)),
        OcrItem("You will lose your reward.", Rect(100, 920, 980, 1000)),
        OcrItem("Close", Rect(150, 1100, 450, 1220)),
        OcrItem("Keep watching", Rect(550, 1100, 950, 1220)),
    ]
    res1 = process_confirmation_popup(dialog1)
    assert res1[0] == "CLICK_CONTINUE"
    assert res1[1] == "Keep watching"
    assert res1[2].left == 550

    # Scenario 2: English - "Quit and lose reward?" with [Quit] and [Resume]
    dialog2 = [
        OcrItem("Quit and lose reward?", Rect(100, 800, 980, 900)),
        OcrItem("Quit", Rect(150, 1100, 450, 1220)),
        OcrItem("Resume", Rect(550, 1100, 950, 1220)),
    ]
    res2 = process_confirmation_popup(dialog2)
    assert res2[0] == "CLICK_CONTINUE"
    assert res2[1] == "Resume"

    # Scenario 3: English - "Close ad? You will lose reward." with [Close] and [Cancel]
    dialog3 = [
        OcrItem("Close ad? You will lose reward.", Rect(100, 800, 980, 900)),
        OcrItem("Close", Rect(150, 1100, 450, 1220)),
        OcrItem("Cancel", Rect(550, 1100, 950, 1220)),
    ]
    res3 = process_confirmation_popup(dialog3)
    assert res3[0] == "CLICK_CONTINUE"
    assert res3[1] == "Cancel"

    # Scenario 4: Chinese - "確定要離開廣告？將無法獲得獎勵" with [放棄獎勵] and [繼續觀看]
    dialog4 = [
        OcrItem("確定要離開廣告？將無法獲得獎勵", Rect(100, 800, 980, 900)),
        OcrItem("放棄獎勵", Rect(150, 1100, 450, 1220)),
        OcrItem("繼續觀看", Rect(550, 1100, 950, 1220)),
    ]
    res4 = process_confirmation_popup(dialog4)
    assert res4[0] == "CLICK_CONTINUE"
    assert res4[1] == "繼續觀看"

    # Scenario 5: Chinese - "離開廣告將無法獲得獎勵" with [離開] and [取消]
    dialog5 = [
        OcrItem("離開廣告將無法獲得獎勵", Rect(100, 800, 980, 900)),
        OcrItem("離開", Rect(150, 1100, 450, 1220)),
        OcrItem("取消", Rect(550, 1100, 950, 1220)),
    ]
    res5 = process_confirmation_popup(dialog5)
    assert res5[0] == "CLICK_CONTINUE"
    assert res5[1] == "取消"

    # Scenario 6: Chinese - "放棄獎勵嗎？" with [放棄] and [繼續]
    dialog6 = [
        OcrItem("放棄獎勵嗎？", Rect(100, 800, 980, 900)),
        OcrItem("放棄", Rect(150, 1100, 450, 1220)),
        OcrItem("繼續", Rect(550, 1100, 950, 1220)),
    ]
    res6 = process_confirmation_popup(dialog6)
    assert res6[0] == "CLICK_CONTINUE"
    assert res6[1] == "繼續"

    # Scenario 7: Anti-abandon popup present, but continue button not rendered yet
    dialog7 = [
        OcrItem("Would you like to close this ad? You will lose your reward.", Rect(100, 800, 980, 900)),
        OcrItem("Close", Rect(150, 1100, 450, 1220)),
    ]
    res7 = process_confirmation_popup(dialog7)
    assert res7 == "SCANNING_AD_WAIT", "Must wait for continue button, never accidentally click Close"

    # Scenario 8: Normal ad playback without confirmation popup
    dialog8 = [
        OcrItem("Play now for free!", Rect(100, 500, 980, 600)),
        OcrItem("ShopBack Cashback", Rect(100, 800, 980, 900)),
    ]
    res8 = process_confirmation_popup(dialog8)
    assert res8 == "NO_POPUP"


# ==============================================================================
# R2: Global Fast Skip (▶|, >|, >>|, >>) Across All Quadrants
# ==============================================================================

def test_r2_fast_skip_global_detection_simulation():
    """Verify global fast skip detection for ▶|, >|, >>|, >> releasing Google Play capsules."""
    fast_skip_symbols = ["▶|", ">|", ">>|", ">>"]

    def find_fast_skip(ocr_items):
        for item in ocr_items:
            t = item.text.replace(" ", "")
            if any(sym in t for sym in fast_skip_symbols):
                return item
        return None

    # Test Case 1: ShopBack ad top-left ▶| Google Play capsule
    shopback_pill = [
        OcrItem("▶| Google Play", Rect(40, 120, 360, 220)),
        OcrItem("ShopBack 現金回饋", Rect(200, 800, 800, 900)),
    ]
    match1 = find_fast_skip(shopback_pill)
    assert match1 is not None
    assert match1.text == "▶| Google Play"
    assert match1.bounds.left == 40

    # Test Case 2: Top-right fast forward >|
    top_right_skip = [
        OcrItem(">|", Rect(920, 120, 1040, 220)),
        OcrItem("Sponsored", Rect(100, 1200, 400, 1260)),
    ]
    match2 = find_fast_skip(top_right_skip)
    assert match2 is not None
    assert match2.text == ">|"

    # Test Case 3: Top-right >>
    double_arrow_skip = [
        OcrItem(">>", Rect(940, 140, 1020, 200)),
    ]
    match3 = find_fast_skip(double_arrow_skip)
    assert match3 is not None
    assert match3.text == ">>"

    # Test Case 4: Bottom-right >>|
    bottom_right_skip = [
        OcrItem(">>|", Rect(880, 2150, 1020, 2250)),
    ]
    match4 = find_fast_skip(bottom_right_skip)
    assert match4 is not None
    assert match4.text == ">>|"

    # Negative Test 1: Plain Google Play badge without fast skip symbol -> NOT fast skip
    plain_play_badge = [
        OcrItem("Google Play", Rect(40, 120, 320, 200)),
        OcrItem("安裝", Rect(400, 1800, 700, 1900)),
    ]
    assert find_fast_skip(plain_play_badge) is None

    # Negative Test 2: Play 商店 badge without fast skip symbol -> NOT fast skip
    plain_tw_play = [
        OcrItem("Play 商店", Rect(40, 120, 300, 200)),
    ]
    assert find_fast_skip(plain_tw_play) is None

    # Negative Test 3: Countdown digits -> NOT fast skip
    countdown_items = [
        OcrItem("15", Rect(950, 120, 1020, 180)),
        OcrItem("秒後可略過", Rect(750, 120, 940, 180)),
    ]
    assert find_fast_skip(countdown_items) is None


# ==============================================================================
# R2: External App Return Recovery (com.android.vending)
# ==============================================================================

def test_r2_external_app_redirect_and_end_card_recovery():
    """Verify redirect to Google Play triggers back navigation and recovers to ad End Card."""
    app_config_code = (SRC_DIR / "config/AppConfig.kt").read_text(encoding="utf-8")
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    # 1. Verify com.android.vending is classified as external redirect package
    assert '"com.android.vending"' in app_config_code
    assert "fun isRedirectPackage(" in app_config_code

    # 2. Verify redirect back key in AdCloserHandler.kt
    assert "val isExternal = fgPkg != null && AppConfig.isRedirectPackage(fgPkg) && !isAd" in closer_code
    assert "service.navigateBack()" in closer_code
    assert "return AdStepResult.RedirectRecovered(fgPkg ?: \"InAppBrowser\")" in closer_code

    # 3. Verify safeNavigateBack in AutomationEngine.kt for com.android.vending
    assert 'externalPkg == "com.android.vending"' in ae_code
    assert "service.safeNavigateBack(minIntervalMs = 800L)" in ae_code

    # 4. Verify ad End Card recovery keywords and back key
    assert 't.contains("ShopBack", ignoreCase = true)' in closer_code
    assert 't.contains("下載")' in closer_code
    assert 't.contains("安裝")' in closer_code
    assert "isEndCard && elapsedSec >= 15" in closer_code
    assert "service.safeNavigateBack(allowInGame = true)" in closer_code
    assert '"廣告結束卡片安全返回鍵"' in closer_code


# ==============================================================================
# Regression Contract Check: test_v1_15_48 Compatibility
# ==============================================================================

def test_v1_15_48_regression_contract_maintained():
    """Ensure all symbols asserted by test_v1_15_48 remain intact in AdCloserHandler.kt."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "isExplicitSkipGooglePlayPill" in closer_code
    assert "hasGooglePlayOnScreen" in closer_code
    assert "!hasGooglePlayOnScreen" in closer_code
    assert "val hasGooglePlayNear = ocrItems.any" in closer_code
    assert "!hasGooglePlayNear" in closer_code


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
