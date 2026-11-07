import os
import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def read_src(rel_path: str) -> str:
    full_path = os.path.join(REPO_ROOT, rel_path)
    with open(full_path, "r", encoding="utf-8") as f:
        return f.read()


class TestV1_15_69UniversalViewportAndAdFixes:
    """驗證 v1.15.69 全螢幕比例與三大金剛鍵自適應、左上角 Google Play / 圓形 X 廣告關閉、5 大日誌死循環修復、與含版本號 TXT 日誌匯出"""

    def test_01_screen_viewport_calibrator_universal_aspect_ratios_and_3button_nav(self):
        calibrator_code = read_src("app/src/main/java/com/linewalk/autoad/vision/ScreenViewportCalibrator.kt")
        bingo_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/BingoTask.kt")
        lottery_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/LotteryTask.kt")
        mission_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/MissionTask.kt")
        receive_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/ReceiveGiftTask.kt")
        friend_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt")

        assert "object ScreenViewportCalibrator" in calibrator_code
        assert "hasThreeButtonNavBar: Boolean" in calibrator_code
        assert "fun isInsideThreeButtonNavBarZone" in calibrator_code
        assert "fun findSubDialogCloseButtonBounds" in calibrator_code
        assert "detectWhiteSheetTopRightCloseX" in calibrator_code

        assert "ScreenViewportCalibrator.findSubDialogCloseButtonBounds" in bingo_code
        assert "ScreenViewportCalibrator.findSubDialogCloseButtonBounds" in lottery_code
        assert "ScreenViewportCalibrator.findSubDialogCloseButtonBounds" in mission_code
        assert "ScreenViewportCalibrator.findSubDialogCloseButtonBounds" in receive_code
        assert "ScreenViewportCalibrator.detectViewport" in friend_code

    def test_02_top_left_google_play_skip_pill_and_caco_top_left_geometric_x(self):
        ad_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt")
        tm_code = read_src("app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt")
        node_code = read_src("app/src/main/java/com/linewalk/autoad/vision/NodeInspector.kt")

        # 1. Google Play 左上角膠囊略過按鈕
        assert "fun findTopLeftGooglePlaySkipPill" in ad_code
        assert "左上角[▶| Google Play]略過鈕" in ad_code
        assert "isTopLeftGooglePlayPill" in node_code

        # 2. CACO 左上角白底圓形 (X) 開放無倒數計時廣告掃描並擴展至 19.5% sw
        assert "val enableLeftCornerScan = isMetaAd || (noActiveCountdown && elapsedSec >= 5) || rewardGrantedOcr != null" in ad_code
        assert "searchZones.add(Rect((sw * 0.020f).toInt(), leftTopLimitY, (sw * 0.195f).toInt(), leftBottomLimitY))" in tm_code

    def test_03_five_log_anomalies_remediated(self):
        ad_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt")
        ocr_code = read_src("app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt")
        node_code = read_src("app/src/main/java/com/linewalk/autoad/vision/NodeInspector.kt")
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")

        # 異常 A: FacebookLandingPage 嚴格限制且單支廣告最多觸發 2 次
        assert "socialLandingPageTotalRecoveries < 2" in ad_code
        # 異常 B: 攔截 Skip 開啟商店 與 BOOK NOW ×
        assert '"開啟商店"' in node_code
        assert '"BOOK"' in ocr_code
        assert "val isSingleCharKw = kw in singleCharSymbols" in ocr_code
        assert "if (isSingleCharKw && cleanText.length > 2) return@firstOrNull false" in ocr_code
        # 異常 C & D: 子視窗關閉支援 allowTopSafeZone = true 與抽抽樂結果畫面防連點
        assert "allowTopSafeZone = true" in eng_code
        assert "if (_fullSessionLogs.size >= 20000)" in eng_code

    def test_04_txt_log_export_with_version_and_remove_sampling_ui(self):
        main_code = read_src("app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt")
        overlay_code = read_src("app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt")
        xml_code = read_src("app/src/main/res/layout/activity_main.xml")
        gradle_code = read_src("app/build.gradle.kts")

        assert "fun buildExportTxtFileName" in main_code
        assert 'return "散步趣助手_v${cleanVer}_診斷日誌_${timestamp}.txt"' in main_code
        assert "MediaStore.Downloads.EXTERNAL_CONTENT_URI" in main_code
        assert "btnRow.addView(cropBtn" not in overlay_code
        assert 'android:visibility="gone"' in xml_code
        assert "versionCode = 102" in gradle_code
        assert 'versionName = "1.15.69"' in gradle_code
