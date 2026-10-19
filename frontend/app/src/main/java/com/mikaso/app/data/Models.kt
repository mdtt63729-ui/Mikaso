package com.mikaso.app.data

/**
 * The app's data layer: every model the UI works with, mirrored from the backend's JSON
 * (see backend/README.md and backend/mikaso/formats.py).
 */

enum class MediaKind { Video, Audio }

/** Platforms the backend can analyse and download. Order = the chips on Home. */
enum class Platform(val label: String) {
    YouTube("YouTube"),
    Instagram("Instagram"),
    Facebook("Facebook");

    companion object {
        /** The backend sends ids like "youtube"; map them back to the enum. */
        fun fromId(id: String?): Platform? {
            if (id.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(id.trim(), ignoreCase = true) }
        }
    }
}

enum class ThemeMode { System, Light, Dark }

enum class SaveMode { Default, Downloads, Custom }

/** Lifecycle of one download. [active] is true while it is queued or running. */
enum class TaskState {
    Queued, Preparing, Analyzing, Downloading, Processing, Finalizing, Saving, Failed, Done;

    val active: Boolean get() = this != Done && this != Failed
}

/** The six backend stages, in order (backend/mikaso/jobs.py STAGES). */
val STAGE_LABELS: List<String> = listOf("Preparing", "Analyzing", "Downloading", "Processing", "Finalizing", "Ready")

data class VideoVariant(
    val container: String,
    val codec: String? = null,
    val fps: Int? = null,
    val hdr: Boolean = false,
    val size: Long? = null,
    val sizeEstimated: Boolean = false,
    val exceedsLimit: Boolean = false,
)

data class VideoQuality(
    val key: String,
    val label: String,
    val height: Int? = null,
    val variants: List<VideoVariant> = emptyList(),
    val isBest: Boolean = false,
)

data class VideoInfo(
    val available: Boolean = false,
    val defaultContainer: String = "mp4",
    val qualities: List<VideoQuality> = emptyList(),
)

data class AudioBitrate(
    val value: Int,
    val size: Long? = null,
    val exceedsSource: Boolean = false,
)

data class AudioFormat(
    val format: String,
    val label: String,
    val ext: String,
    val defaultBitrate: Int? = null,
    val bitrates: List<AudioBitrate> = emptyList(),
)

data class AudioInfo(
    val available: Boolean = false,
    val formats: List<AudioFormat> = emptyList(),
)

/** Result of POST /api/analyze. */
data class MediaInfo(
    val url: String,
    val platform: Platform,
    val title: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val durationText: String? = null,
    val video: VideoInfo = VideoInfo(),
    val audio: AudioInfo = AudioInfo(),
)

/** What the user chose to download. Carries everything the task card and library need. */
data class DownloadRequest(
    val url: String,
    val platform: Platform,
    val title: String,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val durationText: String? = null,
    val kind: MediaKind,
    val label: String,
    val quality: String? = null,
    val container: String? = null,
    val estimatedSize: Long? = null,
    val audioFormat: String? = null,
    val bitrate: Int? = null,
)

/** One download in the queue, kept up to date while it runs. */
data class DownloadTask(
    val id: String,
    val request: DownloadRequest,
    val createdAt: Long,
    val state: TaskState = TaskState.Queued,
    val percent: Float = 0f,
    val stageIndex: Int = 0,
    val queuePosition: Int = 0,
    val speed: Double? = null,
    val eta: Int? = null,
    val indeterminate: Boolean = false,
    val error: String? = null,
    val saved: LibraryItem? = null,
) {
    val active: Boolean get() = state.active
}

/** A finished download: the file on disk plus the metadata we show in the list. */
data class LibraryItem(
    val id: String,
    val kind: MediaKind,
    val platform: Platform,
    val title: String,
    val filename: String,
    val uri: String,
    val mime: String,
    val size: Long = 0,
    val createdAt: Long = 0,
    val uploader: String? = null,
    val thumbnail: String? = null,
    val label: String = "",
)

/** A server-required update (GET /api/update). */
data class RequiredUpdate(
    val title: String,
    val versionName: String = "",
    val link: String,
)

/** Everything the Settings screen reads and writes. Persisted as one JSON blob. */
data class SettingsState(
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val preset: String = "forest",
    val amoled: Boolean = false,
    val splashAnimation: Boolean = true,
    val haptics: Boolean = true,
    val saveMode: SaveMode = SaveMode.Default,
    val saveTree: String = "",
    val maxConcurrent: Int = 2,
    val wifiOnly: Boolean = false,
    val defaultQuality: Int = 0,          // 0 = best
    val defaultContainer: String = "mp4",
    val defaultAudio: String = "best",
    val notifyDone: Boolean = true,
    val showProgress: Boolean = true,
    val serverUrl: String = DEFAULT_SERVER,
    val apiKey: String = "",
    val askedNotifications: Boolean = false,
)

/** Fallback backend address; the user can change it in Settings. */
const val DEFAULT_SERVER = "http://10.0.2.2:8000"
