# LINE 散步趣自動看廣告助手 (LineWalkAutoAd) 發布說明

## 版本資訊
- 版本名稱：v1.15.86
- 版本代碼：versionCode 119, versionName "1.15.86"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.86 針對 `v1.15.85` 雙機實機日誌（`SM-F731N` Cash Giraffe 互動試玩廣告與 `SM-S7210` AHEALTH End Card）與截圖所揭露之「左下角圓形倒數漏辨識與 `Reveal Swipe` 誤踢試玩／重觸商店跳轉循環」、「兩層式 Google Play Half-Sheet 僅按 1 次 `Back` 未徹底退回」、「右上角 `[Google Play ×]` 兩段式商店解鎖支援」及「假復原誤入 `IN_GAME_SCAN` 後由步驟 `D3C` 繞過黑名單連點 9 次並被 `onEnterAdMode` 洗白」問題完成徹底修復與單元回歸驗證。

---

## 具體更新與修復內容 (v1.15.86)

### 1. 底部角落圓形倒數辨識與停用破壞性 `Reveal Swipe` 滑動
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`、`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- `OcrRecognizer.detectAdCountdownSeconds` 擴大支援底部左右兩側角落 (`cy >= 85%`、左/右 `25%` 邊角) 的圓形倒數秒數（如 `Cash Giraffe` 左下角 `@ 8%, 96%`），並自動剝除進度弧造成的括號噪字（如 `"36)"` -> `36`）。
- `AdCloserHandler` Step `8B`（`Reveal Swipe`）新增商店/結尾卡片/已點擊膠囊或已重定向互斥防護，移除 `"Drag"`、`"Tap"`、`"Swipe"`、`"Hold"`、`"Level"` 關鍵字並嚴禁在含 `"CLAIM"`、`"Try Again"`、`"Google Play"`、`"安裝"`、`"下載"` 或 `"$"` 畫面執行滑動，徹底杜絕反覆滑動試玩球門進而連續觸發 Play 商店的跳針循環。

### 2. 左上角 `[▶| Google Play]` 偏左圖示熱區點擊與 Play 商店兩層 Half-Sheet 雙重返回
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`、`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- Step `4B-2` 點擊 `[▶| Google Play]` 時將點擊座標優先鎖定於左側 `[▶|]` 快轉圖示熱區，並讓 Step `4C` 對左側獨立 `[▶|]` 放行；`AutomationEngine.handleExternalAppGuard` 在 `isLegitSkipRedirect` 發送第 1 次 `Back` 並延遲 350ms 後，若前景仍停留商店背景頁 (`com.android.vending`) 即補發第 2 次 `safeNavigateBack`，確保 Half-Sheet 與 Play Store 主體一併退回並清除過期 `lastClickedAdLabel`。

### 3. `[Google Play ×]` 兩段式解鎖＋失敗永久黑名單，與主畫面／`IN_GAME_SCAN` 循環防堵
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`、`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`、`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- `AdCloserHandler` Step `8` 針對右上角 `[Google Play ×]` 右側 `×` 的首次點擊標記為 `"右上角[Google Play ×]關閉鈕"`，走 2.0s + 2x Back 商店緩衝後允許第 2 次點擊嘗試關閉已解鎖之 End Card；若第 2 次仍觸發外部重定向則依一般 `"右上角幾何X"` 規則永久寫入黑名單。
- `OcrRecognizer.isLineWalkMainScreenVisible` 明確拒認含 `"Google Play"`、`"Not interested"`、`"Interested"`、`"CLAIM!"`、`"Try Again"` 等全螢幕廣告畫面；`IN_GAME_SCAN` 步驟 `D3C` 補上 `!hasPlayableOrStoreAdOcr && adWatchdogCooldownTicks == 0` 與 `AdCloserHandler` 黑名單／膠囊過濾，並在 45 秒內重返 `AD_WATCHING` 時保留近期重定向黑名單，且在 `recoverStuckAdToMainScreen` 升級 `CLEAR_TASK` 前執行 `pressHome()`，徹底阻斷 `Play=10` 無限迴圈。

### 4. `v1.15.86` 單元回歸驗證與版本升級
- **檔案**：`app/src/test/java/com/linewalk/autoad/test/v11586/V1_15_86SimulationTest.kt`、`app/build.gradle.kts`
- 新增 `V1_15_86SimulationTest`（4 項雙機情境測試），並將 `versionCode` 升級為 `119`、`versionName` 升級為 `"1.15.86"`（完整保留所有歷代版本紀錄與 APK）。

---

## 版本資訊 (歷史版本: v1.15.85)
- 版本名稱：v1.15.85
- 版本代碼：versionCode 118, versionName "1.15.85"
- 發布類型：正式版本 (Release)

---

## 概述 (歷史版本: v1.15.85)
LineWalkAutoAd v1.15.85 針對 v1.15.84 實機日誌與 AHEALTH 結束卡片／Google Play 重定向循環截圖所揭露之「右上角 `[Google Play ×]` 膠囊 `'y'`/`'a'` 筆畫誤觸幾何 `X` 導致 `Play=2` 來回跳轉」、「問卷按鈕列 `✕ Not interested` 誤識別為關閉鈕」、「結束卡片系統返回鍵遭內嵌廣告攔截反覆重試卻無法脫困」以及「`ForceBringGameToFront` 僅執行無效 `bringAppToFront` 而未清棧」問題完成徹底修復與回歸驗證。

---

## 具體更新與修復內容 (v1.15.85)

### 1. 右上角商店膠囊左向連動封鎖與 OCR 排除，杜絕 `Play=2` 重定向乒乓循環
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- `isInvalidOrBlacklisted` 新增右上角重定向膠囊左向帶狀封鎖（自重定向座標向左延伸 `24%` 螢幕寬，覆蓋同列 `[Google Play ×]` 文字膠囊），解決圓形 `6%` 黑名單半徑未覆蓋 `"Google Play"` 尾端 `'y'`/`'a'` 而於 `[01:37:05]` 發生二次跳轉商店的問題。
- 新增 `isBlockedByCornerPillOrSurveyOcr` 並整合至 `Step 4D`、`Step 7`、`Step 7A2`、`Step 8` 與 `focusedGeoX` 之幾何 `X`／OCR 黑名單條件，在首次點擊即排除 `"Google Play"`、`"Play 商店"`、`"安裝"`、`"下載"` 等商店字樣內部筆畫誤判。

### 2. 排除 `✓ Interested` / `✕ Not interested` 問卷列 `✕` 誤點
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`、`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- `OcrRecognizer.AD_CLOSE_EXCLUDE_KEYWORDS` 新增 `"Not interested"` 與 `"Interested"`。
- `AdCloserHandler` 新增 `isSurveyRowCloseSymbol`，在 `Step 7` 全螢幕關閉 OCR 與 `Step 7A2` 角落 ASCII 比對前自動過濾與問卷按鈕同列之單字元 `✕`/`×`，避免因 ML Kit 拆分文字框而誤觸問卷拒絕按鈕。

### 3. 結束卡片系統返回鍵遭吞噬自動升級強制清棧 + 三段式主畫面恢復統一收斂
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`、`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- `AdCloserHandler` 新增 `swallowedEndCardBackAttempts` 與共享 helper `trySafeEndCardOrRedirectBack`：`Step 7B`（`isEndCard`）與 `Step 8B`（`redirectCount > 0` 安全返回）在系統 `Back` 連續 2 次未退出廣告時，第 3 次直接升級回傳 `AdStepResult.ForceBringGameToFront`（`END_CARD_BACK_SWALLOWED` / `POST_REDIRECT_BACK_SWALLOWED`），無須再空等至 65 秒 Watchdog。
- `AutomationEngine` 抽取共用函式 `recoverStuckAdToMainScreen`，讓全域 Watchdog 逾時、`WatchdogBackTriggered` 達 `timerLimitSec` 上限與 `ForceBringGameToFront` 統一採用 `pressHome -> CLEAR_TOP -> CLEAR_TASK` 三段式清棧逃脫（即使前景套件仍為 `com.linecorp.LGWKTW` 亦能徹底清除卡死的內嵌廣告 Activity）。

### 4. `v1.15.85` 單元回歸驗證與版本升級
- **檔案**：`app/src/test/java/com/linewalk/autoad/test/v11585/V1_15_85SimulationTest.kt`、`app/build.gradle.kts`
- 新增 `V1_15_85SimulationTest`（4 項核心情境測試），並將 `versionCode` 升級為 `118`、`versionName` 升級為 `"1.15.85"`（完整保留所有歷代版本紀錄與 APK）。

---

## 版本資訊 (歷史版本: v1.15.84)
- 版本名稱：v1.15.84
- 版本代碼：versionCode 117, versionName "1.15.84"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.84 針對「全螢幕廣告左上角跨界 `[▶|]` 略過圓鈕無法辨識（如 iHEALTH Run. Burn. Earn.）」、「`13.5%~16.6%` 廣告橫幅／專頁圖示誤觸幾何 `X`」、「Watchdog 返回主畫面後誤觸返回鍵」、「內嵌廣告吞噬 `Back` 導致反覆 `65s` 強退死循環」與「OCR 原生呼叫做逾時防呆」完成全方位修復與回歸驗證。

---

## 具體更新與修復內容 (v1.15.84)

### 1. 左上角跨界深色半透明 `[▶|]` 略過鈕偵測與膠囊／字詞防誤觸
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt`、`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- `TemplateMatcher.detectCornerFastForwardSkip` 與 `scanCornerSkipZone` 擴展左上角掃描區（`x in 2.5%..22.0%`、`y in 3.5%..14.0%`），支援僅上半圈深灰圓框貼邊跨界（Horizon 亮背景交界）搭配單色純白圖示驗證，同時透過 `hasCapsuleExtension` 與有界 8 連通 `isCrossXGlyph` 排除頂部「X 56 秒後可獲獎勵」橫向膠囊及角落幾何 `X`。
- `AdCloserHandler` Step `4C` 放寬 `fastForwardSkipClicks` 至涵蓋 `isMetaAd || isAd || isGame || fgPkg == null`，並加入 `hasAdjacentCapsuleOcr` 阻擋重疊於倒數／Google Play 文字膠囊之座標；同時移除 `8B Reveal Swipe` 對 `"Play"` 寬鬆比對，避免撞上 `"Google Play"` 徽章。

