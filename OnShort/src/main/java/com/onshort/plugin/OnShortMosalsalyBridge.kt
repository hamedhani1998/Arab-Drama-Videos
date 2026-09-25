package com.onshort.plugin

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * جسر تشغيل إلى محرك Mosalsaly الموحّد (/api/episode-source).
 *
 * يستخدمه OnShort عندما يرفض سيرفر OnShort نفسه منصة نهائيًا
 * ("REST bridge"/"not handled"/"Media refresh failed HTTP 5xx") — تلك المنصات
 * لا تُشغَّل عبر OnShort إطلاقًا. لكن Mosalsaly.com يخدم نفس المنصات عبر واصف
 * مشفّر يُفك بمفتاح AES-GCM ثابت إلى روابط مباشرة جاهزة.
 *
 * المطابقة تتم بالبحث عن العنوان في mosalsaly.com ثم قراءة bookId وحلقات العمل
 * وربط رقم الحلقة (OnShort: 1-based) بترتيب سيريال Mosalsaly (الحلقة ep ↔ عنصر ep-1).
 */
class OnShortMosalsalyBridge(private val prefs: android.content.SharedPreferences?) {

    private val TAG = "OnShortBridge"
    private val MOS_MAIN = "https://mosalsaly.com"
    private val MOS_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val MOS_EP_KEY: ByteArray by lazy {
        try { Base64.getDecoder().decode(MOS_EP_KEY_B64) } catch (e: Exception) { MOS_EP_KEY_B64.toByteArray() }
    }

    private fun logD(msg: String) { try { android.util.Log.d(TAG, msg) } catch (_: Exception) {} }
    private fun logE(msg: String) { try { android.util.Log.e(TAG, msg) } catch (_: Exception) {} }

    companion object {
        private const val MOS_EP_KEY_B64 = "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc="
        private val DIZI1 = "https://dizi1.dramadizilerim.com/?url="

        /** OnShort platform slug → Mosalsaly slug. المنصات المدعومة في Mosalsaly فقط. */
        fun mosalsalySlug(onshortSlug: String?): String? {
            return when (onshortSlug?.lowercase()) {
                "netshort" -> "netshort"
                "goodshort" -> "goodshort"
                "dramabite" -> "dramabite"
                "storyreel" -> "storyreel"
                "vibeshort-goodbos" -> "goodshort"   // نفس خلفية goodbos
                else -> null
            }
        }
    }

