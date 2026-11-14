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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetectorOptions

private val AppBackground = Color(0xFF090A0D)
private val Panel = Color(0xFF15171D)
private val Accent = Color(0xFF8B7CFF)
private val Muted = Color(0xFF9295A1)
private val Green = Color(0xFF62D69A)
const val MIN_SHORT_DURATION_MS = ClipIntelligence.MIN_SHORT_DURATION_MS

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
    var videoLink by remember { mutableStateOf("") }
    var requestedClipCount by remember { mutableFloatStateOf(10f) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var outputAspect by remember { mutableStateOf("9:16") }
    var captionText by remember { mutableStateOf("") }
    var speechCaptions by remember { mutableStateOf<List<CaptionCue>>(emptyList()) }
    var speakerTurns by remember { mutableStateOf<List<SpeakerTurn>>(emptyList()) }
    var speakerModelReady by remember { mutableStateOf(SpeechCaptionEngine.isSpeakerModelInstalled(context)) }
    var audioEnergy by remember { mutableStateOf<List<AudioEnergyPoint>>(emptyList()) }
    var speechModelReady by remember { mutableStateOf(SpeechCaptionEngine.isModelInstalled(context)) }
    var processing by remember { mutableStateOf(false) }
    var processingProgress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("Choose a video to start editing") }
    var scenePicks by remember { mutableStateOf<List<Long>>(emptyList()) }
    var facePicks by remember { mutableStateOf<List<Long>>(emptyList()) }
    var faceTrack by remember { mutableStateOf<List<FaceTrackPoint>>(emptyList()) }
    var faceLayouts by remember { mutableStateOf<List<FaceLayoutSample>>(emptyList()) }
    var twoPersonSplitScreen by remember { mutableStateOf(true) }
    var captionStyle by remember { mutableStateOf(CaptionStyle.BOLD_POP) }
    var generatedClips by remember { mutableStateOf<List<GeneratedClip>>(emptyList()) }
    var semanticModelReady by remember { mutableStateOf(SemanticHighlightEngine.isModelInstalled(context)) }
    var semanticHighlights by remember { mutableStateOf<List<SemanticHighlight>>(emptyList()) }
    var semanticBusy by remember { mutableStateOf(false) }
    var semanticProgress by remember { mutableFloatStateOf(0f) }
    var scanningFaces by remember { mutableStateOf(false) }
    var hasAnalysedVideo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) { onDispose { player.release() } }

    DisposableEffect(selectedVideo, player) {
        val uri = selectedVideo
        if (uri == null) {
            onDispose { }
        } else {
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
            onDispose { player.removeListener(listener) }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedVideo = uri
        generatedClips.forEach { it.file.delete() }
        generatedClips = emptyList()
        semanticHighlights = emptyList()
        hasAnalysedVideo = false
        speechCaptions = emptyList()
        speakerTurns = emptyList()
        audioEnergy = emptyList()
        faceTrack = emptyList()
        faceLayouts = emptyList()
        facePicks = emptyList()
        scenePicks = emptyList()
        selectedVideoName = uri?.let { queryDisplayName(context, it) }
        status = if (uri == null) "No video selected" else "Video loaded. Choose your settings and create AI clips."
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBackground) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI CLIPPER", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Your pocket video studio", color = Muted, fontSize = 13.sp)
                }
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Accent, modifier = Modifier.size(28.dp))
            }

            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text("IMPORT FROM LINK", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = videoLink,
                    onValueChange = { videoLink = it.take(2048) },
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF22242C), RoundedCornerShape(10.dp)).padding(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (videoLink.isEmpty()) Text("Paste a direct MP4 video URL…", color = Muted, fontSize = 13.sp)
                        inner()
                    }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val raw = videoLink.trim()
                        val parsed = runCatching { Uri.parse(raw) }.getOrNull()
                        val host = parsed?.host?.lowercase(Locale.US).orEmpty()
                        if (parsed == null || parsed.scheme !in listOf("http", "https") || host.isBlank()) {
                            status = "Enter a valid https:// video-file URL."
                        } else if (host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" || host.endsWith(".youtu.be")) {
                            status = "YouTube links are recognised, but YouTube does not provide this app with the video stream needed for frame-by-frame AI editing. Import an authorised source video file to create clips."
                        } else if (!raw.substringBefore("?").lowercase(Locale.US).let { it.endsWith(".mp4") || it.endsWith(".m4v") || it.endsWith(".mov") || it.endsWith(".webm") }) {
                            status = "This link does not look like a direct video file. Use a direct MP4, M4V, MOV or WebM URL."
                        } else {
                            status = "Downloading linked video to local storage…"
                            scope.launch {
                                try {
                                    val localFile = withContext(Dispatchers.IO) { downloadLinkedVideo(context, parsed) }
                                    generatedClips.forEach { it.file.delete() }
                                    generatedClips = emptyList()
                                    semanticHighlights = emptyList()
                                    selectedVideo = Uri.fromFile(localFile)
                                    selectedVideoName = localFile.name
                                    speechCaptions = emptyList()
                                    speakerTurns = emptyList()
                                    audioEnergy = emptyList()
                                    hasAnalysedVideo = false
                                    faceTrack = emptyList()
                                    faceLayouts = emptyList()
                                    facePicks = emptyList()
                                    scenePicks = emptyList()
                                    status = "Linked video imported. You can now analyse and create clips."
                                } catch (error: Exception) {
                                    status = "Link import failed: ${error.localizedMessage ?: "check the URL and access permissions"}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292B34))
                ) { Text("Use video link") }
                Text("Direct video-file links only. A YouTube URL alone cannot provide the frames and audio needed for editing.", color = Muted, fontSize = 10.sp)
            }

            if (selectedVideo != null) {
                AndroidView(
                    factory = { viewContext -> PlayerView(viewContext).apply { this.player = player; useController = true } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.Black, RoundedCornerShape(18.dp))
                )
                Text(selectedVideoName ?: "Selected video", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)

                Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("CREATE YOUR SHORTS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Choose your settings once. Everything else runs automatically in the background.", color = Muted, fontSize = 11.sp)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("NUMBER OF CLIPS", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(requestedClipCount.toInt().toString(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Slider(
                        value = requestedClipCount,
                        onValueChange = { if (!processing) requestedClipCount = it },
                        valueRange = 1f..30f,
                        steps = 28
                    )

                    Text("OUTPUT FORMAT", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("9:16", "1:1", "16:9", "Original").forEach { aspect ->
                            Button(
                                onClick = { if (!processing) outputAspect = aspect },
                                enabled = !processing,
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (outputAspect == aspect) Accent else Color(0xFF292B34))
                            ) { Text(aspect, fontSize = 10.sp, maxLines = 1) }
                        }
                    }

                    Text("CAPTION STYLE", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        CaptionStyle.values().forEach { style ->
                            Button(
                                onClick = { if (!processing) captionStyle = style },
                                enabled = !processing,
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (captionStyle == style) Accent else Color(0xFF292B34))
                            ) { Text(style.label, fontSize = 9.sp, maxLines = 1) }
                        }
                    }

                    Button(
                        onClick = { if (!processing) twoPersonSplitScreen = !twoPersonSplitScreen },
                        enabled = !processing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = if (twoPersonSplitScreen) Accent else Color(0xFF292B34))
                    ) {
                        Text(if (twoPersonSplitScreen) "Automatic speaker split-screen: ON" else "Automatic speaker split-screen: OFF")
                    }

                    if (!semanticModelReady) {
                        Column(Modifier.fillMaxWidth().background(Color(0xFF20202A), RoundedCornerShape(14.dp)).padding(12.dp)) {
                            Text("ONE-TIME AI SETUP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Download the free language model once. This panel disappears after installation.", color = Muted, fontSize = 10.sp)
                            Button(
                                onClick = {
                                    if (semanticBusy || processing) return@Button
                                    semanticBusy = true
                                    semanticProgress = 0f
                                    status = "Downloading the one-time AI model…"
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                SemanticHighlightEngine.downloadModel(context) { value ->
                                                    scope.launch { semanticProgress = value }
                                                }
                                            }
                                            semanticModelReady = true
                                            status = "AI model installed. It will now run automatically."
                                        } catch (error: Exception) {
                                            status = "AI model download failed."
                                        } finally {
                                            semanticBusy = false
                                        }
                                    }
                                },
                                enabled = !semanticBusy && !processing,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                if (semanticBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (semanticBusy) "Downloading AI…" else "Download free AI model")
                            }
                            if (semanticBusy) {
                                androidx.compose.material3.LinearProgressIndicator(progress = { semanticProgress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                            }
                        }
                    }

                    if (processing) {
                        Column(Modifier.fillMaxWidth().background(Color(0xFF20202A), RoundedCornerShape(14.dp)).padding(12.dp)) {
                            Text("AI EDITING IN PROGRESS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(status, color = Muted, fontSize = 11.sp)
                            Spacer(Modifier.height(7.dp))
                            androidx.compose.material3.LinearProgressIndicator(progress = { processingProgress }, modifier = Modifier.fillMaxWidth(), color = Accent)
                            Spacer(Modifier.height(5.dp))
                            Text("Analysis, editing and rendering are running automatically. Controls stay disabled until the finished clips are ready.", color = Muted, fontSize = 9.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                val uri = selectedVideo ?: return@Button
                                if (durationMs < MIN_SHORT_DURATION_MS) {
                                    status = "This video is shorter than the 60-second minimum."
                                    return@Button
                                }
                                processing = true
                                processingProgress = 0f
                                generatedClips.forEach { it.file.delete() }
                                generatedClips = emptyList()
                                semanticHighlights = emptyList()
                                status = "Starting automatic editing…"

                                scope.launch {
                                    try {
                                        if (!speechModelReady) {
                                            status = "Installing offline speech recognition…"
                                            try {
                                                withContext(Dispatchers.IO) { SpeechCaptionEngine.downloadModel(context) {} }
                                                speechModelReady = true
                                            } catch (_: Exception) {
                                                speechModelReady = false
                                            }
                                        }
                                        var speakerRecognitionAvailable = speakerModelReady
                                        if (!speakerModelReady) {
                                            status = "Installing optional speaker recognition…"
                                            try {
                                                withContext(Dispatchers.IO) { SpeechCaptionEngine.downloadSpeakerModel(context) {} }
                                                speakerModelReady = true
                                                speakerRecognitionAvailable = true
                                            } catch (_: Exception) {
                                                // Speaker diarization is optional. Continue with face tracking if its model is unavailable.
                                                speakerRecognitionAvailable = false
                                            }
                                        }

                                        status = if (speakerRecognitionAvailable) "Transcribing speech and analysing audio…" else "Transcribing speech (speaker model unavailable; face tracking remains enabled)…"
                                        val speech = if (speechModelReady) {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    SpeechCaptionEngine.transcribe(context, uri) {}
                                                }
                                            } catch (_: Exception) {
                                                status = "Speech analysis unavailable; continuing with visual scene and face analysis…"
                                                SpeechAnalysisResult(emptyList(), emptyList(), emptyList())
                                            }
                                        } else {
                                            status = "Speech model unavailable; continuing with visual scene and face analysis…"
                                            SpeechAnalysisResult(emptyList(), emptyList(), emptyList())
                                        }
                                        speechCaptions = speech.captions
                                        speakerTurns = speech.speakerTurns
                                        audioEnergy = speech.audioEnergy
                                        processingProgress = 0.18f

                                        status = "Tracking faces and scene changes…"
                                        val faceResult = try {
                                            withContext(Dispatchers.Default) {
                                                analyseFaces(context, uri, durationMs)
                                            }
                                        } catch (_: Exception) {
                                            FaceScanResult(emptyList(), emptyList(), emptyList())
                                        }
                                        faceTrack = faceResult.track
                                        faceLayouts = faceResult.layouts
                                        facePicks = faceResult.picks
                                        scenePicks = try {
                                            withContext(Dispatchers.Default) {
                                                findScenePicks(context, uri, durationMs)
                                            }
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                        processingProgress = 0.38f

                                        var ranges: List<ClipRange> = emptyList()
                                        if (semanticModelReady && speechCaptions.isNotEmpty()) {
                                            status = "Understanding the conversation with on-device AI…"
                                            semanticHighlights = SemanticHighlightEngine.analyse(
                                                context, speechCaptions, durationMs, requestedClipCount.toInt(),
                                                facePicks, scenePicks, audioEnergy, speakerTurns
                                            ) { value ->
                                                scope.launch { processingProgress = 0.38f + value * 0.30f }
                                            }
                                            ranges = semanticHighlights.take(requestedClipCount.toInt()).map {
                                                ClipRange(it.startMs, it.endMs)
                                            }
                                        } else {
                                            status = "Ranking the strongest moments…"
                                            processingProgress = 0.60f
                                        }

                                        val fallbackMoments = buildClipMoments(
                                            facePicks, scenePicks, speechCaptions, audioEnergy,
                                            durationMs, requestedClipCount.toInt()
                                        )
                                        val usableRanges = ClipIntelligence.mergeExportRanges(
                                            preferredRanges = ranges,
                                            fallbackMoments = fallbackMoments,
                                            durationMs = durationMs,
                                            requestedCount = requestedClipCount.toInt()
                                        )
                                        val moments = usableRanges.map { it.startMs + 20_000L }

                                        if (usableRanges.isEmpty()) {
                                            throw IllegalStateException("AI could not find any usable 60-second moments.")
                                        }

                                        status = "Rendering edited clips…"
                                        processingProgress = 0.70f

                                        exportSuggestedClips(
                                            context, uri, moments, durationMs, faceTrack, faceLayouts,
                                            twoPersonSplitScreen, speakerTurns, captionText, speechCaptions,
                                            captionStyle, usableRanges, semanticHighlights, outputAspect,
                                            onProgress = { value ->
                                                scope.launch {
                                                    processingProgress = 0.70f + value * 0.30f
                                                }
                                            },
                                            onComplete = { clips ->
                                                generatedClips = clips
                                                processing = false
                                                processingProgress = 1f
                                                status = "Finished. Review the edited clips below."
                                            },
                                            onError = { message ->
                                                processing = false
                                                status = message
                                            }
                                        )
                                    } catch (error: Exception) {
                                        processing = false
                                        processingProgress = 0f
                                        status = "Automatic editing failed: ${error.localizedMessage ?: error.javaClass.simpleName}. Your source video is unchanged; check storage space and model availability, then retry."
                                    }
                                }
                            },
                            enabled = !processing && !semanticBusy && durationMs >= MIN_SHORT_DURATION_MS,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Create AI clips", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Text(
                        if (durationMs < MIN_SHORT_DURATION_MS) "Source must be at least 60 seconds."
                        else "The next screen appears only after the edited video files have actually been rendered.",
                        color = Muted,
                        fontSize = 10.sp
                    )
                }

                if (generatedClips.isNotEmpty()) {
                    GeneratedClipReviewPanel(
                        context,
                        generatedClips,
                        onSaved = { count -> status = "Clips saved to Gallery." },
                        onClipsChanged = { updated -> generatedClips = updated },
                        onError = { message -> status = message }
                    )
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth().background(Panel, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(54.dp))
                        Spacer(Modifier.height(18.dp))
                        Text("Turn long videos into shareable clips", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Import a video, choose the number and format, then let AI do the analysis, editing and rendering automatically.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
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
                if (!processing && status.startsWith("Clip saved")) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(16.dp))
                Text(status, color = if (status.startsWith("Clip saved")) Green else Muted, fontSize = 11.sp)
            }
            if (processing) {
                androidx.compose.material3.LinearProgressIndicator(progress = { processingProgress }, modifier = Modifier.fillMaxWidth(), color = Accent)
            }
            Text("Free on-device editing • No account • No paid API", color = Muted, fontSize = 10.sp)
        }
    }
}

