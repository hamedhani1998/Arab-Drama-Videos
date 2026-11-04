package com.kingdomdrama.plugin

import android.util.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val TAG = "KingdomDrama"

private const val HOME_TTL_MS = 10 * 60_000L

/**
 * KingDomDrama — `kingdomdramaapp.com` (دراما قصيرة مترجمة ومدبلجة).
 *
 * بنية الموقع (مقيسة 2026-11):
 *  - الصفحة الرئيسية قائمة واحدة كبيرة مقسّمة إلى تبويبات
 *    (`div#latest-all`, `div#billionaire`, `div#ai-universe`, `div#mystery`)،
 *    كل بطاقة `<div class="latest-movie-img-container lazy">` وفيها:
 *    غلافٌ مضمَّن في `style` + `div.movie-title h3 a` + وسم الجودة
 *    (`div.video_quality_movie`).
 *  - البحث: `GET /search?q=` (بطاقاتٌ بنفس الشكل).
 *  - التصنيفات: `/genre/{slug}`، الدول: `/country/{slug}`، السنوات:
 *    `/year/{year}` — كلها تعرض البطاقات نفسها في HTML.
 *  - صفحة العمل: اللاعب Plyr على يوتيوب فقط:
 *    `<div id="player" data-plyr-provider="youtube" data-plyr-embed-id="…">`.
 *    لا سيرفرات أخرى ولا ملفات ترجمة منفصلة في الصفحة؛ الترجمة على يوتيوب.
 *
 * لذلك كل الروابط تُحلّ عبر المُستخرِج المدمج (YouTube)، مع فحص احتياطي
 * لأي `iframe`/`video`/رابط mp4-m3u8 قد يظهر مستقبلًا.
 */
class KingdomDramaProvider : MainAPI() {

