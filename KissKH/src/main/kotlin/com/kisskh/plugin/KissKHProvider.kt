package com.kisskh.plugin

import android.util.Log
import cloudstreamshared.MediaProbe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * KissKH — `kisskh.bz` (نسخة 2026 مبنية على Next.js).
 *
 * بنية الموقع (مقيسة 2026-11):
 *  - البطاقات: `<article class="media-card"><a class="media-card-link" href="/series/…">`
 *    أو `/movies/…`، والصورة عبر `/_next/image?url=<tmdb>`.
 *  - صفحة المسلسل تحمل روابط كل الحلقات `/series/{id}/season/{s}/episode/{e}/`،
 *    وصفحة الفيلم تحمل زرّ `/watch/movies/{id}/`.
 *  - صفحة المشاهدة (`/watch/…`) تحمل `embedUrl` إلى مشغّل `hnvid.cc/embed/…`.
 *  - المشغّل يبني ملف JSON في `<script id="hnvid-bootstrap">` فيه:
 *      playback.url  = master.m3u8 (كل الجودات داخله)
 *      subtitles[]   = كل ملفات الترجمة المتاحة (url + label)
 *    فتُقرأ الجودات من متغيّرات الـmaster وتُعرض كلها، وتُمرَّر كل الترجمات.
 */
class KissKHProvider : MainAPI() {

