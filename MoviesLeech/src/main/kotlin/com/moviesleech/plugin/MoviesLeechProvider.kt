package com.moviesleech.plugin

import cloudstreamshared.DriveChain
import cloudstreamshared.MediaProbe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * MoviesLeech — `moviesleech.media` (ووردبريس، قالبٌ شبيه بـMoviesMod).
 *
 * بنية الموقع (مقيسة 2026-10):
 *  - صفحة العمل: عنوان `<h3>` لكل جودة (`… 480p [500MB]`) تحته زرّ
 *    `maxbutton` نصّه «Download Links» يقود إلى `leechpro.blog/archives/<id>`.
 *  - صفحة الأرشيف: `✅ Fast Server (G-Drive)` رابطُه
 *    `cloud.unblockedgames.world/?sid=…` (وبقيّة الأزرار `#` بلا روابط).
 *  - البوّابة: `landing(_wp_http)` ← `landing(_wp_http2+token)` ← صفحة تحقّق
 *    تُضبط فيها كعكة `s_343('pepe-…',…)` ثم `?go=pepe-…` ← `driveseed.org/r?…`
 *    ← `/file/` أو `/list/` ← المرايا.
 *  وكل خطوةٍ تُقرأ من الصفحة نفسها داخل [DriveChain]، لا من ثوابت.
 */
class MoviesLeechProvider : MainAPI() {

    override var mainUrl = "https://moviesleech.media"
    override var name = "MoviesLeech"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val head get() = mapOf("User-Agent" to DriveChain.UA)

    override val mainPage = mainPageOf(
        "" to "أحدث الإضافات",
        "/movies/" to "أفلام",
        "/movies/hindi-movies/" to "أفلام هندية",
        "/movies/bollywood/" to "بوليوود",
        "/movies/south-movies/" to "جنوب الهند",
        "/movies/tamil-movies/" to "تاميل",
        "/movies/telugu-movies/" to "تيلوغو",
        "/movies/punjabi-movies/" to "بنجابية",
        "/movies/marathi-movies/" to "ماراثي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> mainUrl + request.data
            request.data.isBlank() -> "$mainUrl/page/$page/"
            else -> mainUrl + request.data + "page/$page/"
        }
        val doc = app.get(url, headers = head).document
        val items = doc.select("article.latestPost").mapNotNull { cardToSearch(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, headers = head).document
        val items = doc.select("article.latestPost").mapNotNull { cardToSearch(it) }
            .distinctBy { it.url }
        return items.ifEmpty { null }
    }

    /** بطاقة `<article class="latestPost">` — نفس قالب MoviesMod. */
    private fun cardToSearch(card: Element): SearchResponse? {
        val a = card.selectFirst("h2.title a") ?: card.selectFirst("a[href]") ?: return null
        val href = a.attr("href")
        if (href.isBlank() || href.contains("/category/")) return null
        val title = a.text().trim()
            .replace(Regex("^Download\\s+"), "")
            .substringBefore("||")
            .trim()
        if (title.isBlank()) return null
        val poster = card.selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.ifBlank { null }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = head).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("||")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        // عنوان الجودة ثم أوّل زرّ أرشيفٍ يليه (حتى عنوان الجودة التالي).
        val scope = doc.selectFirst("div.entry-content") ?: doc.body()
        val pairs = ArrayList<Pair<String, String>>()
        var qualityText = ""
        for (n in scope.select("h3, a[href*=leechpro]")) {
            if (n.tagName() == "h3") {
                qualityText = n.text().trim()
            } else {
                val href = n.attr("href")
                if (href.contains("/archives/") && pairs.none { it.second == href }) {
                    pairs.add(qualityText to href)
                }
            }
        }
        if (pairs.isEmpty()) return null

        val eps = pairs.map { (q, href) ->
            val quality = DriveChain.qualityOf(q)
            // اسم السطر قصير (`480p`) والوسم يُبنى من الجودة وحدها، فلا يتكرّر
            // عنوان الصفحة الطويل في كل رابط.
            val short = Regex("\\b(\\d{3,4}p)\\b", RegexOption.IGNORE_CASE)
                .find(q)?.groupValues?.get(1)
                ?: q.ifBlank { "Download" }
            newEpisode("$href\t\t$quality") {
                this.name = short
                this.season = 1
                this.episode = 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = doc.select("a[href*=movies-by-genre]").map { it.text().trim() }
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
            val archive = parts.getOrNull(0)?.trim().orEmpty()
            val heading = parts.getOrNull(1)?.trim()?.ifBlank { null }
            val quality = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
            if (archive.isBlank()) continue

            // صفحة الأرشيف تحمل روابط `?sid=` (واحدة حقيقية والباقي `#`).
            val body = try {
                app.get(archive, headers = head).text
            } catch (e: Exception) {
                ""
            }
            val sids = Regex("href=\"(https?://[^\"]+\\?sid=[^\"]+)\"")
                .findAll(body)
                .map { DriveChain.unescapeText(it.groupValues[1]) }
                .distinct()
                .toList()

            val entries = if (sids.isEmpty()) listOf(archive) else sids
            for (entry in entries.take(3)) {
                for (l in DriveChain.resolve(entry, heading, quality)) {
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
        }
        return emitted > 0
    }
}
