package com.linewalk.autoad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.linewalk.autoad.config.TemplateCategory
import com.linewalk.autoad.config.TemplateStorage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 手機端「即時框選截圖取樣器」 (TemplateCropOverlay)：
 * 讓使用者直接凍結《LINE 散步趣》或廣告畫面，用手指拉出紅框選取「滿水瓶」、「移動獎勵」或「特殊廣告 X」，
 * 直接儲存至本機範本庫，完全免連電腦裁圖！
 */
class TemplateCropOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val templateStorage: TemplateStorage,
    private val screenshot: Bitmap,
    private val onFinished: (savedCategory: TemplateCategory?) -> Unit
) {

    private var rootLayout: FrameLayout? = null
    private val selectionRect = RectF()
    private var hasValidSelection = false

    fun show() {
        val container = FrameLayout(context)

        // 1. 底層：凍結的遊戲截圖與手指拖曳畫布
        val cropCanvasView = CropCanvasView(context)
        container.addView(
            cropCanvasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 2. 頂部控制列：選擇範本類型 + 儲存 / 取消按鈕
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1E293B"))
            setPadding(36, 32, 36, 28)
        }

        val guideText = TextView(context).apply {
            text = "✂️ 請在下方畫面用手指拖曳框選目標圖示（例如：單一顆發亮的滿水瓶），再選擇分類並點儲存："
            setTextColor(Color.WHITE)
            textSize = 13.5f
        }
        toolbar.addView(guideText)

        val categories = TemplateCategory.entries
        val categorySpinner = Spinner(context).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            setPadding(16, 12, 16, 12)
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                categories.map { it.displayName }
            )
        }
        val spinnerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 16
            bottomMargin = 16
        }
        toolbar.addView(categorySpinner, spinnerParams)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBtn = Button(context).apply {
            text = "✖ 取消"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#64748B"))
            setOnClickListener {
                dismiss(null)
            }
        }

        val saveBtn = Button(context).apply {
            text = "💾 儲存框選範本"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#06C755"))
            setOnClickListener {
                if (!hasValidSelection) {
                    Toast.makeText(context, "請先在畫面上用手指拖曳框選出目標圖示！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedCategory = categories[categorySpinner.selectedItemPosition]
                val cropped = cropSelectedBitmap(cropCanvasView.width, cropCanvasView.height)
                if (cropped != null) {
                    templateStorage.saveTemplate(selectedCategory, cropped)
                    cropped.recycle()
                    Toast.makeText(
                        context,
                        "✅ 已儲存至【${selectedCategory.displayName}】！",
                        Toast.LENGTH_SHORT
                    ).show()
                    dismiss(selectedCategory)
                } else {
                    Toast.makeText(context, "框選範圍過小，請重新框選", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 24
        }
        buttonRow.addView(cancelBtn)
        buttonRow.addView(saveBtn, btnParams)
        toolbar.addView(buttonRow)

        val toolbarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        )
        container.addView(toolbar, toolbarParams)

        val wmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )

        rootLayout = container
        windowManager.addView(container, wmParams)
    }

    private fun cropSelectedBitmap(viewWidth: Int, viewHeight: Int): Bitmap? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val scaleX = screenshot.width.toFloat() / viewWidth.toFloat()
        val scaleY = screenshot.height.toFloat() / viewHeight.toFloat()

        val left = (selectionRect.left * scaleX).toInt().coerceIn(0, screenshot.width - 1)
        val top = (selectionRect.top * scaleY).toInt().coerceIn(0, screenshot.height - 1)
        val right = (selectionRect.right * scaleX).toInt().coerceIn(left + 1, screenshot.width)
        val bottom = (selectionRect.bottom * scaleY).toInt().coerceIn(top + 1, screenshot.height)

        val w = right - left
        val h = bottom - top
        if (w < 16 || h < 16) return null

        return Bitmap.createBitmap(screenshot, left, top, w, h)
    }

    private fun dismiss(savedCategory: TemplateCategory?) {
        rootLayout?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootLayout = null
        if (!screenshot.isRecycled) {
            screenshot.recycle()
        }
        onFinished(savedCategory)
    }

    @SuppressLint("ViewConstructor")
    private inner class CropCanvasView(context: Context) : View(context) {

        private var downX = 0f
        private var downY = 0f

        private val dimPaint = Paint().apply {
            color = Color.parseColor("#77000000")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06C755")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#06C755")
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
        }

        private val dstRect = Rect()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!screenshot.isRecycled) {
                dstRect.set(0, 0, width, height)
                canvas.drawBitmap(screenshot, null, dstRect, null)
            }

            if (hasValidSelection) {
                // 繪製外圍半透明遮罩與綠色高亮選取框
                canvas.drawRect(0f, 0f, width.toFloat(), selectionRect.top, dimPaint)
                canvas.drawRect(0f, selectionRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
                canvas.drawRect(0f, selectionRect.top, selectionRect.left, selectionRect.bottom, dimPaint)
                canvas.drawRect(selectionRect.right, selectionRect.top, width.toFloat(), selectionRect.bottom, dimPaint)

                canvas.drawRect(selectionRect, borderPaint)
                val sizeLabel = "${selectionRect.width().toInt()} x ${selectionRect.height().toInt()} px"
                canvas.drawText(
                    sizeLabel,
                    selectionRect.left,
                    max(selectionRect.top - 14f, 40f),
                    labelPaint
                )
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    selectionRect.set(downX, downY, downX, downY)
                    hasValidSelection = false
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    val curX = event.x
                    val curY = event.y
                    selectionRect.set(
                        min(downX, curX),
                        min(downY, curY),
                        max(downX, curX),
                        max(downY, curY)
                    )
                    hasValidSelection = abs(selectionRect.width()) >= 20f && abs(selectionRect.height()) >= 20f
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
