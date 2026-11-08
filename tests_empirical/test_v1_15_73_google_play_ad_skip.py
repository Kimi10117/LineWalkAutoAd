# -*- coding: utf-8 -*-
"""
v1.15.73 回歸測試：
1. 互動式可玩廣告 (如「解開港口」) 左上角 [▶| Google Play] 略過膠囊極速秒點 (移除 4 秒延遲，第 1 秒起即刻秒點)
2. 略過膠囊點擊 allowBlacklist = false，保障正常跳轉商店返回後座標不被黑名單封鎖
3. 試玩廣告控制項隱藏之滑動螢幕重新喚醒機制 (Reveal Swipe，8B)
4. AdStepResult.SwipedToReveal 密封類與 AutomationEngine 狀態處理與懸浮窗即時提示
5. 單次廣告生命週期重設 (revealSwipeCount / lastRevealSwipeTimeMs)
6. 版本號 (106 / 1.15.73)、0 Emoji 與繁體中文審計
"""
import re
from pathlib import Path
import pytest

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/linewalk/autoad"


def closer_src() -> str:
    return (SRC / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")


def engine_src() -> str:
    return (SRC / "engine/AutomationEngine.kt").read_text(encoding="utf-8")


def build_src() -> str:
    return (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# 1. 4B-2 極速秒點與無黑名單驗證
# ---------------------------------------------------------------------------

def test_google_play_pill_fast_detection_and_no_blacklist():
    src = closer_src()
    idx_4b2 = src.index("// 4B-2. 【左上角 [▶| Google Play] 略過膠囊按鈕極速專責偵測器】")
    idx_4aa = src.index("// 4AA. 【已獲取獎勵膠囊藥丸 [Reward granted ✕] 秒速點擊】")
    code_4b2 = src[idx_4b2:idx_4aa]

    # 1. 必須移除 elapsedSec >= 4，改為第 1 秒起秒點
    assert "elapsedSec >= 1" in code_4b2
    assert "elapsedSec >= 4" not in code_4b2

    # 2. 必須放寬允許點擊次數至 4 次，並設置 2500ms 節流
    assert "googlePlayPillClickCount < 4" in code_4b2
    assert "nowMsForPill - lastGooglePlayPillClickTimeMs >= 2500L" in code_4b2

    # 3. 點擊略過膠囊不得加入黑名單 (allowBlacklist = false)
    assert "recordAndClick(service, topLeftGooglePlayPill, allowBlacklist = false)" in code_4b2
    assert 'return AdStepResult.ClickedCloseOrSkip("左上角[▶| Google Play]略過鈕", topLeftGooglePlayPill)' in code_4b2


# ---------------------------------------------------------------------------
# 2. 8B 滑動螢幕重新喚醒機制 (Reveal Swipe)
# ---------------------------------------------------------------------------

def test_reveal_swipe_mechanism_in_closer():
    src = closer_src()
    assert "// 8B. 【試玩廣告控制項隱藏之滑動螢幕重新喚醒機制 (Reveal Swipe)】" in src
    idx_8b = src.index("// 8B. 【試玩廣告控制項隱藏之滑動螢幕重新喚醒機制 (Reveal Swipe)】")
    idx_9 = src.index("// 9. 防卡死 Watchdog：若單支廣告超過最大等待秒數")
    code_8b = src[idx_8b:idx_9]

    # 檢查觸發條件
    assert "elapsedSec >= 3" in code_8b
    assert "remainingSec == null" in code_8b
    assert "revealSwipeCount < 4" in code_8b
    assert "now - lastRevealSwipeTimeMs >= 3500L" in code_8b
    assert "now - lastGooglePlayPillClickTimeMs >= 2500L" in code_8b
    assert "!isOutsideGame" in code_8b

    # 檢查試玩關鍵字特徵
    assert "立即遊玩" in code_8b
    assert "Level" in code_8b
    assert "港口" in code_8b

    # 檢查垂直滑動派發座標 (中央安全區域)
    assert "service.performSwipe(" in code_8b
    assert "startX = sw * 0.50f" in code_8b
    assert "startY = sh * 0.65f" in code_8b
    assert "endX = sw * 0.50f" in code_8b
    assert "endY = sh * 0.45f" in code_8b
    assert "durationMs = 180L" in code_8b

    # 檢查回傳 SwipedToReveal
    assert "return AdStepResult.SwipedToReveal(revealSwipeCount, elapsedSec)" in code_8b


# ---------------------------------------------------------------------------
# 3. AdStepResult 與 AutomationEngine 整合
# ---------------------------------------------------------------------------

def test_ad_step_result_sealed_class_and_engine_handler():
    c_src = closer_src()
    assert "data class SwipedToReveal(val attempt: Int, val elapsedSec: Int) : AdStepResult()" in c_src

    e_src = engine_src()
    assert "is AdStepResult.SwipedToReveal -> {" in e_src
    assert 'appendLog("[廣告] 偵測到試玩廣告按鈕隱藏，執行滑動螢幕喚醒按鈕 (嘗試: ${res.attempt}/4)")' in e_src
    assert 'updateDetail(' in e_src


# ---------------------------------------------------------------------------
# 4. 單次廣告生命週期重置
# ---------------------------------------------------------------------------

def test_reveal_swipe_counter_reset():
    src = closer_src()
    assert "private var revealSwipeCount: Int = 0" in src
    idx_enter = src.index("fun onEnterAdMode(preserveRedirectState: Boolean = false) {")
    idx_reset = src.index("fun resetClickBlacklist() {", idx_enter)
    enter_code = src[idx_enter:idx_reset]

    assert "lastRevealSwipeTimeMs = 0L" in enter_code
    assert "revealSwipeCount = 0" in enter_code


# ---------------------------------------------------------------------------
# 5. 版本號升級驗證 (106 / 1.15.73)
# ---------------------------------------------------------------------------

def test_version_bump_v1_15_73():
    b_src = build_src()
    assert "versionCode = 106" in b_src
    assert 'versionName = "1.15.73"' in b_src


# ---------------------------------------------------------------------------
# 6. 0 Emoji 審計
# ---------------------------------------------------------------------------

def test_zero_emoji_in_source_code_v1_15_73():
    emoji_pattern = re.compile(
        r"[\U0001F600-\U0001F64F"  # emoticons
        r"\U0001F300-\U0001F5FF"  # symbols & pictographs
        r"\U0001F680-\U0001F6FF"  # transport & map
        r"\U0001F1E0-\U0001F1FF"  # flags
        r"\U0001F900-\U0001F9FF"
        r"\U0001FA70-\U0001FAFF]"
    )
    for p in [SRC / "engine/AdCloserHandler.kt", SRC / "engine/AutomationEngine.kt", ROOT / "app/build.gradle.kts"]:
        text = p.read_text(encoding="utf-8")
        matches = emoji_pattern.findall(text)
        assert not matches, f"Found emoji in {p.name}: {matches}"
