package com.instavideodownloader.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.DecimalFormat
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF111110)
private val Surface = Color(0xFF1C1A18)
private val Surface2 = Color(0xFF22211F)
private val Accent = Color(0xFFFFAB88)
private val TextMain = Color(0xFFFFF2E9)
private val TextMuted = Color(0xFFBEB6AE)
private val Green = Color(0xFF9ED6A8)
private val executor = Executors.newFixedThreadPool(2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        executor.execute { prepareFfmpeg() }
        val sharedUrl = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
        setContent { DownloaderTheme { DownloaderApp(sharedUrl = sharedUrl, activity = this) } }
    }

    /** Expose the bundled ffmpeg/ffprobe binaries to yt-dlp. Idempotent. */
    fun prepareFfmpeg() {
        try {
            Python.getInstance().getModule("bridge").callAttr(
                "prepare_ffmpeg",
                applicationInfo.nativeLibraryDir,
                File(filesDir, "ffmpeg").absolutePath
            )
        } catch (_: Exception) {
        }
    }

    fun saveToDownloads(source: File): Uri? {
        if (!source.exists() || !source.isFile) return null
        val mime = when (source.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, if (mime.startsWith("audio/")) "Music/VideoDL" else "Movies/VideoDL")
        }
        val collection = if (mime.startsWith("audio/")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { it.copyTo(out) } }
            uri
        } catch (_: Exception) {
            contentResolver.delete(uri, null, null)
            null
        }
    }
}

private data class VideoFormat(
    val id: String, val ext: String, val resolution: String, val height: Int,
    val size: Long, val hasVideo: Boolean, val hasAudio: Boolean, val note: String
)
private data class VideoInfo(
    val title: String, val description: String, val duration: Int, val thumbnail: String,
    val extractor: String, val url: String, val id: String, val formats: List<VideoFormat>
)
private data class DownloadItem(
    val id: String, val title: String, val filename: String, val state: String,
    val percent: Float, val speed: String, val eta: String, val message: String, val uri: String = ""
)

@Composable
private fun DownloaderTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Accent, onPrimary = Color(0xFF24120D), background = Bg, onBackground = TextMain,
        surface = Surface, onSurface = TextMain, surfaceVariant = Surface2, onSurfaceVariant = TextMuted,
        secondary = Accent, outline = Color(0xFF514139), error = Color(0xFFFF8A80)
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

