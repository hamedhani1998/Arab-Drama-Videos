package com.deepdrama.plugin

import cloudstreamshared.FormatTag
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val DD_MAIN = "https://www.deep-drama.com"
private const val DD_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

/** ترميز مقطع مسار عربي (لا يترك المقطع على شكل بايتات خام). */
private fun encPath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/**
 * DeepDrama — قياسٌ حيّ لموقع deep-drama.com (2026-10-08).
 *
 * ══ عائلتان في موقع واحد — وهذا هو مفتاح المشكلة ══
 *
 *  ١) عائلة «المسلسلات» (CPT `dd_series`) — الصفحة `/مسلسل/<slug>-<id>/`:
 *     بطاقات `series-card`، والحلقات من `wp-json/deep-drama/v1/playlist/{id}`،
 *     والروابط من `wp-json/deep-drama/v1/episode/{id}/{number}`.
 *     هذه هي التي تصلح لها «حلقة» و«خادم».
 *
 *  ٢) عائلة «المقالات» (`post`) — الصفحة `/2026/10/<slug>.html`:
 *     لا `data-id` فيها إطلاقاً (قِيس: صفر)، ولذلك **لا** تصلح معها واجهة
 *     `playlist`/`episode` (قِيس: `playlist/13612` ⇒ 404). الروابط فيها
 *     مخزَّنة كأزرارٍ مباشرة: `<button class="xr-server-btn" data-src="…">`
 *     وتشير إلى مضيفات جاهزة (رامبل / voe.sx / vidaraa) — «سيرفرات خاصة»
 *     كما وصفها المستخدم. فيديو واحد لكل صفحة ⇒ فيلم لا مسلسل.
 *
 *  ★ الرئيسية تعرض 20 بطاقة `xr-card` **كلها من العائلة الثانية** (قِيس)،
 *    والبحث `/?s=` يعرض `xr-card` كذلك. أما `/المسلسلات/` فتعرض 24 بطاقة
 *    `series-card` من العائلة الأولى. لذلك يجب دعم الاثنتين وإلا ظهرت
 *    الرئيسية بلا شيء، أو انفتحت بطاقة على رابط لا يعمل.
 *
 *  ★ مرساة البطاقة: `<article class="series-card" data-post="9437">` — أي
 *    بخصائص. اشتراط `>` بعد اسم الصنف أعطى صفر بطاقة وأفرغ الرئيسية (سابقاً).
 */

class DeepDramaProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = DD_MAIN
    override var name = "Deep Drama"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    /** قسم الرئيسية الخاص بالمقالات (بطاقات `xr-card` ذات روابط html). */
    private val HOME_KEY = "__home__"

    override val mainPage = mainPageOf(
        HOME_KEY to "الأحدث",
        "الرائج" to "الرائج",
        "الشائع" to "الشائع",
        "الجديد" to "الجديد",
        "الأكثر-مشاهدة" to "الأكثر-مشاهدة",
    )

    private fun headers() = mapOf(
        "User-Agent" to DD_UA,
        "Accept-Language" to "ar",
        "Referer" to "$DD_MAIN/",
    )

    private data class DdCard(val title: String, val url: String, val poster: String?, val movie: Boolean)
    private data class DdEpisode(val number: Int, val locked: Boolean)

    // ---------- تحليل البطاقات ----------

    // ★ لا تشترط `>` بعد الصنف: البطاقة تحمل خصائص (data-post/data-series).
    private val seriesCardRe =
        Regex("""<article class="series-card"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
    private val seriesHrefRe = Regex("""<a\s+href="([^"]+)"\s+class="poster-link"""")
    private val seriesImgRe = Regex("""<img class="series-poster"([^>]*)>""")
    private val seriesTitleRe = Regex("""<h3>\s*<a [^>]*>(.*?)</a>\s*</h3>""", RegexOption.DOT_MATCHES_ALL)

    // الشكل الثاني — الرئيسية والبحث على المحرّك الجديد (روابط مقالات html).
    private val xrCardRe =
        Regex("""<article class="xr-card"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
    private val xrHrefRe = Regex("""<a\s+href="([^"]+)"""")
    private val xrTitleRe =
        Regex("""<h3 class="xr-card__title"[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
    private val xrImgRe = Regex("""<img[^>]*\salt="([^"]*)"""")
    private val xrSrcRe = Regex("""<img[^>]*\ssrc="([^"]+)"""")

    /** زرّ خادم في صفحة المقال. */
    private val xrButtonRe =
        Regex("""<button[^>]*\bclass="[^"]*xr-server-btn[^"]*"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private val dataSrcRe = Regex("""\bdata-src="([^"]+)"""")
    private val iframeSrcRe = Regex("""<iframe[^>]*\bdata-legacy-src="([^"]+)"""")

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

    /** صفحات المقالات: `/2026/10/xxx.html` (وقِيس أن `data-id` فيها صفر). */
    private fun isPostUrl(u: String): Boolean =
        Regex("""/20\d\d/\d\d/[^/]+\.html""").containsMatchIn(u)

    private fun parseCards(html: String): List<DdCard> {
        val out = ArrayList<DdCard>()

        for (m in seriesCardRe.findAll(html)) {
            val body = m.groupValues[1]
            val url = seriesHrefRe.find(body)?.groupValues?.get(1)?.trim() ?: continue
            val title = seriesTitleRe.find(body)?.groupValues?.get(1)?.let { stripTags(it) }
                ?.takeIf { it.isNotBlank() } ?: continue
            val attrs = seriesImgRe.find(body)?.groupValues?.get(1).orEmpty()
            val posters = Regex("""\s+data-posters="([^"]*)"""").find(attrs)?.groupValues?.get(1)
            val src = Regex("""\s+data-src="([^"]+)"""").find(attrs)?.groupValues?.get(1)
                ?: Regex("""\s+src="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            out.add(DdCard(title, url, firstPoster(posters) ?: src, movie = false))
        }
        if (out.isNotEmpty()) return out

        // الشكل الثاني (الرئيسية والبحث).
        for (m in xrCardRe.findAll(html)) {
            val body = m.groupValues[1]
            val url = xrHrefRe.find(body)?.groupValues?.get(1)?.trim() ?: continue
            val h3 = xrTitleRe.find(body)?.groupValues?.get(1)?.let { stripTags(it) }
            val alt = xrImgRe.find(body)?.groupValues?.get(1)?.trim()
            val title = listOfNotNull(h3, alt).firstOrNull { !it.isNullOrBlank() } ?: continue
            val src = xrSrcRe.find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { !it.startsWith("data:") }
            out.add(DdCard(title, url, src, isPostUrl(url)))
        }
        return out
    }

    /** `data-posters` قائمة JSON مُهرَّبة (`&quot;` و`\/`) — نأخذ أول رابط صالح. */
    private fun firstPoster(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val s = raw.replace("&quot;", "\"").replace("&#039;", "'").replace("\\/", "/")
        return Regex("""(https?:[^"]+)""").find(s)?.groupValues?.get(1)
            ?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun cardOf(c: DdCard): SearchResponse? {
        if (c.title.isBlank() || c.url.isBlank()) return null
        return if (c.movie) {
            newMovieSearchResponse(c.title, c.url, TvType.Movie) {
                this.posterUrl = c.poster?.takeIf { it.isNotBlank() }
            }
        } else {
            newTvSeriesSearchResponse(c.title, c.url, TvType.TvSeries) {
                this.posterUrl = c.poster?.takeIf { it.isNotBlank() }
            }
        }
    }

    // ---------- الرئيسية ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            if (request.data == HOME_KEY) {
                val html = app.get("$DD_MAIN/", headers = headers()).text
                val cards = parseCards(html).mapNotNull { cardOf(it) }
                if (cards.isEmpty()) null
                else newHomePageResponse(listOf(HomePageList(request.name, cards)))
            } else {
                val html = app.get(listingUrl(request.data, page), headers = headers()).text
                val cards = parseCards(html).mapNotNull { cardOf(it) }
                if (cards.isEmpty()) null
                else newHomePageResponse(listOf(HomePageList(request.name, cards)))
            }
        } catch (e: Exception) { null }
    }

    /** قائمة الوسم — تحمل أغلفتها و24 بطاقة، و`صفحة/N/` يعمل عليها (قيس). */
    private fun listingUrl(term: String, page: Int): String {
        val q = "?" + URLEncoder.encode("قائمة", "UTF-8") + "=" + URLEncoder.encode(term, "UTF-8")
        val base = if (page <= 1) "$DD_MAIN/${encPath("المسلسلات")}/"
        else "$DD_MAIN/${encPath("المسلسلات")}/${encPath("صفحة")}/$page/"
        return base + q
    }

    // ---------- البحث ----------

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val html = app.get("$DD_MAIN/?s=" + URLEncoder.encode(query, "UTF-8"), headers = headers()).text
            parseCards(html).mapNotNull { cardOf(it) }
        } catch (e: Exception) { null }
    }

    // ---------- التفاصيل ----------

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = headers()).document
            val html = doc.html()

            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() }
            val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() }

            // ★ صفحة مقال: فيديو واحد بخوادم مباشرة — لا playlist ولا حلقات.
            if (isPostUrl(url)) {
                return newMovieLoadResponse(title, url, TvType.Movie, "post|$url") {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }

            val id = seriesIdFrom(html) ?: resolveIdBySlug(url) ?: return null
            val eps = playlistEpisodes(id)
            if (eps.isEmpty()) return null

            val episodes = eps.map { e ->
                newEpisode("$id|${e.number}") {
                    this.name = if (e.locked) "الحلقة ${e.number} (مقفلة)" else "الحلقة ${e.number}"
                    this.episode = e.number
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) { null }
    }

    /**
     * معرّف العمل من الصفحة نفسها. مراسٍ مرتَّبة بالقوة (كلها مقيسة):
     *  ١) `data-id` في زرّ الحفظ — يظهر **مرةً واحدة** في صفحة العمل (`data-id="4488"`)،
     *     أما `data-series` فتظهر عند كل بطاقة «مشابهة» فلا تصلح مرجعاً.
     *  ٢) `wp-json/wp/v2/dd_series/<id>` المضمَّن في الوسم (shortlink).
     *  ٣) استعلام REST بالـslug — احتياطٌ حين يختلف القالب.
     */
    private fun seriesIdFrom(html: String): Int? {
        val ids = Regex("""data-id="(\d+)"""").findAll(html).map { it.groupValues[1] }.toList()
        if (ids.size == 1) return ids[0].toIntOrNull()
        return Regex("""wp-json/wp/v2/dd_series/(\d+)""").find(html)
            ?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun resolveIdBySlug(url: String): Int? {
        return try {
            val raw = url.trimEnd('/').substringAfterLast('/')
            val slug = java.net.URLDecoder.decode(raw, "UTF-8")
            if (slug.isBlank()) return null
            val text = app.get(
                "$DD_MAIN/wp-json/wp/v2/dd_series?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=id",
                headers = headers()
            ).text
            val root = mapper.readTree(text)
            if (root.isArray && root.size() > 0) root[0].get("id")?.asInt() else null
        } catch (e: Exception) { null }
    }

    private suspend fun playlistEpisodes(id: Int): List<DdEpisode> {
        return try {
            val text = app.get("$DD_MAIN/wp-json/deep-drama/v1/playlist/$id", headers = headers()).text
            val arr = mapper.readTree(text).get("episodes") ?: return emptyList()
            arr.mapNotNull { e ->
                val n = e.get("number")?.asInt() ?: return@mapNotNull null
                DdEpisode(n, e.get("locked")?.asBoolean() ?: false)
            }
        } catch (e: Exception) { emptyList() }
    }

    /** خوادم العمل كما يعلنها `playlist.servers` (قِيس: نترو دائماً، و«ريل فرين» أحياناً). */
    private suspend fun playlistServers(id: String): List<String> {
        return try {
            val text = app.get("$DD_MAIN/wp-json/deep-drama/v1/playlist/$id", headers = headers()).text
            val arr = mapper.readTree(text).get("servers") ?: return emptyList()
            arr.mapNotNull { it.get("id")?.asText()?.takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) { emptyList() }
    }

    // ---------- التشغيل ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // ★ صفحة مقال: خوادمها مخزَّنة كأزرار مباشرة.
            val postUrl = Regex("""\|(https?://.+)$""").find(data)?.groupValues?.get(1)
            if (postUrl != null) return loadPostLinks(postUrl, subtitleCallback, callback)

            // الحِمل "id|number" — بمؤشّر ثابت لأن CloudStream قد يُسبقه بـ mainUrl.
            val m = Regex("""(\d+)\|(\d+)""").find(data) ?: return false
            loadEpisodeLinks(m.groupValues[1], m.groupValues[2], subtitleCallback, callback)
        } catch (e: Exception) { false }
    }

    /**
     * روابط الحلقة.
     *
     * ★ الطريقة الصحيحة هي طريقة الموقع نفسه: يُنادى على الخادم الأول، فإن سقط
     *   يُنادى على التالي مع `failed=<السابق>` — وهذا هو ما يقيسه الموقع في
     *   `dd-player-data`. قِيس على 2406 (خادمان): بلا معاملات ⇒ نترو؛
     *   و`?server=reelfren&failed=narto` ⇒ `proxy.dramafren.org`. أما
     *   `?server=reelfren` **وحده** فيُتجاهل ويعيد نترو.
     *
     * ★ الشكل الأول `تلقائي` هو **موجِّه** `stream-e1.narto-drama.com/e/m/<base64>`
     *   يحمل نفس رابط الشكل الثاني داخل حقل `src` — فنبني منه الرابط الحقيقي
     *   بدل تمرير موجِّه لا يقدر أي مشغّل على فتحه (وهو سبب «Source error»).
     */
    private suspend fun loadEpisodeLinks(
        id: String,
        number: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val servers = playlistServers(id)
        val order = servers.ifEmpty { listOf("") }
        val collected = ArrayList<ExtractorLink>()
        var lastSubtitles: JsonNode? = null
        var server = ""

        for ((i, s) in order.withIndex()) {
            val failed = servers.take(i).joinToString(",")
            val text = try {
                var url = "$DD_MAIN/wp-json/deep-drama/v1/episode/$id/$number"
                val params = ArrayList<String>()
                if (s.isNotBlank()) params.add("server=" + URLEncoder.encode(s, "UTF-8"))
                if (failed.isNotBlank()) params.add("failed=" + URLEncoder.encode(failed, "UTF-8"))
                if (params.isNotEmpty()) url += "?" + params.joinToString("&")
                app.get(url, headers = headers()).text
            } catch (e: Exception) { null } ?: continue

            val root = try { mapper.readTree(text) } catch (e: Exception) { continue }
            server = root.get("server")?.asText().orEmpty()
            val label = serverLabel(server)
            val formats = root.get("formats")
            if (formats == null || !formats.isArray || formats.size() == 0) continue
            lastSubtitles = root.get("subtitles")

            // (الرابط ← الجودة والنوع). التكرار يُدمج — والشكل `تلقائي` هو نفسه
            // الشكل المسمّى بعد فكّ الموجِّه، فتُبقى التسمية المفيدة.
            val seen = LinkedHashMap<String, Fmt>()
            for (f in formats) {
                val raw = f.get("url")?.asText()?.takeIf { it.isNotBlank() } ?: continue
                val u = unwrapRedirector(raw) ?: if (raw.contains("/e/m/")) null else raw
                if (u.isNullOrBlank()) continue
                val q = f.get("quality")?.asText().orEmpty()
                val hls = f.get("type")?.asText().equals("hls", true) ||
                    u.contains(".m3u8", true) || u.contains("/api/proxy/", true)
                val old = seen[u]
                if (old == null || (isAutoLabel(old.q) && !isAutoLabel(q))) seen[u] = Fmt(q, hls)
            }
            if (seen.isEmpty()) continue

            seen.entries.forEachIndexed { idx, (u, fmt) ->
                val linkType = if (fmt.hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val nm = buildString {
                    append(label)
                    if (fmt.q.isNotBlank() && !isAutoLabel(fmt.q)) append(" · ").append(fmt.q)
                    if (seen.size > 1) append(" · ").append(idx + 1)
                }
                collected.add(newExtractorLink(name, FormatTag.tagged(nm, u, linkType), u, linkType) {
                    this.quality = getQualityFromName(if (fmt.q.endsWith("p")) fmt.q else "720p")
                    this.headers = headers()
                })
            }
            break // أول خادم يردّ بروابط هو المقصود — كما يفعل الموقع.
        }

        if (collected.isEmpty()) return false
        ordered(collected).forEach { callback(it) }
        emitSubtitles(lastSubtitles, subtitleCallback)
        return true
    }

    /**
     * روابط صفحة مقال: أزرار `xr-server-btn data-src` (رامبل / voe.sx / vidaraa)
     * تُمرَّر إلى مستخرِجات CloudStream المدمجة. قِيس أنها كلها تردّ 200.
     */
    private suspend fun loadPostLinks(
        postUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = try { app.get(postUrl, headers = headers()).text } catch (e: Exception) { return false }

        val servers = ArrayList<String>()
        xrButtonRe.findAll(html).forEach { b ->
            dataSrcRe.find(b.value)?.groupValues?.get(1)?.let { if (it.isNotBlank()) servers.add(it) }
        }
        if (servers.isEmpty()) {
            iframeSrcRe.find(html)?.groupValues?.get(1)?.let { servers.add(it) }
        }
        if (servers.isEmpty()) return false

        var ok = false
        for (s in servers.distinct()) {
            val hit = try {
                loadExtractor(s, postUrl, subtitleCallback, callback)
            } catch (e: Exception) { false }
            if (hit) ok = true
        }
        return ok
    }

    private suspend fun emitSubtitles(subs: JsonNode?, subtitleCallback: (SubtitleFile) -> Unit) {
        if (prefs?.getBoolean(DeepDramaSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) == false) return
        if (subs == null || !subs.isArray) return
        subs.forEach { s ->
            val u = firstOf(s, "url", "file", "src", "link")?.takeIf { it.isNotBlank() } ?: return@forEach
            // ★ زوج «عربي لاتيني» عبر subLangLabel: العرض يكتب `lang` حرفياً
            //   فـ«ar» تظهر هكذا، والاسم العربي وحده يعطي وسم IETF فارغاً؛
            //   الزوج يُصحّح العرض ويُبقي الوسم حيّاً للتحديد التلقائي.
            val code = (firstOf(s, "language", "lang", "code", "locale") ?: "ar").trim()
            if (code.isNotBlank()) subtitleCallback(newSubtitleFile(subLangLabel(code), u))
        }
    }

    /**
     * فكّ موجِّه `/e/m/`: مقطع واحد يحمل `<base64url(payload)>.<signature>`،
     * والرابط الحقيقي في `src`. يُعاد `null` إن تعذّر الفكّ كي يُسقَط الشكل
     * بدل تمرير ما لا يُشغَّل.
     */
    private fun unwrapRedirector(u: String): String? {
        val marker = "/e/m/"
        val i = u.indexOf(marker)
        if (i < 0) return null
        val seg = u.substring(i + marker.length).substringBefore('?').substringBefore('#')
        val dot = seg.indexOf('.')
        if (dot <= 0) return null
        return try {
            var b = seg.substring(0, dot).replace('-', '+').replace('_', '/')
            while (b.length % 4 != 0) b += "="
            val payload = String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), Charsets.UTF_8)
            Regex(""""src"\s*:\s*"([^"]+)"""").find(payload)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { null }
    }

    private fun isAutoLabel(q: String): Boolean =
        q.isBlank() || q == "تلقائي" || q.equals("auto", true)

    /** الشكل: اسم الجودة كما يعرضه الموقع، وهل الرابط m3u8. */
    private data class Fmt(val q: String, val hls: Boolean)

    /** ترتيب الجودات بحسب الإعداد — الافتراضي يبقي ترتيب الموقع حرفياً. */
    private fun ordered(list: List<ExtractorLink>): List<ExtractorLink> {
        return when (prefs?.getString(DeepDramaSettingsBottomSheet.KEY_QUALITY_ORDER, "default")) {
            "asc" -> list.sortedBy { it.quality }
            "desc" -> list.sortedByDescending { it.quality }
            else -> list
        }
    }

    private fun firstOf(node: JsonNode, vararg keys: String): String? {
        for (k in keys) {
            val v = node.get(k)?.asText()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    /** اسم الخادم كما يعرضه الردّ (الموقع يسمّيه «نترو دراما»). */
    private fun serverLabel(id: String): String = when (id.lowercase()) {
        "", "narto" -> "نترو دراما"
        "reelfren" -> "ريل فرين"
        "dramabuzz" -> "درامابَز"
        else -> id
    }
}