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
import kotlinx.coroutines.delay
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
            "video-gen.xyz",
        )

        /**
         * مضيفاتٌ يردّ عليها مُحلِّل الجهاز بـ`NXDOMAIN` وهي **حيّةٌ فعلًا**:
         * قِيس من الجهاز `ping cdn.video-gen.xyz` → `unknown host` بينما
         * `nslookup … 8.8.8.8` يعيد عنوانَي Cloudflare، و`curl --resolve`
         * عليها يعيد 200. وكلّها خلف Cloudflare، فيكفي تثبيت عنوان حافةٍ
         * واحدٍ لها جميعًا — والـ`SNI` يحمل الاسم الصحيح.
         *
         * بلا هذا التثبيت كان كل ملفٍّ على `video-gen` (وهو **المرآة الوحيدة**
         * لبعض الحلقات، إذ لا `r2.dev` في صفحتها) يسقط بـ`UnknownHostException`
         * فتُعلن القائمة فارغة.
         */
        private val pinnedSuffixes = listOf(".video-gen.xyz", ".workers.dev")

        private fun isPinned(host: String): Boolean =
            pinnedHosts.contains(host) || pinnedSuffixes.any { host.endsWith(it) }

        override fun lookup(hostname: String): List<InetAddress> {
            if (isPinned(hostname.lowercase())) {
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
                val episodes = buildEpisodes(epRows, if (pref(KEY_INCLUDE_BATCH, true)) batchRows else emptyList())
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
                // `<رابط الأرشيف>\t<عنوان صفّ الجودة>\t<نصّ الزرّ>`. العنوان هو
                // ما يحمل الجودة (‎1080p x264‎) والحجم، ويُبنى عليه الترتيب
                // والوسم؛ ونصّ الزرّ الثالث يُقرأ اسمًا للسيرفر إن كان اسمًا.
                // وكل سطر يبدأ بـhttp وإلا لحق CloudStream رابط الصفحة بها.
                val data = dlRows.joinToString("\n") { "${it.href}\t${it.heading}\t${it.label}" }
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
     * وعند تكرار الحلقة عبر جوداتٍ مختلفة **تُحفظ كلّها**: لكل جودةٍ ملفٌّ
     * مستقلّ على `driveseed` (‎720p‎ و‎1080p‎ مثلًا)، وحصرُ الأعلى كان يُخفي
     * بقيّة روابط التحميل. السطور تُجمع بـ`\n` و`loadLinks` يحلّ كلًّا منها.
     */
    private suspend fun buildEpisodes(epRows: List<Row>, batchRows: List<Row>): List<Episode> {
        fixDns()
        val archives = epRows.distinctBy { it.href }
        // كل أرشيفٍ يُعيد: حلقاته، ورابط «All Episodes Batch» الذي يفتح
        // `/list/` على driveseed بقائمة الموسم كاملًا (قِيس: ٩ ملفّات لـ٩ حلقات).
        data class Scan(val eps: List<Pair<String, Row>>, val batches: List<Pair<String, Row>>)

        suspend fun scan(row: Row): Scan = try {
            // `getText` لا `app.get`: يردّ 429 على الدفعات المتوازية، وهو
            // يُعيد المحاولة بتراجعٍ أُسّيّ بدل إسقاط أرشيفٍ كامل.
            val got = getText(row.href)
            if (got == null) {
                Log.i(TAG, "archive FAIL ${row.href.substringAfterLast('/')}")
                Scan(emptyList(), emptyList())
            } else {
                val eps = ArrayList<Pair<String, Row>>(16)
                val batches = ArrayList<Pair<String, Row>>(2)
                for (a in RE_SID.findAll(got.first)) {
                    val sid = unescapeText(a.groupValues[1]).replace(RE_WS, "")
                    val label = stripTags(a.groupValues[2])
                    if (label.contains("batch", true) || label.contains("all episodes", true)) {
                        batches.add(sid to row.copy(label = label))
                        continue
                    }
                    val n = epNumberOf(label) ?: continue
                    eps.add(sid to row.copy(label = label, epNum = n))
                }
                Log.i(TAG, "archive ${row.href.substringAfterLast('/')}:" +
                    " حلقات=${eps.size} دفعات=${batches.size}")
                Scan(eps, batches)
            }
        } catch (e: Exception) {
            Log.i(TAG, "archive FAIL ${row.href} ${e::class.simpleName}" +
                if (e is UnknownHostException) " — المضيف لا يُحلّ" else "")
            Scan(emptyList(), emptyList())
        }

        // ★ ٥ أرشيفاتٍ في الوقت الواحد لا ٣: قِيس أن ٨ طلبات متوازية على
        // `episodes.modpro.blog` تُرجع **429**، لكن `getText` صار يُعيد
        // المحاولة، فـ٥ آمنة وأسرع بمرّتين لمسلسلٍ بـ١٨ أرشيفًا (قائمة
        // الحلقات نفسها كانت تتأخّر ثوانيَ طويلة قبل أن تظهر).
        val fetched = ArrayList<Scan>(archives.size)
        for (chunk in archives.chunked(5)) {
            fetched += coroutineScope {
                chunk.map { row -> async(Dispatchers.IO) { scan(row) } }.awaitAll()
            }
            if (archives.size > 5) delay(200)
        }

        // مفتاح الحلقة → كل جوداتها (لا الأعلى فقط). `LinkedHashMap` ليبقى
        // الترتيب الذي وردت به الأرشيفات، والفرز النهائي يضمن الثبات.
        val bySeasonEp = LinkedHashMap<Pair<Int, Int>, MutableList<Pair<String, Row>>>()
        val seenSids = HashSet<String>()
        for (scan in fetched) {
            for ((sid, row) in scan.eps) {
                if (row.epNum <= 0) continue
                // الرابط نفسه قد يتكرّر في أرشيفين (جودة/مرآة) فلا يُحلّ مرّتين.
                if (!seenSids.add(sid)) continue
                val key = (seasonOf(row.heading) ?: 1) to row.epNum
                bySeasonEp.getOrPut(key) { ArrayList(2) }.add(sid to row)
            }
        }

        val showSeasons = pref(KEY_SHOW_SEASONS, true)
        val eps = ArrayList<Episode>(bySeasonEp.size + 1)
        for ((key, list) in bySeasonEp.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val (season, num) = key
            // كل جودةٍ سطرٌ مستقلّ: `<sid>	<عنوان الجودة> · SxxExx	<اسم الرابط>`.
            // رقم الحلقة في العنوان يلزم لاختيار الملفّ الصحيح من صفحات `/list/`،
            // والاسم الثالث هو نصّ رابط الأرشيف (يُصبح اسم السيرفر إن كان اسمًا).
            val payload = list
                .sortedByDescending { qualityOf(it.second.heading) }
                .joinToString("\n") { (sid, row) ->
                    sid + "\t" + epHeading(row.heading, season, num) + "\t" + row.label
                }
            eps.add(newEpisode(payload) {
                this.name = if (showSeasons) "الموسم $season · الحلقة $num" else "الحلقة $num"
                this.season = season
                this.episode = num
            })
        }

        // «كل الحلقات»: ملفّ الـZIP على الموقع محجوبٌ بتسجيل الدخول (قِيس:
        // `/file/` يعرض «Login to download» وحدها، ومرآة `/zfile/` تشير إلى
        // مضيف `workers.dev` لا يُحلّ)، فرابط «All Episodes Batch» في صفحة
        // الحلقات هو الطريق العامل: يفتح `/list/` بقائمة ملفّات الموسم كاملة.
        // سطرٌ لكل جودة، ثم أرشيف الـZIP احتياطًا إن كان متاحًا.
        val batchSeasons = HashSet<Int>()
        for (b in batchRows) {
            val season = seasonOf(b.heading) ?: 1
            if (!batchSeasons.add(season)) continue
            val lines = ArrayList<String>(4)
            val qualityLines = fetched
                .flatMap { it.batches }
                .filter { (seasonOf(it.second.heading) ?: 1) == season }
                .sortedByDescending { qualityOf(it.second.heading) }
                .distinctBy { it.first }
            for ((sid, row) in qualityLines) {
                // «كل الحلقات» تُكتب في العنوان: `epFromText` كان يقرأ `E01-45`
                // من عنوان المدى فيظنّها الحلقة 1 ويُبقي ملفًّا واحدًا من `/list/`.
                lines.add(sid + "\t" + row.heading.trim() + " · كل الحلقات\t" + row.label)
            }
            lines.add(b.href + "\t" + b.heading.trim() + " · كل الحلقات\t" + b.label)
            Log.i(TAG, "batch S$season: qualityLists=${qualityLines.size}")
            eps.add(newEpisode(lines.joinToString("\n")) {
                this.name = if (showSeasons) "الموسم $season · كل الحلقات" else "كل الحلقات"
                this.season = season
                this.episode = 0
            })
        }
        return eps
    }

    /** عنوان سطر الحلقة: عنوان الجودة ثم `SxxExx` (يُقرأ منه الاسم والجودة والمطابقة). */
    private fun epHeading(heading: String, season: Int, ep: Int): String =
        heading.trim() + " · S%02dE%02d".format(season, ep)


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
        /**
         * رابط **صفحة** محجوبة بتسجيل الدخول (ZIP على «G-Drive») لا رابط وسائط:
         * لا يُفحص بـHTTP (فحصه يقيس صفحة HTML فيظهر حجمها ‎18 KB‎ مكان حجم
         * الأرشيف)، ويُعرض بوسم التحميل وحده.
         */
        var gated: Boolean = false,
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

            suspend fun one(e: String): List<Cand> {
                val parts = e.split("\t")
                val link = parts.getOrNull(0)?.trim().orEmpty()
                val heading = parts.getOrNull(1)?.trim()?.ifBlank { null }
                val hint = parts.getOrNull(2)?.trim()?.ifBlank { null }
                if (link.isBlank()) return emptyList()
                val res = resolve(link, heading, hint)
                if (res.isNotEmpty()) return res
                return try {
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
                } catch (ex: Exception) {
                    emptyList()
                }
            }

            // ٦ مدخلاتٍ في الوقت الواحد: كلّ مدخلةٍ سلسلة تحويلات كاملة على
            // `en.thenaukriadda.in` (مضيفٌ آخر غير `episodes.modpro.blog`
            // الذي يردّ 429 على ٨ طلبات). ٣ كانت تُطيل الانتظار دَورَين كاملَين
            // لمسلسلٍ بأربع جودات، والمستخدم يريد القائمة فورًا.
            val resolved = ArrayList<Cand>(entries.size * 2)
            for (chunk in entries.chunked(6)) {
                resolved += coroutineScope {
                    chunk.map { e -> async(Dispatchers.IO) { one(e) } }.awaitAll().flatten()
                }
                if (entries.size > 6) delay(150)
            }
            // التكرار يُقاس بالزوج (الرابط، السيرفر) لا بالرابط وحده: موقع
            // الأفلام يعطي «Fast Server (G-Drive)» و«Google Drive (Server 2)»
            // **الرابط نفسه**، وحذف أحدهما بالرابط كان يُخفي سيرفرًا كاملًا
            // من القائمة. وإعداد «إظهار البدائل» حين يُطفأ يعيد الحذف بالرابط
            // (ملفٌّ واحد بمدخلةٍ واحدة) — ولا يُخفي جودةً أبدًا.
            val deduped = if (showAlt) {
                resolved.distinctBy { it.url + "|" + it.server }
            } else {
                resolved.distinctBy { it.url }
            }
            if (deduped.isEmpty()) {
                Log.i(TAG, "loadLinks: resolved nothing from ${entries.size} entries (dns fixed)")
                return false
            }

            // فحصٌ حقيقيّ كما يفعل المشغّل: طلبٌ عاديّ بلا `Range`. و«لا نعرف»
            // (مهلة/انقطاع/فشل DNS) لا يُسقط مرشّحًا: قِيس أنّ
            // `links.modpro.blog` نفسه لا يُحلّ محليًّا، وأنّ رابط الوسائط
            // أحادي الاستعمال فردُّ 400 عليه لاحقًا ليس دليل موت.
            val probed = coroutineScope {
                deduped.map { c ->
                    async(Dispatchers.IO) {
                        // صفحة محجوبة: لا تُفحص (فحصها يقيس حجم HTML لا حجم الأرشيف).
                        if (c.gated) {
                            c.alive = true
                            c.certain = false
                            return@async c
                        }
                        // ٣٫٥ث سقف الفحص: الفحص يُنتظر قبل عرض القائمة، ومهلةٌ
                        // طويلة على مرآةٍ بطيئة تُؤخّر كل الروابط لا واحدًا.
                        // والرابط الذي لا يُحكم عليه يُبقى (سياسة «لا نعرف»).
                        val r = MediaProbe.probe(c.url, c.referer, c.headers, timeoutMs = 3500)
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

            // كل المرشّحين يُعرضون دائمًا — لا `take(1)`. إعداد «أفضل رابط
            // واحد» كان يُخفي الجودات وبقيّة السيرفرات، والمطلوب صراحةً:
            // كل جودة متاحة بكل سيرفراتها.
            val take = ordered
            var emitted = 0
            for (c in take) {
                // ★ الإرسال محاطٌ بـ`try` **داخل** الحلقة لا حولها كلّها: قِيس أن
                // مرشّحًا واحدًا شاذًّا (رابط لا يُبنى، أو خادم يردّ شيئًا غير
                // متوقّع) كان يُسقط القائمة بأكملها لأن الاستثناء يصعد إلى
                // `catch` الخارجي فيعود `false` ولو كان قبله ما أُرسل. الآن
                // يسقط المرشّح وحده ويُسجَّل، وتبقى بقيّة القائمة.
                //
                // ولكل ملفٍّ وسائط **مدخلتان**: «مشاهدة» و«تحميل» بالرابط نفسه.
                // الموقع لا يقدّم مشغّلًا (قِيس: صفر `iframe` وصفر `video` في
                // صفحة العمل) فالبثّ والتنزيل كلاهما من الملفّ ذاته؛ ووسمٌ واحد
                // كان يوهم المستخدم أنّ مشاهدة الحلقة غير متاحة. ملفّات
                // الأرشيف (`zip`/`rar`) تحمل وسم التحميل وحده.
                val uses = if (downloadOnly(c)) {
                    listOf(MediaProbe.Use.DOWNLOAD)
                } else {
                    listOf(MediaProbe.Use.WATCH, MediaProbe.Use.DOWNLOAD)
                }
                for (use in uses) {
                    try {
                        val label = MediaProbe.label(
                            c.server,
                            MediaProbe.qualityLabel(heightOf(c.quality)),
                            c.bytes,
                            c.url,
                            ExtractorLinkType.VIDEO,
                            c.note,
                            use,
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
     * هل الرابط أرشيفٌ (`zip`/`rar`) لا يُبَثّ؟ الأرشيف يُوسم «تحميل» وحده،
     * وما عداه ملفٌّ قابلٌ للمشاهدة والتنزيل معًا فتُعرض له مدخلتان.
     *
     * ولماذا لم يُعتمد على ادّعاء الموقع: صفحة العمل لا تحمل `iframe` ولا
     * `video` (قِيس: ‎0‎ و‎0‎ في ‎81764‎ بايتًا من `entry-content`) — أي لا مسار
     * مشاهدةٍ منفصل أصلًا، والبثّ يجري من ملفّ `driveseed` نفسه.
     */
    private fun isArchive(c: Cand): Boolean {
        val u = c.url.lowercase()
        if (u.contains(".zip") || u.contains(".rar") || u.contains(".7z")) return true
        // رابط صفحة محجوبة لا امتداد له؛ الوسم يأتي من اسم الملفّ في [Cand.note].
        if (c.note?.contains(".zip", true) == true || c.note?.contains(".rar", true) == true) return true
        return c.mime?.let { it.contains("zip", true) || it.contains("rar", true) } == true
    }

    /** اسم ملفٍّ (لا رابط) يدلّ على أرشيف موسم: `…S01.480p.Part.1.zip`. */
    private fun isArchiveName(name: String): Boolean =
        name.contains(".zip", true) || name.contains(".rar", true) || name.contains(".7z", true)

    /**
     * رابطٌ **للتحميل فقط**: أرشيف (`zip`/`rar`) أو سيرفر «G-Drive».
     *
     * «G-Drive» على الموقع يُعرض تحت «Fast Server (G-Drive)» وهو ملفّ Google
     * Drive كامل: يُنزَّل ولا يُبَثّ في المشغّل، ووسمه بـ«مشاهدة» كان يدفع
     * المستخدم إلى مشغّل لا يعمل. بقيت المرايا الأخرى (‎r2/video-gen‎) تحمل
     * الوسمين لأنها بثٌّ حقيقي.
     */
    private fun downloadOnly(c: Cand): Boolean =
        isArchive(c) || c.server.contains("G-Drive", true) || c.server.contains("Gdrive", true)

    /**
     * حلّ مدخلةٍ واحدة إلى ملفّات وسائط.
     *
     * المدخلة إمّا:
     * - رابط `?sid=` مباشر (ما تبنيه `buildEpisodes` لكل جودة) — سلسلة واحدة فقط.
     * - صفحة أرشيف فيها `?sid=` لكل حلقة؛ **تُختار الحلقة المطلوبة** من نصّ
     *   الرابط (`Episode 5`/`S01E05`).
     * - صفحة أرشيف نصوصها **أسماء سيرفرات** (`✅ Fast Server 1 (G-Drive)`،
     *   `🚀 Google Drive (Server 2)`) — وهذه تُحلّ **كلّها** لا واحدًا منها:
     *   حصرُ الأوّل كان يُخفي بقيّة السيرفرات، وهو سبب «روابط G-Drive لا تظهر».
     * - رابط `driveseed` أو ملفّ وسائط مباشر.
     */
    private suspend fun resolve(
        archive: String,
        serverLabel: String?,
        serverHint: String? = null,
    ): List<Cand> {
        if (archive.isBlank()) return emptyList()
        Log.i(TAG, "resolve: start ${archive.take(100)} label=$serverLabel hint=$serverHint")

        // 1) رابط `?sid=` جاهز: لا طلبَ استكشافي، `followChain` تكفيه.
        if (archive.contains("?sid=")) {
            Log.i(TAG, "resolve: direct sid")
            return chainToMirrors(archive, serverLabel, serverHint)
        }

        // 2) صفحة أرشيف (أو صفحة عملٍ لفيلم).
        val got = try {
            getText(archive, referer = mainUrl)
        } catch (e: Exception) {
            Log.i(TAG, "resolve archive FAIL ${e::class.simpleName}" +
                " ${hostOf(archive)}" +
                if (e is UnknownHostException) " — المضيف لا يُحلّ" else "")
            null
        }
        if (got == null) {
            Log.i(TAG, "resolve: no body ${archive.take(80)}")
            return emptyList()
        }
        val body = got.first
        val finalUrl = got.second

        // 3) رابط `driveseed` مباشر في الصفحة.
        driveseedLink(body)?.let {
            Log.i(TAG, "resolve: driveseed in page -> ${it.take(80)}")
            return mirrorsOf(it, serverLabel, serverHint)
        }

        // 4) أزواج `?sid=` مع نصّها.
        val pairs = RE_SID.findAll(body)
            .map { unescapeText(it.groupValues[1]).replace(RE_WS, "") to stripTags(it.groupValues[2]) }
            .filterNot { it.second.contains("comment", true) }
            .distinctBy { it.first }
            .toList()

        // 4-أ) أرشيف حلقات: نصوصه أرقام حلقات ⇒ تُختار الحلقة المطلوبة وحدها.
        val epPairs = pairs.filter { epFromText(it.second) != null }
        if (epPairs.isNotEmpty()) {
            val pick = pickSid(epPairs, serverLabel)
            Log.i(TAG, "resolve: sids=${pairs.size} wantEp=${epFromText(serverLabel ?: "")}" +
                " picked=${pick.second.take(40)}")
            return chainToMirrors(pick.first, serverLabel, null)
        }

        // 4-ب) نصوص أسماء سيرفرات ⇒ تُحلّ كلّها (سقف ٦) ويُحمل اسم كلٍّ منها
        //      إلى اسم الرابط المعروض، فيرى المستخدم «G-Drive» كما في الموقع.
        if (pairs.isNotEmpty()) {
            val servers = pairs.take(6)
            Log.i(TAG, "resolve: servers=${servers.joinToString(" | ") { it.second.take(30) }}")
            val out = coroutineScope {
                servers.map { (sid, name) ->
                    async(Dispatchers.IO) {
                        chainToMirrors(sid, serverLabel, serverHintOf(name) ?: serverHint)
                    }
                }.awaitAll().flatten()
            }
            // الحذف بالزوج (رابط، سيرفر): «Fast Server (G-Drive)» و«Google
            // Drive (Server 2)» قد يشيران إلى الملفّ نفسه، وحذف أحدهما كان
            // يُخفي سيرفرًا من القائمة.
            if (out.isNotEmpty()) return out.distinctBy { it.url + "|" + it.server }
        }

        // 5) الصفحة نفسها صارت `?sid=` بعد التحويل.
        if (finalUrl.contains("?sid=")) {
            Log.i(TAG, "resolve: redirected to sid")
            return chainToMirrors(finalUrl, serverLabel, serverHint)
        }

        // 6) صفحات بلا نصوص مطابقة: تُحلّ كل روابط `?sid=` القليلة وتُجمع مراياها.
        val all = RE_SID_ANY.findAll(body)
            .map { it.value.replace("&amp;", "&") }
            .distinct()
            .toList()
        if (all.isNotEmpty()) {
            Log.i(TAG, "resolve: ${all.size} sid links (no labels)")
            val out = ArrayList<Cand>()
            for (s in all.take(6)) out.addAll(chainToMirrors(s, serverLabel, serverHint))
            if (out.isNotEmpty()) return out.distinctBy { it.url + "|" + it.server }
        }

        // 7) ملفّ وسائط مباشر في الصفحة.
        directMedia(body)?.let { return it }

        // 8) صفحة نموذج `lp-land`: السلسلة تبدأ من عنوانها النهائي.
        if (body.contains("lp-land") || body.contains("_lp_http") ||
            body.contains("lp-s1-form") || body.contains("timed-content")
        ) {
            Log.i(TAG, "resolve: form page -> chain from ${finalUrl.take(80)}")
            return chainToMirrors(finalUrl, serverLabel, serverHint)
        }

        Log.i(TAG, "resolve: no route in ${archive.take(80)} (${body.length} بايت)")
        return emptyList()
    }

    /** سلسلة `?sid=` واحدة حتى مرايا الملفّ. */
    private suspend fun chainToMirrors(
        sidUrl: String,
        label: String?,
        hint: String? = null,
    ): List<Cand> {
        val fileUrl = followChain(sidUrl)
        if (fileUrl == null) {
            Log.i(TAG, "resolve: followChain returned null for ${sidUrl.take(70)}")
            return emptyList()
        }
        Log.i(TAG, "resolve: got fileUrl=${fileUrl.take(80)} hint=$hint")
        return mirrorsOf(fileUrl, label, hint)
    }

    /**
     * اختيار رابط `?sid=` المطلوب من صفحة أرشيف:
     * 1) رقم الحلقة من نصّ المدخلة (`S01E05`/`E05`/`Episode 5`) — مطابقة تامّة.
     * 2) وإلا «All Episodes Batch» (دفعة الموسم).
     * 3) وإلا الأوّل.
     */
    private fun pickSid(pairs: List<Pair<String, String>>, serverLabel: String?): Pair<String, String> {
        val wantEp = epFromText(serverLabel ?: "")
        if (wantEp != null) {
            pairs.firstOrNull { epFromText(it.second) == wantEp }?.let { return it }
        }
        pairs.firstOrNull {
            it.second.contains("batch", true) || it.second.contains("all episodes", true)
        }?.let { return it }
        return pairs.first()
    }

    /** أوّل ملفّ وسائط مباشر في صفحة (بلا سلسلة تحويلات). */
    private fun directMedia(body: String): List<Cand>? {
        val b = normBody(body)
        Regex("https?://[^\"'\\s<>]+\\.(mp4|mkv|avi|webm|mpg|mpeg|ts|zip|rar)(\\?[^\"'\\s<>]*)?")
            .find(b)?.let {
                return listOf(Cand(it.value, "Direct", qualityOf(it.value), null, null, head))
            }
        Regex("https?://[^\"'\\s<>]+/(download|dl|getfile)/[^\"'\\s<>]+").find(b)?.let {
            return listOf(Cand(it.value, "Direct", qualityOf(it.value), null, null, head))
        }
        return null
    }

    /**
     * اسم السيرفر من نصّ رابط الأرشيف، أو `null` إن كان النصّ رقم حلقة/دفعة.
     * `✅ Fast Server 1 (G-Drive)` → `Fast Server 1 (G-Drive)` (الرمز الأمامي يُزال).
     */
    private fun serverHintOf(label: String?): String? {
        val l = label?.let { stripTags(it) }?.trim().orEmpty()
        if (l.isBlank()) return null
        if (l.contains("batch", true) || l.contains("comment", true)) return null
        if (epFromText(l) != null) return null
        return l.replace(Regex("^[^\\p{L}\\p{N}]+"), "").trim().ifBlank { null }
    }

    /**
     * اسم الملفّ كما يعرضه `driveseed`: سطر `Name : Dutton.Ranch.S01E05….mkv`.
     * يُستعمل اسمًا للرابط، فيطابق ما يُنزَّل فعلًا.
     */
    private fun fileNameOf(html: String): String? {
        // الاسم قد يحوي مسافات (`Dutton Ranch S01E03 Act Of…`)، فيُقرأ حتى `<`
        // لا حتى أوّل فراغ. والسطر في الصفحة: `<li …>Name : <الاسم></li>`.
        Regex("Name\\s*:\\s*([^<\\r\\n]{3,240})").find(html)
            ?.groupValues?.get(1)?.let { return unescapeText(it).trim().ifBlank { null } }
        return Regex("Name\\s*:\\s*([^\\s<]{3,240})")
            .find(html.replace(Regex("<[^>]+>"), " "))
            ?.groupValues?.get(1)
            ?.let { unescapeText(it).trim() }
            ?.ifBlank { null }
    }

    /**
     * حجم الملفّ من صفحة `driveseed`: سطر `Size : 6.12GB`.
     * يلزم لروابط الصفحات المحجوبة، إذ لا يُفحص رابطها (فحصه يقيس صفحة HTML).
     */
    private fun sizeOf(html: String): Long? {
        val m = Regex("Size\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)B", RegexOption.IGNORE_CASE)
            .find(html.replace(Regex("<[^>]+>"), " ")) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val mul = when (m.groupValues[2].uppercase()) {
            "T" -> 1024.0 * 1024 * 1024 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "M" -> 1024.0 * 1024
            "K" -> 1024.0
            else -> 1.0
        }
        return (v * mul).toLong()
    }

    /**
     * اسم الملفّ للعرض: بلا امتداد وبلا ذيل الموقع.
     * `Dutton.Ranch.S01E05.720p.Hindi.English.Esubs.MoviesMod.army.mkv`
     * → `Dutton.Ranch.S01E05.720p.Hindi.English.Esubs`.
     */
    private fun displayName(raw: String): String {
        val t = raw.trim()
        var n = t.substringBeforeLast('.', t)
        n = n.replace(Regex("(?i)\\.?MoviesMod(\\.[A-Za-z0-9]+)*$"), "")
        n = n.replace(Regex("(?i)\\.?(army|meme|com)$"), "")
        return n.trim().ifBlank { t }
    }

    /** يضمّ اسم السيرفر من الأرشيف إلى اسم زرّ الملفّ: `Cloud Download · G-Drive`. */
    private fun combineServer(server: String, hint: String?): String {
        val h = hint?.trim().orEmpty()
        if (h.isBlank()) return server
        if (server.contains(h, true)) return server
        return "$server · $h"
    }

    /**
     * GET بنصّ الاستجابة وعنوانها النهائي، مع إعادة محاولة على 429/5xx.
     *
     * ★ قِيس أن `episodes.modpro.blog` يردّ **429** إذا طُلب منه أكثر من ٣
     * صفحاتٍ في اللحظة نفسها (٨ أرشيفات متوازية ⇒ نصفها 429 وجسمها 8 KB بلا
     * روابط) — وهذا سبب «حلقات لا تظهر روابطها» في المسلسلات ذات المواسم
     * الكثيرة. الانتظار التصاعدي يعيدها سليمة.
     */
    private suspend fun getText(
        url: String,
        referer: String? = null,
        tries: Int = 3,
    ): Pair<String, String>? {
        var wait = 800L
        for (i in 1..tries) {
            try {
                val r = app.get(url, headers = head, referer = referer, allowRedirects = true)
                if (r.isSuccessful) return r.text to r.url.toString()
                val code = r.code
                if (code == 429 || code >= 500) {
                    Log.i(TAG, "getText HTTP $code try$i ${url.take(60)}")
                    if (i < tries) {
                        delay(wait)
                        wait *= 2
                        continue
                    }
                }
                return null
            } catch (e: Exception) {
                if (i == tries) return null
                delay(wait)
                wait *= 2
            }
        }
        return null
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

    /** يفكّ قيمة كعكة `sc(...)`: base64 ثم zlib → نصّ (هو معرّف الجلسة عادةً). */
    private fun decodeSc(v: String): String? = try {
        val bytes = android.util.Base64.decode(v, android.util.Base64.DEFAULT)
        val inf = java.util.zip.Inflater()
        inf.setInput(bytes)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        inf.end()
        String(out.toByteArray(), Charsets.UTF_8)
    } catch (e: Throwable) {
        null
    }

    /** يبني قيمة كعكة `sc(...)` من sid: zlib ثم base64 (عكس [decodeSc]). */
    private fun encodeSc(sid: String): String? = try {
        val def = java.util.zip.Deflater()
        def.setInput(sid.toByteArray(Charsets.UTF_8))
        def.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        def.end()
        android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Throwable) {
        null
    }

    /** محاولة واحدة لكعكةٍ ما: طلبٌ خامّ (بلا جار كعك) ثم `app.get`. */
    private suspend fun lpGoTry(name: String, value: String, referer: String?): String? {
        val go = "https://en.thenaukriadda.in/?lp_go=$name"
        val ck = "$name=$value"
        // 1) خام: الكعكة وحدها
        val raw = rawGet(go, ck, referer)
        if (raw != null) {
            if (!raw.location.isNullOrBlank()) {
                val n = absUrl(go, raw.location)
                Log.i(TAG, "lpGo raw Location -> ${n.take(110)}")
                return n
            }
            if (raw.code in 200..299) {
                driveseedLink(raw.body)?.let {
                    Log.i(TAG, "lpGo raw driveseed -> ${it.take(110)}")
                    return it
                }
                jsTarget(raw.body)?.let {
                    val n = absUrl(go, it)
                    Log.i(TAG, "lpGo raw JS -> ${n.take(110)}")
                    return n
                }
                Log.i(
                    TAG,
                    "lpGo raw بلا انتقال (${raw.body.length})" +
                        " expired=${raw.body.contains("Session expired")}" +
                        " wait=${raw.body.contains("Please wait")}",
                )
                dbg("lp_go_fail_${System.currentTimeMillis()}.html", raw.body)
            }
        }
        // 2) احتياط عبر CloudStream
        for (g in 1..2) {
            val gr = try {
                app.get(
                    go,
                    headers = head + mapOf("Cookie" to ck),
                    referer = referer,
                    cookies = mapOf(name to value),
                    allowRedirects = false,
                )
            } catch (e: Exception) {
                Log.i(TAG, "lpGo app[$g] EX ${e::class.simpleName}")
                null
            }
            if (gr == null) {
                if (g < 2) kotlinx.coroutines.delay(800)
                continue
            }
            val gloc = gr.headers["Location"] ?: gr.headers["location"]
            if (!gloc.isNullOrBlank()) {
                val n = absUrl(go, gloc)
                Log.i(TAG, "lpGo app[$g] Location -> ${n.take(110)}")
                return n
            }
            if (gr.isSuccessful) {
                driveseedLink(gr.text)?.let {
                    Log.i(TAG, "lpGo app[$g] driveseed -> ${it.take(110)}")
                    return it
                }
                jsTarget(gr.text)?.let {
                    val n = absUrl(go, it)
                    Log.i(TAG, "lpGo app[$g] JS -> ${n.take(110)}")
                    return n
                }
                Log.i(
                    TAG,
                    "lpGo app[$g] بلا انتقال (${gr.text.length})" +
                        " expired=${gr.text.contains("Session expired")}",
                )
            } else {
                Log.i(TAG, "lpGo app[$g] HTTP ${gr.code}")
            }
            if (g < 2) kotlinx.coroutines.delay(800)
        }
        return null
    }

    /**
     * خطوة `?lp_go=<name>`.
     *
     * تُسجّل **فكّ تشفير** القيمة ومقارنتها بـ`sid`: قيمة `sc(...)` هي
     * `zlib(sid)`، فإن اختلفت فالكعكة من جلسةٍ أخرى — وهذا سبب
     * «Session expired» بلا لبس. وحينها يُعاد بناء القيمة من الـ`sid`
     * الحالي (`encodeSc`) وتُجرَّب، فلا يهمّ من أين جاءت القيمة الفاسدة.
     */
    private suspend fun lpGoStep(sc: Pair<String, String>, referer: String?, sid: String): String? {
        val sidVal = sid.substringAfter("?sid=", sid).substringBefore("&")
        val dec = decodeSc(sc.second)
        val matches = dec != null && sidVal.isNotEmpty() &&
            dec.trim().trimEnd('=') == sidVal.trim().trimEnd('=')
        Log.i(
            TAG,
            "lpGo name=${sc.first} len=${sc.second.length}" +
                " head=${sc.second.take(6)} tail=${sc.second.takeLast(6)}" +
                " decoded=${dec?.take(28)} sidLen=${sidVal.length} decLen=${dec?.length}" +
                " sidMatch=$matches",
        )
        val values = ArrayList<String>(2)
        if (matches) {
            // القيمة المقروءة من الصفحة سليمة أصلًا (نادرة بسبب المسافات) فتُجرَّب أولًا.
            values.add(sc.second)
        } else {
            // ★ القيمة المقروءة من الصفحة **فاسدة** (مسافات مكان `+` أو قطع)،
            // فإعادة البناء من `sid` تُجرَّب **أولًا**: تجربة الفاسدة قبلها كانت
            // تُهدر ٣ طلبات (~٢٫٥ ثانية) ثم تردّ «Session expired».
            encodeSc(sidVal)?.let {
                values.add(it)
                Log.i(TAG, "lpGo: قيمة بديلة من sid len=${it.length} head=${it.take(6)}")
            }
            values.add(sc.second)
        }
        for ((vi, v) in values.withIndex()) {
            val r = lpGoTry(sc.first, v, referer)
            if (r != null) {
                if (vi > 0) Log.i(TAG, "lpGo: نجحت القيمة البديلة")
                return r
            }
        }
        return null
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
        RE_SC.find(normBody(body))?.let { it.groupValues[1] to scValue(it.groupValues[2]) }

    /**
     * قيمة كعكة `sc(...)` كما تُستعمل في الترويسة.
     *
     * ★ الصفحة تُصدِرها أحيانًا **بمسافات مكان `+`** (قِيس: ‎6‎ مسافات و‎0‎
     * علامة `+` في قيمةٍ واحدة، مع `\/` مهرَّبة)، فيفشل فكّها وتُرسل قيمةٌ
     * فاسدة يردّ عليها الخادم «Session expired». تُعاد `+` هنا فيصلح الفكّ.
     */
    private fun scValue(raw: String): String =
        unescapeText(raw).replace(" ", "+").replace("&#43;", "+")

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
                        Log.i(TAG, "followChain hop$hops: POST ${pr.code} len=${pbody.length} setCookie=${pr.headers["set-cookie"]}")
                        driveseedLink(pbody)?.let { return it }
                        // حلّل ردّ الـPOST كما هو بلا طلبٍ ثانٍ
                        pendingBody = pbody
                        pendingUrl = pr.url.toString()
                        current = pr.url.toString()
                        referer = form.first
                        continue
                    }

                    // 3) كعكة `sc(...)` → `?lp_go=`
                    val scs = RE_SC.findAll(normBody(body))
                        .map { it.groupValues[1] to scValue(it.groupValues[2]) }
                        .distinctBy { it.first }
                        .toList()
                    if (scs.isNotEmpty()) {
                        dbg("sc_page_${attempt}_${System.currentTimeMillis()}.html", body)
                        Log.i(TAG, "followChain try$attempt hop$hops: sc matches=${scs.size}")
                        var resolved: String? = null
                        for ((si, sc) in scs.withIndex()) {
                            val next = lpGoStep(sc, pageUrl, sid)
                            if (next == null) {
                                Log.i(TAG, "followChain sc[$si] فشلت")
                                continue
                            }
                            if (isFinal(next)) return next
                            resolved = next
                            break
                        }
                        if (resolved != null) {
                            current = resolved
                            referer = resolved
                            continue
                        }
                        throw Exception("lp_go بلا انتقال (${scs.size} كعكة)")
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
     * صفحات `driveseed.org` ثلاثة أنماط، والفرق بينها كان سبب كل «لا روابط»:
     *
     * - `/l?id=…&key=…` — غلاف 92 بايت فيه `window.location.replace("/list/<id>")` فقط.
     * - `/list/<id>` — قائمة الموسم: روابط `/file/<id>` لكل حلقة (بلا أي مرآة).
     * - `/file/<id>` — **الصفحة الحقيقية**: تحمل `pub-….r2.dev` (بثّ مباشر)
     *   و`instant|cdn.video-gen.xyz` (غلافان يُحلّان إلى رابط الوسائط).
     *
     * و`instant`/`cdn` تردّان 302 إلى غلاف `video-seed.dev` الذي يحمل رابط
     * الوسائط في معامل `url=` — يُستخرج ولا يُبَثّ. ورابط `r2.dev` إن وُجد رابط
     * ملفٍّ مباشر — **لكنه ليس حاضرًا دائمًا** (قِيس `r2: []` على صفحةٍ كاملة)،
     * فيبقى `video-gen` هو المرآة الوحيدة لبعض الحلقات.
     *
     * الإصدارات السابقة كانت تتوقّف عند `/l?id=` أو عند `/list/` فتُعيد صفرًا.
     * هنا: تُتبَع الانتقالات، ثمّ إن كانت الصفحة قائمةً تُختار الحلقة المطلوبة
     * من `serverLabel` (SxxExx) وتُقرأ صفحتها، ثمّ تُستخرج المرايا.
     */
    private suspend fun mirrorsOf(
        fileUrl: String,
        serverLabel: String?,
        serverHint: String? = null,
    ): List<Cand> {
        var quality = qualityOf(serverLabel ?: fileUrl)
        // «كل الحلقات» تعني: لا تُصفَّ الملفّات برقم حلقة (العنوان يحمل
        // `E01-45` فيُقرأ خطأً حلقةً واحدة).
        val wantEp = if (serverLabel?.contains("كل الحلقات", true) == true) {
            null
        } else {
            epFromText(serverLabel ?: "")
        }
        val baseNote = serverLabel?.let { label ->
            Regex("S\\d{1,2}E\\d{1,3}", RegexOption.IGNORE_CASE).find(label)?.value
                ?: cleanTitle(label).ifBlank { null }
        }
        val cands = ArrayList<Cand>(12)
        val seen = HashSet<String>()
        val gatedNotes = HashSet<String>()

        fun add(
            rawUrl: String,
            server: String,
            referer: String?,
            note: String?,
            gated: Boolean = false,
            bytes: Long? = null,
        ) {
            val u = unescapeText(rawUrl).replace(RE_WS, "").trimEnd('.', ',', ')', ';')
            // المفتاح (الرابط + اسم السيرفر): سيرفران مختلفان قد يعطيان الملفّ
            // نفسه، وحذف الثاني بالرابط وحده يُخفي سيرفرًا من القائمة.
            val full = combineServer(server, serverHint)
            if (u.isBlank() || !seen.add("$u|$full")) return
            // رقم الحلقة من الرابط أولًا (اسم ملفّ `r2.dev` يحمله دائمًا)، ثم
            // اسم الملفّ الكامل، ثم عنوان الجودة. واسم الملفّ هو ما يُنزَّل
            // فعلًا فيطابق «اسم التحميل» الذي يطلبه المستخدم.
            val epMark = Regex("S\\d{1,2}E\\d{1,3}", RegexOption.IGNORE_CASE).find(u)?.value?.uppercase()
            cands.add(
                Cand(
                    u, full, quality,
                    note ?: epMark ?: baseNote, referer, head,
                    bytes = bytes, gated = gated,
                )
            )
        }

        /**
         * جمع روابط الوسائط من صفحة، بأسماء **أزرارها الحقيقية**: صفحة
         * `/file/` تسمّي كل خادم باسمه — «Cloud Download» (‎r2.dev‎)،
         * «Instant Download V2» (‎instant.video-gen‎)، «Instant Download»
         * (‎cdn.video-gen‎)، «Direct Links»/«Server 1/2» (‎workers.dev‎).
         * اختراع اسمٍ بديل («Cloud»/«Mirror») كان يجعل القائمة لا تشبه الموقع.
         */
        fun collect(html: String, pageUrl: String, note: String?): Int {
            val b = html.replace("\\/", "/")
            var found = 0
            for (a in RE_ANCHOR.findAll(b)) {
                val href = RE_HREF.find(a.groupValues[1])?.groupValues?.get(1)
                    ?.let { unescapeText(it) }?.replace(RE_WS, "") ?: continue
                if (href.isBlank()) continue
                val text = stripTags(a.groupValues[2]).trim()
                when {
                    href.contains(".r2.dev") -> {
                        add(href, text.ifBlank { "Cloud Download" }, null, note)
                        found++
                    }
                    href.contains("video-gen.xyz") -> {
                        add(
                            href,
                            text.ifBlank { if (href.contains("cdn.")) "Instant Download" else "Instant Download V2" },
                            pageUrl,
                            note,
                        )
                        found++
                    }
                    href.contains("workers.dev") -> {
                        add(href, text.ifBlank { "Mirror" }, pageUrl, note)
                        found++
                    }
                }
            }
            if (found == 0) {
                for (m in RE_R2_ANY.findAll(b)) {
                    add(m.value, "Cloud Download", null, note)
                    found++
                }
                for (m in RE_VG_ANY.findAll(b)) {
                    add(m.value, if (m.value.contains("cdn.")) "Instant Download" else "Instant Download V2", pageUrl, note)
                    found++
                }
            }
            return found
        }

        /**
         * ملفّ محجوب بتسجيل الدخول (أرشيف `zip` على «G-Drive»): لا رابط وسائط
         * في صفحته — `generate` يردّ «Session Not Found Please Login!» (قِيس).
         * يُعرض رابط الصفحة نفسه بوسم ZIP صريح، فلا يختفي الموسم كلّه ولا
         * يبدو الرابط صالحًا للبثّ.
         */
        fun addGated(pageUrl: String, html: String, note: String?) {
            val file = fileNameOf(html)?.let { displayName(it) } ?: note ?: "ZIP"
            // سقف ١٠ أجزاء: الموسم قد يُقسَّم عشرة أجزاء، وسقف ٤ كان يُخفي
            // بقيّة روابط الـZIP عن المستخدم.
            if (gatedNotes.size >= 10 || !gatedNotes.add(file)) return
            val gatedNote = file + " · ZIP · login required"
            add(
                pageUrl,
                serverHint?.takeIf { it.isNotBlank() } ?: "G-Drive",
                pageUrl,
                gatedNote,
                gated = true,
                bytes = sizeOf(html),
            )
        }

        // صفحات `/wfile/` و`/zfile/` تحمل المرايا حين لا يحملها `/file/`.
        // ★ الطلبات الأربعة متوازية: تسلسلها كان يُضيف ٤ أضعاف زمن الاستجابة
        // لكل ملفٍّ محجوب (والموسم كلّه ملفّات محجوبة).
        suspend fun extras(pageUrl: String) {
            val id = pageUrl.substringAfter("/file/", "").substringBefore("?").substringBefore("/")
            if (id.isBlank()) return
            val targets = listOf(
                "https://driveseed.org/wfile/$id" to "Direct Links",
                "https://driveseed.org/wfile/$id?type=1" to "Server 1",
                "https://driveseed.org/wfile/$id?type=2" to "Server 2",
                "https://driveseed.org/zfile/$id" to "Cloud Resume Download",
            )
            val bodies = coroutineScope {
                targets.map { (u, server) ->
                    async(Dispatchers.IO) {
                        val body = getText(u, referer = pageUrl)?.first ?: return@async null
                        u to (server to body)
                    }
                }.awaitAll().filterNotNull()
            }
            for ((u, pair) in bodies) {
                val server = pair.first
                val body = pair.second
                for (a in RE_ANCHOR.findAll(body.replace("\\/", "/"))) {
                    val href = RE_HREF.find(a.groupValues[1])?.groupValues?.get(1)
                        ?.let { unescapeText(it) }?.replace(RE_WS, "") ?: continue
                    if (!href.contains("workers.dev") && !href.contains(".r2.dev")) continue
                    val text = stripTags(a.groupValues[2]).trim()
                    add(href, text.ifBlank { server }, u, null)
                }
            }
        }

        try {
            var current = fileUrl
            var referer: String? = null
            for (hop in 0..8) {
                val got = getText(current, referer = referer) ?: break
                val body = got.first
                val pageUrl = got.second
                referer = pageUrl

                val fileNote = fileNameOf(body)?.let { displayName(it) }
                // الجودة من اسم الملفّ أدقّ من عنوان الصفّ: صفحة الفيلم قد تجمع
                // ‎480p/720p/1080p‎ تحت عنوانٍ واحد، فيُقرأ أعلاها للجميع.
                fileNote?.let { q -> qualityOf(q).takeIf { it != Qualities.Unknown.value }?.let { quality = it } }
                val note = fileNote ?: baseNote
                val found = collect(body, pageUrl, note)

                if (pageUrl.contains("/file/")) {
                    if (found == 0) {
                        extras(pageUrl)
                        if (cands.isEmpty()) addGated(pageUrl, body, note)
                    }
                    if (cands.isNotEmpty()) break
                }

                // صفحة `/list/` = الموسم كلّه: اقرأ صفحات `/file/`.
                // - حلقةٌ مطلوبة: الملفّ المطابق **مع** ملفّات الأرشيف (‎zip/rar‎)
                //   فيُرى رابط تحميل الموسم بجوار الحلقة، وهو ما طلبه المستخدم.
                // - «كل الحلقات»: **كل** الملفّات، فتُجمع مرايا الموسم.
                val files = fileLinksOf(body)
                if (files.isNotEmpty()) {
                    val picked = if (wantEp != null) {
                        val epFiles = files.filter { (_, t) -> epFromText(t) == wantEp }
                        // أجزاء الأرشيف تُضاف دائمًا (سقف ٤): ملفّات الـZIP لا
                        // تحمل رقم حلقة، فكانت تُصفّى خارج القائمة كلّها.
                        val arch = files.filter { (_, t) -> isArchiveName(t) }.take(4)
                        (epFiles.ifEmpty { files } + arch).distinctBy { it.first }
                    } else {
                        files
                    }
                    val cap = if (wantEp != null) 8 else 20
                    Log.i(TAG, "mirrorsOf /list: files=${files.size} wantEp=$wantEp picked=${picked.size}")
                    // ٨ صفحات في الوقت الواحد: صفحة الموسم قد تحمل ٢٠ ملفًّا،
                    // و٥ في الدفعة تُطيل الانتظار أربع دفعات كاملة.
                    val subs = ArrayList<Pair<String, String>>(picked.size)
                    for (chunk in picked.take(cap).chunked(8)) {
                        subs += coroutineScope {
                            chunk.map { (href, _) ->
                                async(Dispatchers.IO) {
                                    val fu = absUrl(pageUrl, href)
                                    getText(fu, referer = pageUrl)?.let { fu to it.first }
                                }
                            }.awaitAll().filterNotNull()
                        }
                    }
                    // كل الصفحات المجلوبة تُقرأ: `subs` أُحضرت متوازية سلفًا،
                    // فلا كلفة شبكة في قراءتها، والتوقّف عند أوّل رابط كان
                    // يُسقط روابط الأرشيف (‎zip‎) الواقعة بعد ملفّ الحلقة.
                    for ((fu, text) in subs) {
                        val fn = fileNameOf(text)?.let { displayName(it) }
                        val ok = collect(text, fu, fn) > 0
                        if (!ok && fu.contains("/file/")) addGated(fu, text, fn)
                    }
                    if (cands.isNotEmpty()) break
                }

                val mv = jsTarget(body) ?: break
                val next = absUrl(pageUrl, mv)
                if (next == current) break
                current = next
            }
        } catch (e: Exception) {
            Log.i(TAG, "mirrorsOf FAIL ${e::class.simpleName} ${e.message}")
        }

        Log.i(TAG, "mirrorsOf ${fileUrl.takeLast(34)}: cands=${cands.size}" +
            " servers=${cands.map { it.server }.distinct().joinToString("|").take(80)}")
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
        /** نفس الرابطين بأي موضعٍ في الصفحة (وليس `href` وحده) — بعض الصفحات تكتبه نصًّا. */
        val RE_R2_ANY = Regex("https://pub-[a-z0-9-]+\\.r2\\.dev/[^\"'<>\\s\\\\]+")
        val RE_VG_ANY = Regex("https://(?:instant|cdn)\\.video-gen\\.xyz/[^\"'<>\\s\\\\]+")
        /** مرآة `workers.dev` التي تُصدرها صفحات `/wfile/` و`/zfile/`. */
        val RE_WORKER = Regex("href=\"(https?://[^\"]*workers\\.dev/[^\"]+)\"")

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
