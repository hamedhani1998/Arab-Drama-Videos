package com.maraya.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONTokener

/**
 * مصدر «مرايا» (maraya.sba.net.ae) — قناة إماراتية تُبثّ مسلسلات وأفلاماً
 * عربية وخليجية. التطبيق واجهة Nuxt 3 SPA ونظام الوسائط على مضيف منفصل
 * `maraya.faulio.com` (بنية مؤكدّة ميدانياً).
 *
 * البنية:
 *  - الصفحة الرئيسية: GET {API}/home → `blocks[]{block_type, title, projects[]}`
 *    عناصر `vodProgramObject` (id, type=movie|series|program|audio, title, url,
 *    description, lastvideos, covers، access).
 *  - البحث: GET {API}/search?q= → `list[]` عناصر `{item_type, item_id, title,
 *    image, item:{...}}`. `programs` → برنامج/مسلسل؛ `videos` → مشهد من برنامج
 *    (owner_id = برنامجه الأم).
 *  - التفاصيل: GET {API}/project/{id} → `blocks[0].project` (title, type,
 *    description, covers، lastvideos.regular = id آخر حلقة).
 *  - الحلقات: GET {API}/video?program={id}&ipp=100 → `blocks[0].projects[]`
 *    عناصر `vodVideoObject` (id, program_id, season_id, season_number, episode,
 *    title, type=regular|trailer|promo، access) — نُبقي `type=regular` فقط.
 *  - البث: GET {API}/video/{id}/player → `settings` فيه `protocols.hls/.dash`
 *    ورابط MP4 مباشر لكل جودة في `resolutions[]` (key, url) و`vtts[]` ترجمة.
 *    (guest يبث، member→401 — قيد وصول خاص بالموقع)
 */
class MarayaProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    companion object {
        private const val TAG = "Maraya"
        private const val API = "https://maraya.faulio.com/api/v1"
        private const val MEDIA = "https://maraya.faulio.com"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://maraya.sba.net.ae"
    override var name = "Maraya"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    private fun jstr(o: JSONObject?, key: String): String? =
        o?.optString(key)?.takeIf { it.isNotBlank() }

    /**
     * ★ يجلب نص JSON الخام ويبنيه `org.json.JSONObject` يدوياً — وليس
     * `app.get(...).parsed<JSONObject>()`.
     *
     * لماذا؟ تطبيق CloudStream يحلّ `.parsed<T>` عبر محوّل Jackson
     * (`jsonResponseParser`) مخصّص لأنواع `@Serializable` kotlinx — ولا يحتوي
     * مساراً لـ `org.json.JSONObject` (دليل: `MainActivityKt` بلا أي إشارة لـ
     * org.json). النتيجة وقت التشغيل: كائن فارغ/null تُظهر «لا شيء» رغم نجاح
     * API. المصادر العاملة في هذا المستودع (aryarabia/krmzi/lodynet) تبني JSON
     * بذاتها من `.text` — نفعل نفس الشيء هنا.
     */
    private suspend fun fetchJson(url: String): JSONObject {
        val text = app.get(
            url,
            headers = mapOf("User-Agent" to UA, "Accept" to "application/json")
        ).text.trim().removePrefix("﻿")
        // بعض الردود تُبقي ثغرة (مسافة/سطر) قبل { أو ثغرة في النهاية؛
        // JSONTokener يتساهل مع المسافة البيضاء المحيطة.
        return JSONObject(JSONTokener(text))
    }

    private fun posterOf(item: JSONObject): String? {
        jstr(item, "image")?.let { return it }
        val covers = item.optJSONObject("covers")
        if (covers != null) {
            for (key in arrayOf("portrait", "square", "big", "orig", "fullhd")) {
                val v = covers.opt(key) ?: continue
                when (v) {
                    is String -> if (v.isNotBlank()) return v
                    is JSONObject -> {
                        jstr(v, "orig")?.let { return it }
                        jstr(v, "fullhd")?.let { return it }
                    }
                }
            }
        }
        item.optJSONObject("allimages")?.optString("big").takeIf { !it.isNullOrBlank() }?.let { return it }
        return null
    }

    private fun abs(s: String?): String? {
        if (s == null) return null
        return if (s.startsWith("http")) s else "$MEDIA$s"
    }

