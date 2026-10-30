package com.animeflix.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات مصدر «AnimeFlix». تُعرض من زرّ الإعدادات في CloudStream،
 * وتُخزَّن في الملف نفسه الذي يقرأه [AnimeFlixProvider] عند كل طلب.
 */
class AnimeFlixSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FrameLayout(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "AnimeFlix"

        const val KEY_QUALITY_ORDER = "af_quality_order"   // "default" | "desc" | "asc"
        const val KEY_SHOW_ALT = "af_show_alt_mirrors"     // Boolean
        const val KEY_SHOW_SEASONS = "af_show_seasons"     // Boolean
        const val KEY_PREFERRED_Q = "af_preferred_quality" // "all" | "1080" | "720" | "480"

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            AnimeFlixSettingsBottomSheet(prefs).show(fm, "af_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(PREFS_NAME)
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val cat = PreferenceCategory(ctx)
            cat.title = "خيارات التشغيل"
            preferenceScreen.addPreference(cat)

            cat.addPreference(ListPreference(ctx).apply {
                key = KEY_PREFERRED_Q
                title = "الجودة المفضّلة"
                summary = "تُظهر جودةً واحدة فقط في كل حلقة (الافتراضي: كل الجودات)"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "1080", "720", "480")
                entries = arrayOf("كل الجودات", "1080p", "720p", "480p")
                setDefaultValue("all")
            })

            cat.addPreference(ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "ترتيب المرايا داخل الحلقة — الأفضل أوّلًا هو الافتراضي"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "desc", "asc")
                entries = arrayOf(
                    "الافتراضي (الأعلى أوّلًا)",
                    "من الأعلى إلى الأقل",
                    "من الأقل إلى الأعلى",
                )
                setDefaultValue("default")
            })

            cat.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_ALT
                title = "إظهار كل المرايا"
                summary = "عند الإطفاء تُعرض مرآة واحدة لكل ملفّ (‎r2‎ أو ‎video-gen‎) بدل كلّها"
                setDefaultValue(true)
            })

            cat.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SEASONS
                title = "تسمية المواسم في القائمة"
                summary = "«الموسم 1 · الحلقة 5» بدل «الحلقة 5»"
                setDefaultValue(true)
            })

            preferenceScreen.addPreference(Preference(ctx).apply {
                key = "af_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_ALT)
                        remove(KEY_SHOW_SEASONS)
                        remove(KEY_PREFERRED_Q)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }
    }
}
