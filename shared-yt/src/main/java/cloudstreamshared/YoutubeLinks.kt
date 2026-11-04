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

/**
 * حلّ روابط يوتيوب بنفس ترتيب مصدر «ARY العربية» — يُستعمل من أي مصدر يعرض
 * أعمالًا مضمَّنة على يوتيوب (KingDomDrama، Daramly، …).
 *
 * لماذا لا يكفي `loadExtractor` وحده: مُستخرِج يوتيوب المدمج يعيد **صفر روابط**
 * على كثير من الأجهزة/الشبكات (فحص بوت/توقيع)، فيظهر مصدرٌ كامل بلا تشغيل.
 *
 * الترتيب (نفس ترتيب ARY):
 *  1) NewPipe: التنسيقات **المدمجة** (صوت+صورة) — تعمل مباشرة في المشغّل،
 *     وتُجلب معها ترجمات يوتيوب (أفضل جهد).
 *  2) صفحة `watch` مباشرة: `ytInitialPlayerResponse` ثم `streamingData.formats`
 *     (المدمجة أيضًا) مع إضافة `cpn` الذي يتوقعه يوتيوب.
 *  3) المُستخرِج المدمج في التطبيق (احتياط أخير).
 *
 * نتعمّد تجاهل تنسيقات DASH المنفصلة (صورة بلا صوت): تشغيلها يحتاج وسيطًا
 * محليًا لدمج المسارات (وهو ما يفعله ARY بخادم خاص)، بلا ذلك تظهر صامتة.
 */
object YoutubeLinks {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val YT_REFERER = "https://www.youtube.com/"

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
    ): Int {
        val watchUrl = "${YT_REFERER}watch?v=$videoId"
        var produced = 0
        val seen = LinkedHashSet<String>()

        // (١) NewPipe — التنسيقات المدمجة + الترجمة.
        try {
            val handler = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(watchUrl)
            val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, handler) {}
            extractor.fetchPage()

            val muxed = runCatching { extractor.videoStreams }.getOrNull() ?: emptyList()
            for (vs in muxed) {
                val url = runCatching { vs.content }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(url)) continue
                val h = runCatching { vs.height }.getOrNull() ?: 0
                val label = if (h > 0) "${h}p" else "يوتيوب"
                callback(
                    newExtractorLink(
                        source = sourceName,
                        name = "YouTube · $label",
                        url = url,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.quality = if (h > 0) h else 0
                        this.referer = YT_REFERER
                        this.headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
                    }
                )
                produced++
            }

            emitNewPipeSubtitles(extractor, logTag, subtitleCallback)
            Log.d(logTag, "YouTube $videoId NewPipe muxed=$produced")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(logTag, "YouTube $videoId NewPipe failed :: ${e.message}")
        }

        // (٢) صفحة watch مباشرة.
        if (produced == 0) {
            try {
                val html = app.get(
                    watchUrl,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Accept-Language" to "ar,en;q=0.8",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                ).text
                produced += resolveFromHtml(html, sourceName, logTag, seen, callback)
                Log.d(logTag, "YouTube $videoId html fallback links=$produced")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(logTag, "YouTube $videoId watch page failed :: ${e.message}")
            }
        }

        // (٣) المُستخرِج المدمج.
        if (produced == 0) {
            try {
                // `newExtractorLink` دالّة `suspend`، ولا يجوز نداؤها داخل رد نداء
                // `loadExtractor` غير المعلَّق: نجمع أولًا ثم نُصدر بعد عودته.
                val collected = mutableListOf<ExtractorLink>()
                loadExtractor(watchUrl, YT_REFERER, { subtitleCallback(it) }) { collected.add(it) }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(logTag, "YouTube $videoId builtin extractor failed :: ${e.message}")
            }
        }

        return produced
    }

    // ================================ الترجمة ================================

    /**
     * ترجمات NewPipe الموقّعة. تُبثّ بأفضل جهد بلا وسيط محلي: نضيف `fmt=vtt`
     * كي يقرأها المشغّل (صيغة يوتيوب الافتراضية XML لا تُعرض).
     */
    private suspend fun emitNewPipeSubtitles(
        extractor: YoutubeStreamExtractor,
        logTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val tracks = runCatching { extractor.subtitlesDefault }.getOrNull()?.filterNotNull() ?: return
        val seen = HashSet<String>()
        for (t in tracks) {
            val url = runCatching { t.url }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val withFmt = if (url.contains("fmt=")) url else "$url&fmt=vtt"
            if (!seen.add(withFmt)) continue
            val lang = runCatching { t.locale?.language }.getOrNull()?.takeIf { it.isNotBlank() } ?: "ar"
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

    // ============================ صفحة watch ============================

    private suspend fun resolveFromHtml(
        html: String,
        sourceName: String,
        logTag: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val player = ytPlayerResponse(html) ?: return 0
        val status = player.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status != "OK") {
            Log.w(logTag, "playabilityStatus=$status reason=${player.optJSONObject("playabilityStatus")?.optString("reason")}")
            return 0
        }
        val streaming = player.optJSONObject("streamingData") ?: return 0

        // المدمجة فقط: صوت+صورة في رابط واحد.
        val formats = streaming.optJSONArray("formats") ?: return 0
        var produced = 0
        val headers = mapOf("Referer" to YT_REFERER, "User-Agent" to UA)
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val rawUrl = f.optString("url")
            if (rawUrl.isBlank()) continue
            val url = withCpn(rawUrl)
            if (!seen.add(url)) continue
            val label = f.optString("qualityLabel").ifBlank { f.optInt("itag").toString() }
            val height = f.optInt("height")
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
        return produced
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
