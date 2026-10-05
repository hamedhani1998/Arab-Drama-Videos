package nartoshared

import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * Pure URL/JSON helpers — no network, no host-specific state, so both sources use the same
 * answers. Every function here was a copy that drifted between the two providers.
 */

/**
 * Detect whether a stream URL is HLS or a direct video file. URL-based, no network probe.
 *
 * The live API audit on 2026-09-05 showed the source changed hosts AGAIN — it returns a SINGLE
 * playable URL in direct_play_url / play_url (no fixed host), and it can be an HLS playlist OR a
 * direct MP4 per work, so the container has to be read per link.
 */
internal fun inferStreamType(url: String): ExtractorLinkType {
    val lower = url.lowercase()
    if (lower.contains("mime_type=video_mp4") || lower.contains(".mp4") || lower.endsWith(".m4v"))
        return ExtractorLinkType.VIDEO
    return ExtractorLinkType.M3U8
}

/**
 * Best-effort quality read off a URL when the API gives us nothing better.
 * The shortmax token no longer carries the quality in its path, so this usually answers 480p —
 * which is why [qualityOfRes] (the API's own field) is preferred wherever it exists.
 */
internal fun proxyQuality(u: String?): String {
    val s = u ?: return "480p"
    val seg = s.trim().trimEnd('=').substringAfterLast('.')
    val dec = try { java.net.URLDecoder.decode(seg, "UTF-8") } catch (e: Exception) { seg }
    val q = Regex("""_(\d{3,4})p""").find(dec)?.groupValues?.get(1)
    return if (q == null) "480p" else "${q}p"
}

/**
 * The authoritative quality for a multi_resolutions entry is the API's own `resolution`/`label`.
 * MEASURED 2026-10-02: the shortmax token no longer carries the quality in its path (it is
 * .../{token}/main.m3u8, no _720p and no query), so reading quality off the path mislabelled
 * all three as 480p.
 */
internal fun qualityOfRes(r: NartoResolution): String {
    val lbl = r.label?.trim()?.takeIf { it.isNotBlank() }
    if (lbl != null && Regex("""\d{3,4}""").containsMatchIn(lbl)) return lbl
    val n = r.resolution ?: return "480p"
    return "${n}p"
}

/**
 * Decode a JWT payload's "src" field robustly. Payload is base64url JSON (header.payload[.sig]);
 * the JSON text may contain escape sequences (still valid JSON), so decode the bytes then use the
 * ObjectMapper to extract "src" — regexes on the raw string fail on escaped/unicode payloads
 * (mydramawave's are escaped).
 */
internal fun jwtSrc(u: String): String? {
    return try {
        val jwt = u.substringAfter("/e/m/").substringBefore("?")
        // JWT is often signed-compact (payload.signature, NO header) — the FIRST dot-part is
        // always the payload; using substringAfter('.') grabbed the signature as the payload on
        // two-part tokens → gibberish → null → no links.
        val payloadB64 = jwt.substringBefore('.').takeIf { it.isNotBlank() } ?: return null
        val bytes = try {
            java.util.Base64.getUrlDecoder().decode(payloadB64)
        } catch (e: IllegalArgumentException) {
            java.util.Base64.getDecoder().decode(
                payloadB64.padEnd((payloadB64.length + 3) / 4 * 4, '=')
            )
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
        mapper.readTree(text).get("src")?.asText()?.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        null
    }
}

/** A "/e/m/{jwt}" proxy nests root-relative variant/segment URLs many players cannot resolve. */
internal fun isProxyUrl(u: String): Boolean = u.contains("/e/m/")

/** Every track the API returns: the multi list plus any single-track fields. */
internal fun NartoResponse.subtitleTracks(): List<Pair<String, String>> = buildList {
    multiSubtitles.orEmpty().forEach { s ->
        val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
        add(s.langTag() to rel)
    }
    subtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
        add(selectedSubtitleLanguage.subLangTag() to it)
    }
    directSubtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
        add("ar" to it)
    }
}