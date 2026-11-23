package com.lexivoice.ime

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import com.shiyu.handwritten.runtime.HCCRRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LexiVoiceIME : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var voiceManager: VoiceRecognitionManager
    private var handwriteRecognizer: HCCRRecognizer? = null
    private var keyboardPanel: KeyboardPanel? = null
    private var clipboardManager: ClipboardManager? = null

    private val inputBridge = object : KeyboardPanel.InputBridge {
        override fun commit(text: String) {
            currentInputConnection?.commitText(text, 1)
        }

        override fun backspace() {
            val ic = currentInputConnection ?: return
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }

        override fun enter() {
            val ic = currentInputConnection ?: return
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        override fun status(text: String) {
            keyboardPanel?.updateStatus(text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        voiceManager = VoiceRecognitionManager(applicationContext)
        loadHandwritingModel()
        setupClipboardListener()
    }

    private fun loadHandwritingModel() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                HCCRRecognizer.loadNativeLibrary("hccr_jni")
                val rec = HCCRRecognizer(
                    assets,
                    "mbv2_aug.int8.ncnn.param",
                    "mbv2_aug.int8.ncnn.bin",
                    "charset.json"
                )
                withContext(Dispatchers.Main) {
                    handwriteRecognizer = rec
                    keyboardPanel?.updateRecognizer(rec)
                }
            } catch (e: Exception) {
                android.util.Log.w("LexiVoiceIME", "手写模型加载失败：${e.message}")
            }
        }
    }

    /**
     * 剪贴板监听：默认输入法拥有系统剪贴板读取权限（Android 10+ 仅豁免默认 IME）。
     * 用户在任意应用复制文字时记录到键盘剪贴板面板。
     */
    private fun setupClipboardListener() {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardManager?.addPrimaryClipChangedListener {
            try {
                val clip = clipboardManager?.primaryClip ?: return@addPrimaryClipChangedListener
                if (clip.itemCount > 0) {
                    val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return@addPrimaryClipChangedListener
                    keyboardPanel?.pushClipboardEntry(text)
                }
            } catch (_: Exception) {
                // Android 10+ 非默认 IME 或后台限制时静默忽略
            }
        }
    }

    override fun onCreateInputView(): View {
        val panel = KeyboardPanel(this, inputBridge, voiceManager, handwriteRecognizer, serviceScope)
        keyboardPanel = panel
        return panel
    }

    override fun onDestroy() {
        voiceManager.release()
        try {
            handwriteRecognizer?.close()
        } catch (_: Exception) {
        }
        clipboardManager?.removePrimaryClipChangedListener { }
        serviceScope.cancel()
        super.onDestroy()
    }
}
