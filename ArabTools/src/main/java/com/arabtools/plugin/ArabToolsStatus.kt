package com.arabtools.plugin

import android.content.Context
import android.content.SharedPreferences

/**
 * حالة التشخيص الحيّة لـ«أدوات عربية».
 *
 * الغرض: أن يستطيع المستخدم — **دون كبل ولا adb** — أن يعرف ما الذي يجري:
 * هل سُجّل المحرّك؟ هل رأى شاشة؟ هل وُجدت معرّفات التطبيق؟ كل ذلك من زر
 * «تشخيص سريع» داخل شاشة الإعدادات، ومن رسالة قصيرة عند أول تطبيق.
 *
 * يكتبها [ArabToolsMods] وقت العمل ويقرأها [ArabToolsSettings] عند الطلب فقط،
 * فلا ترتيب أو قفل: الحقول بسيطة وتُكتب ذرّياً.
 */
object ArabToolsStatus {

    /** هل نجح تسجيل مستمع دورة الحياة على التطبيق؟ */
    @Volatile
    var registered: Boolean = false

    /** اسم آخر شاشة مرّت علينا (للتشخيص). */
    @Volatile
    var lastActivity: String = "—"

    /** كم مرة نُفِّذ محرّك التطبيق. */
    @Volatile
    var applyCount: Int = 0

    /** آخر رقم رأى فيه المحرّك شريط تنقل سفلي (−1 = لم يمرّ بعد). */
    @Volatile
    var navBarsFound: Int = -1

    /** هل ظهرت شاشة المشغّل في آخر تطبيق؟ */
    @Volatile
    var playerSeen: Boolean = false

    /** آخر خطأ اعترضه المحرّك (يُبتلع دائماً، لكن يُسجَّل هنا). */
    @Volatile
    var lastError: String? = null

    /** ملخّص التعديلات الفعّالة آخر مرة. */
    @Volatile
    var lastSummary: String = "—"

    /** هل أظهرنا رسالة التأكيد في هذه العملية؟ (مرة واحدة تكفي) */
    @Volatile
    var toasted: Boolean = false

    /** هل أوقفته حماية الانهيار تلقائياً؟ */
    @Volatile
    var guardDisabled: Boolean = false

    /** هل شرحنا للمستخدم منفذ النجاة (الضغط المطوّل) مرة واحدة؟ */
    @Volatile
    var escapeHintShown: Boolean = false

    /**
     * تقرير عربي قصير يُعرض في نافذة «تشخيص سريع».
     *
     * نُعيد حساب معرّفات التطبيق هنا مباشرةً (لا نعتمد على المحرّك) كي يعمل
     * التشخيص حتى لو لم يُطبَّق شيء بعد — وهذه بالضبط الحالة التي نحتاج فيها
     * أن نعرف: هل المشكلة أن الشاشة لم تُرَ، أم أن المعرّفات تغيّرت؟
     */
    fun report(context: Context, prefs: SharedPreferences): String {
        val resources = context.resources
        val navId = idOf(resources, "nav_view")
        val railId = idOf(resources, "nav_rail_view")
        val found = ArabToolsPrefs.TABS.count {
            idOf(resources, it.resName) != 0
        }
        val missing = ArabToolsPrefs.TABS.filter { idOf(resources, it.resName) == 0 }
            .joinToString("، ") { it.label }

        val master = prefs.getBoolean(ArabToolsPrefs.MASTER, true)
        val safeMode = prefs.getBoolean(ArabToolsPrefs.SAFE_MODE, false)

        return buildString {
            appendLine("• المفتاح الرئيسي: " + if (master) "مفتوح ✔" else "مغلق ✘ (كل شيء موقوف)")
            appendLine("• الوضع الآمن: " + if (safeMode) "مفتوح ✘ (كل شيء موقوف)" else "مغلق ✔")
            appendLine()
            appendLine("• تسجيل المحرّك على التطبيق: " + if (registered) "ناجح ✔" else "فاشل ✘")
            appendLine("• آخر شاشة وصلت للمحرّك: $lastActivity")
            appendLine("• عدد مرات التطبيق: $applyCount")
            appendLine("• شرائط التنقل المكتشفة: " + if (navBarsFound < 0) "لم يُطبَّق بعد" else "$navBarsFound")
            appendLine()
            appendLine("• معرّف nav_view: " + if (navId != 0) "موجود ✔" else "غير موجود ✘")
            appendLine("• معرّف nav_rail_view: " + if (railId != 0) "موجود ✔" else "غير موجود ✘")
            appendLine("• معرّفات تبويبات التنقل: $found من ${ArabToolsPrefs.TABS.size}")
            if (missing.isNotEmpty()) appendLine("  (المفقود: $missing)")
            appendLine()
            appendLine("• التعديلات الفعّالة: $lastSummary")
            if (guardDisabled) {
                appendLine("• ⚠ أوقفتها حماية الانهيار تلقائياً — أعِد فتح المفتاح الرئيسي.")
            }
            appendLine("• آخر خطأ: " + (lastError ?: "لا يوجد ✔"))
        }.trim()
    }

    private fun idOf(resources: android.content.res.Resources?, name: String): Int =
        if (resources == null) 0
        else runCatching {
            resources.getIdentifier(name, "id", ArabToolsPrefs.APP_PACKAGE)
        }.getOrDefault(0)
}
