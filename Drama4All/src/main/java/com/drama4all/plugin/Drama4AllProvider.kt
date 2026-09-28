package com.drama4all.plugin

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences
import android.util.Log

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

// LIBRARY item embedded on drama4all list + search pages.
// ★ `public_slug` هو المعرّف العام الذي صار عليه الموقع (d4a-xxxx)؛ و`slug` هو
//   المعرّف الداخلي القديم (sf_…). الـapi يقبل الداخلي فقط، فنحتفظ بالاثنين معاً.
private data class SearchItem(
    val cover: String? = null,
    val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int = 0,
    val slug: String? = null,
    @JsonProperty("public_slug") val publicSlug: String? = null,
    val title: String? = null,
    val views: Long? = null,
    @JsonProperty("likes_count") val likesCount: Long? = null,
    val rating: Double? = null,
    val genres: List<String>? = null,
)

// /api/episode/{slug}/{ep}
private data class EpisodeItem(
    @JsonProperty("video_url") val videoUrl: String? = null,
    val subs: List<SubtitleItem>? = null,
)

private data class SubtitleItem(
    val lang: String? = null,
    val url: String? = null,
)

class Drama4AllProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "دراما للجميع"
    override var mainUrl = "https://drama4all.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "list/trending" to "الأكثر مشاهدة",
        "list/recent" to "أضيف حديثاً",
        "list/all" to "المكتبة",
    )

    // Robustly extract the `const LIBRARY = [...]` JSON array token from an HTML page.
    // Uses a real bracket matcher (JSON-aware) instead of the naive indexOf('[')/lastIndexOf(']'),
    // which grabs too much when other arrays/JS follow the LIBRARY array (e.g. on /search pages).
    private fun extractLibraryArray(html: String): String? {
        val mark = html.indexOf("const LIBRARY")
        if (mark < 0) return null
        val start = html.indexOf('[', mark)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (k in start until html.length) {
            val c = html[k]
            if (!inStr) {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return html.substring(start, k + 1) }
                }
            } else {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            }
        }
        return null
    }

    private fun parseLibrary(html: String): List<SearchItem> {
        val arr = extractLibraryArray(html) ?: return emptyList()
        return try {
            mapper.readValue(
                arr,
                object : com.fasterxml.jackson.core.type.TypeReference<List<SearchItem>>() {}
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        val t = title ?: return null
        // ★ نعتمد المعرّف العام `public_slug` (d4a-…) لا القديم `slug` (sf_…):
        //   الموقع نفسه صار يروّج للروابط العامة، والمعرّف القديم لم يعد يعمل.
        //   وإن غاب نعود للقديم — لا نفقد شيئاً.
        val s = publicSlug ?: slug ?: return null
        return newTvSeriesSearchResponse(t, "$mainUrl/series/$s", TvType.TvSeries) {
            posterUrl = cover
            episodes = totalEpisodes.coerceAtLeast(1)
            score = rating?.let { Score.from(it, 10) }
        }
    }

    private fun List<SearchItem>.mapResults(): List<SearchResponse> =
        mapNotNull { it.toSearchResponse() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = "$mainUrl/${request.data}"
            val doc = app.get(url, referer = mainUrl).document
            val items = doc.select("script")
                .mapNotNull { el -> el.html().takeIf { it.contains("const LIBRARY") } }
                .flatMap { parseLibrary(it) }
                .mapResults()
            if (items.isEmpty()) null else newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            // /api/local_search is capped (≈20, and returns narto nt_ too), so it misses most
            // drama4all sf_ works. The site's own /search?q= page embeds the FULL result set in
            // its `const LIBRARY` array — parse that instead.
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val res = app.get("$mainUrl/search?q=$q", referer = mainUrl).text
            val items = parseLibrary(res)
            if (items.isEmpty()) return emptyList()
            items.mapResults()
        } catch (e: Exception) {
            null
        }
    }

    // ★★ تغيّرت الواجهة (r12): حاوية الحلقات صارت `#r12Episodes` بـ`a.r12-ep[data-ep]`
    //   بعد أن كانت `div.eps`. ومع `div.eps` لا يطابق شيءً، فتظهر «قريباً».
    private val EP_ROW = "a.r12-ep[data-ep]"

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = mainUrl).document
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: return null
            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            // ★ تغيّرت الواجهة (r12): صار الوصف في `p.r12-desc` والتصنيفات في
            //   `span.r12-tag`، وأول وسوم «HD» ليس تصنيفاً.和老 fallbacks القديمة.
            val description = doc.selectFirst("p.r12-desc")?.text()?.trim()
                ?: doc.selectFirst("p.synopsis")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            val tags = doc.select("div.r12-tags span.r12-tag").mapNotNull { it.text()?.trim() }
                .ifEmpty { doc.select("div.stage-tags a.tag").mapNotNull { it.text()?.trim() } }
                .filter { it.isNotEmpty() && !it.equals("HD", ignoreCase = true) }
                .distinct()

            // ★ المعرّف الداخلي `sf_…` الذي يطلبه الـapi — مضمّن في `const SERIES`
            //   داخل صفحة السلسلة نفسها. والمعرّف العام `d4a-…` يُرجع 403 من الـapi.
            val internalSlug = doc.select("script").asSequence()
                .map { it.html() }
                .mapNotNull {
                    Regex("""const SERIES\s*=\s*\{[^}]*?slug:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                        .find(it)?.groupValues?.get(1)
                }
                .firstOrNull()
            val publicSlug = Regex("""/series/([\w\-]+)""").find(url)?.groupValues?.get(1)

            // ★★ الرقم في السمة `data-ep` لا في نص الرابط: فالصفحة تكتب
            //   `<span data-i18n="episode">الحلقة</span> 1` والرقم في عقدة نص شقيقة،
            //   فكان `el.text().toIntOrNull()` يعطي null دائماً وتُرفض كل الحلقات.
            val eps = doc.select(EP_ROW).mapNotNull { el ->
                val ep = el.attr("data-ep").trim().toIntOrNull() ?: return@mapNotNull null
                newEpisode("/watch/$publicSlug/$ep|api|$internalSlug") {
                    episode = ep
                    name = "الحلقة $ep"
                }
            }
            Log.d("Drama4All", "load public=$publicSlug internal=$internalSlug episodes=${eps.size}")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                posterUrl = poster
                plot = description
                this.tags = tags
            }
        } catch (e: Exception) {
            Log.e("Drama4All", "load FAILED $url", e)
            null
        }
    }

    // The /api/episode endpoint is server-side gated: a bare request returns HTTP 403
    // {"error":"forbidden"} (Cloudflare-backed). The watch page embeds a short-lived signed
    // ticket in JS: EP_TOKEN (hex) + EP_TOKEN_EXP (unix). The API only answers when called as
    //   /api/episode/{slug}/{ep}?t={Date.now()}&exp={EP_TOKEN_EXP}&token={EP_TOKEN}
    // The token is NOT per-episode: one watch page's token serves all episodes of the work
    // (verified: ep=1 token fetched ep002 too). Window is short (~30s), so on a 403 we re-fetch
    // a fresh watch page and retry once — same resilience pattern as the narto v16 retry.
    //
    // ★ التوقيع يُؤخذ من صفحة `/watch/` بالمعرّف **العام**، بينما يُنادى الـapi بالمعرّف
    //   **الداخلي** — والتوكن لا يقيّد المعرّف إطلاقاً (قِيس: توكن صفحة `d4a-…` أنتج
    //   رابطاً حقيقياً عند طلب `sf_…`، والعكس 403). فالتوقيع العام يخدم أيّهما.
    private suspend fun signedEpisode(internal: String, ep: String, publicSlug: String): EpisodeItem? {
        var attempt = 0
        while (attempt < 2) {
            attempt++
            try {
                // 1) fetch the watch page for THIS ep to obtain the live EP_TOKEN / EP_TOKEN_EXP
                val watchPath = "$mainUrl/watch/$publicSlug/$ep"
                val watchHtml = app.get(watchPath, referer = mainUrl).text
                val token = Regex("""EP_TOKEN\s*=\s*"([^"]+)""").find(watchHtml)?.groupValues?.get(1)
                val exp = Regex("""EP_TOKEN_EXP\s*=\s*(\d+)""").find(watchHtml)?.groupValues?.get(1)
                if (token == null || exp == null) {
                    Log.e("Drama4All", "signedEpisode token missing public=$publicSlug ep=$ep")
                    // token absent -> can't sign -> give up (no point retrying the same page)
                    return null
                }
                // 2) call the gated API with the signature (t = current epoch millis)
                val t = System.currentTimeMillis()
                val url = "$mainUrl/api/episode/$internal/$ep?t=$t&exp=$exp&token=${java.net.URLEncoder.encode(token, "UTF-8")}"
                val json = app.get(url, referer = watchPath).text
                if (json.contains("\"error\":\"forbidden\"")) {
                    // token likely expired between page-load and now -> refetch once for a fresh one
                    Log.e("Drama4All", "signedEpisode forbidden (retry) internal=$internal ep=$ep")
                    if (attempt < 2) { Thread.sleep(600) ; continue }
                    return null
                }
                return mapper.readValue(json, EpisodeItem::class.java)
            } catch (e: Exception) {
                Log.e("Drama4All", "signedEpisode error attempt=$attempt internal=$internal ep=$ep", e)
                if (attempt < 2) { try { Thread.sleep(600) } catch (ei: InterruptedException) { Thread.currentThread().interrupt() } }
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // data = /watch/<publicSlug>/<ep>|api|<internalSlug>
            val m = Regex("""/watch/([\w\-]+)/(\d+)""").find(data) ?: return false
            val ep = m.groupValues[2]

            // ★★ الـapi يقبل المعرّف الداخلي `sf_…` فقط: طلبُ المعرّف العام `d4a-…`
            //   يُرجع 403 «forbidden» دائماً. فنأخذه من `load` حيث مرّ بصفحة السلسلة،
            //   وإن غاب (رابط قديم محفوظ) نعيد استخراجه من صفحة المشاهدة نفسها.
            val carried = data.substringAfter("|api|", "").trim()
            val internal = when {
                carried.startsWith("sf_") || carried.startsWith("nt_") -> carried
                // رابط قديم محفوظ بلا المعرّف الداخلي: صفحة المشاهدة تُعلنه بجوار التوكن
                // في `const SLUG = "sf_…"`، فنستعيده منها بدل أن نخسر الحلقة.
                else -> {
                    val w = try {
                        app.get("$mainUrl${data.substringBefore("|api|")}", referer = mainUrl).text
                    } catch (e: Exception) {
                        Log.e("Drama4All", "recoverSlug page FAILED $data", e); ""
                    }
                    Regex("""const\s+SLUG\s*=\s*"([^"]+)"""").find(w)?.groupValues?.get(1).orEmpty()
                }
            }
            if (internal.isEmpty()) {
                Log.e("Drama4All", "loadLinks no internal slug for $data")
                return false
            }

            val item = signedEpisode(internal, ep, m.groupValues[1]) ?: return false
            val vUrl = item.videoUrl ?: return false

            // 1) كل الترجمات حسب اللغة — نُرسل كل لغة مرة واحدة فقط.
            //    API يعطي تسميات لغة جاهزة (مثل "بالعربية" / "English" / "ar") وقد يُكرّر نفس الرابط
            //    (مِثل ملفي .vtt عربيين متطابقين في sf_ أو نفس .srt عبر لغات). نستبعد التكرار بالرابط
            //    ثم نتجاهل تكرار نفس اللغة لنفس الحلقة.
            val seenSub = HashSet<String>()
            val seenLang = HashSet<String>()
            item.subs?.forEach { s ->
                val lang = s.lang?.trim() ?: return@forEach
                val subUrl = s.url?.trim() ?: return@forEach
                if (subUrl.isEmpty() || !seenSub.add(subUrl)) return@forEach
                if (lang.isEmpty()) { // رابط بلا تسمية لغة -> نمرّره مرة واحدة
                    try { subtitleCallback(newSubtitleFile("ترجمة", subUrl)) } catch (e: Exception) {}
                    return@forEach
                }
                if (!seenLang.add(lang)) return@forEach // نفس اللغة مكررة -> مرة واحدة
                try { subtitleCallback(newSubtitleFile(lang, subUrl)) } catch (e: Exception) {}
            }

            // 2) الجودة الحقيقية من مسار الرابط: nsstorage يبني المسار على هيئة /{lang}/{N}p/{hash}/...
            //    (مثال /ar_SA/1080p/) — نستخرجها بدل افتراض 480p افتراضياً. إن لم نجد الجزء بقي الافتراضي.
            val qualityFromPath: (String) -> String = { url ->
                val m = Regex("""/(\d{3,4})p/""").find(url)
                m?.groupValues?.get(1)?.let { "${it}p" }
                    ?: url.let { u ->
                        when {
                            u.contains("1080") -> "1080p"
                            u.contains("720") -> "720p"
                            u.contains("480") -> "480p"
                            else -> "480p"
                        }
                    }
            }

            val isMp4 = vUrl.lowercase().contains(".mp4") || vUrl.lowercase().contains(".m4v")
                || vUrl.lowercase().contains("mime_type=video_mp4")
            // ★ ترتيب الجودات هنا بلا أثر: الفروع الثلاثة أدناه متنافسة، وواحد فقط
            //   منها ينفّذ في كل حلقة — فالمصدر يبث رابطاً واحداً لا غير، ولا شيء
            //   يُخزَّن ولا يُعاد ترتيبه (البث كما هو حرفياً).
            if (isMp4) {
                // nt_ family: ملف مباشر واحد (R2 .mp4) — الجودة هي الوحيدة المتوفرة من المصدر
                val q = qualityFromPath(vUrl)
                callback(
                    newExtractorLink(source = name, name = "ملف كامل ($q)", url = vUrl, type = ExtractorLinkType.VIDEO) {
                        referer = mainUrl
                        quality = getQualityFromName(q)
                        headers = mapOf("Referer" to mainUrl)
                    }
                )
            } else {
                // HLS: master (جودات متعددة) أم ملف media واحد؟
                val streamText = try { app.get(vUrl, referer = mainUrl).text } catch (e: Exception) { "" }
                if (streamText.contains("#EXT-X-STREAM-INF")) {
                    // master كامل — يشمل كل الجودات المتوفرة، المشغّل يختارها
                    callback(
                        newExtractorLink(source = name, name = "كل الجودات (متغير)", url = vUrl, type = ExtractorLinkType.M3U8) {
                            referer = mainUrl
                            quality = getQualityFromName("1080p")
                            headers = mapOf("Referer" to mainUrl)
                        }
                    )
                } else {
                    // ملف media واحد — الجودة الحقيقية موجودة في المسار (sf_ family: single 1080p)
                    val q = qualityFromPath(vUrl)
                    callback(
                        newExtractorLink(source = name, name = q, url = vUrl, type = ExtractorLinkType.M3U8) {
                            referer = mainUrl
                            quality = getQualityFromName(q)
                            headers = mapOf("Referer" to mainUrl)
                        }
                    )
                }
            }
            true
        } catch (e: Exception) {
            Log.e("Drama4All", "loadLinks FATAL", e)
            false
        }
    }
}
