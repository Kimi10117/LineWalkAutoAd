import re
from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_56_bingo_cooling_down_ast_and_logic():
    """Verify isBingoCoolingDown detects cooldown text while findOpenCellButton allows up to 5 attempts."""
    bingo_code = (SRC_DIR / "tasks/BingoTask.kt").read_text(encoding="utf-8")
    assert "fun isBingoCoolingDown(ocrItems: List<OcrItem>): Boolean" in bingo_code
    assert "距離下次" in bingo_code
    assert "抽選" in bingo_code
    assert "if (isBingoCoolingDown(ocrItems)) return null" not in bingo_code


def test_v1_15_56_bingo_retry_count_persistence():
    """Verify bingoOpenCellRetryCount is NEVER reset to 0 in transitionTo(EngineState.AD_WATCHING)."""
    engine_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    
    # 確保 transitionTo 內絕無重置 bingoOpenCellRetryCount
    trans_match = re.search(r'private fun transitionTo\(newState: EngineState[^{]*\{([^}]*)\}', engine_code)
    assert trans_match is not None
    trans_body = trans_match.group(1)
    assert "bingoOpenCellRetryCount = 0" not in trans_body


def test_v1_15_56_bingo_exit_on_limit_or_cooldown():
    """Verify handleBingoDialog exits immediately when 5 open attempts reached with no Get."""
    engine_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "openCellBtn != null && bingoOpenCellRetryCount < 5" in engine_code
    assert "bingoOpenCellRetryCount >= 5" in engine_code
    assert "currentPhase = PatrolPhase.LOTTERY" in engine_code


def test_v1_15_56_in_house_promo_main_screen_immunity():
    """Verify isLineInHousePromoAd is strictly vetoed on main screen features and requires invite context."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert "今天的步數" in ocr_code
    assert "距離下次累積還有" in ocr_code
    assert "hasMainScreenMarkers" in ocr_code
    # 確保通用詞快來領取、都能獲得等獨立行已被移除
    assert '                t.contains("快來領取") ||' not in ocr_code
    assert 't.contains("都能獲得")' not in ocr_code


def test_v1_15_56_ad_countdown_long_period_timer_guard():
    """Verify detectAdCountdownSeconds skips pure digits when long-period game timers exist."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert "hasLongPeriodTimer" in ocr_code
    assert "距離下次" in ocr_code
    assert "!hasLongPeriodTimer" in ocr_code


def test_v1_15_56_floating_overlay_status_title():
    """Verify FloatingOverlayService shows ad monitor ONLY when state == AD_WATCHING."""
    overlay_code = (SRC_DIR / "ui/FloatingOverlayService.kt").read_text(encoding="utf-8")
    assert "status.state == com.linewalk.autoad.engine.EngineState.AD_WATCHING -> {" in overlay_code
    assert "status.state == com.linewalk.autoad.engine.EngineState.AD_WATCHING || safeDetail.contains(\"廣告\")" not in overlay_code


def test_v1_15_56_version_bump():
    """Verify version bumped to v1.15.56 (versionCode 89) and GitHub workflow updated."""
    gradle_code = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'versionCode = 89' in gradle_code
    assert 'versionName = "1.15.56"' in gradle_code

    workflow_code = Path(".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert 'v1.15.56' in workflow_code

