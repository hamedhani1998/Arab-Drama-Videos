package com.animeflix.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.DriveChain
import cloudstreamshared.MediaProbe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * AnimeFlix — `animeflix.dad` (يُحوِّل إلى `animeflix.my`).
 *
 * بنية الموقع (مقيسة 2026-10):
 *  - صفحة العمل: بطاقات جودة `<div class="dl-card"><span class="q">720p</span>`
 *    وزرّ `Gdrive + Mirrors` يقود إلى `episodes.animeflix.my/archives/<id>`.
 *  - صفحة الأرشيف: عنوانٌ لكل حلقة `<h3><a href="…/getlink/…">Episode N</a></h3>`.
 *  - رابط `/getlink/` يردّ 302 مباشرةً إلى `driveseed.org/r?…` ثم `/file/`،
 *    فتُقرأ مراياه (‎r2.dev / video-gen / workers.dev‎) بمحرّك [DriveChain].
 *
 * لهذا لا يوجد هنا أي «بوّابة» نماذج كما في MoviesMod: الموقع يعطي الرابط
 * النهائي بعد تحويلةٍ واحدة.
 */
class AnimeFlixProvider(private val prefs: SharedPreferences? = null) : MainAPI() {

    override var mainUrl = "https://animeflix.my"
    override var name = "AnimeFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val head get() = mapOf("User-Agent" to DriveChain.UA)

    private val TAG = "AnimeFlix"

