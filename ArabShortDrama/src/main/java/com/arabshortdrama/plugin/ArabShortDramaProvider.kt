package com.arabshortdrama.plugin

import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

private val mapper = ObjectMapper().registerKotlinModule()

private const val DEAD_SUFFIX = " (غير متاح)"

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
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val api = "$mainUrl/api/public"

    private val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

    private suspend fun JsonNode.toSearch(): SearchResponse? {
        val title = str("title") ?: return null
        val slug = str("slug") ?: return null
        // the real YouTube id lives in the slug; video_id on yt- rows is a dead DM id
        val playable = isYt() || isDailymotionAlive(str("video_id"))
        // ★ `name` غير قابل لإعادة الإسناد في MovieSearchResponse، فوسم «غير متاح»
        // يُخبز في الاسم الممرَّر نفسه.
        val shown = if (playable) title else title + DEAD_SUFFIX
        return newMovieSearchResponse(shown, "$mainUrl/drama/$slug", TvType.Movie, fix = false) {
            this.posterUrl = str("thumbnail_url")
        }
    }

    private suspend fun fetch(path: String): JsonNode? = try {
        val res = app.get(
            "$api/$path",
            referer = "$mainUrl/",
            headers = mapOf("User-Agent" to UA, "Accept" to "application/json")
        )
        mapper.readTree(res.text)
    } catch (_: Exception) {
        null
    }

    private fun JsonNode.array(key: String): List<JsonNode> {
        val n = get(key)
        return if (n != null && n.isArray) n.toList() else emptyList()
    }

    private suspend fun cardsOf(nodes: List<JsonNode>): List<SearchResponse> =
        nodes.mapNotNull { it.toSearch() }

    private fun showDead(): Boolean =
        prefs?.getBoolean(ArabShortDramaSettingsBottomSheet.KEY_SHOW_DEAD, true) != false

    /**
     * فحص سريع لوجود فيديو ديلي موشن فعلاً. أغلب معرّفات الموقع وهمية
     * (6 خانات عشوائية) و api.dailymotion.com تُرجع 404 لها؛ نتحقق مرة واحدة
     * لكل دراما ونحفظ النتيجة حتى لا نُعيد الفحص مع كل فتح.
     */
    private val dmAliveCache = mutableMapOf<String, Boolean>()

    private suspend fun isDailymotionAlive(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        dmAliveCache[id]?.let { return it }
        val alive = try {
            val res = app.get(
                "https://api.dailymotion.com/video/$id?fields=id",
                headers = mapOf("User-Agent" to UA)
            )
            // a 404 body still arrives with status 200 on this endpoint; the error
            // object is the only reliable signal, so we branch on the body only.
            val body = res.text
            !body.contains("\"error\"") && body.contains("\"id\"")
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

    /** استخراج جودات يوتيوب عبر NewPipe (كما في aryarabia). */
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

            val audioStreams = s.audioStreams.orEmpty()
            val seen = mutableSetOf<String>()
            (s.videoOnlyStreams ?: emptyList()).forEach { v ->
                val streamUrl = v.content
                if (!seen.add(streamUrl)) return@forEach
                val height = runCatching { v.height }.getOrNull() ?: 0
                if (height <= 0) return@forEach
                produced++
                callback(
                    newExtractorLink(name, "يوتيوب ${height}p", streamUrl) {
                        referer = "https://www.youtube.com/"
                        quality = height
                        audioTracks = audioStreams.map { newAudioFile(it.content) }
                    }
                )
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
            val node = fetch("drama.php?slug=$slug")
            val dmId = node?.get("drama")?.str("video_id")

            val fromYt = if (yt != null) emitYoutube(yt) { collected.add(it) } else 0

            if (dmId != null && isDailymotionAlive(dmId)) {
                collected.add(dmLink(dmId))
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
