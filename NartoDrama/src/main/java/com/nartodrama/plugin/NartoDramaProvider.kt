package com.nartodrama.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// Standalone "Narto Drama" extension. v45: apex narto-drama.com is now fronted by a
// Cloudflare "Checking your browser..." JS challenge (raw HTTP clients AND the app's OkHttp
// both get the challenge page, never content) — so this provider now targets the verified-live
// edge host, same as the Edge extension. Still 100% independent: own module, own cache, own
// refresh channel, own cooldown handling. No shared base class with the other source.
private const val NARTO_HOST = "https://edge.narto-drama.com"
private const val STREAM_HOST = "https://stream.narto-drama.com"

// Backend hosts that are dead (DNS NODATA / non-existent domain) and must NOT be emitted as
// playback links — the player would select them and fail.
private val DEAD_HOST_PATTERNS = listOf("montagehub")

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

// Narto playback API — Narto aggregates short-drama from MANY backends (shortmax, NetShort,
// StardustTV, mydramawave, ...). Each work's direct_play_url/play_url/multi_resolutions may
// be an HLS playlist OR a direct MP4 file — so loadLinks detects the container per link.
private data class NartoResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val canonical: String? = null,          // full canonical URL hint on slug_mismatch
    @JsonProperty("retry_after_seconds") val retryAfterSeconds: Int? = null, // 429 cooldown
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null,
    @JsonProperty("play_url") val playUrl: String? = null,
    @JsonProperty("multi_resolutions") val multiResolutions: List<NartoResolution>? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<NartoSub>? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,
    @JsonProperty("direct_subtitle_url") val directSubtitleUrl: String? = null,
    @JsonProperty("selected_subtitle_language") val selectedSubtitleLanguage: String? = null,
)

private data class NartoResolution(
    val resolution: Int? = null,
    val label: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
)

private data class NartoSub(
    @JsonProperty("language_code") val languageCode: String? = null,
    val label: String? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,     // relative /e/s/{jwt}
)

// Minimal fake JWT the API accepts (claims are not verified, slug/ep read from path).
private val fakeRsCtx = "eyJhbGciOiJub25lIn0.eyJ2IjoiMSJ9."

