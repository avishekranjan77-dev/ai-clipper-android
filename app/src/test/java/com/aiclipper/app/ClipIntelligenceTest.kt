package com.aiclipper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipIntelligenceTest {
    @Test fun rejectsSourceShorterThanOneMinute() {
        assertTrue(ClipIntelligence.selectMoments(emptyList(), emptyList(), emptyList(), emptyList(), 59_999L, 5).isEmpty())
        assertTrue(ClipIntelligence.buildExportRanges(listOf(10_000L), 59_999L).isEmpty())
    }

    @Test fun createsExactlyOneMinuteRangeForOneMinuteSource() {
        val range = ClipIntelligence.buildExportRanges(listOf(20_000L), 60_000L).single()
        assertEquals(0L, range.startMs)
        assertEquals(60_000L, range.endMs)
        assertEquals(60_000L, range.durationMs)
    }

    @Test fun requestedCountIsCappedByAvailableFullMinuteClips() {
        val moments = ClipIntelligence.selectMoments(emptyList(), emptyList(), emptyList(), emptyList(), 180_000L, 30)
        val ranges = ClipIntelligence.buildExportRanges(moments, 180_000L)
        assertTrue(ranges.size <= 3)
        assertTrue(ranges.all { it.durationMs == 60_000L })
    }

    @Test fun exportRangesNeverOverlapAndStayInsideSource() {
        val ranges = ClipIntelligence.buildExportRanges(listOf(25_000L, 35_000L, 95_000L, 155_000L), 240_000L)
        assertTrue(ranges.all { it.startMs >= 0L && it.endMs <= 240_000L })
        assertTrue(ranges.all { it.durationMs == 60_000L })
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun selectionRespectsRequestedCount() {
        val moments = ClipIntelligence.selectMoments(
            listOf(30_000L, 100_000L, 170_000L),
            listOf(50_000L, 120_000L, 190_000L),
            emptyList(), emptyList(), 300_000L, 2
        )
        assertEquals(2, moments.size)
    }

    @Test fun semanticSelectionsAreFilledWithNonOverlappingFallbackRanges() {
        val merged = ClipIntelligence.mergeExportRanges(
            preferredRanges = listOf(ClipRange(0L, 90_000L)),
            fallbackMoments = listOf(20_000L, 100_000L, 180_000L, 250_000L, 300_000L, 350_000L),
            durationMs = 360_000L,
            requestedCount = 4
        )
        assertEquals(4, merged.size)
        assertTrue(merged.all { it.durationMs in 60_000L..90_000L })
        assertTrue(merged.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun mergeCapsCountByAvailableSourceDuration() {
        val merged = ClipIntelligence.mergeExportRanges(
            preferredRanges = emptyList(),
            fallbackMoments = listOf(20_000L, 80_000L, 140_000L, 200_000L),
            durationMs = 120_000L,
            requestedCount = 30
        )
        assertTrue(merged.size <= 2)
        assertTrue(merged.all { it.startMs >= 0L && it.endMs <= 120_000L })
    }


    @Test fun sparseVisualSignalsStillProduceFallbackCandidates() {
        val moments = ClipIntelligence.selectMoments(
            faceMoments = listOf(300_000L),
            sceneMoments = emptyList(),
            speechCaptions = emptyList(),
            audioEnergy = emptyList(),
            durationMs = 600_000L,
            requestedCount = 8
        )
        assertEquals(8, moments.size)
        val ranges = ClipIntelligence.buildExportRanges(moments, 600_000L)
        assertTrue(ranges.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }


    @Test fun fallbackRangesRespectThreeMinuteMaximumWhenRequested() {
        val ranges = ClipIntelligence.buildExportRanges(
            moments = listOf(90_000L, 270_000L, 450_000L),
            durationMs = 600_000L,
            clipDurationMs = 180_000L
        )
        assertTrue(ranges.isNotEmpty())
        assertTrue(ranges.all { it.durationMs == 180_000L })
        assertTrue(ranges.all { it.endMs <= 600_000L })
    }

}