private fun buildClipMoments(
    faceMoments: List<Long>,
    sceneMoments: List<Long>,
    speechCaptions: List<CaptionCue>,
    audioEnergy: List<AudioEnergyPoint>,
    durationMs: Long,
    requestedCount: Int
): List<Long> = ClipIntelligence.selectMoments(
    faceMoments, sceneMoments, speechCaptions, audioEnergy, durationMs, requestedCount
)

private fun Player.isReadyForDuration(): Boolean = playbackState == Player.STATE_READY && duration > 0

private data class FaceTrackPoint(val timeMs: Long, val centerX: Float, val faceCount: Int, val aspectRatio: Float, val primaryFaceId: Int?)
internal data class FaceCenter(val x: Float, val y: Float, val mouthOpen: Float = 0f)
internal data class FaceLayoutSample(val timeMs: Long, val faces: List<FaceCenter>, val sourceAspectRatio: Float)
private data class FaceScanResult(val track: List<FaceTrackPoint>, val picks: List<Long>, val layouts: List<FaceLayoutSample>)

private fun analyseFaces(context: Context, uri: Uri, durationMs: Long): FaceScanResult {
    if (durationMs < 3000L) return FaceScanResult(emptyList(), emptyList(), emptyList())
    val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setMinFaceSize(0.06f)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .enableTracking()
        .build()
    val detector = FaceDetection.getClient(options)
    val retriever = MediaMetadataRetriever()
    val points = mutableListOf<FaceTrackPoint>()
    val scored = mutableListOf<Pair<Long, Double>>()
    val layouts = mutableListOf<FaceLayoutSample>()
    var primaryFaceId: Int? = null
    var previousCenterX = 0.5f
    try {
        retriever.setDataSource(context, uri)
        var time = 700L
        while (time < durationMs - 500L) {
            val frame = retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame != null) {
                val sampleHeight = (640f * frame.height / frame.width).toInt().coerceAtLeast(1)
                val sample = Bitmap.createScaledBitmap(frame, 640, sampleHeight, true)
                if (sample !== frame) frame.recycle()
                val faces = Tasks.await(detector.process(InputImage.fromBitmap(sample, 0)))
                if (faces.isNotEmpty()) {
                    val faceCenters = faces.map { face ->
                        face.boundingBox.centerX().toFloat() / sample.width
                    }
                    val visibleFaces = faces.sortedBy { it.boundingBox.centerX() }.take(2)
                    layouts.add(FaceLayoutSample(
                        time,
                        visibleFaces.map { face ->
                            val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
                            val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
                            val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
                            val mouthOpen = if (mouthLeft != null && mouthRight != null && mouthBottom != null) {
                                (kotlin.math.abs(mouthBottom.y - (mouthLeft.y + mouthRight.y) / 2f) /
                                    face.boundingBox.height().toFloat().coerceAtLeast(1f)).coerceIn(0f, 0.25f)
                            } else 0f
                            FaceCenter(
                                (face.boundingBox.centerX().toFloat() / sample.width).coerceIn(0.05f, 0.95f),
                                (face.boundingBox.centerY().toFloat() / sample.height).coerceIn(0.05f, 0.95f),
                                mouthOpen
                            )
                        },
                        sample.width.toFloat() / sample.height.toFloat()
                    ))
                    val selectedFace = faces.firstOrNull { it.trackingId == primaryFaceId }
                        ?: faces.minByOrNull { kotlin.math.abs(it.boundingBox.centerX().toFloat() / sample.width - previousCenterX) }
                        ?: faces.maxBy { it.boundingBox.width() * it.boundingBox.height() }
                    if (faces.size == 1) primaryFaceId = selectedFace.trackingId
                    val center = if (faces.size > 1) {
                        // Keep a conversation group in frame; larger, nearer faces get a little more weight.
                        val weights = faces.map { face ->
                            kotlin.math.sqrt((face.boundingBox.width().toFloat() * face.boundingBox.height()).coerceAtLeast(1f))
                        }
                        (faceCenters.indices.fold(0f) { total, i -> total + faceCenters[i] * weights[i] } / weights.sum())
                    } else {
                        selectedFace.boundingBox.centerX().toFloat() / sample.width
                    }.coerceIn(0.05f, 0.95f)
                    previousCenterX = center
                    points.add(FaceTrackPoint(time, center, faces.size, sample.width.toFloat() / sample.height.toFloat(), selectedFace.trackingId))
                    val totalFaceArea = faces.sumOf {
                        it.boundingBox.width().toDouble() * it.boundingBox.height()
                    } / (sample.width.toDouble() * sample.height)
                    scored.add(time to (faces.size * 2.0 + totalFaceArea.coerceAtMost(1.0) * 8.0))
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
        if (picks.none { kotlin.math.abs(it - time) < 30000L }) picks.add(time)
        if (picks.size >= 6) break
    }
    return FaceScanResult(points, picks.sorted(), layouts)
}

private fun createFaceTrackingEffect(points: List<FaceTrackPoint>, clipStartMs: Long, outputAspectName: String = "9:16"): MatrixTransformation {
    val sorted = points.sortedBy { it.timeMs }
    require(sorted.isNotEmpty()) { "Face tracking requires at least one detected face" }
    return MatrixTransformation { presentationTimeUs ->
        val absoluteMs = clipStartMs + presentationTimeUs / 1000L
        val beforeIndex = sorted.indexOfLast { it.timeMs <= absoluteMs }.coerceAtLeast(0)
        val afterIndex = sorted.indexOfFirst { it.timeMs >= absoluteMs }.let {
            if (it < 0) sorted.lastIndex else it
        }
        val before = sorted[beforeIndex]
        val after = sorted[afterIndex]
        val interpolated = if (before.timeMs == after.timeMs) before.centerX else {
            val fraction = ((absoluteMs - before.timeMs).toFloat() /
                (after.timeMs - before.timeMs).toFloat()).coerceIn(0f, 1f)
            before.centerX + (after.centerX - before.centerX) * fraction
        }
        // Smooth nearby samples to reduce camera shake, while retaining gradual subject movement.
        val nearby = sorted.filter { kotlin.math.abs(it.timeMs - absoluteMs) <= 1800L }
        val smoothed = if (nearby.isEmpty()) interpolated else
            (interpolated * 0.55f + nearby.map { it.centerX }.average().toFloat() * 0.45f)
        // The 9:16 crop can move substantially away from the source centre on landscape footage.
        // MatrixTransformation operates in normalized device coordinates (-1..1).
        val inputAspect = sorted.first().aspectRatio.coerceAtLeast(0.1f)
        val outputAspect = when (outputAspectName) {
            "1:1" -> 1f
            "16:9" -> 16f / 9f
            "Original" -> inputAspect
            else -> 9f / 16f
        }
        // Compensate for the scale applied by the later Presentation effect.
        val cropScale = inputAspect / outputAspect
        val desiredOffset = if (inputAspect > outputAspect) {
            (((smoothed - 0.5f) * 2f) / cropScale).coerceIn(-0.32f, 0.32f)
        } else 0f
        Matrix().apply { postTranslate(-desiredOffset, 0f) }
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
    faceLayouts: List<FaceLayoutSample>,
    speechCaptions: List<CaptionCue>,
    captionStyle: CaptionStyle,
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
                if (portrait) videoEffects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            }
            val clipCues = speechCaptions.filter { it.endMs > startMs && it.startMs < endMs }.map {
                CaptionCue((it.startMs - startMs).coerceAtLeast(0L), (it.endMs - startMs).coerceAtMost(endMs - startMs), it.text)
            }
            if (clipCues.isNotEmpty()) {
                videoEffects.add(OverlayEffect(listOf(TimedCaptionOverlay(clipCues, captionStyle, faceLayouts, startMs))))
            } else if (caption.isNotBlank()) {
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

@OptIn(UnstableApi::class)
private suspend fun exportSuggestedClips(
    context: Context,
    source: Uri,
    moments: List<Long>,
    durationMs: Long,
    faceTrack: List<FaceTrackPoint>,
    faceLayouts: List<FaceLayoutSample>,
    twoPersonSplitScreen: Boolean,
    speakerTurns: List<SpeakerTurn>,
    caption: String,
    speechCaptions: List<CaptionCue>,
    captionStyle: CaptionStyle,
    preferredRanges: List<ClipRange>,
    highlights: List<SemanticHighlight>,
    outputAspect: String,
    onProgress: (Float) -> Unit,
    onComplete: (List<GeneratedClip>) -> Unit,
    onError: (String) -> Unit
) = withContext(Dispatchers.Main) {
    val clips = (preferredRanges.takeIf { it.isNotEmpty() } ?: ClipIntelligence.buildExportRanges(moments, durationMs))
        .filter { it.durationMs >= MIN_SHORT_DURATION_MS && it.startMs >= 0L && it.endMs <= durationMs }
        .map { it.startMs to it.endMs }
    if (clips.isEmpty()) {
        onError("No usable AI clip ranges were found.")
        return@withContext
    }
    var index = 0
    val generated = mutableListOf<GeneratedClip>()
    fun exportNext() {
        if (index >= clips.size) {
            onProgress(1f)
            onComplete(generated.toList())
            return
        }
        val (startMs, endMs) = clips[index]
        val output = File(context.filesDir, "ai_clip_${System.currentTimeMillis()}_${index}.mp4")
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder().setUri(source).setClippingConfiguration(clipping).build()
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        if (twoPersonSplitScreen && faceLayouts.any { it.faces.size >= 2 }) {
            videoEffects.add(TwoPersonSplitScreenEffect(faceLayouts, speakerTurns, SpeakerFaceMatcher.assign(speakerTurns, faceLayouts), startMs))
        } else if (faceTrack.isNotEmpty()) {
            videoEffects.add(createFaceTrackingEffect(faceTrack, startMs, outputAspect))
        }
        when (outputAspect) {
            "9:16" -> videoEffects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            "1:1" -> videoEffects.add(Presentation.createForWidthAndHeight(1080, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            "16:9" -> videoEffects.add(Presentation.createForWidthAndHeight(1920, 1080, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            // Original keeps the source video's dimensions and aspect ratio.
        }
        val clipCues = speechCaptions.filter { it.endMs > startMs && it.startMs < endMs }.map { cue ->
            CaptionCue(
                (cue.startMs - startMs).coerceAtLeast(0L),
                (cue.endMs - startMs).coerceAtMost(endMs - startMs),
                cue.text,
                cue.words.filter { word -> word.endMs > startMs && word.startMs < endMs }.map { word ->
                    CaptionWord(
                        (word.startMs - startMs).coerceAtLeast(0L),
                        (word.endMs - startMs).coerceAtMost(endMs - startMs),
                        word.text
                    )
                }
            )
        }
        if (clipCues.isNotEmpty()) {
            videoEffects.add(OverlayEffect(listOf(TimedCaptionOverlay(clipCues, captionStyle, faceLayouts, startMs))))
        } else if (caption.isNotBlank()) {
            val styled = SpannableString(caption.trim()).apply {
                setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            videoEffects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(styled))))
        }
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), videoEffects)).build()
        var stopProgress: () -> Unit = {}
        val transformer = Transformer.Builder(context).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                stopProgress()
                try {
                    val matchingHighlight = highlights.maxByOrNull { highlight ->
                        (minOf(highlight.endMs, endMs) - maxOf(highlight.startMs, startMs)).coerceAtLeast(0L)
                    }?.takeIf { it.endMs > startMs && it.startMs < endMs }
                    generated.add(
                        GeneratedClip(
                            output, startMs, endMs, outputAspect,
                            matchingHighlight?.title ?: "AI highlight ${index + 1}",
                            matchingHighlight?.reason.orEmpty(),
                            matchingHighlight?.score ?: 0
                        )
                    )
                    index++
                    onProgress(index.toFloat() / clips.size)
                    exportNext()
                } catch (error: Exception) {
                    output.delete()
                    generated.forEach { it.file.delete() }
                    onError("Clip ${index + 1} could not be prepared: ${error.localizedMessage ?: "unknown error"}")
                }
            }
            override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exportException: ExportException) {
                stopProgress()
                output.delete()
                generated.forEach { it.file.delete() }
                onError("Clip ${index + 1} export failed: ${exportException.localizedMessage ?: "unknown error"}")
            }
        }).build()
        try {
            transformer.start(edited, output.absolutePath)
            val progressHolder = androidx.media3.transformer.ProgressHolder()
            stopProgress = scopeExportProgress(transformer, progressHolder) { value ->
                onProgress((index.toFloat() + value) / clips.size)
            }
        } catch (error: Exception) {
            stopProgress()
            output.delete()
            generated.forEach { it.file.delete() }
            onError("Could not start clip ${index + 1} export: ${error.localizedMessage ?: "unknown error"}")
        }
    }
    exportNext()
}