class NartoDramaProvider : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = NARTO_HOST
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Main screen = one section per tab, each a distinct Arabic query (verified live: different
    // queries return DIFFERENT feeds, 0 overlap). Each tab fetches ONE query when opened.
    // Capped to 12 so first paint is light.
    override val mainPage = mainPageOf(
        "دراما" to "🎬 دراما",
        "مدبلج" to "🎙️ مدبلج",
        "رومانسي" to "💕 رومانسي",
        "أكشن" to "⚔️ أكشن",
    )

    // Referer for all requests/links. This is just an HTTP Referer header the narto stream/
    // subtitle servers expect; it does NOT merge this source with the Edge extension.
    private val nartoOrigin = "https://narto-drama.com"

    // Per-tab in-memory cache so re-entering / tab-hopping serves the list instantly instead of
    // re-fetching the heavy /search page.
    private val searchCache = HashMap<String, String>()

    // Detect whether a stream URL is HLS or a direct video file. URL-based, no network probe.
    private fun inferStreamType(url: String): ExtractorLinkType {
        val lower = url.lowercase()
        if (lower.contains("mime_type=video_mp4") || lower.contains(".mp4") || lower.endsWith(".m4v"))
            return ExtractorLinkType.VIDEO
        return ExtractorLinkType.M3U8
    }

    // ---- Parse the ListItem JSON array embedded in a /search HTML page ----
    private fun parseSearchItems(html: String): List<SearchHit> {
        return try {
            val marker = "\"@type\":\"ListItem\""
            val start = html.indexOf(marker)
            if (start < 0) return emptyList()
            var arrStart = start
            while (arrStart > 0 && html[arrStart] != '[') arrStart--
            if (html[arrStart] != '[') return emptyList()
            var depth = 0
            var arrEnd = -1
            for (k in arrStart until html.length) {
                when (html[k]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { arrEnd = k; break } }
                }
            }
            if (arrEnd < 0) return emptyList()
            mapper.readValue(
                html.substring(arrStart, arrEnd + 1),
                object : com.fasterxml.jackson.core.type.TypeReference<List<SearchHit>>() {}
            ).filter { !it.url.isNullOrBlank() && !it.name.isNullOrBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchSearch(q: String): String? {
        searchCache[q]?.let { return it }
        val urlEncQ = java.net.URLEncoder.encode(q, "UTF-8")
        try {
            val html = app.get("$mainUrl/search?lang=ar-SA&q=$urlEncQ&page=1&perPage=12", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            if (html.contains("\"@type\":\"ListItem\"")) {
                searchCache[q] = html
                return html
            }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search fetch error", e)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val q = request.data.trim()
            val html = fetchSearch(q)
            if (html == null) {
                android.util.Log.e("NartoDrama", "getMainPage fetch failed q=$q")
                return null
            }
            android.util.Log.e("NartoDrama", "getMainPage q=$q fetchMs=${System.currentTimeMillis() - t0} len=${html.length}")
            val items = parseSearchItems(html)
            if (items.isEmpty()) return null
            val list = items.take(12).mapNotNull { it.toSearchResponse() }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "getMainPage ERROR", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val html = fetchSearch(query)
            if (html == null) return null
            parseSearchItems(html).mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search ERROR q=$query", e)
            null
        }
    }

    private fun SearchHit.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        val poster = image?.let { if (it.startsWith("http")) it else mainUrl + it }
        return newTvSeriesSearchResponse(name, "$mainUrl/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(url)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: mainUrl
            var doc: org.jsoup.nodes.Document? = null
            var attempt = 0
            // v31 (perf): no retry-sleep punishment. The detail page is server-bound: the HTML
            // build takes 12-19s on slow slugs, so a generous first timeout gets a real 200 —
            // extra sleeps on failure only ADD perceived delay. One instant re-probe, then give up.
            while (attempt < 2 && doc == null) {
                attempt++
                try {
                    doc = app.get("$loadHost/detail/watch/$slug", referer = nartoOrigin, headers = mapOf("User-Agent" to UA), timeout = 30000L).document
                } catch (e: Exception) {
                    android.util.Log.e("NartoDrama", "load attempt=$attempt/2 slug=$slug error=${e.message?.take(80)}", e)
                }
            }
            if (doc == null) return null
            android.util.Log.e("NartoDrama", "load slug=$slug host=$loadHost fetchMs=${System.currentTimeMillis() - t0} eps=" + doc.select("div.episode-list a.episode-item").size)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")

            var eps = doc.select("div.episode-list a.episode-item")
                .mapNotNull { el ->
                    val href = el.attr("href") ?: return@mapNotNull null
                    val ep = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                    newEpisode("$loadHost/detail/watch/$slug/$ep") {
                        episode = ep
                        name = "الحلقة $ep"
                    }
                }

            if (eps.isEmpty()) {
                android.util.Log.e("NartoDrama", "load slug=$slug no static episodes -> fallback single ep=1")
                eps = listOf(
                    newEpisode("$loadHost/detail/watch/$slug/1") {
                        episode = 1
                        name = "الحلقة"
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
            }
        } catch (e: Exception) {
            null
        }
    }

    // Fetch the refresh-source payload for this provider's OWN host only (narto-drama.com).
    // Per-episode cooldown handling: retryable gate that we wait out (bounded) then retry.
    private suspend fun fetchRefresh(slug: String, ep: String): NartoResponse? {
        var waited = false
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                val body = app.get(
                    "$mainUrl/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx",
                    referer = nartoOrigin,
                    timeout = 60000L
                ).text
                val edge = mapper.readValue(body, NartoResponse::class.java)
                if (edge.ok != true && (edge.message == "refresh_source_recently_failed" || edge.message == "refresh_source_cooldown_active")) {
                    if (waited) {
                        android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN persists slug=$slug ep=$ep retryAfter=${edge.retryAfterSeconds}")
                        return null
                    }
                    waited = true
                    val waitMs = ((edge.retryAfterSeconds ?: 15).coerceIn(4, 12)) * 1000L
                    android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN slug=$slug ep=$ep waiting=${waitMs}ms")
                    try { Thread.sleep(waitMs) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                    continue
                }
                return edge
            } catch (e: Exception) {
                android.util.Log.e("NartoDrama", "fetchRefresh ERROR attempt=$attempt/2 slug=$slug ep=$ep", e)
                if (attempt < 2) {
                    try { Thread.sleep(800) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val m = Regex("""/detail/watch/([^/?]+)/(\d+)""").find(data) ?: return false
            val ep = m.groupValues[2]
            var slug = m.groupValues[1]
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(data)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: mainUrl

            var edge = fetchRefresh(slug, ep)
            if (edge == null) {
                android.util.Log.e("NartoDrama", "loadLinks NO EDGE slug=$slug ep=$ep")
                return false
            }

            if (edge.ok != true && setOf(
                    "stream_temporarily_unavailable",
                    "refresh_source_recently_failed",
                    "refresh_source_cooldown_active"
                ).contains(edge.message)
            ) return false

            if (edge.ok != true && edge.message == "slug_mismatch") {
                val canon = edge.canonical?.let { Regex("""/detail/watch/([^/?]+)/""").find(it)?.groupValues?.get(1) }
                if (canon != null && canon != slug) {
                    android.util.Log.e("NartoDrama", "loadLinks slug_mismatch $slug -> $canon ep=$ep")
                    slug = canon
                    edge = fetchRefresh(slug, ep)
                    if (edge == null) return false
                }
            }
            if (edge.ok != true) {
                android.util.Log.e("NartoDrama", "loadLinks edge.ok!=true (continuing anyway) slug=$slug ep=$ep msg=${edge.message} play=${edge.directPlayUrl?.take(60)} res=${edge.multiResolutions?.size}")
            }

            // 1) subtitles — every track the API returns (multi_subtitles + any single track).
            val seenSubs = LinkedHashSet<String>()
            val subTracks = buildList {
                edge.multiSubtitles.orEmpty().forEach { s ->
                    val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                    val lang = s.label?.takeIf { it.isNotBlank() } ?: s.languageCode ?: "ترجمة"
                    add(lang to rel)
                }
                edge.subtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add((edge.selectedSubtitleLanguage?.takeIf { l -> l.isNotBlank() } ?: "ترجمة") to it)
                }
                edge.directSubtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add("ترجمة مباشرة" to it)
                }
            }
            for ((lang, rel) in subTracks) {
                val subUrl = if (rel.startsWith("http")) rel else STREAM_HOST + rel
                if (!seenSubs.add(subUrl)) continue
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            val emitted = LinkedHashSet<String>()
            var any = false
            var skippedDead = 0

            // shortmax-stream stores one signed token per episode; the ingest drops them at
            // ARBITRARY times (most are already 410 "link expired" even inside the exp window,
            // 2026-09-07 audit: 7/8 sampled tokens dead). Don't hand the player a dead master:
            // probe it (range GET) and skip 410/403. This kills the "plays a bit then spins on
            // a dead token" failure mode.
            fun isAlive(u: String): Boolean {
                val isShortmax = u.contains("shortmax-stream")
                val isProxy = u.contains("/e/m/")
                val probeBody = isProxy   // proxy 200s even when its src is "link expired"
                if (!isShortmax && !isProxy) return true
                return try {
                    val httpConn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    httpConn.apply {
                        requestMethod = "GET"
                        setRequestProperty("Referer", nartoOrigin)
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                        if (!probeBody) setRequestProperty("Range", "bytes=0-1")
                        else setRequestProperty("Range", "bytes=0-255")
                        connectTimeout = 3500
                        readTimeout = 3000
                        instanceFollowRedirects = true
                    }
                    val code = httpConn.responseCode
                    if (code !in 200..399) { httpConn.inputStream?.close(); return false }
                    if (probeBody) {
                        val body = httpConn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                        httpConn.inputStream?.close()
                        !body.contains("link expired")
                    } else {
                        httpConn.inputStream?.close()
                        true
                    }
                } catch (e: Exception) { false }
            }

            suspend fun emit(u: String, label: String, q: String) {
                if (u.isBlank() || !emitted.add(u)) return
                val host = u.substringAfter("//").substringBefore("/").substringBefore(":").lowercase()
                if (DEAD_HOST_PATTERNS.any { host.contains(it) }) {
                    skippedDead++
                    android.util.Log.e("NartoDrama", "emit SKIP dead host $host ($label)")
                    return
                }
                if (!isAlive(u)) {
                    skippedDead++
                    android.util.Log.e("NartoDrama", "emit SKIP dead shortmax token $host ($label)")
                    return
                }
                val type = inferStreamType(u)
                callback(
                    newExtractorLink(source = name, name = label, url = u, type = type) {
                        referer = nartoOrigin
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to nartoOrigin)
                    }
                )
                any = true
            }

            // v37 (fix "افحص المصدرين واصلحهما بالكامل"): live API audit on 2026-09-05 showed the
            // source changed hosts AGAIN — today it returns a SINGLE playable URL in
            // direct_play_url / play_url (no fixed host): the probe hits were
            //   - https://melolo2.narto-drama.com/{token}       -> video/mp4 (verb/dubs, HTTP 200)
            //   - https://v3.tiktokcdn.com/...mime_type=video_mp4  -> video/mp4 (subbed, HTTP 200)
            //   - https://v-a.idrama.video/...                -> video/* (403 from curl, works in app)
            // and STARTING NOW multi_resolutions = [] and multi_subtitles = [] for every probed
            // work (the per-quality list is GONE from the API — the site serves a single file).
            //
            // v36 was therefore broken: it gated "كامل" on host.startsWith("stream-e1") and filled
            // the quality list from multi_resolutions — both now false/empty, so loadLinks emitted
            // NOTHING. The correct universal rule: emit the API's direct/play URL as-is ("كامل"),
            // whatever host it is (TikTok CDN, melolo2, idrama…), because the API hands us the
            // live signed file. Only skip hosts we KNOW are dead, and surface multi_resolutions
            // when the API does include them (some works/servers still do).
            fun proxyQuality(u: String?): String {
                val s = u ?: return "480p"
                val seg = s.trim().trimEnd('=').substringAfterLast('.')
                val dec = try { java.net.URLDecoder.decode(seg, "UTF-8") } catch (e: Exception) { seg }
                val q = Regex("""_(\d{3,4})p""").find(dec)?.groupValues?.get(1)
                return if (q == null) "480p" else "${q}p"
            }

            // Decode a JWT payload's "src" field robustly. Payload is base64url JSON
            // (header.payload[.sig]); the JSON text may contain escape sequences (still valid
            // JSON), so decode the bytes then use the ObjectMapper to extract "src" — regexes
            // on the raw string fail on escaped/unicode payloads (mydramawave's are escaped).
            fun jwtSrc(u: String): String? {
                return try {
                    val jwt = u.substringAfter("/e/m/").substringBefore("?")
                    // JWT is often signed-compact (payload.signature, NO header) — the FIRST
                    // dot-part is always the payload; using substringAfter('.') grabbed the
                    // signature as the payload on two-part tokens → gibberish → null → no links.
                    val payloadB64 = jwt.substringBefore('.').takeIf { it.isNotBlank() }
                        ?: return null
                    val bytes = try {
                        java.util.Base64.getUrlDecoder().decode(payloadB64)
                    } catch (e: IllegalArgumentException) {
                        java.util.Base64.getDecoder().decode(payloadB64.padEnd((payloadB64.length + 3) / 4 * 4, '='))
                    }
                    val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { String(bytes, Charsets.ISO_8859_1) }
                    mapper.readTree(text).get("src")?.asText()?.takeIf { it.startsWith("http") }
                } catch (e: Exception) { null }
            }

            // Decode a proxy ("/e/m/{jwt}") into the real signed src the provider intended.
            // The src is a normal HLS host (akamai-static.shorttv.live, video-v6.mydramawave.com,
            // ...) — emitting that host directly avoids the nested-relative-proxy infinite spin.
            suspend fun emitFromProxy(proxyUrl: String) {
                val src = jwtSrc(proxyUrl) ?: return
                if (!src.contains("/e/m/")) {
                    // If the proxy resolves to an akamai shorttv master, its segments are
                    // `main/segment-N.ts` WITHOUT the auth_key → the raw master 403s mid-play.
                    // The stream-e1 proxy re-wraps each segment as /e/s/{jwt} (with auth), so for
                    // akamai the SAFE link is the proxy itself, not the raw src.
                    if (src.contains("akamai-static.shorttv.live")) {
                        emit(proxyUrl, "كامل", "480p")
                    } else {
                        emit(src, "كامل", proxyQuality(src))
                    }
                }
                // shortmax: same uuid serves 480/720/1080 with one auth_key (verified 200).
                // CRITICAL: path uses `{uuid}_{q}/main.m3u8` with NO `p` — verified live that
                // `_720p`/`_1080p` return HTTP 403 while `_720`/`_1080` return 200. The label keeps
                // the `p` for display but the URL must NOT contain it.
                val m = Regex("""(.+?)_(\d{3,4})(?:p)?/main\.m3u8(\?.*)""").find(src) ?: return
                // akamai raw variants would 403 on their auth-less segments — for akamai the
                // proxy re-wrap (emitted above) is the ONLY safe link, so do NOT add raw variants.
                if (src.contains("akamai-static.shorttv.live")) return
                val base = m.groupValues[1]             // .../hls/{uuid}
                val query = m.groupValues[3]            // ?auth_key=...
                val baseQ = m.groupValues[2].toIntOrNull() ?: 480
                val ordered = listOf(1080, 720, 480).filter { it >= baseQ || it == 480 }
                    .sortedByDescending { it }
                for (q in ordered) {
                    val url = "${base}_${q}/main.m3u8$query"
                    emit(url, "${q}p", "${q}p")
                }
            }

            // 1) "كامل" — prefer real CDN hosts over the /e/m/{jwt} proxy. A proxy's HLS has
            // nested root-relative /e/m/{jwt} variant/segment URLs that many players can't
            // resolve, spinning forever; the signed src host (or a direct m3u8/MP4 from the API)
            // is what actually plays. Emit every direct/CDN candidate, then fall back to the
            // proxy only if there are none (so we never hand back an empty list).
            val isProxy = { u: String -> u.contains("/e/m/") }
            val directs = listOfNotNull(edge.directPlayUrl, edge.playUrl)
                .map { it.trim() }
                .filter { it.isNotBlank() && !isProxy(it) }
                .distinct()
            val proxies = listOfNotNull(edge.playUrl, edge.directPlayUrl)
                .map { it.trim() }
                .filter { it.isNotBlank() && isProxy(it) }
                .distinct()

            var directEmitted = 0
            var directOk = 0
            for (u in directs) {
                if (directEmitted >= 2) break
                // v43: on slow CDNs (shortmax-stream) a 1080 master's 1.7MB segments drain the
                // buffer as fast as it fills ("plays a bit then spins"). Prefer the 480 token
                // (740KB @ ~3s for a 10s segment) so the default "كامل" starts smooth; the
                // multi_resolutions emissions below still give 1080/720 to the quality picker.
                val picked = if (u.contains("shortmax-stream") && !u.contains("/e/m/")) {
                    edge.multiResolutions
                        ?.asSequence()
                        ?.filter { it.streamUrl?.contains("shortmax-stream") == true }
                        ?.minWithOrNull(compareBy { it.resolution ?: 1080 })
                        ?.streamUrl
                        ?.takeIf { it.isNotBlank() }
                } else null
                val before = emitted.size
                emit(picked ?: u, "كامل", picked?.let { proxyQuality(it) } ?: proxyQuality(u))
                if (emitted.size > before) directOk++
                directEmitted++
            }
            if (directOk == 0) {
                // No live direct CDN host survived the probe (dead shortmax tokens) — fall back
                // to the proxy (stream-e1 /e/m) which may still be alive; decode it to its src.
                val p = proxies.firstOrNull()
                if (p != null) emitFromProxy(p)
            }

            // v43: emit multi_resolutions too. Live audit 2026-09-07: the API server handed us
            // THREE separate shortmax-stream signed tokens (1080/720/480) — all three returned
            // HTTP 200 and played (masters + segments) even ~25 min after refresh. They are NOT
            // the dead-410 tokens of older months. Emitting them restores the quality selector,
            // and lets the player pick 480 on slow links (a 1080 segment is 1.7MB @ ~230KB/s
            // stream → consumes buffer faster than it fills → PAUSED buffering dip; 480's 740KB
            // @ 3.2s vs 10s play keeps well ahead). Only safe when the master we emit is NOT a
            // nested /e/m/{jwt} proxy (which would spin) — so gate each on a real CDN host.
            for (res in edge.multiResolutions.orEmpty()) {
                val su = res.streamUrl?.trim().orEmpty()
                if (su.isBlank() || su.contains("/e/m/")) continue
                val label = res.label?.takeIf { it.isNotBlank() } ?: run {
                    val r = res.resolution ?: 480
                    "${r}p"
                }
                emit(su, label, label)
            }

            if (emitted.isEmpty()) {
                android.util.Log.e("NartoDrama", "loadLinks no qualities emitted (all died?) slug=$slug")
            }

            android.util.Log.e("NartoDrama", "loadLinks DONE slug=$slug ep=$ep links=${emitted.size} subs=${subTracks.size} deadSkipped=$skippedDead any=$any")
            any
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "loadLinks FATAL", e)
            false
        }
    }
}