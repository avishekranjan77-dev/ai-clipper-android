package com.aiclipper.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

object SpeechCaptionEngine {
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val TARGET_RATE = 16000

    fun isModelInstalled(context: Context): Boolean {
        val modelDir = File(context.filesDir, MODEL_NAME)
        return File(modelDir, "conf/model.conf").isFile
    }

    fun downloadModel(context: Context, onProgress: (Float) -> Unit): File {
        val root = context.filesDir
        val target = File(root, MODEL_NAME)
        if (File(target, "conf/model.conf").isFile) return target
        val archive = File(context.cacheDir, "$MODEL_NAME.zip.part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Model download failed (HTTP ${connection.responseCode})")
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(archive).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val rootCanonical = root.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(root, entry.name).canonicalFile
                if (!out.path.startsWith(rootCanonical.path + File.separator)) {
                    throw SecurityException("Invalid model archive path")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        archive.delete()
        if (!File(target, "conf/model.conf").isFile) {
            target.deleteRecursively()
            throw IllegalStateException("Downloaded speech model is incomplete")
        }
        onProgress(1f)
        return target
    }

    fun transcribe(context: Context, source: Uri, onProgress: (Float) -> Unit): List<CaptionCue> {
        val modelDir = File(context.filesDir, MODEL_NAME)
        if (!File(modelDir, "conf/model.conf").isFile) {
            throw IllegalStateException("Download the English speech model first")
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, TARGET_RATE.toFloat())
        recognizer.setWords(true)
        val rawWords = mutableListOf<CaptionCue>()
        try {
            extractor.setDataSource(context, source, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = candidate
                    break
                }
            }
            if (audioTrack < 0 || format == null) {
                throw IllegalStateException("This video does not contain an audio track")
            }
            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var phase = 0L
            val info = MediaCodec.BufferInfo()
            var lastProgressUpdate = 0L
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val pcm = outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val mono16k = java.io.ByteArrayOutputStream()
                            val frameCount = pcm.remaining() / (2 * channels)
                            repeat(frameCount) {
                                var sum = 0
                                repeat(channels) { sum += pcm.short.toInt() }
                                val mono = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                phase += TARGET_RATE.toLong()
                                if (phase >= sampleRate.toLong()) {
                                    phase -= sampleRate.toLong()
                                    mono16k.write(mono and 0xff)
                                    mono16k.write((mono shr 8) and 0xff)
                                }
                            }
                            val audioChunk = mono16k.toByteArray()
                            if (audioChunk.isNotEmpty()) {
                                if (recognizer.acceptWaveForm(audioChunk, audioChunk.size)) {
                                    collectWords(recognizer.result, rawWords)
                                }
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(outputIndex, false)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 500) {
                            onProgress((extractor.sampleTime.coerceAtLeast(0L).toFloat() / 1_000_000f).coerceIn(0f, 0.95f))
                            lastProgressUpdate = now
                        }
                    }
                }
            }
            collectWords(recognizer.finalResult, rawWords)
            return groupWords(rawWords)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
            recognizer.close()
            model.close()
        }
    }

    private fun collectWords(json: String, destination: MutableList<CaptionCue>) {
        val result = JSONObject(json).optJSONArray("result") ?: return
        for (i in 0 until result.length()) {
            val word = result.optJSONObject(i) ?: continue
            val text = word.optString("word").trim()
            if (text.isNotEmpty()) {
                val start = (word.optDouble("start", 0.0) * 1000).toLong().coerceAtLeast(0L)
                val end = (word.optDouble("end", 0.0) * 1000).toLong().coerceAtLeast(start + 150L)
                destination.add(CaptionCue(start, end, text))
            }
        }
    }

    private fun groupWords(words: List<CaptionCue>): List<CaptionCue> {
        if (words.isEmpty()) return emptyList()
        val output = mutableListOf<CaptionCue>()
        var group = mutableListOf<CaptionCue>()
        for (word in words) {
            if (group.isNotEmpty() && (group.size >= 5 || word.startMs - group.last().endMs > 700L)) {
                output.add(CaptionCue(group.first().startMs, group.last().endMs, group.joinToString(" ") { it.text }))
                group = mutableListOf()
            }
            group.add(word)
        }
        if (group.isNotEmpty()) {
            output.add(CaptionCue(group.first().startMs, group.last().endMs, group.joinToString(" ") { it.text }))
        }
        return output
    }
}
