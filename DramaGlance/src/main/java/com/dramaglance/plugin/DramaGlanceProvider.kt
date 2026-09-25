package com.dramaglance.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

private val mapper = ObjectMapper()

private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * DramaGlance — موقع عربي لدراما قصيرة عمودية (فحص فعلي 2026-09).
 *
 * فخاخ الموقع الثلاثة (مهمة — لو فات أحدها يسقط المصدر):
 *  1) بادئة /ar على الصفحة الرئيسية فقط. صفحة التفاصيل لا تأخذ بادئة:
 *     /ar/drama/{slug} → 404، بينما /drama/{slug} → 200 ويعود بالعربية إذا كان
 *     المسلسل عربياً (لغة المحتوى تتبع المسلسل نفسه لا البادئة).
 *  2) البحث كذلك بلا بادئة: /search/{الكلمة} (مسار all-features.js submitSearch).
 *     /ar/search/... → 404.
 *  3) video.dramaglance.com و cdn.dramaglance.com خلف Cloudflare: طلب عارٍ
 *     (حتى بلا User-Agent) يُرجع 403 "Just a moment...". ينجح الطلب فقط بترويسات
 *     المتصفح الكاملة (User-Agent + Referer + Origin + Accept) — لذلك تُمرَّر
 *     في ExtractorLink.headers إضافةً إلى referer.
 *
 * بنية الصفحات:
 *  - الرئيسية /ar: div.module-poster-item.module-item، وثلاثة أقسام
 *    (أحدث الإصدارات / الأكثر تداولاً / الأعلى تقييماً). الأول يُبنى بجافاسكربت
 *    فيبقى فارغاً في HTML، فنستخدم القسمين المعبّئين.
 *  - المكتبة /ar/genres/...: نفس شكل البطاقات، وهي الطريق الوحيد للتصفح
 *    الأوسع (الرئيسية لا تكشف سوى 20 مسلسلاً).
 *  - البحث: div.module-card-item.module-item (شكل مختلف عن الرئيسية، ونصوصه
 *    إنجليزية دائماً لأن /search غير مربوط بمCrysoftca عربية).
 *  - التفاصيل: سكربت var player_aaaa={...} فيه encrypt و url مشفّرة بـ
 *    URL-encoding (encrypt:1)، و encrypt:2 = base64.
 *
 * حدود الموقع (مهم): الموقع يعرض معاينة مجانية فقط — ملف m3u8 واحد يجمع
 * أول 8–16 حلقة، والباقي يُحال إلى تطبيق DramaBox/ShortMax. ومسار
 * /movie/{slug}/sid/1/nid/{N} يرجع 500، فلا existe حلقة منفردة ولا سلسلة كاملة
 * من طرف الموقع. لذلك المصدر يعرض المعاينة الحرة ويذكر عدد حلقات المعاينة
 * صراحةً في اسم الحلقة بدل أن يوهم بمشاهدة السلسلة كاملة.
 */
class DramaGlanceProvider : MainAPI() {
    override var name = "DramaGlance"
    override var mainUrl = "https://www.dramaglance.com"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // مسارات بلا بادئة اللغة كما هي (ملاحظة: /ar وحده يعمل في الرئيسية).
    override val mainPage = mainPageOf(
        "ar/genres/id/1" to "كل المسلسلات",
        "ar/genres/by/time/year/2025" to "الأحدث 2025",
        "ar/genres/by/hits/year/2025" to "الأكثر مشاهدة 2025",
        "ar/genres/year/2025" to "عام 2025",
    )

    // ---- ترويسات Cloudflare: بدونها يرفض الموقع كل طلب (403) ----
    private fun browserHeaders(referer: String = mainUrl) = mapOf(
        "User-Agent" to UA,
        "Referer" to "$referer/",
        "Origin" to mainUrl,
        "Accept" to "*/*",
    )

