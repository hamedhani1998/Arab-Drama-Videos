package com.kirmzi.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import android.util.Log

class KirmziProvider : MainAPI() {
    companion object {
        private const val TAG = "Kirmzi"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://kirmzi.tv"
    override var name = "قرمزي"
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
        val title = this.attr("title").ifBlank {
            this.selectFirst("img")?.attr("alt").orEmpty()
        }
        // Extract poster: try data-src (lazy load), then src, then style background-image
        val img = this.selectFirst("img")
        val poster = img?.attr("data-src").orEmpty()
            .ifBlank { img?.attr("src").orEmpty() }
            .ifBlank {
                this.attr("style").let {
                    Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1).orEmpty()
                }
            }
        val type = if (href.contains("/episode/")) TvType.TvSeries else TvType.TvSeries
        return when {
            href.contains("/series/") || href.contains("/episode/") ->
                newTvSeriesSearchResponse(title.ifBlank { "قرمزي" }, href) {
                    this.posterUrl = poster.ifBlank { null }
                }
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        // Only show series, not individual episodes
        val items = doc.select("a.posterThumb[href*=/series/]")
            .mapNotNull { it.toSearchResponse() }
        val homeList = HomePageList("المسلسلات", items)
        return newHomePageResponse(listOf(homeList))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim()}"
        val doc = app.get(url).document
        // Filter by series only, not individual episodes
        return doc.select("a.posterThumb[href*=/series/]").filter { it.attr("href").isNotBlank() }
            .mapNotNull { it.toSearchResponse() }
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
        try {
            // 1. Episode page -> anaplayer iframe
            val epDoc = app.get(data).document
            val anaIframe = epDoc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
                .firstOrNull { it.contains("anaplayer") } ?: run {
                Log.e(TAG, "No anaplayer iframe found on $data")
                return false
            }
            val anaUrl = if (anaIframe.startsWith("//")) "https:$anaIframe" else anaIframe
            Log.d(TAG, "anaplayer base: $anaUrl")

            // 2. Fetch the albaplayer page; its <a> tabs give the serv=N pages.
            val anaDoc = app.get(anaUrl, referer = mainUrl, headers = headers).document
            val tabHrefs = anaDoc.select("a[href*='serv=']").map { it.attr("href") }
                .map { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "https://w.anaplayer.online$it" else it }
                .filter { it.contains("serv=") }.distinct()
            Log.d(TAG, "server tabs: ${tabHrefs.size} : ${tabHrefs.joinToString(", ") { it.substringAfter("serv=") }}")

            // If no tabs (sometimes anaplayer renders iframe directly), fall back to any
            // iframe that points to a real embed host.
            if (tabHrefs.isEmpty()) {
                val directEmbeds = anaDoc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
                    .filter { !it.contains("w.anaplayer") }
                if (directEmbeds.isNotEmpty()) {
                    directEmbeds.forEach { emitEmbed(it, originOf(anaUrl), headers, callback) }
                }
                return tabHrefs.isEmpty()
            }

            // 3. For each tab, fetch ?serv=N and read its embed iframe (or direct player).
            val results = coroutineScope {
                tabHrefs.map { tabHref ->
                    async {
                        val embed = try {
                            val page = app.get(tabHref, referer = anaUrl, headers = headers)
                            val iframeSrc = page.document.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
                                .firstOrNull { !it.contains("w.anaplayer") }
                            iframeSrc?.let { Triple(tabHref, it, "") }
                        } catch (_: Exception) { null }
                        embed
                    }
                }.mapNotNull { it.await() }
            }

            // 4. Emit each embed (dedupe by URL).
            val seen = mutableSetOf<String>()
            results.forEach { (tabUrl, embedSrc, _) ->
                val embedUrl = if (embedSrc.startsWith("//")) "https:$embedSrc"
                else if (embedSrc.startsWith("/")) "https://w.anaplayer.online$embedSrc"
                else embedSrc
                if (seen.add(embedUrl)) {
                    emitEmbed(embedUrl, originOf(anaUrl), headers, callback)
                }
            }
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
        callback: (ExtractorLink) -> Unit
    ) {
        val embedOrigin = originOf(embedUrl)
        val label = hostLabel(embedUrl)
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
                callback.invoke(
                    newExtractorLink(
                        source = "قرمزي",
                        name = label,
                        url = embedUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = embedOrigin
                        this.headers = mapOf("User-Agent" to UA)
                    }
                )
                return
            }
            allMedia.forEach { mediaUrl ->
                val m3u8 = mediaUrl.contains(".m3u8")
                Log.d(TAG, "[$label] emit ${if (m3u8) "HLS" else "MP4"}: $mediaUrl")
                callback.invoke(
                    newExtractorLink(
                        source = "قرمزي",
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

    private fun hostLabel(u: String): String {
        val host = Regex("""https?://([^/:]+)""").find(u)?.groupValues?.get(1) ?: return u
        return host.replace(".space", "").replace(".cyou", "")
            .replace(".website", "").substringBeforeLast('.').ifBlank { host }
    }
}