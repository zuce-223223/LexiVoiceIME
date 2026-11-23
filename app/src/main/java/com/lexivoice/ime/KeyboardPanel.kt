package com.lexivoice.ime

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.shiyu.handwritten.runtime.HCCRRecognizer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 通用输入法键盘面板（纯代码构建）：
 * 候选栏(含剪贴板入口) + 五种键盘（字母/数字/符号/表情/手写）+ 语音键 + 手写设置面板。
 */
class KeyboardPanel(
    context: Context,
    private val input: InputBridge,
    private val voiceManager: VoiceRecognitionManager,
    private var handwriteRecognizer: HCCRRecognizer?,
    private val scope: kotlinx.coroutines.CoroutineScope
) : LinearLayout(context) {

    interface InputBridge {
        fun commit(text: String)
        fun backspace()
        fun enter()
        fun status(text: String)
    }

    private enum class Mode { LETTERS, NUMBERS, SYMBOLS, EMOJI, HANDWRITE }

    private var mode = Mode.LETTERS
    private var shiftOn = false

    private val prefs: SharedPreferences =
        context.getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)

    // ---------- 顶部：候选/状态栏 + 剪贴板入口 ----------
    private val candidateText: TextView
    private val btnClipboard: Button
    private val topRow: FrameLayout

    // ---------- 设置行（手写：笔迹粗细 + 书写速度） ----------
    private val settingsRow: LinearLayout

    // ---------- 剪贴板条目行 ----------
    private val clipboardRow: HorizontalScrollView
    private val clipboardList: LinearLayout
    private val clipboardHistory = mutableListOf<String>()

    // ---------- 键盘主体 ----------
    private val rowsContainer: LinearLayout
    private val handwriteView: HandwritingView
    private val handwriteToolRow: LinearLayout
    private val candidateStrip: HorizontalScrollView
    private val candidateList: LinearLayout

    // ---------- 底部功能行 ----------
    private val bottomBar: LinearLayout
    private val btnModeLetters: Button
    private val btnModeNumbers: Button
    private val btnModeEmoji: Button
    private val btnModeHandwrite: Button
    private val btnVoice: Button

    private var isRecording = false
    private var clipboardRowOpen = false
    private var emojiPage = 0

    /** 手写候选：首个已自动上屏，其余可点击替换。 */
    private var committedCharCount = 0
    private var alternativeCandidates: List<String> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#F7F8FA"))

        // ===== 顶部行：状态文字 + 剪贴板按钮 =====
        topRow = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        candidateText = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(38),
                Gravity.CENTER_VERTICAL or Gravity.START
            )
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(56), 0)
            textSize = 13f
            setTextColor(Color.parseColor("#3A3F47"))
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            text = voiceManager.getModelStatusSummary()
        }
        btnClipboard = smallButton("📋").apply {
            layoutParams = FrameLayout.LayoutParams(dp(44), dp(38), Gravity.CENTER_VERTICAL or Gravity.END)
        }
        btnClipboard.setOnClickListener { toggleClipboardRow() }
        topRow.addView(candidateText)
        topRow.addView(btnClipboard)
        addView(topRow)

        // ===== 设置行（默认隐藏） =====
        settingsRow = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(dp(14), dp(4), dp(14), dp(6))
        }
        val strokeLabel = TextView(context).apply {
            text = "笔迹粗细"
            textSize = 12f
            setTextColor(Color.parseColor("#3A3F47"))
        }
        val strokeSeek = SeekBar(context).apply {
            max = 20 // 4 ~ 24 px（dp 换算在应用时处理）
            progress = prefs.getInt("hw_stroke", 10)
        }
        val speedLabel = TextView(context).apply {
            text = "识别停顿（书写越快取值越小）"
            textSize = 12f
            setTextColor(Color.parseColor("#3A3F47"))
        }
        val speedSeek = SeekBar(context).apply {
            max = 18 // 300 ~ 1200 ms
            progress = prefs.getInt("hw_speed", 6)
        }
        val btnDoneSettings = Button(context).apply {
            text = "完成"
            textSize = 13f
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(Color.parseColor("#3F6FE4"))
            minHeight = 0
            minimumHeight = 0
        }
        btnDoneSettings.setOnClickListener { settingsRow.visibility = GONE }
        strokeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                val px = (v + 4) * resources.displayMetrics.density
                handwriteView.applyStrokeWidth(px)
                prefs.edit().putInt("hw_stroke", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                handwriteView.autoRecognizeDelayMs = 300L + v * 50L
                prefs.edit().putInt("hw_speed", v).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        settingsRow.addView(strokeLabel)
        settingsRow.addView(strokeSeek)
        settingsRow.addView(speedLabel)
        settingsRow.addView(speedSeek)
        settingsRow.addView(btnDoneSettings)
        addView(settingsRow)

        // ===== 剪贴板条目行（默认隐藏） =====
        clipboardRow = HorizontalScrollView(context).apply {
            visibility = GONE
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            isHorizontalScrollBarEnabled = false
        }
        clipboardList = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        clipboardRow.addView(clipboardList)
        addView(clipboardRow)

        // ===== 字母/数字/符号/表情键区 =====
        rowsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        addView(rowsContainer)

        // ===== 手写候选条（默认隐藏） =====
        candidateStrip = HorizontalScrollView(context).apply {
            visibility = GONE
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            isHorizontalScrollBarEnabled = false
        }
        candidateList = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        candidateStrip.addView(candidateList)
        addView(candidateStrip)

        // ===== 手写工具行（清空/识别/设置，默认隐藏） =====
        handwriteToolRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        val btnHwClear = toolButton("清空").apply { layoutParams = LayoutParams(0, dp(36), 1f) }
        val btnHwRecog = toolButton("识别").apply { layoutParams = LayoutParams(0, dp(36), 1f) }
        val btnHwSettings = toolButton("设置").apply { layoutParams = LayoutParams(0, dp(36), 1f) }
        val btnHwExit = toolButton("退出手写").apply { layoutParams = LayoutParams(0, dp(36), 1.4f) }
        btnHwClear.setOnClickListener { handwriteView.clear(); hideCandidates() }
        btnHwRecog.setOnClickListener { handwriteView.recognizeNow() }
        btnHwSettings.setOnClickListener {
            settingsRow.visibility = if (settingsRow.visibility == VISIBLE) GONE else VISIBLE
        }
        btnHwExit.setOnClickListener { switchMode(Mode.LETTERS) }
        handwriteToolRow.addView(btnHwClear)
        handwriteToolRow.addView(btnHwRecog)
        handwriteToolRow.addView(btnHwSettings)
        handwriteToolRow.addView(btnHwExit)
        addView(handwriteToolRow)

        // ===== 手写画布 =====
        handwriteView = HandwritingView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(170))
            visibility = GONE
            recognizer = handwriteRecognizer
            autoRecognizeDelayMs = 300L + prefs.getInt("hw_speed", 6) * 50L
            displayStrokeWidth = (prefs.getInt("hw_stroke", 10) + 4) * resources.displayMetrics.density
            onResult = { candidates -> showCandidates(candidates) }
            onAutoCommitPending = {
                // 连续书写：落笔新字时，上一候选（首选）自动保留（已上屏），其余候选清空
                hideCandidates()
            }
        }
        handwriteView.applyStrokeWidth(handwriteView.displayStrokeWidth)
        addView(handwriteView)

        // ===== 底部功能行 =====
        bottomBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(46))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        btnModeLetters = modeButton("ABC")
        btnModeNumbers = modeButton("123")
        btnModeEmoji = modeButton("😊")
        btnModeHandwrite = modeButton("手写")
        btnVoice = modeButton("🎤").apply { setTextColor(Color.parseColor("#3F6FE4")) }

        val btnSpace = modeButton("空格").apply { layoutParams = LayoutParams(0, dp(42), 2.2f) }
        btnSpace.setOnClickListener { input.commit(" ") }
        val btnBack = modeButton("⌫")
        btnBack.setOnClickListener { input.backspace() }
        val btnEnter = modeButton("回车").apply { setTextColor(Color.parseColor("#3F6FE4")) }
        btnEnter.setOnClickListener { input.enter() }

        btnModeLetters.setOnClickListener { switchMode(Mode.LETTERS) }
        btnModeNumbers.setOnClickListener { switchMode(Mode.NUMBERS) }
        btnModeEmoji.setOnClickListener { switchMode(Mode.EMOJI) }
        btnModeHandwrite.setOnClickListener { switchMode(Mode.HANDWRITE) }
        btnVoice.setOnClickListener { toggleVoice() }

        listOf(btnModeLetters, btnModeNumbers, btnModeEmoji, btnModeHandwrite, btnVoice, btnBack, btnEnter)
            .forEach { it.layoutParams = LayoutParams(0, dp(42), 1f) }
        bottomBar.addView(btnModeLetters)
        bottomBar.addView(btnModeNumbers)
        bottomBar.addView(btnModeEmoji)
        bottomBar.addView(btnModeHandwrite)
        bottomBar.addView(btnVoice)
        bottomBar.addView(btnSpace)
        bottomBar.addView(btnBack)
        bottomBar.addView(btnEnter)
        addView(bottomBar)

        renderKeys()
    }

    // ---------------------------------------------------------------- 基础控件

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

    private fun smallButton(text: String) = Button(context).apply {
        this.text = text
        textSize = 16f
        setBackgroundColor(Color.TRANSPARENT)
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
    }

    private fun toolButton(text: String) = Button(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#3A3F47"))
        setBackgroundColor(Color.parseColor("#E8EAF0"))
        minHeight = 0
        minimumHeight = 0
    }

    // ---------------------------------------------------------------- 模式切换

    private fun switchMode(m: Mode) {
        mode = m
        renderKeys()
        val isHand = m == Mode.HANDWRITE
        handwriteView.visibility = if (isHand) VISIBLE else GONE
        handwriteToolRow.visibility = if (isHand) VISIBLE else GONE
        candidateStrip.visibility = GONE
        settingsRow.visibility = GONE
        if (!isHand) hideCandidates()
        rowsContainer.visibility = if (isHand) GONE else VISIBLE
        highlightModeButtons()
        if (isHand) {
            candidateText.text = if (handwriteRecognizer != null)
                "手写模式：连笔书写，停顿自动识别；点击“退出手写”返回"
            else "手写模型未就绪"
        } else {
            candidateText.text = voiceManager.getModelStatusSummary()
        }
    }

    private fun highlightModeButtons() {
        val active = Color.parseColor("#1A1C1E")
        val normal = Color.parseColor("#8A9099")
        btnModeLetters.setTextColor(if (mode == Mode.LETTERS) active else normal)
        btnModeNumbers.setTextColor(if (mode == Mode.NUMBERS) active else normal)
        btnModeEmoji.setTextColor(if (mode == Mode.EMOJI) active else normal)
        btnModeHandwrite.setTextColor(if (mode == Mode.HANDWRITE) active else normal)
    }

    // ---------------------------------------------------------------- 键区渲染

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
                textSize = if (key.length <= 2) 15f else 12f
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
            key == "表情" -> { switchMode(Mode.EMOJI); return }
            key == "手写" -> { switchMode(Mode.HANDWRITE); return }
            key == "更多" -> { emojiPage = (emojiPage + 1) % emojiPages.size; renderKeys(); return }
            else -> input.commit(key)
        }
    }

    private val emojiPages = listOf(
        listOf(
            listOf("😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃"),
            listOf("😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😙"),
            listOf("😋","😛","😜","🤪","🤨","🧐","🤓","😎","🥳","😏"),
            listOf("😒","😞","😔","😟","😕","🙁","😣","😖","😫","😩")
        ),
        listOf(
            listOf("🤝","👍","👎","👌","✌️","🤞","🤟","🤘","👏","🙌"),
            listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯"),
            listOf("🌸","🌹","🌻","🌴","🌵","🍀","🌙","⭐","🔥","🌈"),
            listOf("🍎","🍐","🍊","🍌","🍉","🍇","🍓","🍒","🥕","🌽")
        ),
        listOf(
            listOf("❤️","🧡","💛","💚","💙","💜","🖤","💔","💯","✅"),
            listOf("⚽","🏀","🏈","⚾","🎾","🏐","🏓","🏸","🥅","🎯"),
            listOf("🏠","🚗","🚕","🚌","🚲","✈️","🚀","🚢","🚂","🛵"),
            listOf("⌚","📱","💻","🖥","📷","🔋","💡","📚","✏️","📎")
        )
    )

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
                addKeyRow(listOf("ABC", "符号", "表情", "空格", "回车"), isFunction = true)
            }
            Mode.SYMBOLS -> {
                addKeyRow(listOf("，", "。", "！", "？", "、", "：", "；", "“", "”", "⌫"))
                addKeyRow(listOf("…", "—", "·", "￥", "%", "#", "&", "*", "+", "="))
                addKeyRow(listOf("ABC", "123", "表情", "手写", "回车"), isFunction = true)
            }
            Mode.EMOJI -> {
                val page = emojiPages[emojiPage]
                for (rowKeys in page) addKeyRow(rowKeys)
                addKeyRow(listOf("ABC", "123", "符号", "更多", "⌫"), isFunction = true)
            }
            Mode.HANDWRITE -> { /* 手写模式下 rowsContainer 隐藏 */ }
        }
    }

    // ---------------------------------------------------------------- 手写候选

    private fun showCandidates(candidates: List<String>) {
        if (candidates.isEmpty()) return
        // 首选候选自动上屏（Gboard 连续书写体验），其余候选展示可替换
        input.commit(candidates[0])
        committedCharCount = candidates[0].length
        candidateList.removeAllViews()
        alternativeCandidates = candidates.drop(1)
        if (alternativeCandidates.isEmpty()) {
            candidateStrip.visibility = GONE
            handwriteView.clear()
            return
        }
        for (cand in alternativeCandidates) {
            val btn = Button(context).apply {
                text = cand
                textSize = 20f
                setTextColor(Color.parseColor("#1A1C1E"))
                setBackgroundColor(Color.parseColor("#F0F2F7"))
                minHeight = 0
                minimumHeight = 0
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(40))
                setPadding(dp(12), 0, dp(12), 0)
            }
            btn.setOnClickListener {
                // 用所选候选替换刚上屏的字
                repeat(committedCharCount) { input.backspace() }
                input.commit(cand)
                committedCharCount = cand.length
                hideCandidates()
                handwriteView.clear()
            }
            candidateList.addView(btn)
        }
        candidateStrip.visibility = VISIBLE
        handwriteView.clear()
    }

    private fun hideCandidates() {
        alternativeCandidates = emptyList()
        candidateList.removeAllViews()
        candidateStrip.visibility = GONE
    }

    // ---------------------------------------------------------------- 剪贴板

    /** 由 InputMethodService 收到系统剪贴板变化时调用（默认输入法有权限读取）。 */
    fun pushClipboardEntry(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (t.length > 200) return
        clipboardHistory.removeAll { it == t }
        clipboardHistory.add(0, t)
        while (clipboardHistory.size > 8) clipboardHistory.removeAt(clipboardHistory.size - 1)
        if (clipboardRowOpen) renderClipboardRow()
    }

    fun updateRecognizer(rec: HCCRRecognizer?) {
        handwriteRecognizer = rec
        handwriteView.recognizer = rec
    }

    private fun toggleClipboardRow() {
        clipboardRowOpen = !clipboardRowOpen
        if (clipboardRowOpen) renderClipboardRow() else clipboardRow.visibility = GONE
    }

    private fun renderClipboardRow() {
        clipboardList.removeAllViews()
        if (clipboardHistory.isEmpty()) {
            val empty = TextView(context).apply {
                text = "剪贴板为空：复制文字后自动出现在这里"
                textSize = 12f
                setTextColor(Color.parseColor("#8A9099"))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
            }
            clipboardList.addView(empty)
        } else {
            for (entry in clipboardHistory) {
                val btn = Button(context).apply {
                    text = if (entry.length > 24) entry.take(24) + "…" else entry
                    textSize = 13f
                    setTextColor(Color.parseColor("#1A1C1E"))
                    setBackgroundColor(Color.parseColor("#F0F2F7"))
                    minHeight = 0
                    minimumHeight = 0
                    setPadding(dp(12), 0, dp(12), 0)
                    layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(40))
                }
                val full = entry
                btn.setOnClickListener {
                    input.commit(full)
                    clipboardRowOpen = false
                    clipboardRow.visibility = GONE
                }
                clipboardList.addView(btn)
            }
        }
        clipboardRow.visibility = VISIBLE
    }

    // ---------------------------------------------------------------- 语音

    private fun toggleVoice() {
        if (!isRecording) {
            if (!voiceManager.isModelReady()) {
                candidateText.text = "请先在主界面下载语音模型"
                return
            }
            isRecording = true
            btnVoice.text = "停止"
            btnVoice.setTextColor(Color.parseColor("#D32F2F"))
            voiceManager.startRecording(scope) { s -> candidateText.text = s }
        } else {
            isRecording = false
            btnVoice.text = "🎤"
            btnVoice.setTextColor(Color.parseColor("#3F6FE4"))
            candidateText.text = "正在识别…"
            scope.launch {
                val text = voiceManager.stopRecordingAndRecognize { s -> candidateText.text = s }
                if (text.isNotEmpty()) input.commit(text)
                candidateText.text = voiceManager.getModelStatusSummary()
            }
        }
    }

    fun updateStatus(text: String) {
        candidateText.text = text
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
