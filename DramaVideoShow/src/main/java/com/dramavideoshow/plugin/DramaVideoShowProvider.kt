package com.dramavideoshow.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.FormatTag
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

private val mapper = ObjectMapper()

private const val TAG = "DramaVideoShow"

/**
 * استجابة `/api/v1/watch/{lang}/{series}/{ep}` — {url, duration}. مصدرُ الرابط
 * الأحطّ على صفحة الحلقة هو ما يسطّر `<source>` نفسه، والموقع لا يوجّه إلا إليه
 * في `getUrl()` (data-watch API احتياطٌ فقط) — لكننا نجلب من الـAPI مباشرةً:
 * جسم JSON أنظف من HTML وأقصر، ولا يحتاج تذكرة.
 */
private data class WatchResponse(
    val url: String? = null,
    val duration: Long? = null,
)

class DramaVideoShowProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "DramaVideoShow"
    override var mainUrl = "https://dramavideoshow.com"
    override var lang = "ar"
    override val hasMainPage: Boolean
        get() = prefs?.getBoolean(DramaVideoShowSettings.KEY_SHOW_HOME, true) != false
    override var hasQuickSearch = false

    // ★ إظهار وسم الصيغة في اسم السيرفر ([MP4]/[M3U8]/[DASH]) — يقرأ المفتاح
    //   عند كل بثّ، فتبديله في الإعدادات يظهر فوراً بلا إعادة فتح.
    private fun showFormatTag(): Boolean =
        prefs?.getBoolean(DramaVideoShowSettings.KEY_SHOW_LIST_LABEL, true) != false

    override val supportedTypes = setOf(TvType.TvSeries)

    /** عناوين أقسام الواجهة كما تظهر في الصفحة (h2) — تُلتقط ديناميكياً. */
    private val homeKeys = listOf(
        "أحدث الإصدارات",
        "مشاهدة حلقات المسلسلات",
        "مسلسلات هذه السنة",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        return try {
            val doc = app.get("$mainUrl/ar", referer = mainUrl).document
            val rows = ArrayList<HomePageList>()
            for (key in homeKeys) {
                val h2 = doc.selectFirst("h2:contains($key)")
                if (h2 == null) continue
                val section = h2.parent() ?: continue
                // البطاقة article.card → الرابط + العنوان + الغلاف + عدد الحلقات
                val items = section.select("article.card").mapNotNull { card ->
                    val a = card.selectFirst("a") ?: return@mapNotNull null
                    val href = a.attr("href")
                    if (!href.startsWith("/ar/drama/")) return@mapNotNull null
                    val absUrl = mainUrl + href
                    val cover = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                    val title = card.selectFirst(".ct")?.text()?.trim()
                        ?: return@mapNotNull null
                    val eps = card.selectFirst(".eps")?.text()?.let { t ->
                        Regex("""\d+""").find(t)?.value?.toIntOrNull()
                    }
                    newTvSeriesSearchResponse(title, absUrl, TvType.TvSeries) {
                        this.posterUrl = cover
                        if (eps != null) this.episodes = eps
                    }
                }
                if (items.isNotEmpty()) rows.add(HomePageList(key, items, true))
            }
            if (rows.isEmpty()) null else newHomePageResponse(rows, false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED", e)
            null
        }
    }

    /** آخر جزء من المسار، مفكوك الترميز — بُعيده معرّف المسلسل. */
    private fun slugFrom(url: String): String? {
        val tail = url.substringAfterLast('/').substringBefore('?')
        if (tail.isBlank()) return null
        return try {
            URLDecoder.decode(tail, "UTF-8")
        } catch (e: Exception) {
            tail
        }
    }

    private fun seriesIdFrom(url: String): String? {
        // معرّف المسلسل — آخر جزءٍ من الشريحة المدّية: .../slug-{id}/episode-N
        val tail = url.substringAfterLast('/').substringBefore('?') // episode-3
        val slugPart = if (tail.startsWith("episode-")) {
            url.substringBeforeLast("/").substringAfterLast('/')
        } else {
            tail
        }
        val dash = slugPart.lastIndexOf('-')
            .takeIf { it >= 0 }
            ?: return null
        val id = slugPart.substring(dash + 1)
        return id.takeIf { it.matches(Regex("""[0-9a-f]{24,24}""")) || id.length >= 20 }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val doc = app.get(
                "$mainUrl/ar/search?q=${java.net.URLEncoder.encode(q, "UTF-8")}",
                referer = mainUrl
            ).document
            doc.select("article.card").mapNotNull { card ->
                val a = card.selectFirst("a") ?: return@mapNotNull null
                val href = a.attr("href")
                if (!href.startsWith("/ar/drama/")) return@mapNotNull null
                val title = card.selectFirst(".ct")?.text()?.trim() ?: return@mapNotNull null
                val cover = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                val eps = card.selectFirst(".eps")?.text()?.let { t ->
                    Regex("""\d+""").find(t)?.value?.toIntOrNull()
                }
                newTvSeriesSearchResponse(title, mainUrl + href, TvType.TvSeries) {
                    this.posterUrl = cover
                    if (eps != null) this.episodes = eps
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED q=$query", e)
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val absoluteUrl = if (url.startsWith("http")) url else mainUrl + url
            val doc = app.get(absoluteUrl, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val cover = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
            // عدد الحلقات: من أزرار الحلقات (الأكثر ثباتاً) أو JSON-LD.
            val epsSet = Regex("""episode-(\d+)""")
                .findAll(doc.html())
                .map { it.groupValues[1].toIntOrNull() }
                .filterNotNull()
                .toSet()
            val count = epsSet.maxOrNull()
                ?: Regex(""""numberOfEpisodes":(\d+)""")
                    .find(doc.html())?.groupValues?.get(1)?.toIntOrNull()

            val base = url.substringBefore("/episode")
            val seriesId = seriesIdFrom(absoluteUrl)
            val episodes = if (count != null && count > 0) {
                (1..count).map { n ->
                    // الحلقة تُحدَّد عن API البث مباشرةً. `seriesId` في `data` كي
                    // لا نعيد فتح صفحة الحلقة في loadLinks ولا نستخرجها من HTML.
                    newEpisode("$base/episode-$n|${seriesId ?: ""}") {
                        episode = n
                        name = "الحلقة $n"
                    }
                }
            } else emptyList()
            if (episodes.isEmpty()) {
                Log.e(TAG, "load no episodes title=$title")
            }
            val tags = doc.select("meta[name='keywords']")?.firstOrNull()?.attr("content")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            newTvSeriesLoadResponse(title, absoluteUrl, TvType.TvSeries, episodes) {
                if (cover != null) {
                    posterUrl = cover
                    backgroundPosterUrl = cover
                }
                val desc = doc.selectFirst("meta[name='description']")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                if (desc != null) plot = desc
                // نص العنوان بلا "مسلسل … –" للصنف البحثي؟ نضعه كما هو
                this.tags = tags
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED $url", e)
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
            // data = "$baseUrl/episode-N|$seriesId" — الرابطُ نفسه، وينبوع الرابط
            // الـAPI معرّفه في الجزء الثاني.
            val parts = data.split("|")
            val rawUrl = parts[0].trim()
            if (rawUrl.isBlank()) {
                Log.e(TAG, "loadLinks empty url data=$data")
                return false
            }
            val seriesId = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            val ep = Regex("""episode-(\d+)""")
                .find(rawUrl)?.groupValues?.get(1)?.toIntOrNull()
            if (seriesId == null || ep == null) {
                Log.e(TAG, "loadLinks missing id/ep data=$data")
                return false
            }
            val watchUrl = "$mainUrl/api/v1/watch/ar/$seriesId/$ep"
            val watch = try {
                mapper.readValue(app.get(watchUrl, referer = mainUrl).text, WatchResponse::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "watch fetch FAILED $watchUrl", e)
                null
            }
            val streamUrl = watch?.url?.trim() ?: return false
            if (streamUrl.isEmpty()) {
                Log.e(TAG, "watch empty url series=$seriesId ep=$ep")
                return false
            }
            val absStream = abs(streamUrl)

            // رابطٌ واحد: `-sd.m3u8` (قِيس 2026-10-09 — لا master ولا تعدد جودات
            // ولا EXT-X-MEDIA). الجودة مسمّاةً من المسار، وإن غابت فالـ720p الافتراضي.
            // الاسم: الجودة ثم وسم الصيغة [M3U8] (التطبيق يعرض ExtractorLink.name
            // فقط في قائمة السيرفرات، فتُضاف الصيغة إلى الاسم لا إلى الرابط).
            val q = qOf(absStream) ?: "720p"
            // الاسم = الجودة المقيسة (720p/540p) ثم وسم الصيغة [M3U8]/[MP4]
            // كأسلوب DirectDrama/Dramadunyam. الوسم قابل للإخفاء من الإعدادات.
            val linkName = if (showFormatTag()) {
                FormatTag.tagged(q, absStream, ExtractorLinkType.M3U8)
            } else q
            Log.d(TAG, "emit series=$seriesId ep=$ep q=$q url=$absStream")

            callback(
                newExtractorLink(
                    source = name,
                    name = linkName,
                    url = absStream,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = getQualityFromName(q)
                    this.referer = mainUrl
                    this.headers = mapOf("Referer" to mainUrl)
                }
            )
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FATAL data=$data", e)
            false
        }
    }

    /** رابط مطلق — الـAPI يمنح مسارات نسبية والمشغّل لا يضيف mainUrl. */
    private fun abs(u: String): String = when {
        u.startsWith("http://") || u.startsWith("https://") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> mainUrl + u
        else -> "$mainUrl/$u"
    }

    /** الجودة الحقيقية من المسار (نمط رفقاء CrazyMaple مقيس): -ld=540، -sd=720. */
    private fun qOf(url: String): String? {
        return when {
            "-ld." in url || "_ld." in url -> "540p"
            "-sd." in url || "_sd." in url -> "720p"
            "-hd." in url || "_hd." in url -> "1080p"
            Regex("""_(\d{3,4})/""").containsMatchIn(url) ->
                Regex("""_(\d{3,4})/""").find(url)!!.groupValues[1] + "p"
            else -> null
        }
    }
}