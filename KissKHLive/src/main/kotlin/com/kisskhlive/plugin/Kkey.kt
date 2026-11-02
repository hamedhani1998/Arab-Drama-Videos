package com.kisskhlive.plugin

import java.util.Locale

/**
 * `kkey` — بصمةٌ مشفَّرة يُرسلها موقع `kisskh.la` مع كل طلب إلى
 * `/api/DramaList/Episode/…` و`/api/Sub/…`. بلا هذا المفتاح يردّ الخادم
 * **403** بجسمٍ فارغ.
 *
 * المفتاح **دالّةٌ محضة** من ثلاثة مدخلات: معرّف الحلقة، وثابتٌ نصّيّ
 * (`viGuid` للفيديو أو `subGuid` للترجمة)، وبقيّة الثوابت أدناه. لا كوكيز
 * ولا جلسة ولا Turnstile — لذلك يمكن توليده محليًّا في كل مرّة بلا أي طلب
 * شبكة، وهذا سبب سرعة فتح الروابط.
 *
 * الخوارزمية (مأخوذة حرفيًّا من `common.js` في الموقع، الدالّة `_0x54b991`):
 *  1. تُجمع 15 قطعةً بـ`|` ثم يُدرَج في الموضع 1 ناتج دالّة التجزئة
 *     `_0x70dbf4` (تشبه `hashCode` في Java لكن بلا تقييدٍ إلى 32 بت).
 *  2. يُحشى النصّ بـPKCS#7 إلى مضاعف 16.
 *  3. تشفيرٌ بنيويّ شبيه بـAES-128-CBC لكن **بجداول جولاتٍ مُدوَّرة**
 *     ومفتاح جولاتٍ مُضمَّن، وليس AES القياسيّ (تحقّقنا: AES القياسيّ يعطي
 *     ناتجًا مختلفًا). لذلك نُقلت الجداول والحساب كما هي.
 *  4. الخرج: ست عشرة بايت لكل كتلة، بالنظام الست عشري بأحرف كبيرة.
 *
 * الثوابت مثبَّتة كما في `appVer`/`platformVer`/`appName` داخل الـSPA، وأي
 * تغييرٍ لها في الموقع يقتضي تحديثها هنا.
 */
object Kkey {

    const val APP_VER = "2.8.10"
    const val PLATFORM_VER = 4830201
    const val APP_NAME = "kisskh"
    const val VI_GUID = "62f176f3bb1b5b8e70e39932ad34a0c7"
    const val SUB_GUID = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"

    /** مفتاح الفيديو لحلقةٍ ما. */
    fun forStream(episodeId: Int): String = key(episodeId, VI_GUID)

    /** مفتاح قائمة الترجمات لحلقةٍ ما. */
    fun forSub(episodeId: Int): String = key(episodeId, SUB_GUID)

    // ---------------------------------------------------------------- المفتاح

    fun key(episodeId: Int, guid: String): String {
        val parts = arrayListOf(
            "", episodeId.toString(), "", "mg3c3b04ba", APP_VER, guid,
            PLATFORM_VER.toString(),
            trunc(APP_NAME), trunc(APP_NAME.lowercase(Locale.US)), trunc(APP_NAME),
            APP_NAME, APP_NAME, APP_NAME, "00", "",
        )
        parts.add(1, jsHash(parts.joinToString("|")).toString())
        val plain = parts.joinToString("|")

        val pad = 16 - plain.length % 16
        val padded = plain + pad.toChar().toString().repeat(pad)

        val words = IntArray(padded.length / 4)
        for (i in padded.indices) {
            words[i shr 2] = words[i shr 2] or
                ((padded[i].code and 0xFF) shl (24 - (i % 4) * 8))
        }
        var off = 0
        while (off < words.size) {
            encryptBlock(words, off)
            off += 4
        }

        val sb = StringBuilder(words.size * 8)
        for (w in words) sb.append(String.format(Locale.US, "%08X", w))
        return sb.toString()
    }

    private fun trunc(s: String): String = if (s.length <= 48) s else s.substring(0, 48)

    /**
     * `_0x70dbf4`: `h = (h << 5) - h + c` على أعداد JavaScript.
     *
     * `h << 5` يمرّ بـ`ToInt32` (تلتفّ إلى 32 بت) ثم **يُطرح `h` كاملًا**
     * بلا تقييد، فقد يتجاوز الناتج 32 بت ويصبح سالبًا كبيرًا — ولهذا يُحفظ
     * في [Long] ويُطبع بإشارته (‎"-6305290284"‎) كما يفعل الموقع.
     */
    private fun jsHash(s: String): Long {
        var h = 0L
        for (ch in s) {
            h = (h.toInt() shl 5).toLong() - h + ch.code
        }
        return h
    }

    // ---------------------------------------------------------------- الجداول

    private val XT = IntArray(256) { i ->
        if (i < 0x80) i shl 1 else (i shl 1) xor 0x11B
    }

    private val SBOX = IntArray(256)
    private val T0 = IntArray(256)
    private val T1 = IntArray(256)
    private val T2 = IntArray(256)
    private val T3 = IntArray(256)

