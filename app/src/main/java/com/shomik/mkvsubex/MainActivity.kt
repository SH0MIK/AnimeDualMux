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

    val nativeSupported: Boolean
        get() = codec.lowercase(Locale.ROOT) in NATIVE_SUBTITLE_CODECS
}

private data class ScannedVideo(
    val uri: Uri,
    val name: String,
    val tracks: List<SubtitleTrack>
)

private val NATIVE_SUBTITLE_CODECS = setOf("ass", "ssa", "subrip", "webvtt")

private val ASS_TIME_REGEX = Regex("""^(\d+):(\d{2}):(\d{2})[.](\d{2})$""")
private val HTML_TAG_REGEX = Regex("""<[^>]*>""")
private val BLANK_LINES_REGEX = Regex("""\n[\t ]*\n+""")

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()

    // Several MKVs are read at the same time during bulk export.
    private val extractPool = Executors.newFixedThreadPool(
        // Subtitle extraction is storage-bound; too many simultaneous MKV scans
        // cause random-I/O contention and memory spikes on phones.
        Runtime.getRuntime().availableProcessors().coerceIn(2, 3)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ExtractorScreen() }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        extractPool.shutdownNow()
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
                    video.tracks.filter { it.nativeSupported && (extractAll || selected[key(video, it)] == true) }
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
                                zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
                                var completed = 0
                                val groups = chosen.groupBy { it.first.uri.toString() }.values.toList()
                                // Extract several files in parallel; ZIP entries are still written in order.
                                val futures = groups.map { group ->
                                    extractPool.submit(java.util.concurrent.Callable<Map<SubtitleTrack, File>> {
                                        extractMultiple(group.first().first, group.map { it.second }, convertAssToVtt)
                                    })
                                }
                                try {
                                    groups.forEachIndexed { groupIndex, group ->
                                        val video = group.first().first
                                        runOnUiThread {
                                            progress = completed.toFloat() / chosen.size
                                            status = "Exporting ${groupIndex + 1} of ${groups.size}: ${video.name}"
                                        }
                                        val outputs = try {
                                            futures[groupIndex].get()
                                        } catch (e: java.util.concurrent.ExecutionException) {
                                            throw (e.cause as? Exception) ?: e
                                        }
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
                                } finally {
                                    futures.forEach { f ->
                                        if (!f.isDone) {
                                            f.cancel(true)
                                        } else {
                                            try { f.get().values.forEach { it.delete() } } catch (_: Exception) {}
                                        }
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
                                        Text("Export every supported track", fontWeight = if (extractAll) FontWeight.SemiBold else FontWeight.Normal)
                                        Text("Fastest way to export all text subtitle tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                    enabled = !extractAll && !busy && track.nativeSupported
                                                )
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    Text(track.title.takeIf { it.isNotBlank() } ?: track.language.uppercase(Locale.ROOT), fontWeight = FontWeight.SemiBold)
                                                    Text("Track ${track.index}  ·  ${track.codec.uppercase(Locale.ROOT)}  ·  ${track.language.uppercase(Locale.ROOT)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                     if (!track.nativeSupported) Text("Not supported by the native extractor", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
                            video.tracks.count { it.nativeSupported && (extractAll || selected[key(video, it)] == true) }
                        }
                        Button(
                            onClick = {
                                val video = videos.singleOrNull()
                                val tracks = video?.tracks?.filter { it.nativeSupported && (extractAll || selected[key(video, it)] == true) }.orEmpty()
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
                    video.tracks.forEach { track -> selected[key(video, track)] = track.nativeSupported }
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

    private val ASS_DRAWING_MODE_REGEX = Regex("""\\p[1-9]\d*""")

    /**
     * Convert directly from Matroska ASS packets to WebVTT.
     * Avoids writing an intermediate ASS file and parsing it back, which is
     * particularly expensive for karaoke-heavy tracks with thousands of events.
     */
    private fun writeAssToPlainVttFile(destination: File, cues: List<RawCue>) {
        data class VttCue(val startMillis: Long, val endMillis: Long, val order: Int, val text: String)
        val converted = ArrayList<VttCue>(cues.size)

        cues.forEachIndexed { order, cue ->
            // Matroska ASS packet: ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
            val packet = String(cue.data, Charsets.UTF_8).trimEnd('\u0000', '\r', '\n')
            val fields = packet.split(",", limit = 9)
            if (fields.size < 9) return@forEachIndexed
            val rawText = fields[8].replace("\u0000", "")
            // Plain WebVTT cannot represent ASS vector drawings; don't leak drawing commands as dialogue.
            if (ASS_DRAWING_MODE_REGEX.containsMatchIn(rawText)) return@forEachIndexed

            val dialogue = stripAssOverrideBlocks(rawText)
                .replace(HTML_TAG_REGEX, "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .replace(BLANK_LINES_REGEX, "\n")
                .replace("\u0000", "")
                .trim()
            if (dialogue.isEmpty()) return@forEachIndexed

            converted += VttCue(
                startMillis = cue.startMs,
                endMillis = cue.startMs + if (cue.durationMs > 0) cue.durationMs else 2000,
                order = order,
                text = dialogue
            )
        }

        check(converted.isNotEmpty()) { "No readable ASS/SSA text cues were found (the track may contain only vector signs or unsupported effects)." }

        // Most Matroska subtitle tracks are already time ordered. Avoid an O(n log n)
        // sort unless the source actually contains out-of-order events.
        val ordered = if (converted.zipWithNext().any { (a, b) -> a.startMillis > b.startMillis }) {
            converted.sortWith(compareBy<VttCue> { it.startMillis }.thenBy { it.order })
            converted
        } else converted

        // Animated ASS signs often repeat the same text in dozens or hundreds of
        // overlapping packets. Collapse only adjacent/overlapping identical text;
        // separate occurrences later in the episode remain separate cues.
        val compacted = ArrayList<VttCue>(ordered.size)
        ordered.forEach { cue ->
            val previous = compacted.lastOrNull()
            if (previous != null && previous.text == cue.text &&
                cue.startMillis <= previous.endMillis + 100 &&
                cue.startMillis >= previous.startMillis
            ) {
                compacted[compacted.lastIndex] = previous.copy(
                    endMillis = maxOf(previous.endMillis, cue.endMillis)
                )
            } else {
                compacted += cue
            }
        }

        destination.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("WEBVTT\n\n")
            compacted.forEach { cue ->
                writer.write("${vttClock(cue.startMillis)} --> ${vttClock(cue.endMillis)}\n")
                writer.write(cue.text)
                writer.write("\n\n")
            }
        }
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
        val match = ASS_TIME_REGEX.matchEntire(value)
            ?: error("Unrecognized ASS timestamp: $value")
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val centiseconds = match.groupValues[4].toLong()
        return (((hours * 60 + minutes) * 60 + seconds) * 1000) + centiseconds * 10
    }

    private fun assTimeToVtt(value: String): String {
        val match = ASS_TIME_REGEX.matchEntire(value)
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
    // Text subtitle tracks are parsed directly from Matroska blocks. The reader
    // seeks over video/audio payloads and never invokes external media tools.
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

    private fun writeWebVttFile(file: File, track: SubtitleTrack, cues: List<RawCue>) {
        val privateHeader = track.codecPrivate
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .trimEnd()
        val header = if (privateHeader.startsWith("WEBVTT")) {
            privateHeader
        } else {
            "WEBVTT" + if (privateHeader.isNotBlank()) "\n\n$privateHeader" else ""
        }

        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(header)
            w.write("\n\n")
            cues.withIndex()
                .sortedWith(compareBy<IndexedValue<RawCue>> { it.value.startMs }.thenBy { it.index })
                .forEach { (_, cue) ->
                    val rawText = String(cue.data, Charsets.UTF_8).trimEnd('\r', '\n')
                    if (rawText.isNotBlank()) {
                        val end = cue.startMs + if (cue.durationMs > 0) cue.durationMs else 2000
                        w.write("${vttClock(cue.startMs)} --> ${vttClock(end)}\n")
                        w.write(restoreVttInlineTimestamps(rawText, cue.startMs))
                        w.write("\n\n")
                    }
                }
        }
    }

    private val vttInlineTimestampRegex = Regex("""<(\d{2,}):([0-5]\d):([0-5]\d)\.(\d{3})>""")

    private fun restoreVttInlineTimestamps(text: String, offsetMs: Long): String =
        vttInlineTimestampRegex.replace(text) { match ->
            val groups = match.groupValues
            val relativeMs = (((groups[1].toLong() * 60 + groups[2].toLong()) * 60 +
                groups[3].toLong()) * 1000) + groups[4].toLong()
            "<${vttClock(relativeMs + offsetMs)}>"
        }

    private fun vttClock(ms: Long): String =
        String.format(
            Locale.ROOT, "%02d:%02d:%02d.%03d",
            ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000
        )

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
        require(tracks.all { it.nativeSupported }) {
            "Native extraction supports ASS, SSA, SRT, and WebVTT tracks only."
        }
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

                // Fast path: jump straight to the subtitle blocks using the Cues index.
                // Tracks that can't be verified as complete fall back to the full scan below.
                val fastDone: Set<SubtitleTrack> = try {
                    extractViaCues(reader, seg.dataStart, segEnd, wanted, cues)
                } catch (_: Exception) {
                    emptySet()
                }
                val scanWanted = wanted.filterValues { it !in fastDone }

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
                        val track = scanWanted[trackNo] ?: return   // not ours: payload is skipped untouched
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
                if (scanWanted.isNotEmpty()) while (position < segEnd) {
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

                if (converting) {
                    val output = File(cacheDir, "subtitle_native_out_$stamp.vtt")
                    created += output
                    writeAssToPlainVttFile(output, trackCues)
                    outputs[track] = output
                } else {
                    val source = File(
                        cacheDir,
                        "subtitle_native_$stamp." + if (codec == "subrip") "srt" else codec
                    )
                    created += source
                    when (codec) {
                        "subrip" -> writeSrtFile(source, trackCues)
                        "webvtt" -> writeWebVttFile(source, track, trackCues)
                        "ass", "ssa" -> writeAssFile(source, track, trackCues)
                        else -> error("Native extraction does not support ${track.codec} tracks yet.")
                    }
                    outputs[track] = source
                }
            }
            return outputs
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }

    private class CueRef(val clusterPos: Long, val relPos: Long)

    /**
     * Reads subtitle blocks directly via the Cues index (a few KB of I/O instead of the
     * whole file). A track is only accepted when it is provably complete: ASS/SSA ReadOrder
     * values must be exactly 0..N-1. Anything else is left for the full scan.
     */
    private fun extractViaCues(
        reader: MkvBlockReader,
        segmentStart: Long,
        segmentEnd: Long,
        wanted: Map<Long, SubtitleTrack>,
        cues: Map<SubtitleTrack, MutableList<RawCue>>
    ): Set<SubtitleTrack> {
        val clusterId = 0x1F43B675L
        val cuesId = 0x1C53BB6BL
        var timecodeScale = 1_000_000L
        var cuesAt = -1L

        // Header region = everything before the first Cluster.
        var position = segmentStart
        while (position < segmentEnd) {
            val e = readHeader(reader, position, segmentEnd) ?: break
            if (e.id == clusterId || e.size < 0) break
            when (e.id) {
                0x1549A966L -> {
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
                0x114D9B74L -> {
                    var p = e.dataStart
                    while (p < e.end) {
                        val seek = readHeader(reader, p, e.end) ?: break
                        if (seek.size < 0) break
                        if (seek.id == 0x4DBBL) {
                            var seekId = -1L
                            var seekPos = -1L
                            var q = seek.dataStart
                            while (q < seek.end) {
                                val f = readHeader(reader, q, seek.end) ?: break
                                if (f.size < 0) break
                                if (f.id == 0x53ABL) seekId = reader.unsigned(f.dataStart, f.size)
                                else if (f.id == 0x53ACL) seekPos = reader.unsigned(f.dataStart, f.size)
                                q = f.end
                            }
                            if (seekId == cuesId && seekPos >= 0) cuesAt = segmentStart + seekPos
                        }
                        p = seek.end
                    }
                }
                cuesId -> cuesAt = position
            }
            if (e.end <= position) break
            position = e.end
        }
        if (cuesAt < 0) return emptySet()

        val cuesEl = readHeader(reader, cuesAt, segmentEnd) ?: return emptySet()
        if (cuesEl.id != cuesId || cuesEl.size < 0) return emptySet()

        val refs = HashMap<Long, MutableList<CueRef>>()
        val broken = HashSet<Long>()
        var cp = cuesEl.dataStart
        while (cp < cuesEl.end) {
            val point = readHeader(reader, cp, cuesEl.end) ?: break
            if (point.size < 0) return emptySet()
            if (point.id == 0xBBL) {
                var q = point.dataStart
                while (q < point.end) {
                    val tp = readHeader(reader, q, point.end) ?: break
                    if (tp.size < 0) return emptySet()
                    if (tp.id == 0xB7L) {
                        var trackNo = -1L
                        var clusterPos = -1L
                        var relPos = -1L
                        var r = tp.dataStart
                        while (r < tp.end) {
                            val f = readHeader(reader, r, tp.end) ?: break
                            if (f.size < 0) return emptySet()
                            when (f.id) {
                                0xF7L -> trackNo = reader.unsigned(f.dataStart, f.size)
                                0xF1L -> clusterPos = reader.unsigned(f.dataStart, f.size)
                                0xF0L -> relPos = reader.unsigned(f.dataStart, f.size)
                            }
                            r = f.end
                        }
                        if (wanted.containsKey(trackNo)) {
                            if (clusterPos < 0 || relPos < 0) broken += trackNo
                            else refs.getOrPut(trackNo) { mutableListOf() } += CueRef(clusterPos, relPos)
                        }
                    }
                    q = tp.end
                }
            }
            cp = point.end
        }

        val timecodeCache = HashMap<Long, Long>()
        val accepted = mutableSetOf<SubtitleTrack>()
        for ((trackNo, track) in wanted) {
            val list = refs[trackNo] ?: continue
            if (trackNo in broken || list.isEmpty()) continue
            val codec = track.codec.lowercase(Locale.ROOT)
            if (codec != "ass" && codec != "ssa") continue   // no completeness proof for SRT

            val sorted = list.sortedWith(compareBy<CueRef>({ it.clusterPos }, { it.relPos }))
            val collected = ArrayList<RawCue>(sorted.size)
            var ok = true
            var last: CueRef? = null
            for (ref in sorted) {
                val prev = last
                if (prev != null && prev.clusterPos == ref.clusterPos && prev.relPos == ref.relPos) continue
                last = ref
                val good = try {
                    readCuedBlock(reader, segmentStart, segmentEnd, ref, trackNo, timecodeScale, timecodeCache, collected)
                } catch (_: Exception) {
                    false
                }
                if (!good) { ok = false; break }
            }
            if (!ok || collected.isEmpty()) continue

            val orders = LongArray(collected.size)
            var complete = true
            for (i in collected.indices) {
                val d = collected[i].data
                val v = String(d, 0, minOf(d.size, 24), Charsets.UTF_8).substringBefore(',').trim().toLongOrNull()
                if (v == null) { complete = false; break }
                orders[i] = v
            }
            if (complete) {
                orders.sort()
                for (i in orders.indices) {
                    if (orders[i] != i.toLong()) { complete = false; break }
                }
            }
            if (!complete) continue

            cues.getValue(track).addAll(collected)
            accepted += track
        }
        return accepted
    }

    private fun readCuedBlock(
        reader: MkvBlockReader,
        segmentStart: Long,
        segmentEnd: Long,
        ref: CueRef,
        trackNo: Long,
        timecodeScale: Long,
        timecodeCache: HashMap<Long, Long>,
        out: MutableList<RawCue>
    ): Boolean {
        val cluster = readHeader(reader, segmentStart + ref.clusterPos, segmentEnd) ?: return false
        if (cluster.id != 0x1F43B675L) return false

        var clusterTs = timecodeCache[ref.clusterPos]
        if (clusterTs == null) {
            var found = -1L
            var p = cluster.dataStart
            var looked = 0
            while (looked < 8 && p < segmentEnd) {
                val c = readHeader(reader, p, segmentEnd) ?: return false
                if (c.size < 0) return false
                if (c.id == 0xE7L) { found = reader.unsigned(c.dataStart, c.size); break }
                p = c.end
                looked++
            }
            if (found < 0) return false
            timecodeCache[ref.clusterPos] = found
            clusterTs = found
        }

        val el = readHeader(reader, cluster.dataStart + ref.relPos, segmentEnd) ?: return false
        var blockStart = -1L
        var blockSize = 0L
        var duration = 0L
        when (el.id) {
            0xA3L -> { blockStart = el.dataStart; blockSize = el.size }
            0xA0L -> {
                if (el.size < 0) return false
                var q = el.dataStart
                while (q < el.end) {
                    val g = readHeader(reader, q, el.end) ?: break
                    if (g.size < 0) return false
                    if (g.id == 0xA1L) { blockStart = g.dataStart; blockSize = g.size }
                    else if (g.id == 0x9BL) duration = reader.unsigned(g.dataStart, g.size)
                    q = g.end
                }
            }
            else -> return false
        }
        if (blockStart < 0 || blockSize <= 0) return false

        val first = reader.u8(blockStart)
        if (first <= 0) return false
        val trackLen = Integer.numberOfLeadingZeros(first) - 24 + 1
        var blockTrack = (first and (0xFF ushr trackLen)).toLong()
        for (i in 1 until trackLen) {
            blockTrack = (blockTrack shl 8) or reader.u8(blockStart + i).coerceAtLeast(0).toLong()
        }
        if (blockTrack != trackNo) return false
        val headerLen = trackLen + 3
        val rel = ((reader.u8(blockStart + trackLen).coerceAtLeast(0) shl 8) or
            reader.u8(blockStart + trackLen + 1).coerceAtLeast(0)).toShort().toLong()
        val flags = reader.u8(blockStart + trackLen + 2)
        if (((flags shr 1) and 3) != 0) return false
        val payloadSize = blockSize - headerLen
        if (payloadSize <= 0 || payloadSize > 4_000_000) return false
        out += RawCue(
            startMs = (clusterTs + rel) * timecodeScale / 1_000_000L,
            durationMs = duration * timecodeScale / 1_000_000L,
            data = reader.bytes(blockStart + headerLen, payloadSize.toInt())
        )
        return true
    }

    private fun extractMultiple(
        video: ScannedVideo,
        tracks: List<SubtitleTrack>,
        convertAssToVtt: Boolean
    ): Map<SubtitleTrack, File> {
        require(tracks.isNotEmpty()) { "No subtitle tracks selected." }
        val unsupported = tracks.filterNot { it.nativeSupported }
        require(unsupported.isEmpty()) {
            "This app's native extractor supports ASS, SSA, SRT, and WebVTT only. " +
                "Image-based or other tracks are unsupported: " +
                unsupported.joinToString { "Track #${it.index} (${it.codec})" }
        }
        return extractTextTracksNative(video, tracks, convertAssToVtt)
    }
}