@Composable
private fun DownloaderApp(sharedUrl: String, activity: MainActivity) {
    var screen by remember { mutableStateOf("Home") }
    var url by remember { mutableStateOf(sharedUrl) }
    var fetching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var selectedType by remember { mutableStateOf("Video + Audio") }
    var selectedFormat by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(DownloadItem("", "", "", "idle", 0f, "", "", "")) }
    var downloads by remember { mutableStateOf(listOf<DownloadItem>()) }
    var version by remember { mutableStateOf("Checking…") }
    val scope = rememberCoroutineScope()
    val prefs = remember { activity.getSharedPreferences("video_dl", android.content.Context.MODE_PRIVATE) }

    LaunchedEffect(Unit) {
        downloads = loadHistory(prefs)
        executor.execute { try { version = Python.getInstance().getModule("bridge").callAttr("version").toString() } catch (_: Exception) { version = "Unavailable" } }
    }
    BackHandler(screen != "Home") { screen = "Home"; error = ""; notice = "" }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Column(Modifier.fillMaxWidth().background(Color(0xFF191917)).padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CloudDownload, null, tint = Color(0xFF24120D), modifier = Modifier.size(27.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Insta Video Downloader", color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Powered by yt-dlp · On-device engine", color = TextMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { screen = "Settings"; error = "" }) { Icon(Icons.Default.Settings, "Settings", tint = Color(0xFFF5D4C4)) }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("Local processing", Green, Icons.Default.Shield)
                    StatusPill("No app backend", TextMuted, Icons.Default.CheckCircle)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF191917), contentColor = Accent) {
                listOf(Triple("Home", Icons.Default.Link, "Home"), Triple("Formats", Icons.Default.Tune, "Formats"), Triple("Downloads", Icons.Default.Download, "Downloads"), Triple("Settings", Icons.Default.Settings, "Settings")).forEach { (label, icon, destination) ->
                    NavigationBarItem(selected = screen == destination || (destination == "Formats" && screen == "Format Picker"), onClick = { screen = if (destination == "Formats" && info != null) "Format Picker" else if (destination == "Formats") "Home" else destination; error = "" }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Color(0xFF3B2B23), unselectedIconColor = TextMuted, unselectedTextColor = TextMuted))
                }
            }
        }
    ) { padding ->
        AnimatedContent(targetState = screen, transitionSpec = { androidx.compose.animation.fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith androidx.compose.animation.fadeOut(tween(120)) }, label = "screen") { current ->
            when (current) {
                "Home" -> HomeScreen(url, { url = it }, fetching, error, notice, downloads, onPaste = {
                    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    if (clipboard.hasPrimaryClip()) url = clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                }, onFetch = {
                    error = ""; notice = ""; info = null; fetching = true
                    val requestUrl = url.trim()
                    executor.execute {
                        val response = try { JSONObject(Python.getInstance().getModule("bridge").callAttr("extract_info", requestUrl).toString()) } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Metadata extraction failed") }
                        activity.runOnUiThread {
                            fetching = false
                            if (response.optBoolean("ok")) {
                                val array = response.optJSONArray("formats") ?: JSONArray()
                                val formats = (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { f -> VideoFormat(f.optString("format_id"), f.optString("ext", "mp4"), f.optString("resolution", "Unknown"), f.optInt("height"), f.optLong("filesize"), f.optBoolean("has_video"), f.optBoolean("has_audio"), f.optString("note")) } }
                                info = VideoInfo(response.optString("title"), response.optString("description"), response.optInt("duration"), response.optString("thumbnail"), response.optString("extractor"), response.optString("webpage_url", requestUrl), response.optString("id"), formats)
                                selectedFormat = formats.firstOrNull { it.hasVideo }?.id ?: formats.firstOrNull()?.id.orEmpty()
                                screen = "Format Picker"
                            } else error = response.optString("error", "Could not fetch this URL")
                        }
                    }
                }, onOpenDownloads = { screen = "Downloads" })
                "Format Picker" -> FormatScreen(info, fetching, selectedType, { selectedType = it; selectedFormat = info?.formats?.firstOrNull { f -> when (it) { "Audio only" -> f.hasAudio && !f.hasVideo; "Video only" -> f.hasVideo && !f.hasAudio; else -> f.hasVideo } }?.id.orEmpty() }, selectedFormat, { selectedFormat = it }, onDownload = {
                    val video = info ?: return@FormatScreen
                    if (downloading) return@FormatScreen
                    downloading = true; error = ""; notice = ""
                    val jobId = UUID.randomUUID().toString().replace("-", "")
                    val outDir = File(activity.getExternalFilesDir(null), "downloads")
                    val initial = DownloadItem(jobId, video.title, "", "starting", 0f, "", "", "Preparing download")
                    progress = initial; downloads = listOf(initial) + downloads
                    screen = "Downloads"
                    scope.launch {
                        activity.prepareFfmpeg()
                        val py = Python.getInstance().getModule("bridge")
                        var finished = false
                        // Build a full yt-dlp format selector. "Video + Audio" merges the
                        // chosen video stream with the best audio stream when the site
                        // offers them separately (which YouTube and Instagram do).
                        val chosen = video.formats.firstOrNull { it.id == selectedFormat }
                        val selector = when (selectedType) {
                            "Audio only" -> selectedFormat.ifBlank { "bestaudio/best" }
                            "Video only" -> selectedFormat.ifBlank { "bestvideo/best" }
                            else -> if (selectedFormat.isBlank()) "bv*+ba/b" else if (chosen?.hasAudio == true) selectedFormat else "$selectedFormat+bestaudio"
                        }
                        val worker = java.util.concurrent.FutureTask<String> { py.callAttr("start_download", video.url, selector, outDir.absolutePath, jobId, when (selectedType) { "Audio only" -> "audio"; "Video only" -> "video"; else -> "video_audio" }).toString() }
                        executor.execute(worker)
                        while (!worker.isDone) {
                            delay(550)
                            val status = try { JSONObject(py.callAttr("get_progress", jobId).toString()) } catch (_: Exception) { JSONObject() }
                            val percent = status.optDouble("percent", 0.0).toFloat().coerceIn(0f, 100f)
                            val speed = status.optDouble("speed", 0.0).let { if (it > 0) formatBytes(it.toLong()) + "/s" else "—" }
                            val eta = status.optInt("eta", -1).let { if (it >= 0) formatDuration(it) else "—" }
                            val item = initial.copy(state = status.optString("status", "downloading"), percent = percent, speed = speed, eta = eta, message = status.optString("message", "Downloading"), filename = status.optString("filename", ""))
                            progress = item; downloads = listOf(item) + downloads.filterNot { it.id == jobId }
                        }
                        val result = try { JSONObject(worker.get()) } catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "Download failed") }
                        val files = outDir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedByDescending { it.lastModified() }.orEmpty()
                        val file = files.firstOrNull { it.name.contains(video.id) } ?: files.firstOrNull()
                        val finalItem = if (result.optBoolean("ok") && file != null) {
                            val saved = activity.saveToDownloads(file)
                            initial.copy(title = video.title, filename = file.name, state = "finished", percent = 100f, message = if (saved != null) "Saved to Downloads/VideoDL" else "Downloaded in app storage", uri = saved?.toString().orEmpty())
                        } else initial.copy(state = "error", message = result.optString("error", "Download failed"))
                        downloads = listOf(finalItem) + downloads.filterNot { it.id == jobId }
                        progress = finalItem; downloading = false
                        saveHistory(prefs, downloads)
                        py.callAttr("cleanup_progress", jobId)
                        if (finalItem.state == "error") error = finalItem.message else notice = finalItem.message
                    }
                }, downloading)
                "Downloads" -> DownloadsScreen(downloads, progress, notice, onOpen = { item ->
                    if (item.uri.isNotBlank()) try { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), if (item.filename.endsWith("mp3")) "audio/*" else "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (_: Exception) { notice = "No compatible player found." }
                }, onRetry = { screen = "Home"; url = info?.url ?: url; notice = "Paste or fetch the URL again to retry." })
                else -> SettingsScreen(version, onCheck = { notice = "Bundled engine: yt-dlp $version. Update by building a new APK." }, notice = notice, onClear = { downloads = emptyList(); saveHistory(prefs, downloads); notice = "Download history cleared. Files already saved to Downloads are kept." })
            }
        }
    }
}

