package com.kisskhlive.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KissKHLivePlugin : Plugin() {
    override fun load(context: Context) {
        // ملف تفضيلات خاصّ بهذه الإضافة وحدها.
        val prefs = context.getSharedPreferences(KissKHLiveSettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(KissKHLiveProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) KissKHLiveSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}
