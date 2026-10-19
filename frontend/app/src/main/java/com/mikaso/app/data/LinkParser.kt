package com.mikaso.app.data

import java.util.regex.Pattern

/**
 * Pulls a link out of whatever text the share sheet hands us (Instagram likes to append the
 * caption) and guesses which platform it belongs to.
 */
object LinkParser {

    private val URL_PATTERN: Pattern =
        Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE)

    private val HOSTS: List<Pair<Platform, List<String>>> = listOf(
        Platform.YouTube to listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "m.youtube.com"),
        Platform.Instagram to listOf("instagram.com", "instagr.am"),
        Platform.Facebook to listOf("facebook.com", "fb.watch", "fb.com", "m.facebook.com"),
    )

    /** The first http(s) link in [text], trimmed of trailing punctuation. Null when there is none. */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val matcher = URL_PATTERN.matcher(text)
        if (!matcher.find()) return null
        return matcher.group().trim().trimEnd('.', ',', ';', ')', ']', '"', '\'')
    }

    /** Which platform a link (or bare host) belongs to, or null when we do not recognise it. */
    fun guessPlatform(text: String?): Platform? {
        val raw = extractUrl(text) ?: text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "https://$raw"
        val host = runCatching { java.net.URI(withScheme).host?.lowercase() }.getOrNull() ?: return null
        return HOSTS.firstOrNull { (_, domains) ->
            domains.any { host == it || host.endsWith(".$it") }
        }?.first
    }
}
