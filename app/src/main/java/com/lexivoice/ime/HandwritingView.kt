package com.lexivoice.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.shiyu.handwritten.runtime.HCCRRecognizer
import kotlin.math.max

/**
 * 手写汉字画布（Gboard 风格）：
 * - 连笔书写：多笔画叠加，停顿 autoDelayMs 后自动识别；
 * - 再次落笔时自动把上次候选首选上屏并清屏，实现连续输入；
 * - 修复旧版识别图边界框只取最后一笔的 bug（现取全部笔迹联合边界）；
 * - 笔迹粗细与识别停顿时间可由外部调节（设置面板）。
 */
class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var recognizer: HCCRRecognizer? = null
    var onResult: ((List<String>) -> Unit)? = null

    /** 本次停顿后自动识别的延时（毫秒），对应“书写速度”：越小越快触发识别。 */
    var autoRecognizeDelayMs: Long = 600L

    /** 屏幕显示笔迹粗细（px），识别图始终使用模型最优的固定线宽。 */
    var displayStrokeWidth: Float = 14f

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
    private var currentStrokeId = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRecognize = false
    private var lastUpTime = 0L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        for (p in paths) canvas.drawPath(p, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 再次落笔：若上一次识别结果尚未被消费，触发“连续书写自动上屏”
                if (pendingRecognize) {
                    pendingRecognize = false
                    handler.removeCallbacksAndMessages(null)
                    flushPendingResult()
                }
                currentStrokeId = System.currentTimeMillis()
                currentPath = Path().apply { moveTo(event.x, event.y) }
                paths.add(currentPath!!)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 记录历史点使曲线更平滑
                for (i in 0 until event.historySize) {
                    currentPath?.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                currentPath?.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath?.lineTo(event.x, event.y)
                currentPath = null
                lastUpTime = System.currentTimeMillis()
                invalidate()
                // 抬笔后延迟自动识别：连笔书写时不会中途误识别
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    if (System.currentTimeMillis() - lastUpTime >= autoRecognizeDelayMs - 80) {
                        pendingRecognize = true
                        recognize()
                    }
                }, autoRecognizeDelayMs)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 连续书写时自动消费上次结果：交由外部决定上屏内容。 */
    var onAutoCommitPending: (() -> Unit)? = null
    private var lastCandidates: List<String> = emptyList()

    private fun flushPendingResult() {
        if (lastCandidates.isNotEmpty()) {
            onAutoCommitPending?.invoke()
        }
        clear()
    }

    /** 手动触发识别（设置面板/工具行“识别”按钮）。 */
    fun recognizeNow() {
        handler.removeCallbacksAndMessages(null)
        if (paths.isEmpty()) return
        lastUpTime = System.currentTimeMillis()
        pendingRecognize = true
        recognize()
    }

    private fun recognize() {
        val rec = recognizer ?: return
        if (paths.isEmpty()) return

        // 全部笔迹联合边界框（修复旧版只取最后一笔的 bug）
        val union = RectF()
        val tmp = RectF()
        var hasContent = false
        for (p in paths) {
            p.computeBounds(tmp, true)
            if (tmp.isEmpty) continue
            if (hasContent) union.union(tmp) else union.set(tmp)
            hasContent = true
        }
        if (!hasContent) return

        // 渲染到 64x64 白底黑字灰度图，等比缩放居中
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val w = max(union.width(), 1f)
        val h = max(union.height(), 1f)
        val content = 56f
        val scale = minOf(content / w, content / h)
        val offsetX = (size - w * scale) / 2f - union.left * scale
        val offsetY = (size - h * scale) / 2f - union.top * scale

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
            gray[i] = ((r + g + b) / 3 and 0xFF).toByte()
        }
        bmp.recycle()

        val floatInput = FloatArray(size * size)
        val ok = rec.preprocess(gray, size, size, floatInput)
        if (!ok) return
        val results = rec.predict(floatInput, 5)
        lastCandidates = results.map { it.text }
        onResult?.invoke(lastCandidates)
    }

    /** 应用笔迹粗细设置并重绘。 */
    fun applyStrokeWidth(widthPx: Float) {
        displayStrokeWidth = widthPx
        strokePaint.strokeWidth = widthPx
        invalidate()
    }

    /** 识别完成后清屏（候选已上屏/展示）。 */
    fun clear() {
        paths.clear()
        currentPath = null
        invalidate()
    }

    fun hasStrokes(): Boolean = paths.isNotEmpty()

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
