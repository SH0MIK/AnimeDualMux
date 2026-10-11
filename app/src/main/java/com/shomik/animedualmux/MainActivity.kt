package com.shomik.animedualmux

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.Executors

private data class MediaTrack(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String,
    val title: String,
    val isDefault: Boolean,
    val width: Int = 0,
    val height: Int = 0
) {
    val label: String
        get() = buildString {
            append("#$index · ")
            append(if (language == "und") "Unknown language" else language.uppercase(Locale.ROOT))
            if (title.isNotBlank()) append(" · $title")
            append(" · $codec")
            if (type == "video" && width > 0) append(" · ${width}×${height}")
            if (isDefault) append(" · default")
        }
}

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AnimeDualMuxScreen() }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    @Composable
    private fun AnimeDualMuxScreen() {
        var inputFile by remember { mutableStateOf<File?>(null) }
        var displayName by remember { mutableStateOf("") }
        var tracks by remember { mutableStateOf<List<MediaTrack>>(emptyList()) }
        var selectedAudio by remember { mutableStateOf<Int?>(null) }
        var selectedSubtitle by remember { mutableStateOf<Int?>(null) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Choose an MKV to begin.") }
        var error by remember { mutableStateOf(false) }

        val openInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                busy = true
                error = false
                status = "Copying selected file into app storage…"
                worker.execute {
                    try {
                        val name = queryDisplayName(uri) ?: "selected-video.mkv"
                        val safeName = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val local = File(cacheDir, "input_${System.currentTimeMillis()}_$safeName")
                        contentResolver.openInputStream(uri).use { source ->
                            requireNotNull(source) { "Could not open the selected file." }
                            local.outputStream().buffered().use { sink -> source.copyTo(sink) }
                        }
                        val probe = probe(local)
                        val audio = probe.filter { it.type == "audio" }
                        val subtitles = probe.filter { it.type == "subtitle" }
                        runOnUiThread {
                            inputFile = local
                            displayName = name
                            tracks = probe
                            // Skip language guessing; let the user choose the exact tracks.
                            selectedAudio = null
                            selectedSubtitle = null
                            status = "Found ${probe.size} tracks. Select the Japanese audio and subtitle manually."
                            busy = false
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            status = "Could not scan file: ${e.message ?: "unknown error"}"
                            error = true
                            busy = false
                        }
                    }
                }
            }
        }

        val saveOutput = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("video/x-matroska")
        ) { uri ->
            val source = inputFile
            if (uri != null && source != null) {
                busy = true
                error = false
                status = "Preparing subtitle and encoding both video tracks. Keep the app open…"
                val audioIndex = selectedAudio
                val subtitleIndex = selectedSubtitle
                worker.execute {
                    try {
                        val result = createDualVideoMkv(source, tracks, audioIndex, subtitleIndex)
                        contentResolver.openOutputStream(uri, "w").use { output ->
                            requireNotNull(output) { "Could not open destination file." }
                            FileInputStream(result).use { input -> input.copyTo(output) }
                        }
                        result.delete()
                        runOnUiThread {
                            status = "Success! Saved a validated dual-video MKV with 2 video tracks and no subtitle tracks."
                            busy = false
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            status = "Conversion failed: ${e.message ?: "unknown error"}"
                            error = true
                            busy = false
                        }
                    }
                }
            }
        }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("AnimeDualMux", style = MaterialTheme.typography.headlineLarge)
                    Text("Create one MKV with a Japanese hard-subbed video and a clean video track for dubs.")
                    Button(
                        onClick = { openInput.launch(arrayOf("video/x-matroska", "video/*", "application/octet-stream")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (inputFile == null) "Select MKV" else "Choose another file") }

                    if (displayName.isNotBlank()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(displayName, style = MaterialTheme.typography.titleMedium)
                                Text("${tracks.count { it.type == "video" }} video · ${tracks.count { it.type == "audio" }} audio · ${tracks.count { it.type == "subtitle" }} subtitle tracks")
                            }
                        }
                    }

                    val audioTracks = tracks.filter { it.type == "audio" }
                    val subtitleTracks = tracks.filter { it.type == "subtitle" }
                    Text("Track selection", style = MaterialTheme.typography.titleLarge)
                    Text("Choose the exact tracks manually. ASS/SSA subtitles are supported.")
                    TrackPicker("Japanese audio", audioTracks, selectedAudio) { selectedAudio = it }
                    TrackPicker("English subtitles to burn in", subtitleTracks, selectedSubtitle) { selectedSubtitle = it }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Output plan", style = MaterialTheme.typography.titleMedium)
                            Text("Video 1: first source video with the selected subtitles burned in.")
                            Text("Video 2: clean copy of the first source video.")
                            Text("Audio: selected Japanese track first, then every other audio track. Subtitle streams are omitted.")
                            Text("Important: MKV stores audio and video as separate tracks; it cannot reliably bind Japanese audio only to Video 1 and dubbed audio only to Video 2. Players may allow any audio track with either video.")
                        }
                    }

                    Button(
                        onClick = {
                            val source = inputFile
                            if (source == null) {
                                status = "Select an input file first."
                                error = true
                            } else if (selectedAudio == null) {
                                status = "No audio track was detected. Choose a valid MKV."
                                error = true
                            } else if (selectedSubtitle == null) {
                                status = "Choose a text-based English subtitle track to burn in."
                                error = true
                            } else {
                                saveOutput.launch("AnimeDualMux-output.mkv")
                            }
                        },
                        enabled = !busy && inputFile != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create dual-video MKV") }

                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(status)
                        }
                    } else {
                        Text(status, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    Text("Large files need free storage for temporary copies and encoding. Keep the device plugged in during long conversions.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun probe(file: File): List<MediaTrack> {
        val session = FFprobeKit.executeWithArguments(arrayOf(
            "-v", "error", "-show_streams", "-of", "json", file.absolutePath
        ))
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IllegalStateException("FFprobe could not read this file: ${session.output.take(300)}")
        }
        val streams = JSONObject(session.output).optJSONArray("streams")
            ?: throw IllegalStateException("No media streams found.")
        val result = mutableListOf<MediaTrack>()
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            val type = s.optString("codec_type")
            if (type !in setOf("video", "audio", "subtitle")) continue
            val tags = s.optJSONObject("tags") ?: JSONObject()
            val disposition = s.optJSONObject("disposition") ?: JSONObject()
            result += MediaTrack(
                index = s.optInt("index", i),
                type = type,
                codec = s.optString("codec_name", "unknown"),
                language = tags.optString("language", "und"),
                title = tags.optString("title", ""),
                isDefault = disposition.optInt("default", 0) == 1,
                width = s.optInt("width", 0),
                height = s.optInt("height", 0)
            )
        }
        if (result.none { it.type == "video" }) throw IllegalStateException("No video stream was found.")
        return result
    }

    private fun createDualVideoMkv(
        input: File,
        tracks: List<MediaTrack>,
        japaneseAudioIndex: Int?,
        subtitleIndex: Int?
    ): File {
        val audioTracks = tracks.filter { it.type == "audio" }
        val japanese = audioTracks.firstOrNull { it.index == japaneseAudioIndex }
            ?: throw IllegalStateException("Select a Japanese audio track.")
        val subtitle = tracks.firstOrNull { it.type == "subtitle" && it.index == subtitleIndex }
            ?: throw IllegalStateException("Select a subtitle track.")
        val textCodecs = setOf("subrip", "ass", "ssa", "webvtt", "mov_text", "text")
        if (subtitle.codec.lowercase(Locale.ROOT) !in textCodecs) {
            throw IllegalStateException("The selected subtitle is image-based (${subtitle.codec}). Choose a text subtitle; OCR is not included yet.")
        }
        val outputDir = File(cacheDir, "exports").apply { mkdirs() }
        val subtitleExtension = when (subtitle.codec.lowercase(Locale.ROOT)) {
            "ssa" -> "ssa"
            "subrip" -> "srt"
            else -> "ass"
        }
        val subtitleFile = File(outputDir, "burn_${System.currentTimeMillis()}.$subtitleExtension")
        val outputFile = File(outputDir, "dual_${System.currentTimeMillis()}.mkv")
        val extract = FFmpegKit.executeWithArguments(arrayOf(
            "-y", "-i", input.absolutePath, "-map", "0:${subtitle.index}", "-c:s", "copy", subtitleFile.absolutePath
        ))
        if (!ReturnCode.isSuccess(extract.returnCode) || !subtitleFile.exists() || subtitleFile.length() == 0L) {
            throw IllegalStateException("Could not export the selected subtitle track: ${extract.output.takeLast(500)}")
        }

        val subtitlePath = subtitleFile.absolutePath
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
        val baseArgs = mutableListOf(
            "-y", "-i", input.absolutePath,
            "-filter_complex", "[0:v:0]split=2[hardbase][clean];[hardbase]subtitles=filename='$subtitlePath'[hard]",
            "-map", "[hard]", "-map", "[clean]"
        )
        // Matroska audio/video tracks are independent. Preserve all audio, Japanese first.
        val orderedAudio = listOf(japanese) + audioTracks.filter { it.index != japanese.index }
        orderedAudio.forEach { baseArgs.addAll(listOf("-map", "0:${it.index}")) }
        baseArgs.addAll(listOf(
            "-map_metadata", "0", "-map_chapters", "0",
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "23",
            "-c:a", "copy", "-sn", "-dn",
            "-max_muxing_queue_size", "2048",
            "-metadata:s:v:0", "title=Japanese Hard-Subbed",
            "-metadata:s:v:1", "title=Clean Video",
            "-disposition:v:0", "default", "-disposition:v:1", "0",
            "-disposition:a:0", "default",
            outputFile.absolutePath
        ))
        // Software encoding is more compatible than running two hardware encoders on a phone.
        val session = FFmpegKit.executeWithArguments(baseArgs.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            outputFile.delete()
            subtitleFile.delete()
            throw IllegalStateException(
                "FFmpeg could not create the MKV. The selected subtitle must be text-based and the build must include the subtitles/libass filter. Details: ${session.output.takeLast(1800)}"
            )
        }
        val validation = FFprobeKit.executeWithArguments(arrayOf(
            "-v", "error", "-show_streams", "-of", "json", outputFile.absolutePath
        ))
        if (!ReturnCode.isSuccess(validation.returnCode)) {
            outputFile.delete()
            throw IllegalStateException("Output validation failed.")
        }
        val streams = JSONObject(validation.output).optJSONArray("streams") ?: throw IllegalStateException("Invalid output.")
        val videoCount = (0 until streams.length()).count { streams.getJSONObject(it).optString("codec_type") == "video" }
        val subtitleCount = (0 until streams.length()).count { streams.getJSONObject(it).optString("codec_type") == "subtitle" }
        val audioCount = (0 until streams.length()).count { streams.getJSONObject(it).optString("codec_type") == "audio" }
        subtitleFile.delete()
        if (videoCount != 2 || subtitleCount != 0 || audioCount != audioTracks.size) {
            outputFile.delete()
            throw IllegalStateException("Validation mismatch: videos=$videoCount, audio=$audioCount, subtitles=$subtitleCount.")
        }
        return outputFile
    }
}

@Composable
private fun TrackPicker(
    title: String,
    tracks: List<MediaTrack>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = tracks.firstOrNull { it.index == selectedIndex }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { expanded = true }, enabled = tracks.isNotEmpty()) {
                Text(selected?.label ?: if (tracks.isEmpty()) "No tracks found" else "Choose track")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                tracks.forEach { track ->
                    DropdownMenuItem(
                        text = { Text(track.label) },
                        onClick = { onSelect(track.index); expanded = false }
                    )
                }
            }
        }
    }
}
