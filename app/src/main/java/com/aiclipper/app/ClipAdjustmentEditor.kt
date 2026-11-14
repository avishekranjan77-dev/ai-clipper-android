package com.aiclipper.app

import android.content.Context
import android.graphics.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
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
    val previewPlayer = remember(clip.file.absolutePath) { ExoPlayer.Builder(context).build() }
    DisposableEffect(previewPlayer) { onDispose { previewPlayer.release() } }
    LaunchedEffect(clip.file.absolutePath) {
        previewPlayer.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(clip.file)))
        previewPlayer.prepare()
        previewPlayer.playWhenReady = false
    }
    var zoom by remember(clip.file.absolutePath) { mutableFloatStateOf(1f) }
    var selectedAspect by remember(clip.file.absolutePath) { mutableStateOf(clip.aspectRatio) }
    var horizontal by remember(clip.file.absolutePath) { mutableFloatStateOf(0f) }
    var vertical by remember(clip.file.absolutePath) { mutableFloatStateOf(0f) }
    var trimStart by remember(clip.file.absolutePath) { mutableFloatStateOf(0f) }
    var trimEnd by remember(clip.file.absolutePath) { mutableFloatStateOf(1f) }
    var rendering by remember { mutableStateOf(false) }
    val duration = clip.durationMs.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth().background(Color(0xFF20222A), RoundedCornerShape(14.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("ADJUST CLIP", color = Color.White, fontSize = 13.sp)
        Text("Set the output frame, then drag the preview or use the controls. The new framing is applied when you re-render.", color = Color(0xFF9295A1), fontSize = 10.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("9:16", "1:1", "16:9", "Original").forEach { aspect ->
                Button(onClick = { selectedAspect = aspect }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 7.dp), colors = ButtonDefaults.buttonColors(containerColor = if (selectedAspect == aspect) Color(0xFF8B7CFF) else Color(0xFF343640))) { Text(aspect, fontSize = 10.sp) }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(230.dp).background(Color.Black, RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF8B7CFF), RoundedCornerShape(10.dp))
                .pointerInput(zoom) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        horizontal = (horizontal + drag.x / size.width * 2f / zoom).coerceIn(-1f, 1f)
                        vertical = (vertical + drag.y / size.height * 2f / zoom).coerceIn(-1f, 1f)
                    }
                }
        ) {
            AndroidView(
                factory = { PlayerView(it).apply { player = previewPlayer; useController = true } },
                update = { it.player = previewPlayer },
                modifier = Modifier.fillMaxWidth().height(230.dp)
            )
            val frameWidth = when (selectedAspect) {
                "1:1" -> 0.62f
                "16:9" -> 0.96f
                "Original" -> 0.94f
                else -> 0.45f
            }
            val frameHeight = when (selectedAspect) {
                "1:1" -> 0.90f
                "16:9" -> 0.52f
                "Original" -> 0.90f
                else -> 0.90f
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth(frameWidth).fillMaxHeight(frameHeight)
                        .offset(x = (horizontal * 24f).dp, y = (vertical * 24f).dp)
                        .border(2.dp, Color(0xFF8B7CFF), RoundedCornerShape(6.dp))
                )
            }
        }
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
                    renderAdjustedClip(context, clip, zoom, horizontal, vertical, trimStart, trimEnd, selectedAspect,
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
private fun renderAdjustedClip(context: Context, clip: GeneratedClip, zoom: Float, horizontal: Float, vertical: Float, trimStart: Float, trimEnd: Float, outputAspect: String, onComplete: (GeneratedClip) -> Unit, onFailure: (String) -> Unit) {
    val source = clip.file
    if (!source.exists()) { onFailure("The original clip file is no longer available."); return }
    val duration = clip.durationMs.coerceAtLeast(1L)
    val start = (duration * trimStart).toLong().coerceIn(0L, duration - 1L)
    val end = (duration * trimEnd).toLong().coerceIn(start + 1L, duration)
    val output = File(context.filesDir, "adjusted_clip_${System.currentTimeMillis()}.mp4")
    val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start).setEndPositionMs(end).build()
    val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(source)).setClippingConfiguration(clipping).build()
    val frameWidthPx = if (outputAspect == "16:9") 1920f else 1080f
    val frameHeightPx = when (outputAspect) { "1:1" -> 1080f; "16:9" -> 1080f; else -> 1920f }
    val framing = MatrixTransformation { Matrix().apply { postScale(zoom, zoom); postTranslate(horizontal * frameWidthPx / 2f, vertical * frameHeightPx / 2f) } }
    val videoEffects = mutableListOf<androidx.media3.common.Effect>()
    when (outputAspect) {
        "9:16" -> videoEffects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        "1:1" -> videoEffects.add(Presentation.createForWidthAndHeight(1080, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        "16:9" -> videoEffects.add(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        // Original retains the clip's existing dimensions.
    }
    videoEffects.add(framing)
    val effects = Effects(emptyList(), videoEffects)
    val edited = EditedMediaItem.Builder(item).setEffects(effects).build()
    val completed = AtomicBoolean(false)
    val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
        override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
            if (completed.compareAndSet(false, true)) onComplete(GeneratedClip(output, clip.startMs + start, clip.startMs + end, outputAspect, clip.title, clip.reason, clip.score))
        }
        override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
            if (completed.compareAndSet(false, true)) { output.delete(); onFailure("Adjustment render failed: ${exportException.localizedMessage ?: "unknown error"}") }
        }
    }).build()
    try { transformer.start(edited, output.absolutePath) }
    catch (error: Exception) { output.delete(); if (completed.compareAndSet(false, true)) onFailure("Could not start adjustment: ${error.localizedMessage ?: "unknown error"}") }
}