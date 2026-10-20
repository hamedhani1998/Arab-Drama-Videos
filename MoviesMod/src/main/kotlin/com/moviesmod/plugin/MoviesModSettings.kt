package com.moviesmod.plugin

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
 * إعدادات مصدر «MoviesMod». تُعرض من زر الإعدادات في CloudStream.
 *
 * كل خيار يُخزَّن في ملف التفضيلات نفسه الذي يمرّره Plugin للمصدر، فيقرأه
 * [MoviesModProvider] مباشرةً عند كل طلب — بلا تخزينٍ مركزي. الربط عبر
 * `preferenceManager.setSharedPreferencesName(PREFS_NAME)` يضمن أن الاختيارات
 * تُحفظ في ذلك الملف بالذات.
 */
class MoviesModSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "MoviesMod"

        // مفاتيح الإعدادات — أسماء قصيرة وفريدة داخل الملف.
        const val KEY_QUALITY_ORDER = "mm_quality_order"      // "default" | "asc" | "desc"
        const val KEY_SHOW_ALT = "mm_show_alt_mirrors"        // Boolean
        const val KEY_INCLUDE_BATCH = "mm_include_batch"      // Boolean
        const val KEY_SHOW_SEASONS = "mm_show_seasons"        // Boolean

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            MoviesModSettingsBottomSheet(prefs).show(fm, "mm_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()

            // اجعل الورقة تكتب في الملف نفسه الذي يقرأه المصدر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            val playbackCategory = PreferenceCategory(ctx)
            playbackCategory.title = "خيارات التشغيل"
            preferenceScreen.addPreference(playbackCategory)

            val qualityOrderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "ترتيب المرايا عند البث — لكل حلقة تُعرض الجودة الأفضل أوّلًا"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "asc", "desc")
                entries = arrayOf(
                    "الافتراضي (كما هو)",
                    "من الأقل إلى الأعلى",
                    "من الأعلى إلى الأقل"
                )
                setDefaultValue("default")
            }
            playbackCategory.addPreference(qualityOrderPref)

            val altPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_ALT
                title = "إظهار المرايا البديلة"
                summary = "تُظهر مرآة جوجل الثانية و«cdn» إضافةً إلى المرآة الأساسية — مفعّلة في الأصل"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(altPref)

            val batchPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_INCLUDE_BATCH
                title = "إظهار حلقة «الكل (دفعة)»"
                summary = "تُضيف ملف الحلقات الكامل (Batch/Zip) كحلقةٍ واحدة داخل المسلسل — معطّلة في الأصل"
                setDefaultValue(false)
            }
            playbackCategory.addPreference(batchPref)

            val seasonPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_SEASONS
                title = "تسمية المواسم في القائمة"
                summary = "تُظهر اسم الموسم بجانب الحلقة (مثال: الموسم 2 · الحلقة 5) — مفعّلة في الأصل"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(seasonPref)

            val resetPref = Preference(ctx).apply {
                key = "mm_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_ALT)
                        remove(KEY_INCLUDE_BATCH)
                        remove(KEY_SHOW_SEASONS)
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}
