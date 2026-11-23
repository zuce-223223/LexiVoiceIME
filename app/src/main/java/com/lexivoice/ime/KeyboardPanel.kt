package com.lexivoice.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.shiyu.handwritten.runtime.HCCRRecognizer
import kotlin.math.roundToInt

/**
 * 通用输入法键盘面板（纯代码构建）：候选栏 + 四种键盘（字母/数字/符号/手写）+ 语音键。
 */
class KeyboardPanel(
    context: Context,
    private val input: InputBridge,
    private val voiceManager: VoiceRecognitionManager,
    private val handwriteRecognizer: HCCRRecognizer?,
    private val scope: kotlinx.coroutines.CoroutineScope
) : LinearLayout(context) {

    interface InputBridge {
        fun commit(text: String)
        fun backspace()
        fun enter()
        fun status(text: String)
    }

    private enum class Mode { LETTERS, NUMBERS, SYMBOLS, HANDWRITE }

    private var mode = Mode.LETTERS
    private var shiftOn = false

    private val candidateBar: TextView
    private val rowsContainer: LinearLayout
    private val handwriteView: HandwritingView
    private val btnModeLetters: Button
    private val btnModeNumbers: Button
    private val btnModeSymbols: Button
    private val btnModeHandwrite: Button
    private val btnVoice: Button

    private var isRecording = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F7F8FA"))

        // 候选栏 / 状态栏
        candidateBar = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(38))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(14), 0, dp(14), 0)
            textSize = 13f
            setTextColor(Color.parseColor("#3A3F47"))
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            text = voiceManager.getModelStatusSummary()
        }
        addView(candidateBar)

        rowsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        addView(rowsContainer)

        handwriteView = HandwritingView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(180))
            visibility = GONE
            recognizer = handwriteRecognizer
            onResult = { candidates ->
                if (candidates.isNotEmpty()) {
                    candidateBar.text = "候选：" + candidates.joinToString(" ")
                    candidateBar.setOnClickListener { input.commit(candidates[0]); candidateBar.setOnClickListener(null) }
                }
            }
        }
        addView(handwriteView)

        // 底部功能行：模式切换 + 语音 + 空格 + 退格 + 回车
        val bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        btnModeLetters = modeButton("ABC")
        btnModeNumbers = modeButton("123")
        btnModeSymbols = modeButton("符号")
        btnModeHandwrite = modeButton("手写")
        btnVoice = modeButton("🎤").apply { setTextColor(Color.parseColor("#3F6FE4")) }

        val btnSpace = modeButton("空格").apply { layoutParams = LayoutParams(0, dp(42), 2.2f) }
        btnSpace.setOnClickListener { input.commit(" ") }
        val btnBack = modeButton("退格")
        btnBack.setOnClickListener { input.backspace() }
        val btnEnter = modeButton("回车").apply { setTextColor(Color.parseColor("#3F6FE4")) }
        btnEnter.setOnClickListener { input.enter() }

        btnModeLetters.setOnClickListener { switchMode(Mode.LETTERS) }
        btnModeNumbers.setOnClickListener { switchMode(Mode.NUMBERS) }
        btnModeSymbols.setOnClickListener { switchMode(Mode.SYMBOLS) }
        btnModeHandwrite.setOnClickListener { switchMode(Mode.HANDWRITE) }
        btnVoice.setOnClickListener { toggleVoice() }

        listOf(btnModeLetters, btnModeNumbers, btnModeSymbols, btnModeHandwrite, btnVoice, btnBack, btnEnter)
            .forEach { it.layoutParams = LayoutParams(0, dp(42), 1f) }
        bottomBar.addView(btnModeLetters)
        bottomBar.addView(btnModeNumbers)
        bottomBar.addView(btnModeSymbols)
        bottomBar.addView(btnModeHandwrite)
        bottomBar.addView(btnVoice)
        bottomBar.addView(btnSpace)
        bottomBar.addView(btnBack)
        bottomBar.addView(btnEnter)
        addView(bottomBar)

        renderKeys()
    }

    private fun modeButton(text: String) = Button(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#1A1C1E"))
        setBackgroundColor(Color.parseColor("#E8EAF0"))
        setPadding(dp(2), dp(2), dp(2), dp(2))
        isAllCaps = false
        typeface = Typeface.DEFAULT
        minHeight = 0
        minimumHeight = 0
    }

    private fun switchMode(m: Mode) {
        mode = m
        renderKeys()
        handwriteView.visibility = if (m == Mode.HANDWRITE) VISIBLE else GONE
        rowsContainer.visibility = if (m == Mode.HANDWRITE) GONE else VISIBLE
        if (m == Mode.HANDWRITE) {
            candidateBar.text = if (handwriteRecognizer != null) "手写模式：在上方书写汉字" else "手写模型未就绪"
        } else {
            candidateBar.text = voiceManager.getModelStatusSummary()
        }
    }

    private fun clearRows() {
        rowsContainer.removeAllViews()
    }

    private fun addKeyRow(keys: List<String>, isFunction: Boolean = false) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
        }
        val weight = if (isFunction) 1.25f else 1f
        for (key in keys) {
            val btn = Button(context).apply {
                text = key
                textSize = 15f
                isAllCaps = false
                typeface = Typeface.DEFAULT
                setTextColor(Color.parseColor(if (isFunction) "#3F6FE4" else "#1A1C1E"))
                setBackgroundColor(Color.parseColor(if (isFunction) "#E4E8F2" else "#FFFFFF"))
                setPadding(0, 0, 0, 0)
                minHeight = 0
                minimumHeight = 0
                layoutParams = LayoutParams(0, dp(42), weight)
            }
            btn.setOnClickListener { onKeyClick(key, isFunction) }
            row.addView(btn)
        }
        rowsContainer.addView(row)
    }

    private fun onKeyClick(key: String, isFunction: Boolean) {
        when {
            key == "⇧" -> { shiftOn = !shiftOn; renderKeys(); return }
            key == "⌫" -> { input.backspace(); return }
            key == "空格" -> { input.commit(" "); return }
            key == "回车" -> { input.enter(); return }
            key == "ABC" -> { switchMode(Mode.LETTERS); return }
            key == "123" -> { switchMode(Mode.NUMBERS); return }
            key == "符号" -> { switchMode(Mode.SYMBOLS); return }
            key == "手写" -> { switchMode(Mode.HANDWRITE); return }
            else -> input.commit(key)
        }
    }

    private fun renderKeys() {
        clearRows()
        when (mode) {
            Mode.LETTERS -> {
                val row1 = "qwertyuiop"
                val row2 = "asdfghjkl"
                val row3 = "zxcvbnm"
                fun transform(s: String) = if (shiftOn) s.uppercase() else s
                addKeyRow(row1.map { transform(it.toString()) })
                addKeyRow(row2.map { transform(it.toString()) })
                addKeyRow(listOf("⇧") + row3.map { transform(it.toString()) } + listOf("⌫"), isFunction = false)
            }
            Mode.NUMBERS -> {
                addKeyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
                addKeyRow(listOf("-", "/", ":", ";", "(", ")", "$", "@", "\"", "⌫"))
                addKeyRow(listOf("ABC", "符号", "手写", "空格", "回车"), isFunction = true)
            }
            Mode.SYMBOLS -> {
                addKeyRow(listOf("，", "。", "！", "？", "、", "：", "；", "“", "”", "⌫"))
                addKeyRow(listOf("…", "—", "·", "￥", "%", "#", "&", "*", "+", "="))
                addKeyRow(listOf("ABC", "123", "手写", "空格", "回车"), isFunction = true)
            }
            Mode.HANDWRITE -> { /* 手写模式下 rowsContainer 隐藏 */ }
        }
    }

    private fun toggleVoice() {
        if (!isRecording) {
            if (!voiceManager.isModelReady()) {
                candidateBar.text = "请先在主界面下载语音模型"
                return
            }
            isRecording = true
            btnVoice.text = "停止"
            btnVoice.setTextColor(Color.parseColor("#D32F2F"))
            voiceManager.startRecording(scope) { s -> candidateBar.text = s }
        } else {
            isRecording = false
            btnVoice.text = "🎤"
            btnVoice.setTextColor(Color.parseColor("#3F6FE4"))
            candidateBar.text = "正在识别…"
            scope.launch {
                val text = voiceManager.stopRecordingAndRecognize { s -> candidateBar.text = s }
                if (text.isNotEmpty()) input.commit(text)
                candidateBar.text = voiceManager.getModelStatusSummary()
            }
        }
    }

    fun updateStatus(text: String) {
        candidateBar.text = text
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
