package com.directdrama.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.FormatTag
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.TreeMap

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val TAG = "DirectDrama"

/** صفوف المنصات (مفتاح ← عنوان الصفّ). `internal` لإعادة استخدامها في ورقة
 *  الإعدادات (قائمة «إخفاء الأقسام») — مصدر واحد للحقيقة لا نسختان تتفرّعان.
 *  قُيست عناوينها من `/ar/platform` نفسها: «مسلسلات {الاسم} القصيرة». */
internal val DDR_PLATFORM_ROWS: List<Pair<String, String>> = listOf(
    "pinedrama" to "مسلسلات PineDrama القصيرة",
    "reelshort" to "مسلسلات ReelShort القصيرة",
    "flextv" to "مسلسلات FlexTV القصيرة",
    "shortmax" to "مسلسلات ShortMax القصيرة",
    "moboreels" to "مسلسلات MoboReels القصيرة",
    "netshort" to "مسلسلات NetShort القصيرة",
    "dramabite" to "مسلسلات DramaBite القصيرة",
    "goodshort" to "مسلسلات GoodShort القصيرة",
    "flickreels" to "مسلسلات FlickReels القصيرة",
    "freereels" to "مسلسلات FreeReels القصيرة",
    "rapidtv" to "مسلسلات RapidTV القصيرة",
    "dotdrama" to "مسلسلات DotDrama القصيرة",
    "radreels" to "مسلسلات RadReels القصيرة",
    "meloshort" to "مسلسلات MeloShort القصيرة",
    "shortswave" to "مسلسلات ShortsWave القصيرة",
    "fundrama" to "مسلسلات FunDrama القصيرة",
    "dramabox" to "مسلسلات DramaBox القصيرة",
    "playlet" to "مسلسلات Playlet القصيرة",
    "flareflow" to "مسلسلات FlareFlow القصيرة",
    // ★ أُضيفت 2026-10-10 من `/ar/platform` نفسه (قِيس: الموقع يسرد **30**
    //   منصة وهذه الثلاث كانت ناقصة — العدد والاسم مأخوذان من الصفحة حرفياً:
    //   VibeShort 19 مسلسلاً، Melolo وLuminaReels مسلسل واحد لكلٍّ). ووُضعت
    //   في موضعها بحسب ترتيب الموقع (عدد المسلسلات تنازلياً) لا في الذيل.
    "vibeshort" to "مسلسلات VibeShort القصيرة",
    "bonustv" to "مسلسلات BonusTV القصيرة",
    "shotshort" to "مسلسلات ShotShort القصيرة",
    "microdrama" to "مسلسلات MicroDrama القصيرة",
    "vigloo" to "مسلسلات Vigloo القصيرة",
    "dramawave" to "مسلسلات DramaWave القصيرة",
    "starshort" to "مسلسلات StarShort القصيرة",
    "stardusttv" to "مسلسلات StarDust TV القصيرة",
    "melolo" to "مسلسلات Melolo القصيرة",
    "luminareels" to "مسلسلات LuminaReels القصيرة",
    "snackshort" to "مسلسلات SnackShort القصيرة",
)

/**
 * صفّا الواجهة الأمامية — **قبل** صفوف المنصات (طلب المستخدم 2026-10-08:
 * «أضِف أقسام الشاشة بجانب المنصات» ثم «بالمقدَّمة قبل المنصات»). كلاهما
 * صفٌّ عابر للمنصات يوفّره الموقع نفسه: `/ar/popular` و`/ar/new-releases`.
 *
 * قياس 2026-10-08: ٣٦ بطاقة في كلٍّ منهما، وبطاقاتهما من **شكل بطاقة المنصة
 * ذاتها** (`img[alt]` بقوسين و`aria-label` بعد فاصلة تحمل عدد الحلقات) فالحلّ
 * واحد لا حلّان؛ والترقيم كصفحة المنصة: `/{page}` (قِيس `/ar/popular/2`
 * يعيد ٣٦ بطاقة أخرى).
 */
internal const val DDR_FRONT_POPULAR = "ddr-front-popular"
internal const val DDR_FRONT_NEW = "ddr-front-new"

internal val DDR_FRONT_ROWS: List<Pair<String, String>> = listOf(
    DDR_FRONT_POPULAR to "الأكثر رواجًا",
    DDR_FRONT_NEW to "الأحدث إضافة",
)

/** `/api/series/suggest` — عشرة نتائج بلا ترقيم صفحات. */
private data class SuggestItem(
    val id: Long? = null,
    val slug: String? = null,
    val title: String? = null,
    @JsonProperty("coverKey") val coverKey: String? = null,
    val episodes: Int? = null,
    val year: Int? = null,
)

private data class SuggestResponse(val items: List<SuggestItem>? = null)

/** `/api/stream/{id}` — استجابة واحدة تحمل الرابط وترجماتها. */
private data class StreamSubtitle(
    val lang: String? = null,
    val label: String? = null,
    val url: String? = null,
    val default: Boolean? = null,
)

private data class StreamRendition(
    val url: String? = null,
    val height: Int? = null,
    val name: String? = null,
    val bandwidth: Long? = null,
)

private data class StreamResponse(
    @JsonProperty("episodeId") val episodeId: Long? = null,
    val type: String? = null,
    val url: String? = null,
    val renditions: List<StreamRendition>? = null,
    val subtitles: List<StreamSubtitle>? = null,
    /** بصمة المصدر — تُرسَل في `?failed=` لطلب مصدرٍ بديل (آلية الموقع نفسه). */
    val sourceTag: String? = null,
)

/** `self.__next_f.push([1,"…"])` — الدفعة ١ نصٌّ مُهرَّب يحمل كل الحلقات بمعرّفاتها. */
private val FLIGHT_RE =
    Regex("""self\.__next_f\.push\(\[1,("(?:[^"\\]|\\.)*")\]\)""", RegexOption.DOT_MATCHES_ALL)

/**
 * أنماط معرّف الحلقة داخل دفعة الطيران — تُجرَّب بالترتيب.
 *
 * الأول هو الدقيق المقيس (`{"id":123,"number":4}`)، والبديلان يقبلان ترتيب
 * المفاتيح معكوساً أو حقولاً بينهما، والرابع يقبل `"number"` كنصّ. قِيس على
 * الهاتف 2026-10-10 أن صفحةً سليمة قد لا تطابق الأول فتُقرأ «لا يوجد روابط».
 * في الأنماط الأربعة **المجموعة 1 هي المعرّف** دائماً.
 */
