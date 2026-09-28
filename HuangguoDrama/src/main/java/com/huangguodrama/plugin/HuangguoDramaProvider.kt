package com.huangguodrama.plugin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()

/** نافذة ذاكرة الـHTML — تخدم الفصول بجلب واحد، وتصمد حتى تأمل طويل قبل التشغيل. */
private const val CACHE_MS = 10 * 60_000L

/** أقصى عدد صفحات محفوظة (دراما واحدة لكل مفتاح) — تصفّح المكتبة يكفي لتجاوزه. */
private const val MAX_CACHED = 24

/** `self.__next_f.push([1,"…"])` — الدفعة ١ نص مُهرَّب. */
private val FLIGHT_RE =
    Regex("""self\.__next_f\.push\(\[1,("(?:[^"\\]|\\.)*")\]\)""", RegexOption.DOT_MATCHES_ALL)

/** `<article … data-drama-card="625">` — نلتقط البطاقة كاملة لتفادي حدود Suspense. */
private val CARD_RE =
    Regex("""<article[^>]*data-drama-card="(\d+)"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)

/** `aria-label="العنوان"` — تسبق صورة الغلاف داخل رابط البطاقة. */
private val TITLE_RE = Regex("""aria-label="([^"]*)"""")

/** `<a class="hg-tag" …>Chinese</a>` — تسمية التصنيف كما يعرضها الموقع. */
private val TAG_RE = Regex("""class="hg-tag"[^>]*>([^<]+)<""")

/**
 * `{"dramaId":"12"` — كائن صفحة الإعلان. ⚠ لا وجود له إلّا في صفحات `previews`
 * فعلاً: في ١٥ صفحة التفاصيل الأخرى تظهر العبارة نفسها داخل رابط
 * `data-detail-feed` (دراما مقترحة)، فالوجود وحده لا يميّز — لا بدّ من أن يكون
 * الكائن **قابلاً للتحليل وأن يحمل `title`**.
 */
private const val TRAILER_ANCHOR = "{\"dramaId\""

/** `&#x27;` و`&#8217;` — كيانات رقمية (عشري أو سداسي عشر) تمرّ عبر `aria-label`. */
private val NUM_ENTITY = Regex("""&#(x?)([0-9a-fA-F]+);""")

/** الكيانات الاسمية التي تُستعمل فعلاً في عناوين الموقع — تفكّها `decodeEntities`. */
private val NAMED_ENTITIES = listOf(
    "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
    "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
    "&nbsp;" to " ", "&hellip;" to "…", "&mdash;" to "—", "&ndash;" to "–"
)

/**
 * HuangguoDrama — دراما قصيرة عربية بالذكاء الاصطناعي (huangguodrama.ai/ar).
 *
 * ★ بنية الموقع (فحص مباشر 2026-09-28): Next.js App Router بلا حماية Cloudflare
 * (200 بلا تحدٍّ)، ولا `__NEXT_DATA__` — البيانات داخل دفعات RSC
 * `self.__next_f.push([1,"…"])`، فنقرأها من HTML ونحلّل JSON منها.
 *
 * الصفحات (لا افتراضات):
 *  - الرئيسية `/ar/`                 → بطاقات نظيفة `{"id":"drama:625",…}`
 *  - المكتبة   `/ar/library/`         → ١٦ دراما، DOM حقيقي `data-drama-card="625"`
 *  - التصنيف   `/ar/category/{slug}/` → نفس شكل المكتبة
 *  - البحث     `/ar/search/?keyword=` → نفس شكل المكتبة
 *  - التفاصيل  `/ar/detail/{id}/`     → `{"drama":{…}}` أو `{"dramaId":…}`
 *  - `/ar/video/{id}/` يوجّه 301 إلى `/ar/detail/{id}/` — فلا داعي لمناداته
 *
 * ★ مرساة الصفحة الرئيسية حسّاسة: الـflight يحمل `"items"` مرّتين، وأولى
 * مرّتين في قائمة التنقّل `{"items":[{"href":"/ar/","label":"الرئيسية"}]}`
 * بلا دراما. لا بدّ من القفل على `"items":[` (قوس مفتوح) وإلا صارت البطاقات صفراً.
 *
 * ★ شكلان للصفحة (لا واحد):
 *  - ١٥ دراما: كائن `"drama"` فيه `episodeList[]` (حلقات، لكل واحدة `video`).
 *  - المعرّف `12` وحده: كائن `{"dramaId":"12", …, "src":"/trailers/12.mp4",
 *    "previews":[{n:2..10}]}` — بلا `"drama"` وبلا `episodeList`. أي حلقات كاملة
 *    أسماؤها «الحلقة 2»… ومعها ٩ معاينات صامتة مدتها ١٠ ثوانٍ. نقرأها من
 *    المرساة الصحيحة وإلا ابتلعنا العنصر.
 *
 * ★ روابط الفيديو: نسبية `/trailers/625.mp4` (307 → media.huangguodrama.ai)
 * أو مطلقة `https://huangguo.chat/motion/drama-episodes/<sha256>.mp4`.
 * كلاهما MP4 قابل للتشغيل مباشرة (تحقّقنا: ftypisom، 206 على Range، 3–171 ميغا).
 * لا ترجمة على الموقع إطلاقاً — العربية مُدمجة في الفيديو نفسه.
 *
 * ★ `episodes` (47، 88، 60…) هو عدد حلقات السلسلة كاملاً، وهو ≠ عدد الحلقات
 * المتاحة فعلاً (9، 10، 11…). نعرض المتاح فقط ولا نَعِد بما لا تشغيل له،
 * ونذكر الفرق في وصف الصفحة.
 *
 * ★ الأداء: بلا أي طلب شبكة خارج الصفحة المطلوبة. `getMainPage` يمرّ على كل
 * فصل فيوكّله على الصفحة نفسها، فالذاكرة (٦٠ ثانية) تخدم الفصول بجلب واحد،
 * والحلقات تُقرأ من نفس صفحة التفاصيل في `load` ولا تُعاد في التشغيل إلا بعد
 * انتهاء النافذة.
 */
