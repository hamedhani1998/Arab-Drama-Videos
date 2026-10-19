package com.mosalsaly.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MosalsalyPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(MosalsalySettings.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(MosalsalyProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                MosalsalySettings.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}