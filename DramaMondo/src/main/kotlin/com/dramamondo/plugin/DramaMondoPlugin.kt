package com.dramamondo.plugin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class DramaMondoPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("DramaMondo", Context.MODE_PRIVATE)
        registerMainAPI(DramaMondoProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) DramaMondoSettings.show(activity.supportFragmentManager, prefs)
        }
    }
}
