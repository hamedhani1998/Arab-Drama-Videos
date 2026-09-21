package com.lodynet.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log

class LodyProvider : MainAPI() {
    companion object {
        private const val TAG = "LodyNet"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // Lody Plus (id 73) is filled server-side at watch-time only (Encrypted,
        // empty Embed) — we can't reproduce the AES fetch, so we skip it.
        private const val LODY_PLUS_ID = 73
        private const val VIDLO_ID = 116413
    }

    override var mainUrl = "https://lodynet.top"
    override var name = "لودي نت LODYNET"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    // ---------- P.A.C.K.E.R. unpacker (port of CloudStream's JsUnpacker) ----------
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

    // scan a (possibly packed) script for direct HLS/MP4 URLs
    fun extractMediaUrls(htmlText: String): List<String> {
        val urls = mutableListOf<String>()
        Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
            .findAll(htmlText).map { it.groupValues[1] }.forEach { urls += it.unescapeUrl() }
        // source: [ { file: "..." } ] pattern (most video.js / resolve players)
        Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(htmlText).map { it.groupValues[1] }.forEach { urls += it.unescapeUrl() }
        // P.A.C.K.E.R. blobs
        try {
            val doc = org.jsoup.Jsoup.parse(htmlText)
            for (s in doc.select("script")) {
                val content = s.data().ifBlank { s.html() }
                if (content.contains("eval(")) {
                    val m = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{""").find(content)
                    if (m == null) continue
                    val start = m.range.first
                    val sample = if (content.length > start + 20000) content.substring(start, start + 20000) else content.substring(start)
                    val unpacked = unpackPackerFromEval(sample) ?: continue
                    Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                        .findAll(unpacked).map { it.groupValues[1] }.forEach { urls += it.unescapeUrl() }
                }
            }
        } catch (_: Exception) {}
        return urls.distinct().filter { it.startsWith("http") }
    }

    // decode \/ and %-escapes back to a usable URL
    fun String.unescapeUrl(): String =
        replace("\\/", "/").replace("\\\\", "\\")

    fun originOf(u: String): String =
        Regex("""https?://[^/"']+""").find(u)?.value ?: u

    // ---- base64 decoder (standard b64, no url-safe variants expected) ----
    fun base64Decode(input: String): String? =
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
                if (bits >= 8) { bits -= 8; bytes += ((buffer shr bits) and 0xFF).toByte() }
            }
            val out = ByteArray(bytes.size)
            for (i in out.indices) out[i] = bytes[i]
            String(out, Charsets.UTF_8)
        } catch (_: Exception) { null }

    // ---------- Card parsing ----------
    private fun Element.card(): SearchResponse? {
        val href = this.attr("href")
        val title = this.attr("title").ifBlank { text() }.trim()
        val cover = this.selectFirst(".NewlyCover, .Cover, .LoadingCover, [class*=Cover]")
            ?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("data-src")?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("src")
        if (href.isBlank() || title.isBlank()) return null
        val isEpisode = href.contains("ep") || title.contains("الحلقة") || href.matches(Regex(".*-s\\d+.*"))
        return if (isEpisode)
            newTvSeriesSearchResponse(title, href) { this.posterUrl = cover }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = cover }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = try { app.get("$mainUrl/").document } catch (_: Exception) { return newHomePageResponse(emptyList()) }
        val cards = doc.select("a[href*='lodynet.top']").mapNotNull { it.card() }
            .distinctBy { it.url }
        // Home has 2 sections — report as one "مضاف حديثا" row (most relevant).
        return if (cards.isEmpty()) newHomePageResponse(emptyList())
        else newHomePageResponse(listOf(HomePageList("مضاف حديثا", cards)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim()}"
        val doc = try { app.get(url).document } catch (_: Exception) { return emptyList() }
        return doc.select("a[href*='lodynet.top']").mapNotNull { it.card() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = try { app.get(url).document } catch (_: Exception) { return null }
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("img[src*='/wp-content/uploads/']")?.attr("data-src")?.ifBlank { null }
            ?: doc.selectFirst("img[src*='/wp-content/uploads/']")?.attr("src")
        val plot = doc.selectFirst("[class*=description], [class*=sinops], [class*=summary], [class*=Details], p")?.text()
            ?.takeIf { it.length > 15 }?.take(600)

        // Series page: #SpaceEpisodes > #ListEpisodes > a.ItemEpisode (title="الحلقة N")
        val episodeLinks = doc.select("a.ItemEpisode[href*='lodynet.top']").mapNotNull { a ->
            val epUrl = a.attr("href").ifBlank { return@mapNotNull null }
            val epText = a.text().ifBlank { a.attr("title") }
            val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) {
                name = epText
                episode = epNum
                posterUrl = poster
            }
        }

        return when {
            episodeLinks.isNotEmpty() ->
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeLinks.sortedBy { it.episode }) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            // Single-page film (or lone episode): movie load response
            // so loadLinks parses PostData on the page itself.
            else ->
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.plot = plot
                }
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
            val doc = app.get(data, headers = mapOf("User-Agent" to UA)).document
            val pageText = doc.toString()

            // Parse ServersWatch JSON array + PageData tokens from the two <script> blocks.
            val servers = Regex("""ServersWatch\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                .findAll(pageText).firstOrNull { it.groupValues[1].contains("\"Name\"") }
                ?.groupValues?.get(1) ?: run {
                Log.w(TAG, "no ServersWatch array on $data")
                return false
            }
            val tokenPlus1 = Regex("""TokenPlus1\s*:\s*"([^"]*)"\s*""").find(pageText)?.groupValues?.get(1).orEmpty()
            val tokenVidlo = Regex("""TokenVidlo\s*:\s*"([^"]*)"\s*""").find(pageText)?.groupValues?.get(1).orEmpty()
            Log.d(TAG, "tokens: plus1='$tokenPlus1' vidlo='$tokenVidlo'")

            // Parse each {Name, Embed, Id, Encrypted}
            data class Server(val name: String, val embed: String, val id: Int, val encrypted: Boolean)
            val parsed = mutableListOf<Server>()
            val objRe = Regex("""\{[^{}]*"Name"\s*:\s*"([^"]*)"[^{}]*\}""")
            for (m in objRe.findAll(servers)) {
                val name = m.groupValues[1]
                val idR = Regex("""Id\s*:\s*(\d+)""").find(m.value)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val embR = Regex("""Embed\s*:\s*"([^"]*)"\s*""").find(m.value)?.groupValues?.get(1).orEmpty()
                val encR = Regex("""Encrypted\s*:\s*(true|false)""").find(m.value)?.groupValues?.get(1) == "true"
                parsed.add(Server(name, embR, idR, encR))
            }
            Log.d(TAG, "servers: ${parsed.joinToString(" | ") { it.name + "(" + it.id + ")" }}")

            val emitted = mutableSetOf<String>()
            parsed.forEach { server ->
                if (server.id == LODY_PLUS_ID) {
                    Log.d(TAG, "skip Lody Plus (id 73) — encrypted/empty Embed")
                    return@forEach
                }
                val decoded = base64Decode(server.embed) ?: run {
                    Log.w(TAG, "cannot b64-decode ${server.name}")
                    return@forEach
                }
                val embedFull = when (server.id) {
                    VIDLO_ID -> decoded + tokenVidlo
                    else -> decoded
                }
                if (!embedFull.isBlank() && emitted.add(embedFull)) {
                    emitEmbed(embedFull, originOf(decoded), server.name, callback)
                }
            }
            return emitted.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error", e)
            return false
        }
    }

    private suspend fun emitEmbed(
        embedUrl: String,
        refererFromPrev: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedOrigin = originOf(embedUrl)
        val emitCb: suspend (ExtractorLinkType, String, String) -> Unit =
            { type, url, ref ->
                callback.invoke(
                    newExtractorLink(
                        source = "لودي نت",
                        name = label,
                        url = url,
                        type = type
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = ref
                        this.headers = mapOf("User-Agent" to UA, "Referer" to ref, "Accept" to "*/*")
                    }
                )
            }
        try {
            val hdrs = mapOf("User-Agent" to UA, "Referer" to refererFromPrev)
            val r = app.get(embedUrl, referer = refererFromPrev, headers = hdrs)
            val text = r.text
            Log.d(TAG, "[$label] GET ${r.url ?: "?"} -> ${r.code} len=${text.length}")
            // Live 404 (vidlo endpoint gone, expired-cert hosts) → skip entirely.
            if (r.code == 404 || r.code == 410 || text.length < 100) {
                Log.w(TAG, "[$label] dead embed (${r.code}, len=${text.length}) — skipping")
                return
            }
            val media = extractMediaUrls(text)
            // vidlo.us/token style HLS in a "sources" var
            val srcList = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*"([^"]+)"\s*""", RegexOption.IGNORE_CASE)
                .findAll(text).map { it.groupValues[1] }.toList()
            val allMedia = (media + srcList).distinct()
            if (allMedia.isEmpty()) {
                Log.d(TAG, "[$label] no HLS/MP4 — emit embed page as VIDEO fallback")
                emitCb(ExtractorLinkType.VIDEO, embedUrl, refererFromPrev)
                return
            }
            allMedia.forEach { mediaUrl ->
                val m3u8 = mediaUrl.contains(".m3u8")
                emitCb(if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, mediaUrl, embedOrigin)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$label] embed unreachable (${e.message ?: ""}) — skipping")
        }
    }

    private fun hostLabel(u: String): String {
        val host = Regex("""https?://([^/:]+)""").find(u)?.groupValues?.get(1) ?: return u
        val cleaned = host.replace(".onl", "").replace(".com", "").replace(".org", "").replace(".to", "")
            .substringBeforeLast('.')
        return cleaned.ifBlank { host }
    }
}