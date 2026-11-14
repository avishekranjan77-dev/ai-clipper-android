package com.aiclipper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerFaceMatcherTest {
    @Test fun mapsEachVoiceToFaceWithHigherMouthActivityDuringItsTurns() {
        val turns = listOf(
            SpeakerTurn(0, 2000, 1, 0.9f),
            SpeakerTurn(3000, 5000, 2, 0.9f)
        )
        val samples = listOf(
            FaceLayoutSample(500, listOf(FaceCenter(0.25f, 0.5f, 0.10f), FaceCenter(0.75f, 0.5f, 0.01f)), 16f / 9f),
            FaceLayoutSample(1000, listOf(FaceCenter(0.25f, 0.5f, 0.12f), FaceCenter(0.75f, 0.5f, 0.01f)), 16f / 9f),
            FaceLayoutSample(3500, listOf(FaceCenter(0.25f, 0.5f, 0.01f), FaceCenter(0.75f, 0.5f, 0.11f)), 16f / 9f),
            FaceLayoutSample(4000, listOf(FaceCenter(0.25f, 0.5f, 0.01f), FaceCenter(0.75f, 0.5f, 0.13f)), 16f / 9f)
        )
        val assignments = SpeakerFaceMatcher.assign(turns, samples)
        assertEquals(0, assignments[1])
        assertEquals(1, assignments[2])
    }

    @Test fun leavesAmbiguousVoiceUnassigned() {
        val turns = listOf(SpeakerTurn(0, 2000, 1, 0.9f))
        val samples = listOf(
            FaceLayoutSample(500, listOf(FaceCenter(0.25f, 0.5f, 0.03f), FaceCenter(0.75f, 0.5f, 0.03f)), 16f / 9f),
            FaceLayoutSample(1000, listOf(FaceCenter(0.25f, 0.5f, 0.04f), FaceCenter(0.75f, 0.5f, 0.04f)), 16f / 9f)
        )
        assertTrue(SpeakerFaceMatcher.assign(turns, samples).isEmpty())
    }
}