### 2. 收斂幾何 `X` 角落掃描區，排除橫幅促銷與廣告主圖示
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt`
- `detectCornerGeometricX` 將右上角左側起點收斂至 `0.80f`（排除 `~78%` 創意內縮圖示）、左上角橫向收斂於 `0.020f..0.162f`（排除 `16.6%` 專頁圖示），並將下緣校準為 `0.140f`（扣除內部掃描邊距後中心止於 `~13.06%`，排除 `13.5%~15.7%` 促銷圖示同時保留 `11.3%` 真實角落叉鈕）。

### 3. Watchdog 主畫面先檢與內嵌廣告雙段強退 Task 升級
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`、`app/src/main/java/com/linewalk/autoad/service/AutoAdAccessibilityService.kt`
- `handleAdWatching` 於判斷 `adElapsed >= timerLimitSec` 前先以 `isGameScreenVisible` 確認是否已回到散步趣主畫面並直接復歸，避免已退場卻誤發返回鍵跳回桌面。
- 針對內嵌廣告吞噬 `Back`（前景仍停在 `com.linecorp.LGWKTW`）情境，新增 `pressHome()`、`forceResetAppTaskToFront(targetPackage, clearTask)`、`verifyRecoveredGameScreen` 與 `consecutiveWatchdogTimeouts`：第 1 階段執行 `pressHome()` + `CLEAR_TOP`，若截圖驗證仍卡在廣告則立即升級 `CLEAR_TASK`，連續無法截圖（`null`）時於第 2 次逾時直上 `CLEAR_TASK` 終結 65 秒迴圈。

### 4. ML Kit OCR 逾時防護與 `v1.15.84` 回歸驗證
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`、`app/src/test/java/com/linewalk/autoad/test/v11584/V1_15_84SimulationTest.kt`、`app/build.gradle.kts`
- `OcrRecognizer.recognize` 導入 `withTimeoutOrNull(2500L)` 與 `Throwable` 保護（維持重拋 `CancellationException`），防止 OCR 停滯卡死引擎迴圈。
- 新增 `V1_15_84SimulationTest`（4 項情境測試），並將 `versionCode` 升級為 `117`、`versionName` 升級為 `"1.15.84"`（完整保留所有歷代版本紀錄與 APK）。

---

## 版本資訊 (歷史版本: v1.15.83)
- 版本名稱：v1.15.83
- 版本代碼：versionCode 116, versionName "1.15.83"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.83 依據 `v1.15.82` 實機診斷日誌（15 輪、50 次廣告完成分析），針對「應用評分推廣卡誤充左上角 Google Play 略過鈕並漏掉角落 X」、「45 秒右下角備援盲點誤觸 Play 商店安裝橫幅」、「Watchdog 強退未重設廣告計時導致 6 秒後秒退」、「黑名單座標重複記錄與無法讀取資料彈窗識別」，以及「每日任務免費直領誤等 4 秒逾時警告」進行五大精準修正。

---

## 具體更新與修復內容 (v1.15.83)

### 1. 收緊 Google Play 略過鈕條件並新增角落獨立 `X` OCR 關閉
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`、`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- `AdCloserHandler.findTopLeftGooglePlaySkipPill` 收緊左上角幾何區間（`cxRatio <= 0.285f`、`cyRatio <= 0.155f`、`leftRatio <= 0.160f`）並過濾星等（`★`／`⭐`／`\d.\d`）、App 名稱前綴與推薦／安裝雜訊，杜絕將「起漲K線!4.8 ★▶ Goo…」推廣卡片誤判為略過鈕。
- `markRecentRedirect` 將預期略過標籤的重定向容錯次數收緊至 `2` 次並依跳轉狀況調整點擊上限，同時於 `OcrRecognizer` 新增 `findCornerAsciiCloseButton` 精準辨識僅位於左上／右上角落安全座標的獨立 `"X"`（如 `"X"@6%,6%`），不再漏失或誤觸中央品牌文字。

### 2. 移除右下角安裝 CTA 盲點並強化備援熱區黑名單過濾
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- `getNextFallbackTarget` 正式移除易誤觸底部全寬安裝按鈕之「螢幕右下角熱區」（`0.89..0.95h`），並將「螢幕右上角熱區」與「內容右上角熱區」往下校準避開系統狀態列邊緣。
- 備援盲點序列會跳過已被 `isInvalidOrBlacklisted` 封鎖的熱區座標；若所有安全角落皆已進黑名單，則直接改發系統安全返回鍵，不再反覆盲點同一跳轉區。

### 3. Watchdog 強退退出時重置廣告計時，杜絕秒級連鎖逾時
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`、`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- `AdCloserHandler` 新增 `clearAdSessionTimer()` 重置 `adStartTimeMs` 與 `watchdogForceBackAttempts`。
- `AutomationEngine` 於 `AD_WATCHDOG_TIMEOUT`、`WATCHDOG_BACK_TIMEOUT` 與強制拉回主畫面離開 `AD_WATCHING` 時呼叫 `clearAdSessionTimer()`，徹底防止數秒後外部瀏覽器／跳轉拉回並 `onEnterAdMode(preserveRedirectState = true)` 時繼承舊時間戳觸發 `74s/65s` 秒退。

### 4. 黑名單座標 16px 半徑去重與新增「無法讀取資料」錯誤彈窗識別
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`、`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`
- `AdCloserHandler` 抽取 `addBlacklistPoint`，對半徑 `16px` 內鄰近或相同座標自動去重，防止日誌出現 `(540,2071),(540,2071)` 重複項目佔滿快照上限。
- `OcrRecognizer.isNetworkErrorDialog` 與 `findAdLoadErrorCloseButton` 納入「無法讀取資料」、「讀取資料失敗」、「資料讀取失敗」，加速排除抽抽樂與副分頁斷線遮罩。

### 5. 每日任務綠色「領取」免廣告直領項目單拍快速恢復
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- 新增 `pendingMissionDirectClaim` 旗標並於 `transitionTo` 切換離場時自動清除；在 `handleMissionDialog` 點擊綠色「領取」後，若下個循環仍在任務視窗且無廣告或領獎中央彈窗，於第 1 拍即以「免看廣告獎勵已直接領取」快速返回 `IN_GAME_SCAN` 繼續領下一項，消除每次 4 秒「領獎確認逾時」等待與誤導日誌。

### 6. 回歸驗證與版本升級
- 新增 `V1_15_83SimulationTest`（5 項情境回歸測試涵蓋上述五大問題）。
- `versionCode` 升級為 `116`、`versionName` 升級為 `"1.15.83"`（相容歷代版本記錄完整保留）。

---

## 版本資訊 (歷史版本: v1.15.82)
- 版本名稱：v1.15.82
- 版本代碼：versionCode 115, versionName "1.15.82"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.82 依據實機診斷日誌，針對「抽抽樂關閉嘗試次數重設導致 1 到 6 無限迴圈」、「右上角幾何叉叉重複誤點擊 85 次」、「外部防護拉回遊戲後遺失觀看廣告狀態」、「左上角幾何叉叉重複跳轉外部瀏覽器」以及「斷線或網路異常彈窗覆蓋抽抽樂與副分頁時未識別」進行五大核心修復，並於網路異常時執行漸進退避等待，避免誤判各分頁廣告已清空。

---

## 具體更新與修復內容 (v1.15.82)

### 1. 修正抽抽樂關閉「1 -> 6 -> 1」死循環
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- `resetLotterySessionState(resetCloseAttempts: Boolean = false)` 新增選項參數；於 `getNextPhase(PatrolPhase.LOTTERY)` 推進流程與第 6 次強制離開抽抽樂分頁時保留 `lotteryDialogCloseAttempts`，使其能順利累積至第 7、8 次（強制切換分頁／執行系統返回鍵與重置），不再重設回 `1`。
- `start()`、`simulateRoundCompletion()` 與 `handleRoundCompletion()` 仍完整重設全部抽抽樂狀態。

### 2. 收緊右上角幾何偵測範圍並停止重複誤點 85 次
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt`、`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- 將 `TemplateMatcher.detectCornerGeometricX` 右上角邊界門檻由 `0.75f` 收緊至 `0.80f`，避開畫面中右上方的非關閉圖示誤檢。
- `AdCloserHandler` 抽取 `isGeoXTarget` 與 `countNearbyPoints`，將幾何叉叉與 Google Play 等高信心按鈕分流；同一幾何叉叉座標累積點擊 3 次無效即加入黑名單，並停止延後 `lastFallbackCornerTimeMs` 保底計時。

### 3. 外部 App 防護拉回時保留 `AD_WATCHING` 觀看狀態
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- 於 `handleExternalAppGuard` 抽取 `bringGameToFrontAndRestore(service, preserveWatchingState, restoreReason)`。
- 當系統正處於 `EngineState.AD_WATCHING` 且因 Google Play／外部瀏覽器 2 拍拉回、外部 App 逾時 3 拍拉回或略過點擊後回彈桌面時，保留 `EngineState.AD_WATCHING` 與原本廣告倒數基準，不再誤切回 `EngineState.IN_GAME_SCAN`。

### 4. 非預期外部瀏覽器跳轉於首次發生即拉黑原座標
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- 抽取 `isExpectedSkipLabel` 判斷預期跳轉按鈕（`Google Play`、`GooglePlay`、`略過`、`快轉`）。
- 於 `markRecentRedirect` 中，若最近點擊並非預期略過按鈕（例如 `左上角幾何X`）卻觸發跳轉外部瀏覽器，第 1 次跳轉即直接將該點加入 `blacklistedPoints`，不再連跳多次；同時維持既定鄰近半徑，不誤傷角落其他有效關閉點。

### 5. 網路異常彈窗穿透子對話框偵測與離線漸進退避
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`、`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- `OcrRecognizer` 新增 `isNetworkErrorDialog(items)` 並強化 `findAdLoadErrorCloseButton`：納入常見網路／連線／通訊錯誤關鍵字、放寬底部關閉與 `重試` 鈕縱軸掃描區間（`0.56f..0.82f`），並在畫面中部（`0.18f..0.72f`）出現明確錯誤提示且搭配關閉按鈕或綠色膠囊按鈕時，不再被背景 `抽抽樂`、`每日賓果`、`邀請好友` 文字誤擋。
- `AutomationEngine` 新增 `consecutiveNetworkErrorCount`，於偵測到網路異常彈窗點擊關閉後執行離線退避（`4s` / `8s`，熔斷時 `5s` / `8s`）且不將分頁標記為 `confirmedEmptyTabs`。

