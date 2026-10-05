package nartoshared

import com.lagradost.cloudstream3.app
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

    suspend fun fetch(slug: String, ep: String): NartoResponse? {
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
            val fresh = fetchUncached(slug, ep)
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
                    // وننتظر ثلاث ثوانٍ — لا نافذة الـcooldown كاملةً — لأنّ الانتظار الطويل
                    // هنا لا يشفي: قِستُ ٨ حلقات على هذا المصدر، روابطُها كلّها
                    // source_refreshed=false و HTTP 410 منذ لحظة إصدارها.
                    try {
                        Thread.sleep(STALE_RETRY_GAP_MS)
                    } catch (e2: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    val next = fetchUncached(slug, ep)
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

    private suspend fun fetchUncached(slug: String, ep: String): NartoResponse? {
        // Try each host in order; final host = the other one (never the same twice).
        var waited = false
        var lastErr: Exception? = null
        for (h in hosts) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
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
                        android.util.Log.e(
                            tag, "fetchRefresh COOLDOWN slug=$slug ep=$ep waiting=${waitMs}ms"
                        )
                        try {
                            Thread.sleep(waitMs)
                        } catch (e2: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
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
                        try {
                            Thread.sleep(800)
                        } catch (e2: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
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
                try {
                    Thread.sleep(backoff)
                } catch (e2: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                android.util.Log.e(tag, "fetchRefresh DNS blip retry after ${backoff}ms slug=$slug ep=$ep")
                for (h in hosts) {
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