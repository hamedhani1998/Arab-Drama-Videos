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
internal fun inferStreamType(url: String, apiIsHls: Boolean? = null): ExtractorLinkType {
    val lower = url.lowercase()
    // HLS is checked FIRST, and that order is the fix: the old order tested ".mp4" before
    // anything else, so an HLS playlist that carries ".mp4" anywhere in its query was labelled
    // VIDEO and the player fed a playlist to the file renderer. A measured m3u8 never contains
    // ".m3u8?...mp4" on this source, so this cannot mislabel a link that plays today.
    if (lower.contains(".m3u8") || lower.contains("mime_type=application/vnd.apple.mpegurl"))
        return ExtractorLinkType.M3U8
    if (lower.contains("mime_type=video_mp4") || lower.contains(".mp4") || lower.endsWith(".m4v"))
        return ExtractorLinkType.VIDEO
    // No hint in the URL at all. MEASURED 2026-10-06: the API sends `direct_play_is_hls` in
    // EVERY refresh payload (7/7 works), and the URL-only guess was wrong where it mattered —
    // slug lms-lmhzwr: play_url is an mp4 mirrored by sulao.montagehub.xyz with NO extension
    // (`/oY1A1lpELbUcBBGqQJAW646NqwAt5QSfvyfkcI?auth_key=...`) while its direct_play_url is the
    // same asset as an explicit `.mp4`, and the API's own flag says is_hls=false. The old code
    // fell through to M3U8 and handed the player a file as a playlist. Read the flag instead of
    // guessing; `null` (older payloads) keeps the previous M3U8 default.
    if (apiIsHls != null) return if (apiIsHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

/**
 * Every track the API returns: the multi list plus any single-track fields.
 *
 * The same language arrives TWICE for Arabic works — once inside `multi_subtitles` and again in
 * the flat `subtitle_url` field — and both were emitted, so the player listed two Arabic rows.
 * MEASURED 2026-10-06, slug nwn-ldrm-shw-at-lqdr: multi_subtitles carries ar-SA, and the flat
 * subtitle_url ALSO decodes to an ar-SA track; the flat one answered HTTP 404 while the
 * multi_subtitles copy is the one the API marks is_default. So the duplicate is not merely
 * cosmetic — the extra row is the broken one. Key on the language tag and let the multi list win,
 * because it is the richer, per-track source. `direct_subtitle_url` keeps its own "ar" key only
 * when Arabic did not already arrive from multi_subtitles, so a genuinely extra track still shows.
 */
internal fun NartoResponse.subtitleTracks(): List<Pair<String, String>> {
    val out = LinkedHashMap<String, String>()
    multiSubtitles.orEmpty().forEach { s ->
        val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
        out[s.langTag()] = rel
    }
    fun addFlat(tag: String, rel: String?) {
        val v = rel?.takeIf { it.isNotBlank() && !it.contains("undefined") } ?: return
        out.putIfAbsent(tag, v)
    }
    addFlat(selectedSubtitleLanguage.subLangTag(), subtitleUrl)
    addFlat("ar", directSubtitleUrl)
    return out.map { (tag, rel) -> tag to rel }
}
