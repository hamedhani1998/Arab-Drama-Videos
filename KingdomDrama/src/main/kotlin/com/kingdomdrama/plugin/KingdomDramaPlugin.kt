package com.kingdomdrama.plugin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KingdomDramaPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(
            KingdomDramaSettingsBottomSheet.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        registerMainAPI(KingdomDramaProvider(prefs))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                KingdomDramaSettingsBottomSheet.show(activity.supportFragmentManager, prefs)
            }
        }
    }
}
