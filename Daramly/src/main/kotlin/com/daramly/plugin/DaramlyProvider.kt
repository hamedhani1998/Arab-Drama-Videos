package com.daramly.plugin

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
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
import com.lagradost.cloudstream3.utils.SubtitleHelper
import cloudstreamshared.YoutubeLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

private const val TAG = "Daramly"
private const val SERVER_TIMEOUT_MS = 12_000L
private const val DEAD_HOST_TIMEOUT_MS = 6_000L

/** فحص سريع لرابط المشاهدة قبل تسليمه (يكشف المضيف الميت الذي يُظهر خطأ 2002). */
private const val LINK_PROBE_TIMEOUT_MS = 3_500L

/** مسار تصنيف مُرمَّز (الموقع يفكّ `%20` وليس `+`). */
private fun catPath(name: String): String =
    "/category/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

/**
 * صفوف الرئيسية بالترتيب الذي تعرضه إعدادات المصدر أيضًا:
 * أول أربعة أقسام من صفحة الموقع، ثم عشر صفحات تصنيف.
 */
val DARAMLY_ROWS: List<Pair<String, String>> = listOf(
    "sec|🚀 الأكثر مشاهدة اليوم" to "الأكثر مشاهدة اليوم",
    "sec|أحدث الإضافات" to "أحدث الإضافات",
    "sec|🔥 الأكثر مشاهدة هذا الأسبوع" to "الأكثر مشاهدة هذا الأسبوع",
    "sec|🎙️ مسلسلات مدبلجة بالعربية" to "مسلسلات مدبلجة",
    catPath("مسلسل") to "مسلسل",
    catPath("مسلسل دراما") to "مسلسل دراما",
    catPath("صيني") to "صيني",
    catPath("مسلسل مترجم") to "مسلسل مترجم",
    catPath("دراما") to "دراما",
    catPath("مسلسل رومانسي") to "مسلسل رومانسي",
    catPath("رومانسي") to "رومانسي",
    catPath("مسلسل أكشن") to "مسلسل أكشن",
    catPath("أكشن") to "أكشن",
    catPath("مسلسل مدبلج") to "مسلسل مدبلج",
)

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
class DaramlyProvider(private val prefs: SharedPreferences? = null) : MainAPI() {

    override var mainUrl = "https://daramly.com"
    override var name = "Daramly"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    /** مسار تصنيف مُرمَّز (الموقع يفكّ `%20` وليس `+`). */
    private fun cat(name: String): String = catPath(name)

    override val mainPage = mainPageOf(*DARAMLY_ROWS.toTypedArray())

    // =============================== الإعدادات ===============================

    private fun homeTtl(): Long {
        val min = prefs?.getString(DaramlySettingsBottomSheet.KEY_CACHE_MIN, "10")
            ?.trim()?.toIntOrNull() ?: 10
        return min.coerceIn(1, 24 * 60) * 60_000L
    }

    private fun rowLimit(): Int =
        prefs?.getString(DaramlySettingsBottomSheet.KEY_ROW_LIMIT, "all")
            ?.trim()?.toIntOrNull() ?: 0

    private fun cardsPerRow(): Int =
        prefs?.getString(DaramlySettingsBottomSheet.KEY_CARDS_PER_ROW, "36")
            ?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: Int.MAX_VALUE

    private fun rowVisible(index: Int): Boolean =
        prefs?.getBoolean(DaramlySettingsBottomSheet.rowKey(index), true) != false

    private fun extraServersEnabled(): Boolean =
        prefs?.getBoolean(DaramlySettingsBottomSheet.KEY_EXTRA_SERVERS, true) != false

    private fun extraPages(): Int =
        prefs?.getString(DaramlySettingsBottomSheet.KEY_EXTRA_PAGES, "3")
            ?.trim()?.toIntOrNull()?.coerceIn(0, 3) ?: 3

    private fun rumbleAutoEnabled(): Boolean =
        prefs?.getBoolean(DaramlySettingsBottomSheet.KEY_RUMBLE_AUTO, true) != false

