package com.moviesleech.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import cloudstreamshared.DriveChain
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesLeechPlugin : Plugin() {
    override fun load(context: Context) {
        DriveChain.installDns()
        val prefs = context.getSharedPreferences(MoviesLeechSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(MoviesLeechProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) MoviesLeechSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
