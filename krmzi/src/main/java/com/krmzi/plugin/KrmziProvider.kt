package com.krmzi.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.Request
import org.jsoup.nodes.Element
import android.util.Log

class KrmziProvider : MainAPI() {
    companion object {
        private const val TAG = "Krmzi.org"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://krmzi.org"
    override var name = "قرمزي"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    // ---------- P.A.C.K.E.R. unpacker (shared, mirror of kirmzi's) ----------
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

    // ---------- Card parsing ----------
    private fun compactTitle(titleAttr: String): String =
        titleAttr
            .replace(" - قرمزي", "").trim()
            .let { Regex("""^مسلسل\s+""").replace(it, "").lineSequence().joinToString(" ") }

    private fun Element.searchCard(): SearchResponse? {
        val href = this.attr("href")
        val title = this.attr("title").ifBlank { this.selectFirst(".title")?.text().orEmpty() }

        // Extract poster: try multiple sources
        // 1. .posterThumb .imgBg style background-image
        // 2. img data-src (lazy load)
        // 3. img src
        val posterImg = this.selectFirst(".posterThumb .imgBg, .posterThumb, img")
        val poster = when {
            // Try style background-image first
            posterImg?.attr("style")?.let { style ->
                Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.groupValues?.get(1)
            } != null -> posterImg?.attr("style")?.let {
                Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1)
            } ?: ""
            // Try data-src
            posterImg?.attr("data-src")?.isNotEmpty() == true -> posterImg?.attr("data-src") ?: ""
            // Try src
            posterImg?.attr("src")?.isNotEmpty() == true -> posterImg?.attr("src") ?: ""
            else -> ""
        }

        if (href.isBlank() || title.isBlank()) return null
        return if (href.contains("/episode/") || href.contains("/series/"))
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster.ifBlank { null }
            }
        else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val navUrl = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val doc = app.get(navUrl).document
        // Only show series, not individual episodes
        val items = doc.select("a[href*=/series/]")
            .mapNotNull { it.searchCard() }
        return newHomePageResponse(listOf(HomePageList("المسلسلات", items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim()}"
        val doc = app.get(url).document
        // Filter by series only, not individual episodes
        return doc.select("a[href*=/series/]")
            .filter { it.attr("href").length > 8 }
            .mapNotNull { it.searchCard() }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Episode page -> resolve to series page (via the episode's /series/ link).
        if (url.contains("/episode/")) {
            val epDoc = app.get(url).document
            val seriesUrl = epDoc.selectFirst("a[href*=/series/]")?.attr("href")
            if (seriesUrl.isNullOrBlank()) return null
            return load(seriesUrl)
        }
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null

        // Extract poster from multiple sources
        val poster = doc.selectFirst("[style*='background-image:url']")?.attr("style")?.let {
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1)
        } ?: doc.selectFirst("img[src*=/wp-content/uploads/]")?.attr("src")
            ?.ifBlank { null }
            ?: doc.selectFirst("img")?.attr("data-src")
            ?.ifBlank { null }
            ?: doc.selectFirst("img")?.attr("src")

        val description = doc.selectFirst("[class*=description], [class*=sinops], [class*=story], [class*='summary']")?.text()

        val episodes = ArrayList<Episode>()
        doc.select("a[href*=/episode/]").forEach { a ->
            val epUrl = a.attr("href").ifBlank { return@forEach }
            val epTitle = a.attr("title").ifBlank { a.text() }
            val epNum = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/(\d+)/$""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
            episodes.add(
                newEpisode(epUrl) {
                    name = if (epTitle.isNotBlank()) compactTitle(epTitle)
                    else "الحلقة ${epNum ?: episodes.size + 1}"
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
        try {
            // ---- Pass 1: cheap static fetch (in case Turnstile is thin/off) ----
            val embedBase = mutableListOf<Pair<String, String>>() // (embedUrl, refererEmbed)
            val staticDoc = try { app.get(data, headers = mapOf("User-Agent" to UA)).document } catch (_: Exception) { null }
            if (staticDoc != null) {
                staticDoc.select("li[data-server]").forEach { li ->
                    val id = li.attr("data-server")
                    val nm = li.attr("data-name")
                    if (id.isNotBlank()) {
                        val url = when (nm) {
                            "Arab HD" -> "https://arabhd.onl/embed-$id.html"
                            "estream" -> "https://arabveturk.com/embed-$id.html"
                            "box" -> "https://youdboox.com/embed-$id.html"
                            "now" -> "https://extreamnow.org/embed-$id.html"
                            "Red HD" -> "https://iplayerhls.com/e/$id"
                            "Pro HD" -> "https://embedo.co/e/$id"
                            "ok" -> "https://ok.ru/videoembed/$id"
                            else -> ""
                        }
                        if (url.isNotBlank()) embedBase += (url to mainUrl)
                    }
                }
            }

            // ---- Pass 2: Turnstile-gated → solve via hidden WebView, capture embed reqs ----
            if (embedBase.isEmpty()) {
                Log.d(TAG, "static had no data-server list → WebViewResolver path")
                val embedRe = Regex("""(?:https?://)?(?:arabhd\.onl|arabveturk\.com|youdboox\.com|extreamnow\.org|iplayerhls\.com|embedo\.co|ok\.ru)/[^\s"']+""")
                val resolver = WebViewResolver(
                    interceptUrl = embedRe,
                    additionalUrls = listOf(embedRe),
                    userAgent = null,
                    useOkhttp = false,
                    script = null,
                    scriptCallback = null,
                    timeout = WebViewResolver.DEFAULT_TIMEOUT
                )
                val (primary, extras) = resolver.resolveUsingWebView(data, referer = mainUrl, headers = emptyMap(), method = "GET") { req ->
                    Log.d(TAG, "WV req: ${req.url}")
                    true
                }
                val captured = (listOfNotNull(primary) + extras)
                    .map { it.url.toString() }
                    .filter { embedRe.containsMatchIn(it) }
                    .distinct()
                Log.d(TAG, "WebView captured embed URLs: $captured")
                captured.forEach { embedBase += (it to mainUrl) }
            }

            if (embedBase.isEmpty()) {
                Log.e(TAG, "No server/embed URLs found for $data")
                return false
            }

            // ---- Unpack each embed -> HLS, emit ----
            val seen = mutableSetOf<String>()
            embedBase.distinctBy { it.first }.forEach { (embedUrl, ref) ->
                val label = hostLabel(embedUrl)
                try {
                    val hdrs = mapOf("User-Agent" to UA, "Referer" to ref)
                    val r = app.get(embedUrl, referer = ref, headers = hdrs)
                    val text = r.text
                    Log.d(TAG, "[$label] GET ${r.url ?: "-"} -> ${r.code} len=${text.length}")
                    val direct = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                        .findAll(text).map { it.groupValues[1] }.toList()
                    val unpacked = analyzeAndUnpackScripts(text)
                    val allMedia = (direct + unpacked).distinct()
                    if (allMedia.isEmpty()) {
                        Log.d(TAG, "[$label] no media inside → emit embed page as VIDEO fallback")
                        emitter(callback, label, embedUrl, ExtractorLinkType.VIDEO, originOf(embedUrl))
                        return@forEach
                    }
                    allMedia.forEach { mediaUrl ->
                        val m3u8 = mediaUrl.contains(".m3u8")
                        emitter(callback, label, mediaUrl, if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, originOf(embedUrl))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$label] embed GET/emit failed: $e")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error", e)
            return false
        }
    }

    private suspend fun emitter(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        type: ExtractorLinkType,
        referer: String
    ) {
        callback.invoke(
            newExtractorLink(
                source = "قرمزي",
                name = label,
                url = url,
                type = type
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Accept" to "*/*",
                    "Referer" to referer
                )
            }
        )
    }

    private fun hostLabel(u: String): String {
        val host = Regex("""https?://([^/:]+)""").find(u)?.groupValues?.get(1) ?: return u
        val cleaned = host.replace(".onl", "").replace(".com", "").replace(".org", "")
            .substringBeforeLast('.')
        return cleaned.ifBlank { host }
    }
}