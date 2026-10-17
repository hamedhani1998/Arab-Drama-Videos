package com.dramamondo.plugin

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

private const val DM_MAIN = "https://dramamondo.com"

class DramaMondoProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = DM_MAIN
    override var name = "DramaMondo"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val HOME_KEY = "__home__"

    override val mainPage = mainPageOf(
        HOME_KEY to "الأحدث"
    )

    // كاش لقطة الـ dump (نحو 585KB لكل الموقع): تُبنى منها صفوف الواجهة والبحث
    // والتفاصيل من طلب واحد بدل إعادة جلبها في كل نداء.
    @Volatile private var cachedDump: Pair<Long, String>? = null
    private val DUMP_TTL_MS = 5 * 60 * 1000L

    private suspend fun fetchDump(): String {
        val now = System.currentTimeMillis()
        val hit = cachedDump
        if (hit != null && now - hit.first < DUMP_TTL_MS) return hit.second
        val text = app.get("$mainUrl/api.php?t=$now").text
        // لا نخزّن استجابة فارغة/معطّلة كي لا تفرغ الصفوف حتى انتهت (درس «الفشل يخفي كل شيء»)
        if (!text.isNullOrBlank() && text.length > 1000) cachedDump = now to text
        return text
    }

    private fun parseDump(text: String): List<JsonNode> {
        return try {
            val json = mapper.readTree(text)
            if (json.isArray) json.toList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // رابط التفاصيل: slug مرمّز — يفتح في المتصفح ويعود إلى load() سليمًا
    private fun detailUrl(slug: String): String =
        "$mainUrl/${URLEncoder.encode(slug, "UTF-8").replace("+", "%20")}"

    private fun cardOf(obj: JsonNode): SearchResponse? {
        val slug = obj["slug"]?.asText() ?: return null
        val title = obj["title"]?.asText()?.trim() ?: return null
        if (slug.isEmpty() || title.isEmpty()) return null
        val poster = obj["poster"]?.asText() ?: ""
        return newTvSeriesSearchResponse(title, detailUrl(slug), TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun platformVisible(slug: String): Boolean {
        val master = prefs?.getBoolean(DramaMondoSettings.KEY_SHOW_PLATFORMS, true) != false
        if (!master) return false
        return prefs?.getBoolean(DramaMondoSettings.platformKey(slug), true) != false
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = parseDump(fetchDump())
        val rows = mutableListOf<HomePageList>()

        // صف «الأحدث»: مرتّب بالأحدث تحديثًا أولًا
        val latest = items
            .sortedByDescending { it["updatedAt"]?.asText() ?: it["createdAt"]?.asText() ?: "" }
            .mapNotNull { cardOf(it) }
            .take(60)
        if (latest.isNotEmpty()) rows.add(HomePageList("الأحدث", latest))

        // صفوف المنصّات: بترتيب platforms.html، ولا صف لمنصّة بلا أعمال
        val grouped = items.filter { it["visible"]?.asBoolean() != false }
            .groupBy { it["platform"]?.asText() ?: "" }
        if (prefs?.getBoolean(DramaMondoSettings.KEY_SHOW_PLATFORMS, true) != false) {
            val limit = prefs?.getString(DramaMondoSettings.KEY_ROW_LIMIT, "all")?.trim()?.toIntOrNull()
            var platformRows = DM_PLATFORMS.mapNotNull { (slug, label) ->
                val cards = grouped[slug]?.mapNotNull { cardOf(it) } ?: return@mapNotNull null
                if (cards.isEmpty() || !platformVisible(slug)) return@mapNotNull null
                HomePageList(label, cards)
            }
            // منصّات تظهر في البيانات ولم تُدرج في platforms.html
            grouped.forEach { (slug, list) ->
                if (slug.isEmpty() || DM_PLATFORMS.any { it.first == slug }) return@forEach
                val cards = list.mapNotNull { cardOf(it) }
                if (cards.isNotEmpty() && platformVisible(slug)) {
                    platformRows = platformRows + HomePageList(slug, cards)
                }
            }
            if (limit != null && limit > 0) platformRows = platformRows.take(limit)
            rows.addAll(platformRows)
        }
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return parseDump(fetchDump()).mapNotNull { obj ->
            val title = obj["title"]?.asText() ?: ""
            val slug = obj["slug"]?.asText() ?: ""
            if (title.lowercase().contains(q) || slug.contains(query.trim())) cardOf(obj) else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // الرابط مرمّز (URLEncoder) بينما slug في الـ API نصّ عربي خام — ففكّ الترميز
        // قبل المطابقة. مقارنة raw==raw تفشل دائمًا وكانت تُسقط load() على المسلسل
        // الأول في القائمة (يفتح تفاصيل عملٍ غير الصحيح).
        val rawSlug = url.substringAfterLast("/").substringBefore("?")
        val slug = try { URLDecoder.decode(rawSlug, "UTF-8") } catch (e: Exception) { rawSlug }
        val items = parseDump(fetchDump())
        val obj = items.firstOrNull {
            val s = it["slug"]?.asText() ?: ""
            s == slug || s == rawSlug
        } ?: return null

        val title = obj["title"]?.asText()?.trim() ?: "DramaMondo"
        val poster = obj["poster"]?.asText() ?: ""
        val synopsis = obj["synopsis"]?.asText() ?: obj["seoDescription"]?.asText() ?: ""

        // روابط سيرفرات وحدة مشاهدة واحدة (سطر لكل رابط) — بيانات الحلقة يجب أن
        // تبدأ بـ http وإلا لاحقق CloudStream mainUrl إليها.
        fun serversOf(node: JsonNode?): List<String> {
            if (node == null || !node.isArray) return emptyList()
            return node.mapNotNull { s -> s["url"]?.asText() }.filter { it.startsWith("http") }
        }

        val episodes = mutableListOf<Episode>()
        val epsList = obj["episodesList"]
        if (epsList != null && epsList.isArray && epsList.size() > 0) {
            epsList.forEachIndexed { idx, e ->
                val servers = serversOf(e["servers"])
                if (servers.isNotEmpty()) {
                    val epNum = e["number"]?.asInt() ?: (idx + 1)
                    episodes.add(newEpisode(servers.joinToString("\n")) {
                        this.name = "الحلقة $epNum"
                        this.episode = epNum
                    })
                }
            }
        }
        if (episodes.isEmpty()) {
            // كل الأجزاء: كل جزء = حلقة واحدة، وسيرفراته كلها داخلها كروابط تشغيل
            val parts = obj["parts"]
            if (parts != null && parts.isArray && parts.size() > 0) {
                parts.forEachIndexed { idx, part ->
                    val servers = serversOf(part["servers"])
                    if (servers.isNotEmpty()) {
                        val partTitle = part["title"]?.asText()?.trim() ?: ""
                        episodes.add(newEpisode(servers.joinToString("\n")) {
                            this.name = partTitle.ifEmpty { "الحلقة ${idx + 1}" }
                            this.episode = idx + 1
                        })
                    }
                }
            }
        }
        if (episodes.isEmpty()) {
            val servers = serversOf(obj["servers"])
            if (servers.isNotEmpty()) {
                episodes.add(newEpisode(servers.joinToString("\n")) {
                    this.name = "المشاهدة كاملة"
                    this.episode = 1
                })
            }
        }
        if (episodes.isEmpty()) {
            // آخر ملاذ: صفحة التفاصيل نفسها — loadLinks يعيد حلّ السيرفرات من الـ dump
            episodes.add(newEpisode(url) {
                this.name = "المشاهدة كاملة"
                this.episode = 1
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // اسم السيرفر الواضح من مضيف الرابط (لا يمكن معرفة الجودة/الحجم من الموقع)
    private fun serverNameOf(rawUrl: String): String = when {
        rawUrl.contains("rumble.com") -> "Rumble"
        rawUrl.contains("voe.") -> "Voe"
        rawUrl.contains("vidara.") -> "Vidara"
        rawUrl.contains("netshort") -> "NetShort"
        rawUrl.contains("dramabox") -> "Dramabox"
        rawUrl.contains("dramawave") -> "DramaWave"
        rawUrl.contains("reelshort") -> "ReelShort"
        rawUrl.contains("shorttv") -> "ShortTV"
        rawUrl.contains("flextv") -> "FlexTV"
        rawUrl.contains("shortmax") -> "ShortMax"
        rawUrl.contains("flickreels") -> "FlickReels"
        rawUrl.contains("moboreels") -> "MoboReels"
        rawUrl.contains("shortwave") -> "ShortWave"
        rawUrl.contains("stardust") -> "StarDust"
        rawUrl.contains("petadrama") -> "PetaDrama"
        rawUrl.contains("storyreel") -> "StoryReel"
        rawUrl.contains("kalostv") -> "KalosTV"
        rawUrl.contains("joyreels") -> "JoyReels"
        rawUrl.contains("goodshort") -> "GoodShort"
        rawUrl.contains("moreshort") -> "MoreShort"
        else -> "DramaMondo"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // بيانات الحلقة = سيرفرات الجزء كلها، سطر لكل رابط. إن جاءت كصفحة تفاصيل
        // (آخر ملاذ في load()) نعيد حلّ السيرفرات من الـ dump حتى لا ت dead-end.
        var urls = data.split("\n").map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false
        if (urls.size == 1 && urls[0].startsWith("$mainUrl/")) {
            val rawSlug = urls[0].substringAfterLast("/").substringBefore("?")
            val slug = try { URLDecoder.decode(rawSlug, "UTF-8") } catch (e: Exception) { rawSlug }
            val obj = parseDump(fetchDump()).firstOrNull {
                val s = it["slug"]?.asText() ?: ""
                s == slug || s == rawSlug
            }
            if (obj != null) {
                val resolved = obj["parts"]?.takeIf { it.isArray }?.flatMap { part ->
                    part["servers"]?.takeIf { it.isArray }?.mapNotNull { s -> s["url"]?.asText() } ?: emptyList()
                }?.filter { it.startsWith("http") } ?: emptyList()
                if (resolved.isNotEmpty()) urls = resolved
            }
        }

        var produced = 0
        for (u in urls) {
            try {
                loadExtractor(u, "$mainUrl/", subtitleCallback) { link ->
                    produced++
                    callback(link)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // المُستخرج لا يعرف هذا المضيف — نجرّب الرابط التالي
            }
        }
        if (produced > 0) return true

        // فشل كل المُستخرجات: نُصدر رابطًا مباشرًا فقط إن كان الملف م media صريحًا
        // (mp4/m3u8) — رابط صفحة تضمّن كـ VIDEO يعطي ExoPlayer 3001 ولا يفيد.
        val direct = urls.filter {
            val p = it.substringBefore("?").lowercase()
            p.endsWith(".mp4") || p.endsWith(".m3u8") || p.contains(".m3u8")
        }
        for (u in direct) {
            val name = serverNameOf(u)
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = u,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
        }
        return direct.isNotEmpty()
    }
}
