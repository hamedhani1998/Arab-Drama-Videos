package com.nartodrama.plugin

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// Standalone "Narto Drama" extension. v46: this provider carries its OWN domain — all
// links/cards/detail/refresh URLs use the apex host https://narto-drama.com (per user:
// "اجعل كل مصدر يحمل الرابط الخاص به"). Browsing HTML (home "/" and "/search") is fetched
// from the live edge host (BRW_HOST) because apex front-ends those routes with a Cloudflare
// JS challenge; only refresh/playback/detail go to apex (verified live: detail 200/82eps,
// refresh {"ok":true,"play_url":"stream-e1..."}). Still 100% independent of the Edge source.
private const val NARTO_HOST = "https://narto-drama.com"
private const val BRW_HOST = "https://edge.narto-drama.com"
// How long a successful refresh-source payload may be reused before we ask the API again. The
// tokens inside it are signed and short-lived, so this must stay well under their lifetime — 30 s
// only covers "re-open the episode you just watched", not a different one.
private const val REFRESH_CACHE_TTL_MS = 30 * 1000L
// stream.narto-drama.com is deliberately NOT a host we use: it answers every subtitle token with
// 501 "local file tetap di VPS edge". The name survives only in the loadLinks comment below.

// Backend hosts that are dead (DNS NODATA / non-existent domain) and must NOT be emitted as
// playback links — the player would select them and fail.
// Hosts we never hand to the player. `cdn.narto-drama.com` is the API's own "direct" host for
// shortmax works, but its TLS certificate is EXPIRED — measured 2026-10-02, valid-through date
// November 12, 2026 yet a strict handshake fails with "certificate has expired", so a link on
// this host can never play. The real qualities live in the signed shortmax-stream tokens, which
// serve fine (cert accepted, valid through December 1, 2026). Recoverable — drop this line once
// the host's cert is fixed.
private val DEAD_HOST_PATTERNS = listOf("montagehub", "cdn.narto-drama.com")

// joyreels-stream hands out signed tokens that go stale fast. MEASURED 2026-10-03: a token the
// API had just minted answered 410 "joyreels-edge: link expired" moments later. Probing a dead
// one is not free, so remember the verdict for a while — the same URL will not come back to life
// within one episode, and re-probing it only adds seconds to every replay.
private val deadLinkCache = LinkedHashMap<String, Long>()
private const val DEAD_LINK_TTL_MS = 5 * 60 * 1000L

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
    // MEASURED 2026-10-04: this boolean is the ONLY thing in the payload that distinguishes a
    // fresh token from a stale one. The API answers ok=true either way:
    //     source_refreshed=false -> joyreels token that is HTTP 410 the moment it arrives
    //                             (and the call itself took 13059 ms)
    //     source_refreshed=true  -> mydramawave, HTTP 200, 18 subtitles, 3 resolutions
    // so "ok" is not a promise of playability. Kept so loadLinks can tell a stale payload from a
    // live one instead of learning it from a 410.
    @JsonProperty("source_refreshed") val sourceRefreshed: Boolean? = null,
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

// Subtitle lang tags. SubtitleFile.getLangTag() resolves a code through fromCodeToLangTagIETF and
// only falls back to fromLanguageToTagIETF; run against the app's own SubtitleHelper (CS3 jar,
// 2026-10-03):   "ar" -> ar      "ar-SA" -> null      "ترجمة" -> null
// A region-suffixed code from the API therefore yields a NULL tag and the player lists a track it
// cannot load. Keep the code, drop the region — the app renders the Arabic name from "ar" itself.
// ("بالعربية" does resolve, via the name fallback — but only for the labels it happens to know.)
private fun String?.subLangTag(): String =
    this?.trim()?.takeIf { it.isNotBlank() }
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.takeIf { it.isNotBlank() } ?: "ar"

private fun NartoSub.langTag(): String = languageCode.subLangTag()

// Minimal fake JWT the API accepts (claims are not verified, slug/ep read from path).
private val fakeRsCtx = "eyJhbGciOiJub25lIn0.eyJ2IjoiMSJ9."

class NartoDramaProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = NARTO_HOST
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Main screen = one section per tab, each a distinct Arabic query (verified live: different
    // queries return DIFFERENT feeds, 0 overlap). fetchSearch caches per query, and getMainPage
    // runs ALL tabs in parallel so hopping tabs never re-fetches. First paint uses the requested
    // tab's own feed.
    override val mainPage = mainPageOf(
        "دراما" to "🎬 دراما",
        "مدبلج" to "🎙️ مدبلج",
        "رومانسي" to "💕 رومانسي",
        "أكشن" to "⚔️ أكشن",
    )

    private val homeSections = listOf("دراما", "مدبلج", "رومانسي", "أكشن")

    // Referer for all requests/links. This is just an HTTP Referer header the narto stream/
    // subtitle servers expect; it does NOT merge this source with the Edge extension.
    private val nartoOrigin = "https://narto-drama.com"

    // Per-tab in-memory cache so re-entering / tab-hopping serves the list instantly instead of
    // re-fetching the heavy /search page.
    private val searchCache = HashMap<String, String>()

    // Cached edge home "/" feed — the guaranteed never-blank fallback (verified live: it always
    // returns the content row, unlike a single /search which can fail or 520). Poster images are
    // absolute (img.nartodrama-api.online) and cards carry apex detail URLs, so the fallback
    // renders identically under any tab name.
    private var homeFeedCache: String? = null

    // One-shot gate: the OTHER tabs + home feed are warmed ONCE in a detached background
    // coroutine (never blocks first paint). Subsequent getMainPage calls tab-hop into warm caches.
    private var warmStarted = false

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

    // Fetch a /search feed. Browsing HTML comes from the live edge host (BRW_HOST) — apex is
    // Cloudflare-challenged for /search — but the cards' URLs embedded in the HTML already point
    // at narto-drama.com, so everything the user taps carries THIS provider's own domain. Also
    // rewrite any stray edge card URLs to apex so every handled link is consistently apex.
    private suspend fun fetchSearch(q: String): String? {
        searchCache[q]?.let { return it }
        val urlEncQ = java.net.URLEncoder.encode(q, "UTF-8")
        try {
            val html = app.get("$BRW_HOST/search?lang=ar-SA&q=$urlEncQ&page=1&perPage=12", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            if (html.contains("\"@type\":\"ListItem\"")) {
                searchCache[q] = html
                return html
            }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search fetch error", e)
        }
        return null
    }

    // The edge home "/" feed (Block-1 CollectionPage ListItems), cached. Apex "/" is
    // Cloudflare-challenged so we read the feed from edge, which is always up.
    private suspend fun fetchHomeFeed(): String? {
        homeFeedCache?.let { return it }
        try {
            val html = app.get("$BRW_HOST/", referer = nartoOrigin, headers = mapOf("User-Agent" to UA)).text
            if (html.contains("\"@type\":\"ListItem\"")) {
                homeFeedCache = html
                return html
            }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "home feed fetch error", e)
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val q = request.data.trim()
            // سرعة الواجهة: نعرض القسم المطلوب فوراً (fetch واحد فقط)، ونجهّز بقية
            // الأقسام في الخلفية دفعةً واحدة بمعزل عن العرض — نفس نمط Reelree.
            // بهذا لا يُحجب أول رسمٍ بانتظار أبطأ fetch من الأقسام الأخرى (كانت
            // التدفئة القديمة awaitAll تنتظر الجميع قبل إرجاع القسم المطلوب).
            warmOthersBackground(q)

            var html = fetchSearch(q)
            var fromFallback = false
            if (html == null || parseSearchItems(html).isEmpty()) {
                // Tab's own feed failed/empty — never blank the screen: serve it from the shared
                // home feed instead (always present, and cards carry apex URLs so nothing changes).
                android.util.Log.e("NartoDrama", "getMainPage q=$q empty/failed -> fallback home feed")
                html = fetchHomeFeed()
                fromFallback = true
                if (html == null) {
                    android.util.Log.e("NartoDrama", "getMainPage fetch failed q=$q")
                    return null
                }
            }
            android.util.Log.e("NartoDrama", "getMainPage q=$q fetchMs=${System.currentTimeMillis() - t0} len=${html.length} fallback=$fromFallback")
            val items = parseSearchItems(html)
            if (items.isEmpty()) {
                android.util.Log.e("NartoDrama", "getMainPage q=$q no items")
                return null
            }
            val list = items.take(12).mapNotNull { it.toSearchResponse() }
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "getMainPage ERROR", e)
            null
        }
    }

    // Warm the OTHER category feeds + the home feed in ONE detached background coroutine
    // (each fetch its own thread), so tab-hopping never waits. Runs once per app life;
    // getMainPage calls it before its inline fetch, but the two never block each other.
    private fun warmOthersBackground(q: String) {
        if (warmStarted) return
        synchronized(this) {
            if (warmStarted) return
            warmStarted = true
        }
        android.util.Log.e("NartoDrama", "starting background warm (q=$q)")
        GlobalScope.launch(Dispatchers.IO) {
            val targets = homeSections.filter { it != q } + "/*home*/"
            targets.map { warmQ ->
                launch(Dispatchers.IO) {
                    if (warmQ == "/*home*/") fetchHomeFeed() else fetchSearch(warmQ)
                }
            }.forEach { it.join() }
            android.util.Log.e("NartoDrama", "background warm complete (searchCache=${searchCache.size})")
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

    // Fetch the refresh-source payload for this provider's OWN host (narto-drama.com), with a
    // fallback to the edge host on network/DNS failure. Per-episode cooldown handling: retryable
    // gate that we wait out (bounded) then retry. On a transient device-DNS blip (UnknownHost on
    // main domain) this still resolves via edge, so loadLinks never returns empty needlessly.
    // MEASURED 2026-10-04 (adb logcat, slug lzl-lmkhtfy ep=1): the server answered
    // "refresh_source_cooldown_active" with retry_after_seconds = 20, but the wait was capped at
    // 12 s (coerceIn(4, 12)), so the retry landed INSIDE the window, drew the same cooldown again,
    // and loadLinks returned false — a legitimate 20 s cooldown ALWAYS ended as an empty episode.
    // Worse, two threads (pids 12006 and 12046 — a double tap on the same episode) each slept the
    // full 12 s and each gave up, so the user paid twice for one cooldown.
    // Three fixes:
    //   1. honour the number the server gave, bounded so an absurd value cannot hang loadLinks;
    //   2. ONE in-flight refresh per episode (a coroutine Mutex, which suspends rather than
    //      blocking — a plain synchronized{} around a Thread.sleep would deadlock the dispatcher);
    //   3. a short success cache, so re-opening the episode the user just watched costs nothing.
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshCache = LinkedHashMap<String, Pair<Long, NartoResponse>>()

    private suspend fun fetchRefresh(slug: String, ep: String): NartoResponse? {
        val key = "$slug/$ep"
        val lock = refreshLocks.computeIfAbsent(key) { Mutex() }
        lock.lock()
        try {
            refreshCache[key]?.let { (at, resp) ->
                if (System.currentTimeMillis() - at < REFRESH_CACHE_TTL_MS) {
                    android.util.Log.e("NartoDrama", "fetchRefresh CACHE hit slug=$slug ep=$ep")
                    return resp
                }
            }
            val fresh = fetchRefreshUncached(slug, ep)
            // A payload that says ok:true but source_refreshed=false is a RE-SERVED STALE token:
            // measured 2026-10-04, such a play_url is HTTP 410 the instant it reaches the player.
            // Asking again is the only lever we have — the upstream ingest is what actually
            // re-mints the token, and it flips to true on a later call for the same episode.
            // Skip the shared cache for a stale payload, or we would replay the dead one.
            if (fresh != null && fresh.ok == true && fresh.sourceRefreshed == false) {
                android.util.Log.e(
                    "NartoDrama",
                    "fetchRefresh STALE payload (source_refreshed=false) — re-requesting slug=$slug ep=$ep"
                )
                val again = fetchRefreshUncached(slug, ep)
                if (again != null && again.ok == true) {
                    refreshCache[key] = System.currentTimeMillis() to again
                    return again
                }
                android.util.Log.e(
                    "NartoDrama",
                    "fetchRefresh still stale after re-request slug=$slug ep=$ep " +
                        "(ok=${again?.ok} msg=${again?.message} refreshed=${again?.sourceRefreshed})"
                )
            }
            if (fresh != null) refreshCache[key] = System.currentTimeMillis() to fresh
            return fresh
        } finally {
            lock.unlock()
            // Drop the lock once nobody waits on it, so the map cannot grow with every episode.
            if (!lock.isLocked) refreshLocks.remove(key, lock)
        }
    }

    private suspend fun fetchRefreshUncached(slug: String, ep: String): NartoResponse? {
        // Try each host in order; final host = the other one (never the same twice).
        val hosts = listOf(mainUrl, BRW_HOST)
        var waited = false
        var lastErr: Exception? = null
        for (h in hosts) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                try {
                    val tl0 = System.currentTimeMillis()
                    val body = app.get(
                        "$h/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx",
                        referer = nartoOrigin,
                        timeout = 30000L
                    ).text
                    val ms = System.currentTimeMillis() - tl0
                    val edge = mapper.readValue(body, NartoResponse::class.java)
                    if (edge.ok != true && (edge.message == "refresh_source_recently_failed" || edge.message == "refresh_source_cooldown_active")) {
                        if (waited) {
                            android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN persists slug=$slug ep=$ep retryAfter=${edge.retryAfterSeconds}")
                            return null
                        }
                        waited = true
                        // Honour the server's own number. The old cap (12 s) was below the 20 s the
                        // API actually asks for, so the retry could never succeed — see the note above.
                        // Bounded at 25 s so a hostile or absurd value cannot hang loadLinks forever.
                        val waitMs = ((edge.retryAfterSeconds ?: 15).coerceIn(4, 25)) * 1000L
                        android.util.Log.e("NartoDrama", "fetchRefresh COOLDOWN slug=$slug ep=$ep waiting=${waitMs}ms")
                        try { Thread.sleep(waitMs) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                        continue
                    }
                    android.util.Log.e("NartoDrama", "fetchRefresh OK host=$h slug=$slug ep=$ep ${ms}ms ok=${edge.ok} play=${edge.directPlayUrl?.take(50) ?: edge.playUrl?.take(50)}")
                    return edge
                } catch (e: Exception) {
                    lastErr = e
                    android.util.Log.e("NartoDrama", "fetchRefresh ERROR host=$h attempt=$attempt/2 slug=$slug ep=$ep", e)
                    if (attempt < 2) {
                        try { Thread.sleep(800) } catch (e2: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }
            }
        }
        android.util.Log.e("NartoDrama", "fetchRefresh ALL HOSTS FAILED slug=$slug ep=$ep lastErr=${lastErr?.message?.take(80)}")
        // MEASURED 2026-10-03 on the phone: when the device resolver is briefly blind (the capture
        // shows UnknownHostException for apex, edge AND cdn within the same second) both hosts die
        // inside ~2s and the whole episode goes empty. A resolver blip is not a dead source — wait
        // it out and try once more, rather than handing the user a page with no links.
        if (lastErr is java.net.UnknownHostException) {
            for (backoff in listOf(1500L, 3000L)) {
                try { Thread.sleep(backoff) } catch (e2: InterruptedException) { Thread.currentThread().interrupt(); return null }
                android.util.Log.e("NartoDrama", "fetchRefresh DNS blip retry after ${backoff}ms slug=$slug ep=$ep")
                for (h in hosts) {
                    try {
                        val body = app.get(
                            "$h/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx",
                            referer = nartoOrigin,
                            timeout = 30000L
                        ).text
                        val edge = mapper.readValue(body, NartoResponse::class.java)
                        if (edge.ok == true) {
                            android.util.Log.e("NartoDrama", "fetchRefresh RECOVERED after DNS blip host=$h slug=$slug ep=$ep")
                            return edge
                        }
                    } catch (e: Exception) {
                        lastErr = e
                    }
                }
            }
        }
        return null
    }

    /**
     * ★ بثّ روابط الحلقة بعد اكتمال تجميعها: «افتراضي» = نفس الترتيب تماماً كما
     * كان (لا إعادة ترتيب إطلاقاً)، و«تصاعدي/تنازلي» يعيدان الترتيب فقط — فرز
     * مستقر فالمتساوية تحتفظ بترتيبها، ولا حذف ولا تكرار ولا تغيير في العدد.
     * تُستدعى مرة واحدة عند المخرج الوحيد من loadLinks.
     */
    private fun emitSorted(prefs: SharedPreferences?, collected: List<ExtractorLink>, callback: (ExtractorLink) -> Unit) {
        val order = prefs?.getString(NartoDramaSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) { "asc" -> collected.sortedBy { it.quality }; "desc" -> collected.sortedByDescending { it.quality }; else -> collected }
        sorted.forEach { callback(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ★ روابط الحلقة تُجمَّع هنا أولاً ثم تُبثّ دفعةً واحدة عند المخرج، ليصل
        // ترتيبها إلى الـ callback كما اختار المستخدم (افتراضياً: كما هي تماماً).
        // تُعرَّف قبل الـ try كي يتمكّن مسار catch من بثّ ما جُمع قبل الخطأ أيضاً
        // (سلوك اليوم: الروابط التي بُثّت قبل الاستثناء لا تضيع).
        val collected = mutableListOf<ExtractorLink>()
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
            //
            // HOST is the bug that made these rows appear and then fail. The site prints the
            // subtitle as a RELATIVE "/e/s/{jwt}" path, so the host is ours to choose, and we
            // were hardcoding STREAM_HOST. MEASURED 2026-10-03 on slug fkh-lgr eps 1/2/3, every
            // one answered by stream.narto-drama.com:
            //     HTTP 501  "local file tetap di VPS edge"   ← the edge VPS holds the file back
            // The same tokens on mainUrl return 200 text/vtt with a real WEBVTT body. So apex
            // serves them and the stream host never did.
            val seenSubs = LinkedHashSet<String>()
            val subTracks = buildList {
                edge.multiSubtitles.orEmpty().forEach { s ->
                    val rel = s.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@forEach
                    add(s.langTag() to rel)
                }
                edge.subtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add(edge.selectedSubtitleLanguage.subLangTag() to it)
                }
                edge.directSubtitleUrl?.takeIf { it.isNotBlank() && !it.contains("undefined") }?.let {
                    add("ar" to it)
                }
            }
            for ((lang, rel) in subTracks) {
                val subUrl = if (rel.startsWith("http")) rel else NARTO_HOST + rel
                if (!seenSubs.add(subUrl)) continue
                // Do NOT wrap this in try/catch. Swallowing it is how a subtitle row shipped that
                // the player could never load, with nothing in logcat to explain why.
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }

            val emitted = LinkedHashSet<String>()
            var any = false
            var skippedDead = 0
            // Why the last probe failed — so "emit SKIP" names the cause (410, expired, TLS)
            // instead of the generic "dead shortmax token" that hid this whole class of bug.
            var lastProbeWhy = ""
            // Did any probe return a VERDICT (an HTTP status, or an expiry marker inside a body)
            // rather than simply failing to reach the host? This is the distinction that decides
            // the last-resort branch below: a verdict means the link is dead and must not be
            // handed to the player, whereas an exception means "could not tell" and a live link
            // may still be sitting there. Never infer this by parsing lastProbeWhy — that is how
            // a verdict and a transport failure end up conflated again.
            var deadByVerdict = false

            // Every Narto stream host hands out a SIGNED, SHORT-LIVED token, and the ingest drops
            // them at arbitrary times — the live failure is a plain HTTP 410, not a parse bug.
            // MEASURED 2026-10-03 from the phone (adb logcat, 12 failures in one browsing pass):
            //   30 mentions of joyreels-stream.narto-drama.com  -> Response code: 410
            //   5  mentions of shortmax-stream.narto-drama.com -> Response code: 410
            // while `loadLinks DONE ... deadSkipped=0` — i.e. the provider reported a healthy
            // result and handed the player links that were already dead. The old probe only ran
            // for hosts containing "shortmax-stream" or "/e/m/", so joyreels was returned as
            // alive WITHOUT a request; zero "emit SKIP" lines appeared in the whole log.
            // So: probe every Narto-hosted stream, and log the status so the reason is visible.
            fun isAlive(u: String): Boolean {
                val host = u.substringAfter("//").substringBefore("/").lowercase()
                if (!host.endsWith("narto-drama.com")) return true   // tiktok/akamai/etc: leave alone
                val isProxy = u.contains("/e/m/")
                // A proxy answers 200 even when the src behind it is "link expired", so read the
                // body and look for the marker instead of trusting the status code.
                val probeBody = isProxy
                // probeBodyForBody = read enough text to look for an expiry marker inside it.
                val readBytes = if (probeBody) 256 else 1
                // MEASURED 2026-10-03 from the phone: a joyreels-stream token answers DIFFERENTLY
                // depending on the Range header, and that difference is the whole bug:
                //     with    "Range: bytes=0-1"  -> HTTP 403  "joyreels-edge: invalid token"
                //     without any Range header  -> HTTP 410  "joyreels-edge: link expired"
                // So the old bytes=0-1 probe made a DEAD link look broken-in-the-probe (FileNotFound)
                // and the player — which sends no Range — got the real 410 and errored. i.e. we
                // were judging liveness on a request shape the player never makes. Probe exactly
                // what the player does: a bare GET, no Range.
                //
                // RE-MEASURED 2026-10-04: a LIVE joyreels token ignores Range outright — both shapes
                // return HTTP 200 application/vnd.apple.mpegurl, 6807 bytes, in the same ~610 ms. So
                // the header bought nothing on a good link and inverted the verdict on a bad one.
                // `useRange = !probeBody` therefore still sent a Range on every DIRECT url — the
                // exact shape the 2026-10-03 finding said to stop using. Only the /e/m/ proxy
                // body-probe ever needs one (it must read text, not stream bytes).
                val useRange = probeBody
                deadLinkCache[u]?.let { at ->
                    if (System.currentTimeMillis() - at < DEAD_LINK_TTL_MS) {
                        lastProbeWhy = "cached dead"
                        // A remembered verdict is still a verdict, not an inability to tell.
                        deadByVerdict = true
                        return false
                    }
                }
                return try {
                    val httpConn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    httpConn.apply {
                        requestMethod = "GET"
                        setRequestProperty("Referer", nartoOrigin)
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                        if (useRange) setRequestProperty("Range", "bytes=0-$readBytes")
                        connectTimeout = 3000
                        readTimeout = 2500
                        instanceFollowRedirects = true
                    }
                    val code = httpConn.responseCode
                    if (code !in 200..399) {
                        lastProbeWhy = "HTTP $code"
                        deadByVerdict = true
                        deadLinkCache[u] = System.currentTimeMillis()
                        return false
                    }
                    if (probeBody) {
                        val body = httpConn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                        val expired = body.contains("link expired") || body.contains("invalid token")
                        if (expired) {
                            lastProbeWhy = "body says link expired"
                            deadByVerdict = true
                            deadLinkCache[u] = System.currentTimeMillis()
                        }
                        !expired
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    // A dead TLS cert lands here as SSLHandshakeException — that is how
                    // cdn.narto-drama.com gets caught, so name it in the log rather than
                    // reporting a bare "dead token".
                    lastProbeWhy = "${e.javaClass.simpleName}: ${e.message?.take(60) ?: "-"}"
                    // Split the two exception families, because they mean opposite things.
                    // A BAD CERTIFICATE is a verdict about the link: the bytes are there (the
                    // same host serves a 26MB mp4 over a relaxed handshake — measured
                    // 2026-10-04) but every strict client, ExoPlayer included, refuses it, so
                    // emitting it guarantees "Source error". A DNS/TIMEOUT failure says nothing
                    // about the link and must not be treated as a verdict — that case exists to
                    // let the last-resort branch hand the player a possibly-live URL.
                    if (e is javax.net.ssl.SSLHandshakeException) deadByVerdict = true
                    deadLinkCache[u] = System.currentTimeMillis()
                    false
                }
            }

            // Register an already-probed, already-vetted link. Split out of emit() so the parallel
            // quality probes can all finish BEFORE any registration, keeping «كامل» first and
            // the quality order the user picked.
            suspend fun emitNow(u: String, label: String, q: String) {
                if (u.isBlank() || u in emitted) return
                emitted.add(u)
                val type = inferStreamType(u)
                collected.add(
                    newExtractorLink(source = name, name = label, url = u, type = type) {
                        referer = nartoOrigin
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to nartoOrigin)
                    }
                )
                any = true
            }

            suspend fun emit(u: String, label: String, q: String) {
                // Dedup on the links we actually ACCEPT, not on every URL we merely looked at.
                // `emitted.add(u)` used to run BEFORE the probe, so a URL that failed the probe
                // was marked as seen for the rest of loadLinks and could never be retried — which
                // silently disabled every fallback that tried the same URL again.
                if (u.isBlank() || u in emitted) return
                val host = u.substringAfter("//").substringBefore("/").substringBefore(":").lowercase()
                if (DEAD_HOST_PATTERNS.any { host.contains(it) }) {
                    skippedDead++
                    android.util.Log.e("NartoDrama", "emit SKIP dead host $host ($label)")
                    return
                }
                if (!isAlive(u)) {
                    skippedDead++
                    android.util.Log.e("NartoDrama", "emit SKIP dead link $host ($label) why=$lastProbeWhy")
                    return
                }
                emitNow(u, label, q)
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

            // The authoritative quality for a multi_resolutions entry is the API's own
            // `resolution`/`label` field. Measured 2026-10-02: the shortmax token no longer
            // carries the quality in its path (it is .../{token}/main.m3u8, no _720p and no
            // query), so reading quality off the path mislabelled all three as 480p.
            fun qualityOfRes(r: NartoResolution): String {
                val lbl = r.label?.trim()?.takeIf { it.isNotBlank() }
                if (lbl != null && Regex("""\d{3,4}""").containsMatchIn(lbl)) return lbl
                val n = r.resolution ?: return "480p"
                return "${n}p"
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
                // ★ «إظهار رابط كامل» = مفعّل افتراضياً، أي سلوك اليوم حرفياً. فقط حين
                //   يُطفئه المستخدم نتخطّى بثّ رابط «كامل» — ولا نلمس روابط الجودات.
                val showFull = prefs?.getBoolean(NartoDramaSettingsBottomSheet.KEY_SHOW_FULL, true) != false
                if (!src.contains("/e/m/") && showFull) {
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
                // Derive the sibling qualities when the URL still carries `{uuid}_{q}/main.m3u8`.
                // MEASURED 2026-10-02: shortmax no longer uses this shape — it is now
                // `/{token}/main.m3u8` with no `_{q}` and no query, so this regex does not match
                // and we return below. That is harmless: shortmax's own qualities arrive as
                // distinct signed tokens in multi_resolutions and are emitted verbatim, each
                // labelled from the API's own `resolution` field. This block still serves any
                // other backend that keeps the older shape.
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
            // ★ «إظهار رابط كامل» — يُقرأ مرّة واحدة هنا: عند الإطفاء نتخطّى
            //   استخدام الروابط المباشرة (لأن موضع بثّها موسوم «كامل»)؛
            //   الافتراضي true = نفس حلقة اليوم حرفياً.
            val showFull = prefs?.getBoolean(NartoDramaSettingsBottomSheet.KEY_SHOW_FULL, true) != false
            for (u in if (showFull) directs else emptyList()) {
                if (directEmitted >= 2) break
                // v43: on slow CDNs (shortmax-stream) a 1080 master's big segments drain the
                // buffer as fast as it fills ("plays a bit then spins"). Prefer the 480 token so
                // the default "كامل" starts smooth; the multi_resolutions emissions below still
                // give 1080/720 to the quality picker.
                // MEASURED 2026-10-02 (re-confirmed): for shortmax the API's direct_play_url is on
                // cdn.narto-drama.com, whose certificate has EXPIRED — it is in DEAD_HOST_PATTERNS,
                // so emitting it as-is gives the player a guaranteed "Source error". Every quality
                // of the work lives in the signed shortmax-stream tokens instead, and those serve
                // fine. So when the API hands us a dead direct but the title has tokens, «كامل»
                // becomes a token. Pick the LOWEST one: the measured segments are 402KB @5s (480)
                // / 578KB (720) / 890KB (1080), and the 1080 drains the buffer faster than it
                // refills on a slow link ("plays a bit then spins"). The higher ones still reach
                // the user through the quality picker below.
                val shortmaxTokens = edge.multiResolutions.orEmpty()
                    .filter { it.streamUrl?.contains("shortmax-stream") == true && it.streamUrl.isNotBlank() }
                val needsToken = u.contains("cdn.narto-drama.com") || u.contains("shortmax-stream")
                val picked = if (needsToken && !u.contains("/e/m/")) {
                    shortmaxTokens.minWithOrNull(compareBy { it.resolution ?: 1080 })?.streamUrl
                } else null
                val pickedQ = picked?.let { pu -> shortmaxTokens.firstOrNull { it.streamUrl == pu } }
                    ?.let { qualityOfRes(it) }
                val before = emitted.size
                emit(picked ?: u, "كامل", pickedQ ?: proxyQuality(u))
                if (emitted.size > before) directOk++
                directEmitted++
            }
            if (directOk == 0 && showFull) {
                // MEASURED 2026-10-03: the API's direct token is frequently already 410, and the
                // quality list is NOT always populated (multi_resolutions is [] for many works).
                // So before giving up on «كامل», re-try the same direct URL once — the ingest
                // refreshes tokens on each call, and the proxy below is a weaker option than a
                // fresh token from the same source. We log the outcome either way so a truly
                // dead episode is distinguishable from one we simply could not refresh.
                val retry = directs.firstOrNull { it.isNotBlank() && it !in emitted }
                if (retry != null) {
                    val before = emitted.size
                    emit(retry, "كامل", proxyQuality(retry))
                    android.util.Log.e("NartoDrama", "loadLinks retry-direct alive=${emitted.size > before} url=${retry.take(60)}")
                    if (emitted.size > before) directOk++
                }
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
            // Probe every quality CONCURRENTLY, then emit in the API's own order.
            //
            // MEASURED 2026-10-03 from the phone: loadLinks took 12.3 s before playback even
            // started (fetchRefresh 12296ms + three serial probes at ~0.6-3 s each). A serial
            // probe spends its whole timeout budget on the FIRST dead token before the player
            // learns anything. The probes are independent — one HTTP request each, no shared
            // state — so run them together and emit afterwards in the order the API listed them,
            // which keeps «كامل» first and the quality picker in the user's chosen order.
            val resCandidates = edge.multiResolutions.orEmpty()
                .map { res -> res to res.streamUrl?.trim().orEmpty() }
                .filter { (_, su) -> su.isNotBlank() && !su.contains("/e/m/") }
            val probed = coroutineScope {
                resCandidates.map { (res, su) ->
                    async(Dispatchers.IO) {
                        // isAlive() annotates lastProbeWhy; that var is only meaningful for the
                        // single-URL paths, so a resolution probe just returns the verdict.
                        Triple(res, su, isAlive(su))
                    }
                }.awaitAll()
            }
            android.util.Log.e(
                "NartoDrama",
                "loadLinks probed ${probed.size} qualities in parallel slug=$slug ep=$ep dead=${probed.count { !it.third }}"
            )
            for ((res, su, ok) in probed) {
                if (!ok) {
                    skippedDead++
                    android.util.Log.e("NartoDrama", "emit SKIP dead resolution (${qualityOfRes(res)}) slug=$slug")
                    continue
                }
                val label = res.label?.trim()?.takeIf { it.isNotBlank() }
                    ?: "${res.resolution ?: 480}p"
                emitNow(su, label, qualityOfRes(res))
            }

            if (emitted.isEmpty()) {
                // Last resort: the API gave us at least one real URL — hand the player the raw
                // direct URL WITHOUT the isAlive probe (device DNS can be transiently flaky; a
                // dead token is better than "no links", and the player surfaces a clear error).
                //
                // MEASURED 2026-10-04 (adb logcat, slug lzl-lmkhtfy ep=1): this "last resort" is
                // exactly what produced the failure the user reported. The probe had just SKIPPED
                // that very URL as HTTP 410 (deadSkipped=3), and then this branch emitted it
                // UNPROBED anyway:
                //     emit SKIP dead link joyreels-stream.narto-drama.com (كامل) why=HTTP 410
                //     loadLinks no links survived probes — emitting raw API URL slug=lzl-lmkhtfy
                //     loadLinks DONE ... links=0 subs=0 deadSkipped=3 any=true
                // and the player immediately failed on it, three times:
                //     ExoPlaybackException: Source error
                //     Caused by: InvalidResponseCodeException: Response code: 410
                // So the probe learned the link was dead, and 400 lines later we handed the player
                // that same dead link with a comment claiming it beat "no links". It did not beat
                // it: the user sees an error screen, which IS "no links" plus a failure toast.
                //
                // Keep the escape hatch for what it was actually for — a probe that could not tell
                // (DNS/TLS blip on the device), where an exception was thrown rather than a status
                // returned. A STATUS means dead, and a dead link must not reach the player.
                android.util.Log.e(
                    "NartoDrama",
                    "loadLinks no links survived probes — deadByVerdict=$deadByVerdict " +
                        "why='$lastProbeWhy' emitRaw=${!deadByVerdict} slug=$slug"
                )
                val raw = if (deadByVerdict) null else
                    listOfNotNull(edge.directPlayUrl, edge.playUrl).firstOrNull { !it.isNullOrBlank() }
                if (!raw.isNullOrBlank()) {
                    val t = inferStreamType(raw)
                    collected.add(
                        newExtractorLink(source = name, name = "كامل", url = raw, type = t) {
                            referer = nartoOrigin
                            quality = getQualityFromName("480p")
                            headers = mapOf("Referer" to nartoOrigin)
                        }
                    )
                    any = true
                }
            }

            android.util.Log.e("NartoDrama", "loadLinks DONE slug=$slug ep=$ep links=${emitted.size} subs=${subTracks.size} deadSkipped=$skippedDead any=$any")
            // ★ المخرج الوحيد بعد نجاح المسار: بثّ كل ما جُمع (مرتَّباً كما اختار
            //   المستخدم) قبل العودة. كل `return false` أعلاه يحدث قبل أي emit
            //   فـ collected فارغ ولا بثّ مطلوب هناك إطلاقاً.
            emitSorted(prefs, collected, callback)
            any
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "loadLinks FATAL", e)
            // ★ حتى عند الخطأ: ما جُمع قبله يُبثّ (سلوك اليوم: الروابط التي سبقت
            //   الاستثناء كانت قد بُثّت أصلاً، فلا تضيع).
            emitSorted(prefs, collected, callback)
            false
        }
    }
}