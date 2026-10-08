package com.dramatip.plugin

import android.app.Dialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
 * إعدادات مصدر «DramaTip». الوحدة بلا خيارات قابلة للتعديل حاليًا — الصفوف
 * الرئيسية (المنصّات) ووسوم الصيغ ثابتة. تبقى الورقة للإتساق مع باقي الوحدات
 * وتعرض رسالة واحدة فقط («لا خيارات»).
 */
class DramaTipSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        const val PREFS_NAME = "DramaTip"

        // مفاتيح الخيارات التي يقرأها DramaTipProvider عند البث.
        const val KEY_QUALITY_ORDER = "dt_quality_order"   // "default" | "asc" | "desc"
        const val KEY_SHOW_SUBTITLES = "dt_show_subtitles" // Boolean — الافتراضي true

        // إظهار/إخفاء أقسام القائمة الرئيسية.
        const val KEY_SHOW_LATEST = "dt_show_latest"       // «الأحدث» — true
        const val KEY_SHOW_POPULAR = "dt_show_popular"     // «الأكثر مشاهدة» — true
        const val KEY_SHOW_PLATFORMS = "dt_show_platforms" // أسماء المنصّات — true

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DramaTipSettingsBottomSheet(prefs).show(fm, "dt_settings")
        }
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // ← الربط الحاسم: اكتب في نفس ملف التفضيلات الذي يقرأه المصدر.
            preferenceManager.setSharedPreferencesName(PREFS_NAME)

            // ← أنشئ شجرة الخيارات ثم املأها (لا تقرأ `preferenceScreen` قبل
            //    إنشائه — هذا كان سبب انهيار التطبيق عند فتح الإعدادات).
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

            // — أقسام القائمة الرئيسية —
            val homeCat = PreferenceCategory(requireContext()).apply {
                title = "أقسام القائمة الرئيسية"
            }
            preferenceScreen.addPreference(homeCat)

            val showLatest = SwitchPreferenceCompat(requireContext()).apply {
                key = KEY_SHOW_LATEST
                title = "الأحدث"
                summary = "يعرض صفّ «الأحدث» قبل أسماء المنصّات"
                setDefaultValue(true)
            }
            homeCat.addPreference(showLatest)

            val showPopular = SwitchPreferenceCompat(requireContext()).apply {
                key = KEY_SHOW_POPULAR
                title = "الأكثر مشاهدة"
                summary = "يعرض صفّ «الأكثر مشاهدة» قبل أسماء المنصّات"
                setDefaultValue(true)
            }
            homeCat.addPreference(showPopular)

            val showPlatforms = SwitchPreferenceCompat(requireContext()).apply {
                key = KEY_SHOW_PLATFORMS
                title = "أسماء المنصّات"
                summary = "يعرض صفًّا لكل منصّة في قائمة المنصّات"
                setDefaultValue(true)
            }
            homeCat.addPreference(showPlatforms)

            // — خيارات التشغيل —
            val cat = PreferenceCategory(requireContext()).apply {
                title = "خيارات التشغيل"
            }
            preferenceScreen.addPreference(cat)

            val qualityOrder = ListPreference(requireContext()).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب السيرفرات"
                summary = "افتراضي: نفس ترتيب الصفحة كما هو"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("default", "asc", "desc")
                entries = arrayOf(
                    "الافتراضي (كما هو)",
                    "من الأقل جودة إلى الأعلى",
                    "من الأعلى إلى الأقل"
                )
                setDefaultValue("default")
            }
            cat.addPreference(qualityOrder)

            val showSubs = SwitchPreferenceCompat(requireContext()).apply {
                key = KEY_SHOW_SUBTITLES
                title = "إظهار الترجمة"
                summary = "يعرض ترجمات الحلقة عند توفرها"
                setDefaultValue(true)
            }
            cat.addPreference(showSubs)

            val reset = Preference(requireContext()).apply {
                key = "dt_reset"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_QUALITY_ORDER)
                        remove(KEY_SHOW_SUBTITLES)
                        remove(KEY_SHOW_LATEST)
                        remove(KEY_SHOW_POPULAR)
                        remove(KEY_SHOW_PLATFORMS)
                    }.apply()
                    android.widget.Toast.makeText(requireContext(), "تمت إعادة الضبط", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(reset)
        }
    }
}