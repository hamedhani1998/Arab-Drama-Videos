package cloudstreamshared

/**
 * قراءة **الدقّة والصيغة** من رأس ملفّ MP4 — من أوّل بايتاته فقط، بلا تنزيل.
 *
 * لماذا: بعض المواقع لا تنشر إلا ملفًّا واحدًا بلا قائمة جودات (قِيس على
 * full-drama: ملفٌّ واحد لكل حلقة)، فسؤال «ما الجودة المتوفّرة؟» لا جواب له في
 * الاستجابة ولا في الترويسة — الجواب مكتوب في صندوق `stsd` داخل `moov`:
 * العرض والارتفاع وترميز الفيديو (HEVC/H.264) والصوت.
 *
 * ولماذا لا يُنزَّل الملفّ: `moov` مُقدَّم في الملفّات المهيّأة للبثّ
 * (faststart)، فيكفي [HEAD_CAP] بايت (قِيس: `moov` في أوّل 360 كيلوبايت).
 * وإن لم يُوجَد الصندوق كاملًا في المقروء نُعيد `null` ونتوقّف — لا يُنزَّل
 * 45 ميغابايت لقراءة رقم.
 *
 * وكل قراءة بايت محروسة بالحدود: الحجم يُقرأ من الرأس، ولا يُوثق به، والملفّ
 * قد يكون مقطوعًا.
 */
object Mp4Head {

    /** ما يُقرأ من أوّل الملفّ محاولةً لإيجاد `moov` كاملًا. */
    const val HEAD_CAP = 512 * 1024

    /** صناديق تحوي صناديق غيرها — يُهبط فيها، وما عداها يُتخطّى. */
    private val BOX_CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts")

    private val VIDEO_CODECS = mapOf(
        "avc1" to "H.264", "avc3" to "H.264", "hvc1" to "HEVC", "hev1" to "HEVC",
        "av01" to "AV1", "vp09" to "VP9", "vp08" to "VP8", "mp4v" to "MPEG-4",
    )

    private val AUDIO_CODECS = mapOf(
        "mp4a" to "AAC", "ac-3" to "AC3", "ec-3" to "EAC3", "Opus" to "Opus",
        "alac" to "ALAC", "flac" to "FLAC", "twos" to "PCM", "sowt" to "PCM",
    )

    data class Info(
        val width: Int,
        val height: Int,
        val videoCodec: String?,
        val audioCodec: String?,
        val audioChannels: Int,
        val audioSampleRate: Int,
    ) {
        /** دقّة مقروءة من الارتفاع — «1080p» — وإن كان عموديًّا فهي من العرض. */
        fun quality(): String? =
            MediaProbe.qualityLabel(maxOf(height, 0).takeIf { it > 0 } ?: width)
                ?: MediaProbe.qualityLabel(width)
    }

