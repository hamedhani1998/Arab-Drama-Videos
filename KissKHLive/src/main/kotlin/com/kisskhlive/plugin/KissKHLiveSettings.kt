package com.kisskhlive.plugin

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
 * إعدادات مصدر «KissKH.la» وحدها.
 *
 * الموقع يعرض لكل حلقة: بثًّا أساسيًّا (`Video`)، وبثًّا بديلًا (`Video_tmp`)
 * أحيانًا، وسيرفرًا خارجيًّا (`ThirdParty`) أحيانًا، وترجمات `/api/Sub`
 * بكل لغاتها. الخيارات تحت تحكّم ما يُعرض من هذه العناصر، وملفّ التفضيلات
 * مستقلّ (بادئة `kkl_`) عن `kisskh.bz` و`kisskh.ba`.
 */
class KissKHLiveSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {
        id = View.generateViewId()
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "KissKHLive"

        const val KEY_SHOW_SUBS = "kkl_show_subs"    // Boolean
        const val KEY_LANG = "kkl_lang_mode"         // all | ar | en | ar_en
        const val KEY_QUALITY = "kkl_quality_mode"   // all | best
        const val KEY_SHOW_ALT = "kkl_show_alt"      // Boolean — بث Video_tmp
        const val KEY_SHOW_MIRROR = "kkl_show_mirror" // Boolean — سيرفر ThirdParty

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            KissKHLiveSettings(prefs).show(fm, "kkl_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(PREFS_NAME)
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val subs = PreferenceCategory(ctx)
            subs.title = "الترجمات"
            preferenceScreen.addPreference(subs)

            subs.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_SUBS
                    title = "إظهار ملفات الترجمة"
                    summary = "كل لغات /api/Sub لهذه الحلقة (تُفكّ وتُقدَّم محليًّا)"
                    setDefaultValue(true)
                }
            )

            subs.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_LANG
                    title = "لغات الترجمة المعروضة"
                    summary = "حصر القائمة يسرّع الاختيار عند كثرة اللغات"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "ar", "en", "ar_en")
                    entries = arrayOf(
                        "كل اللغات (الافتراضي)",
                        "العربية فقط",
                        "الإنجليزية فقط",
                        "العربية والإنجليزية",
                    )
                    setDefaultValue("all")
                }
            )

            val links = PreferenceCategory(ctx)
            links.title = "الروابط والسيرفرات"
            preferenceScreen.addPreference(links)

            links.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_QUALITY
                    title = "الجودات المعروضة"
                    summary = "افتراضيًا تُعرض كل جودةٍ داخل الـmaster دون حذف"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "best")
                    entries = arrayOf(
                        "كل الجودات (الافتراضي)",
                        "أعلى جودة فقط",
                    )
                    setDefaultValue("all")
                }
            )

            links.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_ALT
                    title = "إظهار البث البديل"
                    summary = "رابط Video_tmp عندما يوفّره الموقع"
                    setDefaultValue(true)
                }
            )

            links.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = KEY_SHOW_MIRROR
                    title = "إظهار السيرفر الخارجي"
                    summary = "رابط ThirdParty (يُستخرج منه الوسائط وترجماته إن أمكن)"
                    setDefaultValue(true)
                }
            )

            preferenceScreen.addPreference(
                Preference(ctx).apply {
                    key = "kkl_reset_prefs"
                    title = "إعادة الضبط"
                    summary = "يعيد خيارات هذه الإضافة إلى الافتراضي"
                    setOnPreferenceClickListener {
                        prefs.edit().apply {
                            remove(KEY_SHOW_SUBS)
                            remove(KEY_LANG)
                            remove(KEY_QUALITY)
                            remove(KEY_SHOW_ALT)
                            remove(KEY_SHOW_MIRROR)
                        }.apply()
                        Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            )
        }
    }
}
