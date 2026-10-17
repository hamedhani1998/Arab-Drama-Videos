package com.minutedrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class MinuteDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («MinuteDrama») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث — تماماً كأسلوب NetShort.
        val prefs = context.getSharedPreferences(MinuteDramaSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(MinuteDramaProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) MinuteDramaSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
