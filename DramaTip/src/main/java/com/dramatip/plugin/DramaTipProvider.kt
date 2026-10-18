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

// ★ 2026-10-17. مهلة الفحص الواحد. كانت ‎7ث‎، وهي على مضيفات التشغيل الآسيويّة
// (tiktokcdn وcdreader وfarsunpteltd وidrama) تُنهى قبل أن تردّ الشبكات البطيئة،
// فيُصنَّف الرابط «تعذّر الحكم» ويُسقَط حين يقترن برمزٍ منتهٍ — وهذه كانت علّة
// «لم يعثر على روابط» على FlickReels وiDrama وMoboReels، ودورانُ 2002 على
// PineDrama. سقفٌ لا انتظار: الناجح يعود فورًا.
//
// ★★ 2026-10-18. عُدِّل إلى ‎5ث‎ لسرعة بدء التشغيل، وقد صار ذلك **آمنًا** بعد
//    إصلاحين جعل هذا السقف مجرّد سرعةٍ لا خطر:
//    (١) **بوّابة الرمز المنتهي صارت مشروطة** (‎v25‎، انظر loadLinks): الرابط
//        الذي تعذّر حكمه (`cannot-tell`، ‎certain=false‎) **يُبثّ** في آخر القائمة
//        (أو «last-resort» حين لا حيَّ أصلًا) ولا يُسقَط أبدًا. فما كان يُسقَط
//        قديمًا حين يقترن برمزٍ منتهٍ صار يُبثّ — فلا عودة إلى «لا روابط».
//    (٢) **timeoutُ الجزء يعود ‎-1‎** (segStatus) وليس في ‎400..499‎، فلا يدخل
//        شرط الإسقاط ويبقى الرابط حيًّا. أي أنّ المهلة لا تُسقط شيئًا إطلاقًا.
//    والفعل المقيس: `probeAll` ينتظر **أبطأ** فحصٍ في `awaitAll`، وكلّ `one()`
//    رحلتان (القائمة ثم الجزء) = ‎٢×‎ السقف، فكان ‎10ث‎ يبلغ ‎~20ث‎ في الحال
//    الباطل (قِيس DramaBox ‎~20ث‎ descriptor→بث). ‎5ث‎ يقطع ذلك نصفين، والناجح
//    يعود فورًا فلا تتأثّر المنصّات السريعة أصلًا.
private const val PROBE_TIMEOUT_MS = 5_000

// ★ 2026-10-17. أدنى طابعٍ زمنيًّا يُقبل وقتًا لانتهاء رمز (٢٠٢٣-١١-١٤).
// الآن صار للعمر سلطة إسقاط الرابط (بوّابة الرمز المنتهي في loadLinks)،
// فحقلٌ اسمه `t` على منصّةٍ أخرى قد يحمل هاشًا لا وقتًا — فيُحسب سالبًا
// ويُسقط رابطًا حيًّا. الطابع الحقيقيّ لا يسبق هذا الحدّ بسنوات.
private const val MIN_TOKEN_EPOCH = 1_700_000_000L

// ★ 2026-10-17. وأعلى المعقوليّة كذلك. حقلٌ اسمه `auth_key` على JoyReels حمل
// قيمتين: ‎`1787439293` (ماضٍ) و‎`1947835727` (‎+٤٫٩ سنة‎) في رابط التحويل. ولو
// مُرّرت الثانية كعمرٍ حقيقيّ **لتصدّرت القائمة** (الترتيب نزولٌ بالعمر) فتُختار
// افتراضيًّا على أختها الحيّة — والرقم الوهميّ قد لا يكون رمزًا على المضيف أصلًا.
// والقيم المقيسة على منصّات dramatip بالساعات (‎~50 دقيقة‎) ولا تبلغ يومًا، فسقفُ
// ٤٠٠ يوم يفصل الرمز عن الرقم الذي يشبه الرمز.
private const val MAX_TOKEN_LIFE = 34_560_000L

