package com.dramamondo.plugin

import android.content.SharedPreferences
import cloudstreamshared.MediaProbe
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLDecoder
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val DM_MAIN = "https://dramamondo.com"

class DramaMondoProvider(private val prefs: SharedPreferences?) : MainAPI() {
    override var mainUrl = DM_MAIN
    override var name = "DramaMondo"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val HOME_KEY = "__home__"

    override val mainPage = mainPageOf(
        HOME_KEY to "الأحدث"
    )

    // كاش لقطة الـ dump (نحو 585KB لكل الموقع): تُبنى منها صفوف الواجهة والبحث
    // والتفاصيل من طلب واحد بدل إعادة جلبها في كل نداء.
    @Volatile private var cachedDump: Pair<Long, String>? = null
    private val DUMP_TTL_MS = 5 * 60 * 1000L

    private suspend fun fetchDump(): String {
        val now = System.currentTimeMillis()
        val hit = cachedDump
        if (hit != null && now - hit.first < DUMP_TTL_MS) return hit.second
        val text = app.get("$mainUrl/api.php?t=$now").text
        // لا نخزّن استجابة فارغة/معطّلة كي لا تفرغ الصفوف حتى انتهت (درس «الفشل يخفي كل شيء»)
        if (!text.isNullOrBlank() && text.length > 1000) cachedDump = now to text
        return text
    }

