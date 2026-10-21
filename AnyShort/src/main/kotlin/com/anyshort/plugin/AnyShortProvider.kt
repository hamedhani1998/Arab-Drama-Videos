package com.anyshort.plugin

import android.content.SharedPreferences
import android.util.Log
import cloudstreamshared.FormatTag
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

// أسماء الحقول في الـAPI snake_case (title_id, cover_url…) فتُعلَّق حقلًا حقلًا
// بـ @JsonProperty كما في بقية مصادر المستودع (Drama4All وDramadunyam…) —
// تسميةٌ عامة على المُحلِّل تُجريبٌ غير مُختبَر مع مُنشئات Kotlin، وخطأُ
// التسمية يصمت: الحقل يقرأ null ولا يُسقط شيئاً.
private val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

private const val TAG = "AnyShort"

/** لغة الاستعلامات في الـAPI — الموقع يخدم العربية على مسارات `/ar`. */
private const val API_LANG = "ar"

/** سقف صفحات الحلقات: حدّ أمانٌ للحلقات المتتابعة بلا نهاية (قِيس: أقصى عمل ٧٩ حلقة). */
private const val EPISODE_PAGE = 60
private const val EPISODE_MAX = 600

// ============================== نماذج الـAPI ==============================

/** بطاقة عملٍ كما تردّ في الرئيسية والبحث والتصفّح — الاسم والمعرّف والغلاف. */
private data class Card(
    @JsonProperty("title_id") val titleId: Int? = null,
    val name: String? = null,
    val slug: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null,
    val source: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
)

/** قسمٌ من أقسام `/v1/home` — عناوين تلقائية (`auto`) أو محرّرية (`manual`). */
private data class Rail(
    val title: String? = null,
    @JsonProperty("title_key") val titleKey: String? = null,
    val kind: String? = null,
    val ref: String? = null,
    val items: List<Card>? = null,
)

private data class Spotlight(
    val mode: String? = null,
    @JsonProperty("tag_id") val tagId: Int? = null,
    val items: List<Card>? = null,
)

private data class HomeData(
    val spotlight: Spotlight? = null,
    val rails: List<Rail>? = null,
)

// كل استجابةٍ ملفوفةٌ `{data, meta}` (قِيس 2026-10-21 على home/title/play/subs
// وsearch/episodes) — وقوائم الحلقات والبحث تحمل `page` على نفس المستوى.
// قراءة النماذج من النصّ الأصلي مباشرة تُعطي null في كل حقلٍ (الحقول في
// `data`)، والواجهة والتفاصيل والبثّ كلّها تصير فارغة.
private data class HomeResp(val data: HomeData? = null)

private data class TitleResp(val data: TitleDetail? = null)

private data class PlayResp(val data: PlayStreamList? = null)

/** محتوى `data` في `/play` — عائلة المصدر وصيغته ودرجاته. */
private data class PlayStreamList(
    val source: String? = null,
    val protocol: String? = null,
    val streams: List<PlayStream>? = null,
)

/** ظرف `data` في البحث والتصفّح — الصفحة خارجه ولا نحتاجها (صفحة واحدة لكلٍّ). */
private data class DataList(val data: List<Card>? = null)

private data class TagName(val name: String? = null)

private data class Gating(val mode: String? = null)

private data class TitleDetail(
    @JsonProperty("title_id") val titleId: Int? = null,
    val name: String? = null,
    val slug: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    val tags: List<TagName>? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    val source: String? = null,
    val gating: Gating? = null,
)

private data class EpisodeItem(
    @JsonProperty("episode_id") val episodeId: Long? = null,
    @JsonProperty("episode_no") val episodeNo: Int? = null,
    @JsonProperty("has_streams") val hasStreams: Boolean? = null,
    val access: String? = null,
    val locked: Boolean? = null,
)

private data class PageMeta(
    val limit: Int? = null,
    val offset: Int? = null,
    @JsonProperty("has_more") val hasMore: Boolean? = null,
)

private data class EpisodesResp(
    val data: List<EpisodeItem>? = null,
    val page: PageMeta? = null,
)

/** درجةٌ واحدة كما تردّ في `/play` — الرابط الموقّع وجودتها وصيغتها. */
private data class PlayStream(
    val quality: String? = null,
    val codec: String? = null,
    val protocol: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val url: String? = null,
    @JsonProperty("expire_at") val expireAt: String? = null,
    val stale: Boolean? = null,
)

