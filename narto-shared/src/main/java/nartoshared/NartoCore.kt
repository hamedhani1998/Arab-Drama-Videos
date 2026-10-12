package nartoshared

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/**
 * Shared Narto backend logic. The two sources — "Narto Drama" (apex) and
 * "Edge Narto Drama" (edge host) — are the same provider pointed at different
 * hosts of one backend, so everything except the host lives here.
 *
 * WHY THIS FILE EXISTS: the two providers were separate copies that drifted. Every
 * defect fixed in one (the stale-token retry, the cancellation rethrow, the cooldown
 * cap, the dead-by-verdict rule) stayed live in the other, and the drift was silent —
 * both compiled, both shipped. One copy cannot drift from itself.
 *
 * Compiled into both .cs3 packages via srcDir in each module's build.gradle.kts; the
 * two providers keep separate packages and separate log tags, so nothing collides when
 * CloudStream loads both at once.
 */

internal val mapper = ObjectMapper().registerKotlinModule()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

internal const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// How long a successful refresh-source payload may be reused before we ask the API again.
// The tokens inside it are signed and short-lived, so this must stay well under their
// lifetime — 30 s only covers "re-open the episode you just watched", not a different one.
internal const val REFRESH_CACHE_TTL_MS = 30 * 1000L

// How many times we re-request a stale payload before accepting it as un-refreshable.
// MEASURED 2026-10-05: the first call after an ingest lands, the second 25 s later.
// Three attempts cover the usual refresh window without holding the episode open longer.
internal const val STALE_RETRIES = 3

// Gap between two consecutive requests for the same episode. MEASURED 2026-10-05 on the
// device: without a gap the second request lands 845 ms after the first and the server
// immediately puts that episode on cooldown. Three seconds clears that short window
// without a long wait, because waiting longer does not help here: MEASURED across 8
// episodes, this slice's links are born dead (410 on arrival), so a longer wait buys nothing.
//
// RE-MEASURED 2026-10-06: the gap still draws HTTP 429 (retry_after_seconds=45), so the second
// request is no longer expected to succeed — it is what TELLS us the server's own window. The
// wait that follows is then bounded by FETCH_DEADLINE_MS instead of being slept out blind.
internal const val STALE_RETRY_GAP_MS = 3000L

// Budget for the whole refresh-source phase inside loadLinks. CloudStream wraps loadLinks in
// withTimeout(120_000), and we used to sleep out the server's retry_after (180 s) with
// Thread.sleep, which a coroutine cannot interrupt — so the app cancelled at 120 s while the
// sleeping thread ran on, and the FATAL landed at EXACTLY sleep_start + 180 s, measured twice
// on the device 2026-10-06:
//     11:51:10.669 COOLDOWN waiting=180000ms -> 11:54:10.671 TimeoutCancellationException
//     11:51:14.597 COOLDOWN waiting=180000ms -> 11:54:14.597 loadLinks FATAL
// i.e. a two-minute spinner that could only ever end in "no links".
//
// No wait may START past the deadline, and the hard wrapper sits 5 s under it so the deadline
// logic — not a timeout — is what decides. Probes afterwards cost at most ~17 s (3 s connect /
// 2.5 s read; the resolutions run in parallel), so 95 s + 17 s stays clear of the app's 120 s.
// A server window that cannot fit inside the deadline is reported instead of slept out, because
// sleeping it out is exactly the failure above.
internal const val FETCH_DEADLINE_MS = 90_000L
internal const val FETCH_HARD_TIMEOUT_MS = 95_000L

// Hosts we never hand to the player. `montagehub` (sulao.montagehub.xyz) is genuinely dead —
// NXDOMAIN even from 1.1.1.1 while the apex resolves through Cloudflare, so a link on it can
// never play. MEASURED 2026-10-11.
//
// `cdn.narto-drama.com` is NOT dead — it was wrongly exiled here from a measurement that used
// Windows-Python's OS trust store, which alone reports its cert as "expired". Re-measured
// 2026-10-11 from the JDK cacerts (Mozilla-derived, like Android) and from openssl's Mozilla
// store: BOTH handshake it fine — leaf CN=narto-drama.com, Let's Encrypt YE2, valid through
// Nov 13 2026 — and a bare GET on a fresh token returns the 5 MB mp4. So the "dead" listing
// made «كامل» skip a WORKING direct and substitute a low shortmax token (or nothing on works
// whose multi_resolutions is []), leaving the player with a montagehub dead link — the "no
// playback" bug. Removed 2026-10-11.
internal val DEAD_HOST_PATTERNS = listOf("montagehub")

