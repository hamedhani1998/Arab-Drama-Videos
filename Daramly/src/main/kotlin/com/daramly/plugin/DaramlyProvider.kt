package com.daramly.plugin

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
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
import com.lagradost.cloudstream3.utils.getQualityFromName
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

private const val TAG = "Daramly"

/** ذاكرة صفحة الرئيسية: كل صفوف الرئيسية تُبنى من طلبٍ واحد فقط. */
private const val HOME_TTL_MS = 10 * 60_000L

/**
 * Daramly — `daramly.com` (دراما قصيرة صينية مترجمة ومدبلجة).
 *
 * بنية الموقع (مقيسة 2026-11):
 *  - الرئيسية صفحة واحدة كبيرة فيها ١٤ قسمًا (`section.sec`)، كل قسم عنوان `h2`
 *    وبطاقات `<a class="card">` (١٢ بطاقة).
 *  - البطاقة: `href="/watch/{slug}"` + `img` + `span.card__title`.
 *  - البحث: `/search?q=` (٣٦ نتيجة/صفحة) والترقيم `&p=N`.
 *  - التصنيف: `/category/{الاسم}` (٣٦/صفحة) والترقيم `?p=N`.
 *  - صفحة العمل: سيرفرات أزرار `button[data-src]`، والقيمة **base64 معكوس**
 *    تعطي رابط الاستضافة مباشرة (voe.sx / rumble.com…). وفيها أيضًا
 *    `a.version-link` بعنوان «سيرفرات إضافية» يقود لصفحةٍ بنفس العمل وبسيرفرات
 *    أخرى — نضمّ سيرفراتها كذلك.
 *
 * لا توجد ملفات ترجمة منفصلة على الموقع (الترجمة مدموجة في الفيديو)، ولذلك
 * لا نرسل أي `SubtitleFile` ونكتفي بكل سيرفرات التشغيل وجوداتها.
 */
class DaramlyProvider : MainAPI() {

