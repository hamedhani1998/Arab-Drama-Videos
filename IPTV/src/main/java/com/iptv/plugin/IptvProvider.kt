package com.iptv.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * IPTV — قنوات تلفزيونية مباشرة من مصادر M3U عامة.
 *
 * مزوّدان في إضافة واحدة:
 *  - [IptvOrgProvider]: iptv-org/iptv (الآلاف من القنوات العالمية، قوائم حسب الفئة/اللغة)
 *  - [FreeTvProvider]: Free-TV/IPTV (قنوات مختارة وتعمل، مجمّعة حسب الدولة)
 *
 * القوائم كبيرة (index.m3u ≈ 2.5MB) لذا تُجلب مرة وتُخزَّن بالذاكرة مؤقتًا (TTL)
 * وتُشارَك بين التبويبات والبحث. بعض القنوات تتطلب Referer/User-Agent
 * (#EXTVLCOPT:http-referrer / #EXTVLCOPT:http-user-agent) — نمرّرها في headers الرابط.
 *
 * بنية CloudStream للبث المباشر:
 *  - TvType.Live
 *  - newLiveSearchResponse(...)      بطاقة قناة
 *  - newLiveStreamLoadResponse(name, url, dataUrl){...}  شاشة القناة، dataUrl = رابط m3u8
 *  - loadLinks(data,...) حيث data = dataUrl، نُخرج الرابط عبر newExtractorLink(...M3U8)
 */
class IptvOrgProvider : MainAPI() {
    override var name = "IPTV قنوات (Org)"
    override var mainUrl = "https://iptv-org.github.io"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    // التبويبات: القائمة الكبيرة + تبويبات شائعة (كل اللغات). المفتاح = رابط القائمة.
    private val feeds = listOf(
        "https://iptv-org.github.io/iptv/index.m3u" to "🎛️ الكل",
        "https://iptv-org.github.io/iptv/categories/news.m3u" to "📰 أخبار",
        "https://iptv-org.github.io/iptv/categories/sports.m3u" to "⚽ رياضة",
        "https://iptv-org.github.io/iptv/categories/movies.m3u" to "🎬 أفلام",
        "https://iptv-org.github.io/iptv/categories/music.m3u" to "🎵 موسيقى",
        "https://iptv-org.github.io/iptv/categories/entertainment.m3u" to "🎉 ترفيه",
        "https://iptv-org.github.io/iptv/categories/documentary.m3u" to "📽️ وثائقي",
        "https://iptv-org.github.io/iptv/languages/ara.m3u" to "🕌 عربي",
    )

    override val mainPage = mainPageOf(
        *feeds.map { (feed, label) -> feed to label }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val feed = request.data
        val channels = IptvM3u.fetchCached(feed) ?: return null
        val list = channels.map { it.toLiveSearchResponse() }
        if (list.isEmpty()) return null
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        // البحث على القائمة الكاملة (الكل) — جلب مخزّن.
        val all = IptvM3u.fetchCached("https://iptv-org.github.io/iptv/index.m3u") ?: return null
        val out = mutableListOf<SearchResponse>()
        val seen = java.util.HashSet<String>()
        for (ch in all) {
            if (ch.matches(q)) {
                val r = ch.toLiveSearchResponse()
                if (seen.add(r.url)) out.add(r)
            }
            if (out.size >= 150) break
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        // url هنا رابط البث نفسه. نسترده من المخزن إن أمكن للحصول على الاسم/الشعار،
        // وإلا نستخدم الرابط مع اسم مختصر.
        val ch = IptvM3u.lookup(url)
        return newLiveStreamLoadResponse(ch?.name ?: name, url, url) {
            posterUrl = ch?.logo
            plot = ch?.group?.takeIf { it.isNotBlank() }?.let { "القناة: $it" }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) throw ErrorLoadingException("رابط القناة فارغ")
        val ch = IptvM3u.lookup(data)
        callback(
            newExtractorLink(source = name, name = ch?.name ?: name, url = data, type = ExtractorLinkType.M3U8) {
                headers = ch?.extHeaders ?: emptyMap()
                quality = getQualityFromName("Live")
            }
        )
        return true
    }

    private fun IptvChannel.toLiveSearchResponse() =
        newLiveSearchResponse(name, url) { posterUrl = logo; lang = group }
}

// ===================================================================================

class FreeTvProvider : MainAPI() {
    override var name = "IPTV قنوات (Free)"
    override var mainUrl = "https://raw.githubusercontent.com/Free-TV/IPTV/master"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val freeFeed = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    // قنوات عربية بارزة + قنوات عالمية — "جميع اللغات": نعرض الكل وكل دولة كمفتاح تصفية اختياري.
    override val mainPage = mainPageOf(
        freeFeed to "🎛️ الكل",
        "$freeFeed#News" to "📰 أخبار",
        "$freeFeed#United Arab Emirates" to "🇦🇪 الإمارات",
        "$freeFeed#Saudi Arabia" to "🇸🇦 السعودية",
        "$freeFeed#Egypt" to "🇪🇬 مصر",
        "$freeFeed#United Kingdom" to "🇬🇧 UK",
        "$freeFeed#United States" to "🇺🇸 USA",
        "$freeFeed#Italy" to "🇮🇹 إيطاليا",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val data = request.data
        val feed = data.substringBefore('#')
        val filter = data.substringAfter('#', "").takeIf { it.isNotBlank() }?.lowercase()
        val all = IptvM3u.fetchCached(feed) ?: return null
        val list = all
            .filter { ch -> filter == null || ch.group?.lowercase()?.contains(filter) == true }
            .map { it.toLiveSearchResponse() }
        if (list.isEmpty()) return null
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val all = IptvM3u.fetchCached(freeFeed) ?: return null
        return all.filter { it.matches(q) }.take(150).map { it.toLiveSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val ch = IptvM3u.lookup(url)
        return newLiveStreamLoadResponse(ch?.name ?: name, url, url) {
            posterUrl = ch?.logo
            plot = ch?.group?.takeIf { it.isNotBlank() }?.let { "القناة: $it" }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) throw ErrorLoadingException("رابط القناة فارغ")
        val ch = IptvM3u.lookup(data)
        callback(
            newExtractorLink(source = name, name = ch?.name ?: name, url = data, type = ExtractorLinkType.M3U8) {
                headers = ch?.extHeaders ?: emptyMap()
                quality = getQualityFromName("Live")
            }
        )
        return true
    }

    private fun IptvChannel.toLiveSearchResponse() =
        newLiveSearchResponse(name, url) { posterUrl = logo; lang = group }
}

// ===================================================================================
// مشاركة تحليل M3U + تخزين مؤقت (مرة واحدة لكل رابط، TTL ~10 دقائق).

private data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val httpReferrer: String?,
    val httpUserAgent: String?,
) {
    /** رؤوس البث المطلوبة (بعض القنوات لا تعمل بدون Referer). */
    val extHeaders: Map<String, String> get() = buildMap {
        httpReferrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        httpUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    }

    fun matches(q: String): Boolean =
        (name.lowercase().contains(q) || group?.lowercase()?.contains(q) == true)
}

private object IptvM3u {
    private const val TTL_MS = 10 * 60 * 1000L // 10 دقائق
    private val lock = Any()
    private val cache = HashMap<String, Pair<Long, List<IptvChannel>>>()

    suspend fun fetchCached(feed: String): List<IptvChannel>? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            cache[feed]?.let { (ts, list) -> if (now - ts < TTL_MS) return list }
        }
        val fresh = fetch(feed)
        return synchronized(lock) {
            if (fresh != null) cache[feed] = now to fresh
            // يعيد: القائمة الجديدة، أو آخر قائمة مخزّنة (ولو قديمة) كي لا تفرغ الصفحة.
            cache[feed]?.second
        }
    }

    /** يبحث عن القناة (بفرط رابطها) في كل القوائم المخزّنة — أي تبويب/مزود. */
    suspend fun lookup(url: String): IptvChannel? = synchronized(lock) {
        for (pair in cache.values)
            pair.second.firstOrNull { it.url == url }?.let { return@synchronized it }
        null
    }

    private suspend fun fetch(feed: String): List<IptvChannel>? {
        return try {
            val text = app.get(feed).text
            parseM3u(text)
        } catch (e: Exception) {
            null
        }
    }

    /** يحلل M3U إلى قنوات، مع التقاط #EXTVLCOPT (referrer/user-agent) لكل قناة. */
    private fun parseM3u(text: String): List<IptvChannel> {
        val out = mutableListOf<IptvChannel>()
        var infoLine: String? = null   // سطر #EXTINF الحالي (نقرأ منه الاسم/الشعار/المجموعة)
        var name: String? = null
        var referrer: String? = null
        var agent: String? = null

        // الاسم: بعد آخر فاصلة في سطر #EXTINF (يراعى الاسم الذي يحتوي فواصل في السمات)
        val nameRe = Regex("""#EXTINF:-?\d+\s+.*?,\s*([^"].*?)\s*$""")
        val attrVal = { key: String, line: String? ->
            line?.let { Regex("""(?:^|\s)$key="([^"]*)"""").find(it)?.groupValues?.get(1)?.takeIf { g -> g.isNotBlank() } }
        }
        val optLine = Regex("""#EXTVLCOPT:\s*([A-Za-z-]+)=?(.*)""")

        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXTINF") -> {
                    infoLine = l
                    name = nameRe.find(l)?.groupValues?.get(1)?.trim()
                    referrer = null
                    agent = null
                }
                l.startsWith("#EXTVLCOPT:") -> {
                    val m = optLine.find(l)
                    if (m != null) {
                        when (m.groupValues[1].lowercase()) {
                            "http-referrer" -> referrer = m.groupValues[2].trim()
                            "http-user-agent", "user-agent" -> agent = m.groupValues[2].trim()
                        }
                    }
                }
                l.startsWith("#") || l.startsWith("//") -> { /* توجيهات أخرى نتجاهلها */ }
                else -> {
                    // سطر رابط القناة
                    val url = l
                    if (name != null && url.startsWith("http")) {
                        out.add(
                            IptvChannel(
                                name = name,
                                url = url,
                                logo = attrVal("tvg-logo", infoLine),
                                group = attrVal("group-title", infoLine),
                                httpReferrer = referrer,
                                httpUserAgent = agent,
                            )
                        )
                    }
                    infoLine = null
                    name = null
                    referrer = null
                    agent = null
                }
            }
        }
        return out
    }
}