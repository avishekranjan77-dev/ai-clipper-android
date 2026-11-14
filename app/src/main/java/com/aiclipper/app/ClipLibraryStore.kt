package com.aiclipper.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists generated clip metadata in app-private preferences while keeping the actual
 * rendered MP4 files in filesDir. Clips remain available after app restarts.
 */
internal object ClipLibraryStore {
    private const val PREFS_NAME = "ai_clipper_library"
    private const val KEY_CLIPS = "generated_clips_v1"

    fun load(context: Context): List<GeneratedClip> = runCatching {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLIPS, "[]") ?: "[]"
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val file = File(item.optString("path"))
                if (!file.isFile || file.length() == 0L) continue
                val startMs = item.optLong("startMs", 0L).coerceAtLeast(0L)
                val endMs = item.optLong("endMs", 0L)
                if (endMs <= startMs) continue
                add(
                    GeneratedClip(
                        file = file,
                        startMs = startMs,
                        endMs = endMs,
                        aspectRatio = item.optString("aspectRatio", "9:16"),
                        title = item.optString("title", "AI highlight"),
                        reason = item.optString("reason", ""),
                        score = item.optInt("score", 0).coerceIn(0, 100)
                    )
                )
            }
        }.distinctBy { it.file.absolutePath }
    }.getOrDefault(emptyList())

    fun save(context: Context, clips: List<GeneratedClip>) {
        val array = JSONArray()
        clips.filter { it.file.isFile && it.file.length() > 0L }.distinctBy { it.file.absolutePath }.forEach { clip ->
            array.put(
                JSONObject().apply {
                    put("path", clip.file.absolutePath)
                    put("startMs", clip.startMs)
                    put("endMs", clip.endMs)
                    put("aspectRatio", clip.aspectRatio)
                    put("title", clip.title)
                    put("reason", clip.reason)
                    put("score", clip.score)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLIPS, array.toString())
            .apply()
    }
}
