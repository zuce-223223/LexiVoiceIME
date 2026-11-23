package com.lexivoice.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.shiyu.handwritten.runtime.HCCRRecognizer
import kotlin.math.max

/**
 * 手写汉字画布：采集笔迹，生成 64x64 灰度图，调用离线 HCCR 识别候选字。
 */
class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var recognizer: HCCRRecognizer? = null
    var onResult: ((List<String>) -> Unit)? = null

    private val strokePaint = Paint().apply {
        color = Color.parseColor("#1A1C1E")
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val paths = mutableListOf<Path>()
    private var currentPath: Path? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        for (p in paths) canvas.drawPath(p, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply { moveTo(x, y) }
                paths.add(currentPath!!)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath?.lineTo(x, y)
                currentPath = null
                invalidate()
                recognize()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun recognize() {
        val rec = recognizer ?: return
        if (paths.isEmpty()) return

        // 将笔迹渲染到 64x64 白底黑字灰度图
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        // 计算笔迹边界，等比缩放居中到 56x56 区域
        val bounds = android.graphics.RectF()
        for (p in paths) p.computeBounds(bounds, true)
        val w = max(bounds.width(), 1f)
        val h = max(bounds.height(), 1f)
        val content = 56f
        val scale = minOf(content / w, content / h)
        val offsetX = (size - w * scale) / 2f - bounds.left * scale
        val offsetY = (size - h * scale) / 2f - bounds.top * scale

        val renderPaint = Paint(strokePaint).apply {
            strokeWidth = 6f
            color = Color.BLACK
        }
        c.save()
        c.translate(offsetX, offsetY)
        c.scale(scale, scale)
        for (p in paths) c.drawPath(p, renderPaint)
        c.restore()

        val gray = ByteArray(size * size)
        val pixels = IntArray(size * size)
        bmp.getPixels(pixels, 0, size, 0, 0, size, size)
        for (i in pixels.indices) {
            val r = Color.red(pixels[i])
            val g = Color.green(pixels[i])
            val b = Color.blue(pixels[i])
            // 灰度存进有符号 byte，JNI 侧按 uint8_t 解读（&0xFF）
            gray[i] = ((r + g + b) / 3 and 0xFF).toByte()
        }

        val floatInput = FloatArray(size * size)
        val ok = rec.preprocess(gray, size, size, floatInput)
        if (!ok) return
        val results = rec.predict(floatInput, 5)
        onResult?.invoke(results.map { it.text })
    }

    fun clear() {
        paths.clear()
        currentPath = null
        invalidate()
    }
}
