package com.reelshort.plugin

import cloudstreamshared.FormatTag
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences
import android.util.Log
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.net.URLDecoder

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"
private const val RS_MAIN = "https://www.reelshort.com"

/**
 * ReelShort (https://www.reelshort.com) — منصة دراما قصيرة عالمية كبرى.
 *
 * البنية:
 * - الرئيسية: / — البطاقات في JSON-LD و __NEXT_DATA__
 * - التفاصيل: /movie/<slug>-<bookId> — تحتوي على بيانات المسلسل والحلقات
 * - الحلقات: /episodes/episode-<num>-<slug>-<bookId>-<entryId> — صفحة المشاهدة
 * - الفيديو: يُستخرج من playerConfig / sources في HTML صفحة الحلقة
 * - الترجمات: VTT من subtitle tracks
 * - يدعم جودات متعددة (1080p, 720p, 480p) عبر HLS (m3u8)
 */
class ReelShortProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "ReelShort"
    override var mainUrl = RS_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val mapper = ObjectMapper().registerKotlinModule()

    private fun headers(referer: String = mainUrl) = mapOf(
        "User-Agent" to UA,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
    )

    private fun log(msg: String) { Log.d("ReelShort", msg) }

    override val mainPage = mainPageOf(
        "shelf/new-release-short-movies-dramas-51005193" to "الأحدث",
        "shelf/top-short-movies-dramas-51005194" to "الأكثر مشاهدة",
        "tags/mc-types/female-movies-676d21074582b53a14081663" to "بطولة نسائية",
        "tags/mc-types/male-movies-676d21074582b53a14081662" to "بطولة رجالية",
        "tags/movie-ratings/all-ages-movies-676d210e4582b53a14081a83" to "لكل الأعمار",
        "tags/movie-identities/ceo-movies-676d210e4582b53a14081aaa" to "رئيس تنفيذي",
        "tags/movie-identities/billionaire-movies-676d210e4582b53a14081aab" to "ملياردير",
    )

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = java.util.HashSet<String>()
        
        // محاولة 1: JSON-LD
        val jsonLd = doc.select("script[type=application/ld+json]")
        for (script in jsonLd) {
            try {
                val json = script.html().trim()
                if (json.contains("ItemList") || json.contains("itemListElement")) {
                    val node = mapper.readTree(json)
                    val items = node.path("itemListElement")
                    if (items.isArray) {
                        for (item in items) {
                            val itemNode = item.path("item")
                            val url = itemNode.path("url").asText()
                            val name = itemNode.path("name").asText()
                            val image = itemNode.path("image").asText()
                            if (url.startsWith("http") && name.isNotEmpty() && !seen.add(url)) continue
                            if (url.startsWith("http") && name.isNotEmpty()) {
                                out.add(newTvSeriesSearchResponse(name, url, TvType.TvSeries) {
                                    this.posterUrl = image
                                })
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // محاولة 2: __NEXT_DATA__
        val nextData = doc.select("script#__NEXT_DATA__")
        for (script in nextData) {
            try {
                val json = script.html().trim()
                val node = mapper.readTree(json)
                // التنقل في هيكل Next.js للعثور على البطاقات
                val props = node.path("props").path("pageProps")
                val shelves = props.path("shelves") ?: props.path("data")?.path("shelves") ?: mapper.createArrayNode()
                if (shelves.isArray) {
                    for (shelf in shelves) {
                        val books = shelf.path("books") ?: shelf.path("items")
                        if (books.isArray) {
                            for (book in books) {
                                val bookId = book.path("book_id").asText() ?: book.path("id").asText()
                                val slug = book.path("slug").asText() ?: book.path("title_slug").asText()
                                val title = book.path("title").asText() ?: book.path("name").asText()
                                val cover = book.path("cover").asText() ?: book.path("poster").asText() ?: book.path("vertical_cover").asText()
                                if (bookId.isNotEmpty() && slug.isNotEmpty() && title.isNotEmpty()) {
                                    val url = "$mainUrl/movie/$slug-$bookId"
                                    if (!seen.add(url)) continue
                                    out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                                        this.posterUrl = cover
                                    })
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // محاولة 3: روابط HTML مباشرة
        if (out.isEmpty()) {
            doc.select("a[href*='/movie/']").forEach { a ->
                val href = a.attr("href").trim()
                if (!href.contains("/movie/") || !seen.add(href)) return@forEach
                val img = a.select("img").firstOrNull() ?: return@forEach
                val title = a.attr("title").trim().ifEmpty { img.attr("alt").trim() }.ifEmpty { a.text().trim() }.ifEmpty { return@forEach }
                val poster = img.attr("src").ifEmpty { img.attr("data-src") }?.trim()?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: ""
                out.add(newTvSeriesSearchResponse(title, "$mainUrl$href", TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }

        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = if (page <= 1) "$mainUrl/$request.data" else "$mainUrl/$request.data?page=$page"
            val doc = app.get(url, referer = mainUrl, headers = headers(mainUrl)).document
            val cards = parseCards(doc)
            if (cards.isEmpty()) null else newHomePageResponse(request.name, cards)
        } catch (e: Exception) {
            log("getMainPage failed for ${request.data} :: ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val doc = app.get("$mainUrl/search?q=$enc", referer = mainUrl, headers = headers(mainUrl)).document
            parseCards(doc)
        } catch (e: Exception) {
            log("search failed for '$q' :: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl, headers = headers(mainUrl)).document

            val title = doc.select("h1").firstOrNull()?.text()?.trim()
                ?: doc.select("meta[property=og:title]").attr("content").trim()
                ?: doc.select("title").text().trim()
                ?: return null

            val poster = doc.select("meta[property=og:image]").attr("content")
                .ifEmpty { doc.select("img").firstOrNull()?.attr("src") ?: "" }

            val plot = doc.select("meta[name=description]").attr("content")
                .ifEmpty { doc.select("meta[property=og:description]").attr("content") }
                .ifEmpty { doc.select(".plot, .synopsis, .description").firstOrNull()?.text() ?: "" }

            // استخراج book_id و slug من URL
            val bookId = url.substringAfterLast("-").substringBefore("?")
            val slugPart = url.substringAfterLast("/movie/").substringBeforeLast("-")

            // استخراج عدد الحلقات من JSON-LD أو __NEXT_DATA__
            var totalEpisodes: Int? = null
            val jsonLd = doc.select("script[type=application/ld+json]")
            for (script in jsonLd) {
                try {
                    val json = script.html().trim()
                    val node = mapper.readTree(json)
                    val epCount = node.path("numberOfEpisodes").asInt(0)
                    if (epCount > 0) { totalEpisodes = epCount; break }
                } catch (_: Exception) {}
            }
            if (totalEpisodes == null) {
                val nextData = doc.select("script#__NEXT_DATA__").firstOrNull()?.html()?.trim()
                if (nextData != null) {
                    try {
                        val node = mapper.readTree(nextData)
                        val epCount = node.path("props").path("pageProps").path("book").path("episode_count").asInt(0)
                        if (epCount > 0) totalEpisodes = epCount
                    } catch (_: Exception) {}
                }
            }

            // استخراج روابط الحلقات
            val eps = mutableListOf<Episode>()
            val seen = java.util.HashSet<Int>()

            // من __NEXT_DATA__
            val nextData = doc.select("script#__NEXT_DATA__").firstOrNull()?.html()?.trim()
            if (nextData != null) {
                try {
                    val node = mapper.readTree(nextData)
                    val episodes = node.path("props").path("pageProps").path("book").path("episodes")
                    if (episodes.isArray) {
                        for (ep in episodes) {
                            val epNum = ep.path("episode_number").asInt(0)
                            val epSlug = ep.path("slug").asText()
                            val epId = ep.path("entry_id").asText() ?: ep.path("id").asText()
                            if (epNum > 0 && epSlug.isNotEmpty() && epId.isNotEmpty() && seen.add(epNum)) {
                                val epUrl = "$mainUrl/episodes/episode-$epNum-$epSlug-$bookId-$epId"
                                eps.add(newEpisode(epUrl) {
                                    this.name = "الحلقة $epNum"
                                    this.episode = epNum
                                })
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // fallback: من روابط HTML
            if (eps.isEmpty()) {
                doc.select("a[href*='/episodes/episode-']").forEach { a ->
                    val href = a.attr("href").trim()
                    val epNum = Regex("episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    if (!seen.add(epNum)) return@forEach
                    val abs = if (href.startsWith("http")) href else "$mainUrl$href"
                    eps.add(newEpisode(abs) {
                        this.name = "الحلقة $epNum"
                        this.episode = epNum
                    })
                }
            }

            if (eps.isEmpty()) {
                eps.add(newEpisode(url) { this.name = "الحلقة 1"; this.episode = 1 })
            }
            eps.sortBy { it.episode ?: 0 }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                totalEpisodes?.let { this.tags = listOf("الحلقات: $it") }
            }
        } catch (e: Exception) {
            log("load failed for $url :: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = URL صفحة الحلقة
        val html = try {
            app.get(data, referer = mainUrl, headers = headers(mainUrl)).text
        } catch (e: Exception) {
            log("loadLinks fetch failed for $data :: ${e.message}")
            return false
        }

        val fixed = html.replace("\\/", "/").replace("\\u0026", "&")

        // محاولة 1: playerConfig / sources من JavaScript
        val playerConfigs = mutableListOf<String>()
        val configPatterns = listOf(
            Regex("""playerConfig\s*[:=]\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
            Regex("""videoConfig\s*[:=]\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
            Regex("""sources\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL),
            Regex("""playlist\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL),
            Regex("""videoUrl\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""file\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""src\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""m3u8\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""hls\s*[:=]\s*["']([^"']+)["']"""),
        )
        for (rx in configPatterns) {
            rx.findAll(fixed).forEach { m ->
                val v = m.groupValues.getOrNull(1) ?: m.value
                if (v.isNotEmpty()) playerConfigs.add(v)
            }
        }

        // محاولة 2: روابط مباشرة
        val directUrls = mutableListOf<String>()
        val directPatterns = listOf(
            Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+\.mp4[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+/master\.m3u8[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+/index\.m3u8[^"'\\\s]*"""),
        )
        for (rx in directPatterns) {
            rx.findAll(fixed).forEach { m ->
                val v = m.value.trim()
                if (v.startsWith("http")) directUrls.add(v)
            }
        }

        // محاولة 3: فك التشفير (base64 / URL-encoded)
        val decodedUrls = mutableListOf<String>()
        val encodedPatterns = listOf(
            Regex("""["']([A-Za-z0-9+/=]{100,})["']"""),
            Regex("""(?:src|file|url|m3u8|hls)\s*[:=]\s*["']([^"']*%[0-9A-F]{2}[^"']*)["']"""),
        )
        for (rx in encodedPatterns) {
            rx.findAll(fixed).forEach { m ->
                val v = m.groupValues.getOrNull(1) ?: m.value
                if (v.isNotEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(v, android.util.Base64.DEFAULT)
                        val decoded = String(bytes).trim()
                        if (decoded.startsWith("http")) decodedUrls.add(decoded)
                    } catch (_: Exception) {
                        try {
                            val decoded = URLDecoder.decode(v, "UTF-8").trim()
                            if (decoded.startsWith("http")) decodedUrls.add(decoded)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // محاولة 4: تحليل JSON من playerConfig للعثور على sources array
        val jsonUrls = mutableListOf<String>()
        for (config in playerConfigs) {
            try {
                if (config.startsWith("{") || config.startsWith("[")) {
                    val node = mapper.readTree(config)
                    // البحث عن sources array
                    val sources = node.path("sources") ?: node.path("playlist") ?: node.path("videoSources")
                    if (sources.isArray) {
                        for (src in sources) {
                            val file = src.path("file").asText() ?: src.path("src").asText() ?: src.path("url").asText() ?: src.path("m3u8").asText() ?: src.path("hls").asText()
                            if (file.startsWith("http")) jsonUrls.add(file)
                        }
                    }
                    // كائن واحد
                    if (!sources.isArray && sources.isObject) {
                        val file = sources.path("file").asText() ?: sources.path("src").asText() ?: sources.path("url").asText()
                        if (file.startsWith("http")) jsonUrls.add(file)
                    }
                    // جذر الكائن
                    val rootFile = node.path("file").asText() ?: node.path("src").asText() ?: node.path("url").asText() ?: node.path("m3u8").asText() ?: node.path("hls").asText()
                    if (rootFile.startsWith("http")) jsonUrls.add(rootFile)
                }
            } catch (_: Exception) {}
        }

        val allUrls = (playerConfigs + directUrls + decodedUrls + jsonUrls).distinct()
            .filter { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4") || it.contains("/master.") || it.contains("/index.") || it.contains("hls")) }

        if (allUrls.isEmpty()) {
            log("ReelShort: no video URLs found for $data")
            return false
        }

        // استخراج الترجمات
        val subtitles = mutableMapOf<String, String>()
        val subPatterns = listOf(
            Regex("""["'](?:src|file|url)["']\s*:\s*["']([^"']+\.(?:vtt|srt))[^"']*["']"""),
            Regex("""<track[^>]+src=["']([^"']+\.(?:vtt|srt))[^"']*["']"""),
            Regex("""subtitle[s]?\s*[:=]\s*\[?(.*?)\]?""", RegexOption.DOT_MATCHES_ALL),
        )
        for (rx in subPatterns) {
            rx.findAll(fixed).forEach { m ->
                val subJson = m.groupValues.getOrNull(1) ?: m.value
                try {
                    if (subJson.startsWith("[") || subJson.startsWith("{")) {
                        val node = mapper.readTree(subJson)
                        if (node.isArray) {
                            node.forEach { item ->
                                val lang = item.path("lang").asText() ?: item.path("language").asText() ?: item.path("srclang").asText() ?: "ar"
                                val url = item.path("src").asText() ?: item.path("file").asText() ?: item.path("url").asText()
                                if (url.startsWith("http")) subtitles[lang] = url
                            }
                        } else if (node.isObject) {
                            val lang = node.path("lang").asText() ?: node.path("language").asText() ?: node.path("srclang").asText() ?: "ar"
                            val url = node.path("src").asText() ?: node.path("file").asText() ?: node.path("url").asText()
                            if (url.startsWith("http")) subtitles[lang] = url
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        subtitles.forEach { (lang, url) ->
            subtitleCallback(newSubtitleFile(lang, url))
        }

        // بناء الروابط مع الجودات
        val seenQualities = java.util.HashSet<String>()
        for (videoUrl in allUrls) {
            val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains("/master.") || videoUrl.contains("/index.") || videoUrl.contains("hls")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            val qualityFromUrl = when {
                videoUrl.contains("1080") || videoUrl.contains("1920") -> "1080p"
                videoUrl.contains("720") || videoUrl.contains("1280") -> "720p"
                videoUrl.contains("480") || videoUrl.contains("854") -> "480p"
                videoUrl.contains("360") || videoUrl.contains("640") -> "360p"
                else -> "auto"
            }

            val qualityLabel = if (isM3u8) "HLS" else "MP4"
            val label = "ReelShort ($qualityLabel${if (qualityFromUrl != "auto") " $qualityFromUrl" else ""})"

            val key = "$type-$qualityFromUrl"
            if (!seenQualities.add(key)) continue

            callback(
                newExtractorLink(name, FormatTag.tagged(label, videoUrl, type), videoUrl, type) {
                    this.headers = headers(mainUrl)
                    this.referer = mainUrl
                    this.quality = getQualityFromName(qualityFromUrl)
                }
            )
        }
        return true
    }
}