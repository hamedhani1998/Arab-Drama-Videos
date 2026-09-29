package com.aryarabia.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

/**
 * ARY العربية — قناة يوتيوب واحدة فقط (UC6ApcZBKUPwuL4QcGeWaTZw).
 *
 * لماذا قوائم التشغيل؟ لأنها المصدر الوحيد الكامل على هذه القناة:
 *  - تبويب «الفيديوهات» مختلط وترتيبه زمني؛ إكمال مسلسل واحد منه يتطلب
 *    تصفّح نحو 1800 فيديو / 60 صفحة / ~186 ثانية، والمسلسلات لا تكتمل
 *    إلا بعد مئات الفيديوهات.
 *  - استعلام القناة مع `query` **يتجاهل نص البحث تماماً** ويعيد نفس
 *    النتائج الشعبية.
 *  - `youtubei/v1/search` غير موثوق لهذه القناة (ثلاثة مسلسلات أعادت 0
 *    نتيجة، ولا تتحسّن بزيادة الصفحات).
 *  - كل مسلسل منشور كقائمة تشغيل، وصفحتان لكل قائمة تكفيان لاسترجاع كل
 *    الحلقات بلا نقص (تم التحقق من كل مسلسل: نهاية قلبي 64/64، إنها
 *    مجنونة 62/62، رفيق دربي 40/40 …).
 *
 * النقل: GET على صفحات HTML مباشرة (صفحة القوائم + صفحة القائمة
 * `playlist?list=`) ونقرأ `ytInitialData` منها. هذا أسرع وأقل عرضة
 * لرفض يوتيوب من طلب InnerTube POST، ونفس بنية `lockupViewModel`.
 * لا نستخدم continuation — صفحة القوائم الثانية تُجلب عبر معرف قناة
 * صارم `channel/playlists/` … ولا حاجة لفحص إضافي: 23 مسلسلاً كاملة.
 *
 * التشغيل: نُمرّر رابط يوتيوب العادي، ومُستخرِج يوتيوب المدمج في
 * CloudStream (المبني على NewPipe) يحلّه إلى روابط googlevideo.com حقيقية
 * تدعم التقديم والتأخير (Accept-Ranges: bytes) — بخلاف صفحات HTML التي
 * تسبب الخطأ 2004.
 */