    init {
        var p = 0
        var q = 0
        for (i in 0 until 256) {
            var t = q xor (q shl 1) xor (q shl 2) xor (q shl 3) xor (q shl 4)
            t = (t ushr 8) xor (t and 0xFF) xor 0x63
            val s = t and 0xFF
            SBOX[p] = s
            // الجدول يُبنى من قيمة S-box لا من الفهرس — هذا ما يجعله مختلفًا عن AES.
            val x = (0x101 * XT[s]) xor (0x1010100 * s)
            T0[p] = (x shl 24) or (x ushr 8)
            T1[p] = (x shl 16) or (x ushr 16)
            T2[p] = (x shl 8) or (x ushr 24)
            T3[p] = x
            if (p != 0) {
                val f = XT[p]
                val f2 = XT[XT[f]]
                p = f xor XT[XT[XT[f2 xor f]]]
                q = q xor XT[XT[q]]
            } else {
                p = 1
                q = 1
            }
        }
    }

    /** مفتاح الجولات: 44 كلمةً مُضمَّنة في `common.js` كما هي. */
    private val RK = intArrayOf(
        0x4F6BDAA3.toInt(), 0x9E2F8CB0.toInt(), 0x7F5E722D.toInt(), 0x9EDEF314.toInt(),
        0x536620A8.toInt(), 0xCD49AC18.toInt(), 0xB217DE35.toInt(), 0x2CC92D21.toInt(),
        0x8CBEDDD9.toInt(), 0x41F771C1.toInt(), 0xF3E0AFF4.toInt(), 0xDF2982D5.toInt(),
        0x2DADDE47.toInt(), 0x6C5AAF86.toInt(), 0x9FBA0072.toInt(), 0x409382A7.toInt(),
        0xF9BE824E.toInt(), 0x95E42DC8.toInt(), 0x0A5E2DBA.toInt(), 0x4ACDAF1D.toInt(),
        0x54C72698.toInt(), 0xC1230B50.toInt(), 0xCB7D26EA.toInt(), 0x81B089F7.toInt(),
        0x93604E94.toInt(), 0x524345C4.toInt(), 0x993E632E.toInt(), 0x188EEAD9.toInt(),
        0xCAE77B39.toInt(), 0x98A43EFD.toInt(), 0x019A5DD3.toInt(), 0x1914B70A.toInt(),
        0xB04E1CED.toInt(), 0x28EA2210.toInt(), 0x29707FC3.toInt(), 0x3064C8C9.toInt(),
        0xE8A6C1E9.toInt(), 0xC04CE3F9.toInt(), 0xE93C9C3A.toInt(), 0xD95854F3.toInt(),
        0xB486CCDC.toInt(), 0x74CA2F25.toInt(), 0x9DF6B31F.toInt(), 0x44AEE7EC.toInt(),
    )

    /** متجه البداية المُضمَّن (‎`0x1504af3, 0x56e619cf, 0x2e42bba6, -0x73c08f07`‎). */
    private val IV = intArrayOf(0x01504AF3, 0x56E619CF, 0x2E42BBA6, 0x8C3F70F9.toInt())

    // ---------------------------------------------------------------- التشفير

    private fun encryptBlock(w: IntArray, off: Int) {
        var a = w[off] xor (if (off == 0) IV[0] else w[off - 4]) xor RK[0]
        var b = w[off + 1] xor (if (off == 0) IV[1] else w[off - 3]) xor RK[1]
        var c = w[off + 2] xor (if (off == 0) IV[2] else w[off - 2]) xor RK[2]
        var d = w[off + 3] xor (if (off == 0) IV[3] else w[off - 1]) xor RK[3]

        var k = 4
        for (round in 1..9) {
            val na = T0[a ushr 24] xor T1[(b ushr 16) and 0xFF] xor
                T2[(c ushr 8) and 0xFF] xor T3[d and 0xFF] xor RK[k++]
            val nb = T0[b ushr 24] xor T1[(c ushr 16) and 0xFF] xor
                T2[(d ushr 8) and 0xFF] xor T3[a and 0xFF] xor RK[k++]
            val nc = T0[c ushr 24] xor T1[(d ushr 16) and 0xFF] xor
                T2[(a ushr 8) and 0xFF] xor T3[b and 0xFF] xor RK[k++]
            val nd = T0[d ushr 24] xor T1[(a ushr 16) and 0xFF] xor
                T2[(b ushr 8) and 0xFF] xor T3[c and 0xFF] xor RK[k++]
            a = na; b = nb; c = nc; d = nd
        }

        val na = ((SBOX[a ushr 24] shl 24) or (SBOX[(b ushr 16) and 0xFF] shl 16) or
            (SBOX[(c ushr 8) and 0xFF] shl 8) or SBOX[d and 0xFF]) xor RK[k++]
        val nb = ((SBOX[b ushr 24] shl 24) or (SBOX[(c ushr 16) and 0xFF] shl 16) or
            (SBOX[(d ushr 8) and 0xFF] shl 8) or SBOX[a and 0xFF]) xor RK[k++]
        val nc = ((SBOX[c ushr 24] shl 24) or (SBOX[(d ushr 16) and 0xFF] shl 16) or
            (SBOX[(a ushr 8) and 0xFF] shl 8) or SBOX[b and 0xFF]) xor RK[k++]
        val nd = ((SBOX[d ushr 24] shl 24) or (SBOX[(a ushr 16) and 0xFF] shl 16) or
            (SBOX[(b ushr 8) and 0xFF] shl 8) or SBOX[c and 0xFF]) xor RK[k]

        w[off] = na
        w[off + 1] = nb
        w[off + 2] = nc
        w[off + 3] = nd
    }
}
