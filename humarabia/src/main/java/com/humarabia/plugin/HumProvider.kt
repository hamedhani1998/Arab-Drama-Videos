package com.humarabia.plugin

import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

/**
 * هم العربية + عشق مرشد (Hum TV بالعربية) — قناتا يوتيوب تُعرضان معاً كمصدرٍ
 * واحد. يستخلص هذا الصف قوائم التشغيل (المسلسلات) من القناتين ويدمجها.
 *
 * لماذا قوائم التشغيل؟ نفس حجة [aryarabia]: تبويب «الفيديوهات» مختلط وترتيبه
 * زمني، واستعلام القناة يتجاهل نص البحث، وكل مسلسل منشور كقائمة تشغيل كاملة
 * (عشق مرشد 37 حلقة، مير آبرو 34، دروب العشق 21 …). صفحة «قوائم التشغيل»
 * تعرض القوائم كاملة في صفحة أو صفحتين.
 *
 * النقل: GET على صفحات HTML (صفحة قوائم القناة + صفحة `playlist?list=`) ونقرأ
 * `ytInitialData` منها، كما في [aryarabia]. الصفحة الثانية من قوائم القناة عبر
 * InnerTube POST إن لزم. لا نُعدد حلقات كل قائمة في الرئيسية (كل قائمة تُجلب
 * عند الفتح فقط).
 *
 * التشغيل: NewPipe `fetchPage()` ثم مانيفست DASH محلي (HumDashServer) — نفس
 * المسار المُثبت في aryarabia/ArabShortDrama. NewPipe نفسه لا يُضمَّن في الـcs3
 * ويقدّمه التطبيق وقت التشغيل.
 */
