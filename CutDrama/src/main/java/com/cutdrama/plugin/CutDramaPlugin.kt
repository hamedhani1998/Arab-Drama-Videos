package com.cutdrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class CutDramaPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(CutDramaSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(CutDramaProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) CutDramaSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
