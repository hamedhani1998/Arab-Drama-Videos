package com.nartoedge.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class EdgeNartoPlugin : Plugin() {
    override fun load(context: Context) {
        // إعدادات الوحدة في ملف SharedPreferences فريد («NartoEdge») حتى لا
        // تتصادم مفاتيحها مع وحدة Narto Drama داخل عملية التطبيق الواحدة.
        // تتم قراءتها مباشرة (بلا تخزين مركزي) عند كل بث.
        val prefs = context.getSharedPreferences(EdgeNartoSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(EdgeNartoProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) EdgeNartoSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
