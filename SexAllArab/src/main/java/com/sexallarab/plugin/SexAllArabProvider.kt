package com.sexallarab.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SexAllArabProvider : MainAPI() {
    override var name = "سكس كل العرب"
    override var mainUrl = "https://sexallarab.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = if (request.data.isEmpty()) {
                if (page > 1) "$mainUrl/page/$page/" else mainUrl
            } else {
                "$mainUrl/${request.data.trimEnd('/')}${if (page > 1) "page/$page/" else ""}"
            }
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")?.toString() ?: return@mapNotNull null
                    val title = a.attr("title")?.trim() ?: item.selectFirst("strong.title")?.text()?.trim() ?: ""
                    val poster = item.selectFirst("img.thumb")?.let {
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
                    val poster = item.selectFirst("img.thumb")?.let {
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
            val allScript = doc.select("script").joinToString("\n") { it.data() }

            if (allScript.contains("flashvars")) {
                val entries = listOf("video_url" to "240p", "video_alt_url" to "360p", "video_alt_url2" to "480p")
                for ((urlKey, quality) in entries) {
                    val url = Regex("""$urlKey\s*[:=]\s*['"]([^'"]+)['"]""").find(allScript)?.groupValues?.get(1)
                    if (!url.isNullOrBlank()) {
                        callback(newExtractorLink(name, name, decodeUrl(url), ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl; this.quality = getQualityFromName(quality)
                        })
                    }
                }
                return true
            }

            val videoUrlMatch = Regex("""video_url\s*:\s*['"]([^'"]+)['"]""").find(allScript)
            if (videoUrlMatch != null) {
                callback(newExtractorLink(name, name, decodeUrl(videoUrlMatch.groupValues[1]), ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl; this.quality = getQualityFromName("360p")
                })
                return true
            }

            doc.select("video source").forEach { source ->
                val url = source.attr("src")
                if (url.isNotBlank() && url.contains(".mp4")) {
                    callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl; this.quality = getQualityFromName("360p")
                    })
                    return true
                }
            }

            val iframe = doc.selectFirst("iframe[src]")
            if (iframe != null) {
                loadExtractor(iframe.attr("src"), mainUrl, subtitleCallback, callback)
                return true
            }
            return false
        } catch (e: Exception) { return false }
    }

    private fun decodeUrl(url: String): String {
        val decoded = when {
            url.startsWith("function/0/") -> try {
                android.util.Base64.decode(url.removePrefix("function/0/"), android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
            } catch (_: Exception) { url.removePrefix("function/0/") }
            else -> url
        }
        return when {
            decoded.startsWith("//") -> "https:$decoded"
            decoded.startsWith("https/") -> "https://${decoded.removePrefix("https/")}"
            else -> decoded
        }
    }
}