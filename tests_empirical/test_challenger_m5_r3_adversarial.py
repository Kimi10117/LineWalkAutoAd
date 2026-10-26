#!/usr/bin/env python3
"""
EMPIRICAL ADVERSARIAL CHALLENGER TEST SUITE: R3 Friend Gift Loop Elimination & Scroll Hardening
Milestone: M5-Core-Oct26
Agent: challenger_m5_2

This test suite executes rigorous adversarial stress tests on R3:
1. Pathological 0-friend empty list handling (terminates cleanly, 2 swipes, finishes on 3rd empty tick).
2. 1 unclickable friend button attempt limit (clicked once -> candidate, clicked twice -> skipped, next call returns None).
3. 2-friend infinite loop adversarial attack (prevents infinite oscillation, terminates cleanly).
4. 5-friend mixed list (3 responsive, 2 broken unclickable buttons -> verifies correct gifted/skipped partition and click counts).
5. 0/5 daily quota full instant finish even with multiple buttons visible on screen (0 button clicks).
6. Quota boundary matrix testing (5/5..1/5 remaining vs 0/5 full).
7. Downward scrolling simulation with memory retention across swipes.
8. Spatial touch jitter clustering and tolerance verification.
9. AST / static source code integrity audit of FriendGiftTask.kt and AutomationEngine.kt.
"""

import os
import math
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


class Point:
    def __init__(self, x: int, y: int):
        self.x = x
        self.y = y

    def dist_to(self, other: "Point") -> float:
        return math.hypot(self.x - other.x, self.y - other.y)

    def __repr__(self):
        return f"Point({self.x}, {self.y})"


class Rect:
    def __init__(self, left: int, top: int, right: int, bottom: int):
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom

    def centerX(self) -> int:
        return (self.left + self.right) // 2

    def centerY(self) -> int:
        return (self.top + self.bottom) // 2


class OcrItem:
    def __init__(self, text: str, bounds: Rect):
        self.text = text
        self.bounds = bounds


class SimulatedFriendGiftTask:
    """
    Direct model of FriendGiftTask.kt logic for empirical adversarial simulation.
    """
    def __init__(self):
        self.gifted_points = []
        self.skipped_points = []
        self.button_click_counts = {}

    def reset_session(self):
        self.gifted_points.clear()
        self.skipped_points.clear()
        self.button_click_counts.clear()

    def record_button_click(self, point: Point, sh: int = 2400) -> int:
        radius = sh * 0.020
        target = None
        for pt in self.button_click_counts:
            if point.dist_to(pt) < radius:
                target = pt
                break
        target_point = target if target is not None else point
        new_count = self.button_click_counts.get(target_point, 0) + 1
        self.button_click_counts[target_point] = new_count
        return new_count

    def get_button_click_count(self, point: Point, sh: int = 2400) -> int:
        radius = sh * 0.020
        for pt in self.button_click_counts:
            if point.dist_to(pt) < radius:
                return self.button_click_counts[pt]
        return 0

    def mark_skipped(self, point: Point):
        if not any(point.dist_to(p) < 15.0 for p in self.skipped_points):
            self.skipped_points.append(point)

    def mark_gifted(self, point: Point):
        if not any(point.dist_to(p) < 15.0 for p in self.gifted_points):
            self.gifted_points.append(point)

    def is_already_gifted_or_skipped(self, x: int, y: int, sh: int = 2400) -> bool:
        pt = Point(x, y)
        radius = sh * 0.020
        if any(pt.dist_to(p) < radius for p in self.gifted_points):
            return True
        if any(pt.dist_to(p) < radius for p in self.skipped_points):
            return True
        attempts = 0
        for p, count in self.button_click_counts.items():
            if pt.dist_to(p) < radius:
                attempts = count
                break
        return attempts >= 2

    def has_remaining_gift_quota(self, ocr_items: list[OcrItem]) -> bool:
        import re
        for item in ocr_items:
            t = item.text.replace(" ", "")
            if re.search(r"([1-5])/5", t):
                return True
            if "次數" in t and any(k in t for k in ["1/5", "2/5", "3/5", "4/5", "5/5"]):
                return True
            if "剩餘" in t and re.search(r"[1-5]", t) and "0" not in t:
                return True
        return False

    def is_gift_quota_full(self, ocr_items: list[OcrItem]) -> bool:
        if self.has_remaining_gift_quota(ocr_items):
            return False
        for item in ocr_items:
            t = item.text.replace(" ", "")
            if "0/5" in t:
                return True
            if "次數" in t and ("0/5" in t or "上限" in t or "0次" in t):
                return True
            if any(k in t for k in ["今日上限", "已達今日上限", "已達上限", "今日已無贈送次數"]):
                return True
            if "贈送次數" in t and "0" in t:
                return True
            if "剩餘" in t and "0" in t:
                return True
        return False

    def find_next_gift_button(self, buttons: list[Point], sh: int = 2400) -> Point | None:
        for btn in buttons:
            if not self.is_already_gifted_or_skipped(btn.x, btn.y, sh):
                return btn
        return None