@Composable private fun StatusPill(text: String, tint: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = Color(0xFF282723), shape = CircleShape) { Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp)); Text(text, color = tint, fontSize = 10.sp) } }
}

@Composable private fun HomeScreen(url: String, onUrl: (String) -> Unit, loading: Boolean, error: String, notice: String, downloads: List<DownloadItem>, onPaste: () -> Unit, onFetch: () -> Unit, onOpenDownloads: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text("Video downloader", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Download from websites supported by yt-dlp.", color = TextMuted, fontSize = 13.sp) } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(22.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF514139))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Link, null, tint = Accent); Spacer(Modifier.width(8.dp)); Text("Video URL", color = TextMain, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); TextButton(onClick = onPaste) { Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Paste") } }
                    OutlinedTextField(value = url, onValueChange = onUrl, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Paste a video link here…", color = TextMuted) }, minLines = 2, maxLines = 4, shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = Color(0xFF514139), focusedTextColor = TextMain, unfocusedTextColor = TextMain, cursorColor = Accent))
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Public, null, tint = TextMuted, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Instagram · YouTube · TikTok · 1000+ sites", color = TextMuted, fontSize = 10.sp) }
                    Button(onClick = onFetch, enabled = !loading && url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) { if (loading) { CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Color(0xFF24120D)); Spacer(Modifier.width(9.dp)); Text("Fetching metadata…") } else { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text("Fetch video information") } }
                    if (loading) repeat(3) { SkeletonBar(Modifier.fillMaxWidth(if (it == 1) .65f else .92f), 13) }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (notice.isNotBlank()) Text(notice, color = Accent, fontSize = 12.sp)
                }
            }
        }
        item { Text("Supported platforms", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(listOf("Instagram", "YouTube", "TikTok"), listOf("Facebook", "Twitter / X", "1000+ sites")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { name -> Surface(color = Surface2, shape = RoundedCornerShape(13.dp), modifier = Modifier.weight(1f)) { Column(Modifier.padding(vertical = 15.dp, horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) { Icon(if (name == "1000+ sites") Icons.Default.Public else Icons.Default.VideoFile, null, tint = Accent, modifier = Modifier.size(24.dp)); Text(name, color = TextMain, fontSize = 11.sp, maxLines = 1) } } } }
                }
            }
        }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Recent downloads", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); TextButton(onClick = onOpenDownloads) { Text("See all") } } }
        if (downloads.isEmpty()) item { EmptyPanel("No downloads yet", "Fetched videos and completed downloads will appear here.", Icons.Default.History) }
        else items(downloads.take(3), key = { it.id }) { item -> DownloadRow(item) }
    }
}

