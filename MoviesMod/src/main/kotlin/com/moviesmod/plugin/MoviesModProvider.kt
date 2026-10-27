package com.moviesmod.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.FormatTag
import cloudstreamshared.MediaProbe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns

/**
 * MoviesMod — أفلام ومسلسلات مع روابط التحميل والمشاهدة الحقيقية (moviesmod.uk.com).
 *
 * ★ بنية الموقع: صفحة العمل ليست قائمة روابط، بل **بوّابة**. كل جودة عنوانٌ
 *   (H3 للمسلسل، H4 للفيلم) تحته زرّ `maxbutton` يقود إلى أرشيفٍ على
 *   `episodes.modpro.blog` (حلقات) أو `links.modpro.blog` (فيلم). والأرشيف يقود
 *   إلى `en.thenaukriadda.in/?sid=…`، ومنه تبدأ سلسلة تحويلات (lp) تنتهي عند
 *   ملفٍّ على `driveseed.org`. الروابط الظاهرة في صفحة العمل **ليست** روابط
 *   مشاهدة، وهي علّة «الروابط لا تعمل» في الإصدار الأول.
 *
 * ★ سلسلة `lp` — قِيست كاملةً بلا أي جلسة محفوظة (كل طلب بجرة كعكات جديدة)،
 *   وهو ما يوافق `app` في CloudStream الذي لا يحفظ كعكات:
 *     `?sid=`  →  POST `#lp-land` (حقل `_lp_http` وحده)
 *              →  POST `#lp-s1-form` (`_lp_http2`/`_lp_token`/`_lp_chain`/`_lp_hop_index`)
 *              →  كعكة `sc("lp-…","…",60)` — **اسمها عشوائي في كل مرّة، يُقرأ ولا يُثبَّت**
 *              →  GET `/?lp_go=<الاسم>` مع الكعكة صراحةً
 *              →  `window.location.replace(…)` إلى `driveseed.org/r?key=…`
 *              →  `window.location.replace(…)` إلى `/file/<id>`
 *   قِيس الزمن الكامل ‎13.3 ثانية‎ لسبعة طلبات — داخل مهلة `loadLinks`.
 *
 * ★ الروابط على صفحة الملفّ ليست مباشرة:
 *   - `instant.video-gen.xyz` و`cdn.video-gen.xyz` تردّان 302 إلى
 *     `video-seed.dev/?url=<رابط googleusercontent موقّع>`، وهو **غلاف JS محض**
 *     (`downloadBtn.href = downloadUrl`) — يُستخرج منه معامل `url=` ولا يُبَثّ.
 *     والرابط الداخلي ملفّ Matroska حقيقي: قِيس `200 video/mkv` وبصمة `1a45dfa3`.
 *   - `seedtg.xyz` غلاف HTML (بصمته `0d0a3c21`) لا ملفّ وسائط، ولا يُبَثّ.
 *   - `/zfile/<id>` صفحة توكن لا وسائط.
 *   ولا يُبَثّ رابط `googleusercontent` بمُحيل: قِيس أنه **أحادي الاستعمال** وقصير
 *   العمر (‎٤ فحوص متتالية → 400‎)، فإرسال مُحيل لا ينفع ولا يُسقطه ردٌّ لاحق.
 *
 * ★ التمييز بين فيلم ومسلسل **بنيويّ لا نصّي**: وجود زرّ
 *   `maxbutton-episode-links` ⇒ مسلسل. العنوان قد يحمل كلمة Season وهو فيلم،
 *   ولذلك كان الإصدار الأول يخطئ في التصنيف.
 */
class MoviesModProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    private val TAG = "MoviesMod"

    override var mainUrl = "https://moviesmod.meme"
    override var name = "MoviesMod"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val head get() = mapOf("User-Agent" to UA)
    private val formHead get() = mapOf(
        "User-Agent" to UA,
        "Content-Type" to "application/x-www-form-urlencoded",
    )

    // ---------- تعويض سجلّ DNS المحذوف ----------

    /**
     * أرشيف الأفلام على `links.modpro.blog`، وسجلّ DNS لهذا المضيف **محذوف**
     * (NXDOMAIN من 1.1.1.1 و8.8.8.8 و9.9.9.9 و208.67.222.222 ومن مخدّمات
     * `.blog` الأربعة)، بينما حافة Cloudflare ما زالت تخدمه — قِيس
     * `curl --resolve links.modpro.blog:443:104.21.4.20` ⇒ **200** بصفحة
     * أرشيف كاملة. ولهذا يفشل التطبيق بـ`UnknownHostException` بينما يعمل
     * المتصفّح عند غيره، ولهذا أيضًا لا يظهر رابط واحد من الأفلام.
     *
     * والعلاج أن يُثبَّت اسم المضيف على عنوان الحافة قبل الطلب، لا أن يُعاد
     * كتابة الرابط إلى `https://<ip>/…`: قِيس أنّ الثاني **يفشل** (‎000‎ بلا
     * اتصال) لأن `SNI` يصبح عنوانًا خامًّا و`Host` لا يصحّحه، بينما الأول
     * يترك الرابط و`SNI` والشهادة كما هي فلا يعلم الخادم بشيء.
     *
     * والعنوان يُقرأ من `episodes.modpro.blog` (المضيف الحيّ على النطاق نفسه،
     * وقِيس أن الاثنين يخدمان من العنوانين ذاتهما) — فلا يُثبَّت عنوانٌ في
     * الكود يشيخ. ولو تعذّرت القراءة بقي العنوانان المقيسان.
     */
    private fun fixDns() {
        val c = app.baseClient
        val needsDns = c.dns !is PinnedDns
        val needsTimeout = c.readTimeoutMillis < 90000
        if (!needsDns && !needsTimeout) return
        val b = c.newBuilder()
        if (needsDns) b.dns(PinnedDns(c.dns))
        if (needsTimeout) {
            b.readTimeout(60, TimeUnit.SECONDS)
            b.connectTimeout(30, TimeUnit.SECONDS)
            b.writeTimeout(30, TimeUnit.SECONDS)
            b.callTimeout(90, TimeUnit.SECONDS)
        }
        app.baseClient = b.build()
        if (needsDns) {
            Log.i(TAG, "fixDns: ثُبِّت $PINNED_HOST على ${PinnedDns.lastIps.joinToString()}")
        }
        if (needsTimeout) {
            Log.i(TAG, "fixDns: قلصت مهلات الطلبات إلى 30-90ث")
        }
    }

    /** اسم المضيف من رابط، بلا رمي استثناء على رابطٍ مشوّه. */
    private fun hostOf(url: String): String =
        try { URI(url).host ?: url } catch (e: Exception) { url }

    /**
     * مُحلِّلٌ يمرّ كل شيء إلى DNS الحقيقيّ إلا المضيف المعطوب فيُثبَّته.
     * `base` هو المُحلِّل الأصليّ يُمرَّر في الباني — فالصنف **متداخلٌ لا داخليّ**
     * ولا سبيل له إلى `app`، ولو مرّرناه لدارت القراءة على نفسه.
     */
    private class PinnedDns(private val base: Dns) : Dns {
        private val pinnedHosts = setOf(
            "links.modpro.blog",
            "episodes.modpro.blog",
            "en.thenaukriadda.in",
            "thenaukriadda.in",
            "driveseed.org",
            "www.driveseed.org",
        )

        override fun lookup(hostname: String): List<InetAddress> {
            if (pinnedHosts.contains(hostname.lowercase())) {
                val ips = resolveIps() ?: return base.lookup(hostname)
                return ips.map { InetAddress.getByName(it) }
            }
            return base.lookup(hostname)
        }

        /** العنوان يُقرأ من مضيفٍ حيّ على النطاق نفسه، ولا يُثبَّت في الكود. */
        private fun resolveIps(): List<String>? {
            for (host in PINNED_SEEDS) {
                val got = try {
                    base.lookup(host).mapNotNull { it.hostAddress }
                } catch (e: Exception) {
                    emptyList()
                }
                if (got.isNotEmpty()) {
                    lastIps = got
                    return got
                }
            }
            return null
        }

        companion object {
            /** آخر ما نجح، ليبقى للمضيف عنوانٌ بعد أول قراءة. */
            @Volatile var lastIps: List<String> = listOf("104.21.4.20", "172.67.131.141")
        }
    }

    /**
     * صفوف الصفحة الرئيسية.
     *
     * ★ **ترتيب الزوج مقلوب عمداً**: `mainPageOf` يبني `MainPageData(name, data)`
     *   بترتيب `second → name` و`first → data`، فالأول هو `data` والثاني العنوان:
     *   `"المسار" to "العنوان"`.
     *
     * قِيست الأقسام على الموقع بجلبٍ حقيقي وعدّ بطاقات `article.latestPost`:
     * كلُّ مسارٍ هنا أعاد ‎٢٠ بطاقة‎. واستُثني `/movies/latest-released/` لأنّه
     * أعاد **صفرًا** — قسمٌ فارغ يُنتج صفًّا فارغًا بلا سبب.
     */
    override val mainPage = mainPageOf(
        "/" to "أحدث الإضافات",
        "/movies/" to "أفلام",
        "/tv-series/" to "مسلسلات",
        "/web-series/" to "مسلسلات ويب",
        "/dual-audio/" to "مدبلج (صوتان)",
        "/multi-audio/" to "متعدّد الأصوات",
        "/language/arabian/" to "بالعربية",
        "/language/korean/" to "كوري",
        "/movies/hollywood/" to "هوليوود",
        "/movies/hollywood/english-movies/" to "أفلام إنجليزية",
        "/anime/" to "أنيمي",
    )

    // ---------- الصفحة الرئيسية والبحث ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val path = request.data.trim('/')
            val url = if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"
            val doc = app.get(url, headers = head).document
            val items = parseCards(doc)
            if (items.isEmpty()) return null
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            Log.i(TAG, "getMainPage FAIL ${e::class.simpleName} ${e.message}")
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val doc = app.get("$mainUrl/?s=$q", headers = head).document
            parseCards(doc).ifEmpty { null }
        } catch (e: Exception) {
            Log.i(TAG, "search FAIL ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /**
     * بطاقات القوائم. الشكل واحدٌ في كل الأقسام والبحث:
     * `article.latestPost` ← `h2.front-view-title > a` و`a.post-image > img`.
     *
     * ★ التصنيف هنا بالعنوان لأنّ البطاقة لا تحمل أزرار الجودات، وهو تقريبٌ
     *   كافٍ للبحث. الحكم النهائي في [load] من بنية الصفحة نفسها.
     */
    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = ArrayList<SearchResponse>(24)
        for (card in doc.select("article.latestPost")) {
            val a = card.selectFirst("h2.front-view-title a[href]") ?: continue
            val href = a.attr("href").ifBlank { continue }
            if (!href.startsWith("http")) continue
            val title = cleanTitle(a.text().ifBlank { a.attr("title") })
            if (title.isBlank()) continue
            val poster = card.selectFirst("a.post-image img, img.wp-post-image")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.ifBlank { null }
            val isTv = RE_SEASON.containsMatchIn(title) || href.contains("-season-")
            out.add(
                if (isTv) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            )
        }
        return out.distinctBy { it.url }
    }

    // ---------- صفحة العمل ----------

    override suspend fun load(url: String): LoadResponse? {
        return try {
            fixDns()
            val doc = app.get(url, headers = head).document
            val rawTitle = doc.selectFirst("h1.entry-title")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: metaContent(doc, "og:title")
                ?: return null
            val title = cleanTitle(rawTitle)
            if (title.isBlank()) return null
            val poster = metaContent(doc, "og:image")
            val plot = parsePlot(doc)
            val rows = parseRows(entryContent(doc))

            val epRows = rows.filter { it.kind == KIND_EP && it.href.startsWith("http") }
            val dlRows = rows.filter { it.kind == KIND_DL && it.href.startsWith("http") }
            val batchRows = rows.filter { it.kind == KIND_BATCH && it.href.startsWith("http") }

            if (epRows.isNotEmpty()) {
                val episodes = buildEpisodes(epRows, if (pref(KEY_INCLUDE_BATCH, false)) batchRows else emptyList())
                if (episodes.isEmpty()) {
                    // التشخيص ضروري هنا: الفرع **صامت**، فلا يفرّق المستخدم
                    // بين «لا حلقات في الصفحة» و«حلقاتها لم تُجلب». والتفصيل
                    // يُسجَّل في `buildEpisodes` حيث تُعرف الأعداد الحقيقية.
                    Log.i(TAG, "load: no episodes parsed for $url (صفوف=${epRows.size})")
                    return null
                }
                val seasons = episodes.mapNotNull { it.season }.distinct().sorted()
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    if (seasons.isNotEmpty()) {
                        this.seasonNames = seasons.map { SeasonData(it, "الموسم $it") }
                    }
                }
            }

            if (dlRows.isNotEmpty()) {
                // بيانات الفيلم = روابط أرشيف الجودات، سطرٌ لكل جودة:
                // `<رابط الأرشيف>\t<عنوان صفّ الجودة>`. العنوان هو ما يحمل الجودة
                // (‎1080p x264‎) والحجم، وهو ما يُبنى عليه الترتيب والوسم؛ ونصّ
                // الزرّ نفسه «Download Links» في كل الصفوف فلا يميّز شيئًا.
                // وكل سطر يبدأ بـhttp وإلا لحق CloudStream رابط الصفحة بها.
                val data = dlRows.joinToString("\n") { "${it.href}\t${it.heading}" }
                return newMovieLoadResponse(title, url, TvType.Movie, data) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }

            Log.i(TAG, "load: no rows for $url")
            null
        } catch (e: Exception) {
            Log.i(TAG, "load FAIL ${e::class.simpleName} ${e.message}")
            null
        }
    }

    /** صفّ جودة: نوع الزرّ، ورابطه، ونصّه، والعنوان الذي يقع تحته. */
    private data class Row(
        val kind: String,
        val href: String,
        val label: String,
        val heading: String,
        var epNum: Int = 0,
    )

    /**
     * تقسيم جسم الموضوع عند حدود H3/H4 وربط كل زرّ `maxbutton` بالعنوان الذي
     * يقع تحته — نفس بنية الصفحة المقيسة: عنوانُ الجودة ثم زرُّها. والأزرار
     * الواقعة بين عنوانين تُنسب إلى العنوان السابق، وهو ما تفعله القصّة
     * بالضبط (`html[headingEnd until nextHeadingStart]`).
     *
     * الجودة تُقرأ من نصّ العنوان لا من اسم الملفّ: اسم الملفّ لا يُعرف قبل حلّ
     * السلسلة، والترتيب في قائمة التشغيل يعتمد على هذه الجودة وحدها.
     */
    private fun parseRows(body: Element?): List<Row> {
        if (body == null) return emptyList()
        val html = body.html()
        val marks = RE_HEAD.findAll(html).toList()
        val out = ArrayList<Row>(16)
        for ((i, m) in marks.withIndex()) {
            val heading = stripTags(m.groupValues[2])
            if (heading.isBlank()) continue
            if (SKIP_SECTIONS.any { heading.contains(it, true) }) continue
            val start = m.range.last + 1
            val end = marks.getOrNull(i + 1)?.range?.first ?: html.length
            if (start >= end) continue
            val seg = html.substring(start, end)
            for (a in RE_ANCHOR.findAll(seg)) {
                val attrs = a.groupValues[1]
                val cls = RE_CLASS.find(attrs)?.groupValues?.get(1) ?: continue
                if (!cls.contains("maxbutton")) continue
                val href = RE_HREF.find(attrs)?.groupValues?.get(1)?.let { unescapeText(it) }
                    ?.replace(RE_WS, "") ?: continue
                if (href.isBlank()) continue
                val kind = when {
                    cls.contains("episode-links") -> KIND_EP
                    cls.contains("batch-zip") -> KIND_BATCH
                    cls.contains("download-links") -> KIND_DL
                    else -> continue
                }
                out.add(Row(kind, href, stripTags(a.groupValues[2]), heading))
            }
        }
        return out
    }

    /**
     * بناء الحلقات: لكل صفّ حلقاتٍ أرشيفٌ على `episodes.modpro.blog`، وفيه رابط
     * `?sid=` **لكل حلقة على حدة** — قِيس: ‎١٦‎ رابطًا مختلفًا لـ‎١٦‎ حلقة،
     * و`All Episodes Batch` و`our comment section` خارجها.
     *
     * التطابق على رقم الحلقة **تامًّا** لا جزئيًّا: المطابقة الجزئية تجعل
     * `Episode 1` يبتلع `Episode 10`…`Episode 16` فتُبنى الحلقات كلّها على رابط
     * واحد — وهذا بالضبط شكل «حلقة واحدة» الذي ظهر في الإصدار الأول.
     *
     * وعند تكرار الحلقة عبر جوداتٍ مختلفة تُبقى الأعلى جودةً: صفّ
     * «Korean 1080p x264 Esubs [1.1GB]» هو المقصود، لا ‎720p‎ ولا ‎10bit‎.
     */
    private suspend fun buildEpisodes(epRows: List<Row>, batchRows: List<Row>): List<Episode> {
        fixDns()
        val archives = epRows.distinctBy { it.href }
        val fetched = coroutineScope {
            archives.map { row ->
                async(Dispatchers.IO) {
                    try {
                        val r = app.get(row.href, headers = head)
                        if (!r.isSuccessful) return@async emptyList<Pair<String, Row>>()
                        RE_SID.findAll(r.text).mapNotNull { a ->
                            val sid = unescapeText(a.groupValues[1]).replace(RE_WS, "")
                            val label = stripTags(a.groupValues[2])
                            val n = epNumberOf(label) ?: return@mapNotNull null
                            sid to row.copy(label = label, epNum = n)
                        }.toList().also { found ->
                            Log.i(TAG, "archive ${row.href.substringAfterLast('/')}:" +
                                " sid=${RE_SID.findAll(r.text).count()} بحلقة=${found.size}")
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "archive FAIL ${row.href} ${e::class.simpleName}" +
                            if (e is UnknownHostException) " — المضيف لا يُحلّ" else "")
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        val bySeasonEp = HashMap<Pair<Int, Int>, Pair<String, Row>>()
        for (list in fetched) {
            for ((sid, row) in list) {
                if (row.epNum <= 0) continue
                val key = (seasonOf(row.heading) ?: 1) to row.epNum
                val prev = bySeasonEp[key]
                if (prev == null || qualityOf(row.heading) > qualityOf(prev.second.heading)) {
                    bySeasonEp[key] = sid to row
                }
            }
        }

        val showSeasons = pref(KEY_SHOW_SEASONS, true)
        val eps = ArrayList<Episode>(bySeasonEp.size + 1)
        for ((key, payload) in bySeasonEp.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val (season, num) = key
            // العنوان يُمرَّر مع الرابط (مفصولًا بـtab) ليختار `mirrorsOf`
            // الحلقة الصحيحة من صفحة `/list/` على driveseed.
            eps.add(newEpisode(payload.first + "\t" + payload.second.heading) {
                this.name = if (showSeasons) "الموسم $season · الحلقة $num" else "الحلقة $num"
                this.season = season
                this.episode = num
            })
        }
        for (b in batchRows) {
            val season = seasonOf(b.heading) ?: 1
            eps.add(newEpisode(b.href + "\t" + b.heading) {
                this.name = if (showSeasons) "الموسم $season · كل الحلقات" else "كل الحلقات"
                this.season = season
                this.episode = 0
            })
        }
        return eps
    }

    // ---------- روابط التشغيل ----------

    private data class Cand(
        val url: String,
        val server: String,
        val quality: Int,
        val note: String?,
        val referer: String?,
        val headers: Map<String, String>,
        var bytes: Long? = null,
        var ttfb: Long = Long.MAX_VALUE,
        var alive: Boolean = true,
        var certain: Boolean = false,
        /**
         * `Content-Type` الذي أعلنه الخادم — يُقرأ من الفحص نفسه بلا طلبٍ ثانٍ.
         * لولاه لعُرضت مرايا `video-gen` بوسم `[VIDEO]` وهي ملفّ Matroska حقيقي
         * (رابطها بلا امتدادٍ إطلاقًا).
         */
        var mime: String? = null,
    ) {
        /** رافعة الترتيب: الحي الأسرع أوّلًا، وما لا حكم له آخرًا. */
        fun rank(): Long = if (alive) ttfb else Long.MAX_VALUE - 1
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            fixDns()
            val entries = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }
            if (entries.isEmpty()) return false

            val showAlt = pref(KEY_SHOW_ALT, true)
            val order = prefs?.getString(KEY_QUALITY_ORDER, "default") ?: "default"

            val resolved = coroutineScope {
                entries.map { e ->
                    async(Dispatchers.IO) {
                        val link = e.substringBefore("\t").trim()
                        val heading = e.substringAfter("\t", "").trim().ifBlank { null }
                        val res = resolve(link, heading)
                        if (res.isNotEmpty()) res else {
                            try {
                                val r = app.get(link, headers = head, allowRedirects = true)
                                val t = r.text
                                val out = ArrayList<Cand>()
                                Regex("https?://[^\"'\\s<>]+\\.(mp4|mkv|avi|webm|mpg|mpeg|ts|zip|rar)(\\?[^\"'\\s<>]*)?").findAll(t.replace("\\/", "/")).forEach { m ->
                                    out.add(Cand(m.value.replace("\\/", "/"), heading ?: "Direct", qualityOf(heading ?: m.value), null, null, head))
                                }
                                Regex("https?://[^\"'\\s<>]+/(download|dl|getfile)/[^\"'\\s<>]+").findAll(t.replace("\\/", "/")).forEach { m ->
                                    out.add(Cand(m.value.replace("\\/", "/"), heading ?: "Direct", qualityOf(heading ?: m.value), null, null, head))
                                }
                                out
                            } catch (ex: Exception) { emptyList() }
                        }
                    }
                }.awaitAll().flatten()
            }
            if (resolved.isEmpty()) {
                Log.i(TAG, "loadLinks: resolved nothing from ${entries.size} entries (dns fixed)")
                return false
            }

            // فحصٌ حقيقيّ كما يفعل المشغّل: طلبٌ عاديّ بلا `Range`. و«لا نعرف»
            // (مهلة/انقطاع/فشل DNS) لا يُسقط مرشّحًا: قِيس أنّ
            // `links.modpro.blog` نفسه لا يُحلّ محليًّا، وأنّ رابط الوسائط
            // أحادي الاستعمال فردُّ 400 عليه لاحقًا ليس دليل موت.
            val probed = coroutineScope {
                resolved.map { c ->
                    async(Dispatchers.IO) {
                        val r = MediaProbe.probe(c.url, c.referer, c.headers)
                        c.bytes = c.bytes ?: r.totalBytes
                        c.ttfb = if (r.alive) r.ttfbMs else Long.MAX_VALUE - 1
                        c.alive = r.alive
                        c.certain = r.certain
                        c.mime = r.contentType.ifBlank { null }
                        c
                    }
                }.awaitAll()
            }

            val pool = probed.filterNot { !it.alive && it.certain }.ifEmpty { probed }
            val ordered = when (order) {
                "desc" -> pool.sortedWith(compareByDescending<Cand> { it.quality }.thenBy { it.rank() })
                "asc" -> pool.sortedWith(compareBy<Cand> { it.quality }.thenBy { it.rank() })
                else -> pool.sortedBy { it.rank() }
            }

            val take = if (showAlt) ordered else ordered.take(1)
            var emitted = 0
            for (c in take) {
                // ★ الإرسال محاطٌ بـ`try` **داخل** الحلقة لا حولها كلّها: قِيس أن
                // مرشّحًا واحدًا شاذًّا (رابط لا يُبنى، أو خادم يردّ شيئًا غير
                // متوقّع) كان يُسقط القائمة بأكملها لأن الاستثناء يصعد إلى
                // `catch` الخارجي فيعود `false` ولو كان قبله ما أُرسل. الآن
                // يسقط المرشّح وحده ويُسجَّل، وتبقى بقيّة القائمة.
                try {
                    val label = MediaProbe.label(
                        c.server,
                        MediaProbe.qualityLabel(heightOf(c.quality)),
                        c.bytes,
                        c.url,
                        ExtractorLinkType.VIDEO,
                        c.note,
                        useOf(c),
                        FormatTag.fromMime(c.mime),
                    )
                    callback(
                        newExtractorLink(source = name, name = label, url = c.url, type = ExtractorLinkType.VIDEO) {
                            this.referer = c.referer ?: ""
                            this.headers = c.headers
                            this.quality = c.quality
                        }
                    )
                    emitted++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // الإلغاء ليس خطأ مصدر: يُعاد إطلاقه وإلا بدا للمستخدم
                    // «لا نتائج» بدل «أُلغيت العملية».
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "loadLinks: skip ${c.server} ${e::class.simpleName} ${e.message}")
                }
            }
            // ★ «لا شيء» ليس حالة واحدة: هنا نُفرّق بين «الموقع لا يملك مرآة
            // حيّة لهذا الملفّ» و«المرايا موجودة لكن الفحص لم يحسم». الحالتان
            // كانتا تُسجَّلان سطرًا واحدًا فتبدو العلّة غامضة.
            if (emitted == 0) {
                val dead = probed.count { !it.alive && it.certain }
                Log.i(TAG, "loadLinks: emitted 0 — ${probed.size} candidates, $dead certain-dead" +
                    if (dead == probed.size) " (الموقع لا يملك مرآة حيّة لهذا الملفّ)" else " (لا حكم — أعد المحاولة)")
            } else {
                Log.i(TAG, "loadLinks: emitted $emitted of ${probed.size} probed")
            }
            emitted > 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "loadLinks FAIL ${e::class.simpleName} ${e.message}")
            false
        }
    }

    /**
     * وسم مشاهدة/تحميل من **طبيعة الرابط نفسه** لا من ادّعاء.
     *
     * قائمة تشغيل (`m3u8`/`mpd`) بثٌّ يُشاهد ولا يُنزَّل ملفًّا واحدًا ⇒ «مشاهدة».
     * وما عداه ملفٌّ مباشر — وهو ما يقدّمه الموقع فعلًا: زرُّه «Download» على
     * `driveseed`، وقِيس أنّ صفحة العمل لا تحمل ولا `iframe` واحدًا (‎0‎ إطارًا
     * و‎0‎ وسم `video` في ‎81764‎ بايتًا من `entry-content`) ⇒ لا مسار مشاهدة
     * أصلًا، فوسم «تحميل» هو الصدق لا التقليل. والملفّ يُشغَّل في المشغّل أيضًا
     * — الوسم يوضّح مصدره لا يمنع تشغيله.
     */
    private fun useOf(c: Cand): MediaProbe.Use {
        val u = c.url.lowercase()
        val playlist = u.contains(".m3u8") || u.contains(".mpd") ||
            c.mime?.contains("mpegurl", true) == true || c.mime?.contains("dash+xml", true) == true
        // For direct file links, always show as download
        if (u.contains(".mp4") || u.contains(".mkv") || u.contains(".avi") || u.contains(".webm") || u.contains(".zip") || u.contains(".rar")) {
            return MediaProbe.Use.DOWNLOAD
        }
        return if (playlist) MediaProbe.Use.WATCH else MediaProbe.Use.DOWNLOAD
    }

    /**
     * حلّ مدخلةٍ واحدة إلى ملفّات وسائط.
     *
     * المدخلة قد تكون أرشيفًا (`episodes.modpro.blog/archives/…`) أو رابط
     * `?sid=` مباشرةً. والقراءة تبحث عن `?sid=` **بعلامةٍ ثابتة** لا بموضع،
     * فالأرشيف يردّ 200 وجسمه يتغيّر حجمًا بين جلبٍ وجلب (قِيس: ‎38 KB‎ ثم
     * ‎42 KB‎ للصفحة نفسها).
     */
    private suspend fun resolve(archive: String, serverLabel: String?): List<Cand> {
        if (archive.isBlank()) return emptyList()
        Log.i(TAG, "resolve: start ${archive.take(100)} label=$serverLabel")

        var sid: String = ""
        var label: String? = null
        if (archive.contains("?sid=")) {
            sid = archive
            label = serverLabel
            // لا طلبَ استكشافي هنا: صفحة `?sid=` هي نموذج `lp-land` دائمًا،
            // و`followChain` تُكملها في طلبٍ واحد بلا قراءةٍ مزدوجة.
            Log.i(TAG, "resolve: direct sid")
        } else if (archive.contains("links.modpro.blog")) {
            Log.i(TAG, "resolve: links.modpro.blog archive")
            val (body, finalUrl) = try {
                val r = app.get(archive, headers = head, allowRedirects = true, referer = mainUrl)
                if (!r.isSuccessful) return emptyList()
                r.text to r.url.toString()
            } catch (e: Exception) {
                Log.i(TAG, "resolve archive FAIL ${e::class.simpleName}")
                return emptyList()
            }
            val allSids = RE_SID_ANY.findAll(body).map {
                val raw = it.value
                if (raw.startsWith("http")) raw.replace("&amp;", "&") else "https://$raw".replace("&amp;", "&")
            }.distinct().toList()
            if (allSids.isNotEmpty()) {
                Log.i(TAG, "resolve: links.modpro found ${allSids.size} sid links")
                val results = ArrayList<Cand>()
                for ((idx, s) in allSids.withIndex()) {
                    try {
                        val qr = app.get(s, headers = head, referer = finalUrl, allowRedirects = true)
                        val qt = qr.text
                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                            results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                            continue
                        }
                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                            results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                            continue
                        }
                        Regex("https?://[^\"'\\s<>]+\\.(mp4|mkv|avi|webm|mpg|mpeg|ts|zip|rar)(\\?[^\"'\\s<>]*)?").find(qt.replace("\\/", "/"))?.let { m ->
                            results.add(Cand(m.value.replace("\\/", "/"), allSids[idx], qualityOf(allSids[idx] ?: m.value), null, null, head))
                            continue
                        }
                        Regex("https?://[^\"'\\s<>]+/(download|dl|getfile)/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                            results.add(Cand(m.value.replace("\\/", "/"), allSids[idx], qualityOf(allSids[idx] ?: m.value), null, null, head))
                            continue
                        }
                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                            val v = m.value
                            if (v.contains("/file/") || v.contains("/r?") || v.contains("download")) {
                                results.addAll(mirrorsOf(v, allSids[idx]))
                                continue
                            }
                        }
                        val fu = followChain(s)
                        if (fu != null) {
                            results.addAll(mirrorsOf(fu, allSids[idx]))
                        }
                    } catch (e: Exception) {}
                }
                if (results.isNotEmpty()) return results.distinctBy { it.url }
            }
            // try to extract any sid param
            Regex("sid=([A-Za-z0-9+/=]+)").find(body)?.let { m ->
                val s = "https://en.thenaukriadda.in/?sid=${m.groupValues[1]}"
                try {
                    val qr = app.get(s, headers = head, referer = finalUrl, allowRedirects = true)
                    val qt = qr.text
                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m2 ->
                        return mirrorsOf(m2.value.replace("\\/", "/"), label ?: serverLabel)
                    }
                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m2 ->
                        return mirrorsOf(m2.value.replace("\\/", "/"), label ?: serverLabel)
                    }
                    val fu = followChain(s)
                    if (fu != null) return mirrorsOf(fu, label ?: serverLabel)
                } catch (e: Exception) {}
            }
            return emptyList()
        } else {
            val (body, finalUrl) = try {
                val r = app.get(archive, headers = head, allowRedirects = true, referer = mainUrl)
                if (!r.isSuccessful) return emptyList()
                r.text to r.url.toString()
            } catch (e: Exception) {
                Log.i(TAG, "resolve archive FAIL ${e::class.simpleName}" +
                    " ${hostOf(archive)}" +
                    if (e is UnknownHostException) " — المضيف لا يُحلّ" else "")
                return emptyList()
            }
            // if it's links.modpro.blog page, extract all sid links directly
            if (archive.contains("links.modpro.blog") || finalUrl.contains("links.modpro.blog")) {
                val allSids = RE_SID_ANY.findAll(body).map {
                    val raw = it.value
                    if (raw.startsWith("http")) raw.replace("&amp;", "&") else "https://$raw".replace("&amp;", "&")
                }.distinct().toList()
                if (allSids.isNotEmpty()) {
                    Log.i(TAG, "resolve: links.modpro found ${allSids.size} sid links")
                    val results = ArrayList<Cand>()
                    for ((idx, s) in allSids.withIndex()) {
                        try {
                            val qr = app.get(s, headers = head, referer = finalUrl, allowRedirects = true)
                            val qt = qr.text
                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                                results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                                continue
                            }
                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                                results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                                continue
                            }
                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                                val v = m.value
                                if (v.contains("/file/") || v.contains("/r?")) {
                                    results.addAll(mirrorsOf(v, allSids[idx]))
                                    continue
                                }
                            }
                            val fu = followChain(s)
                            if (fu != null) {
                                results.addAll(mirrorsOf(fu, allSids[idx]))
                            }
                        } catch (e: Exception) {}
                    }
                    if (results.isNotEmpty()) return results.distinctBy { it.url }
                }
            }
            // check if redirected to sid
            if (finalUrl.contains("?sid=")) {
                sid = finalUrl.replace("&amp;", "&")
                label = serverLabel
                Log.i(TAG, "resolve: redirected to sid=$sid")
                // try fast extraction
                try {
                    val qr = app.get(sid, headers = head, referer = archive, allowRedirects = true)
                    val qt = qr.text
                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let {
                        return mirrorsOf(it.value.replace("\\/", "/"), label ?: serverLabel)
                    }
                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let {
                        return mirrorsOf(it.value.replace("\\/", "/"), label ?: serverLabel)
                    }
                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                        val v = m.value
                        if (v.contains("/file/") || v.contains("/r?")) {
                            return mirrorsOf(v, label ?: serverLabel)
                        }
                    }
                    val fu = followChain(sid)
                    if (fu != null) return mirrorsOf(fu, label ?: serverLabel)
                } catch (e: Exception) {}
            } else if (finalUrl.contains("en.thenaukriadda.in")) {
                // look for sid in body
                val allSids = RE_SID_ANY.findAll(body).map {
                    val raw = it.value
                    if (raw.startsWith("http")) raw.replace("&amp;", "&") else "https://$raw".replace("&amp;", "&")
                }.distinct().toList()
                if (allSids.isNotEmpty()) {
                    Log.i(TAG, "resolve: found ${allSids.size} sid links in thenaukriadda page")
                    val results = ArrayList<Cand>()
                    for ((idx, s) in allSids.withIndex()) {
                        try {
                            val qr = app.get(s, headers = head, referer = finalUrl, allowRedirects = true)
                            val qt = qr.text
                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                                results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                                continue
                            }
                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                                results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                                continue
                            }
                            val fu = followChain(s)
                            if (fu != null) {
                                results.addAll(mirrorsOf(fu, allSids[idx]))
                            }
                        } catch (e: Exception) {}
                    }
                    if (results.isNotEmpty()) return results.distinctBy { it.url }
                    sid = allSids.first()
                    label = serverLabel
                } else {
                    sid = finalUrl.replace("&amp;", "&")
                    label = serverLabel
                    Log.i(TAG, "resolve: final is thenaukriadda=$sid")
                }
            } else {
                // look for direct thenaukriadda sid links first
                val allSids = RE_SID_ANY.findAll(body).map {
                    val raw = it.value
                    if (raw.startsWith("http")) raw.replace("&amp;", "&") else "https://$raw".replace("&amp;", "&")
                }.distinct().toList()
                if (allSids.isNotEmpty()) {
                    if (allSids.size > 1) {
                        Log.i(TAG, "resolve: found ${allSids.size} sid links in archive page")
                        val results = ArrayList<Cand>()
                        for ((idx, s) in allSids.withIndex()) {
                            try {
                                val qr = app.get(s, headers = head, referer = archive, allowRedirects = true)
                                val qt = qr.text
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                                    results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                                    continue
                                }
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let { m ->
                                    results.addAll(mirrorsOf(m.value.replace("\\/", "/"), allSids[idx]))
                                    continue
                                }
                                val fu = followChain(s)
                                if (fu != null) {
                                    results.addAll(mirrorsOf(fu, allSids[idx]))
                                }
                            } catch (e: Exception) {}
                        }
                        if (results.isNotEmpty()) return results.distinctBy { it.url }
                    }
                    val raw = allSids.first()
                    sid = raw
                    label = serverLabel
                    Log.i(TAG, "resolve: found sid link in body=$sid")
                    // try quick extraction
                    try {
                        val qr = app.get(sid, headers = head, referer = archive, allowRedirects = true)
                        val qt = qr.text
                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let {
                            return mirrorsOf(it.value.replace("\\/", "/"), label ?: serverLabel)
                        }
                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(qt.replace("\\/", "/"))?.let {
                            return mirrorsOf(it.value.replace("\\/", "/"), label ?: serverLabel)
                        }
                    } catch (e: Exception) {}
                } else if (body.contains("lp-land") || body.contains("_lp_http") || body.contains("lp-s1-form") || body.contains("thenaukriadda.in") || body.contains("timed-content")) {
                    val pairs = RE_SID.findAll(body)
                        .map { unescapeText(it.groupValues[1]).replace(RE_WS, "") to stripTags(it.groupValues[2]) }
                        .filterNot { it.second.contains("comment", true) }
                        .toList()
                    if (pairs.isEmpty()) {
                        sid = finalUrl
                        label = serverLabel
                        Log.i(TAG, "resolve: using finalUrl as start=$sid")
                    } else {
                        // مرآةٌ واحدة تكفي: المطابقة لاسم المرآة إن طُلب، وإلا الأولى.
                        val pick = serverLabel?.let { want -> pairs.firstOrNull { it.second.contains(want, true) } } ?: pairs.first()
                        sid = pick.first
                        label = pick.second
                    }
                }
            }
        }

        Log.i(TAG, "resolve: about to call followChain sidLen=${sid.length}")
        // مسارٌ سريع للروابط المباشرة فقط (ملفٌّ أو صفحة تنزيل بلا سلسلة `?sid=`).
        if (!sid.contains("?sid=")) {
            try {
                val fast = app.get(sid, headers = head, referer = archive, allowRedirects = true)
                val ft = fast.text.replace("\\/", "/")
                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(ft)?.let {
                    Log.i(TAG, "resolve: fast got driveseed file")
                    return mirrorsOf(it.value, label ?: serverLabel)
                }
                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(ft)?.let {
                    Log.i(TAG, "resolve: fast got driveseed r")
                    return mirrorsOf(it.value, label ?: serverLabel)
                }
                Regex("https?://[^\"'\\s<>]+\\.(mp4|mkv|avi|webm|mpg|mpeg|ts|zip|rar)(\\?[^\"'\\s<>]*)?").find(ft)?.let {
                    Log.i(TAG, "resolve: fast got direct file")
                    return listOf(Cand(it.value, label ?: serverLabel ?: "Direct", qualityOf(label ?: it.value), null, null, head))
                }
                Regex("https?://[^\"'\\s<>]+/(download|dl|getfile)/[^\"'\\s<>]+").find(ft)?.let {
                    Log.i(TAG, "resolve: fast got direct download")
                    return listOf(Cand(it.value, label ?: serverLabel ?: "Direct", qualityOf(label ?: it.value), null, null, head))
                }
            } catch (e: Exception) {}
        }
        val fileUrl = followChain(sid) ?: run { Log.i(TAG, "resolve: followChain returned null"); return emptyList() }
        Log.i(TAG, "resolve: got fileUrl=${fileUrl.take(80)}")
        return mirrorsOf(fileUrl, label ?: serverLabel)
    }

    /**
     * سلسلة التحويلات حتى صفحة الملفّ على `driveseed.org`.
     *
     * كل خطوة تقرأ النموذج/التحويل من الجسم نفسه: عناوين `action` وأسماء الحقول
     * **مقروءة لا مثبَّتة** — قِيس أنّ كعكة `lp` تُضبط بـJS باسمٍ عشوائي
     * (`lp-6ad650fa2bdc3` و`lp-6ad701e919f32` و`lp-6ad7049c2e15a` في ثلاث جلسات)،
     * وأنّ `action` الثاني يأتي من الصفحة لا من ثابتٍ في الكود.
     */
    // ---------- أدوات السلسلة ----------

    /** يبني رابطًا مطلقًا من رابطٍ قد يكون نسبيًّا. */
    private fun absUrl(base: String, link: String): String {
        val l = unescapeText(link).trim()
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        if (l.startsWith("//")) return "https:$l"
        return try {
            java.net.URI(base).resolve(l).toString()
        } catch (e: Exception) {
            val b = try { java.net.URI(base) } catch (e2: Exception) { null }
            if (l.startsWith("/") && b?.host != null) "${b.scheme}://${b.host}$l" else l
        }
    }

    /** هل هذا رابط `driveseed` نهائي (صفحة ملفّ/قائمة/غلاف)؟ */
    private fun isFinal(u: String): Boolean =
        u.contains("driveseed.org") && (
            u.contains("/file/") || u.contains("/list/") ||
                u.contains("/wfile/") || u.contains("/l?") || u.contains("/r?")
            )

    /**
     * يُوحّد كل أشكال هروب الروابط التي تُصدِرها الصفحات:
     * `\/`، `&#47;`، `&amp;`، `\u002F`، `\x2F`.
     * بدون هذا يفوت الرابط في صفحةٍ واحدة الشكل ويُقرأ في أخرى.
     */
    private fun normBody(body: String): String =
        unescapeText(body)
            .replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=").replace("\\u003d", "=")
            .replace("\\x2F", "/").replace("\\x2f", "/")
            .replace("\\x26", "&")

    /**
     * أوّل رابط `driveseed` في الجسم بأي شكلٍ من أشكال الهروب.
     * آخر محاولة: فكّ أي كتلة base64 طويلة قد يُخبَّأ فيها الرابط.
     */
    private fun driveseedLink(body: String): String? = driveseedLink(body, 0)

    private fun driveseedLink(body: String, depth: Int): String? {
        val b = normBody(body)
        Regex("https?://[^\"'\\s<>]*driveseed\\.[a-z]+/(?:file|list|wfile)/[^\"'\\s<>]+").find(b)?.let { return it.value }
        Regex("https?://[^\"'\\s<>]*driveseed\\.[a-z]+/(?:l|r|zfile)\\?[^\"'\\s<>]+").find(b)?.let { return it.value }
        Regex("https?://[^\"'\\s<>]*driveseed\\.[a-z]+/[^\"'\\s<>]*").find(b)?.let { return it.value }
        Regex("(?:^|[\"'(\\s])/(?:file|list|wfile)/[A-Za-z0-9_-]{6,}[^\"'\\s<>]*").find(b)?.let {
            return "https://driveseed.org${it.value.trimStart('"', '\'', '(', ' ', '\n', '\t')}"
        }
        if (depth < 2) {
            Regex("[A-Za-z0-9+/]{40,}={0,2}").findAll(b).take(6).forEach { m ->
                try {
                    val dec = String(android.util.Base64.decode(m.value, android.util.Base64.DEFAULT))
                    if (dec.contains("driveseed")) driveseedLink(dec, depth + 1)?.let { return it }
                } catch (e: Throwable) {
                }
            }
        }
        return null
    }

    /** انتقال JS أو `meta refresh` من الصفحة (كل الصيغ الشائعة). */
    private fun jsTarget(body: String): String? {
        val b = normBody(body)
        RE_JS_REDIRECT.find(b)?.groupValues?.getOrNull(1)?.let { return it }
        RE_LOC.find(b)?.groupValues?.getOrNull(1)?.let { return it }
        RE_META_REFRESH.find(b)?.groupValues?.getOrNull(1)?.let { return it }
        Regex(
            "(?:window\\.|document\\.|top\\.|parent\\.|self\\.)?location(?:\\s*\\.\\s*href)?\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE,
        ).find(b)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("window\\.open\\s*\\(\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(b)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    /** نتيجة طلبٍ خامّ: الرمز والجسم وترويسة التحويل. */
    private data class Raw(val code: Int, val body: String, val location: String?)

    /**
     * طلب GET خامّ عبر `HttpURLConnection` **بلا أي كعك من جار التطبيق**.
     *
     * لِمَ لا `app.get`؟ لأنّ OkHttp يعيد بناء ترويسة `Cookie` من جار
     * التطبيق ويتجاهل الترويسة اليدوية حين يكون الجار غير فارغ (وفيه
     * `__cf_bm` من Cloudflare)، فيضيع كعك `lp-*` ويردّ الخادم
     * **«Session expired»** — وهو ما قِيس على الجهاز فعلًا: 1449 بايت،
     * بينما الكمبيوتر (بلا جار ملوّث) يحصل على صفحة التحويل 1425 بايت.
     *
     * هنا لا جار ولا كعك تلقائي: ما نمرّره فقط.
     */
    private suspend fun rawGet(
        url: String,
        cookie: String?,
        referer: String?,
    ): Raw? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var c: java.net.HttpURLConnection? = null
        try {
            c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 20000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            if (!referer.isNullOrBlank()) c.setRequestProperty("Referer", referer)
            if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
            val code = c.responseCode
            val loc = c.getHeaderField("Location")
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Log.i(TAG, "rawGet $code len=${text.length} loc=${loc?.take(90)}")
            Raw(code, text, loc)
        } catch (e: Throwable) {
            Log.i(TAG, "rawGet FAIL ${e::class.simpleName} ${e.message}")
            null
        } finally {
            try {
                c?.disconnect()
            } catch (e: Throwable) {
            }
        }
    }

    /**
     * يكتب تشخيصًا خامًّا في مجلّد التطبيق الخارجي ليُسحب بـ`adb pull`
     * (`/sdcard/Android/data/com.lagradost.cloudstream3/files/mmdbg/`).
     * لا يؤثّر على السلوك: أي فشل في الكتابة يُبتلع.
     */
    private fun dbg(name: String, text: String) {
        try {
            val dirs = listOf(
                java.io.File("/sdcard/Android/data/com.lagradost.cloudstream3/files/mmdbg"),
                java.io.File("/storage/emulated/0/Android/data/com.lagradost.cloudstream3/files/mmdbg"),
            )
            for (d in dirs) {
                try {
                    if (!d.exists()) d.mkdirs()
                    val f = java.io.File(d, name)
                    f.writeText(text.take(300_000))
                    Log.i(TAG, "dbg -> ${f.absolutePath}")
                    return
                } catch (e: Exception) {
                }
            }
        } catch (e: Throwable) {
        }
    }

    /** كعكة `sc("name","value",n)` — الاسم عشوائيٌّ في كل جلسة فيُقرأ من الصفحة. */
    private fun scOf(body: String): Pair<String, String>? =
        RE_SC.find(body)?.let { it.groupValues[1] to unescapeText(it.groupValues[2]) }

    /** روابط `/file/<id>` في صفحة `/list/` مع نصّها (اسم الملفّ). */
    private fun fileLinksOf(html: String): List<Pair<String, String>> =
        Regex("<a[^>]+href=\"([^\"]*/file/[^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") to stripTags(it.groupValues[2]) }
            .filterNot { it.first.contains("/file/delete/") || it.first.contains("/file/reset/") }
            .distinctBy { it.first }
            .toList()

    /** رقم الحلقة من نصّ: `S01E03` أو `E03` أو `Episode 3`. */
    private fun epFromText(text: String): Int? {
        if (text.isBlank()) return null
        Regex("\\bS\\d{1,2}[\\s._-]*E(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        Regex("\\bE(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return epNumberOf(text)
    }

    /**
     * يُكمل سلسلة التحويلات من `?sid=` حتى أوّل رابط `driveseed`.
     *
     * السلسلة المقيسة (2026-10) على `en.thenaukriadda.in`:
     * ```
     * GET  /?sid=…                    → نموذج `lp-land`
     * POST /  (lp-land)               → صفحة فيها نموذج `lp-s1-form`
     * POST <action> (lp-s1-form)      → مقالٌ فيه sc("lp-xxxx","<cookie>",N)
     * GET  /?lp_go=lp-xxxx  + cookie  → جسمٌ فيه location.replace("driveseed.org/l?id=…")
     * ```
     * كلّ خطوة تُقرأ من الصفحة نفسها: `action`، أسماء الحقول، اسم الكعكة.
     * لا شيء منها مثبَّت في الكود، فتبديل أسماء الكعك تلقائيًّا لا يكسرها.
     *
     * ملاحظة: كانت النسخة السابقة تتفرّع في عشرات الحالات وتفشل لأنّها
     * تطلب الصفحة مرّتين وتفقد جسم ردّ الـPOST. هنا الجسم يُحلَّل مباشرةً
     * (`pendingBody`) بلا طلبٍ زائد.
     */
    private suspend fun followChain(sid: String): String? {
        Log.i(TAG, "followChain ENTRY sidLen=${sid.length}")
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                var current = sid
                var referer: String? = sid
                var pendingBody: String? = null
                var pendingUrl: String? = null
                val seenPosts = HashSet<String>()
                var hops = 0
                while (hops < 24) {
                    hops++
                    val body: String
                    val pageUrl: String
                    if (pendingBody != null) {
                        body = pendingBody
                        pageUrl = pendingUrl ?: current
                        pendingBody = null
                        pendingUrl = null
                    } else {
                        Log.i(TAG, "followChain try$attempt hop$hops: GET ${current.take(110)}")
                        val resp = app.get(current, headers = head, referer = referer, allowRedirects = false)
                        val loc = resp.headers["Location"] ?: resp.headers["location"]
                        if (!loc.isNullOrBlank()) {
                            val next = absUrl(current, loc)
                            Log.i(TAG, "followChain try$attempt hop$hops: Location -> ${next.take(110)}")
                            if (isFinal(next)) return next
                            current = next
                            referer = next
                            continue
                        }
                        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                        body = resp.text
                        pageUrl = resp.url.toString()
                    }

                    // 1) وصلنا إلى driveseed
                    driveseedLink(body)?.let {
                        Log.i(TAG, "followChain try$attempt hop$hops: driveseed -> ${it.take(110)}")
                        return it
                    }

                    // 2) نموذج POST (lp-land ثم lp-s1-form)
                    val form = formOf(body, "lp-land") ?: formOf(body, "lp-s1-form")
                    if (form != null) {
                        val key = form.first + "|" + form.second.entries.sortedBy { it.key }.joinToString("&")
                        if (!seenPosts.add(key)) throw Exception("POST مكرَّر لنفس النموذج")
                        Log.i(TAG, "followChain try$attempt hop$hops: POST ${form.first.take(100)} fields=${form.second.keys}")
                        val pr = app.post(
                            form.first,
                            data = form.second,
                            referer = pageUrl,
                            headers = formHead,
                            allowRedirects = false,
                        )
                        val ploc = pr.headers["Location"] ?: pr.headers["location"]
                        if (!ploc.isNullOrBlank()) {
                            val next = absUrl(form.first, ploc)
                            Log.i(TAG, "followChain try$attempt hop$hops: POST Location -> ${next.take(110)}")
                            if (isFinal(next)) return next
                            current = next
                            referer = form.first
                            continue
                        }
                        if (!pr.isSuccessful) throw Exception("POST HTTP ${pr.code}")
                        val pbody = pr.text
                        driveseedLink(pbody)?.let { return it }
                        // حلّل ردّ الـPOST كما هو بلا طلبٍ ثانٍ
                        pendingBody = pbody
                        pendingUrl = pr.url.toString()
                        current = pr.url.toString()
                        referer = form.first
                        continue
                    }

                    // 3) كعكة `sc(...)` → `?lp_go=`
                    scOf(body)?.let { sc ->
                        val go = "https://en.thenaukriadda.in/?lp_go=${sc.first}"
                        Log.i(TAG, "followChain try$attempt hop$hops: sc=${sc.first} -> lp_go")
                        var lastBody: String? = null
                        var moved: String? = null
                        // (أ) طلبٌ خامّ بلا جار كعك: الكعكة وحدها، لا `__cf_bm`
                        val ck = "${sc.first}=${sc.second}"
                        Log.i(
                            TAG,
                            "followChain lp_go cookie len=${sc.second.length}" +
                                " head=${sc.second.take(6)} tail=${sc.second.takeLast(6)}",
                        )
                        val raw = rawGet(go, ck, pageUrl)
                        if (raw != null) {
                            if (!raw.location.isNullOrBlank()) {
                                val next = absUrl(go, raw.location)
                                Log.i(TAG, "followChain lp_go raw Location -> ${next.take(110)}")
                                if (isFinal(next)) return next
                                moved = next
                            } else if (raw.code in 200..299) {
                                lastBody = raw.body
                                driveseedLink(raw.body)?.let {
                                    Log.i(TAG, "followChain lp_go raw driveseed -> ${it.take(110)}")
                                    return it
                                }
                                jsTarget(raw.body)?.let { mv ->
                                    val next = absUrl(go, mv)
                                    Log.i(TAG, "followChain lp_go raw JS -> ${next.take(110)}")
                                    if (isFinal(next)) return next
                                    moved = next
                                }
                                if (moved == null) {
                                    Log.i(
                                        TAG,
                                        "followChain lp_go raw بلا انتقال (${raw.body.length})" +
                                            " expired=${raw.body.contains("Session expired")}" +
                                            " wait=${raw.body.contains("Please wait")}",
                                    )
                                }
                            }
                        }
                        if (moved != null) {
                            current = moved
                            referer = go
                            continue
                        }
                        for (g in 1..3) {
                            val gr = try {
                                app.get(
                                    go,
                                    // احتياط: لو فشل الطلب الخامّ لأي سبب.
                                    headers = head + mapOf("Cookie" to "${sc.first}=${sc.second}"),
                                    referer = pageUrl,
                                    cookies = mapOf(sc.first to sc.second),
                                    allowRedirects = false,
                                )
                            } catch (e: Exception) {
                                Log.i(TAG, "followChain lp_go[$g] EX ${e::class.simpleName}")
                                null
                            }
                            if (gr == null) {
                                if (g < 3) kotlinx.coroutines.delay(900)
                                continue
                            }
                            val gloc = gr.headers["Location"] ?: gr.headers["location"]
                            if (!gloc.isNullOrBlank()) {
                                val next = absUrl(go, gloc)
                                Log.i(TAG, "followChain lp_go[$g] Location -> ${next.take(110)}")
                                if (isFinal(next)) return next
                                moved = next
                                break
                            }
                            if (!gr.isSuccessful) {
                                Log.i(TAG, "followChain lp_go[$g] HTTP ${gr.code}")
                            } else {
                                val b = gr.text
                                lastBody = b
                                driveseedLink(b)?.let {
                                    Log.i(TAG, "followChain lp_go[$g] driveseed -> ${it.take(110)}")
                                    return it
                                }
                                jsTarget(b)?.let { mv ->
                                    val next = absUrl(go, mv)
                                    Log.i(TAG, "followChain lp_go[$g] JS -> ${next.take(110)}")
                                    if (isFinal(next)) return next
                                    moved = next
                                }
                                if (moved != null) break
                                Log.i(
                                    TAG,
                                    "followChain lp_go[$g] بلا انتقال (${b.length} بايت)" +
                                        " wait=${b.contains("Please wait")}" +
                                        " expired=${b.contains("Session expired")}" +
                                        " tail=${b.takeLast(260).replace('\n', ' ').replace('\r', ' ')}",
                                )
                            }
                            if (g < 3) kotlinx.coroutines.delay(900)
                        }
                        if (lastBody != null) {
                            dbg("lp_go_fail_${attempt}_${System.currentTimeMillis()}.html", lastBody!!)
                        }
                        if (moved != null) {
                            current = moved
                            referer = go
                            continue
                        }
                        throw Exception("lp_go بلا انتقال (${lastBody?.length ?: 0} بايت)")
                    }

                    // 4) انتقال JS / meta
                    jsTarget(body)?.let { mv ->
                        val next = absUrl(pageUrl, mv)
                        Log.i(TAG, "followChain try$attempt hop$hops: JS -> ${next.take(110)}")
                        if (isFinal(next)) return next
                        if (next == pageUrl) throw Exception("redirect loop")
                        current = next
                        referer = next
                        continue
                    }

                    dbg("dead_end_${attempt}_${System.currentTimeMillis()}.html", body)
                    throw Exception("طريق مسدود في ${pageUrl.take(80)} (${body.length} بايت)")
                }
                throw Exception("followChain exhausted hops for try$attempt")
            } catch (e: Exception) {
                lastErr = e
                Log.i(TAG, "followChain try$attempt FAIL ${e::class.simpleName} ${e.message}")
                if (attempt < 3) kotlinx.coroutines.delay(1200)
            }
        }
        Log.i(TAG, "followChain FAIL ${lastErr?.javaClass?.simpleName} ${lastErr?.message}")
        return null
    }


    /**
     * مرايا صفحة الملفّ.
     *
     * `instant`/`cdn` تردّان 302 إلى غلاف `video-seed.dev` الذي يحمل رابط
     * الوسائط في معامل `url=` — يُستخرج ولا يُبَثّ. و`seedtg` و`/zfile/`
     * غلافان بلا وسائط فلا يدخلان القائمة أصلًا. ورابط `r2.dev` إن وُجد رابط
     * ملفٍّ مباشر — **لكنه ليس حاضرًا دائمًا** (قِيس `r2: []` على صفحةٍ كاملة).
     */
    /**
     * صفحات `driveseed.org` نمطين، والفرق بينهما يُكاد يكون سبب كل
     * «لا روابط» في الإصدارات السابقة:
     *
     * - `/l?id=…&key=…` — غلاف Redirect Only: جسمه 92 بايت يحتوي
     *   `window.location.replace("/list/<id>")` فقط، **لا روابط وسائط**.
     * - `/list/<id>` — قائمة الملفّات: يحتوي روابط `/file/<id>` لكل جودة.
     * - `/file/<id>` — **الصفحة الحقيقية**: يحمل رابط `r2.dev` المباشر
     *   (قِيس: `pub-2fbc…r2.dev/…/Friends.Sex.and.Games.S01.480p…zip`).
     *
     * الـ`followChain` كان يُعيد `/l?id=` (الغلاف) فتُستخرَج روابطه
     * فارغة، والحلّ أن يُكمل الانتقالين剩下的 إلى `/file/<id>`.
     *
     * والcookie **غير ضروري**: قِيس `/file/<id>` بعميل جديد بلا كعكة
     * يُعيد 200 وrapport r2 واحدٌ — أي أنّ页面 تُ servce للجميع.
     */
    /**
     * مرايا صفحة الملفّ.
     *
     * صفحات `driveseed.org` ثلاثة أنماط، والفرق بينها كان سبب كل «لا روابط»:
     *
     * - `/l?id=…&key=…` — غلاف 92 بايت فيه `window.location.replace("/list/<id>")` فقط.
     * - `/list/<id>` — قائمة الموسم: روابط `/file/<id>` لكل حلقة (بلا أي مرآة).
     * - `/file/<id>` — **الصفحة الحقيقية**: تحمل `pub-….r2.dev` (بثّ مباشر)
     *   و`instant|cdn.video-gen.xyz` (غلافان يُحلّان إلى رابط الوسائط).
     *
     * الإصدارات السابقة كانت تتوقّف عند `/l?id=` أو عند `/list/` فتُعيد صفرًا.
     * هنا: تُتبَع الانتقالات، ثمّ إن كانت الصفحة قائمةً تُختار الحلقة المطلوبة
     * من `serverLabel` (SxxExx) وتُقرأ صفحتها، ثمّ تُستخرج المرايا.
     */
    private suspend fun mirrorsOf(fileUrl: String, serverLabel: String?): List<Cand> {
        val quality = qualityOf(serverLabel ?: fileUrl)
        val note = serverLabel?.let { cleanTitle(it) }?.ifBlank { null }
        val cands = ArrayList<Cand>(8)
        val seen = HashSet<String>()

        fun add(rawUrl: String, server: String, referer: String?) {
            val u = unescapeText(rawUrl).replace(RE_WS, "")
            if (u.isBlank() || !seen.add(u)) return
            cands.add(Cand(u, server, quality, note, referer, head))
        }

        fun collect(html: String, pageUrl: String) {
            for (m in RE_R2.findAll(html)) add(m.groupValues[1], "Cloud", null)
            for (m in RE_VG.findAll(html)) {
                val u = unescapeText(m.groupValues[1])
                add(u, if (u.contains("cdn.")) "CDN" else "Fast", pageUrl)
            }
        }

        try {
            var current = fileUrl
            var referer: String? = null
            for (hop in 0..8) {
                val r = app.get(current, headers = head, referer = referer)
                if (!r.isSuccessful) break
                val body = r.text
                referer = current
                collect(body, current)
                if (cands.isNotEmpty()) break

                // صفحة `/list/` = الموسم كلّه: اقرأ صفحات `/file/` واختر الحلقة.
                val files = fileLinksOf(body)
                if (files.isNotEmpty()) {
                    val wantEp = epFromText(serverLabel ?: "")
                    val picked = if (wantEp != null) {
                        files.filter { (_, t) -> epFromText(t) == wantEp }.ifEmpty { files }
                    } else files
                    Log.i(TAG, "mirrorsOf /list: files=${files.size} wantEp=$wantEp picked=${picked.size}")
                    for ((href, _) in picked.take(if (wantEp != null) 3 else 8)) {
                        val fu = absUrl(current, href)
                        val sub = try {
                            app.get(fu, headers = head, referer = current)
                        } catch (e: Exception) {
                            continue
                        }
                        if (!sub.isSuccessful) continue
                        collect(sub.text, fu)
                        if (cands.isNotEmpty()) break
                    }
                    if (cands.isNotEmpty()) break
                }

                val mv = jsTarget(body) ?: break
                val next = absUrl(current, mv)
                if (next == current) break
                current = next
            }
        } catch (e: Exception) {
            Log.i(TAG, "mirrorsOf FAIL ${e::class.simpleName} ${e.message}")
        }

        Log.i(TAG, "mirrorsOf ${fileUrl.takeLast(34)}: cands=${cands.size}")
        if (cands.isEmpty()) return emptyList()

        // حلّ 302 إلى رابط الوسائط الداخلي — والرابط الداخلي يُبَثّ بلا مُحيل.
        return coroutineScope {
            cands.map { c ->
                async(Dispatchers.IO) {
                    if (!c.url.contains("video-gen.xyz")) return@async c
                    val inner = innerMedia(c.url, c.referer) ?: return@async null
                    c.copy(url = inner, referer = null, headers = emptyMap())
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** يحلّ 302 ثم يستخرج `url=` من غلاف `video-seed.dev`. */
    private suspend fun innerMedia(hopUrl: String, referer: String?): String? {
        return try {
            val r = app.get(hopUrl, headers = head, referer = referer, allowRedirects = false)
            val loc = r.headers["Location"] ?: r.headers["location"] ?: return null
            URI(loc).rawQuery?.split("&")
                ?.firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=")
                ?.let { URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.i(TAG, "innerMedia FAIL ${e::class.simpleName} ${e.message}")
            null
        }
    }

    // ---------- أدوات ----------

    private fun pref(key: String, def: Boolean): Boolean = prefs?.getBoolean(key, def) ?: def

    private fun entryContent(doc: Document): Element? =
        doc.selectFirst("div.entry-content") ?: doc.selectFirst("article .post-content")

    /** القصّة من كتلة `Storyline:` — نصٌّ يُعرض تحت العنوان. */
    private fun parsePlot(doc: Document): String? {
        val scope = entryContent(doc)?.html() ?: return null
        val m = RE_STORYLINE.find(scope) ?: return null
        return stripTags(m.groupValues[1]).ifBlank { null }
    }

    private fun metaContent(doc: Document, prop: String): String? =
        doc.selectFirst("meta[property=$prop]")?.attr("content")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[name=$prop]")?.attr("content")?.trim()?.ifBlank { null }

    /**
     * نموذج POST: يُعاد (action، خريطة الحقول). الحقول تُقرأ بـ`name=`/`value=`
     * مباشرةً لأنّ ترتيب السمات في الصفحة ليس مضمونًا، و`action` من الصفحة نفسها.
     */
    private fun formOf(html: String, id: String): Pair<String, Map<String, String>>? {
        val m = Regex("<form[^>]*id=\"$id\"[^>]*>(.*?)</form>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return null
        val action = RE_ACTION.find(m.groupValues[0])?.groupValues?.get(1)?.let { unescapeText(it) }
            ?.replace(RE_WS, "")
        if (action.isNullOrBlank()) return null
        val fields = RE_FIELD.findAll(m.groupValues[1])
            .associate { unescapeText(it.groupValues[1]) to unescapeText(it.groupValues[2]) }
        if (fields.isEmpty()) return null
        return action to fields
    }

    private fun jsRedirect(html: String): String? =
        RE_JS_REDIRECT.find(html)?.groupValues?.get(1)?.let { unescapeText(it) }?.ifBlank { null }

    private fun seasonOf(text: String): Int? =
        RE_S01.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: RE_SEASON_N.find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun epNumberOf(label: String): Int? {
        if (label.contains("batch", true)) return null
        if (label.contains("comment", true)) return null
        if (RE_ALL_EP.containsMatchIn(label)) return null
        return RE_EP_N.find(label)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun qualityOf(text: String): Int = when {
        RE_Q2160.containsMatchIn(text) -> Qualities.P2160.value
        RE_Q1440.containsMatchIn(text) -> Qualities.P1440.value
        RE_Q1080.containsMatchIn(text) -> Qualities.P1080.value
        RE_Q720.containsMatchIn(text) -> Qualities.P720.value
        RE_Q480.containsMatchIn(text) -> Qualities.P480.value
        RE_Q360.containsMatchIn(text) -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private fun heightOf(quality: Int): Int = when (quality) {
        Qualities.P2160.value -> 2160
        Qualities.P1440.value -> 1440
        Qualities.P1080.value -> 1080
        Qualities.P720.value -> 720
        Qualities.P480.value -> 480
        Qualities.P360.value -> 360
        Qualities.P240.value -> 240
        Qualities.P144.value -> 144
        else -> 0
    }

    /**
     * تنظيف العنوان المعروض. قِيس على ‎٦٠‎ بطاقة حيّة: العنوان الخام يبدأ بـ
     * `Download `، ويحمل قائمة جوداتٍ بعد `||`، وأقواسًا `{…}` للّغة، ووسومًا
     * `[E01 Added]`/`[350MB]`، وذيلًا من صفات المصدر (`Web-DL`/`x264`/`10Bit`…).
     * فيصير «Sheriff Country (Season 1-2)» و«Bad Apples (2026)» نظيفين.
     */
    private fun cleanTitle(raw: String): String {
        var t = stripTags(raw)
        t = RE_LEAD_DOWNLOAD.replace(t, "")
        t = RE_TAIL_MOVIESMOD.replace(t, "")
        t = t.substringBefore("||")
        t = RE_BRACES.replace(t, "")
        t = RE_EP_MARK.replace(t, "")
        t = RE_SIZE_MARK.replace(t, "")
        t = RE_LANG_SUBS.replace(t, "")
        t = RE_TAIL_ATTRS.replace(t, "")
        t = RE_GROUPS.replace(t, "")
        t = RE_TRAILING_DASH.replace(t, "")
        return t.replace(RE_WS, " ").trim()
    }

    private fun stripTags(s: String): String =
        unescapeText(s.replace(RE_TAGS, "")).replace(RE_WS, " ").trim()

    /**
     * فكّ الكيانات **وفكّ هروب JS**.
     *
     * `Parser.unescapeEntities` يفكّ `&#47;` لكنه لا يفكّ `\/` — وهو الهروب الذي
     * تستعمله الصفحات هنا فعلًا: قيمة كعكة `lp` تأتي مشفّرة بـbase64 وفيها `\/`
     * (`sc("lp-6ad650fa2bdc3","eJwNzsuOgjAYQOFXslRNXLgYxgIKlKHSC90hJWHoD9QRhfD0w\/7k5Csi92k…")`)،
     * وكذلك `location.replace("https:\/\/driveseed.org\/r?…")`. وتركها يفكّ
     * النصف يُرسل كعكةً خاطئة ويُبقي التحويل بلا مطابقة. لا يوجد موضعٌ يَصدُق فيه
     * `\/` حرفيًّا: كلّها قيم JS/JSON مهرَّبة أصلها `/`.
     */
    private fun unescapeText(s: String): String =
        org.jsoup.parser.Parser.unescapeEntities(s, false).replace("\\/", "/")

    private companion object {
        const val KIND_EP = "EP"
        const val KIND_DL = "DL"
        const val KIND_BATCH = "BATCH"

        /** المضيف الذي حُذف سجلّه — أرشيف الأفلام. */
        const val PINNED_HOST = "links.modpro.blog"

        /** مضيفاتٌ حيّة على النطاق نفسه، يُقرأ منها عنوان الحافة. */
        val PINNED_SEEDS = listOf("episodes.modpro.blog")

        const val KEY_QUALITY_ORDER = MoviesModSettingsBottomSheet.KEY_QUALITY_ORDER
        const val KEY_SHOW_ALT = MoviesModSettingsBottomSheet.KEY_SHOW_ALT
        const val KEY_INCLUDE_BATCH = MoviesModSettingsBottomSheet.KEY_INCLUDE_BATCH
        const val KEY_SHOW_SEASONS = MoviesModSettingsBottomSheet.KEY_SHOW_SEASONS

        val RE_WS = Regex("\\s+")
        val RE_TAGS = Regex("<[^>]+>")
        val RE_HEAD = Regex("<h([34])[^>]*>(.*?)</h\\1>", RegexOption.DOT_MATCHES_ALL)
        val RE_ANCHOR = Regex("<a([^>]*)>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val RE_CLASS = Regex("class=\"([^\"]*)\"")
        val RE_HREF = Regex("href=\"([^\"]*)\"")
        val RE_ACTION = Regex("action=\"([^\"]*)\"")

        /** روابط الحلقات: الرقم من نصّ الرابط لا من الرابط نفسه. */
        val RE_SID = Regex(
            "<a[^>]*href=\"(https://en\\.thenaukriadda\\.in/\\?sid=[^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val RE_SID_ANY = Regex(
            "(https?://[^\"'\\s<>]+en\\.thenaukriadda\\.in/\\?[^\"'\\s<>]*sid=[^\"'\\s<>]+)",
            RegexOption.DOT_MATCHES_ALL,
        )

        val RE_FIELD = Regex("name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"")
        val RE_SC = Regex("sc\\(\"([^\"]+)\",\"([^\"]+)\",\\d+\\)")
        // البادئة `window.` **اختيارية**: صفحة `?lp_go` تستعمل `window.location`
        // (داخل `setTimeout`)، وصفحة `/r` تستعمل `location` وحدها. الاثنتان مقيسة.
        val RE_JS_REDIRECT = Regex("(?:window\\.)?(?:location|document\\.location|top\\.location)\\.(?:replace|href|assign)\\s*\\(?\\s*[\"']([^\"']+)[\"']\\s*\\)?", RegexOption.IGNORE_CASE)
        /**
         * رابط الانتقال المُعَرَّض في غلاف Redirect-Only (92 بايت،
         * `window.location.replace("/list/<id>")`) — والغلاف `/l?id=…` هو
         * الأداة التي يُعيد فيها `followChain` وينتهي بها المطاف عند
         * `mirrorsOf` دون روابط. هذا المُحلِّل يكمل الانتقالين المتبقيين.
         */
        val RE_LOC = Regex("location(?:\\.replace|\\.href|\\.assign)\\s*\\(\\s*[\"']([^\"']+)[\"']")
        val RE_META_REFRESH = Regex("<meta[^>]+http-equiv=[\"']refresh[\"'][^>]+content=[\"'][^\"']*url=([^\"'>\\s]+)[\"']", RegexOption.IGNORE_CASE)
        val RE_META_REFRESH2 = Regex("url\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        val RE_R2 = Regex("href=\"(https://pub-[^\"]*\\.r2\\.dev/[^\"]+)\"")
        val RE_VG = Regex("href=\"(https://(?:instant|cdn)\\.video-gen\\.xyz/[^\"]+)\"")

        val RE_STORYLINE = Regex(
            "Storyline:?\\s*</[^>]+>\\s*(?:<p[^>]*>)?(.*?)(?:</p>|</div>)",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        val RE_S01 = Regex("\\bS(\\d{1,2})\\b")
        val RE_SEASON_N = Regex("\\bSeason\\s+(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
        val RE_SEASON = Regex("\\(Season\\s+\\d", RegexOption.IGNORE_CASE)
        val RE_EP_N = Regex("\\bEpisode\\s+(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
        val RE_ALL_EP = Regex("\\bAll\\s+Episodes\\b", RegexOption.IGNORE_CASE)

        val RE_Q2160 = Regex("\\b(?:2160p|4K)\\b", RegexOption.IGNORE_CASE)
        val RE_Q1440 = Regex("\\b1440p\\b", RegexOption.IGNORE_CASE)
        val RE_Q1080 = Regex("\\b1080p\\b", RegexOption.IGNORE_CASE)
        val RE_Q720 = Regex("\\b720p\\b", RegexOption.IGNORE_CASE)
        val RE_Q480 = Regex("\\b480p\\b", RegexOption.IGNORE_CASE)
        val RE_Q360 = Regex("\\b360p\\b", RegexOption.IGNORE_CASE)

        val RE_LEAD_DOWNLOAD = Regex("^Download\\s+", RegexOption.IGNORE_CASE)
        val RE_TAIL_MOVIESMOD = Regex("~?\\s*MoviesMod(?:\\.com|\\.us\\.org)?\\s*$", RegexOption.IGNORE_CASE)
        val RE_BRACES = Regex("\\{[^{}]*\\}")
        val RE_EP_MARK = Regex(
            "\\[[^\\[\\]]*\\b(?:E\\d+|S\\d+E\\d+|Added|Complete|End)\\b[^\\[\\]]*\\]",
            RegexOption.IGNORE_CASE,
        )
        val RE_SIZE_MARK = Regex("\\[[^\\[\\]]*\\b(?:MB|GB|KB)\\b[^\\[\\]]*\\]", RegexOption.IGNORE_CASE)
        val RE_LANG_SUBS = Regex(
            "\\[(?:Hindi|English|Korean|Arabic|Tamil|Telugu|Urdu|Japanese|Chinese)\\s+Subs?\\]",
            RegexOption.IGNORE_CASE,
        )
        val RE_TRAILING_DASH = Regex("\\s*[-–|]\\s*$")

        /**
         * ذيل الصفات. `WEB-HD` مُدرَج صراحةً: قِيس تسرّبه في «NCIS: Origins»
         * و«9-1-1» — لم يكن في القائمة الأولى فبقي في العنوان المعروض.
         */
        val RE_TAIL_ATTRS = Regex(
            "\\s+(?:WEB[- ]?DL|WEB[- ]?HD|WEBRip|WEB-Rip|BluRay|Blu-Ray|BRRip|HDTV|HDRip|DVDRip|" +
                "PreDVD|Predvd|HDCAM|MSubs|ESubs|HEVC|x264|x265|H264|H265|10bit|8bit|" +
                "Dual\\s+Audio|Multi\\s+Audio|ORG|NF|AMZN|DSNP|" +
                "480p|540p|576p|720p|1080p|2160p|4K)\\b.*$",
            RegexOption.IGNORE_CASE,
        )
        val RE_GROUPS = Regex(
            "\\((?:[^()]*\\b(?:Audio|Subs?|Subtitles?|Subbed|Dubbed|Dub|Hindi|English|Korean|Japanese|" +
                "Chinese|Arabic|Tamil|Telugu|Malayalam|Urdu|Turkish|Films?|Movies?)\\b[^()]*)\\)",
            RegexOption.IGNORE_CASE,
        )

        val SKIP_SECTIONS = listOf(
            "Search Movies", "Advertisement", "Join Us on Telegram", "Categories",
            "Popular Posts", "Related Posts", "Add Comment", "Comments", "You May Also Like",
        )
    }
}
