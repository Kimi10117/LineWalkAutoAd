import pytest
import os
import re

def test_v1_15_64_version_bumped():
    gradle_path = os.path.join(os.path.dirname(__file__), "..", "app", "build.gradle.kts")
    with open(gradle_path, "r", encoding="utf-8") as f:
        code = f.read()
    assert 'versionCode = 97' in code, "versionCode must be 97 in v1.15.64"
    assert 'versionName = "1.15.64"' in code, "versionName must be 1.15.64"

def test_v1_15_64_game_tab_and_lottery_panel_veto():
    ocr_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad", "vision", "OcrRecognizer.kt")
    with open(ocr_path, "r", encoding="utf-8") as f:
        code = f.read()
    assert "isGameTabScreen" in code, "Must have isGameTabScreen veto in findCenterRewardModalCloseButton"
    assert "秘寶挖挖哇" in code and "數獨達人" in code and "接龍高手" in code
    assert "isLotteryPanelOrResult" in code, "Must have isLotteryPanelOrResult veto in findCenterRewardModalCloseButton"
    assert "大量金幣" in code and "完成任務" in code and "即可獲得" in code
    assert "hasRewardTitleOcr && (isDimmedModal || hasPixelCloseButton" in code, "Fallback must require hasRewardTitleOcr!"

def test_v1_15_64_ad_error_modal_green_features():
    ocr_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad", "vision", "OcrRecognizer.kt")
    with open(ocr_path, "r", encoding="utf-8") as f:
        code = f.read()
    assert "hasGreenModalPixelFeatures" in code, "Must support pixel-based green bear card and button detection without mandatory OCR text"
    assert "greenButtonPts" in code and "greenCardPts" in code

def test_v1_15_64_social_landing_page_fast_return():
    closer_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AdCloserHandler.kt")
    with open(closer_path, "r", encoding="utf-8") as f:
        code = f.read()
    assert "isSocialAppPkg" in code, "Must check for social app packages like facebook/instagram"
    assert "isSocialLandingPage" in code
    assert "bringAppToFront" in code and "socialLandingPageTicks >= 2" in code

def test_v1_15_64_all_screen_modal_close_adaptation():
    bingo_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad", "tasks", "BingoTask.kt")
    lottery_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad", "tasks", "LotteryTask.kt")
    with open(bingo_path, "r", encoding="utf-8") as f:
        b_code = f.read()
    with open(lottery_path, "r", encoding="utf-8") as f:
        l_code = f.read()
    assert "aspect >= 2.15f" in b_code, "BingoTask must adapt for 22:9 foldable aspect ratio"
    assert "aspect >= 2.15f" in l_code, "LotteryTask must adapt for 22:9 foldable aspect ratio"

def test_v1_15_64_circuit_breaker_and_phase_barrier():
    engine_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad", "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        code = f.read()
    assert "checkClickCircuitBreaker" in code, "Must implement click circuit breaker"
    assert "Phase Barrier" in code, "Must implement phase barrier before switching away from main tabs"
    assert "sameTargetClickCount >= 3" in code, "Must trigger circuit breaker after 3 unhandled consecutive clicks"
