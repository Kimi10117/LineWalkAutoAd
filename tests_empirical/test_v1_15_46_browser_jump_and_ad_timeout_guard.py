import os
from pathlib import Path
import re
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"


def test_popup_action_target_excludes_lottery_prize_list_header():
    """Verify that prize list table header '】 觀看廣告抽獎獎勵(第三順位)' is strictly excluded."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    # Exclusions must include 順位, 抽獎, 一覽, 獎勵, 規則
    for kw in ["順位", "抽獎", "一覽", "獎勵", "規則"]:
        assert f't.contains("{kw}")' in ae_code, f"Candidate loop must exclude '{kw}'"

    # Positive coins or explicit skip required
    assert '(coins > 0 || t.contains("跳過廣告") || t.contains("免費"))' in ae_code

    # Sub-dialogs must be vetoed
    assert "lotteryTask.isLotteryDialogOpen(ocrItems, sh)" in ae_code
    assert "!hasBottomModalTitle && !hasBottomModalCancel" in ae_code


def test_handle_in_game_scan_step_order():
    """Verify sub-dialogs and friends page are handled BEFORE popupAction and promoSkip."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    idx_bingo = ae_code.find("bingoTask.isBingoDialogOpen(ocrItems, sh)")
    idx_lottery = ae_code.find("lotteryTask.isLotteryDialogOpen(ocrItems, sh)")
    idx_mission = ae_code.find("missionTask.isMissionDialogOpen(ocrItems, sh, screenBitmap)")
    idx_receive = ae_code.find("receiveGiftTask.isReceiveGiftDialogOpen(ocrItems, sh)")
    idx_friends = ae_code.find("friendGiftTask.isFriendsPage(ocrItems, sh)")
    idx_popup = ae_code.find("val popupAction = findPopupActionTarget(screenBitmap, ocrItems)")
    idx_cancel = ae_code.find("val cancelBtn = ocrRecognizer.findPopupCancelButton(screenBitmap, ocrItems, templateMatcher)")

    assert idx_bingo != -1 and idx_lottery != -1 and idx_mission != -1
    assert idx_receive != -1 and idx_friends != -1 and idx_popup != -1 and idx_cancel != -1

    assert idx_bingo < idx_popup, "Bingo dialog check must precede popupAction check"
    assert idx_lottery < idx_popup, "Lottery dialog check must precede popupAction check"
    assert idx_mission < idx_popup, "Mission dialog check must precede popupAction check"
    assert idx_receive < idx_popup, "Receive gift dialog check must precede popupAction check"
    assert idx_friends < idx_popup, "Friends page check must precede popupAction check"


def test_is_ad_loading_screen_excludes_game_modals():
    """Verify that OcrRecognizer.isAdLoadingScreen returns false for reward and lottery modals."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")

    assert "isGameModalOrResult" in ocr_code
    for modal_kw in ["中獎", "獲得票券", "獲得金幣", "每日賓果", "今日賓果", "抽抽樂", "分享給好友"]:
        assert f't.contains("{modal_kw}")' in ocr_code, f"isAdLoadingScreen must check '{modal_kw}'"


def test_external_app_guard_recovery():
    """Verify external app guard pulls LINE Walk to front and transitions to IN_GAME_SCAN."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")

    assert "service.bringAppToFront(targetPkg)" in ae_code
    assert 'transitionTo(EngineState.IN_GAME_SCAN, "已拉回散步趣，恢復遊戲巡邏...", "[巡邏] 巡邏畫面目標中...")' in ae_code
    assert 'com.android.chrome' in ae_code


def test_ad_closer_loading_ticks_and_in_game_protection():
    """Verify AdCloserHandler uses consecutive adLoadingTicks and avoids in-game back presses."""
    ad_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert "private var adLoadingTicks: Int = 0" in ad_code
    assert "!hasAdActuallyShown && ocrRecognizer.isAdLoadingScreen(ocrItems, screenBitmap)" in ad_code
    assert "adLoadingTicks < 12" in ad_code
    assert "!isGame && now - lastBackActionTimeMs >= 2500L" in ad_code
    assert "service.safeNavigateBack(allowInGame = false)" in ad_code
    assert "elapsedSec >= 28" in ad_code


def test_kotlin_source_invariants_v1_15_46():
    """Verify build.gradle.kts and AndroidManifest.xml invariants for v1.15.46."""
    gradle_code = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    manifest_code = (REPO_ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")

    assert 'versionName = "1.15.46"' in gradle_code or 'versionName = "1.15.47"' in gradle_code
    assert "versionCode = 79" in gradle_code or "versionCode = 80" in gradle_code
    assert '<package android:name="com.linecorp.LGWKTW" />' in manifest_code
    assert '<package android:name="jp.naver.line.android" />' in manifest_code
