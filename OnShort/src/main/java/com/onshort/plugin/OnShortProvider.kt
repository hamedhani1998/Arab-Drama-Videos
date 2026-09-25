package com.onshort.plugin

import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val ONS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

// قواعد واجهات برمجة التطبيقات
private const val ONS_MAIN = "https://onshort.net/ar"
private const val ONS_API = "https://onshort.net/wp-json/onshort-theme/v1"
private const val ONS_PLAY_API = "https://onshort.net/wp-json/onshort-player/v1/episode"

// منصات يرفضها سيرفر OnShort نفسه نهائيًا ("REST bridge"/"not handled").
// عند فشل التشغيل يتجاهلها جسر إعادة البحث (لا يعتمد عليها كمنشور بديل).
private val REJECTED_PLATFORMS = setOf(
    "netshort", "shortmax", "dramabite", "storyreel", "vibeshort-goodbos"
)

// بطاقة مسلسل على الصفحة الرئيسية (يُحلَّل من HTML الخاص بـ listing)
private val cardRe = Regex(
    """<article class="series-card"[^>]*data-series-card="(\d+)"[^>]*>\s*<a class="series-card__link" href="([^"]+)"[^>]*>.*?<img[^>]*src="([^"]+)"[^>]*alt="([^"]*)"[^>]*>.*?<span class="episode-pill">(\d+)\s*<small""",
    RegexOption.DOT_MATCHES_ALL
)

