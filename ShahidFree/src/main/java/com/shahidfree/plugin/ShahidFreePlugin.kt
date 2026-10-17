package com.shahidfree.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class ShahidFreePlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («ShahidFree») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        val prefs = context.getSharedPreferences(ShahidFreeSettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(ShahidFreeProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) ShahidFreeSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}