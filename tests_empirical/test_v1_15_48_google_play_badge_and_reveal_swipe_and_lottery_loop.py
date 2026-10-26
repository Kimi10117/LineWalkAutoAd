from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"


def test_google_play_badge_exclusion_and_meta_guard():
    """Verify that Google Play badge is never clicked as skip and excludes isMetaAd."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "isExplicitSkipGooglePlayPill" in closer_code
    assert "hasGooglePlayOnScreen" in closer_code
    assert "!hasGooglePlayOnScreen" in closer_code
    assert "val hasGooglePlayNear = ocrItems.any" in closer_code
    assert "!hasGooglePlayNear" in closer_code


def test_reveal_swipe_gesture_contract():
    """Verify that keep-alive reveal swipe is dispatched when controls auto-hide."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "private var lastRevealSwipeTimeMs: Long = 0L" in closer_code
    assert "now - lastRevealSwipeTimeMs >= 3500L" in closer_code
    assert "service.performSwipe" in closer_code
    assert "滑動喚醒手勢" in closer_code


def test_lottery_50_draw_attempts_limit_and_fallback():
    """Verify that 50-draw has an attempt limit and falls back to findFreeDrawOnly."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    lottery_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")

    assert "private var lotteryFiftyDrawAttempts = 0" in ae_code
    assert "lotteryFiftyDrawAttempts >= 2" in ae_code
    assert "lotteryTask.findFreeDrawOnly" in ae_code
    assert "fun findFreeDrawOnly(" in lottery_code


def test_ad_end_card_safe_back_key():
    """Verify that ad End Card triggers safeNavigateBack with allowInGame = true when elapsedSec >= 15."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "isEndCard && elapsedSec >= 15" in closer_code
    assert "service.safeNavigateBack(allowInGame = true)" in closer_code
    assert '"廣告結束卡片安全返回鍵"' in closer_code