// Dead verdicts, with a short TTL. joyreels hands out signed tokens that go stale fast, so
// remembering the answer keeps a replay from re-spending the whole probe timeout on it.
//
// Concurrent: probes now run in parallel (emitBatch), so this map has several writers at once.
// A plain LinkedHashMap corrupts under that (lost entries, and a resize can spin); a
// ConcurrentHashMap cannot, and its per-key reads/writes are exactly the shape used here.
internal val deadLinkCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
internal const val DEAD_LINK_TTL_MS = 5 * 60 * 1000L

// Narto playback API. Narto aggregates short-drama from MANY backends (shortmax, NetShort,
// StardustTV, mydramawave, ...). Each work's direct_play_url/play_url/multi_resolutions may be
// an HLS playlist OR a direct MP4 file — so loadLinks detects the container per link.
internal data class NartoResponse(
    val ok: Boolean? = null,
    val message: String? = null,
    val canonical: String? = null,          // full canonical URL hint on slug_mismatch
    @JsonProperty("retry_after_seconds") val retryAfterSeconds: Int? = null,
    @JsonProperty("direct_play_url") val directPlayUrl: String? = null,
    @JsonProperty("play_url") val playUrl: String? = null,
    // MEASURED 2026-10-06: present in 7/7 refresh payloads and previously dropped on the floor.
    // It is the API's own statement of the container, and the URL is not always enough to tell:
    // slug lms-lmhzwr hands a montagehub URL with no extension at all. See [inferStreamType].
    @JsonProperty("direct_play_is_hls") val directPlayIsHls: Boolean? = null,
    @JsonProperty("multi_resolutions") val multiResolutions: List<NartoResolution>? = null,
    @JsonProperty("multi_subtitles") val multiSubtitles: List<NartoSub>? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,
    @JsonProperty("direct_subtitle_url") val directSubtitleUrl: String? = null,
    @JsonProperty("selected_subtitle_language") val selectedSubtitleLanguage: String? = null,
    // MEASURED 2026-10-04: this boolean is the ONLY thing in the payload that distinguishes a
    // fresh token from a stale one. The API answers ok=true either way:
    //     source_refreshed=false -> joyreels token that is HTTP 410 the moment it arrives
    //                             (and the call itself took 13059 ms)
    //     source_refreshed=true  -> mydramawave, HTTP 200, 18 subtitles, 3 resolutions
    // so "ok" is not a promise of playability. Kept so the caller can tell a stale payload
    // from a live one instead of learning it from a 410.
    @JsonProperty("source_refreshed") val sourceRefreshed: Boolean? = null,
)

internal data class NartoResolution(
    val resolution: Int? = null,
    val label: String? = null,
    @JsonProperty("stream_url") val streamUrl: String? = null,
)

internal data class NartoSub(
    @JsonProperty("language_code") val languageCode: String? = null,
    val label: String? = null,
    @JsonProperty("subtitle_url") val subtitleUrl: String? = null,     // relative /e/s/{jwt}
)

// Subtitle lang tags. SubtitleFile.getLangTag() resolves a code through fromCodeToLangTagIETF
// and only falls back to fromLanguageToTagIETF; run against the app's own SubtitleHelper
// (CS3 jar, 2026-10-03):   "ar" -> ar      "ar-SA" -> null      "ترجمة" -> null
// A region-suffixed code from the API therefore yields a NULL tag and the player lists a track
// it cannot load. Keep the code, drop the region — the app renders the Arabic name from "ar".
internal fun String?.subLangTag(): String =
    this?.trim()?.takeIf { it.isNotBlank() }
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.takeIf { it.isNotBlank() } ?: "ar"

