package com.arbada.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ArbadaProvider : MainAPI() {
    override var name = "العربدة"
    override var mainUrl = "https://www.arbada.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "categories/arab-porn-sex-افلام-سكس-عربي/" to "عربي",
        "categories/افلام-سكس-مترجم-عربي/" to "مترجم",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let { it.attr("src") }
                    val rating = item.selectFirst("div.rating")?.text()?.trim()?.replace("%", "")
                    newMovieSearchResponse(title, href, TvType.NSFW) {
                        this.posterUrl = poster
                        if (!rating.isNullOrBlank()) this.score = Score.from(rating, 100)
                    }
                } catch (e: Exception) { null }
            }
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/search/videos/?q=$query", referer = mainUrl).document
            doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim() ?: a.attr("title")
                    val poster = item.selectFirst("img.thumb")?.let { it.attr("src") }
                    newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1.htitle")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.title().substringBefore(" -").trim()
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)
            newMovieLoadResponse(title, url, TvType.NSFW, url) { this.posterUrl = poster; this.plot = description; this.tags = tags }
        } catch (e: Exception) { null }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(data, referer = mainUrl).document
            var found = false
            
            // Method 1: HTML5 video sources - PRIORITY
            doc.select("video source").forEach { src ->
                val url = src.attr("src")
                val quality = src.attr("title").ifBlank { qlt(url) }
                if (url.isNotBlank()) { lnk(url, quality, callback); found = true }
            }
            if (found) return true
            
            // Method 2: flashvars
            doc.select("script").forEach { element ->
                val script = element.html()
                if (script.contains("flashvars")) {
                    val v1 = rgx(script, "video_url"); val v2 = rgx(script, "video_alt_url"); val v3 = rgx(script, "video_alt_url2")
                    val q1 = rgx(script, "video_url_text") ?: "360p"; val q2 = rgx(script, "video_alt_url_text") ?: "480p"; val q3 = rgx(script, "video_alt_url2_text") ?: "720p"
                    v1?.let { lnk(it, q1, callback); found = true }; v2?.let { lnk(it, q2, callback); found = true }; v3?.let { lnk(it, q3, callback); found = true }
                }
            }
            if (found) return true
            
            // Method 3: iframe - pass URL to player
            val iframe = doc.selectFirst("div.embed-wrap iframe, iframe[src*=embed]")
            if (iframe != null) {
                val iframeUrl = iframe.attr("src")
                if (iframeUrl.isNotBlank()) {
                    callback(newExtractorLink(source = name, name = name, url = iframeUrl, type = ExtractorLinkType.M3U8) { this.referer = data; this.quality = getQualityFromName("720p") })
                    return true
                }
            }
            false
        } catch (e: Exception) { false }
    }

    private fun rgx(script: String, key: String): String? {
        val match = Regex("""$key\s*[:=]\s*['"]([^'"]+)['"]""").find(script) ?: return null
        return match.groupValues[1].ifBlank { null }
    }
    private fun qlt(url: String): String = when { url.contains("720p") -> "720p"; url.contains("480p") -> "480p"; url.contains("360p") -> "360p"; url.contains("1080p") -> "1080p"; else -> "360p" }
    private fun cln(url: String): String = when { url.startsWith("function/0/") -> url.removePrefix("function/0/"); url.startsWith("//") -> "https:$url"; else -> url }
    private suspend fun lnk(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(source = name, name = name, url = cln(url), type = ExtractorLinkType.VIDEO) { this.referer = mainUrl; this.quality = getQualityFromName(quality) })
    }
}