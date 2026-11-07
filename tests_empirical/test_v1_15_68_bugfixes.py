import os
import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def read_src(rel_path: str) -> str:
    full_path = os.path.join(REPO_ROOT, rel_path)
    with open(full_path, "r", encoding="utf-8") as f:
        return f.read()


class TestV1_15_68Bugfixes:
    """驗證 v1.15.68 三大實機診斷 Bug 修復與版本升級"""

    def test_01_bingo_candy_reward_modal_close_and_claimed_get_protection(self):
        ocr_code = read_src("app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt")
        bingo_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/BingoTask.kt")
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")

        # 1. 支援「1 跳過廣告糖果到手！」中央彈窗標題且優先返回 OCR[關閉]
        assert 't.contains("糖果到手")' in ocr_code
        assert 't.contains("跳過廣告糖果")' in ocr_code
        assert "if (closeOcr != null && (hasRewardTitleOcr || isDimmedModal || hasPixelCloseButton))" in ocr_code
        # 2. Bingo 視窗前置檢查前景領獎彈窗
        assert "點擊賓果前景領獎彈窗" in eng_code
        # 3. 防止重複點擊已領取之綠旗黑字 Get (強制要求 redPoints.size >= 8)
        assert "if (colOcr != null && redPoints.size >= 8)" in bingo_code

    def test_02_daily_icons_tab_detection_no_false_game_tab_loop(self):
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")
        ocr_code = read_src("app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt")

        # 1. ensureOnTabWithDailyIcons 直接放行日常圖示列與前三頁看板
        assert "hasDailyIconsOrNonGameBoard" in eng_code
        assert "if (hasDailyIconsOrNonGameBoard || activeTab == MainTab.MOVE || activeTab == MainTab.STEP || activeTab == MainTab.TIME)" in eng_code
        assert "if ((activeTab == MainTab.GAME || hasGamePageFeatures) && tabSwitchRetries < 2)" in eng_code
        # 2. 移除 ensureOnTabWithDailyIcons 中會誤判首頁/步數下方橫幅的 POINTSGAME
        ensure_block = eng_code.split("private suspend fun ensureOnTabWithDailyIcons")[1].split("internal suspend fun forceAdvanceToNextTab")[0]
        assert 't.contains("POINTSGAME"' not in ensure_block

    def test_03_trip_com_sponsored_top_left_x_and_fast_forward_skip_cap(self):
        ad_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt")
        tm_code = read_src("app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt")

        # 1. Trip.com 與 3M Sponsored 動態對齊左上角 X 並放行 x < 0.118w
        assert "topSponsoredCenterY" in ad_code
        assert "safeLeftHeaderBottom" in ad_code
        assert "if (x in (sw * 0.118f).toInt()..(sw * 0.850f).toInt() && y < (sh * 0.150f).toInt()) return true" in ad_code
        # 2. 限制右上角略過鈕點擊次數並排除系統狀態列與白底模式 B
        assert "fastForwardSkipClicks < 2" in ad_code
        assert "val topLimitY = max((sh * 0.042f).toInt(), 45)" in tm_code
        assert "if (ringMean > 200f" not in tm_code

    def test_04_version_bump_to_1_15_68(self):
        gradle_code = read_src("app/build.gradle.kts")
        workflow_code = read_src(".github/workflows/build-apk.yml")
        rel_notes = read_src("RELEASE_NOTES.md")

        assert 'versionCode = 101' in gradle_code
        assert 'versionName = "1.15.68"' in gradle_code
        assert "v1.15.68" in workflow_code
        assert "v1.15.68" in rel_notes
