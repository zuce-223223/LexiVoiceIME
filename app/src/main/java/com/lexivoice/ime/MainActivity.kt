package com.lexivoice.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var voiceManager: VoiceRecognitionManager
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvImeStatus: TextView
    private lateinit var tvModelInfo: TextView

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            refreshStatuses()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceManager = VoiceRecognitionManager(applicationContext)
        tvPermissionStatus = findViewById(R.id.tv_permission_status)
        tvImeStatus = findViewById(R.id.tv_ime_status)
        tvModelInfo = findViewById(R.id.tv_model_info)

        findViewById<Button>(R.id.btn_grant_audio).setOnClickListener {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.btn_open_ime_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_switch_ime).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }

        refreshStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        tvPermissionStatus.text = if (granted) {
            "录音权限 (RECORD_AUDIO): 已授权"
        } else {
            "录音权限 (RECORD_AUDIO): 未授权"
        }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabled = imm?.enabledInputMethodList?.any {
            it.packageName == packageName
        } == true
        tvImeStatus.text = if (enabled) {
            "系统输入法状态: 已在系统设置中启用"
        } else {
            "系统输入法状态: 尚未启用"
        }

        tvModelInfo.text = voiceManager.getModelStatusSummary()
    }
}
