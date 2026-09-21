package com.lodynet.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import android.util.Log

class LodyProvider : MainAPI() {
    companion object {
        private const val TAG = "LodyNet"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // "Lody Plus 1" is filled server-side at watch-time only (Encrypted, empty
        // Embed in the HTML) — it cannot be reproduced here, so it is skipped.
        private const val LODY_PLUS_ID = 73
        private const val VIDLO_ID = 116413

        private const val CAT = "https://lodynet.top/category/"

        // Verified live: هر مسار يعيد صفّاً من البطاقات.
        private val HOME_CATEGORIES = listOf(
            "مسلسلات تركية" to "مسلسلات-تركي",
            "مسلسلات هندية" to "مسلسلات-هنديه",
            "مسلسلات أجنبية" to "مسلسلات-اجنبية",
            "أفلام تركية" to "افلام-تركية-مترجم",
            "أفلام هندية" to "افلام-هندية",
            "مسلسلات صينية" to "مسلسلات-صينية-مترجمة",
            "أنيمي" to "انيمي"
        )
    }

    override var mainUrl = "https://lodynet.top"
    override var name = "لودي نت LODYNET"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    // ===================== helpers =====================

    /** "…الحلقة 4" -> "…"  (اسم المسلسل بدون رقم الحلقة) */
    private val epTitleRe = Regex("""\s*(?:ال)?حلقة\s*\d+.*$""")
    private val epNumRe = Regex("""(?:ال)?حلقة\s*(\d+)""")
    private val epNumUrlRe = Regex("""-ep(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * مفتاح التجميع: رابط المسلسل بدون لاحقة الحلقة.
     * الموقع يستخدم ثلاث صيغ:  -ep14 / -ep14-end   و   -e07
     * و   -الحلقة-54  (تصل من HTML مُرمَّزة: %d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-54)
     */
    private val epSuffixRe = Regex(
        """-(?:ep\d+(?:-end)?|e\d+|(?:ال|%d8%a7%d9%84)?(?:حلقة|%d8%ad%d9%84%d9%82%d8%a9)-\d+)/?$""",
        RegexOption.IGNORE_CASE
    )

    /** رقم الحلقة من مُعرّف العنصر: <a id="Ep4" class="ItemEpisode"> */
    private val epIdRe = Regex("""\d+""")

    private fun seriesNameOf(title: String): String =
        epTitleRe.replace(title, "").trim().ifBlank { title.trim() }

    private fun episodeNumberOf(text: String): Int? =
        epNumRe.find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: epNumUrlRe.find(text)?.groupValues?.get(1)?.toIntOrNull()

    private fun seriesKeyOf(url: String): String = epSuffixRe.replace(url.trimEnd('/'), "")

    private fun abs(u: String): String = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> mainUrl + u
        else -> u
    }

    private fun originOf(u: String): String =
        Regex("""https?://[^/"']+""").find(u)?.value ?: u

    // ---- Pure-Kotlin base64 decoder (no android.util.Base64) ----
    private fun base64Decode(input: String): String? =
        try {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val s = input.replace('-', '+').replace('_', '/')
            if (s.length % 4 == 1) return null
            val padded = s + "=".repeat((4 - s.length % 4) % 4)
            val clean = padded.filter { it != '=' }
            if (clean.any { chars.indexOf(it) == -1 }) return null
            val out = java.io.ByteArrayOutputStream()
            var buffer = 0; var bits = 0
            for (ch in clean) {
                buffer = (buffer shl 6) or chars.indexOf(ch)
                bits += 6
                if (bits >= 8) { bits -= 8; out.write((buffer shr bits) and 0xFF) }
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) { null }

    private fun findMatchingBrace(text: String, startIdx: Int): Int {
        if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return -1
        var depth = 0; var i = startIdx
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    // ---- P.A.C.K.E.R. unpacker (some hosts still pack their player) ----
    private fun intToBase36Local(n0: Int): String {
        if (n0 == 0) return "0"
        var n = n0
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder()
        while (n > 0) { sb.append(chars[n % 36]); n /= 36 }
        return sb.reverse().toString()
    }

    private fun jsStringUnescape(s: String): String =
        Regex("""\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}|\\.|\\n|\\r|\\t""").replace(s) { m ->
            val esc = m.value
            try {
                when {
                    esc.startsWith("\\x") -> esc.substring(2).toInt(16).toChar().toString()
                    esc.startsWith("\\u") -> esc.substring(2).toInt(16).toChar().toString()
                    esc == "\\n" -> "\n"
                    esc == "\\r" -> "\r"
                    esc == "\\t" -> "\t"
                    else -> esc.substring(1)
                }
            } catch (_: Exception) { esc }
        }

    private fun parseJsStringAt(text: String, idxInit: Int): Pair<String?, Int> {
        var idx = idxInit
        if (idx >= text.length) return Pair(null, idx)
        val quote = text[idx]
        if (quote != '"' && quote != '\'') return Pair(null, idx)
        idx += 1
        val out = StringBuilder()
        while (idx < text.length) {
            val ch = text[idx]
            if (ch == '\\') {
                if (idx + 1 < text.length) { out.append(text.substring(idx, idx + 2)); idx += 2 } else idx++
            } else if (ch == quote) return Pair(jsStringUnescape(out.toString()), idx + 1)
            else { out.append(ch); idx++ }
        }
        return Pair(null, idx)
    }

    private fun unpackPackerFromEval(evalText: String): String? {
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

    /** يبحث في الصفحة (و داخلها سكربتات P.A.C.K.E.R.) عن روابط وسائط مباشرة */
    private fun extractMediaUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        val direct = Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mp4|webm|mov)[^\s"'<>\\]*)""", RegexOption.IGNORE_CASE)
        direct.findAll(html).forEach { urls += it.groupValues[1] }
        Regex("""file\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { urls += it.groupValues[1] }
        try {
            val doc = org.jsoup.Jsoup.parse(html)
            for (s in doc.select("script")) {
                val content = s.data().ifBlank { s.html() }
                if (!content.contains("eval(")) continue
                val m = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{""").find(content) ?: continue
                val start = m.range.first
                val sample = if (content.length > start + 20000) content.substring(start, start + 20000) else content.substring(start)
                val unpacked = unpackPackerFromEval(sample) ?: continue
                direct.findAll(unpacked).forEach { urls += it.groupValues[1] }
            }
        } catch (_: Exception) {}
        return urls.map { it.replace("\\/", "/") }.distinct().filter { it.startsWith("http") }
    }

