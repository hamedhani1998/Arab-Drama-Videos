package com.kisskh.plugin

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
 * إعدادات مصدر «KissKH» (kisskh.bz) وحدها.
 *
 * لكل إضافة KissKH ملفُّ تفضيلاتٍ ومفاتيحُ خاصّة بها (بادئة `kk_`) حتى لا
 * تتداخل الخيارات بين `kisskh.bz` و`kisskh.la` و`kisskh.ba` وهم في عمليةٍ واحدة.
 *
 * الخيارات هنا تخصّ هذا الموقع فقط: ترجمات المشغّل (hnvid) ولغاتها، وطريقة
 * عرض الجودات التي يفصلها الـmaster.
 */
class KissKHSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        /** ملف تفضيلات خاصّ بهذه الإضافة وحدها. */
        const val PREFS_NAME = "KissKH"

        const val KEY_SHOW_SUBS = "kk_show_subs"      // Boolean — إظهار الترجمات
        const val KEY_LANG = "kk_lang_mode"           // all | ar | en | ar_en
        const val KEY_QUALITY = "kk_quality_mode"     // all | best

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            KissKHSettings(prefs).show(fm, "kk_settings")
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
                    summary = "ترجمات مشغّل hnvid وترجمات الـmaster لكل حلقة"
                    setDefaultValue(true)
                }
            )

            subs.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_LANG
                    title = "لغات الترجمة المعروضة"
                    summary = "يمكن حصر القائمة بلغة واحدة لتسريع الاختيار"
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
            links.title = "الروابط"
            preferenceScreen.addPreference(links)

            links.addPreference(
                ListPreference(ctx).apply {
                    key = KEY_QUALITY
                    title = "الجودات المعروضة"
                    summary = "افتراضيًا تُعرض كل جودةٍ يفصلها الـmaster دون حذف"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("all", "best")
                    entries = arrayOf(
                        "كل الجودات (الافتراضي)",
                        "أعلى جودة فقط",
                    )
                    setDefaultValue("all")
                }
            )

            preferenceScreen.addPreference(
                Preference(ctx).apply {
                    key = "kk_reset_prefs"
                    title = "إعادة الضبط"
                    summary = "يعيد خيارات هذه الإضافة إلى الافتراضي"
                    setOnPreferenceClickListener {
                        prefs.edit().apply {
                            remove(KEY_SHOW_SUBS)
                            remove(KEY_LANG)
                            remove(KEY_QUALITY)
                        }.apply()
                        Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            )
        }
    }
}
