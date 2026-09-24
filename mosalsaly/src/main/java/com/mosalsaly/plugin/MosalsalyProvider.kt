package com.mosalsaly.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private const val MOS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val REEL_MAIN = "https://www.reelshort.com"

private const val GOOD_BASE = "https://goodshort.goodbos.online/hls"

// أسماء المنصات الثمانية عشر (كما في /sources) — تُستخدم أسماء الأقسام في الرئيسية
private val PLATFORMS = listOf(
    "dotdrama" to "DotDrama",
    "dramabite" to "DramaBite",
    "dramabox" to "DramaBox",
    "flickreels" to "FlickReels",
    "goodshort" to "GoodShort",
    "happyshort" to "HappyShort",
    "joyreels" to "JoyReels",
    "kalostv" to "KalosTV",
    "moboreels" to "MoboReels",
    "moreshort" to "MoreShort",
    "mydramawave" to "MyDramaWave",
    "netshort" to "NetShort",
    "petadrama" to "PetaDrama",
    "reelshort" to "Reelshort",
    "shorttv" to "ShortTV",
    "shortwave" to "ShortWave",
    "stardust" to "Stardust",
    "storyreel" to "StoryReel",
)

class MosalsalyProvider : MainAPI() {
    override var name = "Mosalsaly"
    override var mainUrl = "https://mosalsaly.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(*PLATFORMS.map { it.first to it.second }.toTypedArray())

    // جلب مع إعادة محاولة — الموقع بطيء/unstable؛ نفس نمط ReelShort
    private suspend fun getWithRetry(url: String, referer: String?, attempts: Int = 3, backoffMs: Long = 300): String {
        var last = ""
        for (i in 0 until attempts) {
            try {
                val text = app.get(url, headers = mapOf("User-Agent" to MOS_UA), referer = referer).text
                if (text.isNotBlank()) return text
            } catch (e: Exception) { last = "" }
            try { Thread.sleep(backoffMs) } catch (e: Exception) {}
        }
        return last
    }

    private val cardRe = Regex(
        """<article class="group "[^>]*>[\s\S]*?<a\s+[^>]*?(?:href="(/mosalsal/([^"/]*))"[^>]*?aria-label="([^"]*)"|aria-label="([^"]*)"[^>]*?href="(/mosalsal/([^"/]*))")[\s\S]*?<\s*img\b[^>]*?src="(https://[^"]+)""""
    )

