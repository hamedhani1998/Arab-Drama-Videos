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
    override var name = "قرمزي ORG"
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

    // ---- Pure-Kotlin base64url decoder (no android.util.Base64 dependency) ----
    fun base64UrlDecode(input: String): String? =
        try {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val s = input.replace('-', '+').replace('_', '/')
            val pad = if (s.length % 4 == 1) null else (4 - s.length % 4) % 4
            if (pad == null) return null
            val padded = s + "=".repeat(pad)
            val clean = padded.filter { it != '=' }
            if (clean.any { chars.indexOf(it) == -1 }) return null
            val bytes = ArrayList<Byte>(clean.length * 3 / 4)
            var buffer = 0
            var bits = 0
            for (ch in clean) {
                buffer = (buffer shl 6) or chars.indexOf(ch)
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    bytes += ((buffer shr bits) and 0xFF).toByte()
                }
            }
            val out = ByteArray(bytes.size)
            for (i in out.indices) out[i] = bytes[i]
            String(out, Charsets.UTF_8)
        } catch (_: Exception) { null }

    // ---------- Card parsing ----------
    private fun compactTitle(titleAttr: String): String =
        titleAttr
            .replace(" - قرمزي", "").trim()
            .let { Regex("""^مسلسل\s+""").replace(it, "").lineSequence().joinToString(" ") }

    private fun Element.searchCard(): SearchResponse? {
        val href = this.attr("href")
        val title = this.attr("title").ifBlank { this.selectFirst(".title")?.text().orEmpty() }

        // Poster: krmzi series-list cards put it in .poster .imgSer background-image;
        // home episode cards use .posterThumb .imgBg; some cards use <img> with
        // data-src (lazy) or src.
        val styleHolder = this.selectFirst(".imgSer, .poster .imgSer, .posterThumb .imgBg, [style*='background-image']")
        val img = this.selectFirst("img")
        val poster = styleHolder?.attr("style")?.let {
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1)
        }?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("src")

        if (href.isBlank() || title.isBlank()) return null
        return if (href.contains("/series/") || href.contains("/episode/"))
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
            }
        else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // krmzi.org's homepage shows only latest-episode cards (which the user
        // does not want). /series-list/ lists every series once with a real
        // poster. Page 1 holds the newest 40, page 2 the next 40 — present
        // both as separate home rows so the home screen has more than one section.
        val docs = try {
            val p1 = app.get("$mainUrl/series-list/").document
            val p2 = app.get("$mainUrl/series-list/page/2/").document
            listOf(p1, p2)
        } catch (_: Exception) {
            return newHomePageResponse(emptyList())
        }
        fun section(doc: org.jsoup.nodes.Document): List<SearchResponse> {
            val seen = mutableSetOf<String>()
            return doc.select("a[href*=/series/]")
                .mapNotNull { it.searchCard() }
                .filter { seen.add(it.url) }
        }
        return newHomePageResponse(
            listOf(
                HomePageList("المسلسلات", section(docs[0])),
                HomePageList("المزيد من المسلسلات", section(docs[1]))
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim()}"
        val doc = app.get(url).document
        // Search returns episode cards; keep series links so each series appears
        // once (load() resolves episode URLs to their series).
        return doc.select("a[href*=/series/], a[href*=/episode/]")
            .filter { it.attr("href").length > 8 }
            .mapNotNull { it.searchCard() }
            .distinctBy { it.url }
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
            // krmzi.org no longer embeds the server list in the DOM. Each episode
            // page carries a single link `qesen.net/krmzi?post=<base64url JSON>`
            // whose JSON holds every server's name + id:
            //   {"servers":[{"name":"Arab HD","id":"m587mbkexitv"}, ...], "postID":"7469", ...}
            val epDoc = app.get(data, headers = mapOf("User-Agent" to UA)).document
            val qesenHref = epDoc.selectFirst("a[href*='qesen.net/krmzi']")?.attr("href")
                ?: epDoc.selectFirst("a[href*='qesen.net']")?.attr("href").orEmpty()
            val b64 = Regex("""qesen\.net/krmzi\?post=([A-Za-z0-9_\-]+)""")
                .find(qesenHref)?.groupValues?.get(1)
            if (b64.isNullOrBlank()) {
                Log.w(TAG, "no qesen server JSON on episode page")
                return false
            }
            val decoded = runCatching {
                base64UrlDecode(b64)
            }.getOrNull()
            if (decoded.isNullOrBlank()) {
                Log.e(TAG, "qesen JSON base64 decode failed")
                return false
            }
            val servers = runCatching {
                val arr = org.json.JSONObject(decoded).optJSONArray("servers") ?: org.json.JSONArray()
                val out = mutableListOf<Pair<String, String>>() // (name, id)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val n = o.optString("name", "").trim()
                    val id = o.optString("id", "").trim()
                    if (n.isNotBlank() && id.isNotBlank()) out += n to id
                }
                out
            }.getOrDefault(emptyList())
            Log.d(TAG, "servers from qesen JSON: $servers")
            if (servers.isEmpty()) return false

            // ---- Map each server name to its embed URL (mirror of theme's core.js) ----
            fun embedFor(name: String, id: String): String? = when (name) {
                "Arab HD" -> "https://arabhd.onl/embed-$id.html"
                "estream" -> "https://arabveturk.com/embed-$id.html"
                "box" -> "https://youdboox.com/embed-$id.html"
                "now" -> "https://extreamnow.org/embed-$id.html"
                "Red HD" -> "https://iplayerhls.com/e/$id"
                "Pro HD" -> "https://embedo.co/e/$id"
                "ok" -> "https://ok.ru/videoembed/$id"
                "express" -> id // cloud.mail.ru direct link carried in the id
                else -> null
            }

            val emitted = mutableSetOf<String>()
            servers.forEach { (name, id) ->
                val embedUrl = embedFor(name, id)
                if (embedUrl.isNullOrBlank()) {
                    Log.w(TAG, "unknown server name '$name', id='$id' — skipping")
                    return@forEach
                }
                try {
                    if (name == "express") {
                        // cloud.mail.ru is a direct video page; emit it as VIDEO (playable via WebView)
                        if (emitted.add(id)) emitter(callback, name, id, ExtractorLinkType.VIDEO, mainUrl)
                        return@forEach
                    }
                    val ref = when (name) {
                        "ok" -> "https://ok.ru/"
                        else -> originOf(embedUrl)
                    }
                    val r = app.get(embedUrl, referer = mainUrl, headers = mapOf("User-Agent" to UA, "Referer" to mainUrl))
                    val text = r.text
                    Log.d(TAG, "[$name] GET ${r.url ?: "-"} -> ${r.code} len=${text.length}")
                    // direct m3u8/mp4 in page, if present
                    val direct = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                        .findAll(text).map { it.groupValues[1] }.toList()
                    // P.A.C.K.E.R. player script -> unpack -> read sources:[{file:"<m3u8>"}]
                    val unpacked = analyzeAndUnpackScripts(text)
                    val sourcesFile = unpacked.filter { it.contains(".m3u8") }.ifEmpty {
                        Regex("""sources\s*:\s*\[\s*\{\s*file:\s*"([^"]+\.m3u8[^"]*)"\s*""", RegexOption.IGNORE_CASE)
                            .find(text)?.groupValues?.get(1)?.let { listOf(it) } ?: emptyList()
                    }
                    val allMedia = (direct + sourcesFile).distinct()
                    if (allMedia.isEmpty()) {
                        Log.d(TAG, "[$name] no media inside → emit embed page as VIDEO fallback")
                        if (emitted.add(embedUrl)) emitter(callback, name, embedUrl, ExtractorLinkType.VIDEO, originOf(embedUrl))
                        return@forEach
                    }
                    allMedia.forEach { mediaUrl ->
                        if (emitted.add(mediaUrl)) {
                            val m3u8 = mediaUrl.contains(".m3u8")
                            emitter(callback, name, mediaUrl, if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, ref)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$name] embed GET/emit failed: $e")
                }
            }
            return emitted.isNotEmpty()
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
                source = "قرمزي ORG",
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