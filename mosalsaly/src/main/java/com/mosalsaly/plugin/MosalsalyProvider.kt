package com.mosalsaly.plugin

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val mosMapper = ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val TAG = "Mosalsaly"

private const val MOS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val REEL_MAIN = "https://www.reelshort.com"

private const val GOOD_BASE = "https://goodshort.goodbos.online/hls"

// مفتاح AES-GCM الثابت للموقع لتفكيك مصادر الحلقات من /api/episode-source
// خوارزمية: base64decode(enc) → iv = أول 12 بايت، ciphertext = الباقي، ثم AES/GCM/NoPadding
private const val MOS_EP_KEY_B64 = "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc="
private val MOS_EP_KEY: ByteArray by lazy {
    try { Base64.getDecoder().decode(MOS_EP_KEY_B64) } catch (e: Exception) { MOS_EP_KEY_B64.toByteArray() }
}

// فك تشفير حقل enc من واصف حلقة mosalsaly (AES-GCM، static key كما في JS chunk 2f0jsiav2q67u)
private fun decryptMosEnc(enc: String): String? {
    return try {
        val raw = Base64.getDecoder().decode(enc)
        if (raw.size <= 12) return null
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(MOS_EP_KEY, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "decryptMosEnc fail ${e.message}")
        null
    }
}

// تنظيف رابط تم فك تشفيره من واصف (بعض الحقول تأتي بخلفية/مهربات)
private fun cleanDecryptedUrl(u: String?): String? {
    if (u.isNullOrBlank()) return null
    return u.trim()
        .replace("\\u0026", "&").replace("\\u003c", "<").replace("\\u003e", ">")
        .replace("\\/", "/")
        .takeIf { it.startsWith("http") || it.startsWith("https") }
}

/**
 * رقم الجودة من تسمية encByQuality، وتأتي بأشكال متباينة: "1080" و"1080p" و"720 HD".
 * CloudStream يبني قائمة الجودة من الحقل `quality` (Int)، وgetQualityFromName يقرأ
 * الرقم من الاسم بعد حذف "p" — فالاسم رقم خام يمرّ أيضاً. لكن petadrama ترسل
 * "default" (قائمة أم لا جودة) ونقرأها 144p فيظهر كجودة وهمية — فنُسقط اللفظيات
 * بلا رقم، ونقرّب ما تبقّى إلى اسم قياسي.
 */
