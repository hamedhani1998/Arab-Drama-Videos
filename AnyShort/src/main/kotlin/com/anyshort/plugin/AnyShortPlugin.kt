package com.anyshort.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnyShortPlugin : Plugin() {
    override fun load(context: Context) {
        // ملف تفضيلات فريد بهذه الوحدة حتى لا تتصادم مفاتيحها مع أي وحدة أخرى
        // داخل عملية التطبيق الواحدة.
        val prefs = context.getSharedPreferences(AnyShortSettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(AnyShortProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) AnyShortSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}
