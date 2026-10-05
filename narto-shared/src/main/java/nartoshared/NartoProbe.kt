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
        val host = u.substringAfter("//").substringBefore("/").lowercase()
        if (!host.endsWith("narto-drama.com")) return true   // tiktok/akamai/etc: leave alone
        val isProxy = u.contains("/e/m/")
        // A proxy answers 200 even when the src behind it is "link expired", so read the
        // body and look for the marker instead of trusting the status code.
        val probeBody = isProxy
        val readBytes = 256
        // MEASURED 2026-10-03 from the phone: a joyreels-stream token answers DIFFERENTLY
        // depending on the Range header, and that difference is the whole bug:
        //     with    "Range: bytes=0-1"  -> HTTP 403  "joyreels-edge: invalid token"
        //     without any Range header  -> HTTP 410  "joyreels-edge: link expired"
        // So the old bytes=0-1 probe made a DEAD link look broken-in-the-probe (FileNotFound)
        // and the player — which sends no Range — got the real 410 and errored. i.e. we were
        // judging liveness on a request shape the player never makes. Probe exactly what the
        // player does: a bare GET, no Range.
        //
        // RE-MEASURED 2026-10-04: a LIVE joyreels token ignores Range outright — both shapes
        // return HTTP 200 application/vnd.apple.mpegurl, 6807 bytes, in the same ~610 ms. So the
        // header bought nothing on a good link and inverted the verdict on a bad one. Only the
        // /e/m/ proxy body-probe ever needs one (it must read text, not stream bytes).
        val useRange = probeBody
        deadLinkCache[u]?.let { at ->
            if (System.currentTimeMillis() - at < DEAD_LINK_TTL_MS) {
                lastWhy = "cached dead"
                // A remembered verdict is still a verdict, not an inability to tell.
                deadByVerdict = true
                return false
            }
        }
        return try {
            val httpConn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            httpConn.apply {
                requestMethod = "GET"
                setRequestProperty("Referer", referer)
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                if (useRange) setRequestProperty("Range", "bytes=0-$readBytes")
                connectTimeout = 3000
                readTimeout = 2500
                instanceFollowRedirects = true
            }
            val code = httpConn.responseCode
            if (code !in 200..399) {
                lastWhy = "HTTP $code"
                deadByVerdict = true
                deadLinkCache[u] = System.currentTimeMillis()
                return false
            }
            if (probeBody) {
                val body = httpConn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val expired = body.contains("link expired") || body.contains("invalid token")
                if (expired) {
                    lastWhy = "body says link expired"
                    deadByVerdict = true
                    deadLinkCache[u] = System.currentTimeMillis()
                }
                !expired
            } else {
                true
            }
        } catch (e: Exception) {
            // A dead TLS cert lands here as SSLHandshakeException — that is how
            // cdn.narto-drama.com gets caught, so name it rather than reporting a bare
            // "dead token".
            lastWhy = "${e.javaClass.simpleName}: ${e.message?.take(60) ?: "-"}"
            // Split the two exception families, because they mean opposite things.
            // A BAD CERTIFICATE is a verdict about the link: the bytes are there (the same host
            // serves a 26MB mp4 over a relaxed handshake — measured 2026-10-04) but every
            // strict client, ExoPlayer included, refuses it, so emitting it guarantees
            // "Source error". A DNS/TIMEOUT failure says nothing about the link and must not be
            // treated as a verdict — that case exists to let the last-resort branch hand the
            // player a possibly-live URL.
            if (e is javax.net.ssl.SSLHandshakeException) deadByVerdict = true
            deadLinkCache[u] = System.currentTimeMillis()
            false
        }
    }
}