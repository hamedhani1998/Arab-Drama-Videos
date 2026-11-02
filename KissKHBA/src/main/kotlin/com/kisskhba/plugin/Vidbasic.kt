package com.kisskhba.plugin

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * فكّ تشفير روابط مشغّل `vidbasic.top` (سيرفر «Standard Server» في kisskh.ba).
 *
 * صفحة `/3rdplayer.html` تُحمّل `script[data-name=crypto]` وتحمل مفتاحًا
 * مُعمّى في `data-value`؛ وتفكّه في المتصفّح بـ:
 *
 * ```js
 * key = Utf8.parse('9458829337'+'5053432799'+'2224455212'+'89')
 * iv  = Utf8.parse('5259228356'+'829423')
 * CryptoJS.AES.decrypt(dataValue, key, { iv: iv }).toString(Utf8)
 * ```
 *
 * الخرج **رابط الوسائط** نفسه (m3u8 أو mp4). والوسيط `sub` في رابط الصفحة
 * مشفَّرٌ بالمفتاح نفسه، وفكّه يعطي رابط ملفّ الترجمة.
 *
 * المقاسات: المفتاح ٣٢ حرفًا (AES-256)، المتجه ١٦ حرفًا، والحشو PKCS#7 —
 * وهو `PKCS5Padding` في JCE.
 */
object Vidbasic {

    private const val KEY_TEXT = "94588293375053432799222445521289"
    private const val IV_TEXT = "5259228356829423"

    private val key = SecretKeySpec(KEY_TEXT.toByteArray(Charsets.UTF_8), "AES")
    private val iv = IvParameterSpec(IV_TEXT.toByteArray(Charsets.UTF_8))

    /** يفكّ نصًّا بترميز Base64 ويُعيد النصّ الصريح، أو null إن فشل. */
    fun decrypt(base64: String?): String? {
        if (base64.isNullOrBlank()) return null
        return try {
            val clean = base64.trim().replace("\n", "").replace("\r", "")
                .replace(' ', '+')
                .replace("\\/", "/")
            // بعض القيم تُرسل بلا حشو `=` (طولها ليس من مضاعفات ٤).
            val pad = (4 - clean.length % 4) % 4
            val bytes = Base64.decode(clean + "=".repeat(pad), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            String(cipher.doFinal(bytes), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            null
        }
    }

    /** يفكّ ثم يتحقّق أنّ الناتج رابط ويب — وإلا null. */
    fun decryptUrl(base64: String?): String? =
        decrypt(base64)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}
