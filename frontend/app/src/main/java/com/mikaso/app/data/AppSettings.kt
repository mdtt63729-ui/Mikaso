package com.mikaso.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Settings, kept in SharedPreferences as a single JSON blob so the model can grow without migrations. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    /** Apply a change and persist it. */
    fun update(transform: (SettingsState) -> SettingsState) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): SettingsState {
        val raw = prefs.getString(KEY, null) ?: return SettingsState()
        return runCatching { fromJson(JSONObject(raw)) }.getOrDefault(SettingsState())
    }

    private fun save(s: SettingsState) {
        prefs.edit().putString(KEY, toJson(s).toString()).apply()
    }

    private fun toJson(s: SettingsState): JSONObject = JSONObject()
        .put("theme", s.theme.name)
        .put("dynamicColor", s.dynamicColor)
        .put("preset", s.preset)
        .put("amoled", s.amoled)
        .put("splashAnimation", s.splashAnimation)
        .put("haptics", s.haptics)
        .put("saveMode", s.saveMode.name)
        .put("saveTree", s.saveTree)
        .put("maxConcurrent", s.maxConcurrent)
        .put("wifiOnly", s.wifiOnly)
        .put("defaultQuality", s.defaultQuality)
        .put("defaultContainer", s.defaultContainer)
        .put("defaultAudio", s.defaultAudio)
        .put("notifyDone", s.notifyDone)
        .put("showProgress", s.showProgress)
        .put("serverUrl", s.serverUrl)
        .put("apiKey", s.apiKey)
        .put("askedNotifications", s.askedNotifications)

    private fun fromJson(o: JSONObject): SettingsState {
        val d = SettingsState()
        return SettingsState(
            theme = runCatching { ThemeMode.valueOf(o.optString("theme", d.theme.name)) }.getOrDefault(d.theme),
            dynamicColor = o.optBoolean("dynamicColor", d.dynamicColor),
            preset = o.optString("preset", d.preset),
            amoled = o.optBoolean("amoled", d.amoled),
            splashAnimation = o.optBoolean("splashAnimation", d.splashAnimation),
            haptics = o.optBoolean("haptics", d.haptics),
            saveMode = runCatching { SaveMode.valueOf(o.optString("saveMode", d.saveMode.name)) }.getOrDefault(d.saveMode),
            saveTree = o.optString("saveTree", d.saveTree),
            maxConcurrent = o.optInt("maxConcurrent", d.maxConcurrent),
            wifiOnly = o.optBoolean("wifiOnly", d.wifiOnly),
            defaultQuality = o.optInt("defaultQuality", d.defaultQuality),
            defaultContainer = o.optString("defaultContainer", d.defaultContainer),
            defaultAudio = o.optString("defaultAudio", d.defaultAudio),
            notifyDone = o.optBoolean("notifyDone", d.notifyDone),
            showProgress = o.optBoolean("showProgress", d.showProgress),
            serverUrl = o.optString("serverUrl", d.serverUrl),
            apiKey = o.optString("apiKey", d.apiKey),
            askedNotifications = o.optBoolean("askedNotifications", d.askedNotifications),
        )
    }

    private companion object {
        const val PREFS = "mikaso_settings"
        const val KEY = "settings"
    }
}
