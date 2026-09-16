package com.threesk.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * ThreeSk (قصة عشق) — Turkish drama series, Arabic-subtitled/dubbed.
 *
 * Site: https://3iskk.xyz
 *
 * Flow:
 *  - Home / w-srs/ : series cards (type_item_box → data-image poster, title, href)
 *  - Search: ?s={query}
 *  - Series detail: seasons-wrapper → ep-num[data-ep-num] href
 *  - Episode playback: 3-step POST chain:
 *      1. Ep page → form action (aa.3isk.icu/{rand}.php) + news value
 *      2. POST → middle page → myUrl + myInput.value
 *      3. POST → final page → iframe src = 3iskk.xyz/embed/1/{postId}/{ep}/
 *  embed page contains iframe to Cloudflare-protected player (mwdy.cc).
 *  We emit the 3iskk.xyz/embed URL; CloudStream resolves via its internal WebView.
 */
class ThreeSk : MainAPI() {
    override var name      = "قصة عشق"
    override var mainUrl   = "https://3iskk.xyz"
    override var hasMainPage = true
    override var lang      = "ar"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // ── mainPage sections ──────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "مسلسلات"         to "/w-srs/",
        "مدبلجة"         to "/genre/series-mudablij-121/",
        "أحدث الحلقات"   to "/w-hlqat/",
        "أفلام"           to "/w-mvs/",
    )

    // ── SSL bypass (site has expired cert on aa.3isk.icu) ─────────────────
    private val unsafeSSL: SSLContext? by lazy {
        try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
            sc
        } catch (_: Exception) { null }
    }

    private fun setupConn(conn: HttpURLConnection) {
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        conn.setRequestProperty("Accept-Language", "ar,en;q=0.9")
        conn.connectTimeout  = 20_000
        conn.readTimeout     = 20_000
    }

    /** GET with SSL bypass */
    private fun safeGet(url: String, referer: String? = null): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        setupConn(conn)
        if (referer != null) conn.setRequestProperty("Referer", referer)
        if (url.startsWith("https://") && unsafeSSL != null)
            (conn as? javax.net.ssl.HttpsURLConnection)?.sslSocketFactory = unsafeSSL!!.socketFactory
        val code = conn.responseCode
        (if (code in 200..399) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
    } catch (_: Exception) { null }

    /** POST with SSL bypass */
    private fun safePost(url: String, data: Map<String,String>, referer: String? = null): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        setupConn(conn)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        if (referer != null) conn.setRequestProperty("Referer", referer)
        if (url.startsWith("https://") && unsafeSSL != null)
            (conn as? javax.net.ssl.HttpsURLConnection)?.sslSocketFactory = unsafeSSL!!.socketFactory
        val body = data.entries.joinToString("&") { (k,v) ->
            "${URLEncoder.encode(k,"UTF-8")}=${URLEncoder.encode(v,"UTF-8")}" }
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        (if (code in 200..399) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }
    } catch (_: Exception) { null }

    // ── Card parsing (home / search / genre) ──────────────────────────────
    private fun parseCards(doc: Document): List<SearchResponse> =
        doc.select("li.type_item_box a.type_item").mapNotNull { a ->
            val href  = a.attr("href").ifBlank { return@mapNotNull null }
            val title = a.attr("title").ifBlank {
                a.selectFirst(".item_title")?.text()
            } ?: return@mapNotNull null
            val poster = a.selectFirst("img")?.let {
                it.attr("data-image").ifBlank { it.attr("src") } }
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

    // ── getMainPage ───────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = request.data.trimEnd('/') + "/page/$page/"
        val url  = "$mainUrl$path"
        val doc  = Jsoup.parse(safeGet(url, mainUrl) ?: return null)
        return newHomePageResponse(request.name, parseCards(doc))
    }

    // ── search ────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        // Canonical path — the site redirects both `?s=` and `/search/` here.
        val url = "$mainUrl/search/${URLEncoder.encode(query.trim(), "UTF-8")}/"
        val doc = Jsoup.parse(safeGet(url, mainUrl) ?: return emptyList())
        return parseCards(doc)
    }

    // ── load (series detail → episodes) ───────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val html = safeGet(url, mainUrl) ?: return null
        val doc  = Jsoup.parse(html)

        val title = doc.selectFirst("h1.title, .title")?.text()
            ?: doc.title().substringBefore("موقع").trim()

        val poster = doc.selectFirst(".poster-wrapper img")?.let {
            it.attr("src").ifBlank { it.attr("data-image") } }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        val description = doc.selectFirst(".description")?.text()

        // Seasons → episodes
        val episodes = mutableListOf<Episode>()
        doc.select(".seasons-wrapper .seasons-selection ul li[data-value]").forEach { seasonLi ->
            val seasonNum = seasonLi.attr("data-value").toIntOrNull() ?: return@forEach
            doc.select("#season-num-$seasonNum a.ep-num").forEach { a ->
                val epUrl = a.attr("href")
                val epNum = a.attr("data-ep-num").toIntOrNull()
                    ?: Regex("episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                val epTitle = a.selectFirst(".cl_srt")?.text()
                    ?: a.attr("title").ifBlank { null }
                    ?: "الحلقة $epNum"
                if (epUrl.isNotBlank())
                    episodes.add(newEpisode(epUrl) {
                        this.name     = epTitle
                        this.season   = seasonNum
                        this.episode  = epNum
                    })
            }
        }
        // Fallback: no seasons-wrapper → treat all as season 1
        if (episodes.isEmpty()) {
            doc.select("a.ep-num[href*=episodes]").forEach { a ->
                val epUrl = a.attr("href")
                val epNum = a.attr("data-ep-num").toIntOrNull()
                    ?: Regex("episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                val epTitle = a.selectFirst(".cl_srt")?.text()
                    ?: a.attr("title").ifBlank { null }
                    ?: "الحلقة $epNum"
                if (epUrl.isNotBlank())
                    episodes.add(newEpisode(epUrl) {
                        this.name     = epTitle
                        this.season   = 1
                        this.episode  = epNum
                    })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    // ── loadLinks ─────────────────────────────────────────────────────────
    // Episode page → 3-step POST chain → final iframe 3iskk.xyz/embed/{t}/{pid}/{type}/
    //  {t} = server index. Each {t} maps to a distinct player host:
    //    ukrcdn.club  → CF-free, direct m3u8 via /api/videos/{uuid}/playback
    //    miravd/mwdy  → Cloudflare-challenged → emit embed URL (WebView resolves)
    // We enumerate {t}=1..N and emit one link per server that resolves.
    override suspend fun loadLinks(
        url: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val qa = Regex("""(?:season-(\d+))?.*?episode-(\d+)(?:/|$)""").find(url)
        val epLabel = if (qa != null && qa.groupValues[2].isNotBlank()) " EP${qa.groupValues[2]}" else ""

        // Step 1: episode page → gateway form (⚠️ must be aa.3isk.icu, the search box is 3iskk.xyz/)
        val epHtml     = safeGet(url, mainUrl) ?: return false
        val doc        = Jsoup.parse(epHtml)
        val formAction = doc.selectFirst("form[action*=\"aa.3isk.icu\"]")?.attr("action") ?: return false
        val newsValue  = doc.selectFirst("input[name=news]")?.attr("value")     ?: return false

        // Step 2: POST → middle page
        val middleHtml = safePost(formAction,
            mapOf("news" to newsValue, "u" to "", "submit" to "submit"), url) ?: return false

        val myUrl   = Regex("""var myUrl\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: return false
        val inputVl = Regex("""myInput\.value\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: return false

        // Step 3: POST → final page with embed iframe 3iskk.xyz/embed/{t}/{pid}/{type}/
        val finalHtml = safePost(myUrl,
            mapOf("news" to inputVl, "u" to "", "submit" to "submit"), formAction) ?: return false

        // Parse the embed URL → postid + type. Server index is 1..N (unused indices 404).
        val embedM = Regex("""(?:https?://[\w.-]+)?/embed/\d+/(\d+)/(\d+)/""")
            .find(finalHtml) ?: return false
        val postId = embedM.groupValues[1]
        val typeId = embedM.groupValues[2]

        var emitted = 0
        val seenHost = mutableSetOf<String>()

        // Enumerate servers: probe embed/{t}/{pid}/{type} for each t → resolve player host → emit.
        // Servers are 1-indexed and contiguous; unused indices return an empty-src iframe (skipped).
        for (t in 1..6) {
            val embedUrl = "$mainUrl/embed/$t/$postId/$typeId/"
            val embedHtml = safeGet(embedUrl, url) ?: continue

            // Real player page (inside embed's iframe): miravd.com/..., mwdy.cc/..., ukrcdn.club/e/{uuid}
            val playerUrl = Regex("""<iframe[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(embedHtml)?.groupValues?.get(1) ?: continue
            val host = try { URL(playerUrl).host } catch (_: Exception) { continue }
            if (host.isBlank() || !seenHost.add(host)) continue

            when {
                // ── ukrcdn.club: CF-free direct m3u8 ───────────────────────
                host.contains("ukrcdn") -> {
                    resolveUkrcdn(playerUrl, epLabel, callback)?.let { emitted++ }
                }
                // ── miravd / mwdy and other CF-challenged hosts: emit embed URL,
                //    CloudStream's internal WebView executes the CF-JS → reaches player
                else -> {
                    callback(newExtractorLink(name, "سيرفر ${seenHost.size}$epLabel", embedUrl, ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to "$mainUrl/"
                        )
                    })
                    emitted++
                }
            }
        }
        return emitted > 0
    }

    /**
     * ukrcdn.club → direct master.m3u8 (CF-free).
     * Page https://ukrcdn.club/e/{uuid} → extract `g=` token from inline fetch() script →
     * GET https://ukrcdn.club/api/videos/{uuid}/playback?g={token} → JSON {"url": master.m3u8}.
     */
    private suspend fun resolveUkrcdn(
        pageUrl: String,
        epLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = safeGet(pageUrl, mainUrl) ?: return false
        val apiUrl = Regex("""https?://[^"\s]+/api/videos/[^"\s]+/playback[^"\s]*""")
            .find(page)?.value?.replace("\\/", "/") ?: return false
        val master = safeGet(apiUrl, pageUrl)?.let { json ->
            Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
        } ?: return false

        callback(newExtractorLink(name, "سيرفر ukrcdn$epLabel · HLS", master, ExtractorLinkType.M3U8) {
            this.referer = "https://ukrcdn.club/"
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "https://ukrcdn.club/"
            )
        })
        return true
    }
}
