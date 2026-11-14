package com.aiclipper.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.effect.Presentation
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Effects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

private val AppBackground = Color(0xFF090A0D)
private val Panel = Color(0xFF15171D)
private val Accent = Color(0xFF8B7CFF)
private val Muted = Color(0xFF9295A1)
private val Green = Color(0xFF62D69A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                background = AppBackground, surface = Panel, primary = Accent,
                onBackground = Color.White, onSurface = Color.White
            )) { ClipperHome(applicationContext) }
        }
    }
}

@Composable
private fun ClipperHome(context: Context) {
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var portrait by remember { mutableStateOf(true) }
    var captionText by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a video to start editing") }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var analysing by remember { mutableStateOf(false) }
    var scenePicks by remember { mutableStateOf<List<Long>>(emptyList()) }
    var facePicks by remember { mutableStateOf<List<Long>>(emptyList()) }
    var faceTrack by remember { mutableStateOf<List<FaceTrackPoint>>(emptyList()) }
    var scanningFaces by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(selectedVideo) {
        val uri = selectedVideo ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && player.duration > 0) {
                    durationMs = player.duration
                    startMs = 0L
                    endMs = player.duration
                }
            }
        }
        player.addListener(listener)
        if (player.isReadyForDuration()) {
            durationMs = player.duration
            endMs = player.duration
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedVideo = uri
        selectedVideoName = uri?.let { queryDisplayName(context, it) }
        status = if (uri == null) "No video selected" else "Video loaded. Preview, trim and export are ready."
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI CLIPPER", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Your pocket video studio", color = Muted, fontSize = 13.sp)
                }
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
            }

            if (selectedVideo != null) {
                AndroidView(
                    factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.Black, RoundedCornerShape(18.dp))
                )
                Text(selectedVideoName ?: "Selected video", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("TRIM YOUR CLIP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("Start  •  ${formatTime(startMs)}", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = startMs.toFloat().coerceIn(0f, endMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = { startMs = it.toLong().coerceAtMost((endMs - 1000L).coerceAtLeast(0L)) },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f)
                    )
                    Text("End  •  ${formatTime(endMs)}", color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = endMs.toFloat().coerceIn(startMs.toFloat(), durationMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = { endMs = it.toLong().coerceAtLeast(startMs + 1000L).coerceAtMost(durationMs) },
                        valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f)
                    )
                    Text("Source length: ${formatTime(durationMs)}  •  Clip length: ${formatTime((endMs - startMs).coerceAtLeast(0L))}", color = Muted, fontSize = 11.sp)
                }
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("SMART SCENE PICKS", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Find visual scene changes locally on your phone", color = Muted, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val uri = selectedVideo ?: return@Button
                                analysing = true
                                status = "Analysing video frames on this device…"
                                scope.launch {
                                    val picks = withContext(Dispatchers.Default) { findScenePicks(context, uri, durationMs) }
                                    scenePicks = picks
                                    analysing = false
                                    status = if (picks.isEmpty()) "No strong scene changes found. You can still trim manually." else "Found ${picks.size} possible scene-change moments"
                                }
                            },
                            enabled = !analysing,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292B34))
                        ) {
                            if (analysing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text(if (analysing) "Scanning" else "Find moments")
                        }
                    }
                    if (scenePicks.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        scenePicks.forEachIndexed { index, time ->
                            Button(
                                onClick = {
                                    startMs = (time - 5000L).coerceAtLeast(0L)
                                    endMs = (startMs + 15000L).coerceAtMost(durationMs)
                                    player.seekTo(startMs)
                                    status = "Selected scene pick ${index + 1}. Adjust the trim points if needed."
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242236))
                            ) { Text("Moment ${index + 1}  •  ${formatTime(time)}", color = Color.White) }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("AI FACE TRACKING", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("Detect people and follow the main speaker locally", color = Muted, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val uri = selectedVideo ?: return@Button
                                scanningFaces = true
                                status = "AI is scanning faces on this device…"
                                scope.launch {
                                    try {
                                        val result = withContext(Dispatchers.Default) { analyseFaces(context, uri, durationMs) }
                                        faceTrack = result.track
                                        facePicks = result.picks
                                        status = if (result.track.isEmpty()) "No faces detected. You can still use the manual crop." else "Tracked faces in ${result.track.size} sampled moments; found ${result.picks.size} suggested clips."
                                    } catch (error: Exception) {
                                        status = "Face analysis failed: ${error.localizedMessage ?: "try another video"}"
                                    } finally {
                                        scanningFaces = false
                                    }
                                }
                            },
                            enabled = !scanningFaces,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292B34))
                        ) {
                            if (scanningFaces) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text(if (scanningFaces) "Scanning" else "Track faces")
                        }
                    }
                    if (faceTrack.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Face tracking data ready • Portrait export will follow the detected subject.", color = Green, fontSize = 11.sp)
                    }
                    facePicks.forEachIndexed { index, time ->
                        Button(
                            onClick = {
                                startMs = (time - 10000L).coerceAtLeast(0L)
                                endMs = (startMs + 30000L).coerceAtMost(durationMs)
                                player.seekTo(startMs)
                                status = "Selected AI face moment ${index + 1}. Adjust trim if needed."
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242236))
                        ) { Text("Face moment ${index + 1}  •  ${formatTime(time)}", color = Color.White) }
                    }
                }
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("OUTPUT FORMAT", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { portrait = true }, colors = ButtonDefaults.buttonColors(containerColor = if (portrait) Accent else Color(0xFF292B34)), modifier = Modifier.weight(1f)) { Text("9:16 Vertical") }
                        Button(onClick = { portrait = false }, colors = ButtonDefaults.buttonColors(containerColor = if (!portrait) Accent else Color(0xFF292B34)), modifier = Modifier.weight(1f)) { Text("Original") }
                    }
                    Text(if (portrait) "Portrait crop for Shorts, Reels and TikTok" else "Keep the source video's original framing", color = Muted, fontSize = 11.sp)
                }
                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Text("CAPTION OVERLAY", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    BasicTextField(
                        value = captionText,
                        onValueChange = { captionText = it.take(120) },
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF22242C), RoundedCornerShape(10.dp)).padding(12.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                        decorationBox = { inner ->
                            if (captionText.isEmpty()) Text("Type a caption to burn into the exported clip…", color = Muted, fontSize = 13.sp)
                            inner()
                        }
                    )
                    Text("Adds a styled caption overlay. Automatic word-by-word speech transcription is a later step.", color = Muted, fontSize = 10.sp)
                }
                Button(
                    onClick = {
                        val uri = selectedVideo ?: return@Button
                        if (endMs <= startMs) { status = "Choose a valid trim range"; return@Button }
                        exporting = true
                        exportProgress = 0f
                        status = "Preparing your clip…"
                        scope.launch {
                            exportVideo(context, uri, startMs, endMs, portrait, captionText, faceTrack,
                                onProgress = { exportProgress = it },
                                onComplete = { message -> exporting = false; status = message },
                                onError = { message -> exporting = false; status = message })
                        }
                    },
                    enabled = !exporting,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    if (exporting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (exporting) "Exporting…" else "Export clip to Gallery", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(18.dp))
                        Text("Turn long videos into shareable clips", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Import a video to preview it, select a section, choose vertical or original format, and export it to your Gallery.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }

            Button(
                onClick = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292B34))
            ) {
                Icon(Icons.Default.Movie, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (selectedVideo == null) "Choose video from Gallery" else "Change video")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!exporting && status.startsWith("Clip saved")) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
                Text(status, color = if (status.startsWith("Clip saved")) Green else Muted, fontSize = 11.sp)
            }
            if (exporting) {
                androidx.compose.material3.LinearProgressIndicator(progress = { exportProgress }, modifier = Modifier.fillMaxWidth(), color = Accent)
            }
            Text("Free on-device editing • No account • No paid API", color = Muted, fontSize = 10.sp)
        }
    }
}