### 6. 回歸驗證與版本升級
- 新增 `V1_15_82SimulationTest`（5 項情境回歸測試涵蓋上述五大問題）。
- `versionCode` 升級為 `115`、`versionName` 升級為 `"1.15.82"`（相容歷代版本記錄完整保留）。

---

## 版本資訊 (歷史版本: v1.15.81)
- 版本名稱：v1.15.81
- 版本代碼：versionCode 114, versionName "1.15.81"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.81 依據實機執行診斷日誌，針對「動態島等被動懸浮軟體誤觸外部防護」、「LINE 內部宣傳廣告右上角關閉成功率」、「廣告倒數凍結／超時解鎖」、「點擊熔斷器跨階段殘留」與「OCR 畫面線索遙測雜訊」進行五大核心修復。本版本可穿透系統懸浮層取回真實前景 App、強化雙尺度幾何叉叉偵測與系統返回鍵交替策略，並讓診斷日誌精準聚焦於可分析的畫面內容與廣告計時狀態。

---

## 具體更新與修復內容 (v1.15.81)

### 1. 動態島與懸浮輔助套件防誤判保護
- **檔案**：`AppConfig.kt`、`AutoAdAccessibilityService.kt`、`AutomationEngine.kt`
- 新增 `PASSIVE_OVERLAY_UTILITY_PACKAGES` 與 `isPassiveOverlayUtilityPackage(pkg)`，涵蓋 `com.android.systemui`、`com.jamworks.dynamicspot`、`com.linewalk.autoad`、輸入法（IME）及 Samsung Edge 等輔助套件，並支援 `dynamicspot`、`dynamicisland`、`inputmethod` 名稱比對。
- 無障礙服務新增 `isIgnoredOverlayPackage(pkg)`（含 `packageName` 異常保護），並調整 `getForegroundPackageName()` 優先讀取 `TYPE_APPLICATION` 視窗（優先 `isActive`）穿透懸浮層取得真實前景套件；`evaluateForegroundPackage` 將此類懸浮套件視為非外部 App（回傳 `BLOCK_REGULAR_CLICK`），不再誤判進入 `AD_WATCHING` 或觸發外部返回防護。

### 2. LINE 內部宣傳廣告雙尺度幾何 X 偵測與奇偶交替返回
- **檔案**：`TemplateMatcher.kt`、`OcrRecognizer.kt`、`AdCloserHandler.kt`、`AutomationEngine.kt`
- 自 `TemplateMatcher` 抽出共用方法 `detectGeometricXInZones(..., skipZone0BingoBox = true, scaleRatio = 0.018f)` 供 `detectCornerGeometricX` 與跨模組共用，並修正類別大括號結構。
- `OcrRecognizer.findLineInHousePromoCloseButton` 改用雙尺度（`scaleRatio = 0.018f` 與 `0.028f`）幾何 X 偵測結合視口比例備援，並將 `recognizer` 改為 `by lazy` 延遲初始化。
- 於 `AdCloserHandler`（`1A-4` `LINE宣傳廣告`）與 `AutomationEngine` 共用之 `handleInGameLinePromoClose`（串接 `handleInGameScan` 與 `handleTriggerAdPopup`），實施奇數次嘗試關閉節點或幾何 X、偶數次觸發 `safeNavigateBack(allowInGame = true)` 的交替退場機制。

### 3. 廣告倒數凍結或逾時自動解鎖與點擊熔斷器重設
- **檔案**：`AdCloserHandler.kt`、`AutomationEngine.kt`
- `AdCloserHandler` 於 `3B` 階段透過 `isCountdownStuckOrExpired` 偵測連續停滯或超時倒數並自動解除等待鎖定，恢復幾何 X 掃描與強退邏輯。
- `AutomationEngine` 新增 `lastTargetClickTimeMs` 與 `resetClickCircuitBreaker()`，讓 `checkClickCircuitBreaker` 在超過 20 秒未點擊後自動歸零，並於 `start()`、`stop()`、`transitionTo(AD_WATCHING)` 與 `ReturnedToGame` 時一併清除熔斷次數、`inGameLinePromoRetryCount` 與退回拒絕原因。

### 4. `[畫面線索]` OCR 摘要淨化與診斷欄位補充
- **檔案**：`OcrRecognizer.kt`、`AutomationEngine.kt`、`AdCloserHandler.kt`
- 新增 `OcrRecognizer.isSystemTelemetryOcr(text)`，於 `detectAdCountdownSeconds` 排除頂部狀態列（`< 2.8%`）與系統指標文字；`formatStagnationOcrDigest` 進一步過濾狀態列、Pointer Location 遙測字串（如 `P:\d+/\d+`、`dX:` 等）及父子重複文字。
- `ClickedCloseOrSkip` 於空區域或 `"返回鍵"` 動作時省略無效座標紀錄；`AdCloserHandler.getDiagnosticsSnapshotFields()` 新增廣告載入耗時與最後觀察倒數（`load=${adLoadElapsedSec}s | cd=$cdStr`）。

---

## 版本資訊 (歷史版本: v1.15.80)
- 版本名稱：v1.15.80
- 版本代碼：versionCode 113, versionName "1.15.80"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.80 聚焦「計時強退逃脫、動態視口適配、精準領取防誤觸、診斷日誌升級與介面精簡」。本版本徹底移除低效且具學習負擔的現場框選取樣介面，將日常實用控制（廣告卡死強退秒數 `40s ~ 90s`、巡邏掃描速度與僅看異常日誌過濾）直接整合於主畫面；同時針對第三方廣告鎖死、Meta 贊助廣告左上角關閉按鈕辨識、收禮物綠色領取膠囊像素驗證、不同寬高比裝置黑邊動態視口校準，以及供分析使用的狀態快照與雙座標診斷日誌進行全面升級。

---

## 具體更新與修復內容 (v1.15.80)

### 1. 計時器驅動強退逃脫與安全返回診斷
- 修正使用者手動暫停巡邏時協程取消（`CancellationException`）誤觸異常日誌的問題，並清除子對話框等待逾時的假異常紀錄，改以平穩控制日誌 `[控制] 使用者手動暫停巡邏（工作階段平穩停止）` 呈現。
- 實作計時器驅動強制逃脫第三方廣告容器機制：整合有效看門狗秒數換算（`getEffectiveWatchdogLimitSec`）、強制帶出遊戲返回（`safeNavigateBack(..., forceEscapeAdPackage = true)` 與 `[計時強退]`）以及連續強退後強制切回首選遊戲套件（`ForceBringGameToFront`、`resolvePreferredGamePackage`），並於 `handleExternalAppGuard` 為 Google Play 試玩或略過導向保留約 2 秒緩衝保護。
- 擴充 `safeNavigateBack` 拒絕原因診斷（`lastBackRejectReason` 涵蓋 `AD_CONTAINER_PROTECT`、`GAME_PROTECT`、`COOLDOWN`、`SYSTEM_GLOBAL_ACTION_FAILED`），僅於系統實際允許並執行返回動作後才寫入 `[觸發]` 日誌，杜絕 Meta 廣告介面下的假陽性返回紀錄。

### 2. 收禮物像素雙門檻驗證與 Meta 贊助廣告關閉精準化
- `ReceiveGiftTask` 新增活躍綠色「領取」膠囊像素偵測（`hasActiveGreenClaimCapsule`）與 OCR 已領取列雙重驗證（`isRowAlreadyClaimedByOcr`），並搭配動態視口內容座標換算（`vp.mapContentX` / `vp.mapContentY`），停止重複點擊已領取之禮盒列。
- 針對 Meta 「Sponsored」贊助廣告優先啟用左上角幾何叉叉偵測（`detectCornerGeometricX`）並限制盲試次數（`sponsoredTopLeftProbeCount`）；高信心廣告關閉目標豁免座標黑名單（`isHighConfidenceAdTarget`），一般座標則放寬至 `nearbyClicks >= 4` 並套用隨螢幕寬度縮放之動態黑名單半徑（`getBlacklistRadiusPx`），同時修正 `redirectCount > 0` 與 `leftCornerRedirectCount` 邏輯以避免誤觸外部瀏覽器迴圈。

### 3. 動態視口黑邊校準與誘餌廣告文字過濾
- 新增 `ScreenViewportCalibrator` 與 `ViewportMetrics`，支援左右黑邊與有效頂底邊界掃描（`scanEffectiveTopY`、`scanHorizontalLetterboxBounds`）、系統狀態列安全高度查詢（`getStatusBarSafeHeight`）及分段線性內插子對話框關閉比例（`interpolateSubDialogCloseYRatio`），使範本比對、任務座標計算與無障礙點擊可隨裝置長寬比自動適配。
- 於 `OcrRecognizer` 新增 `AD_BAIT_PROMO_KEYWORDS` 誘餌廣告關鍵字清單（如「開始遊戲」、「開始遊玩」、「輕鬆賺」、「立即玩」、「免費試玩」），並串接至 `NodeInspector`、`BingoTask` 與 `GameTask`，避免誤觸試玩廣告按鈕。

### 4. AI 分析診斷日誌強化與導出資訊擴充
- 新增手機狀態快照（`[狀態Snap]` / `buildPhoneStateSnapshot`）、停滯期 OCR 濃縮摘要（`formatStagnationOcrDigest`）及點擊框雙座標顯示（`formatRectForLog` 輸出像素與螢幕百分比 `@ (x, y | nx%, ny%)`）。
- 擴充日誌匯出檔頭（包含螢幕規格、核心設定與工作階段摘要），並提供 `clearFullLogs()` 供即時清除日誌緩衝區。

### 5. 全面拆除現場取樣介面與主畫面實用控制升級
- 完全移除現場框選取樣功能與相關程式、資源檔（刪除 `TemplateStorage.kt`、`TemplateCropOverlay.kt`、`ic_crop.xml`、主畫面取樣卡片與 `launchTemplateCropper()`），並將 `refreshPermissionAndTemplateStatus()` 更名為 `refreshPermissionStatus()`。
- 維持懸浮膠囊原有直覺的 `+` 展開／收合與拖曳體驗（不加入高學習成本手勢或教學彈窗）；膠囊預設縱向位置改以螢幕高度比例與 `dp` 換算（`dpToPx(96)` 下限），收合狀態同步顯示清晰廣告計時進度（`[廣告監控] <階段> Xs / Ys`）。
- 主畫面卡片二新增「廣告卡死強制退回計時器」（`40s ~ 90s` 滑桿與 `-5s`/`+5s` 微調鈕，同步 `engine.config.maxAdWaitSeconds`）及「巡邏掃描速度」（極速 `1000ms` / 標準 `1200ms` / 省電 `1500ms`，同步 `engine.config.loopIntervalMs`）；卡片三新增僅供畫面檢視的「僅看異常／狀態」過濾核取方塊（匯出與複製仍保留完整日誌）與「清空日誌」按鈕。
- `.github/workflows/build-apk.yml` 與 `app/build.gradle.kts` 同步更新發布標籤與版本至 `v1.15.80`（`versionCode 113`）。

