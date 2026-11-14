package com.aiclipper.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay

@UnstableApi
class TimedCaptionOverlay(private val cues: List<CaptionCue>) : CanvasOverlay(true) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(205, 0, 0, 0)
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val timeMs = presentationTimeUs / 1000L
        val cue = cues.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs } ?: return
        textPaint.textSize = (canvas.width * 0.052f).coerceIn(28f, 62f)
        val maxWidth = canvas.width * 0.84f
        val lines = mutableListOf<String>()
        var line = ""
        for (word in cue.text.trim().split(Regex("\\s+"))) {
            val candidate = if (line.isEmpty()) word else line + " " + word
            if (line.isNotEmpty() && textPaint.measureText(candidate) > maxWidth) {
                lines.add(line)
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        val lineHeight = textPaint.textSize * 1.18f
        val boxHeight = lineHeight * lines.size + textPaint.textSize * 0.65f
        val bottom = canvas.height * 0.84f
        val rect = RectF(canvas.width * 0.07f, bottom - boxHeight, canvas.width * 0.93f, bottom)
        canvas.drawRoundRect(rect, 22f, 22f, backgroundPaint)
        lines.forEachIndexed { index, value ->
            val baseline = rect.top + textPaint.textSize * 0.9f + index * lineHeight
            canvas.drawText(value, canvas.width / 2f, baseline, textPaint)
        }
    }
}
