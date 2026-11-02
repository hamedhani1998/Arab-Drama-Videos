package com.kisskhba.plugin

import android.util.Log
import cloudstreamshared.MediaProbe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * KissKH — نسخة `kisskh.ba` (قالب WordPress + Angular).
 *
 * **ليست مرآة** لـ`kisskh.bz` ولا `kisskh.la`: لكلٍّ منها API مختلف تمامًا.
 * هذا الموقع يبني صفحاته على ووردبريس، وله مساحة REST خاصّة بالقالب:
 *  - `/wp-json/kisskh/v1/explore?page=N&order=latest_update&type=…` للفهرس،
 *  - `/wp-json/kisskh/v1/search?q=…&page=N` للبحث.
 *
 * وفيه تُقرأ الروابط من صفحة الحلقة نفسها: بيرق `data-kkh-servers` (JSON)
 * و`data-kkh-downloads` (JSON)، ثم يُفكّ كل سيرفرٍ على حِدة:
 *  - `vidbasic.top/embed/…` ← `3rdplayer.html` ← `data-name=crypto` ← AES-256-CBC
 *    ([Vidbasic]) ← رابط m3u8 + رابط الترجمة،
 *  - `megaplay` وغيرها ← استخراج رابط الوسائط من صفحة التضمين،
 *  - وإن لم يُستخرَج شيء **تُعرض صفحة التضمين نفسها** فلا يُخفي سيرفرًا.
 *
 * ملاحظة الترجمة (مهمّة): الخادم يُعيد ملفّ `.vtt` داخل مقاطعَه نصًّا مُشفّرًا
 * سطرًا سطرًا، ولا يفكّه إلا سكربت الموقع (`cryptoService.b2`). ولأن
 * `newSubtitleFile` لا يقبل إلا رابطًا (لا نصًّا جاهزًا) نعرض **رابط الترجمة
 * الأصلي** كما هو — يظهر في القائمة، لكن قارئ الترجمة في CloudStream لن يفكّ
 * محتواه.
 */
class KissKHBAProvider : MainAPI() {

    override var mainUrl = "https://kisskh.ba"
    override var name = "KissKH.ba"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes =
        setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private val TAG = "KissKHBA"

