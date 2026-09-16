package com.threesk.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import android.util.Log

class ThreeSk : MainAPI() {
    override var mainUrl = "https://3iskk.xyz"
    override var name = "قصة عشق"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    private companion object {
        const val TAG = "ThreeSk"
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  getMainPage — يجلب الصفحة الرئيسية ويحلل كل الأقسام من HTML مباشرة
    //  (كما في re-3arabi — لا بناء روابط صفحة بصفحة، لا ترميز عربي في المضيف)
    // ═════════════════════════════════════════════════════════════════════════
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            Log.d(TAG, "getMainPage called with name=${request.name} data=${request.data}")
            val url = if (request.data.isNotBlank()) "$mainUrl${request.data}" else mainUrl
            Log.d(TAG, "getMainPage fetching: $url")
            val document = app.get(url).document
            val all = ArrayList<HomePageList>()

            // طريقة 1: تحليل الأقسام المفهرسة (sections)
            document.select("section.home-items-sec").forEach { section ->
                val title = section.selectFirst(".sec-title")?.text() ?: return@forEach
                val items = section.select("li.type_item_box a.type_item, li.type_item_wide_box a.type_item_wide")
                    .mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) {
                    all.add(HomePageList(title, items))
                }
            }

            // طريقة 2: إن لم تُوجد أقسام مفهرسة، نحلل كل البطاقات مباشرة
            // (لا بناء روابط، لا زيارات لكل رابط — تحليل واحد سريع)
            if (all.isEmpty()) {
                val cards = document.select("li.type_item_box a.type_item, li.type_item_wide_box a.type_item_wide")
                    .mapNotNull { it.toSearchResponse() }
                if (cards.isNotEmpty()) {
                    all.add(HomePageList(request.name.ifBlank { "قصة عشق" }, cards))
                }
            }

