package com.aiclipper.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class GeneratedClip(val file: File, val startMs: Long, val endMs: Long, val aspectRatio: String = "9:16") {
    val durationMs: Long get() = endMs - startMs
}

@Composable
fun GeneratedClipReviewPanel(
    context: Context,
    clips: List<GeneratedClip>,
    onSaved: (Int) -> Unit,
    onError: (String) -> Unit
) {
    val player = remember { ExoPlayer.Builder(context).build() }
    var reviewClips by remember(clips) { mutableStateOf(clips) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var savedCount by remember { mutableIntStateOf(0) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reviewClips, selectedIndex) {
        val clip = reviewClips.getOrNull(selectedIndex) ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(clip.file)))
        player.prepare()
        player.playWhenReady = false
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Column(Modifier.fillMaxWidth().background(Color(0xFF15171D), RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("REVIEW YOUR AI CLIPS", color = Color.White, fontSize = 14.sp)
        Text("${reviewClips.size} edited previews ready • Nothing is saved to Gallery until you choose", color = Color(0xFF9295A1), fontSize = 11.sp)
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(250.dp).background(Color.Black, RoundedCornerShape(12.dp))
        )
        Text("Previewing clip ${selectedIndex + 1} of ${reviewClips.size}", color = Color.White, fontSize = 12.sp)
        if (editingIndex in reviewClips.indices) {
            ClipAdjustmentEditor(
                clip = reviewClips[editingIndex],
                onCancel = { editingIndex = -1 },
                onRendered = { replacement ->
                    val previous = reviewClips[editingIndex]
                    reviewClips = reviewClips.toMutableList().also { it[editingIndex] = replacement }
                    previous.file.delete()
                    selectedIndex = editingIndex
                    editingIndex = -1
                },
                onError = onError
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().height(230.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            itemsIndexed(reviewClips) { index, clip ->
                Row(Modifier.fillMaxWidth().background(if (index == selectedIndex) Color(0xFF29263F) else Color(0xFF22242C), RoundedCornerShape(10.dp)).padding(9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Clip ${index + 1}  •  ${clip.durationMs / 1000}s", color = Color.White, fontSize = 12.sp)
                        Text(if (index == selectedIndex) "Selected for preview" else "Tap Preview to watch", color = Color(0xFF9295A1), fontSize = 10.sp)
                    }
                    Button(onClick = { selectedIndex = index; player.seekTo(0); player.play() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF343044))) {
                        androidx.compose.material3.Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("Preview")
                    }
                    Spacer(Modifier.size(4.dp))
                    Button(onClick = { selectedIndex = index; editingIndex = index; player.pause() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF343044))) {
                        androidx.compose.material3.Icon(Icons.Default.Tune, contentDescription = "Adjust clip")
                        Text("Adjust")
                    }
                    Button(
                        onClick = {
                            if (busy) return@Button
                            busy = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { saveGeneratedClip(context, clip.file) }
                                    clip.file.delete()
                                    reviewClips = reviewClips.filterNot { it.file == clip.file }
                                    selectedIndex = selectedIndex.coerceAtMost((reviewClips.size - 1).coerceAtLeast(0))
                                    savedCount++
                                    onSaved(1)
                                } catch (error: Exception) { onError(error.localizedMessage ?: "Could not save clip") }
                                finally { busy = false }
                            }
                        },
                        enabled = !busy && clip.file.exists(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF292B34))
                    ) { androidx.compose.material3.Icon(Icons.Default.Download, contentDescription = "Download clip") }
                }
            }
        }
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                scope.launch {
                    var count = 0
                    try {
                        withContext(Dispatchers.IO) {
                            reviewClips.filter { it.file.exists() }.forEach { clip ->
                                saveGeneratedClip(context, clip.file)
                                clip.file.delete()
                                count++
                            }
                        }
                        reviewClips = reviewClips.filter { it.file.exists() }
                        selectedIndex = selectedIndex.coerceAtMost((reviewClips.size - 1).coerceAtLeast(0))
                        savedCount += count
                        onSaved(count)
                    } catch (error: Exception) { onError(error.localizedMessage ?: "Some clips could not be saved") }
                    finally { busy = false }
                }
            },
            enabled = !busy && reviewClips.any { it.file.exists() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B7CFF))
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            else androidx.compose.material3.Icon(Icons.Default.Download, contentDescription = null)
            Spacer(Modifier.size(7.dp))
            Text(if (busy) "Saving…" else "Download all remaining clips")
        }
        if (savedCount > 0) Text("$savedCount clips saved to Gallery. You can still download the remaining previews.", color = Color(0xFF62D69A), fontSize = 11.sp)
    }
}

private fun saveGeneratedClip(context: Context, source: File) {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "AIClip_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AI Clipper")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Gallery could not create a video entry")
    try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            FileInputStream(source).use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("Gallery output stream unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        }
    } catch (error: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
}
