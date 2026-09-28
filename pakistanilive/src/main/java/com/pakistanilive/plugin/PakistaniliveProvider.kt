package com.pakistanilive.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import android.content.SharedPreferences
import android.util.Log

/**
 * مصدر «باكستاني لايف» (pakistanilive.com) — دراما باكستانية مترجمة للعربية.
 *
 * البنية (رُسمت ميدانياً — وكوردبريس/GeneratePress):
 *  - الصفحة الرئيسية: `div.series-item > a[href*=/category/]` على صفحة
 *    «مسلسلات باكستانية مترجمة» — كل بطاقة سلسلة (بوستر `img[data-src]`
 *    نسبي → يُسبق بـ mainUrl، وعنوان `span.series-title`).
 *  - سلسلة: `/category/{slug}/` — بطاقات الحلقات `div.gb-loop-item`، ومعرف
 *    الفئة في `body.category-{id}`. نكتفي بالبطاقات التي تحمل `category-{id}`
 *    (صفحة الكاتيكوري تعرض أيضاً مقالات من سلاسل شقيقة). الحلقات تُرقَّم صفحات
 *    (`page/2/`)، والرقم من عنوان الرابط (`...الحلقة-N-...`).
 *  - حلقة: `initCustomPlayer("video1","{ytId}", \`{SRT}\`)` — معرف يوتيوب +
 *    ترجمة SRT عربية مطمورة. الترجمة تُستضاف عبر [PakiSrtServer] المحلي
 *    (لاعب CloudStream يرفض data: ولا يحمل SubtitleFile محتوىً — انظر الذاكرة).
 */
class PakistaniliveProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    companion object {
        private const val TAG = "Pakistanilive"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        // صفحة «مسلسلات باكستانية مترجمة» (الرئيسية للمصدر).
        private const val MAIN_CATEGORY_ROUTE = "/مسلسلات-باكستانية-مترجمة/"
    }

    override var mainUrl = "https://pakistanilive.com"
    override var name = "Pakistanilive"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "ar"
    override val hasMainPage = true

    private fun abs(u: String): String =
        if (u.startsWith("http")) u else "$mainUrl$u"

    // ---------- بطاقات السلسلة ----------
    private fun seriesCardOf(item: Element): SearchResponse? {
        val href = item.selectFirst("a")?.attr("href") ?: return null
        if (!href.contains("/category/")) return null
        val title = item.selectFirst(".series-title")?.text()?.trim().orEmpty()
            .ifBlank { item.selectFirst("a")?.attr("title") ?: return null }
        val poster = item.selectFirst("img")?.attr("data-src")
            ?.ifBlank { item.selectFirst("img")?.attr("src") }
            ?.let { abs(it) }
        return newTvSeriesSearchResponse(title, abs(href)) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = try { app.get("$mainUrl$MAIN_CATEGORY_ROUTE").document } catch (_: Exception) {
            return newHomePageResponse(emptyList())
        }
        val cards = doc.select("div.series-item").mapNotNull { seriesCardOf(it) }
            .distinctBy { it.url }
        return newHomePageResponse(listOf(HomePageList("مسلسلات باكستانية مترجمة", cards)))
    }

    // ---------- البث ----------
    override suspend fun search(query: String): List<SearchResponse> {
        // لا حاجة للبحث — الموقع فهرس كاتيكوريات؛ نمرره.
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try { app.get(url).document } catch (_: Exception) { return null }

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.substringBefore('|')?.trim()
            ?: return null
        val poster = doc.selectFirst("img[data-src]")?.attr("data-src")
            ?.ifBlank { doc.selectFirst("img")?.attr("src") }
            ?.takeIf { !it.isBlank() }
        val description = doc.selectFirst("h2, .entry-content p")?.text()

        // بطاقات الحلقات تحمل `category-<slug-token>` (لا المعرّف الرقمي الذي
        // يظهر في body). الصفحة تُظهر أيضاً بطاقات سلاسل شقيقة، لذا نكتفي
        // بالبطاقات التي يطابق رمز فئتها رمز فئة body نفسه.
        val bodyClass = doc.selectFirst("body")?.attr("class").orEmpty()
        val catSlug = Regex("""\bcategory-([a-zA-Z0-9-]+)""")
            .find(bodyClass)?.groupValues?.get(1)

        val episodes = ArrayList<Episode>()
        if (catSlug != null) {
            val catToken = "category-$catSlug"
            var curDoc: Document = doc
            var page = 0
            while (true) {
                val cards = curDoc.select("div.gb-loop-item")
                if (cards.isEmpty()) break
                var foundOnPage = 0
                for (c in cards) {
                    val cls = c.attr("class")
                    if (!cls.contains(catToken)) continue
                    foundOnPage++
                    val a = c.selectFirst("a") ?: continue
                    val epUrl = a.attr("href").ifBlank { continue }
                    val epNum = Regex("""الحلقة[_-](\d+)""").find(epUrl)
                        ?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode(epUrl) {
                        name = "الحلقة ${epNum ?: (episodes.size + 1)}"
                        episode = epNum
                        posterUrl = poster
                    })
                }
                if (foundOnPage == 0) break
                page++
                val nextPageUrl = curDoc.selectFirst(
                    "a[href*='/page/${page + 1}/']"
                )?.attr("href") ?: break
                curDoc = try { app.get(abs(nextPageUrl)).document } catch (_: Exception) { break }
            }
        }

        return newTvSeriesLoadResponse(
            title, url, TvType.TvSeries,
            episodes.sortedBy { it.episode }
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val collected = mutableListOf<ExtractorLink>()
        try {
            val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
            val m = Regex("""initCustomPlayer\("video1","([^"]+)",`(.*?)`\s*\)""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
                ?: return false

            val ytId = m.groupValues[1]
            val srtText = m.groupValues[2]

            // الترجمة المطمورة — استضافة محلية (VTT عبر PakiSrtServer).
            if (srtText.isNotBlank() &&
                prefs?.getBoolean(PakistaniliveSettingsBottomSheet.KEY_SHOW_SUBS, true) != false
            ) {
                PakiSrtServer.register(srtText)?.let { url ->
                    runCatching { subtitleCallback(newSubtitleFile("العربية", url)) }
                }
            }

            // الفيديو يوتيوب — نحوّله لمستخرج يوتيوب المدمج في CloudStream
            // (loadExtractor) كي يحصل المشغّل على روابط قابلة للتشغيل فعلاً.
            loadExtractor("https://www.youtube.com/watch?v=$ytId", "", subtitleCallback) { link ->
                collected.add(link)
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error: ${e.message}")
            return false
        }

        if (collected.isEmpty()) return false
        collected.forEach { callback(it) }
        return true
    }
}