package com.aiclipper.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay

enum class CaptionStyle(val label: String) {
    CLASSIC("Classic"),
    BOLD_POP("Bold pop"),
    KARAOKE("Word focus"),
    MINIMAL("Minimal")
}

@UnstableApi
class TimedCaptionOverlay(
    private val cues: List<CaptionCue>,
    private val style: CaptionStyle = CaptionStyle.CLASSIC,
    private val faceSamples: List<FaceLayoutSample> = emptyList(),
    private val clipStartMs: Long = 0L
) : CanvasOverlay(true) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(205, 0, 0, 0)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(255, 220, 40)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val timeMs = presentationTimeUs / 1000L
        val cue = cues.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs } ?: return
        val absoluteMs = clipStartMs + timeMs
        val nearest = faceSamples.minByOrNull { kotlin.math.abs(it.timeMs - absoluteMs) }
        val faceOccupiesLowerCaptionZone = nearest?.faces?.any { it.y in 0.58f..0.96f && it.x in 0.18f..0.82f } == true
        val placeAtTop = faceOccupiesLowerCaptionZone
        textPaint.textSize = (canvas.width * when (style) {
            CaptionStyle.MINIMAL -> 0.040f
            CaptionStyle.CLASSIC -> 0.046f
            CaptionStyle.BOLD_POP, CaptionStyle.KARAOKE -> 0.055f
        }).coerceIn(24f, 62f)
        accentPaint.textSize = textPaint.textSize
        val maxWidth = canvas.width * 0.86f
        val words = cue.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && textPaint.measureText(candidate) > maxWidth) {
                lines.add(line)
                line = word
            } else line = candidate
        }
        if (line.isNotEmpty()) lines.add(line)
        val lineHeight = textPaint.textSize * 1.22f
        val verticalPadding = if (style == CaptionStyle.MINIMAL) 8f else textPaint.textSize * 0.42f
        val boxHeight = lineHeight * lines.size + verticalPadding * 2f
        val centerY = if (placeAtTop) canvas.height * 0.17f else canvas.height * 0.82f
        val rect = RectF(canvas.width * 0.06f, centerY - boxHeight / 2f, canvas.width * 0.94f, centerY + boxHeight / 2f)
        if (style != CaptionStyle.MINIMAL) {
            backgroundPaint.color = when (style) {
                CaptionStyle.BOLD_POP -> android.graphics.Color.argb(220, 8, 8, 8)
                CaptionStyle.KARAOKE -> android.graphics.Color.argb(175, 8, 8, 8)
                else -> android.graphics.Color.argb(205, 0, 0, 0)
            }
            canvas.drawRoundRect(rect, if (style == CaptionStyle.BOLD_POP) 28f else 18f, if (style == CaptionStyle.BOLD_POP) 28f else 18f, backgroundPaint)
        }
        lines.forEachIndexed { index, value ->
            val baseline = rect.top + verticalPadding + textPaint.textSize * 0.88f + index * lineHeight
            if (style == CaptionStyle.KARAOKE) {
                val parts = value.split(" ")
                val active = parts.lastIndex
                val full = parts.joinToString(" ")
                canvas.drawText(full, canvas.width / 2f, baseline, textPaint)
                if (active >= 0) {
                    val prefix = parts.dropLast(1).joinToString(" ")
                    val activeWord = parts.last()
                    val prefixWidth = if (prefix.isEmpty()) 0f else textPaint.measureText("$prefix ")
                    val wordWidth = accentPaint.measureText(activeWord)
                    val startX = canvas.width / 2f - textPaint.measureText(full) / 2f + prefixWidth
                    canvas.drawText(activeWord, startX + wordWidth / 2f, baseline, accentPaint)
                }
            } else {
                if (style == CaptionStyle.BOLD_POP) {
                    textPaint.setShadowLayer(5f, 2f, 3f, android.graphics.Color.BLACK)
                } else textPaint.clearShadowLayer()
                canvas.drawText(value, canvas.width / 2f, baseline, textPaint)
            }
        }
        textPaint.clearShadowLayer()
    }
}
