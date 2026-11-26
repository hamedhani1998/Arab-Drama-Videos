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
import java.net.URLEncoder
import java.net.URLDecoder

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

private val mapper = ObjectMapper().registerKotlinModule()

/**
 * Dramix (https://dramix.tv) — كتالوج دراما قصيرة عمودية متعدد المزودين.
 *
 * البنية (تم فحصها):
 * - التصفح: /ar/new-dramas, /ar/trending, /ar/provider/<slug>
 * - التفاصيل: /ar/drama/<slug> — تحتوي بيانات JSON-LD وروابط الحلقات
 * - المشاهدة: /ar/watch/<slug>/<ep> — مشغّل فيديو يحمل المصدروالترجمات
 * - الفيديو يأتي من مزودين متعددين (BibiShort, FlexTV, MoboReels, BiliTV, إلخ)
 *   لكلٍّ CDN خاص به — نستخرج الروابط بالتعبير التنظيمي من HTML صفحة المشاهدة
 *   ومن سكربتات التهيئة (playerConfig, sources, إلخ).
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
        "provider/shortmax" to "ShortMax"
    )

    private fun headers(referer: String = mainUrl) = mapOf(
        "User-Agent" to UA,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
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

            // استخراج بيانات JSON-LD من الصفحة (Schema.org TVSeries)
            var totalEpisodes: Int? = null
            val jsonLd = doc.select("script[type=application/ld+json]")
            for (script in jsonLd) {
                try {
                    val json = script.html().trim()
                    if (json.contains("@type") && json.contains("TVSeries")) {
                        // يمكن أن يحتوي على numberOfEpisodes
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
                // fallback: محاولة استخراج من JSON-LD أو playerConfig
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
        // data = URL صفحة المشاهدة
        val html = try {
            app.get(data, referer = mainUrl, headers = headers(mainUrl)).text
        } catch (e: Exception) {
            log("Dramix: loadLinks fetch failed for $data :: ${e.message}")
            return false
        }

        // تنظيف الهروب الشائع
        val fixed = html.replace("\\/", "/").replace("\\u0026", "&")

        // محاولة 1: البحث عن إعدادات اللاعب في JavaScript (playerConfig, sources, playlist, إلخ)
        val playerConfigs = mutableListOf<String>()
        val configPatterns = listOf(
            Regex("""playerConfig\s*[:=]\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
            Regex("""sources\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL),
            Regex("""playlist\s*[:=]\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL),
            Regex("""videoUrl\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""file\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""src\s*[:=]\s*["']([^"']+)["']"""),
        )
        for (rx in configPatterns) {
            rx.findAll(fixed).forEach { m ->
                val v = m.groupValues.getOrNull(1) ?: m.value
                if (v.isNotEmpty()) playerConfigs.add(v)
            }
        }

        // محاولة 2: البحث المباشر عن روابط m3u8/mp4 في HTML
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

        // محاولة 3: البحث في سكربتات التهيئة المشفرة (base64, url-encoded)
        val decodedUrls = mutableListOf<String>()
        val encodedPatterns = listOf(
            Regex("""["']([A-Za-z0-9+/=]{100,})["']"""), // base64 محتمل
            Regex("""(?:src|file|url)\s*[:=]\s*["']([^"']*%[0-9A-F]{2}[^"']*)["']"""), // URL-encoded
        )
        for (rx in encodedPatterns) {
            rx.findAll(fixed).forEach { m ->
                val v = m.groupValues.getOrNull(1) ?: m.value
                if (v.isNotEmpty()) {
                    // محاولة فك base64
                    try {
                        val bytes = android.util.Base64.decode(v, android.util.Base64.DEFAULT)
                        val decoded = String(bytes).trim()
                        if (decoded.startsWith("http")) decodedUrls.add(decoded)
                    } catch (_: Exception) {
                        // محاولة فك URL-encoding
                        try {
                            val decoded = URLDecoder.decode(v, "UTF-8").trim()
                            if (decoded.startsWith("http")) decodedUrls.add(decoded)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // تجميع جميع الروابط المكتشفة
        val allUrls = (playerConfigs + directUrls + decodedUrls).distinct()
            .filter { it.startsWith("http") && (it.contains(".m3u8") || it.contains(".mp4") || it.contains("/master.") || it.contains("/index.")) }

        if (allUrls.isEmpty()) {
            log("Dramix: no video URLs found for $data")
            return false
        }

        // استخراج الترجمات (VTT/SRT)
        val subtitles = mutableMapOf<String, String>() // lang -> url
        val subPatterns = listOf(
            Regex("""["'](?:src|file|url)["']\s*:\s*["']([^"']+\.(?:vtt|srt))[^"']*["']"""),
            Regex("""<track[^>]+src=["']([^"']+\.(?:vtt|srt))[^"']*["']"""),
            Regex("""subtitle[s]?\s*[:=]\s*\[?(.*?)\]?""", RegexOption.DOT_MATCHES_ALL),
        )
        for (rx in subPatterns) {
            rx.findAll(fixed).forEach { m ->
                val subJson = m.groupValues.getOrNull(1) ?: m.value
                // محاولة تحليل JSON للترجمات
                try {
                    if (subJson.startsWith("[") || subJson.startsWith("{")) {
                        val node = mapper.readTree(subJson)
                        if (node.isArray) {
                            node.forEach { item ->
                                val lang = item.get("lang")?.asText() ?: item.get("language")?.asText() ?: item.get("srclang")?.asText() ?: "ar"
                                val url = item.get("src")?.asText() ?: item.get("file")?.asText() ?: item.get("url")?.asText()
                                if (url != null && url.startsWith("http")) subtitles[lang] = url
                            }
                        } else if (node.isObject) {
                            val lang = node.get("lang")?.asText() ?: node.get("language")?.asText() ?: node.get("srclang")?.asText() ?: "ar"
                            val url = node.get("src")?.asText() ?: node.get("file")?.asText() ?: node.get("url")?.asText()
                            if (url != null && url.startsWith("http")) subtitles[lang] = url
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // تسجيل الترجمات
        subtitles.forEach { (lang, url) ->
            subtitleCallback(newSubtitleFile(lang, url))
        }

        // بناء روابط الاستخراج مع الجودات
        val seenQualities = java.util.HashSet<String>()
        for (videoUrl in allUrls) {
            val isM3u8 = videoUrl.contains(".m3u8") || videoUrl.contains("/master.") || videoUrl.contains("/index.")
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            // محاولة استخراج الجودة من الرابط
            val qualityFromUrl = when {
                videoUrl.contains("1080") || videoUrl.contains("1920") -> "1080p"
                videoUrl.contains("720") || videoUrl.contains("1280") -> "720p"
                videoUrl.contains("480") || videoUrl.contains("854") -> "480p"
                videoUrl.contains("360") || videoUrl.contains("640") -> "360p"
                else -> "auto"
            }

            val qualityLabel = if (isM3u8) "HLS" else "MP4"
            val label = "الحلقة ($qualityLabel${if (qualityFromUrl != "auto") " $qualityFromUrl" else ""})"

            // تجنب التكرار بالجودة نفسها
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

    private fun abs(u: String): String = if (u.startsWith("http")) u else "$mainUrl$u"
}