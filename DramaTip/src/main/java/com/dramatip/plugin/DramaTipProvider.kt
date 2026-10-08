package com.dramatip.plugin

import cloudstreamshared.FormatTag
import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

private const val TIP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
private const val TIP_MAIN = "https://dramatip.net/ar"

/**
 * DramaTip — مجمّع دراما قصيرة يجمع أكثر من عشرين منصة (NetShort، MyDramaWave،
 * DramaBox، GoodShort، ReelShort…). الموقع Next.js، وصفحاته تُبثّ «RSC» —
 * تدفّق رحلات Next عبر self.__next_f.push؛ لا __NEXT_DATA__ ولا wp-json.
 * الكتالوج (البطاقات) في <script type="application/ld+json">.
 *
 * المسار:
 * - الرئيسية/البحث: JSON-LD (itemListElement) — صف لكل منصة (مفتاح الصف = slug
 *   المنصة). الصفحة 2+ غير موجودة (كل صيغ الترقيم تعيد البطاقات نفسها — قِيس).
 * - التفاصيل (load): JSON-LD TVSeries (الاسم/البوستر/عدد الحلقات) + روابط
 *   الحلقات الصريحة في HTML (/ar/series/<slug>/episode-N).
 * - التشغيل (loadLinks): روابط الفيديو والترجمة مشفّرة AES-256-GCM بمفتاح ثابت
 *   في JS (QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=). التصفُّر: base64 ⇒
 *   nonce (أول 12 بايت) + ciphertext. Flight كل حلقة يحمل:
 *   sourceFirst.chain[] (فيديو الأساس) وsourceFirst.subtitle (ترجمة webvtt)
 *   وnextSourceFirst (سيرفر بديل). صيغة كل مصدر تُقرأ من الرابط نفسه ومن
 *   إعلان الموقع (type/mime) وتُضاف كوسم [MP4] / [M3U8] إلى اسم السيرفر —
 *   طلب المستخدم الصريح: «أريد أن أفرّق بين الصيغ».
 *
 * يعتمد قليلًا على مطابقة اسم المنصّة بالعربية؛ أسماء الصفوف تُكتب من صفحة
 * /ar/sources بنفس حروفها.
 */
class DramaTipProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "DramaTip"
    override var mainUrl = TIP_MAIN
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun logD(msg: String) { try { android.util.Log.d("DramaTip", msg) } catch (_: Exception) {} }
    private fun logE(msg: String) { try { android.util.Log.e("DramaTip", msg) } catch (_: Exception) {} }

    // منصّات الموقع من /ar/sources (slug → الاسم كما يعرضه الموقع). ٢٣ منصة.
    // ملاحظة: الصف يعرض بطاقات تلك المنصّة فقط — لكن بعضها (KalosTV مثلًا) قد
    // يكون محتوياته مقفلة/قليلة في الموقع نفسه؛ لا أثر لذلك هنا.
    private val platformRows = listOf(
        "bibishort" to "BibiShort",
        "bilitv" to "BiliTV",
        "dotdrama" to "DotDrama",
        "dramabite" to "DramaBite",
        "dramabox" to "DramaBox",
        "flickreels" to "FlickReels",
        "goodshort" to "GoodShort",
        "happyshort" to "HappyShort",
        "idrama" to "iDrama",
        "joyreels" to "JoyReels",
        "kalostv" to "KalosTV",
        "moboreels" to "MoboReels",
        "moreshort" to "MoreShort",
        "mydramawave" to "MyDramaWave",
        "netshort" to "NetShort",
        "petadrama" to "PetaDrama",
        "pinedrama" to "PineDrama",
        "playlet" to "Playlet",
        "reelshort" to "Reelshort",
        "shorttv" to "ShortTV",
        "shortwave" to "ShortWave",
        "stardust" to "Stardust",
        "storyreel" to "StoryReel",
    )

    // صف فرعي لكل منصة — مفتاح الصف = slug، يُمرَّر في request.data.
    override val mainPage = mainPageOf(*platformRows.map { (slug, label) -> slug to label }.toTypedArray())

    private fun reqHeaders() = mapOf(
        "User-Agent" to TIP_UA,
        "Accept-Language" to "ar,en;q=0.8",
        "Referer" to TIP_MAIN
    )

    private fun escapeSlug(slug: String): String =
        URLEncoder.encode(slug, "UTF-8").replace("+", "%20")

    private suspend fun fetchText(url: String): String? = try {
        app.get(url, referer = TIP_MAIN, headers = reqHeaders()).text
    } catch (e: Exception) { logE("fetch $url: ${e.message}"); null }

    // ---------- JSON-LD ----------
    private fun jsonLdDoc(html: String): org.json.JSONObject? {
        val m = Regex("""<script type="application/ld\+json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return try { org.json.JSONObject(m.groupValues[1]) } catch (_: Exception) { null }
    }

    private fun graphNodes(doc: org.json.JSONObject?): List<org.json.JSONObject> {
        if (doc == null) return emptyList()
        return try {
            val g = doc.optJSONArray("@graph")
            if (g != null) (0 until g.length()).mapNotNull { i -> g.optJSONObject(i) }
            else listOf(doc)
        } catch (_: Exception) { listOf(doc) }
    }

    // كل mainEntity يحمل itemListElement (بطاقات كتالوج).
    private fun collections(html: String): List<org.json.JSONObject> {
        val out = mutableListOf<org.json.JSONObject>()
        for (node in graphNodes(jsonLdDoc(html))) {
            val me = node.optJSONObject("mainEntity")
            if (me != null && me.has("itemListElement")) out.add(me)
        }
        return out
    }

    private fun parseCollection(html: String, seen: MutableSet<String>): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        for (me in collections(html)) {
            val items = me.optJSONArray("itemListElement") ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val url = it.optString("url").takeIf { u -> u.isNotBlank() } ?: continue
                val title = it.optString("name").replace(Regex("""\s+"""), " ").trim()
                if (title.isBlank() || !seen.add(url)) continue
                val poster = it.optString("image").takeIf { p -> p.isNotBlank() }
                out.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                })
            }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            if (page > 1) return null // لا ترقيم (قِيس: كل الصيغ تعيد الـ 24 نفسها)
            val slug = request.data?.takeIf { it.isNotBlank() } ?: return null
            val h = fetchText("$TIP_MAIN/source/${escapeSlug(slug)}") ?: return null
            val items = parseCollection(h, java.util.HashSet())
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val h = fetchText("$TIP_MAIN/series?q=$q") ?: return null
            val items = parseCollection(h, java.util.HashSet())
            if (items.isEmpty()) null else items
        } catch (e: Exception) { null }
    }

    // ---------- التفاصيل (load) ----------
    private fun tvsNode(html: String): org.json.JSONObject? {
        for (node in graphNodes(jsonLdDoc(html))) {
            if (node.optString("@type") == "TVSeries") return node
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val h = fetchText(url) ?: return null
            val tvs = tvsNode(h) ?: return null
            val title = tvs.optString("name").replace(Regex("""\s+"""), " ").trim()
                .takeIf { it.isNotBlank() } ?: return null
            val poster = tvs.optString("image").takeIf { it.isNotBlank() }
            val total = tvs.optInt("numberOfEpisodes").takeIf { it > 0 } ?: 1

            // روابط الحلقات الصريحة (الموقع يكتب /ar/series/<slug>/episode-N في HTML).
            val hrefs = Regex("""/ar/series/([^"/\s]+)/episode-(\d+)""").findAll(h).toList()
            val pairs = if (hrefs.isNotEmpty()) {
                hrefs.mapNotNull { m ->
                    val n = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    n to m.groupValues[1]
                }.distinctBy { it.first }.sortedBy { it.first }
            } else {
                (1..total).map { it to url.substringAfterLast('/') }
            }

            val eps = pairs.map { (n, slug2) ->
                newEpisode("$slug2|$n") {
                    this.episode = n
                    this.name = "الحلقة $n"
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
            }
        } catch (e: Exception) { null }
    }

    // ---------- فكّ تدفّق RSC ----------
    // كل حزمة: self.__next_f.push([1,"..."]). الجسم نصٌّ JSON مُهرَّب (\uXXXX،
    // \\"…) — نُهرّبه إلى النص الفعلي ثم نجمع الحزم. الكل لا بد منه لقراءة
    // أسماء/حقول عربية ومواقع الحقول المشفّرة (فالنصوص العربية تأتي \u لهم).
    private fun parseHex4(raw: String, at: Int): Int {
        var v = 0
        var k = 0
        while (k < 4 && at + k < raw.length) {
            val d = raw[at + k]
            val x = when (d) {
                in '0'..'9' -> d - '0'
                in 'a'..'f' -> d - 'a' + 10
                in 'A'..'F' -> d - 'A' + 10
                else -> return v
            }
            v = (v shl 4) or x
            k++
        }
        return v
    }

    private fun unescapeJsonStr(raw: String, from: Int, to: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < to) {
            val c = raw[i]
            if (c == '\\' && i + 1 < to) {
                when (val n = raw[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        var code = parseHex4(raw, i + 2)
                        i += 6
                        if (code in 0xD800..0xDBFF && i + 6 <= to &&
                            raw[i] == '\\' && raw[i + 1] == 'u'
                        ) {
                            val low = parseHex4(raw, i + 2)
                            if (low in 0xDC00..0xDFFF) {
                                code = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                                i += 6
                            }
                        }
                        sb.appendCodePoint(code)
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    // (Regex بلا DOT_MATCHES_ALL — الحزم واحدة سطر.)
    private val flightRe = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")

    private fun extractFlight(html: String): String? {
        val sb = StringBuilder()
        var any = false
        for (m in flightRe.findAll(html)) {
            any = true
            sb.append(unescapeJsonStr(m.groupValues[1], 0, m.groupValues[1].length))
        }
        return if (any) sb.toString() else null
    }

    // ---------- AES-256-GCM (المفتاح الثابت في JS) ----------
    private val aesKey: ByteArray = android.util.Base64.decode(
        "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc=", android.util.Base64.NO_WRAP
    )

    private fun decryptEnc(enc: String): String {
        val b = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
        val spec = javax.crypto.spec.GCMParameterSpec(128, b, 0, 12)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), spec)
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    // ---------- روابط التشغيل ----------
    // خريطة الرمز → صيغة معروضة (قيم إعلان الموقع: mp4/dramawave/m3u8…).
    private fun declaredFromType(type: String?): String? = when {
        type == null -> null
        type.contains("mp4") -> "MP4"
        type.contains("m3u8") || type.contains("hls") || type.contains("mpegurl") -> "M3U8"
        type.contains("mpd") || type.contains("dash") -> "DASH"
        else -> type.uppercase()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // الحِمل = "slug|رقم". CloudStream قد يُسبق slug بـ mainUrl — نأخذ آخر جزء بعد '/'.
            val parts = data.trim().split("|")
            val ep = parts.lastOrNull()?.toIntOrNull() ?: 1
            val slugPart = if (parts.size >= 2) parts.dropLast(1).joinToString("|") else parts.firstOrNull() ?: ""
            val slug = slugPart.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false

            val url = "$TIP_MAIN/series/${escapeSlug(slug)}/episode-$ep"
            val html = fetchText(url) ?: return false
            val f = extractFlight(html) ?: return false

            // اسم المنصة من غلاف sourceFirst (يكون الاسم في أول مصدر)
            val source = Regex(""""sourceFirst":{"source":"([^"]+)"""").find(f)?.groupValues?.get(1)
                ?: Regex(""""source":"([^"]+)"""").find(f)?.groupValues?.get(1)
                ?: name

            val collected = mutableListOf<ExtractorLink>()
            var video = 0
            val seen = mutableSetOf<String>()
            var sentSub = false

            // كل blobs (فيديو + ترجمة) — نفس النمط: "enc":"base64".
            // التصنيف بنيوي (كهيكل JSON ذاته): لا نعتمد نافذة سياق قد تختلط
            // (بلوب nextSourceFirst يجاور كائن subtitle فيتسرّب format:webvtt إليه).
            // — كائن الترجمة يحمل format + language (بلا source/refresh).
            // — كائن الفيديو يحمل type + (source أو refresh) (بلا format/language)،
            //   و plain يحتوي mime_type=video_mp4 (أو .m3u8/..mpd).
            for (m in Regex(""""enc":"([A-Za-z0-9+/=]+)"""").findAll(f)) {
                val rawEnc = m.groupValues[1]
                // «نافذة قصيرة بعد enc» فقط — داخل كائن الترجمة توجد format/language؛
                // في كائن الفيديو توجد type/source/refresh (لا format ولا language).
                val after = f.substring(m.range.last + 1, minOf(f.length, m.range.last + 160))
                val before = f.substring(maxOf(0, m.range.first - 140), m.range.first)
                val fmt = Regex(""""format":\s*"([^"]+)"""").find(after)?.groupValues?.get(1)
                val langRaw = Regex(""""language":\s*"([^"]+)"""").find(after)?.groupValues?.get(1)
                val typeRaw = Regex(""""type":\s*"([^"]+)"""").find(after)?.groupValues?.get(1)
                    ?: Regex(""""type":\s*"([^"]+)"""").find(before)?.groupValues?.get(1)
                val hasFormatLang = fmt != null || langRaw != null

                val plain = try { decryptEnc(rawEnc) } catch (e: Exception) { logE("decrypt: ${e.message}"); continue }
                if (plain.isBlank() || !seen.add(plain)) continue

                // الترجمة حقيقتها: كائن يجسّد format/language (بلا type/source)،
                // أو plain يحمل mime_type=text_plain (.srt/.vtt).
                val isSub = hasFormatLang ||
                    plain.lowercase().contains("mime_type=text_plain") ||
                    plain.trim().endsWith(".srt")

                if (isSub) {
                    val lang = langRaw?.substringBefore('_')?.substringBefore('-')?.takeIf { it.isNotBlank() } ?: "ar"
                    try {
                        subtitleCallback(newSubtitleFile(subLangLabel(lang), plain))
                        sentSub = true
                    } catch (e: Exception) { logE("subtitle: ${e.message}") }
                    continue
                }

                video++
                val label = if (video <= 1) source else "$source · بديل"
                val lower = plain.lowercase()
                val extType = when {
                    lower.contains(".mpd") || lower.contains("application/dash+xml") -> ExtractorLinkType.DASH
                    lower.contains(".m3u8") || lower.contains("application/vnd.apple.mpegurl") -> ExtractorLinkType.M3U8
                    else -> ExtractorLinkType.VIDEO
                }
                val mime = Regex("""mime_type=([^&]+)""").find(plain)?.groupValues?.get(1)
                val declared = when {
                    extType == ExtractorLinkType.M3U8 || extType == ExtractorLinkType.DASH -> null
                    extType == ExtractorLinkType.VIDEO && mime != null && mime.contains("mp4") -> "MP4"
                    else -> declaredFromType(typeRaw)
                }
                val tagged = FormatTag.tagged(label, plain, extType, declared)
                collected.add(newExtractorLink(name, tagged, plain, extType) {
                    this.headers = reqHeaders()
                })
            }

            // رؤية: إن فُكّ فيديوٌ واحد على الأقل
            if (collected.isNotEmpty()) {
                collected.forEach { callback(it) }
                logD("DramaTip.loadLinks $source ep=$ep video=${collected.size} sub=$sentSub")
                return true
            }
            logE("DramaTip.loadLinks $source ep=$ep decrypted nothing")
            false
        } catch (e: Exception) { logE("loadLinks: ${e.message}"); false }
    }
}