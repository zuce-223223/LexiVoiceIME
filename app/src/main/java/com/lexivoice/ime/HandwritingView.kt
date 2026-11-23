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
 * 手写汉字画布（Gboard 风格，v4）：
 * - 连笔书写，抬笔停顿后自动识别；
 * - 再次落笔自动消费上次候选并清屏，实现连续输入；
 * - 全部笔迹联合边界框，修复多笔画缩放错位；
 * - 抬笔跳点不再强制连线、多指/跳变不产生无关笔画（首笔粘连修复）；
 * - 识别图线宽按缩放比反算，细至约 2.6px/64px，匹配模型训练笔迹。
 */
class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var recognizer: HCCRRecognizer? = null
    var onResult: ((List<String>) -> Unit)? = null
    var onAutoCommitPending: (() -> Unit)? = null

    /** 停顿多久后识别（毫秒），对应“书写速度”，可调 300–3300ms。 */
    var autoRecognizeDelayMs: Long = 1200L

    /** 屏幕显示笔迹粗细（px）；识别图使用独立细笔迹。 */
    var displayStrokeWidth: Float = 8f

    /** 返回候选数量（首选自动上屏，其余展示）。 */
    var candidateCount: Int = 9

    private val strokePaint = Paint().apply {
        color = Color.parseColor("#1A1C1E")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val paths = mutableListOf<Path>()
    private var currentPath: Path? = null
    private var activePointerId = -1
    private var lastX = -1f
    private var lastY = -1f

    /** 坐标跳变超过该距离时断笔，不再连出长直线（抬笔跳点/首笔粘连主因）。 */
    private val jumpLimit: Float
        get() = 90f * resources.displayMetrics.density

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRecognize = false
    private var lastUpTime = 0L
    private var lastCandidates: List<String> = emptyList()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        for (p in paths) canvas.drawPath(p, strokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 【首笔粘连根治】只要画布上还有旧笔迹，落笔瞬间就同步结算上一字并清屏。
                // 不能再等 1200ms 定时识别：用户连续书写时旧字会与新字叠加成"无关笔画"。
                handler.removeCallbacksAndMessages(null)
                if (paths.isNotEmpty()) {
                    pendingRecognize = true
                    recognize() // 同步识别：首选上屏 + 显示候选 + clear()
                    // 识别失败（模型未就绪等）也必须清屏，绝不让旧笔迹留到新字里
                    if (paths.isNotEmpty()) clear()
                    pendingRecognize = false
                }
                activePointerId = event.getPointerId(0)
                val x = event.getX(0)
                val y = event.getY(0)
                currentPath = Path().apply { moveTo(x, y) }
                paths.add(currentPath!!)
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            // 第二根手指不建新路径，避免无关笔画
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> return true
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                for (i in 0 until event.historySize) {
                    appendPoint(event.getHistoricalX(idx, i), event.getHistoricalY(idx, i))
                }
                appendPoint(event.getX(idx), event.getY(idx))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx >= 0) {
                    for (i in 0 until event.historySize) {
                        appendPoint(event.getHistoricalX(idx, i), event.getHistoricalY(idx, i))
                    }
                }
                // 关键：不再把 UP 坐标强行连线（抬笔跳点会画出一道无关长线）
                currentPath = null
                activePointerId = -1
                lastX = -1f
                lastY = -1f
                lastUpTime = System.currentTimeMillis()
                invalidate()
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    if (System.currentTimeMillis() - lastUpTime >= autoRecognizeDelayMs - 120) {
                        pendingRecognize = true
                        recognize()
                    }
                }, autoRecognizeDelayMs)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun appendPoint(x: Float, y: Float) {
        val path = currentPath ?: return
        if (lastX >= 0f) {
            val dx = x - lastX
            val dy = y - lastY
            if (dx * dx + dy * dy > jumpLimit * jumpLimit) {
                path.moveTo(x, y) // 断笔，不连出长线
            } else {
                path.lineTo(x, y)
            }
        } else {
            path.moveTo(x, y)
        }
        lastX = x
        lastY = y
    }

    private fun flushPendingResult() {
        if (lastCandidates.isNotEmpty()) onAutoCommitPending?.invoke()
        clear()
    }

    /** 手动识别（工具行“识别”按钮）。 */
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

        // 全部笔迹的联合边界框
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

        // 识别图使用细笔迹：缩放后约 2.6px/64px（旧版 6px 过粗，识别率下降）
        val renderPaint = Paint(strokePaint).apply {
            strokeWidth = (2.6f / scale).coerceIn(1f, 20f)
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
            val v = pixels[i]
            gray[i] = (((Color.red(v) + Color.green(v) + Color.blue(v)) / 3) and 0xFF).toByte()
        }
        bmp.recycle()

        val floatInput = FloatArray(size * size)
        if (!rec.preprocess(gray, size, size, floatInput)) return
        val results = rec.predict(floatInput, candidateCount)
        if (results.isEmpty()) return
        lastCandidates = results.map { it.text }
        onResult?.invoke(lastCandidates)
    }

    fun applyStrokeWidth(widthPx: Float) {
        displayStrokeWidth = widthPx
        strokePaint.strokeWidth = widthPx
        invalidate()
    }

    /** 清屏但保留候选状态（供下次落笔自动确认）。 */
    fun clear() {
        paths.clear()
        currentPath = null
        activePointerId = -1
        lastX = -1f
        lastY = -1f
        invalidate()
    }

    /** 进入/退出手写模式时彻底复位，避免残留笔迹与待处理候选。 */
    fun reset() {
        handler.removeCallbacksAndMessages(null)
        clear()
        lastCandidates = emptyList()
        pendingRecognize = false
    }

    fun hasStrokes(): Boolean = paths.isNotEmpty()

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
