package com.aiclipper.app

import java.util.Locale

/**
 * Deterministic offline highlight ranking. This is signal-based selection,
 * not semantic language understanding or speaker diarization.
 */
object ClipIntelligence {
    const val MIN_SHORT_DURATION_MS = 60_000L
    private const val LEAD_IN_MS = 20_000L

    fun selectMoments(
        faceMoments: List<Long>,
        sceneMoments: List<Long>,
        speechCaptions: List<CaptionCue>,
        audioEnergy: List<AudioEnergyPoint>,
        durationMs: Long,
        requestedCount: Int
    ): List<Long> {
        if (durationMs < MIN_SHORT_DURATION_MS || requestedCount <= 0) return emptyList()
        val maxByDuration = (durationMs / MIN_SHORT_DURATION_MS).toInt().coerceAtLeast(1)
        val target = requestedCount.coerceIn(1, 30).coerceAtMost(maxByDuration)
        val latestMoment = (durationMs - (MIN_SHORT_DURATION_MS - LEAD_IN_MS)).coerceAtLeast(0L)
        val candidates = linkedSetOf<Long>()
        faceMoments.forEach { candidates.add(it.coerceIn(0L, latestMoment)) }
        sceneMoments.forEach { candidates.add(it.coerceIn(0L, latestMoment)) }

        if (speechCaptions.isNotEmpty() || audioEnergy.isNotEmpty()) {
            var time = 10_000L
            while (time <= latestMoment) {
                candidates.add(time)
                time += 5_000L
            }
        } else if (candidates.isEmpty()) {
            val samples = (target * 4).coerceAtLeast(4)
            repeat(samples) { index ->
                candidates.add((latestMoment * ((index + 0.5) / samples.toDouble())).toLong())
            }
        }
        if (candidates.isEmpty()) candidates.add(latestMoment / 2L)

        val hooks = listOf(
            "why", "how", "what if", "the truth", "secret", "mistake", "never",
            "always", "because", "the reason", "actually", "imagine", "listen",
            "wait", "look", "important", "remember", "best", "worst", "crazy",
            "amazing", "problem", "solution", "first time", "nobody", "everyone"
        )

        fun score(moment: Long): Double {
            val start = (moment - LEAD_IN_MS).coerceAtLeast(0L)
            val end = (start + MIN_SHORT_DURATION_MS).coerceAtMost(durationMs)
            val cues = speechCaptions.filter { it.endMs > start && it.startMs < end }
            val transcript = cues.joinToString(" ") { it.text.lowercase(Locale.US) }
            val words = transcript.split(Regex("\\s+")).count { it.isNotBlank() }
            val density = (words / 55.0).coerceIn(0.0, 1.25) * 20.0
            val hookScore = hooks.count { term ->
                Regex("\\b" + Regex.escape(term) + "\\b").containsMatchIn(transcript)
            }.coerceAtMost(6) * 4.0
            val continuity = (cues.size / 5.0).coerceIn(0.0, 1.0) * 8.0
            val face = if (faceMoments.any { kotlin.math.abs(it - moment) <= 12_000L }) 12.0 else 0.0
            val scene = if (sceneMoments.any { kotlin.math.abs(it - moment) <= 10_000L }) 7.0 else 0.0
            val speechPenalty = if (speechCaptions.isNotEmpty() && words < 8) 18.0 else 0.0
            val energyValues = audioEnergy.asSequence()
                .filter { it.timeMs >= start && it.timeMs < end }
                .map { it.rms.toDouble().coerceAtLeast(0.0) }
                .toList()
            val averageEnergy = energyValues.average().takeIf { it.isFinite() } ?: 0.0
            val peakEnergy = energyValues.maxOrNull() ?: 0.0
            val energy = (averageEnergy * 70.0 + peakEnergy * 30.0).coerceIn(0.0, 12.0)
            return density + hookScore + continuity + face + scene + energy - speechPenalty
        }

        val ranked = candidates.map { it to score(it) }
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
        val selected = mutableListOf<Long>()
        for ((moment, _) in ranked) {
            if (selected.size >= target) break
            val start = (moment - LEAD_IN_MS).coerceAtLeast(0L)
            val overlaps = selected.any { existing ->
                val existingStart = (existing - LEAD_IN_MS).coerceAtLeast(0L)
                kotlin.math.abs(existingStart - start) < MIN_SHORT_DURATION_MS
            }
            if (!overlaps) selected.add(moment)
        }
        return selected.sorted().take(target)
    }

    /** Produce full-minute export ranges without overlap or out-of-bounds timestamps. */
    fun buildExportRanges(moments: List<Long>, durationMs: Long): List<ClipRange> {
        if (durationMs < MIN_SHORT_DURATION_MS) return emptyList()
        val latestStart = durationMs - MIN_SHORT_DURATION_MS
        val ranges = mutableListOf<ClipRange>()
        moments.distinct().sorted().forEach { moment ->
            val start = (moment - LEAD_IN_MS).coerceAtLeast(0L).coerceAtMost(latestStart)
            val end = start + MIN_SHORT_DURATION_MS
            if (ranges.none { start < it.endMs && end > it.startMs }) {
                ranges.add(ClipRange(start, end))
            }
        }
        return ranges
    }
}

data class ClipRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}