    private fun subtitlesEnabled(): Boolean =
        prefs?.getBoolean(DaramlySettingsBottomSheet.KEY_SUBS, true) != false

    private fun qualityOrder(): String =
        prefs?.getString(DaramlySettingsBottomSheet.KEY_QUALITY_ORDER, "site") ?: "site"

    private fun ytOptions(): YoutubeLinks.Options = YoutubeLinks.Options(
        mode = prefs?.getString(DaramlySettingsBottomSheet.KEY_YT_MODE, "auto") ?: "auto",
        subtitles = subtitlesEnabled(),
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
        val ttl = homeTtl()
        homeDoc?.let { if (now - homeAt < ttl) return it }

        return homeLock.withLock {
            val t = System.currentTimeMillis()
            val cached = homeDoc
            if (cached != null && t - homeAt < ttl) return@withLock cached

            val fetched = app.get("$mainUrl/", referer = "$mainUrl/").document
            homeDoc = fetched
            homeAt = System.currentTimeMillis()
            fetched
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val data = request.data
            val index = DARAMLY_ROWS.indexOfFirst { it.first == data }

            // الإعدادات: صف مخفي أو تجاوز حدّ الصفوف → بلا أي طلب شبكة.
            if (index >= 0 && !rowVisible(index)) {
                return newHomePageResponse(request.name, emptyList())
            }
            val limit = rowLimit()
            if (limit > 0 && index >= limit) {
                return newHomePageResponse(request.name, emptyList())
            }

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
        if (want.isEmpty()) return emptyList()
        val sections = doc.select("section.sec").mapNotNull { sec ->
            val heading = sec.selectFirst("h2")?.text() ?: return@mapNotNull null
            val have = norm(heading)
            if (have.isEmpty()) null else Triple(sec, have, heading)
        }

        // مطابقة تامة أولًا: أسماءٌ مثل «دراما» و«أكشن» هي بادئات/أجزاء من عناوين
        // أقسامٍ أخرى («مسلسل دراما»)، فالمطابقة الجزئية وحدها تُظهر القسم الخطأ.
        val exact = sections.firstOrNull { it.second == want }
        val chosen = exact ?: sections.firstOrNull { it.second.contains(want) }

        if (chosen == null) {
            Log.w(TAG, "section not found title=$title")
            return emptyList()
        }
        val items = chosen.first.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }
        if (items.isEmpty()) Log.w(TAG, "section empty title=$title heading=${chosen.third}")
        return if (items.size > cardsPerRow()) items.take(cardsPerRow()) else items
    }

    // ============================== البطاقات ==============================