private fun qualityOfLabel(label: String): Int {
    val digits = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()
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

/**
 * مسارات الصوت البديلة من قائمة تشغيل HLS: EXT-X-MEDIA:TYPE=AUDIO.
 *
 * القوائم الأم (master) تحمل بجوار جوداتها مسارات الصوت: EXT-X-MEDIA:TYPE=AUDIO…URI="a.m3u8"
 * نقرؤها ونمرّرها في ExtractorLink.audioTracks فيظهر اختيار الصوت في المشغّل.
 * لا يُقرأ شيء إن لم تكن القائمة أم أو لم تحمل صوتاً — وقِسنا أن موقع Mosalsaly
 * لا يرسل أي مسار صوت في واصف الحلقة إطلاقاً، فهذه هي الطريق الوحيد.
 */
private suspend fun audioTracksOf(masterUrl: String, mainUrl: String): List<AudioFile> {
    val text = try {
        app.get(masterUrl, headers = mapOf("User-Agent" to MOS_UA, "Referer" to mainUrl), referer = mainUrl).text
    } catch (e: Exception) {
        Log.w(TAG, "audioTracks fetch fail ${e.message}"); return emptyList()
    }
    if (!text.trimStart().startsWith("#EXT")) return emptyList()
    val out = mutableListOf<AudioFile>()
    val seen = HashSet<String>()
    val re = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^>]*?NAME="([^"]*)"[^>]*?URI="([^"]+)"""")
    for (m in re.findAll(text)) {
        val name = m.groupValues[1].trim()
        var uri = m.groupValues[2].trim()
        if (uri.isBlank()) continue
        if (!uri.startsWith("http")) uri = masterUrl.substringBeforeLast('/') + "/" + uri
        // same name twice = duplicate entry in the playlist — drop it
        if (!seen.add(name.ifBlank { uri })) continue
        out.add(newAudioFile(uri) {
            this.headers = mapOf("User-Agent" to MOS_UA, "Referer" to mainUrl)
        })
        Log.i(TAG, "audio track: $name")
    }
    return out
}

/**
 * اسم وسيلة التشغيل كما ستراها في التطبيق: mp4 / m3u8 / mpd / أخرى.
 *
 * الغرض تمييز المنصات صنفاً كما طلب المستخدم: «ميز بين المنصات الذي تظهر الفيديو
 * mp4 والمنصات التي تظهر الفيديو m3u8». الرابط وحده لا يفصح عن نوعه في قائمة
 * CloudStream — كل سطر اسمه «<platform> <serial>» — فنضيف النوع في الاسم ونهتف به
 * في logcat. الرابط المباشر .mp4 بلا قطع (وهو ما تحمله أغلب المنصات) → mp4؛
 * قائمة تشغيل #EXTM3U أو امتداد .m3u8/.m3u → m3u8؛ XML داش → mpd.
 */
private fun mediaKindName(url: String, type: ExtractorLinkType?): String = when {
    type == ExtractorLinkType.M3U8 || url.contains(".m3u8", true) || url.contains(".m3u", true) -> "m3u8"
    type == ExtractorLinkType.DASH || url.contains(".mpd", true) -> "mpd"
    type == ExtractorLinkType.TORRENT || url.endsWith(".torrent", true) -> "torrent"
    type == ExtractorLinkType.VIDEO || url.contains(".mp4", true) || url.contains(".m4v", true) ||
        url.contains("videoplayback", true) -> "mp4"
    else -> "video"
}

// عندما يعيد mosalsaly back-end رابط ترجمة بـ auth_key قديم/منتهي الصلاحية (تركتُ
// netshort يتأثر: sub auth يجلس أياماً قديماً بينما auth الفيديو طازج → الترجمة 403)،
// نستبدل auth الترجمة بـ auth الفيديو الأساسي الطازج — auth_key على هذه الأقراص عام لكل
// المسارات على نفس المضيف (تحقق: sub path + video auth → 200 WEBVTT).
// NetsShort: جلب اللاعب المباشر لروابط ns-aws-cdn / dizi1 يموت على الجهاز عبر Cronet (HTTP/2)
// لنفس الرابط الذي يرد 200/206 عبر HTTP/1.1 — نمرّر عبر خادم محلي (MosSubServer) يجلب الجسم
// من المصدر بـ HttpURLConnection (HTTP/1.1 + Range) ويقدّمه من 127.0.0.1 بلا أي CDN في مسار
// اللاعب، فيستحيل الـ 403/timeout الذي كان مع Cronet. محصور في netshort فقط — البقية مباشرة.
//
// دفع ملاحظة: في v23 جرّبنا بروكسي الموقع الأصلي dizi1.dramadizilerim.com/?url= للفيديو
// والترجمة — خادمياً كان يرد 200 (WebVTT/206 mp4)، لكن جلب اللاعب له عبر Cronet يموت أيضاً
// ('Source error') والترجمة تصل بنوع MIME خاطئ (حيث يحدد Minecraft MIME من نهاية الرابط الذي
// فيه ?url=... بلا امتداد → يُحسب SRT رغم أن الخادم يرسل WebVTT) — فلا الترتيب يعمل ولا الترجمة.
// لذلك نستبدله كلياً بالخادم المحلي الذي يقدّم mp4/vtt من 127.0.0.1$id.(mp4|vtt) مع MIME صحيح.
private fun routeVideo(platform: String, kind: ExtractorLinkType, url: String, headers: Map<String, String>): String {
    if (platform != "netshort" || kind != ExtractorLinkType.VIDEO) return url
    val local = MosSubServer.registerVideo(url, headers)
    if (local != null) Log.i(TAG, "netshort video via local server")
    else Log.w(TAG, "netshort local server unavailable, keeping direct $url")
    return local ?: url
}

private fun refreshSubtitleAuth(subUrl: String, videoUrl: String?): String {
    if (videoUrl.isNullOrBlank()) return subUrl
    val subHost = Regex("https://([^/]+)").find(subUrl)?.groupValues?.get(1) ?: return subUrl
    val videoHost = Regex("https://([^/]+)").find(videoUrl)?.groupValues?.get(1) ?: return subUrl
    // كلا الرابطين على نفس CDN netshort (auth عام فقط هناك) — الاحتياط الأمان: لا نلمس غيره
    if (subHost != videoHost || !subUrl.contains("auth_key") || !videoUrl.contains("auth_key")) return subUrl
    val subTs = Regex("auth_key=(\\d+)").find(subUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return subUrl
    val videoTs = Regex("auth_key=(\\d+)").find(videoUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return subUrl
    if (subTs >= videoTs) return subUrl   // ترجمة لا تزال أعذب/مثل الفيديو → أبقِها
    // استبدل رقم auth ومنتصف القيمة (توقيع md5 متبوع بشروط) بنفس منقسم القيمة الطازج كاملاً
    return subUrl.replace(Regex("auth_key=[^&\\s]+"), "auth_key=" +
        Regex("auth_key=([^&\\s]+)").find(videoUrl)!!.groupValues[1])
}

// المنصات الثلاث والعشرون (كما في الصفحة الرئيسية للموقع) — كلها قابلة للتشغيل
// عبر /api/episode-source الموحّد. القائمة مقروءة من /masdar/ في الموقع لا من تخميننا:
// bibishort/bilitv/idrama/pinedrama/playlet أُضيفت بعد ظهورها هناك.
// PLATFORM_BY_DISPLAY و homeSections مشتقّتان من هذه القائمة فيتّسعان معها تلقائياً.
internal val PLATFORMS = listOf(
    "bibishort" to "BibiShort",
    "bilitv" to "BiliTV",
    "dotdrama" to "DotDrama",
    "dramabite" to "DramaBite",
    "dramabox" to "DramaBox",
    "flickreels" to "FlickReels",
    "goodshort" to "GoodShort",
    "happyshort" to "HappyShort",
    "idrama" to "IDrama",
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

// خريطة اسم العرض -> slug المنصة (لصفحات RSC التي تُحمَّل المصدر lazily عبر <template>
// فلا يظهر صف «المصدر» inline، بل يتجسّد في جملة الوصف "ومصدره منصة KalosTV").
private val PLATFORM_BY_DISPLAY: Map<String, String> by lazy {
    PLATFORMS.associateBy({ it.second }, { it.first })
}

// أقسام إضافية من /tasnif/ (التصنيفات) — تُعرض أولاً في الواجهة الرئيسية
private val EXTRA_SECTIONS = listOf(
    "populer" to "⭐ الأكثر شعبية",
    "newly-added" to "🆕 أحدث الإضافات",
)

class MosalsalyProvider(
    private val prefs: android.content.SharedPreferences? = null
) : MainAPI() {
    override var name = "Mosalsaly"
    override var mainUrl = "https://mosalsaly.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // الأقسام الرئيسية: الأكثر شعبية وأحدث الإضافات أولاً، ثم المنصات
    // (إعداد «أقسام الواجهة» يتحكم بإظهار/إخفاء EXTRA_SECTIONS)
    private val homeSections: List<Pair<String, String>> by lazy {
        val extras = if (MosalsalySettings.showExtra(prefs)) EXTRA_SECTIONS else emptyList()
        val platforms = PLATFORMS.filter { MosalsalySettings.isPlatformEnabled(prefs, it.first) }
        // لا تدع الواجهة تفرغ حتى لو عطّل المستخدم كل المنصات — نعيد الافتراضي
        if (extras.isEmpty() && platforms.isEmpty()) EXTRA_SECTIONS + PLATFORMS else extras + platforms
    }

    override val mainPage by lazy {
        val page = mainPageOf(*homeSections.toTypedArray())
        // إحماء مسبق: أول ما تفتح الواجهة نبدأ جلب كل الأقسام في الخلفية، فتلحق
        // CloudStream لاحقاً بنفس الجلب المعلّق (Deferred مشترك) بدل أن تبدأ من الصفر.
        //Launcher يبتلع أكثرها قبل أن يمرّر المستخدم إلى الصفحة أصلاً.
        sectionStore.warm(homeSections.map { it.first })
        page
    }

    // بطاقات قسم واحد: جلب + تحليل. تُستدعى من SectionStore (متزامن أو خلفي)
    private suspend fun fetchCards(url: String): List<SearchResponse> {
        // محاولة واحدة لا ثلاثاً: هذه صفحات فهرسة لا بيانات حسّاسة. ثلاث محاولات ×
        // مهلة CloudStream = قفلة طويلة على قسم بطيء بينما الخادم يردّ أصلاً ببطء
        // لا بخطأ (قِسنا 8–32 ثانية للإجابة الواحدة).
        val fetched = try { getWithRetry(url, mainUrl, 1, 0) } catch (e: Exception) { null }
        Log.i(TAG, "section fetch ${url.substringAfter("//").take(60)} len=${fetched?.length ?: -1}")
        if (fetched.isNullOrBlank()) return emptyList()
        return parseCards(fetched)
    }

    /**
     * ذاكرة أقسام الواجهة: تعيد ما جلبناه فوراً وتحدّث في الخلفية.
     *
     * قِسنا أن صفحة `/masdar/<platform>` واحدة تاخذ 8–32 ثانية، و`mainPageOf` ينتظر
     * أبطأ قسم من 25 — فكانت الواجهة الرئيسية لا تظهر إلا بعد أبطأها. هنا:
     *  - ضربة واحدة معلّقة لكل قسم (Deferred): الإحماء والطلب يشاركانها فلا
     *    يُطلب القسم مرتين، ولا ينتظر أحدهما الآخر بلا داعٍ.
     *  - طازج (تحت TTL) → ردّ فوري بلا شبكة.
     *  - قديم → ردّ فوري بالقديم والتحديث يجري خلفه.
     *  - بارد → ننتظر، لكن بسقف زمني: ما تجاوزه يُعرض فارغاً هذه المرة ويصل
     *    لاحقاً لأن الجلب الخلفي يستمر (المهلة تلغي الانتظار لا الجلب).
     *  - فشل الجلب لا يُخزَّن أبداً: تخزينه كـ«لا نتائج» يجعل القسم يبقى فارغاً صامتاً.
     */
    private class SectionStore {
        private data class Entry(val items: List<SearchResponse>, val at: Long)
        private val data = ConcurrentHashMap<String, Entry>()
        private val inflight = ConcurrentHashMap<String, Deferred<List<SearchResponse>>>()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /** القسم المطلوب الآن، أو فارغ إن انقضت مهلة الانتظار. */
        suspend fun await(slug: String, fetch: suspend () -> List<SearchResponse>, waitMs: Long): List<SearchResponse> {
            val hit = data[slug]
            val age = hit?.let { System.currentTimeMillis() - it.at } ?: Long.MAX_VALUE
            if (hit != null && age < TTL_MS) {
                Log.i(TAG, "section cache hit $slug n=${hit.items.size} age=${age / 1000}s")
                return hit.items
            }
            var d = inflight[slug]
            if (d == null) {
                // LAZY: لا يبدأ الجلب إطلاقاً إن كان هناك طلب مطابق مسبق فائز
                val nd = scope.async(start = CoroutineStart.LAZY) {
                    val items = fetch()
                    if (items.isNotEmpty()) data[slug] = Entry(items, System.currentTimeMillis()) else data.remove(slug)
                    items
                }
                val prev = inflight.putIfAbsent(slug, nd)
                if (prev == null) {
                    nd.invokeOnCompletion { inflight.remove(slug, nd) }
                    d = nd
                } else {
                    nd.cancel()
                    d = prev
                }
            }
            if (hit != null) {
                // قديم موجود: نعرضه حالاً ونُكمل التحديث خلفه بلا انتظار
                scope.launch { runCatching { d.await() } }
                return hit.items
            }
            return try {
                withTimeoutOrNull(waitMs) { d.await() } ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "section $slug fail ${e.message}")
                emptyList()
            }
        }

        fun warm(slugs: List<String>) {
            for (s in slugs) scope.launch { runCatching { await(s, { emptyList() }, 0) } }
        }

        companion object { const val TTL_MS = 30 * 60 * 1000L }
    }

    private val sectionStore = SectionStore()

    // سقف انتظار قسم واحد. الموقع 8–32 ثانية للقسم الواحد، وCloudStream ينتظر
    // getMainPage شاملاً المهلة — فنمنعه من تجاوز هذا. ما تجاوزه يُعرض فارغاً
    // هذه المرة ويصل لاحقاً، لأن الجلب الخلفي لا يُلغى بالمهلة (بلوغ الذاكرة).
    private val SECTION_WAIT_MS = 18_000L

    // جلب مع إعادة محاولة — الموقع بطيء/unstable؛ نفس نمط ReelShort.
    // كان يبتلع كل استثناء ويُعيد "" — وهو ما جعل فشل الشبكة يبدو كـ«لا نتائج».
    // الآن نسجّل السبب ونُعيد null ليميزه المستدعي عن الفراغ الحقيقي.
    //
    // الانتظار بين المحاولات متصاعد (300 ثم 600 ثم 1200…) لا ثابت: على شبكة
    // الجوّال قِسنا SocketTimeoutException عند أول محاولة مباشرةً — أي أن
    // الطلب نفسه هو البطيء لا المزدحم، فالمحاولة التالية بعد جزء من الثانية
    // كانت تخسر الجولة كلها. ولا ننتظر بعد آخر محاولة: الانتظار بعدها لا
    // يخدم شيئاً ويضيف تأخيراً محسوساً إلى فشل مبكّر.
    private suspend fun getWithRetry(url: String, referer: String?, attempts: Int = 3, backoffMs: Long = 300): String? {
        for (i in 0 until attempts) {
            try {
                val text = app.get(url, headers = mapOf("User-Agent" to MOS_UA), referer = referer).text
                if (text.isNotBlank()) return text
                Log.w(TAG, "attempt $i blank body from $url")
            } catch (e: Exception) {
                Log.w(TAG, "attempt $i failed: ${e.message}")
            }
            if (i < attempts - 1) {
                try { Thread.sleep(backoffMs * (1L shl i)) } catch (e: Exception) {}
            }
        }
        return null
    }

    // البطاقات: <article class="group "><a ... aria-label="Title" href="/mosalsal/slug"><img ... src="POSTER">...
    // الصورة قد تأتي عبر src= (معظم المنصات) أو srcSet= حصراً (كانت بطاقات dramabox تستخدم srcSet
    // في بعض اللقطات) — نلتقط src إن وجد، وإلا أول URL من srcSet.
    // الأسلوب المُقسَّم (article-block): قصّ كل بطاقة بمفردها ثم اقرأ حقولها داخل بلوكها،
    // بدل regex واحد مع [\s\S]*? عبر الصفحة — أكثر مقاومة لتغيّر ترتيب السمات.
    private val articleBlockRe = Regex("""<article class="group ">[\s\S]*?</article>""")

    private fun parseCards(html: String): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = mutableListOf<SearchResponse>()
        val titleRe = Regex("""aria-label="([^"]*)"""")
        val hrefRe = Regex("""href="(/mosalsal/([^"/]*))"""")
        val srcRe = Regex("""\bsrc="(https://[^"]+)"""")
        val srcSetRe = Regex("""\bsrcSet="(https://[^ ]+)""")

        for (art in articleBlockRe.findAll(html)) {
            val block = art.value
            val title = titleRe.find(block)?.groupValues?.get(1) ?: continue
            val slug = hrefRe.find(block)?.groupValues?.get(2) ?: continue
            if (title.isBlank() || slug.isBlank()) continue
            val poster = srcRe.find(block)?.groupValues?.get(1)
                ?: srcSetRe.find(block)?.groupValues?.get(1)
            if (poster.isNullOrBlank()) continue
            val url = "$mainUrl/mosalsal/$slug"
            if (!seen.add(url)) continue
            out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        // أقسام التصنيفات تأتي من /tasnif/ والمنصات من /masdar/
        val isSection = EXTRA_SECTIONS.any { it.first == slug }
        val base = if (isSection) "$mainUrl/tasnif/$slug" else "$mainUrl/masdar/$slug"
        val url = if (page <= 1) base else "$base/page/$page"
        // ترقيم الصفحات (page>1) لا يدخل الذاكرة: هو جلب نقلة منفصل لا قسم رئيسي،
        // ومفتاحه الصفحيات نفس slug يلتبس بالقسم الأول.
        val items = if (page <= 1) {
            sectionStore.await(slug, { fetchCards(url) }, SECTION_WAIT_MS)
        } else {
            fetchCards(url)
        }
        Log.i(TAG, "getMainPage slug=$slug page=$page cards=${items.size}")
        return if (items.isEmpty()) null else newHomePageResponse(request.name, items)
    }

    // hasQuickSearch = true بلا تنفيذ quickSearch كان يجعل صندوق البحث في
    // التطبيق يرمي NotImplementedError بدل أن يبحث، فالافتراضي في MainAPI
    // يرمي ولا يفرض شيئاً. التوجيه هنا يجعله يمرّ إلى search() نفسه.
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().length < 3) return emptyList()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val html = try {
            getWithRetry("$mainUrl/search?q=$q", mainUrl, 3, 300)
        } catch (e: Exception) { null }
        // getWithRetry كان يبتلع كل استثناء ويُعيد "" — جولة فاشلة تظهر للمستخدم
        // كـ«لا نتائج». صار يُعيد null ليميزه عن الفراغ الحقيقي.
        Log.i(TAG, "search '$query' len=${html?.length ?: -1}")
        if (html.isNullOrBlank()) return emptyList()
        return parseCards(html)
    }

    // قراءة كائن TVSeries من JSON-LD (<script type="application/ld+json">) — يعمل للنص غير المهرب
    private fun jsonLdSeries(html: String): Map<String, String?>? {
        val m = Regex(""""@type":\s*"TVSeries"""").find(html) ?: return null
        // نرجع إلى { المفتوح قبل المؤشر ثم نتتبع الأقواس حتى إغلاق الكائن
        val open = html.lastIndexOf('{', m.range.first)
        if (open < 0) return null
        var depth = 0
        for (i in open until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        // استخلاص الحقول المطلوبة من النص الخام للكائن
                        val block = html.substring(open, i + 1)
                        fun field(key: String): String? =
                            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                                .find(block)?.groupValues?.get(1)
                                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                        return mapOf(
                            "name" to field("name"),
                            "image" to field("image"),
                            "description" to field("description"),
                        )
                    }
                }
            }
        }
        return null
    }

    private data class EpInfo(val chapterId: String, val serial: Int, val cover: String?)

    // المصفوفة المهروبة: [...,"$L42",null,{"bookId":"...","episodes":[{...},...],"slug":...]
    // المفاتيح مهربة عادة (\"chapter_id\") لكن بعض الصفحات تكون غير مهربة — نتعامل مع الاثنين
    private fun parseEpisodes(html: String): List<EpInfo> {
        val out = mutableListOf<EpInfo>()
        // نقبل "episodes":[ و \"episodes\":[
        var idx = html.indexOf("\\\"episodes\\\":[")
        if (idx < 0) idx = html.indexOf("\"episodes\":[")
        if (idx < 0) return out
        // مقطع يبدأ عند episodes ويستمر حتى إغلاق المصفوفة — يدعم orders late/early bookId
        var depth = 0
        var closedAt = -1
        var i = idx
        while (i < html.length) {
            val ch = html[i]
            if (ch == '[') depth++
            else if (ch == ']') { depth--; if (depth == 0) { closedAt = i; break } }
            i++
        }
        if (closedAt < 0) return out
        val block = html.substring(idx, closedAt + 1)
        val objRe = Regex("""\{[^{}]*chapter_id[^{}]*\}""")
        for (m in objRe.findAll(block)) {
            val obj = m.value
            // نفس التنظيف في bookId: النص المهرّب يُبقي شرطة مائلة عكسية لاصقة
            val chId = Regex("""(?:\\")?chapter_id(?:\\")?:\s*(?:\\")?([^"\\<>/\s]{1,})(?:\\")?""")
                .find(obj)?.groupValues?.get(1)?.trim('\\', '"', '/') ?: continue
            val ser = Regex("""(?:\\")?serial_number(?:\\")?:\s*(\d+)""")
                .find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            // ReelShort يبدأ serial من 0 — لا نفيلتر هنا
            if (ser < 0) continue
            val cover = Regex("""(?:\\")?cover(?:\\")?:\s*(?:\\")?((?:[^"\\]|\\.)*)""")
                .find(obj)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
            out.add(EpInfo(chId, ser, cover))
        }
        return out.distinctBy { it.serial }.sortedBy { it.serial }
    }

    private fun extractPlatform(html: String): String? {
        // سطر المصدر في التفاصيل: <dt>المصدر</dt><dd><a href="/masdar/<p>">
        // «المصدر» يظهر أولاً في القوائم/الإشعارات أيضاً؛ نطابق التواجد الذي يليه
        // رابط /masdar/ خلال 500 حرف — هذا هو صف المصدر الفعلي (مهرّب أو عادي).
        // بعض المسلسلات (مثل «عودة الوريثة الصغير») تُنشر بصفحة RSC تحمّل صف المصدر
        // lazily عبر <template id="P:..."> فلا يظهر /masdar/ أبداً inline. مصدرها الحقيقي
        // يتجسّد في جملة الوصف "مصدره منصة KalosTV". الاحتياط: إن فشل النمط القديم،
        // نقرأ اسم المنصة من الوصف ونطابقه في خريطة display->slug.
        Regex("""المصدر.{0,500}?/masdar/([a-z]+)""")
            .find(html)?.groupValues?.get(1)?.lowercase()
            ?.let { return it }
        return Regex("""ومصدره منصة\s+([A-Za-z][A-Za-z0-9]*)""")
            .find(html)?.groupValues?.get(1)
            ?.let { PLATFORM_BY_DISPLAY[it] }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("/mosalsal/").substringBefore("?")
        if (slug.isBlank()) return null
        val fetched = try { getWithRetry("$mainUrl/mosalsal/$slug", mainUrl, 4, 400) }
        catch (e: Exception) { null }
        if (fetched.isNullOrBlank()) return null
        val html = fetched

        // meta title h1
        val h1 = Regex("""<h1[^>]*>\s*([^<]{2,})\s*</h1>""").find(html)?.groupValues?.get(1)?.trim()
        val jsonLd = jsonLdSeries(html)
        val title = h1 ?: jsonLd?.get("name") ?: return null
        val cover = jsonLd?.get("image")
        val plot = jsonLd?.get("description")
        val episodes = parseEpisodes(html)
        // bookId قد يكون مهرّباً أو عادياً، وقد يحمل معرّفات لأصيلة (مثل stardust = اسم العمل العربي
        // `حب-بدأ-بكذبة` يحتوي شرطات وأحرف عربية) — نقبل أي مجموعة ما عدا علامات الإغلاق/الهروب.
        // ثم نشيل أي شرطة مائلة عكسية/علامة اقتباس لاصقة: المُهرِّب ينتج
        // bookId\":\"2062073462919725058\" وقد التقط النمط الشرطة فصار المُعرّف
        // 20 خانة لا 19 → طلب /api/episode-source يردّ descriptor=null بلا سبب ظاهر.
        val bookId = Regex("""(?:\\")?bookId(?:\\")?:\s*(?:\\")?([^"\\<>/\s]{3,})(?:\\")?""")
            .find(html)?.groupValues?.get(1)?.trim('\\', '"', '/') ?: return null
        if (episodes.isEmpty()) return null
        val platform = extractPlatform(html)?.lowercase() ?: return null
        Log.i(TAG, "load ok slug=$slug bookId=$bookId platform=$platform eps=${episodes.size}")
        // slug الأصلي من الرابط — أفضل من slug مشتق من العنوان (قد يختلف)
        val encSlug = java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20")

        var index = 0
        val eps = episodes.map { e ->
            index++
            // data: bookId||chapterId||serialRaw||platform||slug
            // البادئة النصية في أول حقل تمنع CloudStream من دمج mainUrl أمام رقم
            val data0 = "id:$bookId||${e.chapterId}||${e.serial}||$platform||$encSlug"
            newEpisode(data0) {
                episode = index
                name = "الحلقة $index"
                this.posterUrl = e.cover
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            this.posterUrl = cover
            this.plot = plot
        }
    }

    private fun cleanM3u8(url: String): String = url
        .replace("\\u0026", "&")
        .replace("\\u003c", "<").replace("\\u003e", ">")

    // جلب واصف الحلقة من API الموقع: /api/episode-source/{bookId}/{serial}?lang=ar&refresh=1
    // يوفّر مصادر مباشرة لكل المنصات (الرابط بعد فك التشفير جاهز للتشغيل).
    // refreshFlag: "1" عادة، أو "0/rand" عند إعداد «تحديث الروابط قسرياً» لتفادي كاش CloudStream.
    private suspend fun fetchEpisodeDescriptor(bookId: String, serial: Int): ObjectNode? {
        val refreshFlag = if (MosalsalySettings.forceRefresh(prefs)) {
            "1&_t=${System.currentTimeMillis()}"   // معرّف عشوائي يكسر ذاكرة التخزين
        } else "1"
        // bookId ليس رقماً في كل المنصات: stardust تُرجع الاسم العربي نفسه
        // («حب-بدأ-بكذبة»). وإرساله بلا ترميز يفشل الطلب، فنرمّزه مع إبقاء
        // «-» كما هو (هو جزء من الاسم لا محرف استثنائي).
        val bid = try {
            java.net.URLEncoder.encode(bookId, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            Log.w(TAG, "bookId encode fail ${e.message}"); return null
        }
        val url = "$mainUrl/api/episode-source/$bid/$serial?lang=ar&refresh=$refreshFlag"
        return try {
            val text = getWithRetry(url, mainUrl, 3, 400)
            if (text.isNullOrBlank()) { Log.w(TAG, "no descriptor text serial=$serial"); return null }
            val node = mosMapper.readTree(text)
            runCatching {
                val d = node.get("descriptor") as? ObjectNode
                val sub = d?.get("subtitle") as? ObjectNode
                val subJson = if (sub != null) sub.toString().take(500) else "null"
                val refresh = d?.get("chain")?.get(0)?.get("refreshAfter")?.asText().orEmpty()
                Log.i(TAG, "SUBINFO refresh=$refresh sub=$subJson")
            }
            node.get("descriptor") as? ObjectNode
        } catch (e: Exception) {
            Log.w(TAG, "descriptor except ${e.message}")
            null
        }
    }

    // تصنيف الرابط بسرعةٍ وموثوقية دون تحميل جسم mp4.
    //    1) mp4/direct (معلن أو بامتداد) → ثقة فورية بـ VIDEO بدون أي تحميل (سلوك v2/v12 الموثوق؛
    //       بعض CDN تتجاهل Range فترسل جسم mp4 كامل، فإرسال طلب body كان يبطئ/يُعلق → "لا توجد روابط").
    //       استثناء: إذا كشف الـ URL علامة HLS (.m3u8/.m3u) رغم declaration mp4 (حالة moboreels) —
    //       نفحص فعليًا النص الصغير #EXTM3U → M3U8.
    //    2) كل الأنواع الأخرى (hls/hls-enc/storyreel/...) → حمل أول جزء فقط (Range صغير)؛ إن كان #EXTM3U
    //       فعمر × M3U8، وإلا استبعاد (404/403/HTML ميت).
    // رابط منتهٍ مؤكد؟ نرفضه قبل العرض متى حمل Expires= صريحاً في الماضي
    // (مثل encByQuality من netshort الذي يبقي mosalsaly linkاً قديماً → على الجهاز 403 حتى مع Referer).
    // «صريحاً» هو الشرط: مقياس الحسم هو الحالة التي يردّ بها الخادم نفسه، لا ساعة داخل الرابط.
    // auth_key لـ netshort يُقارَن بالساعة المحلية بينما صادره خادم Mosalsaly/CDN بساعة
    // تتأخّر — فكان رابط يردّ 200/206 يُحذف لمجرّد أن auth_key أقدم بعشرة أيام.
    // الفحص يسبق العرض، وprobeMedia/probeQualityUrlHttp11 يبقيان الرابط عند 2xx/206
    // فتبقى السليمة وتُسقَط الميتة فعلاً.
    private fun isExplicitlyExpired(url: String): Boolean {
        return Regex("expires=(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { it < System.currentTimeMillis() / 1000 }
            ?: false
    }

    private suspend fun probeMedia(url: String, declaredType: String?): ExtractorLinkType? {
        val idU = url.lowercase()
        val isDirect = declaredType == "mp4" || declaredType == "mpd" || declaredType == "dash" ||
            idU.contains(".mp4") || idU.contains(".m4v") || idU.contains("videoplayback")

        if (isExplicitlyExpired(url)) {
            Log.w(TAG, "probeMedia expires-in-past skip $url")
            return null
        }

        if (isDirect && !idU.contains(".m3u8") && !idU.contains(".m3u")) {
            return ExtractorLinkType.VIDEO
        }

        return try {
            val resp = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to MOS_UA,
                    "Referer" to mainUrl,
                    "Range" to "bytes=0-65535",
                ),
                referer = mainUrl,
            )
            val text = resp.text
            if (text.isNotBlank() && text.trimStart().startsWith("#EXTM3U")) {
                ExtractorLinkType.M3U8
            } else {
                Log.w(TAG, "probeMedia non-media $url text=${text.take(30)}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeMedia skip $url — ${e.message}")
            null
        }
    }

    // فحص جودة بديلة (غير الأساس): netshort يعطي الجودات (720) على awscdn.netshort.com
    // بمضيف مختلف وبـ auth قديم → 403 دائماً. الأساس (ns-aws-cdn) موثوق. نجلب لجودة
    // بديلة رأساً صغيراً (Range) لنبقي الحيّ ونستبعد الميت دون تحميل جسم كامل (وسيلة
    // v13/v15: نحن مطمئنون لأن awscdn يرد 403 بلا body، وns-aws يرد 206 لنفس الطلب).
    // فحص جودة بديلة عبر HTTP/1.1 (java.net.HttpURLConnection) بدل Cronet HTTP/2 —
    // فالـ app.get عبر Cronet يموت 403 على ns-aws لنفس الرابط الذي يرد 200/206 عبر
    // HTTP/1.1 (درس v13/v24). الجودات الحيّة تُظهر، والميتة (awscdn 720 قديم) تُستبعد.
    private fun probeQualityUrlHttp11(url: String): ExtractorLinkType? {
        if (isExplicitlyExpired(url)) {
            Log.w(TAG, "probeQuality expires-in-past skip $url")
            return null
        }
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", MOS_UA)
            conn.setRequestProperty("Referer", mainUrl)
            conn.setRequestProperty("Range", "bytes=0-65535")
            val rc = conn.responseCode
            if (rc in 200..299) {
                val head = conn.inputStream.use { ins ->
                        val buf = ByteArray(65536)
                        val n = ins.read(buf)
                        if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
                    }.trimStart()
                if (head.startsWith("#EXTM3U")) ExtractorLinkType.M3U8
                else ExtractorLinkType.VIDEO
            } else {
                Log.w(TAG, "probeQuality http11 $rc skip ${url.take(90)}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeQuality http11 skip ${url.take(90)} — ${e.message}")
            null
        }
    }

    /** فحص جودة بديلة — يفضل HTTP/1.1 (الموثوق) ثم Cronet كاحتياط. */
    private suspend fun probeQualityUrl(url: String, declaredType: String?): ExtractorLinkType? {
        val via11 = probeQualityUrlHttp11(url)
        if (via11 != null) return via11
        // احتياط: بعض المزوّدين قد يفضّلون Cronet (لا mp4 مباشر). الحفاظ على السلوك السابق.
        return try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to MOS_UA, "Referer" to mainUrl, "Range" to "bytes=0-65535"
            ), referer = mainUrl)
            val text = resp.text
            if (text.isNotBlank() && text.trimStart().startsWith("#EXTM3U")) ExtractorLinkType.M3U8
            else if (text.isNotBlank()) ExtractorLinkType.VIDEO
            else null
        } catch (e: Exception) {
            Log.w(TAG, "probeQuality skip $url — ${e.message}")
            null
        }
    }

    private suspend fun emitDescriptorLinks(
        descriptor: ObjectNode,
        platform: String,
        serial: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val chain = descriptor.get("chain") as? ArrayNode ?: return false
        var emitted = false
        val seen = HashSet<String>()
        // رابط حي يمرّ فحص المحتوى؛ لا يبقى إلا الحيّ وهو مُصنّف بنوعه الصحيح (M3U8/VIDEO)
        data class CLink(
            val url: String,
            val q: String,
            val kind: ExtractorLinkType,
            // متغيّرات قائمة الأم: (تسمية الجودة، الرابط) — تُملأ لقائمة الأم فقط
            val variants: List<Pair<String, String>> = emptyList(),
        )

        val alive = ArrayList<CLink>()
        // رابط الفيديو الأساسي (auth طازج) — يُستخدم لتجديد auth الترجمة عند انتهائه
        var primaryVideoUrl: String? = null
        // netshort: الخادم يضع refreshAfter في واصف الحلقة. إن كان في الماضي فالمصادر
        // المُعادة هي نسخة مُخزَّنة لم تنتهِ صلاحيتها بعد — والفحص يردّ 403 «time expire».
        // نتخطى فحص الشبكة حينها (وهي لن تنجح على أي حال) ونبقي الروابط لتُعرض؛ فشلُ
        // التشغيل عندها أبلغ من إخفاء الرابط، وCloudStream يعرض «أعد المحاولة».
        var descriptorStale = false
        for (item in chain) {
            val ch = item as? ObjectNode ?: continue
            val type = ch.get("type")?.asText()?.lowercase()
            val baseEnc = ch.get("enc")?.asText()
            val baseUrl = if (baseEnc != null) cleanDecryptedUrl(decryptMosEnc(baseEnc)) else null
            if (primaryVideoUrl == null) primaryVideoUrl = baseUrl
            if (ch.get("refresh")?.asBoolean(true) == true) {
                val after = ch.get("refreshAfter")?.asLong(0L) ?: 0L
                if (after > 0 && after * 1000L < System.currentTimeMillis()) descriptorStale = true
            }

            // اجمع المرشحين: الأساس + كل الجودات المتاحة (encByQuality)
            val candidates = LinkedHashMap<String, String>()  // url -> quality label ("" للأساس)
            if (baseUrl != null) candidates[baseUrl] = ""
            val eq = ch.get("encByQuality") as? ObjectNode
            if (eq != null) {
                for ((q, v) in eq.fields()) {
                    val u = cleanDecryptedUrl(decryptMosEnc(v.asText()))
                    if (u != null) candidates[u] = q
                }
            }
            // مفتاح «default» ليس جودة بل قائمة تشغيل أم (master): petadrama يُرجع
            // default=master.m3u8 مع 540/720/1080 بجواره، والأم تحمل الثلاثة داخلها
            // عبر EXT-X-STREAM-INF. نرفع الأم سطراً مستقلاً — ExoPlayer يختار منها
            // تلقائياً ويعرض كل الجودات في قائمة جودة المشغّل — ونبقي المتغيّرات
            // كروابط مستقلة فتبقى الجودات مسمّاة لا مجهولة.
            val masterKey = candidates.keys.firstOrNull { it.equals("default", true) }
            val masterUrl = masterKey?.let { candidates.remove(it) }
            val autoEnc = ch.get("encAuto")?.asText()
            val autoUrl = if (autoEnc != null) cleanDecryptedUrl(decryptMosEnc(autoEnc)) else null

            // نصنّف المحتوى (وليس الثقة بحقل type): #EXTM3U ⇒ M3U8؛ mp4 المعلن/الممتد ⇒ VIDEO.
            // يبعد الروابط الميتة (404/403/HTML) ويصحّح التصنيف الكاذب (moboreels m3u8 كـ mp4 → 3003).
            // التصنيف من امتداد الرابط أيضاً هنا: بعض المنصات تعلن type=hls وتخدم mp4 فعلياً.
            val probeOn = MosalsalySettings.onlyAlive(prefs)
            for ((url, q) in candidates) {
                if (!seen.add(url)) continue

                // إعداد «الجودات»: "high" = الأعلى فقط — يعرض رابطاً واحداً (الأساس غالباً)
                if (MosalsalySettings.qualityMode(prefs) == "high" && q.isNotBlank()) {
                    Log.i(TAG, "qualityMode=high, skipping $platform $q")
                    continue
                }

                val kind = when {
                    // «الروابط الحيّة فقط» مُطفأ: نصنّف نمطياً من الامتداد ونعرض كما وردت
                    // بلا فحص شبكة — أسرع، ويصلح حين يعجز فحصنا عن قراءة CDN.
                    !probeOn -> if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    descriptorStale -> {
                        // نعرف أنها ستُرفض؛ نصنّفها نمطياً بدل انتظار فحصٍ لن ينجح
                        Log.i(TAG, "descriptor stale ($platform $serial) — emit without probe")
                        if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                    q.isNotBlank() -> probeQualityUrl(url, type)
                    else -> probeMedia(url, type)
                }
                if (kind == null) {
                    Log.w(TAG, "skip dead/mismatched $platform url=${url.take(80)}")
                    continue
                }
                alive.add(CLink(url, q, kind))
            }

            // قائمة الأم: نضيفها سطراً مستقلاً «تلقائي» — هي التي تحمل مسارات الصوت
            // (EXT-X-MEDIA:TYPE=AUDIO) فتُمرَّر في audioTracks، ولأن ExoPlayer يقرأ
            // جوداتها من داخلها فيختار الأعلى منها تلقائياً. أمّا المتغيّرات فتبقى
            // سطوراً مستقلة مسمّاة (540/720/1080) للاختيار اليدوي بجانبها.
            val auto = masterUrl ?: autoUrl
            if (auto != null && !seen.contains(auto) && auto.isNotBlank()) {
                // روابط المتغيّرات مفكوكة أصلاً (فُكّت عند بناء candidates) فلا داعي
                // لفكّها ثانية — ولا نريد فكّاً ثانياً يختلف بالنتيجة.
                val variants = candidates.filterValues { it.isNotBlank() }
                    .map { (u, q) -> q to u }
                if (variants.isNotEmpty()) {
                    val autoKind: ExtractorLinkType =
                        if (probeOn && !descriptorStale) (probeMedia(auto, type) ?: ExtractorLinkType.M3U8)
                        else ExtractorLinkType.M3U8
                    alive.add(CLink(auto, "تلقائي", autoKind, variants))
                    Log.i(TAG, "master playlist $platform ${variants.size} variants")
                }
            }
        }

        // الروابط السطحية (mp4) أولًا (أسرع استجابة)، ثم m3u8
        alive.sortBy { it.kind.ordinal != ExtractorLinkType.VIDEO.ordinal }
        // نجمع الروابط ثم نبثّها دفعة واحدة: «الافتراضي» يبثّها بنفس ترتيبها
        // تماماً كما كان، و«تصاعدي/تنازلي» يعيدان ترتيبها فقط
        // (فرز مستقر — المتساوية تحتفظ بترتيبها، ولا حذف ولا تكرار).
        val collected = mutableListOf<ExtractorLink>()
        for (lnk in alive) {
            val kindName = mediaKindName(lnk.url, lnk.kind)
            val label = buildString {
                if (MosalsalySettings.rawLinks(prefs)) {
                    // «الروابط الخام» (تصحيح): التسمية تصبح الرابط نفسه لو فُعلت
                    append(lnk.url.take(120))
                } else {
                    append("$platform $serial")
                    // وسم الوسيلة في الاسم — بلاه كانت كل المنصات تبدو سطراً واحداً
                    // ولم ميّز المستخدم mp4 من m3u8 إلا بالاسم.
                    append(" · $kindName")
                    if (lnk.q.isNotBlank() && lnk.q != lnk.url) append(" · ${lnk.q}")
                }
            }
            val emitUrl = routeVideo(platform, lnk.kind, lnk.url,
                mapOf("User-Agent" to MOS_UA, "Referer" to mainUrl))
            collected.add(newExtractorLink(name, label, emitUrl, lnk.kind) {
                this.headers = mapOf("User-Agent" to MOS_UA, "Referer" to mainUrl)
                // شغّل حقل referer نفسه (وليس فقط headers) — CronetDataSource يبني الطلب
                // من ExtractorLink.referer وليس headers، وCDNs (مثل netshort) ترفض 403
                // عندما يصل الطلب بلا Referer → "Source error" / فشل التشغيل.
                this.referer = mainUrl
                // encByQuality يرسل أرقاماً مجرّدة ("540" و"720" و"1080") في أكثر المنصات،
                // و"720p" في غيرها. getQualityFromName يتوقّف على لاحقة p، فبلاها كانت كل
                // الجودات quality=Unknown فتظهر في التطبيق باسم واحد بلا تمييز ولا ترتيب.
                if (lnk.q.isNotBlank()) this.quality = qualityOfLabel(lnk.q)
                // مسارات الصوت: قائمة الأم تحمّل EXT-X-MEDIA:TYPE=AUDIO بجوار جوداتها،
                // فنقرأها ونمرّرها في audioTracks فيختارها المستخدم من قائمة الصوت
                // داخل المشغّل. وبلاها كان الصوت مفقوداً في المنصات HLS.
                // الواصف نفسه لا يحمل أي حقل صوت (قِسنا 13 منصة: chain/source/subtitle
                // فقط) — فالمصدر الوحيد لهذه المسارات هو قائمة الأم.
                if (lnk.variants.isNotEmpty() && MosalsalySettings.showAudio(prefs)) {
                    val master = audioTracksOf(lnk.url, mainUrl)
                    if (master.isNotEmpty()) this.audioTracks = master
                }
            })
        }
        val order = MosalsalySettings.qualityOrder(prefs)
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
        if (collected.isNotEmpty()) emitted = true

        // ترجمة (اختياري) — فك نفس المفتاح وأرسله كملف ترجمة.
        // إعداد «الترجمات» في الإعدادات يتحكم بإظهارها/إخفائها.
        val sub = descriptor.get("subtitle") as? ObjectNode
        val subEnc = sub?.get("enc")?.asText()
        if (MosalsalySettings.showSubtitles(prefs) && !subEnc.isNullOrBlank()) {
            val subUrl = cleanDecryptedUrl(decryptMosEnc(subEnc))
            if (!subUrl.isNullOrBlank()) {
                try {
                    // netshort: back-end قد يُرجع auth ترجمة منتهي → نستبدله بأحدث auth فيديو
                    // (نفس المضيف، auth عام لكل المسارات) لمّا كانت الترجمة لا تزال في فترة الصلاحية
                    val subUrlActive = refreshSubtitleAuth(subUrl, primaryVideoUrl)
                    if (subUrlActive != subUrl) Log.i(TAG, "refreshed sub auth (stale ${subUrl.take(70)} → ${subUrlActive.take(70)})")
                    val rawLang = sub.get("language")?.asText()?.takeIf { it.isNotBlank() } ?: "ar"
                    // تطبيع كود اللغة: ar_AE/ar-SA/ar_EG → ar (معيار ISO 639-1)
                    val lang = when {
                        rawLang.startsWith("ar", true) -> "ar"
                        rawLang.startsWith("en", true) -> "en"
                        else -> rawLang
                    }
                    // CloudStream يحدد MIME الترجمة من نهاية الرابط (SubtitleHelper.toSubtitleMimeType()).
                    // روابط netshort تنتهي بـ ?auth_key=... → تُقرأ كـ SRT (application/x-subrip)
                    // رغم أن الخادم يرسل WebVTT (text/vtt) → ExoPlayer يحاول فك WebVTT كـ SRT
                    // فتفشل القدرة على إنشاء track نص (EmbeddedSubtitlesFetchedEvent tracks=[])
                    // والترجمة لا تعمل عند تحديدها رغم أن الخادم يرد WEBVTT سليمًا.
                    // العلاج: نُنهي الرابط بـ .vtt (أو .srt حسب format) — بارامتر إضافي بلا قيمة
                    // يتجاهله خادم الأقراص (الكثير من المزوّدين يفعلون هذا) ويجعل اللاعب يتعرف على النوع.
                    val fmt = sub.get("format")?.asText()?.lowercase().orEmpty()
                    var subUrlFixed = when {
                        subUrlActive.endsWith(".vtt", true) || subUrlActive.endsWith(".srt", true) -> subUrlActive
                        fmt.contains("srt") ->
                            if (subUrlActive.contains("?")) "$subUrlActive&.srt" else "$subUrlActive.srt"
                        else ->
                            if (subUrlActive.contains("?")) "$subUrlActive&.vtt" else "$subUrlActive.vtt"
                    }
                    // NetsShort: جلب اللاعب للترجمة من ns-aws-cdn / dizi1 يموت على الجهاز (403/
                    // Source error عبر Cronet) لنفس الرابط الذي يرد 200 خادمياً عبر HTTP/1.1.
                    // نمرّر الترجمة عبر الخادم المحلي (HttpURLConnection — HTTP/1.1) الذي يسلّم
                    // WebVTT من 127.0.0.1 بلا أي CDN في المسار → مستحيل 403، ونوع MIME صحيح
                    // (الرابط ينتهي .vtt فيميته ExoPlayer على أنه text/vtt لا subrip).
                    // باقي المنصات تبقى مباشرة دون تغيير.
                    if (subUrlActive.contains("netshort.com")) {
                        val local = MosSubServer.registerSubtitle(
                            subUrlActive,
                            mapOf("User-Agent" to MOS_UA, "Referer" to mainUrl)
                        )
                        if (local != null) {
                            subUrlFixed = local
                            Log.i(TAG, "netshort sub via local server")
                        } else {
                            Log.w(TAG, "netshort local server unavailable for sub, keeping fixed")
                        }
                    }
                    subtitleCallback(newSubtitleFile(lang, subUrlFixed) {
                        this.headers = mapOf("User-Agent" to MOS_UA, "Referer" to mainUrl)
                    })
                } catch (e: Exception) { Log.w(TAG, "sub emit fail ${e.message}") }
            }
        }
        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p = data.split("||")
        if (p.size < 4) return false
        // أول حقل هو bookId مسبوقاً بـ"id:". وقد يبنيه التطبيق نفسه بصيغة
        // «<mainUrl>/id:<bookId>» لا «id:<bookId>» — وهذا ما نسجّله فعلاً في
        // سطر raw= على الجهاز. فنؤخذ ما بعد «id:» حين يكون الحقل مُلصَقاً،
        // وإلا نعود للقصّ على «/» الذي كان يغطّي الحالة الأولى.
        val bookId = p[0].let { f ->
            val i = f.indexOf("id:")
            if (i >= 0) f.substring(i + 3) else f.substringAfterLast("/")
        }.trim()
        val chapterId = p[1]
        val serial = p[2].toIntOrNull() ?: return false
        val platform = p[3].lowercase()
        Log.i(TAG, "loadLinks platform=$platform bookId=$bookId ch=$chapterId serial=$serial raw=$data")

        // المنصة معطلة في الإعدادات → لا تجلب أصلاً (وقت/طلبان أقل)
        if (!MosalsalySettings.isPlatformEnabled(prefs, platform)) {
            Log.i(TAG, "platform disabled in settings: $platform")
            return false
        }

        // المسار الموحّد عبر /api/episode-source — يخدم كل المنصات الـ 18
        // يعيد واصفاً مشفّراً يُفك بمفتاح AES-GCM الثابت إلى رابط مباشر جاهز
        val descriptor = fetchEpisodeDescriptor(bookId, serial)
        if (descriptor != null) {
            val emitted = emitDescriptorLinks(descriptor, platform, serial, subtitleCallback, callback)
            if (emitted) {
                Log.i(TAG, "episode-source OK platform=$platform serial=$serial")
                return true
            }
            Log.w(TAG, "descriptor present but no links platform=$platform serial=$serial")
        }

        // احتياط: مسار المنصة المحدّدة مباشرة إذا فشل الواصف
        return when (platform) {
            "goodshort" -> {
                // مصدر GoodShort: m3u8 VOD مباشر — جودة واحدة ثابتة 720p (الموقع يتجاهل &q=)
                val m3u8 = "$GOOD_BASE/$chapterId?bookId=$bookId&q=720p"
                try {
                    val master = getWithRetry(m3u8, mainUrl, 4, 400)
                    if (master.isNullOrBlank() || !master.contains("#EXTM3U")) {
                        Log.w(TAG, "goodshort no master bookId=$bookId ch=$chapterId len=${master?.length ?: -1}")
                        return false
                    }
                    callback(newExtractorLink(name, "GoodShort $serial", cleanM3u8(m3u8), ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = getQualityFromName("720p")
                    })
                    Log.i(TAG, "goodshort OK serial=$serial")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "goodshort except ${e.message}")
                    false
                }
            }
            "reelshort" -> {
                // إعادة بناء صفحة الحلقة على ReelShort ثم قراءة video_url من __NEXT_DATA__
                // ReelShort serial_number يبدأ من 0 — p[2] يحمل serial الأصلي
                val slugEnc = if (p.size >= 5) p[4] else ""
                if (slugEnc.isBlank()) {
                    Log.w(TAG, "reelshort blank slug")
                    return false
                }
                val epUrl = "$REEL_MAIN/ar/episodes/episode-$serial-$slugEnc-$bookId-$chapterId"
                val html = try { getWithRetry(epUrl, REEL_MAIN, 5, 400) } catch (e: Exception) {
                    Log.w(TAG, "reelshort fetch except ${e.message}")
                    return false
                }
                if (html.isNullOrBlank()) {
                    Log.w(TAG, "reelshort blank html url=$epUrl")
                    return false
                }
                val root = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*type="application/json"[^>]*>\s*([\s\S]*?)\s*</script>""")
                    .find(html)?.groupValues?.get(1)
                if (root == null) {
                    Log.w(TAG, "reelshort no NEXT_DATA len=${html.length} url=$epUrl")
                    return false
                }
                val videoUrl = Regex(""""video_url":\s*"([^"]+)"""").find(root)?.groupValues?.get(1)?.let {
                    cleanM3u8(if (it.startsWith("http")) it else "https:$it")
                }
                if (videoUrl == null) {
                    Log.w(TAG, "reelshort no video_url root=${root.length}")
                    return false
                }
                callback(newExtractorLink(name, "ReelShort $serial ($bookId)", videoUrl, ExtractorLinkType.M3U8) {
                    referer = REEL_MAIN
                    quality = getQualityFromName("720p")
                })
                Log.i(TAG, "reelshort OK serial=$serial")
                true
            }
            else -> {
                // المنصّات خارج goodshort/reelshort ترسل رابطاً مشفّراً (enc) لا رابطاً
                // يمكن قراءته — ومفتاح فكّه على خادم Mosalsaly وغير منشور، فلا سبيل لتحويله هنا.
                // نسجّلها صراحةً ليعرف المستخدم أن المشكلة في المنصّة لا في الشبكة.
                Log.w(TAG, "platform '$platform' not supported (encrypted descriptor, no public resolver) serial=$serial")
                false
            }
        }
    }
}