    /**
     * بطاقات MAIN: div.module-poster-item.module-item
     * العنوان في .module-poster-item-title، والغلاف في .module-item-pic img
     * (data-original لأن التحميل كسول)، وسنة الإصدار في .module-item-caption.
     */
    private fun parsePosterCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        for (card in doc.select("div.module-poster-item.module-item")) {
            try {
                val a = card.selectFirst("a[href*=/drama/]") ?: continue
                val href = a.attr("href")
                val slug = href.substringAfterLast("/drama/").substringBefore("?")
                if (slug.isBlank() || !seen.add(slug)) continue

                val title = card.selectFirst(".module-poster-item-title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: continue

                val poster = card.selectFirst(".module-item-pic img")?.let {
                    it.attr("data-original").ifBlank { it.attr("src") }
                }?.trim()?.takeIf { it.isNotBlank() }?.let { abs(it) }

                out.add(newMovieSearchResponse(title, "$mainUrl/drama/$slug", TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * بطاقات البحث: div.module-card-item.module-item (شكل مختلف عن الرئيسية)
     * العنوان في .module-card-item-title، والغلاف في .module-item-pic img،
     * وعدد الحلقات في .module-item-note ("45 episodes").
     */
    private fun parseSearchCards(doc: Document): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()
        for (card in doc.select("div.module-card-item.module-item")) {
            try {
                val a = card.selectFirst("a[href*=/drama/]") ?: continue
                val slug = a.attr("href").substringAfterLast("/drama/").substringBefore("?")
                if (slug.isBlank() || !seen.add(slug)) continue

                val title = card.selectFirst(".module-card-item-title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: continue

                val poster = card.selectFirst(".module-item-pic img")?.let {
                    it.attr("data-original").ifBlank { it.attr("src") }
                }?.trim()?.takeIf { it.isNotBlank() }?.let { abs(it) }

                out.add(newMovieSearchResponse(title, "$mainUrl/drama/$slug", TvType.TvSeries) {
                    this.posterUrl = poster
                })
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun abs(u: String): String = if (u.startsWith("http")) u else "$mainUrl$u"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            // لا ترقيم صفحات في الموقع (?page=2 يعيد نفس الصفحة) فصفحة>1 = نفس المحتوى.
            val doc = app.get("$mainUrl/${request.data}", referer = "$mainUrl/ar",
                headers = mapOf("User-Agent" to UA)).document
            val cards = parsePosterCards(doc)
            if (cards.isEmpty()) null else newHomePageResponse(request.name, cards)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return try {
            // المسار بلا بادئة لغة؛ تُرمّز الكلمة مرتين (الموقع يفكّها مرتين في all-features.js)
            val enc = URLEncoder.encode(URLEncoder.encode(q, "UTF-8"), "UTF-8")
            val doc = app.get("$mainUrl/search/$enc", referer = "$mainUrl/ar",
                headers = mapOf("User-Agent" to UA)).document
            parseSearchCards(doc)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يفكّ حقل url في player_aaaa حسب قيمة encrypt:
     *  1 = URL-encoding (الحالة الوحيدة المرصودة في الموقع)، 2 = base64.
     * يعيد رابط بث كامل، أو null إن تعذّر فكّه.
     */
    private fun decodePlayerUrl(raw: String, encrypt: Int): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            when (encrypt) {
                1 -> URLDecoder.decode(s, "UTF-8")
                2 -> String(android.util.Base64.decode(s, android.util.Base64.DEFAULT))
                else -> s
            }.trim().takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, referer = "$mainUrl/ar",
                headers = mapOf("User-Agent" to UA)).document

            val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: return null

            val poster = doc.selectFirst(".module-info-poster img")?.let {
                it.attr("data-original").ifBlank { it.attr("src") }
            }?.trim()?.takeIf { it.isNotBlank() }?.let { abs(it) }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

            val plot = doc.selectFirst("meta[name=description]")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() }

            // سنة الإصدار، التصنيف، وعدد الحلقات الكلي
            val year = doc.selectFirst(".module-info-tag-link a")?.text()?.trim()?.ifBlank { null }
            val genre = doc.selectFirst(".module-info-meta")?.text()?.trim()?.ifBlank { null }
            val totalText = doc.selectFirst(".module-info-meta-episodes")?.text()?.trim().orEmpty()
            val totalEp = Regex("(\\d+)").find(totalText)?.groupValues?.get(1)?.toIntOrNull()

            // player_aaaa: {flag, encrypt, url:"%68%74..."} — رابط المعاينة الوحيد.
            val playerRaw = Regex("""player_aaaa\s*=\s*(\{.*?\})\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                .find(doc.html())?.groupValues?.get(1)
            val player = playerRaw?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            val encrypt = player?.get("encrypt")?.asInt() ?: 1
            val playUrl = player?.get("url")?.asText()?.let { decodePlayerUrl(it, encrypt) }

            // «الحلقات 1-9» = المعاينة المجانية (ملف واحد يجمع أول 9 حلقات).
            val previewLabel = doc.selectFirst("span.preview-label")?.text()?.trim()?.ifBlank { null }

            val eps = mutableListOf<Episode>()
            if (playUrl != null) {
                // data يبدأ بعنوان http دائمًا (وإلا هشّله CloudStream كمسار نسبي)،
                // والرابط الحقيقي بعد أول "|".
                eps.add(newEpisode("$mainUrl/preview|$playUrl") {
                    episode = 1
                    name = previewLabel ?: "الحلقات المتاحة"
                })
            }

            // الوسم يوضّح للمستخدم أن ما سيشاهده هو معاينة مجانية فقط: الموقع
            // يعرض أول 8–16 حلقة من أصل السلسلة (45–100 حلقة)، والباقي عبر تطبيقه.
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = listOfNotNull(
                    year,
                    genre,
                    totalEp?.let { "الحلقات: $it" },
                    "معاينة مجانية فقط",
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val firstPipe = data.indexOf('|')
        if (firstPipe <= 0 || data.substring(0, firstPipe) != "$mainUrl/preview") return false
        val playUrl = data.substring(firstPipe + 1).trim()
        if (!playUrl.startsWith("http")) return false

        // رابط HLS واحد بلا تشفير (#EXT-X-KEY غير موجود) وبلا ترميزات (#EXT-X-MEDIA غير موجود)
        // → لا جودات متعددة ولا ترجمات لاستخراجها؛ نسلّمه كما هو بترويسات المتصفح.
        callback(newExtractorLink(name, "الحلقات المتاحة مجاناً", playUrl, ExtractorLinkType.M3U8) {
            this.headers = browserHeaders()
            // CloudStream يستخدم headers في نداءات app، لكن مشغّل الفيديو نفسه يبني
            // طلبه من Referer — نملأ الاثنين معًا وإلا رفض Cloudflare الطلب (403).
            this.referer = "$mainUrl/"
            this.quality = getQualityFromName("720p")
        })
        return true
    }
}
