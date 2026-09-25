package com.onshort.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class OnShortPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («OnShort») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث — تماماً كأسلوب DeepDrama/NetShort.
        val prefs = context.getSharedPreferences(OnShortSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(OnShortProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) OnShortSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
