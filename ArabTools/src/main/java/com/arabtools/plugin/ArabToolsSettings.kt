package com.arabtools.plugin

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentManager
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.arabtools.plugin.ArabToolsCatalog.Impl
import com.arabtools.plugin.ArabToolsCatalog.Kind
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * إعدادات «أدوات عربية» — عربية بالكامل.
 *
 * تُعرض من زر الإعدادات في قائمة الإضافات. الشاشة تُبنى كلها من
 * [ArabToolsCatalog.CATEGORIES]، فلا تعريف مكرّر هنا: كل إعداد يوصَف مرة واحدة
 * في ملفات الكتالوج، وهذه الشاشة ترسمه فقط.
 *
 * ### الصدق في العرض
 * كل عنصر عليه علامة «⏳ قريباً» لم يُنفَّذ بعد — يُعرض ولا يُوهم أنه يعمل.
 * وما بلا علامة يعمل فعلاً الآن.
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

        /** وسم العنصر غير المنفَّذ — لا نُوهم المستخدم بأنه يعمل. */
        const val SOON = "⏳ قريباً — "
    }

    class PrefsFragment(private val prefs: SharedPreferences) : PreferenceFragmentCompat() {

        /** مفاتيح ظهرت في هذه الشاشة — يمنع تكرار المفتاح الواحد (يرمي استثناءً). */
        private val usedKeys = HashSet<String>()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.setSharedPreferencesName(ArabToolsPrefs.PREFS_NAME)

            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)

            addIntro(ctx)
            addCatalog(ctx)
            addTools(ctx)
        }

        // ------------------------------------------------------------ المدخل

        /** بطاقة تشرح الشاشتين: ما يعمل الآن وما هو معروض للمستقبل. */
        private fun addIntro(ctx: Context) {
            val liveCount = ArabToolsCatalog.CATEGORIES.sumOf { cat ->
                cat.groups.sumOf { g -> g.defs.count { it.impl == Impl.LIVE } }
            }
            val totalCount = ArabToolsCatalog.CATEGORIES.sumOf { cat ->
                cat.groups.sumOf { g -> g.defs.size }
            }
            preferenceScreen.addPreference(
                Preference(ctx).apply {
                    key = "art_intro"
                    isSelectable = false
                    title = "قائمة الإعدادات العربية"
                    summary = "$totalCount إعداد في ${ArabToolsCatalog.CATEGORIES.size} تصنيف — " +
                        "يعمل منها الآن $liveCount. " +
                        "الإعدادات التي عليها «$SOON» معروضة بالعربية لكنها لم تُنفَّذ بعد. " +
                        "ادخل تصنيفاً من الأسفل لترى إعداداته."
                }
            )
        }

        // ------------------------------------------------------------ الكتالوج

        /** يرسم كل تصنيفات الكتالوج، كل تصنيف في شاشة مستقلّة (تدرّج لا قائمة واحدة طويلة). */
        private fun addCatalog(ctx: Context) {
            ArabToolsCatalog.CATEGORIES.forEachIndexed { index, cat ->
                val screen = preferenceManager.createPreferenceScreen(ctx).apply {
                    key = "art_cat_$index"
                    title = cat.title
                    val live = cat.groups.sumOf { g -> g.defs.count { it.impl == Impl.LIVE } }
                    val total = cat.groups.sumOf { g -> g.defs.size }
                    summary = "$total إعداد — يعمل منها الآن $live"
                }
                renderCategory(ctx, screen, cat, index)
                preferenceScreen.addPreference(screen)
            }
        }

        /** يرسم تصنيفاً واحداً: كل مجموعة `PreferenceCategory` وكل تعريف عنصرُه المناسب. */
        private fun renderCategory(
            ctx: Context,
            screen: PreferenceScreen,
            cat: ArabToolsCatalog.Category,
            index: Int
        ) {
            usedKeys.clear()
            cat.groups.forEachIndexed { gIndex, group ->
                val groupCategory = PreferenceCategory(ctx).apply {
                    key = "art_g_${index}_$gIndex"
                    title = group.title
                }
                screen.addPreference(groupCategory)
                group.defs.forEach { def -> addDef(ctx, groupCategory, def) }

                // تبويبات شريط التنقل: مفاتيحها الحيّة لا تأتي من الكتالوج.
                if (cat.title == "الرئيسية والتنقل" && group.title == "إخفاء تبويبات محددة") {
                    addNavTabSwitches(ctx, groupCategory)
                }
            }
        }

        /** يضيف عنصراً واحداً بالشكل المناسب لنوعه، وما لم يُنفَّذ يُعرض بلا تفعيل. */
        private fun addDef(ctx: Context, parent: PreferenceCategory, def: ArabToolsCatalog.Def) {
            if (!usedKeys.add(def.key)) return

            if (def.impl == Impl.SOON) {
                parent.addPreference(
                    Preference(ctx).apply {
                        key = def.key
                        title = def.title
                        summary = SOON + def.summary
                        isSelectable = false
                    }
                )
                return
            }

            when (def.kind) {
                Kind.LIST -> parent.addPreference(
                    ListPreference(ctx).apply {
                        key = def.key
                        title = def.title
                        summary = def.summary
                        summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                        entryValues = def.values?.toTypedArray() ?: emptyArray()
                        entries = def.entries?.toTypedArray() ?: emptyArray()
                        (def.default as? String)?.let { setDefaultValue(it) }
                    }
                )

                Kind.TEXT -> parent.addPreference(
                    EditTextPreference(ctx).apply {
                        key = def.key
                        title = def.title
                        summary = def.summary
                        (def.default as? String)?.let { setDefaultValue(it) }
                    }
                )

                Kind.SWITCH -> parent.addPreference(
                    SwitchPreferenceCompat(ctx).apply {
                        key = def.key
                        title = def.title
                        summary = def.summary
                        (def.default as? Boolean)?.let { setDefaultValue(it) }
                    }
                )

                Kind.INFO -> parent.addPreference(
                    Preference(ctx).apply {
                        key = def.key
                        title = def.title
                        summary = def.summary
                        isSelectable = false
                    }
                )
            }
        }

        // ------------------------------------------------------------ تبويبات (حيّة)

        /** مفاتيح إخفاء التبويبات — تعمل فعلاً، وتبويب «الإعدادات» محميّ. */
        private fun addNavTabSwitches(ctx: Context, parent: PreferenceCategory) {
            // «إخفاء الشريط كاملاً» يأتي من الكتالوج (LIVE) في مجموعة شريط التنقل،
            // فلا نُكرّره هنا — تكرار المفتاح نفسه يفسد الشاشة.

            for (tab in ArabToolsPrefs.HIDEABLE_TABS) {
                if (!usedKeys.add(tab.key)) continue
                parent.addPreference(
                    SwitchPreferenceCompat(ctx).apply {
                        key = tab.key
                        title = "إخفاء «${tab.label}»"
                        summary = "قد يعود التطبيق إلى التبويب الأول بعد التغيير"
                        setDefaultValue(false)
                    }
                )
            }

            parent.addPreference(
                Preference(ctx).apply {
                    key = "art_nav_settings_protected"
                    title = "تبويب «الإعدادات» لا يُخفى"
                    summary = "لأنه المنفذ الوحيد إلى إعدادات التطبيق وإعدادات هذه الإضافة — " +
                        "إخفاؤه يمنعك من إعادة أي شيء"
                    isSelectable = false
                }
            )
        }

        // ------------------------------------------------------------ الأدوات

        /** إعادة الضبط والتشخيص — هذه تعمل كلها الآن. */
        private fun addTools(ctx: Context) {
            val category = PreferenceCategory(ctx).apply {
                key = "art_tools"
                title = "الأدوات (تعمل الآن)"
            }
            preferenceScreen.addPreference(category)

            category.addPreference(
                Preference(ctx).apply {
                    key = "art_diagnose"
                    title = "تشخيص سريع"
                    summary = "يعرض هل المحرّك يعمل ومعرّفات التطبيق المطلوبة — للاستعمال بلا كبل"
                    setOnPreferenceClickListener {
                        AlertDialog.Builder(ctx)
                            .setTitle("تشخيص «أدوات عربية»")
                            .setMessage(ArabToolsStatus.report(ctx, prefs))
                            .setPositiveButton("حسناً", null)
                            .show()
                        true
                    }
                }
            )

            category.addPreference(
                Preference(ctx).apply {
                    key = "art_reset"
                    title = "إعادة ضبط إعدادات الإضافة"
                    summary = "يعيد كل الخيارات — العاملة والمعروضة — إلى الافتراضي"
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
