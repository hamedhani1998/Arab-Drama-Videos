package com.kisskhba.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissKHBAPlugin : Plugin() {
    override fun load(context: Context) {
        // ملف تفضيلات خاصّ بهذه الإضافة وحدها.
        val prefs = context.getSharedPreferences(KissKHBASettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(KissKHBAProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) KissKHBASettings.show(activity.supportFragmentManager, prefs)
        }
    }
}
