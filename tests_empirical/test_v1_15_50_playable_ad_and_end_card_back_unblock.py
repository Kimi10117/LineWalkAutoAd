from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "app/src/main/java/com/linewalk/autoad"


def test_end_card_matches_shopback_fruit_spinner():
    """Verify isEndCard logic matches ShopBack Fruit Spinner playable end card keywords."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert 't.contains("立即下載")' in closer_code
    assert 't.contains("下載")' in closer_code
    assert 't.contains("ShopBack", ignoreCase = true)' in closer_code
    assert 't.contains("連勝")' in closer_code
    assert 't.contains("金幣")' in closer_code
    assert 'service.safeNavigateBack(allowInGame = true)' in closer_code
    assert '"廣告結束卡片安全返回鍵"' in closer_code


def test_end_card_and_late_timeout_allow_in_game_back():
    """Verify that end card and late timeout back actions pass allowInGame = true."""
    closer_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")

    assert 'service.safeNavigateBack(allowInGame = true)' in closer_code
    assert 'service.safeNavigateBack(allowInGame = false)' in closer_code


def test_service_safe_navigate_back_in_game_guard():
    """Verify AutoAdAccessibilityService safeNavigateBack strictly guards game package unless allowInGame = true."""
    service_code = (SRC_DIR / "service/AutoAdAccessibilityService.kt").read_text(encoding="utf-8")

    assert 'if (com.linewalk.autoad.config.AppConfig.isAdPackage(fg))' in service_code
    assert '!allowInGame && (' in service_code


def test_node_inspector_relaxes_corner_exclusion():
    """Verify NodeInspector protects central notifications but allows corner nodes at y >= 4.0%."""
    ni_code = (SRC_DIR / "vision/NodeInspector.kt").read_text(encoding="utf-8")

    assert 'val inTopNoticeZone = cx in (screenWidth * 0.18f).toInt()..(screenWidth * 0.82f).toInt()' in ni_code
    assert 'if (inTopNoticeZone || cy < max(screenHeight * 0.040f, 40f)) continue' in ni_code


def test_version_bump_v1_15_50():
    """Verify version 1.15.50/1.15.51 and versionCode 83/84."""
    gradle_code = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    workflow_code = (REPO_ROOT / ".github/workflows/build-apk.yml").read_text(encoding="utf-8")

    assert any(c in gradle_code for c in ['versionCode = 83', 'versionCode = 84'])
    assert any(v in gradle_code for v in ['versionName = "1.15.50"', 'versionName = "1.15.51"'])
    assert any(w in workflow_code for w in ['v1.15.50', 'v1.15.51'])
