package com.dramamondo.plugin

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

class DramaMondoSettingsBottomSheet(private val prefs: SharedPreferences) : BottomSheetDialogFragment() {

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
        const val PREFS_NAME = "DramaMondo"
        const val KEY_SHOW_PLATFORMS = "dm_show_platforms"
        const val KEY_ROW_LIMIT = "dm_row_limit"
        const val KEY_SUB_LANGS = "dm_sub_langs"
        const val KEY_PROBE_LINKS = "dm_probe_links"
        const val KEY_QUALITY_ORDER = "dm_quality_order"

        fun platformKey(slug: String) = "dm_platform_$slug"

        fun show(fm: FragmentManager, prefs: SharedPreferences) {
            DramaMondoSettingsBottomSheet(prefs).show(fm, "dm_settings")
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

            // فحص السيرفرات: هو ما يرتّب «الأكثر استجابة» أولًا ويُظهر الحجم
            // والدقّة، وهو أيضًا ما يُسقط الروابط الميّتة قبل اختيار اللاعب
            // (‎2004‎ = انتهى التوقيع أو الرابط). إطفاؤه = بدء أسرع بلا فحص.
            val probePref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_PROBE_LINKS
                title = "فحص السيرفرات قبل التشغيل"
                summary = "يرتّب الأسرع أولًا ويُظهر الدقّة والحجم ويُسقط الميّت (يُبطئ البدء قليلًا)"
                setDefaultValue(true)
            }
            playbackCategory.addPreference(probePref)

            val orderPref = ListPreference(ctx).apply {
                key = KEY_QUALITY_ORDER
                title = "ترتيب الجودات"
                summary = "الأكثر استجابة = ترتيب الروابط بزمن الاستجابة المقيس"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("fastest", "desc", "asc")
                entries = arrayOf(
                    "الأكثر استجابة أولًا (افتراضي)",
                    "الأعلى جودة أولًا",
                    "الأدنى جودة أولًا"
                )
                setDefaultValue("fastest")
            }
            playbackCategory.addPreference(orderPref)

            val platCat = PreferenceCategory(ctx)
            platCat.title = "أقسام المنصات في الواجهة الرئيسية"
            preferenceScreen.addPreference(platCat)

            val showPlatformsPref = SwitchPreferenceCompat(ctx).apply {
                key = KEY_SHOW_PLATFORMS
                title = "المنصات كلها"
                summary = "ألغِ وسمها لإخفاء صفوف كل المنصات دفعةً واحدة"
                setDefaultValue(true)
            }
            platCat.addPreference(showPlatformsPref)

            // مفتاح مستقل لكل منصّة من platforms.html — يُخفى صفّها في الواجهة الرئيسية
            DM_PLATFORMS.forEach { (slug, label) ->
                platCat.addPreference(SwitchPreferenceCompat(ctx).apply {
                    key = platformKey(slug)
                    title = label
                    summary = "إخفاء/إظهار صفّ $label"
                    setDefaultValue(true)
                })
            }

            val rowLimitPref = ListPreference(ctx).apply {
                key = KEY_ROW_LIMIT
                title = "عدد صفوف المنصات المعروضة"
                summary = "أقل صفوف = ظهور أسرع للواجهة الرئيسية"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                entryValues = arrayOf("all", "8", "6", "4", "2")
                entries = arrayOf(
                    "كل المنصات (افتراضي)",
                    "أول 8 منصات",
                    "أول 6 منصات",
                    "أول 4 منصات",
                    "أول منصّتين"
                )
                setDefaultValue("all")
            }
            platCat.addPreference(rowLimitPref)

            val resetPref = Preference(ctx).apply {
                key = "dm_reset_prefs"
                title = "إعادة الضبط الافتراضي"
                summary = "يعيد الخيارات أعلاه للافتراضي"
                setOnPreferenceClickListener {
                    prefs.edit().apply {
                        remove(KEY_SHOW_PLATFORMS)
                        remove(KEY_ROW_LIMIT)
                        remove(KEY_SUB_LANGS)
                        remove(KEY_PROBE_LINKS)
                        remove(KEY_QUALITY_ORDER)
                        DM_PLATFORMS.forEach { (slug, _) -> remove(platformKey(slug)) }
                    }.apply()
                    Toast.makeText(ctx, "تمت إعادة الضبط", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            preferenceScreen.addPreference(resetPref)
        }
    }
}

typealias DramaMondoSettings = DramaMondoSettingsBottomSheet