            Log.d(TAG, "getMainPage: ${all.size} sections, ${all.sumOf { it.list.size }} total cards")
            if (all.isEmpty()) null
            else newHomePageResponse(all)
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage EXCEPTION: ${e.message}", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  toSearchResponse — يحوّل بطاقة HTML إلى نتيجة بحث
    // ═════════════════════════════════════════════════════════════════════════
    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        val title = this.attr("title").ifBlank { null }
            ?: this.selectFirst(".item_title")?.text()
            ?: this.text().trim()
        if (title.isBlank()) return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-image").ifBlank { it.attr("src") }
        }
        return when {
            href.contains("/tvshows/") -> newTvSeriesSearchResponse(title, href) { this.posterUrl = posterUrl }
            href.contains("/movies/") -> newMovieSearchResponse(title, href) { this.posterUrl = posterUrl }
            href.contains("/episodes/") -> {
                val seriesTitle = title.substringBefore(" الحلقة").trim()
                newTvSeriesSearchResponse(seriesTitle.ifBlank { title }, href) { this.posterUrl = posterUrl }
            }
            else -> newTvSeriesSearchResponse(title, href) { this.posterUrl = posterUrl }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  search
    // ═════════════════════════════════════════════════════════════════════════
    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val url = "$mainUrl/search/$query/"
            Log.d(TAG, "search: $url")
            val document = app.get(url).document
            document.select("li.type_item_box a.type_item").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            Log.e(TAG, "search EXCEPTION: ${e.message}", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  load — تفاصيل المسلسل + الحلقات
    // ═════════════════════════════════════════════════════════════════════════
    override suspend fun load(url: String): LoadResponse? {
        return try {
            Log.d(TAG, "load: $url")
            val doc = app.get(url).document
            val title = doc.selectFirst("h1.title, .title")?.text()
                ?: doc.title().substringBefore("موقع").trim()
            val poster = doc.selectFirst(".poster-wrapper img")?.let {
                it.attr("data-image").ifBlank { it.attr("src") }
            } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            val description = doc.selectFirst(".description")?.text()

            val episodes = mutableListOf<Episode>()

            // تحليل المواسم والحلقات
            doc.select(".seasons-wrapper .seasons-selection ul li[data-value]").forEach { seasonLi ->
                val seasonNum = seasonLi.attr("data-value").toIntOrNull() ?: return@forEach
                doc.select("#season-num-$seasonNum a.ep-num").forEach { a ->
                    val epUrl = a.attr("href")
                    val epNum = a.attr("data-ep-num").toIntOrNull()
                        ?: Regex("episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    val epTitle = a.selectFirst(".cl_srt")?.text()
                        ?: a.attr("title").ifBlank { null }
                        ?: epNum?.let { "الحلقة $it" }
                    if (epUrl.isNotBlank())
                        episodes.add(newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        })
                }
            }

            // إن لم تُوجد حلقات بالمواسم، نبحث عن حلقات مباشرة
            if (episodes.isEmpty()) {
                doc.select("a.ep-num[href*=episodes]").forEach { a ->
                    val epUrl = a.attr("href")
                    val epNum = a.attr("data-ep-num").toIntOrNull()
                        ?: Regex("episode-(\\d+)").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                    if (epUrl.isNotBlank())
                        episodes.add(newEpisode(epUrl) {
                            this.name = epNum?.let { "الحلقة $it" }
                            this.season = 1
                            this.episode = epNum
                        })
                }
            }

            Log.d(TAG, "load: title=$title episodes=${episodes.size}")
            if (episodes.isEmpty()) null
            else newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } catch (e: Exception) {
            Log.e(TAG, "load EXCEPTION: ${e.message}", e)
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  loadLinks — سلسلة POST الثلاثية → خوادم متعددة
    // ═════════════════════════════════════════════════════════════════════════
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // نفس أسلوب re-3arabi: سلسلة POST مع تمرير referer في كل طلب
        // (referer الصحيح هو ما يجعل موقع Cloudflare يعيد iframe حقيقي بدل صفحة challenge)
        return try {
            Log.d(TAG, "loadLinks: $data")
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "*/*"
            )

            // الخطوة 0: صفحة الحلقة → form + news field
            val r0 = app.get(data, headers = headers)
            val soup0 = r0.document
            var watchForm = soup0.selectFirst("button.single-watch-btn")?.parent()
            if (watchForm == null) {
                watchForm = soup0.select("form").firstOrNull {
                    it.attr("action").contains("3isk") || it.attr("action").contains("aa.3isk")
                }
            }
            val formAction = watchForm?.attr("action") ?: run {
                Log.e(TAG, "loadLinks: no watch form"); return false
            }
            val formData = (watchForm?.select("input[type=hidden]")
                ?.associate { it.attr("name") to it.attr("value") } ?: emptyMap()).toMutableMap()
            val watchBtn = soup0.selectFirst("button.single-watch-btn")
            if (watchBtn != null && watchBtn.attr("name").isNotBlank())
                formData[watchBtn.attr("name")] = watchBtn.attr("value")
            Log.d(TAG, "loadLinks STEP1: POST $formAction fields=${formData.size}")

            // الخطوة 1: POST → صفحة myUrl + myInput
            val r1 = app.post(formAction, data = formData, referer = data, headers = headers)
            val mMyurl = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(r1.text)
            val mNews = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(r1.text)
            if (mMyurl == null || mNews == null) {
                Log.e(TAG, "loadLinks: myUrl/myInput not found, len=${r1.text.length}")
                return false
            }
            val nextPost = mMyurl.groupValues[1]
            val newsVal = mNews.groupValues[1]
            Log.d(TAG, "loadLinks STEP2: POST $nextPost")

            // الخطوة 2: POST → صفحة الـ embed (iframe ukrcdn بداخلها)
            val r2 = app.post(
                nextPost,
                data = mapOf("news" to newsVal, "u" to "", "submit" to "submit"),
                referer = r1.url,
                headers = headers
            )
            val iframesOnR2 = r2.document.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
            if (iframesOnR2.isEmpty()) {
                Log.e(TAG, "loadLinks: no iframe after POST2 (len=${r2.text.length})")
                // fallback: embed عبر WordPress بحيث يجلب الموقع الرسمي - out
                return false
            }
            val baseIframe = iframesOnR2[0]
            Log.d(TAG, "loadLinks STEP3: base iframe=$baseIframe")

            // الحصول على postId/type من الـ embed للاستكشاف عبر embed/1..N
            val embedM = Regex("""(?:https?://[\w.-]+)?/embed/(\d+)/(\d+)/(\d+)/""").find(baseIframe)
            val postId = embedM?.groupValues?.get(2)
            val typeId = embedM?.groupValues?.get(3)
            val trailing = embedM?.groupValues?.get(3)
                ?.let { "/$it/" }

            var emitted = 0
            val seenHost = mutableSetOf<String>()
            val serversToProbe = if (postId != null && typeId != null) 1..8 else 1..1
            for (t in serversToProbe) {
                val embedUrl = if (postId != null && typeId != null) {
                    "$mainUrl/embed/$t/$postId/$typeId/"
                } else {
                    baseIframe
                }
                try {
                    // referer الموسّع = صفحة الـ r2 التي أنتجتها
                    val embedDoc = app.get(embedUrl, referer = r2.url, headers = headers).document
                    val playerUrl = embedDoc.selectFirst("iframe")?.attr("src")
                        ?: Regex("""(?:data-src|src)\s*=\s*["']([^"']+)["']""").find(embedDoc.html())
                            ?.groupValues?.get(1)
                        ?: continue
                    val host = try { java.net.URL(playerUrl).host } catch (_: Exception) { continue }
                    if (host.isBlank() || !seenHost.add(host)) continue

                    Log.d(TAG, "loadLinks server $t: host=$host")

                    when {
                        host.contains("ukrcdn") -> {
                            emitted += resolveUkrcdn(playerUrl, r2.url, callback)
                        }
                        else -> {
                            val m3 = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(embedDoc.html())
                            if (m3 != null) {
                                callback(newExtractorLink(
                                    name,
                                    "سيرفر ${seenHost.size}",
                                    m3.groupValues[0],
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = host
                                    this.quality = -1
                                    this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                })
                                emitted++
                            } else {
                                callback(newExtractorLink(
                                    name,
                                    "سيرفر ${seenHost.size} (embed)",
                                    playerUrl,
                                    ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = host
                                    this.quality = -1
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "Referer" to host,
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                                    )
                                })
                                emitted++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "loadLinks server $t failed: ${e.message}")
                }
            }
            Log.d(TAG, "loadLinks: emitted $emitted links")
            emitted > 0
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks EXCEPTION: ${e.message}", e)
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  ukrcdn resolver — يحول رابط ukrcdn إلى m3u8 مباشر
    // ═════════════════════════════════════════════════════════════════════════
    private suspend fun resolveUkrcdn(
        embedUrl: String,
        refererFromPrev: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        return try {
            val uuid = Regex("""/e/([a-f0-9-]{36})""").find(embedUrl)?.groupValues?.get(1) ?: return 0

            // نقرأ صفحة embed (referer = صفحة الـ r2 التي أنتجتها) لنحصل على التوكين g=
            val embedDoc = app.get(embedUrl, referer = refererFromPrev)
            val gToken = Regex("""g\s*=\s*["']?([^"'\s&]+)["']?""").find(embedDoc.text)?.groupValues?.get(1)
                ?: return 0
            Log.d(TAG, "resolveUkrcdn g=$gToken")

            val apiUrl = "https://ukrcdn.club/api/videos/$uuid/playback?g=$gToken"
            val apiResp = app.get(apiUrl, referer = "https://ukrcdn.club/").text
            // {"url":"https:\/\/s4.ukrcdn.xyz\/hls\/{uuid}\/master.m3u8?token=...&expires=..."}
            val videoUrl = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)+)"""").find(apiResp)
                ?.groupValues?.get(1)?.replace("\\/", "/") ?: return 0

            if (videoUrl.isBlank()) return 0
            Log.d(TAG, "resolveUkrcdn: $videoUrl")
            callback(newExtractorLink(
                name,
                "ukrcdn (مباشر)",
                videoUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "https://ukrcdn.club/"
                this.quality = -1
                this.headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            })
            1
        } catch (e: Exception) {
            Log.w(TAG, "resolveUkrcdn failed: ${e.message}")
            0
        }
    }
}
