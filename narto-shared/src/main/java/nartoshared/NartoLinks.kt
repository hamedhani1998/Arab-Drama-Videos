package nartoshared

import android.content.SharedPreferences
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The whole of loadLinks, shared by both Narto sources.
 *
 * Everything here is host-independent; the host arrives through [api].mainUrl and [origin].
 * This is the function that drifted furthest between the two providers — the Edge copy was
 * missing the stale retry, the cancellation rethrow, the parallel probe, the dead-by-verdict
 * rule and the last-resort guard, so a fix in one was invisible in the other.
 */
internal suspend fun loadNartoLinks(
    api: MainAPI,
    prefs: SharedPreferences?,
    // Named `origin`, NOT `referer`: inside the newExtractorLink builder lambda below,
    // `referer = referer` resolves the right-hand side to ExtractorLink.referer (the property
    // being assigned) instead of this parameter, so Kotlin reports "'val' cannot be reassigned".
    // A shadowed name that reads correctly is still a bug; this one is not shadowed.
    origin: String,
    tag: String,
    showFullKey: String,
    // مفتاح ترتيب الجودات (نصّ: "default" | "asc" | "desc") — لا مفتاح «إظهار كامل»،
    // فذلك منطقيّ ويُكتب بـ SwitchPreference. تمريره هنا هو ما جعل emitNartoSorted يقرأ
    // قيمةً منطقية بـgetString ويرمي ClassCastException بعد جمع الروابط كلّها: قِسناه
    // 2026-10-06 على الجهاز، الحلقة تُجمع ثم لا تُبثّ، ومسار الاستثناء يرمي ثانيةً ثانية.
    orderKey: String,
    fetch: NartoFetch,
    data: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    // روابط الحلقة تُجمَّع هنا أولاً ثم تُبثّ دفعةً واحدة عند المخرج، ليصل ترتيبها إلى
    // الـ callback كما اختار المستخدم (افتراضياً: كما هي تماماً). تُعرَّف قبل الـ try كي يتمكّن
    // مسار catch من بثّ ما جُمع قبل الخطأ أيضاً.
    val collected = mutableListOf<ExtractorLink>()
    return try {
        // إن أُلغي النداء فلا نُكمل: الابتلاع هنا كان يحوّل «غادر المستخدم الحلقة»
        // إلى «لا روابط»، وهي تهمةٌ لا تخصّ المصدر.
        currentCoroutineContext().ensureActive()
        val m = Regex("""/detail/watch/([^/?]+)/(\d+)""").find(data) ?: return false
        val ep = m.groupValues[2]
        var slug = m.groupValues[1]

        // مهلة واحدة تُوزَّع على كل استدعاءات الجلب في هذا النداء. الخادم قد يطلب نافذة
        // cooldown تصل 180 ثانية، وحدّ التطبيق لـloadLinks هو 120 ثانية؛ فالغلف الصلب هنا
        // يجعل الجلب يعود قبل ذلك مهما طال الانتظار، فلا يظهر TimeoutCancellationException
        // من التطبيق ولا يبقى النداء معلّقاً بعد أن أُلغي.
        val deadlineAt = System.currentTimeMillis() + FETCH_DEADLINE_MS
        var resp = withTimeoutOrNull(FETCH_HARD_TIMEOUT_MS) { fetch.fetch(slug, ep, deadlineAt) }
        if (resp == null) {
            // null means the CALL could not complete (network, the deadline, or the user left).
            // Name it: "NO EDGE" with no cause sent us chasing a host problem twice.
            android.util.Log.e(
                tag,
                "loadLinks no payload slug=$slug ep=$ep (fetch failed, hit the deadline, or was cancelled)"
            )
            return false
        }

        if (resp.ok != true && UNAVAILABLE_MESSAGES.contains(resp.message)) {
            // الخادم قال صراحةً أنّ المصدر غير متاح الآن. وloadLinks لا يستطيع إبلاغ
            // المستخدم بذلك (لا حقل رسالة في LoadResponse — تحقّقتُ من الـjar بـjavap)،
            // فالسجلّ هو المكان الوحيد الذي ينقل السبب.
            // وقِستُ 2026-10-05 أنّ هذا ليس استثناءً: 5 أعمال و13 حلقة، روابطُها كلّها
            // source_refreshed=false و HTTP 410/403 — عدا عملاً واحداً.
            android.util.Log.e(
                tag,
                "loadLinks SOURCE UNAVAILABLE slug=$slug ep=$ep msg=${resp.message} " +
                    "retryAfter=${resp.retryAfterSeconds} (upstream has no playable link)"
            )
            return false
        }

        if (resp.ok != true && resp.message == "slug_mismatch") {
            val canon = resp.canonical?.let {
                Regex("""/detail/watch/([^/?]+)/""").find(it)?.groupValues?.get(1)
            }
            if (canon != null && canon != slug) {
                android.util.Log.e(tag, "loadLinks slug_mismatch $slug -> $canon ep=$ep")
                slug = canon
                resp = withTimeoutOrNull(FETCH_HARD_TIMEOUT_MS) { fetch.fetch(slug, ep, deadlineAt) }
                if (resp == null) return false
            }
        }
        if (resp.ok != true) {
            android.util.Log.e(
                tag,
                "loadLinks ok!=true (continuing anyway) slug=$slug ep=$ep msg=${resp.message} " +
                    "play=${resp.directPlayUrl?.take(60)} res=${resp.multiResolutions?.size}"
            )
        }

        // 1) subtitles — every track the API returns.
        //
        // The subtitle HOST is the bug that made these rows appear and then fail. The site prints
        // the subtitle as a RELATIVE "/e/s/{jwt}" path, so the host is ours to choose, and we
        // were hardcoding the stream host. MEASURED 2026-10-03 on slug fkh-lgr eps 1/2/3, every
        // one answered by stream.narto-drama.com:
        //     HTTP 501  "local file tetap di VPS edge"   ← the edge VPS holds the file back
        // The same tokens on the apex host return 200 text/vtt with a real WEBVTT body. So apex
        // serves them and the stream host never did.
        val seenSubs = LinkedHashSet<String>()
        val subTracks = resp.subtitleTracks()
        for ((lang, rel) in subTracks) {
            val subUrl = if (rel.startsWith("http")) rel else api.mainUrl + rel
            if (!seenSubs.add(subUrl)) continue
            // Do NOT wrap this in try/catch. Swallowing it is how a subtitle row shipped that
            // the player could never load, with nothing in logcat to explain why.
            subtitleCallback(newSubtitleFile(lang, subUrl))
        }

        // The API's own container flag describes the DIRECT play asset only. A multi_resolutions
        // token is a different object (always an HLS master on this source) and a proxy url
        // resolves to a different src again, so the flag is returned ONLY for a URL that IS the
        // direct/play url it was written about — otherwise we would stamp "is_hls=true" from the
        // payload onto an asset it never described, replacing one wrong guess with another.
        fun apiHintFor(u: String): Boolean? {
            val direct = listOfNotNull(resp.directPlayUrl, resp.playUrl).map { it.trim() }
            return if (u.trim() in direct) resp.directPlayIsHls else null
        }

        val emitted = LinkedHashSet<String>()
        var any = false
        var skippedDead = 0
        val probe = Probe(origin)

        // Register an already-probed, already-vetted link. Split out of emit() so the parallel
        // quality probes can all finish BEFORE any registration, keeping «كامل» first and the
        // quality order the user picked.
        suspend fun emitNow(u: String, label: String, q: String, apiIsHls: Boolean? = null) {
            if (u.isBlank() || u in emitted) return
            emitted.add(u)
            val type = inferStreamType(u, apiIsHls)
            collected.add(
                newExtractorLink(source = api.name, name = label, url = u, type = type) {
                    referer = origin
                    quality = getQualityFromName(q)
                    headers = mapOf("Referer" to origin)
                }
            )
            any = true
        }

        suspend fun emit(u: String, label: String, q: String, apiIsHls: Boolean? = null) {
            // Dedup on the links we actually ACCEPT, not on every URL we merely looked at.
            // `emitted.add(u)` used to run BEFORE the probe, so a URL that failed the probe was
            // marked as seen for the rest of loadLinks and could never be retried — which
            // silently disabled every fallback that tried the same URL again.
            if (u.isBlank() || u in emitted) return
            val host = u.substringAfter("//").substringBefore("/").substringBefore(":").lowercase()
            if (DEAD_HOST_PATTERNS.any { host.contains(it) }) {
                skippedDead++
                android.util.Log.e(tag, "emit SKIP dead host $host ($label)")
                return
            }
            if (!probe.isAlive(u)) {
                skippedDead++
                android.util.Log.e(tag, "emit SKIP dead link $host ($label) why=${probe.lastWhy}")
                return
            }
            emitNow(u, label, q, apiIsHls)
        }

        // Decode a proxy ("/e/m/{jwt}") into the real signed src the provider intended. The src is
        // a normal HLS host (akamai-static.shorttv.live, video-v6.mydramawave.com, ...) — emitting
        // that host directly avoids the nested-relative-proxy infinite spin.
        suspend fun emitFromProxy(proxyUrl: String) {
            val src = jwtSrc(proxyUrl) ?: return
            // ★ «إظهار رابط كامل» = مفعّل افتراضياً، أي سلوك اليوم حرفياً. فقط حين يُطفئه
            //   المستخدم نتخطّى بثّ رابط «كامل» — ولا نلمس روابط الجودات.
            val showFull = prefs?.getBoolean(showFullKey, true) != false
            if (!src.contains("/e/m/") && showFull) {
                // If the proxy resolves to an akamai shorttv master, its segments are
                // `main/segment-N.ts` WITHOUT the auth_key → the raw master 403s mid-play. The
                // proxy re-wraps each segment as /e/s/{jwt} (with auth), so for akamai the SAFE
                // link is the proxy itself, not the raw src.
                if (src.contains("akamai-static.shorttv.live")) {
                    emit(proxyUrl, "كامل", "480p")
                } else {
                    emit(src, "كامل", proxyQuality(src))
                }
            }
            // Derive the sibling qualities when the URL still carries `{uuid}_{q}/main.m3u8`.
            // MEASURED 2026-10-02: shortmax no longer uses this shape — it is now
            // `/{token}/main.m3u8` with no `_{q}` and no query, so this regex does not match and
            // we return below. That is harmless: shortmax's own qualities arrive as distinct
            // signed tokens in multi_resolutions and are emitted verbatim, each labelled from the
            // API's own `resolution` field. This block still serves any other backend that keeps
            // the older shape.
            val mm = Regex("""(.+?)_(\d{3,4})(?:p)?/main\.m3u8(\?.*)""").find(src) ?: return
            // akamai raw variants would 403 on their auth-less segments — for akamai the proxy
            // re-wrap (emitted above) is the ONLY safe link, so do NOT add raw variants.
            if (src.contains("akamai-static.shorttv.live")) return
            val base = mm.groupValues[1]             // .../hls/{uuid}
            val query = mm.groupValues[3]            // ?auth_key=...
            val baseQ = mm.groupValues[2].toIntOrNull() ?: 480
            val ordered = listOf(1080, 720, 480).filter { it >= baseQ || it == 480 }
                .sortedByDescending { it }
            for (q in ordered) {
                val url = "${base}_${q}/main.m3u8$query"
                emit(url, "${q}p", "${q}p")
            }
        }

        // 1) "كامل" — prefer real CDN hosts over the /e/m/{jwt} proxy. A proxy's HLS has nested
        // root-relative /e/m/{jwt} variant/segment URLs that many players can't resolve, spinning
        // forever; the signed src host (or a direct m3u8/MP4 from the API) is what actually plays.
        // Emit every direct/CDN candidate, then fall back to the proxy only if there are none.
        val directs = listOfNotNull(resp.directPlayUrl, resp.playUrl)
            .map { it.trim() }
            .filter { it.isNotBlank() && !isProxyUrl(it) }
            .distinct()
        val proxies = listOfNotNull(resp.playUrl, resp.directPlayUrl)
            .map { it.trim() }
            .filter { it.isNotBlank() && isProxyUrl(it) }
            .distinct()

        var directEmitted = 0
        var directOk = 0
        // ★ «إظهار رابط كامل» — يُقرأ مرّة واحدة هنا: عند الإطفاء نتخطّى استخدام الروابط
        //   المباشرة (لأن موضع بثّها موسوم «كامل»)؛ الافتراضي true = نفس حلقة اليوم حرفياً.
        val showFull = prefs?.getBoolean(showFullKey, true) != false
        for (u in if (showFull) directs else emptyList()) {
            if (directEmitted >= 2) break
            // On slow CDNs (shortmax-stream) a 1080 master's big segments drain the buffer as fast
            // as it fills ("plays a bit then spins"). Prefer the 480 token so the default "كامل"
            // starts smooth; the multi_resolutions emissions below still give 1080/720 to the
            // quality picker.
            // MEASURED 2026-10-02 (re-confirmed): for shortmax the API's direct_play_url is on
            // cdn.narto-drama.com, whose certificate has EXPIRED — it is in DEAD_HOST_PATTERNS,
            // so emitting it as-is gives the player a guaranteed "Source error". Every quality of
            // the work lives in the signed shortmax-stream tokens instead, and those serve fine.
            // So when the API hands us a dead direct but the title has tokens, «كامل» becomes a
            // token. Pick the LOWEST one: the measured segments are 402KB @5s (480) / 578KB (720)
            // / 890KB (1080), and the 1080 drains the buffer faster than it refills on a slow
            // link. The higher ones still reach the user through the quality picker below.
            val shortmaxTokens = resp.multiResolutions.orEmpty()
                .filter { it.streamUrl?.contains("shortmax-stream") == true && it.streamUrl.isNotBlank() }
            val needsToken = u.contains("cdn.narto-drama.com") || u.contains("shortmax-stream")
            val picked = if (needsToken && !u.contains("/e/m/")) {
                shortmaxTokens.minWithOrNull(compareBy { it.resolution ?: 1080 })?.streamUrl
            } else null
            val pickedQ = picked?.let { pu -> shortmaxTokens.firstOrNull { it.streamUrl == pu } }
                ?.let { qualityOfRes(it) }
            val before = emitted.size
            emit(picked ?: u, "كامل", pickedQ ?: proxyQuality(u), apiHintFor(picked ?: u))
            if (emitted.size > before) directOk++
            directEmitted++
        }
        if (directOk == 0 && showFull) {
            // MEASURED 2026-10-03: the API's direct token is frequently already 410, and the
            // quality list is NOT always populated (multi_resolutions is [] for many works). So
            // before giving up on «كامل», re-try the same direct URL once — the ingest refreshes
            // tokens on each call, and the proxy below is a weaker option than a fresh token from
            // the same source. We log the outcome either way so a truly dead episode is
            // distinguishable from one we simply could not refresh.
            val retry = directs.firstOrNull { it.isNotBlank() && it !in emitted }
            if (retry != null) {
                val before = emitted.size
                emit(retry, "كامل", proxyQuality(retry), apiHintFor(retry))
                android.util.Log.e(
                    tag, "loadLinks retry-direct alive=${emitted.size > before} url=${retry.take(60)}"
                )
                if (emitted.size > before) directOk++
            }
        }
        if (directOk == 0) {
            // No live direct CDN host survived the probe (dead shortmax tokens) — fall back to the
            // proxy (stream-e1 /e/m) which may still be alive; decode it to its src.
            val p = proxies.firstOrNull()
            if (p != null) emitFromProxy(p)
        }

        // Emit multi_resolutions too. Live audit 2026-09-07: the API server handed us THREE
        // separate shortmax-stream signed tokens (1080/720/480) — all three returned HTTP 200 and
        // played (masters + segments) even ~25 min after refresh. Emitting them restores the
        // quality selector, and lets the player pick 480 on slow links. Only safe when the master
        // we emit is NOT a nested /e/m/{jwt} proxy (which would spin) — so gate each on a real
        // CDN host.
        //
        // MEASURED 2026-10-03 from the phone: loadLinks took 12.3 s before playback even started
        // (fetchRefresh 12296ms + three serial probes at ~0.6-3 s each). A serial probe spends
        // its whole timeout budget on the FIRST dead token before the player learns anything. The
        // probes are independent — one HTTP request each, no shared state — so run them together
        // and emit afterwards in the order the API listed them, which keeps «كامل» first and the
        // quality picker in the user's chosen order.
        val resCandidates = resp.multiResolutions.orEmpty()
            .map { res -> res to res.streamUrl?.trim().orEmpty() }
            .filter { (_, su) -> su.isNotBlank() && !isProxyUrl(su) }
        val probed = coroutineScope {
            resCandidates.map { (res, su) ->
                async(Dispatchers.IO) { Triple(res, su, probe.isAlive(su)) }
            }.awaitAll()
        }
        android.util.Log.e(
            tag,
            "loadLinks probed ${probed.size} qualities in parallel slug=$slug ep=$ep " +
                "dead=${probed.count { !it.third }}"
        )
        for ((res, su, ok) in probed) {
            if (!ok) {
                skippedDead++
                android.util.Log.e(tag, "emit SKIP dead resolution (${qualityOfRes(res)}) slug=$slug")
                continue
            }
            val label = res.label?.trim()?.takeIf { it.isNotBlank() }
                ?: "${res.resolution ?: 480}p"
            emitNow(su, label, qualityOfRes(res))
        }

        if (emitted.isEmpty()) {
            // Last resort: the API gave us at least one real URL — hand the player the raw direct
            // URL WITHOUT the isAlive probe (device DNS can be transiently flaky).
            //
            // MEASURED 2026-10-04 (adb logcat, slug lzl-lmkhtfy ep=1): this "last resort" is
            // exactly what produced the failure the user reported. The probe had just SKIPPED that
            // very URL as HTTP 410 (deadSkipped=3), and then this branch emitted it UNPROBED anyway:
            //     emit SKIP dead link joyreels-stream.narto-drama.com (كامل) why=HTTP 410
            //     loadLinks no links survived probes — emitting raw API URL slug=lzl-lmkhtfy
            //     loadLinks DONE ... links=0 subs=0 deadSkipped=3 any=true
            // and the player immediately failed on it, three times:
            //     ExoPlaybackException: Source error
            //     Caused by: InvalidResponseCodeException: Response code: 410
            // So the probe learned the link was dead, and 400 lines later we handed the player that
            // same dead link with a comment claiming it beat "no links". It did not beat it: the
            // user sees an error screen, which IS "no links" plus a failure toast.
            //
            // Keep the escape hatch for what it was actually for — a probe that could not tell
            // (DNS/TLS blip on the device), where an exception was thrown rather than a status
            // returned. A STATUS means dead, and a dead link must not reach the player.
            android.util.Log.e(
                tag,
                "loadLinks no links survived probes — deadByVerdict=${probe.deadByVerdict} " +
                    "why='${probe.lastWhy}' emitRaw=${!probe.deadByVerdict} slug=$slug"
            )
            val raw = if (probe.deadByVerdict) null else
                listOfNotNull(resp.directPlayUrl, resp.playUrl).firstOrNull { !it.isNullOrBlank() }
            if (!raw.isNullOrBlank()) {
                val t = inferStreamType(raw, apiHintFor(raw))
                collected.add(
                    newExtractorLink(source = api.name, name = "كامل", url = raw, type = t) {
                        referer = origin
                        quality = getQualityFromName("480p")
                        headers = mapOf("Referer" to origin)
                    }
                )
                any = true
            }
        }

        android.util.Log.e(
            tag,
            "loadLinks DONE slug=$slug ep=$ep links=${emitted.size} subs=${subTracks.size} " +
                "deadSkipped=$skippedDead any=$any"
        )
        emitNartoSorted(prefs, collected, orderKey, callback)
        any
    } catch (e: kotlinx.coroutines.CancellationException) {
        // غادر المستخدم الحلقة أو ألغى التطبيق النداء: ليس عطلاً في المصدر. طباعة
        // «loadLinks FATAL» هنا كانت تزدحم بها السجلّ فتُخفي الأخطاء الحقيقية.
        throw e
    } catch (e: Exception) {
        android.util.Log.e(tag, "loadLinks FATAL", e)
        // حتى عند الخطأ: ما جُمع قبله يُبثّ (سلوك اليوم: الروابط التي سبقت الاستثناء كانت
        // قد بُثّت أصلاً، فلا تضيع). وهذه المرة لا يرمي الاستثناء ثانيةً — كانت البثّة
        // الاحتياطية تمرّ بنفس المفتاح الخاطئ فتهرب من الـcatch وتُسقط النداء كله.
        emitNartoSorted(prefs, collected, orderKey, callback)
        false
    }
}

