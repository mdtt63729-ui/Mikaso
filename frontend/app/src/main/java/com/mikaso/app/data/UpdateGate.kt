package com.mikaso.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks the backend whether a newer version is required (GET /api/update) and exposes the answer.
 * The app sends the version code it is running; the server replies only if it must be updated.
 */
class UpdateGate(context: Context, private val api: ApiClient) {

    private val currentCode: Int = versionCode(context)

    private val _required = MutableStateFlow<RequiredUpdate?>(null)
    val required: StateFlow<RequiredUpdate?> = _required.asStateFlow()

    /** Never throws: a server that is down simply leaves the last answer in place. */
    suspend fun refresh() {
        runCatching { api.update(currentCode) }.onSuccess { _required.value = it }
    }

    private fun versionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else {
            @Suppress("DEPRECATION") info.versionCode
        }
    } catch (e: PackageManager.NameNotFoundException) {
        0
    }
}