    private fun parseType(t: String): String =
        when (t) {
            "series", "series_episode", "program", "program_episode" -> "series"
            "movie", "movie_episode" -> "movie"
            else -> t
        }

    private fun projectCard(p: JSONObject): SearchResponse? {
        val id = jstr(p, "id") ?: return null
        val title = jstr(p, "title") ?: return null
        val type = parseType(p.optString("type", "movie"))
        if (type != "movie" && type != "series") return null
        val url = "$mainUrl/title/$id"
        val poster = abs(posterOf(p))
        return if (type == "series")
            newTvSeriesSearchResponse(title, url) { this.posterUrl = poster }
        else
            newMovieSearchResponse(title, url) { this.posterUrl = poster }
    }

    // ---------- الصفحة الرئيسية ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = ArrayList<HomePageList>()
        try {
            val doc = fetchJson("$API/home")
            val blocks = doc.optJSONArray("blocks") ?: JSONArray()
            for (i in 0 until blocks.length()) {
                // كل بلوك يُبنى بنفسه في try — عطل بلوك واحد (شبكة/بنية شاذة)
                // لا يُسقط الصفحة كلها: بقية الصفوف تظهر والصف المعطوب يُتخطّى.
                try {
                    val b = blocks.optJSONObject(i) ?: continue
                    if (b.optString("block_type") == "horizontal_channel_list") continue
                    val title = jstr(b, "title") ?: continue
                    val projects = b.optJSONArray("projects") ?: continue
                    val cards = ArrayList<SearchResponse>()
                    for (j in 0 until projects.length()) {
                        val p = projects.optJSONObject(j) ?: continue
                        projectCard(p)?.let { cards.add(it) }
                    }
                    if (cards.isNotEmpty()) rows.add(HomePageList(title, cards))
                } catch (e: Exception) {
                    Log.e(TAG, "getMainPage block[$i]: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // فشل الطلب نفسه فقط هو الذي يُفرغ الصفحة (لا مشكلة في البيانات).
            Log.e(TAG, "getMainPage: ${e.message}")
        }
        return newHomePageResponse(rows)
    }

    // ---------- البحث ----------
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val doc = fetchJson("$API/search?q=$q")
            val list = doc.optJSONArray("list") ?: return emptyList()
            val out = ArrayList<SearchResponse>()
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                val title = jstr(it, "title") ?: continue
                val itemType = it.optString("item_type", "videos")
                val poster = abs(it.optString("image").takeIf { it.isNotBlank() })
                val item = it.optJSONObject("item")
                if (itemType == "programs") {
                    val id = jstr(it, "item_id") ?: continue
                    out.add(newTvSeriesSearchResponse(title, "$mainUrl/title/$id") { this.posterUrl = poster })
                } else if (itemType == "videos") {
                    // مشهد من برنامج — اربطه ببرنامجه الأم:
                    val ownerId = jstr(item, "owner_id")
                    val route = if (ownerId != null) "$mainUrl/title/$ownerId" else "$mainUrl/video/${it.optString("item_id")}"
                    out.add(newMovieSearchResponse(title, route) { this.posterUrl = poster })
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
            emptyList()
        }
    }

    // ---------- التفاصيل ----------
    override suspend fun load(url: String): LoadResponse? {
        return try {
            val id = Regex("""/(?:title|project|video)/(\d+)""").find(url)?.groupValues?.get(1)
                ?: Regex("""(\d+)""").find(url.substringAfterLast('/'))?.groupValues?.get(1)
                ?: return null
            val doc = fetchJson("$API/project/$id")
            val blocks = doc.optJSONArray("blocks") ?: return null
            var project: JSONObject? = null
            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                if (b.opt("project") is JSONObject) { project = b.optJSONObject("project"); break }
            }
            val p = project ?: return null
            val title = jstr(p, "title") ?: return null
            val type = parseType(p.optString("type", "movie"))
            val poster = abs(posterOf(p))
            val description = jstr(p, "description")

            // الحلقات
            val epsDoc = try {
                fetchJson("$API/video?program=$id&ipp=100")
            } catch (e: Exception) { null }

            // جميع الحلقات (regular) من كل البلوكات — تُستخدم للمسلسلات أيضاً
            // ولإيجاد مقطع الفيلم الحقيقي عند غياب lastvideos.regular.
            val regularVideos = ArrayList<JSONObject>()
            if (epsDoc != null) {
                val blocksArr = epsDoc.optJSONArray("blocks") ?: JSONArray()
                for (i in 0 until blocksArr.length()) {
                    val b = blocksArr.optJSONObject(i) ?: continue
                    val projs = b.optJSONArray("projects") ?: continue
                    for (j in 0 until projs.length()) {
                        val v = projs.optJSONObject(j) ?: continue
                        if (v.optString("type", "regular") != "regular") continue
                        if (jstr(v, "id") != null) regularVideos.add(v)
                    }
                }
            }

            val episodes = ArrayList<Episode>()
            if (type == "series") {
                for (v in regularVideos) {
                    val vId = jstr(v, "id") ?: continue
                    val ep = v.optInt("episode", 0).takeIf { it > 0 }
                    val seasonNum = v.optInt("season_number", 0).takeIf { it > 0 }
                    episodes.add(newEpisode("$mainUrl/video/$vId") {
                        name = jstr(v, "title") ?: "الحلقة ${ep ?: ""}"
                        episode = ep
                        season = seasonNum
                        posterUrl = abs(posterOf(v)) ?: poster
                    })
                }
                // مسلسل — حتى لو لم تجمع الحلقات (اشتراك) نعيده مسلسلاً بلا حلقات،
                // لا فيلماً، كي لا يُسيَّج Type.
                return runCatching {
                    newTvSeriesLoadResponse(title, "$mainUrl/title/$id", TvType.TvSeries, episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))) {
                        this.posterUrl = poster
                        this.plot = description
                    }
                }.getOrNull()
            }