    private fun parseDump(text: String): List<JsonNode> {
        return try {
            val json = mapper.readTree(text)
            if (json.isArray) json.toList() else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // رابط التفاصيل: slug مرمّز — يفتح في المتصفح ويعود إلى load() سليمًا
    private fun detailUrl(slug: String): String =
        "$mainUrl/${URLEncoder.encode(slug, "UTF-8").replace("+", "%20")}"

    private fun cardOf(obj: JsonNode): SearchResponse? {
        val slug = obj["slug"]?.asText() ?: return null
        val title = obj["title"]?.asText()?.trim() ?: return null
        if (slug.isEmpty() || title.isEmpty()) return null
        val poster = obj["poster"]?.asText() ?: ""
        return newTvSeriesSearchResponse(title, detailUrl(slug), TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun platformVisible(slug: String): Boolean {
        val master = prefs?.getBoolean(DramaMondoSettings.KEY_SHOW_PLATFORMS, true) != false
        if (!master) return false
        return prefs?.getBoolean(DramaMondoSettings.platformKey(slug), true) != false
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = parseDump(fetchDump())
        val rows = mutableListOf<HomePageList>()

        // صف «الأحدث»: مرتّب بالأحدث تحديثًا أولًا
        val latest = items
            .sortedByDescending { it["updatedAt"]?.asText() ?: it["createdAt"]?.asText() ?: "" }
            .mapNotNull { cardOf(it) }
            .take(60)
        if (latest.isNotEmpty()) rows.add(HomePageList("الأحدث", latest))

        // صفوف المنصّات: بترتيب platforms.html، ولا صف لمنصّة بلا أعمال
        val grouped = items.filter { it["visible"]?.asBoolean() != false }
            .groupBy { it["platform"]?.asText() ?: "" }
        if (prefs?.getBoolean(DramaMondoSettings.KEY_SHOW_PLATFORMS, true) != false) {
            val limit = prefs?.getString(DramaMondoSettings.KEY_ROW_LIMIT, "all")?.trim()?.toIntOrNull()
            var platformRows = DM_PLATFORMS.mapNotNull { (slug, label) ->
                val cards = grouped[slug]?.mapNotNull { cardOf(it) } ?: return@mapNotNull null
                if (cards.isEmpty() || !platformVisible(slug)) return@mapNotNull null
                HomePageList(label, cards)
            }
            // منصّات تظهر في البيانات ولم تُدرج في platforms.html
            grouped.forEach { (slug, list) ->
                if (slug.isEmpty() || DM_PLATFORMS.any { it.first == slug }) return@forEach
                val cards = list.mapNotNull { cardOf(it) }
                if (cards.isNotEmpty() && platformVisible(slug)) {
                    platformRows = platformRows + HomePageList(slug, cards)
                }
            }
            if (limit != null && limit > 0) platformRows = platformRows.take(limit)
            rows.addAll(platformRows)
        }
        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return parseDump(fetchDump()).mapNotNull { obj ->
            val title = obj["title"]?.asText() ?: ""
            val slug = obj["slug"]?.asText() ?: ""
            if (title.lowercase().contains(q) || slug.contains(query.trim())) cardOf(obj) else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // الرابط مرمّز (URLEncoder) بينما slug في الـ API نصّ عربي خام — ففكّ الترميز
        // قبل المطابقة. مقارنة raw==raw تفشل دائمًا وكانت تُسقط load() على المسلسل
        // الأول في القائمة (يفتح تفاصيل عملٍ غير الصحيح).
        val rawSlug = url.substringAfterLast("/").substringBefore("?")
        val slug = try { URLDecoder.decode(rawSlug, "UTF-8") } catch (e: Exception) { rawSlug }
        val items = parseDump(fetchDump())
        val obj = items.firstOrNull {
            val s = it["slug"]?.asText() ?: ""
            s == slug || s == rawSlug
        } ?: return null

        val title = obj["title"]?.asText()?.trim() ?: "DramaMondo"
        val poster = obj["poster"]?.asText() ?: ""
        val synopsis = obj["synopsis"]?.asText() ?: obj["seoDescription"]?.asText() ?: ""

        // روابط سيرفرات وحدة مشاهدة واحدة (سطر لكل رابط) — بيانات الحلقة يجب أن
        // تبدأ بـ http وإلا لاحقق CloudStream mainUrl إليها.
        fun serversOf(node: JsonNode?): List<String> {
            if (node == null || !node.isArray) return emptyList()
            return node.mapNotNull { s -> s["url"]?.asText() }.filter { it.startsWith("http") }
        }

        val episodes = mutableListOf<Episode>()
        val epsList = obj["episodesList"]
        if (epsList != null && epsList.isArray && epsList.size() > 0) {
            epsList.forEachIndexed { idx, e ->
                val servers = serversOf(e["servers"])
                if (servers.isNotEmpty()) {
                    val epNum = e["number"]?.asInt() ?: (idx + 1)
                    episodes.add(newEpisode(servers.joinToString("\n")) {
                        this.name = "الحلقة $epNum"
                        this.episode = epNum
                    })
                }
            }
        }
        if (episodes.isEmpty()) {
            // كل الأجزاء: كل جزء = حلقة واحدة، وسيرفراته كلها داخلها كروابط تشغيل
            val parts = obj["parts"]
            if (parts != null && parts.isArray && parts.size() > 0) {
                parts.forEachIndexed { idx, part ->
                    val servers = serversOf(part["servers"])
                    if (servers.isNotEmpty()) {
                        val partTitle = part["title"]?.asText()?.trim() ?: ""
                        episodes.add(newEpisode(servers.joinToString("\n")) {
                            this.name = partTitle.ifEmpty { "الحلقة ${idx + 1}" }
                            this.episode = idx + 1
                        })
                    }
                }
            }
        }
        if (episodes.isEmpty()) {
            val servers = serversOf(obj["servers"])
            if (servers.isNotEmpty()) {
                episodes.add(newEpisode(servers.joinToString("\n")) {
                    this.name = "المشاهدة كاملة"
                    this.episode = 1
                })
            }
        }
        if (episodes.isEmpty()) {
            // آخر ملاذ: صفحة التفاصيل نفسها — loadLinks يعيد حلّ السيرفرات من الـ dump
            episodes.add(newEpisode(url) {
                this.name = "المشاهدة كاملة"
                this.episode = 1
            })
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = synopsis
        }
    }

    // اسم السيرفر الواضح من مضيف الرابط (لا يمكن معرفة الجودة/الحجم من الموقع)
    private fun serverNameOf(rawUrl: String): String = when {
        rawUrl.contains("rumble.com") -> "Rumble"
        rawUrl.contains("voe.") -> "Voe"
        rawUrl.contains("vidara.") -> "Vidara"
        rawUrl.contains("netshort") -> "NetShort"
        rawUrl.contains("dramabox") -> "Dramabox"
        rawUrl.contains("dramawave") -> "DramaWave"
        rawUrl.contains("reelshort") -> "ReelShort"
        rawUrl.contains("shorttv") -> "ShortTV"
        rawUrl.contains("flextv") -> "FlexTV"
        rawUrl.contains("shortmax") -> "ShortMax"
        rawUrl.contains("flickreels") -> "FlickReels"
        rawUrl.contains("moboreels") -> "MoboReels"
        rawUrl.contains("shortwave") -> "ShortWave"
        rawUrl.contains("stardust") -> "StarDust"
        rawUrl.contains("petadrama") -> "PetaDrama"
        rawUrl.contains("storyreel") -> "StoryReel"
        rawUrl.contains("kalostv") -> "KalosTV"
        rawUrl.contains("joyreels") -> "JoyReels"
        rawUrl.contains("goodshort") -> "GoodShort"
        rawUrl.contains("moreshort") -> "MoreShort"
        else -> "DramaMondo"
    }

    /** مرشّح تشغيل واحد: ما يُعرض في القائمة قبل الفحص. */
    private data class Cand(
        val kind: String,      // المفتاح الداخلي: rumble / extract / direct
        val server: String,    // الاسم المعروض
        val label: String?,    // «720p» إن عُلمت
        val bytes: Long?,      // حجم الملف إن أعلنه الخادم
        val url: String,
        val type: ExtractorLinkType,
        val referer: String?,
        val headers: Map<String, String> = emptyMap(),
        val ttfb: Long = Long.MAX_VALUE,
        val alive: Boolean = true,
        val certain: Boolean = false,
        val why: String = "",
        val quality: Int = Qualities.Unknown.value,
    ) {
        /** ترتيب العرض: الحيٌّ أولًا، ثم الأسرع استجابةً، ثم الأعلى دقّة. */
        fun rank(): Long {
            val aliveKey = if (alive && certain) 0L else if (alive) 1L else 2L
            return aliveKey * 1_000_000_000L + minOf(ttfb, 999_999_999L)
        }
    }

    /**
     * رامبل — لا مُستخرِج مدمج له (قِيس: لا صنف يطابق `rumble` في
     * `cloudstream.jar`)، فتُقرأ صفحة التضمين نفسها.
     *
     * العقدة `m.f[…]` تحمل `ua` وفيه: `hls.auto.url` قائمة الأم، و`tar` جوداتٌ
     * مفردةٌ مفتاحُها 240/360/480/720 و`meta` يحمل `size` (بالبايت) و`w`/`h`
     * (الدقّة الحقيقية). تُقرأ **بموازنة الأقواس** لا بنمطٍ كسول لأنّ القيم
     * تحمل أقواسًا داخلية، والأقواس داخل السلاسل لا تُعدّ.
     *
     * نُخرج قائمة الأم **و** الجودات المفردة: المستخدم يطلب صراحةً معرفة أنّ
     * الجودة 720 أو 480 ومع حجمها، وقائمة الأم وحدها تخفيها خلف «تلقائي».
     */
    private suspend fun resolveRumble(
        embedUrl: String,
        seen: MutableSet<String>,
        out: MutableList<Cand>
    ) {
        val html = try { app.get(embedUrl, referer = "$mainUrl/").text } catch (e: Exception) { return }
        // نبدأ من `m.f[` كي لا يطابق `"u":{` شيئًا آخر في الصفحة.
        val anchor = html.indexOf("m.f[")
        val scope = if (anchor >= 0) html.substring(anchor) else html
        val uaText = nodeText(scope, "ua") ?: nodeText(scope, "u") ?: return
        val ua = try { mapper.readTree(unescape(uaText)) } catch (e: Exception) { return }
        if (!ua.isObject) return

        val hlsNode = ua.get("hls")
        val master = hlsNode?.get("auto")?.get("url")?.asText()
            ?: hlsNode?.get("url")?.asText()

        val tar = ua.get("tar")
        var topH = 0
        if (tar != null && tar.isObject) {
            val names = tar.fieldNames()
            while (names.hasNext()) {
                val k = names.next()
                val node = tar.get(k) ?: continue
                val u = node.get("url")?.asText()?.takeIf { it.isNotBlank() } ?: continue
                val meta = node.get("meta")
                val h = meta?.get("h")?.asInt() ?: k.toIntOrNull() ?: 0
                val bytes = meta?.get("size")?.asLong()?.takeIf { it > 0 }
                topH = maxOf(topH, h)
                if (!seen.add(u)) continue
                out.add(Cand(
                    kind = "rumble",
                    server = "Rumble",
                    label = MediaProbe.qualityLabel(h),
                    bytes = bytes,
                    url = u,
                    type = ExtractorLinkType.M3U8,
                    referer = null,
                    quality = MediaProbe.qualityLabel(h)?.let { getQualityFromName(it) }
                        ?: Qualities.Unknown.value,
                ))
            }
        }
        if (!master.isNullOrBlank() && master.contains(".m3u8", true) && seen.add(master)) {
            out.add(Cand(
                kind = "rumble",
                server = "Rumble",
                label = "تلقائي",
                bytes = null,
                url = master,
                type = ExtractorLinkType.M3U8,
                referer = null,
                quality = MediaProbe.qualityLabel(topH)?.let { getQualityFromName(it) }
                    ?: Qualities.Unknown.value,
            ))
        }
    }

    /**
     * العقدة JSON المعلَّقة بـ`"<key>":` — تُقرأ **بموازنة الأقواس** لا بنمطٍ
     * كسول، فالقيم تحمل أقواسًا داخلية يبتلعها `.*`، والأقواس داخل السلاسل
     * النصّية لا تُعدّ.
     */
    private fun nodeText(scope: String, key: String): String? {
        val marker = "\"" + key + "\":"
        val i = scope.indexOf(marker + "{")
        if (i < 0) return null
        val open = scope.indexOf('{', i)
        var depth = 0
        var inStr = false
        var esc = false
        for (k in open until scope.length) {
            val c = scope[k]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return scope.substring(open, k + 1)
                    }
                }
            }
        }
        return null
    }

    /** فكّ ترميز JSON داخل حمولة رامبل: `\/` تصير `/`. */
    private fun unescape(s: String): String = s.replace("\\/", "/")

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // بيانات الحلقة = سيرفرات الجزء كلها، سطر لكل رابط. إن جاءت كصفحة تفاصيل
        // (آخر ملاذ في load()) نعيد حلّ السيرفرات من الـ dump حتى لا dead-end.
        var urls = data.split("\n").map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) return false
        if (urls.size == 1 && urls[0].startsWith("$mainUrl/")) {
            val rawSlug = urls[0].substringAfterLast("/").substringBefore("?")
            val slug = try { URLDecoder.decode(rawSlug, "UTF-8") } catch (e: Exception) { rawSlug }
            val obj = parseDump(fetchDump()).firstOrNull {
                val s = it["slug"]?.asText() ?: ""
                s == slug || s == rawSlug
            }
            if (obj != null) {
                val resolved = obj["parts"]?.takeIf { it.isArray }?.flatMap { part ->
                    part["servers"]?.takeIf { it.isArray }?.mapNotNull { s -> s["url"]?.asText() } ?: emptyList()
                }?.filter { it.startsWith("http") } ?: emptyList()
                if (resolved.isNotEmpty()) urls = resolved
            }
        }

        val seen = HashSet<String>()
        val cands = ArrayList<Cand>()

        for (u in urls) {
            try {
                if (hostIs(u, "rumble.com")) {
                    // جودات رامبل تُحلّ من الصفحة (وحجمها ودقّتها معلنة فيها)
                    resolveRumble(u, seen, cands)
                } else {
                    val produced = ArrayList<ExtractorLink>()
                    val ok = try {
                        loadExtractor(u, "$mainUrl/", { subtitleCallback(it) }) { produced.add(it) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        false
                    }
                    for (l in produced) {
                        if (!seen.add(l.url)) continue
                        cands.add(Cand(
                            kind = "extract",
                            server = serverNameOf(u),
                            label = null,
                            bytes = null,
                            url = l.url,
                            type = l.type,
                            referer = l.referer?.takeIf { it.isNotBlank() } ?: "$mainUrl/",
                            headers = l.headers,
                            quality = if (l.quality != Qualities.Unknown.value) l.quality
                            else MediaProbe.qualityLabel(qualityHeightOf(l.name))?.let { getQualityFromName(it) }
                                ?: l.quality,
                        ))
                    }
                    // المُستخرج لم يعرف المضيف: نُبقي الرابط الأصلي مرشّحًا
                    // فقط إن كان وسائط صريحة (mp4/m3u8) — صفحة تضمين كـVIDEO
                    // تُعطي اللاعب «خطأ 3001» ولا تفيد.
                    if (produced.isEmpty() && ok != true && isMedia(u)) {
                        if (seen.add(u)) cands.add(Cand(
                            kind = "direct",
                            server = serverNameOf(u),
                            label = null,
                            bytes = null,
                            url = u,
                            type = if (u.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            referer = "$mainUrl/",
                        ))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // سيرفر واحد لا يُسقط الحلقة كلها
            }
        }

        if (cands.isEmpty()) return false

        // ★ الفحص: طلبٌ واحد صغير لكل مرشّح (يبقى ‎206‎/‎200‎ والجسم بايتان)
        //   يُثبت الحيوية ويقرأ الحجم الكامل من ‎Content-Range‎، ويرتّب
        //   «الأكثر استجابة» أولًا. والفحص كلّه متوازٍ فلا يُطيل البدء.
        val probeLinks = prefs?.getBoolean(DramaMondoSettings.KEY_PROBE_LINKS, true) != false
        val probed: List<Cand> = if (!probeLinks) cands else coroutineScope {
            cands.map { c ->
                async(Dispatchers.IO) {
                    val r = MediaProbe.probe(c.url, c.referer, c.headers)
                    c.copy(
                        bytes = c.bytes ?: r.totalBytes,
                        ttfb = if (r.alive) r.ttfbMs else Long.MAX_VALUE - 1,
                        alive = r.alive,
                        certain = r.certain,
                        why = r.why,
                    )
                }
            }.awaitAll()
        }

        // رابطٌ ميّت بحكمٍ قاطع (كود حالة أو جسم «منتهٍ») يُسقَط في القائمة
        // لكنه يبقى في الاحتياط — فلا تُفقد حلقةٌ كلّ سيرفراتها ميتة.
        val shown = probed.filter { it.alive || !it.certain }
        val fallback = probed.filter { !it.alive && it.certain }
        val pool = if (shown.isEmpty()) fallback else shown
        // الترتيب: «الأكثر استجابة» افتراضيًّا — والحيٌّ يسبق الميّت، والأسرع
        // يسبق الأبطأ، ثمّ الأعلى دقّة عند تقارب زمن الاستجابة.
        val ordered = when (prefs?.getString(DramaMondoSettings.KEY_QUALITY_ORDER, "fastest")) {
            "desc" -> pool.sortedWith(compareByDescending<Cand> { it.quality }.thenBy { it.rank() })
            "asc" -> pool.sortedWith(compareBy<Cand> { it.quality }.thenBy { it.rank() })
            else -> pool.sortedBy { it.rank() }
        }

        var emitted = 0
        for (c in ordered) {
            val label = MediaProbe.label(c.server, c.label, c.bytes, c.url, c.type)
            callback(newExtractorLink(
                source = name,
                name = label,
                url = c.url,
                type = c.type
            ) {
                c.referer?.let { r -> this.referer = r }
                this.headers = c.headers
                this.quality = c.quality
            })
            emitted++
        }
        return emitted > 0
    }

    /** مضيف الرابط بأحرف صغيرة؛ سلسلة فارغة إن تعذّر تحليله. */
    private fun hostOf(u: String): String = try {
        java.net.URI(u).host?.lowercase() ?: ""
    } catch (e: Exception) { "" }

    /** مطابقة بلاحقة المضيف الكاملة: `notrumble.com` لا يُعدّ `rumble.com`. */
    private fun hostIs(u: String, domain: String): Boolean {
        val h = hostOf(u)
        return h == domain || h.endsWith(".$domain")
    }

    /** رابط وسائط صريح يمكن تمريره للاعب مباشرةً. */
    private fun isMedia(u: String): Boolean {
        val p = u.substringBefore("?").lowercase()
        return p.endsWith(".mp4") || p.endsWith(".m3u8") || p.endsWith(".mpd") || p.endsWith(".ts")
    }

    /** ارتفاع مذكور في اسم المُستخرِج («1080p») — لتعبئة الدقّة حين لا يُعلنها. */
    private fun qualityHeightOf(name: String?): Int {
        val m = Regex("(\\d{3,4})p").find(name ?: "") ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

}
