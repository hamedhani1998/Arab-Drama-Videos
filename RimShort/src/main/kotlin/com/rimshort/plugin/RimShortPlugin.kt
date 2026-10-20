package com.rimshort.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RimShortPlugin : Plugin() {
    override fun load(context: Context) {
        // ملف تفضيلات فريد بهذه الوحدة حتى لا تتصادم مفاتيحها مع أي وحدة أخرى
        // داخل عملية التطبيق الواحدة.
        val prefs = context.getSharedPreferences(RimShortSettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(RimShortProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) RimShortSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}
