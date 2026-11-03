# Project: LineWalkAutoAd 助手退步、點偏、死鎖根因修復與全機型螢幕自適應 (v1.15.65)

## Architecture
本專案為 Android 無障礙服務自動化工具《LINE 散步趣助手》，核心模組架構如下：
- **狀態機與巡邏引擎 (`AutomationEngine.kt`)**: 主循環排程、四大分頁切換、主畫面巡邏分發、異常輪詢自癒與無進展推進守護。
- **視覺與光學辨識 (`OcrRecognizer.kt`, `TemplateMatcher.kt`, `NodeInspector.kt`)**: OCR 文字識別、頂部分頁動態 Y 軸錨定、半透明遮罩檢測 (`isDimmedCenterModalOpen`)、Meta 廣告略過鈕偵測 (`detectCornerFastForwardSkip`)。
- **廣告與彈窗關閉 (`AdCloserHandler.kt`)**: 略過符號 (`▶|`, `>|`, `>>`)、防放棄彈窗攔截、安全盲點座標優先級、外部應用跳轉防護。
- **分頁與功能模組 (`MissionTask.kt`, `LotteryTask.kt`, `FriendGiftTask.kt`)**: 任務面板動態分頁、抽抽樂 50 抽票券防護、底部導覽列自適應錨定。
- **懸浮窗服務 (`FloatingOverlayService.kt`)**: 懸浮窗狀態與分頁標題顯示。
- **測試與驗證設施 (`tests_empirical/`)**: Python 多長寬比（16:9、20:9、22:9）經驗回歸測試套件。

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | F1 | 遊戲分頁主畫面遮罩假陽性一票否決 (`OcrRecognizer.kt` `isLineWalkMainScreenVisible` 加入 `isGameTabScreen` 一票否決 `isDimmedCenterModalOpen`) | M1 | Survey (Explorer 1 & 2) |
| 2 | F2 | 主畫面巡邏防死鎖自癒守護與懸浮窗退步修正 (`AutomationEngine.kt` 增加無進展逾時自癒推進機制；修正 `FloatingOverlayService.kt` detail 顯示) | M1 | Survey (Explorer 1 & 2) |
| 3 | F3 | 22:9 螢幕 Meta 廣告快轉略過按鈕搜尋窗適配 (`TemplateMatcher.kt` 放寬 `detectCornerFastForwardSkip` 至 `leftLimitX=sw*0.72f`, `topLimitY=max(sh*0.015f, 20)`) | M2 | Survey (Explorer 2) |
| 4 | F4 | 廣告備援盲點優先級安全重構 (`AdCloserHandler.kt` 移除左上角熱區首選盲點，改以右上角及右下角為優先，杜絕喚起三星瀏覽器) | M2 | Survey (Explorer 1 & 2) |
| 5 | F5 | 22:9 超長螢幕底部導覽列動態 Y 座標適配 (`FriendGiftTask.kt` 依螢幕長寬比動態錨定 Y 座標，22:9 為 0.945f，避開廣告橫幅) | M2 | Survey (Explorer 2) |
| 6 | F6 | 四大分頁 directOcr 搜尋範圍與字數收斂 (`AutomationEngine.kt:1765` 收斂 `cy in 0.07f..0.16f` 且 `t.length <= 4`，杜絕廣告文案干擾) | M2 | Survey (Explorer 2) |
| 7 | F7 | 多長寬比 (22:9, 16:9, 20:9) 經驗測試套件擴充 (`tests_empirical/` 擴充涵蓋 F1~F6 關鍵場景，現有 545+ 測試全綠) | M3 | Survey (Explorer 3) |
| 8 | F8 | 版本號推進與詳細無 Emoji 繁體中文 Git Commit 與 GitHub Release Notes (`versionCode = 98`, `versionName = "1.15.65"`) | M3 | Dispatch Requirement |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | 核心死鎖根除與狀態機自癒修復 | F1, F2 | none | DONE |
| M2 | 全機型螢幕座標適配與廣告略過修復 | F3, F4, F5, F6 | M1 | DONE |
| M3 | 多螢幕長寬比測試擴充、版本推進與無 Emoji 繁中發布 | F7, F8 | M1, M2 | DONE |

## Interface Contracts
### `OcrRecognizer` <-> `AutomationEngine`
- `isLineWalkMainScreenVisible(screenBitmap: Bitmap, items: List<OcrItem>, templateMatcher: TemplateMatcher?): Boolean`:
  若為遊戲分頁畫面 (`isGameTabScreen == true`)，`isDimmedCenterModalOpen` 不得阻擋主畫面判定；唯有在真正偵測到領獎彈窗關閉按鈕時方判定不在主畫面。
- `AutomationEngine`: 當停留於同一分頁且無操作超過設定門檻（例如連續 15 個 Tick 無按鈕可點且未換頁），觸發主動換頁守護推進，杜絕任何無日誌空轉死鎖。

### `TemplateMatcher` <-> `AdCloserHandler`
- `detectCornerFastForwardSkip(screenBitmap: Bitmap): Rect?`:
  在 1080x2640 (22:9) 螢幕下，搜尋範圍必須覆蓋 `x in [0.72f * sw, 0.965f * sw]`，`y in [0.015f * sh, 0.115f * sh]`，精確定位 `(426, 32)` 處略過按鈕，且嚴格過濾促銷文案標籤圖形。
- `AdCloserHandler`: 盲點序列調整為右上角 (0.91w, 0.045h) -> 右下角 (0.91w, 0.92h) -> 右上角 (0.95w, 0.08h)，完全廢除左上角可能擊中廣告主專頁頭像之盲點。

### `FriendGiftTask` <-> `AutomationEngine`
- `getBottomFriendsTabRect(sw: Int, sh: Int, ocrItems: List<OcrItem>): Rect`:
  若 OCR 未匹配，計算 `val aspect = sh.toFloat() / sw.toFloat()`，當 `aspect >= 2.2f` 時備援 Y 錨定於 `sh * 0.945f`，一般螢幕錨定於 `sh * 0.910f`。

## Code Layout
- `app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`: 主畫面判定、半透明遮罩否決邏輯。
- `app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`: 主畫面巡邏狀態機、逾時推進守護、四大分頁 OCR 範圍收斂。
- `app/src/main/java/com/linewalk/autoad/service/FloatingOverlayService.kt`: 懸浮窗狀態與標題更新。
- `app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt`: Meta 略過按鈕熱區擴展。
- `app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`: 盲點順序與安全關閉策略。
- `app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt`: 底部導覽列長寬比自適應。
- `app/build.gradle.kts`: 版本號升級至 versionCode 98, versionName 1.15.65。
- `tests_empirical/`: Python 多長寬比驗證測試套件。
