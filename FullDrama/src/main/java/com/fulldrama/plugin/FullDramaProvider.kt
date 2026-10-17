package com.fulldrama.plugin

import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val FD_MAIN = "https://www.full-drama.com"

class FullDramaProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = FD_MAIN
    override var name = "Full Drama"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val HOME_KEY = "__home__"

    override val mainPage = mainPageOf(
        HOME_KEY to "الأحدث"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = mainUrl
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        doc.select("a[href*='/detail/']").forEach { a ->
            val href = a.attr("href")
            if (href.isEmpty()) return@forEach
            val abs = if (href.startsWith("http")) href else "$mainUrl$href"
            val img = a.select("img").attr("src").ifEmpty { a.select("img").attr("data-src") }
            val title = a.select("h3, h2, .card-title, .title").text().ifEmpty { a.text() }.trim()
            if (title.isEmpty() || !href.contains("/detail/")) return@forEach
            items.add(newTvSeriesSearchResponse(title, abs, TvType.TvSeries) {
                this.posterUrl = img
            })
        }
        return newHomePageResponse(listOf(HomePageList("الأحدث", items.distinctBy { it.url }.take(60))), true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$q"
        val doc = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        doc.select("a[href*='/detail/']").forEach { a ->
            val href = a.attr("href")
            if (href.isEmpty()) return@forEach
            val abs = if (href.startsWith("http")) href else "$mainUrl$href"
            val img = a.select("img").attr("src").ifEmpty { a.select("img").attr("data-src") }
            val title = a.select("h3, h2, .card-title, .title").text().ifEmpty { a.text() }.trim()
            if (title.isEmpty()) return@forEach
            items.add(newTvSeriesSearchResponse(title, abs, TvType.TvSeries) {
                this.posterUrl = img
            })
        }
        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("h1").text().ifEmpty { doc.title() }.trim()
        val poster = doc.select("meta[property=og:image], img[alt*=poster], .xr-hero-poster, .poster img").attr("src").ifEmpty { doc.select("img").firstOrNull()?.attr("src") ?: "" }
        val synopsis = doc.select("meta[property=og:description], .description, .synopsis, p").firstOrNull()?.text()?.trim() ?: ""

        val episodes = mutableListOf<Episode>()
        doc.select("a.xr-episode-btn, .xr-episode-btn a, .episode-btn, [class*='episode']").forEach { a ->
            val href = a.attr("href").ifEmpty { a.attr("data-href") }
            if (href.isEmpty()) return@forEach
            val numStr = href.substringAfter("?ep=", "").substringBefore("&")
            val epNum = numStr.filter { it.isDigit() }.toIntOrNull() ?: (a.text().filter { it.isDigit() }.toIntOrNull() ?: 1)
            val epUrl = if (href.startsWith("http")) href else "$mainUrl$href"
            episodes.add(newEpisode(epUrl) {
                this.name = "الحلقة $epNum"
                this.episode = epNum
            })
        }
        if (episodes.isEmpty()) {
            doc.select("a[href*='?ep=']").forEach { a ->
                val href = a.attr("href")
                if (href.isEmpty()) return@forEach
                val numStr = href.substringAfter("?ep=", "").substringBefore("&")
                val epNum = numStr.filter { it.isDigit() }.toIntOrNull() ?: 1
                val epUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                episodes.add(newEpisode(epUrl) {
                    this.name = "الحلقة $epNum"
                    this.episode = epNum
                })
            }
        }
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "الحلقة 1"
                this.episode = 1
            })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val html = doc.html()
        // Extract mp4 URL (handle escaped & in HTML)
        val mp4Regex = Regex("https://cdn\\.full-drama\\.com/[A-Za-z0-9_\\-]+\\.mp4\\?exp=\\d+(?:\\\\u0026sig=[0-9a-f]+)?(?:\\\\u0026media_type=[a-z_0-9]+)?")
        var match = mp4Regex.find(html)
        if (match == null) {
            val mp4Regex2 = Regex("https://cdn\\.full-drama\\.com/[A-Za-z0-9_\\-]+\\.mp4\\?exp=\\d+(?:&sig=[0-9a-f]+)?(?:&media_type=[a-z_0-9]+)?")
            match = mp4Regex2.find(html)
        }
        if (match != null) {
            val raw = match.value.replace("\\u0026", "&")
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "MP4",
                    url = raw,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
        return false
    }
}
