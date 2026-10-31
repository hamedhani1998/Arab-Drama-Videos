package com.moviesleech.plugin

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
 * إعدادات مصدر «MoviesLeech». تُعرض من زرّ الإعدادات في CloudStream،
 * وتُخزَّن في الملف نفسه الذي يقرأه [MoviesLeechProvider] عند كل طلب.
 */
class MoviesLeechSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        const val PREFS_NAME = "MoviesLeech"

        const val KEY_QUALITY_ORDER = "ml_quality_order"   // "default" | "desc" | "asc"
        const val KEY_SHOW_ALT = "ml_show_alt_mirrors"     // Boolean
        const val KEY_ONE_ENTRY = "ml_one_entry"           // Boolean

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            MoviesLeechSettingsBottomSheet(prefs).show(fm, "ml_settings")
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
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "ترتيب الجودات في قائمة العمل — الأعلى أوّلًا هو الافتراضي"
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
                summary = "مفعّل دائمًا: كل الجودات وكل السيرفرات تُعرض دون استثناء"
                setDefaultValue(true)
                isEnabled = false
            })

            cat.addPreference(SwitchPreferenceCompat(ctx).apply {
                key = KEY_ONE_ENTRY
                title = "مدخل واحد للفيلم"
                summary = "يُعرض الفيلم مدخلًا واحدًا يحمل كل الجودات (أوقفه لعرض كل جودة في مدخل منفصل)"
                setDefaultValue(true)
            })

            preferenceScreen.addPreference(Preference(ctx).apply {
                key = "ml_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_ALT)
                        remove(KEY_ONE_ENTRY)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }
    }
}
