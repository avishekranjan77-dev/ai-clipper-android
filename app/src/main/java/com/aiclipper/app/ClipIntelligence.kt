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
        requestedCount: Int,
        clipDurationMs: Long = MIN_SHORT_DURATION_MS
    ): List<Long> {
        if (durationMs < MIN_SHORT_DURATION_MS || requestedCount <= 0) return emptyList()
        val clipLength = clipDurationMs.coerceAtLeast(MIN_SHORT_DURATION_MS).coerceAtMost(durationMs)
        val maxByDuration = (durationMs / clipLength).toInt().coerceAtLeast(1)
        val target = requestedCount.coerceIn(1, 30).coerceAtMost(maxByDuration)
        val latestMoment = (durationMs - (clipLength - LEAD_IN_MS)).coerceAtLeast(0L)
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
        if (candidates.size < target * 2) {
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
            val end = (start + clipLength).coerceAtMost(durationMs)
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
                kotlin.math.abs(existingStart - start) < clipLength
            }
            if (!overlaps) selected.add(moment)
        }
        // Fill remaining slots with evenly distributed, non-overlapping fallback moments.
        if (selected.size < target) {
            for (index in 0 until target) {
                if (selected.size >= target) break
                val fallbackStart = if (target == 1) {
                    (durationMs - clipLength) / 2L
                } else {
                    ((durationMs - clipLength) * index.toLong() / (target - 1).toLong())
                }
                val fallbackMoment = (fallbackStart + LEAD_IN_MS).coerceAtMost(latestMoment)
                val overlaps = selected.any { existing ->
                    val existingStart = (existing - LEAD_IN_MS).coerceAtLeast(0L)
                    kotlin.math.abs(existingStart - fallbackStart) < clipLength
                }
                if (!overlaps) selected.add(fallbackMoment)
            }
        }
        return selected.sorted().take(target)
    }


    /**
     * Keeps semantic selections first, then fills remaining slots with non-overlapping
     * signal-based ranges when the language model returns fewer moments than requested.
     */
    fun mergeExportRanges(
        preferredRanges: List<ClipRange>,
        fallbackMoments: List<Long>,
        durationMs: Long,
        requestedCount: Int,
        clipDurationMs: Long = 90_000L
    ): List<ClipRange> {
        if (durationMs < MIN_SHORT_DURATION_MS || requestedCount <= 0) return emptyList()
        val clipLength = clipDurationMs.coerceAtLeast(MIN_SHORT_DURATION_MS).coerceAtMost(durationMs)
        val availableSlots = (durationMs / clipLength).toInt().coerceAtLeast(1)
        val target = requestedCount.coerceIn(1, 30).coerceAtMost(availableSlots)
        val chosen = mutableListOf<ClipRange>()
        preferredRanges.forEach { range ->
            if (chosen.size < target &&
                range.startMs >= 0L &&
                range.endMs <= durationMs &&
                range.durationMs in MIN_SHORT_DURATION_MS..clipLength &&
                chosen.none { range.startMs < it.endMs && range.endMs > it.startMs }
            ) {
                chosen.add(range)
            }
        }
        for (fallback in buildExportRanges(fallbackMoments, durationMs, clipLength)) {
            if (chosen.size >= target) break
            if (chosen.none { fallback.startMs < it.endMs && fallback.endMs > it.startMs }) {
                chosen.add(fallback)
            }
        }
        return chosen.sortedBy { it.startMs }
    }

    /** Produce full-minute export ranges without overlap or out-of-bounds timestamps. */
    fun buildExportRanges(moments: List<Long>, durationMs: Long, clipDurationMs: Long = MIN_SHORT_DURATION_MS): List<ClipRange> {
        val clipLength = clipDurationMs.coerceAtLeast(MIN_SHORT_DURATION_MS).coerceAtMost(durationMs)
        val latestStart = durationMs - clipLength
        val ranges = mutableListOf<ClipRange>()
        moments.distinct().sorted().forEach { moment ->
            val start = (moment - LEAD_IN_MS).coerceAtLeast(0L).coerceAtMost(latestStart)
            val end = start + clipLength
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
