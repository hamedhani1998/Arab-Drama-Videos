package com.kisskhlive.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.MediaProbe
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * KissKH — نسخة `kisskh.la` (Angular SPA + واجهة `DramaList` الكلاسيكية).
 *
 * لماذا وحدةٌ مستقلّة عن `KissKH` (‏kisskh.bz): الموقعان **ليسا مرايا**؛ هذا
 * يعمل بـAPI مختلف تمامًا:
 *  - الفهرس: `/api/DramaList/List` و`/Search`، والتفاصيل: `/Drama/{id}`.
 *  - التشغيل: `/api/DramaList/Episode/{id}.png?…&kkey=…` ويحتاج مفتاح
 *    [Kkey] المحسوب محليًّا (انظر توثيقه) — بدونه 403.
 *  - الترجمة: `/api/Sub/{id}?kkey=…` بمفتاحٍ مختلف (`subGuid`).
 *
 * كل ما يعيده الموقع يُعرض: كل سيرفر (`Video` و`Video_tmp` و`ThirdParty`)
 * وكل جودة داخل الـmaster إن وُجدت، وكل ملف ترجمة بلا استثناء.
 */
class KissKHLiveProvider(private val prefs: SharedPreferences? = null) : MainAPI() {

    override var mainUrl = "https://kisskh.la"
    override var name = "KissKH.la"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val TAG = "KissKHLive"

    // -------------------------------------------------------------- الإعدادات

    private fun prefStr(key: String, def: String): String =
        prefs?.getString(key, def) ?: def

    private fun prefBool(key: String, def: Boolean): Boolean =
        prefs?.getBoolean(key, def) ?: def

    private fun showSubs(): Boolean =
        prefBool(KissKHLiveSettings.KEY_SHOW_SUBS, true)

    private fun showAllQualities(): Boolean =
        prefStr(KissKHLiveSettings.KEY_QUALITY, "all") != "best"

    /** هل تُعرض ترجمةٌ بهذه اللغة وفق اختيار المستخدم؟ */
    private fun langOk(label: String?, url: String): Boolean =
        when (prefStr(KissKHLiveSettings.KEY_LANG, "all")) {
            "ar" -> isArabic(label, url)
            "en" -> isEnglish(label, url)
            "ar_en" -> isArabic(label, url) || isEnglish(label, url)
            else -> true
        }

    private fun isArabic(label: String?, url: String): Boolean =
        label?.contains("arab", true) == true || url.contains(".ar.", true)

    private fun isEnglish(label: String?, url: String): Boolean =
        label?.contains("english", true) == true || url.contains(".en.", true)
    private val API = "/api/DramaList"

    private val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val head get() = mapOf(
        "User-Agent" to UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$mainUrl/",
    )

    /**
     * أقسام الرئيسية كما في الموقع: الأحدث، جديد وشائع، مسلسلات، أفلام، أنمي،
     * ثم حالات العرض (مستمرة/مكتملة). القيم هنا هي معاملات واجهة
     * `/api/DramaList/List` نفسها، و[getMainPage] يدمجها مع الافتراضيات.
     */
    override val mainPage = mainPageOf(
        "order=1" to "الأحدث",
        "order=2" to "جديد وشائع",
        "type=1&order=1" to "مسلسلات",
        "type=2&order=1" to "أفلام",
        "type=3&order=1" to "أنمي",
        "status=1&order=1" to "مستمرة الآن",
        "status=2&order=1" to "مكتملة",
    )