---

## 版本資訊 (歷史版本: v1.15.79)
- 版本名稱：v1.15.79
- 版本代碼：versionCode 112, versionName "1.15.79"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.79 依據使用者重要指導，全面相容並最佳化行動端互動式廣告（如 Google Play 試玩廣告等必須跳轉商店或瀏覽器後返回方可獲取略過資格的廣告流程）。本版本維持極速自癒返回機制的同時，導入重定向返回冷卻節流緩衝，杜絕返回後極短時間內重複高頻連點同一座標之現象；同時修正 Meta 廣告（如 Trip.com）左上角實體關閉叉叉（X）之偵測，廢除容易誤觸外部瀏覽器的右上角盲點熱區；並強化全螢幕宣傳頁在主畫面時之返回鍵穿透防護，實現高可靠性、平穩流暢的無人值守掛機體驗。

---

## 具體更新與修復內容 (v1.15.79)

### 1. 支援正常跳轉與重定向返回冷卻節流機制
- **檔案**：`AdCloserHandler.kt`
- **問題分析**：部分互動式廣告需要跳轉 Google Play 或瀏覽器才能觸發略過與結算。原先在外部跳轉時清空黑名單，且返回後未留出畫面渲染時間，導致在 1~2 秒極短間隔內於相同座標連續狂點 13 次跳轉三星瀏覽器。
- **實作細節**：
  - 廢除重定向時清空黑名單之錯誤邏輯，保留已識別黑名單與點擊歷史。
  - 新增 `lastRedirectReturnTimeMs`，於返回廣告畫面後啟動 3500ms 冷卻緩衝期，避免在同一座標高頻連點，給予廣告畫面加載略過按鈕（▶|）之時間。
  - 允許第 1 次與第 2 次正常流程跳轉；僅當同一座標區域累積連跳達 3 次無效時才暫時加入節流黑名單。

### 2. Meta 廣告 (Trip.com) 左上角 X 優先辨識與廢除右上角盲點
- **檔案**：`AdCloserHandler.kt`
- **問題分析**：Meta 廣告（如 Trip.com）之實體關閉鈕位於畫面左上角，但 4D 網頁預覽邏輯中先前設定了 `includeLeftZone = false`，導致未能辨識左上角 X，並在幾何匹配失敗後盲點右上角「以外部瀏覽器開啟」熱區，引發 100% 誤跳轉三星瀏覽器。
- **實作細節**：
  - 4D Meta 預覽層開放 `includeLeftZone = !disableLeftCornerForCurrentAd`，第一時間精確命中並點擊左上角幾何 X。
  - 徹底廢除 `previewCloseRect` 右上角盲點備援，改由安全系統返回鍵關閉預覽層。

### 3. 全螢幕宣傳頁角落關閉無效時返回鍵防穿透保護
- **檔案**：`AutomationEngine.kt`
- **問題分析**：宣傳頁連續 3 次點擊無效時發送系統返回鍵，若當前已處於遊戲主畫面，返回鍵會穿透將散步趣主頁退至 Samsung Launcher 桌面。
- **實作細節**：
  - 於發送安全返回鍵前加入畫面檢驗：`service.safeNavigateBack(allowInGame = !isGameScreen)`，當處於遊戲主畫面時嚴禁發送返回鍵，杜絕穿透退出至系統桌面。

---

## 版本資訊 (歷史版本: v1.15.78)
- 版本名稱：v1.15.78
- 版本代碼：versionCode 111, versionName "1.15.78"
- 發布類型：正式版本 (Release)

---

## 概述 (v1.15.78)
LineWalkAutoAd v1.15.78 針對使用者提供的最新診斷日誌進行深度分析與改善：修復 Meta Sponsored (如 Trip.com) 頂部白底廣告之左上角 X 座標計算偏差與跳轉後左上角永久封鎖死鎖問題，大幅優化幾何 X 偵測容差並引進冷卻重試機制；新增畫面中下方廣告關閉按鈕專用辨識器，支援簡體關閉關鍵字與中下方水平控制欄快速點擊；並針對 Android 17 / Samsung One UI 健全化保護機制。

---

## 版本資訊
- 版本名稱：v1.15.77
- 版本代碼：versionCode 110, versionName "1.15.77"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.77 針對使用者回報的「每日賓果視窗開啟後立即秒關跳往抽抽樂」問題（日誌顯示因頂部綠色橫幅倒數計時文字誤將冷卻狀態判為 true，進而忽略畫面中央亮起的橘色「立即開啟賓果格」按鈕）進行根除修復：重構每日賓果處理流程，改以畫面實體橘色按鈕為第一優先判定，只要按鈕就緒即優先點擊開格；僅在確定無開格按鈕可點時，才檢查連線獎勵 [Get] 並關閉視窗，確保每日賓果 100% 正常開格與領獎。

---

## 具體更新與修復內容 (v1.15.77)

### 1. 每日賓果橘色「立即開啟賓果格」按鈕絕對優先點擊
- **檔案**：`AutomationEngine.kt`
- **問題分析**：在舊版邏輯中，`handleBingoDialog` 在呼叫 `findOpenCellButton` 前先進行了 `isBingoCoolingDown` 判斷。因賓果頂部綠色橫幅常駐標示「距離下次抽選還有 XX 分鐘 XX 秒」與「30 分鐘啟動 1 次...」，導致 `isCoolingDown` 永遠為 true，`findOpenCellButton` 遭到提前阻斷，致使畫面中央明明存在橘色開格按鈕卻被忽視，並在無連線獎勵時於第 1 秒立即關閉視窗。
- **實作細節**：
  - 移除 `findOpenCellButton` 前置之 `!isCoolingDown` 阻斷條件，以畫面上是否存在亮橘色開格按鈕為第一優先基準。
  - 當 `openCellBtn != null` 時，立即優先點擊橘色按鈕進行開格。
  - 僅當 `openCellBtn == null`（畫面上無可點擊之開格按鈕）時，才標記為冷卻狀態並檢查連線 [Get] 獎勵。

### 2. 冷卻特徵文字篩選精準化
- **檔案**：`BingoTask.kt`
- **實作細節**：
  - 移除 `isBingoCoolingDown` 中對靜態遊戲規則說明「30分鐘啟動」的比對，避免說明字樣產生干擾。

---

---

## 概述
LineWalkAutoAd v1.15.76 針對使用者回報的「LINE 內部促銷宣傳廣告無法關閉」問題（日誌記錄連續點擊 2 次後因硬編碼限制停止點擊並誤入黑名單，導致卡在廣告播放達 35 秒）進行徹底根除修復：引入頂部角落白色 (X) 動態像素掃描定位演算法、校準全機型基準幾何座標、徹底解除點擊次數硬編碼與黑名單誤鎖機制，並新增第 3 次起自動派發安全返回鍵 (`safeNavigateBack(allowInGame = true)`) 交叉脫困機制，保證 LINE 宣傳促銷廣告 100% 順暢關閉退回遊戲。

---

## 具體更新與修復內容 (v1.15.76)

### 1. LINE 內部促銷廣告右上角 (X) 按鈕動態像素掃描定位
- **檔案**：`OcrRecognizer.kt`
- **問題分析**：在 Samsung Galaxy Z Flip 5 (22:9 螢幕，1080x2640) 等長螢幕與摺疊手機上，宣傳彈窗頂部 (X) 按鈕中心高度受系統狀態列與長螢幕影響，原靜態幾何計算可能偏向按鈕邊緣，且進場動畫過渡期容易使點擊未被 WebView 接收。
- **實作細節**：
  - 新增 `findLineInHousePromoCloseButton(screenBitmap, sw, sh)`：動態掃描頂部右側角落 (`x in 0.85w..0.98w, y in 0.05h..0.15h`) 之白色筆畫像素簇 (RGB > 225)，精確計算白色 (X) 中心座標。
  - 基準幾何座標更新為中心 `(x = 92.0%, y = 9.25%)`，垂直涵蓋 `0.065h .. 0.120h`，相容 16:9、19.5:9、20:9 及 22:9 (Z Flip 5) 等所有長寬比手機。

### 2. 徹底解除點擊次數上限與黑名單誤鎖
- **檔案**：`AdCloserHandler.kt`
- **問題分析**：先前版本為防第三方廣告文案誤觸，硬編碼 `linePromoCloseClicks < 2` 且傳入 `allowBlacklist = true`。真實宣傳廣告若在動畫期間 2 次未關閉，按鈕即被加入黑名單並永久停止點擊，導致廣告監控陷入 30+ 秒無效滑動。
- **實作細節**：
  - 移除 `linePromoCloseClicks < 2` 硬限制，絕不黑名單此合法關閉按鈕。
  - 直接透過 `FloatingOverlayService.clickWithOverlayPassthrough` 派發手勢，豁免頂部安全禁區 (`allowTopSafeZone = true`)，確保手勢派發 100% 放行。

### 3. 雙重脫困：自動安全返回鍵 (safeNavigateBack)
- **檔案**：`AdCloserHandler.kt`
- **實作細節**：
  - 第 1、2 次點擊右上角 (X) 鈕；
  - 若連點 2 次仍未關閉（例如 WebView 觸控阻斷或進場動畫延遲），第 3 次起自動發送系統安全返回鍵 (`service.safeNavigateBack(minIntervalMs = 600L, allowInGame = true)`) 脫困關閉彈窗；
  - 往後交叉執行點擊 (X) 與返回鍵，徹底杜絕彈窗死鎖。

---

---

## 概述
LineWalkAutoAd v1.15.72 針對長時間掛機診斷日誌（7,267 筆日誌）與實機截圖進行深度分析與根除修復：徹底解決通宵掛機卡在「打開任務面板與關掉任務面板」無限死循環（連續 1,588 次，長達 3.5 小時）；重構頂部巡邏判定層級，嚴格加入子彈窗互斥防禦；全面重構 `LotteryTask` 抽抽樂中獎判定，徹底排除任務、賓果與好友面板，移除寬鬆泛用「獎」字與孤立「x1」比對；新增雙重防死鎖斷路器（任務重複開啟達 5 次強行推進、抽抽樂結果視窗關閉連續嘗試達 5 次自動重設並發送 BACK 鍵）；實作跨階段抽抽樂暫存狀態完整重設。