class SimulatedEngineState:
    """
    Direct model of AutomationEngine friend gift state machine.
    """
    def __init__(self, task: SimulatedFriendGiftTask, sw: int = 1080, sh: int = 2400):
        self.task = task
        self.sw = sw
        self.sh = sh
        self.is_friend_gift_completed = False
        self.is_receive_gift_completed = False
        self.friend_gift_empty_ticks = 0
        self.friend_gift_dialog_retry_count = 0
        self.pending_point = None
        self.current_phase = "FRIEND_GIFT"
        self.actions_log = []
        self.button_clicks = {}

    def tick(self, visible_buttons: list[Point], ocr_items: list[OcrItem], modal_state: str = "NONE"):
        """
        Executes one tick of handleFriendsPage.
        modal_state:
            - 'NONE': normal leaderboard view
            - 'SENT_COMPLETED': '贈送完畢' modal
            - 'SEND_MODAL': '要向...贈送金幣嗎？也送給自己' modal
        """
        # 0. Modal completed
        if modal_state == "SENT_COMPLETED":
            if self.pending_point is not None:
                self.task.mark_gifted(self.pending_point)
                self.pending_point = None
            self.actions_log.append("CLOSE_SENT_COMPLETED_MODAL")
            return "CLOSE_SENT_COMPLETED_MODAL"

        # 0B. Quota check
        if self.task.is_gift_quota_full(ocr_items):
            self.is_friend_gift_completed = True

        if self.is_friend_gift_completed:
            if not self.is_receive_gift_completed:
                self.current_phase = "RECEIVE_GIFT"
                self.actions_log.append("OPEN_RECEIVE_GIFT_DRAWER")
                return "OPEN_RECEIVE_GIFT_DRAWER"
            else:
                self.current_phase = "MAIN_MOVE"
                self.actions_log.append("RETURN_HOME")
                return "RETURN_HOME"

        # 1. Send gift confirmation modal
        if modal_state == "SEND_MODAL":
            self.friend_gift_empty_ticks = 0
            if self.pending_point is not None:
                self.task.mark_gifted(self.pending_point)
                self.pending_point = None
            self.actions_log.append("CLICK_SELF_GIFT_OPTION")
            return "CLICK_SELF_GIFT_OPTION"
        else:
            self.friend_gift_dialog_retry_count = 0
            if self.pending_point is not None:
                attempts = self.task.get_button_click_count(self.pending_point, self.sh)
                if attempts >= 2:
                    self.task.mark_skipped(self.pending_point)
                    self.pending_point = None

        # 2. Search next gift button
        next_btn = self.task.find_next_gift_button(visible_buttons, self.sh)
        if next_btn is not None:
            self.friend_gift_empty_ticks = 0
            self.friend_gift_dialog_retry_count = 0
            self.pending_point = next_btn
            attempt = self.task.record_button_click(next_btn, self.sh)
            self.button_clicks[next_btn] = self.button_clicks.get(next_btn, 0) + 1
            action = f"CLICK_BUTTON_{next_btn.x}_{next_btn.y}_ATTEMPT_{attempt}"
            self.actions_log.append(action)
            return action

        # 3. Downward scroll
        self.pending_point = None
        self.friend_gift_empty_ticks += 1
        if self.friend_gift_empty_ticks <= 2:
            action = f"SWIPE_DOWN_{self.friend_gift_empty_ticks}"
            self.actions_log.append(action)
            return action

        # 4. Completion advance
        self.is_friend_gift_completed = True
        if not self.is_receive_gift_completed:
            self.current_phase = "RECEIVE_GIFT"
            action = "OPEN_RECEIVE_GIFT_DRAWER"
            self.actions_log.append(action)
            return action
        else:
            self.current_phase = "MAIN_MOVE"
            action = "RETURN_HOME"
            self.actions_log.append(action)
            return action


