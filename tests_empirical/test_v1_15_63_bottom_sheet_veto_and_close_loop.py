from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_63_bottom_reward_sheet_vetoed_from_center_modal():
    """Verify that bottom reward sheet with '取消' or '跳過廣告' / '紅利' is strictly vetoed from center reward modal."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert "val isBottomRewardSheet = items.any" in ocr_code
    assert 't == "取消" || t.contains("取消")' in ocr_code
    assert 't.contains("跳過廣告") || t.contains("紅利")' in ocr_code

    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "isBottomRewardSheetVisible" in ae_code
    assert "!isBottomRewardSheetVisible" in ae_code


def test_v1_15_63_center_modal_requires_header_white_ratio():
    """Verify isDimmedCenterModalOpen strictly requires headerWhiteRatio > 0.25f to prevent bottom sheet misclassification."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert "headerWhiteRatio > 0.25f" in ocr_code


def test_v1_15_63_version_bump():
    """Verify versionCode = 96 and versionName = 1.15.63."""
    gradle_code = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    assert "versionCode = 96" in gradle_code
    assert 'versionName = "1.15.63"' in gradle_code
    workflow_code = Path(".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert "v1.15.63" in workflow_code
