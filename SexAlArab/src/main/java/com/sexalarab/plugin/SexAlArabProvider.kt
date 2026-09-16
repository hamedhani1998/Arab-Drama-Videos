package com.sexalarab.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class SexAlArabProvider : MainAPI() {
    override var name = "سكس العرب"
    override var mainUrl = "https://sexalarab.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "latest-updates/" to "احدث الافلام",
        "top-rated/" to "افضل الافلام",
        "most-popular/" to "الاعلى مشاهدة",
        "category/سكس-مترجم/" to "مترجم",
        "category/سكس-امهات/" to "أمهات",
        "category/سكس-محارم/" to "محارم",
        "category/سكس-نيك-عربي/" to "عربي",
        "category/مسلسلات-سكس-مترجم/" to "مسلسلات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}${if (page > 1) "page/$page/" else ""}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("div.item").mapNotNull { item ->
                try {
                    val a = item.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href") ?: return@mapNotNull null
                    val title = item.selectFirst("strong.title")?.text()?.trim()
                        ?: item.selectFirst("a span.title")?.text()?.trim()
                        ?: a.attr("title") ?: return@mapNotNull null
                    val poster = item.selectFirst("img.thumb")?.let {
                        it.attr("data-original").ifBlank { it.attr("data-src").ifBlank { it.attr("src") } }
                    }
                    val rating = item.selectFirst("div.rating")?.ownText()?.trim()
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
                    val title = item.selectFirst("strong.title")?.text()?.trim()
                        ?: item.selectFirst("a span.title")?.text()?.trim()
                        ?: a.attr("title") ?: return@mapNotNull null
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

            // og:title أولاً — عادةً اسم الفيلم الصافي؛ وبعدها h1 داخل المحتوى، مع تجاهل
            // العنوان الأفقي (شعار الموقع) حتى لا يظهر "سكس العرب" كاسم للفيديو
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: doc.select("h1").firstOrNull {
                    val t = it.text().trim()
                    t.length > 2 && !t.contains("سكس العرب") && !t.contains("أحدث")
                }?.text()?.trim()
                ?: doc.selectFirst(".video-title h1, .title h1, article h1")?.text()?.trim()
                ?: doc.selectFirst(".title, .htitle, .video-title")?.text()?.trim()
                ?: doc.selectFirst("meta[itemprop=name]")?.attr("content")?.trim()
                ?: doc.title().substringBefore(" - ").trim().ifBlank { "بدون عنوان" }

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.selectFirst("meta[name=keywords]")?.attr("content")?.split(",")?.map { it.trim() }?.take(6)

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

            // Method 1: وسوم <video><source> — نظِّف الرابط أيضاً
            doc.select("video source").forEach { source ->
                val url = source.attr("src")
                val quality = source.attr("title")
                if (url.isNotBlank() && url.contains(".mp4")) {
                    callback(newExtractorLink(name, name, clean(url), ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                        this.quality = getQualityFromName(quality.ifBlank { "360p" })
                    })
                    found = true
                }
            }
            if (found) return true

            // Method 2: flashvars (video_url / video_alt_url / video_alt_url2)
            // — الفهذه مشكلة ERRN_INVALID_URL: قيمة video_url تأتي مغلّفة بـ function/0/<base64>
            // ويجب فكّها قبل إرسالها للمشغّل
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
                        ?: when (urlKey) { "video_url" -> "240p"; "video_alt_url" -> "360p"; else -> "480p" }
                    if (!url.isNullOrBlank()) {
                        val decoded = clean(url)
                        if (decoded.contains("get_file") || decoded.contains("function/") || decoded.startsWith("http")) {
                            callback(newExtractorLink(name, name, decoded, ExtractorLinkType.VIDEO) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(quality)
                            })
                            found = true
                        }
                    }
                }
            }
            if (found) return true

            // Method 3: iframe embed
            val iframe = doc.selectFirst("iframe[src]")
            if (iframe != null) {
                loadExtractor(iframe.attr("src"), mainUrl, subtitleCallback, callback)
                return true
            }
            return found
        } catch (e: Exception) { return false }
    }

    /**
     * فكّ روابط `function/0/<base64>` إلى الرابط الداخلي، وإصلاح البادئات `//` و `https/`.
     * نفس المنطق العامل في SexAlArabNet و Sexalarab11.
     */
    private fun clean(url: String): String {
        val decoded = when {
            url.startsWith("function/0/") -> {
                try {
                    android.util.Base64.decode(url.removePrefix("function/0/"), android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
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