package cloudstreamshared

import com.lagradost.cloudstream3.utils.ExtractorLinkType

/**
 * وسمٌ يوضّح **صيغة** كل مصدر تشغيل: MP4 / M3U8 / DASH / TS / MKV …
 *
 * حيث يظهر: `ExtractorLink.name` هو الحقل الوحيد الذي يعرضه المشغّل في قائمة
 * «سيرفرات التشغيل»، فيُضاف الوسم إلى الاسم لا إلى الرابط.
 *
 * لماذا لا يمكن أن يُفسد التشغيل:
 * لا يُلمس `url` ولا `referer` ولا `headers` ولا `quality` ولا `type` — التغيير
 * كلّه نصٌّ في اسمٍ معروض. لا يضيف طلب شبكة واحد، ولا يُغيّر ما يطلبه المشغّل من
 * الخادم، ولا يعرف الخادم بالاسم أصلاً. أي أن أخطر ما قد يحدث هو اسم أطول.
 *
 * لماذا يُحسب من الرابط لا من `type` وحده: كثير من المصادر تبثّ mp4 بنوع
 * M3U8 والعكس (نوع ExtractorLinkType مقصود به كيف يُفتح الرابط، لا ما هيته)،
 * فقراءة الامتداد من الرابط أدقّ. ولا يُختَمق الحكم: عند غياب الامتداد
 * يُستعمل النوع المُصرَّح به.
 */
object FormatTag {

    private const val SEP = " · "

    /** الصيغة كما تُقرأ من الرابط، ثم من النوع المُعلن عند غياب الامتداد. */
    fun label(url: String, type: ExtractorLinkType?): String {
        val u = url.lowercase()

        // قائمة المتابعة — يُذكر بها أن صيغة الحاوية M3U8 وليس نوع الملف
        if (u.contains(".m3u8") || u.contains("application/vnd.apple.mpegurl")) return "M3U8"
        if (u.contains(".mpd") || u.contains("application/dash+xml")) return "DASH"

        // حاويات ملفّ واحد
        if (u.contains(".mp4")) return "MP4"
        if (u.contains(".m4v")) return "M4V"
        if (u.contains(".mkv")) return "MKV"
        if (u.contains(".webm")) return "WEBM"
        if (u.contains(".mov")) return "MOV"
        if (u.contains(".avi")) return "AVI"
        if (u.contains(".flv")) return "FLV"
        if (u.contains(".mp3")) return "MP3"
        if (u.contains(".m4a")) return "M4A"
        if (u.contains(".wav")) return "WAV"

        // بلا امتداد (روابط موقّعة أو خلف بروكسي) — نُعلن النوع المُصرَّح به،
        // و«TS» لا يُستنتج من كلمة داخل المسار لأن .ts قد يصادف حرفاً.
        return when (type) {
            ExtractorLinkType.M3U8 -> "M3U8"
            ExtractorLinkType.DASH -> "DASH"
            ExtractorLinkType.TORRENT -> "TORRENT"
            ExtractorLinkType.MAGNET -> "MAGNET"
            ExtractorLinkType.VIDEO -> "VIDEO"
            null -> "VIDEO"
        }
    }

    /**
     * الاسم بعد إضافة الوسم. إن كان الاسم يحمل الصيغة أصلاً (تُظهرها بعض
     * المصادر في تسميتها اليدوية) لا يُكرَّر الوسم.
     */
    fun tagged(name: String, url: String, type: ExtractorLinkType?): String {
        val tag = label(url, type)
        val plain = name.ifBlank { "تشغيل" }
        if (plain.contains(tag, ignoreCase = true)) return plain
        return plain + SEP + "[" + tag + "]"
    }

    /** الوسم وحده مع قوسين — للاستعمال في التسميات المركّبة. */
    fun bracket(url: String, type: ExtractorLinkType?): String = "[" + label(url, type) + "]"
}