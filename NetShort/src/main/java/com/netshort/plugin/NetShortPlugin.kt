package com.netshort.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class NetShortPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («NetShort») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث — تماماً كأسلوب DeepDrama.
        val prefs = context.getSharedPreferences(NetShortSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(NetShortProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) NetShortSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