    private val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val head get() = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "order=latest_update" to "الأحدث",
        "type=Movie&order=latest_update" to "أفلام",
        "type=TVSeries&order=latest_update" to "مسلسلات",
        "type=Anime&order=latest_update" to "أنمي",
        "order=views" to "الأكثر مشاهدة",
    )

    // -------------------------------------------------------------- الفهرس

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val params = request.data.ifBlank { "order=latest_update" }
        val url = "$mainUrl/wp-json/kisskh/v1/explore?page=$page&$params"
        var hasNext = false
        val items = try {
            val o = JSONObject(app.get(url, headers = head).text)
            hasNext = o.optBoolean("has_more", false)
            val arr = o.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { cardToSearch(arr.optJSONObject(it)) }
        } catch (e: Exception) {
            Log.i(TAG, "getMainPage FAIL ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
        return newHomePageResponse(request.name, items, hasNext || items.size >= PAGE_SIZE)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/wp-json/kisskh/v1/search" +
            "?q=${URLEncoder.encode(query, "UTF-8")}&page=1"
        return try {
            val o = JSONObject(app.get(url, headers = head).text)
            val arr = o.optJSONArray("items") ?: JSONArray()
            (0 until arr.length()).mapNotNull { cardToSearch(arr.optJSONObject(it)) }
                .ifEmpty { null }
        } catch (e: Exception) {
            Log.i(TAG, "search FAIL ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /**
     * بطاقة من REST. نوع العمل (`type`) يأتي مع البطاقة نفسها، فنمرّره إلى
     * [load] بوسم `?_t=` كي لا يظهر فيلمٌ كمسلسل — والموقع نفسه قد يسمّي
     * الفيلم متعدّد الأجزاء `Movie` بعدّة حلقات.
     */
    private fun cardToSearch(o: JSONObject?): SearchResponse? {
        if (o == null) return null
        val title = o.optString("title").trim().ifBlank { return null }
        val link = o.optString("url").trim().ifBlank { return null }
        val kind = o.optString("type").trim()
        val poster = o.optString("thumb").trim().ifBlank { null }
        val url = withHint(link, kind)
        return when (kind.lowercase(Locale.US)) {
            "movie" -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
            else -> newTvSeriesSearchResponse(
                title, url,
                if (kind.equals("Anime", true)) TvType.Anime else TvType.TvSeries,
            ) {
                this.posterUrl = poster
            }
        }
    }

    private fun withHint(link: String, kind: String): String {
        if (kind.isBlank()) return link
        val sep = if (link.contains('?')) '&' else '?'
        return "$link$sep" + "_t=" + URLEncoder.encode(kind, "UTF-8")
    }

    // -------------------------------------------------------------- التفاصيل

    override suspend fun load(url: String): LoadResponse? {
        val hint = Regex("[?&]_t=([A-Za-z]+)").find(url)?.groupValues?.get(1)
        val pageUrl = url.replace(Regex("([?&])_t=[A-Za-z]+"), "")
            .trimEnd('?', '&')
            .ifBlank { url }

        val html = try {
            fetchPage(pageUrl)
        } catch (e: Exception) {
            Log.i(TAG, "load FAIL ${e.javaClass.simpleName} ${e.message}")
            return null
        }

        val title = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
            ?.let { unescape(stripTags(it)).trim() }
            ?.ifBlank { null }
            ?: return null

        val poster = Regex("<img[^>]*class=\"[^\"]*kkh-video-poster[^\"]*\"[^>]*src=\"([^\"]+)\"")
            .find(html)?.groupValues?.get(1)
            ?: Regex("<img[^>]*src=\"([^\"]+)\"[^>]*class=\"[^\"]*kkh-video-poster[^\"]*\"")
                .find(html)?.groupValues?.get(1)

        val plot = Regex("data-kkh-share[^>]*data-text=\"([^\"]*)\"", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
            ?.let { unescape(it).trim() }
            ?.ifBlank { null }

        val pageKind = Regex("explore/\\?type=(TVSeries|Movie|Anime|Hollywood)")
            .find(html)?.groupValues?.get(1)
        val kind = (hint ?: pageKind)?.lowercase(Locale.US)

        val tags = ArrayList<String>()
        genres(html).forEach { tags.add(it) }
        when (kind) {
            "anime" -> tags.add("Anime")
            "movie" -> tags.add("Movie")
            "tvseries" -> tags.add("TVSeries")
        }

        val episodes = parseEpisodes(html)
        // `Movie` صريح، أو تصنيفٌ عامّ (Hollywood) بحلقةٍ واحدة — كي لا يظهر
        // فيلمٌ كمسلسلٍ من حلقة.
        val isMovie = kind == "movie" ||
            ((kind == null || kind == "hollywood") && episodes.size <= 1)

        if (isMovie) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, "mov:$pageUrl") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        if (episodes.isEmpty()) return null

        val eps = episodes.mapIndexed { idx, (num, link) ->
            newEpisode("ep:$link") {
                this.name = "الحلقة ${fmt(num)}"
                this.episode = idx + 1
            }
        }
        return newTvSeriesLoadResponse(
            title, pageUrl,
            if (kind == "anime") TvType.Anime else TvType.TvSeries,
            eps,
        ) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    /**
     * حلقات المسلسل من كتلة `data-kkh-episodes` وحدها — لا من الصفحة كلّها،
     * فبعض صفحات المسلسل تعيد روابط «سابق/تالي» خارج الكتلة.
     *
     * الرقم يُقرأ من **نصّ الزرّ** لا من الرابط: الموقع يكرّر حلقةً بروابط
     * مختلفة (`…-episode-7-2/` وهو الحلقة ٨ فعلًا).
     */
    private fun parseEpisodes(html: String): List<Pair<Double, String>> {
        val start = html.indexOf("data-kkh-episodes>")
        if (start < 0) return emptyList()
        val mainEnd = html.indexOf("</main>", start)
        val end = if (mainEnd > start) mainEnd else minOf(html.length, start + 800_000)
        val block = html.substring(start, end)

        val out = LinkedHashMap<Double, String>()
        for (m in Regex(
            "<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(block)) {
            val href = unescape(m.groupValues[1]).replace("&amp;", "&")
            if (!href.contains("-episode-", true)) continue
            val body = stripTags(m.groupValues[2])
            val num = Regex("(\\d+(?:\\.\\d+)?)").find(body)
                ?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("-episode-(\\d+(?:\\.\\d+)?)").find(href)
                    ?.groupValues?.get(1)?.toDoubleOrNull()
                ?: continue
            if (!out.containsKey(num)) out[num] = absolute(href, mainUrl)
        }
        return out.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    private fun genres(html: String): List<String> {
        val i = html.indexOf("class=\"kkh-genres\"")
        if (i < 0) return emptyList()
        val end = html.indexOf("</p>", i).let { if (it > i) it else minOf(html.length, i + 4000) }
        return Regex("<a\\b[^>]*>([^<]+)</a>")
            .findAll(html.substring(i, end))
            .map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    // -------------------------------------------------------------- التشغيل

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val out = try {
            when {
                data.startsWith("mov:") -> resolveMovie(data.removePrefix("mov:"))
                data.startsWith("ep:") -> resolveEpisode(data.removePrefix("ep:"), null)
                else -> Resolved()
            }
        } catch (e: Exception) {
            Log.i(TAG, "loadLinks FAIL ${e.javaClass.simpleName} ${e.message}")
            Resolved()
        }
        val emitted = emit(out, subtitleCallback, callback)
        Log.i(
            TAG,
            "loadLinks ${data.take(60)} → $emitted رابط، ${out.subs.size} ترجمة"
        )
        return emitted > 0
    }

    /** فيلم قد يكون جزءًا واحدًا أو أجزاءً؛ كل الأجزاء تُجلب وتُعرض. */
    private suspend fun resolveMovie(pageUrl: String): Resolved {
        val html = try {
            fetchPage(pageUrl)
        } catch (e: Exception) {
            return Resolved()
        }
        val parts = parseEpisodes(html)
        if (parts.isEmpty()) return Resolved()

        val resolved = coroutineScope {
            parts.map { (num, link) ->
                async {
                    val note = if (parts.size > 1) "الجزء ${fmt(num)}" else null
                    resolveEpisode(link, note)
                }
            }.awaitAll()
        }

        val out = Resolved()
        for (r in resolved) {
            out.subs.addAll(r.subs)
            out.links.addAll(r.links)
        }
        return out
    }

    private suspend fun resolveEpisode(
        epUrl: String,
        note: String?,
    ): Resolved {
        val html = try {
            fetchPage(epUrl)
        } catch (e: Exception) {
            Log.i(TAG, "episode FAIL ${e.javaClass.simpleName} ${e.message}")
            return Resolved()
        }

        val servers = parseArray(
            Regex(
                "<script type=\"application/json\" data-kkh-servers>(.*?)</script>",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(html)?.groupValues?.get(1)
        )
        val downloads = parseArray(
            Regex(
                "<script type=\"application/json\" data-kkh-downloads>(.*?)</script>",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(html)?.groupValues?.get(1)
        )

        val resolved = coroutineScope {
            servers.map { server -> async { resolveServer(server, note) } }.awaitAll()
        }

        val out = Resolved()
        for (r in resolved) {
            out.subs.addAll(r.subs)
            out.links.addAll(r.links)
        }

        // روابط التحميل تُضاف كروابط مستقلة معلَّمة DOWNLOAD.
        for (d in downloads) {
            val linkUrl = d.optString("url").trim().ifBlank { continue }
            val label = d.optString("label").trim().ifBlank { "Download" }
            val quality = d.optString("quality").trim().ifBlank { null }
            out.links.add(
                Link(
                    server = label,
                    url = linkUrl,
                    type = if (linkUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8
                    else ExtractorLinkType.VIDEO,
                    quality = 0,
                    note = quality,
                    referer = "$mainUrl/",
                    use = MediaProbe.Use.DOWNLOAD,
                )
            )
        }

        return out
    }

    /** إصدار المخرجات إلى CloudStream مع إزالة التكرار الحرفيّ فقط. */
    private suspend fun emit(
        r: Resolved,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        var n = 0
        val seenSub = HashSet<String>()
        for (s in r.subs) {
            if (s.url.isBlank() || !seenSub.add(s.url)) continue
            try {
                subtitleCallback(newSubtitleFile(s.label, s.url))
            } catch (e: Exception) {
                Log.i(TAG, "subtitle skip ${e.javaClass.simpleName}")
            }
        }
        val seen = HashSet<String>()
        for (l in r.links) {
            val key = l.url + "|" + l.server + "|" + l.quality + "|" + l.note
            if (!seen.add(key)) continue
            try {
                val quality = MediaProbe.qualityLabel(l.quality)
                callback(
                    newExtractorLink(
                        source = name,
                        name = MediaProbe.label(
                            l.server, quality, null, l.url, l.type, l.note, l.use,
                        ),
                        url = l.url,
                        type = l.type,
                    ) {
                        this.quality = l.quality
                        this.referer = l.referer
                        this.headers = mapOf("Referer" to l.referer)
                    }
                )
                n++
            } catch (e: Exception) {
                Log.i(TAG, "link skip ${e.javaClass.simpleName}")
            }
        }
        return n
    }

    // -------------------------------------------------------------- السيرفرات

    private suspend fun resolveServer(server: JSONObject, note: String?): Resolved {
        val srvName = server.optString("name").trim()
            .ifBlank { server.optString("type").trim() }
            .ifBlank { "Server" }
        var src = server.optString("src").trim()
        if (src.isBlank()) {
            // بعض السيرفرات تُرسل HTML جاهزًا بدل رابط.
            src = Regex("https?://[^\"'\\s<>]+").find(server.optString("html"))
                ?.value.orEmpty()
        }
        if (src.isBlank()) return Resolved()

        return when {
            src.contains("vidbasic.", true) && src.contains("/embed/", true) ->
                resolveVidbasic(src, srvName, note)
            src.contains(".m3u8", true) || src.contains(".mp4", true) ->
                media(src, srvName, note, refererFor(src))
            else -> resolveGeneric(src, srvName, note)
        }
    }

    /**
     * سيرفر `vidbasic`: صفحة تضمين ← إطار `3rdplayer.html` ← قيمة `crypto`
     * المُشفّرة ← [Vidbasic] ← رابط الوسائط، ووسيط `sub` ← رابط الترجمة.
     */
    private suspend fun resolveVidbasic(
        embed: String,
        server: String,
        note: String?,
    ): Resolved {
        val html = try {
            fetchPage(embed, "https://kisskh.ba/")
        } catch (e: Exception) {
            ""
        }

        val playerUrl = findPlayerUrl(html)?.let { absolute(it, embed) }
        if (playerUrl != null) {
            resolvePlayer(playerUrl, server, note, "https://vidbasic.top/")?.let { return it }
        }
        return resolveGeneric(embed, server, note)
    }

    /**
     * يحلّ صفحة مشغّل `3rdplayer.html` (أو ما يشبهها): يفكّ `crypto` ← رابط
     * الوسائط، و`sub` ← رابط الترجمة. يُعيد null إن لم يجد ما يفكّه.
     */
    private suspend fun resolvePlayer(
        playerUrl: String,
        server: String,
        note: String?,
        referer: String,
    ): Resolved? {
        val player = try {
            fetchPage(playerUrl, referer)
        } catch (e: Exception) {
            ""
        }

        val crypto = Regex(
            "<script[^>]*data-name=\"crypto\"[^>]*data-value=\"([^\"]+)\""
        ).find(player)?.groupValues?.get(1)
            ?: Regex("<script[^>]*data-value=\"([^\"]+)\"[^>]*data-name=\"crypto\"")
                .find(player)?.groupValues?.get(1)
            ?: Regex("[?&]key=([^&\"'\\s]+)").find(playerUrl)
                ?.groupValues?.get(1)?.let { urlDecode(it) }

        val out = Resolved()
        // الترجمة: الوسيط `sub` مشفّر بالمفتاح نفسه وناتجه رابط vtt، ومقاطعه
        // مشفّرة أيضًا — فتُقدَّم مفكوكةً عبر [SubProxy].
        Regex("[?&]sub=([^&\"'\\s]+)").find(playerUrl)?.groupValues?.get(1)?.let { raw ->
            Vidbasic.decryptUrl(urlDecode(raw))?.let { subUrl ->
                out.subs.add(Sub(subLabel(subUrl), subtitleUrl(subUrl)))
            }
        }

        val mediaUrl = crypto?.let { Vidbasic.decryptUrl(it) } ?: return null

        val r = media(mediaUrl, server, note, referer)
        out.links.addAll(r.links)
        // ترجمات الماستر (EXT-X-MEDIA) تُضاف أيضًا.
        r.subs.forEach { s -> if (out.subs.none { it.url == s.url }) out.subs.add(s) }
        return out
    }

    /** أوّل إطار `3rdplayer.html` في صفحةٍ ما (مطلقًا كان أو نسبيًّا). */
    private fun findPlayerUrl(html: String): String? =
        Regex("[^\"'\\s<>]*3rdplayer\\.html\\?[^\"'\\s<>]+")
            .find(html)?.value?.replace("&amp;", "&")

    /**
     * سيرفرٌ عامّ (megaplay / streamtape / mixdrop…): صفحة التضمين قد تُفصح
     * عن رابط `.m3u8`/`.mp4`، أو تُغلّف مشغّل vidbasic؛ وإن لم يُفصح عن شيء
     * **تُعرض الصفحة نفسها** كرابط (note = embed) كي لا يختفي سيرفر.
     */
    private suspend fun resolveGeneric(
        embed: String,
        server: String,
        note: String?,
    ): Resolved {
        val html = try {
            app.get(embed, headers = head).text
        } catch (e: Exception) {
            ""
        }

        val found = Regex("https?://[^\"'\\s<>]+\\.(?:m3u8|mp4)[^\"'\\s<>]*")
            .findAll(html)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()

        val out = Resolved()
        for (m in found) {
            val r = media(m, server, note, refererFor(m))
            r.subs.forEach { s -> if (out.subs.none { it.url == s.url }) out.subs.add(s) }
            out.links.addAll(r.links)
        }
        if (out.links.isNotEmpty()) return out

        // صفحةٌ تُغلّف مشغّل vidbasic بدل أن تُفصح عن الرابط مباشرةً.
        findPlayerUrl(html)?.let { nested ->
            resolvePlayer(absolute(nested, embed), server, note, refererFor(embed))
                ?.let { return it }
        }

        out.links.add(
            Link(
                server = server,
                url = embed,
                type = ExtractorLinkType.VIDEO,
                quality = 0,
                note = note ?: "embed",
                referer = "$mainUrl/",
                use = MediaProbe.Use.WATCH,
            )
        )
        return out
    }

    /**
     * رابط وسائط: إن كان m3u8 قُرئ وفُصلت كل جوداته (`EXT-X-STREAM-INF`)،
     * وإلا فهو رابط واحد. لاحظ أنّ قوائم kisskh.ba عادةً **قائمة وسائط واحدة**
     * بلا variants، فهذه هي كل الجودات المتاحة فعلًا.
     */
    private suspend fun media(
        url: String,
        server: String,
        note: String?,
        referer: String,
    ): Resolved {
        val isHls = url.contains(".m3u8", true)
        var variants: List<Pair<Int, String>> = emptyList()
        val out = Resolved()

        if (isHls) {
            val txt = try {
                app.get(url, headers = head + mapOf("Referer" to referer)).text
            } catch (e: Exception) {
                ""
            }
            variants = parseVariants(txt, url)
            parseMediaSubtitles(txt, url).forEach { (label, sub) ->
                out.subs.add(Sub(label ?: subLabel(sub), sub))
            }
        }
        if (variants.isEmpty()) variants = listOf(0 to url)

        val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        for ((height, link) in variants) {
            out.links.add(
                Link(
                    server = server,
                    url = link,
                    type = type,
                    quality = height,
                    note = note,
                    referer = referer,
                    use = MediaProbe.Use.WATCH,
                )
            )
        }
        return out
    }

    // -------------------------------------------------------------- أدوات

    private class Sub(val label: String, val url: String)

    private class Link(
        val server: String,
        val url: String,
        val type: ExtractorLinkType,
        val quality: Int,
        val note: String?,
        val referer: String,
        val use: MediaProbe.Use?,
    )

    private class Resolved {
        val links = ArrayList<Link>()
        val subs = ArrayList<Sub>()
    }

    private fun parseArray(raw: String?): List<JSONObject> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(unescape(raw))
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseVariants(text: String, base: String): List<Pair<Int, String>> {
        if (text.isBlank() || !text.contains("#EXT-X-STREAM-INF")) return emptyList()
        val out = ArrayList<Pair<Int, String>>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val h = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                    ?.groupValues?.get(2)?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotBlank()) out.add(h to absolute(uri, base))
                }
                i = j
            }
            i++
        }
        return out.distinctBy { it.second }.sortedByDescending { it.first }
    }

    private fun parseMediaSubtitles(text: String, base: String): List<Pair<String?, String>> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Pair<String?, String>>()
        for (line in text.lines()) {
            val l = line.trim()
            if (!l.startsWith("#EXT-X-MEDIA:") || !l.contains("TYPE=SUBTITLES", true)) continue
            val uri = attr(l, "URI")?.takeIf { it.isNotBlank() } ?: continue
            val label = attr(l, "NAME")?.trim()?.ifBlank { null }
                ?: attr(l, "LANGUAGE")?.trim()?.ifBlank { null }
            out.add(label to absolute(uri, base))
        }
        return out.distinctBy { it.second }
    }

    private fun attr(tag: String, key: String): String? =
        Regex("(?i)" + Regex.escape(key) + "=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    /** رابط مطلق من رابطٍ نسبيّ داخل [base]. */
    private fun absolute(link: String, base: String): String {
        val l = link.replace("&amp;", "&").trim()
        if (l.startsWith("http://", true) || l.startsWith("https://", true)) return l
        return try {
            URI(base).resolve(l).toString()
        } catch (e: Exception) {
            val root = try {
                val u = URI(base)
                "${u.scheme}://${u.host}/"
            } catch (x: Exception) {
                "$mainUrl/"
            }
            root + l.trimStart('/')
        }
    }

    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /**
     * جلب صفحةٍ بمحاولتين — انقطاعٌ عابر أو ردّ Cloudflare `522` (حصل فعلًا
     * أثناء اختبار الجهاز) لا يجب أن يُفرغ النتيجة.
     */
    private suspend fun fetchPage(url: String, referer: String? = null): String {
        val h = if (referer == null) head else head + mapOf("Referer" to referer)
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                return app.get(url, headers = h).text
            } catch (e: Exception) {
                last = e
                if (attempt == 0) delay(400)
            }
        }
        throw last ?: IllegalStateException("fetch failed: $url")
    }

    /**
     * يُعيد رابط ترجمة **قابلًا للقراءة**: ملفّات `kk.vidbasic.top/track/…`
     * نصوصها مشفّرة، فتُقدَّم عبر [SubProxy] بعد فكّها ([SubText]). وإن تعذّر
     * فتح الوسيط بقي الرابط الأصلي (مشفَّرًا) خيرًا من لا شيء.
     */
    private fun subtitleUrl(src: String): String {
        val url = src.trim()
        if (url.isBlank()) return url
        if (!url.contains("vidbasic", true) && !url.contains("/track/", true)) return url

        var cached: String? = null
        val local = SubProxy.publish(md5(url)) {
            cached ?: buildVtt(url)?.also { cached = it }
        }
        return local ?: url
    }

    private fun buildVtt(url: String): String? = try {
        val raw = runBlocking {
            app.get(url, headers = head + mapOf("Referer" to "https://vidbasic.top/")).text
        }
        SubText.toVtt(raw)
    } catch (e: Exception) {
        Log.i(TAG, "subtitle build FAIL ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun md5(s: String): String = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        Integer.toHexString(s.hashCode())
    }

    private fun refererFor(url: String): String = try {
        val u = URI(url)
        if (u.host.isNullOrBlank()) "$mainUrl/" else "${u.scheme}://${u.host}/"
    } catch (e: Exception) {
        "$mainUrl/"
    }

    /** لغة ملفّ الترجمة من اسمه (`….ar.vtt` ← «Arabic»)، وإلا «Subtitle». */
    private fun subLabel(url: String): String {
        val file = url.substringBefore('?').substringAfterLast('/')
        val code = Regex("\\.([a-zA-Z]{2,3})\\.(?:vtt|srt|ass|ssa)$")
            .find(file)?.groupValues?.get(1)?.lowercase(Locale.US)
        return when (code) {
            // مسارات `kk.vidbasic.top/track/…` لا تحمل رمز لغة في اسمها،
            // والملفّ المرفق بها إنجليزيّ.
            null -> if (url.contains("/track/", true)) "English" else "Subtitle"
            "en" -> "English"
            "ar" -> "Arabic"
            "id" -> "Indonesian"
            "ms" -> "Malay"
            "th" -> "Thai"
            "vi" -> "Vietnamese"
            "zh" -> "Chinese"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "es" -> "Spanish"
            "pt" -> "Portuguese"
            "fr" -> "French"
            "de" -> "German"
            "tr" -> "Turkish"
            "hi" -> "Hindi"
            "tl" -> "Filipino"
            else -> code.uppercase(Locale.US)
        }
    }

    private fun stripTags(s: String): String =
        unescape(s.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun unescape(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")

    /** `3.0` ← «3» و`16.6` ← «16.6». */
    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite() && !d.isNaN()) d.toLong().toString()
        else d.toString()

    private companion object {
        /** حجم صفحة `explore` في REST (٣٠ عنصرًا). */
        const val PAGE_SIZE = 30
    }
}
