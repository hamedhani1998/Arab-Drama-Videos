package nartoshared

/**
 * Liveness probe for Narto stream URLs, and the distinction the last-resort branch depends on.
 *
 * Every Narto stream host hands out a SIGNED, SHORT-LIVED token, and the ingest drops them at
 * arbitrary times — the live failure is a plain HTTP 410, not a parse bug. MEASURED 2026-10-03
 * from the phone (adb logcat, 12 failures in one browsing pass):
 *   30 mentions of joyreels-stream.narto-drama.com  -> Response code: 410
 *   5  mentions of shortmax-stream.narto-drama.com -> Response code: 410
 * while `loadLinks DONE ... deadSkipped=0` — the provider reported a healthy result and handed
 * the player links that were already dead. The old probe only ran for hosts containing
 * "shortmax-stream" or "/e/m/", so joyreels was returned as alive WITHOUT a request.
 */
internal class Probe(private val referer: String) {

    /**
     * Why the last probe failed, so "emit SKIP" names the cause (410, expired, TLS) instead of a
     * generic "dead token" that hides this whole class of bug.
     */
    var lastWhy: String = ""

    /**
     * Did any probe return a VERDICT (an HTTP status, or an expiry marker inside a body) rather
     * than simply failing to reach the host?
     *
     * This is the distinction that decides the last-resort branch: a verdict means the link is
     * dead and must NOT be handed to the player, whereas an exception means "could not tell" and
     * a live link may still be sitting there. Never infer this by parsing [lastWhy] — that is how
     * a verdict and a transport failure end up conflated again.
     */
    var deadByVerdict: Boolean = false

    fun isAlive(u: String): Boolean {
        val (alive, why, verdict) = probeLink(u)
        // isAlive runs on several threads at once: emitBatch and the multi_resolutions block
        // both fan out over one Probe. lastWhy/deadByVerdict are single-slot state a caller
        // reads immediately after a false verdict, so every write must be atomic and visible —
        // a torn or invisible verdict is exactly how the last-resort branch ends up emitting a
        // link the probe just rejected. (Last-writer-wins for the reason string is inherent to
        // a shared slot; the flag itself is only ever raised, as before.)
        synchronized(this) {
            lastWhy = why
            if (verdict) deadByVerdict = true
        }
        return alive
    }

    // The actual probe, with NO shared state: returns (alive, why, isVerdict).
    private fun probeLink(u: String): Triple<Boolean, String, Boolean> {
        // Probe every third-party host too, not just narto-drama.com. montagehub (sulao.
        // montagehub.xyz) is genuinely dead (NXDOMAIN) but is NOT on narto-drama.com, so the old
        // `return true` for anything non-narto handed the player a URL that can never play.
        // A DNS failure lands as an exception → deadByVerdict stays false → the last-resort
        // branch still has its escape hatch for transient resolver blips. A status (403/410) is
        // a verdict.
        val isProxy = u.contains("/e/m/")
        // A proxy answers 200 even when the src behind it is "link expired", so read the
        // body and look for the marker instead of trusting the status code.
        val probeBody = isProxy
        val readBytes = 256
        // The player sends a bare GET, NO Range. Probing WITH a Range inverted the verdict on
        // joyreels (403-with-Range vs 410-without) and nothing bought on a live link — MEASURED
        // 2026-10-03/04 below. Only the /e/m/ proxy body-probe ever needs a Range, so it can read
        // text and stop streaming bytes.
        val useRange = probeBody
        deadLinkCache[u]?.let { at ->
            if (System.currentTimeMillis() - at < DEAD_LINK_TTL_MS) {
                // A remembered verdict is still a verdict, not an inability to tell.
                return Triple(false, "cached dead", true)
            }
        }
        return try {
            val httpConn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            httpConn.apply {
                requestMethod = "GET"
                setRequestProperty("Referer", referer)
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                if (useRange) setRequestProperty("Range", "bytes=0-$readBytes")
                // MEASURED 2026-10-11: a 3 s connect timeout misread a SLOW handshake as a dead
                // cert — cdn.narto-drama.com's TLS handshake took 4.2 s and was then "dead" for
                // 5 minutes. These CDNs are routinely 3-5 s to respond. Bump the budget so the
                // probe reflects a real dead-or-alive, not whether it met an arbitrary clock.
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
            }
            val code = httpConn.responseCode
            if (code !in 200..399) {
                deadLinkCache[u] = System.currentTimeMillis()
                return Triple(false, "HTTP $code", true)
            }
            if (probeBody) {
                val body = httpConn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val expired = body.contains("link expired") || body.contains("invalid token")
                if (expired) {
                    deadLinkCache[u] = System.currentTimeMillis()
                    Triple(false, "body says link expired", true)
                } else {
                    Triple(true, "", false)
                }
            } else {
                Triple(true, "", false)
            }
        } catch (e: Exception) {
            // A dead TLS cert lands here as SSLHandshakeException — naming it distinguishes a
            // cert problem from a generic "dead token".
            val why = "${e.javaClass.simpleName}: ${e.message?.take(60) ?: "-"}"
            // Split the two exception families, because they mean opposite things.
            // A BAD CERTIFICATE is a verdict about the link: the bytes are there but every strict
            // client, ExoPlayer included, refuses it, so emitting it guarantees "Source error".
            // A DNS/TIMEOUT failure says nothing about the link and must not be treated as a
            // verdict — that case exists to let the last-resort branch hand the player a
            // possibly-live URL.
            val verdict = e is javax.net.ssl.SSLHandshakeException
            deadLinkCache[u] = System.currentTimeMillis()
            Triple(false, why, verdict)
        }
    }
}