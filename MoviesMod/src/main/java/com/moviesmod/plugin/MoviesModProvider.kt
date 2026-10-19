package com.moviesmod.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class MoviesModProvider : MainAPI() {
    override var mainUrl = "https://moviesmod.uk.com"
    override var name = "MoviesMod"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        val posts = document.select("article, .post, .latestPost, .entry")
        for (post in posts) {
            val link = post.selectFirst("a[href]")?.attr("href") ?: continue
            if (!link.contains("moviesmod.uk.com")) continue
            val title = post.selectFirst("h2, h3, .title, .entry-title")?.text()?.trim() ?: continue
            if (title.isEmpty()) continue
            val img = post.selectFirst("img")?.attr("src")
            val isTv = title.lowercase().contains("season") || title.lowercase().contains("series") ||
                       link.lowercase().contains("season") || link.lowercase().contains("series")
            items.add(
                if (isTv) {
                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = img
                    }
                } else {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = img
                    }
                }
            )
        }
        return newHomePageResponse(listOf(HomePageList("Latest", items.distinctBy { it.url })))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(url).document
        val items = mutableListOf<SearchResponse>()
        val posts = document.select("article, .post, .latestPost, .entry")
        for (post in posts) {
            val link = post.selectFirst("a[href]")?.attr("href") ?: continue
            if (!link.contains("moviesmod.uk.com")) continue
            val title = post.selectFirst("h2, h3, .title, .entry-title")?.text()?.trim() ?: continue
            if (title.isEmpty()) continue
            val img = post.selectFirst("img")?.attr("src")
            val isTv = title.lowercase().contains("season") || title.lowercase().contains("series") ||
                       link.lowercase().contains("season") || link.lowercase().contains("series")
            items.add(
                if (isTv) {
                    newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                        this.posterUrl = img
                    }
                } else {
                    newMovieSearchResponse(title, link, TvType.Movie) {
                        this.posterUrl = img
                    }
                }
            )
        }
        return items.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst("img")?.attr("src")
        val description = document.selectFirst(".entry-content, .post-content, article")?.text()?.trim()
        val isTv = title.lowercase().contains("season") || title.lowercase().contains("series") ||
                   url.lowercase().contains("season") || url.lowercase().contains("series")

        if (isTv) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(newEpisode(url))) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val links = mutableSetOf<String>()
        document.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.startsWith("http")) {
                val low = href.lowercase()
                if (low.contains("download") || low.contains("mediafire") || low.contains("drive.google") ||
                    low.contains("mega.nz") || low.contains("pixeldrain") || low.contains("gofile") ||
                    low.contains("zippyshare") || low.contains("1fichier") || low.contains("dropbox") ||
                    low.contains("filemoon") || low.contains("streamtape") || low.contains(".mkv") ||
                    low.contains(".mp4") || low.contains("480p") || low.contains("720p") ||
                    low.contains("1080p") || low.contains("2160p") || low.contains("4k")) {
                    links.add(href)
                }
            }
        }
        links.forEach { link ->
            val quality = getQualityFromString(link)
            callback.invoke(
                ExtractorLink(
                    source = getHost(link),
                    name = "MoviesMod - ${qualityLabel(quality)}",
                    url = link,
                    referer = data,
                    quality = quality,
                    type = if (link.lowercase().contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        return links.isNotEmpty()
    }

    private fun getHost(url: String): String = try { URI(url).host?.replace("www.", "") ?: "MoviesMod" } catch (e: Exception) { "MoviesMod" }
    private fun getQualityFromString(s: String): Int {
        val low = s.lowercase()
        return when {
            low.contains("2160p") || low.contains("4k") -> Qualities.P2160.value
            low.contains("1080p") -> Qualities.P1080.value
            low.contains("720p") -> Qualities.P720.value
            low.contains("480p") -> Qualities.P480.value
            low.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    private fun qualityLabel(q: Int): String = when(q) {
        Qualities.P2160.value -> "2160p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        else -> "Unknown"
    }
}