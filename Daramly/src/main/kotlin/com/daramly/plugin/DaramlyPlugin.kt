package com.daramly.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DaramlyPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(
            DaramlySettingsBottomSheet.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        registerMainAPI(DaramlyProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                DaramlySettingsBottomSheet.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}
