package com.reelree.plugin

import cloudstreamshared.FormatTag
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Reelree — موقع دراما قصيرة عمودية (مترجمة/مدبلجة).
 *
 * بنية الموقع (فحص فعلي 2026-09):
 *  - القوائم: article.rr-card > a.rr-card-link + img + h3
 *  - التفاصيل: عنصر .rr-player يحمل سمات data-*:
 *      data-title, data-poster, data-episodes, data-series, data-source, data-orientation
 *      data-media  → قالب الحلقات: يُستبدل %EP% برقم الحلقة (m3u8 أو mp4)
 *      data-rr-server2 → JSON { code, direct, embed, offsets[] } = "السيرفر الكامل"
 *          ملف merged واحد يضم كل الحلقات، بجودات متعددة (master بـ STREAM-INF فقط).
 *          direct = https://reelree.com/api/v2/{code}.m3u8
 *
 * ⚠️ **الترجمات لا تُقرأ من master أبداً** (مقيس 2026-10-02): قوائم
 * `/api/v2/‹code›.m3u8` المقيسة تحمل `#EXT-X-STREAM-INF` و**صفر** `#EXT-X-MEDIA`
 * — ولا `EXT-X-SUBTITLES` ولا `#EXT-X-MEDIA:TYPE=AUDIO`. فالمسح عنهما في
 * القائمة كسرٌ صامت يُرجع صفراً بلا خطأ. والترجمات في مكانين منفصلين:
 *   · `/api/v2/{code}.json` → `subs[]` (ترجمةٌ واحدة للمسلسل كلّه، حتّى 25 لغة)
 *   · `/api/subtitles` (ns) → `data.subtitleList[]` (ترجمة لكل حلقة)
 * وكلاهما يُقدَّم للمشغّل مباشرةً لأن الملفات جاهزة WebVTT سلفاً
 * (`text/vtt`، وتوقيتات بنقاط) — لا عبر وسيط الموقع، فشهادة reelree.com منتهية.
 * وللتفصيل انظر `subtitleUrl`.
 */
class ReelreeProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "Reelree"
    override var mainUrl = "https://reelree.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // صف لكل منصة تجمعها Reelree — بدون حذف أي منصة. تأتي بعد الأقسام العامة.
    private val platformRows = listOf(
        "platform/netshort/" to "منصة NetShort",
        "platform/dramabite/" to "منصة DramaBite",
        "platform/dramabox/" to "منصة DramaBox",
        "platform/dramapops/" to "منصة DramaPops",
        "platform/reelshort/" to "منصة ReelShort",
        "platform/goodshort/" to "منصة GoodShort",
        "platform/shortmax/" to "منصة ShortMax",
        "platform/flickreels/" to "منصة FlickReels",
        "platform/flextv/" to "منصة FlexTV",
        "platform/dramawave/" to "منصة DramaWave",
        "platform/stardusttv/" to "منصة StarDustTV",
    )

    override val mainPage = mainPageOf(
        // الأقسام العامة
        "explore/" to "أحدث المسلسلات",
        "explore/?sort=trending" to "الأكثر مشاهدة",
        "tag/metarjam-arabi/" to "مترجم عربي",
        "tag/mudabalaj-arabi/" to "مدبلج عربي",
        "tag/lang-en/" to "بالإنجليزية",
        // جميع المنصات (حسب الموقع /platforms/)
        *platformRows.map { (path, label) -> path to label }.toTypedArray(),
    )

    private data class RrServer(
        val code: String,
        val direct: String?,
        val embed: String?,
        val offsets: List<Offset>,
    )

    private data class Offset(val ep: Int, val start: Double, val dur: Double)

    private fun parseCards(doc: Document, typ: TvType = TvType.TvSeries): List<SearchResponse> {
        return doc.select("article.rr-card").mapNotNull { card ->
            try {
                val a = card.selectFirst("a.rr-card-link") ?: card.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                val title = card.selectFirst("h3")?.text()?.trim()
                    ?: a.attr("aria-label")?.trim()
                    ?: a.attr("title")?.trim()
                    ?: return@mapNotNull null
                val poster = card.selectFirst("img.rr-poster")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }?.ifBlank { null }

                newMovieSearchResponse(title, href, typ) {
                    this.posterUrl = poster
                }
            } catch (e: Exception) { null }
        }
    }

    // ---- Main-page speed (v3): Reelree is Akamai-fronted and SLOW, and the main page is 16
    // separate row fetches. Every re-open / tab-hop used to re-fetch the whole row and pay full
    // server latency, and a single failing row returned null → blank section. Now:
    //   • every row's parsed cards are cached per data() key,
    //   • re-opening an already-cached row serves the cached cards instantly (never re-fetches),
    //   • on first open we warm ALL remaining rows in the background so the user hopping tabs
    //     gets instant cards,
    //   • a row that fails/empties falls back to the most-recently fetched feed instead of blanking.
    // The row keys are exactly the mainPage data() values (listed here so the background warm can
    // iterate the whole main screen without depending on the cloudstream MainPage API surface).
    private val allRows: List<String> = buildList {
        add("explore/"); add("explore/?sort=trending"); add("tag/metarjam-arabi/"); add("tag/mudabalaj-arabi/"); add("tag/lang-en/")
        addAll(platformRows.map { it.first })
    }

    private val rowCache = HashMap<String, List<SearchResponse>>()
    private var warmStarted = false
    private var lastGoodFeed: List<SearchResponse>? = null
    private val rowLock = Any()

    private suspend fun buildRowUrl(base: String, page: Int): String {
        return when {
            base.contains("?") -> {
                val (path, q) = base.split("?", limit = 2)
                if (page > 1) "$mainUrl/$path/page/$page/?$q" else "$mainUrl/$path/?$q"
            }
            else -> if (page > 1) "$mainUrl/${base}page/$page/" else "$mainUrl/$base"
        }
    }

    private suspend fun fetchRowCards(base: String, page: Int): List<SearchResponse>? {
        val url = buildRowUrl(base, page)
        return try {
            val doc = app.get(url, referer = mainUrl).document
            parseCards(doc)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = request.data
        return try {
            // Already-cached row or a paginated row (page>1 rendered once): serve instantly.
            if (page > 1 || synchronized(rowLock) { rowCache.containsKey(base) }) {
                synchronized(rowLock) { rowCache[base] }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return newHomePageResponse(request.name, it) }
            }
            val fetched = fetchRowCards(base, page)
            if (fetched != null && fetched.isNotEmpty()) {
                synchronized(rowLock) {
                    rowCache[base] = fetched
                    lastGoodFeed = fetched
                }
            } else {
                // Row fetch failed/empty — never blank the section: reuse this row's cached
                // cards if we have them, else the most-recent fetched feed.
                android.util.Log.e("Reelree", "row '$base' fetch failed/empty -> fallback")
                synchronized(rowLock) { rowCache[base] }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return newHomePageResponse(request.name, it) }
                synchronized(rowLock) { lastGoodFeed }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { android.util.Log.e("Reelree", "row '$base' -> last good feed (${it.size})"); return newHomePageResponse(request.name, it) }
                return null
            }

            // Background warm of all remaining rows on first open: fills caches so tab hops paint
            // instantly. Runs detached — never blocks first paint.
            if (!warmStarted) {
                synchronized(rowLock) { if (warmStarted) false else { warmStarted = true; true } }.let { go ->
                    if (go) {
                        android.util.Log.e("Reelree", "starting background warm of all rows")
                        GlobalScope.launch(Dispatchers.IO) {
                            // احمّل كل قسم بمعزلٍ عن الآخر (ترابط لكل صف) بدل التتابع —
                            // فتملأ الأقسام الـ16 بسرعة وكل قسم يظهر فور جاهزيته.
                            val remaining = allRows.filter { it != base }
                            remaining.map { b ->
                                launch(Dispatchers.IO) {
                                    val cards = fetchRowCards(b, 1)
                                    if (cards != null && cards.isNotEmpty()) {
                                        synchronized(rowLock) { rowCache[b] = cards }
                                    }
                                }
                            }.forEach { it.join() }
                            android.util.Log.e("Reelree", "background warm complete (cached=${synchronized(rowLock) { rowCache.size }})")
                        }
                    }
                }
            }
            newHomePageResponse(request.name, fetched)
        } catch (e: Exception) {
            null
        }
    }

    // البحث كان يبني «?s=<حروف عربية خام>»: الاستعلام العربي لا يُرسَل بلا ترميز
    // (محرك HTTP يرفضه أو يفسّره خطأً)، فيفشل الجلب ويظهر للمستخدم «لا نتائج».
    // percent-encoding مع «%20» بدل «+» يطابق ما يقبله الموقع فعلاً.
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val enc = try {
            java.net.URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            android.util.Log.e("Reelree", "search encode fail ${e.message}"); return emptyList()
        }
        // صفحة نتائج ?s= تحمل البطاقات نفسها (rr-card) التي تحملها /explore/، فالمحلّل واحد
        return try {
            val doc = app.get("$mainUrl/?s=$enc", referer = mainUrl).document
            val cards = parseCards(doc)
            android.util.Log.i("Reelree", "search '$q' -> ${cards.size} cards")
            cards
        } catch (e: kotlinx.coroutines.CancellationException) {
            // التطبيق يُلغي النداءات التي لم تعد أحدثَ: عند فتح بطاقة يشغّل
            // CloudStream بحثاً تلقائياً باسم المسلسل، فإذا كتب المستخدم حرفاً
            // جديداً أُلغي البحث السابق. سجّلنا على الجهاز:
            //   ‹طريق› -> 36 cards          (نجح)
            //   ‹طريق الاسرار› fail StandaloneCoroutine was cancelled
            // فالطلب لم يفشل — قِسنا صفحة البحث تردّ في 1.2 ثانية بـ194 بطاقة.
            // وكنا نلتقط الاستثناء بالـ catch التالي ونُعيد قائمة فارغة،
            // فيظنّ التطبيق «المزوّد لم يجد شيئاً» ويعرض «لا نتائج».
            // نُعيده كما هو: الإلغاء قرارُ التطبيق، ونتيجته تُهمَل عمداً.
            android.util.Log.i("Reelree", "search '$q' cancelled by host — discarding")
            throw e
        } catch (e: Exception) {
            // نُعيد قائمة فارغة لا null: null في CloudStream يعني «المزوّد فشل» فتُعرض
            // رسالة خطأ، والقائمة الفارغة تعني «لا نتائج» وهو ما يُرجّحه هذا الفشل.
            android.util.Log.e("Reelree", "search '$q' fail ${e.message}")
            emptyList()
        }
    }

    // hasQuickSearch = true بلا تنفيذ quickSearch كان يترك صندوق البحث السريع
    // فارغاً: الافتراضي في MainAPI يرمي NotImplementedError، والتطبيق لا
    // يستدعي search() عند بعض الشاشات. التوجيه يجعله يمرّ إلى search().
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    /** يحلل data-rr-server2 (HTML-entities) إلى RrServer. */
    private fun parseRrServer(raw: String?): RrServer? {
        if (raw.isNullOrBlank()) return null
        return try {
            val text = raw.replace("&quot;", "\"").replace("\\/", "/")
            val node = mapper.readTree(text)
            val code = node.get("code")?.asText() ?: return null
            val direct = node.get("direct")?.asText()
            val embed = node.get("embed")?.asText()
            val offsets = mutableListOf<Offset>()
            val arr = node.get("offsets")
            if (arr != null && arr.isArray) {
                for (o in arr) {
                    val ep = o.get("ep")?.asInt() ?: continue
                    val start = o.get("start")?.asDouble() ?: 0.0
                    val dur = o.get("dur")?.asDouble() ?: 0.0
                    offsets.add(Offset(ep, start, dur))
                }
            }
            RrServer(code, direct, embed, offsets)
        } catch (e: Exception) { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val watch = doc.selectFirst("[data-media]")

            val title = watch?.attr("data-title")?.trim()
                ?.let { cleanTitle(it) }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanTitle(it) }
                ?: doc.selectFirst("h1, [data-title]")?.text()?.trim()
                ?: return null

            val poster = watch?.attr("data-poster")?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

            val mediaTemplate = watch?.attr("data-media")?.trim()
            val episodes = watch?.attr("data-episodes")?.trim()?.toIntOrNull() ?: 1
            val dataSource = watch?.attr("data-source")?.trim().orEmpty()
            // seriesId يُمرَّر داخل حقل data للحلقات ليتسنّى لـ loadLinks الوصول إلى
            // واجهة الترجمة/الجودات (POST /api/subtitles لـ ns، و /api/{src}/{id}/{n}.json لسواها).
            val seriesId = watch?.attr("data-series")?.trim().orEmpty()
            val rrServer = parseRrServer(watch?.attr("data-rr-server2"))

            // قائمة الحلقات:
            //  1) حلقة خاصة "الحلقة كاملة" ← السيرفر الكامل (الملف المدمج بكل الجودات/الترجمات/الأصوات) — مرة واحدة
            //  2) حلقات منفصلة 1..N ← عبر data-media / %EP% (الحلقة المستقلة)
            // ملاحظة: يجب أن يبدأ حقل data بعنوان http دائمًا — إذا بدأ بنص عادي (مثل "FULL|")
            // يفسّره CloudStream كمسار نسبي فيهشّل الرابط. لذا نضع علامة كاملة أولًا ثم نلحقها
            // بـ "|" وبعدها السيرفر الكامل: data = "https://reelree.com/full|{fullMaster}".
            val eps = mutableListOf<Episode>()
            val fullMaster = rrServer?.direct.orEmpty()
            val srvCode = rrServer?.code.orEmpty()
            if (fullMaster.startsWith("http")) {
                eps.add(newEpisode("$mainUrl/full|$fullMaster|$srvCode") {
                    episode = 0
                    name = "الحلقة كاملة"
                })
            }
            if (mediaTemplate.isNullOrBlank() || !mediaTemplate.startsWith("http")) {
                if (eps.isEmpty() && fullMaster.startsWith("http")) {
                    eps.add(newEpisode("$mainUrl/full|$fullMaster|$srvCode") {
                        episode = 0
                        name = "الحلقة"
                    })
                }
            } else {
                for (n in 1..episodes) {
                    eps.add(newEpisode("$mediaTemplate|$n|$dataSource|$seriesId") {
                        episode = n
                        name = "الحلقة $n"
                    })
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOf(dataSource).filter { it.isNotBlank() }
            }
        } catch (e: Exception) { null }
    }

    /** يفحص master ويعرّف جوداته عبر nil #EXT-X-STREAM-INF، ويرجع قائمة (url, qualityLabel). */
    private fun extractVariants(masterText: String, baseUrl: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val seen = HashSet<String>()
        val lines = masterText.split("\n")
        val resRe = Regex("""RESOLUTION=(\d+x(\d+))""")
        val idxRe = Regex("""BANDWIDTH=(\d+)""")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val next = lines.getOrNull(i + 1)?.trim() ?: run { i++; continue }
                val resMatch = resRe.find(line)
                var uri = next
                if (!uri.startsWith("http")) uri = baseUrl.substringBeforeLast("/") + "/" + uri
                // نفس الـURI مرتين = مُدخل مكرر في الـmaster — يُسقط بدل تكراره في القائمة
                if (uri.isNotBlank() && seen.add(uri)) {
                    // الضلع القصير هو رقم الجودة: مسلسلاتنا رأسية 1080×1920، وقراءة
                    // البعد الثاني (1920) كانت تخدعنا فيعطي 1440p لحلقة عرضها 1080.
                    val q = resMatch?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { w ->
                            val h = resMatch.groupValues.get(2).toIntOrNull() ?: w
                            minOf(w, h)
                        }
                        ?: idxRe.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { bw ->
                            when {
                                bw >= 4000000 -> 1080
                                bw >= 2000000 -> 720
                                else -> 480
                            }
                        }
                        ?: 720
                    out.add(uri to q)
                }
                i++
            }
            i++
        }
        return out
    }

    /** يفحص master ويستخرج الترجمات (SUBTITLES) والأصوات (AUDIO) إذا وُجدت. */
    private class Track(val kind: String, val lang: String, val uri: String)

    private fun extractTracks(masterText: String, baseUrl: String): List<Track> {
        val out = mutableListOf<Track>()
        val re = Regex("""#EXT-X-MEDIA:TYPE=([A-Z]+)[^#]*?NAME="([^"]+)"[^#]*?URI="([^"]+)"""", RegexOption.IGNORE_CASE)
        for (m in re.findAll(masterText)) {
            val kind = m.groupValues[1].uppercase()
            if (kind != "SUBTITLES" && kind != "AUDIO") continue
            val lang = m.groupValues[2]
            var uri = m.groupValues[3]
            if (!uri.startsWith("http")) uri = baseUrl.substringBeforeLast("/") + "/" + uri
            out.add(Track(kind, lang, uri))
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // لا تخزين/فرز هنا عمداً: المصدر يبث رابطاً واحداً 720p للحلقة (ورابط
        // «الحلقة كاملة» 1080p أو أكثر عند السيرفر الكامل) — فترتيب الجودات بلا
        // أثر عملي، والبث يبقى حرفياً بلا تغيير (لا تُخزَّن القائمة ولا يُعاد ترتيبها).
        // ===== المسار 1: "الحلقة كاملة" — السيرفر الكامل فقط (ملف merged بجودات + ترجمات + أصوات) =====
        // البيانات تبدأ بعنوان http للعلامة (حتى لا يهشّل CloudStream حقل data)،
        // والسيرفر الكامل يأتي بعد أول "|".
        val firstPipe = data.indexOf('|')
        if (firstPipe > 0 && data.substring(0, firstPipe) == "$mainUrl/full") {
            val rest = data.substring(firstPipe + 1)
            val secondPipe = rest.indexOf('|')
            val fullMaster = if (secondPipe > 0) rest.substring(0, secondPipe) else rest
            val v2Code = if (secondPipe > 0) rest.substring(secondPipe + 1).trim() else ""
            if (!fullMaster.startsWith("http")) return false

            // ⚠️ **الترجمات والجودات لا تأتي من master إطلاقاً** (مقيس 2026-10-02):
            // قوائم `/api/v2/‹code›.m3u8` تحمل `#EXT-X-STREAM-INF` وصفر `#EXT-X-MEDIA` —
            // فلا `EXT-X-MEDIA:TYPE=SUBTITLES` ولا `TYPE=AUDIO`. فكان المسح عنها
            // فيها يعود بصفرٍ بلا خطأ، ولا تظهر للمشاهد أي ترجمة.
            // المصدر الوحيد الذي يحملها هو `/api/v2/{code}.json` ← `subs[]`.
            val showSubs = prefs?.getBoolean(ReelreeSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) != false
            var subs: List<SubtitleFile> = emptyList()
            var directMaster: String? = null
            if (v2Code.isNotBlank()) {
                var meta: com.fasterxml.jackson.databind.JsonNode? = null
                for (i in 0 until 2) {
                    meta = runCatching {
                        mapper.readTree(app.get("$mainUrl/api/v2/$v2Code.json", referer = mainUrl,
                            headers = mapOf("User-Agent" to UA, "Origin" to mainUrl,
                                "X-Requested-With" to "XMLHttpRequest")).text)
                    }.getOrNull()
                    if (meta != null) break
                    try { Thread.sleep(600L * (i + 1)) } catch (_: InterruptedException) {}
                }
                if (meta != null) {
                    // رابط الأصل (vidara) أثبت من وسيط reelree: أسرع وأضمن للمشغّل
                    directMaster = meta.get("url")?.asText()?.takeIf { it.startsWith("http") }
                    if (showSubs) subs = normalizeSubs(meta.get("subs"))
                    android.util.Log.i("Reelree", "v2 $v2Code subs=${subs.size} default=${meta.get("defaultSub")?.asText()}")
                } else {
                    android.util.Log.e("Reelree", "v2 $v2Code meta unavailable — no subtitles")
                }
            }

            // الجودات تُقرأ من master الذي سنبثّه فعلاً — وهو رابط vidara مباشرةً
            // حين توفّر في /api/v2/{code}.json، فتكون أرقامها هي أرقام البثّ.
            val playUrl = directMaster ?: fullMaster
            val masterText = runCatching {
                app.get(playUrl, referer = mainUrl, headers = mapOf("User-Agent" to UA)).text
            }.getOrNull()
            val variants = masterText?.takeIf { it.startsWith("#EXT") }
                ?.let { extractVariants(it, playUrl) }.orEmpty()
            // أعلى جودة موجودة في قائمة الأم
            val best = variants.maxByOrNull { it.second }?.second ?: 0
            val audioFiles = masterText?.takeIf { it.startsWith("#EXT") }
                ?.let { mt -> extractTracks(mt, playUrl).filter { it.kind == "AUDIO" } }
                ?: emptyList()
            android.util.Log.i("Reelree", "full ${playUrl.take(70)} variants=${variants.size} q=$best audio=${audioFiles.size}")
            callback(newExtractorLink(name, FormatTag.tagged("الحلقة كاملة", playUrl, ExtractorLinkType.M3U8), playUrl, ExtractorLinkType.M3U8) {
                referer = mainUrl
                // الجودة الحقيقية من master — 0 يعني «غير معروف» فيظهر بلا رقم
                quality = if (best > 0) best else Qualities.Unknown.value
                // المسارات الصوتية على audioTracks لا كروابط فيديو: كروابط كانت
                // تظهر في قائمة الجودة وكأنها جودات فيديو، فيختارها المستخدم خطأً.
                if (audioFiles.isNotEmpty()) {
                    this.audioTracks = audioFiles.map { t ->
                        newAudioFile(t.uri) {
                            this.headers = mapOf("User-Agent" to UA, "Referer" to mainUrl)
                        }
                    }
                }
            })
            // ✅ ترجمة «الحلقة كاملة»: واحدةٌ لكل لغات الملفّ المدمج — ما كان مفقوداً تماماً
            for (sf in subs) {
                try { subtitleCallback(sf) } catch (_: Exception) {}
            }
            // والبديل: إن أضاف الـmaster يوماً ما سطر `EXT-X-MEDIA` نقرأه هنا أيضاً (بلا تعارض).
            if (masterText != null && masterText.startsWith("#EXT") && subs.isEmpty()) {
                for (t in extractTracks(masterText, playUrl)) {
                    if (t.kind == "SUBTITLES") {
                        try { subtitleCallback(newSubtitleFile(subLangLabel(t.lang), subtitleUrl(t.uri))) } catch (_: Exception) {}
                    }
                }
            }
            return true
        }

        // ===== المسار 2: الحلقات المنفصلة بالرقم — عبر data-media / %EP% =====
        // روابط /api/{dx|rs|gs|...}/{id}/{n}.m3u8 — كلها **302-إعادة توجيه** إلى سيرفر
        // المصدر الحقيقي (فالبايتات التي يرجعها النداء هي "Found. Redirecting..." لا
        // الملف نفسه). والنوع مختلف حسب المنصة:
        //   - dx (DramaBox): يعيد التوجيه إلى .../{n}.mp4 → MP4 (بعض الحلقات HLS)
        //   - rs (ReelShort): يعيد التوجيه إلى crazymaple...m3u8 → HLS
        //   - gs (GoodShort): يعيد التوجيه إلى goodshort...m3u8 → HLS
        // لذلك نقرأ هيدر Location (الوجهة الفعلية) ونصنّف بالامتداد (المنهج نفسه الذي
        // يعتمده موقع Reelree نفسه: isHlsUrl = /\.m3u8/)، ونبثّ رابط السيرفر الحقيقي
        // مباشرة. إرسال HLS كـ VIDEO = UnrecognizedInputFormatException (خطأ 3003)،
        // وإرسال MP4 كـ HLS = Source error — فتصنيف الوجهة هو ما يمنع كليهما.
        // (v5) نمرّر ضمن data أيضًا منصة المصدر + seriesId (من data-source/data-series) لنستخرج
        // منه الترجمة والجودات الإضافية من واجهة Reelree (راجع fetchEpisodeExtras أدناه).
        val parts = data.split("|", limit = 4)
        val template = parts.getOrNull(0)?.trim() ?: return false
        val ep = parts.getOrNull(1)?.trim() ?: return false
        val source = parts.getOrNull(2)?.trim().orEmpty()
        val seriesId = parts.getOrNull(3)?.trim().orEmpty()
        if (template.isBlank() || !template.startsWith("http")) return false

        val epUrl = template.replace("%EP%", ep)
        // نكتشف وجهة السيرفر الحقيقي (Location) عبر طلب مرافق من منتصف الطريق.
        // CloudStream يتابع 302 تلقائيًا أحيانًا (فتغيب Location ونقرأ المحتوى)، وقد لا
        // يتابعه (فنقرأ Location). وفي الحالتين نصنّف الوجهة الفعلية:
        //   - المنصة `db` (DramaBox عبر miniepisode): قالبها .mp4 لكن الهدف m3u8 → HLS
        //   - المنصة `dx` (DramaBox): قالبها .m3u8 لكن الهدف .mp4 → VIDEO
        //   - rs/gs/sm/ft/dw: قالبها .m3u8 والهدف m3u8 → HLS
        // نبثّ رابط السيرفر الحقيقي مباشرة (بدل وسيط reelree.com) — أسرع وأضمن لمشغّل
        // أطراف ثالثة، ويحوّل HLS كـ M3U8 وMP4 كـ VIDEO فلا خطأَ 3003 ولا Source error.
        var playUrl = epUrl
        val isHls = runCatching {
            val resp = app.get(epUrl, referer = mainUrl, headers = mapOf(
                "User-Agent" to UA,
                "Range" to "bytes=0-199"
            ))
            val loc = resp.headers["Location"]
            if (loc != null && loc.startsWith("http")) {
                playUrl = loc
                // قد تكون الوجهة وسيطًا داخليًا آخر (~/{n}.mp4) بعدها الشبكة الفعلية — نتبعها.
                var check = loc
                if (loc.contains("reelree.com/api") && loc.contains(".mp4")) {
                    val resp2 = app.get(loc, referer = mainUrl, headers = mapOf("User-Agent" to UA))
                    val loc2 = resp2.headers["Location"]
                    if (loc2 != null && loc2.startsWith("http")) {
                        playUrl = loc2
                        check = loc2
                    } else {
                        check = resp2.text
                    }
                }
                check.contains(".m3u8") || check.contains("m3u8") ||
                    (!check.startsWith("http") && (check.startsWith("#EXT") || check.contains("#EXT-X-")))
            } else {
                val t = resp.text
                playUrl = epUrl
                t.startsWith("#EXT") || t.startsWith("#EXTM3U") || t.contains("#EXT-X-")
            }
        }.getOrDefault(epUrl.endsWith(".m3u8"))
        val hls = isHls
        callback(newExtractorLink(name, FormatTag.tagged("الحلقة $ep", playUrl, if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO), playUrl,
            if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            referer = mainUrl
            quality = perEpQuality(playUrl, hls)
        })
        // الجودات والترجمات الإضافية (إن وُجدت) — بعد بثّ رابط الحلقة أصلًا حتى لا
        // يتأخر التشغيل إطلاقًا؛ أي فشل هنا لا يمسّ الرابط الأساسي (المشغّل يتجاهله).
        runCatching {
            fetchEpisodeExtras(ep, source, seriesId, subtitleCallback, callback)
        }
        return true
    }

    /**
 * هل السلسلة رمز لغة قياسي؟ CloudStream يعرض الاسم مقروءاً (العربية، English)
 * حين يجد الرمز في جدول ISO-639، ويعرض السلسلة نفسها حين لا يجده — فمهمّتنا
 * ألا نرسل ما لا معنى له مثل «sub0».
 */
private fun isLangCode(s: String): Boolean =
    s.length in 2..8 && s.matches(Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?$"))

/**
 * ar_AE وar-SA وen-US كلها تشير إلى العربية/الإنجليزية، وقائمة الجودة في
 * التطبيق تبني أسماء لغاتها من ISO-639-1 (رقمان) لا من الرمز الإقليمي.
 * فنقصّ إلى الجزء الأول: ar_AE ← ar. والرمز القياسي بحروف صغيرة.
 */
private fun normalizeLangCode(s: String): String =
    s.replace('_', '-').substringBefore('-').lowercase()

/**
 * Reelree لا يرسل رموز لغة في أغلب المصادر بل **أسماءً بالإنجليزية**:
 * `subs[].lang` = "Arabic"/"Polish"/"Filipino"، و`subtitleList[].subtitleLanguage`
 * = "en_US". وقبل هذا الجدول كان `isLangCode` يرفض الأسماء فيمرّرها حرفية،
 * فظهر للمستخدم «Arabic» و«Polish» بلا ترجمةٍ ولا رمز.
 *
 * فنستعمل جدول `NAME_TO_CODE` نفسه الذي في `player.js` بالموقع، ونحوّل الاسم
 * إلى رمزٍ قياسي فيطابقه CloudStream على جدول ISO-639 فيعرض الاسم الصحيح.
 */
private val NAME_TO_CODE: Map<String, String> = mapOf(
    "arabic" to "ar", "english" to "en", "french" to "fr", "spanish" to "es",
    "espanol" to "es", "portuguese" to "pt", "german" to "de", "italian" to "it",
    "turkish" to "tr", "russian" to "ru", "japanese" to "ja", "korean" to "ko",
    "thai" to "th", "vietnamese" to "vi", "indonesian" to "id", "indonesia" to "id",
    "malay" to "ms", "hindi" to "hi", "filipino" to "fil", "tagalog" to "fil",
    "polish" to "pl", "polski" to "pl", "romanian" to "ro", "rumania" to "ro",
    "bengali" to "bn", "telugu" to "te", "tamil" to "ta", "czech" to "cs",
    "ceko" to "cs", "chinese" to "zh", "dutch" to "nl", "ukrainian" to "uk",
    "greek" to "el", "norwegian" to "no", "swedish" to "sv", "danish" to "da",
    "finnish" to "fi", "hungarian" to "hu", "hebrew" to "he", "persian" to "fa",
    "urdu" to "ur", "nepali" to "ne", "punjabi" to "pa", "gujarati" to "gu",
    "marathi" to "mr", "kannada" to "kn", "malayalam" to "ml", "sinhala" to "si",
    "burmese" to "my", "khmer" to "km", "lao" to "lo", "mongolian" to "mn",
    // DramaWave ترسل 25 اسماًً لا 23 (مقيس 2026-10-02): «Yunani» هي «Greek»
    // بالحروف العربية، و«Traditional Chinese» هي الصينية التقليدية.
    "yunani" to "el", "traditional chinese" to "zh", "simplified chinese" to "zh",
    "mandarin" to "zh", "cantonese" to "zh", "brazilian portuguese" to "pt",
)

/** «Arabic» ← "ar"، و«en_US» ← "en"، وما لا نعرفه يُرجَع كما هو. */
private fun langCodeOf(raw: String?): String {
    val s = raw?.trim()?.lowercase().orEmpty()
    if (s.isEmpty()) return ""
    NAME_TO_CODE[s]?.let { return it }
    val first = s.split('-', '_', ' ').first()
    NAME_TO_CODE[first]?.let { return it }
    if (isLangCode(first)) return first
    return raw?.trim().orEmpty()
}

/**
 * وهنا مكمنُ علّةٍ قِسْتُها اليوم: **شهادة `reelree.com` منتهية** (مقيس 2026-10-02،
 * `certificate has expired`). المشغّل يتحقّق من الشهادات ولا يملك مُمرِّراً معطوباً
 * كـ`app.get`، فكان كل ترجمةٍ نمُرّها عبر `/api/subtitle-proxy` تظهر في القائمة
 * ثم تفشل عند الاختيار.
 *
 * والوسيطُ لا لزوم له أصلاً: ملفات DramaWave وNetShort **جاهزة WebVTT** سلفاً
 * `text/vtt`، تبدأ `WEBVTT`، توقيتاتها بنقاط لا بفواصل — 25 من 25 لغة). فالمسار
 * المباشر يقود إلى مضيفٍ بشهادة سليمة ويصل أضمن وأسرع. ونُبقي الوسيط فقط فيما
 * كان الامتداد `.srt` فعلاً، وهي حالة لم ترد في القياس لكنها قد ترد.
 */
private val SITE = "https://reelree.com"

private fun subtitleUrl(raw: String): String {
    if (raw.startsWith("/api/")) return SITE + raw
    val tail = raw.substringBefore('?').substringAfterLast('/').lowercase()
    return if (tail.endsWith(".vtt")) raw
    else "$SITE/api/subtitle-proxy?url=" + java.net.URLEncoder.encode(raw, "UTF-8")
}

/** نصّف قائمة ترجمات المصدر (حقول كما في normalizeSubs بموقع Reelree) إلى SubtitleFile. */
    private suspend fun normalizeSubs(node: com.fasterxml.jackson.databind.JsonNode?): List<SubtitleFile> {
        val out = mutableListOf<SubtitleFile>()
        if (node == null || !node.isArray) return out
        val seen = HashSet<String>()
        for (s in node) {
            val url = s.get("url")?.asText()
                ?: s.get("subtitleUrl")?.asText()
                ?: s.get("filePath")?.asText()
                ?: s.get("vtt")?.asText()
                ?: s.get("srt")?.asText()
            if (url.isNullOrBlank()) continue
            val label = s.get("label")?.asText()
                ?: s.get("name")?.asText()
                ?: s.get("display_name")?.asText()
                ?: s.get("title")?.asText()
                ?: ""
            val code = s.get("lang")?.asText()
                ?: s.get("language")?.asText()
                ?: s.get("subtitleLanguage")?.asText()
                ?: s.get("code")?.asText()
            // الاسم الإنجليزي (DramaWave/v2) فيه «Arabic» لا «ar_AE»، فهو أدقّ
            val resolved = langCodeOf(label.ifBlank { code })
            if (resolved.isEmpty()) {
                android.util.Log.i("Reelree", "subtitle has no lang/label, skipping")
                continue
            }
            // الرمز يبقى الرمز هنا؛ subLangLabel يكسوه زوجَه عند الإصدار.
            // والاسم المجهول يُمرَّر حرفياً فهو أوضح من «sub0».
            val lang = if (isLangCode(resolved)) normalizeLangCode(resolved) else resolved
            if (!seen.add(lang)) continue
            out.add(newSubtitleFile(subLangLabel(lang), subtitleUrl(url)))
        }
        return out
    }

    private suspend fun fetchEpisodeExtras(
        ep: String,
        source: String,
        seriesId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (source.isBlank() || seriesId.isBlank()) return
        val showSubs = prefs?.getBoolean(ReelreeSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) != false
        val jsonHeaders = mapOf(
            "User-Agent" to UA,
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to mainUrl,
            "X-Requested-With" to "XMLHttpRequest",
        )
        if (source == "ns") {
            // ===== NetShort: POST /api/episode/play → قائمة الحلقات المرتبة (voucher لكل جودة)،
            // ثم POST /api/subtitles بـ (shortPlayId, episodeId) =====
            val body = mapper.writeValueAsString(mapOf(
                "shortPlayId" to seriesId,
                "playClarity" to "720p",
                "codec" to "h264",
                "_lang" to "ar_AE",
            ))
            val node = runCatching { mapper.readTree(app.post("$mainUrl/api/episode/play",
                requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = jsonHeaders, referer = mainUrl).text) }.getOrNull() ?: return
            val list = node.get("data")?.get("episodePlayList") ?: return
            val n = ep.toIntOrNull() ?: return
            if (list.isArray && list.size() >= n) {
                val ent = list[n - 1]
                val episodeId = ent?.get("episodeId")?.asText()?.takeIf { it.isNotBlank() }
                val voucher = ent?.get("playVoucher")?.asText()?.takeIf { it.isNotBlank() }
                if (!voucher.isNullOrBlank()) {
                    // رابط التشغيل الأساسي بلا تسمية جودة: playClarity في قائمة
                    // التشغيل **null دائماً** (مقيس 2026-10-02) — الجودات الحقيقية تأتي
                    // من /api/subtitles أدناه باسمها الصحيح.
                    callback(newExtractorLink(name, FormatTag.tagged("الحلقة $ep", voucher, ExtractorLinkType.VIDEO), voucher, ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = getQualityFromName("720p")
                    })
                }
                if (!episodeId.isNullOrBlank()) {
                    val subBody = mapper.writeValueAsString(mapOf(
                        "shortPlayId" to seriesId,
                        "episodeId" to episodeId,
                        "codec" to "h264",
                        "_lang" to "ar_AE",
                    ))
                    val subNode = runCatching { mapper.readTree(app.post("$mainUrl/api/subtitles",
                        requestBody = subBody.toRequestBody("application/json; charset=utf-8".toMediaType()),
                        headers = jsonHeaders, referer = mainUrl).text) }.getOrNull() ?: return
                    val data = subNode.get("data")
                    // ✅ **الجودات هنا لا في /api/episode/play** (مقيس 2026-10-02):
                    // قائمة التشغيل تُعيد episodeId + playVoucher فقط وplayClarity فيها null،
                    // بينما data.episodePlayList[] في /api/subtitles فيه 540p و 720p و 1080p vouchers مستقلة؛
                    // وهي التي كانت تُظهر أسماء جودات فارغةً في القائمة.
                    val qList = data?.get("episodePlayList")
                    if (qList != null && qList.isArray) {
                        val seenQ = HashSet<String>()
                        for (q in qList) {
                            val qUrl = q.get("playVoucher")?.asText()?.takeIf { it.isNotBlank() } ?: continue
                            val clarity = q.get("playClarity")?.asText()?.trim().orEmpty()
                            if (clarity.isBlank()) continue
                            val label = "الحلقة $ep · $clarity"
                            if (!seenQ.add(label)) continue
                            callback(newExtractorLink(name, FormatTag.tagged(label, qUrl, ExtractorLinkType.VIDEO), qUrl, ExtractorLinkType.VIDEO) {
                                referer = mainUrl
                                quality = qualityOfLabel(clarity)
                            })
                        }
                    }
                    if (showSubs) {
                        for (sf in normalizeSubs(data?.get("subtitleList"))) subtitleCallback(sf)
                    }
                }
            }
        } else if (source in setOf("dw", "dx", "sm", "fr", "ft") && seriesId.isNotBlank()) {
            // ===== سواها (DramaBox/Swan/FullTV...): GET /api/{src}/{seriesId}/{n}.json
            // يحمل subs و qualities في النداء نفسه =====
            val node = runCatching { mapper.readTree(app.get("$mainUrl/api/$source/$seriesId/$ep.json", referer = mainUrl,
                headers = mapOf("User-Agent" to UA, "Origin" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest")).text) }.getOrNull() ?: return
            if (showSubs) {
                val subs = node.get("subs") ?: node.get("data")?.get("subs")
                for (sf in normalizeSubs(subs)) subtitleCallback(sf)
            }
            // مقيس 2026-10-02: هذا الحقل قائمة فارغة [] في سواها دائماً، والجودات
            // تظهر في الماستر لا في الـJSON. فنقرأها من النص في perEpQuality أعلاه.
            val quals = node.get("qualities") ?: node.get("data")?.get("qualities")
            if (quals != null && quals.isArray) {
                val seenUrls = HashSet<String>()
                for (q in quals) {
                    val qUrl = q.get("url")?.asText() ?: q.get("directUrl")?.asText() ?: continue
                    if (!seenUrls.add(qUrl)) continue
                    val qLabel = q.get("quality")?.asText() ?: q.get("label")?.asText() ?: q.get("playClarity")?.asText().orEmpty()
                    callback(newExtractorLink(name, FormatTag.tagged("الحلقة $ep · ${qLabel.ifBlank { "جودة إضافية" }}", qUrl, if (qUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO),
                        qUrl, if (qUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = qualityOfLabel(qLabel)
                    })
                }
            }
        }
    }

    private fun cleanTitle(t: String): String {
        var s = t
            .replace(Regex("""مشاهدة\s*"""), "")
            .replace(Regex("""\s*[-–—|:]\s*.*$"""), "")
            .replace(Regex("""\s*(كامل|جميع الحلقات|مسلسل|حلقات كاملة).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (s.length < 2) s = t.trim()
        return s
    }

    /**
     * CloudStream يبني قائمة الجودة من الحقل `quality` (Int) لا من `name`، وكل روابط
     * Reelree كانت تمرّر `getQualityFromName("720p")` — فتظهر كلها بنفس الرقم وتختفي
     * الأسماء الحقيقية للموقع. هنا نُخرج الرقم الحقيقي من التسمية.
     * تسمية بلا رقم (مثل «جودة إضافية») → Unknown كي لا تصطدم بجودة رابط الحلقة.
     */
    /**
     * أعلى دقّة في ماستر HLS.
     *
     * مسلسلات Reelree *رأسية*: 1080×1920 لا 1920×1080. والضلع الذي يحدّد رقم
     * الجودة هو **القصير** (1080) لا الطويل (1920): لو أخذنا الأكبر لقُلنا 1440p
     * لحلقة عرضُها 1080 بكسل لا غير. فنقرأ `RESOLUTION=w×h` ونأخذ
     * `min(w, h)` — وهو معيار الجودة في HLS أصلاً — فتبقى 1080 و720 و540
     * و480 و360 و240 كما يعلنها الموقع.
     */
    private fun bestResolutionOf(masterText: String): Int? =
        Regex("RESOLUTION=(\\d{3,4})x(\\d{3,4})")
            .findAll(masterText)
            .map { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            .maxOrNull()

    /**
     * جودة رابط الحلقة كما يعلنها الماستر، لا كما نفترضه. كان كل رابط يُبثّ
     * بـ`getQualityFromName("720p")` مفترضةً، فظهرت «720p» على حلقات 1080
     * وعلى سلاسل لا تملك إلا 240. الرابط الذي ليس HLS (بصمة MP4) لا يعلن شيئاً
     * فيرجع `Unknown` بدل اختراع رقم.
     */
    private suspend fun perEpQuality(playUrl: String, isHls: Boolean): Int {
        if (!isHls) return Qualities.Unknown.value
        val text = runCatching {
            app.get(playUrl, referer = mainUrl, headers = mapOf("User-Agent" to UA)).text
        }.getOrNull() ?: return Qualities.Unknown.value
        if (!text.startsWith("#EXT")) return Qualities.Unknown.value
        val best = bestResolutionOf(text) ?: return Qualities.Unknown.value
        return qualityOfLabel("${best}p")
    }

    private fun qualityOfLabel(label: String?): Int {
        val raw = label?.trim().orEmpty()
        val digits = Regex("""(\d{3,4})""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Qualities.Unknown.value
        return getQualityFromName(
            when {
                digits >= 2160 -> "2160p"
                digits >= 1440 -> "1440p"
                digits >= 1080 -> "1080p"
                digits >= 720 -> "720p"
                digits >= 480 -> "480p"
                digits >= 360 -> "360p"
                digits >= 240 -> "240p"
                else -> "144p"
            }
        )
    }
}
