package com.dramadunyam.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class DramadunyamPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («Dramadunyam») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تُقرأ مباشرة (بلا تخزين مركزي) عند كل بث — كأسلوب NetShort.
        val prefs = context.getSharedPreferences(DramadunyamSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(DramadunyamProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) DramadunyamSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
