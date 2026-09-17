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
            href.contains("/episodes/") || href.contains("/watch/episodes/") -> {
                val seriesTitle = title.substringBefore(" الحلقة").trim()
                newTvSeriesSearchResponse(seriesTitle.ifBlank { title }, href) { this.posterUrl = posterUrl }
            }
            href.contains("/seasons/") -> newTvSeriesSearchResponse(title, href) { this.posterUrl = posterUrl }
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
        return document.select("ul.search-page li.type_item_box a.type_item, li.type_item_wide_box a.type_item_wide").mapNotNull {
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
                return String(bytes, Charsets.UTF_8)
            } catch (ignored: IllegalArgumentException) {
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/episodes/") || url.contains("/watch/episodes/")) {
            val episodePage = app.get(url).document
            val seriesUrl = episodePage.selectFirst("a.single-serie-btn")?.attr("href")
            if (seriesUrl.isNullOrBlank()) return null
            return load(seriesUrl)
        }

        val document = app.get(url).document
        val title = document.selectFirst("div.single_info h1.title")?.text()
            ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?: document.selectFirst("h1.title")?.text()
                ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?: return null

        val poster = document.selectFirst("div.poster-wrapper img")?.attr("src")
        val description = document.selectFirst("div.description span[data-nosnippet]")?.text()
            ?: document.selectFirst(".description")?.text()
        val tvType = if (url.contains("/tvshows/") || url.contains("/seasons/")) TvType.TvSeries else TvType.Movie

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = ArrayList<Episode>()

        document.select("div.season-eps, .season-eps").forEach { seasonDiv ->
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/"
        )

        try {
            val r0 = app.get(data, headers = headers)
            val soup0 = r0.document

            val watchForm = soup0.selectFirst("form[method=post]")?.let { form ->
                val action = form.attr("action")
                if (action.contains("3isk") || action.contains("watch")) form else null
            }

            if (watchForm == null) {
                Log.e("ThreeSk", "No watch form found on $data")
                return false
            }

            val postUrl = watchForm.attr("action")
            val formData = watchForm.select("input[type=hidden]")
                .associateTo(mutableMapOf()) { it.attr("name") to it.attr("value") }

            val watchBtn = soup0.selectFirst("button.single-watch-btn")
            if (watchBtn != null) {
                val btnName = watchBtn.attr("name")
                if (btnName.isNotBlank()) formData[btnName] = watchBtn.attr("value").ifBlank { "submit" }
            }

            val r1 = app.post(postUrl, data = formData, referer = data, headers = headers)

            val r1Text = r1.text
            val mMyurl = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(r1Text)
            val mNews = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(r1Text)

            if (mMyurl != null && mNews != null) {
                val nextPost = mMyurl.groupValues[1]
                val newsVal = mNews.groupValues[1]

                val r2 = app.post(nextPost, data = mapOf("news" to newsVal, "u" to "", "submit" to "submit"), referer = r1.url, headers = headers)
                val soup2 = r2.document

                val embedHost = "https://3iskk.xyz"
                val embedUrls = mutableListOf<String>()

                soup2.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                    if (src.isNotBlank()) {
                        embedUrls.add(src)
                    }
                }

                if (embedUrls.isEmpty()) {
                    Log.e("ThreeSk", "No iframes found after POST2")
                    return false
                }

                val foundMediaLinks = mutableSetOf<String>()

                for (embedUrl in embedUrls) {
                    val serverNums = mutableListOf<Int>()

                    val embedMatch = Regex("""(\d+)/(.+)""").find(embedUrl)
                    if (embedMatch != null) {
                        for (n in 1..10) serverNums.add(n)
                    } else {
                        serverNums.add(0)
                    }

                    for (num in serverNums) {
                        val currentUrl = if (num == 0) {
                            embedUrl
                        } else if (embedMatch != null) {
                            "$embedHost/embed/$num/${embedMatch.groupValues[2]}"
                        } else {
                            embedUrl
                        }

                        try {
                            val hdrs = headers.toMutableMap()
                            hdrs["Referer"] = r2.url
                            val rEmbed = app.get(currentUrl, referer = r2.url, headers = hdrs)
                            val embedText = rEmbed.text

                            Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4|webm|mov)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                                .findAll(embedText).forEach { foundMediaLinks.add(it.groupValues[1]) }

                            analyzeAndUnpackScripts(embedText).forEach { foundMediaLinks.add(it) }

                            val embedDoc = rEmbed.document
                            embedDoc.select("iframe").forEach { innerIframe ->
                                val innerSrc = innerIframe.attr("src").ifBlank { innerIframe.attr("data-src") }
                                if (innerSrc.isNotBlank() && innerSrc.startsWith("http")) {
                                    try {
                                        val hdrsInner = headers.toMutableMap()
                                        hdrsInner["Referer"] = currentUrl
                                        val rInner = app.get(innerSrc, referer = currentUrl, headers = hdrsInner)
                                        val innerText = rInner.text
                                        Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4|webm|mov)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                                            .findAll(innerText).forEach { foundMediaLinks.add(it.groupValues[1]) }
                                        analyzeAndUnpackScripts(innerText).forEach { foundMediaLinks.add(it) }
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                if (foundMediaLinks.isEmpty()) {
                    Log.e("ThreeSk", "No media links found")
                    return false
                }

                for (link in foundMediaLinks) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = link,
                            type = if (link.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                return true
            }

            Log.e("ThreeSk", "Failed to extract myUrl/news from POST response")
            return false

        } catch (e: Exception) {
            Log.e("ThreeSk", "loadLinks error", e)
            return false
        }
    }

    private fun analyzeAndUnpackScripts(htmlText: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val doc = org.jsoup.Jsoup.parse(htmlText)
            for (script in doc.select("script")) {
                val content = script.data().ifBlank { script.html() }

                Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4|webm|mov)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                    .findAll(content).forEach { result.add(it.groupValues[1]) }

                if (content.contains("eval(")) {
                    try {
                        val unpacked = unpackPacker(content)
                        if (unpacked != null) {
                            Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4|webm|mov)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                                .findAll(unpacked).forEach { result.add(it.groupValues[1]) }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun unpackPacker(script: String): String? {
        try {
            val packerMatch = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)""").find(script) ?: return null
            val start = packerMatch.range.first

            var braceCount = 0
            var fnStart = -1
            for (i in start until script.length) {
                if (script[i] == '{') {
                    if (fnStart == -1) fnStart = i
                    braceCount++
                } else if (script[i] == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        val body = script.substring(fnStart + 1, i)

                        val pMatch = Regex("""function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{[^}]*return\s+p""").find(script.substring(start, i + 200))

                        val pStart = script.indexOf("function(p,a,c,k,e,d)", start)
                        if (pStart == -1) return null

                        val argsStart = script.indexOf('(', pStart + "function(p,a,c,k,e,d)".length)
                        if (argsStart == -1) return null

                        val argsEnd = script.indexOf(");", argsStart)
                        if (argsEnd == -1) return null

                        val args = script.substring(argsStart + 1, argsEnd)

                        var inStr = false
                        var strChar = ' '
                        var currentArg = StringBuilder()
                        val argList = mutableListOf<String>()
                        var escaped = false

                        for (ch in args) {
                            if (escaped) {
                                currentArg.append(ch)
                                escaped = false
                                continue
                            }
                            if (ch == '\\') {
                                escaped = true
                                currentArg.append(ch)
                                continue
                            }
                            if (!inStr && (ch == '"' || ch == '\'')) {
                                inStr = true
                                strChar = ch
                                continue
                            }
                            if (inStr && ch == strChar) {
                                inStr = false
                                continue
                            }
                            if (!inStr && ch == ',') {
                                argList.add(currentArg.toString())
                                currentArg = StringBuilder()
                                continue
                            }
                            if (!inStr && ch == ' ') continue
                            currentArg.append(ch)
                        }
                        if (currentArg.isNotEmpty()) argList.add(currentArg.toString())

                        if (argList.size < 3) return null

                        val p = argList[0].trim('"', '\'')
                        val a = argList[1].toIntOrNull() ?: return null
                        val c = argList[2].toIntOrNull() ?: return null
                        val kStr = if (argList.size > 3) argList[3].trim('"', '\'') else ""

                        val k = kStr.split("|")

                        var result = p
                        for (idx in c - 1 downTo 0) {
                            if (idx < k.size && k[idx].isNotEmpty()) {
                                val base36Key = intToBase36(idx)
                                result = result.replace(Regex("\\b${Regex.escape(base36Key)}\\b"), k[idx])
                            }
                        }
                        return result
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun intToBase36(n: Int): String {
        if (n == 0) return "0"
        var num = n
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        while (num > 0) {
            sb.append(chars[num % 36])
            num /= 36
        }
        return sb.reverse().toString()
    }
}