    /** يقرأ الرأس من مصفوفة بايتات مقروءة مسبقًا. `null` إن لم يُعثر على `stsd`. */
    fun parse(buf: ByteArray, len: Int = buf.size): Info? {
        val end = minOf(len, buf.size)
        if (end < 16) return null
        val moov = findBox(buf, 0, end, "moov") ?: return null

        var width = 0
        var height = 0
        var video: String? = null
        var audio: String? = null
        var channels = 0
        var sampleRate = 0

        for (trak in children(buf, moov.first, moov.second)) {
            if (trak.third != "trak") continue
            val mdia = findBox(buf, trak.first, trak.second, "mdia") ?: continue
            val kind = handlerType(buf, mdia.first, mdia.second)
            val minf = findBox(buf, mdia.first, mdia.second, "minf") ?: continue
            val stbl = findBox(buf, minf.first, minf.second, "stbl") ?: continue
            val stsd = findBox(buf, stbl.first, stbl.second, "stsd") ?: continue
            // stsd: fullbox (4) + entry_count (4) ثم أوّل مدخل عيّنة
            val entryOff = stsd.first + 8
            val entry = boxAt(buf, entryOff, stsd.second) ?: continue
            val fourcc = entry.first
            // جسم المدخل يبدأ بعد ‎size+fourcc‎: reserved[6] ثم data_reference_index
            // — ومن هناك: العرض في الفيديو عند ‎+24‎ والقنوات في الصوت عند ‎+16‎.
            val body = entry.second

            val isVideoEntry = VIDEO_CODECS.containsKey(fourcc) || kind == "vide"
            if (isVideoEntry && video == null && width == 0) {
                // مدخل فيديو: العرض عند ‎+24‎ والارتفاع عند ‎+26‎ من جسم المدخل
                val w = u16(buf, body + 24, end)
                val h = u16(buf, body + 26, end)
                if (w > 0 || h > 0) {
                    width = w
                    height = h
                    video = VIDEO_CODECS[fourcc]
                }
            } else if (audio == null && (AUDIO_CODECS.containsKey(fourcc) || kind == "soun")) {
                // مدخل صوت: القنوات عند ‎+16‎ ومعدّل العيّنة (‎16.16‎) عند ‎+24‎
                channels = u16(buf, body + 16, end)
                sampleRate = (u32(buf, body + 24, end) ushr 16).toInt()
                audio = AUDIO_CODECS[fourcc]
            }
        }

        if (width == 0 && height == 0 && video == null && audio == null) return null
        return Info(width, height, video, audio, channels, sampleRate)
    }

    /** نوع المعالج من `hdlr`: «vide» أو «soun». */
    private fun handlerType(buf: ByteArray, from: Int, to: Int): String {
        val hdlr = findBox(buf, from, to, "hdlr") ?: return ""
        // hdlr: fullbox (4) + pre_defined (4) ثم handler_type (4)
        val off = hdlr.first + 8
        if (off + 4 > minOf(to, buf.size)) return ""
        return String(buf, off, 4, Charsets.US_ASCII)
    }

    /** [type] بأوّل مظهر له، أو null. */
    private fun findBox(buf: ByteArray, from: Int, to: Int, type: String): Pair<Int, Int>? {
        for (b in children(buf, from, to)) {
            if (b.third == type) return b.first to b.second
        }
        return null
    }

    /** أبناء [from]..[to] من الصناديق غير المحتوية؛ النتيجة (بداية، نهاية، النوع). */
    private fun children(buf: ByteArray, from: Int, to: Int): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        var p = from
        val limit = minOf(to, buf.size)
        while (p + 8 <= limit) {
            val size = u32(buf, p, limit)
            if (size < 8) break
            val type = String(buf, p + 4, 4, Charsets.US_ASCII)
            val bodyStart = p + 8
            val bodyEnd = (p + size).toInt()
            if (bodyEnd > limit) break // صندوق مقطوع — لا يُقرأ نصفه
            if (type in BOX_CONTAINERS) out.addAll(children(buf, bodyStart, bodyEnd))
            out.add(Triple(bodyStart, bodyEnd, type))
            p = bodyEnd
        }
        return out
    }

    /** الصندوق الذي يبدأ عند [off] بالضبط — (النوع، بداية الجسم). */
    private fun boxAt(buf: ByteArray, off: Int, to: Int): Pair<String, Int>? {
        val limit = minOf(to, buf.size)
        if (off + 8 > limit) return null
        val size = u32(buf, off, limit)
        if (size < 8 || off + size > limit) return null
        return String(buf, off + 4, 4, Charsets.US_ASCII) to off + 8
    }

    private fun u16(buf: ByteArray, off: Int, end: Int): Int {
        if (off < 0 || off + 2 > minOf(end, buf.size)) return 0
        return ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
    }

    private fun u32(buf: ByteArray, off: Int, end: Int): Long {
        if (off < 0 || off + 4 > minOf(end, buf.size)) return 0L
        return ((buf[off].toLong() and 0xFF) shl 24) or
            ((buf[off + 1].toLong() and 0xFF) shl 16) or
            ((buf[off + 2].toLong() and 0xFF) shl 8) or
            (buf[off + 3].toLong() and 0xFF)
    }
}
