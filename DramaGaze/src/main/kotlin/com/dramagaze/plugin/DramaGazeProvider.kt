package com.dramagaze.plugin

import cloudstreamshared.FormatTag
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences
import android.util.Log
import org.jsoup.nodes.Document
import java.net.URLEncoder

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

/**
 * DramaGaze (https://dramagaze.com) — كتالوج دراما قصيرة عمودية.
 *
 * بنية الموقع (تم فحصها):
 *  - الرئيسية /ar، والتصفح /ar/browse (كل التصنيفات)،
 *    /ar/browse/<category> (رومانسي، انتقام، ...)، والترقيم ?sort=new&page=N.
 *  - بطاقات التصفح: `<a href="/ar/drama/<slug>-<id>">` تحمل عنواناً و صورة
 *    (img.dramagaze.com).
 *  - التفاصيل /ar/drama/<slug>-<id> (محمية بـ Cloudflare — تُمرَّر ترويسات
 *    المتصفح الكاملة lest يُرفض 403).
 *  - روابط الحلقات/المشاهدة تُستخرج من صفحة التفاصيل، وروابط البث m3u8/mp4
 *    بالتعبير régulier من صفحة المشاهدة.
 */
class DramaGazeProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "DramaGaze"
    override var mainUrl = "https://dramagaze.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "browse" to "الأحدث",
        "browse/romance" to "الرومانسية",
        "browse/ceo-boss" to "الرئيس التنفيذي",
        "browse/revenge" to "الانتقام",
        "browse/mafia" to "مافيا",
        "browse/marriage" to "الزواج",
        "browse/fantasy" to "خيال",
        "browse/historical" to "تاريخي",
        "browse/action" to "أكشن ومغامرة",
        "browse/mystery" to "الغموض",
        "browse/school" to "الحياة المدرسية",
        "browse/pregnancy" to "الحمل والأطفال",
        "browse/sci-fi" to "خيال علمي",
        "browse/medical" to "طبي",
        "browse/thriller" to "إثارة"
    )

    private fun headers(referer: String = mainUrl) = mapOf(
        "User-Agent" to UA,
        "Referer" to "$referer/",
        "Accept" to "text/html,application/xhtml+xml",
    )

    private fun log(msg: String) { Log.d("DramaGaze", msg) }

    private fun browseUrl(data: String, page: Int): String {
        val base = "$mainUrl/ar/$data"
        val isAll = data == "browse"
        return when {
            page <= 1 -> if (isAll) "$base?sort=new" else base
            isAll -> "$base?sort=new&page=$page"
            else -> "$base?page=$page"
        }
    }

    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = java.util.HashSet<String>()
        doc.select("a[href*='/drama/']").forEach { a ->
            val href = a.attr("href").trim()
            if (!href.contains("/drama/") || !seen.add(href)) return@forEach
            val img = a.select("img").firstOrNull() ?: return@forEach
            val title = a.attr("title").trim()
                .ifEmpty { a.select("h2, h3").firstOrNull()?.text()?.trim().orEmpty() }
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
            val doc = app.get(browseUrl(request.data, page), referer = "$mainUrl/ar",
                headers = headers("$mainUrl/ar")).document
            val cards = parseCards(doc)
            if (cards.isEmpty()) null else newHomePageResponse(request.name, cards)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val doc = app.get("$mainUrl/ar/search?q=$enc", referer = "$mainUrl/ar",
                headers = headers("$mainUrl/ar")).document
            parseCards(doc)
        } catch (e: Exception) {
            log("DramaGaze: search failed for '$q' :: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = "$mainUrl/ar", headers = headers("$mainUrl/ar")).document

            val title = doc.select("h1").firstOrNull()?.text()?.trim()
                ?: doc.select("meta[property=og:title]").attr("content").trim()
                ?: return null

            val poster = doc.select("meta[property=og:image]").attr("content")
                .ifEmpty { doc.select("img").firstOrNull()?.attr("src") ?: "" }

            val plot = doc.select("meta[property=og:description]").attr("content")
                .ifEmpty { doc.select("meta[name=description]").attr("content") }
                .ifEmpty { doc.select(".synopsis, .description").firstOrNull()?.text() ?: "" }

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
            }
        } catch (e: Exception) {
            log("DramaGaze: load failed for $url :: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try { app.get(data, referer = "$mainUrl/ar", headers = headers("$mainUrl/ar")).text }
        catch (e: Exception) { return false }
        val fixed = html.replace("\\/", "/").replace("\\u0026", "&")
        val patterns = listOf(
            Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*"""),
            Regex("""https?://[^"'\\\s]+\.mp4[^"'\\\s]*"""),
            Regex("""["']file["']\s*:\s*["']([^"']+)["']"""),
        )
        var video: String? = null
        for (rx in patterns) {
            val m = rx.find(fixed) ?: continue
            video = when {
                rx.pattern.contains("file") -> m.groupValues.getOrNull(1) ?: m.value
                else -> m.value
            }.trim()
            if (video.isNotEmpty() && video.startsWith("http")) break
        }
        if (video.isNullOrBlank()) return false

        val isM3u8 = video.contains("m3u8", true)
        val note = if (isM3u8) "HLS" else "MP4"
        callback(
            newExtractorLink(name, FormatTag.tagged("الحلقة ($note)", video,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO), video,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.headers = headers("$mainUrl/ar")
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName("720p")
            })
        return true
    }
}