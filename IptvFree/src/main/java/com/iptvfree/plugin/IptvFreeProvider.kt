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
    private val HOME_CAP = 200
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
        val all = IptvFreeData.fetchCached(feed) ?: return null
        val list = all
            .filter { ch -> filter == null || ch.group?.lowercase()?.contains(filter) == true }
            .filterNot { ch -> ch.isDeadIp() }
            .take(HOME_CAP)
            .map { it.toLiveSearchResponse() }
        if (list.isEmpty()) return null
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val all = IptvFreeData.fetchCached(freeFeed) ?: return null
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
    private val cache = HashMap<String, Pair<Long, List<IptvChannel>>>()
    private val byUrl = HashMap<String, IptvChannel>()

    suspend fun fetchCached(feed: String): List<IptvChannel>? {
        val now = System.currentTimeMillis()
        cache[feed]?.let { (ts, list) -> if (now - ts < TTL_MS) return list }
        val fresh = fetch(feed)
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

    private suspend fun fetch(feed: String): List<IptvChannel>? {
        return try {
            val text = app.get(feed, timeout = 60_000L).text
            parseM3u(text)
        } catch (e: Exception) {
            null
        }
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