---

## 具體更新與修復內容 (v1.15.72)

### 1. 任務視窗與抽抽樂中獎結果互斥防護
- **檔案**：`AutomationEngine.kt`, `LotteryTask.kt`
- **問題分析**：在巡邏循環中，頂部 A-1 抽抽樂中獎結果判定優先於任務彈窗判定。當任務彈窗開啟且切換至「每日」分頁時，因每日任務列表中包含票券圖示文字「x1」，且任務描述包含「在首頁領取獎勵」，被 A-1 誤判為抽抽樂中獎結果視窗，進而呼叫關閉彈窗將任務視窗強行關閉，造成「主畫面開啟任務 -> 誤判為抽抽樂結果 -> 關閉任務視窗」連續循環 1,588 次。
- **實作細節**：
  - 在 A-1 抽抽樂重疊廣告判定前置加入 `isOtherSubDialogOpenForLottery` 嚴格互斥條件，確認非任務視窗 (`isMissionDialogOpen`)、賓果視窗 (`isBingoDialogOpen`)、好友視窗 (`isFriendsListOpen`) 或禮物抽屜 (`isReceiveGiftDialogOpen`)，才允許抽抽樂邏輯介入。
  - 當處於任務彈窗時，直接由專屬 `handleMissionDialog` 接手處理，徹底杜絕抽抽樂邏輯誤攔截。

### 2. 抽抽樂中獎結果判定精確化與否定排除
- **檔案**：`LotteryTask.kt`
- **實作細節**：
  - 增加任務面板否定排除：若頂部包含「任務」文字，或包含「特別」「每日」「每週」標籤，一律回傳 `false`。
  - 增加賓果與好友面板否定排除（「每日賓果」「好友清單」等）。
  - 刪除寬鬆之 `(t.contains("獎") && !t.contains("獎項"))`，改為嚴格正則 `Regex("""([1-9]|10|特|頭)獎""")` 與 `BIG`、`枚金幣中獎`、`中獎囉`，防止「在首頁領取獎勵」等字樣誤觸。
  - 移除孤立「x1」匹配：要求必須存在中獎抬頭 (`hasRankHeader`) 才能比對中下部獲票特徵。
  - `findLotteryRewardAdButton` 備援策略排除含有「任務」「挑戰」「領取」之項目。

### 3. 任務視窗重複開啟防死鎖斷路器 (Mission Reopen Circuit Breaker)
- **檔案**：`AutomationEngine.kt`
- **實作細節**：
  - 新增 `missionOpenCycleCount` 追蹤單輪巡邏中開啟任務面板次數。
  - 若在單輪巡邏中重複開啟任務面板達 5 次未能正常完結，立即觸發斷路器保護：`appendLog("[任務斷路器] 任務視窗單輪重複開啟達 5 次未能順利完結，觸發防死鎖斷路保護，強制推進至下一階段！")`，強制標記任務完成並推進至下一階段，杜絕無窮迴圈。
  - 任務面板正常檢查完畢或全域狀態重設時自動歸零。

### 4. 抽抽樂結果視窗關閉連續失敗斷路器
- **檔案**：`AutomationEngine.kt`
- **實作細節**：
  - 在 `handleLotteryDialog` 中，若 `lotteryResultCloseAttempts >= 6`，觸發斷路器：清空 `hasClickedLotteryAdReward = false` 與 `lotteryResultCloseAttempts = 0`，並發送系統安全返回鍵 (BACK) 退出殘留彈窗，徹底防止「(嘗試: 1588)」無限累加。

### 5. 跨階段抽抽樂暫存狀態完整重設
- **檔案**：`AutomationEngine.kt`
- **實作細節**：
  - 新增 `resetLotterySessionState()` 方法，每次由 `PatrolPhase.LOTTERY` 推進至下一階段時，自動強制重設 `hasClickedLotteryAdReward`、`lotteryResultRewardWaitTicks`、`lotteryResultCloseAttempts`、`lotteryDialogCloseAttempts`，防止狀態旗標外洩至任務或後續階段。

---

## 版本資訊 (歷史版本)
- 版本名稱：v1.15.71
- 版本代碼：versionCode 104, versionName "1.15.71"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.71 針對通宵掛機診斷日誌（18,045 筆日誌）與 3 張實機截圖進行深度分析與根除修復：徹底解決散步趣抽抽樂 8 獎中獎彈窗與背景廣告重疊 Bug（依使用者實機指導：重疊時只能點擊中獎彈窗底部的 [關閉] 按鈕，再點擊廣告右上角 (X) 退出回到遊戲；背後無廣告則點擊綠色看廣告獲票按鈕）；根除酷澎 Coupang 廣告 Node[txt=X] 連點 1135 次死循環；嚴禁「秒後可獲獎勵」倒數提早點擊膠囊內的 X，並在誤觸確認彈窗時優先點擊 [繼續] 恢復播放領獎；優化三星瀏覽器極速拉回。

---

## 具體更新與修復內容 (v1.15.71)

### 1. 抽抽樂中獎結果彈窗與背景廣告重疊 Bug 修復
- **檔案**：`LotteryTask.kt`, `AutomationEngine.kt`
- **問題分析**：散步趣已知 Bug 在播放全螢幕廣告時，抽抽樂中獎結果視窗可能擋在廣告前面。若背後有廣告，點擊綠色看廣告按鈕會被擋住或失效，點擊廣告右上角 (X) 則會被前景中獎視窗攔截（日誌連續狂點 111 次無效）。
- **實作細節**：
  - 移除「完成任務 / 大量金幣」一票否決條件，使 8 獎中獎彈窗能 100% 精準識別。
  - 新增 `hasBackgroundAdBehindLotteryResult` 偵測中獎彈窗背後是否露出廣告特徵（如「還沒有邀請好友」、「實在太可惜了」等）。
  - 當偵測到中獎彈窗背後有廣告重疊時，**只能點擊中獎彈窗底部的 [關閉] 按鈕**；中獎彈窗關閉後畫面露出廣告本體，下一個 Tick 由標準廣告關閉流程點擊廣告右上角 (X) 退出回到遊戲。
  - 若中獎彈窗背後無廣告（正常抽獎中獎），維持原最佳策略：**優先點擊綠色 [▶ x1 獲得] 按鈕觀看廣告多拿一張票券**。
  - 加入 `!lotteryTask.isLotteryWinResultScreen` 守護，前景存在中獎視窗時嚴禁誤點背景廣告右上角 (X)。

### 2. 根除酷澎 Coupang 廣告 Node[txt=X] 連點 1135 次死循環
- **檔案**：`AutomationEngine.kt`
- **問題分析**：Coupang 廣告加載時，無障礙節點樹存在無效的頂部細線 `Node[txt=X]`，真正的關閉按鈕是正下方灰色圓底白色粗體 `(X)`。D2 區塊缺乏斷路器，導致日誌連續空點多達 1135 次（35 分鐘）、599 次（20 分鐘）、573 次（18 分鐘）。
- **實作細節**：
  - 新增無障礙節點點擊斷路器 `closeNodeConsecutiveClicks`，若同一節點連續點擊達 3 次未響應，立即將該座標列入黑名單。
  - 新增 Coupang 專屬備援策略：在連續無效時，向下偏移 80px 點擊圓形粗體灰底 `(X)` 退出。
  - 連續 4 次未關閉發送安全返回鍵，連續 5 次強制拉回散步趣。

### 3. 嚴禁「秒後可獲獎勵」倒數提早點 X & 確認彈窗優先點 [繼續]
- **檔案**：`OcrRecognizer.kt`, `AdCloserHandler.kt`, `AutomationEngine.kt`
- **問題分析**：糖果傳奇廣告前 4 秒左上角膠囊顯示 `[ X 56 秒後可獲獎勵 ]`，無障礙節點搜尋提早點擊膠囊內的 `X`，觸發中斷確認彈窗「關閉廣告？您將無法獲得獎勵」。隨後巡邏掃描將標題文字誤當成關閉按鈕，每 2 秒狂點標題文字一次（連續點擊 104 次、93 次、91 次死鎖）。
- **實作細節**：
  - 在 `OcrRecognizer.kt` 新增 `detectRewardPendingCountdownSeconds`，擴充倒數正則匹配 `(\d{1,2})\s*秒(?:後)?(?:可獲|發放|獲得|可獲得)?獎勵`。
  - 在 `AdCloserHandler.kt` 將倒數計時判定提前至步驟 3B（所有略過/關閉節點與幾何 X 之前），倒數未完直接回傳 `StillCountingDown`，嚴禁提早點擊。
  - 在 `DIALOG_DISMISS_EXCLUDE_KEYWORDS` 與 `GAME_CLAIM_EXCLUDE_KEYWORDS` 中加入 `"關閉廣告"`, `"無法獲得"`, `"放棄獎勵"`，徹底禁止將確認彈窗標題誤當成關閉按鈕。
  - 在 D0 區塊攔截「中斷防棄看彈窗」：優先點擊藍色按鈕 `[繼續]` 恢復廣告播放領取獎勵，若無則點擊右下角按鈕區的 `[關閉]`。

### 4. 三星瀏覽器 (sbrowser) 極速拉回散步趣
- **檔案**：`AutomationEngine.kt`
- **實作細節**：在 `handleExternalAppGuard` 中，若連續 2 次返回未退出三星瀏覽器，立即直接呼叫 `bringAppToFront("com.linecorp.LGWKTW")` 拉回散步趣，脫困時間縮短至 4 秒以內。

---

## 版本資訊 (歷史版本)
- 版本名稱：v1.15.70
- 版本代碼：versionCode 103, versionName "1.15.70"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.70 為掛機穩定性修復版本：根除實機日誌 `散步趣助手_v1.15.69_診斷日誌_20261108_010457.txt` 中掛機卡在抽抽樂「BIG 8獎 100 枚金幣中獎囉！」結果彈窗長達 6 分鐘的無限循環；封鎖 InMobi 廣告左上角幾何 `(X)` 連續 4 次誤點跳轉瀏覽器；限制 `LINE宣傳廣告關閉(X)` 在同一支廣告連點 35 次的問題。

---

## 具體更新與修復內容 (v1.15.70)

