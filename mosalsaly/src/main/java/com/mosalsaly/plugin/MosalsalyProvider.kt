package com.mosalsaly.plugin

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64
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

// المنصات الثمانية عشر (كما في /sources) — كلها قابلة للتشغيل عبر /api/episode-source
private val PLATFORMS = listOf(
    "goodshort" to "GoodShort",
    "reelshort" to "Reelshort",
    "dotdrama" to "DotDrama",
    "dramabite" to "DramaBite",
    "dramabox" to "DramaBox",
    "flickreels" to "FlickReels",
    "happyshort" to "HappyShort",
    "joyreels" to "JoyReels",
    "kalostv" to "KalosTV",
    "moboreels" to "MoboReels",
    "moreshort" to "MoreShort",
    "mydramawave" to "MyDramaWave",
    "netshort" to "NetShort",
    "petadrama" to "PetaDrama",
    "shorttv" to "ShortTV",
    "shortwave" to "ShortWave",
    "stardust" to "Stardust",
    "storyreel" to "StoryReel",
)

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

    // الأقسام الرئيسية: الأكثر شعبية وأحدث الإضافات أولاً، ثم المنصات الثمانية عشر
    // (إعداد «أقسام الواجهة» يتحكم بإظهار/إخفاء EXTRA_SECTIONS)
    private val homeSections: List<Pair<String, String>> by lazy {
        val extras = if (MosalsalySettings.showExtra(prefs)) EXTRA_SECTIONS else emptyList()
        val platforms = PLATFORMS.filter { MosalsalySettings.isPlatformEnabled(prefs, it.first) }
        // لا تدع الواجهة تفرغ حتى لو عطّل المستخدم كل المنصات — نعيد الافتراضي
        if (extras.isEmpty() && platforms.isEmpty()) EXTRA_SECTIONS + PLATFORMS else extras + platforms
    }

    override val mainPage by lazy { mainPageOf(*homeSections.toTypedArray()) }

    // جلب مع إعادة محاولة — الموقع بطيء/unstable؛ نفس نمط ReelShort
    private suspend fun getWithRetry(url: String, referer: String?, attempts: Int = 3, backoffMs: Long = 300): String {
        var last = ""
        for (i in 0 until attempts) {
            try {
                val text = app.get(url, headers = mapOf("User-Agent" to MOS_UA), referer = referer).text
                if (text.isNotBlank()) return text
            } catch (e: Exception) { last = "" }
            try { Thread.sleep(backoffMs) } catch (e: Exception) {}
        }
        return last
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
        val html = try { getWithRetry(url, mainUrl, 3, 300) } catch (e: Exception) { "" }
        Log.i(TAG, "getMainPage slug=$slug len=${html.length}")
        if (html.isEmpty()) return null
        val items = parseCards(html)
        Log.i(TAG, "getMainPage slug=$slug cards=${items.size}")
        return if (items.isEmpty()) null else newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.trim().length < 3) return emptyList()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val html = try {
            getWithRetry("$mainUrl/search?q=$q", mainUrl, 3, 300)
        } catch (e: Exception) { return emptyList() }
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
            val chId = Regex("""(?:\\")?chapter_id(?:\\")?:\s*(?:\\")?([^"\\<>/\s]{1,})(?:\\")?""")
                .find(obj)?.groupValues?.get(1) ?: continue
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
        // رابط /masdar/ خلال 500 حرف — هذا هو صف المصدر الفعلي (مهرّب أو عادي)
        return Regex("""المصدر.{0,500}?/masdar/([a-z]+)""")
            .find(html)?.groupValues?.get(1)?.lowercase()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("/mosalsal/").substringBefore("?")
        if (slug.isBlank()) return null
        val html = try { getWithRetry("$mainUrl/mosalsal/$slug", mainUrl, 4, 400) }
        catch (e: Exception) { return null }
        if (html.isEmpty()) return null

        // meta title h1
        val h1 = Regex("""<h1[^>]*>\s*([^<]{2,})\s*</h1>""").find(html)?.groupValues?.get(1)?.trim()
        val jsonLd = jsonLdSeries(html)
        val title = h1 ?: jsonLd?.get("name") ?: return null
        val cover = jsonLd?.get("image")
        val plot = jsonLd?.get("description")
        val episodes = parseEpisodes(html)
        // bookId قد يكون مهرّباً أو عادياً، وقد يحمل معرّفات لأصيلة (مثل stardust = اسم العمل العربي
        // `حب-بدأ-بكذبة` يحتوي شرطات وأحرف عربية) — نقبل أي مجموعة ما عدا علامات الإغلاق/الهروب.
        val bookId = Regex("""(?:\\")?bookId(?:\\")?:\s*(?:\\")?([^"\\<>/\s]{3,})(?:\\")?""")
            .find(html)?.groupValues?.get(1) ?: return null
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
        val url = "$mainUrl/api/episode-source/$bookId/$serial?lang=ar&refresh=$refreshFlag"
        return try {
            val text = getWithRetry(url, mainUrl, 3, 400)
            if (text.isBlank()) { Log.w(TAG, "no descriptor text serial=$serial"); return null }
            val node = mosMapper.readTree(text)
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
    // رابط منتهٍ مؤكداً؟ نرفضه قبل العرض متى حمل Expires= صريحاً في الماضي
    // (مثل encByQuality من netshort الذي يبقي mosalsaly linkاً قديماً → على الجهاز 403 حتى مع Referer).
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
    private suspend fun probeQualityUrl(url: String, declaredType: String?): ExtractorLinkType? {
        val idU = url.lowercase()
        if (isExplicitlyExpired(url)) {
            Log.w(TAG, "probeQuality expires-in-past skip $url")
            return null
        }
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
        // رابط حي يمرّ فحص المحتوى؛ llega فقط الحيّ وهو مُصنّف بنوعه الصحيح (M3U8/VIDEO)
        data class CLink(val url: String, val q: String, val kind: ExtractorLinkType)

        val alive = ArrayList<CLink>()
        // رابط الفيديو الأساسي (auth طازج) — يُستخدم لتجديد auth الترجمة عند انتهائه
        var primaryVideoUrl: String? = null
        for (item in chain) {
            val ch = item as? ObjectNode ?: continue
            val type = ch.get("type")?.asText()?.lowercase()
            val baseEnc = ch.get("enc")?.asText()
            val baseUrl = if (baseEnc != null) cleanDecryptedUrl(decryptMosEnc(baseEnc)) else null
            if (primaryVideoUrl == null) primaryVideoUrl = baseUrl

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

            // نصنّف المحتوى (وليس الثقة بحقل type): #EXTM3U ⇒ M3U8؛ mp4 المعلن/الممتد ⇒ VIDEO.
            // يبعد الروابط الميتة (404/403/HTML) ويصحّح التصنيف الكاذب (moboreels m3u8 كـ mp4 → 3003).
            for ((url, q) in candidates) {
                if (!seen.add(url)) continue

                // إعداد «الجودات»: "high" = الأعلى فقط — يعرض رابطاً واحداً (الأساس غالباً)
                if (MosalsalySettings.qualityMode(prefs) == "high" && q.isNotBlank()) {
                    Log.i(TAG, "qualityMode=high, skipping $platform $q")
                    continue
                }

                val kind = if (q.isNotBlank()) probeQualityUrl(url, type) else probeMedia(url, type)
                if (kind == null) {
                    Log.w(TAG, "skip dead/mismatched $platform url=${url.take(80)}")
                    continue
                }
                alive.add(CLink(url, q, kind))
            }
        }

        // الروابط السطحية (mp4) أولًا (أسرع استجابة)، ثم m3u8
        alive.sortBy { it.kind != ExtractorLinkType.VIDEO }
        // نجمع الروابط ثم نبثّها دفعة واحدة: «الافتراضي» يبثّها بنفس ترتيبها
        // تماماً كما كان، و«تصاعدي/تنازلي» يعيدان ترتيبها فقط
        // (فرز مستقر — المتساوية تحتفظ بترتيبها، ولا حذف ولا تكرار).
        val collected = mutableListOf<ExtractorLink>()
        for (lnk in alive) {
            val label = buildString {
                if (MosalsalySettings.rawLinks(prefs)) {
                    // «الروابط الخام» (تصحيح): التسمية تصبح الرابط نفسه لو فُعلت
                    append(lnk.url.take(120))
                } else {
                    append("$platform $serial")
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
                if (lnk.q.isNotBlank()) this.quality = getQualityFromName(lnk.q)
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
        // أول حقل يحمل بادئة نصية "id:" وربما يصل مدمجاً مع mainUrl — ننظّفه دائماً
        var bookId = p[0].substringAfterLast("/").removePrefix("id:")
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
                    if (master.isBlank() || !master.contains("#EXTM3U")) {
                        Log.w(TAG, "goodshort no master bookId=$bookId ch=$chapterId len=${master.length}")
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
                Log.w(TAG, "platform $platform descriptor failed (no links)")
                false
            }
        }
    }
}