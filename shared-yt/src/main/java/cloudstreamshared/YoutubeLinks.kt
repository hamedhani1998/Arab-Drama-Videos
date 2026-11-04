package cloudstreamshared

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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
                    val bestAudio = audiosByLanguage.values.firstOrNull()
                        ?.let { pickAudio(it, video) }
                    val local = YoutubeDashServer.buildAndRegister(
                        video,
                        if (bestAudio != null) listOf(bestAudio) else emptyList(),
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
                Log.d(logTag, "YouTube $videoId DASH links=$produced qualities=${videoOnly.size} audio=${audios.size}")
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
                emitNewPipeSubtitles(videoId, extractor, logTag, options.subLangs, subtitleCallback)
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

    /** ترجمات NewPipe — تُبثّ بأفضل جهد، مع تصفية اللغات المطلوبة. */
    private suspend fun emitNewPipeSubtitles(
        videoId: String,
        extractor: YoutubeStreamExtractor,
        logTag: String,
        subLangs: Set<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (subsEmitted.contains(videoId)) return
        subsEmitted.add(videoId)

        val tracks = runCatching { extractor.subtitlesDefault }.getOrNull()?.filterNotNull() ?: return
        val seen = HashSet<String>()
        for (t in tracks) {
            val url = runCatching { t.url }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val withFmt = if (url.contains("fmt=")) url else "$url&fmt=vtt"
            if (!seen.add(withFmt)) continue
            val lang = runCatching { t.locale?.language }.getOrNull()?.takeIf { it.isNotBlank() } ?: "ar"
            if (subLangs.isNotEmpty() && subLangs.none { lang.lowercase().startsWith(it) }) continue
            try {
                subtitleCallback(
                    newSubtitleFile(lang, withFmt) {
                        this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                    }
                )
            } catch (e: Exception) {
                Log.w(logTag, "subtitle emit failed lang=$lang :: ${e.message}")
            }
        }
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
