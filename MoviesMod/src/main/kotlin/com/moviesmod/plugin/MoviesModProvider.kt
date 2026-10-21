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

    override var mainUrl = "https://moviesmod.uk.com"
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
            eps.add(newEpisode(payload.first) {
                this.name = if (showSeasons) "الموسم $season · الحلقة $num" else "الحلقة $num"
                this.season = season
                this.episode = num
            })
        }
        for (b in batchRows) {
            val season = seasonOf(b.heading) ?: 1
            eps.add(newEpisode(b.href) {
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
                        resolve(link, heading)
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
            Log.i(TAG, "resolve: direct sid=$sid")
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
        // Try fast path first
        try {
            val fast = app.get(sid, headers = head, referer = archive, allowRedirects = true)
            val ft = fast.text
            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(ft.replace("\\/", "/"))?.let {
                Log.i(TAG, "resolve: fast got driveseed file")
                return mirrorsOf(it.value.replace("\\/", "/"), label ?: serverLabel)
            }
            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(ft.replace("\\/", "/"))?.let {
                Log.i(TAG, "resolve: fast got driveseed r")
                return mirrorsOf(it.value.replace("\\/", "/"), label ?: serverLabel)
            }
        } catch (e: Exception) {}
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
    private suspend fun followChain(sid: String): String? {
        Log.i(TAG, "followChain ENTRY sidLen=${sid.length}")
        var lastErr: Exception? = null
        for (i in 1..8) {
            try {
                var current = sid
                var referer: String? = sid
                var hops = 0
                while (hops < 20) {
                    hops++
                    Log.i(TAG, "followChain try$i hop$hops: GET $current")
                    val resp = app.get(current, headers = head, referer = referer, allowRedirects = false)
                    val loc = resp.headers["Location"] ?: resp.headers["location"]
                    if (!loc.isNullOrBlank()) {
                        val nextUrl = if (loc.startsWith("http")) loc else {
                            val base = URI(current)
                            "${base.scheme}://${base.host}${if (base.port != -1 && base.port != 80 && base.port != 443) ":${base.port}" else ""}$loc"
                        }
                        Log.i(TAG, "followChain try$i hop$hops: Location -> $nextUrl")
                        if (nextUrl.contains("driveseed.org") && (nextUrl.contains("/file/") || nextUrl.contains("/r?") || nextUrl.contains("download"))) {
                            return if (nextUrl.startsWith("/")) "https://driveseed.org$nextUrl" else nextUrl
                        }
                        if (nextUrl.contains("/file/")) {
                            return if (nextUrl.startsWith("/")) "https://driveseed.org$nextUrl" else nextUrl
                        }
                        if (nextUrl.contains("thenaukriadda.in") || nextUrl.contains("modpro.blog") || nextUrl.contains("en.")) {
                            current = nextUrl
                            referer = current
                            continue
                        }
                        // other domain, try to follow
                        current = nextUrl
                        referer = current
                        continue
                    }
                    val body = resp.text
                    Log.i(TAG, "followChain try$i hop$hops: bodyLen=${body.length}")
                    if (body.length < 2000) {
                        body.chunked(600).forEachIndexed { idx, chunk -> if (idx < 10) Log.i(TAG, "followChain try$i hop$hops B[$idx]=$chunk") }
                    } else {
                        Log.i(TAG, "followChain try$i hop$hops B[0]=${body.take(400)}")
                    }
                    // look for driveseed links directly - be very aggressive
                    Regex("https?://[^\"'\\s<> ]*driveseed[^\"'\\s<> ]*/file/[^\"'\\s<> ]+").find(body)?.let { return it.value.replace("\\/", "/") }
                    Regex("https?://[^\"'\\s<> ]*driveseed[^\"'\\s<> ]*/r\\?[^\"'\\s<> ]+").find(body)?.let { return it.value.replace("\\/", "/") }
                    Regex("https?://[^\"'\\s<> ]*driveseed[^\"'\\s<> ]+/[^\"'\\s<> ]+").find(body)?.let {
                        val v = it.value.replace("\\/", "/")
                        if (v.contains("/file/") || v.contains("/r?") || v.contains("download") || v.contains("drive")) return v
                    }
                    // look for any driveseed in the whole body
                    Regex("driveseed\\.[a-zA-Z]+/[^\"'\\s<> ]+").findAll(body).forEach { m ->
                        val v = "https://${m.value.replace("\\/", "/")}"
                        if (v.contains("/file/") || v.contains("/r?")) return v
                    }
                    Regex("driveseed[^\"'\\s<> ]+/file/[^\"'\\s<> ]+").findAll(body).forEach { m ->
                        val v = "https://${m.value.replace("\\/", "/")}"
                        return v
                    }
                    // also look for lp-s1-form anywhere
                    Regex("<form[^>]*lp-s1-form[^>]*>(.*?)</form>", RegexOption.DOT_MATCHES_ALL).find(body)?.let { fm ->
                        Log.i(TAG, "followChain try$i hop$hops: found lp-s1-form by regex")
                        val action = RE_ACTION.find(fm.groupValues[0])?.groupValues?.get(1)?.let { unescapeText(it) } ?: current
                        val fields = RE_FIELD.findAll(fm.groupValues[1]).associate { unescapeText(it.groupValues[1]) to unescapeText(it.groupValues[2]) }
                        if (fields.isNotEmpty()) {
                            try {
                                val presp2 = app.post(action, data = fields, referer = current, headers = formHead, allowRedirects = false)
                                val ploc2 = presp2.headers["Location"] ?: presp2.headers["location"]
                                if (!ploc2.isNullOrBlank()) {
                                    val pnu2 = if (ploc2.startsWith("http")) ploc2 else "https://en.thenaukriadda.in$ploc2"
                                    if (pnu2.contains("driveseed.org") || pnu2.contains("/file/")) return if (pnu2.startsWith("/")) "https://driveseed.org$pnu2" else pnu2
                                    current = pnu2; referer = action; continue
                                }
                                val h2 = presp2.text
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(h2)?.let { return it.value }
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(h2)?.let { return it.value }
                                val ck = RE_SC.find(h2)
                                if (ck != null) {
                                    val cname = ck.groupValues[1]
                                    val cval = unescapeText(ck.groupValues[2])
                                    val go = "https://en.thenaukriadda.in/?lp_go=$cname"
                                    try {
                                        val goResp = app.get(go, headers = head, referer = action, cookies = mapOf(cname to cval), allowRedirects = false)
                                        val l = goResp.headers["Location"] ?: goResp.headers["location"]
                                        if (!l.isNullOrBlank()) {
                                            val nu = if (l.startsWith("http")) l else if (l.startsWith("/")) "https://driveseed.org$l" else l
                                            if (nu.contains("driveseed.org") || nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                            current = nu; referer = go; continue
                                        }
                                        val gbody = goResp.text
                                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(gbody)?.let { return it.value }
                                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+/r\\?[^\"'\\s<>]+").find(gbody)?.let { return it.value }
                                    } catch (e: Exception) {}
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    // try js redirect
                    jsRedirect(body)?.let { r ->
                        val nextUrl = if (r.startsWith("http")) r else {
                            val base = URI(current)
                            "${base.scheme}://${base.host}${if (base.port != -1 && base.port != 80 && base.port != 443) ":${base.port}" else ""}$r"
                        }
                        Log.i(TAG, "followChain try$i hop$hops: js -> $nextUrl")
                        if (nextUrl.contains("driveseed.org") && (nextUrl.contains("/file/") || nextUrl.contains("/r?"))) return if (nextUrl.startsWith("/")) "https://driveseed.org$nextUrl" else nextUrl
                        if (nextUrl.contains("/file/")) return if (nextUrl.startsWith("/")) "https://driveseed.org$nextUrl" else nextUrl
                        current = nextUrl
                        referer = current
                        continue
                    }
                    // meta refresh
                    RE_META_REFRESH.find(body)?.groupValues?.getOrNull(1)?.let { r ->
                        val nextUrl = if (r.startsWith("http")) r else {
                            val base = URI(current)
                            "${base.scheme}://${base.host}${if (base.port != -1 && base.port != 80 && base.port != 443) ":${base.port}" else ""}$r"
                        }
                        Log.i(TAG, "followChain try$i hop$hops: meta -> $nextUrl")
                        if (nextUrl.contains("driveseed.org") && (nextUrl.contains("/file/") || nextUrl.contains("/r?"))) return if (nextUrl.startsWith("/")) "https://driveseed.org$nextUrl" else nextUrl
                        if (nextUrl.contains("/file/")) return if (nextUrl.startsWith("/")) "https://driveseed.org$nextUrl" else nextUrl
                        current = nextUrl
                        referer = current
                        continue
                    }
                    // forms
                    formOf(body, "lp-land")?.let { f ->
                        Log.i(TAG, "followChain try$i hop$hops: form lp-land")
                        try {
                            // Try POST with allowRedirects true first (follow naturally)
                            try {
                                val presp = app.post(f.first, data = f.second, referer = current, headers = formHead, allowRedirects = true)
                                val ptext = presp.text
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(ptext)?.let { return it.value }
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]+").find(ptext)?.let { v ->
                                    if (v.value.contains("/file/") || v.value.contains("/r?")) return v.value
                                }
                                jsRedirect(ptext)?.let { r ->
                                    val nu = if (r.startsWith("http")) r else {
                                        val base = URI(presp.url.toString())
                                        "${base.scheme}://${base.host}${if (base.port != -1 && base.port != 80 && base.port != 443) ":${base.port}" else ""}$r"
                                    }
                                    if (nu.contains("driveseed.org") && (nu.contains("/file/") || nu.contains("/r?"))) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                    if (nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                }
                                // look for lp-s1 or sc
                                formOf(ptext, "lp-s1-form")?.let { f2 ->
                                    Log.i(TAG, "followChain try$i hop$hops: form lp-s1-form (from lp-land resp)")
                                    val presp2 = app.post(f2.first, data = f2.second, referer = presp.url.toString(), headers = formHead, allowRedirects = true)
                                    val h2 = presp2.text
                                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(h2)?.let { return it.value }
                                    val ck = RE_SC.find(h2)
                                    if (ck != null) {
                                        val cname = ck.groupValues[1]
                                        val cval = unescapeText(ck.groupValues[2])
                                        val go = "https://en.thenaukriadda.in/?lp_go=$cname"
                                        try {
                                            val goResp = app.get(go, headers = head, referer = presp2.url.toString(), cookies = mapOf(cname to cval), allowRedirects = false)
                                            val l = goResp.headers["Location"] ?: goResp.headers["location"]
                                            if (!l.isNullOrBlank()) {
                                                val nu = if (l.startsWith("http")) l else if (l.startsWith("/")) "https://driveseed.org$l" else l
                                                if (nu.contains("driveseed.org") || nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                            }
                                            val gbody = goResp.text
                                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(gbody)?.let { return it.value }
                                            jsRedirect(gbody)?.let { r ->
                                                val nu = if (r.startsWith("http")) r else "https://driveseed.org$r"
                                                if (nu.contains("driveseed.org") || nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                            }
                                        } catch (e: Exception) {}
                                    }
                                }
                                val ck2 = RE_SC.find(ptext)
                                if (ck2 != null) {
                                    val cname = ck2.groupValues[1]
                                    val cval = unescapeText(ck2.groupValues[2])
                                    val go = "https://en.thenaukriadda.in/?lp_go=$cname"
                                    try {
                                        val goResp = app.get(go, headers = head, referer = presp.url.toString(), cookies = mapOf(cname to cval), allowRedirects = false)
                                        val l = goResp.headers["Location"] ?: goResp.headers["location"]
                                        if (!l.isNullOrBlank()) {
                                            val nu = if (l.startsWith("http")) l else if (l.startsWith("/")) "https://driveseed.org$l" else l
                                            if (nu.contains("driveseed.org") || nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                        }
                                        val gbody = goResp.text
                                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(gbody)?.let { return it.value }
                                    } catch (e: Exception) {}
                                }
                                // if we ended up on en.thenaukriadda.in homepage, try GET sid again or look for links
                                if (ptext.contains("The Naukri Adda") || presp.url.toString().contains("en.thenaukriadda.in")) {
                                    // try to go to sid again
                                    try {
                                        val rs = app.get(current, headers = head, referer = presp.url.toString())
                                        val rsText = rs.text
                                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(rsText)?.let { return it.value }
                                        formOf(rsText, "lp-land")?.let { f3 ->
                                            val r2 = app.post(f3.first, data = f3.second, referer = rs.url.toString(), headers = formHead, allowRedirects = true)
                                            val r2t = r2.text
                                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(r2t)?.let { return it.value }
                                        }
                                    } catch (e: Exception) {}
                                }
                                current = presp.url.toString()
                                referer = current
                                continue
                            } catch (e: Exception) {}
                            // fallback to old behavior
                            val presp = app.post(f.first, data = f.second, referer = current, headers = formHead, allowRedirects = false)
                            val ploc = presp.headers["Location"] ?: presp.headers["location"]
                            if (!ploc.isNullOrBlank()) {
                                val pnu = if (ploc.startsWith("http")) ploc else {
                                    val base = URI(f.first)
                                    "${base.scheme}://${base.host}${if (base.port != -1 && base.port != 80 && base.port != 443) ":${base.port}" else ""}$ploc"
                                }
                                if (pnu.contains("driveseed.org") || pnu.contains("/file/")) return if (pnu.startsWith("/")) "https://driveseed.org$pnu" else pnu
                                current = pnu
                                referer = f.first
                                continue
                            }
                            val h = presp.text
                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(h)?.let { return it.value }
                            formOf(h, "lp-s1-form")?.let { f2 ->
                                Log.i(TAG, "followChain try$i hop$hops: form lp-s1-form (from lp-land resp)")
                                val presp2 = app.post(f2.first, data = f2.second, referer = f.first, headers = formHead, allowRedirects = false)
                                val ploc2 = presp2.headers["Location"] ?: presp2.headers["location"]
                                if (!ploc2.isNullOrBlank()) {
                                    val pnu2 = if (ploc2.startsWith("http")) ploc2 else {
                                        val base = URI(f2.first)
                                        "${base.scheme}://${base.host}${if (base.port != -1 && base.port != 80 && base.port != 443) ":${base.port}" else ""}$ploc2"
                                    }
                                    if (pnu2.contains("driveseed.org") || pnu2.contains("/file/")) return if (pnu2.startsWith("/")) "https://driveseed.org$pnu2" else pnu2
                                }
                                val h2 = presp2.text
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(h2)?.let { return it.value }
                                val ck = RE_SC.find(h2)
                                if (ck != null) {
                                    val cname = ck.groupValues[1]
                                    val cval = unescapeText(ck.groupValues[2])
                                    val go = "https://en.thenaukriadda.in/?lp_go=$cname"
                                    try {
                                        val goResp = app.get(go, headers = head, referer = f2.first, cookies = mapOf(cname to cval), allowRedirects = false)
                                        val l = goResp.headers["Location"] ?: goResp.headers["location"]
                                        if (!l.isNullOrBlank()) {
                                            val nu = if (l.startsWith("http")) l else if (l.startsWith("/")) "https://driveseed.org$l" else l
                                            if (nu.contains("driveseed.org") || nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                        }
                                        val gbody = goResp.text
                                        Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(gbody)?.let { return it.value }
                                    } catch (e: Exception) {}
                                    continue
                                }
                            }
                            val ck2 = RE_SC.find(h)
                            if (ck2 != null) {
                                val cname = ck2.groupValues[1]
                                val cval = unescapeText(ck2.groupValues[2])
                                val go = "https://en.thenaukriadda.in/?lp_go=$cname"
                                try {
                                    val goResp = app.get(go, headers = head, referer = f.first, cookies = mapOf(cname to cval), allowRedirects = false)
                                    val l = goResp.headers["Location"] ?: goResp.headers["location"]
                                    if (!l.isNullOrBlank()) {
                                        val nu = if (l.startsWith("http")) l else if (l.startsWith("/")) "https://driveseed.org$l" else l
                                        if (nu.contains("driveseed.org") || nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                    }
                                    val gbody = goResp.text
                                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(gbody)?.let { return it.value }
                                } catch (e: Exception) {}
                            }
                            Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(h)?.let { return it.value }
                            current = f.first
                            referer = current
                        } catch (e: Exception) {
                            current = f.first
                            referer = current
                        }
                        continue
                    }
                    formOf(body, "lp-s1-form")?.let { f ->
                        Log.i(TAG, "followChain try$i hop$hops: form lp-s1-form")
                        val h = app.post(f.first, data = f.second, referer = current, headers = formHead).text
                        val ck = RE_SC.find(h)
                        if (ck != null) {
                            val cname = ck.groupValues[1]
                            val cval = unescapeText(ck.groupValues[2])
                            val go = "https://en.thenaukriadda.in/?lp_go=$cname"
                            current = go
                            referer = f.first
                            // set cookie by following with cookies
                            try {
                                val goResp = app.get(go, headers = head, referer = referer, cookies = mapOf(cname to cval), allowRedirects = false)
                                val l = goResp.headers["Location"] ?: goResp.headers["location"]
                                if (!l.isNullOrBlank()) {
                                    val nu = if (l.startsWith("http")) l else "https://driveseed.org$l"
                                    if (nu.contains("driveseed.org")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                    if (nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                    current = if (l.startsWith("http")) l else "https://en.thenaukriadda.in$l"
                                    referer = go
                                    continue
                                }
                                val gbody = goResp.text
                                jsRedirect(gbody)?.let { r ->
                                    val nu = if (r.startsWith("http")) r else "https://driveseed.org$r"
                                    if (nu.contains("driveseed.org")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                    if (nu.contains("/file/")) return if (nu.startsWith("/")) "https://driveseed.org$nu" else nu
                                    current = if (r.startsWith("http")) r else "https://en.thenaukriadda.in$r"
                                    referer = go
                                    continue
                                }
                                Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>/]+/file/[^\"'\\s<>]+").find(gbody)?.let { return it.value }
                            } catch (e: Exception) {}
                            continue
                        }
                        continue
                    }
                    // look for driveseed links
                    Regex("https?://[^\"'\\s<>]*driveseed[^\"'\\s<>]*").find(body)?.let { m ->
                        return m.value
                    }
                    Regex("href=[\"']([^\"']*driveseed[^\"']*)[\"']").find(body)?.let { m ->
                        val r = m.groupValues[1]
                        return if (r.startsWith("http")) r else "https://driveseed.org$r"
                    }
                    break
                }
                throw Exception("followChain exhausted hops for try$i")
            } catch (e: Exception) {
                lastErr = e
                Log.i(TAG, "followChain try$i FAIL ${e::class.simpleName} ${e.message}")
                if (i < 6) kotlinx.coroutines.delay(2000)
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
    private suspend fun mirrorsOf(fileUrl: String, serverLabel: String?): List<Cand> {
        val page = try {
            val r = app.get(fileUrl, headers = head)
            if (!r.isSuccessful) return emptyList()
            r.text
        } catch (e: Exception) {
            Log.i(TAG, "mirrorsOf FAIL ${e::class.simpleName}")
            return emptyList()
        }

        val quality = qualityOf(serverLabel ?: fileUrl)
        val note = serverLabel?.let { cleanTitle(it) }?.ifBlank { null }
        val cands = ArrayList<Cand>(4)
        val seen = HashSet<String>()

        fun add(rawUrl: String, server: String, referer: String?) {
            val u = unescapeText(rawUrl).replace(RE_WS, "")
            if (u.isBlank() || !seen.add(u)) return
            cands.add(Cand(u, server, quality, note, referer, head))
        }

        val r2 = RE_R2.findAll(page).toList()
        val vg = RE_VG.findAll(page).toList()
        // كل مرايا `r2` لا أولها فقط: الصفحة قد تُعلن أكثر من رابط مباشر للملفّ
        // نفسه، وكان الأول وحده يُلتقط فيسقط الباقي بلا سبب. والتكرار مأمون
        // لأن `add` تُسقط المكرّر بالرابط.
        for (m in r2) add(m.groupValues[1], "Cloud", null)
        for (m in vg) {
            val u = unescapeText(m.groupValues[1])
            add(u, if (u.contains("cdn.")) "CDN" else "Fast", fileUrl)
        }

        // ★ سطرٌ واحد يكفي لتشخيص «لا روابط» مستقبلًا: عدد كل عائلة مرايا.
        // قِيس أنّ الملفّ قد يحمل `r2=0 vg=2` — أي أنّ مرآة `r2` المباشرة غير
        // موجودة، والبديلان على `video-gen.xyz` وهو نطاق **لا يُحلّ** (NXDOMAIN
        // من 1.1.1.1 و8.8.8.8 معًا، والضابط `driveseed.org` يُحلّ). فتلك الملفّات
        // لا مرآة حيّة لها في الموقع نفسه، ولا يُنقذها أي تغيير في الكود.
        // و`r2=1` يقابل ملفًّا يُبَثّ فعلًا (قِيس `200 video/matroska`).
        Log.i(TAG, "mirrorsOf ${fileUrl.substringAfterLast('/')}: r2=${r2.size} vg=${vg.size}" +
            if (r2.isEmpty() && vg.isEmpty()) " — الموقع لا يُعلن أي مرآة لهذا الملفّ" else "")
        if (r2.isEmpty() && vg.isNotEmpty()) {
            Log.i(TAG, "mirrorsOf: مرايا r2 غائبة والبديل على نطاق لا يُحلّ — قد يُسقطها innerMedia")
        }

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
