package com.pakistanilive.plugin

import cloudstreamshared.FormatTag
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

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

    /** يستخرج كوديك حقيقي من mimeType («video/mp4; codecs="avc1.640028"») إن وُجد. */
    private fun codecFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val i = mime.indexOf("codecs=")
        if (i < 0) return null
        val v = mime.substring(i + "codecs=".length).trim().removeSurrounding("\"")
        return if (v.isNotBlank()) v else null
    }

    // ---------- خيارات التشغيل (مأخوذة من aryarabia) ----------
    private fun playbackMode(): String =
        prefs?.getString(PakistaniliveSettingsBottomSheet.KEY_PLAYBACK_MODE, "newpipe") ?: "newpipe"

    private fun qualityMode(): String =
        prefs?.getString(PakistaniliveSettingsBottomSheet.KEY_MAX_QUALITY, "all") ?: "all"

    private fun orderMode(): String =
        prefs?.getString(PakistaniliveSettingsBottomSheet.KEY_QUALITY_ORDER, "default") ?: "default"

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
    /**
     * بحث: بحث WordPress `/?s=` يتجاهل النص ويعيد نفس جدار الفئات كله
     * (204 بطاقة لكل استعلام) — لا نستخدمه. بدل ذلك نستعلم REST API
     * `wp/v2/categories` (كل فئات الموقع باسمها وعدد حلقاتها ورابطها) ثم
     * نطابق النص في الإضافة. الفئات كلها هنا — 200+ مسلسل باسم عربي حقيقي
     * (مثل «مسلسل باكستاني دمى من الطين - Mitti De Baway») — فيعود البحث
     * نتائج صحيحة مطابقةً فقط.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val normQ = normForSearch(q)
        if (normQ.isBlank()) return emptyList()

        val out = ArrayList<SearchResponse>()
        // الفئات 200+ على 3 صفحات (per_page=100) — نمرّها كلها ونطابق.
        for (page in 1..3) {
            val res = try {
                app.get("$mainUrl/wp-json/wp/v2/categories?per_page=100&page=$page&_fields=name,count,link")
            } catch (_: Exception) { break }
            val arr = try {
                org.json.JSONArray(res.text.trim().removePrefix("﻿"))
            } catch (_: Exception) { break }
            if (arr.length() == 0) break

            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val name = c.optString("name", "").trim().ifBlank { continue }
                val link = c.optString("link", "").ifBlank { continue }
                if (!link.contains("/category/")) continue
                if (!normForSearch(name).contains(normQ)) continue
                out.add(newTvSeriesSearchResponse(name, link) {
                    this.posterUrl = null
                })
            }
        }
        return out.distinctBy { it.url }
    }

    /**
     * توحيد مطابق لروح مدلول البحث في aryarabia: بلا تشكيل، والهمزات
     * والألفات والألف المقصورة موحّدة، والتاء المربوطة كالهاء، والحرف
     * صغير. يجعل «الانين» تجد «الأنين» و«اني» تجد «أناني».
     */
    private fun normForSearch(s: String): String {
        val sb = StringBuilder()
        for (c in s.trim()) {
            when (c) {
                in 'ً'..'ْ', 'ـ', 'ٰ', '،', '|', '-', '_', '(', ')', '[', ']', '"', '\'', ':' -> {}
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ء' -> {}
                ' ' -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString().trim()
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

            // جودات الفيديو (video-only): نُبقي كل تنسيقٍ مميز — ارتفاعه وكوديكه —
            // (mp4/avc وwebm/vp9 وحتى av1) بدل طي كل ارتفاع إلى تنسيقٍ واحد
            // (كان `distinctBy{it.height}` يُسقط نسخ الكوديك المكررة في الصوتيات،
            // ويظهر في اللاعب جزء من الجودات المتاحة فقط).
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

                    PakiStreamInfo(streamUrl, mime, height, label, initR, indexR, codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

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

                    PakiAudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase(), codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }
            val audiosByLanguage = audioInfoList.groupBy { it.language }

            PakiDashServer.ensureStarted()

            // إعداد «الجودات»: الأعلى فقط عند اختيار high (نُبقي نسخة الكوديك
            // العليا أيضاً لئلا يختفي التنسيق شبه المتاح للاعب).
            val effectiveVideos = if (qualityMode() == "high") {
                val top = videoOnlyList.maxByOrNull { it.height }
                if (top != null) videoOnlyList.filter { it.height == top.height } else videoOnlyList
            } else videoOnlyList

            // كل تنسيق فيديو يُبث — بلا استثناء — مُقترناً بأفضل صوتٍ متاحٍ لغةً
            // ومواءمةً للكوديك. عند غياب الصوت نُبث الفيديو وحده (مانيفست بلا
            // AdaptationSet صوتي) بدل أن يسقط تنسيق الفيديو بالكامل.
            for (video in effectiveVideos) {
                val bestAudio = if (audiosByLanguage.isNotEmpty()) {
                    // أول لغة، مع مَن يطابق أسرة كوديك الفيديو (mp4↔mp4، webm↔webm)
                    val lang = audiosByLanguage.keys.firstOrNull()
                    audiosByLanguage[lang]?.let { audios ->
                        val family = if (video.mimeType.contains("webm")) { a: PakiAudioInfo ->
                            a.mimeType.contains("webm")
                        } else { a: PakiAudioInfo ->
                            a.mimeType.contains("mp4")
                        }
                        audios.sortedWith(
                            compareByDescending<PakiAudioInfo>(family).thenByDescending { it.bitrate }
                        ).firstOrNull()
                    }
                } else null

                val label = if (bestAudio != null && audiosByLanguage.size > 1)
                    "${richLabel(video.height, video.url, video.mimeType)} (${bestAudio.language})"
                else richLabel(video.height, video.url, video.mimeType)

                val localLink = PakiDashServer.buildAndRegister(
                    video, if (bestAudio != null) listOf(bestAudio) else emptyList(),
                    durationSeconds
                )
                if (localLink != null) {
                    callback(
                        newExtractorLink(
                            "Pakistanilive", FormatTag.tagged(label, localLink, ExtractorLinkType.DASH),
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

            Log.d(TAG, "$vid NewPipe DASH links=$produced formats=${effectiveVideos.size}")
        } catch (e: Exception) {
            Log.w(TAG, "$vid NewPipe resolve failed: ${e.message}")
        }
        return produced
    }

    // ---------- المسار «مباشر» (روابط HTML) مأخوذ من aryarabia ----------
    private fun ytPlayerResponse(html: String): JSONObject? {
        val markers = listOf(
            "var ytInitialPlayerResponse = ",
            "window[\"ytInitialPlayerResponse\"] = ",
            "\"ytInitialPlayerResponse\"] = ",
            "ytInitialPlayerResponse = "
        )
        for (m in markers) {
            val idx = html.indexOf(m)
            if (idx < 0) continue
            val start = idx + m.length
            if (start >= html.length || html[start] != '{') continue
            var depth = 0
            var i = start
            var inStr = false
            var esc = false
            while (i < html.length) {
                val c = html[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                return try {
                                    JSONObject(html.substring(start, i + 1))
                                } catch (_: Exception) { null }
                            }
                        }
                    }
                }
                i++
            }
        }
        return null
    }

    private fun randomCpn(): String {
        val chars = "0123456789abcdef"
        val r = java.util.Random()
        return (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun qualityLabelOf(vs: org.schabi.newpipe.extractor.stream.VideoStream): String {
        val height = runCatching { vs.height }.getOrNull() ?: 0
        return if (height > 0) height.toString() else "video"
    }

    /** كوديك الفيديو مختصراً للعرض: avc1→H.264، vp9→VP9، av01→AV1، … */
    private fun codecTag(mime: String?): String {
        val m = mime.orEmpty()
        return when {
            m.contains("av01") -> "AV1"
            m.contains("vp09") || m.contains("/vp9") -> "VP9"
            m.contains("avc1") || m.contains("/avc") -> "H.264"
            m.contains("webm") -> "VP9"
            m.contains("mp4") -> "H.264"
            else -> ""
        }
    }

    /** حجم ملف الفيديو من معامل `clen` في رابط googlevideo (بالبايت). */
    private fun byteSizeOf(url: String): Long {
        if (url.isEmpty()) return 0
        val i = url.indexOf("clen=")
        if (i < 0) return 0
        val after = url.substring(i + 5)
        val j = after.indexOf('&')
        val num = if (j >= 0) after.substring(0, j) else after
        return runCatching { num.toLongOrNull() ?: 0L }.getOrDefault(0L)
    }

    /** «360 • 62MB (H.264)» — تسمية تُفرّق الجودات في منظار القائمة. */
    private fun richLabel(height: Int, url: String, mime: String?): String {
        val h = if (height > 0) height.toString() else "auto"
        val bytes = byteSizeOf(url)
        val mb = if (bytes > 0) "• ${"%.1f".format(bytes / 1048576.0)}MB" else ""
        val tag = codecTag(mime)
        val t = if (tag.isNotEmpty()) " ($tag)" else ""
        return "$h$mb$t"
    }

    private fun redirectHost(url: String): String {
        return try {
            val u = java.net.URI(url)
            if (u.host?.endsWith(".googlevideo.com") == true) {
                val query = u.rawQuery
                val base = "https://redirector.googlevideo.com${u.rawPath}"
                if (!query.isNullOrBlank()) "$base?$query" else base
            } else url
        } catch (_: Exception) { url }
    }

    /**
     * مسار HTML المباشر (نمط aryarabia): نجلب صفحة watch، نستخرج
     * `ytInitialPlayerResponse`، ونمرّر الروابط. تبثّ نسخةً بالتواصل مع
     * `cpn`، ونسخةٍ بديلة عبر redirector. إن لم يعطِ روابط url جاهزة يعطي
     * ABR المتكيّف (`serverAbrStreamingUrl`) بكل جودة حرفياً.
     */
    private suspend fun resolveFromHtml(
        vid: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var produced = 0
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        try {
            val res = app.get(
                watchUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept-Language" to "ar",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            )
            val pr = ytPlayerResponse(res.text) ?: return 0
            val status = pr.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") {
                Log.w(TAG, "$vid playableStatus=$status (${pr.optJSONObject("playabilityStatus")?.optString("reason")})")
                return 0
            }
            fun emit(url: String, name: String, quality: Int, headers: Map<String, String> = mapOf()) {
                if (url.isBlank()) return
                val withCpn = if (url.contains(".googlevideo.com")) {
                    if (url.contains("cpn=")) url
                    else url + (if (url.contains("?")) "&" else "?") + "cpn=" + randomCpn()
                } else url
                val link = ExtractorLink(
                    "Pakistanilive", name, withCpn, watchUrl, quality, headers,
                    null, ExtractorLinkType.VIDEO, emptyList<AudioFile>()
                )
                produced++
                callback(link)
                if (withCpn.contains(".googlevideo.com") && !withCpn.contains("redirector.googlevideo.com")) {
                    val redirected = redirectHost(withCpn)
                    if (redirected != withCpn) {
                        val link2 = ExtractorLink(
                            "Pakistanilive", "$name · redirect", redirected, watchUrl, quality,
                            headers, null, ExtractorLinkType.VIDEO, emptyList<AudioFile>()
                        )
                        produced++
                        callback(link2)
                    }
                }
            }
            val sd = pr.optJSONObject("streamingData") ?: return 0
            val arr = ArrayList<JSONObject>()
            sd.optJSONArray("formats")?.let { for (i in 0 until it.length()) arr.add(it.getJSONObject(i)) }
            sd.optJSONArray("adaptiveFormats")?.let { for (i in 0 until it.length()) arr.add(it.getJSONObject(i)) }

            val abr = sd.optString("serverAbrStreamingUrl")
            val abrHeaders = mapOf("Referer" to "https://www.youtube.com/")

            var emittedReal = 0
            for (f in arr) {
                val url = f.optString("url")
                if (url.isBlank()) continue
                val q = f.optString("qualityLabel")
                val name = "Pakistanilive ${if (q.isNotBlank()) q else f.optInt("itag").toString()}"
                emit(url, name, f.optInt("itag"), abrHeaders)
                emittedReal++
            }

            val labels = LinkedHashMap<String, String>()
            for (f in arr) {
                val q = f.optString("qualityLabel")
                if (q.isBlank()) continue
                labels[q] = q
            }
            val ordered = labels.keys.sortedByDescending { label ->
                Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            if (abr.isNotBlank() && emittedReal == 0) {
                if (ordered.isEmpty()) {
                    emit(abr, "Pakistanilive (ABR)", 0, abrHeaders)
                } else {
                    for (label in ordered) {
                        emit(abr, "Pakistanilive $label", 0, abrHeaders)
                    }
                }
            }
            Log.d(TAG, "$vid resolveFromHtml: real=$emittedReal abr-labels=${ordered.size}")
            return produced
        } catch (e: Exception) {
            Log.w(TAG, "$vid resolveFromHtml failed: ${e.message}")
            return produced
        }
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
            // ملاحظة: تُرقّم القناة اللاعب بحسب الحلقة («video1»، «video5»، …)،
            // فالقبض على video1 فقط يُسقط مسلسلاتٍ عدة — نطابق أي رقم.
            Regex("""initCustomPlayer\("video\d+","([^"]+)",`(.*?)`\s*\)""", RegexOption.DOT_MATCHES_ALL)
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
                runCatching { subtitleCallback(newSubtitleFile(subLangLabel("العربية"), url)) }
            }
        }

        val watchUrl = "https://www.youtube.com/watch?v=$ytId"
        val mode = playbackMode()

        // المسار الابتدائي حسب الإعداد: newpipe (افتراضي) / direct / extractor.
        // البقية تُجرَّب احتياطياً ما لم تُنتج روابط (نمط aryarabia).
        val orderedPrimary = when (mode) {
            "extractor" -> 1
            "direct" -> 2
            else -> 0
        }

        val sink: (ExtractorLink) -> Unit = { link -> collected.add(link); Unit }
        var links = 0

        // ★ روابط ما وراء المسارات تجمع في قائمة واحدة ويُبثّ ترتيبها حسب
        //   KEY_QUALITY_ORDER مع بقية الخيارات. الدوال المساندة ترجع عددها.
        suspend fun runFirst() {
            when (orderedPrimary) {
                1 -> {
                    loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
                        sink(link); links++
                    }
                }
                2 -> { links += resolveFromHtml(ytId, subtitleCallback, sink) }
                else -> { links += resolveFromNewPipe(ytId, subtitleCallback, sink) }
            }
        }
        runFirst()

        if (links == 0 && orderedPrimary != 0) {
            links += resolveFromNewPipe(ytId, subtitleCallback, sink)
        }
        if (links == 0 && orderedPrimary != 1) {
            loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
                sink(link); links++
            }
        }
        if (links == 0 && orderedPrimary != 2) {
            links += resolveFromHtml(ytId, subtitleCallback, sink)
        }

        if (collected.isEmpty()) return false

        // ★ البث النهائي: «default» = نفس ترتيب اليوم حرفياً؛ asc/desc يعيدان
        //   الترتيب فقط (فرز مستقر، بلا حذف أو تكرار).
        val order = orderMode()
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
        Log.d(TAG, "loadLinks $ytId mode=$mode links=$links")
        return true
    }
}