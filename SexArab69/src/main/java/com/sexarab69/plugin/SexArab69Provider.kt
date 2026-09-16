package com.sexarab69.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SexArab69Provider : MainAPI() {
    override var name = "سكس عرب 69"
    override var mainUrl = "https://sexarab69.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "احدث الافلام",
        "category/سكس-عربي/" to "سكس عربي",
        "category/سكس-مترجم/" to "سكس مترجم",
        "category/سكس-مصري/" to "سكس مصري",
        "category/سكس-امهات/" to "سكس امهات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = if (request.data.isEmpty()) {
                if (page > 1) "$mainUrl/page/$page/" else mainUrl
            } else {
                "$mainUrl/${request.data.trimEnd('/')}/${if (page > 1) "page/$page/" else ""}"
            }
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.video-block").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a.thumb") ?: item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = item.selectFirst("a.infos span.title")?.text()?.trim()
                        ?: item.selectFirst("span.title")?.text()?.trim()
                        ?: a.attr("title") ?: ""
                    val poster = item.selectFirst("img.video-img")?.let {
                        it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                    }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/?s=$query", referer = mainUrl).document
            doc.select("div.video-block").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a.thumb") ?: item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = item.selectFirst("a.infos span.title")?.text()?.trim()
                        ?: item.selectFirst("span.title")?.text()?.trim()
                        ?: a.attr("title") ?: ""
                    val poster = item.selectFirst("img.video-img")?.let {
                        it.attr("data-src").ifBlank { it.attr("data-lazy-src").ifBlank { it.attr("src") } }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst(".title, .video-title")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("a[rel=tag]").mapNotNull { it.text().trim() }.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, referer = mainUrl).document

            doc.select("meta[itemprop=contentURL]").forEach { meta ->
                val url = meta.attr("content")
                if (url.isNotBlank() && url.contains(".mp4")) {
                    callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName("360p")
                    })
                    return true
                }
            }

            doc.select("video source").forEach { source ->
                val url = source.attr("src")
                val quality = source.attr("title")
                if (url.isNotBlank() && url.contains(".mp4")) {
                    callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(quality.ifBlank { "360p" })
                    })
                    return true
                }
            }

            val iframe = doc.selectFirst("iframe[src*=clean-tube-player], iframe[data-src*=clean-tube-player]")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (iframeUrl.isNotBlank()) {
                    loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                    return true
                }
            }

            val allScript = doc.select("script").joinToString("\n") { it.data() }
            val directMp4 = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").find(allScript)?.groupValues?.get(1)
            if (directMp4 != null) {
                callback(newExtractorLink(name, name, directMp4, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                    this.quality = getQualityFromName("360p")
                })
                return true
            }

            return false
        } catch (e: Exception) { return false }
    }
}