// مفاتيح التذكرة في صفحة التفاصيل (أو في page?p={id})
private val detailPostRe = Regex("""data-post="(\d{3,12})"""")
private val detailTicketRe = Regex("""data-player-ticket="([^"]+)"""")

/**
 * OnShort — WordPress REST API يجمّع عدة منصات (shortmax/netshort/reelshort/goodshort…).
 * النسخة العربية فقط.
 *
 * الموقع بطيء ويقيّد المعدل بشدة، لذا:
 * - load(): يبني قائمة الحلقات فورًا من بيانات مضمّنة في رابط البطاقة (؟cs=)
 *   ولا يعتمد على جلب صفحة التفاصيل (بلا شبكة وبلا أخطاء).
 * - data الحلقة = "postId|رقم" (صيغة قياسية، بلا روابط طويلة تتلف في التطبيق).
 * - التذكرة تُجلب في الخلفية أثناء load() وتُحفظ، وتُستعاد عند التشغيل.
 */
class OnShortProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "OnShort"
    override var mainUrl = ONS_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // تشخيص: سجّل إلى logcat (android.util.Log متاح دائمًا على Android).
    private fun logD(msg: String) { try { android.util.Log.d("OnShort", msg) } catch (_: Exception) {} }
    private fun logE(msg: String) { try { android.util.Log.e("OnShort", msg) } catch (_: Exception) {} }

    // الواجهة الرئيسية: صف لكل منصة من المنصات التي يجمعها الموقع،
    // بحيث تظهر مسلسلات كل منصة مضمّنة في قسمها المخصص.
    // المفتاح = اسم المنصة كما يظهر في رابط /ar/platform/{slug}/.
    // كل المنصات المتوفرة في الموقع (الـ 16 slugs كلها تُرجع بطاقات من /ar/platform/{slug}/ —
    // تحقّقت أغسطس 2026). ملاحظة: حتى لو توفّرت بطاقات منصة، قد لا يُشغّلها مشغّلُ الموقع نفسُه
    // (يرجع "Provider is not handled by REST bridge" مثل NetShort/StoryReel/DramaBite/VibeShort) —
    // هذا قيدٌ من جهة السيرفر، وليس خطأً في الإضافة.
    // ترتيب: المنصات العاملة فعليًا عبر OnShort أولًا (10)، ثم المنصات الست المرفوضة
    // من سيرفر OnShort نفسه في نهاية القائمة. هذه — وإن لم تُشغَّل عبر OnShort — تُشتغل
    // عبر جسر Mosalsaly (بحث تلقائي بالعنوان في mosalsaly.com ثم بثّ /api/episode-source)،
    // قابل للإيقاف من إعدادات الوحدة.
    private val platformRows = listOf(
        "reelshort" to "أحدث مسلسلات ReelShort",
        "dramabox" to "أحدث مسلسلات DramaBox",
        "idrama" to "أحدث مسلسلات iDrama",
        "dramawave" to "أحدث مسلسلات DramaWave",
        "flextv" to "أحدث مسلسلات FlexTV",
        "freereels" to "أحدث مسلسلات FreeReels",
        "microdrama" to "أحدث مسلسلات MicroDrama",
        "moborels" to "أحدث مسلسلات MoBorels",
        "shortswave" to "أحدث مسلسلات ShortsWave",
        "stardusttv" to "أحدث مسلسلات StardustTV",
        "dotdrama" to "أحدث مسلسلات DotDrama",
        // ————— غير متاحة عبر OnShort (مرفوضة من سيربره) — تُشتغل عبر جسر Mosalsaly —————
        "netshort" to "NetShort (عبر Mosalsaly)",
        "shortmax" to "ShortMax (عبر Mosalsaly)",
        "goodshort" to "GoodShort (عبر Mosalsaly)",
        "dramabite" to "DramaBite (عبر Mosalsaly)",
        "storyreel" to "StoryReel (عبر Mosalsaly)",
        "vibeshort-goodbos" to "VibeShort (عبر Mosalsaly)",
    )

    override val mainPage = mainPageOf(
        *platformRows.map { (slug, label) -> slug to label }.toTypedArray()
    )

    // تذكرة جلستنا المؤقتة لكل مسلسل (مفتاح = postId)
    private class PlayCache(val postId: String, val ticket: String)
    @Volatile private var cached: PlayCache? = null
    // ذاكرة تخزين مؤقت للبحث: الاستعلامات المتكررة تعود فورًا دون لمس السيرفر البطيء/المحدود.
    // خريطة query -> (millis, النتائج). نُعيده فقط إذا كان قديمًا بأقل من 5 دقائق.
    @Volatile private var searchCache: MutableMap<String, Pair<Long, List<SearchResponse>>>? = null
    // خيط الجلب الخلفي الحالي — حتى ينتظر loadLinks عليه بدلًا من إعادة جلب الصفحة البطيئة
    @Volatile private var prefetchThread: Thread? = null

    private fun headers() = mapOf(
        "User-Agent" to ONS_UA,
        "Accept" to "application/json, text/plain, */*",
    )

    private suspend fun getWithRetry(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = mapOf(),
        attempts: Int = 3
    ): String? {
        for (i in 0 until attempts) {
            try {
                val text = app.get(url, referer = referer, headers = headers).text
                if (!text.isNullOrBlank()) return text
            } catch (_: Exception) {
                // 504/502/مهلة — أعد المحاولة
            }
            if (i < attempts - 1) Thread.sleep(1200L * (i + 1))
        }
        return null
    }

    // ---------- بيانات مضمّنة في رابط البطاقة (لجعل load() فوريًا) ----------
    // النموذج: {realUrl}?cs={id}|{total}|{title}|{poster}|{platform}
    // platform = صف المنصة في OnShort (مثل storyreel/dramabite/... لصفحة ذكرها الرئيسية
    // أو platform.slug من البحث) — يُمرَّر إلى loadLinks ليفعّل جسر Mosalsaly عند الحاجة.
    private fun embedMeta(realUrl: String, id: String, total: Int, title: String, poster: String?, platform: String?): String {
        return "$realUrl?cs=${URLEncoder.encode(id, "UTF-8")}|${URLEncoder.encode(total.toString(), "UTF-8")}|" +
            "${URLEncoder.encode(title, "UTF-8")}|${URLEncoder.encode(poster ?: "", "UTF-8")}|" +
            "${URLEncoder.encode(platform ?: "", "UTF-8")}"
    }

    private data class Meta(val id: String, val total: Int, val title: String, val poster: String?, val platform: String?)

    // نتيجة بحث خام تحتفظ بمنصة النشر (platform.slug) — يستخدمها جسر إعادة البحث
    // لاختيار منشور بديل بمنصة قابلة للتشغيل (SearchResponse لا ينقل platform).
    private data class SearchRaw(val id: String, val url: String, val title: String, val platform: String?, val total: Int)

    private fun parseMeta(url: String): Meta? {
        val i = url.indexOf("?cs=")
        if (i < 0) return null
        val raw = url.substring(i + 4).split("|")
        if (raw.size < 2) return null
        val id = URLDecoder.decode(raw[0], "UTF-8")
        val total = URLDecoder.decode(raw[1], "UTF-8").toIntOrNull() ?: 1
        val title = if (raw.size > 2) URLDecoder.decode(raw[2], "UTF-8") else ""
        val poster = if (raw.size > 3 && raw[3].isNotEmpty()) URLDecoder.decode(raw[3], "UTF-8") else null
        val platform = if (raw.size > 4 && raw[4].isNotEmpty()) URLDecoder.decode(raw[4], "UTF-8") else null
        return Meta(id, total, title, poster, platform)
    }

    private fun stripMeta(url: String): String {
        val i = url.indexOf("?cs=")
        return if (i >= 0) url.substring(0, i) else url
    }

    // يستخرج الرقم من نهاية postId — فقد يصل رقمًا خامًا (186910) أو رابطًا كاملًا
    // (https://onshort.net/ar/186910) انطلاقًا من روابط قديمة/مشاركة/بحث بلا ؟cs=.
    // API التشغيل يتطلب الرقم فقط؛ أي شيء آخر → 403 → "لم يعثر على روابط".
    private fun extractPostId(raw: String): String {
        val m = Regex("""(\d{3,14})$""").find(raw.trim())
        return m?.groupValues?.get(1) ?: raw
    }

    // رابط صفحة التفاصيل من postId عبر ?p= (ووردبريس) — استخدم النطاق الجذر وليس /ar/
    // لأن ?p= في /ar/ لا يُرجع التذكرة بينما الجذر يرجعها (تحقّقت).
    private fun detailUrl(postId: String): String = "https://onshort.net/?p=$postId"

    // ---------- الصفحة الرئيسية / العرض ----------
    private fun parseListingHtml(html: String, seen: MutableSet<String>, platform: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (m in cardRe.findAll(html)) {
            val id = m.groupValues[1]
            val url = m.groupValues[2]
            val poster = m.groupValues[3]
            val title = m.groupValues[4]
            val total = m.groupValues[5].toIntOrNull() ?: 1
            val safeTitle = title.replace(Regex("""\s+"""), " ").trim()
            if (safeTitle.isBlank() || !seen.add(url)) continue
            val metaUrl = embedMeta(url, id, total, safeTitle, poster, platform)
            out.add(newTvSeriesSearchResponse(safeTitle, metaUrl, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            // request.data = مفتاح الصف = slug المنصة (shortmax/netshort/…)
            val slug = request.data
            val p = maxOf(page, 1)
            val url = if (p == 1) "$ONS_MAIN/platform/$slug/"
                else "$ONS_MAIN/platform/$slug/page/$p/"
            val resp = getWithRetry(url, mainUrl, headers()) ?: return null
            val items = parseListingHtml(resp, java.util.HashSet(), slug)
            if (items.isEmpty()) null
            else newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    // ---------- البحث ----------
    override suspend fun search(query: String): List<SearchResponse>? {
        val key = query.trim().lowercase()
        val now = System.currentTimeMillis()
        // ردّ فوري إن كان الاستعلام نفسه نُفّذ قبل أقل من 5 دقائق (سيرفر OnShort بطيء/محدود السرعة)
        val sc = searchCache?.get(key)
        if (sc != null && now - sc.first < 300_000) return sc.second
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            // limit أصغر = استجابة أسرع (السيرفر يحدّ السرعة). 24 كافية للبحث.
            val base = "$ONS_API/search?q=$q&limit=24&lang=ar"
            // جولة أولى فورية: أردّ فأرةً واحدة app.get بدون انتظار backoff (سريع إدراكيًا)
            // لا نستخدم getWithRetry هنا — الاستدعاء الأول يعود فورًا، وإن فشل نعيده مرة.
            val text = timeFirstSearch(base) ?: return null
            val node = mapper.readTree(text)
            val arr = node.get("results") ?: return emptyList()
            val out = mutableListOf<SearchResponse>()
            val seen = java.util.HashSet<String>()
            for (it in arr) {
                val title = it.get("title")?.asText()?.takeIf { x -> x.isNotBlank() }
                    ?: it.get("base_title")?.asText() ?: continue
                val url = it.get("url")?.asText() ?: continue
                val poster = it.get("cover")?.asText()
                val id = it.get("id")?.asText() ?: continue
                val total = it.get("total")?.asInt() ?: 1
                // أيضًا نمرر platform.slug (الكائن في البحث) — يفيد الجسر عندما تفشل منصة
                val platform = it.get("platform")?.get("slug")?.asText()?.takeIf { p -> p.isNotBlank() }
                if (!seen.add(url)) continue
                val metaUrl = embedMeta(url, id, total, title, poster, platform)
                out.add(newTvSeriesSearchResponse(title, metaUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    if (total > 1) this.episodes = total
                })
            }
            // خزّن النتيجة حتى لا يعيد لمس السيرفر ببطء
            if (searchCache == null) searchCache = java.util.concurrent.ConcurrentHashMap()
            searchCache!![key] = now to out
            out
        } catch (e: Exception) { null }
    }

    // استدعاء بحث واحد فوري (بدون backoff بين المحاولات): نجرب app.get مرة، وإن فشل نعيد مرة واحدة.
    private suspend fun timeFirstSearch(url: String): String? {
        try {
            val t = app.get(url, referer = mainUrl, headers = headers()).text
            if (!t.isNullOrBlank()) return t
        } catch (_: Exception) {}
        try { Thread.sleep(300L) } catch (_: InterruptedException) {}
        try {
            val t = app.get(url, referer = mainUrl, headers = headers()).text
            if (!t.isNullOrBlank()) return t
        } catch (_: Exception) {}
        return null
    }

    // بحث خام يحتفظ بمنصة النشر (platform.slug) لصالح جسر إعادة البحث.
    // يُستخدم نفس واجهة البحث لكن بلا ذاكرة التخزين المؤقت (نريد نتيجة طازجة
    // عند الفشل، وقد يُنشر العمل على عدة منصات نختار بينها). يعيد قائمة نتائج
    // مفرَّغة من التكرار حسب المعرف والمثبّتة في منصتها.
    private suspend fun searchRaw(query: String): List<SearchRaw> {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "$ONS_API/search?q=$q&limit=24&lang=ar"
            val text = timeFirstSearch(url) ?: return emptyList()
            val node = mapper.readTree(text)
            val arr = node.get("results") ?: return emptyList()
            val out = mutableListOf<SearchRaw>()
            val seen = java.util.HashSet<String>()
            for (it in arr) {
                val id = it.get("id")?.asText() ?: continue
                val url2 = it.get("url")?.asText() ?: continue
                val title = it.get("title")?.asText()?.takeIf { x -> x.isNotBlank() }
                    ?: it.get("base_title")?.asText() ?: continue
                if (!seen.add(id)) continue
                val platform = it.get("platform")?.get("slug")?.asText()?.takeIf { p -> p.isNotBlank() }
                out.add(SearchRaw(id, url2, title, platform, it.get("total")?.asInt() ?: 1))
            }
            return out
        } catch (e: Exception) { return emptyList() }
    }

    // جسر «إعادة البحث» (cross-post): حين يرفض سيرفر OnShort تشغيل المنشور الحالي
    // نهائيًا (أو يفشل مؤقتًا)، نبحث العنوان نفسه في OnShort ونختار منشورًا بديلًا
    // على منصة قابلة للتشغيل، ثم نشغّله عبر fetchEpisode مباشرة (لا loadLinks —
    // فلا تكرار). يعيد true إذا بُثّ رابط حيّ واحد على الأقل.
    private suspend fun playViaCrossPost(
        title: String,
        ep: Int,
        platform: String,
        currentPostId: String,
        collected: MutableList<ExtractorLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean {
        if (title.isBlank()) return false
        logD("OnShort.playViaCrossPost title='$title' ep=$ep platform='$platform' current=$currentPostId")
        return try {
            val results = searchRaw(title).filter { it.id != currentPostId }
            if (results.isEmpty()) {
                logD("OnShort.playViaCrossPost no alternate posts")
                return false
            }
            // نُفضِّل النشر المزدوج بنفس العنوان، ثم منصةً قابلة للتشغيل
            // (خارج القائمة المرفوضة)، بترتيب أفضل النتائج حسب العنوان.
            val normTarget = normalizeSearchTitle(title)
            val ranked = results
                .filter { (it.platform ?: "").lowercase() !in REJECTED_PLATFORMS }
                .sortedWith(
                    compareByDescending<SearchRaw> { normalizeSearchTitle(it.title) == normTarget }
                        .thenByDescending { (it.platform ?: "").lowercase() in setOf("dramabox", "reelshort", "dotdrama", "stardusttv", "moborels") }
                )
            var tried = 0
            for (cand in ranked) {
                if (tried >= 4) break
                tried++
                logD("OnShort.playViaCrossPost try cand id=${cand.id} platform=${cand.platform} title='${cand.title}'")
                val node = fetchEpisode(cand.id, ep, null)
                if (node != null && (node.get("ok")?.asBoolean() ?: true)) {
                    val main = node.get("url")?.asText()
                    if (main.isNullOrBlank()) continue
                    emitNode(node, "OnShort · عبر النشر المزدوج", collected, subtitleCallback)
                    logD("OnShort.playViaCrossPost SUCCESS via post ${cand.id} platform=${cand.platform}")
                    return true
                }
            }
            logD("OnShort.playViaCrossPost no playable alternate")
            false
        } catch (e: Exception) {
            logE("OnShort.playViaCrossPost exception ${e.message}")
            false
        }
    }

    // يطبّع نصًا للمقارنة (أحرف صغيرة + بلا علامات/مسافات) — لتطابق عناوين البحث.
    private fun normalizeSearchTitle(t: String): String =
        t.lowercase().replace(Regex("""[^a-z0-9_-؀-ۿ]"""), "")

    // ---------- التفاصيل (load) ----------
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val meta = parseMeta(url)
            if (meta != null) {
                // نبني الحلقات فورًا من البيانات المضمّنة، ونجلب التذكرة في الخلفية للتشغيل
                // (عبر bootstrap API السريع — أسرع من صفحة ?p= البطيئة)
                val cleanId = extractPostId(meta.id)
                bootstrapTicketViaApi(cleanId)
                return buildEpisodes(cleanId, meta.total, meta.title, meta.poster, stripMeta(url), meta.platform)
            }

            // احتياطي: رابط بدون بيانات (روابط قديمة/مشاركة) — نجلب صفحة التفاصيل
            val res = getWithRetry(stripMeta(url), mainUrl, headers()) ?: return null
            val rawId = detailPostRe.find(res)?.groupValues?.get(1) ?: return null
            val postId = extractPostId(rawId)
            val ticket = detailTicketRe.find(res)?.groupValues?.get(1)
            if (ticket != null) cached = PlayCache(postId, ticket)
            val total = Regex("""(?:total"|Episodes")\s*:\s*(\d{1,5})""")
                .find(res)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            // الاستخراج الاحتياطي لا يُمرّر platform (غير متاح من صفحة ?p= سهلًا) —
            // الجسر سيشتغل من البحث بالعنوان فقط عندها
            buildEpisodes(postId, total, extractTitle(res, stripMeta(url)), extractPoster(res), stripMeta(url), null)
        } catch (e: Exception) { null }
    }

    // data الحلقة = "postId|رقم|[platform]|[EncodedTitle]" — الصيغة القياسية لا تتلف في
    // التطبيق، والجزءان الأخيران (اختياريان) يغذّيان جسر Mosalsaly في loadLinks.
    private suspend fun buildEpisodes(
        postId: String,
        total: Int,
        title: String,
        poster: String?,
        url: String,
        platform: String? = null
    ): LoadResponse {
        val eps = mutableListOf<Episode>()
        val encTitle = URLEncoder.encode(title, "UTF-8")
        for (n in 1..maxOf(total, 1)) {
            val data = if (platform.isNullOrBlank()) "$postId|$n" else "$postId|$n|$platform|$encTitle"
            eps.add(newEpisode(data) {
                this.episode = n
                this.name = "الحلقة $n"
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            this.posterUrl = poster
        }
    }

    private fun extractTitle(res: String, url: String): String {
        return Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""")
            .find(res)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(res)?.groupValues?.get(1)
                ?.replace(Regex("""<[^>]+>"""), "")?.trim()
            ?: url.substringAfterLast('/').substringBefore('?').replace('-', ' ').trim()
            ?: "OnShort"
    }

    private fun extractPoster(res: String): String? {
        return Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""").find(res)?.groupValues?.get(1)
    }

    // يجلب التذكرة عبر bootstrap API السريع (بدون الحاجة لصفحة ?p= البطيئة).
    // يراسل مرةً بلا تذكرة فيرد الخادم "Player session expired" + تذكرة صالحة في الجسم.
    // هذه الطريقة أسرع بكثير من صفحة ?p= (~1-3 ثانية بدل 10+ ثانية) وتُعطي رحلة API
    // واحدة عند التشغيل الفعلي (بعد bootstrap) → تظهر الروابط بشكل أسرع.
    private fun bootstrapTicketViaApi(postId: String) {
        if (cached?.postId == postId) return
        val running = prefetchThread
        if (running != null && running.isAlive) return
        val deadline = System.currentTimeMillis() + 15000
        val th = Thread {
            var attempt = 0
            while (cached?.postId != postId && System.currentTimeMillis() < deadline) {
                attempt++
                try {
                    val ts = System.currentTimeMillis()
                    val u = "$ONS_PLAY_API?post=$postId&episode=1&_t=$ts"
                    val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", ONS_UA)
                    conn.setRequestProperty("Referer", mainUrl)
                    conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                    conn.setRequestProperty("Accept-Encoding", "identity")
                    conn.setRequestProperty("X-ONShort-Player", "1")
                    conn.connectTimeout = 6000
                    conn.readTimeout = 7000
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val bytes = stream?.readBytes() ?: ByteArray(0)
                    stream?.close()
                    conn.disconnect()
                    val raw = if (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b) {
                        try {
                            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
                                .bufferedReader(Charsets.UTF_8).use { it.readText() }
                        } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
                    } else {
                        String(bytes, Charsets.UTF_8)
                    }
                    val node = try { mapper.readTree(raw) } catch (_: Exception) { null }
                    val newTicket = node?.get("ticket")?.asText()?.takeIf { it.isNotBlank() }
                    if (newTicket != null) {
                        cached = PlayCache(postId, newTicket)
                    } else {
                        // bootstrap لم يثمر تذكرة — توقف (لا نحرق الوقت على صفحة بطيئة)
                        break
                    }
                } catch (_: Exception) {
                    // مهلة/فشل اتصال — أعد المحاولة بفاصل قصير
                }
                if (cached?.postId != postId && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(300L) } catch (_: InterruptedException) {}
                }
            }
        }
        prefetchThread = th
        th.start()
    }

    // ---------- تحليل الماستر (كل الجودات + الأصوات) ----------
    private data class HlsVariant(val height: Int, val uri: String)
    private data class HlsAudio(val group: String, val name: String, val uri: String)

    private suspend fun fetchMaster(url: String): String? = getWithRetry(url, mainUrl)

    // يقرأ الماستر ويستخرج: (قائمة الجودات، قائمة مجموعات الصوت).
    // الماستر بأسلوب AWS MediaConvert "independent audio":
    // كل جودة في نسخة فيديو منفصلة (بدون مسار صوت) + مجموعة #EXT-X-MEDIA:TYPE=AUDIO منفصلة.
    // إنْ لم توجد مجموعة صوت فالصوت مدمجٌ في كل نسخة (muxed) → عندها تُعرض كل جودة وحدها بأمان.
    private fun parseMaster(text: String, masterUrl: String): Pair<List<HlsVariant>, List<HlsAudio>> {
        val base = masterUrl.substringBeforeLast("/")
        val variants = mutableListOf<HlsVariant>()
        val audios = mutableListOf<HlsAudio>()
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") -> {
                    val g = Regex("""GROUP-ID="([^"]+)"""").find(line)?.groupValues?.get(1) ?: ""
                    val nm = Regex("""NAME="([^"]+)"""").find(line)?.groupValues?.get(1) ?: g
                    val u = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.get(1)
                    if (u != null) audios.add(HlsAudio(g, nm, if (u.startsWith("http")) u else "$base/$u"))
                }
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val h = Regex("""RESOLUTION=\d+x(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val next = lines.getOrNull(i + 1)?.trim()
                    if (next != null && !next.startsWith("#")) {
                        variants.add(HlsVariant(h ?: 0, if (next.startsWith("http")) next else "$base/$next"))
                    }
                }
            }
            i++
        }
        return variants.sortedByDescending { it.height } to audios
    }

    // يحوّل كود اللغة من "pl-PL"/"ar-SA" إلى الشكل القصير ("pl"/"ar") الذي يقبله التطبيق
    private fun normalizeLang(raw: String): String =
        raw.trim().lowercase().substringBefore('-').substringBefore('_').ifBlank { "ar" }

    // يحدد نوع الرابط الحقيقي: HLS (m3u8) ↔ MP4 مباشر. بعض خوادم OnShort تُرجع MP4 مباشرًا
    // (dramaboxdb/flextv.cc) وهذا كسر التشغيل عندما يُرسل كـ M3U8 — المشغل يفشل بـ "Source error".
    private fun linkType(uri: String): ExtractorLinkType {
        val clean = uri.substringBefore('?').substringBefore('#').lowercase()
        return if (clean.endsWith(".m3u8") || clean.contains("/m3u8") || clean.contains("playlist")) {
            ExtractorLinkType.M3U8
        } else {
            // ملف مباشر (mp4/ts/...): يشغّله ExoPlayer مباشرةً دون فك HLS
            ExtractorLinkType.VIDEO
        }
    }

    /**
     * يبثّ روابط عقدة تشغيل OnShort (main + candidates + subtitles) إلى القائمة
     * المجمّعة [collected]. يُستخدم للمسار الأصلي (native) ولجسر إعادة البحث —
     * نفس المنطق تمامًا بلا تكرار. لا يبثّ [emitSorted] هنا؛ المتصل يفعلها مرة
     * واحدة عند النجاح (فالإعدادات لا تُغيّر شيئًا قبل النجاح). [labelSource]
     * يوسم الرابط («OnShort» أو «عبر النشر المزدوج») لتمييز مصدره.
     */
    private suspend fun emitNode(
        node: JsonNode,
        labelSource: String,
        collected: MutableList<ExtractorLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        // الصوت (مهم): HLS الخاص بـ OnShort يستخدم صوتًا مستقلاً (independent audio) —
        // الفيديو في نسخ منفصلة والصوت في مجموعة #EXT-X-MEDIA:TYPE=AUDIO منفصلة.
        // لذلك نُسلّم master URL كاملًا للمشغّل فيُحلّ مجموعةَ الصوت تلقائيًا (صوتٌ مُختار
        // افتراضيًا + قابل للتبديل + جودة متكيّفة). لا نُخرج candidates[] إطلاقًا:
        // فبعضها (مثل microdrama / reeltv) نسخُ فيديو منفصلة (MP4 بدون مسار صوت) —
        // تشغيلُها يؤدي إلى صمتٍ تام (لا يوجد صوت). master فقط هو المضمون السليم صوتيًا.
        val main = node.get("url")?.asText()
        if (!main.isNullOrBlank()) {
            // candidates[] يحمل كل جودة برابطها المستقل. kind يكون "media" (MP4 مباشر)
            // أو "hls" (playlist HLS) — كلا النوعين روابط فيديو صالحة يجب عرضها.
            // (كان فلتر kind=="media" فقط يحذف جودات iDrama/ReelShort/Stardust ذات kind=hls
            //  فيُظهر مزوّد "Auto" واحد بدل كل الجودات المتوفرة — أصلحته.)
            val candidates = mutableListOf<HlsVariant>()
            val candArr = node.get("candidates")
            if (candArr != null && candArr.isArray) {
                for (c in candArr) {
                    val cu = c.get("url")?.asText() ?: continue
                    if (cu.isBlank()) continue
                    val h = Regex("""\d+""").find(c.get("quality")?.asText() ?: "")?.value?.toIntOrNull() ?: 0
                    candidates.add(HlsVariant(h, cu))
                }
            }
            val mainLen = main.length
            val candLens = candidates.map { it.uri.length }.joinToString(",")
            val candHasAuth = candidates.any { it.uri.contains("auth_key") }
            logD("OnShort.emitNode($labelSource) mainLen=$mainLen candLens=[$candLens] mainAuth=${main.contains("auth_key")} candAuth=$candHasAuth")

            // الاستراتيجية السريعة والآمنة (بلا أي سحب شبكة في مسار التشغيل):
            // ExoPlayer/CloudStream يحلّل master HLS بنفسه تلقائيًا ويعطي كل الجودات
            // والأصوات فورًا (يختار الجودة تلقائيًا). لذلك لا نحتاج جلب master مسبقًا
            // (كان هذا الجلب هو سبب تأخير عرض الفيديو والجودات).
            var mainPlay = main
            if (candidates.isNotEmpty()) {
                val mainIsHls = linkType(main) == ExtractorLinkType.M3U8
                val mainInCandidates = candidates.any { it.uri == main }
                // إصلاح الخوادم التي ترسل main ناقصًا (MP4 بلا auth_key) بينما المرشحات
                // تحمل توقيعًا: نختار أفضل مرشح موقّع كأساس التشغيل (flextv/dramaboxdb).
                val mainHasAuth = main.contains("auth_key")
                val best = if (!mainIsHls && !mainHasAuth) {
                    candidates.filter { it.uri.contains("auth_key") }.maxByOrNull { it.height }
                        ?: candidates.firstOrNull()
                } else null
                if (best != null) {
                    logD("OnShort.emitNode REPLACE main(bare) -> cand h=${best.height} (${main.take(55)}... => ${best.uri.take(55)}...)")
                    mainPlay = best.uri
                }
                val effIsHls = linkType(mainPlay) == ExtractorLinkType.M3U8
                val effInCands = candidates.any { it.uri == mainPlay }
                logD("OnShort.emitNode candidates=${candidates.size} mainHls=$effIsHls inCands=$effInCands mainWasHls=$mainIsHls")

                // أساس: main (المُحسَّن) — نرسله دائمًا للمشغّل (HLS أو MP4).
                // كان الشرط القديم (effIsHls || !effInCands) يمنع MP4 موجودة في candidates
                // من الوصول للمشغّل → فيديو FlexTV/DramaBox لا يشتغل.
                // (الافتراضي true = يُبثّ كما كان تماماً؛ إطفاؤه في الإعدادات يخفيه فقط.)
                if (prefs?.getBoolean(OnShortSettingsBottomSheet.KEY_SHOW_AUTO, true) != false) {
                    collected.add(newExtractorLink(name, "Auto · $labelSource", mainPlay, effIsHls.let { if (it) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO }) {
                        this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                    })
                }

                // مرشحات الجودة الصريحة (مختلفة عن main، بلا تكرار مسار).
                // ترتيب العرض: الروابط الأعلى موثوقية ("nav"/"nav2" — الأساس الصالح) أولًا
                // تنازليًا حسب الدقة، ثم روابط "narrow" (الأقل استقرارًا على بعض الخوادم مثل
                // dramaboxdb) في النهاية. نُبقي كل الروابط — لا حذف — لكن نُبرز الصالح أولًا.
                val ranked = candidates.sortedWith(
                    Comparator { a, b ->
                        val ca = if (a.uri.contains("narrow")) 1 else 0
                        val cb = if (b.uri.contains("narrow")) 1 else 0
                        if (ca != cb) ca - cb
                        else b.height - a.height
                    }
                )
                val seen = mutableSetOf<String>()
                // عتبة «أقل جودة معروضة» — "all" (الافتراضي) = بلا ترشيح إطلاقاً،
                // فيمرّ كل مرشّح كما كان. رابط Auto ليس مرشّحاً ولا يشمله هذا الترشيح.
                val minHeight = when (prefs?.getString(OnShortSettingsBottomSheet.KEY_MIN_HEIGHT, "all")) {
                    "480" -> 480
                    "360" -> 360
                    else -> 0
                }
                for (v in ranked) {
                    // تجاهل الرابط الأساسي (لا نكرّره)
                    if (v.uri == mainPlay) continue
                    // لا نعرض "Auto" مكررًا بلا معنى من candidate مجهول الجودة (height=0)
                    // يطابق أساسي الوظيفة — نحتفظ فقط بالجودات الصريحة (height>0).
                    if (v.height <= 0) continue
                    if (minHeight > 0 && v.height < minHeight) continue
                    if (!seen.add(v.uri)) continue
                    val label = "${v.height}p"
                    collected.add(newExtractorLink(name, "$label · $labelSource", v.uri, linkType(v.uri)) {
                        this.quality = getQualityFromName("${v.height}p")
                        this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                    })
                }
            } else {
                // لا يوجد candidates: main وحده. دلّ نوعه (m3u8 = HLS تكيفي كل الجودات،
                // mp4 = مباشر). بلا جلب — يفتح فورًا.
                if (prefs?.getBoolean(OnShortSettingsBottomSheet.KEY_SHOW_AUTO, true) != false) {
                    collected.add(newExtractorLink(name, "Auto · $labelSource", mainPlay, linkType(mainPlay)) {
                        this.headers = mapOf("User-Agent" to ONS_UA, "Referer" to mainUrl)
                    })
                }
            }
        }

        val subs = node.get("subtitles")
        if (subs != null && subs.isArray) {
            val seen = mutableSetOf<String>()
            for (s in subs) {
                val su = s.get("url")?.asText() ?: continue
                if (su.isBlank()) continue
                if (!seen.add(su)) continue
                val langRaw = s.get("lang")?.asText() ?: s.get("label")?.asText() ?: "ar"
                val lang = normalizeLang(langRaw)
                try { subtitleCallback(newSubtitleFile(lang, su)) } catch (_: Exception) {}
            }
        }
    }

    /**
     * بثّ روابط البث المجمّعة دفعة واحدة: «افتراضي» = نفس الترتيب تماماً كما كان،
     * و«تصاعدي/تنازلي» يعيدان الترتيب فقط (فرز مستقر: الروابط متساوية الجودة
     * تحتفظ بترتيبها النسبي، ولا إضافة ولا حذف ولا تكرار).
     */
    private fun emitSorted(prefs: SharedPreferences?, collected: List<ExtractorLink>, callback: (ExtractorLink) -> Unit) {
        val order = prefs?.getString(OnShortSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
    }

    // ---------- روابط التشغيل (loadLinks) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // نجمع روابط البث أولاً ثم نبثّها دفعة واحدة (بترتيب اختيار المستخدم)
            // قبل كل return ناجح — فالإعدادات لا تُغيّر شيئاً افتراضياً.
            val collected = mutableListOf<ExtractorLink>()
            logD("OnShort.loadLinks data='$data'")
            val parts = data.split("|")
            if (parts.size < 2) { logD("OnShort.loadLinks bad data size=${parts.size}"); return false }
            // postId قد يصل رابطًا كاملًا (…/186910) من روابط قديمة/مشاركة — نطبّعه إلى الرقم
            val postId = extractPostId(parts[0])
            val ep = parts[1].toIntOrNull() ?: return false
            // الجزءان الاختياريان (يأتيان من buildEpisodes على template الحديث):
            // |platform|EncodedTitle — يغذّيان جسر Mosalsaly عندما يرفض OnShort المنصة.
            val platform = if (parts.size > 2) URLDecoder.decode(parts[2], "UTF-8") else ""
            val title = if (parts.size > 3 && parts[3].isNotBlank()) URLDecoder.decode(parts[3], "UTF-8") else ""
            logD("OnShort.loadLinks platform='$platform' title='$title'")

            // التذكرة: إن كانت محفوظة نستخدمها، وإلا نبدأ بدون تذكرة —
            // fetchEpisode يبني التذكرة بنفسه من استجابة التشغيل (بدون صفحة ?p= البطيئة)
            // حتى لا تتجاوز مهلة loadLinks القصيرة في التطبيق → "لايوجد روابط".
            val cachedTicket = cached?.takeIf { it.postId == postId }?.ticket
            logD("OnShort.loadLinks post=$postId ep=$ep cachedTicket=${cachedTicket?.isNotBlank() == true}")

            // تسريع أول تشغيل: إن كان الجلب الخلفي للـ prefetch (المُطلق أثناء load()) لا يزال
            // في الخلفية وحُفظت التذكرة للتو، نستخدمها بدلًا من دفع bootstrap كامل داخل
            // fetchEpisode (رحلتان: "Player session expired" ثم نجاح). انتظار قصير جدًا (حتى
            // 1.2ث) يستفيد من الرحلة الجارية بدل تكرارها، وذا لم تكتمل نكمل bootstrap كالمعتاد.
            run {
                val running = prefetchThread
                val deadline = System.currentTimeMillis() + 1200
                while (running != null && running.isAlive && System.currentTimeMillis() < deadline) {
                    cached?.let { if (it.postId == postId) { emitSorted(prefs, collected, callback); return@run } }
                    try { Thread.sleep(120) } catch (_: InterruptedException) { break }
                }
            }
            val cachedTicket2 = cached?.takeIf { it.postId == postId }?.ticket
            logD("OnShort.loadLinks after prefetch-wait cached=${cachedTicket2?.isNotBlank() == true}")
            val effectiveTicket = cachedTicket2 ?: cachedTicket

            val node = fetchEpisode(postId, ep, effectiveTicket)

            // سلسلة الجسور لكل مصدر: حين يرفض سيرفر OnShort تشغيل المنشور الحالي
            // (أو يفشل مؤقتًا) نجرّب جسرَي تشغيل بالترتيب — جسر إعادة البحث ثم جسر
            // Mosalsaly — وأول مصدر يُصدر رابطًا حيًا يربح. العمل نفسه قد يكون منشورًا
            // على منصة أخرى قابلة للتشغيل عند OnShort (cross-post) أو على mosalsaly.com
            // (بحث بالعنوان)؛ لا نقيّد على المنصة — بحث العنوان يحدد النجاح تلقائيًا.
            if (node == null || !(node.get("ok")?.asBoolean() ?: true)) {
                val reason = if (node == null) "null" else rejectReason(node)
                val bridgeEnabled = prefs?.getBoolean(OnShortSettingsBottomSheet.KEY_BRIDGE_ENABLED, true) ?: true
                logD("OnShort.loadLinks OnShort failed (reason='$reason') bridge=$bridgeEnabled titleLen=${title.length}")
                if (bridgeEnabled && title.isNotBlank()) {
                    // 1) جسر إعادة البحث: منشور بديل على منصة قابلة للتشغيل
                    val cross = playViaCrossPost(title, ep, platform, postId, collected, subtitleCallback)
                    if (cross) {
                        emitSorted(prefs, collected, callback)
                        return true
                    }
                    logD("OnShort.loadLinks cross-post bridge did not produce links")
                    // 2) جسر Mosalsaly: بحث العنوان في mosalsaly.com
                    val bridge = OnShortMosalsalyBridge(prefs)
                    val bridged = bridge.playViaMosalsaly(data, title, platform, subtitleCallback) { link -> collected.add(link) }
                    if (bridged) {
                        emitSorted(prefs, collected, callback)
                        return true
                    }
                    logD("OnShort.loadLinks Mosalsaly bridge did not produce links")
                }
                // لا جسر (معطّل) أو لم يجد نصًا — أبلغ بالسبب ونُرجع false
                if (node == null) logD("OnShort.loadLinks fetchEpisode null -> false")
                else logD("OnShort.loadLinks ok=false -> false (${reason})")
                return false
            }

            // بثّ روابط التشغيل من العقدة الناجحة (main + candidates + ترجمات).
            // المنطق مُشارك مع جسر إعادة البحث (emitNode) — هنا بوسم «OnShort».
            val main = node.get("url")?.asText()
            if (!main.isNullOrBlank()) emitNode(node, "OnShort", collected, subtitleCallback)
            // ★ بثّ الروابط المجمّعة قبل كل return ناجح — «افتراضي» = نفس الترتيب
            // تماماً كما كان، و«تصاعدي/تنازلي» يعيدان الترتيب فقط (بلا حذف/تكرار).
            emitSorted(prefs, collected, callback)
            true
        } catch (e: Exception) { false }
    }

    // يجلب التذكرة من صفحة التفاصيل عبر ?p={postId}.
    // لو كان الجلب الخلفي قيد التنفيذ ننتظره حتى ثوانٍ (بدلًا من جلب صفحة بطيئة ثانية
    // قد تتجاوز مهلة loadLinks القصيرة في التطبيق → "لايوجد روابط" عند الضغط السريع).
    private fun fetchTicket(postId: String): String? {
        val waitUntil = System.currentTimeMillis() + 6000
        val running = prefetchThread
        if (running != null && running.isAlive) {
            while (running.isAlive && System.currentTimeMillis() < waitUntil) {
                cached?.let { if (it.postId == postId) return it.ticket }
                try { Thread.sleep(150L) } catch (_: InterruptedException) { break }
            }
            cached?.let { if (it.postId == postId) return it.ticket }
        }
        // الجلب الخلفي لم يُثمر — جلبه المباشر بمانع اتصال أصلي يحتمل 502/504 ويهضم الصفحة
        var attempt = 0
        while (attempt < 3) {
            try {
                val conn = java.net.URL(detailUrl(postId)).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", ONS_UA)
                conn.setRequestProperty("Referer", mainUrl)
                conn.connectTimeout = 12000
                conn.readTimeout = 15000
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val page = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                stream?.close()
                conn.disconnect()
                val ticket = detailTicketRe.find(page)?.groupValues?.get(1)
                if (ticket != null) {
                    cached = PlayCache(postId, ticket)
                    return ticket
                }
            } catch (_: Exception) {
                // 502/504/مهلة — أعد المحاولة
            }
            attempt++
            if (attempt < 3) try { Thread.sleep(700L * attempt) } catch (_: InterruptedException) {}
        }
        return null
    }

    // يقرأ جسم استجابة من API التشغيل ويعيده كـ JsonNode مهما كان كود HTTP (403 يحمل تذكرة جديدة).
    // نفضّل مسار CloudStream اللاتزامني app.get — نفس الطريقة التي يعتمدها مزوّدا ReelShort/DeepDrama
    // العاملان — لأنه لا يحجب نافذة loadLinks القصيرة. وإن فشل (يرمي عند 403 أو لا يعطي جسمًا قابلًا
    // للتحليل) نرجع إلى HttpURLConnection خام يقرأ الجسم من inputStream أو errorStream ويفكّ gzip يدويًا
    // (خادم OnShort يضغط جسم 403؛ identity وحدها تمنع الضغط لكن نحتفظ بالمسارين احتياطيًا).
    private suspend fun rawGetJson(url: String, referer: String, extraHeaders: Map<String, String>): JsonNode? {
        val hdrs = HashMap<String, String>()
        hdrs["User-Agent"] = ONS_UA
        hdrs["Accept"] = "application/json, text/plain, */*"
        hdrs["Accept-Encoding"] = "identity"
        hdrs["Cache-Control"] = "no-cache, no-store"
        hdrs["Pragma"] = "no-cache"
        for ((k, v) in extraHeaders) hdrs[k] = v

        // المسار 1 — CloudStream app.get (اللزامني). السريع ولا يحجب خيط loadLinks.
        try {
            val text = app.get(url, headers = hdrs, referer = referer).text
            if (!text.isNullOrBlank()) {
                val n = try { mapper.readTree(text) } catch (e: Exception) {
                    logE("OnShort.rawGetJson(app) parse failed: ${e.message} | body=${text.take(80)}")
                    null
                }
                if (n != null) {
                    logD("OnShort.rawGetJson(app) ok, has_ticket=${n["ticket"]?.isTextual == true}")
                    return n
                }
            } else {
                logD("OnShort.rawGetJson(app) empty body")
            }
        } catch (e: Exception) {
            logE("OnShort.rawGetJson(app) threw: ${e.message}")
        }

        // المسار 2 — احتياطي خام: يقرأ errorStream على 403/5xx ويفكّ gzip يدويًا.
        // مهلات قصيرة + محاولتان فقط — حتى لا يَعلّق loadLinks ثوانٍ طويلة على بطء/تقييد
        // OnShort (رصدنا تعلّقًا ~12ث بسبب readTimeout 20ث و3 محاولات مع backoff طويل).
        var attempt = 0
        while (attempt < 2) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                for ((k, v) in hdrs) conn.setRequestProperty(k, v)
                conn.connectTimeout = 7000
                conn.readTimeout = 8000
                val code = conn.responseCode
                logD("OnShort.rawGetJson http=$code (attempt=$attempt)")
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.readBytes() ?: ByteArray(0)
                stream?.close()
                conn.disconnect()
                val raw = if (bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0x1f && (bytes[1].toInt() and 0xff) == 0x8b) {
                    try {
                        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
                            .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } catch (_: Exception) { String(bytes, Charsets.UTF_8) }
                } else {
                    String(bytes, Charsets.UTF_8)
                }
                val node = try { mapper.readTree(raw) } catch (e: Exception) {
                    logE("OnShort.rawGetJson(raw) parse failed: ${e.message} | body=${raw.take(80)}")
                    null
                }
                if (node != null) { logD("OnShort.rawGetJson ok, has_ticket=${node["ticket"]?.isTextual == true}"); return node }
            } catch (e: Exception) {
                logE("OnShort.rawGetJson exception (attempt=$attempt): ${e.message}")
            }
            attempt++
            if (attempt < 2) try { Thread.sleep(250L * attempt) } catch (_: InterruptedException) {}
        }
        logE("OnShort.rawGetJson returning null after retries")
        return null
    }

    private suspend fun fetchEpisode(
        postId: String,
        ep: Int,
        ticket: String?
    ): JsonNode? {
        var current = ticket
        var timeoutFails = 0 // عدد فشل rawGetJson المتتالي (timeout/parse) — تجاوزه مباشرة لـ fallback
        for (attempt in 0..3) {
            val ts = System.currentTimeMillis()
            val u = "$ONS_PLAY_API?post=$postId&episode=$ep&_t=$ts"
            // إن لم تكن لدينا تذكرة نرسل بدونها: الخادم يرد 403 مع تذكرة صالحة في الجسم
            // نبدأ منها (bootstrap سريع — لا حاجة لصفحة ?p= البطيئة التي كانت تسبب المهلة).
            val hdrs = mutableMapOf("X-ONShort-Player" to "1") // مطلوب بشدة
            if (!current.isNullOrBlank()) hdrs["X-ONShort-Ticket"] = current
            val node = rawGetJson(u, mainUrl, hdrs) ?: run {
                // rawGetJson فشل (timeout/parse) — لا نتوقف فورًا: أعد المحاولة بنفس التذكرة.
                // هذا يمنع توقف loadLinks على idrama/moborels عندما يفشل الاتصال مؤقتاً.
                timeoutFails++
                logD("OnShort.fetchEpisode null attempt=$attempt timeouts=$timeoutFails (continuing)")
                // إذا فشل rawGetJson مرتين متتاليتين = اتصال معطّل أو محظور → لا جدوى من المحاولة ال3+4
                if (timeoutFails >= 2) {
                    logD("OnShort.fetchEpisode too many timeouts, stopping")
                    break
                }
                if (current.isNullOrBlank()) {
                    // لا تذكرة: حاول جلبها من صفحة التفاصيل (fallback بطيء لكن ينقذ الموقف)
                    val fetched = fetchTicket(postId)
                    if (fetched != null) current = fetched
                }
                continue
            }
            timeoutFails = 0 // نجاح: صفّر عداد الـ timeout
            val newTicket = node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }
            if (newTicket != null) current = newTicket
            val ok = node.get("ok")?.asBoolean(false) ?: true
            val msg = node.get("message")?.asText() ?: node.get("error")?.asText() ?: ""
            // تمييز حاسم: الخادم يصدر تذكرة جديدة في كل استجابة (حتى المرفوضة نهائيًا).
            // رفضُه النهائي يُعرَّف بمحتوى الرسالة (REST bridge / not handled) وليس بـ retryable:
            //  - "Player session expired" = أول خطوة bootstrap (تصدر تذكرة جديدة) — يجب متابعتها،
            //    مهما كان retryable=false، وإلا لن يشتغل أي مصدر (أثبتنا أن المحاولة الثانية تنجح).
            //  - "REST bridge"/"not handled" = المزود مرفوض من موقع وسيط OnShort نفسه.
            //  - "Media refresh failed: HTTP 5xx (401/500/504…)" = مصدر المنصة ينكسر/لا يجيب —
            //    رفض نهائي حقيقي، أوقف المحاولة فورًا بدل إعادة المحاولة البطيئة عدة مرات.
            //    (FreeReels مثلًا يرد 500 Internal Server Error — لا جدوى من تكرار 4 مرات.)
            //  - "Media refresh failed: FlexTV ALL-EPISODES failed" (فاختبار حي 2026-09: HTTP 424)
            //    = مصدر FlexTV الأصلي ينكسر لكن simulator يجدب "ALL-EPISODES" ويصدم 424 — لا بدّ
            //    جودات ولا روابط وسيطة. معاملةٌ نهائية: أوقف فورًا بدل 4 محاولات + fallback بطيء.
            val notHandled = msg.contains("REST bridge") || msg.contains("not handled")
            val flextvDead = msg.contains("flextv") && msg.contains("ALL-EPISODES") && msg.contains("failed")
            val refreshFail = msg.contains("refresh failed") && (Regex("""HTTP\s+[45]\d\d""").containsMatchIn(msg) || flextvDead)
            // رفضٌ نهائي حقيقي = المزود مرفوض من الوسيط (REST bridge/not handled) أو مصدره
            // معطّل نهائيًا (Media refresh failed + HTTP 5xx/424/FlexTV). هذه لا جدوى من إعادتها.
            val permanentReject = notHandled || refreshFail
            // "MoboRels media refresh is already running" = الوسيط يقوم حاليًا بتحديث وسائط
            // MoBoRels؛ هذه حالة مؤقتة — إعادة المحاولة بعد لحظة قد تنجح (المزود يعمل على
            // الموقع فعلًا). لذا لا نعاملها كرفض نهائي، بل نستمر في المحاولة حتى الـ limit.
            val hasFresh = newTicket != null
            val canRetry = (attempt < 3) && !permanentReject
            val fullUrl = node.get("url")?.asText().orEmpty()
            logD("OnShort.fetchEpisode attempt=$attempt ok=$ok newTicket=$hasFresh permReject=$permanentReject msg=${msg.take(50)} urlLen=${fullUrl.length} url=${fullUrl.take(60)}")
            if (!ok) {
                if (!canRetry) {
                    if (msg.isNotBlank()) logE("OnShort.fetchEpisode final reject: $msg")
                    return null
                }
                // تأخير قصير إضافي قبل إعادة المحاولة — إتاحة للوسيط إنهاء "refresh already running"
                // (خاصة MoBoRels) قبل الطلب التالي.
                try { Thread.sleep(600L * (attempt + 1)) } catch (_: InterruptedException) {}
                continue
            }
            // نجاح: احفظ التذكرة الجديدة لبقية الحلقات (بلا جلب صفحة)
            if (newTicket != null) cached = PlayCache(postId, newTicket)
            return node
        }

        // === Fallback: حاول جلب تذكرة من صفحة التفاصيل وإعادة المحاولة最后一次 ===
        // ينقذ idrama/moborels عندما تفشل جميع محاولات API بسبب بطء/timeout مؤقت.
        val fallbackTicket = fetchTicket(postId)
        if (!fallbackTicket.isNullOrBlank()) {
            val ts = System.currentTimeMillis()
            val u = "$ONS_PLAY_API?post=$postId&episode=$ep&_t=$ts"
            val hdrs = mutableMapOf("X-ONShort-Player" to "1", "X-ONShort-Ticket" to fallbackTicket)
            val node = rawGetJson(u, mainUrl, hdrs)
            if (node != null) {
                val ok = node.get("ok")?.asBoolean(false) ?: true
                if (ok) {
                    val newTicket = node.get("ticket")?.asText()?.takeIf { it.isNotBlank() }
                    if (newTicket != null) cached = PlayCache(postId, newTicket)
                    logD("OnShort.fetchEpisode fallback SUCCESS")
                    return node
                }
            }
        }
        logD("OnShort.fetchEpisode all attempts failed for post=$postId ep=$ep")
        return null
    }

    // يعيد رسالة مفهومة للمستخدم بناءً على رسالة الخادوم/الوضع (تُستخدم عند عدم إيجاد روابط)
    private fun rejectReason(node: JsonNode?): String {
        val msg = node?.get("message")?.asText() ?: return ""
        return when {
            msg.contains("REST bridge") || msg.contains("not handled") ->
                "هذه المنصة مرفوضة من خادم OnShort نفسه — يعتمد التشغيل على جسر Mosalsaly (بحث تلقائي بالعنوان)."
            msg.contains("session expired") -> "انتهت جلسة التذكرة — حاول مرة أخرى"
            msg.contains("flextv") && msg.contains("ALL-EPISODES") && msg.contains("failed") ->
                "مصدر FlexTV معطّل من جهة OnShort حاليًا (فشل جلب الحلقات كاملة) — جرّب منصة أخرى."
            msg.contains("refresh failed") && Regex("""HTTP\s+5\d\d""").containsMatchIn(msg) ->
                "مصدر هذه المنصة معطّل من جهة OnShort حاليًا (خطأ في السيرفر 5xx) — جرّب منصة أخرى أو لاحقًا."
            msg.contains("refresh failed") -> "مصدر المنصة لا يستجيب حاليًا (خطأ على المصدر الأصلي)"
            else -> msg
        }
    }
}
