package com.shortdramaar.plugin

import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

/**
 * «دراما قصيرة» — اثنتا عشرة قناة يوتيوب مدبلجة/مترجمة، ونفس نمط مصدر ARY
 * في كل ما عدّا قائمة القنوات وترقيم الحلقات.
 *
 * ## لماذا قوائم التشغيل؟
 * هي المصدر الكامل على هذه القنوات. تبويب «الفيديوهات» مختلط، والبحث
 * داخل القناة إما مُتجاهَل أو ناقص، والدراما القصيرة تنشر كل حلقة في قائمة —
 * فهي الطريقة الوحيدة المضمونة الضمّ لكل حلقات مسلسل.
 *
 * ## أين يخالف ARY عمداً؟
 *
 * **1) لا قناة أساسية.** ARY قناة واحدة وصفٌّ لها + صفُّ «المقترحات». هنا
 * اثنتا عشرة قناة كلها مُدمجة في صفٍّ واحد «مسلسلات دراما قصيرة»، وصفٌّ
 * منفصل «إعلانات وتشويقات» ( playlists宣传ية مستقلة)، وصفٌّ ثالث «مقترحاتك»
 * لما يضيفه المستخدم من الإعدادات. ثلاثة صفوف ثابتة مهما زادت القنوات.
 *
 * **2) لا اسمَ قناة على الكارت.** في ARY كان عنوان الكارت «المسلسل · القناة»
 * عبر `from`. هنا لا تمرّ `from` ولا `bareName` على الكارت إطلاقاً: الاسم
 * هو `bareName(p.title)` وحده. سببه أن `dedupe` هناك كانت قد تُسقط قائمةً
 * وتُبقي أخرى — فعلاً لا بدّ من ذلك حين يُرقَّم كل مسلسل بنفس الطريقة التي
 * يرقّم بها غيره (انظر النقطة 3) — لكن هنا **الترقيم وضعيّ** فلا misalignment
 * أصلاً، فلا داعي لتكرار اسم القناة على الكارت.
 *
 * **3) الترقيم وضعيّ (1، 2، 3…) لا من نص العنوان.** في ARY كانت الحلقات
 * تُرقَّم من «الحلقة 3» لأن القناة تكتبها. قنوات الدراما القصيرة **لا تكتب
 * أرقام الحلقات في العناوين** — كل حلقة في قائمتها، والقائمة بلا رقم. لو
 * استعملنا `EP_NUM_RE` لأعطى كل حلقة رقماً `null` وحدث صفحاتُ تفاصيل بلا
 * حلقة واحدة. فنُرقّم بترتيب القائمة كما نشرها الناشر — وهو نفس الترتيب
 * الذي تعتمده يوتيوب أصلاً، والأصحّ في كل الحالتين.
 *
 * **4) الجلب على دفعاتٍ مقتصرة، لا دفعةً واحدة ولا تسلسلاً تاماً.**
 * ARY قناة واحدة طلبان. هنا 12 قناة = ~24 طلباً. دفعةً واحدة يردّ يوتيوب
 * صفر قوائم بسهولة تحت الضغط، وتسلسلاً تاماً تتأخر الصفحة الرئيسة إلى ما
 * بعد نصف دقيقة. فنجلب أربعاً في اللحظة، ولكل قناة سقفٌ زمني، ولكل الصفحة
 * ميزانيةٌ تُعيد ما وصل عندها. (انظر `FETCH_BATCH` و`CHANNEL_TIMEOUT_MS`
 * و`HOME_BUDGET_MS`.)
 */