            // فيلم: الحلقة الوحيدة عبر lastvideos.regular؛ فإن غابت، نأخذ أول
            // مقطع regular من قائمة حلقات البرنامج (الصحيح لا معرّف المشروع الذي
            // يجلب settings:null في /player).
            val movieVideoId = p.optJSONObject("lastvideos")?.optString("regular").orEmpty()
                .takeIf { it.isNotBlank() }
                ?: regularVideos.firstOrNull()?.let { jstr(it, "id") }
                ?: id
            val watchUrl = "$mainUrl/video/$movieVideoId"
            return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    // ---------- البث ----------
    /**
     * فك روابط التشغيل من `/video/{id}/player`.
     *
     * أخطاء التشغيل المكتشفة ميدانياً (§ أُلحقت عند إصلاحها):
     *  - محتوى الأعضاء (`access:"member"`): `/player` يعيد **401** — قبل هذه
     *    المعالجة كان `return false` يعرض «لا روابط». الآن نُطلق رابط صفحة
     *    المشاهدة كبديل (خارج الـ try كي يغطي الاستثناءات أيضاً).
     *  - أفلام بلا `settings` (أو معرّف مشروع بدل مقطع): `settings:null` —
     *    نفس البديل.
     *  - روابط الضيوف: **HLS/DASH محميّة بـ Widevine/FairPlay** (`drm.type`)،
     *    لا تشتغل على أندرويد؛ لذا تُصدَر روابط MP4 المباشرة (قابلة للتشغيل)
     *    **أولاً** ثم HLS في آخر القائمة لا أولها.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = Regex("""/video/(\d+)""").find(data)?.groupValues?.get(1)
            ?: Regex("""(\d+)""").find(data.substringAfterLast('/'))?.groupValues?.get(1)
        val collected = mutableListOf<ExtractorLink>()

        // جلب عدّادات السيرفر — 401/شبكة/تنسيق تُسقطنا إلى `null` (لا ترمي خارجاً).
        val settings = try {
            if (videoId == null) null
            else fetchJson("$API/video/$videoId/player").optJSONObject("settings")
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks player ($videoId): ${e.message}")
            null
        }

        if (settings != null) {
            // الترجمة (vtts) — مرايا غالباً بدونها
            if (prefs?.getBoolean(MarayaSettingsBottomSheet.KEY_SHOW_SUBS, true) != false) {
                val vtts = settings.optJSONArray("vtts") ?: JSONArray()
                for (i in 0 until vtts.length()) {
                    val v = vtts.optJSONObject(i) ?: continue
                    val url = v.optString("url").ifBlank { v.optString("file") }.ifBlank { continue }
                    val lang = v.optString("lang").ifBlank { "العربية" }
                    runCatching { subtitleCallback(newSubtitleFile(lang, url)) }
                }
            }

            val emitted = mutableSetOf<String>()
            // 1) روابط MP4 المباشرة لكل جودة — قابلة للتشغيل على أندرويد (بلا DRM).
            val res = settings.optJSONArray("resolutions") ?: JSONArray()
            for (i in 0 until res.length()) {
                val r = res.optJSONObject(i) ?: continue
                val u = r.optString("url").ifBlank { continue }
                val q = r.optString("key", "").ifBlank { "auto" }
                if (emitted.add(u)) {
                    emitter(collected, q, u, ExtractorLinkType.VIDEO, null, qualityName = q)
                }
            }

            // 2) HLS — محمي بـ DRM في الغالب؛ يبقى متاحاً لكنه في نهاية القائمة.
            val hls = settings.optJSONObject("protocols")?.optString("hls").orEmpty()
            if (hls.isNotBlank() && emitted.add(hls)) {
                val drm = settings.optJSONObject("drm")?.optString("type").orEmpty()
                emitter(collected, if (drm.isNotBlank()) "HLS (DRM)" else "HLS", hls, ExtractorLinkType.M3U8, null)
            }
        }

        // الحل الصادق عند تعذّر فك السيرفر (member/svod/بلا settings).
        // ميدانياً (فحص 2026-09): محتوى الأعضاء (member) وsvod يعيد /player **401** —
        // روابطه غير موجودة للزائر أصلاً. كل أنواع الروابط في CloudStream
        // (VIDEO/M3U8/DASH) تُشغَّل كمقطع — لا يوجد نوع «صفحة/mوقع». لذا سياسة
        // اليوم (رابط كاذب يبدو فيديو وهو صفحة HTML) خطأ أُصلحه هنا:
        // افتراضياً نُنهي بلا روابط (الوضع الفعلي). وإن فعّل المستخدم خيار
        // «رابط الصفحة الخام» (افتراضياً true = سلوك اليوم) نُصدِر صفحة HTML
        // بتسمية تنبّه أنها لا تُشغَّل — فاختياره هو ولمعدّه سببٌ صريح.
        if (collected.isEmpty() && videoId != null &&
            prefs?.getBoolean(MarayaSettingsBottomSheet.KEY_SHOW_RAW_LINK, true) == true
        ) {
            // طلب المستخدم رؤية الرابط: نُصدِر الصفحة لكن بتسمية «لا يُشغَّل».
            emitter(
                collected, "رابط الصفحة (HTML — لا يُشغَّل)",
                "$mainUrl/video/$videoId", ExtractorLinkType.M3U8, mainUrl
            )
            Log.w(TAG, "loadLinks: no stream for video $videoId — page link emitted (user requested)")
        }

        if (collected.isEmpty()) return false
        emitSorted(prefs, collected, callback)
        return true
    }

    private suspend fun emitter(
        collected: MutableList<ExtractorLink>,
        label: String,
        url: String,
        type: ExtractorLinkType,
        referer: String?,
        qualityName: String? = null
    ) {
        val ref = referer ?: mainUrl
        val quality = runCatching { qualityName?.let { getQualityFromName(it) } }.getOrNull() ?: Qualities.Unknown.value
        collected.add(
            newExtractorLink(
                source = "Maraya",
                name = label,
                url = url,
                type = type
            ) {
                this.quality = quality
                this.referer = ref
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*",
                    "Referer" to ref
                )
            }
        )
    }

    /**
     * ★ بثّ الروابط بعد اكتمالها: «افتراضي» = نفس الترتيب والعدد تماماً،
     * و«تصاعدي/تنازلي» يعيدان ترتيبها فقط (فرز مستقر).
     */
    private fun emitSorted(prefs: SharedPreferences?, collected: List<ExtractorLink>, callback: (ExtractorLink) -> Unit) {
        val order = prefs?.getString(MarayaSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
    }
}