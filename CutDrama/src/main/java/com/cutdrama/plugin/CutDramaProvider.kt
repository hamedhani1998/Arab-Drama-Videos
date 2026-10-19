package com.cutdrama.plugin

import cloudstreamshared.FormatTag
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val mapper = ObjectMapper().registerKotlinModule()

/** صفحة الاستكشاف — عدّاد الموقع نفسه. */
private const val PAGE_SIZE = 24

/**
 * نافذة ذاكرة الـJSON. قصيرة عن قصد: حقل `playback` المُدمج في صفحة المسلسل
 * يحمل روابط موقّعة ينتهي صلاحيتها بعد ‎٨٧ دقيقة‎ (قيس: `e=` في الرابط)،
 * فالذاكرة ‎٦٠ ثانية‎ لا تُقدّم رابطًا منتهيًا أبدًا، وتُغني من يفتح حلقات
 * المسلسل نفسه من جلب صفحة ‎٢٦٣ كيلوبايت‎ في كل مرّة.
 */
private const val CACHE_MS = 60_000L

/**
 * CutDrama — دراما قصيرة (cutdrama.cam). «حلقة كل دقيقة».
 *
 * الموقع SPA بلا صفحات مُخدَمة: كل شيء يُقرأ من `/api`، وهي **نفس العقود
 * التي يستهلكها تطبيق أندرويد** كما ينصّ تعليق الموقع نفسه.
 *
 * ★ لا يحتاج هذا المصدر إلى أي جلسة. كل ما نقرأه علني:
 *   - `/api/config`  → التصنيفات
 *   - `/api/home`    → الأقسام المرسّاة + الترشيحات
 *   - `/api/discover?limit=&offset=&sort=` → التصفّح بترقيم (total 450)
 *   - `/api/dramas?q=`                      → البحث
 *   - `/api/dramas/{id أو slug}`            → المسلسل + حلقاته
 *   - `/api/rankings?range=`                → قوائم التصنيف
 * نقفل الجلسة لأنّ `POST /api/episodes/{id}/playback` وحده يردّ 401 بلا توكن،
 * وليس لنا حاجة إليه: صفحة المسلسل **تُدمج `playback` جاهزًا لكل حلقة حرة**
 * بروابط موقّعة لـ`uid=0` (زائر)، وهي التي يُسخّن بها الموقع نفسه قبل الدخول.
 * قيس: `uid=0` يعطي 200 على القائمة و206 على الجزء بلا توكن ولا مُحيل.
 *
 * ★ الجودات كلّها في `playback.mirrors[]` — ولا شيء خارجها:
 *   - ٩ مرئيات محلّية بجودة ثابتة (H.264: 720/540/360/240، H.265: 720/540/480/360/240)
 *     كلٌّ منها قائمة أحادية البثّ `master.m3u8` تُظهر جودةً واحدة لا تتغيّر.
 *   - ٢ مرآة من المزوّد (`/media/px/…`) وهما **قائمة تكيّفية حقيقية** بخمس
 *     درجات (720/540/480/360/240) فيقفز المشغّل بينها حسب النطاق.
 *   وأمّا `locked_qualities: [1080]` فلا يُبثّ إلا لمشتركي VIP، فلا رابط له.
 *
 * ★ الترجمة: كل حلقة تحمل ٣١ لغة بصيغة VTT على `/api/episodes/{id}/subtitles/{sid}/vtt`.
 *   الرسوميّة تقول إنّ المسار «محمي للحلقة المقفلة»، والقياس يأكّد حدّه: المسار
 *   علني **للحلقات الحرة** (200 بلا توكن) و403 للحلقات المدفوعة. فلكلّ حلقة
 *   حرة ترجماتها الثلاث والثلاثين كاملة.
 *
 * ★ الحلقات المدفوعة (`access.locked`): `playback.type = "locked"` بلا رابط،
 *   وترجمتها 403 حتى بتوكن. تُدرَج في القائمة كي يظهر المسلسل كاملًا كما هو
 *   على الموقع، أمّا `loadLinks` فيُبقيها بلا سيرفر — لا اختراع لرابط.
 */
class CutDramaProvider : MainAPI() {
    override var name = "CutDrama"
    override var mainUrl = "https://cutdrama.cam"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val api = "$mainUrl/api"

