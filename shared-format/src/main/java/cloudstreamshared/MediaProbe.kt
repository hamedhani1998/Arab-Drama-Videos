package cloudstreamshared

import java.net.HttpURLConnection
import java.net.URL

/**
 * قياس **حيوية** رابط التشغيل و**حجم ملفّه** وزمن أوّل بايت، بطلبٍ واحد
 * محدود البايتات — بلا تنزيل فيديو.
 *
 * لماذا `Range: bytes=0-1` لا `HEAD`: قِيس على `cdn.full-drama.com` أنّ HEAD
 * يردّ ‎403‎ بينما GET يردّ ‎206‎ بالمحتوى نفسه — أي أنّ HEAD حكمٌ كاذب هناك،
 * واللاعب لا يرسله أصلًا. وطلب البايتين يطابق شكل طلب اللاعب ويردّ
 * `Content-Range: bytes 0-1/<TOTAL>` فيُقرأ الحجم الكامل من بايتين.
 *
 * ولماذا لا يُقرأ الجسم كاملًا عند ردّ مضيفٍ يتجاهل Range بـ‎200‎: ذلك يعني
 * تنزيل الملفّ كلّه. نقرأ رأس الاستجابة فقط ثم نفصل الاتصال — `disconnect()`
 * قبل قراءة الجسم يُلغي التنزيل، والبايتات المقروءة سقفها [HEAD_CAP].
 *
 * الحكم على الحيوية مضبوط على قاعدة «الحكم مقابل لا نعرف»:
 * كود حالة أو جسم يجاهر بالانتهاء = حكم؛ ومهلة أو انقطاع = لا نعرف (لا يُسقط
 * رابطًا حيًّا لسقطة شبكة عابرة).
 */
object MediaProbe {

    /** سقف ما يُقرأ من جسم استجابة غير وسائط (قائمة تشغيل أو صفحة). */
    private const val HEAD_CAP = 8192

    data class Result(
        val alive: Boolean,
        val httpCode: Int,
        val contentType: String,
        /** الحجم الكامل بالبايت إن أعلنه الخادم، وإلا null. */
        val totalBytes: Long?,
        /** زمن أوّل بايت بالميلي ثانية — رافعة ترتيب «الأكثر استجابة». */
        val ttfbMs: Long,
        /** true حين يكون الحكم قاطعًا (كود/جسم)، وfalse حين «لا نعرف». */
        val certain: Boolean,
        val why: String = "",
        /**
         * أوّل بايتات الاستجابة — تُملأ فقط حين يُطلب [probe] بـ`bodyCap`.
         * يُحلَّل بها رأس الحاوية (ترميز الفيديو ودقّته) بلا طلبٍ ثانٍ.
         */
        val body: ByteArray? = null,
    ) {
        companion object {
            fun unknown(why: String) = Result(false, 0, "", null, Long.MAX_VALUE, false, why)
        }
    }

