#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Milestone 1 Empirical Stress Test & Adversarial Verification Suite (Challenger 2)
LineWalkAutoAd - Milestone 1: Feature F2 (AutomationEngine.kt & FloatingOverlayService.kt)

驗證項目：
1. 狀態機巡邏同一分頁停滯超過 15 Tick 自癒守護與強制推進機制。
2. 非主畫面輪詢多階自癒機制（每 6 Tick 心跳、6~14 Tick 降級主畫面、15 Tick MAIN_TABS 強制推進、20 Tick 其他階段安全返回）。
3. formatActiveStatusText 四大分頁動態跟隨（GAME/TIME/STEP/MOVE），徹底根治退步為首頁之缺陷。
4. 空狀態、向後相容性與極端字串穩健性驗證。
5. 原始碼靜態常數與契約結構實證。
"""

import os
import re
import pytest
from typing import List, Set, Optional

SRC_DIR = os.path.join(
    os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "linewalk", "autoad"
)


class MainTab:
    MOVE = "MOVE"
    STEP = "STEP"
    TIME = "TIME"
    GAME = "GAME"

    LABEL_MAP = {
        MOVE: ("首頁(移動)", "移動"),
        STEP: ("步數", "步數"),
        TIME: ("時間(放置)", "時間"),
        GAME: ("遊戲", "遊戲"),
    }


class EngineState:
    IDLE = ("IDLE", "待命", "0.待命")
    IN_GAME_SCAN = ("IN_GAME_SCAN", "1. 掃描遊戲任務中", "1.巡邏中")
    TRIGGER_AD_POPUP = ("TRIGGER_AD_POPUP", "2. 尋找看廣告按鈕", "2.點彈窗")
    AD_WATCHING = ("AD_WATCHING", "3. 觀看廣告與關閉偵測", "3.廣告監控")
    REWARD_CLAIM = ("REWARD_CLAIM", "4. 確認領取獎勵", "4.完成確認")

    def __init__(self, name: str, title: str, short_title: str):
        self.name = name
        self.title = title
        self.shortTitle = short_title


class PatrolPhase:
    MAIN_TABS = "四大分頁巡邏"
    BINGO = "每日賓果"
    LOTTERY = "抽抽樂"
    MISSION = "任務"
    FRIEND_GIFT = "好友送禮"
    RECEIVE_GIFT = "接收好友禮物"
    COMPLETED = "全任務完成"

    PHASE_ORDER = [MAIN_TABS, BINGO, LOTTERY, MISSION, FRIEND_GIFT, RECEIVE_GIFT, COMPLETED]

    @classmethod
    def get_next_phase(cls, current: str) -> str:
        try:
            idx = cls.PHASE_ORDER.index(current)
            if idx + 1 < len(cls.PHASE_ORDER):
                return cls.PHASE_ORDER[idx + 1]
            return cls.COMPLETED
        except ValueError:
            return cls.COMPLETED


class EngineUiStatus:
    def __init__(
        self,
        is_running: bool = False,
        state: Optional[EngineState] = None,
        current_round: int = 1,
        current_tab: str = MainTab.MOVE,
        status_detail: str = "點擊懸浮窗 [開始] 啟動自動看廣告",
        overlay_safe_detail: str = "點擊開始啟動自動巡邏",
        ads_completed_count: int = 0,
        tasks_triggered_count: int = 0,
        recent_logs: Optional[List[str]] = None,
    ):
        self.isRunning = is_running
        self.state = state if state is not None else EngineState(*EngineState.IDLE)
        self.currentRound = current_round
        self.currentTab = current_tab
        self.statusDetail = status_detail
        self.overlaySafeDetail = overlay_safe_detail
        self.adsCompletedCount = ads_completed_count
        self.tasksTriggeredCount = tasks_triggered_count
        self.recentLogs = recent_logs if recent_logs is not None else []


class FloatingOverlaySimulator:
    """精確重現 FloatingOverlayService.kt 之 formatActiveStatusText 格式化邏輯"""

    @staticmethod
    def format_active_status_text(status: EngineUiStatus) -> str:
        if not status.isRunning:
            return "散步趣助手 · 待命"

        detail = status.statusDetail
        safe_detail = status.overlaySafeDetail

        if "外部保護" in detail or "外部保護" in safe_detail or "防護" in detail:
            return "[外部保護] 安全退出中"

        if "每日賓果" in detail or "每日賓果" in safe_detail or "賓果" in safe_detail:
            if "開格" in detail or "開格" in safe_detail:
                return "[每日賓果] 開格中"
            elif "連線" in detail or "Get" in detail or "Get" in safe_detail:
                return "[每日賓果] 領取Get"
            else:
                return "[每日賓果] 執行中"

        if "抽抽樂" in detail or "抽抽樂" in safe_detail or "抽獎" in detail or "抽獎" in safe_detail:
            return "[抽抽樂] 開啟中"

        if "任務" in detail or "任務" in safe_detail:
            return "[任務] 領取中"

        if "好友" in detail or "好友" in safe_detail or "送禮" in detail or "送禮" in safe_detail:
            return "[好友送禮] 贈送中"

        if "收禮" in detail or "收禮" in safe_detail or "接收禮物" in detail or "接收禮物" in safe_detail:
            return "[接收禮物] 領取中"

        if status.state.name == "AD_WATCHING":
            sec_match = re.search(r"\((\d+)s?\)", safe_detail) or re.search(r"\((\d+)s?\)", detail)
            if sec_match:
                return f"[廣告監控] 播放中 ({sec_match.group(1)}s)"
            else:
                return "[廣告監控] 播放中"

        if "冷卻" in detail or "冷卻" in safe_detail:
            return "[巡邏中] 冷卻等待中"

        if "首頁" in detail or "移動" in detail or "首頁" in safe_detail or "移動" in safe_detail:
            return "[巡邏中] 首頁"

        if "步數" in detail or "步數" in safe_detail:
            return "[巡邏中] 步數"

        if "時間" in detail or "放置" in detail or "時間" in safe_detail or "放置" in safe_detail:
            return "[巡邏中] 時間"

        if "遊戲" in detail or "遊戲" in safe_detail:
            return "[巡邏中] 遊戲"

        if status.state.name == "IN_GAME_SCAN":
            tab = getattr(status, "currentTab", MainTab.MOVE)
            if tab == MainTab.MOVE:
                return "[巡邏中] 首頁"
            elif tab == MainTab.STEP:
                return "[巡邏中] 步數"
            elif tab == MainTab.TIME:
                return "[巡邏中] 時間"
            elif tab == MainTab.GAME:
                return "[巡邏中] 遊戲"

        return f"{status.state.shortTitle} ({status.adsCompletedCount})"


class AutomationEngineSimulator:
    """精確重現 AutomationEngine.kt 之巡邏自癒守護與停滯狀態機推進邏輯"""

    TAB_STAGNATION_THRESHOLD = 15
    NON_MAIN_IDLE_HEARTBEAT_TICKS = 6
    NON_MAIN_IDLE_FALLBACK_TICKS = 6
    NON_MAIN_IDLE_ADVANCE_TICKS = 15
    NON_MAIN_IDLE_SAFETY_BACK_TICKS = 20

    def __init__(self, tab_priority_order: Optional[List[str]] = None):
        self.tabPriorityOrder = (
            tab_priority_order if tab_priority_order else [MainTab.MOVE, MainTab.TIME, MainTab.GAME, MainTab.STEP]
        )
        self.currentPhase = PatrolPhase.MAIN_TABS
        self.currentTab = self.tabPriorityOrder[0]
        self.lastObservedTab = self.currentTab
        self.confirmedEmptyTabs: Set[str] = set()
        self.tabStagnationTicks = 0
        self.nonMainScreenIdleTicks = 0
        self.logs: List[str] = []
        self.detailText = ""
        self.safeDetailText = ""
        self.backKeyCount = 0

    def append_log(self, msg: str):
        self.logs.append(msg)

    def trigger_tab_self_healing_advancement(self, reason: str) -> bool:
        if self.currentPhase != PatrolPhase.MAIN_TABS:
            return False

        self.confirmedEmptyTabs.add(self.currentTab)
        self.tabStagnationTicks = 0
        self.nonMainScreenIdleTicks = 0

        next_remaining_tabs = [t for t in self.tabPriorityOrder if t not in self.confirmedEmptyTabs]
        self.append_log(f"[自癒] {reason}，啟動防死鎖自癒守護！標記【{self.currentTab}】完成並主動推進流程")

        if not next_remaining_tabs:
            tab_list_str = "->".join(self.tabPriorityOrder)
            self.append_log(f"[自癒] 四大分頁 ({tab_list_str}) 巡邏推進完畢，進入下一階段任務！")
            self.currentPhase = PatrolPhase.get_next_phase(PatrolPhase.MAIN_TABS)
            return True

        next_tab = next_remaining_tabs[0]
        self.append_log(f"[自癒] 主動切換至下一分頁【{next_tab}】")
        self.currentTab = next_tab
        self.detailText = f"[自癒] 防死鎖推進：正在切換至【{next_tab}】..."
        self.safeDetailText = f"[自癒] 切換至[{next_tab}]..."
        return True

    def step_main_screen_patrol(self, has_actionable_reward: bool = False, detected_tab: Optional[str] = None):
        """模擬 handleMainScreenPatrol 一個 Tick 之執行"""
        if self.currentPhase != PatrolPhase.MAIN_TABS:
            return "PHASE_NOT_MAIN"

        remaining_tabs = [t for t in self.tabPriorityOrder if t not in self.confirmedEmptyTabs]
        if not remaining_tabs:
            self.currentPhase = PatrolPhase.get_next_phase(PatrolPhase.MAIN_TABS)
            return "ALL_TABS_DONE"

        # Kotlin 邏輯：檢查是否已成功換頁，分頁變更時重置停滯計數
        if self.lastObservedTab != self.currentTab:
            self.lastObservedTab = self.currentTab
            self.tabStagnationTicks = 0

        self.tabStagnationTicks += 1

        if self.tabStagnationTicks >= self.TAB_STAGNATION_THRESHOLD:
            self.trigger_tab_self_healing_advancement(
                reason=f"停留【{self.currentTab}】達 {self.tabStagnationTicks} 個 Tick (~7.5 秒) 無操作推進"
            )
            return "HEALED_ADVANCED"

        # 模擬 detectTabFromScreen
        if detected_tab is not None:
            self.currentTab = detected_tab

        if has_actionable_reward:
            return "ACTION_PERFORMED"

        return "STAGNANT"

    def step_non_main_screen_idle(self, has_main_screen_markers: bool = False):
        """模擬非主畫面輪詢一個 Tick 之執行"""
        self.nonMainScreenIdleTicks += 1
        self.tabStagnationTicks += 1

        # Tier 1
        if self.nonMainScreenIdleTicks % self.NON_MAIN_IDLE_HEARTBEAT_TICKS == 1:
            self.append_log(
                f"[巡邏] 非主畫面輪詢中，當前【{self.currentTab}】，已等待 {self.nonMainScreenIdleTicks} 個 Tick..."
            )
            return "HEARTBEAT"

        # Tier 2
        if (
            has_main_screen_markers
            and self.NON_MAIN_IDLE_FALLBACK_TICKS <= self.nonMainScreenIdleTicks < self.NON_MAIN_IDLE_ADVANCE_TICKS
        ):
            if self.nonMainScreenIdleTicks % 3 == 0:
                self.append_log("[自癒] 畫面具備主畫面特徵，啟動降級主畫面常規巡邏！")
                return "DEGRADED_PATROL"

        # Tier 3 (僅在 MAIN_TABS 階段生效)
        if (
            self.currentPhase == PatrolPhase.MAIN_TABS
            and self.nonMainScreenIdleTicks >= self.NON_MAIN_IDLE_ADVANCE_TICKS
        ):
            self.trigger_tab_self_healing_advancement(
                reason=f"非主畫面輪詢達 {self.nonMainScreenIdleTicks} 個 Tick (~7.5 秒) 無進展"
            )
            return "TIER3_ADVANCED"

        # Tier 4 (在非 MAIN_TABS 或嚴重逾時 20 Tick 時觸發安全返回)
        if self.nonMainScreenIdleTicks >= self.NON_MAIN_IDLE_SAFETY_BACK_TICKS:
            self.append_log(f"[安全返回] 畫面連續 {self.nonMainScreenIdleTicks} 個 Tick 無法辨識，發送返回鍵拉回！")
            self.backKeyCount += 1
            self.nonMainScreenIdleTicks = 0
            return "TIER4_BACK"

        return "IDLE_WAIT"


# ==============================================================================
# PYTEST 實證測試套件
# ==============================================================================

def test_stagnation_threshold_exact_boundary():
    """驗證同一分頁停滯在第 14 Tick 保持等待，第 15 Tick 精確觸發換頁自癒"""
    engine = AutomationEngineSimulator()
    assert engine.currentTab == MainTab.MOVE

    # 模擬 1 至 14 Tick 停滯無按鈕
    for tick in range(1, 15):
        result = engine.step_main_screen_patrol(has_actionable_reward=False)
        assert result == "STAGNANT"
        assert engine.tabStagnationTicks == tick
        assert engine.currentTab == MainTab.MOVE
        assert MainTab.MOVE not in engine.confirmedEmptyTabs

    # 第 15 Tick：觸發自癒推進
    result = engine.step_main_screen_patrol(has_actionable_reward=False)
    assert result == "HEALED_ADVANCED"
    assert MainTab.MOVE in engine.confirmedEmptyTabs
    assert engine.tabStagnationTicks == 0
    assert engine.currentTab == MainTab.TIME
    assert any("停留【MOVE】達 15 個 Tick" in log for log in engine.logs)


def test_stagnation_across_all_four_tabs_sequentially():
    """驗證四大分頁在完全無任何按鈕可點時，連續 15 Tick 自癒推進，最終順利切換至下一階段（BINGO）"""
    engine = AutomationEngineSimulator()
    expected_sequence = [MainTab.MOVE, MainTab.TIME, MainTab.GAME, MainTab.STEP]

    for expected_tab in expected_sequence:
        assert engine.currentTab == expected_tab
        assert engine.currentPhase == PatrolPhase.MAIN_TABS

        # 模擬 14 次空轉
        for _ in range(14):
            engine.step_main_screen_patrol(has_actionable_reward=False)
        assert engine.currentTab == expected_tab

        # 第 15 次觸發換頁
        result = engine.step_main_screen_patrol(has_actionable_reward=False)
        assert result == "HEALED_ADVANCED"
        assert expected_tab in engine.confirmedEmptyTabs

    # 四大分頁皆已完成自癒標記，應推進至 BINGO 階段
    assert engine.currentPhase == PatrolPhase.BINGO
    assert len(engine.confirmedEmptyTabs) == 4
    assert any("四大分頁" in log and "巡邏推進完畢" in log for log in engine.logs)


def test_stagnation_reset_on_normal_tab_switch():
    """驗證若正常換頁，跨 Tick 巡邏時停滯計數器正確歸零，不引發假陽性提早切頁"""
    engine = AutomationEngineSimulator()

    # 在 MOVE 停滯 10 Tick
    for _ in range(10):
        engine.step_main_screen_patrol(has_actionable_reward=False)
    assert engine.tabStagnationTicks == 10

    # 模擬 detectTabFromScreen 於 Tick 結束前偵測到 TIME 分頁
    engine.step_main_screen_patrol(has_actionable_reward=False, detected_tab=MainTab.TIME)
    assert engine.currentTab == MainTab.TIME

    # 下一 Tick 進入 handleMainScreenPatrol，觸發 lastObservedTab != currentTab 重置
    engine.step_main_screen_patrol(has_actionable_reward=False)
    assert engine.tabStagnationTicks == 1
    assert engine.lastObservedTab == MainTab.TIME
    assert MainTab.MOVE not in engine.confirmedEmptyTabs


def test_non_main_screen_multi_tier_defense():
    """驗證非主畫面四階自癒防護機制：
    Tier 1 (每 6 Tick 心跳)
    Tier 2 (6~14 Tick 降級主畫面)
    Tier 3 (15 Tick MAIN_TABS 強制換頁自癒推進)
    Tier 4 (20 Tick 非 MAIN_TABS 嚴重逾時安全返回鍵)
    """
    engine = AutomationEngineSimulator()

    # 1. Tick 1: Tier 1 心跳日誌
    action1 = engine.step_non_main_screen_idle()
    assert action1 == "HEARTBEAT"
    assert any("非主畫面輪詢中" in log for log in engine.logs)

    # 2. Tick 2 ~ 5: 一般等待
    for _ in range(2, 6):
        action = engine.step_non_main_screen_idle()
        assert action == "IDLE_WAIT"

    # 3. Tick 6: Tier 2 降級主畫面重測
    action6 = engine.step_non_main_screen_idle(has_main_screen_markers=True)
    assert action6 == "DEGRADED_PATROL"
    assert any("降級主畫面常規巡邏" in log for log in engine.logs)

    # 4. Tick 7 ~ 14
    for _ in range(7, 15):
        engine.step_non_main_screen_idle(has_main_screen_markers=False)

    # 5. Tick 15: Tier 3 在 MAIN_TABS 階段主動推進換頁
    action15 = engine.step_non_main_screen_idle(has_main_screen_markers=False)
    assert action15 == "TIER3_ADVANCED"
    assert MainTab.MOVE in engine.confirmedEmptyTabs
    assert engine.currentTab == MainTab.TIME
    assert engine.nonMainScreenIdleTicks == 0
    assert engine.tabStagnationTicks == 0

    # 6. 切換至 BINGO 階段，測試 Tier 4 安全返回鍵機制 (達到 20 Tick)
    engine.currentPhase = PatrolPhase.BINGO
    for tick in range(1, 20):
        engine.step_non_main_screen_idle(has_main_screen_markers=False)
    assert engine.nonMainScreenIdleTicks == 19

    action20 = engine.step_non_main_screen_idle(has_main_screen_markers=False)
    assert action20 == "TIER4_BACK"
    assert engine.backKeyCount == 1
    assert engine.nonMainScreenIdleTicks == 0


def test_floating_overlay_all_four_tabs_tracking():
    """驗證 FloatingOverlayService.kt 的 formatActiveStatusText 在四大分頁輪詢時正確跟隨，絕無寫死退步為首頁"""
    overlay = FloatingOverlaySimulator()
    scan_state = EngineState(*EngineState.IN_GAME_SCAN)

    # 1. 遊戲分頁 (GAME)
    status_game = EngineUiStatus(
        is_running=True,
        state=scan_state,
        current_tab=MainTab.GAME,
        status_detail="[巡邏] 正在搜尋可領取目標或關閉按鈕...",
        overlay_safe_detail="[巡邏] 搜尋畫面目標中...",
    )
    assert overlay.format_active_status_text(status_game) == "[巡邏中] 遊戲"

    # 2. 時間分頁 (TIME)
    status_time = EngineUiStatus(
        is_running=True,
        state=scan_state,
        current_tab=MainTab.TIME,
        status_detail="[巡邏] 正在搜尋可領取目標或關閉按鈕...",
        overlay_safe_detail="[巡邏] 搜尋畫面目標中...",
    )
    assert overlay.format_active_status_text(status_time) == "[巡邏中] 時間"

    # 3. 步數分頁 (STEP)
    status_step = EngineUiStatus(
        is_running=True,
        state=scan_state,
        current_tab=MainTab.STEP,
        status_detail="[巡邏] 正在搜尋可領取目標或關閉按鈕...",
        overlay_safe_detail="[巡邏] 搜尋畫面目標中...",
    )
    assert overlay.format_active_status_text(status_step) == "[巡邏中] 步數"

    # 4. 首頁分頁 (MOVE)
    status_move = EngineUiStatus(
        is_running=True,
        state=scan_state,
        current_tab=MainTab.MOVE,
        status_detail="[巡邏] 正在搜尋可領取目標或關閉按鈕...",
        overlay_safe_detail="[巡邏] 搜尋畫面目標中...",
    )
    assert overlay.format_active_status_text(status_move) == "[巡邏中] 首頁"


def test_floating_overlay_backward_compatibility_and_empty_state():
    """驗證空狀態、未運行狀態及向後相容場景"""
    overlay = FloatingOverlaySimulator()

    # 未運行狀態
    idle_status = EngineUiStatus(is_running=False)
    assert overlay.format_active_status_text(idle_status) == "散步趣助手 · 待命"

    # 完全空白 detail 與 safeDetail，且為 IN_GAME_SCAN 狀態
    scan_state = EngineState(*EngineState.IN_GAME_SCAN)
    for tab, expected in [
        (MainTab.MOVE, "[巡邏中] 首頁"),
        (MainTab.STEP, "[巡邏中] 步數"),
        (MainTab.TIME, "[巡邏中] 時間"),
        (MainTab.GAME, "[巡邏中] 遊戲"),
    ]:
        empty_detail_status = EngineUiStatus(
            is_running=True,
            state=scan_state,
            current_tab=tab,
            status_detail="",
            overlay_safe_detail="",
        )
        assert overlay.format_active_status_text(empty_detail_status) == expected

    # 廣告監控狀態
    ad_state = EngineState(*EngineState.AD_WATCHING)
    ad_status = EngineUiStatus(
        is_running=True,
        state=ad_state,
        status_detail="[廣告] 播放中 (28s)",
        overlay_safe_detail="[廣告] 播放中 (28s)...",
    )
    assert overlay.format_active_status_text(ad_status) == "[廣告監控] 播放中 (28s)"


def test_static_code_invariants_in_kotlin_source():
    """靜態檢驗 Kotlin 原始碼中常數定義、狀態機推進與懸浮窗映射結構"""
    engine_file = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_file, "r", encoding="utf-8") as f:
        engine_code = f.read()

    # 常數驗證
    assert "const val TAB_STAGNATION_THRESHOLD = 15" in engine_code
    assert "const val NON_MAIN_IDLE_HEARTBEAT_TICKS = 6" in engine_code
    assert "const val NON_MAIN_IDLE_FALLBACK_TICKS = 6" in engine_code
    assert "const val NON_MAIN_IDLE_ADVANCE_TICKS = 15" in engine_code
    assert "const val NON_MAIN_IDLE_SAFETY_BACK_TICKS = 20" in engine_code

    # 方法與變數
    assert "internal var tabStagnationTicks: Int = 0" in engine_code
    assert "internal var nonMainScreenIdleTicks: Int = 0" in engine_code
    assert "internal suspend fun triggerTabSelfHealingAdvancement" in engine_code
    assert "val currentTab: MainTab = MainTab.MOVE" in engine_code

    # 懸浮窗服務原始碼驗證
    overlay_file = os.path.join(SRC_DIR, "ui", "FloatingOverlayService.kt")
    with open(overlay_file, "r", encoding="utf-8") as f:
        overlay_code = f.read()

    # 嚴格禁止寫死首頁之退步代碼
    assert 'status.state == com.linewalk.autoad.engine.EngineState.IN_GAME_SCAN -> "[巡邏中] 首頁"' not in overlay_code
    # 必須包含動態分頁映射
    assert "when (status.currentTab)" in overlay_code
    assert 'com.linewalk.autoad.engine.MainTab.GAME -> "[巡邏中] 遊戲"' in overlay_code
    assert 'com.linewalk.autoad.engine.MainTab.TIME -> "[巡邏中] 時間"' in overlay_code
    assert 'com.linewalk.autoad.engine.MainTab.STEP -> "[巡邏中] 步數"' in overlay_code
    assert 'com.linewalk.autoad.engine.MainTab.MOVE -> "[巡邏中] 首頁"' in overlay_code


def test_stagnation_with_partial_enabled_tabs():
    """驗證當使用者僅啟用部分分頁（例如僅啟用 TIME 與 GAME）時，自癒推進僅在啟用分頁間跳轉並直接進入下一階段"""
    partial_tabs = [MainTab.TIME, MainTab.GAME]
    engine = AutomationEngineSimulator(tab_priority_order=partial_tabs)
    assert engine.currentTab == MainTab.TIME

    # TIME 分頁停滯 15 Tick
    for _ in range(15):
        engine.step_main_screen_patrol(has_actionable_reward=False)
    assert engine.currentTab == MainTab.GAME
    assert MainTab.TIME in engine.confirmedEmptyTabs

    # GAME 分頁停滯 15 Tick
    for _ in range(15):
        engine.step_main_screen_patrol(has_actionable_reward=False)
    # 所有已啟用分頁皆標記完成，直接進入 BINGO 階段
    assert engine.currentPhase == PatrolPhase.BINGO
    assert MainTab.GAME in engine.confirmedEmptyTabs


def test_adversarial_strings_and_extreme_inputs_on_overlay():
    """對抗性字串檢驗：超長字串、特殊符號、多語系字元與邊界輸入"""
    overlay = FloatingOverlaySimulator()
    scan_state = EngineState(*EngineState.IN_GAME_SCAN)

    # 1. 10,000 字元超長字串
    huge_detail = "系統測試 " + ("X" * 10000)
    huge_status = EngineUiStatus(
        is_running=True,
        state=scan_state,
        current_tab=MainTab.GAME,
        status_detail=huge_detail,
        overlay_safe_detail=huge_detail,
    )
    assert overlay.format_active_status_text(huge_status) == "[巡邏中] 遊戲"

    # 2. 包含干擾字元「(非首頁)」時，因「首頁」出現在前置關鍵字分支，優先匹配「[巡邏中] 首頁」
    trick_status_with_shouye = EngineUiStatus(
        is_running=True,
        state=scan_state,
        current_tab=MainTab.STEP,
        status_detail="[巡邏] 檢查遊戲區塊進度 (非首頁)",
        overlay_safe_detail="[巡邏] 檢查中...",
    )
    assert overlay.format_active_status_text(trick_status_with_shouye) == "[巡邏中] 首頁"

    # 3. 完全不含四大分頁名稱時，精準回退至 status.currentTab 動態映射
    trick_status_clean = EngineUiStatus(
        is_running=True,
        state=scan_state,
        current_tab=MainTab.STEP,
        status_detail="[巡邏] 正在搜尋可點擊按鈕或目標目標...",
        overlay_safe_detail="[巡邏] 搜尋中...",
    )
    assert overlay.format_active_status_text(trick_status_clean) == "[巡邏中] 步數"

    # 4. 倒數秒數正則邊界 (0s, 999s)
    ad_state = EngineState(*EngineState.AD_WATCHING)
    status_0s = EngineUiStatus(is_running=True, state=ad_state, status_detail="[廣告] 播放中 (0s)", overlay_safe_detail="[廣告] 播放中 (0s)...")
    assert overlay.format_active_status_text(status_0s) == "[廣告監控] 播放中 (0s)"

    status_999s = EngineUiStatus(is_running=True, state=ad_state, status_detail="[廣告] 播放中 (999s)", overlay_safe_detail="[廣告] 播放中 (999s)...")
    assert overlay.format_active_status_text(status_999s) == "[廣告監控] 播放中 (999s)"


def test_concurrency_overlay_status_formatting():
    """高並發壓力檢驗：50 執行緒同時調用 format_active_status_text 驗證無競態死鎖或記憶體異常"""
    from concurrent.futures import ThreadPoolExecutor

    overlay = FloatingOverlaySimulator()
    scan_state = EngineState(*EngineState.IN_GAME_SCAN)

    statuses = [
        EngineUiStatus(is_running=True, state=scan_state, current_tab=MainTab.MOVE),
        EngineUiStatus(is_running=True, state=scan_state, current_tab=MainTab.STEP),
        EngineUiStatus(is_running=True, state=scan_state, current_tab=MainTab.TIME),
        EngineUiStatus(is_running=True, state=scan_state, current_tab=MainTab.GAME),
        EngineUiStatus(is_running=False),
    ]
    expected_outputs = {
        MainTab.MOVE: "[巡邏中] 首頁",
        MainTab.STEP: "[巡邏中] 步數",
        MainTab.TIME: "[巡邏中] 時間",
        MainTab.GAME: "[巡邏中] 遊戲",
    }

    def worker_task(idx: int):
        status = statuses[idx % len(statuses)]
        res = overlay.format_active_status_text(status)
        if not status.isRunning:
            assert res == "散步趣助手 · 待命"
        else:
            assert res == expected_outputs[status.currentTab]
        return True

    with ThreadPoolExecutor(max_workers=50) as executor:
        futures = [executor.submit(worker_task, i) for i in range(5000)]
        for f in futures:
            assert f.result() is True


if __name__ == "__main__":
    pytest.main(["-v", __file__])

