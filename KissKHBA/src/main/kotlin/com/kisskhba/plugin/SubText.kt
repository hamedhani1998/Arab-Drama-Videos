package com.kisskhba.plugin

/**
 * ترجمات `kisskh.ba` المُشفَّرة (ملفّات `.vtt` القادمة من `kk.vidbasic.top`).
 *
 * الملفّ نفسه WebVTT صحيح (ترويسة وأوقات)، لكن **كل سطر ترجمةٍ مشفَّرٌ وحده**:
 * Base64 ثم AES‑256‑CBC بالمفتاح والمتجه نفسيهما اللذين يستخدمهما مشغّل
 * `vidbasic` لفكّ رابط الوسائط ([Vidbasic]):
 *
 * ```js
 * AES.decrypt(line, Utf8.parse('9458829337' + '5053432799' +
 *                              '2224455212' + '89'),
 *                    { iv: Utf8.parse('5259228356' + '829423') })
 * ```
 *
 * تحقّقنا على مقاطع حقيقية: `+bkbHMMGoq6J3C3SC7meovVzwOVICTyix1od4EfR1lQ=`
 * ← «Xiaoyu is asleep.»، و`PCIEMw12ch3oZ8x2BvOVAywrUaYD98QMLAnhojAENcMHQQWgppBt0Pm2ZaXGQOCt`
 * ← «The stars are so beautiful tonight.»
 *
 * ملاحظة: بعض الحلقات لا تُرسل معامل `sub` أصلًا، فلا ترجمة لها في الموقع.
 */
object SubText {

    private val TIMESTAMP = "-->"
    private val BLOB = Regex("^[A-Za-z0-9+/]{16,}={0,2}$")

    /** يحوّل ملفّ الترجمة المشفّر إلى WebVTT صالح للقراءة. */
    fun toVtt(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder("WEBVTT\n\n")
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isEmpty()) {
                i++
                continue
            }
            // الملفّ الأصلي يبدأ بـ`WEBVTT` ونحن نكتبها في الترويسة.
            if (t.equals("WEBVTT", true)) {
                i++
                continue
            }
            if (t.contains(TIMESTAMP)) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                out.append(t.replace(',', '.')).append('\n')
                i++
                continue
            }
            // رقم المقطع: يُحذف فقط إذا تبعه سطر وقت (كي لا نحذف نصًّا رقميًّا).
            if (isIndex(t) && nextText(lines, i + 1).contains(TIMESTAMP)) {
                i++
                continue
            }
            out.append(decryptLine(t)).append('\n')
            i++
        }
        return out.toString().trimEnd() + "\n"
    }

    /** يفكّ سطرًا واحدًا؛ وإن لم يكن Base64 صالحًا يُعيده كما هو. */
    private fun decryptLine(line: String): String {
        if (!BLOB.matches(line)) return line
        return Vidbasic.decryptText(line) ?: line
    }

    private fun isIndex(s: String): Boolean =
        s.isNotEmpty() && s.length <= 6 && s.all { it.isDigit() }

    private fun nextText(lines: List<String>, from: Int): String {
        var i = from
        while (i < lines.size) {
            val t = lines[i].trim()
            if (t.isNotEmpty()) return t
            i++
        }
        return ""
    }
}
