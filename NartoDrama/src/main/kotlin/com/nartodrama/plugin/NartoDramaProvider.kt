package com.nartodrama.plugin

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
import nartoshared.nartoGetPage

private const val NARTO_HOST = "https://narto-drama.com"
private const val BRW_HOST = "https://edge.narto-drama.com"

// مفتاح صفّ «الاقتراحات العربية» في mainPage.data(). قيمةٌ ثابتة لا تصادف أي استعلام
// بحث عربي (البادئة /* تعني: هذا صفّ الموجز لا بحث). getMainPage يتفرّع عليها.
private const val SUGGEST_ROW = "/*suggest*/"

// ★ سقف زمني صارم لكل صفّ من صفوف الرئيسية.
//
// لماذا: CloudStream ينادي getMainPage لكل صفوف mainPage معاً ثم يرسم الرئيسية بعد
// اكتمالها كلها. وكانت كل صفحة تُطلب بـPAGE_GET_ATTEMPTS=4 × timeout 30s، أي أنّ صفًّا
// واحدًا يمكن أن يستهلك ~١٢٤ ثانية على شبكة تفشل فيها بعض الطلبات (قِيس 000 على apex)،
// فيبدو للمستخدم أنّ «الواجهة الرئيسية لا تظهر» — بينما الصفوف تُجلب فعلًا، فقط بعد دقائق.
// الآن أي صفّ ينتهي خلال ٢٥ ثانية كحد أقصى: إمّا ببطاقاته أو بصفٍّ فارغ.
private const val ROW_BUDGET_MS = 25_000L
private const val ROW_GET_TIMEOUT_MS = 12_000L
private const val ROW_GET_ATTEMPTS = 2

// Standalone "Narto Drama" extension, pinned to the apex host for playback. v46: this provider
// carries its OWN domain — all links/cards/detail/refresh URLs use the apex host
// https://narto-drama.com (per user: "اجعل كل مصدر يحمل الرابط الخاص به"). Browsing HTML
// (home "/" and "/search") is fetched from the live edge host (BRW_HOST) because apex
// front-ends those routes with a Cloudflare JS challenge; only refresh/playback/detail go to
// apex (verified live: detail 200/82eps, refresh {"ok":true,"play_url":"stream-e1..."}).
// Still 100% independent of the Edge source.
//
// Every playback decision — refresh-source, stale retry, cooldown, liveness probe, link
// emission — lives in ../../narto-shared, so a fix here cannot be missing from Edge Narto
// Drama. That drift happened for the whole life of these two sources and was invisible.

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// One JSON-LD ListItem entry from the search results page.
private data class SearchHit(
    @JsonProperty("@type") val type: String? = null,
    val url: String? = null,    // https://narto-drama.com/detail/watch/{slug}?lang=...
    val name: String? = null,   // Arabic title (e.g. "[مدبلج] ...")
    val image: String? = null,  // /assets/poster/{id}.jpg
)

class NartoDramaProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "Narto Drama"
    override var mainUrl = NARTO_HOST
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Main screen = one section per tab, each a distinct Arabic query (verified live: different
    // queries return DIFFERENT feeds, 0 overlap). fetchSearch caches per query, and getMainPage
    // runs ALL tabs in parallel so hopping tabs never re-fetches. First paint uses the requested
    // tab's own feed.
    //
    // v3: the first row is «الاقتراحات العربية» — the site's own home feed (the same content the
    // fallback uses), so the suggested Arabic dramas show at the top of the screen instead of
    // only appearing when another feed fails. Every row is shown/hidden from the settings
    // (ntd_row_<slug>, default visible) and the rows are never allowed to become empty.
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

    // المنصّة/القسم ← مفتاح التفضيلات (ntd_row_<slug>). الربط هنا لأن data الصف
    // قيمته عربية، والمفتاح ASCII.
    private fun rowKeyFor(data: String): String = when (data) {
        SUGGEST_ROW -> NartoDramaSettingsBottomSheet.KEY_ROW_SUGGEST
        "دراما" -> NartoDramaSettingsBottomSheet.KEY_ROW_DRAMA
        "مدبلج" -> NartoDramaSettingsBottomSheet.KEY_ROW_DUBBED
        "رومانسي" -> NartoDramaSettingsBottomSheet.KEY_ROW_ROMANCE
        "أكشن" -> NartoDramaSettingsBottomSheet.KEY_ROW_ACTION
        else -> "ntd_row_${data}"
    }

    // الصفوف التي لم يخفها المستخدم. إن أخفى الكل نُبقي صف «الاقتراحات» حتى لا تفرغ
    // الواجهة الرئيسية تماماً (وإلا لا يبقى للمستخدم طريق للرجوع من الإعدادات).
    // اسم المزوّد يُضاف إلى عنوان كل صفّ: الرئيسية تعرض صفوف مزوّدٍ واحد فقط، ومع
    // تشابه الأقسام بين المواقع (دراما/مدبلج/رومانسي) كان عنوان الصف لا يقول مصدره.
    private fun rowTitle(label: String): String = "$name • $label"

    private fun visibleRows(): List<Pair<String, String>> {
        val shown = (listOf(suggestRow) + categoryRows).filter { (data, _) ->
            prefs?.getBoolean(rowKeyFor(data), true) != false
        }
        return shown.ifEmpty { listOf(suggestRow) }.map { (data, label) -> data to rowTitle(label) }
    }

    // Referer for all requests/links. This is just an HTTP Referer header the narto stream/
    // subtitle servers expect; it does NOT merge this source with the Edge extension.
    private val nartoOrigin = "https://narto-drama.com"

    // The apex host first, edge as the network-failure fallback (see the class comment).
    private val fetch = NartoFetch(listOf(NARTO_HOST, BRW_HOST), nartoOrigin, "NartoDrama")

    // Per-tab in-memory cache so re-entering / tab-hopping serves the list instantly instead of
    // re-fetching the heavy /search page.
    private val searchCache = HashMap<String, String>()

    // Cached edge home "/" feed — the guaranteed never-blank fallback (verified live: it always
    // returns the content row, unlike a single /search which can fail or 520). Poster images are
    // absolute (img.nartodrama-api.online) and cards carry apex detail URLs, so the fallback
    // renders identically under any tab name.
    private var homeFeedCache: String? = null

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
            mapper.readValue(
                html.substring(arrStart, arrEnd + 1),
                object : com.fasterxml.jackson.core.type.TypeReference<List<SearchHit>>() {}
            ).filter { !it.url.isNullOrBlank() && !it.name.isNullOrBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchSearch(q: String, timeoutMs: Long = ROW_GET_TIMEOUT_MS, attempts: Int = ROW_GET_ATTEMPTS): String? {
        searchCache[q]?.let { return it }
        val urlEncQ = java.net.URLEncoder.encode(q, "UTF-8")
        try {
            val html = nartoGetPage(
                "$BRW_HOST/search?lang=ar-SA&q=$urlEncQ&page=1&perPage=12",
                nartoOrigin, "NartoDrama",
                timeoutMs = timeoutMs, attempts = attempts,
            )
            if (html != null && html.contains("\"@type\":\"ListItem\"")) {
                searchCache[q] = html
                return html
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // CloudStream يلغي البحث القديم حين يكتبه المستخدم آخره. ابتلاعُ الإلغاء هنا
            // كان يحوّله إلى «فشل شبكة» ثم يستمرّ النداء في إرسال طلبات بعده — قِسناه
            // 2026-10-06: خمس «search fetch error» من نوع JobCancellationException دفعةً
            // واحدة. الإلغاء ليس عطلاً في المصدر، ويجب أن يخرج فوراً.
            throw e
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "search fetch error", e)
        }
        return null
    }

    // ★ طلبٌ واحد مشترك لموجز الصفحة (single-flight).
    //
    // قِيس على الجهاز 2026-11-03: صفحة الموجز ٤٥٧ كيلوبايت وتستغرق ~٣٥ ثانية على شبكة
    // المستخدم. وكان كل صفّ من الصفوف الخمسة يفتح تنزيله الخاص عند الفشل، أي خمسة تنزيلات
    // متزامنة لنفس الملف: تتزاحم على النطاق فينجح بعضها ويفشل بعضها (سجلّ الجهاز:
    // صفّان نجحا وصفّان فشلا في اللحظة نفسها). الآن التنزيل واحد، والبقية تنتظره ثم تقرأ
    // من الذاكرة، ويُخزَّن المحتوى **محلَّلًا** كي لا يُعاد تحليل ٤٥٧KB خمس مرات.
    private val homeFeedLock = kotlinx.coroutines.sync.Mutex()
    private var homeFeedItems: List<SearchHit>? = null

    private suspend fun fetchHomeItems(): List<SearchHit>? {
        homeFeedItems?.let { return it }
        homeFeedLock.lock()
        try {
            homeFeedItems?.let { return it } // امتلأت الذاكرة أثناء الانتظار
            for (host in listOf(BRW_HOST, NARTO_HOST)) {
                val html = nartoGetPage(
                    "$host/", nartoOrigin, "NartoDrama",
                    timeoutMs = 10_000L, attempts = 1,
                )
                if (html != null && html.contains("\"@type\":\"ListItem\"")) {
                    val parsed = parseSearchItems(html)
                    if (parsed.isNotEmpty()) {
                        homeFeedCache = html
                        homeFeedItems = parsed
                        return parsed
                    }
                }
            }
            return null
        } finally {
            homeFeedLock.unlock()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val isSuggest = request.data == SUGGEST_ROW
            val q = if (isSuggest) "" else request.data.trim()

            // ★ لا تسخين ولا انتظار للصفوف الأخرى.
            //
            // كان هذا الموضع يجمع كل الصفوف الظاهرة بـawaitAll() ثم يبدأ جلب الصف المطلوب،
            // أي أنّ أول رسم ينتظر *آخر* صفّ. ثم إنّ CloudStream نفسه يستدعي getMainPage
            // لكل صفوف mainPage معاً عند كل رسم — فالتسخين كان يطلب من السيرفر البطيء
            // الطلبات نفسها مرّتين (قِسنا على الجهاز: صفّ 37.9ث، فكانت الواجهة تُرسم بعده).
            // الآن كل نداء يجلب صفّه وحده فوراً.
            //
            // ★ ميزانية واحدة لكل صفّ (ROW_BUDGET_MS) تشمل محاولة القسم والبديل معًا؛
            // قبل ذلك كانت ميزانيتان متتاليتان (٢٥+٢٥) فقِيس على الجهاز صفٌّ استغرق ٥٠ ثانية.
            var fromFallback = false
            val items: List<SearchHit> = kotlinx.coroutines.withTimeoutOrNull(ROW_BUDGET_MS) {
                if (isSuggest) {
                    fetchHomeItems()
                } else {
                    // محاولة قصيرة (٦ث، طلب واحد) لقسم هذا الصفّ: إن نجحت ظهر محتواه
                    // الخاص، وإلا نخدم الموجز المشترك فورًا بدل حرق الميزانية كلها.
                    val own = fetchSearch(q, timeoutMs = 6_000L, attempts = 1)
                        ?.let { parseSearchItems(it) }
                    if (!own.isNullOrEmpty()) own
                    else {
                        fromFallback = true
                        fetchHomeItems()
                    }
                }
            } ?: emptyList()

            android.util.Log.e(
                "NartoDrama",
                "getMainPage q=$q ms=${System.currentTimeMillis() - t0} items=${items.size} fallback=$fromFallback"
            )
            if (items.isEmpty()) {
                // صفٌّ فارغ أفضل من null: بعض إصدارات التطبيق تُسقط رسم الرئيسية كلها
                // حين يُرجع صفٌّ واحد null، فيختفي كل شيء بدل أن يظهر قسمٌ فارغ وحده.
                return newHomePageResponse(request.name, emptyList())
            }
            newHomePageResponse(request.name, items.take(12).mapNotNull { it.toSearchResponse() })
        } catch (e: kotlinx.coroutines.CancellationException) {
            // لُغي النداء (تغيّر القسم أو غادر المستخدم): نُعيده كما هو بدل تسجيله
            // «getMainPage ERROR» — سجلٌّ مليء بـCANCELLED يُخفي الأخطاء الحقيقية.
            throw e
        } catch (e: Exception) {
            android.util.Log.e("NartoDrama", "getMainPage ERROR", e)
            newHomePageResponse(request.name, emptyList())
        }
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
            android.util.Log.e("NartoDrama", "search ERROR q=$query", e)
            null
        }
    }

    private fun SearchHit.toSearchResponse(): SearchResponse? {
        val u = url ?: return null
        val slug = Regex("""/detail/watch/([^/?]+)""").find(u)?.groupValues?.get(1) ?: return null
        val name = this.name ?: return null
        val poster = image?.let { if (it.startsWith("http")) it else NARTO_HOST + it }
        return newTvSeriesSearchResponse(name, "$NARTO_HOST/detail/watch/$slug", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val t0 = System.currentTimeMillis()
        return try {
            val slug = Regex("""/detail/watch/([^/?]+)""").find(url)?.groupValues?.get(1) ?: return null
            val loadHost = Regex("""https://([^/]+)/detail/watch/""").find(url)?.groupValues?.get(1)
                ?.let { "https://$it" } ?: NARTO_HOST

            // ★ Retry + validity live in nartoGetPage now.
            //
            // This used to be a single unchecked attempt: no retry on a transport failure, and
            // no notice that the response was an error document. Both hosts here fail a large
            // fraction of requests at the transport layer (MEASURED 2026-10-12 on the edge VPS:
            // 24 GETs of one detail URL ⇒ 11x200, 1x500, 6xSSLHandshakeException, 6xSocketException)
            // while the SAME URL answers 200 on the next try — which is exactly how a flaky host
            // becomes "500 Internal Server Error" on the detail screen or a half-built episode
            // list. Transient failures (5xx/TLS/Socket) are retried; 4xx is a verdict; an error
            // page is never parsed as a show.
            val html = nartoGetPage("$loadHost/detail/watch/$slug", nartoOrigin, "NartoDrama")
            if (html == null) {
                android.util.Log.e("NartoDrama", "load slug=$slug NO PAGE after retries (${System.currentTimeMillis() - t0}ms)")
                return null
            }
            val doc = org.jsoup.Jsoup.parse(html)
            android.util.Log.e("NartoDrama", "load slug=$slug host=$loadHost fetchMs=${System.currentTimeMillis() - t0} eps=" + doc.select("div.episode-list a.episode-item").size)

            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst("meta[name=description]")?.attr("content")

            var eps = doc.select("div.episode-list a.episode-item")
                .mapNotNull { el ->
                    val href = el.attr("href") ?: return@mapNotNull null
                    val ep = Regex("""/detail/watch/[^/]+/(\d+)""").find(href)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: return@mapNotNull null
                    newEpisode("$loadHost/detail/watch/$slug/$ep") {
                        episode = ep
                        name = "الحلقة $ep"
                    }
                }

            if (eps.isEmpty()) {
                android.util.Log.e("NartoDrama", "load slug=$slug no static episodes -> fallback single ep=1")
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
        tag = "NartoDrama",
        showFullKey = NartoDramaSettingsBottomSheet.KEY_SHOW_FULL,
        orderKey = NartoDramaSettingsBottomSheet.KEY_QUALITY_ORDER,
        fetch = fetch,
        data = data,
        subtitleCallback = subtitleCallback,
        callback = callback,
    )
}