package com.asia4arabs.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import android.content.SharedPreferences
import android.util.Log

/**
 * مصدر «أسيا للعرب» (asia4arabs.com) — مسلسلات وأفلام آسيوية مترجمة.
 *
 * البنية (رُسمت ميدانياً):
 *  - الصفحة الرئيسية: بطاقات `a[href*=/series/], a[href*=/movies/]`.
 *  - صفحة التفاصيل: 4 أزرار سيرفرات `button.server-button` بسمات
 *    `data-server-url` + `data-server-name` (VIDS/UPT/MOLY/VIVO).
 *  - المسلسلات: شبكة حلقات `a.episode-number-btn` بعناوين `?episode=N`،
 *    والـ iframe الحالي في `iframe#episodePlayer[data-litespeed-src]`.
 *  - الأفلام: سيرفر مباشر في نفس الصفحة (لا حلقات).
 *
 * كل سيرفر صفحة تضمين تُستخرج منها روابط HLS/MP4 عند البث:
 *  - VIDS (morencius.com): m3u8 داخل سكربت P.A.C.K.E.R → فكّ ثم اقرأ
 *    `sources:[{file:"...m3u8"}]` (رابط hls2 مباشر مُفوَّت).
 *  - MOLY (vidmoly.org): m3u8 جاهز في `sources:[{file:"..."}]`.
 *  - UPT (upbolt.to): POST `/dl` (op=embed&file_code&auto&referer) يردّ صفحة
 *    فيها m3u8 — نحاكي النموذج بطلب POST.
 *  - VIVO (vinovo.to): بعض التضمينات «Not found»؛ إن وُجد مضمّن يُستخرج منه.
 *  عند تعذّر الاستخراج تُبثّ صفحة التضمين نفسها كرابط VIDEO (مثل krmzi) —
 *  الافتراضي يُبقي هذا السلوك، ويمكن إيقافه من الإعدادات.
 */
class Asia4ArabsProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    companion object {
        private const val TAG = "Asia4aRabs"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override var mainUrl = "https://asia4arabs.com"
    override var name = "Asia4arabs"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    // ---------- P.A.C.K.E.R. unpacker (mirror of krmzi's) ----------
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

