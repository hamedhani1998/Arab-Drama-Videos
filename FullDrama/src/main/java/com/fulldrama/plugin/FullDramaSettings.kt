package com.fulldrama.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class FullDramaSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
            id = android.view.View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "FullDrama"
        const val KEY_SHOW_PLATFORMS = "fd_show_platforms"
        const val KEY_ROW_LIMIT = "fd_row_limit"
        const val KEY_SUB_LANGS = "fd_sub_langs"

        fun platformKey(slug: String) = "fd_platform_$slug"

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            FullDramaSettingsBottomSheet(prefs).show(fm, "fd_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(PREFS_NAME)
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            val subLangsPref = EditTextPreference(ctx).apply {
                key = KEY_SUB_LANGS
                title = "لغات الترجمة المطلوبة"
                summary = "مثال: ar,en — اتركه فارغاً لعرض كل اللغات"
                setDefaultValue("")
                dialogTitle = "لغات الترجمة المطلوبة"
                setOnBindEditTextListener { et ->
                    et.inputType = android.text.InputType.TYPE_CLASS_TEXT
                }
            }
            playbackCategory.addPreference(subLangsPref)

            val platCat = PreferenceCategory(ctx)
            platCat.title = "أقسام التصنيفات في الواجهة الرئيسية"
            preferenceScreen.addPreference(platCat)

            val showPlatformsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_PLATFORMS
                title = "التصنيفات كلها"
                summary = "ألغِ وسمها لإخفاء صفوف كل التصنيفات دفعةً واحدة"
                setDefaultValue(true)
            }
            platCat.addPreference(showPlatformsPref)

            // مفتاح مستقل لكل تصنيف — يُخفى صفّه في الواجهة الرئيسية
            listOf(
                "romance" to "رومانسي", "ceo-boss" to "مدير تنفيذي", "family" to "عائلي",
                "revenge" to "انتقام", "historical" to "تاريخي", "sci-fi" to "خيال علمي",
                "action" to "أكشن", "time-travel" to "السفر بالزمن", "underdog" to "انتصار المستضعف",
                "mystery" to "غموض", "medical" to "طبي", "school" to "مدرسة",
                "thriller" to "إثارة", "business" to "أعمال", "marriage" to "زواج",
                "vampire" to "مستذئب", "pregnancy" to "حمل",
            ).forEach { (slug, label) ->
                platCat.addPreference(SwitchPreferenceCompat(ctx).apply {
                    key = platformKey(slug)
                    title = label
                    summary = "إخفاء/إظهار صفّ $label"
                    setDefaultValue(true)
                })
            }

            val rowLimitPref = ListPreference(ctx).apply {
                key = KEY_ROW_LIMIT
                title = "عدد صفوف التصنيفات المعروضة"
                summary = "أقل صفوف = ظهور أسرع للواجهة الرئيسية"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "8", "6", "4", "2")
                entries = arrayOf(
                    "كل التصنيفات (افتراضي)",
                    "أول 8 تصنيفات",
                    "أول 6 تصنيفات",
                    "أول 4 تصنيفات",
                    "أول تصنيفين"
                )
                setDefaultValue("all")
            }
            platCat.addPreference(rowLimitPref)

            val resetPref = Preference(ctx).apply {
                key = "fd_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_PLATFORMS)
                        remove(KEY_ROW_LIMIT)
                        remove(KEY_SUB_LANGS)
                        listOf(
                            "romance", "ceo-boss", "family", "revenge", "historical", "sci-fi",
                            "action", "time-travel", "underdog", "mystery", "medical", "school",
                            "thriller", "business", "marriage", "vampire", "pregnancy",
                        ).forEach { remove(platformKey(it)) }
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}

typealias FullDramaSettings = FullDramaSettingsBottomSheet