### 1. 修復掛機卡在抽抽樂「BIG 8獎 中獎囉！」結果彈窗
- **檔案**：`LotteryTask.kt`, `AutomationEngine.kt`
- **根因**：從廣告返回時抽獎箱開啟動畫遮住 `50張 / 免費抽 / 5張` 按鈕，`areBothDrawsUnavailable` 誤判為三者皆反灰，第 1 個 Tick 即標記 `isLotteryCompleted = true`；之後結果彈窗浮現，引擎卻一直點擊被模態彈窗遮擋的背景右上角 `(X)` 與底部【集點】，且第 6 次後計數歸零造成無限循環。
- **實作細節**：
  - `AutomationEngine` 抽抽樂區塊在處理完成狀態前，先處理前景領獎彈窗；若前景仍為中獎結果彈窗，撤銷完成標記並交由 `handleLotteryDialog` 點擊 `[x1 獲得]` 或 `[關閉]`。
  - `handleLotteryDialog` 結束判定改為至少連續 3 個空 Tick。
  - `isLotteryWinResultScreen` 新增「頂部 N獎 / 枚金幣 + 中下部 `[x1 獲得]` / 底部 `[關閉]`」複合特徵與實心綠色膠囊像素特徵，並忽略彈窗下方露出的「BIG 獎項一覽」。
  - `areBothDrawsUnavailable` 在偵測到結果彈窗、前景 `[關閉]` / `[分享給好友]`，或看不到任何主面板特徵時，一律不判定為反灰。
  - 關閉脫困順序：前景 `[關閉]` -> 右上角 `(X)` -> 上方遮罩 -> 底部【集點】 -> 安全返回鍵 (第 7、8 次)，不再歸零循環。

### 2. 封鎖左上角幾何 X 連續誤點跳轉瀏覽器
- **檔案**：`AdCloserHandler.kt`, `AutomationEngine.kt`
- **實作細節**：同一支廣告若點擊左上角後觸發外部跳轉 (`sbrowser` 等)，立即封鎖整個左上角區域；非 Meta 廣告左上角幾何 `(X)` 最多嘗試 2 次。

### 3. 限制 LINE 宣傳廣告關閉 (X) 連點
- **檔案**：`AdCloserHandler.kt`
- **實作細節**：`LINE宣傳廣告關閉(X)` 最多嘗試 2 次並檢查黑名單，未關閉即交由標準廣告關閉流程處理。

### 4. 廣告倒數秒數防誤判
- **檔案**：`OcrRecognizer.kt`, `AutomationEngine.kt`
- **實作細節**：頂部純數字倒數必須位於左右兩側角落；抽抽樂等子視窗內不再因內嵌文字誤進廣告監控模式。

---

## 歷史紀錄 (v1.15.69)

### 1. 全機型螢幕比例 (摺疊機 22:9 / 21:9、16:9、全螢幕手勢、傳統三大金剛鍵) 自適應防誤點架構
- **檔案**：`ScreenViewportCalibrator.kt`, `BingoTask.kt`, `LotteryTask.kt`, `MissionTask.kt`, `ReceiveGiftTask.kt`, `FriendGiftTask.kt`, `AdCloserHandler.kt`
- **實作細節**：
  - 自動偵測底部傳統三大金剛導覽列 (`hasThreeButtonNavBar`) 並建立安全禁區，防止誤觸系統返回/首頁/多工鍵；
  - 子視窗（每日賓果、抽抽樂、任務、收禮）右上角 `(X)` 採用像素級「白底圓角面板頂部邊界 (`sheetTopY`) + 右上角深灰色 `(X)` 筆畫中心」動態定位，並支援多點微調輪詢與強制脫困。

### 2. 修復附圖左上角 `[▶| Google Play]` 膠囊與 `CACO` 左上角圓形 `(X)` 廣告無法關閉問題
- **檔案**：`AdCloserHandler.kt`, `TemplateMatcher.kt`, `NodeInspector.kt`
- **實作細節**：
  - 新增 `findTopLeftGooglePlaySkipPill`：播放滿 4 秒且無倒數計時時，精準點擊左上角 (`cy in 2.5%..18.0% sh, cx in 2.0%..42.0% sw`) 的 `[▶| Google Play]` 略過膠囊按鈕；
  - 開放所有無倒數計時廣告在播放滿 5 秒後啟用左上角幾何 `(X)` 偵測 (`enableLeftCornerScan`)，並將 `TemplateMatcher.detectCornerGeometricX` 左上角掃描寬度擴展至 `19.5% sw`。

### 3. 深度修復實機診斷日誌 (`1_15_68bug.txt`) 5 大異常
- **檔案**：`AdCloserHandler.kt`, `OcrRecognizer.kt`, `NodeInspector.kt`, `AutomationEngine.kt`
- **實作細節**：
  - 根除 `FacebookLandingPage` 因泛用詞造成的 69 秒無限死循環洗版；
  - 攔截 `Node[txt=Skip 開啟商店]` 與 `OCR[BOOK NOW ×]` 造成的外部商店/瀏覽器誤跳轉；
  - 修復抽抽樂中獎結果畫面重複點擊 `[x1 獲得]` 10 次問題，並將記憶體日誌容量上限擴充至 20,000 筆。

### 4. 日誌直接匯出為含版本號之 `.txt` 檔案至「檔案」App & 移除「取樣」功能
- **檔案**：`MainActivity.kt`, `FloatingOverlayService.kt`, `activity_main.xml`, `strings.xml`
- **實作細節**：
  - 點擊主畫面 `[匯出日誌 (.txt)]` 直接將完整無截斷日誌寫入系統 `Downloads` 資料夾，檔名自動加上版本號（`散步趣助手_v1.15.69_診斷日誌_yyyyMMdd_HHmmss.txt`）；
  - 移除懸浮窗與主畫面的舊版手動「取樣」按鈕與區塊。

---

## 歷史版本紀錄 (v1.15.68)

### 1. 修復每日賓果「1 跳過廣告糖果到手！」彈窗未關閉與綠旗黑字 Get 重複點擊
- **檔案**：`OcrRecognizer.kt`, `BingoTask.kt`, `AutomationEngine.kt`
- **實作細節**：
  - 擴充 `findCenterRewardModalCloseButton` 支援「糖果到手」、「跳過廣告糖果」、「到手！」等賓果與日常獎勵彈窗標題，並解除當前景出現中央白底 `[關閉]` 按鈕時被底層「每日賓果 / 距離下次抽選」一票否決的限制；
  - 在 `AutomationEngine` 處理賓果視窗前加入前景領獎彈窗優先檢查，確保先點掉中央 `[關閉]` 按鈕再操作或關閉賓果視窗；
  - 在 `BingoTask.findNextClaimableReward` 強制要求 OCR 匹配 `Get` 時必須同時具備珊瑚粉紅膠囊像素特徵 (`redPoints.size >= 8`)，徹底排除已領取後的「綠色小旗子 + 黑字 Get」。

### 2. 修復開啟抽抽樂 / 任務 / 每日賓果時一直點首頁與步數分頁卡死問題
- **檔案**：`AutomationEngine.kt`, `OcrRecognizer.kt`
- **實作細節**：
  - 重構 `ensureOnTabWithDailyIcons`：只要畫面上已出現「賓果 / 抽抽樂 / 糖果 / 任務」日常圖示或位於【首頁 / 步數 / 時間】看板，立即放行並直接點擊目標圖示；
  - 從遊戲分頁判定中徹底移除會在【首頁 / 步數 / 時間】下方輪播橫幅出現的 `POINTSGAME`、`LINEPOINTS`、`輕鬆賺`、`開始遊玩`，解決因下方橫幅導致誤以為在【遊戲】分頁而無限點擊【步數】分頁的死循環。

### 3. 修復 Trip.com Sponsored 左上角 X 關閉與根治廣告右上角略過鈕連點 6 次
- **檔案**：`AdCloserHandler.kt`, `TemplateMatcher.kt`
- **實作細節**：
  - 在 `AdCloserHandler` 中根據 `Sponsored` 標題列高度動態對齊左上角 `X` 點擊座標，並在 `isInvalidOrBlacklisted` 放行左側獨立關閉欄 (`x in 0.030w..0.118w`)，同時相容 `3M` (`y≈0.029h`) 與 `Trip.com` (`y≈0.067h`) 兩種版型，並嚴格封鎖 `x >= 0.118w` 的品牌圓形頭像與下方網址列；
  - 在 `TemplateMatcher.detectCornerFastForwardSkip` 將頂部掃描邊界調高至 `y >= 4.2%` 嚴格避開系統狀態列圖示，移除會誤判白底廣告右上角靜音喇叭與網址文字的「模式 B」，並限制單支廣告最多嘗試 2 次。

---

## 歷史版本紀錄 (v1.15.67)

