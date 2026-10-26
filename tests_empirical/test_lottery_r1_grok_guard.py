#!/usr/bin/env python3
"""
EMPIRICAL TEST SUITE: R1 Lottery Priority & Grok Bot False-Positive Remediation
LineWalkAutoAd
"""

import os
import re
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


class Rect:
    def __init__(self, left, top, right, bottom):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    def centerX(self):
        return (self.left + self.right) / 2.0

    def centerY(self):
        return (self.top + self.bottom) / 2.0


class OcrItem:
    def __init__(self, text, bounds):
        self.text = text
        self.bounds = bounds


def simulate_ocr_close_button_match(items, sw=1080, sh=2400):
    """Simulate OcrRecognizer.kt findCenterRewardModalCloseButton matching logic."""
    for item in items:
        cx = item.bounds.centerX()
        cy = item.bounds.centerY()
        norm_y = cy / float(sh)
        norm_x = cx / float(sw)

        if not (0.60 <= norm_y <= 0.78 and 0.20 <= norm_x <= 0.80):
            continue

        t = item.text.strip()
        t_lower = t.lower()

        # Ad banner keyword exclusion
        is_ad_banner = (
            "google play" in t_lower or
            "play store" in t_lower or
            "play 商店" in t or
            "play商店" in t or
            "安裝" in t or
            "下載" in t or
            "sponsored" in t_lower or
            "AD" in t or
            "廣告" in t or
            "推薦" in t or
            "grok" in t_lower or
            "tiktok" in t_lower or
            "book" in t_lower
        )
        if is_ad_banner:
            continue

        # OK exact matching
        is_exact_ok = bool(re.match(r"^\s*\[?\s*OK\s*\]?\s*$", t, re.IGNORECASE)) or t.lower() == "ok"
        is_chinese_close = any(kw in t for kw in ["關閉", "確認", "確定", "收下"])

        if (is_exact_ok or is_chinese_close) and len(t) <= 6:
            return f"OCR[{t}]", item.bounds

    return None


def test_grok_and_ad_keywords_excluded():
    sw, sh = 1080, 2400
    deceptive_keywords = [
        "Grok", "Grok Bot", "grok", "TikTok", "tiktok", "Book", "book",
        "Facebook", "Google Play", "Play Store", "Play 商店", "安裝", "下載",
        "Sponsored", "AD", "推薦", "廣告"
    ]
    for kw in deceptive_keywords:
        items = [
            OcrItem("獲得15張票券！", Rect(int(sw * 0.25), int(sh * 0.25), int(sw * 0.75), int(sh * 0.35))),
            OcrItem(kw, Rect(int(sw * 0.35), int(sh * 0.65), int(sw * 0.65), int(sh * 0.73)))
        ]
        result = simulate_ocr_close_button_match(items, sw, sh)
        assert result is None, f"Keyword '{kw}' must NEVER match as close button!"


def test_exact_ok_and_chinese_close_accepted():
    sw, sh = 1080, 2400
    valid_keywords = ["OK", "ok", " OK ", "[ OK ]", "關閉", "確認", "確定", "收下"]
    for kw in valid_keywords:
        items = [
            OcrItem("獲得15張票券！", Rect(int(sw * 0.25), int(sh * 0.25), int(sw * 0.75), int(sh * 0.35))),
            OcrItem(kw, Rect(int(sw * 0.35), int(sh * 0.65), int(sw * 0.65), int(sh * 0.73)))
        ]
        result = simulate_ocr_close_button_match(items, sw, sh)
        assert result is not None, f"Valid close keyword '{kw}' must be accepted!"
        assert result[0] == f"OCR[{kw.strip()}]"


def test_roi_bounds_enforced():
    sw, sh = 1080, 2400
    # y = 0.52 (outside 0.60..0.78) -> rejected
    items_high = [OcrItem("OK", Rect(int(sw * 0.35), int(sh * 0.50), int(sw * 0.65), int(sh * 0.54)))]
    assert simulate_ocr_close_button_match(items_high, sw, sh) is None

    # y = 0.85 (outside 0.60..0.78) -> rejected
    items_low = [OcrItem("OK", Rect(int(sw * 0.35), int(sh * 0.83), int(sw * 0.65), int(sh * 0.87)))]
    assert simulate_ocr_close_button_match(items_low, sw, sh) is None

    # x = 0.10 (outside 0.20..0.80) -> rejected
    items_left = [OcrItem("OK", Rect(int(sw * 0.05), int(sh * 0.65), int(sw * 0.15), int(sh * 0.72)))]
    assert simulate_ocr_close_button_match(items_left, sw, sh) is None


def test_lottery_priority_ordering():
    def simulate_find_target_draw(items, is_fifty_cooldown=False, is_free_cooldown=False):
        # Priority 1: 50-draw
        if not is_fifty_cooldown:
            for it in items:
                t = it.text.replace(" ", "")
                if ("50張" in t or ("50" in t and "抽" in t)) and "5張" not in t and "回復" not in t:
                    return "50張抽一次", it.bounds

        # Priority 2: Free-draw
        if not is_free_cooldown:
            for it in items:
                t = it.text.replace(" ", "")
                if ("免費抽" in t or "免費" in t) and "5張" not in t and "回復" not in t:
                    return "免費看廣告抽一次", it.bounds

        return None

    items = [
        OcrItem("50張 抽1次", Rect(100, 1000, 450, 1200)),
        OcrItem("免費抽1次", Rect(550, 1100, 950, 1300)),
        OcrItem("5張 抽1次", Rect(550, 1000, 950, 1100))
    ]

    # Both available -> 50-draw first
    assert simulate_find_target_draw(items, False, False)[0] == "50張抽一次"

    # 50 in cooldown -> free-draw second
    assert simulate_find_target_draw(items, True, False)[0] == "免費看廣告抽一次"

    # Both in cooldown -> None
    assert simulate_find_target_draw(items, True, True) is None


def test_ast_r1_source_integrity():
    ocr_path = os.path.join(SRC_DIR, "vision", "OcrRecognizer.kt")
    with open(ocr_path, "r", encoding="utf-8") as f:
        src_ocr = f.read()

    assert "normY !in 0.60f..0.78f || normX !in 0.20f..0.80f" in src_ocr
    assert "Regex(\"\"\"^\\s*\\[?\\s*OK\\s*\\]?\\s*$\"\"\", RegexOption.IGNORE_CASE)" in src_ocr
    assert "t.contains(\"Grok\", ignoreCase = true)" in src_ocr
    assert "t.contains(\"TikTok\", ignoreCase = true)" in src_ocr
    assert "t.contains(\"Google Play\", ignoreCase = true)" in src_ocr

    lottery_path = os.path.join(SRC_DIR, "tasks", "LotteryTask.kt")
    with open(lottery_path, "r", encoding="utf-8") as f:
        src_lottery = f.read()

    idx_50 = src_lottery.find("【第一順位】：50 抽")
    idx_free = src_lottery.find("【第二順位】：免費看廣告抽一次")
    assert idx_50 != -1 and idx_free != -1
    assert idx_50 < idx_free, "50-draw must be checked before free-draw in LotteryTask"

    engine_path = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        src_engine = f.read()

    assert "val modalCloseBtn = ocrRecognizer.findCenterRewardModalCloseButton(screenBitmap, ocrItems)" in src_engine
    assert "if (modalCloseBtn == null && !hasLotteryResultContext) {" in src_engine


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
