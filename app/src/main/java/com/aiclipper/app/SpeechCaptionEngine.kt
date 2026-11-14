package com.aiclipper.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

data class CaptionWord(val startMs: Long, val endMs: Long, val text: String)
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String, val words: List<CaptionWord> = emptyList())
data class AudioEnergyPoint(val timeMs: Long, val rms: Float)
data class SpeakerTurn(val startMs: Long, val endMs: Long, val speakerId: Int, val confidence: Float)
data class SpeechAnalysisResult(val captions: List<CaptionCue>, val audioEnergy: List<AudioEnergyPoint>, val speakerTurns: List<SpeakerTurn>)
private data class SpeakerCluster(val id: Int, val centroid: MutableList<Float>, var count: Int)

private class EnergyBucket {
    var sumSquares: Double = 0.0
    var sampleCount: Long = 0L
}

object SpeechCaptionEngine {
    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val TARGET_RATE = 16000
    private const val SPEAKER_MODEL_NAME = "vosk-model-spk-0.4"
    private const val SPEAKER_MODEL_URL = "https://alphacephei.com/vosk/models/$SPEAKER_MODEL_NAME.zip"

    fun isModelInstalled(context: Context): Boolean {
        val modelDir = File(context.filesDir, MODEL_NAME)
        return File(modelDir, "conf/model.conf").isFile
    }

    fun isSpeakerModelInstalled(context: Context): Boolean {
        val dir = File(context.filesDir, SPEAKER_MODEL_NAME)
        return listOf("mfcc.conf", "final.ext.raw", "mean.vec", "transform.mat").all { File(dir, it).isFile }
    }