### 1. 徹底根除「無法載入廣告」綠色像素誤判 & 支援「邀請好友 10000 金幣」宣傳頁關閉
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`
- **實作細節**：
  - 移除 `findAdLoadErrorCloseButton` 中無錯誤文字的純綠色像素盲猜分支，強制規定畫面上必須出現明確錯誤文字（「無法載入廣告」、「請稍後再試」等）才可判定為錯誤彈窗，並對「每日賓果」、「邀請好友」、「抽抽樂」與主畫面四大分頁設下一票否決保護；
  - 擴充 `isLineInHousePromoAd` 與 `getLineInHousePromoCloseButton`，支援「邀請好友 最高可獲 10000 金幣 快來領取吧」全螢幕宣傳頁，自動點擊右上角關閉按鈕 (X) 退回主畫面。

### 2. 送禮物與收禮物拆分為兩項獨立開關
- **檔案**：`AppConfig.kt`, `activity_main.xml`, `MainActivity.kt`, `ReceiveGiftTask.kt`, `AutomationEngine.kt`
- **實作細節**：
  - 新增 `enableReceiveGiftTask` 設定項與主畫面核取方塊 `cbReceiveGiftTask`（「9.【接收禮物】自動開啟禮物盒領取好友禮物」），與「8.【好友送禮】排行榜自動送禮 (優先送自己)」完全獨立運作。

### 3. 四大分頁單一項目徹底巡完再換頁 & 時間分頁優先點擊「紅利看廣告獲得一顆莓果」
- **檔案**：`GameTask.kt`, `AutomationEngine.kt`
- **實作細節**：
  - 提高主畫面分頁切換確認門檻至連續 3 個空 Tick，確保「移動、步數、時間、遊戲」單一分頁獎勵完全領取完畢後才切換至下一分頁；
  - 在 `IdleRewardTask` 新增 `findBonusBerryAdButton`：進入【時間】分頁時，第一優先偵測並點擊畫面上的「紅利看廣告額外獲得一顆莓果/蘋果」按鈕，完成後再點擊中間粉紅 `[獲得票券]` 按鈕收取成熟莓果。

### 4. 嚴格禁止點擊首頁下方的無關橫幅廣告
- **檔案**：`GameTask.kt`, `OcrRecognizer.kt`, `AutomationEngine.kt`
- **實作細節**：
  - 將「移動、步數、時間」分頁獎勵按鈕搜尋高度嚴格限制在 `y in 0.25f..0.365f`；
  - 在 `AutomationEngine` 加入主畫面硬性座標防護罩，嚴禁在主畫面點擊 `y in 0.445f..0.895f` 範圍，100% 封鎖「輕鬆賺 獲得金幣!! 開始遊玩」、酷澎「立即下載」與「瞭解詳情」等首頁下方廣告。

### 5. 修復每日賓果卡住不動與底部 [Get] 獎勵按鈕漏點
- **檔案**：`BingoTask.kt`, `AutomationEngine.kt`
- **實作細節**：
  - 放寬 `findNextClaimableReward` 對底部珊瑚粉紅 `[Get]` 膠囊按鈕之色彩與幾何範圍容差；
  - 點擊連線獎勵 `[Get]` 後自動轉入 `TRIGGER_AD_POPUP` 處理看廣告或領獎確認彈窗，並允許同一欄重試最多 3 次直到按鈕消失，解決賓果視窗卡住不動問題。

### 6. 抽抽樂新增支援「5張 抽1次」第三順位連抽
- **檔案**：`LotteryTask.kt`, `AutomationEngine.kt`
- **實作細節**：
  - 更新 `findTargetDrawButton` 與 `handleLotteryDialog` 優先順序為：第一優先「50張 抽1次」-> 第二優先「免費抽1次」-> 第三優先「5張 抽1次」；
  - 當 50 抽與免費抽皆冷卻或不可點且彩券達 5 張以上時，自動點擊橘色「5張 抽1次」按鈕並處理開獎結果視窗。

### 7. 全面強化廣告略過系統、禁止播放期間亂點、解決不可略過廣告提早退出
- **檔案**：`AdCloserHandler.kt`, `TemplateMatcher.kt`, `AppConfig.kt`
- **實作細節**：
  - 支援 Meta `Sponsored` 頂部白底橫幅左上角關閉按鈕 `X` (`x in 0.042w..0.095w, y in 0.018h..0.042h`)，同時嚴格封鎖 `y in 0.046h..0.150h` 的品牌頭像與網址列以及右上角靜音喇叭；
  - 嚴格限制快速略過符號 `>>` 僅匹配邊角獨立短標籤（長度 <= 6），防止誤點購物廣告內文之「雙11必買>>」；
  - 收緊點擊黑名單門檻至 2 次（同一區域點擊 2 次未關閉廣告即封鎖），並移除播放期間每 3.5 秒無差別滑動手勢；
  - 將不可略過廣告之結束卡片與備援返回等待門檻延後至 42~45 秒（預設最大等待上限提升至 65 秒），防止不可略過廣告提早退出導致拿不到獎勵。

---

## 歷史版本回顧

## 版本資訊
- 版本名稱：v1.15.66
- 版本代碼：versionCode 99, versionName "1.15.66"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.66 為徹底根除實機死循環與跨機型螢幕自適應之關鍵正式版本。本版本深度檢測並徹底解決 Samsung Galaxy Z Flip5 (SM-F731N, 22:9 超長螢幕) 於日誌回報之「無法載入廣告」連續 45 次死循環、時間放置蘋果收成「白底黑框紅利按鈕」因紅色素檢驗誤判反灰漏點、以及廣告錯誤彈窗按鈕在不同長寬比螢幕點偏等致命缺陷。全專案經經驗測試套件全數通過驗證，達成零死鎖、零誤殺與全機型螢幕自適應。

---

## 核心問題與根因分析

### 1. 廣告無庫存 (Ad Starvation) 引發「無法載入廣告」45次無限死循環
- **問題現象**：實機診斷日誌自 14:16:12 連續 45 次輸出「[錯誤] 偵測到「無法載入廣告」彈窗，自動點擊 [關閉] 按鈕並重試當前分頁！」，助手在同一分頁無限循環重複碰壁，巡邏完全卡死。
- **根因分析**：在伺服器暫無廣告庫存或限流時，點擊領取會立即彈出「無法載入廣告 請稍後再試。」彈窗。舊版程式碼在偵測到錯誤彈窗時，直接點擊關閉並重置計數 (`emptyTicksOnCurrentTab = 0`, `confirmedEmptyTabs.remove(currentTab)`)，且未接入點擊斷路器，亦未對載入失敗次數設置上限；關閉彈窗後立刻在原分頁重試，導致伺服器秒彈錯誤，陷入永久死循環。

### 2. 白底黑框「紅利 [▶] x1 獲得」按鈕因紅色素過低遭反灰誤殺 (Zero False-Negative)
- **問題現象**：時間分頁蘋果成熟點擊「獲得票券」後彈出下半部領獎視窗，畫面上具備鮮紅「紅利」標籤之黑框白底按鈕，但助手卡在彈窗前不再點擊，懸浮窗停滯。
- **根因分析**：`TemplateMatcher.kt` 之 `isPopupButtonDisabledGray` 採用全粉紅按鈕檢驗邏輯 (`redRatio < 0.15f || disabledRatio > 0.65f`)。然而時間分頁蘋果收成按鈕本體為白底黑框，內部紅色素比例極低 (約 1%)，導致 `isPopupButtonDisabledGray` 將其誤判為「反灰不可點」。`findPopupBonusBadgeAdButton`、`AutomationEngine`、`OcrRecognizer` 與 `AdCloserHandler` 多重誤殺該按鈕，使其完全無法被觸發點擊。

### 3. 22:9 超長螢幕錯誤彈窗關閉按鈕採樣點偏
- **問題現象**：在 Samsung Galaxy Z Flip5 (SM-F731N, 22:9 超長螢幕) 上，廣告錯誤彈窗綠色 [關閉] 按鈕有時未能精確命中。
- **根因分析**：舊版按鈕外框採樣 Y 軸區間 (0.62f..0.72f) 偏窄，在超長螢幕下未能完全覆蓋按鈕外框；且依賴固定比例 fallback Rect，缺乏基於輪框像素之動態外框計算。

### 4. 廣告載入黑幕被主畫面文字一票否決
- **問題現象**：廣告向伺服器緩衝時，畫面黑幕未被及時識別為載入中，提早觸發了超時或誤判。
- **根因分析**：`isAdLoadingScreen` 將 `isGameModalOrResult` 優先級置於文字載入檢驗之前，導致半透明黑幕透出背景「獲得金幣」等字樣時被一票否決。

---

## 具體修復方案

### 1. 分頁廣告無庫存守護 (Ad Starvation Guard) 與斷路器聯防
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- **實作細節**：
  - 導入分頁廣告錯誤計數器 `adLoadErrorCountPerTab: MutableMap<MainTab, Int>`；
  - 接入 `checkClickCircuitBreaker`：若同一個錯誤彈窗連續點擊達 3 次無效，發送系統返回鍵破除死鎖並強制換頁；
  - 實作無庫存守護：同一分頁廣告載入失敗累計達 2 次時，判定該分頁當前無庫存，點擊關閉後自動發送安全返回，並直接呼叫 `forceAdvanceToNextTab` 推進至下一分頁，維持全自動巡邏不中斷；
  - 徹底移除 `emptyTicksOnCurrentTab = 0` 與 `confirmedEmptyTabs.remove(currentTab)`。

### 2. 白底黑框紅利按鈕誤殺全面解禁
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt`, `app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`, `app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`, `app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- **實作細節**：
  - 在 `findPopupBonusBadgeAdButton` 中移除對全粉紅按鈕專用之 `isPopupButtonDisabledGray` 的錯誤調用；
  - 白底黑框按鈕只要具備鮮艷粉紅「紅利」標籤（`matchedRows >= 3`），即代表獎勵有效可領取；
  - 按鈕 Rect 適配全螢幕寬度：X 座標擴展至 `0.15f..0.85f`，高度依比例自適應居中；
  - 同步清理 `AutomationEngine`、`OcrRecognizer` 與 `AdCloserHandler` 中對 `badgeBtn` 的 `isPopupButtonDisabledGray` 二次誤殺。

### 3. 全機型螢幕動態邊界自適應
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`
- **實作細節**：
  - 將綠色按鈕採樣 Y 軸區間拓展為 `0.58f..0.76f`，X 軸 `0.15f..0.85f`，完美覆蓋 16:9 至 22:9 所有螢幕比例；
  - 動態統計綠色像素輪框邊界（`greenButtonMinX`, `greenButtonMaxX`, `greenButtonMinY`, `greenButtonMaxY`），動態輸出精確按鈕 Rect，徹底消除點偏；
  - 擴大錯誤提示關鍵字：新增「無法播放」、「廣告播放失敗」、「連線逾時」。