    override var mainUrl = "https://kingdomdramaapp.com"
    override var name = "KingdomDrama"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "pane|latest-all" to "أحدث الأعمال",
        "pane|billionaire" to "دراما الملياردير",
        "pane|ai-universe" to "عالم دراما الذكاء الاصطناعي",
        "pane|mystery" to "الولادة من جديد وانتقام",
        "path|country/chinese" to "مسلسلات صينية",
        "path|country/korean" to "مسلسلات كورية",
        "path|year/2026" to "أعمال 2026",
    )

    @Volatile
    private var homeDoc: Document? = null

    @Volatile
    private var homeAt = 0L

    private val homeLock = Mutex()

    // ============================== الرئيسية ==============================

    private suspend fun getHomeDoc(): Document? {
        val now = System.currentTimeMillis()
        homeDoc?.let { if (now - homeAt < HOME_TTL_MS) return it }

        return homeLock.withLock {
            val t = System.currentTimeMillis()
            val cached = homeDoc
            if (cached != null && t - homeAt < HOME_TTL_MS) return@withLock cached

            val fetched = app.get("$mainUrl/", referer = "$mainUrl/").document
            homeDoc = fetched
            homeAt = System.currentTimeMillis()
            fetched
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val data = request.data
            if (page > 1) return newHomePageResponse(request.name, emptyList())

            if (data.startsWith("pane|")) {
                val id = data.removePrefix("pane|")
                val doc = getHomeDoc()
                val pane = doc?.selectFirst("div#$id")
                val items = if (pane == null) emptyList() else parseCards(pane)
                newHomePageResponse(request.name, items)
            } else {
                val path = data.removePrefix("path|")
                val doc = app.get("$mainUrl/$path", referer = "$mainUrl/").document
                newHomePageResponse(request.name, parseCards(doc))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED data=${request.data}", e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    // ============================== البطاقات ==============================

    private fun parseCards(root: Element): List<SearchResponse> =
        root.select("div.latest-movie-img-container")
            .mapNotNull { cardToSearch(it) }
            .distinctBy { it.url }

    private fun cardToSearch(container: Element): SearchResponse? {
        val anchor = container.selectFirst("div.movie-title h3 a")
            ?: container.selectFirst("a[href*=/watch/]")
            ?: return null
        val href = anchor.attr("href").trim()
        if (!href.contains("/watch/")) return null

        val title = anchor.text().trim().takeIf { it.isNotBlank() }
            ?: container.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val style = container.attr("style")
        val poster = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)?.trim()
            ?: container.selectFirst("img")?.attr("src")?.trim()
        val quality = container.selectFirst("div.video_quality_movie span")?.text()?.trim()

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster?.ifBlank { null }
            val hd = quality?.contains("HD", ignoreCase = true) == true
            val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            if (year != null) this.year = year
            if (hd) this.quality = com.lagradost.cloudstream3.SearchQuality.HD
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return try {
            val url = "$mainUrl/search?q=" + URLEncoder.encode(q, "UTF-8")
            val doc = app.get(url, referer = "$mainUrl/").document
            parseCards(doc).ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED q=$query", e)
            null
        }
    }

    // ============================== التفاصيل ==============================

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = "$mainUrl/").document
            val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content"))
                ?: return null

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
            val plot = doc.selectFirst("h1")?.parent()?.selectFirst("p")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }
            val tags = doc.select("p:contains(التصنيف) a").map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = plot
                if (tags.isNotEmpty()) this.tags = tags
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED $url", e)
            null
        }
    }

    private fun cleanTitle(raw: String?): String? =
        raw?.substringBefore("|")?.trim()?.takeIf { it.isNotBlank() }

    // ============================== سيرفرات التشغيل ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (!data.startsWith("http")) return false

        return try {
            val doc = app.get(data, referer = "$mainUrl/").document

            // 1) لاعب الموقع: Plyr على يوتيوب (data-plyr-provider / embed-id).
            val candidates = LinkedHashSet<String>()
            for (el in doc.select("div[data-plyr-embed-id], [data-plyr-provider]")) {
                val id = el.attr("data-plyr-embed-id").trim()
                if (id.isBlank()) continue
                when (el.attr("data-plyr-provider").trim().lowercase()) {
                    "youtube" -> candidates.add("https://www.youtube.com/watch?v=$id")
                    "vimeo" -> candidates.add("https://vimeo.com/$id")
                    else -> if (id.startsWith("http")) candidates.add(id)
                }
            }

            // 2) احتياط: iframe أو عنصر فيديو مباشر.
            for (el in doc.select("iframe[src]")) {
                val src = el.attr("src").trim()
                if (src.startsWith("http")) candidates.add(src)
            }
            for (el in doc.select("video[src], video source[src]")) {
                val src = el.attr("src").trim()
                if (src.startsWith("http")) candidates.add(src)
            }

            // 3) احتياط أخير: أي رابط وسائط صريح داخل الصفحة.
            Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4|webm)""")
                .findAll(doc.html())
                .map { it.value }
                .forEach { candidates.add(it) }

            if (candidates.isEmpty()) {
                Log.e(TAG, "loadLinks no players data=$data")
                return false
            }

            Log.d(TAG, "loadLinks candidates=${candidates.size} data=$data")

            val results = coroutineScope {
                candidates.map { u ->
                    async {
                        val links = mutableListOf<ExtractorLink>()
                        var ok = false
                        try {
                            ok = loadExtractor(u, "$mainUrl/", { subtitleCallback(it) }) { links.add(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "extractor FAILED $u :: ${e.message}")
                        }
                        if (links.isEmpty() && isMedia(u)) {
                            links.add(
                                newExtractorLink(
                                    source = name,
                                    name = "مباشر",
                                    url = u,
                                    type = if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                ) {
                                    this.referer = "$mainUrl/"
                                },
                            )
                        }
                        (ok || links.isNotEmpty()) to links
                    }
                }.awaitAll()
            }

            var any = false
            for ((ok, links) in results) {
                for (link in links) {
                    any = true
                    callback(link)
                }
                if (ok) any = true
            }
            any
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FAILED data=$data", e)
            false
        }
    }

    private fun isMedia(url: String): Boolean =
        url.contains(".mp4", true) || url.contains(".m3u8", true) || url.contains(".webm", true)
}