class HumProvider(
    private val prefs: SharedPreferences? = null
) : MainAPI() {

    /** خيار محرك التشغيل المختار في الإعدادات ("newpipe" الافتراضي). */
    private fun playbackMode(): String =
        prefs?.getString(HumSettingsBottomSheet.KEY_PLAYBACK_MODE, "newpipe") ?: "newpipe"

    /** عرض كل الجودات أم الأعلى فقط. */
    private fun qualityMode(): String =
        prefs?.getString(HumSettingsBottomSheet.KEY_MAX_QUALITY, "all") ?: "all"

    companion object {
        private const val TAG = "HumArabia"

        /** قناة «هم العربية» الرئيسية. */
        private const val CHANNEL_ID = "UCyA7992LhLeYSd7ynpiwpeQ"

        /** قناة «عشق مرشد Arab Hum TV» — تُدمج قوائمها ضمن نفس الصف. */
        private const val CHANNEL_ID_2 = "UCOYvEjxqq0sx5XmeQx9-UgA"

        /** صفحة تبويب «قوائم التشغيل» — GET on HTML نجلب منها ytInitialData. */
        private fun playlistsUrl(cid: String) = "https://www.youtube.com/channel/$cid/playlists"

        private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?key=$INNERTUBE_KEY&prettyPrint=false"
        private const val CLIENT_VERSION = "2.20260918.00.00"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val BOM = "﻿"

        /**
         * القوائم العامة غير المسلسلات (مقاطع قصيرة، أغاني، أغلفة، برومو عام…)
         * تُستبعد من صف المسلسلات. (إعلانات وتشويقات المسلسلات هنا لا وجود لها
         * في هذه القناتين — رابط مستقل كما في ARY.)
         */
        private val SKIP_RE = Regex(
            "Shorts|مقاطع|أغاني|Songs|Clips|Telefilms|Promo|أفضل اللحظات|أجمل اللحظات|" +
                "Latest|أحدث|أفلام",
            RegexOption.IGNORE_CASE
        )

        private val EP_NUM_RE = Regex("""حلقة\s*(\d+)""")
        private val FINALE_RE = Regex("""حلقة\s*(?:الأخيرة|الاخيرة|أخيرة|اخيرة)""")

        /** «الجزء الأول/الثاني/…» — عندما تُقسَّم حلقةٌ إلى أجزاء. */
        private val PART_RE = Regex(
            """الجزء\s*([وأ_]?)(ال)?(?:الأول|الاول|الثاني|الثانى|الثاني|ثاني|الثالث|الرابع|الخامس|الأخير|الاخير|النهائى|النهائي|\d+)""",
            RegexOption.IGNORE_CASE
        )
    }

    override var name = "هم العربية"
    override var mainUrl = "https://www.youtube.com/channel/$CHANNEL_ID"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "ar"

    // =============================== HTML / GET ===============================

    private fun ctx(): JSONObject = JSONObject().put(
        "client", JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "ar")
            .put("gl", "US")
    )

    private suspend fun fetchInitialData(url: String, isChannelTab: Boolean): JSONObject {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val res = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Accept-Language" to "ar",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
                val html = res.text
                val data = ytInitialData(html)
                if (data != null) return data
                last = IllegalStateException("no ytInitialData in ${html.length} bytes")
            } catch (e: Exception) {
                last = e
            }
            if (attempt < 2) delay(400L * (attempt + 1))
        }
        throw last ?: IllegalStateException("fetch $url failed")
    }

    /**
     * الصفحة الثانية من قوائم القناة لا تأتي عبر GET (معامل continuation يتجاهله
     * يوتيوب في HTML)، بل InnerTube POST فقط. نستخدمه للمتابعة حصراً، مع حارسٍ:
     * إن فشل لا نُسقط القوائم التي جمعناها أصلاً.
     */
    private suspend fun continuationPage(token: String): JSONObject? {
        val body = JSONObject()
            .put("context", ctx())
            .put("continuation", token)
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                val res = app.post(
                    BROWSE_URL,
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Content-Type" to "application/json",
                        "Accept-Language" to "ar"
                    ),
                    json = body
                )
                val text = res.text.trim()
                if (text.startsWith("{")) return JSONObject(text)
                last = IllegalStateException("non-JSON reply (${text.length} bytes)")
            } catch (e: Exception) {
                last = e
            }
            if (attempt < 1) delay(400L)
        }
        if (last != null) Log.w(TAG, "continuation page failed: ${last.message}")
        return null
    }

    private fun ytInitialData(html: String): JSONObject? {
        val markers = listOf(
            "var ytInitialData = ",
            "window[\"ytInitialData\"] = ",
            "\"ytInitialData\"] = ",
            "ytInitialData = "
        )
        for (m in markers) {
            val idx = html.indexOf(m)
            if (idx < 0) continue
            val start = idx + m.length
            if (start >= html.length || html[start] != '{') continue
            var depth = 0
            var i = start
            var inStr = false
            var esc = false
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
                                } catch (_: Exception) {
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

    private fun collect(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { out.add(it) }
                for (k in node.keys()) collect(node.opt(k), key, out)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
        }
    }

    private fun grab(node: JSONObject, key: String): List<JSONObject> =
        mutableListOf<JSONObject>().also { collect(node, key, it) }

    private fun continuationTokenOf(node: JSONObject): String? =
        grab(node, "continuationCommand")
            .firstNotNullOfOrNull { it.optString("token").ifBlank { null } }

    // ============================ lockupViewModel ============================

    private data class Lockup(
        val id: String,
        val type: String,
        val title: String,
        val thumb: String?,
        val count: Int = 0
    )

    private fun lockupTitle(l: JSONObject): String =
        l.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
            ?.optJSONObject("title")
            ?.optString("content", "")
            .orEmpty()

    private fun lockupThumb(l: JSONObject): String? {
        val root = l.optJSONObject("contentImage") ?: return null
        val tvs = grab(root, "thumbnailViewModel")
        for (tv in tvs) {
            val sources = tv.optJSONObject("image")?.optJSONArray("sources") ?: continue
            for (i in sources.length() - 1 downTo 0) {      // الأخير = الأعلى جودة
                val u = sources.optJSONObject(i)?.optString("url", "").orEmpty()
                if (u.isNotBlank()) return u
            }
        }
        return null
    }

    private fun lockupsOf(node: JSONObject): List<Lockup> =
        grab(node, "lockupViewModel").mapNotNull { l ->
            val id = l.optString("contentId", "").ifBlank { return@mapNotNull null }
            Lockup(
                id, l.optString("contentType", ""), clean(lockupTitle(l)),
                lockupThumb(l), lockupCount(l)
            )
        }

    private val COUNT_RE = Regex("""(\d[\d,]*)\s*(?:فيديو|حلقة)""")

    private fun lockupCount(l: JSONObject): Int {
        val root = l.optJSONObject("contentImage") ?: return 0
        for (badge in grab(root, "thumbnailBadgeViewModel")) {
            val t = badge.optString("text", "")
            COUNT_RE.find(t)?.let {
                return it.groupValues[1].replace(",", "").toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun clean(s: String): String =
        s.replace(BOM, "").replace("\\u0026", "&").trim()

    private fun episodeNumberOf(title: String): Int? =
        EP_NUM_RE.find(title)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * رقم الجزء داخل الحلقة («الجزء الأول/الثاني/…») عند تقسيم حلقةٍ لأجزاء.
     * القناة تقسم أحياناً الحلقة الأخيرة («الحلقة 31 الجزء الأول / الجزء
     * الثاني والاخير»)، فيجب أن يحمل كلّ جزءٍ رقمَ حلقاتٍ مختلفاً وإلا
     * تعارض الحلقتان برقمٍ واحد في التطبيق واختفى أحدهما. نعود 0 عند عدم
     * وجود جزء.
     */
    private fun partNumberOf(title: String): Int {
        val m = PART_RE.find(title) ?: return 0
        val w = keyOf(m.value)          // قبل التوحيد: «الجزء»
        return when {
            w.contains("اول") -> 1
            w.contains("ثان") || w.contains("ثانى") -> 2
            w.contains("ثال") || w.contains("ثالث") -> 3
            w.contains("رابع") -> 4
            w.contains("خامس") -> 5
            w.contains("اخير") -> 6      // «الجزء الأخير» بعد الأول
            else -> {
                // «الجزء 2»، «الجزء 02»
                Regex("""\d+""").find(m.value)?.value?.toIntOrNull()?.takeIf { it in 1..50 } ?: 1
            }
        }
    }

    /** تنسيق الحلقة للعرض: «31» أو «الحلقة 31 - الجزء الأول». */
    private fun episodeLabel(num: Int, title: String): String {
        val part = partNumberOf(title)
        val base = num.toString()
        return when {
            part <= 0 -> base
            part == 1 -> "الحلقة $num"
            else -> "$num (الجزء ${partNameOf(part)})"
        }
    }

    private fun partNameOf(part: Int): String = when (part) {
        1 -> "الأول"
        2 -> "الثاني"
        3 -> "الثالث"
        4 -> "الرابع"
        5 -> "الخامس"
        else -> "الأخير"
    }

    private fun isFinale(title: String): Boolean = FINALE_RE.containsMatchIn(title)

    private fun skipTitle(title: String): Boolean = SKIP_RE.containsMatchIn(title)

    /** يلوّث العنوان بمؤخّرات القناة («| بلال عباس خان، دورِ فشان سليم | هم العربية»). */
    private fun bareName(title: String): String {
        var t = clean(title).replace(Regex("""\s+"""), " ")
        t = t.substringBefore('|').substringBefore('–').substringBefore(" - ").trim()
        if (t.startsWith("مسلسل ")) t = t.removePrefix("مسلسل ").trim()
        return t
    }

    private fun keyOf(title: String): String {
        val sb = StringBuilder()
        for (c in bareName(title)) {
            when (c) {
                in 'ً'..'ْ', 'ـ', 'ٰ' -> {}      // تشكيل وتطويل
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ء' -> {}
                ' ' -> if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                else -> sb.append(c.lowercaseChar())
            }
        }
        return sb.toString().trim()
    }

    private fun posterOf(id: String): String = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private fun playlistUrl(id: String): String = "https://www.youtube.com/playlist?list=$id"

    private fun isPlaylistOrShow(type: String): Boolean =
        type.contains("PLAYLIST") || type.contains("SHOW")

    // ============================== playlists ==============================

    private data class PlaylistInfo(
        val id: String,
        val title: String,
        val cover: String?,
        val count: Int = 0
    )

    private suspend fun channelPlaylists(url: String): List<PlaylistInfo> {
        val out = mutableListOf<PlaylistInfo>()
        try {
            val first = fetchInitialData(url, true)
            for (l in lockupsOf(first)) {
                if (!isPlaylistOrShow(l.type)) continue
                if (out.any { it.id == l.id }) continue
                out.add(PlaylistInfo(l.id, l.title, l.thumb, l.count))
            }
            val token = continuationTokenOf(first)
            val second = token?.let { continuationPage(it) }
            if (second != null) {
                for (l in lockupsOf(second)) {
                    if (!isPlaylistOrShow(l.type)) continue
                    if (out.any { it.id == l.id }) continue
                    out.add(PlaylistInfo(l.id, l.title, l.thumb, l.count))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playlists tab failed: ${e.message}")
        }
        return out
    }

    /** قائمة القناتين المدمجتين (تُحفظ لتُخدم الرئيسية والبحث معاً). */
    @Volatile
    private var cachedPlaylists: List<PlaylistInfo>? = null

    private suspend fun builtinPlaylists(): List<PlaylistInfo> {
        cachedPlaylists?.let { return it }
        // نجلب القناتين المدمجتين بالتوازي، فترتّب الرئيسية عوض تتابعٍ
        // يضاعف زمن الانتظار.
        val builtin = coroutineScope {
            listOf(CHANNEL_ID, CHANNEL_ID_2).map { id ->
                async { channelPlaylists(playlistsUrl(id)) }
            }.awaitAll().flatten()
        }
        val byId = LinkedHashMap<String, PlaylistInfo>()
        for (p in builtin) byId[p.id] = p
        Log.d(TAG, "builtin playlists: ${byId.size}")
        if (byId.isNotEmpty()) cachedPlaylists = byId.values.toList()
        return cachedPlaylists ?: emptyList()
    }

    /**
     * قنوات إضافية من الإعدادات (رابطٌ في كل سطر). كلٌّ منها يظهر بقسمٍ مستقل
     * في الرئيسية وتشملها نتائج البحث. الافتراضي (فارغ) = سلوك اليوم تماماً.
     *
     * الصيغة المقبولة لكل سطر:
     *   - رابط/معرّف قناة فقط: `UC…` أو `/channel/UC…` أو `/@handle` أو `@handle`
     *     → الاسم يُشتق تلقائياً من الـ handle أو المعرّف.
     *   - اسمٌ مخصص للقسم: `الاسم | الرابط` (كلٌّ منهما كما سبق).
     * لا تُسقط أيّ سطر فاشل بقية الأقسام ولا القناتين المدمجتين.
     */
    private data class ExtraChannel(val label: String, val url: String)

    private fun extraChannels(): List<ExtraChannel> {
        val raw = prefs?.getString(HumSettingsBottomSheet.KEY_EXTRA_CHANNELS, "")
            ?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val out = mutableListOf<ExtraChannel>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // اسمٌ مخصص اختياري: «الاسم | الرابط» — الشطر قبل | الاسم، وبعده الرابط.
            var customName: String? = null
            var rest = trimmed
            val pipe = trimmed.split('|')
            if (pipe.size >= 2) {
                val a = pipe[0].trim()
                val b = pipe.subList(1, pipe.size).joinToString("|").trim()
                if (a.isNotEmpty() && (b.startsWith("http") || b.startsWith("UC") || b.startsWith("@") || b.contains("/channel/") || b.contains("youtube.com"))) {
                    customName = a
                    rest = b
                }
            }
            val cid = Regex("""UC[\w-]{22}""").find(rest)?.value
            val handle = Regex("""@([\w.-]+)""").find(rest)?.groupValues?.get(1)
            val url = when {
                cid != null -> playlistsUrl(cid)
                handle != null -> "https://www.youtube.com/@$handle/playlists"
                else -> null
            }
            if (url != null) {
                // الاسم المخصص يغلب الاسم المشتق تلقائياً.
                val label = customName ?: (handle?.let { "@$it" } ?: cid ?: "")
                if (out.none { it.url == url }) out.add(ExtraChannel(label, url))
            }
        }
        return out
    }

    /** كل القوائم (المدمجة + الإضافية) — أساس البحث وعرض الأقسام. */
    private suspend fun allPlaylists(): List<PlaylistInfo> {
        val byId = LinkedHashMap<String, PlaylistInfo>()
        for (p in builtinPlaylists()) byId[p.id] = p
        val extras = extraChannels()
        if (extras.isNotEmpty()) {
            // نجلب القنوات الإضافية بالتوازي (أسرع من التتابع).
            coroutineScope {
                extras.map { extra ->
                    async {
                        try {
                            channelPlaylists(extra.url)
                        } catch (e: Exception) {
                            Log.w(TAG, "extra channel '${extra.label}' failed: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll().forEach { pls ->
                    for (p in pls) if (p.id !in byId) byId[p.id] = p
                }
            }
        }
        return byId.values.toList()
    }

    /** حلقات قائمة: تُعيدها يوتيوب كاملة في الصفحة الأولى لمعظم المسلسلات. */
    private suspend fun playlistItems(playlistId: String): List<Lockup> {
        return try {
            val data = fetchInitialData("https://www.youtube.com/playlist?list=$playlistId", false)
            lockupsOf(data).filter { it.type.contains("VIDEO") }
        } catch (e: Exception) {
            Log.w(TAG, "playlist $playlistId failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * يحوّل عناصر القائمة إلى حلقات مرقّمة مرتّبة. القناة لا ترقّم الحلقة
     * الأخيرة (أو تُرقّمها صراحة «الحلقة الأخيرة»)، فتُوضع بعد أعلى رقم.
     * عناصر غير مرقّمة (مقاطع دروب العشق) تُحافظ على ترتيب القائمة كحلقات.
     */
    private fun episodesOf(items: List<Lockup>): List<Pair<Int, Lockup>> {
        val numbered = mutableListOf<Pair<Int, Lockup>>()
        val extras = mutableListOf<Lockup>()
        var finale: Lockup? = null
        for (i in items) {
            if (skipTitle(i.title)) continue
            val n = episodeNumberOf(i.title)
            when {
                n != null -> numbered.add(n to i)
                isFinale(i.title) && finale == null -> finale = i
                else -> extras.add(i)
            }
        }
        val maxNum = numbered.maxOfOrNull { it.first } ?: 0
        var next = maxNum + 1
        if (finale != null) { numbered.add((next) to finale); next += 1 }
        for (l in extras)
            numbered.add((next++) to l)
        return distinctEpisodes(numbered)
    }

    /**
     * عندما تُقسَّم حلقةٌ لعدة أجزاء (كلّها برقمٍ واحد «الحلقة 31 الجزء
     * الأول/الثاني») تتعارض برقمٍ واحد في التطبيق فيسقط أحدها. نمنح كلّ
     * عنصرٍ رقماً مختلفاً يحافظ على الترتيب: أوّلُ ما يُعرض ضمن رقم الحلقة
     * يأخذ الرقم نفسه، والتالون يأخذون الأرقام اللاحقة الشاغرة (n+1, n+2…).
     * تُرتَّب الأجزاء بترتيب رقم الجزء (الأول قبل الثاني) ثم الناتج تصاعدياً.
     */
    private fun distinctEpisodes(all: List<Pair<Int, Lockup>>): List<Pair<Int, Lockup>> {
        // نفس الفيديو قد يُتكرر في القائمة — نُبقي أول ظهور فقط (كما كان سابقاً).
        val eps = all.distinctBy { it.second.id }

        // جمّع العناصر المتشاركة في رقم الحلقة نفسه (مثل «الحلقة 31» بأجزائها).
        val byBase = LinkedHashMap<Int, MutableList<Lockup>>()
        for ((n, l) in eps) byBase.getOrPut(n) { mutableListOf() }.add(l)

        val used = HashSet<Int>()
        val out = mutableListOf<Pair<Int, Lockup>>()

        for ((base, group) in byBase) {
            // الحلقات المفردة (بلا جزأ) أولاً، ثم الأجزاء بترتيب رقمها
            // (الأول قبل الثاني قبل الأخير).
            val ordered = group.sortedWith(
                compareBy({ partNumberOf(it.title) == 0 }, { partNumberOf(it.title) })
            )
            var slot = base
            for (l in ordered) {
                while (slot in used) slot += 1      // تجاوز الأرقام المشغولة
                out.add(slot to l)
                used.add(slot)
                slot += 1
            }
        }
        return out.sortedBy { it.first }
    }

    // ============================== main page ==============================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val lists = mutableListOf<HomePageList>()

        // صف القناتين المدمجتين أولاً.
        val builtin = builtinPlaylists()
        homeFrom(builtin, "مسلسلات هم العربية", lists)

        // ثم صفٌّ مستقل لكل قناة إضافية (تُجلب بالتوازي؛ لا نعيد ما سبق عرضه).
        val seen = seenKeys(builtin)
        val extras = extraChannels()
        if (extras.isNotEmpty()) {
            coroutineScope {
                extras.map { extra ->
                    async {
                        try {
                            channelPlaylists(extra.url).filter { seen.add(keyOf(it.title)) } to extra
                        } catch (e: Exception) {
                            Log.w(TAG, "extra channel '${extra.label}' failed: ${e.message}")
                            emptyList<PlaylistInfo>() to extra
                        }
                    }
                }.awaitAll().forEach { (pls, extra) ->
                    homeFrom(pls, "قناة ${extra.label}", lists)
                }
            }
        }
        return newHomePageResponse(lists)
    }

    /** مفتاح كل قائمة في قائمةٍ ما — لتفادي تكرار المسلسل نفسه في صفّين. */
    private fun seenKeys(playlists: List<PlaylistInfo>): MutableSet<String> {
        val s = mutableSetOf<String>()
        for (p in playlists) {
            val k = keyOf(p.title)
            if (k.isNotBlank()) s.add(k)
        }
        return s
    }

    private fun homeFrom(playlists: List<PlaylistInfo>, title: String, lists: MutableList<HomePageList>) {
        val series = dedupe(playlists)
        val cards = series.map { p ->
            newTvSeriesSearchResponse(bareName(p.title), playlistUrl(p.id)) {
                this.posterUrl = p.cover
            }
        }
        if (cards.isNotEmpty())
            lists.add(HomePageList(title, cards))
    }

    private fun dedupe(playlists: List<PlaylistInfo>): List<PlaylistInfo> {
        val byKey = LinkedHashMap<String, PlaylistInfo>()
        for (p in playlists) {
            if (p.title.isBlank() || skipTitle(p.title)) continue
            val k = keyOf(p.title)
            if (k.isBlank()) continue
            val prev = byKey[k]
            byKey[k] = when {
                prev == null -> p
                p.count > prev.count -> p
                else -> prev
            }
        }
        return byKey.values.toList()
    }

    // =============================== search ===============================

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val q = clean(query)
        if (q.isBlank()) return emptyList()

        val playlists = allPlaylists()
        if (playlists.isEmpty()) return emptyList()

        val words = keyOf(q).split(' ')
            .filter { it.length > 1 && it != "مسلسل" && it != "حلقه" }

        val hits = if (words.isEmpty())
            playlists.filter { keyOf(it.title).contains(keyOf(q)) }
        else
            playlists.filter { p ->
                val hay = keyOf(p.title)
                words.all { hay.contains(it) }
            }

        val out = hits.map { p ->
            newTvSeriesSearchResponse(bareName(p.title), playlistUrl(p.id)) {
                this.posterUrl = p.cover
            }
        }
        Log.d(TAG, "search '$q' -> ${out.size}")
        return out
    }

    // ================================ load ================================

    override suspend fun load(url: String): LoadResponse? {
        // 1) قائمة تشغيل ?list= (روابط الرئيسية والبحث)
        Regex("""[?&]list=([\w-]+)""").find(url)?.let { m ->
            val pid = m.groupValues[1]
            val info = allPlaylists().firstOrNull { it.id == pid }
                ?: PlaylistInfo(pid, "مسلسل", null)
            return loadPlaylist(info)
        }

        // 2) فيديو مفرد ?v= يفتح كمشاهدة منفردة.
        Regex("""[?&]v=([\w-]{11})""").find(url)?.let { m ->
            val vid = m.groupValues[1]
            return newMovieLoadResponse("فيديو", url, TvType.Movie, vid) {
                this.posterUrl = posterOf(vid)
            }
        }
        return null
    }

    private suspend fun loadPlaylist(info: PlaylistInfo): LoadResponse? {
        val items = playlistItems(info.id)
        val eps = episodesOf(items)
        if (eps.isEmpty()) return null

        val name = bareName(info.title).ifBlank { "مسلسل" }
        val poster = eps.firstOrNull()?.second?.thumb ?: info.cover

        val episodes = eps.map { (num, l) ->
            newEpisode("https://www.youtube.com/watch?v=${l.id}") {
                this.name = episodeLabel(num, l.title).ifBlank { "الحلقة $num" }
                this.episode = num
                this.posterUrl = l.thumb ?: posterOf(l.id)
            }
        }

        Log.d(TAG, "playlist '${info.title}' -> ${episodes.size} episodes")

        return newTvSeriesLoadResponse(name, playlistUrl(info.id), TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    // ============================== loadLinks ==============================

    /** كوديك الفيديو مختصراً للعرض: avc1→H.264، vp9→VP9، av01→AV1، … */
    private fun codecTag(mime: String?): String {
        val m = mime.orEmpty()
        return when {
            m.contains("av01") -> "AV1"
            m.contains("vp09") || m.contains("/vp9") -> "VP9"
            m.contains("avc1") || m.contains("/avc") -> "H.264"
            m.contains("webm") -> "VP9"
            m.contains("mp4") -> "H.264"
            else -> ""
        }
    }

    /** حجم ملف الفيديو من معامل `clen` في رابط googlevideo (بالبايت). */
    private fun byteSizeOf(url: String): Long {
        if (url.isEmpty()) return 0
        val i = url.indexOf("clen=")
        if (i < 0) return 0
        val after = url.substring(i + 5)
        val j = after.indexOf('&')
        val num = if (j >= 0) after.substring(0, j) else after
        return runCatching { num.toLongOrNull() ?: 0L }.getOrDefault(0L)
    }

    /** «360 • 62MB (H.264)» — تسمية تُفرّق الجودات في قائمة الاختيار. */
    private fun richLabel(height: Int, url: String, mime: String?): String {
        val h = if (height > 0) height.toString() else "auto"
        val bytes = byteSizeOf(url)
        val mb = if (bytes > 0) "• ${"%.1f".format(bytes / 1048576.0)}MB" else ""
        val tag = codecTag(mime)
        val t = if (tag.isNotEmpty()) " ($tag)" else ""
        return "$h$mb$t"
    }

    private fun codecFromMime(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val i = mime.indexOf("codecs=")
        if (i < 0) return null
        return mime.substring(i + "codecs=".length).trim().removeSurrounding("\"")
            .ifBlank { null }
    }

    /** رقم الجودة كتسمية (NewPipe قد يعطي height صفراً أحياناً). */
    private fun qualityLabelOf(vs: org.schabi.newpipe.extractor.stream.VideoStream): String {
        val height = runCatching { vs.height }.getOrNull() ?: 0
        return if (height > 0) height.toString() else "video"
    }

    /**
     * مسار التشغيل المطابق لـ aryarabia: `YoutubeStreamExtractor.fetchPage()` يعطي
     * روابط googlevideo **مفكوكة** (توقيع + معامل n) مع نطاقات Init/Index لكل
     * جودة، ونبني منها مانيفست DASH محلي (HumDashServer) يعرض كل جودة مع أفضل
     * صوتٍ لها، فيتلقى يوتيوب طلبات Range شرعية — لا يرفض 2004.
     */
    private suspend fun resolveFromNewPipe(
        vid: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var produced = 0
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(watchUrl)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            val dur = runCatching { s.length }.getOrNull() ?: 0
            val durationSeconds = if (dur > 0) dur else 3600L

            val seenUrls = mutableSetOf<String>()

            val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val streamUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(streamUrl)) return@mapNotNull null

                    val label = qualityLabelOf(vs)
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    var mime = vs.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = HumDashServer.mimeFromUrl(streamUrl, false)

                    val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                    val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                    HumStreamInfo(streamUrl, mime, height, label, initR, indexR, codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

            val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                try {
                    val aUrl = asr.content ?: return@mapNotNull null
                    val bitrate = runCatching { asr.bitrate ?: 128000 }.getOrNull() ?: 128000
                    var mime = runCatching { asr.format?.mimeType }.getOrNull()
                    if (mime.isNullOrEmpty()) mime = HumDashServer.mimeFromUrl(aUrl, true)

                    val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                    val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null

                    HumAudioInfo(aUrl, mime, bitrate, initR, indexR, "_lang", codecFromMime(mime))
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

            runCatching {
                s.subtitlesDefault?.filterNotNull()?.mapNotNull { ss ->
                    try {
                        val lang = ss.locale?.language ?: return@mapNotNull null
                        val content = ss.content ?: ss.url ?: return@mapNotNull null
                        newSubtitleFile(lang, content)
                    } catch (e: Exception) { null }
                }?.forEach { subtitleCallback(it) }
            }

            HumDashServer.ensureStarted()

            val effectiveVideos = if (qualityMode() == "high") {
                val top = videoOnlyList.maxByOrNull { it.height }
                if (top != null) videoOnlyList.filter { it.height == top.height } else videoOnlyList
            } else videoOnlyList

            for (video in effectiveVideos) {
                val bestAudio = if (audioInfoList.isNotEmpty()) {
                    val family = if (video.mimeType.contains("webm")) { a: HumAudioInfo ->
                        a.mimeType.contains("webm")
                    } else { a: HumAudioInfo ->
                        a.mimeType.contains("mp4")
                    }
                    audioInfoList.sortedWith(
                        compareByDescending<HumAudioInfo>(family).thenByDescending { it.bitrate }
                    ).firstOrNull()
                } else null

                val localLink = HumDashServer.buildAndRegister(
                    video, if (bestAudio != null) listOf(bestAudio) else emptyList(),
                    durationSeconds
                )
                if (localLink != null) {
                    callback(
                        newExtractorLink(
                            this@HumProvider.name,
                            richLabel(video.height, video.url, video.mimeType),
                            localLink,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = mainUrl
                            this.quality = video.height
                        }
                    )
                    produced++
                }
            }

            // مقاطع مدمجة (muxed) — احتياط فقط إن لم يُنتج مسار DASH أعلاه أي رابط.
            if (produced == 0) {
                val muxedList = (s.videoStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val mUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(mUrl)) return@mapNotNull null
                        Triple(mUrl, qualityLabelOf(vs), runCatching { vs.height ?: 0 }.getOrNull() ?: 0)
                    } catch (e: Exception) { null }
                }
                val effectiveMuxed = if (qualityMode() == "high") {
                    muxedList.maxByOrNull { it.third }?.let { listOf(it) } ?: muxedList
                } else muxedList
                effectiveMuxed.forEach { (mUrl, mLabel, mHeight) ->
                    callback(
                        newExtractorLink(this@HumProvider.name, "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = mHeight
                        }
                    )
                    produced++
                }
            }

            Log.d(TAG, "$vid NewPipe DASH links=$produced qualities=${effectiveVideos.size}")
        } catch (e: Exception) {
            Log.w(TAG, "$vid NewPipe resolve failed: ${e.message}")
        }
        return produced
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = data.trim()
        val vid = if (Regex("""^[\w-]{11}$""").matches(raw)) raw
        else Regex("""[?&]v=([\w-]{11})""").find(raw)?.groupValues?.get(1)
        if (vid == null) {
            Log.w(TAG, "loadLinks: unexpected data '$data'")
            return false
        }
        val watchUrl = "https://www.youtube.com/watch?v=$vid"
        val mode = playbackMode()

        val orderedPrimary = when (mode) {
            "extractor" -> 1
            "direct" -> 2
            else -> 0                               // newpipe (الافتراضي)
        }

        val collected = mutableListOf<ExtractorLink>()
        val sink: (ExtractorLink) -> Unit = { link -> collected.add(link); Unit }
        var links = 0

        suspend fun runFirst() {
            when (orderedPrimary) {
                1 -> {
                    loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
                        sink(link); links++
                    }
                }
                2 -> {
                    // لا مسار «HTML ذو ABR» هنا: NewPipe+DASH الأساسي وextractor بديل.
                }
                else -> {
                    links += resolveFromNewPipe(vid, subtitleCallback, sink)
                }
            }
        }
        runFirst()

        // احتياط: إن كان المسار الأساسي extractor ولم يُعطِ شيئاً، نجرب NewPipe+DASH.
        if (links == 0 && orderedPrimary != 0) {
            links += resolveFromNewPipe(vid, subtitleCallback, sink)
        }
        if (links == 0 && orderedPrimary != 1) {
            loadExtractor(watchUrl, "https://www.youtube.com/", subtitleCallback) { link ->
                sink(link); links++
            }
        }

        val order = prefs?.getString(HumSettingsBottomSheet.KEY_QUALITY_ORDER, "default")
        val sorted = when (order) {
            "asc" -> collected.sortedBy { it.quality }
            "desc" -> collected.sortedByDescending { it.quality }
            else -> collected
        }
        sorted.forEach { callback(it) }

        Log.d(TAG, "loadLinks $vid mode=$mode links=$links")
        return links > 0
    }
}