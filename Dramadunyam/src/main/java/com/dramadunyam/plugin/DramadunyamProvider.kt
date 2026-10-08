package com.dramadunyam.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.FormatTag
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.URLDecoder
import java.util.TreeMap

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val TAG = "Dramadunyam"

/** استجابة `/api/series` أو `/api/search` المترقّمة. */
private data class DunItem(
    val id: Long? = null,
    val slug: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val platform: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    @JsonProperty("available_episodes") val availableEpisodes: Int? = null,
)

private data class DunListResponse(val data: List<DunItem>? = null)

/** استجابة أقسام الواجهة الأمامية: `/api/yeni-eklenenler` و`/api/siralama`. */
private data class DunFeedResponse(val items: List<DunItem>? = null)

/** استجابة `/api/series/{slug}` — تفاصيل مسلسل واحد. */
private data class DunTag(val slug: String? = null, val name: String? = null)

private data class DunDetail(
    val id: Long? = null,
    val slug: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val platform: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    @JsonProperty("available_episodes") val availableEpisodes: Int? = null,
    val tags: List<DunTag>? = null,
    val genre: String? = null,
)

/** استجابة `/play/{seriesId}/{n}` — رابط بثٍّ واحد مع ترجماته المضمّنة. */
private data class DunSubtitle(val dil: String? = null, val src: String? = null)

private data class PlayResponse(
    val delivery: String? = null,
    val type: String? = null,
    val url: String? = null,
    @JsonProperty("source_tag") val sourceTag: String? = null,
    @JsonProperty("expires_at") val expiresAt: String? = null,
    val altyazilar: List<DunSubtitle>? = null,
)

// ★ إشارات صفوف القسم الأمامي — محمولة في `MainPageData.data` لصفّي
//   «الأحدث» و«الأكثر مشاهدة»؛ يقرؤها `getMainPage` ويميّزها عن مفاتيح
//   المنصات (التي تشبه أسماءً حقيقية كـ"NetShort"). علامة "dun-" لا يصدرها
//   الموقعُ في `platform` إطلاقاً، فالتزامن معها آمن. `internal` لأن ورقة
//   الإعدادات تبني منها قائمة «إخفاء الأقسام» في نفس الحزمة.
internal const val DUN_FRONT_LATEST = "dun-front-latest"
internal const val DUN_FRONT_TOP = "dun-front-top"

/** صفوف المنصات (مفتاح ← عنوان الصفّ). `internal` لإعادة استخدامها في ورقة
 *  الإعدادات (قائمة «إخفاء الأقسام») — مصدر واحد للحقيقة لا نسختان تتفرّعان. */
