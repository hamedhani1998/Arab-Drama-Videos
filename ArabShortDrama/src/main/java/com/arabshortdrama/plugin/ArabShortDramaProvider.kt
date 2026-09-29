package com.arabshortdrama.plugin

import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

private val mapper = ObjectMapper().registerKotlinModule()

private const val DEAD_SUFFIX = " (غير متاح)"

/** مهلة فحص توفّع ديلي موشن — الواجهة بطيئة (حتى 20s) فلا نجمّد التشغيل. */
private const val DM_CHECK_TIMEOUT_MS = 6_000L

/** نافذة ذاكرة الـJSON: `home.php` مرة واحدة يخدم الأقسام الثلاثة، و`drama.php` تُعاد في التشغيل. */
private const val CACHE_MS = 60_000L

/**
 * ArabShortDrama — دراما قصيرة عربية (arabshortdrama.cloud).
 *
 * الموقع واجهة React تُكلّم واجهة JSON علنية بلا حماية Cloudflare (كلها GET):
 *  - /api/public/home.php        → {hero, latest, trending, dramas, categories}
 *  - /api/public/drama.php?slug= → {drama, sameCategory, trending}
 *  - /api/public/search.php?q=   → مصفوفة نتائج (العربية فقط؛ الإنجليزية تُرجع [])
 *
 * ★ حالة الموقع (فحص مباشر) — 43 دراما، مختلطة:
 *  - 17 دراما `slug = yt-{YouTubeId}`: مهلها وسومها من i.ytimg.com، والمعرّف
 *    الحقيقي في الـslug لا في `video_id` (الذي يبقى معرّف ديلي موشن وهمي).
 *    تُشغَّل عبر NewPipe (يوتيوب) كما في aryarabia — NewPipe نفسه لا يُضمَّن في
 *    الـcs3 ويقدّمه التطبيق وقت التشغيل.
 *  - 3 دراما ديلي موشن حيّة (تحقّقنا 200 من api.dailymotion.com).
 *  - 23 دراما وهمية: `video_id` من 6 خانات لا وجود لها (404) — أي أن الموقع
 *    نفسه لا يستطيع تشغيلها. نعرضها بوسم «غير متاح» (يمكن إخفاؤها من الإعدادات).
 *
 * مشغّل الموقع لا يفهم `yt-` إطلاقاً (يبثّ `dailymotion.com/embed/video/{video_id}`
 * فقط) — أي أن هذه الـ17 لا تُشغَّل إلا عبر هذه الإضافة.
 *
 * ★ كل دراما هنا فيلم واحد (حلقات صفرية)؛ دراما ديلي موشن قد يسمّيها الموقع
 * «موسم كامل» لكنها ملف واحد، فنعرضه مرة واحدة بلا تكرار.
 */
class ArabShortDramaProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "ArabShortDrama"
    override var mainUrl = "https://www.arabshortdrama.cloud"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val api = "$mainUrl/api/public"

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun nowMS(): Long = System.currentTimeMillis()

    /**
     * أقسام الصفحة الرئيسية. الترتيب (اسم، data) — و`getMainPage` يوجّه `data`
     * إلى مفتاح home.php. keepAlive يمنع إعادة الجلب عند التنقل بين الأقسام.
     */
    private val sections = listOf(
        "الأحدث" to "latest",
        "الأكثر مشاهدة" to "trending",
        "مختارات" to "hero",
    )

    override val mainPage = mainPageOf(*sections.map { MainPageData(name = it.first, data = it.second) }.toTypedArray())

    // ---------- كيان الموقع ----------

    private fun JsonNode.str(key: String): String? =
        get(key)?.takeIf { !it.isNull }?.asText()?.trim()?.ifBlank { null }

    private fun JsonNode.isYt(): Boolean = str("slug")?.startsWith("yt-") == true

    /**
     * ⚠ لا يمكن استنتاج التوفّع من شكل المعرّف — فحص حيّ على الـ26 معرّفاً
     * (2026-09-25) أثبت أن 14 منها حيّة و12 ميتة، والمعرّفات الحيّة والميتة
     * تتداخل في الطول والشكل تماماً. لذلك **لا طلب شبكة في العرض**، والوسم
     * «غير متاح» يُحسب من `home.php` وحده: يُقرأ حقل `is_active` إن وُجد،
     * وإلا فكل ديلي موشن يُعرض على حاله بلا وسم. التحقّق الحقيقي (طلب واحد،
     * مُخزَّن) يحدث في صفحة التفاصيل والتشغيل.
     */
    private fun JsonNode.markedDead(): Boolean {
        if (isYt()) return false
        val active = get("is_active") ?: get("isActive") ?: get("active")
        if (active != null && !active.isNull) return !(active.isBoolean && active.asBoolean())
        // field absent → do not guess; show untagged.
        return false
    }

    private fun JsonNode.toSearch(): SearchResponse? {
        val title = str("title") ?: return null
        val slug = str("slug") ?: return null
        val shown = if (markedDead()) title + DEAD_SUFFIX else title
        return newMovieSearchResponse(shown, "$mainUrl/drama/$slug", TvType.Movie, fix = false) {
            this.posterUrl = str("thumbnail_url")
        }
    }

    /**
     * ذاكرة قصيرة للـJSON: `home.php` يُجلب مرة واحدة لكل نافذة زمنية ويخدم
     * الأقسام الثلاثة (بدل ثلاثة جلبات)، و`drama.php` يُجلب مرة في صفحة التفاصيل
     * وتُعاد في التشغيل.
     */
    private val jsonCache = mutableMapOf<String, Pair<JsonNode, Long>>()
    private val jsonLock = Any()

    private suspend fun fetch(path: String): JsonNode? {
        val hit = synchronized(jsonLock) {
            val e = jsonCache[path]
            if (e != null && nowMS() - e.second < CACHE_MS) e.first else null
        }
        if (hit != null) return hit
        val fresh = try {
            val res = app.get(
                "$api/$path",
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json")
            )
            mapper.readTree(res.text)
        } catch (_: Exception) {
            null
        }
        if (fresh != null) synchronized(jsonLock) { jsonCache[path] = fresh to nowMS() }
        return fresh
    }

    private fun JsonNode.array(key: String): List<JsonNode> {
        val n = get(key)
        return if (n != null && n.isArray) n.toList() else emptyList()
    }

    private fun cardsOf(nodes: List<JsonNode>): List<SearchResponse> =
        nodes.mapNotNull { it.toSearch() }

    private fun showDead(): Boolean =
        prefs?.getBoolean(ArabShortDramaSettingsBottomSheet.KEY_SHOW_DEAD, true) != false

    /**
     * التحقّق من وجود فيديو ديلي موشن فعلاً — **طلب واحد لكل دراما**، ونتيجته
     * مُخزَّنة. الفحص مُقيَّد بمهلة قصيرة: واجهة ديلي موشن بطيئة جداً قيست بين
     * 0.8s و20s للطلب الواحد، ولا يجوز أن تُجمّد التشغيل انتظارها.
     */
    private val dmAliveCache = mutableMapOf<String, Boolean>()

    private suspend fun isDailymotionAlive(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        dmAliveCache[id]?.let { return it }
        val alive = try {
            withTimeout(DM_CHECK_TIMEOUT_MS) {
                val res = app.get(
                    "https://api.dailymotion.com/video/$id?fields=id",
                    headers = mapOf("User-Agent" to UA)
                )
                // a 404 body still arrives with status 200 on this endpoint; the error
                // object is the only reliable signal, so we branch on the body only.
                val body = res.text
                !body.contains("\"error\"") && body.contains("\"id\"")
            }
        } catch (_: Exception) {
            false
        }
        dmAliveCache[id] = alive
        return alive
    }

    /** معرّف يوتيوب الحقيقي، وهو ما بعد البادئة `yt-` في الـslug. */
    private fun ytId(slug: String?): String? =
        slug?.takeIf { it.startsWith("yt-") }?.removePrefix("yt-")?.ifBlank { null }

    // ---------- الصفحات ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            if (page > 1) return newHomePageResponse(request.name, emptyList())
            val home = fetch("home.php") ?: return newHomePageResponse(request.name, emptyList())

            val items = when (request.data) {
                "hero" -> cardsOf(home.array("hero"))
                "trending" -> cardsOf(home.array("trending"))
                else -> {
                    // «الأحدث»: latest أولاً ثم ما تبقّى من القائمة الكاملة
                    val seen = LinkedHashSet<String>()
                    val all = cardsOf(home.array("latest")) + cardsOf(home.array("dramas"))
                    all.filter { seen.add(it.url) }
                }
            }

            // خيار المستخدم: إخفاء الدراما الوهمية (الافتراضي = إظهارها)
            val final = if (showDead()) items else items.filterNot { it.name.endsWith(DEAD_SUFFIX) }

            newHomePageResponse(request.name, final)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            val node = fetch("search.php?q=$encoded")
            val list = when {
                node == null -> emptyList()
                node.isArray -> node.toList()
                else -> node.array("results")
            }
            var cards = cardsOf(list)
            if (!showDead()) cards = cards.filterNot { it.name.endsWith(DEAD_SUFFIX) }
            cards
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val slug = url.substringAfterLast("/").substringBefore("?").ifBlank { return null }
            val node = fetch("drama.php?slug=$slug") ?: return null
            val drama = node.get("drama") ?: return null
            val title = drama.str("title") ?: return null
            val poster = drama.str("thumbnail_url")

            val yt = ytId(drama.str("slug"))
            val dmId = drama.str("video_id")
            val plot = drama.str("description")
            // التحقّق الفعلي يتم هنا (طلب واحد لدراما واحدة) فيصحّح وسم القائمة
            // المُخمَّن بلا شبكة، ويملأ ذاكرة التوفّع فيتشير في التشغيل بلا فحص.
            val playable = yt != null || isDailymotionAlive(dmId)
            val shown = if (playable) title else title + DEAD_SUFFIX

            // dataUrl هو ما يصل إلى loadLinks — نحفظ فيه الـslug وحده.
            return newMovieLoadResponse(shown, "$mainUrl/drama/$slug", TvType.Movie, slug) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOfNotNull(drama.get("category")?.str("name"))
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- التشغيل ----------

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

    /** «360 • 62MB (H.264)» — تسمية تفرّق الجودات في قائمة الاختيار. */
    private fun richLabel(height: Int, url: String, mime: String?): String {
        val h = if (height > 0) height.toString() else "auto"
        val bytes = byteSizeOf(url)
        val mb = if (bytes > 0) "• ${"%.1f".format(bytes / 1048576.0)}MB" else ""
        val tag = codecTag(mime)
        val t = if (tag.isNotEmpty()) " ($tag)" else ""
        return "$h$mb$t"
    }

    /** يستخرج كوديكاً حقيقياً من mimeType («video/mp4; codecs="avc1.640028"»). */
    private fun codecFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val i = mime.indexOf("codecs=")
        if (i < 0) return null
        return mime.substring(i + "codecs=".length).trim().removeSurrounding("\"")
            .ifBlank { null }
    }

    /**
     * استخراج جودات يوتيوب عبر NewPipe ثم تشغيلها عبر **خادم DASH محلي**
     * (نمط aryarabia المُثبت). بعد تغييرات يوتيوب الأخيرة، روابط googlevideo
     * الخام (كما كانت هذه الإضافة تفعل سابقاً) لم تعد تُشغَّل مباشرة: النطاقات
     * مفكوكة وطلب الوسائط يحتاج Range requests رسمية للمضيف الموقَّع — وهو ما
     * يفعله المانيفست المحلي. يُبثّ كل تنسيق (ارتفاع × كوديك) مع أفضل صوتٍ له.
     */
    private suspend fun emitYoutube(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var produced = 0
        try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance()
                .fromUrl("https://www.youtube.com/watch?v=$videoId")
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            val dur = runCatching { s.length }.getOrNull() ?: 0
            val durationSeconds = if (dur > 0) dur else 3600L

            val seen = mutableSetOf<String>()
            val videoList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { v ->
                try {
                    val streamUrl = v.content ?: return@mapNotNull null
                    if (!seen.add(streamUrl)) return@mapNotNull null
                    val height = runCatching { v.height }.getOrNull() ?: 0
                    if (height <= 0) return@mapNotNull null
                    var mime = v.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = ArabShortDashServer.mimeFromUrl(streamUrl, false)
                    val initR = if (v.initStart != null && v.initEnd != null) "${v.initStart}-${v.initEnd}" else null
                    val indexR = if (v.indexStart != null && v.indexEnd != null) "${v.indexStart}-${v.indexEnd}" else null
                    ASDStreamInfo(streamUrl, mime, height, height.toString(), initR, indexR, codecFromMime(mime))
                } catch (_: Exception) { null }
            }.distinctBy { it.url }

            val audioList = (s.audioStreams ?: emptyList()).mapNotNull { a ->
                try {
                    val aUrl = a.content ?: return@mapNotNull null
                    val bitrate = runCatching { a.bitrate ?: 128000 }.getOrNull() ?: 128000
                    var mime = runCatching { a.format?.mimeType }.getOrNull()
                    if (mime.isNullOrEmpty()) mime = ArabShortDashServer.mimeFromUrl(aUrl, true)
                    val initR = if (a.initStart != null && a.initEnd != null) "${a.initStart}-${a.initEnd}" else null
                    val indexR = if (a.indexStart != null && a.indexEnd != null) "${a.indexStart}-${a.indexEnd}" else null
                    ASDAudioInfo(aUrl, mime, bitrate, initR, indexR, codec = codecFromMime(mime))
                } catch (_: Exception) { null }
            }.distinctBy { it.url }

            ArabShortDashServer.ensureStarted()

            // كل تنسيق فيديو يُبثّ مع أفضل صوتٍ مواءَماً لكوديكه، أو وحده عند غياب الصوت.
            for (video in videoList) {
                val bestAudio = if (audioList.isNotEmpty()) {
                    val family = if (video.mimeType.contains("webm")) { a: ASDAudioInfo ->
                        a.mimeType.contains("webm")
                    } else { a: ASDAudioInfo ->
                        a.mimeType.contains("mp4")
                    }
                    audioList.sortedWith(
                        compareByDescending<ASDAudioInfo>(family).thenByDescending { it.bitrate }
                    ).firstOrNull()
                } else null

                val localLink = ArabShortDashServer.buildAndRegister(
                    video, if (bestAudio != null) listOf(bestAudio) else emptyList(), durationSeconds
                )
                if (localLink != null) {
                    produced++
                    callback(
                        newExtractorLink(name, "يوتيوب ${richLabel(video.height, video.url, video.mimeType)}", localLink, type = ExtractorLinkType.DASH) {
                            referer = "https://www.youtube.com/"
                            quality = video.height
                        }
                    )
                }
            }
        } catch (_: Exception) {
        }
        return produced
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // dataUrl = الـslug (انظر load) — نقبله كاملاً أو مقتطعاً بعد «|»
            val slug = data.substringAfter("|").substringBefore("?").trim()
            if (slug.isEmpty()) return false

            // نجمع الروابط ثم نبثّها دفعة واحدة (بترتيب اختيار المستخدم).
            // الافتراضي = نفس ترتيب الجمع تماماً، بلا حذف ولا تكرار.
            val collected = mutableListOf<ExtractorLink>()

            val yt = ytId(slug)

            // ★ في دراما `yt-` يكون `video_id` معرّف ديلي موشن وهمي، فلا معنى
            // لجلب drama.php ولا لفحص التوفّع — نتجاوز الطلبين كلياً. ولا نُتحمّل
            // فحص التوفّع إلا إذا لم يكن في ذاكرة (صُحّح في صفحة التفاصيل).
            var fromYt = 0
            if (yt != null) {
                fromYt = emitYoutube(yt) { collected.add(it) }
            } else {
                val dmId = fetch("drama.php?slug=$slug")?.get("drama")?.str("video_id")
                if (dmId != null && isDailymotionAlive(dmId)) {
                    collected.add(dmLink(dmId))
                }
            }

            if (collected.isEmpty()) return false

            // تفضيل يوتيوب (الافتراضي) = روابط يوتيوب أولاً ثم ديلي موشن؛
            // إطفاؤه يدور على الشريحة فقط — بلا حذف ولا تكرار.
            val ordered = if (prefs?.getBoolean(ArabShortDramaSettingsBottomSheet.KEY_PREFER_YOUTUBE, true) == false && fromYt > 0 && fromYt < collected.size) {
                collected.drop(fromYt) + collected.take(fromYt)
            } else {
                collected
            }

            // ترتيب الجودات: إعادة ترتيب فقط (Kotlin sortedBy مستقر)،
            // والقيمة الافتراضية = نفس ترتيب الجمع حرفياً.
            val order = prefs?.getString(ArabShortDramaSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
            val sorted = when (order) {
                "asc" -> ordered.sortedBy { it.quality }
                "desc" -> ordered.sortedByDescending { it.quality }
                else -> ordered
            }
            sorted.forEach { callback(it) }
            Log.d("ArabShortDrama", "loadLinks $slug: yt=$fromYt total=${sorted.size}")
            true
        } catch (e: Exception) {
            Log.e("ArabShortDrama", "loadLinks failed: ${e.message}")
            false
        }
    }

    /** يبني رابط ديلي موشن مباشر (جودة واحدة). */
    private suspend fun dmLink(id: String): ExtractorLink {
        val url = "https://www.dailymotion.com/embed/video/$id"
        return newExtractorLink(name, "ديلي موشن", url) {
            referer = "https://www.dailymotion.com/"
            quality = getQualityFromName("480p")
        }
    }
}