    private fun parseCards(root: Element): List<SearchResponse> {
        val all = root.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }
        return if (all.size > cardsPerRow()) all.take(cardsPerRow()) else all
    }

    private fun cardToSearch(card: Element): SearchResponse? {
        val href = card.attr("href").trim()
        if (!href.contains("/watch/")) return null
        val title = card.selectFirst("span.card__title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        // الصور في الموقع نسبية (`/uploads/xxx.webp`): بلا `fixUrl` تظهر البطاقات
        // بلا أيقونة في التطبيق.
        val poster = card.selectFirst("img")?.attr("src")?.trim()?.ifBlank { null }
            ?: card.selectFirst("img")?.attr("data-src")?.trim()?.ifBlank { null }
        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster?.let { fixUrl(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return try {
            val url = "$mainUrl/search?q=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
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

            // الغلاف قد يكون نسبيًا كذلك (`/uploads/…`)، فمرّره على `fixUrl`.
            val rawPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
                ?: doc.selectFirst("div.player img")?.attr("src")?.trim()?.ifBlank { null }
            val poster = rawPoster?.let { fixUrl(it) }
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
            val extras = if (!extraServersEnabled() || extraPages() <= 0) emptyList() else {
                doc.select("a.version-link")
                    .filter { it.text().contains("إضافية") }
                    .mapNotNull { a ->
                        val href = a.attr("href").trim()
                        if (href.isBlank()) null else fixUrl(href)
                    }
                    .filter { it.startsWith("http") && it != pageUrl }
                    .distinct()
                    .take(extraPages())
            }

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
        val lock = Mutex()
        val seen = HashSet<String>()
        var emitted = 0

        coroutineScope {
            servers.map { (u, label) ->
                async {
                    // ★ عزل كل سيرفر: فشل سيرفر واحد (تحويل/شبكة/ترجمة) يجب ألا
                    //   يُسقط بقية الروابط. ومهلة قصيرة لكل سيرفر: voe قد يعلّق
                    //   على مضيف التحويل فيمنع ظهور روابط رامبل الجاهزة.
                    val timeout = if (isVoe(u)) DEAD_HOST_TIMEOUT_MS else SERVER_TIMEOUT_MS
                    val links = try {
                        withTimeoutOrNull(timeout) { resolveServer(u, label, subtitleCallback) }
                            ?: emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.e(TAG, "server FAILED $label $u :: ${e.message}", e)
                        emptyList()
                    }

                    // ★ لا نُسلّم رابطًا على مضيف ميت: المشغّل يعرضه «خطأ شبكة 2002»
                    //   (ERROR_CODE_IO_NETWORK_CONNECTION_FAILED). نتحقق فقط من
                    //   الاستضافات المشكوك فيها (voe) كي لا نُبطئ رامبل السليمة.
                    val live = if (isVoe(u)) links.filter { reachable(it) } else links
                    if (live.size != links.size) {
                        Log.w(TAG, "server $label dropped ${links.size - live.size} dead link(s)")
                    }
                    val sorted = when (qualityOrder()) {
                        "desc" -> live.sortedByDescending { it.quality }
                        "asc" -> live.sortedBy { it.quality }
                        else -> live
                    }

                    // ★ البثّ الفوري: نُسلّم روابط كل سيرفر لحظة جهوزيتها بدل انتظار
                    //   `awaitAll` — روابط رامبل تظهر خلال ثانية، وvoe الميت يُهجر بمهلته.
                    lock.withLock {
                        for (l in sorted) {
                            if (seen.add(l.url)) {
                                emitted++
                                callback(l)
                            }
                        }
                    }
                    Log.d(TAG, "server OK $label links=${live.size}")
                }
            }.awaitAll()
        }

        Log.d(TAG, "emitAll servers=${servers.size} links=$emitted")
        return emitted > 0
    }

    private fun isVoe(url: String): Boolean = url.contains("voe", ignoreCase = true)

    /** هل الرابط يفتح فعلًا؟ ‏HEAD قصير: أي رمز HTTP يعني أن المضيف حيّ. */
    private suspend fun reachable(link: ExtractorLink): Boolean =
        withTimeoutOrNull(LINK_PROBE_TIMEOUT_MS) {
            try {
                val code = app.head(link.url, referer = link.referer ?: "$mainUrl/").code
                code in 200..399 || code == 403 || code == 405 || code == 416
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "link probe FAILED ${link.url.take(80)} :: ${e.message}")
                false
            }
        } ?: false

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
            return resolveRumble(url, label, subtitleCallback)
        }

        // يوتيوب: بعض السيرفرات تُضمّن فيديو يوتيوب مباشرةً؛ نحلّه بمُحلِّل
        // المصدر «ARY العربية» (NewPipe → صفحة watch → المُستخرِج المدمج).
        if (YoutubeLinks.isYoutube(url)) {
            val vid = YoutubeLinks.videoIdOf(url) ?: return emptyList()
            val out = mutableListOf<ExtractorLink>()
            YoutubeLinks.resolve(vid, name, TAG, { subtitleCallback(it) }, { out.add(it) }, ytOptions())
            return out.map { l ->
                newExtractorLink(
                    source = name,
                    name = listOfNotNull(
                        label,
                        l.name.trim().takeIf { it.isNotBlank() },
                        typeLabel(l.type, l.url),
                    ).joinToString(" · "),
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

        val produced = mutableListOf<ExtractorLink>()
        try {
            loadExtractor(url, "$mainUrl/", { subtitleCallback(it) }) { produced.add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "extractor FAILED $label $url :: ${e.message}")
        }

        if (produced.isEmpty()) {
            // بعض الاستضافات (voe) تُرجع صفحة تحويل JS بدل المشغّل. المُستخرِج
            // المدمج يتعامل معها، لكن إن فشل نتبع التحويل بأنفسنا مرة واحدة كي
            // لا يضيع السيرفر من القائمة.
            retryViaRedirect(url, "$mainUrl/", subtitleCallback) { produced.add(it) }
        }

        if (produced.isEmpty() && isMedia(url)) {
            val mediaType = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            produced.add(
                newExtractorLink(
                    source = name,
                    name = "$label · ${typeLabel(mediaType, url)} · مباشر",
                    url = url,
                    type = mediaType,
                ) {
                    this.referer = "$mainUrl/"
                },
            )
        }

        return produced.map { l ->
            newExtractorLink(
                source = name,
                name = listOfNotNull(
                    label,
                    l.name.trim().takeIf { it.isNotBlank() },
                    typeLabel(l.type, l.url),
                ).joinToString(" · "),
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

    /** يتبع `window.location(.href) = '<url>'` ثم يعيد المحاولة على الرابط الجديد. */
    private suspend fun retryViaRedirect(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        add: (ExtractorLink) -> Unit,
    ) {
        val html = try {
            app.get(url, referer = referer).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val next = Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { it.groupValues[1].trim().takeIf { u -> u.startsWith("http") } }
            .firstOrNull { it != url }
            ?: return
        try {
            loadExtractor(next, referer, { subtitleCallback(it) }) { add(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "redirect extractor FAILED $next :: ${e.message}")
        }
    }

    /**
     * رامبل: صفحة التضمين تحمل كائن `ua` فيه `tar` (درجات mp4/hls بأحجامها)
     * و`hls.auto` (بثّ تلقائي). لا يوجد مُستخرِج مدمج لرامبل في التطبيق،
     * فنحلّها هنا كي لا يضيع السيرفر.
     */
    private suspend fun resolveRumble(
        embedUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): List<ExtractorLink> {
        val html = try {
            app.get(embedUrl, referer = "$mainUrl/").text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "rumble fetch FAILED $embedUrl :: ${e.message}")
            return emptyList()
        }

        val out = ArrayList<ExtractorLink>()
        val seenLinks = HashSet<String>()
        val seenSubs = HashSet<String>()
        var topH = 0

        // ★ كل إدخال `m.f["id"]={…}` يحمل `ua` (الدرجات) و`cc` (الترجمات) **شقيقين**،
        //   و`cc` ليس داخل `ua` إطلاقاً — قِيس 2026-11-05 على مقطع دراملي:
        //   `m.f["v7e8yi2"]={… "ua":{tar/hls…}, … "cc":{"ar":{"language":"Arabic","path":"…vtt"}}}`.
        //   القراءة القديمة كانت `ua.get("cc")` (وهو مصفوفة في شكلٍ آخر) فأضاعت
        //   الترجمة كلّها. نقرأ الإدخال كاملاً ثم نأخذ `ua` و`cc` منه.
        val entries = ArrayList<Pair<JsonNode?, JsonNode?>>()
        for (m in rumbleEntryRe.findAll(html)) {
            val open = html.indexOf('{', m.range.last + 1)
            val entry = balancedObject(html, open) ?: continue
            // ★ لا نُحلّل الإدخال كاملاً: آخرُه قد يحوي JS خاماً
            //   (`…,"viewer_id":"…",loaded:h()}`) فيرفضه محلّل JSON. نقتطع
            //   الكائنين الشقيقين `ua` و`cc` من نصّ الإدخال وكلاهما JSON سليم.
            val ua = jsonAt(entry, "\"ua\":{")
                ?: jsonAt(entry, "\"u\":{")?.takeIf { it.has("tar") }
            val cc = jsonAt(entry, "\"cc\":{")
            entries.add(ua to cc)
        }
        if (entries.isEmpty()) {
            // احتياط: صفحات قديمة بلا حاوية `m.f` — كائن `ua` وحده.
            val uaStart = html.indexOf("\"ua\":{")
            if (uaStart < 0) return emptyList()
            val ua = balancedObject(html, html.indexOf('{', uaStart))
                ?.let { runCatching { mapper.readTree(it) }.getOrNull() }
                ?: return emptyList()
            entries.add(ua to (ua.get("cc") ?: standaloneCc(html)))
        }

        for ((ua, cc) in entries) {
            if (ua != null) {
                val tar = ua.get("tar")
                if (tar != null && tar.isObject) {
                    val names = tar.fieldNames()
                    while (names.hasNext()) {
                        val key = names.next()
                        val node = tar.get(key) ?: continue
                        val u = node.get("url")?.asText()?.replace("\\/", "/")
                            ?.takeIf { it.isNotBlank() } ?: continue
                        val meta = node.get("meta")
                        // مفتاح `tar` هو تسمية رامبل للجودة (240/360/480/720…)؛ و`meta.h`
                        // ارتفاعٌ فعليّ قد يكون رأسيًّا (640 لحلقة 360p عمودية) فلا يصلح
                        // للتسمية إلا عند غياب المفتاح الرقمي.
                        val labeled = key.toIntOrNull()
                        val h = labeled ?: (meta?.get("h")?.asInt() ?: 0)
                        val size = meta?.get("size")?.asLong() ?: 0L
                        if (h in 1..199) continue
                        if (!seenLinks.add(u)) continue
                        topH = maxOf(topH, h)
                        val qName = if (h > 0) "${h}p" else "تلقائي"
                        val sizeText = if (size > 0) " · ${formatSize(size)}" else ""
                        out.add(
                            newExtractorLink(
                                source = name,
                                name = "$label · Rumble · $qName · M3U8 (HLS)$sizeText",
                                url = u,
                                type = ExtractorLinkType.M3U8,
                            ) {
                                this.quality = getQualityFromName(qName)
                                this.referer = "https://rumble.com/"
                                this.headers = mapOf("Referer" to "https://rumble.com/")
                            },
                        )
                    }
                }

                val auto = ua.get("hls")?.get("auto")?.get("url")?.asText()?.replace("\\/", "/")
                if (rumbleAutoEnabled() && !auto.isNullOrBlank() && seenLinks.add(auto)) {
                    val qName = if (topH > 0) "${topH}p" else "تلقائي"
                    out.add(
                        newExtractorLink(
                            source = name,
                            name = "$label · Rumble · بثّ تلقائي ABR · M3U8 (HLS) · $qName",
                            url = auto,
                            type = ExtractorLinkType.M3U8,
                        ) {
                            this.quality = getQualityFromName(qName)
                            this.referer = "https://rumble.com/"
                            this.headers = mapOf("Referer" to "https://rumble.com/")
                        },
                    )
                }
            }

            // ترجمات الإدخال — كائن `{ar:{language,path}}` أو مصفوفة `[{language,url}]`.
            // ★ لا نستخدم `newSubtitleFile` هنا: هي suspend وقد تختلف بصمتها بين
            //   نُسخ النواة، وفشلها داخل كوروتين السيرفر كان يُسقط كل الروابط.
            //   الباني المباشر `SubtitleFile` مستقرّ ولا يحتاج سياق تعليق.
            if (subtitlesEnabled() && cc != null) {
                try {
                    emitRumbleSubs(cc, seenSubs) { subtitleCallback(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "rumble subs FAILED ${e.message}", e)
                }
            }
        }

        Log.d(TAG, "rumble $label links=${out.size} subs=${seenSubs.size}")
        return out
    }

    /** إدخالات رامبل: `m.f["<id>"]={…}` — قابلة للتعدّد (إعلان + مقطع). */
    private val rumbleEntryRe = Regex("""m\.f\["[^"]*"]\s*=""")

    /**
     * `cc` في رامبل شكلان: كائنٌ مفتاحه رمز اللغة وقيمته `{language, path}`،
     * أو مصفوفة `[{language, url}]`. نسلّم كل رابط مرة واحدة بلغةٍ مفهومة.
     */
    private fun emitRumbleSubs(
        cc: JsonNode,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        fun add(url: String?, lang: String?) {
            val u = url?.trim()?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return
            val full = when {
                u.startsWith("http://") || u.startsWith("https://") -> u
                u.startsWith("//") -> "https:$u"
                else -> "https://rumble.com/" + u.trimStart('/')
            }
            if (!seen.add(full)) return
            subtitleCallback(SubtitleFile(subLabel(lang ?: "ar"), full))
        }

        if (cc.isArray) {
            for (n in cc) {
                add(
                    n.get("url")?.asText() ?: n.get("path")?.asText(),
                    n.get("language")?.asText() ?: n.get("lang")?.asText(),
                )
            }
        } else if (cc.isObject) {
            val it = cc.fields()
            while (it.hasNext()) {
                val (key, n) = it.next()
                add(
                    n.get("path")?.asText() ?: n.get("url")?.asText(),
                    n.get("language")?.asText() ?: n.get("lang")?.asText() ?: key,
                )
            }
        }
    }

    /** آخر احتياط: كائن `"cc":{…}` مستقلّ في الصفحة (بلا حاوية إدخال). */
    private fun standaloneCc(html: String): JsonNode? {
        val i = html.indexOf("\"cc\":{")
        if (i < 0) return null
        val json = balancedObject(html, html.indexOf('{', i)) ?: return null
        return runCatching { mapper.readTree(json) }.getOrNull()
    }

    /**
     * اسم اللغة كما يعرضه المشغّل: «العربية Arabic».
     * الزوجُ مقصود: العرض يكتب الحقل حرفياً، والوسم/المرشِّح يحتاجان الاسم
     * اللاتيني — والاسم العربي وحده يعطي وسم IETF فارغاً.
     */
    private fun subLabel(raw: String): String {
        val r = raw.trim()
        if (r.isEmpty()) return "العربية Arabic"
        val tag = SubtitleHelper.fromCodeToLangTagIETF(r)
            ?: SubtitleHelper.fromLanguageToTagIETF(r, true)
        if (tag == null) {
            return when (r.lowercase()) {
                "arabic", "العربية" -> "العربية Arabic"
                else -> r
            }
        }
        val ar = SubtitleHelper.fromTagToLanguageName(tag, "ar")
        val en = SubtitleHelper.fromTagToEnglishLanguageName(tag)
        if (ar.isNullOrBlank() && en.isNullOrBlank()) return r
        if (ar.isNullOrBlank()) return en ?: r
        if (en.isNullOrBlank() || ar.equals(en, true)) return ar
        return "$ar $en"
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

    /** يقتطع الكائن الذي يبدأ بعد `marker` ويُحلّله (الماركر جزءٌ من النصّ لا يُحلَّل). */
    private fun jsonAt(scope: String, marker: String): JsonNode? {
        val i = scope.indexOf(marker)
        if (i < 0) return null
        val json = balancedObject(scope, scope.indexOf('{', i)) ?: return null
        return runCatching { mapper.readTree(json) }.getOrNull()
    }

    /**
     * نوعية الفيديو كما يراها المستخدم في قائمة السيرفرات:
     * `M3U8 (HLS)` قائمة بثّ تجزئية، `MP4` ملف مباشر، `DASH` قائمة MPD.
     */
    private fun typeLabel(type: ExtractorLinkType, url: String): String = when (type) {
        ExtractorLinkType.M3U8 -> "M3U8 (HLS)"
        ExtractorLinkType.DASH -> "DASH (MPD)"
        ExtractorLinkType.TORRENT -> "TORRENT"
        ExtractorLinkType.MAGNET -> "MAGNET"
        else -> when {
            url.contains(".m3u8", true) -> "M3U8 (HLS)"
            url.contains(".mpd", true) -> "DASH (MPD)"
            url.contains(".mp4", true) -> "MP4"
            url.contains(".webm", true) -> "WebM"
            url.contains(".mkv", true) -> "MKV"
            else -> "MP4"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024) else String.format("%.0f MB", mb)
    }
}
