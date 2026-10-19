package com.mikaso.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A finished job's file, as reported by GET /api/progress/<id>. */
data class JobResult(
    val filename: String,
    val size: Long,
    val mime: String,
    val downloadUrl: String,
)

/** A snapshot of one job from the backend queue. */
data class JobStatus(
    val stage: String,
    val stageIndex: Int,
    val percent: Float,
    val queuePosition: Int,
    val speed: Double?,
    val eta: Int?,
    val message: String?,
    val error: String?,
    val result: JobResult?,
) {
    val finished: Boolean get() = stage == "ready" || stage == "error"
    val ready: Boolean get() = stage == "ready"
}

/**
 * The one place that talks HTTP to the Mikaso backend.
 *
 * Every method throws [ApiException] with a message that is already fit to show the user.
 */
class ApiClient(private val settings: AppSettings) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun base(): String {
        val url = settings.state.value.serverUrl.trim().trimEnd('/')
        return url.ifEmpty { DEFAULT_SERVER }
    }

    private fun withAuth(builder: Request.Builder, key: String = settings.state.value.apiKey.trim()): Request.Builder {
        if (key.isNotEmpty()) builder.header("X-API-Key", key)
        return builder
    }

    private fun execute(build: () -> Request): JSONObject {
        val request = try {
            build()
        } catch (e: Exception) {
            throw ApiException("That server address does not look right. Check it in Settings.")
        }
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful) {
                    val error = json?.optJSONObject("error")
                    val message = error?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "The server refused the request (HTTP ${response.code})."
                    throw ApiException(message, error?.optString("code") ?: "http_${response.code}")
                }
                return json ?: throw ApiException("The server sent something we could not read.")
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("Could not reach the server. Check the address in Settings.")
        }
    }

    private fun post(path: String, body: JSONObject): JSONObject = execute {
        withAuth(Request.Builder().url(base() + path)).post(body.toString().toRequestBody(jsonType)).build()
    }

    private fun get(path: String): JSONObject = execute {
        withAuth(Request.Builder().url(base() + path)).get().build()
    }

    // ---- endpoints ------------------------------------------------------------------------

    /** POST /api/analyze */
    suspend fun analyze(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val json = post("/api/analyze", JSONObject().put("url", url))
        val media = json.optJSONObject("media")
            ?: throw ApiException("The server did not return any media for that link.")
        parseMedia(media)
    }

    /** POST /api/download/video or /api/download/audio — returns the new job id. */
    suspend fun startDownload(request: DownloadRequest): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("url", request.url)
        val path = if (request.kind == MediaKind.Audio) {
            body.put("format", request.audioFormat ?: "best")
            request.bitrate?.let { body.put("bitrate", it) }
            "/api/download/audio"
        } else {
            body.put("quality", request.quality ?: "best")
            body.put("container", request.container ?: "mp4")
            "/api/download/video"
        }
        val json = post(path, body)
        json.optJSONObject("job")?.optString("job_id")?.takeIf { it.isNotBlank() }
            ?: throw ApiException("The server did not start the download.")
    }

    /** GET /api/progress/<id> */
    suspend fun progress(jobId: String): JobStatus = withContext(Dispatchers.IO) {
        parseJob(get("/api/progress/$jobId"))
    }

    /** POST /api/cancel/<id> */
    suspend fun cancel(jobId: String) = withContext(Dispatchers.IO) {
        runCatching { post("/api/cancel/$jobId", JSONObject()) }
        Unit
    }

    /** GET /api/update?code=N — null when the app is up to date. */
    suspend fun update(currentCode: Int): RequiredUpdate? = withContext(Dispatchers.IO) {
        val json = get("/api/update?code=$currentCode")
        val u = json.optJSONObject("update") ?: return@withContext null
        RequiredUpdate(
            title = u.optString("title", "A new version of Mikaso is available."),
            versionName = u.optString("version_name", ""),
            link = u.optString("link", ""),
        )
    }

    /** GET /api/health — a short, human message for the "Test connection" button. */
    suspend fun health(url: String, key: String): String = withContext(Dispatchers.IO) {
        val baseUrl = url.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER }
        val request = try {
            withAuth(Request.Builder().url("$baseUrl/api/health").get(), key.trim()).build()
        } catch (e: Exception) {
            throw ApiException("That server address does not look right.")
        }
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException("The server answered HTTP ${response.code}.")
                val json = JSONObject(text)
                val app = json.optString("app", "Mikaso")
                val version = json.optString("version", "").trim()
                if (version.isEmpty()) "Connected to $app" else "Connected to $app $version"
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("Could not reach the server. Check the address.")
        }
    }

    /** Stream the finished file for [jobId] straight into [consume]. */
    suspend fun downloadBody(jobId: String, consume: (ResponseBody) -> Unit) = withContext(Dispatchers.IO) {
        val request = try {
            withAuth(Request.Builder().url(base() + "/api/download/$jobId")).get().build()
        } catch (e: Exception) {
            throw ApiException("That server address does not look right.")
        }
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiException("The file is no longer on the server.")
                val body = response.body ?: throw ApiException("The server sent an empty file.")
                consume(body)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("The download was interrupted. Try again.")
        }
    }

    // ---- parsing --------------------------------------------------------------------------

    private fun parseMedia(media: JSONObject): MediaInfo {
        val platform = Platform.fromId(media.optString("platform")) ?: Platform.YouTube
        return MediaInfo(
            url = media.optString("url"),
            platform = platform,
            title = media.optString("title", "Untitled"),
            uploader = media.optStringOrNull("uploader"),
            thumbnail = media.optStringOrNull("thumbnail"),
            durationText = media.optStringOrNull("duration_text"),
            video = parseVideo(media.optJSONObject("video")),
            audio = parseAudio(media.optJSONObject("audio")),
        )
    }

    private fun parseVideo(video: JSONObject?): VideoInfo {
        if (video == null) return VideoInfo()
        val qualities = mutableListOf<VideoQuality>()
        video.optJSONArray("qualities")?.let { array ->
            for (i in 0 until array.length()) {
                val q = array.optJSONObject(i) ?: continue
                val variants = mutableListOf<VideoVariant>()
                q.optJSONArray("variants")?.let { varray ->
                    for (j in 0 until varray.length()) {
                        val v = varray.optJSONObject(j) ?: continue
                        variants.add(
                            VideoVariant(
                                container = v.optString("container", "mp4"),
                                codec = v.optStringOrNull("codec"),
                                fps = v.optIntOrNull("fps"),
                                hdr = v.optBoolean("hdr", false),
                                size = v.optLongOrNull("size"),
                                sizeEstimated = v.optBoolean("size_estimated", false),
                                exceedsLimit = v.optBoolean("exceeds_limit", false),
                            )
                        )
                    }
                }
                qualities.add(
                    VideoQuality(
                        key = q.optString("key", "best"),
                        label = q.optString("label", "Video"),
                        height = q.optIntOrNull("height"),
                        variants = variants,
                        isBest = q.optBoolean("is_best", false),
                    )
                )
            }
        }
        return VideoInfo(
            available = video.optBoolean("available", qualities.isNotEmpty()),
            defaultContainer = video.optString("default_container", "mp4"),
            qualities = qualities,
        )
    }

    private fun parseAudio(audio: JSONObject?): AudioInfo {
        if (audio == null) return AudioInfo()
        val formats = mutableListOf<AudioFormat>()
        audio.optJSONArray("formats")?.let { array ->
            for (i in 0 until array.length()) {
                val f = array.optJSONObject(i) ?: continue
                val bitrates = mutableListOf<AudioBitrate>()
                f.optJSONArray("bitrates")?.let { barray ->
                    for (j in 0 until barray.length()) {
                        val b = barray.optJSONObject(j) ?: continue
                        bitrates.add(
                            AudioBitrate(
                                value = b.optInt("value", 0),
                                size = b.optLongOrNull("size"),
                                exceedsSource = b.optBoolean("exceeds_source", false),
                            )
                        )
                    }
                }
                formats.add(
                    AudioFormat(
                        format = f.optString("format", "best"),
                        label = f.optString("label", "Audio"),
                        ext = f.optString("ext", "m4a"),
                        defaultBitrate = f.optIntOrNull("default_bitrate"),
                        bitrates = bitrates,
                    )
                )
            }
        }
        return AudioInfo(
            available = audio.optBoolean("available", formats.isNotEmpty()),
            formats = formats,
        )
    }

    private fun parseJob(job: JSONObject): JobStatus {
        val error = job.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        val result = job.optJSONObject("result")?.let {
            JobResult(
                filename = it.optString("filename", "download"),
                size = it.optLong("size", 0),
                mime = it.optString("mime", "application/octet-stream"),
                downloadUrl = it.optString("download_url", ""),
            )
        }
        return JobStatus(
            stage = job.optString("stage", "preparing"),
            stageIndex = job.optInt("stage_index", 0),
            percent = job.optDouble("percent", 0.0).toFloat(),
            queuePosition = job.optInt("queue_position", 0),
            speed = job.optDoubleOrNull("speed"),
            eta = job.optDoubleOrNull("eta")?.toInt(),
            message = job.optStringOrNull("message"),
            error = error,
            result = result,
        )
    }
}

// ---- small JSON helpers (org.json returns 0/NaN for missing keys, which we do not want) -----

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").takeIf { it.isNotBlank() }
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    val d = optDouble(key, Double.NaN)
    return if (d.isNaN()) null else d.toInt()
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val d = optDouble(key, Double.NaN)
    return if (d.isNaN()) null else d.toLong()
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val d = optDouble(key, Double.NaN)
    return if (d.isNaN()) null else d
}
