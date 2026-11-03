from pathlib import Path

SRC_DIR = Path("app/src/main/java/com/linewalk/autoad")


def test_v1_15_62_meta_audience_network_ad_not_killed_by_social_guard():
    """Verify com.facebook.katana is recognized as an ad package and NOT an external social app package."""
    app_cfg = (SRC_DIR / "config/AppConfig.kt").read_text(encoding="utf-8")
    assert '"com.facebook.katana"' in app_cfg
    assert 'fun isExternalSocialAppPackage(pkg: String?): Boolean' in app_cfg
    assert 'if (isAdPackage(pkg)) return false' in app_cfg

    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    idx_ad = ae_code.find("if (AppConfig.isAdPackage(fgPkg))")
    idx_social = ae_code.find("else if (AppConfig.isExternalSocialAppPackage(fgPkg))")
    assert idx_ad != -1, "isAdPackage check missing in AutomationEngine"
    assert idx_social != -1, "isExternalSocialAppPackage check missing in AutomationEngine"
    assert idx_ad < idx_social, "isAdPackage MUST precede isExternalSocialAppPackage so Meta ads are never killed prematurely"


def test_v1_15_62_facebook_landing_page_safe_recovery():
    """Verify AdCloserHandler detects Facebook social landing page and safely recovers."""
    ach_code = (SRC_DIR / "engine/AdCloserHandler.kt").read_text(encoding="utf-8")
    assert "val isSocialLandingPage = ocrItems.any" in ach_code
    assert 't.contains("SOGO") || t.contains("Facebook") || t.contains("FB")' in ach_code
    assert "socialLandingPageTicks++" in ach_code
    assert 'service.bringAppToFront("com.linecorp.LGWKTW")' in ach_code


def test_v1_15_62_bingo_cooldown_detection_and_safe_close():
    """Verify BingoTask comprehensive cooldown detection and close button fallback."""
    bt_code = (SRC_DIR / "tasks/BingoTask.kt").read_text(encoding="utf-8")
    assert 't.contains("距離下次")' in bt_code
    assert 't.contains("30分鐘啟動")' in bt_code
    assert "fun findCloseButton(ocrItems: List<OcrItem>, sw: Int, sh: Int): Rect?" in bt_code

    ae_code = (SRC_DIR / "engine/AutomationEngine.kt").read_text(encoding="utf-8")
    assert "bingoDialogCloseAttempts" in ae_code
    assert "service.safeNavigateBack(allowInGame = true)" in ae_code


def test_v1_15_62_dimmed_center_modal_side_edge_detection():
    """Verify OcrRecognizer evaluates dimmed game canvas side edge lum rather than bottom nav bar."""
    ocr_code = (SRC_DIR / "vision/OcrRecognizer.kt").read_text(encoding="utf-8")
    assert "edgeLumSum" in ocr_code
    assert "edgeMean >= 180f" in ocr_code
    assert "footerWhiteRatio > 0.55f" in ocr_code


def test_v1_15_62_version_bump():
    """Verify versionCode = 95 and versionName = 1.15.62."""
    gradle_code = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    assert "versionCode = 95" in gradle_code
    assert 'versionName = "1.15.62"' in gradle_code
    workflow_code = Path(".github/workflows/build-apk.yml").read_text(encoding="utf-8")
    assert "v1.15.62" in workflow_code
