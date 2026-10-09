package com.dramavideoshow.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class DramaVideoShowPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («DramaVideoShow») حتى لا
        // تتصادم مفاتيحها مع أي وحدة أخرى داخل عملية التطبيق الواحدة.
        val prefs = context.getSharedPreferences(DramaVideoShowSettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(DramaVideoShowProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) DramaVideoShowSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}