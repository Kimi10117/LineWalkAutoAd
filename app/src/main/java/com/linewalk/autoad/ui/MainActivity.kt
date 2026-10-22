package com.linewalk.autoad.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.linewalk.autoad.R
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.databinding.ActivityMainBinding
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.service.AutoAdAccessibilityService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 主設定與狀態監控畫面 (MainActivity)：
 * - 內建 Android 13+「受限制的設定」防呆教學與一鍵跳轉應用程式資訊按鈕
 * - 勾選要啟用的《LINE 散步趣》自動化任務
 * - 檢視與管理手機端即時框選的圖示範本庫
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: AutomationEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        engine = AutomationEngine.getInstance(this)
        binding.tvAppTitle.text = "散步趣助手"
        binding.tvAppVersionBadge.text = "v${com.linewalk.autoad.BuildConfig.VERSION_NAME}"
        setupTaskCheckboxes()
        setupClickListeners()
        observeEngineStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionAndTemplateStatus()
    }

    private fun setupTaskCheckboxes() {
        val cfg = engine.config
        binding.cbStepBottle.isChecked = cfg.enableStepBottleTask
        binding.cbMoveGauge.isChecked = cfg.enableMoveGaugeTask
        binding.cbIdleReward.isChecked = cfg.enableIdleRewardTask
        binding.cbGachaTask.isChecked = cfg.enableGachaTask
        binding.cbBingoTask.isChecked = cfg.enableBingoTask
        binding.cbLotteryTask.isChecked = cfg.enableLotteryTask
        binding.cbDailyMissionTask.isChecked = cfg.enableDailyMissionTask
        binding.cbFriendGiftTask.isChecked = cfg.enableFriendGiftTask
        binding.cbClaimNormalWhenNoAd.isChecked = cfg.claimNormalWhenNoAd

        binding.cbStepBottle.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableStepBottleTask = isChecked
        }
        binding.cbMoveGauge.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableMoveGaugeTask = isChecked
        }
        binding.cbIdleReward.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableIdleRewardTask = isChecked
        }
        binding.cbGachaTask.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableGachaTask = isChecked
        }
        binding.cbBingoTask.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableBingoTask = isChecked
        }
        binding.cbLotteryTask.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableLotteryTask = isChecked
        }
        binding.cbDailyMissionTask.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableDailyMissionTask = isChecked
        }
        binding.cbFriendGiftTask.setOnCheckedChangeListener { _, isChecked ->
            cfg.enableFriendGiftTask = isChecked
        }
        binding.cbClaimNormalWhenNoAd.setOnCheckedChangeListener { _, isChecked ->
            cfg.claimNormalWhenNoAd = isChecked
        }
    }

    private fun setupClickListeners() {
        binding.btnUnlockRestricted.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "請點選畫面最右上角的「⋮」選單 ➔ 點擊「允許受限制的設定」",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "請在『已安裝的應用程式』中找到「散步趣助手」並開啟",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnEnableOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnStartOverlayAndGame.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "請先點擊 [2. 授權懸浮窗權限]！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (AutoAdAccessibilityService.instance.value == null) {
                Toast.makeText(this, "請先點擊 [1. 開啟無障礙服務]！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val overlayIntent = Intent(this, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayIntent)
            } else {
                startService(overlayIntent)
            }

            val launchGameIntent = packageManager.getLaunchIntentForPackage("com.linecorp.LGWKTW")
                ?: packageManager.getLaunchIntentForPackage("com.linecorp.LGWALK")
            if (launchGameIntent != null) {
                startActivity(launchGameIntent)
            } else {
                Toast.makeText(
                    this,
                    "懸浮窗已啟動！請手動切換至《LINE 散步趣》畫面",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnClearBottleTemplates.setOnClickListener {
            engine.templateStorage.clearCategory(TemplateCategory.STEP_BOTTLE)
            refreshPermissionAndTemplateStatus()
            Toast.makeText(this, "已清除步數滿水瓶範本", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearAllTemplates.setOnClickListener {
            engine.templateStorage.clearAll()
            refreshPermissionAndTemplateStatus()
            Toast.makeText(this, "已清空所有自訂圖示範本", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyLogs.setOnClickListener {
            val fullLogs = engine.getFullLogsFormatted()
            copyLogsToClipboard(fullLogs, showSuccessToast = true)
        }

        binding.btnExportLogs.setOnClickListener {
            val fullLogs = engine.getFullLogsFormatted()
            val exportLogs = engine.getExportLogsFormatted(maxLines = 1500)
            val safePayload = if (exportLogs.length > 200_000) {
                val notice = "[注意：日誌量極大，已自動安全截斷以防止系統當機。完整日誌請使用「複製日誌」]\n\n"
                notice + exportLogs.takeLast(180_000)
            } else {
                exportLogs
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.subject_export_logs))
                putExtra(Intent.EXTRA_TEXT, safePayload)
            }
            val chooser = Intent.createChooser(sendIntent, getString(R.string.chooser_export_logs))
            try {
                startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                // Fallback: auto-copy to clipboard and notify user
                val copied = copyLogsToClipboard(fullLogs, showSuccessToast = false)
                Toast.makeText(this, R.string.toast_export_fallback_copied, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "匯出日誌失敗：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshPermissionAndTemplateStatus() {
        val accConnected = AutoAdAccessibilityService.instance.value != null
        val overlayGranted = Settings.canDrawOverlays(this)

        // 若無障礙已經成功開啟，自動隱藏受限制設定教學盒讓畫面更清爽；若尚未開啟則顯示提醒
        binding.layoutRestrictedHint.visibility = if (accConnected) View.GONE else View.VISIBLE

        val accText = if (accConnected) "[已連線] 無障礙服務：運作中" else "[未啟用] 無障礙服務：尚未開啟（必開）"
        val overlayText = if (overlayGranted) "[已授權] 懸浮窗權限：正常" else "[未授權] 懸浮窗權限：尚未授權（必開）"
        binding.tvPermissionStatus.text = "$accText\n$overlayText"

        val counts = engine.templateStorage.getTemplateCounts()
        val summaryText = TemplateCategory.entries.joinToString("\n") { cat ->
            val count = counts[cat] ?: 0
            val statusMark = if (count > 0) "[已取樣] $count 張" else "[未取樣] 0 張"
            "• ${cat.displayName}：$statusMark"
        }
        binding.tvTemplateSummary.text = summaryText
    }

    private fun observeEngineStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AutoAdAccessibilityService.instance.collectLatest {
                        refreshPermissionAndTemplateStatus()
                    }
                }
                launch {
                    engine.uiStatus.collectLatest { status ->
                        val header = "狀態：${status.state.shortTitle} | 廣告完成：${status.adsCompletedCount} | 任務觸發：${status.tasksTriggeredCount}\n" +
                            "--------------------------------------------\n"
                        val body = if (status.recentLogs.isEmpty()) {
                            "尚無執行紀錄，請啟動懸浮窗並按 [開始]"
                        } else {
                            status.recentLogs.reversed().joinToString("\n")
                        }
                        binding.tvLiveLogs.text = header + body
                        refreshPermissionAndTemplateStatus()
                    }
                }
            }
        }
    }

    private fun copyLogsToClipboard(logs: String, showSuccessToast: Boolean = true): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, getString(R.string.toast_clipboard_error), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val clip = ClipData.newPlainText("LineWalkAutoAd_Logs", logs)
            clipboard.setPrimaryClip(clip)
            if (showSuccessToast) {
                Toast.makeText(this, getString(R.string.toast_logs_copied), Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            Toast.makeText(this, "複製日誌失敗：${e.message ?: "未知錯誤"}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