    override var mainUrl = "https://kisskh.bz"
    override var name = "KissKH"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val head get() = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "en-US,en;q=0.9",
    )

    private val TAG = "KissKH"

    override val mainPage = mainPageOf(
        "" to "الرئيسية",
        "/new-popular/" to "جديد وشائع",
        "/tv/" to "مسلسلات",
        "/movies/" to "أفلام",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = (mainUrl + request.data).let { if (it.endsWith("/")) it else "$it/" }
        val url = if (page <= 1) base else "${base}?page=$page"
        val doc = try {
            app.get(url, headers = head).document
        } catch (e: Exception) {
            Log.i(TAG, "getMainPage FAIL ${e.javaClass.simpleName} ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
        val items = doc.select("article.media-card")
            .mapNotNull { cardToSearch(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/search/?q=" + URLEncoder.encode(query, "UTF-8")
        val doc = try {
            app.get(url, headers = head).document
        } catch (e: Exception) {
            Log.i(TAG, "search FAIL ${e.javaClass.simpleName} ${e.message}")
            return null
        }
        val items = doc.select("article.media-card")
            .mapNotNull { cardToSearch(it) }
            .distinctBy { it.url }
        return items.ifEmpty { null }
    }

    /** بطاقة `<article class="media-card">` في الرئيسية/البحث. */
    private fun cardToSearch(card: Element): SearchResponse? {
        val a = card.selectFirst("a.media-card-link")
            ?: card.selectFirst("a[href]")
            ?: return null
        val href = a.attr("href").ifBlank { return null }
        if (!href.contains("/series/") && !href.contains("/movies/")) return null
        val title = card.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
            ?: a.attr("aria-label").removePrefix("View ").trim().ifBlank { null }
            ?: return null
        val poster = card.selectFirst("img")?.let { img ->
            decodeNextImage(img.attr("src").ifBlank { img.attr("data-src") })
        }
        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url)
        val doc = try {
            app.get(pageUrl, headers = head).document
        } catch (e: Exception) {
            Log.i(TAG, "load FAIL ${e.javaClass.simpleName} ${e.message}")
            return null
        }
        val ld = jsonLd(doc)
        val ldType = ld?.optString("@type").orEmpty()
        val isMovie = pageUrl.contains("/movies/") || ldType == "Movie"

        val title = ld?.optString("name")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("|")?.trim()?.ifBlank { null }
            ?: return null
        val poster = (ld?.optString("image")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null })
            ?.let { decodeNextImage(it) }
        val plot = ld?.optString("description")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        val tags = ArrayList<String>()
        ld?.optJSONArray("genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { tags.add(it) }
            }
        }

        if (isMovie) {
            val watch = doc.select("a[href]")
                .firstOrNull { it.attr("href").contains("/watch/") }
                ?.attr("href")?.ifBlank { null }
                ?: watchUrlOf(pageUrl)
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, fixUrl(watch)) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        // الحلقات: روابط `/series/{id}/season/{s}/episode/{e}/` (أو روابط watch).
        val epLinks = LinkedHashSet<String>()

        fun addEp(href: String) {
            if (!href.contains("/episode/")) return
            epLinks.add(if (href.contains("/watch/")) href else href.replace("/series/", "/watch/series/"))
        }

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (href.contains("/episode/")) addEp(fixUrl(href))
        }

        val seasons = ld?.optInt("numberOfSeasons", 1) ?: 1
        if (seasons > 1) {
            val base = pageUrl.trimEnd('/')
            for (s in 1..seasons) {
                // الموسم الأول موجود في الصفحة نفسها غالبًا؛ نجلبه فقط إن غاب.
                if (epLinks.any { it.contains("/season/$s/") }) continue
                val sdoc = try {
                    app.get("$base/season/$s/", headers = head).document
                } catch (e: Exception) {
                    continue
                }
                sdoc.select("a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (href.contains("/episode/")) addEp(fixUrl(href))
                }
            }
        }

        val episodes = epLinks.mapNotNull { link ->
            val s = Regex("/season/(\\d+)/").find(link)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val e = Regex("/episode/(\\d+)/").find(link)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            newEpisode(link) {
                this.name = if (seasons > 1) "الموسم $s · الحلقة $e" else "الحلقة $e"
                this.season = s
                this.episode = e
            }
        }.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .distinctBy { it.data }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
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
        val watchUrl = watchUrlOf(data)
        val watchHtml = try {
            app.get(watchUrl, headers = head).text
        } catch (e: Exception) {
            Log.i(TAG, "loadLinks watch FAIL ${e.javaClass.simpleName} ${e.message}")
            ""
        }
        if (watchHtml.isBlank()) return false

        // صفحة المشاهدة تحمل رابط المشغّل في JSON-LD: "embedUrl":"https://hnvid.cc/embed/…"
        // نأخذ كل ما ورد (قد يتعدّد لاحقًا) لا الأول فقط — «كل السيرفرات».
        val embeds = Regex("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(watchHtml)
            .map { it.groupValues[1].replace("\\/", "/").replace("\\u0026", "&") }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
        if (embeds.isEmpty()) return false

        // كل ملفات الترجمة المتاحة على الموقع لهذه الحلقة/الفيلم.
        val subSeen = HashSet<String>()
        suspend fun emitSub(label: String?, url: String, base: String) {
            if (url.isBlank()) return
            val abs = resolve(base, url)
            if (!subSeen.add(abs)) return
            val name = label?.trim()?.ifBlank { null } ?: "Subtitle"
            try {
                subtitleCallback(newSubtitleFile(name, abs))
            } catch (e: Exception) {
                Log.i(TAG, "subtitle skip ${e.javaClass.simpleName}")
            }
        }

        var emitted = 0
        embeds.forEachIndexed { index, embed ->
            val embedHtml = try {
                app.get(embed, headers = head).text
            } catch (e: Exception) {
                Log.i(TAG, "loadLinks embed FAIL ${e.javaClass.simpleName} ${e.message}")
                ""
            }
            if (embedHtml.isBlank()) return@forEachIndexed

            val boot = extractBootstrap(embedHtml)
            val playback = boot?.optJSONObject("playback")?.optString("url").orEmpty()
                .ifBlank {
                    Regex("\"playback\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"")
                        .find(embedHtml)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()
                }
            if (playback.isBlank()) return@forEachIndexed
            val masterUrl = resolve(embed, playback)

            // ترجمات المشغّل: `subtitles` و`subtitle_tracks` (وقد يوجد ملف خارجي).
            for (key in listOf("subtitles", "subtitle_tracks")) {
                val arr = boot?.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("url")
                    if (u.isBlank()) continue
                    val label = o.optString("label").ifBlank {
                        o.optString("language").ifBlank { o.optString("lang") }
                    }
                    emitSub(label, u, embed)
                }
            }
            boot?.opt("external_subtitle")?.let { ext ->
                if (ext is JSONObject) {
                    val u = ext.optString("url")
                    if (u.isNotBlank()) emitSub(ext.optString("label"), u, embed)
                }
            }

            // master.m3u8 يحمل كل الجودات كـvariants — تُعرض كلها منفصلة.
            val masterText = try {
                app.get(masterUrl, headers = head + mapOf("Referer" to embed)).text
            } catch (e: Exception) {
                Log.i(TAG, "master FAIL ${e.javaClass.simpleName} ${e.message}")
                ""
            }

            // ترجمات مضمّنة في الـmaster وقد لا تكون في JSON المشغّل.
            parseMediaSubtitles(masterText, masterUrl).forEach { (label, u) ->
                emitSub(label, u, masterUrl)
            }

            val variants = parseVariants(masterText, masterUrl)
            val links = if (variants.isEmpty()) listOf(0 to masterUrl) else variants
            val server = if (embeds.size > 1) "HNVID ${index + 1}" else "HNVID"

            for ((height, url) in links) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = MediaProbe.label(
                            server,
                            MediaProbe.qualityLabel(height),
                            null,
                            url,
                            ExtractorLinkType.M3U8,
                            null,
                            MediaProbe.Use.WATCH,
                        ),
                        url = url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = height
                        this.referer = embed
                        this.headers = mapOf("Referer" to embed)
                    }
                )
            }
            emitted += links.size
        }
        Log.i(TAG, "loadLinks: $emitted جودة، ${subSeen.size} ترجمة، ${embeds.size} مشغّل")
        return emitted > 0
    }

    // ---------- أدوات ----------

    /** كل variant في master.m3u8: (الارتفاع، الرابط المطلق). */
    private fun parseVariants(text: String, base: String): List<Pair<Int, String>> {
        if (text.isBlank()) return emptyList()
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
                    if (uri.isNotBlank()) out.add(h to resolve(base, uri))
                }
                i = j
            }
            i++
        }
        return out.distinctBy { it.second }.sortedByDescending { it.first }
    }

    /**
     * ترجمات مضمّنة في `master.m3u8`:
     * `#EXT-X-MEDIA:TYPE=SUBTITLES,NAME="English",LANGUAGE="en",URI="subs/en.m3u8"`
     */
    private fun parseMediaSubtitles(text: String, base: String): List<Pair<String?, String>> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Pair<String?, String>>()
        for (line in text.lines()) {
            val l = line.trim()
            if (!l.startsWith("#EXT-X-MEDIA:")) continue
            if (!l.contains("TYPE=SUBTITLES", ignoreCase = true)) continue
            val uri = attr(l, "URI")?.takeIf { it.isNotBlank() } ?: continue
            val name = attr(l, "NAME")?.trim()?.ifBlank { null }
            val lang = attr(l, "LANGUAGE")?.trim()?.ifBlank { null }
            out.add((name ?: lang) to resolve(base, uri))
        }
        return out.distinctBy { it.second }
    }

    private fun attr(tag: String, key: String): String? {
        val m = Regex("(?i)" + Regex.escape(key) + "=\"([^\"]*)\"").find(tag) ?: return null
        return m.groupValues[1]
    }

    private fun extractBootstrap(html: String): JSONObject? {
        val m = Regex(
            "<script[^>]*id=[\"']hnvid-bootstrap[\"'][^>]*>(.*?)</script>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html) ?: return null
        return try {
            JSONObject(m.groupValues[1].trim())
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonLd(doc: Document): JSONObject? {
        for (el in doc.select("script[type=application/ld+json]")) {
            val raw = el.data().ifBlank { el.html() }.trim()
            if (raw.isBlank()) continue
            val obj = try {
                JSONObject(raw)
            } catch (e: Exception) {
                continue
            }
            val type = obj.optString("@type")
            if (type == "TVSeries" || type == "Movie") return obj
            val graph = obj.optJSONArray("@graph") ?: continue
            for (i in 0 until graph.length()) {
                val g = graph.optJSONObject(i) ?: continue
                val gt = g.optString("@type")
                if (gt == "TVSeries" || gt == "Movie") return g
            }
        }
        return null
    }

    /** `/_next/image?url=<encoded>` ← الرابط الأصلي (عادةً tmdb). */
    private fun decodeNextImage(src: String): String? {
        if (src.isBlank()) return null
        val abs = if (src.startsWith("http")) src else mainUrl + src
        val inner = Regex("[?&]url=([^&]+)").find(abs)?.groupValues?.get(1)
        return if (inner != null) {
            try {
                URLDecoder.decode(inner, "UTF-8")
            } catch (e: Exception) {
                abs
            }
        } else {
            abs
        }
    }

    /** صفحة المشاهدة من أي رابط حلقة/فيلم. */
    private fun watchUrlOf(data: String): String {
        val url = fixUrl(data)
        if (url.contains("/watch/")) return url
        if (url.contains("/movies/")) return url.replace("/movies/", "/watch/movies/")
        if (url.contains("/series/")) return url.replace("/series/", "/watch/series/")
        return url
    }

    private fun resolve(base: String, link: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        return try {
            java.net.URI(base).resolve(link).toString()
        } catch (e: Exception) {
            base.substringBeforeLast('/') + "/" + link.trimStart('/')
        }
    }
}
