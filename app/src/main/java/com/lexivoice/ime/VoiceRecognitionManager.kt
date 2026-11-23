package com.lexivoice.ime

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 语音识别：录音 → PCM → sherpa-onnx SenseVoice 离线识别。
 * 模型文件由 ModelDownloadManager 下载到 App 私有目录，从这里按文件路径加载。
 */
class VoiceRecognitionManager(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val downloadManager = ModelDownloadManager(context)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentPcmFile: File? = null
    private var recognizer: OfflineRecognizer? = null

    fun isModelReady(): Boolean = downloadManager.isModelReady()

    fun getModelStatusSummary(): String =
        if (isModelReady()) "语音模型已就绪 · 可离线语音输入"
        else "语音模型未下载 · 点「下载语音模型」后可用"

    private fun getOrCreateRecognizer(): OfflineRecognizer {
        return recognizer ?: OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = downloadManager.modelPath,
                        useInverseTextNormalization = true
                    ),
                    tokens = downloadManager.tokensPath,
                    numThreads = 4
                ),
                decodingMethod = "greedy_search"
            )
        ).also { recognizer = it }
    }

    fun startRecording(scope: CoroutineScope, onStatusChanged: (String) -> Unit) {
        if (recordingJob?.isActive == true) return

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) {
            onStatusChanged("音频缓冲区初始化失败")
            return
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBuffer * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onStatusChanged("麦克风初始化失败，请检查录音权限")
            return
        }

        val outputFile = File(context.cacheDir, "lexivoice_latest.pcm")
        currentPcmFile = outputFile
        audioRecord = recorder
        recorder.startRecording()
        onStatusChanged("正在录音…")

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBuffer)
            var totalBytes = 0L
            try {
                FileOutputStream(outputFile).use { out ->
                    while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val n = recorder.read(buffer, 0, buffer.size)
                        if (n > 0) {
                            out.write(buffer, 0, n)
                            totalBytes += n
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatusChanged("录音异常：${e.message}") }
            } finally {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                } catch (_: IllegalStateException) {
                }
                recorder.release()
                if (audioRecord === recorder) audioRecord = null
            }
            val sec = String.format("%.1f", totalBytes.toDouble() / (SAMPLE_RATE * 2))
            withContext(Dispatchers.Main) { onStatusChanged("录音完成（约 ${sec}s）") }
        }
    }

    suspend fun stopRecordingAndRecognize(onStatusChanged: (String) -> Unit = {}): String {
        recordingJob?.cancelAndJoinSafely()
        recordingJob = null

        return withContext(Dispatchers.IO) {
            val pcmFile = currentPcmFile
            val byteLength = pcmFile?.takeIf { it.exists() }?.length() ?: 0L
            val shortCount = (byteLength / 2).toInt()
            if (shortCount == 0) {
                withContext(Dispatchers.Main) { onStatusChanged("未采集到有效音频") }
                return@withContext ""
            }
            if (!isModelReady()) {
                withContext(Dispatchers.Main) { onStatusChanged("语音模型未下载") }
                return@withContext ""
            }

            val samples = ShortArray(shortCount)
            pcmFile!!.inputStream().use { input ->
                val raw = ByteArray(shortCount * 2)
                val read = input.read(raw)
                if (read > 0) {
                    val buf = ByteBuffer.wrap(raw, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                    var i = 0
                    while (buf.remaining() >= 2 && i < samples.size) samples[i++] = buf.short
                }
            }

            val rec = try {
                withContext(Dispatchers.Main) { onStatusChanged("正在加载离线模型…") }
                getOrCreateRecognizer()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatusChanged("模型加载失败：${e.message}") }
                return@withContext ""
            }

            val floats = FloatArray(shortCount) { samples[it] / 32768f }
            try {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(floats, SAMPLE_RATE)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    withContext(Dispatchers.Main) {
                        onStatusChanged(if (text.isEmpty()) "识别完成，未得到文字" else "识别完成")
                    }
                    text
                } finally {
                    stream.release()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatusChanged("识别失败：${e.message}") }
                ""
            }
        }
    }

    private suspend fun Job.cancelAndJoinSafely() {
        try { cancel(); join() } catch (_: Exception) {}
    }

    fun release() {
        recordingJob?.cancel()
        recordingJob = null
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }
}
