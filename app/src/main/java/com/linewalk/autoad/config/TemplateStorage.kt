package com.linewalk.autoad.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

/**
 * 定義手機端「即時框選取樣」支援的圖示分類，並紀錄建議搜尋的螢幕相對區域 (Normalized ROI)。
 * 注意：《LINE 散步趣》主畫面下半部 (y > 0.46) 為內嵌橫幅廣告區 (如 Amazon 安裝廣告)，
 * 因此主畫面任務的 ROI 嚴格限制在 y = 0.14 ~ 0.45 的上半部互動舞台區！
 */
enum class TemplateCategory(
    val id: String,
    val displayName: String,
    val description: String,
    val defaultSearchRoi: RectF
) {
    STEP_BOTTLE(
        id = "step_bottle",
        displayName = "1. 步數『獲得票券』或水瓶 (優先)",
        description = "已內建自動辨識粉紅色『獲得票券』按鈕；亦可框選自訂水瓶圖示。",
        defaultSearchRoi = RectF(0.05f, 0.15f, 0.95f, 0.42f)
    ),
    AD_TRIGGER_BTN(
        id = "ad_trigger_btn",
        displayName = "2. 彈窗『看廣告』按鈕圖示",
        description = "框選彈窗中「觀看廣告 / 影片圖示」按鈕（搭配中文 OCR 雙重保障）。",
        defaultSearchRoi = RectF(0.08f, 0.22f, 0.92f, 0.85f)
    ),
    AD_CLOSE_ICON(
        id = "ad_close_icon",
        displayName = "3. 廣告關閉 X / >> 圖示",
        description = "若遇到特殊造型的廣告『X』或『>>』關閉按鈕，可框選加入範本庫。",
        defaultSearchRoi = RectF(0.0f, 0.02f, 1.0f, 0.25f)
    ),
    MOVE_GAUGE(
        id = "move_gauge",
        displayName = "4. 首頁(移動)獎勵圖示",
        description = "框選『首頁』分頁中集滿的交通工具/領獎圖示。",
        defaultSearchRoi = RectF(0.05f, 0.15f, 0.95f, 0.44f)
    ),
    IDLE_REWARD(
        id = "idle_reward",
        displayName = "5. 時間(放置)獎勵圖示",
        description = "框選『時間』分頁中角色定時產出的領獎按鈕或圖示。",
        defaultSearchRoi = RectF(0.05f, 0.15f, 0.95f, 0.44f)
    ),
    GACHA_BTN(
        id = "gacha_btn",
        displayName = "6. 抽抽樂 / 糖果 / 任務按鈕",
        description = "框選抽抽樂或糖果的看廣告抽獎按鈕。",
        defaultSearchRoi = RectF(0.05f, 0.15f, 0.95f, 0.85f)
    )
}

data class SavedTemplate(
    val category: TemplateCategory,
    val file: File,
    val bitmap: Bitmap
)

class TemplateStorage(context: Context) {

    private val rootDir = File(context.applicationContext.filesDir, "templates").apply {
        if (!exists()) mkdirs()
    }

    @Volatile
    private var memoryCache: Map<TemplateCategory, List<SavedTemplate>>? = null

    @Synchronized
    fun saveTemplate(category: TemplateCategory, croppedBitmap: Bitmap): File {
        val categoryDir = File(rootDir, category.id).apply {
            if (!exists()) mkdirs()
        }
        val fileName = "tpl_${System.currentTimeMillis()}.png"
        val outFile = File(categoryDir, fileName)
        FileOutputStream(outFile).use { fos ->
            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
        }
        memoryCache = null
        return outFile
    }

    @Synchronized
    fun getTemplates(category: TemplateCategory): List<SavedTemplate> {
        val cache = memoryCache ?: loadAllIntoCache()
        return cache[category].orEmpty()
    }

    @Synchronized
    fun getTemplateCounts(): Map<TemplateCategory, Int> {
        val cache = memoryCache ?: loadAllIntoCache()
        return TemplateCategory.entries.associateWith { cache[it]?.size ?: 0 }
    }

    @Synchronized
    fun clearCategory(category: TemplateCategory) {
        val categoryDir = File(rootDir, category.id)
        if (categoryDir.exists()) {
            categoryDir.listFiles()?.forEach { it.delete() }
        }
        memoryCache = null
    }

    @Synchronized
    fun clearAll() {
        TemplateCategory.entries.forEach { clearCategory(it) }
    }

    private fun loadAllIntoCache(): Map<TemplateCategory, List<SavedTemplate>> {
        val result = mutableMapOf<TemplateCategory, List<SavedTemplate>>()
        for (cat in TemplateCategory.entries) {
            val dir = File(rootDir, cat.id)
            if (!dir.exists()) {
                result[cat] = emptyList()
                continue
            }
            val list = dir.listFiles { f -> f.extension.equals("png", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.mapNotNull { file ->
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) SavedTemplate(cat, file, bmp) else null
                }
                .orEmpty()
            result[cat] = list
        }
        memoryCache = result
        return result
    }
}
