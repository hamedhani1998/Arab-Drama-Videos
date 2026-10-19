package com.pakistanilive.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class PakistanilivePlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(PakistaniliveSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(PakistaniliveProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) PakistaniliveSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}