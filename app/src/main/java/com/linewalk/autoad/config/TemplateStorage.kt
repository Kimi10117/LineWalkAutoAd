package com.linewalk.autoad.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

/**
 * 定義手機端「即時框選取樣」支援的圖示分類，並紀錄建議搜尋的螢幕相對區域 (Normalized ROI)。
 */
enum class TemplateCategory(
    val id: String,
    val displayName: String,
    val description: String,
    val defaultSearchRoi: RectF
) {
    STEP_BOTTLE(
        id = "step_bottle",
        displayName = "1. 步數滿水瓶圖示 (優先)",
        description = "框選遊戲主畫面中「已裝滿發亮的水瓶」圖示，用於自動點擊水瓶領取獎勵。",
        defaultSearchRoi = RectF(0.05f, 0.25f, 0.95f, 0.90f)
    ),
    AD_TRIGGER_BTN(
        id = "ad_trigger_btn",
        displayName = "2. 看廣告領取按鈕圖示",
        description = "框選彈窗中「觀看廣告 / 影片圖示」按鈕（搭配中文 OCR 雙重保障）。",
        defaultSearchRoi = RectF(0.10f, 0.30f, 0.90f, 0.85f)
    ),
    AD_CLOSE_ICON(
        id = "ad_close_icon",
        displayName = "3. 廣告關閉 X / >> 圖示",
        description = "若遇到特殊造型的廣告『X』或『>>』關閉按鈕，可框選加入範本庫。",
        defaultSearchRoi = RectF(0.0f, 0.0f, 1.0f, 0.22f)
    ),
    MOVE_GAUGE(
        id = "move_gauge",
        displayName = "4. 移動距離獎勵圖示",
        description = "框選移動頁面中「集滿的交通工具/火車車票」領獎圖示。",
        defaultSearchRoi = RectF(0.05f, 0.20f, 0.95f, 0.88f)
    ),
    IDLE_REWARD(
        id = "idle_reward",
        displayName = "5. 放置/時間獎勵圖示",
        description = "框選角色定時產出的金幣/寶箱圖示。",
        defaultSearchRoi = RectF(0.05f, 0.20f, 0.95f, 0.88f)
    ),
    GACHA_BTN(
        id = "gacha_btn",
        displayName = "6. 扭蛋 / 刮刮樂按鈕",
        description = "框選扭蛋或刮刮樂的看廣告抽獎按鈕。",
        defaultSearchRoi = RectF(0.10f, 0.30f, 0.90f, 0.90f)
    )
}

data class SavedTemplate(
    val category: TemplateCategory,
    val file: File,
    val bitmap: Bitmap
)

/**
 * 管理使用者在手機懸浮窗直接框選儲存的圖示範本。
 */
class TemplateStorage(context: Context) {

    private val rootDir = File(context.applicationContext.filesDir, "templates").apply {
        if (!exists()) mkdirs()
    }

    @Volatile
    private var memoryCache: Map<TemplateCategory, List<SavedTemplate>>? = null

    /**
     * 將框選裁切出的 Bitmap 存入對應分類資料夾，並刷新快取。
     */
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

    /**
     * 取得指定分類下已儲存的所有範本 (含記憶體快取)。
     */
    @Synchronized
    fun getTemplates(category: TemplateCategory): List<SavedTemplate> {
        val cache = memoryCache ?: loadAllIntoCache()
        return cache[category].orEmpty()
    }

    /**
     * 取得各分類目前已儲存的範本數量，供主畫面顯示。
     */
    @Synchronized
    fun getTemplateCounts(): Map<TemplateCategory, Int> {
        val cache = memoryCache ?: loadAllIntoCache()
        return TemplateCategory.entries.associateWith { cache[it]?.size ?: 0 }
    }

    /**
     * 清除指定分類的所有範本。
     */
    @Synchronized
    fun clearCategory(category: TemplateCategory) {
        val categoryDir = File(rootDir, category.id)
        if (categoryDir.exists()) {
            categoryDir.listFiles()?.forEach { it.delete() }
        }
        memoryCache = null
    }

    /**
     * 清除全部範本。
     */
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
