package com.threesk.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

/**
 * ThreeSk (قصة عشق) — Turkish drama series, Arabic-subtitled/dubbed.
 *
 * Site: https://3iskk.xyz
 *
 * Flow:
 *  - Home / w-srs/ : series cards (type_item_box → data-image poster, title, href)
 *  - Search: /search/{term}/
 *  - Series detail: seasons-wrapper → ep-num[data-ep-num] href
 *  - Episode playback: 3-step POST chain:
 *      1. Ep page → form action (aa.3isk.icu/{rand}.php) + news value
 *      2. POST → middle page → myUrl + myInput.value
 *      3. POST → final page → iframe src = 3iskk.xyz/embed/{t}/{postId}/{type}/
 *  {t} = server index (1..N). Each t maps to a distinct player host:
 *    ukrcdn.club  → CF-free, direct m3u8 via /api/videos/{uuid}/playback
 *    miravd/mwdy  → Cloudflare-challenged → emit embed URL (WebView resolves)
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

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val defaultHeaders = mapOf("User-Agent" to UA, "Accept-Language" to "ar,en;q=0.9")

    // ── Card parsing (home / search / genre) ──────────────────────────────
    private fun parseCards(doc: org.jsoup.nodes.Document): List<SearchResponse> =
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
        return try {
            val url = "$mainUrl${request.data.trimEnd('/')}/page/$page/"
            val doc = app.get(url, referer = mainUrl, headers = defaultHeaders).document
            val list = parseCards(doc)
            if (list.isEmpty()) null else newHomePageResponse(request.name, list)
        } catch (_: Exception) { null }
    }

    // ── search ────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val url = "$mainUrl/search/${java.net.URLEncoder.encode(query.trim(), "UTF-8")}/"
            val doc = app.get(url, referer = mainUrl, headers = defaultHeaders).document
            parseCards(doc)
        } catch (_: Exception) { emptyList() }
    }

    // ── load (series detail → episodes) ───────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl, headers = defaultHeaders).document

            val title = doc.selectFirst("h1.title, .title")?.text()
                ?: doc.title().substringBefore("موقع").trim()

            val poster = doc.selectFirst(".poster-wrapper img")?.let {
                it.attr("data-image").ifBlank { it.attr("src") } }
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

            if (episodes.isEmpty()) null
            else newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot      = description
            }
        } catch (_: Exception) { null }
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

        return try {
            // Step 1: episode page → gateway form (⚠️ must be aa.3isk.icu, NOT the search box 3iskk.xyz/)
            val epHtml     = app.get(url, referer = mainUrl, headers = defaultHeaders).text
            val doc        = Jsoup.parse(epHtml)
            val formAction = doc.selectFirst("form[action*=\"aa.3isk.icu\"]")?.attr("action") ?: return false
            val newsValue  = doc.selectFirst("input[name=news]")?.attr("value") ?: return false

            // Step 2: POST → middle page
            val middleHtml = app.post(formAction,
                data     = mapOf("news" to newsValue, "u" to "", "submit" to "submit"),
                referer  = url,
                headers  = defaultHeaders + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
                )).text

            val myUrl   = Regex("""var myUrl\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: return false
            val inputVl = Regex("""myInput\.value\s*=\s*"([^"]+)"""").find(middleHtml)?.groupValues?.get(1) ?: return false

            // Step 3: POST → final page with embed iframe 3iskk.xyz/embed/{t}/{pid}/{type}/
            val finalHtml = app.post(myUrl,
                data     = mapOf("news" to inputVl, "u" to "", "submit" to "submit"),
                referer  = formAction,
                headers  = defaultHeaders + mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8"
                )).text

            // Parse the embed URL → postid + type. Server index is 1..N (unused indices return an empty-src iframe).
            val embedM = Regex("""(?:https?://[\w.-]+)?/embed/\d+/(\d+)/(\d+)/""")
                .find(finalHtml) ?: return false
            val postId = embedM.groupValues[1]
            val typeId = embedM.groupValues[2]

            var emitted = 0
            val seenHost = mutableSetOf<String>()

            // Enumerate servers: probe embed/{t}/{pid}/{type} for each t → resolve player host → emit.
            // Servers are 1-indexed and contiguous; unused indices return an empty-src iframe (skipped).
            for (t in 1..6) {
                val embedUrl  = "$mainUrl/embed/$t/$postId/$typeId/"
                val embedHtml = try {
                    app.get(embedUrl, referer = url, headers = defaultHeaders).text
                } catch (_: Exception) { continue }

                // Real player page (inside embed's iframe): miravd.com/..., mwdy.cc/..., ukrcdn.club/e/{uuid}
                val playerUrl = Regex("""<iframe[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(embedHtml)?.groupValues?.get(1) ?: continue
                val host = try { java.net.URL(playerUrl).host } catch (_: Exception) { continue }
                if (host.isBlank() || !seenHost.add(host)) continue

                when {
                    // ── ukrcdn.club: CF-free direct m3u8 ───────────────────────
                    host.contains("ukrcdn") -> {
                        if (resolveUkrcdn(playerUrl, epLabel, callback)) emitted++
                    }
                    // ── miravd / mwdy and other CF-challenged hosts: emit embed URL,
                    //    CloudStream's internal WebView executes the CF-JS → reaches player
                    else -> {
                        callback(newExtractorLink(name, "سيرفر ${seenHost.size}$epLabel", embedUrl, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.headers = mapOf(
                                "User-Agent" to UA,
                                "Referer" to "$mainUrl/"
                            )
                        })
                        emitted++
                    }
                }
            }
            emitted > 0
        } catch (_: Exception) { false }
    }

    /**
     * ukrcdn.club → direct master.m3u8 (CF-free).
     * Page https://ukrcdn.club/e/{uuid} → extract playback API URL from inline script →
     * GET https://ukrcdn.club/api/videos/{uuid}/playback?g={token} → JSON {"url": master.m3u8}.
     */
    private suspend fun resolveUkrcdn(
        pageUrl: String,
        epLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = try {
            app.get(pageUrl, referer = mainUrl, headers = defaultHeaders).text
        } catch (_: Exception) { return false }
        val apiUrl = Regex("""https?://[^"\s]+/api/videos/[^"\s]+/playback[^"\s]*""")
            .find(page)?.value?.replace("\\/", "/") ?: return false
        val json = try {
            app.get(apiUrl, referer = pageUrl, headers = defaultHeaders).text
        } catch (_: Exception) { return false }
        val master = Regex(""""url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return false

        callback(newExtractorLink(name, "سيرفر ukrcdn$epLabel · HLS", master, ExtractorLinkType.M3U8) {
            this.referer = "https://ukrcdn.club/"
            this.headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://ukrcdn.club/"
            )
        })
        return true
    }
}
