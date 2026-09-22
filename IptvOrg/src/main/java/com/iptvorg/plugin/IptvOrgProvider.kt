package com.iptvorg.plugin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * IPTV Org — قنوات تلفزيونية مباشرة من iptv-org/iptv (آلاف القنوات العالمية).
 *
 * القائمة الكاملة (index.m3u ≈ 2.5MB) كبيرة؛ لذلك:
 *  - نجلب القائمة مرة ونخزّنها بالذاكرة مؤقتًا (TTL)، ونشاركها بين التبويبات والبحث.
 *  - في الصفحة الرئيسية نقتطع عددًا معقولًا (cap) حتى لا ينشغل التطبيق والواجهة تفتح بسرعة.
 *  - القنوات التي رابطها IP خام (http://IP:port/play/…) تكون ميتة فعليًا (2004/3002)
 *    فنستبعدها تلقائيًا، ونمرّر Referer/User-Agent الموجود في #EXTVLCOPT حيث لزم.
 *
 * بنية CloudStream للبث المباشر:
 *  - TvType.Live
 *  - newLiveSearchResponse(name, url) {...}          بطاقة قناة
 *  - newLiveStreamLoadResponse(name, url, dataUrl){} شاشة القناة
 *  - loadLinks(data, ...) نُخرج الرابط (M3U8) مع الرؤوس المطلوبة
 */
class IptvOrgProvider : MainAPI() {
    override var name = "IPTV قنوات (Org)"
    override var mainUrl = "https://iptv-org.github.io"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val feeds = listOf(
        "https://iptv-org.github.io/iptv/index.m3u" to "🎛️ الكل",
        "https://iptv-org.github.io/iptv/languages/ara.m3u" to "🕌 عربي",
        "https://iptv-org.github.io/iptv/categories/news.m3u" to "📰 أخبار",
        "https://iptv-org.github.io/iptv/categories/sports.m3u" to "⚽ رياضة",
        "https://iptv-org.github.io/iptv/categories/movies.m3u" to "🎬 أفلام",
        "https://iptv-org.github.io/iptv/categories/music.m3u" to "🎵 موسيقى",
        "https://iptv-org.github.io/iptv/categories/entertainment.m3u" to "🎉 ترفيه",
        "https://iptv-org.github.io/iptv/categories/documentary.m3u" to "📽️ وثائقي",
    )

    // مرايا بديلة (jsDelivr) لكل مصدر، عند تعذّر الوصول المباشر.
    private fun mirrorsOf(feed: String) = listOf(
        feed,
        feed.replaceFirst("https://iptv-org.github.io/iptv", "https://cdn.jsdelivr.net/gh/iptv-org/iptv@gh-pages")
    )

    // عدد نتائج البحث الذي نعرضه (حتى لا تُثقل الواجهة).
    private val SEARCH_CAP = 150

    override val mainPage = mainPageOf(*feeds.map { it.second to it.first }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        // mainPage data = الرابط (لأن mainPageOf بنقط المفتاح=الاسم والقيمة=الرابط)
        val feed = request.data
        val channels = IptvOrgData.fetchCached(feed, mirrorsOf(feed)) ?: return null
        val list = channels.map { it.toLiveSearchResponse() }
        if (list.isEmpty()) return null
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val all = IptvOrgData.fetchCached(feeds.first().first, mirrorsOf(feeds.first().first)) ?: return null
        val seen = java.util.HashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (ch in all) {
            if (!ch.matches(q)) continue
            val r = ch.toLiveSearchResponse()
            if (seen.add(r.url)) out.add(r)
            if (out.size >= SEARCH_CAP) break
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val ch = IptvOrgData.lookup(url)
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
        val ch = IptvOrgData.lookup(data)
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
// مشاركة تحليل M3U + تخزين مؤقت (مرة واحدة لكل رابط، TTL ~10 دقائق) + فلترة القنوات الميتة.

/** قناة واحدة من قائمة M3U مع الرؤوس المطلوبة. */
private data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String?,
    val httpReferrer: String?,
    val httpUserAgent: String?,
) {
    /** رؤوس البث المطلوبة (#EXTVLCOPT) — بعض القنوات لا تعمل بدون Referer. */
    val extHeaders: Map<String, String> get() = buildMap {
        httpReferrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        httpUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    }

    /** رابط IP خام (http://IP:port/play/…) — شبه ميت دائمًا (يتجمد / يخطئ 2004). */
    fun isDeadIp(): Boolean =
        url.startsWith("http://") && Regex("""^[a-z]+://\d{1,3}(\.\d{1,3}){3}:\d+/""").containsMatchIn(url)

    fun matches(q: String): Boolean =
        (name.lowercase().contains(q) || group?.lowercase()?.contains(q) == true)
}

private object IptvOrgData {
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

    /** يجلب القائمة من المصدر الأساسي، فإن فشل جرّب مرايا jsDelivr. */
    private suspend fun fetch(feed: String, mirrors: List<String>): List<IptvChannel>? {
        for (url in mirrors) {
            try {
                val text = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 90_000L).text
                val parsed = parseM3u(text)
                val filtered = parsed.filterNot { it.isDeadIp() }
                if (filtered.isNotEmpty()) return filtered
            } catch (e: Exception) {
                // جرّب المصدر التالي
            }
        }
        return null
    }

    /** يحلل M3U إلى قنوات، مع التقاط #EXTVLCOPT (referrer/user-agent) لكل قناة. */
    private fun parseM3u(text: String): List<IptvChannel> {
        val out = mutableListOf<IptvChannel>()
        var infoLine: String? = null   // سطر #EXTINF الحالي (الاسم/الشعار/المجموعة)
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
                    referrer = null
                    agent = null
                }
                l.startsWith("#EXTVLCOPT:") -> {
                    val m = optLine.find(l)
                    if (m != null) when (m.groupValues[1].lowercase()) {
                        "http-referrer" -> referrer = m.groupValues[2].trim()
                        "http-user-agent", "user-agent" -> agent = m.groupValues[2].trim()
                    }
                }
                l.startsWith("#") || l.startsWith("//") -> { /* توجيهات أخرى نتجاهلها */ }
                else -> {
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
                    infoLine = null; name = null; referrer = null; agent = null
                }
            }
        }
        return out
    }
}