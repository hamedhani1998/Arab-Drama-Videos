package com.shortdramahub.plugin

import cloudstreamshared.FormatTag
import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.jsoup.Jsoup
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

/**
 * «شورت دراما هب» — فهرس أفلامٍ قصيرة مدبلجة على موقع `shortdramahub.world`.
 *
 * ## ما هو الموقع بالضبط (مقيس، لا مُفترَض)
 *
 * موقع PHP مخصّص (nginx، لا WordPress) يعرض **فهرساً** لأفلامٍ قصيرة، وكل
 * صفحة مشاهدة تُضمّن **إطار يوتيوب واحداً فقط** (`id="mainMoviePlayer"`).
 * لا m3u8 ولا mp4 ولا مرايا ولا حلقات ولا ترجمة مرفوعة: الفيلم كامل على
 * يوتيوب، والموقع واجهةُ تصفّحٍ له.
 *
 * لذلك المصدر لا يستخرج وسائط من الموقع إطلاقاً — يستخرج **معرّف يوتيوب**
 * من صفحة المشاهدة ثم يشغّله بمسار NewPipe نفسه المستعمل في إضافة
 * «دراما قصيرة». وهذا هو التصميم الصحيح لهذا الموقع، لا التفافٌ عنه.
 *
 * ## من أين تأتي القائمة
 *
 * `/api/home-movies?offset=N` يُعيد `{ok, items[], offset, limit, total,
 * has_more}`، ٢٥ عنصراً في الصفحة (المعامل `limit` **مُتجاهَل**)، و`total`
 * = ٤٥٥ سطراً / ٤٤٩ رابطاً فريداً. المعاملات `q` و`search` و`category`
 * و`cat` و`type` كلها مُتجاهَلة — يُتحقَّق من ذلك بالاستجابة نفسها.
 *
 * صفحات الأقسام `/category/{Movies|Drama|Action}` **نقية** (٨٠/٥٨/٥ بطاقات)
 * و**بلا ترقيم إطلاقاً**: `?page=` و`?p=` و`?offset=` و`?start=` و`?from=`
 * و`?pg=` و`/2` كلها مُتجاهَلة أو ٤٠٤. وروابط التنقّل العربية في الموقع
 * (`/category/رومانسي` …) **معطوبة من الخادم**: تُعيد ٠ بطاقة.
 *
 * ## لماذا لا روابط googlevideo مباشرة
 *
 * قياساً على `ytInitialPlayerResponse`: `playabilityStatus = OK`،
 * `formats = 0`، `adaptiveFormats = 34`، و**كل تنسيق منها `url` غائب**
 * بينما `serverAbrStreamingUrl` موجود. أي أن الروابط المباشرة **غير
 * موجودة أصلاً** في الصفحة، فـ`loadExtractor` وحده لا يصلح مساراً أساسياً
 * هنا. المسار الذي يعمل هو NewPipe + DASH محلي (نفس نمط «دراما قصيرة»).
 *
 * ## الترجمة
 *
 * يوتيوب ينشر على هذه المقاطع مسارات `asr` تلقائية (`ar` و`en` مقيسة).
 * تُصدَّر العربية وحدها، ويمكن إيقافها من الإعدادات. الجلب عبر خادم محلي
 * لأن رابط `timedtext` موقَّع ومحدود الصلاحية — التفصيل في
 * `ShortDramaHubSubServer`.
 */
