package com.shahidfree.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.FormatTag
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

private const val TAG = "ShahidFree"

class ShahidFreeProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "ShahidFree"
    override var mainUrl = "https://drama.shahidfree.site"
    override var lang = "ar"

    override val hasMainPage: Boolean
        get() = prefs?.getBoolean(ShahidFreeSettings.KEY_SHOW_HOME, true) != false
    override var hasQuickSearch = false

    // ★ إظهار وسم الصيغة في اسم السيرفر ([M3U8]/[MP4]) — يقرأ المفتاح عند كل
    //   بثّ، فتبديله في الإعدادات يظهر فوراً بلا إعادة فتح.
    private fun showFormatTag(): Boolean =
        prefs?.getBoolean(ShahidFreeSettings.KEY_FORMAT_TAG, true) != false

    /** عدد أقسام الرئيسية المعروضة — "all" أو رقم. يقرأ عند كل رسم للواجهة. */
    private fun homeRowCap(): Int? =
        prefs?.getString(ShahidFreeSettings.KEY_HOME_ROWS, "all")
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

    override val supportedTypes = setOf(TvType.TvSeries)

    /**
     * أقسام الواجهة: «الأحدث على الموقع» ثم التصنيفان الموجودان في القائمة
     * (مترجم/مدبلج). عنوان كل قسم يُقرأ من نص الصفحة الفعلية (h1) ويُعرَض
     * كما هو (تسميات عربية للموقع نفسه).
     */
    private fun mainSections(): List<Pair<String, String>> = listOf(
        "الأحدث" to "$mainUrl/category/feed",
        "مدبلج" to "$mainUrl/series/?genre_filter=dubbed",
        "مترجم" to "$mainUrl/series/?genre_filter=translated",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        return try {
            val rows = ArrayList<HomePageList>()
            for ((title, url) in mainSections()) {
                val items = cardsFrom(url)
                if (items.isEmpty()) {
                    Log.d(TAG, "getMainPage empty section title=$title")
                    continue
                }
                rows.add(HomePageList(title, items.take(10), true))
            }
            if (rows.isEmpty()) {
                Log.e(TAG, "getMainPage no rows found")
                null
            } else {
                // حدّ عدد الأقسام المعروضة — كل قِسْم صفٌّ تنتظره الواجهة قبل
                // أول رسم، فتقليله يسرّع فتح المصدر.
                val cap = homeRowCap()
                val capped = if (cap != null && rows.size > cap) rows.take(cap) else rows
                newHomePageResponse(capped, false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val result = try {
            val q = query.trim()
            if (q.isEmpty()) emptyList()
            else app.get(
                "$mainUrl/?s=${java.net.URLEncoder.encode(q, "UTF-8")}",
                referer = mainUrl
            ).document.select("a.card").mapNotNull { card -> cardToSearch(card) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED q=$query", e)
            null
        }
        return result
    }

    /** بطاقات قائمة عامة (أرشيف/تصنيف/بحث) — البنية `a.card > .poster img + h3`. */
    private suspend fun cardsFrom(url: String): List<SearchResponse> {
        val doc = app.get(url, referer = mainUrl).document
        val cards = doc.select("a.card").mapNotNull { card -> cardToSearch(card) }
        return cards
    }

    private fun cardToSearch(card: Element): SearchResponse? {
        val href = card.attr("href")
        if (!href.startsWith("/series/")) return null
        val absUrl = mainUrl + href
        val cover = card.selectFirst(".poster img")?.attr("src")?.takeIf { it.isNotBlank() }
        // العنوان داخل h3، ويزيل الموقع اللاحقة الثابتة «| دراما شو».
        val title = card.selectFirst("h3")?.text()?.trim()
            ?.removeSuffix("| دراما شو")
            ?.trim()
            ?: return null
        return newTvSeriesSearchResponse(title, absUrl, TvType.TvSeries) {
            this.posterUrl = cover
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val absoluteUrl = if (url.startsWith("http")) url else mainUrl + url
            val doc = app.get(absoluteUrl, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
            val cover = doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }

            // سيرفرات التشغيل من أزرار `.servers button[data-server]`. كل مسلسلٍ
            // حلقةٌ واحدة، فكل زرٍّ سيرفرُ بديلٍ لنفس الفيديو (vidhold غالباً).
            val servers = doc.select(".servers button[data-server]")
                .mapNotNull { it.attr("data-server").takeIf { s: String -> s.isNotBlank() } }
                .distinct()

            val epName = doc.selectFirst(".watch-section h2")?.text()?.trim()

            // حلقة واحدة: السيرفرات مفصولة بـ | قبل الجزء الأخير (رابط الصفحة).
            val epData = (servers + absoluteUrl).joinToString("|")
            val episode = newEpisode(epData) {
                name = epName ?: "الحلقة 1"
                episode = 1
            }

            val tags = doc.select("a[href*='/genre/']").mapNotNull { it.text().trim().takeIf(String::isNotEmpty) ?: null }

            newTvSeriesLoadResponse(title, absoluteUrl, TvType.TvSeries, listOf(episode)) {
                if (cover != null) {
                    posterUrl = cover
                    backgroundPosterUrl = cover
                }
                val plot = doc.selectFirst("meta[name='description']")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                if (plot != null) this.plot = plot
                if (tags.isNotEmpty()) this.tags = tags
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
            // data = "$servers|$absoluteUrl" — السيرفرات مفصولة بـ | قبل آخر جزء.
            val parts = data.split("|")
            val servers = parts.dropLast(1).map { it.trim() }.filter { it.isNotBlank() }
            if (servers.isEmpty()) {
                Log.e(TAG, "loadLinks no servers data=$data")
                return false
            }

            // ترتيبٌ ثابت: vidhold أولاً (بُثّ HLS موقّع يعمل)، ثم البقايا.
            val ordered = servers.sortedWith { a, b ->
                val rank: (String) -> Int = { u ->
                    when {
                        u.contains("vidhold") -> 0
                        u.contains("bysebuho") -> 1
                        else -> 2
                    }
                }
                rank(a) - rank(b)
            }

            val seen = HashSet<String>()
            var emitted = false
            for (src in ordered) {
                if (!seen.add(src)) continue
                when {
                    // vidhold: بُثّ HLS مباشرٌ بعد /e/ أو /d/. نَجلب master حيّ
                    // من embed ثم نخرج الرابط على الفور (tokens تتبدل كل طلب —
                    // لا نخزّن شيئاً، وكل فتحٍ يجلب master جديداً).
                    src.contains("vidhold.com/e/") || src.contains("vidhold.com/d/") -> {
                        val eid = src.substringAfterLast('/').substringBefore('?')
                        if (eid.isBlank()) continue
                        val master = "https://vidhold.com/api/stream/$eid"
                        val ref = "https://vidhold.com/${
                            src.substringAfter("vidhold.com/").substringBefore('/')
                        }/$eid"
                        val m3u8 = fetchMaster(master, ref)
                        if (m3u8.isNullOrBlank()) continue
                        val linkName = if (showFormatTag()) {
                            FormatTag.tagged("HLS", m3u8, ExtractorLinkType.M3U8)
                        } else "HLS"
                        Log.d(TAG, "emit vidhold id=$eid url=$m3u8")
                        callback(
                            newExtractorLink(
                                source = name,
                                name = linkName,
                                url = m3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = getQualityFromName("HLS")
                                this.referer = "https://vidhold.com/"
                                this.headers = mapOf("Referer" to "https://vidhold.com/")
                            }
                        )
                        emitted = true
                    }
                    // المرايا (vidaraa/bysebuho/rumble/voe…): إمّا بلا وصول مباشر
                    // من التطبيق (vidaraa 403، bysebuho خلف كابتشا+Fingerprint) أو
                    // صيغتُها غير مضمونة — نَتجاوزها بصمت ونكمل إلى التالي.
                    else -> Log.d(TAG, "loadLinks skip mirror src=$src")
                }
            }
            if (!emitted) {
                Log.e(TAG, "loadLinks nothing emitted servers=$servers")
                return false
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FATAL data=$data", e)
            false
        }
    }

    /** يجلب master vidhold الحي. **يلزم** Referer من vidhold وإلا 403. */
    private suspend fun fetchMaster(url: String, referer: String): String? {
        return try {
            val text = app.get(url, referer = referer).text
            if (text.isBlank() || !text.startsWith("#EXTM3U")) null else text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchMaster FAILED $url", e)
            null
        }
    }
}