package com.aiclipper.app

/** Maps audio diarization identities to visible faces using mouth movement during each voice's turns. */
internal object SpeakerFaceMatcher {
    fun assign(
        turns: List<SpeakerTurn>,
        samples: List<FaceLayoutSample>
    ): Map<Int, Int> {
        val assignments = mutableMapOf<Int, Int>()
        val speakers = turns.map { it.speakerId }.distinct()
        for (speaker in speakers) {
            val scores = doubleArrayOf(0.0, 0.0)
            val counts = intArrayOf(0, 0)
            val speakerTurns = turns.filter { it.speakerId == speaker }
            for (turn in speakerTurns) {
                for (sample in samples) {
                    if (sample.faces.size < 2 || sample.timeMs < turn.startMs || sample.timeMs > turn.endMs) continue
                    for (faceIndex in 0..1) {
                        scores[faceIndex] += sample.faces[faceIndex].mouthOpen.toDouble()
                        counts[faceIndex]++
                    }
                }
            }
            if (counts.all { it >= 2 }) {
                val average0 = scores[0] / counts[0]
                val average1 = scores[1] / counts[1]
                if (kotlin.math.abs(average0 - average1) >= 0.006) {
                    assignments[speaker] = if (average0 > average1) 0 else 1
                }
            }
        }
        return assignments
    }
}