// منصّات الموقع من /ar/sources (slug → الاسم كما يعرضه الموقع). ٢٤ منصة.
// ★ 2026-10-17. كانت ٢٣ وفُقِّد `freereels`: تُضاف هنا كلّ ما يظهر في
// `/ar/sources` ولا يزيد (الفهرس يُقرأ من هناك كلّما استُدعي، والترتيب ترتيب
// الموقع). وقيست FreeReels قبل الإضافة: ٢٤ نتيجةً في `/source/freereels`،
// وصفحة الحلقة تحمل `sourceFirst` بكتابها، ورابطها يفتح ‎200‎ ونوعه
// `audio/x-mpegurl` على `video-v6/video-v81.mydramawave.com`. والمنصّة كانت
// **مفقودةٌ من الإعدادات أيضًا** — لا صفَّ لها في الرئيسية ولا مفتاحَ
// إظهارٍ في لوح الإعدادات، فما كان يُرى بأصلٍ.
// ثابت على مستوى الملف كي تشاركه الإعدادات (مفتاح إظهار/إخفاء لكل منصّة).
internal val DRAMA_TIP_PLATFORMS: List<Pair<String, String>> = listOf(
    "bibishort" to "BibiShort",
    "bilitv" to "BiliTV",
    "dotdrama" to "DotDrama",
    "dramabite" to "DramaBite",
    "dramabox" to "DramaBox",
    "flickreels" to "FlickReels",
    "freereels" to "FreeReels",
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

    // منصّات الموقع من /ar/sources (slug → الاسم كما يعرضه الموقع). ٢٤ منصة —
    // انظر تعليق `DRAMA_TIP_PLATFORMS` أعلاه.
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
        val v = rawTokenEpoch(url) ?: return null
        val rem = v - now
        // ★ 2026-10-17. **سقفُ المعقوليّة الأعلى — ضرورته مقيسة.**
        //
        // حقلٌ اسمه `auth_key` على JoyReels يحمل قيمتين مختلفتين تمامًا:
        // ‎`1787439293` (ماضٍ — 2026-08-22) في الرابط الأوّل، و‎`1947835727`
        // (‎+٤٫٩ سنة‎) في رابط إعادة التوجيه. والثانية لو مرّت كعمرٍ حقيقيّ
        // **لتصدّرت القائمة** (لأنّ الترتيب نزولٌ بالعمر) فتختارها القائمة
        // الافتراضيّة على أختها الحيّة القصيرة العمر — والقيمة الوهميّة قد لا
        // تكون رمزًا على المضيف أصلًا. والقيمة الحقيقيّة على منصّات dramatip
        // كلّها مقيسةٌ بالساعات (‎~50 دقيقة‎) ولا تبلغ يومًا، فسقفُ ٤٠٠ يوم
        // يفصل الرمز عن الرقم الذي يشبه الرمز. (وهذا نظيرُ `MIN_TOKEN_EPOCH`
        // الأدنى، فالحقل المجهول لا يحكم على الرابط في أيّ اتّجاه.)
        return rem.takeIf { it <= MAX_TOKEN_LIFE }
    }

    /**
     * طابع انتهاء الرمز كرقمٍ مطلق من أيّ حقلٍ زمنيّ معروف، بلا حكمٍ على العمر.
     *
     * ★ 2026-10-17. **مسحُ المنصّات الـ٢٣ كلّها هو ما وسّع هذه القائمة.**
     *
     * القياس على المنصّات التي شكا المستخدم من خطأ 2004 عليها كشف أنّ الموقع
     * يُوقّع روابطه بأسماء مختلفة لكلّ منصّة، وأنّ بوّابتينا (`t=` و`verify=`)
     * لا ترى أيًّا منها ⇒ الرابط المنتهي يُبَثّ **بلا حكمٍ زمنيّ** فيردّ ‎403‎
     * على أوّل طلب وسائط ⇒ 2004. والأسماء المقيسة (بمفاتيحها الفعليّة كما ظهرت
     * في الرابط المفكوك) أربعة:
     *  - `auth_key=` وهو **الأوسع**: قيس على JoyReels وDotDrama وStoryReel
     *    وNetShort. وبشكلين: مجرّدًا `?auth_key=1787439293`، ومُركَّبًا
     *    `?auth_key=1947835727-<hash>-0-<hash>` — والطابع هو ما قبل أوّل شرطة،
     *    فالأنماط أدناه تتوقّف عند أوّل ما ليس رقمًا. ولم يُختر الصكّ افتراضًا:
     *    قيس على `…-sd-encrypt-stream.m3u8` أنّ الطابع المجرّد **ماضٍ**، وأنّ
     *    الطلب يردّ 302 إلى رابطٍ جديد صالح. فالحكم هنا **قاصرٌ على الترتيب**
     *    ولا يُسقط شيئًا (انظر بوّابة البثّ: الرابط المُثبت حيًّا يُبثّ ولو انتهى
     *    رمزه، لأنّ إعادة التوجيه تُجدّده فعلًا). وقيست سالبةً على StoryReel
     *    (‎-1270س‎) بينما رابطها يُعرض للمستخدم — أي أنّ الحقل **قد يكون مُهمَلًا
     *    على منصّةٍ ومُحترمًا على أخرى**، وهذا سببٌ إضافيّ لعدم الإسقاط به.
     *  - `ts=<ثوانٍ>` على iDrama: قيس أن مسارًا حيًّا ‎`?ts=1792251558`‎ يردّ 200
     *    والنوع `video/mp2t`، وأن المسار نفسه بمسارٍ مضاعف يردّ 403.
     *  - `wsTime=<ثوانٍ>` على DramaBite (نمط أنظمة البثّ الصينيّة، مع `wsSecret`).
     *  - `Expires=<ثوانٍ>` على HappyShort/NetShort/KalosTV (توقيع CloudFront،
     *    مع `Signature` و`Key-Pair-Id`). وقيس KalosTV برمزٍ **مضى بـ٣٩٣ ساعة**
     *    — أي رابطٌ ميتٌ كان يُبَثّ بلا حكم.
     *
     * و**لا يصحّ اختبارها بالاستبدال**: جُرِّب استبدال `ts=` و`wsTime=` بقيم
     * قريبة من الآن فعادت **403 في كلّ الحالات** — فالطابع مقترنٌ بالتوقيع
     * المجاور، فلا يُتحقَّق منه إلا بقراءة قيمته الحقيقيّة من الرابط. وهذا ما
     * نفعله هنا: نقرأ ولا نُغيّر.
     *
     * وكلٌّ منها محروسٌ بـ[MIN_TOKEN_EPOCH] كي لا يُحسب هاشٌ قصير عمرًا سالبًا
     * فيُسقط رابطًا حيًّا (وهو نفس الصنف الذي حُرس في `t=`).
     *
     * ★★ و**الأخذ بأصغر الطوابع لا بأوّل مطابقة**: قيس على NetShort أنّ الرابط
     * يحمل `Expires` **و**`auth_key` معًا، وعلى KalosTV مثلهما — فرابطٌ يحمل
     * حقلين يقصر عمره بأقربهما انتهاءً، وهذا هو ما يجعل «صغير» صحيحًا. (وأثر
     * ذلك على الترتيب وحده؛ ولا إسقاط.)
     */
    private fun rawTokenEpoch(url: String): Long? {
        val cands = mutableListOf<Long>()
        tokenExpiryEpoch(url)?.let { cands.add(it) }
        // الأنماط مرتّبة بحسب الشيوع المقيس، وكلٌّ منها يتوقّف عند أوّل ما ليس
        // رقمًا فـ`1787439293-<hash>` تُقرأ `1787439293`.
        for (re in listOf(
            Regex("""[?&]verify=(\d{9,})"""),
            Regex("""[?&]auth_key=(\d{9,})"""),
            Regex("""[?&](?:ts|wsTime|Expires|expire|expires)=(\d{9,})"""),
        )) {
            re.find(url)?.groupValues?.get(1)?.toLongOrNull()
                ?.takeIf { it >= MIN_TOKEN_EPOCH }?.let { cands.add(it) }
        }
        // ★ 2026-10-17. **رموز البروكسي داخل الحمولة لا في الاستعلام.**
        //
        // الالتقاط أثبت الحالة: رابط BiliTV على `stream-e1.narto-drama.com/e/m/`
        // فردّ 403 وجسمه حرفيًّا `invalid or expired token` ⇒ خطأ 2004. والحمولة
        // ‎base64url(JSON)‎: `{"v":1,"t":"prov","src":"…m3u8","prov":"bilitv",
        // "exp":1792195200}` — و`exp` **ماضٍ بـ٧٫٣ ساعة**. لكنّه ليس في الاستعلام،
        // فلا تراه بوّابتا `t=` ولا `verify=`، ويُبَثّ الرابط المنتهي فيظهر 2004
        // — وهو نفس صنف علّة [[dramatip-2004-expired-token-leak]] لكن في حقلٍ
        // ثالث. ومسار `src` داخل البروكسي هو الرابط الموقّع الحقيقي، فتاريخه هو
        // الحقيقة. (وهذا يوافق ما قِيس سابقًا على `narto-shared` في
        // [[narto-ok-true-but-source-refreshed-false]].)
        proxyPayload(url)
            ?.let { Regex(""""exp"\s*:\s*(\d{9,})""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.takeIf { it >= MIN_TOKEN_EPOCH }?.let { cands.add(it) }
        return cands.minOrNull()
    }

    /**
     * ★ 2026-10-17. حمولة بروكسي نارتو (`…/e/m/<b64>.<sig>`) مفكوكةً كنصّ JSON،
     * أو null إن لم تكن من ذلك الشكل. تفكيكٌ واحدٌ يخدم الحكمين: الزمن
     * ([rawTokenEpoch]: `exp` هو طابع الانتهاء) والنوع ([classify]).
     */
    private fun proxyPayload(url: String): String? {
        val m = Regex("""/e/[ms]/([A-Za-z0-9_-]+)""").find(url) ?: return null
        return try {
            // نفس تفكيك DeepDrama (المقيس): ترقيع الحشو بعد صيغة URL الآمنة.
            var s = m.groupValues[1].replace('-', '+').replace('_', '/')
            while (s.length % 4 != 0) s += "="
            String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { null }
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

    // ★ 2026-10-17. **لماذا تُثبَّت معطيات السلسلة لا تُخبَّأ في الذاكرة فحسب؟**
    //
    // `seriesMeta` كانت خريطةً في الذاكرة وحدها، تُمحى كلّها حين تتجاوز ٦٤
    // مفتاحًا. وقِيس على الجهاز أنّ صفحة الحلقة تحمّل **~٤٥٠ كيلوبايت**، وأنّ
    // جلبها يسقط سقوطًا عابرًا على الشبكات البطيئة (ثلاث محاولاتٍ كلٌّ منها
    // مهلةٌ كاملة). وحين تسقط ولم يبقَ في الذاكرة bookId، صار `apiLinks`
    // **لا يُستدعى أصلًا** — فتبقى وصفة الصفحة المخبّأة وحدها، ورموزها منتهية
    // مقيسةً على FlickReels وiDrama وMoboReels، فتُسقَط كلُّها وتظهر
    // «لم يعثر على روابط». فالحفظ الدائم هو ما يقطع تلك السلسلة كلّها:
    // `bookId`/`firstSerial` ثابتان للسلسلة كلّها، فلا ضررَ في بقائهما.
    private val META_PREFIX = "meta:"

    // سقفٌ صغير: مفاتيح خفيفة، والغرض ألّا تسقط سلسلةُ الجلسة الواحدة.
    private fun putSeriesMeta(key: String, m: SeriesMeta) {
        if (key.isBlank()) return
        if (seriesMeta.size > 64) seriesMeta.clear()
        seriesMeta[key] = m
        // التثبيت يبقى بعد إغلاق التطبيق وإعادة تشغيله: مسح الخريطة لا يُسقطه.
        try {
            prefs?.edit()?.putString(
                META_PREFIX + key,
                listOf(m.bookId ?: "", m.firstSerial.toString(), m.source ?: "").joinToString("|"),
            )?.apply()
        } catch (_: Exception) { /* التثبيت زائدٌ لا أساسٌ — الفشل هنا لا يُبطل الحلقة */ }
    }

    /** قراءة ما ثُبِّت سابقًا، أو `null` إن لم يكن هناك ما يصلح لسؤال الـAPI. */
    private fun persistedMeta(key: String): SeriesMeta? {
        if (key.isBlank()) return null
        seriesMeta[key]?.let { return it }
        val raw = try { prefs?.getString(META_PREFIX + key, null) } catch (_: Exception) { null }
        if (raw.isNullOrBlank()) return null
        val p = raw.split("|")
        val book = p.getOrNull(0)?.takeIf { it.isNotBlank() }
        val source = p.getOrNull(2)?.takeIf { it.isNotBlank() }
        val first = p.getOrNull(1)?.toIntOrNull() ?: 1
        if (book == null && source == null) return null
        return SeriesMeta(book, first, source).also { seriesMeta[key] = it }
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
            if (attempt < 3) delay(900L * attempt)
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
        // ★ 2026-10-18. **فخّ سياسة مراجع الدلو على HappyShort.**
        //
        // `media.tqshort.com` يقف خلف حافّة CloudFront/S3 تحمل سياسة مراجع
        // على الدلو، والقاعدة فيها تضادٌّ خبيث: `https://tqshort.com/` — وهو
        // ما تُنتجه القاعدة العامة من آخر نطاقين — **مرفوضة** بـ403 جسمه
        // `denied by bucket referer policy`، بينما `https://www.tqshort.com/`
        // **مقبولة**، والمرجع الغائب (بلا Referer) كذلك. قِيس على الرابط
        // نفسه في الالتقاط 2026-10-18:
        //   none=200   tqshort.com/=403   www.tqshort.com/=200   dramatip=403
        // أي أنّ الرابط حيٌّ والمرجع وحده هو ما يقتله. ولأنّ الفحص
        // (`Probe.one`) والبثّ (`addLink`) كلاهما يمرّ عبر `platformReferer`
        // — ويُقيس `segStatus` للأجزاء كذلك — كانت السياسة تُسقط رابطًا
        // حيًّا فيُصنَّف موتًا قاطعًا فتُطرح الحلقة بـ«لم يعثر على روابط».
        // والإصلاح هنا مرجعٌ واحد لا يمسّ منصّةً أخرى: لا مضيفٌ سوا tqshort
        // ينتهي root=tqshort.com، وقِيس أنّ www مقبولة و200 ثابتة.
        if (root == "tqshort.com") return "https://www.tqshort.com/"
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
     * ★ 2026-10-18. **فخّ `org.json`: الشرطة المائلة تُهرَب فتُعمي كلّ أنماط الاستخراج.**
     *
     * `JSONObject.toString()` يمرّ بـ`JSONStringer` في libcore، وهو يُهرِب `/`
     * إلى `\/` (نصّ RFC 4627: علامة التنصيص والشرطة المائلة العكسيّة والشرطة
     * المائلة). وكلّا نمطينا يُغلق على علامة تنصيصٍ مباشرةً:
     * `"enc":"([A-Za-z0-9+/=]+)"`. فحين تحمل القيمة `/` — وهي تحمله دائمًا لأنّ
     * الحمولة base64 لرابطٍ — يصير النصّ `"enc":"Q56F…\/…"`، والحرف الذي يلي
     * المجموعة `\` لا `"`، فيفشل **المطابقة كلّها**. و`Regex` بلا مرساةٍ يُسقط
     * الموضع الفاشل ولا يقصّه، فلا تبقى من القيمة شيء.
     *
     * والأثر مقيسٌ على الجهاز في التقاط v23 لمنصّة BiliTV:
     *   `episode-source ok … stale=24823s token=?s`
     * أي أنّ الأرقام (`refreshAfter`) طابقت والحمولةَ لم تطابق. فسقط
     * `descBestLife` إلى null، وسقط `candsOf(apiDesc)` إلى **صفر مرشّحين**، فلم
     * يعرف الكود عمر الرمز أصلًا — وهو جذر «لم يعثر على روابط» و«‎2004» معًا.
     *
     * ووصفة الصفحة ما كانت تُبتلى لأنّ `extractFlight` يمرّ بـ[unescapeJsonStr]
     * فيُفكّ قبل المطابقة — وهذا هو الفرق الوحيد بين المسارين.
     *
     * فالعلاج الجذريّ واحد: **التطبيع عند الحدّ** — كلّ نصٍّ آتٍ من `JSONObject`
     * يمرّ من هنا قبل أيّ استخراج. ولا يمسّ النصّ السليم: `\/` و`\"` و`\\`
     * و`\uXXXX` إعادةُ كتابةٍ لا عملية على وصفةٍ بلا هرب، والحمولات المفكوكة
     * تبقى كما هي.
     */
    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val cp = hex.toIntOrNull(16)
                    if (cp != null && hex.length == 4) { sb.append(cp.toChar()); i += 6 }
                    else { sb.append(c); i++ }
                }
                '/', '"', '\\' -> { sb.append(n); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
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
                        // ★ 2026-10-18. **نصّ الوصفة يمرّ بـ[unescapeJson] أوّلًا.**
                        // `desc.toString()` يُهرِب `/` إلى `\/`، فيعمي `encRe`
                        // و`qualRe` معًا (التفصيل في تعليق unescapeJson) —
                        // وهو ما جعل سجلّ الجهاز يقول `token=?s` على BiliTV.
                        // ويستفيد منه أيضًا [parseChain]/[chainCands] لأنّ
                        // [plainFromDescriptor] أدناه يعمل على النصّ المطبَّع.
                        val descText = unescapeJson(desc.toString())
                        // ★ 2026-10-18. عدّادٌ واحد يجعل الجذر مرئيًّا في السجلّ:
                        // إن صفر، فالوصفة بلا حمولةٍ مفكوكة أصلًا (فلا حكمَ زمنيّ
                        // ولا مرشّح) — وهو ما كان يمرّ صامتًا قبل الإصلاح.
                        logD("DramaTip: descriptor chain=${desc.optJSONArray("chain")?.length() ?: 0}" +
                            " enc=${encRe.findAll(descText).count()} book=$bookId ser=$serial")
                        val staleness = descStaleness(descText)
                        // ★ 2026-10-17. الحكم الثاني — وهو الحاسم: **عمر الرمز
                        // نفسه**. `refreshAfter` قد يغيب تمامًا (قِيس اليوم
                        // `None` على الوصفة المخبّأة لكتابٍ رمزُه منتهٍ)، فلا
                        // تُكتشف البلى بالحقل بل بالرمز. وحين ينتهي الرمز يكون
                        // بثّه خطأ 2004 (BAD_HTTP_STATUS) حتمًا: القائمة تفتح
                        // ثم يرفض الحافّة قطعةَ الفيديو بـ403.
                        val bestLife = descBestLife(descText)
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
                            return plainFromDescriptor(descText)
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
                            return plainFromDescriptor(descText)
                        }
                        // ★ رصدٌ للتقاط: كم تأخّرت الوصفة عن موعد بلاها، وكم
                        // يتبقّى لرمزها؟ الرقمان هما الفرق بين «بائتة» و«طازجة»
                        // في سجلّ الجهاز، ويُقطع الشكّ في أنّ الطلب كان خاطئًا.
                        android.util.Log.d(
                            "DramaTip",
                            "episode-source ok book=$bookId ser=$serial bust=$bust" +
                                " stale=${staleness ?: "?"}s token=${bestLife ?: "?"}s"
                        )
                        return plainFromDescriptor(descText)
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
            if (attempt < 3) delay(900L * attempt)
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
        // ★ 2026-10-17. **بروكسي نارتو: الرابط الحقيقي داخل الحمولة لا في المسار.**
        // مسارُه `/e/m/<b64>` بلا امتداد، فكان يُصنَّف `VIDEO` (‎[VIDEO] في الالتقاط)
        // بينما `src` داخله `…-video-hd.m3u8` — أي أنّ فحصًا تعذّر (مهلة) كان
        // يُسلِّمه للمشغّل كمقطعٍ تدريجيّ فيفشل تفكيكه. والحمولة تحمل الامتداد
        // الحقيقي، فهي الحُجّة عند غياب الجسم المقيس.
        proxyPayload(url)?.let { p ->
            val src = Regex(""""src"\s*:\s*"([^"]+)"""").find(p)?.groupValues?.get(1)
            if (src != null) {
                return if (src.contains(".m3u8", true)) ExtractorLinkType.M3U8 to null
                else ExtractorLinkType.VIDEO to null
            }
        }
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
    //
    // ★ 2026-10-17. `finalUrl`: الرابط الذي انتهى عنده التحويل إن كان مختلفًا عن
    // الأصل. قِيس على JoyReels أنّ رمزَها **ماضٍ** في الوصفة، والمضيف **يجدّده
    // بنفسه** بتحويل ‎302‎ إلى رابطٍ طازج صالح (‎auth_key=1947835727‎ مقابل
    // ‎1787439293‎). وكان الرابط المُنعش يُبثَّ **بلازمته المنتهيَة** فيحتاج
    // اللاعب إلى متابعة التحويل؛ وقِيس على الشبكات البطيئة أنّ ذلك يسقط فيظهر
    // «لم يعثر على روابط» على محتوىٍ يعمل. فصار الطازج هو ما يُبثَّ.
    private data class Verdict(
        val alive: Boolean,
        val contentType: String,
        val body: String?,
        val why: String,
        val certain: Boolean,
        val finalUrl: String? = null,
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
                if (attempt < 3) delay(600L * attempt)
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
                c.connectTimeout = PROBE_TIMEOUT_MS
                c.readTimeout = PROBE_TIMEOUT_MS
                val code = c.responseCode
                val landed = c.url?.toString()?.takeIf { it.isNotBlank() && it != u }
                // ★ 2026-10-17. **الرابط الذي انتهى عنده التحويل.** `HttpURLConnection`
                // يُحدِّث حقلَ `url` حين يتابع، فيصير هو وجهة التحويل الأخيرة.
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
                        landed,
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
                        return Verdict(false, ct, null, "proxy body: ${b.take(48)}", true, landed)
                    }
                }
                // ★ 2026-10-16. رابطٌ يدّعي HLS (امتداد ‎.m3u8‎ أو نوع محتوى mpegurl)
                // وقُرئ جسمه فوجد **ليس** قائمة تشغيل ⇒ هو صفحة خطأ مُتنكّرة بزي
                // قائمة تشغيل. قِيس على DramaBox: مسار ‎/v1/dramabox/…m3u8‎ يردّ
                // 200 بقالب HTML بمقاس 27KB، فيُصنَّف M3U8 بالامتداد ويُبثّ، فيردّ
                // اللاعب «خطأ 3002» (بايتات ليست وسائط). الحكم هنا يمنعه من الأصل.
                val claimsHls = u.contains(".m3u8") || ct.lowercase().contains("mpegurl")
                if (claimsHls && !body.isNullOrEmpty() && !body.contains("#EXTM3U")) {
                    return Verdict(false, ct, body, "not a playlist: ${body.take(48)}", true, landed)
                }
                // ★ 2026-10-17. **جذر خطأ 2004: القائمة تُفتح ثم يُرفض الجزء.**
                //
                // ‎2004‎ = `ERROR_CODE_IO_BAD_HTTP_STATUS`: اللاعب يفتح قائمة البثّ
                // بنجاح ثم تردّ **قطعة الفيديو** 4xx. وقِيس اليوم على BibiShort أنّ
                // قائمتها تردّ ‎200 application/vnd.apple.mpegurl‎، بينما **كلّ جزءٍ
                // يحمل امتداد `?s=` خاصًّا به** (‎segment-0.ts?s=fca24…‎ مقابل
                // ‎segment-1.ts?s=bdaf1…‎) — أي أنّ التوقيع مُسبقٌ لكلّ جزءٍ على
                // حِدة، فينتهي والقائمة تبقى حيّة الشكل. فحصُ القائمة وحده لا يرى
                // ذلك أصلًا، ويُبثَّ الرابط فيظهر ‎2004‎.
                //
                // والفحص هنا يقرأ **أوّل أجزاء** القائمة (بحدود ‎Range: 0-1023‎
                // فتبقى الرحلة كيلوبايتٍ واحد بدل 751KB مقيسةً) ولا يُسقط إلا على
                // **رفضٍ قطعيّ** (‎4xx‎) في قائمة VOD مُعلَنة ‎#EXT-X-ENDLIST‎ —
                // فلا تُقتل البثوث الحيّة ولا تُسقط حلقةً بسبب مهلةٍ أو انقطاع.
                // وحين يُرفض يُعاد الفحص بمُرجِع اللاعب ومُتصفّحه أيضًا: قِيس أنّ
                // بعض الحوافّ تفرّق بين المرجِعين، فلا يُسقط رابطٌ يقبله اللاعب.
                if (claimsHls && body.contains("#EXT-X-ENDLIST")) {
                    val child = firstChildOf(c.url?.toString() ?: u, body)
                    if (child != null) {
                        // ★ 2026-10-18. **مرجعُ القائمة لا مرجعُ الجزء.**
                        //
                        // اللاعب يرسل هامش `Referer` واحدًا لكلّ رحلات مصدرٍ واحد:
                        // هو مرجعُ القائمة (`platformReferer(playUrl)`)، لا مرجعَ
                        // مضيفِ الجزء. وقِيس على HappyShort أنّ قائمتها تُسلَّم من
                        // `pl.shortdizilab.com` بينما أجزاؤها **موكَلة** خلف
                        // `hls-proxy-i4ic7.b-cdn.net/?url=<media.tqshort.com/…>`.
                        // فكانت `platformReferer(child)` تُرجع `https://b-cdn.net/`
                        // (جذر مضيف الجزء) وهو **مرفوض** ‎403، بينما مرجع القائمة
                        // `https://shortdizilab.com/` **مقبول** ‎200 — أي أنّ
                        // المسبار كان يُسقط رابطًا حيًّا يصلحه اللاعب، فيظهر
                        // «لم يعثر على روابط». والقاعدة: افحص الجزء بمَرجع القائمة.
                        val segRef = platformReferer(c.url?.toString() ?: u)
                        val segCode = segStatus(child, segRef, ANDROID_UA)
                        // ★ 2026-10-18. **الشِّقّ المقلوب في بوّابة الإجماع.**
                        //
                        // التعليق يقول «لا يُسقط رابطٌ يقبله اللاعب»، لكن التنفيذ
                        // كان يفعل العكس: `if (byPlayer in 400..499) byPlayer
                        // else segCode` — فإذا قال فحص اللاعب إنّ الجزء **حيّ**
                        // (‎200‎) رجع الحكم إلى `segCode` المرفوض ويُسقط. والصواب
                        // أنّ قبولَ اللاعب يُبطل الرفض: نُرجع ‎-1‎ (خارج نطاق
                        // ‎4xx‎) فلا يدخل `if (agreed in 400..499)` ولا يُسقَط.
                        val agreed = if (segCode in 400..499) {
                            val byPlayer = segStatus(child, TIP_MAIN, TIP_UA)
                            if (byPlayer in 400..499) byPlayer else -1
                        } else segCode
                        if (agreed in 400..499) {
                            return Verdict(
                                false, ct, body, "segment HTTP $agreed", true, landed,
                            )
                        }
                    }
                }
                Verdict(true, ct, body, "", true, landed)
            } catch (e: Exception) {
                // شهادة TLS مرفوضة حكمٌ: البايتات موجودة لكنّ كلّ عميلٍ صارم يرفضها.
                // ★ 2026-10-16. ومضيفٌ لا يُحلّ اسمه أصلًا حكمٌ كذلك: لا عميلَ يبلغه.
                // قِيس على DotDrama أنّ مضيفه videotv.vividshort.com سقط في كلّ
                // فحصٍ من الجهاز؛ وحين كان ‎UnknownHostException‎ يُعدّ «تعذّر
                // معرفة» بُثّ الرابط الميت فاختار اللاعب التشغيل وأظهر «خطأ 2001»
                // — وهو عطلُ شبكةٍ في ظاهر الأمر لا رابطٌ ميت. وما عدا ذلك (مهلة،
                // انقطاع اتصال) لا يقول شيئًا عن الرابط — يُبثّ بلا حكمٍ في آخر
                // القائمة. ★ وعن إعادة الحلّ المتكرّرة: انظر التعليق داخل catch.
                var certain = e is javax.net.ssl.SSLHandshakeException
                if (e is java.net.UnknownHostException) {
                    // ★ 2026-10-18. **الحلّ يُعاد ثلاثًا بفاصلٍ لا مرّةً واحدة.**
                    //
                    // القاعدة سليمة — «مضيفٌ لا يُحلّ حكمٌ» — لكن تنفيذها كان
                    // ضعيفًا: إعادةُ حلٍّ **فوريّة** تقع داخل النافذة نفسها التي
                    // فشل فيها الطلب، فيُصيب العابرُ المحاولتين معًا فتُصنَّف
                    // ميتةً مُثبتة. وهذا مقيسٌ على FlickReels في التقاط 2026-10-18:
                    // أُسقطت `zshipricf.farsunpteltd.net` بحكم «موتٍ مُثبت»،
                    // ثم حُلّ الاسم على الجهاز نفسه بعد لحظات (104.18.2.42).
                    // أي أنّ حلقةً تعمل سقطت بسباقِ DNS عابر.
                    //
                    // والإصلاحان معًا: (١) ثلاث محاولاتٍ متباعدة، فالمضيف الميت
                    // فعلًا (videotv.vividshort.com مقيسًا) يسقط في الثلاث كلّها
                    // فيبقى حكمه كما كان؛ (٢) وحين يفلح الحلّ في أيّ محاولة
                    // نُعيد الحكم إلى «تعذّر معرفة» لا «ميّت» — فيُبثّ الرابط في
                    // مؤخّرة القائمة ويعيد التطبيقُ فحصه، ولا يُسقط سباقٌ عابرٌ
                    // رابطًا حيًّا. ولا يُبثّ رابطٌ مُثبتُ الموت أبدًا، فالقاعدة
                    // الأولى لم تُخرَق.
                    var resolved = false
                    for (attempt in 1..3) {
                        val ok = try { java.net.InetAddress.getByName(uri.host); true }
                        catch (_: Exception) { false }
                        if (ok) { resolved = true; break }
                        if (attempt < 3) try { Thread.sleep(150L * attempt) } catch (_: Exception) {}
                    }
                    // داخل هذا الفرع `e` هو UnknownHostException بالضرورة، فالحكم
                    // موتٌ مُثبتٌ إن لم يفلح الحلّ في المحاولات الثلاث كلّها.
                    certain = !resolved
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

        /**
         * أوّل مسارٍ حقيقيّ في قائمة التشغيل (أول سطرٍ ليس تعليقًا)، مُحلًّا على
         * مُنسِق قائمة الأب. ‎null‎ إن لم تُعثر قائمة أوّلًا.
         *
         * حلُّ المسار ضروريّ: قِيس على BibiShort أنّ أبناءها نسبيّون
         * (‎segment-0.ts?s=…‎)، وعلى iDrama أنّ أبناءها **مُطلَقون على الأصل**
         * (‎/d110e…/71d5….ts‎) — والاثنان يجب أن يُحلّا على مُضيف القائمة لا على
         * مجلّدها (وهو ما أصلحه حلّ HLS في v22).
         */
        private fun firstChildOf(playlistUrl: String, body: String): String? {
            val line = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return null
            return try { URL(URL(playlistUrl), line).toString() } catch (e: Exception) { null }
        }

        /**
         * كود حالة جزءٍ واحد. ‎-1‎ عند أيّ فشلٍ في الاتصال (مهلة أو DNS أو TLS):
         * الفشل لا يُثبت رفضًا، فلا يُسقط به رابطٌ (قاعدة «الحكم مقابل لا نعرف»).
         */
        private fun segStatus(segUrl: String, referer: String, ua: String): Int {
            var conn: HttpURLConnection? = null
            return try {
                val c = URL(segUrl).openConnection() as HttpURLConnection
                conn = c
                c.requestMethod = "GET"
                c.setRequestProperty("Referer", referer)
                c.setRequestProperty("User-Agent", ua)
                // ‎Range‎ يُبقي الرحلة كيلوبايتٍ واحد (الجزء 751KB مقيسةً على BibiShort)،
                // والمُضيف الذي لا يدعمه يتجاهل الرأس فلا يتغيّر كود الحالة.
                c.setRequestProperty("Range", "bytes=0-1023")
                c.instanceFollowRedirects = true
                c.connectTimeout = PROBE_TIMEOUT_MS
                c.readTimeout = PROBE_TIMEOUT_MS
                val code = c.responseCode
                runCatching { c.inputStream?.close() }
                code
            } catch (e: Exception) { -1 } finally {
                runCatching { conn?.disconnect() }
            }
        }
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

    /**
     * ★ 2026-10-17. **حلّ روابط HLS الأبناء (variants والأجزاء) بلا قاعدةٍ خاطئة.**
     *
     * قِيس على **iDrama** (منصّة شكا المستخدم من 2004 عليها) أنّ قائمة الجودة
     * تحمل المسارات **مطلقةً من الجذر** لا نسبةً لدليل القائمة:
     * ‎`/d110e…/71d5….ts?ts=…&secret=…`‎. وكان الكود يبنيها بـ
     * `c.url.substringBeforeLast('/') + "/" + seg` فينتج
     * ‎`https://v-a.idrama.video/d110e…//d110e…/71d5….ts`‎ — أي مسارًا مضاعفًا
     * ⇒ **403 text/html**، وهو خطأ 2004 بعينه. والمسار نفسه على
     * ‎`https://v-a.idrama.video` + المسار المطلق ⇒ **200 video/mp2t**. فالعلّة
     * كانت في التركيب لا في المصدر.
     *
     * وثلاثة أشكالٍ للحلّ كما ينصّ RFC 3986 (وهو ما يفعله أيُّ مشغّل HLS):
     *  - `https://…` مطلقٌ كامل ⇒ كما هو.
     *  - `//host/…` نسبةُ بروتوكولٍ ⇒ بروتوكول القائمة + الرابط.
     *  - `/…` مطلقٌ من الجذر ⇒ **أصل** القائمة (المضيف لا دليله) + الرابط.
     *  - وما عدا ذلك نسبيٌّ ⇒ دليل القائمة + الرابط.
     *
     * وكان الخلل غائبًا عن كلّ فحصٍ ساكن، لأنّ الكود «يعمل» ويعيد رابطًا صالحَ
     * الشكل — لكنّه رابطٌ خاطئ يُسقِط البثّ في المشغّل وحده.
     */
    private fun resolveHlsChild(playlistUrl: String, child: String): String {
        val c = child.trim()
        if (c.startsWith("http://") || c.startsWith("https://")) return c
        val u = java.net.URI(playlistUrl)
        val origin = "${u.scheme}://${u.rawAuthority}"
        return when {
            c.startsWith("//") -> "${u.scheme}:$c"
            c.startsWith("/") -> origin + c
            else -> playlistUrl.substringBeforeLast('/', playlistUrl) + "/" + c
        }
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
            val cached = persistedMeta(bareSlug(slug))
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
            // ★ 2026-10-17. خطّ نجاة في **الآخر تمامًا**: أبناء القائمة المتكيّفة
            // بجوداتهم الثابتة. تُبثّ أخيرًا (وأولويّتها منخفضة) فلا تسبق
            // المتكيّفة ولا تسرق اختيار المشغّل الافتراضي إن كانت المتكيّفة حيّة.
            val fallbacks = mutableListOf<ExtractorLink>()
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

            // ★★ 2026-10-18. **«موتٌ مُثبت» صنفًا موجبًا، لا استثناءً في قائمة.**
            //
            // خطّ النجاة (`lenient`) أُنشئ ليُرخي الحكم على ما **تعذّر الحكم
            // عليه** وحدَه (مهلة، انقطاع اتصال) فلا تسقط حلقةٌ حيّة لعابرِ شبكة.
            // لكن تنفيذه كان مقلوبًا: يشترط `raw.certain == true` — أي أن يطلب
            // إرخاء **الأحكام القاطعة** — ثم يستثني ثلاث فئاتٍ معدودة
            // (`wrongToken`، `fakePlaylist`، `tokenExpired`). وكلّ حكمٍ قاطعٍ
            // خارج تلك الثلاث يمرّ: و**‎404‎ منها**، لأنّ `certain=true` وليس
            // رابطًا موقّعًا فتفشل الثلاثة كلّها فيُقلب إلى «حيّ» ويُبثّ.
            //
            // وهذا هو ما ظهر في التقاط الجهاز 2026-10-18 فعلًا:
            //   `CS3ExoPlayer: playerError (…pl.shortdizilab.com/v1/happyshort/17169797.m3u8…)
            //    InvalidResponseCodeException: Response code: 404`
            // — بُثّ رابطٌ مردودٌ بـ‎404‎ من الخادم مع أنّ المسبار أثبته ميتًا،
            // و`lenient=true` كانت في السجلّ في اللحظة نفسها. وهو أصل «الخطأ
            // 2004» نفسه من وجهٍ آخر: رابطٌ يُبثّ بعد أن أُثبت موته.
            //
            // والقاعدة الصحيحة كما تقولها تعليقات البوّابة نفسها: **لا يُرخى
            // القاطع أبدًا، ويُرخى غير القاطع فقط**. وهذا ما يعنيه `!certain`:
            // لا مهلةٌ ولا انقطاعَ يُثبتان موتًا، وأيّ كود حالةٍ يُثبته.
            val definiteDeath = { v: Verdict? -> v != null && !v.alive && v.certain }

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
            // التوقيع الناقص والقائمةُ المزيفة تبقى موتًا صريحًا، فبثّها يعني خطأ
            // 2004 أو 3002 على محتوى يعمل في الموقع. **والرمز المنتهي خرج من
            // هذا الصنف اليوم**: قِيس على JoyReels أنّ المضيف يجدّده بتحويل 302،
            // فبثُّه ليس خطأً حتمًا (انظر بوّابة الإسقاط).
            var lenient = false
            // ★ 2026-10-18. **عدّادات الأحكام القاطعة** — الدليل الذي كان غائبًا.
            //
            // التقاط 2026-10-18 أظهر `video=3` ورابطًا مردودًا بـ‎404‎ بلا أيّ أثر
            // في السجلّ يفسّره: خرج رابطٌ مُثبتُ الموت من البوّابة ولم يُطبع
            // سطرٌ واحد يقول ذلك. فصار كلّ حكمٍ قاطعٍ يُعدّ، والملخّص يُطبع في
            // سطر الإخراج الأخير — فيُقرأ العطب من السجلّ بلا تخمين، ويُقارَن
            // العدد بالمُبثّ (`video=`): ‎403‎ أو ‎404‎ داخل المُبثّ = خللٌ في
            // البوّابة لا في الشبكة. مفاتيح مرتّبة كي يبقى السطر ثابتًا للقراءة.
            val certainReasons = java.util.TreeMap<String, Int>()
            fun noteCertain(v: Verdict?) {
                if (v == null || v.alive || !v.certain) return
                val k = v.why.substringBefore(':').substringBefore(' ').ifBlank { "-" }
                certainReasons[k] = (certainReasons[k] ?: 0) + 1
            }

            suspend fun uniqOf(fc: List<Cand>): List<Cand> {
                val cs = if (fc.isNotEmpty()) fc + pageCands else pageCands
                val seen = HashSet<String>()
                // ★ 2026-10-16. الأطزج أوّلًا عند التكافؤ: الوصفة الواحدة قد
                // تحمل أكثر من عنصرٍ لسلسلةٍ (MoboReels، HappyShort)، وعنصرٌ
                // `refreshAfter` فيه ماضٍ بائتٌ بائتٌ. والترتيب الافتراضيّ كان
                // ترتيب الموقع، فيسبق البائتُ الحيَّ فيختاره اللاعب فيفشل بـ403.
                // و`distinctBy` بعد الترتيب تُبقي الأطزج عند تطابق الرابط.
                //
                // ★★ 2026-10-17. **ترتيبُ «الرموز قبل غيرها» يمتدّ إلى الشوطين.**
                // `tokenExpired` وحدها لا تفرّق بين «منتهٍ» و«غير مُحكوم» (كلاهما
                // يرجع عمرًا ≤ 0)، وهذه الفجوة بالذات هي التي كانت تجعل رابطًا
                // منتهيًا **يسبق** رابطًا حيًّا إذا كان الثاني **بلا حقل زمنيّ
                // معروف** — وهذا هو شِقّ علّة MoboReels المتبقّي: وصفةُ الصفحة
                // تحمل رمزًا منتهيًا (‎-9064ث‎) والـAPI تحمل حيًّا (‎+3597ث‎)،
                // فإذا سقط تصنيف `t=` لأيّ سببٍ صار الحيُّ «غير مُحكوم» ورجع
                // ‎Long.MIN_VALUE‎ فسبقه الميت. والترتيب الصريح ثلاثيّ:
                // حيٌّ (٢) ثم **مجعولٌ** أي أُعلن انتهاؤه صراحةً (١) ثم مجهول (٠).
                return cs.filter { seen.add(it.url) }.sortedWith(
                    compareByDescending<Cand> {
                        val rem = tokenRemainingSeconds(it.url)
                        when {
                            rem == null -> 0L
                            rem <= 0 -> 1L
                            else -> 2L
                        }
                    }.thenByDescending { tokenRemainingSeconds(it.url) ?: Long.MIN_VALUE }
                )
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
            //
            // ★★ و**صُحِّح اليوم تصحيحًا جوهريًّا**: كان الحكم المحلّيّ يُسقِط
            // الرحلة كلّها فلا يُفحَص الرابط أبدًا. وقِيس على JoyReels أنّ ذلك
            // **خطأ**: رابطٌ رمزُه **ماضٍ** (`auth_key=1787439293`، أي أغسطس ٢٠٢٦)
            // يردّ على الطلب ‎**302**‎ إلى رابطٍ **طازج** صالح
            // (`auth_key=1947835727-…`)، وتلك قائمته تُبَثّ 200 والنوع
            // `application/vnd.apple.mpegurl`، وأجزاؤها 200 و`video/mp2t`. أي أنّ
            // المضيف **يجدّد بنفسه** بالتحويل. فإسقاطه محلّيًّا = «لم يعثر على
            // روابط» على محتوىٍ يعمل. فالقاعدة الصحيحة: **الرمز المنتهي يُرتَّب
            // آخرًا ويُفحَص ولا يُحكم عليه من الرابط** — والحكم للشبكة.
            // أمّا منع صرف المهلة على حكمٍ محكومٍ سلفًا فقد صار في موضعين أدقّ:
            // ترتيب `uniqOf` (الأطزج أوّلًا) وبوّابة الإسقاط عند البثّ.
            // ★ 2026-10-17. **الجذر الثاني لدوران الفيديو.** كان الرمز المنتهي
            // يُفحَص أوّلًا (٧ث مهلةً للطلب، ثم حتى ٣ إعادات = ‎~٢٢ث‎) **ثم**
            // يُسقَط — فيدفع المستخدم ثمن رحلةٍ محكومٍ عليها سلفًا، ويراها دورانًا.
            suspend fun probeAll(list: List<Cand>): List<Verdict?> = coroutineScope {
                list.map { c ->
                    async(Dispatchers.IO) {
                        if (probing) probe.check(c.url) else null
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
            //
            // ★ 2026-10-17. **هل بقي حيٌّ أصلًا؟** هذا الرقم هو ما يحوّل بوّابة
            // «الرمز المنتهي» من إسقاطٍ مطلق إلى إسقاطٍ مشروط. قِيس على JoyReels
            // أنّ وصفتها كلّها رموزٌ **ماضية** والمضيف يجدّدها بتحويل 302؛ فإذا
            // تعذّر الفحص على شبكةٍ بطيئة لم يبقَ حيٌّ، وكان الإسقاط المطلق
            // ينتهي بـ«لم يعثر على روابط» على محتوىٍ يعمل. والقاعدة الآن: لا
            // يُسقط المنتهي إلا بموتٍ **مُثبت** أو مع وجود بديلٍ حيّ.
            val anyAlive = verdicts.any { it?.alive == true }
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
                //
                // ★★ 2026-10-17. **إلا إذا أثبت الفحص نفسه أنّ الرابط حيّ.**
                // هذا الاستثناء صنعته قياسات JoyReels: الرمز الماضي (`auth_key`
                // = ‎أغسطس ٢٠٢٦‎) لا يعني رابطًا ميتًا هناك، بل يعني أن المضيف
                // سيحوّل ‎302‎ إلى رابطٍ جدّده بنفسه — والمسبار يتبع التحويل
                // (`instanceFollowRedirects=true`) فيقرأ قائمةً حيّة. وكانت
                // البوّابة تسبق حكم المسبار فتُسقط الرابط قبل أن ترى نتيجته،
                // فيظهر «لم يعثر على روابط» على محتوى يعمل. والقاعدة الآن:
                // **حكمُ الشبكة الحيّ يتقدّم على حساب العمر من الرابط**، أمّا
                // عند غياب الحكم (cannot-tell) أو موتٍ قطعيّ نُبقي حساب العمر
                // كما كان (فهو ما يمنع بثّ المنتهي حين تعذّر الفحص).
                if (tokenExpired(c.url, raw) && raw?.alive != true) {
                    // ★ 2026-10-17. **بوّابة مشروطة لا مطلقة.**
                    //
                    // المطلقة كانت تسقط كلّ رمزٍ مضى أجله لم يُثبت المسبار حيّته —
                    // وهذا هو جذر «لم يعثر على روابط» على JoyReels: وصفتها كلّها
                    // رموزٌ ماضية والمضيف يجدّدها بتحويل ‎302‎، فحين تنتهي مهلة
                    // الفحص على شبكةٍ بطيئة تسقط الحلقة كلّها بلا رابطٍ واحد.
                    // والشرط الآن: يُسقط المنتهي بموتٍ **مُثبت** (`certain`)، أو
                    // مع **بديلٍ حيّ** في المرشّحين (فلا داعي لبثّ ميت). أمّا
                    // حين لا حيَّ أصلًا، فبثُّه آخرُ خطّ نجاة: اللاعب يتبع
                    // التحويل بنفسه وقد يجدّده — وهو خيرٌ من «لا روابط» حتمًا.
                    val provenDead = raw?.certain == true
                    if (provenDead || anyAlive) {
                        logD("DramaTip: drop expired ${c.label} ${c.url.take(70)}" +
                            " عمر=${tokenRemainingSeconds(c.url)}s why=${raw?.why ?: "-"}" +
                            (if (provenDead) " (موتٌ مُثبت)" else " (بديلٌ حيّ موجود)"))
                        continue
                    }
                    logD("DramaTip: last-resort expired ${c.label} ${c.url.take(70)}" +
                        " عمر=${tokenRemainingSeconds(c.url)}s why=${raw?.why ?: "-"}" +
                        " (لا حيَّ أصلًا — بثُّه خيرٌ من «لا روابط»)")
                }
                if (tokenExpired(c.url, raw) && raw?.alive == true) {
                    logD("DramaTip: keep re-minted ${c.label} عمر=${tokenRemainingSeconds(c.url)}s" +
                        " (المسبار أثبت الحياة بعد تحويل) ${c.url.take(70)}")
                }
                // الإسقاط للرابط المُثبت موته فقط (كود حالةٍ أو علامةٌ في جسم البروكسي أو
                // شهادة TLS مرفوضة أو مضيفٌ لا يُحلّ بعد إعادة محاولةٍ صريحة). أمّا
                // تعذّر الاتصال (مهلة أو انقطاع) فلا يُسقط الرابط — يُبثّ في الآخر.
                // ★ وفي إرخاء خطّ النجاة (lenient=true) يُبثّ ما **تعذّر الحكم
                // عليه وحدَه**: رمزٌ محكومٌ بموته (منتهٍ أو ناقص التوقيع أو قائمةٌ
                // مزيفة) يبقى ميتًا فلا يُبثّ، لأنّ بثّه يظهر للمستخدم خطأ 2004
                // (BAD_HTTP_STATUS) أو 3002 — وهي الأخطاء التي جاء الإصلاح لأجلها.
                // ★ 2026-10-18. صار الشرط صنفًا موجبًا (`!certain`) لا استثناءً من
                // قائمة: `wrongToken`/`fakePlaylist`/`tokenExpired` كلّها أحكامٌ
                // قاطعة (‎certain=true‎) فتشملها `definiteDeath` تلقائيًّا، وليس
                // بقيّةُ الأحكام القاطعة وحدها هو ما كان يفلت.
                noteCertain(raw)   // حكم المسبار الأصلي، قبل أيّ إرخاء
                val v = if (lenient && raw != null && !raw.alive && !definiteDeath(raw)) raw.copy(alive = true) else raw
                if (v != null && !v.alive && v.certain) {
                    noteCertain(v)
                    logD("DramaTip: drop dead ${c.label} ${c.url.take(70)} why=${v.why}")
                    continue
                }
                val cannotTell = v != null && !v.alive
                val why = v?.why
                if (cannotTell) logD("DramaTip: cannot-tell ${c.label} ${c.url.take(70)} why=$why")
                // ★ 2026-10-17. **ما يُبثَّ هو وجهة التحويل إن وُجدت، لا الأصل.**
                //
                // المسبار يتبع التحويل ويقرأ جسم الوجهة، فحكمه «حيّ» يخصّها. وكان
                // الأصل المنتهي هو ما يُبثَّ (الوجهة مكشوفة في `Content-Location`
                // لا في الرابط)، فيُطلب من اللاعب أن يعيد متابعة التحويل على
                // شبكته — وقِيس على الشبكات البطيئة أنّ ذلك يسقط فتظهر «لا روابط»
                // على محتوىٍ يعمل (JoyReels بالتحديد: 302 → رمزٌ طازج). فبثُّ
                // الطازج مباشرةً يقطع تلك المرحلة كلّها، وحين لا يتحوّل لا يتغيّر
                // شيء إطلاقًا (المنصّات السواها كلّها بلا تحويل).
                val playUrl = v?.finalUrl?.takeIf { it.isNotBlank() } ?: c.url
                if (playUrl != c.url) {
                    logD("DramaTip: redirect-resolved ${c.label} → ${playUrl.take(90)}" +
                        " (الأصل منتهٍ/يحتاج تحويلًا)")
                }
                val (kind, declared) = classify(playUrl, c.type, v?.contentType.orEmpty(), v?.body)
                if (!emitted.add(playUrl)) continue
                // ★ 2026-10-17. **القائمة المتعدّدة الجودات تُقدَّم أوّلًا.**
                //
                // القائمة التي تحمل `#EXT-X-STREAM-INF` هي القائمة `master`
                // المتكيّفة: يختار المشغّل منها الجودة بنفسه حسب الشبكة لحظةً
                // بلحظة. ورابط الجودة الثابتة لا يتكيّف، فإن دخل شبكةً أضعف من
                // جوْدته تقطّعَ — وهذا أصل التقطّع. فتقديم المتكيّفة يجعله
                // الاختيار الافتراضي فيسلك المشغّل مسار التكيّف.
                //
                // والترتيب في التطبيق **نزوليّ بالأولويّة**، والأولويّة تُبنى في
                // `QualityDataHelper.getLinkPriority` على الجودة ثم المصدر. وجميع
                // روابطنا مصدرُها واحد (فتُلغى حصّة المصدر)، فالجودة وحدها تحكم.
                // وقُرئت الأولويّات من enum `Qualities` في عبوة التطبيق (الوسيط
                // الرابع للمُنشئ): ‎P144=0، P240=2، P360=3، P480=4، P720=5،
                // P1080=6، P1440=7، P2160=8‎، أمّا `Unknown` فـ**٤** — أي طبقة
                // ‎480p‎. فالوصم صريحًا هو ما يحكم، لا غيابه.
                // ومن هنا كان الخلل: القائمة المتكيّفة كانت تُوسم بجودةٍ من سلسلة
                // الوصفة (‎· 720p‎) فتنزل إلى أولويّة ٥، فيسبقها ثابتٌ بجودة 1080
                // (٦) أو 2160 (٨) فيتصدّر الثابتُ **الذي لا يتكيّف** — فيظهر
                // التقطّع. فالآن تُوسم المتكيّفة بأقصى دقّةٍ تُعلنها (فتصل إلى
                // الطبقة العليا)، وتُبثّ **أولًا**، فيفوز تساوي الأولويّة بترتيب
                // الإضافة؛ والأبناء يلحقون بها بأجوادهم الحقيقية كخطّ نجاة.
                val hasStreamInf = v?.body?.contains("#EXT-X-STREAM-INF", ignoreCase = true) == true
                val adaptive = kind == ExtractorLinkType.M3U8 && !cannotTell && hasStreamInf
                // أقصى دقّة في القائمة المتكيّفة — تُوسم بها كي تصل إلى طبقة
                // الأولويّة العليا فتتصدّر (المتكيّفة **تستطيع** أن تُسلّم هذه
                // الدقّة، فالوسم صادق)، والأبناء يحملون دقّاتهم الحقيقية تحتها.
                val maxRes = if (adaptive) {
                    Regex("""RESOLUTION=(\d{3,4})x(\d{3,4})""").findAll(v!!.body.orEmpty())
                        .map { minOf(it.groupValues[1].toIntOrNull() ?: 0, it.groupValues[2].toIntOrNull() ?: 0) }
                        .filter { it > 0 }.maxOrNull()
                } else null
                addLink(
                    if (cannotTell) deferred else collected,
                    if (adaptive) "$source · متعدد الجودات" else c.label,
                    playUrl,
                    if (adaptive) maxRes?.let { "${it}p" } else c.qLabel,
                    kind, declared
                )
                if (cannotTell || kind != ExtractorLinkType.M3U8) continue
                val body = v?.body ?: continue
                // ★ والقاعدة كما ينصّ RFC 3986 لا `base + "/" + l` — التفصيل
                // في تعليق [resolveHlsChild] (خلل iDrama: مسارٌ مضاعف ⇒ 403).
                var inf: String? = null
                for (raw in body.replace("\u0000", "").split('\n')) {
                    val l = raw.trim()
                    if (l.startsWith("#EXT-X-STREAM-INF:")) {
                        inf = l.removePrefix("#EXT-X-STREAM-INF:")
                    } else if (inf != null && l.isNotBlank() && !l.startsWith("#")) {
                        val q = resolutionLabel(inf)
                        variants.add(Var(c.label + (q?.let { " · $it" } ?: ""), resolveHlsChild(playUrl, l), q))
                        inf = null
                    }
                }
            }

            // ٤) صيغ الـmaster: موجة ثانية بالتوازي أيضًا، ثم بثّها.
            //
            // ★ 2026-10-17. **ترتيب: المتكيّفة أوّلًا ثم الأبناء الثوابت.**
            // الأبناء ثوابت الجودة لا تتكيّف، فإن دخلت شبكةً أضعف تقطّعت — فيجب
            // أن تأتي **بعد** المتكيّفة لا قبلها، مع بقائها خطّ نجاةً إن ماتت
            // الأولى. والترتيب في التطبيق نزوليّ بالأولويّة (انظر التعليق في
            // دورة البثّ): فالأبناء يحملون جودةً حقيقية (٢–٨) فتقع تحت المتكيّفة
            // التي لا جودة لها (400).
            if (variants.isNotEmpty()) {
                val ok = coroutineScope {
                    variants.map { v -> async(Dispatchers.IO) { probe.alive(v.url) } }.awaitAll()
                }
                for ((i, v) in variants.withIndex()) {
                    if (ok[i] && emitted.add(v.url)) addLink(fallbacks, v.label, v.url, v.qLabel, ExtractorLinkType.M3U8, null)
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
            collected.addAll(fallbacks)
            val order = prefs?.getString(DramaTipSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
            var ordered = when (order) {
                "asc" -> collected.sortedBy { it.quality }
                "desc" -> collected.sortedByDescending { it.quality }
                else -> collected
            }
            // ★ DramaBox: متعدد الجودات أولاً، ثم أفضل جودة أولًا
            if (source.equals("DramaBox", ignoreCase = true) || source.contains("DramaBox", ignoreCase = true)) {
                ordered = ordered.sortedWith(compareByDescending<ExtractorLink> {
                    it.name.contains("متعدد الجودات")
                }.thenByDescending { it.quality })
            }

            if (ordered.isNotEmpty()) {
                ordered.forEach { callback(it) }
                // ★ رصدٌ لعمر أول رابط مُبثّ: هذا الرقم هو ما يفسّر «Source error»
                // بعد الإسقاط. قيمته السالبة (مثل ‎-7533s‎) قاطعة بأن رمزًا ميتًا
                // بُثّ فعلًا رغم كل الفحص — وهي أهمّ ما يُقرأ في التقاط المستخدم.
                val firstLife = ordered.firstOrNull { it.url.startsWith("http") }
                    ?.let { tokenRemainingSeconds(it.url) }
                // ★ 2026-10-18. ويُلحق بالموتِ المُثبت عدده وأسبابه: إن ظهر داخل
                // `video=` رابطٌ من هذه الأسباب فالخلل في البوّابة، وإن خلا منها
                // فالإسقاط سليم والعلّة في الشبكة أو في الرمز البائت.
                val deaths = certainReasons.entries.joinToString(",") { "${it.key}=${it.value}" }
                logD("DramaTip.loadLinks $source ep=$ep video=${ordered.size} sub=$sentSub" +
                    (firstLife?.let { " توكن=${it}s" } ?: "") +
                    " موت-مثبت=[${deaths.ifBlank { "-" }}]")
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
        // ★★ 2026-10-18. **جذرٌ مُثبتٌ لعطب Petadrama، وخلافٌ بين المسبار والرابط.**
        //
        // كان البثّ يمرّر `reqHeaders()` — وهي تحمل `Referer` **ثابتًا** على
        // `https://dramatip.net/ar` — بينما المسبار (`Probe.one`) يفحص بـ
        // `platformReferer(url)`، أي مرجع المنصّة نفسها. فالفحص يقول «حيّ» عن
        // مرجعٍ لا يطلبه اللاعب أبدًا، والرابط يُبثّ بمرجعٍ آخر يُرفض في الشوط
        // الثاني: أي أنّ بوابة القبول تُخترَق بخلافٍ داخليّ لا بعطبٍ في الشبكة.
        //
        // وقِيس 2026-10-18 على ٢٤ منصّة (٣ حلقاتٍ لكلٍّ، ٢٧ زوج مضيف×مرجع، بلا
        // رأس Range أي بشكل طلب اللاعب حرفيًّا):
        //   - `cfcdn.petadrama.com` ←‏ **403** مع `dramatip.net/ar` و**200** مع
        //     `https://petadrama.com/` (مرجع المنصّة). الفرق قاطع ومتكرّر.
        //   - ولا مضيفَ واحد **يشترط** `dramatip.net/ar` (المجموعة المقابلة صفر).
        //     ونقاط النارتو (`stream-e1`/`bibishort-e2` على narto-drama.com)
        //     تقبله لأنّ `platformReferer` تُعيده صريحًا لمسارات `/e/m/` و`/e/s/`.
        // فصار المرجع مرجعَ المنصّة، وهو **نفس ما يفحصه المسبار** — فلا يبقى
        // اختلافٌ بين ما يُفحص وما يُبثّ. وهذا إصلاحٌ محض: لم يُسقط مضيفًا واحدًا
        // ممّا كان يعمل (المقاس أعلاه).
        val headers = reqHeaders().toMutableMap()
        headers["Referer"] = platformReferer(url)
        collected.add(newExtractorLink(name, tagged, url, kind) {
            this.headers = headers
            if (!qLabel.isNullOrBlank()) {
                val q = getQualityFromName(qLabel)
                if (q > 0) this.quality = q
            }
        })
    }
}