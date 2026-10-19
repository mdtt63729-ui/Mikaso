package com.mikaso.app.data

import android.content.Context
import com.mikaso.app.DownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs downloads in the background: starts the job on the backend, polls its progress, then
 * streams the finished file into the phone and adds it to the library.
 *
 * One coroutine per task, so the app can queue many at once; the backend decides how many run together.
 */
class DownloadManager(
    private val context: Context,
    private val settings: AppSettings,
    private val api: ApiClient,
    private val files: FileStore,
    private val library: LibraryStore,
    private val notifier: Notifier,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runners = ConcurrentHashMap<String, Job>()      // task id -> coroutine
    private val backendIds = ConcurrentHashMap<String, String>() // task id -> backend job id

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    /** Queue a download and return its task id (used to show the progress card). */
    fun enqueue(request: DownloadRequest): String {
        val id = UUID.randomUUID().toString()
        val task = DownloadTask(id = id, request = request, createdAt = System.currentTimeMillis())
        _tasks.update { it + task }
        DownloadService.start(context)
        runners[id] = scope.launch { run(id, request) }
        return id
    }

    /** Stop a task and forget it. */
    fun cancel(id: String) {
        runners.remove(id)?.cancel()
        backendIds.remove(id)?.let { backendId -> scope.launch { api.cancel(backendId) } }
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /** Re-run a failed task, keeping the same card. */
    fun retry(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        runners.remove(id)?.cancel()
        backendIds.remove(id)
        update(id) {
            it.copy(
                state = TaskState.Queued, percent = 0f, stageIndex = 0, queuePosition = 0,
                speed = null, eta = null, indeterminate = false, error = null, saved = null,
            )
        }
        DownloadService.start(context)
        runners[id] = scope.launch { run(id, task.request) }
    }

    /** Stop everything (the notification's "Cancel all" action, and the Downloads screen). */
    fun cancelAll() {
        runners.values.forEach { it.cancel() }
        runners.clear()
        val ids = backendIds.values.toList()
        backendIds.clear()
        _tasks.update { emptyList() }
        scope.launch { ids.forEach { api.cancel(it) } }
    }

    // ---- internals ------------------------------------------------------------------------

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private suspend fun run(id: String, request: DownloadRequest) {
        try {
            val backendId = api.startDownload(request)
            backendIds[id] = backendId

            var status = api.progress(backendId)
            applyStatus(id, status)
            while (!status.finished) {
                delay(POLL_MS)
                if (backendIds[id] == null) return          // cancelled while we waited
                status = api.progress(backendId)
                applyStatus(id, status)
            }

            val result = status.result
            if (status.ready && result != null) {
                update(id) { it.copy(state = TaskState.Saving, stageIndex = STAGE_LABELS.lastIndex, percent = 100f, indeterminate = false) }
                val saved = saveFile(id, backendId, result, request)
                if (saved != null) {
                    val item = LibraryItem(
                        id = UUID.randomUUID().toString(),
                        kind = request.kind,
                        platform = request.platform,
                        title = request.title,
                        filename = result.filename,
                        uri = saved.uri,
                        mime = result.mime,
                        size = if (saved.size > 0) saved.size else result.size,
                        createdAt = System.currentTimeMillis(),
                        uploader = request.uploader,
                        thumbnail = request.thumbnail,
                        label = request.label,
                    )
                    library.add(item)
                    update(id) { it.copy(state = TaskState.Done, saved = item, percent = 100f) }
                    notifier.done(request.title, success = true, show = settings.state.value.notifyDone)
                } else {
                    update(id) { it.copy(state = TaskState.Failed, error = "Could not save the file to your phone.") }
                    notifier.done(request.title, success = false, show = settings.state.value.notifyDone)
                }
            } else {
                update(id) { it.copy(state = TaskState.Failed, error = status.error ?: "The download failed.") }
                notifier.done(request.title, success = false, show = settings.state.value.notifyDone)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            update(id) { it.copy(state = TaskState.Failed, error = e.message) }
            notifier.done(request.title, success = false, show = settings.state.value.notifyDone)
        } catch (e: Exception) {
            update(id) { it.copy(state = TaskState.Failed, error = "Something went wrong. Try again.") }
        } finally {
            backendIds.remove(id)
            runners.remove(id)
        }
    }

    private fun applyStatus(id: String, status: JobStatus) {
        val state = when (status.stage) {
            "preparing" -> TaskState.Preparing
            "analyzing" -> TaskState.Analyzing
            "downloading" -> TaskState.Downloading
            "processing" -> TaskState.Processing
            "finalizing" -> TaskState.Finalizing
            else -> TaskState.Downloading
        }
        update(id) {
            it.copy(
                state = state,
                percent = status.percent.coerceIn(0f, 100f),
                stageIndex = status.stageIndex.coerceIn(0, STAGE_LABELS.lastIndex),
                queuePosition = status.queuePosition,
                speed = status.speed?.takeIf { v -> v > 0.0 },
                eta = status.eta?.takeIf { v -> v > 0 },
                indeterminate = status.stage == "preparing" && status.percent <= 0f,
            )
        }
    }

    private suspend fun saveFile(id: String, backendId: String, result: JobResult, request: DownloadRequest): SavedFile? {
        var saved: SavedFile? = null
        api.downloadBody(backendId) { body ->
            saved = files.save(body.byteStream(), result.filename, result.mime, request.kind, settings.state.value)
        }
        return saved
    }

    private companion object {
        const val POLL_MS = 700L
    }
}