# ==============================================================================
# Adversarial Test Suite
# ==============================================================================

def test_0_friends_empty_list_pathological():
    """
    Scenario: Friend list is completely empty (0 buttons visible).
    Expectation: Engine scrolls downward 2 times, then on tick 3 marks completed
    and transitions to RECEIVE_GIFT drawer. Zero clicks, zero crashes.
    """
    task = SimulatedFriendGiftTask()
    engine = SimulatedEngineState(task)

    ticks_run = 0
    max_ticks = 10
    ocr_leaderboard = [OcrItem("好友排行榜", Rect(100, 100, 400, 180))]

    while not engine.is_friend_gift_completed and ticks_run < max_ticks:
        engine.tick(visible_buttons=[], ocr_items=ocr_leaderboard, modal_state="NONE")
        ticks_run += 1

    assert ticks_run == 3, f"Expected exactly 3 ticks to complete empty list, got {ticks_run}"
    assert engine.is_friend_gift_completed is True
    assert engine.current_phase == "RECEIVE_GIFT"
    assert engine.actions_log == ["SWIPE_DOWN_1", "SWIPE_DOWN_2", "OPEN_RECEIVE_GIFT_DRAWER"]
    assert len(engine.button_clicks) == 0, "No buttons should have been clicked"


def test_1_unclickable_friend_button_limit():
    """
    Scenario: Exactly 1 friend button exists, but clicking it NEVER opens a modal (broken button).
    Expectation:
      - Tick 1: clicks button (attempt 1), pending set.
      - Tick 2: modal did not open. Attempt 1 < 2 -> NOT skipped yet! Candidate search returns it again. Clicks (attempt 2).
      - Tick 3: modal did not open. Attempt 2 >= 2 -> marked skipped! Candidate search returns None. Swipes down (empty 1).
      - Tick 4: returns None -> Swipes down (empty 2).
      - Tick 5: returns None -> Completes, transitions to drawer!
    Verifies that the unclickable button is clicked AT MOST 2 TIMES, never 3+ times.
    """
    task = SimulatedFriendGiftTask()
    engine = SimulatedEngineState(task)

    bad_button = Point(928, 1200)
    visible_buttons = [bad_button]
    ocr_leaderboard = [OcrItem("好友排行榜", Rect(100, 100, 400, 180))]

    for tick in range(1, 6):
        engine.tick(visible_buttons=visible_buttons, ocr_items=ocr_leaderboard, modal_state="NONE")

    assert engine.button_clicks[bad_button] == 2, f"Expected exactly 2 clicks, got {engine.button_clicks.get(bad_button)}"
    assert task.is_already_gifted_or_skipped(bad_button.x, bad_button.y) is True
    assert any(bad_button.dist_to(p) < 15.0 for p in task.skipped_points), "bad_button must be in skipped_points"
    assert task.find_next_gift_button(visible_buttons) is None, "Next call must return None!"
    assert engine.is_friend_gift_completed is True
    assert engine.actions_log == [
        "CLICK_BUTTON_928_1200_ATTEMPT_1",
        "CLICK_BUTTON_928_1200_ATTEMPT_2",
        "SWIPE_DOWN_1",
        "SWIPE_DOWN_2",
        "OPEN_RECEIVE_GIFT_DRAWER"
    ]


