package com.aiclipper.app

import android.content.Context
import android.graphics.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ClipAdjustmentEditor(clip: GeneratedClip, onCancel: () -> Unit, onRendered: (GeneratedClip) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    var zoom by remember(clip.file.absolutePath) { mutableFloatStateOf(1f) }
    var horizontal by remember(clip.file.absolutePath) { mutableFloatStateOf(0f) }
    var vertical by remember(clip.file.absolutePath) { mutableFloatStateOf(0f) }
    var trimStart by remember(clip.file.absolutePath) { mutableFloatStateOf(0f) }
    var trimEnd by remember(clip.file.absolutePath) { mutableFloatStateOf(1f) }
    var rendering by remember { mutableStateOf(false) }
    val duration = clip.durationMs.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().background(Color(0xFF20222A), RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("ADJUST CLIP", color = Color.White, fontSize = 13.sp)
        Text("Zoom  ${"%.2f".format(zoom)}×", color = Color.White, fontSize = 12.sp)
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..2.5f)
        Text("Horizontal framing", color = Color.White, fontSize = 12.sp)
        Slider(value = horizontal, onValueChange = { horizontal = it }, valueRange = -1f..1f)
        Text("Vertical framing", color = Color.White, fontSize = 12.sp)
        Slider(value = vertical, onValueChange = { vertical = it }, valueRange = -1f..1f)
        Text("Trim start  ${(trimStart * duration / 1000).toInt()}s", color = Color.White, fontSize = 12.sp)
        Slider(value = trimStart, onValueChange = { trimStart = it.coerceAtMost(trimEnd - 0.1f) }, valueRange = 0f..0.9f)
        Text("Trim end  ${(trimEnd * duration / 1000).toInt()}s", color = Color.White, fontSize = 12.sp)
        Slider(value = trimEnd, onValueChange = { trimEnd = it.coerceAtLeast(trimStart + 0.1f) }, valueRange = 0.1f..1f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCancel, enabled = !rendering, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF343640))) { Text("Cancel") }
            Button(onClick = {
                if (!rendering) {
                    rendering = true
                    renderAdjustedClip(context, clip, zoom, horizontal, vertical, trimStart, trimEnd,
                        onComplete = { result -> rendering = false; onRendered(result) },
                        onFailure = { message -> rendering = false; onError(message) })
                }
            }, enabled = !rendering && (zoom != 1f || horizontal != 0f || vertical != 0f || trimStart > 0f || trimEnd < 1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B7CFF))) { Text(if (rendering) "Rendering…" else "Apply & re-render") }
        }
        if (rendering) Text("Rendering a new file. Your original clip is kept until rendering succeeds.", color = Color(0xFF9295A1), fontSize = 10.sp)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun renderAdjustedClip(context: Context, clip: GeneratedClip, zoom: Float, horizontal: Float, vertical: Float, trimStart: Float, trimEnd: Float, onComplete: (GeneratedClip) -> Unit, onFailure: (String) -> Unit) {
    val source = clip.file
    if (!source.exists()) { onFailure("The original clip file is no longer available."); return }
    val duration = clip.durationMs.coerceAtLeast(1L)
    val start = (duration * trimStart).toLong().coerceIn(0L, duration - 1L)
    val end = (duration * trimEnd).toLong().coerceIn(start + 1L, duration)
    val output = File(context.filesDir, "adjusted_clip_${System.currentTimeMillis()}.mp4")
    val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()
    val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(source)).setClippingConfiguration(clipping).build()
    val framing = MatrixTransformation { Matrix().apply { postScale(zoom, zoom); postTranslate(horizontal * 0.55f, vertical * 0.55f) } }
    val effects = Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP), framing))
    val edited = EditedMediaItem.Builder(item).setEffects(effects).build()
    val completed = AtomicBoolean(false)
    val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
        override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
            if (completed.compareAndSet(false, true)) onComplete(GeneratedClip(output, clip.startMs + start, clip.startMs + end))
        }
        override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
            if (completed.compareAndSet(false, true)) { output.delete(); onFailure("Adjustment render failed: ${exportException.localizedMessage ?: "unknown error"}") }
        }
    }).build()
    try { transformer.start(edited, output.absolutePath) }
    catch (error: Exception) { output.delete(); if (completed.compareAndSet(false, true)) onFailure("Could not start adjustment: ${error.localizedMessage ?: "unknown error"}") }
}