private data class SubTrack(
    @JsonProperty("language_code") val languageCode: String? = null,
    @JsonProperty("language_name") val languageName: String? = null,
    val format: String? = null,
    val url: String? = null,
)

private data class SubsResp(val data: List<SubTrack>? = null)

class AnyShortProvider(private val prefs: SharedPreferences? = null) : MainAPI() {
    override var name = "AnyShort"
    override var mainUrl = "https://anyshort.net"
    override var lang = "ar"
    override val hasMainPage: Boolean
        get() = prefs?.getBoolean(AnyShortSettings.KEY_SHOW_HOME, true) != false
    override var hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    // ============================== إعدادات الوحدة ==============================

    private fun showFormatTag(): Boolean =
        prefs?.getBoolean(AnyShortSettings.KEY_FORMAT_TAG, true) != false

    private fun showSubs(): Boolean =
        prefs?.getBoolean(AnyShortSettings.KEY_SHOW_SUBS, true) != false

    private fun homeRowCap(): Int? = prefs?.getString(AnyShortSettings.KEY_HOME_ROWS, "all")
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    private fun descOrder(): Boolean =
        prefs?.getString(AnyShortSettings.KEY_EPISODE_ORDER, "as_is") == "desc"

    // ============================== جلسة الـAPI ==============================

    // الـAPI كلّه خلف `/v1` فوق نطاق الموقع نفسه، وبمفتاح جلسةٍ يُكتسب بـ
    // مصافحة `POST /auth/session` (platform=web) ثم يُرسَل `Bearer` في كل طلب —
    // نفس ما تفعله واجهة الموقع (`api.js`). الرمز يُخزَّن هنا ويُجدَّد تلقائياً
    // عند 401 (انتهاء أو إبطال)، مرةً واحدة لكل طلب.
    @Volatile
    private var token: String? = null

    private suspend fun handshake(): String? = try {
        val r = app.post(
            "$mainUrl/v1/auth/session",
            json = mapOf("platform" to "web"),
            headers = mapOf("accept" to "application/json"),
            referer = mainUrl,
        )
        if (r.isSuccessful) {
            val t = mapper.readTree(r.text)?.path("data")?.path("access_token")?.asText()
                ?.takeIf { it.isNotBlank() }
            token = t
            t
        } else {
            Log.e(TAG, "handshake ${r.code}")
            null
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "handshake FAILED", e)
        null
    }

    /** جسم JSON من `/v1/...` فوق نطاق الموقع — فشله يعود `null` ولا يُسقط شيئًا. */
    private suspend fun apiJson(path: String): String? {
        val t0 = token ?: handshake() ?: return null
        return try {
            val r = app.get(
                mainUrl + path,
                headers = mapOf("authorization" to "Bearer $t0"),
                referer = mainUrl,
            )
            if (r.isSuccessful) return r.text
            if (r.code != 401) {
                Log.e(TAG, "api ${r.code} $path")
                return null
            }
            // رمزٌ منتهٍ أو مُبطَل — مصافحةٌ جديدة ثم إعادة المحاولة مرةً واحدة.
            token = null
            val t1 = handshake() ?: return null
            val r2 = app.get(
                mainUrl + path,
                headers = mapOf("authorization" to "Bearer $t1"),
                referer = mainUrl,
            )
            if (r2.isSuccessful) r2.text else {
                Log.e(TAG, "api ${r2.code} $path (after re-handshake)")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "api FAILED $path", e)
            null
        }
    }

    // ============================== البطاقات والروابط ==============================

    /** رابط التفاصيل: `/ar/film/{slug}-{id}` — المعرّف دائماً آخر جزء كما في الموقع. */
    private fun detailUrl(id: Int, slug: String?, fallbackName: String?): String {
        val s = slug?.trim().takeUnless { it.isNullOrBlank() }
            ?: fallbackName?.trim()?.replace(Regex("""\s+"""), "-")
            ?: "film"
        return "$mainUrl/ar/film/$s-$id"
    }

    private fun card(c: Card): SearchResponse? {
        val id = c.titleId ?: return null
        val title = c.name?.trim().takeUnless { it.isNullOrBlank() } ?: return null
        return newTvSeriesSearchResponse(title, detailUrl(id, c.slug, title), TvType.TvSeries) {
            posterUrl = c.coverUrl?.takeIf { it.isNotBlank() }
            val n = c.totalEpisodes
            if (n != null && n > 0) this.episodes = n
        }
    }

    private fun queryParam(raw: String, key: String): String? =
        Regex("""[?&]$key=([^&#\s]+)""").find(raw)?.groupValues?.get(1)