@Composable private fun FormatScreen(info: VideoInfo?, loading: Boolean, type: String, onType: (String) -> Unit, selected: String, onSelected: (String) -> Unit, onDownload: () -> Unit, busy: Boolean) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Choose your format", color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text("Available formats are detected for each video.", color = TextMuted, fontSize = 12.sp) }
        item {
            if (loading || info == null) SkeletonVideoCard() else Card(colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(13.dp)).background(Color(0xFF302A25)), contentAlignment = Alignment.Center) { Icon(Icons.Default.VideoFile, null, tint = Accent, modifier = Modifier.size(46.dp)) }
                    Text(info.title, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${formatDuration(info.duration)} · ${info.extractor}", color = TextMuted, fontSize = 11.sp)
                    if (info.description.isNotBlank()) Text(info.description, color = TextMuted, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { Text("Download type", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Video + Audio", "Video only", "Audio only").forEach { option -> SelectableRow(option, when (option) { "Audio only" -> "Audio stream where available"; "Video only" -> "Video without audio"; else -> "Best video, audio merged automatically" }, type == option) { onType(option) } } } }
        item { Text("Available quality", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        if (info == null) item { SkeletonBar(Modifier.fillMaxWidth(), 56) }
        else {
            val eligible = info.formats.filter { f -> when (type) { "Audio only" -> f.hasAudio && !f.hasVideo; "Video only" -> f.hasVideo && !f.hasAudio; else -> f.hasVideo } }
            val unique = eligible.distinctBy { if (it.height > 0) "${it.height}p" else "${it.resolution}-${it.ext}" }.take(16)
            if (unique.isEmpty()) item { EmptyPanel("No matching format found", "Try another download type or use Best available.", Icons.Default.Info) }
            items(unique, key = { it.id }) { f ->
                val label = if (f.height > 0) "${f.height}p" else f.resolution
                SelectableRow("$label · ${f.ext.uppercase()}", "${if (f.hasAudio) "Audio included" else "Best audio will be merged"} · ${if (f.size > 0) formatBytes(f.size) else "Size unknown"}${if (f.note.isNotBlank()) " · ${f.note}" else ""}", selected == f.id) { onSelected(f.id) }
            }
            item { Text("Tip: the app lists formats yt-dlp reports for this URL. Separate video/audio streams are merged automatically using the bundled FFmpeg.", color = TextMuted, fontSize = 11.sp) }
        }
        item { Button(onClick = onDownload, enabled = info != null && !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "Download in progress…" else "Download selected format") } }
    }
}

@Composable private fun DownloadsScreen(items: List<DownloadItem>, current: DownloadItem, notice: String, onOpen: (DownloadItem) -> Unit, onRetry: () -> Unit) {
    var tab by remember { mutableStateOf("Active") }
    val shown = when (tab) { "Completed" -> items.filter { it.state == "finished" }; "Failed" -> items.filter { it.state == "error" }; else -> items.filter { it.state !in listOf("finished", "error") } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
        Text("Downloads", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Track progress and open saved files.", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("Active", "Completed", "Failed").forEachIndexed { index, label -> SegmentedButton(selected = tab == label, onClick = { tab = label }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label, fontSize = 11.sp) } } }
        Spacer(Modifier.height(12.dp))
        if (notice.isNotBlank()) Text(notice, color = Accent, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyPanel("Nothing here yet", "Downloads in this category will show up here.", Icons.Default.Download) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) { items(shown, key = { it.id }) { item -> DownloadRow(item, onClick = { onOpen(item) }, onRetry = onRetry) } }
    }
}

@Composable private fun DownloadRow(item: DownloadItem, onClick: () -> Unit = {}, onRetry: () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF382A23)), contentAlignment = Alignment.Center) { Icon(if (item.state == "finished") Icons.Default.CheckCircle else Icons.Default.VideoFile, null, tint = if (item.state == "finished") Green else Accent) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(item.title.ifBlank { "Video download" }, color = TextMain, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp); Text(item.message.ifBlank { item.state }, color = TextMuted, fontSize = 10.sp, maxLines = 2) }
                if (item.state == "finished") Icon(Icons.Default.CheckCircle, null, tint = Green)
            }
            if (item.state !in listOf("finished", "error")) {
                LinearProgressIndicator(progress = { (item.percent / 100f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = Accent, trackColor = Color(0xFF403B36))
                Row { Text("${item.percent.toInt()}% · ${item.speed}", color = Accent, fontSize = 10.sp); Spacer(Modifier.weight(1f)); Text("ETA ${item.eta}", color = TextMuted, fontSize = 10.sp) }
            }
            if (item.state == "error") TextButton(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("Try again") }
        }
    }
}

