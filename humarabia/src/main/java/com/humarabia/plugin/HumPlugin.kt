package com.humarabia.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class HumPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("HUM", Context.MODE_PRIVATE)
        registerMainAPI(HumProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                HumSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}