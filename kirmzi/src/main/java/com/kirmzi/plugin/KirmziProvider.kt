package com.kirmzi.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import android.util.Log
import android.content.SharedPreferences

class KirmziProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    companion object {
        private const val TAG = "Kirmzi"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://kirmzi.tv"
    override var name = "قرمزي TV"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    // ---- P.A.C.K.E.R. unpacker (port of CloudStream's JsUnpacker) ----
    fun intToBase36Local(n0: Int): String {
        if (n0 == 0) return "0"
        var n = n0
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        while (n > 0) { sb.append(chars[n % 36]); n /= 36 }
        return sb.reverse().toString()
    }

    fun jsStringUnescape(s: String): String {
        val regex = Regex("""\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}|\\.|\\n|\\r|\\t""")
        return regex.replace(s) { m ->
            val esc = m.value
            try {
                when {
                    esc.startsWith("\\x") -> esc.substring(2).toInt(16).toChar().toString()
                    esc.startsWith("\\u") -> esc.substring(2).toInt(16).toChar().toString()
                    esc == "\\n" -> "\n"
                    esc == "\\r" -> "\r"
                    esc == "\\t" -> "\t"
                    esc == "\\'" -> "'"
                    esc == "\\\"" -> "\""
                    else -> esc.substring(1)
                }
            } catch (_: Exception) { esc }
        }
    }

    fun parseJsStringAt(text: String, idxInit: Int): Pair<String?, Int> {
        var idx = idxInit
        if (idx >= text.length) return Pair(null, idx)
        val quote = text[idx]
        if (quote != '"' && quote != '\'') return Pair(null, idx)
        idx += 1
        val out = StringBuilder()
        while (idx < text.length) {
            val ch = text[idx]
            if (ch == '\\') {
                if (idx + 1 < text.length) { out.append(text.substring(idx, idx + 2)); idx += 2 }
                else idx++
            } else if (ch == quote) { return Pair(jsStringUnescape(out.toString()), idx + 1) }
            else { out.append(ch); idx++ }
        }
        return Pair(null, idx)
    }

    fun findMatchingBrace(text: String, startIdx: Int): Int {
        if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return -1
        var depth = 0; var i = startIdx
        while (i < text.length) {
            val ch = text[i]
            if (ch == '{') depth++
            else if (ch == '}') { depth--; if (depth == 0) return i }
            i++
        }
        return -1
    }

    /** Unpacks a P.A.C.K.E.R. eval blob; the compiled CloudStream jar exposes the
     *  same algorithm. Handles the anaplayer/embed format:
     *  eval(function(p,a,c,k,e,d){...}('qq..',33,22,'k0|k1|...'.split('|'),0,{})) */
    fun unpackPackerFromEval(evalText: String): String? {
        try {
            val startFn = evalText.indexOf("function(p,a,c,k,e,d)")
            if (startFn == -1) return null
            val braceOpen = evalText.indexOf('{', startFn)
            if (braceOpen == -1) return null
            val braceClose = findMatchingBrace(evalText, braceOpen)
            if (braceClose == -1) return null
            val argsStart = evalText.indexOf('(', braceClose)
            if (argsStart == -1) return null
            var i = argsStart + 1
            while (i < evalText.length && evalText[i].isWhitespace()) i++
            val (pVal, newI) = parseJsStringAt(evalText, i); i = newI
            if (pVal == null) return null
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
            val aMatch = Regex("""\d+""").find(evalText.substring(i)) ?: return null
            i += aMatch.range.last + 1
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
            val cMatch = Regex("""\d+""").find(evalText.substring(i)) ?: return null
            val cVal = cMatch.value.toInt()
            i += cMatch.range.last + 1
            while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
            val kList = mutableListOf<String>()
            if (i < evalText.length && (evalText[i] == '"' || evalText[i] == '\'')) {
                val (kStr, i2) = parseJsStringAt(evalText, i); i = i2
                if (kStr != null) kList.addAll(kStr.split("|"))
            } else {
                val m2 = Regex("""(['"])(.*?)\1\s*\.split\s*\(\s*['"]\|['"]\s*\)""", RegexOption.DOT_MATCHES_ALL).find(evalText)
                if (m2 != null) kList.addAll(m2.groupValues[2].split("|"))
            }
            var p = pVal
            for (idx in cVal - 1 downTo 0) {
                val key = intToBase36Local(idx)
                if (idx < kList.size && kList[idx].isNotEmpty()) {
                    p = Regex("\\b" + Regex.escape(key) + "\\b").replace(p ?: "", kList[idx])
                }
            }
            return p
        } catch (_: Exception) { return null }
    }

