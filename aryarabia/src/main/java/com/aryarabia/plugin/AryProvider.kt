package com.aryarabia.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

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
class AryProvider : MainAPI() {

    companion object {
        private const val TAG = "AryArabia"

        private const val CHANNEL_ID = "UC6ApcZBKUPwuL4QcGeWaTZw"

        /** صفحة تبويب «قوائم التشغيل» في القناة — GET على HTML نجلب منها ytInitialData. */
        private const val PLAYLISTS_URL = "https://www.youtube.com/channel/$CHANNEL_ID/playlists"

        private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?key=$INNERTUBE_KEY&prettyPrint=false"
        private const val CLIENT_VERSION = "2.20260918.00.00"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val BOM = "﻿"

        /**
         * قوائم ليست مسلسلات. القناة تنشر لكل مسلسل قائمةً أو أكثر من قوائم
         * الملحقات (إعلان ترويجي / تشويقي، أفضل اللحظات، أغاني، Shorts)،
         * إضافة إلى قوائم عامة (الأفضل لدى، أحدث الفيديوهات، أفلام). كل هذه
         * تُستبعد وإلا ظهرت في الواجهة كأنها مسلسلات.
         */
        private val SKIP_RE = Regex(
            "إعلان|تشويق|تريلر|trailer|ملخص|مشهد|Shorts|أفضل اللحظات|أجمل اللحظات|" +
                "أغاني|برومو|الأفضل لدى|أحدث فيديو|أحدث الفيديوهات|Latest Videos|أفلام",
            RegexOption.IGNORE_CASE
        )

        /**
         * القناة لا تُوحّد كتابة الكلمة: «الحلقة 3» و«حلقة 24» و«اللحقة 5»
         * (خطأ مطبعي) كلها موجودة فعلاً. وكلها تحتوي «حلقة» كسلسلة فرعية،
         * فالأنماط أدناه بلا «ال» تلتقط الثلاثة معاً.
         */
        private val EP_NUM_RE = Regex("""حلقة\s*(\d+)""")
        private val FINALE_RE = Regex("""حلقة\s*(?:الأخيرة|الاخيرة|أخيرة|اخيرة)""")
    }

    override var name = "ARY العربية"
    override var mainUrl = "https://www.youtube.com/channel/$CHANNEL_ID"
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

    /** «مسلسل نهاية قلبي» و«نهاية قلبي | ARY» كلاهما «نهاية قلبي». */
    private fun bareName(title: String): String {
        var t = clean(title).replace(Regex("""\s+"""), " ")
        t = t.substringBefore('|').substringBefore('–').substringBefore(" - ").trim()
        if (t.startsWith("مسلسل ")) t = t.removePrefix("مسلسل ").trim()
        return t
    }

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

    // ============================== playlists ==============================

    private data class PlaylistInfo(
        val id: String,
        val title: String,
        val cover: String?,
        val count: Int = 0
    )

    /**
     * كل قوائم القناة: صفحة tab «قوائم التشغيل» الجاهزة تعرض أول 30،
     * ثم نتابع بطلب صفحة ثانية لاسترداد الباقي (القناة عندها ~54 قائمة).
     * قائمة شبه ثابتة، فتُحفظ في الذاكرة وتخدم الصفحة الرئيسية والبحث معاً.
     */
    @Volatile
    private var cachedPlaylists: List<PlaylistInfo>? = null