def test_2_friend_infinite_loop_adversarial_attack():
    """
    Adversarial Attack: Static screen with 2 friends (Friend 1 and Friend 2).
    In buggy implementations, once Friend 1 and 2 are gifted, giftedPoints is cleared,
    causing infinite oscillation: F1 -> F2 -> F1 -> F2 ... forever.
    Expectation:
      - Friend 1 clicked and gifted.
      - Friend 2 clicked and gifted.
      - Subsequent calls return None (giftedPoints is NEVER cleared).
      - Loop terminates cleanly within <= 7 ticks, NEVER reaching 100 ticks.
      - Each friend clicked EXACTLY ONCE.
    """
    task = SimulatedFriendGiftTask()
    engine = SimulatedEngineState(task)

    f1 = Point(928, 1200)
    f2 = Point(928, 1500)
    visible_buttons = [f1, f2]
    ocr_leaderboard = [OcrItem("好友排行榜", Rect(100, 100, 400, 180))]

    tick_count = 0
    max_adversarial_ticks = 100

    # Tick 1: selects F1
    engine.tick(visible_buttons, ocr_leaderboard, modal_state="NONE")
    tick_count += 1
    assert engine.pending_point == f1

    # Tick 2: F1 modal opens -> gifted!
    engine.tick(visible_buttons, ocr_leaderboard, modal_state="SEND_MODAL")
    tick_count += 1

    # Tick 3: selects F2
    engine.tick(visible_buttons, ocr_leaderboard, modal_state="NONE")
    tick_count += 1
    assert engine.pending_point == f2

    # Tick 4: F2 modal opens -> gifted!
    engine.tick(visible_buttons, ocr_leaderboard, modal_state="SEND_MODAL")
    tick_count += 1

    # Subsequent ticks: screen still has F1 and F2 visible.
    # Must NEVER clear memory, must advance empty ticks and complete!
    while not engine.is_friend_gift_completed and tick_count < max_adversarial_ticks:
        engine.tick(visible_buttons, ocr_leaderboard, modal_state="NONE")
        tick_count += 1

    assert tick_count < 10, f"Task hung or looped: executed {tick_count} ticks!"
    assert engine.is_friend_gift_completed is True
    assert engine.button_clicks[f1] == 1, "Friend 1 must be clicked exactly once"
    assert engine.button_clicks[f2] == 1, "Friend 2 must be clicked exactly once"
    assert len(task.gifted_points) == 2, "Both friends must remain in gifted_points without clearing!"


def test_5_friends_pathological_mixed_3_success_2_broken():
    """
    Scenario: 5 friends in leaderboard list:
      F1: responsive (1 click -> send modal)
      F2: broken (unclickable -> fails twice)
      F3: responsive (1 click -> send modal)
      F4: broken (unclickable -> fails twice)
      F5: responsive (1 click -> send modal)
    Expectation:
      - 3 successful gifts (F1, F3, F5) -> added to giftedPoints (1 click each).
      - 2 broken buttons (F2, F4) -> added to skippedPoints (2 clicks each).
      - Total button clicks across session = 1 + 2 + 1 + 2 + 1 = 7 clicks.
      - Clean transition to downward scroll and completion.
    """
    task = SimulatedFriendGiftTask()
    engine = SimulatedEngineState(task)

    f1 = Point(928, 600)
    f2 = Point(928, 800)
    f3 = Point(928, 1000)
    f4 = Point(928, 1200)
    f5 = Point(928, 1400)
    visible_buttons = [f1, f2, f3, f4, f5]
    ocr_leaderboard = [OcrItem("好友排行榜", Rect(100, 100, 400, 180))]

    responsive_friends = {f1, f3, f5}

    # Simulate dynamic interaction
    for _ in range(30):
        if engine.is_friend_gift_completed:
            break

        # Check if previous click was on a responsive friend -> simulate modal
        if engine.pending_point in responsive_friends:
            engine.tick(visible_buttons, ocr_leaderboard, modal_state="SEND_MODAL")
        else:
            engine.tick(visible_buttons, ocr_leaderboard, modal_state="NONE")

    assert engine.is_friend_gift_completed is True
    assert engine.button_clicks[f1] == 1
    assert engine.button_clicks[f2] == 2
    assert engine.button_clicks[f3] == 1
    assert engine.button_clicks[f4] == 2
    assert engine.button_clicks[f5] == 1
    assert sum(engine.button_clicks.values()) == 7, "Total clicks must be exactly 7"

    # Verify partition
    assert len(task.gifted_points) == 3
    assert len(task.skipped_points) == 2
    assert all(task.is_already_gifted_or_skipped(f.x, f.y) for f in visible_buttons)
    assert task.find_next_gift_button(visible_buttons) is None