    /** Scan HTML for eval packers, unpack each, collect media (m3u8/mp4) URLs. */
    fun analyzeAndUnpackScripts(htmlText: String): List<String> {
        try {
            val doc = org.jsoup.Jsoup.parse(htmlText)
            val found = mutableListOf<String>()
            for (s in doc.select("script")) {
                val content = s.data().ifBlank { s.html() }
                if (content.contains("eval(")) {
                    val m = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{""").find(content)
                    if (m == null) continue
                    val start = m.range.first
                    val sample = if (content.length > start + 20000) content.substring(start, start + 20000) else content.substring(start)
                    val unpacked = unpackPackerFromEval(sample) ?: continue
                    val hits = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                        .findAll(unpacked).map { it.groupValues[1] }.toList()
                    found.addAll(hits)
                }
            }
            return found
        } catch (e: Exception) {
            Log.e(TAG, "analyzeAndUnpackScripts ex: ${e.message}")
            return emptyList()
        }
    }

    fun originOf(u: String): String =
        Regex("""https?://[^/"']+""").find(u)?.value ?: u

    // ---- Card parsing ----
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null
        // Skip "coming soon" (قريباً) cards that have no episodes yet.
        if (this.selectFirst(".ribbon")?.text()?.contains("قريب") == true) return null
        val title = this.attr("title").ifBlank {
            this.selectFirst(".title")?.text().orEmpty()
                .ifBlank { this.selectFirst("img")?.attr("alt").orEmpty() }
        }.orEmpty()
        // Poster lives inside the card: img.imgSer lazy has data-src or src;
        // footnote styles use a style background-image (krmzi).
        val img = this.selectFirst("img")
        val poster = img?.attr("data-src").orEmpty()
            .ifBlank { img?.attr("src").orEmpty() }
            .ifBlank {
                this.selectFirst("[style*='background-image']")?.attr("style")?.let {
                    Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1).orEmpty()
                }.orEmpty()
            }.orEmpty()
        if (title.isBlank()) return null
        return when {
            href.contains("/series/") -> newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster.ifBlank { null }
            }
            href.contains("/movies/") || href.contains("/film/") -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster.ifBlank { null }
            }
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // The homepage only shows episode cards, which the user does not want
        // scattered across the home. Use the site's real listings: the full
        // series page and (if present) the movies page, so the home has more
        // than one section.
        val items = ArrayList<SearchResponse>()
        val seen = mutableSetOf<String>()
        fun addAll(doc: org.jsoup.nodes.Document?) {
            if (doc == null) return
            doc.select(".block-post a[href*=/series/], a.posterThumb[href*=/series/], a[href*=/movies/]")
                .mapNotNull { it.toSearchResponse() }
                .forEach { if (seen.add(it.url)) items += it }
        }
        addAll(try { app.get("$mainUrl/turkish-series/").document } catch (_: Exception) { null })
        addAll(try { app.get("$mainUrl/movies/").document } catch (_: Exception) { null })
        if (items.isEmpty()) return newHomePageResponse(emptyList())
        // Section 1 = series; Section 2 = films (kept only if it has entries).
        val series = items.filter { it.url.contains("/series/") }
        val films = items.filter { it.url.contains("/movies/") }
        val sections = mutableListOf<HomePageList>()
        if (series.isNotEmpty()) sections += HomePageList("المسلسلات", series)
        if (films.isNotEmpty()) sections += HomePageList("الأفلام", films)
        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim()}"
        val doc = app.get(url).document
        // Search returns series cards (a.block-post > a[href*=/series/]) with
        // real posters. Fall back to any series/episode card if the layout differs.
        val cards = doc.select(".block-post a[href], article.post a[href]")
        val items = cards
            .filter { it.attr("href").isNotBlank() }
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        // If given an episode URL, resolve to its series page (like ThreeSk).
        if (url.contains("/episode/")) {
            val epDoc = app.get(url).document
            val seriesUrl = epDoc.selectFirst("a[href*=/series/]")?.attr("href")
            if (seriesUrl.isNullOrBlank()) return null
            return load(seriesUrl)
        }
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null

        // Extract poster from multiple sources
        val poster = doc.selectFirst("img[src*=/wp-content/uploads/]")?.attr("src")
            ?.ifBlank { null }
            ?: doc.selectFirst("img")?.attr("data-src")
            ?.ifBlank { null }
            ?: doc.selectFirst("img")?.attr("src")

        val description = doc.selectFirst("[class*=description], [class*=sinops], [class*=story]")?.text()

        // Movie page (kirmzi.tv /movies/...) → no episodes.
        if (url.contains("/movies/") || url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Episodes from the series page
        val episodes = ArrayList<Episode>()
        doc.select("a[href*=/episode/]").forEach { a ->
            val epUrl = a.attr("href").ifBlank { return@forEach }
            val epTitle = a.attr("title")
            // "مسلسل X الحلقة 70 قرمزي" -> 70
            val epNum = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/(\d+)/$""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            episodes.add(
                newEpisode(epUrl) {
                    name = if (epTitle.isNotBlank()) {
                        Regex("""^مسلسل\s+""").replace(epTitle, "").replace(" قرمزي", "").trim()
                    } else "الحلقة ${epNum ?: episodes.size + 1}"
                    episode = epNum
                    posterUrl = poster
                }
            )
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
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
        Log.d(TAG, "loadLinks START for: $data")
        val headers = mapOf(
            "User-Agent" to UA
        )
        // نجمع كل روابط السيرفرات أولاً (emitEmbed يرفعها إلى هنا)، ثم نبثّها دفعة
        // واحدة عند المخرج الوحيد من loadLinks. الافتراضي = نفس ترتيبها تماماً.
        val collected = mutableListOf<ExtractorLink>()
        try {
            // 1. Episode page -> anaplayer iframe (the albaplayer wrapper).
            val epDoc = app.get(data).document
            val anaIframe = epDoc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
                .firstOrNull { it.contains("anaplayer") } ?: run {
                Log.e(TAG, "No anaplayer iframe found on $data")
                return false
            }
            val anaUrl = if (anaIframe.startsWith("//")) "https:$anaIframe" else anaIframe
            val anaOrigin = originOf(anaUrl)
            Log.d(TAG, "anaplayer base: $anaUrl")

            // 2. Fetch the albaplayer page; its <a> tabs are the named servers
            //    (CDNPlus, MP4Plus, AnaFast, Vidoba, VidSpeed, OK) -> ?serv=N.
            //    Keep the tab's text label so each emitted link gets its real name.
            val anaDoc = app.get(anaUrl, referer = mainUrl, headers = headers).document
            val tabs = anaDoc.select("a[href*='serv=']").mapNotNull { a ->
                val href = a.attr("href")
                val label = a.text().trim().ifBlank {
                    a.selectFirst(".title")?.text()?.trim().orEmpty()
                }
                val abs = when {
                    href.startsWith("//") -> "https:$href"
                    href.startsWith("/") ->
                        Regex("""https?://[^/]+""").find(anaUrl)?.value + href
                    else -> href
                }
                // The visible label (e.g. "CDNPlus") — fall back to serv=N when
                // a tab hand-picked a human name.
                val name = label.ifBlank { "سيرفر " + href.substringAfter("serv=").substringBefore("&") }
                if (abs.contains("serv=")) abs to name else null
            }.distinctBy { it.first }
            Log.d(TAG, "server tabs: ${tabs.size} : ${tabs.joinToString(", ") { it.first.substringAfter("serv=") + "=" + it.second }}")

            // If no tabs (sometimes anaplayer renders the embed iframe directly),
            // fall back to any iframe that points to a real embed host.
            if (tabs.isEmpty()) {
                val directEmbeds = anaDoc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
                    .filter { !it.contains("w.anaplayer") }
                if (directEmbeds.isNotEmpty()) {
                    directEmbeds.forEach {
                        emitEmbed(it, originOf(anaUrl), headers, collected, label = hostLabel(it))
                    }
                }
                emitSorted(prefs, collected, callback)
                return tabs.isEmpty()
            }

            // 3. For each tab, fetch ?serv=N and read its embed iframe (or direct player).
            val results = coroutineScope {
                tabs.map { (tabHref, label) ->
                    async {
                        val embed = try {
                            val page = app.get(tabHref, referer = anaUrl, headers = headers)
                            val iframeSrc = page.document.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
                                .firstOrNull { !it.contains("w.anaplayer") }
                            iframeSrc?.let { Triple(tabHref, it, label) }
                        } catch (_: Exception) { null }
                        embed
                    }
                }.mapNotNull { it.await() }
            }

            // 4. Emit each embed with its real server name (dedupe by URL).
            val seen = mutableSetOf<String>()
            results.forEach { (_, embedSrc, tabLabel) ->
                val embedUrl = when {
                    embedSrc.startsWith("//") -> "https:$embedSrc"
                    embedSrc.startsWith("/") -> anaOrigin + embedSrc
                    else -> embedSrc
                }
                val label = tabLabel.ifBlank { hostLabel(embedUrl) }
                if (seen.add(embedUrl)) {
                    emitEmbed(embedUrl, anaOrigin, headers, collected, label = label)
                }
            }
            emitSorted(prefs, collected, callback)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error", e)
            return false
        }
    }

    private suspend fun emitEmbed(
        embedUrl: String,
        refererFromPrev: String,
        headersBase: Map<String, String>,
        collected: MutableList<ExtractorLink>,
        label: String = hostLabel(embedUrl)
    ) {
        val embedOrigin = originOf(embedUrl)
        try {
            val hdrs = headersBase.toMutableMap()
            hdrs["Referer"] = refererFromPrev
            val r = app.get(embedUrl, referer = refererFromPrev, headers = hdrs)
            val text = r.text
            Log.d(TAG, "[$label] GET ${r.url ?: "?"} -> ${r.code} len=${text.length}")
            // a) direct media URLs in the HTML
            val direct = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                .findAll(text).map { it.groupValues[1] }.toList()
            // b) inside P.A.C.K.E.R. blobs
            val unpacked = analyzeAndUnpackScripts(text)
            val allMedia = (direct + unpacked).distinct()
            if (allMedia.isEmpty()) {
                Log.d(TAG, "[$label] no HLS/MP4 found — emit embed page as VIDEO fallback")
                // فرع «صفحة التضمين كـ VIDEO»: يُسقَط فقط إن عطّل المستخدم الخيار
                // (الافتراضي مفعّل = سلوك اليوم حرفياً). لا يتأثر به أي رابط وسائط.
                if (prefs?.getBoolean(KirmziSettingsBottomSheet.KEY_SHOW_RAW_LINK, true) != false) {
                    collected.add(
                        newExtractorLink(
                            source = "قرمزي TV",
                            name = label,
                            url = embedUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = embedOrigin
                            this.headers = mapOf("User-Agent" to UA)
                        }
                    )
                }
                return
            }
            allMedia.forEach { mediaUrl ->
                val m3u8 = mediaUrl.contains(".m3u8")
                Log.d(TAG, "[$label] emit ${if (m3u8) "HLS" else "MP4"}: $mediaUrl")
                collected.add(
                    newExtractorLink(
                        source = "قرمزي TV",
                        name = label,
                        url = mediaUrl,
                        type = if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = embedOrigin
                        this.headers = mapOf(
                            "User-Agent" to UA,
                            "Accept" to "*/*",
                            "Referer" to embedOrigin
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$label] embed GET/emit failed: $e")
        }
    }

    /**
     * ★ بثّ الروابط بعد اكتمالها: «افتراضي» = نفس الترتيب والعدد تماماً،
     * و«تصاعدي/تنازلي» يعيدان ترتيبها فقط (فرز مستقر: المتساوية تحتفظ بترتيبها،
     * ولا حذف ولا تكرار).
     */
    private fun emitSorted(prefs: SharedPreferences?, collected: List<ExtractorLink>, callback: (ExtractorLink) -> Unit) {
        val order = prefs?.getString(KirmziSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
    }

    private fun hostLabel(u: String): String {
        val host = Regex("""https?://([^/:]+)""").find(u)?.groupValues?.get(1) ?: return u
        return host.replace(".space", "").replace(".cyou", "")
            .replace(".website", "").substringBeforeLast('.').ifBlank { host }
    }
}