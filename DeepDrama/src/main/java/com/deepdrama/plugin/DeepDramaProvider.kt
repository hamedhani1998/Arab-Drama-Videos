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

/** ترميز مقطع مسار عربي (لا يترك أحد المقطعين على شكل بايتات خام). */
private fun encPath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/**
 * DeepDrama — قياسٌ حيّ لموقع deep-drama.com بعد انتقاله إلى WordPress.
 *
 * ★ سببُ الخطأ الذي أفرغ الرئيسية تماماً (قيس 2026-10-07):
 *   الموقع يعرض وسم البطاقة هكذا `<article class="series-card" data-post="9437">`
 *   بينما كان الكود يشترط `<article class="series-card">` **بلا أي خصيصة**،
 *   فكان التعبير النمطي يعيد صفراً دائماً ⇒ `parseCards` فارغة ⇒ `null` ⇒
 *   صفحةٌ رئيسية بلا أي صف. وهذا هو «لا يظهر شي في الواجهة الرئيسية» بالضبط.
 *   القاعدة: لا تشترط `>` مباشرةً بعد اسم الصنف — اقبل أي خصائص.
 *
 * anchors مقيسة (كلها 200):
 *   - صفوف الرئيسية: `/المسلسلات/?قائمة=الرائج|الشائع|الجديد|الأكثر-مشاهدة`
 *     وكلٌّ منها يردّ **24** بطاقة `<article class="series-card">` بغلافٍ حقيقي.
 *   - ترقيم الصفحات: `/المسلسلات/صفحة/2/` (وأيضاً مع `?قائمة=`).
 *   - البحث: `/?s=…` يردّ 200 ببطاقات `<article class="xr-card">`
 *     (شكلٌ مختلف! لذلك [parseCards] يتعامل مع الشكلين).
 *   - التفاصيل: `<button class="save-series" data-id="4488">` مرةً واحدة،
 *     ويحمل الوسم أيضاً `wp-json/wp/v2/dd_series/4488` كمرساةٍ ثانية.
 *   - الحلقات: `/wp-json/deep-drama/v1/playlist/{id}` ⇒ `{state:"ready", episodes:[…]}`.
 *   - التشغيل: `/wp-json/deep-drama/v1/episode/{id}/{number}` ⇒ `formats[]`.
 *
 * ملاحظة الخادم: `playlist.servers` = `[{"id":"narto","label":"نترو دراما"}]` —
 * خادمٌ واحد فقط، لا عدة خوادم. فلا يُختلق ما ليس موجوداً.
 */
class DeepDramaProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = DD_MAIN
    override var name = "Deep Drama"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val sections = listOf("الرائج", "الشائع", "الجديد", "الأكثر-مشاهدة")

    override val mainPage = mainPageOf(
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

    private data class DdCard(val title: String, val url: String, val poster: String?, val id: Int?)
    private data class DdEpisode(val number: Int, val locked: Boolean)

    // ---------- تحليل البطاقات ----------

    // ★ لا تشترط `>` بعد الصنف: البطاقة تحمل data-post، وهذا هو سبب الفراغ سابقاً.
    private val seriesCardRe =
        Regex("""<article class="series-card"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
    private val seriesHrefRe = Regex("""<a\s+href="([^"]+)"\s+class="poster-link"""")
    private val seriesImgRe = Regex("""<img class="series-poster"([^>]*)>""")
    private val seriesTitleRe = Regex("""<h3>\s*<a [^>]*>(.*?)</a>\s*</h3>""", RegexOption.DOT_MATCHES_ALL)

    // الشكل الثاني — صفحات البحث والرئيسية على المحرّك الجديد.
    private val xrCardRe =
        Regex("""<article class="xr-card"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
    private val xrHrefRe = Regex("""<a\s+href="([^"]+)"""")
    private val xrTitleRe = Regex("""<h3 class="xr-card__title"[^>]*>(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
    private val xrImgRe = Regex("""<img[^>]*\salt="([^"]*)"""")
    private val xrSrcRe = Regex("""<img[^>]*\ssrc="([^"]+)"""")

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

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
            val id = Regex("""\s+data-series="(\d+)"""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
            out.add(DdCard(title, url, firstPoster(posters) ?: src, id))
        }
        if (out.isNotEmpty()) return out

        // الاحتياط: شكل xr-card (البحث على المحرّك الجديد).
        for (m in xrCardRe.findAll(html)) {
            val body = m.groupValues[1]
            val url = xrHrefRe.find(body)?.groupValues?.get(1)?.trim() ?: continue
            val h3 = xrTitleRe.find(body)?.groupValues?.get(1)?.let { stripTags(it) }
            val alt = xrImgRe.find(body)?.groupValues?.get(1)?.trim()
            val title = listOfNotNull(h3, alt).firstOrNull { !it.isNullOrBlank() } ?: continue
            val src = xrSrcRe.find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { !it.startsWith("data:") }
            val id = Regex("""data-post="(\d+)"""").find(m.value)?.groupValues?.get(1)?.toIntOrNull()
            out.add(DdCard(title, url, src, id))
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
        return newTvSeriesSearchResponse(c.title, c.url, TvType.TvSeries) {
            this.posterUrl = c.poster?.takeIf { it.isNotBlank() }
        }
    }

    // ---------- الرئيسية ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val term = request.data.ifBlank { sections.first() }
            val html = app.get(listingUrl(term, page), headers = headers()).text
            val cards = parseCards(html).mapNotNull { cardOf(it) }
            if (cards.isEmpty()) null
            else newHomePageResponse(listOf(HomePageList(request.name, cards)))
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
     * معرّف العمل من الصفحة نفسها. ثلاث مراسٍ مرتَّبة بالقوة (كلها مقيسة):
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

    // ---------- التشغيل ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // الحِمل "id|number" — بمؤشّر ثابت لأن CloudStream قد يُسبقه بـ mainUrl.
            val m = Regex("""(\d+)\|(\d+)""").find(data) ?: return false
            val id = m.groupValues[1]
            val number = m.groupValues[2]

            val text = app.get(
                "$DD_MAIN/wp-json/deep-drama/v1/episode/$id/$number",
                headers = headers()
            ).text
            val root = mapper.readTree(text)
            val label = serverLabel(root.get("server")?.asText().orEmpty())

            val formats = root.get("formats")
            var emitted = 0
            if (formats != null && formats.isArray) {
                formats.forEachIndexed { i, f ->
                    val u = f.get("url")?.asText()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                    val type = f.get("type")?.asText().orEmpty()
                    val q = f.get("quality")?.asText().orEmpty()
                    val isHls = type.equals("hls", true) || u.contains(".m3u8", true)
                    val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    // ★ الاسم هو ما يراه المستخدم وحده (لا واجهة للجودة في الـAPI).
                    val nm = buildString {
                        append(label)
                        if (q.isNotBlank() && q != "تلقائي") append(" · ").append(q)
                        if (formats.size() > 1) append(" · ").append(i + 1)
                    }
                    callback(newExtractorLink(name, FormatTag.tagged(nm, u, linkType), u, linkType) {
                        this.quality = getQualityFromName(
                            if (q.endsWith("p")) q else "720p"
                        )
                        this.headers = headers()
                    })
                    emitted++
                }
            }

            if (prefs?.getBoolean(DeepDramaSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) != false) {
                val subs = root.get("subtitles")
                if (subs != null && subs.isArray) {
                    subs.forEach { s ->
                        val u = firstOf(s, "url", "file", "src", "link")
                            ?.takeIf { it.isNotBlank() } ?: return@forEach
                        val code = (firstOf(s, "language", "lang", "code", "locale") ?: "ar").trim()
                        if (code.isNotBlank()) subtitleCallback(newSubtitleFile(code, u))
                    }
                }
            }
            emitted > 0
        } catch (e: Exception) { false }
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