package com.rimshort.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.FormatTag
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val TAG = "RimShort"

/** لغة الاستعلامات في الـAPI — الموقع يخدم العربية على `/ar` والباقي بلا تغيير. */
private const val API_LANG = "ar"

/**
 * سقف انتظار الواجهة الرئيسية.
 *
 * الموقع نفسه هو البطيء لا الكود: `/api/home` قِيس ٢٠٢٦-١٠-٢٠ بين ٠٫٦ث و٢٢ث
 * (`ttfb` وحده بين ٠٫٦ث و٢٢ث)، والاستجابة ١٨٣ كيلوبايت لا تقبل التقسيم — لا
 * `limit` ولا `shelf` ولا `page` تُقلّلها (كلّها مُتجاهَلة، والردّ واحد بالحجم
 * نفسه). فما بقي إلا سقفٌ صريح.
 *
 * ولاستخدام السقف فائدةٌ ثانية: الحافّة تخزّن الردّ يوماً كاملاً
 * (`s-maxage=86400`)، فانتهاء المهلة هنا يُسخّن الحافّة ويجعل إعادة المحاولة
 * سريعة — وذلك أهون من دوّارٍ لا ينتهي.
 */
private const val HOME_BUDGET_MS = 10_000L

/**
 * عملٌ مصغّر كما تعيده `/api/home` و`/api/search` — **لا** يُشترك نموذجَه مع
 * [DramaDetail] لأن `episodes` هنا عددٌ صحيح (عدد الحلقات)، وفي تفاصيل العمل
 * مصفوفةُ حلقات — تسميةٌ واحدة وأنواعٌ مختلفة، ودمجُهما في نموذجٍ واحد يجعل
 * أحد الاستجابتين تُخفَق في التحليل.
 */
private data class LiteBook(
    val id: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val episodes: Int? = null,
    val firstChapterId: String? = null,
)

/** قسمٌ من أقسام الرئيسية — لكل قسم عشرة أعمال تقريباً. */
private data class Shelf(
    val shelfId: String? = null,
    val name: String? = null,
    val books: List<LiteBook>? = null,
)

/** بانر الصفحة الرئيسية — `id` و`bookId` متعادلان، والصورة `bookPic` عمودية. */
private data class Banner(
    val id: String? = null,
    val bookId: String? = null,
    val title: String? = null,
    val bookPic: String? = null,
    val verticalPic: String? = null,
    val chapterId: String? = null,
)

private data class HomeResponse(
    val books: List<LiteBook>? = null,
    val shelves: List<Shelf>? = null,
    val banners: List<Banner>? = null,
)

/** حلقةٌ واحدة كما تردّ في `/api/drama/{id}` — بلا عنوان، الرقم وكيفتها فقط. */
private data class EpisodeItem(
    val number: Int? = null,
    val chapterId: String? = null,
)

private data class DramaDetail(
    val id: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val totalEpisodes: Int? = null,
    val tags: List<String>? = null,
    val status: String? = null,
    val episodes: List<EpisodeItem>? = null,
)

private data class VideoResponse(
    val title: String? = null,
    val bookId: String? = null,
    val chapterId: String? = null,
    val episodeNumber: Int? = null,
    val videoUrl: String? = null,
    val videoType: String? = null,
)

/** درجةٌ واحدة داخل قائمة تكيّفية — رابطها المطلق وجودتها المقيسة. */
private data class Variant(val url: String, val quality: String?)

/** مسار ترجمةٍ من `#EXT-X-MEDIA` — رابطها المطلق ورمز لغتها كما أعلنته. */
private data class SubTrack(val url: String, val lang: String)

/** قائمة تشغيلٍ بعد تحليلها: درجاتها وترجماتها. */
private data class ParsedPlaylist(
    val variants: List<Variant>,
    val subs: List<SubTrack>,
)

class RimShortProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "RimShort"
    override var mainUrl = "https://rimshort.cyou"
    override var lang = "ar"
    override val hasMainPage: Boolean
        get() = prefs?.getBoolean(RimShortSettings.KEY_SHOW_HOME, true) != false
    override var hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    /** إظهار وسم الصيغة في اسم السيرفر ([M3U8]/[MP4]/…) — يقرأ عند كل بثّ. */
    private fun showFormatTag(): Boolean =
        prefs?.getBoolean(RimShortSettings.KEY_FORMAT_TAG, true) != false

    /** إظهار ترجمات القائمة التكيّفية — يقرأ عند كل بثّ. */
    private fun showSubs(): Boolean =
        prefs?.getBoolean(RimShortSettings.KEY_SHOW_SUBS, true) != false

    /** عدد أقسام الرئيسية المعروضة — "all" أو رقم. يقرأ عند كل رسم. */
    private fun homeRowCap(): Int? = prefs?.getString(RimShortSettings.KEY_HOME_ROWS, "all")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private fun descOrder(): Boolean =
        prefs?.getString(RimShortSettings.KEY_EPISODE_ORDER, "as_is") == "desc"

    /** جسم JSON من الـAPI فوق نطاق الموقع نفسه (الاستعلامات تمرّ عبر الواجهة). */
    private suspend fun apiJson(path: String): String? = try {
        val r = app.get(mainUrl + path, referer = mainUrl)
        if (r.isSuccessful) r.text else {
            Log.e(TAG, "api ${r.code} $path")
            null
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "api FAILED $path", e)
        null
    }

    // ============================== Main page ==============================

    /** استجابة واحدة تُطعم كل الصفوف: البانرات ثم أقسام الموقع كما هي ترتيبها. */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        return try {
            // سقفٌ صريح: مهلة الـOkHttp الافتراضية دقائق، فسطرٌ بطيء يُبقي
            // الواجهة فارغة كل ذلك الوقت. عند انتهاء المهلة يعود `null` —
            // والتطبيق يعرض واجهةً بديلة أو رسالة بدل دوّارٍ لا ينتهي.
            val text = withTimeoutOrNull(HOME_BUDGET_MS) { apiJson("/api/home?lang=$API_LANG") }
                ?: run {
                    Log.e(TAG, "getMainPage over budget ${HOME_BUDGET_MS}ms")
                    return null
                }
            val home = mapper.readValue(text, HomeResponse::class.java)
            val rows = ArrayList<HomePageList>()

            home.banners.orEmpty().mapNotNull { bannerCard(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { rows.add(HomePageList("مُختار الموقع", it)) }

            for (shelf in home.shelves.orEmpty()) {
                val items = shelf.books.orEmpty().mapNotNull { liteCard(it) }
                if (items.isEmpty()) continue
                val label = shelf.name?.trim().orEmpty().ifEmpty { "قسم" }
                rows.add(HomePageList(label, items))
            }

            // بديل إن غابت الأقسام: كل الأعمال كما تردّها الصفحة الرئيسية نفسها.
            if (rows.isEmpty()) {
                val items = home.books.orEmpty().mapNotNull { liteCard(it) }
                if (items.isNotEmpty()) rows.add(HomePageList("أحدث الإصدارات", items))
            }

            if (rows.isEmpty()) {
                Log.e(TAG, "getMainPage empty home")
                null
            } else {
                val cap = homeRowCap()
                val capped = if (cap != null && rows.size > cap) rows.take(cap) else rows
                newHomePageResponse(capped, false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED", e)
            null
        }
    }

    private fun bannerCard(b: Banner): SearchResponse? {
        val id = b.bookId?.takeIf { it.isNotBlank() }
            ?: b.id?.takeIf { it.isNotBlank() }
            ?: return null
        val title = b.title?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        return newTvSeriesSearchResponse(title, "$mainUrl/watch/$id", TvType.TvSeries) {
            posterUrl = b.bookPic?.takeIf { it.isNotBlank() } ?: b.verticalPic
        }
    }

    private fun liteCard(b: LiteBook): SearchResponse? {
        val id = b.id?.takeIf { it.isNotBlank() } ?: return null
        val title = b.title?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        return newTvSeriesSearchResponse(title, "$mainUrl/watch/$id", TvType.TvSeries) {
            posterUrl = b.cover?.takeIf { it.isNotBlank() }
            val n = b.episodes
            if (n != null && n > 0) this.episodes = n
        }
    }

    // ============================== Search ==============================

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val path = "/api/search?lang=$API_LANG&q=" + URLEncoder.encode(q, "UTF-8")
            val text = apiJson(path) ?: return null
            mapper.readValue(text, Array<LiteBook>::class.java).mapNotNull { liteCard(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED q=$query", e)
            null
        }
    }

    // ========================= Details & Chapters =========================

    /** معرّف العمل من أي شكلٍ يصلنا: رابط `/watch/{id}` أو معرّفه مجرّداً. */
    private fun bookIdFrom(raw: String): String? {
        val fromPath = Regex("""/watch/([0-9a-f]{24})""").find(raw)?.groupValues?.get(1)
        if (fromPath != null) return fromPath
        val tail = raw.substringAfterLast('/').substringBefore('?')
        return tail.takeIf { it.matches(Regex("""[0-9a-f]{24}""")) }
    }

    private fun queryParam(raw: String, key: String): String? =
        Regex("""[?&]$key=([^&#\s]+)""").find(raw)?.groupValues?.get(1)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val id = bookIdFrom(url) ?: run {
                Log.e(TAG, "load no book id url=$url")
                return null
            }
            val text = apiJson("/api/drama/$id?lang=$API_LANG") ?: return null
            val d = mapper.readValue(text, DramaDetail::class.java)
            val title = d.title?.trim().takeUnless { it.isNullOrBlank() } ?: return null

            // كل حلقة تحمل `ch` في رابطها: من دونه تردّ `/api/video` خطأً 404
            // «Video not found» (قِيس 2026-10-20) فلا تُبنى قائمةٌ بلا مفتاح.
            val numbered = d.episodes.orEmpty()
                .filter { it.number != null && !it.chapterId.isNullOrBlank() }
            val ordered = if (descOrder()) numbered.sortedByDescending { it.number }
            else numbered.sortedBy { it.number }

            val episodes = ordered.map { ep ->
                newEpisode("$mainUrl/watch/$id?ep=${ep.number}&ch=${ep.chapterId}") {
                    episode = ep.number
                    name = "الحلقة ${ep.number}"
                }
            }
            if (episodes.isEmpty()) Log.e(TAG, "load no episodes id=$id title=$title")

            newTvSeriesLoadResponse(title, "$mainUrl/watch/$id", TvType.TvSeries, episodes) {
                d.cover?.takeIf { it.isNotBlank() }?.let {
                    posterUrl = it
                    backgroundPosterUrl = it
                }
                d.description?.takeIf { it.isNotBlank() }?.let { plot = it }
                d.tags.orEmpty().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
                    tags = it
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED $url", e)
            null
        }
    }

    /** إيجاد `chapterId` لحلقةٍ جاء رابطها بلا `ch` — نفس ما تفعله الصفحة نفسها. */
    private suspend fun resolveChapterId(id: String, ep: Int): String? {
        val text = apiJson("/api/drama/$id?lang=$API_LANG") ?: return null
        return mapper.readValue(text, DramaDetail::class.java).episodes.orEmpty()
            .firstOrNull { it.number == ep }
            ?.chapterId
            ?.takeIf { it.isNotBlank() }
    }

    // ============================== Pages ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val id = bookIdFrom(data)
            val ep = queryParam(data, "ep")?.toIntOrNull()
            Log.d(TAG, "loadLinks raw=$data id=$id ep=$ep")
            if (id == null || ep == null) {
                Log.e(TAG, "loadLinks unparsable data=$data")
                return false
            }

            val chapterId = queryParam(data, "ch")?.takeIf { it.isNotBlank() }
                ?: resolveChapterId(id, ep)
            if (chapterId == null) {
                Log.e(TAG, "loadLinks no chapter id=$id ep=$ep")
                return false
            }

            val path = "/api/video?bookId=$id&episodeNum=$ep" +
                "&chapterId=$chapterId&lang=$API_LANG"
            val text = apiJson(path) ?: return false
            val v = mapper.readValue(text, VideoResponse::class.java)
            val stream = v.videoUrl?.trim().takeIf { !it.isNullOrEmpty() } ?: run {
                Log.e(TAG, "loadLinks no videoUrl id=$id ep=$ep")
                return false
            }
            val playlistUrl = abs(stream)

            // ============ سيرفرات التشغيل: لماذا لا نقرأ القائمة دائماً ============
            //
            // سيرفرٌ واحد لكل حلقة: `videoUrl` حقلٌ واحد، ولا منتقي سيرفرات في
            // الواجهة، و`line`/`server`/`quality`/`format` مُتجاهَلة، ولواحق
            // الجودات (‑ld/‑hd/‑fhd/‑hd2/‑sd2) تردّ 404. فما «السيرفرات» إلا
            // درجات القائمة الواحدة.
            //
            // لكن انتظار القائمة قبل إرسال أي رابط هو ما جعل الدوّار لا ينتهي:
            // التطبيق (`ResultViewModel2.loadLinks`) يُجمِّع الروابط في مجموعةٍ
            // ويُعيدها **دفعةً واحدة بعد رجوع `loadLinks`**، ودوّاره `_loadedLinks`
            // يبقى طوال ذلك. وقائمة هذا الموقع قِيس ٢٠٢٦-١٠-٢٠ بين ٠٫٧ث و٢٣ث
            // (وتأخّرت مرّةً حتى المهلة فسقطت)، فالانتظار كان يقع كله على البثّ.
            //
            // والحلّ: شكل الرابط نفسه يقول أيّ قائمةٍ هي، بلا أي جلب.
            // مقياسٌ على **١٠٣ أعمال: صفر مخالفة** —
            //   `/h264/` في المسار  ⟺  قائمةٌ تكيّفية (٣ درجات + ١٩ ترجمة)
            //   وما عداه                ⟺  قائمةٌ مفردة (لا درجات ولا ترجمات)
            // والتكيّفيّ نادر: **١ من ١٠٣ (٠٫٩٧٪)**. فالغالبية تُبَثّ فوراً،
            // والنادر وحده ينتظر القائمة ليُظهر جوداته الثلاث.
            val declared = v.videoType?.let { FormatTag.fromMime(it) ?: it.trim().uppercase() }

            val adaptive = ADAPTIVE_PATH.containsMatchIn(playlistUrl)
            val parsed = if (adaptive) {
                // سقفٌ للقائمة النادرة وحدها: بعد انتهائه يُبَثّ رابطها كما هو
                // (المشغّل يقرأ القائمة التكيّفية بنفسه)، فلا ينكسر شيء.
                val text = withTimeoutOrNull(PLAYLIST_BUDGET_MS) { fetchPlaylist(playlistUrl) }
                if (text == null) Log.e(TAG, "master over budget id=$id ep=$ep")
                text?.let { parsePlaylist(it, playlistUrl) }
            } else {
                null
            }
            val variants = parsed?.variants.orEmpty()

            // بلا درجات (مفردة، أو فشل جلب القائمة) يبقى رابط القائمة نفسه صالحاً
            // للتشغيل — فالفشل هنا لا يُسقط البثّ.
            val streams = if (variants.isNotEmpty()) variants
            else listOf(Variant(playlistUrl, qOf(playlistUrl)))

            for (s in streams) {
                // الجودة المقيسة ثم وسم الصيغة في الاسم — التطبيق يعرض
                // `ExtractorLink.name` فقط في «سيرفرات التشغيل».
                val q = s.quality ?: "720p"
                val linkName = if (showFormatTag()) {
                    FormatTag.tagged(q, s.url, ExtractorLinkType.M3U8, declared)
                } else {
                    q
                }
                Log.d(TAG, "emit id=$id ep=$ep q=$q url=${s.url}")
                callback(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = s.url,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.quality = getQualityFromName(q)
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to mainUrl)
                    },
                )
            }

            if (showSubs()) emitSubtitles(parsed?.subs.orEmpty(), id, ep, subtitleCallback)

            streams.isNotEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FATAL data=$data", e)
            false
        }
    }

    /**
     * إظهار ترجمات القائمة التكيّفية — ١٩ لغة، والعربية فيها.
     *
     * **بلا أي جلب.** `URI` في `#EXT-X-MEDIA` قائمةُ ترجمةٍ (`subtitle_<hash>/ar.m3u8`)
     * وفيها ملفّ `.vtt` واحد باسم `<اللغة>-00001.vtt` — نمطٌ ثابت قِيس
     * ٢٠٢٦-١٠-٢٠ على **١٩/١٩ مساراً** في العمل التكيّفيّ، وعلى رابطٍ مشتقٍّ
     * بلا قراءة القائمة الفرعية أصلاً: **١٩/١٩ يردّ 200 و`WEBVTT` صحيحاً**.
     * فالمسار يُبنى من نص القائمة وحدها.
     *
     * ولماذا هذا مهمّ: المشغّل يُسلَّم ملفَّ ترجمةٍ لا قائمة تشغيل، وكانت
     * النسخة السابقة تجلب القوائم الـ١٩ لتستخرج ملفّاتها — ٤ إلى ٧ ثوانٍ
     * (وتحت الحمل نجح ١٤/١٩ ثم ٩/١٩، لأن الـCDN يخنق الطلبات المتزامنة).
     * وصفرُ جلبٍ يعني أن الترجمات تصل مع الروابط في النداء نفسه، فلا تضيع
     * على مستمعٍ رحل — فالتطبيق يُعيد النتيجة دفعةً واحدة بعد رجوع `loadLinks`.
     */
    private suspend fun emitSubtitles(
        subs: List<SubTrack>,
        id: String,
        ep: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (subs.isEmpty()) return
        for (t in subs.distinctBy { it.lang }) {
            // رابط مسار الترجمة ← ملفّه الوحيد: `<مجلّده>/<اللغة>-00001.vtt`
            val dir = t.url.substringBeforeLast('/', "")
            val vtt = if (dir.isEmpty()) t.url else "$dir/${t.lang}-00001.vtt"
            Log.d(TAG, "sub ${t.lang} id=$id ep=$ep url=$vtt")
            subtitleCallback(
                newSubtitleFile(subLangLabel(t.lang), vtt) {
                    this.headers = mapOf("Referer" to mainUrl)
                },
            )
        }
    }

    /** جلب قائمة تشغيلٍ من الـCDN — فشلُه لا يُسقط البثّ، يعود `null`. */
    private suspend fun fetchPlaylist(url: String): String? = try {
        val r = app.get(url, referer = mainUrl)
        if (r.isSuccessful) r.text else {
            Log.e(TAG, "playlist ${r.code} $url")
            null
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "playlist FAILED $url", e)
        null
    }

    /**
     * تفكيك قائمة HLS إلى درجاتها وترجماتها.
     *
     * الجودة = **الأصغر** من العرض والارتفاع: الفيديو عمودي (٥٤٠×٩٦٠، ٧٢٠×١٢٨٠،
     * ١٠٨٠×١٩٢٠) فالعرض هو الدلالة لا الارتفاع — و`min` تصحّ للعمودي والأفقي معاً.
     * ويُقرأ الوسم من `RESOLUTION`، فإن غاب فمن مسار الدرجة (`video/720p/…`).
     */
    private fun parsePlaylist(text: String, playlistUrl: String): ParsedPlaylist {
        val dir = playlistUrl.substringBeforeLast('/', playlistUrl) + "/"
        val lines = text.lines()
        val variants = ArrayList<Variant>()
        val subs = ArrayList<SubTrack>()
        var i = 0
        while (i < lines.size) {
            val ln = lines[i].trim()
            when {
                ln.startsWith("#EXT-X-STREAM-INF") -> {
                    // الرابط على أول سطرٍ تالٍ غير معلَّق (قد تليه وسوم).
                    var j = i + 1
                    while (j < lines.size && lines[j].trim().startsWith("#")) j++
                    val uri = lines.getOrNull(j)?.trim().orEmpty()
                    if (uri.isNotEmpty() && !uri.startsWith("#")) {
                        variants.add(Variant(resolve(dir, uri), variantQuality(ln, uri)))
                        i = j
                    }
                }

                ln.startsWith("#EXT-X-MEDIA") && ln.contains("TYPE=SUBTITLES") -> {
                    val lang = attr(ln, "LANGUAGE")
                    val uri = attr(ln, "URI")
                    if (!lang.isNullOrBlank() && !uri.isNullOrBlank()) {
                        subs.add(SubTrack(resolve(dir, uri), lang))
                    }
                }
            }
            i++
        }
        return ParsedPlaylist(variants, subs)
    }

    /** الجودة من `RESOLUTION` ثم من مسار الدرجة. */
    private fun variantQuality(inf: String, uri: String): String? {
        val wh = RE_RESOLUTION.find(inf)?.let {
            it.groupValues[1].toIntOrNull() to it.groupValues[2].toIntOrNull()
        }
        val fromRes = wh?.let { (w, h) ->
            val m = listOfNotNull(w, h).minOrNull()
            m?.takeIf { it >= 144 }?.let { "${it}p" }
        }
        return fromRes ?: qOf(uri)
    }

    /** قيمة وسمٍ في سطر `#EXT-X-…` — `LANGUAGE="ar"` ← `ar`. */
    private fun attr(line: String, key: String): String? =
        Regex(key + "=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /** تحويل مسارٍ نسبيّ إلى مطلق مقابل مجلّد القائمة أو أصل الموقع. */
    private fun resolve(dir: String, uri: String): String = when {
        uri.startsWith("http://") || uri.startsWith("https://") -> uri
        uri.startsWith("//") -> "https:$uri"
        uri.startsWith("/") -> RE_ORIGIN.find(dir)?.groupValues?.get(1)?.plus(uri) ?: uri
        else -> dir + uri
    }

    /** رابط مطلق — الـAPI يعيد مسارات كاملة دائماً، والمشغّل لا يضيف mainUrl. */
    private fun abs(u: String): String = resolve("$mainUrl/", u)

    /** الجودة من المسار — نمط CDN CrazyMaple نفسه في DramaVideoShow. */
    private fun qOf(url: String): String? = when {
        "-ld." in url || "_ld." in url -> "540p"
        "-sd." in url || "_sd." in url -> "720p"
        "-hd." in url || "_hd." in url -> "1080p"
        RE_PATH_QUALITY.containsMatchIn(url) ->
            RE_PATH_QUALITY.find(url)!!.groupValues[1] + "p"
        else -> null
    }

    private companion object {
        val RE_RESOLUTION = Regex("""RESOLUTION=(\d+)x(\d+)""")
        val RE_PATH_QUALITY = Regex("""_(\d{3,4})/""")
        val RE_ORIGIN = Regex("""^(https?://[^/]+)""")

        /**
         * علامة القائمة التكيّفية: `/h264/` في مسار `videoUrl`.
         *
         * مقياسٌ على ١٠٣ أعمال (٢٠٢٦-١٠-٢٠): صفر مخالفة — كل عملٍ مساره يحوي
         * `/h264/` أعطى قائمةً بثلاث درجات و١٩ ترجمة، وكل ما عداه قائمةً
         * مفردةً بلا درجات ولا ترجمات. والعلامة تُفحَص **قبل** أي جلب، فالمفردة
         * (١٠٢ من ١٠٣) تُبَثّ فوراً بلا انتظار.
         */
        val ADAPTIVE_PATH = Regex("""/h264/""")

        /** سقف انتظار القائمة التكيّفية — بعد انتهائه يُبَثّ رابطها كما هو. */
        const val PLAYLIST_BUDGET_MS = 6_000L
    }
}
