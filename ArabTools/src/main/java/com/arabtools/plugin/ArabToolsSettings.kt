package com.arabtools.plugin

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
 * إعدادات «أدوات عربية» — عربية بالكامل.
 *
 * تُعرض من زر الإعدادات في قائمة الإضافات. الخيارات تُحفظ في ملف تفضيلات
 * خاص بالوحدة ([ArabToolsPrefs.PREFS_NAME])، ويقرأها [ArabToolsMods] مباشرة
 * عند كل رسم للشاشة، فالتغيير يظهر فوراً دون إعادة تشغيل التطبيق.
 */
class ArabToolsSettings(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(prefs))
            .commit()
    }

    companion object {
        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            ArabToolsSettings(prefs).show(fm, "art_settings")
        }

        /** ألوان جاهزة تُعرض في قوائم الألوان (نصوص عربية + قيم محفوظة). */
        val COLOR_VALUES = arrayOf("default", "black", "dark", "blue", "purple", "red")
        val COLOR_ENTRIES = arrayOf(
            "افتراضي (دون تغيير)",
            "أسود",
            "رمادي غامق",
            "أزرق",
            "بنفسجي",
            "أحمر"
        )
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(ArabToolsPrefs.PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            addGeneral(ctx)
            addPlayer(ctx)
            addAppearance(ctx)
            addNavigation(ctx)
            addTools(ctx)
        }

        /** (١) عام — المفتاح الرئيسي وزر الإيقاف المؤقت. */
        private fun addGeneral(ctx: android.content.Context) {
            val category = PreferenceCategory(ctx).apply { title = "عام" }
            preferenceScreen.addPreference(category)

            category.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = ArabToolsPrefs.MASTER
                    title = "تفعيل التعديلات"
                    summary = "المفتاح الرئيسي — إيقافه يُلغي كل التعديلات أدناه فوراً"
                    setDefaultValue(true)
                }
            )
            category.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = ArabToolsPrefs.SAFE_MODE
                    title = "الوضع الآمن"
                    summary = "يوقف كل التعديلات مؤقتاً دون فقدان إعداداتها"
                    setDefaultValue(false)
                }
            )
        }

        /** (٢) المشغّل. */
        private fun addPlayer(ctx: android.content.Context) {
            val category = PreferenceCategory(ctx).apply { title = "المشغّل" }
            preferenceScreen.addPreference(category)

            category.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = ArabToolsPrefs.PLAYER_LANDSCAPE
                    title = "تدوير تلقائي إلى الشاشة الأفقية"
                    summary = "يعمل عند بدء التشغيل فقط، ويعود الوضع الطبيعي عند الخروج منه"
                    setDefaultValue(false)
                }
            )
        }

        /** (٣) المظهر. */
        private fun addAppearance(ctx: android.content.Context) {
            val category = PreferenceCategory(ctx).apply { title = "المظهر" }
            preferenceScreen.addPreference(category)

            category.addPreference(
                ListPreference(ctx).apply {
                    key = ArabToolsPrefs.NIGHT_MODE
                    title = "وضع العرض"
                    summary = "فرض الوضع الليلي أو النهاري على التطبيق كله"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = arrayOf("auto", "dark", "light")
                    entries = arrayOf("تلقائي (دون تغيير)", "ليلي دائماً", "نهاري دائماً")
                    setDefaultValue("auto")
                }
            )
            category.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = ArabToolsPrefs.HIDE_STATUS
                    title = "إخفاء شريط الحالة"
                    summary = "يُخفي شريط الوقت والبطارية أعلى الشاشة"
                    setDefaultValue(false)
                }
            )
            category.addPreference(
                ListPreference(ctx).apply {
                    key = ArabToolsPrefs.STATUS_COLOR
                    title = "لون شريط الحالة"
                    summary = "لا يعمل على أندرويد 15 وما بعده (النظام يتجاهل اللون)"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = COLOR_VALUES
                    entries = COLOR_ENTRIES
                    setDefaultValue("default")
                }
            )
            category.addPreference(
                ListPreference(ctx).apply {
                    key = ArabToolsPrefs.NAV_COLOR
                    title = "لون شريط أزرار النظام السفلي"
                    summary = "هذا شريط أندرويد السفلي، لا شريط تبويبات التطبيق"
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                    entryValues = COLOR_VALUES
                    entries = COLOR_ENTRIES
                    setDefaultValue("default")
                }
            )
        }

        /** (٤) الرئيسية والتنقل — الشريط كاملاً أو تبويبات بعينها. */
        private fun addNavigation(ctx: android.content.Context) {
            val category = PreferenceCategory(ctx).apply { title = "الرئيسية والتنقل" }
            preferenceScreen.addPreference(category)

            category.addPreference(
                SwitchPreferenceCompat(ctx).apply {
                    key = ArabToolsPrefs.NAV_HIDE_BAR
                    title = "إخفاء شريط التنقل السفلي"
                    summary = "يُخفي شريط التبويبات السفلي كاملاً"
                    setDefaultValue(false)
                }
            )

            val tabsCategory = PreferenceCategory(ctx).apply {
                title = "إخفاء تبويبات محددة"
            }
            category.addPreference(tabsCategory)

            for (tab in ArabToolsPrefs.TABS) {
                tabsCategory.addPreference(
                    SwitchPreferenceCompat(ctx).apply {
                        key = tab.key
                        title = "إخفاء «${tab.label}»"
                        summary = "قد يعود التطبيق إلى التبويب الأول بعد التغيير"
                        setDefaultValue(false)
                    }
                )
            }
        }

        /** (٥) الأدوات — إعادة الضبط ومعلومات قصيرة. */
        private fun addTools(ctx: android.content.Context) {
            val category = PreferenceCategory(ctx).apply { title = "الأدوات" }
            preferenceScreen.addPreference(category)

            category.addPreference(
                Preference(ctx).apply {
                    key = "art_reset"
                    title = "إعادة ضبط إعدادات الإضافة"
                    summary = "يعيد كل الخيارات أعلاه إلى الافتراضي"
                    setOnPreferenceClickListener {
                        ArabToolsPrefs.reset(prefs)
                        Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            )

            category.addPreference(
                Preference(ctx).apply {
                    key = "art_about"
                    title = "حول الإضافة"
                    summary = "أدوات عربية — تخصيص الواجهة والمشغّل. التعديلات تعمل فوراً " +
                        "عند التنقل بين الشاشات، وتُلغى كلها من المفتاح الرئيسي."
                    isSelectable = false
                }
            )
        }
    }
}
