package com.aiclipper.app

import android.content.Context
import android.os.StatFs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

/** Free on-device transcript understanding. The text model is fused with separate visual/audio signals. */
data class SemanticHighlight(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val reason: String,
    val score: Int
) {
    val durationMs: Long get() = endMs - startMs
}

object SemanticHighlightEngine {
    const val MODEL_SIZE_LABEL = "about 586 MB"
    private const val MODEL_FILE = "qwen3_0_6b.litertlm"
    private const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm?download=true"
    private const val MIN_MODEL_BYTES = 500L * 1024L * 1024L
    private const val MIN_CLIP_MS = 60_000L
    private const val MAX_CLIP_MS = 180_000L
    private const val WINDOW_MS = 180_000L
    private const val STEP_MS = 120_000L
    private const val INFERENCE_TIMEOUT_MS = 90_000L

    fun isModelInstalled(context: Context): Boolean {
        val file = File(context.filesDir, MODEL_FILE)
        return file.isFile && file.length() >= MIN_MODEL_BYTES
    }

    fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILE)

    fun downloadModel(context: Context, onProgress: (Float) -> Unit): File {
        val target = modelFile(context)
        if (isModelInstalled(context)) return target
        val stat = StatFs(context.filesDir.absolutePath)
        if (stat.availableBytes < MIN_MODEL_BYTES + 120L * 1024L * 1024L) {
            throw IllegalStateException("Not enough free storage. The AI model needs about 586 MB plus temporary download space.")
        }
        val partial = File(context.filesDir, "$MODEL_FILE.download")
        partial.delete()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AIClipper-Android/1.0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("AI model download failed (HTTP ${connection.responseCode})")
            }
            val total = connection.contentLengthLong
            if (total in 1 until MIN_MODEL_BYTES) {
                throw IllegalStateException("The model server returned a file smaller than the expected model.")
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    var lastProgressAt = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val now = System.currentTimeMillis()
                        if (total > 0L && (now - lastProgressAt >= 400L || copied == total)) {
                            onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            lastProgressAt = now
                        }
                    }
                    output.fd.sync()
                    if (copied < MIN_MODEL_BYTES || (total > 0L && copied != total)) {
                        throw IllegalStateException("AI model download was incomplete. Please retry on a stable connection.")
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            if (!isModelInstalled(context)) {
                target.delete()
                throw IllegalStateException("Downloaded AI model failed its size check.")
            }
            onProgress(1f)
            return target
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    suspend fun analyse(
        context: Context,
        captions: List<CaptionCue>,
        durationMs: Long,
        requestedCount: Int,
        faceMoments: List<Long>,
        sceneMoments: List<Long>,
        audioEnergy: List<AudioEnergyPoint>,
        speakerTurns: List<SpeakerTurn>,
        userPreference: String = "",
        maxClipDurationMs: Long = 90_000L,
        onProgress: (Float) -> Unit = {},
        onStage: (String) -> Unit = {}
    ): List<SemanticHighlight> {
        val modelExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AIClipper-LiteRT-LM").apply { isDaemon = true }
        }
        val modelDispatcher = modelExecutor.asCoroutineDispatcher()
        return try {
            withContext(modelDispatcher) {
                require(isModelInstalled(context)) { "The on-device AI model is missing or incomplete. Download it again from One-Time AI Setup." }
                require(captions.isNotEmpty()) { "Generate speech captions before semantic highlight analysis." }
                require(durationMs >= MIN_CLIP_MS) { "The source video must be at least 60 seconds long." }
                val clipMaximum = maxClipDurationMs.coerceIn(MIN_CLIP_MS, MAX_CLIP_MS)
                val windows = buildWindows(captions, durationMs)
                if (windows.isEmpty()) return@withContext emptyList()

                onStage("Loading the on-device AI model. The first load can take a little while…")
                onProgress(0.02f)
                val engine = Engine(
                    EngineConfig(
                        modelPath = modelFile(context).absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                )
                var engineInitialized = false
                try {
                    engine.initialize()
                    engineInitialized = true
                    onProgress(0.08f)
                    val candidates = mutableListOf<SemanticHighlight>()
                    windows.forEachIndexed { index, window ->
                        onStage("Understanding conversation ${index + 1} of ${windows.size}…")
                        val conversation = engine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(
                                    "You are a precise video-editor highlight analyst. Understand the meaning, context, story arc, " +
                                        "humour, emotion, surprise, conflict, useful insight and completeness of spoken conversation. " +
                                        "Do not reveal reasoning. Do not invent dialogue or timestamps. Return only valid JSON."
                                )
                            )
                        )
                        val response = try {
                            withTimeout(INFERENCE_TIMEOUT_MS) {
                                buildString {
                                    conversation.sendMessageAsync(
                                        buildPrompt(window, faceMoments, sceneMoments, audioEnergy, speakerTurns, userPreference, clipMaximum) +
                                            "\n\n/no_think"
                                    ).collect { message -> append(message.toString()) }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            runCatching { conversation.cancelProcess() }
                            throw cancelled
                        } finally {
                            conversation.close()
                        }
                        candidates.addAll(parseHighlights(response, window.startMs, window.endMs, captions, clipMaximum))
                        onProgress(0.08f + ((index + 1f) / (windows.size + 1f)) * 0.76f)
                    }
                    onStage("Comparing and ranking the strongest moments…")
                    onProgress(0.88f)
                    val refinedCandidates = globallyRefine(engine, candidates)
                    onProgress(1f)
                    rankAndMerge(refinedCandidates, requestedCount, durationMs, faceMoments, sceneMoments, audioEnergy, speakerTurns)
                } finally {
                    if (engineInitialized) engine.close()
                }
            }
        } finally {
            modelDispatcher.close()
            modelExecutor.shutdown()
        }
    }

    /**
     * Second pass compares candidate summaries from every transcript window together.
     * This helps the model choose varied, high-value moments across the full source video.
     */
    private suspend fun globallyRefine(engine: Engine, candidates: List<SemanticHighlight>): List<SemanticHighlight> {
        val pool = candidates.distinctBy { it.startMs }.sortedByDescending { it.score }.take(30)
        if (pool.size <= 1) return pool
        val options = pool.mapIndexed { index, item ->
            "$index | ${item.startMs}-${item.endMs} ms | score ${item.score} | ${item.title} | ${item.reason}"
        }.joinToString("\n")
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    "You are the final editor selecting the best moments across an entire long video. " +
                        "Prioritize genuine meaning, complete context, variety and strong viewer value. Return valid JSON only."
                )
            )
        )
        val response = try {
            withTimeout(INFERENCE_TIMEOUT_MS) {
                buildString {
                    conversation.sendMessageAsync(
                        "Compare these candidate moments from the complete source video. Select the strongest and most varied moments. " +
                            "Do not select overlapping ranges or repeated versions of the same point. " +
                            "Return up to ${pool.size} items in ranked order with original integer id and revised score 0-100. " +
                            "Do not invent ids. JSON only: {\"ranked\":[{\"id\":0,\"score\":95}]}\n\nCANDIDATES:\n$options\n\n/no_think"
                    ).collect { message -> append(message.toString()) }
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw cancelled
        } finally {
            conversation.close()
        }
        val fence = 96.toChar().toString().repeat(3)
        val cleaned = response.substringAfter("</think>", response).replace(fence + "json", "").replace(fence, "")
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        if (first < 0 || last <= first) return pool
        val array = runCatching {
            JSONObject(cleaned.substring(first, last + 1)).optJSONArray("ranked")
        }.getOrNull() ?: return pool
        val refined = mutableListOf<SemanticHighlight>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val id = row.optInt("id", -1)
            val item = pool.getOrNull(id) ?: continue
            refined.add(item.copy(score = row.optInt("score", item.score).coerceIn(0, 100)))
        }
        return if (refined.isEmpty()) pool else refined
    }

    private data class TranscriptWindow(val startMs: Long, val endMs: Long, val cues: List<CaptionCue>)

    private fun buildWindows(captions: List<CaptionCue>, durationMs: Long): List<TranscriptWindow> {
        val result = mutableListOf<TranscriptWindow>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + WINDOW_MS, durationMs)
            val cues = captions.filter { it.endMs > start && it.startMs < end }
            if (cues.size >= 3) result.add(TranscriptWindow(start, end, cues))
            if (end >= durationMs) break
            start += STEP_MS
        }
        return result
    }

    private fun buildPrompt(
        window: TranscriptWindow,
        faces: List<Long>,
        scenes: List<Long>,
        energy: List<AudioEnergyPoint>,
        speakers: List<SpeakerTurn>,
        userPreference: String,
        maxClipDurationMs: Long
    ): String {
        val transcript = window.cues.joinToString("\n") {
            "[${it.startMs}-${it.endMs}] ${it.text.replace("\n", " ").take(180)}"
        }.take(9_000)
        val sceneCount = scenes.count { it in window.startMs..window.endMs }
        val faceCount = faces.count { it in window.startMs..window.endMs }
        val speakerCount = speakers.filter { it.endMs > window.startMs && it.startMs < window.endMs }
            .map { it.speakerId }.distinct().size
        val energyValues = energy.filter { it.timeMs in window.startMs..window.endMs }.map { it.rms }
        val energySummary = if (energyValues.isEmpty()) "unknown" else
            "average %.3f, peak %.3f".format(Locale.US, energyValues.average(), energyValues.maxOrNull() ?: 0f)

        return """
            Analyse this transcript as an editor making engaging short videos.
            USER'S REQUESTED FOCUS: ${userPreference.trim().take(400).ifBlank { "No extra preference; choose the strongest complete moments." }}
            Prioritize the requested focus when it is supported by the transcript, but never invent moments or ignore context.
            Available absolute timeline: ${window.startMs} to ${window.endMs} milliseconds.
            Video evidence summary: $faceCount face-detection samples, $sceneCount scene changes,
            $speakerCount distinct diarized speakers, audio RMS $energySummary.

            Select at most TWO genuinely interesting, self-contained moments from this window.
            A selected moment must be at least 60,000 ms and at most ${maxClipDurationMs} milliseconds long.
            Prefer a strong opening, a complete story or explanation, a surprising reveal, a funny exchange,
            a clear emotional reaction, a useful actionable insight, a compelling disagreement, or a question
            followed by a satisfying answer. Preserve enough setup and payoff to make sense without the full video.
            Avoid greetings, long pauses, repeated points, contextless fragments, unfinished sentences,
            dead air, and moments whose only attraction is a keyword.
            Do not select two overlapping moments. Do not assume a loud sound is interesting.
            Timestamps in the transcript are absolute milliseconds. Use them to choose precise absolute start_ms/end_ms.
            Keep every range within the available timeline. Return score from 0 to 100 and a short title and reason.
            If nothing is worth clipping, return an empty highlights array.

            Return exactly this JSON shape and no markdown:
            {"highlights":[{"start_ms":0,"end_ms":60000,"score":85,"title":"Short hook title","reason":"Why the moment works"}]}

            TRANSCRIPT:
            $transcript
        """.trimIndent()
    }

    private fun parseHighlights(
        raw: String,
        windowStart: Long,
        windowEnd: Long,
        captions: List<CaptionCue>,
        maxClipDurationMs: Long
    ): List<SemanticHighlight> {
        val fence = 96.toChar().toString().repeat(3)
        val cleaned = raw.substringAfter("</think>", raw).replace(fence + "json", "").replace(fence, "")
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        if (first < 0 || last <= first) return emptyList()
        val root = runCatching { JSONObject(cleaned.substring(first, last + 1)) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("highlights") ?: return emptyList()
        val output = mutableListOf<SemanticHighlight>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val start = item.optLong("start_ms", -1L)
            val end = item.optLong("end_ms", -1L)
            if (start < windowStart || end > windowEnd || end - start !in MIN_CLIP_MS..maxClipDurationMs) continue
            if (captions.count { it.endMs > start && it.startMs < end } < 8) continue
            val title = item.optString("title").trim().take(80).ifBlank { "AI-selected highlight" }
            val reason = item.optString("reason").trim().take(220).ifBlank { "A complete conversation moment selected by semantic analysis." }
            output.add(SemanticHighlight(start, end, title, reason, item.optInt("score", 50).coerceIn(0, 100)))
        }
        return output
    }

    private fun rankAndMerge(
        candidates: List<SemanticHighlight>,
        requestedCount: Int,
        durationMs: Long,
        faces: List<Long>,
        scenes: List<Long>,
        energy: List<AudioEnergyPoint>,
        speakers: List<SpeakerTurn>
    ): List<SemanticHighlight> {
        val target = requestedCount.coerceIn(1, 30)
        val ranked = candidates.map { item ->
            val sceneSignal = scenes.count { it in item.startMs..item.endMs }.coerceAtMost(3) * 2.0
            val faceSignal = if (faces.any { it in item.startMs..item.endMs }) 2.0 else 0.0
            val speakerChanges = speakers.filter { it.endMs > item.startMs && it.startMs < item.endMs }
                .map { it.speakerId }.distinct().size
            val speakerSignal = (speakerChanges - 1).coerceAtLeast(0).coerceAtMost(3) * 1.5
            val rms = energy.filter { it.timeMs in item.startMs..item.endMs }.map { it.rms.toDouble() }
            val energySignal = if (rms.isEmpty()) 0.0 else
                ((rms.average() * 5.0) + ((rms.maxOrNull() ?: 0.0) * 2.0)).coerceIn(0.0, 3.0)
            val fused = (item.score * 0.88 + sceneSignal + faceSignal + speakerSignal + energySignal).coerceAtMost(100.0)
            item.copy(score = fused.toInt())
        }.filter { it.startMs >= 0 && it.endMs <= durationMs }.sortedByDescending { it.score }

        val chosen = mutableListOf<SemanticHighlight>()
        for (candidate in ranked) {
            if (chosen.size >= target) break
            val overlaps = chosen.any { existing ->
                val intersection = (minOf(existing.endMs, candidate.endMs) - maxOf(existing.startMs, candidate.startMs)).coerceAtLeast(0L)
                intersection > 15_000L || abs(existing.startMs - candidate.startMs) < 45_000L
            }
            if (!overlaps) chosen.add(candidate)
        }
        return chosen.sortedBy { it.startMs }
    }
}
