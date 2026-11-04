package cloudstreamshared

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * حلّ روابط يوتيوب بنفس طريقة مصدر «ARY العربية» — يُستعمل من أي مصدر يعرض
 * أعمالًا مضمَّنة على يوتيوب (KingDomDrama، Daramly، …).
 *
 * لماذا لا يكفي `loadExtractor` وحده: مُستخرِج يوتيوب المدمج يعيد **صفر روابط**
 * على كثير من الأجهزة/الشبكات (فحص بوت/توقيع)، فيظهر مصدرٌ كامل بلا تشغيل.
 *
 * ولماذا لا يكفي NewPipe المدمج وحده: يوتيوب لا يمنح اليوم إلا تنسيقًا مدمجًا
 * واحدًا (360p/720p غالبًا)، أما بقية الجودات فمسارات فيديو بلا صوت. لذلك:
 *
 *  1) NewPipe: كل مسار فيديو (video-only) يُبنى له مانيفست DASH محلي يحمل
 *     أفضل صوت معه — فتُعرض **كل** الجودات وتعمل بالصوت. وإن لم يوجد أي
 *     مسار فيديو، نرتدّ إلى التنسيقات المدمجة.
 *  2) صفحة `watch` مباشرة: `ytInitialPlayerResponse` ثم `streamingData.formats`.
 *  3) المُستخرِج المدمج في التطبيق (احتياط أخير).
 */
object YoutubeLinks {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val YT_REFERER = "https://www.youtube.com/"

    /** لغات الترجمة الافتراضية حين لا يحدّد المصدر شيئًا. */
    private val DEFAULT_SUB_LANGS = listOf("ar", "en")

    /** خيارات يقرأها كل مصدر من إعداداته. */
    data class Options(
        /** auto | newpipe | html | extractor */
        val mode: String = "auto",
        /** 0 = بلا حد. */
        val maxHeight: Int = 0,
        val subtitles: Boolean = true,
        /** "ar,en" — فارغة = كل اللغات. */
        val subLangs: Set<String> = emptySet(),
    )

    /** حارس تكرار الترجمة: نفس الفيديو قد يُحلّ أكثر من مرة في نداءٍ واحد. */
    private val subsEmitted = ConcurrentHashMap.newKeySet<String>()

    /** يستخرج معرّف الفيديو من أي صيغة رابط/معرّف يوتيوب. */
    fun videoIdOf(raw: String): String? {
        val s = raw.trim()
        if (Regex("""^[\w-]{11}$""").matches(s)) return s
        Regex("""[?&]v=([\w-]{11})""").find(s)?.let { return it.groupValues[1] }
        Regex("""youtu\.be/([\w-]{11})""").find(s)?.let { return it.groupValues[1] }
        Regex("""/embed/([\w-]{11})""").find(s)?.let { return it.groupValues[1] }
        Regex("""/shorts/([\w-]{11})""").find(s)?.let { return it.groupValues[1] }
        return null
    }

    fun isYoutube(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com") || u.contains("youtu.be")
    }

    /**
     * يُبثّ كل روابط الفيديو المتاحة وترجماته. يعيد عدد الروابط المُنتَجة.
     *
     * @param sourceName اسم المصدر كما يظهر في قائمة السيرفرات.
     */
    suspend fun resolve(
        videoId: String,
        sourceName: String,
        logTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        options: Options = Options(),
    ): Int {
        val watchUrl = "${YT_REFERER}watch?v=$videoId"
        var produced = 0
        val seen = LinkedHashSet<String>()

        val newPipe: suspend () -> Int = {
            resolveFromNewPipe(videoId, watchUrl, sourceName, logTag, seen, options, subtitleCallback, callback)
        }
        val html: suspend () -> Int = {
            resolveFromWatchPage(watchUrl, sourceName, logTag, seen, options, subtitleCallback, callback)
        }
        val extractor: suspend () -> Int = {
            resolveFromBuiltIn(watchUrl, sourceName, logTag, seen, callback)
        }

        // ترتيب المراحل حسب اختيار المستخدم: نبدأ بالمختار ثم نكمل احتياطًا.
        val order = when (options.mode) {
            "newpipe" -> listOf(newPipe, html, extractor)
            "html" -> listOf(html, newPipe, extractor)
            "extractor" -> listOf(extractor, newPipe, html)
            else -> listOf(newPipe, html, extractor)
        }

        for (stage in order) {
            if (produced > 0) break
            produced += stage()
        }

        if (produced == 0) Log.w(logTag, "YouTube $videoId produced no links")
        return produced
    }

