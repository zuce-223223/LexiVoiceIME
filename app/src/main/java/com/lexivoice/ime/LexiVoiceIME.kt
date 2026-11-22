package com.lexivoice.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LexiVoiceIME : InputMethodService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var voiceManager: VoiceRecognitionManager
    private var isRecording = false

    private var tvStatus: TextView? = null
    private var btnRecord: Button? = null

    override fun onCreate() {
        super.onCreate()
        voiceManager = VoiceRecognitionManager(applicationContext)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_keyboard_view, null)
        tvStatus = view.findViewById(R.id.tv_ime_status)
        btnRecord = view.findViewById(R.id.btn_voice_toggle)

        tvStatus?.text = voiceManager.getModelStatusSummary()

        btnRecord?.setOnClickListener {
            if (!isRecording) {
                startVoiceInput()
            } else {
                stopVoiceInputAndCommit()
            }
        }

        view.findViewById<Button>(R.id.btn_space)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        view.findViewById<Button>(R.id.btn_backspace)?.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }

        view.findViewById<Button>(R.id.btn_enter)?.setOnClickListener {
            val ic = currentInputConnection ?: return@setOnClickListener
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }

        return view
    }

    private fun startVoiceInput() {
        isRecording = true
        btnRecord?.text = getString(R.string.btn_stop_voice)
        voiceManager.startRecording(serviceScope) { status ->
            tvStatus?.text = status
        }
    }

    private fun stopVoiceInputAndCommit() {
        isRecording = false
        btnRecord?.text = getString(R.string.btn_start_voice)
        tvStatus?.text = getString(R.string.status_recognizing)

        serviceScope.launch {
            val recognizedText = voiceManager.stopRecordingAndRecognize { status ->
                tvStatus?.text = status
            }
            if (recognizedText.isNotEmpty()) {
                currentInputConnection?.commitText(recognizedText, 1)
            }
            tvStatus?.text = voiceManager.getModelStatusSummary()
        }
    }

    override fun onDestroy() {
        voiceManager.release()
        serviceScope.cancel()
        super.onDestroy()
    }
}
