package com.nartoedge.plugin

import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import nartoshared.UA
import nartoshared.loadNartoLinks
import nartoshared.NartoFetch
import nartoshared.NartoRowCache
import nartoshared.nartoGetPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val EDGE_HOST = "https://edge.narto-drama.com"

// مفتاح صفّ «الاقتراحات العربية» في mainPage.data(). قيمةٌ ثابتة لا تصادف أي استعلام
// بحث عربي (البادئة /* تعني: هذا صفّ الموجز لا بحث). getMainPage يتفرّع عليها.
private const val SUGGEST_ROW = "/*suggest*/"

// ★ سقف زمني صارم لكل صفّ من صفوف الرئيسية (نفس تعليل NartoDrama):
// التطبيق يرسم الرئيسية بعد اكتمال كل الصفوف، وكل صفحة كانت تُطلب بـ٤ محاولات × ٣٠ ثانية،
// فصفٌّ واحد على شبكة متعثّرة كان يؤجّل الرسم دقائق فيبدو أنّ «الواجهة الرئيسية لا تظهر».
private const val ROW_BUDGET_MS = 40_000L
private const val ROW_GET_TIMEOUT_MS = 10_000L
private const val ROW_GET_ATTEMPTS = 1

// مفتاح الموجز في مخزن القرص (بقية المفاتيح هي استعلامات الصفوف نفسها).
private const val HOME_CACHE_KEY = "home"

// Standalone "Edge Narto Drama" extension — pinned to https://edge.narto-drama.com ONLY.
// This provider is 100% independent: its own cache, its own refresh channel, its own cooldown
// handling — it never treats the apex host as its own.
//
// Every playback decision — refresh-source, stale retry, cooldown, liveness probe, link
// emission — lives in ../../narto-shared, exactly as in "Narto Drama". The only difference
// between the two sources is the host they pin: this one starts at the edge host. That
// separation used to be a full copy of the file, and the copies drifted: the stale-token retry,
// the cancellation rethrow, the cooldown cap, the parallel quality probe and the
// dead-by-verdict rule were all fixed here and all missing here. A copy cannot drift from
// itself.

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://edge.narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

class EdgeNartoProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "Edge Narto Drama"
    override var mainUrl = EDGE_HOST
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Main screen = one section per tab, each a distinct Arabic query (verified live: different
    // queries return DIFFERENT feeds, 0 overlap). fetchSearch caches per query, and getMainPage
    // runs ALL tabs in parallel so hopping tabs never re-fetches. First paint uses the requested
    // tab's own feed.
    //
    // v3: the first row is «الاقتراحات العربية» — the site's own home feed, so the suggested
    // Arabic dramas show at the top of the screen. Every row is shown/hidden from the settings
    // (nte_row_<slug>, default visible) and the rows are never allowed to become empty.
    private val suggestRow = SUGGEST_ROW to "الاقتراحات العربية"

    private val categoryRows = listOf(
        "دراما" to "🎬 دراما",
        "مدبلج" to "🎙️ مدبلج",
        "رومانسي" to "💕 رومانسي",
        "أكشن" to "⚔️ أكشن",
    )

    override val mainPage = mainPageOf(
        *visibleRows().map { (d, l) -> d to l }.toTypedArray()
    )

    // الصف ← مفتاح التفضيلات (nte_row_<slug>). الربط هنا لأن data الصف قيمته عربية.
    private fun rowKeyFor(data: String): String = when (data) {
        SUGGEST_ROW -> EdgeNartoSettingsBottomSheet.KEY_ROW_SUGGEST
        "دراما" -> EdgeNartoSettingsBottomSheet.KEY_ROW_DRAMA
        "مدبلج" -> EdgeNartoSettingsBottomSheet.KEY_ROW_DUBBED
        "رومانسي" -> EdgeNartoSettingsBottomSheet.KEY_ROW_ROMANCE
        "أكشن" -> EdgeNartoSettingsBottomSheet.KEY_ROW_ACTION
        else -> "nte_row_${data}"
    }

    // الصفوف التي لم يخفها المستخدم. إن أخفى الكل نُبقي صف «الاقتراحات» حتى لا تفرغ
    // الواجهة الرئيسية تماماً (وإلا لا يبقى للمستخدم طريق للرجوع من الإعدادات).
    // اسم المزوّد يُضاف إلى عنوان كل صفّ كي يُعرف مصدره من العنوان نفسه.
    private fun rowTitle(label: String): String = "$name • $label"

    private fun visibleRows(): List<Pair<String, String>> {
        val shown = (listOf(suggestRow) + categoryRows).filter { (data, _) ->
            prefs?.getBoolean(rowKeyFor(data), true) != false
        }
        return shown.ifEmpty { listOf(suggestRow) }.map { (data, label) -> data to rowTitle(label) }
    }

    // Referer for all requests/links — the apex domain. This is the ONLY value shared in spirit
    // with the other source and it is just an HTTP Referer header the narto stream/subtitle
    // servers expect; it does NOT merge the two sources (each still browses and fetches
    // playback from its OWN pinned domain above).
    private val nartoOrigin = "https://narto-drama.com"

    // ★ THE ONLY DIFFERENCE between the two sources: which host leads the refresh-source call.
    // This one is pinned to edge, so edge is first and there is no fallback host — if edge fails,
    // that is this source's own failure, exactly as intended by keeping it independent.
    private val fetch = NartoFetch(listOf(EDGE_HOST), nartoOrigin, "EdgeNarto")

    // Per-tab in-memory cache so re-entering / tab-hopping serves the list instantly instead of
    // re-fetching the heavy /search page.
    private val searchCache = HashMap<String, String>()

    // Cached edge home "/" feed — the guaranteed never-blank fallback (verified live: it always
    // returns the content row, unlike a single /search which can fail or 520). Posters are
    // absolute (img.nartodrama-api.online) and cards carry edge detail URLs, so the fallback
    // renders identically under any tab name.
    private var homeFeedCache: String? = null

    // ★ مخزن القرص + نطاق الخلفية (نفس تعليل NartoDrama): رسمٌ فوري بعد أول تشغيل
    // بدل انتظار صفحة الموجز (٤٥٧KB/~٣٥ث) في كل إقلاع.
    private val rowCache = NartoRowCache(prefs, "nte_v12_row_")
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Parse the ListItem JSON array embedded in a /search HTML page ----
    private fun parseSearchItems(html: String): List<SearchHit> {
        return try {
            val marker = "\"@type\":\"ListItem\""
            val start = html.indexOf(marker)
            if (start < 0) return emptyList()
            var arrStart = start
            while (arrStart > 0 && html[arrStart] != '[') arrStart--
            if (html[arrStart] != '[') return emptyList()
            var depth = 0
            var arrEnd = -1
            for (k in arrStart until html.length) {
                when (html[k]) {
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { arrEnd = k; break } }
                }
            }
            if (arrEnd < 0) return emptyList()
            parseItemsJson(html.substring(arrStart, arrEnd + 1))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** نفس التحليل لكن من مصفوفة JSON جاهزة (النسخة المخزّنة على القرص). */
    private fun parseItemsJson(json: String): List<SearchHit> =
        try {
            mapper.readValue(
                json,
                object : com.fasterxml.jackson.core.type.TypeReference<List<SearchHit>>() {}
            ).filter { !it.url.isNullOrBlank() && !it.name.isNullOrBlank() }
        } catch (e: Exception) {
            emptyList()
        }

    private fun itemsJson(items: List<SearchHit>): String? =
        try {
            mapper.writeValueAsString(items)
        } catch (e: Exception) {
            null
        }

    private suspend fun fetchSearch(q: String, timeoutMs: Long = ROW_GET_TIMEOUT_MS, attempts: Int = ROW_GET_ATTEMPTS): String? {
        searchCache[q]?.let { return it }
        val urlEncQ = java.net.URLEncoder.encode(q, "UTF-8")
        try {
            val html = nartoGetPage(
                "$mainUrl/search?lang=ar-SA&q=$urlEncQ&page=1&perPage=12",
                nartoOrigin, "EdgeNarto",
                timeoutMs = timeoutMs, attempts = attempts,
            )
            if (html != null && html.contains("\"@type\":\"ListItem\"")) {
                searchCache[q] = html
                return html
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CloudStream يلغي البحث القديم حين يكتبه المستخدم آخره. ابتلاعُ الإلغاء هنا
            // كان يحوّله إلى «فشل شبكة» ثم يستمرّ النداء في إرسال طلبات بعده — قِسناه
            // على الجهاز 2026-10-06 (NartoDrama خمس مرات في نَفَسٍ واحد). الإلغاء ليس
            // عطلاً في المصدر، ويجب أن يخرج فوراً.
            throw e
        } catch (e: Exception) {
            android.util.Log.e("EdgeNarto", "search fetch error", e)
        }
        return null
    }

    // ★ طلبٌ واحد مشترك لموجز الصفحة (single-flight) — نفس تعليل NartoDrama:
    // الموجز ٤٥٧KB/٣٥ث على شبكة المستخدم، ولو طلبه كل صفٍّ عند فشله لتزاحمت خمسة
    // تنزيلات لنفس الملف وسقط بعضها. الآن تنزيل واحد، والبقية تقرأه من الذاكرة محلَّلًا.
    private val homeFeedLock = kotlinx.coroutines.sync.Mutex()
    private var homeFeedItems: List<SearchHit>? = null

    private suspend fun fetchHomeItems(): List<SearchHit>? {
        homeFeedItems?.let { return it }

        // ★ نسخة القرص أولًا (رسم فوري)، والتحديث في الخلفية إن مرّت مدة الصلاحية.
        rowCache.get(HOME_CACHE_KEY)?.let { entry ->
            val cached = parseItemsJson(entry.json)
            if (cached.isNotEmpty()) {
                homeFeedItems = cached
                if (System.currentTimeMillis() - entry.at > NartoRowCache.STALE_AFTER_MS) {
                    bgScope.launch { runCatching { refreshHomeFeed(force = true) } }
                    android.util.Log.e(
                        "EdgeNarto",
                        "home feed from DISK age=${(System.currentTimeMillis() - entry.at) / 1000}s items=${cached.size}"
                    )
                }
                return cached
            }
        }
        return refreshHomeFeed(force = false)
    }

    /** جلب الموجز من الشبكة (single-flight) وتخزينه على القرص عند النجاح. */
    private suspend fun refreshHomeFeed(force: Boolean): List<SearchHit>? {
        homeFeedLock.lock()
        try {
            if (!force) homeFeedItems?.let { return it }
            // دورتان (محاولتان × ١٢ث): الموجز هو مصدر كل الصفوف، فلا نسقط بعد فشل واحد.
            for (round in 1..2) {
                val html = nartoGetPage(
                    "$mainUrl/", nartoOrigin, "EdgeNarto",
                    timeoutMs = 12_000L, attempts = 1,
                )
                if (html != null && html.contains("\"@type\":\"ListItem\"")) {
                    val parsed = parseSearchItems(html)
                    if (parsed.isNotEmpty()) {
                        homeFeedCache = html
                        homeFeedItems = parsed
                        itemsJson(parsed)?.let { rowCache.put(HOME_CACHE_KEY, it) }
                        return parsed
                    }
                }
            }
            return homeFeedItems
        } finally {
            homeFeedLock.unlock()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val isSuggest = request.data == SUGGEST_ROW
            val q = if (isSuggest) "" else request.data.trim()

            // ★ No warm, no waiting on the other rows.
            //
            // This used to awaitAll() every visible row before it even started the requested
            // row's fetch, so the first paint waited on the SLOWEST row. And CloudStream itself
            // calls getMainPage for every row of mainPage at once on each render — so the warm
            // asked the slow server for the same pages twice (measured on device: a 37.9 s row,
            // with the home screen painted only after it). Each call now fetches its own row
            // immediately; searchCache/homeFeedCache keep tab-hops and re-renders instant.
            //
            // ★ And every fetch is wrapped in a single withTimeoutOrNull budget: even a frozen
            // network can never hold a row longer than ROW_BUDGET_MS, so the home screen always
            // paints. (It used to be two sequential budgets, 25+25 — measured 50 s per row.)
            // ★ الصفّ من القرص أولًا: لا شبكة ولا انتظار (نفس تعليل NartoDrama).
            if (!isSuggest) {
                val cachedEntry = rowCache.get(q)
                val cachedItems = cachedEntry?.let { parseItemsJson(it.json) } ?: emptyList()
                if (cachedItems.isNotEmpty()) {
                    val age = System.currentTimeMillis() - (cachedEntry?.at ?: 0L)
                    if (age > NartoRowCache.STALE_AFTER_MS) bgScope.launch { refreshRow(q) }
                    android.util.Log.e(
                        "EdgeNarto",
                        "getMainPage q=$q DISK items=${cachedItems.size} age=${age / 1000}s ms=${System.currentTimeMillis() - t0}"
                    )
                    return newHomePageResponse(
                        request.name,
                        cachedItems.take(12).mapNotNull { it.toSearchResponse() }
                    )
                }
            }

            var fromFallback = false
            val items: List<SearchHit> = kotlinx.coroutines.withTimeoutOrNull(ROW_BUDGET_MS) {
                if (isSuggest) {
                    fetchHomeItems()
                } else {
                    // محاولة قصيرة (٦ث، طلب واحد) لقسم هذا الصفّ ثم الموجز المشترك.
                    val own = fetchSearch(q, timeoutMs = 6_000L, attempts = 1)
                        ?.let { parseSearchItems(it) }
                    if (!own.isNullOrEmpty()) {
                        itemsJson(own)?.let { rowCache.put(q, it) }
                        own
                    } else {
                        fromFallback = true
                        fetchHomeItems()
                    }
                }
            } ?: emptyList()

            android.util.Log.e(
                "EdgeNarto",
                "getMainPage q=$q ms=${System.currentTimeMillis() - t0} items=${items.size} fallback=$fromFallback"
            )
            if (items.isEmpty()) {
                // صفٌّ فارغ بدل null: انظر التعليق المطابق في NartoDrama.
                return newHomePageResponse(request.name, emptyList())
            }
            newHomePageResponse(request.name, items.take(12).mapNotNull { it.toSearchResponse() })
        } catch (e: kotlinx.coroutines.CancellationException) {
            // لُغي النداء (تغيّر القسم أو غادر المستخدم): نُعيده كما هو بدل تسجيله
            // «getMainPage ERROR» — سجلٌّ مليء بـCANCELLED يُخفي الأخطاء الحقيقية.
            throw e
        } catch (e: Exception) {
            android.util.Log.e("EdgeNarto", "getMainPage ERROR", e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    /** تحديث صفٍّ واحد في الخلفية (stale-while-revalidate). */
    private suspend fun refreshRow(q: String) {
        searchCache.remove(q)
        val html = fetchSearch(q, timeoutMs = ROW_GET_TIMEOUT_MS, attempts = ROW_GET_ATTEMPTS) ?: return
        val items = parseSearchItems(html)
        if (items.isEmpty()) return
        itemsJson(items)?.let { rowCache.put(q, it) }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val html = fetchSearch(query)
            if (html == null) return null
            parseSearchItems(html).mapNotNull { it.toSearchResponse() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // بحثٌ ألغاه المستخدم بكتابةٍ أحدث ليس «لا نتائج»: إعادته null هنا كانت تعرض
            // صفحةً فارغة لاستعلامٍ يعيد فعلاً عشرات البطاقات. الإلغاء يخرج كما هو.
            throw e
        } catch (e: Exception) {
            android.util.Log.e("EdgeNarto", "search ERROR q=$query", e)
            null
        }
    }

    private fun SearchHit.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        val poster = image?.let { if (it.startsWith("http")) it else mainUrl + it }
        return newTvSeriesSearchResponse(name, "$mainUrl/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(url)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: mainUrl

            // ★ الرابط يُبذل للمحاولات والتحقق من الصحة في nartoGetPage.
            //
            // كان هنا محاولة واحدة بلا تحقق: ولا يُعاد الطلب على فشل النقل، ولا يُلاحَظ أنّ
            // الرد صفحةُ خطأ. وedge.narto-drama.com يفشل في أكثر من نصف الطلبات للنطاق نفسه
            // (قِسناه 2026-10-12: 24 طلباً ⇒ 11×200 و1×500 و6×TLS و6×Socket) بينما الرابط
            // نفسه يُجيب 200 في المحاولة التالية — فكانت النتيجة "500 Internal Server Error"
            // على شاشة التفاصيل أو قائمةَ حلقاتٍ ناقصة. الآن يُعاد الطلب على الفشل العارض
            // (5xx/TLS/Socket) ويتوقف فورًا على 4xx (حكمٌ على الرابط)، وصفحةُ الخطأ لا تُحلَّل.
            val html = nartoGetPage("$loadHost/detail/watch/$slug", nartoOrigin, "EdgeNarto")
            if (html == null) {
                android.util.Log.e("EdgeNarto", "load slug=$slug NO PAGE after retries (${System.currentTimeMillis() - t0}ms)")
                return null
            }
            val doc = org.jsoup.Jsoup.parse(html)
            android.util.Log.e("EdgeNarto", "load slug=$slug host=$loadHost fetchMs=${System.currentTimeMillis() - t0} eps=" + doc.select("div.episode-list a.episode-item").size)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")

            var eps = doc.select("div.episode-list a.episode-item")
                .mapNotNull { el ->
                    val href = el.attr("href") ?: return@mapNotNull null
                    // الرابط المطلق هو ما تبنيه الصفحة (…/detail/watch/{slug}/{n}?lang=en-US)،
                    // لكن نحتفظ بمسار الهوست المطلوب ·{loadHost} ليبقى التشغيل على النطاق نفسه.
                    val ep = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                    newEpisode("$loadHost/detail/watch/$slug/$ep") {
                        episode = ep
                        name = "الحلقة $ep"
                    }
                }

            if (eps.isEmpty()) {
                android.util.Log.e("EdgeNarto", "load slug=$slug no static episodes -> fallback single ep=1")
                eps = listOf(
                    newEpisode("$loadHost/detail/watch/$slug/1") {
                        episode = 1
                        name = "الحلقة"
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // لُغي النداء: null هنا كانت تُقرأ «لا تفاصيل لهذا العنوان» وهي تهمةٌ لا تخصّ
            // المصدر. الإلغاء يخرج كما هو.
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = loadNartoLinks(
        api = this,
        prefs = prefs,
        origin = nartoOrigin,
        tag = "EdgeNarto",
        showFullKey = EdgeNartoSettingsBottomSheet.KEY_SHOW_FULL,
        orderKey = EdgeNartoSettingsBottomSheet.KEY_QUALITY_ORDER,
        fetch = fetch,
        data = data,
        subtitleCallback = subtitleCallback,
        callback = callback,
    )
}