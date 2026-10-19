package com.dramaglance.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class DramaGlancePlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («DramaGlance») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث — تماماً كأسلوب Reelree.
        val prefs = context.getSharedPreferences(DramaGlanceSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(DramaGlanceProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) DramaGlanceSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