/**
 * بثّ روابط الحلقة بعد اكتمال تجميعها: «افتراضي» = نفس الترتيب تماماً كما كان (لا إعادة
 * ترتيب إطلاقاً)، و«تصاعدي/تنازلي» يعيدان الترتيب فقط — فرز مستقر فالمتساوية تحتفظ بترتيبها،
 * ولا حذف ولا تكرار ولا تغيير في العدد. تُستدعى مرة واحدة عند المخرج الوحيد من loadLinks.
 */
internal fun emitNartoSorted(
    prefs: SharedPreferences?,
    collected: List<ExtractorLink>,
    orderKey: String,
    callback: (ExtractorLink) -> Unit,
) {
    // قراءة آمنة: مفتاحٌ مكتوب بنوعٍ آخر (منطقيّ من SwitchPreference، أو عددٌ من SeekBar)
    // يجعل SharedPreferences.getString يرمي ClassCastException. قِسناه على الجهاز
    // 2026-10-06: تمرير مفتاح الإظهار بدل مفتاح الترتيب جمع روابط الحلقة كاملةً ثم ألقاها
    // بعيداً عند البثّ، ورمى ثانيةً في مسار الاستثناء فهرب من الـcatch. تفضيلٌ لا يُقرأ
    // لا يُسقط حلقةً بأكملها.
    val order = try {
        prefs?.getString(orderKey, "default")
    } catch (e: ClassCastException) {
        android.util.Log.e(
            "NartoLinks",
            "emitNartoSorted key=$orderKey is not a String (${e.message}) — keeping default order"
        )
        "default"
    }
    val sorted = when (order) {
        "asc" -> collected.sortedBy { it.quality }
        "desc" -> collected.sortedByDescending { it.quality }
        else -> collected
    }
    sorted.forEach { callback(it) }
}