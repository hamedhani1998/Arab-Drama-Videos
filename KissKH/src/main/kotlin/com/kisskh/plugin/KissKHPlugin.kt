package com.kisskh.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissKHPlugin : Plugin() {
    override fun load(context: Context) {
        // ملف تفضيلات خاصّ بهذه الإضافة وحدها.
        val prefs = context.getSharedPreferences(KissKHSettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(KissKHProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) KissKHSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}
