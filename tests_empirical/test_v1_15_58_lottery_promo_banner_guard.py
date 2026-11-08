from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_58_lottery_win_result_screen_strict_veto():
    """Verify isLotteryWinResultScreen checks main panel features without vetoing 8-prize dialogs."""
    lt_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")
    assert "fun isLotteryWinResultScreen(" in lt_code  # v1.15.70: 新增 screenBitmap 參數
    # v1.15.71: 移除「完成任務/即可獲得/大量金幣」一票否決，防止抽抽樂 8 獎彈窗中間配圖被誤判否決
    assert 't.contains("查看獎項")' in lt_code
    # Ensure bare t.contains("獲得") is removed from Strategy B in findLotteryRewardAdButton
    assert '(t.contains("x1", ignoreCase = true) || t.contains("1張") || t.contains("獲得"))' not in lt_code


def test_v1_15_58_both_draws_unavailable_immediate_close():
    """Verify areBothDrawsUnavailable allows immediate exit when both 50-draw and Free-draw are gray."""
    lt_code = (SRC_DIR / "tasks/LotteryTask.kt").read_text(encoding="utf-8")
    assert "fun areBothDrawsUnavailable(" in lt_code
    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "val hasLotteryResultContext = lotteryTask.isLotteryWinResultScreen(ocrItems, sh, screenBitmap)" in ae_code
    assert "val bothGray = lotteryTask.areBothDrawsUnavailable(screenBitmap, ocrItems, templateMatcher)" in ae_code
    assert "if (currentPhaseEmptyTicks < 3)" in ae_code  # v1.15.70: 至少 3 個空 Tick 才判定完成


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
