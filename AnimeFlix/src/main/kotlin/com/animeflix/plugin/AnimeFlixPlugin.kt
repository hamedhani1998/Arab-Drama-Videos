package com.animeflix.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import cloudstreamshared.DriveChain
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeFlixPlugin : Plugin() {
    override fun load(context: Context) {
        // تعويض DNS لمرايا `video-gen` و`workers.dev` قبل أي طلب.
        DriveChain.installDns()
        val prefs = context.getSharedPreferences(AnimeFlixSettingsBottomSheet.PREFS_NAME, Context.MODE_PRIVATE)
        registerMainAPI(AnimeFlixProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) AnimeFlixSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
        }
    }
}