    /** معرّف العمل من رابط تفاصيله: آخر رقمٍ في المسار قبل أي استعلام. */
    private fun titleIdFrom(raw: String): Int? =
        Regex("""/(\d+)(?:[/?#]|$)""").find(raw.substringBefore('?'))?.groupValues?.get(1)?.toIntOrNull()

    // ============================== الواجهة الرئيسية ==============================

    /** عناوين الأقسام التلقائية تردّ بلغة الموقع الافتراضية (فيتنامية قِيس
     * 2026-10-21: «Mới Cập Nhật») — نضع العربية الثابتة بدل النصّ الأجنبي. */
    private fun railTitle(r: Rail): String {
        if (r.kind == "auto") when (r.ref) {
            "newest" -> return "الأحدث"
            "popular" -> return "الأكثر رواجًا"
        }
        return r.title?.trim().takeUnless { it.isNullOrBlank() } ?: "قسم"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        return try {
            val text = apiJson("/v1/home?lang=$API_LANG") ?: return null
            val h = mapper.readValue(text, HomeResp::class.java).data ?: return null
            val rows = ArrayList<HomePageList>()
            h.spotlight?.items.orEmpty().mapNotNull { card(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { rows.add(HomePageList("المختار", it)) }
            val cap = homeRowCap()
            for (r in h.rails.orEmpty()) {
                val cards = r.items.orEmpty().mapNotNull { card(it) }
                if (cards.isEmpty()) continue
                rows.add(HomePageList(railTitle(r), cards))
                if (cap != null && rows.size >= cap) break
            }
            if (rows.isEmpty()) return null
            newHomePageResponse(rows)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage FAILED", e)
            null
        }
    }

    // ============================== البحث ==============================

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val path = "/v1/search?q=" + URLEncoder.encode(q, "UTF-8") + "&lang=$API_LANG&limit=24"
            val text = apiJson(path) ?: return null
            mapper.readValue(text, DataList::class.java).data.orEmpty().mapNotNull { card(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "search FAILED q=$query", e)
            null
        }
    }

    // ============================== التفاصيل والحلقات ==============================

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val id = titleIdFrom(url) ?: run {
                Log.e(TAG, "load no title id url=$url")
                return null
            }
            val tText = apiJson("/v1/titles/$id?lang=$API_LANG") ?: return null
            val t = mapper.readValue(tText, TitleResp::class.java).data ?: return null
            val title = t.name?.trim().takeUnless { it.isNullOrBlank() } ?: return null

            // الحلقات تأتي مصفوفةً مستقلّة (لا في التفاصيل)، وتُصفَّح ٦٠ً في
            // الطلبة — أقصى عملٍ قِيس 79 حلقة، والسقف يمنع جولةً بلا نهاية.
            val eps = ArrayList<EpisodeItem>()
            var offset = 0
            while (offset < EPISODE_MAX) {
                val eText = apiJson(
                    "/v1/titles/$id/episodes?lang=$API_LANG" +
                        "&limit=$EPISODE_PAGE&offset=$offset",
                ) ?: break
                val resp = mapper.readValue(eText, EpisodesResp::class.java)
                val batch = resp.data.orEmpty()
                if (batch.isEmpty()) break
                eps.addAll(batch)
                if (resp.page?.hasMore != true) break
                offset += EPISODE_PAGE
            }

            val numbered = eps.filter { it.episodeId != null && it.episodeNo != null }
            val ordered = if (descOrder()) numbered.sortedByDescending { it.episodeNo }
            else numbered.sortedBy { it.episodeNo }

            val episodes = ordered.map { ep ->
                // معرّف الحلقة يُحمَل في الاستعلام: `/play` و`/subtitles` يطلبان
                // `episode_id` لا رقمها، ومعرّف العمل وحده لا يكفي.
                newEpisode("${detailUrl(id, t.slug, title)}?ep=${ep.episodeNo}&eid=${ep.episodeId}") {
                    episode = ep.episodeNo
                    name = "الحلقة ${ep.episodeNo}"
                }
            }
            if (episodes.isEmpty()) Log.e(TAG, "load no episodes id=$id title=$title")

            newTvSeriesLoadResponse(title, detailUrl(id, t.slug, title), TvType.TvSeries, episodes) {
                t.coverUrl?.takeIf { it.isNotBlank() }?.let {
                    posterUrl = it
                    backgroundPosterUrl = it
                }
                t.description?.takeIf { it.isNotBlank() }?.let { plot = it }
                t.tags.orEmpty().mapNotNull { it?.name?.takeIf { n -> n.isNotBlank() } }
                    .takeIf { it.isNotEmpty() }?.let { tags = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "load FAILED $url", e)
            null
        }
    }