class AryProvider(
    private val prefs: SharedPreferences? = null
) : MainAPI() {

    /** خيار محرك التشغيل المختار في الإعدادات ("newpipe" الافتراضي). */
    private fun playbackMode(): String =
        prefs?.getString(ArySettingsBottomSheet.KEY_PLAYBACK_MODE, "newpipe") ?: "newpipe"

    /**
     * لغة الترجمة المطلوبة، أو `SUB_LANG_AUTO` للعربية الأصلية بلا
     * طلبٍ إضافي (وهو السلوك الافتراضي). راجع `emitSubtitle`.
     */
    private fun subLanguage(): String =
        prefs?.getString(ArySettingsBottomSheet.KEY_SUB_LANG, SUB_LANG_AUTO) ?: SUB_LANG_AUTO

    /** أي ملف صوتي مع كل جودة ("best" الافتراضي = أعلى بت/ث متوافق). */
    private fun audioPref(): String =
        prefs?.getString(ArySettingsBottomSheet.KEY_AUDIO_PREF, "best") ?: "best"

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
        prefs?.getString(ArySettingsBottomSheet.KEY_MAX_QUALITY, "all") ?: "all"

    /** هل نستخدم النطاق البديل redirector. */
    private fun useRedirect(): Boolean =
        prefs?.getBoolean(ArySettingsBottomSheet.KEY_REDIRECT, false) ?: false

    companion object {
        private const val TAG = "AryArabia"

        private const val CHANNEL_ID = "UC6ApcZBKUPwuL4QcGeWaTZw"

        /** صفحة تبويب «قوائم التشغيل» في القناة — GET على HTML نجلب منها ytInitialData. */
        private const val PLAYLISTS_URL = "https://www.youtube.com/channel/$CHANNEL_ID/playlists"

        /**
         * قناتان انضمّتا إلى ARY بعد حذف إضافتي «هم العربية» و«عشق مرشد»
         * (`@humarabia` و`@ishqmurshidarabichumtv`) ودمجهما هنا:
         *   - UCyA7992LhLeYSd7ynpiwpeQ  = هم العربية
         *   - UCOYvEjxqq0sx5XmeQx9-UgA  = عشق مرشد Arab Hum TV
         * تُدمج قوائمهما في صفّ ARY نفسه تحت قسم «المقترحات» — لا صفًّا
         * لكل قناة، فعدد الأقسام على الشاشة هو ما يبطئ العرض ويجعل
         * التطبيق يعلّق. تشغيل الحلقات يبقى من هذا المصدر نفسه بلا فرق.
         */
        private val COMPANION_CHANNELS = listOf(
            "هم العربية" to "UCyA7992LhLeYSd7ynpiwpeQ",
            "عشق مرشد" to "UCOYvEjxqq0sx5XmeQx9-UgA"
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
         * القيمة الافتراضية لـ«لغة الترجمة»: العربية الأصلية بلا `tlang`.
         * يوتيوب لا ينشر على ARY سوى مسار ترجمةٍ عربيّ واحد، وما عداه
         * (نحو 156 لغة) يُولَّد عند الطلب — فكل لغة أخرى تكلّف طلباً.
         */
        private const val SUB_LANG_AUTO = "auto"

        /**
         * قوائم ليست مسلسلات ولا إعلانات ترويجية: ملحقات القناة العامة
         * (أفضل اللحظات، أغاني، Shorts، ملخص، مشهد …) وقوائم الوكالة
         * (الأفضل لدى، أحدث الفيديوهات، أفلام …). كل هذه تُستبعد من صف
         * المسلسلات. أما إعلانات وتشويقات المسلسلات (إعلان ترويجي/تشويقي،
         * تريلر، برومو) فنعرضها في صفٍّ منفصل — طلب المستخدم.
         *
         * `Clips` و`Telefilms` أُضيفتا مع دمج قناتي هم وعشق مرشد: القوائم
         * باسميهما ليست مسلسلات (كان تُستبعد في إضافتهما المستقلة). ولا
         * يظهر أيٌّ منهما في عناوين ARY أصلاً — فالبندان لا يمسّان صفّها.
         */
        private val SKIP_RE = Regex(
            "ملخص|مشهد|Shorts|أفضل اللحظات|أجمل اللحظات|أغاني|الأفضل لدى|" +
                "أحدث فيديو|أحدث الفيديوهات|Latest Videos|أفلام|إهداء|" +
                "Clips|Telefilms",
            RegexOption.IGNORE_CASE
        )

        /** إعلانات المسلسلات وتشويقاتها — صفٌّ منفصل في الواجهة. */
        private val PROMO_RE = Regex(
            "إعلان|تشويق|تريلر|trailer|برومو",
            RegexOption.IGNORE_CASE
        )

        /**
         * القناة لا تُوحّد كتابة الكلمة: «الحلقة 3» و«حلقة 24» و«اللحقة 5»
         * (خطأ مطبعي) كلها موجودة فعلاً. وكلها تحتوي «حلقة» كسلسلة فرعية،
         * فالأنماط أدناه بلا «ال» تلتقط الثلاثة معاً.
         */
        private val EP_NUM_RE = Regex("""حلقة\s*(\d+)""")
        private val FINALE_RE = Regex("""حلقة\s*(?:الأخيرة|الاخيرة|أخيرة|اخيرة)""")

        /** «الجزء الأول/الثاني/…» — عندما تُقسَّم حلقةٌ إلى أجزاء. */
        private val PART_RE = Regex(
            """الجزء\s*([وأ_]?)(ال)?(?:الأول|الاول|الثاني|الثانى|الثالث|الرابع|الخامس|الأخير|الاخير|النهائى|النهائي|\d+)""",
            RegexOption.IGNORE_CASE
        )
    }

    override var name = "ARY العربية"
    override var mainUrl = "https://www.youtube.com/channel/$CHANNEL_ID"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ar"

    /**
     * بعض الشبكات تحجب مضيفات googlevideo الفرعية (rr2--sn-…googlevideo.com)
     * الخاصة ببث الفيديو مع السماح بالنطاق الأم (googlevideo.com / redirector).
     * عند التفعيل نمرّر الروابط عبر نطاقٍ أم بديل. التنبيه: يوتيوب يشترط الشهادة
     * للمضيف الموقّع عليه، لذا قد يعيد البث 403/خطأ شهادة على أي نطاقٍ مبدَّل.
     * الافتراضي معطّل ليعمل لعموم الشبكات. (يدوي للبحث)
     */

    // =============================== HTML / GET ===============================

    private fun ctx(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "ar")
            .put("gl", "US")
    )

    /**
     * نجلب صفحة HTML ونستخرج منها `ytInitialData`. نقرأ قوائم القناة
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
     * يستخرج كائن `ytInitialData` من HTML يسيراً: نجد بداية الكائن بلا
     * الاعتماد على شكل المتغير، ونطابق الأقواس المتوازنة مع مراعاة
     * الأوتار المهرَّبة. لا نعتمد on `JSON.parse` (يوتيوب يرفض أحياناً
     * كائن JSON نحن لا نتحكم فيه) — نمسح نصياً.
     */
    private fun ytInitialData(html: String): JSONObject? {
        val markers = listOf(
            "var ytInitialData = ",
            "window[\"ytInitialData\"] = ",
            "\"ytInitialData\"] = ",
            "ytInitialData = "
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
        /** عدد الفيديوهات كما يصرّح به يوتيوب (يظهر «40 فيديو»)، للتمييز. */
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
     *  - قائمة  : `contentImage.collectionThumbnailViewModel.primaryThumbnail.thumbnailViewModel…`
     * لذلك نبحث عن أي `thumbnailViewModel` تحت contentImage بدل تثبيت مسار
     * واحد، وإلا رجعت أغلفة القوائم فارغة.
     */
    private fun lockupThumb(l: JSONObject): String? {
        val root = l.optJSONObject("contentImage") ?: return null
        val tvs = grab(root, "thumbnailViewModel")
        for (tv in tvs) {
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

    /**
     * الشارة تسمّي العنصر «فيديو» أو «حلقة» حسب القائمة:
     * «40 فيديو» و«29 حلقة» كلتاهما عدد عناصر القائمة.
     */
    private val COUNT_RE = Regex("""(\d[\d,]*)\s*(?:فيديو|حلقة)""")

    /**
     * «40 فيديو» — شارة على غلاف القائمة، في
     * `contentImage.…thumbnailViewModel.overlays[].thumbnailBadgeViewModel.text`
     * (وليست في صفوف البيانات الوصفية كما قد يُظن — تلك للفيديوهات).
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

    private fun episodeNumberOf(title: String): Int? =
        EP_NUM_RE.find(title)?.groupValues?.get(1)?.toIntOrNull()

    private fun isFinale(title: String): Boolean = FINALE_RE.containsMatchIn(title)

    private fun skipTitle(title: String): Boolean = SKIP_RE.containsMatchIn(title)

    /** رقم الجزء داخل الحلقة («الجزء الأول/الثاني/…») عند تقسيم حلقةٍ لأجزاء.
     *   القناة تقسم أحياناً الحلقة الأخيرة («31 الجزء الأول / الجزء الثاني»)،
     *   فيتعارض الجزءان برقمٍ واحد في التطبيق ويختفي أحدهما. نعيد 0 دون جزء. */
    private fun partNumberOf(title: String): Int {
        val m = PART_RE.find(title) ?: return 0
        val w = keyOf(m.value)          // توحيد الهمزات: «الجزء»
        return when {
            w.contains("اول") -> 1
            w.contains("ثان") || w.contains("ثانى") -> 2
            w.contains("ثال") || w.contains("ثالث") -> 3
            w.contains("رابع") -> 4
            w.contains("خامس") -> 5
            w.contains("اخير") -> 6      // «الجزء الأخير» بعد الأول
            else -> Regex("""\d+""").find(m.value)?.value?.toIntOrNull()?.takeIf { it in 1..50 } ?: 1
        }
    }

    /** تنسيق الحلقة للعرض: «31» أو «الحلقة 31 - الجزء الأول». */
    private fun episodeLabel(num: Int, title: String): String {
        val part = partNumberOf(title)
        val base = num.toString()
        return when {
            part <= 0 -> base
            part == 1 -> "الحلقة $num"
            else -> "$num (الجزء ${partNameOf(part)})"
        }
    }

    private fun partNameOf(part: Int): String = when (part) {
        1 -> "الأول"
        2 -> "الثاني"
        3 -> "الثالث"
        4 -> "الرابع"
        5 -> "الخامس"
        else -> "الأخير"
    }

    /** «مسلسل نهاية قلبي» و«نهاية قلبي | ARY» كلاهما «نهاية قلبي». */
    private fun bareName(title: String): String {
        var t = clean(title).replace(Regex("""\s+"""), " ")
        t = t.substringBefore('|').substringBefore('–').substringBefore(" - ").trim()
        if (t.startsWith("مسلسل ")) t = t.removePrefix("مسلسل ").trim()
        return t
    }

    /** اسم الإعلان مع إبقاء صداه «إعلان/تشويق» ليسهل تمييزه عن المسلسل. */
    private fun promoName(title: String): String =
        clean(title).replace(Regex("""\s+"""), " ").trim()

    /**
     * مفتاح التطابق: بلا تشكيل، والهمزات والألفات والألف المقصورة موحّدة،
     * والتاء المربوطة كالهاء. القناة تكتب الاسم نفسه بأشكال مختلفة
     * («الأنين» و«الانين»)، ونفس المسلسل قد يظهر في قائمتي تشغيل (وجدنا
     * «مسلسل التربية» مرتين)، فبغير هذا التوحيد يتكرر المسلسل في الواجهة.
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

    /**
     * رابط صفحة القائمة بصيغة يوتيوب الحقيقية. CloudStream يمرّر كل رابط
     * عبر `fixUrl` قبل `load()`؛ و`fixUrl` يلصق `mainUrl` على أي رابط لا
     * يبدأ بـ "http" — منها `ary://pl/…` — فيصل مكسوراً. الروابط الحقيقية
     * `https://www.youtube.com/playlist?list=…` تمرّ سالكة، و`load()`
     * يستخرج المعرّف من `?list=`. (نتقبّل أيضًا `ary://pl/` متأخّراً في
     * load() للمحفوظات القديمة.)
     */
    private fun playlistUrl(id: String): String = "https://www.youtube.com/playlist?list=$id"

    /**
     * المسلسلات في تبويب القوائم نوعان من يوتيوب: PLAYLIST لأغلب المسلسلات،
     * وSHOW للمسلسلات الجديدة/القادمة (مثل «مسلسل الغيرة»). نقبلهما —
     * وكلٌّ منهما يُفتح بصفحة قائمة اعتيادية.
     */
    private fun isPlaylistOrShow(type: String): Boolean =
        type.contains("PLAYLIST") || type.contains("SHOW")

    /** إعلانات وتشويقات المسلسلات (طلب المستخدم) — لا تُستبعد ولا تُدمج. */
    private fun isPromo(title: String): Boolean = PROMO_RE.containsMatchIn(title)

    // ============================== playlists ==============================

    private data class PlaylistInfo(
        val id: String,
        val title: String,
        val cover: String?,
        val count: Int = 0,
        /**
         * اسم القناة التي جاءت منها القائمة (فارغ = قناة ARY الأساسية).
         * يُعرض مع اسم المسلسل في صف «المقترحات» ليعرف المستخدم مصدره،
         * ولا يُلحق بالعنوان الأصلي — فيبقى اسم المسلسل نظيفاً في صفحة
         * تفاصيله (يأتي من `title` عبر `bareName`).
         */
        val from: String = ""
    )

    // ============================== extra channels ==============================

    /**
     * قنوات إضافية من إعدادات المصدر (رابطٌ في كل سطر). كلٌّ منها يظهر ضمن
     * قسمٍ واحد «المقترحات» مع القنوات المدمجة، وتشملها نتائج البحث، دون
     * تغيير صفّ ARY الأساسي. الافتراضي (فارغ) = سلوك اليوم تماماً.
     *
     * الصيغة المقبولة لكل سطر:
     *   - رابط/معرّف قناة فقط: `UC…` أو `/channel/UC…` أو `/@handle` أو `@handle`
     *     → الاسم يُشتق تلقائياً من الـ handle أو المعرّف.
     *   - اسمٌ مخصص: `الاسم | الرابط`.
     */
    private data class ExtraChannel(val label: String, val url: String)

    private fun extraChannels(): List<ExtraChannel> {
        val raw = prefs?.getString(ArySettingsBottomSheet.KEY_EXTRA_CHANNELS, "")
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
     * قوائم قناةٍ ما: صفحة tab «قوائم التشغيل» تعرض أول 30، ثم نتابع بطلب
     * صفحة ثانية لاسترداد الباقي. تُستخدم للقناة الأساسية والقنوات الإضافية.
     * إن فشلت الصفحة الثانية نحتفظ بالأولى.
     *
     * نُرجع `null` — لا قائمة فارغة — عند فشل الطلب، ليميّز المستدعي بين
     * «القناة بلا قوائم» و«الطلب لم يصل». حفظُ نتيجةٍ فارغة بعد فشلٍ عابرٍ
     * كان يُخفي مسلسلات القناة عن البحث حتى إعادة تشغيل التطبيق.
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
     * قوائم المصدر كلها: قناة ARY الأساسية + القناتان المندمجتان + كل قناة
     * أضافها المستخدم. تُجلب كلها مرةً واحدة (بالتوازي) وتُحفظ، فتبقى في
     * الذاكرة طلباتُ صفحةِ واحدة لا عمليتين.
     */
    private data class SourceLists(
        /** قوائم ARY وحدها — هذه صفّ «مسلسلات ARY العربية». */
        val base: List<PlaylistInfo>,
        /** قوائم بقية القنوات مجتمعة — هذه صفّ «المقترحات» الواحد. */
        val companions: List<PlaylistInfo>,
        /** قناة لم يصل طلبها — نُعيد المحاولة لاحقاً بدل تثبيت فراغها. */
        val failed: Set<String>
    ) {
        /** كل القوائم بلا تكرار — هذا ما يبحث فيه `search`. */
        val all: List<PlaylistInfo> = (base + companions).distinctBy { it.id }
    }

    @Volatile
    private var cachedSource: SourceLists? = null

    /** آخر جلبٍ ناقص، ومتى حدث — لنعيد المحاولة إلا بعد مهلة. */
    @Volatile private var lastPartial: SourceLists? = null
    @Volatile private var lastFailedAt = 0L

    /** متى جُلبت القوائم بنجاح آخر مرة — يحدّد انتهاء صلاحية الحفظ. */
    @Volatile private var cachedAt = 0L

    /** آخر حلقات ناجحة لكل قائمة، ومتى جُلبت — لكل قائمةٍ مدتها الخاصة. */
    private val episodeItems = HashMap<String, Pair<List<Lockup>, Long>>()

    /**
     * الفيديوهات التي أُصدِرت ترجمتها في نداء `loadLinks` الجاري — حارس
     * تكرار لا ذاكرة: `resolveFromNewPipe` قد تُستدعى ثلاث مرات (أساسي
     * ثم احتياطان)، فبلا هذا الحارس تنزل نفس الترجمة ثلاثاً في قائمة
     * المشغّل. يُمسح في أول `loadLinks` فلا يبقى أثر بين الحلقات.
     */
    private val subsEmittedFor = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * يبطل كل ما خُزّن عند تغيير إعدادات القنوات: اللائحة الجديدة تعني
     * قوائمَ ومقترحاتَ مختلفة، فلا يجوز أن تُقدَّم بياناتُ القديم حتى تنتهي
     * المهلة. التشغيلُ بلا هذه الإضافة يعمل تماماً كالمعتاد.
     */
    private var prefsWatcher: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun watchChannelSetting() {
        val p = prefs ?: return
        if (prefsWatcher != null) return
        val w = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != ArySettingsBottomSheet.KEY_EXTRA_CHANNELS) return@OnSharedPreferenceChangeListener
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

    /**
     * عمر حفظ الحلقات قبل إعادة جلبها. الحلقات هي ما يتغيّر فعلاً (مسلسلٌ
     * ينشر حلقة اليوم)، فمهلةٌ قصيرة هنا هي الفارق بين «الحلقة الجديدة
     * ظهرت» و«الحلقة الجديدة ستظهر بعد إعادة تشغيل التطبيق».
     */
    private val EPISODE_FRESH_MS = 3 * 60 * 1000L

    /** مفتاح القناة الأساسية في `SourceLists.failed` (ـ URL مفتاحها). */
    private val BASE_URL_KEY = "base"

    private val RETRY_COOLDOWN_MS = 20_000L

    /**
     * عمر حفظ القوائم قبل إعادة جلبها — قصير عمداً، ليجعل تحديثات الحلقات
     * والمسلسلات تظهر أوّل بأوّل: الحفظ كان يدوم الجلسة كلها، فمسلسلٌ
     * نشرته القناة اليوم لا يظهر إلا بعد إعادة تشغيل التطبيق. الآن
     * نُعيد استعمال آخر جلبٍ ناجح، وأول فتح للرئيسية بعد انقضاء المهلة
     * يعيد الفحص من يوتيوب.
     *
     * ليس صفراً عن قصد: إعادة الجلب عند كل فتح كانت سبب إبطاء يوتيوب
     * سابقاً وإخفاء كل الأقسام دفعةً واحدة. مهلةٌ قصيرة تُظهر الجديد
     * سريعاً، وتبقى مجموعة الفتحات المتتابعة في طلبٍ واحد لكل قناة.
     */
    private val FRESH_MS = 5 * 60 * 1000L

    /**
     * جلبٌ واحد لكل قناة: ARY الأساسية + القناتان المندمجتان + كل قناة
     * أضافها المستخدم، جميعها بالتوازي.
     *
     * ⚠ لا تُخفّض هذا إلى طلبٍ لكل صفحة رئيسية: الإصدار السابق كان يجلب
     * القناة الإضافية مرتين (مرةً في `allPlaylists` ومرةً لبناء صفّها)،
     * فتضاعف عدد الطلبات على يوتيوب عند كل فتح للرئيسية حتى بطأ يوتيوب
     * الردّ وصارت `channelPlaylists` تُرجع فارغة — فيختفي البحث ومعه كل
     * الأقسام معاً. هنا طلبٌ واحد لكل قناة لكل مهلة.
     */
    private suspend fun loadSourceLists(): SourceLists = coroutineScope {
        val extras = extraChannels()
        val base = async { channelPlaylists(PLAYLISTS_URL) }

        // بقية القنوات (المندمجة ثم الإضافية) — كلها طلبات متوازٍ واحد.
        val others = COMPANION_CHANNELS.map { (label, cid) ->
            ExtraChannel(label, "https://www.youtube.com/channel/$cid/playlists")
        } + extras

        val restLists = others.map { ch ->
            async {
                val pls = try {
                    channelPlaylists(ch.url)
                } catch (e: Exception) {
                    Log.w(TAG, "channel '${ch.label}' failed: ${e.message}")
                    null
                }
                ch to pls
            }
        }.awaitAll()

        val baseLists = base.await()
        // قوائم ARY تسبق غيرها، فمشتركٌ معها يُنسب إلى ARY ولا يتكرّر.
        val baseIds = baseLists.orEmpty().map { it.id }.toSet()
        val failed = mutableSetOf<String>()
        if (baseLists == null) failed.add(BASE_URL_KEY)

        val companions = restLists.flatMap { (ch, pls) ->
            if (pls == null) { failed.add(ch.url); emptyList() }
            else pls.filter { it.id !in baseIds }
                .map { it.copy(from = ch.label) }
        }
        SourceLists(baseLists.orEmpty(), companions, failed)
    }

    /**
     * القوائم المحفوظة. لا نحفظ إلا إذا نجحت **كل** القنوات: فشلُ قناةٍ
     * عابرٌ (بطء يوتيوب) كان يُثبّت فراغها فيبقى قسمُها ونتائجُها مخفيّة
     * حتى إعادة تشغيل التطبيق. الآن يُعاد الجلب — بعد مهلة قصيرة فقط،
     * حتى لا يولّد كل ضغطة مفتاح في البحث طلباً جديداً (وهو مابطّئ يوتيوب
     * من الأصل).
     */
    private suspend fun sourceLists(): SourceLists {
        val now = System.currentTimeMillis()
        cachedSource?.let { if (now - cachedAt < FRESH_MS) return it }
        if (now - lastFailedAt < RETRY_COOLDOWN_MS && lastPartial != null) return lastPartial!!
        val src = loadSourceLists()
        Log.d(
            TAG,
            "playlists: base=${src.base.size} companions=${src.companions.size} " +
                "failed=${src.failed.size} total=${src.all.size}"
        )
        if (src.failed.isEmpty() && src.base.isNotEmpty()) {
            cachedSource = src
            cachedAt = now
            lastPartial = null
        } else {
            lastFailedAt = now
            lastPartial = src
        }
        return src
    }

    /** كل القوائم (أساسية + إضافية) للبحث و`load` — للمعرّف اسمُه الصحيح. */
    private suspend fun allPlaylists(): List<PlaylistInfo> = sourceLists().all

    /**
     * حلقات قائمة: 60–100+ حلقة يُعيدها يوتيوب كاملةً في الصفحة الأولى.
     *
     * تُحفظ الحلقات مدّةً قصيرة كي تظهر الحلقات الجديدة أوّل بأوّل: من
     * يفتح مسلسلاً بعد ساعتين يجب أن يرى ما نُشر فيه بعد آخر فتح له،
     * لا ما رآه في وقته الأول. الحفظ يُلغى عند فشل الجلب (لا يُثبَّت
     * فراغٌ أبداً — انظر `sourceLists`)، ويُحذف عند تغيير إعدادات
     * القنوات فلا تُقرأ قائمةٌ بقيمٍ من العالم القديم.
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
     * يحوّل عناصر القائمة إلى حلقات مرقّمة مرتّبة. القناة لا ترقّم الحلقة
     * الأخيرة، فتُوضع بعد أعلى رقم مُرقّم.
     *
     * الإعلانات ذات الحلقات المرقّمة تُرقَّم عادياً. أما الإعلانات التي لا
     * تملك سوى عناصر غير مرقّمة («مسلسل X قريباً على ARYالعربية») فنُبقيها
     * كحلقات في النهاية — وإلا عادت القائمة فارغة وفشل فتح صفحة تفاصيلها.
     */
    private fun episodesOf(items: List<Lockup>): List<Pair<Int, Lockup>> {
        val numbered = mutableListOf<Pair<Int, Lockup>>()
        val extras = mutableListOf<Lockup>()
        var finale: Lockup? = null
        for (i in items) {
            if (skipTitle(i.title)) continue
            val n = episodeNumberOf(i.title)
            when {
                n != null -> numbered.add(n to i)
                isFinale(i.title) && finale == null -> finale = i
                else -> extras.add(i)
            }
        }
        val maxNum = numbered.maxOfOrNull { it.first } ?: 0
        // «حلقة الأخيرة» فوق كل الأرقام، ثم التيزرات/غير المرقّمة بعدها —
        // أرقام متتالية للترتيب فقط كي تبقى القائمة مرئية ولا يُسقط فتحها.
        var next = maxNum + 1
        if (finale != null) { numbered.add((next) to finale); next += 1 }
        for (l in extras)
            numbered.add((next++) to l)
        return distinctEpisodes(numbered)
    }

    /** يفصل أجزاءَ الحلقة الواحدة بأرقامٍ مختلفة — انظر comment في episodesOf. */
    private fun distinctEpisodes(all: List<Pair<Int, Lockup>>): List<Pair<Int, Lockup>> {
        val eps = all.distinctBy { it.second.id }
        val byBase = LinkedHashMap<Int, MutableList<Lockup>>()
        for ((n, l) in eps) byBase.getOrPut(n) { mutableListOf() }.add(l)

        val used = HashSet<Int>()
        val out = mutableListOf<Pair<Int, Lockup>>()

        for ((base, group) in byBase) {
            val ordered = group.sortedWith(
                compareBy({ partNumberOf(it.title) == 0 }, { partNumberOf(it.title) })
            )
            var slot = base
            for (l in ordered) {
                while (slot in used) slot += 1
                out.add(slot to l)
                used.add(slot)
                slot += 1
            }
        }
        return out.sortedBy { it.first }
    }

    // ============================== main page ==============================

    /**
     * الصفحة الرئيسية = قوائم القنوات (المسلسلات). طلب أو طلبان فقط: لا
     * نعدّد حلقات كل قائمة هنا (23 قائمة × صفحتان = 46 طلباً بلا داعٍ)،
     * والحلقات تُجلب عند فتح المسلسل.
     *
     * ⚠ صِرنا صفَّين لا أكثر، ونُبقيهما ثابتين مهما أضاف المستخدم قنوات:
     *   1) «مسلسلات ARY العربية»  — مسلسلات قناة ARY وحدها.
     *   2) «المقترحات»            — كل ما جاء من القنوات الأخرى مجتمعةً
     *      في صفٍّ واحد، واسم القناة يظهر مع كل مسلسل داخله.
     * الإصدار السابق كان يبني صفًّا مستقلاً لكل قناة، فكل قناة إضافية
     * كانت تُضاعف الأقسام على الشاشة وترهق الواجهة — وهو ما جعل التطبيق
     * يعلّق. صفٌّ واحد لا يتأثر بعدد القنوات إطلاقاً.
     *
     * لا نضيف صفاً من تبويب «الفيديوهات»: جُرِّب وقيس، فالقناة ترفع مقاطع
     * قصيرة من مسلسل واحد (450 فيديو متتالٍ كلها «الغيرة»، وبعد 15 صفحة
     * لم يظهر مسلسل ثانٍ)، فالصف كان سيبدو كأن القناة مسلسلٌ واحد.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val lists = mutableListOf<HomePageList>()

        // جلبٌ واحد لكل قناة (الأساسية أولاً، وبقية القنوات بالتوازي) — لا
        // نعيد طلب أي قناة هنا، فالبيانات محفوظة أصلاً.
        watchChannelSetting()
        val src = sourceLists()

        // صفّ ARY الأساسي: قوائم قناة ARY وحدها.
        homeFrom(src.base, lists)

        // صفٌّ واحد للقنوات كلها مجتمعةً (المندمجة + الإضافية).
        val extras = dedupe(src.companions.filter { !isPromo(it.title) })
        if (extras.isNotEmpty()) {
            val cards = extras.map { p -> companionCard(p) }
            lists.add(HomePageList("المقترحات", cards))
        }
        return newHomePageResponse(lists)
    }

    /**
     * كارت مسلسل من قناةٍ أخرى، بعنوان يذكر مصدره («عہد الوفا · هم العربية»)
     * ليعرف المستخدم أن المسلسل ليس من ARY.
     *
     * آمن أن نغيّر اسم الكارت: صفحة التفاصيل لا تأخذ اسمها منه، بل من
     * `PlaylistInfo.title` عبر `bareName` في `loadPlaylist` — فيبقى داخل
     * المسلسل اسمُه نظيفاً بلا هذه اللاحقة.
     */
    private fun companionCard(p: PlaylistInfo) = newTvSeriesSearchResponse(
        if (p.from.isBlank()) bareName(p.title) else "${bareName(p.title)} · ${p.from}",
        playlistUrl(p.id)
    ) {
        this.posterUrl = p.cover
    }

    private fun homeFrom(playlists: List<PlaylistInfo>, lists: MutableList<HomePageList>, rowTitle: String = "مسلسلات ARY العربية") {
        val series = dedupe(playlists.filter { !isPromo(it.title) })
        val cards = series.map { p ->
            newTvSeriesSearchResponse(bareName(p.title), playlistUrl(p.id)) {
                this.posterUrl = p.cover
            }
        }
        if (cards.isNotEmpty())
            lists.add(HomePageList(rowTitle, cards))

        // إعلان المسلسل قد يكون «ترويجياً» و«تشويقياً» في آنٍ — كلاهما قائمة
        // مستقلة يريد المستخدم رؤيتها، فلا ندمجهما (`dedupe` يوحّد اسميهما).
        val promoCards = playlists.filter { isPromo(it.title) }
            .distinctBy { it.id }
            .map { p ->
                newTvSeriesSearchResponse(promoName(p.title), playlistUrl(p.id)) {
                    this.posterUrl = p.cover
                }
            }
        if (promoCards.isNotEmpty())
            lists.add(HomePageList("إعلانات وتشويقات المسلسلات", promoCards))
    }

    /**
     * يزيل تكرار الاسم نفسه: نفس المسلسل قد يُنشر بقائمتين (PLAYLIST وSHOW —
     * «مسلسل التربية» مثلًا) أو بتسميتين (مسلسل X / X). عند التكرار نُبقي
     * القائمة الأكبر. نُستدعى على سلاسل وإعلانات منفصلة، فلا تتداخل.
     */
    private fun dedupe(playlists: List<PlaylistInfo>): List<PlaylistInfo> {
        val byKey = LinkedHashMap<String, PlaylistInfo>()
        for (p in playlists) {
            if (p.title.isBlank() || skipTitle(p.title)) continue
            val k = keyOf(p.title)
            if (k.isBlank()) continue
            val prev = byKey[k]
            byKey[k] = when {
                prev == null -> p
                p.count > prev.count -> p
                else -> prev
            }
        }
        return byKey.values.toList()
    }

    // =============================== search ===============================

    /**
     * البحث مطابقةٌ نصية داخل قوائم القناة المحفوظة، لا طلب شبكة.
     * (`query` في استعلام القناة مُتجاهَل من يوتيوب، و`youtubei/v1/search`
     * ناقص لهذه القناة — راجع تعليق الصنف.)
     */
    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = clean(query)
        if (q.isBlank()) return emptyList()

        val playlists = allPlaylists()
        if (playlists.isEmpty()) return emptyList()

        // كل كلمة في السؤال يجب أن ترد في اسم المسلسل. التوحيد يجعل
        // «الانين» تجد «الأنين» و«اني» تجد «أناني» (الألف المقصورة/الياء).
        val words = keyOf(q).split(' ')
            .filter { it.length > 1 && it != "مسلسل" && it != "حلقه" }

        // نبحث في المسلسلات والإعلانات معاً، بلا مزج (`dedupe` للسيريس فقط —
        // ترويجي وتشويقي إعلانان مختلفان وكلاهما يُعرض).
        val seriesHits = hitsIn(dedupe(playlists.filter { !isPromo(it.title) }), words, keyOf(q))
        val promoHits = hitsIn(playlists.filter { isPromo(it.title) }, words, keyOf(q))

        // نعيدها مصفوفة واحدة: المسلسلات أولاً ثم إعلاناتها. النتيجة من قناة
        // أخرى تحمل اسمها كما في صف «المقترحات».
        val out = (seriesHits + promoHits).map { p ->
            newTvSeriesSearchResponse(
                when {
                    isPromo(p.title) -> promoName(p.title)
                    p.from.isBlank() -> bareName(p.title)
                    else -> "${bareName(p.title)} · ${p.from}"
                },
                playlistUrl(p.id)
            ) {
                this.posterUrl = p.cover
            }
        }
        Log.d(TAG, "search '$q' -> ${out.size}")
        return out
    }

    /** مطابقة نصية داخل قائمة قوائم (يتقاسمها البحث للسلاسل والإعلانات). */
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
     * `load()` (في APIRepository.load). `fixUrl` لا يمسك روابط `ary://`
     * فيُلصق عليها `mainUrl` فيصبح الرابط
     * `.../channel/UC…/ary://pl/…` ويفشل الفرع. لذلك:
     *  - الصفحة الرئيسية تُبني بروابط يوتيوب حقيقية `?list=` (لا يكسّرها fixUrl)
     *  - ونتقبّل معرّف القائمة من موضع أيقونته في الرابط (مكسورٍ أو صافٍ)
     *    حتى تظل المسلسلات المحفوظة/المشارَكة قديماً تعمل.
     */
    override suspend fun load(url: String): LoadResponse? {
        // 1) روابطنا الداخلية + أي رابط يحمل ary://pl/ مهما كان موضعه.
        if (url.contains("ary://pl/")) {
            val pid = url.substringAfter("ary://pl/").trim()
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
            // الحلقة تُمرّر عبر link حقيقي لكي لا يلصق fixUrl عليه mainUrl
            // (مثل ary://pl/ تماماً)، وloadLinks يقبل الرابط أو المعرّف النقي.
            newEpisode("https://www.youtube.com/watch?v=${l.id}") {
                this.name = episodeLabel(num, l.title).ifBlank { "الحلقة $num" }
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

    /**
     * نُمرّر رابط المشاهدة العادي فقط: مُستخرِج يوتيوب المدمج في
     * CloudStream يعرف يوتيوب أصلاً، ويحلّ الرابط إلى ملفات googlevideo.com
     * التي تدعم التقديم والتأخير. لا نُصدر صفحة HTML أبداً (سبب الخطأ 2004).
     *
     * تجاوز الحجب: بعض الشبكات تحجب مضيف googlevideo الفرعي (rr2--sn-…)
     * مع السماح بالنطاق الأم redirector.googlevideo.com. لا يمكن تجاوز التوقيع،
     * لكن نضيف نسخةً بديلة تعبّر عبر redirector (يقبلها السيرفر أحياناً)،
     * وبذلك يتاح للاعب خيار آخر عند فشل الأصل.
     */
    /**
     * يستخرج كائن `ytInitialPlayerResponse` من HTML بنفس منطق
     * `ytInitialData`: نجد البداية ونطابق الأقواس المتوازنة مع مراعاة
     * الأوتار المهرَّبة.
     */
    private fun ytPlayerResponse(html: String): JSONObject? {
        val markers = listOf(
            "var ytInitialPlayerResponse = ",
            "window[\"ytInitialPlayerResponse\"] = ",
            "\"ytInitialPlayerResponse\"] = ",
            "ytInitialPlayerResponse = "
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

    /**
     * يجلب حلقة يوتيوب مباشرة: صفحة watch.html ثم نستخرج
     * ytInitialPlayerResponse ونمرر روابطها عبر callback. هذا بديل
     * مباشر عن extractor المدمج الذي أعطى 0 روابط على هذا الجهاز.
     */
    /**
     * يعيد توجيه رابط googlevideo عبر النطاق الأم redirector.googlevideo.com
     * بدل مضيف rr*--sn-… المحجوب على بعض الشبكات. يُحافظ على كل معاملات
     * التوقيع كما هي. بعض الشبكات تقبل redirect (فيتحول بعده للمضيف الأصلي) —
     * يُجرب عادةً مع النسخة الأصلية.
     */
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
     * فيديو ARY يحمل اثني عشر تنسيقاً صوتياً (AAC و Opus من ~60 إلى ~153
     * ألف بت/ث) و**لا مسارات صوتية بديلة** — أي لا نسخة إنجليزية من
     * الصوت. لذلك `audioTracks` في CloudStream (وهو للّغة ثانية على الفيديو
     * نفسه) لا مكان له هنا، فترجمةُ طلب «جميع الملفات الصوتية» عملياً:
     * **أن يُختار أيٌّ من الاثني عشر** مع كل جودة، لا أن يُحصر في واحد.
     *
     * الافتراضي `best` هو السلوك السابق حرفياً: مواءمة كوديك الفيديو
     * (webm ↔ webm، mp4 ↔ mp4) ثم أعلى بت/ث — لأن اختيار opus لفيديو mp4
     * قد يجعل اللاعب يحوّل الصوت بلا داعٍ.
     *
     * أي خيار آخر يبقى داخل نفس مجموعة الصيغ المتوافقة، فلا نخرج عن
     * كوديك الفيديو ولا نكسر المانيفست.
     */
    private fun pickAudio(audios: List<AudioInfo>, video: StreamInfo, pref: String): AudioInfo? {
        // مواءمة كوديك الفيديو أولاً: webm يستقبل webm، وmp4 يستقبل mp4.
        val family = if (video.mimeType.contains("webm")) { a: AudioInfo ->
            a.mimeType.contains("webm")
        } else { a: AudioInfo ->
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
     * يصدّر ترجمةً واحدةً للمشغّل.
     *
     * ARY لا تنشر على يوتيوب سوى مسار ترجمةٍ واحد (`ar` تلقائية، `asr`)،
     * وتترك الباقي — نحو 156 لغة — *غير منشورة*: يوتيوب يولّدها عند الطلب
     * عبر معامل `tlang` على رابط `timedtext` الموقَّع. ولهذا لا جديد هنا
     * في `getSubtitlesDefault()`: هو يعيد ذلك المسار الواحد، لا الستة
     * والخمسين. فبنينا الجلب على `timedtext` مباشرةً.
     *
     * اللغة الافتراضية عربية (سلوك ما قبل هذا الإصدار تماماً: بلا
     * `tlang` ولا طلبٍ إضافي)، والمستخدم يختار غيرها من الإعدادات.
     *
     * الجلب عبر `HttpURLConnection` (HTTP/1.1) لا عبر `app.get`، لسبب
     * موثّق في `MosSubServer`: مكدّس يوتيوب يقتل بعض الطلبات على أجهزة
     * بعينها بينما يردّ HTTP/1.1 سليماً. النص يُثبَّت محلياً على خادم
     * `ArySubServer` ثم يُسلَّم رابط `127.0.0.1` — لأن `SubtitleFile` لا
     * يحمل محتوىً، ولأن المشغّل يرفض عناوين `data:`.
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
            // في نداء `loadLinks` واحد (أساسي ثم احتياطان)، فبلا هذا الحارس
            // تنزل نفس الترجمة ثلاثاً في قائمة المشغّل.
            if (subsEmittedFor.putIfAbsent(vid, true) != null) return

            val track = runCatching { extractor.subtitlesDefault }
                .getOrNull()?.filterNotNull()?.firstOrNull() ?: return
            val base = track.url?.takeIf { it.isNotBlank() } ?: return

            val wanted = subLanguage()
            val shown = if (wanted == SUB_LANG_AUTO) "ar" else wanted
            // `fmt=vtt` يجعل يوتيوب يعيد WebVTT مباشرةً. و`tlang` هو
            // ما يولّد الترجمة بلغة أخرى — يوتيوب لا ينشرها محسوبة.
            val url = buildString {
                append(base)
                append("&fmt=vtt")
                if (wanted != SUB_LANG_AUTO) {
                    append("&tlang=")
                    append(URLEncoder.encode(wanted, "UTF-8"))
                }
            }

            val text = fetchSubtitleText(url, "https://www.youtube.com/watch?v=$vid")
            if (text.isBlank()) return

            val local = ArySubServer.register(text) ?: return
            subtitleCallback(
                newSubtitleFile(shown, local) {
                    this.headers = mapOf("Referer" to "https://www.youtube.com/")
                }
            )
            Log.d(TAG, "$vid subtitle ok lang=$shown bytes=${text.length}")
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
     * مسار تشغيل مطابق لسيرفرات إضافة «يوتيوب» في re-3arabi (التي تعمل على
     * هاتفك): `YoutubeStreamExtractor.fetchPage()` يعطي روابط googlevideo
     * **مفكوكة التوقيع ومعامل n** بالإضافة إلى نطاقات Initialization/Index لكل
     * جودة، ثم نبني منها مانيفست DASH محلي (`127.0.0.1`) يعرض كل جودة مع
     * أفضل صوت لها، فيتلقى يوتيوب طلبات Range شرعية — لا ترفض 2004.
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

            // جودات الفيديو (video-only): مفتاح الاستخراج — نُبقي كل تنسيقٍ مميز
            // (ارتفاعه وكوديكه: avc وvp9 وav1) بدل طيّ كل ارتفاع إلى تنسيقٍ واحد،
            // فيظهر في اللاعب كل الجودات المتاحة بدل جزءٍ منها.
            val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val streamUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(streamUrl)) return@mapNotNull null

                    val label = qualityLabelOf(vs)
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    var mime = vs.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = AryDashServer.mimeFromUrl(streamUrl, false)

                    val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                    val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                    StreamInfo(streamUrl, mime, height, label, initR, indexR, codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

            val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                try {
                    val aUrl = asr.content ?: return@mapNotNull null
                    val bitrate = runCatching { asr.bitrate ?: 128000 }.getOrNull() ?: 128000
                    var mime = runCatching { asr.format?.mimeType }.getOrNull()
                    if (mime.isNullOrEmpty()) mime = AryDashServer.mimeFromUrl(aUrl, true)

                    val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                    val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null
                    var rawLang = runCatching { asr.audioTrackId ?: "Default" }.getOrNull() ?: "Default"
                    if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                    AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase(), codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }
            val audiosByLanguage = audioInfoList.groupBy { it.language }

            // ★ لا شيء يُجلب عبر الشبكة هنا: الروابط تُبثّ فوراً أدناه، ثم
            //   تُطلق الترجمة في خيط منفصل. الجلبُ الشبكي للترجمة قبل
            //   إصدار الروابط كان يوقف `loadLinks` حتى 20 ثانية — فيبدو
            //   للمستخدم «الفيديو يدور ولا يشتغل» — ويزيد على ذلك أن
            //   CloudStream يجمع `loadLinks` كاملاً قبل عرض القائمة، فلا
            //   تظهر الترجمة إلا بعد انتهاء الجلب. صفر جلب هنا.
            AryDashServer.ensureStarted()

            // إعداد «الجودات»: الأعلى فقط عند اختيار high (نُبقي نسخة الكوديك
            // العليا أيضاً لئلا يختفي التنسيق شبه المتاح للاعب).
            val effectiveVideos = if (qualityMode() == "high") {
                val top = videoOnlyList.maxByOrNull { it.height }
                if (top != null) videoOnlyList.filter { it.height == top.height } else videoOnlyList
            } else videoOnlyList

            // كل تنسيق فيديو يُبث — بلا استثناء — مقترناً بصوتٍ مختارٍ من
            // تنسيقاته. عند غياب الصوت نُبث الفيديو وحده (مانيفست بلا صوته)
            // بدل أن يسقط التنسيق بالكامل.
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

                val localLink = AryDashServer.buildAndRegister(
                    video, if (bestAudio != null) listOf(bestAudio) else emptyList(),
                    durationSeconds
                )
                if (localLink != null) {
                    callback(
                        newExtractorLink(
                            "ARY العربية",
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

            // مقاطع مدمجة (muxed) — احتياط فقط: إن لم يُنتج مسار DASH أعلاه أي
            // رابط (فيديو بلا video-only). كانت تُبث دائماً قبلاً كروابط خام
            // «Legacy» تفشل أحياناً في اللاعب، فأصبحت هنا للطوارئ لا للعرض.
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
                        newExtractorLink("ARY العربية", "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = mHeight
                        }
                    )
                    produced++
                }
            }

            Log.d(TAG, "$vid NewPipe DASH links=$produced qualities=${effectiveVideos.size}")

            // ★ الترجمة تأتي بعد الروابط، في coroutine منفصل على IO. لا
            //   نبطل `loadLinks` حتى لو عَلِقت الترجمة: التشغيل لا يتأخر
            //   بسببها. النمط نفسه المستعمل في NartoDrama و Reellee.
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
                    "ARY العربية",
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
                            "ARY العربية",
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

            // 1) أي تنسيق برابط url جاهز (يوتيوب يخفيها حديثاً لكن إن ظهرت استخدمها)
            var emittedReal = 0
            for (f in arr) {
                val url = f.optString("url")
                if (url.isBlank()) continue
                val q = f.optString("qualityLabel")
                val name = "ARY ${if (q.isNotBlank()) q else f.optInt("itag").toString()}"
                emit(url, name, f.optInt("itag"), abrHeaders)
                emittedReal++
            }

            // 2) كل الجودات الكائنة (encoded في adaptiveFormats لكن url مخفٍ):
            //    نبني قائمة اختيارات من qualityLabels، كلها تشير إلى ABR المتكيّف
            //    (serverAbrStreamingUrl يتكيّف تلقائياً بين الجودات).
            val labels = LinkedHashMap<String, String>() // label -> available quality
            for (f in arr) {
                val q = f.optString("qualityLabel")
                if (q.isBlank()) continue
                // أبقي أعلى دقة موجودة لكل تسمية (آخر دقة فائقة)
                labels[q] = q
            }
            // ترتيب تنازلي للحجم
            val ordered = labels.keys.sortedByDescending { label ->
                Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            var emittedAb = 0
            if (abr.isNotBlank() && produced == 0) {
                // عند غياب كل url: عرض قائمة الجودات على نفس ABR المتكيّف
                if (ordered.isEmpty()) {
                    emit(abr, "ARY (ABR)", 0, abrHeaders)
                    emittedAb++
                } else {
                    for (label in ordered) {
                        emit(abr, "ARY $label", 0, abrHeaders)
                        emittedAb++
                    }
                }
            } else if (abr.isNotBlank() && emittedReal == 0) {
                // روابط url حقيقية وُجدت → لا ندخل تعديلات؛ ABR يبقى احتياطياً
            }
            Log.d(TAG, "$vid resolveFromHtml: real=$emittedReal abr-entries=$emittedAb labels=${ordered.size}")
            return produced
        } catch (e: Exception) {
            Log.w(TAG, "$vid resolveFromHtml failed: ${e.message}")
            return produced
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
        // إذا كان المستخدم اختار extractor أو direct، نبدأ به ونواصل الباقي
        // احتياطياً ما لم تنتج روابط.
        val orderedPrimary = when (mode) {
            "extractor" -> 1
            "direct" -> 2
            else -> 0                               // newpipe (الافتراضي)
        }

        val startMs = System.currentTimeMillis()
        var links = 0

        // نجمع روابط كل المسارات في قائمة واحدة، ثم نبثّها في النهاية بعد الفرز.
        // الترتيب الافتراضي يبثّها بنفس ترتيبها تماماً كما كان (بلا تغيير)،
        // والتصاعدي/التنازلي يعيدان ترتيبها فقط بلا حذف أو تكرار.
        // ★ ملاحظة: الدوال المساندة (resolveFromHtml/NewPipe) تُرجع عدد روابطها
        //   بنفسها وتُصفّر `links` عبر +=، فلا نزيد عدّاداً هنا حتى لا يُحسب
        //   الرابط مرتين (كان سيشوّه شروط الاحتياط links == 0).
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

        // ★ البث النهائي: «default» = نفس ترتيب اليوم حرفياً؛ asc/desc يعيدان
        //   الترتيب فقط (فرز مستقر: المتساوية تحتفظ بترتيبها، ولا حذف ولا تكرار).
        val order = prefs?.getString(ArySettingsBottomSheet.KEY_QUALITY_ORDER, "default")
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
    