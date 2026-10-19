@echo off
chcp 65001 >nul
echo ========================================================
echo   《LINE 散步趣》自動看廣告助手 - 雲端打包 APK 推送工具
echo ========================================================
echo.
echo 請先至 https://github.com/new 建立一個空白的儲存庫 (Repository)
echo (建議設為 Private 私人儲存庫，不需勾選 Add a README)
echo.
set /p REPO_URL="請貼上你的 GitHub 儲存庫網址 (例如 https://github.com/你的帳號/LineWalkAutoAd.git): "

if "%REPO_URL%"=="" (
    echo [錯誤] 未輸入網址！
    pause
    exit /b 1
)

git remote remove origin >nul 2>&1
git remote add origin %REPO_URL%
git branch -M main
echo.
echo 正在推送程式碼至 GitHub 並觸發雲端自動打包 APK...
git push -u origin main

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo ✅ 推送成功！
    echo 請打開你的 GitHub 儲存庫網頁，點擊頂部的 [Actions] 頁籤，
    echo 等待約 2~3 分鐘亮綠燈後，點進去即可在 Artifacts 下載：
    echo 👉 LineWalkAutoAd-APK.zip (解壓縮即為 app-debug.apk)
    echo ========================================================
) else (
    echo.
    echo ❌ 推送失敗，請確認網址是否正確以及是否已登入 GitHub。
)
pause
