package com.fulldrama.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class FullDramaPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("FullDrama", Context.MODE_PRIVATE)
        registerMainAPI(FullDramaProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) FullDramaSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}