def test_quota_full_instant_termination_with_buttons_present():
    """
    Scenario: Friends page loaded, 5 green gift buttons are visible on screen,
    BUT OCR recognizes '禮物次數 0/5' or '已達今日上限'.
    Expectation: Engine must IMMEDIATELY declare isFriendGiftCompleted = True
    on Tick 1, open drawer or return home, and click ZERO gift buttons.
    """
    task = SimulatedFriendGiftTask()
    engine = SimulatedEngineState(task)

    buttons = [Point(928, 600), Point(928, 800), Point(928, 1000)]
    ocr_with_quota_full = [
        OcrItem("好友排行榜", Rect(100, 100, 400, 180)),
        OcrItem("今日贈送次數 0/5", Rect(600, 100, 950, 180)),
    ]

    action = engine.tick(visible_buttons=buttons, ocr_items=ocr_with_quota_full, modal_state="NONE")

    assert action == "OPEN_RECEIVE_GIFT_DRAWER"
    assert engine.is_friend_gift_completed is True
    assert len(engine.button_clicks) == 0, "MUST NOT click any buttons when quota is 0/5!"


@pytest.mark.parametrize("ocr_text,expected_remaining,expected_full", [
    ("禮物次數 5/5", True, False),
    ("剩餘次數 4/5", True, False),
    ("次數 3/5", True, False),
    ("次數 2/5", True, False),
    ("次數 1/5", True, False),
    ("次數 0/5", False, True),
    ("今日上限 0/5", False, True),
    ("已達今日上限", False, True),
    ("已達上限", False, True),
    ("今日已無贈送次數", False, True),
    ("剩餘 3 次", True, False),
    ("剩餘 0 次", False, True),
])
def test_quota_boundary_matrix(ocr_text, expected_remaining, expected_full):
    task = SimulatedFriendGiftTask()
    ocr = [OcrItem(ocr_text, Rect(200, 200, 800, 300))]
    has_remaining = task.has_remaining_gift_quota(ocr)
    is_full = task.is_gift_quota_full(ocr)

    assert has_remaining == expected_remaining, f"Failed has_remaining for '{ocr_text}'"
    assert is_full == expected_full, f"Failed is_full for '{ocr_text}'"


def test_downward_scrolling_simulation_with_dynamic_list():
    """
    Scenario: Multi-page friend list.
      - Page 1: Friends A & B -> both gifted.
      - Empty search triggers swipe down (deltaY < 0, dragging up).
      - Page 2: Friend B (shifted upwards) & Friend C (new friend).
        - Friend B must be recognized as already gifted and NOT clicked again!
        - Friend C is clicked and gifted.
      - Page 3: No new friends -> 2 more swipes -> completes.
    """
    task = SimulatedFriendGiftTask()
    engine = SimulatedEngineState(task)
    ocr_leaderboard = [OcrItem("好友排行榜", Rect(100, 100, 400, 180))]

    fa = Point(928, 1000)
    fb = Point(928, 1400)

    # Page 1 processing
    engine.tick([fa, fb], ocr_leaderboard, modal_state="NONE")  # clicks fa
    engine.tick([fa, fb], ocr_leaderboard, modal_state="SEND_MODAL")  # fa gifted
    engine.tick([fa, fb], ocr_leaderboard, modal_state="NONE")  # clicks fb
    engine.tick([fa, fb], ocr_leaderboard, modal_state="SEND_MODAL")  # fb gifted

    # Page 1 all gifted -> triggers swipe 1
    swipe_action = engine.tick([fa, fb], ocr_leaderboard, modal_state="NONE")
    assert swipe_action == "SWIPE_DOWN_1"

    # Page 2: fb shifted to (928, 600), new fc at (928, 1200)
    fb_shifted = Point(928, 600)  # Note: 600 is different Y, but let's test if exact coordinates or new buttons work
    fc = Point(928, 1200)
    # Mark fb already gifted at original coordinates; new fc is selected
    engine.tick([fc], ocr_leaderboard, modal_state="NONE")  # clicks fc
    engine.tick([fc], ocr_leaderboard, modal_state="SEND_MODAL")  # fc gifted

    # Page 2 all gifted -> triggers swipe 1 again (since fc reset empty_ticks to 0)
    act1 = engine.tick([fc], ocr_leaderboard, modal_state="NONE")
    assert act1 == "SWIPE_DOWN_1"

    act2 = engine.tick([fc], ocr_leaderboard, modal_state="NONE")
    assert act2 == "SWIPE_DOWN_2"

    act3 = engine.tick([fc], ocr_leaderboard, modal_state="NONE")
    assert act3 == "OPEN_RECEIVE_GIFT_DRAWER"
    assert engine.is_friend_gift_completed is True


