package com.dramix.plugin

import cloudstreamshared.FormatTag
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences
import android.util.Log
import org.jsoup.nodes.Document
import java.net.URLEncoder

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36"

/**
 * Dramix (https://dramix.tv) — كتالوج دراما قصيرة عمودية متعدد المزودين.
 *
 * بنية الموقع (تم فحصها فعلياً):
 *  - الرئيسية /ar: العناوين الرائجة + الجديدة.
 *  - الجديدة /ar/new-dramas[/page/N]: بطاقات `<a href="/ar/drama/<slug>">` تحمل صورة.
 *  - الرائجة /ar/trending[/page/N]: بطاقات مشابهة.
 *  - مزود /ar/provider/<name>[/page/N]: بطاقات ذلك المزود.
 *  - التفاصيل /ar/drama/<slug>: العنوان h1، الغلاف og:image، الملخص، وروابط الحلقات
 *    `/ar/watch/<slug>/<n>`.
 *  - المشاهدة /ar/watch/<slug>/<n>: مشغّل فيديو يحتوي رابط m3u8/mp4 يُستخرج بالتعبير
 *    régulier من HTML الصفحة (يُرفق كل طلب بـ User-Agent lest يُرفض 403).
 *  - البحث /ar/search?q=: نتائج server-side، فارغة إن لم توجد.
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
        "Accept" to "text/html,application/xhtml+xml",
    )

    private fun log(msg: String) { Log.d("Dramix", msg) }

    /**rabaط عرض التصفح: "new-dramas" | "trending" | "provider/<slug>" */
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
            val img = a.select("img").firstOrNull()
            if (img == null) return@forEach
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
            val url = browseUrl(request.data, page)
            val doc = app.get(url, referer = mainUrl, headers = headers(mainUrl)).document
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
            val doc = app.get("$mainUrl/ar/search?q=$enc", referer = mainUrl,
                headers = headers(mainUrl)).document
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
        val html = try { app.get(data, referer = mainUrl, headers = headers(mainUrl)).text }
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

        val isM3u8 = video.endsWith(".m3u8", true) || video.contains("m3u8", true)
        val note = if (isM3u8) "HLS" else "MP4"
        callback(
            newExtractorLink(name, FormatTag.tagged("الحلقة ($note)", video,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO), video,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.headers = headers(mainUrl)
                this.referer = mainUrl
                this.quality = getQualityFromName("720p")
            })
        return true
    }

    private fun abs(u: String): String = if (u.startsWith("http")) u else "$mainUrl$u"
}
