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
}
