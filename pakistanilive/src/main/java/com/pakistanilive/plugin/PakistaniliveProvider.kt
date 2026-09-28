package com.pakistanilive.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
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

    /**
     * ● نيوبايب + DASH محلي (المسار «الأساسي» — نفس أسلوب aryarabia).
     *   `YoutubeStreamExtractor.fetchPage()` يعطي روابط googlevideo مفكوكة مع
     *   نطاقات Initialization/Index لكل جودة، ونبني منها مانيفست DASH على
     *   `127.0.0.1` يعرض كل جودة مع أفضل صوت — فيتلقى يوتيوب طلبات Range
     *   رسمية. ينجح حيث فشل `loadExtractor` في بعض الشبكات.
     */
    private suspend fun resolveFromNewPipe(
        vid: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var produced = 0
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(watchUrl)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            val dur = runCatching { s.length }.getOrNull() ?: 0
            val durationSeconds = if (dur > 0) dur else 3600L

            val seenUrls = mutableSetOf<String>()

            // جودات الفيديو (video-only): وفّقها مع أفضل صوت
            val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val streamUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(streamUrl)) return@mapNotNull null
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    val label = if (height > 0) height.toString() else "video"
                    var mime = vs.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = PakiDashServer.mimeFromUrl(streamUrl, false)

                    val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                    val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                    PakiStreamInfo(streamUrl, mime ?: "video/mp4", height, label, initR, indexR)
                } catch (e: Exception) { null }
            }.distinctBy { it.height }

            val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                try {
                    val aUrl = asr.content ?: return@mapNotNull null
                    val bitrate = runCatching { asr.bitrate ?: 128000 }.getOrNull() ?: 128000
                    var mime = runCatching { asr.format?.mimeType }.getOrNull()
                    if (mime.isNullOrEmpty()) mime = PakiDashServer.mimeFromUrl(aUrl, true)

                    val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                    val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null
                    var rawLang = runCatching { asr.audioTrackId ?: "Default" }.getOrNull() ?: "Default"
                    if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                    PakiAudioInfo(aUrl, mime ?: "audio/mp4", bitrate, initR, indexR, rawLang.uppercase())
                } catch (e: Exception) { null }
            }.distinctBy { it.url }
            val audiosByLanguage = audioInfoList.groupBy { it.language }

            PakiDashServer.ensureStarted()

            if (audiosByLanguage.isNotEmpty()) {
                for (video in videoOnlyList) {
                    for ((lang, audios) in audiosByLanguage) {
                        val bestAudioForLang = if (video.mimeType.contains("webm")) {
                            audios.sortedWith(compareByDescending<PakiAudioInfo> { it.mimeType.contains("webm") }.thenByDescending { it.bitrate }).firstOrNull()
                        } else {
                            audios.sortedWith(compareByDescending<PakiAudioInfo> { it.mimeType.contains("mp4") }.thenByDescending { it.bitrate }).firstOrNull()
                        }
                        if (bestAudioForLang != null) {
                            val localLink = PakiDashServer.buildAndRegister(
                                video, listOf(bestAudioForLang), durationSeconds
                            )
                            if (localLink != null) {
                                callback(
                                    newExtractorLink(
                                        "Pakistanilive",
                                        "${video.label} (${bestAudioForLang.language})",
                                        localLink,
                                        type = ExtractorLinkType.DASH
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = video.height
                                    }
                                )
                                produced++
                            }
                        }
                    }
                }
            }

            // مقاطع مدمجة (muxed) كاحتياط إضافي — روابط مباشرة
            val muxedList = (s.videoStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val mUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(mUrl)) return@mapNotNull null
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    Triple(mUrl, if (height > 0) height.toString() else "video", height)
                } catch (e: Exception) { null }
            }
            muxedList.forEach { (mUrl, mLabel, mHeight) ->
                callback(
                    newExtractorLink("Pakistanilive", "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                        this.referer = mainUrl
                        this.quality = mHeight
                    }
                )
                produced++
            }

            Log.d(TAG, "$vid NewPipe DASH links=$produced qualities=${videoOnlyList.size}")
        } catch (e: Exception) {
            Log.w(TAG, "$vid NewPipe resolve failed: ${e.message}")
        }
        return produced
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val collected = mutableListOf<ExtractorLink>()
        val m = try {
            val html = app.get(data, headers = mapOf("User-Agent" to UA)).text
            Regex("""initCustomPlayer\("video1","([^"]+)",`(.*?)`\s*\)""", RegexOption.DOT_MATCHES_ALL)
                .find(html)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks fetch error: ${e.message}")
            null
        } ?: return false

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

        // المسار الأساسي: NewPipe + DASH محلي (يعمل حيث يفشل loadExtractor).
        // خلافاً للأصل نُبقي loadExtractor احتياطاً (لا تحلّ محل الإعدادات).
        val sink: (ExtractorLink) -> Unit = { link -> collected.add(link); Unit }
        var links = resolveFromNewPipe(ytId, subtitleCallback, sink)

        if (links == 0) {
            loadExtractor("https://www.youtube.com/watch?v=$ytId", "https://www.youtube.com/", subtitleCallback) { link ->
                sink(link); links++
            }
        }

        if (collected.isEmpty()) return false
        collected.forEach { callback(it) }
        return true
    }
}