class HuangguoDramaProvider(
    private val prefs: SharedPreferences? = null,
    /** ★ `MainAPI` لا يوفّر سياقاً؛ نأخذه من `Plugin.load` (انظر HuangguoDramaPlugin). */
    private val appContext: Context? = null
) : MainAPI() {
    override var name = "HuangguoDrama"
    override var mainUrl = "https://huangguodrama.ai"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    /**
     * ★ لا خيار «ترتيب جودات» هنا عمداً: كل حلقة رابط mp4 واحد بلا بدائل ولا
     * جودات (متحقَّق: ملف 3–171 ميغا)، فالفرز عليه بلا مفعول. البدائل المعروضة
     * في الورقة كلّها تؤثّر في **القائمة** (ما يُعرض من حلقات) لا في الروابط.
     *
     * وكل افتراضي = سلوك اليوم تماماً: إظهار معاينات الإعلان، بلا حدّ حلقات،
     * ترتيب الموقع، وتشغيل مباشر بلا تأكيد.
     */
    private fun showPreviews(): Boolean =
        prefs?.getBoolean(HuangguoDramaSettingsBottomSheet.KEY_SHOW_PREVIEWS, true) != false

    private fun episodeLimit(): Int =
        prefs?.getString(HuangguoDramaSettingsBottomSheet.KEY_EPISODE_LIMIT, "0")?.toIntOrNull() ?: 0

    private fun newestFirst(): Boolean =
        prefs?.getString(HuangguoDramaSettingsBottomSheet.KEY_EPISODE_ORDER, "as_is") == "desc"

    private fun confirmPlay(): Boolean =
        prefs?.getBoolean(HuangguoDramaSettingsBottomSheet.KEY_CONFIRM_PLAY, false) == true

    /**
     * ★ `aria-label` مخرَجٌ من HTML، فهو يحمل كيانات لا نصاً: العنوان يظهر
     * للمستخدم `The Mafia Boss&#x27;s Secret Twins` — وسمٌ غريب في واجهة عربية.
     * نفكّها هنا بالترتيب: الكيانات المرقّمية أولاً (وإلا فككنا `&amp;#x27;` مرّتين
     * فصارت `&#x27;` نصاً)، ثم الاسمية الخمس.
     */
    private fun decodeEntities(s: String): String {
        var out = s
        for (pass in 0 until 2) {
            out = NUM_ENTITY.replace(out) { m ->
                val body = m.groupValues[1]
                val code = if (body.startsWith("x") || body.startsWith("X"))
                    body.drop(1).toIntOrNull(16) else body.toIntOrNull()
                if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
            }
        }
        for ((entity, ch) in NAMED_ENTITIES) out = out.replace(entity, ch)
        return out.trim()
    }

    /**
     * يقصّ قائمة الحلقات إلى الخيارات المعروضة. ★ الافتراضي (بلا حدّ، بلا عكس)
     * يُعيد `this` كما هو — لا نسخ ولا حذف ولا تغيير ترتيب.
     */
    private fun <T> List<T>.applyListOptions(limit: Int): List<T> =
        if (limit > 0 && size > limit) subList(0, limit) else this

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun nowMS(): Long = System.currentTimeMillis()

    private val htmlCache = mutableMapOf<String, Pair<String, Long>>()
    private val htmlLock = Any()

    /**
     * ★★ هذا سبب «لم يتم العثور على روابط» على الجوال:
     *
     * الموقع ينشر كل حلقة في ملف مستقل (`/trailers/625-01.mp4` … `-10.mp4`)،
     * فالروابط لا تُبنى إلا من HTML صفحة التفاصيل — ولأن `loadLinks` يبدأ
     * بعدها بثوانٍ لا بما يكفي لنافذة قصيرة، كان يجب أن يُعيد الجلب.
     *
     * مهلتنا الأولى كانت ٦٠ ثانية، فالقارئ الذي يتأمل الوصف ثم يضغط «تشغيل»
     * كانت نافذته قد انتهت، فيصير طلباً ثانياً؛ فإن أخفق (تحديد معدّل، شبكة
     * الجوال، انقطاع لحظي) رجع `null` ⇒ `loadLinks` = false ⇒ الواجهة تقول
     * «لم يتم العثور على روابط» وبلا سبب ظاهر للمستخدم.
     *
     * الإصلاح على طبقتين، وكلتاهما لا تغيّر المحتوى إطلاقاً:
     *  ١) نافذة أطول (بيانات دراما منشورة لا تتغيّر أثناء التصفّح).
     *  ②) عند فشل الجلب نرجع للنسخة القديمة إن وُجدت — فإخفاق الشبكة ليس
     *     دليلاً على غياب الروابط، وهو ما كان يرفضها بلا تمييز.
     */
    private suspend fun fetchPage(path: String): String? {
        var stale: String? = null
        synchronized(htmlLock) {
            val e = htmlCache[path]
            if (e != null) {
                if (nowMS() - e.second < CACHE_MS) return e.first
                stale = e.first
            }
        }
        val fresh = try {
            app.get("$mainUrl$path", referer = "$mainUrl/ar/", headers = mapOf("User-Agent" to UA)).text
        } catch (e: Exception) {
            Log.e("HuangguoDrama", "fetchPage FAILED $path : ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (fresh != null) {
            synchronized(htmlLock) {
                // حدّ أعلى للذاكرة: الدراما الواحدة تحتفظ بصفحتها فقط، ومع
                // تصفّح المكتبة (١٦ دراما) يتجاوز العدد ذلك لولا هذا القصّ.
                if (htmlCache.size >= MAX_CACHED) {
                    val oldest = htmlCache.minByOrNull { it.value.second }?.key
                    if (oldest != null) htmlCache.remove(oldest)
                }
                htmlCache[path] = fresh to nowMS()
            }
            return fresh
        }
        stale?.let {
            Log.w("HuangguoDrama", "fetchPage $path failed — using cached copy (${it.length} chars)")
            return it
        }
        return null
    }

    // ---------- قراءة دفعات RSC ----------

    /**
     * يجمع دفعات RSC في نص واحد. كل دفعة `self.__next_f.push([1,"…"])` سلسلة
     * JSON مُهرَّبة، فنحلّ كل واحدة على حدة ثم نُلحق — والنص المُجمَّع يجتاز
     * تحليل JSON عند أول `{` بعد المرساة.
     */
    private fun flightOf(html: String): String {
        val sb = StringBuilder()
        for (m in FLIGHT_RE.findAll(html)) {
            try {
                sb.append(mapper.readTree(m.groupValues[1]).asText())
            } catch (_: Exception) {
            }
        }
        return sb.toString()
    }

    /**
     * موضع نهاية قيمة JSON تبدأ عند [start]، مع احترام النصوص والهروب —
     * فلا تُحسب `{` أو `}` أو `[` داخل نص كجزء من البنية (ووصف الدراما
     * عربي طويل يمرّ هنا). يُرجع ‎-1‎ إن لم تُغلق القيمة.
     */
    private fun endOfValue(s: String, start: Int): Int {
        var depth = 0
        var i = start
        var inStr = false
        var esc = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** أول قيمة JSON ([opener] هو `{` أو `[`) بعد [from]، أو null. */
    private fun valueAt(s: String, from: Int, opener: Char): JsonNode? {
        val start = s.indexOf(opener, if (from < 0) 0 else from)
        if (start < 0) return null
        val end = endOfValue(s, start)
        if (end < 0) return null
        return try {
            mapper.readTree(s.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    private fun firstObject(flight: String, from: Int): JsonNode? = valueAt(flight, from, '{')

    /**
     * ★ لا بدّ من مصفوفة لا كائن: بعد `"items":[` يأتي `[{…},{…}]`، وعدّ
     * الأقواس المعقوفة وحدها يُعيد آخر كائن فقط (أو نصاً غير صالح كـ
     * `{…},{…}`) — فنوازن `[]` و`{}` معاً ونحلّل المصفوفة كاملة.
     */
    private fun firstArray(flight: String, from: Int): List<JsonNode> =
        valueAt(flight, from, '[')?.toList().orEmpty()

    private fun JsonNode.str(key: String): String? =
        get(key)?.takeIf { !it.isNull }?.asText()?.trim()?.ifBlank { null }

    private fun JsonNode.int(key: String): Int? = get(key)?.takeIf { it.isNumber }?.asInt()

    private fun JsonNode.array(key: String): List<JsonNode> {
        val n = get(key)
        return if (n != null && n.isArray) n.toList() else emptyList()
    }

    /**
     * الغلاف المضمون `/covers/d/{id}.jpg` — تحقّقنا أنه 200 image/jpeg لكل
     * المعرّفات (بما فيها `12`). نفضّل ما يصفه الموقع حين يوجد، ونرجع لهذا.
     */
    private fun cover(id: String): String = "$mainUrl/covers/d/$id.jpg"

    private fun abs(u: String): String =
        if (u.startsWith("http://") || u.startsWith("https://")) u else "$mainUrl$u"

    // ---------- البطاقات ----------

    /** بطاقة نظيفة من الصفحة الرئيسية: `{"id":"drama:625",…,"cover":"/covers/d/625.jpg"}`. */
    private fun nodeToCard(n: JsonNode): SearchResponse? {
        val raw = n.str("id") ?: return null
        if (!raw.startsWith("drama:")) return null
        val id = raw.removePrefix("drama:").trim()
        val title = n.str("title") ?: return null
        return newMovieSearchResponse(title, "$mainUrl/ar/detail/$id/", TvType.TvSeries, fix = false) {
            this.posterUrl = n.str("cover")?.let { abs(it) } ?: cover(id)
        }
    }

    /** `<article data-drama-card="625">` — المكتبة/التصنيف/البحث. */
    private fun htmlToCards(html: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (m in CARD_RE.findAll(html)) {
            val id = m.groupValues[1]
            val title = TITLE_RE.find(m.groupValues[2])?.groupValues?.get(1)?.let { decodeEntities(it) }
            if (title.isNullOrBlank()) continue
            out.add(
                newMovieSearchResponse(title, "$mainUrl/ar/detail/$id/", TvType.TvSeries, fix = false) {
                    this.posterUrl = cover(id)
                }
            )
        }
        return out
    }

    // ---------- الفصول ----------

    private val sections = listOf(
        "المكتبة" to "/ar/library/",
        "الرئيسية" to "/ar/",
    )

    override val mainPage =
        mainPageOf(*sections.map { MainPageData(name = it.first, data = it.second) }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val html = fetchPage(request.data) ?: return newHomePageResponse(request.name, emptyList())
            val cards = if (request.data == "/ar/") {
                val f = flightOf(html)
                // ★ `"items":[` لا `"items"` — انظر شرح المرساة في رأس الملف.
                val at = f.indexOf("\"items\":[")
                val items = if (at >= 0) firstArray(f, at) else emptyList()
                items.mapNotNull { nodeToCard(it) }
            } else {
                htmlToCards(html)
            }
            newHomePageResponse(request.name, cards)
        } catch (e: Exception) {
            Log.e("HuangguoDrama", "getMainPage ${request.data}: ${e.message}")
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val html = fetchPage("/ar/search/?keyword=" + URLEncoder.encode(q, "UTF-8"))
            if (html == null) return emptyList() else htmlToCards(html)
        } catch (e: Exception) {
            Log.e("HuangguoDrama", "search: ${e.message}")
            null
        }
    }

    // ---------- التفاصيل ----------

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val id = url.substringAfter("/ar/detail/").substringBefore("/").trim()
            if (id.isEmpty()) return null
            val html = fetchPage("/ar/detail/$id/") ?: return null
            val flight = flightOf(html)

            // ★ الشكلان: مسلسل (`"drama":`) أو إعلان/معاينات (`{"dramaId"`).
            // المرشّح لا يُعتمد على وجود العبارة وحدها — انظر TRAILER_ANCHOR.
            trailerObject(flight)?.let { return loadTrailer(id, html, it) }
            loadSeries(id, html, flight)
        } catch (e: Exception) {
            Log.e("HuangguoDrama", "load: ${e.message}")
            null
        }
    }

    private suspend fun loadSeries(id: String, html: String, flight: String): LoadResponse? {
        val drama = firstObject(flight, flight.indexOf("\"drama\":")) ?: return null
        val title = drama.str("title") ?: return null
        val total = drama.int("episodes")

        val eps = drama.array("episodeList").mapNotNull { e ->
            // ★ نرفض أي رابط غير http: فصول ناقصة تحمل `video: null` أو ""،
            // وإظهارها يعني «لا روابط» أمام المستخدم عند الضغط على فصل بلا فيديو.
            val v = e.str("video") ?: return@mapNotNull null
            if (!v.startsWith("http") && !v.startsWith("/")) return@mapNotNull null
            val n = e.int("n") ?: return@mapNotNull null
            n to e.str("title")
        }
        if (eps.isEmpty()) return null

        val episodes = eps.applyListOptions(episodeLimit())
            .map { (n, label) ->
                newEpisode("$mainUrl/ar/detail/$id/|ep|$n") {
                    this.episode = n
                    this.name = label ?: "الحلقة $n"
                }
            }
            .sortedBy { it.episode }
            .toMutableList()
        if (newestFirst()) episodes.reverse()

        return newTvSeriesLoadResponse(title, "$mainUrl/ar/detail/$id/", TvType.TvSeries, episodes) {
            this.posterUrl = drama.str("cover")?.let { abs(it) } ?: cover(id)
            this.plot = plotOf(drama.str("desc"), total, episodes.size)
            this.tags = tagsOf(html, null)
        }
    }

    /**
     * كائن صفحة الإعلان إن كانت هذه صفحة معاينات: يُشترط أن يُحلَّل وأن يحمل
     * `title` و`src`، وإلا فلا نعتبرها صفحة إعلان (العبارة تظهر في صفحات أخرى).
     */
    private fun trailerObject(flight: String): JsonNode? {
        var from = 0
        while (true) {
            val at = flight.indexOf(TRAILER_ANCHOR, from)
            if (at < 0) return null
            val o = firstObject(flight, at)
            if (o != null && o.str("title") != null && o.str("src") != null) return o
            from = at + 1
        }
    }

    /**
     * صفحة الإعلان (المعرّف `12`): `src` هو الحلقة ١ (مقطع `/trailers/12.mp4`)
     * و`previews[]` معاينات صامتة ١٠ ثوانٍ مرقّمة ٢..١٠.
     */
    private suspend fun loadTrailer(id: String, html: String, t: JsonNode): LoadResponse? {
        val title = decodeEntities(t.str("title") ?: return null)

        // الحلقة ١ هي الفيديو الحقيقي؛ وبعدها تسع «معاينة» صامتة مدتها ١٠
        // ثوانٍ. إطفاء الخيار يُخفي التسع معاً وتبقى الحلقة ١ وحدها.
        val eps = mutableListOf<Triple<Int, String, String>>()
        eps += Triple(1, "الحلقة 1", "tr")
        if (showPreviews()) {
            for (p in t.array("previews")) {
                val n = p.int("n") ?: continue
                if (p.str("src") == null) continue
                eps += Triple(n, "معاينة $n", "tr")
            }
        }

        val episodes = eps.applyListOptions(episodeLimit())
            .map { (n, label, kind) ->
                newEpisode("$mainUrl/ar/detail/$id/|$kind|$n") {
                    this.episode = n
                    this.name = label
                }
            }
            .sortedBy { it.episode }
            .toMutableList()
        if (newestFirst()) episodes.reverse()

        return newTvSeriesLoadResponse(title, "$mainUrl/ar/detail/$id/", TvType.TvSeries, episodes) {
            // ★ `poster` هنا `/covers/d/12-p01.jpg` (لوحة الإعلان)؛ ومع ذلك نفضّل
            // الغلاف المضمون الموحّد لأنه موجود لكل المعرّفات بلا استثناء.
            this.posterUrl = cover(id)
            this.plot = plotOf(
                t.str("description"),
                null,
                episodes.size,
                extra = "المقاطع ٢–١٠ معاينات صامتة مدتها ١٠ ثوانٍ، وليست حلقات كاملة."
            )
            this.tags = tagsOf(html, t)
        }
    }

    private fun plotOf(desc: String?, total: Int?, available: Int, extra: String? = null): String {
        val tail = if (extra != null) extra
        else if (total != null && total > available && available > 0)
            "الحلقات المتاحة الآن: $available من $total."
        else null
        return listOfNotNull(desc, tail).joinToString("\n\n")
    }

    /**
     * تصنيفات كما يعرضها الموقع. ★ لا نقرأ `drama.tags` من الـflight: هي رموز
     * داخلية (`dushi`، `dananzhu`) تظهر للمستخدم كما هي. تسمية الموقع
     * (`Chinese`) في DOM، وفي كائن الإعلان في `tags[].label`.
     */
    private fun tagsOf(html: String, trailer: JsonNode?): List<String> {
        if (trailer != null) {
            val labels = trailer.array("tags").mapNotNull { it.str("label") }
            if (labels.isNotEmpty()) return labels
        }
        return TAG_RE.findAll(html).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    // ---------- التشغيل ----------

    /**
     * ★ كل حلقة رابط mp4 واحد بلا بدائل ولا جودات (متحقَّق: ملف واحد 3–171 ميغا)،
     * فلا ترتيب ولا نسخة احتياطية — ولا نُبقي أي خيار بلا مفعول.
     *
     * dataUrl هو ما يصل إلى loadLinks: "<id>|ep|<n>" أو "<id>|tr|<n>".
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            Log.d("HuangguoDrama", "loadLinks ENTER data=[$data]")
            val p = data.split("|")
            if (p.size < 3) {
                Log.e("HuangguoDrama", "loadLinks BAD data (parts=${p.size})")
                return false
            }
            val id = p[0].trim()
            val kind = p[1].trim()
            val n = p[2].trim().toIntOrNull()
            if (id.isEmpty() || n == null) {
                Log.e("HuangguoDrama", "loadLinks BAD id/n id=[$id] n=[${p[2]}]")
                return false
            }

            val html = fetchPage("/ar/detail/$id/")
            if (html == null) {
                Log.e("HuangguoDrama", "loadLinks NO HTML for $id")
                return false
            }
            val flight = flightOf(html)
            Log.d("HuangguoDrama", "loadLinks id=$id kind=$kind n=$n htmlLen=${html.length} flightLen=${flight.length}")

            val video: String? = when (kind) {
                "ep" -> {
                    val at = flight.indexOf("\"drama\":")
                    val drama = if (at >= 0) firstObject(flight, at) else null
                    drama?.array("episodeList")?.firstOrNull { (it.int("n") ?: -1) == n }?.str("video")
                }

                "tr" -> {
                    val t = trailerObject(flight)
                    if (n == 1) t?.str("src")
                    else t?.array("previews")?.firstOrNull { (it.int("n") ?: -1) == n }?.str("src")
                }

                else -> null
            }
            if (video.isNullOrBlank()) {
                Log.e(
                    "HuangguoDrama",
                    "loadLinks NO VIDEO id=$id kind=$kind n=$n (dramaAt=${flight.indexOf("\"drama\":")} trailerAt=${flight.indexOf(TRAILER_ANCHOR)})"
                )
                return false
            }

            val url = abs(video)
            Log.d("HuangguoDrama", "loadLinks EMIT $url")
            callback(
                newExtractorLink(source = name, name = "MP4", url = url) {
                    this.type = ExtractorLinkType.VIDEO
                    this.quality = getQualityFromName("720p")
                    this.referer = "$mainUrl/ar/detail/$id/"
                    // ★ «تأكيد قبل التشغيل» (افتراضياً false ⇒ لا أثر):
                    // لأن `loadLinks` suspend فـ`withContext(Dispatchers.Main)`
                    // يضمن أن `toast` يُعرض على الخيط الرئيسي لا أن يُهمَل.
                    if (confirmPlay()) {
                        val ctx = appContext
                        if (ctx != null) withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "جارٍ فتح $name…", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            true
        } catch (e: Exception) {
            Log.e("HuangguoDrama", "loadLinks EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }
}