class ShortDramaProvider(
    private val prefs: SharedPreferences? = null
) : MainAPI() {

    /** خيار محرك التشغيل المختار في الإعدادات ("newpipe" الافتراضي). */
    private fun playbackMode(): String =
        prefs?.getString(ShortDramaSettings.KEY_PLAYBACK_MODE, "newpipe") ?: "newpipe"

    /**
     * أي ملف صوتي مع كل جودة.
     *
     * ★ لا إعدادَ لهذا: حُذف «الملف الصوتي» من إعدادات ARY مع قسم الترجمة
     * والصوت، فصار الاختيار داخلياً — أعلى بت/ث متوافق.
     */
    private fun audioPref(): String = "best"

    /** يستخرج كوديك حقيقي من mimeType («video/mp4; codecs="avc1.640028"») إن وُجد. */
    private fun codecFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val i = mime.indexOf("codecs=")
        if (i < 0) return null
        val v = mime.substring(i + "codecs=".length).trim().removeSurrounding("\"")
        return if (v.isNotBlank()) v else null
    }

    /** عرض كل الجودات أم الأعلى فقط. */
    private fun qualityMode(): String =
        prefs?.getString(ShortDramaSettings.KEY_MAX_QUALITY, "all") ?: "all"

    /** هل نستخدم النطاق البديل redirector. */
    private fun useRedirect(): Boolean =
        prefs?.getBoolean(ShortDramaSettings.KEY_REDIRECT, false) ?: false

    companion object {
        private const val TAG = "ShortDrama"

        /**
         * القنوات الاثنتا عشرة، كما رابطها المستخدم. لا نُدرج `UC…` ولا نُشتقه:
         * الاشتقاق من الـ handle هو مسار `extraChannels()` المُختبَر، والقيم
         * هنا مفاتيحُ بحث لا معرّفات — فتظهر في سجلّات التشغيل باسم القناة.
         *
         * ⚠️ ثلاث ملاحظات على الصياغة (لم تتغيّر عمداً، ليعمل المصدر فوراً):
         *   - `دراماقصيرة-ج8ق` فيها شَرطة، وYouTube يعاملها فاصلةً فيُقصر
         *     المعرّف على ما قبلها. تعمل إن كان ذيلُها لاحقاً؛ وإن لم تكن
         *     القناة موجودة بهذا الاسم فلا تُستبعد قنوات بسبب هذه النقطة.
         *   - `@قناةالمسلسلاتالرومانسية` و`@山谷45` و`@مسرحهابيدراما` أسماء
         *     عربية/صينية تحتاج percent-encoding في الرابط النهائي.
         */
        private val BASE_CHANNELS = listOf(
            "دراما عربية" to "https://www.youtube.com/@Arabicdrma/playlists",
            "المسلسلات الرومانسية" to "https://www.youtube.com/@قناةالمسلسلاتالرومانسية/playlists",
            "YoYo Arabic" to "https://www.youtube.com/@YoYoArabicChannel/playlists",
            "دراما قصيرة عن الحب والأخلاق" to "https://www.youtube.com/@دراماقصيرةعنالحبوالأخلاق/playlists",
            "دراما قصيرة ج8ق" to "https://www.youtube.com/@دراماقصيرة-ج8ق/playlists",
            "Dragon Short Drama" to "https://www.youtube.com/@dragonshortdrama/playlists",
            "مسرح هابد" to "https://www.youtube.com/@مسرحهابيدراما/playlists",
            "HeartThrob TV" to "https://www.youtube.com/@HeartThrobTV-u5x/playlists",
            "PM GG" to "https://www.youtube.com/@PM-GGH108/playlists",
            "Dumpling Drama" to "https://www.youtube.com/@DumplingDrama-p5x/playlists",
            "Hero Legend" to "https://www.youtube.com/@HeroLegendAR/playlists",
            "Shangu" to "https://www.youtube.com/@山谷45/playlists"
        )

        private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?key=$INNERTUBE_KEY&prettyPrint=false"
        private const val CLIENT_VERSION = "2.20260918.00.00"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val BOM = "﻿"

        /**
         * إعلانات وتشويقات المسلسلات — صفٌّ منفصل، مطابق لما يفعله ARY.
         *
         * ⚠️ التحفّظ: `PROMO_RE` كان مُتحقَّقاً منه على عناوين ARY وحدها. هنا
         * قد يلتقط كلمةً عابرة في اسم مسلسل شرقي، فيُخرج مسلسلاً من صفّه
         * ويُدخله في صف الإعلانات. النتيجة أخفّ من أن تُعطّل الصف، فبقي —
         * لكن إن ظهر مسلسلٌ في الصفّ الخطأ فحذف `isPromo` من `getMainPage`
         * هو الإصلاح (سطرٌ واحد) دون لمس شيء آخر.
         */
        private val PROMO_RE = Regex(
            "إعلان|تشويق|تريلر|trailer|برومو|-teaser",
            RegexOption.IGNORE_CASE
        )

        /**
         * المقاطع القصيرة (Shorts) داخل قائمة غيرها: كلٌّ منها دقيقة أو أقل،
         * ولو عُدّت حلقات لصارت الصفحة كلها «حلقات قصيرة» والحلقة الحقيقية
         * الطويلة تقفز بينها. فنستبعدها **من موضعها لا من اسمها**: العنوان
         * الذي يبدأ بـ `Shorts` (وحده أو بعد مسافة) هو ما يسمّيه يوتيوب
         * نفسه.
         *
         * عمداً لا نُوسّع هذا النمط إلى كلمات مثل «ملخص» أو «أجمل اللحظات»
         * كما في ARY: تلك مُتحقَّق منها على عناوين ARY وحدها، وقد تطابق
         * كلمةً في اسم حلقة شرقية فتسقط حلقةً شرعية من صفها بصمت.
         */
        private val SHORTS_RE = Regex("""^\s*Shorts(\s|$)""", RegexOption.IGNORE_CASE)
    }

    override var name = "دراما قصيرة"
    override var mainUrl = "https://www.youtube.com/"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ar"

    // =============================== HTML / GET ===============================

    private fun ctx(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "ar")
            .put("gl", "US")
    )

    /**
     * نجلب صفحة HTML ونستخرج منها `ytInitialData`. نقرأ قوائم القنوات
     * والحلقات من هذا الكائن، بنفس مسارات `lockupViewModel`.
     */
    private suspend fun fetchInitialData(url: String): JSONObject {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val res = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Accept-Language" to "ar",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
                val html = res.text
                val data = ytInitialData(html)
                if (data != null) return data
                last = IllegalStateException("no ytInitialData in ${html.length} bytes")
            } catch (e: Exception) {
                last = e
            }
            if (attempt < 2) delay(400L * (attempt + 1))
        }
        throw last ?: IllegalStateException("fetch $url failed")
    }

    /**
     * الصفحة الثانية من قوائم التشغيل لا تأتي عبر GET (معامل continuation
     * يتجاهله يوتيوب في HTML)، بل InnerTube POST فقط. نستخدم POST للمتابعة
     * حصراً، مع حارس: إن فشل لا نُسقط القوائم التي جمعناها أصلاً.
     */
    private suspend fun continuationPage(token: String): JSONObject? {
        val body = JSONObject()
            .put("context", ctx())
            .put("continuation", token)
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                val res = app.post(
                    BROWSE_URL,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Content-Type" to "application/json",
                        "Accept-Language" to "ar"
                    ),
                    json = body
                )
                val text = res.text.trim()
                if (text.startsWith("{")) return JSONObject(text)
                last = IllegalStateException("non-JSON reply (${text.length} bytes)")
            } catch (e: Exception) {
                last = e
            }
            if (attempt < 1) delay(400L)
        }
        if (last != null) Log.w(TAG, "continuation page failed: ${last.message}")
        return null
    }

    /**
     * يستخرج كائن `ytInitialData` من HTML: نجد بداية الكائن بلا الاعتماد على
     * شكل المتغير، ونطابق الأقواس المتوازنة مع مراعاة الأوتار المهرَّبة.
     */
    private fun ytInitialData(html: String): JSONObject? = balancedJson(html, "ytInitialData")

    private fun ytPlayerResponse(html: String): JSONObject? = balancedJson(html, "ytInitialPlayerResponse")

    /** يجد كائن JSON مسمّىً في HTML ويقرؤه بمطابقة الأقواس المتوازنة. */
    private fun balancedJson(html: String, name: String): JSONObject? {
        val markers = listOf(
            "var $name = ",
            "window[\"$name\"] = ",
            "\"$name\"] = ",
            "$name = "
        )
        for (m in markers) {
            val idx = html.indexOf(m)
            if (idx < 0) continue
            val start = idx + m.length
            if (start >= html.length || html[start] != '{') continue
            var depth = 0
            var i = start
            var inStr = false
            var esc = false
            while (i < html.length) {
                val c = html[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                return try {
                                    JSONObject(html.substring(start, i + 1))
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                    }
                }
                i++
            }
        }
        return null
    }

    /** يجمع كل القيم الواقعة تحت مفتاح معيّن أينما وردت في الشجرة المتشعّبة. */
    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out.add(it) }
                for (k in node.keys()) collect(node.opt(k), key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun grab(node: JSONObject, key: String): List<JSONObject> =
        mutableListOf<JSONObject>().also { collect(node, key, it) }

    /** أول توكن «متابعة» في استجابة القناة — لسحب الصفحة الثانية. */
    private fun continuationTokenOf(node: JSONObject): String? =
        grab(node, "continuationCommand")
            .firstNotNullOfOrNull { it.optString("token").ifBlank { null } }

    // ============================ lockupViewModel ============================
    // الشكل الحديث من يوتيوب. البديل القديم (playlistVideoRenderer /
    // videoRenderer) يعود فارغاً في هذه الاستجابات تماماً.

    private data class Lockup(
        val id: String,
        val type: String,
        val title: String,
        val thumb: String?,
        /** عدد الفيديوهات كما يصرّح به يوتيوب، للتمييز بين قوائم. */
        val count: Int = 0
    )

    private fun lockupTitle(l: JSONObject): String =
        l.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
            ?.optJSONObject("title")
            ?.optString("content", "")
            .orEmpty()

    /**
     * موضع الغلاف يختلف باختلاف نوع العنصر:
     *  - فيديو  : `contentImage.thumbnailViewModel.image.sources`
     *  - قائمة  : `contentImage.collectionThumbnailViewModel.primaryThumbnail.…`
     * لذلك نبحث عن أي `thumbnailViewModel` تحت contentImage بدل تثبيت مسار
     * واحد، وإلا رجعت أغلفة القوائم فارغة.
     */
    private fun lockupThumb(l: JSONObject): String? {
        val root = l.optJSONObject("contentImage") ?: return null
        for (tv in grab(root, "thumbnailViewModel")) {
            val sources = tv.optJSONObject("image")?.optJSONArray("sources") ?: continue
            for (i in sources.length() - 1 downTo 0) {      // الأخير = الأعلى جودة
                val u = sources.optJSONObject(i)?.optString("url", "").orEmpty()
                if (u.isNotBlank()) return u
            }
        }
        return null
    }

    private fun lockupsOf(node: JSONObject): List<Lockup> =
        grab(node, "lockupViewModel").mapNotNull { l ->
            val id = l.optString("contentId", "").ifBlank { return@mapNotNull null }
            Lockup(
                id, l.optString("contentType", ""), clean(lockupTitle(l)),
                lockupThumb(l), lockupCount(l)
            )
        }

    /** الشارة تسمّي العنصر «فيديو» أو «حلقة» حسب القائمة. */
    private val COUNT_RE = Regex("""(\d[\d,]*)\s*(?:فيديو|حلقة)""")

    /**
     * «40 فيديو» — شارة على غلاف القائمة، في
     * `contentImage.…thumbnailViewModel.overlays[].thumbnailBadgeViewModel.text`.
     */
    private fun lockupCount(l: JSONObject): Int {
        val root = l.optJSONObject("contentImage") ?: return 0
        for (badge in grab(root, "thumbnailBadgeViewModel")) {
            val t = badge.optString("text", "")
            COUNT_RE.find(t)?.let {
                return it.groupValues[1].replace(",", "").toIntOrNull() ?: 0
            }
        }
        return 0
    }

    /** العناوين تصل أحياناً بعلامة BOM أو مسافات زائدة أو ‎&‎ مطموسة. */
    private fun clean(s: String): String =
        s.replace(BOM, "").replace("\\u0026", "&").trim()

    /** تطبيع المسافة المتصدّرة من بعض أسماء القنوات. */
    private fun normalize(s: String): String =
        s.replace(Regex("[\\u200E\\u200F\\u200D]"), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * «مسلسل نهاية قلبي» و«نهاية قلبي | ARY» كلاهما «نهاية قلبي». نفصل عند
     * الأول من هذه الفواصل فقط — وتُبقي سطوراً مثل «الأ completing» و«2026»
     * سليمة (فاصلة تفصلها طبيعية).
     */
    private fun bareName(title: String): String {
        var t = normalize(title).substringBefore('|').substringBefore('–').substringBefore(" - ")
        if (t.startsWith("مسلسل ")) t = t.removePrefix("مسلسل ").trim()
        return t
    }

    /**
     * مفتاح التطابق: بلا تشكيل، والهمزات والألفات والألف المقصورة موحّدة،
     * والتاء المربوطة كالهاء. تُستعمل للبحث فقط — لا لإسقاط قوائم.
     */
    private fun keyOf(title: String): String {
        val sb = StringBuilder()
        for (c in bareName(title)) {
            when (c) {
                in 'ً'..'ْ', 'ـ', 'ٰ' -> {}      // تشكيل وتطويل
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ء' -> {}
                ' ' -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString().trim()
    }

    private fun posterOf(id: String): String = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private fun playlistUrl(id: String): String = "https://www.youtube.com/playlist?list=$id"

    /**
     * المسلسلات في تبويب القوائم نوعان من يوتيوب: PLAYLIST لأغلبها، وSHOW
     * للمسلسلات الجديدة/القادمة. نقبلهما — وكلٌّ منهما يُفتح بصفحة قائمة
     * اعتيادية. (LOCKUP_CONTENT_IMAGE مذكور هنا توثيقاً: قد يرد كـ contentType
     * شبيه، وهو مقبول أصلاً لأنه يبدأ بـ LOCKUP_.)
     */
    private fun isPlaylistOrShow(type: String): Boolean =
        type.contains("PLAYLIST") || type.contains("SHOW")

    private fun isPromo(title: String): Boolean = PROMO_RE.containsMatchIn(title)

    // ============================== playlists ==============================

    private data class PlaylistInfo(
        val id: String,
        val title: String,
        val cover: String?,
        val count: Int = 0
    )

    // ============================== extra channels ==============================

    /**
     * قنوات إضافية من إعدادات المصدر (رابطٌ في كل سطر). كلٌّ منها يظهر ضمن
     * قسمٍ واحد «مقترحاتك»، وتشملها نتائج البحث.
     *
     * الصيغة المقبولة لكل سطر:
     *   - رابط/معرّف قناة فقط: `UC…` أو `/channel/UC…` أو `/@handle` أو `@handle`
     *   - اسمٌ مخصص: `الاسم | الرابط`.
     *
     * نفس كود ARY (`extraChannels`) حرفياً.
     */
    private data class ExtraChannel(val label: String, val url: String)

    private fun extraChannels(): List<ExtraChannel> {
        val raw = prefs?.getString(ShortDramaSettings.KEY_EXTRA_CHANNELS, "")
            ?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val out = mutableListOf<ExtraChannel>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            var customName: String? = null
            var rest = trimmed
            val pipe = trimmed.split('|')
            if (pipe.size >= 2) {
                val a = pipe[0].trim()
                val b = pipe.subList(1, pipe.size).joinToString("|").trim()
                if (a.isNotEmpty() && (b.startsWith("http") || b.startsWith("UC") || b.startsWith("@") || b.contains("/channel/") || b.contains("youtube.com"))) {
                    customName = a
                    rest = b
                }
            }
            val cid = Regex("""UC[\w-]{22}""").find(rest)?.value
            val handle = Regex("""@([\w.-]+)""").find(rest)?.groupValues?.get(1)
            val url = when {
                cid != null -> "https://www.youtube.com/channel/$cid/playlists"
                handle != null -> "https://www.youtube.com/@$handle/playlists"
                else -> null
            }
            if (url != null) {
                val label = customName ?: (handle?.let { "@$it" } ?: cid ?: "")
                if (out.none { it.url == url }) out.add(ExtraChannel(label, url))
            }
        }
        return out
    }

    /**
     * قوائم قناة: صفحة tab «قوائم التشغيل» تعرض أول 30، ثم نتابع بطلب صفحة
     * ثانية للاسترداد الباقي. إن فشلت الصفحة الثانية نحتفظ بالأولى.
     *
     * نُرجع `null` — لا قائمة فارغة — عند فشل الطلب، ليميّز المستدعي بين
     * «القناة بلا قوائم» و«الطلب لم يصل».
     */
    private suspend fun channelPlaylists(url: String): List<PlaylistInfo>? {
        val out = mutableListOf<PlaylistInfo>()
        try {
            val first = fetchInitialData(url)
            for (l in lockupsOf(first)) {
                if (!isPlaylistOrShow(l.type)) continue
                if (out.any { it.id == l.id }) continue
                out.add(PlaylistInfo(l.id, l.title, l.thumb, l.count))
            }
            val token = continuationTokenOf(first)
            val second = token?.let { continuationPage(it) }
            if (second != null) {
                for (l in lockupsOf(second)) {
                    if (!isPlaylistOrShow(l.type)) continue
                    if (out.any { it.id == l.id }) continue
                    out.add(PlaylistInfo(l.id, l.title, l.thumb, l.count))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playlists tab failed: ${e.message}")
            return null
        }
        return out
    }

    /**
     * قوائم المصدر كلها: القنوات الاثنتا عشرة المدمجة + كل قناة أضافها
     * المستخدم.
     *
     * ⚠️ التسلسل مقصود: 12 قناة = ~24 طلباً، ومتوازياً يردّ يوتيوب صفر
     * قوائم بسهولة تحت الضغط. كل قناةRequest تُعزل: فشلُ واحدة يمرّ ولا
     * يُسقط الباقي.
     */
    private data class SourceLists(
        /** كل القوائم بلا تكرار (بمعرّف القائمة) — هذا ما يبحث فيه `search`. */
        val all: List<PlaylistInfo>,
        /** قوائم القنوات المدمجة — صفّ «مسلسلات دراما قصيرة». */
        val builtin: List<PlaylistInfo>,
        /** قوائم إضافات المستخدم — صفّ «مقترحاتك». */
        val extra: List<PlaylistInfo>,
        /** قناة لم يصل طلبها — نُعيد المحاولة لاحقاً بدل تثبيت فراغها. */
        val failed: Set<String>
    )

    @Volatile
    private var cachedSource: SourceLists? = null

    @Volatile private var lastPartial: SourceLists? = null
    @Volatile private var lastFailedAt = 0L

    /** متى جُلبت القوائم بنجاح آخر مرة — يحدّد انتهاء صلاحية الحفظ. */
    @Volatile private var cachedAt = 0L

    /** آخر حلقات ناجحة لكل قائمة، ومتى جُلبت — لكل قائمةٍ مدتها الخاصة. */
    private val episodeItems = HashMap<String, Pair<List<Lockup>, Long>>()

    /**
     * الفيديوهات التي أُصدِرت ترجمتها في نداء `loadLinks` الجاري — حارس
     * تكرار لا ذاكرة: `resolveFromNewPipe` قد تُستدعى ثلاث مرات، فبلا هذا
     * الحارس تنزل نفس الترجمة ثلاثاً. يُمسح في أول `loadLinks`.
     */
    private val subsEmittedFor = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * يبطل كل ما خُزّن عند تغيير إعدادات القنوات: اللائحة الجديدة تعني
     * قوائمَ مختلفة، فلا يجوز أن تُقدَّم بياناتُ القديم حتى تنتهي المهلة.
     */
    private var prefsWatcher: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun watchChannelSetting() {
        val p = prefs ?: return
        if (prefsWatcher != null) return
        val w = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != ShortDramaSettings.KEY_EXTRA_CHANNELS) return@OnSharedPreferenceChangeListener
            Log.d(TAG, "extra channels changed — dropping cached playlists")
            cachedSource = null
            cachedAt = 0L
            lastPartial = null
            lastFailedAt = 0L
            synchronized(episodeItems) { episodeItems.clear() }
        }
        prefsWatcher = w
        runCatching { p.registerOnSharedPreferenceChangeListener(w) }
            .onFailure { Log.w(TAG, "cannot watch prefs: ${it.message}") }
    }

    /** عمر حفظ الحلقات قبل إعادة جلبها — قصير عمداً لتظهر الحلقات الجديدة. */
    private val EPISODE_FRESH_MS = 3 * 60 * 1000L

    private val RETRY_COOLDOWN_MS = 20_000L

    /**
     * عمر حفظ القوائم. خمس دقائق — نفس ARY. ليس للسرعة وحدها: فتحُ مصدر
     * واحد لكل 12 قناة يستغرق وقتاً، وتحديثُ الواجهة يجب أن ينتهي بلا
     * انتظار.
     */
    private val FRESH_MS = 5 * 60 * 1000L

    /**
     * سقف زمني لقناةٍ واحدة.
     *
     * كان نداء الصفحة الرئيسية ينتظر 12 قناة × طلبين (~24 طلباً) **بالكامل**
     * قبل أن يُظهر شيئاً، فكانت الصفحة «تأخّر» كل مرة، وأسوأ: قناةٌ واحدة
     * بطيئة كانت تجمّدها دقيقةً كاملة. ARY قناةٌ واحدة فلم يظهر عنده هذا.
     * القطع عند هذا الحد يجعل الأسوأ انتظاراً محدوداً: القناة البطيئة
     * تُتخطّى وتُحاول لاحقاً (انظر `failed` في `SourceLists`).
     */
    private val CHANNEL_TIMEOUT_MS = 12_000L

    /**
     * كم قناة نجلبها في اللحظة الواحدة.
     *
     * 12 دفعةً واحدة = ضغطٌ على يوتيوب يعيد صفر قوائم (وهو ما دفعنا
     * للتسلسل أصلاً)، و2 لكل دفعة = 6 أدوار تسلسلية. أربعٌ توازن بينهما.
     */
    private val FETCH_BATCH = 4

    /**
     * ميزانية الصفحة الرئيسية كلّها.
     *
     * سقف القناة (12 ث) × ثلاث دفعات = 36 ث أسوأ حالة، وهذا ما زال
     * «تأخّراً» يقنع المستخدم أن التطبيق معلّق. فبعد هذه الميزانية
     * **نُعيد ما وصل** ونترك الباقي للدفعة التالية: الصف يظهر بما في
     * يده فوراً، والقنوات المتبقية تظهر عند التحديث التالي.
     */
    private val HOME_BUDGET_MS = 22_000L

    /**
     * ميزانية الدفعة الأولى فقط — وهي ما يظهر عند أول فتح.
     *
     * الهدف أن **الصف يظهر بسرعة** كصفحة ARY: عند أول فتح لا يوجد
     * شيء مخزّن بعد، فنجلب الدفعة الأولى وحدها (≈7 ث) ونعرض ما وصل، ثم
     * تكمل البقية في الخلفية (انظر `refreshSourceListsAsync`). بعد أول
     * فتح، كل الفتحات التالية ترجع فوراً من الذاكرة بلا أي انتظار.
     */
    private val FIRST_BATCH_BUDGET_MS = 7_000L

    /**
     * قوائم مجموعة قنوات: `url -> null` يعني «لم تصل» (فشل أو تجاوز
     * السقف الزمني أو تجاوز الميزانية)، والفرق بين الفشل والنجاح مقصود:
     * الفشل يُعاد محاولته لاحقاً، ولا يُثبَّت فراغاً أبداً.
     */
    private suspend fun fetchChannels(
        targets: List<Pair<String, String>>,
        budgetMs: Long = HOME_BUDGET_MS
    ): Map<String, List<PlaylistInfo>?> = coroutineScope {
        val out = HashMap<String, List<PlaylistInfo>?>()
        val startedAt = System.currentTimeMillis()

        // دفعات: متوازٍ داخلها، تسلسلي بينها.
        for (batch in targets.chunked(FETCH_BATCH)) {
            // تجاوزنا الميزانية: ما لم يُجلب بعد يُبقى `null` = يُعاد لاحقاً.
            if (System.currentTimeMillis() - startedAt > budgetMs) {
                Log.w(
                    TAG,
                    "home budget spent after ${batch.firstOrNull()?.first ?: "?"} — " +
                        "deferring ${targets.size - out.size} channel(s)"
                )
                break
            }
            batch.map { (label, url) ->
                async {
                    val pls = withTimeoutOrNull(CHANNEL_TIMEOUT_MS) {
                        try {
                            channelPlaylists(url)
                        } catch (e: Exception) {
                            Log.w(TAG, "'$label' failed: ${e.message}")
                            null
                        }
                    }
                    if (pls == null) Log.w(TAG, "no playlists from '$label'")
                    url to pls
                }
            }.awaitAll().forEach { (url, pls) -> out[url] = pls }
        }
        out
    }

    /** جلبٌ واحد لكل قناة: الاثنتا عشرة المدمجة ثم إضافات المستخدم. */
    private suspend fun loadSourceLists(budgetMs: Long = HOME_BUDGET_MS): SourceLists {
        val extras = extraChannels()
        val builtin = mutableListOf<PlaylistInfo>()
        val extra = mutableListOf<PlaylistInfo>()
        val failed = mutableSetOf<String>()
        val seen = HashSet<String>()

        val results = fetchChannels(BASE_CHANNELS + extras.map { it.label to it.url }, budgetMs)

        for ((_, url) in BASE_CHANNELS) {
            val pls = results[url]
            if (pls == null) failed.add(url) else for (p in pls) if (seen.add(p.id)) builtin.add(p)
        }
        for (ch in extras) {
            val pls = results[ch.url]
            if (pls == null) failed.add(ch.url) else for (p in pls) if (seen.add(p.id)) extra.add(p)
        }

        return SourceLists(builtin + extra, builtin, extra, failed)
    }

    /**
     * القوائم المحفوظة. لا نحفظ إلا إذا نجحت **كل** القنوات: فشلُ قناةٍ
     * عابرٌ كان يُثبّت فراغها فيبقى قسمُها ونتائجُها مخفيّة حتى إعادة تشغيل
     * التطبيق.
     */
    private suspend fun sourceLists(): SourceLists {
        val now = System.currentTimeMillis()
        cachedSource?.let { if (now - cachedAt < FRESH_MS) return it }

        // ★ ما تُخزّن من قبل؟ رجّعه فوراً وحدّث في الخلفية — النمط نفسه في
        //   ARY، وهو ما جعل page الرئيسية تظهر سريعاً. بدونه كان نداء
        //   `getMainPage` يُexpect 12 channel (~2s كل واحدة) قبل أن يُظهر
        //   أي صف، فكانت Page الرئيسية «تأخّر» كل فتحة.
        val stale = lastPartial ?: cachedSource
        if (stale != null) {
            refreshSourceListsAsync()
            return stale
        }

        // أول تحميل: لا شيء مخزّن، فاستخدم الميزانية المحدودة (الدفعة الأولى).
        val src = loadSourceLists(budgetMs = FIRST_BATCH_BUDGET_MS)
        Log.d(
            TAG,
            "playlists: builtin=${src.builtin.size} extra=${src.extra.size} " +
                "failed=${src.failed.size} total=${src.all.size}"
        )
        if (src.failed.isEmpty() && src.all.isNotEmpty()) {
            cachedSource = src
            cachedAt = now
            lastPartial = null
        } else {
            lastFailedAt = now
            lastPartial = src
        }
        return src
    }

    /**
     *حدّث القواعد في الخلفية دون إيقاف Page الرئيسية: يُشغّل
     * `loadSourceLists` على IO، وعند نجاحه يُخزّن النتيجة (`cachedSource`
     * أو `lastPartial`) فيُظهرها الفتحة التالية فوراً.
     *
     * حارس تكرار: دعوة متزامنة تُشغّل وظيفة واحدة فقط، ولا تُضاعف
     * number of طلبات يوتيوب.
     */
    @Volatile private var refreshing = false
    private val cacheLock = Any()

    private fun refreshSourceListsAsync() {
        if (refreshing) return
        refreshing = true
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val src = loadSourceLists()
                val now = System.currentTimeMillis()
                synchronized(cacheLock) {
                    if (src.failed.isEmpty() && src.all.isNotEmpty()) {
                        cachedSource = src
                        cachedAt = now
                        lastPartial = null
                    } else {
                        lastFailedAt = now
                        lastPartial = src
                    }
                }
                Log.d(
                    TAG,
                    "background refresh: builtin=${src.builtin.size} " +
                        "extra=${src.extra.size} failed=${src.failed.size}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "background refresh failed: ${e.message}")
            } finally {
                refreshing = false
            }
        }
    }

    private suspend fun allPlaylists(): List<PlaylistInfo> = sourceLists().all

    /**
     * حلقات قائمة: يوتيوب يُعيدها كاملةً في الصفحة الأولى (60–100+).
     *
     * تُحفظ الحلقات مدّةً قصيرة كي تظهر الجديدة أوّل بأوّل. الحفظ يُلغى عند
     * فشل الجلب (لا يُثبَّت فراغٌ أبداً)، ويُحذف عند تغيير إعدادات القنوات.
     */
    private suspend fun playlistItems(playlistId: String): List<Lockup> {
        val now = System.currentTimeMillis()
        val hit = synchronized(episodeItems) { episodeItems[playlistId] }
        if (hit != null && now - hit.second < EPISODE_FRESH_MS) return hit.first

        val items = try {
            fetchInitialData("https://www.youtube.com/playlist?list=$playlistId")
                .let { lockupsOf(it).filter { l -> l.type.contains("VIDEO") } }
        } catch (e: Exception) {
            Log.w(TAG, "playlist $playlistId failed: ${e.message}")
            // آخر قائمةٍ ناجبة أفضل من لا شيء — ولا تُحفظ هنا.
            return hit?.first ?: emptyList()
        }
        if (items.isEmpty()) return hit?.first ?: emptyList()

        synchronized(episodeItems) { episodeItems[playlistId] = items to now }
        return items
    }

    /**
     * يحوّل عناصر القائمة إلى حلقات مرقّمة **بترتيبها**.
     *
     * الترتيب الوضعي (انظر تعليق الصنف): القنوات لا تكتب أرقام حلقات في
     * العناوين، فالترتيب الذي نشرت به القائمة هو الترقيم الصحيح.
     *
     * Shorts تُستبعد من موضعها: لو أُبقيت لأصبحت كل صفحة «حلقات قصيرة».
     */
    private fun episodesOf(items: List<Lockup>): List<Pair<Int, Lockup>> {
        val kept = items.filterNot { isShorts(it.title) }
        val out = mutableListOf<Pair<Int, Lockup>>()
        for ((i, l) in kept.withIndex()) out.add((i + 1) to l)
        return out
    }

    private fun isShorts(title: String): Boolean = SHORTS_RE.containsMatchIn(normalize(title))

    // ============================== main page ==============================

    /**
     * الصفحة الرئيسية = قوائم القنوات. ثلاثة صفوف ثابتة مهما زاد عدد القنوات:
     *   1) «مسلسلات دراما قصيرة» — كل القنوات المدمجة مجتمعة.
     *   2) «إعلانات وتشويقات»     — صف مستقل (لا يُدمج بترويسة).
     *   3) «مقترحاتك»             — ما أضافه المستخدم من الإعدادات.
     *
     * لا نضيف صفاً من تبويب «الفيديوهات»: ترتيبه زمني ومختلط، فيبدو كأن
     * القناة مسلسل واحد — وهذا ما جعل صفّ الفيديوهات بطيءً وغير مفيد في ARY.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val lists = mutableListOf<HomePageList>()

        watchChannelSetting()
        val src = sourceLists()

        // صفّ المسلسلات — كل القنوات المدمجة مجتمعة.
        val series = src.builtin.filter { !isPromo(it.title) }
        if (series.isNotEmpty()) {
            val cards = series.map { p ->
                newTvSeriesSearchResponse(bareName(p.title), playlistUrl(p.id)) {
                    this.posterUrl = p.cover
                }
            }
            lists.add(HomePageList("مسلسلات دراما قصيرة", cards))
        }

        // صفّ الإعلانات والتشويقات — مستقل، لمّا كان «ترويجي» و«تشويقي» إعلانان
        // مختلفان وكلاهما يُعرض. القنوات المدمجة أولاً، ثم إضافات المستخدم.
        val promos = (src.builtin + src.extra).filter { isPromo(it.title) }
            .distinctBy { it.id }
            .map { p ->
                newTvSeriesSearchResponse(clean(p.title), playlistUrl(p.id)) {
                    this.posterUrl = p.cover
                }
            }
        if (promos.isNotEmpty()) {
            lists.add(HomePageList("إعلانات وتشويقات", promos))
        }

        // صفّ إضافات المستخدم.
        val extra = src.extra.filter { !isPromo(it.title) }
        if (extra.isNotEmpty()) {
            val cards = extra.map { p ->
                newTvSeriesSearchResponse(bareName(p.title), playlistUrl(p.id)) {
                    this.posterUrl = p.cover
                }
            }
            lists.add(HomePageList("مقترحاتك", cards))
        }
        return newHomePageResponse(lists)
    }

    // =============================== search ===============================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    /**
     * البحث مطابقةٌ نصية داخل القوائم المحفوظة، لا طلب شبكة — مطابق لـ ARY.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val q = clean(query)
        if (q.isBlank()) return emptyList()

        val playlists = allPlaylists()
        if (playlists.isEmpty()) return emptyList()

        // كل كلمة في السؤال يجب أن ترد في اسم المسلسل. التوحيد يجعل
        // «الانين» تجد «الأنين» و«اني» تجد «أناني».
        val words = keyOf(q).split(' ')
            .filter { it.length > 1 && it != "مسلسل" && it != "حلقه" }

        val out = hitsIn(playlists, words, keyOf(q)).map { p ->
            newTvSeriesSearchResponse(bareName(p.title), playlistUrl(p.id)) {
                this.posterUrl = p.cover
            }
        }
        Log.d(TAG, "search '$q' -> ${out.size}")
        return out
    }

    /** مطابقة نصية داخل قائمة القوائم. */
    private fun hitsIn(pool: List<PlaylistInfo>, words: List<String>, queryKey: String): List<PlaylistInfo> {
        if (words.isEmpty())
            return pool.filter { keyOf(it.title).contains(queryKey) }
        return pool.filter { p ->
            val hay = keyOf(p.title)
            words.all { hay.contains(it) }
        }
    }

    // ================================ load ================================

    /**
     * ملاحظة مهمة: CloudStream يمرّر كل رابط واصل هنا عبر `fixUrl` قبل
     * `load()` (في APIRepository.load). `fixUrl` لا يمسك روابطنا الداخلية
     * `sdar://`، فيُلصق عليها `mainUrl` فيصبح الرابط
     * `https://www.youtube.com/sdar://pl/…` ويفشل الفرع. لذلك:
     *  - الصفحة الرئيسية تُبنى بروابط يوتيوب حقيقية `?list=` (لا يكسّرها fixUrl)
     *  - ونتقبّل معرّف القائمة من موضع أيقونته في الرابط (مكسورٍ أو صافٍ)
     *    حتى تظل المسلسلات المحفوظة/المشارَكة قديمة تعمل.
     */
    override suspend fun load(url: String): LoadResponse? {
        // 1) روابطنا الداخلية + أي رابط يحمل sdar://pl/ مهما كان موضعه.
        if (url.contains("sdar://pl/")) {
            val pid = url.substringAfter("sdar://pl/").trim()
            if (pid.isBlank()) return null
            val info = allPlaylists().firstOrNull { it.id == pid }
                ?: PlaylistInfo(pid, "مسلسل", null)
            return loadPlaylist(info)
        }

        // 2) روابط يوتيوب الحقيقية (؟list=) — الصفحة الرئيسية والروابط
        //    المفتوحة من خارج الإضافة.
        Regex("""[?&]list=([\w-]+)""").find(url)?.let { m ->
            val pid = m.groupValues[1]
            val info = allPlaylists().firstOrNull { it.id == pid }
                ?: PlaylistInfo(pid, "مسلسل", null)
            return loadPlaylist(info)
        }

        // 3) فيديو مفرد ?v= يفتح كمشاهدة، بشاشة LoadResponse الملائمة.
        Regex("""[?&]v=([\w-]{11})""").find(url)?.let { m ->
            val vid = m.groupValues[1]
            return newMovieLoadResponse("فيديو", url, TvType.Movie, vid) {
                this.posterUrl = posterOf(vid)
            }
        }
        return null
    }

    private suspend fun loadPlaylist(info: PlaylistInfo): LoadResponse? {
        val items = playlistItems(info.id)
        val eps = episodesOf(items)
        if (eps.isEmpty()) return null

        val name = bareName(info.title).ifBlank { "مسلسل" }
        val poster = eps.firstOrNull()?.second?.thumb ?: info.cover

        val episodes = eps.map { (num, l) ->
            // الحلقة تُمرّر عبر رابط حقيقي لكي لا يلصق fixUrl عليه mainUrl.
            //
            // ★ ااسم الحلقة: عنوان الفيديو (كما نشره الناشر) مع الرقم
            //   الترتيبي كتمييز. القنوات هنا لا تكتب أرقام في العناوين (انظر
            //   تعليق الصنف)، فعنوان الفيديو هو ما يميز هذه الحلقة عن غيرها
            //   من نفس DVR. بدونه تظهر كل الحلقات "الحلقة 1/2/3…" بلا
            //   فرق، في难 يميزها. فنضع الرقم أولاً ثم الاسم.
            val title = clean(l.title).ifBlank { "الحلقة $num" }
            newEpisode("https://www.youtube.com/watch?v=${l.id}") {
                this.name = "$num - $title"
                this.episode = num
                this.posterUrl = l.thumb ?: posterOf(l.id)
            }
        }

        Log.d(TAG, "playlist '${info.title}' -> ${episodes.size} episodes")

        return newTvSeriesLoadResponse(name, playlistUrl(info.id), TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ============================== loadLinks ==============================

    /** content playback nonce — يضيفه NewPipe لكل رابط (يوتيوب يتوقعه). */
    private fun randomCpn(): String {
        val chars = "0123456789abcdef"
        val r = java.util.Random()
        return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** رقم الجودة كتسمية (NewPipe قد يعطي height صفراً أحياناً). */
    private fun qualityLabelOf(vs: org.schabi.newpipe.extractor.stream.VideoStream): String {
        val height = runCatching { vs.height }.getOrNull() ?: 0
        if (height > 0) return height.toString()
        return "video"
    }

    /** كوديك الفيديو مختصراً للعرض: avc1→H.264، vp9→VP9، av01→AV1، … */
    private fun codecTag(mime: String?): String {
        val m = mime.orEmpty()
        return when {
            m.contains("av01") -> "AV1"
            m.contains("vp09") || m.contains("/vp9") -> "VP9"
            m.contains("avc1") || m.contains("/avc") -> "H.264"
            m.contains("webm") -> "VP9"
            m.contains("mp4") -> "H.264"
            else -> ""
        }
    }

    /** حجم ملف الفيديو من معامل `clen` في رابط googlevideo (بالبايت). */
    private fun byteSizeOf(url: String): Long {
        if (url.isEmpty()) return 0
        val i = url.indexOf("clen=")
        if (i < 0) return 0
        val after = url.substring(i + 5)
        val j = after.indexOf('&')
        val num = if (j >= 0) after.substring(0, j) else after
        return runCatching { num.toLongOrNull() ?: 0L }.getOrDefault(0L)
    }

    /**
     * اختيار الملف الصوتي المرافق لجودة فيديو معيّنة.
     *
     * هذه القنوات لا تملك مسارات صوتية بديلة (لا نسخة إنجليزية من الصوت
     * الأصلي المدبلج)، فترجمةُ «جميع الملفات الصوتية» عملياً: **أن يُختار
     * أيٌّ من تنسيقاتها** مع كل جودة، لا أن يُحصر في واحد.
     *
     * الافتراضي `best` هو السلوك نفسه: مواءمة كوديك الفيديو (webm ↔ webm،
     * mp4 ↔ mp4) ثم أعلى بت/ث.
     */
    private fun pickAudio(audios: List<SdAudioInfo>, video: SdStreamInfo, pref: String): SdAudioInfo? {
        val family = if (video.mimeType.contains("webm")) { a: SdAudioInfo ->
            a.mimeType.contains("webm")
        } else { a: SdAudioInfo ->
            a.mimeType.contains("mp4")
        }
        val compatible = audios.filter(family).ifEmpty { audios }
        if (pref.isEmpty() || pref == "best") {
            return compatible.maxByOrNull { it.bitrate }
        }
        return when (pref) {
            "opus" -> compatible.filter { it.mimeType.contains("webm") }
                .maxByOrNull { it.bitrate } ?: compatible.maxByOrNull { it.bitrate }
            "aac" -> compatible.filter { it.mimeType.contains("mp4") }
                .maxByOrNull { it.bitrate } ?: compatible.maxByOrNull { it.bitrate }
            "lowest" -> compatible.minByOrNull { it.bitrate }
            "low" -> compatible.sortedBy { it.bitrate }
                .getOrNull((compatible.size - 1) / 2) ?: compatible.maxByOrNull { it.bitrate }
            "high" -> compatible.sortedByDescending { it.bitrate }
                .getOrNull(1) ?: compatible.maxByOrNull { it.bitrate }
            else -> compatible.maxByOrNull { it.bitrate }
        }
    }

    /** «360 • 62MB (H.264)» — تسمية تُفرّق الجودات في منظار القائمة. */
    private fun richLabel(height: Int, url: String, mime: String?): String {
        val h = if (height > 0) height.toString() else "auto"
        val bytes = byteSizeOf(url)
        val mb = if (bytes > 0) "• ${"%.1f".format(bytes / 1048576.0)}MB" else ""
        val tag = codecTag(mime)
        val t = if (tag.isNotEmpty()) " ($tag)" else ""
        return "$h$mb$t"
    }

    /**
     * يصدّر ترجمةً واحدةً للمشغّل — تلقائياً، بلا إعداد (مطابق لـ ARY بعد
     * حذفه لقسم «الترجمة والصوت»).
     *
     * يوتيوب ينشر على هذه القنوات مسار ترجمةٍ واحداً أو بلا مسار إطلاقاً
     * (المدبلج عربي أصلي)، ويترك الباقي **غير منشور**: يوتيوب يولّدها عند
     * الطلب عبر معامل `tlang` على رابط `timedtext` الموقَّع. ولهذا بنينا
     * الجلب على `timedtext` مباشرةً لا على `getSubtitlesDefault()`.
     *
     * 1) الأصلية أولاً: رابط يوتيوب يُسلَّم للمشغّل مباشرةً بلا شرط نجاح
     *    جلب — فتبقى ظاهرة في القائمة دائماً ولو ردّ يوتيوب فارغاً لها.
     * 2) ثم العربية عبر `tlang` — تلقائياً بلا اختيار من المستخدم.
     *
     * الجلب عبر `HttpURLConnection` (HTTP/1.1) لا عبر `app.get`، لسبب
     * موثّق في `ShortDramaSubServer`: مكدّس يوتيوب يقتل بعض الطلبات على
     * أجهزة بعينها بينما يردّ HTTP/1.1 سليماً. النص يُثبَّت محلياً على خادم
     * محلي ثم يُسلَّم رابط `127.0.0.1` — لأن `SubtitleFile` لا يحمل محتوىً،
     * ولأن المشغّل يرفض عناوين `data:`.
     *
     * الفشل يُبتلَع بصمت: ترجمةٌ غير ظاهرة أفضل من استثناءٍ يصل المشغّل.
     */
    private suspend fun emitSubtitle(
        vid: String,
        extractor: YoutubeStreamExtractor,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        try {
            // حارس التكرار: `resolveFromNewPipe` قد تُستدعى حتى ثلاث مرات
            // في نداء `loadLinks` واحد، فبلا هذا الحارس تنزل نفس الترجمة
            // ثلاثاً في قائمة المشغّل.
            if (subsEmittedFor.putIfAbsent(vid, true) != null) return

            val tracks = runCatching { extractor.subtitlesDefault }
                .getOrNull()?.filterNotNull() ?: return
            if (tracks.isEmpty()) return

            // 1) الأصلية — كما في ARY تماماً.
            val original = tracks.firstOrNull { !it.url.isNullOrBlank() }
            if (original != null) {
                subtitleCallback(
                    newSubtitleFile(original.locale?.language ?: "ar", original.url!!) {
                        this.headers = mapOf("Referer" to "https://www.youtube.com/")
                    }
                )
            }

            // 2) ثم العربية التلقائية. يوتيوب لا ينشرها، فتُولَّد عند الطلب.
            val base = original?.url?.takeIf { it.isNotBlank() } ?: return
            val text = fetchSubtitleText(
                "$base&fmt=vtt&tlang=ar",
                "https://www.youtube.com/watch?v=$vid"
            )
            if (text.isBlank()) return

            val local = ShortDramaSubServer.register(text) ?: return
            subtitleCallback(
                newSubtitleFile("ar", local) {
                    this.headers = mapOf("Referer" to "https://www.youtube.com/")
                }
            )
            Log.d(TAG, "$vid subtitle ok lang=ar bytes=${text.length}")
        } catch (e: Exception) {
            Log.w(TAG, "$vid subtitle skipped: ${e.message}")
        }
    }

    /** جلب نص الترجمة عبر HTTP/1.1 — يُرجع فارغاً عند أي فشل. */
    private fun fetchSubtitleText(url: String, referer: String): String =
        try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", referer)
            conn.setRequestProperty("Accept", "text/vtt, text/plain, */*")
            val rc = conn.responseCode
            if (rc !in 200..299) { Log.w(TAG, "timedtext http $rc"); "" }
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "timedtext failed: ${e.message}")
            ""
        }

    /**
     * مسار تشغيل مطابق لسيرفرات إضافة «يوتيوب»: `YoutubeStreamExtractor.fetchPage()`
     * يعطي روابط googlevideo **مفكوكة التوقيع ومعامل n** بالإضافة إلى نطاقات
     * Initialization/Index لكل جودة، ثم نبني منها مانيفست DASH محلي (`127.0.0.1`)
     * يعرض كل جودة مع أفضل صوت لها، فيتلقى يوتيوب طلبات Range شرعية — لا ترفض
     * 2004.
     *
     * NewPipe نفسه لا يُضمَّن في الـ cs3؛ تطبيق CloudStream يقدّمه وقت التشغيل.
     */
    private suspend fun resolveFromNewPipe(
        vid: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var produced = 0
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(watchUrl)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            val dur = runCatching { s.length }.getOrNull() ?: 0
            val durationSeconds = if (dur > 0) dur else 3600L

            val seenUrls = mutableSetOf<String>()

            // جودات الفيديو (video-only): نُبقي كل تنسيقٍ مميز (ارتفاعه
            // وكوديكه: avc وvp9 وav1) بدل طيّ كل ارتفاع إلى تنسيقٍ واحد، فيظهر
            // في اللاعب كل الجودات المتاحة بدل جزءٍ منها.
            val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val streamUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(streamUrl)) return@mapNotNull null

                    val label = qualityLabelOf(vs)
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    var mime = vs.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = ShortDramaDashServer.mimeFromUrl(streamUrl, false)

                    val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                    val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                    SdStreamInfo(streamUrl, mime, height, label, initR, indexR, codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

            val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                try {
                    val aUrl = asr.content ?: return@mapNotNull null
                    val bitrate = runCatching { asr.bitrate ?: 128000 }.getOrNull() ?: 128000
                    var mime = runCatching { asr.format?.mimeType }.getOrNull()
                    if (mime.isNullOrEmpty()) mime = ShortDramaDashServer.mimeFromUrl(aUrl, true)

                    val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                    val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null
                    var rawLang = runCatching { asr.audioTrackId ?: "Default" }.getOrNull() ?: "Default"
                    if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                    SdAudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase(), codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }
            val audiosByLanguage = audioInfoList.groupBy { it.language }

            // ★ لا شيء يُجلب عبر الشبكة هنا: الروابط تُبثّ فوراً أدناه، ثم
            //   تُطلق الترجمة في خيط منفصل. الجلبُ الشبكي للترجمة قبل
            //   إصدار الروابط كان يوقف `loadLinks` حتى 20 ثانية — فيبدو
            //   للمستخدم «الفيديو يدور ولا يشتغل».
            ShortDramaDashServer.ensureStarted()

            // إعداد «الجودات»: الأعلى فقط عند اختيار high.
            val effectiveVideos = if (qualityMode() == "high") {
                val top = videoOnlyList.maxByOrNull { it.height }
                if (top != null) videoOnlyList.filter { it.height == top.height } else videoOnlyList
            } else videoOnlyList

            // كل تنسيق فيديو يُبث — بلا استثناء — مقترناً بصوتٍ مختارٍ من
            // تنسيقاته. عند غياب الصوت نُبث الفيديو وحده بدل أن يسقط التنسيق.
            for (video in effectiveVideos) {
                val bestAudio = if (audiosByLanguage.isNotEmpty()) {
                    val lang = audiosByLanguage.keys.firstOrNull()
                    audiosByLanguage[lang]?.let { audios ->
                        pickAudio(audios, video, audioPref())
                    }
                } else null

                val label = if (bestAudio != null && audiosByLanguage.size > 1)
                    "${richLabel(video.height, video.url, video.mimeType)} (${bestAudio.language})"
                else richLabel(video.height, video.url, video.mimeType)

                val localLink = ShortDramaDashServer.buildAndRegister(
                    video, if (bestAudio != null) listOf(bestAudio) else emptyList(),
                    durationSeconds
                )
                if (localLink != null) {
                    callback(
                        newExtractorLink(
                            "دراما قصيرة",
                            label,
                            localLink,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = mainUrl
                            this.quality = video.height
                        }
                    )
                    produced++
                }
            }

            // مقاطع مدمجة (muxed) — احتياط فقط: إن لم يُنتج مسار DASH أعلاه
            // أي رابط (فيديو بلا video-only).
            if (produced == 0) {
                val muxedList = (s.videoStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val mUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(mUrl)) return@mapNotNull null
                        Triple(mUrl, qualityLabelOf(vs), runCatching { vs.height ?: 0 }.getOrNull() ?: 0)
                    } catch (e: Exception) { null }
                }
                val effectiveMuxed = if (qualityMode() == "high") {
                    muxedList.maxByOrNull { it.third }?.let { listOf(it) } ?: muxedList
                } else muxedList
                effectiveMuxed.forEach { (mUrl, mLabel, mHeight) ->
                    callback(
                        newExtractorLink("دراما قصيرة", "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = mHeight
                        }
                    )
                    produced++
                }
            }

            Log.d(TAG, "$vid NewPipe DASH links=$produced qualities=${effectiveVideos.size}")

            // ★ الترجمة تأتي بعد الروابط، في coroutine منفصل على IO.
            if (produced > 0) {
                val ext = s
                val cb = subtitleCallback
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        emitSubtitle(vid, ext, cb)
                    } catch (e: Exception) {
                        Log.w(TAG, "$vid subtitle job: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$vid NewPipe resolve failed: ${e.message}")
        }
        return produced
    }

    /** بديل مباشر: صفحة watch.html ثم نستخرج ytInitialPlayerResponse. */
    private suspend fun resolveFromHtml(
        vid: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var produced = 0
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        try {
            val res = app.get(
                watchUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept-Language" to "ar",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
            val pr = ytPlayerResponse(res.text) ?: return 0
            val status = pr.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") {
                Log.w(TAG, "$vid playableStatus=$status (${pr.optJSONObject("playabilityStatus")?.optString("reason")})")
                return 0
            }
            fun emit(url: String, name: String, quality: Int, headers: Map<String, String> = mapOf()) {
                if (url.isBlank()) return
                // أسلوب NewPipe: إضافة cpn (content playback nonce) لكل رابط يوتيوب
                val withCpn = if (url.contains(".googlevideo.com")) {
                    if (url.contains("cpn=")) url
                    else url + (if (url.contains("?")) "&" else "?") + "cpn=" + randomCpn()
                } else url
                val link = ExtractorLink(
                    "دراما قصيرة",
                    name,
                    withCpn,
                    watchUrl,
                    quality,
                    headers,
                    null,
                    ExtractorLinkType.VIDEO,
                    emptyList<AudioFile>()
                )
                produced++
                callback(link)
                if (useRedirect() && withCpn.contains(".googlevideo.com") && !withCpn.contains("redirector.googlevideo.com")) {
                    val redirected = redirectHost(withCpn)
                    if (redirected != withCpn) {
                        val link2 = ExtractorLink(
                            "دراما قصيرة",
                            "$name · redirect",
                            redirected,
                            watchUrl,
                            quality,
                            headers,
                            null,
                            ExtractorLinkType.VIDEO,
                            emptyList<AudioFile>()
                        )
                        produced++
                        callback(link2)
                    }
                }
            }
            val sd = pr.optJSONObject("streamingData") ?: return 0
            val arr = ArrayList<JSONObject>()
            sd.optJSONArray("formats")?.let { for (i in 0 until it.length()) arr.add(it.getJSONObject(i)) }
            sd.optJSONArray("adaptiveFormats")?.let { for (i in 0 until it.length()) arr.add(it.getJSONObject(i)) }

            val abr = sd.optString("serverAbrStreamingUrl")
            val abrHeaders = mapOf("Referer" to "https://www.youtube.com/")

            // 1) أي تنسيق برابط url جاهز
            var emittedReal = 0
            for (f in arr) {
                val url = f.optString("url")
                if (url.isBlank()) continue
                val q = f.optString("qualityLabel")
                val name = "SD ${if (q.isNotBlank()) q else f.optInt("itag").toString()}"
                emit(url, name, f.optInt("itag"), abrHeaders)
                emittedReal++
            }

            // 2) كل الجودات الكائنة (روابطها مخفية): نبني قائمة اختيارات من
            //    qualityLabels، كلها تشير إلى ABR المتكيّف.
            val labels = LinkedHashMap<String, String>()
            for (f in arr) {
                val q = f.optString("qualityLabel")
                if (q.isBlank()) continue
                labels[q] = q
            }
            val ordered = labels.keys.sortedByDescending { label ->
                Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            var emittedAb = 0
            if (abr.isNotBlank() && produced == 0) {
                if (ordered.isEmpty()) {
                    emit(abr, "SD (ABR)", 0, abrHeaders)
                    emittedAb++
                } else {
                    for (label in ordered) {
                        emit(abr, "SD $label", 0, abrHeaders)
                        emittedAb++
                    }
                }
            }
            Log.d(TAG, "$vid resolveFromHtml: real=$emittedReal abr-entries=$emittedAb labels=${ordered.size}")
            return produced
        } catch (e: Exception) {
            Log.w(TAG, "$vid resolveFromHtml failed: ${e.message}")
            return produced
        }
    }

    /** إعادة توجيه رابط googlevideo عبر النطاق الأم بدل مضيف rr*--sn-… المحجوب. */
    private fun redirectHost(url: String): String {
        return try {
            val u = java.net.URI(url)
            if (u.host?.endsWith(".googlevideo.com") == true) {
                val query = u.rawQuery
                val base = "https://redirector.googlevideo.com${u.rawPath}"
                if (!query.isNullOrBlank()) "$base?$query" else base
            } else url
        } catch (_: Exception) {
            url
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // كل نداءٍ هو لمشاهد جديد: تصفير حارس تكرار الترجمة.
        subsEmittedFor.clear()
        val raw = data.trim()
        val vid = if (Regex("""^[\w-]{11}$""").matches(raw)) raw
        else Regex("""[?&]v=([\w-]{11})""").find(raw)?.groupValues?.get(1)
        if (vid == null) {
            Log.w(TAG, "loadLinks: unexpected data '$data'")
            return false
        }
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        val mode = playbackMode()

        // سمّي المسارات على ترتيبها حسب الإعداد: أيها «أساسي» اليوم؟
        val orderedPrimary = when (mode) {
            "extractor" -> 1
            "direct" -> 2
            else -> 0                               // newpipe (الافتراضي)
        }

        val startMs = System.currentTimeMillis()
        var links = 0

        // نجمع روابط كل المسارات في قائمة واحدة، ثم نبثّها في النهاية بعد الفرز.
        val collected = mutableListOf<ExtractorLink>()
        val sink: (ExtractorLink) -> Unit = { link -> collected.add(link); Unit }

        suspend fun runFirst() {
            when (orderedPrimary) {
                1 -> {
                    loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
                        sink(link); links++
                    }
                }
                2 -> {
                    links += resolveFromHtml(vid, subtitleCallback, sink)
                }
                else -> {
                    links += resolveFromNewPipe(vid, subtitleCallback, sink)
                }
            }
        }
        runFirst()

        if (links == 0 && orderedPrimary != 0) {
            links += resolveFromNewPipe(vid, subtitleCallback, sink)
        }
        if (links == 0 && orderedPrimary != 1) {
            loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
                sink(link); links++
            }
        }
        if (links == 0 && orderedPrimary != 2) {
            links += resolveFromHtml(vid, subtitleCallback, sink)
        }

        // ★ البث النهائي: «default» = نفس ترتيب روابط التشغيل؛ asc/desc يعيدان
        //   الترتيب فقط (فرز مستقر: المتساوية تحتفظ بترتيبها).
        val order = prefs?.getString(ShortDramaSettings.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }

        val elapsed = System.currentTimeMillis() - startMs
        Log.d(TAG, "loadLinks $vid mode=$mode links=$links in ${elapsed}ms")
        if (links == 0) Log.w(TAG, "loadLinks for $vid produced ZERO links at all")
        return true
    }
}