    private suspend fun allPlaylists(): List<PlaylistInfo> {
        cachedPlaylists?.let { return it }

        val out = mutableListOf<PlaylistInfo>()
        try {
            // الصفحة الأولى: GET على HTML — الطريق الأسرع والأقل عرضة للرفض.
            val first = fetchInitialData(PLAYLISTS_URL)
            for (l in lockupsOf(first)) {
                if (!l.type.contains("PLAYLIST")) continue
                if (out.any { it.id == l.id }) continue
                out.add(PlaylistInfo(l.id, l.title, l.thumb, l.count))
            }

            // الصفحة الثانية: POST عبر InnerTube فقط (الـ GET لا يقلّبها).
            // إن فشل نحتفظ بالصفحة الأولى — لا نُسقط كل القوائم.
            val token = continuationTokenOf(first)
            val second = token?.let { continuationPage(it) }
            if (second != null) {
                for (l in lockupsOf(second)) {
                    if (!l.type.contains("PLAYLIST")) continue
                    if (out.any { it.id == l.id }) continue
                    out.add(PlaylistInfo(l.id, l.title, l.thumb, l.count))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playlists tab failed: ${e.message}")
        }

        Log.d(TAG, "playlists: ${out.size}")
        if (out.isNotEmpty()) cachedPlaylists = out
        return out
    }

    /** حلقات قائمة: 60–100+ حلقة يُعيدها يوتيوب كاملةً في الصفحة الأولى. */
    private suspend fun playlistItems(playlistId: String): List<Lockup> {
        return try {
            val data = fetchInitialData("https://www.youtube.com/playlist?list=$playlistId")
            lockupsOf(data).filter { it.type.contains("VIDEO") }
        } catch (e: Exception) {
            Log.w(TAG, "playlist $playlistId failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * يحوّل عناصر القائمة إلى حلقات مرقّمة مرتّبة. القناة لا ترقّم الحلقة
     * الأخيرة، فتُوضع بعد أعلى رقم مُرقّم.
     */
    private fun episodesOf(items: List<Lockup>): List<Pair<Int, Lockup>> {
        val numbered = mutableListOf<Pair<Int, Lockup>>()
        var finale: Lockup? = null
        for (i in items) {
            if (skipTitle(i.title)) continue
            val n = episodeNumberOf(i.title)
            when {
                n != null -> numbered.add(n to i)
                isFinale(i.title) && finale == null -> finale = i
            }
        }
        val maxNum = numbered.maxOfOrNull { it.first } ?: 0
        finale?.let { numbered.add((maxNum + 1) to it) }
        return numbered.distinctBy { it.second.id }.sortedBy { it.first }
    }

    // ============================== main page ==============================

    /**
     * الصفحة الرئيسية = قوائم القناة (المسلسلات). طلب أو طلبان فقط: لا
     * نعدّد حلقات كل قائمة هنا (23 قائمة × صفحتان = 46 طلباً بلا داعٍ)،
     * والحلقات تُجلب عند فتح المسلسل.
     *
     * لا نضيف صفاً من تبويب «الفيديوهات»: جُرِّب وقيس، فالقناة ترفع مقاطع
     * قصيرة من مسلسل واحد (450 فيديو متتالٍ كلها «الغيرة»، وبعد 15 صفحة
     * لم يظهر مسلسل ثانٍ)، فالصف كان سيبدو كأن القناة مسلسلٌ واحد.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        return homeFrom(allPlaylists())
    }

    private fun homeFrom(playlists: List<PlaylistInfo>): HomePageResponse {
        val cards = dedupe(playlists).map { p ->
            newTvSeriesSearchResponse(bareName(p.title), "ary://pl/${p.id}") {
                this.posterUrl = p.cover
            }
        }
        if (cards.isEmpty()) return newHomePageResponse(emptyList())
        return newHomePageResponse(listOf(HomePageList("مسلسلات ARY العربية", cards)))
    }

    /** الصفحة الرئيسية كصف واحد — يُستخدم للبحث أيضاً. */
    private fun searchRow(playlists: List<PlaylistInfo>): List<SearchResponse> =
        homeFrom(playlists).items.firstOrNull()?.list ?: emptyList()

    /**
     * يستبعد قوائم الملحقات والقوائم العامة، ويزيل تكرار الاسم نفسه
     * (نفس المسلسل في أكثر من قائمة). عند التكرار نُبقي القائمة الأكبر،
     * لأن قائمة الإعلانات الترويجية أصغر من قائمة المسلسل دوماً.
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

        val pool = dedupe(playlists)
        val hits = if (words.isEmpty()) {
            pool.filter { keyOf(it.title).contains(keyOf(q)) }
        } else {
            pool.filter { p ->
                val hay = keyOf(p.title)
                words.all { hay.contains(it) }
            }
        }

        Log.d(TAG, "search '$q' -> ${hits.size}")
        return homeFrom(hits).items.firstOrNull()?.list ?: emptyList()
    }

    // ================================ load ================================

    override suspend fun load(url: String): LoadResponse? {
        // روابطنا الداخلية: ary://pl/<playlistId>
        if (url.startsWith("ary://pl/")) {
            val pid = url.removePrefix("ary://pl/").trim()
            if (pid.isBlank()) return null
            val info = allPlaylists().firstOrNull { it.id == pid }
                ?: PlaylistInfo(pid, "مسلسل", null)
            return loadPlaylist(info)
        }

        // روابط يوتيوب الحقيقية، لمن يفتح الإضافة من خارجها
        Regex("""[?&]list=([\w-]+)""").find(url)?.let { m ->
            val pid = m.groupValues[1]
            val info = allPlaylists().firstOrNull { it.id == pid }
                ?: PlaylistInfo(pid, "مسلسل", null)
            return loadPlaylist(info)
        }
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
            // بيانات الحلقة = معرّف الفيديو؛ يُبنى رابط المشاهدة وقت التشغيل.
            newEpisode(l.id) {
                this.name = l.title.ifBlank { "الحلقة $num" }
                this.episode = num
                this.posterUrl = l.thumb ?: posterOf(l.id)
            }
        }

        Log.d(TAG, "playlist '${info.title}' -> ${episodes.size} episodes")

        return newTvSeriesLoadResponse(name, "ary://pl/${info.id}", TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ============================== loadLinks ==============================

    /**
     * نُمرّر رابط المشاهدة العادي فقط: مُستخرِج يوتيوب المدمج في
     * CloudStream يعرف يوتيوب أصلاً، ويحلّ الرابط إلى ملفات googlevideo.com
     * التي تدعم التقديم والتأخير. لا نُصدر صفحة HTML أبداً (سبب الخطأ 2004).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = data.trim()
        if (!Regex("""^[\w-]{11}$""").matches(vid)) {
            Log.w(TAG, "loadLinks: unexpected data '$data'")
            return false
        }
        val watchUrl = "https://www.youtube.com/watch?v=$vid"

        return try {
            loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(TAG, "extraction failed for $vid: ${e.message}")
            false
        }
    }
}