    // -------------------------------------------------------------- الفهرس

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // نبدأ من الافتراضيات ثم نُطبّق معاملات القسم فوقها، لأنّ بعض الأقسام
        // تغيّر status أو type (مكتملة/مستمرة/أنمي) وكانت الافتراضيات تُلغيها.
        val q = LinkedHashMap<String, String>()
        q["page"] = page.toString()
        q["pageSize"] = "24"
        q["sub"] = "0"
        q["country"] = "0"
        q["type"] = "0"
        q["status"] = "0"
        q["order"] = "1"
        request.data.split("&").forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) q[part.substring(0, eq)] = part.substring(eq + 1)
        }
        val query = q.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$mainUrl$API/List?$query"
        val items = try {
            val txt = app.get(url, headers = head).text
            val arr = JSONObject(txt).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { cardToSearch(arr.optJSONObject(it)) }
        } catch (e: Exception) {
            Log.i(TAG, "getMainPage FAIL ${e.javaClass.simpleName} ${e.message}")
            emptyList()
        }
        return newHomePageResponse(request.name, items, items.size >= PAGE_SIZE)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl$API/Search?q=${URLEncoder.encode(query, "UTF-8")}&type=0"
        return try {
            val arr = JSONArray(app.get(url, headers = head).text)
            (0 until arr.length()).mapNotNull { cardToSearch(arr.optJSONObject(it)) }
                .ifEmpty { null }
        } catch (e: Exception) {
            Log.i(TAG, "search FAIL ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    private fun cardToSearch(o: JSONObject?): SearchResponse? {
        if (o == null) return null
        val id = o.optInt("id", 0)
        if (id <= 0) return null
        val title = o.optString("title").trim().ifBlank { return null }
        val poster = o.optString("thumbnail").ifBlank { null }
        return newTvSeriesSearchResponse(title, "$mainUrl/drama/$id", TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // -------------------------------------------------------------- التفاصيل

    override suspend fun load(url: String): LoadResponse? {
        val id = Regex("/drama/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val o = try {
            JSONObject(app.get("$mainUrl$API/Drama/$id?isq=false", headers = head).text)
        } catch (e: Exception) {
            Log.i(TAG, "load FAIL ${e.javaClass.simpleName} ${e.message}")
            return null
        }
        val title = o.optString("title").trim().ifBlank { return null }
        val poster = o.optString("thumbnail").ifBlank { null }
        val plot = o.optString("description").ifBlank { null }
        val tags = ArrayList<String>()
        o.optString("country").trim().takeIf { it.isNotBlank() }?.let { tags.add(it) }

        val epsArr = o.optJSONArray("episodes") ?: JSONArray()
        val eps = (0 until epsArr.length()).mapNotNull { i ->
            val e = epsArr.optJSONObject(i) ?: return@mapNotNull null
            val eid = e.optInt("id", 0)
            if (eid <= 0) null else eid to e.optDouble("number", 0.0)
        }.sortedBy { it.second }

        // النوع من الخادم لا من عدد الحلقات: مسلسلٌ بدأ بحلقةٍ واحدة يبقى مسلسلًا.
        if (o.optString("type").equals("Movie", ignoreCase = true)) {
            return newMovieLoadResponse(title, url, TvType.Movie, "mov:$id") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        val episodes = eps.mapIndexed { idx, (eid, num) ->
            // نُسبق بـmainUrl بأنفسنا لأنّ `newEpisode` يمرّر البيانات على
            // `fixUrl`؛ هكذا تبقى كما هي ويُزيلها [normalize] في [loadLinks].
            newEpisode("$mainUrl/ep:$eid") {
                this.name = "الحلقة ${fmt(num)}"
                this.episode = idx + 1
            }
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    // -------------------------------------------------------------- التشغيل

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val emitted = try {
            val key = normalize(data)
            if (key.startsWith("mov:")) {
                loadMovie(key.removePrefix("mov:").toIntOrNull() ?: return false,
                    subtitleCallback, callback)
            } else {
                val epId = key.removePrefix("ep:").toIntOrNull() ?: return false
                resolveEpisode(epId, null, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.i(TAG, "loadLinks FAIL ${e.javaClass.simpleName} ${e.message}")
            0
        }
        Log.i(TAG, "loadLinks → $emitted رابط")
        return emitted > 0
    }

    /** يزيل ما قد تسبقه به `fixUrl` من `mainUrl` عن بيانات الحلقة/الفيلم. */
    private fun normalize(data: String): String =
        if (data.startsWith("ep:") || data.startsWith("mov:")) data
        else data.removePrefix("$mainUrl/").removePrefix(mainUrl)

    /** فيلم قد يكون جزءًا واحدًا أو أجزاءً (بعض ما يُوسم Movie له عدّة حلقات). */
    private suspend fun loadMovie(
        dramaId: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {        if (dramaId == null) return 0
        val o = try {
            JSONObject(app.get("$mainUrl$API/Drama/$dramaId?isq=false", headers = head).text)
        } catch (e: Exception) {
            return 0
        }
        val arr = o.optJSONArray("episodes") ?: return 0
        val eps = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val eid = e.optInt("id", 0)
            if (eid <= 0) null else eid to e.optDouble("number", 0.0)
        }.sortedBy { it.second }
        if (eps.isEmpty()) return 0

        return coroutineScope {
            eps.mapIndexed { idx, (eid, num) ->
                async {
                    val note = if (eps.size > 1) "الجزء ${fmt(num)}" else null
                    resolveEpisode(eid, note, subtitleCallback, callback)
                }
            }.sumOf { it.await() }
        }
    }

    /**
     * حلقة واحدة: مفتاح الفيديو ← JSON التشغيل، ومفتاح الترجمة ← قائمة الترجمات،
     * بالتوازي. كل سيرفر يعود يُعرض مستقلًّا.
     */
    private suspend fun resolveEpisode(
        epId: Int,
        note: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int = coroutineScope {
        val subsDeferred = async { fetchSubtitles(epId) }

        var emitted = 0
        val body = try {
            app.get(
                "$mainUrl$API/Episode/$epId.png?err=false&ts=0&time=&kkey=${Kkey.forStream(epId)}",
                headers = head,
            ).text
        } catch (e: Exception) {
            Log.i(TAG, "episode $epId FAIL ${e.javaClass.simpleName}")
            ""
        }

        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }

        val subs = try {
            subsDeferred.await()
        } catch (e: Exception) {
            emptyList()
        }
        val seenSub = HashSet<String>()
        for ((label, url) in subs) {
            if (!seenSub.add(url)) continue
            try {
                subtitleCallback(newSubtitleFile(label, url))
            } catch (e: Exception) {
                Log.i(TAG, "subtitle skip ${e.javaClass.simpleName}")
            }
        }

        if (o != null) {
            // `Type: 1` = حلقةٌ متاحة، و`Type: 2` = لم تُطرح بعد (الرابط صفحة
            // عدّاد تنازليّ لا فيديو). ولا نُخفي رابطًا حقيقيًّا: كل ما يشبه
            // وسائط يُعرض ولو كان النوع غير معروف.
            val typeNum = o.optInt("Type", 1)
            val video = normUrl(optText(o, "Video"))
            val alt = normUrl(optText(o, "Video_tmp"))
            val third = normUrl(optText(o, "ThirdParty"))

            if (video.isNotBlank() && (typeNum == 1 || looksMedia(video))) {
                emitted += emitStream(video, "KISSKH", note, subtitleCallback, callback)
            }
            if (alt.isNotBlank() && alt != video &&
                prefBool(KissKHLiveSettings.KEY_SHOW_ALT, true) &&
                (typeNum == 1 || looksMedia(alt))
            ) {
                emitted += emitStream(alt, "KISSKH ALT", note, subtitleCallback, callback)
            }
            if (third.isNotBlank() && prefBool(KissKHLiveSettings.KEY_SHOW_MIRROR, true)) {
                emitted += emitMirror(third, note, subtitleCallback, callback)
            }
        }
        emitted
    }

    /** `//host/path` ← `https://host/path` (الموقع يعيد بعض الروابط بلا بروتوكول). */
    private fun normUrl(s: String): String {
        val t = s.trim()
        return when {
            t.startsWith("//") -> "https:$t"
            else -> t
        }
    }

    /** قيمةٌ نصّية من JSON، مع اعتبار `null`/`None`/`undefined` غيابًا. */
    private fun optText(o: JSONObject?, key: String): String {
        val v = o?.opt(key) ?: return ""
        if (v == JSONObject.NULL) return ""
        val s = v.toString().trim()
        return if (
            s.equals("null", true) || s.equals("none", true) ||
            s.equals("undefined", true)
        ) "" else s
    }

    private fun looksMedia(url: String): Boolean {
        val u = url.lowercase(Locale.US)
        return u.contains(".m3u8") || u.contains(".mp4") || u.contains("hls")
    }

    /** ترجمات الحلقة: كل ما يعيده `/api/Sub/{id}` بلا تصفية. */
    private suspend fun fetchSubtitles(epId: Int): List<Pair<String, String>> {
        if (!showSubs()) return emptyList()
        return try {
            val arr = JSONArray(
                app.get("$mainUrl/api/Sub/$epId?kkey=${Kkey.forSub(epId)}", headers = head).text
            )
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val src = o.optString("src").trim()
                if (src.isEmpty()) return@mapNotNull null
                val label = o.optString("label").trim().ifBlank {
                    o.optString("land").trim().ifBlank { "Subtitle" }
                }
                label to src
            }
                // التصفية قبل إنشاء روابط الوسيط كي لا نُجهّز ما لن يُعرض.
                .filter { langOk(it.first, it.second) }
                .map { (label, src) -> label to subtitleUrl(src) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * يُعيد رابط الترجمة **قابلًا للقراءة**: ملفّات `*.txt1` تُخدَّم عبر
     * [SubProxy] بعد فكّها، وما عداها (`.srt` صريح و`.vtt`) يُمرَّر كما هو.
     */
    private fun subtitleUrl(src: String): String {
        val url = normalizeSubUrl(src)
        val path = url.substringBefore('?').substringBefore('#')
        // التشفير في kisskh.la هو لملفّات `.txt1` وحدها.
        if (!path.endsWith(".txt1", true)) return url

        var cached: String? = null
        val local = SubProxy.publish(md5(url)) {
            cached ?: buildVtt(url)?.also { cached = it }
        }
        // إن تعذّر فتح الوسيط نُبقي الرابط الأصلي (مشفَّرًا) خيرًا من لا شيء.
        return local ?: url
    }

    /** يضيف الامتداد الذي يتوقّعه خادم الترجمة إن كان ناقصًا. */
    private fun normalizeSubUrl(src: String): String {
        val clean = src.trim()
        val q = clean.indexOf('?')
        val path = if (q >= 0) clean.substring(0, q) else clean
        val tail = if (q >= 0) clean.substring(q) else ""
        if (Regex("\\.(srt|vtt|ass|ssa)(\\.txt1)?$", RegexOption.IGNORE_CASE).containsMatchIn(path)) {
            return clean
        }
        return path + ".srt.txt1" + tail
    }

    private fun buildVtt(url: String): String? = try {
        val raw = runBlocking {
            app.get(url, headers = head + mapOf("Referer" to "$mainUrl/")).text
        }
        SubText.toVtt(raw, encrypted = true)
    } catch (e: Exception) {
        Log.i(TAG, "subtitle build FAIL ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun md5(s: String): String = try {
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        Integer.toHexString(s.hashCode())
    }

    /** مرجعٌ مناسب لرابط: أصل مضيفه، وإلا مضيف الموقع. */
    private fun refFor(url: String): String = try {
        val u = URI(url)
        if (u.host.isNullOrBlank()) "$mainUrl/" else "${u.scheme}://${u.host}/"
    } catch (e: Exception) {
        "$mainUrl/"
    }

    private class MirrorSub(
        val label: String,
        val url: String,
        val headers: Map<String, String>? = null,
    )

    /** إعداد مشغّلٍ مضمَّن في الصفحة: مصدر الوسائط + كل ملفات الترجمة. */
    private class Payload(val source: String, val tracks: List<MirrorSub>)

    /**
     * يقرأ `<script type="application/json" id="player-payload">` (megaplay
     * وغيرها): `source` هو master، و`tracks` كل لغات الترجمة المتاحة.
     */
    private fun parsePayload(html: String): Payload? {
        val raw = Regex(
            "<script[^>]*id=\"player-payload\"[^>]*>(.*?)</script>",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1) ?: return null
        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        val source = o.optString("source").trim()
        val tracks = ArrayList<MirrorSub>()
        val arr = o.optJSONArray("tracks")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val u = t.optString("file").trim()
                if (u.isBlank()) continue
                val label = t.optString("label").trim().ifBlank { "Subtitle" }
                tracks.add(MirrorSub(label, u, mapOf("Referer" to refFor(u))))
            }
        }
        if (source.isBlank() && tracks.isEmpty()) return null
        return Payload(source, tracks)
    }

    // -------------------------------------------------------------- الروابط

    /** يفتح رابط بث (m3u8 أو ملف) ويعرض كل جودةٍ بداخله. */
    private suspend fun emitStream(
        url: String,
        server: String,
        note: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        referer: String = "$mainUrl/",
    ): Int {
        if (url.isBlank()) return 0
        val isHls = url.contains(".m3u8", true)

        var variants: List<Pair<Int, String>> = emptyList()
        if (isHls) {
            val txt = try {
                app.get(url, headers = head + mapOf("Referer" to referer)).text
            } catch (e: Exception) {
                Log.i(TAG, "playlist FAIL ${e.javaClass.simpleName}")
                ""
            }
            variants = parseVariants(txt, url)
            parseMediaSubtitles(txt, url).forEach { (label, sub) ->
                if (!showSubs() || !langOk(label, sub)) return@forEach
                try {
                    subtitleCallback(newSubtitleFile(label ?: "Subtitle", sub) {
                        this.headers = mapOf("Referer" to referer)
                    })
                } catch (e: Exception) {
                }
            }
        }
        if (variants.isEmpty()) variants = listOf(0 to url)
        // «أعلى جودة فقط» اختياري؛ الافتراضي إظهار كل الجودات.
        if (!showAllQualities() && variants.size > 1) {
            val top = variants.maxByOrNull { it.first }
            if (top != null && top.first > 0) variants = listOf(top)
        }

        val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        for ((height, link) in variants) {
            callback(
                newExtractorLink(
                    source = name,
                    name = MediaProbe.label(
                        server,
                        MediaProbe.qualityLabel(height),
                        null,
                        link,
                        type,
                        note,
                        MediaProbe.Use.WATCH,
                    ),
                    url = link,
                    type = type,
                ) {
                    this.quality = height
                    this.referer = referer
                    this.headers = mapOf("Referer" to referer)
                }
            )
        }
        return variants.size
    }

    /**
     * سيرفر `ThirdParty`: غالبًا صفحة تضمين (dwish.pro…). نحاول استخراج رابط
     * الوسائط منها، وإن تعذّر **نُبقي الصفحة نفسها** في القائمة حتى يراها
     * المستخدم — لا نُخفي سيرفرًا.
     */
    private suspend fun emitMirror(
        url: String,
        note: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        if (url.contains(".m3u8", true) || url.contains(".mp4", true)) {
            return emitStream(url, "KISSKH MIRROR", note, subtitleCallback, callback)
        }
        val html = try {
            app.get(url, headers = head).text
        } catch (e: Exception) {
            ""
        }
        // مشغّلاتٌ مثل megaplay تُضمّن المصدر و**كل** لغات الترجمة في
        // `player-payload`، فأولى من التقاط رابط الوسائط وحده.
        parsePayload(html)?.let { p ->
            if (showSubs()) {
                for (t in p.tracks) {
                    if (!langOk(t.label, t.url)) continue
                    try {
                        subtitleCallback(newSubtitleFile(t.label, t.url) {
                            t.headers?.let { h -> this.headers = h }
                        })
                    } catch (e: Exception) {
                    }
                }
            }
            if (p.source.isNotBlank()) {
                val n = emitStream(
                    p.source, "KISSKH MIRROR", note, subtitleCallback, callback,
                    refFor(p.source),
                )
                if (n > 0) return n
            }
        }
        val media = Regex("""https?://[^"'\\\s<>]+\.(?:m3u8|mp4)[^"'\\\s<>]*""")
            .findAll(html).map { it.value }.distinct().toList()

        if (media.isNotEmpty()) {
            return media.sumOf {
                emitStream(it, "KISSKH MIRROR", note, subtitleCallback, callback, refFor(it))
            }
        }
        callback(
            newExtractorLink(
                source = name,
                name = MediaProbe.label(
                    "KISSKH MIRROR", null, null, url,
                    ExtractorLinkType.VIDEO, note ?: "embed", MediaProbe.Use.WATCH,
                ),
                url = url,
                type = ExtractorLinkType.VIDEO,
            ) {
                this.referer = "$mainUrl/"
            }
        )
        return 1
    }

    // -------------------------------------------------------------- أدوات

    /** كل variant في master.m3u8: (الارتفاع، الرابط المطلق). */
    private fun parseVariants(text: String, base: String): List<Pair<Int, String>> {
        if (text.isBlank() || !text.contains("#EXT-X-STREAM-INF")) return emptyList()
        val out = ArrayList<Pair<Int, String>>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val h = Regex("RESOLUTION=(\\d+)x(\\d+)").find(line)
                    ?.groupValues?.get(2)?.toIntOrNull() ?: 0
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("#"))) j++
                if (j < lines.size) {
                    val uri = lines[j].trim()
                    if (uri.isNotBlank()) out.add(h to resolve(base, uri))
                }
                i = j
            }
            i++
        }
        return out.distinctBy { it.second }.sortedByDescending { it.first }
    }

    /** ترجمات معلنة داخل الـmaster: `#EXT-X-MEDIA:TYPE=SUBTITLES,…,URI="…"`. */
    private fun parseMediaSubtitles(text: String, base: String): List<Pair<String?, String>> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Pair<String?, String>>()
        for (line in text.lines()) {
            val l = line.trim()
            if (!l.startsWith("#EXT-X-MEDIA:") || !l.contains("TYPE=SUBTITLES", true)) continue
            val uri = attr(l, "URI")?.takeIf { it.isNotBlank() } ?: continue
            val name = attr(l, "NAME")?.trim()?.ifBlank { null }
            val lang = attr(l, "LANGUAGE")?.trim()?.ifBlank { null }
            out.add((name ?: lang) to resolve(base, uri))
        }
        return out.distinctBy { it.second }
    }

    private fun attr(tag: String, key: String): String? =
        Regex("(?i)" + Regex.escape(key) + "=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun resolve(base: String, link: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        return try {
            java.net.URI(base).resolve(link).toString()
        } catch (e: Exception) {
            base.substringBeforeLast('/') + "/" + link.trimStart('/')
        }
    }

    /** `16.6` ← «16.6» و`3.0` ← «3». */
    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) {
            d.toLong().toString()
        } else {
            String.format(Locale.US, "%s", d.toString())
        }

    private companion object {
        const val PAGE_SIZE = 24
    }
}
