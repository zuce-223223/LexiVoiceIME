package com.lexivoice.ime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 语音模型下载与解压：从 GitHub release 下载 SenseVoice int8 模型包，
 * 解压出 model.int8.onnx 与 tokens.txt 到 App 私有目录，供 sherpa-onnx 从文件加载。
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
        const val MODEL_DIR = "sense-voice"
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
    }

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR).apply { if (!exists()) mkdirs() }

    val modelPath: String get() = File(modelDir, MODEL_FILE).absolutePath
    val tokensPath: String get() = File(modelDir, TOKENS_FILE).absolutePath

    fun isModelReady(): Boolean =
        File(modelDir, MODEL_FILE).let { it.exists() && it.length() > 1_000_000L } &&
                File(modelDir, TOKENS_FILE).exists()

    suspend fun download(onProgress: (Int, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val archive = File(context.cacheDir, "sense-voice.tar.bz2")
        try {
            onProgress(2, "正在连接服务器…")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.requestMethod = "GET"
            conn.connect()

            if (conn.responseCode !in 200..299) {
                onProgress(0, "下载失败：HTTP ${conn.responseCode}")
                return@withContext false
            }

            val total = conn.contentLength.toDouble()
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(archive).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastPct = -1
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = (written / total * 88).toInt().coerceIn(2, 88)
                            if (pct != lastPct) {
                                lastPct = pct
                                val mb = written / 1024 / 1024
                                onProgress(pct, "正在下载模型 ${mb} MB")
                            }
                        }
                    }
                }
            }
            conn.disconnect()

            onProgress(90, "正在解压模型…")
            TarArchiveInputStream(
                BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))
            ).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val name = File(entry.name).name
                    val target = when (name) {
                        MODEL_FILE -> File(modelDir, MODEL_FILE)
                        TOKENS_FILE -> File(modelDir, TOKENS_FILE)
                        else -> null
                    }
                    if (target != null && !entry.isDirectory) {
                        FileOutputStream(target).use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextTarEntry
                }
            }

            onProgress(100, "模型下载完成")
            isModelReady()
        } catch (e: Exception) {
            onProgress(0, "下载失败：${e.message}")
            false
        } finally {
            if (archive.exists()) archive.delete()
        }
    }

    fun deleteModel() {
        File(modelDir, MODEL_FILE).delete()
        File(modelDir, TOKENS_FILE).delete()
    }
}
