package com.mosalsaly.plugin

import android.util.Log
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val mosMapper = ObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val TAG = "Mosalsaly"

private const val MOS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val REEL_MAIN = "https://www.reelshort.com"

private const val GOOD_BASE = "https://goodshort.goodbos.online/hls"

// مفتاح AES-GCM الثابت للموقع لتفكيك مصادر الحلقات من /api/episode-source
// خوارزمية: base64decode(enc) → iv = أول 12 بايت، ciphertext = الباقي، ثم AES/GCM/NoPadding
private const val MOS_EP_KEY_B64 = "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc="
private val MOS_EP_KEY: ByteArray by lazy {
    try { Base64.getDecoder().decode(MOS_EP_KEY_B64) } catch (e: Exception) { MOS_EP_KEY_B64.toByteArray() }
}

// فك تشفير حقل enc من واصف حلقة mosalsaly (AES-GCM، static key كما في JS chunk 2f0jsiav2q67u)
private fun decryptMosEnc(enc: String): String? {
    return try {
        val raw = Base64.getDecoder().decode(enc)
        if (raw.size <= 12) return null
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(MOS_EP_KEY, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "decryptMosEnc fail ${e.message}")
        null
    }
}

// تنظيف رابط تم فك تشفيره من واصف (بعض الحقول تأتي بخلفية/مهربات)
private fun cleanDecryptedUrl(u: String?): String? {
    if (u.isNullOrBlank()) return null
    return u.trim()
        .replace("\\u0026", "&").replace("\\u003c", "<").replace("\\u003e", ">")
        .replace("\\/", "/")
        .takeIf { it.startsWith("http") || it.startsWith("https") }
}

// أسماء المنصات الثمانية عشر (كما في /sources) — تُستخدم أسماء الأقسام في الرئيسية
// الترتيب: أولاً المنصات المدعومة تشغيلاً (GoodShort, ReelShort)، ثم الباقية (غير مدعومة للعب)
private val PLATFORMS = listOf(
    "goodshort" to "GoodShort",
    "reelshort" to "Reelshort",
    "dotdrama" to "DotDrama",
    "dramabite" to "DramaBite",
    "dramabox" to "DramaBox",
    "flickreels" to "FlickReels",
    "happyshort" to "HappyShort",
    "joyreels" to "JoyReels",
    "kalostv" to "KalosTV",
    "moboreels" to "MoboReels",
    "moreshort" to "MoreShort",
    "mydramawave" to "MyDramaWave",
    "netshort" to "NetShort",
    "petadrama" to "PetaDrama",
    "shorttv" to "ShortTV",
    "shortwave" to "ShortWave",
    "stardust" to "Stardust",
    "storyreel" to "StoryReel",
)

// أقسام إضافية من /tasnif/ (التصنيفات) تُعرض بعد المنصات في الواجهة الرئيسية
private val EXTRA_SECTIONS = listOf(
    "populer" to "⭐ الأكثر شعبية",
    "newly-added" to "🆕 أحدث الإضافات",
    "power-comeback" to "⚡ القوة والعودة",
    "revenge" to "🔥 الانتقام",
    "romantik" to "❤️ الرومانسية",
    "guclu-kadin" to "💪 امرأة قوية",
    "rich-ceo" to "💼 رجل أعمال غني",
    "modern-ask-evlilik" to "💍 حب وزواج حديث",
    "fantasy" to "🧙 فانتازيا",
    "gizli-kimlik" to "🎭 هوية خفية",
    "dusmandan-aska" to "💘 من عداوة إلى حب",
    "yukselis-geri-donus" to "🚀 الصعود والعودة",
    "tarihi-antik" to "🕌 تاريخي",
    "zaman-yolculugu" to "⏳ السفر عبر الزمن",
    "second-chance" to "🔁 فرصة ثانية",
    "ask-ucgeni" to "🔺 مثلث الحب",
    "aile-dramasi" to "👨‍👩‍👦 دراما عائلية",
)

