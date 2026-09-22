package com.iptvfree.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * IPTV Free — قنوات تلفزيونية مباشرة من Free-TV/IPTV
 * (قائمة مختارة ≈ 2070 قناة، مجمّعة حسب الدولة عبر group-title).
 *
 * لاحظ: بعض القنوات (مثل MBC 2 / MBC Max) رابطها IP خام (http://IP:port/play/…)
 * وهو فعليًا ميت (يتجمد / يخطئ 2004 أو 3002) — نستبعدها من الصفحة ونُظهر
 * رسالة واضحة إن فُتحت. القنوات المعروفة السليمة (MBC 1/4/5/Drama عبر edgenext)
 * تعمل بشكل طبيعي.
 */
class IptvFreeProvider : MainAPI() {
    override var name = "IPTV قنوات (Free)"
    override var mainUrl = "https://raw.githubusercontent.com/Free-TV/IPTV/master"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val freeFeed = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    private val freeFeedCdn = "https://cdn.jsdelivr.net/gh/Free-TV/IPTV@master/playlist.m3u8"
    private val SEARCH_CAP = 200

    // التبويبات: "الكل" + دول مهمة (كل منها مرشّحاً عبر group-title).
    private val tabs = listOf(
        "🎛️ الكل" to "",
        "📰 أخبار" to "News",
        "🇦🇪 الإمارات" to "United Arab Emirates",
        "🇸🇦 السعودية" to "Saudi Arabia",
        "🇪🇬 مصر" to "Egypt",
        "🇬🇧 بريطانيا" to "United Kingdom",
        "🇺🇸 أمريكا" to "United States",
        "🇮🇹 إيطاليا" to "Italy",
    )

    override val mainPage = mainPageOf(*tabs.map { it.first to "${freeFeed}#${it.second}" }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val feed = request.data.substringBefore('#')
        val filter = request.data.substringAfter('#').takeIf { it.isNotBlank() }?.lowercase()
        val all = IptvFreeData.fetchCached(
            feed,
            if (feed == freeFeed) listOf(freeFeed, freeFeedCdn) else listOf(feed)
        ) ?: return null
        val list = all
            .filter { ch -> filter == null || ch.group?.lowercase()?.contains(filter) == true }
            .filterNot { ch -> ch.isDeadIp() }
            .map { it.toLiveSearchResponse() }
        if (list.isEmpty()) return null
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val all = IptvFreeData.fetchCached(freeFeed, listOf(freeFeed, freeFeedCdn)) ?: return null
        val out = all.filter { it.matches(q) }.filterNot { it.isDeadIp() }.take(SEARCH_CAP).map { it.toLiveSearchResponse() }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val ch = IptvFreeData.lookup(url)
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
        if (data.startsWith("http://") && Regex("""^[a-z]+://\d{1,3}(\.\d{1,3}){3}:\d+/""").containsMatchIn(data))
            throw ErrorLoadingException("⚠️ القناة متوقفة مؤقتًا (الرابط ميت). جرّب قناة أخرى أو عد لاحقًا.")
        val ch = IptvFreeData.lookup(data)
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
// مشاركة تحليل M3U + تخزين مؤقت.

private data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val httpReferrer: String?,
    val httpUserAgent: String?,
) {
    val extHeaders: Map<String, String> get() = buildMap {
        httpReferrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        httpUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    }

    fun isDeadIp(): Boolean =
        url.startsWith("http://") && Regex("""^[a-z]+://\d{1,3}(\.\d{1,3}){3}:\d+/""").containsMatchIn(url)

    fun matches(q: String): Boolean =
        (name.lowercase().contains(q) || group?.lowercase()?.contains(q) == true)
}

private object IptvFreeData {
    private const val TTL_MS = 10 * 60 * 1000L
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private val cache = HashMap<String, Pair<Long, List<IptvChannel>>>()
    private val byUrl = HashMap<String, IptvChannel>()

    suspend fun fetchCached(feed: String, mirrors: List<String> = listOf(feed)): List<IptvChannel>? {
        val now = System.currentTimeMillis()
        cache[feed]?.let { (ts, list) -> if (now - ts < TTL_MS) return list }
        val fresh = fetch(feed, mirrors)
        return synchronized(byUrl) {
            if (fresh != null) {
                cache[feed] = now to fresh
                byUrl.clear()
                for (ch in fresh) if (ch.url.isNotBlank()) byUrl[ch.url] = ch
            }
            cache[feed]?.second
        }
    }

    suspend fun lookup(url: String): IptvChannel? = synchronized(byUrl) { byUrl[url] }

    /** يجلب القائمة من المصدر الأساسي، فإن فشل جرّب مرآة jsDelivr. */
    private suspend fun fetch(feed: String, mirrors: List<String>): List<IptvChannel>? {
        for (url in mirrors) {
            try {
                val text = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 90_000L).text
                val parsed = parseM3u(text)
                if (!parsed.isNullOrEmpty()) return parsed
            } catch (e: Exception) {
                // جرّب المصدر التالي
            }
        }
        return null
    }

    private fun parseM3u(text: String): List<IptvChannel> {
        val out = mutableListOf<IptvChannel>()
        var infoLine: String? = null
        var name: String? = null
        var referrer: String? = null
        var agent: String? = null

        val nameRe = Regex("""#EXTINF:-?\d+\s+.*?,\s*([^"].*?)\s*$""")
        val attrVal = { key: String, line: String? ->
            line?.let {
                Regex("""(?:^|\s)$key="([^"]*)"""").find(it)?.groupValues?.get(1)
                    ?.takeIf { g -> g.isNotBlank() }
            }
        }
        val optLine = Regex("""#EXTVLCOPT:\s*([A-Za-z-]+)=?(.*)""")

        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            when {
                l.startsWith("#EXTINF") -> {
                    infoLine = l
                    name = nameRe.find(l)?.groupValues?.get(1)?.trim()
                    referrer = null; agent = null
                }
                l.startsWith("#EXTVLCOPT:") -> {
                    val m = optLine.find(l)
                    if (m != null) when (m.groupValues[1].lowercase()) {
                        "http-referrer" -> referrer = m.groupValues[2].trim()
                        "http-user-agent", "user-agent" -> agent = m.groupValues[2].trim()
                    }
                }
                l.startsWith("#") || l.startsWith("//") -> { }
                else -> {
                    val url = l
                    if (name != null && url.startsWith("http")) {
                        out.add(
                            IptvChannel(
                                name = name, url = url,
                                logo = attrVal("tvg-logo", infoLine),
                                group = attrVal("group-title", infoLine),
                                httpReferrer = referrer, httpUserAgent = agent,
                            )
                        )
                    }
                    infoLine = null; name = null; referrer = null; agent = null
                }
            }
        }
        return out
    }
}