    private fun parseCards(html: String): List<SearchResponse> {
        val seen = HashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (m in cardRe.findAll(html)) {
            // ترتيب المجموعات يعتمد على أيهما أتى أولاً (href ثم aria | aria ثم href)
            val title = if (m.groupValues[3].isNotBlank()) m.groupValues[3] else m.groupValues[4]
            val slug = if (m.groupValues[2].isNotBlank()) m.groupValues[2] else m.groupValues[6]
            if (title.isBlank() || slug.isBlank()) continue
            val poster = m.groupValues[7]
            val url = "$mainUrl/mosalsal/$slug"
            if (!seen.add(url)) continue
            out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val platform = request.data
        val url = if (page <= 1) "$mainUrl/masdar/$platform"
        else "$mainUrl/masdar/$platform/page/$page"
        val html = try { getWithRetry(url, mainUrl, 3, 300) } catch (e: Exception) { "" }
        if (html.isEmpty()) return null
        val items = parseCards(html)
        return if (items.isEmpty()) null else newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        if (query.trim().length < 3) return emptyList()
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val html = try {
            getWithRetry("$mainUrl/search?q=$q", mainUrl, 3, 300)
        } catch (e: Exception) { return emptyList() }
        return parseCards(html)
    }

    // قراءة كائن TVSeries من JSON-LD (<script type="application/ld+json">) — يعمل للنص غير المهرب
    private fun jsonLdSeries(html: String): Map<String, String?>? {
        val m = Regex(""""@type":\s*"TVSeries"""").find(html) ?: return null
        // نرجع إلى { المفتوح قبل المؤشر ثم نتتبع الأقواس حتى إغلاق الكائن
        val open = html.lastIndexOf('{', m.range.first)
        if (open < 0) return null
        var depth = 0
        for (i in open until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        // استخلاص الحقول المطلوبة من النص الخام للكائن
                        val block = html.substring(open, i + 1)
                        fun field(key: String): String? =
                            Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                                .find(block)?.groupValues?.get(1)
                                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
                        return mapOf(
                            "name" to field("name"),
                            "image" to field("image"),
                            "description" to field("description"),
                        )
                    }
                }
            }
        }
        return null
    }

    private data class EpInfo(val chapterId: String, val serial: Int, val cover: String?)

    // المصفوفة المهروبة: [...,"$L42",null,{"bookId":"...","episodes":[{...},...],"slug":...]
    // المفاتيح مهربة عادة (\"chapter_id\") لكن بعض الصفحات تكون غير مهربة — نتعامل مع الاثنين
    private fun parseEpisodes(html: String): List<EpInfo> {
        val out = mutableListOf<EpInfo>()
        // نقبل "episodes":[ و \"episodes\":[
        var idx = html.indexOf("\\\"episodes\\\":[")
        if (idx < 0) idx = html.indexOf("\"episodes\":[")
        if (idx < 0) return out
        // مقطع يبدأ عند episodes ويستمر حتى إغلاق المصفوفة — يدعم orders late/early bookId
        var depth = 0
        var closedAt = -1
        var i = idx
        while (i < html.length) {
            val ch = html[i]
            if (ch == '[') depth++
            else if (ch == ']') { depth--; if (depth == 0) { closedAt = i; break } }
            i++
        }
        if (closedAt < 0) return out
        val block = html.substring(idx, closedAt + 1)
        val objRe = Regex("""\{[^{}]*chapter_id[^{}]*\}""")
        for (m in objRe.findAll(block)) {
            val obj = m.value
            val chId = Regex("""(?:\\")?chapter_id(?:\\")?:\s*(?:\\")?([0-9a-zA-Z]+)(?:\\")?""")
                .find(obj)?.groupValues?.get(1) ?: continue
            val ser = Regex("""(?:\\")?serial_number(?:\\")?:\s*(\d+)""")
                .find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            // ReelShort يبدأ serial من 0 — لا نفيلتر هنا
            if (ser < 0) continue
            val cover = Regex("""(?:\\")?cover(?:\\")?:\s*(?:\\")?((?:[^"\\]|\\.)*)""")
                .find(obj)?.groupValues?.get(1)
                ?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
            out.add(EpInfo(chId, ser, cover))
        }
        return out.distinctBy { it.serial }.sortedBy { it.serial }
    }

    private fun extractPlatform(html: String): String? {
        // سطر المصدر في التفاصيل: <dt>المصدر</dt><dd><a href="/masdar/<p>">
        // «المصدر» يظهر أولاً في القوائم/الإشعارات أيضاً؛ نطابق التواجد الذي يليه
        // رابط /masdar/ خلال 500 حرف — هذا هو صف المصدر الفعلي (مهرّب أو عادي)
        return Regex("""المصدر.{0,500}?/masdar/([a-z]+)""")
            .find(html)?.groupValues?.get(1)?.lowercase()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfter("/mosalsal/").substringBefore("?")
        if (slug.isBlank()) return null
        val html = try { getWithRetry("$mainUrl/mosalsal/$slug", mainUrl, 4, 400) }
        catch (e: Exception) { return null }
        if (html.isEmpty()) return null

        // meta title h1
        val h1 = Regex("""<h1[^>]*>\s*([^<]{2,})\s*</h1>""").find(html)?.groupValues?.get(1)?.trim()
        val jsonLd = jsonLdSeries(html)
        val title = h1 ?: jsonLd?.get("name") ?: return null
        val cover = jsonLd?.get("image")
        val plot = jsonLd?.get("description")
        val episodes = parseEpisodes(html)
        // bookId قد يكون مهرّباً أو عادياً
        val bookId = Regex("""(?:\\")?bookId(?:\\")?:\s*(?:\\")?([0-9a-zA-Z]+)(?:\\")?""")
            .find(html)?.groupValues?.get(1) ?: return null
        if (episodes.isEmpty()) return null
        val platform = extractPlatform(html)?.lowercase() ?: return null
        // slug الأصلي من الرابط — أفضل من slug مشتق من العنوان (قد يختلف)
        val encSlug = java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20")

        var index = 0
        val eps = episodes.map { e ->
            index++
            // data: bookId||chapterId||serialRaw||platform||slug
            val data0 = "$bookId||${e.chapterId}||${e.serial}||$platform||$encSlug"
            newEpisode(data0) {
                episode = index
                name = "الحلقة $index"
                this.posterUrl = e.cover
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            this.posterUrl = cover
            this.plot = plot
        }
    }

    private fun cleanM3u8(url: String): String = url
        .replace("\\u0026", "&")
        .replace("\\u003c", "<").replace("\\u003e", ">")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p = data.split("||")
        if (p.size < 4) return false
        val bookId = p[0]
        val chapterId = p[1]
        val serial = p[2].toIntOrNull() ?: return false
        val platform = p[3].lowercase()

        return when (platform) {
            "goodshort" -> {
                // مصدر GoodShort: m3u8 VOD مباشر — جودة واحدة ثابتة 720p (الموقع يتجاهل &q=)
                val m3u8 = "$GOOD_BASE/$chapterId?bookId=$bookId&q=720p"
                try {
                    val master = getWithRetry(m3u8, mainUrl, 4, 400)
                    if (master.isBlank() || !master.contains("#EXTM3U")) return false
                    callback(newExtractorLink(name, "GoodShort $serial", cleanM3u8(m3u8), ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = getQualityFromName("720p")
                    })
                    true
                } catch (e: Exception) { false }
            }
            "reelshort" -> {
                // إعادة بناء صفحة الحلقة على ReelShort ثم قراءة video_url من __NEXT_DATA__
                // ReelShort serial_number يبدأ من 0 — p[2] يحمل serial الأصلي
                val slugEnc = if (p.size >= 5) p[4] else ""
                if (slugEnc.isBlank()) return false
                val epUrl = "$REEL_MAIN/ar/episodes/episode-$serial-$slugEnc-$bookId-$chapterId"
                val html = try { getWithRetry(epUrl, REEL_MAIN, 5, 400) } catch (e: Exception) { return false }
                val root = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*type="application/json"[^>]*>\s*([\s\S]*?)\s*</script>""")
                    .find(html)?.groupValues?.get(1) ?: return false
                val videoUrl = Regex(""""video_url":\s*"([^"]+)"""").find(root)?.groupValues?.get(1)?.let {
                    cleanM3u8(if (it.startsWith("http")) it else "https:$it")
                } ?: return false
                callback(newExtractorLink(name, "ReelShort $serial ($bookId)", videoUrl, ExtractorLinkType.M3U8) {
                    referer = REEL_MAIN
                    quality = getQualityFromName("720p")
                })
                true
            }
            else -> false
        }
    }
}