package com.arabtools.plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * «أدوات عربية» — إضافة تخصيص لا مصدر محتوى.
 *
 * لا تُسجّل مزوّداً ولا تُعيد أي محتوى: عملها كله تعديلات على واجهة التطبيق
 * (شريط التنقل، المظهر، المشغّل) يطلبها المستخدم مفتاحاً مفتاحاً من إعداداتها
 * العربية. أسلوبها واجهة أندرويد العامة، لا انعكاس على أصناف داخلية.
 *
 * ### التوقيت (سبب إعادة كتابة هذا الملف)
 * التطبيق يُحمِّل الإضافات من داخل `MainActivity.onCreate` عبر مسار مؤجَّل،
 * أي أن [load] يُنادى **بعد** أن تكون الشاشة الأولى قد مرّت بـ`onResume`
 * بالفعل. لو اكتفينا بتسجيل مستمع دورة الحياة لما وصلنا أي نداء للشاشة
 * القائمة، ولاستُطبّق أي تعديل (وهذا بالضبط ما كان يحدث: الإعدادات تُفتح
 * ولا يتغيّر شيء).
 *
 * الحل: السياق المارّ إلى [load] في مسار التحميل هو الشاشة نفسها — نأخذه
 * مباشرةً ونُمرّره إلى [ArabToolsMods.prime] الذي يربط المراقب ويطبّق فوراً.
 */
@CloudstreamPlugin
class ArabToolsPlugin : Plugin() {

    private var mods: ArabToolsMods? = null

    override fun load(context: Context) {
        // ملف التفضيلات باسم الإضافة حتى لا تتصادم مفاتيحنا مع وحدة أخرى.
        val prefs = context.getSharedPreferences(ArabToolsPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        // شفاء فوري لأي قفل خلّفه إصدار قديم (إخفاء تبويب الإعدادات) — قبل أي تعديل.
        ArabToolsPrefs.healLegacy(prefs)
        val application = context.applicationContext as? Application
        if (application != null) {
            val engine = ArabToolsMods(application, prefs)
            runCatching { application.registerActivityLifecycleCallbacks(engine) }
                .onSuccess {
                    mods = engine
                    ArabToolsStatus.registered = true
                    // نأخذ الشاشة الحالية من السياق: تحقّقنا في bytecode التطبيق
                    // أن مسار التحميل يُمرّر `MainActivity` نفسه لا سياق التطبيق.
                    engine.prime(context as? Activity)
                }
                .onFailure {
                    ArabToolsStatus.registered = false
                    ArabToolsStatus.lastError = it.javaClass.simpleName + ": " + it.message
                    Log.w(ArabToolsMods.TAG, "تعذّر تسجيل مستمع دورة الحياة", it)
                }
        } else {
            ArabToolsStatus.lastError = "سياق التطبيق غير متاح"
        }

        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) ArabToolsSettings.show(activity.supportFragmentManager, prefs)
        }
    }

    override fun beforeUnload() {
        mods?.detach()
        mods = null
    }
}