    // ============================== سيرفرات التشغيل ==============================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val eid = queryParam(data, "eid")?.toLongOrNull()
            val ep = queryParam(data, "ep")?.toIntOrNull()
            Log.d(TAG, "loadLinks raw=$data eid=$eid ep=$ep")
            if (eid == null) {
                Log.e(TAG, "loadLinks unparsable data=$data")
                return false
            }

            // روابط `/play` موقّعة وتنتهي خلال ساعات (حقل `expire_at` قِيس
            // 2026-10-21: ~16 ساعة) — تُطلب هنا مع كل بثّ ولا تُخزَّن أبداً.
            val pText = apiJson("/v1/episodes/$eid/play?lang=$API_LANG") ?: return false
            val play = mapper.readValue(pText, PlayResp::class.java).data ?: return false
            val streams = play.streams.orEmpty().filter { !it.url.isNullOrBlank() }
            if (streams.isEmpty()) {
                Log.e(TAG, "loadLinks no streams eid=$eid ep=$ep")
                return false
            }

            // الصيغة المُعلَنة: `protocol` في الاستجابة (`mp4` | `hls`). تُمرَّر
            // إلى الوسم لئلا يُخمَن من رابطٍ موقّعٍ بلا امتداد (روابط tiktokcdn
            // بلا `.mp4` في المسار إطلاقاً — قِيس 2026-10-21).
            val declared = when (play.protocol?.lowercase()) {
                "hls" -> "M3U8"
                "mp4" -> "MP4"
                else -> play.protocol?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
            }

            // الترتيب كما يرتبه موقعه نفسه (`streamSources` في theoplayer.js):
            // h264 أولاً (توافق)، ثم الأعلى ارتفاعاً — والمشغّل يعكس الترتيب
            // بـ -priority، فالأول في القائمة أفضل افتراضاً.
            val sorted = streams.sortedWith(
                compareBy<PlayStream> { if (it.codec == "h264") 0 else 1 }
                    .thenByDescending { it.height ?: 0 },
            )

            for (s in sorted) {
                val u = s.url ?: continue
                val isHls = s.protocol.equals("hls", ignoreCase = true) ||
                    u.contains(".m3u8", ignoreCase = true)
                val type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val q = s.quality?.trim().takeUnless { it.isNullOrBlank() }
                    ?: s.height?.let { "${it}p" }
                    ?: "Auto"
                val linkName = if (showFormatTag()) {
                    FormatTag.tagged(q, u, type, declared)
                } else {
                    q
                }
                Log.d(TAG, "emit eid=$eid ep=$ep q=$q hls=$isHls url=$u")
                callback(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = u,
                        type = type,
                    ) {
                        this.quality = getQualityFromName(q)
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to mainUrl)
                    },
                )
            }

            if (showSubs()) emitSubtitles(eid, ep, subtitleCallback)

            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks FATAL data=$data", e)
            false
        }
    }

    /**
     * ملفّات الترجمة من `/episodes/{id}/subtitles` — تردّ ملفات VTT مباشرة
     * (`format: webvtt`) بروابط موقّعة، لكل لغةٍ ملفّها كما يعلن `language_name`
     * («العربية» قِيس 2026-10-21). أعمال `netshort` وحدها تحملها؛
     * `pinedrama` تترجم داخل الصورة فلا تردّ شيئاً هنا — وذلِك لا يُسقط البثّ.
     */
    private suspend fun emitSubtitles(
        eid: Long,
        ep: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val text = apiJson("/v1/episodes/$eid/subtitles?lang=$API_LANG") ?: return
        val subs = mapper.readValue(text, SubsResp::class.java).data.orEmpty()
            .filter { !it.url.isNullOrBlank() }
        for (s in subs) {
            val label = s.languageName?.trim().takeUnless { it.isNullOrBlank() }
                ?: s.languageCode?.trim().takeUnless { it.isNullOrBlank() }
                ?: "ترجمة"
            Log.d(TAG, "sub eid=$eid ep=$ep lang=$label url=${s.url}")
            subtitleCallback(
                newSubtitleFile(label, s.url!!) {
                    this.headers = mapOf("Referer" to mainUrl)
                },
            )
        }
    }
}