internal val DUN_PLATFORM_ROWS: List<Pair<String, String>> = listOf(
    "NetShort" to "مسلسلات NetShort",
    "DramaWave" to "مسلسلات DramaWave",
    "DramaBox" to "مسلسلات DramaBox",
    "PineDrama" to "مسلسلات PineDrama",
    "ReelShort" to "مسلسلات ReelShort",
    "FreeReels" to "مسلسلات FreeReels",
    "ShortMax" to "مسلسلات ShortMax",
    "FlexTV" to "مسلسلات FlexTV",
    "FlickReels" to "مسلسلات FlickReels",
    "StarDust" to "مسلسلات StarDust",
    "MoboReels" to "مسلسلات MoboReels",
    "ShortWave" to "مسلسلات ShortWave",
    "Storyreel" to "مسلسلات Storyreel",
    "FlareFlow" to "مسلسلات FlareFlow",
    "KalosTV" to "مسلسلات KalosTV",
    "SerialPlus" to "مسلسلات SerialPlus",
    "GoodShort" to "مسلسلات GoodShort",
    "DramaBite" to "مسلسلات DramaBite",
    "CubeTV" to "مسلسلات CubeTV",
    "HappyShort" to "مسلسلات HappyShort",
    "StarShort" to "مسلسلات StarShort",
    "ShotShort" to "مسلسلات ShotShort",
    "RapidTV" to "مسلسلات RapidTV",
    "Playlet" to "مسلسلات Playlet",
    "RadReels" to "مسلسلات RadReels",
    "Joyreels" to "مسلسلات Joyreels",
    "RaptDrama" to "مسلسلات RaptDrama",
    "iDrama" to "مسلسلات iDrama",
    "BiliTV" to "مسلسلات BiliTV",
    "BonusTV" to "مسلسلات BonusTV",
    "Reelife" to "مسلسلات Reelife",
    "ShortBox" to "مسلسلات ShortBox",
    "GoldDrama" to "مسلسلات GoldDrama",
    "VibeShort" to "مسلسلات VibeShort",
    "SodaReels" to "مسلسلات SodaReels",
    "MicroDrama" to "مسلسلات MicroDrama",
    "Vigloo" to "مسلسلات Vigloo",
    "DramaPops" to "مسلسلات DramaPops",
    "DramaRush" to "مسلسلات DramaRush",
    "Shorten" to "مسلسلات Shorten",
    "MeloShort" to "مسلسلات MeloShort",
    "TopDrama" to "مسلسلات TopDrama",
    "Melolo" to "مسلسلات Melolo",
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
 * `fromLanguageToTagIETF(lang, true)`؛ والتطبيق يشتقّ «العربية» من الرمز نفسه،
 * فالرمز هو المُدخل والتسمية العربية هي المخرج — لا تُمرَّر التسمية كما هي.
 */
private fun normalizeSubLang(raw: String): String? {
    if (raw.isEmpty()) return null
    if (SubtitleHelper.fromCodeToLangTagIETF(raw) != null) return raw
    NATIVE_SUB_LANG[raw.lowercase()]?.let { return it }
    return SubtitleHelper.fromLanguageToTagIETF(raw, true)
}

class DramadunyamProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "Dramadunyam"
    override var mainUrl = "https://dramadunyam.com"
    override var lang = "ar"

    // ★ المفتاح الأم: `hasMainPage` ديناميكي فيُسحَب المصدر من الصفحة الرئيسية
    //   كليّاً فور إطفاء «إظهار الواجهة الرئيسية» في ورقة الإعدادات.
    override val hasMainPage: Boolean
        get() = showHome()
    override val supportedTypes = setOf(TvType.TvSeries)

    // ★ قسم الواجهة الأمامية — صفّان فوق صفوف المنصات: «الأحدث» و«الأكثر
    //   مشاهدة». كلاهما يعرض كل منصات الموقع معاً (يوفّرهما الموقع نفسُه عبر
    //   `/api/yeni-eklenenler` و`/api/siralama`). مكوّن (خاصيتان ديناميكيتان:
    //   `mainPage` تُقرأ عند رسم الواجهة، والواجهة تطلب الأقسام عند أول صفحة)،
    //   فتضاف/تُسحَب صفوف القسم الأمامي فوراً حسب إعداد «إظهار القسم الأمامي».
    override val mainPage: List<MainPageData>
        get() {
            // مطابقةٌ حرفية لما يبنيه `mainPageOf`: (name=الثاني، data=الأول،
            // horizontalImages=الافتراضي false) — أي تمرير `true` هنا كان يغيّر
            // شكل بطاقات صفوف المنصات كلّها.
            val hidden = hiddenRows()
            val accent = if (showFront()) {
                listOf(
                    MainPageData("الأحدث", DUN_FRONT_LATEST),
                    MainPageData("الأكثر مشاهدة", DUN_FRONT_TOP)
                ).filter { it.data !in hidden }
            } else emptyList()
            // صفوف المنصات اختيارية (إعداد «إظهار قوائم المنصات»).
            if (!showPlatforms()) return accent
            val rows = DUN_PLATFORM_ROWS.map { (k, v) -> MainPageData(v, k) }
                .filter { it.data !in hidden }
            // حدّ عدد الصفوف: كل صف طلب API، فتقليله يقصّ زمن فتح الواجهة
            // (يُطبَّق بعد إسقاط المخفية كي يبقى المطلوب ظاهراً كاملاً، وعلى
            // صفوف المنصات وحدها — وإلا لضاع صفّا الواجهة الأمامية عند أي حدّ).
            val raw = prefs?.getString(DramadunyamSettingsBottomSheet.KEY_HOME_ROWS, "all") ?: "all"
            val n = raw.toIntOrNull()
            val capped = if (n != null && n in 1 until rows.size) rows.take(n) else rows
            return accent + capped
        }
    private fun showSubs(): Boolean =
        prefs?.getBoolean(DramadunyamSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) != false

    private fun descOrder(): Boolean =
        prefs?.getString(DramadunyamSettingsBottomSheet.KEY_EPISODE_ORDER, "as_is") == "desc"

    private fun searchScope(): String =
        prefs?.getString(DramadunyamSettingsBottomSheet.KEY_SEARCH_SCOPE, "ar") ?: "ar"

    private fun showFront(): Boolean =
        prefs?.getBoolean(DramadunyamSettingsBottomSheet.KEY_SHOW_FRONT, true) != false

    private fun showHome(): Boolean =
        prefs?.getBoolean(DramadunyamSettingsBottomSheet.KEY_SHOW_HOME, true) != false

    private fun showPlatforms(): Boolean =
        prefs?.getBoolean(DramadunyamSettingsBottomSheet.KEY_SHOW_PLATFORMS, true) != false

    // ★ الإخفاء الجزئي: مجموعة مفاتيح الأقسام المخفية من ورقة الإعدادات
    //   (`dun_hidden_rows`) — كل قسم يُحدَّد بالظهور أو الإخفاء على حدة.
    //   القيم = مفاتيح `MainPageData.data` نفسها (أسامٍ لا تظهر في الموقع،
    //   فلا تتعارض مع معرّف منصة حقيقي). مجموعة خالية = الكل ظاهر (الافتراضي).
    private fun hiddenRows(): Set<String> =
        prefs?.getStringSet(DramadunyamSettingsBottomSheet.KEY_HIDDEN_ROWS, null) ?: emptySet()

    // ── بوابة التزامن على API ────────────────────────────────────────────────
    //
    // قياس حيّ 2026-10-07: 43 طلباً متزامناً (شكل الصفحة الرئيسية كما يرسلها
    // التطبيق فعلاً) تحصل منها **42 على 403** Turnstile خلال 4.3 ثوانٍ — هذا
    // هو أصل «أخطاء كل المنصات». أمّا موجات من 4 طلبات بفاصل 0.5 ثانية بعد
    // اكتمال كل موجة فنجحت 43/43 في 11.4 ثانية. فالبوابة هنا تُحاكي الموجة
    // الآمنة: أربع رخص، وتأخير نصف ثانية داخل الرخصة قبل كل طلب، فأول أربع
    // تنطلق معاً وأمّا التالية فبعد ربع يستقرّ — لا يبقى سقف تزامن 4 ولا معدل
    // يقفز فوق ~4 طلبات/ثانية.
    private val apiGate = Semaphore(4)

    /** يمرّر [block] عبر البوابة (أربع جلسات كحدّ أقصى + نصف ثانية تمهيد). */
    private suspend fun <T> gated(block: suspend () -> T): T =
        apiGate.withPermit {
            delay(500)
            block()
        }

    // ── التذكرة `dd_bilet` ───────────────────────────────────────────────────
    //
    // الموقع يرفض كل `/api/*` و`/play/*` بلا هذه التذكرة (412 `{"error":"bilet"}`).
    // `app` بلا cookie jar (مقيس على MainActivityKt) فلا تُحفظ تلقائياً. تذكرة
    // اليوم تُسقَط في **`Set-Cookie` لنقطة `/api/config`** (قيس 2026-10-07):
    // صفحة `/ar/` تعود 200 بلا أي dd_bilet في الرؤوس، فلا جدوى من تسخينها.
    // `/api/config` يردّ أيضاً `{"turnstile":{"aktif":true,"zorunlu":false}}`
    // — التذكرة محمية بـTurnstile لكنه غير إلزامي (zorunlu=false)، فيُصدرها
    // الخادم لطلبٍ عادٍ. صلاحيتها 12 ساعة؛ وعند 412 نُعيد التسخين مرةً واحدة.
    private var cachedTicket: String? = null

    // ★ تسخين أحادي: الصفوف تُطلَق متزامنة، ولو دخل 40 طلباً `warmTicket` معاً
    //   لأُنزلت 40 طلباً على `/api/config` دفعة واحدة وهي أول ما يثير Turnstile.
    //   القفل يجعل الأول يسخّن والبقية تنتظره ثم تقرأ النتيجة من الذاكرة.
    private val ticketLock = Mutex()

    private suspend fun warmTicket(): String? {
        cachedTicket?.takeIf { it.isNotBlank() }?.let { return it }
        ticketLock.withLock {
            cachedTicket?.takeIf { it.isNotBlank() }?.let { return it }
            val t = try {
                // المصدر المقيس للتذكرة اليوم. `/ar/` احتياطٌ لأيامٍ سابقة كان يسقطها.
                val rConfig = app.get("$mainUrl/api/config", referer = mainUrl)
                val c1 = rConfig.cookies["dd_bilet"]?.takeIf { it.isNotBlank() }
                if (c1 != null) {
                    c1
                } else {
                    app.get("$mainUrl/ar/", referer = mainUrl).cookies["dd_bilet"]
                        ?.takeIf { it.isNotBlank() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ticket warm FAILED", e)
                null
            }
            cachedTicket = t
            if (t == null) Log.e(TAG, "ticket empty — site refused dd_bilet")
            return cachedTicket
        }
    }

    /** تذكرة + رؤوس اختيارية، أو null إن تعذّر التسخين. */
    private fun authHeaders(extra: Map<String, String>): Map<String, String>? {
        val t = cachedTicket?.takeIf { it.isNotBlank() }
            ?: return null
        return extra + mapOf("Cookie" to "dd_bilet=$t")
    }

    /** 412 = تذكرة فاسدة/منتهية؛ صفحة تحدّي = يعترض الخادم. كلاهما يُعاد تسخينه. */
    private fun needsRewarm(r: com.lagradost.nicehttp.NiceResponse): Boolean {
        if (r.code == 412) return true
        val txt = r.text
        return txt.contains("Just a moment", ignoreCase = true)
    }

    /**
     * التذكرة المتجدّدة قد تصل في `Set-Cookie` أي استجابة — نعتمدها فوراً
     * كي لا نرسل تذكرةً عفا عليها القيد (قياس 2026-10-08: قيم `dd_bilet`
     * ثابتة عبر الدورات، والتبديل يحدث أحياناً بلا إعلان).
     */
    private fun adoptTicket(r: com.lagradost.nicehttp.NiceResponse) {
        val t = r.cookies["dd_bilet"]?.takeIf { it.isNotBlank() } ?: return
        if (t != cachedTicket) {
            Log.d(TAG, "adopted rotated dd_bilet")
            cachedTicket = t
        }
    }

    /**
     * طلبٌ بجرعةٍ من الحيل: التذكرة صراحةً، وعند الرفض (412/تحدّي) يُعاد
     * التسخين **حتى ثلاث دورات** بفاصل ثانية ونصف.
     *
     * لماذا ثلاث لا واحدة (قياس 2026-10-08): التذكرة تموت بشكل حادٍّ متقطع —
     * في محاولةٍ واحدة ماتت التذكرة بعد طلبٍ واحد، وأحياناً بعد 12 طلباً،
     * والتذكرة المُعاد تسخينها قد تُرفض هي الأخرى من أوّلها (وُصفت «bilet»
     * من البداية). الدورة الثالثة رفعت التعافي من «طلب واحد ثم موت» إلى
     * النجاح في كل المقاسات — والرفض المستمر يعود null مع سجلّ صريح.
     */
    private suspend fun getWithTicket(
        url: String,
        referer: String? = null,
        extra: Map<String, String> = emptyMap()
    ): com.lagradost.nicehttp.NiceResponse? {
        warmTicket() ?: run {
            Log.e(TAG, "getWithTicket no ticket $url")
            return null
        }
        var last: com.lagradost.nicehttp.NiceResponse? = null
        for (cycle in 1..3) {
            val h = authHeaders(extra) ?: return null
            val r = app.get(url, referer = referer ?: mainUrl, headers = h)
            adoptTicket(r)
            last = r
            if (!needsRewarm(r)) return r
            Log.d(TAG, "re-warm cycle=$cycle after ${r.code} $url")
            cachedTicket = null
            warmTicket() ?: return r
            // فاصل قصير: الرفض يتغيّر مع الزمن لا مع التذكرة وحدها (قِيس).
            if (cycle < 3) kotlinx.coroutines.delay(1500)
        }
        Log.e(TAG, "getWithTicket persistent 412 after 3 cycles $url")
        return last
    }

    /** رابط مطلق — الموقع يرسل مسارات نسبية والمشغّل لا يضيف mainUrl. */
    private fun abs(u: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> mainUrl + u
        else -> "$mainUrl/$u"
    }

    /** آخر جزء من المسار، مفكوك الترميز — هو `slug` في رابط المسلسل. */
    private fun slugFrom(url: String): String? {
        val tail = url.substringAfterLast('/').substringBefore('?')
        if (tail.isBlank()) return null
        return try {
            URLDecoder.decode(tail, "UTF-8")
        } catch (e: Exception) {
            tail
        }
    }

    private fun DunItem.toSearch(): SearchResponse? {
        val t = title?.takeIf { it.isNotBlank() } ?: return null
        val s = slug?.takeIf { it.isNotBlank() } ?: return null
        return newTvSeriesSearchResponse(t, "$mainUrl/ar/series/$s", TvType.TvSeries) {
            posterUrl = cover?.takeIf { it.isNotBlank() }?.let { abs(it) }
            if (availableEpisodes != null) episodes = availableEpisodes
            else totalEpisodes?.takeIf { it > 0 }?.let { episodes = it }
        }
    }

    /** «الأحدث» — قسم الواجهة الأمامية. الموقع يسمّيه «أضيف حديثًا». */
    private suspend fun fetchLatestRow(): List<HomePageList>? {
        val r = getWithTicket(
            "$mainUrl/api/yeni-eklenenler?sayfa=1&adet=40&lang=ar",
            referer = mainUrl
        ) ?: return null
        if (!r.isSuccessful) {
            Log.e(TAG, "yeni-eklenenler HTTP ${r.code}")
            return null
        }
        return try {
            mapper.readValue(r.text, DunFeedResponse::class.java).items.orEmpty()
                .mapNotNull { it.toSearch() }
                .takeIf { it.isNotEmpty() }
                ?.let { listOf(HomePageList("الأحدث", it, true)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "yeni-eklenenler parse FAILED", e)
            null
        }
    }

    /** «الأكثر مشاهدة» — قسم واجهة بتصنيف (sıralama). أسبوعي، مرتبط باللغة. */
    private suspend fun fetchTopRow(): List<HomePageList>? {
        val r = getWithTicket(
            "$mainUrl/api/siralama?donem=hafta&lang=ar",
            referer = mainUrl
        ) ?: return null
        if (!r.isSuccessful) {
            Log.e(TAG, "siralama HTTP ${r.code}")
            return null
        }
        return try {
            mapper.readValue(r.text, DunFeedResponse::class.java).items.orEmpty()
                .mapNotNull { it.toSearch() }
                .takeIf { it.isNotEmpty() }
                ?.let { listOf(HomePageList("الأكثر مشاهدة", it, true)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "siralama parse FAILED", e)
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val pn = if (page <= 1) 1 else page
            when (request.data) {
                // صفّا القسم الأمامي — «الأحدث» و«الأكثر مشاهدة». طلبهما عند الصفحة 1
                // فقط، والصفحة الأولى هي التي تعرضهما (لا ترقيم لهما).
                DUN_FRONT_LATEST, DUN_FRONT_TOP -> {
                    if (page > 1) return null
                    // فشل التحميل → null (حالة خطأ صريحة) لا صفٌّ فارغ يبدو كسراً.
                    // `gated` هنا لأن صفّي الأمامي يُطلَقان مع الـ43 صفاً دفعةً واحدة.
                    val rows = gated {
                        if (request.data == DUN_FRONT_LATEST) fetchLatestRow()
                        else fetchTopRow()
                    }
                    return rows?.let { newHomePageResponse(it, false) }
                }
            }
            // ★ البوابة إلزامية على كل صف: التطبيق يُطلق الصفوف متزامنة، وقياس
            //   2026-10-07 أثبت أن 43 طلباً في لحظة واحدة تعطي 42×403 Turnstile
            //   خلال 4.3 ثانية — وهو سبب «أخطاء كل المنصات». موجات من 4 + نصف
            //   ثانية بينها تكمل كل الصفوف في 11.4 ثانية بلا رفض واحد.
            gated {
                val r = getWithTicket(
                    "$mainUrl/api/series?page=$pn&limit=40&sort=yeni&platform=${request.data}&lang=ar",
                    referer = mainUrl
                ) ?: return@gated null
                if (!r.isSuccessful) {
                    Log.e(TAG, "series HTTP ${r.code} page=$pn platform=${request.data}")
                    return@gated null
                }
                val data = mapper.readValue(r.text, DunListResponse::class.java).data.orEmpty()
                val items = data.mapNotNull { it.toSearch() }
                if (items.isEmpty()) {
                    // 200 + JSON صالح + `data: []` = المنصة فارغة فعلاً على الموقع
                    // (قِيس: Joyreels وBiliTV على IP سليم، 6 صيغ بلا فرق). صفٌّ فارغ
                    // أمين، أمّا null فتعرض صفّ خطأ يوهم بأن الفحص فشل.
                    Log.d(TAG, "series genuinely empty platform=${request.data}")
                    newHomePageResponse(request.name, emptyList())
                } else newHomePageResponse(request.name, items)
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
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val scope = searchScope()
            val qs = java.net.URLEncoder.encode(q, "UTF-8")

            // `lang=ar` وحده هو ما يُعيد العناوين العربية (مقيس: «من الكراهية
            // إلى الحب» = 0 بدونه، 1 به) — بلاه تعود العناوين التركية الأصلية.
            val mine = if (scope != "orig") {
                val r = getWithTicket("$mainUrl/api/search?q=$qs&limit=18&lang=ar", referer = "$mainUrl/ar/search")
                    ?: return null
                if (r.isSuccessful) mapper.readValue(r.text, DunListResponse::class.java).data.orEmpty()
                    .mapNotNull { it.toSearch() } else emptyList()
            } else emptyList()

            val orig = if (scope != "ar") {
                val r = getWithTicket("$mainUrl/api/search?q=$qs&limit=18", referer = "$mainUrl/ar/search")
                    ?: return null
                if (r.isSuccessful) mapper.readValue(r.text, DunListResponse::class.java).data.orEmpty()
                    .mapNotNull { it.toSearch() } else emptyList()
            } else emptyList()

            if (scope == "both") {
                val mineUrls = mine.map { it.url }.toSet()
                mine + orig.filter { it.url !in mineUrls }
            } else if (scope == "orig") orig else mine
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED q=$query", e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val slug = slugFrom(url) ?: return null
            val r = getWithTicket("$mainUrl/api/series/$slug?lang=ar", referer = mainUrl) ?: return null
            if (!r.isSuccessful) {
                Log.e(TAG, "detail HTTP ${r.code} slug=$slug")
                return null
            }
            val d = mapper.readValue(r.text, DunDetail::class.java)
            val title = d.title?.takeIf { it.isNotBlank() } ?: return null

            // الحلقات أرقامٌ صحيحة 1..availableEpisodes — لا HTML؛ نضمّن معرّف
            // المسلسل في `data` كي لا يعود بثّ الحلقة ليفتح صفحتها أو يسأل API.
            val count = d.availableEpisodes?.takeIf { it > 0 }
                ?: d.totalEpisodes?.takeIf { it > 0 }
                ?: return null
            val base = "$mainUrl/ar/series/${d.slug?.takeIf { it.isNotBlank() } ?: slug}"
            val eps = TreeMap<Int, String>()
            for (i in 1..count) eps[i] = "$base/episode-$i|id|${d.id}"

            val ordered = if (descOrder()) eps.entries.toList().reversed() else eps.entries.toList()
            val episodes = ordered.map { (num, data) ->
                newEpisode(data) {
                    episode = num
                    name = "الحلقة $num"
                }
            }
            val tags = d.tags?.mapNotNull { it.name?.takeIf { s -> s.isNotBlank() } }
                ?: d.genre?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                ?: emptyList()
            Log.d(TAG, "load title=$title episodes=${episodes.size}")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                // الغلاف نفسه يغذّي الخلفية: `ResultViewModel2` يأخذ
                // `backgroundPosterUrl ?: posterUrl`، لكن بقاء الحقل null يجعل
                // أعلى صفحة التفاصيل بلا تمويه في بعض المسارات.
                val coverAbs = d.cover?.takeIf { it.isNotBlank() }?.let { abs(it) }
                posterUrl = coverAbs
                backgroundPosterUrl = coverAbs
                plot = d.description?.takeIf { it.isNotBlank() }
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
            // data = "$url|id|$seriesId" — الرابطُ نفسُه، ثم المعرّف أنظف من أي HTML.
            val parts = data.split("|")
            val rawUrl = parts[0].trim()
            if (rawUrl.isBlank()) {
                Log.e(TAG, "loadLinks empty url data=$data")
                return false
            }
            val epUrl = abs(rawUrl)
            val ep = Regex("""episode-(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return false

            // المعرّف محمول في `data`؛ إن لم يوجد فمن API التفاصيل (مسار الحلقة).
            val seriesId = parts.getOrNull(2)?.toLongOrNull() ?: run {
                val slug = slugFrom(epUrl) ?: run {
                    Log.e(TAG, "loadLinks no slug data=$data")
                    return false
                }
                val r = getWithTicket("$mainUrl/api/series/$slug?lang=ar", referer = mainUrl) ?: return false
                if (!r.isSuccessful) {
                    Log.e(TAG, "detail HTTP ${r.code} slug=$slug")
                    return false
                }
                mapper.readValue(r.text, DunDetail::class.java).id ?: run {
                    Log.e(TAG, "loadLinks no seriesId slug=$slug")
                    return false
                }
            }

            // 503 مؤقتة قد يردّها الخادم — الموقع نفسه يعيد المحاولة ×4/~3ث.
            var play: PlayResponse? = null
            for (attempt in 1..3) {
                val r = getWithTicket("$mainUrl/play/$seriesId/$ep", referer = mainUrl) ?: return false
                if (r.isSuccessful) {
                    play = mapper.readValue(r.text, PlayResponse::class.java)
                    break
                }
                if (r.code != 503) {
                    Log.e(TAG, "play HTTP ${r.code} series=$seriesId ep=$ep")
                    return false
                }
                Log.d(TAG, "play 503 retry $attempt series=$seriesId ep=$ep")
                kotlinx.coroutines.delay(1500)
            }
            val p = play ?: return false

            // كل الترجمات المضمّنة — دائمًا مطلقة على dramaflix.net، ورمز اللغة
            // في `dil`؛ ثم لا رابطٌ مكرّر ولا لغةٌ مكرّرة.
            if (showSubs()) {
                val seenUrl = HashSet<String>()
                val seenLang = HashSet<String>()
                p.altyazilar.orEmpty().forEach { s ->
                    val subRaw = s.src?.trim().orEmpty()
                    if (subRaw.isEmpty()) return@forEach
                    val subUrl = abs(subRaw)
                    if (!seenUrl.add(subUrl)) return@forEach
                    val rawLang = s.dil?.trim().orEmpty()
                    val lang = normalizeSubLang(rawLang) ?: rawLang.ifEmpty { "ترجمة" }
                    if (!seenLang.add(lang)) return@forEach
                    try {
                        subtitleCallback(newSubtitleFile(subLangLabel(lang), subUrl) {
                            this.headers = mapOf("Referer" to mainUrl)
                        })
                    } catch (_: Exception) {
                    }
                }
            }

            // الصيغة: `type` يقول hls/mp4، وله روابط بلا امتداد إطلاقاً
            // (mp4 حملة تنتهي بـ`mime_type=video_mp4`) فنُمرّره إلى FormatTag
            // ليكتب [MP4] لا [VIDEO] — انظر FormatTag.label.
            val typeStr = p.type.orEmpty().lowercase()
            val raw = p.url?.trim().orEmpty()
            if (raw.isEmpty()) {
                Log.e(TAG, "play empty url series=$seriesId ep=$ep")
                return false
            }
            val vUrl = abs(raw)
            val declared = when {
                typeStr == "mp4" -> "MP4"
                typeStr == "hls" -> "M3U8"
                else -> null
            }
            val linkType = if (typeStr == "hls" || vUrl.contains(".m3u8")) ExtractorLinkType.M3U8
            else ExtractorLinkType.VIDEO

            // اسم المصدر = `delivery` لا `source_tag` — قِيس 2026-10-08 على 16
            // منصّة: source_tag رمزٌ عشوائي جديد لكل حلقة (mem3cz/4frcxd/…)
            // لا علاقة له بالتسليم، فكان يُصدر اسم مُهاتَر في قائمة المصادر.
            // `psig` لم يُقَس قيمته فيبقى اسم الحلقة — لا نخترع تسمية لم نرها.
            val serverName = when (p.delivery?.lowercase()) {
                "direct" -> "مباشر"
                "relay" -> "وسيط"
                "relay_auth" -> "وسيط مُوثّق"
                else -> "الحلقة $ep"
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = FormatTag.tagged(serverName, vUrl, linkType, declared),
                    url = vUrl,
                    type = linkType
                ) {
                    referer = mainUrl
                    qOf(vUrl)?.let { quality = getQualityFromName(it) }
                    headers = mapOf("Referer" to mainUrl)
                }
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FATAL data=$data", e)
            false
        }
    }

    /**
     * الجودة الحقيقية من المسار — إن غابت لا نخترع اسماً.
     * الأنماط قِيست فعلاً 2026-10-08: `-ld.m3u8`=540×960 و`-sd.m3u8`=720×1280
     * على crazymaple وwolftv، وDramaBox يحقن `.720p.` في اسم الملف، وGoodShort
     * يمرّر `?q=720p`. و`-hd` غير مقاس فلا يُخمَّن.
     */
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