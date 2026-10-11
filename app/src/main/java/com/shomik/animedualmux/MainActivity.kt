package com.shomik.animedualmux

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private data class SubtitleTrack(
    val index: Int,
    val codec: String,
    val language: String,
    val title: String
) {
    val extension: String
        get() = when (codec.lowercase(Locale.ROOT)) {
            "ass" -> "ass"
            "ssa" -> "ssa"
            "subrip" -> "srt"
            "webvtt" -> "vtt"
            "hdmv_pgs_subtitle" -> "sup"
            "dvd_subtitle" -> "sub"
            else -> "mks"
        }

    val label: String
        get() = "Track #$index · ${language.uppercase(Locale.ROOT)} · " +
            (title.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + codec
}

private data class ScannedVideo(
    val file: File,
    val name: String,
    val tracks: List<SubtitleTrack>
)

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ExtractorScreen() }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Composable
    private fun ExtractorScreen() {
        var videos by remember { mutableStateOf<List<ScannedVideo>>(emptyList()) }
        val selected = remember { mutableStateMapOf<String, Boolean>() }
        var extractAll by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableStateOf(0f) }
        var status by remember { mutableStateOf("Choose one MKV or select multiple MKV files.") }
        var isError by remember { mutableStateOf(false) }
        var pendingSingle by remember { mutableStateOf<Pair<ScannedVideo, SubtitleTrack>?>(null) }

        val singlePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) scanUris(listOf(uri), selected) { result, message ->
                videos = result
                status = message
                isError = result.isEmpty()
            }
        }

        val bulkPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) scanUris(uris, selected) { result, message ->
                videos = result
                status = message
                isError = result.isEmpty()
            }
        }

        val saveSingle = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val pair = pendingSingle
            if (uri != null && pair != null) {
                busy = true
                isError = false
                progress = 0.1f
                worker.execute {
                    try {
                        val output = extract(pair.first, pair.second)
                        contentResolver.openOutputStream(uri).use { stream ->
                            requireNotNull(stream) { "Could not open destination." }
                            FileInputStream(output).use { it.copyTo(stream) }
                        }
                        output.delete()
                        runOnUiThread {
                            status = "Export complete: ${pair.second.label}"
                            progress = 1f
                            busy = false
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            status = "Export failed: ${e.message ?: "unknown error"}"
                            isError = true
                            busy = false
                        }
                    }
                }
            }
        }

        val saveBulk = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            if (uri != null) {
                val snapshot = videos
                val chosen = snapshot.flatMap { video ->
                    video.tracks.filter { extractAll || selected[key(video, it)] == true }
                        .map { video to it }
                }
                if (chosen.isEmpty()) {
                    status = "Select at least one subtitle track."
                    isError = true
                } else {
                    busy = true
                    isError = false
                    progress = 0f
                    worker.execute {
                        try {
                            val zipFile = File(cacheDir, "subtitle_export_${System.currentTimeMillis()}.zip")
                            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                                chosen.forEachIndexed { index, pair ->
                                    runOnUiThread {
                                        progress = index.toFloat() / chosen.size
                                        status = "Extracting ${index + 1} of ${chosen.size}: ${pair.first.name}"
                                    }
                                    val output = extract(pair.first, pair.second)
                                    val base = pair.first.name.substringBeforeLast('.', pair.first.name)
                                        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                    val title = pair.second.title.takeIf { it.isNotBlank() }
                                        ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                    val entry = buildString {
                                        append(base).append("/")
                                        append("track_").append(pair.second.index).append("_")
                                        if (title != null) append(title).append("_")
                                        append(pair.second.language).append(".").append(pair.second.extension)
                                    }
                                    zip.putNextEntry(ZipEntry(entry))
                                    FileInputStream(output).use { it.copyTo(zip) }
                                    zip.closeEntry()
                                    output.delete()
                                }
                            }
                            contentResolver.openOutputStream(uri).use { stream ->
                                requireNotNull(stream) { "Could not open ZIP destination." }
                                FileInputStream(zipFile).use { it.copyTo(stream) }
                            }
                            zipFile.delete()
                            runOnUiThread {
                                status = "Bulk export complete: ${chosen.size} subtitle track(s) saved."
                                progress = 1f
                                busy = false
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                status = "Bulk export failed: ${e.message ?: "unknown error"}"
                                isError = true
                                busy = false
                            }
                        }
                    }
                }
            }
        }

        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("MKV Subtitle Extractor", style = MaterialTheme.typography.headlineLarge)
                    Text("Extract subtitle tracks from one or many MKV files. Processing stays on your device; videos are not re-encoded.")

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { singlePicker.launch(arrayOf("video/*", "application/octet-stream")) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("Select one MKV") }
                        OutlinedButton(
                            onClick = { bulkPicker.launch(arrayOf("video/*", "application/octet-stream")) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("Bulk select") }
                    }

                    if (videos.isNotEmpty()) {
                        Text(
                            "${videos.size} file(s) · ${videos.sumOf { it.tracks.size }} subtitle track(s)",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = extractAll, onClick = { extractAll = true })
                                    Text("Extract all tracks")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = !extractAll, onClick = { extractAll = false })
                                    Text("Select tracks manually")
                                }
                            }
                        }

                        videos.forEach { video ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(video.name, style = MaterialTheme.typography.titleMedium)
                                    if (video.tracks.isEmpty()) Text("No subtitle tracks found.")
                                    video.tracks.forEach { track ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = selected[key(video, track)] == true,
                                                onCheckedChange = { selected[key(video, track)] = it },
                                                enabled = !extractAll && !busy
                                            )
                                            Text(track.label, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        val chosenCount = videos.sumOf { video ->
                            video.tracks.count { extractAll || selected[key(video, it)] == true }
                        }
                        Button(
                            onClick = {
                                val video = videos.singleOrNull()
                                val tracks = video?.tracks?.filter {
                                    extractAll || selected[key(video, it)] == true
                                }.orEmpty()
                                if (video != null && tracks.size == 1) {
                                    pendingSingle = video to tracks.first()
                                    saveSingle.launch(
                                        video.name.substringBeforeLast('.', video.name) +
                                            "_track_${tracks.first().index}.${tracks.first().extension}"
                                    )
                                } else if (chosenCount > 0) {
                                    saveBulk.launch("subtitles_${videos.size}_files.zip")
                                } else {
                                    status = "No subtitle tracks selected."
                                    isError = true
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (videos.size == 1) "Export selected subtitle(s)" else "Export bulk ZIP")
                        }
                    }

                    if (busy) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        status,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "ASS/SSA styling is preserved when copied directly. PGS can be exported as SUP; bitmap subtitles are not OCR-converted to text.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    private fun key(video: ScannedVideo, track: SubtitleTrack): String =
        video.file.absolutePath + ":" + track.index

    private fun scanUris(
        uris: List<Uri>,
        selected: SnapshotStateMap<String, Boolean>,
        done: (List<ScannedVideo>, String) -> Unit
    ) {
        done(emptyList(), "Scanning ${uris.size} file(s)…")
        worker.execute {
            val scanned = mutableListOf<ScannedVideo>()
            val failures = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                var localFile: File? = null
                try {
                    val name = contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "video_${index + 1}.mkv"

                    localFile = File(cacheDir, "subtitle_input_${System.currentTimeMillis()}_${index}.mkv")
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not read the selected file." }
                        localFile.outputStream().buffered().use { output -> input.copyTo(output) }
                    }

                    val probe = FFprobeKit.executeWithArguments(
                        arrayOf("-v", "error", "-show_streams", "-of", "json", localFile.absolutePath)
                    )
                    check(ReturnCode.isSuccess(probe.returnCode)) {
                        "FFprobe could not read $name: ${probe.output.take(160)}"
                    }

                    val streams = JSONObject(probe.output).optJSONArray("streams")
                    val tracks = mutableListOf<SubtitleTrack>()
                    if (streams != null) {
                        for (i in 0 until streams.length()) {
                            val stream = streams.getJSONObject(i)
                            if (stream.optString("codec_type") != "subtitle") continue
                            val tags = stream.optJSONObject("tags") ?: JSONObject()
                            tracks += SubtitleTrack(
                                index = stream.optInt("index", i),
                                codec = stream.optString("codec_name", "unknown"),
                                language = tags.optString("language", "und"),
                                title = tags.optString("title", "")
                            )
                        }
                    }
                    scanned += ScannedVideo(localFile, name, tracks)
                } catch (e: Exception) {
                    localFile?.delete()
                    failures += e.message ?: "Could not scan file"
                }
            }

            runOnUiThread {
                selected.clear()
                scanned.forEach { video ->
                    video.tracks.forEach { selected[key(video, it)] = true }
                }
                done(
                    scanned,
                    if (failures.isEmpty()) "Scan complete. Review tracks, then export."
                    else "Scanned ${scanned.size} file(s); ${failures.size} failed: ${failures.take(2).joinToString()}"
                )
            }
        }
    }

    private fun extract(video: ScannedVideo, track: SubtitleTrack): File {
        val output = File(
            cacheDir,
            "subtitle_track_${track.index}_${System.currentTimeMillis()}.${track.extension}"
        )
        val session = FFmpegKit.executeWithArguments(
            arrayOf(
                "-y", "-v", "error", "-i", video.file.absolutePath,
                "-map", "0:${track.index}", "-c:s", "copy", output.absolutePath
            )
        )
        if (!ReturnCode.isSuccess(session.returnCode) || !output.exists() || output.length() == 0L) {
            output.delete()
            error(
                "Track #${track.index} (${track.codec}) failed: " +
                    (session.failStackTrace ?: session.output.take(200))
            )
        }
        return output
    }
}