private fun epIdRes(ep: Int): List<Regex> = listOf(
    Regex("""\{"id":(\d+),"number":$ep[,}]"""),
    Regex("""\{"id":(\d+)[^{}]{0,200}?"number":$ep[^{}]{0,40}?\}"""),
    Regex("""\{"number":$ep[^{}]{0,200}?"id":(\d+)[^{}]{0,40}?\}"""),
    Regex("""\{"id":(\d+),"number":"$ep"[,}]"""),
)

// أسماء لغاتٍ يرسلها الموقع بحروفٍ محلّيةّ لا تعرفها مكتبة التطبيق ولا رمز ISO
// يُشتقّ منها (قِيس على cloudstream.jar: `getLangTag()` يرجع لها null في المسارين
// فيبقى المسار بلا اسم). ما عداها يتولّاه `SubtitleHelper` نفسه.
private val NATIVE_SUB_LANG = mapOf(
    "日本語" to "ja",
    "繁體中文" to "zh",
    "简体中文" to "zh",
    "中文" to "zh",
    "한국어" to "ko",
    "हिन्दी" to "hi",
    "हिंदी" to "hi",
)

/**
 * تحويل تسمية اللغة إلى الرمز الذي يقبله المشغّل. القاعدة مقيسة لا مفترضة:
 * `SubtitleFile.getLangTag()` = `fromCodeToLangTagIETF(lang)` وإلاّ
 * `fromLanguageToTagIETF(lang, true)`؛ والتطبيق يشتقّ «اليابانية» من الرمز نفسه،
 * فالرمز هو المُدخل والتسمية العربية هي المخرج — لا تُمرَّر التسمية كما هي.
 */
private fun normalizeSubLang(raw: String): String? {
    if (raw.isEmpty()) return null
    if (SubtitleHelper.fromCodeToLangTagIETF(raw) != null) return raw
    NATIVE_SUB_LANG[raw.lowercase()]?.let { return it }
    return SubtitleHelper.fromLanguageToTagIETF(raw, true)
}

class DirectDramaProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "DirectDrama"
    override var mainUrl = "https://directdrama.com"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries)

    // ★ كل منصات الموقع (27) صفّاً في الصفحة الرئيسية — من `DDR_PLATFORM_ROWS`
    //   (مصدر واحد للحقيقة يشترك مع ورقة الإعدادات). تُبنى مرّة واحدة ثم
    //   يقصّها `mainPage` حسب الإعدادات.
    private val allPlatformRows = mainPageOf(*DDR_PLATFORM_ROWS.toTypedArray())

    // ★ صفّا الواجهة الأمامية — من `DDR_FRONT_ROWS` (نفس المصدر الذي تشترك
    //   معه ورقة الإعدادات في قائمة «إخفاء الأقسام»). يوضعان **قبل** المنصات.
    private val frontRows = mainPageOf(*DDR_FRONT_ROWS.toTypedArray())

    // ★ ذاكرة صفوف الواجهة (في الذاكرة لا على القرص): صفحة المنصة ~450 كيلوبايت
    //   والموقع بطيء (قِيس `SocketTimeoutException` على صفحات المسلسل)، وصفٌّ
    //   واحد فاشل يُرى «واجهةً ناقصة» لأن التطبيق ينتظر كل الصفوف. الصفّ الناجح
    //   يُحفَظ ١٠ دقائق فيظهر كاملاً في الزيارة التالية.
    //   ⚠️ لا يُحفَظ إلا صفٌّ **غير فارغ** — نتيجةٌ فارغة مخبَّأة تُخفي الواجهة
    //   كلها بلا خطأ (درسٌ مقيس في [[plugin-empty-fetch-hides-everything]]).
    private val rowCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
    private val rowCacheMs = 10 * 60 * 1000L

    // ★ خصائص ديناميكية: تُقرأ مع كل رسم للواجهة، فالمفاتيح من ورقة الإعدادات
    //   تُطبَّق بلا إعادة تشغيل. `hasMainPage=false` يُخفي المصدر من الصفحة
    //   الرئيسية كليّاً.
    override val hasMainPage: Boolean
        get() = prefs?.getBoolean(DirectDramaSettingsBottomSheet.KEY_SHOW_HOME, true) != false

    // ★ الإخفاء الجزئي: مجموعة مفاتيح الأقسام المخفية من ورقة الإعدادات
    //   (`ddr_hidden_rows`) — كل قسم يُحدَّد بالظهور أو الإخفاء على حدة.
    //   القيم = مفاتيح `MainPageData.data` نفسها: صفّا الواجهة الأمامية
    //   (`DDR_FRONT_ROWS`) ثم مفاتيح المنصات الـ27. مجموعة خالية = الكل ظاهر.
    private fun hiddenRows(): Set<String> =
        prefs?.getStringSet(DirectDramaSettingsBottomSheet.KEY_HIDDEN_ROWS, null) ?: emptySet()

    override val mainPage: List<MainPageData>
        get() {
            val hidden = hiddenRows()
            // صفّا الواجهة الأمامية أوّلاً — طلب المستخدم 2026-10-08:
            // «بالمقدَّمة قبل المنصات».
            val front = if (showFront()) frontRows.filter { it.data !in hidden } else emptyList()
            if (!showPlatforms()) return front
            // الإخفاء يسبق حدّ العدد كي يبقى المطلوب ظاهراً كاملاً.
            // الافتراضي 18 لا «كل الصفوف»: التطبيق ينتظر **كل** الصفوف قبل رسم
            // أول بطاقة، وكل صف هنا صفحة HTML كاملة (~440 كيلوبايت) — 29 صفّاً
            // تعني ~13 ميغابايت و10 ثوانٍ قبل أول رسم. من يستحبّ كل شيء يختاره
            // من الإعدادات.
            val rows = allPlatformRows.filter { it.data !in hidden }
            val raw = prefs?.getString(DirectDramaSettingsBottomSheet.KEY_HOME_ROWS, "18") ?: "18"
            val n = raw.toIntOrNull() ?: return front + rows
            return front + (if (n in 1 until rows.size) rows.take(n) else rows)
        }

    private fun showFront(): Boolean =
        prefs?.getBoolean(DirectDramaSettingsBottomSheet.KEY_SHOW_FRONT, true) != false

    private fun showPlatforms(): Boolean =
        prefs?.getBoolean(DirectDramaSettingsBottomSheet.KEY_SHOW_PLATFORMS, true) != false

    private fun showSubs(): Boolean =
        prefs?.getBoolean(DirectDramaSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) != false

    private fun descOrder(): Boolean =
        prefs?.getString(DirectDramaSettingsBottomSheet.KEY_EPISODE_ORDER, "as_is") == "desc"

    // ── سياق المشاهدة (`/api/stream/*`) ──────────────────────────────────────
    //
    // قِيس 2026-10-10: الموقع صار يرفض `/api/stream/{id}` بـ`403
    // {"error":{"message":"viewing_context_required"}} ما لم يكن «سياق مشاهدة»
    // مُنشأً مسبقاً بطلب صفحةٍ من الموقع. مشغّل الموقع نفسه يبنيه بطلب
    // `fetch(window.location.pathname,{method:"HEAD",credentials:"same-origin"})`
    // ثم يعيد المحاولة (مقيس في chunk الـwatch: الفرع `0===n&&403===f&&
    // "viewing_context_required"===h`).
    //
    // `app` في CloudStream **بلا cookie jar** (مقيسٌ في Dramadunyam) فلا تُحفَظ
    // الكوكيز تلقائياً بين الطلبات — ولهذا كان كل `/api/stream` يردّ 403 على
    // الهاتف حتى بعد نجاح جلب صفحة الحلقة وقراءة معرّفها. العلاج ثلاث طبقات:
    //   (١) نحفظ كوكيز كل استجابة نراها (`adoptCookies`)،
    //   (٢) نرسلها في ترويسة `Cookie` على طلب البثّ (`cookieHeaders`)،
    //   (٣) وإن عاد 403 فنسخّن السياق من صفحة الحلقة ثم نعيد المحاولة.
    //
    // قفلٌ لأن الطلبين (البثّ والترجمة/الجودة) قد يتداخلان؛ لا نريد ثلاثة
    // تسخينات متوازية تُنزل ثلاثة طلبات صفحات ٢٢٠ كيلوبايت معاً.
    private val viewCookieLock = Mutex()
    // خريطة متزامنة: `adoptCookies` تُنادى من مسارات متوازية (صفوف الرئيسية،
    // البثّ، التسخين) بلا القفل — `LinkedHashMap` كانت تُفسَد تحت التوازي.
    private val viewCookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * يحفظ كوكيز استجابة (الاسم=القيمة) — **دمجٌ لا استبدال**: كوكي السياق
     * `dd_view` يأتي من صفحة الحلقة، وأي استجابة لاحقة قد تُسقط كوكي `__cf_bm`
     * أو غيره فقط؛ لو استبدلنا لضاع `dd_view` وعاد 403.
     */
    private fun adoptCookies(r: com.lagradost.nicehttp.NiceResponse) {
        val c = r.cookies
        if (c.isEmpty()) return
        var changed = false
        for ((k, v) in c) {
            if (v.isBlank()) continue
            if (viewCookies[k] != v) {
                viewCookies[k] = v
                changed = true
            }
        }
        if (changed) Log.d(TAG, "adopted cookies n=${viewCookies.size} has_dd_view=${viewCookies.containsKey("dd_view")}")
    }

    /** سياق المشاهدة كسلسلة كوكيز (`k=v; k=v`) — فارغة إن لم يُبنَ بعد. */
    private fun cookieString(): String =
        viewCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** ترويسة `Cookie` من السياق المُجمَّع — فارغة إن لم يُبنَ بعد. */
    private fun cookieHeaders(): Map<String, String> =
        cookieString().takeIf { it.isNotBlank() }?.let { mapOf("Cookie" to it) } ?: emptyMap()

    /**
     * يُنشئ/يُنعش سياق المشاهدة بطلب صفحة الحلقة — نفس ما يفعله مشغّل الموقع.
     * `HEAD` أوّلاً (رخيص، ولا يُحمّل ٢٢٠ كيلوبايت لكل حلقة)؛ فإن لم تُسقط
     * الرؤوس كوكيز (بعض الخوادم لا تفعل على HEAD) نجلب الصفحة `GET`.
     */
    private suspend fun primeViewingContext(pageUrl: String) {
        viewCookieLock.withLock {
            try {
                adoptCookies(app.head(pageUrl, referer = mainUrl))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prime HEAD FAILED ${e.javaClass.simpleName}")
            }
            if (!viewCookies.containsKey("dd_view")) {
                try {
                    adoptCookies(app.get(pageUrl, referer = mainUrl))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "prime GET FAILED ${e.javaClass.simpleName}")
                }
            }
            Log.d(TAG, "primed viewing context n=${viewCookies.size} has_dd_view=${viewCookies.containsKey("dd_view")}")
        }
    }

    /** رابط مطلق — الموقع يرسل مسارات نسبية (`/img/…`، `/subs/…`) والمشغّل لا يضيف mainUrl. */
    private fun abs(u: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> mainUrl + u
        else -> "$mainUrl/$u"
    }

    /** المعرّف العربي في الاقتراحات يُرمَّز كما ترمّزه روابط الموقع نفسها. */
    private fun seriesUrl(slug: String): String =
        "$mainUrl/ar/series/" + URLEncoder.encode(slug, "UTF-8").replace("+", "%20")

    private fun SuggestItem.toSearch(): SearchResponse? {
        val t = title?.takeIf { it.isNotBlank() } ?: return null
        val s = slug?.takeIf { it.isNotBlank() } ?: return null
        return newTvSeriesSearchResponse(t, seriesUrl(s), TvType.TvSeries) {
            posterUrl = coverKey?.takeIf { it.isNotBlank() }?.let { "$mainUrl/img/covers/$it/480.webp" }
            episodes = this@toSearch.episodes?.takeIf { it > 0 }
            this.year = this@toSearch.year
        }
    }

    // ── الصفحة الرئيسية: رفّ لكل منصة، 36 بطاقة في الصفحة ────────────────────
    //
    // بطاقة المنصة رابطٌ إلى `/ar/series/…` **بلا** `/episode-` (ذلك رابط «ابدأ
    // الحلقة ١» العائم)، وعنوانه في `img[alt]` بين قوسين مِعْتَتَبَتَيْن، وعدد
    // حلقاته في `aria-label` بعد الفاصلة. الصفحة تحتوي ٧٢ رابطاً لـ36 مسلسلاً
    // (بطاقة + زر تشغيل لكلٍّ منها) فنُزيل تكرار الرابط قبل البناء.
    //   صفّا الواجهة الأمامية (`DDR_FRONT_ROWS`) يمرّان بنفس المحلّل: قياس
    // 2026-10-08 وجد بطاقة `/ar/popular` و`/ar/new-releases` من الشكل ذاته
    // حرفياً (٣٦ بطاقة، `alt` بقوسين، `aria-label` بعد فاصلة، والعدّاد في
    // 35 و33 من 36). والترقيم `/{page}` كصفحة المنصة.
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = when {
                request.data == DDR_FRONT_POPULAR ->
                    if (page <= 1) "$mainUrl/ar/popular" else "$mainUrl/ar/popular/$page"
                request.data == DDR_FRONT_NEW ->
                    if (page <= 1) "$mainUrl/ar/new-releases" else "$mainUrl/ar/new-releases/$page"
                page <= 1 -> "$mainUrl/ar/platform/${request.data}"
                else -> "$mainUrl/ar/platform/${request.data}/$page"
            }
            val doc = fetchDoc(url, attempts = 2)
            if (doc == null) {
                // ★ فشل الجلب (503 من صندوق عقوبة الموقع) — لا نُعيد صفاً فارغاً
                //   لأنّ التطبيق ينتظر كل الصفوف، فصفٌّ واحد بلا نتيجة يمحو
                //   «الواجهة الرئيسية» كلها. نُعيد آخر نتيجة ناجحة إن وُجدت.
                val cached = rowCache[url]?.takeIf { System.currentTimeMillis() - it.first < rowCacheMs }
                if (cached != null) {
                    Log.w(TAG, "row FETCH FAILED — using cache data=${request.data} items=${cached.second.size}")
                    return newHomePageResponse(request.name, cached.second)
                }
                Log.w(TAG, "row FETCH FAILED data=${request.data} url=$url (no cache)")
                return null
            }
            val seen = HashSet<String>()
            val items = doc.select("""a[href^="/ar/series/"]""").mapNotNull { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty() || href.contains("/episode-")) return@mapNotNull null
                if (!seen.add(href)) return@mapNotNull null

                val aria = a.attr("aria-label").trim()
                val alt = a.selectFirst("img")?.attr("alt").orEmpty()
                val title = Regex("""«([^»]+)»""").find(alt)?.groupValues?.get(1)?.trim()
                    ?: aria.substringBefore("،").trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val count = Regex("""(\d+)\s*حلقة""").find(aria)?.groupValues?.get(1)?.toIntOrNull()
                val poster = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst("img")?.attr("srcset")?.substringBefore(",")?.trim()?.substringBefore(" ")

                newTvSeriesSearchResponse(title, abs(href), TvType.TvSeries) {
                    posterUrl = poster?.let { abs(it) }
                    episodes = count
                }
            }
            // ★ سجلٌّ لكل صفّ: التطبيق ينتظر **كل** الصفوف قبل رسم أول بطاقة،
            //   فصفٌّ واحد فاشل يُرى «واجهةً ناقصة» بلا سبب ظاهر. هذا السطر
            //   يقول أيّ صفٍّ عاد فارغاً وبأي رمز HTTP — يُقرأ من adb logcat.
            if (items.isEmpty()) {
                Log.w(TAG, "row EMPTY data=${request.data} page=$page url=$url")
                null
            } else {
                Log.d(TAG, "row data=${request.data} page=$page items=${items.size}")
                // ★ نخزّن النتيجة الناجحة فقط (لا نخزّن فراغاً أبداً — درس
                //   «الفراغ المخزّن يمحو كل شيء»): الحاجة إليها هي أن ينجو
                //   الصفّ من انقطاع عابر في الجلب التالي.
                rowCache[url] = System.currentTimeMillis() to items
                newHomePageResponse(request.name, items)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // الإلغاء ليس فشل شبكة (قِيس: NartoDrama 2026-10-06)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED page=$page data=${request.data}", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val r = app.get("$mainUrl/api/series/suggest?locale=ar&q=$q", referer = "$mainUrl/ar/search")
            if (!r.isSuccessful) return null
            mapper.readValue(r.text, SuggestResponse::class.java).items.orEmpty()
                .mapNotNull { it.toSearch() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED q=$query", e)
            null
        }
    }

    /**
     * كل نصوص `self.__next_f.push([1,"…"])` مُفكّكة ومُركّبة — فيها
     * `"episodes":[{"id":2391326,"number":1,…}]` أي معرّف كل حلقة، وهو ما
     * يطلبه `/api/stream/` ولا يظهر في صفحة المسلسل إطلاقاً.
     */
    private fun flightBlob(html: String): String {
        val sb = StringBuilder()
        for (m in FLIGHT_RE.findAll(html)) {
            try {
                sb.append(mapper.readTree(m.groupValues[1]).asText())
            } catch (_: Exception) {
            }
        }
        return sb.toString()
    }

    /** كتلة JSON-LD الأولى التي تحمل `@type=TVSeries` (قد تكون داخل `@graph`). */
    private fun jsonLdSeries(doc: Document): JsonNode? {
        for (el in doc.select("script[type=application/ld+json]")) {
            val txt = el.data().ifEmpty { el.html() }
            if (txt.isBlank()) continue
            val root = try {
                mapper.readTree(txt)
            } catch (_: Exception) {
                continue
            }
            val nodes = if (root.isArray) root else root.path("@graph").takeIf { it.isArray } ?: root
            for (n in nodes) {
                if (n.isObject && n.path("@type").asText() == "TVSeries") return n
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = fetchDoc(url) ?: return null
            val ld = jsonLdSeries(doc)

            // ★ الأسماء الأنظف من JSON-LD (`name` بلا «مسلسل … كامل – N حلقة»)،
            //   وحدها احتياط إلى h1 وعنوان الصفحة المُنقّى.
            val title = ld?.path("name")?.asText()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("#series-title")?.let { h1 ->
                    h1.select("span.sr-only, span.font-sans").remove()
                    h1.text().trim().takeIf { it.isNotBlank() }
                }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" – ")
                ?: return null

            val poster = ld?.path("image")?.let { img ->
                if (img.isObject) img.path("url").asText(null) else img.asText(null)
            }?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            val description = ld?.path("description")?.asText()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("p.clamp-pad")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            val tags = ld?.path("genre")?.let { g ->
                when {
                    g.isArray -> g.mapNotNull { it.asText(null)?.takeIf { s -> s.isNotBlank() } }
                    g.isTextual -> listOfNotNull(g.asText().takeIf { it.isNotBlank() })
                    else -> null
                }
            } ?: emptyList()

            // عدد الحلقات: JSON-LD هو المرجع (`numberOfEpisodes`)، واحتياطاً
            // أوّل رقم في شارة «N حلقة» داخل صفحة المسلسل.
            val total = ld?.path("numberOfEpisodes")?.takeIf { it.isIntegralNumber }?.asInt()
                ?: Regex("""(\d+)\s*حلقة""").find(doc.select("ul[aria-label] li").firstOrNull()?.text().orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull()

            // قاعدة بناء روابط الحلقات: صفحة المسلسل نفسها، وما قبل `/episode-`
            // إن جاء الرابط من حلقة.
            val base = url.substringBeforeLast("/episode-").trimEnd('/')

            val eps = TreeMap<Int, String>()
            ld?.path("episode")?.takeIf { it.isArray }?.forEach { e ->
                val n = e.path("episodeNumber").takeIf { it.isIntegralNumber }?.asInt() ?: return@forEach
                val u = e.path("url").asText("").takeIf { it.isNotBlank() }?.let { abs(it) } ?: "$base/episode-$n"
                eps[n] = u
            }
            doc.select("""a[href*="/episode-"]""").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty()) return@forEach
                val n = Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                eps[n] = abs(href)
            }
            // نُكمل الناقص من العدّاد: JSON-LD يحصر مصفوفة الحلقات في ١٠، وبعض
            // الصفحات قد تحذف روابط، فالأعداد الناقصة تُبنى على النمط نفسه.
            if (total != null && total in 1..500) {
                for (n in 1..total) if (!eps.containsKey(n)) eps[n] = "$base/episode-$n"
            }

            val ordered = if (descOrder()) eps.entries.toList().reversed() else eps.entries.toList()
            val episodes = ordered.map { (n, u) ->
                newEpisode(u) {
                    episode = n
                    name = "الحلقة $n"
                }
            }
            Log.d(TAG, "load title=$title episodes=${episodes.size} total=$total")

            if (episodes.isEmpty()) return null
            // الغلاف قد يأتي نسبياً من JSON-LD (`image: "/img/…"`) والمشغّل لا يضيف
            // mainUrl، فنبنيه مطلقاً. وخلفية النتيجة (`backgroundPosterUrl`) كانت
            // تبقى null دائماً فما رُسم تمويهُ أعلى صفحة التفاصيل.
            val posterAbs = poster?.takeIf { it.isNotBlank() }?.let { abs(it) }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = posterAbs
                backgroundPosterUrl = posterAbs
                plot = description
                this.tags = tags
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED $url", e)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val epUrl = when {
                data.startsWith("http") -> data
                data.startsWith("/") -> mainUrl + data
                else -> "$mainUrl/$data"
            }
            val ep = Regex("""episode-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return false

            // 1) صفحة الحلقة → نص الطيران → معرّف الحلقة (لا يظهر في أي مكان آخر).
            //    صفحةٌ واحدة تفشل في الشبكة تعني «لا يوجد روابط» لحلقة سليمة، فمحاولة ثانية.
            val html = fetchHtml(epUrl) ?: run {
                Log.e(TAG, "loadLinks episode page FAILED ep=$ep url=$epUrl")
                return false
            }
            val blob = flightBlob(html)
            // ★ معرّف الحلقة — النمط الدقيق أولاً ثم بدائل. قِيس على الهاتف
            //   2026-10-10 (`شغف متأجج` حلقة 1): الصفحة تُجلب بنجاح والدفعة
            //   موجودة، لكن النمط الدقيق لا يطابق — فترتيب المفاتيح أو وجود
            //   حقولٍ بينهما يختلف من صفحةٍ لأخرى. البديلان يسمحان بأي ترتيب
            //   وبأي حقولٍ بين `id` و`number` ضمن الكائن نفسه (`[^{}]{0,N}`).
            val id = epIdRes(ep).firstNotNullOfOrNull { re ->
                re.find(blob)?.groupValues?.get(1)?.toLongOrNull()
            } ?: run {
                // نشهد ما حول `"number":$ep` كي يُشخَّص الشكل الجديد بلا تخمين.
                val at = blob.indexOf("\"number\":$ep")
                Log.e(TAG, "loadLinks no episode id ep=$ep url=$epUrl window=${blob.substring(maxOf(0, at - 90), minOf(blob.length, maxOf(0, at) + 90)).replace('\n', ' ')}")
                return false
            }

            // 2) `/api/stream/` يرفض بلا Referer (403 مقيس) — نُرسله كصفحة الحلقة.
            //    وقد يردّ `503 source_busy` أو 429 مؤقتاً: فشلٌ واحد هنا يعرض
            //    «لا يوجد روابط» لحلقة تعمل، فنعيد على الأخطاء المؤقتة.
            val primary = fetchStream(id, epUrl, failedTag = null) ?: return false

            // 3) فحص الحيولة: حين يموت المضيف (قياس 2026-10-08:
            //    `flareflow.dotkosong.web.id` = NXDOMAIN حقيقي و`videotv.vividshort.com`
            //    حيّ) نطلب مصدراً بديلاً بالآلية نفسها التي يستخدمها موقع DirectDrama
            //    في صفحات JSّه: `?locale=ar&refresh=1&failed=<sourceTag>`. البديل الحيّ
            //    يُبثَّ **أوّلاً** كي يسبقه المشغّل (هو يختار الرابط الأول دائماً).
            val seenSubUrl = HashSet<String>()
            val seenSubLang = HashSet<String>()
            val primaryUrl = primary.url?.trim().orEmpty().takeIf { it.isNotBlank() }?.let { abs(it) }
            if (primaryUrl == null) {
                Log.e(TAG, "stream empty url ep=$ep")
                return false
            }
            if (probeHealth(primaryUrl) == Health.DEAD) {
                val alt = primary.sourceTag?.takeIf { it.isNotBlank() }?.let { fetchStream(id, epUrl, it) }
                val altUrl = alt?.url?.trim().orEmpty().takeIf { it.isNotBlank() }?.let { abs(it) }
                if (alt != null && altUrl != null && altUrl != primaryUrl &&
                    probeHealth(altUrl) == Health.ALIVE
                ) {
                    // اسم السيرفر يدخل اسم الرابط من `serverLabel`؛ فإن اتّفق
                    // سيرفر البديل والأساسي بقي الصفّان متطابقين، فنميّز البديل
                    // بوسمٍ صريح. (مقيس: البديل غالباً من مضيفٍ آخر — فحينها لا
                    // حاجة للوسم ويكفي اسم السيرفر.)
                    val sameServer = serverLabel(altUrl) == serverLabel(primaryUrl)
                    Log.i(TAG, "loadLinks alternate alive ep=$ep ${altUrl.take(70)}")
                    emitStream(alt, ep, seenSubUrl, seenSubLang, subtitleCallback, callback,
                        labelSuffix = if (sameServer) " · بديل" else "")
                    emitStream(primary, ep, seenSubUrl, seenSubLang, subtitleCallback, callback)
                    return true
                }
                // لا بديل حيّ: يبقى الأساسي. «تعذّر الفحص» ليس حُكم إسقاط — وإلا
                // حُوِّلت حلقةٌ تعمل إلى «لا يوجد روابط».
                Log.i(TAG, "loadLinks primary DEAD ep=$ep alt=${altUrl ?: "none"} — emit primary anyway")
            }
            emitStream(primary, ep, seenSubUrl, seenSubLang, subtitleCallback, callback)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FATAL data=$data", e)
            false
        }
    }

    /** حكم فحص الرابط — ميتٌ وتعذّر الفحص منفصلان عمداً (قِيس NartoDrama). */
    private enum class Health { ALIVE, DEAD, UNKNOWN }

    /**
     * مهلة قصيرة: رابط ميت يُمهَل٢٫٥ ثانية لا عشر — وإلا طالت مهلة `loadLinks`
     * نفسها حتى يعرض التطبيق «لا روابط» (قِيس Mosalsaly `probeMedia`).
     * بلا `Range`: رأس Range يقلب الحُكم على بعض المضيفات (قِيس joyreels).
     * نقرأ رأساً صغيراً من الجسد ثم نغلق — لا تحميل ملف كامل.
     */
    private fun probeHealth(url: String): Health = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 2500
        conn.readTimeout = 3500
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Referer", mainUrl)
        // مسارات الوكيل `directdrama.com/px/…` محميّةٌ ببوابة سياق المشاهدة أيضاً:
        // بلا الكوكي تردّ 403 (مقيس) فيُحكم على رابطٍ حيٍّ بالموت ظلماً.
        cookieString().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        val code = try {
            conn.responseCode
        } finally {
            // نقرأ رأساً صغيراً فقط (64 بايت) ثم نقطع: الجسم قد يكون mp4 بـ٩ ميغابايت.
            runCatching {
                conn.inputStream?.use { ins -> ins.read(ByteArray(64)) }
            }
            conn.disconnect()
        }
        val verdict = when {
            code in 200..399 -> Health.ALIVE
            // حُكم قاطع على هذا الرابط/المضيف — لا يُعدَّل بتكرار الطلب.
            code in listOf(401, 403, 404, 410, 451) -> Health.DEAD
            else -> Health.UNKNOWN   // 408/429/5xx مؤقّتة: لا حُكم
        }
        Log.i(TAG, "probe $verdict http=$code ${url.take(80)}")
        verdict
    } catch (e: java.net.UnknownHostException) {
        Log.i(TAG, "probe DEAD dns ${url.take(80)}")
        Health.DEAD
    } catch (e: javax.net.ssl.SSLException) {
        // شهادة مرفوضة عند كل عميل صارم — والمشغّل منهم (قِيس reelree/cdn.narto).
        Log.i(TAG, "probe DEAD ssl ${e.message?.take(40)} ${url.take(70)}")
        Health.DEAD
    } catch (e: Exception) {
        Log.i(TAG, "probe UNKNOWN ${e.javaClass.simpleName} ${url.take(70)}")
        Health.UNKNOWN
    }

    /**
     * جلب نصّ الصفحة — محاولات، لأن صفحاً واحداً يفشل تعني «لا يوجد روابط»
     * لحلقةٍ سليمة (نفاد اتصال قصير لا يُذكر في أي سجلّ). ونحفظ كوكيز
     * الاستجابة: هي سياق المشاهدة الذي يطلبه `/api/stream/` لاحقاً.
     */
    private suspend fun fetchHtml(url: String): String? {
        repeat(3) { i ->
            if (i > 0) delay(600L * i)
            try {
                val r = app.get(url, referer = mainUrl)
                adoptCookies(r)
                // ★ `app.get` يعيد صفحات الأخطاء كنصٍّ عادي (404 مقيس — درس
                //   Episode.url)، و503 تحدي الحماية كذلك. وبلا هذا الفحص
                //   تُقرأ صفحة الخطأ «صفحة حلقة» وتفشل قراءة معرّف الحلقة
                //   فيُقال «لا يوجد روابط» لحلقةٍ سليمة (قِيس على الهاتف
                //   2026-10-10: `شغف متأجج` حلقة 1).
                if (r.code !in 200..299) {
                    Log.w(TAG, "fetchHtml try=$i HTTP ${r.code} ${url.take(70)}")
                    return@repeat
                }
                return r.text
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "fetchHtml try=$i FAILED ${e.javaClass.simpleName} ${url.take(70)}")
            }
        }
        return null
    }

    /**
     * جلب صفحة كـ`Document` مع محاولات — قِيس على الهاتف 2026-10-10: صفحة
     * المسلسل تُبتَلع بـ`SocketTimeoutException` من Cloudflare فتعود «لا
     * تفاصيل» لمسلسلٍ سليم. `attempts` أصغر لصفوف الرئيسية: الصفوف تُطلَق
     * متزامنةً (١٨ طلباً)، وتكرارها ثلاثاً يُضاعف الوابلَ فيزيد الحجب
     * (درس وابل DUN) — فمحاولتان بفاصلٍ متزايد تكفيان هناك.
     */
    private suspend fun fetchDoc(url: String, attempts: Int = 3): Document? {
        repeat(attempts) { i ->
            if (i > 0) delay(500L * i)
            try {
                val r = app.get(url, referer = mainUrl)
                adoptCookies(r)
                // ★ صفحة خطأ (503/404) تُحلَّل كوثيقة فارغة فتُقرأ «صفٌّ بلا
                //   بطاقات» = واجهةٌ ناقصة بلا أي سجلّ خطأ. الفحص يجعلها محاولة
                //   تُعاد بدلاً من نتيجةٍ كاذبة.
                if (r.code !in 200..299) {
                    Log.w(TAG, "fetchDoc try=$i HTTP ${r.code} ${url.take(70)}")
                    return@repeat
                }
                return r.document
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "fetchDoc try=$i FAILED ${e.javaClass.simpleName} ${url.take(70)}")
            }
        }
        return null
    }

    /**
     * `/api/stream/{id}?locale=ar` — ومنه فرعُ البديل `&refresh=1&failed=<sourceTag>`
     * (الآلية نفسها في JS الموقع: `fetch("/api/stream/".concat(id,"?",l))` حيث
     * `l = "locale=…&refresh=1&failed=…"`.
     *
     * يحمل ترويسة `Cookie` (سياق المشاهدة) — بلاها يردّ الخادم دائماً
     * `403 viewing_context_required` (قِيس على الهاتف 2026-10-10). وإن عاد 403
     * بهذا الرمز نسخّن السياق من `pageUrl` ثم نعيد المحاولة (نفس منطق مشغّل
     * الموقع: HEAD ثم إعادة). ونطبع **جسم** الخطأ لأن 403 قد يكون JSON سياق
     * وقد يكون تحدي Cloudflare — وبلا الجسم لا يُفرَّق بينهما (درس DUN).
     * نعيد على الأخطاء المؤقتة فقط: `503 source_busy` مقيسٌ هنا، و`410
     * source_unavailable` يعني عدم توفّر البديل.
     */
    private suspend fun fetchStream(id: Long, pageUrl: String, failedTag: String?): StreamResponse? {
        val query = buildString {
            append("?locale=ar")
            if (!failedTag.isNullOrBlank()) {
                append("&refresh=1&failed=").append(URLEncoder.encode(failedTag, "UTF-8"))
            }
        }
        // ★ قِيس على الهاتف 2026-10-10: `503 source_busy` («The source is slow to
        //   respond, try again shortly») يحتاج صبراً بالثواني لا بأجزائها —
        //   ٤ محاولات بفاصل 0.7s×i نفدت في حالةٍ ونجحت في أخرى عند المحاولة
        //   **الأخيرة**. فالمحاولات ٥ والفاصل 1.2s×i (≈12 ثانية كحدٍّ أقصى،
        //   داخل سقف الـ30s المفروض على loadLinks): هذا أطول انتظار يبرّره
        //   نصّ الخادم نفسه، ولا يحوّل حلقةً تعمل إلى «لا يوجد روابط».
        repeat(5) { i ->
            if (i > 0) delay(1200L * i)
            val r = try {
                app.get(
                    "$mainUrl/api/stream/$id$query",
                    headers = cookieHeaders(),
                    referer = pageUrl
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "stream try=$i FAILED ${e.javaClass.simpleName} id=$id")
                return@repeat
            }
            if (r.isSuccessful) {
                return try {
                    mapper.readValue(r.text, StreamResponse::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "stream parse FAILED id=$id", e)
                    null
                }
            }
            val body = runCatching { r.text }.getOrNull().orEmpty()
            Log.w(
                TAG,
                "stream HTTP ${r.code} try=$i id=$id failedTag=$failedTag body=${body.take(140)}"
            )
            // ★ سياق المشاهدة: أنشئه من صفحة الحلقة ثم أعد المحاولة حاملاً الكوكيز.
            if (r.code == 403 && body.contains("viewing_context_required")) {
                viewCookies.remove("dd_view")   // سياقٌ جديد لا قديم
                primeViewingContext(pageUrl)
                return@repeat
            }
            adoptCookies(r)
            if (r.code !in listOf(403, 408, 429, 500, 502, 503, 504)) return null
        }
        return null
    }

    /**
     * إصدار استجابة بثّ واحدة كاملة: الترجمات (رابط مطلق + رمز لغة، ولغة واحدة
     * لا تُكرَّر — مجموعتا الفرز تمرَّران بين النداءات كي لا تُكرَّر الترجمة عند
     * إصدار الأساسي بعد البديل)، ثم الرابط الأساسي، ثم الجودات الإضافية.
     */
    private suspend fun emitStream(
        node: StreamResponse,
        ep: Int,
        seenSubUrl: MutableSet<String>,
        seenSubLang: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        labelSuffix: String = "",
    ) {
        // الترجمة — مساراتها نسبية (`/subs/1751101/ar.vtt`) والمشغّل لا يضيف mainUrl
        // إلى SubtitleFile.url أبداً (مقيس على cloudstream.jar) فنبنيه نحن.
        if (showSubs()) {
            node.subtitles.orEmpty().forEach { s ->
                val rawUrl = s.url?.trim().orEmpty()
                if (rawUrl.isEmpty()) return@forEach
                val subUrl = abs(rawUrl)
                if (!seenSubUrl.add(subUrl)) return@forEach
                val rawLang = (s.lang?.trim().orEmpty().ifEmpty { s.label?.trim().orEmpty() })
                val lang = normalizeSubLang(rawLang) ?: rawLang.ifEmpty { "ترجمة" }
                if (!seenSubLang.add(lang)) return@forEach
                try {
                    subtitleCallback(newSubtitleFile(subLangLabel(lang), subUrl) {
                        // ملفّ الترجمة محميّ بنفس بوابة /api/stream: بلا كوكي سياق
                        // المشاهدة يردّ 403 viewing_context_required — مقيس: 200 مع
                        // `dd_view`، و403 بدونه (رأس Referer وحده لا يكفي).
                        this.headers = mapOf("Referer" to mainUrl) + cookieHeaders()
                    })
                } catch (_: Exception) {
                }
            }
        }

        // الصيغة: `type` يقول hls/mp4، وله روابط بلا امتداد إطلاقاً
        // (حملة mp4 تنتهي بـ`mime_type=video_mp4`) فنُمرّره إلى FormatTag
        // ليكتب [MP4] لا [VIDEO] — انظر FormatTag.label.
        val typeStr = node.type.orEmpty().lowercase()
        val rawUrl = node.url?.trim().orEmpty()
        if (rawUrl.isEmpty()) return
        val vUrl = abs(rawUrl)
        val declared = when {
            typeStr == "mp4" -> "MP4"
            typeStr == "hls" -> "M3U8"
            else -> null
        }
        val linkType = if (typeStr == "hls" || vUrl.contains(".m3u8")) ExtractorLinkType.M3U8
        else ExtractorLinkType.VIDEO

        // اسم السيرفر في اسم الرابط: المشغّل يعرض `name` وحده، وبلا هذا تتكرّر
        // صفوفٌ متطابقة «الحلقة N» بلا ما يفرّق بين سيرفرٍ وآخر.
        val srv = serverLabel(vUrl)
        val epName = "الحلقة $ep" + (srv?.let { " · $it" } ?: "") + labelSuffix

        callback(
            newExtractorLink(source = name, name = FormatTag.tagged(epName, vUrl, linkType, declared), url = vUrl, type = linkType) {
                referer = mainUrl
                qOf(vUrl)?.let { quality = getQualityFromName(it) }
                // مسارات الوكيل `directdrama.com/px/…` تردّ 403 بلا كوكي سياق
                // المشاهدة (مقيس: 200 معه) — فالمشغّل يحتاج الكوكي مع الوسائط نفسها.
                headers = mapOf("Referer" to mainUrl) + cookieHeaders()
            }
        )

        // الجودات الإضافية إن أعلنتها الاستجابة (قياسياً المصفوفة فارغة،
        // فلا تُصدر شيئاً) — بعد الرابط الأساسي دائماً حتى لا تحلّ محلّه.
        node.renditions.orEmpty().forEach { rd ->
            val ru = rd.url?.trim().orEmpty()
            if (ru.isEmpty()) return@forEach
            val u = abs(ru)
            if (u == vUrl) return@forEach
            val label = rd.height?.takeIf { it > 0 }?.let { "${it}p" }
                ?: rd.name?.takeIf { it.isNotBlank() }
                ?: "جودة إضافية"
            val rType = if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val rDeclared = if (u.contains(".m3u8")) "M3U8" else declared
            val rName = if (srv != null) "$label · $srv" else label
            callback(
                newExtractorLink(source = name, name = FormatTag.tagged(rName, u, rType, rDeclared), url = u, type = rType) {
                    referer = mainUrl
                    rd.height?.takeIf { it > 0 }?.let { quality = getQualityFromName("${it}p") }
                    headers = mapOf("Referer" to mainUrl) + cookieHeaders()
                }
            )
        }
    }

    /**
     * الجودة الحقيقية من المسار — إن غابت لا نخترع اسماً.
     * الأنماط نفسها المقيسة على Dramadunyam 2026-10-08 (نفس عائلات المُضيفات):
     * `-ld.`=540 و`-sd.`=720 مقاسان، و`.720p.` و`q=720p` شكلان مُدرجان؛
     * و`-hd` غير مقاس فلا يُخمَّن.
     */
    /**
     * اسم **سيرفر التشغيل** — من مضيف الوسائط، باسمٍ قصير مفهوم.
     *
     * المشغّل يعرض `ExtractorLink.name` وحده (مقيس)؛ وكانت كل الروابط تُسمّى
     * «الحلقة N» فيتكرّر الصفّ نفسه مرّاتٍ بلا ما يفرّق بينها. الموقع لا يعطي
     * اسماً للسيرفر في `StreamResponse` (`sourceTag` بصمةٌ مبهمة مثل `17n7hew`
     * لا اسم)، فالمضيف هو المُعرّف الوحيد المتاح.
     *
     * القاعدة مبنية على المضيف لا على جدول أسماء: النطاق المسجَّل = آخر مقطعين
     * (`…/miniepisode.media`)، فاسم الخدمة هو المقطع السابق له مباشرةً. تعمل مع
     * كل المُضيفات المقيسة هنا: `cdn-video.miniepisode.media` ← Miniepisode،
     * `hshwapp2.drt768.com` ← Drt768، `reeltv.janzhoutec.com` ← Janzhoutec،
     * `cdnvideo.cdreader.com` ← Cdreader، `awscdn.netshort.com` ← Netshort.
     */
    private fun serverLabel(url: String): String? {
        val host = runCatching { java.net.URL(url).host }
            .getOrNull()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
            ?: return null
        // وكيل الموقع نفسه (`directdrama.com/px/…`) — يُسمّى باسم الموقع لا باسم موقعه.
        if (host == "directdrama.com" || host.endsWith(".directdrama.com")) return "وكيل الموقع"
        val parts = host.split('.')
        if (parts.size < 2) return host
        val svc = parts[parts.size - 2]
        if (svc.length < 3 || svc.all { it.isDigit() }) return host
        return svc.replaceFirstChar { it.uppercase() }
    }

    private fun qOf(url: String): String? {
        Regex("""_(\d{3,4})/""").find(url)?.let { return it.groupValues[1] + "p" }
        Regex("""/(\d{3,4})p/""").find(url)?.let { return it.groupValues[1] + "p" }
        Regex("""\.(\d{3,4})p\.""").find(url)?.let { return it.groupValues[1] + "p" }
        Regex("""[?&]q=(\d{3,4})p\b""").find(url)?.let { return it.groupValues[1] + "p" }
        if (Regex("""-ld\.""").containsMatchIn(url)) return "540p"
        if (Regex("""-sd\.""").containsMatchIn(url)) return "720p"
        return null
    }
}
