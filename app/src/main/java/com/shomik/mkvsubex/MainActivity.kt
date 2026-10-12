package com.shomik.mkvsubex

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    val streamIndex: Int,
    val codec: String,
    val language: String,
    val title: String,
    val codecPrivate: String = ""
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
    val uri: Uri,
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
        var convertAssToVtt by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var scanning by remember { mutableStateOf(false) }
        var scanProgress by remember { mutableStateOf(0f) }
        var progress by remember { mutableStateOf(0f) }
        var status by remember { mutableStateOf("Choose one MKV or select multiple MKV files.") }
        var isError by remember { mutableStateOf(false) }
        var pendingSingle by remember { mutableStateOf<Pair<ScannedVideo, SubtitleTrack>?>(null) }

        val singlePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) scanUris(listOf(uri), selected, onProgress = { completed, total, name ->
                scanning = true
                scanProgress = completed.toFloat() / total.coerceAtLeast(1)
                status = "Scanning $completed of $total: $name"
            }) { result, message ->
                scanning = false
                scanProgress = 1f
                videos = result
                status = message
                isError = result.isEmpty()
            }
        }

        val bulkPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) scanUris(uris, selected, onProgress = { completed, total, name ->
                scanning = true
                scanProgress = completed.toFloat() / total.coerceAtLeast(1)
                status = "Scanning $completed of $total: $name"
            }) { result, message ->
                scanning = false
                scanProgress = 1f
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
                        val output = extractMultiple(pair.first, listOf(pair.second), convertAssToVtt).getValue(pair.second)
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
                        var zipFile: File? = null
                        try {
                            zipFile = File(cacheDir, "subtitle_export_${System.currentTimeMillis()}.zip")
                            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                                var completed = 0
                                val groups = chosen.groupBy { it.first.uri.toString() }
                                groups.values.forEach { group ->
                                    val video = group.first().first
                                    runOnUiThread {
                                        progress = completed.toFloat() / chosen.size
                                        status = "Extracting ${group.size} track(s): ${video.name}"
                                    }
                                    val outputs = extractMultiple(video, group.map { it.second }, convertAssToVtt)
                                    try {
                                        group.forEach { pair ->
                                            val output = outputs.getValue(pair.second)
                                            val base = pair.first.name.substringBeforeLast('.', pair.first.name)
                                                .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                            val title = pair.second.title.takeIf { it.isNotBlank() }
                                                ?.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                            val entry = buildString {
                                                append(base).append("/")
                                                append("track_").append(pair.second.index).append("_")
                                                if (title != null) append(title).append("_")
                                                append(pair.second.language).append(".").append(exportExtension(pair.second, convertAssToVtt))
                                            }
                                            zip.putNextEntry(ZipEntry(entry))
                                            FileInputStream(output).use { it.copyTo(zip) }
                                            zip.closeEntry()
                                            completed++
                                            runOnUiThread { progress = completed.toFloat() / chosen.size }
                                        }
                                    } finally {
                                        outputs.values.forEach { it.delete() }
                                    }
                                }
                            }
                            contentResolver.openOutputStream(uri).use { stream ->
                                requireNotNull(stream) { "Could not open ZIP destination." }
                                FileInputStream(zipFile).use { it.copyTo(stream) }
                            }
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
                        } finally {
                            zipFile?.delete()
                        }
                    }
                }
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFFB8A6FF),
                onPrimary = Color(0xFF17112E),
                secondary = Color(0xFF73DDF5),
                background = Color(0xFF080B12),
                surface = Color(0xFF111724),
                surfaceVariant = Color(0xFF1B2434),
                onSurface = Color(0xFFF3F5FC),
                onSurfaceVariant = Color(0xFFAAB6CC),
                error = Color(0xFFFF8196)
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xFF171F32),
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Image(painter = painterResource(id = R.drawable.mkv_subex_icon), contentDescription = "MKV SubEx app icon", modifier = Modifier.size(44.dp).padding(2.dp), contentScale = ContentScale.Fit)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("MKV SubEx", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("SUBTITLE WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        }
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFF132C2C)) {
                            Text("ON-DEVICE", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), color = Color(0xFF83E6CB), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    Column(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Your subtitles.\nReady to export.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, lineHeight = MaterialTheme.typography.headlineLarge.lineHeight, color = MaterialTheme.colorScheme.onSurface)
                        Text("Extract subtitle tracks from MKV files without re-encoding the video.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF131D30))
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("START A SESSION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                    Text("Choose your MKV files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Text("↗", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
                            }
                            Button(
                                onClick = { singlePicker.launch(arrayOf("video/*", "application/octet-stream")) },
                                enabled = !busy && !scanning,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("＋   Select a single file", Modifier.padding(vertical = 5.dp), fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { bulkPicker.launch(arrayOf("video/*", "application/octet-stream")) },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("Select multiple files", Modifier.padding(vertical = 4.dp)) }
                            Text("Files stay on your device. No upload, no video re-encoding.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (videos.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("YOUR SESSION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                Text("Files & subtitle tracks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${videos.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("FILES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${videos.sumOf { it.tracks.size }}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text("Subtitle tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (extractAll) "ALL" else "CUSTOM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                                    Text("Selection mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Card(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("EXPORT SETTINGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("Convert ASS / SSA to VTT", fontWeight = FontWeight.SemiBold)
                                        Text("Plain WebVTT with formatting tags removed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(checked = convertAssToVtt, onCheckedChange = { convertAssToVtt = it }, enabled = !busy)
                                }
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = extractAll, onClick = { extractAll = true }, enabled = !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text("Export every track", fontWeight = if (extractAll) FontWeight.SemiBold else FontWeight.Normal)
                                        Text("Fastest way to export the whole file", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = !extractAll, onClick = { extractAll = false }, enabled = !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text("Choose tracks manually", fontWeight = if (!extractAll) FontWeight.SemiBold else FontWeight.Normal)
                                        Text("Pick only the languages you need", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        videos.forEachIndexed { videoIndex, video ->
                            Card(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                            Text("MKV", Modifier.padding(horizontal = 10.dp, vertical = 9.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(video.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text("${video.tracks.size} subtitle track(s)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (video.tracks.isEmpty()) {
                                        Text("No subtitle tracks found in this file.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    video.tracks.forEach { track ->
                                        Surface(
                                            Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (selected[key(video, track)] == true && !extractAll) Color(0xFF202A40) else Color(0xFF151C29)
                                        ) {
                                            Row(
                                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Checkbox(
                                                    checked = selected[key(video, track)] == true,
                                                    onCheckedChange = { selected[key(video, track)] = it },
                                                    enabled = !extractAll && !busy
                                                )
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Text(track.title.takeIf { it.isNotBlank() } ?: track.language.uppercase(Locale.ROOT), fontWeight = FontWeight.SemiBold)
                                                    Text("Track ${track.index}  ·  ${track.codec.uppercase(Locale.ROOT)}  ·  ${track.language.uppercase(Locale.ROOT)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (convertAssToVtt && track.codec.lowercase(Locale.ROOT) in setOf("ass", "ssa")) {
                                                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF183638)) {
                                                        Text("VTT", Modifier.padding(horizontal = 7.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF80E5E1), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
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
                                val tracks = video?.tracks?.filter { extractAll || selected[key(video, it)] == true }.orEmpty()
                                if (video != null && tracks.size == 1) {
                                    pendingSingle = video to tracks.first()
                                    saveSingle.launch(
                                        video.name.substringBeforeLast('.', video.name) + "_track_${tracks.first().index}." +
                                            exportExtension(tracks.first(), convertAssToVtt)
                                    )
                                } else if (chosenCount > 0) {
                                    saveBulk.launch("subtitles_${videos.size}_files.zip")
                                } else {
                                    status = "No subtitle tracks selected."
                                    isError = true
                                }
                            },
                            enabled = !busy && chosenCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Export ${chosenCount} subtitle track(s)", Modifier.padding(vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                    }

                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = if (isError) Color(0xFF321923) else Color(0xFF121C2B))
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isError) "!" else if (busy) "↻" else "✓", color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(if (isError) "Something needs attention" else if (busy) "Export in progress" else "Activity status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (scanning) {
                                LinearProgressIndicator(progress = { scanProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text("${(scanProgress.coerceIn(0f, 1f) * 100).toInt()}% scanned", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                            if (busy) {
                                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}% complete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                    Text("ASS/SSA can be converted to plain VTT. PGS tracks export as SUP; image-based subtitles are not OCR-converted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("MKV SubEx  •  Private by design", Modifier.fillMaxWidth().padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF69778E))
                }
            }
        }
    }

    private fun key(video: ScannedVideo, track: SubtitleTrack): String =
        video.uri.toString() + ":" + track.index

    private fun scanUris(
        uris: List<Uri>,
        selected: SnapshotStateMap<String, Boolean>,
        onProgress: (Int, Int, String) -> Unit,
        done: (List<ScannedVideo>, String) -> Unit
    ) {
        worker.execute {
            val scanned = mutableListOf<ScannedVideo>()
            val failures = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                var name = "video_${index + 1}.mkv"
                try {
                    name = contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: name
                    runOnUiThread { onProgress(index, uris.size, name) }

                    // Read only EBML element headers and the Tracks element directly from
                    // the provider's seekable descriptor. No video-sized cache copy is made.
                    val tracks = contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                        requireNotNull(descriptor) { "Could not open selected file." }
                        FileInputStream(descriptor.fileDescriptor).use { input ->
                            readMatroskaSubtitleTracks(input.channel)
                        }
                    }
                    scanned += ScannedVideo(uri, name, tracks)
                } catch (e: Exception) {
                    failures += "$name: ${e.message ?: "Could not scan file"}"
                }
                runOnUiThread { onProgress(index + 1, uris.size, name) }
            }

            runOnUiThread {
                selected.clear()
                scanned.forEach { video ->
                    video.tracks.forEach { selected[key(video, it)] = true }
                }
                done(
                    scanned,
                    if (failures.isEmpty()) "Scan complete. Read MKV track metadata without copying video files."
                    else "Scanned ${scanned.size} file(s); ${failures.size} failed: ${failures.take(2).joinToString()}"
                )
            }
        }
    }

    private data class EbmlElement(val id: Long, val size: Long, val dataStart: Long) {
        val end: Long get() = if (size < 0) -1 else dataStart + size
    }

    /**
     * Minimal seekable EBML reader for Matroska track metadata.
     * It skips payloads by seeking over element sizes, never reading video clusters.
     */
    private fun readMatroskaSubtitleTracks(channel: java.nio.channels.FileChannel): List<SubtitleTrack> {
        val fileSize = channel.size()
        channel.position(0)
        var segment: EbmlElement? = null
        while (channel.position() < fileSize) {
            val element = readEbmlElement(channel, fileSize) ?: break
            if (element.id == 0x18538067L) {
                segment = element
                break
            }
            if (element.end < 0) break
            channel.position(element.end)
        }
        val segmentElement = segment ?: error("This file does not contain a Matroska Segment.")
        val segmentEnd = if (segmentElement.size < 0) fileSize else minOf(segmentElement.end, fileSize)
        var position = segmentElement.dataStart
        var tracksElement: EbmlElement? = null

        while (position < segmentEnd) {
            channel.position(position)
            val element = readEbmlElement(channel, segmentEnd) ?: break
            if (element.id == 0x1654AE6BL) {
                tracksElement = element
                break
            }
            if (element.size < 0) {
                // An unknown-sized top-level element cannot safely be skipped.
                break
            }
            if (element.end <= position) break
            position = element.end
        }

        val tracks = tracksElement ?: error("Could not locate the MKV Tracks metadata.")
        val tracksEnd = minOf(tracks.end, segmentEnd)
        val result = mutableListOf<SubtitleTrack>()
        var subtitleStreamIndex = 0
        position = tracks.dataStart
        while (position < tracksEnd) {
            channel.position(position)
            val element = readEbmlElement(channel, tracksEnd) ?: break
            if (element.id == 0xAEL) {
                val entryEnd = if (element.size < 0) tracksEnd else minOf(element.end, tracksEnd)
                var childPosition = element.dataStart
                var trackNumber = 0
                var trackType = 0
                var codec = "unknown"
                var language = "und"
                var title = ""
                var codecPrivate = ""
                while (childPosition < entryEnd) {
                    channel.position(childPosition)
                    val child = readEbmlElement(channel, entryEnd) ?: break
                    if (child.size < 0) break
                    when (child.id) {
                        0xD7L -> trackNumber = readEbmlUnsigned(channel, child)
                        0x83L -> trackType = readEbmlUnsigned(channel, child)
                        0x86L -> codec = readEbmlString(channel, child)
                        0x536EL -> title = readEbmlString(channel, child)
                        0x63A2L -> codecPrivate = readEbmlString(channel, child, maxBytes = 1_000_000)
                        0x22B59CL, 0x22B59DL -> language = readEbmlString(channel, child)
                    }
                    if (child.end <= childPosition) break
                    childPosition = child.end
                }
                if (trackType == 0x11) {
                    result += SubtitleTrack(
                        index = trackNumber,
                        streamIndex = subtitleStreamIndex++,
                        codec = when (codec) {
                            "S_TEXT/ASS" -> "ass"
                            "S_TEXT/SSA" -> "ssa"
                            "S_TEXT/UTF8" -> "subrip"
                            "S_TEXT/WEBVTT" -> "webvtt"
                            "S_HDMV/PGS" -> "hdmv_pgs_subtitle"
                            "S_VOBSUB" -> "dvd_subtitle"
                            else -> codec.removePrefix("S_TEXT/").lowercase(Locale.ROOT)
                        },
                        language = language,
                        title = title,
                        codecPrivate = codecPrivate
                    )
                }
            }
            if (element.size < 0 || element.end <= position) break
            position = element.end
        }
        return result
    }

    private fun readEbmlElement(
        channel: java.nio.channels.FileChannel,
        limit: Long
    ): EbmlElement? {
        val start = channel.position()
        if (start >= limit) return null
        val idFirst = readOneByte(channel) ?: return null
        val idLength = ebmlVintLength(idFirst)
        var id = idFirst.toLong()
        repeat(idLength - 1) {
            val next = readOneByte(channel) ?: error("Unexpected end of EBML element ID.")
            id = (id shl 8) or next.toLong()
        }
        val sizeFirst = readOneByte(channel) ?: error("Missing EBML element size.")
        val sizeLength = ebmlVintLength(sizeFirst)
        var size = (sizeFirst and (0xFF ushr sizeLength)).toLong()
        repeat(sizeLength - 1) {
            val next = readOneByte(channel) ?: error("Unexpected end of EBML element size.")
            size = (size shl 8) or next.toLong()
        }
        val unknownMarker = (1L shl (7 * sizeLength)) - 1
        if (size == unknownMarker) size = -1
        val dataStart = channel.position()
        if (dataStart > limit || (size >= 0 && size > limit - dataStart)) {
            error("Invalid or truncated EBML element.")
        }
        return EbmlElement(id, size, dataStart)
    }

    private fun ebmlVintLength(firstByte: Int): Int {
        if (firstByte == 0) error("Invalid EBML variable-length integer.")
        for (length in 1..8) {
            if ((firstByte and (0x80 ushr (length - 1))) != 0) return length
        }
        error("Invalid EBML variable-length integer.")
    }

    private fun readOneByte(channel: java.nio.channels.FileChannel): Int? {
        val buffer = java.nio.ByteBuffer.allocate(1)
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) return null
        }
        return buffer.array()[0].toInt() and 0xFF
    }

    private fun readEbmlUnsigned(
        channel: java.nio.channels.FileChannel,
        element: EbmlElement
    ): Int {
        if (element.size !in 1..4) return 0
        channel.position(element.dataStart)
        var value = 0
        repeat(element.size.toInt()) {
            value = (value shl 8) or (readOneByte(channel) ?: 0)
        }
        return value
    }

    private fun readEbmlString(
        channel: java.nio.channels.FileChannel,
        element: EbmlElement,
        maxBytes: Int = 4096
    ): String {
        if (element.size <= 0 || element.size > maxBytes) return ""
        channel.position(element.dataStart)
        val buffer = java.nio.ByteBuffer.allocate(element.size.toInt())
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        return String(buffer.array(), Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun exportExtension(track: SubtitleTrack, convertAssToVtt: Boolean): String =
        if (convertAssToVtt && track.codec.lowercase(Locale.ROOT) in setOf("ass", "ssa")) {
            "vtt"
        } else {
            track.extension
        }

    private fun convertAssToPlainVtt(source: File, destination: File) {
        val lines = source.readText(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .lines()
        var inEvents = false
        var fields = emptyList<String>()
        data class VttCue(val startMillis: Long, val order: Int, val text: String)
        val cues = mutableListOf<VttCue>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue

            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                fields = trimmed.substringAfter(":").split(",").map {
                    it.trim().lowercase(Locale.ROOT)
                }
                continue
            }
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true) || fields.isEmpty()) continue

            val values = trimmed.substringAfter(":").trimStart().split(",", limit = fields.size)
            if (values.size != fields.size) continue
            val startIndex = fields.indexOf("start")
            val endIndex = fields.indexOf("end")
            val textIndex = fields.indexOf("text")
            if (startIndex < 0 || endIndex < 0 || textIndex < 0) continue

            val start = assTimeToVtt(values[startIndex].trim())
            val end = assTimeToVtt(values[endIndex].trim())
            val dialogue = stripAssOverrideBlocks(values[textIndex])
                .replace(Regex("""<[^>]*>"""), "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .replace(Regex("""\n[\t ]*\n+"""), "\n")
                .trim()
            if (dialogue.isEmpty()) continue
            cues += VttCue(assTimeToMillis(values[startIndex].trim()), cues.size, "$start --> $end\n$dialogue")
        }

        check(cues.isNotEmpty()) { "No readable ASS/SSA dialogue cues were found." }
        val sortedCues = cues.sortedWith(compareBy<VttCue> { it.startMillis }.thenBy { it.order })
        destination.writeText("WEBVTT\n\n" + sortedCues.joinToString("\n\n") { it.text } + "\n", Charsets.UTF_8)
    }

    private fun stripAssOverrideBlocks(value: String): String {
        val result = StringBuilder(value.length)
        var inOverrideBlock = false
        for (character in value) {
            when (character) {
                '{' -> inOverrideBlock = true
                '}' -> if (inOverrideBlock) inOverrideBlock = false else result.append(character)
                else -> if (!inOverrideBlock) result.append(character)
            }
        }
        return result.toString()
    }

    private fun assTimeToMillis(value: String): Long {
        val match = Regex("""^(\d+):(\d{2}):(\d{2})[.](\d{2})$""").matchEntire(value)
            ?: error("Unrecognized ASS timestamp: $value")
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val centiseconds = match.groupValues[4].toLong()
        return (((hours * 60 + minutes) * 60 + seconds) * 1000) + centiseconds * 10
    }

    private fun assTimeToVtt(value: String): String {
        val match = Regex("""^(\d+):(\d{2}):(\d{2})[.](\d{2})$""").matchEntire(value)
            ?: error("Unrecognized ASS timestamp: $value")
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val centiseconds = match.groupValues[4].toLong()
        return "%02d:%02d:%02d.%03d".format(
            Locale.ROOT, hours, minutes, seconds, centiseconds * 10
        )
    }

    // ---------------------------------------------------------------------
    // Export
    //
    // Text tracks (ASS/SSA/SRT) are pulled straight out of the MKV by walking
    // Cluster -> Block headers and seeking past every video/audio payload.
    // No cache copy of the video, no FFmpeg process. PGS/VobSub/WebVTT still go
    // through FFmpeg, but read the file through its descriptor (no copy) when
    // possible.
    // ---------------------------------------------------------------------

    private class RawCue(val startMs: Long, val durationMs: Long, val data: ByteArray)

    /** Small buffered positional reader so header parsing isn't a syscall per byte. */
    private class MkvBlockReader(private val channel: java.nio.channels.FileChannel) {
        private val buffer = ByteArray(8192)
        private var bufferStart = -1L
        private var bufferLength = 0
        val size: Long = channel.size()

        fun u8(position: Long): Int {
            if (position < 0 || position >= size) return -1
            if (position < bufferStart || position >= bufferStart + bufferLength) {
                val target = java.nio.ByteBuffer.wrap(buffer)
                var read = 0
                while (target.hasRemaining()) {
                    val count = channel.read(target, position + read)
                    if (count <= 0) break
                    read += count
                }
                bufferStart = position
                bufferLength = read
                if (read == 0) return -1
            }
            return buffer[(position - bufferStart).toInt()].toInt() and 0xFF
        }

        fun bytes(position: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            val target = java.nio.ByteBuffer.wrap(out)
            var read = 0
            while (target.hasRemaining()) {
                val count = channel.read(target, position + read)
                if (count <= 0) error("Unexpected end of file.")
                read += count
            }
            return out
        }

        fun unsigned(position: Long, length: Long): Long {
            var value = 0L
            for (i in 0 until length.coerceIn(0L, 8L).toInt()) {
                value = (value shl 8) or u8(position + i).coerceAtLeast(0).toLong()
            }
            return value
        }
    }

    private fun readHeader(reader: MkvBlockReader, position: Long, limit: Long): EbmlElement? {
        if (position >= limit) return null
        val first = reader.u8(position)
        if (first <= 0) return null
        val idLength = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (idLength > 4) return null
        var id = first.toLong()
        for (i in 1 until idLength) {
            val b = reader.u8(position + i)
            if (b < 0) return null
            id = (id shl 8) or b.toLong()
        }
        val sizePosition = position + idLength
        val sizeFirst = reader.u8(sizePosition)
        if (sizeFirst <= 0) return null
        val sizeLength = Integer.numberOfLeadingZeros(sizeFirst) - 24 + 1
        var size = (sizeFirst and (0xFF ushr sizeLength)).toLong()
        for (i in 1 until sizeLength) {
            val b = reader.u8(sizePosition + i)
            if (b < 0) return null
            size = (size shl 8) or b.toLong()
        }
        if (size == (1L shl (7 * sizeLength)) - 1) size = -1
        val dataStart = sizePosition + sizeLength
        // Be lenient with truncated files: clamp instead of failing.
        if (size >= 0 && dataStart + size > limit) size = limit - dataStart
        return EbmlElement(id, size, dataStart)
    }

    private fun assClock(ms: Long): String {
        val cs = (ms + 5) / 10
        return String.format(
            Locale.ROOT, "%d:%02d:%02d.%02d",
            cs / 360000, (cs / 6000) % 60, (cs / 100) % 60, cs % 100
        )
    }

    private fun srtClock(ms: Long): String =
        String.format(
            Locale.ROOT, "%02d:%02d:%02d,%03d",
            ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000
        )

    private fun writeSrtFile(file: File, cues: List<RawCue>) {
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            cues.forEachIndexed { i, cue ->
                val end = cue.startMs + if (cue.durationMs > 0) cue.durationMs else 2000
                w.write("${i + 1}\n${srtClock(cue.startMs)} --> ${srtClock(end)}\n")
                w.write(String(cue.data, Charsets.UTF_8).trim())
                w.write("\n\n")
            }
        }
    }

    private fun writeAssFile(file: File, track: SubtitleTrack, cues: List<RawCue>) {
        val isSsa = track.codec.equals("ssa", ignoreCase = true)
        var header = track.codecPrivate.removePrefix("\uFEFF").replace("\r\n", "\n").trimEnd()
        val eventsAt = header.lastIndexOf("[Events]", ignoreCase = true)
        if (eventsAt < 0) header += "\n\n[Events]"
        if (eventsAt < 0 || !header.substring(eventsAt).contains("Format:", ignoreCase = true)) {
            header += "\nFormat: " + (if (isSsa) "Marked" else "Layer") +
                ", Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        }
        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(header)
            w.write("\n")
            cues.forEach { cue ->
                // Matroska ASS block: ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
                val parts = String(cue.data, Charsets.UTF_8).split(",", limit = 9)
                if (parts.size < 9) return@forEach
                val end = cue.startMs + if (cue.durationMs > 0) cue.durationMs else 2000
                w.write(
                    "Dialogue: ${parts[1]},${assClock(cue.startMs)},${assClock(end)}," +
                        "${parts[2]},${parts[3]},${parts[4]},${parts[5]},${parts[6]},${parts[7]}," +
                        parts[8].trimEnd('\r', '\n') + "\n"
                )
            }
        }
    }

    /** One pass over the MKV, collecting blocks for every requested text track at once. */
    private fun extractTextTracksNative(
        video: ScannedVideo,
        tracks: List<SubtitleTrack>,
        convertAssToVtt: Boolean
    ): Map<SubtitleTrack, File> {
        val wanted = tracks.associateBy { it.index.toLong() }
        val cues = tracks.associateWith { mutableListOf<RawCue>() }
        val level1Ids = setOf(
            0x1F43B675L, 0x1C53BB6BL, 0x1254C367L, 0x1043A770L,
            0x1941A469L, 0x114D9B74L, 0x1549A966L, 0x1654AE6BL
        )

        contentResolver.openFileDescriptor(video.uri, "r").use { descriptor ->
            requireNotNull(descriptor) { "Could not open selected file." }
            FileInputStream(descriptor.fileDescriptor).use { input ->
                val reader = MkvBlockReader(input.channel)
                val fileSize = reader.size

                var position = 0L
                var segment: EbmlElement? = null
                while (position < fileSize) {
                    val e = readHeader(reader, position, fileSize) ?: break
                    if (e.id == 0x18538067L) { segment = e; break }
                    if (e.size < 0) break
                    position = e.end
                }
                val seg = segment ?: error("This file does not contain a Matroska Segment.")
                val segEnd = if (seg.size < 0) fileSize else minOf(seg.end, fileSize)
                var timecodeScale = 1_000_000L

                fun processCluster(cluster: EbmlElement): Long {
                    val clusterEnd = if (cluster.size < 0) segEnd else cluster.end
                    var clusterTs = 0L

                    fun handleBlock(start: Long, size: Long, durationTicks: Long) {
                        val first = reader.u8(start)
                        if (first <= 0) return
                        val trackLen = Integer.numberOfLeadingZeros(first) - 24 + 1
                        var trackNo = (first and (0xFF ushr trackLen)).toLong()
                        for (i in 1 until trackLen) {
                            trackNo = (trackNo shl 8) or reader.u8(start + i).coerceAtLeast(0).toLong()
                        }
                        val track = wanted[trackNo] ?: return   // not ours: payload is skipped untouched
                        val headerLen = trackLen + 3
                        val rel = ((reader.u8(start + trackLen).coerceAtLeast(0) shl 8) or
                            reader.u8(start + trackLen + 1).coerceAtLeast(0)).toShort().toLong()
                        val flags = reader.u8(start + trackLen + 2)
                        if (((flags shr 1) and 3) != 0) {
                            throw UnsupportedOperationException("Laced subtitle block")
                        }
                        val payloadSize = size - headerLen
                        if (payloadSize <= 0 || payloadSize > 4_000_000) return
                        val data = reader.bytes(start + headerLen, payloadSize.toInt())
                        cues.getValue(track) += RawCue(
                            startMs = (clusterTs + rel) * timecodeScale / 1_000_000L,
                            durationMs = durationTicks * timecodeScale / 1_000_000L,
                            data = data
                        )
                    }

                    var p = cluster.dataStart
                    while (p < clusterEnd) {
                        val c = readHeader(reader, p, clusterEnd) ?: return clusterEnd
                        if (cluster.size < 0 && c.id in level1Ids) return p
                        if (c.size < 0) return clusterEnd
                        when (c.id) {
                            0xE7L -> clusterTs = reader.unsigned(c.dataStart, c.size)
                            0xA3L -> handleBlock(c.dataStart, c.size, 0L)
                            0xA0L -> {
                                var blockStart = -1L
                                var blockSize = 0L
                                var duration = 0L
                                var q = c.dataStart
                                while (q < c.end) {
                                    val g = readHeader(reader, q, c.end) ?: break
                                    if (g.size < 0) break
                                    if (g.id == 0xA1L) { blockStart = g.dataStart; blockSize = g.size }
                                    else if (g.id == 0x9BL) duration = reader.unsigned(g.dataStart, g.size)
                                    q = g.end
                                }
                                if (blockStart >= 0) handleBlock(blockStart, blockSize, duration)
                            }
                        }
                        p = c.end
                    }
                    return clusterEnd
                }

                position = seg.dataStart
                while (position < segEnd) {
                    val e = readHeader(reader, position, segEnd) ?: break
                    if (e.id == 0x1F43B675L) {
                        val next = processCluster(e)
                        if (next <= position) break
                        position = next
                        continue
                    }
                    if (e.size < 0) break
                    if (e.id == 0x1549A966L) {
                        var p = e.dataStart
                        while (p < e.end) {
                            val c = readHeader(reader, p, e.end) ?: break
                            if (c.size < 0) break
                            if (c.id == 0x2AD7B1L) {
                                val v = reader.unsigned(c.dataStart, c.size)
                                if (v > 0) timecodeScale = v
                            }
                            p = c.end
                        }
                    }
                    position = e.end
                }
            }
        }

        val created = mutableListOf<File>()
        val outputs = linkedMapOf<SubtitleTrack, File>()
        try {
            tracks.forEach { track ->
                val trackCues = cues.getValue(track)
                check(trackCues.isNotEmpty()) {
                    "Track #${track.index} (${track.codec}) produced an empty file."
                }
                val codec = track.codec.lowercase(Locale.ROOT)
                val converting = convertAssToVtt && codec in setOf("ass", "ssa")
                val stamp = "${track.index}_${System.nanoTime()}"
                val source = File(
                    cacheDir,
                    "subtitle_native_$stamp." + if (codec == "subrip") "srt" else codec
                )
                created += source
                if (codec == "subrip") writeSrtFile(source, trackCues)
                else writeAssFile(source, track, trackCues)

                if (converting) {
                    val output = File(cacheDir, "subtitle_native_out_$stamp.vtt")
                    created += output
                    convertAssToPlainVtt(source, output)
                    source.delete()
                    outputs[track] = output
                } else {
                    outputs[track] = source
                }
            }
            return outputs
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }

    /** Prefer reading the picked file through its descriptor; only copy to cache as a last resort. */
    private fun <T> withFfmpegInput(video: ScannedVideo, block: (String) -> T): T {
        try {
            contentResolver.openFileDescriptor(video.uri, "r").use { descriptor ->
                if (descriptor != null) {
                    val path = "/proc/self/fd/${descriptor.fd}"
                    if (File(path).canRead()) return block(path)
                }
            }
        } catch (_: Exception) {
            // fall through to the copy path
        }
        val local = materializeForExport(video)
        try {
            return block(local.absolutePath)
        } finally {
            local.delete()
        }
    }

    /** FFmpeg path, used for PGS / VobSub / WebVTT or if the native reader hits something odd. */
    private fun extractWithFfmpeg(
        video: ScannedVideo,
        tracks: List<SubtitleTrack>,
        convertAssToVtt: Boolean
    ): Map<SubtitleTrack, File> {
        val intermediates = linkedMapOf<SubtitleTrack, File>()
        val outputs = linkedMapOf<SubtitleTrack, File>()
        val mapArgs = mutableListOf<String>()

        tracks.forEach { track ->
            val converting = convertAssToVtt && track.codec.lowercase(Locale.ROOT) in setOf("ass", "ssa")
            val extension = if (converting) track.codec.lowercase(Locale.ROOT) else exportExtension(track, convertAssToVtt)
            val stamp = "${track.index}_${System.nanoTime()}"
            val intermediate = File(cacheDir, "subtitle_batch_source_$stamp.$extension")
            val output = if (converting) File(cacheDir, "subtitle_batch_output_$stamp.vtt") else intermediate
            intermediates[track] = intermediate
            outputs[track] = output
            mapArgs.addAll(listOf("-map", "0:s:${track.streamIndex}", "-c:s", "copy", intermediate.absolutePath))
        }

        try {
            val session = withFfmpegInput(video) { path ->
                FFmpegKit.executeWithArguments(
                    (listOf("-y", "-v", "error", "-i", path) + mapArgs).toTypedArray()
                )
            }
            if (!ReturnCode.isSuccess(session.returnCode)) {
                error("Extraction failed: " + (session.failStackTrace ?: session.output.take(300)))
            }
            tracks.forEach { track ->
                val intermediate = intermediates.getValue(track)
                val output = outputs.getValue(track)
                check(intermediate.exists() && intermediate.length() > 0L) {
                    "Track #${track.index} (${track.codec}) produced an empty file."
                }
                if (output != intermediate) {
                    convertAssToPlainVtt(intermediate, output)
                    intermediate.delete()
                }
            }
            return outputs
        } catch (e: Exception) {
            intermediates.values.forEach { it.delete() }
            outputs.values.forEach { it.delete() }
            throw e
        }
    }

    private fun extractMultiple(
        video: ScannedVideo,
        tracks: List<SubtitleTrack>,
        convertAssToVtt: Boolean
    ): Map<SubtitleTrack, File> {
        require(tracks.isNotEmpty()) { "No subtitle tracks selected." }
        val nativeCodecs = setOf("ass", "ssa", "subrip")
        val results = linkedMapOf<SubtitleTrack, File>()
        var remaining = tracks

        val textTracks = tracks.filter { it.codec.lowercase(Locale.ROOT) in nativeCodecs }
        if (textTracks.isNotEmpty()) {
            try {
                results += extractTextTracksNative(video, textTracks, convertAssToVtt)
                remaining = tracks.filterNot { it in textTracks }
            } catch (_: Exception) {
                // Unusual file (laced blocks, damaged clusters...): let FFmpeg handle everything.
            }
        }
        if (remaining.isNotEmpty()) {
            try {
                results += extractWithFfmpeg(video, remaining, convertAssToVtt)
            } catch (e: Exception) {
                results.values.forEach { it.delete() }
                throw e
            }
        }
        return tracks.associateWith { results.getValue(it) }
    }

    private fun materializeForExport(video: ScannedVideo): File {
        val local = File(cacheDir, "subtitle_input_export_${System.currentTimeMillis()}.mkv")
        contentResolver.openInputStream(video.uri).use { input ->
            requireNotNull(input) { "Could not read selected video for export." }
            local.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        return local
    }
}