    private fun decryptMosEnc(enc: String): String? {
        return try {
            val raw = Base64.getDecoder().decode(enc)
            if (raw.size <= 12) return null
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(MOS_EP_KEY, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            logE("decryptMosEnc fail ${e.message}")
            null
        }
    }

    private fun cleanDecryptedUrl(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return u.trim()
            .replace("\\u0026", "&").replace("\\u003c", "<").replace("\\u003e", ">")
            .replace("\\/", "/")
            .takeIf { it.startsWith("http") || it.startsWith("https") }
    }

    /** netshort video يحتاج تمريره عبر بروكسي dizi1 — نفس ما يفعل Mosalsaly الموقع. */
    private fun needsDizi1(platform: String, kind: ExtractorLinkType, url: String): Boolean =
        platform == "netshort" && kind == ExtractorLinkType.VIDEO && !url.contains("dizi1")

    private fun wrapDizi1(rawUrl: String): String =
        DIZI1 + java.net.URLEncoder.encode(rawUrl, "UTF-8").replace("+", "%20")

    // ---------- البحث عن العمل في mosalsaly.com (مطابقة بالعنوان) ----------

    private data class Card(val title: String, val slug: String)

    private fun parseCards(html: String): List<Card> {
        val out = mutableListOf<Card>()
        // <article> بدون الافتراض على أي class (الصفحة قد تغيّر الـ class أحيانًا)
        val artRe = Regex("""<article\b[^>]*>[\s\S]*?</article>""")
        val titleRe = Regex("""aria-label="([^"]*)"""")
        val hrefRe = Regex("""href="(/mosalsal/([^"/]*))"""")
        for (a in artRe.findAll(html)) {
            val block = a.value
            val title = titleRe.find(block)?.groupValues?.get(1) ?: continue
            val slug = hrefRe.find(block)?.groupValues?.get(2) ?: continue
            if (title.isBlank() || slug.isBlank()) continue
            out.add(Card(title.replace(Regex("""\s+"""), " ").trim(), slug))
        }
        return out
    }

    /** يبحث بالعنوان ويقيس التشابه؛ يفضّل المطابقة الأقرب (تجاهل الحالة العربية/اللاتينية والمسافات). */
    private fun normalizeTitle(t: String): String =
        t.lowercase().replace(Regex("""[^a-z0-9؀-ۿ]"""), "")

    private suspend fun getText(url: String): String? {
        var attempt = 0
        while (attempt < 3) {
            try {
                val t = app.get(url, headers = mapOf("User-Agent" to MOS_UA, "Accept-Language" to "ar"), referer = MOS_MAIN).text
                if (!t.isNullOrBlank()) return t
            } catch (_: Exception) {}
            attempt++
            if (attempt < 3) try { Thread.sleep(350L * attempt) } catch (_: InterruptedException) {}
        }
        return null
    }

    // ---------- تفاصيل العمل في mosalsaly: bookId + الحلقات ----------

    private data class EpInfo(val chapterId: String, val serial: Int)
    private data class SeriesMeta(
        val bookId: String,
        val platform: String?,
        val episodes: List<EpInfo>,
    )

    private fun parseSeries(html: String): SeriesMeta? {
        val bookId = Regex("""(?:\\")?bookId(?:\\")?:\s*(?:\\")?([^"\\<>/\s]{3,})(?:\\")?""")
            .find(html)?.groupValues?.get(1)?.trimEnd('\\') ?: return null
        val platform = Regex("""المصدر.{0,500}?/masdar/([a-z]+)""")
            .find(html)?.groupValues?.get(1)?.lowercase()
        val eps = parseEpisodes(html)
        if (eps.isEmpty()) return null
        return SeriesMeta(bookId, platform, eps)
    }

    private fun parseEpisodes(html: String): List<EpInfo> {
        val out = mutableListOf<EpInfo>()
        var idx = html.indexOf("\\\"episodes\\\":[")
        if (idx < 0) idx = html.indexOf("\"episodes\":[")
        if (idx < 0) return out
        var depth = 0
        var closedAt = -1
        var i = idx
        while (i < html.length) {
            val ch = html[i]
            if (ch == '[') depth++
            else if (ch == ']') { depth--; if (depth == 0) { closedAt = i; break } }
            i++
        }
        if (closedAt < 0) return out
        val block = html.substring(idx, closedAt + 1)
        val objRe = Regex("""\{[^{}]*chapter_id[^{}]*\}""")
        for (m in objRe.findAll(block)) {
            val obj = m.value
            val chId = Regex("""(?:\\")?chapter_id(?:\\")?:\s*(?:\\")?([^"\\<>/\s]{1,})(?:\\")?""")
                .find(obj)?.groupValues?.get(1)?.trimEnd('\\') ?: continue
            val ser = Regex("""(?:\\")?serial_number(?:\\")?:\s*(\d+)""")
                .find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (ser < 0) continue
            out.add(EpInfo(chId, ser))
        }
        return out.distinctBy { it.serial }.sortedBy { it.serial }
    }

    // ---------- واصف الحلقة + الفك + البث ----------

    private fun isExplicitlyExpired(url: String): Boolean =
        Regex("expires=(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { it < System.currentTimeMillis() / 1000 } ?: false

    private suspend fun probeMedia(url: String, declaredType: String?): ExtractorLinkType? {
        val idU = url.lowercase()
        val isDirect = declaredType == "mp4" || declaredType == "mpd" || declaredType == "dash" ||
            idU.contains(".mp4") || idU.contains(".m4v") || idU.contains("videoplayback")
        if (isExplicitlyExpired(url)) return null
        if (isDirect && !idU.contains(".m3u8") && !idU.contains(".m3u")) return ExtractorLinkType.VIDEO
        return try {
            val text = app.get(url, headers = mapOf(
                "User-Agent" to MOS_UA, "Referer" to MOS_MAIN, "Range" to "bytes=0-65535"
            ), referer = MOS_MAIN).text
            if (text.isNotBlank() && text.trimStart().startsWith("#EXTM3U")) ExtractorLinkType.M3U8
            else null
        } catch (e: Exception) { null }
    }

    private suspend fun probeQualityUrl(url: String, declaredType: String?): ExtractorLinkType? {
        if (isExplicitlyExpired(url)) return null
        return try {
            val text = app.get(url, headers = mapOf(
                "User-Agent" to MOS_UA, "Referer" to MOS_MAIN, "Range" to "bytes=0-65535"
            ), referer = MOS_MAIN).text
            if (text.isNotBlank() && text.trimStart().startsWith("#EXTM3U")) ExtractorLinkType.M3U8
            else if (text.isNotBlank()) ExtractorLinkType.VIDEO
            else null
        } catch (e: Exception) { null }
    }

    private suspend fun fetchDescriptor(bookId: String, serial: Int): ObjectNode? {
        val url = "$MOS_MAIN/api/episode-source/$bookId/$serial?lang=ar&refresh=1"
        val text = getText(url) ?: return null
        return try {
            (mapper.readTree(text).get("descriptor") as? ObjectNode)
        } catch (e: Exception) { null }
    }

    /** يبثّ روابط الحلقة من واصف Mosalsaly (كل الجودات + الترجمات). يعيد true عند إصدار رابط حيّ. */
    private suspend fun emitDescriptor(
        descriptor: ObjectNode,
        platform: String,
        serial: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val chain = descriptor.get("chain") as? ArrayNode ?: return false
        var primaryVideoUrl: String? = null
        val seen = HashSet<String>()
        var emitted = false
        for (item in chain) {
            val ch = item as? ObjectNode ?: continue
            val type = ch.get("type")?.asText()?.lowercase()
            val baseEnc = ch.get("enc")?.asText()
            val baseUrl = if (baseEnc != null) cleanDecryptedUrl(decryptMosEnc(baseEnc)) else null
            if (primaryVideoUrl == null) primaryVideoUrl = baseUrl
            val candidates = LinkedHashMap<String, String>()
            if (baseUrl != null) candidates[baseUrl] = ""
            val eq = ch.get("encByQuality") as? ObjectNode
            if (eq != null) {
                for ((q, v) in eq.fields()) {
                    val u = cleanDecryptedUrl(decryptMosEnc(v.asText()))
                    if (u != null) candidates[u] = q
                }
            }
            for ((url, q) in candidates) {
                if (!seen.add(url)) continue
                val kind = if (q.isNotBlank()) probeQualityUrl(url, type) else probeMedia(url, type)
                if (kind == null) continue
                val label = buildString {
                    append("Mosalsaly $serial")
                    if (q.isNotBlank() && q != url) append(" · $q")
                }
                val emitUrl = if (needsDizi1(platform, kind, url)) wrapDizi1(url) else url
                callback(newExtractorLink("OnShort", label, emitUrl, kind) {
                    this.headers = mapOf("User-Agent" to MOS_UA, "Referer" to MOS_MAIN)
                    this.referer = MOS_MAIN
                    if (q.isNotBlank()) this.quality = getQualityFromName(q)
                })
                emitted = true
            }
        }
        // ترجمة
        val sub = descriptor.get("subtitle") as? ObjectNode
        val subEnc = sub?.get("enc")?.asText()
        if (!subEnc.isNullOrBlank()) {
            val subUrl = cleanDecryptedUrl(decryptMosEnc(subEnc))
            if (!subUrl.isNullOrBlank()) {
                try {
                    val isNetshort = subUrl.contains("netshort.com")
                    var subUrlFixed = subUrl
                    if (isNetshort && !primaryVideoUrl.isNullOrBlank()) {
                        val subHost = Regex("https://([^/]+)").find(subUrl)?.groupValues?.get(1)
                        val vidHost = Regex("https://([^/]+)").find(primaryVideoUrl)?.groupValues?.get(1)
                        if (subHost != null && subHost == vidHost && subUrl.contains("auth_key") && primaryVideoUrl.contains("auth_key")) {
                            val subTs = Regex("auth_key=(\\d+)").find(subUrl)?.groupValues?.get(1)?.toLongOrNull()
                            val vidTs = Regex("auth_key=(\\d+)").find(primaryVideoUrl)?.groupValues?.get(1)?.toLongOrNull()
                            if (subTs != null && vidTs != null && subTs < vidTs) {
                                subUrlFixed = subUrl.replace(Regex("auth_key=[^&\\s]+"), "auth_key=" +
                                    Regex("auth_key=([^&\\s]+)").find(primaryVideoUrl)!!.groupValues[1])
                            }
                        }
                    }
                    val fmt = sub.get("format")?.asText()?.lowercase().orEmpty()
                    subUrlFixed = when {
                        subUrlFixed.endsWith(".vtt", true) || subUrlFixed.endsWith(".srt", true) -> subUrlFixed
                        fmt.contains("srt") -> if (subUrlFixed.contains("?")) "$subUrlFixed&.srt" else "$subUrlFixed.srt"
                        else -> if (subUrlFixed.contains("?")) "$subUrlFixed&.vtt" else "$subUrlFixed.vtt"
                    }
                    if (isNetshort) subUrlFixed = wrapDizi1(subUrlFixed)
                    val rawLang = sub.get("language")?.asText()?.takeIf { it.isNotBlank() } ?: "ar"
                    val lang = when {
                        rawLang.startsWith("ar", true) -> "ar"
                        rawLang.startsWith("en", true) -> "en"
                        else -> rawLang
                    }
                    subtitleCallback(newSubtitleFile(lang, subUrlFixed) {
                        this.headers = mapOf("User-Agent" to MOS_UA, "Referer" to MOS_MAIN)
                    })
                } catch (e: Exception) { logE("bridge sub fail ${e.message}") }
            }
        }
        return emitted
    }

    // ---------- الدخول البسيط: البحث / الـ bookId / التشغيل ----------

    /**
     * تشغيل حلقة OnShort عبر Mosalsaly:
     * @param data  "postId|ep" (يُستخدم الرقم فقط) — الحلقة ep (1-based)
     * @param onshortTitle  عنوان العمل كما في OnShort
     * @param onshortSlug   صف المنصة في OnShort (مثل storyreel/dramabite/...)
     */
    suspend fun playViaMosalsaly(
        data: String,
        onshortTitle: String,
        onshortSlug: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        // no gating on onshortSlug: العنوان وحده هو المُنتقى — العمل قد يكون منشورًا
        // على منصة أخرى عند Mosalsaly، والبحث لا يعتمد على slug المنصة أصلاً.
        val ep = data.split("|").getOrNull(1)?.toIntOrNull() ?: 0
        if (ep <= 0) return false
        if (onshortTitle.isBlank()) { logD("bridge: blank title"); return false }

        logD("bridge playViaMosalsaly title='$onshortTitle' ep=$ep slug='$onshortSlug'")

        // 1) بحث بالعنوان
        val q = java.net.URLEncoder.encode(onshortTitle, "UTF-8").replace("+", "%20")
        val searchHtml = getText("$MOS_MAIN/search?q=$q") ?: run {
            logD("bridge: search failed"); return false
        }
        val cards = parseCards(searchHtml).distinctBy { it.slug }
        if (cards.isEmpty()) { logD("bridge: no search cards"); return false }

        // 2) اختر أفضل نتيجة: تطابق العنوان أولًا، ويفضّل المنصة الهدف عند توفرها
        val normTarget = normalizeTitle(onshortTitle)
        var best: Card? = null
        var bestScore = 0
        for (c in cards.take(8)) {
            val norm = normalizeTitle(c.title)
            val titleMatch = norm == normTarget || normTarget.contains(norm) || norm.contains(normTarget)
            // نحمّل التفاصيل فقط للنتائج الواعدة — نفحص المنصة عبر /masdar/ داخل التفاصيل
            // (المنصة ليست في بطاقة البحث)؛ لذا جرّب التطابق الأول ثم نتحقق من البيانات
            if (titleMatch) {
                val score = if (norm == normTarget) 2 else 1
                if (score > bestScore) { bestScore = score; best = c }
                if (bestScore == 2) break
            }
        }
        var card = best
        if (card == null && cards.isNotEmpty()) card = cards.first()
        if (card == null) { logD("bridge: no match"); return false }

        // 3) حمّل صفحة العمل: bookId + platform + episodes
        val detailHtml = getText("$MOS_MAIN/mosalsal/${card.slug}") ?: run {
            logD("bridge: detail failed"); return false
        }
        val meta = parseSeries(detailHtml)
        if (meta == null) { logD("bridge: parseSeries null"); return false }

        // تحقق: العمل على المنصة المطلوبة أم لا (سمح بالاختلاف — بعض العناوين تُنشر على منصتين)
        val detailPlatform = meta.platform?.lowercase()
        logD("bridge: slug=${card.slug} bookId=${meta.bookId} platform=${detailPlatform} eps=${meta.episodes.size}")

        // 4) سيريال الحلقة: الحلقة ep (OnShort 1-based) ↔ عنصر ep-1 من Mosalsaly
        val epInfo = meta.episodes.getOrNull(ep - 1)
        val serial = epInfo?.serial ?: (ep - 1)
        logD("bridge: serial=$serial (ep=$ep)")

        // 5) واصف التشغيل
        val descriptor = fetchDescriptor(meta.bookId, serial) ?: run {
            logD("bridge: descriptor fetch failed (bookId=${meta.bookId} serial=$serial)")
            return false
        }
        val emitted = emitDescriptor(descriptor, detailPlatform ?: onshortSlug, serial, subtitleCallback, callback)
        logD("bridge: emitted=$emitted bookId=${meta.bookId} serial=$serial")
        return emitted
    }
}