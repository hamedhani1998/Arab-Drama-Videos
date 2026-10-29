package com.animeflix.plugin

import cloudstreamshared.DriveChain
import cloudstreamshared.MediaProbe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * AnimeFlix — `animeflix.dad` (يُحوِّل إلى `animeflix.my`).
 *
 * بنية الموقع (مقيسة 2026-10):
 *  - صفحة العمل: بطاقات جودة `<div class="dl-card"><span class="q">720p</span>`
 *    وزرّ `Gdrive + Mirrors` يقود إلى `episodes.animeflix.my/archives/<id>`.
 *  - صفحة الأرشيف: عنوانٌ لكل حلقة `<h3><a href="…/getlink/…">Episode N</a></h3>`.
 *  - رابط `/getlink/` يردّ 302 مباشرةً إلى `driveseed.org/r?…` ثم `/file/`،
 *    فتُقرأ مراياه (‎r2.dev / video-gen / workers.dev‎) بمحرّك [DriveChain].
 *
 * لهذا لا يوجد هنا أي «بوّابة» نماذج كما في MoviesMod: الموقع يعطي الرابط
 * النهائي بعد تحويلةٍ واحدة.
 */
class AnimeFlixProvider : MainAPI() {

    override var mainUrl = "https://animeflix.my"
    override var name = "AnimeFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val head get() = mapOf("User-Agent" to DriveChain.UA)

    override val mainPage = mainPageOf(
        "" to "أحدث الإضافات",
        "/category/latest-release/" to "أحدث الإصدارات",
        "/category/ongoing/" to "مستمر",
        "/category/movies/" to "أفلام",
        "/category/genre/action/" to "أكشن",
        "/category/genre/fantasy/" to "فانتازيا",
        "/category/genre/comedy/" to "كوميديا",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> mainUrl + request.data
            request.data.isBlank() -> "$mainUrl/page/$page/"
            else -> mainUrl + request.data + "page/$page/"
        }
        val doc = app.get(url, headers = head).document
        val items = doc.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, headers = head).document
        val items = doc.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }
        return items.ifEmpty { null }
    }

    /** بطاقة `<a class="card" href="…/download-…">` في الصفحة الرئيسية/البحث. */
    private fun cardToSearch(card: Element): SearchResponse? {
        val href = card.attr("href").ifBlank { card.selectFirst("a")?.attr("href").orEmpty() }
        if (!href.contains("/download-")) return null
        val raw = card.selectFirst("span.card-t")?.text()?.trim()
            ?: card.attr("title").replace(Regex("^Download\\s+"), "").substringBefore("||").trim()
        if (raw.isBlank()) return null
        val poster = card.selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.ifBlank { null }
        return newTvSeriesSearchResponse(raw, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = head).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("main img")?.attr("src")?.ifBlank { null }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("p.clamp2")?.text()?.ifBlank { null }

        // بطاقات الجودة: `<span class="q">720p</span>` + زرّ الأرشيف.
        val cards = doc.select("div.dl-card").mapNotNull { c ->
            val q = c.selectFirst("span.q")?.text()?.trim().orEmpty()
            val href = c.selectFirst("a[href*=archives]")?.attr("href") ?: return@mapNotNull null
            q to href
        }
        if (cards.isEmpty()) return null

        // رقم الحلقة → (الجودة، عنوان الصفّ، رابط `/getlink/`).
        val byEp = LinkedHashMap<Int, MutableList<Triple<Int, String, String>>>()
        var hasEpisodes = false
        for ((q, href) in cards) {
            val quality = DriveChain.qualityOf(q)
            val body = try {
                app.get(href, headers = head).text
            } catch (e: Exception) {
                ""
            }
            val adoc = org.jsoup.Jsoup.parse(body)
            val links = adoc.select("a[href*=getlink]")
                .map { it.attr("href") to it.text().trim() }
                .distinctBy { it.first }
            if (links.isEmpty()) {
                byEp.getOrPut(0) { ArrayList() }
                    .add(Triple(quality, q.ifBlank { "Download" }, href))
            } else {
                hasEpisodes = true
                for ((g, t) in links) {
                    val n = DriveChain.epFromText(t) ?: 1
                    byEp.getOrPut(n) { ArrayList() }.add(Triple(quality, t, g))
                }
            }
        }
        if (byEp.isEmpty()) return null

        val eps = byEp.entries.sortedBy { it.key }.map { (n, list) ->
            val payload = list
                .sortedByDescending { it.first }
                .joinToString("\n") { (q, heading, href) -> "$href\t$heading\t$q" }
            newEpisode(payload) {
                this.name = if (hasEpisodes) "الحلقة $n" else list.firstOrNull()?.second ?: "Download"
                this.season = 1
                this.episode = if (hasEpisodes) n else 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = doc.select("a[href*=category/genre]").map { it.text().trim() }
                .filter { it.isNotBlank() }.distinct()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = 0
        for (line in data.split("\n")) {
            val parts = line.split("\t")
            val url = parts.getOrNull(0)?.trim().orEmpty()
            val heading = parts.getOrNull(1)?.trim()?.ifBlank { null }
            val quality = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            if (url.isBlank()) continue
            val links = DriveChain.resolve(url, heading, quality)
            for (l in links) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = MediaProbe.label(
                            l.server,
                            MediaProbe.qualityLabel(DriveChain.heightOf(l.quality)),
                            l.bytes,
                            l.url,
                            null,
                            l.note,
                            if (l.downloadOnly) MediaProbe.Use.DOWNLOAD else MediaProbe.Use.WATCH,
                        ),
                        url = l.url,
                    ) {
                        this.quality = l.quality
                        this.referer = l.referer ?: ""
                    }
                )
                emitted++
            }
        }
        return emitted > 0
    }
}
