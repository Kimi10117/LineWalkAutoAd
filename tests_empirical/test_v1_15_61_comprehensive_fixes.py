import pytest
from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_61_ad_error_dialog_recognition_and_main_screen_veto():
    """Verify OcrRecognizer defines findAdLoadErrorCloseButton and isLineWalkMainScreenVisible vetoes on ad error."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert "fun findAdLoadErrorCloseButton" in ocr_code
    assert '無法載入廣告' in ocr_code
    assert '請稍後再試' in ocr_code
    assert '錯誤彈窗綠色[關閉]鈕' in ocr_code

    # Check main screen veto
    assert "findAdLoadErrorCloseButton(screenBitmap, items) != null" in ocr_code


def test_v1_15_61_automation_engine_ad_error_recovery_and_tab_retry():
    """Verify AutomationEngine closes ad error dialog and resets emptyTicksOnCurrentTab so tab isn't skipped."""
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "ocrRecognizer.findAdLoadErrorCloseButton" in ae_code
    assert "confirmedEmptyTabs.remove(currentTab)" in ae_code
    assert '廣告載入失敗彈窗' in ae_code


def test_v1_15_61_bingo_cooldown_and_limit_immediate_exit():
    """Verify BingoTask defines isBingoCoolingDown and AutomationEngine exits immediately without waiting."""
    bingo_code = (SRC_DIR / "tasks/BingoTask.kt").read_text(encoding="utf-8")
    assert "fun isBingoCoolingDown" in bingo_code
    assert "距離下次抽選還有" in bingo_code

    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "val isCoolingDown = bingoTask.isBingoCoolingDown(ocrItems)" in ae_code
    assert "!isCoolingDown && bingoOpenCellRetryCount < 5" in ae_code


def test_v1_15_61_lottery_cooldown_and_facebook_escape():
    """Verify LotteryTask detects both draws in cooldown and AdCloserHandler intercepts Facebook landing page."""
    lottery_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")
    assert "hasLeftCooldown" in lottery_code
    assert "hasRightCooldown" in lottery_code

    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    assert "isSocialLandingPage" in closer_code
    assert "FacebookLandingPage" in closer_code
    assert "廣告載入失敗彈窗" in closer_code


def test_v1_15_61_version_bump():
    """Verify versionCode = 94 and versionName = 1.15.61."""
    gradle_code = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    assert "versionCode = 94" in gradle_code
    assert 'versionName = "1.15.61"' in gradle_code
    workflow_code = Path(".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert "v1.15.61" in workflow_code
