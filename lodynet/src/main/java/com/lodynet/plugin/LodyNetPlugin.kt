package com.lodynet.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class LodyNetPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد حتى لا تتصادم مفاتيحها
        // مع أي وحدة أخرى داخل عملية التطبيق الواحدة. تُقرأ مباشرة بلا تخزين مركزي.
        val prefs = context.getSharedPreferences(LodySettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(LodyProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                LodySettingsBottomSheet.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}