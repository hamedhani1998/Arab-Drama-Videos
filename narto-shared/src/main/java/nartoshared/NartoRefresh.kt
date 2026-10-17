package nartoshared

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches the refresh-source payload, shared by both Narto sources.
 *
 * [hosts] is the whole difference between them: the apex provider passes its own host first
 * (falling back to edge for network/DNS failure), the edge provider passes edge first.
 */
internal class NartoFetch(
    private val hosts: List<String>,
    private val referer: String,
    private val tag: String,
) {
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val refreshCache = LinkedHashMap<String, Pair<Long, NartoResponse>>()

    /** ok=true but source_refreshed=false = a link that is HTTP 410 at the player. */
    private fun isStale(r: NartoResponse?): Boolean =
        r != null && r.ok == true && r.sourceRefreshed == false

    /**
     * هل يتّسع [ms] من الآن إلى [deadlineAt]؟ يُسجَّل السبب حين لا يتّسع، فالعودة الصامتة
     * كانت تُقرأ في السجلّ «فشل شبكة» بينما الحقيقة أنّ نافذة الخادم أكبر من عمر النداء.
     */
    private fun withinDeadline(deadlineAt: Long, ms: Long, what: String): Boolean {
        val now = System.currentTimeMillis()
        if (now + ms <= deadlineAt) return true
        android.util.Log.e(
            tag,
            "fetchRefresh SKIP $what — needs ${ms}ms but only ${deadlineAt - now}ms remain " +
                "before the loadLinks deadline (a wait that long cannot finish inside it)"
        )
        return false
    }

    suspend fun fetch(slug: String, ep: String, deadlineAt: Long): NartoResponse? {
        val key = "$slug/$ep"
        val lock = refreshLocks.computeIfAbsent(key) { Mutex() }
        lock.lock()
        try {
            refreshCache[key]?.let { (at, resp) ->
                if (System.currentTimeMillis() - at < REFRESH_CACHE_TTL_MS) {
                    android.util.Log.e(tag, "fetchRefresh CACHE hit slug=$slug ep=$ep")
                    return resp
                }
            }
            val fresh = fetchUncached(slug, ep, deadlineAt)
            // A payload that says ok:true but source_refreshed=false is a RE-SERVED STALE token:
            // measured 2026-10-04, such a play_url is HTTP 410 the instant it reaches the player.
            // Asking again is the only lever we have — the upstream ingest is what actually
            // re-mints the token, and it flips to true on a later call for the same episode.
            // Skip the shared cache for a stale payload, or we would replay the dead one.
            if (isStale(fresh)) {
                android.util.Log.e(
                    tag,
                    "fetchRefresh STALE payload (source_refreshed=false) — re-requesting slug=$slug ep=$ep"
                )
                // ونعيد حتى يصبح منعشاً فعلاً. الكود كان يرجع بعد محاولة واحدة، فيخزّن
                // رابطاً ميتاً (410) ويعلن الأمر ناجحاً — والسجلّ من 2026-10-05 يُظهر ذلك:
                //   STALE payload → re-request → still stale (ok=null msg=null)
                //   ثم CACHE hit أعاد تلك النتيجة الفاشلة مرتين أخريين، فصارت ثلاث فتحات
                //   للـepisode كلّها بلا رابط، والرابط الوحيد الذي أعطاه الخادم بـok=true
                //   كان منتهياً (410).
                var again = fresh!!
                var tries = 0
                while (tries < STALE_RETRIES) {
                    if (!isStale(again)) break
                    tries++
                    android.util.Log.e(
                        tag, "fetchRefresh stale retry $tries/$STALE_RETRIES slug=$slug ep=$ep"
                    )
                    // انتظارٌ قبل كلّ طلبٍ متتالٍ. بدونه كان الطلب الثاني يقع في نفس
                    // اللحظة تقريباً فيرفع الخادم الـcooldown على الحلقة نفسها فتنتهي
                    // المحاولاتُ بـ«ما زال منتهياً» بلا فائدة — قِسنا ذلك على الجهاز:
                    // retry 1/3 عند 47.799 ثم COOLDOWN عند 48.644، أي بعد 845 ميلي فقط.
                    //
                    // delay بدل Thread.sleep: النوم بـThread.sleep لا يقبل إلغاء النداء،
                    // فيبقى loadLinks حياً بعد أن يقول التطبيق إنه ألغاه — قِسنا ذلك
                    // 2026-10-06 حين أظهر الـFATAL دائماً بعد انتهاء النوم لا بعد الإلغاء.
                    if (!withinDeadline(deadlineAt, STALE_RETRY_GAP_MS, "stale gap")) break
                    delay(STALE_RETRY_GAP_MS)
                    val next = fetchUncached(slug, ep, deadlineAt)
                    if (next == null) break
                    again = next
                }
                if (again !== fresh && again.ok == true && again.sourceRefreshed != false) {
                    refreshCache[key] = System.currentTimeMillis() to again
                    android.util.Log.e(tag, "fetchRefresh recovered after $tries slug=$slug ep=$ep")
                    return again
                }
                android.util.Log.e(
                    tag,
                    "fetchRefresh still stale after $tries tries slug=$slug ep=$ep " +
                        "(ok=${again.ok} msg=${again.message} refreshed=${again.sourceRefreshed})"
                )
            }
            // رابط منتهٍ لا يُخزَّن: العنصر التالي يخزّن fresh فقط لو لم يكن منتهياً، وإلا
            // أعاد CACHE hit الميتَ في كل فتح لاحق بلا أي طلب شبكة.
            if (fresh != null && !isStale(fresh)) {
                refreshCache[key] = System.currentTimeMillis() to fresh
            }
            return fresh
        } finally {
            lock.unlock()
            // Drop the lock once nobody waits on it, so the map cannot grow with every episode.
            if (!lock.isLocked) refreshLocks.remove(key, lock)
        }
    }

    private suspend fun fetchUncached(slug: String, ep: String, deadlineAt: Long): NartoResponse? {
        // Try each host in order; final host = the other one (never the same twice).
        var waited = false
        var lastErr: Exception? = null
        for (h in hosts) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                // لا نبدأ طلباً جديداً خارج الموعد النهائي: app.get مُعطى 30 ثانية، وقد يتجاوز
                // بطلبٍ واحد حدّ التطبيق البالغ 120 ثانية قبل أن يصل الوقت إلى الفحص التالي.
                if (System.currentTimeMillis() >= deadlineAt) {
                    android.util.Log.e(
                        tag,
                        "fetchRefresh DEADLINE before attempt host=$h attempt=$attempt " +
                            "slug=$slug ep=$ep — returning now rather than crossing the loadLinks limit"
                    )
                    return null
                }
                try {
                    val tl0 = System.currentTimeMillis()
                    val body = app.get(
                        "$h/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx",
                        referer = referer,
                        timeout = 30000L
                    ).text
                    val ms = System.currentTimeMillis() - tl0
                    val resp = mapper.readValue(body, NartoResponse::class.java)
                    if (resp.ok != true && (resp.message == "refresh_source_recently_failed" ||
                            resp.message == "refresh_source_cooldown_active")
                    ) {
                        if (waited) {
                            android.util.Log.e(
                                tag,
                                "fetchRefresh COOLDOWN persists slug=$slug ep=$ep retryAfter=${resp.retryAfterSeconds}"
                            )
                            return null
                        }
                        waited = true
                        // Honour the server's own number. MEASURED 2026-10-05: this endpoint
                        // answers retry_after_seconds = 180, and the old 25 s cap meant the
                        // window could not possibly close, so the retry drew the same cooldown
                        // and the episode always ended empty after a 27 s wait. The cap now
                        // matches what the server actually asks for (180 s) — still bounded, so a
                        // hostile value cannot hang loadLinks forever, but no longer guaranteed
                        // to be too short to ever succeed.
                        val waitMs = ((resp.retryAfterSeconds ?: 15).coerceIn(4, 180)) * 1000L
                        // الخادم يطلب 45 ثانية في بعض الحالات و180 في أخرى، وحدّ التطبيق
                        // لـloadLinks هو 120 ثانية. نومُ 180 ثانية بـThread.sleep كان يعني:
                        // التطبيق يلغي عند 120 ثانية، والنوم يكمل إلى 180، ثم يظهر الـFATAL —
                        // قِسناه مرتين 2026-10-06 بالضبط (180.000 و180.002 ثانية). فننتظر
                        // ما يقع داخل الموعد (و45 ثانية تكفي فعلياً) ونعود فوراً لما لا يقع،
                        // لأنّ انتظارٌ لا يمكن الوصول إليه يعني شاشة تدور دقيقتين ثم فشل.
                        if (!withinDeadline(deadlineAt, waitMs, "cooldown retry_after=${waitMs}ms")) {
                            return null
                        }
                        android.util.Log.e(
                            tag, "fetchRefresh COOLDOWN slug=$slug ep=$ep waiting=${waitMs}ms"
                        )
                        delay(waitMs)
                        continue
                    }
                    android.util.Log.e(
                        tag,
                        "fetchRefresh OK host=$h slug=$slug ep=$ep ${ms}ms ok=${resp.ok} " +
                            "play=${resp.directPlayUrl?.take(50) ?: resp.playUrl?.take(50)}"
                    )
                    return resp
                } catch (e: Exception) {
                    // An explicit user/caller cancellation is NOT a network error. Catching it
                    // here burned ~2.4 s of retries and Thread.sleep AFTER the app had already
                    // walked away, then reported the episode as having no links (measured
                    // 2026-10-05 12:25:45, four "JobCancellationException" lines followed by
                    // "ALL HOSTS FAILED lastErr=Job was cancelled"). Rethrow so loadLinks
                    // returns at once.
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastErr = e
                    android.util.Log.e(
                        tag, "fetchRefresh ERROR host=$h attempt=$attempt/2 slug=$slug ep=$ep", e
                    )
                    if (attempt < 2) {
                        delay(800)
                    }
                }
            }
        }
        android.util.Log.e(
            tag,
            "fetchRefresh ALL HOSTS FAILED slug=$slug ep=$ep lastErr=${lastErr?.message?.take(80)}"
        )
        // MEASURED 2026-10-03 on the phone: when the device resolver is briefly blind (the
        // capture shows UnknownHostException for apex, edge AND cdn within the same second) both
        // hosts die inside ~2 s and the whole episode goes empty. A resolver blip is not a dead
        // source — wait it out and try once more, rather than handing the user a page with no links.
        if (lastErr is java.net.UnknownHostException) {
            for (backoff in listOf(1500L, 3000L)) {
                if (!withinDeadline(deadlineAt, backoff, "dns backoff=$backoff")) return null
                delay(backoff)
                android.util.Log.e(tag, "fetchRefresh DNS blip retry after ${backoff}ms slug=$slug ep=$ep")
                for (h in hosts) {
                    if (System.currentTimeMillis() >= deadlineAt) return null
                    try {
                        val body = app.get(
                            "$h/e/rs/detail/watch/$slug/$ep/refresh-source?rs_ctx=$fakeRsCtx",
                            referer = referer,
                            timeout = 30000L
                        ).text
                        val resp = mapper.readValue(body, NartoResponse::class.java)
                        if (resp.ok == true) {
                            android.util.Log.e(
                                tag, "fetchRefresh RECOVERED after DNS blip host=$h slug=$slug ep=$ep"
                            )
                            return resp
                        }
                    } catch (e: Exception) {
                        // Same rule as above: a cancelled job is not a resolver blip, and
                        // retrying it would keep loadLinks alive after the user left.
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastErr = e
                    }
                }
            }
        }
        return null
    }
}