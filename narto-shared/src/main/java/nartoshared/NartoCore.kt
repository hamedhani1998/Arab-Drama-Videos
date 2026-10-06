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

// Hosts we never hand to the player. `cdn.narto-drama.com` is the API's own "direct" host for
// shortmax works, but its TLS certificate is EXPIRED — measured 2026-10-02, valid-through date
// November 12, 2026 yet a strict handshake fails with "certificate has expired", so a link on
// this host can never play. The real qualities live in the signed shortmax-stream tokens, which
// serve fine (cert accepted, valid through December 1, 2026). Recoverable — drop this line once
// the host's cert is fixed.
internal val DEAD_HOST_PATTERNS = listOf("montagehub", "cdn.narto-drama.com")

// Dead verdicts, with a short TTL. joyreels hands out signed tokens that go stale fast, so
// remembering the answer keeps a replay from re-spending the whole probe timeout on it.
internal val deadLinkCache = LinkedHashMap<String, Long>()
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

// The server messages that mean "no playable source right now", as opposed to a network failure.
internal val UNAVAILABLE_MESSAGES = setOf(
    "stream_temporarily_unavailable",
    "refresh_source_recently_failed",
    "refresh_source_cooldown_active",
)