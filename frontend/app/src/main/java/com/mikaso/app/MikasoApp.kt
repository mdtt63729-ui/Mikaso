package com.mikaso.app

import coil.memory.MemoryCache
import coil.disk.DiskCache
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.mikaso.app.data.ApiClient
import com.mikaso.app.data.AppSettings
import com.mikaso.app.data.DownloadManager
import com.mikaso.app.data.FileStore
import com.mikaso.app.data.LibraryStore
import com.mikaso.app.data.Notifier
import com.mikaso.app.data.UpdateGate

/** Owns the long-lived pieces so downloads survive screen changes and keep running in the background. */
class MikasoApp : Application(), ImageLoaderFactory {
    lateinit var settings: AppSettings
        private set
    lateinit var api: ApiClient
        private set
    lateinit var files: FileStore
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var downloads: DownloadManager
        private set
    lateinit var updates: UpdateGate
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        api = ApiClient(settings)
        updates = UpdateGate(this, api)
        files = FileStore(this)
        library = LibraryStore(this)
        notifier = Notifier(this)
        notifier.ensureChannels()
        downloads = DownloadManager(this, settings, api, files, library, notifier)
    }

    /** Coil can also draw a frame of a downloaded video when no thumbnail link is available. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.18).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(48L * 1024 * 1024).build() }
            .allowRgb565(true)          // thumbnails do not need 32-bit colour: half the memory
            .crossfade(160)
            .build()
}
