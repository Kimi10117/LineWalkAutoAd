#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Deep Concurrency and Chronological Ordering Stress Test
Simulating coroutine/thread interactions with AutomationEngine logging buffer.
"""

import sys
import os
import re
import time
import datetime
import threading
import random
from concurrent.futures import ThreadPoolExecutor

class ConcurrentLogger:
    def __init__(self, max_capacity=5000):
        self.max_capacity = max_capacity
        self._fullSessionLogs = []
        self._lock = threading.Lock()
        self.sessionStartTime = datetime.datetime.now()

    def appendLog(self, message: str, date=None):
        if date is None:
            date = datetime.datetime.now()
        if "全自動巡邏已啟動" in message:
            self.sessionStartTime = date
        timestamp = date.strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{timestamp}] {message}"
        with self._lock:
            if len(self._fullSessionLogs) >= self.max_capacity:
                self._fullSessionLogs.pop(0)
            self._fullSessionLogs.append((date, line))

    def get_logs_snapshot(self):
        with self._lock:
            return list(self._fullSessionLogs)

    def getFullLogsFormatted(self, device="Pixel 8 Pro", os_ver="Android 14 (API 34)", app_ver="v1.15.20"):
        start_time_str = self.sessionStartTime.strftime("%Y-%m-%d %H:%M:%S")
        export_time_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with self._lock:
            snapshot = [line for (_, line) in self._fullSessionLogs]

        sb = []
        sb.append("========================================")
        sb.append("《LINE 散步趣助手》系統診斷日誌")
        sb.append(f"應用版本：{app_ver}")
        sb.append(f"裝置型號：{device}")
        sb.append(f"系統版本：{os_ver}")
        sb.append(f"啟動時間：{start_time_str}")
        sb.append(f"匯出時間：{export_time_str}")
        sb.append(f"日誌筆數：{len(snapshot)}")
        sb.append("========================================\n")
        if not snapshot:
            sb.append("(目前尚無執行日誌記錄)")
        else:
            for line in snapshot:
                sb.append(line)
        return "\n".join(sb)


def test_stress():
    print("[1] Stress Testing Concurrent Coroutine Appending (100 coroutines x 100 logs)...")
    logger = ConcurrentLogger(max_capacity=5000)

    # Simulate 100 concurrent workers
    worker_count = 100
    logs_per_worker = 100

    start_barrier = threading.Barrier(worker_count)

    def worker(worker_id):
        start_barrier.wait() # simultaneous start
        for i in range(logs_per_worker):
            logger.appendLog(f"Worker {worker_id:03d} Event {i:03d}")
            if random.random() < 0.05:
                # Occasional concurrent read
                _ = logger.getFullLogsFormatted()

    with ThreadPoolExecutor(max_workers=worker_count) as pool:
        futures = [pool.submit(worker, w) for w in range(worker_count)]
        for f in futures:
            f.result()

    snapshot = logger.get_logs_snapshot()
    print(f"    Total logs retained in buffer: {len(snapshot)}")
    assert len(snapshot) == 5000, f"Expected exactly 5000 logs, got {len(snapshot)}"

    # Check chronological order (monotonic non-decreasing dates)
    inversions = 0
    for i in range(len(snapshot) - 1):
        d1 = snapshot[i][0]
        d2 = snapshot[i+1][0]
        # Compare down to second resolution
        s1 = d1.strftime("%Y-%m-%d %H:%M:%S")
        s2 = d2.strftime("%Y-%m-%d %H:%M:%S")
        if s1 > s2:
            inversions += 1

    print(f"    Second-level timestamp inversions: {inversions}")
    assert inversions == 0, f"Detected {inversions} timestamp inversions!"

    # Verify diagnostic header at multi-thousand logs
    formatted = logger.getFullLogsFormatted()
    assert "日誌筆數：5000" in formatted
    assert "《LINE 散步趣助手》系統診斷日誌" in formatted

    print("[+] Concurrency and chronological ordering passed with zero flaws!")

if __name__ == "__main__":
    test_stress()
