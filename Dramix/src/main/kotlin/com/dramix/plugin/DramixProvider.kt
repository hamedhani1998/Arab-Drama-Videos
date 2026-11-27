package com.dramix.plugin

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
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URLDecoder

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

private val mapper = ObjectMapper().registerKotlinModule()

/**
 * Dramix (https://dramix.tv) — كتالوج دراما قصيرة يجمع عدة مزودين.
 * 
 * آلية استخراج الفيديو:
 * 1. صفحة المشاهدة /ar/watch/<slug>/<ep> تحتوي على iframe أو فيديو من المزود الأصلي
 * 2. المزودون: BibiShort, FlexTV, MoboReels, BiliTV, FlareFlow, DramaNova, KalosTV, RapidTV, ReelShort, ShortMax
 * 3. كل مزود له CDN خاص - نستخرج رابط iframe ثم نزور صفحة المزود لاستخراج الفيديو الفعلي
 */
class DramixProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "Dramix"
    override var mainUrl = "https://dramix.tv"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "new-dramas" to "الأحدث",
        "trending" to "الأكثر رواجاً",
        "provider/flextv" to "FlexTV",
        "provider/moboreels" to "MoboReels",
        "provider/bilitv" to "BiliTV",
        "provider/flareflow" to "FlareFlow",
        "provider/dramanova" to "DramaNova",
        "provider/kalostv" to "KalosTV",
        "provider/rapidtv" to "RapidTV",
        "provider/reelshort" to "ReelShort",
        "provider/shortmax" to "ShortMax",
        "provider/bibishort" to "BibiShort",
        "provider/dotdrama" to "DotDrama",
        "provider/dramabite" to "DramaBite",
        "provider/dramabox" to "DramaBox",
        "provider/dramawave" to "DramaWave",
        "provider/flickreels" to "FlickReels",
        "provider/freereels" to "FreeReels",
        "provider/fundrama" to "FunDrama",
        "provider/goodshort" to "GoodShort",
        "provider/happyshort" to "HappyShort",
        "provider/idrama" to "iDrama",
        "provider/joyreels" to "JoyReels",
        "provider/microdrama" to "MicroDrama",
        "provider/reelbuzz" to "ReelBuzz",
        "provider/reelife" to "Reelife",
        "provider/shortical" to "Shortical",
        "provider/stardusttv" to "StardustTV",
        "provider/starshort" to "StarShort",
        "provider/velolo" to "Velolo",
        "provider/vigloo" to "Vigloo",
        "provider/vyntage" to "Vyntage"
    )

    private fun headers(referer: String = mainUrl) = mapOf(
        "User-Agent" to UA,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ar,en;q=0.9",
    )

    private fun log(msg: String) { Log.d("Dramix", msg) }

    private fun browseUrl(data: String, page: Int): String {
        val base = "$mainUrl/ar/$data"
        return if (page <= 1) base else "$base/page/$page"
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = java.util.HashSet<String>()
        doc.select("a[href*='/drama/']").forEach { a ->
            val href = a.attr("href").trim()
            if (!href.contains("/drama/") || !seen.add(href)) return@forEach
            val img = a.select("img").firstOrNull() ?: return@forEach
            val title = a.attr("title").trim()
                .ifEmpty { img.attr("alt").trim() }
                .ifEmpty { a.text().trim() }
                .ifEmpty { return@forEach }
            val poster = img.attr("src").ifEmpty { img.attr("data-src") }
                ?.trim()?.let { if (it.startsWith("http")) it else "$mainUrl$it" } ?: ""
            out.add(newTvSeriesSearchResponse(title, "$mainUrl$href", TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val doc = app.get(browseUrl(request.data, page), referer = mainUrl, headers = headers(mainUrl)).document
            val cards = parseCards(doc)
            if (cards.isEmpty()) null else newHomePageResponse(request.name, cards)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val doc = app.get("$mainUrl/ar/search?q=$enc", referer = mainUrl, headers = headers(mainUrl)).document
            parseCards(doc)
        } catch (e: Exception) {
            log("Dramix: search failed for '$q' :: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl, headers = headers(mainUrl)).document

            val title = doc.select("h1").firstOrNull()?.text()?.trim()
                ?: doc.select("meta[property=og:title]").attr("content").trim()
                ?: return null

            val poster = doc.select("meta[property=og:image]").attr("content")
                .ifEmpty { doc.select("img").firstOrNull()?.attr("src") ?: "" }

            val plot = doc.select("meta[name=description]").attr("content")
                .ifEmpty { doc.select(".synopsis, .description").firstOrNull()?.text() ?: "" }

            var totalEpisodes: Int? = null
            val jsonLd = doc.select("script[type=application/ld+json]")
            for (script in jsonLd) {
                try {
                    val json = script.html().trim()
                    if (json.contains("@type") && json.contains("TVSeries")) {
                        val epMatch = Regex("\"numberOfEpisodes\"\\s*:\\s*(\\d+)").find(json)
                        if (epMatch != null) {
                            totalEpisodes = epMatch.groupValues?.get(1)?.toIntOrNull()
                            break
                        }
                    }
                } catch (_: Exception) {}
            }

            val eps = mutableListOf<Episode>()
            val seen = java.util.HashSet<Int>()
            doc.select("a[href*='/watch/']").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty()) return@forEach
                val tail = href.substringAfterLast("/watch/").substringBefore("?")
                val parts = tail.split("/")
                if (parts.size < 2) return@forEach
                val num = parts.last().filter { it.isDigit() }.toIntOrNull() ?: 1
                if (!seen.add(num)) return@forEach
                val abs = if (href.startsWith("http")) href else "$mainUrl$href"
                eps.add(newEpisode(abs) {
                    this.name = "الحلقة $num"
                    this.episode = num
                })
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
            log("Dramix: load failed for $url :: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = URL صفحة المشاهدة على dramix.tv
        // نحتاج أولاً استخراج iframe أو رابط المزود، ثم زيارة صفحة المزود لاستخراج الفيديو
        return try {
            val watchDoc = app.get(data, referer = mainUrl, headers = headers(mainUrl)).document
            
            // البحث عن iframe يحتوي على المشغل
            val iframes = watchDoc.select("iframe[src]")
            var providerUrl: String? = null
            var providerName = "Unknown"
            
            for (iframe in iframes) {
                val src = iframe.attr("src")
                if (src.contains("http") && (src.contains("reelshort") || src.contains("flextv") || 
                    src.contains("moboreels") || src.contains("bilitv") || src.contains("bibishort") ||
                    src.contains("kalostv") || src.contains("rapidtv") || src.contains("shortmax") ||
                    src.contains("dramanova") || src.contains("flareflow"))) {
                    providerUrl = if (src.startsWith("http")) src else "https:$src"
                    break
                }
            }
            
            // إذا لم يوجد iframe، نبحث عن روابط مباشرة في الصفحة
            if (providerUrl == null) {
                val html = watchDoc.html()
                val fixed = html.replace("\\/", "/").replace("\\u0026", "&")
                
                // البحث عن روابط m3u8/mp4 مباشرة
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
                
                if (directUrls.isNotEmpty()) {
                    return processVideoUrls(directUrls.distinct(), data, subtitleCallback, callback)
                }
            }
            
            // إذا وجدنا iframe للمزود، نزور صفحته
            if (providerUrl != null) {
                log("Dramix: Found provider iframe: $providerUrl")
                return tryExtractFromProvider(providerUrl, data, subtitleCallback, callback)
            }
            
            // محاولة أخيرة: البحث في JSON-LD أو playerConfig
            val html = watchDoc.html()
            val fixed = html.replace("\\/", "/").replace("\\u0026", "&")
            
            val playerConfigs = mutableListOf<String>()
            val configPatterns = listOf(
                Regex("""playerConfig\s*[:=]\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
                Regex("""videoConfig\s*[:=]\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
                Regex("""sources\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL),
                Regex("""playlist\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL),
            )
            for (rx in configPatterns) {
                rx.findAll(fixed).forEach { m ->
                    val v = m.groupValues.getOrNull(1) ?: m.value
                    if (v.isNotEmpty()) playerConfigs.add(v)
                }
            }
            
            val jsonUrls = mutableListOf<String>()
            for (config in playerConfigs) {
                try {
                    if (config.startsWith("{") || config.startsWith("[")) {
                        val node = mapper.readTree(config)
                        val sources = node.path("sources") ?: node.path("playlist") ?: node.path("videoSources")
                        if (sources.isArray) {
                            for (src in sources) {
                                val file = src.path("file").asText() ?: src.path("src").asText() ?: src.path("url").asText() ?: src.path("m3u8").asText() ?: src.path("hls").asText()
                                if (file.startsWith("http")) jsonUrls.add(file)
                            }
                        }
                        if (!sources.isArray && sources.isObject) {
                            val file = sources.path("file").asText() ?: sources.path("src").asText() ?: sources.path("url").asText()
                            if (file.startsWith("http")) jsonUrls.add(file)
                        }
                        val rootFile = node.path("file").asText() ?: node.path("src").asText() ?: node.path("url").asText() ?: node.path("m3u8").asText() ?: node.path("hls").asText()
                        if (rootFile.startsWith("http")) jsonUrls.add(rootFile)
                    }
                } catch (_: Exception) {}
            }
            
            if (jsonUrls.isNotEmpty()) {
                return processVideoUrls(jsonUrls.distinct(), data, subtitleCallback, callback)
            }
            
            log("Dramix: no video source found for $data")
            false
        } catch (e: Exception) {
            log("Dramix: loadLinks failed for $data :: ${e.message}")
            false
        }
    }

    private suspend fun tryExtractFromProvider(providerUrl: String, originalUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            // زيارة صفحة المزود
            val providerDoc = app.get(providerUrl, referer = mainUrl, headers = headers(mainUrl)).document
            val html = providerDoc.html().replace("\\/", "/").replace("\\u0026", "&")
            
            // محاولة 1: استخراج من JSON-LD
            val jsonLd = providerDoc.select("script[type=application/ld+json]")
            for (script in jsonLd) {
                try {
                    val node = mapper.readTree(script.html().trim())
                    if (node.has("contentUrl")) {
                        val url = node.path("contentUrl").asText()
                        if (url.startsWith("http") && (url.contains(".m3u8") || url.contains(".mp4"))) {
                            return processVideoUrls(listOf(url), originalUrl, subtitleCallback, callback)
                        }
                    }
                    if (node.has("embedUrl")) {
                        val embedUrl = node.path("embedUrl").asText()
                        if (embedUrl.startsWith("http")) {
                            // زيارة embed URL بشكل تكراري
                            return tryExtractFromProvider(embedUrl, originalUrl, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
            
            // محاولة 2: البحث عن playerConfig في الصفحة
            val playerConfigs = mutableListOf<String>()
            val configPatterns = listOf(
                Regex("""playerConfig\s*[:=]\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
                Regex("""videoConfig\s*[:=]\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
                Regex("""sources\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL),
                Regex("""videoUrl\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""file\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""src\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""m3u8\s*[:=]\s*["']([^"']+)["']"""),
                Regex("""hls\s*[:=]\s*["']([^"']+)["']"""),
            )
            for (rx in configPatterns) {
                rx.findAll(html).forEach { m ->
                    val v = m.groupValues.getOrNull(1) ?: m.value
                    if (v.isNotEmpty()) playerConfigs.add(v)
                }
            }
            
            val jsonUrls = mutableListOf<String>()
            for (config in playerConfigs) {
                try {
                    if (config.startsWith("{") || config.startsWith("[")) {
                        val node = mapper.readTree(config)
                        val sources = node.path("sources") ?: node.path("playlist") ?: node.path("videoSources")
                        if (sources.isArray) {
                            for (src in sources) {
                                val file = src.path("file").asText() ?: src.path("src").asText() ?: src.path("url").asText() ?: src.path("m3u8").asText() ?: src.path("hls").asText()
                                if (file.startsWith("http")) jsonUrls.add(file)
                            }
                        }
                        if (!sources.isArray && sources.isObject) {
                            val file = sources.path("file").asText() ?: sources.path("src").asText() ?: sources.path("url").asText()
                            if (file.startsWith("http")) jsonUrls.add(file)
                        }
                        val rootFile = node.path("file").asText() ?: node.path("src").asText() ?: node.path("url").asText() ?: node.path("m3u8").asText() ?: node.path("hls").asText()
                        if (rootFile.startsWith("http")) jsonUrls.add(rootFile)
                    }
                } catch (_: Exception) {}
            }
            
            // محاولة 3: روابط مباشرة
            val directUrls = mutableListOf<String>()
            val directPatterns = listOf(
                Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*"""),
                Regex("""https?://[^"'\\\s]+\.mp4[^"'\\\s]*"""),
                Regex("""https?://[^"'\\\s]+/master\.m3u8[^"'\\\s]*"""),
                Regex("""https?://[^"'\\\s]+/index\.m3u8[^"'\\\s]*"""),
            )
            for (rx in directPatterns) {
                rx.findAll(html).forEach { m ->
                    val v = m.value.trim()
                    if (v.startsWith("http")) directUrls.add(v)
                }
            }
            
            val allUrls = (jsonUrls + directUrls).distinct()
                .filter { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4") || it.contains("/master.") || it.contains("/index.") || it.contains("hls")) }
            
            if (allUrls.isNotEmpty()) {
                return processVideoUrls(allUrls, originalUrl, subtitleCallback, callback)
            }
            
            // محاولة 4: فك التشفير base64/URL-encoded
            val decodedUrls = mutableListOf<String>()
            val encodedPatterns = listOf(
                Regex("""["']([A-Za-z0-9+/=]{100,})["']"""),
                Regex("""(?:src|file|url|m3u8|hls)\s*[:=]\s*["']([^"']*%[0-9A-F]{2}[^"']*)["']"""),
            )
            for (rx in encodedPatterns) {
                rx.findAll(html).forEach { m ->
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
            
            val allDecoded = decodedUrls.distinct()
                .filter { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4") || it.contains("/master.") || it.contains("/index.") || it.contains("hls")) }
            
            if (allDecoded.isNotEmpty()) {
                return processVideoUrls(allDecoded, originalUrl, subtitleCallback, callback)
            }
            
            log("Dramix: failed to extract video from provider: $providerUrl")
            false
        } catch (e: Exception) {
            log("Dramix: provider extraction failed for $providerUrl :: ${e.message}")
            false
        }
    }

    private suspend fun processVideoUrls(
        urls: List<String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // استخراج الترجمات من الصفحة الأصلية
        extractSubtitles(referer, subtitleCallback)
        
        val seenQualities = java.util.HashSet<String>()
        var hasLinks = false
        
        for (videoUrl in urls) {
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
            val label = "Dramix ($qualityLabel${if (qualityFromUrl != "auto") " $qualityFromUrl" else ""})"
            
            val key = "$type-$qualityFromUrl"
            if (!seenQualities.add(key)) continue
            
            hasLinks = true
            callback(
                newExtractorLink(name, FormatTag.tagged(label, videoUrl, type), videoUrl, type) {
                    this.headers = headers(mainUrl)
                    this.referer = referer
                    this.quality = getQualityFromName(qualityFromUrl)
                }
            )
        }
        return hasLinks
    }

    private suspend fun extractSubtitles(pageUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val doc = app.get(pageUrl, referer = mainUrl, headers = headers(mainUrl)).document
            val html = doc.html().replace("\\/", "/").replace("\\u0026", "&")
            
            val subtitles = mutableMapOf<String, String>()
            val subPatterns = listOf(
                Regex("""["'](?:src|file|url)["']\s*:\s*["']([^"']+\.(?:vtt|srt))[^"']*["']"""),
                Regex("""<track[^>]+src=["']([^"']+\.(?:vtt|srt))[^"']*["']"""),
                Regex("""subtitle[s]?\s*[:=]\s*\[?(.*?)\]?""", RegexOption.DOT_MATCHES_ALL),
            )
            for (rx in subPatterns) {
                rx.findAll(html).forEach { m ->
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
        } catch (e: Exception) {
            log("Dramix: subtitle extraction failed :: ${e.message}")
        }
    }
}