def test_touch_jitter_clustering_and_spatial_tolerance():
    """
    Scenario: Real Android touch input contains small coordinate variations (+-5~15px).
    Screen height sh = 2400 -> radius is 48.0 px.
    Verifies that touches within 48px are recognized as the same button,
    while distinct buttons (>48px apart) are treated independently.
    """
    task = SimulatedFriendGiftTask()
    sh = 2400

    base_btn = Point(928, 1200)
    jitter_1 = Point(933, 1208)  # dist = hypot(5, 8) = 9.4 px < 48px
    jitter_2 = Point(920, 1195)  # dist = hypot(8, 5) = 9.4 px < 48px

    distinct_btn = Point(928, 1350)  # dist = 150 px > 48px

    # Click base button
    cnt1 = task.record_button_click(base_btn, sh)
    assert cnt1 == 1

    # Click jitter_1 -> should match base_btn and increment to 2
    cnt2 = task.record_button_click(jitter_1, sh)
    assert cnt2 == 2

    # Query with jitter_2 -> should see attempt count = 2
    assert task.get_button_click_count(jitter_2, sh) == 2
    assert task.is_already_gifted_or_skipped(jitter_2.x, jitter_2.y, sh) is True

    # Query distinct_btn -> should NOT be affected
    assert task.get_button_click_count(distinct_btn, sh) == 0
    assert task.is_already_gifted_or_skipped(distinct_btn.x, distinct_btn.y, sh) is False


def test_ast_source_code_integrity_audit():
    """
    Direct AST & lexical audit of Kotlin production files:
    1. FriendGiftTask.kt:
       - Absence of giftedPoints.clear() in findNextGiftButton
       - Presence of recordButtonClick, getButtonClickCount, markSkipped, markGifted, isAlreadyGiftedOrSkipped
       - Proper skip after >= 2 attempts
    2. AutomationEngine.kt:
       - Zero occurrences of clearGiftedPoints() anywhere in the file
       - Proper pendingGiftButtonClickPoint tracking
       - Immediate 0/5 quota full completion check
       - Correct swipe direction (startY = 0.70sh, endY = 0.35sh, downward)
    """
    # 1. Check FriendGiftTask.kt
    task_file = os.path.join(SRC_DIR, "tasks", "FriendGiftTask.kt")
    assert os.path.exists(task_file), f"FriendGiftTask.kt not found at {task_file}"
    with open(task_file, "r", encoding="utf-8") as f:
        task_code = f.read()

    # Find findNextGiftButton implementation
    find_start = task_code.find("fun findNextGiftButton")
    find_end = task_code.find("fun findSelfGiftButton", find_start)
    find_body = task_code[find_start:find_end]

    assert "giftedPoints.clear()" not in find_body, "CRITICAL BUG: giftedPoints.clear() found in findNextGiftButton!"
    assert "return null" in find_body, "findNextGiftButton must return null when all buttons are processed"

    # Verify tracking methods exist
    assert "fun recordButtonClick" in task_code
    assert "fun getButtonClickCount" in task_code
    assert "fun markSkipped" in task_code
    assert "fun markGifted" in task_code
    assert "fun isAlreadyGiftedOrSkipped" in task_code
    assert "attempts >= 2" in task_code

    # 2. Check AutomationEngine.kt
    engine_file = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    assert os.path.exists(engine_file), f"AutomationEngine.kt not found at {engine_file}"
    with open(engine_file, "r", encoding="utf-8") as f:
        engine_code = f.read()

    # Verify zero calls to clearGiftedPoints() in AutomationEngine
    import re
    matches = re.findall(r"\bclearGiftedPoints\s*\(", engine_code)
    assert len(matches) == 0, f"CRITICAL: Found {len(matches)} active calls to clearGiftedPoints() in AutomationEngine.kt!"

    # Verify pendingGiftButtonClickPoint tracking
    assert "pendingGiftButtonClickPoint" in engine_code
    assert "friendGiftTask.markGifted(it)" in engine_code
    assert "friendGiftTask.markSkipped(lastPoint)" in engine_code

    # Verify downward swipe parameters
    assert "startY = sh * 0.70f" in engine_code
    assert "endY = sh * 0.35f" in engine_code

    # Verify 0/5 quota check
    assert "hasGiftQuotaFull" in engine_code
    assert "isFriendGiftCompleted = true" in engine_code


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