internal fun NartoSub.langTag(): String = languageCode.subLangTag()

// Minimal fake JWT the API accepts (claims are not verified, slug/ep are read from the path).
internal const val fakeRsCtx = "eyJhbGciOiJub25lIn0.eyJ2IjoiMSJ9."

// How many times a page GET is retried while the host keeps failing TRANSIENTLY.
// MEASURED 2026-10-12 against edge.narto-drama.com/detail/watch/1-flash-sale, 24 sequential
// GETs: 11x HTTP 200, 1x HTTP 500, 6x SSLHandshakeException, 6x SocketException. More than
// half the requests to that host fail at the transport layer while the SAME URL answers 200 on
// the next try — so a single attempt (what every page fetch here did) turns "the host is
// flaky" into "this show has no episodes" / "500 Internal Server Error" on screen. The retry
// is what separates the two.
internal const val PAGE_GET_ATTEMPTS = 4
internal const val PAGE_GET_RETRY_GAP_MS = 400L

// Fetch a page from a flaky host, retrying the transport/5xx failures that mean "try again",
// and NEVER handing back an error page.
//
// ★ Why the status check is not optional: CloudStream's app.get returns the BODY of a 500 as
// the response text, so the old `app.get(...).text` path parsed the literal
// "<title>500 Internal Server Error</title>" page as if it were a show page and found no
// episodes or no title. A URL that answered is not the same as a URL that succeeded.
// 4xx and 410 are verdicts about the URL (retrying cannot fix a 404) and are not retried;
// 5xx, TLS/Socket failures and DNS blips are transient and are.
internal suspend fun nartoGetPage(
    url: String,
    referer: String,
    tag: String,
    timeoutMs: Long = 30_000L,
): String? {
    var lastWhy = "-"
    for (attempt in 1..PAGE_GET_ATTEMPTS) {
        try {
            val res = com.lagradost.cloudstream3.app.get(
                url,
                referer = referer,
                headers = mapOf("User-Agent" to UA),
                timeout = timeoutMs,
            )
            val code = res.code
            val body = res.text
            if (code in 200..299 && !body.isNullOrBlank()) {
                // Some fronts answer 200 with an error document (a proxy's soft error). Treat
                // it as a failure so the retry can find the real page.
                if (!isErrorPage(body)) return body
                lastWhy = "soft-error page (HTTP 200)"
            } else {
                lastWhy = "HTTP $code"
                // A real verdict on the URL — retrying a 404/410 only burns the budget.
                if (code in 400..499) {
                    android.util.Log.e(tag, "page GET VERDICT HTTP $code url=${url.take(90)}")
                    return null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The user left / a newer search replaced this call: not a network failure.
            throw e
        } catch (e: Exception) {
            lastWhy = "${e.javaClass.simpleName}"
        }
        android.util.Log.e(
            tag,
            "page GET attempt=$attempt/$PAGE_GET_ATTEMPTS failed ($lastWhy) url=${url.take(90)}"
        )
        if (attempt < PAGE_GET_ATTEMPTS) {
            try {
                kotlinx.coroutines.delay(PAGE_GET_RETRY_GAP_MS * attempt)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }
    android.util.Log.e(tag, "page GET gave up after $PAGE_GET_ATTEMPTS ($lastWhy) url=${url.take(90)}")
    return null
}

// A 200 whose body is an error document, not a page. Checked on the top of the body only —
// a real page can legitimately contain the word "error" somewhere.
internal fun isErrorPage(body: String): Boolean {
    val head = body.take(1200)
    return head.contains("Internal Server Error") ||
        head.contains("500 Internal Server Error") ||
        Regex("""<title>\s*(500|502|503|504)\b""").containsMatchIn(head)
}

// The server messages that mean "no playable source right now", as opposed to a network failure.
internal val UNAVAILABLE_MESSAGES = setOf(
    "stream_temporarily_unavailable",
    "refresh_source_recently_failed",
    "refresh_source_cooldown_active",
)