    // ============================== NewPipe / DASH ==============================

    private suspend fun resolveFromNewPipe(
        videoId: String,
        watchUrl: String,
        sourceName: String,
        logTag: String,
        seen: MutableSet<String>,
        options: Options,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        return try {
            val handler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(watchUrl)
            val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, handler) {}
            extractor.fetchPage()

            val durationSeconds = runCatching { extractor.length }
                .getOrNull()?.takeIf { it > 0 } ?: 3600L

            val seenMedia = HashSet<String>()

            // كل مسارات الفيديو (بلا صوت): لكلٍّ منها مانيفست DASH بصوته.
            val videoOnly = runCatching { extractor.videoOnlyStreams }.getOrNull()
                ?.mapNotNull { vs ->
                    try {
                        val streamUrl = vs.content ?: return@mapNotNull null
                        if (!seenMedia.add(streamUrl)) return@mapNotNull null
                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                        if (options.maxHeight > 0 && height > options.maxHeight) return@mapNotNull null
                        var mime = runCatching { vs.format?.mimeType }.getOrNull()
                        if (mime.isNullOrEmpty()) mime = YoutubeDashServer.mimeFromUrl(streamUrl, false)
                        val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                        val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null
                        YtVideoTrack(
                            url = streamUrl,
                            mimeType = mime,
                            height = height,
                            label = qualityLabel(height, streamUrl, mime),
                            initRange = initR,
                            indexRange = indexR,
                            codec = codecFromMime(mime),
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.distinctBy { it.url }
                ?: emptyList()

            val audios = runCatching { extractor.audioStreams }.getOrNull()
                ?.mapNotNull { asr ->
                    try {
                        val aUrl = asr.content ?: return@mapNotNull null
                        val bitrate = runCatching { asr.bitrate ?: 128000 }.getOrNull() ?: 128000
                        var mime = runCatching { asr.format?.mimeType }.getOrNull()
                        if (mime.isNullOrEmpty()) mime = YoutubeDashServer.mimeFromUrl(aUrl, true)
                        val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                        val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null
                        var lang = runCatching { asr.audioTrackId ?: "Default" }.getOrNull() ?: "Default"
                        if (lang.contains(".")) lang = lang.substringBefore(".")
                        YtAudioTrack(
                            url = aUrl,
                            mimeType = mime,
                            bitrate = bitrate,
                            initRange = initR,
                            indexRange = indexR,
                            language = lang.uppercase(),
                            codec = codecFromMime(mime),
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.distinctBy { it.url }
                ?: emptyList()

            val audiosByLanguage = audios.groupBy { it.language }

            var produced = 0
            if (videoOnly.isNotEmpty()) {
                YoutubeDashServer.ensureStarted()
                for (video in videoOnly) {
                    // كل لغة صوت تُضاف إلى المانيفست، فيستطيع المشغّل التبديل بينها.
                    val bestAudios = audiosByLanguage.values
                        .mapNotNull { group -> pickAudio(group, video) }
                    val local = YoutubeDashServer.buildAndRegister(
                        video,
                        bestAudios,
                        durationSeconds,
                    ) ?: continue
                    if (!seen.add(local)) continue
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = "YouTube · ${video.label}",
                            url = local,
                            type = ExtractorLinkType.DASH,
                        ) {
                            this.quality = video.height
                            this.referer = YT_REFERER
                            this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                        }
                    )
                    produced++
                }
                Log.d(logTag, "YouTube $videoId DASH links=$produced qualities=${videoOnly.size} audio=${audios.size} langs=${audiosByLanguage.size}")
            }

            // لا مسار DASH؟ نرتدّ إلى المدمجة (فيها صوت أصلًا).
            if (produced == 0) {
                val muxed = runCatching { extractor.videoStreams }.getOrNull() ?: emptyList()
                for (vs in muxed) {
                    val url = runCatching { vs.content }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                    val h = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    if (options.maxHeight > 0 && h > options.maxHeight) continue
                    if (!seen.add(url)) continue
                    val label = if (h > 0) "${h}p" else "يوتيوب"
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = "YouTube · $label",
                            url = url,
                            type = ExtractorLinkType.VIDEO,
                        ) {
                            this.quality = h
                            this.referer = YT_REFERER
                            this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                        }
                    )
                    produced++
                }
                Log.d(logTag, "YouTube $videoId muxed fallback links=$produced")
            }

            if (options.subtitles) {
                // بعد بثّ الروابط: الترجمة تُجلب في خيطٍ منفصل فلا تؤخّر التشغيل،
                // وتظهر في قائمة المشغّل حالما تصل (نفس نمط مصدر ARY).
                val ext = extractor
                val cb = subtitleCallback
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        emitSubtitles(videoId, watchUrl, ext, logTag, options, cb)
                    } catch (e: Exception) {
                        Log.w(logTag, "YouTube $videoId subtitle job failed :: ${e.message}")
                    }
                }
            }

            produced
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "YouTube $videoId NewPipe failed :: ${e.message}")
            0
        }
    }

    // ============================== صفحة watch ==============================

    private suspend fun resolveFromWatchPage(
        watchUrl: String,
        sourceName: String,
        logTag: String,
        seen: MutableSet<String>,
        options: Options,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        return try {
            val html = app.get(
                watchUrl,
                headers = mapOf(
                    "User-Agent" to UA,
                    "Accept-Language" to "ar,en;q=0.8",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
            ).text
            val player = ytPlayerResponse(html) ?: return 0
            val status = player.optJSONObject("playabilityStatus")?.optString("status")
            if (status != null && status != "OK") {
                Log.w(logTag, "playabilityStatus=$status reason=${player.optJSONObject("playabilityStatus")?.optString("reason")}")
                return 0
            }
            val formats = player.optJSONObject("streamingData")?.optJSONArray("formats") ?: return 0
            var produced = 0
            val headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val rawUrl = f.optString("url")
                if (rawUrl.isBlank()) continue
                val height = f.optInt("height")
                if (options.maxHeight > 0 && height > options.maxHeight) continue
                val url = withCpn(rawUrl)
                if (!seen.add(url)) continue
                val label = f.optString("qualityLabel").ifBlank { f.optInt("itag").toString() }
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = "YouTube · $label",
                        url = url,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = height
                        this.referer = YT_REFERER
                        this.headers = headers
                    }
                )
                produced++
            }
            Log.d(logTag, "YouTube watch page links=$produced")
            produced
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "watch page failed :: ${e.message}")
            0
        }
    }

    // ============================ المُستخرِج المدمج ============================

    private suspend fun resolveFromBuiltIn(
        watchUrl: String,
        sourceName: String,
        logTag: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        return try {
            // `newExtractorLink` دالّة `suspend`، ولا يجوز نداؤها داخل رد نداء
            // `loadExtractor` غير المعلَّق: نجمع أولًا ثم نُصدر بعد عودته.
            val collected = mutableListOf<ExtractorLink>()
            loadExtractor(watchUrl, YT_REFERER, { }) { collected.add(it) }
            var produced = 0
            for (link in collected) {
                if (!seen.add(link.url)) continue
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = if (link.name.isBlank()) "YouTube" else "YouTube · ${link.name}",
                        url = link.url,
                        type = link.type,
                    ) {
                        this.quality = link.quality
                        this.referer = YT_REFERER
                        this.headers = link.headers + mapOf("Referer" to YT_REFERER)
                        this.extractorData = link.extractorData
                    }
                )
                produced++
            }
            Log.d(logTag, "builtin extractor links=$produced")
            produced
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "builtin extractor failed :: ${e.message}")
            0
        }
    }

    // ================================ أدوات ================================

    /** «720 · 24.3MB (VP9)» — تسمية تُفرّق الجودات في قائمة السيرفرات. */
    private fun qualityLabel(height: Int, url: String, mime: String?): String {
        val h = if (height > 0) "${height}p" else "تلقائي"
        val mb = sizeMbOf(url)
        val size = if (mb > 0) " · ${String.format("%.1f", mb)}MB" else ""
        val tag = codecTag(mime)
        val codec = if (tag.isNotEmpty()) " ($tag)" else ""
        return "$h$size$codec"
    }

    /** حجم المقطع من `clen=` في رابط googlevideo. */
    private fun sizeMbOf(url: String): Double {
        val n = Regex("""[?&]clen=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return 0.0
        return n / 1048576.0
    }

    private fun codecTag(mime: String?): String {
        val m = mime?.lowercase() ?: return ""
        return when {
            m.contains("av01") -> "AV1"
            m.contains("vp09") || m.contains("vp9") -> "VP9"
            m.contains("avc") || m.contains("h264") -> "AVC"
            m.contains("hev") || m.contains("hvc") || m.contains("h265") -> "HEVC"
            else -> ""
        }
    }

    private fun codecFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val i = mime.indexOf("codecs=")
        if (i < 0) return null
        val v = mime.substring(i + "codecs=".length).trim().removeSurrounding("\"")
        return v.ifBlank { null }
    }

    /** صوت متوافق مع حاوية الفيديو أولًا، ثم الأعلى bitrate. */
    private fun pickAudio(audios: List<YtAudioTrack>, video: YtVideoTrack): YtAudioTrack? {
        if (audios.isEmpty()) return null
        val videoIsWebm = video.mimeType.contains("webm", true)
        val sameFamily = audios.filter { it.mimeType.contains("webm", true) == videoIsWebm }
        return (sameFamily.ifEmpty { audios }).maxByOrNull { it.bitrate }
    }

    // ================================ الترجمة ================================

    /**
     * يوتيوب ينشر مسار ترجمة واحدًا غالبًا، وبقية اللغات يولّدها عند الطلب عبر
     * معامل `tlang` على رابط `timedtext` الموقَّع. لذلك لا نكتفي بما يعيده
     * NewPipe، بل:
     *  1) نجلب النص لكل لغة مطلوبة (الأصلية + اللغات المختارة) عبر HTTP/1.1.
     *  2) نحوّله إلى WebVTT (xml / json3 / srv3).
     *  3) نقدّمه من خادم محلي (`127.0.0.1`)، لا رابط يوتيوب مباشر.
     *
     * ونُزيل توجيهات الموضع الرأسية (`line:…`) من كل لقطة: بعض ملفات يوتيوب
     * تضع النص في أعلى الفيديو، والمشغّل يرسم WebVTT العادي في الأسفل.
     */
    private suspend fun emitSubtitles(
        videoId: String,
        watchUrl: String,
        extractor: YoutubeStreamExtractor?,
        logTag: String,
        options: Options,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        // حارس التكرار: نفس الفيديو قد يُحلّ أكثر من مرة في نداءٍ واحد.
        if (!subsEmitted.add(videoId)) return

        val tracks = runCatching { extractor?.subtitlesDefault }.getOrNull()?.filterNotNull() ?: emptyList()
        val original = tracks.firstOrNull { !it.url.isNullOrBlank() } ?: return
        val base = original.url ?: return
        val originalLang = original.locale?.language?.takeIf { it.isNotBlank() } ?: "ar"

        val wanted = LinkedHashSet<String>()
        wanted.add(originalLang)
        wanted.addAll(options.subLangs.ifEmpty { DEFAULT_SUB_LANGS.toSet() })

        var emitted = 0
        for (lang in wanted) {
            val text = fetchSubtitleText(base, lang, originalLang, watchUrl)
            if (text.isBlank()) continue
            val vtt = toWebVtt(text)
            if (vtt.isBlank()) continue
            val local = YoutubeSubServer.register(vtt) ?: continue
            subtitleCallback(
                newSubtitleFile(subLangLabel(lang), local) {
                    this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                }
            )
            emitted++
            Log.d(logTag, "subtitle ok lang=$lang bytes=${vtt.length}")
        }

        // احتياط أخير: لم ينجح أي جلب → رابط يوتيوب الأصلي بصيغة VTT.
        if (emitted == 0) {
            subtitleCallback(
                newSubtitleFile(subLangLabel(originalLang), vttUrl(base)) {
                    this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                }
            )
            Log.d(logTag, "subtitle direct fallback lang=$originalLang")
        }
    }

    /**
     * جلب نص الترجمة: الأصلية بلا `tlang`، وغيرها بـ`tlang`.
     * `timedtext` قد يردّ 200 بجسد فارغ عند الحجب، ولذلك نحوّل ونتحقق هنا
     * لا في موضع الاستعمال.
     */
    private fun fetchSubtitleText(
        base: String,
        lang: String,
        originalLang: String,
        watchUrl: String,
    ): String {
        val candidates = if (lang == originalLang) {
            listOf(base, "$base&fmt=vtt", "$base&tlang=$lang")
        } else {
            listOf("$base&tlang=$lang", "$base&tlang=$lang&fmt=vtt")
        }
        for (u in candidates) {
            val body = httpGet(u, watchUrl)
            if (body.isNotBlank() && toWebVtt(body).isNotBlank()) return body
        }
        return ""
    }

    /** HTTP/1.1 يدويًا: بعض الأجهزة/الشبكات يُقتل فيها طلب المشغّل (Cronet). */
    private fun httpGet(url: String, referer: String): String = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", referer)
        conn.setRequestProperty("Accept", "text/vtt, text/plain, */*")
        val rc = conn.responseCode
        if (rc !in 200..299) {
            ""
        } else {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    } catch (e: Exception) {
        ""
    }

    /** يحوّل أي صيغة يوتيوب إلى WebVTT صالح. */
    private fun toWebVtt(raw: String): String {
        val t = raw.trim().removePrefix("\uFEFF").trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("WEBVTT")) return sanitizeCues(t)
        return try {
            when {
                t.startsWith("{") -> json3ToVtt(t)
                t.startsWith("<") -> xmlToVtt(t)
                t.contains("-->") -> sanitizeCues("WEBVTT\n\n$t")
                else -> ""
            }
        } catch (e: Exception) {
            Log.w("YoutubeLinks", "vtt convert failed: ${e.message}")
            ""
        }
    }

    /**
     * يُزيل توجيهات الموضع من أسطر التوقيت (`align:start position:0% line:0`)
     * فتُرسم اللقطة في الموضع الافتراضي أسفل الفيديو.
     */
    private fun sanitizeCues(vtt: String): String {
        val out = StringBuilder()
        val normalized = vtt.replace("\r\n", "\n").replace('\r', '\n')
        for (line in normalized.split("\n")) {
            if (line.contains("-->")) {
                val start = line.substringBefore("-->").trim()
                val end = line.substringAfter("-->").trim().substringBefore(' ')
                out.append(start).append(" --> ").append(end).append('\n')
            } else {
                out.append(line).append('\n')
            }
        }
        return out.toString().trim()
    }

    /** `json3`: `events[].segs[].utf8` مع `tStartMs`/`dDurationMs`. */
    private fun json3ToVtt(raw: String): String {
        val root = JSONObject(raw)
        val events = root.optJSONArray("events") ?: return ""
        val sb = StringBuilder("WEBVTT\n\n")
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val line = StringBuilder()
            for (j in 0 until segs.length()) {
                line.append(segs.optJSONObject(j)?.optString("utf8", "").orEmpty())
            }
            val text = line.toString().trim()
            if (text.isEmpty()) continue
            val start = ev.optDouble("tStartMs", 0.0).toLong()
            val dur = ev.optDouble("dDurationMs", 0.0).toLong().coerceAtLeast(1L)
            sb.append(ts(start)).append(" --> ").append(ts(start + dur)).append('\n')
            sb.append(text).append("\n\n")
        }
        return if (sb.length <= 8) "" else sb.toString()
    }

    /** `xml`: `<text start="0.5" dur="1.2">…</text>`. */
    private fun xmlToVtt(raw: String): String {
        val sb = StringBuilder("WEBVTT\n\n")
        val tagRe = Regex("""<text\b([^>]*)>([\s\S]*?)</text>""")
        var found = false
        for (m in tagRe.findAll(raw)) {
            val attrs = m.groupValues[1]
            val body = unescapeXml(m.groupValues[2]).replace('\n', ' ').trim()
            if (body.isEmpty()) continue
            val start = attrDouble(attrs, "start") ?: 0.0
            val dur = attrDouble(attrs, "dur") ?: 2.0
            sb.append(ts(start.toLong())).append(" --> ").append(ts((start + dur).toLong())).append('\n')
            sb.append(body).append("\n\n")
            found = true
        }
        return if (found) sb.toString() else ""
    }

    private fun attrDouble(attrs: String, name: String): Double? =
        Regex("""\b$name\s*=\s*"([\d.]+)"""").find(attrs)
            ?.groupValues?.get(1)?.toDoubleOrNull()

    /** WebVTT timecode. `Locale.US` مقصود: الأرقام العربية-الهندية تُفسد الوقت. */
    private fun ts(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val msec = ms % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d", h, m, s, msec)
    }

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")

    /** يستبدل أي `fmt=` قائم بـ`vtt` بدل لصق معاملٍ ثانٍ يُهمَل. */
    private fun vttUrl(url: String): String = when {
        url.contains("fmt=") -> url.replace(Regex("""([?&])fmt=[^&]*"""), "$1fmt=vtt")
        else -> url + (if (url.contains("?")) "&" else "?") + "fmt=vtt"
    }

    /**
     * اسم اللغة في قائمة الترجمات: «العربية Arabic».
     * الرمز وحده يظهر خامًا في القائمة، والاسم العربي وحده يفشل في الوسم
     * والفلترة — فنجمع الاثنين، وإن فشل كل شيء نُعيد الرمز كما هو.
     */
    private fun subLangLabel(raw: String): String {
        if (raw.isEmpty()) return raw
        val tag = com.lagradost.cloudstream3.utils.SubtitleHelper
            .fromCodeToLangTagIETF(raw)
            ?: com.lagradost.cloudstream3.utils.SubtitleHelper
                .fromLanguageToTagIETF(raw, true)
            ?: return raw
        val ar = com.lagradost.cloudstream3.utils.SubtitleHelper
            .fromTagToLanguageName(tag, "ar") ?: return raw
        val en = com.lagradost.cloudstream3.utils.SubtitleHelper
            .fromTagToEnglishLanguageName(tag) ?: return raw
        return if (ar.equals(en, true)) en else "$ar $en"
    }

    /** يوتيوب يتوقّع `cpn` (content playback nonce) في روابط googlevideo. */
    private fun withCpn(url: String): String {
        if (!url.contains(".googlevideo.com") || url.contains("cpn=")) return url
        val chars = "0123456789abcdef"
        val r = java.util.Random()
        val cpn = (1..16).map { chars[r.nextInt(chars.length)] }.joinToString("")
        return url + (if (url.contains("?")) "&" else "?") + "cpn=$cpn"
    }

    /** `ytInitialPlayerResponse` من HTML بأقواس متوازنة (يتجاهل الأقواس داخل السلاسل). */
    private fun ytPlayerResponse(html: String): JSONObject? {
        val markers = listOf(
            "var ytInitialPlayerResponse = ",
            "window[\"ytInitialPlayerResponse\"] = ",
            "\"ytInitialPlayerResponse\"] = ",
            "ytInitialPlayerResponse = ",
        )
        for (m in markers) {
            val idx = html.indexOf(m)
            if (idx < 0) continue
            val start = idx + m.length
            if (start >= html.length || html[start] != '{') continue
            var depth = 0
            var inStr = false
            var esc = false
            var i = start
            while (i < html.length) {
                val c = html[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                return try {
                                    JSONObject(html.substring(start, i + 1))
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }
                    }
                }
                i++
            }
        }
        return null
    }
}
