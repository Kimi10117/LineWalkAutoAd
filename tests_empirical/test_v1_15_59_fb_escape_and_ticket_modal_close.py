from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_59_external_social_app_package_guard():
    """Verify AppConfig defines isExternalSocialAppPackage and AutomationEngine intercepts it immediately."""
    app_cfg = (SRC_DIR / "config/AppConfig.kt").read_text(encoding="utf-8")
    assert "fun isExternalSocialAppPackage(pkg: String?): Boolean" in app_cfg
    assert '"com.facebook.katana"' in app_cfg

    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "AppConfig.isExternalSocialAppPackage(fgPkg)" in ae_code
    assert "service.navigateBackFromExternalSocialApp" in ae_code
    assert "service.bringAppToFront(targetPkg)" in ae_code

    service_code = (SRC_DIR / "service/AutoAdAccessibilityService.kt").read_text(encoding="utf-8")
    assert "fun navigateBackFromExternalSocialApp" in service_code


def test_v1_15_59_ticket_15_not_excluded_by_lottery_guard():
    """Verify findCenterRewardModalCloseButton does not mistake '15張' in '獲得15張票券！' as lottery '5張'."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert "hasExplicitTicketOrCoinModalTitle" in ocr_code
    assert '!hasExplicitTicketOrCoinModalTitle && items.any' in ocr_code
    assert 't.contains("5張抽")' in ocr_code
    assert 'hasPixelCloseButton' in ocr_code
    assert 'findCenterRewardModalCloseButton(screenBitmap, items) != null || isDimmedCenterModalOpen(screenBitmap)' in ocr_code


def test_v1_15_60_version_bump():
    """Verify versionCode = 93 and versionName = 1.15.60."""
    gradle_code = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    assert "versionCode = 93" in gradle_code
    assert 'versionName = "1.15.60"' in gradle_code
    workflow_code = Path(".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert "v1.15.60" in workflow_code
