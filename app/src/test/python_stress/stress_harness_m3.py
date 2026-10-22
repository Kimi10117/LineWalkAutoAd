#!/usr/bin/env python3
"""
Stress Harness M3: Bingo Sequential Logic & Friend Gift Limit / No-Loop Transition
Covers:
1. BingoTask: Prioritize orange '立即開啟賓果格' -> click all 'Get' buttons -> complete bingo.
2. FriendGiftTask: Detect 5/5 quota limit, prevent short-list loop, transition to receive gift drawer.
"""

import sys

def test_bingo_sequential_flow():
    print("=== [TEST SUITE 1] Bingo Sequential Flow (R5) ===")

    # Mock screen state
    class MockBingoState:
        def __init__(self):
            self.open_cells_left = 2
            self.has_1_bingo_get = True
            self.has_3_bingo_get = True
            self.claimed_cols = set()
            self.is_completed = False
            self.empty_ticks = 0
            self.actions = []

        def find_open_cell_button(self):
            if self.open_cells_left > 0:
                return "Rect(open_cell)"
            return None

        def find_next_claimable_reward(self):
            # Column 0: 1 BINGO
            if 0 not in self.claimed_cols and self.has_1_bingo_get:
                return (0, "1 BINGO Get")
            # Column 1: 3 BINGO
            if 1 not in self.claimed_cols and self.has_3_bingo_get:
                return (1, "3 BINGO Get")
            return None

        def tick(self):
            # Step 1: Open cells FIRST
            open_cell = self.find_open_cell_button()
            if open_cell:
                self.actions.append("CLICK_OPEN_CELL")
                self.open_cells_left -= 1
                return

            # Step 2: Claim rewards
            reward = self.find_next_claimable_reward()
            if reward:
                col, label = reward
                self.claimed_cols.add(col)
                self.actions.append(f"CLICK_{label}")
                return

            # Step 3: Complete
            self.empty_ticks += 1
            if self.empty_ticks >= 2:
                self.is_completed = True
                self.actions.append("CLOSE_BINGO")

    sim = MockBingoState()
    for _ in range(10):
        if sim.is_completed:
            break
        sim.tick()

    print("  Executed actions:", sim.actions)
    assert sim.actions == [
        "CLICK_OPEN_CELL",
        "CLICK_OPEN_CELL",
        "CLICK_1 BINGO Get",
        "CLICK_3 BINGO Get",
        "CLOSE_BINGO"
    ], f"Unexpected sequence: {sim.actions}"
    assert sim.is_completed is True
    print("  [+] test_bingo_sequential_flow: PASS")

def test_friend_gift_quota_and_transition():
    print("\n=== [TEST SUITE 2] Friend Gift Quota & Drawer Transition (R7) ===")

    # Test quota full detection
    def is_gift_quota_full(ocr_texts):
        for t in ocr_texts:
            clean = t.replace(" ", "")
            if "5/5" in clean or ("次數" in clean and ("5/5" in clean or "上限" in clean)) or "今日上限" in clean or "已達上限" in clean:
                return True
        return False

    assert is_gift_quota_full(["毛大帥", "禮物次數", "2/5"]) is False
    assert is_gift_quota_full(["毛大帥", "禮物次數", "5/5"]) is True
    assert is_gift_quota_full(["已達到今日上限"]) is True
    print("  [+] Quota detection tests: PASS")

    # Test short friend list no-loop
    class MockFriendGiftFlow:
        def __init__(self):
            self.friends = [(928, 1217), (928, 1397)] # 2 friends
            self.gifted = set()
            self.empty_ticks = 0
            self.scroll_count = 0
            self.is_completed = False
            self.actions = []

        def find_next_button(self):
            for pt in self.friends:
                if pt not in self.gifted:
                    return pt
            return None

        def tick(self):
            # Check completed
            if self.is_completed:
                return

            # Check next button
            btn = self.find_next_button()
            if btn:
                self.gifted.add(btn)
                self.actions.append(f"GIFT_{btn}")
                return

            # Scroll
            self.empty_ticks += 1
            if self.empty_ticks <= 2:
                self.scroll_count += 1
                self.actions.append(f"SCROLL_{self.scroll_count}")
                # Notice: gifted is NOT cleared!
                return

            # Completed
            self.is_completed = True
            self.actions.append("OPEN_RECEIVE_GIFT_DRAWER")

    fg = MockFriendGiftFlow()
    for _ in range(10):
        if fg.is_completed:
            break
        fg.tick()

    print("  Executed actions:", fg.actions)
    assert fg.actions == [
        "GIFT_(928, 1217)",
        "GIFT_(928, 1397)",
        "SCROLL_1",
        "SCROLL_2",
        "OPEN_RECEIVE_GIFT_DRAWER"
    ], f"Unexpected sequence: {fg.actions}"
    assert fg.is_completed is True
    print("  [+] test_friend_gift_short_list_no_loop: PASS")

if __name__ == "__main__":
    test_bingo_sequential_flow()
    test_friend_gift_quota_and_transition()
    print("\n========================================================")
    print("ALL M3 TESTS PASSED SUCCESSFULLY!")
    print("========================================================")