class ShortDramaHubProvider(
    private val prefs: SharedPreferences? = null
) : MainAPI() {

    /** خيار محرك التشغيل المختار في الإعدادات ("newpipe" الافتراضي). */
    private fun playbackMode(): String =
        prefs?.getString(ShortDramaHubSettings.KEY_PLAYBACK_MODE, "newpipe") ?: "newpipe"

    /** عرض كل الجودات أم الأعلى فقط. */
    private fun qualityMode(): String =
        prefs?.getString(ShortDramaHubSettings.KEY_MAX_QUALITY, "all") ?: "all"

    /** هل نستخدم النطاق البديل redirector. */
    private fun useRedirect(): Boolean =
        prefs?.getBoolean(ShortDramaHubSettings.KEY_REDIRECT, false) ?: false

    /** هل نصدّر الترجمة التلقائية. */
    private fun subtitlesOn(): Boolean =
        prefs?.getBoolean(ShortDramaHubSettings.KEY_SUBTITLES, true) ?: true

    /**
     * أي ملف صوتي مع كل جودة. لا إعدادَ له — هذه المقاطع لا تحمل مسارات
     * صوتية بديلة، فالاختيار داخلي: أعلى بت/ث موافق لكوديك الفيديو.
     */
    private fun audioPref(): String = "best"

    private fun ytPlayerResponse(html: String): JSONObject? =
        balancedJson(html, "ytInitialPlayerResponse")

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

    /** يستخرج كوديك حقيقي من mimeType («video/mp4; codecs="avc1.640028"») إن وُجد. */
    private fun codecFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val i = mime.indexOf("codecs=")
        if (i < 0) return null
        val v = mime.substring(i + "codecs=".length).trim().removeSurrounding("\"")
        return if (v.isNotBlank()) v else null
    }

    companion object {
        private const val TAG = "ShortDramaHub"

        private const val SITE = "https://shortdramahub.world"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** الأقسام الثلاثة العاملة — بالمُعرّف الإنجليزي لأن العربي معطوب من الخادم. */
        private val CATEGORIES = listOf(
            "Movies" to "أفلام",
            "Drama" to "دراما",
            "Action" to "أكشن"
        )

        /** كم صفحةً نجلب للصفّ الأول (٢٥ عنصراً في الصفحة). */
        private const val NEWEST_PAGES = 2

        private const val PAGE_SIZE = 25
    }

    override var name = "شورت دراما هب"
    override var mainUrl = "https://shortdramahub.world/"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ar"

    // =============================== helpers ===============================

    private fun absUrl(path: String): String =
        if (path.startsWith("http")) path
        else mainUrl + path.removePrefix("/")

    /** ١١ حرفاً من صورة يوتيوب المصغّرة — كل عنصر في الفهرس يحمل واحداً. */
    private fun ytIdOf(thumb: String): String? =
        Regex("""/vi/([\w-]{11})/""").find(thumb)?.groupValues?.get(1)

    // =============================== catalogue ===============================

    /** عنصرٌ من فهرس الـAPI أو من بطاقة قسم. */
    private data class Cat(
        val title: String,
        val url: String,
        val poster: String?,
        val category: String?
    ) {
        /** معرّف يوتيوب يُشتق من المصغّرة — وهو ما يشغّله المشغّل. */
        val vid: String? get() = Regex("""/vi/([\w-]{11})/""").find(poster.orEmpty())
            ?.groupValues?.get(1)
    }

    /**
     * صفحةٌ واحدة من الـAPI. `limit` مُتجاهَل من الخادم (٢٥ دائماً)،
     * و`offset` يعمل على أي قيمة.
     */
    private suspend fun apiPage(offset: Int): List<Cat> {
        val res = app.get(
            "$SITE/api/home-movies?offset=$offset",
            headers = mapOf("User-Agent" to UA, "Accept" to "application/json")
        )
        if (!res.isSuccessful) {
            Log.w(TAG, "api offset=$offset http=${res.code}")
            return emptyList()
        }
        val root = runCatching { JSONObject(res.text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Cat>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val path = o.optString("url")
            val title = o.optString("title").trim()
            if (path.isBlank() || title.isBlank()) continue
            out.add(
                Cat(
                    title = title,
                    url = absUrl(path),
                    poster = o.optString("thumbnail").ifBlank { null },
                    category = o.optString("category").ifBlank { null }
                )
            )
        }
        return out
    }

    /**
     * صفحات الـAPI بالتوازي. الجلب المتوازي مُختبَر على هذا الموقع:
     * ١٩ طلباً متوازياً = ٣٫٢ ثانية، كلها ٢٠٠.
     */
    private suspend fun apiPages(offsets: List<Int>): List<Cat> = coroutineScope {
        offsets.map { off -> async(Dispatchers.IO) { apiPage(off) } }.awaitAll().flatten()
    }

    /**
     * بطاقات قسم. الصفحة نقية وبلا ترقيم، فما فيها هو كل القسم.
     * (بلا جلبٍ موازٍ: ثلاثة أقسام فقط.)
     */
    private suspend fun categoryPage(slug: String): List<Cat> {
        val res = app.get(
            "$SITE/category/$slug",
            headers = mapOf("User-Agent" to UA, "Accept-Language" to "ar")
        )
        if (!res.isSuccessful) {
            Log.w(TAG, "category $slug http=${res.code}")
            return emptyList()
        }
        val doc = Jsoup.parse(res.text)
        return doc.select("article.feed-card").mapNotNull { card ->
            val a = card.selectFirst("div.feed-info h3 a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = a.text().trim()
            if (href.isBlank() || title.isBlank()) return@mapNotNull null
            Cat(
                title = title,
                url = absUrl(href),
                poster = card.selectFirst("a.feed-cover img")?.attr("src")?.ifBlank { null },
                category = slug
            )
        }
    }

    /** بطاقة → كارت بحث. */
    private fun Cat.toSearch(): SearchResponse =
        newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
        }

    // ============================== main page ==============================

    /**
     * أربعة صفوف ثابتة، تُجلب كلها بالتوازي: صفُّ الأحدث من الـAPI، وثلاثة
     * صفوف أقسام. لا صفوف ديناميكية ولا «المزيد» — الموقع لا يملك ترقيماً
     * أصلاً، فصفٌّ بلا نهاية هو الصدق الوحيد الممكن هنا.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val lists = coroutineScope {
            // كل الطلبات تُطلق معاً ثم تُنتظر معاً — الصفحة الرئيسة تنتظر
            // كل صفوفها قبل أول رسم (سلوك CloudStream)، فعدد الطلبات هو
            // الرافعة الوحيدة على زمن أول رسم.
            val newest = async(Dispatchers.IO) {
                apiPages((0 until NEWEST_PAGES).map { it * PAGE_SIZE }).distinctBy { it.url }
            }
            val sections = CATEGORIES.map { (slug, label) ->
                async(Dispatchers.IO) {
                    label to categoryPage(slug).distinctBy { it.url }
                }
            }

            val out = mutableListOf<HomePageList>()
            val newestItems = newest.await()
            if (newestItems.isNotEmpty()) {
                out.add(HomePageList("أحدث الإضافات", newestItems.map { it.toSearch() }))
            }
            for (section in sections) {
                val (label, items) = section.await()
                if (items.isNotEmpty()) {
                    out.add(HomePageList(label, items.map { it.toSearch() }))
                }
            }
            out
        }
        return newHomePageResponse(lists)
    }

    // =============================== search ===============================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    /**
     * البحث من الخادم: `/search?q=` صفحةٌ واحدة تُعيد **كل** المطابقات
     * (بلا ترقيم). قيس أن نتائجه مطابقة تماماً لمطابقةٍ نصية محلّية على
     * الفهرس الكامل (السجن ٥/٥، الحب ٤/٤، زوجته ٣٥/٣٥، امرأة ٨/٨) —
     * فطلبٌ واحد يكفي ولا حاجة لجلب ٤٥٥ عنصراً في كل بحث.
     *
     * عند **عطلٍ** في الخادم (لا عند صفر نتيجة حقيقية) نرتدّ لمطابقة محلّية
     * على الفهرس كاملاً — أبطأ، لكنه يبقي البحث عاملاً بدل أن يُظهر
     * «لا نتائج» كاذبة. أما صفرُ نتيجةٍ من خادمٍ سليم فهو جوابٌ صحيح
     * يُمرَّر كما هو، ولا يُكلِّف المستخدم جلب ٤٥٥ عنصراً بلا سبب.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val res = runCatching {
            app.get(
                "$SITE/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"),
                headers = mapOf("User-Agent" to UA, "Accept-Language" to "ar")
            )
        }.getOrNull()

        if (res != null && res.isSuccessful) {
            val doc = Jsoup.parse(res.text)
            // نتقبّل الصفحة كصحيحة فقط إن حملت بنية الموقع فعلاً؛ صفحةٌ
            // بلا أي بطاقة ولا قسمٍ هي علامةُ عطلٍ لا علامةُ «لا نتائج».
            val looksSane = doc.selectFirst("article.feed-card") != null ||
                doc.selectFirst("section") != null
            if (looksSane) {
                val out = doc.select("article.feed-card").mapNotNull { card ->
                    val a = card.selectFirst("div.feed-info h3 a") ?: return@mapNotNull null
                    val href = a.attr("href")
                    val title = a.text().trim()
                    if (href.isBlank() || title.isBlank()) return@mapNotNull null
                    Cat(title, absUrl(href), card.selectFirst("a.feed-cover img")?.attr("src"), null)
                        .toSearch()
                }
                Log.d(TAG, "search '$q' -> ${out.size} (server)")
                return out
            }
            Log.w(TAG, "search '$q': server page not sane (${res.text.length} bytes) — falling back")
        } else {
            Log.w(TAG, "search '$q': server http=${res?.code} — falling back")
        }

        // ارتداد محلّي — الفهرس كاملاً ثم مطابقة نصية.
        val all = apiPages((0 until 19).map { it * PAGE_SIZE }).distinctBy { it.url }
        val key = normalize(q)
        val out = all.filter { normalize(it.title).contains(key) }.map { it.toSearch() }
        Log.d(TAG, "search '$q' -> ${out.size} (local over ${all.size})")
        return out
    }

    /**
     * توحيد النص العربي: التشكيل والتطويل تُزال، والألف بأشكالها تُردّ إلى
     * «ا»، والتاء المربوطة إلى «ه»، والياء إلى «ى» — فتُطابق «الانين»
     * «الأنين» و«زوجه» «زوجة».
     */
    private fun normalize(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.code in 0x064B..0x065F -> {}
                ch.code == 0x0640 -> {}
                ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> b.append('ا')
                ch == 'ة' -> b.append('ه')
                ch == 'ى' -> b.append('ي')
                ch == 'ؤ' -> b.append('و')
                ch == 'ئ' -> b.append('ي')
                else -> b.append(ch)
            }
        }
        return b.toString().lowercase()
    }

    // ================================ load ================================

    /**
     * صفحة المشاهدة → معرّف يوتيوب.
     *
     * الـ`data` المُسلَّم إلى `loadLinks` هو **رابط يوتيوب كامل** لا معرّفاً
     * عارياً، عن قصد: CloudStream يمرّر الروابط عبر `fixUrl` قبل `load`،
     * فرابطٌ لا يبدأ بـ`http` قد يُلصق عليه `mainUrl` فيتلف. ورابط
     * `https://www.youtube.com/watch?v=…` يبقى قابلاً للاستخراج مهما لُصق
     * عليه (انظر `extractVid`).
     */
    override suspend fun load(url: String): LoadResponse? {
        val target = if (url.startsWith("http")) url else absUrl(url)

        val res = runCatching {
            app.get(target, headers = mapOf("User-Agent" to UA, "Accept-Language" to "ar"))
        }.getOrNull()
        if (res == null || !res.isSuccessful) {
            Log.w(TAG, "load http=${res?.code} url=$target")
            return null
        }
        val html = res.text
        val doc = Jsoup.parse(html)

        // معرّف يوتيوب: الإطار المدمج أولاً، ثم صورة og، ثم أي مصغّرة.
        // (مقيس: كل صفحة مشاهدة تحمل إطاراً واحداً بالضبط.)
        val vid = Regex("""youtube(?:-nocookie)?\.com/embed/([\w-]{11})""").find(html)
            ?.groupValues?.get(1)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { ytIdOf(it) }
            ?: ytIdOf(html)
        if (vid == null) {
            Log.w(TAG, "load no youtube id in ${html.length} bytes url=$target")
            return null
        }

        // العنوان: og:title يحمل اسم الموقع مذيّلاً — نقطعه عنده.
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val title = og.substringBefore(" - Watch Online").trim()
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
            .ifBlank { "فيديو" }

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: "https://img.youtube.com/vi/$vid/maxresdefault.jpg"

        Log.d(TAG, "load vid=$vid title=${title.take(40)}")

        return newMovieLoadResponse(title, target, TvType.Movie, "https://www.youtube.com/watch?v=$vid") {
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
     * اختيار الملف الصوتي المرافق لجودة فيديو معيّنة: مواءمة كوديك الفيديو
     * (webm ↔ webm، mp4 ↔ mp4) ثم أعلى بت/ث.
     */
    private fun pickAudio(audios: List<SdhAudioInfo>, video: SdhStreamInfo): SdhAudioInfo? {
        val family = if (video.mimeType.contains("webm")) { a: SdhAudioInfo ->
            a.mimeType.contains("webm")
        } else { a: SdhAudioInfo ->
            a.mimeType.contains("mp4")
        }
        val compatible = audios.filter(family).ifEmpty { audios }
        return compatible.maxByOrNull { it.bitrate }
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
     * يصدّر ترجمةً واحدةً للمشغّل — العربية، بلا اختيار من المستخدم.
     *
     * يوتيوب ينشر على هذه المقاطع مسارات `asr` تلقائية (`ar` و`en`)، ولا
     * ينشر ترجماتٍ مكتوبة. الجلب عبر `HttpURLConnection` (HTTP/1.1) لا عبر
     * `app.get`، لسببٍ موثّق في `ShortDramaHubSubServer`. والنص يُثبَّت على
     * خادم محلي ثم يُسلَّم رابط `127.0.0.1` — لأن `SubtitleFile` لا يحمل
     * محتوىً، ولأن المشغّل يرفض عناوين `data:`.
     *
     * الفشل يُبتلَع بصمت: ترجمةٌ غير ظاهرة أفضل من استثناءٍ يصل المشغّل.
     */
    private suspend fun emitSubtitle(
        vid: String,
        extractor: YoutubeStreamExtractor,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (!subtitlesOn()) return
        try {
            // حارس التكرار: `resolveFromNewPipe` قد تُستدعى أكثر من مرة في
            // نداء `loadLinks` واحد، فبلا هذا الحارس تنزل نفس الترجمة مراراً.
            if (subsEmittedFor.putIfAbsent(vid, true) != null) return

            val tracks = runCatching { extractor.subtitlesDefault }
                .getOrNull()?.filterNotNull() ?: return
            if (tracks.isEmpty()) return

            val referer = "https://www.youtube.com/watch?v=$vid"

            // العربية أولاً (مسار asr)، وإلا أي مسارٍ موقَّع متاح.
            val base = tracks.firstOrNull { it.locale?.language?.startsWith("ar") == true && !it.url.isNullOrBlank() }?.url
                ?: tracks.firstOrNull { !it.url.isNullOrBlank() }?.url
                ?: return

            // ★ الصيغ بالترتيب بلا `fmt` في الأولى: `sparams` الموقَّع لا
            //   يشمل `fmt` فتُهمَل أو تُرفض، و`fmt+vlang` معاً يردّان جسداً
            //   صفرياً دائماً (مقيس) — وهو ما يجعل «الترجمة لا تظهر».
            val text = fetchSubtitleText(base, referer).takeIf { it.isNotBlank() }
                ?: fetchSubtitleText("$base&fmt=vtt", referer).takeIf { it.isNotBlank() }
                ?: fetchSubtitleText("$base&fmt=json3", referer)
            if (text.isBlank()) return

            val vtt = toWebVtt(text)
            if (vtt.isBlank()) return

            val local = ShortDramaHubSubServer.register(vtt) ?: return
            subtitleCallback(
                newSubtitleFile(subLangLabel("ar"), local) {
                    this.headers = mapOf("Referer" to "https://www.youtube.com/")
                }
            )
            Log.d(TAG, "$vid subtitle ok bytes=${text.length}")
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
     * يحوّل نص يوتيوب إلى WebVTT — وهو ما يقبله المشغّل.
     *
     * ★ الفخّ: `timedtext` يردّ **200 بجسد صفري** عند الحجب على الشبكة،
     *   فيكفي فحص `responseCode` تسجيلاً لترجمةٍ فارغة تظهر ولا تعرض شيئاً.
     *   لذلك نرفض الجسد الفارغ هنا — في الموضع الذي تنشأ فيه المشكلة.
     */
    private fun toWebVtt(raw: String): String {
        val t = raw.trim().removePrefix("﻿").trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("WEBVTT")) return if (t.contains("-->")) t else ""
        return try {
            when {
                t.startsWith("{") -> json3ToVtt(t)
                t.startsWith("<") -> xmlToVtt(t)
                t.contains("-->") -> t
                else -> ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "vtt convert failed: ${e.message}")
            ""
        }
    }

    /** `json3`: `events[].segs[].utf8` مع `tStartMs`/`dDurationMs`. */
    private fun json3ToVtt(raw: String): String {
        val root = org.json.JSONObject(raw)
        val events = root.optJSONArray("events") ?: return ""
        val sb = StringBuilder("WEBVTT\n\n")
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val line = StringBuilder()
            for (j in 0 until segs.length()) {
                line.append(segs.optJSONObject(j)?.optString("utf8", "").orEmpty())
            }
            val text = line.toString().trim()
            if (text.isEmpty()) continue
            val start = ev.optDouble("tStartMs", 0.0).toLong()
            val dur = ev.optDouble("dDurationMs", 0.0).toLong().coerceAtLeast(1L)
            sb.append(ts(start)).append(" --> ").append(ts(start + dur)).append('\n')
            sb.append(text).append("\n\n")
        }
        return if (sb.length <= 8) "" else sb.toString()
    }

    /** `xml`: `<text start="0.5" dur="1.2">…</text>`. */
    private fun xmlToVtt(raw: String): String {
        val sb = StringBuilder("WEBVTT\n\n")
        // نعتمد على <text …> ونقرأ الخصائص بالاسم لا بترتيبها: يوتيوب
        // لا يضمن أن `start` تسبق `dur`.
        val tagRe = Regex("""<text\b([^>]*)>([\s\S]*?)</text>""")
        var found = false
        for (m in tagRe.findAll(raw)) {
            val attrs = m.groupValues[1]
            val body = unescapeXml(m.groupValues[2]).replace('\n', ' ').trim()
            if (body.isEmpty()) continue
            val start = attrDouble(attrs, "start") ?: 0.0
            val dur = attrDouble(attrs, "dur") ?: 2.0
            sb.append(ts(start.toLong())).append(" --> ").append(ts((start + dur).toLong()))
                .append('\n')
            sb.append(body).append("\n\n")
            found = true
        }
        return if (found) sb.toString() else ""
    }

    /** يقرأ خاصية رقمية من نص الخصائص، بهامش مسافةٍ اختياري حول `=`. */
    private fun attrDouble(attrs: String, name: String): Double? =
        Regex("""\b$name\s*=\s*"([\d.]+)"""").find(attrs)
            ?.groupValues?.get(1)?.toDoubleOrNull()

    /**
     * WebVTT timecode: `HH:MM:SS.mmm`.
     *
     * ★ `Locale.US` مقصود: بلاه تخرج الأرقام عربية-هندية على هاتفٍ عربي
     *   (`٠٥:٠٣:١٢`) فيرفض المشغّل التوقيت ولا يعرض الترجمة أصلاً.
     */
    private fun ts(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val msec = ms % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", h, m, s, msec)
    }

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    /**
     * المسار الذي يعمل: `YoutubeStreamExtractor.fetchPage()` يعطي روابط
     * googlevideo **مفكوكة التوقيع ومعامل n** مع نطاقات Initialization/Index
     * لكل جودة، ثم نبني منها مانيفست DASH محلي (`127.0.0.1`) يعرض كل جودة
     * مع أفضل صوت لها، فيتلقى يوتيوب طلبات Range شرعية — لا ترفض 2004.
     *
     * هذا ليس تفضيلاً: روابط googlevideo **غير موجودة أصلاً** في صفحة
     * يوتيوب لهذه المقاطع (كل تنسيق بلا `url`)، فالمسار المباشر مستحيل.
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
            // وكوديكه: avc وvp9 وav1) بدل طيّ كل ارتفاع إلى تنسيقٍ واحد،
            // فيظهر في اللاعب كل الجودات المتاحة بدل جزءٍ منها.
            val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val streamUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(streamUrl)) return@mapNotNull null

                    val label = qualityLabelOf(vs)
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    var mime = vs.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = ShortDramaHubDashServer.mimeFromUrl(streamUrl, false)

                    val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                    val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                    SdhStreamInfo(streamUrl, mime, height, label, initR, indexR, codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

            val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                try {
                    val aUrl = asr.content ?: return@mapNotNull null
                    val bitrate = runCatching { asr.bitrate ?: 128000 }.getOrNull() ?: 128000
                    var mime = runCatching { asr.format?.mimeType }.getOrNull()
                    if (mime.isNullOrEmpty()) mime = ShortDramaHubDashServer.mimeFromUrl(aUrl, true)

                    val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                    val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null
                    var rawLang = runCatching { asr.audioTrackId ?: "Default" }.getOrNull() ?: "Default"
                    if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                    SdhAudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase(), codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

            // ★ لا شيء يُجلب عبر الشبكة هنا: الروابط تُبثّ فوراً أدناه، ثم
            //   تُطلق الترجمة في خيط منفصل. الجلبُ الشبكي للترجمة قبل
            //   إصدار الروابط كان يوقف `loadLinks` حتى 20 ثانية — فيبدو
            //   للمستخدم «الفيديو يدور ولا يشتغل».
            ShortDramaHubDashServer.ensureStarted()

            // إعداد «الجودات»: الأعلى فقط عند اختيار high.
            val effectiveVideos = if (qualityMode() == "high") {
                val top = videoOnlyList.maxByOrNull { it.height }
                if (top != null) videoOnlyList.filter { it.height == top.height } else videoOnlyList
            } else videoOnlyList

            // كل تنسيق فيديو يُبث — بلا استثناء — مقترناً بصوتٍ مختارٍ من
            // تنسيقاته. عند غياب الصوت نُبث الفيديو وحده بدل أن يسقط التنسيق.
            for (video in effectiveVideos) {
                val bestAudio = pickAudio(audioInfoList, video)
                val label = richLabel(video.height, video.url, video.mimeType)

                val localLink = ShortDramaHubDashServer.buildAndRegister(
                    video, if (bestAudio != null) listOf(bestAudio) else emptyList(),
                    durationSeconds
                )
                if (localLink != null) {
                    callback(
                        newExtractorLink(
                            name, FormatTag.tagged(label, localLink, ExtractorLinkType.DASH),
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
                        newExtractorLink(name, FormatTag.tagged("$mLabel (Legacy)", mUrl, INFER_TYPE), mUrl, type = INFER_TYPE) {
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

    /**
     * بديل مباشر: صفحة watch ثم نستخرج `ytInitialPlayerResponse`.
     *
     * على هذا الموقع يعطي صفراً دائماً — كل تنسيقٍ بلا `url` — لكنه يبقى
     * مساراً ثالثاً لأن `serverAbrStreamingUrl` موجود وقد يخدم يوماً.
     */
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
            fun emit(url: String, label: String, quality: Int, headers: Map<String, String> = mapOf()) {
                if (url.isBlank()) return
                // أسلوب NewPipe: إضافة cpn (content playback nonce) لكل رابط يوتيوب
                val withCpn = if (url.contains(".googlevideo.com")) {
                    if (url.contains("cpn=")) url
                    else url + (if (url.contains("?")) "&" else "?") + "cpn=" + randomCpn()
                } else url
                produced++
                callback(
                    ExtractorLink(
                        name, label, withCpn, watchUrl, quality, headers, null,
                        ExtractorLinkType.VIDEO, emptyList<AudioFile>()
                    )
                )
                if (useRedirect() && withCpn.contains(".googlevideo.com") && !withCpn.contains("redirector.googlevideo.com")) {
                    val redirected = redirectHost(withCpn)
                    if (redirected != withCpn) {
                        produced++
                        callback(
                            ExtractorLink(
                                name, "$label · redirect", redirected, watchUrl, quality, headers, null,
                                ExtractorLinkType.VIDEO, emptyList<AudioFile>()
                            )
                        )
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
                val label = "SD ${if (q.isNotBlank()) q else f.optInt("itag").toString()}"
                emit(url, label, f.optInt("itag"), abrHeaders)
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

    /**
     * استخراج معرّف يوتيوب من `data` بأي شكلٍ وصل.
     *
     * عمداً متسامح: `data` قد يصل معرّفاً عارياً، أو رابط `watch?v=…`، أو
     * رابطاً لُصق عليه `mainUrl` (`…world/https://www.youtube.com/watch?v=…`).
     * كلُّ شكلٍ له نمطه، والنتيجة نفسها.
     */
    private fun extractVid(data: String): String? {
        val raw = data.trim()
        if (Regex("""^[\w-]{11}$""").matches(raw)) return raw
        Regex("""[?&]v=([\w-]{11})""").find(raw)?.let { return it.groupValues[1] }
        Regex("""youtube(?:-nocookie)?\.com/embed/([\w-]{11})""").find(raw)?.let { return it.groupValues[1] }
        Regex("""/vi/([\w-]{11})/""").find(raw)?.let { return it.groupValues[1] }
        Regex("""youtu\.be/([\w-]{11})""").find(raw)?.let { return it.groupValues[1] }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // كل نداءٍ هو لمشاهد جديد: تصفير حارس تكرار الترجمة.
        subsEmittedFor.clear()
        Log.d(TAG, "loadLinks raw=$data")

        val vid = extractVid(data)
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
                1 -> loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
                    sink(link); links++
                }
                2 -> links += resolveFromHtml(vid, subtitleCallback, sink)
                else -> links += resolveFromNewPipe(vid, subtitleCallback, sink)
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
        val order = prefs?.getString(ShortDramaHubSettings.KEY_QUALITY_ORDER, "default")
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

    private val subsEmittedFor = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
}