    fun downloadSpeakerModel(context: Context, onProgress: (Float) -> Unit): File {
        val root = context.filesDir
        val target = File(root, SPEAKER_MODEL_NAME)
        if (isSpeakerModelInstalled(context)) return target
        val archive = File(context.cacheDir, "$SPEAKER_MODEL_NAME.zip.part")
        val connection = (URL(SPEAKER_MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("Speaker model download failed (HTTP ${connection.responseCode})")
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
        } finally { connection.disconnect() }
        val canonicalRoot = root.canonicalFile
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val outputFile = File(root, entry.name).canonicalFile
                    if (!outputFile.path.startsWith(canonicalRoot.path + File.separator)) throw SecurityException("Invalid speaker model archive path")
                    if (entry.isDirectory) outputFile.mkdirs() else {
                        outputFile.parentFile?.mkdirs()
                        FileOutputStream(outputFile).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } finally { archive.delete() }
        if (!isSpeakerModelInstalled(context)) {
            target.deleteRecursively()
            throw IllegalStateException("Speaker model archive is missing required files")
        }
        onProgress(1f)
        return target
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

    fun transcribe(context: Context, source: Uri, durationMs: Long, onProgress: (Float) -> Unit): SpeechAnalysisResult {
        val modelDir = File(context.filesDir, MODEL_NAME)
        if (!isModelInstalled(context)) {
            throw IllegalStateException("The offline English speech model is not installed correctly.")
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var model: Model? = null
        var speakerModel: SpeakerModel? = null
        var recognizerForCleanup: Recognizer? = null
        val rawWords = mutableListOf<CaptionCue>()
        val speakerTurns = mutableListOf<SpeakerTurn>()
        val speakerClusters = mutableListOf<SpeakerCluster>()
        val energyBuckets = sortedMapOf<Long, EnergyBucket>()
        try {
            val loadedModel = Model(modelDir.absolutePath)
            model = loadedModel
            val loadedSpeakerModel = if (isSpeakerModelInstalled(context)) {
                SpeakerModel(File(context.filesDir, SPEAKER_MODEL_NAME).absolutePath).also { speakerModel = it }
            } else null
            val recognizer = if (loadedSpeakerModel != null) {
                Recognizer(loadedModel, TARGET_RATE.toFloat(), loadedSpeakerModel)
            } else {
                Recognizer(loadedModel, TARGET_RATE.toFloat())
            }
            recognizerForCleanup = recognizer
            recognizer.setWords(true)
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
                            var frameIndex = 0
                            repeat(frameCount) {
                                var sum = 0
                                repeat(channels) { sum += pcm.short.toInt() }
                                val mono = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                val sampleTimeMs = info.presentationTimeUs.coerceAtLeast(0L) / 1000L +
                                    frameIndex.toLong() * 1000L / sampleRate.toLong().coerceAtLeast(1L)
                                val bucket = energyBuckets.getOrPut(sampleTimeMs / 1000L) { EnergyBucket() }
                                bucket.sumSquares += mono.toDouble() * mono.toDouble()
                                bucket.sampleCount++
                                frameIndex++
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
                                    collectWords(recognizer.result, rawWords, speakerTurns, speakerClusters)
                                }
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(outputIndex, false)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 350) {
                            val totalDurationUs = durationMs.coerceAtLeast(1L) * 1000L
                            onProgress((info.presentationTimeUs.coerceAtLeast(0L).toFloat() / totalDurationUs.toFloat()).coerceIn(0f, 0.99f))
                            lastProgressUpdate = now
                        }
                    }
                }
            }
            collectWords(recognizer.finalResult, rawWords, speakerTurns, speakerClusters)
            val energy = energyBuckets.map { (second, bucket) ->
                val rms = if (bucket.sampleCount == 0L) 0.0 else
                    kotlin.math.sqrt(bucket.sumSquares / bucket.sampleCount.toDouble()) / 32768.0
                AudioEnergyPoint(second * 1000L, rms.toFloat())
            }
            onProgress(1f)
            return SpeechAnalysisResult(groupWords(rawWords), energy, speakerTurns)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
            try { recognizerForCleanup?.close() } catch (_: Exception) {}
            try { speakerModel?.close() } catch (_: Exception) {}
            try { model?.close() } catch (_: Exception) {}
        }
    }

    private fun collectWords(
        json: String,
        destination: MutableList<CaptionCue>,
        speakerTurns: MutableList<SpeakerTurn>,
        clusters: MutableList<SpeakerCluster>
    ) {
        val parsed = JSONObject(json)
        val words = parsed.optJSONArray("result")
        var startMs = Long.MAX_VALUE
        var endMs = 0L
        if (words != null) for (i in 0 until words.length()) {
            val word = words.optJSONObject(i) ?: continue
            val text = word.optString("word").trim()
            if (text.isNotEmpty()) {
                val start = (word.optDouble("start", 0.0) * 1000).toLong().coerceAtLeast(0L)
                val end = (word.optDouble("end", 0.0) * 1000).toLong().coerceAtLeast(start + 150L)
                destination.add(CaptionCue(start, end, text))
                startMs = minOf(startMs, start)
                endMs = maxOf(endMs, end)
            }
        }
        val speakerVector = parsed.optJSONArray("spk")
        if (speakerVector != null && startMs != Long.MAX_VALUE && endMs > startMs) {
            val vector = (0 until speakerVector.length()).map { speakerVector.optDouble(it, 0.0).toFloat() }
            if (vector.isNotEmpty() && vector.all { it.isFinite() }) {
                val closest = clusters.map { it to cosineSimilarity(vector, it.centroid) }.maxByOrNull { it.second }
                val match = closest?.takeIf { it.second >= 0.72f }
                val id: Int
                if (match == null) {
                    id = clusters.size + 1
                    clusters.add(SpeakerCluster(id, vector.toMutableList(), 1))
                } else {
                    val cluster = match.first
                    id = cluster.id
                    for (i in vector.indices) cluster.centroid[i] = (cluster.centroid[i] * cluster.count + vector[i]) / (cluster.count + 1)
                    cluster.count++
                }
                speakerTurns.add(SpeakerTurn(startMs, endMs, id, match?.second ?: 1f))
            }
        }
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa <= 0.0 || bb <= 0.0) return -1f
        return (dot / kotlin.math.sqrt(aa * bb)).toFloat()
    }

    private fun groupWords(words: List<CaptionCue>): List<CaptionCue> {
        if (words.isEmpty()) return emptyList()
        val output = mutableListOf<CaptionCue>()
        var group = mutableListOf<CaptionCue>()
        for (word in words) {
            if (group.isNotEmpty() && (group.size >= 5 || word.startMs - group.last().endMs > 700L)) {
                output.add(
                    CaptionCue(
                        group.first().startMs,
                        group.last().endMs,
                        group.joinToString(" ") { it.text },
                        group.map { CaptionWord(it.startMs, it.endMs, it.text) }
                    )
                )
                group = mutableListOf()
            }
            group.add(word)
        }
        if (group.isNotEmpty()) {
            output.add(
                CaptionCue(
                    group.first().startMs,
                    group.last().endMs,
                    group.joinToString(" ") { it.text },
                    group.map { CaptionWord(it.startMs, it.endMs, it.text) }
                )
            )
        }
        return output
    }
}