private fun scopeExportProgress(
    transformer: Transformer,
    progressHolder: androidx.media3.transformer.ProgressHolder,
    onProgress: (Float) -> Unit
): () -> Unit {
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var active = true
    val poll = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                val state = transformer.getProgress(progressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress / 100f)
                    handler.postDelayed(this, 500L)
                } else {
                    handler.postDelayed(this, 500L)
                }
            } catch (_: Exception) {
                active = false
            }
        }
    }
    handler.post(poll)
    return {
        active = false
        handler.removeCallbacks(poll)
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

private fun downloadLinkedVideo(context: Context, uri: Uri): File {
    val extension = uri.lastPathSegment?.substringAfterLast('.', "mp4")?.lowercase(Locale.US)
        ?.takeIf { it in setOf("mp4", "m4v", "mov", "webm") } ?: "mp4"
    val destination = File(context.cacheDir, "linked_video_${System.currentTimeMillis()}.$extension")
    val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20000
        readTimeout = 60000
        instanceFollowRedirects = true
        requestMethod = "GET"
    }
    try {
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Server returned HTTP ${connection.responseCode}")
        }
        val contentType = connection.contentType?.lowercase(Locale.US).orEmpty()
        if (contentType.isNotBlank() && !contentType.startsWith("video/") && !contentType.contains("octet-stream")) {
            throw IllegalStateException("The link did not return a video file")
        }
        val maxBytes = 2L * 1024L * 1024L * 1024L
        if (connection.contentLengthLong > maxBytes) throw IllegalStateException("Video link is larger than the 2 GB import limit")
        connection.inputStream.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IllegalStateException("Video link is larger than the 2 GB import limit")
                    output.write(buffer, 0, count)
                }
            }
        }
        if (destination.length() < 1024L) throw IllegalStateException("Downloaded file is empty or incomplete")
        return destination
    } catch (error: Exception) {
        destination.delete()
        throw error
    } finally {
        connection.disconnect()
    }
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
