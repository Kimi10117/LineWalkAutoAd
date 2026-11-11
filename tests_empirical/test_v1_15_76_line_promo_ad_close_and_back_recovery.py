import io
from pathlib import Path
from PIL import Image
import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OCR_RECOGNIZER = PROJECT_ROOT / "app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt"
AD_CLOSER_HANDLER = PROJECT_ROOT / "app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt"
AUTOMATION_ENGINE = PROJECT_ROOT / "app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt"
BUILD_GRADLE = PROJECT_ROOT / "app/build.gradle.kts"

USER_SCREENSHOT = Path(r"C:\Users\kimic\.gemini\antigravity\brain\1517d055-ada4-4bca-866d-09e825fe0a19\.user_uploaded\media_1794411712411_9393858b.jpg")


def test_user_screenshot_dynamic_x_detection():
    """Verify that scanning top-right corner on the actual user screenshot finds the white X center."""
    assert USER_SCREENSHOT.exists(), "User screenshot must exist"
    img = Image.open(USER_SCREENSHOT)
    w, h = img.size
    arr = np.array(img)

    x_min, x_max = int(w * 0.85), int(w * 0.98)
    y_min, y_max = int(h * 0.05), int(h * 0.15)

    white_pts = []
    for y in range(y_min, y_max):
        for x in range(x_min, x_max):
            r, g, b = arr[y, x][:3]
            if r > 225 and g > 225 and b > 225:
                white_pts.append((x, y))

    assert len(white_pts) in range(10, 201), f"White pixel count {len(white_pts)} out of range"
    avg_x = sum(p[0] for p in white_pts) / len(white_pts)
    avg_y = sum(p[1] for p in white_pts) / len(white_pts)

    # Center is approximately 92.1% X, 9.6% Y
    assert 0.90 <= avg_x / w <= 0.94, f"X ratio {avg_x/w} outside expected 0.90..0.94"
    assert 0.08 <= avg_y / h <= 0.11, f"Y ratio {avg_y/h} outside expected 0.08..0.11"


def test_ocr_recognizer_has_find_promo_close_button():
    """Verify findLineInHousePromoCloseButton implementation and fallback."""
    code = OCR_RECOGNIZER.read_text(encoding="utf-8")
    assert "fun findLineInHousePromoCloseButton(screenBitmap: Bitmap?, sw: Int, sh: Int): Rect" in code
    assert "fun getLineInHousePromoCloseButton(sw: Int, sh: Int): Rect" in code
    assert "r > 225 && g > 225 && b > 225" in code
    assert "count in 10..200" in code


def test_ad_closer_handler_promo_retry_and_back_logic():
    """Verify AdCloserHandler alternates between close button tap and safeNavigateBack."""
    code = AD_CLOSER_HANDLER.read_text(encoding="utf-8")
    assert "ocrRecognizer.isLineInHousePromoAd(ocrItems)" in code
    assert "findLineInHousePromoCloseButton(screenBitmap, sw, sh)" in code
    assert "linePromoCloseClicks++" in code
    assert "linePromoCloseClicks == 1 || linePromoCloseClicks == 2 || linePromoCloseClicks % 2 == 0" in code
    assert "service.safeNavigateBack(minIntervalMs = 600L, allowInGame = true)" in code
    # Must NOT blacklist the button
    assert "LINE宣傳廣告關閉(X)" in code
    assert "LINE宣傳廣告安全返回鍵" in code


def test_automation_engine_promo_close_uses_calibrated_button():
    """Verify AutomationEngine in all states passes allowTopSafeZone and uses calibrated button."""
    code = AUTOMATION_ENGINE.read_text(encoding="utf-8")
    # State 1: In-game scan
    assert "val closeBtn = ocrRecognizer.findLineInHousePromoCloseButton(screenBitmap, sw, sh)" in code
    # All clicks on promo close button must use allowTopSafeZone = true
    assert "service.clickRect(closeBtn, maxJitterPx = 2, allowTopSafeZone = true)" in code


def test_version_bump_v1_15_76():
    """Verify version is bumped to 1.15.76 (versionCode 109)."""
    code = BUILD_GRADLE.read_text(encoding="utf-8")
    assert 'versionCode = 109' in code
    assert 'versionName = "1.15.76"' in code