    /**
     * فحص واحد. يُستدعى من خيط إدخال/إخراج مباشرةً (لا suspend) — الاستدعاء من
     * داخل التطبيق يأتي مغلَّفًا في `Dispatchers.IO`.
     *
     * [bodyCap] > 0 يطلب أوّل هذا العدد من البايتات ويعيدها في [Result.body]
     * **مع** الحكم والحجم في طلبٍ واحد — يُستعمل حين نحتاج قراءة رأس الملفّ
     * (الدقّة والترميز) فلا نُكرّر الاتصال.
     */
    fun probe(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 7000,
        bodyCap: Int = 0,
    ): Result {
        val uri = try { URL(url) } catch (e: Exception) {
            // رابطٌ لا يُبنى ليس رابطًا ميتًا — لا نُسقطه، ونُبقيه بلا حكم.
            return Result(true, 0, "", null, Long.MAX_VALUE, false, "unparseable")
        }
        val lowerUrl = url.lowercase()
        // قائمة التشغيل ملفّ نصّي صغير يُقرأ كاملًا؛ وإرسال `Range` إليها قد
        // يردّ بايتين فقط فتبدو «قائمة مزيفة» وهي سليمة (حكمٌ كاذب).
        val isPlaylist = lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd")
        val looksVideo = lowerUrl.let {
            it.contains(".mp4") || isPlaylist ||
                it.contains("media_type=video") || it.contains(".ts")
        }
        var conn: HttpURLConnection? = null
        val t0 = System.currentTimeMillis()
        return try {
            val c = uri.openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "GET"
            // البايتان يكفيان للحجم؛ ووسم الوسائط لا يحتاج قراءةً أصلًا — إلا
            // إن طُلب جسمٌ لقراءة الرأس فيُطلب سقفه من البداية. ولا يُرسل
            // `Range` إلى قائمة تشغيل: قد يُقتطع الردّ فتبدو قائمةً مزيفة.
            if (looksVideo && !isPlaylist) {
                c.setRequestProperty("Range", "bytes=0-" + (if (bodyCap > 0) bodyCap - 1 else 1))
            }
            referer?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Referer", it) }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.instanceFollowRedirects = true
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            val code = c.responseCode
            val ttfb = System.currentTimeMillis() - t0
            val ct = c.contentType.orEmpty()

            if (code !in 200..399) {
                // الجسم (حتى ٥١٢ بايت) هو الفرق بين «رابط ميّت» و«توقيع منتهٍ»
                // — و`full-drama` يعلن الثانية حرفيًّا: {"error":"link expired"}
                val body = try { readLimited(c.errorStream, 512) } catch (e: Exception) { null }
                return Result(
                    alive = false, httpCode = code, contentType = ct, totalBytes = null,
                    ttfbMs = ttfb, certain = true,
                    why = if (body.isNullOrBlank()) "HTTP $code" else "HTTP $code: ${body.take(64)}",
                )
            }

            val total = totalFromHeaders(c, isPlaylist)
            // قائمة تشغيل مزيفة (HTML بزيّ m3u8) تُردّ قبل اللاعب — وإلا فهو
            // «خطأ 3002» (بايتات ليست وسائط) في وقت المشاهدة.
            val claimsHls = url.contains(".m3u8", true) || ct.contains("mpegurl", true)
            if (claimsHls) {
                val body = readLimited(c.inputStream, HEAD_CAP)
                if (body.isNullOrEmpty() || !body.contains("#EXTM3U")) {
                    return Result(false, code, ct, total, ttfb, true, "not a playlist: ${body?.take(48).orEmpty()}")
                }
            }
            // جسمٌ لقراءة رأس الحاوية عند الطلب — يُقرأ بسقفٍ صارم ثم يُقطع
            // الاتصال في `finally`، فلا يكبر الطلب عن [bodyCap].
            val head = if (bodyCap > 0 && !claimsHls) {
                try {
                    val buf = ByteArray(bodyCap)
                    var n = 0
                    c.inputStream.use { s ->
                        while (n < bodyCap) {
                            val r = s.read(buf, n, bodyCap - n)
                            if (r <= 0) break
                            n += r
                        }
                    }
                    if (n > 0) buf.copyOf(n) else null
                } catch (e: Exception) { null }
            } else null
            Result(true, code, ct, total, ttfb, true, "", head)
        } catch (e: Exception) {
            // شهادة TLS مرفوضة أو مضيف لا يُحلّ = حكم؛ ومهلة/انقطاع = لا نعرف.
            val certain = e is javax.net.ssl.SSLHandshakeException ||
                e is java.net.UnknownHostException
            Result.unknown("${e.javaClass.simpleName}: ${e.message?.take(48) ?: "-"}")
                .copy(certain = certain)
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /** الحجم الكامل: `Content-Range: bytes 0-1/44983631` أولًا ثم `Content-Length`. */
    private fun totalFromHeaders(c: HttpURLConnection, isPlaylist: Boolean): Long? {
        val cr = c.getHeaderField("Content-Range")
        if (cr != null) {
            val total = cr.substringAfterLast('/', "").trim()
            total.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        // قائمة التشغيل: `Content-Length` حجم ملفّها النصّي لا حجم الفيديو —
        // عرضه يعني «905 B» بجوار فيلم بحجمه 858 ميغابايت. لا يُعرض أصلًا.
        if (isPlaylist) return null
        val len = c.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: return null
        // مع ‎206‎ الطول هو طول المقطع لا الملف؛ يُقرأ من Content-Range وحده.
        return if (c.responseCode == 206) null else len.takeIf { it > 0 }
    }

    private fun readLimited(ins: java.io.InputStream?, max: Int): String? = try {
        ins?.let { s ->
            val buf = ByteArray(max)
            val n = s.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    /** حجم مقروء: «858 MB» و«18.8 MB» و«940 KB». */
    fun human(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.1f GB".format(gb)
            mb >= 100 -> "%.0f MB".format(mb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            else -> "%.0f KB".format(kb)
        }
    }

    /** دقّة مقروءة من ارتفاع فيديو بالبكسل. */
    fun qualityLabel(height: Int): String? = when {
        height >= 2160 -> "2160p"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        height >= 240 -> "240p"
        height > 0 -> "${height}p"
        else -> null
    }

    /**
     * ما الذي يفعله الرابط: **مشاهدة** أم **تحميل**.
     *
     * لماذا يلزم تمييزه: قائمة «سيرفرات التشغيل» تعرض كل شيء في قائمة واحدة،
     * ورابط تحميل ملفٍّ كامل بجوار بثٍّ مباشر لا يفترقان بالعين إلا بهذا
     * الوسم — والاثنان يُفتحان في المشغّل نفسه. المستخدم طلب التمييز صريحًا.
     *
     * ولماذا كلمةٌ عربية لا أيقونة وحدها: الاسم يظهر في قائمة نصّية، والرمز
     * وحده قد لا يُقرأ (أو يُقرأ صندوقًا فارغًا في بعض الخطوط)، فالمعنى في
     * الكلمة والرمز زينةٌ لها.
     */
    enum class Use(val mark: String) {
        WATCH("▶ مشاهدة"),
        DOWNLOAD("⬇ تحميل"),
    }

    /**
     * اسم السيرفر الكامل كما يراه المستخدم في قائمة التشغيل:
     * `▶ مشاهدة · Rumble · 720p · 858 MB · [M3U8]` — الحقول الغائبة تُحذف بلا
     * فراغات زائدة.
     *
     * [use] يضع وسم مشاهدة/تحميل **في الصدارة** لا في الذيل: العين تمسح أول
     * الاسم، والذيل مزدحم بالصيغة والحجم. وغيابه يعني «لم يُصنَّف» فلا يُوسم
     * بشيء كاذب.
     *
     * [declared] صيغةٌ أعلنها الخادم (`Content-Type`) — تُستعمل عند غياب
     * الامتداد من الرابط، وهو حال مرايا `video-gen` التي لا امتداد لها.
     */
    fun label(
        server: String,
        quality: String?,
        bytes: Long?,
        url: String,
        type: com.lagradost.cloudstream3.utils.ExtractorLinkType?,
        note: String? = null,
        use: Use? = null,
        declared: String? = null,
    ): String {
        val parts = ArrayList<String>(6)
        use?.let { parts.add(it.mark) }
        parts.add(server.ifBlank { "تشغيل" })
        quality?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        note?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        human(bytes)?.let { parts.add(it) }
        return FormatTag.tagged(parts.joinToString(" · "), url, type, declared)
    }
}
