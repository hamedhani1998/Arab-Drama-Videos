package com.stardusttv.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences

private val mapper = ObjectMapper().registerKotlinModule()

class StardustTVProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "StardustTV"
    override var mainUrl = "https://www.stardusttv.net"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث الدراما",
    )

    // ---- إشارات تحليل HTML ----
    // بطاقة العمل في الصفحة الرئيسية: <a class="video_item ..." href="/ar/episodes/01-العنوان-31554" title="العنوان">
    //   ... <img class="poster ..." src="https://assets.stardusttv.cc/uploadfile/...">
    private val cardStartRegex = Regex(
        """<a\s+class="video_item[^"]*"[^>]*href="(/ar/episodes/[^"]+)"[^>]*>"""
    )
    private val titleInBlockRegex = Regex("""title="([^"]*)"""")
    private val posterInBlockRegex = Regex("""class="poster[^"]*"[^>]*src="([^"]+)"""")

    // بيانات Nuxt المضمّنة في كل صفحة (قائمة الحلقات كاملة مع روابط الـ m3u8 للحلقات المجانية)
    private val nuxtRegex = Regex(
        """<script type="application/json" id="__NUXT_DATA__"[^>]*>([\s\S]*?)</script>"""
    )

    private data class Card(val title: String, val url: String, val poster: String?)

    private data class ShowData(
        val title: String,
        val plot: String?,
        val poster: String?,
        // (رقم الحلقة, رابط m3u8)
        val episodes: List<Pair<Int, String>>,
    )

    // يعيد العنوان العربي من مسار الرابط مثل (01-سيد-البورصة-21582) → سيد البورصة
    private fun titleFromPath(path: String): String? {
        var p = try {
            java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            path
        }
        p = p.replace(Regex("""^\d+-"""), "").replace(Regex("""-\d+$"""), "")
        val t = p.replace("-", " ").trim()
        return t.takeIf { it.isNotBlank() }
    }

    private fun parseCards(html: String): List<Card> {
        val cards = mutableListOf<Card>()
        val seen = mutableSetOf<String>()
        for (m in cardStartRegex.findAll(html)) {
            val href = m.groupValues[1]
            if (!seen.add(href)) continue
            val end = html.indexOf("</a>", m.range.last)
            if (end < 0) continue
            val block = html.substring(m.range.last, end)
            val title = titleInBlockRegex.find(block)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() } ?: continue
            val poster = posterInBlockRegex.find(block)?.groupValues?.get(1)
                ?.substringBefore("?")
            cards.add(Card(title, "$mainUrl$href", poster))
        }
        return cards
    }

    // فكّ ضغط بيانات Nuxt 3: قيمة أي حقل كعدد (أو سلسلة رقمية) هي فهرس
    // في نفس مصفوفة الـ JSON الكبيرة، فيجب تحويل الفهرس إلى القيمة الفعلية.
    private class Nuxt(private val root: JsonNode) {
        // في بيانات Nuxt 3، قيمة الحقل كعدد صحيح هي فهرس مباشر في مصفوفة الـ JSON
        // (مرجع لمرة واحدة فقط — root[idx] هو القيمة النهائية وليس مرجعاً آخر).
        fun deref(node: JsonNode?): JsonNode? {
            if (node == null) return node
            return when {
                node.isNumber -> {
                    val idx = node.asInt()
                    if (idx in 0 until root.size()) root[idx] else node
                }
                node.isTextual -> {
                    val idx = node.asText().toIntOrNull()
                    if (idx != null && idx in 0 until root.size()) {
                        val r = root[idx]
                        if (!r.isContainerNode) r else node
                    } else node
                }
                else -> node
            }
        }

        fun text(node: JsonNode?): String? = deref(node)?.takeIf { it.isTextual }?.asText()
        fun int(node: JsonNode?): Int? =
            deref(node)?.takeIf { it.isNumber }?.asInt()
    }

    private fun parseNuxtDetail(html: String, showId: Int): ShowData? {
        val nuxtMatch = nuxtRegex.find(html) ?: return null
        val root = try {
            mapper.readTree(nuxtMatch.groupValues[1])
        } catch (e: Exception) {
            return null
        } ?: return null
        if (!root.isArray) return null
        val nuxt = Nuxt(root)

        var showTitle: String? = null
        var showPlot: String? = null
        var showPoster: String? = null
        val eps = mutableListOf<Pair<Int, String>>()
        val seenEps = HashSet<Int>()

        fun visit(node: JsonNode, depth: Int) {
            if (depth > 16) return
            when {
                node.isArray -> for (c in node) visit(c, depth + 1)
                node.isObject -> {
                    // كائن العمل (يحتوي episode_total + cover_path)
                    if (node.has("episode_total") && node.has("cover_path")) {
                        val id = nuxt.int(node.get("id"))
                        if (id != null && id == showId) {
                            showTitle = nuxt.text(node.get("english_name"))
                                ?: nuxt.text(node.get("name"))
                            showPlot = nuxt.text(node.get("intro"))
                            showPoster = nuxt.text(node.get("cover_path"))
                        }
                    }
                    // كائن الحلقة (يحتوي filepath + vid)
                    if (node.has("filepath") && node.has("vid")) {
                        val vid = nuxt.int(node.get("vid"))
                        if (vid != null && vid == showId) {
                            val fp = nuxt.text(node.get("filepath"))
                            if (fp != null && fp.startsWith("http") && fp.contains(".m3u8")) {
                                val sort = nuxt.int(node.get("sort"))
                                if (sort != null && sort > 0 && seenEps.add(sort)) {
                                    eps.add(sort to fp)
                                }
                            }
                        }
                    }
                    val it = node.fields()
                    while (it.hasNext()) visit(it.next().value, depth + 1)
                }
            }
        }
        visit(root, 0)

        if (eps.isEmpty()) return null
        return ShowData(
            title = showTitle ?: "",
            plot = showPlot,
            poster = showPoster,
            episodes = eps.sortedBy { it.first },
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val res = app.get("$mainUrl/${request.data}", referer = mainUrl).text
            val items = parseCards(res).map { card ->
                newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                    this.posterUrl = card.poster
                }
            }
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            // لا توجد صفحة بحث على الموقع (SSR لا يوفرها)؛ نبحث في قائمة الصفحة الرئيسية.
            val res = app.get("$mainUrl/ar", referer = mainUrl).text
            parseCards(res)
                .filter { it.title.contains(q, ignoreCase = true) }
                .map { card ->
                    newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                        this.posterUrl = card.poster
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val res = app.get(url, referer = mainUrl).text

            val rawPath = url.substringAfter("/ar/episodes/", url)
            val showId = rawPath.substringAfterLast("-").toIntOrNull() ?: return null
            val data = parseNuxtDetail(res, showId) ?: return null

            val title = data.title.ifBlank {
                Regex("""<title>([^<]+)</title>""").find(res)?.groupValues?.get(1)?.trim()
                    ?: titleFromPath(rawPath)
                    ?: "StardustTV"
            }

            val eps = data.episodes.map { (num, fp) ->
                newEpisode(fp) {
                    episode = num
                    name = "الحلقة $num" + if (fp.contains("_AR_DUB")) " (مدبلج)" else ""
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = data.poster
                plot = data.plot
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val m3u8 = data
            // رابط واحد فقط 720p — فترتيب الجودات هنا بلا أثر (لا تُخزَّن القائمة
            // ولا يُعاد ترتيبها: البث كما هو حرفياً).
            callback(
                newExtractorLink(name, "StardustTV 720p", m3u8, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = getQualityFromName("720p")
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}