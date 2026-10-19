package com.linewalk.autoad.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.databinding.ActivityMainBinding
import com.linewalk.autoad.engine.AutomationEngine
import com.linewalk.autoad.service.AutoAdAccessibilityService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 主設定與狀態監控畫面 (MainActivity)：
 * - 檢查並引導開啟「無障礙服務」與「懸浮窗權限」
 * - 勾選要啟用的《LINE 散步趣》自動化任務（步數水瓶優先、移動距離、放置獎勵、扭蛋）
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
        binding.cbClaimNormalWhenNoAd.setOnCheckedChangeListener { _, isChecked ->
            cfg.claimNormalWhenNoAd = isChecked
        }
    }

    private fun setupClickListeners() {
        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "請在無障礙設定中找到「散步趣自動看廣告無障礙服務」並開啟",
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

            // 啟動懸浮控制窗服務
            val overlayIntent = Intent(this, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(overlayIntent)
            } else {
                startService(overlayIntent)
            }

            // 嘗試自動拉起《LINE 散步趣》(com.linecorp.LGWALK)
            val launchGameIntent = packageManager.getLaunchIntentForPackage("com.linecorp.LGWALK")
            if (launchGameIntent != null) {
                startActivity(launchGameIntent)
            } else {
                Toast.makeText(
                    this,
                    "懸浮控制窗已啟動！請手動切換至《LINE 散步趣》畫面",
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
    }

    private fun refreshPermissionAndTemplateStatus() {
        val accConnected = AutoAdAccessibilityService.instance.value != null
        val overlayGranted = Settings.canDrawOverlays(this)

        val accText = if (accConnected) "✅ 無障礙服務：已啟用連線" else "❌ 無障礙服務：尚未啟用（必開）"
        val overlayText = if (overlayGranted) "✅ 懸浮窗權限：已授權" else "❌ 懸浮窗權限：尚未授權（必開）"
        binding.tvPermissionStatus.text = "$accText\n$overlayText"

        val counts = engine.templateStorage.getTemplateCounts()
        val summaryText = TemplateCategory.entries.joinToString("\n") { cat ->
            val count = counts[cat] ?: 0
            val statusMark = if (count > 0) "🟢 已存 $count 張" else "⚪ 尚未取樣"
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
                        val header = "目前狀態：${status.state.title} | 累計完成廣告：${status.adsCompletedCount} 支 | 觸發任務：${status.tasksTriggeredCount} 次\n" +
                            "--------------------------------------------\n"
                        val body = if (status.recentLogs.isEmpty()) {
                            "尚無執行紀錄，請啟動懸浮控制窗並按 [▶ 開始]"
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
}
