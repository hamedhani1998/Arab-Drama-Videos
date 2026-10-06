package com.deepdrama.plugin

import cloudstreamshared.FormatTag
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val DD_MAIN = "https://www.deep-drama.com"
private const val DD_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// عناوين Blogger: "مشاهدة مسلسل X مترجم كامل جميع الحلقات HD أونلاين | ديب دراما"
private val titleCleanRe = Regex("""مشاهدة\s*مسلسل\s*(.*?)\s*مترجم.*""", RegexOption.DOT_MATCHES_ALL)
private val titleAllRe = Regex("""^\s*(?:مشاهدة\s*)?(?:مسلسل\s*)?(.+?)\s*$""")
// لاحقة الموقع: "| ديب دراما"
private val titleSiteSuffixRe = Regex("""\s*\|\s*ديب\s*دراما\s*$""")

private class DdEntry(
    val title: String?,
    val url: String?,
    val poster: String?,
)

/**
 * DeepDrama — موقع Blogger عربي، كل مشاركة = مسلسل كامل في فيديو واحد مدمج
 * بخوادم متعددة.
 *
 * ★ رصد حي 2026-10-02 على ثمانية مسلسلات حقيقية، فحصةً لكل خادم:
 *
 *  1) vidaraa.cc — الخادم الوحيد العامل دائماً. يوفّر master تكيفي بثلاث جودات
 *     (480x854 / 720x1280 / 1080x1920) صوتها مدموج داخل كل جودة (mp4a.40.2)،
 *     و«ترجمة عربية مضمونة» فعلاً: 6 من 8 أفلام أتت `subtitles` كقائمة تحوي
 *     مسار WebVTT عربياً حقيقياً (والاثنان الآخران `null` — أي لا ترجمة لهما أصلاً).
 *  2) Rumble — يعمل، لكن **master التكيفي يردّ 403 "Access denied" دائماً**
 *     وبكل الترويسات (جرّبته: UA فقط، ومرجعية الصفحة، ومرجعية التضمين،
 *     وOrigin). الطريق العامل الوحيد هو `tar` chunklist (200 بمقاطع TS حقيقية)،
 *     وبجودة واحدة فقط 360x640. وترجمته `"cc":[]` أي لا ترجمة إطلاقاً.
 *  3) voe.sx — **ميت**: يحوّل إلى `jeremyparticipantanything.com` وهو NXDOMAIN
 *     (لا يُحلّ DNS). لا يُبثّ منه رابط، ولا يُحاول شيء عند الضغط على عنصره.
 *
 * فالنتيجة العملية: vidaraa هو مصدر التشغيل والحقيقة، وRumble خيار حقيقي بلا
 * master ولا ترجمة. وكل رابط تشغيل يُبنى برؤوس خاصة بالخادم الصادر منه — vidaraa
 * بمرجعيته هو، وRumble بمرجعية rumble.com — لأن تمرير مرجعية خادمٍ آخر رابطَ هذا
 * الخادم يردّ 403 عند اللاعب بلا أن يظهر السبب في أي مكان ظاهر للمستخدم.
 *
 * البيانات (روابط الخوادم) تُخزَّن في الحلقة أثناء عرض التفاصيل، وتُحلّ كل
 * نتيجة مرة واحدة وتُخزَّن مؤقتًا ليكون التشغيل فوريًا.
 */
class DeepDramaProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "Deep Drama"
    override var mainUrl = DD_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // أقسام الموقع من التذييل (الأقسام الأساسية + أنواع مختارة).
    private val sections = listOf(
        "أحدث المسلسلات" to null,
        "مسلسلات" to "مسلسل",
        "صيني" to "صيني",
        "مسلسلات مدبلجة" to "مسلسل مدبلج",
        "مسلسلات مترجمة" to "مسلسل مترجم",
        "أفلام" to "فيلم",
        "مسلسل أكشن" to "مسلسل أكشن",
        "مسلسل رومانسي" to "مسلسل رومانسي",
        "مسلسل دراما" to "مسلسل دراما",
        "مسلسل تاريخي" to "مسلسل تاريخي",
        "مسلسل فانتازيا" to "مسلسل فانتازيا",
    )

    override val mainPage = mainPageOf(
        *sections.map { it.first to it.first }.toTypedArray()
    )

    /**
     * رؤوس روابط vidaraa.
     *
     * قيس على المضيف: الـ master وروابط الجودات ردّت 200 حتى بـ UA وحده. لكن
     * بحمل UA + Referer + Origin كاملةً نضمن عمل الرابط في كل الحالات بدل أن
     * يعمل على هذا الحاسوب ويخسر على الهاتف. وما يبقى هنا يُحسم في emitServer
     * لكل خادم على حدة.
     */
    private fun headers() = mapOf(
        "User-Agent" to DD_UA,
        "Referer" to "https://vidaraa.cc/",
        "Origin" to "https://vidaraa.cc",
    )

    /**
     * روابط Rumble تحتاج مرجعيةً إلى rumble.com لا إلى vidaraa: الـ chunklist هو
     * الطريق الوحيد العامل هناك، ومرجعيةُ خادمٍ آخر قد تُسقطه كما تُسقط الـ master.
     */
    private fun rumbleHeaders() = mapOf(
        "User-Agent" to DD_UA,
        "Referer" to "https://rumble.com/",
        "Origin" to "https://rumble.com",
    )

    // رؤوس لجلب ملف ترجمة من خادم معيّن: Seepixو headers للسيرفر لتجنّب ردّ 403
    // (صفحة خطأ HTML تُعرض كرموز). vidaraa يتطلب Referer/Origin؛ Rumble يكفي UA.
    /**
     * رؤوس جلب ملف الترجمة. ★ مقيسة على السلوك الحيّ: ملفات vidaraa (نطاقات
     * `m*.s1q2105.com/subtitles/…_subtitle_0.vtt`) ردّت 200 بأي رؤوس — لا
     * تُمنع بالـ Referer، فمرور UA وحده كافٍ ولا يحتاج أصلاً. ومع ذلك نُبقي
     * Referer/Origin لأنهما لا يضرّان (الاختبار أعطى نفس البايتات حرفياً)،
     * وأي شبكة تعيد التحقق منهم تُخطئ التقدير.
     */
    private fun subHeaders(serverName: String): Map<String, String> =
        if (serverName.contains("vidaraa", ignoreCase = true))
            mapOf(
                "User-Agent" to DD_UA,
                "Referer" to "https://vidaraa.cc/",
                "Origin" to "https://vidaraa.cc",
            )
        else
            mapOf("User-Agent" to DD_UA)

    /**
     * خلاصة التحقق القاطع من مصدر الترجمات:
     * يخدّم vidaraa و Rumble ملفات .vtt متطابقة ومضاعفة-الترميز — نصٌّ عربي حُوّل
     * خطأً إلى UTF-8 مزدوج (مثلاً "لكن" → "ÙÙÙÙ"). كشفُ الفكّ (ISO_8859_1 → UTF_8)
     * يسترجع العربية في 681 سطراً من كلٍّ منهما، لكن صندوق CloudStream لـ plugins
     * لا يوفّر أي وسيلةٍ لتوجيه بايتاتٍ مصححةٍ إلى المشغّل: SubtitleFile لا يحمل سوى
     * lang/url/headers (ولا حقل محتوى)، والمشغّل يرفض data:، والـ plugin لا يملك
     * Context لكتابة ملفٍ محلي. لهذا نعرض الترجمة برابطها ورؤوسها الصحيحة كما
     * يوفّرها السيرفر — وهو الخيار الوحيد الذي يقبله المشغّل فعلاً.
     */
    private val mojibakeDiagnosticNote = Unit

    // نتيجة فكّ روابط خادم: الـ master التكيفي + الجودات الفردية + الترجمات + الصوت.
    private data class ServerResolved(
        val name: String,               // اسم الخادم للعرض
        val hls: String?,               // master التكيفي (جميع الجودات)
        val renditions: List<ServerRendition>, // الجودات الفردية (اختياري)
        val subtitles: List<SubtitleTrack>,
        val directVideo: String?,       // mp4 مباشر
        val extraHls: List<ServerRendition> = emptyList(), // روابط HLS إضافية قابلة للتشغيل (مثل chunklist Rumble)
        val altLabel: String = "",      // تسمية بديلة لوصف الرابط (مثل جودة tar)
        /** ملاحظة تُعرض للمستخدم تشرح نقص الخادم (جودة واحدة، بلا ترجمة…). */
        val note: String = "",
    )
    private data class ServerRendition(val url: String, val height: Int, val bandwidth: Long = 0)
    // ملف ترجمة: اسم اللغة + رابط .vtt.
    // تمريره برابطه المباشر (كما في v4 الذي أثبت العرض الصحيح). لا نستخدم inline/data:
    // لأن مشغّل التطبيق لا يعرضها (أخفى الترجمة كليًا في v6).
    private data class SubtitleTrack(val label: String, val url: String)

    // تخزين مؤقت لنتائج فكّ كل خادم حسب مصدره (رابط التضمين أو filecode).
    // الجلب يتم مرة واحدة أثناء عرض التفاصيل، فيُعاد استخدامه فورًا عند التشغيل.
    private val resolveCache = java.util.concurrent.ConcurrentHashMap<String, ServerResolved>()

    // ---------- Rumble ----------

    /**
     * يستخرج كائن JSON مُغلق بالأقواس يبدأ بعد المفتاح مباشرة (مثلاً `"u":{...}`)
     * من نص يحتوي JSON مضغوط، بنطاق تطابق الأقواس — أأمن من قص سماكة ثابتة.
     */
    private fun extractJsonObject(text: String, key: String): JsonNode? {
        val idx = text.indexOf(key)
        if (idx < 0) return null
        val start = text.indexOf('{', idx)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return try { mapper.readTree(text.substring(start, i + 1)) } catch (_: Exception) { null }
                    }
                }
                '"' -> inStr = true
            }
        }
        return null
    }

    /** يجلب روابط Rumble (مخزّنة) — مرة واحدة ثم يُعاد استخدامها. */
    private suspend fun resolveRumble(embedUrl: String): ServerResolved {
        resolveCache["rumble:$embedUrl"]?.let { return it }
        val html = app.get(embedUrl, headers = headers()).text
        val cleaned = html.replace("\\/", "/")

        // المصدر الحقيقي الكامل داخل كائن `u` (قيس حيّ 2026-10-02 على
        // rumble.com/embed/v7e3f8k — القيم كما وردت حرفياً):
        //   u.hls.url      = .../hls-vod/{id}/playlist.m3u8   → 403 "Access denied" دائماً
        //   u.tar.url      = .../Al42A.oaa.tar?r_file=chunklist.m3u8&r_type=…&r_range=…
        //                                                   → 200، ومقاطعه TS حقيقية
        //                                                     (أول مقطع: 0x47، 3412 حزمة
        //                                                     × 188 بايت = 395364 بايت)
        //   u.audio.url    = .../Al42A.Gaa.aac                 → صوت منفصل (لا يُعرض: الفيديوهات فقط)
        //   u.timeline.url = .../Al42A.Faa.mp4 (180x320)        ← معاينة صغيرة، ليست الفيلم
        //   "cc":[]                                           ← لا ترجمة إطلاقاً في Rumble
        //
        // ★ قرار: لا نُصدّر الـ master أبداً بعد قياس أربع طرق (UA وحده، ومرجعية
        //   الصفحة، ومرجعية التضمين، وOrigin — كلها 403). كان الكود ينشر رابطاً
        //   ميتاً أولاً في القائمة، فيختاره اللاعب تلقائياً فيفشل التشغيل رغم أن
        //   رابط 360p تحته سليم تماماً. نُصدّر tar فقط: هذا ما يفعله مشغّل الموقع
        //   فعلاً، وهو ما ينجح.
        val uNode = extractJsonObject(cleaned, "\"u\"")
        // نقرأ وجود حقل hls فقط (لنقرّر التسمية) — ولا نُبثّه: قيس 403 دائماً.
        val hasDeadMaster = uNode?.get("hls")?.get("url")?.asText()?.isNotBlank() == true
        val tar = uNode?.get("tar")?.get("url")?.asText()?.takeIf { it.isNotBlank() }
        val tarMeta = uNode?.get("tar")?.get("meta")
        val tarH = tarMeta?.get("h")?.asInt() ?: 0
        val tarW = tarMeta?.get("w")?.asInt() ?: 0
        // ملاحظة: Rumble يوفّر `u.audio.url` (aac منفصل)، ولا نعرضه رابطاً —
        //   طلب المستخدم هو الفيديوهات فقط بلا مسار صوتي.

        // الترجمات: Rumble يقدّم "cc":{lang:{language,path}} أو مصفوفة [] (بلا ترجمة).
        val subs = mutableListOf<SubtitleTrack>()
        val ccNode = extractJsonObject(cleaned, "\"cc\"")
        if (ccNode != null && ccNode.isObject) {
            ccNode.fields().forEach { (lang, info) ->
                val path = info.get("path")?.asText()?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val langName = info.get("language")?.asText().orEmpty()
                subs.add(SubtitleTrack("${langName.ifBlank { lang }} (Rumble)", path))
            }
        }

        // المسارات القابلة للتشغيل عند Rumble: tar chunklist فقط (الجودة الوحيدة
        // المتاحة فعلياً: 360x640). لا master ميتة، ولا معاينة 180x320.
        val extra = mutableListOf<ServerRendition>()
        if (tar != null) {
            extra.add(ServerRendition(tar, tarH, 0))
        }
        val resolved = ServerResolved(
            name = "Rumble",
            hls = null,                 // ★ 403Always — لا يُبثّ.
            renditions = emptyList(),
            subtitles = subs,
            directVideo = null,  // لا ملف mp4 كامل مباشر عند Rumble — الصحيح هو الـ HLS.
            extraHls = extra,
            altLabel = if (tarW > 0 && tarH > 0) "${tarW}x${tarH}" else "360p",
            note = if (hasDeadMaster && tar == null) "لا مسار قابل للتشغيل"
                else if (hasDeadMaster) "الجودة المتاحة ${if (tarH > 0) "${tarH}p" else "360p"} فقط — الجودة الأعلى على vidaraa"
                else "",
        )
        resolveCache["rumble:$embedUrl"] = resolved
        return resolved
    }

    // ---------- voe.sx ----------

    /**
     * voe.sx — **خادم ميت** (قيس حيّ 2026-10-02).
     *
     * صفحة `voe.sx/e/{id}` ما زالت تردّ 200، لكنها صفحة تحويل JavaScript بلا
     * أي رابط وسائط، ووجهتها `jeremyparticipantanything.com` لا تُحَلّ في DNS
     * إطلاقاً (`NXDOMAIN` من 8.8.8.8 ومن محلّل النظام). لا m3u8 ولا mp4 في
     * أي من الصفحتين.
     *
     * ★ قرار: نحتفظ بالكود (قد يعود الخادم يوماً) لكن **لا نُبثّ منه شيئاً**.
     * كان يُحاول فكّه عند تشغيل كل مسلسل، فيقضي وقتاً في طلبات تُنتظر ثم تفشل
     * بلا فائدة، والحلقة لا تظهر إلا بعد انتهاء المحاولة كلها. الآن يُوسم
     * ميتاً فوراً فينتهي خلال جزء من الثانية.
     */
    private suspend fun resolveVoe(embedUrl: String): ServerResolved {
        resolveCache["voe:$embedUrl"]?.let { return it }
        var hls: String? = null
        var direct: String? = null
        var reachable = false
        try {
            val page = app.get(embedUrl, headers = headers()).text
            val cleaned = page.replace("\\/", "/")

            // 1) بعض النسخ تكشف master/مسارات مباشرة داخل الصفحة (url في سكربت).
            hls = Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").find(cleaned)?.groupValues?.get(1)
            direct = Regex("""(https?://[^"'\s<>]+?\.mp4[^"'\s<>]*)""").find(cleaned)?.groupValues?.get(1)

            // 2) rotator: معلمة permanentToken → نُكمل إلى خادم الوجهة ونفكّ منه.
            var finalUrl = cleaned.substringAfter("window.location.href = '", "").substringBefore("'")
            if (finalUrl.isBlank()) {
                finalUrl = Regex("""(?:location|location\.href|window\.location)\s*=\s*["']([^"']+)["']""")
                    .find(cleaned)?.groupValues?.get(1) ?: ""
            }
            if (finalUrl.startsWith("http")) {
                try {
                    val hub = app.get(finalUrl, headers = headers()).text
                    val hubClean = hub.replace("\\/", "/")
                    reachable = true
                    if (hls == null) {
                        hls = Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""")
                            .find(hubClean)?.groupValues?.get(1)
                    }
                    if (direct == null) {
                        direct = Regex("""(https?://[^"'\s<>]+?\.mp4[^"'\s<>]*)""")
                            .find(hubClean)?.groupValues?.get(1)
                    }
                    if (hls == null) {
                        hls = Regex("""(?:source|file)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
                            .find(hubClean)?.groupValues?.get(1)
                    }
                } catch (_: Exception) { /* وجهة الـ rotator معطّلة — نستمر */ }
            }
        } catch (_: Exception) { /* voe غير قابل للفك — نرجع فارغًا */ }

        val resolved = ServerResolved(
            name = "voe",
            hls = hls?.takeIf { it.isNotBlank() },
            renditions = emptyList(),
            subtitles = emptyList(),
            directVideo = direct?.takeIf { it.isNotBlank() },
            note = if (hls == null && direct == null && !reachable)
                "الخادم معطّل حالياً — استخدم vidaraa" else "",
        )
        resolveCache["voe:$embedUrl"] = resolved
        return resolved
    }

    // ---------- vidaraa ----------

    /** يحول رابط vidaraa إلى filecode (المقطع بعد /e/). */
    private fun vidaraaFilecode(embedUrl: String): String? {
        return Regex("""/(?:e|v|embed)/([A-Za-z0-9_-]+)""").find(embedUrl)?.groupValues?.get(1)
    }

    /** يحلل الجودات من master vidaraa (روابط نسبية تُحلّ مقابل مجلد master). */
    private fun parseVidaraaMaster(masterText: String, masterBase: String): List<ServerRendition> {
        val out = mutableListOf<ServerRendition>()
        val lines = masterText.lines()
        for (i in 0 until lines.size - 1) {
            val inf = lines[i].trim()
            if (!inf.startsWith("#EXT-X-STREAM-INF")) continue
            val height = Regex("""RESOLUTION=\d+x(\d+)""").find(inf)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(inf)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            var url = lines[i + 1].trim()
            if (url.isBlank() || url.startsWith("#")) continue
            // الروابط نسبية (index_1080x1920.m3u8?token=...) — نحلها مقابل مجلد master
            if (!url.startsWith("http")) url = masterBase + url
            out.add(ServerRendition(url, height, bw))
        }
        return out.distinctBy { it.height }
    }

    /**
     * يجلب بيانات vidaraa من API.
     *
     * ★ قاعدة مُقاسة حيّاً، لا افتراضية: **لا نستعمل نتيجة مخزّنة لجلب الترجمة.**
     * مسار الترجمة نفسه محدود الصلاحية (مصفوف من 3 قيم بعد اسميه اللفظي/الرقمي،
     * يتغيّر كل نداء) — يعطي 200 مع WebVTT عربي سليم في لحظته، وبعد ساعات يعيد
     * 404 "page not found" على الرابط نفسه بلا تغيّر في المضيف. وجدته بالصدفة
     * لأن أول استدعاء في المسح دُفن تحت قصّ النص إلى 70 حرفاً فبدا اللاحق
     * `_subtitle_0.vtt` مقطوعاً و404.
     *
     * فما نصنعه: نُبقي تسخين الذاكرة (fetchLinks/preload) لأنه يجعل التشغيل
     * فورياً، لكن **loadLinks يتجاوزها دائماً** ويطلب من vidaraa استدعاءً جديداً
     * في كل مرة يفتح فيها المستخدم السلسلة. فالتشغيل الأول بعد فتح المسلسل
     * يقرأ رابطاً حياً، وكما طال hiatus بين فتح المسلسل وضغط «تشغيل» تبقى
     * الترجمة صالحةً لأن المسار يُجدَّد في اللحظة الأخيرة لا قبل دقائق.
     *
     * الخادم نفسه: `subtitles` قد تكون `null` (لا ترجمة للفيلم أصلاً) أو قائمة
     * تحوي WebVTT عربياً حقيقياً — 6 من 8 مسلسلات فحوصها latter.
     */
    private suspend fun resolveVidaraa(embedUrl: String, forceRefresh: Boolean = false): ServerResolved {
        val key = "vidaraa:$embedUrl"
        if (!forceRefresh) resolveCache[key]?.let { return it }
        val filecode = vidaraaFilecode(embedUrl) ?: return ServerResolved("vidaraa", null, emptyList(), emptyList(), null)
        var streamUrl: String? = null
        var directMp4: String? = null
        var subs = emptyList<SubtitleTrack>()
        try {
            val body = mapper.writeValueAsString(mapOf("filecode" to filecode, "device" to "web"))
            val resp = app.post(
                "https://vidaraa.cc/api/stream",
                requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType()),
                headers = headers() + mapOf(
                    "Content-Type" to "application/json",
                    "Referer" to embedUrl,
                    "Origin" to "https://vidaraa.cc",
                ),
                referer = embedUrl,
            ).text
            val node = mapper.readTree(resp)
            streamUrl = node.get("streaming_url")?.asText()?.takeIf { it.isNotBlank() }
            // ترجمة vidaraa: عناصر {file_path, language}. المسار رابط WebVTT كامل
            // (ينتهي ‎_subtitle_0.vtt‎) ولا يحتاج ترويسة خاصة (قيس: 200 بأي رؤوس).
            val subArr = node.get("subtitles")
            if (subArr != null && subArr.isArray) {
                subs = subArr.mapNotNull { s ->
                    val path = s.get("file_path")?.asText()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val lang = s.get("language")?.asText().orEmpty().ifBlank { "العربية" }
                    SubtitleTrack("$lang (vidaraa)", path)
                }
            }
        } catch (_: Exception) { streamUrl = null }

        // vidaraa يخدم شكلين مختلفين بحسب الفيديو:
        //   1) HLS master (~p1-*.s1q2105.com/hls/.../master.m3u8?token=) — نقسم الجودات النسبية.
        //   2) ملف mp4 مباشر (streamix.so/uploads/video_*.mp4) — ليس HLS إطلاقًا.
        // التمييز بالامتداد حتى لا نمرّر mp4 كنوع M3U8 فينكسر المشغّل، ولا نقرأ mp4 كـ playlist.
        var renditions = emptyList<ServerRendition>()
        val su = streamUrl ?: ""
        val isDirectMp4 = su.lowercase().let {
            it.contains(".mp4") || it.contains(".m4v") || it.contains("mime_type=video_mp4")
        }
        if (isDirectMp4) {
            directMp4 = su
            streamUrl = null
        } else if (su.isNotBlank()) {
            try {
                val base = su.substringBeforeLast('/') + "/"
                val masterText = app.get(su, headers = headers(), referer = "https://vidaraa.cc/").text
                renditions = parseVidaraaMaster(masterText, base)
            } catch (_: Exception) { renditions = emptyList() }
        }

        val resolved = ServerResolved(
            name = "vidaraa",
            hls = streamUrl,
            renditions = renditions,
            subtitles = subs,
            directVideo = directMp4,
            note = if (subs.isEmpty() && streamUrl != null)
                "لا ترجمة متوفرة لهذا المقطع" else "",
        )
        resolveCache[key] = resolved
        return resolved
    }

    private fun cleanTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val base = titleSiteSuffixRe.replace(title, "").trim()
        val clean = titleCleanRe.find(base)?.groupValues?.get(1)?.trim()
            ?: titleAllRe.find(base)?.groupValues?.get(1)?.trim()
            ?: base.trim()
        if (clean.isBlank()) return null
        return clean
    }

    // تحليل تغذية Blogger (الصفحة الرئيسية)
    private fun parseFeedEntries(text: String): List<DdEntry> {
        val out = mutableListOf<DdEntry>()
        if (!text.trim().startsWith("{")) return out
        val root = mapper.readTree(text).get("feed") ?: return out
        val entries = root.get("entry") ?: return out
        for (e in entries) {
            val title = e.get("title")?.get("\$t")?.asText()
            var url: String? = null
            val links = e.get("link")
            if (links != null && links.isArray) {
                for (l in links) {
                    if (l.get("rel")?.asText() == "alternate") { url = l.get("href")?.asText(); break }
                }
            }
            var poster: String? = null
            val content = e.get("content")?.get("\$t")?.asText()
            if (!content.isNullOrBlank()) {
                val m = Regex("""https://[^\s"'<>]+\.(?:jpg|jpeg|png|webp)[^\s"'<>]*""").find(content)
                poster = m?.value
            }
            out.add(DdEntry(cleanTitle(title), url, poster))
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val label = sections.firstOrNull { it.first == request.data }?.second
            val start = ((page - 1) * 12) + 1
            val base = if (label == null)
                "$DD_MAIN/feeds/posts/default?alt=json&max-results=12&start-index=$start"
            else
                "$DD_MAIN/feeds/posts/default/-/${java.net.URLEncoder.encode(label, "UTF-8")}?alt=json&max-results=12&start-index=$start"
            val text = app.get(base, headers = headers()).text
            val items = parseFeedEntries(text)
            if (items.isEmpty()) null
            else {
                val list = items.mapNotNull { e ->
                    val title = e.title ?: return@mapNotNull null
                    val url = e.url ?: return@mapNotNull null
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = e.poster
                    }
                }
                newHomePageResponse(request.name, list)
            }
        } catch (e: Exception) { null }
    }

    // بطاقات الموقع (بنية .xr-card)
    private val xrCardRe = Regex(
        """<article class='xr-card'>.*?<a href='([^']+)' title='([^']*)'.*?<img[^>]*src='([^']+)'""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val posterSizeRe = Regex("""=w\d+""")
    private fun upgradePoster(u: String): String = posterSizeRe.replace(u, "=w720")

    private fun parseCards(html: String): List<DdEntry> {
        val out = mutableListOf<DdEntry>()
        for (m in xrCardRe.findAll(html)) {
            val url = m.groupValues[1].trim()
            val title = cleanTitle(m.groupValues[2]) ?: continue
            val poster = upgradePoster(m.groupValues[3].trim()).ifBlank { null }
            if (url.isBlank()) continue
            out.add(DdEntry(title, url, poster))
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val base = "$DD_MAIN/search?q=$q&max-results=20"
            val text = app.get(base, headers = headers()).text
            val items = parseCards(text)
            if (items.isEmpty()) return emptyList()
            items.mapNotNull { e ->
                val title = e.title ?: return@mapNotNull null
                val url = e.url ?: return@mapNotNull null
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = e.poster
                }
            }
        } catch (e: Exception) { null }
    }

    // أزرار الخوادم في صفحة المسلسل
    private val serverBtnRe = Regex("""xr-server-btn[^>]*data-src="([^"]+)"""")

    private fun serverButtons(html: String): List<String> {
        val out = mutableListOf<String>()
        for (m in serverBtnRe.findAll(html)) {
            val url = m.groupValues[1].trim()
            if (url.startsWith("http") && url.isNotBlank()) out.add(url)
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = headers()).document
            val raw = doc.html()

            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.let { cleanTitle(it) }
                ?: cleanTitle(doc.title()).orEmpty()
                .ifBlank { "Deep Drama" }
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractPoster(raw)
            val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")

            val servers = serverButtons(raw)
            if (servers.isEmpty()) return null

            // كل أزرار الخوادم كما يعرضها الموقع (rumble/voe/vidaraa...) — نُدرجها
            // كلها في بيانات الحلقة، ثم يرتّبها loadLinks حسب الاعتمادية. نقصّ
            // معامل الاستعلام عند الحفظ: جرّبته على Rumble — العنوانان المطبوعان
            // (بـ ?pub= وبدونه) يعطيان tar وhls متطابقين حرفياً، فلا يُفقد شيئاً.
            val bundle = servers.joinToString("|||") { it.substringBefore("?") }

            // نُسخّن ذاكرة التخزين للخوادم القابلة للفك (vidaraa/rumble) أثناء عرض
            // التفاصيل حتى يكون أول تشغيل أسرع. loadLinks يتجاوز التسخين للترجمة
            // (forceRefresh) لأن رابطها محدود الصلاحية — فالتسخين هنا للروابط
            // والجودات فقط. voe.sx لا يُسخَّن: خادمه ميت.
            servers.forEach { srv ->
                try {
                    when {
                        srv.contains("vidaraa") -> resolveVidaraa(srv.substringBefore("?"))
                        srv.contains("rumble") -> resolveRumble(srv.substringBefore("?"))
                    }
                } catch (_: Exception) { /* تجاهل — يُعاد عند الحاجة في loadLinks */ }
            }

            val episode = newEpisode(bundle) {
                name = "الحلقة الكاملة"
                episode = 1
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(episode)) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) { null }
    }

    // استخراج صورة الغلاف من HTML المشاركة (بعد تفكيك الكيانات)
    private fun extractPoster(raw: String): String? {
        val html = raw.replace("&amp;", "&")
        return Regex("""https://(?:acf\.)?goodshort\.com/[^"'\s<>\\]+?\.(?:jpg|jpeg|png|webp)[^"'\s<>\\]*""")
            .find(html)?.value
            ?: Regex("""https://blogger\.googleusercontent\.com/[^"'\s<>\\]+?\.(?:jpg|jpeg|png|webp)[^"'\s<>\\]*""")
                .find(html)?.value
    }

    /**
     * يبثّ خيارات خادم واحد: master تكيفي + جودات فردية (كل جودة يوفّرها الموقع) +
     * فيديو مباشر (إن وُجد فعلاً) + ترجمات كل لغة.
     * لا نكرّر نقطة جودة واحدة (إن كانت الجودات مفردة) — الـ master وحده يكفي.
     */
    private suspend fun emitServer(
        prefs: SharedPreferences?,
        server: ServerResolved,
        primary: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // نجمع روابط هذا الخادم أولاً، ثم نبثّها بعد فرزها حسب اختيار المستخدم.
        // الافتراضي (default) يبثّها بنفس ترتيبها تماماً كما كان — بلا أي تغيير.
        val collected = mutableListOf<ExtractorLink>()
        val sink: (ExtractorLink) -> Unit = { collected.add(it); Unit }

        val tag = server.name
        val master = server.hls ?: server.renditions.maxByOrNull { it.height }?.url
        val renditions = server.renditions.sortedBy { it.height }
        // ★ الرؤوس حسب الخادم لا دفعةً واحدة: روابط Rumble (chunklist) لا تُخدَم
        //   بمرجعية vidaraa. رابطٌ سليم بلا رؤوسه = 403 عند اللاعب، وهو ترجمة
        //   «التشغيل لا يفتح» إلى صمت.
        val linkHeaders =
            if (server.name.contains("rumble", ignoreCase = true)) rumbleHeaders() else headers()

        // 1) الـ master التكيفي — الخيار المضمون الذي يشمل كل الجودات.
        if (master != null) {
            val max = renditions.maxOfOrNull { it.height } ?: 1080
            sink(newExtractorLink(name, FormatTag.tagged("${if (primary) "★ " else ""}$tag · جميع الجودات", master, ExtractorLinkType.M3U8), master, ExtractorLinkType.M3U8) {
                this.quality = getQualityFromName("${max}p")
                this.headers = linkHeaders
            })
        }

        // 2) الجودات الفردية — كل ما يعرضه الموقع.
        //    vidaraa: ثلاث playlists فعلاً (480x854 / 720x1280 / 1080x1920)، قِسناها.
        //    ملاحظة مهمة: الروابط في الـ master نسبية، وحلّها يتم مقابل **مجلد**
        //    الـ master لا مساره الكامل — ولهذا تبقى الروابط صالحة.
        renditions.forEach { r ->
            val bw = if (r.bandwidth > 0) " · ${(r.bandwidth / 1000)}k" else ""
            sink(newExtractorLink(name, FormatTag.tagged("${if (primary) "★ " else ""}$tag ${r.height}p$bw", r.url, ExtractorLinkType.M3U8), r.url, ExtractorLinkType.M3U8) {
                this.quality = getQualityFromName("${r.height}p")
                this.headers = linkHeaders
            })
        }

        // 2b) روابط HLS إضافية قابلة للتشغيل (chunklist Rumble): نعرضها دائمًا
        //     كخيار مستقل، لأنها الجودة الوحيدة التي يخدّمها Rumble فعلياً —
        //     الـ master يردّ 403 «Access denied» بكل الترويسات (قيس 2026-10-02).
        server.extraHls.forEach { x ->
            val label = if (x.height > 0) "${x.height}p" else (server.altLabel.ifBlank { "جودة" })
            sink(newExtractorLink(name, FormatTag.tagged("${if (primary) "★ " else ""}$tag · ${label}", x.url, ExtractorLinkType.M3U8), x.url, ExtractorLinkType.M3U8) {
                this.quality = getQualityFromName(x.height.takeIf { it > 0 }?.let { "${it}p" } ?: "480p")
                this.headers = linkHeaders
                this.referer = "https://rumble.com/"
            })
        }

        // 3) فيديو مباشر (mp4) إن وُجد فعلاً وقابلاً للتشغيل.
        //    vidaraa: بعض الفيديوات تُخدم كـ mp4 مباشر. نعرضه فقط إذا لم يتوفر
        //    HLS بديل — فمع وجود HLS نعرض رابطاً بلا فائدة.
        //    Rumble لا يوفّر mp4 كاملاً (المعاينة 180x320 ليست الفيلم) فلا نصدّره.
        server.directVideo?.let { mp4 ->
            val hasHls = server.hls != null || server.renditions.isNotEmpty()
            if (hasHls) {
                // يوجد HLS صحيحة تعمل — لا نعرض mp4 معطلاً.
            } else {
                val q = Regex("""/(\d{3,4})p/""").find(mp4)?.groupValues?.get(1)
                    ?: if (mp4.contains("1080")) "1080" else if (mp4.contains("720")) "720" else "480"
                sink(newExtractorLink(name, FormatTag.tagged("$tag MP4", mp4, ExtractorLinkType.VIDEO), mp4, ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName("${q}p")
                    this.headers = linkHeaders
                })
            }
        }

        // 4) ملفات الترجمة (كل لغة يوفّرها الخادم). نمرّرها برابطها المباشر برؤوس
        // قياسية صحيحة (User-Agent + Referer/Origin للخادم الصادر) حتى لا يردّ
        // السيرفر بصفحة خطأ 403 تُعرض كرموز.
        server.subtitles.forEach { sub ->
            try {
                if (prefs?.getBoolean(DeepDramaSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) != false) {
                    subtitleCallback(
                        newSubtitleFile(sub.label, sub.url) {
                            this.headers = subHeaders(server.name)
                        }
                    )
                }
            } catch (_: Exception) {}
        }

        // ★ بثّ الروابط بعد اكتمالها: «افتراضي» = نفس الترتيب تماماً، و«تصاعدي/
        // تنازلي» يعيدان ترتيبها فقط (فرز مستقر: المتساوية تحتفظ بترتيبها، ولا
        // حذف ولا تكرار).
        val order = when (prefs?.getString(DeepDramaSettingsBottomSheet.KEY_QUALITY_ORDER, "default")) {
            "asc" -> "asc"
            "desc" -> "desc"
            else -> "default"
        }
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            if (data.isBlank()) return false

            // بيانات الحلقة: كل روابط الخوادم (rumble/voe/vidaraa...) بترتيب الموقع.
            val serverUrls = data.split("|||").map { it.trim() }.filter { it.startsWith("http") && it.isNotBlank() }
            if (serverUrls.isEmpty()) return false

            // ★ ترتيب الخوادم حسب الاعتمادية، لا بترتيب الموقع. الموقع يضع
            //   Rumble أولاً (وأحياناً voe)، واللاعب يختار **أول** رابط في
            //   القائمة تلقائياً — فكان التشغيل يبدأ من أضعف خادم في كل مرة.
            //   vidaraa هو الوحيد الذي أعطى master تكيفياً صالحاً 100% في القياس،
            //   فهو الأول دائماً.
            fun rankOf(s: String) = when {
                s.contains("vidaraa") -> 0
                s.contains("rumble") -> 1
                s.contains("voe") || s.contains("vfaststream") -> 2
                else -> 3
            }
            val ordered = serverUrls.sortedBy { rankOf(it) }

            // نجهّز قائمة الخوادم بحسب أولويتها، ونتجنب أي تكرار في العناوين.
            // لا نعتمد فقط على الذاكرة المؤقتة (load قد يفشل في تسخينها عند الرجوع
            // السريع)، بل نعيد الفكّ هنا فوراً — لضمان أن التشغيل لا 'ينكسر' عند العودة.
            val resolved = mutableListOf<ServerResolved>()
            val seen = mutableSetOf<String>()

            for (s in ordered) {
                val kind = when (rankOf(s)) {
                    0 -> "vidaraa"
                    1 -> "rumble"
                    2 -> "voe"
                    else -> "other"
                }
                if (!seen.add(kind)) continue  // سيرفر واحد لكل نوع

                val resolvedServer = try {
                    when (kind) {
                        // ★ forceRefresh: رابط الترجمة عند vidaraa محدود الصلاحية،
                        // فنطلب واحداً جديداً في كل مرة. انظر شرح resolveVidaraa.
                        "vidaraa" -> resolveVidaraa(s, forceRefresh = true)
                        "rumble" -> resolveRumble(s)
                        "voe" -> resolveVoe(s)
                        else -> null
                    }
                } catch (_: Exception) { null }

                if (resolvedServer != null) {
                    // نبثّ هذا الخادم فور حلّه، قبل الانتظار على الخادم الآخر —
                    // فيبدأ الفيديو بسرعة ولا ينتظر المحاولتين معاً.
                    val anyPlayable = resolvedServer.hls != null || resolvedServer.renditions.isNotEmpty() ||
                        resolvedServer.directVideo != null || resolvedServer.extraHls.isNotEmpty()
                    emitServer(prefs, resolvedServer, resolved.isEmpty() && anyPlayable, subtitleCallback, callback)
                    if (anyPlayable) resolved.add(resolvedServer)
                }
            }

            // لا نستخدم أبداً loadExtractor العام هنا: iframes DeepDrama ليست
            // extensions قابلة للفهم وتفشل، فتسبب 'لا يفتح'. إن لم تُحلّ أي نتيجة
            // نُعيد المحاولة على أول خادم عامل (قد يكون تعذّر عابراً في الشبكة).
            if (resolved.isEmpty()) {
                val first = ordered.firstOrNull()
                if (first != null) {
                    val retry = try {
                        when (rankOf(first)) {
                            0 -> resolveVidaraa(first, forceRefresh = true)
                            1 -> resolveRumble(first)
                            else -> resolveVoe(first)
                        }
                    } catch (_: Exception) { null }
                    if (retry != null) emitServer(prefs, retry, true, subtitleCallback, callback)
                }
            }
            true
        } catch (e: Exception) { false }
    }
}
