package com.dramatip.plugin

import cloudstreamshared.FormatTag
import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TIP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
private const val TIP_MAIN = "https://dramatip.net/ar"
private const val TIP_HOST = "https://dramatip.net"

// نفس وكيل اللاعب المتنقّل — قِيس أنّ مضيفي التشغيل يميّزون به.
private const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 10)"

// سقف قراءة جسم الفحص: قائمة تشغيل HLS كاملة تقع دونه بمراتب، ويمنع تنزيل VOD ضخم.
private const val READ_CAP = 524_288

// ★ 2026-10-17. أدنى طابعٍ زمنيًّا يُقبل وقتًا لانتهاء رمز (٢٠٢٣-١١-١٤).
// الآن صار للعمر سلطة إسقاط الرابط (بوّابة الرمز المنتهي في loadLinks)،
// فحقلٌ اسمه `t` على منصّةٍ أخرى قد يحمل هاشًا لا وقتًا — فيُحسب سالبًا
// ويُسقط رابطًا حيًّا. الطابع الحقيقيّ لا يسبق هذا الحدّ بسنوات.
private const val MIN_TOKEN_EPOCH = 1_700_000_000L

// منصّات الموقع من /ar/sources (slug → الاسم كما يعرضه الموقع). ٢٣ منصة.
// ثابت على مستوى الملف كي تشاركه الإعدادات (مفتاح إظهار/إخفاء لكل منصّة).
internal val DRAMA_TIP_PLATFORMS: List<Pair<String, String>> = listOf(
    "bibishort" to "BibiShort",
    "bilitv" to "BiliTV",
    "dotdrama" to "DotDrama",
    "dramabite" to "DramaBite",
    "dramabox" to "DramaBox",
    "flickreels" to "FlickReels",
    "goodshort" to "GoodShort",
    "happyshort" to "HappyShort",
    "idrama" to "iDrama",
    "joyreels" to "JoyReels",
    "kalostv" to "KalosTV",
    "moboreels" to "MoboReels",
    "moreshort" to "MoreShort",
    "mydramawave" to "MyDramaWave",
    "netshort" to "NetShort",
    "petadrama" to "PetaDrama",
    "pinedrama" to "PineDrama",
    "playlet" to "Playlet",
    "reelshort" to "Reelshort",
    "shorttv" to "ShortTV",
    "shortwave" to "ShortWave",
    "stardust" to "Stardust",
    "storyreel" to "StoryReel",
)

/**
 * DramaTip — مجمّع دراما قصيرة يجمع أكثر من عشرين منصة (NetShort، MyDramaWave،
 * DramaBox، GoodShort، ReelShort…). الموقع Next.js، وصفحاته تُبثّ «RSC» —
 * تدفّق رحلات Next عبر self.__next_f.push؛ لا __NEXT_DATA__ ولا wp-json.
 * الكتالوج (البطاقات) في <script type="application/ld+json">.
 *
 * المسار:
 * - الرئيسية/البحث: JSON-LD (itemListElement) — صف لكل منصة (مفتاح الصف = slug
 *   المنصة). الصفحة 2+ غير موجودة (كل صيغ الترقيم تعيد البطاقات نفسها — قِيس).
 * - التفاصيل (load): JSON-LD TVSeries (الاسم/البوستر/عدد الحلقات) + روابط
 *   الحلقات الصريحة في HTML (/ar/series/<slug>/episode-N).
 * - التشغيل (loadLinks): روابط الفيديو والترجمة مشفّرة AES-256-GCM بمفتاح ثابت
 *   في JS (QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=). التصفُّر: base64 ⇒
 *   nonce (أول 12 بايت) + ciphertext. Flight كل حلقة يحمل:
 *   sourceFirst.chain[] (فيديو الأساس) وsourceFirst.subtitle (ترجمة webvtt)
 *   وnextSourceFirst (سيرفر بديل). صيغة كل مصدر تُقرأ من الرابط نفسه ومن
 *   إعلان الموقع (type/mime) وتُضاف كوسم [MP4] / [M3U8] إلى اسم السيرفر —
 *   طلب المستخدم الصريح: «أريد أن أفرّق بين الصيغ».
 *
 * يعتمد قليلًا على مطابقة اسم المنصّة بالعربية؛ أسماء الصفوف تُكتب من صفحة
 * /ar/sources بنفس حروفها.
 */
class DramaTipProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "DramaTip"
    override var mainUrl = TIP_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun logD(msg: String) { try { android.util.Log.d("DramaTip", msg) } catch (_: Exception) {} }
    private fun logE(msg: String) { try { android.util.Log.e("DramaTip", msg) } catch (_: Exception) {} }

    // منصّات الموقع من /ar/sources (slug → الاسم كما يعرضه الموقع). ٢٣ منصة.
    // ملاحظة: الصف يعرض بطاقات تلك المنصّة فقط — لكن بعضها (KalosTV مثلًا) قد
    // يكون محتوياته مقفلة/قليلة في الموقع نفسه؛ لا أثر لذلك هنا.
    private val platformRows = DRAMA_TIP_PLATFORMS

    // الصفوف الرئيسية بترتيب العرض: «الأحدث» و«الأكثر مشاهدة» قبل أسماء المنصّات.
    // مفاتيح الصفوف (تُمرَّر في request.data عند البث):
    //   latest  → /ar/category/newly-added
    //   popular → /ar/category/populer
    //   <slug>  → /ar/source/<slug>
    //
    // كل منصّة تُعرض/تُخفى بمفتاحها المستقل dt_platform_<slug> (الافتراضي ظاهر).
    private fun homeRows(): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        if (prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_LATEST, true) != false)
            rows.add("latest" to "الأحدث")
        if (prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_POPULAR, true) != false)
            rows.add("popular" to "الأكثر مشاهدة")
        if (prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_PLATFORMS, true) != false) {
            for ((slug, label) in platformRows) {
                if (prefs?.getBoolean(DramaTipSettingsBottomSheet.platformKey(slug), true) != false)
                    rows.add(slug to label)
            }
        }
        return rows
    }

    override val mainPage = mainPageOf(*homeRows().map { (k, l) -> k to l }.toTypedArray())

    private fun reqHeaders() = mapOf(
        "User-Agent" to TIP_UA,
        "Accept-Language" to "ar,en;q=0.8",
        "Referer" to TIP_MAIN
    )

    /**
     * ★ 2026-10-16. انتهاء صلاحية رمز البثّ من الرمز نفسه.
     *
     * قِيس في 2026-10-16 على `cdnvideo.cdreader.com` (حافّة TencentEdgeOne):
     *  - `sign` ناقص أو منتهٍ ← `403` وجسمه حرفيًّا `need sign info`.
     *  - الرابط الموقّع الطازج ← `200` ونوعه `application/vnd.apple.mpegurl`.
     *  - والجهاز نفسه (خارج التطبيق، عبر `nc`) يجلب الرمز الطازج بـ`200`،
     *    فشبكته بريئة والـ403 ليس حكمًا على المضيف.
     *
     * والمصدر الحقيقي للعلّة: **الوصفة مُخبَّأة** (`cache-control: private,
     * max-age=300`)، وعُقد dramatip تخدم نُسخًا متباعدة. قِيس على عقدتين
     * لاسمٍ واحد: `104.21.44.220` أعطت رمزًا `t` ينتهي بعد **دقيقتين**،
     * و`172.67.204.32` أعطت رمزًا ينتهي بعد **٣٥ دقيقة**. فحلقةٌ تُصيب
     * نسخةً شبه منتهية تُفحَص بعد ثانيتين فتردّ `403` وتُسقَط، فيرى المستخدم
     * «لم يعثر على روابط» على منصّةٍ يعمل محتواها في الموقع.
     *
     * والحقل `t` في الاستعلام **وقتُ الانتهاء** لا وقت الصكّ: قِيس أن الفرق
     * بين `t` ووقت الطلب يبلغ ٢٦–٥٩ دقيقة (أطول من عمر جلسة المشاهدة).
     * فبه نعرف العمر الباقي بلا ساعةٍ إضافية على الشبكة.
     */
    private fun tokenExpiryEpoch(url: String): Long? {
        val h = Regex("""[?&]t=([0-9a-fA-F]{8,})""").find(url)?.groupValues?.get(1) ?: return null
        // ★ 2026-10-17. حدُّ المعقوليّة ضروريّ الآن لأنّ العمر صار يُسقط الرابط
        // (بوّابة الرمز المنتهي): حقلٌ اسمه `t` قد يحمل قيمةً ليست طابعًا زمنيًّا
        // على منصّةٍ أخرى (هاشًا قصيرًا)، فيُحسب عمره سالبًا ويُسقط رابطًا حيًّا.
        // فالطابع الموقّع لا يسبق ٢٠٢٣ (‎1_700_000_000‎) — وما دونه ليس وقتًا.
        val v = h.toLongOrNull(16) ?: return null
        return v.takeIf { it >= MIN_TOKEN_EPOCH }
    }

    /**
     * ثوانٍ باقية لصلاحية كل رمز في المرشّحين (null = لا رمزَ زمنيّ فلا حكم).
     *
     * والنوعان المقيسان رمزان ينتهيان بوقت **مطلق**:
     *  - `?t=<hex>` على MoboReels/منصّات `cdreader.com` و`adp.10.m3u8`.
     *  - `?verify=<ثوانٍ>` على Playlet (`zshipricf.farsunpteltd.net`): الالتقاط
     *    أظهر `verify=1792166498` يفشل بـ403 وهو **ماضٍ بساعتين**، وآخر
     *    `verify=1792174203` فشل وهو ماضٍ بـ٤ دقائق — فالاثنان وقتُ انتهاء.
     *
     * وتُعرض القيمة السالبة كما هي: «عمر الباقي -7533s» في السجلّ تعني رمزًا
     * ميتًا بُثّ، وهي الدليل القاطع في التقاط المستخدم.
     */
    private fun tokenRemainingSeconds(url: String): Long? {
        val now = System.currentTimeMillis() / 1000
        tokenExpiryEpoch(url)?.let { return it - now }
        Regex("""[?&]verify=(\d{9,})""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?.takeIf { it >= MIN_TOKEN_EPOCH }?.let { return it - now }
        return null
    }

    /**
     * ★ 2026-10-17. **كم بقي** حتى موعد بلا الوصفة المعلن (`refreshAfter`)؟
     *
     * ★ تصحيح إشارةٍ مهمّ: كان هذا الدالّة تُرجع `now - refreshAfter`، وكان
     * الشرط `staleness < -1` — وهذا **معكوس**. قِيس اليوم على كتاب 13100415
     * بالوصفتين مباشرةً (2026-10-17 06:11 UTC):
     *  - وصفةٌ **مخبّأة** (`?lang=ar`): `refreshAfter = now + 617` أي **لم يحن
     *    التجديد بعد** ⇒ هي الطازجة، والكود كان يرميها «بائتة».
     *  - وصفةٌ **مُجدَّدة** (`?lang=ar&refresh=1`): `refreshAfter = now + 2927`
     *    أي أطول عمرًا ⇒ والأولى أقصر منها فقط لأنّها وُلدت قبلها.
     * فالحقل إعلانٌ بموعد التجديد، وصارت الدالّة تُرجع **المتبقّي** (سالبًا إذا
     * فات الموعد)، والشرط صريحًا: `remaining < 0`.
     */
    private fun descStaleness(descJson: String): Long? {
        val nowSec = System.currentTimeMillis() / 1000
        val vals = Regex(""""refreshAfter"\s*:\s*(\d{6,})""")
            .findAll(descJson)
            .map { it.groupValues[1].toLongOrNull() }
            .filterNotNull()
            .toList()
        if (vals.isEmpty()) return null
        // أصغر متبقٍّ = أسوأ مرشّحٍ يحكم البثّ.
        return (vals.min()) - nowSec
    }

    /**
     * ★ 2026-10-17. عمر أفضل رمزٍ في الوصفة — يُقرأ **محليًّا** بلا شبكة.
     *
     * بوّابة `refreshAfter` أعلاه لا تكفي: قِيس اليوم على MoboReels (كتاب
     * 13100415) أنّ الوصفة تُخبَّأ **بلا `refreshAfter` إطلاقًا** (`None`)
     * بينما رمزها منتهٍ فعلًا (‎-700ث‎ فحصًا مباشرًا)، وأنّ الطلب نفسه
     * بـ`refresh=1` يعود برمزٍ صالح (~3500ث) **14/14**. فالرمز هو الحقيقة
     * التي لا تغيب: نقرؤه من الرابط المفكوك، ونردّ الوصفة بائتة إن انتهى.
     *
     * ويُؤخذ **أطول** عمرٍ في السلسلة، فالأفضل هو ما يُبثّ أولًا.
     * وnull حين لا يحمل أيُّ مرشّحٍ رمزًا زمنيًّا (فلا حكم).
     */
    private fun descBestLife(descJson: String): Long? {
        var best: Long? = null
        for (re in listOf(encRe, qualRe)) {
            for (m in re.findAll(descJson)) {
                val u = decryptOrSkip(m.groupValues[1]) ?: continue
                if (!u.startsWith("http") || u.contains("subtitle", ignoreCase = true)) continue
                val life = tokenRemainingSeconds(u) ?: continue
                val b = best
                if (b == null || life > b) best = life
            }
        }
        return best
    }


    private fun escapeSlug(slug: String): String =
        URLEncoder.encode(slug, "UTF-8").replace("+", "%20")

    private suspend fun fetchText(url: String): String? = try {
        app.get(url, referer = TIP_MAIN, headers = reqHeaders()).text
    } catch (e: Exception) { logE("fetch $url: ${e.message}"); null }

    /**
     * ما تحمله صفحة السلسلة من معطياتٍ **لا تتعلّق بحلقةٍ بعينها**: الكتاب
     * (`bookId`) وأوّل رقم تسلسلي واسم المصدر. هذه فقط تُخبَّأ.
     *
     * ★ ولماذا لا يُخبَّأ الحِمل كلّه؟ قِيس على FlickReels أنّ صفحة السلسلة
     * تحمل سلسلة الحلقة **الأولى** (`…hls_78186…`) بينما صفحة episode-7 تحمل
     * `…hls_84268…` — فالرموز تختلف بالحلقة. فلو خُبّئ الحِمل كلّه لخدمنا
     * حلقةً أخرى رابطَ الحلقة الأولى: يفتح المستخدم الحلقة ٧ فيرى الحلقة ١
     * — وهو أسوأ من «لم يعثر على روابط». أمّا `bookId`/`firstSerial` فواحدٌ
     * للسلسلة كلّها (قِيس على المنصّات السبع)، والـAPI يُسأل بهما عن الحلقة
     * الصحيحة (`serial = ep + firstSerial - 1`).
     *
     * والفائدة المقصودة: إن سقطت صفحة الحلقة سقوطًا عابرًا، لم نَعُد نعيد
     * `false` فورًا؛ بل نمضي إلى مسار الـAPI الذي لا يحتاج تلك الصفحة أصلًا.
     */
    private class SeriesMeta(val bookId: String?, val firstSerial: Int, val source: String?)

    private val seriesMeta = java.util.concurrent.ConcurrentHashMap<String, SeriesMeta>()

    // سقفٌ صغير: مفاتيح خفيفة، والغرض ألّا تسقط سلسلةُ الجلسة الواحدة.
    private fun putSeriesMeta(key: String, m: SeriesMeta) {
        if (key.isBlank()) return
        if (seriesMeta.size > 64) seriesMeta.clear()
        seriesMeta[key] = m
    }

    /**
     * سلَغٌ واحد من رابط السلسلة ومن `Episode.data` — ليتطابق مفتاح الخِبأ فيهما.
     *
     * وقد يأتي سلَغ الصفحة صريحاً (`قصة-انتصار`) أو مُرمَّزاً
     * (`%D9%82%D8%B5%D8%A9…`) حسب الطريق الذي فتح بها المستخدم الحلقة،
     * فنفكّ الترميز ليجتمع المفتاحان على حالٍ واحدة. وإلّا لذهب الخِبأ
     * سُدىً وهو جوهر هذا الإصلاح.
     */
    private fun bareSlug(s: String): String {
        // نشذّ ما بعد «؟»/«#» **ثم** الذيل المائل: عنوانٌ ينتهي بـ«/» كان يعطي
        // مقطعًا فارغًا (`substringAfterLast('/')` = "") فيُسقط putSeriesMeta
        // المفتاح الفارغ صامتًا ⇒ لا كاش ⇒ لا bookId ⇒ «لا روابط».
        val path = s.substringBefore('?').substringBefore('#').trimEnd('/')
        val raw = path.substringAfterLast('/').trim()
        if (raw.isBlank()) return ""
        if ('%' !in raw) return raw
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8").trim()
        } catch (e: Exception) { raw }
    }

    /**
     * هل الحِمل حِملُ **حلقةٍ حقيقيّة** أم صفحةُ خطأ/404 بزيّ Next؟ كلاهما
     * يُنتج flight غير فارغ، فلا يصلح `null` وحده فاصلًا. والفارق المقيس:
     * كائن `sourceFirst` (وما فيه من `bookId`) **لا يوجد إلا في حِملٍ صحيح**.
     *
     * لماذا هذا الفصل جوهريّ: قِيس 2026-10-16 على FlickReels أنّ
     * ‎/episode-999‎ و‎/episode-abc‎ و‎/episode-0‎ **والسلَّغ المُرمَّز مرّتين**
     * تعيد كلّها flight بحجم ‎101KB‎ بلا `sourceFirst` وبلا `bookId` (254KB أقلّ
     * من الصفحة الحقيقيّة). وكان `loadLinks` يقرأها بالكائن لا بالحقل:
     * `f?.let { metaOf(it) } ?: seriesMeta[...]` — فحملٌ غير فارغ ⇒ يُختار
     * `metaOf` الفارغ ⇒ `bookId=null` ⇒ **لا يُسأل الـAPI أصلًا**، و`parseChain`
     * يعود فارغًا ⇒ «لم يعثر على روابط». والصفحة الحقيقيّة للسلَّغ نفسه موجودة
     * في الكاش من `load()`، فلا يجوز أن يُحجب عنها.
     */
    private fun hasSourceFirst(f: String?): Boolean =
        f != null && f.contains("\"sourceFirst\"")

    /**
     * يجلب حِمل صفحة الحلقة، ويكرّر الفشل **العابر** حتّى ثلاث مرّات.
     *
     * لماذا: هذا الجلب هو **قنّاص «لم يعثر على روابط»**. قِيس على FlickReels
     * أنّ `curl` يفشل مرّةً كلّ بضع محاولات (‎000 — انتهاء مهلة الاتصال،
     * والنجاح قد يستغرق ‎9.8s‎ وهو دون مهلة التطبيق)؛ وكان `loadLinks` يجلب
     * الصفحة **مرّةً واحدة بلا إعادة** ثم يعيد `false` فورًا — *قبل* أن يسأل
     * الـAPI أصلًا. فيرى المستخدم «لم يعثر على روابط» ورابطُ الـAPI الحيّ لم
     * يُسأل عنه بعد. والتكرار لا يضرّ: السلسلة مهلةٌ والطلب واحد.
     *
     * ولا نكرّر على المسارات المتوازية (البحث يجلب ~٢٠ صفحة معًا) — التكرار
     * هناك يضاعف الحمل على خادمٍ هشّ. هذا الجلب وحيدٌ في مساره.
     */
    private suspend fun episodeFlight(url: String): String? {
        for (attempt in 1..3) {
            val html = fetchText(url)
            if (html != null) {
                val f = extractFlight(html)
                // ★ 2026-10-16. لا يكفي ألّا يكون null: صفحة 404 تُنتج flight
                // بحجم 101KB بلا `sourceFirst` (انظر hasSourceFirst). فمعيار
                // القبول صار وجودَ `sourceFirst` صريحًا، وما دونه يُعالَج
                // كفشلٍ عابر يُعاد، فإن أبى أن يتحسّن عاد null فيُتّكأ على
                // معطيات الكاش بدل أن يُحجب عنها.
                if (hasSourceFirst(f)) return f
                logE("DramaTip: flight بلا sourceFirst (صفحة خطأ/404؟) attempt=$attempt/3 url=$url")
            } else {
                logE("DramaTip: episode flight attempt=$attempt/3 url=$url")
            }
            if (attempt < 3) delay(700L * attempt)
        }
        return null
    }

    /**
     * يجلب **نصّ** ملفّ الترجمة من رابطها. لا يُعاد المحاولة ولا يُطلق استثناءً:
     * ترجمةٌ تعذّرت لا يجوز أن تُفقد الحلقة روابطها.
     *
     * ولماذا لا نسلم الرابط نفسه للمشغّل: قِيس على MoboReels أن رابط الموقع
     * ‎…/api/v1/series/{id}/subtitles/{n}?format=srt‎ **بلا امتداد** — والمشغّل
     * يميّز مسار الترجمة بالامتداد ونوع المحتوى، وهذا ما يجعل الخادم المحلي
     * يخدمها موسومةً `text/vtt` و`.vtt`.
     */
    private suspend fun fetchSubtitleText(url: String): String? = try {
        app.get(
            url,
            referer = platformReferer(url),
            headers = mapOf(
                "User-Agent" to TIP_UA,
                "Accept" to "*/*",
                "Accept-Language" to "ar,en;q=0.8"
            ),
            timeout = 20_000L
        ).text.takeIf { it.isNotBlank() }
    } catch (e: Exception) { logE("subtitle $url: ${e.message}"); null }

    // ---------- JSON-LD ----------
    private fun jsonLdDoc(html: String): org.json.JSONObject? {
        val m = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return try { org.json.JSONObject(m.groupValues[1]) } catch (_: Exception) { null }
    }

    private fun graphNodes(doc: org.json.JSONObject?): List<org.json.JSONObject> {
        if (doc == null) return emptyList()
        return try {
            val g = doc.optJSONArray("@graph")
            if (g != null) (0 until g.length()).mapNotNull { i -> g.optJSONObject(i) }
            else listOf(doc)
        } catch (_: Exception) { listOf(doc) }
    }

    // كل mainEntity يحمل itemListElement (بطاقات كتالوج)؛ وقد تحمل العقدة
    // نفسُها itemListElement مباشرةً (كما في تصنيفات الموقع) — نأخذهما معًا.
    private fun collections(html: String): List<org.json.JSONObject> {
        val out = mutableListOf<org.json.JSONObject>()
        for (node in graphNodes(jsonLdDoc(html))) {
            val me = node.optJSONObject("mainEntity")
            if (me != null && me.has("itemListElement")) out.add(me)
            else if (node.has("itemListElement")) out.add(node)
        }
        return out
    }

    private fun parseCollection(html: String, seen: MutableSet<String>): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (me in collections(html)) {
            val items = me.optJSONArray("itemListElement") ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val url = it.optString("url").takeIf { u -> u.isNotBlank() } ?: continue
                val title = it.optString("name").replace(Regex("""\s+"""), " ").trim()
                if (title.isBlank() || !seen.add(url)) continue
                val poster = it.optString("image").takeIf { p -> p.isNotBlank() }
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            if (page > 1) return null // لا ترقيم (قِيس: كل الصيغ تعيد الـ 24 نفسها)
            val slug = request.data.takeIf { it.isNotBlank() } ?: return null
            // «الأحدث» و«الأكثر مشاهدة» من تصنيفات الموقع؛ غيرها = مصدر/منصّة.
            val url = when (slug) {
                "latest" -> "$TIP_MAIN/category/newly-added"
                "popular" -> "$TIP_MAIN/category/populer"
                else -> "$TIP_MAIN/source/${escapeSlug(slug)}"
            }
            val h = fetchText(url) ?: return null
            val items = parseCollection(h, java.util.HashSet())
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val seen = java.util.HashSet<String>()
            val allItems = mutableListOf<SearchResponse>()

            // 1) البحث العام (كما كان) — يعيد 24 نتيجة
            val general = fetchText("$TIP_MAIN/series?q=$q")
            if (general != null) {
                val items = parseCollection(general, seen)
                allItems.addAll(items)
            }

            // 2) البحث في كل منصّة على حدة — بعضها لا يظهر في البحث العام
            // ننفّذ الطلبات بالتوازي لتقليل المهلة الكلية
            val platformSlugs = platformRows.map { it.first }
            val results = coroutineScope {
                platformSlugs.map { slug ->
                    async(Dispatchers.IO) {
                        val url = "$TIP_MAIN/source/${escapeSlug(slug)}?q=$q"
                        val h = fetchText(url)
                        if (h != null) parseCollection(h, seen) else emptyList()
                    }
                }.awaitAll()
            }
            for (items in results) allItems.addAll(items)

            if (allItems.isEmpty()) null else allItems
        } catch (e: Exception) { null }
    }

    // ---------- التفاصيل (load) ----------
    private fun tvsNode(html: String): org.json.JSONObject? {
        for (node in graphNodes(jsonLdDoc(html))) {
            if (node.optString("@type") == "TVSeries") return node
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val h = fetchText(url) ?: return null
            // نحفظ معطيات السلسلة (الكتاب وأوّلٌ تسلسلي واسم المصدر) الآن:
            // فيها يُسأل الـAPI عن روابط أيّ حلقة، ولا تتغيّر بحلقةٍ بعينها
            // (بخلاف الرموز المشفّرة في `chain` — انظر SeriesMeta).
            // لا نُخبِّئ إلا معطًى **قابلًا للاستعمال** (bookId أو مصدر): فحِمل
            // صفحة 404 يمرّ من هنا بلا `sourceFirst`، وتخبئته كانت تُنشئ مدخلًا
            // فارغًا يحجب كلّ كتابٍ حقيقيّ لاحق عن نفس السلَّغ.
            extractFlight(h)?.let { fl ->
                if (hasSourceFirst(fl)) {
                    val m = metaOf(fl)
                    if (m.bookId != null || m.source != null) {
                        putSeriesMeta(bareSlug(url), m)
                        logD("DramaTip: meta cached slug=${bareSlug(url)} book=${m.bookId} src=${m.source}")
                    }
                } else {
                    logE("DramaTip: load() flight بلا sourceFirst (صفحة 404؟) url=$url")
                }
            }
            val tvs = tvsNode(h) ?: return null
            val title = tvs.optString("name").replace(Regex("""\s+"""), " ").trim()
                .takeIf { it.isNotBlank() } ?: return null
            val poster = tvs.optString("image").takeIf { it.isNotBlank() }
            val total = tvs.optInt("numberOfEpisodes").takeIf { it > 0 } ?: 1

            // روابط الحلقات الصريحة (الموقع يكتب /ar/series/<slug>/episode-N في HTML).
            //
            // ★ 2026-10-16. زرّ «الحلقة التالية/السابقة» يحمل هو أيضًا hrefًّا
            // لـepisode-N، وقِيس على سلسلةٍ بحلقةٍ واحدة أنّ قائمتها تعرض
            // episode-1 وحدها بينما يشير الزرّ إلى episode-2 **وهي صفحة 404**،
            // فتُبنى في التطبيق حلقةٌ شبحية أخيرة لا يعملها الموقع أصلًا — وهذا
            // هو «الحلقة تظهر ولا تعمل» بالضبط. الرقم المذكور في زرّ التنقّل
            // وحده ليس دليلًا على وجود الحلقة؛ وما يُذكر في قائمة الحلقات يبقى
            // صالحًا مهما قال الزرّ (ففي السلاسل الطويلة يشير الزرّ إلى حلقةٍ
            // قائمة أصلًا). نستبعد الأرقام التنقّلية الوحيدة ونبقى الباقي كما هو.
            val epHref = Regex("""/ar/series/([^"/\s]+)/episode-(\d+)""")
            val navTail = Regex("""الحلقة\s*(التالية|السابقة|القادمة|الأخيرة|الماضية)""")
            val hrefs = epHref.findAll(h).toList()
            val fromList = HashSet<Int>()
            val fromNav = HashSet<Int>()
            for (m in hrefs) {
                val n = m.groupValues[2].toIntOrNull() ?: continue
                // ما بعد الرابط حتى `</a>`: زرّ التنقّل يعلّق اسمه هناك فورًا.
                val seg = h.substring(m.range.last + 1)
                val stop = seg.indexOf("</a>").takeIf { it in 0..240 }
                    ?: minOf(240, seg.length)
                if (navTail.containsMatchIn(seg.substring(0, stop))) fromNav.add(n)
                else fromList.add(n)
            }
            val phantom = fromNav.filter { it !in fromList }.toSet()
            if (phantom.isNotEmpty()) {
                logD("DramaTip: load() حلقات شبحية من زرّ التنقّل مستبعدة: $phantom slug=${bareSlug(url)}")
            }
            val listed = hrefs.mapNotNull { m ->
                val n = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (n in phantom) return@mapNotNull null
                n to m.groupValues[1]
            }
            val pairs = if (listed.isNotEmpty()) {
                listed.distinctBy { it.first }.sortedBy { it.first }
            } else {
                (1..total).map { it to url.substringAfterLast('/') }
            }

            val eps = pairs.map { (n, slug2) ->
                newEpisode("$slug2|$n") {
                    this.episode = n
                    this.name = "الحلقة $n"
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
            }
        } catch (e: Exception) { null }
    }

    // ---------- فكّ تدفّق RSC ----------
    // كل حزمة: self.__next_f.push([1,"..."]). الجسم نصٌّ JSON مُهرَّب (\uXXXX،
    // \\"…) — نُهرّبه إلى النص الفعلي ثم نجمع الحزم. الكل لا بد منه لقراءة
    // أسماء/حقول عربية ومواقع الحقول المشفّرة (فالنصوص العربية تأتي \u لهم).
    private fun parseHex4(raw: String, at: Int): Int {
        var v = 0
        var k = 0
        while (k < 4 && at + k < raw.length) {
            val d = raw[at + k]
            val x = when (d) {
                in '0'..'9' -> d - '0'
                in 'a'..'f' -> d - 'a' + 10
                in 'A'..'F' -> d - 'A' + 10
                else -> return v
            }
            v = (v shl 4) or x
            k++
        }
        return v
    }

    private fun unescapeJsonStr(raw: String, from: Int, to: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < to) {
            val c = raw[i]
            if (c == '\\' && i + 1 < to) {
                when (val n = raw[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        var code = parseHex4(raw, i + 2)
                        i += 6
                        if (code in 0xD800..0xDBFF && i + 6 <= to &&
                            raw[i] == '\\' && raw[i + 1] == 'u'
                        ) {
                            val low = parseHex4(raw, i + 2)
                            if (low in 0xDC00..0xDFFF) {
                                code = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                                i += 6
                            }
                        }
                        sb.appendCodePoint(code)
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    // (Regex بلا DOT_MATCHES_ALL — الحزم واحدة سطر.)
    private val flightRe = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")

    private fun extractFlight(html: String): String? {
        val sb = StringBuilder()
        var any = false
        for (m in flightRe.findAll(html)) {
            any = true
            sb.append(unescapeJsonStr(m.groupValues[1], 0, m.groupValues[1].length))
        }
        return if (any) sb.toString() else null
    }

    // ---------- AES-256-GCM (المفتاح الثابت في JS) ----------
    private val aesKey: ByteArray = android.util.Base64.decode(
        "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=", android.util.Base64.NO_WRAP
    )

    // مرجع المنصّة من الرابط: آخر نطاقين (مثال: shorttv.live، netshort.com).
    // للبروكسي /e/m/ أو /e/s/ (نارتو) نستخدم dramatip.net لأن اللاعب يُمرّر من هناك.
    private fun platformReferer(url: String): String {
        val isNartoProxy = url.contains("/e/m/") || url.contains("/e/s/")
        if (isNartoProxy) return TIP_MAIN
        val host = Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1)
        if (host == null) return TIP_MAIN
        val parts = host.split('.')
        val root = if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        return "https://$root/"
    }

    private fun decryptEnc(enc: String): String {
        val b = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
        val spec = javax.crypto.spec.GCMParameterSpec(128, b, 0, 12)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), spec)
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    // ---------- روابط التشغيل ----------
    // أنواع الموقع التي تعني HLS صراحةً (قِيست على ٢٣ منصّة):
    // hls، hls-enc (صيغة joyreels/shorttv)، goodshort، dramawave، storyreel.
    // لماذا نحتاجها: GoodShort تبثّ HLS من مسارٍ بلا ‎.m3u8
    // (…/hls/61502918?q=720p) — قِيس 200 application/vnd.apple.mpegurl و‎#EXTM3U —
    // فتصنّفه قراءةُ الامتداد وحدها mp4 خطأً، فيحاول اللاعب تفكيكه كملفٍ تدريجيّ
    // ويفشل. فيتقدّم نوعُ الموقع ويُصلح الوسم والنوعيّ معًا.
    private val hlsTypes = setOf("hls", "hls-enc", "goodshort", "dramawave", "storyreel")

    // ★ مفتاح العطل: الصفحة تُسلّم وصفةً مُخبَّأة. قِيس 2026-10-12 على DramaBite أنّ
    // الوصفة نفسها في HTML تحمل رمزًا عمره 8 ساعات (wsTime -28951s) فيردّ الـCDN
    // 403، بينما /api/episode-source/{book}/{serial}?lang=ar&refresh=1 يعطي الحلقة
    // نفسها رمزًا عمره 5 ثوانٍ فيردّ 200 — والفرق بين الطلبين سِتّة أحرف.
    // وصفة الـAPI لا تفقد شيئًا: قِيس أنّها تحمل chain وencByQuality وسطر الترجمة
    // كما في الصفحة (shorttv: ٤٨٠/٧٢٠/١٠٨٠؛ petadrama: ٥٤٠/٧٢٠/١٠٨٠ + ترجمة ar).
    private val tipApi = "$TIP_HOST/api/episode-source"

    // ★ 2026-10-14. الوصفة تُسلَّم كـ {"source":…,"chain":[{"enc":…,"type":…,
    // "source":…,"encByQuality":{…}},{"enc":…,…}], "subtitle":null|{…}}.
    //
    // الكود القديم «plainFromDescriptor» جمع فقط حقول القيمة النصية على عمقٍ
    // ثابت (depth==2) — أي فقط داخل كائن subtitle. فعندما يكون subtitle = null
    // (PineDrama، NetShort، FlickReels، …) يُنتج نصًّا فارغًا، و parseChain()
    // يعود به []، فيُظهر «لا روابط» حتى لو أن الوصفة حاملة chain كاملة.
    //
    // والحلّ بسيط: لا نُفلطح الـJSON، نُعيد له هيكله كاملاً (كما كان يفعل
    // pageFlight) بدل جمع الحقول. هذا يُطابق parseChain()/sourceName()/
    // objectAfterKey("subtitle") التي كلّها تعمل على كائن sourceFirst.
    private fun plainFromDescriptor(json: String): String {
        // json = محتوى descriptor (Object). نُلفّه بـ "sourceFirst" كي يجده
        // parseChain()/sourceName()/objectAfterKey("subtitle") كما يجدونه في
        // pageFlight. الـJSON الأصلي يبقى كما هو — لا تفكيك.
        return "\"sourceFirst\":$json"
    }

    /**
     * كل الروابط المُفكَّكة (بالصيغة التي يفهمها كود الاستخراج القائم: حقول
     * ‎enc/qال‎ مفصولة بـNUL) من وصفة الـAPI — أو null إن فشل الطلب.
     *
     * طلبٌ منفصل ومعزول تمامًا عن الوصفة الأساسية: أيّ فشل هنا يعيد null فيسقط
     * الكود على سلوكه القديم، ولا تكون «إعادة الجلب» سببًا في فقدان روابط.
     */
    private suspend fun apiLinks(bookId: String, serial: Int, bust: Boolean = false): String? {
        // ★ 2026-10-16. كانت مهلةً واحدة ثابتة (٢٠ث) في كل محاولة، ومحاولات
        // الكود الثلاث تتقاسم الفشل نفسه. الصيغة الجديدة: محاولتان بمهلةٍ أقصر
        // (١٢ث) تُبقيان الزمن الكلي في حدود رسالةٍ واحدة، ثم محاولةٌ ثالثة
        // بمهلةٍ أطول (٢٥ث) تُنجي حين يكون المضيف بطيئًا لا غائبًا — وهو
        // الفرق بين «انتهت المهلة» و«لا روابط». ولا يضرّ الحالة السليمة:
        // المهلة سقفٌ لا انتظار، فالطلب الناجح يعود فورًا.
        //
        // ولم نُضاعف بالتوازي: قِيس أنّ الخدمة إمّا تردّ كاملةً أو تفشل
        // كاملةً في اللحظة (١٢ طلبًا متوازيًا نجحت كلّها، و٤٠ متتابعًا كذلك)،
        // فالتغطية المكانيّة لا تُنجي شيئًا والعلاج زمنيّ.
        // ★ 2026-10-16. `refresh=1` لا يخترق تخزين الحافّة دائمًا: قِيس في
        // 2026-10-16 أنّ `refresh=1` ومعاملًا عشوائيًّا و`no-cache` تعيد
        // **الرمز نفسه** داخل نافذة الذاكرة (‎max-age=300‎). والعلّة الحقيقيّة
        // أنّ عُقد dramatip تخدم نُسخًا متباعدة: عقدتان لاسمٍ واحد أعطتا
        // `t` بعمر **دقيقتين** وأخرى بعمر **٣٥ دقيقة**. فالعلاج ليس اختراق
        // الذاكرة بل **إعادة الطلب** (قد تُصيب نُسخةً أطزج) ثم خطّ نجاةٍ
        // يُبثّ فيه المنتهي. ويُضاف المعامل العشوائي لأنّه لا يضرّ، وقد
        // يُصيب مفتاحًا مختلفًا على عقدةٍ أخرى. أثره هنا: رمزٌ بعمر ~٥٠ دقيقة.
        val baseUrl = "$tipApi/$bookId/$serial?lang=ar&refresh=1"
        var lastWhy = "-"
        for (attempt in 1..3) {
            // ★ 2026-10-17. مضادّ الذاكرة **فريدٌ لكلّ محاولة** لا لكلّ نداء.
            // قِيس على الجهاز أنّ ثلاث محاولاتٍ بالرابط نفسه تعيد **الجسم
            // البائت نفسه** (تسجيل: `field=-2837s` يتكرّر بالثانية نفسها)،
            // فالمحاولات كانت عبثًا. وبمفتاحٍ متغيّرٍ قد تصيب نُسخةً أطزج على
            // العقدة نفسها أو مفتاحًا على عقدةٍ أخرى — وهي العلّة المقيسة:
            // عقدُ dramatip تخدم نُسخًا متباعدة (نسخةٌ عمرها ٥٢ ألف ثانية
            // مقابل أختها الطازجة للكتاب نفسه).
            val url = baseUrl +
                if (bust || attempt > 1) "&_=${System.currentTimeMillis()}$attempt" else ""
            try {
                val timeout = if (attempt >= 3) 25_000L else 12_000L
                val res = app.get(url, referer = TIP_MAIN, headers = reqHeaders(), timeout = timeout)
                if (res.code in 200..299) {
                    val desc = org.json.JSONObject(res.text).optJSONObject("descriptor")
                    // وصفةٌ بلا chain لا تُغني (قد تكون استجابةً جزئيّة): تُعامَل
                    // فشلًا فتُعاد، ولا تُبتلع صامتةً فيرى المستخدم «لا روابط».
                    if (desc != null && desc.optJSONArray("chain") != null) {
                        // ★ 2026-10-16. بوّابة الطزاجة. الالتقاط أثبت أنّ الرمز قد
                        // يصل **منتهيًا** فعلًا (‎Source error‎ ٤٠٣ في اللاعب على
                        // `cdnvideo.cdreader.com`)، والوصفة نفسها تُعلن موعد
                        // البلى: `refreshAfter` طابعٌ زمنيّ، وبعده `refresh=true`.
                        // قِيس على كتابٍ عالق: بلا `refresh=1` يتبقّى **-٢٫٣ ساعة**،
                        // ومعه **+٤٤ دقيقة**، ١٢/١٢. فالوصفة التي `refreshAfter`
                        // فيها ماضٍ لا تُقبل — يُعاد طلبها بمفتاحٍ متغيّر فورًا
                        // بدل انتظار مهلةٍ كاملة. وإن أصرّ الأصل على نسخةٍ بائتة،
                        // قبولها خيرٌ من null: خطّ نجاة البثّ يبقيها، واللاعب قد
                        // يجلب القائمة فيجدّدها بنفسه.
                        val staleness = descStaleness(desc.toString())
                        // ★ 2026-10-17. الحكم الثاني — وهو الحاسم: **عمر الرمز
                        // نفسه**. `refreshAfter` قد يغيب تمامًا (قِيس اليوم
                        // `None` على الوصفة المخبّأة لكتابٍ رمزُه منتهٍ)، فلا
                        // تُكتشف البلى بالحقل بل بالرمز. وحين ينتهي الرمز يكون
                        // بثّه خطأ 2004 (BAD_HTTP_STATUS) حتمًا: القائمة تفتح
                        // ثم يرفض الحافّة قطعةَ الفيديو بـ403.
                        val bestLife = descBestLife(desc.toString())
                        // ★ 2026-10-17. الصيغة الصحيحة (`refreshAfter` = موعد
                        // التجديد، والوصفة بائتةٌ إذا **فات**): انظر descStaleness.
                        val staleByField = staleness != null && staleness < 0
                        val staleByToken = bestLife != null && bestLife <= 0
                        // ★ 2026-10-17. إذا أعلن الحقل أنّ موعد التجديد **فات** لكنّ
                        // الرمز المفكوك **ما زال حيًّا**، فلا تطعن في الرمز: عقدُ
                        // dramatip تخدم نُسخًا متباعدة، فـ`refreshAfter` يخبّئه
                        // حدودُ حافّةٍ أقدم بينما الرمز الذي بين أيدينا لم ينتهِ
                        // بعد. قِيس على كتاب 3896: `refreshAfter` يسبق `verify`
                        // بنحو ٦٠٠ث (كلاهما وقتُ انتهاء)، فالنسخة «بائتة الحقل»
                        // قد تكون «حيّة الرمز» — وبثُّها سليمٌ ما لم ينتهِ الرمز.
                        // كان الكود يُهدر ثلاث محاولات ثم يُعيد الجسم البائت
                        // نفسه فيُسقط رابطًا صالحًا (‎token=+3002s‎ مقابل
                        // ‎life=-52141s‎ في السجلّ). فالحقل يحكم فقط حين لا رمز.
                        if (bestLife != null && bestLife > 0) {
                            android.util.Log.d(
                                "DramaTip",
                                "episode-source accept (token حيّ $bestLife s يتقدّم على الحقل" +
                                    " ${staleness ?: "?"}s) book=$bookId ser=$serial"
                            )
                            return plainFromDescriptor(desc.toString())
                        }
                        if (staleByField || staleByToken) {
                            lastWhy = if (staleByToken)
                                "رمزٌ منتهٍ (عمر ${bestLife}ث)"
                            else "وصفة بائتة (فات موعدها بـ${-staleness!!}ث)"
                            android.util.Log.e(
                                "DramaTip",
                                "episode-source stale book=$bookId ser=$serial" +
                                    " field=${staleness ?: "?"}s token=${bestLife ?: "?"}s"
                            )
                            if (attempt < 3) {
                                delay(120L)
                                continue
                            }
                            // استُنفدت المحاولات والأصل مُصرّ على نسخةٍ منتهية.
                            // هذه هي الحالة الوحيدة التي يُقبل فيها الميت —
                            // ليقرّرها loadLinks: تُسقَط صراحةً خيرٌ من بثّها
                            // فيرى المستخدم خطأ 2004 على محتوى شغّال.
                            return plainFromDescriptor(desc.toString())
                        }
                        // ★ رصدٌ للتقاط: كم تأخّرت الوصفة عن موعد بلاها، وكم
                        // يتبقّى لرمزها؟ الرقمان هما الفرق بين «بائتة» و«طازجة»
                        // في سجلّ الجهاز، ويُقطع الشكّ في أنّ الطلب كان خاطئًا.
                        android.util.Log.d(
                            "DramaTip",
                            "episode-source ok book=$bookId ser=$serial bust=$bust" +
                                " stale=${staleness ?: "?"}s token=${bestLife ?: "?"}s"
                        )
                        return plainFromDescriptor(desc.toString())
                    }
                    lastWhy = if (desc == null) "بلا descriptor" else "بلا chain"
                } else {
                    // 403 هنا غالبًا حدُّ معدّل لا حكمٌ على الوصفة: {error:forbidden}
                    lastWhy = "HTTP ${res.code}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastWhy = e.javaClass.simpleName
            }
            android.util.Log.e("DramaTip", "episode-source attempt=$attempt/3 ($lastWhy) book=$bookId ser=$serial")
            if (attempt < 3) delay(700L * attempt)
        }
        android.util.Log.e("DramaTip", "episode-source gave up ($lastWhy) book=$bookId ser=$serial")
        return null
    }

    private val encRe = Regex(""""enc":"([A-Za-z0-9+/=]+)"""")
    private val typeRe = Regex(""""type":\s*"([^"]+)"""")
    private val langRe = Regex(""""language":\s*"([^"]+)"""")
    private val qualRe = Regex(""""(\d{3,4})":"([A-Za-z0-9+/=]+)"""")

    /**
     * (نوع الرابط كما يفتحه اللاعب، الصيغة المُعلنة للوسم) من الرابط وإعلان الموقع
     * والقِيَم المقيسة (نوع المحتوى والجسم إن قُرئ).
     *
     * ترتيب الحجّة = ترتيب قوّة الدليل: الجسم المقيس (‎#EXTM3U‎) ثم نوع المحتوى
     * المقيس ثم امتداد الرابط ثم إعلان الموقع. هذا هو ما يحسم الحالات المتضاربة:
     * MoboReels تُعلن type=mp4 ورابطها ينتهي ‎.m3u8 (فيفوز الامتداد)، وGoodShort
     * لا امتداد لها إطلاقًا (فيفوز نوع المحتوى/الجسم).
     */
    private fun classify(
        url: String,
        type: String?,
        contentType: String,
        body: String?,
    ): Pair<ExtractorLinkType, String?> {
        val u = url.lowercase()
        val ct = contentType.lowercase()
        val t = type?.lowercase()
        if (ct.contains("dash") || u.contains(".mpd") || u.contains("dash+xml")) return ExtractorLinkType.DASH to null
        // قائمة تشغيل مقيسة — أقوى دليل، ويسبق كل إعلان.
        if (ct.contains("mpegurl") || body?.contains("#EXTM3U") == true) return ExtractorLinkType.M3U8 to null
        if (u.contains(".m3u8")) return ExtractorLinkType.M3U8 to null
        // قُرئ جسمٌ فعليّ وليس قائمة تشغيل ⇒ ليس HLS مهما أعلن الموقع
        // (تفاديًا لبثّ mp4 بنوع M3U8 فيفشل اللاعب).
        if (!body.isNullOrEmpty()) return ExtractorLinkType.VIDEO to (if (t == "mp4") "MP4" else null)
        if (t != null && t in hlsTypes) return ExtractorLinkType.M3U8 to null
        if (t == "mp4") return ExtractorLinkType.VIDEO to "MP4"
        return ExtractorLinkType.VIDEO to null
    }

    // ---------- قراءة بنية sourceFirst ----------
    // عنصر chain واحد: نوعه + رابط الفيديو + جوداته (encByQuality).
    private class ChainEntry(val type: String?, val enc: String?, val qualities: List<Pair<String, String>>)

    /**
     * نصّ كائن/مصفوفة متوازن الأقواس بدءًا من أول فتحة في/بعد [from]، واعٍ بالنصوص
     * (فتُهمَل الأقواس داخل علامات التنصيص). نستعمله بدل تتبّع المواضع كما كان
     * الكود يفعل: المواضع تفترض ترتيبًا لمفتاحٍ ما، والكائن المتوازن لا يفترض شيئًا.
     */
    private fun balanced(s: String, from: Int, open: Char, close: Char): String? {
        val i = s.indexOf(open, from)
        if (i < 0) return null
        var depth = 0
        var j = i
        var inStr = false
        var esc = false
        while (j < s.length) {
            val c = s[j]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return s.substring(i, j + 1)
                }
            }
            j++
        }
        return null
    }

    /**
     * الكائن بعد مفتاح ‎"key":‎ — ويتحقّق أنّ أول حرف فعّال «{». هذا التحقّق هو
     * الفرق بين قراءة القيمة والانزلاق إلى أوّل كائنٍ لاحقٍ في الصفحة كلّها:
     * ‎"subtitle":null‎ كان سيُطابق أوّل «{» بعده (أي كائنًا لا علاقة له).
     */
    private fun objectAfterKey(s: String, key: String, from: Int = 0): String? {
        val k = s.indexOf(key, from)
        if (k < 0) return null
        var i = k + key.length
        while (i < s.length && (s[i] == ':' || s[i] == ' ' || s[i] == '\t')) i++
        if (i >= s.length || s[i] != '{') return null
        return balanced(s, i, '{', '}')
    }

    /** عناصر chain[] بأسمائها وأنواعها وجوداتها. لا يُلمس nextSourceFirst هنا إطلاقًا. */
    private fun parseChain(f: String): List<ChainEntry> {
        val sf = f.indexOf("\"sourceFirst\"")
        if (sf < 0) return emptyList()
        val ci = f.indexOf("\"chain\":[", sf)
        if (ci < 0) return emptyList()
        val arr = balanced(f, ci, '[', ']') ?: return emptyList()
        val out = mutableListOf<ChainEntry>()
        var pos = 0
        while (true) {
            val k = arr.indexOf('{', pos)
            if (k < 0) break
            val obj = balanced(arr, k, '{', '}') ?: break
            pos = k + obj.length
            val qs = mutableListOf<Pair<String, String>>()
            objectAfterKey(obj, "\"encByQuality\"")?.let { qo ->
                for (m in qualRe.findAll(qo)) qs.add(m.groupValues[1] to m.groupValues[2])
            }
            out.add(ChainEntry(typeRe.find(obj)?.groupValues?.get(1), encRe.find(obj)?.groupValues?.get(1), qs))
        }
        return out
    }

    private fun sourceName(f: String): String = metaOf(f).source ?: name

    /**
     * يقرأ من الحِمل ما لا يتعلّق بحلقةٍ بعينها: الكتاب وأوّلٌ تسلسلي والمصدر.
     * (واسم المصدر يُقرأ من كائن `sourceFirst` — وهو الموضع الذي تقرأ منه
     * `sourceName` أيضًا، فلا يتفرّق المصدران.)
     */
    private fun metaOf(f: String): SeriesMeta {
        val bookId = Regex(""""bookId"\s*:\s*"([^"]+)"""").find(f)?.groupValues?.get(1)
        val firstSerial = Regex(""""firstSerial"\s*:\s*(-?\d+)""").find(f)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val obj = objectAfterKey(f, "\"sourceFirst\"")
        val src = obj?.let { Regex(""""source":\s*"([^"]*)"""").find(it) }
            ?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        return SeriesMeta(bookId, firstSerial, src)
    }

    /**
     * كل كائنات الترجمة في وصفةٍ ما — بالشكلين:
     *  - كائن  `"subtitle":{"enc":…,"format":"srt","language":"ar"}` (MoboReels)
     *  - مصفوفة `"subtitle":[{enc,language},{enc,language}]` (منصّاتٌ متعدّدة اللغات)
     *
     * ولا نستعمل `objectAfterKey` هنا لأنّها تشترط أن يلي المفتاحَ «{» مباشرةً،
     * فيسقط الشكل المصفوفي كاملًا بلا سطر سجلٍّ واحد — وهو أحد أسباب اختفاء
     * الترجمة. والنصّ لا يُفكّ هنا، فالقرار في موضع البثّ.
     */
    private fun subtitleObjects(s: String): List<String> {
        val k = s.indexOf("\"subtitle\"")
        if (k < 0) return emptyList()
        var i = k + "\"subtitle\"".length
        while (i < s.length && (s[i] == ':' || s[i] == ' ' || s[i] == '\t')) i++
        if (i >= s.length) return emptyList()
        return when (s[i]) {
            '{' -> listOfNotNull(balanced(s, i, '{', '}'))
            '[' -> {
                val arr = balanced(s, i, '[', ']') ?: return emptyList()
                val out = mutableListOf<String>()
                var pos = 0
                while (true) {
                    val b = arr.indexOf('{', pos)
                    if (b < 0) break
                    val obj = balanced(arr, b, '{', '}') ?: break
                    pos = b + obj.length
                    out.add(obj)
                }
                out
            }
            else -> emptyList() // null أو نصّ — لا ترجمة
        }
    }

    /**
     * نتيجة فحصٍ واحد: حيّ؟ + ما قِيس فعلًا (نوع المحتوى، وجسم محدود لعرضه) + السبب
     * + هل هي **حكمٌ** (علمٌ) أم **تعذّر معرفة**؟
     *
     * التمييز هو ما يمنع تكرار علّة narto الموثّقة: كود حالةٍ (403/404) أو علامةٌ في
     * جسم البروكسي حكمٌ بأنّ الرابط ميت، أما انقطاع DNS أو مهلة الشبكة فلا يقولان
     * شيئًا عن الرابط — وإسقاطهما كان يجعل «لا روابط» نتيجةً لخللٍ عابر. وشهادة TLS
     * الفاسدة استثناء: البايتات موجودة لكن كل عميلٍ صارم يرفضها، فهي حكم.
     */
    // data class كي يُبنى «حكمٌ مُرخى» بـcopy في خطّ النجاة بلا تكرار الحقول.
    private data class Verdict(
        val alive: Boolean,
        val contentType: String,
        val body: String?,
        val why: String,
        val certain: Boolean,
    )

    /**
     * مسبار الحيوية — بلا HEAD وبلا Range، لأنّ اللاعب لا يرسل شيئًا منهما.
     *
     * قِيس في narto-shared أنّ إضافة Range قلبت الحكم على joyreels (403 معها،
     * 410 بدونها)، وأنّ بروكسي ‎/e/m/‎ يردّ 200 حتى ولو كان المصدر خلفه منتهيًا؛
     * فالفحص هنا يقلّد اللاعب حرفيًّا، ويقرأ جسم البروكسي بحثًا عن العلامة بدل
     * الوثوق بكود الحالة.
     *
     * ويُقرأ الجسم أيضًا حين لا يحسم نوعُ المحتوى أنه فيديو تدريجيّ — فقائمة
     * التشغيل تُعرَف من ‎#EXTM3U‎ وهذا ما يصنّف المسارات بلا امتداد (GoodShort).
     * والقراءة مسقوفة (‎[READ_CAP]‎ بايت) فلا يُنزَّل MP4 كامل إن صادف VOD.
     *
     * المرجع (Referer) يُحسب لكل رابط من خلال `platformReferer(url)` —
     * shorttv يحتاج referer shorttv.live، netshort يحتاج netshort.com، إلخ.
     */
    private inner class Probe {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Verdict>()

        suspend fun check(u: String): Verdict {
            cache[u]?.let { return it }
            val r = withContext(Dispatchers.IO) { one(u) }
            cache[u] = r
            return r
        }

        /**
         * إعادة فحص رابطٍ سبق أن سقط — لتمييز السقطة العابرة من الموت الدائم.
         * يُعاد الفحص فعلًا (لا يُقرأ من الذاكرة) حتى ثلاث مرّات، لأنّ مضيفات
         * dramatip كثيرة العطل العابر (قِيس على بروكسي narto ردُّ 403 ثم 200
         * للطلب نفسه)، وسلسلةٌ من عنصرٍ واحد تسقط بسقطةٍ واحدة. ولا يعود الحكم
         * الجديد أسوأ من الأول: المهلة أو انقطاع الاتصال لا يُثبتان موتًا فيُحفظ
         * الأول، والحكم القاطع يبقى قاطعًا.
         */
        suspend fun recheck(u: String): Verdict? {
            val prev = cache[u]
            var best = prev
            for (attempt in 1..3) {
                val fresh = withContext(Dispatchers.IO) { one(u) }
                if (fresh.alive) { cache[u] = fresh; return fresh }
                if (fresh.certain) best = fresh
                if (attempt < 3) delay(500L * attempt)
            }
            val result = best
            if (result != null) cache[u] = result
            return result
        }

        suspend fun alive(u: String): Boolean = check(u).alive

        private fun one(u: String): Verdict {
            val proxy = u.contains("/e/m/") || u.contains("/e/s/")
            // رابطٌ لا يُبنى ليس حكمًا بأنه ميت (قد يكون ترميزًا غريبًا فقط) — نُبقيه
            // بلا فحص، كما تفعل قاعدة «الحكم مقابل لا نعرف»: لا نُسقط ما لم نُثبت موته.
            val uri = try { URL(u) } catch (e: Exception) { return Verdict(true, "", null, "unparseable", false) }
            var conn: HttpURLConnection? = null
            return try {
                val c = uri.openConnection() as HttpURLConnection
                conn = c
                c.requestMethod = "GET"
                c.setRequestProperty("Referer", platformReferer(u))
                c.setRequestProperty("User-Agent", ANDROID_UA)
                // Range على البروكسي فقط (لبثّ جسمٍ صغيرٍ محدود لبضعٍ من الكيلوبايتات).
                if (proxy) c.setRequestProperty("Range", "bytes=0-65535")
                c.instanceFollowRedirects = true
                c.connectTimeout = 7000
                c.readTimeout = 7000
                val code = c.responseCode
                if (code !in 200..399) {
                    // ★ 2026-10-16. حافّة البثّ تُعلن سبب الرفض في جسم الخطأ:
                    // ‎403‎ جسمه حرفيًّا `need sign info` معناه **توقيعٌ ناقصٌ أو
                    // منتهٍ** لا رابطٌ ميّت. فحفظ الجسم (٦٤ بايتًا) هو الفرق بين
                    // إسقاط الحلقة بحكمٍ أعمى وإعادة إنتاج الوصفة من أصلها —
                    // وهو ما جعل التقاط الـ403 القديم بلا جسمٍ يُضلّ.
                    val whyBody = if (code == 403) readLimited(c.errorStream, 512).take(64) else ""
                    return Verdict(
                        false, c.contentType.orEmpty(),
                        whyBody.takeIf { it.isNotBlank() },
                        if (whyBody.isNotBlank()) "HTTP $code: $whyBody" else "HTTP $code",
                        true,
                    )
                }
                val ct = c.contentType.orEmpty()
                // ★ 2026-10-16. كنّا لا نقرأ جسمًا وسمه المضيف `video/*` كي لا
                // نُنزّل فيديو كاملًا. لكنّ ذلك أفقدنا التمييز حين **يكذب**
                // الوسم: مدخل DramaBox الرئيس يعلن `type:hls` ويشير إلى
                // `…/v1/dramabox/…m3u8` يردّ HTML — وحكمه قائمٌ على قراءة
                // جسمه. فصار الفحص يقرأ رأسًا محدودًا (٣٢KB) من كل جسم،
                // وحينها إن بدأ الجسم بترويسة ISO-BMFF (`ftyp`) فهو وسائط
                // حقيقيّة فالوسم صادق؛ وإن كان `<` أو `{` فهو صفحة مُتنكّرة
                // ⇒ ليس HLS مهما أعلن. وهو ما يعني: لا تنزيل فيديو كامل (يبقى
                // سقفُ `readLimited` بالبايت)، ولا حكمَ على وسمٍ وحده.
                // وإن وسم المُضيف الجسم `video/*` فحسبُنا رأسٌ صغير (٦٤KB):
                // ترويسة `ftyp` تظهر في أوّل بايتات، فلا داعي لقراءة ملفٍّ
                // ضخمٍ من شبكةٍ قد تتباطأ فنُطيل موجة الفحص بلا فائدة.
                val head = if (ct.lowercase().contains("video/")) 64_000 else READ_CAP
                val body = readLimited(c.inputStream, head)
                if (proxy) {
                    val b = body.orEmpty()
                    if (b.contains("link expired") || b.contains("invalid token") || b.contains("invalid or expired")) {
                        return Verdict(false, ct, null, "proxy body: ${b.take(48)}", true)
                    }
                }
                // ★ 2026-10-16. رابطٌ يدّعي HLS (امتداد ‎.m3u8‎ أو نوع محتوى mpegurl)
                // وقُرئ جسمه فوجد **ليس** قائمة تشغيل ⇒ هو صفحة خطأ مُتنكّرة بزي
                // قائمة تشغيل. قِيس على DramaBox: مسار ‎/v1/dramabox/…m3u8‎ يردّ
                // 200 بقالب HTML بمقاس 27KB، فيُصنَّف M3U8 بالامتداد ويُبثّ، فيردّ
                // اللاعب «خطأ 3002» (بايتات ليست وسائط). الحكم هنا يمنعه من الأصل.
                val claimsHls = u.contains(".m3u8") || ct.lowercase().contains("mpegurl")
                if (claimsHls && !body.isNullOrEmpty() && !body.contains("#EXTM3U")) {
                    return Verdict(false, ct, body, "not a playlist: ${body.take(48)}", true)
                }
                Verdict(true, ct, body, "", true)
            } catch (e: Exception) {
                // شهادة TLS مرفوضة حكمٌ: البايتات موجودة لكنّ كلّ عميلٍ صارم يرفضها.
                // ★ 2026-10-16. ومضيفٌ لا يُحلّ اسمه أصلًا حكمٌ كذلك: لا عميلَ يبلغه.
                // قِيس على DotDrama أنّ مضيفه videotv.vividshort.com لم يُحلّ قطّ؛
                // وحين كان ‎UnknownHostException‎ يُعدّ «تعذّر معرفة» بُثّ الرابط
                // الميت فاختار اللاعب التشغيل وأظهر «خطأ 2001» — وهو عطلُ شبكةٍ في
                // ظاهر الأمر لا رابطٌ ميت. نُعيد الحلّ مرّةً صريحةً قبل الحكم كي لا
                // يُسقط سباقُ DNS عابرٌ رابطًا حيًّا. وما عدا ذلك (مهلة، انقطاع
                // اتصال) لا يقول شيئًا عن الرابط — يُبثّ بلا حكمٍ في آخر القائمة.
                var certain = e is javax.net.ssl.SSLHandshakeException
                if (e is java.net.UnknownHostException) {
                    val stillDead = try { java.net.InetAddress.getByName(uri.host); false }
                    catch (_: Exception) { true }
                    certain = stillDead
                }
                Verdict(
                    false, "", null,
                    "${e.javaClass.simpleName}: ${e.message?.take(48) ?: "-"}",
                    certain,
                )
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

        // قراءة مسقوفة: تكفي لقائمة تشغيلٍ كاملة، وتمنع تنزيل ملفّ VOD ضخمٍ إن أخطأ التصنيف.
        private fun readLimited(ins: java.io.InputStream?, max: Int): String = try {
            if (ins == null) "" else ins.bufferedReader().use { rd ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < max) {
                    val n = rd.read(buf, 0, minOf(buf.size, max - sb.length))
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } catch (e: Exception) { "" }
    }

    /**
     * عنصر chain واحد ← مرشّحات (اسم، رابط، نوع، دقّة). الأساس أولًا ثم جوداته،
     * وكل رابط مذكور مرّة واحدة بأعلى دقّة معلنة له (MoreShort: 480/720/1080
     * لملفٍ واحد ← يُبثّ مرّة).
     */
    private fun chainCands(e: ChainEntry, source: String): List<Triple<String, String, String?>> {
        val out = mutableListOf<Triple<String, String, String?>>()
        val byUrl = LinkedHashMap<String, String>()
        for ((q, qenc) in e.qualities) {
            val qu = decryptOrSkip(qenc) ?: continue
            val prev = byUrl[qu]
            if (prev == null || (q.toIntOrNull() ?: 0) > (prev.toIntOrNull() ?: 0)) byUrl[qu] = q
        }
        e.enc?.let { decryptOrSkip(it) }?.let { main ->
            val q = byUrl[main]
            out.add(Triple(if (q != null) "$source · ${q}p" else source, main, e.type))
        }
        // ★ 2026-10-16. الجودات تُعلن بنوع المدخل نفسه — بخلاف الواقع على DramaBox:
        // مدخلُه الرئيس `type:"hls"` يقود إلى `…/v1/dramabox/…m3u8` (قالب HTML
        // مُتنكّر ⇒ يُسقطه الفحص)، بينما جوداته 720/1080 روابط **mp4** حيّة على
        // `dramaboxdb.com`. فكان يُصنَّف mp4 بـM3U8 بالنوع الموروث، وفحصُه لا
        // يقرأ جسمه (يرى `video/mp4` فيكفّ) فيبقى M3U8 ⇒ حكمُه موكولٌ للاعب
        // فيفشل بـ3002. والامتداد هو الدليل الأقوى: إن كان للجودة امتدادٌ مخالف
        // لنوع مدخلها خلَفه هو — وإلاّ بقي النوع الموروث (فلا يتغيّر سلوك
        // المنصّات التي جوداتها HLS مجزّأةً فعلًا، مثل JoyReels).
        for ((qu, q) in byUrl) {
            val vq = (
                if (qu.substringBefore('?').substringAfterLast('/').contains('.', ignoreCase = false)) {
                    qu.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
                } else ""
                ).lowercase()
            val ty = if (vq == "mp4" || vq == "m4v" || vq == "webm") "mp4" else e.type
            out.add(Triple("$source · ${q}p", qu, ty))
        }
        return out
    }

    /** اسم دقّة من سطر EXT-X-STREAM-INF: min(العرض، الارتفاع) — الفيديو القصير عموديّ. */
    private fun resolutionLabel(inf: String): String? {
        val r = Regex("""RESOLUTION=(\d{3,4})x(\d{3,4})""").find(inf)?.groupValues ?: return null
        val w = r[1].toIntOrNull() ?: 0
        val h = r[2].toIntOrNull() ?: 0
        val n = (minOf(w, h)).takeIf { it > 0 } ?: h
        return if (n > 0) "${n}p" else null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // الحِمل = "slug|رقم". CloudStream قد يُسبق slug بـ mainUrl — نأخذ آخر جزء بعد '/'.
            val parts = data.trim().split("|")
            val ep = parts.lastOrNull()?.toIntOrNull() ?: 1
            val slugPart = if (parts.size >= 2) parts.dropLast(1).joinToString("|") else parts.firstOrNull() ?: ""
            val slug = slugPart.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false

            val url = "$TIP_MAIN/series/${escapeSlug(slug)}/episode-$ep"
            // ★ قد يفشل الحِمل سقوطًا عابرًا (قِيس: صفحات dramatip تسقط مرّةً كلّ
            // بضع محاولات، وبعضها استغرق ٩٫٨ث — لكلّ طلبٍ مستقلٍّ). وكان الكود
            // يعيد `false` فورًا فيرى المستخدم «لم يعثر على روابط» — *قبل* أن
            // يسأل الـAPI الحيّ أصلًا. فلا نُسقط الحلقة بسبب الحِمل: نستمرّ
            // ونتّكئ على معطيات السلسلة المحفوظة (من `load`) لسؤال الـAPI عن
            // هذه الحلقة بعينها، ووصفةُ الصفحة تبقى مكمّلًا إن حضرت.
            val f = episodeFlight(url)
            // ★ 2026-10-16. ليس `f != null` بل `f` يحمل `sourceFirst`: الحِمل
            // غير الفارغ قد يكون صفحة 404 (بلا bookId)، وكان وجوده **يحجب**
            // الكاش المكتوب في `load()` فيُطفئ الـAPI ويسقط الرابط. فصار
            // الاختيار حقلًا لا كائنًا: خُذ metaOf من الحِمل الصالح وحده.
            val fMeta = if (hasSourceFirst(f)) metaOf(f!!) else null
            val cached = seriesMeta[bareSlug(slug)]
            // الكاش لا يُستعمل إلا إذا كان **قابلًا للاستعمال** (يحمل bookId أو
            // مصدرًا): فمعطى ناقص (حِملٌ سابق بلا bookId) لا ينفع وضارٌّ وحده.
            val meta = fMeta?.takeIf { it.bookId != null || it.source != null }
                ?: cached?.takeIf { it.bookId != null || it.source != null }
            val source = meta?.source
                ?: (if (hasSourceFirst(f)) sourceName(f!!) else name)

            // ★ إعادة الجلب: وصفة الصفحة مُخبَّأة وقد تحمل رموزًا منتهية (انظر TIP_API).
            // الـAPI يُسأل عن الحلقة نفسها من كتابها ورقمها التسلسلي، وتُدمج الوصفتان:
            // الوصفة المُنعشة أولًا لأنها الأحدث، ثم وصفة الصفحة. كل رابط يُبثّ مرّة
            // واحدة (emitted أدناه)، فالتكرار بين الوصفتين لا يُضاعف السيرفرات.
            // bookId قد يكون أبجديًا رقميًا مع شرطات (bilitv: "asrr-lzwj-lkhfy-at")
            val firstSerial = meta?.firstSerial ?: 1
            val bookId = meta?.bookId
            val apiDesc = if (bookId != null) apiLinks(bookId, ep + firstSerial - 1) else null
            if (apiDesc != null) logD("DramaTip: episode-source OK book=$bookId ser=${ep + firstSerial - 1}")
            // ★ سطرٌ تشخيصيٌّ واحد يكفي لتفسير «لم يعثر على روابط» من سجلّات
            // المستخدم بلا تخمين: أيّ الثلاثة سقط — الكاش، أم الحِمل، أم الـAPI.
            if (apiDesc == null || !hasSourceFirst(f)) {
                logE("DramaTip.loadLinks ep=$ep diag: bookId=$bookId cached=${cached != null} " +
                    "flight=${if (f == null) "ساقط" else if (hasSourceFirst(f)) "صالح" else "بلا sourceFirst"} " +
                    "api=${if (apiDesc != null) "حيّ" else "فشل"} slug=${bareSlug(slug)}")
            }

            val collected = mutableListOf<ExtractorLink>()
            // ما اجتاز الفحص، وما تعذّر الحكم عليه (انقطاع عابر لا يُسقط رابطًا) — يُبثّ
            // هذا بعد ذاك، فيبقى اختيار اللاعب الافتراضي على رابطٍ مُثبتٍ حيّ.
            val deferred = mutableListOf<ExtractorLink>()
            val emitted = LinkedHashSet<String>()
            val probe = Probe()
            val showSubs = prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) ?: true
            var sentSub = 0

            // ١) المرشّحون من chain[]: رابط الأساس + جوداته (encByQuality).
            //
            // لا يُقرأ nextSourceFirst إطلاقًا. قِيس 2026-10-12 على أربع منصّات
            // بحلقتين متتاليتين أنّ nextSourceFirst في الحلقة N يساوي chain في
            // الحلقة N+1 حرفيًّا (GoodShort 61502918←61502919، Playlet /1/←/2/،
            // BibiShort /1/←/2/، PineDrama): أي أنه «الحلقة التالية» لا «سيرفرًا
            // بديلًا». بثّه موسومًا «· بديل» كان يُشغّل حلقةً خاطئة عند اختياره.
            class Cand(val label: String, val url: String, val type: String?, val qLabel: String?)
            fun candsOf(desc: String): List<Cand> {
                val out = mutableListOf<Cand>()
                val srcName = sourceName(desc)
                for (e in parseChain(desc)) {
                    for ((label, u, ty) in chainCands(e, srcName)) {
                        val q = Regex("""·\s*(\d{3,4})p$""").find(label)?.groupValues?.get(1)
                        out.add(Cand(label, u, ty, q?.let { "${it}p" }))
                    }
                }
                return out
            }

            // ★ 2026-10-16. الأولوية للوصفة المُنعشة وحدها متى حملت chain صالحة،
            // ولا تُدمج بوصفة الصفحة (كما كانت تفعل listOfNotNull). السبب: وصفة
            // الصفحة مُخبَّأة وقد تعود برموزٍ **حيّة الشكل ميتة المفعول** (توكنٌ
            // مضى أوانه أو مضيفٌ تغيّر) — فلا يكشفها فحصٌ ولا يُسقطها، وتُبثّ أوّلًا
            // لأنّ ترتيب الإسقاط هو ترتيب المرشّحين، فيختارها اللاعب الافتراضيًّا
            // ويفشل التشغيل بلا سببٍ ظاهر. لكنّها تبقى **خطّ نجاة**: قِيس على BiliTV
            // أن مضيف الأنعش وحدَه يسقط بـ403 عابر فتبقى الحلقة بلا روابط، فنجعل
            // روابط الصفحة مرشّحاتٍ أخيرةً بعد الأنعش — تُبثّ في الآخر فلا تسرق
            // الافتراضيَّ من رابطٍ مُثبتٍ حيّ.
            val seenCands = HashSet<String>()
            var freshCands = apiDesc?.let { candsOf(it) }.orEmpty()
            val pageCands = f?.let { candsOf(it) }.orEmpty()

            // ★ 2026-10-16. أحكامٌ تُوكل إلى اللاعب موتًا مؤكّدًا: ٤٠٣ من حافّة
            // البثّ **وجسمها `need sign info`** — أي توقيعٌ ناقصٌ أو منتهٍ لا
            // رابطٌ ميّت، و`not a playlist` — أي قالبُ HTML مُتنكّرٌ بزيّ قائمة
            // تشغيل، فاللاعب يردّه «خطأ 3002». نُجمعهما لإعادة إنتاج الوصفة
            // من أصلها (تخريب ذاكرة العُقدة) لا لإسقاط الحلقة.
            val wrongToken = { v: Verdict? ->
                v != null && v.why.startsWith("HTTP 403") &&
                    (v.body?.contains("need sign", ignoreCase = true) == true)
            }
            val fakePlaylist = { v: Verdict? -> v != null && v.why.startsWith("not a playlist:") }

            // ★ 2026-10-17. الجذر الحقيقيّ لخطأ **2004**.
            //
            // الالتقاط أثبت أنّ اللاعب يردّ 2004 (`ERROR_CODE_IO_BAD_HTTP_STATUS`)
            // على رابطين: قائمةُ البثّ تُفتح ثم تردّ قطعةُ الفيديو ٤٠٣. وقِيس اليوم
            // أنّ الحافّة تُعلن السبب بصيغتين مختلفتين: `need sign info` للتوقيع
            // الناقص، و**`t info expired` للرمز المنتهي**. وكان `wrongToken`
            // يشترط الصيغة الأولى وحدها، فمرّ المنتهي بلا حكم — وأخطرُ من ذلك
            // أنّ خطّ النجاة (`lenient`) كان يُرخي الحكم على **كلّ** ما لم
            // يُصنَّف، فبُثّ المنتهي فعلًا (‎-7533s‎ في السجلّ) وظهر 2004.
            //
            // فصار الرمز المنتهي صنفًا مستقلًّا: يستحقّ إعادة الإنتاج (كالناقص)،
            // و**لا يُرخى أبدًا** في خطّ النجاة — لأنّ بثّه خطأ 2004 حتمًا.
            // ولا يُعاد تصنيف مضيفٍ حيّ إلى ميت: البوّابة تقرأ العمر محليًّا
            // أيضًا، فلا يبقى في القائمة رمزٌ ميت أصلًا.
            val tokenExpired = { u: String, v: Verdict? ->
                (tokenRemainingSeconds(u)?.let { it <= 0 } == true) ||
                    (v != null && v.why.startsWith("HTTP 403") &&
                        (v.body?.contains("t info expired", ignoreCase = true) == true))
            }

            // وحين تنتهي إعادة الإنتاج، نُرخي الحكم على ما **تعذّر الحكم عليه
            // وحدَه** (مهلة أو انقطاع) — أمّا الحكم القاطع فلا يُرخى: ٤٠٣
            // التوقيع الناقص والرمزُ المنتهي والقائمةُ المزيفة تبقى موتًا صريحًا،
            // فبثّها يعني خطأ 2004 أو 3002 على محتوى يعمل في الموقع.
            var lenient = false

            suspend fun uniqOf(fc: List<Cand>): List<Cand> {
                val cs = if (fc.isNotEmpty()) fc + pageCands else pageCands
                val seen = HashSet<String>()
                // ★ 2026-10-16. الأطزج أوّلًا عند التكافؤ: الوصفة الواحدة قد
                // تحمل أكثر من عنصرٍ لسلسلةٍ (MoboReels، HappyShort)، وعنصرٌ
                // `refreshAfter` فيه ماضٍ بائتٌ بائتٌ. والترتيب الافتراضيّ كان
                // ترتيب الموقع، فيسبق البائتُ الحيَّ فيختاره اللاعب فيفشل بـ403.
                // و`distinctBy` بعد الترتيب تُبقي الأطزج عند تطابق الرابط.
                return cs.filter { seen.add(it.url) }
                    .sortedByDescending { tokenRemainingSeconds(it.url) ?: Long.MIN_VALUE }
            }

            var uniq = uniqOf(freshCands)
            if (uniq.isEmpty()) {
                logE("DramaTip.loadLinks $source ep=$ep decrypted nothing")
                return false
            }

            // ٢) فحص المرشّحين كلّهم في وقتٍ واحد: الرحلات مستقلّة، فالتسلسل يكلّف
            //    مهلةً كاملة لكل رابط ميت (حتى ٧ث للواحد) ويراها المستخدم تأخّرًا.
            //    وإذا أطفأ المستخدم «فحص الروابط» نُبثّ بلا أي طلب شبكة (وضع سريع)
            //    فلا يُقاس نوع المحتوى ولا تُفرَّع الجودات — لكن التصنيف يبقى
            //    صحيحًا لأنّه يسقط إلى الرابط وإعلان الموقع.
            val probing = prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_PROBE_LINKS, true) != false
            // ★ 2026-10-16. إعادة فحص المُسقَط (حتى ٣ مرّات داخل recheck) قبل قبول
            // الإسقاط. مضيفات بثّ dramatip كثيرة العطل العابر (narto يردّ 403
            // لطلبٍ ويقبل التالي بالقيمة نفسها)، وسلسلةٌ من عنصرٍ واحد يسقط عنصرها
            // = «لا روابط» للحلقة كلّها. والإعادة تُحفظ بحيث لا تعود أسوأ من الأول:
            // المهلة أو انقطاع الاتصال لا يُثبتان شيئًا، والحكم القاطع يبقى قاطعًا.
            // ★ 2026-10-17. **الجذر الثاني لدوران الفيديو.** كان الرمز المنتهي
            // يُفحَص أوّلًا (٧ث مهلةً للطلب، ثم حتى ٣ إعادات = ‎~٢٢ث‎) **ثم**
            // يُسقَط — فيدفع المستخدم ثمن رحلةٍ محكومٍ عليها سلفًا، ويراها دورانًا.
            // والعمر معلومٌ محلّيًّا من الرابط نفسه، فلا يُصرف عليه فحصٌ أصلًا:
            // حكمٌ صناعيّ «منتهٍ» بلا شبكة. وهذا يقصّر الطريق إلى الروابط
            // الحيّة بقدر ما في القائمة من موتى — وقد كانت كلّها موتى في الالتقاط.
            suspend fun probeAll(list: List<Cand>): List<Verdict?> = coroutineScope {
                list.map { c ->
                    async(Dispatchers.IO) {
                        when {
                            !probing -> null
                            tokenExpired(c.url, null) -> Verdict(false, "", null, "منتهٍ محلّيًّا", true)
                            else -> probe.check(c.url)
                        }
                    }
                }.awaitAll()
            }
            var verdicts = probeAll(uniq)
            run {
                // تُعاد السقطات القابلة للعَرض وحدها — كود حالة أو علامة بروكسي أو
                // جسمٌ ليس قائمة تشغيل — لأنّها هي التي تُردّ عابرةً على مضيفات
                // dramatip. ولا تُعاد المهلة (٧ ثوانٍ لكل محاولة ولا تُثبت شيئًا)،
                // ولا المضيف الذي لا يُحلّ (الحلّ يُعاد صريحًا داخل one())، ولا
                // فشل شهادة TLS (قاطعٌ بالطبع).
                // ★ 2026-10-17. ولا تُعاد رحلةٌ **محكومةٌ سلفًا**: الرمز المنتهي
                // عمره معروفٌ محلّيًّا (وقد يكون حكمُه من `probeAll` محليًّا
                // أيضًا)، وإعادته ثلاثة أضعافٍ (٣‎×‎٧ث = ‎٢١ث‎) لا تُقلب حكمًا
                // ولا تُنجي شيئًا — وقد قِيس أنّ كلّ مرشّحٍ في الالتقاط كان
                // منتهيًا، فكانت الإعادات كلّها زمنًا يُرى دورانًا في اللاعب.
                val toRetry = uniq.filterIndexed { i, c ->
                    val v = if (probing) verdicts[i] else null
                    v != null && !v.alive && !tokenExpired(c.url, v) &&
                        (v.why.startsWith("HTTP ") ||
                            v.why.startsWith("proxy body:") || v.why.startsWith("not a playlist:"))
                }
                if (toRetry.isEmpty()) return@run
                val again = coroutineScope {
                    toRetry.map { c -> async(Dispatchers.IO) { probe.recheck(c.url) } }.awaitAll()
                }
                val byUrl = toRetry.mapIndexed { i, c -> c.url to again[i] }.toMap()
                verdicts = uniq.mapIndexed { i, c -> byUrl[c.url] ?: verdicts[i] }
            }

            // ٢-ب) ★ 2026-10-16. العلاج الجذريّ لـ«لم يعثر على روابط» على MoboReels:
            //    الوصفة **بائتة** فرمزُها منتهٍ أو ناقص. لا نُصلح الرمز (لا نملك
            //    المفتاح)، بل **نعيد إنتاج الوصفة** من أصلها بتخريب ذاكرة العُقدة،
            //    ونُعيد الفحص. وذلك مرّتين على الأكثر — فلا يطول الزمن على الحلقة
            //    السليمة (تُنجي من الموجة الأولى فتُقفز الحلقة كلّها).
            // ★ 2026-10-17. إعادة الإنتاج **لازمةٌ بالضبط** عندما تكون الرموز
            // منتهية: قِيس أنّ `refresh=1` يعيد رمزًا طازجًا (‎+3500ث‎، ١٤/١٤)
            // بينما نسخة الصفحة المخبّأة منتهية (‎-700ث‎، ٠/١٤). فالرمز المنتهي
            // هو أوضح داعٍ لإعادة الجلب لا مانعٌ منه — فلا يُشترط هنا غيره.
            if (probing && bookId != null && verdicts.none { it?.alive == true }) {
                val baseSerial = meta.firstSerial
                val ser = ep + baseSerial - 1

                // ثلاثة أسباب تستحقّ إعادة الإنتاج: رمزٌ ناقص (٤٠٣
                // `need sign info`)، ورمزٌ **منتهٍ** (٤٠٣ `t info expired` أو
                // عمرٌ سالب في الرابط — وهو مصدر خطأ 2004)، وقائمةٌ مزيفة
                // (HTML بزيّ m3u8 → «خطأ 3002» عند اللاعب).
                val badIdx = uniq.indices.filter {
                    wrongToken(verdicts[it]) || fakePlaylist(verdicts[it]) ||
                        tokenExpired(uniq[it].url, verdicts[it])
                }
                val rem = uniq.getOrNull(badIdx.firstOrNull() ?: 0)?.let { tokenRemainingSeconds(it.url) }
                logE("DramaTip: bad-token count=${badIdx.size}/${uniq.size}" +
                    (rem?.let { " (عمر الباقي ${it}s)" } ?: "") + " book=$bookId ser=$ser")

                // تجميع حدّين من الـAPI ثم ترجيح **الأطول عمرًا**: قِيس أنّ
                // `refresh=1` وحده قد يعيد الرمز البائت نفسه داخل نافذة الذاكرة
                // (‎max-age=300‎)، بينما عقدتان لاسم dramatip نفسه أعطتا `t`
                // بعمر دقيقتين وأخرى بعمر ٥٠ دقيقة. فالفرق كلّه في **إعادة
                // الطلب** حتى تصيب نُسخةً أطزج — لا في اختراق الذاكرة.
                val runs = mutableListOf<List<Cand>>()
                for (attempt in 1..2) {
                    // لا نبتلع إلغاء البحث: CloudStream يُلغي الطلبات البائتة،
                    // وابتلاعه يُظهر «لا روابط» لحلقةٍ سليمة (قِيس سابقًا).
                    val desc = try { apiLinks(bookId, ser, bust = attempt > 1) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { null }
                    desc?.let { candsOf(it) }?.takeIf { it.isNotEmpty() }?.let { runs.add(it) }
                    if (attempt < 2) delay(200L)
                }
                val reCands = runs.flatten().distinctBy { it.url }
                    .sortedByDescending { tokenRemainingSeconds(it.url) ?: 0L }
                if (reCands.isNotEmpty() && reCands.map { it.url } != uniq.map { it.url }) {
                    freshCands = reCands
                    uniq = uniqOf(freshCands)
                    verdicts = probeAll(uniq)
                    logD("DramaTip: re-minted cands=${uniq.size} best=${uniq.firstOrNull()?.let { tokenRemainingSeconds(it.url) }}s")
                }

                // بعد استنفاد الإنتاج: يُرخى الحكم على ما **تعذّر الحكم عليه**
                // وحدَه (مهلة أو انقطاع اتصال — لا يُثبتان موتًا). وأمّا الحكم
                // القاطع فلا يُرخى أبدًا: ٤٠٣ التوقيع الناقص، والرمزُ المنتهي،
                // والقائمةُ المزيفة — بثُّها خطأ 2004/3002 حتمًا، وقد قِيس أنّ
                // إرخاءها هو **مصدر خطأ 2004** الذي رآه المستخدم (بُثّ رمزٌ
                // عمره ‎-7533s‎ لأنّ جسم الحافّة قال `t info expired` لا
                // `need sign info`). وقائمةٌ فارغةٌ أصدق من رابطٍ يفتح بخطأ.
                if (!verdicts.any { it?.alive == true }) {
                    lenient = true
                    logE("DramaTip: lenient=true (المنتهي لا يُرخى — إرخاءٌ لما تعذّر حكمه) book=$bookId ser=$ser")
                }
            }

            // ٣) بثّ الأحياء بترتيب الصفحة؛ وكل HLS حيّ يُفرَّع على جوداته الحقيقية.
            class Var(val label: String, val url: String, val qLabel: String?)
            val variants = mutableListOf<Var>()
            for ((i, c) in uniq.withIndex()) {
                val raw = verdicts[i]
                // ★ 2026-10-17. **بوّابة الرمز المنتهي — الجذر المباشر لخطأ 2004.**
                //
                // عمر الرمز معلومٌ محلّيًّا من الرابط نفسه (`t=` و`verify=` وقتا
                // انتهاء)، فلا حاجة إلى شبكةٍ للحكم به. وكان الرابط المنتهي
                // ينجو من الإسقاط كلّه حين **تنتهي مهلة** فحصه: فيُصنَّف «تعذّر
                // الحكم» ويُبثّ في الآخر (deferred)، فيفتح اللاعب القائمة ثم
                // تردّ قطعةُ الفيديو ٤٠٣ فيظهر 2004. والالتقاط يشهد: رابطٌ
                // `f0.mp4?t=6ad24b02` عمره ‎-7894s‎ بُثّ بعد فحصه
                // (`cannot-tell … SocketTimeoutException`) فظهر 2004.
                //
                // فالحكم المحلّيّ يسبق كلّ شيء: رمزٌ مضى أجله لا يُبثّ أبدًا،
                // ولو قال الفحص إنّه مات، ولو تعذّر الفحص.
                if (tokenExpired(c.url, raw)) {
                    logD("DramaTip: drop expired ${c.label} ${c.url.take(70)}" +
                        " عمر=${tokenRemainingSeconds(c.url)}s why=${raw?.why ?: "-"}")
                    continue
                }
                // الإسقاط للرابط المُثبت موته فقط (كود حالةٍ أو علامةٌ في جسم البروكسي أو
                // شهادة TLS مرفوضة أو مضيفٌ لا يُحلّ بعد إعادة محاولةٍ صريحة). أمّا
                // تعذّر الاتصال (مهلة أو انقطاع) فلا يُسقط الرابط — يُبثّ في الآخر.
                // ★ وفي إرخاء خطّ النجاة (lenient=true) يُبثّ ما **تعذّر الحكم
                // عليه وحدَه**: رمزٌ محكومٌ بموته (منتهٍ أو ناقص التوقيع أو قائمةٌ
                // مزيفة) يبقى ميتًا فلا يُبثّ، لأنّ بثّه يظهر للمستخدم خطأ 2004
                // (BAD_HTTP_STATUS) أو 3002 — وهي الأخطاء التي جاء الإصلاح لأجلها.
                val v = if (lenient && raw != null && !raw.alive && raw.certain &&
                    !wrongToken(raw) && !fakePlaylist(raw) && !tokenExpired(c.url, raw)
                ) raw.copy(alive = true) else raw
                if (v != null && !v.alive && v.certain) {
                    logD("DramaTip: drop dead ${c.label} ${c.url.take(70)} why=${v.why}")
                    continue
                }
                val cannotTell = v != null && !v.alive
                val why = v?.why
                if (cannotTell) logD("DramaTip: cannot-tell ${c.label} ${c.url.take(70)} why=$why")
                val (kind, declared) = classify(c.url, c.type, v?.contentType.orEmpty(), v?.body)
                if (!emitted.add(c.url)) continue
                addLink(if (cannotTell) deferred else collected, c.label, c.url, c.qLabel, kind, declared)
                if (cannotTell || kind != ExtractorLinkType.M3U8) continue
                val body = v?.body ?: continue
                val base = c.url.substringBeforeLast('/', c.url)
                var inf: String? = null
                for (raw in body.replace("\u0000", "").split('\n')) {
                    val l = raw.trim()
                    if (l.startsWith("#EXT-X-STREAM-INF:")) {
                        inf = l.removePrefix("#EXT-X-STREAM-INF:")
                    } else if (inf != null && l.isNotBlank() && !l.startsWith("#")) {
                        val q = resolutionLabel(inf)
                        val vu = if (l.startsWith("http")) l else "$base/$l"
                        variants.add(Var(c.label + (q?.let { " · $it" } ?: ""), vu, q))
                        inf = null
                    }
                }
            }

            // ٤) صيغ الـmaster: موجة ثانية بالتوازي أيضًا، ثم بثّها.
            if (variants.isNotEmpty()) {
                val ok = coroutineScope {
                    variants.map { v -> async(Dispatchers.IO) { probe.alive(v.url) } }.awaitAll()
                }
                for ((i, v) in variants.withIndex()) {
                    if (ok[i] && emitted.add(v.url)) addLink(collected, v.label, v.url, v.qLabel, ExtractorLinkType.M3U8, null)
                }
            }

            // ٥) الترجمة — تُقدَّم من **خادمٍ محليّ** لأنّ المشغّل يطلب رابطًا
            //    حقيقيًّا ولا يقبل نصًّا خامًّا ولا `data:` (وكان تسليم النصّ
            //    المفكوك بوصفه رابطًا هو سبب اختفاء الترجمة على **كلّ** المنصّات
            //    لا على MoboReels وحدها — انظر DramaTipSubServer).
            //
            //    والوصفة تحمل الترجمة على شكلين: كائنًا واحدًا
            //    `"subtitle":{"enc":…,"format":"srt","language":"ar"}` (MoboReels)،
            //    أو مصفوفة `"subtitle":[{…},{…}]` — فنجمع الشكلين. و`objectAfterKey`
            //    لا تصلح لهذا لأنّها تشترط «{» بعد المفتاح فيسقط الشكل المصفوفي.
            //
            //    ونكرّر على الوصفتين (الأنعش ثم الصفحة) ونتعقّب النصوص المُبثّة،
            //    فلا تتكرّر ترجمةٌ واحدة في القائمة حين تحمل الوصفتان الملفّ نفسه.
            if (showSubs) {
                // نجمع أولًا ثم نُبثّ: الوصفتان (الأنعش والصفحة) تحملان الترجمة
                // نفسها، فالدمج بالرابط يمنع جلبتين متطابقتين، والدمج بالنصّ
                // يمنع ظهور مسارٍ واحد مرّتين في قائمة المشغّل.
                val subSources = LinkedHashMap<String, String>()
                for (desc in listOfNotNull(apiDesc, f)) {   // f قد تكون null إن سقط الجلب — والترجمة تأتي من الـAPI وحدها حينها
                    for (sobj in subtitleObjects(desc)) {
                        val enc = encRe.find(sobj)?.groupValues?.get(1) ?: continue
                        val value = decryptOrSkip(enc)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                        val lang = langRe.find(sobj)?.groupValues?.get(1)
                            ?.substringBefore('_')?.substringBefore('-')?.takeIf { it.isNotBlank() } ?: "ar"
                        subSources.putIfAbsent(value, lang)
                    }
                }
                val emittedText = HashSet<String>()
                for ((value, lang) in subSources) {
                    // القيمة **رابطٌ** على المنصّات المقيسة كلّها (MoboReels:
                    // ‎…/subtitles/1?format=srt‎، JoyReels: ‎….srt‎، BiliTV:
                    // بروكسي narto ‎/e/s/‎) — لا نصَّ SRT. وإن جاء نصٌّ خامًّا على
                    // منصّةٍ أخرى نخدمه كما هو فلا نُهدر الحالتين.
                    val text = if (value.startsWith("http")) {
                        fetchSubtitleText(value) ?: continue
                    } else value
                    if (!emittedText.add(text)) continue
                    val local = DramaTipSubServer.register(text) ?: continue
                    subtitleCallback(newSubtitleFile(subLangLabel(lang), local))
                    sentSub++
                }
            }

            // الترتيب حسب إعدادات المستخدم (افتراضي = ترتيب الصفحة كما هو).
            // غير المُحكوم يلحق بالمُثبت فيبقى الأول اختيارَ اللاعب الافتراضي.
            collected.addAll(deferred)
            val order = prefs?.getString(DramaTipSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
            val ordered = when (order) {
                "asc" -> collected.sortedBy { it.quality }
                "desc" -> collected.sortedByDescending { it.quality }
                else -> collected
            }

            if (ordered.isNotEmpty()) {
                ordered.forEach { callback(it) }
                // ★ رصدٌ لعمر أول رابط مُبثّ: هذا الرقم هو ما يفسّر «Source error»
                // بعد الإسقاط. قيمته السالبة (مثل ‎-7533s‎) قاطعة بأن رمزًا ميتًا
                // بُثّ فعلًا رغم كل الفحص — وهي أهمّ ما يُقرأ في التقاط المستخدم.
                val firstLife = ordered.firstOrNull { it.url.startsWith("http") }
                    ?.let { tokenRemainingSeconds(it.url) }
                logD("DramaTip.loadLinks $source ep=$ep video=${ordered.size} sub=$sentSub" +
                    (firstLife?.let { " توكن=${it}s" } ?: ""))
                return true
            }
            logE("DramaTip.loadLinks $source ep=$ep nothing survived probes")
            false
        } catch (e: Exception) { logE("loadLinks: ${e.message}"); false }
    }

    // يعطي plain النصّ المكشوف أو null عند تعذّر فكّه.
    private fun decryptOrSkip(enc: String): String? = try { decryptEnc(enc) } catch (e: Exception) { logE("decrypt: ${e.message}"); null }

    // يبثّ رابطًا اجتاز الفحص: يوسمه بالصيغة ويضبط دقّته في مُنتقي الجودات.
    // لاحظ اسم المعامل qLabel لا quality: داخل بناء newExtractorLink تُحلّ
    // `quality` إلى خاصية المستقبِل، فتُقرأ-تُكتب على نفسها (فخّ ظلّل اسمًا
    // حذّر منه narto-shared: "referer = referer").
    private suspend fun addLink(
        collected: MutableList<ExtractorLink>,
        label: String,
        url: String,
        qLabel: String?,
        kind: ExtractorLinkType,
        declared: String?,
    ) {
        val tagged = FormatTag.tagged(label, url, kind, declared)
        collected.add(newExtractorLink(name, tagged, url, kind) {
            this.headers = reqHeaders()
            if (!qLabel.isNullOrBlank()) {
                val q = getQualityFromName(qLabel)
                if (q > 0) this.quality = q
            }
        })
    }
}