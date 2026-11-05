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

    /**
     * لغات الترجمة المطلوبة إضافةً إلى المنشورة. لا نطلب إلا العربية افتراضيًا:
     * يوتيوب لا ينشر العربية إلا نادرًا ويولّدها عند الطلب عبر `tlang`.
     */
    private val DEFAULT_SUB_LANGS = listOf("ar")

    /** خيارات يقرأها كل مصدر من إعداداته. */
    data class Options(
        /** auto | newpipe | html | extractor */
        val mode: String = "auto",
        /** 0 = بلا حد. */
        val maxHeight: Int = 0,
        val subtitles: Boolean = true,
        /** "ar,en" — فارغة: كل المنشور + محاولة العربية. */
        val subLangs: Set<String> = emptySet(),
    )

    /**
     * حارس تكرار الترجمة: نفس الفيديو قد يُحلّ أكثر من مرة في `loadLinks`
     * واحد (سيرفرات متعددة). الحارس مؤقّت لا دائم: تشغيل الفيديو مرة أخرى
     * لاحقًا يجب أن يُظهر الترجمة من جديد.
     */
    private val subsEmittedAt = ConcurrentHashMap<String, Long>()
    private const val SUBS_GUARD_MS = 10_000L

    private fun claimSubtitles(videoId: String): Boolean {
        val now = System.currentTimeMillis()
        val it = subsEmittedAt.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > SUBS_GUARD_MS) it.remove()
        }
        while (true) {
            val prev = subsEmittedAt[videoId]
            if (prev == null) return subsEmittedAt.putIfAbsent(videoId, now) == null
            if (now - prev < SUBS_GUARD_MS) return false
            if (subsEmittedAt.replace(videoId, prev, now)) return true
        }
    }

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
                // ★ متزامنة، لا في خيط منفصل: CloudStream يجمع الترجمات في
                //   مجموعة محلية ويبني قائمتها بعد عودة `loadLinks`. أي إصدار
                //   بعد العودة يضيع — وهذا سبب اختفاء الترجمات تمامًا.
                //   الروابط أُرسلت أعلاه، فالمشغّل لا ينتظر هذه الخطوة ليعمل.
                try {
                    emitSubtitles(videoId, watchUrl, extractor, logTag, options, subtitleCallback)
                } catch (e: Exception) {
                    Log.w(logTag, "YouTube $videoId subtitles failed :: ${e.message}")
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

            // ترجمات صفحة watch — شبكة احتياطية كاملة: مسارات timedtext موجودة
            // في `playerResponse` بكل اللغات المنشورة، فتُظهر الترجمة حتى لو
            // فشل NewPipe كليًا.
            if (options.subtitles) {
                emitCaptionTracks(videoIdOf(watchUrl) ?: watchUrl, player, logTag, subtitleCallback)
            }

            val sd = player.optJSONObject("streamingData") ?: return 0

            // منذ أواخر 2025 يوتيوب لا يضع `url` في `adaptiveFormats`، بل يكتفي
            // بـ`serverAbrStreamingUrl` (مانيفست متكيّف واحد). ولذلك نقرأ
            // القائمتين معًا ونرتّب الاحتياط: DASH حقيقي ← مدمج ← ABR.
            val all = ArrayList<JSONObject>()
            sd.optJSONArray("formats")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { all.add(it) }
            }
            sd.optJSONArray("adaptiveFormats")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { all.add(it) }
            }

            val headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
            val durationSeconds = player.optJSONObject("videoDetails")
                ?.optString("lengthSeconds")?.toLongOrNull()?.takeIf { it > 0 } ?: 3600L

            var produced = 0

            // (1) روابط فيديو حقيقية (نادرة اليوم): نبني لها مانيفست DASH محليًا
            //     مع أفضل صوت، فتُعرض كل الجودة **بصوتها** لا صامتة.
            val videoTracks = all.mapNotNull { f ->
                val u = f.optString("url")
                if (u.isBlank() || !f.optString("mimeType").startsWith("video/")) return@mapNotNull null
                val h = f.optInt("height")
                if (options.maxHeight > 0 && h > options.maxHeight) return@mapNotNull null
                val mime = f.optString("mimeType").ifBlank { YoutubeDashServer.mimeFromUrl(u, false) }
                val initR = rangeOf(f.optJSONObject("initRange"))
                val indexR = rangeOf(f.optJSONObject("indexRange"))
                YtVideoTrack(
                    url = withCpn(u),
                    mimeType = mime,
                    height = h,
                    label = qualityLabel(h, u, mime),
                    initRange = initR,
                    indexRange = indexR,
                    codec = codecFromMime(mime),
                )
            }.distinctBy { it.url }

            val audioTracks = all.mapNotNull { f ->
                val u = f.optString("url")
                if (u.isBlank() || !f.optString("mimeType").startsWith("audio/")) return@mapNotNull null
                val mime = f.optString("mimeType").ifBlank { YoutubeDashServer.mimeFromUrl(u, true) }
                var lang = f.optJSONObject("audioTrack")?.optString("id").orEmpty()
                if (lang.isBlank()) lang = "DEFAULT"
                if (lang.contains(".")) lang = lang.substringBefore(".")
                YtAudioTrack(
                    url = withCpn(u),
                    mimeType = mime,
                    bitrate = f.optInt("bitrate").takeIf { it > 0 } ?: 128000,
                    initRange = rangeOf(f.optJSONObject("initRange")),
                    indexRange = rangeOf(f.optJSONObject("indexRange")),
                    language = lang.uppercase(),
                    codec = codecFromMime(mime),
                )
            }.distinctBy { it.url }

            if (videoTracks.isNotEmpty()) {
                YoutubeDashServer.ensureStarted()
                val audiosByLanguage = audioTracks.groupBy { it.language }
                for (video in videoTracks) {
                    val bestAudios = audiosByLanguage.values.mapNotNull { pickAudio(it, video) }
                    val local = YoutubeDashServer.buildAndRegister(video, bestAudios, durationSeconds) ?: continue
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
                            this.headers = headers
                        }
                    )
                    produced++
                }
                Log.d(logTag, "YouTube watch DASH links=$produced qualities=${videoTracks.size}")
            }

            // (2) تنسيقات مدمجة (فيها صوت أصلًا) — إن لم توجد مسارات فيديو.
            if (produced == 0) {
                sd.optJSONArray("formats")?.let { a ->
                    for (i in 0 until a.length()) {
                        val f = a.optJSONObject(i) ?: continue
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
                }
            }

            // (3) احتياط ABR: لا `url` لأي تنسيق، لكن `serverAbrStreamingUrl`
            //     موجود. نُظهر **كل** تسمية جودة (وإن شاركت الرابط) لأن المشغّل
            //     يعرض التسميات في القائمة، والمانيفست المتكيّف يتكفّل بالجودة.
            //     هذا هو نفس مسار ARY العربية المُثبت على الجهاز.
            if (produced == 0) {
                val abr = sd.optString("serverAbrStreamingUrl")
                if (abr.isNotBlank()) {
                    val labels = LinkedHashMap<String, Int>()
                    for (f in all) {
                        val q = f.optString("qualityLabel")
                        if (q.isBlank() || q == "undefined") continue
                        val h = f.optInt("height")
                        if (options.maxHeight > 0 && h > options.maxHeight) continue
                        labels[q] = maxOf(h, labels[q] ?: 0)
                    }
                    val ordered = labels.entries.sortedByDescending { it.value }
                    val url = withCpn(abr)
                    if (ordered.isEmpty()) {
                        if (seen.add("$url#abr")) {
                            callback(
                                newExtractorLink(
                                    source = sourceName,
                                    name = "YouTube · ABR",
                                    url = url,
                                    type = ExtractorLinkType.VIDEO,
                                ) {
                                    this.referer = YT_REFERER
                                    this.headers = headers
                                }
                            )
                            produced++
                        }
                    } else {
                        for ((label, h) in ordered) {
                            if (!seen.add("$url#abr:$label")) continue
                            callback(
                                newExtractorLink(
                                    source = sourceName,
                                    name = "YouTube · $label (ABR)",
                                    url = url,
                                    type = ExtractorLinkType.VIDEO,
                                ) {
                                    this.quality = h
                                    this.referer = YT_REFERER
                                    this.headers = headers
                                }
                            )
                            produced++
                        }
                    }
                    Log.d(logTag, "YouTube watch ABR entries=$produced labels=${labels.size}")
                }
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

    /** `{"start":"0","end":"738"}` → `"0-738"` (صيغة SegmentBase في المانيفست). */
    private fun rangeOf(o: JSONObject?): String? {
        if (o == null) return null
        val start = o.optString("start")
        val end = o.optString("end")
        if (start.isBlank() || end.isBlank()) return null
        return "$start-$end"
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
     * يصدّر الترجمات للمشغّل — **متزامنًا** قبل عودة `loadLinks`، لأن
     * CloudStream يجمعها في مجموعة محلية ثم يبني القائمة بعد العودة؛ أي
     * إصدار من خيطٍ منفصل يضيع (وهو سبب «الترجمة لا تظهر»).
     *
     *  1) كل مسار منشور على يوتيوب يُصدَّر فورًا برابط `fmt=vtt` مباشر:
     *     صفر طلبات شبكة، وكل اللغات المنشورة تظهر دائمًا. نستبدل `fmt`
     *     ولا نُلحقه، لأن رابط `timedtext` الموقَّع يحمل `fmt=ttml` أصلًا
     *     ولصق معامل ثانٍ يُهمَل فيعود TTML وهو ما يرفضه المشغّل.
     *  2) ثم أفضل جهد للغات المطلوبة غير المنشورة عبر `tlang` (العربية
     *     افتراضيًا)، بميزانية زمنية قصيرة. إن فشلت تبقى المنشورة ظاهرة.
     */
    private suspend fun emitSubtitles(
        videoId: String,
        watchUrl: String,
        extractor: YoutubeStreamExtractor?,
        logTag: String,
        options: Options,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val tracks = runCatching { extractor?.subtitlesDefault }
            .getOrNull()?.filterNotNull() ?: emptyList()
        if (tracks.isEmpty()) return
        if (!claimSubtitles(videoId)) return

        // (1) المنشور: رابط VTT مباشر، فورًا ودون شبكة.
        val nativeLangs = LinkedHashSet<String>()
        val seenTracks = HashSet<String>()
        var emitted = 0
        for (track in tracks) {
            val raw = track.url?.takeIf { it.isNotBlank() } ?: continue
            if (!seenTracks.add(raw)) continue
            val lang = track.locale?.language?.takeIf { it.isNotBlank() } ?: "und"
            nativeLangs.add(baseLang(lang))
            subtitleCallback(
                newSubtitleFile(subLangLabel(lang), vttUrl(raw)) {
                    this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                }
            )
            emitted++
        }
        Log.d(logTag, "YouTube $videoId native subtitles=$emitted langs=$nativeLangs")
        if (emitted == 0) return

        // (2) المطلوب غير المنشور: `tlang` على الرابط الموقَّع (ميزانية 9ث).
        val base = tracks.firstNotNullOfOrNull { t -> t.url?.takeIf { it.isNotBlank() } } ?: return
        val originalLang = tracks.firstOrNull { !it.url.isNullOrBlank() }
            ?.locale?.language?.takeIf { it.isNotBlank() } ?: "ar"

        val requested = LinkedHashSet<String>()
        requested.addAll(options.subLangs)
        if (options.subLangs.isEmpty()) requested.addAll(DEFAULT_SUB_LANGS)

        val missing = requested
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && baseLang(it) !in nativeLangs }
            .distinct()
            .take(3)
        if (missing.isEmpty()) return

        val started = System.currentTimeMillis()
        val budget = 9_000L
        for (lang in missing) {
            val left = budget - (System.currentTimeMillis() - started)
            if (left < 1_000L) break
            val text = fetchSubtitleText(base, lang, originalLang, watchUrl, left)
            if (text.isBlank()) continue
            val vtt = toWebVtt(text)
            if (vtt.isBlank()) continue
            val local = YoutubeSubServer.register(vtt) ?: continue
            subtitleCallback(
                newSubtitleFile(subLangLabel(lang), local) {
                    this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                }
            )
            Log.d(logTag, "YouTube $videoId translated subtitle lang=$lang bytes=${vtt.length}")
        }
    }

    /**
     * مسارات الترجمة من `playerResponse.captions` مباشرةً. تُسلَّم كلغة
     * `fmt=vtt` بلا أي جلب، فحتى لو تعذّر استخراج أي رابط تشغيل تظل الترجمة
     * ظاهرة في القائمة.
     */
    private suspend fun emitCaptionTracks(
        videoId: String,
        player: JSONObject,
        logTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val list = player.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return
        if (list.length() == 0) return
        if (!claimSubtitles(videoId)) return

        val seen = HashSet<String>()
        var n = 0
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val base = t.optString("baseUrl").takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(base)) continue
            val lang = t.optString("languageCode").ifBlank { "und" }
            val name = t.optJSONObject("name")?.optString("simpleText")?.takeIf { it.isNotBlank() }
            val label = name ?: subLangLabel(lang)
            subtitleCallback(
                newSubtitleFile(label, vttUrl(base)) {
                    this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                }
            )
            n++
        }
        Log.d(logTag, "YouTube $videoId watch captions=$n")
    }

    /**
     * جلب نص الترجمة: الأصلية بلا `tlang`، وغيرها بـ`tlang`.
     * نستبدل `fmt` بدل لصقه (المعامل الثاني يُهمَل)، ونتحقّق من قابلية
     * التحويل هنا لا في موضع الاستعمال — فـ`timedtext` قد يردّ 200 بجسد
     * فارغ عند الحجب.
     */
    private fun fetchSubtitleText(
        base: String,
        lang: String,
        originalLang: String,
        watchUrl: String,
        budgetMs: Long,
    ): String {
        val t = budgetMs.toInt().coerceIn(1_500, 9_000)
        val connect = minOf(4_500, t)
        val read = minOf(6_000, t)
        val candidates = if (baseLang(lang) == baseLang(originalLang)) {
            listOf(vttUrl(base), base, withFmt(base, "srv3"))
        } else {
            listOf(
                "$base&tlang=$lang",
                withFmt("$base&tlang=$lang", "vtt"),
            )
        }
        for (u in candidates) {
            if (u.isBlank()) continue
            val body = httpGet(u, watchUrl, connect, read)
            if (body.isNotBlank() && toWebVtt(body).isNotBlank()) return body
        }
        return ""
    }

    /** HTTP/1.1 يدويًا: بعض الأجهزة/الشبكات يُقتل فيها طلب المشغّل (Cronet). */
    private fun httpGet(url: String, referer: String, connectMs: Int, readMs: Int): String = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", referer)
        conn.setRequestProperty("Accept", "text/vtt, text/plain, */*")
        // بلا هذا قد يردّ الخادم gzip، وقراءتنا الخام تُنتج بايتات غير نصية.
        conn.setRequestProperty("Accept-Encoding", "identity")
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

    /**
     * XML بيوتيوب بثلاث صيغ:
     *  - `transcript`: `<text start="0.5" dur="1.2">…</text>`
     *  - `ttml`: `<p begin="00:00:01.000" end="00:00:03.000">…</p>` (الافتراضية)
     *  - `srv3`: `<p t="1000" d="2000">…</p>` (ميلي ثانية)
     * تجاهل TTML سابقًا كان يجعل كل جلب يبدو فارغًا — فلا تظهر ترجمة أبدًا.
     */
    private fun xmlToVtt(raw: String): String {
        val transcript = transcriptToVtt(raw)
        if (transcript.isNotBlank()) return transcript
        val ttml = ttmlToVtt(raw)
        if (ttml.isNotBlank()) return ttml
        return srv3ToVtt(raw)
    }

    /** الصيغة النصّية القديمة: `<text start="0.5" dur="1.2">…</text>`. */
    private fun transcriptToVtt(raw: String): String {
        val sb = StringBuilder("WEBVTT\n\n")
        val tagRe = Regex("""<text\b([^>]*)>([\s\S]*?)</text>""")
        var found = false
        for (m in tagRe.findAll(raw)) {
            val attrs = m.groupValues[1]
            val body = stripTags(m.groupValues[2])
            if (body.isEmpty()) continue
            val start = attr(attrs, "start")?.toDoubleOrNull() ?: 0.0
            val dur = attr(attrs, "dur")?.toDoubleOrNull() ?: 2.0
            sb.append(ts((start * 1000).toLong()))
                .append(" --> ")
                .append(ts(((start + dur) * 1000).toLong()))
                .append('\n')
            sb.append(body).append("\n\n")
            found = true
        }
        return if (found) sb.toString() else ""
    }

    /** TTML: `<p begin="…" end="…">…</p>`؛ بعض الملفات تستعمل `dur`. */
    private fun ttmlToVtt(raw: String): String {
        if (!raw.contains("<p", true)) return ""
        val sb = StringBuilder("WEBVTT\n\n")
        val tagRe = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""")
        var found = false
        for (m in tagRe.findAll(raw)) {
            val attrs = m.groupValues[1]
            val body = stripTags(m.groupValues[2])
            if (body.isEmpty()) continue
            val begin = parseTimecode(attr(attrs, "begin")) ?: continue
            val end = parseTimecode(attr(attrs, "end"))
                ?: parseTimecode(attr(attrs, "dur"))?.let { begin + it }
                ?: (begin + 2000)
            if (end <= begin) continue
            sb.append(ts(begin)).append(" --> ").append(ts(end)).append('\n')
            sb.append(body).append("\n\n")
            found = true
        }
        return if (found) sb.toString() else ""
    }

    /** srv3: `<p t="1230" d="1870">…<br/>…</p>` — الأرقام ميلي ثانية. */
    private fun srv3ToVtt(raw: String): String {
        val sb = StringBuilder("WEBVTT\n\n")
        val tagRe = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""")
        var found = false
        for (m in tagRe.findAll(raw)) {
            val attrs = m.groupValues[1]
            val body = stripTags(m.groupValues[2])
            if (body.isEmpty()) continue
            val start = attr(attrs, "t")?.toLongOrNull()
                ?: parseTimecode(attr(attrs, "begin"))
                ?: continue
            val dur = (attr(attrs, "d")?.toLongOrNull() ?: 2000L).coerceAtLeast(1L)
            sb.append(ts(start)).append(" --> ").append(ts(start + dur)).append('\n')
            sb.append(body).append("\n\n")
            found = true
        }
        return if (found) sb.toString() else ""
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(attrs)?.groupValues?.get(1)

    /** `00:00:01.500` أو `1.5s` أو `1500ms` → ميلي ثانية. */
    private fun parseTimecode(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.endsWith("ms", true)) return s.dropLast(2).trim().toDoubleOrNull()?.toLong()
        val t = s.removeSuffix("s").removeSuffix("S").trim()
        if (t.isEmpty()) return null
        if (t.contains(":")) {
            var total = 0.0
            for (p in t.split(":")) total = total * 60 + (p.toDoubleOrNull() ?: return null)
            return (total * 1000).toLong()
        }
        if (t.endsWith("f", true)) return null
        return t.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }

    /** يزيل وسوم الكيانات الداخلية (`<s>`, `<span>`, `<br/>`) ويوحّد الفراغات. */
    private fun stripTags(raw: String): String = unescapeXml(
        raw.replace(Regex("""<br\s*/?>"""), " ")
            .replace(Regex("""<[^>]*>"""), "")
    ).replace(Regex("""\s+"""), " ").trim()

    private fun baseLang(code: String): String =
        code.trim().lowercase().substringBefore('-').substringBefore('_')

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
    private fun vttUrl(url: String): String = withFmt(url, "vtt")

    private fun withFmt(url: String, fmt: String): String = when {
        url.contains("fmt=") -> url.replace(Regex("""([?&])fmt=[^&]*"""), "$1fmt=$fmt")
        else -> url + (if (url.contains("?")) "&" else "?") + "fmt=$fmt"
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
