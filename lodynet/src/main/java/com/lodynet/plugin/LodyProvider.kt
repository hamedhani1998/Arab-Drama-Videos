package com.lodynet.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import android.util.Log

class LodyProvider : MainAPI() {
    companion object {
        private const val TAG = "LodyNet"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // "Lody Plus 1" و "VIP" يصلان دائماً بـ Embed فارغ (Encrypted=true) —
        // يُملأان من الخادم وقت المشاهدة فقط، لذا لا يمكن إعادة إنتاجهما هنا.
        private const val LODY_PLUS_ID = 73
        private const val VIP_ID = 1
        private const val VIDLO_ID = 116413

        private const val CAT = "https://lodynet.top/category/"

        /**
         * بحث القالب نفسه (Lodynet2020). هذا هو نفس الـ endpoint الذي يستدعيه
         * الموقع عند الكتابة في مربع البحث، ويعيد JSON جاهزاً في ~1 ثانية،
         * بدل صفحة /search/ التي تستغرق 3–20 ثانية وأحياناً لا تعيد نتائج.
         */
        private const val WP_SEARCH =
            "https://lodynet.top/wp-content/themes/Lodynet2020/Api/RequestSearch.php?value="

        /** واجهة ووردبريس: تُعيد كل حلقات أي قسم مُرقّم دفعة واحدة (حتى 100). */
        private const val WP_POSTS = "https://lodynet.top/wp-json/wp/v2/posts"

        /** واجهة أقسام ووردبريس — لتحديد قسم المسلسل من رقمه. */
        private const val WP_CATS = "https://lodynet.top/wp-json/wp/v2/categories"

        /** سقف صفحات الحلقات (100 لكل صفحة) — يغطي أطول مسلسل على الموقع. */
        private const val MAX_EP_PAGES = 12

        // مسارات الأقسام على الرئيسية (مُتحقَّق منها: كل مسار يعيد صفّاً كاملاً)
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
    override val hasQuickSearch = true

    // ===================== helpers =====================

    /** "مسلسل هوس مترجم الحلقة 45" -> "مسلسل هوس مترجم" */
    private val epTitleRe = Regex("""\s*(?:ال)?حلقة\s*\d+.*$""")
    private val epNumRe = Regex("""(?:ال)?حلقة\s*[^\d]{0,8}(\d+)""")
    private val epNumUrlRe = Regex("""(?:-ep|-e)(\d+)""", RegexOption.IGNORE_CASE)

    /**
     * لاحقة الحلقة في روابط الموقع. ثلاث صيغ:
     *   -ep14 / -ep14-end    و    -e07    و    -الحلقة-54
     * والصيغة الأخيرة تصل من HTML مُرمَّزة: %d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-54
     */
    private val epSuffixRe = Regex(
        """-(?:ep\d+(?:-end)?|e\d+|(?:ال|%d8%a7%d9%84)?(?:حلقة|%d8%ad%d9%84%d9%82%d8%a9)-\d+)/?$""",
        RegexOption.IGNORE_CASE
    )

    /** رقم الحلقة في نهاية الرابط — آخر ملاذ حين لا يحمل العنصر عنواناً مرقّماً. */
    private val trailingNumRe = Regex("""-(\d+)/?$""")

    private fun seriesNameOf(title: String): String =
        epTitleRe.replace(title, "").trim().ifBlank { title.trim() }

    private fun episodeNumberOf(text: String, url: String = ""): Int? {
        epNumRe.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        epNumUrlRe.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        epNumUrlRe.find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        trailingNumRe.find(url.trimEnd('/'))?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    /** مفتاح التجميع: الرابط بدون لاحقة الحلقة. */
    private fun seriesKeyOf(url: String): String = epSuffixRe.replace(url.trimEnd('/'), "")

    /**
     * روابط الحلقات التي يمكن استنتاج قسم مسلسلها منها — أي لاحقة حلقة
     * **رقمية** يدعمها الموقع. لاحقة «الحلقة-N» العربية وحدها مستبعدة:
     * تلك روابط قسم (`…/category/<slug>/الحلقة-45`) وليست مواضيع، فلا
     * معرّف لها في واجهة ووردبريس.
     */
    private val RESOLVABLE_EP_RE = Regex(
        """-(?:ep\d+(?:-end)?|e\d+)(?:/|$)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * يُكمل الروابط النسبية. مهم جداً: بحث القالب (RequestSearch.php) يعيد
     * روابط نسبية بلا شرطة أولى مثل "category/xxx"، وكانت تمر كما هي فيفشل
     * فحص "/category/" ويسقط المسلسل إلى فرع الفيلم — وهذا سبب ظهور المسلسلات
     * كأفلام وبلا حلقات.
     */
    private fun abs(u0: String): String {
        val u = u0.replace("\\/", "/").trim()
        return when {
            u.isBlank() -> ""
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl + u
            else -> "$mainUrl/$u"
        }
    }

    private fun originOf(u: String): String =
        Regex("""https?://[^/"']+""").find(u)?.value ?: u

    /** عناوين الموقع تحمل BOM في أولها أحياناً — ننظّفها */
    private fun clean(s: String): String = s.replace("﻿", "").trim()

    /**
     * يحوّل تسمية الجودة ("1080p", "1280x720") إلى قيمة Qualities.
     * بدونها يبقى المشغّل على Unknown فيختار أول رابط — وغالباً أكبر ملف
     * فينقطع عند التقديم/التأخير.
     */
    private fun qualityOf(vararg labels: String): Int {
        val s = labels.joinToString(" ").lowercase()
        return when {
            s.contains("2160") || s.contains("4k") -> Qualities.P2160.value
            s.contains("1440") -> Qualities.P1440.value
            s.contains("1080") -> Qualities.P1080.value
            s.contains("720") -> Qualities.P720.value
            s.contains("480") -> Qualities.P480.value
            s.contains("360") -> Qualities.P360.value
            s.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    /**
     * رابط صفحة HTML ليس وسائط: المشغّل لا يستطيع تحميله ولا التقديم فيه،
     * فيعطي ExoPlayer الخطأ 2004 (ERROR_CODE_IO_BAD_HTTP_STATUS) وينقطع.
     */
    private fun isDeadUrl(u: String): Boolean {
        if (u.isBlank() || !u.startsWith("http")) return true
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".html") || path.endsWith(".htm") ||
            path.endsWith(".php") || path.endsWith("/")
    }

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

    /** يبحث في الصفحة (و داخل سكربتاتها المُحزَّمة) عن روابط وسائط مباشرة */
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
        // .ItemNewly وحدها هي بطاقات المحتوى الحقيقية (رئيسية/أقسام).
        // لا نستخدم .SuggestionsItems: فهي شريط "مقترحات" ثابت يظهر أعلى كل
        // صفحة بغض النظر عن نتائج البحث، فتُقحم 12 بطاقة غير متعلقة.
        for (a in doc.select(".ItemNewly > a[href], .NewlyItems > a[href]")) {
            val href = abs(a.attr("href").trim())
            if (href.isBlank() || !href.contains("lodynet.top")) continue
            val title = clean(
                a.attr("title").ifBlank {
                    a.selectFirst("strong, .NewlyTitle, .title")?.text().orEmpty()
                }.ifBlank { a.text() }
            )
            if (title.isBlank()) continue
            val coverEl = a.selectFirst("[data-src], img, .NewlyCover")
            val cover = coverEl?.let {
                it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { null }
            }
            res.add(RawCard(title, href, cover))
        }
        return res
    }

    /** فيلم أم مسلسل؟ الموقع يسبق الأفلام بكلمة "فيلم". */
    private fun isMovieTitle(title: String): Boolean {
        val t = title.trimStart()
        return t.startsWith("فيلم") || t.startsWith("مشاهدة فيلم") ||
            t.startsWith("انمي فيلم") || t.contains("فيلم ")
    }

    private fun cardOf(name: String, url: String, cover: String?): SearchResponse =
        if (isMovieTitle(name)) {
            newMovieSearchResponse(name, url, TvType.Movie) { this.posterUrl = cover }
        } else {
            newTvSeriesSearchResponse(name, url) { this.posterUrl = cover }
        }

    /**
     * يجمع حلقات نفس المسلسل في بطاقة واحدة باسم المسلسل.
     *
     * المفتاح هو الرابط مجرّداً من لاحقة الحلقة (للتجميع فقط)، أما **الرابط
     * المستهدف فيبقى كما هو**: فبطاقات الصفحة الرئيسية تشير إلى حلقات لا
     * إلى أقسام، و`load()` صار يستنتج قسم المسلسل من رابط الحلقة. لو
     * استهدفنا الرابط المجرّد (`…-s01`) لوقعنا على صفحة الحلقة 1 نفسها
     * ولظهر المسلسل كفيلم بلا حلقات — وهذا أصل العلّة.
     */
    private fun groupCards(raw: List<RawCard>): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        for (c in raw) {
            val key = seriesKeyOf(c.href)
            if (out.containsKey(key)) continue
            val name = seriesNameOf(c.title)
            out[key] = cardOf(name, c.href, c.cover)
        }
        return out.values.toList()
    }

    // ===================== main page =====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val rows = mutableListOf<HomePageList>()

        // 1) آخر الإضافات — من الصفحة الرئيسية
        try {
            val home = app.get("$mainUrl/", headers = mapOf("User-Agent" to UA)).document
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
                        val cards = groupCards(rawCards(app.get(url, headers = mapOf("User-Agent" to UA)).document))
                        if (cards.isNotEmpty()) HomePageList(label, cards) else null
                    } catch (e: Exception) {
                        Log.e(TAG, "category '$label' failed: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        rows.addAll(catRows)

        return newHomePageResponse(rows)
    }

    // ===================== search =====================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = clean(query)
        if (q.isBlank()) return emptyList()

        // بحث القالب: JSON جاهز (Title/Url/Cover/Category) في ~1 ثانية.
        // العنوان "[0]" هو المُصطلح نفسه، و"[1]" هي النتائج.
        try {
            val enc = java.net.URLEncoder.encode(q, "UTF-8")
            val body = app.get(
                WP_SEARCH + enc,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to "$mainUrl/",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
            val arr = org.json.JSONArray(body)
            val items = arr.optJSONArray(1) ?: org.json.JSONArray()
            val out = LinkedHashMap<String, SearchResponse>()
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val title = clean(o.optString("Title", ""))
                if (title.isBlank()) continue
                val url = abs(o.optString("Url", "").replace("\\/", "/").trim())
                if (url.isBlank()) continue
                val cover = o.optString("Cover", "").ifBlank { null }
                val key = seriesKeyOf(url)
                if (out.containsKey(key)) continue
                val name = seriesNameOf(title)
                val movie = isMovieTitle(name)
                out[key] = cardOf(name, if (movie) url else key, cover)
            }
            if (out.isNotEmpty()) return out.values.toList()
        } catch (e: Exception) {
            Log.w(TAG, "theme search failed: ${e.message}")
        }

        // احتياطي: صفحة بحث ووردبريس (أبطأ لكنها تعمل حين يتعطّل الـ API)
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        for (u in listOf("$mainUrl/?s=$enc", "$mainUrl/search/$enc/")) {
            val doc = try {
                app.get(u, headers = mapOf("User-Agent" to UA)).document
            } catch (_: Exception) { null } ?: continue
            val grouped = groupCards(rawCards(doc))
            if (grouped.isNotEmpty()) return grouped
        }
        return emptyList()
    }

    // ===================== load =====================

    /** معرّف القسم في ووردبريس — موجود في ترويسة أي صفحة (حلقة أو قسم). */
    private val catIdRe = Regex("""wp-json/wp/v2/categories/(\d+)""")

    /**
     * هل هذه صفحة مسلسل أم صفحة مشاهدة واحدة (فيلم/حلقة)؟
     *
     * المعيار القاطع: صفحات المسلسلات (الأقسام) تعرض ترويسة ووردبريس
     * `wp-json/wp/v2/categories/<id>`، وصفحات المواضيع المفردة (الأفلام
     * والحلقات) لا تعرضها إطلاقاً — بل تعرض `ServersWatch` بدلاً منها.
     * قِيس ذلك مباشرة على الموقع: صفحة قسم = 1 مرجع، صفحة موضوع = 0.
     */
    private fun isSeriesPage(html: String): Boolean =
        catIdRe.containsMatchIn(html) && !html.contains("ServersWatch")

    override suspend fun load(url: String): LoadResponse? {
        val doc = try {
            app.get(url, headers = mapOf("User-Agent" to UA)).document
        } catch (_: Exception) { null } ?: return null

        val html = doc.html()

        // ---- مسلسل ----
        // صفحة القسم (…/category/<slug>) هي صفحة المسلسل: لا تحمل h1،
        // وحلقاتها روابط عادية بلاحقة «الحلقة-N» — لا class="ItemEpisode".
        // لذلك نقرأها من واجهة ووردبريس التي تُعيد الحلقات كلها دفعة واحدة.
        if (isSeriesPage(html)) {
            val catId = catIdRe.find(html)?.groupValues?.get(1)
            if (catId != null) {
                val res = loadSeries(url, catId, doc)
                if (res != null) return res
            }
        }

        // ---- مسلسل وصلنا من رابطه حلقة ----
        // وهذا حال بطاقات «مضاف حديثاً»: رابط حلقة، لا رابط قسم. لا نستطيع
        // قراءة صفحة القسم من هذا الرابط (تجريده يقع على صفحة الحلقة 1)،
        // فيُستنتج قسم المسلسل من أقسام الموضوع عبر واجهة ووردبريس.
        if (RESOLVABLE_EP_RE.containsMatchIn(url)) {
            val catId = seriesCatFromEpisode(url)
            if (catId != null) {
                val catUrl = CAT + java.net.URLEncoder.encode(catId, "UTF-8") + "/"
                Log.d(TAG, "'$url' -> series category $catId")
                // صفحة القسم تُقرأ لواجهة ووردبريس (loadSeries) وتُستخدم
                // أيضاً لاستخراج الاسم والغلاف بعيداً عن عنوان الحلقة.
                val catDoc = try {
                    app.get(catUrl, headers = mapOf("User-Agent" to UA)).document
                } catch (_: Exception) { null }
                val res = loadSeries(catUrl, catId, catDoc ?: doc)
                if (res != null) return res
            }
        }

        // ---- فيلم / حلقة مفردة ----
        val title = clean(
            doc.selectFirst("h1")?.text().orEmpty()
                .ifBlank { doc.selectFirst("meta[property='og:title']")?.attr("content").orEmpty() }
        )
        if (title.isBlank()) return null

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst("img[src*='/wp-content/uploads/']")?.attr("data-src")
                ?.ifBlank { null }

        val plot = doc.selectFirst("[class*=Story], [class*=story], [class*=description], [class*=Details]")
            ?.text()?.takeIf { it.length > 20 }?.take(700)

        return newMovieLoadResponse(seriesNameOf(title), url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /**
     * يستنتج قسم المسلسل من **رابط حلقة واحدة**.
     *
     * هذا هو إصلاح «المضاف حديثاً»: بطاقات هذا القسم روابط حلقات
     * (`…/a-love-other-than-yours-s01-ep4`)، وتجريد لاحقة الحلقة يعطي
     * `…/a-love-other-than-yours-s01` وهو رابط **غير موجود** — الموقع
     * يحوّله (301) إلى صفحة الحلقة 1 نفسها. وتلك صفحة موضوع لا صفحة قسم،
     * فلا تُطابق `isSeriesPage()` ويسقط المسلسل إلى فرع الفيلم: يُعرض
     * كفيلم بلا حلقات. (وبطاقات الأقسام تعمل لأنها روابط أقسام فعلاً.)
     *
     * الحل: كل موضوع يحمل في ووردبريس مصفوفة أقسامه. قسم المسلسل هو الوحيد
     * ذو **قسم أب** (الأب هو قسم التصنيف العام كـ«مسلسلات كورية»)، أما
     * الأفلام فكل أقسامها رئيسية بلا أب. مُتحقَّق منه على 20 رابطاً.
     *
     * نتحقق من الأب أيضاً — لا نثق بالمعرّف وحده — حتى لا يُبنى مسلسل وهمي
     * من أي رابط في حال تغيّر الموقع.
     */
    private suspend fun seriesCatFromEpisode(url: String): String? {
        val slug = java.net.URLDecoder.decode(
            url.trimEnd('/').substringAfterLast('/'), "UTF-8"
        ).ifBlank { return null }

        val post = try {
            app.get(
                "$WP_POSTS?slug=${java.net.URLEncoder.encode(slug, "UTF-8")}" +
                    "&_fields=categories",
                headers = mapOf("User-Agent" to UA)
            ).text
        } catch (e: Exception) {
            Log.w(TAG, "slug lookup failed for '$slug': ${e.message}")
            return null
        }

        val catIds = try {
            val arr = org.json.JSONArray(post)
            val o = if (arr.length() > 0) arr.optJSONObject(0) else null
            val cs = o?.optJSONArray("categories") ?: return null
            (0 until cs.length()).mapNotNull { cs.optInt(it).takeIf { c -> c != 0 } }
        } catch (e: Exception) {
            Log.w(TAG, "slug lookup parse failed for '$slug': ${e.message}")
            return null
        }
        if (catIds.isEmpty()) return null

        for (cid in catIds) {
            val parent = try {
                val c = app.get(
                    "$WP_CATS/$cid?_fields=parent",
                    headers = mapOf("User-Agent" to UA)
                ).text
                org.json.JSONObject(c).optInt("parent", 0)
            } catch (_: Exception) {
                0
            }
            if (parent != 0) return cid.toString()
        }
        return null
    }

    /**
     * حلقات المسلسل من واجهة ووردبريس: كل الحلقات مع روابطها وعناوينها —
     * أسرع وأكمل من قراءة الصفحة (التي تعرض 30 فقط كما أنها لا تحمل class
     * مناسباً).
     *
     * الصفحات: ووردبريس لا يعطي أكثر من 100 عنصر للطلب، وبعض المسلسلات
     * طويلة حقاً (سر الحنين 150، أمْنِية وإن تحققت 899)، فبطلب واحد كانت
     * الحلقات 1–50 تسقط من مسلسل من 150 حلقة. نتابع الصفحات حتى النقص.
     */
    private suspend fun loadSeries(url: String, catId: String, doc: Document): LoadResponse? {
        val baseTitle = clean(
            doc.selectFirst("meta[property='og:title']")?.attr("content").orEmpty()
                .ifBlank { doc.selectFirst("title")?.text().orEmpty().substringBefore(" - ") }
        )
        val name = seriesNameOf(baseTitle.ifBlank { "مسلسل" })

        data class Ep(val num: Int?, val title: String, val url: String)

        val eps = mutableListOf<Ep>()
        var page = 1
        while (page <= MAX_EP_PAGES) {
            val arr = try {
                org.json.JSONArray(
                    app.get(
                        "$WP_POSTS?categories=$catId&per_page=100&page=$page" +
                            "&orderby=date&order=desc&_fields=id,link,title",
                        headers = mapOf("User-Agent" to UA)
                    ).text
                )
            } catch (e: Exception) {
                // الصفحة 400 بعد الأخيرة — نكتفي بما جمعناه
                if (page > 1) break
                Log.w(TAG, "wp posts failed for cat $catId: ${e.message}")
                break
            }
            if (arr.length() == 0) break

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val link = abs(o.optString("link", "").trim())
                if (link.isBlank()) continue
                val t = clean(
                    o.optJSONObject("title")?.optString("rendered", "").orEmpty()
                        .replace(Regex("""<[^>]+>"""), "")
                )
                // إن كان عنوان الموضوع هو اسم المسلسل نفسه بلا رقم حلقة،
                // فالرقم في نهاية الرابط (…-الحلقة-45) هو المصدر الموثوق.
                val num = episodeNumberOf(t, link)
                eps.add(Ep(num, if (num != null && t == name) "" else t, link))
            }
            if (arr.length() < 100) break
            page++
        }

        // احتياطي: استخراج الحلقات من الصفحة نفسها بلاحقة «الحلقة-N»
        if (eps.isEmpty()) {
            for (a in doc.select("a[href]")) {
                val href = abs(a.attr("href").trim())
                if (href.isBlank() || !epSuffixRe.containsMatchIn(href.trimEnd('/'))) continue
                val t = clean(a.attr("title").ifBlank { a.text() })
                eps.add(Ep(episodeNumberOf(t, href), t.ifBlank { "حلقة" }, href))
            }
        }

        val uniq = eps.distinctBy { it.url }
        if (uniq.isEmpty()) return null

        val ordered = uniq.sortedWith(compareBy({ it.num ?: Int.MAX_VALUE }, { it.title }))

        // غلاف المسلسل: صفحة القسم بلا og:image، لكن بطاقة المسلسل نفسها
        // مضمّنة فيها كـ ItemNewly باسم مطابق (‎…/uploads/…/هوس-220x220.webp).
        var poster: String? = doc.selectFirst("meta[property='og:image']")
            ?.attr("content")?.ifBlank { null }
        // حين لا تكون `doc` صفحةَ القسم (بطاقةُ «مضاف حديثاً» رابطُ حلقة،
        // و`loadSeries` قد تُمرَّر صفحة الحلقة) يكون og:image صورةَ الحلقة،
        // ولا تصلح غلافاً للمسلسل. نستبعدها بمطابقة الاسم.
        val want = name.replace(Regex("""\s+"""), " ").trim()
        val posterLooksRight = { u: String? ->
            if (u.isNullOrBlank()) false
            else {
                val base = java.net.URLDecoder.decode(u.substringAfterLast('/'), "UTF-8")
                val stem = want.replace("مسلسل ", "").trim()
                stem.length < 4 || base.contains(stem.take(8)) ||
                    base.contains(stem.takeLast(8))
            }
        }
        if (!posterLooksRight(poster)) poster = null
        if (poster == null) {
            poster = rawCards(doc).firstOrNull {
                seriesNameOf(it.title).replace(Regex("""\s+"""), " ").trim() == want &&
                    !it.cover.isNullOrBlank()
            }?.cover
        }
        // احتياطي أخير: أي صورة في مجلد الرفع تحمل اسم المسلسل.
        if (poster == null && want.length > 4) {
            poster = Regex("""(https?://lodynet\.top/wp-content/uploads/[^"'\s)]+)""")
                .findAll(doc.html())
                .map { it.groupValues[1] }
                .firstOrNull { u ->
                    val base = java.net.URLDecoder.decode(u.substringAfterLast('/'), "UTF-8")
                    base.contains(want.replace("مسلسل ", "").trim().take(6))
                }
        }

        val episodes = ordered.map { e ->
            newEpisode(e.url) {
                this.name = e.title.ifBlank { e.num?.let { n -> "الحلقة $n" } ?: "حلقة" }
                this.episode = e.num
                this.posterUrl = poster
            }
        }

        Log.d(TAG, "series '$name' cat=$catId -> ${episodes.size} episodes")

        return newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ===================== loadLinks =====================

    private data class Srv(val name: String, val embed: String, val id: Int, val encrypted: Boolean)

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

            // --- PageData: الرموز التي يُلحقها الموقع بروابط سيرفرات معيّنة
            //     (Lody Plus ← TokenPlus1، ViD LO ← TokenVidlo). بدونها لا
            //     يعمل السيرفر إطلاقاً، وهذا سبب عدم ظهور أي تشغيل في الحلقات
            //     الجديدة التي لا تحتوي إلا على «Lody Plus 1».
            var tokenVidlo = ""
            var tokenPlus1 = ""
            runCatching {
                val i = html.indexOf("PageData")
                if (i >= 0) {
                    val b = html.indexOf('{', i)
                    if (b >= 0) {
                        val e = findMatchingBrace(html, b)
                        if (e > b) {
                            val o = org.json.JSONObject(html.substring(b, e + 1))
                            tokenVidlo = o.optString("TokenVidlo", "")
                            tokenPlus1 = o.optString("TokenPlus1", "")
                        }
                    }
                }
            }

            // هل يُخفي الموقع محتوى المشغّل للزوار؟
            val encryptedMode = html.contains("const EncryptionStatus = true")

            if (servers.isEmpty()) {
                Log.w(TAG, "no servers on page")
                return false
            }

            // كل سيرفر يُحلّ على حدة ثم نجمع — السيرفرات التي لا تُحلّ تُتجاهل
            // (رابط صفحتها ليس وسائط: يجعل المشغّل يعطي خطأ 2004 وينقطع).
            val targets = mutableListOf<Pair<Srv, String>>()
            val done = mutableSetOf<String>()

            // القاعدة كما في سكربت الموقع himself (SwitchServer):
            //   embed = base64(field)؛ ثم يُلحق الرمز الخاص بالسيرفر.
            fun embedOf(s: Srv): String? {
                val base = base64Decode(s.embed)?.trim()
                if (base.isNullOrBlank()) return null
                return when (s.id) {
                    LODY_PLUS_ID -> base + tokenPlus1
                    VIDLO_ID -> base + tokenVidlo
                    else -> base
                }
            }

            // Lody Plus: الحقل فارغ لكن القاعدة تُلحق TokenPlus1 — وهو
            // صفحة تشغيل على الموقع نفسه وليست iframe لموقع خارجي، لذلك هي
            // ما يُشغّل الحلقات الجديدة فعلاً. نُمرّرها عبر tryManualExtract
            // وليس كرابط مباشر: بعض الحلقات تُحمِّل الوسائط ديناميكياً
            // (وهنا kebab page) فلا يُحسم mp4/m3u8 إحصاءً مسبقاً.
            val plus1 = servers.firstOrNull { it.id == LODY_PLUS_ID }
            if (plus1 != null && !tokenPlus1.isNullOrBlank()) {
                val pageUrl = data.substringBefore('?').trimEnd('/')
                val direct = pageUrl + "/" + tokenPlus1
                if (done.add(direct)) {
                    Log.d(TAG, "Lody Plus -> direct page: $direct")
                    targets.add(plus1 to direct)
                }
            }

            for (s in servers) {
                if (s.id == LODY_PLUS_ID) continue          // عُولج أعلاه
                if (s.id == VIP_ID) { Log.d(TAG, "skip VIP (empty embed)"); continue }
                if (s.id == VIDLO_ID) {
                    // ViD LO (رقم 116413): مضيفه www.vidlo.us أصبح 404 لروابط
                    // embed-{id}.html وليس له مُستخرِج مدمج في التطبيق — لا يُنتج
                    // تشغيلاً أبداً، فيُحذف لكيلا يملأ قائمة السيرفرات باسم "vid lo"
                    // بلا روابط قابلة للتشغيل.
                    Log.d(TAG, "skip ViD LO (dead host, no built-in extractor)")
                    continue
                }
                if (s.embed.isBlank()) { Log.d(TAG, "skip ${s.name} (empty embed)"); continue }
                val embedUrl = embedOf(s)
                if (embedUrl.isNullOrBlank()) { Log.w(TAG, "${s.name}: base64 decode failed"); continue }
                if (!done.add(embedUrl)) continue
                targets.add(s to embedUrl)
            }

            if (targets.isEmpty()) {
                Log.w(TAG, "no usable servers (all embeds empty)")
                return false
            }
            Log.d(TAG, "encryptedMode=$encryptedMode targets=${targets.size}")

            // مهم للأداء: كل السيرفرات بالتوازي. سابقاً كان كل سيرفر يُنتظر
            // على حدة (~3–5 ثوانٍ لكل واحد لـ megamax) فيصل المجموع ~25 ثانية
            // ويظن المستخدم أن السيرفرات «لا تستجيب».
            val results = coroutineScope {
                targets.map { (s, u) ->
                    async {
                        val links = mutableListOf<ExtractorLink>()
                        val subs = mutableListOf<SubtitleFile>()
                        try {
                            resolveServer(u, s.name, { subs.add(it) }, { links.add(it) })
                        } catch (e: Exception) {
                            Log.w(TAG, "${s.name}: ${e.message}")
                        }
                        Triple(s.name, subs, links)
                    }
                }.awaitAll()
            }

            var any = false
            for ((_, subs, links) in results) {
                subs.forEach { subtitleCallback.invoke(it) }
                links.forEach { callback.invoke(it); any = true }
            }
            Log.d(TAG, "loadLinks done: $any (${results.sumOf { it.third.size }} links)")
            return any
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks error", e)
            return false
        }
    }

    /**
     * يحاول إظهار سيرفر واحد بعدة طرق، ويعيد عدد الروابط التي أُرسلت.
     * 1) مُجمِّع megamax (يعطي عشرات المرايا المعروفة دفعة واحدة)
     * 2) مُستخرِجات CloudStream المدمجة
     * 3) استخراج يدوي من الصفحة
     *
     * لا نُرسل رابط الـ embed نفسه أبداً: هو صفحة HTML، فيعطي المشغّل خطأ
     * 2004 (ERROR_CODE_IO_BAD_HTTP_STATUS) ولا يستطيع التقديم/التأخير فيها.
     */
    private suspend fun resolveServer(
        embedUrl: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val referer = originOf(embedUrl)

        // 0) Lody Plus: الرابط صفحة تشغيل على الموقع، والمشغّل داخلها يبني
        //    رابط الوسائط من PageData. نجلبها ونستخرج m3u8/mp4 مباشرة.
        if (referer.contains("lodynet.top")) {
            try {
                if (tryManualExtract(embedUrl, referer, serverName, callback)) return 1
            } catch (_: Exception) {
            }
            Log.w(TAG, "$serverName: Lody Plus page had no direct media")
            return 0
        }

        // 1) megamax — صفحته تحمل قائمة مرايا كاملة (720p/480p…)
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

        Log.w(TAG, "$serverName: no direct media — skipped (no HTML pages emitted)")
        return 0
    }

    /**
     * megamax.me صفحة تشغيل Laravel/Inertia. بيانات الفيديو تُطلب لاحقاً عبر
     * "partial reload" — بطلب نفس الرابط مع ترويسات X-Inertia نحصل على JSON
     * يحوي كل الجودات وكل المرايا (voe, mixdrop, doodstream, lulustream…)،
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

            // نرتّب الجودات من الأعلى إلى الأدنى حتى يبدأ المشغّل بأفضل جودة
            val order = (0 until qualities.length()).mapNotNull { qualities.optJSONObject(it) }
                .sortedByDescending { qualityOf(it.optString("label", ""), it.optString("resolution", "")) }

            // كل المرايا بالتوازي أيضاً — بعضها يستغرق ثوانيَ ويوقف البقية.
            data class Mirror(val label: String, val qNum: Int, val driver: String, val link: String)

            val mirrors = mutableListOf<Mirror>()
            for (q in order) {
                val qLabel = q.optString("label", "HD").trim()
                val resolution = q.optString("resolution", "").trim()
                val qNum = qualityOf(qLabel, resolution)
                val ms = q.optJSONArray("mirrors") ?: continue
                var qEmitted = 0
                for (mi in 0 until ms.length()) {
                    if (qEmitted >= 3) break            // لا نُغرق القائمة
                    val m = ms.optJSONObject(mi) ?: continue
                    val driver = m.optString("driver", "").trim()
                    val rawLink = m.optString("link", "").trim()
                    // بعض المرايا تصل فارغة أو بحرفية "0 Bytes" — نتجاهلها
                    if (rawLink.isBlank() || rawLink.contains("Byt", true)) continue
                    var link = rawLink
                    if (link.startsWith("//")) link = "https:$link"
                    if (!link.startsWith("http")) continue
                    if (isDeadUrl(link)) continue
                    if (!tried.add(link)) continue
                    mirrors.add(Mirror(qLabel, qNum, driver, link))
                    qEmitted++
                }
            }

            val resolved = coroutineScope {
                mirrors.map { m ->
                    async {
                        // callback الـ loadExtractor ليس suspend، لذا نجمع أولاً
                        // ثم نُعيد الإرسال بعد خروجه (newExtractorLink مُعلَّمة suspend).
                        val collected = mutableListOf<ExtractorLink>()
                        try {
                            loadExtractor(m.link, originOf(m.link), { }) { l -> collected.add(l) }
                        } catch (e: Exception) {
                            Log.d(TAG, "megamax [${m.label}/${m.driver}] miss: ${e.message}")
                        }
                        m to collected
                    }
                }.awaitAll()
            }

            for ((m, links) in resolved) {
                for (l in links) {
                    // لا نُمرّر إلا روابط وسائط حقيقية — أي رابط صفحة HTML
                    // يجعل المشغّل يعطي خطأ 2004 أو ينقطع عند التقديم.
                    if (isDeadUrl(l.url)) {
                        Log.w(TAG, "megamax [${m.label}/${m.driver}] HTML page, not media — skipped")
                        continue
                    }
                    // نضمن كتابة رقم الجودة حتى لو أعاد المُستخرِج Unknown،
                    // فيختار المشغّل الجودة الصحيحة ولا ينقطع أثناء التقديم.
                    val qv = if (l.quality == Qualities.Unknown.value) m.qNum else l.quality
                    callback.invoke(
                        newExtractorLink(
                            source = "لودي نت",
                            name = "${m.label} · ${m.driver.ifBlank { "server" }}",
                            url = l.url,
                            type = l.type
                        ) {
                            this.quality = qv
                            this.referer = l.referer
                            this.headers = l.headers
                        }
                    )
                    emitted++
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
            val media = extractMediaUrls(text).filterNot { isDeadUrl(it) }
            if (media.isEmpty()) {
                Log.d(TAG, "[$label] no direct media in page")
                return false
            }
            media.forEach { u ->
                // m3u8 (أو امتداد HLS) ← M3U8. بقية الروابط بلا امتداد معروف
                // (وثمة ملفات .m4s/مقاطع DASH) تُعامَد كفيديو مباشر، لا HLS:
                // المشغّل يرفض DASH مقسّماً دون مانيfest فيدى ويعطي 2004.
                val isHls = u.contains(".m3u8", true) || u.contains(".m3u", true)
                val hasVideoExt = u.contains(".mp4", true) || u.contains(".webm", true) ||
                    u.contains(".mov", true) || u.contains(".m4s", true) || u.contains(".ts", true)
                val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val finalUrl = if (u.startsWith("//")) "https:$u" else u
                emit(callback, if (label.isBlank()) "Lody Plus" else label, finalUrl, type, originOf(embedUrl))
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
