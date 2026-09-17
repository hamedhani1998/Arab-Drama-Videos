package com.threesk.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log

class ThreeSk : MainAPI() {
    override var mainUrl = "https://3iskk.xyz"
    override var name = "قصة عشق"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    private fun Element.toSearchResponse(): SearchResponse? {
        val encodedUrl = this.attr("data-clse")
        val href = if (encodedUrl.isNotBlank()) {
            try {
                try {
                    String(android.util.Base64.decode(encodedUrl, android.util.Base64.DEFAULT))
                } catch (_: Exception) {
                    try {
                        String(android.util.Base64.decode(encodedUrl, android.util.Base64.URL_SAFE))
                    } catch (_: Exception) {
                        String(android.util.Base64.decode(encodedUrl, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
                    }
                }
            } catch (e: Exception) {
                Log.e("ThreeSk", "Base64 decoding failed for '$encodedUrl'. Falling back to href. Error: ${e.message}")
                this.attr("href")
            }
        } else {
            this.attr("href")
        }

        if (href.isBlank()) return null
        val title = this.attr("title")
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
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val all = ArrayList<HomePageList>()

        document.select("section.home-items-sec").forEach { section ->
            val title = section.selectFirst(".sec-title")?.text() ?: return@forEach
            val items = section.select("li.type_item_box a.type_item, li.type_item_wide_box a.type_item_wide")
                .mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                all.add(HomePageList(title, items))
            }
        }
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        val document = app.get(url).document
        return document.select("ul.search-page li.type_item_box a.type_item").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun decodeBase64Compat(encoded: String): String? {
        var s = encoded.trim()
        val mod = s.length % 4
        if (mod != 0) {
            s += "=".repeat(4 - mod)
        }
        val flagsToTry = listOf(
            android.util.Base64.DEFAULT,
            android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        for (flags in flagsToTry) {
            try {
                val bytes = android.util.Base64.decode(s, flags)
                return try {
                    String(bytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    String(bytes)
                }
            } catch (ignored: IllegalArgumentException) {
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/episodes/")) {
            val episodePage = app.get(url).document
            val seriesUrl = episodePage.selectFirst("a.single-serie-btn")?.attr("href")
            if (seriesUrl.isNullOrBlank()) return null
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val title = document.selectFirst("div.single_info h1.title")?.text()
            ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?: return null

        val poster = document.selectFirst("div.poster-wrapper img")?.attr("src")
        val description = document.selectFirst("div.description span[data-nosnippet]")?.text()
        val tvType = if (url.contains("/tvshows/")) TvType.TvSeries else TvType.Movie

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = ArrayList<Episode>()

        document.select("div.season-eps").forEach { seasonDiv ->
            val seasonNum = seasonDiv.attr("id").removePrefix("season-num-").toIntOrNull() ?: 1

            seasonDiv.select("a.ep-num").forEach { epA ->
                val rawUrl = epA.attr("data-clse").ifBlank { epA.attr("href") }
                if (rawUrl.isBlank()) return@forEach

                val epUrl = if (rawUrl.startsWith("http")) {
                    rawUrl
                } else {
                    try {
                        decodeBase64Compat(rawUrl) ?: epA.attr("href")
                    } catch (e: Exception) {
                        epA.attr("href")
                    }
                }

                val epNum = epA.attr("data-ep-num").toIntOrNull()
                val epName = epA.attr("title").ifBlank { "الحلقة $epNum" }

                episodes.add(
                    newEpisode(epUrl) {
                        name = epName
                        episode = epNum
                        season = seasonNum
                        posterUrl = poster
                    }
                )
            }
        }

        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, tvType, episodes.sortedBy { it.episode }) {
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
                        esc == "\\\\" -> "\\"
                        else -> if (esc.length >= 2 && esc[0] == '\\') esc.substring(1) else esc
                    }
                } catch (_: Exception) { esc }
            }
        }

        fun intToBase36(n0: Int): String {
            if (n0 == 0) return "0"
            var n = n0
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % 36])
                n /= 36
            }
            return sb.reverse().toString()
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
                    if (idx + 1 < text.length) {
                        out.append(text.substring(idx, idx + 2))
                        idx += 2
                    } else { idx++ }
                } else if (ch == quote) {
                    return Pair(jsStringUnescape(out.toString()), idx + 1)
                } else {
                    out.append(ch)
                    idx++
                }
            }
            return Pair(null, idx)
        }

        fun findMatchingBrace(text: String, startIdx: Int): Int {
            if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return -1
            var depth = 0
            var i = startIdx
            while (i < text.length) {
                val ch = text[i]
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        fun unpackPackerFromEval(evalText: String): Pair<String?, String?> {
            try {
                val startFn = evalText.indexOf("function(p,a,c,k,e,d)")
                if (startFn == -1) return Pair(null, "no function signature")
                val braceOpen = evalText.indexOf('{', startFn)
                if (braceOpen == -1) return Pair(null, "no opening brace")
                val braceClose = findMatchingBrace(evalText, braceOpen)
                if (braceClose == -1) return Pair(null, "no matching brace")
                val argsStart = evalText.indexOf('(', braceClose)
                if (argsStart == -1) return Pair(null, "no args start")
                var i = argsStart + 1
                while (i < evalText.length && evalText[i].isWhitespace()) i++
                val (pVal, newI) = parseJsStringAt(evalText, i); i = newI
                if (pVal == null) return Pair(null, "cannot parse p string")
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val aMatch = Regex("""\d+""").find(evalText.substring(i))
                if (aMatch == null) return Pair(null, "cannot parse a")
                val aVal = aMatch.value.toInt()
                i += aMatch.range.last + 1
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val cMatch = Regex("""\d+""").find(evalText.substring(i))
                if (cMatch == null) return Pair(null, "cannot parse c")
                val cVal = cMatch.value.toInt()
                i += cMatch.range.last + 1
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val kList = mutableListOf<String>()
                if (i < evalText.length && (evalText[i] == '"' || evalText[i] == '\'')) {
                    val (kStr, i2) = parseJsStringAt(evalText, i)
                    i = i2
                    if (kStr != null) { kList.addAll(kStr.split("|")) }
                } else {
                    val m2 = Regex("""(['"])(.*?)\1\s*\.split\s*\(\s*['"]\|['"]\s*\)""", RegexOption.DOT_MATCHES_ALL).find(evalText)
                    if (m2 != null) { kList.addAll(m2.groupValues[2].split("|")) }
                }
                var p = pVal
                for (idx in cVal - 1 downTo 0) {
                    val key = intToBase36(idx)
                    if (idx < kList.size && kList[idx].isNotEmpty()) {
                        p = Regex("\\b" + Regex.escape(key) + "\\b").replace(p ?: "") { kList[idx] }
                    }
                }
                return Pair(p, null)
            } catch (e: Exception) {
                return Pair(null, "exception:${e.message}")
            }
        }

        fun analyzeAndSaveEvalScripts(htmlText: String): List<String> {
            try {
                val doc = org.jsoup.Jsoup.parse(htmlText)
                val scripts = doc.select("script")
                val found = mutableListOf<String>()
                for (s in scripts) {
                    val content = s.data().ifBlank { s.html() }
                    if (content.contains("eval(")) {
                        val m = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{""").find(content)
                        if (m != null) {
                            val start = m.range.first
                            val sample = if (content.length > start + 10000) content.substring(start, start + 10000) else content.substring(start)
                            val (unpacked, _) = unpackPackerFromEval(sample)
                            if (unpacked != null) {
                                val mediaRegex = Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                                mediaRegex.findAll(unpacked).forEach { found.add(it.groupValues[1]) }
                            }
                        }
                    }
                }
                return found
            } catch (e: Exception) {
                Log.e("ThreeSk", "analyzeAndSaveEvalScripts error", e)
                return emptyList()
            }
        }

        fun getAllIframeSrcs(doc: org.jsoup.nodes.Document): List<String> {
            return doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
        }

        suspend fun processSingleEmbedServer(
            embedUrl: String,
            refererFromPrevPage: String,
            headersBase: Map<String, String>,
            serverLabel: String = "unknown"
        ): Set<String> {
            val result = mutableSetOf<String>()
            try {
                val hdrs = headersBase.toMutableMap()
                hdrs["Referer"] = refererFromPrevPage
                val rIf1 = try {
                    app.get(embedUrl, referer = refererFromPrevPage, headers = hdrs)
                } catch (e: Exception) {
                    Log.w("ThreeSk", "GET embed $embedUrl failed", e)
                    return result
                }
                val text1 = rIf1.text

                Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                    .findAll(text1).forEach { result.add(it.groupValues[1]) }

                analyzeAndSaveEvalScripts(text1).forEach { result.add(it) }

                val docIf1 = rIf1.document
                val iframe1Srcs = getAllIframeSrcs(docIf1)
                if (iframe1Srcs.isNotEmpty()) {
                    val iframe2Src = iframe1Srcs[0]
                    val hdrs2 = hdrs.toMutableMap()
                    hdrs2["Referer"] = embedUrl
                    val rFinal = try {
                        app.get(iframe2Src, referer = embedUrl, headers = hdrs2)
                    } catch (e: Exception) { null }
                    if (rFinal != null) {
                        val t = rFinal.text
                        Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
                            .findAll(t).forEach { result.add(it.groupValues[1]) }
                        analyzeAndSaveEvalScripts(t).forEach { result.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e("ThreeSk", "processSingleEmbedServer exception", e)
            }
            return result
        }

        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            val r0 = app.get(data, headers = headers)
            val soup0 = r0.document

            var watchForm = soup0.selectFirst("button.single-watch-btn")?.parent()
            if (watchForm == null) {
                for (f in soup0.select("form")) {
                    val act = f.attr("action")
                    if (act.contains("3isk") || act.contains("aa.3isk") || act.contains("watch")) {
                        watchForm = f
                        break
                    }
                }
            }
            if (watchForm == null) {
                Log.e("ThreeSk", "No watch form found")
                return false
            }

            val firstPostUrl = watchForm.attr("action")
            val firstFormData = watchForm.select("input[type=hidden]")
                .associate { it.attr("name") to it.attr("value") }.toMutableMap()

            val watchBtn = soup0.selectFirst("button.single-watch-btn")
            if (watchBtn != null) {
                val btnName = watchBtn.attr("name")
                if (btnName.isNotBlank()) firstFormData[btnName] = watchBtn.attr("value")
            }

            val r1 = app.post(firstPostUrl, data = firstFormData, referer = data, headers = headers)
            val mMyurl = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(r1.text)
            val mNews = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(r1.text)
            if (mMyurl == null || mNews == null) {
                Log.e("ThreeSk", "Failed to extract myUrl or news from POST1")
                return false
            }

            val nextPost = mMyurl.groupValues[1]
            val newsVal = mNews.groupValues[1]

            val r2 = app.post(nextPost, data = mapOf("news" to newsVal, "u" to "", "submit" to "submit"), referer = r1.url, headers = headers)
            val soup2 = r2.document
            val iframeSrcsOnR2 = getAllIframeSrcs(soup2)
            if (iframeSrcsOnR2.isEmpty()) {
                Log.e("ThreeSk", "No iframe found on page after POST2")
                return false
            }

            val baseIframeSrc = iframeSrcsOnR2[0]
            val foundAllMediaLinks = mutableMapOf<String, MutableSet<String>>()

            val embedMatch = Regex("""(https://3iskk\.xyz/embed/)(\d+)/(.*)""").find(baseIframeSrc)
            if (embedMatch != null) {
                val baseUrlPrefix = embedMatch.groupValues[1]
                val trailingPart = embedMatch.groupValues[3]
                for (serverNum in 1..5) {
                    val currentEmbedUrl = "$baseUrlPrefix$serverNum/$trailingPart"
                    val mediaLinks = processSingleEmbedServer(currentEmbedUrl, r2.url, headers, serverLabel = serverNum.toString())
                    if (mediaLinks.isNotEmpty()) {
                        mediaLinks.forEach { link ->
                            foundAllMediaLinks.getOrPut(link) { mutableSetOf() }.add(serverNum.toString())
                        }
                    }
                }
            } else {
                val mediaLinks = processSingleEmbedServer(baseIframeSrc, r2.url, headers, serverLabel = "base")
                if (mediaLinks.isNotEmpty()) {
                    mediaLinks.forEach {
                        foundAllMediaLinks.getOrPut(it) { mutableSetOf() }.add("base")
                    }
                }
            }

            if (foundAllMediaLinks.isEmpty()) {
                Log.e("ThreeSk", "No media links were extracted")
                return false
            }

            for ((link, servers) in foundAllMediaLinks) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - سيرفر ${servers.joinToString(",")}",
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true

        } catch (e: Exception) {
            Log.e("ThreeSk", "Unexpected error in loadLinks", e)
            return false
        }
    }
}