    // ---- Base64URL decode (pure Kotlin, no android.util.Base64) ----
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
    private fun Element.searchCard(): SearchResponse? {
        val href = this.attr("href")
        val title = this.attr("title").ifBlank { this.selectFirst(".title")?.text().orEmpty() }

        // Poster: various card styles — background-image, img src, data-src.
        val styleHolder = this.selectFirst("[style*='background-image']")
        val img = this.selectFirst("img")
        val poster = styleHolder?.attr("style")?.let {
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(it)?.groupValues?.get(1)
        }?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("src")

        if (href.isBlank() || title.isBlank()) return null
        return if (href.contains("/series/") || href.contains("/movies/"))
            newTvSeriesSearchResponse(title, href) {
                this.posterUrl = poster
            }
        else null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val docs = try {
            val p1 = app.get("$mainUrl/").document
            val p2 = app.get("$mainUrl/page/${page + 1}/").document
            listOf(p1, p2)
        } catch (_: Exception) {
            return newHomePageResponse(emptyList())
        }
        fun section(doc: org.jsoup.nodes.Document): List<SearchResponse> {
            val seen = mutableSetOf<String>()
            return doc.select("a[href*=/series/], a[href*=/movies/]")
                .mapNotNull { it.searchCard() }
                .filter { seen.add(it.url) }
        }
        return newHomePageResponse(
            listOf(
                HomePageList("الأفلام والمسلسلات", section(docs[0])),
                HomePageList("المزيد", section(docs[1]))
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim()}"
        val doc = app.get(url).document
        return doc.select("a[href*=/series/], a[href*=/movies/]")
            .filter { it.attr("href").length > 8 }
            .mapNotNull { it.searchCard() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img[src*=/wp-content/uploads/]")?.attr("src")
            ?.ifBlank { null }
            ?: doc.selectFirst("img")?.attr("data-src")
            ?.ifBlank { null }
            ?: doc.selectFirst("img")?.attr("src")
        val description = doc.selectFirst("h2.section-heading, .entry-content p, .storyline p, .description p")?.text()

        // Series with episodes.
        val episodes = ArrayList<Episode>()
        val isSeries = url.contains("/series/") || doc.selectFirst("a.episode-number-btn") != null
        if (isSeries) {
            doc.select("a.episode-number-btn").forEach { a ->
                val epUrl = a.attr("href").ifBlank { return@forEach }
                val epNum = Regex("""episode=(\d+)""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(
                    newEpisode(epUrl) {
                        name = "الحلقة ${epNum ?: (episodes.size + 1)}"
                        episode = epNum
                        posterUrl = poster
                    }
                )
            }
        }

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.sortedBy { it.episode }) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Movie: loadLinks will re-fetch the page and read its server buttons
        // (data-server-url) for all servers; the page URL is what we hand over.
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val collected = mutableListOf<ExtractorLink>()
        try {
            // For series episode URLs ( ?episode=N ) fetch the page again to get
            // the matching server buttons for that episode.
            val pageForServers = if (data.contains("episode=")) {
                app.get(data, headers = mapOf("User-Agent" to UA)).text
            } else data
            val serverButtons = org.jsoup.Jsoup.parse(pageForServers)
                .select("button.server-button")
            val servers = serverButtons.mapNotNull { btn ->
                val url = btn.attr("data-server-url").ifBlank { return@mapNotNull null }
                val name = btn.attr("data-server-name").ifBlank { "سيرفر" }
                url to name
            }
            Log.d(TAG, "servers on page: ${servers.map { it.second }}")

            // No servers → nothing to emit (movie server may not be a button).
            if (servers.isEmpty()) {
                // Try the current active iframe source directly.
                val embedded = Regex("""iframe[^>]*?(?:data-litespeed-src|src)=["'](https?://[^"']+)["']""")
                    .findAll(pageForServers).map { it.groupValues[1] }
                for (e in embedded) {
                    try { extractFromEmbed(e, "سيرفر", null, subtitleCallback, collected) } catch (_: Exception) {}
                }
                if (collected.isEmpty()) return false
                emitSorted(prefs, collected, callback)
                return true
            }

            val emitted = mutableSetOf<String>()
            servers.forEach { (embedUrl, name) ->
                try {
                    extractFromEmbed(embedUrl, name, null, subtitleCallback, collected, emitted)
                } catch (e: Exception) {
                    Log.e(TAG, "[$name] embed extraction failed: $e")
                }
            }
            emitSorted(prefs, collected, callback)
            return emitted.isNotEmpty() || collected.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error", e)
            return false
        }
    }

    /**
     * يفتح صفحة تضمين السيرفر ويستخرج روابط HLS/MP4 منها.
     * `emitted` (عندما يكون غير null) يُستخدم لمنع تكرار الروابط عبر السيرفرات.
     *
     * السيرفرات التي تستخرج يدوياً (VIDS/UPT/MOLY — m3u8 في الصفحة/بفكّ PACKER)
     * تبقى كما هي. سيرفرات Doodstream/Vinovo لا تُظهر m3u8 في الصفحة إطلاقاً —
     * البث يتكوّن لاحقاً (async /pass_md5 + token) — لذلك عند عدم إيجاد ميديا
     * نمرّر التضمين إلى مُستخرجي CloudStream المدمجين عبر `loadExtractor`
     * (يقف doodstream/vinovo/vidoza… ويعمل تماماً كما في lodynet) قبل fallback
     * صفحة التضمين كرابط VIDEO.
     */
    private suspend fun extractFromEmbed(
        embedUrl: String,
        label: String,
        _referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        collected: MutableList<ExtractorLink>,
        emitted: MutableSet<String>? = null
    ) {
        val ref = _referer ?: originOf(embedUrl)
        val isUpBolt = embedUrl.contains("upbolt.to")
        val isVidMoly = embedUrl.contains("vidmoly.org")
        val isMorencius = embedUrl.contains("morencius.com")

        val text: String = if (isUpBolt) {
            // UpBolt: POST /dl (doodstream-style) with the embed file_code & referer.
            val code = Regex("""/e/([^/?]+)""").find(embedUrl)?.groupValues?.get(1)
                ?: Regex("""highb\.(?:to|link)/e/([^/?]+)""").find(embedUrl)?.groupValues?.get(1)
            if (code.isNullOrBlank()) throw IllegalArgumentException("no upbolt code")
            val body = "op=embed&file_code=$code&auto=1&referer=$mainUrl"
            val r = app.post(
                "https://upbolt.to/dl",
                requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Referer" to mainUrl
                )
            )
            r.text
        } else {
            app.get(embedUrl, referer = mainUrl, headers = mapOf("User-Agent" to UA, "Referer" to mainUrl)).text
        }
        Log.d(TAG, "[$label] GET $embedUrl -> len=${text.length}")

        val media = mutableListOf<String>()
        // Direct m3u8/mp4 in page text.
        Regex("""(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { media.add(it.groupValues[1]) }

        // P.A.C.K.E.R. player script → unpack → read sources:[{file:"<m3u8>"}].
        val unpacked = analyzeAndUnpackScripts(text)
        Regex("""sources\s*:\s*\[\s*\{\s*file:\s*"([^"]+\.m3u8[^"]*)"\s*""", RegexOption.IGNORE_CASE)
            .findAll(unpacked.joinToString(" ")).forEach { media.add(it.groupValues[1]) }

        // For vidmoly: also sources:[{...}] shape with single quotes.
        if (isVidMoly) {
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+?\.m3u8[^'"]*)['"]""", RegexOption.IGNORE_CASE)
                .findAll(text).forEach { media.add(it.groupValues[1]) }
        }

        val distinct = media.any { it.contains(".m3u8") }
            .let { media.distinct() }
            .let { found ->
                // Prefer m3u8 (HLS) over mp4; keep order stable.
                found.sortedBy { if (it.contains(".m3u8")) 0 else 1 }
            }
        if (distinct.isEmpty()) {
            // Doodstream/Vinovo embeds have no m3u8 in the page; the stream is built
            // async (/pass_md5 + token). CloudStream's registry handles these hosts.
            if (emitted == null || emitted.add("loadExtractor:$embedUrl")) {
                Log.d(TAG, "[$label] no media inside → try built-in extractor")
                val builtIn = mutableListOf<ExtractorLink>()
                try {
                    loadExtractor(embedUrl, ref, subtitleCallback) { link -> builtIn.add(link) }
                } catch (e: Exception) {
                    Log.d(TAG, "[$label] loadExtractor failed: ${e.message}")
                }
                if (builtIn.isNotEmpty()) {
                    collected.addAll(builtIn)
                    return
                }
            }
            // لم يجد المدمَج شيئاً → صفحة التضمين نفسها كرابط VIDEO (السلوك السابق).
            Log.d(TAG, "[$label] no media inside → fallback to embed page as VIDEO")
            if (prefs?.getBoolean(Asia4ArabsSettingsBottomSheet.KEY_SHOW_RAW_LINK, true) != false) {
                if (emitted == null || emitted.add(embedUrl)) {
                    emitter(collected, label, embedUrl, ExtractorLinkType.VIDEO, originOf(embedUrl))
                }
            }
            return
        }
        distinct.forEach { mediaUrl ->
            if (emitted == null || emitted.add(mediaUrl)) {
                val m3u8 = mediaUrl.contains(".m3u8")
                emitter(collected, label, mediaUrl, if (m3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, ref)
            }
        }
    }

    private suspend fun emitter(
        collected: MutableList<ExtractorLink>,
        label: String,
        url: String,
        type: ExtractorLinkType,
        referer: String
    ) {
        collected.add(
            newExtractorLink(
                source = "Asia4arabs",
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

    /**
     * ★ بثّ الروابط بعد اكتمالها: «افتراضي» = نفس الترتيب والعدد تماماً،
     * و«تصاعدي/تنازلي» يعيدان ترتيبها فقط (فرز مستقر: المتساوية تحتفظ بترتيبها،
     * ولا حذف ولا تكرار).
     */
    private fun emitSorted(prefs: SharedPreferences?, collected: List<ExtractorLink>, callback: (ExtractorLink) -> Unit) {
        val order = prefs?.getString(Asia4ArabsSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
    }
}