private fun Player.isReadyForDuration(): Boolean = playbackState == Player.STATE_READY && duration > 0

private data class FaceTrackPoint(val timeMs: Long, val centerX: Float, val faceCount: Int)
private data class FaceScanResult(val track: List<FaceTrackPoint>, val picks: List<Long>)

private fun analyseFaces(context: Context, uri: Uri, durationMs: Long): FaceScanResult {
    if (durationMs < 3000L) return FaceScanResult(emptyList(), emptyList())
    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .enableTracking()
        .build()
    val detector = FaceDetection.getClient(options)
    val retriever = MediaMetadataRetriever()
    val points = mutableListOf<FaceTrackPoint>()
    val scored = mutableListOf<Pair<Long, Double>>()
    try {
        retriever.setDataSource(context, uri)
        var time = 700L
        while (time < durationMs - 500L) {
            val frame = retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null) {
                val sample = Bitmap.createScaledBitmap(frame, 640, (640f * frame.height / frame.width).toInt().coerceAtLeast(360), true)
                if (sample !== frame) frame.recycle()
                val faces = Tasks.await(detector.process(InputImage.fromBitmap(sample, 0)))
                if (faces.isNotEmpty()) {
                    val primary = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                    val center = (primary.boundingBox.centerX().toFloat() / sample.width).coerceIn(0.05f, 0.95f)
                    points.add(FaceTrackPoint(time, center, faces.size))
                    val area = primary.boundingBox.width().toDouble() * primary.boundingBox.height() / (sample.width.toDouble() * sample.height)
                    scored.add(time to (faces.size * 2.0 + area * 8.0))
                }
                sample.recycle()
            }
            time += 900L
        }
    } finally {
        retriever.release()
        detector.close()
    }
    val picks = mutableListOf<Long>()
    for ((time, score) in scored.sortedByDescending { it.second }) {
        if (score < 2.2) continue
        if (picks.none { kotlin.math.abs(it - time) < 12000L }) picks.add(time)
        if (picks.size >= 6) break
    }
    return FaceScanResult(points, picks.sorted())
}

