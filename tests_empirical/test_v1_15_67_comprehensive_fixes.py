import os
import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "java", "com", "linewalk", "autoad")


def read_src(rel_path: str) -> str:
    full_path = os.path.join(REPO_ROOT, rel_path)
    with open(full_path, "r", encoding="utf-8") as f:
        return f.read()


class TestV1_15_67ComprehensiveFixes:
    """驗證 v1.15.67 八大修復與功能需求之實作正確性"""

    def test_01_ad_load_error_strict_text_requirement_and_invite_10000_promo(self):
        ocr_code = read_src("app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt")
        # 1. findAdLoadErrorCloseButton 必須強制要求 hasErrorText，且不得包含 else if (hasGreenModalPixelFeatures) 盲猜分支
        assert "if (!hasErrorText) {" in ocr_code
        assert "else if (hasGreenModalPixelFeatures)" not in ocr_code
        assert "isNonErrorScreen" in ocr_code
        # 2. 支援邀請好友 10000 金幣全螢幕宣傳頁
        assert "邀請好友" in ocr_code
        assert "10000" in ocr_code
        assert "快來領取" in ocr_code

    def test_02_independent_friend_gift_and_receive_gift_toggles(self):
        cfg_code = read_src("app/src/main/java/com/linewalk/autoad/config/AppConfig.kt")
        xml_code = read_src("app/src/main/res/layout/activity_main.xml")
        main_code = read_src("app/src/main/java/com/linewalk/autoad/ui/MainActivity.kt")
        recv_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/ReceiveGiftTask.kt")
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")

        assert "var enableReceiveGiftTask: Boolean" in cfg_code
        assert "cbReceiveGiftTask" in xml_code
        assert "cbFriendGiftTask" in xml_code
        assert "binding.cbReceiveGiftTask" in main_code
        assert "config.enableReceiveGiftTask" in recv_code
        assert "PatrolPhase.RECEIVE_GIFT -> config.enableReceiveGiftTask && !isReceiveGiftCompleted" in eng_code

    def test_03_single_tab_exhaustion_and_time_tab_bonus_berry_first(self):
        game_task_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/GameTask.kt")
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")

        # 單一分頁必須連續 3 次空 Tick 確認才換頁
        assert "if (emptyTicksOnCurrentTab < 3)" in eng_code
        # 時間分頁優先點擊紅利看廣告獲得一顆莓果
        assert "findBonusBerryAdButton" in game_task_code
        assert "hasTriedBonusBerryInCurrentStay" in game_task_code
        assert "看廣告獲莓果鈕" in game_task_code

    def test_04_strict_home_bottom_banner_protection(self):
        game_task_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/GameTask.kt")
        ocr_code = read_src("app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt")
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")

        # 主畫面獎勵按鈕搜尋高度限制在 0.25f..0.365f
        assert "RectF(0.15f, 0.25f, 0.85f, 0.365f)" in game_task_code
        assert "yEndRatio = 0.365f" in game_task_code
        # 硬性座標防護罩封鎖 y in 0.445f..0.895f
        assert "targetCyRatio in 0.445f..0.895f" in eng_code
        # 排除關鍵字包含酷澎與活動橫幅字眼
        for kw in ["酷澎", "Coupang", "原萃", "報名中", "立即下載", "瞭解詳情"]:
            assert kw in ocr_code

    def test_05_daily_bingo_get_button_and_popup_transition(self):
        bingo_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/BingoTask.kt")
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")

        assert "r > 185 && g < 128 && b < 135" in bingo_code
        assert "redPoints.size >= 18" in bingo_code
        assert "已點擊賓果連線獎勵" in eng_code

    def test_06_lottery_five_draw_third_priority(self):
        lottery_code = read_src("app/src/main/java/com/linewalk/autoad/tasks/LotteryTask.kt")
        eng_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt")

        assert "fun findFiveDrawOnly(" in lottery_code
        assert "Pair(\"5張抽一次\", fiveDrawOcr.bounds)" in lottery_code
        assert "lotteryFiveDrawAttempts" in eng_code

    def test_07_ad_skip_and_no_random_clicks_and_unskippable_ad_wait(self):
        ad_code = read_src("app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt")
        tm_code = read_src("app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt")

        # Sponsored 頂部白底橫幅左上角 X 支援與網址列封鎖
        assert "isCurrentAdTopSponsored" in ad_code
        assert "Sponsored頂部左上角X" in ad_code
        assert "leftTopLimitY = max((sh * 0.014f).toInt(), 18)" in tm_code
        # 嚴格過濾 >> 促銷文字
        assert "promoBaitWords" in ad_code
        # 同座標點擊 2 次即加入黑名單
        assert "if (allowBlacklist && nearbyClicks >= 2)" in ad_code
        # 不可略過廣告安全等待時間提升至 42s / 45s
        assert "isEndCard && elapsedSec >= 42" in ad_code
        assert "if (elapsedSec >= 45)" in ad_code

    def test_08_version_bump_to_1_15_67(self):
        gradle_code = read_src("app/build.gradle.kts")
        workflow_code = read_src(".github/workflows/build-apk.yml")
        rel_notes = read_src("RELEASE_NOTES.md")

        assert 'versionName = "1.15.67"' in gradle_code
        assert "v1.15.67" in workflow_code
        assert "v1.15.67" in rel_notes
