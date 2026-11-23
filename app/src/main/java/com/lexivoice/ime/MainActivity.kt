package com.lexivoice.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var voiceManager: VoiceRecognitionManager
    private lateinit var modelManager: ModelDownloadManager
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvImeStatus: TextView
    private lateinit var tvModelInfo: TextView
    private lateinit var tvDownloadStatus: TextView
    private lateinit var progressModel: ProgressBar
    private lateinit var btnDownload: Button
    private var downloading = false

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatuses() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceManager = VoiceRecognitionManager(applicationContext)
        modelManager = ModelDownloadManager(applicationContext)
        tvPermissionStatus = findViewById(R.id.tv_permission_status)
        tvImeStatus = findViewById(R.id.tv_ime_status)
        tvModelInfo = findViewById(R.id.tv_model_info)
        tvDownloadStatus = findViewById(R.id.tv_download_status)
        progressModel = findViewById(R.id.progress_model)
        btnDownload = findViewById(R.id.btn_download_model)

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
        btnDownload.setOnClickListener { downloadModel() }

        refreshStatuses()
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun downloadModel() {
        if (downloading) return
        downloading = true
        btnDownload.isEnabled = false
        progressModel.visibility = ProgressBar.VISIBLE
        tvDownloadStatus.visibility = TextView.VISIBLE
        progressModel.progress = 0

        lifecycleScope.launch {
            val ok = modelManager.download { pct, msg ->
                progressModel.progress = pct
                tvDownloadStatus.text = msg
            }
            downloading = false
            btnDownload.isEnabled = true
            progressModel.visibility = ProgressBar.GONE
            tvDownloadStatus.text = if (ok) "语音模型已下载完成" else "语音模型下载失败，请检查网络"
            tvDownloadStatus.visibility = TextView.VISIBLE
            refreshStatuses()
        }
    }

    private fun refreshStatuses() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        tvPermissionStatus.text = if (granted) "录音权限：已授权 ✓" else "录音权限：未授权"

        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        tvImeStatus.text = if (enabled) "输入法状态：已启用 ✓" else "输入法状态：尚未启用"

        tvModelInfo.text = if (modelManager.isModelReady()) {
            "语音模型：已就绪（可离线识别）✓"
        } else {
            "语音模型：未下载（首次语音需联网下载约 156 MB）"
        }
        btnDownload.text = if (modelManager.isModelReady()) "重新下载语音模型" else "下载语音模型"
    }
}
