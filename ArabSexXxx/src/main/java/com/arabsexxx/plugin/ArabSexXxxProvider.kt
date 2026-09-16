package com.arabsexxx.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ArabSexXxxProvider : MainAPI() {
    override var name = "سكس عربي xxx"
    override var mainUrl = "https://www.arabsex.xxx"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest/" to "احدث الافلام",
        "top_rated/" to "افضل الافلام",
        "most_popular/" to "الاعلى مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = a.attr("title")?.trim() ?: item.selectFirst("strong.title")?.text()?.trim() ?: ""
                    val poster = item.selectFirst("img.thumb, img.lazy-load")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
                    }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/$query/", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = a.attr("title")?.trim() ?: item.selectFirst("strong.title")?.text()?.trim() ?: ""
                    val poster = item.selectFirst("img.thumb, img.lazy-load")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
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
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster; this.plot = description; this.tags = tags
            }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val doc = app.get(data, referer = mainUrl).document
            val html = doc.html()

            // Method 1: contentUrl in JSON-LD schema - MOST RELIABLE
            val contentUrl = Regex(""""contentUrl"\s*:\s*"([^"]+\.mp4[^"]*)""").find(html)?.groupValues?.get(1)
            if (!contentUrl.isNullOrBlank()) {
                callback(newExtractorLink(name, name, contentUrl, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl; this.quality = getQualityFromName("720p")
                })
                return true
            }

            // Method 2: video_url in kt_player script
            val videoUrl = Regex("""video_url\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!videoUrl.isNullOrBlank()) {
                callback(newExtractorLink(name, name, decodeUrl(videoUrl), ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl; this.quality = getQualityFromName("720p")
                })
                return true
            }

            // Method 3: video source tags
            doc.select("video source").forEach { source ->
                val url = source.attr("src")
                if (url.isNotBlank() && url.contains(".mp4")) {
                    callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl; this.quality = getQualityFromName("360p")
                    })
                    return true
                }
            }

            return false
        } catch (e: Exception) { return false }
    }

    private fun decodeUrl(url: String): String {
        val decoded = when {
            url.startsWith("function/0/") -> {
                try {
                    val base64 = url.removePrefix("function/0/")
                    android.util.Base64.decode(base64, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                } catch (_: Exception) { url.removePrefix("function/0/") }
            }
            else -> url
        }
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https/") -> "https://${decoded.removePrefix("https/")}"
            else -> decoded
        }
    }
}