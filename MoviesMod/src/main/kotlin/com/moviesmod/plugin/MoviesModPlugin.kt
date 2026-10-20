package com.moviesmod.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesModPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(MoviesModSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(MoviesModProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) MoviesModSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
