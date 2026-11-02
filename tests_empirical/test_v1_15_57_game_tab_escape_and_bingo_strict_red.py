from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_57_bingo_zero_bingo_veto_and_strict_red_capsule():
    """Verify 0 BINGO veto and strict red capsule threshold prevent candy/coin icon false positives."""
    bingo_code = (SRC_DIR / "tasks/BingoTask.kt").read_text(encoding="utf-8")
    assert 't == "0BINGO" || t.startsWith("0BINGO")' in bingo_code
    assert "r > 195 && g < 110 && b < 110 && (r - g) > 90 && (r - b) > 90" in bingo_code
    assert "if (redPoints.size >= 25)" in bingo_code


def test_v1_15_57_bingo_anti_deadlock_cap_on_step3():
    """Verify handleBingoDialog caps step 3 anti-early-exit wait to colRetries <= 2 to prevent infinite loop."""
    engine_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "if (colIdx in claimedBingoColumns && colRetries <= 2)" in engine_code
    assert "bingoColumnRetryCount[colIdx] = colRetries" in engine_code


def test_v1_15_57_game_tab_escape_for_daily_sub_dialogs():
    """Verify PatrolPhase.BINGO, LOTTERY, and MISSION escape from MainTab.GAME to MainTab.MOVE before clicking daily icons."""
    engine_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "private suspend fun ensureOnTabWithDailyIcons(" in engine_code
    assert "if (activeTab == MainTab.GAME || hasGamePageFeatures)" in engine_code
    assert "if (!ensureOnTabWithDailyIcons(service, screenBitmap, ocrItems, sw, sh)) return" in engine_code
    assert "subDialogOpenAttempts" in engine_code


def test_v1_15_57_candy_and_coin_pixels_not_detected_as_red_get():
    """Verify beige/pink candy and coin pixels (RGB 250, 209, 165) are rejected by strict red condition."""
    sample_unclaimed_pixels = [
        (250, 209, 165),
        (253, 218, 154),
        (248, 203, 162),
        (245, 195, 210),
    ]
    for r, g, b in sample_unclaimed_pixels:
        is_red = r > 195 and g < 110 and b < 110 and (r - g) > 90 and (r - b) > 90
        assert not is_red, f"Unclaimed icon pixel {(r, g, b)} should not match strict red Get button"

    # True red [Get] button pixel (#FF3B30 / #E5252A)
    true_red_pixels = [
        (255, 59, 48),
        (229, 37, 42),
        (240, 50, 50),
    ]
    for r, g, b in true_red_pixels:
        is_red = r > 195 and g < 110 and b < 110 and (r - g) > 90 and (r - b) > 90
        assert is_red, f"True red Get pixel {(r, g, b)} must match strict red Get button"


def test_v1_15_57_version_bump():
    """Verify versionCode = 90 and versionName = 1.15.57."""
    gradle_code = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    assert "versionCode = 90" in gradle_code
    assert 'versionName = "1.15.57"' in gradle_code
    workflow_code = Path(".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert "v1.15.57" in workflow_code
