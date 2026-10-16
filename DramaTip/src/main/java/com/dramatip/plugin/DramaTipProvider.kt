package com.dramatip.plugin

import cloudstreamshared.FormatTag
import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TIP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
private const val TIP_MAIN = "https://dramatip.net/ar"
private const val TIP_HOST = "https://dramatip.net"

// نفس وكيل اللاعب المتنقّل — قِيس أنّ مضيفي التشغيل يميّزون به.
private const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 10)"

// سقف قراءة جسم الفحص: قائمة تشغيل HLS كاملة تقع دونه بمراتب، ويمنع تنزيل VOD ضخم.
private const val READ_CAP = 524_288

// منصّات الموقع من /ar/sources (slug → الاسم كما يعرضه الموقع). ٢٣ منصة.
// ثابت على مستوى الملف كي تشاركه الإعدادات (مفتاح إظهار/إخفاء لكل منصّة).
internal val DRAMA_TIP_PLATFORMS: List<Pair<String, String>> = listOf(
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
    private val platformRows = DRAMA_TIP_PLATFORMS

    // الصفوف الرئيسية بترتيب العرض: «الأحدث» و«الأكثر مشاهدة» قبل أسماء المنصّات.
    // مفاتيح الصفوف (تُمرَّر في request.data عند البث):
    //   latest  → /ar/category/newly-added
    //   popular → /ar/category/populer
    //   <slug>  → /ar/source/<slug>
    //
    // كل منصّة تُعرض/تُخفى بمفتاحها المستقل dt_platform_<slug> (الافتراضي ظاهر).
    private fun homeRows(): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        if (prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_LATEST, true) != false)
            rows.add("latest" to "الأحدث")
        if (prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_POPULAR, true) != false)
            rows.add("popular" to "الأكثر مشاهدة")
        if (prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_PLATFORMS, true) != false) {
            for ((slug, label) in platformRows) {
                if (prefs?.getBoolean(DramaTipSettingsBottomSheet.platformKey(slug), true) != false)
                    rows.add(slug to label)
            }
        }
        return rows
    }

    override val mainPage = mainPageOf(*homeRows().map { (k, l) -> k to l }.toTypedArray())

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

    /**
     * يجلب **نصّ** ملفّ الترجمة من رابطها. لا يُعاد المحاولة ولا يُطلق استثناءً:
     * ترجمةٌ تعذّرت لا يجوز أن تُفقد الحلقة روابطها.
     *
     * ولماذا لا نسلم الرابط نفسه للمشغّل: قِيس على MoboReels أن رابط الموقع
     * ‎…/api/v1/series/{id}/subtitles/{n}?format=srt‎ **بلا امتداد** — والمشغّل
     * يميّز مسار الترجمة بالامتداد ونوع المحتوى، وهذا ما يجعل الخادم المحلي
     * يخدمها موسومةً `text/vtt` و`.vtt`.
     */
    private suspend fun fetchSubtitleText(url: String): String? = try {
        app.get(
            url,
            referer = platformReferer(url),
            headers = mapOf(
                "User-Agent" to TIP_UA,
                "Accept" to "*/*",
                "Accept-Language" to "ar,en;q=0.8"
            ),
            timeout = 20_000L
        ).text.takeIf { it.isNotBlank() }
    } catch (e: Exception) { logE("subtitle $url: ${e.message}"); null }

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

    // كل mainEntity يحمل itemListElement (بطاقات كتالوج)؛ وقد تحمل العقدة
    // نفسُها itemListElement مباشرةً (كما في تصنيفات الموقع) — نأخذهما معًا.
    private fun collections(html: String): List<org.json.JSONObject> {
        val out = mutableListOf<org.json.JSONObject>()
        for (node in graphNodes(jsonLdDoc(html))) {
            val me = node.optJSONObject("mainEntity")
            if (me != null && me.has("itemListElement")) out.add(me)
            else if (node.has("itemListElement")) out.add(node)
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
            val slug = request.data.takeIf { it.isNotBlank() } ?: return null
            // «الأحدث» و«الأكثر مشاهدة» من تصنيفات الموقع؛ غيرها = مصدر/منصّة.
            val url = when (slug) {
                "latest" -> "$TIP_MAIN/category/newly-added"
                "popular" -> "$TIP_MAIN/category/populer"
                else -> "$TIP_MAIN/source/${escapeSlug(slug)}"
            }
            val h = fetchText(url) ?: return null
            val items = parseCollection(h, java.util.HashSet())
            newHomePageResponse(request.name, items)
        } catch (e: Exception) { null }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val seen = java.util.HashSet<String>()
            val allItems = mutableListOf<SearchResponse>()

            // 1) البحث العام (كما كان) — يعيد 24 نتيجة
            val general = fetchText("$TIP_MAIN/series?q=$q")
            if (general != null) {
                val items = parseCollection(general, seen)
                allItems.addAll(items)
            }

            // 2) البحث في كل منصّة على حدة — بعضها لا يظهر في البحث العام
            // ننفّذ الطلبات بالتوازي لتقليل المهلة الكلية
            val platformSlugs = platformRows.map { it.first }
            val results = coroutineScope {
                platformSlugs.map { slug ->
                    async(Dispatchers.IO) {
                        val url = "$TIP_MAIN/source/${escapeSlug(slug)}?q=$q"
                        val h = fetchText(url)
                        if (h != null) parseCollection(h, seen) else emptyList()
                    }
                }.awaitAll()
            }
            for (items in results) allItems.addAll(items)

            if (allItems.isEmpty()) null else allItems
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

    // مرجع المنصّة من الرابط: آخر نطاقين (مثال: shorttv.live، netshort.com).
    // للبروكسي /e/m/ أو /e/s/ (نارتو) نستخدم dramatip.net لأن اللاعب يُمرّر من هناك.
    private fun platformReferer(url: String): String {
        val isNartoProxy = url.contains("/e/m/") || url.contains("/e/s/")
        if (isNartoProxy) return TIP_MAIN
        val host = Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1)
        if (host == null) return TIP_MAIN
        val parts = host.split('.')
        val root = if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        return "https://$root/"
    }

    private fun decryptEnc(enc: String): String {
        val b = android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)
        val spec = javax.crypto.spec.GCMParameterSpec(128, b, 0, 12)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), spec)
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }

    // ---------- روابط التشغيل ----------
    // أنواع الموقع التي تعني HLS صراحةً (قِيست على ٢٣ منصّة):
    // hls، hls-enc (صيغة joyreels/shorttv)، goodshort، dramawave، storyreel.
    // لماذا نحتاجها: GoodShort تبثّ HLS من مسارٍ بلا ‎.m3u8
    // (…/hls/61502918?q=720p) — قِيس 200 application/vnd.apple.mpegurl و‎#EXTM3U —
    // فتصنّفه قراءةُ الامتداد وحدها mp4 خطأً، فيحاول اللاعب تفكيكه كملفٍ تدريجيّ
    // ويفشل. فيتقدّم نوعُ الموقع ويُصلح الوسم والنوعيّ معًا.
    private val hlsTypes = setOf("hls", "hls-enc", "goodshort", "dramawave", "storyreel")

    // ★ مفتاح العطل: الصفحة تُسلّم وصفةً مُخبَّأة. قِيس 2026-10-12 على DramaBite أنّ
    // الوصفة نفسها في HTML تحمل رمزًا عمره 8 ساعات (wsTime -28951s) فيردّ الـCDN
    // 403، بينما /api/episode-source/{book}/{serial}?lang=ar&refresh=1 يعطي الحلقة
    // نفسها رمزًا عمره 5 ثوانٍ فيردّ 200 — والفرق بين الطلبين سِتّة أحرف.
    // وصفة الـAPI لا تفقد شيئًا: قِيس أنّها تحمل chain وencByQuality وسطر الترجمة
    // كما في الصفحة (shorttv: ٤٨٠/٧٢٠/١٠٨٠؛ petadrama: ٥٤٠/٧٢٠/١٠٨٠ + ترجمة ar).
    private val tipApi = "$TIP_HOST/api/episode-source"

    // ★ 2026-10-14. الوصفة تُسلَّم كـ {"source":…,"chain":[{"enc":…,"type":…,
    // "source":…,"encByQuality":{…}},{"enc":…,…}], "subtitle":null|{…}}.
    //
    // الكود القديم «plainFromDescriptor» جمع فقط حقول القيمة النصية على عمقٍ
    // ثابت (depth==2) — أي فقط داخل كائن subtitle. فعندما يكون subtitle = null
    // (PineDrama، NetShort، FlickReels، …) يُنتج نصًّا فارغًا، و parseChain()
    // يعود به []، فيُظهر «لا روابط» حتى لو أن الوصفة حاملة chain كاملة.
    //
    // والحلّ بسيط: لا نُفلطح الـJSON، نُعيد له هيكله كاملاً (كما كان يفعل
    // pageFlight) بدل جمع الحقول. هذا يُطابق parseChain()/sourceName()/
    // objectAfterKey("subtitle") التي كلّها تعمل على كائن sourceFirst.
    private fun plainFromDescriptor(json: String): String {
        // json = محتوى descriptor (Object). نُلفّه بـ "sourceFirst" كي يجده
        // parseChain()/sourceName()/objectAfterKey("subtitle") كما يجدونه في
        // pageFlight. الـJSON الأصلي يبقى كما هو — لا تفكيك.
        return "\"sourceFirst\":$json"
    }

    /**
     * كل الروابط المُفكَّكة (بالصيغة التي يفهمها كود الاستخراج القائم: حقول
     * ‎enc/qال‎ مفصولة بـNUL) من وصفة الـAPI — أو null إن فشل الطلب.
     *
     * طلبٌ منفصل ومعزول تمامًا عن الوصفة الأساسية: أيّ فشل هنا يعيد null فيسقط
     * الكود على سلوكه القديم، ولا تكون «إعادة الجلب» سببًا في فقدان روابط.
     */
    private suspend fun apiLinks(bookId: String, serial: Int): String? {
        val url = "$tipApi/$bookId/$serial?lang=ar&refresh=1"
        var lastWhy = "-"
        for (attempt in 1..3) {
            try {
                val res = app.get(url, referer = TIP_MAIN, headers = reqHeaders(), timeout = 20_000L)
                if (res.code in 200..299) {
                    val desc = org.json.JSONObject(res.text).optJSONObject("descriptor") ?: return null
                    return plainFromDescriptor(desc.toString())
                }
                // 403 هنا غالبًا حدُّ معدّل لا حكمٌ على الوصفة: {error:forbidden}
                lastWhy = "HTTP ${res.code}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastWhy = e.javaClass.simpleName
            }
            android.util.Log.e("DramaTip", "episode-source attempt=$attempt/3 ($lastWhy) book=$bookId ser=$serial")
            if (attempt < 3) delay(700L * attempt)
        }
        android.util.Log.e("DramaTip", "episode-source gave up ($lastWhy) book=$bookId ser=$serial")
        return null
    }

    private val encRe = Regex(""""enc":"([A-Za-z0-9+/=]+)"""")
    private val typeRe = Regex(""""type":\s*"([^"]+)"""")
    private val langRe = Regex(""""language":\s*"([^"]+)"""")
    private val qualRe = Regex(""""(\d{3,4})":"([A-Za-z0-9+/=]+)"""")

    /**
     * (نوع الرابط كما يفتحه اللاعب، الصيغة المُعلنة للوسم) من الرابط وإعلان الموقع
     * والقِيَم المقيسة (نوع المحتوى والجسم إن قُرئ).
     *
     * ترتيب الحجّة = ترتيب قوّة الدليل: الجسم المقيس (‎#EXTM3U‎) ثم نوع المحتوى
     * المقيس ثم امتداد الرابط ثم إعلان الموقع. هذا هو ما يحسم الحالات المتضاربة:
     * MoboReels تُعلن type=mp4 ورابطها ينتهي ‎.m3u8 (فيفوز الامتداد)، وGoodShort
     * لا امتداد لها إطلاقًا (فيفوز نوع المحتوى/الجسم).
     */
    private fun classify(
        url: String,
        type: String?,
        contentType: String,
        body: String?,
    ): Pair<ExtractorLinkType, String?> {
        val u = url.lowercase()
        val ct = contentType.lowercase()
        val t = type?.lowercase()
        if (ct.contains("dash") || u.contains(".mpd") || u.contains("dash+xml")) return ExtractorLinkType.DASH to null
        // قائمة تشغيل مقيسة — أقوى دليل، ويسبق كل إعلان.
        if (ct.contains("mpegurl") || body?.contains("#EXTM3U") == true) return ExtractorLinkType.M3U8 to null
        if (u.contains(".m3u8")) return ExtractorLinkType.M3U8 to null
        // قُرئ جسمٌ فعليّ وليس قائمة تشغيل ⇒ ليس HLS مهما أعلن الموقع
        // (تفاديًا لبثّ mp4 بنوع M3U8 فيفشل اللاعب).
        if (!body.isNullOrEmpty()) return ExtractorLinkType.VIDEO to (if (t == "mp4") "MP4" else null)
        if (t != null && t in hlsTypes) return ExtractorLinkType.M3U8 to null
        if (t == "mp4") return ExtractorLinkType.VIDEO to "MP4"
        return ExtractorLinkType.VIDEO to null
    }

    // ---------- قراءة بنية sourceFirst ----------
    // عنصر chain واحد: نوعه + رابط الفيديو + جوداته (encByQuality).
    private class ChainEntry(val type: String?, val enc: String?, val qualities: List<Pair<String, String>>)

    /**
     * نصّ كائن/مصفوفة متوازن الأقواس بدءًا من أول فتحة في/بعد [from]، واعٍ بالنصوص
     * (فتُهمَل الأقواس داخل علامات التنصيص). نستعمله بدل تتبّع المواضع كما كان
     * الكود يفعل: المواضع تفترض ترتيبًا لمفتاحٍ ما، والكائن المتوازن لا يفترض شيئًا.
     */
    private fun balanced(s: String, from: Int, open: Char, close: Char): String? {
        val i = s.indexOf(open, from)
        if (i < 0) return null
        var depth = 0
        var j = i
        var inStr = false
        var esc = false
        while (j < s.length) {
            val c = s[j]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return s.substring(i, j + 1)
                }
            }
            j++
        }
        return null
    }

    /**
     * الكائن بعد مفتاح ‎"key":‎ — ويتحقّق أنّ أول حرف فعّال «{». هذا التحقّق هو
     * الفرق بين قراءة القيمة والانزلاق إلى أوّل كائنٍ لاحقٍ في الصفحة كلّها:
     * ‎"subtitle":null‎ كان سيُطابق أوّل «{» بعده (أي كائنًا لا علاقة له).
     */
    private fun objectAfterKey(s: String, key: String, from: Int = 0): String? {
        val k = s.indexOf(key, from)
        if (k < 0) return null
        var i = k + key.length
        while (i < s.length && (s[i] == ':' || s[i] == ' ' || s[i] == '\t')) i++
        if (i >= s.length || s[i] != '{') return null
        return balanced(s, i, '{', '}')
    }

    /** عناصر chain[] بأسمائها وأنواعها وجوداتها. لا يُلمس nextSourceFirst هنا إطلاقًا. */
    private fun parseChain(f: String): List<ChainEntry> {
        val sf = f.indexOf("\"sourceFirst\"")
        if (sf < 0) return emptyList()
        val ci = f.indexOf("\"chain\":[", sf)
        if (ci < 0) return emptyList()
        val arr = balanced(f, ci, '[', ']') ?: return emptyList()
        val out = mutableListOf<ChainEntry>()
        var pos = 0
        while (true) {
            val k = arr.indexOf('{', pos)
            if (k < 0) break
            val obj = balanced(arr, k, '{', '}') ?: break
            pos = k + obj.length
            val qs = mutableListOf<Pair<String, String>>()
            objectAfterKey(obj, "\"encByQuality\"")?.let { qo ->
                for (m in qualRe.findAll(qo)) qs.add(m.groupValues[1] to m.groupValues[2])
            }
            out.add(ChainEntry(typeRe.find(obj)?.groupValues?.get(1), encRe.find(obj)?.groupValues?.get(1), qs))
        }
        return out
    }

    private fun sourceName(f: String): String {
        val obj = objectAfterKey(f, "\"sourceFirst\"") ?: return name
        val m = Regex(""""source":\s*"([^"]*)"""").find(obj)
        return m?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: name
    }

    /**
     * كل كائنات الترجمة في وصفةٍ ما — بالشكلين:
     *  - كائن  `"subtitle":{"enc":…,"format":"srt","language":"ar"}` (MoboReels)
     *  - مصفوفة `"subtitle":[{enc,language},{enc,language}]` (منصّاتٌ متعدّدة اللغات)
     *
     * ولا نستعمل `objectAfterKey` هنا لأنّها تشترط أن يلي المفتاحَ «{» مباشرةً،
     * فيسقط الشكل المصفوفي كاملًا بلا سطر سجلٍّ واحد — وهو أحد أسباب اختفاء
     * الترجمة. والنصّ لا يُفكّ هنا، فالقرار في موضع البثّ.
     */
    private fun subtitleObjects(s: String): List<String> {
        val k = s.indexOf("\"subtitle\"")
        if (k < 0) return emptyList()
        var i = k + "\"subtitle\"".length
        while (i < s.length && (s[i] == ':' || s[i] == ' ' || s[i] == '\t')) i++
        if (i >= s.length) return emptyList()
        return when (s[i]) {
            '{' -> listOfNotNull(balanced(s, i, '{', '}'))
            '[' -> {
                val arr = balanced(s, i, '[', ']') ?: return emptyList()
                val out = mutableListOf<String>()
                var pos = 0
                while (true) {
                    val b = arr.indexOf('{', pos)
                    if (b < 0) break
                    val obj = balanced(arr, b, '{', '}') ?: break
                    pos = b + obj.length
                    out.add(obj)
                }
                out
            }
            else -> emptyList() // null أو نصّ — لا ترجمة
        }
    }

    /**
     * نتيجة فحصٍ واحد: حيّ؟ + ما قِيس فعلًا (نوع المحتوى، وجسم محدود لعرضه) + السبب
     * + هل هي **حكمٌ** (علمٌ) أم **تعذّر معرفة**؟
     *
     * التمييز هو ما يمنع تكرار علّة narto الموثّقة: كود حالةٍ (403/404) أو علامةٌ في
     * جسم البروكسي حكمٌ بأنّ الرابط ميت، أما انقطاع DNS أو مهلة الشبكة فلا يقولان
     * شيئًا عن الرابط — وإسقاطهما كان يجعل «لا روابط» نتيجةً لخللٍ عابر. وشهادة TLS
     * الفاسدة استثناء: البايتات موجودة لكن كل عميلٍ صارم يرفضها، فهي حكم.
     */
    private class Verdict(
        val alive: Boolean,
        val contentType: String,
        val body: String?,
        val why: String,
        val certain: Boolean,
    )

    /**
     * مسبار الحيوية — بلا HEAD وبلا Range، لأنّ اللاعب لا يرسل شيئًا منهما.
     *
     * قِيس في narto-shared أنّ إضافة Range قلبت الحكم على joyreels (403 معها،
     * 410 بدونها)، وأنّ بروكسي ‎/e/m/‎ يردّ 200 حتى ولو كان المصدر خلفه منتهيًا؛
     * فالفحص هنا يقلّد اللاعب حرفيًّا، ويقرأ جسم البروكسي بحثًا عن العلامة بدل
     * الوثوق بكود الحالة.
     *
     * ويُقرأ الجسم أيضًا حين لا يحسم نوعُ المحتوى أنه فيديو تدريجيّ — فقائمة
     * التشغيل تُعرَف من ‎#EXTM3U‎ وهذا ما يصنّف المسارات بلا امتداد (GoodShort).
     * والقراءة مسقوفة (‎[READ_CAP]‎ بايت) فلا يُنزَّل MP4 كامل إن صادف VOD.
     *
     * المرجع (Referer) يُحسب لكل رابط من خلال `platformReferer(url)` —
     * shorttv يحتاج referer shorttv.live، netshort يحتاج netshort.com، إلخ.
     */
    private inner class Probe {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Verdict>()

        suspend fun check(u: String): Verdict {
            cache[u]?.let { return it }
            val r = withContext(Dispatchers.IO) { one(u) }
            cache[u] = r
            return r
        }

        /**
         * إعادة فحص رابطٍ سبق أن سقط — لتمييز السقطة العابرة من الموت الدائم.
         * يُعاد الفحص فعلًا (لا يُقرأ من الذاكرة) حتى ثلاث مرّات، لأنّ مضيفات
         * dramatip كثيرة العطل العابر (قِيس على بروكسي narto ردُّ 403 ثم 200
         * للطلب نفسه)، وسلسلةٌ من عنصرٍ واحد تسقط بسقطةٍ واحدة. ولا يعود الحكم
         * الجديد أسوأ من الأول: المهلة أو انقطاع الاتصال لا يُثبتان موتًا فيُحفظ
         * الأول، والحكم القاطع يبقى قاطعًا.
         */
        suspend fun recheck(u: String): Verdict? {
            val prev = cache[u]
            var best = prev
            for (attempt in 1..3) {
                val fresh = withContext(Dispatchers.IO) { one(u) }
                if (fresh.alive) { cache[u] = fresh; return fresh }
                if (fresh.certain) best = fresh
                if (attempt < 3) delay(500L * attempt)
            }
            val result = best
            if (result != null) cache[u] = result
            return result
        }

        suspend fun alive(u: String): Boolean = check(u).alive

        private fun one(u: String): Verdict {
            val proxy = u.contains("/e/m/") || u.contains("/e/s/")
            // رابطٌ لا يُبنى ليس حكمًا بأنه ميت (قد يكون ترميزًا غريبًا فقط) — نُبقيه
            // بلا فحص، كما تفعل قاعدة «الحكم مقابل لا نعرف»: لا نُسقط ما لم نُثبت موته.
            val uri = try { URL(u) } catch (e: Exception) { return Verdict(true, "", null, "unparseable", false) }
            var conn: HttpURLConnection? = null
            return try {
                val c = uri.openConnection() as HttpURLConnection
                conn = c
                c.requestMethod = "GET"
                c.setRequestProperty("Referer", platformReferer(u))
                c.setRequestProperty("User-Agent", ANDROID_UA)
                // Range على البروكسي فقط (لبثّ جسمٍ صغيرٍ محدود لبضعٍ من الكيلوبايتات).
                if (proxy) c.setRequestProperty("Range", "bytes=0-65535")
                c.instanceFollowRedirects = true
                c.connectTimeout = 7000
                c.readTimeout = 7000
                val code = c.responseCode
                if (code !in 200..399) return Verdict(false, "", null, "HTTP $code", true)
                val ct = c.contentType.orEmpty()
                val readBody = proxy || !ct.lowercase().contains("video/")
                val body = if (readBody) readLimited(c.inputStream, READ_CAP) else null
                if (proxy) {
                    val b = body.orEmpty()
                    if (b.contains("link expired") || b.contains("invalid token") || b.contains("invalid or expired")) {
                        return Verdict(false, ct, null, "proxy body: ${b.take(48)}", true)
                    }
                }
                // ★ 2026-10-16. رابطٌ يدّعي HLS (امتداد ‎.m3u8‎ أو نوع محتوى mpegurl)
                // وقُرئ جسمه فوجد **ليس** قائمة تشغيل ⇒ هو صفحة خطأ مُتنكّرة بزي
                // قائمة تشغيل. قِيس على DramaBox: مسار ‎/v1/dramabox/…m3u8‎ يردّ
                // 200 بقالب HTML بمقاس 27KB، فيُصنَّف M3U8 بالامتداد ويُبثّ، فيردّ
                // اللاعب «خطأ 3002» (بايتات ليست وسائط). الحكم هنا يمنعه من الأصل.
                val claimsHls = u.contains(".m3u8") || ct.lowercase().contains("mpegurl")
                if (claimsHls && !body.isNullOrEmpty() && !body.contains("#EXTM3U")) {
                    return Verdict(false, ct, body, "not a playlist: ${body.take(48)}", true)
                }
                Verdict(true, ct, body, "", true)
            } catch (e: Exception) {
                // شهادة TLS مرفوضة حكمٌ: البايتات موجودة لكنّ كلّ عميلٍ صارم يرفضها.
                // ★ 2026-10-16. ومضيفٌ لا يُحلّ اسمه أصلًا حكمٌ كذلك: لا عميلَ يبلغه.
                // قِيس على DotDrama أنّ مضيفه videotv.vividshort.com لم يُحلّ قطّ؛
                // وحين كان ‎UnknownHostException‎ يُعدّ «تعذّر معرفة» بُثّ الرابط
                // الميت فاختار اللاعب التشغيل وأظهر «خطأ 2001» — وهو عطلُ شبكةٍ في
                // ظاهر الأمر لا رابطٌ ميت. نُعيد الحلّ مرّةً صريحةً قبل الحكم كي لا
                // يُسقط سباقُ DNS عابرٌ رابطًا حيًّا. وما عدا ذلك (مهلة، انقطاع
                // اتصال) لا يقول شيئًا عن الرابط — يُبثّ بلا حكمٍ في آخر القائمة.
                var certain = e is javax.net.ssl.SSLHandshakeException
                if (e is java.net.UnknownHostException) {
                    val stillDead = try { java.net.InetAddress.getByName(uri.host); false }
                    catch (_: Exception) { true }
                    certain = stillDead
                }
                Verdict(
                    false, "", null,
                    "${e.javaClass.simpleName}: ${e.message?.take(48) ?: "-"}",
                    certain,
                )
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

        // قراءة مسقوفة: تكفي لقائمة تشغيلٍ كاملة، وتمنع تنزيل ملفّ VOD ضخمٍ إن أخطأ التصنيف.
        private fun readLimited(ins: java.io.InputStream?, max: Int): String = try {
            if (ins == null) "" else ins.bufferedReader().use { rd ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < max) {
                    val n = rd.read(buf, 0, minOf(buf.size, max - sb.length))
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } catch (e: Exception) { "" }
    }

    /**
     * عنصر chain واحد ← مرشّحات (اسم، رابط، نوع، دقّة). الأساس أولًا ثم جوداته،
     * وكل رابط مذكور مرّة واحدة بأعلى دقّة معلنة له (MoreShort: 480/720/1080
     * لملفٍ واحد ← يُبثّ مرّة).
     */
    private fun chainCands(e: ChainEntry, source: String): List<Triple<String, String, String?>> {
        val out = mutableListOf<Triple<String, String, String?>>()
        val byUrl = LinkedHashMap<String, String>()
        for ((q, qenc) in e.qualities) {
            val qu = decryptOrSkip(qenc) ?: continue
            val prev = byUrl[qu]
            if (prev == null || (q.toIntOrNull() ?: 0) > (prev.toIntOrNull() ?: 0)) byUrl[qu] = q
        }
        e.enc?.let { decryptOrSkip(it) }?.let { main ->
            val q = byUrl[main]
            out.add(Triple(if (q != null) "$source · ${q}p" else source, main, e.type))
        }
        for ((qu, q) in byUrl) out.add(Triple("$source · ${q}p", qu, e.type))
        return out
    }

    /** اسم دقّة من سطر EXT-X-STREAM-INF: min(العرض، الارتفاع) — الفيديو القصير عموديّ. */
    private fun resolutionLabel(inf: String): String? {
        val r = Regex("""RESOLUTION=(\d{3,4})x(\d{3,4})""").find(inf)?.groupValues ?: return null
        val w = r[1].toIntOrNull() ?: 0
        val h = r[2].toIntOrNull() ?: 0
        val n = (minOf(w, h)).takeIf { it > 0 } ?: h
        return if (n > 0) "${n}p" else null
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
            val source = sourceName(f)

            // ★ إعادة الجلب: وصفة الصفحة مُخبَّأة وقد تحمل رموزًا منتهية (انظر TIP_API).
            // الـAPI يُسأل عن الحلقة نفسها من كتابها ورقمها التسلسلي، وتُدمج الوصفتان:
            // الوصفة المُنعشة أولًا لأنها الأحدث، ثم وصفة الصفحة. كل رابط يُبثّ مرّة
            // واحدة (emitted أدناه)، فالتكرار بين الوصفتين لا يُضاعف السيرفرات.
            // bookId قد يكون أبجديًا رقميًا مع شرطات (bilitv: "asrr-lzwj-lkhfy-at")
            val bookId = Regex(""""bookId"\s*:\s*"([^"]+)"""").find(f)?.groupValues?.get(1)
            val firstSerial = Regex(""""firstSerial"\s*:\s*(-?\d+)""").find(f)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val apiDesc = if (bookId != null) apiLinks(bookId, ep + firstSerial - 1) else null
            if (apiDesc != null) logD("DramaTip: episode-source OK book=$bookId ser=${ep + firstSerial - 1}")

            val collected = mutableListOf<ExtractorLink>()
            // ما اجتاز الفحص، وما تعذّر الحكم عليه (انقطاع عابر لا يُسقط رابطًا) — يُبثّ
            // هذا بعد ذاك، فيبقى اختيار اللاعب الافتراضي على رابطٍ مُثبتٍ حيّ.
            val deferred = mutableListOf<ExtractorLink>()
            val emitted = LinkedHashSet<String>()
            val probe = Probe()
            val showSubs = prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_SHOW_SUBTITLES, true) ?: true
            var sentSub = 0

            // ١) المرشّحون من chain[]: رابط الأساس + جوداته (encByQuality).
            //
            // لا يُقرأ nextSourceFirst إطلاقًا. قِيس 2026-10-12 على أربع منصّات
            // بحلقتين متتاليتين أنّ nextSourceFirst في الحلقة N يساوي chain في
            // الحلقة N+1 حرفيًّا (GoodShort 61502918←61502919، Playlet /1/←/2/،
            // BibiShort /1/←/2/، PineDrama): أي أنه «الحلقة التالية» لا «سيرفرًا
            // بديلًا». بثّه موسومًا «· بديل» كان يُشغّل حلقةً خاطئة عند اختياره.
            class Cand(val label: String, val url: String, val type: String?, val qLabel: String?)
            fun candsOf(desc: String): List<Cand> {
                val out = mutableListOf<Cand>()
                val srcName = sourceName(desc)
                for (e in parseChain(desc)) {
                    for ((label, u, ty) in chainCands(e, srcName)) {
                        val q = Regex("""·\s*(\d{3,4})p$""").find(label)?.groupValues?.get(1)
                        out.add(Cand(label, u, ty, q?.let { "${it}p" }))
                    }
                }
                return out
            }

            // ★ 2026-10-16. الأولوية للوصفة المُنعشة وحدها متى حملت chain صالحة،
            // ولا تُدمج بوصفة الصفحة (كما كانت تفعل listOfNotNull). السبب: وصفة
            // الصفحة مُخبَّأة وقد تعود برموزٍ **حيّة الشكل ميتة المفعول** (توكنٌ
            // مضى أوانه أو مضيفٌ تغيّر) — فلا يكشفها فحصٌ ولا يُسقطها، وتُبثّ أوّلًا
            // لأنّ ترتيب الإسقاط هو ترتيب المرشّحين، فيختارها اللاعب الافتراضيًّا
            // ويفشل التشغيل بلا سببٍ ظاهر. لكنّها تبقى **خطّ نجاة**: قِيس على BiliTV
            // أن مضيف الأنعش وحدَه يسقط بـ403 عابر فتبقى الحلقة بلا روابط، فنجعل
            // روابط الصفحة مرشّحاتٍ أخيرةً بعد الأنعش — تُبثّ في الآخر فلا تسرق
            // الافتراضيَّ من رابطٍ مُثبتٍ حيّ.
            val seenCands = HashSet<String>()
            val freshCands = apiDesc?.let { candsOf(it) }.orEmpty()
            val pageCands = candsOf(f)
            val cands = if (freshCands.isNotEmpty()) freshCands + pageCands else pageCands
            val uniq = cands.filter { seenCands.add(it.url) }
            if (uniq.isEmpty()) {
                logE("DramaTip.loadLinks $source ep=$ep decrypted nothing")
                return false
            }

            // ٢) فحص المرشّحين كلّهم في وقتٍ واحد: الرحلات مستقلّة، فالتسلسل يكلّف
            //    مهلةً كاملة لكل رابط ميت (حتى ٧ث للواحد) ويراها المستخدم تأخّرًا.
            //    وإذا أطفأ المستخدم «فحص الروابط» نُبثّ بلا أي طلب شبكة (وضع سريع)
            //    فلا يُقاس نوع المحتوى ولا تُفرَّع الجودات — لكن التصنيف يبقى
            //    صحيحًا لأنّه يسقط إلى الرابط وإعلان الموقع.
            val probing = prefs?.getBoolean(DramaTipSettingsBottomSheet.KEY_PROBE_LINKS, true) != false
            // ★ 2026-10-16. إعادة فحص المُسقَط (حتى ٣ مرّات داخل recheck) قبل قبول
            // الإسقاط. مضيفات بثّ dramatip كثيرة العطل العابر (narto يردّ 403
            // لطلبٍ ويقبل التالي بالقيمة نفسها)، وسلسلةٌ من عنصرٍ واحد يسقط عنصرها
            // = «لا روابط» للحلقة كلّها. والإعادة تُحفظ بحيث لا تعود أسوأ من الأول:
            // المهلة أو انقطاع الاتصال لا يُثبتان شيئًا، والحكم القاطع يبقى قاطعًا.
            suspend fun probeAll(list: List<Cand>): List<Verdict?> = coroutineScope {
                list.map { c -> async(Dispatchers.IO) { if (probing) probe.check(c.url) else null } }.awaitAll()
            }
            var verdicts = probeAll(uniq)
            run {
                // تُعاد السقطات القابلة للعَرض وحدها — كود حالة أو علامة بروكسي أو
                // جسمٌ ليس قائمة تشغيل — لأنّها هي التي تُردّ عابرةً على مضيفات
                // dramatip. ولا تُعاد المهلة (٧ ثوانٍ لكل محاولة ولا تُثبت شيئًا)،
                // ولا المضيف الذي لا يُحلّ (الحلّ يُعاد صريحًا داخل one())، ولا
                // فشل شهادة TLS (قاطعٌ بالطبع).
                val toRetry = uniq.filterIndexed { i, _ ->
                    val v = if (probing) verdicts[i] else null
                    v != null && !v.alive && (v.why.startsWith("HTTP ") ||
                        v.why.startsWith("proxy body:") || v.why.startsWith("not a playlist:"))
                }
                if (toRetry.isEmpty()) return@run
                val again = coroutineScope {
                    toRetry.map { c -> async(Dispatchers.IO) { probe.recheck(c.url) } }.awaitAll()
                }
                val byUrl = toRetry.mapIndexed { i, c -> c.url to again[i] }.toMap()
                verdicts = uniq.mapIndexed { i, c -> byUrl[c.url] ?: verdicts[i] }
            }

            // ٣) بثّ الأحياء بترتيب الصفحة؛ وكل HLS حيّ يُفرَّع على جوداته الحقيقية.
            class Var(val label: String, val url: String, val qLabel: String?)
            val variants = mutableListOf<Var>()
            for ((i, c) in uniq.withIndex()) {
                val v = verdicts[i]
                // الإسقاط للرابط المُثبت موته فقط (كود حالةٍ أو علامةٌ في جسم البروكسي أو
                // شهادة TLS مرفوضة أو مضيفٌ لا يُحلّ بعد إعادة محاولةٍ صريحة). أمّا
                // تعذّر الاتصال (مهلة أو انقطاع) فلا يُسقط الرابط — يُبثّ في الآخر.
                if (v != null && !v.alive && v.certain) {
                    logD("DramaTip: drop dead ${c.label} ${c.url.take(70)} why=${v.why}")
                    continue
                }
                val cannotTell = v != null && !v.alive
                val why = v?.why
                if (cannotTell) logD("DramaTip: cannot-tell ${c.label} ${c.url.take(70)} why=$why")
                val (kind, declared) = classify(c.url, c.type, v?.contentType.orEmpty(), v?.body)
                if (!emitted.add(c.url)) continue
                addLink(if (cannotTell) deferred else collected, c.label, c.url, c.qLabel, kind, declared)
                if (cannotTell || kind != ExtractorLinkType.M3U8) continue
                val body = v?.body ?: continue
                val base = c.url.substringBeforeLast('/', c.url)
                var inf: String? = null
                for (raw in body.replace("\u0000", "").split('\n')) {
                    val l = raw.trim()
                    if (l.startsWith("#EXT-X-STREAM-INF:")) {
                        inf = l.removePrefix("#EXT-X-STREAM-INF:")
                    } else if (inf != null && l.isNotBlank() && !l.startsWith("#")) {
                        val q = resolutionLabel(inf)
                        val vu = if (l.startsWith("http")) l else "$base/$l"
                        variants.add(Var(c.label + (q?.let { " · $it" } ?: ""), vu, q))
                        inf = null
                    }
                }
            }

            // ٤) صيغ الـmaster: موجة ثانية بالتوازي أيضًا، ثم بثّها.
            if (variants.isNotEmpty()) {
                val ok = coroutineScope {
                    variants.map { v -> async(Dispatchers.IO) { probe.alive(v.url) } }.awaitAll()
                }
                for ((i, v) in variants.withIndex()) {
                    if (ok[i] && emitted.add(v.url)) addLink(collected, v.label, v.url, v.qLabel, ExtractorLinkType.M3U8, null)
                }
            }

            // ٥) الترجمة — تُقدَّم من **خادمٍ محليّ** لأنّ المشغّل يطلب رابطًا
            //    حقيقيًّا ولا يقبل نصًّا خامًّا ولا `data:` (وكان تسليم النصّ
            //    المفكوك بوصفه رابطًا هو سبب اختفاء الترجمة على **كلّ** المنصّات
            //    لا على MoboReels وحدها — انظر DramaTipSubServer).
            //
            //    والوصفة تحمل الترجمة على شكلين: كائنًا واحدًا
            //    `"subtitle":{"enc":…,"format":"srt","language":"ar"}` (MoboReels)،
            //    أو مصفوفة `"subtitle":[{…},{…}]` — فنجمع الشكلين. و`objectAfterKey`
            //    لا تصلح لهذا لأنّها تشترط «{» بعد المفتاح فيسقط الشكل المصفوفي.
            //
            //    ونكرّر على الوصفتين (الأنعش ثم الصفحة) ونتعقّب النصوص المُبثّة،
            //    فلا تتكرّر ترجمةٌ واحدة في القائمة حين تحمل الوصفتان الملفّ نفسه.
            if (showSubs) {
                // نجمع أولًا ثم نُبثّ: الوصفتان (الأنعش والصفحة) تحملان الترجمة
                // نفسها، فالدمج بالرابط يمنع جلبتين متطابقتين، والدمج بالنصّ
                // يمنع ظهور مسارٍ واحد مرّتين في قائمة المشغّل.
                val subSources = LinkedHashMap<String, String>()
                for (desc in listOfNotNull(apiDesc, f)) {
                    for (sobj in subtitleObjects(desc)) {
                        val enc = encRe.find(sobj)?.groupValues?.get(1) ?: continue
                        val value = decryptOrSkip(enc)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                        val lang = langRe.find(sobj)?.groupValues?.get(1)
                            ?.substringBefore('_')?.substringBefore('-')?.takeIf { it.isNotBlank() } ?: "ar"
                        subSources.putIfAbsent(value, lang)
                    }
                }
                val emittedText = HashSet<String>()
                for ((value, lang) in subSources) {
                    // القيمة **رابطٌ** على المنصّات المقيسة كلّها (MoboReels:
                    // ‎…/subtitles/1?format=srt‎، JoyReels: ‎….srt‎، BiliTV:
                    // بروكسي narto ‎/e/s/‎) — لا نصَّ SRT. وإن جاء نصٌّ خامًّا على
                    // منصّةٍ أخرى نخدمه كما هو فلا نُهدر الحالتين.
                    val text = if (value.startsWith("http")) {
                        fetchSubtitleText(value) ?: continue
                    } else value
                    if (!emittedText.add(text)) continue
                    val local = DramaTipSubServer.register(text) ?: continue
                    subtitleCallback(newSubtitleFile(subLangLabel(lang), local))
                    sentSub++
                }
            }

            // الترتيب حسب إعدادات المستخدم (افتراضي = ترتيب الصفحة كما هو).
            // غير المُحكوم يلحق بالمُثبت فيبقى الأول اختيارَ اللاعب الافتراضي.
            collected.addAll(deferred)
            val order = prefs?.getString(DramaTipSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
            val ordered = when (order) {
                "asc" -> collected.sortedBy { it.quality }
                "desc" -> collected.sortedByDescending { it.quality }
                else -> collected
            }

            if (ordered.isNotEmpty()) {
                ordered.forEach { callback(it) }
                logD("DramaTip.loadLinks $source ep=$ep video=${ordered.size} sub=$sentSub")
                return true
            }
            logE("DramaTip.loadLinks $source ep=$ep nothing survived probes")
            false
        } catch (e: Exception) { logE("loadLinks: ${e.message}"); false }
    }

    // يعطي plain النصّ المكشوف أو null عند تعذّر فكّه.
    private fun decryptOrSkip(enc: String): String? = try { decryptEnc(enc) } catch (e: Exception) { logE("decrypt: ${e.message}"); null }

    // يبثّ رابطًا اجتاز الفحص: يوسمه بالصيغة ويضبط دقّته في مُنتقي الجودات.
    // لاحظ اسم المعامل qLabel لا quality: داخل بناء newExtractorLink تُحلّ
    // `quality` إلى خاصية المستقبِل، فتُقرأ-تُكتب على نفسها (فخّ ظلّل اسمًا
    // حذّر منه narto-shared: "referer = referer").
    private suspend fun addLink(
        collected: MutableList<ExtractorLink>,
        label: String,
        url: String,
        qLabel: String?,
        kind: ExtractorLinkType,
        declared: String?,
    ) {
        val tagged = FormatTag.tagged(label, url, kind, declared)
        collected.add(newExtractorLink(name, tagged, url, kind) {
            this.headers = reqHeaders()
            if (!qLabel.isNullOrBlank()) {
                val q = getQualityFromName(qLabel)
                if (q > 0) this.quality = q
            }
        })
    }
}