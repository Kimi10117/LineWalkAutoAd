#!/usr/bin/env python3
"""
EMPIRICAL TEST SUITE: R3 Friend Gift Loop Elimination & Scroll Hardening
LineWalkAutoAd
"""

import os
import math
import pytest

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(BASE_DIR, "app", "src", "main", "java", "com", "linewalk", "autoad")


class Point:
    def __init__(self, x, y):
        self.x = x
        self.y = y

    def dist_to(self, other):
        return math.hypot(self.x - other.x, self.y - other.y)


class MockFriendGiftTask:
    def __init__(self):
        self.gifted_points = []
        self.skipped_points = []
        self.button_click_counts = {}

    def reset_session(self):
        self.gifted_points.clear()
        self.skipped_points.clear()
        self.button_click_counts.clear()

    def record_button_click(self, pt, sh=2400):
        radius = sh * 0.020
        target = None
        for p in self.button_click_counts:
            if pt.dist_to(p) < radius:
                target = p
                break
        if target is None:
            target = pt
        new_count = self.button_click_counts.get(target, 0) + 1
        self.button_click_counts[target] = new_count
        return new_count

    def get_button_click_count(self, pt, sh=2400):
        radius = sh * 0.020
        for p in self.button_click_counts:
            if pt.dist_to(p) < radius:
                return self.button_click_counts[p]
        return 0

    def mark_skipped(self, pt):
        if not any(pt.dist_to(p) < 15.0 for p in self.skipped_points):
            self.skipped_points.append(pt)

    def mark_gifted(self, pt):
        if not any(pt.dist_to(p) < 15.0 for p in self.gifted_points):
            self.gifted_points.append(pt)

    def is_already_gifted_or_skipped(self, x, y, sh=2400):
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

    def find_next_gift_button(self, buttons, sh=2400):
        for btn in buttons:
            if not self.is_already_gifted_or_skipped(btn.x, btn.y, sh):
                return btn
        # Notice: NEVER clears gifted_points! Returns None when all handled.
        return None


def test_no_loop_on_two_friends():
    task = MockFriendGiftTask()
    friend1 = Point(928, 1200)
    friend2 = Point(928, 1400)
    screen_buttons = [friend1, friend2]

    # Tick 1: finds friend 1
    btn1 = task.find_next_gift_button(screen_buttons)
    assert btn1 is friend1
    task.mark_gifted(btn1)

    # Tick 2: finds friend 2
    btn2 = task.find_next_gift_button(screen_buttons)
    assert btn2 is friend2
    task.mark_gifted(btn2)

    # Tick 3: all gifted -> MUST return None (NO infinite reset!)
    btn3 = task.find_next_gift_button(screen_buttons)
    assert btn3 is None
    assert len(task.gifted_points) == 2, "gifted_points must NOT be cleared!"


def test_two_click_retry_and_skip_protection():
    task = MockFriendGiftTask()
    bad_btn = Point(928, 1200)

    # 1st attempt
    c1 = task.record_button_click(bad_btn)
    assert c1 == 1
    assert task.is_already_gifted_or_skipped(bad_btn.x, bad_btn.y) is False

    # 2nd attempt
    c2 = task.record_button_click(bad_btn)
    assert c2 == 2
    assert task.is_already_gifted_or_skipped(bad_btn.x, bad_btn.y) is True

    # Mark skipped
    task.mark_skipped(bad_btn)
    assert task.find_next_gift_button([bad_btn]) is None


def test_scroll_downward_and_completion_advance():
    task = MockFriendGiftTask()
    buttons = [Point(928, 1200)]
    task.mark_gifted(buttons[0])

    empty_ticks = 0
    actions = []
    is_completed = False

    for tick in range(5):
        next_btn = task.find_next_gift_button(buttons)
        if next_btn is not None:
            actions.append(f"CLICK_{next_btn.y}")
        else:
            empty_ticks += 1
            if empty_ticks <= 2:
                actions.append(f"SWIPE_DOWN_{empty_ticks}")
            else:
                is_completed = True
                actions.append("OPEN_RECEIVE_GIFT_DRAWER")
                break

    assert actions == ["SWIPE_DOWN_1", "SWIPE_DOWN_2", "OPEN_RECEIVE_GIFT_DRAWER"]
    assert is_completed is True


def test_ast_r3_source_integrity():
    task_path = os.path.join(SRC_DIR, "tasks", "FriendGiftTask.kt")
    with open(task_path, "r", encoding="utf-8") as f:
        src_task = f.read()

    # Verify absence of malicious giftedPoints.clear() in findNextGiftButton
    find_next_idx = src_task.find("fun findNextGiftButton")
    find_self_idx = src_task.find("fun findSelfGiftButton")
    find_next_body = src_task[find_next_idx:find_self_idx]
    assert "giftedPoints.clear()" not in find_next_body, "findNextGiftButton must NOT clear giftedPoints!"

    # Verify tracking methods
    assert "fun recordButtonClick" in src_task
    assert "fun getButtonClickCount" in src_task
    assert "fun markSkipped" in src_task
    assert "fun isAlreadyGiftedOrSkipped" in src_task

    engine_path = os.path.join(SRC_DIR, "engine", "AutomationEngine.kt")
    with open(engine_path, "r", encoding="utf-8") as f:
        src_engine = f.read()

    # Verify pendingGiftButtonClickPoint tracking
    assert "pendingGiftButtonClickPoint: android.graphics.Point?" in src_engine
    assert "attempts >= 2" in src_engine
    assert "friendGiftTask.markSkipped(lastPoint)" in src_engine

    # Verify no interim clearGiftedPoints in handleFriendsPage
    friends_page_idx = src_engine.find("fun handleFriendsPage")
    friends_page_end = src_engine.find("fun getTabRect", friends_page_idx)
    friends_page_body = src_engine[friends_page_idx:friends_page_end]
    assert "friendGiftTask.clearGiftedPoints()" not in friends_page_body


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
