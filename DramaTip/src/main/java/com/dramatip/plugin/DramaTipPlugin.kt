package com.dramatip.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class DramaTipPlugin : Plugin() {
    override fun load(context: Context) {
        // ملف تفضيلات فريد للوحدة (لا تعارض مفاتيح مع أي وحدة أخرى داخل العملية).
        val prefs = context.getSharedPreferences(DramaTipSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(DramaTipProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) DramaTipSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}