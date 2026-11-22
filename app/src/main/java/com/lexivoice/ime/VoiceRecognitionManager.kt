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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceRecognitionManager(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SENSE_VOICE_ASSET_DIR = "sense-voice"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentPcmFile: File? = null
    private var recognizer: OfflineRecognizer? = null

    fun getModelStatusSummary(): String {
        return try {
            val files = context.assets.list(SENSE_VOICE_ASSET_DIR)?.filter { it != "README.txt" }.orEmpty()
            if (files.isEmpty()) {
                "模型目录 assets/sense-voice 暂无权重文件"
            } else {
                "已检测到模型资源: ${files.joinToString(", ")}"
            }
        } catch (e: IOException) {
            "读取模型目录失败: ${e.message}"
        }
    }

    fun hasModelFiles(): Boolean {
        return try {
            val files = context.assets.list(SENSE_VOICE_ASSET_DIR)?.filter { it != "README.txt" }.orEmpty()
            files.isNotEmpty()
        } catch (_: IOException) {
            false
        }
    }

    private fun getOrCreateRecognizer(): OfflineRecognizer {
        return recognizer ?: OfflineRecognizer(
            assetManager = context.assets,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$SENSE_VOICE_ASSET_DIR/model.int8.onnx",
                        useInverseTextNormalization = true
                    ),
                    tokens = "$SENSE_VOICE_ASSET_DIR/tokens.txt",
                    numThreads = 4
                ),
                decodingMethod = "greedy_search"
            )
        ).also { recognizer = it }
    }

    fun startRecording(scope: CoroutineScope, onStatusChanged: (String) -> Unit) {
        if (recordingJob?.isActive == true) return

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            onStatusChanged("音频缓冲区初始化失败")
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 2
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
        onStatusChanged("正在录音...")

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(minBufferSize)
            var totalBytes = 0L

            try {
                FileOutputStream(outputFile).use { out ->
                    while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val readBytes = recorder.read(buffer, 0, buffer.size)
                        if (readBytes > 0) {
                            out.write(buffer, 0, readBytes)
                            totalBytes += readBytes
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onStatusChanged("录音写入异常: ${e.message}")
                }
            } finally {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: IllegalStateException) {
                }
                recorder.release()
                if (audioRecord === recorder) {
                    audioRecord = null
                }
            }

            val durationSec = (totalBytes.toDouble() / (SAMPLE_RATE * 2)).let {
                String.format("%.1f", it)
            }
            withContext(Dispatchers.Main) {
                onStatusChanged("录音完成（约 ${durationSec}s）")
            }
        }
    }

    suspend fun stopRecordingAndRecognize(onStatusChanged: (String) -> Unit = {}): String {
        recordingJob?.cancelAndJoinSafely()
        recordingJob = null

        return withContext(Dispatchers.IO) {
            val pcmFile = currentPcmFile
            val byteLength = pcmFile?.takeIf { it.exists() }?.length() ?: 0L
            val shortCount = (byteLength / 2).toInt()
            val samples = ShortArray(shortCount)

            if (pcmFile != null && shortCount > 0) {
                pcmFile.inputStream().use { input ->
                    val rawBytes = ByteArray(shortCount * 2)
                    val read = input.read(rawBytes)
                    if (read > 0) {
                        val buf = ByteBuffer.wrap(rawBytes, 0, read).order(ByteOrder.LITTLE_ENDIAN)
                        var idx = 0
                        while (buf.remaining() >= 2 && idx < samples.size) {
                            samples[idx++] = buf.short
                        }
                    }
                }
            }

            if (shortCount == 0) {
                withContext(Dispatchers.Main) { onStatusChanged("未采集到有效音频") }
                return@withContext ""
            }

            if (!hasModelFiles()) {
                withContext(Dispatchers.Main) { onStatusChanged("未检测到 assets/sense-voice 模型文件") }
                return@withContext ""
            }

            val rec = try {
                withContext(Dispatchers.Main) { onStatusChanged("正在加载离线识别模型...") }
                getOrCreateRecognizer()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatusChanged("模型加载失败: ${e.message}") }
                return@withContext ""
            }

            val floatSamples = FloatArray(shortCount) { index -> samples[index] / 32768f }

            try {
                val stream = rec.createStream()
                try {
                    stream.acceptWaveform(floatSamples, SAMPLE_RATE)
                    rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    withContext(Dispatchers.Main) {
                        onStatusChanged(if (text.isEmpty()) "识别完成，但未得到文字" else "识别完成")
                    }
                    text
                } finally {
                    stream.release()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onStatusChanged("识别失败: ${e.message}") }
                ""
            }
        }
    }

    private suspend fun Job.cancelAndJoinSafely() {
        try {
            cancel()
            join()
        } catch (_: Exception) {
        }
    }

    fun release() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            recognizer?.release()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