class MosalsalyProvider : MainAPI() {
    override var name = "Mosalsaly"
    override var mainUrl = "https://mosalsaly.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // الأقسام الرئيسية: المنصات أولاً ثم الأقسام/التصنيفات الإضافية
    private val homeSections: List<Pair<String, String>> =
        PLATFORMS + EXTRA_SECTIONS

    override val mainPage = mainPageOf(*homeSections.toTypedArray())

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
        val slug = request.data
        // أقسام التصنيفات تأتي من /tasnif/ والمنصات من /masdar/
        val isSection = EXTRA_SECTIONS.any { it.first == slug }
        val base = if (isSection) "$mainUrl/tasnif/$slug" else "$mainUrl/masdar/$slug"
        val url = if (page <= 1) base else "$base/page/$page"
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
        Log.i(TAG, "load ok slug=$slug bookId=$bookId platform=$platform eps=${episodes.size}")
        // slug الأصلي من الرابط — أفضل من slug مشتق من العنوان (قد يختلف)
        val encSlug = java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20")

        var index = 0
        val eps = episodes.map { e ->
            index++
            // data: bookId||chapterId||serialRaw||platform||slug
            // البادئة النصية في أول حقل تمنع CloudStream من دمج mainUrl أمام رقم
            val data0 = "id:$bookId||${e.chapterId}||${e.serial}||$platform||$encSlug"
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

    // جلب واصف الحلقة من API الموقع: /api/episode-source/{bookId}/{serial}?lang=ar&refresh=1
    // يوفّر مصادر مباشرة لكل المنصات (الرابط بعد فك التشفير جاهز للتشغيل)
    private suspend fun fetchEpisodeDescriptor(bookId: String, serial: Int): ObjectNode? {
        val url = "$mainUrl/api/episode-source/$bookId/$serial?lang=ar&refresh=1"
        return try {
            val text = getWithRetry(url, mainUrl, 3, 400)
            if (text.isBlank()) { Log.w(TAG, "no descriptor text serial=$serial"); return null }
            val node = mosMapper.readTree(text)
            node.get("descriptor") as? ObjectNode
        } catch (e: Exception) {
            Log.w(TAG, "descriptor except ${e.message}")
            null
        }
    }

    private suspend fun emitDescriptorLinks(
        descriptor: ObjectNode,
        platform: String,
        serial: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val chain = descriptor.get("chain") as? ArrayNode ?: return false
        var emitted = false
        val seen = HashSet<String>()

        for (item in chain) {
            val ch = item as? ObjectNode ?: continue
            val enc = ch.get("enc")?.asText() ?: continue
            val url = cleanDecryptedUrl(decryptMosEnc(enc)) ?: continue
            if (!seen.add(url)) continue
            val type = ch.get("type")?.asText()?.lowercase()
            val isHls = url.contains(".m3u8") || type?.contains("hls") == true
            val isVideo = url.contains(".mp4") || type == "mp4" || type == "stardust" || (!url.contains(".m3u8") && type == "video")
            val linkType = if (isVideo) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            val label = "$platform $serial"
            callback(newExtractorLink(name, label, url, linkType) {
                this.headers = mapOf("User-Agent" to MOS_UA, "Referer" to mainUrl)
                if (isVideo) this.quality = getQualityFromName("720p")
            })
            emitted = true
        }

        // ترجمة (اختياري) — فك نفس المفتاح وأرسله كملف ترجمة
        val sub = descriptor.get("subtitle") as? ObjectNode
        val subEnc = sub?.get("enc")?.asText()
        if (!subEnc.isNullOrBlank()) {
            val subUrl = cleanDecryptedUrl(decryptMosEnc(subEnc))
            if (!subUrl.isNullOrBlank()) {
                try {
                    val lang = sub.get("language")?.asText()?.takeIf { it.isNotBlank() } ?: "ar"
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                } catch (e: Exception) { Log.w(TAG, "sub emit fail ${e.message}") }
            }
        }
        return emitted
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p = data.split("||")
        if (p.size < 4) return false
        // أول حقل يحمل بادئة نصية "id:" وربما يصل مدمجاً مع mainUrl — ننظّفه دائماً
        var bookId = p[0].substringAfterLast("/").removePrefix("id:")
        val chapterId = p[1]
        val serial = p[2].toIntOrNull() ?: return false
        val platform = p[3].lowercase()
        Log.i(TAG, "loadLinks platform=$platform bookId=$bookId ch=$chapterId serial=$serial raw=$data")

        // المسار الموحّد عبر /api/episode-source — يخدم كل المنصات الـ 18
        // يعيد واصفاً مشفّراً يُفك بمفتاح AES-GCM الثابت إلى رابط مباشر جاهز
        val descriptor = fetchEpisodeDescriptor(bookId, serial)
        if (descriptor != null) {
            val emitted = emitDescriptorLinks(descriptor, platform, serial, subtitleCallback, callback)
            if (emitted) {
                Log.i(TAG, "episode-source OK platform=$platform serial=$serial")
                return true
            }
            Log.w(TAG, "descriptor present but no links platform=$platform serial=$serial")
        }

        // احتياط: مسار المنصة المحدّدة مباشرة إذا فشل الواصف
        return when (platform) {
            "goodshort" -> {
                // مصدر GoodShort: m3u8 VOD مباشر — جودة واحدة ثابتة 720p (الموقع يتجاهل &q=)
                val m3u8 = "$GOOD_BASE/$chapterId?bookId=$bookId&q=720p"
                try {
                    val master = getWithRetry(m3u8, mainUrl, 4, 400)
                    if (master.isBlank() || !master.contains("#EXTM3U")) {
                        Log.w(TAG, "goodshort no master bookId=$bookId ch=$chapterId len=${master.length}")
                        return false
                    }
                    callback(newExtractorLink(name, "GoodShort $serial", cleanM3u8(m3u8), ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = getQualityFromName("720p")
                    })
                    Log.i(TAG, "goodshort OK serial=$serial")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "goodshort except ${e.message}")
                    false
                }
            }
            "reelshort" -> {
                // إعادة بناء صفحة الحلقة على ReelShort ثم قراءة video_url من __NEXT_DATA__
                // ReelShort serial_number يبدأ من 0 — p[2] يحمل serial الأصلي
                val slugEnc = if (p.size >= 5) p[4] else ""
                if (slugEnc.isBlank()) {
                    Log.w(TAG, "reelshort blank slug")
                    return false
                }
                val epUrl = "$REEL_MAIN/ar/episodes/episode-$serial-$slugEnc-$bookId-$chapterId"
                val html = try { getWithRetry(epUrl, REEL_MAIN, 5, 400) } catch (e: Exception) {
                    Log.w(TAG, "reelshort fetch except ${e.message}")
                    return false
                }
                val root = Regex("""<script[^>]*id="__NEXT_DATA__"[^>]*type="application/json"[^>]*>\s*([\s\S]*?)\s*</script>""")
                    .find(html)?.groupValues?.get(1)
                if (root == null) {
                    Log.w(TAG, "reelshort no NEXT_DATA len=${html.length} url=$epUrl")
                    return false
                }
                val videoUrl = Regex(""""video_url":\s*"([^"]+)"""").find(root)?.groupValues?.get(1)?.let {
                    cleanM3u8(if (it.startsWith("http")) it else "https:$it")
                }
                if (videoUrl == null) {
                    Log.w(TAG, "reelshort no video_url root=${root.length}")
                    return false
                }
                callback(newExtractorLink(name, "ReelShort $serial ($bookId)", videoUrl, ExtractorLinkType.M3U8) {
                    referer = REEL_MAIN
                    quality = getQualityFromName("720p")
                })
                Log.i(TAG, "reelshort OK serial=$serial")
                true
            }
            else -> {
                Log.w(TAG, "platform $platform descriptor failed (no links)")
                false
            }
        }
    }
}