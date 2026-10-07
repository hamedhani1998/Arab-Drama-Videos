package com.directdrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class DirectDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («DirectDrama») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تُقرأ مباشرة (بلا تخزين مركزي) عند كل بث — كأسلوب NetShort.
        val prefs = context.getSharedPreferences(DirectDramaSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(DirectDramaProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) DirectDramaSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