    override var mainUrl = "https://daramly.com"
    override var name = "Daramly"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "sec|🚀 الأكثر مشاهدة اليوم" to "الأكثر مشاهدة اليوم",
        "sec|أحدث الإضافات" to "أحدث الإضافات",
        "sec|🔥 الأكثر مشاهدة هذا الأسبوع" to "الأكثر مشاهدة هذا الأسبوع",
        "sec|🎙️ مسلسلات مدبلجة بالعربية" to "مسلسلات مدبلجة",
        "/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%20%D8%AF%D8%B1%D8%A7%D9%85%D8%A7" to "مسلسل دراما",
        "/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%20%D8%B1%D9%88%D9%85%D8%A7%D9%86%D8%B3%D9%8A" to "مسلسل رومانسي",
        "/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%20%D8%A3%D9%83%D8%B4%D9%86" to "مسلسل أكشن",
        "/category/%D9%85%D8%B3%D9%84%D8%B3%D9%84%20%D9%85%D8%AF%D8%A8%D9%84%D8%AC" to "مسلسل مدبلج",
    )

    private val mapper = ObjectMapper()

    @Volatile
    private var homeDoc: Document? = null

    @Volatile
    private var homeAt = 0L

    private val homeLock = Mutex()

    // ============================== الرئيسية ==============================

    /** صفحة الرئيسية مرة واحدة لكل الصفوف (single-flight + صلاحية قصيرة). */
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
            if (data.startsWith("sec|")) {
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                val doc = getHomeDoc()
                val items = if (doc == null) emptyList() else sectionItems(doc, data.removePrefix("sec|"))
                newHomePageResponse(request.name, items)
            } else {
                // صفحات التصنيف تدعم الترقيم `?p=N` (٣٦ عنصرًا/صفحة — مقيس).
                val url = if (page > 1) "$mainUrl$data?p=$page" else "$mainUrl$data"
                val doc = app.get(url, referer = "$mainUrl/").document
                newHomePageResponse(request.name, parseCards(doc))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED data=${request.data}", e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun norm(text: String): String =
        text.filter { it.isLetterOrDigit() || it == ' ' }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun sectionItems(doc: Document, title: String): List<SearchResponse> {
        val want = norm(title)
        for (sec in doc.select("section.sec")) {
            val heading = sec.selectFirst("h2")?.text() ?: continue
            val have = norm(heading)
            if (have.isNotEmpty() && (have == want || have.contains(want))) {
                val items = sec.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }
                if (items.isNotEmpty()) return items
            }
        }
        Log.w(TAG, "section not found title=$title")
        return emptyList()
    }

    // ============================== البطاقات ==============================

    private fun parseCards(root: Element): List<SearchResponse> =
        root.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }

    private fun cardToSearch(card: Element): SearchResponse? {
        val href = card.attr("href").trim()
        if (!href.contains("/watch/")) return null
        val title = card.selectFirst("span.card__title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = card.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster
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
            val html = doc.html()
            val title = jsonLdName(html)
                ?: cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content"))
                ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return null

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
                ?: doc.selectFirst("div.player img")?.attr("src")?.trim()?.ifBlank { null }
            // الوصف الكامل في فقرات `p.film__story`؛ وصف og مقتطع بـ«…».
            val plot = doc.select("p.film__story").joinToString("\n") { it.text().trim() }
                .trim().ifBlank { null }
                ?: doc.selectFirst("p.hero__desc")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifBlank { null }
            val tags = jsonLdGenres(html)
                ?: doc.select("a[href*=/category/]").map { it.text().trim() }
                    .filter { it.isNotBlank() }.distinct().take(8)

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

    /** الاسم النظيف من JSON-LD (`TVSeries.name`) بدل عنوان الصفحة الطويل. */
    private fun jsonLdName(html: String): String? =
        Regex(""""@type"\s*:\s*"TVSeries"[\s\S]{0,400}?"name"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun jsonLdGenres(html: String): List<String>? =
        Regex(""""genre"\s*:\s*\[([^\]]*)]""")
            .find(html)?.groupValues?.get(1)
            ?.let { inner -> Regex(""""([^"]+)"""").findAll(inner).map { it.groupValues[1] }.toList() }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

    private fun cleanTitle(raw: String?): String? =
        raw?.substringBefore("|")?.substringBefore(" - مشاهدة")?.trim()?.takeIf { it.isNotBlank() }

    // ============================== سيرفرات التشغيل ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageUrl = data.lineSequence().firstOrNull()?.trim().orEmpty()
        if (!pageUrl.startsWith("http")) return false

        return try {
            val doc = app.get(pageUrl, referer = "$mainUrl/").document

            // سيرفرات الصفحة الأساسية (الأزرار أولًا، ثم أي عنصر data-src آخر).
            val servers = LinkedHashMap<String, String>()
            collectServers(doc) { u, label -> if (!servers.containsKey(u)) servers[u] = label }

            // صفحات «سيرفرات إضافية» التي يعلنها الموقع لنفس العمل.
            val extras = doc.select("a.version-link")
                .filter { it.text().contains("إضافية") }
                .mapNotNull { a ->
                    val href = a.attr("href").trim()
                    if (href.isBlank()) null else fixUrl(href)
                }
                .filter { it.startsWith("http") && it != pageUrl }
                .distinct()
                .take(3)

            var any = emitAll(servers, subtitleCallback, callback)

            if (extras.isNotEmpty()) {
                val extraDocs = coroutineScope {
                    extras.map { u -> async { runCatching { app.get(u, referer = "$mainUrl/").document }.getOrNull() } }
                        .awaitAll()
                }
                val extraServers = LinkedHashMap<String, String>()
                for (d in extraDocs.filterNotNull()) {
                    collectServers(d) { u, label ->
                        if (!servers.containsKey(u) && !extraServers.containsKey(u)) {
                            extraServers[u] = "إضافي · $label"
                        }
                    }
                }
                if (extraServers.isNotEmpty()) any = emitAll(extraServers, subtitleCallback, callback) || any
            }

            any
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FAILED data=$data", e)
            false
        }
    }

    private suspend fun emitAll(
        servers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (servers.isEmpty()) return false
        val results = coroutineScope {
            servers.map { (u, label) -> async { resolveServer(u, label, subtitleCallback) } }.awaitAll()
        }
        var any = false
        for (links in results) {
            for (link in links) {
                any = true
                callback(link)
            }
        }
        return any
    }

    /**
     * أزرار السيرفرات تحمل `data-src` = base64 معكوس. نفكّها إلى رابط الاستضافة
     * المباشر، ونتجاهل أي قيمة لا تعطي رابطًا صالحًا.
     */
    private fun collectServers(doc: Document, sink: (String, String) -> Unit) {
        val nodes = doc.select("button[data-src]").ifEmpty { doc.select("[data-src]") }
        for (el in nodes) {
            val raw = el.attr("data-src").trim()
            if (raw.isEmpty()) continue
            val url = decodeServer(raw) ?: continue
            val label = if (el.tagName() == "button") el.text().trim() else ""
            sink(url, label.ifBlank { "سيرفر" })
        }
    }

    private fun decodeServer(raw: String): String? {
        val direct = raw.trim()
        if (direct.startsWith("http://") || direct.startsWith("https://")) return direct

        return try {
            val rev = direct.reversed().trimEnd('=')
            val padded = rev + "=".repeat((4 - rev.length % 4) % 4)
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
            decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (e: Exception) {
            Log.w(TAG, "server decode failed ${direct.take(12)}")
            null
        }
    }

    private suspend fun resolveServer(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        if (url.contains("rumble.com", ignoreCase = true)) {
            return resolveRumble(url, label)
        }

        val produced = mutableListOf<ExtractorLink>()
        try {
            loadExtractor(url, "$mainUrl/", { subtitleCallback(it) }) { produced.add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extractor FAILED $label $url :: ${e.message}")
        }

        if (produced.isEmpty() && isMedia(url)) {
            produced.add(
                newExtractorLink(
                    source = name,
                    name = "$label · مباشر",
                    url = url,
                    type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$mainUrl/"
                },
            )
        }

        return produced.map { l ->
            newExtractorLink(
                source = name,
                name = if (l.name.isBlank()) label else "$label · ${l.name}",
                url = l.url,
                type = l.type,
            ) {
                this.quality = l.quality
                this.referer = l.referer
                this.headers = l.headers
                this.extractorData = l.extractorData
            }
        }
    }

    private fun isMedia(url: String): Boolean =
        url.contains(".mp4", true) || url.contains(".m3u8", true) || url.contains(".webm", true)

    /**
     * رامبل: صفحة التضمين تحمل كائن `ua` فيه `tar` (درجات mp4/hls بأحجامها)
     * و`hls.auto` (بثّ تلقائي). لا يوجد مُستخرِج مدمج لرامبل في التطبيق،
     * فنحلّها هنا كي لا يضيع السيرفر.
     */
    private suspend fun resolveRumble(embedUrl: String, label: String): List<ExtractorLink> {
        val html = try {
            app.get(embedUrl, referer = "$mainUrl/").text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "rumble fetch FAILED $embedUrl :: ${e.message}")
            return emptyList()
        }

        val uaStart = html.indexOf("\"ua\":{")
        if (uaStart < 0) return emptyList()
        val json = balancedObject(html, html.indexOf('{', uaStart)) ?: return emptyList()
        val ua = try {
            mapper.readTree(json)
        } catch (e: Exception) {
            Log.w(TAG, "rumble json FAILED")
            return emptyList()
        }

        val out = ArrayList<ExtractorLink>()
        var topH = 0

        val tar = ua.get("tar")
        if (tar != null && tar.isObject) {
            val names = tar.fieldNames()
            while (names.hasNext()) {
                val key = names.next()
                val node = tar.get(key) ?: continue
                val u = node.get("url")?.asText()?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: continue
                val meta = node.get("meta")
                val h = meta?.get("h")?.asInt() ?: key.toIntOrNull() ?: 0
                val size = meta?.get("size")?.asLong() ?: 0L
                if (h in 1..199) continue
                topH = maxOf(topH, h)
                val qName = if (h > 0) "${h}p" else "تلقائي"
                val sizeText = if (size > 0) " · ${formatSize(size)}" else ""
                out.add(
                    newExtractorLink(
                        source = name,
                        name = "$label · Rumble · $qName$sizeText",
                        url = u,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = getQualityFromName(qName)
                    },
                )
            }
        }

        val auto = ua.get("hls")?.get("auto")?.get("url")?.asText()?.replace("\\/", "/")
        if (!auto.isNullOrBlank()) {
            val qName = if (topH > 0) "${topH}p" else "تلقائي"
            out.add(
                newExtractorLink(
                    source = name,
                    name = "$label · Rumble · بثّ مباشر",
                    url = auto,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.quality = getQualityFromName(qName)
                },
            )
        }

        Log.d(TAG, "rumble $label links=${out.size}")
        return out
    }

    /** كائن JSON متوازن الأقواس يبدأ عند `open` (يتجاهل الأقواس داخل السلاسل). */
    private fun balancedObject(scope: String, open: Int): String? {
        if (open < 0 || open >= scope.length || scope[open] != '{') return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in open until scope.length) {
            val c = scope[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return scope.substring(open, i + 1)
                }
            }
        }
        return null
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.0f MB", mb)
    }
}
