package com.aiclipper.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AppBackground = Color(0xFF090A0D)
private val Panel = Color(0xFF15171D)
private val Accent = Color(0xFF8B7CFF)
private val Muted = Color(0xFF9295A1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                background = AppBackground,
                surface = Panel,
                primary = Accent,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
                ClipperHome(applicationContext)
            }
        }
    }
}

@Composable
private fun ClipperHome(context: Context) {
    var selectedVideo by remember { mutableStateOf<Uri?>(null) }
    var selectedVideoName by remember { mutableStateOf<String?>(null) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedVideo = uri
        selectedVideoName = uri?.let { queryDisplayName(context, it) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AppBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("AI CLIPPER", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Turn long videos into", color = Muted, fontSize = 14.sp)
                    Text("moments worth sharing.", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(Panel, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Accent)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Panel, RoundedCornerShape(24.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(Color(0xFF242236), RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Accent, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (selectedVideo == null) "Create your first clips" else "Video ready to import",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    selectedVideoName ?: "Choose a video from your phone. The next step will analyse it and find engaging moments.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text(
                        if (selectedVideo == null) "Choose a video" else "Choose another video",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (selectedVideo != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF62D69A), modifier = Modifier.size(16.dp))
                        Text(" Video selected successfully", color = Color(0xFF62D69A), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Private photo picker • No broad storage permission", color = Muted, fontSize = 11.sp)
            }

            Text("YOUR WORKSPACE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Your generated clips will appear here.", color = Muted, fontSize = 13.sp)
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) return cursor.getString(nameIndex)
        }
    }
    return uri.lastPathSegment ?: "Selected video"
}
