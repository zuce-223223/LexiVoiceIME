package com.lexivoice.ime

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
                withContext(Dispatchers.Main) { handwriteRecognizer = rec }
            } catch (e: Exception) {
                android.util.Log.w("LexiVoiceIME", "手写模型加载失败：${e.message}")
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
        serviceScope.cancel()
        super.onDestroy()
    }
}
