package com.fulldrama.plugin

import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val FD_MAIN = "https://www.full-drama.com"

// أقسام التصنيفات بترتيب قائمة الموقع — لكلٍّ مفتاح إظهار/إخفاء في الإعدادات
// (الموقع لا يملك «منصات»؛ التصنيفات هي أقسامه المقابلة).
private val FD_GENRES = listOf(
    "romance" to "رومانسي",
    "ceo-boss" to "مدير تنفيذي",
    "family" to "عائلي",
    "revenge" to "انتقام",
    "historical" to "تاريخي",
    "sci-fi" to "خيال علمي",
    "action" to "أكشن",
    "time-travel" to "السفر بالزمن",
    "underdog" to "انتصار المستضعف",
    "mystery" to "غموض",
    "medical" to "طبي",
    "school" to "مدرسة",
    "thriller" to "إثارة",
    "business" to "أعمال",
    "marriage" to "زواج",
    "vampire" to "مستذئب",
    "pregnancy" to "حمل",
)

class FullDramaProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = FD_MAIN
    override var name = "Full Drama"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val HOME_KEY = "__home__"

    // الصفوف الظاهرة: «الأحدث» أولًا ثم التصنيفات حسب مفاتيح الإعدادات.
    // (CS3 يستدعي getMainPage لكل صف عند كل رسم — عدد الصفوف هو رافعة السرعة.)
    override val mainPage = mainPageOf(
        *visibleRows().toTypedArray()
    )

    private fun visibleRows(): List<Pair<String, String>> {
        val master = prefs?.getBoolean(FullDramaSettings.KEY_SHOW_PLATFORMS, true) != false
        val shown = mutableListOf(HOME_KEY to "الأحدث")
        if (master) {
            val limit = prefs?.getString(FullDramaSettings.KEY_ROW_LIMIT, "all")?.trim()?.toIntOrNull()
            val rows = FD_GENRES.filter { (slug, _) ->
                prefs?.getBoolean(FullDramaSettings.platformKey(slug), true) != false
            }
            shown += if (limit != null && limit > 0) rows.take(limit) else rows
        }
        return shown
    }

    // ذاكرة صفوف الواجهة: "slug|page" -> (millis, البطاقات) — إعادة الرسم فوري،
    // وعند فشل جلبٍ جديد تُعاد آخر نسخة ناجحة بدل إفراغ الصفّ.
    private val rowCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
    private val ROW_CACHE_TTL_MS = 10 * 60 * 1000L

    // تنظيف عنوان البطاقات: «مشاهدة مسلسل X مترجم كامل جميع الحلقات HD» -> X
    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("""^مشاهدة\s+(?:مسلسل|فيلم|حلقة)\s*"""), "")
        t = t.replace(Regex("""\s*(?:مترجم|مدبلج)\s+كامل(?:\s+جميع\s+الحلقات)?\s*(?:HD|FHD|4K)?$"""), "")
        t = t.replace(Regex("""\s+HD$"""), "")
        return t.trim().ifEmpty { raw.trim() }
    }

    private fun parseCardsDoc(doc: org.jsoup.nodes.Document, seen: MutableSet<String>): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        doc.select("article.card a[href*='/detail/'], a[href*='/detail/']").forEach { a ->
            val href = a.attr("href")
            if (href.isEmpty() || !href.contains("/detail/")) return@forEach
            val abs = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seen.add(abs)) return@forEach
            val imgEl = a.select("img").firstOrNull()
            val img = imgEl?.attr("src")?.ifEmpty { imgEl.attr("data-src") } ?: ""
            val rawTitle = a.select("h3, h2, .card-title, .title").text()
                .ifEmpty { a.attr("aria-label") }
                .ifEmpty { imgEl?.attr("alt") ?: "" }
                .ifEmpty { a.text() }
            val title = cleanTitle(rawTitle)
            if (title.isEmpty()) return@forEach
            out.add(newTvSeriesSearchResponse(title, abs, TvType.TvSeries) {
                this.posterUrl = img
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val slug = request.data
        val p = maxOf(page, 1)
        val ckey = "$slug|$p"
        val hit = rowCache[ckey]
        if (hit != null && System.currentTimeMillis() - hit.first < ROW_CACHE_TTL_MS) {
            return newHomePageResponse(request.name, hit.second)
        }
        val url = when {
            slug == HOME_KEY && p == 1 -> mainUrl
            slug == HOME_KEY -> "$mainUrl/library?page=$p"
            p == 1 -> "$mainUrl/genre/$slug"
            else -> "$mainUrl/genre/$slug?page=$p"
        }
        val items = try {
            val doc = app.get(url).document
            parseCardsDoc(doc, java.util.HashSet())
        } catch (e: Exception) {
            emptyList()
        }
        if (items.isEmpty()) {
            // الفشل/الفراغ لا يُفرغ صفًّا كان ظاهرًا
            if (hit != null && hit.second.isNotEmpty()) {
                return newHomePageResponse(request.name, hit.second)
            }
            return null
        }
        rowCache[ckey] = System.currentTimeMillis() to items
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        // /?s= يتجاهله الموقع صامتًا — نقطة البحث الصحيحة /search?q=
        val doc = app.get("$mainUrl/search?q=$q").document
        return parseCardsDoc(doc, java.util.HashSet())
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = cleanTitle(
            doc.select("h1").text().ifEmpty { doc.title() }
        )
        val poster = doc.select("meta[property=og:image]").attr("content")
            .ifEmpty { doc.select("img").firstOrNull()?.attr("src") ?: "" }
        val synopsis = doc.select("meta[property=og:description]").attr("content")
            .ifEmpty { doc.select(".description, .synopsis").firstOrNull()?.text() ?: "" }

        val episodes = mutableListOf<Episode>()
        val seen = java.util.HashSet<String>()
        // أزرار الحلقات: نفس مسار التفاصيل + ?ep=N (الأولى بلا ?ep=)
        doc.select("a.xr-episode-btn, .xr-episode-btn a, a[href*='?ep=']").forEach { a ->
            val href = a.attr("href").ifEmpty { a.attr("data-href") }
            if (href.isEmpty()) return@forEach
            val numStr = href.substringAfter("?ep=", "").substringBefore("&")
            val epNum = numStr.filter { it.isDigit() }.toIntOrNull()
                ?: a.text().filter { it.isDigit() }.toIntOrNull()
                ?: 1
            val epUrl = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> "$mainUrl$href"
                else -> "$mainUrl/$href"
            }
            if (!seen.add(epUrl)) return@forEach
            episodes.add(newEpisode(epUrl) {
                this.name = "الحلقة $epNum"
                this.episode = epNum
            })
        }
        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = "الحلقة 1"
                this.episode = 1
            })
        }
        episodes.sortBy { it.episode ?: 0 }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try { app.get(data).text } catch (e: Exception) { return false }
        // videoUrl داخل حمولة RSC: قد تأتي مهرَّبة بـ " أو \' و& مهرَّبة &
        val patterns = listOf(
            Regex("https://cdn\\.full-drama\\.com/[A-Za-z0-9_\\-]+\\.mp4\\?exp=\\d+(?:\\\\u0026sig=[0-9a-f]+)?(?:\\\\u0026media_type=[a-z_0-9]+)?"),
            Regex("https://cdn\\.full-drama\\.com/[A-Za-z0-9_\\-]+\\.mp4\\?exp=\\d+(?:&sig=[0-9a-f]+)?(?:&media_type=[a-z_0-9]+)?"),
            Regex("https://cdn\\.full-drama\\.com/[A-Za-z0-9_\\-]+\\.mp4\\?[^\"'\\\\\\s]+"),
        )
        for (rx in patterns) {
            val match = rx.find(html) ?: continue
            val raw = match.value.replace("\\u0026", "&")
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "MP4",
                    url = raw,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.VIDEO
                )
            )
            return true
        }
        return false
    }
}
