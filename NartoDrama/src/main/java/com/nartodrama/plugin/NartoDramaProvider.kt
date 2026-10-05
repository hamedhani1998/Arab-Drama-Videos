package com.nartodrama.plugin

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import nartoshared.UA
import nartoshared.loadNartoLinks
import nartoshared.NartoFetch

private const val NARTO_HOST = "https://narto-drama.com"
private const val BRW_HOST = "https://edge.narto-drama.com"

// Standalone "Narto Drama" extension, pinned to the apex host for playback. v46: this provider
// carries its OWN domain — all links/cards/detail/refresh URLs use the apex host
// https://narto-drama.com (per user: "اجعل كل مصدر يحمل الرابط الخاص به"). Browsing HTML
// (home "/" and "/search") is fetched from the live edge host (BRW_HOST) because apex
// front-ends those routes with a Cloudflare JS challenge; only refresh/playback/detail go to
// apex (verified live: detail 200/82eps, refresh {"ok":true,"play_url":"stream-e1..."}).
// Still 100% independent of the Edge source.
//
// Every playback decision — refresh-source, stale retry, cooldown, liveness probe, link
// emission — lives in ../../narto-shared, so a fix here cannot be missing from Edge Narto
// Drama. That drift happened for the whole life of these two sources and was invisible.

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

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

    // The apex host first, edge as the network-failure fallback (see the class comment).
    private val fetch = NartoFetch(listOf(NARTO_HOST, BRW_HOST), nartoOrigin, "NartoDrama")

    // Per-tab in-memory cache so re-entering / tab-hopping serves the list instantly instead of
    // re-fetching the heavy /search page.
    private val searchCache = HashMap<String, String>()

    // Cached edge home "/" feed — the guaranteed never-blank fallback (verified live: it always
    // returns the content row, unlike a single /search which can fail or 520). Poster images are
    // absolute (img.nartodrama-api.online) and cards carry apex detail URLs, so the fallback
    // renders identically under any tab name.
    private var homeFeedCache: String? = null

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
            val html = app.get(
                "$BRW_HOST/search?lang=ar-SA&q=$urlEncQ&page=1&perPage=12",
                referer = nartoOrigin,
                headers = mapOf("User-Agent" to UA)
            ).text
            if (html.contains("\"@type\":\"ListItem\"")) {
                searchCache[q] = html
                return html
            }
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search fetch error", e)
        }
        return null
    }

    // The edge home "/" feed (Block-1 CollectionPage ListItems), cached.
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
            // Fire the 3 OTHER category feeds + the home feed in parallel while the requested
            // tab's own feed loads, so tab-hopping hits the cache instead of re-fetching a
            // 4-6s /search page.
            val warm = coroutineScope {
                val targets = homeSections.filter { it != q } + "/*home*/"
                targets.map { warmQ ->
                    async { if (warmQ == "/*home*/") fetchHomeFeed() else fetchSearch(warmQ) }
                }.awaitAll()
            }
            android.util.Log.e("NartoDrama", "getMainPage q=$q warm=${warm.count { it != null }}")

            var html = fetchSearch(q)
            var fromFallback = false
            if (html == null || parseSearchItems(html).isEmpty()) {
                // Tab's own feed failed/empty — never blank the screen: serve the shared home
                // feed instead (always present).
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
        val poster = image?.let { if (it.startsWith("http")) it else NARTO_HOST + it }
        return newTvSeriesSearchResponse(name, "$NARTO_HOST/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(url)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: NARTO_HOST
            var doc: org.jsoup.nodes.Document? = null
            var attempt = 0
            // v2 (perf): no retry-sleep punishment. The detail page is server-bound: the HTML
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = loadNartoLinks(
        api = this,
        prefs = prefs,
        origin = nartoOrigin,
        tag = "NartoDrama",
        showFullKey = NartoDramaSettingsBottomSheet.KEY_SHOW_FULL,
        fetch = fetch,
        data = data,
        subtitleCallback = subtitleCallback,
        callback = callback,
    )
}