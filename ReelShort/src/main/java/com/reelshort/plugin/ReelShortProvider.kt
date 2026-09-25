package com.reelshort.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.content.SharedPreferences

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

class ReelShortProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "ReelShort"
    override var mainUrl = "https://www.reelshort.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "ar" to "أحدث المسلسلات",
    )

    private fun extractNextData(html: String): JsonNode? {
        val regex = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*type="application/json"[^>]*>\s*([\s\S]*?)\s*</script>""")
        val m = regex.find(html) ?: return null
        return try { mapper.readTree(m.groupValues[1]) } catch (e: Exception) { null }
    }

    // جلب مع إعادة محاولة فورية — الموقع بطيء وغير مستقر (DNS متقطع) وقد يقطع أول مرة.
    // `attempts` أكبر و`backoff` أطول للمواقع الحساسة (صفحات الحلقات والماستر) لأن فشل DNS
    // قد يستمر لثوانٍ، و Eng الموقّت الداخلي لـ CloudStream قد يقطع قبل النجاح إن كانت الإعادة أسرع.
    private suspend fun getWithRetry(url: String, referer: String?, attempts: Int = 3, backoffMs: Long = 300): String {
        var last = ""
        for (i in 0 until attempts) {
            try {
                val text = app.get(url, headers = mapOf("User-Agent" to UA), referer = referer).text
                if (text.isNotBlank()) return text
            } catch (e: Exception) { last = "" }
            // مهلة قصيرة قبل الإعادة (بدون kotlinx حتى لا نعتمد على المكتبة)
            try { Thread.sleep(backoffMs) } catch (e: Exception) {}
        }
        return last
    }

    private fun textOrNull(node: JsonNode?, vararg paths: String): String? {
        var n: JsonNode? = node ?: return null
        for (p in paths) {
            val next = n?.get(p) ?: return null
            n = if (next.isNumber) next else next
        }
        return if (n != null && n.isTextual) n.asText() else null
    }

    private fun isVttFamily(videoPic: String?): Boolean =
        videoPic != null && videoPic.contains("/vtt-m3u8/")

    private fun vttMasterUrl(videoPic: String?): String? {
        if (!isVttFamily(videoPic)) return null
        return videoPic!!.replaceAfterLast("/", "h264/h264.m3u8")
    }

    private fun extractTrailerM3u8(html: String): String? {
        val regex = Regex("""\"contentUrl\"\s*:\s*\"(https://[^\"]+\.m3u8[^\"]*)\"""")
        return regex.find(html)?.groupValues?.get(1)
    }

    private data class Book(
        val id: String,
        val title: String,
        val cover: String?,
        val lang: String?,
        val chapterCount: Int?,
    )

    private data class Episode(
        val serialNumber: Int,
        val videoPic: String?,
        val chapterId: String?,
    )

    private fun parseBooksList(node: JsonNode?): List<Book> {
        if (node == null) return emptyList()
        val list: JsonNode? = if (node.isArray) node
            else node.get("books")?.takeIf { it.isArray }
            ?: node.get("list")?.takeIf { it.isArray }
        if (list == null) return emptyList()
        val out = mutableListOf<Book>()
        for (item in list) {
            val id = textOrNull(item, "book_id") ?: continue
            val title = textOrNull(item, "book_title") ?: continue
            val cover = textOrNull(item, "book_pic")
            val lang = textOrNull(item, "lang")
            val cc = item.get("chapter_count")
            val chapterCount = if (cc != null && cc.isNumber) cc.asInt() else null
            out.add(Book(id, title, cover, lang, chapterCount))
        }
        return out
    }

    private fun parseEpisodes(node: JsonNode?): List<Episode> {
        if (node == null) return emptyList()
        val ob = node.get("online_base")
        val cl = node.get("chapter_list")
        val arr: JsonNode? = if (ob != null && ob.isArray) ob else if (cl != null && cl.isArray) cl else null
            ?: return emptyList()
        val out = mutableListOf<Episode>()
        val seen = HashSet<Int>()
        for (e in arr!!) {
            val snNode = e.get("serial_number")
            if (snNode == null || !snNode.isNumber) continue
            val sn = snNode.asInt()
            if (sn < 1) continue
            if (!seen.add(sn)) continue
            val videoPic = textOrNull(e, "video_pic")
            val chapterId = textOrNull(e, "chapter_id")
            out.add(Episode(sn, videoPic, chapterId))
        }
        return out.sortedBy { it.serialNumber }
    }

    private val langNames = mapOf(
        "ar" to "العربية", "de" to "الألمانية", "en" to "الإنجليزية",
        "es" to "الإسبانية", "fr" to "الفرنسية", "in" to "الإندونيسية",
        "it" to "الإيطالية", "ja" to "اليابانية", "pl" to "البولندية",
        "pt" to "البرتغالية", "ro" to "الرومانية", "ru" to "الروسية",
        "th" to "التايلاندية", "tr" to "التركية",
    )

    // بناء رابط المسلسل بالشكل الجديد /ar/movie/{slug}-{id} — الموقع أصبح يرفض الرابط بدون slug (301)
    private fun moviePath(title: String, id: String): String {
        val slug = java.net.URLEncoder.encode(title.trim().replace(" ", "-"), "UTF-8")
            .replace("+", "%20")
        return "/ar/movie/$slug-$id"
    }

    private fun bookToSearch(b: Book): SearchResponse =
        newTvSeriesSearchResponse(b.title, "$mainUrl${moviePath(b.title, b.id)}", TvType.TvSeries) {
            this.posterUrl = b.cover
        }

    // استخراج الكتب من HTML مباشرة
    private fun parseBooksFromHtml(html: String): List<SearchResponse> {
        val seen = HashSet<String>()
        val results = mutableListOf<SearchResponse>()
        val regex = Regex("""/ar/movie/([^"'\\]+)""")
        for (m in regex.findAll(html)) {
            val bookId = m.groupValues[1].substringBefore("?").substringBefore("#")
            if (!bookId.all { it.isLetterOrDigit() }) continue
            if (!seen.add(bookId)) continue
            val bookUrl = "$mainUrl/ar/movie/$bookId"
            results.add(newTvSeriesSearchResponse(bookId, bookUrl, TvType.TvSeries))
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = when (request.data) {
            "ar" -> "/ar"
            else -> "/${request.data}"
        }
        val html = try { getWithRetry("$mainUrl$path", mainUrl) } catch (e: Exception) { "" }

        val all = mutableListOf<SearchResponse>()

        // محاولة 1: __NEXT_DATA__ من نفس الرد المُجلب (بدون طلب ثانٍ)
        val root = extractNextData(html)
        if (root != null) {
            val fallback = root.get("props")?.get("pageProps")?.get("fallback")
            if (fallback != null) {
                // البحث في كل المفاتيح
                for (key in fallback.fieldNames()) {
                    val valNode = fallback.get(key)
                    val books = parseBooksList(valNode)
                    if (books.isNotEmpty()) {
                        val seen = HashSet<String>()
                        for (b in books) {
                            if (seen.add(b.id)) all.add(bookToSearch(b))
                        }
                        if (all.isNotEmpty()) break
                    }
                    // بحث في bookShelfList
                    val shelves = valNode?.get("bookShelfList")
                    if (shelves != null && shelves.isArray) {
                        val seen = HashSet<String>()
                        for (shelf in shelves) {
                            for (b in parseBooksList(shelf)) {
                                if (seen.add(b.id)) all.add(bookToSearch(b))
                            }
                        }
                        if (all.isNotEmpty()) break
                    }
                }
            }
        }

        // محاولة 2: HTML
        if (all.isEmpty()) {
            val seen = HashSet<String>()
            for (item in parseBooksFromHtml(html)) {
                if (seen.add(item.url)) all.add(item)
            }
        }

        return if (all.isEmpty()) null else newHomePageResponse(request.name, all)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val searchPath = "/ar/search?keywords=$q&type=movies"
        val html = try { getWithRetry("$mainUrl$searchPath", mainUrl) } catch (e: Exception) { "" }

        // محاولة 1: __NEXT_DATA__ من نفس الرد المُجلب (بدون طلب ثانٍ)
        val root = extractNextData(html)
        if (root != null) {
            val data = root.get("props")?.get("pageProps")
            if (data != null) {
                val books = parseBooksList(data.get("books"))
                if (books.isNotEmpty()) {
                    val searchLower = query.trim().lowercase()
                    return books.filter { it.title.lowercase().contains(searchLower) }.map { bookToSearch(it) }
                }
            }
        }

        // محاولة 2: HTML
        val htmlBooks = parseBooksFromHtml(html)
        if (htmlBooks.isNotEmpty()) {
            val searchLower = query.trim().lowercase()
            return htmlBooks.filter { it.name.lowercase().contains(searchLower) }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // المسار الكامل بعد /ar/movie/ - قد يكون مجرد id أو {slug}-{id}
        val rawPath = url.substringAfter("/ar/movie/").substringBefore("?").substringBefore("#")
        val pathSegs = rawPath.split("-")
        val bookId = pathSegs.lastOrNull()?.takeIf { it.all(Char::isLetterOrDigit) && it.length >= 8 }
            ?: when {
                url.contains("/ar/episodes/") -> {
                    val path = url.substringAfter("/ar/episodes/").substringBefore("?").substringBefore("#")
                    val parts = path.split("-")
                    if (parts.size >= 4) parts[3] else return null
                }
                else -> url.substringAfterLast("/").substringBefore("?")
            }
        // استخدم المسار الموجود في الرابط مباشرة (يتضمن slug — الموقع يرفض الرابط بدون slug)
        val moviePath = if (rawPath.isNotBlank()) rawPath else bookId
        val res = getWithRetry("$mainUrl/ar/movie/$moviePath", mainUrl, 5, 400)
        val root = extractNextData(res) ?: return null
        val data = root.get("props")?.get("pageProps")?.get("data") ?: return null
        val title = textOrNull(data, "book_title") ?: return null
        val cover = textOrNull(data, "book_pic")
        val desc = textOrNull(data, "special_desc") ?: textOrNull(data, "book_sub_title")
        val lang = textOrNull(data, "lang")
        val originalLang = textOrNull(data, "original_lang")
        val isDub = (data.get("is_dub")?.let { if (it.isNumber) it.asInt() == 1 else false }) == true
        val episodes = parseEpisodes(data)

        val plot = buildString {
            if (!desc.isNullOrBlank()) append(desc)
            if (!lang.isNullOrBlank()) {
                if (isNotEmpty()) append(" • ")
                append("اللغة: ").append(langNames[lang] ?: lang)
            }
            if (!originalLang.isNullOrBlank() && originalLang != lang) {
                if (isNotEmpty()) append(" • ")
                append("الأصلية: ").append(langNames[originalLang] ?: originalLang)
            }
            if (isDub) {
                if (isNotEmpty()) append(" • ")
                append("مدبلج")
            }
        }.ifBlank { null }

        // حالة 1: توجد حلقات VTT قابلة للتشغيل
        val vttEpisodes = episodes.filter { isVttFamily(it.videoPic) }
        if (vttEpisodes.isNotEmpty()) {
            val eps = vttEpisodes.map { e ->
                val data0 = "0||${e.videoPic ?: ""}||${e.serialNumber}||"
                newEpisode(data0) {
                    episode = e.serialNumber
                    name = "الحلقة ${e.serialNumber}"
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = cover
                this.plot = plot
            }
        }

        // حالة 2: حلقات بدون VTT - لكل حلقة صفحة خاصة بها تحمل رابط فيديو الحلقة الفعلية
        if (episodes.isNotEmpty()) {
            // رابط الحلقة: /ar/episodes/episode-{serial}-{slug}-{bookId}-{chapterId}
            // مهم: نبني العنوان الرابط مشفّرًا (URL-encoded) لأن العناوين العربية الخام في HTML
            // قد ترفضها بعض عملاء HTTP داخل CloudStream (الرابط المشفّر يعمل دائمًا)
            val encodedSlug = java.net.URLEncoder.encode(title.trim().replace(" ", "-"), "UTF-8")
                .replace("+", "%20")
            val trailer = extractTrailerM3u8(res)
            val eps = episodes.map { e ->
                val cid = e.chapterId
                val epUrl = if (!cid.isNullOrBlank() && cid.all { it.isLetterOrDigit() })
                    "$mainUrl/ar/episodes/episode-${e.serialNumber}-$encodedSlug-$bookId-$cid"
                else ""
                // data: 1||episodePageUrl||serialNumber||trailer — للعروض القديمة نجلب فيديو الحلقة من صفحتها
                val data0 = "1||$epUrl||${e.serialNumber}||${trailer ?: ""}"
                newEpisode(data0) {
                    episode = e.serialNumber
                    name = "الحلقة ${e.serialNumber}"
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = cover
                this.plot = plot
            }
        }

        return null
    }

    // ★ بثّ روابط الحلقة بعد اكتمالها: «افتراضي» = نفس الترتيب تماماً، و«تصاعدي/
    // تنازلي» يعيدان ترتيبها فقط (فرز مستقر: المتساوية تحتفظ بترتيبها، ولا حذف
    // ولا تكرار ولا تغيير في المحتوى).
    private fun emitSorted(
        prefs: SharedPreferences?,
        collected: List<ExtractorLink>,
        callback: (ExtractorLink) -> Unit
    ) {
        val order = prefs?.getString(ReelShortSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "family||[...]||serialNumber||[trailer]"
        //   VTT family:  0||videoPic||serialNumber||          → master 3 جودات + ترجمات
        //   old-VOD:     1||episodePageUrl||serialNumber||trailer → فيديو الحلقة من صفحتها، وإلا المقدمة
        val parts = data.split("||")
        if (parts.size < 3) return false
        val family = parts[0]
        val payload = parts[1]
        val serialNumber = parts[2]
        val trailer = if (parts.size >= 4) parts[3] else ""

        // نجمع روابط الحلقة أولاً ثم نبثّها دفعة واحدة (بترتيب اختيار المستخدم).
        // الافتراضي = نفس الترتيب تماماً، بلا حذف ولا تكرار.
        val collected = mutableListOf<ExtractorLink>()

        // العروض القديمة: كل حلقة لها صفحة تحمل فيديو الحلقة الفعلي (video_url)
        if (family == "1") {
            if (payload.isNotBlank()) {
                // صفحة الحلقة حسّاسة لفشل DNS → إعادة محاولة أكثر (5) وبمهلة أطول (400ms)
                try {
                    val html = getWithRetry(payload, mainUrl, 5, 400)
                    val root = extractNextData(html)
                    val d = root?.get("props")?.get("pageProps")?.get("data")
                    val videoUrl = d?.get("video_url")?.takeIf { it.isTextual && it.asText().startsWith("http") }?.asText()
                    if (!videoUrl.isNullOrBlank()) {
                        collected.add(
                            newExtractorLink(name, "ReelShort $serialNumber", videoUrl, ExtractorLinkType.M3U8) {
                                referer = mainUrl
                                quality = getQualityFromName("1080p")
                            }
                        )
                        emitSorted(prefs, collected, callback)
                        return true
                    }
                } catch (e: Exception) {}
            }
            // احتياطي: عند فشل صفحة الحلقة نعرض المقدمة إن وجدت
            // إظهار رابط المقدمة — الافتراضي true = سلوك اليوم حرفياً؛ إطفاؤه يتخطى
            // هذا الموقع فقط (فلا رابط إطلاقاً بدل عرض مقطع تشويقي)، ولا يمسّ
            // روابط الحلقات الأخرى.
            if (prefs?.getBoolean(ReelShortSettingsBottomSheet.KEY_SHOW_TRAILER, true) != false
                && trailer.isNotBlank()) {
                collected.add(
                    newExtractorLink(name, "ReelShort $serialNumber (مقدمة)", trailer, ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = getQualityFromName("1080p")
                    }
                )
                emitSorted(prefs, collected, callback)
                return true
            }
            return false
        }

        if (family == "0" && payload.isNotBlank()) {
            val master = vttMasterUrl(payload) ?: return false
            return try {
                // الماستر على CDN متقطعة DNS → إعادة محاولة أكثر
                val masterText = getWithRetry(master, mainUrl, 5, 400)
                // الماستر يوفر 3 جودات (540p/720p/1080p) + ترجمات متعددة — نعرض كل الجودات
                var found = false
                val streamRe = Regex("""RESOLUTION=(?:(\d+)x(\d+))[^,]*""")
                var streamIdx = 0
                val variantLines = masterText.split("\n")
                val depth = "\t".repeat(1)
                for ((i, line) in variantLines.withIndex()) {
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        streamIdx++
                        val next = variantLines.getOrNull(i + 1)?.trim() ?: continue
                        val resMatch = streamRe.find(line)
                        val res = resMatch?.groupValues?.get(2) ?: continue
                        val qLabel = when (res) {
                            "540" -> "540p"
                            "720" -> "720p"
                            else -> "1080p"
                        }
                        val uri = if (next.startsWith("http")) next else master.substringBeforeLast("/") + "/" + next
                        collected.add(newExtractorLink(name, "ReelShort $serialNumber ($qLabel)", uri, ExtractorLinkType.M3U8) {
                            referer = mainUrl
                            quality = getQualityFromName(qLabel)
                        })
                        found = true
                    }
                }
                if (found) {
                    // الترجمات من نفس الماستر
                    val subRegex = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES[^>]*NAME="([^"]+)"[^>]*URI="([^"]+)"""")
                    for (m in subRegex.findAll(masterText)) {
                        val lang = m.groupValues[1]
                        val uri = m.groupValues[2]
                        val subUrl = if (uri.startsWith("http")) uri else master.substringBeforeLast("/") + "/" + uri
                        val cleanLang = when (lang) {
                            "Arabic", "MSA" -> "ar"
                            else -> lang.lowercase().take(2)
                        }
                        try { subtitleCallback(newSubtitleFile(cleanLang, subUrl)) } catch (_: Exception) {}
                    }
                    emitSorted(prefs, collected, callback)
                    return true
                }
                // احتياطي: إرجاع الماستر نفسه إن لم تُحلل الجودات
                collected.add(newExtractorLink(name, "ReelShort $serialNumber", master, ExtractorLinkType.M3U8) {
                    referer = mainUrl
                    quality = getQualityFromName("1080p")
                })
                emitSorted(prefs, collected, callback)
                true
            } catch (e: Exception) { false }
        }
        return false
    }
}