@Composable private fun SettingsScreen(version: String, onCheck: () -> Unit, notice: String, onClear: () -> Unit) {
    var maxDownloads by remember { mutableFloatStateOf(2f) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Customize your download experience.", color = TextMuted, fontSize = 12.sp) }
        item { SettingsCard(Icons.Default.Folder, "Download location", "Movies/VideoDL/ and Music/VideoDL/", "Files are copied to public Downloads media folders using Android MediaStore.") }
        item { Card(colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Tune, null, tint = Accent); Spacer(Modifier.width(10.dp)); Column { Text("Concurrent downloads", color = TextMain, fontWeight = FontWeight.SemiBold); Text("${maxDownloads.toInt()} at a time (UI preference)", color = TextMuted, fontSize = 11.sp) } }; Slider(value = maxDownloads, onValueChange = { maxDownloads = it }, valueRange = 1f..3f, steps = 1, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent)) } } }
        item { SettingsCard(Icons.Default.Shield, "Privacy", "No account, analytics or app backend", "Requests go directly from your device to the target website. Sites may still receive normal network metadata.") }
        item { Card(colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CloudDownload, null, tint = Accent); Spacer(Modifier.width(10.dp)); Column { Text("yt-dlp engine", color = TextMain, fontWeight = FontWeight.SemiBold); Text("Version $version", color = TextMuted, fontSize = 11.sp) } }; OutlinedButton(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Check engine version") }; Text("This version is bundled into the APK. Updating yt-dlp requires a new app build.", color = TextMuted, fontSize = 11.sp) } } }
        item { OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) { Text("Clear download history") } }
        if (notice.isNotBlank()) item { Text(notice, color = Accent, fontSize = 12.sp) }
        item { Text("About Insta Video Downloader", color = TextMain, fontWeight = FontWeight.SemiBold); Text("On-device extraction powered by yt-dlp. Only download content you own or have permission to save. Platform terms and copyright laws still apply.", color = TextMuted, fontSize = 12.sp) }
    }
}

@Composable private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(17.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) { Icon(icon, null, tint = Accent); Spacer(Modifier.width(12.dp)); Column { Text(title, color = TextMain, fontWeight = FontWeight.SemiBold); Text(subtitle, color = TextMuted, fontSize = 12.sp); Spacer(Modifier.height(5.dp)); Text(detail, color = TextMuted, fontSize = 11.sp) } } }
}

@Composable private fun SelectableRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (selected) Color(0xFF33251F) else Surface, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Accent else Color(0xFF383531)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, color = TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp); Text(subtitle, color = TextMuted, fontSize = 10.sp) }; RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = TextMuted)) }
    }
}

@Composable private fun SkeletonBar(modifier: Modifier = Modifier, height: Int = 14) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(initialValue = .28f, targetValue = .72f, animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "shimmer")
    Box(modifier.height(height.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF8A7568).copy(alpha = alpha)))
}

@Composable private fun SkeletonVideoCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Surface2), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { SkeletonBar(Modifier.fillMaxWidth(), 190); SkeletonBar(Modifier.fillMaxWidth(.82f), 18); SkeletonBar(Modifier.fillMaxWidth(.45f), 12); SkeletonBar(Modifier.fillMaxWidth(), 12); SkeletonBar(Modifier.fillMaxWidth(.72f), 12) } }
}

@Composable private fun EmptyPanel(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = Accent, modifier = Modifier.size(36.dp)); Text(title, color = TextMain, fontWeight = FontWeight.SemiBold); Text(subtitle, color = TextMuted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble(); var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return "${DecimalFormat("#.##").format(value)} ${units[index]}"
}
private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "Duration unknown"
    val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}
private fun saveHistory(prefs: android.content.SharedPreferences, items: List<DownloadItem>) {
    val array = JSONArray()
    items.take(100).forEach { d -> array.put(JSONObject().put("id", d.id).put("title", d.title).put("filename", d.filename).put("state", d.state).put("percent", d.percent).put("speed", d.speed).put("eta", d.eta).put("message", d.message).put("uri", d.uri)) }
    prefs.edit().putString("history", array.toString()).apply()
}
private fun loadHistory(prefs: android.content.SharedPreferences): List<DownloadItem> {
    return try { val array = JSONArray(prefs.getString("history", "[]")); (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { o -> DownloadItem(o.optString("id"), o.optString("title"), o.optString("filename"), o.optString("state"), o.optDouble("percent").toFloat(), o.optString("speed"), o.optString("eta"), o.optString("message"), o.optString("uri")) } } } catch (_: Exception) { emptyList() }
}
