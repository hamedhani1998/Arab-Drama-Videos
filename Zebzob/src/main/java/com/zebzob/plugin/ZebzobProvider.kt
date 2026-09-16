package com.zebzob.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ZebzobProvider : MainAPI() {
    override var name = "زب زوب"
    override var mainUrl = "https://zebzob.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "احدث الافلام",
        "category/سكس-سحاق/" to "سكس سحاق",
        "category/سكس-عربي/" to "سكس عربي",
        "category/سكس-امهات/" to "سكس امهات",
        "category/سكس-اخوات/" to "سكس اخوات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = if (request.data.isEmpty()) {
                if (page > 1) "$mainUrl/page/$page/" else mainUrl
            } else {
                "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            }
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("article.thumb-block").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("span.title")?.text()?.trim()
                        ?: item.selectFirst("a.infos span.title")?.text()?.trim()
                        ?: a.attr("title")
                    val poster = item.selectFirst("img.video-main-thumb")?.let {
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
            doc.select("article.thumb-block").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("span.title")?.text()?.trim()
                        ?: item.selectFirst("a.infos span.title")?.text()?.trim()
                        ?: a.attr("title")
                    val poster = item.selectFirst("img.video-main-thumb")?.let {
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
                ?: doc.selectFirst(".title, .video-title, .htitle")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
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
            var found = false

            // Method 1: video source tags - PRIMARY METHOD
            doc.select("video source").forEach { source ->
                val url = source.attr("src")
                val quality = source.attr("title")
                if (url.isNotBlank() && url.contains(".mp4")) {
                    callback(newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(quality.ifBlank { "360p" })
                    })
                    found = true
                }
            }
            if (found) return true

            // Method 2: flashvars fallback
            val allScript = doc.select("script").joinToString("\n") { it.data() }
            if (allScript.contains("flashvars")) {
                val entries = listOf(
                    "video_url" to "video_url_text",
                    "video_alt_url" to "video_alt_url_text",
                    "video_alt_url2" to "video_alt_url2_text"
                )
                for ((urlKey, textKey) in entries) {
                    val url = Regex("""$urlKey\s*[:=]\s*['"]([^'"]+)['"]""").find(allScript)?.groupValues?.get(1)
                    val quality = Regex("""$textKey\s*[:=]\s*['"]([^'"]+)['"]""").find(allScript)?.groupValues?.get(1)
                        ?: when(urlKey) { "video_url" -> "240p"; "video_alt_url" -> "360p"; else -> "480p" }
                    if (!url.isNullOrBlank()) {
                        callback(newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                            this.referer = mainUrl
                            this.quality = getQualityFromName(quality)
                        })
                        found = true
                    }
                }
            }
            if (found) return true

            // Method 3: iframe embed - try player01 AJAX, then loadExtractor
            val iframeUrl = doc.selectFirst("meta[itemprop=embedURL]")?.attr("content")
                ?: doc.selectFirst("iframe[src*=player01]")?.attr("src")
                ?: doc.selectFirst("iframe[src]")?.attr("src")

            if (!iframeUrl.isNullOrBlank()) {
                val playerMatch = Regex("""(https?://[^/]+)/hdplay/([A-Za-z0-9+/=]+)\.html""").find(iframeUrl)
                if (playerMatch != null) {
                    val baseUrl = playerMatch.groupValues[1]
                    val hash = playerMatch.groupValues[2]
                    try {
                        val resp = app.post("$baseUrl/hdplay/ajax_sources.php",
                            data = mapOf("vid" to hash),
                            referer = iframeUrl,
                            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"))
                        val sources = Regex(""""file"\s*:\s*"([^"]+)"""").findAll(resp.text).map { it.groupValues[1] }.toList()
                        val labels = Regex(""""label"\s*:\s*"([^"]+)"""").findAll(resp.text).map { it.groupValues[1] }.toList()
                        for ((i, s) in sources.withIndex()) {
                            callback(newExtractorLink(name, name, s, ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(labels.getOrElse(i) { "360p" })
                            })
                            found = true
                        }
                        if (found) return true
                    } catch (_: Exception) {}
                }
                // Fallback: use loadExtractor
                loadExtractor(iframeUrl, mainUrl, subtitleCallback, callback)
                return true
            }

            return false
        } catch (e: Exception) { return false }
    }

    private fun clean(url: String): String {
        val decoded = when {
            url.startsWith("function/0/") -> {
                try { android.util.Base64.decode(url.removePrefix("function/0/"), android.util.Base64.DEFAULT).toString(Charsets.UTF_8) }
                catch (_: Exception) { url.removePrefix("function/0/") }
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