### 4. 廣告載入黑幕優先級重構
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`
- **實作細節**：
  - 將 `hasLoadingText`（「載入中」、「廣告載入」、「載入時間過長」、「重新啟動應用程式」等）提升為最優先判定，不再受背景透出文字一票否決。

---

## 歷史版本回顧

### 版本資訊
- 版本名稱：v1.15.65
- 版本代碼：versionCode 98, versionName "1.15.65"
- 發布類型：正式版本 (Release)

---

## 概述
LineWalkAutoAd v1.15.65 為全機型通用自適應重大更新版本。本版本徹底解決近期於 Samsung Galaxy Z Flip5 (SM-F731N, 22:9 超長螢幕) 及各類長寬比裝置上所回報之主畫面巡邏死鎖、Meta 廣告略過按鈕未匹配、左上角盲點誤喚起外部瀏覽器、底部導覽列遭廣告橫幅遮擋等核心缺陷。全專案經 636 項經驗回歸測試與對抗性壓力測試全數通過驗證，達成零死鎖、零誤觸與全機型螢幕自適應。

---

## 核心問題與根因分析

### 1. 遊戲分頁遮罩誤判造成主畫面巡邏死鎖
- **問題現象**：勾選「遊戲」分頁後啟動巡邏，助手進入遊戲分頁後卡死停滯，不再執行任何點擊或換頁。
- **根因分析**：遊戲分頁主介面具備大面積深色漸層邊框，在視覺分析模組中被 `isDimmedCenterModalOpen` 誤判為「半透明彈窗遮罩開啟」，導致主畫面判定 `isLineWalkMainScreenVisible` 返回 false。巡邏狀態機誤判當前處於未知彈窗畫面，進入等待關閉循環，但畫面上並無任何彈窗關閉按鈕可點，因而引發永久空轉死鎖。

### 2. 巡邏狀態機缺乏逾時自癒機制
- **問題現象**：當遭遇非預期畫面或辨識落空時，懸浮窗停滯無更新，日誌在長達 100 秒內無任何輸出。
- **根因分析**：巡邏主循環在非主畫面狀態下缺乏主動逾時推進機制；若某一分頁獎勵點擊後無反饋且未換頁，狀態機無法主動跳轉至下一分頁，造成流程永久停滯。

### 3. Meta 廣告快轉略過按鈕在 22:9 螢幕 y=32 偏移未匹配
- **問題現象**：播放 Meta/Facebook 廣告時，右上角出現的倒數結束快轉雙箭頭按鈕 (`>>`) 未被識別點擊，廣告卡在結束卡片直至逾時。
- **根因分析**：在 Samsung Galaxy Z Flip5 (1080x2640, 22:9) 超長螢幕下，快轉按鈕因頂部系統狀態列排版擠壓，中心座標落於頂部邊緣 (y=32, 22:9 解析度對應 y=66)，超出原幾何搜尋窗 `topLimitY` 與半徑檢測範圍；且舊演算法採單向固定對比，在白底黑圖示或淺底深圖示時對比失效，外環對稱性不足亦易將 momo 促銷標籤等圖示產生幾何混淆。

### 4. 廣告備援盲點誤觸外部瀏覽器
- **問題現象**：廣告倒數結束後，助手在嘗試備援盲點點擊關閉時，突然跳轉喚起三星瀏覽器 (`com.sec.android.app.sbrowser`) 並開啟外部網頁。
- **根因分析**：舊版廣告備援盲點序列中，第一首選點包含螢幕左上角熱區 (x=0.08w, y=0.04h)。在特定 Meta 廣告格式中，左上角恰為廣告主官方粉絲專頁頭像或品牌連結，盲點擊中後直接觸發外部 Intent 跳轉至瀏覽器。

### 5. 22:9 底部導覽列被廣告橫幅遮擋
- **問題現象**：在 22:9 螢幕上執行好友送禮時，助手點擊底部導覽列切換「好友」分頁落空，反而點擊到浮動橫幅廣告。
- **根因分析**：超長螢幕下 LINE 散步趣底部常駐廣告橫幅高度上移，舊版備援座標固定於 `sh * 0.910f`，正好命中廣告橫幅邊界，未能有效避開。

### 6. directOcr 範圍過廣受廣告文案干擾
- **問題現象**：主畫面頂部分頁切換偶發點擊偏誤或未按預期順序切換。
- **根因分析**：頂部分頁 directOcr 辨識範圍過寬，且未對文字長度進行限制，導致遊戲內活動輪播橫幅中的促銷文案（例如包含「時間倒數」、「限定遊戲」等長文字標題）被誤採集為分頁導覽標籤，計算出錯誤的基準錨點。

---

## 具體修復方案

### 1. 遊戲分頁遮罩一票否決與領獎彈窗豁免 (F1)
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/OcrRecognizer.kt`
- **實作細節**：
  - 在 `isLineWalkMainScreenVisible` 中加入 `isGameTabScreen` 一票否決邏輯：若畫面具備遊戲分頁特徵標籤（「秘寶挖挖哇」、「數獨達人」、「接龍高手」等），禁止 `isDimmedCenterModalOpen` 將主畫面判定為 false。
  - 在 `findCenterRewardModalCloseButton` 中加入 `!hasExplicitTicketOrCoinModalTitle` 豁免條件：當遊戲分頁中真正跳出票券或金幣獲得彈窗時，彈窗標題具有明確關鍵字，此時豁免一票否決，確保領獎彈窗能被正確識別並秒速點擊 (X) 關閉。

### 2. 巡邏防死鎖四層自癒守護與懸浮窗即時同步 (F2)
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`、`app/src/main/java/com/linewalk/autoad/ui/FloatingOverlayService.kt`
- **實作細節**：
  - **Tier 1 (心跳日誌守護)**：非主畫面輪詢每 6 個 Tick (~3 秒) 輸出診斷日誌，杜絕無日誌空轉。
  - **Tier 2 (降級重測機制)**：具備主畫面特徵且連續 6 個 Tick 無任何彈窗可點時，判定為遮罩誤判，降級放行執行主畫面常規巡邏。
  - **Tier 3 (主動換頁推進守護)**：在主分頁巡邏階段，若停滯於同一分頁達 15 個 Tick (~7.5 秒) 無任何操作推進，自動將當前分頁標記為完成，並強制觸發換頁切換至下一分頁。
  - **Tier 4 (嚴重逾時安全返回)**：若未知畫面停留達 20 個 Tick (~10 秒)，自動發送系統安全返回鍵 (`safeNavigateBack`)，將畫面拉回散步趣主流程。
  - **懸浮窗動態同步**：`FloatingOverlayService` 狀態更新全面依據 `uiStatus.currentTab` 動態呈現當前分頁名稱，徹底解決分頁顯示退步問題。

### 3. Meta 廣告快轉略過按鈕搜尋窗適配與雙向對比篩選 (F3)
- **檔案**：`app/src/main/java/com/linewalk/autoad/vision/TemplateMatcher.kt`
- **實作細節**：
  - **搜尋窗放寬**：將快轉按鈕搜尋範圍放寬至 `leftLimitX = sw * 0.72f`、`topLimitY = max((sh * 0.015f).toInt(), 20)`，垂直搜尋起點對齊內環半徑 `rInner`，完整涵蓋頂部狀態列 y=32 (22:9 螢幕 y=66)。
  - **自適應雙向對比**：實作模式 A（深底白圖示，`ringMean < 115f`）與模式 B（淺底深圖示，`ringMean > 200f && innerMin < 160f`），並引入膠囊填充率加權評分。
  - **幾何對稱性驗證**：建立外環 12 點取樣圓形對稱性檢查 (`ringDiff < 35f && maxSymDiff < 30f && hvDiff < 20f`)，在真實錄影幀 `frame_25.jpg` 上精確命中真實略過按鈕 (429, 32)，且 100% 否決 momo 促銷標籤 (407, 82)。

### 4. 純右側安全盲點序列重構 (F4)
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AdCloserHandler.kt`
- **實作細節**：
  - 徹底移除左上角熱區盲點，重構為純右側安全序列：
    1. 首選：右上角頂部 (0.91w, 0.045h)
    2. 次選：右下角底部 (0.91w, 0.920h)
    3. 備援：內容右上角 (0.95w, 0.080h)
  - 確保所有盲點點擊之 X 軸比例皆大於等於 0.86w，徹底消除點擊左上角廣告主專頁頭像喚起三星瀏覽器之風險。

### 5. 22:9 超長螢幕底部導覽列動態 Y 座標適配 (F5)
- **檔案**：`app/src/main/java/com/linewalk/autoad/tasks/FriendGiftTask.kt`
- **實作細節**：
  - 依裝置長寬比動態計算 `aspect = sh.toFloat() / sw.toFloat()`。
  - 當 `aspect >= 2.2f`（如 22:9 螢幕，aspect ~ 2.44）時，將備援 Y 錨點調整為 `sh * 0.945f`（平移 92px），精準避開 0.910f 上方廣告橫幅。
  - 一般螢幕長寬比（16:9, 19.5:9）維持 `sh * 0.910f`，兼顧通用性與超長螢幕防護。

### 6. 四大分頁 OCR 搜尋範圍與文字長度收斂 (F6)
- **檔案**：`app/src/main/java/com/linewalk/autoad/engine/AutomationEngine.kt`
- **實作細節**：
  - 將四大分頁辨識之垂直搜尋範圍嚴格限制於 `cy in 0.07f..0.16f`，且限定文字長度 `t.length <= 4`。
  - 徹底隔離畫面中段輪播橫幅與促銷文案干擾，確保四大分頁錨定精準穩定。

---

## 全螢幕長寬比適配驗證

| 螢幕規格 | 代表機型 | 長寬比 (Aspect) | 底部導覽列錨點 | 四大分頁 Y 基準線 | Meta 略過按鈕適配 |
|---------|---------|----------------|---------------|------------------|-------------------|
| 1080x2640 | Samsung Galaxy Z Flip5 | 2.44 (22:9) | Y = 0.945f (避開廣告) | 動態 OCR 錨定 / 0.125f | 精準命中 y=32 (偏移 3px 內) |
| 1080x2400 | 主流 Android 旗艦 | 2.22 (20:9) | Y = 0.945f (避開廣告) | 動態 OCR 錨定 / 0.125f | 精準命中右上角安全窗 |
| 1080x1920 | 標準 16:9 裝置 | 1.78 (16:9) | Y = 0.910f (常規錨定) | 動態 OCR 錨定 / 0.125f | 精準命中右上角安全窗 |

---

## 經驗測試套件驗證數據
- **執行命令**：`python -m pytest tests_empirical/`
- **測試總數**：636 項測試案例
- **執行結果**：
  - 633 passed (通過)
  - 3 skipped (跳過，需外接手機硬體之端到端測試)
  - 0 failed (失敗)
  - 測試耗時：約 4.0 秒
- **測試覆蓋模組**：
  - F1/F2 主畫面巡邏死鎖與自癒守護：`test_v1_15_65_m1_fixes.py`、`test_challenger_m1_*.py`
  - F3/F4/F5/F6 螢幕自適應與 Meta 略過：`test_v1_15_65_m2_screen_adaptation.py`、`test_challenger_m2_*.py`
  - 歷史回歸相容性測試：v1.15.45 至 v1.15.64 全版本回歸套件全數維持綠燈通過。

---

## 程式碼品質與規範遵循
- **繁體中文語言遵循**：所有代碼修改、註解、日誌輸出、測試案例名稱與本發布說明均 100% 使用繁體中文。
- **零 Emoji 規範**：全專案原始碼、註解、Git 變更集及本發布說明經嚴格字元掃描，完全不含任何 Emoji（0 個 Emoji）。
- **架構相容性**：遵循最小變更原則，未引入多餘依賴，相容 Android 11+ (API 30~34)。