    private val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * صفوف الصفحة الرئيسية. `data` هو نصّ الاستعلام كما يبنيه موقع الويب
     * في `browsePage` (`qsFor`) — نفس المعاملات حرفيًا، فلا نخترع بروتوكولًا.
     * الصفّ الأول «الرئيسية» يقرأ `/api/home` ويُظهر أقسام الموقع كما هي.
     */
    override val mainPage = mainPageOf(
        "الرئيسية" to "home",
        "الأكثر مشاهدة" to "sort=popular",
        "الأحدث" to "sort=latest",
        "الأعلى تقييمًا" to "sort=rating",
        "الأكثر حلقات" to "sort=episodes",
        "مدبلج للعربية" to "sort=popular&translation=dubbed",
        "دراما آسيوية" to "sort=popular&language=asia",
        "دراما غربية" to "sort=popular&language=west",
    )

    // ---------- كيان الموقع ----------

    private fun JsonNode.str(key: String): String? =
        get(key)?.takeIf { !it.isNull }?.asText()?.trim()?.ifBlank { null }

    private fun JsonNode.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isNull && it.isNumber }?.asInt()

    private fun JsonNode.arr(key: String): List<JsonNode> {
        val n = get(key)
        return if (n != null && n.isArray) n.toList() else emptyList()
    }

    /**
     * أسماء التصنيفات. في استجابات القوائم تأتي `genres` سلاسل عربية خامّة،
     * وفي صفحة المسلسل قد تأتي كائنات `{name_ar}` — والواجهة نفسها تحتتمل
     * الصورتين (`typeof g === 'string' ? g : g.name_ar`)، فنفعل ذلك هنا.
     */
    private fun JsonNode.genreNames(): List<String> =
        arr("genres").mapNotNull { g ->
            if (g.isTextual) g.asText().trim().ifBlank { null }
            else g.str("name_ar") ?: g.str("name")
        }.distinct()

    /** ذاكرة الـJSON: مفتاحها المسار كما يُطلب. */
    private val jsonCache = mutableMapOf<String, Pair<JsonNode, Long>>()
    private val jsonLock = Any()

    private fun nowMS(): Long = System.currentTimeMillis()

    private suspend fun fetch(path: String): JsonNode? {
        val hit = synchronized(jsonLock) {
            val e = jsonCache[path]
            if (e != null && nowMS() - e.second < CACHE_MS) e.first else null
        }
        if (hit != null) return hit
        val fresh = try {
            val res = app.get(
                "$api/$path",
                referer = "$mainUrl/",
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json")
            )
            mapper.readTree(res.text)
        } catch (_: Exception) {
            null
        }
        if (fresh != null) synchronized(jsonLock) { jsonCache[path] = fresh to nowMS() }
        return fresh
    }

    /** بطاقة مسلسل ← بطاقة بحث. الغلاف نسبي (`/uploads/covers/…`) فيُصلح. */
    private fun JsonNode.toCard(): SearchResponse? {
        val id = intOrNull("id") ?: return null
        val title = str("title") ?: return null
        val slug = str("slug")
        val url = if (!slug.isNullOrBlank()) "$mainUrl/drama/$slug" else "$api/dramas/$id"
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = str("poster")?.let { fixUrl(it) }
            this.year = intOrNull("year")
            // لا حقل ملخّص في بطاقة البحث بهذه النسخة (`plot`/`description`
            // غير موجودين على SearchResponse)، فيبقى الملخّص لصفحة التفاصيل.
            this.episodes = intOrNull("episode_count")
        }
    }

    private fun List<JsonNode>.mapCards(): List<SearchResponse> = mapNotNull { it.toCard() }

    // ---------- الصفحات ----------

    /**
     * صفّ «الرئيسية»: أقسام `/api/home` كما هي بأسمائها العربية. كلّ الأقسام
     * تُبنى من طلبٍ واحد، فلا تُكلِّف الظهور الأول بتسع طلبات متوازية.
     * القسم بترتيب `hero` يُرسَم بالبانرات في الموقع، ونحن نعرضه صفًّا عاديًا
     * لأنّ بطاقاته هي بطاقات الأعمال نفسها.
     */
    private suspend fun homeLists(): List<HomePageList> {
        val home = fetch("home") ?: return emptyList()
        val lists = home.arr("sections").mapNotNull { sec ->
            val title = sec.str("title") ?: return@mapNotNull null
            val items = sec.arr("items").mapCards()
            if (items.isEmpty()) null else HomePageList(title, items)
        }.toMutableList()
        home.arr("recommendations").mapCards().takeIf { it.isNotEmpty() }?.let {
            lists.add(HomePageList("مرشّح لك", it))
        }
        return lists
    }

    /**
     * الصفّان معًا: «الرئيسية» تقرأ `/api/home` وتُبقي أقسام الموقع بأسمائها،
     * وسائر الصفوف تقرأ `/api/discover` بالترتيب والفلتر كما يبنيهما موقع الويب
     * (`qsFor`)، فيترقّم كلٌّ منها بـ`offset` على حدة.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            if (request.data == "home") {
                if (page > 1) return newHomePageResponse(request.name, emptyList())
                newHomePageResponse(homeLists(), false)
            } else {
                val offset = (page - 1) * PAGE_SIZE
                val node = fetch("discover?limit=$PAGE_SIZE&offset=$offset&${request.data}")
                val items = node?.arr("items")?.mapCards().orEmpty()
                val total = node?.intOrNull("total") ?: 0
                newHomePageResponse(request.name, items, offset + items.size < total)
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            fetch("dramas?q=$encoded&limit=50")?.arr("items")?.mapCards()
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query) ?: emptyList()

    override suspend fun load(url: String): LoadResponse? {
        return try {
            // الموقع يقبل المعرّف الرقمي والاسم اللصيق معًا في المسار نفسه
            // (`/api/dramas/355` و`/api/dramas/sister-stole-…` يعيدان ٢٦٢٧٠٥
            // بايت بالضبط)، فيكفي أن نأخذ ما بعد آخر مقطوع.
            val tail = url.substringAfterLast("/").substringBefore("?")
            if (tail.isBlank()) return null
            val d = fetch("dramas/$tail") ?: return null
            val id = d.intOrNull("id") ?: return null
            val title = d.str("title") ?: return null

            val episodes = d.arr("episodes").mapNotNull { e ->
                val epId = e.intOrNull("id") ?: return@mapNotNull null
                val num = e.intOrNull("number") ?: return@mapNotNull null
                newEpisode("$id|$epId") {
                    this.episode = num
                    this.name = "الحلقة $num"
                }
            }.sortedBy { it.episode }
            if (episodes.isEmpty()) return null

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = d.str("poster")?.let { fixUrl(it) }
                this.backgroundPosterUrl = d.str("cover")?.let { fixUrl(it) }
                this.plot = d.str("synopsis")
                this.tags = d.genreNames() +
                    d.arr("tags").mapNotNull { it.asText()?.trim()?.ifBlank { null } }
                // لا `rating`: الحقل مهجور في هذه النسخة (`score` هو البديل)،
                // وقيمة الموقع `0` مع `rating_count = 0` في كلّ ما قيس — فالأفضل
                // ألّا نكتب وسماً صفريًّا يُقرأ تقييمًا.
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * ترتيب المرآة في قائمة السيرفرات — والمشغّل **يأخذ الأوّل**، فالحكم هنا
     * ليس تنسيقًا جماليًّا بل سرعة بدء التشغيل.
     *
     * H.264 قبل H.265 دائمًا، لسببين مقيسين:
     *   1. فكّ H.265 عتاديّ في الجهاز، فقد يمتنع المشغّل عن البثّ أصلًا.
     *   2. مرآة المزوّد H.265 تأرجحت في القياس: 200، 200، ثم مهلة ٢٥ ثانية
     *      مرّتين. مهلة الأصل لا يُصلحها كود، فتُؤخَّر بدلًا من أن تُخفى
     *      (المستخدم طلب **كل** الجودات) — تُترك في القائمة كي يجدها من
     *      يبحث عنها، ولا تكون أوّل ما يُجرَّب.
     * وH.264 الموزّع من المزوّد صار أوّلًا: تكيّفي يستقرّ على 720 حسب
     * النطاق، وقيس 200 في كلّ محاولة، بخلاف المرئيات الثابتة التي تُظهر
     * درجة واحدة لا تتغيّر.
     */
    private fun mirrorRank(m: JsonNode): Int {
        val provider = m.str("source") == "provider"
        val h265 = m.str("codec") == "h265" || m.str("id")?.startsWith("h265") == true ||
            m.str("url")?.contains("h265") == true
        return when {
            provider && !h265 -> 0
            !provider && !h265 -> 1
            provider && h265 -> 2
            else -> 3
        }
    }

    /** جودة المرآة. الحقل رقمي في المحلّي (`720`) ونصّي في المزوّد (`"h265"`). */
    private fun mirrorQuality(m: JsonNode): Int? = m.intOrNull("quality")

    /** اسم المرآة كما يسمّيه الموقع، أو اسمٌ مُشتقّ حين يترك الموقع بلا تسمية. */
    private fun mirrorLabel(m: JsonNode, quality: Int?): String {
        m.str("label")?.let { return it }
        val codec = m.str("codec")
            ?: if (m.str("id")?.startsWith("h265") == true || m.str("quality") == "h265") "h265" else "h264"
        val codecName = if (codec == "h265") "H.265" else "H.264"
        val adaptive = m.str("source") == "provider"
        val head = if (adaptive) "تكيّفي" else (quality?.let { "${it}p" } ?: "غير معروفة")
        return "$head · $codecName · " + if (adaptive) "المزوّد" else "الخادم"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|", limit = 2)
            if (parts.size < 2) return false
            val dramaId = parts[0].trim()
            val epId = parts[1].trim().toIntOrNull() ?: return false
            if (dramaId.isBlank()) return false

            // نقرأ من صفحة المسلسل لا من `/episodes/{id}/playback`: تلك ترفض
            // الزائر بـ401، وهذه تحمل نفس المرآة موقّعة لـ`uid=0` ومُدمجة أصلًا.
            val d = fetch("dramas/$dramaId") ?: return false
            val ep = d.arr("episodes").firstOrNull { it.intOrNull("id") == epId } ?: return false

            val locked = ep.get("access")?.get("locked")?.takeIf { !it.isNull }?.asBoolean(true) ?: true
            if (locked) return false

            val pb = ep.get("playback") ?: return false
            val mirrors = pb.arr("mirrors").filter { !it.str("url").isNullOrBlank() }
            if (mirrors.isEmpty()) return false

            // الترجمات — ٣١ لغة، والمسار علني لكلّ حلقة حرة. نمرّر الرمز
            // IETF كما هو (`ar-SA`) لا الاسم اللاتيني، كي لا يسقط الوسم.
            ep.arr("subtitles").forEach { st ->
                val path = st.str("path") ?: return@forEach
                val lang = st.str("lang") ?: return@forEach
                subtitleCallback(newSubtitleFile(subLangLabel(lang), fixUrl(path)))
            }

            mirrors.sortedWith(
                compareBy<JsonNode>({ mirrorRank(it) }, { -(mirrorQuality(it) ?: 0) })
            )
                .forEach { m ->
                    val raw = m.str("url") ?: return@forEach
                    val url = fixUrl(raw)
                    val quality = mirrorQuality(m)
                    val label = mirrorLabel(m, quality)
                    // وسم الصيغة في الاسم لا في الرابط: المشغّل لا يعرض إلا
                    // `name`، وكلّ ما هنا `.m3u8` → `[M3U8]`، فإن جاء mp4
                    // يومًا تغيّر الوسم وحده ولا يُلمَس الرابط ولا الهيدرات.
                    callback(
                        newExtractorLink(
                            name,
                            FormatTag.tagged(label, url, ExtractorLinkType.M3U8),
                            url,
                            ExtractorLinkType.M3U8
                        ) {
                            // بلا `referer`: القائمة والجزء والترجمة كلّها 200/206
                            // بلا مُحيل (قيس مباشر)، وسياسات الدلو تُسقط مُحيلًا
                            // معيّنًا بينما تقبل الغياب — فالغياب هو الأأمن هنا.
                            this.quality = if (quality != null && quality > 0)
                                getQualityFromName("${quality}p") else Qualities.Unknown.value
                        }
                    )
                }

            true
        } catch (_: Exception) {
            false
        }
    }
}
