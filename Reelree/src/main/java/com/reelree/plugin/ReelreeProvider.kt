package com.reelree.plugin

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
 *          ملف merged واحد يضم كل الحلقات، بجودات متعددة (+ ترجمات/أصوات إن وُجدت في master).
 *          direct = https://reelree.com/api/v2/{code}.m3u8  ← master متعدد الجودات
 *
 * loadLinks يقدّم سيرفرين لكل حلقة:
 *  1) "الحلقات"        ← عبر data-media / %EP% (الحلقة المستقلة)
 *  2) "السيرفر الكامل" ← عبر data-rr-server2.direct (الملف المدمج بكل الجودات/الترجمات/الأصوات)
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

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = mainUrl).document
            parseCards(doc)
        } catch (e: Exception) { null }
    }

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
            if (fullMaster.startsWith("http")) {
                eps.add(newEpisode("$mainUrl/full|$fullMaster") {
                    episode = 0
                    name = "الحلقة كاملة"
                })
            }
            if (mediaTemplate.isNullOrBlank() || !mediaTemplate.startsWith("http")) {
                if (eps.isEmpty() && fullMaster.startsWith("http")) {
                    eps.add(newEpisode("$mainUrl/full|$fullMaster") {
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
                val q = resMatch?.groupValues?.get(2)?.toIntOrNull()
                    ?: idxRe.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { bw ->
                        when {
                            bw >= 4000000 -> 1080
                            bw >= 2000000 -> 720
                            else -> 480
                        }
                    }
                    ?: 720
                out.add(uri to q)
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
            val fullMaster = data.substring(firstPipe + 1).trim()
            if (!fullMaster.startsWith("http")) return false

            callback(newExtractorLink(name, "الحلقة كاملة", fullMaster, ExtractorLinkType.M3U8) {
                referer = mainUrl
                quality = getQualityFromName("1080p")
            })

            // الترجمات والأصوات من master السيرفر الكامل — نجلب master بمرونة (مهلات/أعد)
            // حتى لا نعتمد على رحلة واحدة قد تهلة (السيرفر بطيء). نستخرج tracks إن نجحنا،
            // وإن فشلنا نبقى على master المسلّم أصلًا (المشغّل قد يحلّها هو أيضًا).
            var masterText: String? = null
            for (i in 0 until 3) {
                masterText = runCatching {
                    app.get(fullMaster, referer = mainUrl, headers = mapOf("User-Agent" to UA)).text
                }.getOrNull()
                if (!masterText.isNullOrBlank()) break
                try { Thread.sleep(800L * (i + 1)) } catch (_: InterruptedException) {}
            }
            if (!masterText.isNullOrBlank() && masterText.startsWith("#EXT")) {
                for (t in extractTracks(masterText, fullMaster)) {
                    try {
                        if (t.kind == "SUBTITLES") {
                            // إظهار الترجمة — الافتراضي true = سلوك اليوم حرفياً؛ إطفاؤه
                            // يتخطى ملف الترجمة فقط، ولا يمسّ رابط الفيديو إطلاقاً.
                            if (prefs?.getBoolean(ReelreeSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) != false) {
                                subtitleCallback(newSubtitleFile(t.lang, t.uri))
                            }
                        } else if (t.kind == "AUDIO") {
                            // إظهار روابط الصوت المنفصلة — الافتراضي true = سلوك اليوم
                            // حرفياً؛ إطفاؤه يتخطى مسارات الصوت فقط، ولا يمسّ رابط الحلقة.
                            if (prefs?.getBoolean(ReelreeSettingsBottomSheet.KEY_SHOW_AUDIO_TRACKS, true) != false) {
                                callback(newExtractorLink(name, "صوت: ${t.lang}", t.uri, ExtractorLinkType.M3U8) {
                                    referer = mainUrl
                                    quality = getQualityFromName("720p")
                                })
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            return true
        }

        // ===== المسار 2: الحلقات المنفصلة بالرقم — عبر data-media / %EP% =====
        // روابط /api/{dx|rs}/{id}/{n}.m3u8 — رغم الامتداد .m3u8 المتشابه فإن المحتوى مختلف:
        //   - /api/dx/*/{n}.m3u8 → ملف MP4 مباشر (ftypisom...)
        //   - /api/rs/*/{n}.m3u8 → HLS حقيقي (#EXTM3U...)
        // لذلك نفحص أول بايتات (range صغير سريع) لتحديد النوع الصحيح — إرسال HLS كـ VIDEO
        // يفشل بـ UnrecognizedInputFormatException، وإرسال MP4 كـ HLS يفشل بـ Source error أيضًا.
        // (v5) نمرّر ضمن data أيضًا منصة المصدر + seriesId (من data-source/data-series) لنستخرج
        // منه الترجمة والجودات الإضافية من واجهة Reelree (راجع fetchEpisodeExtras أدناه).
        val parts = data.split("|", limit = 4)
        val template = parts.getOrNull(0)?.trim() ?: return false
        val ep = parts.getOrNull(1)?.trim() ?: return false
        val source = parts.getOrNull(2)?.trim().orEmpty()
        val seriesId = parts.getOrNull(3)?.trim().orEmpty()
        if (template.isBlank() || !template.startsWith("http")) return false

        val epUrl = template.replace("%EP%", ep)
        val isHls = epUrl.endsWith(".m3u8") && runCatching {
            val sig = app.get(epUrl, referer = mainUrl, headers = mapOf(
                "User-Agent" to UA,
                "Range" to "bytes=0-199"
            )).text
            sig.startsWith("#EXT") || sig.startsWith("#EXTM3U") || sig.contains("#EXT-X-")
        }.getOrDefault(false)
        callback(newExtractorLink(name, "الحلقة $ep", epUrl,
            if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
            referer = mainUrl
            quality = getQualityFromName("720p")
        })
        // الجودات والترجمات الإضافية (إن وُجدت) — بعد بثّ رابط الحلقة أصلًا حتى لا
        // يتأخر التشغيل إطلاقًا؛ أي فشل هنا لا يمسّ الرابط الأساسي (المشغّل يتجاهله).
        runCatching {
            fetchEpisodeExtras(ep, source, seriesId, subtitleCallback, callback)
        }
        return true
    }

    /** نصّف قائمة ترجمات المصدر (حقول كما في normalizeSubs بموقع Reelree) إلى SubtitleFile. */
    private suspend fun normalizeSubs(node: com.fasterxml.jackson.databind.JsonNode?): List<SubtitleFile> {
        val out = mutableListOf<SubtitleFile>()
        if (node == null || !node.isArray) return out
        val seen = HashSet<String>()
        var i = 0
        for (s in node) {
            val url = s.get("url")?.asText()
                ?: s.get("subtitleUrl")?.asText()
                ?: s.get("filePath")?.asText()
                ?: s.get("vtt")?.asText()
                ?: s.get("srt")?.asText()
            if (url.isNullOrBlank()) continue
            val code = s.get("lang")?.asText()
                ?: s.get("language")?.asText()
                ?: s.get("subtitleLanguage")?.asText()
                ?: s.get("code")?.asText()
            val label = s.get("label")?.asText()
                ?: s.get("name")?.asText()
                ?: s.get("display_name")?.asText()
                ?: s.get("title")?.asText()
                ?: ""
            val lang = (code ?: label).ifBlank { "sub$i" }.trim()
            val key = "$lang|${label.orEmpty()}"
            if (!seen.add(key)) continue
            out.add(newSubtitleFile(lang, url))
            i++
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
                    val clarity = ent.get("playClarity")?.asText()?.trim().orEmpty()
                    callback(newExtractorLink(name, "الحلقة $ep${if (clarity.isBlank()) "" else " · $clarity"}",
                        voucher, ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = getQualityFromName(if (clarity.isBlank()) "720p" else clarity)
                    })
                }
                if (showSubs && !episodeId.isNullOrBlank()) {
                    val subBody = mapper.writeValueAsString(mapOf(
                        "shortPlayId" to seriesId,
                        "episodeId" to episodeId,
                        "codec" to "h264",
                        "_lang" to "ar_AE",
                    ))
                    val subNode = runCatching { mapper.readTree(app.post("$mainUrl/api/subtitles",
                        requestBody = subBody.toRequestBody("application/json; charset=utf-8".toMediaType()),
                        headers = jsonHeaders, referer = mainUrl).text) }.getOrNull() ?: return
                    val subs = subNode.get("data")?.get("subtitleList")
                    for (sf in normalizeSubs(subs)) subtitleCallback(sf)
                }
            }
        } else if (source in setOf("dw", "dx", "sm", "fr", "ft") && seriesId.isNotBlank()) {
            // ===== سواها (DramaBox/Swan/FullTV...): GET /api/{src}/{seriesId}/{n}.json
            // يحمل subs و qualities في النداء نفسه =====
            val node = runCatching { mapper.readTree(app.get("$mainUrl/api/$source/$seriesId/$ep.json", referer = mainUrl,
                headers = mapOf("User-Agent" to UA)).text) }.getOrNull() ?: return
            if (showSubs) {
                val subs = node.get("subs") ?: node.get("data")?.get("subs")
                for (sf in normalizeSubs(subs)) subtitleCallback(sf)
            }
            val quals = node.get("qualities") ?: node.get("data")?.get("qualities")
            if (quals != null && quals.isArray) {
                val seenUrls = HashSet<String>()
                for (q in quals) {
                    val qUrl = q.get("url")?.asText() ?: q.get("directUrl")?.asText() ?: continue
                    if (!seenUrls.add(qUrl)) continue
                    val qLabel = q.get("quality")?.asText() ?: q.get("label")?.asText() ?: q.get("playClarity")?.asText().orEmpty()
                    callback(newExtractorLink(name, "الحلقة $ep · ${qLabel.ifBlank { "جودة إضافية" }}",
                        qUrl, if (qUrl.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = getQualityFromName(if (qLabel.isBlank()) "720p" else qLabel)
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
}