private fun createFaceTrackingEffect(points: List<FaceTrackPoint>, clipStartMs: Long): MatrixTransformation {
    val sorted = points.sortedBy { it.timeMs }
    return MatrixTransformation { presentationTimeUs ->
        val absoluteMs = clipStartMs + presentationTimeUs / 1000L
        val before = sorted.lastOrNull { it.timeMs <= absoluteMs } ?: sorted.first()
        val after = sorted.firstOrNull { it.timeMs >= absoluteMs } ?: sorted.last()
        val center = if (before.timeMs == after.timeMs) before.centerX else {
            val fraction = ((absoluteMs - before.timeMs).toFloat() / (after.timeMs - before.timeMs).toFloat()).coerceIn(0f, 1f)
            before.centerX + (after.centerX - before.centerX) * fraction
        }
        // Presentation performs the 9:16 crop afterwards; this translation moves its crop window toward the tracked face.
        val target = (center * 2f - 1f).coerceIn(-0.4375f, 0.4375f)
        Matrix().apply { setTranslate(-target, 0f) }
    }
}


private fun findScenePicks(context: Context, uri: Uri, durationMs: Long): List<Long> {
    if (durationMs < 4000L) return emptyList()
    val retriever = MediaMetadataRetriever()
    val scores = mutableListOf<Pair<Long, Double>>()
    try {
        retriever.setDataSource(context, uri)
        var previous: Bitmap? = null
        var time = 1000L
        while (time < durationMs - 1000L) {
            val frame = retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                val small = Bitmap.createScaledBitmap(frame, 24, 24, true)
                if (small !== frame) frame.recycle()
                val pixels = IntArray(24 * 24)
                small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
                var score = 0.0
                previous?.let { old ->
                    val oldSmall = if (old.width == 24 && old.height == 24) old else Bitmap.createScaledBitmap(old, 24, 24, true)
                    val oldPixels = IntArray(24 * 24)
                    oldSmall.getPixels(oldPixels, 0, 24, 0, 0, 24, 24)
                    for (i in pixels.indices) {
                        val a = pixels[i]
                        val b = oldPixels[i]
                        val la = ((a shr 16 and 255) * 0.299 + (a shr 8 and 255) * 0.587 + (a and 255) * 0.114)
                        val lb = ((b shr 16 and 255) * 0.299 + (b shr 8 and 255) * 0.587 + (b and 255) * 0.114)
                        score += kotlin.math.abs(la - lb)
                    }
                    score /= pixels.size
                    scores.add(time to score)
                    if (oldSmall !== old) oldSmall.recycle()
                }
                previous?.recycle()
                previous = small
            }
            time += 2000L
        }
        previous?.recycle()
    } catch (_: Exception) {
        return emptyList()
    } finally {
        retriever.release()
    }
    val sorted = scores.sortedByDescending { it.second }
    val chosen = mutableListOf<Long>()
    for ((time, score) in sorted) {
        if (score < 16.0) break
        if (chosen.none { kotlin.math.abs(it - time) < 8000L }) chosen.add(time)
        if (chosen.size == 4) break
    }
    return chosen.sorted()
}

@OptIn(UnstableApi::class)
private suspend fun exportVideo(
    context: Context,
    source: Uri,
    startMs: Long,
    endMs: Long,
    portrait: Boolean,
    caption: String,
    faceTrack: List<FaceTrackPoint>,
    onProgress: (Float) -> Unit,
    onComplete: (String) -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.Main) {
        try {
            val output = File(context.cacheDir, "clipper_${System.currentTimeMillis()}.mp4")
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
            val mediaItem = MediaItem.Builder().setUri(source).setClippingConfiguration(clipping).build()
            val videoEffects = mutableListOf<androidx.media3.common.Effect>()
            if (portrait) {
                if (faceTrack.isNotEmpty()) videoEffects.add(createFaceTrackingEffect(faceTrack, startMs))
                videoEffects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            }
            if (caption.isNotBlank()) {
                val styledCaption = SpannableString(caption.trim()).apply {
                    setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                videoEffects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(styledCaption))))
            }
            val effects = Effects(emptyList(), videoEffects)
            val edited = EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
            val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    try {
                        val saved = copyToGallery(context, output)
                        output.delete()
                        onComplete(if (saved) "Clip saved to your Gallery" else "Export finished, but Gallery save failed")
                    } catch (error: Exception) {
                        onError("Gallery save failed: ${error.localizedMessage ?: "unknown error"}")
                    }
                }
                override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    onError("Export failed: ${exportException.localizedMessage ?: "try a shorter video"}")
                }
            }).build()
            transformer.start(edited, output.absolutePath)
            onProgress(0.1f)
        } catch (error: Exception) {
            onError("Could not start export: ${error.localizedMessage ?: "unknown error"}")
        }
    }
}

private fun copyToGallery(context: Context, source: File): Boolean {
    val name = "AIClip_${System.currentTimeMillis()}.mp4"
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AI Clipper")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { output ->
        FileInputStream(source).use { input -> input.copyTo(output) }
    } ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }
    return true
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return uri.lastPathSegment ?: "Selected video"
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
}
