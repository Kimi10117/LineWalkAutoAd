from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_58_lottery_win_result_screen_strict_veto():
    """Verify isLotteryWinResultScreen vetoes main lottery panel promo banner ('完成任務 即可獲得 大量金幣!!')."""
    lt_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")
    assert "fun isLotteryWinResultScreen(ocrItems: List<OcrItem>, sh: Int): Boolean" in lt_code
    assert 't.contains("完成任務")' in lt_code
    assert 't.contains("即可獲得")' in lt_code
    assert 't.contains("大量金幣")' in lt_code
    assert 't.contains("查看獎項")' in lt_code
    # Ensure bare t.contains("獲得") is removed from Strategy B in findLotteryRewardAdButton
    assert '(t.contains("x1", ignoreCase = true) || t.contains("1張") || t.contains("獲得"))' not in lt_code


def test_v1_15_58_both_draws_unavailable_immediate_close():
    """Verify areBothDrawsUnavailable allows immediate exit when both 50-draw and Free-draw are gray."""
    lt_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")
    assert "fun areBothDrawsUnavailable(" in lt_code
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "val hasLotteryResultContext = lotteryTask.isLotteryWinResultScreen(ocrItems, sh)" in ae_code
    assert "val bothGray = lotteryTask.areBothDrawsUnavailable(screenBitmap, ocrItems, templateMatcher)" in ae_code
    assert "if (!bothGray && currentPhaseEmptyTicks < 2)" in ae_code


def test_v1_15_58_center_modal_close_excludes_lottery_main_panel():
    """Verify findCenterRewardModalCloseButton excludes Lottery main panel ('最多 5000 枚金幣')."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert '!t.contains("最多")' in ocr_code
    assert '!t.contains("完成任務")' in ocr_code


def test_v1_15_58_version_bump():
    """Verify versionCode = 91 and versionName = 1.15.58."""
    gradle_code = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    assert "versionCode = 91" in gradle_code
    assert 'versionName = "1.15.58"' in gradle_code
    workflow_code = Path(".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert "v1.15.58" in workflow_code