    override val mainPage = mainPageOf(
        "" to "أحدث الإضافات",
        "/category/latest-release/" to "أحدث الإصدارات",
        "/category/ongoing/" to "مستمر",
        "/category/movies/" to "أفلام",
        "/category/genre/action/" to "أكشن",
        "/category/genre/fantasy/" to "فانتازيا",
        "/category/genre/comedy/" to "كوميديا",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            page <= 1 -> mainUrl + request.data
            request.data.isBlank() -> "$mainUrl/page/$page/"
            else -> mainUrl + request.data + "page/$page/"
        }
        val doc = app.get(url, headers = head).document
        val items = doc.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/?s=" + URLEncoder.encode(query, "UTF-8")
        val doc = app.get(url, headers = head).document
        val items = doc.select("a.card").mapNotNull { cardToSearch(it) }.distinctBy { it.url }
        return items.ifEmpty { null }
    }

    /** بطاقة `<a class="card" href="…/download-…">` في الصفحة الرئيسية/البحث. */
    private fun cardToSearch(card: Element): SearchResponse? {
        val href = card.attr("href").ifBlank { card.selectFirst("a")?.attr("href").orEmpty() }
        if (!href.contains("/download-")) return null
        val raw = card.selectFirst("span.card-t")?.text()?.trim()
            ?: card.attr("title").replace(Regex("^Download\\s+"), "").substringBefore("||").trim()
        if (raw.isBlank()) return null
        val poster = card.selectFirst("img")?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.ifBlank { null }
        return newTvSeriesSearchResponse(raw, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = head).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("main img")?.attr("src")?.ifBlank { null }
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("p.clamp2")?.text()?.ifBlank { null }

        // بطاقات الجودة: `<span class="q">720p</span>` + زرّ الأرشيف.
        val cards = doc.select("div.dl-card").mapNotNull { c ->
            val q = c.selectFirst("span.q")?.text()?.trim().orEmpty()
            val href = c.selectFirst("a[href*=archives]")?.attr("href") ?: return@mapNotNull null
            q to href
        }
        if (cards.isEmpty()) return null

        // إعداد «الجودة المفضّلة»: يُبقي بطاقات جودةٍ واحدة فقط، وإن لم توجد
        // يعود لكل البطاقات حتى لا يبقى العمل بلا روابط.
        val preferred = prefs?.getString(AnimeFlixSettingsBottomSheet.KEY_PREFERRED_Q, "all") ?: "all"
        val order = prefs?.getString(AnimeFlixSettingsBottomSheet.KEY_QUALITY_ORDER, "default") ?: "default"
        val showSeasons = pref(AnimeFlixSettingsBottomSheet.KEY_SHOW_SEASONS, true)
        val wantedCards = if (preferred == "all") {
            cards
        } else {
            val want = preferred.toIntOrNull() ?: 0
            cards.filter { DriveChain.heightOf(DriveChain.qualityOf(it.first)) == want }
                .ifEmpty { cards }
        }

        // رقم الحلقة → (الجودة، عنوان الصفّ، رابط `/getlink/`).
        val byEp = LinkedHashMap<Int, MutableList<Triple<Int, String, String>>>()
        var hasEpisodes = false
        for ((q, href) in wantedCards) {
            val quality = DriveChain.qualityOf(q)
            val body = try {
                app.get(href, headers = head).text
            } catch (e: Exception) {
                ""
            }
            val adoc = org.jsoup.Jsoup.parse(body)
            val links = adoc.select("a[href*=getlink]")
                .map { it.attr("href") to it.text().trim() }
                .distinctBy { it.first }
            if (links.isEmpty()) {
                byEp.getOrPut(0) { ArrayList() }
                    .add(Triple(quality, q.ifBlank { "Download" }, href))
            } else {
                hasEpisodes = true
                for ((g, t) in links) {
                    val n = DriveChain.epFromText(t) ?: 1
                    byEp.getOrPut(n) { ArrayList() }.add(Triple(quality, t, g))
                }
            }
        }
        if (byEp.isEmpty()) return null

        fun sorted(list: List<Triple<Int, String, String>>) = when (order) {
            "asc" -> list.sortedBy { it.first }
            else -> list.sortedByDescending { it.first }
        }

        val tags = doc.select("a[href*=category/genre]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        // عملٌ بحلقةٍ واحدة (فيلم أنمي في الغالب): مدخل مشاهدة واحد يحمل كل
        // الجودات، بدل أن تظهر الجودة الواحدة «حلقة» في مسلسل.
        if (byEp.size <= 1) {
            val flat = sorted(byEp.values.firstOrNull().orEmpty())
            if (flat.isEmpty()) return null
            val payload = flat.joinToString("\n") { (q, _, href) -> "$href\t\t$q" }
            Log.i(TAG, "load: فيلم — ${flat.size} جودة في مدخل واحد")
            return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        val eps = byEp.entries.sortedBy { it.key }.map { (n, list) ->
            val payload = sorted(list)
                .joinToString("\n") { (q, heading, href) -> "$href\t$heading\t$q" }
            newEpisode(payload) {
                this.name = if (showSeasons) "الموسم 1 · الحلقة $n" else "الحلقة $n"
                this.season = 1
                this.episode = n
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val lines = data.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return false

        // كل مدخلات الحلقة/الفيلم تُحلّ **معًا** لا واحدًا بعد الآخر: سلسلة
        // التحويلات (٢–٤ طلبات) كانت تُجمَع تسلسليًّا فتُضاعف زمن الظهور بعدد
        // الجودات. الآن الزمن = أبطأ سلسلة واحدة، لا مجموعها.
        val all = coroutineScope {
            lines.map { line ->
                async(Dispatchers.IO) {
                    val parts = line.split("\t")
                    val url = parts.getOrNull(0)?.trim().orEmpty()
                    val heading = parts.getOrNull(1)?.trim()?.ifBlank { null }
                    val quality = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                    if (url.isBlank()) return@async emptyList<DriveChain.Link>()
                    val got = try {
                        DriveChain.resolve(url, heading, quality)
                    } catch (e: Exception) {
                        Log.i(TAG, "resolve EX ${e.javaClass.simpleName}: ${e.message}")
                        emptyList()
                    }
                    if (got.isNotEmpty()) return@async got
                    // خطة بديلة: امسح الصفحة (أو وجهتها) مباشرةً عن أي رابط.
                    Log.i(TAG, "resolve فارغ — scanDirect ${url.take(70)}")
                    try {
                        DriveChain.scanDirect(url, heading, quality)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        Log.i(TAG, "loadLinks: ${all.size} رابطًا من ${lines.size} مدخلًا")
        if (all.isEmpty()) return false

        // لا يُحذف إلا التكرار **المطابق تمامًا** (رابط + سيرفر + جودة + ملاحظة
        // + نوع الاستعمال). أي رابط مختلف بجودةٍ أو سيرفر يظهر، مهما كان
        // إعداد «إظهار البدائل» — لأن المطلوب كل الروابط دون استثناء.
        val seen = HashSet<String>()
        val links = all.filter {
            seen.add(
                it.url + "|" + it.server + "|" + it.quality + "|" +
                    (it.note ?: "") + "|" + it.downloadOnly
            )
        }

        for (l in links) {
            callback(
                newExtractorLink(
                    source = name,
                    name = MediaProbe.label(
                        l.server,
                        MediaProbe.qualityLabel(DriveChain.heightOf(l.quality)),
                        l.bytes,
                        l.url,
                        null,
                        l.note,
                        if (l.downloadOnly) MediaProbe.Use.DOWNLOAD else MediaProbe.Use.WATCH,
                    ),
                    url = l.url,
                ) {
                    this.quality = l.quality
                    this.referer = l.referer ?: ""
                }
            )
        }
        return links.isNotEmpty()
    }

    private fun pref(key: String, def: Boolean): Boolean = prefs?.getBoolean(key, def) ?: def
}