    // ===================== cards =====================

    private data class RawCard(val title: String, val href: String, val cover: String?)

    private fun rawCards(doc: Document): List<RawCard> {
        val res = mutableListOf<RawCard>()
        for (a in doc.select(".ItemNewly > a[href], .SuggestionsItems[href], .NewlyItems > a[href]")) {
            val href = abs(a.attr("href").trim())
            if (href.isBlank() || !href.contains("lodynet.top")) continue
            val title = a.attr("title").ifBlank {
                a.selectFirst("strong, .NewlyTitle, .SuggestionsTitle, .title")?.text().orEmpty()
            }.ifBlank { a.text() }.trim()
            if (title.isBlank()) continue
            val coverEl = a.selectFirst("[data-src], img, .NewlyCover, .SuggestionsCover")
            val cover = coverEl?.let {
                it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { null }
            }
            res.add(RawCard(title, href, cover))
        }
        return res
    }

    /**
     * يجمع حلقات نفس المسلسل في بطاقة واحدة باسم المسلسل.
     * الرابط المعروض هو صفحة المسلسل الأساسية (بدون لاحقة الحلقة) حتى
     * يحصل المستخدم على قائمة الحلقات كاملة بدل حلقة واحدة.
     */
    private fun groupCards(raw: List<RawCard>): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (c in raw) {
            val key = seriesKeyOf(c.href)
            if (out.containsKey(key)) continue
            val name = seriesNameOf(c.title)
            val isMovie = name.startsWith("فيلم") || name.startsWith("مشاهدة فيلم")
            // فيلم: رابطه الأصلي (لا لاحقة حلقة). مسلسل: الصفحة الأساسية.
            val target = if (isMovie) c.href else key
            out[key] = if (isMovie) {
                newMovieSearchResponse(name, target, TvType.Movie) { this.posterUrl = c.cover }
            } else {
                newTvSeriesSearchResponse(name, target) { this.posterUrl = c.cover }
            }
        }
        return out.values.toList()
    }

    // ===================== main page =====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val rows = mutableListOf<HomePageList>()

        // 1) آخر الإضافات — من الصفحة الرئيسية
        try {
            val home = app.get("$mainUrl/").document
            val cards = groupCards(rawCards(home))
            if (cards.isNotEmpty()) rows.add(HomePageList("مضاف حديثاً", cards))
        } catch (e: Exception) {
            Log.e(TAG, "home section failed: ${e.message}")
        }

        // 2) أقسام الموقع — بالتوازي
        val catRows = coroutineScope {
            HOME_CATEGORIES.map { (label, slug) ->
                async {
                    try {
                        val url = CAT + java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20") + "/"
                        val cards = groupCards(rawCards(app.get(url).document))
                        if (cards.isNotEmpty()) HomePageList(label, cards) else null
                    } catch (e: Exception) {
                        Log.e(TAG, "category '$label' failed: ${e.message}")
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
        rows.addAll(catRows)

        return newHomePageResponse(rows)
    }

    // ===================== search =====================

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = try {
            app.get("$mainUrl/?s=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}").document
        } catch (_: Exception) { return emptyList() }
        val grouped = groupCards(rawCards(doc))
        if (grouped.isNotEmpty()) return grouped

        // fallback: أي رابط مقال داخل الموقع
        val loose = doc.select("a[href*='/lodynet.top/']").mapNotNull { a ->
            val href = abs(a.attr("href").trim())
            val title = a.attr("title").ifBlank { a.text() }.trim()
            if (href.isBlank() || title.isBlank() || href.contains("/category/") ||
                href.contains("/tag/") || href.contains("/page/")) null
            else RawCard(title, href, null)
        }
        return groupCards(loose)
    }

    // ===================== load =====================

    override suspend fun load(url: String): LoadResponse? {
        val doc = try { app.get(url).document } catch (_: Exception) { return null }

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("img[src*='/wp-content/uploads/']")?.attr("data-src")?.ifBlank { null }
            ?: doc.selectFirst("img[src*='/wp-content/uploads/']")?.attr("src")?.ifBlank { null }

        val plot = doc.selectFirst("[class*=Story], [class*=story], [class*=description], [class*=Details]")
            ?.text()?.takeIf { it.length > 20 }?.take(700)

        val episodes = doc.select("a.ItemEpisode[href], #ListEpisodes a[href]").mapNotNull { a ->
            val epUrl = abs(a.attr("href").trim())
            if (epUrl.isBlank()) return@mapNotNull null
            val epText = a.attr("title").ifBlank { a.text() }.trim()
            // الأوثق: id="Ep12" — ثم العنوان — ثم الرابط
            val epNum = epIdRe.find(a.id())?.value?.toIntOrNull()
                ?: episodeNumberOf(epText)
                ?: episodeNumberOf(epUrl)
            newEpisode(epUrl) {
                this.name = epText.ifBlank { epNum?.let { "الحلقة $it" } ?: "حلقة" }
                this.episode = epNum
                this.posterUrl = poster
            }
        }.distinctBy { it.data }

        return if (episodes.isNotEmpty()) {
            // ترتيب صحيح حتى لو فقدت بعض الحلقات رقمها (episode == null)
            val ordered = episodes.sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name }))
            newTvSeriesLoadResponse(seriesNameOf(rawTitle), url, TvType.TvSeries, ordered) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(rawTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ===================== loadLinks =====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "loadLinks: $data")
        try {
            val html = app.get(data, headers = mapOf("User-Agent" to UA)).text

            // --- ServersWatch: مصفوفة JSON صحيحة، لكن المفاتيح مُقتبسة
            //     ({"Name":…,"Embed":…,"Id":…,"Encrypted":…}) — لذا تُقرأ بـ JSON لا بـ regex.
            val arrText = Regex("""ServersWatch\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                .findAll(html).firstOrNull { it.groupValues[1].contains("\"Name\"") }
                ?.groupValues?.get(1)

            data class Srv(val name: String, val embed: String, val id: Int, val encrypted: Boolean)
            val servers = if (arrText == null) emptyList() else runCatching {
                val arr = org.json.JSONArray(arrText)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val n = o.optString("Name", "").trim()
                    if (n.isBlank()) null
                    else Srv(n, o.optString("Embed", ""), o.optInt("Id", -1), o.optBoolean("Encrypted", false))
                }
            }.getOrDefault(emptyList())
            Log.d(TAG, "servers: ${servers.size} -> ${servers.joinToString { "${it.name}#${it.id}" }}")

            // --- PageData tokens (VidLO needs ?st=…&e=…)
            var tokenVidlo = ""
            runCatching {
                val i = html.indexOf("PageData")
                if (i >= 0) {
                    val b = html.indexOf('{', i)
                    if (b >= 0) {
                        val e = findMatchingBrace(html, b)
                        if (e > b) {
                            val o = org.json.JSONObject(html.substring(b, e + 1))
                            tokenVidlo = o.optString("TokenVidlo", "")
                        }
                    }
                }
            }

            if (servers.isEmpty()) {
                Log.w(TAG, "no servers on page")
                return false
            }

            var any = false
            val done = mutableSetOf<String>()
            for (s in servers) {
                if (s.id == LODY_PLUS_ID) { Log.d(TAG, "skip Lody Plus"); continue }
                if (s.embed.isBlank()) { Log.d(TAG, "skip ${s.name} (empty embed)"); continue }

                val decoded = base64Decode(s.embed)?.trim() ?: run {
                    Log.w(TAG, "${s.name}: base64 decode failed"); null
                } ?: continue
                if (decoded.isBlank()) continue

                val embedUrl = if (s.id == VIDLO_ID) decoded + tokenVidlo else decoded
                if (!done.add(embedUrl)) continue

                if (resolveServer(embedUrl, s.name, subtitleCallback, callback) > 0) any = true
            }
            return any
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error", e)
            return false
        }
    }

    /**
     * يحاول إظهار سيرفر واحد بعدة طرق، ويعيد عدد الروابط التي أُرسلت.
     * 1) مُجمِّع megamax (يعطي عشرات السيرفرات المعروفة دفعة واحدة)
     * 2) مُستخرِجات CloudStream المدمجة
     * 3) استخراج يدوي من الصفحة
     * 4) رابط الـ embed نفسه (حتى لا يختفي أي سيرفر)
     */
    private suspend fun resolveServer(
        embedUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val referer = originOf(embedUrl)

        // 1) megamax — صفحته تحمل قائمة مرايا كاملة (1080p/720p/480p)
        if (originOf(embedUrl).contains("megamax")) {
            val n = resolveMegamax(embedUrl, serverName, callback)
            Log.d(TAG, "$serverName: megamax -> $n links")
            if (n > 0) return n
        }

        // 2) مُستخرِجات CloudStream المدمجة (voe/mixdrop/okru/uqload/doodstream…)
        var resolved = 0
        try {
            loadExtractor(embedUrl, referer, subtitleCallback) { link ->
                resolved++
                callback.invoke(link)
            }
        } catch (e: Exception) {
            Log.d(TAG, "$serverName: built-in extractor miss (${e.message})")
        }
        if (resolved > 0) {
            Log.d(TAG, "$serverName: resolved by built-in extractor ($resolved)")
            return resolved
        }

        // 3) محاولة يدوية: جلب الصفحة واستخراج m3u8/mp4
        if (tryManualExtract(embedUrl, referer, serverName, callback)) return 1

        // 4) دائماً أظهِر السيرفر — رابط الـ embed نفسه
        Log.d(TAG, "$serverName: fallback -> embed page")
        emit(callback, serverName, embedUrl, ExtractorLinkType.VIDEO, referer)
        return 1
    }

    /**
     * megamax.me صفحة تشغيل Laravel/Inertia. بيانات الفيديو تُطلب لاحقاً عبر
     * "partial reload" — بطلب نفس الرابط مع ترويسات X-Inertia نحصل على JSON
     * يحوي كل الجودات وكل المرايا (voe, mixdrop, doodstream, lulustream…),
     * وكلها لها مُستخرِجات مدمجة في CloudStream.
     */
    private suspend fun resolveMegamax(
        iframeUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        return try {
            val page = app.get(iframeUrl, headers = mapOf("User-Agent" to UA))
            val html = page.text
            val base = originOf(page.url).ifBlank { originOf(iframeUrl) }

            // مهم: JSON الصفحة يحتوي "files\/mirror\/video" — يجب إزالة الشرطة
            // المائلة المُهرَّبة وإلا لن يطابق الـ header ولن يعيد السيرفر streams.
            val component = Regex(""""component"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: "files/mirror/video"
            val version = Regex(""""version"\s*:\s*"([^"]+)"""")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/").orEmpty()

            val hdrs = mutableMapOf(
                "User-Agent" to UA,
                "Accept" to "text/html, application/xhtml+xml",
                "X-Inertia" to "true",
                "X-Inertia-Partial-Data" to "streams",
                "X-Inertia-Partial-Component" to component,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to iframeUrl
            )
            if (version.isNotBlank()) hdrs["X-Inertia-Version"] = version

            val r = app.get(iframeUrl, headers = hdrs)
            val streams = org.json.JSONObject(r.text)
                .optJSONObject("props")?.optJSONObject("streams")
                ?: run { Log.w(TAG, "megamax: no streams prop"); return 0 }

            if (streams.optString("status") != "success") {
                Log.w(TAG, "megamax: status=${streams.optString("status")}")
                return 0
            }

            val qualities = streams.optJSONArray("data") ?: return 0
            Log.d(TAG, "megamax: ${qualities.length()} qualities")

            var emitted = 0
            val tried = mutableSetOf<String>()

            for (qi in 0 until qualities.length()) {
                val q = qualities.optJSONObject(qi) ?: continue
                val qLabel = q.optString("label", "HD").trim()
                val mirrors = q.optJSONArray("mirrors") ?: continue

                var qEmitted = 0
                for (mi in 0 until mirrors.length()) {
                    if (qEmitted >= 4) break            // لا نُغرق القائمة
                    val m = mirrors.optJSONObject(mi) ?: continue
                    val driver = m.optString("driver", "").trim()
                    var link = m.optString("link", "").trim()
                    if (link.isBlank()) continue
                    if (link.startsWith("//")) link = "https:$link"
                    if (!link.startsWith("http")) continue
                    if (!tried.add(link)) continue

                    val linkRef = originOf(link)
                    // callback الـ loadExtractor ليس suspend، لذا نجمع أولاً
                    // ثم نُعيد الإرسال بعد خروجه (myExtractorLink مُعلَّمة suspend).
                    val collected = mutableListOf<ExtractorLink>()
                    try {
                        loadExtractor(link, linkRef, { }) { l -> collected.add(l) }
                    } catch (e: Exception) {
                        Log.d(TAG, "megamax [$qLabel/$driver] miss: ${e.message}")
                    }
                    for (l in collected) {
                        // نُعيد التسمية لتظهر الجودة واسم السيرفر بوضوح
                        callback.invoke(
                            newExtractorLink(
                                source = "لودي نت",
                                name = "$qLabel · ${driver.ifBlank { "server" }}",
                                url = l.url,
                                type = l.type
                            ) {
                                this.quality = l.quality
                                this.referer = l.referer
                                this.headers = l.headers
                            }
                        )
                    }
                    if (collected.isNotEmpty()) {
                        qEmitted += collected.size
                        emitted += collected.size
                    }
                }
            }
            emitted
        } catch (e: Exception) {
            Log.w(TAG, "megamax failed: ${e.message}")
            0
        }
    }

    private suspend fun tryManualExtract(
        embedUrl: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val r = app.get(embedUrl, referer = referer, headers = mapOf("User-Agent" to UA, "Referer" to referer))
            val text = r.text
            Log.d(TAG, "[$label] GET ${r.url} ${r.code} len=${text.length}")
            val media = extractMediaUrls(text)
            if (media.isEmpty()) {
                Log.d(TAG, "[$label] no direct media in page")
                return false
            }
            media.forEach { u ->
                emit(callback, label, u, if (u.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, originOf(embedUrl))
            }
            true
        } catch (e: Exception) {
            Log.d(TAG, "[$label] manual fetch failed: ${e.message}")
            false
        }
    }

    // newExtractorLink مُعلَّمة suspend في هذه النسخة، لذا emit نفسها suspend
    private suspend fun emit(
        callback: (ExtractorLink) -> Unit,
        label: String,
        url: String,
        type: ExtractorLinkType,
        referer: String
    ) {
        callback.invoke(
            newExtractorLink(
                source = "لودي نت",
                name = label,
                url = url,
                type = type
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to referer,
                    "Accept" to "*/*"
                )
            }
        )
    }
}
