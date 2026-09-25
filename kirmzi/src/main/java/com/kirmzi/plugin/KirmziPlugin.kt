package com.kirmzi.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class KirmziPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («Kirmzi») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث.
        val prefs = context.getSharedPreferences(KirmziSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(KirmziProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) KirmziSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
