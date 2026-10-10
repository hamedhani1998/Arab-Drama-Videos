package com.arabtools.plugin

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * «أدوات عربية» — إضافة تخصيص لا مصدر محتوى.
 *
 * لا تُسجّل مزوّداً ولا تُعيد أي محتوى: عملها كله تعديلات على واجهة التطبيق
 * (شريط التنقل، المظهر، المشغّل) يطلبها المستخدم مفتاحاً مفتاحاً من إعداداتها
 * العربية. أسلوبها واجهة أندرويد العامة، لا انعكاس على أصناف داخلية.
 */
@CloudstreamPlugin
class ArabToolsPlugin : Plugin() {

    private var mods: ArabToolsMods? = null

    override fun load(context: Context) {
        // سياق الإضافة هو سياق التطبيق، ومنه نصل إلى Application فنسجّل مستمع
        // دورة الحياة. الملف باسم الإضافة حتى لا تتصادم مفاتيحنا مع وحدة أخرى.
        val prefs = context.getSharedPreferences(ArabToolsPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val application = context.applicationContext as? Application
        if (application != null) {
            val engine = ArabToolsMods(application, prefs)
            runCatching { application.registerActivityLifecycleCallbacks(engine) }
                .onSuccess { mods = engine }
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
